# Provider editor controls — proposed delta

## Requirements

- The authenticated editor shows configured non-secret values unchanged. A configured API key is masked by default; explicit eye activation reveals it. Image keys are fetched individually through the authenticated, name-audited secret value endpoint, with no-store. Do not put values in URLs, logs or diagnostics.
- The enhanced protocol selector must be above the mobile editor and operable by touch.
- Provider editor action buttons use a registered md size: minimum 44 × 44 CSS pixels (`--mtap`, with 44px fallback), horizontal padding `--ds-space-3`, font `--ds-fs-btn`, radius `--ds-radius-pill`, no wrapping. A two-column grid keeps four actions readable. Icon-only or other size tiers are not specified by this delta.

## Acceptance and limits

`tests/settings-imagegen-two-forms.spec.mjs` exercises a mocked image provider, configured endpoint, explicit reveal, touch selector and four action geometry; `tests/settings-key-reveal.spec.mjs` covers LLM masking. `scripts/check-provider-buttons.mjs` checks the action-row declarations and four registered markup sites. It does not evaluate arbitrary CSS overrides, rendered text overflow, other editor controls, other settings pages, every viewport or physical devices. The browser test checks rendered geometry in one mobile viewport; its recording is not a physical device test.

## Non-goals and follow-up

Settings home and other secondary page buttons are untouched. Suggested md tier candidates (not modified): `src/main/resources/web/js/sidebar.js:452-453` schedule save/reset, `:571` TTL save, `:730-731` speech save/clear, `:961-962` settings actions; `src/main/resources/web/js/secretsPanel.js:474-475,500` secret editor actions. Inspect each actual layout before adopting. The provider form's model, time-window and eye controls remain outside the current checker and need separate tier registration and browser checks. Image status only returns an explicitly configured endpoint, not an adapter default; investigate any report of a saved endpoint missing with an isolated fixture. Image section writes currently omit projected fields such as timeout; preservation is separate work. LLM key readback still prefetches all provider values under the existing authenticated endpoint; switching to explicit single-provider reads remains open.
