# Security

Deep Client provides complete control over a Minecraft session. Treat its bearer token as a credential and never expose either the REST port or noVNC directly to the public Internet.

Non-loopback API binding requires `DEEPCLIENT_TOKEN`. The example Compose file binds REST and noVNC to host loopback. Put authentication and TLS in front of both services before making them remotely accessible.

Please report vulnerabilities privately through GitHub's security-advisory interface instead of opening a public issue.
