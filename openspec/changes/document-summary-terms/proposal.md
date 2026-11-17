# Document summary terms

## Delta

During CLI index rebuild, a vault document with a frontmatter `summary` of 40–100 Unicode code points becomes a searchable `document_summary` term named for its title. Store the entire summary, document path, status and file modification time. Missing, empty or out-of-range summaries do not create a document-summary term; existing declared terms and document records remain available. Extraction is centralized to allow the pending choice of a visible summary block to be implemented at that seam.

`list`, `show` and `doc` expose complete document-summary metadata in text and JSON; existing term behavior remains unchanged. The SQLite-backed `/api/wiki/terms` read side sees the new rows after rebuild. The live term registry and document graph do not ingest these rows in this change.

## Acceptance

Automated tests cover missing, underlength, 40, 100, and overlength summaries and real CLI rebuild/list/show/doc including complete text, path, status and modification time. Execute the suite 20 consecutive times in a single JVM without retry.

## Document citation card delta

Document wikilinks and backlinks, and document reference message cards, open a keyboard-accessible card before navigation. The card queries the SQLite terms API for an exact local document path on every opening and presents the full summary, status, file update time and path; absent summaries retain an explicit fallback and an open-document button. Escape and outside click close and restore focus. Ordinary glossary-term previews and explicit file-opening surfaces remain unchanged. Remote documents show an unavailable-summary state because the local SQLite index cannot establish remote identity. Tests exercise the two citation surfaces, backlinks, missing summary and keyboard actions; real isolated-instance screenshots remain an acceptance requirement.
