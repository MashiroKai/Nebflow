package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.std.Mutex
import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath, StandardOpenOption}

/**
 * 打卡 done 历史（认知二期产品批·周报生成器数据源）：待办/打卡项被 done 销账时
 * 追加一条**只增不改**的 JSONL 记录。此前引擎只删行不留痕——「本周打卡 N/M」
 * 无从聚合（实施批前的实证缺口：store 只存当前态）。JSONL 选型 = 引擎侧
 * 持久写既有惯型（UsageRecordStore / NodeReportRegistry 同款）。
 *
 * 文件：`~/.stand/checkin-records/done-records.jsonl`，每行一条
 * {{{
 *   {"taskId":"…","name":"fitness-checkin","content":"今天：深蹲 5×5",
 *    "sessionId":"…","doneAt":1730000000000,"via":"tool"}
 * }}}
 * `via` ∈ "tool"（Schedule action=done 销账）| "ws"（前端打卡 UI 圆钮）。
 * 坏行跳过不炸整卷（聚合面的韧性：一行损坏 ≠ 全周无数据）。
 */
final case class DoneRecord(
  taskId: String,
  name: Option[String],
  content: String,
  sessionId: String,
  doneAt: Long,
  via: String
)

object DoneRecord:
  given Configuration = Configuration.default.withDefaults
  given Codec[DoneRecord] = ConfiguredCodec.derived

  val ViaTool: String = "tool"
  val ViaWs: String = "ws"

final class DoneRecordStore private (file: JPath, lock: cats.effect.std.Mutex[IO]):

  private def ensureParent: IO[Unit] =
    IO.blocking(Option(file.getParent).foreach(p => Files.createDirectories(p)))

  /** 追加一条 done 记录（只增不改；锁内写——并发销账不交错行）。 */
  def record(r: DoneRecord): IO[Unit] =
    lock.lock.surround {
      ensureParent *>
        IO.blocking {
          Files.write(
            file,
            (r.asJson.noSpaces + "\n").getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
          )
        }.void
    }

  /** 全量读（坏行跳过；文件不存在 = Nil）。 */
  def loadAll: IO[List[DoneRecord]] =
    IO.blocking {
      if Files.exists(file) then
        Files.readAllLines(file).toArray(new Array[String](0)).toList.flatMap { line =>
          val t = line.trim
          if t.isEmpty then None
          else jsonParse(t).flatMap(_.as[DoneRecord]).toOption
        }
      else Nil
    }

  /** 窗口查询（周报聚合面：[fromMs, toMs) 内完成的记录）。 */
  def between(fromMs: Long, toMs: Long): IO[List[DoneRecord]] =
    loadAll.map(_.filter(r => r.doneAt >= fromMs && r.doneAt < toMs))

end DoneRecordStore

object DoneRecordStore:

  /** 磁盘位置：`~/.stand/checkin-records/done-records.jsonl`。 */
  def apply(dataRoot: os.Path): DoneRecordStore =
    new DoneRecordStore(
      (dataRoot / "checkin-records" / "done-records.jsonl").toNIO,
      {
        import cats.effect.unsafe.implicits.global
        Mutex[IO].unsafeRunSync()
      }
    )

end DoneRecordStore
