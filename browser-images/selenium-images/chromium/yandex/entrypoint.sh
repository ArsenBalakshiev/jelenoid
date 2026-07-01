#!/bin/sh

set -eu

export HOME=/home/jelenoid
mkdir -p "${HOME}"
cd "${HOME}"

if command -v x11vnc >/dev/null 2>&1; then
    mkdir -p "${HOME}/.vnc"
    VNC_PASSWORD="${SE_VNC_PASSWORD:-selenoid}"
    x11vnc -storepasswd "${VNC_PASSWORD}" "${HOME}/.vncpasswd" >/dev/null 2>&1 || true
fi

exec "$@"
