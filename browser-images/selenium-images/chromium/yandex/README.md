# Yandex Browser for Jelenoid / Selenoid

Two variants of a Yandex Browser + YandexDriver image, built on the same shared
base (`jelenoid/selenium-base:bookworm`) that powers the `chrome/` variant.

- `Dockerfile` - **headless** variant. Uses the real Yandex Browser binary
  launched in `--headless` mode via the wrapper.
- `Dockerfile.vnc` - **VNC** variant with Xvfb + openbox + x11vnc on `:5900`
  for live observation (full headed Yandex in a virtual display).

Both expose the same Selenoid-compatible endpoints:

| Port | Endpoint | Purpose |
|------|----------|---------|
| 4444 | `/status`, `/session`, `/wd/hub/status`, `/wd/hub/session` | W3C WebDriver (via yandexdriver + wd-proxy) |
| 7070 | `/devtools/page/{sessionId}` | CDP WebSocket proxy (used by jelenoid-server `/session/{id}/se/cdp`) |
| 5900 | VNC (only `-vnc` image) | x11vnc, password `selenoid` |

## Why this works

Yandex Browser is Chromium 146+ packaged by Yandex. The CDP and WebDriver
stacks are upstream-Chromium, so:

- `cdp-proxy` and `wd-proxy` (Go binaries) are reused **verbatim** from
  `../chrome/`. They talk to whatever is listening on `127.0.0.1:9222`
  (browser DevTools) and `127.0.0.1:4445` (driver W3C endpoint).
- **YandexDriver** is a binary-compatible fork of chromedriver, so any
  Selenium/Java client that uses `browserName=chrome` works as-is.
- The `/usr/local/bin/google-chrome` wrapper and the `/usr/local/bin/chromedriver`
  symlink are exposed for clients that hardcode those names (they point at
  the Yandex binaries).

`managed_policies.json` ships in the image at
`/etc/opt/yandex/browser/policies/managed/managed_policies.json` and disables
Safe Browsing, Phishing Protection, and Yandex Protect - otherwise the
browser throws scary "your connection is not private" overlays at any
self-signed test endpoint (test-app, internal HTTPS gateways, etc.).

## Layout

```
yandex/
├── Dockerfile                       # headless (yandex-browser --headless)
├── Dockerfile.vnc                   # VNC     (full Yandex + Xvfb + x11vnc)
├── cdp-proxy/                       # Go WS proxy :7070 -> chromedriver se:cdp
├── wd-proxy/                        # Go reverse proxy :4444 -> yandexdriver
├── entrypoint.sh                    # prepares runtime, exec CMD
├── start-headless.sh                # starts yandexdriver + cdp-proxy + wd-proxy
├── start-vnc.sh                     # + Xvfb, openbox, x11vnc
├── openbox-autostart                # X11 anti-screensaver
├── managed_policies.json            # disables Safe Browsing / Yandex Protect
├── get-latest-yandex.sh             # resolves latest Stable from apt repo
├── build.py                         # build + tag + (optionally) push
├── test.py                          # smoke test (Windows-friendly)
└── README.md
```

## Build

First time (or after base image changes) - build the base:

```sh
cd browser-images/selenium-images/chromium/base
./build-base.sh             # → jelenoid/selenium-base:bookworm
```

Then build the Yandex images:

```sh
cd ../yandex

# Headless, latest stable Yandex Browser (auto-resolved from apt repo)
# + matching YandexDriver (auto-resolved from GitHub by major.minor)
python build.py

# VNC
python build.py --no-vnc=false   # default: builds both headless and vnc

# Pin a specific deb version (still auto-resolves a driver with matching major.minor)
python build.py --version 26.4.1.1110-1

# Or pin everything manually
python build.py \
  --version 26.4.1.1110-1 \
  --driver-url https://github.com/yandex/YandexDriver/releases/download/v26.4.1-stable/yandexdriver-26.4.1.1103-linux.zip
```

Tags produced:

- `suomessa/jelenoid:yandex-26.4.1` and `suomessa/jelenoid:yandex-latest`
- `suomessa/jelenoid:yandex-26.4.1-vnc` and `suomessa/jelenoid:yandex-latest-vnc`

Then push (CI usage):

```sh
python build.py --push
python build.py --registry ghcr.io/myorg --push
```

## Why "auto-resolve YandexDriver by major.minor"?

YandexDriver releases don't ship a Linux zip for every version (some tags
publish only win64/mac). The resolver:

1. Reads the resolved browser version, e.g. `26.4.1.1110-1` → `major.minor=26.4`.
2. Lists all YandexDriver releases from the GitHub API
   (`https://api.github.com/repos/yandex/YandexDriver/releases`).
3. Keeps only those whose tag `v<tag>-stable` has matching `major.minor` **and**
   has a `yandexdriver-…-linux.zip` asset.
4. Picks the latest by tag.

This satisfies the rule "the first two components of the version must match
between browser and driver" while surviving the gaps in the release pipeline.
Pass `--driver-url` to skip auto-resolution.

## Run (smoke test)

```sh
# from chromium/yandex/
python test.py            # headless
python test.py --vnc      # VNC
python test.py --skip-build --skip-build-base  # reuse cached images
python test.py --keep     # don't delete the container
```

`test.py` will (1) build base if missing, (2) build the yandex image, (3) start
the container, (4) verify `yandex-browser`/`yandexdriver` versions and the
`managed_policies.json`, (5) wait for `/status`, (6) create/close a real
WebDriver session, (7) verify CDP proxy and CDP command execution, (8) for VNC
also take a screenshot of the Xvfb framebuffer.
Pass: `===== ALL CHECKS PASSED =====`.

## Run directly (manual smoke test)

Headless:

```sh
docker run --rm -d --name yandex --shm-size=2g -p 4444:4444 -p 7070:7070 \
    suomessa/jelenoid:yandex-latest

curl -s http://localhost:4444/status
```

VNC:

```sh
docker run --rm -d --name yandex --shm-size=2g \
    -p 4444:4444 -p 7070:7070 -p 5900:5900 \
    suomessa/jelenoid:yandex-latest-vnc

# TigerVNC / noVNC: localhost:5900, password "selenoid"
```

## Use via Jelenoid (Selenoid-style)

In your `browsers.json`:

```json
{
  "yandex": {
    "default": "26.4.1",
    "versions": {
      "26.4.1":        { "image": "suomessa/jelenoid:yandex-26.4.1" },
      "26.4.1-vnc":    { "image": "suomessa/jelenoid:yandex-26.4.1-vnc" },
      "latest":        { "image": "suomessa/jelenoid:yandex-latest" },
      "latest-vnc":    { "image": "suomessa/jelenoid:yandex-latest-vnc" }
    }
  }
}
```

`jelenoid-server` starts one container per session, proxies `/wd/hub` to its
`:4444`, CDP to `:7070`, and (for the VNC variant) `/vnc/{sessionId}` to its
`:5900`.

## Configuration (env)

| Variable                       | Default                                  | Notes |
|--------------------------------|------------------------------------------|-------|
| `YANDEXDRIVER_PORT`            | `4444`                                   | WebDriver port exposed by the container (via `wd-proxy`) |
| `SE_SCREEN_WIDTH` / `_HEIGHT`  | `1920` / `1080`                          | VNC Xvfb display size |
| `SE_SCREEN_DEPTH`              | `24`                                     | Xvfb color depth |
| `SE_VNC_PORT`                  | `5900`                                   | VNC TCP port (VNC image only) |
| `SE_VNC_PASSWORD`              | `selenoid`                               | VNC password |

## Notes

- Image runs as unprivileged user `jelenoid` (uid 1000).
- `dumb-init` is PID 1 for clean signal handling and zombie reaping.
- `yandexdriver` runs in **loopback-only** mode on `127.0.0.1:4445`; `wd-proxy`
  exposes it on `0.0.0.0:4444`. `wd-proxy` дополнительно поддерживает устаревший
  путь `/wd/hub/status`, чтобы образ работал и со старыми сборками
  `container-manager`.
- `/usr/local/bin/google-chrome` - это wrapper, который всегда добавляет
  `--no-sandbox --disable-dev-shm-usage` (и `--headless` для headless-варианта).
  Поэтому Java/Selenium-клиентам не обязательно передавать эти флаги в
  `goog:chromeOptions`.
- `/usr/local/bin/chromedriver` - это symlink на `yandexdriver` для
  совместимости с клиентами, которые жёстко завязаны на имя `chromedriver`.
  Настоящие пути: `/opt/yandexdriver/yandexdriver`,
  `/opt/yandex/browser/yandex-browser`.
- The WebDriver endpoint uses yandexdriver's default URL base (`/`), so
  `/status` returns `{"ready": true}` and `/session` creates sessions.
  `wd-proxy` дополнительно принимает `/wd/hub/status` и `/wd/hub/session`,
  чтобы образ был совместим и с `twilio/selenoid`, и с legacy-сборками.
- For the VNC variant, `start-vnc.sh` starts X11 (`xvfb` + `openbox` +
  `x11vnc`) in the background, then launches `yandexdriver` + `cdp-proxy` and
  execs `wd-proxy` on the foreground. `wd-proxy` is PID-of-interest - while
  it lives, the container lives.
- **`managed_policies.json` обязателен для нормальной работы** в test-среде:
  без него Yandex Browser перекрывает любой self-signed / невалидный TLS
  предупреждением "Ваше подключение не защищено", которое нельзя закрыть из
  Selenium. Содержимое файла: `SafeBrowsingProtectionLevel=0`,
  `YandexPhisingProtection=false`, `YandexProtectionMode=false`.
- `--shm-size=2g` is required at `docker run` time (compose: `shm_size: 2gb`).
- The base image is shared with the `chrome/` variant.
