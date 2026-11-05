package nebflow.core.delegate

import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * 模式参数决定论 spec（delegate-param-decisions 批 2026-11-04）——验收第 4/5 条
 * 的机械判据：
 *
 *  - **P1 零模型调用的类型级判据**：[[ModeParam.resolve]] 是纯函数（`Either[String,
 *    ModeDecision]`）——签名里没有 IO、没有端口、没有配置读取，spawn 路径上不存在
 *    任何可被模型调用的接缝（旧 `DelegateModeAllocator` 端口与
 *    `DelegateModeMachine` 执行体已删除）。
 *  - **P2 不填 ⇒ 确定性 kernel**（leg=default）。
 *  - **P3 显式 auto ⇒ 明确报错**，文案含引导语「请直接指定 kernel 或 dispatcher，
 *    分配器已移除」——不静默映射（静默映射会让「分配器有没有真的删掉」无法验证）。
 *  - **P4 配置面失效**：旧总闸 `delegate.dualMode.enabled` / `failopenMode` 在
 *    nebflow.json 里怎么写都不影响解析结果（那段配置读取代码已删除——本判据钉
 *    「口径已从配置面撤除」）。
 */
class ModeParamSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "mode-param")
    os.makeDir.all(tmp)
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def writeConfig(json: String): Unit =
    val p = tmp / nebflow.shared.Branding.configFileName
    os.write.over(p, json)

  test("P1 纯函数形态：resolve 返回 Either（无 IO / 无端口）——零模型调用是类型级保证") {
    val out: Either[String, ModeDecision] = ModeParam.resolve(Some("kernel"))
    assertEquals(out.map(_.mode), Right(DualModeConfig.ModeKernel))
    // 签名承重：返回类型是 Either（若哪天被改成 IO[...] 包裹，本文件第 43 行的类型
    // 注解与下面这条赋值会直接编译失败——类型级判据，无需运行期断言）。
    val typed: Either[String, ModeDecision] = ModeParam.resolve(None)
    assertEquals(typed.map(_.mode), Right(DualModeConfig.ModeKernel))
  }

  test("P2 不填 mode ⇒ 确定性 kernel（leg=default，理由里写明 zero model calls）") {
    val d = ModeParam.resolve(None).getOrElse(fail("omitted mode must resolve"))
    assertEquals(d.mode, DualModeConfig.ModeKernel)
    assertEquals(d.leg, ModeLeg.Default)
    assert(d.reason.contains("zero model calls"), d.reason)
    assertEquals(ModeParam.default.mode, DualModeConfig.ModeKernel)
  }

  test("P2b 显式 kernel/dispatcher 原样采纳（大小写/空白容忍，leg=param）") {
    assertEquals(ModeParam.resolve(Some("kernel")).map(_.mode), Right(DualModeConfig.ModeKernel))
    assertEquals(ModeParam.resolve(Some("  Dispatcher ")).map(_.mode), Right(DualModeConfig.ModeDispatcher))
    assertEquals(ModeParam.resolve(Some("dispatcher")).map(_.leg), Right(ModeLeg.Param))
    assertEquals(ModeParam.resolve(Some("  ")).map(_.leg), Right(ModeLeg.Default), "blank = omitted")
  }

  test("P3 显式 auto ⇒ 明确报错（含引导语），绝不静默映射成 kernel") {
    val err = ModeParam.resolve(Some("auto")).swap.getOrElse(fail("auto must be rejected, not mapped"))
    assertEquals(err, ModeParam.AutoRemoved)
    assert(err.contains("allocator has been removed"), err)
    assert(err.contains("Pass mode=\"kernel\" or mode=\"dispatcher\" directly"), err)
    assert(err.contains("请直接指定 kernel 或 dispatcher，分配器已移除"), err)
    assert(err.contains("DELEGATE_MODE_AUTO_REMOVED"), err)
  }

  test("P3b 值域外的词 ⇒ 显式报错（enum 单点来自 DualModeConfig.ModeWords）") {
    val err = ModeParam.resolve(Some("agentflow")).swap.getOrElse(fail("unknown word must be rejected"))
    assert(err.contains("invalid mode 'agentflow'"), err)
    assert(err.contains("kernel|dispatcher"), err)
    assert(err.contains("DELEGATE_MODE_INVALID"), err)
  }

  test("P4 旧双模式总闸配置已退役：nebflow.json 里怎么写都不改变解析结果") {
    writeConfig("""{"delegate":{"dualMode":{"enabled":true,"failopenMode":"dispatcher"}}}""")
    assertEquals(ModeParam.resolve(None).map(_.mode), Right(DualModeConfig.ModeKernel), "the config face is gone")
    writeConfig("""{"delegate":{"dualMode":{"enabled":false,"failopenMode":"kernel"}}}""")
    assertEquals(ModeParam.resolve(Some("dispatcher")).map(_.mode), Right(DualModeConfig.ModeDispatcher))
  }

  test("P5 值域词表与 schema enum 同源（ModeWords = kernel|dispatcher，无 auto）") {
    assertEquals(DualModeConfig.ModeWords, List("kernel", "dispatcher"))
    assert(!DualModeConfig.ModeWords.contains("auto"), "auto must not be a legal word any more")
    assert(DualModeConfig.isModeWord("kernel") && DualModeConfig.isModeWord("dispatcher"))
    assert(!DualModeConfig.isModeWord("auto"))
  }
end ModeParamSpec
