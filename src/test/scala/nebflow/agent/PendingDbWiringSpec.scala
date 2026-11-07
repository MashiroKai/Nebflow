package nebflow.agent

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{InteractionAnswered, InteractionHubCommand, InteractionKind, InteractionReply, InteractionRequest}
import nebflow.db.format.PendingRecord
import nebflow.db.{Db, DbConfig, PendingStream, ReadResult}
import nebflow.shared.UiMessage

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * db 接线第一阶 C3 —— 待审单落库（设计 D-8 / 验收②「重启后待审单不丢」）：
 *
 *  1. record/remove 写穿 db pending 面（开单行 + 决议事件行；digest 对拍）。
 *  2. 「重启」重水合：permission/mcpPermission 未闭单**重装回 hub 且可答**（答
 *     决议经既有收口链回写 db 闭单行）；ask 未闭单**过期关闭**（expired-by-restart
 *     终态行 + 广播帧 + V6 孤儿 ask == 0）。
 *  3. legacy 台账与 db 面双读去重（同一 requestId 不双播）。
 *  4. pending 档损坏 ⇒ 显式拒绝（隔离 + WARN + legacy 腿全量兜底——绝不静默空起步）。
 */
class PendingDbWiringSpec extends CatsEffectSuite:

  extension (h: nebflow.db.DbHandle)
    private def foldState(stream: PendingStream, requestId: String): IO[Option[PendingRecord]] =
      h.readPending(stream).map {
        case ReadResult.Present(sr) =>
          val latest = nebflow.db.Recovery.foldPending(sr.records).find(_.requestId == requestId)
          latest
        case _ => None
      }

  /** IO[(临时目录, 句柄)] 的顺手件：munit CatsEffectSuite 无 unsafeRunSync 便利。 */
  private def openDbAt(): IO[(java.nio.file.Path, nebflow.db.DbHandle)] =
    IO.blocking(Files.createTempDirectory("pending-db")).flatMap { root =>
      Db.open(DbConfig(dataRoot = root)).map(h => (root, h))
    }

  private def permCard(rid: String, deferred: Deferred[IO, Boolean]): InteractionRequest =
    InteractionRequest(
      requestId = rid,
      kind = InteractionKind.Permission,
      payload = Json.obj("type" -> Json.fromString("askPermission"),
        "toolName" -> Json.fromString("Bash"), "summary" -> Json.fromString("rm -rf scratch")),
      reply = InteractionReply.PermissionReply(deferred),
      rootSessionId = "root-db",
      sourceAgent = "stand",
      sourceSession = "src-1"
    )

  private def recordCard(ledger: PendingAskLedger, req: InteractionRequest, q: String): IO[Unit] =
    ledger.record(PendingAskLedger.Entry.of(req, q))

  test("C3: record/remove write-through to db pending face (open row + decision row)"):
    openDbAt().flatMap { case (_, db) =>
      val path = os.temp.dir() / "pending-asks.json"
      val ledger = new PendingAskLedger(path, db = Some(db))
      for
        d <- Deferred[IO, Boolean]
        req = permCard("r-wt-1", d)
        _ <- recordCard(ledger, req, "允许执行该命令吗？")
        row1 <- db.foldState(PendingStream.Permission, "r-wt-1")
        _ = assertEquals(row1.map(_.state), Some(PendingRecord.StateOpen), "开单行应为 open")
        _ = assertEquals(row1.map(_.payloadDigest), Some(PendingRecord.digestOf(req.payload)),
          "digest 对拍 = payload 摘要（全文不入库）")
        _ <- ledger.remove("r-wt-1")
        row2 <- db.foldState(PendingStream.Permission, "r-wt-1")
        _ = assertEquals(row2.map(_.state), Some(PendingRecord.StateClosed), "销账 ⇒ 决议行 closed")
        _ = assertEquals(row2.flatMap(_.closeReason), Some("slot-closed"))
        // 原行字节不动（追加式闭合）：开单行仍在文件里。
        raw <- db.readPending(PendingStream.Permission)
        _ = raw match
          case ReadResult.Present(sr) => assert(sr.records.size >= 2, "开单+闭单两行同在（append 语义）")
          case other => fail(s"expected Present, got $other")
      yield ()
    }

  test("C3: restart keeps permission ticket reinstallable-and-answerable; ask expired-closed"):
    openDbAt().flatMap { case (_, db) =>
      val path = os.temp.dir() / "pending-asks.json"
      // ── 第一代进程：两张挂起单（permission + ask），无任何收口路径（模拟重启杀进程）。
      val ledger1 = new PendingAskLedger(path, db = Some(db))
      for
        d1 <- Deferred[IO, Boolean]
        _ <- recordCard(ledger1, permCard("r-perm-1", d1), "允许写文件吗？")
        askReq = InteractionRequest(
          requestId = "r-ask-1", kind = InteractionKind.AskUser,
          payload = Json.obj("type" -> Json.fromString("askUser"),
            "items" -> Json.arr(Json.obj("question" -> Json.fromString("选哪个方案?")))),
          reply = InteractionReply.AskUserReply(None),
          rootSessionId = "root-db", sourceAgent = "stand", sourceSession = "src-1")
        _ <- recordCard(ledger1, askReq, "选哪个方案?")
        // ── 「重启」：新 hub + 新台账实例（同根同 db）。
        system = nebflow.actor.ActorSystem("pending-restart")
        ledger2 = new PendingAskLedger(path, db = Some(db))
        hub <- system.spawn(InteractionHub(ledger = Some(ledger2)), "hub-restart")
        broadcasts <- Ref.of[IO, List[Json]](Nil)
        receipts <- Ref.of[IO, List[(String, UiMessage)]](Nil)
        report <- ledger2.bootReconcile(
          broadcast = j => broadcasts.update(_ :+ j),
          appendReceipt = (sid, msg) => receipts.update(_ :+ (sid -> msg)),
          retireAskTodo = _ => IO.unit
        )
        _ = assertEquals(report.dbReinstalled, 1, s"permission 单应重装 1 张（report=${report.summary}）")
        _ = assertEquals(report.dbAskExpired, 1, "ask 单应过期关闭 1 张")
        // ask：db 终态行（expired-by-restart）+ 广播帧 + V6 不变式。
        askRow <- db.foldState(PendingStream.Ask, "r-ask-1")
        _ = assertEquals(askRow.map(_.state), Some(PendingRecord.StateClosed))
        _ = assertEquals(askRow.flatMap(_.closeReason), Some(nebflow.db.Recovery.ExpiredCloseReason))
        _ = assertEquals(askRow.flatMap(_.closedBy), Some(nebflow.db.Recovery.ExpiredCloseBy))
        orphans <- db.orphanOpenAsks()
        _ = assertEquals(orphans, 0, "V6：恢复腿跑后孤儿 ask == 0")
        bc <- broadcasts.get
        _ = assert(bc.exists(_.hcursor.downField("requestId").as[String].toOption.contains("r-ask-1")),
          "ask 过期广播帧在场（expired-orphan 语义保持）")
        // permission：重装回 hub ⇒ 可答（走既有 Answered 收口链）。McpPermission/
        // AskUser 进全局快照（snapshotFrames 口径）；Permission 卡走答复链验证。
        _ <- IO.sleep(150.millis) // hub forkTurn 异步登记重装槽
        snap <- hub.?[List[Json]](reply => InteractionHubCommand.ListAllPendingAsks(reply))
        _ = assert(!snap.exists(_.hcursor.downField("requestId").as[String].toOption.contains("r-ask-1")),
          "已过期 ask 不在 pending 快照")
        _ <- hub ! InteractionHubCommand.Answered(
          InteractionAnswered(requestId = "r-perm-1", rootSessionId = "root-db",
            payload = Json.obj("approved" -> Json.fromBoolean(true))))
        _ <- IO.sleep(200.millis) // hub forkTurn 异步收口
        permRow <- db.foldState(PendingStream.Permission, "r-perm-1")
        _ = assertEquals(permRow.map(_.state), Some(PendingRecord.StateClosed),
          "重装的 permission 卡被答复 ⇒ db 闭单行（重装且可答闭环）")
        // 原槽 deferred 不被复活（数据面恢复不复活会话身份）——答复走的是重装卡
        // 的一次性 deferred（无人等待，随槽位收口自然弃用），故不断言 d1。
        _ <- system.stopAll
      yield ()
    }

  test("C3: corrupt pending doc — explicit refusal (quarantine + WARN), never silent-empty"):
    openDbAt().flatMap { case (root, db) =>
      val path = os.temp.dir() / "pending-asks.json"
      val ledger = new PendingAskLedger(path, db = Some(db))
      for
        d <- Deferred[IO, Boolean]
        _ <- recordCard(ledger, permCard("r-cp-1", d), "允许吗？")
        // 注入坏行（追加面撕裂之外的中间坏帧——直接写文件模拟盘上损坏）。
        _ <- IO.blocking {
          val f = root.resolve("db").resolve("pending").resolve("permission.jsonl")
          java.nio.file.Files.write(f, java.nio.file.Files.readAllBytes(f))
          val text = java.nio.file.Files.readString(f)
          java.nio.file.Files.writeString(f, text + "{\"seq\":99999,\"requestId\":\"GARBAGE-no-json\n")
        }
        plan <- db.recoverPendingD8().attempt
        _ = assert(plan.isLeft, "坏 pending 档 ⇒ 显式拒绝（DbError.StreamCorrupt），绝不静默空起步")
        q = Option(root.resolve("db").resolve("pending"))
          .map(d => java.nio.file.Files.list(d).toArray.map(_.toString).exists(_.contains(".corrupt-")))
          .getOrElse(false)
        _ = assert(q, "拒绝路径伴随隔离（.corrupt-<ts> 全字节保留）")
        // bootReconcile 全腿：db 面拒绝 ⇒ WARN + legacy 腿兜底（不炸 boot 链）。
        report <- ledger.bootReconcile(_ => IO.unit, (_, _) => IO.unit, _ => IO.unit)
        _ = assertEquals(report.dbReinstalled, 0, "db 面失败时 plan 归零（legacy 兜底语义）")
      yield ()
    }

end PendingDbWiringSpec
