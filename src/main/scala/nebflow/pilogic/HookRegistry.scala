package nebflow.pilogic

import io.circe.Json

/** 注册面（设计 §Ⅲ.2）：不可变注册表 + COW 快照。
  * 编译期接线（构造期注入 RunSeed.hooks）；运行期增删 = 拿到新 registry 后下一轮生效
  * （帧边界即 turn 边界）。L-4 采纳「仅装配层」：注册 API 只暴露给装配层，插件无运行期注册面。
  */

/** 注册序控制：缺省 Append=注册序（对位 pi 先扩展次序、后扩展内注册次）；Priority 显式插队。 */
enum HookOrder:
  case Append
  case Priority(rank: Int)

/** 注册表内部件（位点 + 序 + 回调；位点入参本轮 Json 承载）。 */
private[pilogic] sealed trait HookEntry[F[_]]:
  def id: HookId
  def point: HookPoint
  def order: HookOrder
  def registrationIndex: Int

private[pilogic] final case class InterceptorEntry[F[_]](
    id: HookId,
    point: HookPoint,
    order: HookOrder,
    registrationIndex: Int,
    errorPolicy: HookErrorPolicy,
    handle: Json => F[HookEffect],
) extends HookEntry[F]

private[pilogic] final case class ObserverEntry[F[_]](
    id: HookId,
    point: HookPoint,
    order: HookOrder,
    registrationIndex: Int,
    observe: Json => F[Unit],
) extends HookEntry[F]

/** 派发快照（对位 pi 派发前拍快照：运行中注册不影响本次派发——验收 A-9 结构性成立：
  * dispatch 收到的是快照值，之后 registry 任何变更对新快照才可见）。 */
final class HookSnapshot[F[_]] private[pilogic] (val entries: Vector[HookEntry[F]]):

  /** 同位点按总序排序：Priority(rank) 先于 Append；同档按注册序——确定性可复现。 */
  private[pilogic] def orderedAt(point: HookPoint): Vector[HookEntry[F]] =
    entries.filter(_.point == point).sortBy { e =>
      e.order match
        case HookOrder.Priority(r) => (0, r, e.registrationIndex)
        case HookOrder.Append      => (1, 0, e.registrationIndex)
    }

  def hookIdsAt(point: HookPoint): Vector[HookId] = orderedAt(point).map(_.id)
  def size: Int = entries.size

/** 不可变 hook 注册表（COW：每个 with / without 调用返回新实例，原快照不受影响）。 */
trait HookRegistry[F[_]]:
  def snapshot: HookSnapshot[F]
  def withInterceptor(
      point: HookPoint,
      hook: Interceptor[F],
      order: HookOrder = HookOrder.Append,
      errorPolicy: Option[HookErrorPolicy] = None,
  ): HookRegistry[F]
  def withObserver(
      point: HookPoint,
      hook: Observer[F],
      order: HookOrder = HookOrder.Append,
  ): HookRegistry[F]
  def without(hookId: HookId): HookRegistry[F]

object HookRegistry:

  def empty[F[_]]: HookRegistry[F] = Impl(Vector.empty)

  /** 装配期拒绝（A-7 机械校验）：returnRead=false ⇒ 只收 Observer；true ⇒ 只收 Interceptor。 */
  private def requireKind(point: HookPoint, interceptor: Boolean): Unit =
    if point.capability.returnRead != interceptor then
      throw IllegalArgumentException(
        s"位点 ${point.display}（returnRead=${point.capability.returnRead}）" +
          (if interceptor then "返值不被读取——只接受 Observer" else "返值必被读取——只接受 Interceptor"),
      )

  /** 错误档只可收紧不可放宽（§Ⅲ.4：Continue→Block→FailRun）。 */
  private def requireTightened(point: HookPoint, provided: HookErrorPolicy): Unit =
    if HookErrorPolicy.severity(provided) < HookErrorPolicy.severity(point.defaultErrorPolicy) then
      throw IllegalArgumentException(
        s"位点 ${point.display} 缺省错误档 ${point.defaultErrorPolicy}：注册只可收紧（Continue→Block→FailRun），$provided 属放宽",
      )

  private final case class Impl[F[_]](entries: Vector[HookEntry[F]]) extends HookRegistry[F]:
    def snapshot: HookSnapshot[F] = HookSnapshot(entries)

    def withInterceptor(
        point: HookPoint,
        hook: Interceptor[F],
        order: HookOrder,
        errorPolicy: Option[HookErrorPolicy],
    ): HookRegistry[F] =
      requireKind(point, interceptor = true)
      val policy = errorPolicy.getOrElse(point.defaultErrorPolicy)
      requireTightened(point, policy)
      Impl(entries :+ InterceptorEntry(nextId(), point, order, entries.size, policy, hook.handle))

    def withObserver(point: HookPoint, hook: Observer[F], order: HookOrder): HookRegistry[F] =
      requireKind(point, interceptor = false)
      Impl(entries :+ ObserverEntry(nextId(), point, order, entries.size, hook.observe))

    def without(hookId: HookId): HookRegistry[F] =
      Impl(entries.filterNot(_.id == hookId))

    private def nextId(): HookId = HookId(s"hook-${entries.size}")
  end Impl

end HookRegistry
