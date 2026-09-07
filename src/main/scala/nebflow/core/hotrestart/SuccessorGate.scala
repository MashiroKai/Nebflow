package nebflow.core.hotrestart

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}

import scala.concurrent.duration.*

/** 握手 intent（hot-restart 批设计 §3.3）——旧实例 write-ahead 落盘、后继进程读它
  * 获知旧 pid 与目标端口；phase 字段由后继回写（readyToBind）或旧实例中止时改写
  * （failed）。唯一新增盘上产物（设计原则 4：零新持久面）。
  *
  * 落位 `<dataRoot>/restart/intent.json`，AtomicJson 原子写（R6：临时文件+rename，
  * 读侧只见完整旧文件或完整新文件）。
  */
final case class HotRestartIntent(
  /** 本次重启世代（epoch millis at intent write——握手文件链的关联键）。 */
  generation: Long,
  /** 旧实例 pid——后继 [s4] 等它死亡（确认优雅链走完、端口即将让渡，非赌博 sleep）。 */
  oldPid: Long,
  host: String,
  port: Int,
  home: String,
  /** "bundled" | "jar"——仅决定 spawn 命令构造（两形态共用同一握手协议，§4）。 */
  form: String,
  /** spawn 命令摘要（可观测/失败取证用）。 */
  spawnCmd: String,
  triggerSource: String,
  /** "spawned"（旧实例写）→ "readyToBind"（后继 Zone A 完成回写）→ 归档；
    * 中止路径改写 "failed"（failure 字段带原因）。 */
  phase: String,
  ts: Long,
  failure: Option[String] = None
)

object HotRestartIntent:
  given Encoder[HotRestartIntent] = Encoder.derived
  given Decoder[HotRestartIntent] = Decoder.derived

/** 后继侧终态记录（[s7] 后继 bind 成功后写 successor.json——握手完成终态，兼作
  * 可观测/验收锚点）。 */
final case class SuccessorRecord(generation: Long, newPid: Long, version: String, ts: Long)
object SuccessorRecord:
  given Encoder[SuccessorRecord] = Encoder.derived
  given Decoder[SuccessorRecord] = Decoder.derived

/** 握手文件 IO 与后继进场闸（hot-restart 批设计 §3.3 [s1]/[s3]/[s4] + §12 卫生）。
  *
  * 本对象是纯文件/纯逻辑层——不 spawn 进程、不触碰端口（探测经函数注入），GatewayMain
  * 与 HotRestart 编排器都依赖它；注入式探测使 [s4] 等待逻辑可无进程单测。
  */
object SuccessorGate:
  private val logger = NebflowLogger.forName("nebflow.hotrestart")

  val RestartDirName = "restart"
  val IntentFileName = "intent.json"
  val SuccessorFileName = "successor.json"
  val LastRestartFileName = "last-restart.json"

  /** R6：后继拒绝陈旧 intent（>60s 拒绝进场——spawn 应当即时，超龄 = 链路异常）。 */
  val MaxIntentAgeMs: Long = 60_000L
  /** 验收 12：普通 boot 发现 >10min 未归档 intent → WARN + 归档，不阻塞。 */
  val StaleIntentArchiveMs: Long = 600_000L
  /** awaitHandover 的特殊失败标记：旧 pid 已死但端口仍有活监听 = 外来抢占者进场窗
    * （设计 §3.3）——调用方必须改走 ensureSingleInstance 既有语义，不得直接进场。 */
  val ForeignOccupantSignal = "foreign-occupant-after-old-death"

  def restartDir(dataRoot: os.Path): os.Path = dataRoot / RestartDirName
  def intentPath(dataRoot: os.Path): os.Path = restartDir(dataRoot) / IntentFileName
  def successorPath(dataRoot: os.Path): os.Path = restartDir(dataRoot) / SuccessorFileName
  def lastRestartPath(dataRoot: os.Path): os.Path = restartDir(dataRoot) / LastRestartFileName

  // ===== intent 文件 IO（全 AtomicJson 原子写，R6）=====

  def writeIntent(path: os.Path, intent: HotRestartIntent): IO[Unit] =
    AtomicJson.write(path, intent.asJson.noSpaces)

  /** 读 intent；缺失/损坏 → None（损坏文件不清除——留给陈旧归档或人工取证）。 */
  def readIntent(path: os.Path): IO[Option[HotRestartIntent]] =
    IO.blocking(if os.exists(path) then Some(os.read(path)) else None).flatMap {
      case None => IO.pure(None)
      case Some(raw) => IO.fromOption(io.circe.parser.decode[HotRestartIntent](raw).toOption)(
        new Exception("undecodable")).map(Some(_)).handleErrorWith(_ => IO.pure(None))
    }

  /** 回写 phase（read-modify-write 原子；文件缺失 no-op——中止路径竞态下文件可能
    * 已被归档）。 */
  def markPhase(path: os.Path, phase: String, failure: Option[String] = None): IO[Unit] =
    readIntent(path).flatMap {
      case None => IO.unit
      case Some(intent) => writeIntent(path, intent.copy(phase = phase, failure = failure))
    }

  /** 中止路径失败记录（验收 4/5：intent failure 记录三有一）。 */
  def markFailed(path: os.Path, reason: String): IO[Unit] =
    markPhase(path, "failed", Some(reason))

  // ===== successor.json + 归档（[s7]/[s8]，验收 12）=====

  def writeSuccessor(dataRoot: os.Path, generation: Long, newPid: Long): IO[Unit] =
    val rec = SuccessorRecord(generation, newPid, nebflow.Version.string, System.currentTimeMillis())
    AtomicJson.write(successorPath(dataRoot), rec.asJson.noSpaces)

  /** intent 归档为 last-restart.json（后继握手完成终态动作；成功重启后 intent 不残留）。 */
  def archiveIntent(dataRoot: os.Path): IO[Unit] =
    IO.blocking {
      val ip = intentPath(dataRoot)
      if os.exists(ip) then
        os.makeDir.all(lastRestartPath(dataRoot) / os.up)
        os.move(ip, lastRestartPath(dataRoot), replaceExisting = true)
    }

  /** 普通 boot（非 succeed）的握手文件卫生（验收 12）：>10min 未归档 intent → WARN
    * + 归档，绝不阻塞/绝不丢弃（内容保留在 last-restart.json 供取证）。 */
  def archiveStaleIntent(dataRoot: os.Path, nowMs: Long = System.currentTimeMillis()): IO[Unit] =
    val ip = intentPath(dataRoot)
    IO.blocking {
      if os.exists(ip) then Some((os.mtime(ip), os.read(ip))) else None
    }.flatMap {
      case None => IO.unit
      case Some((mtime, _)) if nowMs - mtime <= StaleIntentArchiveMs => IO.unit
      case Some((mtime, content)) =>
        logger
          .warn(
            s"[hot-restart] stale unarchived intent found (${(nowMs - mtime) / 1000}s old) — archiving (not blocking boot)")
          .flatMap(_ =>
            IO.blocking {
              os.write.over(lastRestartPath(dataRoot), content, createFolders = true)
              os.remove(ip)
              ()
            }.handleErrorWith(e => logger.warn(s"[hot-restart] stale intent archive failed: ${e.getMessage}")))
    }

  // ===== [s1] 后继进场校验（纯函数）=====

  /** 后继 boot 的 intent 校验（R6）：home 必须等于本进程 data root（防跨 home 误接
    * 力）、port/host 必须与解析后的 gateway 配置一致（argv 断链检测）、ts 必须新鲜
    * （>60s 拒绝进场）。任一不过 = 大声拒绝 boot——旧实例不受影响（仍在服务，其
    * C2 会超时中止并清理 draining，fail-safe 保持服务）。 */
  def validate(
    ctx: SuccessorContext,
    expectedHome: os.Path,
    expectedHost: String,
    expectedPort: Int,
    nowMs: Long = System.currentTimeMillis(),
    maxAgeMs: Long = MaxIntentAgeMs
  ): Either[String, Unit] =
    if ctx.home != expectedHome.toString then
      Left(s"intent home '${ctx.home}' != this instance's data root '$expectedHome'")
    else if ctx.port != expectedPort then
      Left(s"intent port ${ctx.port} != resolved gateway port $expectedPort (spawn argv/env broken?)")
    else if ctx.host != expectedHost then
      Left(s"intent host '${ctx.host}' != resolved gateway host '$expectedHost'")
    else if nowMs - ctx.ts > maxAgeMs then
      Left(s"intent is stale (age ${nowMs - ctx.ts}ms > ${maxAgeMs}ms) — refusing to enter")
    else Right(())

  // ===== [s4] 端口让渡等待（探测注入，纯逻辑可单测）=====

  /** 等旧 pid 死亡（≤pidDeadlineMs，对「旧实例走完优雅链、端口即将让渡」的确认）
    * → connect 探测无活监听 → 放行进场。探测语义对齐 SingleInstanceGuard（TW socket
    * 拒绝 connect → false = 无活监听；不在此处 strict bind——Ember 的 bind + 既有
    * 65s fail-open 语义归下游）。
    *
    * 返回：
    *  - Right(()) —— 可进场
    *  - Left(ForeignOccupantSignal) —— 旧 pid 已死但端口有活监听 = 外来抢占窗，调用方
    *    改走 ensureSingleInstance 既有语义（verified stale 清除 / foreign 大声拒绝）
    *  - Left(其他) —— 旧 pid 等待超时（旧实例挂死中途）→ 后继放弃进场退出，旧实例
    *    继续服务 / watchdog 人工兜底
    */
  def awaitHandover(
    oldPid: Long,
    port: Int,
    pidAlive: Long => IO[Boolean],
    liveListener: Int => IO[Boolean],
    pidDeadlineMs: Long = 90_000L,
    pollMs: FiniteDuration = 250.millis,
    nowMs: () => Long = () => System.currentTimeMillis()
  ): IO[Either[String, Unit]] =
    val deadline = nowMs() + pidDeadlineMs
    def pidWait: IO[Either[String, Unit]] =
      pidAlive(oldPid).flatMap { alive =>
        if !alive then IO.pure(Right(()))
        else if nowMs() > deadline then
          IO.pure(Left(s"old instance (pid $oldPid) still alive after ${pidDeadlineMs}ms — handover abandoned"))
        else IO.sleep(pollMs) *> pidWait
      }
    pidWait.flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(()) =>
        liveListener(port).map { live =>
          if live then Left(ForeignOccupantSignal) // 进场窗被抢占——交 ensureSingleInstance 裁决
          else Right(())                            // 无活监听（空闲或仅 TW）——进场
        }
    }
end SuccessorGate

/** 后继进程上下文（Main 在 --succeed 分流时经静态 setter 传入——GatewayConfig
  * ._portOverride 同款模式，GatewayMain.run 保持零参数契约）。 */
final case class SuccessorContext(
  oldPid: Long,
  host: String,
  port: Int,
  home: String,
  generation: Long,
  ts: Long,
  intentPath: os.Path
)

object SuccessorContext:
  private var _current: Option[SuccessorContext] = None

  def set(ctx: SuccessorContext): Unit = _current = Some(ctx)
  def get: Option[SuccessorContext] = _current
  def clear(): Unit = _current = None

  /** 从 intent 文件构建上下文（解析失败/文件缺失 → Left；此处只做解析层校验，
    * home/port/新鲜度校验由 [[SuccessorGate.validate]] 在 boot 链上做）。 */
  def load(intentPath: os.Path): Either[String, SuccessorContext] =
    if !os.exists(intentPath) then Left(s"intent file not found: $intentPath")
    else
      io.circe.parser.decode[HotRestartIntent](os.read(intentPath)) match
        case Left(err) => Left(s"intent file undecodable: ${err.getMessage}")
        case Right(intent) =>
          Right(
            SuccessorContext(
              oldPid = intent.oldPid,
              host = intent.host,
              port = intent.port,
              home = intent.home,
              generation = intent.generation,
              ts = intent.ts,
              intentPath = intentPath
            )
          )
end SuccessorContext
