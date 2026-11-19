# Design

Canvas Ask proposes in-memory, individually reviewable edits to the file it is already bound to.
`src/main/scala/nebflow/core/ask/AskEditReviews.scala` owns the review aggregate: an edit or a
whole-file rewrite is captured against a snapshot as the diff baseline; traversal, foreign paths and
symlinks are refused (`capture refuses other paths, relative paths, traversal and foreign sessions`,
`capture refuses symlinks, binary content and missing files`); a proposal stays pending until
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
