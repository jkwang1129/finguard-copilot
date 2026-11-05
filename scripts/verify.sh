#!/usr/bin/env bash
set -euo pipefail

bash ./mvnw -B -ntp clean test

(
    cd tools/agent-evaluation
    uv sync --locked --python 3.12
    uv run --python 3.12 pytest -q
)

(
    cd frontend
    npm ci
    npm run lint
    npm run test -- --run
    npm run build
)

(
    cd tools/mcp-bridge
    uv sync --locked --python 3.12
    uv run --python 3.12 pytest -q
)

git diff --check
