# Document summary terms

## Delta

During CLI index rebuild, a vault document with a frontmatter `summary` of 40–100 Unicode code points becomes a searchable `document_summary` term named for its title. Store the entire summary, document path, status and file modification time. Missing, empty or out-of-range summaries do not create a document-summary term; existing declared terms and document records remain available. Extraction is centralized to allow the pending choice of a visible summary block to be implemented at that seam.

`list`, `show` and `doc` expose complete document-summary metadata in text and JSON; existing term behavior remains unchanged. The SQLite-backed `/api/wiki/terms` read side sees the new rows after rebuild. The live term registry and document graph do not ingest these rows in this change.

## Acceptance

Automated tests cover missing, underlength, 40, 100, and overlength summaries and real CLI rebuild/list/show/doc including complete text, path, status and modification time. Execute the suite 20 consecutive times in a single JVM without retry. No frontend or runtime instance changes.
