# Deep Client Test Core

[![Build](https://github.com/CarbonNeuron/DeepClientTestCore/actions/workflows/build.yml/badge.svg)](https://github.com/CarbonNeuron/DeepClientTestCore/actions/workflows/build.yml)

Deep Client Test Core is a Fabric 1.21.1 client mod that turns a real rendered Minecraft client into an HTTP-controlled server test fixture. It is aimed at LLMs and automated test harnesses developing Paper, Fabric, Velocity, or other custom server software.

The default deployment is Playwright-like: one session manager starts isolated Minecraft clients on demand, returns a session ID immediately, proxies their APIs and viewers, and removes them after an idle timeout. Its browser dashboard shows every active client as a live framebuffer grid.

The mod exposes semantic controls and structured observations while preserving the rendered game as visual ground truth:

- held and tick-bounded movement, attack, use, jump, sneak, sprint, and drop inputs;
- every standard vanilla keybinding, including inventory, swap-hands, pick-block, chat, tab list, perspective, and hotbar keys;
- absolute or relative camera control;
- direct item, block, entity, swing, drop, respawn, and continuous block-breaking actions;
- container operations for pickup, shift-click, hotbar swap, clone, throw, drag-craft, and pickup-all;
- screenshot-coordinate GUI click, move, drag, scroll, GLFW key, and text-entry events;
- chat, command, hotbar selection, connect, and disconnect actions;
- player, world, inventory, crosshair target, nearby-entity state, and a bounded chat/action-bar event journal;
- lossless PNG screenshots and a continuous multipart frame stream;
- token-authenticated REST access, with all game access marshalled onto the client thread;
- an Xvfb + noVNC container so humans can watch and intervene through a browser.
- Baritone 1.11.2 pathfinding exposed as observable, cancellable navigation jobs.

## Local development

Java 21 is required.

```powershell
./gradlew.bat runClient
```

By default the API listens only on `127.0.0.1:8080` and does not require a token. To expose it beyond loopback, set both variables:

```powershell
$env:DEEPCLIENT_BIND = '0.0.0.0'
$env:DEEPCLIENT_TOKEN = 'a-long-random-token'
./gradlew.bat runClient
```

## API examples

All endpoints except health use `Authorization: Bearer $DEEPCLIENT_TOKEN` when a token is configured.

```bash
# Observe the client
curl -H "Authorization: Bearer $DEEPCLIENT_TOKEN" http://localhost:8080/v1/state

# Lightweight position and the last 20 server/chat messages
curl -H "Authorization: Bearer $DEEPCLIENT_TOKEN" http://localhost:8080/v1/player/position
curl -H "Authorization: Bearer $DEEPCLIENT_TOKEN" "http://localhost:8080/v1/chat?limit=20"

# Poll efficiently by passing the latest_sequence returned by the prior request
curl -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  "http://localhost:8080/v1/chat?limit=100&after=42"

# Join an offline-mode development server
curl -X POST -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type":"connect","address":"localhost:25565"}' \
  http://localhost:8080/v1/actions

# Hold forward+sprint, then release every held control
curl -X POST -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type":"input","inputs":{"forward":true,"sprint":true}}' \
  http://localhost:8080/v1/actions
curl -X POST -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type":"release_all"}' http://localhost:8080/v1/actions

# Turn 45 degrees and attack for one game tick
curl -X POST -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type":"look","delta_yaw":45}' http://localhost:8080/v1/actions
curl -X POST -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type":"pulse","input":"attack"}' http://localhost:8080/v1/actions

# Start, inspect, and cancel a Baritone navigation job
curl -X POST -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"x":100,"y":64,"z":-25,"radius":1}' \
  http://localhost:8080/v1/navigation/walk-to
curl -H "Authorization: Bearer $DEEPCLIENT_TOKEN" http://localhost:8080/v1/navigation
curl -X DELETE -H "Authorization: Bearer $DEEPCLIENT_TOKEN" http://localhost:8080/v1/navigation

# Save the exact framebuffer or watch its stream
curl -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  http://localhost:8080/v1/screenshot -o screenshot.png
ffplay -f mpjpeg -headers "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  http://localhost:8080/v1/stream.mjpeg

# Record the stream as an MP4 (requires ffmpeg on the API caller)
ffmpeg -f mpjpeg -headers "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  -i http://localhost:8080/v1/stream.mjpeg \
  -c:v libx264 -pix_fmt yuv420p session.mp4
```

The complete machine-readable contract is in [`openapi.yaml`](openapi.yaml) and is also served by each client at `/openapi.yaml`.

Call `GET /v1/capabilities` to discover every supported named input and action at runtime. `GET /v1/screen` returns the current handler's `sync_id`, cursor stack, and all slot IDs; use those IDs with the `container_click` action rather than estimating pixel coordinates.

```bash
# Inspect a chest/inventory and shift-click slot 3
curl -H "Authorization: Bearer $DEEPCLIENT_TOKEN" http://localhost:8080/v1/screen
curl -X POST -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type":"container_click","slot":3,"button":0,"action":"quick_move"}' \
  http://localhost:8080/v1/actions

# Use a block face or attack a loaded entity ID from /v1/state
curl -X POST -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type":"use_block","x":10,"y":64,"z":5,"side":"up"}' \
  http://localhost:8080/v1/actions
```

## Session manager and dashboard

Copy `.env.example` to `.env`, replace the token, then build the versioned client image and start the manager:

```bash
docker compose up --build
```

Open `http://localhost:8080`, enter the configured token, and create clients from the dashboard. The dashboard shows each real framebuffer as a continuous MJPEG feed and opens an interactive noVNC view on demand.

The same lifecycle is available to agents:

```bash
# Create asynchronously; save the returned id
curl -X POST -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"minecraft_version":"1.21.1","username":"TestAgent"}' \
  http://localhost:8080/v1/sessions

# Poll until status is ready
curl -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  http://localhost:8080/v1/sessions/SESSION_ID

# Any client endpoint is available below /client
curl -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  http://localhost:8080/v1/sessions/SESSION_ID/client/v1/state

# Explicit cleanup (otherwise the idle TTL handles it)
curl -X DELETE -H "Authorization: Bearer $DEEPCLIENT_TOKEN" \
  http://localhost:8080/v1/sessions/SESSION_ID
```

Session creation accepts only Minecraft versions mapped by `DEEPCLIENT_SESSION_IMAGES`; callers cannot choose arbitrary Docker images. Add another prebuilt version with a comma-separated mapping such as `1.21.1=image-a,1.20.4=image-b`. The manager API is documented in [`manager/openapi.yaml`](manager/openapi.yaml); the per-client API remains in [`openapi.yaml`](openapi.yaml).

Proxied HTTP requests and open MJPEG/noVNC connections count as activity. The manager does not let a running stream expire underneath its caller. A manager restart discovers its labeled client containers again. Session filesystem data is ephemeral and removed with the container.

Compose binds the dashboard to host loopback. The manager protects client APIs with its bearer token and gives the browser a separate unguessable viewer ticket. Keep it loopback-only or put TLS and authentication in front of it before exposing it to a network.

The manager mounts `/var/run/docker.sock`, which is effectively root access to the Docker host. Run only trusted builds of the manager and never offer its API to untrusted users.

Inside a container, a server running on the Docker host is normally reachable as `host.docker.internal:25565` on Docker Desktop. Put the clients and server in the same Compose network when running the test server in Docker.

### GPU acceleration

GPU sessions are opt-in twice: configure the manager's `DEEPCLIENT_GPU_MODE`, then send `"gpu":true` while creating a session. Supported modes are:

- `dri` — AMD/Intel Mesa on a native Linux Docker host using `/dev/dri`;
- `nvidia` — NVIDIA Container Toolkit using a Docker GPU device request;
- `wsl-dxg` — experimental direct-WSL setup using `/dev/dxg` and `/usr/lib/wsl`;
- `none` — the safe default, using Mesa llvmpipe software rendering.

HIP/ROCm accelerates compute workloads, but Minecraft renders through OpenGL; HIP by itself does not accelerate this client. For an AMD GPU, the relevant rendering path is Mesa via `/dev/dri` on Linux or the experimental D3D12 Mesa driver through `/dev/dxg` in WSL. If ROCm needs an architecture override to discover your GPU, set the GPU-specific `DEEPCLIENT_HSA_OVERRIDE_GFX_VERSION` (for example `10.3.0`); the manager forwards it as `HSA_OVERRIDE_GFX_VERSION`. `DEEPCLIENT_MESA_D3D12_ADAPTER_NAME` independently selects the D3D12 rendering adapter and defaults to `AMD`.

Docker Desktop may not pass an AMD adapter into its Linux VM even when the surrounding WSL distro can see it. In that case, run the Docker Engine inside that WSL distro or retain software rendering. Xvfb may also choose llvmpipe despite a visible GPU, so verify the real result in `GET .../client/v1/state` under `graphics.renderer` rather than assuming device exposure worked.

On the current Windows Docker Desktop host used for development, the NVIDIA runtime exists but reports `WSL environment detected but no adapters were found`; therefore this repository does not enable GPU mode by default.

The browser view is noVNC; screenshots and dashboard frames come directly from Minecraft's framebuffer.

The development launcher creates offline client identities. That is appropriate for isolated, `online-mode=false` test servers. Joining authenticated public/online-mode servers requires launching the built mod through a real Minecraft launcher session instead of the development launcher.

Baritone is an external LGPL-3.0 dependency and is deliberately not embedded in the MIT-licensed mod jar. Docker and `runClient` load it automatically through Gradle. When installing the built jar in a launcher, also install `baritone-api-fabric-1.11.2.jar` from the official Baritone release.

## Configuration

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `DEEPCLIENT_BIND` | `127.0.0.1` | HTTP bind address; a non-loopback value requires a token |
| `DEEPCLIENT_PORT` | `8080` | HTTP port |
| `DEEPCLIENT_TOKEN` | empty | Bearer token |
| `DEEPCLIENT_REQUEST_TIMEOUT_SECONDS` | `10` | Maximum wait for client-thread operations |
| `DEEPCLIENT_STREAM_FPS` | `4` | Frame stream rate, from 1 to 20 |
| `DEEPCLIENT_NEARBY_ENTITY_LIMIT` | `64` | Maximum entities returned by state |
| `MINECRAFT_WIDTH` / `MINECRAFT_HEIGHT` | `1280` / `720` | Container framebuffer size |
| `MINECRAFT_USERNAME` | `DeepClient` | Offline development identity |

Manager configuration:

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `DEEPCLIENT_MANAGER_TOKEN` | required | Dashboard and session API bearer token |
| `DEEPCLIENT_SESSION_IMAGES` | `1.21.1=deep-client-test-core:1.21.1` | Allowed version-to-image mappings |
| `DEEPCLIENT_SESSION_IDLE_TTL` | `30m` | Inactivity duration before teardown |
| `DEEPCLIENT_CLEANUP_INTERVAL` | `30s` | Idle-session scan interval |
| `DEEPCLIENT_GPU_MODE` | `none` | `none`, `nvidia`, `dri`, or experimental `wsl-dxg` |
| `DEEPCLIENT_HSA_OVERRIDE_GFX_VERSION` | empty | Optional GPU-specific ROCm/HIP architecture override forwarded to WSL sessions |
| `DEEPCLIENT_MESA_D3D12_ADAPTER_NAME` | `AMD` | Adapter substring used by Mesa's WSL D3D12 renderer |
| `DEEPCLIENT_SESSION_MEMORY_BYTES` | `4294967296` | Memory limit for each client container |
| `DEEPCLIENT_SESSION_NANO_CPUS` | `0` | Optional CPU limit in billionths of a CPU |

## Scope and roadmap

The current API covers normal gameplay through semantic actions plus raw screen input as a fallback for vanilla and custom GUIs. Baritone supplies long-distance movement. REST keeps actions small and deterministic so an agent can observe after every action and recover from mistakes.

Likely next layers are a WebSocket event channel, richer world/chunk queries, recipe and trade helpers, audio capture, and a lower-bandwidth H.264 or WebRTC stream. See the public issue tracker for active work.
