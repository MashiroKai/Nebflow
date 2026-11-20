# Design — office preview cache and converted-PDF preference

## Cache identity

`NfOfficeRoutes` keyed its converted-preview cache on `(path, mtime)`. Both parts
are wrong for this purpose:

- `path` is not identity: the same document reached through a different mount or
  a copied file is a different path with identical bytes.
- `mtime` is not identity: a same-size replacement with a restored timestamp is
  a different document that looks unchanged.

New key = `sha256(source bytes) + extension + converter-leg signature`. The
converter signature names the toolchain that produced the PDF, so a converter
upgrade cannot silently serve a render from an older leg. The hash is computed
over the bytes the route already reads — no extra read, no extra I/O.

Consequence accepted: cache entries are no longer addressable by path alone, so
the eviction sweep walks entries by age rather than by path prefix. Retention
semantics are otherwise unchanged.

## Conversion-first display

For a local DOCX/XLSX tab the viewer asks the conversion service first:

1. Conversion returns a PDF → hand it to the existing PDF viewer. The original
   is not fetched for a frontend re-render (previously both happened).
2. Conversion is unavailable or fails → fall back to the format-specific
   frontend viewer (`docx.js` / `xlsx.js` / `pptx.js`) and render a visible
   inline notice stating that a simplified view is being shown.

The notice uses the declared inline notification type (`notice-card
notice-info`). It is deliberately not a new class family: the product declares
exactly three notification types (toast / banner / inline) and a fourth style is
a gate failure, not a style choice.

Fallback is visible rather than logged-only because the failure mode it
represents — "the preview looks different from the document" — is not
distinguishable from a rendering bug by the reader alone.

## Blob-only attachments

Attachments without a local file backing never had a conversion leg; they keep
their existing viewer unchanged. Routing them through the same code path would
require bytes the attachment surface does not hold.

## Verification

- `NfOfficeRoutesSpec` — same-size replacement with restored timestamp yields a
  different key; timestamp-only change keeps it; converter-signature change
  rotates it.
- `tests/canvas-office-viewers.spec.mjs` — converted path shows the PDF without
  fetching the original; failure path shows the frontend viewer plus the
  visible notice.
