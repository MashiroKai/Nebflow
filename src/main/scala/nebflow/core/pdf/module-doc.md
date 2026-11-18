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
| Page index | `…/<sha256>/index.json` | the inverted index `{term: [page,…]}` the ask selects pages with; part of "a complete entry" |
| Pages | `…/<sha256>/pages/p%04d.md` | one Markdown file per page, 1-based; image links are relative (`../media/…`) |
| Media | `…/<sha256>/media/p%04d-<index>.<ext>` | page attribution is in the file name |
| Parser script | `<dataRoot>/pdf-cache/_parser/pdf_parse-<sha8>.py` | extracted lazily on the first parse, content-addressed |
| Parser deps | `src/main/resources/pdf/requirements.txt` | permissive-only, pinned versions + sha256 |
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

## Storage rule (the fixed location of the per-page Markdown)

**Rule.** Every parse product of a PDF lives under ONE root —
`<dataRoot>/pdf-cache/` — and nowhere else. The per-page Markdown of a document is
therefore always at

```
<dataRoot>/pdf-cache/<sha256[0:2]>/<sha256>/pages/p%04d.md
```

with `<sha256>` the digest of the document's exact bytes and a 1-based page number
zero-padded to four digits (`pages/p0002.md` = page 2). Nothing is written next to
the PDF, nothing into the repository, nothing into a per-project workspace: the data
root is the only configuration input, so a cache entry path is a contract a caller
can derive without asking the process.

- The digest is computed from bytes, so the SAME location holds for the same content
  regardless of file name or path — a renamed or moved PDF keeps its entry (and its
  notes); an edited PDF is a different digest and therefore a different entry.
- The entry is written atomically (staging dir + rename), so a partially written
  directory is never mistaken for a document.
- Page numbering in the path, the file name, the `index.json` posting lists and the
  citation the answer prints (`p.2`) are the same 1-based number. There is no second
  numbering convention.
- Media extracted from a page is `<entry>/media/p%04d-<index>.<ext>`; the manifest is
  `<entry>/meta.json`; the page index is `<entry>/index.json`. The full table is the
  "Paths (single source each)" section above.
- Notes are deliberately NOT in the cache: user data lives under
  `<dataRoot>/pdf-notes/<sha256>/p%04d.json` so that reclaiming a cache entry can
  never cost a note.

**Getting the dependencies in place (the environment side of the same contract).**
The parser is a subprocess, so the interpreter needs the pinned set from
`src/main/resources/pdf/requirements.txt`. Neither the JVM nor a stock `python3`
carries it, and nothing is installed into a system interpreter. The one-step
bootstrap is `scripts/tests/pdf_python_env.sh`:

```
scripts/tests/pdf_python_env.sh                 # venv at ~/.cache/stand/pdf-venv
export STAND_PDF_PYTHON="$HOME/.cache/stand/pdf-venv/bin/python3"
sbt "testOnly nebflow.core.pdf.* nebflow.gateway.PdfAskHttpConcurrencySpec"
```

`STAND_PDF_PYTHON` must be exported on the **sbt process** (the Scala suites run in
the sbt JVM), and on the process running `scripts/tests/pdf-parse.test.mjs`. The
venv lives outside the repository and outside any data root — it is a machine
artifact, never instance state.

**A missing dependency is not a red gate.** The dependency set is machine-local,
so neither gate may turn "this checkout has not bootstrapped yet" into a failure —
that would make a local artifact a repository dependency:

| State | Scala suite (`PdfAskFaceSpec`) | Node suite (`pdf-parse.test.mjs`) |
|---|---|---|
| deps absent, `STAND_PDF_PYTHON` unset | `assume` skips the two fixture-backed tests | 3 parser tests **skip**; the reason (with the bootstrap line) is printed; the run exits 0 |
| deps absent, `STAND_PDF_PYTHON` **set** | skip | **hard failure** — the knob was pointed at an interpreter that cannot do the job, and a skip would hide exactly that breakage |
| deps present | the two tests really run | every test really runs |

So a fresh checkout and CI are green on install alone, and the capability is
proven only where it exists. **CI runs the parser end to end only if the runner
bootstraps first** — one step, before any test command:

```
scripts/tests/pdf_python_env.sh
```

`node --test scripts/tests/*.test.mjs` is the self-check for both states (3 skips
with the knob unset, 93/93 with it set). The workflow files are deliberately NOT
touched by this batch: wiring the bootstrap into CI is a separate decision about
runner cost and network access.

## On demand: the index, not the document

An ask must decide WHICH pages to open before it opens any. The previous shape
read every page file to rank them, which made an ask cost grow with the document
(500 pages ⇒ 500 reads for a one-page answer) and made "on demand" a claim about
the response shape rather than about the I/O.

The entry therefore carries `index.json`: an inverted index (lowercase token,
length ≥ 2 → ascending 1-based pages) written once, at parse time, by the parser
while the document is open anyway. An ask:

1. reads the manifest (`page_count`, `page_names`) and the index — discovery;
2. resolves the question's terms against the index and ranks the matching pages;
3. opens EXACTLY `min(matched, MaxPagesPerAsk)` page files, one per cited page.

The count is observable twice: in the answer (`page_reads.pages_opened`,
`page_reads.whole_document_read:false`) and process-wide
(`PdfCache.pageReadsFor(cacheRoot)`), so "only the matching pages were read" is a
fact a reviewer re-derives rather than a claim about a loop. `markdown_version`
2 is the shape that promises the index; a v1 entry (or any entry whose index is
missing) is a MISS and is reparsed, never half-served.

## One cache per process (the HTTP single-flight)

`PdfCache` carries the in-memory single-flight map, the manifest it already read
and the writer lock for one data root. The HTTP routes used to build a NEW
instance per request (`PdfCache.forDataRoot` in each arm), so four concurrent
asks for one document ran four parses and four publication races — the dedup only
helped callers that happened to share an instance.

`PdfCache.sharedForDataRoot(dataRoot)` is now the single construction point on
the HTTP surface: one instance per data root (the data root is the single
configuration input, so process-wide sharing cannot cross instances). The
observable is `PdfCache.parseStartsFor(cacheRoot)` — parses started for one data
root, counted at the one point a parser subprocess is about to run.

## Parser: license and dependency reproducibility

- **Parser**: pdfplumber (MIT) for text lines and tables plus pypdf (BSD-3) for
  the document structure, the encryption verdict and embedded images. Reported in
  the manifest as `{"name":"pdfplumber","version":…}`; the script is
  `src/main/resources/pdf/pdf_parse.py`.
- **License**: **no AGPL and no copyleft anywhere in this path** (author decision,
  this batch). The previous parser was PyMuPDF (AGPL-3.0) — a copyleft license on
  a *separate executable* this feature invokes, which is a materially different
  situation from an Apache/MIT library linked into the JVM. It has been removed;
  `requirements.txt` records the whole permissive set and its licenses.
- **Reproducibility**: `src/main/resources/pdf/requirements.txt` pins every
  package in the resolved tree (`pdfplumber==0.11.10`, `pypdf==7.1.0` and the
  seven transitive packages) with the sha256 of its source distribution and
  documents the hash-verified install command; the parser version is recorded in
  every entry's `meta.json`; the interpreter comes from one env knob; the script
  is content-addressed under `<dataRoot>/pdf-cache/_parser/`, so the exact script
  that produced an entry can be read back from the instance. The JVM adds **no**
  new library dependency (`build.sbt` is unchanged).
- **Test fixtures**: `scripts/tests/pdf_fixture.py` writes its PDFs byte by byte
  with the standard library (encryption via pypdf), so the fixture path adds no
  dependency of its own and, in particular, no AGPL writer.
- **Non-goals** (stated so they are not assumed): no multi-column reflow model
  (reading order is pdfplumber's `(top, x0)` line order), no OCR (a scanned page
  yields no text), no table cell type inference, no image re-encoding.

## Seams

- `PdfCache` is cache + single-flight + publication. It is the only writer, and
  `sharedForDataRoot` is the only construction point the HTTP surface may use
  (one instance per data root ⇒ one flight registry per process).
- `PdfIndex` is the discovery surface read by the ask leg: term → pages. It never
  holds page bodies.
- `PdfParser` is the only code that spawns a process, and the only place the
  parser identity/version lives on the JVM side.
- `PdfAsk` selects pages (through the index) and renders the excerpt block; it
  holds the budget caps (`MaxPagesPerAsk` / `MaxCharsPerPage` / `MaxCharsPerAsk`)
  and never loads a whole document.
- `PdfNotes` is the only writer under `pdf-notes/`.
- `PdfRoutes` is the only HTTP surface, and the only caller of `PdfCache.ensure`.

`PdfParser.MarkdownVersion` must equal `MARKDOWN_VERSION` in the parser script;
`scripts/tests/pdf-parse.test.mjs` asserts it, and the Scala suite asserts the same
pairing from the classpath copy.
