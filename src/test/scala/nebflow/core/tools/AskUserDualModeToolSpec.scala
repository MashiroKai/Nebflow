package nebflow.core.tools

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.{ActorPath, ActorRef}
import nebflow.actor.{AgentCommand, AgentDef}
import nebflow.shared.{AttachContract, PathUtil}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * **非阻塞-only + 顶层附件** 的工具级验收（root 2026-10-02 令 #462 裁①/②/③/④；
 * 改写自「工具面按角色分化批」的运行期兜底闸 + 可达性预检验收，**强度不降**）：
 *
 *  - **面外参数闸**（裁②「不保留 `mode` 键」）：携带 `mode`（任何取值、任何身份）
 *    ⇒ 显式可判读 `ToolError`（不按取值分叉、不静默忽略），且闸在任何副作用之前
 *    （stub agent ref 零消息 —— 无 askUser 帧、无 hub 槽位、无等待态）；
 *  - **零拒绝面**（裁①「A 全量非阻塞」）：general/depth=1、kernel/depth=1、
 *    `agentDef=None` 三形态**都不再**有 root-only 拒绝 ⇒ 一律拿到非阻塞 ack 并派发；
 *  - **无预检**（裁④）：无 hub / 无 root 窗口**不再** fail-closed ⇒ 照常派发，
 *    承接面在 hub 扇出 / pending（全链读数见 `AskUserDualModeRuntimeSpec`）；
 *  - **附件判据**（裁③）：顶层可选参数，逐形态拒绝（非绝对 / `~` / `.`·`..` /
 *    不存在 / 非普通文件 / 凭据域 / 越界 / 件数 / 大小）—— 判据本体零复制
 *    （只经 `FilePolicyPort` 发问，见 `AskUserAttachmentSpec` 的机械读数）；
 *  - **ack 文案**：机器可读（requestId + 问题数 + 附件数）+ 明确「不要等待」+
 *    未答兜底指令；
 *  - **askGuard 仍第一顺位**：headless 恒拒，且拒绝发生在 `mode` 闸之前。
 *
 * 真实 AgentActor / hub 侧的全链读数见 `AskUserDualModeRuntimeSpec`。
 */
class AskUserDualModeToolSpec extends FunSuite:

  private val generalDef = AgentDef(name = "general", description = "", tools = Nil)
  private val nebulaDef = AgentDef(name = "Nebula", description = "", tools = Nil)
  private val kernelDef = AgentDef(name = "kernel", description = "", tools = Nil)

  /**
   * 记录式 stub agent ref：`!` 落 sink；`?` 记录消息后**立即**用 `answers`
   * 回复（模拟「人在窗口点答」）——工具侧不再等待任何东西，桩只为断言零副作用面。
   */
  private def recordingRef(
    sink: scala.collection.mutable.ListBuffer[AgentCommand],
    answers: List[String] = List("A")
  ): ActorRef[AgentCommand] =
    new ActorRef[AgentCommand]:
      val path: ActorPath = ActorPath("__rec", Nil)
      def !(msg: AgentCommand): IO[Unit] = IO { sink += msg; () }
      def ?[R](makeMsg: ActorRef[R] => AgentCommand, t: Option[FiniteDuration]): IO[R] =
        for
          d <- Deferred[IO, R]
          replyRef = new ActorRef[R]:
            val path: ActorPath = ActorPath("__rec-reply", Nil)
            def !(m: R): IO[Unit] = d.complete(m).void
            def ?[Q](mk: ActorRef[Q] => R, tt: Option[FiniteDuration]): IO[Q] =
              IO.raiseError(new UnsupportedOperationException("no nested asks on the recording ref"))
          msg = makeMsg(replyRef)
          _ <- IO { sink += msg; () }
          // 测试桩：AskUser 的 reply 载荷恒为 List[String]（工具侧只走这一条），
          // 泛型 R 在此被擦除 ⇒ 直接投答案即可（无类型不安全的生产面）。
          _ <- replyRef ! answers.asInstanceOf[R]
          r <- d.get
        yield r

  private def ctxFor(
    defn: AgentDef,
    depth: Int,
    ref: Option[ActorRef[AgentCommand]],
    shared: Option[nebflow.agent.SharedResources] = None
  ): ToolContext =
    ToolContext(
      projectRoot = "",
      sessionId = Some("probe-session"),
      rootSessionId = Some("probe-session"),
      agentDef = Some(defn),
      depth = depth,
      agentActorRef = ref,
      sharedResources = shared
    )

  private val twoQuestions = JsonObject(
    "questions" -> io.circe.Json.arr(
      io.circe.Json
        .obj("question" -> "picked?".asJson, "options" -> io.circe.Json.arr(io.circe.Json.obj("label" -> "A".asJson)))
    )
  )

  // ============================================================
  // 面外参数闸（裁②）：`mode` 出现即拒
  // ============================================================

  test("面外参数闸: mode 缺席 ⇒ 放行；任何取值/任何身份 ⇒ 显式可判读 ToolError") {
    assert(AskUserQuestionTool.rejectRetiredModeParam(JsonObject.empty).isEmpty, "缺席被误拒")

    val values = List(
      "non-blocking".asJson,
      "blocking".asJson,
      "nonblocking".asJson, // 拼写
      "Non-Blocking".asJson, // 大小写
      "".asJson, // 空串
      123.asJson, // 类型不对
      io.circe.Json.True // 布尔（旧设想的 boolean 形态）
    )
    values.foreach { v =>
      AskUserQuestionTool.rejectRetiredModeParam(JsonObject("mode" -> v)) match
        case Some(err) =>
          assert(err.message.contains(AskUserQuestionTool.ModeParamRetiredCode), s"错误码缺失：${err.message}")
          assert(err.message.contains("does not accept a `mode` parameter"), s"文案不可判读：${err.message}")
          assert(err.message.contains(v.noSpaces), s"文案未回显入参：${err.message}")
        case None => fail(s"mode=$v 被静默接受（裁②「不保留键」未落地）")
    }
  }

  test("面外参数闸: 拒绝发生在任何副作用之前（stub agent ref 零消息）+ 与身份无关") {
    for (defn, depth) <- List((nebulaDef, 0), (generalDef, 1), (kernelDef, 1)) do
      val sink = scala.collection.mutable.ListBuffer.empty[AgentCommand]
      val ctx = ctxFor(defn, depth, Some(recordingRef(sink)))
      val input = twoQuestions.add("mode", "non-blocking".asJson)
      AskUserQuestionTool.call(input, ctx).unsafeRunSync() match
        case Left(err) => assert(err.message.contains(AskUserQuestionTool.ModeParamRetiredCode), err.message)
        case Right(ok) => fail(s"${defn.name}/depth=$depth 的带 mode 调用被放行：$ok")
      assertEquals(sink.toList, Nil, s"${defn.name} 的拒绝之后仍有副作用（已派发 AskUser）")
  }

  test("askGuard 仍第一顺位: headless 下连带 mode 的调用也恒拒（且先于 mode 闸）") {
    val sink = scala.collection.mutable.ListBuffer.empty[AgentCommand]
    val ctx = ctxFor(nebulaDef, 0, Some(recordingRef(sink)))
    val input = twoQuestions.add("mode", "non-blocking".asJson)
    // 纯判据面：headless=true 恒拒（文案 = HeadlessErrorMessage）
    assertEquals(
      AskUserQuestionTool.askGuard(headless = true).map(_.message),
      Some(AskUserQuestionTool.HeadlessErrorMessage)
    )
    assert(
      AskUserQuestionTool.askGuard(headless = false).isEmpty,
      "askGuard(headless=false) 不应拦"
    )
    // 源码级顺序 pin：`call()` 里 askGuard 分支先于 rejectRetiredModeParam 分支
    // （headless 是最高顺位，不被参数闸降级）—— 见下表 test。
    assert(input("mode").isDefined)
    assertEquals(sink.toList, Nil)
  }

  test("源码级 pin: call() 的闸序 = askGuard → 面外参数闸 → askUser（且 askUser 无 mode 分叉）") {
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "AskUserQuestionTool.scala")
    val iGuard = src.indexOf("askGuard() match")
    val iReject = src.indexOf("rejectRetiredModeParam(input) match")
    val iAsk = src.indexOf("case None => askUser(input, ctx)")
    assert(iGuard > 0 && iReject > iGuard && iAsk > iReject, "call() 的闸序被改动（headless 必须最高顺位）")
    // 退役符号零残留（工具源文件内）
    for retired <- List("parseMode", "AskMode", "rootVariant", "schemaRoot", "rootWindowReachable", "preflight")
    do assert(!src.contains(retired), s"已退役符号 `$retired` 仍在工具源文件里")
  }

  // ============================================================
  // 零拒绝面（裁①）+ 无预检（裁④）：全身份派发 + 非阻塞 ack
  // ============================================================

  test("裁① 全量非阻塞: 节点/内核/无 agentDef 三形态都不再被 root-only 门拒绝") {
    for (defn, depth, label) <- List(
        (nebulaDef, 0, "root"),
        (generalDef, 1, "node(depth=1)"),
        (kernelDef, 1, "kernel(depth=1)")
      )
    do
      val sink = scala.collection.mutable.ListBuffer.empty[AgentCommand]
      val ctx = ctxFor(defn, depth, Some(recordingRef(sink)))
      AskUserQuestionTool.call(twoQuestions, ctx).unsafeRunSync() match
        case Right(ack) =>
          assert(ack.contains("non-blocking:"), s"$label 未拿到非阻塞 ack：$ack")
          assert(!ack.contains("ASKUSER_NONBLOCK_NOT_ROOT"), s"$label 仍被 root-only 门拒绝：$ack")
        case Left(err) => fail(s"$label 的常规提问被拒：${err.message}")
      sink.toList match
        case List(cmd: AgentCommand.AskUser) =>
          assertEquals(cmd.items.size, 1)
          assertEquals(cmd.attachments, Nil, "无附件调用点派发了非空附件")
          // 工具侧一律非阻塞 ⇒ 恒不置 awaitsAnswer
          assertEquals(cmd.awaitsAnswer, false, "工具侧派发被标成等待答复（造出永不解除的等待）")
        case other => fail(s"$label 期望恰好一条 AskUser 派发，实得：$other")
  }

  test("裁④ 无预检: sharedResources 缺席（无 hub 可查）也不再 fail-closed") {
    val sink = scala.collection.mutable.ListBuffer.empty[AgentCommand]
    val ctx = ctxFor(nebulaDef, 0, Some(recordingRef(sink)), shared = None)
    AskUserQuestionTool.call(twoQuestions, ctx).unsafeRunSync() match
      case Right(ack) =>
        assert(ack.contains("non-blocking:"), ack)
        assert(!ack.contains("ASKUSER_NONBLOCK_NO_ROOT_WINDOW"), s"预检腿未去除：$ack")
      case Left(err) => fail(s"无 sharedResources 时仍被预检拒绝（裁④ 未落地）：${err.message}")
    assertEquals(sink.toList.size, 1, "问题未派发（承接面拿不到它）")
  }

  test("无 agent 会话: agentActorRef=None ⇒ 显式报错（不悬挂、不静默）") {
    val ctx = ToolContext(projectRoot = "", sessionId = Some("rest-call"), agentDef = Some(nebulaDef), depth = 0)
    AskUserQuestionTool.call(twoQuestions, ctx).unsafeRunSync() match
      case Left(err) => assert(err.message.contains("requires agent actor"), err.message)
      case Right(ok) => fail(s"无 agent 会话仍返回成功：$ok")
  }

  // ============================================================
  // ack 文案（唯一回执面）
  // ============================================================

  test("ack 文案: 机器可读（requestId + 问题数 + 附件数）+ 明确「不要等待」+ 未答兜底指令") {
    val items = AskUserQuestionTool.parseItems(
      io.circe.Json
        .arr(
          io.circe.Json
            .obj("question" -> "q1".asJson, "options" -> io.circe.Json.arr(io.circe.Json.obj("label" -> "A".asJson)))
        )
        .asArray
        .get
    )
    val ack = AskUserQuestionTool.nonBlockingAck(items, "abcdef01")
    assert(ack.contains("requestId=abcdef01"), ack)
    assert(ack.contains("1 question(s)"), ack)
    assert(ack.contains("do not wait"), ack)
    assert(ack.contains("best judgment"), ack)
    assert(!ack.contains("attachment(s)"), s"无附件时出现了附件段（字节漂移）：$ack")
    // 附件在场 ⇒ 附件数进 ack（机器可读），其余文案逐字不变
    val withAtt = AskUserQuestionTool.nonBlockingAck(items, "abcdef01", List("/a/b.pdf", "/a/c.png"))
    assert(withAtt.contains("· 2 attachment(s)"), withAtt)
    assert(withAtt.replace(" · 2 attachment(s)", "") == ack, s"附件段改动了其余文案：$withAtt")
  }

  // ============================================================
  // 附件判据（裁③）：逐形态拒绝
  // ============================================================

  // 附件判据要求实现方接线（生产 = GatewayMain 装配；spec 自接线，先例
  // `FileRefsServabilityScopeSpec:44`）。答案桥端口同理：生产由 `SharedResources`
  // 构造行注册（`SharedResources.scala:419`），本 spec 不构造 SharedResources ⇒
  // 自接线到**同一**生产实现（不复制、不伪造第二份桥）。
  nebflow.core.FilePolicyPort.install(nebflow.gateway.NfFilePolicy)
  nebflow.core.AskUserAnswerPort.install(
    new nebflow.core.AskUserAnswerPort.Face:
      def ref(
        target: ActorRef[AgentCommand],
        items: List[nebflow.shared.AskItem],
        requestId: String,
        ctx: ToolContext
      ): ActorRef[List[String]] = nebflow.agent.AskUserAnswerBridge.ref(target, items, requestId, ctx)
  )

  private var savedRoot: os.Path = scala.compiletime.uninitialized
  private val made = scala.collection.mutable.ListBuffer.empty[Path]

  override def beforeEach(context: BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    super.beforeEach(context)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    made.foreach { d =>
      if Files.exists(d) then
        val s = Files.walk(d)
        try s.sorted(java.util.Comparator.reverseOrder()).forEach(x => Files.deleteIfExists(x))
        finally s.close()
    }
    made.clear()
    super.afterEach(context)

  /** 本次用例独占的数据根（`afterEach` 清理）。 */
  private def freshRoot(label: String): Path =
    val d = Files.createTempDirectory(s"askuser-attach-$label-")
    made += d
    d

  /** 数据根内的可服务文件（`projects/` 首段在 `NfDataRootAllowlist` 内）。 */
  private def servedFile(root: Path, rel: String, bytes: Int = 32): Path =
    val p = root.resolve(rel)
    Files.createDirectories(p.getParent)
    Files.write(p, Array.fill(bytes)(0x41.toByte))
    p

  private def assertRejected(paths: List[String], code: String, label: String = ""): ToolError =
    AskUserQuestionTool.validateAttachments(paths) match
      case Left(err) =>
        assert(err.message.contains(code), s"$label 错误码不符（期望 $code）：${err.message}")
        err
      case Right(ok) => fail(s"$label 期望拒绝（$code），实得过闸：$ok")

  test("附件形态: 顶层可选 —— parseAttachments 缺省/非数组 ⇒ Nil；非字符串项丢弃；校验过闸原样返回") {
    assertEquals(AskUserQuestionTool.parseAttachments(JsonObject.empty), Nil)
    assertEquals(AskUserQuestionTool.parseAttachments(JsonObject("attachments" -> "not-an-array".asJson)), Nil)
    assertEquals(
      AskUserQuestionTool.parseAttachments(JsonObject("attachments" -> io.circe.Json.arr("a".asJson, 7.asJson))),
      List("a")
    )
    val root = freshRoot("ok")
    val f = servedFile(root, "projects/demo/a.pdf")
    PathUtil.setDataRoot(os.Path(root))
    assertEquals(AskUserQuestionTool.validateAttachments(List(f.toString)), Right(List(f.toString)))
  }

  test("附件拒绝①: 非绝对路径 / `~` / `..`·`.` 词法（禁折叠、禁展开）") {
    val root = freshRoot("lexical")
    val f = servedFile(root, "projects/demo/a.pdf")
    PathUtil.setDataRoot(os.Path(root))
    assertRejected(List("projects/demo/a.pdf"), "ASKUSER_ATTACH_NOT_ABSOLUTE", "相对路径")
    assertRejected(List("~/Documents/a.pdf"), "ASKUSER_ATTACH_TILDE", "`~`")
    assertRejected(List(root.resolve("projects/../projects/demo/a.pdf").toString), "ASKUSER_ATTACH_RELATIVE_SEGMENT", "`..`")
    assertRejected(List(root.resolve("./projects/demo/a.pdf").toString), "ASKUSER_ATTACH_RELATIVE_SEGMENT", "`.`")
    // 🔴 词法拒**不**看文件是否真存在 ⇒ 上面第 3/4 条命中的是归一化后的真实文件，
    // 仍然被拒（禁自动折叠 = 审计面可比）。
    assert(Files.exists(f), "夹具自身不成立")
  }

  test("附件拒绝②: 不存在 / 目录 / 不可读 ⇒ 各自显式拒绝") {
    val root = freshRoot("existence")
    val dir = root.resolve("projects/demo")
    Files.createDirectories(dir)
    PathUtil.setDataRoot(os.Path(root))
    assertRejected(List(dir.resolve("nope.pdf").toString), "ASKUSER_ATTACH_UNREADABLE", "不存在")
    assertRejected(List(dir.toString), "ASKUSER_ATTACH_NOT_REGULAR", "目录")
  }

  test("附件拒绝③: 越界（不在可读域）/ 凭据域 —— 判据经 FilePolicyPort，文案只给域清单名") {
    val root = freshRoot("domain")
    val outside = servedFile(root, "top.pdf") // dataRoot 下的非白名单首段
    val secret = servedFile(root, "secrets/secret.pdf") // 凭据段
    PathUtil.setDataRoot(os.Path(root))
    val e1 = assertRejected(List(outside.toString), "ASKUSER_ATTACH_OUT_OF_DOMAIN", "越界")
    assert(e1.message.contains(AskUserQuestionTool.ReadableDomainLabel), s"文案未给域清单名：${e1.message}")
    // 🔴 文案只给**域清单名**，不给宿主白名单根的物理全貌：即便入参路径本身被回显，
    // 也不得出现「端点根/白名单根」的其它绝对路径（`/Users/<name>/.nebflow` 一类）。
    assert(
      !e1.message.contains(PathUtil.dataRoot.toString) || e1.message.contains(outside.toString),
      s"文案泄漏宿主数据根：${e1.message}"
    )
    assert(!e1.message.contains(System.getProperty("user.home")), s"文案泄漏 home：${e1.message}")
    assertRejected(List(secret.toString), "ASKUSER_ATTACH_OUT_OF_DOMAIN", "凭据段（先被凭据阶梯拦下）")
  }

  test("附件拒绝④: 符号链接越界 ⇒ 按真实路径拒（无「按原串判」旁路）") {
    val root = freshRoot("symlink")
    // 真身 = 同一数据根内**被拒**的位置（非白名单首段）⇒ 链接串在域内、真身在域外（域 = 可读子树）
    val target = servedFile(root, "top.pdf")
    val link = root.resolve("projects/demo/link.pdf")
    Files.createDirectories(link.getParent)
    Files.createSymbolicLink(link, target)
    PathUtil.setDataRoot(os.Path(root))
    assertRejected(List(link.toString), "ASKUSER_ATTACH_OUT_OF_DOMAIN", "symlink 越界")
    // 对偶：链接指向**域内**真实文件 ⇒ 过闸（拒的是「真实路径越界」，不是「链接」本身）
    val inside = servedFile(root, "projects/demo/real.pdf")
    val okLink = root.resolve("projects/demo/ok-link.pdf")
    Files.createSymbolicLink(okLink, inside)
    assertEquals(
      AskUserQuestionTool.validateAttachments(List(okLink.toString)).isRight,
      true,
      "域内符号链接被误拒（判据应是「真实路径越界」，不是「符号链接」）"
    )
  }

  test("附件拒绝⑤: 件数超上限（单点常量 AttachContract.MaxAttachmentsPerMessage）") {
    val root = freshRoot("count")
    val files = (1 to AttachContract.MaxAttachmentsPerMessage + 1).map(i => servedFile(root, s"projects/demo/f$i.pdf")).toList
    PathUtil.setDataRoot(os.Path(root))
    val err = assertRejected(files.map(_.toString), AskUserQuestionTool.AttachCountCode, "件数超限")
    assert(err.message.contains(s"${AttachContract.MaxAttachmentsPerMessage}"), err.message)
    // 边界：恰好等于上限 ⇒ 过闸
    assertEquals(
      AskUserQuestionTool
        .validateAttachments(files.take(AttachContract.MaxAttachmentsPerMessage).map(_.toString))
        .isRight,
      true,
      "恰好等于上限被误拒"
    )
  }

  test("附件拒绝⑥: 单件大小超 100 MB（上限 = 前端 Canvas 打开闸同一把尺）") {
    assertEquals(AskUserQuestionTool.MaxAttachmentBytes, 100L * 1024 * 1024)
    val root = freshRoot("size")
    val big = servedFile(root, "projects/demo/big.pdf")
    // 稀疏文件：定位到上限 +1 字节写 1 字节（不真落 100 MB 数据）。
    val ch = java.nio.channels.FileChannel.open(big, java.nio.file.StandardOpenOption.WRITE)
    try
      ch.position(AskUserQuestionTool.MaxAttachmentBytes + 1)
      ch.write(java.nio.ByteBuffer.wrap(Array[Byte](0x41)))
    finally ch.close()
    assertEquals(java.nio.file.Files.size(big), AskUserQuestionTool.MaxAttachmentBytes + 2)
    PathUtil.setDataRoot(os.Path(root))
    assertRejected(List(big.toString), "ASKUSER_ATTACH_TOO_LARGE", "超 100 MB")
    // 边界对偶：恰好等于上限 ⇒ 过闸（上限语义是「≤」，不是「<」）
    val exact = servedFile(root, "projects/demo/exact.pdf")
    val ch3 = java.nio.channels.FileChannel.open(exact, java.nio.file.StandardOpenOption.WRITE)
    try
      ch3.position(AskUserQuestionTool.MaxAttachmentBytes - 1)
      ch3.write(java.nio.ByteBuffer.wrap(Array[Byte](0x41)))
    finally ch3.close()
    assertEquals(java.nio.file.Files.size(exact), AskUserQuestionTool.MaxAttachmentBytes)
    assertEquals(
      AskUserQuestionTool.validateAttachments(List(exact.toString)).isRight,
      true,
      "恰好等于单件上限的文件被误拒（上限语义应为 ≤）"
    )
  }

  test("附件拒绝⑦: 凭据形态名 / 非普通文件（设备）—— 各自显式拒绝，文案不点名命中表项") {
    val root = freshRoot("credential")
    val elsewhere = freshRoot("credential-outside") // 不在数据根内 ⇒ 判据落到凭据名面
    PathUtil.setDataRoot(os.Path(root))
    // 凭据形态 basename（判据本体 = `NfFilePolicy.NfCredentialNamePattern`，本文件零复制）
    val keyFile = elsewhere.resolve("id_rsa")
    Files.write(keyFile, "not-a-real-key".getBytes("UTF-8"))
    val e = assertRejected(List(keyFile.toString), "ASKUSER_ATTACH_CREDENTIAL", "凭据形态名")
    // 🔴 文案不叙述「命中了哪条表项」（只报输入路径 + 凭据类判词）
    assert(!e.message.contains("known credential shape"), s"文案叙述了命中表项：${e.message}")
    assert(e.message.contains(keyFile.toString), s"文案应原样回显输入路径：${e.message}")

    // 非普通文件：/dev/null 存在、可读，但不是普通文件 ⇒ 显式拒（判据在字节层之前）
    if Files.exists(java.nio.file.Paths.get("/dev/null")) then
      assertRejected(List("/dev/null"), "ASKUSER_ATTACH_NOT_REGULAR", "字符设备")
  }

  test("附件判据零复制（机械读数）: 工具源文件内无第二份白名单表/凭据正则/字符串 contains 判定") {
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "AskUserQuestionTool.scala")
    // 判据本体只能在 FilePolicyPort 实现侧（gateway.NfFilePolicy）出现
    for banned <- List("NfDataRootAllowlist", "NfCredentialNamePattern", "NfCredentialPathSegments", "NfExternalCredentialEntries")
    do assert(!src.contains(banned), s"工具层出现第二份判据表 `$banned`（禁复制）")
    // 唯一判据访问点 = FilePolicyPort 的两个问题
    val hits = "FilePolicyPort\\.port\\.(endpointVerdictLayer|credentialInodeHit)".r.findAllIn(src).toList
    assertEquals(
      hits.map(_.replace("FilePolicyPort.port.", "")).sorted,
      List("credentialInodeHit", "endpointVerdictLayer"),
      "FilePolicyPort 调用点不再是「两层各一」"
    )
  }

  test("附件全链: 通过校验的附件随 AskUser 派发（工具侧零改写），ack 带附件数") {
    val root = freshRoot("chain")
    val f = servedFile(root, "projects/demo/a.pdf")
    PathUtil.setDataRoot(os.Path(root))
    val sink = scala.collection.mutable.ListBuffer.empty[AgentCommand]
    val ctx = ctxFor(nebulaDef, 0, Some(recordingRef(sink)))
    val input = twoQuestions.add("attachments", io.circe.Json.arr(f.toString.asJson))
    AskUserQuestionTool.call(input, ctx).unsafeRunSync() match
      case Right(ack) =>
        assert(ack.contains("· 1 attachment(s)"), ack)
      case Left(err) => fail(s"合法附件被拒：${err.message}")
    sink.toList match
      case List(cmd: AgentCommand.AskUser) => assertEquals(cmd.attachments, List(f.toString))
      case other => fail(s"期望一条 AskUser（带附件），实得：$other")
  }

  test("附件拒绝 ⇒ 整次调用拒（不部分放行）: 一条合法 + 一条越界 ⇒ 零派发") {
    val root = freshRoot("partial")
    val good = servedFile(root, "projects/demo/good.pdf")
    val bad = servedFile(root, "top.pdf")
    PathUtil.setDataRoot(os.Path(root))
    val sink = scala.collection.mutable.ListBuffer.empty[AgentCommand]
    val ctx = ctxFor(nebulaDef, 0, Some(recordingRef(sink)))
    val input = twoQuestions.add("attachments", io.circe.Json.arr(good.toString.asJson, bad.toString.asJson))
    AskUserQuestionTool.call(input, ctx).unsafeRunSync() match
      case Left(err) => assert(err.message.contains("ASKUSER_ATTACH_OUT_OF_DOMAIN"), err.message)
      case Right(ok) => fail(s"部分放行：$ok")
    assertEquals(sink.toList, Nil, "被拒的调用仍派发了 AskUser（部分放行）")
  }

end AskUserDualModeToolSpec
