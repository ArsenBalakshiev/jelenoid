#!/bin/sh
# Резолвит последнюю Stable-версию Yandex Browser для amd64.
# Используется в Dockerfile как часть yandex-downloader стадии.
# Принимает опционально YANDEX_VERSION_INDEX_URL для override (полезно для CI).
#
# Выводит в формате shell-переменных:
#   YANDEX_VERSION=<deb version, например 26.4.1.1110-1>
#   YANDEX_DEB_URL=<полный URL .deb>
#   YANDEX_BROWSER_REVISION=<часть до '-', например 26.4.1.1110>
#   YANDEX_MAJOR_MINOR=<первые две цифры версии, например 26.4>

set -eu

URL="${YANDEX_VERSION_INDEX_URL:-https://repo.yandex.ru/yandex-browser/deb/dists/stable/main/binary-amd64/Packages}"

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

python3 - "$tmp" <<'PY'
import re, sys
from collections import defaultdict

with open(sys.argv[1]) as f:
    text = f.read()

# deb Packages-файл - это последовательность блоков вида
#   Package: yandex-browser-stable
#   Version: 26.4.1.1110-1
#   Filename: pool/main/y/yandex-browser-stable/yandex-browser-stable_26.4.1.1110-1_amd64.deb
#   ...
# Блоки разделены пустой строкой. Парсим построчно.

blocks = defaultdict(dict)
current = None
for line in text.splitlines():
    if not line.strip():
        current = None
        continue
    if line.startswith(" "):
        continue
    if ":" not in line:
        continue
    key, _, value = line.partition(":")
    key = key.strip()
    value = value.strip()
    if key == "Package":
        current = value
        blocks[current] = blocks.get(current, {})
    elif current is not None:
        blocks[current][key] = value

stable = blocks.get("yandex-browser-stable")
if not stable or "Version" not in stable or "Filename" not in stable:
    sys.stderr.write("yandex-browser-stable not found in Packages index\n")
    sys.exit(1)

ver = stable["Version"]
# deb-version: "26.4.1.1110-1" → "26.4.1.1110" (до '-') + "1" (после).
# Это revision (=26.4.1.1110) + deb revision (=1).
m = re.match(r"^(\d+(?:\.\d+)+)(?:-(\d+))?$", ver)
if not m:
    sys.stderr.write(f"unexpected Version format: {ver}\n")
    sys.exit(1)
browser_revision = m.group(1)
deb_revision = m.group(2) or "0"
major_minor = ".".join(browser_revision.split(".")[:2])
deb_url = "https://repo.yandex.ru/yandex-browser/deb/" + stable["Filename"]

print(f"YANDEX_VERSION={ver}")
print(f"YANDEX_DEB_URL={deb_url}")
print(f"YANDEX_BROWSER_REVISION={browser_revision}")
print(f"YANDEX_DEB_REVISION={deb_revision}")
print(f"YANDEX_MAJOR_MINOR={major_minor}")
PY
