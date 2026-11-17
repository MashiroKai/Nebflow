package nebflow.core.mail

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.{Message, MessageRole, PathUtil}

/**
 * Round-aligned completion-debt generation spec (contract: roundId IS the debt
 * generation — the terminal mailbox key `<id>#<roundId>` and the round ledger share
 * one generation mechanism; legacy/no-round identities keep minting UUIDs).
 *
 *  - D-1 first record of a round mints the registry's current roundId.
 *  - D-2 same-round re-record (same recipient + content) keeps the generation.
 *  - D-3 after settlement, a new round's record mints the NEW roundId.
 *  - D-4 intra-round content drift mints a fresh UUID (distinct identity — the
 *    same round's re-completion must not collide with the round key).
 *  - D-5 records without a round pointer (legacy / external leg) mint a UUID.
 */
class CompletionDebtRoundIdSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "debt-roundid")
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def putRecord(id: String, roundId: Option[String]): Unit =
    nebflow.core.delegate.DelegateRegistry.put(
      nebflow.core.delegate.DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = tmp.toString,
        project = None,
        taskId = None,
        parentSessionId = "parent-debt",
        title = "debt round id",
        createdAt = System.currentTimeMillis(),
        roundNo = roundId.map(_ => 1),
        roundId = roundId
      )
    )

  private def msgs(text: String): List[Message] = List(Message(MessageRole.Assistant, Left(text)))

  private def generationOf(id: String): String =
    CompletionDebtStore.pending(id).unsafeRunSync().toOption.flatten.getOrElse(sys.error("debt must exist")).generation

  test("D-1 first record of a round mints the registry's roundId") {
    putRecord("delegate-debt-d1", Some("gen-d1-round"))
    val ok = CompletionDebtStore.record("delegate-debt-d1", "parent-debt", msgs("done")).unsafeRunSync()
    assert(ok)
    assertEquals(generationOf("delegate-debt-d1"), "gen-d1-round")
  }

  test("D-2 same-round re-record keeps the generation (idempotent identity)") {
    putRecord("delegate-debt-d2", Some("gen-d2-round"))
    // the SAME message instances, as the production re-declaration path does
    val once = msgs("done")
    CompletionDebtStore.record("delegate-debt-d2", "parent-debt", once).unsafeRunSync()
    CompletionDebtStore.record("delegate-debt-d2", "parent-debt", once).unsafeRunSync()
    assertEquals(generationOf("delegate-debt-d2"), "gen-d2-round")
  }

  test("D-3 after settlement the next round's record mints the NEW roundId") {
    putRecord("delegate-debt-d3", Some("gen-d3-r1"))
    val roundOne = msgs("round one done")
    CompletionDebtStore.record("delegate-debt-d3", "parent-debt", roundOne).unsafeRunSync()
    // settle (consumer ack) then open round 2 (registry pointer moves)
    CompletionDebtStore
      .acknowledge("delegate-debt-d3", "parent-debt", roundOne, "gen-d3-r1")
      .unsafeRunSync()
    putRecord("delegate-debt-d3", Some("gen-d3-r2"))
    // rewrite the registry pointer to round 2 (roundNo follows)
    nebflow.core.delegate.DelegateRegistry.startRound("delegate-debt-d3", 2, "gen-d3-r2")
    CompletionDebtStore.record("delegate-debt-d3", "parent-debt", msgs("round two done")).unsafeRunSync()
    assertEquals(generationOf("delegate-debt-d3"), "gen-d3-r2")
  }

  test("D-4 intra-round content drift mints a fresh UUID (no collision with the round key)") {
    putRecord("delegate-debt-d4", Some("gen-d4-round"))
    CompletionDebtStore.record("delegate-debt-d4", "parent-debt", msgs("first completion")).unsafeRunSync()
    assertEquals(generationOf("delegate-debt-d4"), "gen-d4-round")
    // same round, DIFFERENT content: a distinct identity is required
    CompletionDebtStore.record("delegate-debt-d4", "parent-debt", msgs("drifted completion")).unsafeRunSync()
    val drifted = generationOf("delegate-debt-d4")
    assert(drifted != "gen-d4-round", "content drift must not reuse the round key")
    assert(drifted.nonEmpty && !drifted.startsWith("gen-"), "the drifted identity is a fresh UUID")
  }

  test("D-5 records without a round pointer keep the legacy UUID minting") {
    putRecord("delegate-debt-d5", None)
    CompletionDebtStore.record("delegate-debt-d5", "parent-debt", msgs("legacy done")).unsafeRunSync()
    val gen = generationOf("delegate-debt-d5")
    assert(gen.nonEmpty, "legacy shape still mints a generation")
    assert(gen.length >= 32, s"a UUID-shaped generation: $gen")
  }

end CompletionDebtRoundIdSpec
