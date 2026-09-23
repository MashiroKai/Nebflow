package nebflow.core.hotrestart

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowLogger

import java.net.{HttpURLConnection, InetSocketAddress, Proxy, URI}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import scala.concurrent.duration.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * 健康端点**载荷构造的唯一来源**（hotupdate 批 2 G3）。
 *
 * 既有健康端点（`RestApiRoutes.scala:76`，挂载点 `/api/health`）的响应体就是本方法
 * 的返回值——端点处理器与「后继门口探针端点」（[[ProbeEndpoint]]）共用本方法，
 * **不新造第二套健康载荷**：字段名、字段序、取值语义在单一处。
 *
 * 字段（逐字沿用既有端点）：`product` / `status` / `version` / `providers` / `search`。
 * `version` = `nebflow.Version.string`——**这正是第四档自检的取证对象**（设计 §12
 * 未取证项 #2 的答案：载荷含版本号，故第四档形态 = 载荷版本核对，无需替代判据）。
 */
object HealthPayload:

  def build(monitor: nebflow.llm.ProviderHealthMonitor): IO[Json] =
    for
      modelStates <- monitor.getStates
      searchHealth <- monitor.getSearchHealth
    yield
      val providers = modelStates.map { case (k, st) =>
        k -> (st match
          case nebflow.llm.HealthState.Up => "up"
          case nebflow.llm.HealthState.Down(reason, _) => s"down: $reason")
      }
      val search = searchHealth match
        case nebflow.llm.SearchApiHealth.Unconfigured => Json.obj("status" -> "unconfigured".asJson)
        case nebflow.llm.SearchApiHealth.Up => Json.obj("status" -> "up".asJson)
        case nebflow.llm.SearchApiHealth.Down(reason, since) =>
          Json.obj("status" -> "down".asJson, "reason" -> reason.asJson, "since" -> since.asJson)
      Json.obj(
        "product" -> "nebflow".asJson, // single-instance guard identification
        "status" -> "ok".asJson,
        "version" -> nebflow.Version.string.asJson,
        "providers" -> providers.asJson,
        "search" -> search
      )

  /** 载荷里的版本号（读侧单点：探针核对与「后继自证」都不许自己解析别处）。 */
  def versionOf(payload: Json): Option[String] =
    payload.hcursor.downField("version").as[String].toOption.filter(_.trim.nonEmpty)

end HealthPayload

/**
 * 「后继门口探针端点」（hotupdate 批 2 G3 第三档的**生产者侧**）。
 *
 * 为什么需要它：单端口让渡的次序是**旧实例先退出、后继才 bind**（后继的进场闸
 * `SuccessorGate.awaitHandover` 先等旧 pid 死亡、再确认无活监听，才轮到
 * `GatewayMain` 的 `EmberServerBuilder` 绑定，`GatewayMain.scala:1082`）。⇒ 在
 * 「到门口确认」与「优雅让渡」之间的自检窗口里，**共享端口仍属旧实例**——旧实例直接
 * 探自己那个端口只能探到自己（自观察假绿）。结构性出路只有一条：后继在门口**另开一个
 * loopback 临时端口**把「我能不能服务」变成可被旧实例独立观察的事实（真 socket、真
 * HTTP、真载荷），并把它写进 intent 回执（`probePort`）。
 *
 * 三条纪律：
 *  1. **只用 loopback + 临时端口**（`127.0.0.1:0`），不占真端口、不对外暴露。
 *  2. **载荷单一来源**（[[HealthPayload.build]]）——探针端点不是第二套健康端点。
 *  3. **fail-open**：起不来就记 WARN、回执里不带 `probePort`，让旧实例把第三/四档
 *     报成 `unverified`（大声、可核、不静默）——**绝不因探针端点失败而阻塞既有的
 *     交接链**（既有重启面零回归优先）。
 *
 * 传输层用 JDK 内置 `com.sun.net.httpserver.HttpServer`：门口位在 Ember 构建之前
 * （`GatewayMain.scala:708` 的 `succeedPortGate` 早于 `:1082` 的 Ember build），刻意
 * **不动 boot 次序、不预建 Ember 栈**，也不构成第二套启动链（它只是一个只读探针
 * 监听器，随交接结束即停）。
 */
final class ProbeEndpoint private (server: com.sun.net.httpserver.HttpServer, val port: Int):

  /** 停探针端点（幂等、吞异常——收尾失败不得影响交接）。 */
  def stop: IO[Unit] =
    IO.blocking(server.stop(0))
      .void
      .handleErrorWith(e => IO(ProbeEndpoint.logger.warn(s"[hot-restart] probe endpoint stop failed: ${msg(e)}")))

  private def msg(e: Throwable): String = Option(e.getMessage).getOrElse(e.toString)

object ProbeEndpoint:

  private val logger = NebflowLogger.forName("nebflow.hotrestart")

  /** 探针端点的路径与既有健康端点一致（挂载前缀 `/api`）——探针函数只有一套。 */
  val Path: String = "/api/health"

  /** 起探针端点。Left = 起不来（fail-open：调用方记 WARN 并继续既有交接链）。 */
  def start(monitor: nebflow.llm.ProviderHealthMonitor): IO[Either[String, ProbeEndpoint]] =
    IO.blocking {
      val server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.setExecutor(Executors.newSingleThreadExecutor(r =>
        val t = new Thread(r, "nebflow-health-probe")
        t.setDaemon(true)
        t
      ))
      server.createContext(
        Path,
        (ex: com.sun.net.httpserver.HttpExchange) =>
          try
            if ex.getRequestMethod != "GET" then ex.sendResponseHeaders(405, -1)
            else
              val bytes = HealthPayload.build(monitor).unsafeRunSync().noSpaces.getBytes(StandardCharsets.UTF_8)
              ex.getResponseHeaders.set("Content-Type", "application/json; charset=UTF-8")
              ex.sendResponseHeaders(200, bytes.length.toLong)
              Using.resource(ex.getResponseBody)(_.write(bytes))
          catch
            case NonFatal(e) =>
              logger.warn(s"[hot-restart] probe endpoint handler failed: ${Option(e.getMessage).getOrElse(e.toString)}")
              try ex.sendResponseHeaders(500, -1)
              catch case NonFatal(_) => ()
          finally ex.close()
      )
      server.start()
      Right(new ProbeEndpoint(server, server.getAddress.getPort)): Either[String, ProbeEndpoint]
    }.flatMap {
      case Right(ep) =>
        logger
          .info(s"[hot-restart] door probe endpoint up on 127.0.0.1:${ep.port} (loopback-only, ${Path})")
          .as(Right(ep): Either[String, ProbeEndpoint])
      case Left(e) => IO.pure(Left(e): Either[String, ProbeEndpoint])
    }.handleErrorWith(e => IO.pure(Left(s"probe endpoint unavailable: ${Option(e.getMessage).getOrElse(e.toString)}")))

end ProbeEndpoint

/**
 * 健康载荷的 HTTP 读取（**唯一一处**——旧实例侧第三档探针、后继侧绑定后自检、
 * spec 都走这里）。绕过 JVM 系统代理（`SingleInstanceGuard.probeNebflow:141-158`
 * 同款口径：本地代理会吞掉 loopback 并慢失败）。
 */
object HealthHttp:

  def fetch(host: String, port: Int, timeoutMs: Int, path: String = ProbeEndpoint.Path): IO[Either[String, Json]] =
    IO.blocking {
      val conn = URI
        .create(s"http://$host:$port$path")
        .toURL
        .openConnection(Proxy.NO_PROXY)
        .asInstanceOf[HttpURLConnection]
      conn.setConnectTimeout(timeoutMs)
      conn.setReadTimeout(timeoutMs)
      conn.setRequestMethod("GET")
      try
        val code = conn.getResponseCode
        if code != 200 then Left(s"HTTP $code from http://$host:$port$path")
        else
          val body = Using.resource(scala.io.Source.fromInputStream(conn.getInputStream, "UTF-8"))(_.mkString)
          io.circe.parser.parse(body).left.map(err => s"undecodable health payload: ${err.message}")
      finally conn.disconnect()
    }.handleErrorWith(e =>
      IO.pure(Left(s"health fetch failed (http://$host:$port$path): ${Option(e.getMessage).getOrElse(e.toString)}"))
    )

end HealthHttp

/**
 * 健康自检的四档（hotupdate 批 2 G3，设计 §6① 四档递进）——**档位是机器 token**，
 * 逐档独立超时、逐档独立结果（`pass` / `fail` / `unverified`）。
 */
enum HealthTier(val wire: String):
  /** 第一档：后继进程存活（既有能力：C1/C2 的存活判定）。 */
  case SuccessorAlive extends HealthTier("successor-alive")

  /** 第二档：到门口回执（既有能力：intent phase=readyToBind）。 */
  case DoorReceipt extends HealthTier("ready-to-bind-receipt")

  /** 第三档：端口可服务（新增薄层：既有健康端点载荷 + 既有端口探测能力）。 */
  case PortServing extends HealthTier("port-serving")

  /** 第四档：版本核对（新增薄层：载荷 `version` vs 期望版本，取证见 [[HealthPayload]]）。 */
  case VersionMatch extends HealthTier("version-match")

/**
 * 单档结果。`unverified` 是**显式第三态**：既不假装通过，也不把「拿不到证据」当成
 * 失败（后者会让不发达探针面的既有形态（legacy 后继 / bundled 形态）从此起不来——
 * 既有重启面的回归）。任何 `unverified` 都必须带**可核原因**并大声落日志/进帧。
 */
enum HealthOutcome(val wire: String):
  case Pass extends HealthOutcome("pass")
  case Fail extends HealthOutcome("fail")
  case Unverified extends HealthOutcome("unverified")

final case class HealthTierResult(tier: HealthTier, outcome: HealthOutcome, detail: String, elapsedMs: Long)

/**
 * 四档报告（逐档读数 + 结论）。`isHealthy` = **无 fail**（`unverified` 不否定健康——
 * 见 [[HealthOutcome]] 的取舍说明）；`failed` / `unverified` 供中止原因与诊断明细使用。
 */
final case class HealthReport(tiers: List[HealthTierResult]):

  def failed: Option[HealthTierResult] = tiers.find(_.outcome == HealthOutcome.Fail)
  def unverified: List[HealthTierResult] = tiers.filter(_.outcome == HealthOutcome.Unverified)
  def isHealthy: Boolean = failed.isEmpty

  /** 逐档明细（诊断面；进日志与失败原因，不是展示文案）。 */
  def detail: String =
    tiers.map(t => s"${t.tier.wire}=${t.outcome.wire}(${t.detail}; ${t.elapsedMs}ms)").mkString(" | ")

  def tierWire(t: HealthTier): Option[String] = tiers.find(_.tier == t).map(_.outcome.wire)

end HealthReport

/**
 * 四档健康自检（hotupdate 批 2 G3）。
 *
 * **插入点**：`HotRestart.begin` 的 [7] C2（到门口确认）与 [8]（优雅让渡）之间——
 * 结构性唯一窗口。失败 ⇒ **复用既有中止路径** `HotRestart.abort`（清 draining +
 * intent failure + 旧实例继续服务），**零第二套中止机**。
 *
 * 两侧载体（逐档给出**证据从哪来**，避免自观察假绿）：
 *  - ① ② 由**旧实例**在窗口内独立观察（进程存活 / 回执文件）。
 *  - ③ ④ 由**旧实例**对后继在门口公告的 loopback 探针端口做**独立**观察
 *    （真连接 + 真 HTTP + 真载荷），数据来自[[ProbeEndpoint]]（后继门口起、交接即停）。
 *    为什么不能探共享端口：见 [[ProbeEndpoint]] 的次序论证（会探到旧实例自己）。
 *  - 后继侧绑定成功之后的同一组档位由 `HealthCheck.afterBind` 复跑（交接后短窗口内
 *    的判据，G4 触发器②的挂点），两侧共用本对象的档位/结果类型与探针实现。
 */
object HealthCheck:

  private val logger = NebflowLogger.forName("nebflow.hotrestart")

  /**
   * 期望版本（第四档的**真值来源**，纯函数、可单测）。取序：
   *  1. 安装指针 `current-version`（安装脚本写、启动器读——**同一份**指针，批 2 G4）；
   *  2. 回执里的 spawn 命令中的包路径（旧实例自己选定的包 = 它将要交棒的那个 artifact）；
   *  3. 都拿不到 ⇒ None（第四档报 `unverified`，**不静默通过**）。
   * 刻意**不**用后继自报的版本当期望值（那是循环论证）。
   */
  def expectedVersion(pointer: Option[String], spawnCmd: Option[String]): Option[String] =
    pointer.map(_.trim).filter(_.nonEmpty).orElse(spawnCmd.flatMap(versionFromJarPath))

  /** 从包路径里解析版本（`nebflow-assembly-<version>.jar`；与安装脚本的命名契约一致）。 */
  def versionFromJarPath(cmd: String): Option[String] =
    val re = raw"[^/\s]*-(?:assembly|launcher)-([0-9][^/\s]*)\.jar".r
    re.findFirstMatchIn(cmd).map(_.group(1)).filter(_.nonEmpty)

  /** 第一档：后继在 `holdMs` 窗口内**持续存活**（不是单次采样——窗口内死亡即该档红）。 */
  private def tierAlive(isAlive: IO[Boolean], holdMs: Long, pollMs: Long): IO[HealthTierResult] =
    val started = System.currentTimeMillis()
    def loop(deadline: Long): IO[HealthTierResult] =
      isAlive.flatMap { alive =>
        val now = System.currentTimeMillis()
        if !alive then
          IO.pure(
            HealthTierResult(
              HealthTier.SuccessorAlive,
              HealthOutcome.Fail,
              s"successor process died inside the ${holdMs}ms liveness window",
              now - started
            )
          )
        else if now >= deadline then
          IO.pure(
            HealthTierResult(
              HealthTier.SuccessorAlive,
              HealthOutcome.Pass,
              s"successor process alive for the whole ${holdMs}ms window",
              now - started
            )
          )
        else IO.sleep(pollMs.millis) *> loop(deadline)
        end if
      }
    loop(System.currentTimeMillis() + holdMs)

  end tierAlive

  /** 第二档：到门口回执（读 intent 文件；`failed` 相位带原因直接红，不靠超时猜）。 */
  private def tierReceipt(
    readIntent: IO[Option[HotRestartIntent]],
    deadlineMs: Long,
    pollMs: Long
  ): IO[HealthTierResult] =
    val started = System.currentTimeMillis()
    def loop(deadline: Long): IO[HealthTierResult] =
      readIntent.flatMap {
        case Some(i) if i.phase == "readyToBind" =>
          IO.pure(
            HealthTierResult(
              HealthTier.DoorReceipt,
              HealthOutcome.Pass,
              "successor reported readyToBind",
              System.currentTimeMillis() - started
            )
          )
        case Some(i) if i.phase == "failed" =>
          IO.pure(
            HealthTierResult(
              HealthTier.DoorReceipt,
              HealthOutcome.Fail,
              s"successor reported failure: ${i.failure.getOrElse("(no reason recorded)")}",
              System.currentTimeMillis() - started
            )
          )
        case other =>
          val now = System.currentTimeMillis()
          if now >= deadline then
            IO.pure(
              HealthTierResult(
                HealthTier.DoorReceipt,
                HealthOutcome.Fail,
                s"no readyToBind receipt within ${deadlineMs}ms (intent phase=${other.map(_.phase).getOrElse("<absent>")})",
                now - started
              )
            )
          else IO.sleep(pollMs.millis) *> loop(deadline)
      }
    loop(System.currentTimeMillis() + deadlineMs)

  end tierReceipt

  /**
   * 第三档：端口可服务——对后继公告的 loopback 探针端口做**独立**观察：
   * 连接探测（既有能力，注入）+ 真 HTTP GET `/api/health` 要求 200。
   * `probePort=None` ⇒ `unverified`（带原因，绝不静默当成通过）。
   */
  private def tierPortServing(
    probePort: Option[Int],
    connectProbe: Option[Int => IO[Boolean]],
    deadlineMs: Long,
    pollMs: Long,
    isAlive: IO[Boolean]
  ): IO[HealthTierResult] =
    val started = System.currentTimeMillis()
    def finish(outcome: HealthOutcome, detail: String): HealthTierResult =
      HealthTierResult(HealthTier.PortServing, outcome, detail, System.currentTimeMillis() - started)
    (probePort, connectProbe) match
      case (None, _) =>
        IO.pure(
          finish(
            HealthOutcome.Unverified,
            "successor announced no loopback probe endpoint (legacy successor or probe endpoint unavailable)"
          )
        )
      case (Some(_), None) =>
        IO.pure(
          finish(
            HealthOutcome.Unverified,
            "port-probe capability not wired into this instance (nebflow.cli.SingleInstanceGuard.connectProbeAccepted not injected)"
          )
        )
      case (Some(port), Some(probe)) =>
        def loop(deadline: Long): IO[HealthTierResult] =
          probe(port).flatMap {
            case false =>
              val now = System.currentTimeMillis()
              if now >= deadline then
                IO.pure(
                  finish(
                    HealthOutcome.Fail,
                    s"nothing accepted on the announced probe port $port within ${deadlineMs}ms"
                  )
                )
              else IO.sleep(pollMs.millis) *> loop(deadline)
            case true =>
              HealthHttp
                .fetch("127.0.0.1", port, math.max(500L, deadlineMs - (System.currentTimeMillis() - started)).toInt)
                .flatMap {
                  case Right(payload) =>
                    IO.pure(
                      finish(
                        HealthOutcome.Pass,
                        s"probe port $port served HTTP 200 ${ProbeEndpoint.Path} (version=${HealthPayload.versionOf(payload).getOrElse("<absent>")})"
                      )
                    )
                  case Left(err) =>
                    val now = System.currentTimeMillis()
                    if now >= deadline then
                      IO.pure(
                        finish(
                          HealthOutcome.Fail,
                          s"probe port $port accepted but did not serve ${ProbeEndpoint.Path} within ${deadlineMs}ms: $err"
                        )
                      )
                    else IO.sleep(pollMs.millis) *> loop(deadline)
                }
          }
        for r <- loop(System.currentTimeMillis() + deadlineMs)
            .handleErrorWith(e =>
              IO.pure(finish(HealthOutcome.Fail, s"port probe raised: ${Option(e.getMessage).getOrElse(e.toString)}"))
            )
        yield r

    end match

  end tierPortServing

  /**
   * 第四档：版本核对——探针载荷的 `version` vs [[expectedVersion]] 的真值。
   * 自己的独立期限：在期限内反复取载荷，直到拿到可核版本。
   */
  private def tierVersionMatch(
    probePort: Option[Int],
    connectProbe: Option[Int => IO[Boolean]],
    expected: Option[String],
    deadlineMs: Long,
    pollMs: Long,
    cachedPayload: Option[Json]
  ): IO[HealthTierResult] =
    val started = System.currentTimeMillis()
    def finish(outcome: HealthOutcome, detail: String): HealthTierResult =
      HealthTierResult(HealthTier.VersionMatch, outcome, detail, System.currentTimeMillis() - started)
    if expected.isEmpty then
      IO.pure(
        finish(
          HealthOutcome.Unverified,
          "expected version unresolvable (no install pointer and no version-bearing package path) — version comparison not performed"
        )
      )
    else if probePort.isEmpty || connectProbe.isEmpty then
      IO.pure(finish(HealthOutcome.Unverified, "no usable loopback probe endpoint — version comparison not performed"))
    else
      def loop(deadline: Long, payload: Option[Json]): IO[HealthTierResult] =
        val observed = payload.flatMap(HealthPayload.versionOf)
        observed match
          case Some(v) if v == expected.get =>
            IO.pure(finish(HealthOutcome.Pass, s"served version ${expected.get} matches the expected version"))
          case Some(v) =>
            IO.pure(finish(HealthOutcome.Fail, s"served version '$v' != expected version '${expected.get}'"))
          case None =>
            val now = System.currentTimeMillis()
            if now >= deadline then
              IO.pure(
                finish(
                  HealthOutcome.Fail,
                  s"no version-bearing health payload from the successor within ${deadlineMs}ms"
                )
              )
            else
              HealthHttp.fetch("127.0.0.1", probePort.get, math.max(500L, deadline - now).toInt).flatMap {
                case Right(p) => IO.sleep(pollMs.millis) *> loop(deadline, Some(p))
                case Left(_) if payload.isDefined => loop(deadline, payload)
                case Left(_) => IO.sleep(pollMs.millis) *> loop(deadline, None)
              }
        end match
      end loop
      loop(System.currentTimeMillis() + deadlineMs, cachedPayload)

    end if

  end tierVersionMatch

  /**
   * **门口自检（旧实例侧，插入点）**：四档递进、逐档独立超时、档间短路。
   *
   * `isAlive` / `readIntent` / `connectProbe` 全是注入点（spec 可无进程驱动四档；
   * 既有 `HotRestart.spawn` 注入先例同款）。
   */
  def atDoor(
    isAlive: IO[Boolean],
    readIntent: IO[Option[HotRestartIntent]],
    expected: Option[String],
    t1HoldMs: Long,
    t2DeadlineMs: Long,
    t3DeadlineMs: Long,
    t4DeadlineMs: Long,
    pollMs: Long,
    connectProbe: Option[Int => IO[Boolean]]
  ): IO[HealthReport] =
    for
      r1 <- tierAlive(isAlive, t1HoldMs, pollMs)
      r2 <-
        if r1.outcome == HealthOutcome.Fail then
          IO.pure(HealthTierResult(HealthTier.DoorReceipt, HealthOutcome.Unverified, "skipped: first tier failed", 0L))
        else tierReceipt(readIntent, t2DeadlineMs, pollMs)
      intent <- readIntent
      probePort = intent.flatMap(_.probePort)
      r3 <-
        if r1.outcome == HealthOutcome.Fail || r2.outcome == HealthOutcome.Fail then
          IO.pure(
            HealthTierResult(HealthTier.PortServing, HealthOutcome.Unverified, "skipped: an earlier tier failed", 0L)
          )
        else tierPortServing(probePort, connectProbe, t3DeadlineMs, pollMs, isAlive)
      cached <- probePort
        .filter(_ => r3.outcome == HealthOutcome.Pass)
        .traverse(p => HealthHttp.fetch("127.0.0.1", p, 1500))
        .map(_.flatMap(_.toOption))
      r4 <-
        if r1.outcome == HealthOutcome.Fail || r2.outcome == HealthOutcome.Fail || r3.outcome == HealthOutcome.Fail then
          IO.pure(
            HealthTierResult(HealthTier.VersionMatch, HealthOutcome.Unverified, "skipped: an earlier tier failed", 0L)
          )
        else tierVersionMatch(probePort, connectProbe, expected, t4DeadlineMs, pollMs, cached)
      report = HealthReport(List(r1, r2, r3, r4))
      _ <- logger.info(s"[hot-restart] health self-check (door): ${report.detail}")
    yield report

  /**
   * **绑定后自检（后继侧）**：交接后短窗口内的判据（G4 触发器②的挂点）。此刻真端口
   * 已在手，故第三/四档对**真端口**取证（真 socket + 真 HTTP + 真载荷），不需要探针
   * 端点；第一/二档在此位置恒真（进程即自己、回执即自己的相位），故直接给出读数。
   */
  def afterBind(
    port: Int,
    monitor: nebflow.llm.ProviderHealthMonitor,
    expected: Option[String],
    t3DeadlineMs: Long,
    t4DeadlineMs: Long,
    pollMs: Long
  ): IO[HealthReport] =
    val started = System.currentTimeMillis()
    def serving: IO[HealthTierResult] =
      def loop(deadline: Long): IO[HealthTierResult] =
        HealthHttp.fetch("127.0.0.1", port, 1500).flatMap {
          case Right(payload) =>
            IO.pure(
              HealthTierResult(
                HealthTier.PortServing,
                HealthOutcome.Pass,
                s"bound port $port served HTTP 200 ${ProbeEndpoint.Path}",
                System.currentTimeMillis() - started
              )
            )
          case Left(err) =>
            val now = System.currentTimeMillis()
            if now >= deadline then
              IO.pure(
                HealthTierResult(
                  HealthTier.PortServing,
                  HealthOutcome.Fail,
                  s"bound port $port did not serve ${ProbeEndpoint.Path} within ${t3DeadlineMs}ms: $err",
                  now - started
                )
              )
            else IO.sleep(pollMs.millis) *> loop(deadline)
        }
      loop(System.currentTimeMillis() + t3DeadlineMs)
    end serving
    def versionTier(served: HealthTierResult, payload: Option[Json]): IO[HealthTierResult] =
      if served.outcome == HealthOutcome.Fail then
        IO.pure(
          HealthTierResult(HealthTier.VersionMatch, HealthOutcome.Unverified, "skipped: an earlier tier failed", 0L)
        )
      else if expected.isEmpty then
        IO.pure(
          HealthTierResult(
            HealthTier.VersionMatch,
            HealthOutcome.Unverified,
            "expected version unresolvable (no install pointer) — version comparison not performed",
            0L
          )
        )
      else
        payload.flatMap(HealthPayload.versionOf) match
          case Some(v) if v == expected.get =>
            IO.pure(
              HealthTierResult(
                HealthTier.VersionMatch,
                HealthOutcome.Pass,
                s"served version ${expected.get} matches the expected version",
                0L
              )
            )
          case Some(v) =>
            IO.pure(
              HealthTierResult(
                HealthTier.VersionMatch,
                HealthOutcome.Fail,
                s"served version '$v' != expected version '${expected.get}'",
                0L
              )
            )
          case None =>
            IO.pure(
              HealthTierResult(
                HealthTier.VersionMatch,
                HealthOutcome.Fail,
                s"health payload carried no version (expected '${expected.get}')",
                0L
              )
            )
    for
      r1 <- IO.pure(
        HealthTierResult(HealthTier.SuccessorAlive, HealthOutcome.Pass, "this process is serving (self)", 0L)
      )
      r2 <- IO.pure(HealthTierResult(HealthTier.DoorReceipt, HealthOutcome.Pass, "handover completed (self)", 0L))
      r3 <- serving
      cached <- (if r3.outcome == HealthOutcome.Pass then HealthHttp.fetch("127.0.0.1", port, 1500)
                 else IO.pure(Left("skipped")))
        .map(_.toOption)
      r4 <- versionTier(r3, cached)
      report = HealthReport(List(r1, r2, r3, r4))
      _ <- logger.info(s"[hot-restart] health self-check (post-bind): ${report.detail}")
    yield report

  end afterBind

  /**
   * 四档逐档独立超时的默认值（毫秒）。上界合计 **50s ≤ 设计建议的 60s**；逐项可注入
   * （system prop `nebflow.hotRestart.health.*`，与既有 `HotRestart.Timing` 同款口径）。
   */
  object Timeouts:
    val T1SuccessorAliveHoldMs: Long = 5000L
    val T2DoorReceiptMs: Long = 10000L
    val T3PortServingMs: Long = 30000L
    val T4VersionMatchMs: Long = 5000L
    val PollMs: Long = 250L

end HealthCheck
