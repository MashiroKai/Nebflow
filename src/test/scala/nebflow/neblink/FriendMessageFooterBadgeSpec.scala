package nebflow.neblink

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite
import nebflow.neblink.FriendCodecs.given

import scala.io.Source

/** 批 D（agent 代发 footer 标识）**本支专属**断言 —— 病灶 = 读路径上三处
  * 「白名单式字段枚举」把服务端已下发的 `origin` 抹掉：
  *
  *   ① 网关会话模型 `MessageSummary`（本仓 Scala）—— REST 出参经
  *      `RestApiRoutes` 的 `_.asJson` **重编码** ⇒ 模型无该字段 = 前端永远拿不到；
  *   ② 浏览器 `messages.js#frameMessage`（live 帧路径）—— 逐字段显式枚举 ⇒ 丢键；
  *   ③ 浏览器 `messages.js` 的徽标可见性门 —— 修前 `out && isAgentSent(m)` 让
  *      接收侧永不进入分支（作者裁定「双方可见」后放开）。
  *
  * 本件钉**本批新增面**：三态解码 / 加性编码（省键纪律）/ 出参键序 / 两侧手写
  * codec 成对，以及三处读路径的**文本契约门**（源码级，语义谓词而非行号锚）。
  *
  * 渲染面（DOM 计数 / 文案 / 缓存两态 / P5 兜底方向）**不在本件**：那些是
  * `scripts/verify-friendmsg-badge-render.mjs` 的判据（需真浏览器）。
  * 与 `FriendMessageOriginSpec.scala`（r2 已改，本批**不动**）分工：那份钉**发送侧
  * 置位**（`sendAsAgent → origin=Some("agent")` 上 wire），本件钉**读路径不丢字段**。 */
class FriendMessageFooterBadgeSpec extends FunSuite:

  private val repoRoot = os.pwd

  // ── 夹具 ────────────────────────────────────────────────

  /** 完整 5 键旧形态 + 可注入的 origin 片段（缺键 / null / 取值三态）。 */
  private def raw(originFragment: String = ""): String =
    s"""{"id":7,"senderId":"u1","kind":"text","body":"hi","createdAt":1700000000$originFragment}"""

  private val Legacy5Key = """{"id":1,"senderId":"u1","kind":"text","body":"b","createdAt":1}"""

  private def decodeMessage(json: String): MessageSummary =
    parse(json).flatMap(_.as[MessageSummary]).fold(e => fail(s"decode failed: $e"), identity)

  // ===== (A) 网关模型 / codec（REST 出参面）=====

  test("D-A1 解码三态互不折叠：agent / user / 缺键 / null") {
    assertEquals(decodeMessage(raw(""","origin":"agent"""")).origin, Some("agent"))
    assertEquals(decodeMessage(raw(""","origin":"user"""")).origin, Some("user"))
    // 「不可判」与「服务端明说 user」是两态：缺键 / null 一律 None，
    // **禁**折叠成 Some("user")（折叠会把老服务端的缺键伪装成确证值）。
    assertEquals(decodeMessage(raw()).origin, None, "缺键 ⇒ None")
    assertEquals(decodeMessage(raw(""","origin":null""")).origin, None, "null ⇒ None（与缺键同义）")
  }

  test("D-A2 缺 origin 键的旧形态**整体可解**（加性字段不改变解码门槛）") {
    val m = decodeMessage(raw())
    assertEquals((m.id, m.senderId, m.kind, m.body, m.createdAt), (7L, "u1", "text", "hi", 1700000000L))
    assertEquals(m.attachments, None)
  }

  test("D-A3 编码：origin=None ⇒ **省键**，出参与旧 5 键形态**逐字节相同**") {
    val m = MessageSummary(1L, "u1", "text", "b", 1L)
    assertEquals(
      m.asJson.noSpaces,
      Legacy5Key,
      "None 必须省键（不是 \"origin\":null）：老服务端→前端路径的字节形态不变"
    )
  }

  test("D-A4 编码：origin=Some ⇒ 键在且值原样出（网关 REST 重编码不再抹掉它）") {
    val j = MessageSummary(1L, "u1", "text", "b", 1L, None, Some("agent")).asJson
    assertEquals(j.hcursor.get[String]("origin").toOption, Some("agent"))
    assert(j.asObject.exists(_.contains("origin")), s"出参必须带 origin 键：${j.noSpaces}")
  }

  test("D-A5 出参键序：legacy 5 键不动，加性键 origin → attachments 追加在后") {
    val att = AttachmentSummary(id = "a1", name = "n.txt", size = 3L, sha256 = "aa", state = "ready", mime = None)
    val j   = MessageSummary(2L, "u1", "text", "b", 2L, Some(List(att)), Some("agent")).asJson
    assertEquals(
      j.asObject.map(_.keys.toList),
      Some(List("id", "senderId", "kind", "body", "createdAt", "origin", "attachments")),
      "前 5 键顺序 = 旧形态；加性键在后"
    )
  }

  test("D-A6 往返：agent 来源消息 encode → decode 逐字段等价（两侧手写 codec 成对）") {
    val m    = MessageSummary(3L, "u1", "text", "b", 3L, None, Some("agent"))
    val back = parse(m.asJson.noSpaces).flatMap(_.as[MessageSummary])
    assertEquals(back, Right(m))
  }

  test("D-A7 出参形态：origin 是**字符串类型**的加性键（非 null / 非对象）") {
    val j = MessageSummary(4L, "u1", "text", "b", 4L, None, Some("user")).asJson
    assertEquals(
      j.asObject.map(_.keys.toSet),
      Some(Set("id", "senderId", "kind", "body", "createdAt", "origin")),
      "键集 = 旧 5 键 + origin"
    )
    assertEquals(j.hcursor.get[String]("origin").toOption, Some("user"))
  }

  // ===== (B) 前端读路径的文本契约门（源码级 · 语义谓词）=====

  private def read(rel: String): String =
    val p = repoRoot / os.RelPath(rel).segments
    assert(os.exists(p), s"源码不存在：$p（本 spec 必须在仓根运行）")
    val src = Source.fromFile(p.toIO, "UTF-8")
    try src.mkString
    finally src.close()

  private lazy val messagesJs: String = read("src/main/resources/web/js/messages.js")

  /** 去掉**整行注释**（本文件注释极多且含 `:` / `{` / `//` 形态的引文，会污染
    * 键集提取与守卫判读）。只丢注释行、不丢代码行 ⇒ 判据仍作用在真实代码上。 */
  private def codeOnly(src: String): String =
    src.linesIterator
      .filterNot { l =>
        val t = l.trim
        t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/")
      }
      .mkString("\n")

  /** 取顶层 `function <name>(…)` 的函数体（花括号配平扫描）—— **不依赖行号**。 */
  private def functionBody(src: String, name: String): String =
    val marker = s"function $name("
    val head   = src.indexOf(marker)
    assert(head >= 0, s"未找到顶层函数 $name（判据锚已漂移）")
    val open = src.indexOf('{', head)
    assert(open > head, s"$name 的函数体起始花括号未找到")
    var depth = 0
    var i     = open
    while i < src.length do
      src.charAt(i) match
        case '{' => depth += 1
        case '}' =>
          depth -= 1
          if depth == 0 then return src.substring(open + 1, i)
        case _ => ()
      i += 1
    fail(s"$name 的函数体花括号不配平（判据无法判读）")

  private lazy val code = codeOnly(messagesJs)

  test("D-B1 frameMessage 的白名单**含** origin，且绑定到帧上的 p.origin") {
    val body = functionBody(code, "frameMessage")
    // 语义谓词 = 返回对象字面量的**键集**（不是行号 / 不是整句字面串）：
    // 键集随格式化、注释、字段重排而变的情况下判据仍成立。
    val keys = """(?m)^\s*([A-Za-z_$][\w$]*)\s*:""".r.findAllMatchIn(body).map(_.group(1)).toList
    assert(keys.contains("origin"), s"frameMessage 键集缺 origin ⇒ live 帧路径丢字段。键集=$keys")
    val bound = """(?m)^\s*origin\s*:\s*p\.origin\b""".r.findFirstIn(body)
    assert(bound.isDefined, s"origin 必须取自帧字段 p.origin（禁硬编码取值）。body=$body")
  }

  /** 取 `if (…)` 的**守卫表达式**（括号配平扫描）。
    * 🔴 朴素 `[^)]*` 会在 `isAgentSent(m)` 的**内层括号**处截断（实得
    * `isAgentSent(m`）—— 本件初版的判据即栽在此处（同族缺陷：判据本身写错 ⇒ 假红/假绿）。 */
  private def guardOf(stmt: String): String =
    val m = """if\s*\(""".r.findFirstMatchIn(stmt).getOrElse(fail(s"该语句缺 if 守卫：$stmt"))
    var depth = 1
    var i     = m.end
    while i < stmt.length && depth > 0 do
      stmt.charAt(i) match
        case '(' => depth += 1
        case ')' => depth -= 1
        case _   => ()
      i += 1
    assert(depth == 0, s"守卫括号不配平：$stmt")
    stmt.substring(m.end, i - 1)

  test("D-B2 徽标可见性门 = isAgentSent(m)，`out` 不再是条件（作者裁「双方可见」）") {
    val line = code.linesIterator.find(_.contains("fm-msg-agent-badge"))
      .getOrElse(fail("未找到徽标渲染语句（判据锚已漂移）"))
    // 语义谓词 = 该语句的**守卫表达式**（归一化空白后比对），而不是整句字面串。
    val guard = guardOf(line).replaceAll("\\s+", "")
    assertEquals(guard, "isAgentSent(m)", s"守卫应为 isAgentSent(m)：$line")
    assert(!guard.contains("out"), s"守卫不得再含方向门 out（接收侧将永不显徽标）：$line")
  }

  test("D-B3 resolveOut 兜底档不再恒 out：两源皆缺席走显式证据判据（P5）") {
    val body = functionBody(code, "resolveOut")
    // ① 回归向量钉：修前那一行式（`byId === null ? true : byId`）**必须不存在** ——
    //    它把「两源皆缺席」静默判成 out（r2 引入的方向翻转，本批修点）。
    val silentFlip = """byId\s*===\s*null\s*\?\s*true\s*:\s*byId""".r.findFirstIn(body)
    assert(silentFlip.isEmpty, s"静默翻转回归向量仍在：${silentFlip.getOrElse("")}")
    // ② 正向：兜底档必须经**显式**证据存在性判据（可解释、可审计），而非裸字面量。
    assert(
      body.contains("hasDirectionEvidence"),
      s"兜底档必须使用显式证据判据 hasDirectionEvidence（禁静默字面量）：$body"
    )
  }

  test("D-B4 hasDirectionEvidence 的语义 = 两源任一在场（缺席定义唯一：undefined/null/空串）") {
    val body = functionBody(code, "hasDirectionEvidence")
    val hasSid = body.contains("m.senderId") || body.contains("m && m.senderId")
    assert(hasSid, s"证据源须含 senderId：$body")
    assert(body.contains("conv.friend"), s"证据源须含 conv.friend.userId：$body")
    assert(body.contains("present"), s"缺席定义必须单点收敛为 present 谓词：$body")
  }
