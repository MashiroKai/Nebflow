# Design

Canvas Ask proposes in-memory, individually reviewable edits to the file it is already bound to.
`src/main/scala/nebflow/core/ask/AskEditReviews.scala` owns the review aggregate: an edit or a
whole-file rewrite is captured against a snapshot as the diff baseline; traversal, foreign paths and
symlinks are refused (`capture refuses other documents and foreign sessions, and normalises .. /
relative spellings`, `capture follows identity through symlinks: same document passes, another
target is refused`); a proposal stays pending until
individually accepted or rejected; accept writes the source file only if its snapshot is still
current and reloads the Canvas content, while reject leaves it untouched. Rewrites go through the
same review seam (`ask_edit`/`ask_write` refuse non-ask sessions and ask sessions without a
binding). The file-bound shell face is constrained separately:
`src/main/scala/nebflow/core/ask/AskBashGuard.scala` validates a command at the post-hook
pre-routing execution boundary and is fail-closed — unknown commands, write primitives, shell
expansion, command substitution, redirection, compound operators and unsafe arguments are refused,
and git is limited to read-only subcommands with the pager pinned off. The allowlist and argument
validation are best-effort enforcement with parser defects as residual risk and are NOT an
operating-system sandbox.

## Note (author ruling, 2026-11-19): release-line test literals

On the release line `AskEditReviewSpec.scala` names the two capture refusals
`capture refuses other documents and foreign sessions, and normalises .. / relative spellings`
(`src/test/scala/nebflow/core/ask/AskEditReviewSpec.scala:246`) and `capture follows identity through
symlinks: same document passes, another target is refused` (`:278`). The binary-content and
missing-file refusals have no separately named test there: they are asserted inside the symlink test
(`:288-307` — `assert(b.left.exists(_.contains("binary"))` at `:306`, `assert(m.left.exists(_.contains("does not exist"))` at `:307`).
CAE-SCN-002 therefore cites those two test literals. The binary and missing-file refusals are really
covered, but the coverage is not a standalone named test, and this note is the record of that gap
rather than a claim that it is one.
