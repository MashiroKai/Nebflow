# Session model selection

## Intent
A session's explicit model overrides its role setting until the user restores following. Changing a role setting only affects following sessions. Explicitly selected models must never fall back to a different model on failure.

## Acceptance
- Both streaming and non-streaming requests send only the explicit session model when present.
- Role setting saves retain session choices across reload and restart.
- Session header and delegate row expose follow/override, with an action to follow again.
- An unavailable selected model is reported with its reason in the delegate row without selecting another model.
- Verify actual outgoing model with an isolated fake provider; cover follow, override, restore, and failure, and run regression and repeated tests.
