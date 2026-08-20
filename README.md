# Deep Client Test Core

[![Build](https://github.com/CarbonNeuron/DeepClientTestCore/actions/workflows/build.yml/badge.svg)](https://github.com/CarbonNeuron/DeepClientTestCore/actions/workflows/build.yml)

Deep Client Test Core is a Fabric 1.21.1 client mod that turns a real rendered Minecraft client into an HTTP-controlled server test fixture. It is aimed at LLMs and automated test harnesses developing Paper, Fabric, Velocity, or other custom server software.

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

## Docker and multiple clients

Copy `.env.example` to `.env`, replace the token, then start two independent clients:

```bash
docker compose up --build
```

| Client | REST API | Browser screen |
| --- | --- | --- |
| 1 | `http://localhost:8081` | `http://localhost:6081/vnc.html?autoconnect=true` |
| 2 | `http://localhost:8082` | `http://localhost:6082/vnc.html?autoconnect=true` |

Compose binds these ports to host loopback. noVNC itself has no password in this development image, so keep it loopback-only or put authentication in front of it before exposing it to a network.

Inside a container, a server running on the Docker host is normally reachable as `host.docker.internal:25565` on Docker Desktop. Put the clients and server in the same Compose network when running the test server in Docker.

The image uses Mesa software rendering. For higher frame rates, adapt the image to expose the host GPU (`/dev/dri` on Linux or the NVIDIA container runtime). The browser view is noVNC; API screenshots come directly from Minecraft's framebuffer.

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

## Scope and roadmap

The current API covers normal gameplay through semantic actions plus raw screen input as a fallback for vanilla and custom GUIs. Baritone supplies long-distance movement. REST keeps actions small and deterministic so an agent can observe after every action and recover from mistakes.

Likely next layers are a WebSocket event channel, richer world/chunk queries, recipe and trade helpers, audio capture, and a lower-bandwidth H.264 or WebRTC stream. See the public issue tracker for active work.
