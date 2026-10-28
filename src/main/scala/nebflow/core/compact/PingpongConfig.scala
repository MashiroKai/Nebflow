package nebflow.core.compact

import cats.effect.IO
import nebflow.shared.PathUtil

/**
 * 乒乓压缩（主会话无感上下文压缩一期，spec §G2）配置开关。
 *
 * 读 `nebflow.json` 的 `pingpong.enabled`；**默认 false**（一期灰度主会话——
 * 关 = 全仓行为零漂移，老同步压缩全链路原样作为回落执行体）。热读、无缓存，
 * 每次触发门判定时读盘（`Guardrails.enabled` 同款先例：setConfig 后下个判定
 * 生效，无需重启）。
 *
 * 作用域由谓词门共同决定（缺一不可，见 AgentPingpong.gateOpen）：
 *   `RootAgentIdentity.isRootAgent(agentDef, depth) && PingpongConfig.enabled`
 * —— 谓词假的一切会话（dispatcher / node / delegate / kernel / team / social /
 * standalone 非 Nebula 根会话）走原路径逐字节不变（spec §G1）。
 *
 * 运行期关闭语义（spec §E-12）：只关**新 fork**；在飞影子跑完照常换入（换入
 * 语义与开关解耦，避免半关状态）。
 */
object PingpongConfig:

  /** Read `pingpong.enabled` from nebflow.json; false on any miss (default OFF). Hot-read, no caching. */
  def enabled: IO[Boolean] =
    IO.blocking {
      val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(configPath) then None
      else
        io.circe.parser
          .parse(os.read(configPath))
          .toOption
          .flatMap(_.hcursor.downField("pingpong").downField("enabled").as[Boolean].toOption)
    }.handleErrorWith(_ => IO.pure(None))
      .map(_.getOrElse(false))

  /**
   * 刀一①（2026-10-28 无感压缩批）：影子腿摘要模型（spec §H2 二期预留位落地）。
   * 读 `pingpong.shadowModel`（如 `"zhipu/glm-5.3"`）。缺省 None = 影子腿沿用主腿
   * `agentDef.model` 候选链（一期 §B3 行为逐字）；点名 ⇒ 影子腿请求改用
   * `AgentModelConfig(preferred = 点名, fallbacks = Nil)` 的**钉死单模型链**——
   * 摘要调用脱离主腿廉价回落链（2026-10-28 三连败实证死因：摘要调用与主腿共用
   * 廉价快模型池，巨 payload + 高峰期一起翻车）。热读、无缓存，同 `enabled`。
   */
  def shadowModel: IO[Option[String]] =
    IO.blocking {
      val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(configPath) then None
      else
        io.circe.parser
          .parse(os.read(configPath))
          .toOption
          .flatMap(_.hcursor.downField("pingpong").downField("shadowModel").as[Option[String]].toOption)
          .flatten
    }.handleErrorWith(_ => IO.pure(None))
      .map(_.map(_.trim).filter(_.nonEmpty))

  /**
   * 刀一④（2026-10-28 无感压缩批）：fork 触发提前量。fork 判据从「越过阈值」
   * 提前到「阈值 ×0.9 预 fork」——换入仍只在轮边界，语义不变。给影子腿的 fork 内
   * 重试（刀一②）与失败退避留出时间余量，避免三连败后直接被 0.95 硬守卫逮住
   * （audit 案二·第一刀-4）。仅作用于乒乓门开的 fork 观测点；门关（谓词假/开关关）
   * 与 pp-fallback 老同步路径的触发阈值逐字不变。
   */
  val ForkPrePositionRatio: Double = 0.9

  /** Pre-positioned fork threshold: ceil(threshold × 0.9). Pure, spec-pinned. */
  def forkPrePositionThreshold(thresholdTokens: Int): Int =
    math.ceil(thresholdTokens.toDouble * ForkPrePositionRatio).toInt

end PingpongConfig
