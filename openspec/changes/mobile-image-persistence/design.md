# Design

An outgoing browser-selected device image is published to an owner-only stable copy before it is
sent, so it stays readable after the browser loses its file handle. This intentionally revises the
previous zero-retention behaviour for browser image transfers; agent-origin files and non-image
transfers keep their existing behaviour. `src/main/scala/nebflow/dropbox/DropboxService.scala` owns
the publish leg and the capacity warning (`imageCopyLimitReached` around lines 1144 and 2097), and
`src/main/scala/nebflow/shared/DropboxModels.scala` carries the new `imageCopyPath` field (lines
57-62, 149, 183, 203) while `saveOutPath` continues to record the existing path. The sender's
authenticated clients resolve the history path through the existing file-ticket route; the local
path is never sent to the peer. Copies are addressed by SHA-256 and a retry publishes atomically
from the newly verified upload bytes. Image copies have no automatic TTL: a capacity warning is
logged at 10 GiB and above without deleting anything, which is a warning threshold and not a
write-enforced quota. Chat upload durability, Canvas Ask attachment support, failed-image retry,
device-message deletion and cross-client end-to-end acceptance remain separate open work.
