package nebflow.core.profile

import cats.effect.IO
import cats.effect.std.Mutex
import io.circe.{Codec, Json}
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import nebflow.shared.NebflowLogger

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath, StandardOpenOption}

/**
 * 呈现校准信号持久化（呈现档入口改造+围栏语义批，作者令 2026-10-30 20:13；
 * 设计稿 §6.3 抱怨校准路的数据面）。单文件 `profile-calibration.json`：
 * {{{
 *   { "signals": [ { "ts": 1761821580000, "kind": "complaint",
 *                    "data": { "rule": "too-long", "detail": "…" } } ] }
 * }}}
 * 环形缓冲纪律：信号只追加，总量封顶 [[ProfileCalibrationStore.KeepLast]] 条
 * （超出丢最旧）——校准消费看的是**近期信号分布**，不是全史；文件永不无限
 * 增长。读-改-写全走单把 [[Mutex]]（PresentationProfileStore 同款纪律），
 * 崩溃窗口最长丢一次追加，档面不受影响。
 *
 * 本批只立采集面 + [[ProfileCalibrator]] 钩子；auto 调整逻辑下批接
 * （届时消费 suggest 产出）。
 */
final class ProfileCalibrationStore private (file: JPath):

  private val logger = NebflowLogger.forName("nebflow.profile.calibration")
  private lazy val lock: Mutex[IO] =
    import cats.effect.unsafe.implicits.global
    Mutex[IO].unsafeRunSync()

  /** 磁盘态（纯数据，spec 直断言）。 */
  final case class State(signals: List[ProfileCalibrationSignal.Signal] = List.empty)

  object State:
    given Configuration = Configuration.default.withDefaults
    given Codec[State] = ConfiguredCodec.derived

  def load: IO[State] =
    IO.blocking {
      if Files.exists(file) then
        val raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        jsonParse(raw).flatMap(_.as[State]) match
          case Right(s) => s
          case Left(e) =>
            // 损坏文件 = 回空信号表，不炸面（校准面永远可用，大不了重新积累）。
            logger.warnSync(s"profile-calibration.json unreadable (${e.getMessage}) — falling back to empty")
            State()
      else State()
    }

  private def persist(s: State): IO[Unit] =
    IO.blocking {
      Option(file.getParent).foreach(p => Files.createDirectories(p))
      Files.write(
        file,
        s.asJson.noSpaces.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
    }

  private def update(f: State => State): IO[State] =
    lock.lock.surround {
      for
        cur <- load
        next = f(cur)
        _ <- persist(next)
      yield next
    }

  /** 追加一条信号（环形缓冲：超帽丢最旧）。 */
  def record(signal: ProfileCalibrationSignal.Signal): IO[State] =
    update(s => s.copy(signals = (s.signals :+ signal).takeRight(ProfileCalibrationStore.KeepLast)))

  /** 最近 n 条（校准消费/调试面；纯读）。 */
  def recent(n: Int): IO[List[ProfileCalibrationSignal.Signal]] =
    load.map(_.signals.takeRight(math.max(0, n)))

  /** 全量信号（ProfileCalibrator.suggest 的输入面）。 */
  def all: IO[List[ProfileCalibrationSignal.Signal]] = load.map(_.signals)

end ProfileCalibrationStore

object ProfileCalibrationStore:

  /** 环形缓冲容量：只保留最近 500 条信号（校准看分布不看全史）。 */
  val KeepLast: Int = 500

  /** 磁盘位置：`~/.stand/profile-calibration.json`（数据根独立 JSON）。 */
  def defaultFile(dataRoot: os.Path): JPath =
    (dataRoot / "profile-calibration.json").toNIO

  def apply(dataRoot: os.Path): ProfileCalibrationStore =
    new ProfileCalibrationStore(defaultFile(dataRoot))

end ProfileCalibrationStore
