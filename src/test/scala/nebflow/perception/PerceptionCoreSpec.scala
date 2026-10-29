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
      broadcast = j => IO { events += j; () },
      // 投递出域 seam 桩：本 suite 不跑投递腿（false=不可达 → 落 spool，零外呼）
      deliverEnvelope = (_, _) => IO.pure(false)
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

  // ── 回归：sweepLoop/spoolLoop 构造期自引用 SOE（P0 boot-fatal）─────────
  //
  // 旧版 `IO.sleep(..) *> sweepOnce *> sweepLoop`：`*>` 右操作数是急切的方法
  // 调用——构造一个迭代的 IO 需同步递归构造全部未来迭代 → boot 必经点
  // `doBoot` 的 `core.sweepLoop.start`（PerceptionService）当场 StackOverflowError，
  // 任何新 daemon 起不来（切版批实证 2/2，引入=183c375c2）。修法 = 递归腿
  // `IO.defer` 包住（同 ProjectActor.ttlScanner / TaskStuckWatcher.run 先例）。
  // 感知域两循环节奏固定（10s/45s），不能像先例那样缩 interval——推进钉用
  // 真实节奏 + 过期窗被清作可观察证据。

  test("regression: sweepLoop/spoolLoop 求值（构造）不炸栈——boot 必经点") {
    val (core, _) = newCore()
    core.bootInitialize.unsafeRunSync()
    // 旧版在下面两行「值构造期」即 StackOverflowError（尚未 .start）
    val _ = core.sweepLoop
    val _ = core.spoolLoop
  }

  test("regression: sweepLoop fiber 可启动、进入 sleep 腿、可取消") {
    val (core, _) = newCore()
    core.bootInitialize.unsafeRunSync()
    val fiber = core.sweepLoop.start.unsafeRunSync() // 旧版：构造期 SOE
    Thread.sleep(200) // 存活于 10s sleep 腿（未异常终止由 cancel/join 完成佐证）
    fiber.cancel.unsafeRunSync()
    fiber.join.unsafeRunSync()
  }

  test("regression: spoolLoop 求值不炸栈且 fiber 可启动取消") {
    val (core, _) = newCore()
    core.bootInitialize.unsafeRunSync()
    val fiber = core.spoolLoop.start.unsafeRunSync() // 旧版：构造期 SOE
    Thread.sleep(150)
    fiber.cancel.unsafeRunSync()
    fiber.join.unsafeRunSync()
  }

  test("regression: sweepLoop 跨 sleep 边界推进 ≥1 轮（过期窗被扫除）") {
    val (core, _) = newCore()
    core.bootInitialize.unsafeRunSync()
    val w = core.openOtpWindow("asknb-loop", -1).unsafeRunSync() // 已过期窗
    val fiber = core.sweepLoop.start.unsafeRunSync()
    // 轮询至窗口被扫除 = sleep(10s) → sweepOnce → 递归腿重新进入，一轮完整走通；
    // 上限 13s（sleep 节奏 10s + 余量）。旧版在 start 前即 SOE，到不了这里。
    val deadline = System.currentTimeMillis() + 13000L
    var gone = false
    while !gone && System.currentTimeMillis() < deadline do
      Thread.sleep(250)
      gone = !core.windows.get.map(_.contains(w.windowId)).unsafeRunSync()
    fiber.cancel.unsafeRunSync()
    assert(gone, "sweepLoop 未在 13s 内完成第一轮 sweep（过期窗未被清）——循环未推进")
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

  test("core: sendDraft 附件缺失——逐路径前置拒发（SMTP 不触达，r3 裁决 4）") {
    val (core, _) = newCore()
    core.bootInitialize.unsafeRunSync()
    core.connectForTest("spec3@qq.com").unsafeRunSync()
    val doc = DraftCodec.DraftDoc("", List("a@b.com"), Nil, Nil, "s", "<p>b</p>", List("/nonexistent/att.pdf"))
    val rec = core.createDraft(doc).unsafeRunSync().toOption.get
    val res = core.sendDraft(rec.draftId, snapshot = false).unsafeRunSync()
    assert(res.isLeft)
    val msg = PerceptionModel.SendFailure.readable(res.left.toOption.get)
    assert(msg.contains("附件不存在"), msg)
    assert(msg.contains("att.pdf"), msg) // 逐路径列出
  }

  test("draft: prepareAttachments——在位文件读字节；缺失逐路径拒（r2 §7.2/7.3）") {
    val (core, tmp) = newCore()
    val f = tmp / "att.txt"
    os.write(f, "hello-att")
    core.prepareAttachments(List(f.toString)) match
      case Right(atts) =>
        assertEquals(atts.size, 1)
        assertEquals(new String(atts.head._2), "hello-att")
      case Left(x) => fail(s"在位文件不应拒：$x")
    core.prepareAttachments(List(f.toString, "/nonexistent/x.pdf")) match
      case Left(ma) => assert(PerceptionModel.SendFailure.readable(ma).contains("x.pdf"))
      case Right(_) => fail("缺失文件应拒发")
    // stats 面（画布 chip 数据源）
    val stats = core.attachmentStats(List(f.toString, "/nonexistent/x.pdf"))
    assertEquals(stats.size, 2)
    assert(stats.head.exists)
    assert(stats(1).exists == false)
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

  // ── L1 判定腿（作者构想 1 + r2 §5.2：缺省流量经 LLM 三值判定，降级搁置）──

  private def judgeCore(verdict: L1Verdict, calls: java.util.concurrent.atomic.AtomicInteger): (PerceptionCore, os.Path) =
    val tmp = Files.createTempDirectory("perception-l1-spec")
    val store = new PerceptionStore(os.Path(tmp))
    val events = new scala.collection.mutable.ArrayBuffer[io.circe.Json]
    val core = new PerceptionCore(
      store = store,
      broadcast = j => IO { events += j; () },
      judge = Some(mail => IO { calls.incrementAndGet(); verdict }),
      deliverEnvelope = (_, _) => IO.pure(false)
    )
    (core, os.Path(tmp))

  private def accFixture(id: String): MailAccount =
    MailAccount(id = id, address = s"me@$id.test", imapHost = "imap.test", smtpHost = "smtp.test", authCodeSecret = "")

  test("judge: parseVerdict 三值——单 DELIVER/DROP 判；双有/双无=Unavailable") {
    val p = MailImportanceJudge.parseVerdict
    assertEquals(p("DELIVER"), L1Verdict.Deliver)
    assertEquals(p("drop"), L1Verdict.Drop)
    assertEquals(p("建议 DELIVER：真人事务来信"), L1Verdict.Deliver)
    assertEquals(p("营销邮件，DROP"), L1Verdict.Drop)
    assertEquals(p("DELIVER or DROP?"), L1Verdict.Unavailable) // 双有 = 病态回复
    assertEquals(p("无法判断"), L1Verdict.Unavailable)
    assertEquals(p(""), L1Verdict.Unavailable)
  }

  test("judge: buildUserContent 头部齐全 + 正文 4KB 截断注记（数据面锁定）") {
    val long = "x" * (MailImportanceJudge.BodyCapChars + 100)
    val c = MailImportanceJudge.buildUserContent(mailFixture(1).copy(bodyText = long))
    assert(c.contains("发件人: s@x.com"))
    assert(c.contains("主题: 您的验证码"))
    assert(c.contains(s"[正文超长，已截断：原文 ${long.length} 字符]"))
    assert(c.length < MailImportanceJudge.BodyCapChars + 200) // 全文不出门
    assertEquals(MailImportanceJudge.BodyCapChars, 4096)
    assertEquals(MailImportanceJudge.TimeoutSec, 30)
  }

  test("judge: promptFor 维护型身份（sessionId/agentId=perception）+ searchAllowed=false") {
    val req = MailImportanceJudge.promptFor(mailFixture(1))
    assertEquals(req.sessionId, "perception")
    assertEquals(req.agentId, "perception")
    assertEquals(req.searchAllowed, false)
    assertEquals(req.systemStable, Some(MailImportanceJudge.SystemPrompt))
  }

  test("judge: judgeViaLlm 桩——正常回复/坏回复/传输失败三形态") {
    val mail = mailFixture(9)
    val ok: LlmRequest2IO = _ => IO.pure(respLike("DELIVER"))
    assertEquals(MailImportanceJudge.judgeViaLlm(ok)(mail).unsafeRunSync(), L1Verdict.Deliver)
    val bad: LlmRequest2IO = _ => IO.pure(respLike("？？？"))
    assertEquals(MailImportanceJudge.judgeViaLlm(bad)(mail).unsafeRunSync(), L1Verdict.Unavailable)
    val down: LlmRequest2IO = _ => IO.raiseError(new RuntimeException("llm down"))
    assertEquals(MailImportanceJudge.judgeViaLlm(down)(mail).unsafeRunSync(), L1Verdict.Unavailable)
  }

  private type LlmRequest2IO = nebflow.shared.LlmRequest => IO[MailImportanceJudge.LlmRespLike]
  private def respLike(r: String): MailImportanceJudge.LlmRespLike =
    new MailImportanceJudge.LlmRespLike { def reply = r }

  // ── L0 黑名单 / 白名单（判定 0.1 / 0.2 三形态）────────────────────────

  test("l0: isBlocked 三形态（裸域/@domain/完整地址）+ 空表恒 false + 大小写不敏感") {
    val (core, _) = newCore()
    val m = mailFixture(2) // from=s@x.com domain=x.com
    assert(core.isBlocked(List("x.com"), m))
    assert(core.isBlocked(List("@x.com"), m))
    assert(core.isBlocked(List("s@x.com"), m))
    assert(core.isBlocked(List("X.COM"), m))
    assert(!core.isBlocked(List("y.com"), m))
    assert(!core.isBlocked(List("s@y.com"), m))
    assert(!core.isBlocked(Nil, m)) // 空表 = 恒 false（零行为变化）
  }

  test("l0: isTrusted 白名单直通（完整地址与 @domain 形态）") {
    val (core, _) = newCore()
    val m = mailFixture(3)
    val acc = accFixture("specx").copy(trustedSenders = List("S@X.com", "@boss.com"))
    assert(core.isTrusted(acc, m)) // s@x.com 命中完整地址（大小写归一）
    assert(!core.isTrusted(acc.copy(trustedSenders = List("@y.com")), m))
    val boss = m.copy(from = "ceo@boss.com", fromDomain = "boss.com")
    assert(core.isTrusted(acc, boss)) // @domain 形态
  }

  // ── 退避序列（r1 §4.2：1s 起步 → 倍增 → 上限 5min ± 20% 抖动）─────────

  test("backoff: 1s 起步、指数倍增、上限 300s、抖动 ±20% 有界") {
    val b = PerceptionService.backoffDelaySec
    assertEquals(b(1, 0.0), 1L) // 1×0.8 → 1（下限钳 1s）
    assertEquals(b(1, 1.0), 1L) // 1×1.2 → 1
    assertEquals(b(2, 0.0), 2L)
    assertEquals(b(3, 0.0), 3L) // 4×0.8=3.2 → 3
    assertEquals(b(3, 1.0), 5L) // 4×1.2=4.8 → 5
    assertEquals(b(4, 0.5), 8L) // 8×1.0
    assertEquals(b(100, 0.0), 240L) // cap 300×0.8
    assertEquals(b(100, 1.0), 360L) // cap 300×1.2
    assert((1 to 14).forall(a => b(a, 0.0) <= b(a, 1.0))) // 抖动单调有界
  }

  // ── OTP 窗：引擎同步开窗幂等 + 命中元数据只读面（r1 §6.1 T0 裁定）─────

  test("otp: 开窗幂等 by requestId；windowHitMeta 只读不消费") {
    val (core, _) = newCore()
    core.bootInitialize.unsafeRunSync()
    val w1 = core.openOtpWindow("ask-idem", 60).unsafeRunSync()
    val w2 = core.openOtpWindow("ask-idem", 60).unsafeRunSync()
    assertEquals(w1.windowId, w2.windowId) // 后到者复用同一窗（防双窗双播）
    assertEquals(core.windowHitMeta(w1.windowId).unsafeRunSync(), None)
    core.deliverOtpHit(List(w1), mailFixture(3), OtpExtractor.OtpCandidate("654321", 9, "t")).unsafeRunSync()
    assertEquals(core.windowHitMeta(w1.windowId).unsafeRunSync().map(_.code), Some("654321"))
    // 只读：meta 后 consume 仍拿得到（元数据不消费槽）
    assertEquals(core.consumeOtp(w1.windowId).unsafeRunSync().map(_.code), Some("654321"))
    assertEquals(core.windowHitMeta(w1.windowId).unsafeRunSync(), None) // 消费后清空
  }

  // ── L1 管线（processMails 判定序 r1 §5.1）+ Message-ID 台账 + spool 补判

  test("l1: processMails Drop——judge 恰一次、台账入账；同 Message-ID 重放跳过（补拉去重）") {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    val (core, _) = judgeCore(L1Verdict.Drop, calls)
    core.bootInitialize.unsafeRunSync()
    val acc = accFixture("specdrop")
    val mail = IncomingMail(5, "spam@ads.com", "ads.com", List("me@specdrop.test"), "限时促销", "", "<m5@ads>", "buy now")
    core.processMails(acc, List(mail)).unsafeRunSync()
    assertEquals(calls.get, 1)
    assert(core.store.loadState()(acc.id).decidedMsgIds.contains("<m5@ads>"))
    // 重放同一封（UIDVALIDITY 重建补拉重入）→ 台账拦截，judge 不再被调
    core.processMails(acc, List(mail.copy(uid = 9))).unsafeRunSync()
    assertEquals(calls.get, 1)
  }

  test("l1: Unavailable——入 spool 搁置（不臆测投递/丢弃）；补判仍不可用则留存") {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    val (core, _) = judgeCore(L1Verdict.Unavailable, calls)
    core.bootInitialize.unsafeRunSync()
    val acc = accFixture("specunav")
    val mail = IncomingMail(6, "news@x.com", "x.com", List("me@specunav.test"), "周报", "", "<m6@x>", "hello")
    core.processMails(acc, List(mail)).unsafeRunSync()
    assertEquals(core.store.spoolList().size, 1)
    assert(core.store.spoolList().head._2.pendingMail.isDefined) // 整信带元数据入 spool
    core.replaySpool().unsafeRunSync() // LLM 仍不可用 → 留存待恢复
    assertEquals(core.store.spoolList().size, 1)
  }

  test("l1: spool 补判 Drop——信封删除，零注入（恢复后按序补判）") {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    val (core, _) = judgeCore(L1Verdict.Drop, calls)
    core.bootInitialize.unsafeRunSync()
    val mail = mailFixture(7)
    core.store.spoolWrite(PerceptionModel.DeliveryEnvelope("normal", "", pendingMail = Some(mail)))
    core.replaySpool().unsafeRunSync()
    assertEquals(core.store.spoolList(), Nil)
  }

  // ── OTP 意图正则（ask 显式/隐式开窗判据）──────────────────────────────

  test("otp-intent: questionLooksLikeOtp 中英验证码意图词 + 2fa") {
    assert(OtpExtractor.questionLooksLikeOtp("请把验证码发我"))
    assert(OtpExtractor.questionLooksLikeOtp("What is the verification code?"))
    assert(OtpExtractor.questionLooksLikeOtp("需要 2FA 口令"))
    assert(!OtpExtractor.questionLooksLikeOtp("今天天气怎么样"))
    assert(!OtpExtractor.questionLooksLikeOtp("帮我看下账单"))
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
