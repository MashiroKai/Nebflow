package nebflow.neblink

import java.nio.charset.StandardCharsets.UTF_8

/**
 * 附件上传 `uploadId` 的**形态闸** —— **唯一实现点**（attachid 批，2026-09-16）。
 *
 * ==WHY（缺陷原文 = 独立复核位 A1）==
 *
 * 网页腿的 `uploadId` 是**客户端可控**的查询参数（`RestApiRoutes.scala:1691`，
 * `POST /api/attachments?uploadId=…`），而它被直接当作**单个路径段**拼进临时件路径
 * （`FriendService.scala:1173` `PathUtil.dataRoot / "attach-uploads" / s"$uploadId.part"`）。
 * os-lib 的 `os.Path / String` 走 `BasePath.checkSegment`，**段内含 `/` 即抛**
 * `PathError.InvalidSegment`（本仓 assembly jar 内字节码实测：`indexOf(47)` 命中即抛，
 * 文案逐字 `[/] is not a valid character to appear in a non-literal path segment.`）；
 * 该抛点位于 `uploadStream` 续体的 `handleErrorWith` **之外**（后者只包 `:1175` 起的
 * for 推导）⇒ 冒泡成 http4s 的 **500 + 空体**（无 `code`/`error`、无日志），调用方只能
 * 看到「失败」。本批实测：`uploadId` 含 `/` 的 7 个形态一律 `500` + 体长 0（零落盘、
 * 零上游调用 —— 即**不是**路径穿越，但失败形态不可判读）；含 NUL 同样 `500` + 空体。
 *
 * ==本闸的口径（与 `TargetDirGuard` 同族纪律，**不新造第二套机制**）==
 *
 *   1. **先在字符串上判死，再交给路径构造** —— 🔴 禁把「构造器抛异常」当闸位
 *      （`TargetDirGuard` 文件头同款判据：`InvalidSegment` 是单段口的天然行为，不是校验）；
 *   2. **词法判定、未触盘** ⇒ 非法 id 在**任何**消费点上都是零落盘 / 零上游调用 /
 *      零临时件（判定发生在任何 `os.` 调用之前）；
 *   3. **正集白名单**（不是黑名单）：id 只允许 ASCII 字母 / 数字 / `.` / `_` / `-`，
 *      且 UTF-8 长度 ≤ [[MaxLength]]。白名单**天然包含** `/`、`\`、NUL、控制字符、`%`、
 *      非 ASCII ⇒ 无需枚举黑名单（黑名单漏一个就是逃逸面）。跨仓真源
 *      `neblink-server/src/attachments.rs:261` 的 `is_valid_attachment_id` 是**同一种规则**
 *      （正字符集 + 长度上限）—— 那边字符集更窄，是因为它校验的是**服务端自造**的 UUIDv4；
 *      本处要放行的是浏览器自造的 `att-<ms>-<seq>-<base36>`
 *      （`web/js/attachUpload.js:293`），逐字在集内 ⇒ 合法客户端零影响；
 *   4. 🔴 **禁「洗字符再继续」**（禁把 `/` 替换成安全字符后走原路径）：那会改变 id 语义
 *      （取消键与上传键必须逐字同一个串）并可能造出新的等价面。本闸只有**原样放行**或
 *      **拒**两种结局，**没有**第三条路径；
 *   5. **缺席 / 空白 ≠ 非法**：`uploadId` 缺席是**既有合法语义**（本次上传不可取消，
 *      服务端自造 `anon-…` 仅供进度帧关联，见 `RestApiRoutes.scala:1723-1726`）⇒ 调用方
 *      **只在非空时**调用本闸，该面逐字不变。
 *
 * ==消费点（`grep` 现取枚举，全部走本闸，禁各写一份）==
 *
 *   - `RestApiRoutes` 上传路由：查询参数 `uploadId`（读点 `:1691`）
 *   - `RestApiRoutes` 取消路由：路径段 `uploadId`（读点 `:1817`）
 *   - `FriendService.uploadStream`：唯一拼临时件路径的点（`:1173`）—— fail-closed 兜底，
 *     任何未来调用者都绕不过
 *   - `AttachUploadRegistry`：只把 id 当**内存键**（不拼路径、不触盘）⇒ 由上面两处入口
 *     保证到它手里的 id 已合法；本类不做第二套判定（禁多点漂移）
 */
object AttachUploadId:

  /**
   * 长度上限（UTF-8 字节）。远低于 POSIX `NAME_MAX`(255) —— 上限的用途是把**无界输入**
   * 挡在文件系统之外：超长 id 会让临时件名撞 `ENAMETOOLONG`，而那是另一条
   * 「不可判读的失败」路（本批实测：5000 字节 id ⇒ `413 attach_too_large`，形状误导）。
   * 实测客户端 id ≈ 30 字节（`att-<13 位 ms>-<1 位 seq>-<6 位 base36>`）⇒ 128 留足余量。
   */
  val MaxLength: Int = 128

  /**
   * 错误码 = 网关既有 4xx 信封沿用的码（同路由 `:1694` / `:1696` 的两条 400 用的就是它）。
   * 形状 `{ok:false, code, error}` **不新造第二套**；`code` 也不新造（前端/调用方已有该码的
   * 处理面）。
   */
  val ErrorCode: String = "invalid_argument"

  /** 允许字符集 = `[A-Za-z0-9._-]`（正集，见类头 ③）。 */
  private def allowed(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      c == '.' || c == '_' || c == '-'

  /**
   * 回显安全化：不可打印字符渲染成 `\uXXXX`。错误体与日志**不得**被 NUL / 控制字符
   * 污染（原样回显等于把输入里的控制序列搬进日志与 JSON 体）。
   */
  private def render(raw: String, max: Int = 64): String =
    val sb = new StringBuilder
    var i = 0
    while i < raw.length && sb.length < max do
      val c = raw.charAt(i)
      if c >= ' ' && c <= '~' then sb.append(c) else sb.append(f"\\u${c.toInt}%04x")
      i += 1
    if i < raw.length then sb.append("…") // 截断回显（回显不得无界放大）
    sb.toString

  /**
   * 单个字符的**安全**回显：控制字符只给码位（不得原样搬进错误体 / 日志），其余原样给
   * 字形 + 码位（非 ASCII 的可打印字符也是可打印的 —— 拒因文案不得把人读得懂的字说成
   * 「不可打印」）。
   */
  private def showChar(c: Char): String =
    val cp = f"U+${c.toInt}%04X"
    if Character.isISOControl(c) then s"$cp (control character)" else s"$cp ('$c')"

  private val Tail = " — nothing was uploaded."

  /**
   * **唯一判定入口**。
   *
   * @return `Right(id)` = 合法 id（**去首尾空白后的原样串**，调用方必须用这个值，禁再用原始
   *         入参）；`Left(reason)` = 拒因（英文、自描述，供错误体的 `error` 字段**直接透出**）。
   */
  def validate(raw: String): Either[String, String] =
    val id = Option(raw).getOrElse("").trim
    val len = id.getBytes(UTF_8).length
    if id.isEmpty then
      Left(s"Invalid uploadId: it is empty; use a non-empty id (letters, digits, '.', '_', '-') or omit it$Tail")
    else if len > MaxLength then
      Left(
        s"Invalid uploadId: $len UTF-8 bytes exceeds the $MaxLength-byte limit (value starts with '${render(id)}')$Tail"
      )
    else if id.forall(_ == '.') then
      Left(s"Invalid uploadId: '${render(id)}' is a dots-only name ('.' / '..' and friends are not valid ids)$Tail")
    else
      val bad = id.indexWhere(c => !allowed(c))
      if bad >= 0 then
        Left(
          s"Invalid uploadId: forbidden character ${showChar(id.charAt(bad))} at index $bad in '${render(id)}' " +
            s"— allowed: ASCII letters, digits, '.', '_', '-' (at most $MaxLength bytes); " +
            s"omit the parameter for an upload that cannot be cancelled$Tail"
        )
      else Right(id)
    end if
  end validate

end AttachUploadId
