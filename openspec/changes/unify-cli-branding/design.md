## Design

The official horizontal black/white SVG lockups are the artwork source. The existing installer sampler emits an ASCII Java Properties resource with the same seven installer payloads. Installers stay standalone; the packaged JVM resource lets every runtime launcher share one renderer without deployment-time copying.

Scala startup renders only after the existing port guard admits foreground or daemon-parent startup. Shell wrappers no longer own artwork. The interactive CLI uses the same renderer. Terminal stdout supports the existing OSC 1337/SIXEL image paths or the installer half-block fallback; narrow terminals use plain Stand text. CI, redirected output, quiet and JSON startup do not print decorative artwork.

Payload parity and independent-artwork detection run in the shared static registry. Unit tests verify generator escaping, protocol selection, exact artwork rows and downgrade behavior. Native Windows behavior requires a separate platform run; policy-level unit tests do not claim such execution.
