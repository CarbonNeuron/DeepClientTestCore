# Contributing

Issues and pull requests are welcome. Deep Client targets Minecraft 1.21.1, Fabric, and Java 21.

Before submitting a pull request:

1. Run `./gradlew build` (`gradlew.bat build` on Windows).
2. Update `openapi.yaml` when changing HTTP behavior.
3. Keep all Minecraft reads and mutations on the render/client thread through `DeepClientController`.
4. Add focused tests for code that can be exercised without a running game.
5. When changing the manager, run `docker build manager` so its Go tests and static build execute.

For runtime checks, start the Docker image and verify health, screenshot, MJPEG, noVNC, and any changed action against an isolated test server.
