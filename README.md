[English](./README.md)

---

<div align="center">

### High-performance Selenium + Playwright Hub on Java / Spring Boot

A lightweight and fast orchestrator for running UI tests in **isolated Docker containers** with support for
**Selenium (W3C WebDriver)** and **Playwright**, including queueing, concurrency limits,
and observability (UI + metrics).

## Why Jelenoid

Jelenoid is not “just a proxy to a browser” — it’s a full orchestrator: it starts a container **per session**, enforces concurrency and queue limits, and cleans up the environment reliably.

Key benefits:
- **Isolation by default:** one session = one container, less flakiness and “dirty” state.
- **High throughput:** the server handles routing/session control while Docker operations are moved into a dedicated service.
- **Selenium + Playwright in one hub:** run different kinds of tests in a single stack.
- **Load control:** concurrency limits and request queueing for CI/CD.
- **Observability:** separate metrics service + messaging + database for storage.

---

## 🧩 Stack services

The stack runs via Docker Compose and includes:

### `jelenoid-server`
Main hub (Selenium + Playwright):
- Port: `4444`
- Reads browser configuration from `browsers.json`
- Sends execution to `container-manager`
- Can publish events/logs to NATS (optional)

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

### `metrics-service`
Metrics service:
- Stores data in Postgres
- Uses NATS for event delivery
- Depends on `nats` and `jelenoid-server`

### `nats`
NATS + JetStream:
- Ports: `4222` (client), `8222` (monitoring)
- JetStream storage: `nats_data` volume

### `metrics-db`
PostgreSQL for metrics:
- Port: `5432`
- DB/user/password configured via environment variables

> Note: `depends_on` controls container start order in Compose but does not guarantee a dependency is “ready” to accept connections (unless you add healthchecks / readiness logic).

---

## 🚀 Key Features

### 🌐 General Functionality
- **Dynamic Container Management:** Automatically starts and stops Docker containers for each seleniumSession.
- **Full Test Isolation:** Provides a clean environment for every test run.
- **Resource Limiting:** Sets limits on the number of parallel seleniumSessions and browser versions.
- **Request Queue:** Features an integrated queuing mechanism to manage load, which is critical for CI/CD pipelines.
- **Jelenoid UI:** A simple and convenient web interface for monitoring seleniumSessions.


### 🤖 Selenium
- **Full W3C WebDriver Proxying:** Reliably forwards all commands of the protocol.
- **Centralized State Management:** A single service (`ActiveSessionsService`) tracks all active seleniumSessions.
- **Flexible Configuration:** Full support for `alwaysMatch` / `firstMatch` and vendor-specific options (`selenoid:options`).
- **File Uploads:** Easily upload files to the container during a test via the `/seleniumSession/{sessionId}/file` endpoint.
- **Live VNC Streaming:** Interactive, real-time access to the browser's desktop via any noVNC client.
- **Chrome DevTools Protocol (CDP) Proxy:** Direct access to the browser's DevTools for network emulation and other debugging tasks.

### 🎭 Playwright
- **Native Support:** Full integration for running Playwright tests.
- **Command Proxying:** Reliably forwards commands from the test to the browser.
- **Session Management:** Dynamically creates and manages seleniumSessions in containers.

---

## ⚙️ Configuration (Environment Variables)

jelenoid-server

| Variable                   | Description                                                               | Default Value                          |
|----------------------------|---------------------------------------------------------------------------| -------------------------------------- |
| `PARALLEL_SESSIONS`        | The number of parallel seleniumSessions for Selenium tests.               | `10`                                   |
| `QUEUE_LIMIT`              | The queue limit for Selenium seleniumSessions.                            | `100`                                  |
| `BROWSERS_FILE`            | The path to the `browsers.json` file for image configuration.             | (internal file)                        |
| `QUEUE_TIMEOUT`            | The timeout for the Selenium queue (in ms).                               | `30000`                                |
| `SESSION_TIMEOUT`          | The inactivity timeout for a Selenium/Playwright seleniumSession (in ms). | `600000`                               |
| `STARTUP_TIMEOUT`          | The timeout for the job that tracks hanging seleniumSessions (in ms).     | `30000`                                |
| `UI_HOSTS_LIST`            | A comma-separated list of UI hosts for CORS.                              | `http://localhost:80,http://localhost` |
| `PLAYWRIGHT_SESSION_LIMIT` | The number of parallel seleniumSessions for Playwright tests.             | `10`                                   |
| `PLAYWRIGHT_QUEUE_LIMIT`   | The queue limit for Playwright seleniumSessions.                          | `100`                                  |
| `NATS_SERVER`              | A nats host fot collect logs.                                             | `nats://nats:4222`                                  |

container-manager

| Variable                     | Description                  | Default Value  |
|------------------------------|------------------------------|----------------|
| `CLEANUP_TIMEOUT`            | Container deletion timeout.  | `15000`        |
| `CONTAINER_STARTING_TIMEOUT` | Timeout for container start. | `60000`        |
| `DOCKER_NETWORK`             | Docker network name.         | `jelenoid-net` |
| `CONTAINER_MANAGER_PORT`     | App port.                    | `8080`         |

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
npm run dev --prefix jelenoid-ui
```

### Building Images
To build the server image:
```shell
docker build -t suomessa/jelenoid:server-latest .\jelenoid-server\
```

To build the UI image:
```shell
docker build -t suomessa/jelenoid:ui-latest .\jelenoid-ui\.
```

To build the metrics image:
```shell
docker build -t suomessa/jelenoid:metrics-latest .\service-metrics\.
```

Demo nginx for tests:
```shell
docker run -d --name mynginx -p 8080:80 nginx
```

</div>