package nebflow.gateway

/**
 * 手动恢复依赖宿（重启手动恢复批，2026-11-06 20:36 作者令）：boot 装配点
 * （GatewayMain 创建 ActorSystem 后）把 [[nebflow.agent.DelegateRevival.Deps]]
 * 安装进来，`/api/delegates/restore` 与 `/api/delegates/restore-all` 两个 REST 臂
 * 现读。进程内单写（boot 一次 install），测试面可复位。
 *
 * 不走 RestApiCtx 构造参数：那会改构造签名、波及全部测试构造点——holder 是零涟漪
 * 的装配面（PresenceRoutes 读 `current`，缺席 ⇒ 503 可读错误，绝不 NPE）。
 */
object ManualRevivalHost:

  private val depsRef =
    new java.util.concurrent.atomic.AtomicReference[Option[nebflow.agent.DelegateRevival.Deps]](None)

  /** boot 装配点调用（ActorSystem 建成后、boot sweep 前）。 */
  def install(deps: nebflow.agent.DelegateRevival.Deps): Unit = depsRef.set(Some(deps))

  /** REST 臂现读（None = 尚未装配）。 */
  def current: Option[nebflow.agent.DelegateRevival.Deps] = depsRef.get

  /** 测试复位。 */
  def reset(): Unit = depsRef.set(None)
end ManualRevivalHost
