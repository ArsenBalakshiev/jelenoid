# Chrome for Jelenoid / Selenoid

Two variants of a minimal Chrome + ChromeDriver image, built on a shared base
(`jelenoid/selenium-base:bookworm`) that holds all the system dependencies,
fonts, X11 libs, supervisord, socat, etc.

- `Dockerfile` — headless, listens on `:4444` (ChromeDriver WebDriver endpoint)
- `Dockerfile.vnc` — same, plus Xvfb + openbox + x11vnc on `:5900` for live observation

Both are fully compatible with `jelenoid-server` / Selenoid: a single WebDriver
port (`4444`) exposed, `/wd/hub/status` returns `{"ready": true}` once Chrome is up.

## Layout

```
chromium/
├── base/
│   ├── Dockerfile.base          # debian + system libs + supervisord + socat
│   ├── get-latest-chrome.sh     # resolves latest Stable from googlechromelabs (baked into base image)
│   └── build-base.sh            # convenience: ./build-base.sh [tag-suffix]
└── chrome/
    ├── Dockerfile               # headless (FROM base)
    ├── Dockerfile.vnc           # VNC     (FROM base)
    ├── supervisord.conf         # chromedriver + socat programs
    ├── supervisord-vnc.conf     # + xvfb, openbox, x11vnc
    ├── entrypoint.sh
    ├── openbox-autostart
    ├── build.py                 # build + tag + (optionally) push
    ├── test.py                  # smoke test (Windows-friendly)
    └── README.md
```

`get-latest-chrome.sh` lives in `base/` only — it's copied into the base image at
`/usr/local/share/jelenoid/get-latest-chrome.sh`, and the chrome `Dockerfile`
references it via `COPY --from=jelenoid/selenium-base:bookworm ...`.

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

## Run (smoke test)

```sh
# from chromium/chrome/
python test.py            # headless
python test.py --vnc      # VNC
python test.py --skip-build --skip-build-base  # reuse cached images
python test.py --keep     # don't delete the container
```

`test.py` will (1) build base if missing, (2) build the chrome image, (3) start
the container, (4) wait for `/wd/hub/status`, (5) create/close a real WebDriver
session, (6) for VNC also take a screenshot of the Xvfb framebuffer (== what
noVNC/TigerVNC would see). Pass: `===== ALL CHECKS PASSED =====`.

## Run directly (manual smoke test)

```sh
docker run --rm -d --name chrome --shm-size=2g -p 4444:4444 \
    suomessa/jelenoid:chrome-latest

curl -s http://localhost:4444/wd/hub/status
```

VNC:

```sh
docker run --rm -d --name chrome --shm-size=2g \
    -p 4444:4444 -p 5900:5900 \
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
`:4444`, and (for the VNC variant) proxies `/vnc/{sessionId}` to its `:5900`.

## Configuration (env)

| Variable                       | Default                                  | Notes |
|--------------------------------|------------------------------------------|-------|
| `CHROMEDRIVER_PORT`            | `4444`                                   | WebDriver port exposed by the container (socat front-port) |
| `CHROMEDRIVER_URL_BASE`        | `/wd/hub`                                | required by Jelenoid/Selenoid |
| `SE_SCREEN_WIDTH` / `_HEIGHT`  | `1920` / `1080`                          | VNC Xvfb display size |
| `SE_SCREEN_DEPTH`              | `24`                                     | Xvfb color depth |
| `SE_VNC_PORT`                  | `5900`                                   | VNC TCP port (VNC image only) |
| `SE_VNC_PASSWORD`              | _(not set in image)_                     | VNC password. If set in runtime, x11vnc uses it; otherwise VNC is open. |

`SE_VNC_PASSWORD` is intentionally **not baked into the image** (no `ENV`).
Pass it at runtime: `docker run -e SE_VNC_PASSWORD=...` or set it in
`jelenoid-server` container-manager env. This avoids leaking the default
"selenoid" password in `docker inspect`.

## Notes

- Image runs as unprivileged user `jelenoid` (uid 1000).
- `dumb-init` is PID 1 for clean signal handling and zombie reaping.
- `chromedriver` runs in **loopback-only** mode on `127.0.0.1:4445`; `socat`
  exposes it on `0.0.0.0:4444`. This works around Chrome 150+ restrictions
  (rejects non-local connections, doesn't accept CIDR in `--allowed-ips`)
  without ever exposing chromedriver directly to the network.
- For the VNC variant, processes start in order: `xvfb` → `openbox` → `x11vnc` →
  `chromedriver` → `socat` (enforced by `priority` in supervisord.conf).
- **Why openbox?** Chrome behaves more like a real desktop app with a window
  manager (handles `window.open()`, `window.maximize()`, native dialogs). The
  VNC image is ~50MB larger than headless because of this trade-off.
- **Why `openbox-autostart`?** Disables X screensaver/DPMS so the VNC display
  doesn't blank out during long idle periods between test sessions.
- `--shm-size=2g` is required for Chrome at `docker run` time
  (compose: `shm_size: 2gb`).
- The base image is shared — firefox/webkit/etc. variants can `FROM
  jelenoid/selenium-base:bookworm` and just add their browser.
