package nebflow.gateway

import cats.effect.{Async, Ref}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.Json
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration

private[nebflow] object TakeoverSettler:
  type Active = (String, String, nebflow.core.browser.BrowserScreencast, () => Unit)
  enum Outcome:
    case NothingToSettle, SettledAsFailure, SettledNormally

  final class Settler[F[_]: Async](
      releaseResources: Active => F[Unit],
      releaseLease: String => F[Unit],
      recordFailure: String => F[Unit],
      finishNormally: (String, String) => F[Unit],
      send: Json => F[Unit],
      frameTimeout: FiniteDuration,
      logError: (String, Throwable) => F[Unit]
  ):
    private val state = Ref.unsafe[F, Option[Active]](None)
    private val failedStream = new AtomicReference[nebflow.core.browser.BrowserScreencast](null)
    private val failureFrame = Json.obj("type" -> Json.fromString("status"), "error" -> Json.fromString("画面流故障，接管已停止"))

    private def isFailed(a: Active): F[Boolean] = Async[F].delay(failedStream.get() eq a._3)
    def markFailed(stream: nebflow.core.browser.BrowserScreencast): Unit = failedStream.set(stream)
    def current: F[Option[Active]] = state.get
    def register(a: Active): F[Unit] = state.set(Some(a)) *> recheck
    def recheck: F[Unit] = state.get.flatMap(_.traverse_(a => isFailed(a).ifM(settle(true).void, Async[F].unit)))
    def tick: F[Unit] = recheck
    def finish: F[Outcome] = settle(true)
    def finalizer: F[Unit] = settle(false).void

    def settle(sendFrame: Boolean): F[Outcome] =
      Async[F].uncancelable { poll =>
        state.getAndSet(None).flatMap {
          case None => Async[F].pure(Outcome.NothingToSettle)
          case Some(a @ (id, session, _, _)) =>
            val release = releaseResources(a)
            isFailed(a).flatMap {
              case true =>
                (attempted(releaseLease(id), s"takeover lease release failed: $id") *>
                  attempted(recordFailure(id), s"takeover failure ledger write failed: $id")).guarantee(release) *>
                  (if sendFrame then poll(send(failureFrame).timeoutTo(frameTimeout,
                    logError(s"takeover failure frame timed out: $id", new java.util.concurrent.TimeoutException))) else Async[F].unit) *>
                  Async[F].pure(Outcome.SettledAsFailure)
              case false => finishNormally(id, session).guarantee(release).as(Outcome.SettledNormally)
            }
        }
      }

    private def attempted(fa: F[Unit], msg: String): F[Unit] = fa.attempt.flatMap {
      case Left(e) => logError(msg, e)
      case Right(_) => Async[F].unit
    }
