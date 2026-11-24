package nebflow.core.ask

import cats.effect.IO

import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * File-bound Canvas Ask · 写面路径边界（单点判定，ask 写面收窄批 2026-11-18）
 *
 * 作者令：「约束一下 canvas 的 ask 它只有它所对应的文档的编辑能力，其他复杂任务要
 * 转交给主会话，同时也要在引擎上限制它编辑其他文件」。
 *
 * 判定语义（**归一后同一份文档才放行**，不是字符串相等）：
 *   1. 相对拼写按**绑定文件所在目录**解析（= 该会话的 cwd，见
 *      AskSessions.sessionCwd）⇒ 同一份文档的相对拼写不是绕过面；
 *   2. 词法消 `.` / `..`（`normalize`）⇒ `a/../b` 这类拼写先归一再看身份；
 *   3. 归一后取 **realpath**（沿符号链接全程解析）+ `Files.isSameFile`
 *      （同 inode / 同 device⇒ 硬链、软链、大小写不敏感卷的等价拼写、重复挂载/
 *      别名路径全部收成同一身份）；
 *   4. 身份 == 绑定文档 ⇒ 放行；其余一切 ⇒ 拒绝（含 `..` 指向别处、绝对路径指向
 *      别处、软链指向别处、大小写变体指向**另一份**文件、别名挂在别处）。
 *
 * 本对象是**唯一**的路径判决面：AskEditReviews.capture（ask_edit / ask_write 的
 * 目标闸）只经本面，不许在别处再写一份 startsWith/字符串相等判据（静态闸
 * scripts/check-ask-write-scope.mjs 钉住这条）。
 *
 * 残余风险（如实申报）：本判定是**应用层**身份归一，不是操作系统沙箱；判定与
 * 落盘之间若目标被外部替换（TOCTOU），写入的是归一时刻解析出的真身路径。落盘
 * 侧的过期/外部改动闸（AskEditReviews 的 stale 纪律）继续承担那一面。
 * ═══════════════════════════════════════════════════════════════════════════ */
object AskPathBoundary:

  /**
   * 判决：`allowed` = 归一后指向绑定文档；`canonical` = 归一后的真身绝对路径
   * （放行时即落盘目标；拒绝时 = 被拒拼写归一后的读数，供审计留痕）。
   */
  final case class Decision(allowed: Boolean, canonical: String, reason: String):
    def refused: Boolean = !allowed

  /** 规则陈述（拒绝文案必带——「本会话只能编辑它对应的文档」）。 */
  val ScopeRule: String = "this ask session can edit only the document it is bound to"

  /** 转交建议（拒绝文案必带——越界不是死路，是转交主会话）。 */
  val HandoffHint: String =
    "hand it to the main session with send_message_to_main_chat (structured handoff: task / paths / reason / expected)"

  /** 拒绝文案单点：原因 + 规则 + 转交建议（三者缺一不可，显式可读，绝不静默）。 */
  def refusal(why: String): String =
    s"ask edit refused: $why — $ScopeRule; $HandoffHint"

  /**
   * 判决入口（IO 面，落盘/realpath 都在 blocking 池）。
   * @param boundFile 绑定文档（AskBinding.file）
   * @param requested 请求拼写（原样，未 trim 前）
   */
  def decide(boundFile: String, requested: String): IO[Decision] =
    IO.blocking(decideSync(boundFile, requested))

  /** 纯判定（spec 直接驱动；无 IO 包装）。 */
  def decideSync(boundFile: String, requested: String): Decision =
    val raw = Option(requested).map(_.trim).getOrElse("")
    if raw.isEmpty then Decision(false, "", refusal("`path` is required"))
    else
      try
        val bound = Path.of(boundFile)
        val resolved = resolveSpelling(bound, raw)
        if !Files.exists(resolved) then
          // 断链软链 / 不存在的硬拼写同落此处：ask 面永不创建文件。
          Decision(
            false,
            resolved.toString,
            refusal(s"the requested path does not exist (${resolved.toString}) — the ask face never creates files")
          )
        else if Files.isDirectory(resolved) then
          Decision(
            false,
            resolved.toString,
            refusal(s"the requested path is a directory, not a document (${resolved.toString})")
          )
        else if !Files.exists(bound) then
          Decision(false, resolved.toString, refusal(s"the bound document no longer exists (${bound.toString})"))
        else
          val requestedReal = resolved.toRealPath()
          val boundReal = bound.toRealPath()
          val sameDocument = Files.isSameFile(resolved, bound) || requestedReal == boundReal
          if sameDocument then Decision(true, requestedReal.toString, "")
          else
            Decision(
              false,
              requestedReal.toString,
              refusal(
                s"the requested path spells out a different document than the bound one " +
                  s"(requested ${resolved.toString} → ${requestedReal.toString}; bound ${boundReal.toString})"
              )
            )
        end if
      catch
        case NonFatal(e) =>
          Decision(
            false,
            raw,
            refusal(
              s"the requested path could not be resolved (${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)})"
            )
          )

    end if

  end decideSync

  /**
   * 拼写归一：绝对拼写直接用；相对拼写按**绑定文件的父目录**解析（该会话 cwd 的
   * 单点，见 AskSessions.sessionCwd），再词法消 `.` / `..`。符号链接不在本步解析
   * （留给 realpath，见 decideSync 第 3 步）。
   */
  private def resolveSpelling(bound: Path, raw: String): Path =
    val asGiven = Path.of(raw)
    if asGiven.isAbsolute then asGiven.normalize()
    else
      val dir = Option(bound.getParent).getOrElse(bound.toAbsolutePath.getParent)
      Option(dir).getOrElse(Path.of("/")).resolve(asGiven).normalize()

  // ── 工具面同判据（写面收窄的第二个消费点：Bash 面不给写，见 AskBashGuard）──

  /**
   * 快速判据（无 IO 包装；`Write`/`Edit` 类直写工具若被某条通路误挂到 ask 面，
   * 由 AgentSessionExecution.buildAllowedToolSet 的 ask 早退集负责剥离——本方法
   * 只服务「给定绑定 + 请求拼写」的同一份语义查询）。
   */
  def isBoundDocument(boundFile: String, requested: String): Boolean =
    decideSync(boundFile, requested).allowed

end AskPathBoundary
