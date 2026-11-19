package nebflow.perception

import munit.FunSuite

import nebflow.perception.PerceptionModel.SendFailure

import java.io.{BufferedReader, BufferedOutputStream, InputStreamReader, PrintStream}
import java.net.{InetAddress, ServerSocket, Socket}
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SMTP 发送硬化 spec（报障 2026-10-31：作者发信遇超时怒评批）。
 *
 * 覆盖报障批四修复点：
 *   1. DATA 阶段分级超时（dataTimeoutMsFor）——大附件邮件服务器扫描/确认
 *      >20s 不再被统一短超时误伤；
 *   2. DATA 最终回执不再丢弃——服务器在报文提交后的拒收（5xx）旧版被报成
 *      「发送成功」，本 spec 钉住分类；
 *   3. 失败三分类（Timeout 可重试 / Ambiguous 可能已投递禁盲重试 / Rejected
 *      按码分可重试与否）+ sendWithRetry 只重试瞬时且零投递风险者；
 *   4. readable 人话文案（超时告知可重发；Ambiguous 告知可能已发出勿重发；
 *      不再把裸异常类名顶在用户面前——「Host did not answer in time」式
 *      裸报错根治面）。
 *
 * 纪律：夹具全部 mock 值，零真实账号/授权码；本地 loopback 明文 socket +
 * 脚本 mock SMTP 服务器直驱 smtpTransact（TLS 包装腿在 send() 内，薄到
 * 不值得拆——协议判定面全部在本 spec 覆盖内）。
 */
class MailClientSendSpec extends FunSuite:

  private val testMail = MailClient.OutgoingMail(
    from = "mock-user@qq.com",
    fromName = "Mock Sender",
    to = List("a@b.com"),
    cc = Nil,
    bcc = Nil,
    subject = "硬化批测试信",
    bodyHtml = "<p>正文一行</p>"
  )

  private def buildData: String = MailClient.SmtpCodec.buildData(testMail)

  /**
   * 脚本 mock SMTP 服务器（单连接/实例）。各命令回覆可配；特殊哨兵：
   *   ""   = 保持静默（客户端撞 SoTimeout）
   *   null = 直接断连
   * dataFinal 在收到 DATA 载荷收尾 "." 后调用（可内嵌 sleep 模拟慢回执；
   * 返回 "" = 保持静默）。收到的 DATA 载荷全文暴露在 dataReceived。
   */
  private final class MockSmtp(
    greeting: String = "220 mock ESMTP ready",
    ehlo: String = "250-mock greets you\r\n250 AUTH PLAIN",
    auth: String = "235 Authenticated",
    mail: String = "250 OK",
    rcpt: String = "250 OK",
    dataCmd: String = "354 End data with <CR><LF>.<CR><LF>",
    dataFinal: () => String = () => "250 OK queued as mock-1",
    quit: String = "221 Bye"
  ):
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress)
    val port: Int = server.getLocalPort
    @volatile var dataReceived: String = ""
    @volatile var ehloCount: Int = 0
    private val stopped = AtomicBoolean(false)
    private val thread = new Thread(() =>
      try
        val conn = server.accept()
        try
          val out = PrintStream(new BufferedOutputStream(conn.getOutputStream), true, "ISO-8859-1")
          def send(s: String): Unit = { out.print(s + "\r\n"); out.flush() }
          send(greeting)
          val reader = BufferedReader(new InputStreamReader(conn.getInputStream, "ISO-8859-1"))
          val sb = new StringBuilder
          var inData = false
          var alive = true
          while alive do
            val line = reader.readLine()
            if line == null then alive = false
            else if inData then
              if line == "." then
                dataReceived = sb.toString
                sb.setLength(0)
                inData = false
                val fin = dataFinal()
                if fin != null && fin.nonEmpty then send(fin)
              else sb.append(line).append("\r\n")
            else
              val u = line.toUpperCase
              val reply: String =
                if u.startsWith("EHLO") then { ehloCount += 1; ehlo }
                else if u.startsWith("AUTH") then auth
                else if u.startsWith("MAIL") then mail
                else if u.startsWith("RCPT") then rcpt
                else if u.startsWith("DATA") then { inData = true; dataCmd }
                else if u.startsWith("QUIT") then quit
                else "502 Command not implemented"
              if reply == null then alive = false // 断连哨兵
              else if reply.nonEmpty then send(reply)
              if u.startsWith("QUIT") then alive = false
        finally try conn.close() catch case _: Throwable => ()
      catch case _: Throwable => ()
    )
    thread.setDaemon(true)
    thread.start()
    def stop(): Unit =
      stopped.set(true)
      try server.close() catch case _: Throwable => ()

  /** 客户端腿：真实 loopback socket + 小超时，DATA 阶段经 onDataPhase 切大超时。 */
  private def transact(
    server: MockSmtp,
    handshakeTimeoutMs: Int = 400,
    dataTimeoutMs: Int = 5000
  ): Either[SendFailure, String] =
    val sock = new Socket(InetAddress.getLoopbackAddress, server.port)
    sock.setSoTimeout(handshakeTimeoutMs)
    try
      val in = new BufferedReader(new InputStreamReader(sock.getInputStream, "ISO-8859-1"))
      MailClient.smtpTransact(
        in = in,
        out = sock.getOutputStream,
        user = "mock-user@qq.com",
        authCode = "mock-code-not-real",
        mail = testMail,
        data = buildData,
        onDataPhase = () => sock.setSoTimeout(dataTimeoutMs)
      )
    finally
      try sock.close() catch case _: Throwable => ()
      server.stop()

  // ════════ ① 成功链路 ════════

  test("session: 全链路成功——Right(messageId)，服务器实收完整 DATA 载荷") {
    val server = new MockSmtp()
    val res = transact(server)
    assert(res.isRight, s"成功链路判失败: ${res.left.toOption.get}")
    val mid = res.toOption.get
    assert(mid.nonEmpty, "messageId 不应为空")
    assert(server.dataReceived.contains("Message-ID:"), "服务器应收到带 Message-ID 的载荷")
    assert(server.dataReceived.contains("Subject:"), "服务器应收到主题头")
  }

  // ════════ ② DATA 分级超时（报障核心：大附件慢确认不再误伤）════════

  test("session: DATA 回执慢（1.2s）但落在分级窗口内 ⇒ 成功——旧统一短超时在此必败") {
    // 验红实证：握手超时仅 400ms——若 onDataPhase 未把 DATA 阶段切到大超时
    //（或旧版统一超时语义），等待最终回执 1.2s 必撞 400ms SoTimeout。
    val server = new MockSmtp(dataFinal = () => { Thread.sleep(1200); "250 OK slow-scan-done" })
    val res = transact(server, handshakeTimeoutMs = 400, dataTimeoutMs = 5000)
    assert(res.isRight, s"分级超时应放行慢回执: ${res.left.toOption.get}")
  }

  test("session: DATA 回执超出分级窗口 ⇒ Ambiguous（可能已投递，禁盲重试）") {
    val server = new MockSmtp(dataFinal = () => { Thread.sleep(3000); "250 OK too-late" })
    val res = transact(server, handshakeTimeoutMs = 400, dataTimeoutMs = 800)
    assertEquals(res.left.toOption.get, SendFailure.Ambiguous)
    assert(!MailClient.isSendRetryable(res.left.toOption.get), "Ambiguous 盲重试 = 重复投递，绝不可重试")
    val msg = SendFailure.readable(res.left.toOption.get)
    assert(msg.contains("可能已发出"), msg)
    assert(msg.contains("请勿立即重发"), msg)
  }

  // ════════ ③ 握手阶段失败分类（全部零投递风险，可安全重试）════════

  test("session: 握手阶段静默 ⇒ Timeout（readable 人话、可重试）") {
    val server = new MockSmtp(auth = "")
    val res = transact(server, handshakeTimeoutMs = 400)
    assertEquals(res.left.toOption.get, SendFailure.Timeout)
    assert(MailClient.isSendRetryable(res.left.toOption.get))
    val msg = SendFailure.readable(res.left.toOption.get)
    assert(msg.contains("超时"), msg)
    assert(msg.contains("重发"), msg)
    assert(!msg.contains("Exception"), s"用户面不得透传异常类名: $msg")
  }

  test("session: 握手阶段断连 ⇒ Network（可重试）") {
    val server = new MockSmtp(auth = null) // 断连哨兵
    val res = transact(server, handshakeTimeoutMs = 400)
    res.left.toOption.get match
      case SendFailure.Network(_) => () // 预期分类
      case other => fail(s"预期 Network，得 $other")
    assert(MailClient.isSendRetryable(res.left.toOption.get))
  }

  // ════════ ④ 服务器回执按码分类 ════════

  test("session: AUTH 535 ⇒ AuthFailed（确定性失败，不重试）") {
    val server = new MockSmtp(auth = "535 Authentication failed")
    val res = transact(server, handshakeTimeoutMs = 400)
    assertEquals(res.left.toOption.get, SendFailure.AuthFailed)
    assert(!MailClient.isSendRetryable(res.left.toOption.get))
  }

  test("session: RCPT 550 ⇒ Rejected 不重试；RCPT 451 ⇒ Rejected 可重试") {
    val hard = new MockSmtp(rcpt = "550 Mailbox unavailable")
    assertEquals(transact(hard, handshakeTimeoutMs = 400).left.toOption.get,
      SendFailure.Rejected("550 Mailbox unavailable"))
    assert(!MailClient.isSendRetryable(SendFailure.Rejected("550 Mailbox unavailable")))

    val soft = new MockSmtp(rcpt = "451 Local error, try later")
    val r = transact(soft, handshakeTimeoutMs = 400)
    assertEquals(r.left.toOption.get, SendFailure.Rejected("451 Local error, try later"))
    assert(MailClient.isSendRetryable(r.left.toOption.get), "4xx = 瞬时，可安全重发")
  }

  test("session: DATA 最终回执 5xx ⇒ 拒收判失败（旧版丢弃回执报成功，缺陷钉）") {
    val server = new MockSmtp(dataFinal = () => "552 Storage quota exceeded")
    val res = transact(server, handshakeTimeoutMs = 400)
    assertEquals(res.left.toOption.get, SendFailure.Rejected("552 Storage quota exceeded"))
    assert(!MailClient.isSendRetryable(res.left.toOption.get), "5xx = 确定性拒收，不重试")
    assert(server.dataReceived.nonEmpty, "服务器确已收到载荷（拒收发生在报文提交后）")
  }

  test("session: DATA 最终回执 4xx ⇒ Rejected 可重试（服务器明示未接收，零投递风险）") {
    val server = new MockSmtp(dataFinal = () => "452 Resources temporarily unavailable")
    val res = transact(server, handshakeTimeoutMs = 400)
    assertEquals(res.left.toOption.get, SendFailure.Rejected("452 Resources temporarily unavailable"))
    assert(MailClient.isSendRetryable(res.left.toOption.get))
  }

  // ════════ ⑤ 分级函数与重试外壳（纯逻辑面）════════

  test("dataTimeoutMsFor: 60s 底 + 每 100KB +1s，封底 300s") {
    assertEquals(MailClient.dataTimeoutMsFor(0), 60000)
    assertEquals(MailClient.dataTimeoutMsFor(50 * 1024), 60000) // <100KB 不加
    assertEquals(MailClient.dataTimeoutMsFor(5 * 1024 * 1024), 60000 + 51 * 1000)
    assertEquals(MailClient.dataTimeoutMsFor(1024 * 1024 * 1024), 300000) // 封底
  }

  test("sendWithRetry: Timeout/Network 重试至成功；Ambiguous/AuthFailed 一次即停") {
    var attempts = 0
    val okAfterTimeout = MailClient.sendWithRetry(maxAttempts = 2) {
      attempts += 1
      if attempts == 1 then Left(SendFailure.Timeout) else Right("mid-1")
    }
    assertEquals(okAfterTimeout, Right("mid-1"))
    assertEquals(attempts, 2)

    attempts = 0
    val ambiguous = MailClient.sendWithRetry(maxAttempts = 2) {
      attempts += 1
      Left(SendFailure.Ambiguous)
    }
    assert(ambiguous.isLeft)
    assertEquals(attempts, 1, "Ambiguous 不得重试（重复投递红线）")

    attempts = 0
    val authFailed = MailClient.sendWithRetry(maxAttempts = 2) {
      attempts += 1
      Left(SendFailure.AuthFailed)
    }
    assertEquals(attempts, 1, "AuthFailed 是确定性失败，不得重试")

    attempts = 0
    val exhausted = MailClient.sendWithRetry(maxAttempts = 2) {
      attempts += 1
      Left(SendFailure.Timeout)
    }
    assertEquals(exhausted, Left(SendFailure.Timeout))
    assertEquals(attempts, 2, "重试次数上限 = maxAttempts")
  }

  // ════════ ⑥ readable 人话文案 ════════

  test("readable: Timeout/Ambiguous/Network 三态文案人话、可行动、无裸异常") {
    val t = SendFailure.readable(SendFailure.Timeout)
    assert(t.contains("超时") && t.contains("重发"), t)
    assert(!t.contains("Exception") && !t.contains("Socket"), s"不得透传异常类名: $t")

    val a = SendFailure.readable(SendFailure.Ambiguous)
    assert(a.contains("可能已发出"), a)
    assert(a.contains("请勿立即重发"), a)

    val n = SendFailure.readable(SendFailure.Network("SocketTimeoutException: connect timed out"))
    assert(n.contains("网络"), n)
    assert(n.contains("重发"), n)
  }

end MailClientSendSpec
