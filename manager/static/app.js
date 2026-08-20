const state = { token: sessionStorage.getItem("deepclient-token") || "", sessions: new Map(), timer: null };
const $ = selector => document.querySelector(selector);

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set("Authorization", `Bearer ${state.token}`);
  if (options.body) headers.set("Content-Type", "application/json");
  const response = await fetch(path, { ...options, headers });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try { message = (await response.json()).message || message; } catch (_) {}
    throw new Error(message);
  }
  return response;
}

function setConnected(connected) {
  $("#login").hidden = connected;
  $("#dashboard").hidden = !connected;
  $("#connection-dot").classList.toggle("online", connected);
  $("#connection-text").textContent = connected ? "Manager connected" : "Signed out";
}

async function login(token) {
  state.token = token;
  const response = await api("/v1/versions");
  const info = await response.json();
  sessionStorage.setItem("deepclient-token", token);
  $("#version").replaceChildren(...info.minecraft_versions.map(version => new Option(version, version)));
  $("#gpu").disabled = info.gpu_mode === "none";
  $("#gpu").title = info.gpu_mode === "none" ? "GPU sessions are disabled" : `Manager GPU mode: ${info.gpu_mode}`;
  $("#ttl").textContent = `Idle cleanup: ${Math.round(info.idle_ttl_seconds / 60)} min · GPU: ${info.gpu_mode}`;
  setConnected(true);
  await refresh();
  clearInterval(state.timer);
  state.timer = setInterval(refresh, 2500);
}

async function refresh() {
  try {
    const response = await api("/v1/sessions");
    const data = await response.json();
    render(data.sessions);
    $("#notice").textContent = "";
  } catch (error) {
    $("#notice").textContent = error.message;
  }
}

function render(sessions) {
  const root = $("#sessions");
  const seen = new Set();
  for (const session of sessions) {
    seen.add(session.id);
    let card = state.sessions.get(session.id);
    if (!card) {
      card = $("#session-template").content.firstElementChild.cloneNode(true);
      card.dataset.id = session.id;
      root.append(card);
      state.sessions.set(session.id, card);
      card.querySelector('[data-action="delete"]').onclick = () => destroySession(session.id);
      card.querySelector('[data-action="viewer"]').onclick = () => openViewer(session.id);
      card.querySelector('[data-action="state"]').onclick = () => inspectState(session.id);
    }
    card.querySelector("h2").textContent = session.username;
    const status = card.querySelector(".status");
    status.textContent = session.status;
    status.className = `status ${session.status}`;
    card.querySelector('[data-field="version"]').textContent = session.minecraft_version;
    card.querySelector('[data-field="id"]').textContent = session.id;
    card.querySelector('[data-field="activity"]').textContent = new Date(session.last_active_at).toLocaleTimeString();
    card.querySelector('[data-field="gpu"]').textContent = session.gpu ? "GPU requested" : "Software";
    if (session.status === "ready") startStream(card, session.id);
  }
  for (const [id, card] of state.sessions) {
    if (!seen.has(id)) {
      card.remove();
      state.sessions.delete(id);
    }
  }
  $("#empty").hidden = sessions.length !== 0;
}

async function startStream(card, id) {
  if (card.dataset.streamStarted === "true") return;
  card.dataset.streamStarted = "true";
  try {
    const response = await api(`/v1/sessions/${id}/viewer-ticket`, { method: "POST" });
    const { stream_url } = await response.json();
    const image = card.querySelector("img");
    image.src = stream_url;
    image.style.display = "block";
    card.querySelector(".feed-state").style.display = "none";
    image.onerror = () => {
      image.style.display = "none";
      card.querySelector(".feed-state").style.display = "block";
      card.dataset.streamStarted = "false";
    };
  } catch (_) {
    card.querySelector(".feed-state").textContent = "Waiting for framebuffer…";
    card.dataset.streamStarted = "false";
  }
}

async function createSession(event) {
  event.preventDefault();
  const body = { minecraft_version: $("#version").value, gpu: $("#gpu").checked };
  if ($("#username").value) body.username = $("#username").value;
  try {
    await api("/v1/sessions", { method: "POST", body: JSON.stringify(body) });
    $("#username").value = "";
    await refresh();
  } catch (error) { $("#notice").textContent = error.message; }
}

async function destroySession(id) {
  if (!confirm(`Destroy session ${id}?`)) return;
  try { await api(`/v1/sessions/${id}`, { method: "DELETE" }); await refresh(); }
  catch (error) { $("#notice").textContent = error.message; }
}

async function openViewer(id) {
  try {
    const response = await api(`/v1/sessions/${id}/viewer-ticket`, { method: "POST" });
    const { viewer_url } = await response.json();
    window.open(viewer_url, `_deepclient_${id}`, "noopener");
  } catch (error) { $("#notice").textContent = error.message; }
}

async function inspectState(id) {
  try {
    const response = await api(`/v1/sessions/${id}/client/v1/state`);
    const value = await response.json();
    const popup = window.open("", `_deepclient_state_${id}`);
    popup.document.body.innerHTML = `<pre style="white-space:pre-wrap;font:13px monospace"></pre>`;
    popup.document.querySelector("pre").textContent = JSON.stringify(value, null, 2);
  } catch (error) { $("#notice").textContent = error.message; }
}

$("#login-form").onsubmit = async event => {
  event.preventDefault();
  try { await login($("#token").value); $("#login-error").textContent = ""; }
  catch (error) { $("#login-error").textContent = error.message; }
};
$("#create-form").onsubmit = createSession;
$("#refresh").onclick = refresh;
$("#logout").onclick = () => { sessionStorage.removeItem("deepclient-token"); state.token = ""; clearInterval(state.timer); setConnected(false); };
if (state.token) login(state.token).catch(() => setConnected(false));
