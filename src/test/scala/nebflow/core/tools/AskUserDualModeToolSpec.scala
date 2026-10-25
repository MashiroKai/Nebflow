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

  test("附件形态: 顶层可选 —— parseAttachments 缺省/非数组 ⇒ Nil；非字符串项丢弃；校验过闸返回解析后绝对路径") {
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

  test("附件形态·Pop 同判①: /tmp 等数据域外本机可读路径放行（09:41 翻转——目录域门撤除）") {
    // 判据 = Pop 文件面（存在 + 普通文件 + probeFile 的端点同一份阶梯）。临时目录
    // 本就在任何数据根之外 ⇒ 翻转后放行（改前此处被 OUT_OF_DOMAIN 拒——作者四撞之一）。
    val outside = freshRoot("outside-domain")
    val f = outside.resolve("report.md")
    Files.write(f, Array.fill(32)(0x41.toByte))
    PathUtil.setDataRoot(os.Path(freshRoot("unrelated-root")))
    assertEquals(AskUserQuestionTool.validateAttachments(List(f.toString)), Right(List(f.toString)))
  }

  test("附件形态·Pop 同判②: `~` 形态展开后放行，回传**解析后**绝对路径（home 注入隔离）") {
    val fakeHome = freshRoot("fake-home")
    val seeded = fakeHome.resolve("nebflow-attach-spec/notes.md")
    Files.createDirectories(seeded.getParent)
    Files.write(seeded, "# hello".getBytes("UTF-8"))
    val savedHome = System.getProperty("user.home")
    System.setProperty("user.home", fakeHome.toString)
    try
      // `~/...` 与 Pop 同一解析源（FileRefs.resolvePath 的 sys.props("user.home") 展开）。
      AskUserQuestionTool.validateAttachments(List("~/nebflow-attach-spec/notes.md")) match
        case Right(paths) => assertEquals(paths, List(seeded.toString), "`~` 必须展开为绝对路径回传")
        case Left(err) => fail(s"`~` 形态被拒（Pop 能挂的 AskUser 就能挂）：${err.message}")
    finally System.setProperty("user.home", savedHome)
  }

  test("附件形态·Pop 同判③: `.`/`..` 词法段折叠放行（与 Pop 的 normalize 同判，禁词法二把尺）") {
    val root = freshRoot("dotdot")
    val f = servedFile(root, "projects/demo/a.pdf")
    PathUtil.setDataRoot(os.Path(root))
    val folded = root.resolve("projects/../projects/demo/a.pdf")
    AskUserQuestionTool.validateAttachments(List(folded.toString)) match
      case Right(paths) => assertEquals(paths, List(f.toString), "折叠后回传规范绝对路径")
      case Left(err) => fail(s"词法折叠被拒（Pop 同判要求放行）：${err.message}")
  }

  test("附件拒绝①: 不存在 / 目录 / 非普通文件（/dev/null）⇒ 各自显式拒绝") {
    val root = freshRoot("existence")
    val dir = root.resolve("projects/demo")
    Files.createDirectories(dir)
    PathUtil.setDataRoot(os.Path(root))
    assertRejected(List(dir.resolve("nope.md").toString), "ASKUSER_ATTACH_NOT_FOUND", "不存在")
    assertRejected(List(dir.toString), "ASKUSER_ATTACH_NOT_REGULAR", "目录")
    // /dev/null 字符设备前提是 POSIX 面（Windows 无此设备文件，引擎分支语义不同）⇒ 门控。
    if !nebflow.core.CredentialFileAcl.isWindows(nebflow.core.CredentialFileAcl.currentOsName)
      && Files.exists(java.nio.file.Paths.get("/dev/null"))
    then assertRejected(List("/dev/null"), "ASKUSER_ATTACH_NOT_REGULAR", "字符设备")
  }

  test("附件拒绝②: 凭据身份（形态名）与凭据 inode（secrets 子树）—— 域外逃逸面零松动") {
    val root = freshRoot("credential")
    PathUtil.setDataRoot(os.Path(root))
    // 凭据形态 basename（判据本体 = NfFilePolicy.NfCredentialNamePattern，本文件零复制）。
    // 落在**数据域外**的普通临时目录——拒绝只能来自凭据身份层，不能来自目录域（门已撤）。
    val elsewhere = freshRoot("credential-outside")
    val keyFile = elsewhere.resolve("id_rsa")
    Files.write(keyFile, "not-a-real-key".getBytes("UTF-8"))
    val e = assertRejected(List(keyFile.toString), "ASKUSER_ATTACH_CREDENTIAL", "凭据形态名")
    // 09:41 令「报错自解释真实拒绝因」：文案回显输入路径 + 端点同一份拒因（与 Pop
    // 警告同源逐字）+ 双维度尾注。旧「不点名表项」redaction 随 OUT_OF_DOMAIN 退役
    // （该 redaction 是给浏览器 403 面的；工具错误面向模型，Pop 警告本就全量引用）。
    assert(e.message.contains(keyFile.toString), s"文案应回显输入路径：${e.message}")
    assert(e.message.contains(AskUserQuestionTool.RejectionTypeNote), s"双维度尾注缺失：${e.message}")
    // 数据根 secrets 子树 = R2 inode 快照收口（scanCredentialInodes 覆盖）。
    val secret = servedFile(root, "secrets/secret.pdf")
    assertRejected(List(secret.toString), "ASKUSER_ATTACH_CREDENTIAL", "凭据 inode")
  }

  test("附件拒绝③: 符号链接按真实路径判 —— 链接到凭据真身 ⇒ 拒；链接到普通文件 ⇒ 过（无「按原串判」旁路）") {
    nebflow.SymlinkProbe.assumeSymlinks() // Windows symlink 特权门（能力缺失 ⇒ ignore）
    val root = freshRoot("symlink")
    val secret = servedFile(root, "secrets/secret-target.pdf")
    val link = root.resolve("projects/demo/link.pdf")
    Files.createDirectories(link.getParent)
    Files.createSymbolicLink(link, secret)
    PathUtil.setDataRoot(os.Path(root))
    assertRejected(List(link.toString), "ASKUSER_ATTACH_CREDENTIAL", "symlink 指向凭据")
    // 对偶：链接指向普通文件 ⇒ 过闸（拒的是「真实路径是凭据」，不是「符号链接」本身）。
    val plain = servedFile(root, "projects/demo/real.md")
    val okLink = root.resolve("projects/demo/ok-link.md")
    Files.createSymbolicLink(okLink, plain)
    assertEquals(
      AskUserQuestionTool.validateAttachments(List(okLink.toString)).isRight,
      true,
      "指向普通文件的符号链接被误拒"
    )
  }

  test("附件拒绝④: 件数超上限（单点常量 AttachContract.MaxAttachmentsPerMessage）") {
    val root = freshRoot("count")
    val files =
      (1 to AttachContract.MaxAttachmentsPerMessage + 1).map(i => servedFile(root, s"projects/demo/f$i.pdf")).toList
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

  test("附件拒绝⑤: 单件大小超 100 MB（上限 = 前端 Canvas 打开闸同一把尺）") {
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
      AskUserQuestionTool
        .validateAttachments(List(exact.toString))
        .isRight,
      true,
      "恰好等于单件上限的文件被误拒（上限语义应为 ≤）"
    )
  }

  test("附件拒绝⑥: 双维度自解释尾注随每条拒绝文案（09:35 令②(b)：报错说明哪一维失败、哪一维无关）") {
    val root = freshRoot("dual-dim")
    PathUtil.setDataRoot(os.Path(root))
    val e = assertRejected(List(root.resolve("projects/demo/ghost.md").toString), "ASKUSER_ATTACH_NOT_FOUND", "不存在")
    assert(e.message.contains(AskUserQuestionTool.RejectionTypeNote), s"尾注缺失：${e.message}")
    assert(
      e.message.contains("never the file type"),
      s"尾注未说明「类型面永不是拒绝维」：${e.message}"
    )
  }

  test("附件判据零复制（机械读数）: 工具源文件内无第二份判据表、无直连 FilePolicyPort（判据经 FileRefs/Pop 同一入口）") {
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "AskUserQuestionTool.scala")
    // 判据本体只能在 FilePolicyPort 实现侧（gateway.NfFilePolicy）出现
    for banned <- List(
        "NfDataRootAllowlist",
        "NfCredentialNamePattern",
        "NfCredentialPathSegments",
        "NfExternalCredentialEntries",
        "NfFileAllowedExt",
        "AllowedExtensions"
      )
    do assert(!src.contains(banned), s"工具层出现第二份判据表 `$banned`（禁复制）")
    // 09:41 令「禁自造第二套域规则/第二权威」：工具层不直连端口（端口问答收拢在
    // FileRefs——Pop 的同一入口），解析/探针 = FileRefs 两个单点。
    assert(!src.contains("FilePolicyPort.port."), "工具层直连 FilePolicyPort（应经 FileRefs 同一入口）")
    assert(src.contains("FileRefs.resolveCandidates"), "解析未走 Pop 同一解析源 FileRefs.resolveCandidates")
    assert(src.contains("FileRefs.probeFile"), "准入未走 Pop 同一探针 FileRefs.probeFile")
  }

  test("附件全链: 通过校验的附件以**解析后绝对路径**随 AskUser 派发，ack 带附件数") {
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

  test("附件拒绝 ⇒ 整次调用拒（不部分放行）: 一条合法 + 一条不存在 ⇒ 零派发") {
    val root = freshRoot("partial")
    val good = servedFile(root, "projects/demo/good.md")
    val bad = root.resolve("projects/demo/ghost.md")
    PathUtil.setDataRoot(os.Path(root))
    val sink = scala.collection.mutable.ListBuffer.empty[AgentCommand]
    val ctx = ctxFor(nebulaDef, 0, Some(recordingRef(sink)))
    val input = twoQuestions.add("attachments", io.circe.Json.arr(good.toString.asJson, bad.toString.asJson))
    AskUserQuestionTool.call(input, ctx).unsafeRunSync() match
      case Left(err) => assert(err.message.contains("ASKUSER_ATTACH_NOT_FOUND"), err.message)
      case Right(ok) => fail(s"部分放行：$ok")
    assertEquals(sink.toList, Nil, "被拒的调用仍派发了 AskUser（部分放行）")
  }

end AskUserDualModeToolSpec
