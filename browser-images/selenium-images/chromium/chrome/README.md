# Chrome for Jelenoid / Selenoid

Two variants of a Chrome + ChromeDriver image, built on a shared base
(`jelenoid/selenium-base:bookworm`) that holds all the system dependencies,
fonts, X11 libs, etc.

- `Dockerfile` — **headless** variant based on `chrome-headless-shell` from
  Chrome for Testing. Smaller than full Chrome, no display server.
- `Dockerfile.vnc` — **VNC** variant with full `chrome-linux64`, plus
  Xvfb + openbox + x11vnc on `:5900` for live observation.

Both expose the same Selenoid-compatible endpoints:

| Port | Endpoint | Purpose |
|------|----------|---------|
| 4444 | `/status`, `/session`, `/wd/hub/status`, `/wd/hub/session` | W3C WebDriver (via chromedriver + wd-proxy) |
| 7070 | `/devtools/page/{sessionId}` | CDP WebSocket proxy (used by jelenoid-server `/session/{id}/se/cdp`) |
| 5900 | VNC (only `-vnc` image) | x11vnc, password `selenoid` |

## Why a separate CDP proxy?

jelenoid-server proxies CDP to `ws://container:7070/devtools/page/{sessionId}`
(Selenoid-style). ChromeDriver itself exposes CDP per session at
`/session/{sessionId}/se/cdp`. The tiny Go binary `cdp-proxy` (built inside
each image) bridges the two paths.

## Layout

```
chromium/
├── base/
│   ├── Dockerfile.base          # debian + system libs + dumb-init
│   ├── get-latest-chrome.sh     # resolves latest Stable from googlechromelabs
│   └── build-base.sh            # convenience: ./build-base.sh [tag-suffix]
└── chrome/
    ├── Dockerfile               # headless (chrome-headless-shell)
    ├── Dockerfile.vnc           # VNC     (full Chrome)
    ├── cdp-proxy/               # Go WebSocket proxy :7070 -> chromedriver se:cdp
    ├── wd-proxy/                # Go reverse proxy :4444 -> chromedriver
    ├── entrypoint.sh            # prepares runtime, exec CMD
    ├── start-headless.sh        # starts chromedriver + cdp-proxy + wd-proxy
    ├── start-vnc.sh             # + Xvfb, openbox, x11vnc
    ├── openbox-autostart
    ├── build.py                 # build + tag + (optionally) push
    ├── test.py                  # smoke test (Windows-friendly)
    └── README.md
```

## Build

First time (or after base image changes) — build the base:

```sh
cd browser-images/selenium-images/chromium/base
./build-base.sh             # → jelenoid/selenium-base:bookworm
```

Then build the browser images:

```sh
cd ../chrome

# Headless, latest stable Chrome (auto-resolved from googlechromelabs)
docker build -t suomessa/jelenoid:chrome-latest -f Dockerfile .

# VNC
docker build -t suomessa/jelenoid:chrome-latest-vnc -f Dockerfile.vnc .

# Or pin a specific version
docker build -t suomessa/jelenoid:chrome-150.0.7871.24 \
    --build-arg CHROME_VERSION=150.0.7871.24 \
    -f Dockerfile .
```

Or use the helper:

```sh
python build.py              # build both variants, local only
python build.py --push       # build + push to Docker Hub
python build.py --registry ghcr.io/myorg --push
python build.py --no-vnc     # headless only
```

## Run (smoke test)

```sh
# from chromium/chrome/
python test.py            # headless
python test.py --vnc      # VNC
python test.py --skip-build --skip-build-base  # reuse cached images
python test.py --keep     # don't delete the container
```

`test.py` will (1) build base if missing, (2) build the chrome image, (3) start
the container, (4) wait for `/status`, (5) create/close a real WebDriver
session, (6) verify CDP proxy and CDP command execution, (7) for VNC also take
a screenshot of the Xvfb framebuffer. Pass: `===== ALL CHECKS PASSED =====`.

## Run directly (manual smoke test)

Headless:

```sh
docker run --rm -d --name chrome --shm-size=2g -p 4444:4444 -p 7070:7070 \
    suomessa/jelenoid:chrome-latest

curl -s http://localhost:4444/status
```

VNC:

```sh
docker run --rm -d --name chrome --shm-size=2g \
    -p 4444:4444 -p 7070:7070 -p 5900:5900 \
    suomessa/jelenoid:chrome-latest-vnc

# TigerVNC / noVNC: localhost:5900, password "selenoid"
```

## Use via Jelenoid (Selenoid-style)

In your `browsers.json`:

```json
{
  "chrome": {
    "default": "latest",
    "versions": {
      "latest":        { "image": "suomessa/jelenoid:chrome-latest" },
      "latest-vnc":    { "image": "suomessa/jelenoid:chrome-latest-vnc" }
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
| `CHROMEDRIVER_PORT`            | `4444`                                   | WebDriver port exposed by the container (via `wd-proxy`) |
| `SE_SCREEN_WIDTH` / `_HEIGHT`  | `1920` / `1080`                          | VNC Xvfb display size |
| `SE_SCREEN_DEPTH`              | `24`                                     | Xvfb color depth |
| `SE_VNC_PORT`                  | `5900`                                   | VNC TCP port (VNC image only) |
| `SE_VNC_PASSWORD`              | `selenoid`                               | VNC password |

## Notes

- Image runs as unprivileged user `jelenoid` (uid 1000).
- `dumb-init` is PID 1 for clean signal handling and zombie reaping.
- `chromedriver` runs in **loopback-only** mode on `127.0.0.1:4445`; `wd-proxy`
  exposes it on `0.0.0.0:4444`. This works around Chrome 150+ restrictions
  (rejects non-local connections, doesn't accept CIDR in `--allowed-ips`)
  without ever exposing chromedriver directly to the network.
  `wd-proxy` дополнительно поддерживает устаревший путь `/wd/hub/status`,
  чтобы образ работал и со старыми сборками `container-manager`.
- `/usr/local/bin/google-chrome` — это wrapper, который всегда добавляет
  `--no-sandbox --disable-dev-shm-usage` (и `--headless` для headless-варианта).
  Поэтому Java/Selenium-клиентам не обязательно передавать эти флаги в
  `goog:chromeOptions`.
- The WebDriver endpoint uses chromedriver's default URL base (`/`), so
  `/status` returns `{"ready": true}` and `/session` creates sessions.
  `wd-proxy` дополнительно принимает `/wd/hub/status` и `/wd/hub/session`,
  чтобы образ был совместим и с `twilio/selenoid`, и с legacy-сборками.
- For the VNC variant, `start-vnc.sh` starts X11 (`xvfb` + `openbox` + `x11vnc`)
  in the background, then launches `chromedriver` + `cdp-proxy` and execs
  `wd-proxy` on the foreground. `wd-proxy` is PID-of-interest — while it lives,
  the container lives.
- **Why openbox?** Chrome behaves more like a real desktop app with a window
  manager (handles `window.open()`, `window.maximize()`, native dialogs). The
  VNC image is ~300 MB larger than headless because of the X stack.
- **Why `openbox-autostart`?** Disables X screensaver/DPMS so the VNC display
  doesn't blank out during long idle periods between test sessions.
- `--shm-size=2g` is required for Chrome at `docker run` time
  (compose: `shm_size: 2gb`).
- The base image is shared — firefox/webkit/etc. variants can `FROM
  jelenoid/selenium-base:bookworm` and just add their browser.
