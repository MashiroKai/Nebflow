package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

/** devoscfix 批（2026-09-17）· F-A 会话重交换条件收窄的**双向钉**。
  *
  * 缺陷形态（诊断 H-1 结构放大链，`.nebflow/reports/20260917_devosc-diag.md` §1/§2）：
  * `discover` 对**任何** `Left` 都置空 token + 重做整会话交换 ⇒ 服务端「同设备一活
  * 会话」kick 旧会话 + 该路由显式拆隧道 + 广播 offline ⇒ 隧道以新 token 重连注册 +
  * 广播 online ⇒ **一次网络抖动 = 一对「设备 removed/added」**（实测耦合 ≤2s）。
  *
  * 验收（作者三答① / P1）：
  *  - (a) **传输类**失败（超时 / connect 超时 / 非认证非 2xx）⇒ 会话交换次数 **0**、
  *    token **未**置空（改前 = 1 / 已置空 ⇒ 本 spec 在基线上必红）；
  *  - (b) **401 / 403 令牌拒收** ⇒ 仍重做会话交换（改前改后一致，不回归）。
  *
  * 测法：真实代码路径 —— `NeblinkClient` 子类只替换 [[NeblinkClient.sendRequest]]
  * 这一个既有测试桩覆写点（与 `NeblinkClientReloginSpec` 同手法），`login` /
  * `discover` / `heartbeat` 全部走**未改动的产品代码**。
  */
class NeblinkTransportFailureSessionRetentionSpec extends FunSuite:

  private val cfg = NeblinkServerConfig(
    url = "http://127.0.0.1:1", // never contacted — the transport is stubbed
    networkId = "net",
    secret = "s",
    deviceToken = Some("device-token")
  )

  private val LoginOk   = """{"token":"tok-1","networkId":"net","deviceId":"dev","peers":[]}"""
  private val LoginOk2  = """{"token":"tok-2","networkId":"net","deviceId":"dev","peers":[]}"""
  private val SessionEp = "/api/device/session"
  private val LoginEp   = "/api/device/login"
  private val HbEp      = "/api/device/heartbeat"

  /** Real NeblinkClient + canned per-URL transport; every request is recorded so
    * the session-exchange site is countable (that count IS the defect signal:
    * >1 exchange per heartbeat failure is what kicks the session server-side). */
  private class Stub:
    var calls = List.empty[String]
    var reply: (String, String) => Either[String, String] = (_, _) => Left("unstubbed")

    val client = new NeblinkClient(cfg, serverPort = 1):
      override protected def sendRequest(
        method: String,
        url: String,
        body: String,
        token: Option[String]
      ): IO[Either[String, String]] =
        IO { calls = calls :+ url } *> IO.pure(reply(method, url))

    def exchanges: Int = calls.count(u => u.contains(SessionEp) || u.contains(LoginEp))
    def heartbeats: Int = calls.count(_.contains(HbEp))
    def token: Option[String] = client.currentSessionToken

  /** Seed a live session through the REAL login path (no field poking). */
  private def seed(s: Stub): Unit =
    s.reply = (_, _) => Right(LoginOk)
    assertEquals(
      s.client.login("dev", "name", "macos", List(NeblinkEndpoint("10.0.0.5", 1, "lan"))).unsafeRunSync(),
      Right(Nil)
    )
    assertEquals(s.token, Some("tok-1"), "seed login must establish the session")
    assertEquals(s.exchanges, 1)

  private def discover(s: Stub): Either[String, List[NeblinkPeerInfo]] =
    s.client.discover("dev", "name", "macos", Nil).unsafeRunSync()

  // ===== P1(a) 改前红 / 改后绿：传输类失败不再触发整会话重交换 =====

  test("P1(a): 一次传输类心跳失败 ⇒ 会话交换 0 次、token 保持（改前：1 次 + token 置空）") {
    val s = new Stub
    seed(s) // exchanges = 1
    // the network stalls: every request now fails as a transport-class failure
    s.reply = (_, _) => Left("request timed out")

    val out = discover(s)

    assertEquals(out, Left("request timed out"), "discover 仍以 Left 上报失败（返值语义不变）")
    assertEquals(s.heartbeats, 1, "恰一次心跳尝试（不新增重试腿）")
    assertEquals(
      s.exchanges,
      1,
      "🔴 传输失败不得重做会话交换（旧行为 = 2 ⇒ 服务端 kick + 拆隧道 + offline/online 一对）"
    )
    assertEquals(s.token, Some("tok-1"), "🔴 token 必须保持 —— 置空正是『无会话窗』与重登的入口")
  }

  test("P1(a): 其它传输类形态（404 / 5xx / connect 超时 / 连接复位）同样保持 token") {
    // 全部取自生产日志实测原文（诊断证据 01；404 计数 8、connect 超时 25、超时 119）
    val transportErrors = List(
      """HTTP 404: {"error":"not found"}""",
      "HTTP 500: internal",
      "HTTP connect timed out",
      "request timed out",
      "Connection reset by peer"
    )
    transportErrors.foreach { err =>
      val s = new Stub
      seed(s)
      s.reply = (_, _) => Left(err)
      assertEquals(discover(s), Left(err))
      assertEquals(s.exchanges, 1, s"[$err] 非认证类失败不得重做会话交换")
      assertEquals(s.token, Some("tok-1"), s"[$err] 必须保持 token")
    }
  }

  // ===== P1(b) 改前改后一致：认证类失败仍重做会话交换 =====

  test("P1(b): 401 ⇒ 仍置空 token + 重做会话交换（一次），并换上服务端新 token") {
    val s = new Stub
    seed(s)
    s.reply = (_, url) =>
      if url.contains(HbEp) then Left("HTTP 401: unauthorized") else Right(LoginOk2)

    val out = discover(s)

    assertEquals(out, Right(Nil))
    assertEquals(s.heartbeats, 1)
    assertEquals(s.exchanges, 2, "401 ⇒ 恰一次重做会话交换（token 轮换）")
    assertEquals(s.token, Some("tok-2"), "必须换上服务端新 token")
  }

  test("P1(b): 403 令牌拒收原文 ⇒ 重做会话交换，与 401 同路") {
    val s = new Stub
    seed(s)
    s.reply = (_, url) =>
      if url.contains(HbEp) then Left("""HTTP 403: {"error":"Invalid or expired token"}""")
      else Right(LoginOk2)

    assertEquals(discover(s), Right(Nil))
    assertEquals(s.exchanges, 2, "403 令牌拒收 ⇒ 恰一次重做会话交换")
    assertEquals(s.token, Some("tok-2"))
  }

  test("P1(b)/边界: 业务 403（体里无 token）**不算**认证类 ⇒ 保持 token") {
    val s = new Stub
    seed(s)
    // 既有语义（sessionRecoverable 注释自陈）：只有真令牌拒收才准入重登 ——
    // 重登会以「同设备一活会话」踢掉自己的旧会话，业务 403 不该付这个代价。
    s.reply = (_, url) =>
      if url.contains(HbEp) then Left("""HTTP 403: {"error":"not_friend"}""") else Right(LoginOk2)

    assertEquals(discover(s), Left("""HTTP 403: {"error":"not_friend"}"""))
    assertEquals(s.exchanges, 1, "业务 403 不得触发会话交换")
    assertEquals(s.token, Some("tok-1"), "业务 403 必须保持 token")
  }

  // ===== 分类判据表（防日后回归；与实现处注释同源） =====

  test("分类判据表: sessionRecoverable 只放行 401 与令牌型 403") {
    val recoverable = List(
      "HTTP 401: unauthorized",
      "HTTP 401",
      """HTTP 403: {"error":"Invalid or expired token"}""",
      "HTTP 403: Missing or invalid token"
    )
    val transport = List(
      "request timed out",
      "HTTP connect timed out",
      "Connection reset by peer",
      """HTTP 404: {"error":"not found"}""",
      "HTTP 500: boom",
      """HTTP 403: {"error":"not_friend"}""",
      """HTTP 403: {"error":"not_blocker"}""",
      NeblinkClient.KickParkedAutoLoginRefusal
    )
    recoverable.foreach(e => assert(NeblinkClient.sessionRecoverable(e), s"[$e] 应属认证类（可重登）"))
    transport.foreach(e => assert(!NeblinkClient.sessionRecoverable(e), s"[$e] 应属传输/业务类（保持 token）"))
  }

end NeblinkTransportFailureSessionRetentionSpec
