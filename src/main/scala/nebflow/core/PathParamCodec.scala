package nebflow.core

/**
 * The ONE source of truth for the "filesystem path ⇄ URL query parameter" pair
 * (imgref batch, 2026-09-18 作者令).
 *
 * 作者实证（2026-09-18，同一改面的两起失败）：
 *   ① 路径含空格的引用被 `%20` 编码后交给 Card ⇒ 解析器按**字面**去找
 *      `/Users/you/My%20Project/…` ⇒ `not-found`；
 *   ② 原样空格路径 ⇒ 工具回包 `proxied=2 / failed=0`，前端 `img` 实际仍加载失败
 *      —— 「代理改写成功 ≠ 前端取回成功」。
 *
 * 两起失败是同一个分歧的两头：**产 URL 的一侧与解 URL 的一侧各用各的编码口径**。
 * 本件把这一对收敛成单点，判据两条：
 *
 *   · **产**（[[encode]]）：空格一律出 `%20`。`java.net.URLEncoder` 是
 *     `application/x-www-form-urlencoded` 口径（空格 → `+`），而 `+` 只在 form
 *     语境里才等于空格 —— 在路径里它是**字面加号**。既有先例 =
 *     `RestApiRoutes.encSeg`（`URLEncoder.encode(s,"UTF-8").replace("+","%20")`，
 *     注释「与 NeblinkClient.enc 同法」，代理腿转发用）。
 *   · **解**（[[decode]]）：先折 **bare `+`**（旧载荷 / 重放卡里 JVM form 编码留下
 *     的），再 percent-decode；`%2B`（真加号）不动。与前端唯一的解码纪律
 *     `web/js/nfTicket.js decodePathParam` 逐字同法 —— 该件自称 “the ONE statement
 *     of this discipline in the repo”，本件把它在 JVM 侧补齐，两侧成对一致。
 *
 * 🔴 **解码不参与任何权限判定**。它只回答「去问哪个字符串」：工具侧每个候选形态仍
 * 原样过 `FileRefs.probeFile`（扩展名 / 存在性 / 大小 / 真可读 / 可服务），端点侧每个
 * 候选形态仍原样过 `WebSocketRoutes.nfFileVerdict`（词法归一 → `exists`/`isRegularFile`
 * → `toRealPath` → R2 inode → credential namespace → realpath 上的扩展名）。判据全部
 * 作用在 **realpath** 上，与字符串形态无关 ⇒ 变形形态**造不出**「原串判不住、变形后
 * 判得住」的穿透：`%2e%2e` / `%2F` 解出来只是 `..` / `/`，与直接写 `..` / `/` 得到
 * 同一个 realpath、同一份判据（`NfTicketRoutesSpec` 的 traversal 断言逐条钉住）。
 */
object PathParamCodec:

  /** Path → URL query-parameter value. Space becomes `%20`, never `+`. */
  def encode(path: String): String =
    java.net.URLEncoder.encode(String.valueOf(path), "UTF-8").replace("+", "%20")

  /** URL query-parameter value → path. Folds a bare `+` (form-encoded space)
    * BEFORE percent-decoding, so `%2B` still means a literal plus.
    * `None` when the escapes are malformed. */
  def decode(encoded: String): Option[String] =
    try Some(java.net.URLDecoder.decode(String.valueOf(encoded).replace("+", " "), "UTF-8"))
    catch case _: Exception => None

  /** Filesystem-path candidates named by ONE value, **least-transformed first**:
    * the raw value, then its percent-decoded form, then its bare-`+`-folded form.
    *
    * 顺序是判据的一部分：原样先试，只有在原样**没有命中**时才轮到变形形态 —— 因此
    * 一个真的含 `+` 或 `%` 的文件名永远不会被变形形态顶掉。
    *
    * 变形只在串里**确实带** `%` 或 `+` 时产生（否则返回单元素表：零开销、零行为
    * 变化，既有全绿面逐字不动）。 */
  def candidates(value: String): List[String] =
    val v = String.valueOf(value)
    val out = scala.collection.mutable.ListBuffer.empty[String]
    def add(s: String): Unit = if s.nonEmpty && !out.contains(s) then out += s
    add(v)
    if v.indexOf('%') >= 0 then decode(v).foreach(add)
    if v.indexOf('+') >= 0 then add(v.replace('+', ' '))
    out.toList

  /** The disclosure line the author's order requires whenever a NON-raw form is
    * the one that worked ("命中时必须在回包/告警里显式说明用了哪一形态").
    * `""` when the raw form is the one that hit. */
  def formNote(value: String, form: String): String =
    if form == value then ""
    else
      val why =
        if value.indexOf('%') >= 0 && value.indexOf('+') >= 0 then "URL escapes and a bare `+`"
        else if value.indexOf('%') >= 0 then "URL escapes (e.g. `%20` for a space)"
        else "a bare `+` (the form-encoded spelling of a space)"
      s"this reference spells its path with $why, which is not a path that exists on disk; it was " +
        s"resolved as the decoded form \"$form\" instead. A path containing spaces is fine — the " +
        "proxied URL emitted below carries the decoded path"

end PathParamCodec
