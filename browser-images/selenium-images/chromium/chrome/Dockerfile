# Chrome headless для Jelenoid/Selenoid. Наследует базовый образ со всеми
# системными зависимостями (X11, GTK, шрифты, supervisord, socat).
# Версию Chrome можно указать явно (--build-arg CHROME_VERSION=...) —
# по умолчанию скрипт берёт последнюю Stable-версию с googlechromelabs.

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
        CHROME_URL="https://storage.googleapis.com/chrome-for-testing-public/${CHROME_VERSION}/${PLATFORM}/chrome-linux64.zip"; \
        CHROMEDRIVER_URL="https://storage.googleapis.com/chrome-for-testing-public/${CHROME_VERSION}/${PLATFORM}/chromedriver-linux64.zip"; \
    fi; \
    echo "Resolving CHROME_VERSION=${CHROME_VERSION}"; \
    if [ "${CHROME_VERSION}" = "latest-stable" ]; then \
        echo "Auto-detected:"; \
        echo "  CHROME_VERSION=${CHROME_VERSION}"; \
        echo "  CHROME_URL=${CHROME_URL}"; \
        echo "  CHROMEDRIVER_URL=${CHROMEDRIVER_URL}"; \
    fi; \
    mkdir -p /opt/chrome /opt/chromedriver; \
    curl -fsSL -o /tmp/chrome.zip "${CHROME_URL}"; \
    curl -fsSL -o /tmp/driver.zip "${CHROMEDRIVER_URL}"; \
    unzip -q /tmp/chrome.zip -d /opt/chrome; \
    unzip -q /tmp/driver.zip -d /opt/chromedriver; \
    rm /tmp/chrome.zip /tmp/driver.zip

# ---- final: собственно образ с chromedriver + socat-прокси --------------
FROM jelenoid/selenium-base:bookworm
ARG CHROME_VERSION=latest-stable

LABEL org.opencontainers.image.title="jelenoid-chrome-selenium" \
      org.opencontainers.image.description="Chrome + ChromeDriver for Jelenoid/Selenoid (headless)" \
      org.opencontainers.image.source="https://github.com/balakshievas/jelenoid" \
      org.opencontainers.image.licenses="GPL-3.0"

ENV CHROME_BIN=/opt/chrome/chrome-linux64/chrome \
    CHROMEDRIVER_BIN=/opt/chromedriver/chromedriver-linux64/chromedriver \
    CHROMEDRIVER_PORT=4444 \
    CHROMEDRIVER_URL_BASE=/wd/hub

COPY --from=chrome-downloader --chown=jelenoid:jelenoid /opt/chrome /opt/chrome
COPY --from=chrome-downloader --chown=jelenoid:jelenoid /opt/chromedriver /opt/chromedriver
COPY --chown=root:root --chmod=755 supervisord.conf /etc/supervisor/supervisord.conf
COPY --chown=root:root --chmod=755 entrypoint.sh /usr/local/bin/entrypoint.sh

USER root
RUN ln -sf "${CHROME_BIN}" /usr/local/bin/google-chrome \
    && ln -sf "${CHROMEDRIVER_BIN}" /usr/local/bin/chromedriver
USER jelenoid

EXPOSE 4444

HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 \
    CMD curl -fsS http://127.0.0.1:4444/wd/hub/status | grep -q '"ready":true' || exit 1

USER jelenoid
WORKDIR /home/jelenoid

ENTRYPOINT ["dumb-init", "--", "/usr/local/bin/entrypoint.sh"]
CMD ["supervisord", "-c", "/etc/supervisor/supervisord.conf", "-n"]
