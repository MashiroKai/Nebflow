package nebflow.core.executor

import nebflow.shared.PathUtil

/**
 * ExternalSpawnConfig —— 外部执行器 spawn 环境配置（codex-spawn-fix 批
 * 2026-10-19）。
 *
 * == 背景（调度方手动复现实证的 codex 死因①）==
 * codex exec 不带代理无法达 OpenAI——进程挂死。带
 * `https_proxy=http://127.0.0.1:7890` 实测全通（SMOKE-OK，exit 0，JSONL 事件流
 * 正常）。而引擎 spawn 外部 CLI（[[ExternalSessionRunner]] buildProcess）原先
 * 不注入代理环境：引擎若由 launchd/面板拉起，shell rc 里的代理导出它根本看不到。
 *
 * == 配置（nebflow.json 顶层 `externalExecutors` 节）==
 * {{{
 *   "externalExecutors": {
 *     "proxyEnv": {
 *       "http_proxy": "http://127.0.0.1:7890",
 *       "https_proxy": "http://127.0.0.1:7890"
 *     }
 *   }
 * }}}
 *   - 缺省 / 节缺失 / 坏形 ⇒ 空映射 = **零注入**（旧行为逐字）。
 *   - 只注入配置**点名**的键值——绝不把引擎进程 env 里可能存在的其他敏感变量
 *     无脑透传扩大化（ProcessBuilder 默认继承父进程 env 是 Java 语义，本配置
 *     只做点名加法，不做清空、不做全量转发）。
 *   - 热读（每次 spawn 现读，仿 [[ExecutorRegistry.readSettings]]）：改配置后
 *     下一次 spawn 即生效。
 */
object ExternalSpawnConfig:

  private val Section = "externalExecutors"
  private val ProxyEnvKey = "proxyEnv"

  /** 热读 proxyEnv 键值（生产入口：config 路径取当前 dataRoot）。 */
  def proxyEnv: Map[String, String] =
    proxyEnvFrom(PathUtil.configJsonReadPath(PathUtil.dataRoot))

  /**
   * 纯核（spec 可驱动）。宽容解码：文件缺失 / JSON 坏形 / 节非对象 / 值非字符
   * 串 ⇒ 相应键丢弃，整体坏 ⇒ 空映射，绝不抛出（检测面同款纪律——配置坏不楔
   * 死派发）。空键名丢弃（`env.put("", v)` 在 POSIX spawn 是 EINVAL）。
   */
  private[executor] def proxyEnvFrom(configPath: os.Path): Map[String, String] =
    try
      if !os.exists(configPath) then Map.empty
      else
        io.circe.parser
          .parse(os.read(configPath))
          .toOption
          .flatMap(_.hcursor.downField(Section).focus)
          .flatMap(_.asObject)
          .flatMap(o => io.circe.Json.fromJsonObject(o).hcursor.downField(ProxyEnvKey).focus)
          .flatMap(_.asObject)
          .map(_.toMap.collect { case (k, v) if k.trim.nonEmpty && v.isString => k -> v.asString.get })
          .getOrElse(Map.empty)
    catch case _: Throwable => Map.empty

  /**
   * 把配置点名的键值注入 ProcessBuilder 环境（在默认继承的父进程 env 上做
   * 点名加法——绝不清空既有继承，也绝不注入配置之外的键）。空映射 = no-op。
   */
  def installProxyEnv(pb: java.lang.ProcessBuilder): Unit =
    val named = proxyEnv
    if named.nonEmpty then
      val env = pb.environment()
      named.foreach { case (k, v) => env.put(k, v) }

end ExternalSpawnConfig
