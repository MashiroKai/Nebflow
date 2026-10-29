package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.daemon.{DaemonConfig, DaemonStore}
import nebflow.perception.PerceptionClient
import nebflow.shared.{NebflowLogger, PathUtil}

import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import scala.concurrent.duration.*

/**
 * 统一 daemon 管理进程的引擎侧 dock 腿（统一 daemon 批 2026-10-29；作者裁定
 * 「独立daemon吧 正好和我们心跳进程合并 那就是管daemon的」）。
 *
 * 取代 GatewayMain 旧 `perceptionBoot`（引擎进程内五循环折中态）：
 *
 *  1. `writeToken` —— boot token 落 `<dataRoot>/perception/daemon.token`
 *     （0600，**每引擎 boot 轮换**：daemon 随引擎走（stopAll/reclaim 既有
 *     心跳语义），无旧 token 存活窗；token 不进 daemons.json/command/env——
 *     授权码不入明文配置的同纪律）。
 *  2. `upsertEntry` —— 统一 daemon 托管条目（id=`perception-daemon`）进
 *     `daemons.json`：autoStart=true（一起起停）、restartOnExit=true（一起被
 *     watchdog 管：指数退避 + 5 次上限，既有语义）、port（一起写健康面：面板行
 *     + 端口探针）。用户条目零触碰（只动本 id）。空 java.class.path（jpackage
 *     形态）⇒ 条目仍登记但不 autoStart（spawn 不可行，WARN + 面板可见）。
 *  3. `awaitHandshake` —— 引擎既有 autoStart（2s 延迟腿）拉起后轮询 daemon
 *     `/health`（10s 有界，**失败只 WARN 绝不阻塞启动**——原 perceptionBoot
 *     失败语义逐字），200 ⇒ [[PerceptionClient.bind]]（代理/工具腿就绪）。
 *
 * 引擎重启处理 = 现行心跳语义逐字：干净停机 DaemonService.stopAll 随引擎走；
 * 异常死亡 ⇒ 下次 boot reclaimStaleDaemons 按 marker+startTime 回收重启。
 */
object PerceptionDaemonDock:

  private val logger = NebflowLogger.forName("nebflow.perception.dock")

  /** 托管条目 id（daemons.json 单一标识；面板行同名显示）。 */
  val EntryId: String = "perception-daemon"

  /** 握手预算（有界；超时 WARN-only）。 */
  val HandshakeTimeoutSec: Int = 10

  /** token 文件（0600）路径。 */
  def tokenFile(home: os.Path): os.Path = home / "perception" / "daemon.token"

  /** 写 boot token（0600，每 boot 轮换；先于 spawn 落盘——daemon fail-fast 面）。 */
  def writeToken(home: os.Path): IO[String] =
    IO.blocking {
      val raw = new Array[Byte](32)
      new SecureRandom().nextBytes(raw)
      val token = raw.map("%02x".format(_)).mkString
      os.makeDir.all(home / "perception")
      os.write.over(
        tokenFile(home),
        token,
        perms = if scala.util.Properties.isWin then null else PosixFilePermissions.fromString("rw-------")
      )
      token
    }

  /** 端口挑选：绑 127.0.0.1:0 取实际端口即释（选中与 spawn 间的占位窗同
    * DaemonSpawn 端口门的固有竞态级别——占用时 daemon fail-fast，watchdog
    * 按 restartOnExit 语义走，面板 Crashed 可见）。 */
  def pickPort(): IO[Int] =
    IO.blocking {
      val s = new java.net.ServerSocket()
      try
        s.setReuseAddress(true)
        s.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress, 0))
        s.getLocalPort
      finally s.close()
    }

  /**
   * 统一 daemon 托管命令面（DaemonSpawn 家族同款拼装：java.home + java.class.path
   * + 主类 + --home/--port/--engine-port；`-Dnebflow.perception.llm.enabled` 按
   * 引擎当前值透传——kill-switch 两进程一致）。jpackage 形态（classpath 空）⇒
   * None（spawn 不可行，与 DaemonSpawn.classPathSpawnable 同判据）。纯函数（spec 钉）。
   */
  def buildCommand(home: os.Path, port: Int, enginePort: Int, llmEnabled: Boolean, classPath: String): Option[List[String]] =
    if classPath.trim.isEmpty then None
    else
      val javaBin = s"${sys.props.getOrElse("java.home", "java")}/bin/java"
      Some(List(
        javaBin,
        s"-Dnebflow.perception.llm.enabled=$llmEnabled",
        "-cp", classPath,
        "nebflow.perception.PerceptionDaemonMain",
        "--home", home.toString,
        "--port", port.toString,
        "--engine-port", enginePort.toString
      ))

  /** Upsert 结果：spawnable（classpath 可用）+ 挑定的 daemon 端口 + token。 */
  final case class Upserted(spawnable: Boolean, daemonPort: Int, token: String)

  /**
   * Upsert 托管条目（只动本 id；用户条目零触碰）。写 token 先行（daemon
   * fail-fast 面依赖）。不可 spawn 时条目仍登记（autoStart=false 保持面板
   * 可见性），返回 spawnable=false。
   */
  def upsertEntry(store: DaemonStore, home: os.Path, enginePort: Int): IO[Upserted] =
    writeToken(home).flatMap { token =>
      pickPort().flatMap { port =>
        buildCommand(
          home, port, enginePort,
          nebflow.perception.PerceptionService.LlmJudgeEnabled,
          sys.props.getOrElse("java.class.path", "")
        ) match
          case None =>
            logger
              .warn("perception daemon not spawnable (empty java.class.path — jpackage form); entry registered, autoStart off")
              .as(Upserted(spawnable = false, port, token))
          case Some(command) =>
            val cfg = DaemonConfig(
              id = EntryId,
              name = "感知守护进程",
              command = command,
              cwd = None,
              env = Map.empty,
              autoStart = true,
              restartOnExit = true,
              port = Some(port),
              healthCheckSec = 15
            )
            store.add(cfg).as(Upserted(spawnable = true, port, token))
      }
    }

  /**
   * 握手轮询：等 daemon `/health` 200（有界，默认 [[HandshakeTimeoutSec]]s；
   * spec 注短预算）⇒ PerceptionClient.bind。超时/失败 = WARN（不阻塞、不重试
   * 进 boot 链——autoStart/restartOnExit 家族语义自持后续）。
   */
  def awaitHandshake(daemonPort: Int, token: String, budgetMs: Long = HandshakeTimeoutSec * 1000L): IO[Unit] =
    val deadline = System.currentTimeMillis() + budgetMs
    pollHandshake(daemonPort, token, deadline).void

  /**
   * 握手单步（IO.defer 包住：`*>`/flatMap 右操作数急切求值，裸递归构造期即
   * StackOverflowError——sweepLoop/spoolLoop 同案，PerceptionCoreSpec 回归钉）。
   * 返回 true = 终局（docked 或预算耗尽 WARN）。
   */
  private def pollHandshake(daemonPort: Int, token: String, deadline: Long): IO[Boolean] =
    IO.defer {
      PerceptionClient.probeHealth(daemonPort, token, timeoutSec = 2).flatMap {
        case true =>
          PerceptionClient.bind(PerceptionClient.Endpoint(daemonPort, token)) *>
            logger.info(s"perception unified daemon docked: /health 200 (entry=$EntryId)").as(true)
        case false =>
          if System.currentTimeMillis() >= deadline then
            logger
              .warn(s"perception unified daemon handshake timeout — boot continues; autoStart/restartOnExit will settle it")
              .as(true)
          else IO.sleep(200.millis).as(false)
      }
    }.flatMap(done => if done then IO.pure(true) else pollHandshake(daemonPort, token, deadline))

  /** dock 顶腿：upsert + 握手（独立冒烟/单腿调用形态；autoStart 由调用方另行驱动）。 */
  def dock(store: DaemonStore, enginePort: Int, home: os.Path = PathUtil.dataRoot): IO[Unit] =
    prepare(store, enginePort, home).flatMap {
      case Some(up) => handshake(up)
      case None     => IO.unit
    }

  /**
   * boot 腿第一半：upsert 托管条目（用户条目零触碰）。GatewayMain 在
   * autoStart **之前**调用（条目在场才可被家族拉起）。None = jpackage 形态
   * （spawn 不可行；面板条目已登记，WARN 可见）。
   */
  def prepare(store: DaemonStore, enginePort: Int, home: os.Path = PathUtil.dataRoot): IO[Option[Upserted]] =
    upsertEntry(store, home, enginePort).map {
      case u if u.spawnable => Some(u)
      case _                => None
    }

  /** boot 腿第二半：autoStart 之后等 /health 200（10s 有界，WARN-only）。 */
  def handshake(up: Upserted): IO[Unit] = awaitHandshake(up.daemonPort, up.token)
