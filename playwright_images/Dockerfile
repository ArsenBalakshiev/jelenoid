FROM ubuntu:24.04

ARG PLAYWRIGHT_VERSION=1.58.0
ENV PLAYWRIGHT_VERSION=${PLAYWRIGHT_VERSION}
ENV DEBIAN_FRONTEND=noninteractive
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

RUN apt-get update && apt-get install -y \
    curl ca-certificates \
    --no-install-recommends && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs --no-install-recommends && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN npm init -y && \
    npm install --no-package-lock playwright@${PLAYWRIGHT_VERSION} && \
    npx playwright@${PLAYWRIGHT_VERSION} install --with-deps chromium && \
    rm -rf /root/.npm/_cacache /app/node_modules/.cache

CMD ["sleep", "infinity"]
