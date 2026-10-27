package nebflow.neblink

import cats.effect.IO
import cats.effect.std.{Dispatcher, Queue}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import nebflow.shared.NebflowLogger
import nebflow.shared.TunnelOriginMark
import org.http4s.{Header, Headers, HttpApp, Method, Request, Response, Uri}
import org.typelevel.ci.CIString

import java.net.URI
import java.net.http.{HttpClient, WebSocket, WebSocketHandshakeException}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, CompletionStage, ExecutionException, TimeoutException, TimeUnit}

/**
 * Portal frame bridge for ONE relay WebSocket connection (phase 3a design
 * sections 3.1-3.5): consumes the downlink contract frames
 * (`http_request` / `body_frame` / `ws_open` / `ws_frame` / `ws_close` /
 * `ConsoleTicketMint` / `BindingControl`) and produces the uplink frames
 * (`http_response` + `body_frame` streaming, `ws_open_result`, `ws_frame`,
 * `ws_close`, `ConsoleTicketResult`) through [[PortalWireCodec]] — the single
 * canonical encoding point, always carrying `proto: 1`.
 *
 * Ordering model: every inbound frame is enqueued as an IO action into an
 * unbounded FIFO mailbox (offers happen on the single relay listener thread,
 * so submission order == wire order) and executed by ONE consumer fiber.
 * The mailbox actions themselves only do O(1) state work — channel
 * registration, seq checks, queue offers — so the consumer can never be
 * head-of-line-blocked by a slow socket. All blocking work lives in
 * dedicated fibers: the per-request HTTP cycle fiber, and the per-cycle WS
 * pump PAIR (downlink relay→local, uplink local→relay, design 3.3); every
 * relay-bound send goes through [[RelayFrameSender]]'s per-socket lock, and
 * per-stream order is preserved by construction (each stream is produced by
 * exactly one fiber / one FIFO pump).
 *
 * `keep actors out of cats-effect`: this whole face is plain cats-effect IO
 * driven straight off the WS listener thread (a plain queue offer) — no
 * actor crosses the boundary.
 *
 * HTTP face (design 3.2): in-process direct call into the lifted
 * `Router(...).orNotFound` slice (INCLUDING the gzip static tree, EXCLUDING
 * the outer `ConnGuard.requestTap` — requestTap is count-only there; the
 * bridge counts tunnel requests itself via the tunnel guard port, so keeping
 * it out avoids double counting). The request body is a lazy stream pulling
 * from a per-requestId bounded queue (never fully buffered); the response
 * body streams back as `body_frame`s sliced at [[BodyChunkBytes]].
 *
 * WS face (design 3.3): the local endpoint is reached over a real loopback
 * WebSocket (`ws://127.0.0.1:<gatewayPort><path>`; the path is forwarded
 * verbatim, query string included — stage A historically passed a token in
 * the query, a rail the stage D retirement removed). v1.1: a ws_open
 * `headers` object is injected into the loopback handshake (the browser
 * session cookie crosses the bridge; absent keeps the v1 shape). Frames pump
 * both ways with kind/fin mapped 1:1; `ws_close` propagates both ways.
 */
private[neblink] final class PortalTunnelBridge(
  deps: PortalTunnelDeps,
  sender: RelayFrameSender,
  /**
   * v1.3（契约 2.15）portal-v2 能力位：本连接隧道升级时 relay 是否 echo 了
   * `neblink.portal.v2`。true ⇒ 本连接所有 ws 周期的应用消息一律走
   * `ws_data_frame`（同帧族替代，契约 2.14）；false ⇒ v1 整帧面逐字不变。
   * 连接常量（升级时判定一次，与连接同生命周期）。
   */
  val portalV2: Boolean = false
):

  import PortalTunnelBridge.*

  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

  /** pong-reliability 批（2026-10-22）：隧道活性 pong 的上行口——包内唯一授权
    * 访问器，供 [[PortalConnection.sendControlText]] 把控制帧路由进与本业务
    * bulk 同一把单发位锁的发送器（Send pending 重试 + 预算）。 */
  private[neblink] def controlSender: RelayFrameSender = sender

  /** Per-connection state: HTTP body channels + local WS cycles, keyed by requestId. */
  private val bodyChannels = new ConcurrentHashMap[String, BodyChannel]
  /** Test seam (package-private): the self-safe-drop spec registers cycles here directly. */
  private[neblink] val wsCycles = new ConcurrentHashMap[String, WsCycle]

  /** First-body-frame backstop (test seam over [[PortalTunnelBridge.FirstBodyFrameTimeout]]). */
  @volatile private[neblink] var firstBodyFrameTimeout: scala.concurrent.duration.FiniteDuration =
    PortalTunnelBridge.FirstBodyFrameTimeout

  /**
   * Inbound mailbox. A plain JDK blocking queue (not a cats-effect Queue):
   * offers must land in CALLER order — the single relay listener thread is
   * the only producer for downlink frames, so a direct `offer` gives strict
   * wire order for free; dispatching each offer through the dispatcher pool
   * (the first cut) let two offers race and a body_frame overtake its own
   * http_request. Unbounded: the relay listener thread must never block on
   * queue pressure — backpressure here would stall ping/pong and get a live
   * tunnel killed by the 2-miss liveness check, the exact failure mode the
   * server-side design forbids on its own read loop.
   */
  private val mailbox = new java.util.concurrent.LinkedBlockingQueue[IO[Unit]]()

  /**
   * Consumer loop — started by the tunnel's connectOnce, cancelled at
   * teardown. `IO.interruptible` (not `IO.blocking`): the parked `take`
   * must respond to fiber cancellation — JDK blocking queues throw
   * InterruptedException on thread interrupt, which is what lets teardown's
   * `fiber.cancel` return instead of wedging a parked thread forever.
   */
  def run: IO[Unit] = IO.interruptible(mailbox.take()).flatten >> run

  /** Relay-listener entry: enqueue one decoded portal frame (FIFO, single consumer). */
  def enqueueFrame(frame: PortalWireCodec.PortalFrame): Unit =
    mailbox.offer(safeguard(actionFor(frame)))

  private def safeguard(action: IO[Unit]): IO[Unit] =
    action.handleErrorWith { e =>
      logger.warn(
        s"portal mailbox action failed: ${NeblinkRelayTunnel.describeErr(e)} " +
          "(the connection stays up; the affected cycle, if any, is dropped)"
      )
    }

  private def actionFor(frame: PortalWireCodec.PortalFrame): IO[Unit] =
    // The whole dispatch defers: several handlers perform their cycle-table
    // lookups/mutations EAGERLY while building the returned IO (feedBodyFrame,
    // feedWsFrame, handleWsClose). Evaluating them here — on the WS reader
    // thread, before the consumer has run the PREVIOUS frame's action — lets a
    // body_frame overtake its own http_request's registration whenever both
    // arrive in one read batch (exactly what the T-10 R1b stall was: the
    // server, post-R1, delivers http_request + body in the same millisecond).
    // Deferring puts every side effect back on the mailbox consumer fiber, in
    // wire order, which is the ordering the FIFO mailbox exists to provide.
    IO.defer {
      frame match
        case f: PortalWireCodec.HttpRequest        => handleHttpRequest(f)
        case f: PortalWireCodec.BodyFrame          => feedBodyFrame(f)
        case f: PortalWireCodec.WsOpen             => handleWsOpen(f)
        case f: PortalWireCodec.WsFrame            => feedWsFrame(f)
        case f: PortalWireCodec.WsDataFrame        => feedWsDataFrame(f)
        case f: PortalWireCodec.WsClose            => handleWsClose(f)
        case f: PortalWireCodec.ConsoleTicketMint  => handleConsoleTicketMint(f)
        case f: PortalWireCodec.BindingControl     => handleBindingControl(f)
        case f: PortalWireCodec.MigrationChunk =>
          // Explicit not-implemented case (design 3.1): the migration relay face
          // belongs to phase 5; the frame must not fall into the unknown-type
          // branch (that would read as contract drift).
          logger.warn("MigrationChunk frame dropped: migration relay face is not implemented in this phase")
        case other =>
          // Uplink frame types echoed back to the host (relay_response family:
          // http_response / ws_open_result / ConsoleTicketResult / relay_*).
          // Semantically no action; debug only so echoes stay distinguishable
          // from unknown types.
          logger.debug(s"portal frame of uplink type echoed back — ignored: ${other.getClass.getSimpleName}")
    }

  // ────────────────────────── HTTP face ──────────────────────────

  /**
   * Envelope IP: the single-value `x-forwarded-for` the ingress writes from
   * the trusted proxy-injected X-Real-IP (design 2.5). A client-supplied XFF
   * chain never survives the ingress strip, so the first (only) value IS the
   * caller address. Absent header (direct/dev shapes) = "unknown" bucket.
   */
  private def envelopeIpOf(frame: PortalWireCodec.HttpRequest): String =
    frame.headers
      .find(_._1.equalsIgnoreCase("x-forwarded-for"))
      .map(_._2.trim)
      .filter(_.nonEmpty)
      .getOrElse("unknown")

  private def handleHttpRequest(frame: PortalWireCodec.HttpRequest): IO[Unit] =
    val envelopeIp = envelopeIpOf(frame)
    deps.faces.tunnelGuard match
      case Some(guard) =>
        guard.observe(envelopeIp) *> guard.check(envelopeIp).flatMap {
          case Some(reason) =>
            logger.warn(
              s"portal http_request rejected by the tunnel guard: requestId=${frame.requestId} " +
                s"envelopeIp=$envelopeIp method=${frame.method} path=${frame.path.takeWhile(_ != '?')} reason=$reason — answering 429"
            ) *>
              // One-shot answer (head + optional body): spawned OFF the mailbox —
              // the consumer never waits on the relay socket.
              respondStatus(frame.requestId, 429, "application/json", s"""{"error":"rate_limited","reason":"$reason"}""").start.void
          case None => startHttpCycle(frame)
        }
      case None => startHttpCycle(frame)

  private def startHttpCycle(frame: PortalWireCodec.HttpRequest): IO[Unit] =
    deps.faces.httpApp match
      case None =>
        logger.warn(
          "portal http_request dropped: the lifted HTTP app is not bound " +
            "(bridge wired before gateway server assembly)"
        ) *>
          respondStatus(frame.requestId, 503, "text/plain", "portal bridge not wired").start.void
      case Some(app) =>
        (for
          method <- Method.fromString(frame.method)
          uri <- Uri.fromString(frame.path)
        yield (method, uri)) match
          case Left(err) =>
            logger.warn(
              s"portal http_request face unparseable locally (requestId=${frame.requestId}): $err"
            ) *>
              respondStatus(frame.requestId, 502, "text/plain", "unparseable http_request face").start.void
          case Right((method, uri)) =>
            // Per-requestId body channel: opened here, INSIDE the mailbox
            // action, so a body_frame arriving right behind the http_request
            // is guaranteed to find its cycle registered. Bodyless verbs
            // (design 3.2 "GET/no body: close the tail of the queue
            // immediately") get an empty body stream — no channel, no wait.
            val channelOpt =
              if PortalTunnelBridge.mayCarryBody(method) then Some(registerBodyChannel(frame.requestId)) else None
            val body: Stream[IO, Byte] =
              channelOpt.fold(Stream.empty: Stream[IO, Byte])(channelBodyStream)
            val req = Request[IO](
              method = method,
              uri = uri,
              headers = Headers(toRequestHeaders(frame.headers)),
              body = body
            )
            // Spawned OFF the mailbox (one slow app run must not head-of-line
            // block the other cycles). The fiber is registered on the channel
            // so a dropped cycle (seq disorder / queue overflow / teardown)
            // can CANCEL it — "drop the cycle" must mean no http_response
            // leaves a poisoned request, not a truncated-body 200.
            val spawn =
              runLocalRequest(frame.requestId, app, req).start.flatMap { fiber =>
                IO(channelOpt.foreach(_.cycle.set(Some(fiber))))
              }.void
            // Empty-body backstop on bodyful verbs: the contract allows ZERO
            // body_frames for a request whose body is empty, which would leave
            // the channel open forever (no End signal — the handler parks on
            // queue.take and the cycle hangs server-side). The ingress is
            // expected to send a terminal frame for an empty body (its batch's
            // obligation); this deadline is the host-side defense. Non-contract
            // implementation constant (transport backfills belong to T-1/v1.1).
            channelOpt.fold(spawn)(ch => armFirstBodyDeadline(frame.requestId, ch) *> spawn)

  /**
   * First-body-frame deadline: if no body_frame at all arrived within the
   * window, end the channel (the handler proceeds with an empty body) and
   * drop the registration — late frames then hit the no-open-cycle path.
   * Idempotent and self-limiting: if the body started flowing (or the cycle
   * finished first), the guard condition simply fails and the fiber ends.
   */
  private def armFirstBodyDeadline(requestId: String, ch: BodyChannel): IO[Unit] =
    (IO.sleep(firstBodyFrameTimeout) *>
      IO {
        !ch.receivedAny.get() && bodyChannels.remove(requestId, ch)
      }.flatMap { fired =>
        IO.whenA(fired)(
          logger.warn(
            s"portal body backstop fired: no body_frame within ${firstBodyFrameTimeout.toSeconds}s " +
              s"(requestId=$requestId) — proceeding with an empty body"
          ) *> ch.queue.tryOffer(BodySignal.End).void
        )
      }).start.void

  private def runLocalRequest(requestId: String, app: HttpApp[IO], req: Request[IO]): IO[Unit] =
    val headSent = new java.util.concurrent.atomic.AtomicBoolean(false)
    (for
      resp <- app.run(req)
      (headers, setCookies) = toResponseFaces(resp)
      _ <- sender.send(
        PortalWireCodec.encode(
          PortalWireCodec.HttpResponse(requestId, resp.status.code, headers, setCookies)
        )
      )
      _ = headSent.set(true)
      _ <- streamResponseBody(requestId, resp.body)
    yield ())
      .guarantee(IO { bodyChannels.remove(requestId); () })
      .handleErrorWith { e =>
        logger.warn(
          s"portal http cycle failed: requestId=$requestId error=${NeblinkRelayTunnel.describeErr(e)}"
        ) *>
          IO.whenA(!headSent.get())(
            respondStatus(requestId, 502, "text/plain", "portal http cycle failed")
          )
      }

  /**
   * Response body streaming: seq from 0 (+1 per frame), `more=false` only on the last chunk.
   * An EMPTY body still emits exactly one empty `more=false` terminator — the ingress
   * aggregator ends the browser's body stream only on a `more=false` frame, so a head-only
   * cycle (204/303/304) would otherwise park the browser until timeout (staging evidence
   * 2026-10-11: redemption 303 head arrived, body stream never closed).
   */
  private def streamResponseBody(requestId: String, body: Stream[IO, Byte]): IO[Unit] =
    for
      seq <- cats.effect.Ref.of[IO, Int](0)
      _ <- body
        .chunkLimit(BodyChunkBytes)
        .zipWithNext
        .evalMap { (chunk, next) =>
          seq.getAndUpdate(_ + 1).flatMap { n =>
            sender.send(
              PortalWireCodec.encode(
                PortalWireCodec.BodyFrame(requestId, n, more = next.isDefined, chunk.toArray)
              )
            )
          }
        }
        .compile
        .drain
      sent <- seq.get
      _ <- IO.whenA(sent == 0)(
        sender.send(
          PortalWireCodec.encode(PortalWireCodec.BodyFrame(requestId, 0, more = false, Array.emptyByteArray))
        )
      )
    yield ()

  private def registerBodyChannel(requestId: String): BodyChannel =
    val previous = bodyChannels.put(requestId, new BodyChannel)
    if previous != null then
      logger.warn(s"portal body channel replaced: requestId=$requestId already had an open cycle (server bug?)")
    bodyChannels.get(requestId)

  private def channelBodyStream(ch: BodyChannel): Stream[IO, Byte] =
    Stream
      .repeatEval(ch.queue.take)
      .takeWhile(_ != BodySignal.End)
      .flatMap {
        case BodySignal.Data(bytes) => Stream.chunk(Chunk.array(bytes))
        case BodySignal.End         => Stream.empty
      }

  private def feedBodyFrame(frame: PortalWireCodec.BodyFrame): IO[Unit] =
    Option(bodyChannels.get(frame.requestId)) match
      case None =>
        logger.warn(
          s"portal body_frame dropped: no open cycle requestId=${frame.requestId} seq=${frame.seq}"
        )
      case Some(ch) =>
        ch.nextSeq.modify { n => (n + 1, n) }.flatMap { expected =>
          if expected != frame.seq then
            dropBodyCycle(frame.requestId, s"seq out of order (expected=$expected got=${frame.seq})")
          else
            ch.receivedAny.set(true)
            ch.queue.tryOffer(BodySignal.Data(frame.data)).flatMap {
              case true =>
                if !frame.more then
                  ch.queue.tryOffer(BodySignal.End).flatMap {
                    case true  => IO.unit
                    case false => dropBodyCycle(frame.requestId, "body queue full at End")
                  }
                else IO.unit
              case false => dropBodyCycle(frame.requestId, "body queue full")
            }
        }

  /**
   * Drop-cycle discipline (design 2.3 analog): WARN + CANCEL the app-run
   * fiber. The dropped cycle must not answer, so the handler must be
   * interrupted mid-flight — deliberately WITHOUT offering End first: a
   * terminal signal would let the fiber complete with a spliced body and
   * race its answer past the cancel.
   */
  private def dropBodyCycle(requestId: String, reason: String): IO[Unit] =
    logger.warn(s"portal body cycle dropped: requestId=$requestId reason=$reason")
    Option(bodyChannels.remove(requestId)) match
      case None     => IO.unit
      case Some(ch) => cancelCycle(ch)

  private def cancelCycle(ch: BodyChannel): IO[Unit] =
    IO(ch.cycle.get()).flatMap {
      case None    => IO.unit
      case Some(f) => f.cancel.void
    }

  private def toRequestHeaders(headers: PortalWireCodec.Headers): List[Header.Raw] =
    val client = headers.toList.collect {
      case (name, value) if !DropRequestHeaderNames.contains(name.toLowerCase(java.util.Locale.ROOT)) =>
        Header.Raw(CIString(name), value)
    }
    // Stage C belt-and-suspenders: the in-process request also carries the
    // process-self-signed tunnel mark ([[nebflow.shared.TunnelOriginMark]]) as
    // the SECOND origin signal next to remote = None. A client-supplied
    // same-named header is dropped first, so the request-side value is always
    // the bridge's own (or absent when the mark is not armed).
    TunnelOriginMark.injectHeader.fold(client) { case (name, v) =>
      client.filterNot(_.name.toString.equalsIgnoreCase(name)) :+ Header.Raw(CIString(name), v)
    }

  /**
   * Response head as the contract 2.4 faces: the `headers` object (lowercased
   * name -> single string value) plus the `setCookie` array.
   *
   * Set-Cookie is the one header that must NOT be comma-joined (RFC 6265
   * cookie attributes can carry commas, and the v1.1 contract forbids joining
   * it into `headers`): each occurrence is relayed as its OWN `setCookie` array
   * element, in first-seen order, and never enters `headers` (v1.1 sender
   * obligation, contract 2.4 D8). Every OTHER multi-valued header keeps the
   * conservative comma-join (the v1 shape: one string per name). Hop-by-hop
   * artifacts of the in-process call are dropped.
   */
  private def toResponseFaces(resp: Response[IO]): (PortalWireCodec.Headers, Vector[String]) =
    val merged = new scala.collection.mutable.LinkedHashMap[String, String]()
    val setCookies = new scala.collection.mutable.ArrayBuffer[String]()
    resp.headers.headers.foreach { h =>
      val name = h.name.toString.toLowerCase(java.util.Locale.ROOT)
      if name == "set-cookie" then setCookies += h.value
      else if !DropResponseHeaderNames.contains(name) then
        merged.update(name, merged.get(name).map(prev => s"$prev, ${h.value}").getOrElse(h.value))
    }
    (merged.toVector, setCookies.toVector)

  /** Standalone status answer (429 / 502 / 503 shapes) with a one-frame plain body. */
  private def respondStatus(requestId: String, status: Int, contentType: String, body: String): IO[Unit] =
    sender.send(
      PortalWireCodec.encode(
        PortalWireCodec.HttpResponse(requestId, status, Vector("content-type" -> contentType), Vector.empty)
      )
    ) *>
      // Every cycle ends with a `more=false` frame — empty body included; the
      // ingress terminates the browser stream on that frame alone.
      sender.send(
        PortalWireCodec.encode(PortalWireCodec.BodyFrame(requestId, 0, more = false, body.getBytes(UTF_8)))
      )

  // ────────────────────────── WS face ──────────────────────────

  /**
   * ws_open handling (design 3.3 "每周期 fiber 对"): connect the loopback WS,
   * register the cycle, start BOTH pump fibers, then queue the open result.
   * The whole body is spawned OFF the mailbox (connect can take up to the
   * connect timeout).
   *
   * v1.1 D9 consumption face (contract 2.6): a `headers` object on the frame
   * is injected into the loopback handshake request (the browser session
   * cookie crosses the bridge verbatim — `headers.cookie` hits the local
   * GatewayAuth session track). Absent `headers` keeps the exact v1 shape.
   *
   * ROLLOUT HARD ORDER (contract 2.6, freeze first then send): a host running
   * the pre-v1.1 codec accepts this frame but SILENTLY DROPS the `headers`
   * field (unknown extra keys), so the handshake proceeds WITHOUT the bridge
   * headers and session-cookie crossing silently degrades — the local auth
   * falls back to its remaining faces and the tunnel WS surface is not
   * promised available in that window. Tunnel WS sessionization goes live only
   * after both ends run v1.1 codecs.
   *
   * Uplink ordering without buffering: the local listener does NOT request
   * delivery in `onOpen`; the cycle is ACTIVATED only after the pumps are
   * running and the ws_open_result sits at the head of the uplink queue —
   * the JDK therefore delivers no local frame before that, so the relay sees
   * ws_open_result strictly before any ws_frame of the same cycle.
   */
  private def handleWsOpen(frame: PortalWireCodec.WsOpen): IO[Unit] =
    val requestId = frame.requestId
    val uri = s"ws://127.0.0.1:${deps.faces.gatewayPort}${frame.path}"
    val cycle = new WsCycle(requestId)
    connectLocalWs(uri, frame.subprotocol, frame.headers, cycle)
      .flatMap { local =>
        IO {
          cycle.local = local
          wsCycles.put(requestId, cycle)
        } *>
          startPumps(cycle) *>
          IO {
            cycle.queueOpenResult(accepted = true, status = 101)
            cycle.activate()
          }
      }
      .handleErrorWith { e =>
        val status = e match
          case hs: WebSocketHandshakeException => Option(hs.getResponse).map(_.statusCode()).getOrElse(0)
          case _                               => 0
        logger.warn(
          s"portal ws_open failed: requestId=$requestId path=${frame.path} " +
            s"error=${NeblinkRelayTunnel.describeErr(e)} — answering accepted=false status=$status"
        ) *>
          // No cycle was registered, so nothing can follow this frame: a
          // direct send is safe (we are already off the mailbox).
          sender.send(
            PortalWireCodec.encode(PortalWireCodec.WsOpenResult(requestId, accepted = false, status = status))
          )
      }
      .start
      .void

  private def connectLocalWs(
    uri: String,
    subprotocol: Option[String],
    headers: Option[PortalWireCodec.Headers],
    cycle: WsCycle
  ): IO[WebSocket] =
    IO {
      val client = HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        // Loopback call: a system proxy must never intercept it (same face as
        // the relay tunnel's client — a proxied environment answered the
        // loopback ws connect with ConnectException).
        .proxy(java.net.ProxySelector.of(null))
        .connectTimeout(java.time.Duration.ofSeconds(LocalConnectTimeoutSec))
        .build()
      val builder = client.newWebSocketBuilder()
      subprotocol.foreach(builder.subprotocols(_))
      headers.foreach(h =>
        wsHandshakeHeaders(h).foreach { (name, value) => builder.header(name, value) }
      )
      // Stage C: self-signed tunnel-origin mark on the loopback handshake —
      // the WS face has no mechanical origin signal (the socket IS loopback),
      // so this header is the tunnel discriminator GatewayAuth consumes. A
      // client-supplied same-named header never survives wsHandshakeHeaders.
      TunnelOriginMark.injectHeader.foreach { (name, v) => builder.header(name, v) }
      builder.buildAsync(URI.create(uri), new LocalWsListener(cycle))
    }.flatMap(cf => IO.fromCompletableFuture(IO(cf)))

  private def startPumps(cycle: WsCycle): IO[Unit] =
    downPumpLoop(cycle).start.flatMap(f => IO(cycle.downPump.set(Some(f)))) *>
      upPumpLoop(cycle).start.flatMap(f => IO(cycle.upPump.set(Some(f))))

  /**
   * Downlink pump (relay → local): drains the cycle's bounded inbox into the
   * local WS, one bounded send at a time — a stalled local peer stalls ONLY
   * this cycle's pump (design 3.3 per-cycle fiber pair), never the mailbox.
   */
  private def downPumpLoop(cycle: WsCycle): IO[Unit] =
    IO.interruptible(cycle.toLocal.take()).flatMap {
      case WsCycle.DownlinkItem.Frame(frame) =>
        forwardToLocal(cycle, frame).flatMap { ok =>
          if ok then downPumpLoop(cycle) else IO.unit // forward failure dropped the cycle
        }
      case WsCycle.DownlinkItem.LocalClose(code, reason) =>
        // Server-initiated ws_close: drain-then-close — pending frames went
        // first, now complete the local close handshake. The local endpoint's
        // onClose then queues the uplink ws_close echo (close propagates both
        // ways); the uplink pump exits on that tail marker by itself.
        IO.blocking {
          try cycle.local.sendClose(code, reason)
          catch case _: Exception => ()
        }.void
      case WsCycle.DownlinkItem.LocalGone =>
        // The local endpoint died (onClose/onError): nothing more to forward.
        IO.unit
    }

  private def forwardToLocal(cycle: WsCycle, frame: PortalWireCodec.WsFrame): IO[Boolean] =
    val local = cycle.local
    if local == null then IO.pure(false)
    else
      val fut =
        if frame.kind == "text" then local.sendText(new String(frame.data, UTF_8), frame.fin)
        else local.sendBinary(java.nio.ByteBuffer.wrap(frame.data), frame.fin)
      IO.blocking(fut.get(LocalSendTimeoutSec.toLong, TimeUnit.SECONDS))
        .as(true)
        .handleErrorWith { e =>
          // We are ON the downlink pump's own stack: use the self-safe drop
          // (abort local first, cancel only the OTHER pump, never self).
          dropFromDownPump(cycle, s"local ws send failed (${NeblinkRelayTunnel.describeErr(e)})").as(false)
        }

  /**
   * Uplink pump (local → relay): drains the cycle's bounded inbox to the
   * relay socket (serialized through [[RelayFrameSender]]), preserving the
   * FIFO the local listener produced. Exits on the RelayClose tail marker
   * (close propagated up) or on a relay send failure (cycle dropped).
   */
  private def upPumpLoop(cycle: WsCycle): IO[Unit] =
    IO.interruptible(cycle.uplink.take()).flatMap {
      case WsCycle.UplinkItem.OpenResult(accepted, status) =>
        sender
          .send(
            PortalWireCodec.encode(
              PortalWireCodec.WsOpenResult(cycle.requestId, accepted, status)
            )
          )
          .flatMap(_ => upPumpLoop(cycle))
          .handleErrorWith(e =>
            // We are ON the uplink pump's own stack: self-safe drop.
            dropFromUpPump(cycle, s"uplink open-result send failed (${NeblinkRelayTunnel.describeErr(e)})")
          )
      case WsCycle.UplinkItem.Frame(frame) =>
        sender
          .send(PortalWireCodec.encode(frame))
          .flatMap(_ => upPumpLoop(cycle))
          .handleErrorWith(e =>
            dropFromUpPump(cycle, s"uplink frame send failed (${NeblinkRelayTunnel.describeErr(e)})")
          )
      case WsCycle.UplinkItem.Sharded(kind, data) =>
        // v1.3（契约 2.14 同帧族替代）：portal-v2 周期的应用消息一律
        // ws_data_frame。Sharded 件由 listener 在 v2 能力下产生（消息级记账，
        // 切片发生在发送位上——设计 §7）；本 pump 单 fiber 逐片等完成 ⇒ 线序
        // = seq 序，与既有 ws_frame 有序流同构。每片独立过 RelayFrameSender
        //（256KiB raw → base64 ≈349K 字符 = bulk 车道，与 body_frame 同档）。
        PortalTunnelBridge
          .encodeWsUplink(portalV2 = true, cycle.requestId, kind, data)
          .traverse_(sender.send)
          .flatMap(_ => upPumpLoop(cycle))
          .handleErrorWith(e =>
            dropFromUpPump(cycle, s"uplink shard send failed (${NeblinkRelayTunnel.describeErr(e)})")
          )
      case WsCycle.UplinkItem.RelayClose(code, reason) =>
        sender
          .send(PortalWireCodec.encode(PortalWireCodec.WsClose(cycle.requestId, code, reason)))
          .flatMap(_ => cancelPump(cycle.downPump)) // tail: stop the other pump, this one returns
          .handleErrorWith(_ => IO.unit) // relay socket dying; teardown owns the rest
    }

  /** Mailbox action for ws_frame: a non-blocking inbox offer — never blocks the consumer. */
  private def feedWsFrame(frame: PortalWireCodec.WsFrame): IO[Unit] =
    Option(wsCycles.get(frame.requestId)) match
      case None =>
        logger.warn(s"portal ws_frame dropped: no open ws cycle requestId=${frame.requestId}")
      case Some(cycle) =>
        if !cycle.toLocal.offer(WsCycle.DownlinkItem.Frame(frame)) then
          dropWsCycle(cycle, "downlink inbox full (local peer stalled)")
        else IO.unit

  /**
   * v1.3 下行重组（契约 2.14）：`ws_data_frame` 喂进该 cycle 的重组槽；
   * `more=false` 到齐 ⇒ 组装原字节按既有 `DownlinkItem.Frame` 投递（本地转发
   * 面零变化，inbox 仍按**消息**记账——设计 §7）。violation（seq 违规 / 同消息
   * enc·kind 混值 / 累计超 cap）⇒ 丢**整个** cycle（契约 2.14 流级纪律，镜像
   * body_frame 丢环——有序流上「挖洞续流」比显式断环更伤）。重组无独立计时：
   * 终结帧缺席归 cycle 撕除面（teardown / 隧道重连摘除即随 cycle 对象消亡，
   * 设计 §4.4「无新增计时器」）。enc=gzip 在压缩批（下一批）落地前显式拒绝
   * 整条消息所在的 cycle——fail-closed，绝不把未解压字节投给本地 face。
   */
  private def feedWsDataFrame(frame: PortalWireCodec.WsDataFrame): IO[Unit] =
    Option(wsCycles.get(frame.requestId)) match
      case None =>
        logger.warn(
          s"portal ws_data_frame dropped: no open ws cycle requestId=${frame.requestId} seq=${frame.seq}"
        )
      case Some(cycle) =>
        cycle.reasm.feed(frame) match
          case Left(reason) =>
            dropWsCycle(cycle, s"ws_data_frame reassembly violation: $reason")
          case Right(None) => IO.unit // 已缓冲，等后续分片
          case Right(Some((kind, bytes))) =>
            if !cycle.toLocal.offer(
                WsCycle.DownlinkItem.Frame(PortalWireCodec.WsFrame(frame.requestId, kind, bytes, fin = true))
              )
            then dropWsCycle(cycle, "downlink inbox full at reassembly completion (local peer stalled)")
            else IO.unit

  /** Mailbox action for ws_close: enqueue the drain-then-close marker — never blocks the consumer. */
  private def handleWsClose(frame: PortalWireCodec.WsClose): IO[Unit] =
    Option(wsCycles.remove(frame.requestId)) match
      case None =>
        logger.debug(s"portal ws_close for unknown cycle requestId=${frame.requestId} — ignored")
      case Some(cycle) =>
        if !cycle.toLocal.offer(WsCycle.DownlinkItem.LocalClose(frame.code, frame.reason)) then
          dropWsCycle(cycle, "downlink inbox full at ws_close")
        else IO.unit

  /**
   * EXTERNAL drop (mailbox consumer / teardown-adjacent paths — NOT on a pump
   * stack): unregister + abort the local WS first + cancel BOTH pumps. Safe
   * to cancel both because neither fiber is the caller.
   */
  private def dropWsCycle(cycle: WsCycle, reason: String): IO[Unit] =
    logger.warn(s"portal ws cycle dropped: requestId=${cycle.requestId} reason=$reason")
    IO { wsCycles.remove(cycle.requestId, cycle) } *> abortLocal(cycle) *> cancelPumps(cycle)

  /**
   * SELF-SAFE drop, called from a pump fiber's OWN failure path: a pump must
   * never cancel itself — CE3 self-cancel either suspends until the fiber's
   * own completion (everything sequenced after it in this chain would never
   * run) or terminates early, and either way the local socket would leak to
   * the gateway's idle timeout (the cycle is already out of the map, so the
   * connection teardown sweep cannot reach it either). Hence the order:
   * unregister → ABORT the local WS → cancel only the OTHER pump; the calling
   * pump then simply returns and its loop ends.
   */
  private[neblink] def dropFromDownPump(cycle: WsCycle, reason: String): IO[Unit] =
    logger.warn(s"portal ws cycle dropped: requestId=${cycle.requestId} reason=$reason")
    IO { wsCycles.remove(cycle.requestId, cycle) } *> abortLocal(cycle) *> cancelPump(cycle.upPump)

  private[neblink] def dropFromUpPump(cycle: WsCycle, reason: String): IO[Unit] =
    logger.warn(s"portal ws cycle dropped: requestId=${cycle.requestId} reason=$reason")
    IO { wsCycles.remove(cycle.requestId, cycle) } *> abortLocal(cycle) *> cancelPump(cycle.downPump)

  private def abortLocal(cycle: WsCycle): IO[Unit] =
    IO.blocking {
      val local = cycle.local
      if local != null then
        try local.abort()
        catch case _: Exception => ()
    }.void

  private def cancelPumps(cycle: WsCycle): IO[Unit] =
    IO {
      wsCycles.remove(cycle.requestId, cycle) // idempotent two-arg remove
      (cycle.downPump.getAndSet(None), cycle.upPump.getAndSet(None))
    }.flatMap { (down, up) =>
      down.fold(IO.unit)(_.cancel).void *> up.fold(IO.unit)(_.cancel).void
    }

  private def cancelPump(ref: java.util.concurrent.atomic.AtomicReference[Option[cats.effect.Fiber[IO, Throwable, Unit]]]): IO[Unit] =
    IO(ref.getAndSet(None)).flatMap(_.fold(IO.unit)(_.cancel)).void

  /**
   * JDK listener for the LOCAL side of a ws_open cycle. Every callback only
   * OFFERS into the cycle's bounded inboxes (plain Java queue offers — no IO,
   * no dispatcher, no mailbox): ordering is the queue FIFO, and blocking work
   * lives in the per-cycle pump fibers, never on the caller.
   */
  private final class LocalWsListener(val cycle: WsCycle) extends WebSocket.Listener:
    private val requestId = cycle.requestId

    override def onOpen(ws: WebSocket): Unit =
      // Activation-gated delivery: do NOT request here. The cycle activates
      // (request(1)) only after the ws_open_result is queued, pinning the
      // uplink order result-then-frames without early-frame buffering.
      ()

    private val messages = new PortalWsMessageAssembler()
    @volatile private var failed = false

    private def received(ws: WebSocket, kind: String, result: Either[String, Option[Array[Byte]]]): Unit =
      result match
        case Right(Some(bytes)) =>
          // #949：offerUp 现在排队等容量（满则拍退避，不丢）——返回即「件已收下」。
          // request(1) 因此后置到收下之后：inbox 积压时本地 peer 在这里第一次被
          // 真实背压（JDK 停发 ⇒ 本地 app 慢下来），而不是「照常放行+丢件」。
          // v1.3（契约 2.14/2.15）：portal-v2 ⇒ 消息级 Sharded 件（切片在 uplink
          // pump 的发送位上，inbox 记账保持消息粒度）；非 v2 ⇒ 原 ws_frame 路
          // 径逐字不变（回落面 golden）。
          val item =
            if portalV2 then WsCycle.UplinkItem.Sharded(kind, bytes)
            else WsCycle.UplinkItem.Frame(PortalWireCodec.WsFrame(requestId, kind, bytes, fin = true))
          offerUp(item)
          ws.request(1)
        case Right(None) => ws.request(1)
        case Left(reason) =>
          failed = true
          messages.reset()
          wsCycles.remove(requestId, cycle)
          val code = if reason == "message-too-large" then 1009 else 1002
          offerUp(WsCycle.UplinkItem.RelayClose(code, reason))
          cycle.toLocal.offer(WsCycle.DownlinkItem.LocalGone)
          ws.abort()

    override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      if !failed then received(ws, "text", messages.text(data, last))
      null

    override def onBinary(ws: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage[?] =
      if !failed then received(ws, "binary", messages.binary(data, last))
      null

    override def onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
      messages.reset()
      if !failed then
        failed = true
        wsCycles.remove(requestId, cycle)
        offerUp(WsCycle.UplinkItem.RelayClose(statusCode, reason))
        cycle.toLocal.offer(WsCycle.DownlinkItem.LocalGone)
      null

    override def onError(ws: WebSocket, error: Throwable): Unit =
      messages.reset()
      if !failed then
        failed = true
        logger.debugSync(s"portal local ws error: requestId=$requestId error=${NeblinkRelayTunnel.describeErr(error)}")
        wsCycles.remove(requestId, cycle)
        offerUp(WsCycle.UplinkItem.RelayClose(1011, error.getClass.getSimpleName))
        cycle.toLocal.offer(WsCycle.DownlinkItem.LocalGone)

    /** #949：uplink 满态处置核心在 [[offerUplinkAwaitCapacity]]（排队等容量，不丢件）。 */
    private def offerUp(item: WsCycle.UplinkItem): Unit =
      offerUplinkAwaitCapacity(cycle, item)
  end LocalWsListener

  /**
   * #949（2026-10-26）——修前这里「满了就丢 + 每次一条 WARN」：丢件=浏览器流
   * 永久缺帧，且固定 2-3 个 requestId 每秒数条刷了 2000 行（无退避无同因去重，
   * 死循环空烧 CPU）。修后 = **排队等容量**：拍退避（[[UplinkBackoff]]，翻倍
   * 封顶，零墙钟无放弃时限）等待 pump 腾位；同因 WARN 走 [[UplinkFullAlarm]]
   * 限频（首条 + 每 32 事件一条，状态变化即报）；本地 peer 由调用方的
   * `request(1)` 窗口第一次被真实背压。退出只有两个事件：拿到容量；周期死亡
   * （注册表摘除）——死亡件进 [[PortalUplinkDeadLetter]] 落盘补投，永不静默
   * 消失（#944「宁重勿丢」同款语义）。package-private：spec 直驱。
   */
  private[neblink] def offerUplinkAwaitCapacity(cycle: WsCycle, item: WsCycle.UplinkItem): Unit =
    var accepted = cycle.uplink.offer(item)
    var waited = false
    while !accepted && !cycleDead(cycle) do
      waited = true
      cycle.alarm
        .strike(cycle.uplink.size(), PortalTunnelBridge.WsQueueSlots, cycle.itemBytes(item))
        .foreach(logger.warnSync)
      // 拍退避：轮次 = 本回合已历满态事件数（停等拍数随之翻倍、封顶）。
      UplinkBackoff.park(round = cycle.alarm.strikeCount.toInt.max(1))
      accepted = cycle.uplink.offer(item)
    if accepted then
      if waited then cycle.alarm.recovered().foreach(logger.infoSync)
    else
      // 周期已死而件未走（pump 死亡/周期被摘）：死信落盘，状态变化即报。
      PortalUplinkDeadLetter.enqueueSync(
        cycle.requestId,
        PortalUplinkDeadLetter.SourceWaitCycleDeath,
        cycle.itemKind(item),
        PortalWireCodec.encode(cycle.itemFrame(item)),
        cycle.itemBytes(item),
        "cycle left the registry while the uplink inbox was full (pump dead / cycle dropped)"
      )
  end offerUplinkAwaitCapacity

  /** 周期死亡事件：注册表里已不是本周期（摘除即死——teardown/双 pump 失败路径都在此摘）。 */
  private def cycleDead(cycle: WsCycle): Boolean = wsCycles.get(cycle.requestId) ne cycle

  // ────────────────────────── console ticket mint ──────────────────────────

  /**
   * Policy chain lifted to [[PortalTunnelDeps.mintConsoleTicket]] (phase 3b,
   * plan §3.4 — single implementation, gate subset parameterized): the relay
   * mint point enforces the binding gate (unbound => `unbound`) plus the
   * shared budget (`rate_limited`); the local break-glass mint point shares
   * the SAME chain with the binding gate exempted. Success => ticket +
   * expiresInSec (90s TTL, plan §3.4).
   */
  private def handleConsoleTicketMint(frame: PortalWireCodec.ConsoleTicketMint): IO[Unit] =
    val result: IO[PortalWireCodec.ConsoleTicketResult] =
      deps
        .mintConsoleTicket(frame.accountSub, ConsoleTicketStore.OriginRelayMint, enforceBindingGate = true)
        .map {
          case Right(issued) =>
            PortalWireCodec.ConsoleTicketResult(
              requestId = frame.requestId,
              mintId = frame.mintId,
              ticket = Some(issued.token),
              expiresInSec = Some(issued.expiresInSec.toInt),
              error = None
            )
          case Left(err) => mintError(frame, err)
        }
    // One-shot answer: spawned OFF the mailbox (the consumer never waits on
    // the relay socket; a single result frame has no ordering dependency).
    result.flatMap(r => sender.send(PortalWireCodec.encode(r))).start.void

  private def mintError(frame: PortalWireCodec.ConsoleTicketMint, error: String): PortalWireCodec.ConsoleTicketResult =
    PortalWireCodec.ConsoleTicketResult(
      requestId = frame.requestId,
      mintId = frame.mintId,
      ticket = None,
      expiresInSec = None,
      error = Some(error)
    )

  // ────────────────────────── binding control ──────────────────────────

  /**
   * `revoked` (judgment-table row 1): terminal state + persist, invalidate
   * every console ticket, stop serving the relay tool face (enforced at the
   * tunnel's handleRelayRequest), short-circuit session self-heal, probe the
   * relay at low frequency until row 4 clears the state.
   *
   * Phase 3b (plan §5 row 1) fulfills the "full-session revocation" note:
   * the SAME signal additionally revokes every relay-mint gateway session
   * (via the bound faces port) and every nf path-read ticket (via the deps
   * port). `local-ticket` sessions survive ON PURPOSE — the break-glass
   * escape (v3 :290) — and cannot renew themselves (the AuthAdmin face is
   * file-token Bearer only).
   */
  private def handleBindingControl(frame: PortalWireCodec.BindingControl): IO[Unit] =
    frame.kind match
      case "revoked" =>
        deps.bindingState.markUnbound().flatMap { transitioned =>
          deps.consoleTickets.revokeAll().flatMap { dropped =>
            (deps.faces.sessionRevocation.getOrElse(IO.pure(0)), deps.revokeNfTickets)
              .mapN((sessionsDropped, nfTicketsDropped) =>
                logger.warn(
                  "BindingControl revoked: host binding is TERMINAL — console tickets invalidated " +
                    s"(dropped=$dropped, transitioned=$transitioned, sessionsDropped=$sessionsDropped, " +
                    s"nfTicketsDropped=$nfTicketsDropped); the relay tool face stops serving " +
                    "and no session self-heal/re-login will be attempted"
                )
              )
          }
        }
      case other =>
        logger.warn(
          s"BindingControl kind=$other received — the quiesce/stand-down family is not implemented " +
            "in this phase (phase 5 migration face); frame dropped"
        )

  // ────────────────────────── teardown ──────────────────────────

  /**
   * Connection-end cleanup: cancel every open body cycle's app-run fiber,
   * stop every WS cycle's pump pair, and abort every local WS (its onClose
   * offers a final close marker whose uplink send will fail fast on the dead
   * relay socket — harmless).
   */
  def teardown(): IO[Unit] =
    IO {
      val channels = new java.util.ArrayList[BodyChannel]()
      bodyChannels.values.forEach(channels.add)
      bodyChannels.clear()
      val cycles = new java.util.ArrayList[WsCycle]()
      wsCycles.values.forEach(cycles.add)
      wsCycles.clear()
      (channels, cycles)
    }.flatMap { (channels, cycles) =>
      def drain[A](list: java.util.ArrayList[A]): List[A] =
        val out = scala.collection.mutable.ListBuffer.empty[A]
        list.forEach(out += _)
        out.toList
      drain(channels).traverse_(cancelCycle) *>
        drain(cycles).traverse_(cancelPumps) *>
        // #949 面二：连接收尾时仍在 uplink inbox 排队的件 ⇒ 死信落盘（带来源
        // 与 requestId），relay 恢复后按序补投——永不静默消失（#944 同款）。
        drain(cycles).traverse_(preservePendingUplink) *>
        IO {
          drain(cycles).foreach { c =>
            val local = c.local
            if local != null then
              try local.abort()
              catch case _: Exception => ()
          }
        }
    }

  /**
   * #949：一个周期的 uplink inbox 全量残件进死信区（同步核心，单件写失败不抛）。
   * 顺序 = 队列 FIFO（poll 序 == 入队序，死信文件名序保序补投）。
   */
  private def preservePendingUplink(c: WsCycle): IO[Unit] = IO {
    var item = c.uplink.poll()
    while item != null do
      PortalUplinkDeadLetter.enqueueSync(
        c.requestId,
        PortalUplinkDeadLetter.SourceTeardown,
        c.itemKind(item),
        PortalWireCodec.encode(c.itemFrame(item)),
        c.itemBytes(item),
        "connection ended with the item still queued in the uplink inbox"
      )
      item = c.uplink.poll()
  }

  /**
   * #949 面二·补投腿：按入队序把死信区残件经**当前连接**的 sender 补投上去
   * （成功出队、失败留盘等下一轮重连；单件炸不拖垮其余）。由
   * [[PortalConnection.armLifecycle]] 在消费纤维起来后 fire-and-forget 驱动，
   * 零墙钟——触发只认「（重）连成功」这一事件，无重试节拍（#944 outboxDrain
   * 同款装配位）。
   */
  private[neblink] def replayDeadLetters: IO[Unit] =
    PortalUplinkDeadLetter
      .drain(entry => sender.send(entry.frameText))
      .flatMap {
        case PortalUplinkDeadLetter.DrainReport(Nil, Nil) => IO.unit
        case report =>
          IO(
            logger.infoSync(
              s"portal uplink dead-letter replay: replayed=${report.replayed.size} kept=${report.kept.size}" +
                report.kept.take(3).map { (id, err) => s" [$id: $err]" }.mkString
            )
          )
      }
      .handleErrorWith(e =>
        logger.warn(s"portal uplink dead-letter replay failed (entries kept on disk): ${e.getMessage}").void
      )

end PortalTunnelBridge

/**
 * Frames sent to the relay WS, serialized per socket (JDK sendText ordering
 * contract).
 *
 * portal-cycle 批（2026-10-15，复测 W1 两个失败面的修复件）——修前
 * `send` 只是「锁跨 `future.get(15s)`」，两个邻接失败面都出自该语义的盲区：
 *
 *  - **写停摆超时**（W1 `TimeoutException` ×5）：路径停摆时 future 在写预算内
 *    不完成 ⇒ 周期失败。但 `get` 超时**不取消** future——帧仍在写、JDK 单发位
 *    （`pendingTextOrBinary`）仍被它占着；修前丢掉了这个事实，下一帧
 *    `sendText` 撞位拿到已失败的 `IllegalStateException` future ⇒ 第二个健康
 *    周期被一个误导性错误连坐。修后：在飞 future 记账（[[inFlight]]），下一发
 *    **先排空**（有界）再入队——顺序由构造保证，路径在排空窗内恢复则周期存活。
 *  - **单发位瞬态碰撞**（W1 `ISE: Send pending` ×4）：心跳 ping/pong、ack、
 *    bind_confirm、relay_response 等**绕锁直发方**（控制面不得被 bulk 顶头
 *    阻塞，绕锁是有意的）恰在本方入队瞬间占位 ⇒ 修前一发拒接就整周期失败。
 *    修后：拒接（future 已失败携 `IllegalStateException` 且出向开）按瞬态
 *    处理——小额停等重试，有界；真写失败（IOException 族 / 出向已关的 ISE）
 *    照旧立即如实传播。
 *
 * 超时语义收紧为诚实三件：本周期失败携带**可读消息**的 `TimeoutException`；
 * **不 abort** socket（写仍可能完成——停摆不是会话死亡证据，撕会话不属于
 * sender 的权限）；在飞帧保持记账，不污染后续发送。
 *
 * 双车道让位（稳定性批 2026-10-26，作者 01:09「大文件传输→登录状态失效」归因
 * 修复腿①）——修前 `send` 只有「monitored 单锁 + 逐帧等写完」一个车道，
 * `synchronized` 是**非公平**监视器：5MB+ 传输的 bulk 泵逐帧释放后可立即重夺
 * 锁（barging），控制帧发送线程在锁上排队多久没有上界。被顶在队尾的控制帧族
 * 与后果（实测归因，见 reports/20261026_*_session-stability）：
 *   · 隧道活性 pong → relay 侧 2-miss liveness 判死撕隧道 → 骑隧道的浏览器
 *     WS + HTTP 周期全体塌方（传输中「登录状态失效」的第一现场）；
 *   · ws_frame（浏览器控制台 WS 的 pong）→ 前端 3-miss 自我逐出 → 重连风暴；
 *   · http_response 头 → ingress `PORTAL_RESP_HEADER_TIMEOUT`（15s）超窗回
 *     504 → 前端把传输拥堵误读成「中继平面凭据死」→ 重登引导链被拉动。
 * 修后：**小帧走控制车道**（[[RelayFrameSender.isControlSized]]：≤64KiB 字符
 * ——pong/ack/响应头/ws_frame 小消息同族；body_frame 256KiB→base64 ≈ 349K
 * 字符、readFile 大帧至 4MiB，全都进不了控制车道），控制车道在发送位排队序上
 * **越过 bulk 等待者**（[[controlWaiters]] 在拿锁前自增，bulk 在锁内让位等待）；
 * 等待上界从「无界（非公平监视器 + 洪峰泵）」收紧为「一个在飞帧的写完」。
 * bulk 饿死防线 = [[RelayFrameSender.ControlGrantCap]] 连续控制让位信用——超
 * 过后即使仍有控制等待者，下一个 bulk 也放行（控制帧小而稀，正常流远触不到）。
 * 车道决策只影响**不同流之间**的排队序：同一周期/流的帧仍由同一 fiber 顺序发
 * 出且逐帧等完成（http 头先于本体、body seq 单调、ws_frame 有序——构造不变），
 * 跨流重排与修前非公平监视器下的实际行为同权，不属契约面。
 */
private[neblink] final class RelayFrameSender(
  ws: WebSocket,
  sendTimeoutSec: Int = RelayFrameSender.SendTimeoutSec,
  pendingRetryMillis: Long = RelayFrameSender.PendingRetryMillis,
  /** 测试缝：连续控制让位信用上限（稳定性批 2026-10-26 双车道让位）。 */
  controlGrantCap: Int = RelayFrameSender.ControlGrantCap
):

  /**
   * 双车道让位锁（稳定性批 2026-10-26；替代修前的 `new Object` 监视器——非公平
   * 监视器允许 bulk 泵 barging，控制帧等待无上界，即「大传输饿死控制面」的
   * 机械根）。**公平模式**：锁等待 FIFO 化是车道让位的确定性基座（bulk 让位 =
   * 「取锁 → 见控制等待者 → await 重排队尾」，公平模式下让位轮转次数 = 排队
   * bulk 数，控制等待时间严格有界；非公平模式下规范无法钉出确定序列）。发送
   * 速率低（帧/百毫秒级），公平模式的吞吐代价可忽略；位置阶段（drainPrevious +
   * enqueueAndAwait）仍在锁内——与修前同构，JDK 单发位纪律零变化。
   */
  private val lock = new java.util.concurrent.locks.ReentrantLock(true)
  private val laneGate = lock.newCondition()

  /**
   * 正在等待发送位的控制面发送数。控制线程在**拿锁之前**自增（拿锁后可能被
   * bulk 持有者阻塞——那时它还不可见），让已持锁的 bulk 让位判据看得见；
   * Atomic 读写在锁外自增/锁内判读之间无竞态危害（最坏 = 多等一轮 1s 重查）。
   */
  private val controlWaiters = new java.util.concurrent.atomic.AtomicInteger(0)

  /** 连续控制让位计数（仅锁内读写）：控制每放行一个 +1，bulk 放行即清零。 */
  private var bulkCredit: Int = 0

  /**
   * The last future this sender handed to `ws.sendText` that may still be in
   * flight — i.e. still holding the JDK single-send position. Guarded by
   * `lock`; null = position known free.
   */
  private var inFlight: CompletableFuture[WebSocket] = null

  /**
   * Send one complete text frame. The lock serializes ALL portal uplink
   * frames on this socket: `java.net.http.WebSocket.sendText` fails while the
   * previous text send has not completed, so concurrent fibers (mailbox
   * consumer + per-cycle streamers) must not race the position.
   *
   * Budgeting (implementation constants, not contract): acquiring the
   * single-send position — draining a still-in-flight predecessor plus
   * retrying transient rejections — shares ONE [[RelayFrameSender.SendTimeoutSec]]
   * window; once enqueued, the frame's own write gets a fresh full window.
   * The bounded waits keep a stalled relay from wedging the senders forever
   * without ever tearing the session from inside the sender.
   *
   * 车道决策（稳定性批 2026-10-26）按**帧尺寸**单点判定：小帧（控制面族）在
   * bulk 等待者之前放行，等待上界 = 一个在飞帧的写完；调用点零改动（body_frame
   * 256KiB→base64 与 ws 大帧天然超出阈值，永远进不了控制车道）。
   */
  def send(text: String): IO[Unit] =
    IO.blocking {
      val control = RelayFrameSender.isControlSized(text)
      if control then controlWaiters.incrementAndGet()
      try
        lock.lock()
        try
          if !control then
            // bulk 让位环：有控制等待者且让位信用未耗尽 ⇒ 等待（1s 有界重查，
            // 防信号丢失长睡）。信用耗尽 = 连续 ControlGrantCap 个控制放行后
            // bulk 强制放行（防控制洪泛饿死 bulk 的理论面）。
            while controlWaiters.get() > 0 && bulkCredit < controlGrantCap do
              laneGate.await(1, TimeUnit.SECONDS)
            bulkCredit = 0
          val positionDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(sendTimeoutSec.toLong)
          drainPrevious(positionDeadlineNs)
          enqueueAndAwait(text, positionDeadlineNs)
          if control then bulkCredit += 1
          ()
        finally
          laneGate.signalAll()
          lock.unlock()
      finally
        if control then controlWaiters.decrementAndGet()
    }

  /**
   * A predecessor frame whose write budget expired (or that was interrupted
   * mid-await) may STILL be occupying the single-send position. Wait for it
   * (bounded by the remaining position budget) instead of letting our own
   * `sendText` bounce off the position with a misleading
   * IllegalStateException: if the stalled write completes, this send proceeds
   * and the cycle survives; if the budget exhausts, this send fails with an
   * honest stall error while the predecessor stays tracked for the next one.
   * A predecessor that completed EXCEPTIONALLY also frees the position — the
   * failure itself surfaces from our own send below (the socket is likely
   * broken; the tunnel liveness machinery owns that path).
   */
  private def drainPrevious(positionDeadlineNs: Long): Unit =
    val predecessor = inFlight
    inFlight = null
    if predecessor != null && !predecessor.isDone then
      val remainingNs = math.max(0L, positionDeadlineNs - System.nanoTime())
      try predecessor.get(remainingNs, TimeUnit.NANOSECONDS)
      catch
        case _: TimeoutException =>
          // Still in flight ⇒ still holding the single-send position: keep the
          // accounting (the doc promise above) so the NEXT send drains it
          // instead of bouncing off the held position through the retry loop.
          inFlight = predecessor
          throw previousStallTimeout()
        case e: InterruptedException =>
          // Interrupted mid-drain: the predecessor was NOT cancelled and keeps
          // the position — same accounting as the timeout face.
          inFlight = predecessor
          Thread.currentThread().interrupt()
          throw e
        case _: ExecutionException => () // position free either way; our send reports the socket honestly

  /**
   * Enqueue behind the (now expected-free) single-send position and await the
   * wire write with a fresh full write budget. Transient position rejections
   * — an already-failed `IllegalStateException` future while the output is
   * open, i.e. a lock-BYPASSING control sender (heartbeat ping/pong, ack,
   * bind_confirm, relay_response) held the position at our instant — are
   * retried with a small park until the position budget runs out; they are
   * normal operation, never a cycle failure. Real write failures (IOException
   * family; ISE with the output already closed) propagate immediately.
   */
  private def enqueueAndAwait(text: String, positionDeadlineNs: Long): Unit =
    var pendingRejection: Throwable = null
    var done = false
    while !done do
      if System.nanoTime() >= positionDeadlineNs then throw positionTimeout(pendingRejection)
      val fut: CompletableFuture[WebSocket] =
        try ws.sendText(text, true)
        catch
          // Defensive sync-throw face: the javadoc contract says
          // IllegalStateException when a previous send is pending (this JDK
          // returns a failed future instead — pinned by the pump-selfabort P1
          // real-socket fixture). Same verdict as the async face below: retry
          // while the output is open, real death otherwise.
          case e: IllegalStateException =>
            if ws.isOutputClosed() then throw e
            pendingRejection = e
            parkBeforeRetry()
            null
      if fut != null then
        inFlight = fut
        try
          // (InterruptedException propagates with `inFlight` still set on
          // purpose: the future was not cancelled and keeps the position —
          // the next send drains it, same accounting as drainPrevious.)
          fut.get(sendTimeoutSec.toLong, TimeUnit.SECONDS)
          inFlight = null
          done = true
        catch
          case e: ExecutionException =>
            inFlight = null
            e.getCause match
              case ise: IllegalStateException if !ws.isOutputClosed() =>
                pendingRejection = ise
                parkBeforeRetry()
              case c: Exception => throw c
              case c            => throw new ExecutionException(c)
          case _: TimeoutException =>
            // Write budget exhausted while the frame is STILL in flight (`get`
            // with timeout does not cancel it — it keeps holding the single-send
            // position). Keep it tracked so the NEXT send drains instead of
            // bouncing off the position misleadingly; fail THIS cycle only and
            // never abort the socket: a stalled write is not a session death
            // verdict (the write may still complete once the path recovers).
            throw writeStallTimeout()

  private def parkBeforeRetry(): Unit =
    try Thread.sleep(pendingRetryMillis)
    catch case _: InterruptedException => Thread.currentThread().interrupt()

  private def writeStallTimeout(): TimeoutException =
    new TimeoutException(
      s"relay frame write did not complete within ${sendTimeoutSec}s — relay write stalled (slow WAN / path stall?); " +
        "connection left intact, frame still in flight"
    )

  private def previousStallTimeout(): TimeoutException =
    new TimeoutException(
      s"previous relay frame still in flight after ${sendTimeoutSec}s — relay write stalled (slow WAN / path stall?); " +
        "connection left intact"
    )

  private def positionTimeout(lastRejection: Throwable): TimeoutException =
    val cause =
      if lastRejection == null then ""
      else s" (last rejection: ${NeblinkRelayTunnel.describeErr(lastRejection)})"
    new TimeoutException(
      s"relay send position not acquired within ${sendTimeoutSec}s$cause — connection left intact"
    )

object RelayFrameSender:
  /** Non-contract implementation constant (backpressure tuning belongs to T-1). */
  val SendTimeoutSec: Int = 15

  /** Non-contract implementation constant: park between position retries. */
  val PendingRetryMillis: Long = 25L

  /**
   * 控制车道尺寸阈值（稳定性批 2026-10-26，非契约实现常量）：≤64KiB 字符的帧
   * 走控制车道。口径两端留足余量——控制面帧（pong/ack/bind_confirm/响应头/
   * ws_open_result/小 ws_frame）最大也就几 KB；body_frame 的 256KiB chunk
   * base64 后 ≈ 349K 字符、ws 大帧上限 4MiB，都 5 倍开外进不了控制车道。
   * 语义余量：body 尾帧（<256KiB 的 more=false 终结帧）若落控制车道无害
   * （小帧插队只轻微延迟他流；同流顺序由单 fiber 逐帧等完成保证，见类注）。
   */
  val ControlLaneMaxChars: Int = 64 * 1024

  /**
   * 连续控制让位信用上限（非契约实现常量）：控制车道连续放行这么多个之后，
   * 即使仍有控制等待者，下一个 bulk 也放行——控制洪泛不饿死 bulk 的理论防线
   * （控制帧小而稀，正常流远触不到该值）。
   */
  val ControlGrantCap: Int = 16

  /** 车道判定单点（spec 钉）：小帧 = 控制面族。 */
  private[neblink] def isControlSized(text: String): Boolean =
    text.length <= ControlLaneMaxChars

private[neblink] object PortalTunnelBridge:

  /** Non-contract implementation constants (contract section 4 values are placeholders pending T-1). */
  val BodyChunkBytes: Int = 256 * 1024
  val BodyQueueSlots: Int = 64
  val LocalConnectTimeoutSec: Int = 10
  val LocalSendTimeoutSec: Int = 5

  /**
   * Per-cycle WS pump inbox depth (downlink and uplink alike).
   * Ledger #949 §2 face-③ (author-approved 2026-10-26): 128 → 64 (conservative
   * end of the suggested 32–64 band) — a full 128-slot inbox only inflated the
   * queued backlog and the dead-letter volume on slow uplinks; true backpressure
   * is the request(n) flow-control delivered by the #949 batch.
   */
  val WsQueueSlots: Int = 64
  // Base64 plus envelope stays below the relay listener's 8 MiB frame cap.
  val MaxWsMessageBytes: Int = 4 * 1024 * 1024

  /**
   * v1.3 WS 分片常量（契约 2.14）：上行单条 ws_data_frame 的 raw 载荷切片上限。
   * 默认 256 KiB，对齐 body_frame 既有口径（BodyChunkBytes / ingress
   * PORTAL_BODY_CHUNK_BYTES）；base64 后 ≈349K 字符，稳定落 bulk 车道（控制车道
   * 阈值 64KiB 字符——语义余量与 body_frame 同注）。env `NEBLINK_WS_SHARD_BYTES`
   * 可覆写，钳位 [1KiB, MaxWsMessageBytes]；契约 2.14：任意正切片尺寸均合规，
   * 该值是实现常量非契约值。
   */
  val WsShardBytes: Int =
    val raw = sys.env.get("NEBLINK_WS_SHARD_BYTES").map(_.trim).filter(_.nonEmpty).flatMap(_.toIntOption)
    val picked = raw.getOrElse(256 * 1024)
    math.max(1024, math.min(picked, MaxWsMessageBytes))

  /**
   * v1.3 能力协商 offer 总闸（契约 2.15；灰度回退面 = 「env 关 ⇒ 回整帧」）：
   * `NEBLINK_PORTAL_V2` 置 0/false/off（大小写不敏感）⇒ 隧道升级不 offer
   * subprotocol ⇒ 对端无从 select ⇒ 全链 v1 整帧（逐字原路）。缺省开（契约
   * 2.15 / 设计 §4.3 兼容矩阵：新引擎 offer，未 echo 即回落，协商失败不破链）。
   * def 而非 val：每次连接现读，测试与运维在同一进程内可翻面。
   */
  private[neblink] def portalV2OfferEnabled: Boolean =
    sys.env.get("NEBLINK_PORTAL_V2").map(_.trim).filter(_.nonEmpty) match
      case Some(v) => !(v.equalsIgnoreCase("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off"))
      case None    => true

  /**
   * Backstop for a bodyful request that receives ZERO body_frames (contract
   * 2.3 allows "0..n"; an empty body with no terminal frame from the ingress
   * would otherwise park the handler on the channel forever). Generous vs
   * any legitimate first chunk (one ingress slice + one RTT). Non-contract
   * implementation constant; the ingress-side obligation (send a terminal
   * frame for an empty body) is registered with its batch.
   */
  val FirstBodyFrameTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(30).seconds

  private val DropRequestHeaderNames: Set[String] =
    Set("transfer-encoding", "connection", "keep-alive", "upgrade")
  private val DropResponseHeaderNames: Set[String] =
    Set("transfer-encoding", "connection", "keep-alive")

  /**
   * WS handshake injection discipline (contract 2.6 D9, same delivery face as
   * the HTTP bridge headers): hop-by-hop and upgrade-owned headers are never
   * injected — the JDK client owns the upgrade face (`upgrade` / `connection`
   * / the `sec-websocket-*` family; the subprotocol carrier is the ws_open
   * `subprotocol` field), and `host` / `content-length` / `origin` describe
   * the local loopback call, not the browser call being bridged. Everything
   * else — the `cookie` bridge head in particular — is forwarded verbatim.
   *
   * Stage C: the tunnel-mark header name is on the drop list too — a browser
   * supplied same-named header is discarded here, then the bridge injects the
   * genuine armed value after filtering (drop-then-inject keeps the mark
   * unforgeable and duplicate-free).
   */
  private val DropWsHandshakeHeaderNames: Set[String] =
    (DropRequestHeaderNames ++ Set(
      "host",
      "content-length",
      "origin",
      "sec-websocket-accept",
      "sec-websocket-extensions",
      "sec-websocket-key",
      "sec-websocket-permessage-deflate",
      "sec-websocket-protocol",
      "sec-websocket-version"
    )) + TunnelOriginMark.headerName

  /** Filter a ws_open frame's headers down to the injectable handshake face. */
  private[neblink] def wsHandshakeHeaders(headers: PortalWireCodec.Headers): Vector[(String, String)] =
    headers.filterNot(h => DropWsHandshakeHeaderNames.contains(h._1.toLowerCase(java.util.Locale.ROOT)))

  /**
   * Verbs that never carry a request body in the portal shape (the ingress
   * sends zero body_frames for them, so the host must not wait for any):
   * http4s 0.23 `Method` has no `hasBody` member — this is the local
   * equivalent, name-based and exhaustive over the bodyless RFC verbs.
   */
  private val BodylessMethodNames: Set[String] = Set("GET", "HEAD", "DELETE", "OPTIONS", "TRACE")

  private[neblink] def mayCarryBody(method: Method): Boolean =
    !BodylessMethodNames.contains(method.name)

  sealed trait BodySignal
  object BodySignal:
    final case class Data(bytes: Array[Byte]) extends BodySignal
    case object End extends BodySignal

  /**
   * Per-requestId request-body channel: a bounded queue plus the strictly
   * increasing seq tracker (contract 2.5: 0-based, +1 per direction per
   * requestId; the downlink direction is checked here, the uplink encoder
   * produces it).
   */
  private[neblink] final class BodyChannel:
    val queue: Queue[IO, BodySignal] = Queue.bounded[IO, BodySignal](BodyQueueSlots).unsafeRunSync()
    val nextSeq: cats.effect.Ref[IO, Int] = cats.effect.Ref.unsafe[IO, Int](0)
    /** The app-run fiber of this cycle (registered after spawn; cancel = drop the cycle). */
    val cycle = new java.util.concurrent.atomic.AtomicReference[Option[cats.effect.Fiber[IO, Throwable, Unit]]](None)
    /** Whether any body_frame arrived — gates the first-frame deadline backstop. */
    val receivedAny = new java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * One ws_open cycle (design 3.3 "每周期 fiber 对"): the loopback WebSocket
   * plus TWO bounded inboxes, each drained by its own pump fiber —
   * downlink (relay → local) and uplink (local → relay, also carrying the
   * ws_open_result head and the ws_close tail). A stalled endpoint on either
   * side stalls only this cycle's pump; the shared mailbox consumer never
   * waits on a socket.
   */
  private[neblink] final class WsCycle(val requestId: String):
    val toLocal = new java.util.concurrent.LinkedBlockingQueue[WsCycle.DownlinkItem](WsQueueSlots)
    val uplink = new java.util.concurrent.LinkedBlockingQueue[WsCycle.UplinkItem](WsQueueSlots)
    /**
     * v1.3（契约 2.14）下行重组槽：cap 与 loopback 消息 cap 取等（MaxWsMessageBytes）。
     * 只被 mailbox 单消费纤维喂入；随本 cycle 摘除整体回收（无独立计时器）。
     */
    val reasm = new PortalTunnelBridge.WsReasmBuffer(PortalTunnelBridge.MaxWsMessageBytes)
    val downPump =
      new java.util.concurrent.atomic.AtomicReference[Option[cats.effect.Fiber[IO, Throwable, Unit]]](None)
    val upPump =
      new java.util.concurrent.atomic.AtomicReference[Option[cats.effect.Fiber[IO, Throwable, Unit]]](None)
    private val activated = new java.util.concurrent.atomic.AtomicBoolean(false)

    /** #949 面一：同因「inbox full」限频报警器（每周期一个；本地 listener 单生产者）。 */
    val alarm = new UplinkFullAlarm(requestId)

    /** Filled once the loopback connect completes (before pumps start). */
    @volatile var local: WebSocket = null

    /** Queue the open result at the head of the uplink (before activation). */
    def queueOpenResult(accepted: Boolean, status: Int): Unit =
      uplink.offer(WsCycle.UplinkItem.OpenResult(accepted, status))

    /** Start delivering local frames (after the open result is queued). */
    def activate(): Unit =
      if activated.compareAndSet(false, true) then
        val w = local
        if w != null then w.request(1)

    // ── #949：uplink 件的死信投影（帧原文 / 族名 / 字节读数） ──

    def itemFrame(item: WsCycle.UplinkItem): PortalWireCodec.PortalFrame = item match
      case WsCycle.UplinkItem.OpenResult(accepted, status) =>
        PortalWireCodec.WsOpenResult(requestId, accepted, status)
      case WsCycle.UplinkItem.Frame(f)                 => f
      case WsCycle.UplinkItem.Sharded(kind, data) =>
        // v1.3 死信投影 = v1 整帧形：死信补投是「周期已死后的一次性尽力而为」
        //（周期已不在注册表 ⇒ 收端不可能有同周期 v2 分片流在飞 ⇒ 无两族混流
        // 风险）；投影到 v1 面让死信件与既有格式同形（replay 只认 frameText）。
        PortalWireCodec.WsFrame(requestId, kind, data, fin = true)
      case WsCycle.UplinkItem.RelayClose(code, reason) => PortalWireCodec.WsClose(requestId, code, reason)

    def itemKind(item: WsCycle.UplinkItem): String = item match
      case _: WsCycle.UplinkItem.OpenResult => "open_result"
      case _: WsCycle.UplinkItem.Frame      => "ws_frame"
      case _: WsCycle.UplinkItem.Sharded    => "ws_data_frame"
      case _: WsCycle.UplinkItem.RelayClose => "relay_close"

    def itemBytes(item: WsCycle.UplinkItem): Long = item match
      case WsCycle.UplinkItem.Frame(f)          => f.data.length.toLong
      case WsCycle.UplinkItem.Sharded(_, data)  => data.length.toLong
      case _                                    => 0L
  end WsCycle

  object WsCycle:
    sealed trait DownlinkItem
    object DownlinkItem:
      final case class Frame(frame: PortalWireCodec.WsFrame) extends DownlinkItem
      /** Server-initiated ws_close: drain pending frames, then close the local WS. */
      final case class LocalClose(code: Int, reason: String) extends DownlinkItem
      /** The local endpoint is gone; the downlink pump exits. */
      case object LocalGone extends DownlinkItem

    sealed trait UplinkItem
    object UplinkItem:
      /** Head item: the ws_open_result precedes any local frame by construction. */
      final case class OpenResult(accepted: Boolean, status: Int) extends UplinkItem
      final case class Frame(frame: PortalWireCodec.WsFrame) extends UplinkItem
      /**
       * v1.3（契约 2.14/2.15）：一条**完整**本地消息，portal-v2 周期专用。
       * inbox 记账保持**消息**粒度（设计 §7：分片使每消息帧数 ×N，但 cycle
       * inbox 与重组槽按消息数记账）；切片在 uplink pump 的发送位上逐片进行。
       */
      final case class Sharded(kind: String, data: Array[Byte]) extends UplinkItem
      /** Tail item: propagate the close up to the relay, then the pump exits. */
      final case class RelayClose(code: Int, reason: String) extends UplinkItem
  end WsCycle

  /**
   * v1.3 上行改道单点（设计 §5.1）：portal-v2 ⇒ 整消息按 [[WsShardBytes]] 切成
   * `ws_data_frame` 帧文本序（seq 0..n-1、末片 more=false、enc=none——压缩为
   * 下一批；契约 2.14：切片按 raw 字节，空消息 = 单条空终结帧）；非 v2 ⇒ 单条
   * v1 `ws_frame`（fin=true），字节级原路（回落面 golden）。纯函数、spec 直测。
   */
  private[neblink] def encodeWsUplink(
    portalV2: Boolean,
    requestId: String,
    kind: String,
    bytes: Array[Byte]
  ): Vector[String] =
    if !portalV2 then
      Vector(PortalWireCodec.encode(PortalWireCodec.WsFrame(requestId, kind, bytes, fin = true)))
    else
      val n = math.max(1, (bytes.length + WsShardBytes - 1) / WsShardBytes)
      Vector.tabulate(n) { i =>
        val from = i * WsShardBytes
        val to = math.min(from + WsShardBytes, bytes.length)
        PortalWireCodec.encode(
          PortalWireCodec.WsDataFrame(
            requestId,
            i,
            more = i < n - 1,
            kind,
            "none",
            java.util.Arrays.copyOfRange(bytes, from, to)
          )
        )
      }

  /**
   * v1.3（契约 2.14）下行重组槽：一 cycle 一个（`WsCycle.reasm`），mailbox 单
   * 消费纤维喂入（零锁）。纪律：
   *   - seq 期望值**全周期单调**（契约 2.14：每方向每 requestId 严格 +1，
   *     more=false 是消息边界而非 seq 重置点——body_frame 2.5 同口径读到整周期）；
   *   - enc/kind 首片钉定，同消息后续帧不一致 = violation（契约 2.14 流级纪律）；
   *   - 累计字节 cap = [[MaxWsMessageBytes]]（与 loopback 消息 cap **取等**——
   *     #955 配对关系：一消息两 cap 夹逼会造成「relay 收齐了、引擎组装不下」的
   *     不对称丢环）；
   *   - 完成时交出 (kind, bytes) 并只重置字节/钉定面（seq 面保持到周期终）。
   * 生命周期：随 WsCycle 消亡（teardown / dropWsCycle 摘除即整体回收）——
   * 设计 §4.4「无新增计时器」：终结帧缺席归 cycle 撕除面，不设半消息超时回收。
   */
  private[neblink] final class WsReasmBuffer(val maxBytes: Int):
    private var expectedSeq: Int                    = 0
    private var pinnedEnc: String                   = null
    private var pinnedKind: String                  = null
    private val out: java.io.ByteArrayOutputStream  = new java.io.ByteArrayOutputStream()

    /**
     * 喂入一片：None = 已缓冲；Some((kind, bytes)) = 消息完成；Left = violation
     * （调用方丢整个 cycle）。
     */
    def feed(frame: PortalWireCodec.WsDataFrame): Either[String, Option[(String, Array[Byte])]] =
      if frame.seq != expectedSeq then
        Left(s"seq out of order (expected=$expectedSeq got=${frame.seq})")
      else if pinnedEnc != null && frame.enc != pinnedEnc then
        Left(s"enc mismatch within one message (pinned=$pinnedEnc got=${frame.enc})")
      else if pinnedKind != null && frame.kind != pinnedKind then
        Left(s"kind mismatch within one message (pinned=$pinnedKind got=${frame.kind})")
      else if out.size().toLong + frame.data.length.toLong > maxBytes.toLong then
        Left(s"reassembly over cap (max=$maxBytes, accumulated=${out.size()} at seq=${frame.seq})")
      else
        if pinnedEnc == null then
          pinnedEnc = frame.enc
          pinnedKind = frame.kind
        out.write(frame.data)
        expectedSeq += 1
        if frame.more then Right(None)
        else
          val enc   = pinnedEnc
          val kind  = pinnedKind
          val bytes = out.toByteArray
          out.reset()
          pinnedEnc = null
          pinnedKind = null
          // 压缩批（下一批）落地前：enc=gzip 到齐也显式拒绝——fail-closed，
          // 绝不把未解压字节投给本地 face（显式丢环 + WARN，非静默截断）。
          if enc == "gzip" then Left("enc=gzip message completed but this build does not decode it yet (compression batch pending)")
          else Right(Some((kind, bytes)))
  end WsReasmBuffer

end PortalTunnelBridge

/**
 * ONE connection's portal face (staging fix, deterministic repro: after a
 * relay reconnect the tunnel-level mutable bridge field could hold a bridge
 * whose mailbox consumer was dead or not yet started — frames were offered
 * into it and vanished silently, and every answer went out through a stale
 * sender holding the closed socket: `java.io.IOException: Output closed`).
 *
 * This holder replaces the shared tunnel field:
 *  - the LISTENER carries its own connection's holder, so dispatch can never
 *    cross connections (a new session's frames reach the new bridge/sender
 *    by construction; the old connection's frames die with the old bridge);
 *  - the mailbox consumer's lifecycle does NOT hang off the connectOnce
 *    chain tail (which stalls across reconnect rounds when the chain runs as
 *    a dispatcher task — the repro showed the consumer starting seconds late
 *    on the first connection and never on the second). [[armLifecycle]]
 *    drives start + teardown through the dispatcher instead, the same
 *    mechanism every other listener-spawned effect already uses.
 */
private[neblink] final class PortalConnection(dispatcher: Dispatcher[IO]):

  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

  private val bridgeRef = new java.util.concurrent.atomic.AtomicReference[Option[PortalTunnelBridge]](None)
  private val consumer =
    new java.util.concurrent.atomic.AtomicReference[Option[cats.effect.Fiber[IO, Throwable, Unit]]](None)

  /** Bind this connection's bridge (connectOnce setup, right after the upgrade). */
  def bind(bridge: PortalTunnelBridge): Unit = bridgeRef.set(Some(bridge))

  def hasBridge: Boolean = bridgeRef.get.isDefined

  /** Route one decoded frame into THIS connection's bridge (buffered until the consumer starts). */
  def dispatch(frame: PortalWireCodec.PortalFrame): Unit =
    bridgeRef.get.foreach(_.enqueueFrame(frame))

  /**
   * Self-driven lifecycle (called once from the connectOnce setup, on the
   * socket thread): start the mailbox consumer, then — when the connection's
   * `closed` latch completes — tear the bridge down and cancel the consumer.
   * Frames offered before the consumer starts are buffered by the unbounded
   * mailbox and processed as soon as it runs.
   */
  def armLifecycle(closed: cats.effect.Deferred[IO, Unit]): Unit =
    bridgeRef.get.foreach { bridge =>
      dispatcher.unsafeRunAndForget(
        bridge.run.start.flatMap(f => IO(consumer.set(Some(f)))) *>
          // 台账 #949：连接活了 ⇒ 上行死信区按序补投的第一刻（fire-and-forget：
          // 失败件留盘，等下一轮重连或下一次 boot；零墙钟，不设重试节拍——
          // #944 outboxDrain 同款装配位，位于 connected 生命周期内）。
          bridge.replayDeadLetters *>
          closed.get *> bridge.teardown() *> IO(consumer.getAndSet(None)).flatMap {
            _.fold(IO.unit)(_.cancel).void
          }
      )
    }

  /**
   * pong-reliability 批（2026-10-22「大传输触发重连」诊断修复批）：**隧道活性
   * pong 的可靠上行口**。修前 relay ping 的应答走 listener 线程裸
   * `ws.sendText` 且无视返回的 future —— 而 JDK 单发位被 bulk 帧占住时
   * `sendText` 不同步抛、只回携 `IllegalStateException("Send pending")` 的失败
   * future（NeblinkRelayTunnelHeartbeatSendSpec P1 真 socket 契约钉）⇒ bulk
   * 洪峰下 pong **静默丢失** ⇒ relay 侧 2-miss liveness 判死撕隧道（本文件
   * mailbox 注释记载的口径）⇒ 骑隧道的浏览器 WS 全体重连 = 作者症状。
   *
   * 修后：pong 过 [[RelayFrameSender]]（与业务 bulk 同一把单发位锁）——
   * `send` 内建「Send pending 重试 + 15s 预算」，且持锁等待期间 bulk 泵取不到
   * 发送位，pong 不会被业务洪峰饿死（等待上界 = 一个在飞帧的写完，有序流上
   * pong 落在其后的帧之前，线序保持）。发送失败（预算耗尽/socket 真死）在此
   * 显化留痕后吞掉——连接生死归隧道 liveness 机械管辖，pong 路径永不撕连接。
   * 桥未绑（升级竞态窗）回落修前裸发语义：尽力而为，不新增失败面。
   */
  def sendControlText(text: String, ws: java.net.http.WebSocket): Unit =
    bridgeRef.get match
      case Some(bridge) =>
        dispatcher.unsafeRunAndForget(
          bridge.controlSender.send(text).handleErrorWith { e =>
            IO(
              logger.debug(
                s"tunnel control frame (pong) not delivered after sender budget: ${NeblinkRelayTunnel.describeErr(e)}"
              )
            )
          }
        )
      case None =>
        // 桥未绑（upgrade 与首帧的竞态窗，此窗内不可能有 bulk 在飞）：修前裸发
        // 语义兜底——同步异常吞掉，与既有 case "ping" 面逐字同形。
        try { ws.sendText(text, true); () }
        catch case _: Exception => ()

end PortalConnection
