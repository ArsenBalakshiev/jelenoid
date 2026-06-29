#!/bin/sh
# Fast init для VNC-варианта Yandex-контейнера.
# Запускает yandexdriver + прокси как можно раньше, X11 - в фоне.
set -eu

export HOME=/home/jelenoid
export USER=jelenoid
export DISPLAY=:${SE_DISPLAY_NUM:-99}
export XAUTHORITY="${HOME}/.Xauthority"
export YANDEX_BIN="${YANDEX_BIN:-/usr/local/bin/google-chrome}"
export YANDEXDRIVER_BIN="${YANDEXDRIVER_BIN:-/opt/yandexdriver/yandexdriver}"
export CDP_PROXY_DEBUGGER_BASE="${CDP_PROXY_DEBUGGER_BASE:-127.0.0.1:9222}"

cd "${HOME}"
mkdir -p "${HOME}/.vnc"

# VNC-пароль (совместимость с UI)
VNC_PASSWORD="${SE_VNC_PASSWORD:-selenoid}"
x11vnc -storepasswd "${VNC_PASSWORD}" "${HOME}/.vncpasswd" >/dev/null 2>&1 || true

# Запускаем X11 и WM в фоне (не блокируют yandexdriver)
Xvfb "${DISPLAY}" -screen 0 "${SE_SCREEN_WIDTH:-1920}x${SE_SCREEN_HEIGHT:-1080}x${SE_SCREEN_DEPTH:-24}" -ac -nolisten tcp -dpi 96 +extension RANDR >/dev/null 2>&1 &
openbox >/dev/null 2>&1 &
if [ "${ENABLE_VNC:-false}" = "true" ]; then
    x11vnc -display "${DISPLAY}" -forever -shared -rfbport "${SE_VNC_PORT:-5900}" -rfbauth "${HOME}/.vncpasswd" >/dev/null 2>&1 &
fi

# Запускаем yandexdriver и прокси.
# wd-proxy запускаем на переднем плане - пока он жив, контейнер жив.
"${YANDEXDRIVER_BIN}" --port=4445 --allowed-origins=* >/dev/null 2>&1 &
/usr/local/bin/cdp-proxy >/dev/null 2>&1 &
exec /usr/local/bin/wd-proxy
