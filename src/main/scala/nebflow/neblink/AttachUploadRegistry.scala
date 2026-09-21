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
 *
 * ── 预览暂存（传输集成批 S7 · 2026-09-22）──
 * 跨仓契约 §B.6 选定 P1 = 预览随 **E1 申报体**走；而网页腿的 E1 由网关在**整件上传
 * 请求内**发起（[[FriendService.uploadStream]] ⇒ [[NeblinkClient.createAttachment]]），
 * 预览却产自浏览器（`attachImagePreview.js`）⇒ 两个 HTTP 请求之间必须有一处**进程内**
 * 中转：浏览器先 `POST /api/attachments/preview`（同 `uploadId` 键）把**已过契约判定**
 * 的六键字段暂存（[[stagePreview]]），整件上传落地时网关**消费一次读**（[[takePreview]]
 * = 原子取出并移除，不存在二次消费）拼进 E1。
 *
 * 有界与诚实：暂存容量 [[PreviewStageCap]] 条（封顶 ⇒ `stagePreview` 返回 `false` ⇒
 * 路由回 `{staged:false}` ⇒ 客户端按「无预览」原样上传，**零阻塞零改写**——预览是
 * 建议性加性面）；未发生上传的暂存条目由后续暂存的**先入先出挤出**兜底（与取消位同款
 * 进程内生命期：网关重启即清空，无落盘、无泄漏到磁盘的形态）。
 */
final class AttachUploadRegistry(
  ref: Ref[IO, Set[String]],
  previews: Ref[IO, Vector[(String, AttachmentPreview)]]
):

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

  /** 暂存一枚预览申报（同 `uploadId` 重复暂存 = **替换**，浏览器重试申报天然幂等）。
    * 表满（≥ [[AttachUploadRegistry.PreviewStageCap]]）⇒ `false`（调用方按「无预览」收场）。 */
  def stagePreview(uploadId: String, p: AttachmentPreview): IO[Boolean] =
    previews.modify { v =>
      if v.size >= AttachUploadRegistry.PreviewStageCap && !v.exists(_._1 == uploadId) then (v, false)
      else (v.filterNot(_._1 == uploadId) :+ (uploadId -> p), true)
    }

  /** 消费一次读：取出并移除该 `uploadId` 的暂存（缺席 ⇒ `None` ⇒ E1 无 `preview` 键）。 */
  def takePreview(uploadId: String): IO[Option[AttachmentPreview]] =
    previews.modify { v =>
      val hit = v.find(_._1 == uploadId).map(_._2)
      (v.filterNot(_._1 == uploadId), hit)
    }

  /** 暂存面现读（读数/诊断用；不计入取消位表）。 */
  def stagedPreviews: IO[Vector[String]] = previews.get.map(_.map(_._1))

end AttachUploadRegistry

object AttachUploadRegistry:
  private[neblink] val CancelPrefix = "cancel:"
  private def cancelKey(uploadId: String): String = CancelPrefix + uploadId

  /** 预览暂存上限（条）。单条 ≤ 256 KiB 字节 ⇒ 表 ≤ 128 条 = ≤ 32 MiB 进程内上界。 */
  val PreviewStageCap: Int = 128

  def create: IO[AttachUploadRegistry] =
    for
      r  <- Ref.of[IO, Set[String]](Set.empty)
      pv <- Ref.of[IO, Vector[(String, AttachmentPreview)]](Vector.empty)
    yield new AttachUploadRegistry(r, pv)

  /** 供 [[nebflow.agent.SharedResources]] 的字段缺省值使用（同其既有 `Ref.unsafe` 先例：
    * 缺省值只在构造期求值一次，构造方 = GatewayMain 单点）。 */
  def unsafe: AttachUploadRegistry = new AttachUploadRegistry(
    Ref.unsafe[IO, Set[String]](Set.empty),
    Ref.unsafe[IO, Vector[(String, AttachmentPreview)]](Vector.empty)
  )
