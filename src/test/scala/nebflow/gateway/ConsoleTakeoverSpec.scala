package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

/**
 * 控制台单实例 · 接管注册表 spec（2026-10-27 批，设计稿
 * `reports/20261027_231456_console-single-instance/DESIGN.md` §10 A1–A8）。
 *
 * 驱动**真实注册表**（假发送缝收帧账），钉六条验收红线：
 * 二进一踢 / 刷新不自杀 / 断线宽限不误踢（含僵尸回收零帧）/ 重新接管反向踢 /
 * 僵尸回收不触发提示 / 总闸关闭=零漂移；另钉 Bearer 直通与帧形。
 */
class ConsoleTakeoverSpec extends CatsEffectSuite:

  private val logger = nebflow.shared.NebflowLogger.forName("test.console-takeover")

  /** 假发送缝：connId → 已收帧列表（序即送达序）。 */
  private def fixture(enabled: Boolean = true): IO[(ConsoleTakeover, Ref[IO, List[(String, Json)]])] =
    for sent <- Ref.of[IO, List[(String, Json)]](Nil)
      reg = new ConsoleTakeover(
        (connId, json) => sent.update(_.prepended((connId, json))),
        logger,
        enabled = enabled
      )
    yield (reg, sent)

  private def typesOf(sent: Ref[IO, List[(String, Json)]]): IO[List[(String, String)]] =
    sent.get.map(_.reverse.map { case (c, j) => c -> j.hcursor.downField("type").as[String].getOrElse("?") })

  private def framesTo(sent: Ref[IO, List[(String, Json)]], connId: String): IO[List[Json]] =
    sent.get.map(_.reverse.collect { case (c, j) if c == connId => j })

  private val sidA = "session-aaaa-1111"
  private val sidB = "session-bbbb-2222"
  private val sidC = "session-cccc-3333"

  // ── A1 二进一踢：B fresh claim ⇒ owner 翻位 + A 活跃连接收 takeover 帧 ──

  test("A1 second fresh session takes over: owner flips, old owner's live conns get takeover frame"):
    for (reg, sent) <- fixture()
      _ <- reg.onConnect(sidA, "conn-a1")
      _ <- reg.onConnect(sidB, "conn-b1")
      ownerNow <- reg.owner
      aFrames <- framesTo(sent, "conn-a1")
      types <- typesOf(sent)
    yield
      assertEquals(ownerNow, Some(sidB), "fresh claim 必胜（互斥权最后写入者胜）")
      assertEquals(aFrames.map(_.hcursor.downField("type").as[String].getOrElse("?")), List("takeover"))
      assert(aFrames.head.hcursor.downField("at").as[Long].isRight, "takeover 帧带 at 戳")
      assertEquals(types, List(("conn-a1", "takeover")), "唯一一帧 = 推给旧主连接；新实例侧零帧（它是接管方，不被踢）")

  // ── A2 刷新不自杀：同会话重连静默续位，零帧 ──

  test("A2 same-session reconnect (refresh) silently resumes: no frame, owner unchanged"):
    for (reg, sent) <- fixture()
      _ <- reg.onConnect(sidA, "conn-a1")
      _ <- reg.onConnect(sidA, "conn-a2") // 刷新后新连接（旧连接未及离场也无妨）
      ownerNow <- reg.owner
      types <- typesOf(sent)
    yield
      assertEquals(ownerNow, Some(sidA))
      assertEquals(types, Nil, "同会话新连接 = 静默续位，任何方向都零帧")

  // ── A3 断线宽限不误踢：owner 掉线不摘位；同会话重连零帧 ──

  test("A3 owner drop never yields: disconnect keeps ownership, resume is silent"):
    for (reg, sent) <- fixture()
      _ <- reg.onConnect(sidA, "conn-a1")
      _ <- reg.onDisconnect("conn-a1") // 掉线（含 #960 判死回收路径）
      ownerAfterDrop <- reg.owner
      typesAfterDrop <- typesOf(sent)
      _ <- reg.onConnect(sidA, "conn-a2") // 宽限内重连（同会话）
      ownerAfterResume <- reg.owner
      typesAfterResume <- typesOf(sent)
    yield
      assertEquals(ownerAfterDrop, Some(sidA), "掉线 ≠ 让位：owner 按会话持久")
      assertEquals(typesAfterDrop, Nil, "断线路径零出帧（僵尸回收不触发提示）")
      assertEquals(ownerAfterResume, Some(sidA))
      assertEquals(typesAfterResume, Nil, "同会话重连静默续位——抖动误踢结构上不可表达")

  // ── A4 重新接管反向踢 ──

  test("A4 retake flips ownership back and reverse-kicks the new owner"):
    for (reg, sent) <- fixture()
      _ <- reg.onConnect(sidA, "conn-a1")
      _ <- reg.onConnect(sidB, "conn-b1") // B 接管，A 落 superseded
      replies <- Ref.of[IO, List[Json]](Nil)
      _ <- reg.handleRetake("conn-a1", j => replies.update(_.prepended(j)))
      ownerNow <- reg.owner
      replyList <- replies.get.map(_.reverse)
      bFrames <- framesTo(sent, "conn-b1")
    yield
      assertEquals(ownerNow, Some(sidA), "retake ⇒ owner 翻回")
      assertEquals(
        replyList.map(_.hcursor.downField("ok").as[Boolean].getOrElse(false)),
        List(true),
        "takeoverResult ok=true"
      )
      assertEquals(
        bFrames.map(_.hcursor.downField("type").as[String].getOrElse("?")),
        List("takeover"),
        "反向踢：B 的活跃连接收 takeover 帧"
      )

  // ── A5 旧主回连不反抢：pastOwner 重连落 superseded（本连接收帧，owner 不动）──

  test("A5 past-owner reconnect does NOT auto-reclaim: it lands superseded, owner untouched"):
    for (reg, sent) <- fixture()
      _ <- reg.onConnect(sidA, "conn-a1")
      _ <- reg.onConnect(sidB, "conn-b1") // B 接管
      _ <- reg.onDisconnect("conn-a1")
      _ <- reg.onConnect(sidA, "conn-a2") // A 旧主回连（机器自动重连，无用户意图）
      ownerNow <- reg.owner
      a2Frames <- framesTo(sent, "conn-a2")
      b1Frames <- framesTo(sent, "conn-b1")
      _ <- reg.handleRetake("conn-a2", _ => IO.unit) // 回连方 retake 即回 active（被踢面的出路）
      ownerAfterRetake <- reg.owner
    yield
      assertEquals(ownerNow, Some(sidB), "回连不反抢：B 的所有权不动（防后台标签隐身夺权）")
      assertEquals(
        a2Frames.map(_.hcursor.downField("type").as[String].getOrElse("?")),
        List("takeover"),
        "回连方本连接收 superseded 通知（被踢面，一键 retake）"
      )
      assertEquals(b1Frames.map(_.hcursor.downField("type").as[String].getOrElse("?")), Nil, "B 零打扰")
      assertEquals(ownerAfterRetake, Some(sidA))

  // ── A6 僵尸回收不触发提示：回收只摘连接账、无任何帧 ──

  test("A6 zombie recycle (disconnect of every conn) emits zero frames and keeps ownership"):
    for (reg, sent) <- fixture()
      _ <- reg.onConnect(sidA, "conn-a1")
      _ <- reg.onConnect(sidA, "conn-a2")
      _ <- reg.onDisconnect("conn-a1") *> reg.onDisconnect("conn-a2") // 判死回收全场连接
      ownerNow <- reg.owner
      types <- typesOf(sent)
      count <- reg.connCount(sidA)
    yield
      assertEquals(ownerNow, Some(sidA), "全场掉线 owner 仍持久（takeover 唯一发射点 = claim/retake）")
      assertEquals(types, Nil)
      assertEquals(count, 0, "连接账已摘净")

  // ── A7 总闸关闭 = 现行为零漂移 ──

  test("A7 master switch off: registry is a full no-op (no claims, no frames)"):
    for (reg, sent) <- fixture(enabled = false)
      _ <- reg.onConnect(sidA, "conn-a1")
      _ <- reg.onConnect(sidB, "conn-b1")
      replies <- Ref.of[IO, List[Json]](Nil)
      _ <- reg.handleRetake("conn-a1", j => replies.update(_.prepended(j)))
      ownerNow <- reg.owner
      types <- typesOf(sent)
      replyList <- replies.get
    yield
      assertEquals(ownerNow, None, "总闸关闭不记账")
      assertEquals(types, Nil, "零帧")
      assertEquals(replyList, Nil, "retake 亦 no-op（路由侧关闭时根本不拦截，双保险）")

  // ── A8 Bearer 直通 + 帧形/判别函数钉 ──

  test("A8 unregistered conn (bearer rail bypass) retake is refused; frame shapes pinned"):
    for (reg, sent) <- fixture()
      _ <- reg.onConnect(sidA, "conn-a1")
      replies <- Ref.of[IO, List[Json]](Nil)
      _ <- reg.handleRetake("conn-ghost", j => replies.update(_.prepended(j))) // 未注册连接
      replyList <- replies.get.map(_.reverse)
    yield
      assertEquals(
        replyList.map(_.hcursor.downField("ok").as[Boolean].getOrElse(true)),
        List(false),
        "未注册连接 retake ⇒ ok:false"
      )
      // 帧形钉（协议面，前端/老客户端兼容面）
      val takeover = ConsoleTakeover.takeoverFrame(1234L)
      assertEquals(takeover.hcursor.downField("type").as[String].getOrElse("?"), "takeover")
      assertEquals(takeover.hcursor.downField("at").as[Long].getOrElse(0L), 1234L)
      val ok = ConsoleTakeover.resultFrame(ok = true)
      assertEquals(ok.hcursor.downField("type").as[String].getOrElse("?"), "takeoverResult")
      assertEquals(ok.hcursor.downField("ok").as[Boolean].getOrElse(false), true)
      // 判别函数：正型/异型/非 JSON
      assert(ConsoleTakeover.isRetakeFrame("""{"type":"takeoverRetake"}"""))
      assert(!ConsoleTakeover.isRetakeFrame("""{"type":"ping"}"""))
      assert(!ConsoleTakeover.isRetakeFrame("not-json"))

  // ── A9 fresh claim 对无活连接的旧主必胜（旧主不在场 = 推送尽力而为）──

  test("A9 fresh claim wins even when the incumbent has no live conn (best-effort push)"):
    for (reg, sent) <- fixture()
      _ <- reg.onConnect(sidA, "conn-a1")
      _ <- reg.onDisconnect("conn-a1") // 旧主已死（休眠/回收）
      _ <- reg.onConnect(sidC, "conn-c1") // 第三台进入
      ownerNow <- reg.owner
      types <- typesOf(sent)
    yield
      assertEquals(ownerNow, Some(sidC), "入口不被旧主僵尸挡住（第二台先弹「已在别处打开」= 语义反了）")
      assertEquals(types, Nil, "无活连接 ⇒ 零帧，旧主下次重连按 pastOwners 落被踢面（最终一致）")

  // ── A10 多标签同会话集体被踢 + retake 面向全会话广播 ──

  test("A10 takeover pushes reach every live conn of the displaced session"):
    for (reg, sent) <- fixture()
      _ <- reg.onConnect(sidA, "conn-a1")
      _ <- reg.onConnect(sidA, "conn-a2") // 同浏览器第二标签
      _ <- reg.onConnect(sidB, "conn-b1")
      aFrames <- framesTo(sent, "conn-a1")
      a2Frames <- framesTo(sent, "conn-a2")
    yield
      assertEquals(aFrames.map(_.hcursor.downField("type").as[String].getOrElse("?")), List("takeover"))
      assertEquals(a2Frames.map(_.hcursor.downField("type").as[String].getOrElse("?")), List("takeover"),
        "同会话全部活跃连接都收接管帧（模态在两个标签都弹）")

end ConsoleTakeoverSpec
