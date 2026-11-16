## Why

Agents and the author need a single read-only way to query engine-retained message windows.

## Scope

Add a dependency-free Node CLI over the session index and UI windows. A session's channel bindings describe routing, not per-message provenance; unbound windows are not presumed to be console-only. Do not read channel configuration, credentials, tool payloads, model transcripts, or shadow shards. UI retention may omit older messages.

## Acceptance

Exercise filtering, formats, errors, privacy and read-only behavior using a temporary fixture. Sample actual retained windows read-only without disclosing their content. No backend, deployment or restart changes.
