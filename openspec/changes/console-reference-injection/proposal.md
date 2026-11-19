# Console reference injection

## Why

Console author text is delivered to the agent as-is, so a leading todo reply `#title·id` and a
message card reach the agent as opaque text instead of the body they point at. The agent then either
guesses or asks for the content again.

## What Changes

Resolve a leading todo reply identifier and a message card in the console author text before agent
delivery, without changing the stored author transcript. Inject short referenced bodies verbatim and
substitute the first 500 code units plus a query handle for long ones. Resolve a client-declared
absolute path only when it is a currently readable regular file, and otherwise emit an explicit
unavailable marker. Missing or unresolvable identifiers produce an explicit marker and a diagnostic
warning. Agent-to-agent message-card expansion is unchanged.

## Boundary

The injected chat quote is a snapshot of what the client supplied, not a verified durable
source-message identity; legacy snapshots without the completeness flag are explicitly unresolved
rather than silently treated as complete. Quoted attachment names, types and positions are injected
but image bytes are not replayed, and a handle can fail after the source message is deleted.
