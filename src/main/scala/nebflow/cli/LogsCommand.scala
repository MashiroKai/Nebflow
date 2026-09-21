package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

/** `nebflow logs` — 日志取数面（定位 / 尾读 / 筛选），**纯读**。
  *
  * 禁改既有日志写面：本命令不写、不截断、不轮转、不删任何日志文件，只 `os.list`
  * / `RandomAccessFile(read)`；logback 的 FILE appender 与各 *LogWriter 一字未动。
  *
  * 目录取 `ctx.configDir / "logs"`（= `PathUtil.dataRoot / "logs"`，与 logback.xml
  * `${NEBFLOW_HOME}/logs`、各 LogWriter 的落点同源；`--home` 因此天然隔离）。
  */
object LogsCommand extends CliCommand:
  def name = "logs"
  def description = "Locate and read log files (read-only)"
  def subcommands = List(LogsPath, LogsList, LogsTail)
  def examples = List(
    "nebflow logs path",
    "nebflow logs list",
    "nebflow logs tail -n 100 --grep ERROR"
  )

  // ── 取数面常量（纯，供 spec 直测）────────────────────────────────────────

  /** 活动日志文件名 = logback.xml 的 `<file>` 末段（src/main/resources/logback.xml
    * 的 FILE appender，唯一权威；轮转件为 `nebflow.<date>.<i>.log`）。此处刻意留
    * 字面量并指回该处，不做第二套推导 —— logback 实际写的是哪个文件，本命令就读
    * 哪个文件。 */
  private[cli] val ActiveLogName = "nebflow.log"

  private[cli] val DefaultTailLines = 50

  /** 尾读行数上限（防一条 `-n` 把整个 40MB 日志读进内存）。 */
  private[cli] val MaxTailLines = 10000

  /** 反向按块读的块大小；累计读量上限见 [[MaxTailBytes]]。 */
  private val BlockBytes = 64 * 1024
  private[cli] val MaxTailBytes = 32 * 1024 * 1024

  /** `logs list` 的条目上限：目录超大时截断并显式标注剩余条数。 */
  private[cli] val MaxListEntries = 500

  /** `--file` 只接受 logs 目录下的**裸文件名**：拒绝路径分隔符 / `..` / 绝对路径
    * （路径穿越面 fail-closed）。合法日志名（`nebflow.log`、
    * `nebflow.2026-09-19.0.log`）均不含这些形态。
    */
  private[cli] def validateLogFileName(raw: String): Either[String, String] =
    val name = raw.trim
    if name.isEmpty then Left("Log file name is empty")
    else if name.contains("/") || name.contains("\\") || name.contains("..") then
      Left(
        s"Invalid --file value '$raw': only a bare file name inside the logs directory is accepted " +
          "(no path separators, no '..', no absolute paths)"
      )
    else Right(name)

  /** 从文件尾部向前按块读，返回最后 `n` 行（不含结尾换行产生的空行）。
    *
    * 有界：最多读 [[MaxTailBytes]] 或到文件头为止，**不做整文件读** —— 生产
    * `logs/` 下单文件 20–40MB、已有 7 个轮转件。反向读保证 `-n 50` 只碰尾部
    * 一两块；块先缓存后拼接再解码，多字节字符被块边界切开也不会碎。
    */
  private[cli] def tailLines(f: java.io.File, n: Int): List[String] =
    if n <= 0 then Nil
    else
      val raf = new java.io.RandomAccessFile(f, "r")
      try
        val len = raf.length()
        if len == 0L then Nil
        else
          val chunks = scala.collection.mutable.ListBuffer.empty[Array[Byte]]
          var pos = len
          var newlines = 0
          var read = 0L
          while pos > 0L && newlines < n + 1 && read < MaxTailBytes do
            val start = math.max(0L, pos - BlockBytes)
            val size = (pos - start).toInt
            val chunk = new Array[Byte](size)
            raf.seek(start)
            raf.readFully(chunk)
            var i = 0
            while i < chunk.length do
              if chunk(i) == '\n'.toByte then newlines += 1
              i += 1
            chunks.prepend(chunk)
            read += size
            pos = start
          end while
          val total = chunks.foldLeft(0)(_ + _.length)
          val out = new Array[Byte](total)
          var off = 0
          chunks.foreach { c =>
            java.lang.System.arraycopy(c, 0, out, off, c.length)
            off += c.length
          }
          val raw = new String(out, java.nio.charset.StandardCharsets.UTF_8)
          val split = raw.split("\n", -1).toList
          val noTrailingBlank = if split.nonEmpty && split.last.isEmpty then split.init else split
          noTrailingBlank.map(_.stripSuffix("\r")).takeRight(n)
      finally raf.close()

  private object LogsPath extends CliSubcommand:
    def name = "path"
    def description = "Print the active log file path (default)"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      IO.blocking {
        val dir = logsDir(ctx)
        val file = dir / ActiveLogName
        if ctx.json then
          CliResult.Json(
            Json.obj(
              "logDir" -> dir.toString.asJson,
              "activeFile" -> file.toString.asJson,
              "exists" -> os.exists(file).asJson
            )
          )
        else CliResult.text(file.toString) // 单行：`cat "$(nebflow logs path)"` 可直接用
      }

  private object LogsList extends CliSubcommand:
    def name = "list"
    def description = "List log files in the logs directory"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      IO.blocking {
        val dir = logsDir(ctx)
        if !os.exists(dir) then CliResult.Error(s"Log directory not found: $dir")
        else
          val all = os.list(dir).toList
          val sorted = all.sortBy(p => (-os.mtime(p), p.last))
          val shown = sorted.take(MaxListEntries)
          val truncated = sorted.size - shown.size
          if ctx.json then
            CliResult.Json(
              Json.obj(
                "logDir" -> dir.toString.asJson,
                "total" -> sorted.size.asJson,
                "truncated" -> truncated.asJson,
                "entries" -> shown.map { p =>
                  val isDir = os.isDir(p)
                  Json.obj(
                    "name" -> (if isDir then p.last + "/" else p.last).asJson,
                    "kind" -> (if isDir then "dir" else "file").asJson,
                    "sizeBytes" -> (if isDir then 0L else os.size(p)).asJson,
                    "mtimeMs" -> os.mtime(p).asJson
                  )
                }.asJson
              )
            )
          else
            val lines = shown.map { p =>
              val isDir = os.isDir(p)
              val name = if isDir then p.last + "/" else p.last
              val size = if isDir then "-" else os.size(p).toString
              val ts = java.time.Instant.ofEpochMilli(os.mtime(p)).toString
              f"  ${name}%-40s $size%12s  $ts"
            }
            val head = s"$dir"
            val tail =
              if truncated > 0 then List(s"  ... $truncated more entries")
              else Nil
            CliResult.Text(head :: lines ::: tail)
      }

  private object LogsTail extends CliSubcommand:
    def name = "tail"
    def description = "Print the last lines of a log file (optionally filtered by --grep)"

    def params = List(
      CliParam("lines", Some('n'), "Number of trailing lines", default = Some(DefaultTailLines.toString)),
      CliParam("grep", Some('g'), "Keep only tailed lines matching this regex"),
      CliParam("file", Some('f'), "Log file name under the logs directory", default = Some(ActiveLogName))
    )

    def run(ctx: CliContext): IO[CliResult] =
      IO.blocking {
        val dir = logsDir(ctx)
        val rawFile = ctx.args.get("file").filter(_.nonEmpty).getOrElse(ActiveLogName)
        val pattern: Either[String, Option[java.util.regex.Pattern]] =
          ctx.args.get("grep") match
            case Some(g) => compilePattern(g)
            case None    => Right(None)
        validateLogFileName(rawFile) match
          case Left(err) => CliResult.Error(err)
          case Right(name) =>
            val target = dir / name
            if !os.exists(target) then CliResult.Error(s"Log file not found: $target")
            else if os.isDir(target) then CliResult.Error(s"Not a log file (directory): $target")
            else
              pattern match
                case Left(err) => CliResult.Error(err)
                case Right(patOpt) =>
                  val n = ctx.args
                    .get("lines")
                    .flatMap(_.toIntOption)
                    .getOrElse(DefaultTailLines)
                    .max(1)
                    .min(MaxTailLines)
                  val raw = tailLines(target.toIO, n)
                  val kept = patOpt.fold(raw)(p => raw.filter(l => p.matcher(l).find()))
                  if ctx.json then
                    CliResult.Json(
                      Json.obj(
                        "file" -> target.toString.asJson,
                        "lines" -> kept.asJson,
                        "count" -> kept.size.asJson,
                        "grep" -> ctx.args.get("grep").asJson
                      )
                    )
                  else CliResult.Text(kept)
      }
    end run

    /** 非法正则 ⇒ 可描述报错（说出出错位置），不让 `PatternSyntaxException` 冒到
      * CliRouter 的通用 `Error:` 面上丢信息。 */
    private def compilePattern(raw: String): Either[String, Option[java.util.regex.Pattern]] =
      if raw.isEmpty then Right(None)
      else
        try Right(Some(java.util.regex.Pattern.compile(raw)))
        catch
          case e: java.util.regex.PatternSyntaxException =>
            Left(
              s"Invalid --grep pattern '$raw': ${e.getDescription} (at index ${e.getIndex}). " +
                "Hint: escape special regex characters such as [ ] ( ) * + ? ."
            )
  end LogsTail

  private def logsDir(ctx: CliContext): os.Path =
    ctx.configDir / "logs"
end LogsCommand
