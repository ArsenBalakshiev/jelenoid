[English](./README.md) | [Русский](./README_ru.md)

---

# Jelenoid: Мощный и легковесный Selenium хаб на Java/Spring

![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![Version](https://img.shields.io/badge/version-1.0.0-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-brightgreen)
![Docker](https://img.shields.io/badge/Docker-Supported-blue)

**Jelenoid** — это высокопроизводительный, легковесный и полностью настраиваемый Selenium-хаб, написанный на Java/Spring. Он предоставляет нативную поддержку **Selenium** и **Playwright**, позволяя запускать UI-тесты в изолированных Docker-контейнерах через единый API.

В отличие от простых прокси, Jelenoid выступает в роли полноценного оркестратора, динамически управляя жизненным циклом браузерных сессий, обеспечивая чистоту окружения и надежность для каждого тестового запуска.

---

## 🚀 Ключевые возможности

### 🌐 Общая функциональность
- **Динамическое управление контейнерами:** Автоматический запуск и остановка Docker-контейнеров для каждой сессии.
- **Полная изоляция тестов:** Чистая среда для каждого запуска.
- **Ограничение ресурсов:** Установка лимитов на количество параллельных сессий и версий браузеров.
- **Очередь запросов:** Интегрированный механизм очередей для управления нагрузкой, что критически важно для CI/CD.
- **Jelenoid UI:** Простой и удобный веб-интерфейс для мониторинга сессий.


### 🤖 Selenium
- **Полное проксирование W3C WebDriver:** Надежная передача всех команд протокола.
- **Централизованное управление состоянием:** Единый сервис (`ActiveSessionsService`) для отслеживания всех активных сессий.
- **Гибкая конфигурация:** Полная поддержка `alwaysMatch` / `firstMatch` и вендорных опций (`selenoid:options`).
- **Загрузка файлов:** Простая загрузка файлов в контейнер во время теста через эндпоинт `/seleniumSession/{sessionId}/file`.
- **Live VNC Streaming:** Интерактивный доступ к рабочему столу браузера в реальном времени через любой noVNC-клиент.
- **Chrome DevTools Protocol (CDP) Proxy:** Прямой доступ к DevTools браузера для эмуляции сети и других отладочных задач.

### 🎭 Playwright
- **Нативная поддержка:** Полноценная интеграция для запуска тестов Playwright.
- **Проксирование команд:** Надежная передача команд от теста к браузеру.
- **Управление сессиями:** Динамическое создание и управление сессиями в контейнерах.

---

## ⚙️ Конфигурация (Environment-переменные)

Приложение настраивается с помощью переменных окружения.

| Переменная                 | Описание                                                               | Значение по умолчанию |
| -------------------------- | ---------------------------------------------------------------------- | --------------------- |
| `PARALLEL_SESSIONS`        | Количество параллельных сессий для Selenium тестов.                      | `10`                  |
| `QUEUE_LIMIT`              | Лимит очереди для Selenium сессий.                                     | `100`                 |
| `DOCKER_NETWORK`           | Docker-сеть для контейнеров.                                           | `jelenoid-net`        |
| `BROWSERS_FILE`            | Путь к файлу `browsers.json` для настройки образов.                     | (внутренний файл)     |
| `QUEUE_TIMEOUT`            | Таймаут ожидания в очереди Selenium (мс).                               | `30000`               |
| `SESSION_TIMEOUT`          | Таймаут неактивности сессии Selenium/Playwright (мс).                   | `600000`              |
| `STARTUP_TIMEOUT`          | Таймаут для job'ы, отслеживающей зависшие сессии (мс).                  | `30000`               |
| `CLEANUP_TIMEOUT`          | Таймаут на удаление контейнера (мс).                                     | `15000`               |
| `CONTAINER_STARTING_TIMEOUT` | Таймаут на запуск контейнера (мс).                                       | `60000`               |
| `UI_HOSTS_LIST`            | Список хостов UI для CORS (через запятую).                              | `http://localhost:80,http://localhost` |
| `PLAYWRIGHT_PORT`          | Порт внутри контейнера Playwright.                                     | `3000`                |
| `PLAYWRIGHT_DEFAULT_VERSION`| **(Обязательно)** Версия Playwright по умолчанию.                      | (нет)                 |
| `PLAYWRIGHT_SESSION_LIMIT` | Количество параллельных сессий для Playwright тестов.                   | `10`                  |
| `PLAYWRIGHT_QUEUE_LIMIT`   | Лимит очереди для Playwright сессий.                                   | `100`                 |


---

## 🛠️ Использование и команды

### Запуск в режиме разработки
Для запуска сервера в Docker (с hot-reload для разработки):
```shell
docker-compose up -d --build --force-recreate jelenoid-server
```

Для запуска менеджера контейнеров в Docker (с hot-reload для разработки):
```shell
docker-compose up -d --build --force-recreate container-manager
```

Запуск ui:
```shell
npm run dev --prefix jelenoid-ui
```

### Сборка образов
Сборка образа сервера:
```shell
docker build -t suomessa/jelenoid:server-latest .\jelenoid-server\
```

Сборка образа UI:
```shell
docker build -t suomessa/jelenoid:ui-latest .\jelenoid-ui\.
```

Сборка образа метрик:
```shell
docker build -t suomessa/jelenoid:metrics-latest .\service-metrics\.
```

Demo nginx для тестов:
```shell
docker run -d --name mynginx -p 8080:80 nginx
```