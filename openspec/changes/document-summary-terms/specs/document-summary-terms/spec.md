## ADDED Requirements

### Requirement: [DST-REQ-001] Frontmatter summaries become a searchable document-summary term

During a CLI index rebuild a vault document whose frontmatter `summary` is 40–100 Unicode code
points SHALL become a `document_summary` term named for its title, storing the whole summary, the
document path, status and file modification time. Missing, empty or out-of-range summaries SHALL NOT
create the term, and existing declared terms and document records SHALL remain available.

#### Scenario: [DST-SCN-001] Summary length boundary

- **WHEN** the index rebuild reads frontmatter summaries of 39, 40, 100 and 101 code points and documents without a summary
- **THEN** only the 40–100 range produces a document-summary term and the other inputs leave the index unchanged

### Requirement: [DST-REQ-002] The document citation card shows complete summary metadata

A document citation card SHALL open before navigation, SHALL query the terms API for an exact local
document path on every opening, and SHALL present the full summary, status, file update time and
path. A document without a stored summary SHALL keep an explicit fallback with an open-document
button, and Escape or an outside click SHALL close the card and restore focus.

#### Scenario: [DST-SCN-002] Card with and without a summary

- **WHEN** a document link, backlink or reference card opens for a locally indexed document with and without a stored summary
- **THEN** the card shows the stored metadata, or the explicit fallback when no summary is stored
