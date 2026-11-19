package nebflow.core.compact

import cats.effect.IO
import io.circe.syntax.*
import nebflow.db.{DbHandle, DbHost, ReadResult}
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

  private val logger = NebflowLogger.forName("nebflow.compaction.archiver")

  /** 生产默认落盘根（GatewayMain:673 装配点同值；默认根判别自开的基准）。
   *  def 而非 val：PathUtil.dataRoot 是测试换根缝，须每次求值。 */
  def defaultSessionsRoot: os.Path = PathUtil.dataRoot / "sessions"

  /**
   * @param sessionsRoot the data-root `sessions/` directory (e.g.
   *        `PathUtil.dataRoot / "sessions"` — see GatewayMain wiring).
   * @param db db 接线第一阶 C7（快照面落库）：显式注入缝（测试传临时根句柄）。
   *        缺省 + 默认 sessionsRoot 构造（= 生产装配，GatewayMain 无参调用）⇒
   *        自开共享句柄（DbHost best-effort——失败 ⇒ 纯旧档行为，零波及）。
   */
  def fileSystem(sessionsRoot: os.Path, db: Option[DbHandle] = None): HistoryArchiver = new:

    /** db 影子面句柄（None = 未接线/自开失败 ⇒ db 腿全部 no-op）。 */
    private val dbHandle: Option[DbHandle] = db.orElse(
      Option.when(sessionsRoot == defaultSessionsRoot)(
        DbHost.openBestEffort(PathUtil.dataRoot.toNIO)
      ).flatten
    )

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
    }.flatMap { res =>
      // db 影子腿（C7）：旧档写成功后补写快照 + 压缩点位 + 读回校验；任何 db
      // 故障 ⇒ WARN、返回值语义不变（旧档仍是主读面，best-effort 铁律 0.1）。
      // 元信息（sessionName/agentName/mode/extra）留旧档侧——第一阶边界。
      res match
        case Right(_) => shadowToDb(sessionId, before, after).as(res)
        case Left(_)  => IO.pure(res)
    }

    /** 单份快照落 db + 读回校验（不符/失败 ⇒ WARN，返回 db 内引用位或 None）。 */
    private def putSnapshot(h: DbHandle, sessionId: String, label: String, content: String): IO[Option[String]] =
      h.writeSnapshot(sessionId, content).attempt.flatMap {
        case Left(e) =>
          logger.warn(s"db snapshot($label) failed for $sessionId: ${e.getMessage}") *> IO.pure(None)
        case Right(n) =>
          val ref = s"snapshots/$n.json"
          h.readSnapshot(sessionId, n).attempt.flatMap {
            case Right(ReadResult.Present(c)) if c == content => IO.pure(Some(ref))
            case Right(other) =>
              logger.warn(s"db snapshot($label) readback mismatch for $sessionId ($ref): $other") *>
                IO.pure(Some(ref))
            case Left(e) =>
              logger.warn(s"db snapshot($label) readback failed for $sessionId ($ref): ${e.getMessage}") *>
                IO.pure(Some(ref))
          }
      }

    /**
     * before/after 双快照（会话 `snapshots/<n>.json` 位，设计 §3.4(e)）+
     * compactPoint 点位行（refs = db 快照位；旧档路径仍由返回值给宿主）。
     */
    private def shadowToDb(
      sessionId: String,
      before: List[Message],
      after: List[Message]
    ): IO[Unit] = dbHandle match
      case None => IO.unit
      case Some(h) =>
        putSnapshot(h, sessionId, "before", before.asJson.spaces2).flatMap { bRef =>
          putSnapshot(h, sessionId, "after", after.asJson.spaces2).flatMap { aRef =>
            (bRef, aRef) match
              case (Some(b), Some(a)) =>
                h.appendCompactPoint(sessionId, b, a,
                  tokens = (before.size + after.size).toLong).attempt.flatMap {
                  case Left(e) =>
                    logger.warn(s"db compactPoint append failed for $sessionId: ${e.getMessage}")
                  case Right(_) => IO.unit
                }
              case _ => IO.unit // 快照腿任一失败 ⇒ 点位不落（无引用可指）
          }
        }

  private val FileTimeFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

  private def formatTimestamp(i: Instant): String = FileTimeFmt.format(i)

end HistoryArchiver
