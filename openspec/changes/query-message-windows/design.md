## Storage and scope

The engine retains UI window records as arrays in `sessions/<id>.ui.json`, with session metadata and channel bindings in `sessions/_index.json`. The CLI reads those files directly, discovers unindexed UI windows by filename, and does not invoke store APIs that could perform migrations or writes. A session binding identifies a route, not each message's origin; console and social traffic may share a window. Unbound sessions are not inferred to be console. UI retention means results are not complete historical transcripts.

## Query and privacy

Window ID or name, bound channel, sender, inclusive epoch-millisecond or zoned ISO time bounds, and case-insensitive text substring filters combine conjunctively. User, AI and agent text rows are ordered newest first and limited; missing timestamps do not match time bounds. Output is a table or JSON. The CLI does not read channel configuration or secret stores and excludes tool payloads. Whole rows matching credential-like patterns are suppressed, including matches in names or senders; this heuristic cannot guarantee detection of all secrets, so output remains sensitive. Binding metadata values are not emitted.

## Verification

A Node test constructs a temporary index and UI windows, including an unindexed window, multiple message types, a credential-like row and a tool payload. It exercises filter combinations, boundary timestamps, JSON and table output, invalid arguments and malformed input, and compares fixture bytes before and after querying. This verifies fixture behavior and read-only effects there, not completeness of live history or perfect secret detection. Live sampling is separately read-only and reported with content redacted.
