package nebflow.core

import nebflow.shared.{Message, MessageRole, ContentBlock, LlmHandle, LlmRequest, StreamChunk, ToolCall, ToolDefinition}
import nebflow.core.tools.{ToolRegistry, ToolError}
import cats.effect.IO
import cats.syntax.all.*
import java.nio.file.{Files, Paths}
import scala.io.Source

object Repl:
  private val IMAGE_EXTENSIONS = List(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg")
  private val VIDEO_EXTENSIONS = List(".mp4", ".mov", ".avi", ".mkv", ".webm", ".flv", ".wmv", ".m4v")
  private val MEDIA_EXTENSIONS = IMAGE_EXTENSIONS ++ VIDEO_EXTENSIONS
  private val MEDIA_REGEX = s"(?:^|\\s)((?:/[^\\s]+|[~.][^\\s]+)\\.(?:${MEDIA_EXTENSIONS.map(_.drop(1)).mkString("|")}))".r

  private val MIME_MAP = Map(
    "png" -> "image/png", "jpg" -> "image/jpeg", "jpeg" -> "image/jpeg",
    "gif" -> "image/gif", "webp" -> "image/webp", "bmp" -> "image/bmp",
    "svg" -> "image/svg+xml", "mp4" -> "video/mp4", "mov" -> "video/quicktime",
    "avi" -> "video/x-msvideo", "mkv" -> "video/x-matroska", "webm" -> "video/webm",
    "flv" -> "video/x-flv", "wmv" -> "video/x-ms-wmv", "m4v" -> "video/x-m4v"
  )

  private val MAX_IMAGE_SIZE = 5 * 1024 * 1024 // 5MB

  def replaceMediaPaths(input: String): String =
    val matches = MEDIA_REGEX.findAllMatchIn(input).toList
    if matches.isEmpty then input
    else
      var result = ""
      var lastEnd = 0
      matches.zipWithIndex.foreach { case (m, i) =>
        val path = m.group(1)
        val fullMatch = m.matched
        val pathIndexInMatch = fullMatch.indexOf(path)
        val pathStart = m.start + pathIndexInMatch
        result += input.substring(lastEnd, pathStart)
        result += s"[media ${i + 1}]"
        lastEnd = pathStart + path.length
      }
      result + input.substring(lastEnd)

  private def buildUserMessage(input: String): IO[Message] = IO.blocking {
    val matches = MEDIA_REGEX.findAllMatchIn(input).toList
    if matches.isEmpty then
      Message(MessageRole.User, Left(input))
    else
      val imageBlocks = scala.collection.mutable.ListBuffer.empty[ContentBlock.Image]
      val mediaItems = scala.collection.mutable.ListBuffer.empty[String]

      matches.foreach { m =>
        val filePathStr = m.group(1).replaceFirst("^~", sys.props.getOrElse("user.home", "~"))
        val ext = filePathStr.split("\\.").lastOption.map(_.toLowerCase).getOrElse("")
        val filePath = Paths.get(filePathStr)

        if !Files.exists(filePath) || !Files.isRegularFile(filePath) then
          mediaItems += s"Not a file: $filePathStr"
        else if VIDEO_EXTENSIONS.exists(filePathStr.toLowerCase.endsWith) then
          val sizeKb = (Files.size(filePath) / 1024).toInt
          mediaItems += s"Video: $filePathStr (${sizeKb}KB)"
        else if Files.size(filePath) > MAX_IMAGE_SIZE then
          val sizeMb = Files.size(filePath).toDouble / 1024 / 1024
          mediaItems += s"Image too large: $filePathStr (${sizeMb}%.1fMB > 5MB)"
        else
          val mediaType = MIME_MAP.getOrElse(ext, "application/octet-stream")
          val bytes = Files.readAllBytes(filePath)
          val base64 = java.util.Base64.getEncoder.encodeToString(bytes)
          val sizeKb = (bytes.length / 1024).toInt
          mediaItems += s"Image: $filePathStr (${sizeKb}KB, $mediaType)"
          imageBlocks += ContentBlock.Image(base64, mediaType)
      }

      val displayInput = replaceMediaPaths(input)
      val blocks = ContentBlock.Text(displayInput) :: imageBlocks.toList
      Message(MessageRole.User, Right(blocks))
  }

  def loadSystemPrompt(): String =
    // Try to load from resources first, then fallback
    val resource = Option(getClass.getResourceAsStream("/host.md"))
    resource match
      case Some(is) =>
        try Source.fromInputStream(is).mkString
        catch case _: Exception => defaultPrompt
      case None => defaultPrompt

  private val defaultPrompt = """你是 Nebflow 的命令行助手。用户通过终端与你对话，你可以读写项目文件、执行命令来帮用户完成工作。

你是用户与代码之间的接口。你的职责：
1. 理解用户意图
2. 将意图拆解为可执行的子任务
3. 使用工具执行任务
4. 汇总结果交付给用户

做事原则：
- 持续工作直到任务完全解决，然后才结束。
- 方法失败时先诊断原因，再换策略。
- 保持最小复杂度。
- 对你没读过的代码不提修改建议。
- 除非绝对必要，不创建新文件。

交互风格：
- 简洁、直接，直奔主题。
- 文件路径用反引号标记。
- 不使用 emoji。
- 不重复工具执行的细节。"""

  private case class ConsumeResult(
    text: String,
    toolCalls: List[ToolCall],
    results: List[(ToolCall, ToolExecResult)],
    stopReason: Option[String]
  )

  private def consumeStream(
    stream: fs2.Stream[IO, StreamChunk],
    store: ReplUi,
    llm: LlmHandle[IO]
  ): IO[ConsumeResult] =
    IO.ref(false).flatMap { interruptedRef =>
      IO.ref("").flatMap { textRef =>
        IO.ref(false).flatMap { textStartedRef =>
          IO.ref(List.empty[ToolCall]).flatMap { toolCallsRef =>
            IO.ref(List.empty[IO[(ToolCall, ToolExecResult)]]).flatMap { executionsRef =>

              val escFiber = store.onEscInterrupt(IO.delay(interruptedRef.set(true)))

              val processStream = stream.evalMap { chunk =>
                interruptedRef.get.flatMap { interrupted =>
                  if interrupted then
                    IO.raiseError(new UserAbort())
                  else
                    chunk match
                      case StreamChunk.TextDelta(delta) =>
                        if delta.nonEmpty then
                          textStartedRef.get.flatMap { started =>
                            textRef.update(_ + delta) *>
                            store.emitTextDelta(delta) *>
                            textStartedRef.set(true)
                          }
                        else IO.unit
                      case StreamChunk.ToolCallChunk(toolCall) =>
                        toolCallsRef.update(_ :+ toolCall) *>
                        textStartedRef.get.flatMap { hasText =>
                          if hasText then store.emitTextDone() else IO.unit
                        } *>
                        IO.delay(summarizeToolCall(toolCall)).flatMap { label =>
                          val execIO = store.emitToolStart(label) *>
                            executeTool(toolCall, System.getProperty("user.dir"), Some(llm)).flatMap { result =>
                              val resultSummary = summarizeToolResult(toolCall, result.content)
                              store.emitToolEnd(label, resultSummary, result.content, result.isError).as((toolCall, result))
                            }
                          executionsRef.update(_ :+ execIO)
                        }
                      case StreamChunk.Done(stopReason, usage, _) =>
                        stopReason match
                          case Some("max_tokens") => store.emitMaxTokens()
                          case Some("timeout") => store.emitTimeout()
                          case _ => textStartedRef.get.flatMap { hasText =>
                            if hasText then store.emitTextDone() else IO.unit
                          }
                }
              }.compile.drain

              processStream.handleErrorWith {
                case _: UserAbort => store.emitInterrupted() *> IO.unit
                case e => IO.raiseError(e)
              } *> executionsRef.get.flatMap { execs =>
                execs.sequence.flatMap { results =>
                  textRef.get.flatMap { text =>
                    toolCallsRef.get.map { toolCalls =>
                      ConsumeResult(text, toolCalls, results, None)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

  private def appendToolRound(
    messages: List[Message],
    text: String,
    toolCalls: List[ToolCall],
    results: List[(ToolCall, ToolExecResult)]
  ): List[Message] =
    val assistantBlocks = scala.collection.mutable.ListBuffer.empty[ContentBlock]
    if text.nonEmpty then assistantBlocks += ContentBlock.Text(text)
    toolCalls.foreach { call =>
      assistantBlocks += ContentBlock.ToolUse(call.id, call.name, call.input)
    }
    val assistantMsg = Message(MessageRole.Assistant, Right(assistantBlocks.toList))

    val resultBlocks = results.map { case (call, result) =>
      ContentBlock.ToolResult(call.id, result.content, Some(result.isError))
    }
    val resultMsg = Message(MessageRole.User, Right(resultBlocks))

    messages ++ List(assistantMsg, resultMsg)

  def runRepl(
    userInput: String,
    llm: LlmHandle[IO],
    projectRoot: String,
    initialMessages: List[Message] = Nil,
    store: ReplUi,
    onToolRound: Option[List[Message] => IO[Unit]] = None
  ): IO[List[Message]] =
    val systemPrompt = loadSystemPrompt()
    buildUserMessage(userInput).flatMap { userMessage =>
      def loop(messages: List[Message]): IO[List[Message]] =
        val allMessages = Message(MessageRole.System, Left(systemPrompt)) :: messages
        val stream = llm.sendStream(LlmRequest(
          messages = allMessages,
          sessionId = "repl",
          agentId = "user",
          tools = Some(ToolRegistry.ALL_TOOLS)
        ))

        consumeStream(stream, store, llm).flatMap { consumed =>
          if consumed.toolCalls.isEmpty then
            IO.pure(messages)
          else
            val updated = appendToolRound(messages, consumed.text, consumed.toolCalls, consumed.results)
            onToolRound.traverse_(_.apply(updated)) *> loop(updated)
        }.handleErrorWith {
          case _: UserAbort => IO.pure(messages)
          case e => IO.raiseError(e)
        }

      loop(initialMessages :+ userMessage)
    }
