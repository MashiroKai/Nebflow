# Design

During a CLI index rebuild a vault document whose frontmatter `summary` is 40–100 Unicode code
points becomes a `document_summary` term named for its title. The extraction and the term write live
in the terms CLI (`tools/stand-terms`), which stores the whole summary, the document path, status
and file modification time; a missing, empty or out-of-range summary creates no term, and existing
declared terms and document records stay available. The extraction is centralised so that the
pending visible-summary-block choice can later be implemented at that seam. On the read side the
SQLite-backed `/api/wiki/terms` route exposes the new rows after a rebuild, while the live term
registry and the document graph do not ingest them in this change. The citation surface
`src/main/resources/web/js/documentTermCard.js` keys the card on `t.source === 'document_summary'`
and presents the stored metadata with an explicit fallback when no summary is stored.
