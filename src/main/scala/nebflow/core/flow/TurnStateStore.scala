package nebflow.core.flow

import cats.effect.IO
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * Deletes a session's turn-state marker file.
 *
 * R4 legacy-hygiene batch (2026-10-29): this object used to be the durable
 * crash-recovery marker (`save` / `load` + `TurnState` codec). Audit verdict
 * (report II §3.4-①, mapping table P4): `save` had NO production call site —
 * the restore path in FlowTreeActor read files that nothing ever wrote
 * (fake recovery chain). The whole save/load/restore subtree was removed;
 * only the cleanup primitive survives (deleteSession hygiene — consumed by
 * SessionStore, asserted by DeleteSessionCleanupSpec via the raw file).
 *
 * Storage: ~/.nebflow/sessions/<sessionId>/turn-state.json
 */
object TurnStateStore:
  private val logger = NebflowLogger.forName("nebflow.flow.turnstate")

  /** Delete the turn-state file (session deleted). No-op for empty sessionId. */
  def clear(sessionId: String): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      val file = PathUtil.dataRoot / "sessions" / sessionId / "turn-state.json"
      IO.blocking {
        if os.exists(file) then os.remove(file)
      }.void
        .handleErrorWith(e => logger.warn(s"TurnStateStore.clear failed for $sessionId: ${e.getMessage}").void)

end TurnStateStore
