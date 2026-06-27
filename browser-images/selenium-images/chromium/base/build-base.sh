#!/bin/sh
# Сборка базового образа. Запускать из корня репозитория.
#   ./build-base.sh           → собирает jelenoid/selenium-base:bookworm
#   ./build-base.sh myname    → собирает jelenoid/selenium-base:myname
set -eu

cd "$(dirname "$0")"

NAME="${1:-bookworm}"
TAG="jelenoid/selenium-base:${NAME}"

echo ">>> building ${TAG}"
docker build -t "${TAG}" -f Dockerfile.base .
echo ">>> done: ${TAG}"
