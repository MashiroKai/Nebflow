package nebflow.core.plugin

/**
 * mcp.json env 的 **secret 指针形态**（安全批 2026-10-29 ④a，审计报告 §7 缝隙 1 /
 * §8 建议的落地）：env 值 = `${secret:<name>}` —— 值本体只存 secrets 库
 * （`<dataRoot>/secrets/<name>`，owner-only 0600），运行期由
 * `PluginMcpManager.runtimeConfig` 解析注入；mcp.json、审批清单、日志**永不含值**
 * （SecretsStore 命名纪律同族；`SecretInjection.materialize` 的读取语义——raw bytes
 * / UTF-8 / 去一个尾换行 / ≤64KiB——在展开点复用）。
 *
 * 三个消费方共用本件（勿各自造正则）：
 *  - 装载校验（`PluginRegistry.validateServerEntry`）：含 `${secret:` 但非完整指针
 *    ⇒ entry 拒（fail-closed）；
 *  - 审批红标（`PluginRegistry.approvalFlags`）：凭据类键名的红标启发对指针形态
 *    **不触发**（值不是明文凭据；红标降为兜底——非指针明文值照旧红标）；
 *  - 运行期展开（`PluginMcpManager.runtimeConfig`）：解析失败 ⇒ server 启动失败
 *    （PLUGIN_MCP_START_FAILED，可见、点名 secret 名不点名值）。
 */
object SecretEnvPointer:

  /** 指针正则：全串 `${secret:<name>}`；name = SecretsStore.validateName 同款字符面
   *  （`^[a-z0-9][a-z0-9._-]{0,63}$`）。 */
  private val pattern = """^\$\{secret:([a-z0-9][a-z0-9._-]{0,63})\}$""".r

  /** 完整合法指针 ⇒ Some(secret 名)。 */
  def parse(value: String): Option[String] =
    value.trim match
      case pattern(name) => Some(name)
      case _ => None

  /** 装载层语法守卫（全串匹配才放行）。 */
  def matches(value: String): Boolean = parse(value).isDefined

  /** 指针标记（存在性探测用——「含 ${secret: 但非法」= 半截指针）。 */
  val Marker = "${secret:"

end SecretEnvPointer
