# Tasks

- [x] Extract a frontmatter `summary` of 40–100 Unicode code points during the CLI index rebuild.
- [x] Store the whole summary, document path, status and file modification time as a `document_summary` term.
- [x] Leave the index unchanged for missing, empty or out-of-range summaries.
- [x] Expose the new rows through the SQLite-backed terms read side and the document citation card, with an explicit fallback when no summary is stored.
- [ ] Visible summary block at the extraction seam — NOT implemented (the seam exists; the choice is pending).
- [ ] Ingest the rows into the live term registry and the document graph — NOT implemented in this change.
- [ ] Real isolated-instance screenshots of the citation card — NOT captured in this batch.
