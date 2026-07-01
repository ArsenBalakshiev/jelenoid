#!/bin/sh

set -eu

URL="${CHROME_VERSION_JSON_URL:-https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json}"
CHANNEL="${CHROME_CHANNEL:-Stable}"

if ! command -v curl >/dev/null 2>&1; then
    echo "curl required" >&2
    exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 required" >&2
    exit 1
fi

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

curl -fsSL "$URL" -o "$tmp" || { echo "failed to download $URL" >&2; exit 1; }

python3 - "$tmp" "$CHANNEL" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
channel = sys.argv[2]
stable = data["channels"][channel]
ver = stable["version"]
def url_for(kind):
    for d in stable["downloads"][kind]:
        if d["platform"] == "linux64":
            return d["url"]
    raise SystemExit(f"no linux64 entry for {kind}")
chrome_url = url_for("chrome")
driver_url = url_for("chromedriver")
headless_url = url_for("chrome-headless-shell")
print(f'CHROME_VERSION={ver}')
print(f'CHROME_URL={chrome_url}')
print(f'CHROMEDRIVER_URL={driver_url}')
print(f'HEADLESS_SHELL_URL={headless_url}')
PY
