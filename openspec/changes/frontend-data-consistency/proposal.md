# Data consistency — proposed

This change addresses the current authorized Note and todo data-repair request, not historical accepted intent. Conflicts retain local and server copies rather than silently overwriting. A named rearm now keeps the stored record ID and moves across sessions in a recoverable write-then-remove order; legacy ownership is adopted only from a producer-proven marker and otherwise refused before any write. Real-instance acceptance remains open.

The Note protocol also has an executable live entry (`tests/note-protocol-live.mjs`) for an isolated instance started by the main session; it has not been run by this source-only step. Complete cross-platform GUI acceptance remains open.
