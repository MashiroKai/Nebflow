package nebflow.llm

import cats.effect.{IO, Ref}
import nebflow.core.NebflowLogger

/**
 * Tracks per-model signals that a model cannot actually handle image input,
 * and maintains a runtime vision=false override for such models — even when
 * their static config says vision=true (B3 Phase 1: optimistic by default,
 * demoted at runtime on evidence).
 *
 * Two independent demotion signals:
 *  - Repeated empty completions on requests that contained images (>= 2).
 *  - Provider error messages that explicitly blame image/multimodal support.
 *
 * Override semantics (oscillation fix): an override is only lifted by a
 * SUCCESSFUL completion that actually contained images. An image-free success
 * (e.g. after images were stripped) proves nothing about vision — lifting on
 * it would flip the model straight back to sending images, fail again, and
 * oscillate forever.
 *
 * Overrides are persisted to models.json (B3 Phase 2) so they survive
 * restarts: the persisted annotation then acts as a static annotation with
 * the same priority (see ModelRegistry / resolveCapabilities).
 */
final class EmptyCompletionTracker:

  private val logger = NebflowLogger.forName("nebflow.empty-tracker")

  case class Entry(emptyCount: Int, hadImage: Boolean)

  private val counters: Ref[IO, Map[String, Entry]] = Ref.unsafe(Map.empty)
  private val runtimeOverrides: Ref[IO, Map[String, Boolean]] = Ref.unsafe(Map.empty)

  private def ref(providerId: String, modelId: String): String = s"$providerId/$modelId"

  /** Lowercased fragments that indicate a vision/multimodal capability error. */
  private val VisionErrorPatterns = List(
    "image",
    "multimodal",
    "multi-modal",
    "not support",
    // Chinese phrasings used by domestic providers (GLM/Kimi/Qwen/DeepSeek
    // etc.). Compound phrases rather than bare "图片"/"图像": an error merely
    // containing 图片 (e.g. 图片下载失败, a download failure) is not a
    // vision-capability signal. isVisionError only sees provider error
    // messages, so these stay narrowly about image-input handling.
    "不支持图片",
    "不支持图像",
    "图片输入",
    "图像输入",
    "无法识别图片",
    "无法识别图像",
    "无法处理图片",
    "无法处理图像",
    "多模态",
    "不支持该类型内容"
  )

  /** Does this provider error message explicitly blame image support? */
  def isVisionError(errorMessage: String): Boolean =
    val lower = errorMessage.toLowerCase
    VisionErrorPatterns.exists(lower.contains)

  /** Set the runtime override and persist it to models.json (Phase 2). */
  private def setVisionOverrideFalse(providerId: String, modelId: String, reason: String): IO[Unit] =
    val key = ref(providerId, modelId)
    runtimeOverrides.update(_.updated(key, false)) *>
      IO(logger.warn(s"Runtime vision override: $key marked vision=false ($reason)")) *>
      IO.blocking {
        // Best-effort persistence — a write failure must not break the stream.
        try ModelRegistry.persistVision(providerId, modelId, vision = false)
        catch case e: Exception => logger.warnSync(s"models.json persist failed: ${e.getMessage}")
      }

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
      map.get(key) match
        case Some(e) if e.emptyCount >= 2 && e.hadImage =>
          setVisionOverrideFalse(
            providerId,
            modelId,
            s"${e.emptyCount} empty completions with image"
          )
        case _ => IO.unit
    }

  end onEmptyCompletion

  /**
   * Record a provider error that explicitly blames image/multimodal support.
   * Immediate demotion — no waiting for the empty-completion threshold.
   */
  def onVisionError(providerId: String, modelId: String, errorMessage: String): IO[Unit] =
    IO.whenA(isVisionError(errorMessage))(
      setVisionOverrideFalse(providerId, modelId, s"provider error: ${errorMessage.take(200)}")
    )

  /**
   * Record a successful completion.
   *
   * Oscillation fix: the vision=false override is only lifted when the
   * successful request actually contained images (`hadImage=true`) — proof
   * the model can process vision. An image-free success (typical after
   * stripImages) only clears the empty-completion counter; the override
   * stays, so the model is not flip-flopped back into failing requests.
   */
  def resetOnSuccess(providerId: String, modelId: String, hadImage: Boolean): IO[Unit] =
    val key = ref(providerId, modelId)
    counters.update(_ - key) *>
      IO.whenA(hadImage)(runtimeOverrides.update(_ - key))

  /** Check if there's a runtime vision override for this model. Returns Some(false) if flagged. */
  def getRuntimeVision(providerId: String, modelId: String): IO[Option[Boolean]] =
    runtimeOverrides.get.map(_.get(ref(providerId, modelId)))

  /**
   * Clear ALL runtime heuristics (override + empty-completion counters) for a
   * model. Called when the user explicitly annotates vision=true via REST: an
   * explicit annotation is a statement of intent that outranks the runtime
   * auto-demotion heuristic (B3 restore semantics — without this, a persisted
   * vision=false plus a live in-memory override could never be recovered
   * without a restart, because stripImages guarantees no image-bearing success
   * ever reaches resetOnSuccess).
   */
  def clearOverride(providerId: String, modelId: String): IO[Unit] =
    val key = ref(providerId, modelId)
    counters.update(_ - key) *> runtimeOverrides.update(_ - key)

end EmptyCompletionTracker

object EmptyCompletionTracker:
  /**
   * Shared JVM-wide instance. Runtime vision overrides are keyed by
   * providerId/modelId — they describe the MODEL, not the agent session — so
   * the LLM pipeline (interface.scala) and the REST API (RestApiRoutes,
   * PUT vision=true → clearOverride) must read/write the same state.
   */
  val shared: EmptyCompletionTracker = new EmptyCompletionTracker
end EmptyCompletionTracker
