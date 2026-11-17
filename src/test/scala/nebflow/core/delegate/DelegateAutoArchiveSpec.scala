package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.SpecResources
import nebflow.core.project.ChainsReadFace
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * delegate-lifecycle-reform **阶段②**（2026-11-17 · 状态派生可见性替换存储式归档）——
 * 本 spec 由「引擎自动归档验收」改为「**归档腿退役验收**」：存储式归档的写点已整体
 * 退役，收口由 [[DelegateVisibility]] 现读复算，「用户不想再看」改走
 * [[DelegateRegistry.Record.disposition]] 的**非破坏性**表态旗（零删除）。
 *
 *  - ① **写点退役**：`onTerminalCleanup` 不再写 `archivedAt`（引擎自动归档腿整体删除），
 *    记录本体零删除、幂等。
 *  - ② **判词腿不受影响**：failed/cancelled 的 engine 判词行仍写真值（verdict 值域零
 *    blocked）——退役只摘归档写点，不动判词。
 *  - ③ **表态旗单点**（替换 archive/unarchive 对偶）：`setDisposition` 幂等、值域闸、
 *    查无记录 = Left；`clearDisposition` 是非破坏性对偶面。
 *  - ④ **两窗可见性**（状态派生）：存量归档旗 ⇒ 历史窗（863 条旧记录观感零变化）；
 *    轮次已结算但**未表态** ⇒ 活动窗（新制的「用户尚未表态」形态）；表态 ⇒ 历史窗，
 *    清旗 ⇒ 回活动窗（纯派生，零写盘复现）。
 *  - ⑤ **读面**：两窗互补零重叠 + 行携带 `visibility` 词；chainJson 双向键照常可读。
 *  - ⑥ **fail-soft 臂**：查无记录（外部腿/旧盘）= 零动作零异常。
 *
 * 判定机缺席（withJudgeAbsent 同 DelegateTerminalSpec 先例）——本面只走
 * onTerminalCleanup（清理序列），不进判定机。
 */
class DelegateAutoArchiveSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-auto-archive"
  private val originalRoot = PathUtil.dataRoot

  private val now: Long = System.currentTimeMillis()

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "chains")
    os.remove.all(tempRoot / "delegates")
    os.remove.all(tempRoot / "subagent-tasks")

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def seed(
    id: String,
    taskId: Option[String],
    archivedAt: Option[Long] = None
  ): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = tempRoot.toString,
        project = None,
        taskId = taskId,
        parentSessionId = "nebula-root",
        title = "seeded",
        createdAt = 1L,
        branch = Some(s"delegate/$id"),
        baseBranch = Some("main"),
        projectWorkspace = Some(tempRoot.toString),
        archivedAt = archivedAt
      )
    )

  /** 轮次台账直接落盘（append-only JSONL 的盘上形态）——状态派生的收口证据。 */
  private def seedRound(id: String, roundNo: Int, settledAt: Option[Long]): Unit =
    val dir = tempRoot / "delegates"
    os.makeDir.all(dir)
    val row = Json.obj(
      "kind" -> "round".asJson,
      "roundNo" -> roundNo.asJson,
      "roundId" -> s"r$roundNo".asJson,
      "startedAt" -> (now - 1000L).asJson,
      "cause" -> "spawn".asJson,
      "settledAt" -> settledAt.asJson
    )
    os.write.append(dir / s"$id.rounds.jsonl", row.noSpaces + "\n", createFolders = true)

  /** 退役 blocked 判词行直落（#566：仅模拟旧盘遗留行——引擎已永不写出该词）。 */
  private def seedBlockedVerdict(id: String, taskId: Option[String]): Unit =
    ChainStore.appendSync(
      ChainStore.keyFor(taskId, id),
      "terminal-verdict",
      Json
        .obj(
          "delegateId" -> Json.fromString(id),
          "taskId" -> Json.fromString(taskId.getOrElse("")),
          "verdict" -> Json.fromString("blocked"),
          "reason" -> Json.fromString("waiting for user"),
          "source" -> Json.fromString("engine")
        )
    )

  private def archivedAt(id: String): Option[Long] = DelegateRegistry.find(id).flatMap(_.archivedAt)

  private def rowIds(json: Json): List[String] =
    json.hcursor.downField("rows").as[List[Json]].getOrElse(List.empty[Json]).flatMap { row =>
      row.hcursor.get[String]("id").toOption
    }

  private def windowIds(archived: Boolean): List[String] =
    rowIds(ChainsReadFace.rowsJson(Map.empty, archived = archived).unsafeRunSync())

  test("① the auto-archive leg is RETIRED: terminal cleanup writes no archive flag (zero delete, idempotent)") {
    val system = ActorSystemFor("aa-1")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ = seed("delegate-nebflow-aa1", Some("81"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-aa1", "nebula-root", "completed", "completed")
      rec1 = DelegateRegistry.find("delegate-nebflow-aa1")
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-aa1", "nebula-root", "completed", "completed")
      rec2 = DelegateRegistry.find("delegate-nebflow-aa1")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(
        rec1.flatMap(_.archivedAt),
        None,
        "the auto-archive leg is retired — cleanup writes NO archive flag (state-derived visibility only)"
      )
      assertEquals(rec2.flatMap(_.archivedAt), None, "the rerun is likewise a zero-write no-op")
      // 零删除纪律：记录本体原样（title/branch 原值留存——历史窗读的是全记录）。
      assertEquals(rec1.map(_.title), Some("seeded"))
      assertEquals(rec1.flatMap(_.branch), Some("delegate/delegate-nebflow-aa1"))
      assertEquals(rec1.map(_.createdAt), rec2.map(_.createdAt), "idempotent: the record body is untouched across reruns")
      assert(os.exists(tempRoot / "delegates" / "delegate-nebflow-aa1.json"), "zero deletion: the record file stays")
    end for
  }

  test(
    "② #566: engine terminal verdict rows still carry true values (failed/cancelled) — the retirement touched no verdict leg"
  ) {
    val system = ActorSystemFor("aa-2")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ = seed("delegate-nebflow-aa2", Some("82"))
      _ = seedBlockedVerdict("delegate-nebflow-aa2", Some("82"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-aa2", "nebula-root", "completed", "completed")
      _ = seed("delegate-nebflow-aa3", Some("83"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-aa3", "nebula-root", "failed", "failed")
      _ = seed("delegate-nebflow-aa4", Some("84"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-aa4", "nebula-root", "cancelled", "cancelled")
      verdictRows = ChainStore.listSync().flatMap(_.entries).filter(_.kind == "terminal-verdict")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      // completed 腿的判词行由 onCompleted 写，本处不重复；failed/cancelled 写真值。
      def verdictOf(did: String): Option[String] =
        verdictRows
          .filter(_.payload.hcursor.get[String]("delegateId").toOption.contains(did))
          .flatMap(_.payload.hcursor.get[String]("verdict").toOption)
          .lastOption
      assertEquals(verdictOf("delegate-nebflow-aa3"), Some("failed"), "engine verdict rows carry true terminal values")
      assertEquals(verdictOf("delegate-nebflow-aa4"), Some("cancelled"), "engine verdict rows carry true terminal values")
      // 引擎不再写任何归档旗（#566 的「blocked 判词豁免」随归档腿整体退役而失去意义）。
      assertEquals(archivedAt("delegate-nebflow-aa2"), None, "a legacy blocked verdict row triggers no archive write")
    end for
  }

  test(
    "③ disposition is the non-destructive single point (idempotent, value gate, unknown id = Left; clear is its dual)"
  ) {
    seed("delegate-nebflow-aa5", Some("85"))
    val first = DelegateRegistry.setDisposition("delegate-nebflow-aa5", DelegateRegistry.DispositionAcknowledged)
    val second = DelegateRegistry.setDisposition("delegate-nebflow-aa5", DelegateRegistry.DispositionAcknowledged)
    assertEquals(first.toOption.map(_ > 0), Some(true), "the first disposition write records a positive timestamp")
    assertEquals(second, first, "the second write returns the original timestamp (zero rewrite)")
    assertEquals(
      DelegateRegistry.dispositionOf("delegate-nebflow-aa5").map(_._1),
      Some(DelegateRegistry.DispositionAcknowledged),
      "the flag reads back"
    )
    // 值域闸：自由文本绝不落盘。
    assert(
      DelegateRegistry.setDisposition("delegate-nebflow-aa5", "meh").isLeft,
      "a value outside the disposition word range = Left"
    )
    // 零删除：记录本体原样。
    assertEquals(DelegateRegistry.find("delegate-nebflow-aa5").map(_.title), Some("seeded"))
    // 非破坏性对偶面：清旗回现役（零删除、历史不变）。
    assertEquals(DelegateRegistry.clearDisposition("delegate-nebflow-aa5"), Right(()), "clear removes the flag")
    assertEquals(DelegateRegistry.dispositionOf("delegate-nebflow-aa5"), None, "the flag is gone")
    assertEquals(DelegateRegistry.clearDisposition("delegate-nebflow-aa5"), Right(()), "clearing again is a zero-write no-op")
    assert(DelegateRegistry.setDisposition("delegate-nebflow-unknown", DelegateRegistry.DispositionIgnored).isLeft, "unknown id = Left")
    assert(DelegateRegistry.setDisposition("bad id!", DelegateRegistry.DispositionIgnored).isLeft, "invalid id = Left")
    assert(DelegateRegistry.clearDisposition("delegate-nebflow-unknown").isLeft, "clear on an unknown id = Left")
    assertEquals(DelegateRegistry.find("delegate-nebflow-aa5").map(_.title), Some("seeded"), "zero delete throughout")
  }

  test("④ state-derived windows: settlement alone stays ACTIVE; settlement + marker (legacy flag or disposition) ⇒ history") {
    // (a) 存量归档旗、无轮次台账 ⇒ 收口（旧记录观感零变化）。
    seed("delegate-nebflow-aa6", Some("86"), archivedAt = Some(now))
    // (b) 轮次已结算、未表态 ⇒ **活动窗**（新制：用户尚未表态）。
    seed("delegate-nebflow-aa7", Some("87"))
    seedRound("delegate-nebflow-aa7", 1, settledAt = Some(now - 60_000L))
    // (c) 轮次已结算 + 表态 ⇒ 历史窗。
    seed("delegate-nebflow-aa8", Some("88"))
    seedRound("delegate-nebflow-aa8", 1, settledAt = Some(now - 60_000L))
    assertEquals(
      DelegateRegistry.setDisposition("delegate-nebflow-aa8", DelegateRegistry.DispositionIgnored).isRight,
      true
    )
    // (d) 未收口 ⇒ 活动窗（无证据）。
    seed("delegate-nebflow-aa9", Some("89"))

    val active = windowIds(archived = false)
    val history = windowIds(archived = true)
    assert(active.contains("delegate-nebflow-aa7"), s"settled but unmarked ⇒ active window; active=$active")
    assert(!history.contains("delegate-nebflow-aa7"), s"settled but unmarked is NOT a history row; history=$history")
    assert(history.contains("delegate-nebflow-aa6"), s"legacy archive flag ⇒ history window; history=$history")
    assert(history.contains("delegate-nebflow-aa8"), s"settled + disposition ⇒ history window; history=$history")
    assert(active.contains("delegate-nebflow-aa9"), s"no settlement evidence ⇒ active window; active=$active")

    // 清旗 ⇒ 纯派生回活动窗（零写盘复现「复活即回现役」的既有语义）。
    assertEquals(DelegateRegistry.clearDisposition("delegate-nebflow-aa8"), Right(()))
    val active2 = windowIds(archived = false)
    val history2 = windowIds(archived = true)
    assert(active2.contains("delegate-nebflow-aa8"), s"clearing the flag returns it to the active window; active=$active2")
    assert(!history2.contains("delegate-nebflow-aa8"), s"and out of the history window; history=$history2")
  }

  test("⑤ read face: the rows projection carries the visibility word and chainJson stays readable (G4-3)") {
    seed("delegate-nebflow-aa10", Some("810"), archivedAt = Some(now))
    val history = ChainsReadFace.rowsJson(Map.empty, archived = true).unsafeRunSync()
    val histRows = history.hcursor.downField("rows").as[List[Json]].getOrElse(List.empty[Json])
    val row = histRows.find(_.hcursor.get[String]("id").toOption.contains("delegate-nebflow-aa10"))
    assert(row.isDefined, "the history window lists the settled row")
    assertEquals(
      row.flatMap(_.hcursor.get[String]("visibility").toOption),
      Some("history"),
      "the row carries the state-derived visibility word"
    )
    assertEquals(
      row.flatMap(_.hcursor.get[Boolean]("settled").toOption),
      Some(true),
      "the row carries the state-derived settled reading"
    )
    // chainJson 双向键（台账号/ delegateId）对历史窗键照常可读。
    assert(ChainsReadFace.chainJson("810", Map.empty).unsafeRunSync().isDefined, "chainJson by ledger taskNo stays readable")
    assert(
      ChainsReadFace.chainJson("delegate-nebflow-aa10", Map.empty).unsafeRunSync().isDefined,
      "chainJson by delegateId stays readable (keyFor two-way face)"
    )
  }

  test("⑥ fail-soft arm: cleanup on an unknown id (external leg / legacy disk) is a zero-action no-op") {
    val system = ActorSystemFor("aa-6")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-never", "nebula-root", "completed", "completed")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield assertEquals(DelegateRegistry.find("delegate-nebflow-never"), None, "no record is fabricated")
    end for
  }

  private def ActorSystemFor(tag: String): nebflow.actor.ActorSystem =
    nebflow.actor.ActorSystem(s"daa-$tag-${scala.util.Random.nextInt(100000)}")

end DelegateAutoArchiveSpec
