# Provider editor & settings controls — delta

## Requirements

### R1 — Configured values are visible; unconfigured fields are explicitly marked
- The provider panel shows, on each provider card, the **configured endpoint** (`baseUrl`) verbatim. A provider with no endpoint configured shows an explicit "default endpoint" wording instead of a blank — an unconfigured field showing a placeholder is correct behaviour, not a defect.
- The editor form prefills every configured non-secret value (endpoint, protocol, model). A configured API key is masked by default; explicit eye activation reveals it. Image keys are fetched individually through the authenticated, name-audited secret value endpoint, with `no-store`. Values never enter URLs, logs or diagnostics.
- A stored protocol/adapter value that is absent from the current projection is **re-added to the selector** rather than silently dropped: otherwise a configured protocol disappears from the face and is silently rewritten on the next save.

### R2 — The enhanced protocol selector is a full-width, fully clickable field
- The select enhancer's wrapper (`.nf-select`) follows the width intent of the field it wraps: a form-row control (`.cfg-input`, `width: 100%`) keeps the **full row width**, so the whole row is the hit area. An inline `width: auto` settings-row select stays shrink-to-fit.
- Root cause of the reported "protocol selector does not respond": the `inline-grid` wrapper is shrink-to-fit, so the wrapped select's `width: 100%` resolved against the wrapper's grid area and collapsed to the trigger's max-content — measured 366px → 144px (1280×800) and 343px → 144px (375×812). The multi-hundred-pixel remainder of the row was dead space.
- The selector must be operable by touch and open its option sheet on a click anywhere within the field row.

### R3 — One registered action-button tier across the settings surface
- All action buttons in the settings modal (`.set-btn` family) and in the provider editor action row (`#cfg-modal .cfg-modal-actions .cfg-btn`) use the **same md tier**: minimum 44 × 44 CSS px (`--mtap`, 44px fallback), horizontal padding `--ds-space-3`, font `--ds-fs-btn`, radius `--ds-radius-pill`, no wrapping. No second geometry is introduced.
- The tier is identical in both viewports; the mobile surface no longer drops back to its own horizontal padding / font size.
- Before: settings family 28 px high / 10 px radius (desktop) and 44 px / 16 px padding / 13 px font / 10 px radius (mobile) vs the editor family's 44 px / 12 px / pill. After: one shape, `44px|12px|12px|12px|999px|nowrap`.

## Reusable size tiers

All dimensions are CSS pixels. Every tier requires a minimum interactive hit area of 44 × 44; visual height and padding never reduce that hit area. `--mtap` is the mobile minimum, with a 44px fallback. All labels remain horizontal and `white-space: nowrap`; select a larger tier or shorter label rather than wrap.

| Tier | Visual height | Horizontal padding | Font | Radius | Minimum hit area | Status |
|---|---:|---:|---|---|---:|---|
| sm | 32 | 8 | 12px | 8px | 44 × 44 | Proposed; not implemented |
| md | 44 minimum | `--ds-space-3` | `--ds-fs-btn` | `--ds-radius-pill` | 44 × 44 | **Implemented for the provider action row AND the settings action family** |
| lg | 48 | 20 | 16px | `--ds-radius-pill` | 44 × 44 | Proposed; not implemented |

`--set-btn-h` now takes the md tier (`var(--mtap, 44px)`); `--set-btn-pad-x` stays `--ds-space-3`. No other shared token change is made. sm and lg numbers are proposals, not claims about existing tokens or checked implementations.

## Shared mobile dropdown impact

The `dropdown.css` width rule applies to every enhanced single-select that wraps a `.cfg-input`. Outside the provider editor, the settings page's own selects use `.cfg-select` with an inline `width: auto` and are therefore unaffected. The remaining enhanced selects listed below are **not regression-tested by this change**: `js/sidebar.js:887,895` language/thinking; `js/modelPanel.js:348,632,679,873` model chain/settings; `js/orbSettingsUI.js:92,100,114` appearance; `js/socialPanel.js:1101`; `js/bgAgentPopup.js:640`; `js/usageDashboard.js:186`; `js/onboarding.js:874`; `js/wikiGraph.js:389`; `js/daemonPanel.js:178`; `js/mobile/searchFace.js:86`. They remain a review and regression backlog.

## Acceptance and limits

- `tests/provider-settings-three.spec.mjs` (zero-backend harness, 1280×800 + 375×812 + a WebKit leg for the Safari engine) covers: row-width equality of the protocol field with its siblings; five-point `elementFromPoint` hit test across the field row; a real pointer click on the row's right-hand region opening the option sheet; a real option pick landing on the native select; the save request carrying the picked protocol; the configured endpoint and protocol prefilled in the edit form; the card endpoint readout in both configured and unconfigured states; and both action families' measured geometry.
- `scripts/check-provider-buttons.mjs` (in `check:frontend`) checks both families' declarations and requires the two to agree on the md tier.
- `tests/provider-panel-overflow.spec.mjs` covers text overflow in the panel across six viewport/theme cells.
- It does not evaluate arbitrary CSS overrides, physical devices, or the secrets-editor overlay's own buttons. The recordings are browser viewport captures, not physical device tests.

## Non-goals and follow-up

- The secrets editor overlay buttons (`js/secretsPanel.js:475,500`) are outside this change and are not yet on the md tier.
- The 28 px inline-row tier has been retired for the settings action family; any surface still rendering a bare `.cfg-btn` without `.set-btn` is unregistered and needs its own tier decision.
- Settings home and other secondary page buttons (`js/sidebar.js` schedule/TTL/STT rows are on `.set-btn` and therefore in tier; the secrets overlay is not) remain to be swept.
- Image status returns only an explicitly configured endpoint, not an adapter default — this is why the unconfigured card state reads "default endpoint" rather than printing a synthesised URL.
