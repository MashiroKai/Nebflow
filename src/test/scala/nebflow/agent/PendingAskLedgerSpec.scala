package nebflow.agent

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import nebflow.actor.{InteractionAnswered, InteractionHubCommand, InteractionKind, InteractionReply, InteractionRequest}
import nebflow.shared.UiMessage

/**
 * PendingAskLedger — 重启孤儿对账批（2026-10-22「Header 待答徽标僵尸态」批）：
 *  - 台账读写往返 + 幂等销账；
 *  - hub 四条收口路径随行销账（Request 记 → answered/cleanup/close/cancel 删）；
 *  - bootReconcile：残留全部过期关闭（关卡帧 + 历史回执行 + 清账 + 汇总）。
 */
class PendingAskLedgerSpec extends CatsEffectSuite:

  private def tempLedger(): PendingAskLedger =
    new PendingAskLedger(os.temp.dir() / "pending-asks.json")

  private def entry(rid: String, ageH: Long = 1, root: String = "root-1"): PendingAskLedger.Entry =
    PendingAskLedger.Entry(
      requestId = rid,
      kind = "AskUser",
      rootSessionId = root,
      sourceSession = s"src-$rid",
      sourceAgent = "nebflow",
      firstQuestion = s"question $rid",
      createdAt = System.currentTimeMillis() - ageH * 3600000L
    )

  private def askRequest(requestId: String, rootSid: String = "root-1"): InteractionRequest =
    InteractionRequest(
      requestId = requestId,
      kind = InteractionKind.AskUser,
      payload = Json.obj(
        "type" -> Json.fromString("askUser"),
        "items" -> Json.arr(
          Json.obj(
            "question" -> Json.fromString("continue?"),
            "options" -> Json.arr(),
            "allowOther" -> Json.fromBoolean(false)
          )
        )
      ),
      reply = InteractionReply.AskUserReply(None),
      rootSessionId = rootSid,
      sourceAgent = "nebflow",
      sourceSession = "src-x"
    )

  // ── 台账本面 ──────────────────────────────────────────────────────────

  test("record/load roundtrip persists across instances (file is the authority)") {
    val path = os.temp.dir() / "pending-asks.json"
    val l1 = new PendingAskLedger(path)
    for
      _ <- l1.record(entry("r1"))
      _ <- l1.record(entry("r2", ageH = 30))
      // 新实例（重启模拟）从同一文件读出全量
      l2 = new PendingAskLedger(path)
      loaded <- l2.load()
    yield
      assertEquals(loaded.map(_.requestId).toSet, Set("r1", "r2"))
      assert(os.exists(path), "ledger file must exist after record")
    end for
  }

  test("remove is idempotent and only drops the targeted entry") {
    val ledger = tempLedger()
    for
      _ <- ledger.record(entry("r1"))
      _ <- ledger.record(entry("r2"))
      _ <- ledger.remove("r1")
      _ <- ledger.remove("r1") // 幂等
      _ <- ledger.remove("never-existed") // 幂等 no-op
      loaded <- ledger.load()
    yield assertEquals(loaded.map(_.requestId), List("r2"))
    end for
  }

  // ── bootReconcile ─────────────────────────────────────────────────────

  test("bootReconcile expires ALL leftovers: close frames + receipts + ledger cleared") {
    val ledger = tempLedger()
    for
      frames <- Ref.of[IO, List[Json]](Nil)
      receipts <- Ref.of[IO, List[(String, String)]](Nil)
      _ <- ledger.record(entry("fresh-1", ageH = 1, root = "root-A"))
      _ <- ledger.record(entry("stale-1", ageH = 30, root = "root-B"))
      report <- ledger.bootReconcile(
        broadcast = j => frames.update(_ :+ j),
        appendReceipt = (rootSid, msg) =>
          // 回执行内容取自 System 行（bootReconcile 的过期回执恒为 System 型）
          val text = msg match
            case s: UiMessage.System => s.content
            case other => other.toString
          receipts.update(_ :+ (rootSid -> text))
      )
      loaded <- ledger.load()
      fs <- frames.get
      rs <- receipts.get
    yield
      assertEquals(report, PendingAskLedger.Report(inspected = 2, closed = 2))
      assertEquals(loaded, Nil, "ledger must be cleared after reconcile")
      // 关卡帧：每条残留一帧，reason=expired-orphan，root 会话寻址
      assertEquals(fs.size, 2)
      val byRid = fs.map(f => f.hcursor.downField("requestId").as[String].toOption.get -> f).toMap
      assertEquals(byRid("fresh-1").hcursor.downField("reason").as[String], Right("expired-orphan"))
      assertEquals(byRid("fresh-1").hcursor.downField("sessionId").as[String], Right("root-A"))
      assertEquals(byRid("stale-1").hcursor.downField("sessionId").as[String], Right("root-B"))
      assertEquals(byRid("fresh-1").hcursor.downField("type").as[String], Right("askUserClosed"))
      // 历史回执行：落到各自 root 会话，内容可读（过期语义 + requestId 可归因）
      assertEquals(rs.map(_._1).toSet, Set("root-A", "root-B"))
      assert(rs.forall(_._2.contains("问题已过期关闭")), "receipt must carry the expiry semantics")
      assert(rs.exists((sid, txt) => sid == "root-A" && txt.contains("fresh-1")), "receipt names the requestId")
    end for
  }

  test("bootReconcile on an empty ledger is a zero-action no-op") {
    val ledger = tempLedger()
    for
      frames <- Ref.of[IO, List[Json]](Nil)
      report <- ledger.bootReconcile(broadcast = j => frames.update(_ :+ j), appendReceipt = (_, _) => IO.unit)
      fs <- frames.get
    yield
      assertEquals(report, PendingAskLedger.Report(inspected = 0, closed = 0))
      assertEquals(fs, Nil)
    end for
  }

  // ── hub 接线（ledger=Some 时四条收口路径随行销账）────────────────────────

  test("hub records on Request and removes on Answered / CloseRequest / CleanupForSession") {
    val system = nebflow.actor.ActorSystem("ledger-hub-test")
    val ledger = tempLedger()
    for
      hub <- system.spawn(InteractionHub(None, Some(ledger)), "interaction-hub-ledger")
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      // ① Request 记账
      _ <- hub ! InteractionHubCommand.Request(askRequest("lr-1"))
      _ <- hub ! InteractionHubCommand.Request(askRequest("lr-2"))
      _ <- hub ! InteractionHubCommand.Request(askRequest("lr-3", rootSid = "root-2"))
      _ <- IO.sleep(150.millis)
      afterAsk <- ledger.load()
      // ② answered 销账
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("lr-1", "root-1", Json.obj("answers" -> Json.arr(Json.fromString("yes"))))
      )
      _ <- IO.sleep(150.millis)
      afterAnswer <- ledger.load()
      // ③ CloseRequest（调用侧撤回）销账
      _ <- hub ! InteractionHubCommand.CloseRequest("lr-2")
      _ <- IO.sleep(150.millis)
      afterClose <- ledger.load()
      // ④ CleanupForSession（来源死亡级联）销账——lr-3 的 sourceSession=src-x
      _ <- hub ! InteractionHubCommand.CleanupForSession("src-x", "test-cascade")
      _ <- IO.sleep(150.millis)
      afterCleanup <- ledger.load()
      _ <- system.stopAll
    yield
      assertEquals(afterAsk.map(_.requestId).toSet, Set("lr-1", "lr-2", "lr-3"), "Request must record every slot")
      assertEquals(afterAsk.find(_.requestId == "lr-1").map(_.firstQuestion), Some("continue?"), "excerpt carried")
      assertEquals(afterAnswer.map(_.requestId), List("lr-2", "lr-3"), "answered entry removed")
      assertEquals(afterClose.map(_.requestId), List("lr-3"), "closed entry removed")
      assertEquals(afterCleanup, Nil, "cleanup cascade removes the rest")
    end for
  }

  test("hub without a ledger (default None) behaves byte-identically — no ledger file touched") {
    val system = nebflow.actor.ActorSystem("ledger-none-test")
    for
      hub <- system.spawn(InteractionHub(), "interaction-hub-nologger")
      _ <- hub ! InteractionHubCommand.RegisterRoot("root-1", (_: Json) => IO.unit)
      _ <- hub ! InteractionHubCommand.Request(askRequest("nl-1"))
      _ <- IO.sleep(150.millis)
      _ <- hub ! InteractionHubCommand.Answered(
        InteractionAnswered("nl-1", "root-1", Json.obj("answers" -> Json.arr(Json.fromString("ok"))))
      )
      _ <- IO.sleep(150.millis)
      _ <- system.stopAll
    yield assert(true, "no exception — default-None constructor unchanged")
    end for
  }

end PendingAskLedgerSpec
