# Office preview cache and converted-PDF preference

## Why

Two defects shared one root: the office preview path trusted the wrong identity
for "is this the same document", and it preferred the frontend re-render over
the already-working server conversion.

- The preview cache key included the source modification time and path. A
  same-size replacement with a restored timestamp therefore reused the previous
  render — the reader saw stale content with no way to tell.
- Local DOCX/XLSX tabs re-rendered in the browser even when the authenticated
  office conversion service could return a PDF. The fallback path existed but
  failed silently, so "the document looks wrong" had no visible explanation.

## What changes

- Cache identity derives from the complete source bytes, the source extension
  and the converter-leg signature. Modification time and path no longer take
  part; identical bytes/extension/signature share a key.
- Local DOCX/XLSX tabs request the conversion service first and display its PDF
  through the existing PDF viewer. On failure the format-specific frontend
  viewer stays available and shows a visible inline notice (the declared
  `notice-card notice-info` inline type — no fourth notification style).
- Blob-only attachments keep their existing viewer; nothing about their path
  changes.

## Impact

- `src/main/scala/nebflow/gateway/NfOfficeRoutes.scala` — cache key derivation.
- `src/main/resources/web/js/viewers/{docx,xlsx,pptx}.js` — conversion-first
  display plus the visible fallback notice.
- Tests: `NfOfficeRoutesSpec`, `tests/canvas-office-viewers.spec.mjs`.

## Out of scope

- The converter itself, its toolchain and its failure classification.
- Non-office formats.
- Cache eviction policy (unchanged; the key change does not alter retention).
