package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.{NebflowLogger, PathUtil}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.util.matching.Regex

/**
 * relay/P2P 远端执行审计（Q3 裁定批 T4，2026-09-11）。
 *
 * WHY：`dangerLevel` 只在 confirm-edits / auto-edits 下渲染权限卡；**auto-all 下
 * 不参与任何决策**（ToolReversibility 对 auto-all 全放行）——所以「远端设备上跑
 * 了什么」在事故复盘里没有任何持久痕迹。本审计行是那条缺失的痕迹：「控制另一台
 * 电脑」这条业务腿（relay 隧道鉴权自愈批的事故本体）每次下发都落一行 JSONL。
 *
 * 范围（本批只做可见性，D7-b）：**不含 egress 阻断（D7-a）**、不含对端侧审计、
 * 不含策略/拦截面。
 *
 * 输出：`<dataRoot>/logs/relay-exec-audit.jsonl`——dataRoot = PathUtil.dataRoot
 * （CLI `--home` / NEBFLOW_HOME / 默认 `~/.nebflow`，与 LlmLogWriter/ToolsLogWriter
 * 同款契约：隔离实例的审计行不落进生产 home）。一行一个 JSON 对象：
 * {{{
 * {"ts":"2026-09-11T07:12:03.123Z","deviceId":"<来源设备>","targetDeviceId":"<对端>",
 *  "via":"relay|p2p","action":"Bash","command":"<redact 后的命令摘要>",
 *  "projectRoot":"…","cwd":"…"}
 * }}}
 * - `deviceId` = **来源**设备（本机 NebLink 身份，即下发方；p2p 路径同时作为
 *   `X-Nebflow-Device` 头随请求发出），`targetDeviceId` = 被驱动的对端。
 * - `command` 一律 **redact**（见 [[redact]]）——密钥/token/长串只留
 *   「长度 + 前缀 + SHA-256 前 8 位」，**禁明文**。
 * - `cwd` = 本机下发进程的工作目录（`user.dir`）；远端 cwd 在下发侧不可知，
 *   伪造不如留空语义——故只记本地。
 *
 * 保留期：**待未决 D1**（与 router/tools 日志的 retentionDays 无关），本批
 * **不做 prune**，不新增保留期常量。
 *
 * 失败语义：审计写入失败（磁盘满/权限）**绝不影响远端执行**——吞掉异常 + WARN，
 * 因为「审计缺失」比「远端命令被审计挡住」危害小得多。
 */
object RelayExecAudit:

  private val logger = NebflowLogger.forName("nebflow.relay.audit")

  /** 单行上限：redact 后的命令摘要最多保留的字符数（超出 → 前缀 + 长度 + 哈希）。 */
  private val MaxCommandChars = 300

  /** 通用「长串」判据：token 字符集连续 ≥32 字符（**不含 `/` `.` `:`**——避免把
    * 路径/URL 误判成长串，那会让摘要失去可读性）。 */
  private val LongRunRe = """[A-Za-z0-9_\-+=]{32,}""".r

  /** 密钥名（`NAME=value` / `NAME: value` / JSON `"NAME": value` 结构）。`Bearer`/
    * `Basic` 由单独规则处理。键名尾的可选引号属于键名组——保留原样输出。
    * 裸值排除 shell 分隔符（`; & | ( ) 引号`）——否则 `TOKEN=x; next-cmd` 会把
    * 分号一起吃掉，摘要里两条命令粘连（2026-09-11 取证 dump 实测形态）。 */
  private val SecretKeyRe = (
    """(?i)([A-Za-z0-9_.\-]*(?:token|secret|passw(?:or)?d|api[_-]?key|apikey|access[_-]?key|auth(?:orization)?|credential)[A-Za-z0-9_.\-]*["']?)(\s*[=:]\s*)("([^"]*)"|'([^']*)'|([^\s"'`()|;&]+))"""
  ).r

  /** `Bearer <cred>` / `Basic <b64>`（Authorization 头与命令行都走这条）。 */
  private val BearerRe = """(?i)\b(bearer|basic)\s+([A-Za-z0-9._\-+/=]{6,})""".r

  /** CLI 旗标形态：`--token <value>` / `-password <value>`（无 `=`/`:` 分隔符的
    * 那种）。 */
  private val SecretFlagRe = (
    """(?i)(--?(?:token|secret|passw(?:or)?d|api[_-]?key|apikey|access[_-]?key|auth(?:orization)?|credential)\b\s+)([^\s"'`()|;&]+)"""
  ).r

  /** 已知密钥前缀形态。 */
  private val KnownPrefixRe =
    """(?i)\b(sk-[A-Za-z0-9_\-]{8,}|gh[pousr]_[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{12,}|xox[baprs]-[A-Za-z0-9\-]{8,})""".r

  /** URL userinfo 中的口令：`scheme://user:pass@host`（**只遮蔽口令**——用户名留
    * 着才有取证价值）。 */
  private val UrlUserinfoRe = """://([^/\s:@]{1,64}):([^/\s@]{1,128})@""".r

  /** Fixed-millisecond ISO8601 UTC（与 ToolsLogWriter 同款，字典序可排）。 */
  private val tsFormat =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  private val writeLock = new Object

  /** 审计文件路径（dataRoot 在**每次调用**读取——隔离实例重定向后立即跟随）。 */
  def auditFile: Path =
    (PathUtil.dataRoot / "logs" / "relay-exec-audit.jsonl").toNIO

  // ── Redaction ────────────────────────────────────────────────────────

  private def sha8(raw: String): String =
    val d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))
    d.take(4).map(b => f"${b & 0xff}%02x").mkString

  /** 遮蔽占位：长度 + （≥8 字符才给）前 3 字符 + SHA-256 前 8 位。短密钥不给前缀
    * ——6 字符口令露 3 字符等于露一半。 */
  private def maskOf(raw: String): String =
    if raw.length < 8 then s"[redacted len=${raw.length} sha256:${sha8(raw)}]"
    else s"[redacted len=${raw.length} pre=${raw.take(3)} sha256:${sha8(raw)}]"

  /**
   * 命令摘要的 redact（禁明文）。按序六趟，每趟只做「该形态已知」的遮蔽：
   *   1. `Bearer/Basic <cred>`（**先**于 KV 规则——否则 `Authorization: Bearer x`
   *      的值位会被 KV 规则吃掉 `Bearer`，把真 token 留在明文里）
   *   2. CLI 旗标 `--token <value>`
   *   3. `token=…` / `password: "…"` 结构（值可为引号串/裸串）
   *   4. 已知密钥前缀（sk-/ghp_/AKIA/xox）
   *   5. URL userinfo 口令
   *   6. 通用长串（≥32，见 [[LongRunRe]]）
   * 末尾整体封顶 [[MaxCommandChars]]（同样走「长度 + 前缀 + 哈希」）。
   */
  def redact(command: String): String =
    if command == null then ""
    else
      val s1 = replaceMatches(command, BearerRe, m => m.group(1) + " " + maskOf(m.group(2)))
      val s1b = replaceMatches(s1, SecretFlagRe, m => m.group(1) + maskOf(m.group(2)))
      val s2 = replaceMatches(
        s1b,
        SecretKeyRe,
        m =>
          val value = List(m.group(4), m.group(5), m.group(6)).find(_ != null).getOrElse("")
          // `Authorization: Bearer <x>` 的值位是关键字本身（真 token 已由第一趟
          // 遮蔽）——别把关键字换成遮蔽文本，那只会让摘要失去可读性。
          if m.group(6) != null && (value.equalsIgnoreCase("bearer") || value.equalsIgnoreCase("basic"))
          then m.matched
          else m.group(1) + m.group(2) + maskOf(value)
      )
      val s3 = replaceMatches(s2, KnownPrefixRe, m => maskOf(m.matched))
      val s4 = replaceMatches(
        s3,
        UrlUserinfoRe,
        // 只遮蔽口令：用户名不是密钥，留着才有取证价值（若用户名本身就是
        // token 形态，前一/后一趟的已知前缀/长串规则会接住它）。
        m => "://" + m.group(1) + ":" + maskOf(m.group(2)) + "@"
      )
      val s5 = replaceMatches(s4, LongRunRe, m => maskOf(m.matched))
      capLength(s5)

  /** 按匹配手工拼串（不用 `appendReplacement`：它的 `$`/`\` 转义语义会让「遮蔽
    * 文本本身被当替换模板」——拼串无此歧义）。 */
  private def replaceMatches(s: String, re: Regex, f: Regex.Match => String): String =
    val sb = new StringBuilder
    var last = 0
    for m <- re.findAllMatchIn(s) do
      sb.append(s.substring(last, m.start))
      sb.append(f(m))
      last = m.end
    sb.append(s.substring(last))
    sb.toString

  private def capLength(s: String): String =
    if s.length <= MaxCommandChars then s
    else s.take(MaxCommandChars) + s"…[len=${s.length} sha256:${sha8(s)}]"

  /**
   * 任意工具入参 → 单行命令摘要（redact 前）。Bash 用 `command`；其余远端可执行
   * 工具（Read/Write/Edit/Glob/Grep）用 `key=value` 扁平化——审计要能回答「对端被
   * 读了什么/写了什么」，而不只是「跑了一条命令」。
   */
  def summarizeParams(toolName: String, params: JsonObject): String =
    params("command").flatMap(_.asString).filter(_.nonEmpty) match
      case Some(c) => c
      case None =>
        val flat = params.toList.map((k, v) => s"$k=${v.noSpaces}").mkString(" ").trim
        if flat.isEmpty then toolName else flat

  // ── Recording ────────────────────────────────────────────────────────

  /**
   * 落一行审计（在**真正下发**前调用）。返回的 IO 永不失败：写盘异常 → WARN。
   *
   * @param sourceDeviceId 来源设备（本机 NebLink 身份）
   * @param targetDeviceId 被驱动的对端 deviceId
   * @param via            "relay" | "p2p"
   * @param action         工具名（Bash/Read/…）
   * @param command        原文命令/入参摘要（**本方法内 redact**——调用方拿不到
   *                       「忘了 redact」的机会）
   */
  def record(
    sourceDeviceId: String,
    targetDeviceId: String,
    via: String,
    action: String,
    command: String,
    projectRoot: String,
    cwd: String
  ): IO[Unit] =
    IO.blocking {
      val entry = Json.obj(
        "ts" -> tsFormat.format(Instant.now()).asJson,
        "deviceId" -> sourceDeviceId.asJson,
        "targetDeviceId" -> targetDeviceId.asJson,
        "via" -> via.asJson,
        "action" -> action.asJson,
        "command" -> redact(command).asJson,
        "projectRoot" -> projectRoot.asJson,
        "cwd" -> cwd.asJson
      )
      val path = auditFile
      writeLock.synchronized {
        Files.createDirectories(path.getParent)
        Files.write(
          path,
          (entry.noSpaces + "\n").getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
        )
      }
    }.void.handleErrorWith(e => logger.warn(s"RelayExecAudit: audit line dropped: ${e.getMessage}"))

end RelayExecAudit
