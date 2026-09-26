package nebflow.neblink

/**
 * 好友附件**能力判据**（4b 腿 A-5）—— 裁定④「不加能力自报字段」下，客户端唯一可用的
 * 判读面 = **路由存在性**（跨仓契约件 §G.2；本件是它在客户端腿的唯一实现点）。
 *
 * ## 为什么不是「自报字段」
 * `neblink-server` 侧零能力端点（§G.1：`/api/info` 仍只 `name`/`version`/`port`，
 * `/api/health` 仍只 `{"status":"ok"}`，不新增 capability 端点），本仓**也不新增任何
 * 自报字段/本地开关**（裁定④）。判据只用两样东西：**我们对 `POST
 * /api/friends/{id}/attachments` 发一次探测请求**，以及**服务端对它的响应码**
 * —— 老服务端没有这条路由 ⇒ `404`；新服务端路由在 ⇒ 参数校验/关系闸先答话
 * （`422`/`403`/`429`…）。两者互斥，判据**单调**（能力上线 = 路由上线）。
 *
 * ## 探测请求为什么是「故意非法的声明」
 * E1 的校验顺序（§B.1 E1 表）是「先闸后参」且**校验通过前不落任何行**：`name` 空 /
 * `size = 0` 必被拒（`422 INVALID_ARGUMENT` / `ATTACH_TOO_LARGE`）。用这份体探测 ⇒
 * 新服务端**零副作用**（不建上传会话），老服务端 `404`。若某些实现没走这份校验而
 * 回了 `2xx`（契约不符，但路由显然存在），我们按「有能力」处理并**如实登记**：那会
 * 留下一条 `uploading` 会话，由服务端 24 h 强删兜底（§F.1）。
 *
 * ## 三态与「不可判」
 * | 态 | 触发 | 行为（见 [[AttachmentCapability.refusal]] / 工具面） |
 * |---|---|---|
 * | [[AttachmentCapability.Supported]] | 2xx 或任何**非 404 的 HTTP 码**（422/403/429/…） | 允许带附件（进入 E1/E2 上传链） |
 * | [[AttachmentCapability.Unsupported]] | `HTTP 404`（无该路由） | **明确拒绝**并回执「服务端不支持附件」 |
 * | [[AttachmentCapability.Undetermined]] | 传输层失败 / 5xx / 无会话 / 响应不可解释 | **明确拒绝**并回执「无法确认服务端附件能力」 |
 *
 * 「不可判」为什么也**拒绝**（而不是降级发纯文本）：§G.3 的契约要求是「发送前必须完成
 * 一次存在性探测；**禁止**在未探测的情况下直接携带 `attachments` 发送」。探测不可判
 * ⇒ 探测未完成 ⇒ 只有「不发送附件」不违约；此刻静默改发纯文本 = 信息包 §5.1-C 的
 * 静默不达（用户以为附件发出去了）。两者都是 fail-closed，**文案必须可区分**（否则
 * 用户无法判断该改服务端版本还是该重试）。
 */
enum AttachmentCapability:
  /** 对齐：服务端有附件路由。`evidence` = 逐字响应读数（写进工具回执/日志）。 */
  case Supported(evidence: String)

  /** 不对齐：服务端明确无该路由。 */
  case Unsupported(reason: String)

  /** 不可判：信息缺失/未知（传输失败、5xx、无会话…）。 */
  case Undetermined(reason: String)

object AttachmentCapability:

  /** 探测请求体（**故意非法**，见类注释：校验通过前零落行，故探测零副作用）。 */
  val ProbeBody: String = """{"name":"","size":0,"sha256":"probe"}"""

  /** 探测目标路径（= E1，§B.1）。 */
  def probePath(friendUserId: String): String =
    s"/api/friends/${java.net.URLEncoder.encode(friendUserId, "UTF-8")}/attachments"

  /**
   * `Left` 文本里的 HTTP 码（`NeblinkClient.sendRequest*` 的非 2xx 形态逐字为
   * `"HTTP <code>: <body>"`；传输层失败**不带**该前缀 ⇒ None）。
   *
   * 🔴 `(?s)….*` 不是装饰：Scala 的 `Regex` **模式匹配**要求整串匹配
   * （`unapplySeq` 走 `matched`），只写 `^HTTP (\d{3})\b` 会**永远不匹配**
   * —— 本批实测踩到过（422 被判成「传输失败」⇒ 能力判据反向）。
   */
  private val HttpStatusRe = """(?s)^HTTP (\d{3})\b.*""".r

  def httpStatus(err: String): Option[Int] = err match
    case HttpStatusRe(code) => code.toIntOption
    case _ => None

  /** 唯一判定点（纯函数，A-5 三态钉的直接被测对象）。 */
  def judge(result: Either[String, String]): AttachmentCapability =
    result match
      case Right(_) =>
        Supported("probe answered 2xx — the attachment route exists")
      case Left(err) =>
        httpStatus(err) match
          case Some(404) =>
            Unsupported(err)
          case Some(code) if code >= 500 =>
            Undetermined(s"server error on the existence probe — $err")
          case Some(code) =>
            // 400/401/403/409/413/415/422/429/431… 都是「路由在、请求被拒」：
            // 老服务端不可能对一条不存在的路由答这些码（它对任何未注册路径答 404）。
            Supported(s"probe rejected with HTTP $code — the attachment route exists ($err)")
          case None =>
            if err == "Not logged in" then Undetermined("no NebLink session — probe not attempted")
            else Undetermined(s"probe transport failure — $err")

  /** 工具面拒绝文案（**可判读**：说明「附件未发送」+ 原因 + 下一步）。 */
  def refusal(cap: AttachmentCapability): String = cap match
    case Supported(_) => ""
    case Unsupported(reason) =>
      "Attachments were NOT sent: the NebLink server does not support attachments " +
        s"(the upload route `POST /api/friends/<id>/attachments` does not exist on this server — $reason). " +
        "Send text only, or use a `device:` target for files. Nothing was uploaded and no message was sent."
    case Undetermined(reason) =>
      "Attachments were NOT sent: could not confirm the server supports attachments " +
        s"($reason). This is not a permission error — retry once the connection to the NebLink server is back, " +
        "or send text only (`device:` targets transfer files over the device channel). Nothing was uploaded " +
        "and no message was sent."
end AttachmentCapability
