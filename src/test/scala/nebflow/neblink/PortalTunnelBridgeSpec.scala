package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.neblink.PortalWireCodec.*
import nebflow.shared.PathUtil
import nebflow.shared.TunnelOriginMark
import org.http4s.{Header, Headers, HttpApp, Request, Response, Status}
import org.typelevel.ci.CIString

import java.net.http.WebSocket
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue}

import scala.concurrent.duration.*

/**
 * Portal bridge unit tests (phase 3a A4): the per-connection
 * [[PortalTunnelBridge]] against a fake relay WebSocket and a stub lifted
 * HttpApp — no network, no ember. Pins the judgment-relevant behaviors:
 *
 *  - http_request runs the lifted app in-process and answers
 *    http_response + body_frame with canonical proto:1 bytes (an empty
 *    response body still ends the cycle with exactly one empty `more=false`
 *    terminator — the ingress closes the browser stream on that frame alone);
 *  - the request body streams through the per-requestId channel (seq order,
 *    End semantics; POST echo round-trip; out-of-order seq drops the cycle);
 *  - the response body is sliced at the chunk constant with more=true on all
 *    but the last frame;
 *  - the tunnel guard port is keyed by the envelope IP and a rejection
 *    answers a 429-shaped http_response (criterion 5, host half);
 *  - ConsoleTicketMint mints a one-time ticket (redeemed-then-destroyed,
 *    budget exhaustion => rate_limited);
 *  - BindingControl revoked flips the persisted binding state and revokes
 *    live console tickets.
 */
class PortalTunnelBridgeSpec extends CatsEffectSuite:

  private val Rid = "0a1b2c3d-4e5f-4678-9abc-def012345678"
  private val MintId = "1f2e3d4c-5b6a-4978-8877-6a5b4c3d2e1f"

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  /** Envelope IPs the guard port observed (cleared per test). */
  private val observedIps = new ConcurrentLinkedQueue[String]()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    observedIps.clear()
    savedRoot = PathUtil.dataRoot
    tmpDir = Files.createTempDirectory("nb-portal-bridge-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── harness ────────────────────────────────────────────────────────────

  /** Records every text sent to the "relay"; futures complete immediately. */
  private final class FakeWebSocket extends WebSocket:
    val sent = new ConcurrentLinkedQueue[String]()
    @volatile var aborted = false

    override def sendText(data: CharSequence, last: Boolean): CompletableFuture[WebSocket] =
      sent.add(data.toString)
      CompletableFuture.completedFuture(this)

    override def sendBinary(data: java.nio.ByteBuffer, last: Boolean): CompletableFuture[WebSocket] =
      CompletableFuture.completedFuture(this)

    override def sendClose(code: Int, reason: String): CompletableFuture[WebSocket] =
      CompletableFuture.completedFuture(this)
    override def request(n: Long): Unit = ()

    override def sendPing(data: java.nio.ByteBuffer): CompletableFuture[WebSocket] =
      CompletableFuture.completedFuture(this)

    override def sendPong(data: java.nio.ByteBuffer): CompletableFuture[WebSocket] =
      CompletableFuture.completedFuture(this)
    override def getSubprotocol: String = ""
    override def isOutputClosed: Boolean = false
    override def isInputClosed: Boolean = false
    override def abort(): Unit = aborted = true

  end FakeWebSocket

  private def sentTexts(fake: FakeWebSocket): List[String] =
    val out = scala.collection.mutable.ListBuffer.empty[String]
    fake.sent.forEach(out += _)
    out.toList

  private def waitUntil(timeout: FiniteDuration)(cond: IO[Boolean]): IO[Boolean] =
    IO.monotonic.flatMap { start =>
      def loop: IO[Boolean] =
        cond.flatMap { ok =>
          if ok then IO.pure(true)
          else
            IO.monotonic.flatMap { now =>
              if now - start > timeout then IO.pure(false) else IO.sleep(50.millis) *> loop
            }
        }
      loop
    }

  /** Stub lifted app: constant body + the request method echoed as a header. */
  private def constApp(body: String): HttpApp[IO] = HttpApp[IO] { (req: Request[IO]) =>
    IO(
      Response[IO](status = Status.Ok)
        .withHeaders(Headers(Header.Raw(CIString("X-Echo-Method"), req.method.name)))
        .withBodyStream(Stream.emits(body.getBytes(UTF_8)))
    )
  }

  /** Stub lifted app: echoes the request body back. */
  private val echoApp: HttpApp[IO] = HttpApp[IO] { (req: Request[IO]) =>
    req.body.compile.toList.map { bytes =>
      Response[IO](status = Status.Ok)
        .withHeaders(Headers(Header.Raw(CIString("X-Echo-Method"), req.method.name)))
        .withBodyStream(Stream.emits(bytes))
    }
  }

  private def mkDeps(faces: PortalHostFaces): PortalTunnelDeps =
    mkDeps(faces, nfRevocations = new ConcurrentLinkedQueue[Integer]())

  /** Phase 3b: the nf-ticket revocation port records each invocation (count assertions). */
  private def mkDeps(faces: PortalHostFaces, nfRevocations: ConcurrentLinkedQueue[Integer]): PortalTunnelDeps =
    new PortalTunnelDeps(
      faces,
      ConsoleTicketStore.unsafeCreate(),
      PortalBindingState.unsafeCreate(),
      IO.pure(DeviceIdentity("qa-device", "qa-host", "macos")),
      IO(nfRevocations.add(1)).as(0)
    )

  private def withBridge[A](
    app: HttpApp[IO],
    guard: Option[PortalHostFaces.TunnelGuard] = None,
    nfRevocations: ConcurrentLinkedQueue[Integer] = new ConcurrentLinkedQueue[Integer]()
  )(body: (PortalTunnelBridge, FakeWebSocket, PortalTunnelDeps) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        faces <- IO(new PortalHostFaces(gatewayPort = 1))
        _ = faces.bindHttpApp(app)
        _ = guard.foreach(faces.bindTunnelGuard)
        deps = mkDeps(faces, nfRevocations)
        fake = new FakeWebSocket
        bridge = new PortalTunnelBridge(deps, new RelayFrameSender(fake))
        fiber <- bridge.run.start
        out <- body(bridge, fake, deps).guarantee(bridge.teardown() *> fiber.cancel)
      yield out
    }

  private def decodeSent(fake: FakeWebSocket): List[PortalFrame] =
    sentTexts(fake).flatMap(t => PortalWireCodec.decode(t).toOption)

  private def httpCycles(fake: FakeWebSocket): List[(String, Int)] =
    decodeSent(fake).collect { case HttpResponse(rid, status, _, _) => (rid, status) }

  private def bodyFrames(fake: FakeWebSocket, requestId: String): List[BodyFrame] =
    decodeSent(fake).collect { case f: BodyFrame if f.requestId == requestId => f }

  private def assembledBody(fake: FakeWebSocket, requestId: String): Array[Byte] =
    bodyFrames(fake, requestId).map(_.data).foldLeft(Array.emptyByteArray)(_ ++ _)

  // ── http face ──────────────────────────────────────────────────────────

  test("http_request GET: in-process run answers http_response + one body_frame, canonical proto:1 bytes"):
    withBridge(constApp("ok")) { (bridge, fake, _) =>
      bridge.enqueueFrame(HttpRequest(Rid, "GET", "/api/echo?q=1", Vector("x-forwarded-for" -> "203.0.113.9")))
      for
        ok <- waitUntil(5.seconds)(IO(bodyFrames(fake, Rid).size == 1))
        _ = assert(ok, s"no complete answer; sent=${sentTexts(fake)}")
        cycles = httpCycles(fake)
        _ = assertEquals(cycles, List((Rid, 200)))
        head = sentTexts(fake).head
        _ = assertEquals(
          io.circe.parser.parse(head).flatMap(_.asObject.map(_.keys.toList).toRight("not obj")).toOption.get,
          List("type", "proto", "requestId", "status", "headers"),
          s"http_response key order drifted: $head"
        )
        _ = assert(head.contains("\"proto\":1"), s"proto envelope missing: $head")
        _ = assert(head.contains("x-echo-method"), s"app-produced header not carried: $head")
        frames = bodyFrames(fake, Rid)
        _ = assertEquals(frames.map(f => (f.seq, f.more)).toList, List((0, false)))
        _ = assertEquals(new String(frames.head.data, UTF_8), "ok")
      yield ()
      end for
    }

  test("empty response body still terminates the cycle with one empty more=false body_frame"):
    withBridge(HttpApp[IO](_ => IO(Response[IO](status = Status.NoContent)))) { (bridge, fake, _) =>
      bridge.enqueueFrame(HttpRequest(Rid, "GET", "/api/none", Vector("x-forwarded-for" -> "203.0.113.9")))
      for
        ok <- waitUntil(5.seconds)(IO(httpCycles(fake).contains((Rid, 204))))
        _ = assert(ok, s"no http_response; sent=${sentTexts(fake)}")
        _ <- waitUntil(5.seconds)(IO(bodyFrames(fake, Rid).nonEmpty))
        frames = bodyFrames(fake, Rid)
        _ = assertEquals(frames.length, 1, s"expected exactly one terminator; got ${frames.length}")
        _ = assert(!frames.head.more, "the lone frame must be the more=false terminator")
        _ = assert(frames.head.data.isEmpty, "the terminator carries no bytes")
        _ = assertEquals(frames.head.seq, 0)
      yield ()
    }

  // T-10 R1b regression: BOTH frames enqueue before the consumer runs a
  // single action. The pre-fix dispatch evaluated feedBodyFrame eagerly at
  // enqueue time (WS reader thread) — the body cycle's lookup ran before the
  // http_request's registration and the cycle was dropped, deterministically,
  // whenever the frames arrived in one read batch. The dispatch must defer so
  // the consumer runs register-then-feed in wire order.
  test("body_frame enqueued back-to-back with its http_request is not dropped (deferred dispatch)"):
    for
      faces <- IO(new PortalHostFaces(gatewayPort = 1))
      _ = faces.bindHttpApp(echoApp)
      deps = mkDeps(faces)
      fake = new FakeWebSocket
      bridge = new PortalTunnelBridge(deps, new RelayFrameSender(fake))
      // Enqueue everything BEFORE the consumer starts.
      _ = bridge.enqueueFrame(
        HttpRequest(
          Rid,
          "POST",
          "/api/echo",
          Vector("x-forwarded-for" -> "203.0.113.9", "content-type" -> "text/plain")
        )
      )
      _ = bridge.enqueueFrame(BodyFrame(Rid, 0, more = false, "hello".getBytes(UTF_8)))
      fiber <- bridge.run.start
      _ <- IO.sleep(1.seconds)
      _ = assert(
        new String(assembledBody(fake, Rid), UTF_8) == "hello",
        s"the body must reach the app via its registered cycle; sent=${sentTexts(fake)}"
      )
      _ <- bridge.teardown() *> fiber.cancel
    yield ()

  test("http_request POST: request body streams through the channel and echoes back byte-exactly"):
    withBridge(echoApp) { (bridge, fake, _) =>
      bridge.enqueueFrame(
        HttpRequest(
          Rid,
          "POST",
          "/api/echo",
          Vector("x-forwarded-for" -> "203.0.113.9", "content-type" -> "text/plain")
        )
      )
      bridge.enqueueFrame(BodyFrame(Rid, 0, more = true, "hel".getBytes(UTF_8)))
      bridge.enqueueFrame(BodyFrame(Rid, 1, more = false, "lo".getBytes(UTF_8)))
      for
        ok <- waitUntil(5.seconds)(IO(bodyFrames(fake, Rid).size == 1))
        _ = assert(ok, s"echo incomplete; sent=${sentTexts(fake)}")
        _ = assertEquals(new String(assembledBody(fake, Rid), UTF_8), "hello")
        _ = assert(sentTexts(fake).head.contains("POST"), s"app must see a POST: ${sentTexts(fake).head}")
      yield ()
    }

  test("http_request POST with out-of-order body seq drops the cycle (no partial processing)"):
    withBridge(echoApp) { (bridge, fake, _) =>
      bridge.enqueueFrame(HttpRequest(Rid, "POST", "/api/echo", Vector("x-forwarded-for" -> "203.0.113.9")))
      // seq jumps 0 -> 2: the cycle must be dropped, NOT fed a spliced body.
      bridge.enqueueFrame(BodyFrame(Rid, 2, more = false, "xx".getBytes(UTF_8)))
      for
        _ <- IO.sleep(300.millis)
        _ = assert(httpCycles(fake).isEmpty, s"dropped cycle must not answer; sent=${sentTexts(fake)}")
      yield ()
    }

  test("bodyful POST with ZERO body_frames is rescued by the first-frame backstop (empty body proceeds)"):
    withBridge(echoApp) { (bridge, fake, _) =>
      // Contract 2.3 allows "0..n" body_frames: an ingress that sends zero
      // frames for an empty POST body would park the handler on the channel
      // forever — the backstop ends it with an empty body instead.
      bridge.firstBodyFrameTimeout = 400.millis
      bridge.enqueueFrame(HttpRequest(Rid, "POST", "/api/echo", Vector("x-forwarded-for" -> "203.0.113.9")))
      for
        ok <- waitUntil(5.seconds)(IO(httpCycles(fake).contains((Rid, 200))))
        _ = assert(
          ok,
          s"the empty-body POST must complete via the backstop instead of hanging; sent=${sentTexts(fake)}"
        )
      yield ()
    }

  test("mailbox stays live after ws traffic: an unknown-cycle ws_frame does not stall subsequent cycles"):
    withBridge(echoApp) { (bridge, fake, _) =>
      // ws handling is a non-blocking inbox offer (per-cycle pump fibers own
      // the blocking sends) — ws traffic for an unknown cycle must not delay
      // an unrelated mint cycle behind it.
      bridge.enqueueFrame(WsFrame("1631a2b0-9c8d-4e7f-8a9b-c0d1e2f3a4b5", "text", "x".getBytes(UTF_8), true))
      bridge.enqueueFrame(ConsoleTicketMint(Rid, MintId, "logto|user-42", "portal"))
      for
        ok <- waitUntil(5.seconds)(IO(sentTexts(fake).exists(_.contains("ConsoleTicketResult"))))
        _ = assert(
          ok,
          s"the mint result must arrive despite preceding ws traffic; sent=${sentTexts(fake)}"
        )
      yield ()
    }

  test("pump-failure drop is self-safe: aborts the local WS first, cancels only the OTHER pump"):
    // The hazard this pins: a pump fiber's failure path used to run a drop
    // that canceled BOTH pumps — including the caller — with the local
    // abort() sequenced AFTER the cancels. Under either CE3 self-cancel
    // semantic the abort could be bypassed, leaking the loopback socket to
    // the gateway's idle timeout (the cycle had already left the registry,
    // so the connection teardown sweep could not reach it either).
    withBridge(echoApp) { (bridge, _, _) =>
      def mkFlagFiber =
        for
          flag <- cats.effect.Ref.of[IO, Boolean](false)
          fiber <- IO.never[Unit].guaranteeCase(oc => IO.whenA(oc.isCanceled)(flag.set(true))).start
        yield (flag, fiber)
      for
        (downFlag, downFiber) <- mkFlagFiber
        (upFlag, upFiber) <- mkFlagFiber
        localFake = new FakeWebSocket
        cycle = new PortalTunnelBridge.WsCycle("self-drop-down")
        _ = cycle.local = localFake
        _ = cycle.downPump.set(Some(downFiber))
        _ = cycle.upPump.set(Some(upFiber))
        _ = bridge.wsCycles.put("self-drop-down", cycle)
        // Downlink pump failure: drop runs to COMPLETION (old code could hang
        // on self-cancel), aborts the local socket, cancels only the uplink.
        _ <- bridge.dropFromDownPump(cycle, "unit: downlink send failure")
        _ = assert(localFake.aborted, "the local WS must be aborted on the pump failure path")
        _ = assert(!bridge.wsCycles.containsKey("self-drop-down"), "the cycle must leave the registry")
        upCanceled <- waitUntil(2.seconds)(upFlag.get)
        _ = assert(upCanceled, "the OTHER pump must be canceled by the failure drop")
        _ <- IO.sleep(200.millis)
        downCanceledEarly <- downFlag.get
        _ = assert(!downCanceledEarly, "a pump's own failure drop must NEVER cancel itself")
        // Same discipline from the uplink side.
        localFake2 = new FakeWebSocket
        cycle2 = new PortalTunnelBridge.WsCycle("self-drop-up")
        (downFlag2, downFiber2) <- mkFlagFiber
        _ = cycle2.local = localFake2
        _ = cycle2.downPump.set(Some(downFiber2))
        _ = bridge.wsCycles.put("self-drop-up", cycle2)
        _ <- bridge.dropFromUpPump(cycle2, "unit: uplink send failure")
        _ = assert(localFake2.aborted, "uplink failure must abort the local WS too")
        _ <- IO.sleep(200.millis)
        // cycle2.upPump was never set: nothing to self-cancel — the call must
        // still have completed (reached this point) and unregistered.
        _ = assert(!bridge.wsCycles.containsKey("self-drop-up"))
        // Cleanup the deliberately-uncanceled fibers.
        _ <- downFiber.cancel *> upFiber.cancel *> downFiber2.cancel
      yield ()
      end for
    }

  test("response body larger than the chunk constant slices into ordered body_frames (seq 0..n, more=false last)"):
    val big = ("a" * (PortalTunnelBridge.BodyChunkBytes + 10)).getBytes(UTF_8)
    val bigApp: HttpApp[IO] = HttpApp[IO] { _ =>
      IO(Response[IO](status = Status.Ok).withBodyStream(Stream.emits(big)))
    }
    withBridge(bigApp) { (bridge, fake, _) =>
      bridge.enqueueFrame(HttpRequest(Rid, "GET", "/api/big", Vector("x-forwarded-for" -> "203.0.113.9")))
      for
        ok <- waitUntil(5.seconds)(IO(bodyFrames(fake, Rid).size == 2))
        _ = assert(ok, s"expected exactly 2 body_frames; sent=${sentTexts(fake)}")
        frames = bodyFrames(fake, Rid)
        _ = assertEquals(frames.map(_.seq).toList, List(0, 1))
        _ = assertEquals(frames.map(_.more).toList, List(true, false))
        _ = assert(java.util.Arrays.equals(assembledBody(fake, Rid), big), "sliced body drifted from the source")
      yield ()
    }

  test("unbound http_app face answers a 503-shaped http_response (bridge wired without the slice)"):
    Dispatcher.parallel[IO].use { dispatcher =>
      val faces = new PortalHostFaces(gatewayPort = 1) // no bindHttpApp: server not assembled
      val deps = mkDeps(faces)
      val fake = new FakeWebSocket
      val bridge = new PortalTunnelBridge(deps, new RelayFrameSender(fake))
      for
        fiber <- bridge.run.start
        _ = bridge.enqueueFrame(HttpRequest(Rid, "GET", "/api/x", Vector.empty))
        ok <- waitUntil(5.seconds)(IO(httpCycles(fake).contains((Rid, 503))))
          .guarantee(fiber.cancel *> bridge.teardown())
        _ = assert(ok, s"expected a 503 answer; sent=${sentTexts(fake)}")
      yield ()
    }

  // ── tunnel guard (criterion 5, host half) ──────────────────────────────

  test("tunnel guard port is keyed by the envelope IP; a rejection answers 429"):
    withBridge(
      echoApp,
      guard = Some(
        PortalHostFaces.TunnelGuard(
          observe = ip => IO(observedIps.add(ip)).void,
          check = _ => IO.pure(Some("tunnel-per-ip-cap"))
        )
      )
    ) { (bridge, fake, _) =>
      bridge.enqueueFrame(HttpRequest(Rid, "GET", "/api/x", Vector("x-forwarded-for" -> "203.0.113.9")))
      for
        ok <- waitUntil(5.seconds)(IO(httpCycles(fake).contains((Rid, 429))))
        _ = assert(ok, s"expected a 429 answer; sent=${sentTexts(fake)}")
        _ = assert(sentTexts(fake).exists(_.contains("429")))
        _ = assert(observedIps.contains("203.0.113.9"), s"guard must observe the ENVELOPE IP; observed=$observedIps")
        _ = assert(!observedIps.contains("unknown"), "a present XFF must not degrade to the unknown bucket")
        _ = assert(!httpCycles(fake).contains((Rid, 200)), "a rejected request must never reach the lifted app")
      yield ()
    }

  test("absent x-forwarded-for degrades to the unknown bucket (direct/dev shape)"):
    withBridge(
      echoApp,
      guard = Some(
        PortalHostFaces.TunnelGuard(observe = ip => IO(observedIps.add(ip)).void, check = _ => IO.pure(None))
      )
    ) { (bridge, fake, _) =>
      bridge.enqueueFrame(HttpRequest(Rid, "GET", "/api/x", Vector.empty))
      for
        ok <- waitUntil(5.seconds)(IO(httpCycles(fake).contains((Rid, 200))))
        _ = assert(ok)
        _ = assert(observedIps.contains("unknown"), s"observed=$observedIps")
      yield ()
    }

  // ── phase 3c: contract v1.1 faces (setCookie relay + ws_open headers) ──

  test("two Set-Cookie headers relay as two setCookie array elements — comma-join prohibited (v1.1 D8)"):
    val cookieApp = HttpApp[IO] { _ =>
      IO(
        Response[IO](status = Status.Ok).withHeaders(
          Headers(
            Header.Raw(
              CIString("Set-Cookie"),
              "nebflow_session=s3cr3t; Max-Age=43200; Path=/; SameSite=Lax; Secure; HttpOnly"
            ),
            Header.Raw(CIString("Set-Cookie"), "theme=dark; Path=/"),
            Header.Raw(CIString("X-Other"), "v1"),
            Header.Raw(CIString("X-Other"), "v2")
          )
        )
      )
    }
    withBridge(cookieApp) { (bridge, fake, _) =>
      bridge.enqueueFrame(HttpRequest(Rid, "GET", "/api/login", Vector("x-forwarded-for" -> "203.0.113.9")))
      for
        ok <- waitUntil(5.seconds)(IO(httpCycles(fake).nonEmpty))
        _ = assert(ok, s"no http_response; sent=${sentTexts(fake)}")
        head = decodeSent(fake).collectFirst { case r: HttpResponse => r }.get
        // Each occurrence rides its OWN array element, byte-exact, first-seen
        // order (the redemption 303 face: a comma-joined Set-Cookie would
        // forge one merged cookie and break the browser jar).
        _ = assertEquals(
          head.setCookie,
          Vector(
            "nebflow_session=s3cr3t; Max-Age=43200; Path=/; SameSite=Lax; Secure; HttpOnly",
            "theme=dark; Path=/"
          )
        )
        _ = assert(!head.headers.exists(_._1 == "set-cookie"), s"set-cookie must never ride headers: ${head.headers}")
        // Non-Set-Cookie multi-valued headers keep the v1 conservative comma-join.
        _ = assertEquals(head.headers.find(_._1 == "x-other").map(_._2), Some("v1, v2"))
        // Wire face: setCookie sits after headers (schema order); the headers
        // object carries no set-cookie key.
        raw = sentTexts(fake).find(_.contains("http_response")).get
        _ = assert(
          raw.contains("\"headers\":{\"x-other\":\"v1, v2\"},\"setCookie\":["),
          s"wire order/setCookie face drifted: $raw"
        )
      yield ()
      end for
    }

  /**
   * Raw-socket WS-upgrade endpoint (the JDK HttpServer cannot answer a 101,
   * the repo has no WS server library on the test classpath): captures the
   * handshake request head verbatim, answers a REAL 101 (Sec-WebSocket-Accept
   * per RFC 6455), then holds the socket until close(). Binds 127.0.0.1 on an
   * ephemeral port only. The head is recorded BEFORE the 101 is written, so
   * once the client's open future completes the capture is already readable.
   */
  private final class WsHandshakeCapture(message: Option[Array[Byte]] = None) extends AutoCloseable:
    val handshakes = new ConcurrentLinkedQueue[String]()
    private val server = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
    private val sockets = new ConcurrentLinkedQueue[java.net.Socket]()
    @volatile private var stopped = false

    private val acceptor = new Thread(() =>
      while !stopped do
        try
          val sock = server.accept()
          sockets.add(sock)
          new Thread(() => serve(sock)).start()
        catch case _: Exception => ()
    )
    acceptor.setDaemon(true)
    acceptor.start()

    def port: Int = server.getLocalPort

    /** Values of one (lowercased) header name across the captured heads. */
    def headerValues(name: String): List[String] =
      val out = scala.collection.mutable.ListBuffer.empty[String]
      handshakes.forEach { head =>
        head.linesIterator.drop(1).foreach { l =>
          val n = l.takeWhile(_ != ':').trim.toLowerCase(java.util.Locale.ROOT)
          if n == name then out += l.dropWhile(_ != ':').drop(1).trim
        }
      }
      out.toList

    private def serve(sock: java.net.Socket): Unit =
      try
        val reader = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream, UTF_8))
        val head = scala.collection.mutable.ListBuffer.empty[String]
        var line = reader.readLine()
        while line != null && line.nonEmpty do
          head += line
          line = reader.readLine()
        handshakes.add(head.mkString("\r\n"))
        val key = head
          .collectFirst {
            case l if l.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:") =>
              l.dropWhile(_ != ':').drop(1).trim
          }
          .getOrElse("")
        val accept = java.util.Base64.getEncoder.encodeToString(
          java.security.MessageDigest
            .getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF_8))
        )
        val resp =
          "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            s"Sec-WebSocket-Accept: $accept\r\n\r\n"
        sock.getOutputStream.write(resp.getBytes(UTF_8))
        sock.getOutputStream.flush()
        message.foreach { bytes =>
          val out = new java.io.DataOutputStream(sock.getOutputStream)
          def emit(op: Int, fin: Boolean, data: Array[Byte]): Unit =
            out.writeByte((if fin then 0x80 else 0) | op)
            if data.length < 126 then out.writeByte(data.length)
            else if data.length <= 65535 then
              out.writeByte(126)
              out.writeShort(data.length)
            else
              out.writeByte(127)
              out.writeLong(data.length.toLong)
            out.write(data)
            out.flush()
          val cut = bytes.length / 2
          emit(1, false, bytes.take(cut))
          emit(0, true, bytes.drop(cut))
        }
      catch case _: Exception => ()

    def close(): Unit =
      stopped = true
      try server.close()
      catch case _: Exception => ()
      sockets.forEach(s =>
        try s.close()
        catch case _: Exception => ()
      )
  end WsHandshakeCapture

  test("ws_open with headers injects the cookie into the loopback handshake verbatim (v1.1 D9)"):
    val capture = new WsHandshakeCapture
    // The capture's lifetime rides the returned IO (a Scala try/finally around
    // an IO DESCRIPTION would run close() before munit ever runs the IO).
    Dispatcher
      .parallel[IO]
      .use { _ =>
        val faces = new PortalHostFaces(gatewayPort = capture.port)
        val deps = mkDeps(faces)
        val fake = new FakeWebSocket
        val bridge = new PortalTunnelBridge(deps, new RelayFrameSender(fake))
        for
          fiber <- bridge.run.start
          _ = bridge.enqueueFrame(
            WsOpen(
              Rid,
              "/ws",
              None,
              Some(
                Vector(
                  "cookie" -> "nebflow_session=abc123; theme=dark",
                  "x-portal-face" -> "console",
                  // Hop-face / upgrade-owned heads must NOT be injected.
                  "host" -> "evil.example",
                  "upgrade" -> "websocket",
                  "sec-websocket-protocol" -> "nebflow.chat"
                )
              )
            )
          )
          ok <- waitUntil(5.seconds)(
            IO(decodeSent(fake).collectFirst { case r: WsOpenResult => r }.isDefined)
          ).guarantee(fiber.cancel *> bridge.teardown())
          _ = assert(ok, s"no ws_open_result; sent=${sentTexts(fake)}")
          result = decodeSent(fake).collectFirst { case r: WsOpenResult => r }.get
          _ = assertEquals(
            (result.accepted, result.status),
            (true, 101),
            s"loopback upgrade failed; sent=${sentTexts(fake)}"
          )
          // The cookie head crosses VERBATIM (the GatewayAuth session-track
          // face); the ordinary bridge head rides along.
          _ = assertEquals(capture.headerValues("cookie"), List("nebflow_session=abc123; theme=dark"))
          _ = assertEquals(capture.headerValues("x-portal-face"), List("console"))
          // Upgrade-owned faces stay single-headed (the JDK's own): no second
          // host/upgrade line, no injected subprotocol.
          hosts = capture.headerValues("host")
          _ = assertEquals(hosts.length, 1, s"host must stay the JDK's own loopback head: $hosts")
          _ = assert(hosts.head.startsWith("127.0.0.1:"), s"host must be the loopback endpoint: $hosts")
          _ = assertEquals(capture.headerValues("upgrade").length, 1)
          _ = assertEquals(capture.headerValues("sec-websocket-protocol"), Nil)
        yield ()
        end for
      }
      .guarantee(IO(capture.close()))

  test("ws_open without headers keeps the v1 handshake shape (no cookie head injected)"):
    val capture = new WsHandshakeCapture
    Dispatcher
      .parallel[IO]
      .use { _ =>
        val faces = new PortalHostFaces(gatewayPort = capture.port)
        val deps = mkDeps(faces)
        val fake = new FakeWebSocket
        val bridge = new PortalTunnelBridge(deps, new RelayFrameSender(fake))
        for
          fiber <- bridge.run.start
          _ = bridge.enqueueFrame(WsOpen(Rid, "/ws", None, None))
          ok <- waitUntil(5.seconds)(
            IO(decodeSent(fake).collectFirst { case r: WsOpenResult => r }.isDefined)
          ).guarantee(fiber.cancel *> bridge.teardown())
          _ = assert(ok, s"no ws_open_result; sent=${sentTexts(fake)}")
          result = decodeSent(fake).collectFirst { case r: WsOpenResult => r }.get
          _ = assertEquals(
            (result.accepted, result.status),
            (true, 101),
            s"loopback upgrade failed; sent=${sentTexts(fake)}"
          )
          _ = assertEquals(capture.headerValues("cookie"), Nil, "a headers-less ws_open must not grow a cookie head")
        yield ()
        end for
      }
      .guarantee(IO(capture.close()))

  test("fragmented large config crosses the real JDK listener as one complete browser JSON message"):
    val payload = io.circe.Json
      .obj(
        "type" -> io.circe.Json.fromString("configData"),
        "config" -> io.circe.Json.fromString("配置😀" * 12000)
      )
      .noSpaces
      .getBytes(UTF_8)
    val capture = new WsHandshakeCapture(Some(payload))
    val faces = new PortalHostFaces(gatewayPort = capture.port)
    val fake = new FakeWebSocket
    val bridge = new PortalTunnelBridge(mkDeps(faces), new RelayFrameSender(fake))
    (for
      fiber <- bridge.run.start
      _ = bridge.enqueueFrame(WsOpen(Rid, "/ws", None, None))
      ok <- waitUntil(5.seconds)(IO(decodeSent(fake).exists {
        case f: WsFrame => f.fin
        case _ => false
      })).guarantee(fiber.cancel *> bridge.teardown())
      _ = assert(ok, "fragmented config never completed")
      frames = decodeSent(fake).collect { case f: WsFrame => f }
      _ = assertEquals(frames.size, 1, "the relay sends each wire frame as a complete browser message")
      _ = assertEquals(frames.head.fin, true)
      _ = assertEquals(frames.head.kind, "text")
      _ = assert(java.util.Arrays.equals(frames.head.data, payload), "UTF-8 config bytes must survive fragmentation")
      _ = assert(io.circe.parser.parse(new String(frames.head.data, UTF_8)).isRight)
    yield ()).guarantee(IO(capture.close()))

  // ── console ticket mint ────────────────────────────────────────────────

  test("ConsoleTicketMint: success result with ticket + expiresInSec; redeemed-then-destroyed"):
    withBridge(echoApp) { (bridge, fake, deps) =>
      bridge.enqueueFrame(ConsoleTicketMint(Rid, MintId, "logto|user-42", "portal"))
      for
        ok <- waitUntil(5.seconds)(IO(sentTexts(fake).exists(_.contains("ConsoleTicketResult"))))
        _ = assert(ok, s"no ConsoleTicketResult; sent=${sentTexts(fake)}")
        result = decodeSent(fake).collectFirst { case r: ConsoleTicketResult => r }.get
        _ = assertEquals(result.requestId, Rid)
        _ = assertEquals(result.mintId, MintId)
        _ = assert(result.ticket.isDefined, s"ticket missing: $result")
        // Phase 3b (plan §3.4): relay-mint TTL raised 30s -> 90s (the 20s
        // server-side mint chain was eating the browser's remainder).
        _ = assertEquals(result.expiresInSec, Some(90))
        _ = assert(result.error.isEmpty)
        consumed <- deps.consoleTickets.verifyAndConsume(result.ticket.get)
        _ = assert(consumed.isRight, s"first redemption must succeed: $consumed")
        again <- deps.consoleTickets.verifyAndConsume(result.ticket.get)
        _ = assert(again == Left("invalid-ticket"), s"redeemed-then-destroyed: second attempt=$again")
      yield ()
      end for
    }

  test("ConsoleTicketMint: budget exhaustion answers rate_limited"):
    withBridge(echoApp) { (bridge, fake, _) =>
      val n = ConsoleTicketStore.MintBudgetPerWindow
      (0 to n).foreach { i =>
        bridge.enqueueFrame(ConsoleTicketMint(Rid, MintId, s"logto|user-$i", "portal"))
      }
      for
        ok <- waitUntil(5.seconds)(
          IO(decodeSent(fake).collect { case r: ConsoleTicketResult => r }.size == n + 1)
        )
        _ = assert(ok, s"all ${n + 1} results must arrive; sent=${sentTexts(fake)}")
        results = decodeSent(fake).collect { case r: ConsoleTicketResult => r }
        _ = assertEquals(results.count(_.ticket.isDefined), n, s"exactly the budget may mint")
        _ = assertEquals(results.count(_.error.contains("rate_limited")), 1, "the (n+1)-th mint must be rate_limited")
      yield ()
    }

  // ── binding control ────────────────────────────────────────────────────

  test("BindingControl revoked: binding goes terminal (persisted) and live console tickets are revoked"):
    withBridge(echoApp) { (bridge, fake, deps) =>
      for
        _ <- deps.consoleTickets.issue("logto|user-42", "qa-device")
        _ = bridge.enqueueFrame(BindingControl("revoked", None, None))
        // Wait for BOTH terminal faces: isUnbound flips inside the markUnbound
        // transition, revokeAll runs right after its persist — polling either
        // alone races the other.
        ok <- waitUntil(5.seconds)(
          (deps.bindingState.isUnbound, deps.consoleTickets.size).tupled.map { case (u, s) => u && s == 0 }
        )
        _ = assert(ok, "binding must be terminal AND every live console ticket dropped")
        // Persistence: a fresh state machine over the same dataRoot reads unbound.
        freshUnbound <- PortalBindingState.unsafeCreate().isUnbound
        _ = assert(freshUnbound, "the terminal state must survive a process restart (kickParked lesson)")
      yield ()
    }

  test("BindingControl quiesce family: not implemented in 3a, no state change"):
    withBridge(echoApp) { (bridge, fake, deps) =>
      bridge.enqueueFrame(BindingControl("quiesce", None, Some(3600)))
      for
        _ <- IO.sleep(200.millis)
        unbound <- deps.bindingState.isUnbound
        _ = assert(!unbound, "quiesce must not flip the binding state in 3a")
      yield ()
    }

  // ── phase 3b: full-session revocation (plan §5) ────────────────────────

  test("BindingControl revoked fires the sessionRevocation port AND the nf-ticket revocation port"):
    val nfRevocations = new ConcurrentLinkedQueue[Integer]()
    withBridge(echoApp, nfRevocations = nfRevocations) { (bridge, fake, deps) =>
      val observed = new ConcurrentLinkedQueue[Integer]()
      deps.faces.bindSessionRevocation(IO(observed.add(1)).as(2))
      bridge.enqueueFrame(BindingControl("revoked", None, None))
      for
        ok <- waitUntil(5.seconds)(IO(observed.size() == 1 && nfRevocations.size() == 1))
        _ = assert(ok, s"both revocation ports must fire; observed=$observed nf=$nfRevocations")
      yield ()
    }

  test(
    "revoked × real GatewaySessionStore: relay-mint sessions die, local-ticket survives (origin filter, criterion 9 in-repo half)"
  ):
    // The bridge and the REAL session store, wired the way GatewayMain wires
    // them (faces.sessionRevocation = store.revokeRelayMinted) — no relay, no
    // ember: enqueue the frame and observe the store.
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        store <- nebflow.gateway.GatewaySessionStore.create()
        faces <- IO(new PortalHostFaces(gatewayPort = 1))
        _ = faces.bindHttpApp(echoApp)
        nfRevocations = new ConcurrentLinkedQueue[Integer]()
        deps = mkDeps(faces, nfRevocations)
        _ = faces.bindSessionRevocation(store.revokeRelayMinted())
        fake = new FakeWebSocket
        bridge = new PortalTunnelBridge(deps, new RelayFrameSender(fake))
        fiber <- bridge.run.start
        _ <- store.create("logto|user-42", "relay-mint", "net-1")
        _ <- store.create("local", "local-ticket", "")
        _ = bridge.enqueueFrame(BindingControl("revoked", None, None))
        // The signal is terminal when BOTH faces settled: the console store is
        // empty AND the session store holds only the local-ticket row.
        ok <- waitUntil(5.seconds)((deps.consoleTickets.size, store.size).tupled.map { case (t, sess) =>
          t == 0 && sess == 1
        })
        _ = assert(ok, "relay-mint sessions must be revoked; the local-ticket session survives")
        relayGone <- store.list.map(_.forall(_.origin == "local-ticket"))
        _ = assert(relayGone, "survivor must be exactly the local-ticket session")
        _ <- bridge.teardown() *> fiber.cancel
      yield ()
    }

  test(
    "mintConsoleTicket gate split: the local break-glass mint is binding-gate EXEMPT in the revoked state while the relay mint stays gated (review pin T-8-1)"
  ):
    val faces = new PortalHostFaces(gatewayPort = 1)
    val deps = mkDeps(faces)
    for
      _ <- deps.bindingState.markUnbound()
      local <- deps.mintConsoleTicket(
        ConsoleTicketStore.LocalAccountSub,
        ConsoleTicketStore.OriginLocalTicket,
        enforceBindingGate = false
      )
      relay <- deps.mintConsoleTicket(
        "logto|u-1",
        ConsoleTicketStore.OriginRelayMint,
        enforceBindingGate = true
      )
    yield
      assert(local.isRight, s"the escape face must mint in the revoked state, got $local")
      assertEquals(relay, Left("unbound"))
    end for

  // ── stage C: the tunnel-origin mark rides every bridged request ────────

  test(
    "stage C: the bridge injects the armed tunnel mark on in-process HTTP requests; a client-supplied same-named header is dropped first"
  ):
    TunnelOriginMark.arm()
    val markName = TunnelOriginMark.headerName
    val markValue = TunnelOriginMark.armedValue.getOrElse(sys.error("mark failed to arm"))
    // Echo the value(s) of the mark header seen by the app into the body.
    val markApp = HttpApp[IO] { (req: Request[IO]) =>
      val values = req.headers.headers.collect { case h if h.name.toString.equalsIgnoreCase(markName) => h.value }
      IO(Response[IO](status = Status.Ok).withBodyStream(Stream.emits(values.mkString(",").getBytes(UTF_8))))
    }
    withBridge(markApp) { (bridge, fake, _) =>
      // The browser frame carries a same-named GARBAGE header under an
      // uppercased name (headers are case-insensitive on the wire); drop-
      // then-inject must leave exactly one mark header — the armed value.
      bridge.enqueueFrame(
        HttpRequest(Rid, "GET", "/api/echo", Vector(markName.toUpperCase -> "client-supplied-garbage"))
      )
      for
        ok <- waitUntil(5.seconds)(IO(bodyFrames(fake, Rid).nonEmpty))
        _ = assert(ok, s"no complete answer; sent=${sentTexts(fake)}")
        body = new String(assembledBody(fake, Rid), UTF_8)
        _ = assertEquals(body, markValue, "the app must see exactly one mark header carrying the armed value")
      yield ()
    }

  test(
    "stage C: wsHandshakeHeaders drops the tunnel mark name (the bridge re-injects the armed value after filtering)"
  ):
    TunnelOriginMark.arm()
    val filtered = PortalTunnelBridge.wsHandshakeHeaders(
      Vector("cookie" -> "sid=1", TunnelOriginMark.headerName -> "client-garbage")
    )
    assert(filtered.exists(_._1 == "cookie"), "the cookie bridge head must survive the filter unchanged")
    assert(
      !filtered.exists(_._1.equalsIgnoreCase(TunnelOriginMark.headerName)),
      "a client-supplied mark header must never survive the handshake filter"
    )
    assert(
      TunnelOriginMark.injectHeader.isDefined,
      "an armed mark must expose an injectable (name, value) pair for the loopback handshake"
    )

  // ── pong-reliability 批（2026-10-22「大传输触发重连」）：隧道活性 pong 的可靠
  //    上行口。修前 case "ping" 裸 sendText + 无视 future —— bulk 占住 JDK 单发
  //    位时 pong 静默丢失 ⇒ relay 2-miss liveness 撕隧道 ⇒ 浏览器 WS 重连。修后
  //    pong 过 PortalConnection.sendControlText → RelayFrameSender（与 bulk 同
  //    锁、Send pending 重试、预算内响亮失败）。本段钉组合面。

  test("pong control uplink: a bound bridge routes the pong through the RelayFrameSender onto the wire"):
    Dispatcher.parallel[IO].use { dispatcher =>
      val conn = new PortalConnection(dispatcher)
      for
        faces <- IO(new PortalHostFaces(gatewayPort = 1))
        deps = mkDeps(faces)
        fake = new FakeWebSocket
        _ = conn.bind(new PortalTunnelBridge(deps, new RelayFrameSender(fake)))
        _ <- IO(conn.sendControlText("""{"type":"pong"}""", fake))
        ok <- waitUntil(5.seconds)(IO(sentTexts(fake).exists(_.contains("\"pong\""))))
        _ = assert(ok, s"pong must land on the relay socket; sent=${sentTexts(fake)}")
      yield ()
    }

  test("pong control uplink: no bridge bound yet (upgrade race window) falls back to the direct best-effort send"):
    Dispatcher.parallel[IO].use { dispatcher =>
      val conn = new PortalConnection(dispatcher)
      val fake = new FakeWebSocket
      for
        _ <- IO(conn.sendControlText("""{"type":"pong"}""", fake))
        ok <- waitUntil(5.seconds)(IO(sentTexts(fake).exists(_.contains("\"pong\""))))
        _ = assert(ok, s"the fallback must still answer the ping; sent=${sentTexts(fake)}")
      yield ()
    }

end PortalTunnelBridgeSpec
