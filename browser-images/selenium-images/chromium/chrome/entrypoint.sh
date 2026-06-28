#!/bin/sh
# Entrypoint: подготавливает runtime и exec CMD.
# В VNC-варианте создаёт файл пароля (selenoid по умолчанию).

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
