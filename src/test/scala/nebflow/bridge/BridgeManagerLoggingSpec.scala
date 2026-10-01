package nebflow.bridge

import cats.effect.{IO, Ref}
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.shared.SessionMeta
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/** Observability spec for the [[BridgeManager]] lifecycle legs.
  *
  * Why this exists: a deactivation reaches [[BridgeManager.unregister]], and on
  * the success / absent-name path that leg used to emit nothing. The only
  * readable trace of it was the ABSENCE of `Bridge plugin 'x' started`, which
  * conflates three distinct situations — never attempted, verdict false, start
  * failed. These guards pin the per-leg log line so the negative verdict is
  * readable from the log alone.
  *
  * The log lines are captured with a logback ListAppender on the
  * `nebflow.bridge` logger (same in-repo form as OpenAiAdapterSpec) — no new
  * test dependency, logback is already a main dependency.
  */
class BridgeManagerLoggingSpec extends CatsEffectSuite:

  private object NoopCtx extends BridgeContext:
    def injectMessage(sessionId: String, content: String, senderId: Option[String],
        origin: Option[nebflow.bridge.BridgeOrigin] = None): IO[Unit] = IO.unit
    def interruptAgent(sessionId: String): IO[Unit] = IO.unit
    def sessionMeta(sessionId: String): IO[Option[SessionMeta]] = IO.none
    def listSessions: IO[List[SessionMeta]] = IO.pure(Nil)
    def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit] = IO.unit

  private final class FakePlugin(val name: String, calls: Ref[IO, List[String]]) extends BridgePlugin:
    def start(ctx: BridgeContext): IO[Unit] = calls.update(_ :+ s"start:$name")
    def stop: IO[Unit] = calls.update(_ :+ s"stop:$name")
    def onAgentEvent(sessionId: String, event: Json): IO[Unit] = IO.unit

  private def newCalls: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

  /** Attach a list appender to the `nebflow.bridge` logger, run `io`, and hand
    * back both the result and every event that logger emitted. */
  private def capture[A](io: IO[A]): IO[(A, List[ILoggingEvent])] =
    val lb = LoggerFactory.getLogger("nebflow.bridge").asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    IO.delay { appender.start(); lb.addAppender(appender) }
      .bracket { _ => io.map(a => (a, appender.list.asScala.toList)) }(
        _ => IO.delay { lb.detachAppender(appender); appender.stop() }
      )

  private def infos(events: List[ILoggingEvent]): List[String] =
    events.filter(_.getLevel == Level.INFO).map(_.getFormattedMessage)

  // ── the main-goal leg: unregister success / no-op must leave a trace ──────

  test("unregister on a registered plugin logs an INFO line naming the plugin") {
    val calls = newCalls
    for
      mgr <- BridgeManager.create(NoopCtx)
      _ <- mgr.register(FakePlugin("feishu", calls))
      out <- capture(mgr.unregister("feishu"))
      (_, events) = out
      seen <- calls.get
    yield
      val msgs = infos(events)
      assert(
        msgs.exists(m => m.contains("feishu") && m.contains("unregister")),
        s"expected an INFO unregister line for 'feishu', got $msgs"
      )
      assertEquals(seen, List("stop:feishu"), "unregister must stop the plugin")
  }

  test("unregister on an absent name still logs the verdict (the negative-verdict leg)") {
    for
      mgr <- BridgeManager.create(NoopCtx)
      out <- capture(mgr.unregister("feishu")) // never registered: the sync case-false path
      (_, events) = out
      _ = assert(
        infos(events).exists(m => m.contains("feishu") && m.contains("unregister")),
        s"absent-name unregister is the sync verdict-false leg and must be readable, got ${infos(events)}"
      )
      names <- mgr.registeredNames
    yield assertEquals(names, Set.empty[String])
  }

  test("unregister logs once per call — no per-round spam") {
    val calls = newCalls
    for
      mgr <- BridgeManager.create(NoopCtx)
      _ <- mgr.register(FakePlugin("feishu", calls))
      out <- capture(mgr.unregister("feishu") *> mgr.unregister("feishu"))
      (_, events) = out
    yield assertEquals(
      infos(events).count(m => m.contains("feishu") && m.contains("unregister")),
      2,
      "one INFO line per unregister call"
    )
  }

  // ── the allowed same-family second leg: stopAll success ──────────────────

  test("stopAll logs an INFO line per stopped plugin") {
    val calls = newCalls
    for
      mgr <- BridgeManager.create(NoopCtx)
      _ <- mgr.register(FakePlugin("feishu", calls))
      out <- capture(mgr.stopAll)
      (_, events) = out
      seen <- calls.get
    yield
      val msgs = infos(events)
      assert(
        msgs.exists(m => m.contains("feishu") && m.contains("stop")),
        s"expected an INFO stop line for 'feishu', got $msgs"
      )
      assertEquals(seen, List("stop:feishu"))
  }

  // ── pure-predicate coverage (量具 ①, zero new dependency) ─────────────────

  test("registeredNames tracks register/unregister exactly") {
    val calls = newCalls
    for
      mgr <- BridgeManager.create(NoopCtx)
      before <- mgr.registeredNames
      _ <- mgr.register(FakePlugin("feishu", calls))
      afterRegister <- mgr.registeredNames
      _ <- mgr.unregister("feishu")
      afterUnregister <- mgr.registeredNames
      _ <- mgr.unregister("feishu") // idempotent, absent
      afterIdempotent <- mgr.registeredNames
    yield
      assertEquals(before, Set.empty[String])
      assertEquals(afterRegister, Set("feishu"))
      assertEquals(afterUnregister, Set.empty[String])
      assertEquals(afterIdempotent, Set.empty[String])
  }

end BridgeManagerLoggingSpec
