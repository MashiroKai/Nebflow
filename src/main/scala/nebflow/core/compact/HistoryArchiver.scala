package nebflow.core.compact

import cats.effect.IO
import io.circe.syntax.*
import nebflow.shared.*
import nebflow.shared.given

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

/** Result of archiving a compaction — paths to generated files. */
case class CompactionArchive(
  sessionDir: String,
  beforeJsonPath: String,
  afterJsonPath: String
)

trait HistoryArchiver:

  /**
   * Archive a compaction run. Writes:
   *   - before.json : raw messages before compaction
   *   - after.json  : raw messages after compaction
   *
   * 2026-09-15 作者令（逐字）：「把上下文压缩写成 report 的功能删掉，并且删掉以往
   * 记录的 report。」—— 此前在本方法内额外落一份人读 markdown
   * （`<ts>-report.md`，首行 `# Context Compaction Report`）的整条生成链已删除：
   * report 文件写入点、`buildReport` 构造器，以及只服务于它的消息统计/渲染辅助
   * （`messageStats` / `messagesToText` / `messageToText`）与 `CompactionArchive.reportPath`。
   * before/after 原始 JSON 转储是**另一件事**，不在该令范围内，保持原样。
   *
   * Landing spot: `<sessionsRoot>/<sessionId>/compaction/<ts>-*` — grouped by
   * session under the data-root `sessions/` directory (2026-09-02 迁移：此前写
   * 项目目录 `archives/<shortSid>/`，污染 repo 工作区；现对齐
   * CompactionQueueStore 的按会话归组规范 `sessions/<sessionId>/`)。
   *
   * Returns Right(archive) on success; callers must treat failure as non-blocking.
   */
  def archiveCompaction(
    sessionId: String,
    sessionName: Option[String],
    agentName: String,
    before: List[Message],
    after: List[Message],
    mode: String,
    extra: Map[String, String] = Map.empty
  ): IO[Either[String, CompactionArchive]]

end HistoryArchiver

object HistoryArchiver:

  /** @param sessionsRoot the data-root `sessions/` directory (e.g.
   *        `PathUtil.dataRoot / "sessions"` — see GatewayMain wiring). */
  def fileSystem(sessionsRoot: os.Path): HistoryArchiver = new:

    def archiveCompaction(
      sessionId: String,
      sessionName: Option[String],
      agentName: String,
      before: List[Message],
      after: List[Message],
      mode: String,
      extra: Map[String, String]
    ): IO[Either[String, CompactionArchive]] = IO.blocking {
      try
        val now = Instant.now()
        val ts = formatTimestamp(now)
        val dir = sessionsRoot / sessionId / "compaction"
        os.makeDir.all(dir)

        val prefix = s"$ts"
        val beforeFile = dir / s"$prefix-before.json"
        val afterFile = dir / s"$prefix-after.json"

        // --- Raw JSON dumps (the only artifacts this archiver writes) ---
        os.write.over(beforeFile, before.asJson.spaces2)
        os.write.over(afterFile, after.asJson.spaces2)

        Right(
          CompactionArchive(
            sessionDir = dir.toString,
            beforeJsonPath = beforeFile.toString,
            afterJsonPath = afterFile.toString
          )
        )
      catch case e: Throwable => Left(e.getMessage)
    }

  private val FileTimeFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

  private def formatTimestamp(i: Instant): String = FileTimeFmt.format(i)

end HistoryArchiver
