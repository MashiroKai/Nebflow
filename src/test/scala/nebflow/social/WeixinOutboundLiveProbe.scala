package nebflow.social

import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

/**
 * LIVE full-chain probe for the weixin outbound leg (weixin-outbound batch).
 * NOT part of the offline suite — run explicitly:
 *
 * {{{
 * sbt "Test/runMain nebflow.social.WeixinOutboundLiveProbe"
 * }}}
 *
 * Chain under probe (batch brief: fixture 回复自引擎侧触发→shim→iLink 投递读数):
 *
 *   engine adapter (WeixinOutboundAdapter.sendLive — the REAL production path,
 *   cold token + cold sidecar_url read) → real shim process (spawned node
 *   shim.mjs on a loopback port, shared token via file, iLink base pinned to a
 *   local stub) → stub iLink (records the sendmessage wire) → delivery reading.
 *
 * Egress discipline: the ONLY network this probe touches is loopback — the
 * shim's WEIXIN_SIDECAR_ILINK_BASE points at the in-process stub, so the real
 * ilinkai endpoint is never reached. The real-outbound verification against
 * the filehelper target is the AUTHOR's call and is NOT executed here.
 */
object WeixinOutboundLiveProbe:

  def main(args: Array[String]): Unit =
    banner("weixin-outbound LIVE chain probe — engine → shim → stub iLink (loopback only)")

    // ── ① stub iLink: records every sendmessage, answers a uint64-bearing ret=0 ──
    val recorded = new java.util.concurrent.CopyOnWriteArrayList[String]
    val stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    stub.createContext(
      "/ilink/bot/sendmessage",
      (exchange: HttpExchange) =>
        try
          val auth = Option(exchange.getRequestHeaders.getFirst("Authorization"))
            .map(a => s"Bearer ***(len ${a.length}B)")
            .getOrElse("-")
          val body = new String(exchange.getRequestBody.readAllBytes, StandardCharsets.UTF_8)
          recorded.add(
            s"POST ${exchange.getRequestURI}  auth=$auth  authType=${exchange.getRequestHeaders.getFirst("AuthorizationType")}" +
              s"  uin=${Option(exchange.getRequestHeaders.getFirst("X-WECHAT-UIN")).map(l => s"${l}B").getOrElse("-")}" +
              s"  app=${exchange.getRequestHeaders.getFirst("iLink-App-Id")}/${exchange.getRequestHeaders.getFirst("iLink-App-ClientVersion")}\n  body=$body")
          // RAW answer: a JS/Java number literal would already lose uint64 precision
          val resp = "{\"ret\":0,\"message_id\":987654321098765432}".getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.set("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, resp.length.toLong)
          exchange.getResponseBody.write(resp)
        finally exchange.close(),
    )
    stub.start()
    val stubBase = s"http://127.0.0.1:${stub.getAddress.getPort}"
    readout("stub iLink up", stubBase)

    // ── ② temp engine home: token mint + card (sidecar_url patched once the shim port is known) ──
    val root = os.temp.dir(prefix = "nb-weixin-out-live-")
    val token = WeixinOutboundAdapter.ensureToken(root).unsafeRunSync()
      .fold(e => throw new IllegalStateException(s"token mint failed: $e"), identity)
    readout("engine token minted", s"${WeixinOutboundAdapter.tokenPath(root)} (owner-only, ${token.length}B)")
    SocialChannels.save(
      root,
      WeixinIlinkBridgePlugin.Name,
      io.circe.parser
        .parse(
          """{"enabled":true,"fields":{"ilink_bot_id":"liveprobe@im.bot","ilink_user_id":"wx_liveprobe","bot_token":"engine-side-never-used","sidecar_url":""}}"""
        )
        .toOption
        .get,
    ) match
      case Right(_) => readout("channel card saved", "weixin-ilink enabled+verified")
      case Left(err) => throw new IllegalStateException(s"card save failed: $err")

    // ── ③ fake host state: ONE mirrored account (the bot_token source) ──
    val stateDir = os.temp.dir(prefix = "nb-weixin-out-live-openclaw-")
    val acctDir = stateDir / "openclaw-weixin" / "accounts"
    os.makeDir.all(acctDir)
    os.write.over(acctDir / "liveprobe-im-bot.json",
      """{"token":"host-account-token-0123456789abcdef","userId":"wx_liveprobe","savedAt":"2026-10-07T00:00:00.000Z"}""")
    os.write.over(stateDir / "openclaw-weixin" / "accounts.json", """["liveprobe-im-bot"]""")
    readout("fake host account", s"$stateDir (1 account: liveprobe-im-bot)")

    // ── ④ spawn the REAL shim on a free loopback port ──
    val shimPort = freePort()
    val worktree = os.pwd
    val shim = Process(
      Seq("node", (worktree / "tools" / "weixin-sidecar" / "shim.mjs").toString, "--port", shimPort.toString),
      cwd = Some(worktree.toIO),
      "WEIXIN_SEND_TOKEN_FILE" -> WeixinOutboundAdapter.tokenPath(root).toString,
      "WEIXIN_SIDECAR_ILINK_BASE" -> stubBase,
      "OPENCLAW_STATE_DIR" -> stateDir.toString,
      "WEIXIN_SIDECAR_SEND_BACKOFF_MS" -> "200",
    ).run()
    val shimBase = s"http://127.0.0.1:$shimPort"
    try
      SocialChannels.save(
        root,
        WeixinIlinkBridgePlugin.Name,
        io.circe.parser.parse(s"""{"fields":{"sidecar_url":"$shimBase"}}""").toOption.get,
      ) match
        case Right(_) => readout("sidecar_url", shimBase)
        case Left(err) => throw new IllegalStateException(err.toString)

      awaitHealthy(shimBase, deadline = 10.seconds.fromNow)
      readout("shim healthz", "ok (state=idle, login leg untouched)")

      // ── ⑤ THE CHAIN: engine adapter → shim → stub iLink ──
      val res = WeixinOutboundAdapter.sendLive(root)("filehelper", "weixin-outbound fixture reply — engine→shim→iLink chain probe").unsafeRunSync()
      readout("ENGINE reading", s"ok=${res.ok} delivered=${res.delivered} id=${res.messageId.getOrElse("-")} code=${res.code} reason=${res.reason}")
      if recorded.isEmpty then throw new IllegalStateException("the stub recorded NOTHING — the chain did not reach iLink")
      readout("WIRE at stub iLink", s"${recorded.size} sendmessage call(s):")
      recorded.asScala.toList.zipWithIndex.foreach((line, i) => println(f"    [$i] $line"))

      // ── ⑥ negative probe: a MISMATCHING token must be refused by the shim ──
      val root2 = os.temp.dir(prefix = "nb-weixin-out-live-wrongtok-")
      os.makeDir.all(WeixinOutboundAdapter.tokenPath(root2) / os.up)
      os.write.over(WeixinOutboundAdapter.tokenPath(root2), "a-completely-different-token")
      SocialChannels.save(
        root2, WeixinIlinkBridgePlugin.Name,
        io.circe.parser.parse(s"""{"fields":{"sidecar_url":"$shimBase"}}""").toOption.get)
      val res2 = WeixinOutboundAdapter.sendLive(root2)("filehelper", "should never leave the shim").unsafeRunSync()
      readout("NEGATIVE reading (wrong token)", s"ok=${res2.ok} code=${res2.code} reason=${res2.reason}")
      if res2.ok || res2.reason != "shim_auth_rejected" then
        throw new IllegalStateException(s"the shim accepted a wrong token?! $res2")
      if recorded.size != 1 then
        throw new IllegalStateException(s"the refused send leaked to iLink (${recorded.size} calls)")

      banner("LIVE CHAIN VERDICT: delivered — engine adapter → shim /send → iLink wire, all faces as designed")
    finally
      shim.destroy()
      Thread.sleep(300)
      stub.stop(0)
      println(s"    [cleanup] shim stopped, stub stopped; temp roots left for inspection: $root")

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    try s.getLocalPort
    finally s.close()

  private def awaitHealthy(base: String, deadline: Deadline): Unit =
    val client = java.net.http.HttpClient.newHttpClient()
    while deadline.hasTimeLeft do
      try
        val resp = client.send(
          java.net.http.HttpRequest.newBuilder(java.net.URI.create(s"$base/healthz")).GET().build(),
          java.net.http.HttpResponse.BodyHandlers.ofString())
        if resp.statusCode() == 200 then return
      catch case _: Exception => ()
      Thread.sleep(200)
    throw new IllegalStateException(s"shim never became healthy at $base")

  private def banner(s: String): Unit = println(s"\n══════ $s ══════")
  private def readout(k: String, v: String): Unit = println(s"  ▸ $k: $v")
