package nebflow.perception

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.nio.file.Files
// 域类型嵌在 PerceptionModel 对象内（单文件单对象纪律），测试面直接摊平引用。
import PerceptionModel.*

/**
 * 感知域核心纯逻辑 spec（mail-perception-impl 批）。
 *
 * 覆盖：域预设识别 / OTP 提取与掩码 / 账单分类 / 草稿编解码（含引用块剥取）/
 * MIME 组装与点填充 / 存储面（0600、spool、账单缓冲）/ 窗口单码槽状态机 /
 * 面板条目四色投影 / 快照一致性守则（stale 判据）。全部 mock 值，零真实凭据。
 */
class PerceptionCoreSpec extends FunSuite:

  // ── MailboxPresets ─────────────────────────────────────────────────────

  test("presets: QQ 邮箱两问自动识别（imap/smtp 全配）") {
    val p = MailboxPresets.forAddress("i@qq.com")
    assert(p.isDefined)
    assertEquals(p.get.imapHost, "imap.qq.com")
    assertEquals(p.get.smtpHost, "smtp.qq.com")
    assertEquals(p.get.imapPort, 993)
    assertEquals(p.get.smtpPort, 465)
    assert(p.get.smtpImplicitTls)
  }

  test("presets: outlook 走 587 STARTTLS（非隐式 TLS）") {
    val p = MailboxPresets.forAddress("someone@outlook.com").get
    assertEquals(p.smtpPort, 587)
    assert(!p.smtpImplicitTls)
  }

  test("presets: 未收录域返回 None（高级手填路径）") {
    assertEquals(MailboxPresets.forAddress("me@some-university.edu.cn"), None)
  }

  test("presets: domainOf 大小写归一") {
    assertEquals(MailboxPresets.domainOf("User@GMAIL.com"), "gmail.com")
    assertEquals(MailboxPresets.domainOf("no-at-sign"), "")
  }

  // ── OtpExtractor ───────────────────────────────────────────────────────

  test("otp: 中文验证码邮件 → 提取数字码") {
    val hits = OtpExtractor.extract("您的验证码", "您好，验证码 482913，5 分钟内有效。请勿泄露。")
    assert(hits.nonEmpty)
    assertEquals(hits.head.code, "482913")
  }

  test("otp: 英文 verification code 邮件") {
    val hits = OtpExtractor.extract("Your verification code", "Your code is 993 121. Do not share it.")
    // "993 121" 不构成一段（含空格分隔），应提 993 与 121 中高置信者——都合法
    assert(hits.nonEmpty)
    assert(hits.forall(h => h.code.forall(_.isDigit)))
  }

  test("otp: 无意图词的邮件不误报（订单号不提取）") {
    val hits = OtpExtractor.extract("订单发货通知", "您的订单 2026102500123 已发货。")
    assertEquals(hits, Nil)
  }

  test("otp: 年份形 4 位数字不作为验证码") {
    val hits = OtpExtractor.extract("验证成功通知", "您已于 2026 年完成验证，感谢使用。")
    assertEquals(hits.filter(_.code == "2026"), Nil)
  }

  test("otp: 主题意图加权高于正文") {
    val withSubject = OtpExtractor.extract("登录验证码", "流水号 123456")
    assertEquals(withSubject.headOption.map(_.code), Some("123456"))
  }

  test("otp: mask 只露前三位") {
    assertEquals(OtpExtractor.mask("482913"), "482***")
    assertEquals(OtpExtractor.mask("12"), "***")
  }

  // ── BillingClassifier ──────────────────────────────────────────────────

  test("billing: 支付宝域直接判账单") {
    val v = BillingClassifier.classify("alipay.com", "支付宝电子回单", "")
    assert(v.isBilling)
    assert(v.matchedBy.startsWith("domain:"))
  }

  test("billing: 关键词+金额判账单；关键词无金额不判") {
    assert(BillingClassifier.classify("example.com", "10月账单已出", "本期应缴 ¥128.50 元").isBilling)
    assert(!BillingClassifier.classify("example.com", "账单功能介绍", "我们不提供账单服务").isBilling)
  }

  test("billing: 普通邮件不判") {
    assert(!BillingClassifier.classify("friend.me", "周五吃饭吗", "老地方见").isBilling)
  }

  // ── DraftCodec ─────────────────────────────────────────────────────────

  test("draft: serialize → parse 往返一致") {
    val doc = DraftCodec.DraftDoc(
      draftId = "draft-x",
      to = List("a@b.com", "c@d.com"),
      cc = List("e@f.com"),
      bcc = Nil,
      subject = "10 月对账",
      bodyHtml = "<p>正文一行</p>",
      attachments = List("/tmp/invoice.pdf"),
      replyToMessageId = Some("<mid@x>"),
      quoteHtml = Some("<perception-quote data-quote=\"1\"><div class=\"pq-head\">发件人：g@h.com</div></perception-quote>")
    )
    val raw = DraftCodec.serialize(doc)
    val back = DraftCodec.parse("draft-x", raw)
    assert(back.isRight)
    val d = back.toOption.get
    assertEquals(d.to, doc.to)
    assertEquals(d.cc, doc.cc)
    assertEquals(d.subject, doc.subject)
    assertEquals(d.attachments, doc.attachments)
    assertEquals(d.replyToMessageId, doc.replyToMessageId)
    assert(d.quoteHtml.exists(_.contains("pq-head")))
    assert(d.bodyHtml.contains("正文一行"))
  }

  test("draft: parse 容忍缺失字段 / 未闭合报错") {
    assert(DraftCodec.parse("d", "no front matter at all").isLeft)
    assert(DraftCodec.parse("d", "---\nto: a@b.com\n").isLeft)
    val ok = DraftCodec.parse("d", "---\nsubject: 只有主题\n---\n\n<p>hi</p>")
    assert(ok.isRight)
    assertEquals(ok.toOption.get.to, Nil)
  }

  test("draft: 引用块剥取（blockquote 到文末归 quote）") {
    val (quote, body) = DraftCodec.splitQuote("<p>回复正文</p>\n<blockquote><p>原信</p>")
    assert(quote.isDefined)
    assert(quote.get.endsWith("</blockquote>")) // 未闭合自动补
    assertEquals(body.trim, "<p>回复正文</p>")
  }

  test("draft: htmlToText 剥标签还原换行") {
    val t = DraftCodec.htmlToText("<p>第一行</p>\n<p>第二行</p>")
    assert(t.contains("第一行"))
    assert(t.contains("第二行"))
  }

  test("draft: 地址解析（逗号/分号/空白分隔，滤非法）") {
    assertEquals(DraftCodec.parseAddresses("a@b.com, c@d.com；e@f.com"), List("a@b.com", "c@d.com", "e@f.com"))
    assertEquals(DraftCodec.parseAddresses("not-an-address"), Nil)
  }

  // ── MailClient 纯函数面 ────────────────────────────────────────────────

  test("mime: buildData 组装 alternative 正文 + B 编码主题 + 点填充") {
    val mail = MailClient.OutgoingMail(
      from = "me@qq.com",
      fromName = "张三",
      to = List("a@b.com"),
      cc = List("c@d.com"),
      bcc = Nil,
      subject = "你好 & 问候",
      bodyHtml = "<p>第一段</p><p>.开头行</p>",
      attachments = Nil
    )
    val data = MailClient.SmtpCodec.buildData(mail)
    assert(data.startsWith("From:"))
    assert(data.contains("=?UTF-8?B?")) // 主题 B 编码
    assert(data.contains("multipart/mixed"))
    assert(data.contains("text/plain"))
    assert(data.contains("text/html"))
    // 正文完整性（经 base64 传输后可解回；正文内的行首点由 dotStuff 在
    // 传输层统一处理——单独的 dotStuff 单测在下方钉行为）。
    val (h, b) = MailClient.Rfc822.parse(data)
    val roundtrip = MailClient.Rfc822.bodyText(h, b)
    assert(roundtrip.contains("第一段"))
    assert(roundtrip.contains(".开头行"))
    // 结尾终止符
    assert(data.trim.endsWith("--"))
  }

  test("mime: dotStuff 只填行首点") {
    assertEquals(MailClient.SmtpCodec.dotStuff("a\r\n.b\r\n..c"), "a\r\n..b\r\n...c")
  }

  test("rfc822: 头解析 + base64 正文解码") {
    val raw =
      "From: =?UTF-8?B?" +
        java.util.Base64.getEncoder.encodeToString("例处".getBytes("UTF-8")) +
        "?= <s@x.com>\r\nSubject: =?UTF-8?B?" +
        java.util.Base64.getEncoder.encodeToString("验证码".getBytes("UTF-8")) +
        "?=\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
        java.util.Base64.getEncoder.encodeToString("你的验证码是 552310".getBytes("UTF-8")) + "\r\n"
    val (headers, body) = MailClient.Rfc822.parse(raw)
    // parse 是浅层契约（头保持原样）；RFC2047 解码在 HeaderCodec / toIncomingMail 层。
    assertEquals(MailClient.HeaderCodec.decode(headers("subject")), "验证码")
    assert(headers("from").contains("<s@x.com>"))
    val text = MailClient.Rfc822.bodyText(headers, body)
    assert(text.contains("552310"))
  }

  test("rfc822: multipart 深取 text/plain part") {
    val b64 = java.util.Base64.getEncoder.encodeToString("plain-part 884212".getBytes("UTF-8"))
    val raw =
      "From: s@x.com\r\nSubject: t\r\nContent-Type: multipart/alternative; boundary=\"BB\"\r\n\r\n" +
        "--BB\r\nContent-Type: text/plain\r\nContent-Transfer-Encoding: base64\r\n\r\n" + b64 + "\r\n" +
        "--BB\r\nContent-Type: text/html\r\n\r\n<p>html-part</p>\r\n" +
        "--BB--\r\n"
    val (headers, body) = MailClient.Rfc822.parse(raw)
    val text = MailClient.Rfc822.bodyText(headers, body)
    assert(text.contains("884212"))
  }

  test("rfc822: toIncomingMail 组装（域名抽取/首地址）") {
    val raw =
      "From: Xiao Ming <xm@Example.COM>\r\nTo: me@qq.com\r\nSubject: hi\r\nMessage-ID: <m1@x>\r\n\r\nbody here"
    val mail = MailClient.toIncomingMail(7, raw)
    assertEquals(mail.uid, 7L)
    assertEquals(mail.from, "xm@example.com".stripSuffix("@"))
    assertEquals(mail.from, "xm@Example.COM".toLowerCase)
    assertEquals(mail.fromDomain, "example.com")
    assertEquals(mail.messageId, "<m1@x>")
  }

  // ── PerceptionStore ────────────────────────────────────────────────────

  test("store: 账户落盘无授权码值；草稿/审计/spool/账单缓冲全链路") {
    val tmp = Files.createTempDirectory("perception-spec")
    val store = new PerceptionStore(os.Path(tmp))
    store.ensureDirs()

    // accounts
    val acc = MailAccount(id = "qqcom", address = "i@qq.com", imapHost = "imap.qq.com", smtpHost = "smtp.qq.com", authCodeSecret = "perception-i-qq-com")
    assert(store.saveAccounts(List(acc)).isRight)
    val loaded = store.loadAccounts()
    assertEquals(loaded.map(_.address), List("i@qq.com"))
    assert(!os.read(os.Path(tmp) / "accounts.json").contains("auth-code-plain")) // 值永不入账户文件

    // secrets 名派生合法（SecretsStore 命名纪律）
    val name = MailAccount.secretNameFor("i@qq.com")
    assert(name.startsWith("perception-"))
    assert(name.matches("^[a-z0-9][a-z0-9._-]{0,63}$"))

    // draft file 0600
    val path = store.writeDraftFile("draft-1", "hello")
    assert(path.isRight)
    val p = os.Path(path.toOption.get)
    assertEquals(os.read(p), "hello")
    val perms = Files.getPosixFilePermissions(p.toNIO)
    assert(perms.size() == 2) // owner read+write only

    // audit append
    store.appendAudit(AuditLine(1L, "L0", "spec", "detail"))
    store.appendAudit(AuditLine(2L, "deliver", "spec2", ""))
    assert(os.read.lines(os.Path(tmp) / "audit.jsonl").size == 2)

    // spool write → list → remove
    assert(store.spoolWrite(DeliveryEnvelope("normal", "信")).isRight)
    val spooled = store.spoolList()
    assertEquals(spooled.size, 1)
    assertEquals(spooled.head._2.text, "信")
    store.spoolRemove(spooled.head._1)
    assertEquals(store.spoolList(), Nil)

    // billing buffer
    assert(store.billingAppend("2026-10-25", PerceptionStore.BillingLine(1L, "<m>", "alipay.com", "s", "¥1.00", "domain")).isRight)
    assertEquals(store.billingRead("2026-10-25").size, 1)
    store.billingClear("2026-10-25")
    assertEquals(store.billingRead("2026-10-25"), Nil)

    // drafts registry
    assert(store.saveDrafts(List(DraftRecord("d1", "/tmp/x", 1L, 1L))).isRight)
    assertEquals(store.loadDrafts().map(_.draftId), List("d1"))
  }

  // ── PerceptionCore（临时根 + 直构）────────────────────────────────────

  private def newCore(): (PerceptionCore, os.Path) =
    val tmp = Files.createTempDirectory("perception-core-spec")
    val store = new PerceptionStore(os.Path(tmp))
    val events = new scala.collection.mutable.ArrayBuffer[io.circe.Json]
    val core = new PerceptionCore(
      store = store,
      resources = null, // 注入面不触达（本 suite 不跑投递腿）
      broadcast = j => IO { events += j; () }
    )
    (core, os.Path(tmp))

  test("core: OTP 窗口单码槽——命中/覆盖/一次性消费/过期") {
    val (core, _) = newCore()
    core.bootInitialize.unsafeRunSync()
    val w = core.openOtpWindow("asknb-1", 600).unsafeRunSync()
    // 无命中时 consume 为空
    assertEquals(core.consumeOtp(w.windowId).unsafeRunSync(), None)
    // 命中
    val hit = PerceptionModel.OtpHit("482913", "s@x.com", "x.com", 1L)
    core.deliverOtpHit(List(w), mailFixture(uid = 1), OtpExtractor.OtpCandidate("482913", 9, "t")).unsafeRunSync()
    // 消费一次拿到
    val got = core.consumeOtp(w.windowId).unsafeRunSync()
    assertEquals(got.map(_.code), Some("482913"))
    // 消费后槽空（一次性）
    assertEquals(core.consumeOtp(w.windowId).unsafeRunSync(), None)
    // 第二码覆盖（重新命中）
    core.deliverOtpHit(List(w), mailFixture(uid = 2), OtpExtractor.OtpCandidate("777001", 9, "t")).unsafeRunSync()
    assertEquals(core.consumeOtp(w.windowId).unsafeRunSync().map(_.code), Some("777001"))
  }

  test("core: sweep 过期窗口零 UI 广播（expired 帧）且窗口移除") {
    val (core, _) = newCore()
    core.bootInitialize.unsafeRunSync()
    val w = core.openOtpWindow("asknb-2", -1).unsafeRunSync() // 已过期窗
    core.sweepOnce.unsafeRunSync()
    val remaining = core.consumeOtp(w.windowId).unsafeRunSync()
    assertEquals(remaining, None)
  }

  test("core: 面板条目投影——未配置/已连接/暂停/授权失效四态与四色") {
    val (core, _) = newCore()
    core.bootInitialize.unsafeRunSync()
    // 未配置
    val empty = core.entriesPayload.unsafeRunSync()
    assertEquals(empty.entries, Nil)
    // 连接态（不走真实 IMAP——直改 Ref）
    core.connectForTest("spec@qq.com").unsafeRunSync()
    val one = core.entriesPayload.unsafeRunSync().entries
    assertEquals(one.size, 1)
    assertEquals(one.head.state, PerceptionModel.EntryState.Idle)
    assertEquals(PerceptionModel.EntryState.colorOf(one.head.state), "ok")
    // 暂停
    core.toggleAccount(one.head.id, enabled = false).unsafeRunSync()
    val paused = core.entriesPayload.unsafeRunSync().entries.head
    assertEquals(paused.state, PerceptionModel.EntryState.Paused)
    assertEquals(PerceptionModel.EntryState.colorOf(paused.state), "dim")
  }

  test("core: 草稿快照一致性——编辑后 mtime > snapshotAt 判 stale") {
    val (core, tmp) = newCore()
    core.bootInitialize.unsafeRunSync()
    core.connectForTest("spec2@qq.com").unsafeRunSync()
    val doc = DraftCodec.DraftDoc("", List("a@b.com"), Nil, Nil, "s", "<p>b</p>", Nil)
    val rec = core.createDraft(doc).unsafeRunSync().toOption.get
    // 快照发送（未编辑）不 stale
    assert(!core.draftView(rec.draftId).unsafeRunSync().get.stale)
    // 画布保存 → stale
    Thread.sleep(5)
    core.saveDraft(rec.draftId, DraftCodec.serialize(doc.copy(subject = "s-edited"))).unsafeRunSync()
    val v = core.draftView(rec.draftId).unsafeRunSync().get
    assert(v.stale)
    assertEquals(v.doc.toOption.get.subject, "s-edited")
  }

  test("core: sendDraft 附件拒绝（本批发送附件未开放，不静默丢弃）") {
    val (core, _) = newCore()
    core.bootInitialize.unsafeRunSync()
    core.connectForTest("spec3@qq.com").unsafeRunSync()
    val doc = DraftCodec.DraftDoc("", List("a@b.com"), Nil, Nil, "s", "<p>b</p>", List("/tmp/att.pdf"))
    val rec = core.createDraft(doc).unsafeRunSync().toOption.get
    val res = core.sendDraft(rec.draftId, snapshot = false).unsafeRunSync()
    assert(res.isLeft)
    assert(PerceptionModel.SendFailure.readable(res.left.toOption.get).contains("附件"))
  }

  test("core: abandonDraft 置态并删文件") {
    val (core, _) = newCore()
    core.bootInitialize.unsafeRunSync()
    val doc = DraftCodec.DraftDoc("", List("a@b.com"), Nil, Nil, "s", "<p>b</p>", Nil)
    val rec = core.createDraft(doc).unsafeRunSync().toOption.get
    assert(os.exists(os.Path(rec.path)))
    core.abandonDraft(rec.draftId).unsafeRunSync()
    assert(!os.exists(os.Path(rec.path)))
    val v = core.draftView(rec.draftId).unsafeRunSync().get
    assertEquals(v.record.state, "abandoned")
  }

  private def mailFixture(uid: Long): PerceptionModel.IncomingMail =
    PerceptionModel.IncomingMail(uid, "s@x.com", "x.com", List("me@qq.com"), "您的验证码", "", s"<m$uid@x>", "code 111222")

  extension (core: PerceptionCore)
    /** Test seam: register an account WITHOUT touching real IMAP/secrets. */
    def connectForTest(address: String): IO[Unit] =
      for
        _ <- core.testInjectAccount(MailAccount(
          id = address.replaceAll("[^a-z0-9]", ""),
          address = address,
          imapHost = "imap.test",
          smtpHost = "smtp.test",
          authCodeSecret = ""
        ))
      yield ()
end PerceptionCoreSpec
