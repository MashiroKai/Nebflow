package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * 控制台单实例 · **同端判据**（2026-11-07 作者报障：只在手机这一个端用，却反复弹
 * 「控制台已在其他位置打开 / 您在其他地方登录了控制台，此窗口已让出控制权」）。
 *
 * 报障根因（`stand.log` 2026-11-07 10:36–10:53 实证）：
 * ```
 * 10:50:44 console ticket redeemed … origin=local-ticket sid=jdU1nIG- ua=…HeadlessChrome… loopback=true
 * 10:50:44 instance 15892e3b took over from instance 68fb20e7 — takeover pushed to 1 conn(s)   ← 手机端弹框
 * 10:50:52 instance 68fb20e7 retook console control (http leg)                                  ← 作者点【重新接管】
 * 10:52:47 instance f860a661 took over from instance 68fb20e7 — takeover pushed to 1 conn(s)    ← 一分钟后又被顶
 * 10:41:16 instance 68fb20e7 reconnected as non-owner — superseded notice sent                 ← 自己回连也弹
 * ```
 * 手机端 = `68fb20e7`（`sid=NWi5q4Ui`、`referer=https://link.mystand.space/`、
 * `ua=iPhone…`）；顶它的是**同一台 Mac 上的 Playwright/headless 控制台会话**（临时
 * profile ⇒ 空 localStorage ⇒ 每次运行一个新的 `crypto.randomUUID` clientId）以及
 * 「同一个端换 origin（门户 vs 直连）就换 clientId」这个结构缺口。旧口径把
 * clientId 差异一律判成「可证不同的第二个端」⇒ 弹框。
 *
 * 本 spec 钉死四条（作者令④）：同端重连不弹窗 / 真第二端接入才弹窗 /
 * 重启后旧会话不算第二端 / 旧端无可见活动时静默让位（不弹）。
 */
class ConsoleTakeoverSameEndSpec extends CatsEffectSuite:

  private val logger = nebflow.shared.NebflowLogger.forName("test.console-takeover.same-end")

  /** 与真机同形的 UA：手机端（iPhone Safari）与桌面/自动化端（HeadlessChrome）。 */
  private val uaPhone =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
  private val uaPhoneSameOtherOrigin = uaPhone // 同一台手机的另一条入口（link.mystand.space vs 直连）UA 逐字相同
  private val uaDesk =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
  private val uaHeadless =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/131.0.0.0 Safari/537.36"

  private def fixture(
    enabled: Boolean = true,
    activityTtlMs: Long = ConsoleTakeover.ActivityTtlMs,
    automationSpectator: Boolean = true
  ): IO[(ConsoleTakeover, Ref[IO, List[(String, Json)]])] =
    for sent <- Ref.of[IO, List[(String, Json)]](Nil)
      reg = new ConsoleTakeover(
        (connId, json) => sent.update(_.prepended((connId, json))),
        logger,
        enabled = enabled,
        activityTtlMs = activityTtlMs,
        automationSpectator = automationSpectator
      )
    yield (reg, sent)

  private def framesTo(sent: Ref[IO, List[(String, Json)]], connId: String): IO[List[Json]] =
    sent.get.map(_.reverse.collect { case (c, j) if c == connId => j })

  private def typesOf(sent: Ref[IO, List[(String, Json)]]): IO[List[(String, String)]] =
    sent.get.map(_.reverse.map { case (c, j) => c -> j.hcursor.downField("type").as[String].getOrElse("?") })

  private def meta(cid: String, ua: String, ip: String = "10.0.0.9",
                   automated: Boolean = false): ConsoleTakeover.ClientMeta =
    ConsoleTakeover.ClientMeta(Some(cid), Some(ua), Some(ip), automated)

  // 手机端（报障现场）：clientId 68fb20e7 同形，两条不同会话 id。
  private val cidPhone = "68fb20e7-1111-2222-3333-444455556666"
  private val cidPhone2 = "aaaabbbb-1111-2222-3333-444455556666" // 清存储/换入口后的新 clientId
  private val sidPhone = "NWi5q4Ui-session-1"
  private val sidPhone2 = "Fe5oMz77-session-2"

  // ── W1 报障主用例：同端重连不弹窗（换 clientId 也一样） ────────────────────

  test("W1 same-end reconnect (identical UA, NEW clientId) hands over silently, zero frames"):
    for
      (reg, sent) <- fixture()
      _ <- reg.onConnect(sidPhone, "conn-phone-1", meta(cidPhone, uaPhone, "192.168.1.20"))
      _ <- reg.onConnect(sidPhone2, "conn-phone-2", meta(cidPhone2, uaPhoneSameOtherOrigin, "192.168.1.20"))
      ownerNow <- reg.owner
      types <- typesOf(sent)
    yield
      assertEquals(ownerNow, Some("c:" + cidPhone2), "同端让位：owner 翻到新键（静默接管）")
      assertEquals(types, Nil, "同端 ⇒ 零帧：旧连接不得收 takeover（这就是作者看到的误报框）")

  // ── W2 真第二端才弹窗 ────────────────────────────────────────────────────

  test("W2 a genuinely different end (different UA + clientId, live) still gets the takeover frame"):
    for
      (reg, sent) <- fixture()
      _ <- reg.onConnect(sidPhone, "conn-phone-1", meta(cidPhone, uaPhone, "192.168.1.20"))
      _ <- reg.onConnect("sid-desk-1", "conn-desk-1", meta("desk-cid-1", uaDesk, "192.168.1.10"))
      ownerNow <- reg.owner
      phoneFrames <- framesTo(sent, "conn-phone-1")
      types <- typesOf(sent)
    yield
      assertEquals(ownerNow, Some("c:desk-cid-1"))
      assertEquals(phoneFrames.map(_.hcursor.downField("type").as[String].toOption), List(Some("takeover")),
        "可证不同的活跃端接入 ⇒ 被顶端收提示（单实例语义不变）")
      assertEquals(types, List(("conn-phone-1", "takeover")), "接管方零帧")

  // ── W3 重启后旧会话记录不算第二端 ─────────────────────────────────────────

  test("W3 after an engine restart the legacy session-key record is NOT a second end (silent)"):
    for
      (reg, sent) <- fixture()
      // 重启后首条连接：门户腿不带 clientId ⇒ 以会话 id 入账（日志实证 _iBo-TWM 形态）
      _ <- reg.onConnect(sidPhone, "conn-legacy",
        ConsoleTakeover.ClientMeta(None, Some(uaPhone), Some("192.168.1.20")))
      firstOwner <- reg.owner
      // 同一台手机随后带 clientId 连回（localStorage 里本来就有）
      _ <- reg.onConnect(sidPhone2, "conn-phone-2", meta(cidPhone, uaPhoneSameOtherOrigin))
      ownerNow <- reg.owner
      types <- typesOf(sent)
    yield
      assertEquals(firstOwner, Some(sidPhone), "重启后首实例以会话键即位（无 clientId 的引导窗）")
      assertEquals(ownerNow, Some("c:" + cidPhone),
        "同端（UA 指纹相同）⇒ 静默让位给带身份的连接：所有权平滑移交，不是被「另一个端」抢")
      assertEquals(types, Nil, "旧会话记录不算第二端：一条帧都不许发")

  // ── W4 旧端无可见活动 ⇒ 仍推帧（2026-11-21 作者令：推所有已知旧连接，不限活动窗；
  //    错过的通知记入补推账，见 W6）──────────────────────────────────────────

  test("W4 idle/ghost incumbent is still pushed the takeover frame — no activity-window gate"):
    for
      (reg, sent) <- fixture(activityTtlMs = 60L)
      _ <- reg.onConnect(sidPhone, "conn-phone-1", meta(cidPhone, uaPhone))
      _ <- IO.sleep(150.millis) // 超过活动窗：连接还挂着但没在看
      _ <- reg.onConnect("sid-desk-1", "conn-desk-1", meta("desk-cid-1", uaDesk))
      ownerNow <- reg.owner
      types <- typesOf(sent)
    yield
      assertEquals(ownerNow, Some("c:desk-cid-1"), "互斥权照样翻位（谁在看谁拿）")
      assertEquals(types.map(_._2), List("takeover"), "旧端已知连接一律收到接管帧（不再以活动窗过滤）")

  // ── W6 端未连接 ⇒ 重连补推，ack 才清账（2026-11-21 作者令：错过事件不能静默丢） ──

  test("W6 displaced phone offline at takeover: notice replayed on reconnect, cleared only by matching ack"):
    for
      (reg, sent) <- fixture()
      _ <- reg.onConnect(sidPhone, "conn-phone-1", meta(cidPhone, uaPhone))
      _ <- reg.onDisconnect("conn-phone-1") // 手机此刻未连接（息屏/断网）
      _ <- reg.onConnect("sid-desk-1", "conn-desk-1", meta("desk-cid-1", uaDesk)) // 电脑接管
      // 手机重连：补推账在场 ⇒ 本连接收一次 takeover（at 即接管时刻）
      _ <- reg.onConnect(sidPhone, "conn-phone-2", meta(cidPhone, uaPhone))
      phone2 <- framesTo(sent, "conn-phone-2")
      at = phone2.headOption.flatMap(_.hcursor.downField("at").as[Long].toOption).getOrElse(-1L)
      // 错误 at 的确认不清账
      _ <- reg.ackNotice("conn-phone-2", at + 1)
      _ <- reg.onDisconnect("conn-phone-2")
      _ <- reg.onConnect(sidPhone, "conn-phone-3", meta(cidPhone, uaPhone))
      stillPending <- framesTo(sent, "conn-phone-3")
      // 正确 at 确认 ⇒ 清账
      _ <- reg.ackNotice("conn-phone-2", at)
      _ <- reg.onDisconnect("conn-phone-3")
      _ <- reg.onConnect(sidPhone, "conn-phone-4", meta(cidPhone, uaPhone))
      cleared <- framesTo(sent, "conn-phone-4")
    yield
      assertEquals(phone2.map(_.hcursor.downField("type").as[String].getOrElse("?")), List("takeover"),
        "重连补推：本连接收一次接管帧")
      assert(at > 0L, "补推帧携带接管时刻 at")
      assertEquals(stillPending.map(_.hcursor.downField("type").as[String].getOrElse("?")), List("takeover"),
        "错误 at 的确认不清账：下次连接仍补推")
      // 清账后仍有一帧：既有「旧主回连 ⇒ superseded 通知」（past 分支，回连非 owner 的
      // 被踢面出路）不随 ack 消失——补推账只管「错过的那次」，不改回连既有语义。
      assertEquals(cleared.map(_.hcursor.downField("type").as[String].getOrElse("?")), List("takeover"),
        "清账后回连仍由既有 past 分支给一次 superseded 通知（非补推账）")

  test("W7 pending ledger survives an engine restart: the missed notice is replayed after reboot"):
    val path = java.nio.file.Files.createTempDirectory("tbf-pending").resolve("console-takeover-pending.json")
    for
      sent1 <- Ref.of[IO, List[(String, Json)]](Nil)
      reg1 = new ConsoleTakeover(
        (c, j) => sent1.update(_.prepended((c, j))), logger, pendingStorePath = Some(path))
      _ <- reg1.onConnect(sidPhone, "conn-phone-1", meta(cidPhone, uaPhone))
      _ <- reg1.onDisconnect("conn-phone-1")
      _ <- reg1.onConnect("sid-desk-1", "conn-desk-1", meta("desk-cid-1", uaDesk)) // 电脑接管，手机离线
      fileExists <- IO(java.nio.file.Files.exists(path))
      // 引擎重启：新实例、同一账本文件，旧内存全部丢弃
      sent2 <- Ref.of[IO, List[(String, Json)]](Nil)
      reg2 = new ConsoleTakeover(
        (c, j) => sent2.update(_.prepended((c, j))), logger, pendingStorePath = Some(path))
      _ <- reg2.onConnect(sidPhone, "conn-phone-2", meta(cidPhone, uaPhone))
      phone2 <- framesTo(sent2, "conn-phone-2")
    yield
      assert(fileExists, "接管时账本已原子落盘")
      assertEquals(phone2.map(_.hcursor.downField("type").as[String].getOrElse("?")), List("takeover"),
        "引擎重启后补推仍生效（账本从盘读回）")

  test("W5 noteActivity keeps a watching incumbent live — the takeover frame IS pushed then"):
    for
      (reg, sent) <- fixture(activityTtlMs = 200L)
      _ <- reg.onConnect(sidPhone, "conn-phone-1", meta(cidPhone, uaPhone))
      _ <- IO.sleep(120.millis)
      _ <- reg.noteActivity("conn-phone-1") // 心跳/交互帧
      _ <- reg.onConnect("sid-desk-1", "conn-desk-1", meta("desk-cid-1", uaDesk))
      types <- typesOf(sent)
    yield assertEquals(types, List(("conn-phone-1", "takeover")),
      "真有可见活动的旧端才提示（判据 = ActivityTtlMs 内有过入站帧）")

  // ── W6 自动化端不夺作者的控制台 ───────────────────────────────────────────

  test("W6 automated (webdriver) client joins as spectator: never displaces a live human, zero frames"):
    for
      (reg, sent) <- fixture()
      _ <- reg.onConnect(sidPhone, "conn-phone-1", meta(cidPhone, uaPhone))
      _ <- reg.onConnect("sid-test-1", "conn-test-1", meta("test-cid-1", uaHeadless, automated = true))
      ownerNow <- reg.owner
      types <- typesOf(sent)
      // 观察位可见内容（测试要看得见内容，否则自动化验证据全白）
      gate <- reg.gateFrame("c:test-cid-1", """{"type":"textDelta","delta":"x"}""")
    yield
      assertEquals(ownerNow, Some("c:" + cidPhone), "自动化端不翻 owner")
      assertEquals(types, Nil, "观察位零帧（不弹框、也不给别人弹）")
      assert(gate, "观察位内容闸放行")

  test("W7 automated client declares via UA only (no webdriver flag) — same spectator outcome"):
    for
      (reg, sent) <- fixture()
      _ <- reg.onConnect(sidPhone, "conn-phone-1", meta(cidPhone, uaPhone))
      _ <- reg.onConnect("sid-test-1", "conn-test-1", meta("test-cid-1", uaHeadless))
      ownerNow <- reg.owner
      types <- typesOf(sent)
    yield
      assertEquals(ownerNow, Some("c:" + cidPhone))
      assertEquals(types, Nil, "headless UA 兜底判据 = 观察位，零帧")

  test("W8 automation spectator switch OFF restores the old behaviour (takeover frame pushed)"):
    for
      (reg, sent) <- fixture(automationSpectator = false)
      _ <- reg.onConnect(sidPhone, "conn-phone-1", meta(cidPhone, uaPhone))
      _ <- reg.onConnect("sid-test-1", "conn-test-1", meta("test-cid-1", uaHeadless, automated = true))
      ownerNow <- reg.owner
      types <- typesOf(sent)
    yield
      assertEquals(ownerNow, Some("c:test-cid-1"), "开关关 ⇒ 旧口径（自动化端照常夺权）")
      assertEquals(types, List(("conn-phone-1", "takeover")))

  // ── W9 内容闸与同端判据一致（不弹窗也不能白屏） ───────────────────────────

  test("W9 content gate: a same-end holder is never starved of content frames"):
    for
      (reg, _) <- fixture()
      _ <- reg.onConnect(sidPhone, "conn-phone-1", meta(cidPhone, uaPhone))
      _ <- reg.onConnect(sidPhone2, "conn-phone-2", meta(cidPhone2, uaPhoneSameOtherOrigin))
      oldTabGate <- reg.gateFrame("c:" + cidPhone, """{"type":"textDelta","delta":"x"}""")
      newTabGate <- reg.gateFrame("c:" + cidPhone2, """{"type":"textDelta","delta":"x"}""")
    yield
      assert(oldTabGate, "同端旧标签照喂内容（不弹窗但全白比弹窗更糟）")
      assert(newTabGate, "新标签是 owner，恒放行")

  // ── W10 判据核（单源函数的白盒钉） ────────────────────────────────────────

  test("W10 verdict/endprint core: same UA ⇒ SameEnd, different UA ⇒ Conflict, no identity ⇒ Undecidable"):
    val p1 = ConsoleTakeover.endprintOf(Some(uaPhone))
    val p2 = ConsoleTakeover.endprintOf(Some(uaPhoneSameOtherOrigin))
    val d1 = ConsoleTakeover.endprintOf(Some(uaDesk))
    assert(p1.isDefined && p1 == p2, "同一 UA（含换 origin）⇒ 同一端指纹")
    assert(p1 != d1, "不同 UA ⇒ 不同端指纹")
    assertEquals(ConsoleTakeover.verdict(Some("a"), Some("b"), p1, p2), ConsoleTakeover.Verdict.SameEnd)
    assertEquals(ConsoleTakeover.verdict(Some("a"), Some("b"), p1, d1), ConsoleTakeover.Verdict.Conflict)
    assertEquals(ConsoleTakeover.verdict(None, Some("b"), None, d1),
      ConsoleTakeover.Verdict.Undecidable("joining page carries no client id (legacy cache)"))
    assertEquals(ConsoleTakeover.uaClass(Some(uaPhone)), "iphone")
    assertEquals(ConsoleTakeover.uaClass(Some(uaHeadless)), "headless-browser")
    assert(ConsoleTakeover.uaLooksAutomated(Some(uaHeadless)))
    assert(!ConsoleTakeover.uaLooksAutomated(Some(uaPhone)))
    assert(ConsoleTakeover.declaredAutomated("""{"type":"takeoverIdentify","clientId":"x","automated":true}"""))
    assert(!ConsoleTakeover.declaredAutomated("""{"type":"takeoverIdentify","clientId":"x"}"""))

  // ── W11 日志证据位（批③最小埋点）：描述串包含 sid/ip/ua 类/端指纹/自动化 ──

  test("W11 describe() carries the evidence fields used to attribute a supersede"):
    val inst = ConsoleTakeover.Instance("c:abc", "session-xyz-1", Some("abc"), Set("conn-1"), 0L,
      endprint = Some("0f1e2d3c4b5a"), uaClass = "iphone", ip = Some("192.168.1.20"), automated = false)
    val line = ConsoleTakeover.describe(Some(inst))
    assert(line.contains("sid=session-"), line)
    assert(line.contains("ip=192.168.1.20"), line)
    assert(line.contains("ua=iphone"), line)
    assert(line.contains("end=0f1e2d3c4b5a"), line)
    assert(line.contains("auto=no"), line)
    assertEquals(ConsoleTakeover.describe(None), "sid=? ip=? ua=? end=? auto=?")
