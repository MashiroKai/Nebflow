# Tasks

- [x] Resolve a leading todo reply `#title·id` and a message card in the console author text before agent delivery.
- [x] Leave the stored author transcript unchanged.
- [x] Inject short referenced bodies verbatim and fold bodies over 4,000 UTF-16 code units to the first 500 units plus a query handle.
- [x] Mint a message-token handle for a long structured quote or snapshot and serve it through the authenticated `GET /api/sessions/msg-token/<sid8-seq6>` route.
- [x] Inject quoted attachment names, types and positions; never replay image bytes.
- [x] Resolve a client-declared absolute path only when it is a currently readable regular file, else emit an explicit unavailable marker.
- [x] Emit an explicit marker and a diagnostic warning for a missing or unresolvable identifier.
- [x] Keep agent-to-agent message-card expansion unchanged.
- [ ] Treat a client quote snapshot as a verified durable source-message identity — NOT claimed; it is a client snapshot and legacy snapshots without the completeness flag stay explicitly unresolved.
