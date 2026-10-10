package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

/**
 * `nebflow logs` — 日志取数面（定位 / 尾读 / 筛选），**纯读**。
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
    "nebflow logs tail -n 100 --grep ERROR",
    "nebflow logs --follow"
  )

  // ── 取数面常量（纯，供 spec 直测）────────────────────────────────────────

  /**
   * 活动日志文件名 = logback.xml 的 `<file>` 末段（src/main/resources/logback.xml
   * 的 FILE appender，唯一权威；轮转件为 `nebflow.<date>.<i>.log`）。此处刻意留
   * 字面量并指回该处，不做第二套推导 —— logback 实际写的是哪个文件，本命令就读
   * 哪个文件。
   */
  private[cli] val ActiveLogName = "nebflow.log"

  private[cli] val DefaultTailLines = 50

  /** 尾读行数上限（防一条 `-n` 把整个 40MB 日志读进内存）。 */
  private[cli] val MaxTailLines = 10000

  /** 反向按块读的块大小；累计读量上限见 [[MaxTailBytes]]。 */
  private val BlockBytes = 64 * 1024
  private[cli] val MaxTailBytes = 32 * 1024 * 1024

  /** `logs list` 的条目上限：目录超大时截断并显式标注剩余条数。 */
  private[cli] val MaxListEntries = 500

  // ── --follow（phase 2 daemon 批 §3.3）─────────────────────────────────
  //
  // `-f` 已被 `--file` 占用 ⇒ follow 只有长旗、无短旗（对规格字面「-f/--follow」
  // 的显式偏离，零破坏现有 -f）。路由面（R2-1）：`nebflow logs --follow`（无
  // tail 子命令）会以 `--follow` 为首 token 走 acceptsLeadingPositional ⇒ 落
  // head 子命令 LogsPath ⇒ 旗必须同时挂 LogsPath.params 与 LogsTail.params
  // 两处，共用同一循环。

  /** The follow flag, shared by LogsPath and LogsTail (R2-1: both routes). */
  private[cli] def followParam: CliParam =
    CliParam("follow", None, "Keep printing new lines as the log grows (Ctrl+C to exit)", isFlag = true)

  /**
   * One rotation-aware drain step (pure, spec-driven): read the byte range
   * `[start, len)` of the file where `start` = `pos`, or 0 when the file
   * shrank below `pos` (logback rotation = rename + fresh file at size 0 ⇒
   * reopen from the head). Returns the COMPLETE lines found (a trailing
   * partial line is held back by advancing the offset only past the last
   * newline) and the new offset. The active file keeps its name across
   * rotations (`logback.xml` `<file>` + SizeAndTimeBased policy), so no `%i`
   * index parsing is ever needed.
   */
  private[cli] def drainStep(f: java.io.File, pos: Long): (List[String], Long) =
    if !f.exists() then (Nil, pos)
    else
      val len = f.length()
      if len == 0L && pos == 0L then (Nil, 0L)
      else
        val start = if len < pos then 0L else pos
        if start >= len then (Nil, len)
        else
          val raf = new java.io.RandomAccessFile(f, "r")
          try
            raf.seek(start)
            val data = new Array[Byte]((len - start).toInt)
            raf.readFully(data)
            val lastNl = data.lastIndexOf('\n'.toByte)
            if lastNl == -1 then (Nil, len - data.length) // no complete line yet — hold all back
            else
              val text = new String(java.util.Arrays.copyOf(data, lastNl + 1), "UTF-8")
              val lines =
                text.split("\n", -1).toList.dropRight(1).map(_.stripSuffix("\r"))
              (lines, len - (data.length - lastNl - 1))
          finally raf.close()

  /**
   * The follow loop: wait for a logs-directory event, drain what appeared on
   * the watched file, print (after the optional grep filter), repeat. The
   * event wait is injected (`awaitEvent`) so the spec drives the state
   * machine without a real WatchService; the real face uses one
   * WatchService per command watching the DIRECTORY (rotation is a rename —
   * watching the file itself would go stale). Ends only via cancellation
   * (Ctrl+C); no exit-code promise is made for that path.
   */
  private[cli] def followLoop(
      dir: os.Path,
      fileName: String,
      grep: Option[java.util.regex.Pattern],
      print: String => IO[Unit],
      awaitEvent: () => Unit
  ): IO[Unit] =
    def loop(pos: Long): IO[Unit] =
      IO.blocking(awaitEvent()) *>
        IO.blocking(drainStep((dir / fileName).toIO, pos)).flatMap { case (lines, newPos) =>
          lines
            .filter(l => grep.forall(p => p.matcher(l).find()))
            .traverse_(print) *> loop(newPos)
        }
    val start = IO.blocking {
      val f = (dir / fileName).toIO
      if f.exists() then f.length() else 0L
    }
    start.flatMap(loop)

  /** One WatchService over the logs dir; any event wakes the drain. */
  private final class WatchAwait(dir: java.nio.file.Path) extends AutoCloseable:
    private val watcher = java.nio.file.FileSystems.getDefault.newWatchService()
    dir.register(
      watcher,
      java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
      java.nio.file.StandardWatchEventKinds.ENTRY_DELETE,
      java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
    )
    def await(): Unit =
      val key = watcher.take()
      key.reset()
    def close(): Unit =
      watcher.close()

  /** The production follow face: real directory watch + println. */
  private def followReal(dir: os.Path, fileName: String, grep: Option[java.util.regex.Pattern]): IO[Unit] =
    IO.blocking(new WatchAwait(java.nio.file.Paths.get(dir.toString))).flatMap { w =>
      followLoop(dir, fileName, grep, IO.println, () => w.await())
        .guarantee(IO.blocking(w.close()))
    }

  /**
   * `--file` 只接受 logs 目录下的**裸文件名**：拒绝路径分隔符 / `..` / 绝对路径
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

  /**
   * 从文件尾部向前按块读，返回最后 `n` 行（不含结尾换行产生的空行）。
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
        end if
      finally raf.close()

      end try

  private[cli] object LogsPath extends CliSubcommand:
    def name = "path"
    def description = "Print the active log file path (default; --follow keeps printing new lines)"
    def params = List(followParam)

    def run(ctx: CliContext): IO[CliResult] =
      val follow = ctx.args.get("follow").contains("true")
      if follow then
        if ctx.json then IO.pure(CliResult.Error("--follow is a streaming face — no JSON output"))
        else
          IO.blocking {
            val dir = logsDir(ctx)
            val file = dir / ActiveLogName
            if !os.exists(file) then Left(s"Log file not found: $file")
            else Right((dir, tailLines(file.toIO, DefaultTailLines)))
          }.flatMap {
            case Left(err) => IO.pure(CliResult.Error(err))
            case Right((dir, initial)) =>
              initial.traverse_(IO.println) *>
                followReal(dir, ActiveLogName, None).as(CliResult.Success)
          }
      else
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

  end LogsPath

  private[cli] object LogsList extends CliSubcommand:
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
          end if
        end if
      }

  end LogsList

  private[cli] object LogsTail extends CliSubcommand:
    def name = "tail"
    def description = "Print the last lines of a log file (optionally filtered by --grep; --follow keeps printing)"

    def params = List(
      CliParam("lines", Some('n'), "Number of trailing lines", default = Some(DefaultTailLines.toString)),
      CliParam("grep", Some('g'), "Keep only tailed lines matching this regex"),
      CliParam("file", Some('f'), "Log file name under the logs directory", default = Some(ActiveLogName)),
      followParam
    )

    def run(ctx: CliContext): IO[CliResult] =
      val follow = ctx.args.get("follow").contains("true")
      if follow && ctx.json then IO.pure(CliResult.Error("--follow is a streaming face — no JSON output"))
      else
        IO.blocking {
          val dir = logsDir(ctx)
          val rawFile = ctx.args.get("file").filter(_.nonEmpty).getOrElse(ActiveLogName)
          val pattern: Either[String, Option[java.util.regex.Pattern]] =
            ctx.args.get("grep") match
              case Some(g) => compilePattern(g)
              case None => Right(None)
          validateLogFileName(rawFile) match
            case Left(err) => Left(CliResult.Error(err))
            case Right(name) =>
              val target = dir / name
              if !os.exists(target) then Left(CliResult.Error(s"Log file not found: $target"))
              else if os.isDir(target) then Left(CliResult.Error(s"Not a log file (directory): $target"))
              else
                pattern match
                  case Left(err) => Left(CliResult.Error(err))
                  case Right(patOpt) =>
                    val n = ctx.args
                      .get("lines")
                      .flatMap(_.toIntOption)
                      .getOrElse(DefaultTailLines)
                      .max(1)
                      .min(MaxTailLines)
                    val raw = tailLines(target.toIO, n)
                    val kept = patOpt.fold(raw)(p => raw.filter(l => p.matcher(l).find()))
                    Right((dir, name, kept, patOpt))
              end if
          end match
        }.flatMap {
          case Left(errResult) => IO.pure(errResult)
          case Right((dir, name, kept, patOpt)) =>
            if follow then
              // Streaming face: initial tail now, then the watch loop; the
              // loop only returns via cancellation (Ctrl+C) — Success then.
              kept.traverse_(IO.println) *>
                followReal(dir, name, patOpt).as(CliResult.Success)
            else if ctx.json then
              IO.pure(
                CliResult.Json(
                  Json.obj(
                    "file" -> ((dir / name).toString).asJson,
                    "lines" -> kept.asJson,
                    "count" -> kept.size.asJson,
                    "grep" -> ctx.args.get("grep").asJson
                  )
                )
              )
            else IO.pure(CliResult.Text(kept))
        }
    end run

    /**
     * 非法正则 ⇒ 可描述报错（说出出错位置），不让 `PatternSyntaxException` 冒到
     * CliRouter 的通用 `Error:` 面上丢信息。
     */
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
