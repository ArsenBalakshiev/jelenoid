#!/bin/sh
# Entrypoint: подготавливает runtime и exec CMD.
# В headless-варианте просто exec'ит supervisord.
# В VNC-варианте дополнительно создаёт VNC password файл.

set -eu

export HOME=/home/jelenoid
mkdir -p "${HOME}"
cd "${HOME}"

if [ "${CHROMEDRIVER_URL_BASE:-}" = "" ]; then
    export CHROMEDRIVER_URL_BASE=/wd/hub
fi

if [ -n "${SE_VNC_PASSWORD:-}" ] && command -v x11vnc >/dev/null 2>&1; then
    mkdir -p "${HOME}/.vnc"
    x11vnc -storepasswd "${SE_VNC_PASSWORD}" "${HOME}/.vncpasswd" >/dev/null 2>&1 || true
fi

exec "$@"
