package nebflow.core.delegate

import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * dispatcher 封存闸 spec（dispatcher-seal 批 2026-11-18 · 作者令逐字：「先不修复
 * 派个批 报delegate的dispatcher模式封存 然后立即重启 让kernel来做」）——验收三组：
 *
 *  - **S1 封存态（缺省）显式拒**：`mode=dispatcher` ⇒ Left，断言错误码
 *    `DELEGATE_DISPATCHER_SEALED` 与文案要点（现象/kernel 出路/恢复方法）——不许只断
 *    「失败」；且**绝不静默降级**（Right 里不得出现 mode 被改写成 kernel 的结果）。
 *  - **S2 开关可恢复**：`delegate.dispatcherMode = "enabled"` ⇒ dispatcher 原样放行
 *    （leg=param，行为与封存前一致）；非法词/缺键 ⇒ 仍封存（fail-closed）。
 *  - **S3 kernel 零触碰**：`mode=kernel` 与省略 mode 在封存态/恢复态都原样解析
 *    （kernel / default-kernel），既有路径不受本批影响。
 */
class DispatcherSealSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root / "nonexistent"

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "dispatcher-seal")
    os.makeDir.all(tmp)
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tmp)

  private def writeConfig(json: String): Unit =
    val p = tmp / nebflow.shared.Branding.configFileName
    os.write.over(p, json)

  // ── S1 封存态（缺省）：mode=dispatcher 显式拒 ──────────────────────────────

  test("S1a 封存态下 dispatcher 被拒：错误码 DELEGATE_DISPATCHER_SEALED，一眼可辨是封存") {
    val err = DispatcherSeal
      .resolveForDispatch(Some("dispatcher"), DispatcherSeal.Sealed)
      .swap
      .getOrElse(fail("sealed state must reject mode=dispatcher explicitly"))
    assertEquals(err, DispatcherSeal.SealedMessage)
    assert(err.contains("DELEGATE_DISPATCHER_SEALED"), err)
    assert(err.contains("SEALED"), err)
  }

  test("S1b 拒绝文案说得出路与恢复方法（现象 + mode=kernel + stand.json 开关）") {
    val err = DispatcherSeal.SealedMessage
    assert(err.contains("mode=\"kernel\""), "must point at the kernel way out")
    assert(err.contains("delegate.dispatcherMode=\"enabled\""), "must name the recovery switch verbatim")
    assert(err.contains("dispatcher 模式已封存"), "bilingual: the seal statement is readable in Chinese too")
    assert(err.contains("父会话"), "the recurring hang phenomenon is stated (parent never re-awakened)")
  }

  test("S1c 绝不静默降级：封存拒就是 Left，不存在「dispatcher 进、kernel 出」的结果") {
    DispatcherSeal.resolveForDispatch(Some("dispatcher"), DispatcherSeal.Sealed) match
      case Left(_) => () // 唯一合法结果：显式拒绝
      case Right(d) =>
        fail(s"sealed state must not admit dispatcher (got ${d.mode}); silently rewriting to kernel is banned too")
  }

  test("S1d 与词表拒的可辨性：封存拒 ≠ 非法词拒（错误码不同族）") {
    val sealedErr =
      DispatcherSeal.resolveForDispatch(Some("dispatcher"), DispatcherSeal.Sealed).swap.getOrElse(fail(""))
    val invalidErr =
      DispatcherSeal.resolveForDispatch(Some("nosuchmode"), DispatcherSeal.Sealed).swap.getOrElse(fail(""))
    assert(sealedErr.contains("DELEGATE_DISPATCHER_SEALED"), "seal rejection carries its own code")
    assert(invalidErr.contains("DELEGATE_MODE_INVALID"), "vocabulary rejection keeps the ModeParam code")
    assert(!invalidErr.contains("DELEGATE_DISPATCHER_SEALED"), "a bad word is a bad word, not a seal")
  }

  // ── S2 开关可恢复：enabled ⇒ dispatcher 回归，行为与封存前一致 ─────────────

  test("S2a enabled 态下 dispatcher 原样放行（leg=param——与封存前的解析结果一致）") {
    DispatcherSeal.resolveForDispatch(Some("dispatcher"), DispatcherSeal.Enabled) match
      case Right(d) =>
        assertEquals(d.mode, DualModeConfig.ModeDispatcher)
        assertEquals(d.leg, ModeLeg.Param)
      case Left(err) => fail(s"enabled switch must restore dispatcher dispatch, got rejection: $err")
  }

  test("S2b 配置节解析：{\"dispatcherMode\":\"enabled\"} ⇒ enabled；缺节/缺键/非法词 ⇒ sealed（fail-closed）") {
    def node(json: String): Option[io.circe.Json] = io.circe.parser.parse(json).toOption
    assertEquals(
      node("{}").flatMap(_.asObject).map(_.isEmpty),
      Some(true)
    ) // probe: the helper parses, not wraps a string literal
    assertEquals(
      DispatcherSeal.modeFromConfigNode(node("""{"dispatcherMode":"enabled"}""")),
      DispatcherSeal.Enabled
    )
    assertEquals(
      DispatcherSeal.modeFromConfigNode(node("""{"dispatcherMode":"  ENABLED  "}""")),
      DispatcherSeal.Enabled,
      "case/whitespace tolerance matches ModeParam"
    )
    assertEquals(DispatcherSeal.modeFromConfigNode(None), DispatcherSeal.Sealed, "absent node = sealed default")
    assertEquals(DispatcherSeal.modeFromConfigNode(node("{}")), DispatcherSeal.Sealed, "absent key = sealed default")
    assertEquals(
      DispatcherSeal.modeFromConfigNode(node("""{"dispatcherMode":"yes"}""")),
      DispatcherSeal.Sealed,
      "unintelligible value must NOT unseal (fail-closed)"
    )
  }

  test("S2c 端到端：真 stand.json 写 enabled ⇒ 派发路径解析回 dispatcher；不写 ⇒ 封存拒") {
    // 无配置文件（既有安装形态）：缺省 = 封存
    val sealedMode = DispatcherSeal.modeFromConfigNode(
      scala.util.Try(nebflow.shared.Config.loadServiceConfig().delegate).toOption.flatten
    )
    assertEquals(sealedMode, DispatcherSeal.Sealed)
    assert(DispatcherSeal.resolveForDispatch(Some("dispatcher"), sealedMode).isLeft)

    // 作者显式置 enabled：恢复（热读，无重启迁移）
    writeConfig("""{"delegate":{"dispatcherMode":"enabled"}}""")
    val enabledMode = DispatcherSeal.modeFromConfigNode(
      scala.util.Try(nebflow.shared.Config.loadServiceConfig().delegate).toOption.flatten
    )
    assertEquals(enabledMode, DispatcherSeal.Enabled)
    assertEquals(
      DispatcherSeal.resolveForDispatch(Some("dispatcher"), enabledMode).map(d => (d.mode, d.leg)),
      Right((DualModeConfig.ModeDispatcher, ModeLeg.Param))
    )
  }

  // ── S3 kernel 零触碰：kernel / 省略在两种开关态下都原样 ────────────────────

  test("S3a mode=kernel 回归：封存态与恢复态都原样放行（leg=param）") {
    for sealMode <- List(DispatcherSeal.Sealed, DispatcherSeal.Enabled) do
      DispatcherSeal.resolveForDispatch(Some("kernel"), sealMode) match
        case Right(d) =>
          assertEquals(d.mode, DualModeConfig.ModeKernel)
          assertEquals(d.leg, ModeLeg.Param)
        case Left(err) => fail(s"kernel must be untouched by the seal gate ($sealMode): $err")
  }

  test("S3b 省略 mode 回归：仍按既有规则落 kernel（leg=default），封存闸不改变缺省方向") {
    for sealMode <- List(DispatcherSeal.Sealed, DispatcherSeal.Enabled) do
      DispatcherSeal.resolveForDispatch(None, sealMode) match
        case Right(d) =>
          assertEquals(d.mode, DualModeConfig.ModeKernel)
          assertEquals(d.leg, ModeLeg.Default)
        case Left(err) => fail(s"omitted mode must stay default-kernel ($sealMode): $err")
  }

  test("S3c 既有词表拒（auto/merger）在封存态下仍按 ModeParam 原文案拒（先词表后封存闸）") {
    assert(
      DispatcherSeal
        .resolveForDispatch(Some("auto"), DispatcherSeal.Sealed)
        .swap
        .exists(_.contains("DELEGATE_MODE_AUTO_REMOVED"))
    )
    assert(
      DispatcherSeal
        .resolveForDispatch(Some("merger"), DispatcherSeal.Sealed)
        .swap
        .exists(_.contains("DELEGATE_MODE_MERGER_RETIRED"))
    )
  }
end DispatcherSealSpec
