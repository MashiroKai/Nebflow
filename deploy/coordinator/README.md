# Nebflow Coordinator Deployment

The coordination server is a standalone service for device discovery,
replacing the Tailscale dependency.

## Quick Start

### Build

```bash
# From project root
sbt assembly
# Output: target/scala-3.5.2/nebflow.jar
```

### Deploy on Windows (as a Service)

1. Copy `nebflow.jar` to this directory (or to the target machine)
2. Run `install-service.bat` as Administrator
3. The service starts immediately and auto-starts on boot

```bat
REM On the target Windows machine
install-service.bat
REM Verify
curl http://localhost:9090/api/health
```

Management:
```bat
nssm stop NebflowCoordinator    REM Stop
nssm start NebflowCoordinator   REM Start
nssm status NebflowCoordinator  REM Check status
uninstall-service.bat           REM Remove service
```

### Run on macOS/Linux (foreground, for dev)

```bash
./run.sh
# Or directly:
java -cp nebflow.jar nebflow.coordinator.CoordinatorMain
```

## Configuration

The coordinator runs on port **9090** with in-memory storage.
All state (networks, devices) is lost on restart — devices reconnect automatically.

### Create a Network

```bash
curl -X POST http://<server-ip>:9090/api/network/create \
  -H "Content-Type: application/json" \
  -d '{"name":"My Devices"}'
# Returns: {"networkId":"...", "secret":"..."}
```

### Configure Nebflow Devices

Add to `~/.nebflow/neblink/config.json`:

```json
{
  "enabled": true,
  "coordinator": {
    "server": "http://<server-ip>:9090",
    "networkId": "<from step above>",
    "secret": "<from step above>"
  }
}
```

## Requirements

- Java 17+
- NSSM (for Windows Service — auto-installed via Chocolatey)
