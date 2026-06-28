# Chrome headless-shell для Jelenoid/Selenoid. Наследует базовый образ со всеми
# системными зависимостями. Версию Chrome можно указать явно
# (--build-arg CHROME_VERSION=...), по умолчанию берётся последняя Stable.
#
# Используется chrome-headless-shell из Chrome for Testing — минимальный
# браузерный бинарник для headless-автоматизации (меньше полного Chrome).

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

# ---- chrome-downloader: парсит JSON и качает бинарники -----------------
FROM jelenoid/selenium-base:bookworm AS chrome-downloader
ARG TARGETARCH=amd64
ARG CHROME_VERSION=latest-stable
ARG CHROME_VERSION_JSON_URL=https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json

USER root
COPY --from=jelenoid/selenium-base:bookworm /usr/local/share/jelenoid/get-latest-chrome.sh /tmp/get-latest-chrome.sh
RUN set -eux; \
    chmod +x /tmp/get-latest-chrome.sh; \
    if [ "${CHROME_VERSION}" = "latest-stable" ]; then \
        eval "$(CHROME_VERSION_JSON_URL=${CHROME_VERSION_JSON_URL} /tmp/get-latest-chrome.sh)"; \
    else \
        case "${TARGETARCH}" in \
            amd64) PLATFORM=linux64 ;; \
            arm64) PLATFORM=linux-arm64 ;; \
            *) echo "Unsupported arch: ${TARGETARCH}"; exit 1 ;; \
        esac; \
        HEADLESS_SHELL_URL="https://storage.googleapis.com/chrome-for-testing-public/${CHROME_VERSION}/${PLATFORM}/chrome-headless-shell-${PLATFORM}.zip"; \
        CHROMEDRIVER_URL="https://storage.googleapis.com/chrome-for-testing-public/${CHROME_VERSION}/${PLATFORM}/chromedriver-${PLATFORM}.zip"; \
    fi; \
    echo "Resolving CHROME_VERSION=${CHROME_VERSION}"; \
    echo "  HEADLESS_SHELL_URL=${HEADLESS_SHELL_URL}"; \
    echo "  CHROMEDRIVER_URL=${CHROMEDRIVER_URL}"; \
    mkdir -p /opt/chrome /opt/chromedriver; \
    curl -fsSL -o /tmp/headless-shell.zip "${HEADLESS_SHELL_URL}"; \
    curl -fsSL -o /tmp/driver.zip "${CHROMEDRIVER_URL}"; \
    unzip -q /tmp/headless-shell.zip -d /opt/chrome; \
    unzip -q /tmp/driver.zip -d /opt/chromedriver; \
    rm /tmp/headless-shell.zip /tmp/driver.zip

# ---- final: образ с chrome-headless-shell + chromedriver + cdp-proxy ---
FROM jelenoid/selenium-base:bookworm
ARG CHROME_VERSION=latest-stable

LABEL org.opencontainers.image.title="jelenoid-chrome-headless-shell" \
      org.opencontainers.image.description="Chrome headless-shell + ChromeDriver for Jelenoid/Selenoid" \
      org.opencontainers.image.source="https://github.com/balakshievas/jelenoid" \
      org.opencontainers.image.licenses="GPL-3.0"

ENV CHROME_BIN=/opt/chrome/chrome-headless-shell-linux64/chrome-headless-shell \
    CHROMEDRIVER_BIN=/opt/chromedriver/chromedriver-linux64/chromedriver \
    CHROMEDRIVER_PORT=4444

COPY --from=cdp-proxy-builder --chown=root:root --chmod=755 /cdp-proxy /usr/local/bin/cdp-proxy
COPY --from=wd-proxy-builder --chown=root:root --chmod=755 /wd-proxy /usr/local/bin/wd-proxy
COPY --from=chrome-downloader --chown=jelenoid:jelenoid /opt/chrome /opt/chrome
COPY --from=chrome-downloader --chown=jelenoid:jelenoid /opt/chromedriver /opt/chromedriver
COPY --chown=root:root --chmod=755 entrypoint.sh /usr/local/bin/entrypoint.sh
COPY --chown=root:root --chmod=755 start-headless.sh /usr/local/bin/start-headless.sh

USER root
# Wrapper добавляет флаги, необходимые для запуска Chrome в Docker без прав root.
RUN { echo '#!/bin/sh'; echo 'exec /opt/chrome/chrome-headless-shell-linux64/chrome-headless-shell --no-sandbox --disable-gpu --headless "$@"'; } > /usr/local/bin/google-chrome \
    && chmod 755 /usr/local/bin/google-chrome \
    && ln -sf /opt/chromedriver/chromedriver-linux64/chromedriver /usr/local/bin/chromedriver
# Переопределяем CHROME_BIN, чтобы chromedriver использовал wrapper.
ENV CHROME_BIN=/usr/local/bin/google-chrome
USER jelenoid

EXPOSE 4444 7070

HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 \
    CMD curl -fsS http://127.0.0.1:4444/status | grep -q '"ready":true' || exit 1

WORKDIR /home/jelenoid

ENTRYPOINT ["dumb-init", "--", "/usr/local/bin/entrypoint.sh"]
CMD ["/usr/local/bin/start-headless.sh"]
