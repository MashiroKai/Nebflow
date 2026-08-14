package nebflow.llm

import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil

/**
 * B3 Phase 1+2: vision tri-state + runtime demotion semantics.
 *
 * ModelRegistry.persistVision writes to `PathUtil.dataRoot / "models.json"`
 * and keeps a JVM-wide cache, so every test isolates the dataRoot in
 * beforeEach/afterEach (restoring + reloading after — a stale cache would
 * otherwise leak into other suites' file-section tests).
 */
class EmptyCompletionTrackerSpec extends CatsEffectSuite:

  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(os.temp.dir(prefix = "nb-vision-spec-"))
    ModelRegistry.reload()

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    ModelRegistry.reload()

  private val pid = "glm"
  private val mid = "glm-5"

  // ============================================================
  // isVisionError — explicit image/multimodal blame detection
  // ============================================================

  test("isVisionError matches image/multimodal blame, case-insensitive") {
    val tracker = EmptyCompletionTracker()
    assert(tracker.isVisionError("This model does not support IMAGE input"))
    assert(tracker.isVisionError("Error: multimodal content not enabled for this plan"))
    assert(tracker.isVisionError("multi-modal capability required"))
    assert(!tracker.isVisionError("invalid api key"))
    assert(!tracker.isVisionError("rate limit exceeded"))
  }

  // ============================================================
  // Signal 1: repeated empty completions (threshold >= 2, with image)
  // ============================================================

  test("onEmptyCompletion: single empty completion with image does not demote") {
    val tracker = EmptyCompletionTracker()
    for
      _ <- tracker.onEmptyCompletion(pid, mid, hadImage = true)
      v <- tracker.getRuntimeVision(pid, mid)
    yield assertEquals(v, None)
  }

  test("onEmptyCompletion: two empty completions with image demote and persist to models.json") {
    val tracker = EmptyCompletionTracker()
    for
      _ <- tracker.onEmptyCompletion(pid, mid, hadImage = true)
      _ <- tracker.onEmptyCompletion(pid, mid, hadImage = true)
      v <- tracker.getRuntimeVision(pid, mid)
      persisted = ModelRegistry.lookup(pid, mid).flatMap(_.vision)
    yield
      assertEquals(v, Some(false))
      // Phase 2: the override survives restarts as a static annotation
      assertEquals(persisted, Some(false))
  }

  test("onEmptyCompletion: empty completions without image never demote") {
    val tracker = EmptyCompletionTracker()
    for
      _ <- tracker.onEmptyCompletion(pid, mid, hadImage = false)
      _ <- tracker.onEmptyCompletion(pid, mid, hadImage = false)
      _ <- tracker.onEmptyCompletion(pid, mid, hadImage = false)
      v <- tracker.getRuntimeVision(pid, mid)
    yield assertEquals(v, None)
  }

  // ============================================================
  // Signal 2: explicit vision-blaming provider error (immediate)
  // ============================================================

  test("onVisionError: vision-blaming error demotes immediately and persists") {
    val tracker = EmptyCompletionTracker()
    for
      _ <- tracker.onVisionError(pid, mid, "this model does not support image input")
      v <- tracker.getRuntimeVision(pid, mid)
      persisted = ModelRegistry.lookup(pid, mid).flatMap(_.vision)
    yield
      assertEquals(v, Some(false))
      assertEquals(persisted, Some(false))
  }

  test("onVisionError: unrelated error does not demote") {
    val tracker = EmptyCompletionTracker()
    for
      _ <- tracker.onVisionError(pid, mid, "401 unauthorized")
      v <- tracker.getRuntimeVision(pid, mid)
    yield assertEquals(v, None)
  }

  // ============================================================
  // Oscillation fix — resetOnSuccess semantics
  // ============================================================

  test("resetOnSuccess: image-free success keeps the override (oscillation fix)") {
    val tracker = EmptyCompletionTracker()
    for
      _ <- tracker.onVisionError(pid, mid, "multimodal not supported")
      // A success after images were stripped proves nothing about vision —
      // lifting here would flip the model back to failing image requests.
      _ <- tracker.resetOnSuccess(pid, mid, hadImage = false)
      v <- tracker.getRuntimeVision(pid, mid)
    yield assertEquals(v, Some(false))
  }

  test("resetOnSuccess: image-bearing success lifts the override") {
    val tracker = EmptyCompletionTracker()
    for
      _ <- tracker.onVisionError(pid, mid, "multimodal not supported")
      _ <- tracker.resetOnSuccess(pid, mid, hadImage = true)
      v <- tracker.getRuntimeVision(pid, mid)
    yield assertEquals(v, None)
  }

  test("resetOnSuccess: clears the empty-completion counter") {
    val tracker = EmptyCompletionTracker()
    for
      _ <- tracker.onEmptyCompletion(pid, mid, hadImage = true)
      // Success (image-free) resets the counter; the next single empty
      // completion must NOT trip the threshold on stale counts.
      _ <- tracker.resetOnSuccess(pid, mid, hadImage = false)
      _ <- tracker.onEmptyCompletion(pid, mid, hadImage = true)
      v <- tracker.getRuntimeVision(pid, mid)
    yield assertEquals(v, None)
  }

  // ============================================================
  // Explicit restore — clearOverride (B3 restore semantics, 1b)
  // ============================================================

  test("clearOverride: explicit vision=true restore clears override and counters") {
    val tracker = EmptyCompletionTracker()
    for
      // Demote via signal 2 (immediate)
      _ <- tracker.onVisionError(pid, mid, "this model does not support image input")
      v1 <- tracker.getRuntimeVision(pid, mid)
      // User explicitly annotates vision=true (REST PUT): runtime heuristic
      // state must be cleared — explicit intent outranks auto-demotion.
      _ <- tracker.clearOverride(pid, mid)
      v2 <- tracker.getRuntimeVision(pid, mid)
      // Counters cleared too: a single later empty completion must not
      // re-trip the threshold on stale counts.
      _ <- tracker.onEmptyCompletion(pid, mid, hadImage = true)
      v3 <- tracker.getRuntimeVision(pid, mid)
    yield
      assertEquals(v1, Some(false))
      assertEquals(v2, None) // effectiveVision falls back to candidate.vision
      assertEquals(v3, None)
  }

  test("shared instance: REST clearOverride and the LLM pipeline see the same state") {
    // RestApiRoutes (PUT vision=true) clears EmptyCompletionTracker.shared;
    // interface.scala reads the same instance — otherwise the restore would
    // only fix the persisted annotation while the in-memory override keeps
    // stripping images until restart. Uses a dedicated modelId and cleans up,
    // so the JVM-wide singleton carries no state into other suites.
    val t1 = EmptyCompletionTracker.shared
    val t2 = EmptyCompletionTracker.shared
    assert(t1 eq t2)
    for
      _ <- t1.onVisionError(pid, "shared-model", "no image support")
      v <- t2.getRuntimeVision(pid, "shared-model")
      _ <- t1.clearOverride(pid, "shared-model")
      _ <- t1.clearOverride(pid, "shared-model") // also clears counters
      v2 <- t2.getRuntimeVision(pid, "shared-model")
    yield
      assertEquals(v, Some(false))
      assertEquals(v2, None)
  }

  // ============================================================
  // ModelRegistry tri-state annotations (B3 Phase 1)
  // ============================================================

  test("ModelRegistry: explicit vision annotations decode, absent field stays None") {
    // Raw JSON with partial fields — hand-written files must load: missing
    // capabilityTags falls back to empty (tolerant file decoder), missing
    // vision on an entry decodes to None — the input to the optimistic
    // getOrElse(true) in resolveCapabilities.
    os.write.over(
      PathUtil.dataRoot / "models.json",
      s"""{"models":{"$pid/explicit-true":{"vision":true},""" +
        s""""$pid/explicit-false":{"vision":false},""" +
        s""""$pid/unannotated":{"capabilities":[]}}}"""
    )
    ModelRegistry.reload()

    assertEquals(ModelRegistry.lookup(pid, "explicit-true").flatMap(_.vision), Some(true))
    assertEquals(ModelRegistry.lookup(pid, "explicit-false").flatMap(_.vision), Some(false))
    assertEquals(ModelRegistry.lookup(pid, "unannotated").flatMap(_.vision), None)
    assertEquals(ModelRegistry.lookup(pid, "never-seen"), None)
  }

  test("ModelRegistry.persistVision: preserves sibling fields, creates missing entries") {
    ModelRegistry.save(Map(
      s"$pid/$mid" -> ModelRegistry.ModelEntry(
        vision = Some(true),
        capabilities = List("reasoning", "tools")
      )
    ))
    ModelRegistry.persistVision(pid, mid, vision = false)
    assertEquals(ModelRegistry.lookup(pid, mid).flatMap(_.vision), Some(false))
    assertEquals(ModelRegistry.lookup(pid, mid).map(_.capabilities), Some(List("reasoning", "tools")))

    // Absent entry: persistVision creates it instead of failing
    ModelRegistry.persistVision(pid, "brand-new", vision = false)
    assertEquals(ModelRegistry.lookup(pid, "brand-new").flatMap(_.vision), Some(false))
  }

end EmptyCompletionTrackerSpec
