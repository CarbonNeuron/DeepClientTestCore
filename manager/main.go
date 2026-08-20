package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const managedLabel = "dev.deepclient.managed"

//go:embed static/* openapi.yaml
var staticFiles embed.FS

type config struct {
	listenAddr  string
	token       string
	network     string
	images      map[string]string
	idleTTL     time.Duration
	cleanup     time.Duration
	shmBytes    int64
	memory      int64
	nanoCPUs    int64
	gpuMode     string
	hsaOverride string
	mesaAdapter string
}

type session struct {
	ID            string    `json:"id"`
	Minecraft     string    `json:"minecraft_version"`
	Username      string    `json:"username"`
	GPU           bool      `json:"gpu"`
	ContainerID   string    `json:"-"`
	ContainerName string    `json:"-"`
	ClientToken   string    `json:"-"`
	ViewerToken   string    `json:"-"`
	CreatedAt     time.Time `json:"created_at"`
	LastActive    time.Time `json:"last_active_at"`
	Active        int       `json:"-"`
	Deleting      bool      `json:"-"`
}

type sessionView struct {
	ID                string    `json:"id"`
	Minecraft         string    `json:"minecraft_version"`
	Username          string    `json:"username"`
	GPU               bool      `json:"gpu"`
	Status            string    `json:"status"`
	CreatedAt         time.Time `json:"created_at"`
	LastActive        time.Time `json:"last_active_at"`
	ExpiresAt         time.Time `json:"expires_at"`
	ActiveConnections int       `json:"active_connections"`
	ControlURL        string    `json:"control_url"`
	ScreenshotURL     string    `json:"screenshot_url"`
}

type manager struct {
	cfg      config
	docker   *dockerClient
	mu       sync.Mutex
	sessions map[string]*session
	static   http.Handler
}

type dockerClient struct{ http *http.Client }

type dockerError struct {
	status int
	body   string
}

func (e *dockerError) Error() string { return fmt.Sprintf("Docker returned %d: %s", e.status, e.body) }

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatal(err)
	}
	docker := newDockerClient()
	if err := ensureNetwork(context.Background(), docker, cfg.network); err != nil {
		log.Fatalf("prepare session network: %v", err)
	}
	staticRoot, err := fs.Sub(staticFiles, "static")
	if err != nil {
		log.Fatal(err)
	}
	m := &manager{cfg: cfg, docker: docker, sessions: map[string]*session{}, static: http.FileServer(http.FS(staticRoot))}
	if err := m.restore(context.Background()); err != nil {
		log.Printf("session restore warning: %v", err)
	}
	go m.cleanupLoop()
	server := &http.Server{Addr: cfg.listenAddr, Handler: m, ReadHeaderTimeout: 10 * time.Second}
	log.Printf("Deep Client manager listening on %s; versions=%v; idle_ttl=%s; gpu_mode=%s", cfg.listenAddr, sortedKeys(cfg.images), cfg.idleTTL, cfg.gpuMode)
	log.Fatal(server.ListenAndServe())
}

func loadConfig() (config, error) {
	c := config{
		listenAddr:  env("DEEPCLIENT_MANAGER_BIND", ":8080"),
		token:       os.Getenv("DEEPCLIENT_MANAGER_TOKEN"),
		network:     env("DEEPCLIENT_SESSION_NETWORK", "deepclient-sessions"),
		idleTTL:     durationEnv("DEEPCLIENT_SESSION_IDLE_TTL", 30*time.Minute),
		cleanup:     durationEnv("DEEPCLIENT_CLEANUP_INTERVAL", 30*time.Second),
		shmBytes:    int64Env("DEEPCLIENT_SESSION_SHM_BYTES", 1<<30),
		memory:      int64Env("DEEPCLIENT_SESSION_MEMORY_BYTES", 4<<30),
		nanoCPUs:    int64Env("DEEPCLIENT_SESSION_NANO_CPUS", 0),
		gpuMode:     strings.ToLower(env("DEEPCLIENT_GPU_MODE", "none")),
		hsaOverride: strings.TrimSpace(os.Getenv("DEEPCLIENT_HSA_OVERRIDE_GFX_VERSION")),
		mesaAdapter: env("DEEPCLIENT_MESA_D3D12_ADAPTER_NAME", "AMD"),
	}
	if c.token == "" {
		return c, errors.New("DEEPCLIENT_MANAGER_TOKEN is required")
	}
	if c.idleTTL < time.Minute {
		return c, errors.New("DEEPCLIENT_SESSION_IDLE_TTL must be at least 1m")
	}
	if c.cleanup < time.Second {
		return c, errors.New("DEEPCLIENT_CLEANUP_INTERVAL must be at least 1s")
	}
	switch c.gpuMode {
	case "none", "nvidia", "dri", "wsl-dxg":
	default:
		return c, errors.New("DEEPCLIENT_GPU_MODE must be none, nvidia, dri, or wsl-dxg")
	}
	c.images = parseImages(env("DEEPCLIENT_SESSION_IMAGES", "1.21.1=deep-client-test-core:1.21.1"))
	if len(c.images) == 0 {
		return c, errors.New("DEEPCLIENT_SESSION_IMAGES must contain at least one version=image mapping")
	}
	return c, nil
}

func parseImages(value string) map[string]string {
	result := map[string]string{}
	for _, pair := range strings.Split(value, ",") {
		parts := strings.SplitN(strings.TrimSpace(pair), "=", 2)
		if len(parts) == 2 && strings.TrimSpace(parts[0]) != "" && strings.TrimSpace(parts[1]) != "" {
			result[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
		}
	}
	return result
}

func newDockerClient() *dockerClient {
	transport := &http.Transport{
		DisableCompression: true,
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "unix", "/var/run/docker.sock")
		},
	}
	return &dockerClient{http: &http.Client{Transport: transport, Timeout: 30 * time.Second}}
}

func (d *dockerClient) do(ctx context.Context, method, path string, body any, out any) error {
	var reader io.Reader
	if body != nil {
		pipeReader, pipeWriter := io.Pipe()
		reader = pipeReader
		go func() {
			err := json.NewEncoder(pipeWriter).Encode(body)
			_ = pipeWriter.CloseWithError(err)
		}()
	}
	req, err := http.NewRequestWithContext(ctx, method, "http://docker"+path, reader)
	if err != nil {
		return err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := d.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		message, _ := io.ReadAll(io.LimitReader(resp.Body, 8192))
		return &dockerError{status: resp.StatusCode, body: strings.TrimSpace(string(message))}
	}
	if out != nil && resp.StatusCode != http.StatusNoContent {
		return json.NewDecoder(resp.Body).Decode(out)
	}
	return nil
}

func ensureNetwork(ctx context.Context, docker *dockerClient, name string) error {
	err := docker.do(ctx, http.MethodGet, "/networks/"+url.PathEscape(name), nil, nil)
	if err == nil {
		return nil
	}
	var de *dockerError
	if !errors.As(err, &de) || de.status != http.StatusNotFound {
		return err
	}
	return docker.do(ctx, http.MethodPost, "/networks/create", map[string]any{"Name": name, "Driver": "bridge", "CheckDuplicate": true}, nil)
}

func (m *manager) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "no-referrer")
	if r.URL.Path == "/v1/health" {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "service": "deepclient-manager", "time": time.Now().UTC()})
		return
	}
	if r.URL.Path == "/openapi.yaml" {
		body, _ := staticFiles.ReadFile("openapi.yaml")
		w.Header().Set("Content-Type", "application/yaml; charset=utf-8")
		_, _ = w.Write(body)
		return
	}
	if !strings.HasPrefix(r.URL.Path, "/v1/") {
		m.static.ServeHTTP(w, r)
		return
	}
	if strings.Contains(r.URL.Path, "/viewer/") || strings.Contains(r.URL.Path, "/preview/") {
		m.handleTicketProxy(w, r)
		return
	}
	if !m.authenticated(r) {
		writeError(w, http.StatusUnauthorized, "A valid manager Bearer token is required")
		return
	}
	switch r.URL.Path {
	case "/v1/versions":
		if r.Method != http.MethodGet {
			writeError(w, http.StatusMethodNotAllowed, "Expected GET")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"minecraft_versions": sortedKeys(m.cfg.images), "gpu_mode": m.cfg.gpuMode, "idle_ttl_seconds": int(m.cfg.idleTTL.Seconds())})
	case "/v1/sessions":
		if r.Method == http.MethodGet {
			m.listSessions(w, r)
		} else if r.Method == http.MethodPost {
			m.createSession(w, r)
		} else {
			writeError(w, http.StatusMethodNotAllowed, "Expected GET or POST")
		}
	default:
		m.handleSession(w, r)
	}
}

func (m *manager) authenticated(r *http.Request) bool {
	provided := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	return len(provided) == len(m.cfg.token) && subtle.ConstantTimeCompare([]byte(provided), []byte(m.cfg.token)) == 1
}

func (m *manager) listSessions(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	items := make([]*session, 0, len(m.sessions))
	for _, s := range m.sessions {
		items = append(items, s)
	}
	m.mu.Unlock()
	sort.Slice(items, func(i, j int) bool { return items[i].CreatedAt.Before(items[j].CreatedAt) })
	views := make([]sessionView, len(items))
	var wait sync.WaitGroup
	for index, s := range items {
		wait.Add(1)
		go func() {
			defer wait.Done()
			views[index] = m.view(r.Context(), s)
		}()
	}
	wait.Wait()
	writeJSON(w, http.StatusOK, map[string]any{"sessions": views, "count": len(views)})
}

var usernamePattern = regexp.MustCompile(`^[A-Za-z0-9_]{3,16}$`)

func (m *manager) createSession(w http.ResponseWriter, r *http.Request) {
	var request struct {
		Minecraft string `json:"minecraft_version"`
		Username  string `json:"username"`
		GPU       bool   `json:"gpu"`
	}
	decoder := json.NewDecoder(io.LimitReader(r.Body, 65536))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&request); err != nil {
		writeError(w, http.StatusBadRequest, "Request body must be a valid session object")
		return
	}
	image, ok := m.cfg.images[request.Minecraft]
	if !ok {
		writeError(w, http.StatusBadRequest, "Unsupported minecraft_version")
		return
	}
	if request.GPU && m.cfg.gpuMode == "none" {
		writeError(w, http.StatusBadRequest, "GPU sessions are disabled by this manager")
		return
	}
	id := randomToken(10)
	if request.Username == "" {
		request.Username = "Agent" + strings.ToUpper(id[:6])
	}
	if !usernamePattern.MatchString(request.Username) {
		writeError(w, http.StatusBadRequest, "username must be 3-16 letters, digits, or underscores")
		return
	}
	now := time.Now().UTC()
	s := &session{ID: id, Minecraft: request.Minecraft, Username: request.Username, GPU: request.GPU,
		ContainerName: "deepclient-" + id, ClientToken: randomToken(32), ViewerToken: randomToken(32), CreatedAt: now, LastActive: now}
	containerID, err := m.createContainer(r.Context(), s, image)
	if err != nil {
		writeError(w, http.StatusBadGateway, "Could not create client container: "+err.Error())
		return
	}
	s.ContainerID = containerID
	if err := m.docker.do(r.Context(), http.MethodPost, "/containers/"+containerID+"/start", nil, nil); err != nil {
		_ = m.removeContainer(context.Background(), s)
		writeError(w, http.StatusBadGateway, "Could not start client container: "+err.Error())
		return
	}
	m.mu.Lock()
	m.sessions[s.ID] = s
	m.mu.Unlock()
	writeJSON(w, http.StatusCreated, m.view(r.Context(), s))
}

func (m *manager) createContainer(ctx context.Context, s *session, image string) (string, error) {
	environment := []string{
		"DEEPCLIENT_BIND=0.0.0.0", "DEEPCLIENT_PORT=8080", "DEEPCLIENT_TOKEN=" + s.ClientToken,
		"DEEPCLIENT_STREAM_FPS=4", "MINECRAFT_WIDTH=1280", "MINECRAFT_HEIGHT=720", "MINECRAFT_USERNAME=" + s.Username,
	}
	host := map[string]any{"NetworkMode": m.cfg.network, "ShmSize": m.cfg.shmBytes, "Memory": m.cfg.memory, "NanoCpus": m.cfg.nanoCPUs}
	if s.GPU {
		environment = append(environment, "LIBGL_ALWAYS_SOFTWARE=0")
		switch m.cfg.gpuMode {
		case "nvidia":
			host["DeviceRequests"] = []any{map[string]any{"Driver": "nvidia", "Count": -1, "Capabilities": [][]string{{"gpu", "graphics", "utility", "display"}}}}
			environment = append(environment, "NVIDIA_VISIBLE_DEVICES=all", "NVIDIA_DRIVER_CAPABILITIES=graphics,utility,compute,display")
		case "dri":
			host["Devices"] = []any{map[string]any{"PathOnHost": "/dev/dri", "PathInContainer": "/dev/dri", "CgroupPermissions": "rwm"}}
		case "wsl-dxg":
			host["Devices"] = []any{map[string]any{"PathOnHost": "/dev/dxg", "PathInContainer": "/dev/dxg", "CgroupPermissions": "rwm"}}
			host["Binds"] = []string{
				"/usr/lib/wsl:/usr/lib/wsl:ro",
				"/mnt/host/wslg/.X11-unix:/tmp/.X11-unix:rw",
				"/mnt/host/wslg/runtime-dir:/mnt/wslg/runtime-dir:rw",
				"/mnt/host/wslg/PulseServer:/mnt/wslg/PulseServer:rw",
			}
			environment = append(environment, "DEEPCLIENT_DISPLAY_BACKEND=wslg", "DISPLAY=:0",
				"XDG_RUNTIME_DIR=/mnt/wslg/runtime-dir",
				"PULSE_SERVER=unix:/mnt/wslg/PulseServer", "LD_LIBRARY_PATH=/usr/lib/wsl/lib",
				"LIBGL_ALWAYS_SOFTWARE=0", "HSA_ENABLE_DXG_DETECTION=1", "GALLIUM_DRIVER=d3d12",
				"MESA_D3D12_DEFAULT_ADAPTER_NAME="+m.cfg.mesaAdapter)
			if m.cfg.hsaOverride != "" {
				environment = append(environment, "HSA_OVERRIDE_GFX_VERSION="+m.cfg.hsaOverride)
			}
		}
	}
	labels := map[string]string{
		managedLabel: "true", "dev.deepclient.session": s.ID, "dev.deepclient.minecraft": s.Minecraft,
		"dev.deepclient.username": s.Username, "dev.deepclient.client-token": s.ClientToken,
		"dev.deepclient.viewer-token": s.ViewerToken, "dev.deepclient.created": s.CreatedAt.Format(time.RFC3339Nano),
		"dev.deepclient.gpu": strconv.FormatBool(s.GPU),
	}
	body := map[string]any{"Image": image, "Env": environment, "Labels": labels, "HostConfig": host}
	var result struct {
		ID string `json:"Id"`
	}
	path := "/containers/create?name=" + url.QueryEscape(s.ContainerName)
	if err := m.docker.do(ctx, http.MethodPost, path, body, &result); err != nil {
		return "", err
	}
	return result.ID, nil
}

func (m *manager) handleSession(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/v1/sessions/")
	parts := strings.SplitN(rest, "/", 2)
	if len(parts) == 0 || parts[0] == "" {
		writeError(w, http.StatusNotFound, "Session not found")
		return
	}
	m.mu.Lock()
	s := m.sessions[parts[0]]
	m.mu.Unlock()
	if s == nil {
		writeError(w, http.StatusNotFound, "Session not found")
		return
	}
	if len(parts) == 1 {
		switch r.Method {
		case http.MethodGet:
			writeJSON(w, http.StatusOK, m.view(r.Context(), s))
		case http.MethodDelete:
			if err := m.destroy(r.Context(), s); err != nil {
				writeError(w, http.StatusBadGateway, err.Error())
				return
			}
			w.WriteHeader(http.StatusNoContent)
		default:
			writeError(w, http.StatusMethodNotAllowed, "Expected GET or DELETE")
		}
		return
	}
	switch parts[1] {
	case "viewer-ticket":
		if r.Method != http.MethodPost {
			writeError(w, http.StatusMethodNotAllowed, "Expected POST")
			return
		}
		path := fmt.Sprintf("/v1/sessions/%s/viewer/vnc.html?autoconnect=true&resize=scale&path=%s&token=%s",
			s.ID, url.QueryEscape("v1/sessions/"+s.ID+"/viewer/websockify"), url.QueryEscape(s.ViewerToken))
		preview := fmt.Sprintf("/v1/sessions/%s/preview/stream.mjpeg?token=%s", s.ID, url.QueryEscape(s.ViewerToken))
		writeJSON(w, http.StatusOK, map[string]string{"viewer_url": path, "stream_url": preview})
	default:
		if parts[1] == "client" || strings.HasPrefix(parts[1], "client/") {
			m.proxy(w, r, s, "client")
			return
		}
		writeError(w, http.StatusNotFound, "Session route not found")
	}
}

func (m *manager) handleTicketProxy(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/v1/sessions/")
	parts := strings.SplitN(rest, "/", 2)
	if len(parts) != 2 {
		writeError(w, http.StatusNotFound, "Ticketed route not found")
		return
	}
	kind := ""
	if parts[1] == "viewer" || strings.HasPrefix(parts[1], "viewer/") {
		kind = "viewer"
	} else if parts[1] == "preview" || strings.HasPrefix(parts[1], "preview/") {
		kind = "preview"
	} else {
		writeError(w, http.StatusNotFound, "Ticketed route not found")
		return
	}
	m.mu.Lock()
	s := m.sessions[parts[0]]
	m.mu.Unlock()
	if s == nil {
		writeError(w, http.StatusNotFound, "Session not found")
		return
	}
	cookieName := "deepclient_viewer_" + s.ID
	provided := r.URL.Query().Get("token")
	if secureEqual(provided, s.ViewerToken) {
		http.SetCookie(w, &http.Cookie{Name: cookieName, Value: s.ViewerToken, Path: "/v1/sessions/" + s.ID + "/", HttpOnly: true, SameSite: http.SameSiteStrictMode})
		query := r.URL.Query()
		query.Del("token")
		r.URL.RawQuery = query.Encode()
		http.Redirect(w, r, r.URL.String(), http.StatusSeeOther)
		return
	}
	cookie, _ := r.Cookie(cookieName)
	if cookie == nil || !secureEqual(cookie.Value, s.ViewerToken) {
		writeError(w, http.StatusUnauthorized, "Viewer ticket is missing or invalid")
		return
	}
	m.proxy(w, r, s, kind)
}

func (m *manager) proxy(w http.ResponseWriter, r *http.Request, s *session, kind string) {
	if !m.beginUse(s) {
		writeError(w, http.StatusGone, "Session is being destroyed")
		return
	}
	finished := make(chan struct{})
	var endOnce sync.Once
	end := func() { endOnce.Do(func() { m.endUse(s) }) }
	go func() {
		select {
		case <-r.Context().Done():
			end()
		case <-finished:
		}
	}()
	defer func() {
		close(finished)
		end()
	}()
	port := 8080
	prefix := "/v1/sessions/" + s.ID + "/client"
	if kind == "viewer" {
		port = 6080
		prefix = "/v1/sessions/" + s.ID + "/viewer"
	} else if kind == "preview" {
		prefix = "/v1/sessions/" + s.ID + "/preview"
	}
	target, _ := url.Parse(fmt.Sprintf("http://%s:%d", s.ContainerName, port))
	proxy := httputil.NewSingleHostReverseProxy(target)
	original := proxy.Director
	proxy.Director = func(req *http.Request) {
		original(req)
		req.URL.Path = strings.TrimPrefix(r.URL.Path, prefix)
		if kind == "preview" {
			req.URL.Path = "/v1/stream.mjpeg"
		} else if req.URL.Path == "" {
			req.URL.Path = "/"
		}
		req.Host = target.Host
		if kind != "viewer" {
			req.Header.Set("Authorization", "Bearer "+s.ClientToken)
		} else {
			req.Header.Del("Authorization")
		}
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, _ *http.Request, err error) {
		writeError(w, http.StatusBadGateway, "Client is not ready: "+err.Error())
	}
	proxy.ServeHTTP(w, r)
}

func (m *manager) beginUse(s *session) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if current := m.sessions[s.ID]; current != s || s.Deleting {
		return false
	}
	s.Active++
	s.LastActive = time.Now().UTC()
	return true
}

func (m *manager) endUse(s *session) {
	m.mu.Lock()
	if s.Active > 0 {
		s.Active--
	}
	s.LastActive = time.Now().UTC()
	m.mu.Unlock()
}

func (m *manager) view(ctx context.Context, s *session) sessionView {
	m.mu.Lock()
	last, active := s.LastActive, s.Active
	m.mu.Unlock()
	status := "stopped"
	var inspect struct {
		State struct {
			Running bool   `json:"Running"`
			Status  string `json:"Status"`
		} `json:"State"`
	}
	if err := m.docker.do(ctx, http.MethodGet, "/containers/"+s.ContainerID+"/json", nil, &inspect); err == nil {
		status = inspect.State.Status
		if inspect.State.Running && clientHealthy(ctx, s.ContainerName) {
			status = "ready"
		} else if inspect.State.Running {
			status = "starting"
		}
	}
	base := "/v1/sessions/" + s.ID + "/client"
	return sessionView{ID: s.ID, Minecraft: s.Minecraft, Username: s.Username, GPU: s.GPU, Status: status,
		CreatedAt: s.CreatedAt, LastActive: last, ExpiresAt: last.Add(m.cfg.idleTTL), ActiveConnections: active,
		ControlURL: base, ScreenshotURL: base + "/v1/screenshot"}
}

var internalHTTP = &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 800 * time.Millisecond}

func clientHealthy(ctx context.Context, name string) bool {
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+name+":8080/v1/health", nil)
	resp, err := internalHTTP.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == http.StatusOK
}

func (m *manager) cleanupLoop() {
	ticker := time.NewTicker(m.cfg.cleanup)
	defer ticker.Stop()
	for now := range ticker.C {
		m.mu.Lock()
		stale := make([]*session, 0)
		for _, s := range m.sessions {
			if sessionExpired(s, now, m.cfg.idleTTL) {
				s.Deleting = true
				stale = append(stale, s)
			}
		}
		m.mu.Unlock()
		for _, s := range stale {
			err := m.removeContainer(context.Background(), s)
			var de *dockerError
			if err != nil && (!errors.As(err, &de) || de.status != http.StatusNotFound) {
				m.mu.Lock()
				s.Deleting = false
				s.LastActive = time.Now().UTC()
				m.mu.Unlock()
				log.Printf("cleanup session %s: %v", s.ID, err)
			} else {
				m.mu.Lock()
				delete(m.sessions, s.ID)
				m.mu.Unlock()
				log.Printf("cleaned idle session %s", s.ID)
			}
		}
	}
}

func sessionExpired(s *session, now time.Time, ttl time.Duration) bool {
	return s.Active == 0 && now.Sub(s.LastActive) >= ttl
}

func (m *manager) destroy(ctx context.Context, s *session) error {
	m.mu.Lock()
	s.Deleting = true
	m.mu.Unlock()
	err := m.removeContainer(ctx, s)
	var de *dockerError
	if err != nil && (!errors.As(err, &de) || de.status != http.StatusNotFound) {
		m.mu.Lock()
		s.Deleting = false
		m.mu.Unlock()
		return err
	}
	m.mu.Lock()
	delete(m.sessions, s.ID)
	m.mu.Unlock()
	return nil
}

func (m *manager) removeContainer(ctx context.Context, s *session) error {
	return m.docker.do(ctx, http.MethodDelete, "/containers/"+s.ContainerID+"?force=1&v=1", nil, nil)
}

func (m *manager) restore(ctx context.Context) error {
	filters, _ := json.Marshal(map[string][]string{"label": {managedLabel + "=true"}})
	var containers []struct {
		ID     string            `json:"Id"`
		Names  []string          `json:"Names"`
		Labels map[string]string `json:"Labels"`
	}
	if err := m.docker.do(ctx, http.MethodGet, "/containers/json?all=1&filters="+url.QueryEscape(string(filters)), nil, &containers); err != nil {
		return err
	}
	for _, container := range containers {
		labels := container.Labels
		created, err := time.Parse(time.RFC3339Nano, labels["dev.deepclient.created"])
		if err != nil {
			created = time.Now().UTC()
		}
		name := "deepclient-" + labels["dev.deepclient.session"]
		if len(container.Names) > 0 {
			name = strings.TrimPrefix(container.Names[0], "/")
		}
		s := &session{ID: labels["dev.deepclient.session"], Minecraft: labels["dev.deepclient.minecraft"], Username: labels["dev.deepclient.username"],
			GPU: labels["dev.deepclient.gpu"] == "true", ContainerID: container.ID, ContainerName: name,
			ClientToken: labels["dev.deepclient.client-token"], ViewerToken: labels["dev.deepclient.viewer-token"], CreatedAt: created, LastActive: created}
		if s.ID != "" && s.ClientToken != "" && s.ViewerToken != "" {
			m.sessions[s.ID] = s
		}
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]any{"error": http.StatusText(status), "message": message})
}

func randomToken(bytes int) string {
	value := make([]byte, bytes)
	if _, err := rand.Read(value); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(value)
}

func secureEqual(a, b string) bool {
	return len(a) == len(b) && subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

func sortedKeys(values map[string]string) []string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func durationEnv(name string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func int64Env(name string, fallback int64) int64 {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return fallback
	}
	return parsed
}
