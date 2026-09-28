package nebflow.neblink

import cats.effect.{IO, Ref}

/**
 * 在飞附件上传的**取消位**登记表（attachcl 批，2026-09-16）。
 *
 * 为什么需要它：网页腿的上传是**一个** HTTP 请求（整件 body → 网关 → 分块驱动），
 * 浏览器没有「停止服务端循环」的通道；取消必须由**第二个请求**（`POST
 * /api/attachments/{uploadId}/cancel`）把取消位翻起来，分块驱动在**下一块发出前**
 * 读它（[[AttachUpload.Hooks.cancelled]]）⇒ 停止后续分块、返回
 * [[AttachUpload.Failure.Cancelled]]（**不报完成**，硬钉②）。
 *
 * 登记键 = 客户端生成的 `uploadId`（`register` 只认在场的 id ⇒ 对未知 id 的 cancel
 * 返回 `false`，不伪造成功）。上传结束（成功/失败/取消）一律 `release`，表不长期驻留。
 *
 * 进程内、不落盘：取消是**运行时态**（网关重启后没有任何在飞上传可取消，`cancel`
 * 对旧 id 返回 `false` 即诚实终态）。
 */
final class AttachUploadRegistry(ref: Ref[IO, Set[String]]):

  /** 登记一次上传（进入上传链即登记，取消请求才有可命中面）。 */
  def register(uploadId: String): IO[Unit] = ref.update(_ + uploadId)

  /** 上传收尾（**必调**，三条出口都要走：成功 / 失败 / 取消）。 */
  def release(uploadId: String): IO[Unit] = ref.update(_ - uploadId - AttachUploadRegistry.cancelKey(uploadId))

  /** 翻取消位。未登记过的 id ⇒ `false`（不新造位、不谎报「已取消」）。 */
  def cancel(uploadId: String): IO[Boolean] =
    ref.modify(s => if s.contains(uploadId) then (s + AttachUploadRegistry.cancelKey(uploadId), true) else (s, false))

  /** 分块驱动的取消判据（下一块前求值）。 */
  def isCancelled(uploadId: String): IO[Boolean] = ref.get.map(_.contains(AttachUploadRegistry.cancelKey(uploadId)))

  /** 在飞 id 快照（读数/诊断用）。 */
  def inFlight: IO[Set[String]] = ref.get.map(_.filterNot(_.startsWith(AttachUploadRegistry.CancelPrefix)))

object AttachUploadRegistry:
  private[neblink] val CancelPrefix = "cancel:"
  private def cancelKey(uploadId: String): String = CancelPrefix + uploadId

  def create: IO[AttachUploadRegistry] = Ref.of[IO, Set[String]](Set.empty).map(new AttachUploadRegistry(_))

  /** 供 [[nebflow.agent.SharedResources]] 的字段缺省值使用（同其既有 `Ref.unsafe` 先例：
    * 缺省值只在构造期求值一次，构造方 = GatewayMain 单点）。 */
  def unsafe: AttachUploadRegistry = new AttachUploadRegistry(Ref.unsafe[IO, Set[String]](Set.empty))
