FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        fluxbox \
        libasound2 \
        libgl1-mesa-dri \
        libglx-mesa0 \
        libxi6 \
        libxrender1 \
        libxtst6 \
        libxxf86vm1 \
        mesa-utils \
        novnc \
        websockify \
        x11-utils \
        x11vnc \
        xdotool \
        xvfb \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties LICENSE openapi.yaml ./
COPY gradle ./gradle
RUN chmod +x gradlew \
    && ./gradlew --no-daemon dependencies

COPY src ./src
RUN ./gradlew --no-daemon build

COPY docker/entrypoint.sh /usr/local/bin/deepclient-entrypoint
RUN chmod +x /usr/local/bin/deepclient-entrypoint

ENV DISPLAY=:99 \
    LIBGL_ALWAYS_SOFTWARE=1 \
    DEEPCLIENT_BIND=0.0.0.0 \
    DEEPCLIENT_PORT=8080 \
    DEEPCLIENT_STREAM_FPS=4 \
    MINECRAFT_WIDTH=1280 \
    MINECRAFT_HEIGHT=720 \
    MINECRAFT_USERNAME=DeepClient

EXPOSE 8080 5900 6080
VOLUME ["/app/run"]

HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=6 \
    CMD curl --fail http://127.0.0.1:8080/v1/health || exit 1

ENTRYPOINT ["deepclient-entrypoint"]
