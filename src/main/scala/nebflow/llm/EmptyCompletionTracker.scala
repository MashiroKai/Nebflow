package nebflow.llm

import cats.effect.{IO, Ref}
import nebflow.core.NebflowLogger

/**
 * Tracks empty completions per model to detect runtime vision capability issues.
 *
 * When a model returns empty completions repeatedly with image content, it likely
 * doesn't support vision — even if its config says vision=true. The tracker marks
 * such models with a runtime vision=false override.
 *
 * State is in-memory only (not persisted). Resets on restart.
 */
final class EmptyCompletionTracker:

  private val logger = NebflowLogger.forName("nebflow.empty-tracker")

  case class Entry(emptyCount: Int, hadImage: Boolean)

  private val counters: Ref[IO, Map[String, Entry]] = Ref.unsafe(Map.empty)
  private val runtimeOverrides: Ref[IO, Map[String, Boolean]] = Ref.unsafe(Map.empty)

  private def ref(providerId: String, modelId: String): String = s"$providerId/$modelId"

  /** Record an empty completion. If threshold reached with images, marks runtime vision=false. */
  def onEmptyCompletion(providerId: String, modelId: String, hadImage: Boolean): IO[Unit] =
    val key = ref(providerId, modelId)
    counters.update { map =>
      val existing = map.getOrElse(key, Entry(0, false))
      val updated = Entry(
        emptyCount = existing.emptyCount + 1,
        hadImage = existing.hadImage || hadImage
      )
      map.updated(key, updated)
    } *> counters.get.flatMap { map =>
      val entry = map.get(key)
      entry match
        case Some(e) if e.emptyCount >= 2 && e.hadImage =>
          // Runtime detection: this model likely doesn't support vision
          runtimeOverrides.update(_.updated(key, false)) *>
            logger.warn(
              s"Runtime detection: $key likely does not support vision " +
                s"(${e.emptyCount} empty completions with image)"
            )
        case _ => IO.unit
    }

  end onEmptyCompletion

  /**
   * Reset counter and runtime override on successful completion — clears any
   *  transient issues and lifts a previously-set vision=false override so a
   *  mis-detected model can be re-evaluated instead of staying flagged until
   *  process restart.
   */
  def resetOnSuccess(providerId: String, modelId: String): IO[Unit] =
    val key = ref(providerId, modelId)
    counters.update(_ - key) *> runtimeOverrides.update(_ - key)

  /** Check if there's a runtime vision override for this model. Returns Some(false) if flagged. */
  def getRuntimeVision(providerId: String, modelId: String): IO[Option[Boolean]] =
    runtimeOverrides.get.map(_.get(ref(providerId, modelId)))

end EmptyCompletionTracker
