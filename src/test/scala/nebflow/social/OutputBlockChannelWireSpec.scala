package nebflow.social

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.bridge.BridgeContext
import nebflow.shared.SessionMeta

/**
 * Pipeline-level spec for the report-block channel wiring (report 块批，作者
 * 令 2026-11-20：report 块只对主会话生效).
 *
 * 🔴 NO NETWORK, NO CREDENTIAL: both live bridges are driven through their
 * real `onAgentEvent` event pipeline (textDelta / toolStart / done frames —
 * the production shapes) with constructor-injected RECORDING send seams, and
 * the assertions read the WIRE PAYLOADS.
 *
 * ==Scope parameterisation (author ruling 2026-11-20)==
 *
 * Every scenario registers TWICE — `root=true` (the user-visible face: the
 * wire carries ONLY the block-inner join) and `root=false` (every other
 * session: the wire carries the FULL RAW text — the previous behavior, no
 * hold, no swallow). The scope is constructor-injected
 * ([[WeixinIlinkBridgePlugin.rootSessionGate]] /
 * [[FeishuBridgePlugin.rootSessionGate]]), so no test reads machine config.
 */
class OutputBlockChannelWireSpec extends CatsEffectSuite:

  // ───────────────────────────── fixtures ─────────────────────────────

  /** Both scopes — each scenario below registers once per scope. */
  private val Gates = List(true, false)

  /** Minimal recording BridgeContext (routes rebuild reads listSessions). */
  private final class Ctx(sessions: List[SessionMeta]) extends BridgeContext:
    def injectMessage(
        sessionId: String,
        content: String,
        senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None
    ): IO[Unit] = IO.unit
    override def injectMessageWithMedia(
        sessionId: String,
        content: String,
        senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None,
        attachments: List[nebflow.bridge.BridgeAttachment] = Nil
    ): IO[Unit] = IO.unit
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(sessions)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  private def wxMeta(id: String, senderId: String): SessionMeta =
    SessionMeta(
      id = id,
      name = id,
      createdAt = 0L,
      updatedAt = 0L,
      hasUnread = false,
      bridges = Map(
        WeixinIlinkBridgePlugin.Name -> Json.obj("ilink_user_id" -> Json.fromString(senderId))
      )
    )

  private def fsMeta(id: String, chatId: String): SessionMeta =
    SessionMeta(
      id = id,
      name = id,
      createdAt = 0L,
      updatedAt = 0L,
      hasUnread = false,
      bridges = Map(FeishuBridgePlugin.Name -> Json.obj("chat_id" -> Json.fromString(chatId)))
    )

  /** Recording weixin text send seam — captures every (to, payload). */
  private def wxSeam(): (os.Path => WeixinIlinkBridgePlugin.Send, Ref[IO, List[(String, String)]]) =
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val factory: os.Path => WeixinIlinkBridgePlugin.Send = _ => (to, text) =>
      calls.update(_ :+ ((to, text))).as(WeixinOutboundAdapter.SendResult(true, true, Some("m1"), 200, "delivered"))
    (factory, calls)

  /** Recording feishu text send seam — captures every (receiveId, payload). */
  private def fsSeam(): (FeishuBridgePlugin.Send, Ref[IO, List[(String, String)]]) =
    val calls = Ref.unsafe[IO, List[(String, String)]](Nil)
    val fn: FeishuBridgePlugin.Send = (_appId, _appSecret, _region, receiveId, text) =>
      calls
        .update(_ :+ ((receiveId, text)))
        .as(FeishuChannel.SendResult(ok = true, Some("m1"), 200, "ok", "now", "chat_id"))
    (fn, calls)

  private def delta(sessionId: String, text: String): Json =
    Json.obj("type" -> "textDelta".asJson, "sessionId" -> sessionId.asJson, "delta" -> text.asJson)

  /** A textDelta carrying the emitting actor's stamped identity (the production
   *  frame shape: `AgentActor.stampSourceIdentity` adds `isRoot`). */
  private def deltaStamped(sessionId: String, text: String, isRoot: Boolean): Json =
    Json.obj(
      "type" -> "textDelta".asJson,
      "sessionId" -> sessionId.asJson,
      "delta" -> text.asJson,
      "isRoot" -> isRoot.asJson
    )

  private val toolStart = Json.obj("type" -> "toolStart".asJson)
  private def doneEv(sessionId: String): Json = Json.obj("type" -> "done".asJson, "sessionId" -> sessionId.asJson)

  // ── weixin iLink: the turn pipeline strips to the join (gate ON) / ships raw (gate OFF) ──

  Gates.foreach { gate =>
    test(s"WX-OB1 multi-block turn ⇒ gated: ONLY the blank-line block join; ungated: full raw passthrough (root=$gate)") {
      val (factory, calls) = wxSeam()
      val ctx = new Ctx(List(wxMeta("s1", "wx_alice")))
      val p = new WeixinIlinkBridgePlugin(
        os.temp.dir(prefix = "nb-ob-wx-"),
        pinnedAllowedIlinkUserIds = Some(Nil),
        rootSessionGate = Some(_ => gate),
        send = factory
      )
      val raw = "先看结论前的思考叙述 <report>A</report>\n中间的叙述不出站\n<report>  B  </report>"
      for
        _ <- p.start(ctx)
        _ <- p.onAgentEvent("s1", delta("s1", "先看结论前的思考叙述 "))
        _ <- p.onAgentEvent("s1", delta("s1", "<report>A</report>\n中间的叙述不出站\n"))
        _ <- p.onAgentEvent("s1", delta("s1", "<report>  B  </report>"))
        _ <- p.onAgentEvent("s1", doneEv("s1"))
        got <- calls.get
      yield
        if gate then assertEquals(got, List(("wx_alice", "A\n\nB")), "the wire carries only the block-inner join")
        else assertEquals(got, List(("wx_alice", raw)), "non-root session: the wire carries the full raw text, verbatim")
    }
  }

  Gates.foreach { gate =>
    test(s"WX-OB2 a mid-block segment flush: gated = HELD then ships the join once closed; ungated = raw passthrough (root=$gate)") {
      val (factory, calls) = wxSeam()
      val ctx = new Ctx(List(wxMeta("s1", "wx_alice")))
      val p = new WeixinIlinkBridgePlugin(
        os.temp.dir(prefix = "nb-ob-wx-"),
        pinnedAllowedIlinkUserIds = Some(Nil),
        rootSessionGate = Some(_ => gate),
        send = factory
      )
      for
        _ <- p.start(ctx)
        _ <- p.onAgentEvent("s1", delta("s1", "<report>未完"))
        _ <- p.onAgentEvent("s1", toolStart) // segment boundary lands INSIDE the open block
        _ <- p.onAgentEvent("s1", delta("s1", "段</report>"))
        _ <- p.onAgentEvent("s1", doneEv("s1"))
        got <- calls.get
        bufAtEnd <- p.pendingRef.get
      yield
        if gate then
          assertEquals(got, List(("wx_alice", "未完段")), "the completed block ships its inner content once")
          assertEquals(bufAtEnd.get("s1"), None, "the turn buffer is consumed at done")
        else
          // 全量原样：段边界照旧切段发送，标签字面随文出站，HOLD 旁路。
          assertEquals(
            got,
            List(("wx_alice", "<report>未完"), ("wx_alice", "段</report>")),
            "non-root session: raw passthrough per segment, no hold, tags verbatim"
          )
          assertEquals(bufAtEnd.get("s1"), None, "the turn buffer is consumed at done")
    }
  }

  Gates.foreach { gate =>
    test(s"WX-OB3 narration-only segments: gated = strip (narration gone, block ships); ungated = raw passthrough (root=$gate)") {
      val (factory, calls) = wxSeam()
      val ctx = new Ctx(List(wxMeta("s1", "wx_alice")))
      val p = new WeixinIlinkBridgePlugin(
        os.temp.dir(prefix = "nb-ob-wx-"),
        pinnedAllowedIlinkUserIds = Some(Nil),
        rootSessionGate = Some(_ => gate),
        send = factory
      )
      for
        _ <- p.start(ctx)
        _ <- p.onAgentEvent("s1", delta("s1", "这一段纯工具过程叙述，块外。"))
        _ <- p.onAgentEvent("s1", toolStart)
        _ <- p.onAgentEvent("s1", delta("s1", "<report>可见结论</report>"))
        _ <- p.onAgentEvent("s1", doneEv("s1"))
        got <- calls.get
      yield
        if gate then assertEquals(got, List(("wx_alice", "可见结论")), "narration never reaches the wire; the block does")
        else
          assertEquals(
            got,
            List(("wx_alice", "这一段纯工具过程叙述，块外。"), ("wx_alice", "<report>可见结论</report>")),
            "non-root session: every segment ships raw, narration included"
          )
    }
  }

  Gates.foreach { gate =>
    test(s"WX-OB4 a turn with NO output block: gated = NO text (never a fallback); ungated = full raw (root=$gate)") {
      val (factory, calls) = wxSeam()
      val ctx = new Ctx(List(wxMeta("s1", "wx_alice")))
      val p = new WeixinIlinkBridgePlugin(
        os.temp.dir(prefix = "nb-ob-wx-"),
        pinnedAllowedIlinkUserIds = Some(Nil),
        rootSessionGate = Some(_ => gate),
        send = factory
      )
      for
        _ <- p.start(ctx)
        _ <- p.onAgentEvent("s1", delta("s1", "旧式无块回复全文。"))
        _ <- p.onAgentEvent("s1", doneEv("s1"))
        got <- calls.get
      yield
        if gate then assertEquals(got, List.empty, "作者令：不要回退成发全文")
        else assertEquals(got, List(("wx_alice", "旧式无块回复全文。")), "non-root session: the wire carries the full raw text")
    }
  }

  // ── feishu: the same single source, the same semantics ───────────────

  Gates.foreach { gate =>
    test(s"FS-OB1 a feishu turn: gated = ONLY the block-inner join; ungated = full raw (root=$gate)") {
      val (fn, calls) = fsSeam()
      val ctx = new Ctx(List(fsMeta("s1", "oc_bound")))
      val p = new FeishuBridgePlugin(
        os.temp.dir(prefix = "nb-ob-fs-"),
        send = fn,
        pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec")),
        rootSessionGate = Some(_ => gate)
      )
      val raw = "思考叙述 <report>飞书可见内容</report> 尾部叙述"
      for
        _ <- p.rebuildRoutes(ctx)
        _ <- p.onAgentEvent("s1", delta("s1", raw))
        _ <- p.onAgentEvent("s1", doneEv("s1"))
        got <- calls.get
      yield
        if gate then assertEquals(got, List(("oc_bound", "飞书可见内容")), "the feishu wire payload is the block join alone")
        else assertEquals(got, List(("oc_bound", raw)), "non-root session: the feishu wire carries the full raw text")
    }
  }

  Gates.foreach { gate =>
    test(s"FS-OB2 the mid-block segment flush: gated = HELD then ships the join; ungated = raw passthrough (root=$gate)") {
      val (fn, calls) = fsSeam()
      val ctx = new Ctx(List(fsMeta("s1", "oc_bound")))
      val p = new FeishuBridgePlugin(
        os.temp.dir(prefix = "nb-ob-fs-"),
        send = fn,
        pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec")),
        rootSessionGate = Some(_ => gate)
      )
      for
        _ <- p.rebuildRoutes(ctx)
        _ <- p.onAgentEvent("s1", delta("s1", "<report>第一"))
        _ <- p.onAgentEvent("s1", toolStart)
        _ <- p.onAgentEvent("s1", delta("s1", "半</report>"))
        _ <- p.onAgentEvent("s1", doneEv("s1"))
        got <- calls.get
      yield
        if gate then assertEquals(got, List(("oc_bound", "第一半")), "the completed block ships once, inner content only")
        else
          assertEquals(
            got,
            List(("oc_bound", "<report>第一"), ("oc_bound", "半</report>")),
            "non-root session: raw passthrough per segment, no hold, tags verbatim"
          )
    }
  }

  Gates.foreach { gate =>
    test(s"FS-OB3 a feishu turn with NO output block: gated = NO text (never a fallback); ungated = full raw (root=$gate)") {
      val (fn, calls) = fsSeam()
      val ctx = new Ctx(List(fsMeta("s1", "oc_bound")))
      val p = new FeishuBridgePlugin(
        os.temp.dir(prefix = "nb-ob-fs-"),
        send = fn,
        pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec")),
        rootSessionGate = Some(_ => gate)
      )
      for
        _ <- p.rebuildRoutes(ctx)
        _ <- p.onAgentEvent("s1", delta("s1", "纯叙述回复。"))
        _ <- p.onAgentEvent("s1", doneEv("s1"))
        got <- calls.get
      yield
        if gate then assertEquals(got, List.empty, "作者令：不要回退成发全文")
        else assertEquals(got, List(("oc_bound", "纯叙述回复。")), "non-root session: the wire carries the full raw text")
    }
  }

  // ── §⑥ root 打标接通 + 渠道绑定过滤（作者令 2026-11-20）───────────────────
  //
  // 生产面 = **源头打标**：出站 flush 问的是「这一帧的发射会话是不是 root」
  // （`AgentActor.stampSourceIdentity` 落在帧上的 `isRoot`），不按窗口/渠道归属
  // 推断。下面两条走**真帧**（带 `isRoot` 键），证明打标一路通到出站 payload：
  //   - 同一条正文，帧上 `isRoot:true` ⇒ 只出块内；`isRoot:false` ⇒ 全文原样；
  //   - **未绑定渠道的会话**即使被标 root 也送不出去（绑定过滤仍在，不靠打标
  //     替代路由）——标记不是发送许可。

  test("⑥ stamped frames drive the face: isRoot=true ships the block join, isRoot=false ships the raw text") {
    val (fn, calls) = fsSeam()
    val ctx = new Ctx(List(fsMeta("s1", "oc_bound"), fsMeta("s2", "oc_other")))
    // 未注入常量缝 ⇒ 走生产路径：读帧上打标记忆。
    val p = new FeishuBridgePlugin(
      os.temp.dir(prefix = "nb-ob-fs-stamp-"),
      send = fn,
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec"))
    )
    val raw = "叙述 <report>可见正文</report> 尾述"
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s1", deltaStamped("s1", raw, isRoot = true))
      _ <- p.onAgentEvent("s1", doneEv("s1"))
      _ <- p.onAgentEvent("s2", deltaStamped("s2", raw, isRoot = false))
      _ <- p.onAgentEvent("s2", doneEv("s2"))
      got <- calls.get
    yield
      assertEquals(got, List(("oc_bound", "可见正文"), ("oc_other", raw)),
        "root 帧摘块内，非 root 帧原样直出——判据是帧上的源头标记")
  }

  test("⑥ an unbound session is never delivered, however it is stamped (binding filter still governs)") {
    val (fn, calls) = fsSeam()
    // 只绑定 s1；s2 有 root 打标但**没有渠道绑定**。
    val ctx = new Ctx(List(fsMeta("s1", "oc_bound")))
    val p = new FeishuBridgePlugin(
      os.temp.dir(prefix = "nb-ob-fs-unbound-"),
      send = fn,
      pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec"))
    )
    for
      _ <- p.rebuildRoutes(ctx)
      _ <- p.onAgentEvent("s2", deltaStamped("s2", "<report>不该出站</report>", isRoot = true))
      _ <- p.onAgentEvent("s2", doneEv("s2"))
      got <- calls.get
    yield assertEquals(got, List.empty, "打标不是发送许可：未绑定会话零出站，也不猜归属")
  }

  // ── 飞书/微信正文证据导出（`-Dnbreport.evidence=<file>`；不带该属性 = 零动作）──
  //
  // 判据已由上面 16 条断言钉死；本件只把**真出站 payload**（两条渠道 × root/非 root）
  // 原样落盘成证据文件供报告引用，不做第二套逻辑、不新增判定。
  test("evidence export: the exact channel payloads for root vs non-root (opt-in via -Dnbreport.evidence)") {
    sys.props.get("nbreport.evidence") match
      case None => IO.unit
      case Some(target) =>
        val raw = "思考叙述 <report>结论：缓存 bug 已修</report>\n工具过程叙述\n<report>动了 src/login.ts 一个文件</report> 尾述"
        def wxRun(isRoot: Boolean): IO[List[String]] =
          val (factory, calls) = wxSeam()
          val ctx = new Ctx(List(wxMeta("s1", "wx_alice")))
          val p = new WeixinIlinkBridgePlugin(
            os.temp.dir(prefix = "nb-ob-wx-evd-"),
            pinnedAllowedIlinkUserIds = Some(Nil),
            rootSessionGate = Some(_ => isRoot),
            send = factory
          )
          for
            _ <- p.start(ctx)
            _ <- p.onAgentEvent("s1", delta("s1", raw))
            _ <- p.onAgentEvent("s1", doneEv("s1"))
            got <- calls.get
          yield got.map(_._2)
        def fsRun(isRoot: Boolean): IO[List[String]] =
          val (fn, calls) = fsSeam()
          val ctx = new Ctx(List(fsMeta("s1", "oc_bound")))
          val p = new FeishuBridgePlugin(
            os.temp.dir(prefix = "nb-ob-fs-evd-"),
            send = fn,
            pinnedCreds = Some(FeishuCredentials.Credential("test-app", "test-secret", "spec")),
            rootSessionGate = Some(_ => isRoot)
          )
          for
            _ <- p.rebuildRoutes(ctx)
            _ <- p.onAgentEvent("s1", delta("s1", raw))
            _ <- p.onAgentEvent("s1", doneEv("s1"))
            got <- calls.get
          yield got.map(_._2)
        for
          wxRoot <- wxRun(true)
          wxPlain <- wxRun(false)
          fsRoot <- fsRun(true)
          fsPlain <- fsRun(false)
        yield
          val body = io.circe.Json.obj(
            "source_text" -> raw.asJson,
            "weixin_root" -> wxRoot.asJson,
            "weixin_nonroot" -> wxPlain.asJson,
            "feishu_root" -> fsRoot.asJson,
            "feishu_nonroot" -> fsPlain.asJson
          ).spaces2
          os.write.over(os.Path(target), body, createFolders = true)
          ()
  }

end OutputBlockChannelWireSpec
