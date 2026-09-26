package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.shared.NebflowLogger

import java.util.concurrent.TimeUnit

import scala.io.Source
import scala.util.Using.resource

class ScriptTool(config: ExternalToolConfig, val toolDir: os.Path) extends Tool:
  private val logger = NebflowLogger(getClass)

  val name = config.name
  val description = config.description
  val inputSchema = config.inputSchema

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val jsonInput = Json.fromJsonObject(input).noSpaces
    logger.info(s"ScriptTool executing", "name" -> config.name, "command" -> config.command) *>
      IO.blocking {
        val pb = ProcessBuilder("sh", "-c", config.command)
        pb.directory(new java.io.File(ctx.projectRoot))
        // TOOL_DIR points at the directory holding this tool's config file,
        // so commands can reference relative resources, e.g.
        //   "command": "python3 $TOOL_DIR/analyze.py"
        pb.environment().put("TOOL_DIR", toolDir.toString)
        pb.redirectErrorStream(false)
        val process = pb.start()

        // Write JSON input to stdin.
        // ⚠️ 子进程可能**不读 stdin 就结束**（`printf`/`exit` 这类即刻返回的命令，或脚本
        // 自己不看输入）——此时 JDK 进程回收线程已关掉父侧 stdin 管道：写入抛
        // `IOException("Stream closed")`（管道读端全关时为 `Broken pipe`）。
        // 这不是脚本失败：脚本的权威结局是**退出码 + stdout/stderr**。旧行为让该写入异常
        // 经外层 handleError 变成工具失败，把一次成功执行（exit 0 + 正确 stdout）报成
        // `Script execution failed: Stream closed`（ToolLoaderSpec TOOL_DIR 用例的负载敏红形态）。
        // 故：写入失败**只留痕不判败**，真实结局一律由下面的退出码分支给。
        val stdinNotWritable: Option[String] =
          try
            val stdin = process.getOutputStream
            stdin.write(jsonInput.getBytes("UTF-8"))
            stdin.close()
            None
          catch
            case e: java.io.IOException =>
              logger.warn(
                "script stdin not writable (child exited without reading it) — outcome taken from exit code/streams",
                "name" -> config.name,
                "reason" -> e.getMessage
              )
              Some(e.getMessage)

        val finished = process.waitFor(config.timeoutSeconds, TimeUnit.SECONDS)
        if !finished then
          process.destroyForcibly()
          Left(ToolError(s"Script '${config.name}' timed out after ${config.timeoutSeconds}s"))
        else if process.exitValue() == 0 then
          val stdout = resource(Source.fromInputStream(process.getInputStream, "UTF-8"))(_.mkString)
          Right(stdout)
        else
          val stderr = resource(Source.fromInputStream(process.getErrorStream, "UTF-8"))(_.mkString)
          // 空 stderr 时补一条可描述的结局（退出码 + stdin 不可写原因），不留空错误文本。
          val msg =
            if stderr.trim.nonEmpty then stderr
            else
              val base = s"Script '${config.name}' exited with code ${process.exitValue()}"
              stdinNotWritable.fold(base)(r => s"$base (stdin not writable: $r)")
          Left(ToolError(msg))
        end if
      }.handleError(e => Left(ToolError(s"Script execution failed: ${e.getMessage}")))
  end call

  def summarize(input: JsonObject): String = s"${config.name}(...)"

  def summarizeResult(input: JsonObject, result: String): String = s"${config.name} completed"
end ScriptTool
