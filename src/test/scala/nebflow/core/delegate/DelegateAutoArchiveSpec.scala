package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SpecResources
import nebflow.core.project.ChainsReadFace
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * delegate/flowmap 两层架构批（2026-10-09 · 规格 §D 自动归档 + G4-2 反归档 +
 * G4-3 归档读面）——引擎自动归档单点验收：
 *
 *  - ① 终态自动归档：completed 终态 ⇒ `archivedAt` 单旗自动写入（零删除——记录
 *    本体原样留存）；幂等（二跑零重写，时间戳不变）。
 *  - ② blocked 判词豁免：completed 终态但 ChainStore 最新 terminal-verdict =
 *    blocked ⇒ 不归档（blocked 是「等用户介入」的活性终态，归档会把待办沉入
 *    历史窗）。
 *  - ③ failed/cancelled 终态照归：engine 补 blocked 判词行在先（onTerminalCleanup
 *    verdictLine 腿），autoArchive 只认「completed 事件 × blocked 判词」的组合豁免
 *    ——非 completed 事件照归（判词行只是 failed 的成因记录，不是活性待办）。
 *  - ④ unarchive 反操作（G4-2）：与 archive 对偶的单点——摘旗 `archivedAt = None`；
 *    未归档幂等零写；查无记录/非法 id = Left；archive 侧幂等（已归档原时间戳）。
 *  - ⑤ G4-3 归档读面：自动归档后 rows 两窗互补零重叠（在册窗无 / 历史窗
 *    `?archived=1` 有）；chainJson（GET /api/chains/:key 读面）对已归档键照常可读
 *    （台账号与 delegateId 双向键同面）——前端 L1 fail-soft 补拉的历史快照数据源。
 *  - ⑥ fail-soft 臂：查无记录（外部腿/旧盘）= 零动作零异常。
 *
 * 判定机缺席（withJudgeAbsent 同 DelegateTerminalSpec 先例）——本面只走
 * onTerminalCleanup（清理序列），不进判定机。
 */
class DelegateAutoArchiveSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-auto-archive"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()
    os.remove.all(tempRoot / "chains")
    os.remove.all(tempRoot / "delegates")
    os.remove.all(tempRoot / "subagent-tasks")

  override def afterEach(context: munit.AfterEach): Unit = MergeQueue.reset()

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def seed(id: String, taskId: Option[String]): Unit =
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
        projectWorkspace = Some(tempRoot.toString)
      )
    )

  /** blocked 判词行直落（真流程由 onCompleted 判定机/onTerminalCleanup 补行腿写）。 */
  private def seedBlockedVerdict(id: String, taskId: Option[String]): Unit =
    ChainStore.appendSync(
      ChainStore.keyFor(taskId, id),
      "terminal-verdict",
      DelegateJudge.verdictPayload(
        id,
        taskId,
        DelegateJudge.Verdict.Blocked("waiting for user"),
        source = "engine"
      )
    )

  private def archivedAt(id: String): Option[Long] = DelegateRegistry.find(id).flatMap(_.archivedAt)

  private def rowIds(json: Json): List[String] =
    json.hcursor.downField("rows").as[List[Json]].getOrElse(List.empty[Json]).flatMap { row =>
      row.hcursor.get[String]("id").toOption
    }

  test("① completed terminal auto-archives (single flag, zero delete) and is idempotent across reruns") {
    val system = ActorSystemFor("aa-1")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ = seed("delegate-nebflow-aa1", Some("81"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-aa1", "nebula-root", "completed", "completed")
      rec1 = DelegateRegistry.find("delegate-nebflow-aa1")
      ts1 = rec1.flatMap(_.archivedAt)
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-aa1", "nebula-root", "completed", "completed")
      ts2 = archivedAt("delegate-nebflow-aa1")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(ts1.isDefined, "the completed terminal must write the archivedAt flag")
      assertEquals(ts2, ts1, "the rerun must not rewrite the flag (idempotent archive)")
      // 零删除纪律：记录本体原样（title/branch 原值留存——历史窗读的是全记录）。
      assertEquals(rec1.map(_.title), Some("seeded"))
      assertEquals(rec1.flatMap(_.branch), Some("delegate/delegate-nebflow-aa1"))
    end for
  }

  test("② completed terminal with a blocked verdict is NOT auto-archived (waiting-for-user stays in the live window)") {
    val system = ActorSystemFor("aa-2")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ = seed("delegate-nebflow-aa2", Some("82"))
      _ = seedBlockedVerdict("delegate-nebflow-aa2", Some("82"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-aa2", "nebula-root", "completed", "completed")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(
        archivedAt("delegate-nebflow-aa2"),
        None,
        "a blocked verdict on the completed leg must stay unarchived (user follow-up pending)"
      )
    end for
  }

  test("③ failed/cancelled terminals auto-archive even though the engine wrote a blocked verdict line") {
    val system = ActorSystemFor("aa-3")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ = seed("delegate-nebflow-aa3", Some("83"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-aa3", "nebula-root", "failed", "failed")
      _ = seed("delegate-nebflow-aa4", Some("84"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-aa4", "nebula-root", "cancelled", "cancelled")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      // ③ 判别点：failed 腿先补 engine blocked 判词行，autoArchive 仍照归——
      // blocked 豁免只认「completed 事件 × blocked 判词」的组合（② 面为对照）。
      assert(archivedAt("delegate-nebflow-aa3").isDefined, "the failed terminal must auto-archive")
      assert(archivedAt("delegate-nebflow-aa4").isDefined, "the cancelled terminal must auto-archive")
    end for
  }

  test("④ unarchive is the single-point inverse (flag removal, idempotent, no delete); archive idempotent; invalid/unknown ids refuse") {
    seed("delegate-nebflow-aa5", Some("85"))
    val t1 = DelegateRegistry.archive("delegate-nebflow-aa5")
    val t2 = DelegateRegistry.archive("delegate-nebflow-aa5")
    assertEquals(t1.toOption.map(_ > 0), Some(true), "the manual archive writes a positive timestamp")
    assertEquals(t2, t1, "the second archive returns the original timestamp (zero rewrite)")
    assertEquals(DelegateRegistry.unarchive("delegate-nebflow-aa5"), Right(()), "unarchive removes the flag")
    assertEquals(archivedAt("delegate-nebflow-aa5"), None, "the record flows back to the live window")
    assertEquals(DelegateRegistry.find("delegate-nebflow-aa5").map(_.title), Some("seeded"), "zero delete: the record body is intact")
    assertEquals(DelegateRegistry.unarchive("delegate-nebflow-aa5"), Right(()), "unarchive on a live record is a no-op (zero write)")
    assert(DelegateRegistry.unarchive("delegate-nebflow-unknown").isLeft, "unknown id = Left")
    assert(DelegateRegistry.unarchive("bad id!").isLeft, "invalid id = Left")
    assert(DelegateRegistry.archive("delegate-nebflow-unknown").isLeft, "archive on an unknown id = Left (mirror arm)")
  }

  test("⑤ G4-3 read face: the two rows windows are complementary and chainJson stays readable for archived keys") {
    val system = ActorSystemFor("aa-5")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      _ = seed("delegate-nebflow-aa6", Some("86"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-aa6", "nebula-root", "completed", "completed")
      live <- ChainsReadFace.rowsJson(Map.empty, archived = false)
      hist <- ChainsReadFace.rowsJson(Map.empty, archived = true)
      byTask <- ChainsReadFace.chainJson("86", Map.empty)
      byId <- ChainsReadFace.chainJson("delegate-nebflow-aa6", Map.empty)
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(!rowIds(live).contains("delegate-nebflow-aa6"), "the live window must NOT list the archived row")
      // 历史窗行 = Record 原样投影（archivedAt 旗随 asJson 透出——抽屉/徽章计数源）。
      val histRows = hist.hcursor.downField("rows").as[List[Json]].getOrElse(List.empty[Json])
      assert(histRows.exists(_.hcursor.get[String]("id").toOption.contains("delegate-nebflow-aa6")), "the history window (?archived=1) must list it")
      val flagOnRow = histRows.flatMap(_.hcursor.get[Option[Long]]("archivedAt").toOption).flatten
      assert(flagOnRow.nonEmpty, "the history row carries the archivedAt flag (drawer/badge count source)")
      assert(byTask.isDefined, "chainJson by ledger taskNo stays readable after auto-archive (G4-3)")
      assert(byId.isDefined, "chainJson by delegateId stays readable (keyFor two-way face)")
    end for
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
