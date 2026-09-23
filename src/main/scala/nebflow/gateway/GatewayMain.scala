package nebflow.gateway

import cats.effect.*
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.*
import nebflow.bridge.*
import nebflow.core.*
import nebflow.core.daemon.{DaemonService, DaemonStore}
import nebflow.core.hooks.*
import nebflow.core.mcp.*
import nebflow.core.project.{ProjectRuntimeRegistry, ProjectStore}
import nebflow.core.scheduler.{ScheduledTaskService, ScheduledTaskStore}
import nebflow.core.seed.SeedService
import nebflow.core.skill.SkillService
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.*
import nebflow.llm.*
import nebflow.neblink.*
import nebflow.service.{ConfigSnapshot, *}
import nebflow.shared.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

import scala.concurrent.duration.*

object GatewayMain extends IOApp:
  private val logger = NebflowLogger.forName("nebflow.gateway")

  private def pidFilePath = java.nio.file.Paths.get(PathUtil.dataRoot.toString, "nebflow.pid")

  /**
   * Kill stale Nebflow processes before starting — but ONLY verified Nebflow
   * processes (P0 2026-09-06 host-kill hardening). Port-based detection
   * (lsof) plus a per-pid identity check (JDK ProcessHandle command line,
   * ps fallback): every listener's command line must carry a Nebflow marker
   * before it may be destroyed (see [[StaleProcessGuard]] — restart flows
   * still clear their stale instance).
   * A foreign occupant refuses startup loudly; it is NEVER killed.
   * Falls back to PID file only on Windows.
   */
  private def ensureSingleInstance(port: Int): IO[Unit] =
    IO.blocking {
      val os = sys.props.getOrElse("os.name", "").toLowerCase
      if os.contains("mac") || os.contains("linux") then
        StaleProcessGuard.classify(
          StaleProcessGuard.portListenerPids(port),
          ProcessHandle.current.pid
        ) match
          case Right(stalePids) =>
            if stalePids.nonEmpty then
              logger
                .warn(
                  s"[startup] killing verified stale nebflow processes on port $port: " +
                    s"${stalePids.mkString(", ")}"
                )
                .unsafeRunSync()
              for pid <- stalePids do
                try ProcessHandle.of(pid).ifPresent(_.destroyForcibly())
                catch case _: Exception => ()
              Thread.sleep(1000)
            writePidFile()
          case Left(foreign) =>
            // P0 2026-09-06 (09:07 incident class): a NON-nebflow process
            // holds the port. NEVER destroy it — refuse startup loudly; the
            // boot dies here instead of killing someone else's process.
            val detail =
              s"[startup] REFUSING to start: port $port is held by a non-nebflow " +
                s"process (${foreign.detail}) — stop it or pick another port " +
                "(--port / NEBFLOW_GATEWAY_PORT)"
            logger.error(detail).unsafeRunSync()
            throw new IllegalStateException(detail)
        end match
      else writePidFile()
      end if
    }

  /**
   * Write the current PID file (unchanged behavior of the pre-hardening tail
   * of ensureSingleInstance; reached only when startup is NOT refused).
   */
  private def writePidFile(): Unit =
    val pf = pidFilePath
    java.nio.file.Files.createDirectories(pf.getParent)
    java.nio.file.Files.write(
      pf,
      ProcessHandle.current.pid.toString.getBytes("UTF-8"),
      java.nio.file.StandardOpenOption.CREATE,
      java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
    )

  // ===== 热重启引导闸（hot-restart 批设计 §3.3）=====

  /**
   * Boot entry gate dispatch: normal boot = stale-intent hygiene + the standard
   * single-instance hardening; succeed boot (hot-restart successor) = [s1]
   * intent validation only — the port is STILL HELD by the old instance at this
   * point, so ensureSingleInstance must NOT run here (its verified-stale
   * classification would kill the very instance this successor is replacing:
   * 禁裸 kill 红线零弱化). The successor takes the port via succeedPortGate
   * below, after the old pid is confirmed dead.
   */
  private def entryGate(cfg: GatewayConfig): IO[Unit] =
    nebflow.core.hotrestart.SuccessorContext.get match
      case None =>
        // Normal boot: archive a stale (>10min) unarchived restart intent
        // (验收 12 — WARN + archive, never blocking), then standard hardening.
        nebflow.core.hotrestart.HotRestart.archiveStaleIntentIfNeeded *>
          ensureSingleInstance(cfg.port.value)
      case Some(ctx) =>
        // [s1] intent validation (R6: home/port/host/freshness). Failure =
        // loud refusal — the old instance is unaffected (still serving; its
        // C2 poll times out, it TERMs this process and clears draining).
        nebflow.core.hotrestart.SuccessorGate
          .validate(ctx, PathUtil.dataRoot, cfg.host.toString, cfg.port.value)
          .fold(
            err =>
              logger
                .error(s"[hot-restart] successor intent validation failed: $err — refusing to boot")
                .flatMap(_ => IO.raiseError(new IllegalStateException(s"hot-restart successor refused: $err"))),
            _ =>
              logger.info(
                s"[hot-restart] successor intent validated (old pid ${ctx.oldPid}, port ${ctx.port}, generation ${ctx.generation})"
              )
          )
    end match
  end entryGate

  /**
   * 热重启 succeed 门（[s3]+[s4]，插桩位：projectTtlScanner 与 Ember build 之间，
   * 设计 §3.3 文件级触点）：Zone A 引导完成的回执（intent phase=readyToBind 原子
   * 回写）+ 端口让渡等待——等旧 pid 死亡（≤90s，对「旧实例走完优雅链」的确认，
   * 非赌博 sleep）∧ connect 探测无活监听（TW socket 拒绝 connect = 可进场，Ember
   * 的 bind + 既有 65s fail-open 语义归下游）。旧 pid 死后端口仍有活监听 = 外来
   * 抢占者进场窗 → 回落 ensureSingleInstance 既有语义（verified stale 清除 /
   * foreign 大声拒绝——单实例红线零弱化）。等待总超时 → failure 记录 + 弃进场退出
   * （旧实例继续服务 / watchdog 人工兜底）。通过后本进程才写 pid 文件（旧实例的
   * removePid hook 已随其死亡跑完，无竞删窗口）。普通启动恒为 no-op。
   *
   * **批 2 G3 追加（门口探针端点的生产者侧）**：在写 readyToBind 回执**之前**起一个
   * loopback 临时端点（[[nebflow.core.hotrestart.ProbeEndpoint]]，与既有健康端点同载荷），
   * 把端口号写进回执的 `probePort`——使**旧实例**能在交接前**独立**观察「新版本能不能
   * 服务」（该窗口内共享端口仍属旧实例，直接探它等于探自己；见 `ProbeEndpoint` 的次序
   * 论证）。端点随交接结束即停（`use` 作用域）；**fail-open**：起不来只记 WARN + 不带
   * `probePort`（旧实例把第三/四档报 `unverified`），既有交接链零改变。
   */
  private def succeedPortGate(cfg: GatewayConfig, healthMonitor: nebflow.llm.ProviderHealthMonitor): IO[Unit] =
    nebflow.core.hotrestart.SuccessorContext.get match
      case None => IO.unit
      case Some(ctx) =>
        val pid = ProcessHandle.current.pid
        logger
          .info(
            s"[hot-restart] successor zone A done (pid $pid) — marking readyToBind, waiting for old pid ${ctx.oldPid} to hand over port ${cfg.port.value}"
          )
          .flatMap(_ =>
            // 门口探针端点（fail-open，交接结束即停）
            nebflow.core.hotrestart.ProbeEndpoint.start(healthMonitor).flatMap { probe =>
              val announce = probe.toOption.map(_.port)
              val announceWarn = probe.fold(
                err =>
                  logger.warn(
                    s"[hot-restart] door probe endpoint unavailable (health gate tiers 3/4 will report unverified): $err"
                  ),
                _ => IO.unit
              )
              announceWarn *>
                nebflow.core.hotrestart.SuccessorGate
                  .markPhase(ctx.intentPath, "readyToBind", probePort = announce)
                  .flatMap(_ =>
                    nebflow.core.hotrestart.SuccessorGate
                      .awaitHandover(
                        ctx.oldPid,
                        cfg.port.value,
                        pidAlive = p => IO.blocking(ProcessHandle.of(p).map(_.isAlive).orElse(false)),
                        liveListener = p => IO.blocking(nebflow.cli.SingleInstanceGuard.connectProbeAccepted(p))
                      )
                      .flatMap {
                        case Right(()) => IO.unit
                        case Left(err) if err == nebflow.core.hotrestart.SuccessorGate.ForeignOccupantSignal =>
                          logger
                            .warn(
                              s"[hot-restart] old pid ${ctx.oldPid} dead but port ${cfg.port.value} still served — foreign-preemption window; standard single-instance gate applies"
                            )
                            .flatMap(_ => ensureSingleInstance(cfg.port.value))
                        case Left(err) =>
                          nebflow.core.hotrestart.SuccessorGate.markFailed(ctx.intentPath, err).attempt.void *>
                            logger.error(
                              s"[hot-restart] port handover failed: $err — successor exiting (old instance unaffected)"
                            ) *>
                            IO.raiseError(new IllegalStateException(s"hot-restart port handover failed: $err"))
                      }
                  )
                  .guarantee(probe.toOption.map(_.stop).getOrElse(IO.unit))
                  .void
            }
          ) *>
          IO.blocking(writePidFile()) *>
          logger.info(s"[hot-restart] port handover confirmed — successor (pid $pid) owns pid file, binding now")
    end match
  end succeedPortGate

  private def openBrowser(url: String): IO[Unit] = IO.blocking {
    val os = sys.props.getOrElse("os.name", "").toLowerCase
    val cmd =
      if os.contains("mac") then Seq("open", url)
      else if os.contains("win") then Seq("rundll32", "url.dll,FileProtocolHandler", url)
      else Seq("xdg-open", url)
    try Runtime.getRuntime.exec(cmd.toArray)
    catch case _: Exception => ()
  }

  private val QuitCommands: Set[String] = Set("quit", "exit", "q")

  /**
   * Block until user types a quit command on stdin.
   * If stdin is closed (EOF — common under sbt run / non-interactive shells), wait
   * forever instead of exiting, so the server is only killed by SIGINT/SIGTERM.
   *
   * On Windows, StdIn.readLine() may not be interrupted by Thread.interrupt() from
   * Ctrl+C. Use a polling approach with a short sleep to allow cancellation.
   */
  private def waitForQuit: IO[Unit] =
    val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
    if isWindows then pollStdinLoop
    else
      IO.interruptible(Option(scala.io.StdIn.readLine())).flatMap {
        case None => IO.never // stdin closed — keep running until cancelled
        case Some(line) =>
          if QuitCommands.contains(line.trim.toLowerCase) then IO.unit
          else waitForQuit
      }

  /**
   * Windows-compatible stdin polling: non-blocking check + short sleep.
   * On Windows CI (background process with no stdin), System.in.available()
   * throws IOException — we catch it and fall back to a sleep loop so the
   * server keeps running until cancelled externally.
   */
  private def pollStdinLoop: IO[Unit] =
    IO.blocking {
      if System.in.available() > 0 then Option(scala.io.StdIn.readLine())
      else null
    }.handleErrorWith { _ =>
      // stdin unavailable (e.g. Windows background process) — sleep forever
      IO.never
    }.flatMap {
      case Some(line) =>
        if QuitCommands.contains(line.trim.toLowerCase) then IO.unit
        else pollStdinLoop
      case _ =>
        IO.sleep(300.millis) *> pollStdinLoop
    }

  /** Load MCP configs and start all servers on an existing McpManager. */
  private def startMcpServers(
    config: NebflowServiceConfig,
    manager: McpManager,
    agentLibrary: AgentLibrary
  ): IO[Unit] =
    val fromConfig = config.mcpServers.getOrElse(Map.empty)
    for
      _ <- agentLibrary.seedDefaults()
      _ <- agentLibrary.loadAll()
      _ <- logger.info("Initializing global MCP servers...")
      _ <- manager.startAll(fromConfig)
      _ <- startAgentMcpServers(manager)
      _ <- logger.info("MCP servers initialized")
      _ <- loadExternalTools()
    yield ()

  end startMcpServers

  /**
   * Start agent-scoped MCP servers (agent directory `tools/mcp/`, one `*.json`
   * per server) at startup. Each server is started independently; a failing
   * server is logged and skipped so one bad config can't block the rest of the
   * boot.
   */
  private def startAgentMcpServers(mcpManager: McpManager): IO[Unit] =
    for
      servers <- AgentMcpLoader.scanAll()
      _ <- servers.traverse_ { case (_, serverId, cfg) =>
        if cfg.isEnabled then
          mcpManager
            .startServer(serverId, cfg)
            .timeout(5.seconds)
            .handleErrorWith(e => logger.warn(s"Agent MCP server '$serverId' failed: ${e.getMessage}"))
        else logger.info(s"Agent MCP server '$serverId' is disabled, skipping")
      }
      _ <-
        if servers.nonEmpty then logger.info(s"Started ${servers.size} agent MCP server(s)")
        else IO.unit
    yield ()

  private def loadExternalTools(): IO[Unit] =
    for
      _ <- ToolLoader.reload()
      _ <- ToolLoader.startFileWatcher().start // background fiber — hot reload on file changes
    yield ()

  /**
   * Background heartbeat loop for NebLink server mode.
   * Every 30 seconds: send heartbeat, update peer list + trusted IPs.
   * Uses the discovery's current client (hot-swappable).
   * Returns the fiber so the caller can cancel/restart it.
   */
  private def startHeartbeatLoop(
    discovery: nebflow.neblink.NeblinkDiscovery
  ): IO[cats.effect.Fiber[IO, Throwable, Unit]] =
    def loop: IO[Unit] =
      for
        delay <- discovery.currentDelay
        _ <- IO.sleep(delay)
        _ <- discovery.heartbeatCycle
        _ <- IO.defer(loop)
      yield ()
    loop.start

  private lazy val defaultConfig: NebflowServiceConfig = NebflowServiceConfig(
    llm = ServiceLlmConfig(
      providers = Map.empty
      // #339：占位 llm.model default 已删（字段退役）——未配置时 registry 走
      // "首 provider 首模型"兜底
    )
  )

  /**
   * P0 2026-09-06 (09:07 host-kill incident): GatewayMain never parsed argv —
   * `java nebflow.gateway.GatewayMain --port 8097` silently DROPPED the flag,
   * the port fell back to the default 8080, and the startup port-clear then
   * hit the live host instance. Any argument now fails fast and loud;
   * GatewayMain is not the user entry point. Main.bootGateway boots the real
   * gateway via run(Nil).
   */
  def run(args: List[String]): IO[ExitCode] =
    args match
      case Nil =>
        // JDK baseline gate (2026-09-10, JDK-21 batch, B track): boot needs Java 21+.
        // Deliberately HERE, not in `Main.run`: the self-repair path
        // (`nebflow update|uninstall|doctor|version`) goes Main.run → CliRouter →
        // GatewayClient (plain HTTP) / the install script, and never boots the
        // gateway in-process — gating Main.run would brick the very command a
        // user on an old JVM needs to run. Main.bootGateway and
        // Main.succeedGateway both funnel through this Nil branch, so every real
        // gateway boot is still covered.
        if JvmRequirement.isSatisfied then runGateway.as(ExitCode.Success)
        else IO.println(JvmRequirement.errorText).as(ExitCode.Error)
      case _ =>
        IO.println(
          "ERROR: nebflow.gateway.GatewayMain does not accept arguments " +
            "(before 2026-09-06 they were silently ignored)."
        ) *>
          IO.println(
            "GatewayMain is not the user entry point. " +
              "Use: nebflow.Main --home <dir> --port <port> start"
          ) *>
          IO.pure(ExitCode.Error)
  end run

  private def runGateway: IO[Unit] =
    // Team #11 ④: log which Windows toolchain pieces (Git Bash / rg)
    // resolved at boot — missing pieces must be loud up front, not discovered
    // later inside a failing tool call. No-op off Windows.
    nebflow.core.WindowsDepProbe.warnIfMissing *>
      // Read port from config first, then run the boot entry gate on that port
      GatewayConfig.load.flatMap { cfg =>
        entryGate(cfg) *> GatewayConfig.load.flatMap { cfg =>
          // Expose resolved gateway port and PID to agent via system properties
          System.setProperty("nebflow.gateway.port", cfg.port.value.toString)
          System.setProperty("nebflow.gateway.pid", ProcessHandle.current.pid.toString)
          // Safe config load — never crash on bad config; auto-restore from snapshot on corruption
          val configRef: Ref[IO, NebflowServiceConfig] = Ref.unsafe {
            try
              val cfg = Config.loadServiceConfig()
              // Save snapshot on successful load (fire-and-forget)
              ConfigSnapshot.save().unsafeRunSync()
              cfg
            catch
              case e: Exception =>
                logger.warn(s"Config load failed: ${e.getMessage}")
                // Try restoring from latest snapshot
                ConfigSnapshot.restoreLatest().unsafeRunSync() match
                  case true =>
                    logger.info("Restored config from latest snapshot")
                    try Config.loadServiceConfig()
                    catch
                      case _: Exception =>
                        logger.warn("Restored snapshot also invalid — starting with defaults")
                        defaultConfig
                  case false =>
                    logger.warn("No snapshot available — starting with defaults")
                    defaultConfig
          }
          configRef.get.flatMap { config =>
            // #311 (2026-08-19): enforce the preset invariant at boot — a usable
            // default preset must always exist once any model is configured.
            // Creates/seeds model-presets.json when absent (from llm.model, i.e.
            // the user's first provider model) and repairs a dangling/chain-less
            // default. Idempotent; healthy files are untouched.
            try nebflow.core.presets.PresetStore().ensureDefaultPreset()
            catch case e: Exception => logger.warn(s"Default preset seeding failed: ${e.getMessage}")
              // #339 D-b：llm.model 一次性迁移（先播种验证后剥离，原子写；失败
              // 幂等重试）。种子源此时仍优先读 llm.model（迁移优先），剥离后新
              // 安装的种子源走 providers 推导。
            try nebflow.core.presets.PresetStore.migrateGlobalModelChain()
            catch case e: Exception => logger.warn(s"llm.model migration failed: ${e.getMessage}")
              // LLM 日志记录（2026-09-13「默认关」批 + D-A 持久化）：启动时以
              // nebflow.json 顶层 `llmLog.enabled` 落盘值为准；**无落盘值 ⇒ 保持
              // 默认关**（`LlmLogWriter` 初值 = false）。显式改动过的值由此跨重启
              // 保持（旧缺陷：内存开关不落盘，宿主重启静默回落 true）。
              // fail-safe：节缺失 / 非法 ⇒ None ⇒ 默认关（loadEnabled 纯解析）。
              // 生效读数落一行启动日志（infoSync：本块为语句序列，非 IO 组合）——
              // 供「重启后只读复验」直接 grep，无需触碰运行实例。
            locally {
              val persistedLlmLog = nebflow.core.LlmLogWriter.loadEnabled(config.llmLog)
              persistedLlmLog.foreach(nebflow.core.LlmLogWriter.setEnabled)
              // 回收腿武装（2026-09-13 回收解耦批）：保留窗执行**不再挂写入开关**
              // （关态下也必须淘汰出窗文件），改为绑「本实例已启动」——武装后
              // `logResponse` 的回收 tick 才会真正跑。默认**不武装**：非实例调用者
              // （spec / e2e 脚本在真实 dataRoot 上跑完整轮次）绝不能触发破坏性回收
              // （事故取证见 llmlogprune 批（内部留档））。
              nebflow.core.LlmLogWriter.armRetention()
              logger.infoSync(
                s"LLM log recording: ${if nebflow.core.LlmLogWriter.isEnabled then "enabled" else "disabled"} " +
                  s"(persisted=${persistedLlmLog.map(_.toString).getOrElse("none → default off")}) " +
                  s"| retention=armed (3-day window, independent of the write switch)"
              )
            }
            Auth.loadOrCreateToken.flatMap { token =>
              // Global session state shared across all connections
              val sessionStore = new SessionStore(PathUtil.dataRoot / "sessions", PathUtil.dataRoot / "tasks")
              val sessionModelOverrides: Ref[IO, Map[String, ModelCandidate]] = Ref.unsafe(Map.empty)
              sessionStore.load
                .flatMap { _ =>
                  // Single-session architecture: guarantee the primary agent Nebula
                  // has exactly one session and that it is the active session shown in
                  // the Main window, before any WS client connects. Adopts legacy
                  // agentless sessions (preserving history) and activates as needed.
                  // freshinstall-rootsessionid 批 M1：返回值**不再丢弃**——它就是
                  // 「开机补建」的产物，是启动挂载根的唯一真源（此前 startupMount
                  // 另起一次 listSessionsByAgent 现查 + `getOrElse("")`）。
                  sessionStore.ensureActiveAgentSession("Nebula")
                }
                .flatMap { bootRoot =>
                  // Flow-node supervision P3: llm.streamTimeouts watchdog overrides
                  // (boot-time; config changes take effect on restart).
                  configRef.get.flatMap { bootCfg =>
                    val st = bootCfg.llm.streamTimeouts.getOrElse(nebflow.llm.StreamTimeoutsConfig())
                    IO(LlmInterface.applyStreamTimeouts(st.firstTokenSec, st.inactivitySec, st.noProgressSec))
                  } *> LlmInterface.createLlm(sessionModelOverrides, configRef = Some(configRef)).flatMap {
                    case (handle, registry, healthMonitor, releaseBackend) =>
                      // Clear per-session model overrides on restart so all sessions
                      // follow the global fallback order from config.
                      sessionStore.clearAllSessionModels() *> McpManager.create.flatMap { mcpManager =>
                        // --- Fast path: only essential init before server start ---
                        val chatRoutes = new ChatRoutes(handle, token)
                        val isConfigured = config.llm.providers.nonEmpty
                        // #339 D-c：contextWindow 改读默认 preset 链首个能解析到
                        // provider 模型表的 ref（preferred 优先逐个试 fallbacks；
                        // 全失败回 Defaults.ContextWindow + warn）。横幅同时暴露
                        // preset 名 + 实际 ref，与设置页 preset 卡片逐字可对上。
                        val (contextWindow, presetLabel): (Int, Option[(String, String)]) =
                          if !isConfigured then (Defaults.ContextWindow, None)
                          else
                            try
                              val pFile = nebflow.core.presets.PresetStore().load()
                              val dp = pFile.presets
                                .getOrElse(pFile.defaultPreset, nebflow.core.presets.ModelPreset(pFile.defaultPreset))
                              val chain = dp.preferred.toList ++ dp.fallbacks
                              val resolved = chain.flatMap { ref =>
                                try
                                  val (providerId, modelId) = Config.parseModelRef(ref)
                                  config.llm.providers
                                    .get(providerId)
                                    .flatMap(_.models.find(_.id == modelId))
                                    .map(m => (m.contextWindow, ref))
                                catch case _: Exception => None
                              }
                              resolved.headOption match
                                case Some((cw, ref)) => (cw, Some((pFile.defaultPreset, ref)))
                                case None =>
                                  logger.warn(
                                    s"Default preset '${pFile.defaultPreset}' resolves to no provider model; " +
                                      s"contextWindow falls back to ${Defaults.ContextWindow}"
                                  )
                                  (Defaults.ContextWindow, Some((pFile.defaultPreset, dp.preferred.getOrElse(""))))
                            catch case _: Exception => (Defaults.ContextWindow, None)
                        val baseUrl = s"http://localhost:${cfg.port}"
                        val url = s"$baseUrl?token=$token"
                        sys.props.update("nebflow.url", baseUrl)

                        // Initialize thinking config from nebflow.json (default enabled=true)
                        val initialThinking = config.thinkingConfig.getOrElse(nebflow.llm.ThinkingConfig())
                        val thinkingConfigRef: Ref[IO, nebflow.llm.ThinkingConfig] = Ref.unsafe(initialThinking)
                        // 冻结调度（freeze-schedule，#337 黑名单语义）：从 nebflow.json
                        // workSchedule 节（JSON 键名保留，语义=冻结时段）fail-safe 加载
                        // （非法配置视为关闭——恒不冻结，功能旁路）。
                        val initialFreezeSchedule =
                          nebflow.core.schedule.FreezeSchedule.load(config.workSchedule)
                        val freezeScheduleRef: Ref[IO, nebflow.core.schedule.FreezeScheduleConfig] =
                          Ref.unsafe(initialFreezeSchedule)
                        // 工具结果 TTL 清理（#341）：fail-safe 加载（非法配置
                        // 视为关闭——默认关，request-only 清理）。Ref 化（镜像
                        // freezeScheduleRef）——setToolResultTtl WS 热更。
                        val toolResultTtlCfg =
                          nebflow.core.compact.ToolResultTtlConfig.load(config.toolResultTtl)
                        val toolResultTtlRef: Ref[IO, nebflow.core.compact.ToolResultTtlConfig] =
                          Ref.unsafe(toolResultTtlCfg)
                        // C2-6（/api/nf-file 断凭据腿批）：按 path 短时票据存储。
                        // TTL = nebflow.json 的 nfFile.ticketTtlSeconds（R3，默认
                        // 1800s）；issue 路径按 mtime 节流重读，改配置无需重启。
                        // 策略对象（凭据命名空间 + R2 启动 inode 快照）一并在此
                        // 构造并注入，使读端点与签发端点共用同一份权威判据。
                        val nfTicketStore: NfTicketStore =
                          NfTicketStore.unsafeCreate(NfTicketStore.loadTtlSeconds())
                        val nfPathPolicy: WebSocketRoutes.NfPathPolicy =
                          WebSocketRoutes.NfPathPolicy.standard()
                        // 执行环境 provider（拆围栏批 S3 / design §4.2）：fail-safe
                        // 加载 + 按 provider 装配执行面一次缓存（provider=host 缺省
                        // = 宿主直跑不 probe；local-process 才 probe，阻塞 <1s；取值
                        // 非法或 container/auto 未实现 ⇒ 显式失败，不静默回落宿主）。
                        val sandboxCfg = nebflow.core.sandbox.SandboxConfig.load(config.sandbox)
                        nebflow.core.sandbox.SandboxRuntime.init(sandboxCfg)
                        logger.info(s"nebflow ${nebflow.Version.string}") *>
                          (if !isConfigured then logger.info("No LLM provider configured — open the web UI to set up")
                           else
                             presetLabel match
                               case Some((name, ref)) =>
                                 logger.info(s"Context window: $contextWindow tokens (default preset \"$name\": $ref)")
                               case None =>
                                 logger.info(s"Context window: $contextWindow tokens")) *>
                          RateLimiter.create().flatMap { rateLimiter =>
                            FileChangeTracker.create(System.getProperty("user.dir")).flatMap { fileTracker =>
                              // Create Dispatcher for the multi-agent runtime, then start server
                              cats.effect.std.Dispatcher.parallel[IO].use { dispatcher =>
                                val agentLibrary = new AgentLibrary(AgentLibrary.defaultDir, Some(config))
                                nebflow.core.tools.FileLockManager.create.flatMap { fileLockMgr =>
                                  val hooksConfig = HooksConfigLoader.load(os.pwd)
                                  val hookEngine = HookEngine(hooksConfig)
                                  val actorSystem = nebflow.actor.ActorSystem("local")
                                  val voiceMutedRef: Ref[IO, Boolean] = Ref.unsafe(false)
                                  val sharedResources = SharedResources(
                                    llm = handle,
                                    dispatcher = dispatcher,
                                    sessionStore = sessionStore,
                                    projectRoot = os.pwd,
                                    thinkingConfigRef = thinkingConfigRef,
                                    rateLimiter = rateLimiter,
                                    fileChangeTracker = fileTracker,
                                    contextWindow = contextWindow,
                                    agentLibrary = agentLibrary,
                                    taskStore = FileTaskStore,
                                    // 压缩报告落盘（2026-09-02 迁移）：sessions/<sessionId>/compaction/
                                    // （按会话归组，对齐 CompactionQueueStore 规范）——不再写项目目录 archives/
                                    historyArchiver =
                                      nebflow.core.compact.HistoryArchiver.fileSystem(PathUtil.dataRoot / "sessions"),
                                    fileLockManager = fileLockMgr,
                                    sessionModelOverrides = sessionModelOverrides,
                                    providerRegistry = registry,
                                    healthMonitor = healthMonitor,
                                    actorSystem = actorSystem,
                                    hookEngine = hookEngine,
                                    voiceMutedRef = voiceMutedRef,
                                    freezeScheduleRef = freezeScheduleRef,
                                    toolResultTtlRef = toolResultTtlRef,
                                    bashResilience = nebflow.shared.BashResilienceConfig(
                                      hardTimeoutMs = config.bashBackgroundHardTimeoutMs
                                        .getOrElse(nebflow.shared.Defaults.BashBackgroundHardTimeoutMs),
                                      stuckWindowSec =
                                        config.bashStuckWindowSec.getOrElse(nebflow.shared.Defaults.BashStuckWindowSec),
                                      healthCheckIntervalSec = config.bashHealthCheckIntervalSec
                                        .getOrElse(nebflow.shared.Defaults.BgHealthCheckIntervalSec)
                                    ),
                                    sandboxConfig = sandboxCfg
                                  )
                                  // P2: spawn the global InteractionHub and publish its ref.
                                  // Every agent's permission/AskUser requests and every frontend
                                  // interaction answer route through this single actor.
                                  val hubSetup: IO[Unit] =
                                    actorSystem.spawn(nebflow.agent.InteractionHub(), "interaction-hub").flatMap {
                                      hubRef =>
                                        sharedResources.interactionHubRef.set(Some(hubRef))
                                    }
                                  // 任务 TTL 启动扫描（任务工具重做 2026-08-30）：扫
                                  // tasks/ 全目录（session+team）物理删除过期任务——
                                  // 判据=磁盘持久化的 createdAt/completedAt，重启后
                                  // 仍生效。Best-effort，永不阻塞启动。
                                  val taskTtlSweep: IO[Unit] =
                                    FileTaskStore.purgeAllExpired().attempt.flatMap {
                                      case Right(_) => IO.unit
                                      case Left(e) =>
                                        logger.warn(s"Task TTL sweep failed: ${e.getMessage}").void
                                    }
                                  // V2 (2026-09-03) 孤儿 delegate 启动收殓：崩溃时在飞的
                                  // SubTask/Delegate 任务——部分结果抢救 + 父会话 F2 队列
                                  // 通知（correlationId 幂等）+ 任务记录终态化。挂在启动
                                  // 装配点（任何会话 actor spawn 之前 → 队列文件单写者），
                                  // 多次重启幂等（终态化后 findRunningTasks 不再命中）。
                                  // Best-effort：永不阻塞启动。
                                  val subagentCrashSweep: IO[Unit] =
                                    nebflow.agent.SubAgentStartupRecovery
                                      .recoverOrphans(sharedResources.subAgentTaskStore, sessionStore)
                                      .attempt
                                      .flatMap {
                                        case Right(ids) if ids.nonEmpty =>
                                          logger
                                            .info(s"Subagent crash sweep: ${ids.size} orphan task(s) recovered")
                                            .void
                                        case _ => IO.unit
                                      }
                                  // wsHub 提前到 startupMount 之前创建：启动挂载的项目
                                  // engine 需要持有一个真实广播 wsSend（节点/分发器 agent
                                  // 事件 + nodeUpdated/nodeCompleted 等 Flow Map 事件）。
                                  // 此前 wsHub 在挂载后才构造 → mountAll 传 None → engine
                                  // wsSendFn 为 no-op，节点/分发器事件永远到不了前端
                                  // （#28 可观测缺口根因之一）。wsHub 本身无依赖，提前
                                  // 构造零副作用（无连接时 broadcast 空转）。
                                  val wsHub = new WsHub()
                                  // #37 启动自动挂载（0b 契约「阶段 1 补自动挂载」）：磁盘已有
                                  // 项目 → 幂等挂载（rootSessionId = 顶层 Nebula 主会话 id，
                                  // 启动期无会话上下文直接传顶层根）。免重启后人工重挂——
                                  // 与 ProjectCreate 幂等挂载（运行时主动路径）互补。
                                  // crash-recovery 批（2026-09-07）：恢复开启时启动挂载跳过
                                  // 挂载期僵尸收殓（cancelled）——崩溃残留 running 节点留给
                                  // 紧随其后的 projectCrashSweep 认领（rehydrate/failed）；
                                  // 恢复关闭时保持既有收殓行为（回滚语义=回到现状）。
                                  // freshinstall-rootsessionid 批 M1-b（作者 09-14 裁定 (b)：
                                  // **跳过挂载、继续启动**）：挂载根 = 上一步「开机补建」的
                                  // 产物 `bootRoot`（唯一真源），不再现查索引 + `getOrElse("")`。
                                  // 空 ⇒ 跳过本次 mountAll + ERROR + 事件（命名根因 = **开机
                                  // 补建未产出会话**），🔴 绝不把空串当 rootSessionId 挂载。
                                  val startupMount: IO[Unit] =
                                    if bootRoot.id.isEmpty then
                                      logger.error(
                                        "Startup mount SKIPPED: 开机补建未产出会话 " +
                                          "(boot backfill ensureActiveAgentSession(\"Nebula\") returned an EMPTY " +
                                          "session id) — no rootSessionId is available to attribute startup-mounted " +
                                          "projects; refusing to mount with an empty rootSessionId (projects stay " +
                                          "unmounted until a restart whose boot backfill yields a real session)"
                                      ) *> wsHub.broadcast(
                                        io.circe.Json.obj(
                                          "type" -> "startupMountSkipped".asJson,
                                          "reason" -> "boot-backfill-produced-no-session".asJson,
                                          "detail" -> ("startup mount skipped: 开机补建未产出会话" +
                                            "（ensureActiveAgentSession(\"Nebula\") 的返回 id 为空）；" +
                                            "已跳过挂载——禁以空串 rootSessionId 挂载").asJson
                                        )
                                      )
                                    else
                                      for
                                        projects <- ProjectStore.list()
                                        mounted <- ProjectRuntimeRegistry.mountAll(
                                          projects,
                                          bootRoot.id,
                                          actorSystem,
                                          sharedResources,
                                          // #28 可观测接线：启动挂载传入真实广播 wsSend——
                                          // engine 的节点/分发器事件经 wsHub 到达前端 subagent
                                          // 面板（此前 None → no-op，事件静默丢失）。
                                          Some((json: io.circe.Json) => wsHub.broadcast(json)),
                                          skipStaleReap = nebflow.shared.Defaults.CrashRecoveryEnabled
                                        )
                                        _ <-
                                          if mounted > 0 then
                                            logger.info(
                                              s"Startup mount: $mounted project(s) mounted (rootSessionId=${bootRoot.id})"
                                            )
                                          else IO.unit
                                      yield ()
                                  // boot-time 崩溃断点恢复（crash-recovery 批 2026-09-07）：
                                  // 挂载后、TtlTick 首拍前同步跑快段（分类+认领，秒级——被认领
                                  // 节点翻 Pending 即刻脱离 watchdog 判死口径，竞速由挂载顺序
                                  // 结构性关闭），慢段（rehydrate 续跑，分钟级）在 sweep 内
                                  // fork 不阻塞 listen。恢复关闭（crashRecovery.enabled=false）
                                  // = 本步空转 + 上面 mountAll 已走既有僵尸收殓——完全回到
                                  // 本批前现状。sweep 内部逐项目/逐节点 handleError，异常不
                                  // 阻塞 boot（残余 Running 由 watchdog +30s 兜底收敛 failed）。
                                  val projectCrashSweep: IO[Unit] =
                                    if nebflow.shared.Defaults.CrashRecoveryEnabled then
                                      nebflow.core.project.ProjectCrashRecovery.recoverAll().flatMap { projects =>
                                        if projects > 0 then
                                          logger.info(
                                            s"Boot crash-recovery sweep: $projects project(s) had crashed nodes claimed (rehydrate/failed)"
                                          )
                                        else IO.unit
                                      }
                                    else IO.unit
                                  // 宿主启动自动重入（boot-wake 批 2026-09-13，方案件 A 档 A1；
                                  // 作者 09-13 裁定「重启后唤醒源 = 直接上 A」）：**独立于
                                  // crash-recovery** 的第二条 boot 扫描腿——crash-recovery 只认
                                  // `status == Running` 残留，故「零 running 但有未完成工作」的项目
                                  // 重启后没有任何东西叫醒控制面（分发器不是节点、不在任何扫描面），
                                  // 停摆窗无上界（实测 89.34s / 150.93s，且靠一封无关 Mail 偶然结束）。
                                  // 本腿对每个在册项目读**落盘事实**（flow-map.json + tasks/ +
                                  // results/ + transcript 存在性）派生需重入清单，非空则经**既有通道**
                                  // DispatchNotify.defaultTrigger 发一条 TriggerDispatcher（文本 =
                                  // 清单正文）。零新调度器（boot 单次执行）、零节点写（不重激活
                                  // blocked / 不自动清 pendingSuccession / 不重投任何边）、零重试
                                  // （单次尝试，失败只记录）。触发面 = 仅本 boot 链（每进程恰一次、
                                  // 早于 server listen）；普通会话启动/工具调用/TtlTick 扫描腿均无
                                  // 本入口。开关 nebflow.boot.dispatcherWake（默认 true）——false 时
                                  // 本步空转，完全回到本批前现状。
                                  val projectBootWake: IO[Unit] =
                                    if nebflow.shared.Defaults.BootDispatcherWakeEnabled then
                                      nebflow.core.project.BootDispatcherWake.wakeAll().flatMap { r =>
                                        // 无条件汇总行（含零命中）：使「零唤醒 boot」也可审计——
                                        // 本批前该形态在日志里零痕迹，缺陷只能靠人肉发现。
                                        logger.info(s"Boot dispatcher wake: ${r.summary}")
                                      }
                                    else IO.unit
                                  // #28 阶段 0：Project Flow Map **completed** 节点 TTL 扫描
                                  // （24h 显示消失 → 移归档 + WS nodeRemoved；failed/cancelled
                                  // 无 TTL 不过期——2026-09-07 裁定，死亡现场保留待上层裁决；
                                  // Node 运行本身不设超时）。周期给所有已挂载 ProjectActor
                                  // 发 TtlTick；无项目时空转。
                                  val projectTtlScanner: IO[Unit] =
                                    nebflow.core.project.ProjectActor.ttlScanner(30.seconds).start.void
                                  // 宿主睡眠/唤醒感知（hostresume 批 2026-09-22，设计卡 §4 #3；
                                  // D-2 默认 true + kill-switch：`nebflow.wake.sense.enabled=false`
                                  // ⇒ launch 即 IO.unit，不挂载 = 逐字节现状）。15s 双钟断流纤维
                                  // （ttlScanner 同款形态）——判出的睡眠窗进 PowerStateTracker 窗集
                                  // （TaskStuckWatcher 两轴与整流 no-progress 守卫的时间基修正消费，
                                  // D-6 首批仅此两处）+ boot-wake.json 台账 append（kind=sleep/wake，
                                  // 恒 blocking=false）+ host-wake 审计事件（D-7：仅审计，不揽分发
                                  // 器）。server listen 前就绪；零新调度器、零子进程（A1 纯 JVM）。
                                  val hostWakeSensor: IO[Unit] = nebflow.core.project.WakeSensor.launch
                                  // 冷启动播种（cold-start seed 批 2026-09-07）：fresh home
                                  // 在 startupMount 前就绪默认最小集（project-dispatcher /
                                  // general / memory-consolidator agents + 4 系统插件 +
                                  // projects/general）——通用项目需于挂载前存在，干净 home
                                  // 启动即自动挂载、Mail(address="project:general") 直达分发器
                                  // （作者 2026-09-17 裁定①：撤销 09-16「移除内置 general
                                  // 项目」令；裁定②：既有 home 亦 add-only 补种——缺目录才建、绝不改既有内容）。
                                  // 幂等 + fail-soft（见 SeedService
                                  // 注释），失败仅告警不阻塞启动（与 seedDefaults/startupMount 同构）。
                                  val seedMinimalSet: IO[Unit] = SeedService.ensureSeeded()
                                  // 热重启编排器（hot-restart 批）：触发器无关——WS restart
                                  // 命令（P1）经 sharedResources.hotRestart 触发；REST/桌面菜单
                                  // 后续批次接同一入口。broadcast 接 wsHub（restartStatus 帧）。
                                  val hotRestart = new nebflow.core.hotrestart.HotRestart(
                                    sharedResources,
                                    cfg.port.value,
                                    cfg.host.toString,
                                    wsHub.broadcast,
                                    // 端口探测能力：复用既有 `SingleInstanceGuard.connectProbeAccepted`
                                    // （既有调用点即本文件的 succeedPortGate）。core 层不得反向
                                    // 依赖 cli ⇒ 在**构造点**注入（hotupdate 批 2 G3 第三档）。
                                    connectProbe =
                                      Some(p => IO.blocking(nebflow.cli.SingleInstanceGuard.connectProbeAccepted(p))),
                                    // 安装目录（版本留存 + 指针）：生产装配显式注入（spec 传临时
                                    // 目录 ⇒ 指针动作旁路，绝不动真实安装）
                                    installDir = Some(nebflow.core.hotrestart.InstallPointer.defaultInstallDir())
                                  )
                                  // 统一更新编排器（hotupdate 批 1，设计 §4 设计 A）：全部入口
                                  // 只发「更新请求」，由它独占执行；重启相位**一律委托**上面的
                                  // 热重启编排器（排空/翻态/派生/握手零复制）；统一进度帧经**同一
                                  // 广播通道** wsHub.broadcast（复用点 #11，构造点即本处）。
                                  val updateOrchestrator = new nebflow.core.hotupdate.UpdateOrchestrator(
                                    Some(hotRestart),
                                    wsHub.broadcast
                                  )
                                  val sharedResourcesWithRestart =
                                    sharedResources.copy(
                                      hotRestart = Some(hotRestart),
                                      updateOrchestrator = Some(updateOrchestrator)
                                    )
                                  hubSetup *> taskTtlSweep *> subagentCrashSweep *> seedMinimalSet *> startupMount *> projectCrashSweep *> projectBootWake *> projectTtlScanner *> hostWakeSensor *> succeedPortGate(
                                    cfg,
                                    sharedResources.healthMonitor
                                  ) *> {
                                    val sharedResourcesLive = sharedResourcesWithRestart
                                    // 2026-09-13（permshield S1）：`SessionService` 不再需要
                                    // "档位覆盖快照"入参——档位只有应用级全局持久一源
                                    // （`GlobalSafety.defaultMode`），两个列表出口
                                    // （`sessionList` / `agentSessionList`）天然同源，
                                    // 2026-09-12 修复轮 D1 的"漏注入 ⇒ 同连接两个读数"
                                    // 失败类在类型上已不可能（无投入可漏）。
                                    val sessionService = new SessionService(sessionStore)
                                    val agentService = new AgentService(agentLibrary)
                                    val configService = ConfigService

                                    // --- Bridge Manager (plugins: telegram, etc.) ---
                                    val bridgeInjectRef: Ref[IO, Option[(String, String, Option[String]) => IO[Unit]]] =
                                      Ref.unsafe(None)

                                    // Holder for wsRoutes so we can wire bridge refs after safe construction
                                    var wsRoutesHolder: Option[WebSocketRoutes] = None

                                    val bridgeCtx = new BridgeContext:
                                      def injectMessage(sessionId: String, content: String, senderId: Option[String])
                                        : IO[Unit] =
                                        bridgeInjectRef.get.flatMap(_.fold(IO.unit)(_(sessionId, content, senderId)))
                                      def interruptAgent(sessionId: String): IO[Unit] =
                                        wsRoutesHolder match
                                          case Some(routes) =>
                                            routes.handleBridgeAgentCommand(sessionId, AgentCommand.Interrupt())
                                          case None => IO.unit
                                      def sessionMeta(sessionId: String): IO[Option[SessionMeta]] =
                                        sessionStore.getSessionMeta(sessionId)
                                      def listSessions: IO[List[SessionMeta]] =
                                        sessionStore.listSessions
                                      def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json])
                                        : IO[Unit] =
                                        sessionStore.updateSessionBridge(sessionId, platform, config)

                                    val bridgeSetup: IO[BridgeManager] =
                                      BridgeManager.create(bridgeCtx)

                                    // Create neblink service (device discovery only, no file sync)
                                    val neblinkServiceF: IO[NeblinkService] =
                                      NeblinkService.create(cfg.port.value, dispatcher)

                                    bridgeSetup.flatMap { bridgeManager =>
                                      neblinkServiceF.flatMap { neblinkService =>
                                        // Presence WS service — maintains real-time online/offline via persistent WebSocket connections
                                        val presenceService =
                                          new nebflow.neblink.NeblinkPresenceService(neblinkService, cfg.port.value)(
                                            dispatcher
                                          )
                                        // Late-bound discovery holder for the silent re-login hook: the
                                        // startup client is created BEFORE NeblinkDiscovery (below) —
                                        // the hook resolves the hot-swap target at call time.
                                        val neblinkDiscoveryHolder =
                                          new java.util.concurrent.atomic.AtomicReference[Option[
                                            nebflow.neblink.NeblinkDiscovery
                                          ]](None)
                                        // Check if NebLink Server is configured; if so, create client for NebLink-based discovery
                                        val neblinkConfigAtBoot = neblinkService.neblinkConfig.unsafeRunSync()
                                        // 隔离护栏（2026-09-11 作者裁定，见 EnrollGuard）：非默认 home
                                        // 的实例（--home / <PREFIX>_HOME）默认**不**自动向生产网注册。
                                        // 2026-09-14 订正（踢旧批 ③，**纯注释，零行为改动**）：原文写
                                        // 「同账号重复登录会踢掉作者主客户端」与实现不符——自
                                        // 2026-09-11 起 deviceId = UUIDv5(机器码|scope)，非默认 home 的
                                        // scope = dataRoot 路径 ⇒ 派生**不同** id（NeblinkModel.scala
                                        // 的 deviceIdScope / deriveDeviceId），而服务端踢旧维度是
                                        // (device_id, network_id) ⇒ 隔离实例登录**不会**踢主客户端
                                        // （该表述是随机 deviceId 时代的残留，取证见
                                        // .nebflow/reports/20260914_170224_kickold-forensics__chain-n-07d47ec4.md
                                        // §2.5 不一致②）。
                                        // 闸的真实理由 = 别让一次性实例在生产设备表里**新增**设备行
                                        // （2026-09-11 /tmp/nebflow-coldstart 污染即此类）。
                                        // NEBFLOW_ALLOW_PROD_ENROLL=1 显式放行；默认 home 零行为变化；
                                        // 本地/自定义 host 不受影响。显式用户登录的放行面见
                                        // EnrollGuard 的 explicitUserAction（案 C）。
                                        val bootEnrollRefusal: Option[String] =
                                          neblinkConfigAtBoot.neblinkServer
                                            .flatMap(s => nebflow.neblink.EnrollGuard.enrollRefusal(s.url))
                                        val neblinkClient: Option[nebflow.neblink.NeblinkClient] =
                                          neblinkConfigAtBoot match
                                            case nc if nc.neblinkServer.isDefined && bootEnrollRefusal.isEmpty =>
                                              Some(
                                                new nebflow.neblink.NeblinkClient(
                                                  nc.neblinkServer.get,
                                                  cfg.port.value,
                                                  onDeviceTokenRejected = Some(
                                                    nebflow.neblink.LogtoSilentRelogin.make(
                                                      neblinkService,
                                                      IO(neblinkDiscoveryHolder.get),
                                                      cfg.port.value,
                                                      // 2026-09-11（复核 N1）：原为 IO.pure(...unsafeRunSync()...) ——
                                                      // IO.pure 的实参严格求值 ⇒ 该 unsafeRunSync 在 boot 执行，
                                                      // 启动客户端的 silent-relogin server URL 被冻结在 boot 值。
                                                      // 改为 live 读（同 RestApiRoutes.neblinkServerUrl 先例）。
                                                      //
                                                      // 案 b①（2026-09-20）：末级回落经**同一判据**
                                                      // （EnrollGuard.prodDefaultTarget —— 单点，禁第二实现）：
                                                      // 隔离数据根 + 无显式开关 ⇒ 无目标 ⇒ 本接缝降级为
                                                      // login-required（不注册），与两侧入口同语。
                                                      neblinkService.neblinkConfig.map(
                                                        _.neblinkServer
                                                          .map(_.url)
                                                          .orElse(
                                                            nebflow.neblink.EnrollGuard.prodDefaultTarget
                                                          )
                                                      )
                                                    )
                                                  ),
                                                  // F2 (2026-09-10 friend-search batch): identity source
                                                  // for the API-level 401/403 session self-heal.
                                                  identity = Some(neblinkService.identity),
                                                  // 2026-09-14（踢旧批 r2）：自动登录的停摆门
                                                  // （真值源 = NeblinkService.kickParked，写侧 =
                                                  // 隧道的 `disconnect` 帧，清侧 = 显式用户登录）。
                                                  autoLoginParked = IO(neblinkService.kickParked)
                                                )
                                              )
                                            case nc if nc.neblinkServer.isDefined =>
                                              // 命中隔离护栏：不建 boot 客户端 ⇒ 不自动登录/入网。
                                              // 不做静默降级——拒绝理由在下方 IO 链上打 WARN。
                                              None
                                            case _ => None
                                        // Wire relay client + presence service into NeblinkService so
                                        // DropboxService / RemoteExecutor / status endpoint can use them.
                                        neblinkService.setRelayClient(neblinkClient)
                                        neblinkService.setPresenceService(presenceService)
                                        // Register remote executor for cross-device tool dispatch (P2P + relay fallback)
                                        RemoteExecutor.initialize(neblinkService, dispatcher, neblinkClient)
                                        // F1 (2026-09-10 friend-search incident): discovery is the
                                        // AUTHORITATIVE live-client holder (clientRef, swapped by
                                        // enrollment hot-swap) — construct it BEFORE every consumer and
                                        // hand out reads of it, so all components share one client
                                        // instance. Previously FriendService + relay tunnel captured the
                                        // constructor-time client: after a UI re-login/账号切换 the
                                        // server's one-live-session policy kicked that client's session
                                        // and FriendService 403'd until process restart (search → 502).
                                        val tsDiscovery = new nebflow.neblink.NeblinkDiscovery(
                                          neblinkService,
                                          cfg.port.value,
                                          presenceService,
                                          neblinkClient
                                        )
                                        neblinkDiscoveryHolder.set(Some(tsDiscovery))
                                        // A2A 一期：FriendService 基于 NeblinkClient（好友/消息 REST +
                                        // 事件去重/限速/未读 cursor）。WS 事件回调广播 friendEvent 给前端
                                        // （messages.js 监听）；agentMessaging 配置来自 neblinkConfig。
                                        // F1: FriendService 每次调用读 discovery.currentClient（权威
                                        // live client），hot-swap 后自动跟随新实例。
                                        // 2026-09-11 boot 快照修复（A 案）：好友域的**存在性**不再由
                                        // boot 期的 client 快照决定（此前整段落在
                                        // `neblinkClient.map { … }` 内 ⇒ 全新 home 恒 None ⇒ 16 个
                                        // /api/friends* 站点 404 到进程重启；判决书定论 ③）。用户走
                                        // device-flow/AC+PKCE 登录只 hot-swap client
                                        // （NeblinkEnrollment.persist），故装配必须无条件；「未登录」
                                        // 由 FriendService.withClient → Left("Not logged in") 表达，
                                        // 不靠「服务不存在」表达。
                                        val amConfig = neblinkService.neblinkConfig.unsafeRunSync().agentMessaging
                                        // ⑦（2026-09-12）：本地好友备注（<dataRoot>/friend-remarks.json）
                                        // 启动读一次 → SharedResources 里那个 friendService 的内存 Ref。
                                        // 与 PeerDescriptionStore 同族：先例 NeblinkService.createInternal
                                        // 的 `PeerDescriptionStore.load → Ref.of`；此处调用点同 amConfig
                                        // 字节相邻的 boot 读形态（都在 IO 链的装配段内）。
                                        // 读失败（文件损坏 / 权限 / 半写）⇒ 折成空 map + WARN：
                                        // 备注是**可丢弃的本地态**（最坏情况 = 用户重设一次），绝不
                                        // 允许它把整条 boot 装配链掀翻（GatewayMain 这段是 val 求值，
                                        // 未捕获的 IO 失败 = 启动崩）。
                                        val friendRemarks =
                                          nebflow.neblink.FriendRemarkStore.load
                                            .handleErrorWith { e =>
                                              logger.warn(
                                                s"friend-remarks load failed (falling back to empty): ${e.getMessage}"
                                              ) *> IO.pure(Map.empty[String, String])
                                            }
                                            .unsafeRunSync()
                                        val friendService = nebflow.neblink.NeblinkWiring.friendService(
                                          tsDiscovery.currentClient,
                                          amConfig,
                                          // #147 接线段（2026-09-12）：ask 档确认链的装配缝接线。
                                          // 此前该参数不存在 ⇒ FriendService.askConfirm 恒 None
                                          // ⇒ ask 档一调即失败（作者 2026-09-11 裁定 U-5 的事实锚）。
                                          // 接的值就是**生产运行时真正执行的那个实现**
                                          // （nebflow.agent.SendConfirm.production）——不是桩、
                                          // 没有「接了但走不到」的第二条实现：它发 AskUser 确认请求、
                                          // 收确认/拒绝、据此决定投递与否。
                                          // 会话靶（谁在问）不由此处决定：本缝在 boot 期、不知道任何
                                          // 会话，而确认卡必须渲染在提问会话的窗口（前端 askUser 帧按
                                          // sessionId 找 view、无 session 即丢弃）。靶由唯一持
                                          // ToolContext 的调用侧（SendMessage 工具）按次经
                                          // SendConfirm.locally 挂进 fiber-local；无靶（REST 直调 /
                                          // harness）⇒ production 显式 fail-closed（不发送、不静默批准）。
                                          // 理由与代码锚见 nebflow.agent.SendConfirm 文件头。
                                          askConfirm = Some(nebflow.agent.SendConfirm.production),
                                          onFriendEvent = Some { ev =>
                                            // Frontend contract (messages.js onMessage('friend_event')):
                                            // frame type is "friend_event"; event type in msg.event;
                                            // payload fields (conversationId/messageId/body/...) flattened
                                            // onto the frame.
                                            // 波3 ①opt-A2：展平逻辑收归 `FriendEvent.frontendFrame`
                                            // （唯一实现）——修前直接展平 ev.payload，而它比注释假设
                                            // 的「字段袋」高一层（真值在 ev.payload.payload），
                                            // 前端因此恒读不到 conversationId（L3）。纯函数实现
                                            // 使帧形状可单测；此处只做广播。
                                            wsHub.broadcast(nebflow.neblink.FriendEvent.frontendFrame(ev))
                                          },
                                          remarks = friendRemarks,
                                          // D-B（2026-09-13）：送达确证（ack）生产者接线。
                                          // 隧道对象在本行**之后**才创建（且 logout/ensure 会
                                          // 反复重连），故此处按**每次调用 live 读**解析
                                          // （`neblinkService.relayTunnelOpt`），不捕获快照
                                          // —— 与 `setRelayTunnelStarter` / `ensureRelayTunnel`
                                          // 的既有 live 读口径一致。
                                          //
                                          // 🔴 F4（2026-09-18 回执诚实性批，作者裁示「回执诚实性
                                          // 修、单列小批」）：修前这里是
                                          // `case None => IO.unit` —— 把「隧道对象压根不在册」
                                          // **吞成成功**（与 `sendAck` 的「无 socket 即成功」
                                          // 同款假陈述，两条腿合起来使调用方无从判别）。现在
                                          // 统一走 `NeblinkRelayTunnel.sendAckLive`（**唯一**
                                          // 实现点，设备邮件腿同址复用），结局 = 可判别的
                                          // `AckOutcome`（`NoLiveSocket` 如实上报、绝不读成
                                          // 已发出）。帧编码与语义边界见
                                          // `NeblinkRelayTunnel.sendAck` 与
                                          // `FriendService.ackProcessed`。
                                          ackSender = Some { eventId =>
                                            nebflow.neblink.NeblinkRelayTunnel.sendAckLive(
                                              neblinkService.relayTunnelOpt,
                                              eventId
                                            )
                                          }
                                        )
                                        // A2A 一期（#290 域 A）：SendMessage 工具接线——
                                        // 授权仅 Nebula agent.json 声明（作者特批 2026-08-28），
                                        // 服务依赖走 RemoteExecutor.initialize 同款单例模式。
                                        // 2026-09-11：无条件 initialize（未登录由 sendAsAgent →
                                        // Left("Not logged in") 表达；工具侧 `service match None`
                                        // 兜底保留）。
                                        FriendMessageTool.initialize(friendService)
                                        // relay tunnel 装配（2026-09-11 常驻）：不再由 boot 期
                                        // 的 neblinkClient 快照门住 —— 全新 home 也要有隧道
                                        // 对象，否则用户登录后 relay/事件推送永远不出现。
                                        // serverUrl 由连接期 live 解析（构造参已移除）；
                                        // stop()（logout）后由 ensureRelayTunnel（enrollment）
                                        // 复活 —— 装配 owner 始终是这里。
                                        val relayTunnel = new nebflow.neblink.NeblinkRelayTunnel(
                                          neblinkService,
                                          // F1: read the authoritative client live on each reconnect —
                                          // a constructor-time closure kept returning a kicked session.
                                          () => tsDiscovery.currentClient.map(_.flatMap(_.currentSessionToken)),
                                          friendService = Some(friendService)
                                        )(dispatcher)
                                        neblinkService.setRelayTunnel(relayTunnel)
                                        dispatcher.unsafeRunAndForget(relayTunnel.connect())
                                        // Baseline refresh (spec §5.2 startup entry): conversations +
                                        // per-conversation keyset pull + friends. Silently no-ops when
                                        // not logged in yet (FriendService catches upstream errors);
                                        // later friend_event pushes keep state fresh.
                                        // 仍保留 boot 条件触发：无 client 时这一拉只会刷 warn 噪声
                                        // （登录后由面板 REST / friend_event 兜底）。
                                        if neblinkClient.isDefined then
                                          dispatcher.unsafeRunAndForget(friendService.refreshAll())
                                        // enrollment 只发「确保在跑」信号（单飞 + 幂等）；
                                        // 登记必须落在 IO 链上（setRelayTunnelStarter 返回 IO，
                                        // 写成裸语句会静默不执行）。
                                        bootEnrollRefusal
                                          .fold(IO.unit)(r => logger.warn(s"[neblink] $r — boot client not created")) *>
                                          neblinkService.setRelayTunnelStarter(relayTunnel.ensure()) *>
                                          // Discovery service — uses NebLink Server for discovery
                                          neblinkService.setDiscoveryHook(
                                            tsDiscovery.discoverCycle
                                              .handleErrorWith(e =>
                                                logger.debug(s"Discovery error: ${e.getMessage}").void
                                              )
                                          ) *> neblinkService.setDiagnostic(tsDiscovery.diagnosticScan) *>
                                          neblinkService.addPeerChangeCallback(
                                            wsHub.broadcast(io.circe.Json.obj("type" -> "peerListChanged".asJson))
                                          ) *>
                                          // Wire the WS data channel send function so other services
                                          // (e.g. DropboxService) can send P2P messages via presenceService.
                                          neblinkService.setSendDataFn((deviceId, channel, payload) =>
                                            presenceService.sendData(deviceId, channel, payload)
                                          ) *>
                                          // Trigger an immediate discovery cycle now that the hook is wired.
                                          neblinkService.sendSync(nebflow.neblink.SyncCommand.PeerDiscovered) *>
                                          // Start NebLink Server heartbeat loop (if configured) — maintains
                                          // session liveness and updates peer list every 30 seconds.
                                          // 2026-09-11 注释校正（复核 R2-d）：这里 `.void` 丢弃了 fiber，
                                          // 并没有「存起来以便 hot-swap 时取消/重启」；实际语义是每拍经
                                          // discovery.currentClient 读 live client（NeblinkDiscovery:78-82）
                                          // ⇒ 无需重启，loop 本就跟随 hot-swap。
                                          startHeartbeatLoop(tsDiscovery).void *>
                                          // Create Dropbox service (cross-device messaging & file transfer)
                                          nebflow.dropbox.DropboxService.create(neblinkService, wsHub).flatMap {
                                            dropboxService =>
                                              val sharedResourcesWithBridge =
                                                sharedResourcesLive.copy(
                                                  bridgeManager = Some(bridgeManager),
                                                  neblinkService = Some(neblinkService),
                                                  // 2026-09-11 boot 快照修复：slot 恒 Some（装配缝里
                                                  // 明确记录「boot 快照不再参与存在性判据」）。
                                                  friendService = nebflow.neblink.NeblinkWiring
                                                    .sharedResourcesSlot(neblinkClient, friendService),
                                                  dropboxService = Some(dropboxService)
                                                )

                                              // --- Create Scheduled Task Service before wsRoutes ---
                                              // routeToAgent needs wsRoutesHolder, which is set below (same pattern as bridge)
                                              val scheduledTaskService = new ScheduledTaskService(
                                                sharedResourcesWithBridge.dispatcher,
                                                sharedResourcesWithBridge.scheduledTaskStore,
                                                (sid, event) =>
                                                  wsRoutesHolder match
                                                    case Some(routes) => routes.handleBridgeAgentCommand(sid, event)
                                                    case None => IO.unit,
                                                wsHub.broadcast,
                                                sessionStore
                                              )
                                              dispatcher.unsafeRunAndForget(scheduledTaskService.start())
                                              val sharedResourcesFinal = sharedResourcesWithBridge.copy(
                                                scheduledTaskService = Some(scheduledTaskService)
                                              )
                                              // device-mail 批（2026-09-15）：跨设备 Nebula
                                              // 邮件的收件腿装配——注入本机 Nebula 会话需要
                                              // SharedResources（root 会话解析单点）与前端广播口。
                                              // 与 FriendMessageTool/RemoteExecutor 同款单例缝；
                                              // 未接线 ⇒ 注入路径显式失败（不静默）。
                                              nebflow.neblink.DeviceMailInbox.initialize(
                                                sharedResourcesFinal,
                                                (json: io.circe.Json) => wsHub.broadcast(json),
                                                // 收件回执出口（契约 v2 ④）：与好友消息 ack
                                                // **同一缝、同一实现点**（`sendAckLive` 的
                                                // live 读隧道，见上面的 friendService ackSender）。
                                                // 🔴 F4（2026-09-18 回执诚实性批）：修前的
                                                // `case None => IO.unit` 把「隧道对象不在册」
                                                // 吞成成功 ⇒ `DeviceMailInbox` 照打
                                                // `ack sent to the server`（假陈述，缺陷 B 的
                                                // 放大因）。现在如实返回 `AckOutcome`
                                                // （F5 据此分支记账）。
                                                Some { eventId =>
                                                  nebflow.neblink.NeblinkRelayTunnel.sendAckLive(
                                                    neblinkService.relayTunnelOpt,
                                                    eventId
                                                  )
                                                }
                                              )

                                              // --- Daemon Service (external process lifecycle) ---
                                              val daemonService = new DaemonService(dispatcher)
                                              val sharedResourcesWithDaemon = sharedResourcesFinal.copy(
                                                daemonService = Some(daemonService)
                                              )
                                              // JVM shutdown hook: daemons must die with Nebflow even when the JVM
                                              // is killed by SIGINT/SIGTERM (Ctrl+C) — cats-effect `.guarantee`
                                              // finalizers are not guaranteed to run on abrupt termination. The
                                              // shutdown hook always runs on JVM exit. Idempotent: stopAll on an
                                              // already-stopped set is a no-op, so it is safe alongside the
                                              // graceful path in `.guarantee` below.
                                              //
                                              // releaseBackend runs FIRST: on Ctrl+C the graceful path never
                                              // executes, so without this the in-flight LLM HTTP requests (FS2/
                                              // sttp via the JDK HttpClient) would keep running and the provider
                                              // would keep generating (and billing) their responses. shutdownNow
                                              // aborts them at the TCP level; close + dispatcher release tear down
                                              // the pool. Release is idempotent (once-guarded in createLlm), so
                                              // hook + graceful `.guarantee` cannot double-release.
                                              Runtime.getRuntime.addShutdownHook(
                                                new Thread(() =>
                                                  try releaseBackend.unsafeRunSync()
                                                  catch case _: Throwable => ()
                                                  try daemonService.stopAll().unsafeRunSync()
                                                  catch case _: Throwable => ()
                                                )
                                              )
                                              // Auto-start daemons configured with autoStart=true
                                              dispatcher.unsafeRunAndForget(
                                                IO.sleep(2.seconds) *> daemonService
                                                  .autoStart(DaemonStore())
                                                  .handleErrorWith(e =>
                                                    logger.warn(s"Daemon auto-start failed: ${e.getMessage}")
                                                  )
                                              )

                                              TtsService.create().flatMap { ttsService =>
                                                SttService.create().flatMap { sttService =>
                                                  // R-1b conn-guard（2026-09-22）：per-IP WS 看护 + 摄取超时
                                                  // 显式化 + 连接面快照/预警 + /health/conn（设计件 §C④，
                                                  // 20260917_gateway-connection-hardening.md）。引用点：下方
                                                  // builder 钩子 / WS·REST 构造 / 快照循环 / requestTap。
                                                  val connGuardLogger = NebflowLogger.forName("nebflow.conn-guard")
                                                  ConnGuard.create(connGuardLogger).flatMap { connGuard =>
                                                    EmberServerBuilder
                                                      .default[IO]
                                                      .withHost(cfg.host)
                                                      .withPort(cfg.port)
                                                      // 2026-09-22 watchdog repair: 默认 maxConnections=1024
                                                      // 被 KAI 对端的 presence 拨号风暴（~1 条/秒、请求永
                                                      // 不被读取也永不关闭）耗尽 → parJoin(1024) 饿死连接
                                                      // 摄取 → 整个 HTTP 面失聪（健康检查 HTTP=000）。
                                                      // 4096 = 4 倍余量；idleTimeout 1h→5min 加速回收僵
                                                      // 死连接（presence WS 心跳 5s/10s，不受影响）。
                                                      // R-1b 返工：值改单源引用（ConnGuardConfig.
                                                      // EmberMaxConnections），供 conn-guard 的「连接代理
                                                      // 预警腿」按同一上限对齐口径（判词项 (c)）。
                                                      .withMaxConnections(ConnGuardConfig.EmberMaxConnections)
                                                      // R-1a 落定的 idle 值改单源引用（值不变，零行为变更）。
                                                      .withIdleTimeout(ConnGuardConfig.IdleTimeout)
                                                      // R-1b：摄取层超时显式化——库默认 javap 钉死（header 5s /
                                                      // shutdown 30s：http4s-server_3-0.23.30 package$defaults$ 与
                                                      // ember Defaults$）。零行为变更；防上游默认漂移 + 摄取面可
                                                      // 检索（设计件 §E.3「留给实施批钉」项）。
                                                      .withRequestHeaderReceiveTimeout(
                                                        ConnGuardConfig.HeaderReceiveTimeout
                                                      )
                                                      .withShutdownTimeout(ConnGuardConfig.ShutdownTimeout)
                                                      // 连接级异常钩子（0.23.30 仅有的官方连接级缝，设计件 §B.4/§C④-3）：
                                                      // ReadTimeout = 闲置回收（5min idle 的常规动作）；RequestHeaders
                                                      // Timeout = 头部摄取超时（慢速摄取/风暴形状信号）；写失败 = 对端
                                                      // 断开。逐条落 nebflow.log——把「静默失联」变可留痕。未匹配异常
                                                      // 走 ember 默认处理，行为零改动。（注：RequestHeadersTimeout 类
                                                      // 本体 private[ember] ⇒ 以类名守卫判别，不用类型模式。）
                                                      .withConnectionErrorHandler {
                                                        case e
                                                            if e.getClass.getSimpleName.contains(
                                                              "RequestHeadersTimeout"
                                                            ) =>
                                                          connGuardLogger.warn(
                                                            s"conn-guard: ember request-headers-timeout (slow ingestion, ${ConnGuardConfig.HeaderReceiveTimeout}): ${String.valueOf(e.getMessage)}"
                                                          )
                                                        case e: org.http4s.ember.core.EmberException.ReadTimeout =>
                                                          connGuardLogger.debug(
                                                            s"conn-guard: ember read-timeout (idle reap, ${ConnGuardConfig.IdleTimeout}): ${String.valueOf(e.getMessage)}"
                                                          )
                                                      }
                                                      .withOnWriteFailure { (reqOpt, _resp, t) =>
                                                        connGuardLogger.debug(
                                                          s"conn-guard: ember write-failure to ${reqOpt
                                                              .fold("?")(r => ConnGuard.normalizeIp(r.remoteAddr))}: ${t.toString}"
                                                        )
                                                      }
                                                      .withHttpWebSocketApp { wsb =>
                                                        val wsRoutes = new WebSocketRoutes(
                                                          wsb,
                                                          sessionService,
                                                          agentService,
                                                          configService,
                                                          configRef,
                                                          rateLimiter,
                                                          token,
                                                          fileTracker,
                                                          sessionStore,
                                                          wsHub,
                                                          contextWindow,
                                                          sharedResourcesWithDaemon,
                                                          mcpManager,
                                                          sttService = sttService,
                                                          nfTicketStore = nfTicketStore,
                                                          nfPathPolicy = nfPathPolicy,
                                                          connGuard = connGuard
                                                        )
                                                        wsRoutesHolder = Some(wsRoutes)

                                                        // REST API routes for CLI consumption
                                                        val restApiRoutes = new RestApiRoutes(
                                                          token,
                                                          configRef,
                                                          sharedResourcesWithDaemon,
                                                          sessionStore,
                                                          wsRoutes,
                                                          neblinkService = Some(neblinkService),
                                                          ttsService = ttsService,
                                                          neblinkDiscovery = Some(tsDiscovery),
                                                          gatewayPort = cfg.port.value,
                                                          wsHub = wsHub,
                                                          connGuard = connGuard
                                                        )

                                                        // R-1b：请求级 per-IP 观测 tap（§C④-2，只计数不拦截——
                                                        // WS 升级面的准入由各受理点 connGuard.checkWs 把门）。
                                                        ConnGuard.requestTap(connGuard)(
                                                          Router(
                                                            "/api" -> (chatRoutes.routes <+> restApiRoutes.routes <+> restApiRoutes
                                                              .presenceWsRoutes(wsb)),
                                                            // Logto AC+PKCE loopback callback (RFC 8252) —
                                                            // root-level, outside /api: the provider's browser
                                                            // redirect carries no gateway token.
                                                            "/auth" -> restApiRoutes.authCallbackRoutes,
                                                            // Static tree only — gzip must never wrap the
                                                            // "/api" tree (SSE stream, presence WS).
                                                            "/" -> GzipMiddleware(wsRoutes.routes)
                                                          ).orNotFound
                                                        )
                                                      }
                                                      .build
                                                      .use { _ =>
                                                        // Wire bridge inject ref
                                                        val wireBridge = wsRoutesHolder match
                                                          case Some(wsRoutes) =>
                                                            bridgeInjectRef.set(Some(wsRoutes.handleBridgeMessage))
                                                          case None => IO.unit
                                                        wireBridge *> (for
                                                          _ <- logger.info(
                                                            s"gateway listening on ${cfg.host}:${cfg.port}"
                                                          )
                                                          _ <- logger.info(
                                                            s"access URL: $baseUrl (token in ~/.nebflow/auth.json)"
                                                          )
                                                          // R-1b conn-guard：60s 连接面快照 + warnPct 预警
                                                          // （§C④-1/5）。不经 HTTP 面——连接层被灌死时这条
                                                          // 观察者路仍照常输出，正是失联取证需要的独立观察者。
                                                          _ <- connGuard.snapshotLoop.start
                                                          // ── 插件装载健康摘要（P1 静默缩容可见性，2026-09-10）──
                                                          // 启动完成即聚合输出一次：总包数/载入数/目录可见数 +
                                                          // 拒载清单 + 未批准清单 + digest 漂移清单（一行一条）。
                                                          // 干净场景零输出（healthSummary 返回 None）；同状态去重，
                                                          // 重扫 tick 不重复刷屏（PluginRegistry.logHealthSummary）。
                                                          // best-effort：摘要失败只 WARN，不影响启动。
                                                          _ <- nebflow.core.plugin.PluginRegistry
                                                            .logHealthSummary("startup")
                                                            .handleErrorWith(e =>
                                                              logger
                                                                .warn(s"plugin health summary failed: ${e.getMessage}")
                                                            )
                                                          // ── 热重启握手终态（[s7]，hot-restart 批设计 §3.3）──
                                                          // successor.json（握手完成锚点）+ intent 归档
                                                          // last-restart.json + 冷却窗锚点置位（R7）+
                                                          // completed 帧（此刻重连尚未发生，帧为 best-
                                                          // effort；验收 1 以 last-restart.json 存在为准）。
                                                          // 普通启动（无 SuccessorContext）恒为 no-op。
                                                          //
                                                          // **批 2 G3 追加：绑定后健康自检（交接后短窗口内的判据）**
                                                          // 真端口已在手 ⇒ 第三/四档对**真端口**取证（真 socket +
                                                          // 真 HTTP + 真载荷），不需要探针端点。判据通过 ⇒ 下面既有的
                                                          // 终态链原样执行；判据失败 ⇒ **不写「已完成」**，改为：
                                                          // ① 指针化回滚（切回上一版 + 复起上一版，走既有后继协议）
                                                          // ② 落盘 rollback.json（诊断面）+ restartStatus 的 rolled-back 帧
                                                          // ③ 本进程退出，把端口让给上一版（既有 let-handover-machinery）
                                                          // fail-open：自检自身异常/无期望版本 ⇒ `unverified`（大声记名，
                                                          // 不当失败）——既有启动链零回归；见 `HealthCheck`。
                                                          _ <- nebflow.core.hotrestart.SuccessorContext.get.traverse_ {
                                                            ctx =>
                                                              val newPid = ProcessHandle.current.pid
                                                              val complete = nebflow.core.hotrestart.SuccessorGate
                                                                .writeSuccessor(
                                                                  PathUtil.dataRoot,
                                                                  ctx.generation,
                                                                  newPid
                                                                ) *>
                                                                nebflow.core.hotrestart.SuccessorGate
                                                                  .archiveIntent(PathUtil.dataRoot) *>
                                                                nebflow.core.hotrestart.HotRestart
                                                                  .noteRestartCompleted() *>
                                                                wsHub.broadcast(
                                                                  io.circe.Json.obj(
                                                                    "type" -> "restartStatus".asJson,
                                                                    "phase" -> "completed".asJson,
                                                                    "detail" -> s"successor pid $newPid serving (generation ${ctx.generation})".asJson
                                                                  )
                                                                ) *>
                                                                logger.info(
                                                                  s"[hot-restart] handover complete — successor pid $newPid serving, intent archived (generation ${ctx.generation}, old pid ${ctx.oldPid})"
                                                                )
                                                              val installDir = nebflow.core.hotrestart.InstallPointer
                                                                .defaultInstallDir()
                                                              nebflow.core.hotrestart.InstallPointer
                                                                .expectedVersion(installDir)
                                                                .flatMap { expected =>
                                                                  nebflow.core.hotrestart.HealthCheck
                                                                    .afterBind(
                                                                      cfg.port.value,
                                                                      sharedResources.healthMonitor,
                                                                      expected,
                                                                      nebflow.core.hotrestart.HealthCheck.Timeouts.T3PortServingMs,
                                                                      nebflow.core.hotrestart.HealthCheck.Timeouts.T4VersionMatchMs,
                                                                      nebflow.core.hotrestart.HealthCheck.Timeouts.PollMs
                                                                    )
                                                                    .handleErrorWith(e =>
                                                                      logger
                                                                        .warn(
                                                                          s"[hotupd] post-bind health self-check raised (treated as unverified, boot continues): ${e.getMessage}"
                                                                        )
                                                                        .as(nebflow.core.hotrestart.HealthReport(Nil))
                                                                    )
                                                                    .flatMap { report =>
                                                                      if report.isHealthy then
                                                                        report.unverified.traverse_(u =>
                                                                          logger.warn(
                                                                            s"[hotupd] post-bind health tier UNVERIFIED (${u.tier.wire}): ${u.detail} — recorded, never reported as pass"
                                                                          )
                                                                        ) *> complete
                                                                      else
                                                                        val reason =
                                                                          s"post-bind health self-check failed: ${report.failed.map(_.detail).getOrElse("unknown")} [${report.detail}]"
                                                                        val rollback =
                                                                          new nebflow.core.hotupdate.Rollback(
                                                                            installDir = installDir,
                                                                            dataRoot = PathUtil.dataRoot,
                                                                            host = ctx.host,
                                                                            port = ctx.port
                                                                          )
                                                                        logger.error(
                                                                          s"[hotupd] $reason — rolling back to the retained previous version (pointer-authoritative)"
                                                                        ) *>
                                                                          rollback.run(reason).flatMap { outcome =>
                                                                            wsHub.broadcast(
                                                                              io.circe.Json.obj(
                                                                                "type" -> "restartStatus".asJson,
                                                                                "phase" -> "rolled-back".asJson,
                                                                                "detail" -> outcome
                                                                                  .fold(
                                                                                    err =>
                                                                                      s"rollback incomplete: $err — pointers and diagnostics are on disk; see the safe-boot path",
                                                                                    o =>
                                                                                      s"new version ${o.fromVersion} failed its health self-check — rolled back to ${o.toVersion} (jar ${o.jar}); previous version relaunching"
                                                                                  )
                                                                                  .asJson
                                                                              )
                                                                            ) *>
                                                                              logger.error(
                                                                                s"[hotupd] ROLLBACK outcome: ${outcome.fold(identity, o => s"${o.fromVersion} -> ${o.toVersion}")} — this process exits so the previous version can take the port"
                                                                              ) *>
                                                                              IO.raiseError(
                                                                                new IllegalStateException(
                                                                                  s"new version failed the post-bind health self-check: $reason — rolled back to the retained previous version"
                                                                                )
                                                                              )
                                                                          }
                                                                    }
                                                                }
                                                          }
                                                          // Register bridge as WsHub listener for agent events
                                                          _ <- wsHub.register(json =>
                                                            val sessionId =
                                                              json.hcursor
                                                                .downField("sessionId")
                                                                .as[String]
                                                                .getOrElse("")
                                                            if sessionId.nonEmpty then
                                                              bridgeManager.dispatchAgentEvent(sessionId, json)
                                                            else IO.unit
                                                          )
                                                          _ <- bridgeManager.startAll.start // start in background
                                                          // --- Background: LLM provider health monitoring ---
                                                          _ <- healthMonitor.start().void.start
                                                          // --- Background: TaskStuckWatcher (P0 阶段 3) ---
                                                          // 卡死识别与恢复：周期扫 agentRegistry，Processing 态且 turn 活动
                                                          // 超阈值（默认 10min，可配 stuckThresholdMs）→ 子 agent 发 Stop 走
                                                          // BackoffSupervisor 退避重启；根 agent 广播 taskStuck 事件由用户决定。
                                                          _ <- nebflow.core.processor.TaskStuckWatcher
                                                            .run(
                                                              sharedResourcesWithDaemon,
                                                              wsHub,
                                                              interval =
                                                                nebflow.shared.Defaults.StuckWatcherIntervalSec.seconds,
                                                              thresholdMs = config.stuckThresholdMs
                                                                .getOrElse(nebflow.shared.Defaults.StuckThresholdMs)
                                                            )
                                                            .start
                                                          // --- Background: FreezeScheduler (freeze-schedule) ---
                                                          // 冻结恢复扫描：周期向 Frozen 态 agent 发 CheckFreezeGate，
                                                          // 出冻结段则恢复挂起的 dispatch（仿 TaskStuckWatcher 模式：
                                                          // 错误自愈 + `>>` 递归栈安全）。Frozen 态 TaskStuckWatcher
                                                          // 天然豁免（只扫 Processing）。
                                                          _ <- nebflow.core.processor.FreezeScheduler
                                                            .run(
                                                              sharedResourcesWithDaemon,
                                                              interval =
                                                                nebflow.shared.Defaults.FreezeCheckIntervalSec.seconds
                                                            )
                                                            .start
                                                          // --- P0（2026-08-30）：skip 持久化恢复 ---
                                                          // 重启前「跳过本次」若未到期（skipUntil > now），恢复进内存
                                                          // ref——WS/REST 的 freezeState.skipped 随即可见，输入栏不再
                                                          // 重新冻结；已过期则清理盘上残留（跳过非永久，下一段照常）。
                                                          _ <- nebflow.core.schedule.FreezeSchedule
                                                            .loadSkipUntil(nebflow.core.PathUtil.dataRoot)
                                                            .flatMap {
                                                              case Some(t) if t > System.currentTimeMillis() =>
                                                                sharedResourcesWithDaemon.freezeSkipUntilRef.set(
                                                                  Some(t)
                                                                ) *>
                                                                  logger.info(
                                                                    s"Freeze skip restored from disk (skipUntil=$t)"
                                                                  )
                                                              case Some(_) =>
                                                                nebflow.core.schedule.FreezeSchedule
                                                                  .persistSkip(nebflow.core.PathUtil.dataRoot, None)
                                                                  .handleErrorWith(e =>
                                                                    logger.warn(
                                                                      s"Failed to clean expired freeze skip: ${e.getMessage}"
                                                                    )
                                                                  )
                                                              case None => IO.unit
                                                            }
                                                          _ <-
                                                            if GatewayConfig.noBrowser ||
                                                              nebflow.core.HeadlessMode.enabled
                                                            then IO.unit
                                                            else openBrowser(url)
                                                          // --- Background init: skills dir, MCP servers ---
                                                          _ <- SkillService
                                                            .ensureDefaults()
                                                            .handleErrorWith(e =>
                                                              logger.warn(s"Skills init failed: ${e.getMessage}")
                                                            )
                                                            .start
                                                          // --- Background init: MCP servers ---
                                                          _ <- startMcpServers(config, mcpManager, agentLibrary)
                                                            .flatMap { _ =>
                                                              // Broadcast updated MCP server list to all connected clients
                                                              mcpManager.listServers
                                                                .map(_.map { case (id, enabled) =>
                                                                  io.circe.Json
                                                                    .obj("id" -> id.asJson, "enabled" -> enabled.asJson)
                                                                })
                                                                .flatMap { mcpJson =>
                                                                  wsHub.broadcast(
                                                                    io.circe.Json.obj(
                                                                      "type" -> "mcpServersUpdate".asJson,
                                                                      "mcpServers" -> mcpJson.asJson
                                                                    )
                                                                  )
                                                                }
                                                            }
                                                            .handleErrorWith { e =>
                                                              logger.warn(s"Background init failed: ${e.getMessage}")
                                                            }
                                                            .start
                                                          // --- NebLink: sync is event-driven (actor), no background loops needed ---
                                                          _ <- logger.info(
                                                            "Type 'quit', 'exit', or 'q' (or press Ctrl+C) to stop"
                                                          ) *>
                                                            // 热重启优雅让渡闸（hot-restart 批 §3.3）：race 任一
                                                            // 完成即收敛 use 块 → Ember 优雅 stop → .guarantee
                                                            // 链 → JVM 自然退出（替代 System.exit(0)）。R4 零
                                                            // 回归：quit/Ctrl+C 走 Left；Deferred 仅由 HotRestart
                                                            // 编排器 complete——三既有退出路径行为不变。
                                                            IO.race(
                                                              waitForQuit,
                                                              sharedResourcesWithDaemon.gatewayShutdown.get
                                                            ).void
                                                        yield ())
                                                      }
                                                      .guarantee(
                                                        logger.info("shutting down...") *>
                                                          daemonService.stopAll() *>
                                                          // P0 (2026-08-19): abort in-flight LLM requests BEFORE the
                                                          // sttp backend/dispatcher close — Ctrl+C previously let
                                                          // FS2 streams keep burning tokens during JVM drain.
                                                          nebflow.llm.LlmInterface.cancelAllInflight() *>
                                                          // 2026-09-11：原为 boot 快照 neblinkClient.traverse_ ——
                                                          // cold-start ⇒ None（退出不通知 server）、hot-swap 后通知的
                                                          // 是旧（已被踢）client。改读权威 live client（同
                                                          // performLocalLogout 的既有先例；Ref.get 不抛）。
                                                          tsDiscovery.currentClient.flatMap(_.traverse_(_.logout)) *>
                                                          mcpManager.stopAll() *>
                                                          releaseBackend
                                                      )
                                                  } // end connGuard (R-1b)
                                                } // end sttService
                                              } // end ttsService
                                          } // end neblinkService setup block
                                      } // end neblinkService
                                    } // end bridgeManager
                                  } // end main setup scope
                                } // end fileLockMgr
                              } // end dispatcher.use
                            } // end fileTracker
                          } // end rateLimiter
                      } // end mcpManager
                  }
                }
            } // end config flatMap
          }
        }
      }

end GatewayMain
