#!/usr/bin/env bash
set -e

echo "Stopping containers and removing locally-built images..."
docker compose down --rmi local

echo "Rebuilding and starting..."
docker compose up --build
