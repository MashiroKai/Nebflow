# NebLink Server (Rust)

Coordination server for Nebflow device discovery and the NebLink web dashboard.
Single Rust binary (~5MB RAM), axum + SQLite, no runtime dependencies.

This document covers the **recommended production deployment**: a cloud VPS with
Docker + Caddy (automatic HTTPS) and GitHub OAuth login.

> The legacy Windows-NSSM install (`install-service.bat`) is kept for existing
> single-machine setups, but new deployments should use the Docker path below.

---

## Architecture

```
                ┌─────────────────────────────────────────┐
   Browser ───► │ Caddy (80/443, auto Let's Encrypt TLS)  │
                │   neblink.nebflow.space                  │
                └───────────────────┬─────────────────────┘
                                    │ reverse_proxy :9090
                ┌───────────────────▼─────────────────────┐
                │ neblink-server (axum, Rust)             │
                │   - GitHub OAuth login (users)          │
                │   - Network / device management         │
                │   - Device pairing codes + credentials  │
                │   SQLite at /data/neblink.db            │
                └─────────────────────────────────────────┘
```

Authentication:
- **Users** log in with GitHub OAuth. Access (JWT, 15 min) and refresh (30 d,
  revocable, stored hashed) tokens are carried in signed HttpOnly cookies.
- **Devices** join a network via a 6-digit **pairing code** generated in the web
  UI, which yields a long-lived per-device credential stored at
  `~/.nebflow/neblink/device.json`. Legacy shared-secret login still works for
  backward compatibility.

---

## Deploy on a cloud VPS (recommended)

### 1. Prerequisites

- A VPS with ports 80 and 443 open (e.g. a $5/mo DigitalOcean / Vultr droplet,
  or an Aliyun/Tencent light instance — note a registered domain is required
  for ICP filing on mainland-China hosts).
- DNS control over `nebflow.space`.

### 2. DNS

Add an A (and/or AAAA) record:

```
neblink.nebflow.space.   IN  A   <your-vps-ipv4>
```

Wait for it to propagate (`dig neblink.nebflow.space` should resolve before
proceeding — Caddy needs this for the TLS challenge).

### 3. Create a GitHub OAuth App

Go to <https://github.com/settings/developers> → **OAuth Apps** → **New OAuth App**:

| Field | Value |
|-------|-------|
| Application name | `NebLink` |
| Homepage URL | `https://neblink.nebflow.space` |
| Authorization callback URL | `https://neblink.nebflow.space/api/auth/github/callback` |

After creating, generate a client secret. Note the **Client ID** and
**Client Secret**.

### 4. Configure & launch

On the VPS:

```bash
git clone <this-repo> nebflow && cd nebflow/deploy/neblink-server/docker
cp .env.example .env
# Edit .env:
#   NEBFLOW_JWT_SECRET   -> openssl rand -hex 32
#   GITHUB_CLIENT_ID     -> from step 3
#   GITHUB_CLIENT_SECRET -> from step 3
#   (OAUTH_REDIRECT_URL and APP_PUBLIC_URL are already set for the canonical domain)
docker compose up -d --build
```

Caddy fetches a certificate on the first request to
`https://neblink.nebflow.space` (this can take ~10–30 s). Open the URL in a
browser — you should see the NebLink login page.

### 5. Operations

```bash
docker compose logs -f neblink-server   # tail app logs
docker compose restart neblink-server   # apply upgrades (rebuild first)
docker compose down                     # stop everything
```

**Backups:** the entire state is `docker/data/neblink.db`. Back up that file
(e.g. `sqlite3 neblink.db ".backup '/tmp/neblink.db.bak'"` or just copy it
while the server is idle).

**Upgrades:** `git pull && docker compose up -d --build`. DB schema migrations
run automatically on startup (`CREATE TABLE IF NOT EXISTS` + additive ALTERs).

---

## Local development

```bash
cd neblink-server
export NEBFLOW_JWT_SECRET=$(openssl rand -hex 32)
export GITHUB_CLIENT_ID=...
export GITHUB_CLIENT_SECRET=...
# For local testing, use a separate GitHub OAuth App (or the same one with a
# localhost callback) and point the redirect at your local server:
export OAUTH_REDIRECT_URL=http://localhost:9090/api/auth/github/callback
export APP_PUBLIC_URL=http://localhost:9090
cargo run
# Server listens on http://localhost:9090
```

---

## API summary

| Endpoint | Method | Auth | Purpose |
|----------|--------|------|---------|
| `/api/auth/github/login` | GET | — | Redirect to GitHub to start OAuth |
| `/api/auth/github/callback` | GET | — | OAuth callback; sets auth cookies |
| `/api/auth/refresh` | POST | refresh cookie | Rotate access token |
| `/api/auth/logout` | POST | cookie | Revoke refresh token, clear cookies |
| `/api/user/me` | GET | user cookie/JWT | Current user info |
| `/api/network/create` | POST | user | Create a network |
| `/api/network/list` | GET | user | List owned networks |
| `/api/network/{id}` | DELETE | user | Delete a network |
| `/api/network/{id}/rotate` | POST | user | Rotate network secret |
| `/api/network/{id}/devices` | GET | user | List devices in network |
| `/api/network/{id}/devices/{dev}` | DELETE | user | Revoke a device |
| `/api/network/{id}/pair-code` | POST | user | Generate a 6-digit pairing code |
| `/api/device/enroll` | POST | pair code | Exchange pair code for a device credential |
| `/api/device/session` | POST | device credential | Get an ephemeral session token |
| `/api/device/login` | POST | network secret | **Legacy** shared-secret login |
| `/api/device/heartbeat` | POST | session token | Refresh session, get peers |
| `/api/device/peers` | GET | session token | List peers |
| `/api/device/endpoints` | POST | session token | Update device endpoints |
| `/api/device/logout` | DELETE | session token | End session |
| `/api/health` | GET | — | Health check |
