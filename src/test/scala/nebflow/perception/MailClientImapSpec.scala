package nebflow.perception

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.net.{InetAddress, ServerSocket, Socket}
import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IMAP 判定 spec（报障 2026-10-28：「连接邮箱」把 SELECT 的成功响应当错误
 * 整段倾倒并 take(160) 半截截断）。
 *
 * 三面：
 *   1. 成功判定——SELECT/SEARCH/FETCH 响应以 untagged 数据行开头、tagged
 *      完成行收尾（RFC 3501 §7），完成行 OK ⇒ Right（夹具 = 截图同形状：
 *      `* 4497 EXISTS / * 0 RECENT / * OK [UNSEEN 429] / …`）；
 *   2. 错误判定——tagged NO/BAD / 超时 / 断连才走错误路径，错误串人类可读
 *      （raw untagged 数据不进错误串，UI 不再倾倒）；
 *   3. 截断防御——流被截断（无完成行）⇒ 防御句；readableConnectError 输出
 *      恒单行且擦除授权码。
 *
 * 纪律：夹具全部 mock 值，零真实账号/授权码；本地 mock 服务器走 loopback
 * 明文 socket（ImapSession 构造形参已放宽为 Socket，生产腿不变）。
 */
class MailClientImapSpec extends FunSuite:

  // ── 夹具：截图同形状的 QQ SELECT 成功响应（数值即截图值）────────────
  private val qqSelectOk: String =
    "* 4497 EXISTS\r\n" +
      "* 0 RECENT\r\n" +
      "* OK [UNSEEN 429]\r\n" +
      "* OK [UIDVALIDITY 1540030357] UID validity status\r\n" +
      "* OK [UIDNEXT 5207] Predicted next UID\r\n" +
      "* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)\r\n" +
      "a2 OK [READ-WRITE] SELECT completed\r\n"

  // 截图截断复现：旧代码 err.take(160) 恰好切在 `* FLAGS (\Answered \Fla`
  // （15+12+19+51+40+23 = 160，\r\n 各计 2）——钉住截断来源是后端，防回归。
  test("evidence: 旧错误路径 take(160) 的切点 = 截图半截文本（截断来源在后端）") {
    val cut = qqSelectOk.take(160)
    assert(cut.startsWith("* 4497 EXISTS"))
    assert(cut.endsWith("* FLAGS (\\Answered \\Fla"))
  }

  // ════════ ① 成功判定（纯 imapVerdict）════════

  test("imapVerdict: SELECT 成功（untagged 序列 + tagged OK 收尾）⇒ Right——旧版 startsWith 首行误判回归钉") {
    val v = MailClient.imapVerdict("a2", qqSelectOk)
    assert(v.isRight, "SELECT 成功响应被判错——imapVerdict 未锚定 tagged 完成行")
    // 旧判据（resp.startsWith("a2 OK")）对同形状恒 false（bug 实证）：
    assert(!qqSelectOk.startsWith("a2 OK"))
  }

  test("imapVerdict: LOGIN 带前置 untagged CAPABILITY 也判 Right") {
    val resp = "* CAPABILITY IMAP4rev1 UIDPLUS\r\na1 OK Logged in\r\n"
    assert(MailClient.imapVerdict("a1", resp).isRight)
  }

  test("imapVerdict: 单行 tagged OK（LOGIN 形状）判 Right") {
    assert(MailClient.imapVerdict("a1", "a1 OK Authenticated\r\n").isRight)
  }

  // ════════ ② 错误判定 ════════

  test("imapVerdict: tagged NO ⇒ Left 可读、不含 untagged raw 数据") {
    val v = MailClient.imapVerdict("a2", "* 4497 EXISTS\r\na2 NO SELECT failed\r\n")
    val err = v.left.toOption.get
    assert(err.contains("NO"))
    assert(err.contains("SELECT failed"))
    assert(!err.contains("4497"), "错误串不得夹带 untagged 原始数据")
    assert(!err.contains("\r\n"))
  }

  test("imapVerdict: tagged NO [AUTHENTICATIONFAILED] ⇒ 认证失败可辨（供 connectAccount 分类）") {
    val v = MailClient.imapVerdict("a1", "a1 NO [AUTHENTICATIONFAILED] LOGIN Login error or password error\r\n")
    val err = v.left.toOption.get
    assert(err.contains("AUTHENTICATIONFAILED"))
    assert(err.contains("AUTH"))
  }

  test("imapVerdict: tagged BAD ⇒ Left 可读") {
    val v = MailClient.imapVerdict("a2", "a2 BAD Command not supported\r\n")
    val err = v.left.toOption.get
    assert(err.startsWith("IMAP BAD"))
    assert(!err.contains("\r\n"))
  }

  test("imapVerdict: 病态超长完成行截到 200 字符（截断防御）") {
    val long = "x" * 500
    val v = MailClient.imapVerdict("a1", s"a1 NO $long\r\n")
    assert(v.left.toOption.get.length <= 210)
  }

  test("imapVerdict: 无完成行（流截断）⇒ 防御句，不倾倒 raw") {
    val v = MailClient.imapVerdict("a2", "* 4497 EXISTS\r\n* 0 RECENT\r\n")
    val err = v.left.toOption.get
    assert(err.contains("不完整"))
    assert(!err.contains("4497"))
  }

  // ════════ 统计解析（EXISTS/UNSEEN/UIDVALIDITY）════════

  test("InboxStats: 截图同形状 SELECT 响应 → 4497 封 / 429 未读 / UIDVALIDITY 1540030357") {
    val s = MailClient.InboxStats.fromSelectResponse(qqSelectOk)
    assertEquals(s.exists, 4497)
    assertEquals(s.unseen, Some(429))
    assertEquals(s.uidValidity, Some(1540030357L))
  }

  test("InboxStats: 缺 UNSEEN/UIDVALIDITY 的最小响应容忍（None）") {
    val s = MailClient.InboxStats.fromSelectResponse("* 0 EXISTS\r\na2 OK SELECT completed\r\n")
    assertEquals(s.exists, 0)
    assertEquals(s.unseen, None)
    assertEquals(s.uidValidity, None)
  }

  // ════════ 会话全链路（本地 mock IMAP，loopback 明文）════════

  /**
   * Sequential scripted IMAP server: greeting, then one scripted reply per command line.
   * Reply conventions: "" = 保持静默（客户端撞 SoTimeout）；null = 直接断连。
   */
  private final class ScriptedImap(greeting: String, replies: Seq[String => String]):
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress)
    val port: Int = server.getLocalPort
    private val stopped = AtomicBoolean(false)

    private val thread = new Thread(() =>
      try
        val conn = server.accept()
        try
          val out = PrintStream(java.io.BufferedOutputStream(conn.getOutputStream), true, "UTF-8")
          out.print(greeting + "\r\n"); out.flush()
          val reader = BufferedReader(InputStreamReader(conn.getInputStream, "UTF-8"))
          val it = replies.iterator
          var alive = true
          while alive do
            val line = reader.readLine()
            if line == null || !it.hasNext then alive = false
            else
              it.next()(line) match
                case "" => () // 静默
                case null => alive = false // 断连
                case s => out.print(s); out.flush()
        finally
          try conn.close()
          catch case _: Throwable => ()
        end try
      catch case _: Throwable => ()
    )
    thread.setDaemon(true)
    thread.start()

    def stop(): Unit =
      stopped.set(true)
      try server.close()
      catch case _: Throwable => ()

  end ScriptedImap

  private def withSession(server: ScriptedImap, soTimeoutMs: Int = 5000)(
    f: MailClient.ImapSession => Unit
  ): Unit =
    val socket = new Socket(InetAddress.getLoopbackAddress, server.port)
    socket.setSoTimeout(soTimeoutMs)
    val session = new MailClient.ImapSession(socket)
    try
      session.readGreeting() match
        case Left(e) => fail(s"greeting rejected: $e")
        case Right(_) => ()
      f(session)
    finally session.close()
    server.stop()

  private val okLogin: String => String = _ => "a1 OK Authenticated\r\n"

  test("session: QQ 形状连接全链路——LOGIN → SELECT 成功 → 统计 4497/429") {
    val server = ScriptedImap(
      "* OK QQIMAP mock ready",
      List(
        okLogin,
        _ => qqSelectOk
      )
    )
    withSession(server) { s =>
      assert(s.login("mock-user@qq.com", "mock-auth-code-not-real").isRight)
      val stats = s.selectInbox()
      assert(stats.isRight, s"selectInbox 仍判错: ${stats.left.toOption.get}")
      assertEquals(stats.toOption.get.exists, 4497)
      assertEquals(stats.toOption.get.unseen, Some(429))
    }
  }

  test("session: UID SEARCH + UID FETCH（带 {n} 字面量）在 untagged 序列后判 OK") {
    val body17 = "From: a@b.c\r\n\r\nhi" // 17 bytes
    val server = ScriptedImap(
      "* OK mock ready",
      List(
        okLogin,
        _ => qqSelectOk,
        _ => "* SEARCH 12\r\na3 OK SEARCH completed\r\n",
        _ => s"* 1 FETCH (BODY[] {17}\r\n$body17\r\n)\r\na4 OK FETCH completed\r\n"
      )
    )
    withSession(server) { s =>
      assert(s.login("mock-user@qq.com", "mock-auth-code-not-real").isRight)
      assert(s.selectInbox().isRight)
      assertEquals(s.searchUids(0), Right(List(12L)))
      assertEquals(s.fetchMessage(12), Right(body17))
    }
  }

  test("session: LOGIN tagged NO [AUTHENTICATIONFAILED] ⇒ Left 可读") {
    val server =
      ScriptedImap("* OK mock ready", List(_ => "a1 NO [AUTHENTICATIONFAILED] LOGIN Login error or password error\r\n"))
    withSession(server) { s =>
      val r = s.login("mock-user@qq.com", "mock-auth-code-not-real")
      val err = r.left.toOption.get
      assert(err.contains("AUTHENTICATIONFAILED"), err)
      assert(!err.contains("\r\n"))
    }
  }

  test("session: SELECT tagged NO ⇒ Left 可读、不倾倒 untagged raw") {
    val server = ScriptedImap(
      "* OK mock ready",
      List(
        okLogin,
        _ => "* 4497 EXISTS\r\n* 0 RECENT\r\na2 NO SELECT failed, mailbox locked\r\n"
      )
    )
    withSession(server) { s =>
      assert(s.login("mock-user@qq.com", "mock-auth-code-not-real").isRight)
      val r = s.selectInbox()
      val err = r.left.toOption.get
      assert(err.contains("SELECT failed"), err)
      assert(!err.contains("4497"), s"错误串倾倒了 untagged raw: $err")
    }
  }

  test("session: SELECT 后服务器静默 ⇒ 超时走错误路径（人类可读，非挂死）") {
    val server = ScriptedImap(
      "* OK mock ready",
      List(
        okLogin,
        _ => "" // SELECT 静默
      )
    )
    withSession(server, soTimeoutMs = 400) { s =>
      assert(s.login("mock-user@qq.com", "mock-auth-code-not-real").isRight)
      val r = s.selectInbox()
      val err = r.left.toOption.get
      assert(err.contains("超时"), err)
      assert(!err.contains("\r\n"))
    }
  }

  test("session: 响应中途断连 ⇒ Left 可读连接中断") {
    val server = ScriptedImap(
      "* OK mock ready",
      List(
        okLogin,
        _ => null // SELECT 时直接断
      )
    )
    withSession(server) { s =>
      assert(s.login("mock-user@qq.com", "mock-auth-code-not-real").isRight)
      val r = s.selectInbox()
      val err = r.left.toOption.get
      assert(err.contains("中断"), err)
    }
  }

  // ════════ readableConnectError（连接失败人类可读面）════════

  private val rawDump = "* 4497 EXISTS\r\n* 0 RECENT\r\n* OK [UNSEEN 429]\r\n"

  test("readable: raw untagged 倾倒输入 ⇒ 人类可读单行（不再透传 raw）") {
    val msg = PerceptionService.readableConnectError(rawDump, "imap.qq.com", 993)
    assert(!msg.contains("\r\n"))
    assert(!msg.contains("EXISTS"))
    assert(!msg.contains("429"))
    assert(msg.contains("连接失败"))
  }

  test("readable: 认证失败标记 ⇒ 授权码指引文案") {
    val msg =
      PerceptionService.readableConnectError("IMAP NO [AUTHENTICATIONFAILED] LOGIN Login error", "imap.qq.com", 993)
    assert(msg.contains("授权码"))
  }

  test("readable: 超时 / 域名解析失败 / 拒绝 各归各类") {
    assert(PerceptionService.readableConnectError("IMAP 响应超时 · 服务器长时间无响应", "imap.qq.com", 993).contains("超时"))
    assert(
      PerceptionService
        .readableConnectError("IMAP connect SocketTimeoutException: connect timed out", "imap.qq.com", 993)
        .contains("超时")
    )
    assert(
      PerceptionService
        .readableConnectError("IMAP connect UnknownHostException: imap.qq.com", "imap.qq.com", 993)
        .contains("无法连接")
    )
    assert(PerceptionService.readableConnectError("IMAP NO SELECT failed", "imap.qq.com", 993).contains("拒绝"))
  }

  test("readable: 授权码擦除（任何回显路径不入 UI/日志面）") {
    // 擦除先于分类：含 AUTH 字样的授权码值被抹去后不再触发「认证失败」分支，
    // 落入 BAD 拒绝臂；两条断言各钉一面（值不可见 + 分类诚实）。
    val msg = PerceptionService.readableConnectError(
      "IMAP BAD echo MYSECRETAUTHCODE",
      "imap.qq.com",
      993,
      authCode = "MYSECRETAUTHCODE"
    )
    assert(!msg.contains("MYSECRETAUTHCODE"))
    assert(msg.contains("服务器拒绝"), msg)
  }

  // ════════ connectAccount 腿（失败路径零半保存；零真实凭据）════════
  //
  // 成功腿的编排（saveSecret → dataRoot/secrets 全局根）在 spec 里换根会污染
  // 并行套件——成功形态已由 session 全链路 + InboxStats + routes JSON 面
  // （编译期）覆盖；此处只钉失败路径的关键契约：可读错误 + 零持久化。

  test("core: connectAccount 连不上 ⇒ Left 可读且零落盘（失败路径不半保存）") {
    val tmp = java.nio.file.Files.createTempDirectory("perception-connect-fail-spec")
    val core = new PerceptionCore(
      store = new PerceptionStore(os.Path(tmp)),
      resources = null,
      broadcast = _ => IO.unit
    )
    core.bootInitialize.unsafeRunSync() // loadAccounts 缺文件 = Nil，零写盘
    try
      val res = core
        .connectAccount(
          address = "mock-user@qq.com",
          authCode = "mock-wrong-code",
          imapHost = Some("127.0.0.1"),
          imapPort = Some(1), // 本机必拒口 → 连接失败形状
          smtpHost = Some("127.0.0.1"),
          smtpPort = Some(2465)
        )
        .unsafeRunSync()
      val err = res.left.toOption.get
      assert(err.contains("无法连接"), s"连接失败应映射可读文案: $err")
      assert(!err.contains("\r\n") && !err.contains("Exception"), s"错误面不得透传 raw/异常类名: $err")
      // 失败路径零持久化：无账户注册表、无账户文件、无状态文件（secret 同理不落）
      assertEquals(core.accounts.get.unsafeRunSync().toList, Nil)
      val root = os.Path(tmp)
      assert(!os.exists(root / "accounts.json"), "失败路径不得写账户文件")
      assert(!os.exists(root / "state.json"), "失败路径不得写状态文件")
    finally os.remove.all(os.Path(tmp))
    end try
  }
end MailClientImapSpec
