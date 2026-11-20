package nebflow.gateway

/**
 * attachment-persistence 批（2026-11-18，P0「已发送的文件过一会失效」）：
 * 用户附件的**落盘身份**与**读取身份**必须是同一把钥匙。
 *
 * 读取面 = `StaticRoutes.uploadsRoutes`：`GET /uploads/<sid>/<file>` 授权读
 * `resource:attachment:<sid>/<file>`（见 `OwnerStore.resourceKey` 与
 * `UploadsRoutesSpec` 的钉形）。因此落盘侧 `AttachmentCommitService.commit` 的
 * `resourceId` 必须逐字等于 `<sid>/<file>`——修前 WebSocket 上传腿走 2 参
 * `commit(access, key, …)`（resourceId = 裸文件名），登记的键与读取面要的键
 * 不同 ⇒ 即便文件落盘、`/uploads/<sid>/<file>` 也一律 403。
 *
 * `<sid>` 是前端帧里的**聊天会话 id**，逐段进 URL。本对象是它的唯一形状闸：
 * 必须是**单个**安全路径段（拒空、拒 `.`/`..`、拒 `/`、拒 NUL、拒过长），
 * 否则返回 `None`（调用方**不落盘**并留 warn 日志——宁可如实不落，也不落一个
 * 读取面永远取不到、或落在 uploads 根之外的名字）。
 */
private[gateway] object AttachmentScope:

  /** 路径段长度上限（与 `ResourceKey` 的 128 同档，防超长名撑爆文件系统）。 */
  private val MaxSegmentLength = 128

  /** 聊天会话 id → 可安全充当**单段** URL 路径的身份；不合法 ⇒ `None`。 */
  def segmentOf(raw: String): Option[String] =
    if raw == null then None
    else
      val s = raw.trim
      if s.isEmpty || s.length > MaxSegmentLength then None
      else if s == "." || s == ".." then None
      // 单段：任何路径分隔符/NUL/控制字符都出局（`%2F` 解出来就是 `/`，
      // 带上它就是两段——读取面按 2 段判形，落了也取不回来）。
      else if s.exists(c => c == '/' || c == '\\' || c == '\u0000' || c.isControl) then None
      else Some(s)
end AttachmentScope
