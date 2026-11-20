package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite
import nebflow.db.{Db, DbConfig, ReadResult}
import nebflow.shared.NebflowLogger

import java.nio.file.{Files, Path}

/**
 * db 接线第一阶 C2 —— DispatchNotify 冷却 guard 档（设计 D-10 / 验收 V8）：
 *
 *  1. 冷却窗写穿 db guard 小档；「重启」（新 DispatchNotify 实例 + 同 db 根）后
 *     冷却不归零 ⇒ Suppress 不重发（#144 重发风暴家族的直接修复判据）。
 *  2. markSent（notifySentAt）跨重启仍把已通知节点挡在 redeliver 候选集外。
 *  3. 对照组：**无 db 句柄**（现状）重启后冷却归零 ⇒ 同一节点立即重发——证明
 *     抑制来自接线而非巧合，同时钉住「主链行为差异清单」的证据位。
 *  4. guard 档损坏 ⇒ 保守向（全部仍在冷却）+ 隔离留档（宁可少发不可风暴）。
 */
class DispatchNotifyGuardPersistenceSpec extends FunSuite:

  private val CooldownMs = 30L * 60L * 1000L // 30min——跨「重启」仍在未来
  private val WindowMs = 10L * 60L * 1000L

  private def tempDir(tag: String): Path = Files.createTempDirectory(s"dn-guard-$tag")

  private def seedFailed(store: FlowMapStore, ids: String*): IO[Unit] =
    store.mutate { s =>
      s.copy(nodes = s.nodes ++ ids.map { id =>
        id -> NodeDef(
          id = id,
          name = s"node-$id",
          agent = "general",
          task = Some(s"task for $id"),
          status = NodeLifecycle.Failed,
          result = Some(s"boom: $id"),
          out = List(OutEdge.root),
          createdAt = 1_700_000_000_000L,
          completedAt = Some(1_700_000_000_000L)
        )
      }.toMap)
    }.void

  private def mkNotify(
    store: FlowMapStore,
    ws: String,
    proj: String,
    triggered: Ref[IO, List[String]],
    guardDb: Option[nebflow.db.DbHandle]
  ): DispatchNotify =
    new DispatchNotify(
      store,
      ws,
      proj,
      escalate = (_: String, _: String) => IO.unit,
      emitUpdated = (_: NodeDef) => IO.unit,
      trigger = (t: String) => triggered.update(_ :+ t),
      failedBudgetMax = 100,
      failedWindowMs = WindowMs,
      failedCooldownMs = CooldownMs,
      failedWindowThreshold = 3,
      windowMs = 0L,
      guardDb = guardDb
    )

  /** 驱动单节点 failed 通知（snapshot 现读 → notifyTerminal 单一入口）。 */
  private def fail(dn: DispatchNotify, store: FlowMapStore, id: String): IO[Unit] =
    store.snapshot.map(_.nodes(id)).flatMap(n => dn.notifyTerminal(n, NotifyReason.Failed))

  test("C2: cooldown window survives restart via db guard doc — suppressed, no resend storm"):
    val dbRoot = tempDir("restart")
    val wsDir = tempDir("ws")
    val db = Db.open(DbConfig(dataRoot = dbRoot)).unsafeRunSync()
    try
      val io = for
        store <- FlowMapStore.open("guard-proj", wsDir.toString)
        triggered <- Ref.of[IO, List[String]](Nil)
        dn1 = mkNotify(store, wsDir.toString, "guard-proj", triggered, Some(db))
        _ <- seedFailed(store, "n-1", "n-2", "n-3", "n-4")
        // 三连败：#1/#2 Proceed+trigger+markSent；#3 触阈值 ⇒ CooldownOn（不触发）。
        _ <- fail(dn1, store, "n-1")
        _ <- fail(dn1, store, "n-2")
        _ <- fail(dn1, store, "n-3")
        t1 <- triggered.get
        _ = assert(clue(t1.size) == 2, s"exactly 2 triggers before cooldown (got ${t1.size})")
        // #4 同进程内：冷却 ⇒ Suppress（不触发不 markSent）。
        _ <- fail(dn1, store, "n-4")
        t2 <- triggered.get
        _ = assert(clue(t2.size) == 2, "in-process cooldown suppresses the 4th")
        // 「重启」：新实例（全新内存 Ref）+ 同一 db 句柄 ⇒ guard 档重水合。
        dn2 = mkNotify(store, wsDir.toString, "guard-proj", triggered, Some(db))
        _ <- fail(dn2, store, "n-4")
        t3 <- triggered.get
        _ = assert(clue(t3.size) == 2, "RESTART must NOT resend: cooldown recovered from db guard doc (V8 判据)")
        // guard 档在盘上且冷却在未来。
        doc <- db.readNotifyGuard(s"dispatch-notify:failed:guard-proj")
        _ = doc match
          case ReadResult.Present(d) =>
            assert(
              clue(d.cooldownUntil) > System.currentTimeMillis(),
              "persisted cooldownUntil must still be in the future"
            )
          case other => fail(s"expected Present guard doc, got $other")
      yield ()
      io.unsafeRunSync()
    finally db.close()

    end try

  test("C2: without db wiring the same restart RESENDS (control — 主链行为差异证据)"):
    val wsDir = tempDir("ws-nodb")
    val io = for
      store <- FlowMapStore.open("guard-nodb", wsDir.toString)
      triggered <- Ref.of[IO, List[String]](Nil)
      dn1 = mkNotify(store, wsDir.toString, "guard-nodb", triggered, None)
      _ <- seedFailed(store, "n-1", "n-2", "n-3", "n-4")
      _ <- fail(dn1, store, "n-1")
      _ <- fail(dn1, store, "n-2")
      _ <- fail(dn1, store, "n-3")
      t1 <- triggered.get
      _ = assert(clue(t1.size) == 2)
      // 现状（无 db）：重启 = 窗口态蒸发 ⇒ n-4 立即 Proceed 重发。
      dn2 = mkNotify(store, wsDir.toString, "guard-nodb", triggered, None)
      _ <- fail(dn2, store, "n-4")
      t2 <- triggered.get
      _ = assert(clue(t2.size) == 3, "control: in-memory guard resets on restart ⇒ resend (今天的既有行为)")
    yield ()
    io.unsafeRunSync()

  test("C2: markSent persists across restart — redeliver excludes already-notified nodes"):
    val dbRoot = tempDir("marksent")
    val wsDir = tempDir("ws2")
    val db = Db.open(DbConfig(dataRoot = dbRoot)).unsafeRunSync()
    try
      val io = for
        store <- FlowMapStore.open("mark-proj", wsDir.toString)
        triggered <- Ref.of[IO, List[String]](Nil)
        dn1 = mkNotify(store, wsDir.toString, "mark-proj", triggered, Some(db))
        _ <- seedFailed(store, "m-1", "m-2", "m-3", "m-4")
        _ <- fail(dn1, store, "m-1")
        _ <- fail(dn1, store, "m-2")
        _ <- fail(dn1, store, "m-3") // CooldownOn
        t1 <- triggered.get
        _ = assert(clue(t1.size) == 2)
        // 重启 + redeliver：m-1/m-2 已 markSent ⇒ 不入候选（计数只含 m-3/m-4 = 2）；
        // m-3/m-4 被恢复的冷却 Suppress ⇒ 无新触发。
        dn2 = mkNotify(store, wsDir.toString, "mark-proj", triggered, Some(db))
        candidates <- dn2.redeliver()
        t2 <- triggered.get
        _ = assert(clue(candidates) == 2, s"only unmarked nodes stay candidates (got $candidates)")
        _ = assert(clue(t2.size) == 2, "redeliver under recovered cooldown must not trigger")
      yield ()
      io.unsafeRunSync()
    finally db.close()

    end try

  test("C2: corrupt guard doc recovers conservative (all-cooling) + quarantined"):
    val dbRoot = tempDir("corrupt")
    val wsDir = tempDir("ws3")
    val db = Db.open(DbConfig(dataRoot = dbRoot)).unsafeRunSync()
    try
      val io = for
        store <- FlowMapStore.open("corrupt-proj", wsDir.toString)
        triggered <- Ref.of[IO, List[String]](Nil)
        _ <- seedFailed(store, "c-1")
        // 注入坏 guard 档（writeGuardDoc 的 V3 拒写 ⇒ 只能直写文件模拟盘上损坏）。
        _ =
          val guardDir = dbRoot.resolve("db").resolve("guard")
          Files.createDirectories(guardDir)
          Files.writeString(
            guardDir.resolve("notify-dispatch-notify-failed-corrupt-proj.json"),
            "{\"version\":1,\"source\":\"x\",\"failedAt\":[NOT-JSON"
          )
        dn = mkNotify(store, wsDir.toString, "corrupt-proj", triggered, Some(db))
        _ <- fail(dn, store, "c-1")
        t <- triggered.get
        _ = assert(clue(t.isEmpty), "corrupt guard ⇒ conservative all-cooling ⇒ suppressed")
        quarantined = Files
          .list(dbRoot.resolve("db").resolve("guard"))
          .toArray
          .map(_.toString)
          .count(_.contains(".corrupt-"))
        _ = assert(clue(quarantined) >= 1, "corrupt guard doc must be quarantined (.corrupt-<ts>)")
      yield ()
      io.unsafeRunSync()
    finally db.close()
    end try

end DispatchNotifyGuardPersistenceSpec
