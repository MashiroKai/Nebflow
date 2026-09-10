package nebflow.core.sandbox

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.tools.{BashTool, ToolContext}

import java.nio.file.Files

/**
 * 沙箱拆围栏批 **S3（拆宿主 Bash 包裹 + fail-closed 宿主路径退场）+ R4（provider
 * 语义与缺省值）+ §4.5（回退点机制化）** 的**定向行为测试**。
 *
 * 覆盖点（逐条对应 design 权威源）：
 *  - S3-a：宿主路径不再 fail-closed（§1.1 F8 / R7=g1）——`SandboxRuntime` 里
 *    backend 的 `available=false` 不再拦 Bash；顺带覆盖 Linux 隐性不可用的
 *    **代码链代理**（非 macOS 时 `SandboxBackend.probe` 恒 false，旧判据下 Bash
 *    整体不可用；新判据不读 probe，故解除。**无 Linux 实机 ⇒ 标未验证**）。
 *  - S3-b：`wrap` 接缝的宿主路径（provider=host ⇒ `Host.wrap` 恒 None ⇒ shell 走
 *    plain）与包裹路径（注册一个会包裹的后端 ⇒ 命令 argv 真被替换 = 接缝仍承重）。
 *  - R4：`provider` 语义与**缺省 host**；四取值解析；**非法取值不静默回落 host**。
 *  - U7：未实现 provider（container / auto）⇒ **显式失败**，且**不静默降级回宿主
 *    执行**（用命令副作用探针证「命令确实没跑」）。
 *  - §4.5：回退点可执行——`provider=local-process` 恢复包裹后端（argv 形状实证）；
 *    `enabled=false` 旧行为路径保留不删。
 *
 * 环境纪律：本机会话（旧引擎 spawn 的 Bash）自身在 Seatbelt 包裹内 ⇒ 嵌套
 * `sandbox-exec` 被拒（实测 rc=71），故 provider=local-process 的真实 probe 在
 * **本会话内恒失败**——该分支按「显式失败」断言，真实端到端需宿主重启后观测
 * （结果里显式标「未验证」）。探针文件全部即刻删除，零残留。
 */
class SandboxProviderSpec extends CatsEffectSuite:

  private val created = scala.collection.mutable.ListBuffer.empty[os.Path]

  override def beforeEach(context: munit.BeforeEach): Unit =
    // 每个用例从缺省态起步（provider=host），避免全局注册表串味
    SandboxRuntime.init(SandboxConfig())
    super.beforeEach(context)

  override def afterEach(context: munit.AfterEach): Unit =
    // 还原全局注册表：缺省 provider=host（并清空 providerFailure，防跨 suite 污染）
    SandboxRuntime.init(SandboxConfig())
    created.foreach(p => try os.remove.all(p) catch case _: Exception => ())
    created.clear()
    super.afterEach(context)

  private def freshDir(tag: String): os.Path =
    val d = os.Path(SandboxPolicy.canonicalize(Files.createTempDirectory(s"nb-s3-$tag").toAbsolutePath))
    created += d
    d

  private def ctxIn(tmp: os.Path, session: String): ToolContext =
    ToolContext(
      projectRoot = tmp.toString,
      sandbox = SandboxPolicy.forRoot(tmp, SandboxConfig()),
      sessionId = Some(session)
    )

  private def parse(json: String): SandboxConfig =
    SandboxConfig.load(Some(io.circe.parser.parse(json).toOption.get))

  /** 只包裹不讲理的假后端：wrap 把 argv 整体替换为一个可辨识的 argv——
    * 用于证明「wrap 接缝确实会包裹」（等价于 provider=local-process 时 Seatbelt
    * 产出的 `sandbox-exec -p … -- /bin/bash …` 形态）。 */
  private def wrappedStub(marker: String) = new SandboxBackend:
    val name = "stub-wrapped"
    val available = true
    def wrap(argv: List[String], policy: SandboxPolicy): Option[List[String]] =
      Some(List("/bin/echo", marker))

  // ------------------------------------------------------------------
  // R4：provider 语义与缺省值
  // ------------------------------------------------------------------

  test("R4: provider 缺省 = host（缺省构造 / absent 配置 / 非法节点 fail-safe）") {
    assertEquals(SandboxConfig().provider, SandboxProvider.Host, "缺省构造必须 host")
    assertEquals(SandboxConfig.load(None).provider, SandboxProvider.Host, "无 sandbox 节 ⇒ host")
    assertEquals(parse("""{"enabled":true}""").provider, SandboxProvider.Host, "无 provider 键 ⇒ host")
    assertEquals(parse("""{"provider":"host"}""").provider, SandboxProvider.Host)
    assertEquals(parse("""{"provider":"LOCAL-PROCESS"}""").provider, SandboxProvider.LocalProcess,
      "取值大小写不敏感（trim + toLowerCase）")
    assertEquals(parse("""{"enabled":true}""").providerError, None, "合法配置不得带 providerError")
  }

  test("R4: provider 四取值可解析（host / local-process / container / auto）") {
    assertEquals(parse("""{"provider":"local-process"}""").provider, SandboxProvider.LocalProcess)
    assertEquals(parse("""{"provider":"container"}""").provider, SandboxProvider.Container)
    assertEquals(parse("""{"provider":"auto"}""").provider, SandboxProvider.Auto)
    assertEquals(SandboxProvider.names, List("host", "local-process", "container", "auto"))
  }

  test("R4/U7: 非法 provider 取值 ⇒ providerError 非空（不静默回落 host）+ init 后 Bash 显式失败") {
    val cfg = parse("""{"provider":"seatbelt"}""")
    assert(cfg.providerError.exists(_.contains("seatbelt")), s"旧名须显式报错: ${cfg.providerError}")
    assert(cfg.providerError.exists(_.contains("local-process")), "错误文案须给出新名（可修）")
    val garbled = parse("""{"provider":"docker"}""")
    assert(garbled.providerError.isDefined, "docker（旧值）不得被静默接受")
    assert(parse("""{"provider":"nonsense"}""").providerError.isDefined, "未知取值不得被静默接受")
    // 类型错误也不静默
    val wrongType = parse("""{"provider":3}""")
    assert(wrongType.providerError.isDefined, "非字符串取值不得被静默接受")
    // 执行期：显式失败（不是静默跑宿主）
    SandboxRuntime.init(cfg)
    assert(SandboxRuntime.failureCause.isDefined, "非法取值 ⇒ provider 面显式失败")
    assertEquals(SandboxRuntime.provider, SandboxProvider.Host, "失败态 provider 记录（文案用）")
  }

  // ------------------------------------------------------------------
  // S3-a：宿主路径不再 fail-closed（含 Linux 隐性不可用解除的代码链代理）
  // ------------------------------------------------------------------

  test("S3-a: provider=host 下 backend.available=false 不再拦 Bash（旧判据失去对象）") {
    val tmp = freshDir("failopen")
    val ctx = ctxIn(tmp, "nb-s3-failopen")
    SandboxRuntime.init(SandboxConfig())                       // provider=host
    SandboxRuntime.backend = SandboxBackend.Unavailable         // 旧 §A.4-4 的「probe 失败」
    assertEquals(SandboxRuntime.failureCause, None, "provider=host 不得因后端不可用而失败")
    BashTool.call(JsonObject("command" -> "echo NBX_S3_HOST_RUNS".asJson), ctx).unsafeRunSync() match
      case Right(out) =>
        assert(out.contains("NBX_S3_HOST_RUNS"), s"宿主路径必须直跑: $out")
        assert(!out.contains("SANDBOX_UNAVAILABLE"), out)
      case Left(err) => fail(s"宿主路径不得再 fail-closed（R7=g1）: ${err.message}")
  }

  test("S3-a: Linux 隐性不可用解除——非 macOS probe 恒 false 的旧链条已不参与判据（代码链代理，Linux 实机未验证）") {
    val tmp = freshDir("linuxchain")
    val ctx = ctxIn(tmp, "nb-s3-linuxchain")
    // 代码链代理：probe 非 macOS 恒 false（SandboxBackend.Seatbelt.probe 的 isMac 早退）
    // ⇒ 旧判据 `enabled && !current.available` + 缺省 bashFailIfUnavailable=true 会拦掉
    // 全部 Bash；新判据只看 providerFailure，与平台 probe 无关。
    assert(!SandboxBackend.Seatbelt.probe("definitely-not-a-real-path"),
      "probe 对不可用路径须 false（fail-safe）")
    SandboxRuntime.init(SandboxConfig())                        // 缺省 host
    assertEquals(SandboxRuntime.failureCause, None, "缺省 host 不依赖任何 probe")
    BashTool.call(JsonObject("command" -> "echo NBX_S3_LINUX_PROXY".asJson), ctx).unsafeRunSync() match
      case Right(out) => assert(out.contains("NBX_S3_LINUX_PROXY"), out)
      case Left(err)  => fail(s"缺省 host 不得拦 Bash（Linux 实机未验证，此处为代码链代理）: ${err.message}")
  }

  // ------------------------------------------------------------------
  // S3-b：wrap 接缝（宿主路径不包裹 / 注册包裹后端即恢复包裹）
  // ------------------------------------------------------------------

  test("S3-b: 接缝承重——provider=host 不包裹；注册会包裹的后端 ⇒ 命令真被包裹（回退机制机械支点）") {
    val tmp = freshDir("seam")
    SandboxRuntime.init(SandboxConfig()) // host：Host.wrap 恒 None ⇒ shell 走 plain
    assertEquals(SandboxRuntime.current.wrap(List("bash", "-c", "true"), SandboxPolicy.forRoot(tmp, SandboxConfig())), None,
      "宿主路径下 wrap 必须 None（⇒ shell 走 plain，宿主不再包裹）")
    // 宿主直跑：命令原文执行
    BashTool.call(JsonObject("command" -> "echo NBX_S3_PLAIN".asJson), ctxIn(tmp, "nb-s3-plain")).unsafeRunSync() match
      case Right(out) => assert(out.contains("NBX_S3_PLAIN"), out)
      case Left(err)  => fail(s"宿主直跑失败: ${err.message}")
    // 注册包裹后端（provider=local-process 的形态：wrap 产出包裹 argv）⇒ 同一接缝生效
    SandboxRuntime.backend = wrappedStub("NBX_S3_WRAPPED")
    BashTool.call(JsonObject("command" -> "echo NBX_S3_PLAIN".asJson), ctxIn(tmp, "nb-s3-wrapped")).unsafeRunSync() match
      case Right(out) =>
        assert(out.contains("NBX_S3_WRAPPED"), s"接缝必须把 argv 换成包裹形态: $out")
        assert(!out.contains("NBX_S3_PLAIN"), s"被包裹后原命令不得再直跑: $out")
      case Left(err) => fail(s"包裹路径不得失败: ${err.message}")
  }

  // ------------------------------------------------------------------
  // U7：未实现/不可用 provider = 明示失败，绝不静默回落宿主
  // ------------------------------------------------------------------

  test("U7: provider=container（本批未实现）⇒ 显式失败且命令不执行（无宿主回落）") {
    val tmp = freshDir("container")
    val probeFile = tmp / "container-should-not-exist.txt"
    SandboxRuntime.init(SandboxConfig(provider = SandboxProvider.Container))
    assert(SandboxRuntime.failureCause.exists(_.contains("未实现")), s"须显式失败: ${SandboxRuntime.failureCause}")
    BashTool.call(JsonObject("command" -> s"echo leaked > ${probeFile.toString}".asJson), ctxIn(tmp, "nb-s3-container")).unsafeRunSync() match
      case Left(err) =>
        assert(err.message.startsWith("SANDBOX_UNAVAILABLE"), err.message)
        assert(err.message.contains("NOT silently downgraded"), s"文案须声明未回落到宿主: ${err.message}")
        assert(!os.exists(probeFile), "显式失败 ⇒ 命令绝不能在宿主上执行（无静默降级）")
      case Right(out) => fail(s"未实现的 provider 必须显式失败，不得回落宿主执行: $out")
  }

  test("U7: provider=auto（本批未实现）⇒ 显式失败且命令不执行（无三段降级回宿主）") {
    val tmp = freshDir("auto")
    val probeFile = tmp / "auto-should-not-exist.txt"
    SandboxRuntime.init(SandboxConfig(provider = SandboxProvider.Auto))
    assert(SandboxRuntime.failureCause.exists(_.contains("auto")), s"须显式失败: ${SandboxRuntime.failureCause}")
    BashTool.call(JsonObject("command" -> s"echo leaked > ${probeFile.toString}".asJson), ctxIn(tmp, "nb-s3-auto")).unsafeRunSync() match
      case Left(err) => assert(err.message.startsWith("SANDBOX_UNAVAILABLE"), err.message)
      case Right(out) => fail(s"provider=auto 未实现 ⇒ 必须显式失败: $out")
    assert(!os.exists(probeFile), "命令不得在宿主上执行")
  }

  test("U7: provider=local-process 后端不可用（本会话语境：嵌套沙箱 probe 必败）⇒ 显式失败不降级") {
    val tmp = freshDir("localproc")
    SandboxRuntime.init(SandboxConfig(provider = SandboxProvider.LocalProcess))
    assertEquals(SandboxRuntime.provider, SandboxProvider.LocalProcess)
    assertEquals(SandboxRuntime.backend.name, "seatbelt", "回退点须装配 local-process 的执行面")
    if SandboxRuntime.backend.available then
      // 宿主未重启/未嵌套包裹的机器上 probe 通过：此时 Bash 恢复包裹（见 argv 形状用例）
      assertEquals(SandboxRuntime.failureCause, None)
    else
      assert(SandboxRuntime.failureCause.exists(_.contains("local-process")), s"须显式失败: ${SandboxRuntime.failureCause}")
      BashTool.call(JsonObject("command" -> "echo NBX_S3_NEVER".asJson), ctxIn(tmp, "nb-s3-localproc")).unsafeRunSync() match
        case Left(err) =>
          assert(err.message.startsWith("SANDBOX_UNAVAILABLE"), err.message)
          assert(!err.message.contains("NBX_S3_NEVER"), "拒绝文案不得包含命令执行结果")
        case Right(out) => fail(s"local-process 不可用时必须显式失败，不得静默回落宿主执行: $out")
  }

  // ------------------------------------------------------------------
  // §4.5：回退点机制化（可执行 + 有实证）
  // ------------------------------------------------------------------

  test("§4.5: 回退点恢复宿主 Bash 包裹——provider=local-process 的 Seatbelt 后端产出 sandbox-exec 包裹 argv") {
    val tmp = freshDir("rollback")
    val policy = SandboxPolicy.forRoot(tmp, SandboxConfig())
    // probe 通过路径的 argv 形状实证（注入恒真的 sandboxExecPath 以绕过嵌套环境限制；
    // 真实 probe 语义不变：src/main 的 Seatbelt.probe 逐字未改）
    val seatbelt = new SandboxBackend.Seatbelt("/usr/bin/true")
    assert(seatbelt.available, "注入恒真 probe 路径后 Seatbelt 须 available")
    assertEquals(
      seatbelt.wrap(List("bash", "-c", "echo hi"), policy).map(_.take(2)),
      Some(List("/usr/bin/true", "-p")),
      "local-process 的 wrap 必须是 `<sandboxExecPath> -p <profile> … -- /bin/bash …` 包裹形态"
    )
    val wrappedCmd = seatbelt.wrap(List("bash", "-c", "echo hi"), policy).get
    assert(wrappedCmd.contains("--") && wrappedCmd.last == "echo hi", wrappedCmd.toString)
    assert(wrappedCmd.contains("/bin/bash"), "包裹 argv 必须仍以 /bin/bash 执行命令（语义不变）")
    // 真实 Seatbelt 的 executable 常量未漂移（回退点兑现的是真 sandbox-exec）
    assertEquals(SandboxBackend.Seatbelt.SandboxExecPath, "/usr/bin/sandbox-exec")
  }

  test("§4.5: 旧行为回退点保留——enabled=false ⇒ 不 probe / 不包裹 / 不拦 Bash（与拆围栏前逐字一致）") {
    val tmp = freshDir("legacyoff")
    val ctx = ToolContext(
      projectRoot = tmp.toString,
      sandbox = SandboxPolicy.forRoot(tmp, SandboxConfig(enabled = false)),
      sessionId = Some("nb-s3-legacyoff")
    )
    SandboxRuntime.init(SandboxConfig(enabled = false))
    assertEquals(SandboxRuntime.failureCause, None, "旧行为回退态不得产生 provider 失败")
    assertEquals(SandboxRuntime.current.wrap(List("bash", "-c", "true"), ctx.sandbox), None, "旧行为不包裹")
    BashTool.call(JsonObject("command" -> "echo NBX_S3_LEGACY".asJson), ctx).unsafeRunSync() match
      case Right(out) => assert(out.contains("NBX_S3_LEGACY"), out)
      case Left(err)  => fail(s"enabled=false 回退态必须直跑（旧行为逐字保留）: ${err.message}")
    // 回退支点本身不删：forRoot 短路 off（无会话根 = 路径语义亦回旧）
    assert(SandboxPolicy.forRoot(tmp, SandboxConfig(enabled = false)).pathRoot.isEmpty)
  }

  test("§4.5: provider 语义与 enabled 回退的优先次序——enabled=false 覆盖 provider（WARN 而非静默）") {
    SandboxRuntime.init(SandboxConfig(enabled = false, provider = SandboxProvider.Container))
    assertEquals(SandboxRuntime.failureCause, None, "enabled=false = 用户显式选择旧行为（不 probe 不拦）")
    assertEquals(SandboxRuntime.provider, SandboxProvider.Host, "旧行为态 provider 落 host")
    // 反向：enabled=true 时 container 必须显式失败（不因任何回退开关被静默吞掉）
    SandboxRuntime.init(SandboxConfig(enabled = true, provider = SandboxProvider.Container))
    assert(SandboxRuntime.failureCause.isDefined)
  }
