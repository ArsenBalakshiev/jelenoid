#!/bin/sh
set -eu

export HOME=/home/jelenoid
export USER=jelenoid
export CHROME_BIN="${CHROME_BIN:-/usr/local/bin/google-chrome}"
export CDP_PROXY_DEBUGGER_BASE="${CDP_PROXY_DEBUGGER_BASE:-127.0.0.1:9222}"

cd "${HOME}"

/opt/chromedriver/chromedriver-linux64/chromedriver --port=4445 --allowed-origins=* >/dev/null 2>&1 &
/usr/local/bin/cdp-proxy >/dev/null 2>&1 &
exec /usr/local/bin/wd-proxy
