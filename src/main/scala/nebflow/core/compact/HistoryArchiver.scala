package nebflow.core.compact

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.*
import nebflow.shared.given

trait HistoryArchiver:
  /** Archive messages before compaction. Returns Right(path) on success, Left(reason) on failure.
   *  Callers should treat failure as non-blocking (log and continue). */
  def archive(sessionId: String, messages: List[Message]): IO[Either[String, String]]

  /** Archive a before/after comparison of compaction. Returns Right(path) on success.
   *  Callers should treat failure as non-blocking (log and continue). */
  def archiveComparison(
    sessionId: String,
    mode: String,
    before: List[Message],
    after: List[Message]
  ): IO[Either[String, String]]

object HistoryArchiver:

  def fileSystem(root: os.Path): HistoryArchiver = new:
    def archive(sessionId: String, messages: List[Message]): IO[Either[String, String]] =
      IO.blocking {
        try
          val ts = java.time.Instant.now().toEpochMilli
          val dir = root / "archives" / sessionId
          os.makeDir.all(dir)
          val target = dir / s"$ts.json"
          os.write.over(target, messages.asJson.spaces2)
          Right(target.toString)
        catch case e: Throwable => Left(e.getMessage)
      }

    def archiveComparison(
      sessionId: String,
      mode: String,
      before: List[Message],
      after: List[Message]
    ): IO[Either[String, String]] = IO.blocking {
      try
        val ts = java.time.Instant.now().toEpochMilli
        val dir = root / "archives" / sessionId
        os.makeDir.all(dir)
        val target = dir / s"$ts-comparison.json"
        val json = Json.obj(
          "timestamp" -> Json.fromLong(ts),
          "mode" -> Json.fromString(mode),
          "beforeCount" -> Json.fromInt(before.size),
          "afterCount" -> Json.fromInt(after.size),
          "before" -> before.asJson,
          "after" -> after.asJson
        )
        os.write.over(target, json.spaces2)
        Right(target.toString)
      catch case e: Throwable => Left(e.getMessage)
    }
end HistoryArchiver
