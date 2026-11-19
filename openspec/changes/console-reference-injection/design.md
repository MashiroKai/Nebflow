# Design

Resolution happens on the console delivery path before the agent sees the text, and the stored
author transcript is untouched. `src/main/scala/nebflow/gateway/ConsoleQuoteResolver.scala` owns the
expansion: it resolves a leading todo reply `#title·id` against the scheduled-task store (`detail`
when present, otherwise `content`), injects short bodies verbatim, and folds bodies longer than
4,000 UTF-16 code units down to the first 500 units plus a query handle
(`Schedule {action:"get", id:"..."}`). `src/main/scala/nebflow/core/msgtokens/MsgTokenRegistry.scala`
mints the message-token handle for a long structured quote or a long snapshot, and
`src/main/scala/nebflow/gateway/SessionRoutes.scala` serves the authenticated
`GET /api/sessions/msg-token/<sid8-seq6>` read. A client-declared absolute path is resolved only
when it is a currently readable regular file and its canonical local path is injected with a
source-unverified warning; a missing, relative, unreadable or cross-device path receives an explicit
unavailable marker. Missing or unresolvable identifiers produce an explicit marker plus a diagnostic
warning, and todo identity is the eight-hex scheduled-task id, never a message-card id.
