# Provider editor controls — proposed delta

## Requirements

- The authenticated editor shows configured non-secret values unchanged. A configured API key is masked by default; explicit eye activation reveals it. Image keys are fetched individually through the authenticated, name-audited secret value endpoint, with no-store. Do not put values in URLs, logs or diagnostics.
- The enhanced protocol selector must be above the mobile editor and operable by touch.
- Provider editor action buttons use a registered md size: minimum 44 × 44 CSS pixels (`--mtap`, with 44px fallback), horizontal padding `--ds-space-3`, font `--ds-fs-btn`, radius `--ds-radius-pill`, no wrapping. A two-column grid keeps four actions readable. Icon-only or other size tiers are not specified by this delta.

## Reusable proposed size tiers

All dimensions are CSS pixels. Every tier requires a minimum interactive hit area of 44 × 44; visual height and padding never reduce that hit area. `--mtap` is the mobile minimum, with a 44px fallback. All labels remain horizontal and `white-space: nowrap`; select a larger tier or shorter label rather than wrap.

| Tier | Visual height | Horizontal padding | Font | Radius | Minimum hit area | Status |
|---|---:|---:|---|---|---:|---|
| sm | 32 | 8 | 12px | 8px | 44 × 44 | Proposed; not implemented |
| md | 44 minimum | `--ds-space-3` | `--ds-fs-btn` | `--ds-radius-pill` | 44 × 44 | Implemented for provider action row |
| lg | 48 | 20 | 16px | `--ds-radius-pill` | 44 × 44 | Proposed; not implemented |

No shared token change is made. sm and lg numbers are proposals, not claims about existing tokens or checked implementations.

## Shared mobile dropdown impact

The `dropdown.css` layering rule affects every enhanced mobile single-select. Outside the provider editor, these are **not regression-tested by this change**: `js/sidebar.js:887,895` language/thinking; `js/modelPanel.js:348,632,679,873` model chain/settings; `js/orbSettingsUI.js:92,100,114` appearance; `js/socialPanel.js:1101`; `js/bgAgentPopup.js:640`; `js/usageDashboard.js:186`; `js/onboarding.js:874`; `js/wikiGraph.js:389`; `js/daemonPanel.js:178`; `js/mobile/searchFace.js:86`. Existing `tests/select-unify-f.spec.mjs` has broad select and mobile search cases but was not run in this follow-up. They remain a review and regression backlog; runtime-generated or conditionally displayed selects may extend the list.

## Acceptance and limits

`tests/settings-imagegen-two-forms.spec.mjs` exercises a mocked image provider, configured endpoint, explicit reveal, touch selector and four action geometry; `tests/settings-key-reveal.spec.mjs` covers LLM masking. `scripts/check-provider-buttons.mjs` checks the action-row declarations and four registered markup sites. It does not evaluate arbitrary CSS overrides, rendered text overflow, other editor controls, other settings pages, every viewport or physical devices. The browser test checks rendered geometry in one mobile viewport; its recording is not a physical device test.

## Non-goals and follow-up

Settings home and other secondary page buttons are untouched. Suggested md tier candidates (not modified): `src/main/resources/web/js/sidebar.js:452-453` schedule save/reset, `:571` TTL save, `:730-731` speech save/clear, `:961-962` settings actions; `src/main/resources/web/js/secretsPanel.js:474-475,500` secret editor actions. Inspect each actual layout before adopting. The provider form's model, time-window and eye controls remain outside the current checker and need separate tier registration and browser checks. Image status only returns an explicitly configured endpoint, not an adapter default; investigate any report of a saved endpoint missing with an isolated fixture. Image section writes currently omit projected fields such as timeout; preservation is separate work. LLM editor now reveals one provider on demand. The old authenticated batch endpoint remains available for compatibility and is a residual exposure surface.
