[English](./README.md)

---

<div align="center">

### High-performance Selenium + Playwright Hub

A lightweight and fast orchestrator for running UI tests in **isolated Docker containers** with support for
**Selenium (W3C WebDriver)** and **Playwright**, including queueing, concurrency limits,
and observability (UI for live monitoring).

## Why Jelenoid

Jelenoid is not “just a proxy to a browser” — it’s a full orchestrator: it starts a container **per session**, enforces concurrency and queue limits, and cleans up the environment reliably.

Key benefits:
- **Isolation by default:** one session = one container, less flakiness and “dirty” state.
- **High throughput:** the server handles routing/session control while Docker operations are moved into a dedicated service.
- **Selenium + Playwright in one hub:** run different kinds of tests in a single stack.
- **Load control:** concurrency limits and request queueing for CI/CD.
- **Observability:** monitoring UI with live VNC, container logs and SSE-powered state updates.

---

## 🧩 Stack services

The stack runs via Docker Compose and includes:

### `jelenoid-server`
Main hub (Selenium + Playwright):
- Port: `4444`
- Reads browser configuration from `browsers.json`
- Sends execution to `container-manager`

### `container-manager`
Container lifecycle manager:
- Starts/stops browser containers
- Requires Docker API access: mounts `/var/run/docker.sock`
- Runs in the same network as `jelenoid-server` (`jelenoid-net`)
- Default internal port: `8080`

### `jelenoid-ui`
Web UI for monitoring:
- Production image: `suomessa/jelenoid:ui-latest`
- Port: `80` (published to host)
- Connects to `jelenoid-server` via `VITE_SERVER_BASE_URL`

---

## 🚀 Key Features

### 🌐 General Functionality
- **Dynamic Container Management:** Automatically starts and stops Docker containers for each session.
- **Full Test Isolation:** Provides a clean environment for every test run.
- **Resource Limiting:** Sets limits on the number of parallel sessions and browser versions.
- **Request Queue:** Features an integrated queuing mechanism to manage load, which is critical for CI/CD pipelines.
- **Playwright Container Pool:** Reuses warm Playwright containers by `image|version` to reduce cold-start time.
- **Jelenoid UI:** A simple and convenient web interface for monitoring sessions.


### 🤖 Selenium
- **Full W3C WebDriver Proxying:** Reliably forwards all commands of the protocol.
- **Centralized State Management:** A single service (`ActiveSessionsService`) tracks all active sessions.
- **Flexible Configuration:** Full support for `alwaysMatch` / `firstMatch` and vendor-specific options (`selenoid:options`).
- **File Uploads:** Easily upload files to the container during a test via the `/wd/hub/session/{sessionId}/file` endpoint.
- **Live VNC Streaming:** Interactive, real-time access to the browser's desktop via any noVNC client.
- **Chrome DevTools Protocol (CDP) Proxy:** Direct access to the browser's DevTools for network emulation and other debugging tasks.

### 🎭 Playwright
- **Native Support:** Full integration for running Playwright tests.
- **WebSocket Proxying:** Reliably forwards commands from the test to the browser.
- **Session Management:** Dynamically creates and manages sessions in containers.
- **Multi-version Routing:** Connect to a specific Playwright version via `/playwright-{version}`.

---

## ⚙️ Configuration (Environment Variables)

jelenoid-server

| Variable                   | Description                                                               | Default Value                          |
|----------------------------|---------------------------------------------------------------------------| -------------------------------------- |
| `JELENOID_PORT`            | HTTP port of the hub.                                                     | `4444`                                 |
| `JELENOID_PUBLIC_HOST`     | Public host used to build the `se:cdp` WS URL. Use `0.0.0.0` in prod.      | `0.0.0.0`                              |
| `CONTAINER_MANAGER_ADDRESS`| URL of the container-manager.                                             | `http://container-manager:8080`        |
| `PARALLEL_SESSIONS`        | The number of parallel sessions for Selenium tests.                       | `10`                                   |
| `QUEUE_LIMIT`              | The queue limit for Selenium sessions.                                    | `100`                                  |
| `BROWSERS_FILE`            | The path to the `browsers.json` file for image configuration.             | `browsers.json`                        |
| `QUEUE_TIMEOUT`            | The timeout for the Selenium queue (in ms).                               | `30000`                                |
| `SESSION_TIMEOUT`          | The inactivity timeout for a Selenium/Playwright session (in ms).         | `600000`                               |
| `STARTUP_TIMEOUT`          | The interval of the inactive-sessions sweeper (in ms).                    | `30000`                                |
| `UI_HOSTS_LIST`            | A comma-separated list of UI hosts for CORS.                              | `http://localhost:3000,http://localhost:4444` |
| `PLAYWRIGHT_SESSION_LIMIT` | The number of parallel sessions for Playwright tests.                     | `10`                                   |
| `PLAYWRIGHT_QUEUE_LIMIT`   | The queue limit for Playwright sessions.                                  | `100`                                  |
| `ENABLE_QUEUE`             | Set to `false` to disable the queue and return `503` on overflow.         | `true`                                 |
| `PLAYWRIGHT_CONTAINER_POOL_ENABLED` | Enable Playwright container pool.                                  | `false`                                |
| `PLAYWRIGHT_CONTAINER_POOL_MAX_SIZE` | Maximum total pool size.                                          | `10`                                   |
| `PLAYWRIGHT_CONTAINER_POOL_MAX_PER_KEY` | Max containers per `image\|version` key.                          | `5`                                    |
| `PLAYWRIGHT_CONTAINER_POOL_IDLE_MS` | Idle TTL in ms after which a pooled container is stopped.            | `60000`                                |

container-manager

| Variable                     | Description                  | Default Value  |
|------------------------------|------------------------------|----------------|
| `CONTAINER_MANAGER_PORT`     | HTTP port.                   | `8080`         |
| `DOCKER_NETWORK`             | Docker network name.         | `jelenoid-net` |
| `PLAYWRIGHT_PORT`            | Port for `playwright run-server` inside the container. | `3000` |
| `PLAYWRIGHT_CONTAINER_SHM_SIZE`  | `/dev/shm` size for Playwright containers (bytes). | `4294967296` (4 GiB) |
| `PLAYWRIGHT_CONTAINER_TMPFS_SIZE` | `/tmp` tmpfs size for Playwright containers.     | `2g`           |
| `SELENIUM_CONTAINER_SHM_SIZE`    | `/dev/shm` size for Selenium containers (bytes). | `2147483648` (2 GiB) |
| `SELENIUM_CONTAINER_TMPFS_SIZE`  | `/tmp` tmpfs size for Selenium containers.        | `1g`           |
| `CLEANUP_TIMEOUT`            | `docker stop` timeout (ms).  | `15000`        |
| `CONTAINER_STARTING_TIMEOUT` | Timeout to wait for container readiness (ms). | `60000` |

jelenoid-ui

| Variable                 | Description                              | Default Value                |
|--------------------------|------------------------------------------|------------------------------|
| `VITE_SERVER_BASE_URL`   | Base URL of `jelenoid-server` (REST/SSE/WS). | `http://localhost:4444`   |
| `VITE_UI_PORT`           | Vite dev server port.                    | `8080`                       |

---

# Start app

```shell
docker-compose --f=docker-compose-prod.yml up -d
```

# Start app in dev mode

## 🛠️ Usage and Commands

### Running in Development Mode
To run the server in Docker (with hot-reload for development):
```shell
docker-compose up -d --build --force-recreate jelenoid-server
```

To run the container-manager in Docker (with hot-reload for development):
```shell
docker-compose up -d --build --force-recreate container-manager
```

To run the UI:
```shell
docker-compose up -d jelenoid-ui
# or natively
npm install --prefix jelenoid-ui && npm run dev --prefix jelenoid-ui
```

### Lint UI
```shell
npm run lint --prefix jelenoid-ui
```

### E2E tests (requires a running hub on `localhost:4444`)
```shell
cd tests
mvn test                                                       # all
mvn test -Dtest=SeleniumE2ETest                                # one class
mvn test -Dhub.url=http://localhost:4444/wd/hub                # custom hub
mvn test -Dplaywright.ws=ws://localhost:4444/playwright-1.58.0 # specific version
```

### Building Images
To build the server image:
```shell
docker build -t suomessa/jelenoid:server-latest .\jelenoid-server\
```

To build the container-manager image:
```shell
docker build -t suomessa/jelenoid:container-manager-latest .\container-manager\
```

To build the UI image:
```shell
docker build -t suomessa/jelenoid:ui-latest .\jelenoid-ui\.
```

To build the test-app image:
```shell
docker build -t suomessa/jelenoid:test-app-latest .\test-app\.
```

Demo nginx for tests:
```shell
docker run -d --name mynginx -p 8080:80 nginx
```

</div>
