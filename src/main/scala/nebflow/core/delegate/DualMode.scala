package nebflow.core.delegate

import cats.effect.IO
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, PathUtil}

import scala.collection.mutable

/**
 * DualModeConfig —— **delegate 双模式总闸**（设计
 * `docs/design/20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN.md`
 * §8.2；2026-10-28 实施批）。
 *
 * == 闸面 ==
 *  - 总闸 `delegate.dualMode.enabled`（nebflow.json），缺省 **false = 现行为逐字节**
 *    （任何 delegate 持全量工具面、spawn 时整 brief 一次插件分配、判是/判否一律建
 *    分支——本闸关 = §3.2 Inert 态：零模式选择、零 mode-decision 审计行）。
 *  - sys prop `nebflow.delegate.dualMode.enabled` 热读覆写（`PluginsConfig` /
 *    `Defaults.DelegateRevivalEnabled` 同款先例；作者灰度旋钮——改配置即时生效，
 *    无需重启）。
 *  - 缺省方向旋钮 `delegate.dualMode.failopenMode`（缺省 `kernel`）——分配器全败时
 *    的缺省模式（两错不对称：kernel 误判复杂任务 = 单会话硬扛零机制风险；
 *    dispatcher 误判简单任务 = 白付链开销——缺省取便宜的一侧）。
 *
 * == 读法 ==
 * 热读（每次判定现读）：absent / 非法 / garbage → 缺省。`enabledNow` 是同步读
 * （工具面/挂载面等纯函数位的判据输入——`DelegateRegistry.modeFaceOf` 单点消费）；
 * `enabled` 是 IO 包裹形态（spawn 链等 IO 语境）。
 */
object DualModeConfig:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.dual-mode")

  val SysPropEnabled = "nebflow.delegate.dualMode.enabled"

  /** 模式词值域（registry/任务行 mode 字段 + mode-decision 审计行共用）。 */
  val ModeKernel: String = "kernel"
  val ModeDispatcher: String = "dispatcher"

  /** sys prop 覆写（三分值域：true/false 显式；未设 = 无覆写）。 */
  private def sysProp: Option[Boolean] =
    sys.props.get(SysPropEnabled).flatMap(v => scala.util.Try(v.toBoolean).toOption)

  private def readConfigBool(key: String, default: Boolean): Boolean =
    val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    val parsed: Option[Boolean] =
      if !os.exists(configPath) then None
      else
        io.circe.parser
          .parse(os.read(configPath))
          .toOption
          .flatMap(_.hcursor.downField("delegate").downField("dualMode").downField(key).as[Option[Boolean]].toOption)
          .flatten
    parsed.getOrElse(default)

  private def readConfigString(key: String, default: String): String =
    val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    val parsed: Option[String] =
      if !os.exists(configPath) then None
      else
        io.circe.parser
          .parse(os.read(configPath))
          .toOption
          .flatMap(_.hcursor.downField("delegate").downField("dualMode").downField(key).as[Option[String]].toOption)
          .flatten
    parsed.getOrElse(default)

  /** 总闸热读（IO 形态——spawn 链消费）。 */
  def enabled: IO[Boolean] = IO.blocking(enabledNow)

  /** 总闸同步热读（纯函数位判据——工具面/挂载面单点消费）。 */
  def enabledNow: Boolean = sysProp.getOrElse(readConfigBool("enabled", default = false))

  /** 缺省方向旋钮（kernel | dispatcher；非法值回落 kernel——fail-open 安全侧）。 */
  def failopenMode: String =
    readConfigString("failopenMode", default = ModeKernel) match
      case ModeDispatcher => ModeDispatcher
      case _              => ModeKernel

  /** 值域校验（mode 参数/记录字段的合法词）。 */
  def isModeWord(s: String): Boolean = s == ModeKernel || s == ModeDispatcher
end DualModeConfig

/**
 * 模式判定结果（[[DelegateModeAllocator]] 端口产出）：mode + 产出腿 + 原因。
 * 腿值域（mode-decision 审计行 `leg` 字段）：manual | jev | llm-fallback |
 * fail-open | inert。
 */
final case class ModeDecision(mode: String, leg: String, reason: String)

/**
 * DelegateModeAllocator —— **模式选择器端口**（core 零 llm import 的倒置窄口，
 * `core.jev.PluginAllocator` 注册器同款形态）：执行体在 `llm.decision`
 * （[[nebflow.llm.decision.DelegateModeMachine]]），注册点 = `agent.SharedResources`
 * 构造行。未注册 = fail-open kernel（leg=fail-open，reason 面未装配——绝不阻断
 * 派发，与插件分配端口同一姿态）。
 */
object DelegateModeAllocator:

  /** 接线面（执行体实现；单方法）。 */
  trait Face:
    def decideMode(subject: String, taskText: String): IO[ModeDecision]
  end Face

  private val face = new java.util.concurrent.atomic.AtomicReference[Option[Face]](None)

  def install(f: Face): Unit = face.set(Some(f))
  def clear(): Unit = face.set(None)
  def installed: Boolean = face.get.isDefined

  /** 端口统一入口（core 调用点唯一消费面）：未注册/内部异常 = fail-open kernel。 */
  def decideMode(subject: String, taskText: String): IO[ModeDecision] =
    face.get match
      case None =>
        IO.pure(ModeDecision(DualModeConfig.ModeKernel, "fail-open", "mode allocator face not installed"))
      case Some(f) =>
        f.decideMode(subject, taskText).handleErrorWith { e =>
          IO(
            NebflowLogger
              .forName("nebflow.core.delegate.mode-alloc")
              .warnSync(s"mode allocation '$subject' errored (${e.getClass.getSimpleName}) — fail-open kernel")
          ).as(ModeDecision(DualModeConfig.ModeKernel, "fail-open", s"allocator error: ${e.getClass.getSimpleName}"))
        }
end DelegateModeAllocator
