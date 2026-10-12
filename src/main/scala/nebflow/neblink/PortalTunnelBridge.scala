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
import java.util.concurrent.{ConcurrentHashMap, CompletionStage, TimeUnit}

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
 * WebSocket (`ws://127.0.0.1:<gatewayPort><path>`; the path may carry a
 * query string — stage A passes `?token=<file token>`, consumed by the local
 * `/ws` handshake's token fallback). v1.1: a ws_open `headers` object is
 * injected into the loopback handshake (the browser session cookie crosses
 * the bridge; absent keeps the v1 shape). Frames pump both ways with kind/fin
 * mapped 1:1; `ws_close` propagates both ways.
 */
private[neblink] final class PortalTunnelBridge(
  deps: PortalTunnelDeps,
  sender: RelayFrameSender
):

  import PortalTunnelBridge.*

  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

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
    frame match
      case f: PortalWireCodec.HttpRequest        => handleHttpRequest(f)
      case f: PortalWireCodec.BodyFrame          => feedBodyFrame(f)
      case f: PortalWireCodec.WsOpen             => handleWsOpen(f)
      case f: PortalWireCodec.WsFrame            => feedWsFrame(f)
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
                s"envelopeIp=$envelopeIp reason=$reason — answering 429"
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

    override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      offerUp(
        WsCycle.UplinkItem.Frame(
          PortalWireCodec.WsFrame(requestId, "text", data.toString.getBytes(UTF_8), last)
        )
      )
      ws.request(1)
      null

    override def onBinary(ws: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage[?] =
      val bytes = new Array[Byte](data.remaining())
      data.get(bytes)
      offerUp(WsCycle.UplinkItem.Frame(PortalWireCodec.WsFrame(requestId, "binary", bytes, last)))
      ws.request(1)
      null

    override def onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
      // Local endpoint closed: propagate the close UP (tail marker — the uplink
      // pump drains pending frames first, then sends ws_close and exits) and
      // stop the downlink pump (nothing more can be forwarded).
      wsCycles.remove(requestId, cycle)
      offerUp(WsCycle.UplinkItem.RelayClose(statusCode, reason))
      cycle.toLocal.offer(WsCycle.DownlinkItem.LocalGone)
      null

    override def onError(ws: WebSocket, error: Throwable): Unit =
      logger.debugSync(
        s"portal local ws error: requestId=$requestId error=${NeblinkRelayTunnel.describeErr(error)}"
      )
      wsCycles.remove(requestId, cycle)
      offerUp(WsCycle.UplinkItem.RelayClose(1011, error.getClass.getSimpleName))
      cycle.toLocal.offer(WsCycle.DownlinkItem.LocalGone)

    private def offerUp(item: WsCycle.UplinkItem): Unit =
      if !cycle.uplink.offer(item) then
        logger.warnSync(
          s"portal uplink inbox full: requestId=$requestId — uplink item dropped (relay slower than the local peer)"
        )
  end LocalWsListener

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
        IO {
          drain(cycles).foreach { c =>
            val local = c.local
            if local != null then
              try local.abort()
              catch case _: Exception => ()
          }
        }
    }

end PortalTunnelBridge

/** Frames sent to the relay WS, serialized per socket (JDK sendText ordering contract). */
private[neblink] final class RelayFrameSender(ws: WebSocket):

  private val lock = new Object

  /**
   * Send one complete text frame. The lock serializes ALL portal uplink
   * frames on this socket: `java.net.http.WebSocket.sendText` throws
   * IllegalStateException when the previous send has not completed, so
   * concurrent fibers (mailbox consumer + per-cycle streamers) must not race.
   * The bounded wait keeps a stalled relay from wedging the senders forever.
   */
  def send(text: String): IO[Unit] =
    IO.blocking {
      lock.synchronized {
        ws.sendText(text, true).get(RelayFrameSender.SendTimeoutSec.toLong, TimeUnit.SECONDS)
        ()
      }
    }

object RelayFrameSender:
  /** Non-contract implementation constant (backpressure tuning belongs to T-1). */
  val SendTimeoutSec: Int = 15

private[neblink] object PortalTunnelBridge:

  /** Non-contract implementation constants (contract section 4 values are placeholders pending T-1). */
  val BodyChunkBytes: Int = 256 * 1024
  val BodyQueueSlots: Int = 64
  val LocalConnectTimeoutSec: Int = 10
  val LocalSendTimeoutSec: Int = 5

  /** Per-cycle WS pump inbox depth (downlink and uplink alike). */
  val WsQueueSlots: Int = 128

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
    val downPump =
      new java.util.concurrent.atomic.AtomicReference[Option[cats.effect.Fiber[IO, Throwable, Unit]]](None)
    val upPump =
      new java.util.concurrent.atomic.AtomicReference[Option[cats.effect.Fiber[IO, Throwable, Unit]]](None)
    private val activated = new java.util.concurrent.atomic.AtomicBoolean(false)

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
      /** Tail item: propagate the close up to the relay, then the pump exits. */
      final case class RelayClose(code: Int, reason: String) extends UplinkItem
  end WsCycle

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
        bridge.run.start.flatMap(f => IO(consumer.set(Some(f)))) >>
          closed.get *> bridge.teardown() *> IO(consumer.getAndSet(None)).flatMap {
            _.fold(IO.unit)(_.cancel).void
          }
      )
    }

end PortalConnection
