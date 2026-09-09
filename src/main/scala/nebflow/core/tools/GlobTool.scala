package nebflow.core.tools

import cats.effect.IO
import nebflow.core.sandbox.{FileSandbox, SandboxPolicy}
import io.circe.JsonObject
import io.circe.syntax.*

object GlobTool extends Tool:
  val MAX_RESULTS = 100
  private val MAX_HEAD_LIMIT = 10000

  // glob 特殊字符（extractBaseDir 与单层锚定判定共用）
  private val globChars = Set('*', '?', '[', '{')

  val name = "Glob"

  val description = """Fast file pattern matching tool that works with any codebase size.

- Supports glob patterns like "**/*.js" or "src/**/*.ts"
- `*` matches a single path level only (e.g. "*.js" = files directly in path, NOT recursive); use "**" to recurse (e.g. "**/*.js")
- Dot-files/dot-directories are excluded by default; a pattern segment starting with "." (e.g. ".git", "*/.git", ".nebflow/*") matches them explicitly
- Returns matching file paths sorted by modification time (newest first)
- Oversized result sets are truncated with a note (never a hard error); narrow the pattern or raise head_limit to page through
- Use this tool when you need to quickly find files by name patterns
- ALWAYS use Glob (not Bash with find/ls) to find files by name"""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "pattern" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The glob pattern to match files against (e.g. \"**/*.js\", \"src/**/*.ts\"). \"*\" stays within one path level; \"**\" recurses. A \".\"-prefixed segment (e.g. \"*/.git\") opts into hidden dot-paths.".asJson
        ),
        "path" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The directory to search in. Defaults to current working directory.".asJson
        ),
        "head_limit" -> io.circe.Json.obj(
          "type" -> "integer".asJson,
          "description" -> s"Maximum entries to return (default $MAX_RESULTS, max $MAX_HEAD_LIMIT). Extra matches are cut and noted — raise it or narrow the pattern to page.".asJson
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
    else
      // count file lines only — truncation notes ("(Showing first ..."/"(N matches ...")
      // and the blank line before them are not entries
      val n = result.split("\\n").count(l => l.nonEmpty && !l.startsWith("("))
      s"$n files found"

  /** Extract the static base directory from a glob pattern (everything before first glob char). */
  private def extractBaseDir(pattern: String): (String, String) =
    val normalized = pattern.replace('\\', '/')
    // Find first glob special character
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

    // head_limit（20260909 Glob 修复批）：缺省 MAX_RESULTS；非法值给可行动文案
    val headLimit: Either[ToolError, Int] =
      input("head_limit").flatMap(_.asNumber).flatMap(_.toInt) match // JsonNumber.toInt: Option[Int]（可溢出）
        case None    => Right(MAX_RESULTS)
        case Some(n) =>
          if n < 1 then
            Left(ToolError(
              s"Invalid head_limit: $n — must be >= 1. Omit the parameter for the default ($MAX_RESULTS). (GLOB_HEAD_LIMIT)"))
          else if n > MAX_HEAD_LIMIT then
            Left(ToolError(
              s"Invalid head_limit: $n — must be <= $MAX_HEAD_LIMIT. Use a more specific pattern instead of a larger window. (GLOB_HEAD_LIMIT)"))
          else Right(n)

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
    // ".." 段并给可行动文案。"．" 段由 RelPath 归一（放宽无害——搜索根仍过
    // §A.3 沙箱读闸门 canonicalize + readableRoots contain，2026-09-06 读宽批
    // 后 contain 恒真、负向规则仍生效）。
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

    // dot 显式判定（20260909 Glob 修复批）：用户 pattern 任一路径段以 "." 开头
    // （如 ".git"、"*/.git"、".nebflow/*"）= 显式点名 dot 路径 → 放行隐藏文件；
    // 否则默认隐藏（负向 glob 实现，防误扫 .git/node_modules 内部）。
    val dotExplicit = rawPattern.replace('\\', '/').split('/').exists(_.startsWith("."))

    // §A.3 读闸门：搜索根 canonicalize + readableRoots contain（2026-09-06 读宽
    // 批后 contain 恒真，检查链保留）；rg 从 canonical 根起跑（检查对象=执行
    // 对象）。rg 默认不跟随 symlink 下钻（无 --follow），遍历逃逸由该默认承担
    // （§A.8-4）。
    searchRootEither.flatMap { searchRootPath =>
      headLimit.flatMap { limit =>
        FileSandbox.checkReadRoot(ctx, searchRootPath) match
          case Left(err) => Left(err)
          case Right(canonicalRoot) =>
            // 凭据红线遍历排除（沙箱开时）：搜索根在 ~/.nebflow/agents 子树内 →
            // rg 排除 agent 私有记忆（memory.md）——文件级负向规则管不住目录遍历。
            val memExcludes =
              if ctx.sandbox.enabled then SandboxPolicy.memoryGlobExcludes(canonicalRoot.wrapped) else Nil
            runGlob(relPattern, canonicalRoot, workDir, memExcludes, dotExplicit, limit)
      }
    }
  }

  private def runGlob(
    relPattern: String,
    searchRootPath: os.Path,
    workDir: String,
    memoryExcludes: List[String] = Nil,
    dotExplicit: Boolean = false,
    headLimit: Int = MAX_RESULTS
  ): Either[ToolError, String] =
    // relPattern → rg glob 映射（20260909 Glob 修复批）。rg 走 cwd 相对搜索
    // （搜索根为进程 cwd、搜 "."，见下），gitignore 语义锚定搜索根：
    //  - 含 "/"：原样（"*/.git"、"中文*/**"、"**/*.ts" 语义即直觉）。
    //  - 无 "/" 且含 glob 字符：前导 "/" 锚定搜索根 → 单层语义。无锚定时 rg 的
    //    basename 语义会递归全树——旧实现 "*" 实为全量列举，大目录直接撞
    //    500KB 输出上限（defect ① 根因）。
    //  - 无 "/" 纯字面名（如 ".git"）：basename 任意层匹配（find -name 语义，
    //    供「按名找文件」直觉；验收：".git" 命中 worktree 指针文件）。
    val rgGlob =
      if relPattern.contains('/') then relPattern
      else if relPattern.exists(globChars.contains) then "/" + relPattern
      else relPattern
    // Use ripgrep for file listing — much faster than Java NIO Files.walk
    // --no-ignore: match old behavior (Files.walk ignores .gitignore, so should we)
    val args = scala.collection.mutable.ListBuffer[String](
      "--files",
      "--glob",
      rgGlob,
      "--sort=modified",
      "--color=never",
      "--no-ignore",
      "--no-messages"
    )
    // dot 默认隐藏（20260909）：rg 在显式 --glob 白名单存在时会跳过自身 hidden
    // 过滤（rg 15 实证），故负向 glob 实现；"!.*/" 剪枝隐藏目录（.git/.svn/.hg
    // 等 dot 目录一并覆盖——旧五条 VCS 专用排除被替代）。pattern 显式含 "." 段
    // 时不加（显式点名放行，defect ③ 的 "*/.git" 场景）。
    if !dotExplicit then
      args ++= List("--glob", "!.*/", "--glob", "!.*")
    // agent 私有记忆排除（后置 glob 规则优先级更高——rg last-match-wins）
    args ++= memoryExcludes

    // 以搜索根为进程 cwd、搜 "."：gitignore 锚定语义以搜索根为基准。旧实现传
    // 绝对路径 → rg 对完整绝对路径做 glob 对齐，"*/.git" 等单段通配与中文段
    // pattern 从文件系统根起永远失配（defect ③ 根因）；相对化还让输出天然
    // 相对（500KB 预算省掉绝对前缀）。
    args += "."

    RgHelper.runRg(
      args.toList,
      workDir,
      processCwd = Some(searchRootPath.toString),
      truncateOnOverflow = true
    ) match
      case Left(err) => Left(err)
      case Right((stdoutStr, stderrStr, exitCode, rgTruncated)) =>
        if stdoutStr.trim.isEmpty && exitCode == 2 then
          Left(
            ToolError(s"Error: ${if stderrStr.trim.nonEmpty then stderrStr.trim else s"rg exited with code $exitCode"}")
          )
        else if stdoutStr.trim.isEmpty then Right("No files found matching the pattern.")
        else
          // rg --sort=modified returns oldest first; reverse for newest first
          val lines = stdoutStr.trim.split("\n").reverse
          // rg 输出 cwd 相对路径（"./x" 或 "x"）→ 以搜索根解析回绝对再转相对输出
          val results = lines.take(headLimit).map { rel =>
            try
              val p = os.Path(rel.stripPrefix("./"), base = searchRootPath)
              if p.startsWith(searchRootPath) then p.relativeTo(searchRootPath).toString
              else rel
            catch case _: Exception => rel
          }

          val shown = results.length
          val totalLines = lines.length
          val output = results.mkString("\n")
          // 截断注记（20260909 defect ②）：超限不再硬报错——rg 500KB 截断时总数
          // 未知如实说明；head_limit 截断时给精确总数与分页指引。
          val note =
            if rgTruncated then
              s"\n\n(Showing first $shown matches — total exceeds the 500KB rg output cap and is unknown. " +
                s"Use a more specific pattern, or raise head_limit (max $MAX_HEAD_LIMIT) to see more.)"
            else if totalLines > shown then
              s"\n\n($totalLines matches in total — showing first $shown. " +
                s"Use a more specific pattern to narrow, or raise head_limit (max $MAX_HEAD_LIMIT).)"
            else ""
          Right(output + note)
  end runGlob
end GlobTool
