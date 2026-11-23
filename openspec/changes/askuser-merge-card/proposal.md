# AskUser merge question

## Scope

Add a merge question to AskUserQuestion, preserving the existing card shell and the one-string-per-question answer wire. Existing questions remain unchanged. The card does not execute merges or query Git.

## Contract

A question may contain `merge` with `target` (current target), `targets` (target candidates), `candidates` (available source names), `operations` (booleans `add`, `delete`, `reorder`, `select`, `note`), and `branches` in merge order. Each branch has `name`, optional `note`, `selected`, and `relations`, a map keyed by target. A relation has `status` and optional `conflict` explanation. When data for the selected target is absent the UI must show unknown, not invent a relationship. New branches without data likewise show unknown.

The answer is a JSON object serialized as the corresponding existing answer string: `{ "decision": "approve|reject|supplement|defer", "branches": [{ "name": "...", "note": "..." }], "target": "...", "text": "..." }`. Branches contain selected entries in displayed order. Reject requires nonblank text; approve requires at least one selected branch. Supplement and defer remain distinct decisions. The agent receives the string in the existing asynchronous reply and can parse it as JSON. No secrets are accepted in this question.

## Acceptance

Preserve existing AskUser tests. Add red-to-green tests for parsing, event forwarding and reply serialization, selection gating, mandatory rejection reason, add/delete/reorder, target-dependent relation/conflict, light/dark at 390/1280. Demonstrate a mutation that drops order or renames a field makes the test fail. Test through an isolated harness without the host engine or its port. Capture four screenshots. Do not merge or deploy.
