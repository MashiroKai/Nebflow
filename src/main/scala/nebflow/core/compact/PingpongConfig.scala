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

end PingpongConfig
