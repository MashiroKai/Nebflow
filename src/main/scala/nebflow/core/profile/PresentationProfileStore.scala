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
 * 方案档案持久化（认知二期产品批；作者裁定 §9-11：落 `~/.stand` 数据根独立
 * JSON，与引擎配置分离）。单文件 `presentation-profiles.json`：
 * {{{
 *   { "current": "expert-minimal",
 *     "overrides": { "adhd-friendly": { "maxLines": "6" } } }
 * }}}
 * `current` = 当前档键（五档之一）；`overrides` = 档参数的逐项覆盖（string 值，
 * 经 [[PresentationProfile.validateOverride]] 校验后才入账）。缺文件/缺键 =
 * 默认值（DefaultKey / 空覆盖），存量零迁移。
 *
 * 读-改-写全走单把 [[Mutex]]（ScheduledTaskStore 同款纪律），覆盖写入用
 * CREATE+TRUNCATE_EXISTING——崩溃窗口最长丢一次覆盖写入，档选择（current）
 * 不受影响。
 */
final class PresentationProfileStore private (file: JPath):

  private val logger = NebflowLogger.forName("nebflow.profile.store")
  // CE 3.5 的 Mutex 无 unsafe 直构 —— lazy + global runtime 物化一次
  // （ScheduledTaskStore.storeLock 同款先例）。
  private lazy val lock: Mutex[IO] =
    import cats.effect.unsafe.implicits.global
    Mutex[IO].unsafeRunSync()

  /** 磁盘态（纯数据，spec 直断言）。 */
  final case class State(current: String, overrides: Map[String, Map[String, String]])

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
            // 损坏文件 = 回默认档，不炸面（呈现面永远有可用档）。
            logger.warnSync(s"presentation-profiles.json unreadable (${e.getMessage}) — falling back to defaults")
            State(PresentationProfile.DefaultKey, Map.empty)
      else State(PresentationProfile.DefaultKey, Map.empty)
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

  /** 当前档（未知键兜底默认档——文件被手改坏也不出档外值）。 */
  def currentProfile: IO[PresentationProfile] =
    load.map(s => PresentationProfile.find(s.current).getOrElse(PresentationProfile.ExpertMinimal))

  /**
   * 选档（选档器 UI 主路，§6.3 显式选择）：未知档键 = Left(可行动报错)。
   */
  def setCurrent(key: String): IO[Either[String, PresentationProfile]] =
    PresentationProfile.find(key) match
      case None =>
        IO.pure(
          Left(
            s"unknown profile \"$key\" — expected one of ${PresentationProfile.All.map(_.key).mkString(", ")}"
          )
        )
      case Some(p) =>
        update(_.copy(current = p.key)).map(_ => Right(p))

  /**
   * 档参数逐项覆盖（§6.2 表的产品化）：先校验后入账；value 传 null/缺省 =
   * 清除该参数覆盖（回档默认）。
   */
  def setOverride(profileKey: String, param: String, value: Option[String]): IO[Either[String, State]] =
    PresentationProfile.find(profileKey) match
      case None =>
        IO.pure(
          Left(s"unknown profile \"$profileKey\" — expected one of ${PresentationProfile.All.map(_.key).mkString(", ")}")
        )
      case Some(_) =>
        value match
          case None =>
            update(s => s.copy(overrides = s.overrides.updatedWith(profileKey)(_.map(_ - param).filter(_.nonEmpty))))
              .map(Right(_))
          case Some(v) =>
            PresentationProfile.validateOverride(param, v) match
              case Some(err) => IO.pure(Left(err))
              case None =>
                update(s =>
                  s.copy(overrides = s.overrides.updatedWith(profileKey) {
                    case Some(m) => Some(m + (param -> v.trim))
                    case None => Some(Map(param -> v.trim))
                  })
                ).map(Right(_))

  /**
   * 档定义 + 覆盖合成（下发/渲染唯一取值口）：内置定义上叠该档 overrides。
   * 纯函数。
   */
  def resolve(profile: PresentationProfile, overrides: Map[String, String]): PresentationProfile =
    if overrides.isEmpty then profile
    else
      def num(field: String, default: Int): Int =
        overrides.get(field).flatMap(_.toIntOption).getOrElse(default)
      profile.copy(
        maxLines = num("maxLines", profile.maxLines),
        keyNumbers = num("keyNumbers", profile.keyNumbers),
        foldDefault = overrides.getOrElse("foldDefault", profile.foldDefault),
        imageDensity = overrides.getOrElse("imageDensity", profile.imageDensity),
        reminderCadence = overrides.getOrElse("reminderCadence", profile.reminderCadence),
        supervision = overrides.getOrElse("supervision", profile.supervision),
        pendingStyle = overrides.getOrElse("pendingStyle", profile.pendingStyle)
      )

  /** 当前档的合成结果（含覆盖）。 */
  def resolvedCurrent: IO[PresentationProfile] =
    load.flatMap(s =>
      IO.pure(resolve(PresentationProfile.find(s.current).getOrElse(PresentationProfile.ExpertMinimal), s.overrides.getOrElse(s.current, Map.empty)))
    )

  /** WS 下发面：当前态全量（档集 + 覆盖 + 当前键）。 */
  def snapshotJson: IO[Json] =
    load.map { s =>
      val resolved = PresentationProfile.All.map(p =>
        val ov = s.overrides.getOrElse(p.key, Map.empty)
        val base = p.asJson
        val withOv =
          if ov.isEmpty then base
          else base.mapObject(_.add("overrides", ov.asJson))
        withOv
      )
      Json.obj(
        "current" -> PresentationProfile.find(s.current).map(_.key).getOrElse(PresentationProfile.DefaultKey).asJson,
        "profiles" -> resolved.asJson,
        "overrides" -> s.overrides.asJson
      )
    }

end PresentationProfileStore

object PresentationProfileStore:

  /** 磁盘位置：`~/.stand/presentation-profiles.json`（裁定 §9-11：数据根独立 JSON）。 */
  def defaultFile(dataRoot: os.Path): JPath =
    (dataRoot / "presentation-profiles.json").toNIO

  def apply(dataRoot: os.Path): PresentationProfileStore =
    new PresentationProfileStore(defaultFile(dataRoot))

end PresentationProfileStore
