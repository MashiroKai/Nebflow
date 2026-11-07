package nebflow.gateway.console

import nebflow.gateway.*

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite

/**
 * 控制台单实例 · **在带身份声明**（2026-11-03 作者报障「手机端和电脑端同时在线，
 * 怎么没有显示被挤掉」）。
 *
 * 报障根因（日志实证，`stand.log` 2026-11-03 10:07–10:30）：
 *   引擎重启到误报根修批（2026-11-02）之后的构建，实例身份只认握手 `?clientId=`；
 *   但控制台的真实交付路径上 query 不保证送到引擎——
 *   ```
 *   10:07:40 instance -BYzfuqQ claimed console control (first instance)
 *   10:08:14 instance XgG4kawC joined — no takeover (joining page carries no client id (legacy cache))
 *   10:17:30 instance 68fb20e7 retook console control (http leg)      ← 同一浏览器，HTTP 腿带 clientId
 *   10:17:32 instance CMwZ6YBb joined — no takeover (joining page …)  ← ws 腿却没有 clientId
 *   10:27:27 / 10:30:36 同上（后者 = 门户重座新会话）
 *   ```
 *   ⇒ 七条控制台连接**无一**带身份 ⇒ `verdict` 恒 `Undecidable` ⇒ 既不提示也不踢
 *   （同时 `gateFrame` 因身份不可证全放行）⇒ 单实例语义整体空转。
 *
 * 修法 = 身份第二条腿：ws open 后客户端在**数据面**重申
 * `{"type":"takeoverIdentify","clientId":…}`，服务端把该连接从退路键（会话 id）
 * 迁移到身份键并重跑裁决。本 spec 钉死：真抢占必踢（S1/S5/S8/S9）、自己人不误踢
 * （S2/S3/S4）、无身份空转（S6）、总闸关零漂移（S7）、帧形与判别函数（S6/S10）。
 */
class ConsoleTakeoverIdentifySpec extends CatsEffectSuite:

  private val logger = nebflow.shared.NebflowLogger.forName("test.console-takeover.identify")

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

  private def typesTo(sent: Ref[IO, List[(String, Json)]], connId: String): IO[List[String]] =
    framesTo(sent, connId).map(_.map(_.hcursor.downField("type").as[String].getOrElse("?")))

  private val sidDesk = "session-desktop-1111"
  private val sidPhone = "session-phone-2222"
  private val sidDesk2 = "session-desktop-9999" // 静默重座后的新会话 id
  private val cidDesk = "client-desktop-1111"
  private val cidPhone = "client-phone-2222"
  private val hDesk = "c:" + cidDesk
  private val hPhone = "c:" + cidPhone

  // ── S1 报障现场复原（红→绿的主用例）：手机进来自报身份 ⇒ 电脑被踢 + 收提示 ──

  test("S1 phone declares identity in-band after a query-less handshake ⇒ desktop is kicked with a takeover frame"):
    for
      (reg, sent) <- fixture()
      // 电脑先到：握手无 clientId（隧道脸丢 query）⇒ 退路键即位
      _ <- reg.onConnect(sidDesk, "conn-desk", None)
      ownerAfterHandshake <- reg.owner
      // 在带声明：同一连接改挂身份键，所有权随标签走（零帧）
      holderDesk <- reg.onIdentify(sidDesk, "conn-desk", Some(cidDesk))
      ownerAfterIdentify <- reg.owner
      framesAfterIdentify <- typesOf(sent)
      // 手机到：同样先落退路键（此时身份不可证 ⇒ 按设计零帧、不误踢）
      _ <- reg.onConnect(sidPhone, "conn-phone", None)
      ownerAfterJoin <- reg.owner
      framesAfterJoin <- typesOf(sent)
      // 手机在带声明 ⇒ 两个可证不同的实例 ⇒ 真抢占：电脑收 takeover 帧
      holderPhone <- reg.onIdentify(sidPhone, "conn-phone", Some(cidPhone))
      ownerFinal <- reg.owner
      deskFrames <- typesTo(sent, "conn-desk")
      phoneFrames <- typesTo(sent, "conn-phone")
      allTypes <- typesOf(sent)
    yield
      assertEquals(ownerAfterHandshake, Some(sidDesk), "握手无身份 ⇒ 退路键（会话 id）先接下控制权")
      assertEquals(holderDesk, Some(hDesk), "声明回执 = 本连接此后应使用的实例键")
      assertEquals(ownerAfterIdentify, Some(hDesk), "迁移不是身份变化：所有权随标签走（不得把 owner 留给已不存在的键）")
      assertEquals(framesAfterIdentify, Nil, "迁移本身零出帧（kick 由迁移后的重裁决对**旧主**判定）")
      assertEquals(ownerAfterJoin, Some(hDesk), "身份不可证的新到方不得反踢（假阴性可接受）")
      assertEquals(framesAfterJoin, Nil, "身份不可证 ⇒ 零提示（不再有「没人挤我也弹提示」）")
      assertEquals(holderPhone, Some(hPhone))
      assertEquals(ownerFinal, Some(hPhone), "最后写入者胜：手机拿到控制权")
      assertEquals(deskFrames, List("takeover"), "★ 被挤掉的电脑**必须**收到 takeover 帧（报障的直接修复点）")
      assertEquals(phoneFrames, Nil, "接管方零帧（它不是被踢的一方）")
      assertEquals(allTypes, List(("conn-desk", "takeover")), "全场唯一一帧 = 推给旧主")

  // ── S2 静默重座不再自踢（误报根修批的回归面，在带腿同样成立）──

  test("S2 silent reseat via in-band declaration (same clientId, NEW session id) stays silent"):
    for
      (reg, sent) <- fixture()
      _ <- reg.onConnect(sidDesk, "conn-desk", None)
      _ <- reg.onIdentify(sidDesk, "conn-desk", Some(cidDesk))
      _ <- reg.onDisconnect("conn-desk")
      // 会话过期 ⇒ 静默重座 ⇒ 新 sessionId 连回（握手仍无 query）
      _ <- reg.onConnect(sidDesk2, "conn-desk2", None)
      _ <- reg.onIdentify(sidDesk2, "conn-desk2", Some(cidDesk))
      ownerNow <- reg.owner
      types <- typesOf(sent)
    yield
      assertEquals(ownerNow, Some(hDesk), "同一浏览器换会话 = 同一实例（owner 不动）")
      assertEquals(types, Nil, "换会话零提示（旧口径在此连续自踢 19 次）")

  // ── S3 握手已带身份时，在带声明幂等（零重裁决、零帧）──

  test("S3 in-band declaration is a no-op when the handshake already carried the same clientId"):
    for
      (reg, sent) <- fixture()
      _ <- reg.onConnect(sidDesk, "conn-desk", Some(cidDesk)) // 直连脸：query 腿可用
      ownerBefore <- reg.owner
      holder <- reg.onIdentify(sidDesk, "conn-desk", Some(cidDesk))
      ownerAfter <- reg.owner
      types <- typesOf(sent)
    yield
      assertEquals(ownerBefore, Some(hDesk))
      assertEquals(holder, Some(hDesk))
      assertEquals(ownerAfter, Some(hDesk), "幂等：键没变 ⇒ 不跑第二次裁决")
      assertEquals(types, Nil, "零帧")

  // ── S4 同浏览器多标签：两条连接都自报身份 ⇒ 合并进同一实例账，零帧 ──

  test("S4 two tabs of one browser declaring the same identity merge into one instance account, zero frames"):
    for
      (reg, sent) <- fixture()
      _ <- reg.onConnect(sidDesk, "conn-desk-1", None)
      _ <- reg.onConnect(sidDesk, "conn-desk-2", None)
      _ <- reg.onIdentify(sidDesk, "conn-desk-1", Some(cidDesk))
      _ <- reg.onIdentify(sidDesk, "conn-desk-2", Some(cidDesk))
      ownerNow <- reg.owner
      conns <- reg.connCount(sidDesk)
      types <- typesOf(sent)
    yield
      assertEquals(ownerNow, Some(hDesk), "同一实例（同 clientId）")
      assertEquals(conns, 2, "两条连接的账都迁移到身份键上（多标签集体被踢的推送面）")
      assertEquals(types, Nil, "同浏览器多标签零帧")

  // ── S5 迁移后旧主被踢目标完整（多标签全会话送达）──

  test("S5 after migration the displaced instance's every live conn receives the takeover frame"):
    for
      (reg, sent) <- fixture()
      _ <- reg.onConnect(sidDesk, "conn-desk-1", None)
      _ <- reg.onConnect(sidDesk, "conn-desk-2", None)
      _ <- reg.onIdentify(sidDesk, "conn-desk-1", Some(cidDesk))
      _ <- reg.onIdentify(sidDesk, "conn-desk-2", Some(cidDesk))
      _ <- reg.onConnect(sidPhone, "conn-phone", None)
      _ <- reg.onIdentify(sidPhone, "conn-phone", Some(cidPhone))
      d1 <- typesTo(sent, "conn-desk-1")
      d2 <- typesTo(sent, "conn-desk-2")
      p <- typesTo(sent, "conn-phone")
    yield
      assertEquals(d1, List("takeover"), "标签 1 收接管帧")
      assertEquals(d2, List("takeover"), "标签 2 同收（模态在两个标签都弹）")
      assertEquals(p, Nil, "接管方零帧")

  // ── S6 无身份 / 坏帧：静默空转，绝不因坏体断连或误记账 ──

  test("S6 missing or malformed identity declarations are inert"):
    for
      (reg, sent) <- fixture()
      _ <- reg.onConnect(sidDesk, "conn-desk", None)
      holderNoId <- reg.onIdentify(sidDesk, "conn-desk", None)
      ownerNow <- reg.owner
      types <- typesOf(sent)
    yield
      assertEquals(holderNoId, Some(sidDesk), "无身份声明 ⇒ 回报当前（退路）键，账目零改动")
      assertEquals(ownerNow, Some(sidDesk), "owner 不动")
      assertEquals(types, Nil, "零帧")
      // 判别函数：正型/异型/非 JSON/超长 clientId
      assert(ConsoleTakeover.isIdentifyFrame("""{"type":"takeoverIdentify","clientId":"c1"}"""))
      assert(!ConsoleTakeover.isIdentifyFrame("""{"type":"takeoverRetake"}"""))
      assert(!ConsoleTakeover.isIdentifyFrame("""{"type":"identify"}"""), "不得与既有未知帧型混淆")
      assert(!ConsoleTakeover.isIdentifyFrame("not-json"))
      assertEquals(ConsoleTakeover.declaredClientId("""{"type":"takeoverIdentify","clientId":"  c1  "}"""), Some("c1"),
        "trim 后取用")
      assertEquals(ConsoleTakeover.declaredClientId("""{"type":"takeoverIdentify"}"""), None, "缺席 ⇒ None")
      assertEquals(
        ConsoleTakeover.declaredClientId("""{"type":"takeoverIdentify","clientId":""" + "x" * 200 + "}"),
        None,
        "超长 clientId 一律拒绝（与握手腿同档上限）"
      )
      assertEquals(ConsoleTakeover.declaredClientId(""), None)

  // ── S7 总闸关 = 全 no-op（零漂移）──

  test("S7 master switch off: in-band declaration is a full no-op"):
    for
      (reg, sent) <- fixture(enabled = false)
      holder <- reg.onIdentify(sidDesk, "conn-desk", Some(cidDesk))
      ownerNow <- reg.owner
      types <- typesOf(sent)
    yield
      assertEquals(holder, Some(hDesk), "总闸关 ⇒ 只回报键（闸侧直通，不记账）")
      assertEquals(ownerNow, None)
      assertEquals(types, Nil)

  // ── S8 pastOwners 随迁移走：旧主（无 query 握手）重连 + 自报身份 ⇒ 落被踢面不反抢 ──

  test("S8 past-owner reconnect that declares identity lands superseded, never auto-reclaims"):
    for
      (reg, sent) <- fixture()
      _ <- reg.onConnect(sidDesk, "conn-desk", None)
      _ <- reg.onIdentify(sidDesk, "conn-desk", Some(cidDesk))
      _ <- reg.onConnect(sidPhone, "conn-phone", None)
      _ <- reg.onIdentify(sidPhone, "conn-phone", Some(cidPhone)) // 手机接管，电脑落被踢面
      _ <- reg.onDisconnect("conn-desk")
      // 电脑重连（后台标签醒来；握手仍无 query）⇒ 自报同一身份
      _ <- reg.onConnect(sidDesk2, "conn-desk-2", None)
      _ <- reg.onIdentify(sidDesk2, "conn-desk-2", Some(cidDesk))
      ownerNow <- reg.owner
      deskFrames <- typesTo(sent, "conn-desk-2")
      phoneFrames <- typesTo(sent, "conn-phone")
    yield
      assertEquals(ownerNow, Some(hPhone), "回连不反抢：owner 仍是手机")
      assertEquals(deskFrames, List("takeover"), "回连方收 superseded 通知（被踢面，一键 retake 是出路）")
      assertEquals(phoneFrames, Nil, "owner 零打扰")

  // ── S9 内容帧闸跟着身份换轨（迁移后 superseded 断喂成立）──

  test("S9 content gate follows the declared identity (superseded instance stops receiving data frames)"):
    for
      (reg, _) <- fixture()
      _ <- reg.onConnect(sidDesk, "conn-desk", None)
      deskHolder <- reg.onIdentify(sidDesk, "conn-desk", Some(cidDesk))
      _ <- reg.onConnect(sidPhone, "conn-phone", None)
      phoneHolder <- reg.onIdentify(sidPhone, "conn-phone", Some(cidPhone))
      deskMsg <- reg.gateFrame(deskHolder.get, """{"type":"textDelta","text":"secret"}""")
      deskTake <- reg.gateFrame(deskHolder.get, """{"type":"takeover","at":1}""")
      phoneMsg <- reg.gateFrame(phoneHolder.get, """{"type":"textDelta","text":"secret"}""")
    yield
      assert(!deskMsg, "被顶出实例（身份可证不同）断喂内容帧（隐私面）")
      assert(deskTake, "接管协议帧放行（模态重弹/回执可达）")
      assert(phoneMsg, "owner 全放行")

  // ── S10 HTTP 腿（重新接管）与在带身份同键：迁移后 reload 不再自我判冲突 ──

  test("S10 http-leg retake keyed by the declared identity flips ownership without self-conflict"):
    for
      (reg, sent) <- fixture()
      _ <- reg.onConnect(sidDesk, "conn-desk", None)
      _ <- reg.onIdentify(sidDesk, "conn-desk", Some(cidDesk))
      _ <- reg.onConnect(sidPhone, "conn-phone", None)
      _ <- reg.onIdentify(sidPhone, "conn-phone", Some(cidPhone)) // 手机接管
      // 电脑点【重新接管】：HTTP 腿带同一个 clientId ⇒ 与在带腿同键
      outcome <- reg.retakeBySession(sidDesk, Some(cidDesk))
      ownerNow <- reg.owner
      phoneFrames <- typesTo(sent, "conn-phone")
    yield
      assertEquals(ownerNow, Some(hDesk), "所有权翻回")
      assert(outcome == ConsoleTakeover.RetakeOutcome.Ok(1), s"反向踢送达 1 连接（实际 $outcome）")
      assertEquals(phoneFrames, List("takeover"), "手机被反向踢")
      assertEquals(ConsoleTakeover.identifyFrame("abc").hcursor.downField("type").as[String].toOption,
        Some("takeoverIdentify"), "帧形由伴生对象单点钉（前端哨兵按同一字面量比对）")

end ConsoleTakeoverIdentifySpec
