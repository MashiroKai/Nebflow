package nebflow.core.boot

import cats.effect.IO
import nebflow.shared.{Defaults, NebflowLogger}

/**
 * 邮件 / 心跳进程封存的**启动缝**（seal 批 2026-11-03；作者令逐字「邮件和心跳进程功能，
 * 进入封存，发布剔除，本地sbt run 可用。」）。
 *
 * 为什么需要一个缝而不是在各处 if：
 *   - 验收面要求「关闭时零后台进程/零定时器**注册**」可机械判定（可注入注册记录器）。
 *     本对象的 [[Registrar]] 就是 boot 纤维的**唯一出口**：`Sealed` ⇒ 只写一行封存说明、
 *     **不 `.start`**；`Launch` ⇒ `.start` 并回记账。spec 注入 [[RecordingRegistrar]]
 *     即可对「注册了几条」逐条断言（见 `ProcessSealSpec`）。
 *   - 开关单点在 [[nebflow.shared.Defaults]] 两函数（`MailProcessEnabled` /
 *     `HeartbeatProcessEnabled`），本对象只做**决策 + 记账 + 说明日志**，不另存状态。
 *
 * 封存范围（**进程/定时器**，不是工具本体——`Mail` 单次投递、daemon 面板单次
 * start/stop 一律照旧）：
 *   - 心跳 = daemon 家族托管进程（作者 2026-10-29 裁定逐字「独立daemon吧 正好和我们
 *     心跳进程合并 那就是管daemon的」；`#daemon-btn` title 即「心跳进程」）⇒
 *     `DaemonService.autoStart` 的 boot 扇出（连同 dock/handshake 腿）；
 *   - 邮件 = 邮件感知守护（`perception-daemon` 五循环）的 upsert/spawn、
 *     分发器回复打包窗定时腿、设备邮件 15s 回执超时腿。
 *
 * 复活：`-Dnebflow.mail.process.enabled=true` / `-Dnebflow.heartbeat.enabled=true`
 * （`sbt run` 直加即生效，无需改码）。发布剔除 = 打包脚本/Dockerfile 显式写 `=false`。
 */
object ProcessSeal:

  private val logger = NebflowLogger.forName("nebflow.seal")

  /** 封存族。两族的开关 props 见 [[nebflow.shared.Defaults]]。 */
  enum Realm:
    case Mail, Heartbeat

  object Realm:

    private[ProcessSeal] val MailProp = "nebflow.mail.process.enabled"
    private[ProcessSeal] val HeartbeatProp = "nebflow.heartbeat.enabled"

    extension (r: Realm)

      /** 该族当前开关读数（**每次现读** system prop ⇒ 测试/运维可即时翻转）。 */
      def enabled: Boolean = r match
        case Realm.Mail => Defaults.MailProcessEnabled
        case Realm.Heartbeat => Defaults.HeartbeatProcessEnabled

      /** 开关的 system prop 名（说明日志与验收面引用同一字符串）。 */
      def prop: String = r match
        case Realm.Mail => MailProp
        case Realm.Heartbeat => HeartbeatProp

      /** 说明日志里的人类可读族名（封存行逐字用）。 */
      def sealLabel: String = r match
        case Realm.Mail => "邮件后台投递"
        case Realm.Heartbeat => "心跳（daemon 家族）"

    end extension

  end Realm

  /** 决策面（纯函数，spec 判红锚）。 */
  enum Decision:
    case Launch, Sealed

  /** 纯决策：开 ⇒ Launch（照旧注册），关 ⇒ Sealed（零注册）。 */
  def decide(enabled: Boolean): Decision = if enabled then Decision.Launch else Decision.Sealed

  /** 按族开关现读决策。 */
  def decideFor(realm: Realm): Decision = decide(realm.enabled)

  /** 一条注册尝试的读数（spec 记账用）。 */
  final case class Attempt(realm: Realm, name: String, decision: Decision)

  /** boot 纤维出口协议。生产 = [[DefaultRegistrar]]；spec = [[RecordingRegistrar]]。 */
  trait Registrar:
    /** 注册一条 boot 后台纤维。Sealed ⇒ `fiber` 绝不启动（零 .start、零孤儿线程）。 */
    def register(realm: Realm, name: String)(fiber: IO[Unit]): IO[Unit]

  /** 记录一条尝试并给出决策（不启动任何东西的纯记账器；spec 专用形态）。 */
  final class RecordingRegistrar extends Registrar:
    private val buf = scala.collection.mutable.ListBuffer.empty[Attempt]

    def attempts: List[Attempt] = buf.synchronized(buf.toList)

    def launchedNames: List[String] = attempts.collect { case Attempt(_, n, Decision.Launch) => n }

    def sealedNames: List[String] = attempts.collect { case Attempt(_, n, Decision.Sealed) => n }

    def reset(): Unit = buf.synchronized(buf.clear())

    def register(realm: Realm, name: String)(fiber: IO[Unit]): IO[Unit] =
      val d = decideFor(realm)
      IO.delay { buf.synchronized(buf += Attempt(realm, name, d)); () }

  /** 生产实现：Sealed ⇒ 一行封存说明（`[seal]`）且零启动；Launch ⇒ 启动并记一行。 */
  object DefaultRegistrar extends Registrar:

    def register(realm: Realm, name: String)(fiber: IO[Unit]): IO[Unit] =
      decideFor(realm) match
        case Decision.Sealed =>
          logger.info(
            s"[seal] ${realm.sealLabel}进程已封存（${realm.prop}=false）——不注册后台进程/定时器：$name"
          )
        case Decision.Launch =>
          logger.info(s"[seal] ${realm.sealLabel}进程在线（${realm.prop}=true）——注册后台进程/定时器：$name") *>
            fiber.start.void

  /** 生产入口单点（GatewayMain 调用；registrar 默认 [[DefaultRegistrar]]）。 */
  def launch(realm: Realm, name: String)(fiber: IO[Unit]): IO[Unit] =
    DefaultRegistrar.register(realm, name)(fiber)

  /** 注入式单点（spec 用；生产不传 registrar）。 */
  def launchWith(registrar: Registrar, realm: Realm, name: String)(fiber: IO[Unit]): IO[Unit] =
    registrar.register(realm, name)(fiber)

  /**
   * 单行封存说明（**一次性 boot 腿**用：它们不是纤维，但同样要留唯一可见读数）。
   * 只在调用方已判定 `Sealed` 时使用——本函数不做决策、不注册任何东西。
   */
  def sealedNotice(realm: Realm, name: String): IO[Unit] =
    logger.info(s"[seal] ${realm.sealLabel}进程已封存（${realm.prop}=false）——不注册后台进程/定时器：$name")

  /**
   * **就地门**（用于「已经在一条后台纤维体内」的 boot 腿，例如 GatewayMain 的
   * `dispatcher.unsafeRunAndForget(...)` 扇出）：Sealed ⇒ 只写一行封存说明、`body`
   * **一次都不执行**；Launch ⇒ 一行在线说明 + `body` 就地执行（不额外起纤维）。
   *
   * 与 [[launch]] 的分工：`launch` 用于「本来就在起纤维」的腿（`.start` 语义），
   * `whenEnabled` 用于「本来就在纤维里、只需按开关短路」的腿。两者共同保证
   * **Sealed 下零注册**。
   */
  def whenEnabled(realm: Realm, name: String)(body: IO[Unit]): IO[Unit] =
    decideFor(realm) match
      case Decision.Sealed => sealedNotice(realm, name)
      case Decision.Launch =>
        logger.info(s"[seal] ${realm.sealLabel}进程在线（${realm.prop}=true）——后台腿照旧：$name") *> body

  /** [[whenEnabled]] 的带默认值形态（`Sealed` ⇒ 返回 `fallback`，`body` 不执行）。 */
  def whenEnabledOr[A](realm: Realm, name: String)(fallback: A)(body: IO[A]): IO[A] =
    decideFor(realm) match
      case Decision.Sealed => sealedNotice(realm, name).as(fallback)
      case Decision.Launch =>
        logger.info(s"[seal] ${realm.sealLabel}进程在线（${realm.prop}=true）——后台腿照旧：$name") *> body

end ProcessSeal
