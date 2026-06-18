# AGENTS.md — Jelenoid

Полное руководство по архитектуре, конвенциям и рабочим процессам репозитория **Jelenoid** — высокопроизводительного Selenium + Playwright хаба для изолированного запуска UI-тестов в Docker-контейнерах.

---

## 1. Что это за проект

Jelenoid — оркестратор браузерных сессий. Каждый запрос тестового клиента порождает **отдельный Docker-контейнер с браузером**, который после завершения сессии удаляется. Хаб дополнительно:

- ограничивает количество параллельных сессий и ставит запросы в очередь;
- проксирует W3C WebDriver, Chrome DevTools Protocol (CDP), VNC и Playwright WebSocket;
- хранит конфигурацию браузеров (`browsers.json`) с возможностью hot-edit через API;
- публикует события о состоянии через Server-Sent Events (для UI);
- ведёт пул переиспользуемых Playwright-контейнеров для снижения холодного старта.

---

## 2. Архитектура (поток запроса)

```
Test client (Selenium/Playwright SDK)
      │
      ▼
jelenoid-server  (Go, :4444)
  ├── WebDriver proxy           POST/DELETE /wd/hub/session[/{id}]
  ├── Playwright WebSocket      /playwright, /playwright-{version}
  ├── CDP proxy                 /session/{id}/se/cdp
  ├── VNC proxy                 /vnc/{sessionId}
  ├── SSE-канал состояния       /events
  ├── REST API                  /api/browsers, /api/limit/*
  └── Логи контейнера           /logs/{sessionId}
      │
      ▼ HTTP
container-manager  (Go, :8080)
  ├── /api/containers/selenium   POST (start)
  ├── /api/containers/playwright POST (start)
  ├── /api/containers?containerId=…  DELETE
  ├── /api/containers/{id}/file  POST  (multipart upload)
  ├── /api/containers/{id}/logs  GET   (SSE stream)
  └── /health                    GET
      │
      ▼ unix socket
/var/run/docker.sock → браузерные контейнеры

Параллельно:
jelenoid-ui       (React/Vite, :80 prod / :5173 dev) ← SSE + REST → jelenoid-server
test-app          (Node, :3000) — подсобное приложение для E2E
```

---

## 3. Состав репозитория (что есть и что активно)

| Путь                                | Язык/стек          | Назначение                                                                 | Статус |
|-------------------------------------|--------------------|----------------------------------------------------------------------------|--------|
| `jelenoid-server/`                  | Go 1.24            | Главный хаб (Selenium + Playwright) на :4444                               | **active** |
| `container-manager/`                | Go 1.25            | Docker-lifecycle на :8080 (через `/var/run/docker.sock`)                   | **active** |
| `jelenoid-ui/`                      | TS/React 19 + Vite 5 | Мониторинг-интерфейс, читает `/events`, `/api/browsers`, `/logs`, `/vnc`   | **active** |
| `tests/`                            | Java 21 + Maven    | E2E/интеграционные/нагрузочные тесты (Selenium, Playwright, Selenide)      | active |
| `test-app/`                         | Node 20 + Express  | Минимальный сайт для прогонов тестов (`host.docker.internal:3000`)         | вспомогательное |
| `playwright_images/`                | Dockerfile         | Сборка кастомного образа Playwright + VNC (`suomessa/jelenoid:playwright-chromium-vnc-1.58.0`) | вспомогательное |
| `browser-config/`, `build/`, `target/`, `playwright_images/` | — | Пустые/артефактные директории; не используются рантаймом                   | ignore |
| `browsers.json`                     | JSON               | Каталог образов: `chrome`, `yandex`, `playwright` (default + versions)     | **активный** |
| `docker-compose.yml`                | Compose v3.8       | Dev-стек: hot-reload для Go-сервисов (`.air`), UI в dev-режиме, test-app   | active |
| `docker-compose-prod.yml`           | Compose v3.8       | Prod-стек: готовые образы `suomessa/jelenoid:*-latest`                     | active |
| `README.md`, `LICENSE` (GPLv3)      | —                  | Документация проекта                                                       | — |

---

## 4. Дерево исходников — ключевые файлы

### 4.1 `jelenoid-server/` (Go)
```
cmd/server/main.go                 ← точка входа, монтаж зависимостей, регистрация роутов
internal/config/config.go          ← env → Config (ServerPort, PublicHost, лимиты, таймауты, pool)
internal/dto/dto.go                ← BrowserInfo, ContainerInfo, SeleniumSession, PendingRequest, StatusResponse, PlaywrightPoolStatsDTO
internal/handlers/
  wd_hub.go                        ← /wd/hub/session (create/delete/proxy/upload)
  middleware.go                    ← CORS
  active_sessions.go               ← /api/limit/* (счётчики)
  browser_manager.go               ← /api/browsers (get/add/delete)
  events.go                        ← /events (SSE)
  logs.go                          ← /logs/{id} (SSE стрим логов контейнера)
  vnc_proxy.go                     ← /vnc/{id} (WebSocket→TCP 5900 прокси)
  devtools_proxy.go                ← /session/{id}/se/cdp (WebSocket прокси к 7070)
internal/services/
  active_sessions.go               ← центральный реестр: Selenium- и Playwright-сессии, очереди, слоты
  selenium_session.go              ← бизнес-логика: создание/удаление, ReverseProxy, аплоад файлов, ProcessQueue, HTTPError
  playwright_session.go            ← WS-прокси Playwright + пул/без пула, Shutdown
  playwright_container_pool.go     ← пул Playwright-контейнеров (maxSize, maxPerKey, idle eviction)
  browser_manager.go               ← in-memory snapshot + debounce persist в browsers.json
  docker_external.go               ← HTTP-клиент к container-manager
  emitter.go                       ← SSEHub
  status.go / status_notifier.go   ← сборка StatusResponse + батчинг событий (100мс debounce)
  queue.go                         ← generic queue[T] (FIFO с компакцией)
go.mod                             ← module github.com/balakshievas/jelenoid-server-go
.air.toml                          ← air hot-reload (bin=./tmp/main, build cmd)
Dockerfile / Dockerfile.dev
```

**Роуты jelenoid-server (см. `cmd/server/main.go:84-141`):**
- `POST   /wd/hub/session`                — создать Selenium-сессию
- `DELETE /wd/hub/session[/{id}]`         — закрыть
- `POST   /wd/hub/session/{id}/file` — загрузить файл (base64-ZIP)
- `/wd/hub/session/{id}/`                 — ReverseProxy к контейнеру
- `/api/limit/sessions|request|…`         — счётчики и лимиты
- `/api/browsers[?add|delete]`            — CRUD каталога
- `/events`                               — SSE `state-update`
- `/logs/{sessionId}`                     — SSE логи
- `/vnc/{sessionId}`                      — WS прокси на 5900
- `/session/{id}/se/cdp`                  — WS прокси на 7070
- `/playwright`, `/playwright/`, `/playwright-{version}` — WebSocket Playwright

### 4.2 `container-manager/` (Go)
```
main.go                       ← http.ServeMux + /health
config/config.go              ← env (Port, DockerNetwork, ShmSize, Tmpfs, таймауты)
api/handlers.go               ← HTTP-хендлеры + SSE-обёртка SseWriter
docker/base.go                ← Manager: NewClientWithOpts, StopContainer, ImageExists, WaitForPort, CopyFileToContainer (zip→tar, защита от zip-bomb 50MB)
docker/selenium.go            ← StartSelenium: имя `jelenoid-session-<uuid>`, network=jelenoid-net, shm, /tmp tmpfs, ждёт http://container:4444/status {"ready":true}
docker/playwright.go          ← StartPlaywright: имя `jelenoid-playwright-<uuid[:8]>`, Init, IpcMode=host, CapAdd=SYS_ADMIN, shm, /tmp tmpfs, cmd: `npx -y playwright@<ver> run-server --port 3000 --host 0.0.0.0`, ждёт TCP :3000
models/models.go              ← ContainerInfo (те же поля, что в jelenoid-server/dto)
go.mod                        ← module github.com/balakshievas/Jelenoid/container-manager
.air.toml / Dockerfile / Dockerfile.dev
```

### 4.3 `jelenoid-ui/` (TS/React/Vite)
```
src/main.tsx                  ← bootstrap
src/App.tsx                   ← EventSource('/events'), tabs (Monitoring / Manual session)
src/types/server.ts           ← типы ServerState, SeleniumStat, PlaywrightStat, SessionPairInfo, MonitoringSession (discriminated union kind: 'selenium' | 'playwright')
src/components/header/        ← шапка: connection status + счётчики очередей и лимитов
src/components/tabs/          ← простая панель табов
src/components/MonitoringTab/ ← список сессий, выбор, логи + VNC; использует @novnc/novnc core/rfb (alias → lib/rfb.js)
  SessionCard.tsx             ← карточка Selenium/Playwright, кнопки «Мониторинг» / «Закрыть сессию» (DELETE /wd/hub/session/{id})
  SessionLogsCard.tsx         ← EventSource('/logs/{id}')
  VncSessionCard.tsx          ← WS-URL + RFB connect/disconnect, fullscreen
  VncScreen.tsx               ← обёртка над @novnc/novnc core/rfb, PASS = 'selenoid', scaleViewport, ResizeObserver
src/components/ManualSessionTab/ManualSessionTab.tsx ← форма: GET /api/browsers → POST /wd/hub/session (selenoid:options.enableVNC)
public/assets/                ← selenium_logo.jpg, playwright_logo.png
docker/nginx.conf              ← SPA fallback try_files $uri /index.html
vite.config.ts                 ← VITE_SERVER_BASE_URL (loadEnv), proxy '/events' в dev, alias '@novnc/novnc/core/rfb'
.env                           ← VITE_SERVER_BASE_URL=http://localhost:4444, VITE_UI_PORT=8080
```

### 4.4 `tests/` (Java/Maven, JUnit 5)
Зависимости: `selenium-java 4.31.0`, `playwright 1.58.0`, `selenide 7.9.1`, `junit-jupiter 5.12.2`, `testcontainers 1.21.3`, `awaitility 4.2.2`, `Java-WebSocket 1.5.4`, `jackson 2.18.2`, `lombok 1.18.36`.

Классы (см. `src/test/java/com/balakshievas/tests/`):

- `selenium/BaseSeleniumJelenoidTest.java` — `HUB_URL = System.getProperty("hub.url", "http://localhost:4444/wd/hub")`, по умолчанию Chrome 133, `selenoid:options.enableVNC=true`, `@AfterEach` quit.
- `selenium/SeleniumE2ETest.java` — ~40 кейсов против `test-app` (`http://host.docker.internal:3000`).
- `selenium/SeleniumJelenoidIntegrationTest.java` — реальный интернет, проверки VNC, аплоад файла через `/file`, обработка `Not found browser version`.
- `selenium/SeleniumJelenoidLoadTest.java` — `PARALLEL_TESTS=30` через `Executors.newVirtualThreadPerTaskExecutor()`.
- `selenium/SeleniumJelenoidThroughputTest.java` — `ACTIVE_SESSIONS=10` × `COMMANDS_PER_SESSION=2000` `getTitle()`.
- `playwright/BasePlaywrightJelenoidTest.java` — `playwright.ws = ws://localhost:4444/playwright`.
- `playwright/PlaywrightE2ETest.java` — ~35 кейсов (cookies, drag&drop, storage, network intercept, timeouts, parameterized nav).
- `playwright/PlaywrightJelenoidIntegrationTest.java` — смоук против `http://host.docker.internal:8080/` (nginx).
- `playwright/PlaywrightJelenoidProxyStressTest.java` — `TOTAL_SESSIONS_TO_TEST=20` при лимите 10, проверяет очередь.
- `playwright/PlaywrightJelenoidThroughputTest.java` — RPS-бенчмарк.
- `playwright/PlaywrightJelenoidVersionsTest.java` — `/playwright` vs `/playwright-1.58.0`.
- `selenide/BaseSelenideTest.java` — `Configuration.remote = http://localhost:4444/wd/hub`, browser=chrome 133.
- `selenide/SelenideShowcaseTest.java` — примеры с `$()`.

### 4.5 `test-app/`
Node/Express на 3000. Раздаёт статику из `public/` (15 HTML-страниц: `index`, `slow`, `dynamic`, `cookies`, `iframe`, `dropdown`, `checkboxes`, `alerts`, `forms`, `hidden`, `dragdrop`, `upload`, `tables`, `storage`, `iframe-content`) + API: `/api/delay/:s`, `/api/cookies`, `/api/slow-response`, `/api/download`, `/api/slow-download`. Тесты обращаются к нему по `http://host.docker.internal:3000`.

### 4.6 `playwright_images/Dockerfile`
`ubuntu:24.04` + Node 20 + `playwright@${PLAYWRIGHT_VERSION}` + `playwright install --with-deps chromium`. Собирает кастомный образ `suomessa/jelenoid:playwright-chromium-vnc-1.58.0`.

---

## 5. Команды

### 5.1 Go-сервисы (dev в Docker, hot-reload)
```sh
docker-compose up -d --build --force-recreate jelenoid-server
docker-compose up -d --build --force-recreate container-manager
```

### 5.2 Go-сервисы (нативно)
```sh
cd jelenoid-server && go run ./cmd/server
cd container-manager  && go run main.go
```

### 5.3 UI
```sh
# В Docker
docker-compose up -d jelenoid-ui

# Нативно (Node 20)
cd jelenoid-ui && npm install && npm run dev
```

### 5.4 Линт UI
```sh
cd jelenoid-ui && npm run lint
```

### 5.5 Продакшен-стек
```sh
docker-compose -f docker-compose-prod.yml up -d
```

### 5.6 Сборка образов
```sh
docker build -t suomessa/jelenoid:server-latest            .\jelenoid-server\
docker build -t suomessa/jelenoid:container-manager-latest .\container-manager\
docker build -t suomessa/jelenoid:ui-latest                .\jelenoid-ui\
docker build -t suomessa/jelenoid:test-app-latest           .\test-app\
```

### 5.7 E2E-тесты (нужен запущенный hub на `localhost:4444`)
```sh
cd tests
mvn test                                                      # всё
mvn test -Dtest=SeleniumE2ETest                               # один класс
mvn test -Dhub.url=http://localhost:4444/wd/hub               # кастомный hub
mvn test -Dplaywright.ws=ws://localhost:4444/playwright-1.58.0 # версия playwright
```

---

## 6. Конфигурация (env)

### 6.1 `jelenoid-server` (см. `internal/config/config.go`)
| Переменная                          | Default                                | Назначение |
|-------------------------------------|----------------------------------------|------------|
| `JELENOID_PORT`                     | `4444`                                 | Порт HTTP |
| `JELENOID_PUBLIC_HOST`              | `0.0.0.0`                              | **Обязательно** `0.0.0.0` в проде, иначе не примет внешние подключения (используется для ссылки `ws://…/session/{id}/se/cdp` в `selenoid:options.se:cdp`) |
| `CONTAINER_MANAGER_ADDRESS`         | `http://container-manager:8080`        | URL container-manager |
| `BROWSERS_FILE`                     | `browsers.json`                        | Путь к каталогу образов (монтируется read-only) |
| `PARALLEL_SESSIONS`                 | `10`                                   | Лимит параллельных Selenium-сессий |
| `QUEUE_LIMIT`                       | `100`                                  | Ёмкость Selenium-очереди |
| `QUEUE_TIMEOUT`                     | `30000` (мс)                           | Таймаут ожидания в очереди |
| `SESSION_TIMEOUT`                   | `600000` (мс, 10 мин)                  | Неактивность → остановка контейнера |
| `STARTUP_TIMEOUT`                   | `30000` (мс)                           | Интервал тикера `CheckInactiveSessions` |
| `UI_HOSTS_LIST`                     | `http://localhost:3000,http://localhost:4444` | CORS allowlist (через запятую) |
| `PLAYWRIGHT_SESSION_LIMIT`          | `10`                                   | Лимит параллельных Playwright WS |
| `PLAYWRIGHT_QUEUE_LIMIT`            | `100`                                  | Ёмкость Playwright-очереди |
| `PLAYWRIGHT_CONTAINER_POOL_ENABLED` | `false`                                | Включить пул Playwright-контейнеров |
| `PLAYWRIGHT_CONTAINER_POOL_MAX_SIZE`| `10`                                   | Общий размер пула |
| `PLAYWRIGHT_CONTAINER_POOL_MAX_PER_KEY` | `5`                                | Максимум контейнеров на ключ `image\|version` |
| `PLAYWRIGHT_CONTAINER_POOL_IDLE_MS` | `60000`                                | Через сколько мс idle-контейнер удаляется |
| `ENABLE_QUEUE`                      | `true`                                 | Если `false` — без очереди, `503` при переполнении (используется в `server-perf` инстансе) |

### 6.2 `container-manager`
| Переменная                     | Default        | Назначение |
|--------------------------------|----------------|------------|
| `CONTAINER_MANAGER_PORT`       | `8080`         | Порт HTTP |
| `DOCKER_NETWORK`               | `jelenoid-net` | Сеть для запуска контейнеров |
| `PLAYWRIGHT_PORT`              | `3000`         | Порт run-server внутри Playwright-контейнера |
| `PLAYWRIGHT_CONTAINER_SHM_SIZE`| `4294967296`   | 4GiB /dev/shm |
| `PLAYWRIGHT_CONTAINER_TMPFS_SIZE` | `2g`        | Размер /tmp tmpfs |
| `SELENIUM_CONTAINER_SHM_SIZE`  | `2147483648`   | 2GiB /dev/shm |
| `SELENIUM_CONTAINER_TMPFS_SIZE`| `1g`           | Размер /tmp tmpfs |
| `CLEANUP_TIMEOUT`              | `15000` (мс)   | `docker stop --time` |
| `CONTAINER_STARTING_TIMEOUT`   | `60000` (мс)   | Ждать готовности контейнера |

### 6.3 `jelenoid-ui`
- `VITE_SERVER_BASE_URL` (default `http://localhost:4444`) — база для API/SSE/WS.
- `VITE_UI_PORT` (default `8080`) — для Vite dev server (на проде не используется).
- В прод-образе: `PORT=80`, entrypoint выполняет `vite-envs.sh` (см. `Dockerfile`) для runtime-инжекции env в HTML.

---

## 7. `browsers.json` — каталог образов

```jsonc
{
  "chrome":     { "default": "133", "versions": { "133": { "image": "twilio/selenoid:chrome_stable_133" }, "138": { "image": "twilio/selenoid:chrome_stable_138" } } },
  "yandex":     { "default": "133", "versions": { "133": { "image": "twilio/selenoid:yandex_stable_133" } } },
  "playwright": { "default": "1.58.0", "versions": { "1.53.0": { "image": "mcr.microsoft.com/playwright:v1.53.0" }, "1.58.0": { "image": "suomessa/jelenoid:playwright-chromium-vnc-1.58.0" } } }
}
```

- Читается `jelenoid-server` (в `BrowserManagerService.initBrowsersFromFile`), снапшот держится в памяти и с дебаунсом 500 мс пишется обратно.
- API: `GET /api/browsers`, `PUT /api/browsers/add` (создаёт или возвращает существующую запись), `DELETE /api/browsers/delete?browserName=…&browserVersion=…`.
- В `docker-compose.yml` файл монтируется read-only (`./browsers.json:/app/browsers.json:ro`). **Изменения в `browsers.json` на хосте требуют рестарта контейнера** (или редактирования через API, которое пишет в смонтированный путь).
- В `docker-compose-prod.yml` тот же файл монтируется read-only, но API в проде пишет в контейнерный путь (без обратной синхронизации).

---

## 8. Конвенции кода

### 8.1 Go
- Go 1.24 (`jelenoid-server`), Go 1.25 (`container-manager`). Модули называются по-разному — это нормально, не выравнивать.
- `internal/` пакеты; никаких циклов.
- Без сторонних фреймворков: только `net/http` (>=1.22 style routing), `gorilla/websocket`, `google/uuid`, `docker/docker`.
- Логирование — `log` пакета из stdlib (без структурного логгера; `selenoid/main.go` использует `log.Printf`).
- Конкурентность: `sync.RWMutex` + `atomic.Int32/Int64` для счётчиков, `chan struct{}` для семафоров, generic `queue[T]` (FIFO с периодической компакцией).
- HTTP-ошибки: типизированная `services.HTTPError{StatusCode, Message}` (см. `selenium_session.go:377`).
- SSE-канал: `SSEHub` (`emitter.go`) с initial snapshot + per-subscriber buffered channel.
- Все статусы переводятся через `services.StatusService.BuildStatus()`; broadcast дебаунсится 100 мс (см. `cmd/server/main.go:144-158`).

### 8.2 React/TS
- React 19 + Vite 5 + SWC. Строгий TS (`strict`, `noUnusedLocals`, `noUnusedParameters`, `verbatimModuleSyntax`).
- ESLint v8 (плоский конфиг `eslint.config.js`), правила `react-hooks/recommended`, `react-refresh/only-export-components`. Запуск: `npm run lint` (max-warnings 0).
- Алиас в `vite.config.ts`: `@novnc/novnc/core/rfb` → `@novnc/novnc/lib/rfb.js`. **Не удалять эту строку** — без неё Vite не сможет собрать `VncScreen.tsx`.
- Стиль — обычные CSS-файлы рядом с компонентом, без CSS-модулей/Tailwind.
- Без комментариев в коде (кроме разрешённых JSDoc в публичных API).

### 8.3 Java (только `tests/`)
- Java 21, Lombok, JUnit 5, Surefire 3.5.2. `ThreadLocal<WebDriver>` для изоляции тестов.

### 8.4 Общие правила
- **Не добавляй комментарии без явной просьбы.**
- **Не коммить без явной просьбы.**
- **Не храни секреты в репо**; для локальной разработки используй `.env`, который уже в `.gitignore`.
- **Перед PR обязательно прогони `npm run lint`** в `jelenoid-ui/` и `mvn -DskipTests package` для Java-частей, которые менял.

---

## 9. Gotchas (реальные «подводные камни»)

1. **`JELENOID_PUBLIC_HOST=0.0.0.0` обязателен в проде.** Без него `selenoid:options.se:cdp` (см. `selenium_session.go:190`) генерируется с `0.0.0.0:4444`, и клиенты извне не смогут подключиться. В `docker-compose-prod.yml` уже выставлен.
2. **`/var/run/docker.sock` обязателен** для `container-manager`. В Linux-окружении без него старт упадёт на `cli, err := client.NewClientWithOpts(...)`. В Windows/Mac через Docker Desktop маунт работает автоматически.
3. **Модули называются по-разному**: `jelenoid-server` → `github.com/balakshievas/jelenoid-server-go`, `container-manager` → `github.com/balakshievas/Jelenoid/container-manager` (заглавная `J`!). Не «исправляй» это.
4. **`browsers.json` смонтирован read-only** в `docker-compose.yml`. Любые изменения из контейнера уйдут в никуда. В проде (`docker-compose-prod.yml`) — то же самое.
5. **`container-manager/go.sum` в `.gitignore`** — генерируется автоматически при первой сборке.
6. **`jelenoid-server/browsers.json`** и **`jelenoid-server/jelenoid-server-test`** — в `.gitignore` (артефакты). Не путай с корневым `browsers.json`.
7. **`browser-config/`, `build/`, `target/`, `playwright_images/` (кроме Dockerfile)** — мусорные/пустые директории, оставлены от реорганизации; не используй.
8. **CDP WebSocket** проксируется на `container:7070` (см. `devtools_proxy.go:41`). Убедись, что образ `twilio/selenoid:chrome_stable_*` действительно слушает 7070 — для свежих образов это верно.
9. **VNC WebSocket** проксируется напрямую на TCP 5900 (см. `vnc_proxy.go:59`). Пароль по умолчанию в UI: `'selenoid'` (см. `VncScreen.tsx:12`). Образ должен стартовать с `ENABLE_VNC=true`, что хаб делает автоматически, если в `selenoid:options.enableVNC=true`.
10. **`PLAYWRIGHT_CONTAINER_POOL_ENABLED=true`** включает пул с дефолтным 10-секундным бездействием. Если тест быстро закрывает браузер, контейнер ещё «прогревается» — учти при замерах RPS.
11. **`tests` используют `http://host.docker.internal:3000`** — это работает на Docker Desktop (Win/Mac) и в `docker-compose.yml` через маппинг. На Linux-хосте может потребоваться `--add-host=host.docker.internal:host-gateway` или правка `TEST_APP_BASE` в базовых классах.
12. **Двух `BaseXxx` тестовых классов с разной семантикой** для Selenium (`BaseSeleniumJelenoidTest`, `BaseSelenideTest`) — не путай, у Selenide свой `Configuration.remote`.
13. **В `tests/pom.xml` используется `playwright 1.58.0`** — должен совпадать с версией, зарегистрированной в `browsers.json` для ключа `playwright`.
14. **Конфигурация UI в проде прокидывается через `vite-envs.sh`** (см. `jelenoid-ui/Dockerfile:16-21`). Если добавишь новую `VITE_*` переменную в `.env` — не забудь поддержать её в этом скрипте (см. комментарии в `vite.config.ts:1`).
15. **`dev` compose использует общий Go module cache** (`go-mod-cache` volume). Если меняешь `go.mod` в одном из сервисов, пересобери оба.
16. **No CI/CD.** Любая проверка — локально: `npm run lint` (UI), `mvn -DskipTests package` (Java), `go build ./...` (Go). Тесты — `cd tests && mvn test`.
17. **Concurrent session creation semantics**: `ActiveSessionsService.TryReserveSlot()` — атомарный счётчик; если > лимита, откатывает. Если очередь включена — `OfferToQueue` в FIFO. При освобождении слота `DeleteSession` явно вызывает `ProcessQueue`.
18. **`StatusService.BuildStatus()` всегда возвращает свежий срез** активных и queued сессий. `StatusNotifier.OnStatusChanged` триггерится из горутины в `cmd/server/main.go:144-158` с 100мс debounce — не вызывай `Broadcast` напрямую, используй `DispatchStatus()` или `statusChan`.

---

## 10. HTTP/WS-протоколы — как пользоваться хабом

### 10.1 Selenium (W3C)
```http
POST /wd/hub/session
Content-Type: application/json

{
  "capabilities": {
    "firstMatch": [{
      "browserName": "chrome",
      "browserVersion": "133",
      "selenoid:options": { "enableVNC": true }
    }]
  }
}
```
Возвращает `value.sessionId` — это **hubSessionId**, не remote. Используй его в `DELETE /wd/hub/session/{hubSessionId}` и в `/wd/hub/session/{hubSessionId}/...`. Любые `…/{remoteId}/...` хаб автоматически транслирует (см. `selenium_session.go:229-249`).

### 10.2 Аплоад файла в контейнер
```http
POST /wd/hub/session/{hubSessionId}/file
{ "file": "<base64-encoded zip>" }
```
Ответ: `{"value":"/<имя-файла>"}`. Контейнер-manager распаковывает ZIP (берёт первый файл, защита от zip-bomb 50 МБ), упаковывает в tar и `CopyToContainer` в `/`.

### 10.3 CDP
Клиент подключается к `ws://{publicHost}:4444/session/{hubSessionId}/se/cdp` — хаб проксирует на `ws://{containerName}:7070/devtools/page/{remoteSessionId}`. Ссылку можно получить в `capabilities.se:cdp` ответа на создание сессии.

### 10.4 VNC
Клиент подключается к `ws://{publicHost}:4444/vnc/{hubSessionId}` (subprotocol `binary`). Хаб проксирует на `tcp://{containerName}:5900`. Пароль — `selenoid`.

### 10.5 Playwright
```js
const browser = await chromium.connect('ws://localhost:4444/playwright');
// или конкретная версия:
const browser = await chromium.connect('ws://localhost:4444/playwright-1.58.0');
```
Маршрут разбирается в `playwright_session.go:92-100`. Контейнер стартует с `npx -y playwright@<version> run-server --port 3000 --host 0.0.0.0`.

### 10.6 SSE-канал состояния
```http
GET /events
```
При подключении отдаёт текущий `state-update`, далее шлёт его при изменениях. Структура — `dto.StatusResponse` (`SeleniumStat` + `PlaywrightStat` + опц. `PlaywrightPoolStatsDTO`).

### 10.7 Браузерный каталог
- `GET    /api/browsers` — список `BrowserInfo`.
- `PUT    /api/browsers/add` — `{"name": "...", "version": "...", "dockerImageName": "...", "isDefault": true}`.
- `DELETE /api/browsers/delete?browserName=chrome&browserVersion=138`.

---

## 11. Часто используемые сценарии работы с кодом

### Запустить полный dev-стек
```sh
docker-compose up -d --build --force-recreate
```

### Добавить новую версию Chrome
1. `browsers.json` → добавить версию под `chrome.versions` (см. §7).
2. Если хочется без рестарта: `curl -X PUT http://localhost:4444/api/browsers/add -d '{"name":"chrome","version":"140","dockerImageName":"twilio/selenoid:chrome_stable_140","isDefault":false}' -H 'Content-Type: application/json'`.
3. Убедиться, что образ `twilio/selenoid:chrome_stable_140` есть на хосте (или подтянется при первом старте).

### Добавить новую версию Playwright
1. `browsers.json` → добавить версию под `playwright.versions`.
2. Сбилдить кастомный образ через `playwright_images/Dockerfile` (или использовать `mcr.microsoft.com/playwright:vX.Y.Z`).
3. Тест-клиент подключается к `ws://hub:4444/playwright-X.Y.Z`.

### Изменить лимиты параллельности
В `docker-compose-prod.yml` (или `docker-compose.yml` для dev) поменять `PARALLEL_SESSIONS`, `QUEUE_LIMIT`, `PLAYWRIGHT_SESSION_LIMIT`, `PLAYWRIGHT_QUEUE_LIMIT` для `jelenoid-server`.

### Включить пул Playwright
`PLAYWRIGHT_CONTAINER_POOL_ENABLED=true` + `PLAYWRIGHT_CONTAINER_POOL_MAX_SIZE=N` + `PLAYWRIGHT_CONTAINER_POOL_IDLE_MS=N`. Пул переиспользует контейнеры по ключу `image|version` с TTL после последнего релиза.

### Прогнать только нагрузочный тест
```sh
cd tests && mvn test -Dtest=SeleniumJelenoidLoadTest
```

### Снять дамп логов конкретной Selenium-сессии
```sh
curl -N http://localhost:4444/logs/<hubSessionId>
```

---

## 12. Безопасность

- Аутентификация в текущей реализации **отсутствует**. Тестовые базовые классы передают `jelenoidToken` через `selenoid:options`, но он игнорируется. Не выставляй хаб в интернет без reverse-proxy с авторизацией.
- CORS allowlist управляется через `UI_HOSTS_LIST`. Символ `*` в этом списке не работает (точное сравнение, см. `handlers/middleware.go:12`).
- VNC без шифрования, пароль `selenoid` зашит в UI. Не используй VNC в недоверенной сети.
- `JELENOID_PUBLIC_HOST` влияет на ссылку `se:cdp` и должен совпадать с тем, как клиенты резолвят хаб.

---

## 13. Дополнительные заметки

- `browsers.json` хранит `isDefault` per-version; `BrowserManagerService` это инициализирует из `entry.Default == version`. При ручном добавлении через API поле `isDefault: true` сделает версию дефолтной для этого браузера.
- `ServiceBrowserInfo` снапшот — `atomic.Pointer[browserSnapshot]`, чтение lock-free, запись под `s.mu`.
- `cmd/server/main.go:84-141` — единственное место регистрации роутов; при добавлении нового эндпоинта не дублируй логику в `handlers`, а делай новый `ServeHTTP`-метод.
- `docker_external.go:124-170` — стрим логов реализован как goroutine с `chan []byte` (cap 256). Закрывается при `ctx.Done()` или EOF.
- `playwright_container_pool.go` — `poolKey(image, version) = image + "|" + version` (см. `playwright_container_pool.go:121`). Не используй `:` — это сепаратор для `browsers.json`.
- `go-mod-cache` volume в dev compose переиспользуется между двумя Go-сервисами. Если упадёт один модуль, может упасть и второй — `docker volume rm jelenoid_go-mod-cache` чинит.
- В `jelenoid-ui` `jelenoid-ui.iml` — артефакт IDEA, оставлен в репо. Не критично, но если у тебя нет IDEA — можешь удалить.
- `playwright_images/Dockerfile` собирает **только** `chromium` (`--with-deps chromium`). Если понадобятся firefox/webkit — поменяй аргумент.

---

## 14. Быстрые ссылки на ключевые места кода

- Точка входа хаба: `jelenoid-server/cmd/server/main.go:19`
- Резервирование слота: `jelenoid-server/internal/services/active_sessions.go:97`
- Очередь Selenium: `jelenoid-server/internal/services/selenium_session.go:72-114`
- Прокси-маршрутизатор W3C: `jelenoid-server/internal/handlers/wd_hub.go:55-67`
- WS Playwright: `jelenoid-server/internal/services/playwright_session.go:46-90`
- Пул Playwright: `jelenoid-server/internal/services/playwright_container_pool.go:133-204`
- VNC прокси: `jelenoid-server/internal/handlers/vnc_proxy.go:32-101`
- CDP прокси: `jelenoid-server/internal/handlers/devtools_proxy.go:23-81`
- SSE-хаб: `jelenoid-server/internal/services/emitter.go:66-113`
- HTTP-клиент к container-manager: `jelenoid-server/internal/services/docker_external.go:31-90`
- Старт Selenium-контейнера: `container-manager/docker/selenium.go:18-61`
- Старт Playwright-контейнера: `container-manager/docker/playwright.go:13-55`
- Конвертация ZIP→TAR + копирование: `container-manager/docker/base.go:80-140`
- Мониторинг-таб: `jelenoid-ui/src/components/MonitoringTab/MonitoringTab.tsx:20-81`
- VNC-экран: `jelenoid-ui/src/components/MonitoringTab/VncScreen.tsx:14-67`
- Ручное создание сессии: `jelenoid-ui/src/components/ManualSessionTab/ManualSessionTab.tsx:67-118`
- Базовый Selenium-тест: `tests/src/test/java/com/balakshievas/tests/selenium/BaseSeleniumJelenoidTest.java:14-87`
- Базовый Playwright-тест: `tests/src/test/java/com/balakshievas/tests/playwright/BasePlaywrightJelenoidTest.java:8-52`
