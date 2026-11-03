package nebflow.core.tools

/**
 * ShellEnvCordon —— **会话子进程环境的品牌围栏**（引擎会话治理专项 · 缺陷五，
 * 作者令 2026-11-03 14:38 逐字「会话环境变量污染：`NEBFLOW_GATEWAY_PORT=6138`
 * 误导跨品牌 CLI，改为 stand 专属命名空间或不在通用会话面注入」）。
 *
 * 病灶：本实例以 stand 品牌运行（`brand.conf` 的 `envPrefix=STAND`），但引擎进程
 * 自身是被 `NEBFLOW_GATEWAY_PORT=6138` 拉起来的（autostart/restart 腿的既有约定），
 * 而 [[nebflow.core.tools.ShellSession]] 的 spawn 点是**原样继承父环境**——于是每
 * 一个 agent Bash 会话里都能读到 `NEBFLOW_GATEWAY_PORT=6138`。跨品牌 CLI（`nebflow`
 * 二进制 / 任何按 NEBFLOW_* 找实例的脚本）在这个 shell 里就会**指向本 instance 的
 * 端口**，判断被带偏（实证：会话内 `printenv NEBFLOW_GATEWAY_PORT` = 6138，而
 * `STAND_GATEWAY_PORT` 未设——`ops/bin/stand-cli-lib.sh` 只认 STAND_GATEWAY_PORT）。
 *
 * 修法（本对象 = 纯决策，spawn 点照单执行）：
 *   - **摘除**通用面 `NEBFLOW_GATEWAY_PORT`（品牌不是 nebflow 时才摘；nebflow 品牌
 *     下它就是本品牌的名字，零改动）；
 *   - **注入**本品牌专属名 `<envPrefix>_GATEWAY_PORT`（stand 构建 = `STAND_GATEWAY_PORT`），
 *     值与引擎真实端口同源（`sys.props("nebflow.gateway.port")` 缺省
 *     `Defaults.brandDefaultPort`）——stand 自己的 CLI 读它，跨品牌 CLI 不再被误导。
 *
 * 语义边界：只动**端口这一对名字**，其余环境变量逐字继承（不越权翻遍全环境）。
 */
object ShellEnvCordon:

  /** 通用面遗留名（历史事实，不由 brand.conf 派生——同 Branding.env 的 legacy 口径）。 */
  val LegacyPortVar: String = "NEBFLOW_GATEWAY_PORT"

  /** 本品牌专属端口变量名（`STAND_GATEWAY_PORT` 等）。 */
  def brandPortVar(envPrefix: String): String = s"${envPrefix}_GATEWAY_PORT"

  /** 引擎真实端口（缺省 = `Defaults.brandDefaultPort`）。 */
  def port: String =
    sys.props.getOrElse("nebflow.gateway.port", nebflow.shared.Defaults.brandDefaultPort.toString)

  /** 围栏决策（纯函数，spec 判红锚）：要摘除的键 + 要写入的键值。 */
  final case class Cordon(removals: List[String], additions: List[(String, String)]):
    def isEmpty: Boolean = removals.isEmpty && additions.isEmpty

  def decide(parentKeys: Set[String], envPrefix: String, portValue: String): Cordon =
    val legacyBrand = envPrefix.equalsIgnoreCase("NEBFLOW")
    val brand = brandPortVar(envPrefix)
    // 品牌名恒写（值 = 真实端口）；非 nebflow 品牌恒摘遗留名（Remove 幂等，父环境
    // 里没有也无害）；nebflow 品牌下品牌名 == 遗留名 ⇒ 零摘除、零变化。
    val removals = if legacyBrand then Nil else List(LegacyPortVar)
    val additions = List(brand -> portValue)
    Cordon(removals.distinct, additions.distinct)

end ShellEnvCordon
