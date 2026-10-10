package nebflow.neblink

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import nebflow.shared.NebflowLogger
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
 * That single consumer is what makes the order-sensitive per-requestId
 * protocols correct (body_frame seq, ws_frame stream order) and what
 * serializes outbound sends on the JDK relay socket (`sendText` throws
 * IllegalStateException when the previous send has not completed). Heavy
 * work (the local HTTP app run, the local WS connect) is spawned OFF the
 * mailbox so one slow cycle cannot head-of-line-block the others; each such
 * fiber only sends frames it produced itself, ordered by construction.
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
 * `/ws` handshake's token fallback). Frames pump both ways with kind/fin
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
  private val wsCycles = new ConcurrentHashMap[String, WsCycle]

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

  private def enqueueRaw(action: IO[Unit]): Unit =
    mailbox.offer(safeguard(action))

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
              respondStatus(frame.requestId, 429, "application/json", s"""{"error":"rate_limited","reason":"$reason"}""")
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
          respondStatus(frame.requestId, 503, "text/plain", "portal bridge not wired")
      case Some(app) =>
        (for
          method <- Method.fromString(frame.method)
          uri <- Uri.fromString(frame.path)
        yield (method, uri)) match
          case Left(err) =>
            logger.warn(
              s"portal http_request face unparseable locally (requestId=${frame.requestId}): $err"
            ) *>
              respondStatus(frame.requestId, 502, "text/plain", "unparseable http_request face")
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
            runLocalRequest(frame.requestId, app, req).start.flatMap { fiber =>
              IO(channelOpt.foreach(_.cycle.set(Some(fiber))))
            }.void

  private def runLocalRequest(requestId: String, app: HttpApp[IO], req: Request[IO]): IO[Unit] =
    val headSent = new java.util.concurrent.atomic.AtomicBoolean(false)
    (for
      resp <- app.run(req)
      _ <- sender.send(
        PortalWireCodec.encode(
          PortalWireCodec.HttpResponse(requestId, resp.status.code, toResponseHeaders(resp))
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

  /** Response body streaming: seq from 0 (+1 per frame), `more=false` only on the last chunk. */
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
    headers.toList.collect {
      case (name, value) if !DropRequestHeaderNames.contains(name.toLowerCase(java.util.Locale.ROOT)) =>
        Header.Raw(CIString(name), value)
    }

  /**
   * Response headers as contract 2.4 shape (lowercased name -> single string
   * value). Multi-valued headers are comma-joined in first-seen order — the
   * v1 contract carries one string per name, and comma-joining is the
   * conservative collapse (set-cookie is not a portal-face concern in 3a).
   * Hop-by-hop artifacts of the in-process call are dropped.
   */
  private def toResponseHeaders(resp: Response[IO]): PortalWireCodec.Headers =
    val merged = new scala.collection.mutable.LinkedHashMap[String, String]()
    resp.headers.headers.foreach { h =>
      val name = h.name.toString.toLowerCase(java.util.Locale.ROOT)
      if !DropResponseHeaderNames.contains(name) then
        merged.update(name, merged.get(name).map(prev => s"$prev, ${h.value}").getOrElse(h.value))
    }
    merged.toVector

  /** Standalone status answer (429 / 502 / 503 shapes) with a one-frame plain body. */
  private def respondStatus(requestId: String, status: Int, contentType: String, body: String): IO[Unit] =
    sender.send(
      PortalWireCodec.encode(PortalWireCodec.HttpResponse(requestId, status, Vector("content-type" -> contentType)))
    ) *>
      (if body.isEmpty then IO.unit
       else
         sender.send(
           PortalWireCodec.encode(PortalWireCodec.BodyFrame(requestId, 0, more = false, body.getBytes(UTF_8)))
         ))

  // ────────────────────────── WS face ──────────────────────────

  private def handleWsOpen(frame: PortalWireCodec.WsOpen): IO[Unit] =
    val requestId = frame.requestId
    val uri = s"ws://127.0.0.1:${deps.faces.gatewayPort}${frame.path}"
    connectLocalWs(requestId, uri, frame.subprotocol)
      .flatMap { local =>
        IO { wsCycles.put(requestId, WsCycle(local)) } *>
          sender.send(
            PortalWireCodec.encode(PortalWireCodec.WsOpenResult(requestId, accepted = true, status = 101))
          )
      }
      .handleErrorWith { e =>
        val status = e match
          case hs: WebSocketHandshakeException => Option(hs.getResponse).map(_.statusCode()).getOrElse(0)
          case _                               => 0
        logger.warn(
          s"portal ws_open failed: requestId=$requestId path=${frame.path} " +
            s"error=${NeblinkRelayTunnel.describeErr(e)} — answering accepted=false status=$status"
        ) *>
          sender.send(
            PortalWireCodec.encode(PortalWireCodec.WsOpenResult(requestId, accepted = false, status = status))
          )
      }
      // Connect can take up to the connect timeout — spawned OFF the mailbox
      // (the server only sends ws_frame for this cycle after seeing the
      // ws_open_result, so no ordering hazard).
      .start
      .void

  private def connectLocalWs(requestId: String, uri: String, subprotocol: Option[String]): IO[WebSocket] =
    IO {
      val client = HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(java.time.Duration.ofSeconds(LocalConnectTimeoutSec))
        .build()
      val builder = client.newWebSocketBuilder()
      subprotocol.foreach(builder.subprotocols(_))
      builder.buildAsync(URI.create(uri), new LocalWsListener(requestId))
    }.flatMap(cf => IO.fromCompletableFuture(IO(cf)))

  private def feedWsFrame(frame: PortalWireCodec.WsFrame): IO[Unit] =
    Option(wsCycles.get(frame.requestId)) match
      case None =>
        logger.warn(s"portal ws_frame dropped: no open ws cycle requestId=${frame.requestId}")
      case Some(cycle) =>
        val fut =
          if frame.kind == "text" then cycle.local.sendText(new String(frame.data, UTF_8), frame.fin)
          else cycle.local.sendBinary(java.nio.ByteBuffer.wrap(frame.data), frame.fin)
        IO.blocking(fut.get(LocalSendTimeoutSec.toLong, TimeUnit.SECONDS)).void.handleErrorWith { e =>
          dropWsCycle(frame.requestId, cycle, s"local ws send failed (${NeblinkRelayTunnel.describeErr(e)})")
        }

  private def handleWsClose(frame: PortalWireCodec.WsClose): IO[Unit] =
    Option(wsCycles.remove(frame.requestId)) match
      case None =>
        logger.debug(s"portal ws_close for unknown cycle requestId=${frame.requestId} — ignored")
      case Some(cycle) =>
        IO.blocking {
          try cycle.local.sendClose(frame.code, frame.reason)
          catch case _: Exception => ()
          ()
        }

  private def dropWsCycle(requestId: String, cycle: WsCycle, reason: String): IO[Unit] =
    logger.warn(s"portal ws cycle dropped: requestId=$requestId reason=$reason")
    IO { wsCycles.remove(requestId, cycle) } *>
      IO.blocking {
        try cycle.local.abort()
        catch case _: Exception => ()
      }.void

  private def unregisterWsCycle(requestId: String, ws: WebSocket): Unit =
    wsCycles.remove(requestId, ws)

  /**
   * JDK listener for the LOCAL side of a ws_open cycle. Every callback
   * enqueues the uplink frame through the SAME mailbox (single consumer ⇒
   * uplink order preserved even against concurrent HTTP cycles).
   */
  private final class LocalWsListener(val requestId: String) extends WebSocket.Listener:
    override def onOpen(ws: WebSocket): Unit = ws.request(1)

    override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      enqueueUplink(PortalWireCodec.WsFrame(requestId, "text", data.toString.getBytes(UTF_8), last))
      ws.request(1)
      null

    override def onBinary(ws: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage[?] =
      val bytes = new Array[Byte](data.remaining())
      data.get(bytes)
      enqueueUplink(PortalWireCodec.WsFrame(requestId, "binary", bytes, last))
      ws.request(1)
      null

    override def onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
      unregisterWsCycle(requestId, ws)
      enqueueRaw(sender.send(PortalWireCodec.encode(PortalWireCodec.WsClose(requestId, statusCode, reason))))
      null

    override def onError(ws: WebSocket, error: Throwable): Unit =
      unregisterWsCycle(requestId, ws)
      logger.debugSync(
        s"portal local ws error: requestId=$requestId error=${NeblinkRelayTunnel.describeErr(error)}"
      )
      enqueueRaw(
        sender.send(
          PortalWireCodec.encode(
            PortalWireCodec.WsClose(requestId, 1011, error.getClass.getSimpleName)
          )
        )
      )

    private def enqueueUplink(frame: PortalWireCodec.WsFrame): Unit =
      enqueueRaw(sender.send(PortalWireCodec.encode(frame)))
  end LocalWsListener

  // ────────────────────────── console ticket mint ──────────────────────────

  /**
   * Policy checkpoints first (design 3.4): terminal binding => `unbound`;
   * mint budget exhausted => `rate_limited`; (the `quiesced` enum value has
   * no live path in 3a — the quiesce family is server-side phase 5 material;
   * the response shape supports it.) Success => ticket + expiresInSec.
   */
  private def handleConsoleTicketMint(frame: PortalWireCodec.ConsoleTicketMint): IO[Unit] =
    val result: IO[PortalWireCodec.ConsoleTicketResult] =
      deps.bindingState.isUnbound.flatMap {
        case true => IO.pure(mintError(frame, "unbound"))
        case false =>
          deps.consoleTickets.tryConsumeMintBudget().flatMap {
            case false => IO.pure(mintError(frame, "rate_limited"))
            case true =>
              deps.identity.flatMap { id =>
                deps.consoleTickets.issue(frame.accountSub, id.deviceId).map { issued =>
                  PortalWireCodec.ConsoleTicketResult(
                    requestId = frame.requestId,
                    mintId = frame.mintId,
                    ticket = Some(issued.token),
                    expiresInSec = Some(issued.expiresInSec.toInt),
                    error = None
                  )
                }
              }
          }
      }
    result.flatMap(r => sender.send(PortalWireCodec.encode(r)))

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
   * relay at low frequency until row 4 clears the state. Phase 3b extends
   * this with full-session revocation.
   */
  private def handleBindingControl(frame: PortalWireCodec.BindingControl): IO[Unit] =
    frame.kind match
      case "revoked" =>
        deps.bindingState.markUnbound().flatMap { transitioned =>
          deps.consoleTickets.revokeAll().flatMap { dropped =>
            logger.warn(
              "BindingControl revoked: host binding is TERMINAL — console tickets invalidated " +
                s"(dropped=$dropped, transitioned=$transitioned); the relay tool face stops serving " +
                "and no session self-heal/re-login will be attempted"
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
   * Connection-end cleanup: end every open body channel (so the spawned app
   * fibers finish instead of hanging on `queue.take`) and abort every local
   * WS (its onClose enqueues the final ws_close, which will fail fast on the
   * dead relay socket — harmless).
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
        IO {
          drain(cycles).foreach { c =>
            try c.local.abort()
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

  private val DropRequestHeaderNames: Set[String] =
    Set("transfer-encoding", "connection", "keep-alive", "upgrade")
  private val DropResponseHeaderNames: Set[String] =
    Set("transfer-encoding", "connection", "keep-alive")

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

  private[neblink] final case class WsCycle(local: WebSocket)

end PortalTunnelBridge
