# Yandex Browser headless для Jelenoid/Selenoid. Наследует базовый образ
# со всеми системными зависимостями и добавляет:
#   * Yandex Browser Stable из официального apt-репозитория
#     https://repo.yandex.ru/yandex-browser/deb
#   * YandexDriver с GitHub (https://github.com/yandex/YandexDriver/releases)
#   * managed_policies.json, отключающий SafeBrowsing и Yandex Protect
#   * cdp-proxy + wd-proxy (как у Chrome-варианта)
#
# Yandex Browser - это Chromium 146+ с обвязкой Yandex, поэтому совместим с
# chromedriver-протоколом через YandexDriver (бинарно-совместим с chromedriver,
# поддерживает W3C WebDriver, CDP и т.д.). Тот же cdp-proxy/wd-proxy, что и
# в Chrome-варианте, переиспользуется без изменений.
#
# Версии:
#   YANDEX_VERSION       - deb-пакет. latest-stable = apt-cache policy yandex-browser-stable,
#                          иначе - точная версия (например 26.4.1.1110-1).
#   YANDEXDRIVER_URL     - прямая ссылка на yandexdriver-<...>-linux.zip из
#                          релизов YandexDriver. build.py резолвит её по
#                          major.minor версии браузера (см. yandex/build.py).

# ---- go builders -------------------------------------------------------
FROM --platform=$BUILDPLATFORM golang:1.24-alpine AS cdp-proxy-builder
ARG TARGETARCH
ARG TARGETOS=linux
WORKDIR /src
COPY cdp-proxy/go.mod cdp-proxy/go.sum ./
COPY cdp-proxy/main.go ./
RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} go build -o /cdp-proxy .

FROM --platform=$BUILDPLATFORM golang:1.24-alpine AS wd-proxy-builder
ARG TARGETARCH
ARG TARGETOS=linux
WORKDIR /src
COPY wd-proxy/go.mod ./
COPY wd-proxy/main.go ./
RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} go build -o /wd-proxy .

# ---- yandex-downloader: ставит Yandex Browser + качает YandexDriver ----
FROM jelenoid/selenium-base:bookworm AS yandex-downloader

ARG YANDEX_VERSION=latest-stable
ARG YANDEXDRIVER_URL=""

USER root

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        dirmngr \
        gpg; \
    curl -fsSL https://repo.yandex.ru/yandex-browser/YANDEX-BROWSER-KEY.GPG \
        | gpg --dearmor -o /usr/share/keyrings/yandex-browser-archive-keyring.gpg; \
    echo "deb [signed-by=/usr/share/keyrings/yandex-browser-archive-keyring.gpg] https://repo.yandex.ru/yandex-browser/deb stable main" \
        > /etc/apt/sources.list.d/yandex-browser.list; \
    apt-get update; \
    if [ "${YANDEX_VERSION}" = "latest-stable" ]; then \
        YANDEX_PKG_VER=$(apt-cache policy yandex-browser-stable | awk '/Candidate:/{print $2; exit}'); \
    else \
        YANDEX_PKG_VER="${YANDEX_VERSION}"; \
    fi; \
    echo "Resolved YANDEX_VERSION=${YANDEX_PKG_VER}"; \
    apt-get install -y --no-install-recommends --allow-downgrades \
        "yandex-browser-stable=${YANDEX_PKG_VER}"; \
    apt-get purge -y --auto-remove dirmngr gpg; \
    rm -rf /var/lib/apt/lists/*; \
    if [ -z "${YANDEXDRIVER_URL}" ]; then \
        echo "ERROR: YANDEXDRIVER_URL is required" >&2; \
        exit 1; \
    fi; \
    mkdir -p /opt/yandexdriver; \
    echo "Downloading YandexDriver from ${YANDEXDRIVER_URL}"; \
    curl -fsSL -o /tmp/driver.zip "${YANDEXDRIVER_URL}"; \
    unzip -q /tmp/driver.zip -d /opt/yandexdriver; \
    rm /tmp/driver.zip; \
    # GitHub-зип выкладывает бинарь без +x - выставляем явно.
    find /opt/yandexdriver -type f -name 'yandexdriver*' -exec chmod 0755 {} +; \
    ls -la /opt/yandex/browser /opt/yandexdriver

# ---- final: образ с Yandex Browser + YandexDriver + cdp-proxy + wd-proxy
FROM jelenoid/selenium-base:bookworm
ARG YANDEX_VERSION=latest-stable

LABEL org.opencontainers.image.title="jelenoid-yandex-headless" \
      org.opencontainers.image.description="Yandex Browser + YandexDriver for Jelenoid/Selenoid (headless)" \
      org.opencontainers.image.source="https://github.com/balakshievas/jelenoid" \
      org.opencontainers.image.licenses="GPL-3.0"

USER root
COPY --from=cdp-proxy-builder --chown=root:root --chmod=755 /cdp-proxy /usr/local/bin/cdp-proxy
COPY --from=wd-proxy-builder --chown=root:root --chmod=755 /wd-proxy /usr/local/bin/wd-proxy
COPY --from=yandex-downloader --chown=jelenoid:jelenoid /opt/yandex/browser /opt/yandex/browser
COPY --from=yandex-downloader --chown=jelenoid:jelenoid /opt/yandexdriver /opt/yandexdriver
COPY --chown=root:root --chmod=755 entrypoint.sh /usr/local/bin/entrypoint.sh
COPY --chown=root:root --chmod=755 start-headless.sh /usr/local/bin/start-headless.sh
COPY --chown=root:root --chmod=644 managed_policies.json /etc/opt/yandex/browser/policies/managed/managed_policies.json
# COPY создаёт родительские каталоги без execute-бита - Yandex Browser не сможет
# прочитать policies без +x на каждом уровне пути.
RUN chmod 0755 /etc/opt/yandex /etc/opt/yandex/browser /etc/opt/yandex/browser/policies /etc/opt/yandex/browser/policies/managed

ENV YANDEX_BIN=/usr/local/bin/google-chrome \
    YANDEXDRIVER_BIN=/opt/yandexdriver/yandexdriver \
    YANDEXDRIVER_PORT=4444

# Wrapper добавляет флаги, необходимые для запуска Yandex Browser в Docker без прав root.
# Имя google-chrome выбрано намеренно - selenium-клиенты часто передают chromeOptions,
# а YandexDriver читает CHROME_BIN, как и chromedriver.
RUN { echo '#!/bin/sh'; echo 'exec /opt/yandex/browser/yandex-browser --no-sandbox --disable-gpu --headless "$@"'; } > /usr/local/bin/google-chrome \
    && chmod 755 /usr/local/bin/google-chrome \
    && ln -sf /opt/yandex/browser/yandex-browser /usr/local/bin/yandex-browser \
    && ln -sf /opt/yandex/browser/yandex-browser /usr/local/bin/yandex-browser-stable \
    && ln -sf /opt/yandexdriver/yandexdriver /usr/local/bin/yandexdriver \
    && ln -sf /opt/yandexdriver/yandexdriver /usr/local/bin/chromedriver
# Переопределяем CHROME_BIN, чтобы YandexDriver использовал wrapper.
ENV CHROME_BIN=/usr/local/bin/google-chrome
USER jelenoid

EXPOSE 4444 7070

HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 \
    CMD curl -fsS http://127.0.0.1:4444/status | grep -q '"ready":true' || exit 1

WORKDIR /home/jelenoid

ENTRYPOINT ["dumb-init", "--", "/usr/local/bin/entrypoint.sh"]
CMD ["/usr/local/bin/start-headless.sh"]
