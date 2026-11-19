# Design

Validate branch names with Git and the six-class allowlist; reject non-feature/bug MR sources. A pre-push hook consumes every destination ref, failing closed on malformed input and protecting main and release branches unless explicitly allowed. The override is not authorization to bypass author-only policy. Wire the MR check into the existing USTC GitLab static job; server-side protections remain necessary because local hooks and source-controlled CI can be bypassed.

裁定 2026-11-19 17:35：release 支链由 `beta→stable` 改为一律从 `main` 派生，规范面为权威面。
