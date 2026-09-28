package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import scala.concurrent.duration.*

/**
  * Headless synchronous turn executor (P0 benchmark — /tmp/headless-design.md
  * D1/D2). Standalone object (zero class deps) so it is directly unit-testable
  * — RestApiRoutes only wires auth/session validation around it.
  *
  * Completion is observed through a WsHub listener: the agent's turn lifecycle
  * already broadcasts `done` (UiMessage flush happens inside the recording
  * wsSend BEFORE the event is forwarded) and then `sessionBusy busy=false`.
  * The actor is untouched — the Deferred is awaited on the caller's fiber,
  * and the listener callback is fire-and-forget from the actor's perspective.
  */
object TurnEndpoint:

  /**
    * Turn gate: at most one in-flight synchronous turn per session. The
    * actor's ImmediateInput queueing still exists underneath — this gate only
    * stops HTTP callers from racing two synchronous turns whose completion
    * events would be indistinguishable. Object-level singleton: one gate set
    * per JVM, shared across route instances.
    */
  private val inFlightTurns: cats.effect.Ref[IO, Set[String]] =
    cats.effect.Ref.unsafe[IO, Set[String]](Set.empty)

  private[gateway] def acquireGate(sessionId: String): IO[Boolean] =
    inFlightTurns.modify(s => if s.contains(sessionId) then (s, false) else (s + sessionId, true))

  private[gateway] def releaseGate(sessionId: String): IO[Unit] =
    inFlightTurns.update(_ - sessionId) // idempotent

  /** Gate a turn body; Conflict when the session already has one in flight. */
  def gated(sessionId: String)(body: IO[Response[IO]]): IO[Response[IO]] =
    acquireGate(sessionId).flatMap {
      case false =>
        IO.pure(
          Response[IO](Status.Conflict).withEntity(
            Json.obj("error" -> "a synchronous turn is already in flight for this session".asJson)
          )
        )
      case true => body.guarantee(releaseGate(sessionId))
    }

  /** Wire the turn lifecycle through a WsHub listener. */
  def runTurn(
    wsHub: WsHub,
    sessionStore: SessionStore,
    dispatch: (String, String) => IO[Unit],
    sessionId: String,
    content: String,
    timeoutSec: Int
  ): IO[Response[IO]] =
    val startedAt = System.currentTimeMillis()
    for
      usageRef <- cats.effect.Ref.of[IO, Option[Json]](None)
      errorRef <- cats.effect.Ref.of[IO, Option[String]](None)
      dispatchedRef <- cats.effect.Ref.of[IO, Boolean](false)
      doneSignal <- cats.effect.Deferred[IO, Unit]
      // Position window: every UiMessage appended after this count belongs to
      // this turn (UiMessage.Tool carries no timestamp, so time-windowing is
      // not precise enough — index slicing is).
      totalBefore <- sessionStore.getUiMessages(sessionId, 0, 0).map(_._2)
      listenerId <- wsHub.register { json =>
        val hc = json.hcursor
        val t = hc.downField("type").as[String].getOrElse("")
        val sid = hc.downField("sessionId").as[String].getOrElse("")
        if sid != sessionId then IO.unit
        else
          t match
            case "sessionBusy" if !hc.downField("busy").as[Boolean].getOrElse(true) =>
              // Completion signal. Guarded on dispatched: a stale busy=false
              // tail from a previous turn arriving between listener setup and
              // our ImmediateInput dispatch must not complete us early.
              dispatchedRef.get.flatMap(was => if was then doneSignal.complete(()).void else IO.unit)
            case "done" =>
              // depth-0 done carries model/contextWindow/inputTokens — capture
              // verbatim for the response usage field (better than estimating).
              usageRef.set(Some(Json.obj(
                "model" -> hc.downField("model").as[Option[String]].getOrElse(None).asJson,
                "contextWindow" -> hc.downField("contextWindow").as[Option[Int]].getOrElse(None).asJson,
                "inputTokens" -> hc.downField("inputTokens").as[Option[Int]].getOrElse(None).asJson
              )))
            case "error" =>
              hc.downField("message").as[String].toOption match
                case Some(msg) => errorRef.set(Some(msg))
                case None      => IO.unit
            case _ => IO.unit
      }
      // Persist the user bubble + dispatch ImmediateInput (same sequence as
      // the WS immediateInput/userMessage cases — production wiring passes
      // wsRoutes.dispatchUserText here).
      _ <- dispatch(sessionId, content)
      _ <- dispatchedRef.set(true)
      completed <- doneSignal.get.as(true).timeoutTo(timeoutSec.seconds, IO.pure(false))
      _ <- wsHub.unregister(listenerId) // idempotent; also runs on the 504 path
      (finalMessage, toolCalls) <- readTurnResult(sessionId, totalBefore, sessionStore)
      errorOpt <- errorRef.get
      usageOpt <- usageRef.get
    yield
      val body = Json.obj(
        "status" -> (if completed then
                      (if errorOpt.isDefined then "error" else "completed")
                    else "timeout").asJson,
        "sessionId" -> sessionId.asJson,
        "finalMessage" -> finalMessage.asJson,
        "toolCalls" -> toolCalls.asJson,
        "usage" -> usageOpt.getOrElse(Json.Null).asJson,
        "durationMs" -> (System.currentTimeMillis() - startedAt).asJson,
        "error" -> errorOpt.fold(Json.Null.asJson)(_.asJson)
      )
      if completed then Response[IO](Status.Ok).withEntity(body)
      else Response[IO](Status.GatewayTimeout).withEntity(body)
    end for
  end runTurn

  /**
    * Extract this turn's result from the UiMessage stream. The recording
    * wsSend flushes the final Ai bubble as part of the done event — BEFORE
    * the busy=false broadcast reaches our listener — so the first read
    * normally hits. The 200ms × 25 retry is defensive (slow disk flush,
    * backfill-only turns): on exhaustion it degrades to an empty
    * finalMessage while keeping status=completed (a lagging persist is not a
    * turn failure).
    */
  private def readTurnResult(
    sessionId: String,
    totalBefore: Int,
    sessionStore: SessionStore
  ): IO[(String, List[Json])] =
    def attempt(remaining: Int): IO[(String, List[Json])] =
      sessionStore.getUiMessages(sessionId, 0, 0).flatMap { case (msgs, _) =>
        val window = msgs.drop(totalBefore)
        window.collectFirst { case a: nebflow.shared.UiMessage.Ai => a } match
          case Some(_) =>
            val finalMessage =
              window.collect { case a: nebflow.shared.UiMessage.Ai => a.text }.lastOption.getOrElse("")
            val toolCalls = window.collect { case t: nebflow.shared.UiMessage.Tool =>
              Json.obj("label" -> t.label.asJson, "summary" -> t.summary.asJson, "isError" -> t.isError.asJson)
            }
            IO.pure((finalMessage, toolCalls))
          case None =>
            if remaining > 0 then IO.sleep(200.millis) *> attempt(remaining - 1)
            else IO.pure(("", Nil))
      }
    attempt(25)
  end readTurnResult

end TurnEndpoint
