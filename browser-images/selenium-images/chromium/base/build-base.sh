#!/bin/sh
set -eu

cd "$(dirname "$0")"

NAME="${1:-bookworm}"
TAG="jelenoid/selenium-base:${NAME}"

echo ">>> building ${TAG}"
docker build -t "${TAG}" -f Dockerfile.base .
echo ">>> done: ${TAG}"
