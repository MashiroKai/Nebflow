# Automatic credential references

## Intent and boundary

On credential-bearing configuration saves, store values in owner-only secret files and persist references. Preserve legacy inline reads and the user's existing ability to reveal and copy a value. Migrate existing values only on an isolated copy until the owner authorizes production migration.

Agent and secret files share an OS identity. This change does not claim cryptographic isolation or prevent deliberate shell reads. Its intended boundary is no incidental disclosure on normal agent-facing surfaces, with audited reads of references through managed code. Direct shell or filesystem access cannot be comprehensively audited by an in-process resolver alone; an OS-level audit mechanism would be required for that guarantee.

## Scenarios

- A save persists references, not credential values; secret contents are atomically stored with owner-only permissions.
- Runtime and user reveal resolve both legacy inline values and references; agent-facing projections do not return values.
- Managed reference reads record the reader and reference without a value. Direct filesystem reads are explicitly outside this mechanism.
- Isolated migration is idempotent, backs up before writing, supports rollback, and permits a real post-migration model call without printing credentials.
- Focused regression failure sets do not exceed baseline; new cases repeat twenty times in the same process.

## Delivery status

This proposal is not evidence that the scenarios are implemented or verified. All entry points, snapshots, error surfaces, user-versus-agent authorization, and isolated runtime verification must be reviewed before acceptance.
