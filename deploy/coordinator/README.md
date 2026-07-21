# NebLink Server (Rust)

Lightweight coordination server for Nebflow device discovery.
Single binary, ~5MB RAM, no runtime dependencies.

## Build

```bash
cd coordinator
cargo build --release
# Output: target/release/neblink-server (or .exe on Windows)
```

## Deploy on Windows (as a Service)

1. Copy `neblink-server.exe` to `deploy/coordinator/`
2. Run `install-service.bat` as Administrator
3. Auto-starts on boot, ~5MB RAM, no Java needed

## Run (any platform)

```bash
./neblink-server    # starts on port 9090
```

## API

| Endpoint | Method | Auth | Purpose |
|----------|--------|------|---------|
| `/api/network/create` | POST | - | Create a network, returns secret |
| `/api/device/login` | POST | - | Login device, returns token + peers |
| `/api/device/heartbeat` | POST | Bearer | Refresh session, get updated peers |
| `/api/device/peers` | GET | Bearer | List peers in network |
| `/api/device/endpoints` | POST | Bearer | Update device endpoints |
| `/api/device/logout` | DELETE | Bearer | Remove session |
| `/api/health` | GET | - | Health check |

### Quick Start

```bash
# Create network
curl -X POST http://localhost:9090/api/network/create \
  -H "Content-Type: application/json" \
  -d '{"name":"My Devices"}'
# -> {"networkId":"...","secret":"..."}

# Configure Nebflow devices in ~/.nebflow/neblink/config.json:
# {
#   "enabled": true,
#   "coordinator": {
#     "server": "http://<server-ip>:9090",
#     "networkId": "...",
#     "secret": "..."
#   }
# }
```
