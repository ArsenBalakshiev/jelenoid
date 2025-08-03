[English](./README.md) | [Русский](./README_ru.md)

---

# Jelenoid: Powerful and Lightweight Selenium Hub on Java/Spring

![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![Version](https://img.shields.io/badge/version-1.0.0-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-brightgreen)
![Docker](https://img.shields.io/badge/Docker-Supported-blue)

**Jelenoid** is a high-performance, lightweight, and fully customizable Selenium hub written in Java/Spring. It provides native support for both **Selenium** and **Playwright**, allowing you to run UI tests in isolated Docker containers through a single API.

Unlike simple proxies, Jelenoid acts as a full-fledged orchestrator, dynamically managing the lifecycle of browser seleniumSessions to ensure a clean and reliable environment for every test run.

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

The application is configured using environment variables.

| Variable                   | Description                                                      | Default Value                          |
| -------------------------- | ---------------------------------------------------------------- | -------------------------------------- |
| `PARALLEL_SESSIONS`        | The number of parallel seleniumSessions for Selenium tests.              | `10`                                   |
| `QUEUE_LIMIT`              | The queue limit for Selenium seleniumSessions.                           | `100`                                  |
| `DOCKER_NETWORK`           | The Docker network for containers.                               | `jelenoid-net`                         |
| `BROWSERS_FILE`            | The path to the `browsers.json` file for image configuration.     | (internal file)                        |
| `QUEUE_TIMEOUT`            | The timeout for the Selenium queue (in ms).                      | `30000`                                |
| `SESSION_TIMEOUT`          | The inactivity timeout for a Selenium/Playwright seleniumSession (in ms).| `600000`                               |
| `STARTUP_TIMEOUT`          | The timeout for the job that tracks hanging seleniumSessions (in ms).    | `30000`                                |
| `CLEANUP_TIMEOUT`          | The timeout for container removal (in ms).                       | `15000`                                |
| `CONTAINER_STARTING_TIMEOUT` | The timeout for container startup (in ms).                       | `60000`                                |
| `UI_HOSTS_LIST`            | A comma-separated list of UI hosts for CORS.                     | `http://localhost:80,http://localhost` |
| `PLAYWRIGHT_PORT`          | The port inside the Playwright container.                        | `3000`                                 |
| `PLAYWRIGHT_DEFAULT_VERSION`| **(Required)** The default version of Playwright.                | (none)                                 |
| `PLAYWRIGHT_SESSION_LIMIT` | The number of parallel seleniumSessions for Playwright tests.            | `10`                                   |
| `PLAYWRIGHT_QUEUE_LIMIT`   | The queue limit for Playwright seleniumSessions.                         | `100`                                  |

---

## 🛠️ Usage and Commands

### Running in Development Mode
To run the server in Docker (with hot-reload for development):
```shell
docker-compose up -d --build --force-recreate jelenoid-server
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