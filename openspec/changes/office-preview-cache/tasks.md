# Tasks — office preview cache and converted-PDF preference

## Implemented

- [x] Derive the preview cache key from source bytes + extension + converter-leg
      signature; drop path and mtime from identity.
- [x] Keep timestamp-only changes on the same key (no spurious re-conversion).
- [x] Rotate the key when the converter-leg signature changes.
- [x] Request the conversion service first for local DOCX/XLSX tabs and render
      its PDF through the existing PDF viewer.
- [x] Skip the frontend re-render when the converted PDF is available.
- [x] Fall back to the format-specific frontend viewer on conversion failure.
- [x] Show a visible inline notice on the fallback path (`notice-card
      notice-info`, the declared inline type).
- [x] Leave blob-only attachments on their existing viewer.

## Verification

- [x] `NfOfficeRoutesSpec` — same-size replacement with restored timestamp
      differs; timestamp-only change is stable; converter signature rotates.
- [x] `tests/canvas-office-viewers.spec.mjs` — converted path does not fetch the
      original; failure path shows the frontend viewer plus the notice.

## Follow-ups (not in this change)

- [ ] Cache eviction review once entries can no longer be addressed by path.
- [ ] Failure-classification detail for the conversion service (which failures
      warrant retry versus immediate fallback).
