# Feishu approval and table delivery

## Scope

- Deliver pending tool permission requests to the bound Feishu conversation and accept authenticated, request-bound allow/deny callbacks. A decision must reach the original pending interaction, exactly once; expired, unauthorized or mismatched callbacks must not approve anything.
- Render GFM tables in ordinary Feishu replies as narrow-screen readable labeled rows, preserving cell content. Do not change Weixin output or local transcripts. When parsing is ambiguous, retain the original table instead of dropping data.

## Acceptance

Exercise both permission kinds and allow/deny including unauthorized, expired and racing callbacks, and prove continuation in an isolated instance with actual Feishu delivery. Exercise table edge cases and prove actual Feishu-side output. Record platform configuration, security policy and any unavailable live evidence separately. This proposal is in progress, not an accepted specification.
