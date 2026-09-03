package nebflow.core.tools

import cats.effect.IO
import nebflow.core.sandbox.FileSandbox
import io.circe.JsonObject
import io.circe.syntax.*

object GlobTool extends Tool:
  val MAX_RESULTS = 100

  val name = "Glob"

  val description = """Fast file pattern matching tool that works with any codebase size.

- Supports glob patterns like "**/*.js" or "src/**/*.ts"
- Returns matching file paths sorted by modification time
- Use this tool when you need to quickly find files by name patterns
- ALWAYS use Glob (not Bash with find/ls) to find files by name"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "pattern" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The glob pattern to match files against (e.g. \"**/*.js\", \"src/**/*.ts\")".asJson
        ),
        "path" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The directory to search in. Defaults to current working directory.".asJson
        )
      ),
      "required" -> io.circe.Json.arr("pattern".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val pattern = input("pattern").flatMap(_.asString).getOrElse("")
    val path = input("path").flatMap(_.asString)
    path match
      case Some(p) => s"""Glob("$pattern")\n  (path="$p")"""
      case None => s"""Glob("$pattern")"""

  def summarizeResult(input: JsonObject, result: String): String =
    if result == "No files found matching the pattern." then "No files found"
    else s"${result.split("\\n").length} files found"

  /** Extract the static base directory from a glob pattern (everything before first glob char). */
  private def extractBaseDir(pattern: String): (String, String) =
    val normalized = pattern.replace('\\', '/')
    // Find first glob special character
    val globChars = Set('*', '?', '[', '{')
    val idx = normalized.indexWhere(globChars.contains)
    if idx < 0 then
      // No glob chars — literal path
      val lastSep = normalized.lastIndexOf('/')
      if lastSep < 0 then ("", normalized)
      else (normalized.substring(0, lastSep), normalized.substring(lastSep + 1))
    else
      val staticPrefix = normalized.substring(0, idx)
      val lastSep = staticPrefix.lastIndexOf('/')
      if lastSep < 0 then ("", normalized)
      else (staticPrefix.substring(0, lastSep), normalized.substring(lastSep + 1))
  end extractBaseDir

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.blocking {
    val rawPattern = input("pattern").flatMap(_.asString).getOrElse("")
    val pathOpt = input("path").flatMap(_.asString)
    // 阶段 2a 沙箱（§A.2/§A.8-8）：相对路径与缺省根按节点 sandbox.root 解析——
    // 修掉默认根=JVM user.dir 的现状；沙箱关时保持旧行为（user.dir）。
    val workDir = System.getProperty("user.dir")
    val baseDir: os.Path =
      if ctx.sandbox.enabled then ctx.sandbox.root
      else os.Path(workDir)

    // Resolve search directory
    val (baseFromPattern, relPattern) = extractBaseDir(rawPattern)
    val explicitPath = pathOpt.map { p =>
      if p.startsWith("/") || (p.length >= 2 && p.charAt(1) == ':') then os.Path(p)
      else nebflow.core.PathUtil.resolvePath(p, baseDir)
    }
    val workDirPath = baseDir
    // pattern 静态前缀 → 搜索根（20260903 Glob 修复）：静态前缀（首个 glob 字符
    // 前的目录部分，如 "src/main/resources/web/js"）含 "/" 时旧实现走 os-lib 单段
    // `/` 拼接 → InvalidSegment 崩（description 鼓励的 "src/**/*.ts" 写法自身必崩，
    // 当日 5 崩实锤）→ 改 os.RelPath 多段构造（项目先例 TransferFileTool ×6 /
    // PathUtil.resolvePath:99）。注意：os.RelPath 把 ".." 解析为 Up 段（允许逃逸，
    // 中段 ".." 被 NIO normalize 静默折叠），与旧行为（拒）不符 → 构造前显式拒绝
    // ".." 段并给可行动文案。"．" 段由 RelPath 归一（放宽无害——搜索根仍受
    // §A.3 沙箱读闸门 canonicalize + readableRoots 约束）。
    val searchRootEither: Either[ToolError, os.Path] =
      if baseFromPattern.startsWith("/") || (baseFromPattern.length >= 2 && baseFromPattern.charAt(1) == ':') then
        Right(os.Path(baseFromPattern))
      else if baseFromPattern.nonEmpty then
        if baseFromPattern.split('/').contains("..") then
          Left(ToolError(
            s"Invalid glob pattern: static directory prefix '$baseFromPattern' must not contain '..' segments — " +
              "search stays within the search root; adjust the pattern's directory prefix. (GLOB_PATTERN)"))
        else
          val base = explicitPath.getOrElse(workDirPath)
          try Right(base / os.RelPath(baseFromPattern))
          catch
            case e: Exception =>
              Left(ToolError(
                s"Invalid glob pattern: static directory prefix '$baseFromPattern' is not a usable relative path " +
                  s"(${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}). (GLOB_PATTERN)"))
      else Right(explicitPath.getOrElse(workDirPath))

    // §A.3 读闸门：搜索根 canonicalize + readableRoots contain；rg 从 canonical
    // 根起跑（检查对象=执行对象）。rg 默认不跟随 symlink 下钻（无 --follow），
    // 遍历逃逸由该默认承担（§A.8-4）。
    searchRootEither.flatMap { searchRootPath =>
      FileSandbox.checkReadRoot(ctx, searchRootPath) match
        case Left(err) => Left(err)
        case Right(canonicalRoot) => runGlob(relPattern, canonicalRoot, workDir)
    }
  }

  private def runGlob(
    relPattern: String,
    searchRootPath: os.Path,
    workDir: String
  ): Either[ToolError, String] =
    // Use ripgrep for file listing — much faster than Java NIO Files.walk
    // --no-ignore: match old behavior (Files.walk ignores .gitignore, so should we)
    // --hidden: include hidden files, consistent with GrepTool
    val args = scala.collection.mutable.ListBuffer[String](
      "--files",
      "--glob",
      relPattern,
      "--sort=modified",
      "--color=never",
      "--hidden",
      "--no-ignore",
      "--no-messages",
      // Exclude VCS directories
      "--glob",
      "!.git",
      "--glob",
      "!.svn",
      "--glob",
      "!.hg",
      "--glob",
      "!.bzr",
      "--glob",
      "!.jj"
    )

    // Search from the resolved root
    args += searchRootPath.toString

    RgHelper.runRg(args.toList, workDir) match
      case Left(err) => Left(err)
      case Right((stdoutStr, stderrStr, exitCode)) =>
        if stdoutStr.trim.isEmpty && exitCode == 2 then
          Left(
            ToolError(s"Error: ${if stderrStr.trim.nonEmpty then stderrStr.trim else s"rg exited with code $exitCode"}")
          )
        else if stdoutStr.trim.isEmpty then Right("No files found matching the pattern.")
        else
          // rg --sort=modified returns oldest first; reverse for newest first
          val lines = stdoutStr.trim.split("\n").reverse
          // Convert absolute paths to relative paths from searchRoot
          val results = lines.take(MAX_RESULTS).map { absPath =>
            try
              val p = os.Path(absPath)
              if p.startsWith(searchRootPath) then p.relativeTo(searchRootPath).toString
              else absPath
            catch case _: Exception => absPath
          }

          val output = results.mkString("\n")
          Right(
            if results.length >= MAX_RESULTS then
              output + "\n\n(Results are truncated. Consider using a more specific path or pattern.)"
            else output
          )
  end runGlob
end GlobTool
