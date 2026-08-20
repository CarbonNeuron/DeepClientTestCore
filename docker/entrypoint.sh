#!/bin/sh
set -eu

if [ "${DEEPCLIENT_DISPLAY_BACKEND:-xvfb}" = "wslg" ]; then
    until xdpyinfo -display "${DISPLAY}" >/dev/null 2>&1; do
        sleep 0.1
    done

    # WSLg's Xwayland server is rootless, so capture only this container's
    # Minecraft window. MIT-SHM cannot cross the container boundary.
    (
        while :; do
            game_pid=""
            for command_file in /proc/[0-9]*/cmdline; do
                if tr '\0' ' ' <"${command_file}" 2>/dev/null | grep -q 'net.fabricmc.devlaunchinjector.Main'; then
                    game_pid="${command_file#/proc/}"
                    game_pid="${game_pid%/cmdline}"
                    break
                fi
            done
            if [ -n "${game_pid}" ]; then
                window_id="$(xdotool search --pid "${game_pid}" --name 'Minecraft' 2>/dev/null | head -n 1 || true)"
                if [ -n "${window_id}" ]; then
                    exec x11vnc -display "${DISPLAY}" -id "${window_id}" -noshm -noxdamage \
                        -forever -shared -rfbport 5900 -nopw >/tmp/x11vnc.log 2>&1
                fi
            fi
            sleep 0.25
        done
    ) &
else
    screen="${MINECRAFT_WIDTH}x${MINECRAFT_HEIGHT}x24"
    Xvfb "${DISPLAY}" -screen 0 "${screen}" -ac +extension GLX +render -noreset &
    until xdpyinfo -display "${DISPLAY}" >/dev/null 2>&1; do
        sleep 0.1
    done
    fluxbox >/tmp/fluxbox.log 2>&1 &
    x11vnc -display "${DISPLAY}" -forever -shared -rfbport 5900 -nopw >/tmp/x11vnc.log 2>&1 &
fi

websockify --web=/usr/share/novnc/ 6080 localhost:5900 >/tmp/novnc.log 2>&1 &

exec ./gradlew --offline --no-daemon runClient \
    --args="--width ${MINECRAFT_WIDTH} --height ${MINECRAFT_HEIGHT} --username ${MINECRAFT_USERNAME}"
