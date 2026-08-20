#!/bin/sh
set -eu

screen="${MINECRAFT_WIDTH}x${MINECRAFT_HEIGHT}x24"
Xvfb "${DISPLAY}" -screen 0 "${screen}" -ac +extension GLX +render -noreset &
until xdpyinfo -display "${DISPLAY}" >/dev/null 2>&1; do
    sleep 0.1
done
fluxbox >/tmp/fluxbox.log 2>&1 &
x11vnc -display "${DISPLAY}" -forever -shared -rfbport 5900 -nopw >/tmp/x11vnc.log 2>&1 &
websockify --web=/usr/share/novnc/ 6080 localhost:5900 >/tmp/novnc.log 2>&1 &

exec ./gradlew --no-daemon runClient \
    --args="--width ${MINECRAFT_WIDTH} --height ${MINECRAFT_HEIGHT} --username ${MINECRAFT_USERNAME}"
