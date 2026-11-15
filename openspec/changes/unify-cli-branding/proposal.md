## Why

Installer and startup paths displayed different Stand artwork. Startup used a standalone S and only a subset of invocation forms reached it.

## What Changes

- Use the installer's official horizontal symbol-and-wordmark lockup for every CLI artwork surface.
- Generate installer and JVM artwork from the same vector assets and verify payload parity.
- Render startup artwork once after startup admission, preserving quiet, redirected and machine-readable output.

## Capabilities

### New Capabilities
- `cli-branding`: shared official terminal artwork and safe runtime rendering.

## Impact

Changes affect terminal artwork generation, runtime startup and interactive display, launcher delegation, tests and documentation. No live installation, service restart, version change or publication is included.
