# module-doc — `nebflow.core.pdf` (PDF note/ask)

Page-anchored notes on PDFs, and asking questions about a PDF where the answer
cites page numbers. Two disciplines define the module: **nothing parses until an
ask message is sent**, and **a cache entry path is a contract**.

## Paths (single source each)

| What | Path | Notes |
|---|---|---|
| Cache root | `<dataRoot>/pdf-cache/` | one path per instance data root |
| Cache entry | `<dataRoot>/pdf-cache/<sha256[0:2]>/<sha256>/` | digest-named, digest-verified |
| Manifest | `…/<sha256>/meta.json` | `schema`, `source_sha256`, `page_count`, `page_names`, `parser`, `markdown_version`, `generated_at_ms`, `media`, `table_count`, `image_count` |
| Pages | `…/<sha256>/pages/p%04d.md` | one Markdown file per page, 1-based; image links are relative (`../media/…`) |
| Media | `…/<sha256>/media/p%04d-<index>.<ext>` | page attribution is in the file name |
| Parser script | `<dataRoot>/pdf-cache/_parser/pdf_parse-<sha8>.py` | extracted lazily on the first parse, content-addressed |
| Notes | `<dataRoot>/pdf-notes/<sha256>/p%04d.json` | keyed by document digest + 1-based page |
| Parse/env knob | `STAND_PDF_PYTHON` (default `python3`) | the only interpreter selector |

The data root is the single configuration input (`--home` / `STAND_HOME` /
`~/.stand` via `nebflow.shared.PathUtil`). There is no second cache path: an
isolated instance with its own data root can neither read nor pollute another
instance's cache.

HTTP surface (all session-authenticated, `nebflow.gateway.PdfRoutes`):

| Route | Parses? | Purpose |
|---|---|---|
| `POST /api/pdf/open` | **no** | identity (`sha256`), whether an entry exists, which pages carry notes |
| `GET /api/pdf/note` | **no** | read one page's note (with the page bound when a manifest exists) |
| `POST /api/pdf/note` | **no** | write/replace one page's note (conditional on `expected_version`) |
| `GET /api/pdf/page` | **no** | read one cached page's Markdown |
| `POST /api/pdf/ask` | **yes** | the ask message: starts the background parse (`202 parsing`), then answers with cited page excerpts |

Note semantics: identity = (document digest, 1-based page). A note never parses —
without a manifest the page number is accepted and reported as
`page_verified:false`; once a parse has happened the same page is judged against
`page_count` (`out_of_range`). A snippet (verbatim quoted text) rides along as
descriptive evidence and as a ranking signal; it is not part of the identity.
Notes are never deleted automatically.

## Cleaning policy

Nothing in this feature deletes a cache entry or a note on its own.

- **Entries are immutable and content-addressed.** A digest is only ever added;
  an existing entry is never rewritten in place (a re-parse of the same bytes is
  impossible by construction, and a changed `markdown_version` makes the entry a
  *miss*, leaving the old directory untouched).
- **Failure leaves nothing.** The parser writes into a sibling staging directory
  (`…/<sha>.tmp-<rand>/`); only a complete entry is renamed into place. A failed
  run removes the staging directory, so a failure can never be mistaken for an
  empty document and is retried by the next ask.
- **Staging leftovers are the only thing worth reclaiming.** `…/*.tmp-*`
  directories are safe to delete once no parse is running (a crashed process is
  the only way to leave one). Deleting a completed entry costs one reparse — never
  data loss, because notes live outside the cache under `pdf-notes/`.
- **Retention is the operator's call, and it is not implemented here.** There is
  deliberately no age-based sweeper in this batch (no background daemon is added),
  so an instance's cache grows by the number of distinct documents asked about.
  That is a known open item, not a hidden policy.
- `pdf-notes/` is user data: no automatic reclamation, ever.

## Parser: license and dependency reproducibility

- **Parser**: PyMuPDF (`import fitz`), reported in the manifest as
  `{"name":"pymupdf","version":…}`; the script is
  `src/main/resources/pdf/pdf_parse.py`.
- **License**: PyMuPDF is **AGPL-3.0** (see
  <https://pypi.org/project/PyMuPDF/> for the license statement of the pinned
  version). This is the single most important item to decide before shipping this
  feature beyond a local/author-controlled instance: AGPL-3.0 is a copyleft
  license on a *separate executable* this feature invokes, which is a materially
  different situation from an Apache/MIT library linked into the JVM. Recorded as
  a blocking open item in the batch report; the seam that would absorb a
  replacement is `PdfParser` (one subprocess + one output contract).
- **Why PyMuPDF**: reading order, table detection and image extraction with page
  attribution in one open-source dependency. Alternatives considered and their
  gaps, for the decision: `pypdf` (BSD-3, no tables, weak reading order),
  `pypdfium2` (BSD-3/Apache-2.0, no table detection), Apache PDFBox (Apache-2.0,
  JVM-native, text and images only — tables would have to be built by hand or via
  `tabula-java`).
- **Reproducibility**: the PyMuPDF version is recorded in every entry's
  `meta.json`; the interpreter comes from one env knob; the script itself is
  content-addressed under `<dataRoot>/pdf-cache/_parser/`, so the exact script
  that produced an entry can be read back from the instance. The JVM adds **no**
  new library dependency (`build.sbt` is unchanged).
- **Non-goals** (stated so they are not assumed): no multi-column reflow model
  (reading order is PyMuPDF's sorted block order), no OCR (a scanned page yields
  no text), no table cell type inference.

## Seams

- `PdfCache` is cache + single-flight + publication. It is the only writer.
- `PdfParser` is the only code that spawns a process, and the only place the
  parser identity/version lives on the JVM side.
- `PdfAsk` selects pages and renders the excerpt block; it holds the budget caps
  (`MaxPagesPerAsk` / `MaxCharsPerPage` / `MaxCharsPerAsk`) and never loads a whole
  document.
- `PdfNotes` is the only writer under `pdf-notes/`.
- `PdfRoutes` is the only HTTP surface, and the only caller of `PdfCache.ensure`.

`PdfParser.MarkdownVersion` must equal `MARKDOWN_VERSION` in the parser script;
`scripts/tests/pdf-parse.test.mjs` asserts it, and the Scala suite asserts the same
pairing from the classpath copy.
