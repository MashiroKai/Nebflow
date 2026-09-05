package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

import nebflow.service.{MemoryBudget, MemoryStore}

/**
 * MemoryEdit（阶段 2c agent 收敛，设计文档 §C.2）——Nebula 专用记忆维护工具，
 * 替代 Nebula 的 Read/Write/Edit 记忆通道（裁定 2：移除 Nebula 的 Write/Edit）。
 *
 * 接口语义（§C.2 原文）：
 *   target : "user" | "agent"   → ~/.nebflow/User.md | ~/.nebflow/agents/Nebula/memory.md
 *   action : "append" | "update" | "remove" | "replace_section"
 *   section?  — ## 标题精确匹配；append 缺省 = 文件尾
 *   match?    — update/remove 定位：条目内精确子串（首个命中）
 *   content?  — append/update 的新文本（条目级，不是整文件）
 *
 *   append          = section 尾（无 section 则文件尾）追加一条目
 *   update          = 按 match 定位首个命中条目，替换为 content
 *   remove          = 删除定位条目
 *   replace_section = 整节替换（memory-consolidation 清理用，防多次 remove 漏删）
 *
 * 路径安全（H-1 已确认①）：目标路径白名单硬编码为上述两个文件——工具自身即
 * 路径校验层，schema 无任何路径参数，不套 project 沙箱（Nebula 本就在沙箱外，
 * 约束内建在工具里，§A.7 豁免面）。User.md / memory.md 双文件沿用 MemoryStore
 * 既有读取链与写入函数（saveUserMemory / saveAgentMemory——写后自动失效
 * mtime 缓存，下一 lifecycle 节点生效，ContextRefresher.buildMemoryBlock 注入
 * 链零改动）。
 *
 * 设计取舍（§C.2）：条目化操作而非全文重写——全文重写参数（整文件 content）
 * 不提供，杜绝一次幻觉抹掉全部记忆；replace_section 是唯一大粒度操作且限定节级。
 * 操作无命中时结构化报错并列出既有条目前缀（可行动自纠，工具错误消息惯例）。
 *
 * 条目模型：markdown 列表行（"- " 开头），沿用现状条目格式
 * `- <fact>（→<id> 详情在 ~/.nebflow/memory/<id>.md）`（ContextRefresher
 * buildMemoryBlock 头注同款）。section = "## " 开头的标题行，节体 = 标题行到
 * 下一标题行/文件尾之间的内容。
 */
object MemoryEditTool extends Tool:

  val name = "MemoryEdit"

  val description =
    """Edit Nebula's persistent memory files — entry-level operations on the two-file memory whitelist. Changes take effect at the next lifecycle node (memory is injected into the system prompt per turn); no need to re-read to verify.
## Targets (hardcoded whitelist — no path parameter exists)
- target=user → ~/.nebflow/User.md — user facts: identity, preferences, working style, environment.
- target=agent → ~/.nebflow/agents/Nebula/memory.md — routing experience, technical lessons, domain knowledge.
## Actions
- append: add an entry at the end of `section` (omit `section` = end of file). `content` required.
- update: locate the FIRST entry containing `match` (exact substring; scope to `section` when given) and replace it with `content`. Both required.
- remove: locate the same way and delete the entry. `match` required.
- replace_section: replace the ENTIRE body of `section` with `content` (bulk cleanup — use instead of many removes). Both required.
## Semantics
- Entries are markdown list lines ("- ..."); convention: `- <fact>（→<id> detail at ~/.nebflow/memory/<id>.md）`.
- `section` matches a "## Heading" line exactly (the "## " prefix is optional in the parameter).
- No whole-file rewrite exists by design — memory cannot be wiped in one call.
- append/update `content` must be ONE entry: a single line starting with "- ". Multi-line content is rejected (MEMORYEDIT_ENTRY_FORMAT) — drifted non-entry lines would be invisible to update/remove forever. Use replace_section for a multi-line section body.
- Concurrency: same-file MemoryEdit calls are serialized per file within this process (a read-modify-write is atomic against other MemoryEdit calls). Cross-process writers and direct saves to these files from other subsystems are NOT locked — do not race them.
## Budget (write-side enforcement, 2026-09-05 memory-management ruling)
- append/update validate the POST-WRITE file size before saving. Hard budget: User.md 50KB, agent memory.md 30KB — exceeding it is rejected (MEMORYEDIT_BUDGET) with the largest sections listed: consolidate first, then write.
- Over the 80% soft line (User.md 40KB / memory.md 24KB) the write succeeds but the result carries a WARN — schedule a consolidation pass, don't wait for the weekly audit.
- replace_section is exempt by design: it is the consolidation (shrinking) channel; gating it would remove the only way back under budget. The Dream extraction hook shares the same gate on its side.
- Injection is NEVER truncated (ruling 2026-09-05 §3.3): over-budget memory silently taxes every future session — the write-side gate is the only enforcement, so honor the WARN."""

  val inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "target" -> Json.obj(
        "type"        -> "string".asJson,
        "enum"        -> Json.arr("user".asJson, "agent".asJson),
        "description" -> "Which memory file: user → ~/.nebflow/User.md; agent → ~/.nebflow/agents/Nebula/memory.md.".asJson
      ),
      "action" -> Json.obj(
        "type"        -> "string".asJson,
        "enum"        -> Json.arr("append".asJson, "update".asJson, "remove".asJson, "replace_section".asJson),
        "description" -> "append / update / remove / replace_section (see description).".asJson
      ),
      "section" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "## Heading exact match ('## ' prefix optional). append: insert at this section's end (omit = file end). update/remove: scope the match search. replace_section: the section to replace (required).".asJson
      ),
      "match" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "update/remove locator: exact substring inside a list entry (first hit wins). Required for update/remove.".asJson
      ),
      "content" -> Json.obj(
        "type"        -> "string".asJson,
        "description" -> "New text (entry-level). Required for append/update/replace_section.".asJson
      )
    ),
    "required" -> Json.arr("target".asJson, "action".asJson)
  )

  def summarize(input: JsonObject): String =
    val t = input("target").flatMap(_.asString).getOrElse("?")
    val a = input("action").flatMap(_.asString).getOrElse("?")
    s"MemoryEdit($t/$a)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 300 then result.take(297) + "..." else result

  // ------------------------------------------------------------------
  // Target whitelist（唯一写面：两个文件，别无他径）
  // ------------------------------------------------------------------

  // load 为懒 thunk（QC nit 2）：resolveTarget 只拼路径/装 thunk，不触文件系统——
  // target/参数/条目格式校验失败路径（MEMORYEDIT_TARGET / PARAM / ACTION /
  // ENTRY_FORMAT）零文件系统读（不再有 MtimeCache 读副作用）。
  private case class Target(path: os.Path, load: () => Option[String], save: String => IO[Unit])

  private def resolveTarget(target: String): Either[ToolError, Target] =
    target match
      case "user"  => Right(Target(MemoryStore.userMemoryPath, () => MemoryStore.loadUserMemory, MemoryStore.saveUserMemory))
      case "agent" => Right(Target(MemoryStore.agentMemoryPath("Nebula"), () => MemoryStore.loadAgentMemory("Nebula"), MemoryStore.saveAgentMemory("Nebula", _)))
      case other =>
        Left(ToolError(
          s"MemoryEdit: unknown target '$other' — only \"user\" (~/.nebflow/User.md) and \"agent\" (~/.nebflow/agents/Nebula/memory.md) exist. (MEMORYEDIT_TARGET)"))

  // per-file 互斥（QC nit 3）：单进程内同一目标文件的整段读-改-写串行化（见 call）。
  // 取舍：锁放工具侧而非 MemoryStore.saveFile——读（readLines）也在本工具，锁住
  // 整段 RMW 才能消丢更新；store 侧锁只能串行写、护不住读-改-写窗口。跨进程
  // 写入与 WS-route 等工具外直写不在锁面内，工具 description 已明示。
  private val fileLocks = new ConcurrentHashMap[String, AnyRef]()

  private def lockFor(path: os.Path): AnyRef =
    fileLocks.asScala.getOrElseUpdate(path.toString, new Object)

  // ------------------------------------------------------------------
  // 行模型：sections = "## " 标题行；entries = "- " 列表行
  // ------------------------------------------------------------------

  private def isHeading(line: String): Boolean = line.trim.startsWith("## ")
  private def isEntry(line: String): Boolean   = line.trim.startsWith("- ")

  private def headingName(line: String): String = line.trim.stripPrefix("## ").trim

  private def canonicalSection(section: String): String = section.trim.stripPrefix("## ").trim

  /** 在 lines 中精确定位节标题（"## " 前缀可选），返回标题行下标。 */
  private def findSection(lines: Vector[String], section: String): Option[Int] = {
    val want = canonicalSection(section)
    lines.indices.find(i => isHeading(lines(i)) && headingName(lines(i)) == want)
  }

  /** 节体范围：标题行之后到下一标题行/文件尾（半开区间 [start, end)，文件级下标）。 */
  private def sectionBodyRange(lines: Vector[String], headingIdx: Int): (Int, Int) =
    val end = lines.indices.drop(headingIdx + 1).find(i => isHeading(lines(i))).getOrElse(lines.length)
    (headingIdx + 1, end)

  private def existingSectionNames(lines: Vector[String]): String =
    val names = lines.filter(isHeading).map(headingName)
    if names.isEmpty then "(none)" else names.mkString(", ")

  /** 列出条目前缀（自纠线索）：每条截断 60 字符。 */
  private def entryPrefixes(lines: Vector[String]): String =
    val entries = lines.filter(isEntry).map(l => "  " + l.trim.take(60))
    if entries.isEmpty then "  (no entries)" else entries.mkString("\n")

  private def normalizeContent(content: String): Vector[String] =
    content.trim.linesIterator.toVector

  /** 条目模型防漂移（QC nit 6）：append/update 的 content 必须是「单条目」——恰一行
    * 且以 "- " 开头。多行 content 经 normalizeContent 切行插入后，非条目行 locate
    * 永远扫不到（只认 isEntry 行）——在入口拒绝并给可行动出路。选校验而非仅写
    * description：校验可执行、可测，防漂移强度高于文档约定。replace_section 按设计
    * 语义允许多行区段体，不走此校验。 */
  private def singleEntryGuard(action: String, content: String): Option[ToolError] =
    val ls = normalizeContent(content)
    if ls.lengthIs > 1 then Some(ToolError(
      s"""MemoryEdit: $action content must be ONE entry line, got ${ls.length} lines — multi-line content drifts out of the entry model (locate scans "- " lines only; extra lines would be invisible to update/remove). Use replace_section for a multi-line section body, or append each entry separately. (MEMORYEDIT_ENTRY_FORMAT)"""))
    else if !ls.headOption.exists(_.startsWith("- ")) then Some(ToolError(
      s"""MemoryEdit: $action content must start with "- " (markdown list entry); got "${content.trim.take(60)}" — non-entry text is invisible to update/remove. (MEMORYEDIT_ENTRY_FORMAT)"""))
    else None

  private def readLines(load: () => Option[String]): Vector[String] =
    load().getOrElse("").linesIterator.toVector

  // ------------------------------------------------------------------
  // 预算闸（§6.2-2.2，2026-09-05 memory-management-plan 批次二机制一）：
  // append/update 落盘前校验【新文件总字节】——超硬顶拒绝（附 top-3 节+整理
  // 指引），超 80% 放行+结果附 WARN。与 singleEntryGuard 同层的入口校验；
  // replace_section 不闸（整理/收缩通道，见 description Budget 节）。
  // DreamMode.updateMemory 共用 MemoryBudget 判据（防 hook 侧绕过）。
  // ------------------------------------------------------------------

  private def bytesOf(content: String): Long =
    content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong

  /** 超硬顶 → Some(结构化拒绝)；其余 None（放行判定交 verdict 成功路径）。 */
  private def budgetExceeded(action: String, targetId: String, newContent: String): Option[ToolError] =
    MemoryBudget.verdict(targetId, bytesOf(newContent)) match
      case MemoryBudget.Exceeded(_, _) =>
        Some(ToolError(MemoryBudget.exceededMessage(
          action, targetId, targetPathLabel(targetId), bytesOf(newContent), newContent)))
      case _ => None

  private def targetPathLabel(targetId: String): String =
    if targetId == "user" then "~/.nebflow/User.md" else "~/.nebflow/agents/Nebula/memory.md"

  /** 成功结果文本；80% 软警时在生效提示后追加 WARN（放行不拦截）。 */
  private def okWithBudget(action: String, path: os.Path, detail: String, targetId: String, newContent: String): Either[ToolError, String] =
    val base = ok(action, path, detail)
    MemoryBudget.verdict(targetId, bytesOf(newContent)) match
      case MemoryBudget.Warn(_, _, _) => base.map(_ + "\n\n" + MemoryBudget.warnNotice(targetId, bytesOf(newContent)))
      case _                          => base

  /** 结果文本：动作 + 文件 + 变更摘要 + 生效提示（§C.2 结果语义）。 */
  private def ok(action: String, path: os.Path, detail: String): Either[ToolError, String] =
    Right(
      s"""MemoryEdit ok: $action → $path
         |$detail
         |Takes effect at the next lifecycle node — no need to re-read to verify.""".stripMargin)

  /** update/remove 共用定位：可选 section 限域 + 条目内精确子串（首个命中），
    * 返回命中条目的文件级行下标（QC nit 4：lines 恒等于输入，冗余 tuple 已裁撤）。
    * Left = 结构化报错（缺节/无命中，附可行动线索）。 */
  private def locate(lines: Vector[String], section: Option[String], matchStr: String): Either[ToolError, Int] =
    section match
      case Some(sec) =>
        findSection(lines, sec) match
          case None =>
            Left(ToolError(
              s"MemoryEdit: no section '## ${canonicalSection(sec)}'. Existing sections: ${existingSectionNames(lines)} (MEMORYEDIT_NO_SECTION)"))
          case Some(h) =>
            val (b0, b1) = sectionBodyRange(lines, h)
            lines.indices.slice(b0, b1).find(i => isEntry(lines(i)) && lines(i).contains(matchStr)) match
              case None =>
                Left(ToolError(
                  s"""MemoryEdit: no entry containing "$matchStr" in section '## ${canonicalSection(sec)}'. Existing entry prefixes:
                     |${entryPrefixes(lines.slice(b0, b1))} (MEMORYEDIT_NO_MATCH)""".stripMargin))
              case Some(abs) => Right(abs)
      case None =>
        lines.indices.find(i => isEntry(lines(i)) && lines(i).contains(matchStr)) match
          case None =>
            Left(ToolError(
              s"""MemoryEdit: no entry containing "$matchStr". Existing entry prefixes:
                 |${entryPrefixes(lines)} (MEMORYEDIT_NO_MATCH)""".stripMargin))
          case Some(abs) => Right(abs)

  // ------------------------------------------------------------------
  // call
  // ------------------------------------------------------------------

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    IO.blocking {
      val target   = input("target").flatMap(_.asString).getOrElse("")
      val action   = input("action").flatMap(_.asString).getOrElse("")
      val section  = input("section").flatMap(_.asString)
      val matchStr = input("match").flatMap(_.asString)
      val content  = input("content").flatMap(_.asString)

      // 条目模型防漂移（QC nit 6）：append/update 的 content 单条目校验（提前算一次，
      // 由下方 guard 消费；replace_section 不在列——区段替换按设计允许多行）。
      val entryFormatError: Option[ToolError] = (action, content) match
        case ("append", Some(c)) => singleEntryGuard("append", c)
        case ("update", Some(c)) => singleEntryGuard("update", c)
        case _                   => None

      val outcome: Either[ToolError, String] =
        resolveTarget(target).flatMap { t =>
          // per-file 锁（QC nit 3）：整段读-改-写（readLines → 计算 → save）持锁串行，
          // 并发 MemoryEdit 对同一文件不再互相丢更新。
          lockFor(t.path).synchronized {
            action match
              case "append" if content.isEmpty =>
                Left(ToolError("MemoryEdit: append requires `content` (the entry to add). (MEMORYEDIT_PARAM)"))
              case "append" if entryFormatError.isDefined =>
                Left(entryFormatError.get)
              case "update" if matchStr.isEmpty || content.isEmpty =>
                Left(ToolError("MemoryEdit: update requires `match` (locator) and `content` (replacement). (MEMORYEDIT_PARAM)"))
              case "update" if entryFormatError.isDefined =>
                Left(entryFormatError.get)
              case "remove" if matchStr.isEmpty =>
                Left(ToolError("MemoryEdit: remove requires `match` (locator). (MEMORYEDIT_PARAM)"))
              case "replace_section" if section.isEmpty || content.isEmpty =>
                Left(ToolError("MemoryEdit: replace_section requires `section` and `content` (full new body). (MEMORYEDIT_PARAM)"))
              case "append" =>
                val lines = readLines(t.load)
                val add   = normalizeContent(content.getOrElse(""))
                section match
                  case None =>
                    val newContent = (if lines.forall(_.trim.isEmpty) then add else lines ++ add).mkString("\n") + "\n"
                    budgetExceeded("append", target, newContent) match
                      case Some(rejection) => Left(rejection)
                      case None =>
                        t.save(newContent).unsafeRunSync()
                        okWithBudget("append (file end)", t.path, s"+ ${add.mkString(" ⏎ ")}", target, newContent)
                  case Some(sec) =>
                    findSection(lines, sec) match
                      case None =>
                        Left(ToolError(
                          s"MemoryEdit: no section '## ${canonicalSection(sec)}'. Existing sections: ${existingSectionNames(lines)} (MEMORYEDIT_NO_SECTION)"))
                      case Some(h) =>
                        val (b0, b1) = sectionBodyRange(lines, h)
                        val newContent = lines.patch(b1, add, 0).mkString("\n") + "\n"
                        budgetExceeded("append", target, newContent) match
                          case Some(rejection) => Left(rejection)
                          case None =>
                            t.save(newContent).unsafeRunSync()
                            okWithBudget(s"append (section '$sec')", t.path, s"+ ${add.mkString(" ⏎ ")}", target, newContent)

              case "update" =>
                val lines = readLines(t.load)
                locate(lines, section, matchStr.getOrElse("")).flatMap { abs =>
                  val old = lines(abs)
                  val rep = normalizeContent(content.getOrElse(""))
                  val newContent = lines.patch(abs, rep, 1).mkString("\n") + "\n"
                  budgetExceeded("update", target, newContent) match
                    case Some(rejection) => Left(rejection)
                    case None =>
                      t.save(newContent).unsafeRunSync()
                      okWithBudget("update", t.path, s"- ${old.trim.take(80)}\n+ ${rep.mkString(" ⏎ ")}", target, newContent)
                }

              case "remove" =>
                val lines = readLines(t.load)
                locate(lines, section, matchStr.getOrElse("")).flatMap { abs =>
                  val old = lines(abs)
                  t.save(lines.patch(abs, Nil, 1).mkString("\n") + "\n").unsafeRunSync()
                  ok("remove", t.path, s"- ${old.trim.take(80)}")
                }

              case "replace_section" =>
                val lines = readLines(t.load)
                val sec   = section.getOrElse("")
                findSection(lines, sec) match
                  case None =>
                    Left(ToolError(
                      s"MemoryEdit: no section '## ${canonicalSection(sec)}'. Existing sections: ${existingSectionNames(lines)} (MEMORYEDIT_NO_SECTION)"))
                  case Some(h) =>
                    val (b0, b1)  = sectionBodyRange(lines, h)
                    val oldBody   = lines.slice(b0, b1)
                    val add       = normalizeContent(content.getOrElse(""))
                    t.save(lines.patch(b0, add, b1 - b0).mkString("\n") + "\n").unsafeRunSync()
                    ok(s"replace_section '$sec'", t.path,
                      s"replaced ${oldBody.count(isEntry)} entries with ${add.count(isEntry)} entries")

              case other =>
                Left(ToolError(
                  s"MemoryEdit: unknown action '$other' — one of append/update/remove/replace_section. (MEMORYEDIT_ACTION)"))
          }
        }
      outcome
    }
end MemoryEditTool
