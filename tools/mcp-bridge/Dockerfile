FROM ghcr.io/astral-sh/uv:0.10.11@sha256:3472e43b4e738cf911c99d41bb34331280efad54c73b1def654a6227bb59b2b4 AS uv
FROM python:3.12-alpine@sha256:e6dae589c1c05976188fd3bd019afd091445fc533ed2ea29001beddc7368a9c8
COPY --from=uv /uv /bin/uv
WORKDIR /app
ENV UV_PYTHON_DOWNLOADS=never PYTHONDONTWRITEBYTECODE=1
COPY pyproject.toml uv.lock ./
COPY src ./src
COPY scripts/acceptance_client.py ./
RUN uv sync --locked --no-dev --no-editable --no-cache
USER 10001:10001
ENTRYPOINT ["/app/.venv/bin/python"]
