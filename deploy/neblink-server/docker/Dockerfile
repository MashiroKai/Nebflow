# syntax=docker/dockerfile:1
# Multi-stage build for neblink-server.
# Stage 1 builds a release binary; stage 2 ships it on a minimal image.

FROM rust:1.83-bookworm AS builder

# The USTC mirror config in neblink-server/.cargo/config.toml is intended for
# the developer's local machine and may not be reachable / desirable in CI, so
# we drop it here and rely on the default crates.io index.
WORKDIR /build
COPY neblink-server/Cargo.toml neblink-server/Cargo.lock* ./
COPY neblink-server/src ./src
COPY neblink-server/static ./static

# Build a stripped release binary. `--locked` keeps the build reproducible when
# a Cargo.lock is present; if absent, cargo just resolves normally.
RUN cargo build --release --bin neblink-server && \
    strip target/release/neblink-server

FROM debian:bookworm-slim AS runtime
# ca-certificates: required for HTTPS calls to GitHub (OAuth) and Let's Encrypt.
# libssl is not needed (we use rustls), but ca-certificates is.
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /build/target/release/neblink-server /app/neblink-server

# Persistent data lives in /data (SQLite db). Mounted via docker-compose.
RUN mkdir -p /data
ENV NEBLINK_DB_PATH=/data/neblink.db
ENV NEBLINK_SERVER_PORT=9090

EXPOSE 9090

# A non-root user for defense in depth.
RUN useradd --system --uid 1001 --home /app neblink
RUN chown -R neblink:neblink /app /data
USER neblink

CMD ["/app/neblink-server"]
