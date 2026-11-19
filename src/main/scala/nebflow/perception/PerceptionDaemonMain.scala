package nebflow.perception

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.NebflowLogger
import org.http4s.HttpRoutes
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

import scala.concurrent.duration.*

/**
 * 统一 daemon 管理进程入口（统一 daemon 批 2026-10-29；作者裁定逐字
 * 「独立daemon吧 正好和我们心跳进程合并 那就是管daemon的」）。
 *
 * 单进程双职责：
 *  - 职责A 感知五循环（poll/sweep/digest/spool/idle）+ MailImportanceJudge
 *    判定逻辑（裁决解析在侧，LLM transport 出域）——自引擎进程抽出；
 *  - 职责B 自心跳 watchdog（30s 拍活性日志）+ `/health` 健康面——daemon
 *    家族的心跳语义在本进程自持。
 *
 * 托管关系（管 daemon 的）：由引擎 GatewayMain 经 DaemonService 声明托管
 * （daemons.json 条目 autoStart/restartOnExit/端口健康探针/pid marker/stopAll
 * ——现行心跳语义逐字）；同时保留**独立可运行性**（隔离冒烟）：
 * {{{
 *   java -cp <jar> nebflow.perception.PerceptionDaemonMain --home <root> --port N --engine-port M
 * }}}
 *
 * 进程契约：
 *  - `--home <root>`（必填）：数据根（PathUtil.setDataRoot 同 `Main` 语义）；
 *    token 必读 `<root>/perception/daemon.token`（0600，引擎先于 spawn 落盘），
 *    缺失 fail-fast exit 2（错误面到 stderr，不经 logger——logback 尚未重配）。
 *  - `--port N`（0 = 自选空闲，日志报实际值）；绑定 127.0.0.1（IPC 边界）。
 *  - `--engine-port M`：引擎 API 端口（ingest 出域腿目标）。
 *  - 日志单写者：boot 期编程式重配 logback —— FILE 落
 *    `<root>/logs/perception-daemon/logs/stand.log`（引擎 stand.log 单写者
 *    不变量不破），CONSOLE 保留（stdout/stderr 由 DaemonService 环形缓冲
 *    收入面板 recentOutput = 面板健康面）。
 *  - 退出：SIGTERM/SIGINT ⇒ IOApp 取消腿 ⇒ ember 释放 + 五循环 fiber 取消
 *    （引擎 stopAll/reclaim 的对侧）。
 */
object PerceptionDaemonMain extends IOApp:

  private val failFlush = "ERROR nebflow.perception.daemon - "

  /** 退出码：token 缺失 / 参数缺失 = 配置面 fail-fast（非崩溃）。 */
  val ExitConfig = 2

  final case class Args(home: os.Path, port: Int, enginePort: Int)

  def parseArgs(argv: List[String]): Either[String, Args] =
    def loop(rest: List[String], home: Option[os.Path], port: Int, enginePort: Int): Either[String, Args] =
      rest match
        case Nil =>
          home match
            case Some(h) if enginePort > 0 => Right(Args(h, port, enginePort))
            case Some(_)                   => Left("--engine-port is required (engine ingest target)")
            case None                      => Left("--home is required")
        case "--home" :: v :: t      => loop(t, Some(os.Path(v, os.pwd)), port, enginePort)
        case "--port" :: v :: t      =>
          v.toIntOption match
            case Some(p) => loop(t, home, p, enginePort)
            case None    => Left(s"--port must be an integer: $v")
        case "--engine-port" :: v :: t =>
          v.toIntOption match
            case Some(p) if p > 0 => loop(t, home, port, p)
            case _                => Left(s"--engine-port must be a positive integer: $v")
        case other :: _ => Left(s"unknown argument: ${other.take(40)}")
    loop(argv, None, 0, 0)

  def run(args: List[String]): IO[ExitCode] =
    parseArgs(args) match
      case Left(err) =>
        // logger 未就绪（logback 未重配）——stderr 直出面（fail-fast 契约）。
        IO.blocking(System.err.println(s"$failFlush boot refused: $err")).as(ExitCode(ExitConfig))
      case Right(a) =>
        // 进程第一事实：数据根（同 Main --home 语义），先于任何感知路径解析。
        nebflow.shared.PathUtil.setDataRoot(a.home)
        isolateLogging(a.home) match
          case Left(err) =>
            IO.blocking(System.err.println(s"$failFlush logging isolation failed: $err")).as(ExitCode(ExitConfig))
          case Right(_) =>
            serve(a)

  // ── logging isolation（先于任何 logger 使用；见类注「日志单写者」）──────

  /**
   * 编程式重配 logback 根 logger：CONSOLE（保留——面板 recentOutput 面）+
   * FILE 落 `<home>/logs/perception-daemon/logs/stand.log`。须在首个 slf4j
   * logger 创建前调用（本 object 体零 logger 引用；serve() 才触达）。
   * 失败返回 Left（console 面仍在，进程可继续以 console-only 跑）。
   */
  private[perception] def isolateLogging(home: os.Path): Either[String, Unit] =
    try
      val ctx = org.slf4j.LoggerFactory.getILoggerFactory.asInstanceOf[ch.qos.logback.classic.LoggerContext]
      ctx.stop()
      val root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
      root.detachAndStopAllAppenders()

      val console = new ch.qos.logback.core.ConsoleAppender[ch.qos.logback.classic.spi.ILoggingEvent]
      console.setContext(ctx)
      console.setEncoder(encoder(ctx, "%d{HH:mm:ss.SSS} %msg%n"))
      console.setName("CONSOLE")
      console.start()

      val logRoot = home / "logs" / "perception-daemon" / "logs"
      os.makeDir.all(logRoot)
      val file = new ch.qos.logback.core.rolling.RollingFileAppender[ch.qos.logback.classic.spi.ILoggingEvent]
      file.setContext(ctx)
      file.setEncoder(encoder(ctx, "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %replace(%msg){'\\x1B\\[[0-9;]*m', ''}%n"))
      file.setFile((logRoot / "stand.log").toString)
      val policy = new ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
      policy.setContext(ctx)
      policy.setParent(file)
      policy.setFileNamePattern((logRoot / "stand.%d{yyyy-MM-dd}.%i.log").toString)
      policy.setMaxHistory(7)
      policy.setMaxFileSize(ch.qos.logback.core.util.FileSize.valueOf("50MB"))
      policy.setTotalSizeCap(ch.qos.logback.core.util.FileSize.valueOf("200MB"))
      policy.start()
      file.setRollingPolicy(policy)
      file.setName("FILE")
      file.start()

      root.setLevel(ch.qos.logback.classic.Level.INFO)
      root.addAppender(console)
      root.addAppender(file)
      ctx.start()
      Right(())
    catch case e: Throwable => Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")

  private def encoder(ctx: ch.qos.logback.classic.LoggerContext, pattern: String): ch.qos.logback.classic.encoder.PatternLayoutEncoder =
    val enc = new ch.qos.logback.classic.encoder.PatternLayoutEncoder
    enc.setContext(ctx)
    enc.setPattern(pattern)
    enc.start()
    enc

  // ── serving ───────────────────────────────────────────────────────────

  private def serve(a: Args): IO[ExitCode] =
    val logger = NebflowLogger.forName("nebflow.perception.daemon")
    val bootAt = System.currentTimeMillis()
    def healthJson: IO[Json] = IO(System.currentTimeMillis()).map { now =>
      Json.obj(
        "ok" -> true.asJson,
        "role" -> "perception-unified-daemon".asJson,
        "bootedAt" -> bootAt.asJson,
        "uptimeMs" -> (now - bootAt).asJson,
        "loops" -> List("poll", "sweep", "digest", "spool", "idle").asJson,
        "enginePort" -> a.enginePort.asJson
      )
    }
    tokenOf(a.home) match
      case Left(err) => logger.error(s"boot refused: $err").as(ExitCode(ExitConfig))
      case Right(token) =>
        val leg = new PerceptionEngineLeg(a.enginePort, token)
        val bindPort = if a.port == 0 then pickFreePort() else a.port
        for
          _ <- logger.info(
            s"perception unified daemon starting: home=${a.home} port=$bindPort enginePort=${a.enginePort} " +
              s"llmJudge=${PerceptionService.LlmJudgeEnabled} logRoot=${a.home}/logs/perception-daemon/logs"
          )
          // 五循环 + 判定 seam 全部经 PerceptionService.boot（出域腿注入；
          // boot 幂等，重复调用零行为）。
          _ <- PerceptionService.boot(
            broadcast = leg.broadcast,
            deliverEnvelope = leg.deliver,
            judge = PerceptionEngineLeg.judgeViaEngine(leg)
          )
          routes: HttpRoutes[IO] = PerceptionDaemonRoutes.routes(
            resolve = PerceptionService.coreNow,
            health = healthJson,
            token = token
          )
          // 职责B：自心跳 watchdog（30s 拍活性日志；五循环各自 attempt/递归
          // 自恢复，本拍是健康面的可观察活性证词）。
          _ <- watchdogLoop(logger, bindPort).start
          exit <- EmberServerBuilder
            .default[IO]
            .withHost(com.comcast.ip4s.Host.fromString("127.0.0.1").getOrElse(com.comcast.ip4s.Host.fromString("localhost").get))
            .withPort(com.comcast.ip4s.Port.fromInt(bindPort).get)
            .withHttpApp(Router("/" -> routes).orNotFound)
            .withShutdownTimeout(scala.concurrent.duration.FiniteDuration(5, scala.concurrent.duration.SECONDS))
            .build
            .use { server =>
              logger.info(
                s"perception unified daemon serving on 127.0.0.1:${server.address.getPort} " +
                  "(health=/health token-gated; managed by engine DaemonService — 随引擎起停)"
              ) *> IO.never
            }
            .as(ExitCode.Success)
        yield exit

  /** --port 0 = 自选空闲（绑定即释的固有占位窗，与引擎 dock pickPort 同级）。 */
  private def pickFreePort(): Int =
    val s = new java.net.ServerSocket()
    try
      s.setReuseAddress(true)
      s.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress, 0))
      s.getLocalPort
    finally s.close()

  /**
   * 自心跳 watchdog（30s 拍活性日志）。递归腿 `IO.defer` 包住——`*>` 右操作数
   * 是急切的方法调用，裸递归在构造期即 StackOverflowError（sweepLoop/spoolLoop
   * 同案，PerceptionCoreSpec 回归钉有案）。
   */
  private def watchdogLoop(logger: NebflowLogger, port: Int): IO[Unit] =
    IO.defer {
      IO.sleep(30.seconds) *>
        logger.info(s"perception-daemon heartbeat: port=$port loops=poll/sweep/digest/spool/idle alive=true") *>
        watchdogLoop(logger, port)
    }

  private def tokenOf(home: os.Path): Either[String, String] =
    val f = home / "perception" / "daemon.token"
    if !os.exists(f) then
      Left(s"token file missing: $f (engine writes it 0600 before spawn)")
    else
      val t = os.read(f).trim
      if t.isEmpty then Left(s"token file empty: $f") else Right(t)
