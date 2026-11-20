package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*

import java.util.concurrent.atomic.AtomicLong

/**
 * 三档选路的**实测夹具**（2026-11-04，非 spec）——报告里每个毫秒都能由本文件复核：
 * `sbt "Test/runMain nebflow.core.tools.RouteTriStateMeasureMain"`。
 *
 * 四段测量，跑的都是**生产代码本体**（不是替身）：
 *
 *   1. `real_probe`：真机探测器（本机 Tailscale 当前是 stopped 的真实状态）的判决 + 耗时；
 *   2. `cache`：连续 10 次选路的探测次数（「不重复收税」判据的读数）；
 *   3. `tristate`：三态 A/B/C 的**改前（fail-open = 只看地址形状）vs 改后（探测门控）**
 *      行为对照，改前臂额外给出真拨一次的实测耗时（那就是被省掉的税）；
 *   4. `ipv6`：本机候选面在 IPv6 优先改造后的实际形态（v6 前置 + v4 留槽）。
 *
 * 输出统一是 `MEASURE <name> <json>` 一行一条，便于落盘成证据文件。
 */
object RouteTriStateMeasureMain:

  /** 本批事故里对端的 tailnet 地址（候选集塌缩后唯一活下来的那条）。 */
  private val TailnetCandidate = "http://100.65.49.141:6138"
  private val LanCandidate = "http://192.0.2.145:6138"

  def main(args: Array[String]): Unit =
    DirectRoute.resetForTests()
    realProbe()
    cacheEvidence()
    triState()
    // IPv6 优先的真机读数在 `nebflow.neblink.LocalEndpointReadoutMain`（`NeblinkEndpoint`
    // 是 `private[neblink]`，候选面读数只能从那个包内取——不在本文件里绕开可见性）。
    println("MEASURE done {\"ok\":true}")

  // ---- ① 真机探测 ----

  private def realProbe(): Unit =
    val binary = TailnetProbe.resolveBinary(TailnetProbe.binaryCandidates())
    val t0 = System.nanoTime()
    val r = TailnetProbe.system().probe
    val ms = (System.nanoTime() - t0) / 1000000L
    emit(
      "real_probe",
      io.circe.Json.obj(
        "platform" -> TailnetProbe.platformLabel.asJson,
        "binary" -> binary.getOrElse("<none>").asJson,
        "verdict" -> r.verdict.toString.asJson,
        "reason" -> TailnetVerdict.reasonOf(r.verdict).getOrElse("-").asJson,
        "probeMs" -> ms.asJson,
        "budgetMs" -> TailnetProbe.ProcessBudgetMs.asJson
      )
    )
  end realProbe

  // ---- ② 缓存：连续 N 次只探测 1 次 ----

  private def cacheEvidence(): Unit =
    val calls = new AtomicLong(0L)
    val inner = new TailnetProbe:
      def probe: ProbeResult =
        calls.incrementAndGet()
        ProbeResult(TailnetVerdict.Unavailable(RouteReason.TailscaleStopped), cached = false, ms = 0L)
    val cached = TailnetProbe.cached(inner)
    val perCall = (1 to 10).map { _ =>
      val t0 = System.nanoTime()
      val plan = DirectRoute.planSync("KAI", List(TailnetCandidate), cached)
      val ms = (System.nanoTime() - t0) / 1000000L
      plan.probeMs.max(ms)
    }
    emit(
      "cache",
      io.circe.Json.obj(
        "calls" -> 10.asJson,
        "realProbes" -> calls.get().asJson,
        "cachedHits" -> perCall.count(_ == 0L).asJson,
        "perCallProbeMs" -> perCall.asJson
      )
    )
    // 生产口径的真机冷探（含 spawn）与随后 9 次命中，各一次读数。
    val prod = TailnetProbe.cached(TailnetProbe.system())
    val t0 = System.nanoTime()
    val first = prod.probe
    val firstMs = (System.nanoTime() - t0) / 1000000L
    val t1 = System.nanoTime()
    val second = prod.probe
    val secondMs = (System.nanoTime() - t1) / 1000000L
    emit(
      "cache_prod",
      io.circe.Json.obj(
        "coldMs" -> firstMs.asJson,
        "coldCached" -> first.cached.asJson,
        "warmMs" -> secondMs.asJson,
        "warmCached" -> second.cached.asJson,
        "sameVerdict" -> (first.verdict == second.verdict).asJson
      )
    )
  end cacheEvidence

  // ---- ③ 三态：改前 vs 改后 ----

  private def triState(): Unit =
    val states = List(
      ("A", "tailscale 停", Parse.parse(stoppedJson), TailnetProbe.Unprobed),
      ("B", "tailscale 起且 peer 可达", Parse.parse(runningJson(true)), TailnetProbe.Unprobed),
      ("C", "tailscale 起但 peer 不在线", Parse.parse(runningJson(false)), TailnetProbe.Unprobed)
    )
    states.foreach { case (id, label, verdict, _) =>
      // 改前臂：无探测器（fail-open）= 只看地址形状，tailnet 候选照旧排第一 ⇒ 白付一次探测。
      val beforePlan = DirectRoute.planSync("KAI", List(TailnetCandidate), TailnetProbe.Unprobed)
      val t0 = System.nanoTime()
      val dialed = P2pReachability
        .probeConcurrently(beforePlan.attempt, P2pProbePolicy.ProbeConnectTimeoutMs)
        .attempt
        .unsafeRunSync()
      val beforeMs = (System.nanoTime() - t0) / 1000000L
      // 改后臂：注入该态的探测判决 ⇒ 门控生效；tailnet 候选被跳过时**零拨号**。
      val afterProbe = TailnetProbe.stub(verdict)
      val afterPlan = DirectRoute.planSync("KAI", List(TailnetCandidate), afterProbe)
      val t1 = System.nanoTime()
      val afterDialed: Option[Either[Throwable, Option[String]]] =
        if afterPlan.attempt.isEmpty then None
        else
          Some(
            P2pReachability
              .probeConcurrently(afterPlan.attempt, P2pProbePolicy.ProbeConnectTimeoutMs)
              .attempt
              .unsafeRunSync()
          )
      val afterMs = (System.nanoTime() - t1) / 1000000L
      emit(
        s"tristate_$id",
        io.circe.Json.obj(
          "state" -> label.asJson,
          "verdict" -> verdict.toString.asJson,
          "before" -> io.circe.Json.obj(
            "via" -> beforePlan.via.asJson,
            "candidatesDialed" -> beforePlan.attempt.size.asJson,
            "reasons" -> beforePlan.reasons.asJson,
            "dialed" -> describe(dialed).asJson,
            "paidMs" -> beforeMs.asJson,
            "logLine" -> beforePlan.logLine("KAI").asJson
          ),
          "after" -> io.circe.Json.obj(
            "via" -> afterPlan.via.asJson,
            "candidatesDialed" -> afterPlan.attempt.size.asJson,
            "reasons" -> afterPlan.reasons.asJson,
            "dialed" -> afterDialed.map(describe).getOrElse("<none: 0ms, no probe paid>").asJson,
            "paidMs" -> afterMs.asJson,
            "logLine" -> afterPlan.logLine("KAI").asJson
          )
        )
      )
    }
    // LAN 直拨零回归：tailscale 停时 LAN 候选照旧被拨（via=direct）。
    val lanPlan =
      DirectRoute.planSync("KAI", List(TailnetCandidate, LanCandidate), TailnetProbe.stub(Parse.parse(stoppedJson)))
    emit(
      "tristate_lan_regression",
      io.circe.Json.obj(
        "attempt" -> lanPlan.attempt.asJson,
        "via" -> lanPlan.via.asJson,
        "reasons" -> lanPlan.reasons.asJson
      )
    )
  end triState

  // ---- ④ IPv6 真机读数见 nebflow.neblink.LocalEndpointReadoutMain ----

  // ---- 工具 ----

  private def describe(r: Either[Throwable, Option[String]]): String = r match
    case Left(e) => s"error:${e.getClass.getSimpleName}"
    case Right(None) => "no-answer"
    case Right(Some(w)) => s"winner:$w"

  private def emit(name: String, json: io.circe.Json): Unit =
    println(s"MEASURE $name ${json.noSpaces}")

  // ---- 三态夹具（`tailscale status --json` 的真实字段形态）----

  private val stoppedJson: String =
    """{"Version":"1.104.2","TUN":true,"BackendState":"Stopped","HaveNodeKey":true,
      |"TailscaleIPs":null,"Self":{"ID":"","HostName":"Mashiro's MacBook Pro","TailscaleIPs":null,"Online":false},
      |"Peer":{}}""".stripMargin

  private def runningJson(peerOnline: Boolean): String =
    s"""{"Version":"1.104.2","BackendState":"Running",
       |"TailscaleIPs":["100.127.149.106","fd7a:115c:a1e0::1"],
       |"Self":{"ID":"nSelf","HostName":"host-a","Online":true,
       |        "TailscaleIPs":["100.127.149.106","fd7a:115c:a1e0::1"]},
       |"Peer":{"nodekey:aaaa":{"HostName":"kai","Online":$peerOnline,
       |        "TailscaleIPs":["100.65.49.141","fd7a:115c:a1e0::2"]}}}""".stripMargin
end RouteTriStateMeasureMain
