# Design

Use an independently settable virtual model sidecar with the same parsing and resolution conventions as existing model slots. A single accessor reads it on demand; if absent or empty, it warns and resolves the main chain, including the existing seed fallback. Reuse the existing authenticated model GET/PUT path and shared desktop/mobile settings rendering with the existing row token. The small-model slot is not a session category and does not own session pins.
