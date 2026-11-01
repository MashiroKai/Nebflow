package nebflow.shared

/**
 * output 块特性总闸——**提示词组装面**的热读缝（prompt-face 补批 2026-11-01）。
 *
 * 作者令「把 output 块相关的内容先关闭，因为我现在没有体会出来有什么区别」：
 * 闸关闭时，agent 提示词组装里所有要求/教 agent 写 `<output>` 块的段一律不注入
 * ——任何 agent 的上下文里都不出现 output 块指令，agent 自然不写块，控制台/
 * 渠道也就不会出现字面 `<output>` 标签。五个组装 seam（根会话 order-375 段、
 * Nebula 模板 Voice bullet、delegate brief、节点 protocolFootnote、subagent/
 * workflow 步首条消息包装）在各自装配时刻调用本读数判定。
 *
 * 同一键 `nebflow.json` 顶层 `outputBlock.enabled`（**缺席 = 关**，作者令默认
 * false），三面共用一个键：
 *
 *   - 渠道剥离面：`nebflow.social.outbound.OutputBlock.enabledFromConfig`
 *     （委托到本读数——一个键，一份读实现）；
 *   - 前端渲染面：web/js/featureFlags.js `outputBlockEnabled()`；
 *   - 提示词组装面（本对象）。
 *
 * 热读语义（与已合两面一致，fail-safe 关）：缺席 / 解析失败 / 类型不符 / 读盘
 * 异常 = 关。**组装期判定**：提示词是组装期产物，各 seam 在装配时刻求值——闸
 * 翻动后新装配即翻面；已在飞的旧上下文不回改（下个生命周期节点自然刷新）。
 */
object OutputBlockGate:

  /**
   * 热读 `outputBlock.enabled`（渠道剥离面
   * `nebflow.social.outbound.OutputBlock.enabledFromConfig` 同款读法，收拢为
   * 单一实现：缺席 / 解析失败 / 类型不符 / 读盘异常 = 关——默认 OFF = 作者令；
   * 闸是**恢复今日行为**的入口，不是缺省面）。
   */
  def enabled(): Boolean =
    try
      val p = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(p) then false
      else
        io.circe.parser
          .parse(os.read(p))
          .toOption
          .flatMap(_.hcursor.downField("outputBlock").downField("enabled").as[Boolean].toOption)
          .getOrElse(false)
    catch case _: Exception => false

end OutputBlockGate
