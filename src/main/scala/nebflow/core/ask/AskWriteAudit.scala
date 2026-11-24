package nebflow.core.ask

import nebflow.shared.{NebflowLogger, PathUtil}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * File-bound Canvas Ask · 越界审计留痕（ask 写面收窄批 2026-11-18）
 *
 * 作者令：「越权尝试要有审计留痕（身份、目标路径、时间、结果），可被后续查证」。
 *
 * 形态：**每条** ask 写面判决（放行与拒绝）都落一行 JSON Lines——
 *   `<dataRoot>/ask/audit.jsonl`（append-only；进程重启不丢，可被 grep/jq 查证）
 *   同时在 `nebflow.ask.audit` 日志面留同内容一行（运维面即时可读）。
 * 行字段（固定）：ts / iso / identity / tool / requested / canonical / result /
 *   reason。`result` ∈ {"allow","deny"}；`identity` = ask 会话 id（`ask-<8hex>`）。
 *
 * 纪律：审计**绝不**让工具调用失败——写盘异常只落 warn 日志（fail-open 只影响
 * 留痕面，不影响判决面；判决本身仍是 fail-closed）。
 * ═══════════════════════════════════════════════════════════════════════════ */
object AskWriteAudit:

  private val logger = NebflowLogger.forName("nebflow.ask.audit")

  /** 审计文件相对 dataRoot 的位置（ask 绑定表 index.json 同目录）。 */
  val RelativeFile: String = "ask/audit.jsonl"

  /** 生产落点。 */
  def defaultFile: Path = Path.of(PathUtil.dataRoot.toString, "ask", "audit.jsonl")

  /**
   * spec 注入落点（生产永为 None ⇒ [[defaultFile]] 生效）。先例：
   * `AskEditReviews.resetForTest` / `AskHandoffRateLimit.reset()`——测试态注入是
   * 窄口，不是配置项；`None` 复位即生产口径。
   */
  @volatile private var injectedTarget: Option[Path] = None

  def injectTargetForTest(target: Option[Path]): Unit = injectedTarget = target

  private def target: Path = injectedTarget.getOrElse(defaultFile)

  def currentTarget: Path = target

  /**
   * 记一行（放行 ∧ 拒绝都记）。
   * @param identity  ask 会话 id（`ask-<8hex>`）
   * @param tool      触发面（"ask_edit" / "ask_write" / "Bash"）
   * @param requested 请求拼写（原样）
   * @param canonical 归一后的真身路径（判定不出 = 原样回显）
   * @param allowed   判决结果
   * @param reason    理由（放行 = ""；拒绝 = 完整拒绝文案）
   */
  def record(
    identity: String,
    tool: String,
    requested: String,
    canonical: String,
    allowed: Boolean,
    reason: String
  ): Unit =
    recordTo(target, identity, tool, requested, canonical, allowed, reason)

  /** 指定落点（spec 注入临时目录用；生产走 [[record]]）。 */
  def recordTo(
    file: Path,
    identity: String,
    tool: String,
    requested: String,
    canonical: String,
    allowed: Boolean,
    reason: String
  ): Unit =
    val ts = System.currentTimeMillis()
    val verdict = if allowed then "allow" else "deny"
    val line = io.circe.Json
      .obj(
        "ts" -> io.circe.Json.fromLong(ts),
        "iso" -> io.circe.Json.fromString(java.time.Instant.ofEpochMilli(ts).toString),
        "identity" -> io.circe.Json.fromString(identity),
        "tool" -> io.circe.Json.fromString(tool),
        "requested" -> io.circe.Json.fromString(requested),
        "canonical" -> io.circe.Json.fromString(canonical),
        "result" -> io.circe.Json.fromString(verdict),
        "reason" -> io.circe.Json.fromString(reason)
      )
      .noSpaces
    // 日志面：字段顺序固定，便于 grep（不打印任何凭据——本面只有路径与判决）。
    logger.info(
      s"ask-scope-audit identity=$identity tool=$tool result=$verdict " +
        s"requested=${q(requested)} canonical=${q(canonical)} reason=${q(reason)}"
    )
    try
      val parent = file.getParent
      if parent != null then Files.createDirectories(parent)
      Files.write(
        file,
        (line + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
    catch
      case NonFatal(e) =>
        logger.warn(s"ask audit append failed (non-fatal, verdict unaffected): ${e.getMessage}")
  end recordTo

  /** 日志面引号包裹（空串显示 `""`；换行折叠成 `⏎`——审计日志恒单行一条）。 */
  private def q(value: String): String =
    "\"" + Option(value).getOrElse("").replace('\n', '⏎').replace('\r', '⏎') + "\""

  /** 读面（全部行；查证/spec 用——生产零调用）。缺失文件 = 空表。 */
  def readAll(file: Path): List[String] =
    try
      if !Files.exists(file) then Nil
      else
        new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
          .split('\n')
          .toList
          .filter(_.trim.nonEmpty)
    catch case NonFatal(_) => Nil

end AskWriteAudit
