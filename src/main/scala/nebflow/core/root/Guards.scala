package nebflow.core.root

import cats.effect.IO
import nebflow.cli.SingleInstanceGuard
import nebflow.gateway.GatewayConfig
import nebflow.shared.{Defaults, PathUtil}

/**
 * 三层数据根 · 双守卫（B1，契约 `reports-evidence/20261104_b1-dataroot/design-api.md` §5）。
 *
 * 两个「拒绝」面 —— [[OwnerGuard]]（凭据 owner vs 本引擎服务的账号）与 [[LoginGuard]]
 * （登录前置门 + 禁热切）—— 共用一个 [[Refusal]]：稳定 `code` + **必须可行动**的 `message`
 * （沿用 `LoginCommand.SignInFailure` 的稳定码闭集风格）。
 */
final case class Refusal(code: String, message: String)

/**
 * owner 双点名校验（验收 V2）：本引擎服务的账号 vs 凭据里的 owner。
 *
 * 纪律（与既有 `OwnerIdentity` 同一条，`OwnerIdentity.scala:20-22`）：**读不到锚 ⇒ 不冒认 owner**
 * ⇒ `credentialSub = None` 走 fail-open 放行 —— 无锚时不新增拒绝面（否则老用户第一次升级就被锁在门外）。
 * 有锚且不等 ⇒ 拒服务，文案必须同时点名两个值 + 给出 `stand logout` / `stand login` 的行动建议
 * （静默降级 = 用 A 的凭据跑在 B 的账号根上，正是本守卫要堵的缺口）。
 */
object OwnerGuard:

  /** 稳定码（放进 `Refusal.code`，同时出现在文案尾部便于日志检索）。 */
  val Code: String = "owner-account-mismatch"

  def check(accountSub: String, credentialSub: Option[String]): Either[Refusal, Unit] =
    credentialSub match
      case None => Right(())
      case Some(sub) if sub == accountSub => Right(())
      case Some(owner) =>
        Left(
          Refusal(
            Code,
            s"This engine serves account '$accountSub' but the stored credential belongs to " +
              s"'$owner' — run 'stand logout', then 'stand login' to sign in again. ($Code)"
          )
        )

end OwnerGuard

/**
 * 登录前置门 + 禁热切（§0 总则 2「进程级单值，禁热切」的判定面）。
 *
 * 放在守卫里而**不是**放进 `PathUtil.setDataRoot`：测试要反复换根，`PathUtil` 必须保持可重复赋值
 * （713 处既有引用依赖此语义）；「禁热切」只是**生产启动面**的约束，由 CLI / 网关入口判。
 */
object LoginGuard:

  /** `stand login` 前置门的稳定码。 */
  val LoginCode: String = "account-switch-requires-logout"

  /** 禁热切的稳定码。 */
  val HotSwitchCode: String = "hot-switch-forbidden"

  /**
   * `stand login` 前置门：单实例单端口 ⇒ 登录即换根。跑着 A 却要登 B 时必须先登出，
   * 否则用户只会得到「旧实例还占着端口」的谎话报错。
   *
   * 判据（契约 §5）：端口上有**活实例**且该实例的账号（机器层 `active-account`）≠ 目标 ⇒ 拒；
   * 无指针 / 同账号 / 端口上没有本品牌活实例 ⇒ 放行。
   */
  def checkLoginAllowed(targetAccountSub: String): IO[Either[Refusal, Unit]] =
    AccountRoot.readPointer(PathUtil.machineRoot).map(_.accountSub) match
      case None => IO.pure(Right(()))
      case Some(current) if current == targetAccountSub => IO.pure(Right(()))
      case Some(current) =>
        liveInstanceOnMachinePort.map { live =>
          if !live then Right(())
          else
            Left(
              Refusal(
                LoginCode,
                s"This machine is already signed in as '$current' (single instance, single port) — " +
                  s"run 'stand logout' first, then 'stand login' to sign in as '$targetAccountSub'. " +
                  s"($LoginCode)"
              )
            )
        }

  /** 禁热切：同值放行（启动序前后各取一次 = 同值），不同值 ⇒ 拒（登录后写指针 + 重启生效）。 */
  def checkHotSwitch(previous: os.Path, next: os.Path): Either[Refusal, Unit] =
    if previous == next then Right(())
    else
      Left(
        Refusal(
          HotSwitchCode,
          s"The data root is decided once per process (was '$previous', now '$next') — the engine never " +
            s"switches roots in-process; write the account pointer and restart instead. ($HotSwitchCode)"
        )
      )

  // ── 内部 ───────────────────────────────────────────────────────────────

  /**
   * 机器端口上是否有**本品牌**活实例（复用 `SingleInstanceGuard.checkPort` 的归属分类；
   * 零 drain 等待 —— 登录是前台交互，不等 TIME_WAIT）。
   *
   * `NebflowInstance`（本品牌）与 `OtherHomeInstance`（本品牌但别的 home）都算「有活实例」——
   * 端口是机器级唯一资源，任何占着它的本品牌实例都会让「登录即换根」变成谎话报错。
   * 端口没被占 / 被别的程序占 ⇒ 与账号切换无关，放行。
   */
  private def liveInstanceOnMachinePort: IO[Boolean] =
    machinePort.flatMap { port =>
      SingleInstanceGuard
        .checkPort(MachineBindHost, port, drainWaitMs = 0L)
        .map {
          case _: SingleInstanceGuard.NebflowInstance => true
          case _: SingleInstanceGuard.OtherHomeInstance => true
          case _ => false
        }
        .handleError(_ => false)
    }

  /** 机器端口 = 本实例网关端口（解析链与启动同链：`--port` > env > config > 品牌默认）。 */
  private def machinePort: IO[Int] =
    GatewayConfig.load.map(_.port.value).handleError(_ => Defaults.brandDefaultPort)

  /** 网关默认绑定面（与 `GatewayConfig` 的 DefaultHost 同一取值；探活只关心「有没有人听」）。 */
  private val MachineBindHost: String = "0.0.0.0"

end LoginGuard
