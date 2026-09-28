package nebflow.neblink

/**
 * E4 回执闸（腿 A 补件批 4b1 · §F.1b）—— **唯一**的 fail-closed 判定点。
 *
 * ## 为什么单列一处
 * 本仓此前 E4（`POST /api/attachments/{id}/received`）**零调用点**（腿 A 复核已判事实）
 * ⇒ 裁定③「接收完毕即删」的主触发不在本腿落点。补件批要的不是「加上一次调用」，
 * 而是把**什么算接收完毕**做成一个**可判、可测、缺证据即不发**的闸：本对象即该闸，
 * 引擎侧（网关鉴权路由 → [[FriendService.ackAttachmentReceived]]）是**唯一**调用者。
 * 🔴 前端**不复制**第二套判定（只上报证据，见 `web/js/friendsApi.js#ackAttachmentReceived`）。
 *
 * ## 方向 = fail-closed（逐字自裁定口径）
 * **验证不了就不删服务端 blob**。全部 `Evidence` 字段缺一即 `Skip`：
 * 「缺证据」**不等于**「通过」；`Skip` 一律 ⇒ **零 E4**（服务端 blob 由 24 h TTL 兜底，
 * §F.1「兜底 TTL 不是接收完毕的定义」——它只是「未接收完毕」的超时弃置）。
 *
 * ## 判据（§F.1b ①/②）
 *   - **①「落盘成功」** = 整件字节写入**最终位置**（非临时名）∧ 本地整件 sha256 == 服务端
 *     声明 digest。**UI 展示不是条件**；「开始下载」「下到一半」不算。
 *   - **②「回执内容」** = 本地算出的 **64 位小写 hex**；服务端校验不符 ⇒ `422` 零副作用
 *     （防误删）⇒ 本闸把「不符」挡在**发送之前**，绝不让服务端替我们兜。
 *
 * ## 已登记的 provisional 偏差（见报告 / 证据件 `01-byte-landing-inventory.md`）
 * 本仓 UI 路径的「最终位置 + fsync」**不可证**（落点 = 浏览器/OS 下载管理器，无成功回执面）。
 * 依 root #524 log ④ 的 fallback 口径实施：最低硬条件 = 完整字节 + 自算 sha 相符（本闸强制），
 * 「落盘完成度」由客户端以 `landedFinal` 显式申报（申报 false 即 `Skip`）。
 */
object AttachmentAck:

  /** 客户端上报的「落盘证据」。字段一律 `Option`/显式布尔 —— 缺证据就是缺证据。 */
  final case class Evidence(
    /** 客户端**本地自算**的整件 sha256（64 位小写 hex）；拿不到字节 ⇒ `None`。 */
    localSha256: Option[String],
    /** 服务端**声明**的整件 digest（消息元数据 `AttachmentDto.sha256` / E3 的 `X-Attachment-Sha256`）。 */
    declaredSha256: String,
    /** 客户端持有的完整字节数（`blob.size`）；拿不到 ⇒ `None`。 */
    receivedBytes: Option[Long],
    /** 服务端声明的整件长度（消息元数据 `AttachmentDto.size`）；拿不到 ⇒ `None`。 */
    expectedBytes: Option[Long],
    /** 是否已落**最终位置**（或经 OS 保存且拿到成功回执）。**申报 false ⇒ 一律不发**。 */
    landedFinal: Boolean
  )

  /** 闸的输出：要么发（携**本地**算出的 digest），要么不发（带可判读原因）。 */
  enum Decision:
    case Fire(digest: String)
    case Skip(reason: String)

  /** 闸的**结局**（含发送面结果）；`Failed` **永不**上抛——失败静默容忍（见 service）。 */
  enum Result:
    case Acknowledged
    case Skipped(reason: String)
    case Failed(reason: String)

  /** 64 位小写 hex（§F.1b ② 的**唯一**形态；大写/短串/非 hex 一律拒）。 */
  private val Digest = "[0-9a-f]{64}".r

  def isDigest(s: String): Boolean = Digest.matches(s)

  /** 规范化上报值：去空白 + 转小写（**只**做形态归一，不做任何「宽容」判定——
    * 归一后仍须逐字 `.matches`）。空串 ⇒ `None`（= 未上报）。 */
  def normalize(raw: Option[String]): Option[String] =
    raw.map(_.trim.toLowerCase).filter(_.nonEmpty)

  /** 纯判定（无 IO）：**唯一**的 yes/no 点。顺序即优先级，任一 Skip ⇒ 零 E4。 */
  def decide(ev: Evidence): Decision =
    val local    = normalize(ev.localSha256)
    val declared = ev.declaredSha256.trim.toLowerCase
    if !ev.landedFinal then Decision.Skip("not-landed-final")
    else if local.isEmpty then Decision.Skip("bytes-unavailable")
    else if declared.isEmpty then Decision.Skip("digest-missing")
    else if !isDigest(declared) then Decision.Skip("digest-shape")
    else if !isDigest(local.get) then Decision.Skip("digest-shape")
    else if ev.receivedBytes.isEmpty || ev.expectedBytes.isEmpty then Decision.Skip("bytes-incomplete-unverifiable")
    else if ev.receivedBytes.get != ev.expectedBytes.get then Decision.Skip("bytes-incomplete")
    else if local.get != declared then Decision.Skip("digest-mismatch")
    else Decision.Fire(local.get)

end AttachmentAck
