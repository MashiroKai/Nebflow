package nebflow.core

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import scala.concurrent.duration.*
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.UiMessage
import java.nio.file.Files

class SessionStoreUiWriteRaceSpec extends CatsEffectSuite:
  test("concurrent first writers share one session lock and lose no updates"):
    val root = os.Path(Files.createTempDirectory("ui-first-race"))
    val store = SessionStore(root / "sessions", root / "tasks")
    val sid = "first-race"
    for
      ready <- Deferred[IO, Unit]
      gate <- Deferred[IO, Unit]
      count <- Ref.of[IO, Int](0)
      _ = store.beforeUiLockInstall = _ => count.updateAndGet(_ + 1).flatMap(n => if n == 2 then ready.complete(()).void else IO.unit) *> gate.get
      one <- store.appendUiMessages(sid, List(UiMessage.User("one"))).start
      two <- store.appendUiMessages(sid, List(UiMessage.User("two"))).start
      _ <- ready.get
      _ <- gate.complete(())
      _ <- one.joinWithNever *> two.joinWithNever
      _ <- store.flushPendingUiWrites
      rows <- store.getUiMessages(sid, 0, 100).map(_._1)
      _ = assertEquals(rows.collect { case u: UiMessage.User => u.text }.toSet, Set("one", "two"))
    yield ()

  private def writerWaitsForFlush(name: String)(writer: (SessionStore, String) => IO[Unit]): IO[Unit] =
    val root = os.Path(Files.createTempDirectory("ui-writer-race"))
    val store = SessionStore(root / "sessions", root / "tasks")
    val sid = name
    for
      entered <- Deferred[IO, Unit]
      gate <- Deferred[IO, Unit]
      done <- Ref.of[IO, Boolean](false)
      first <- Ref.of[IO, Boolean](true)
      _ = store.beforeUiFlushWrite = _ => first.getAndSet(false).flatMap(isFirst =>
        if isFirst then entered.complete(()).void *> IO.uncancelable(_ => gate.get) else IO.unit)
      _ <- store.appendUiMessages(sid, List(UiMessage.User("one")))
      flush <- store.flushPendingUiWrites.start
      _ <- entered.get.timeout(5.seconds)
      operation <- (writer(store, sid) *> done.set(true)).start
      _ <- IO.sleep(200.millis)
      premature <- done.get
      _ <- gate.complete(())
      _ <- flush.joinWithNever *> operation.joinWithNever
      cached <- store.getUiMessages(sid, 0, 100).map(_._1)
      cold = SessionStore(root / "sessions", root / "tasks")
      disk <- cold.getUiMessages(sid, 0, 100).map(_._1)
      _ = assert(!premature, s"$name completed while flush held the lock")
      _ = assertEquals(disk, cached)
    yield ()

  test("save waits for in-flight flush"):
    writerWaitsForFlush("save")((store, sid) => store.setSessionFromCloud(sid, Nil, List(UiMessage.User("replacement"))))

  test("delete waits for in-flight flush"):
    writerWaitsForFlush("delete")((store, sid) => store.deleteUiMessages(sid))

  test("cloud merge waits for in-flight flush"):
    writerWaitsForFlush("merge")((store, sid) =>
      store.mergeSessionFromCloud(sid, Nil, List(UiMessage.User("one"), UiMessage.User("two"))).void)

  test("delayed old flush cannot overwrite a newer flush; disk equals cache"):
    val root = os.Path(Files.createTempDirectory("ui-flush-race"))
    val store = SessionStore(root / "sessions", root / "tasks")
    val sid = "flush-race"
    for
      entered <- Deferred[IO, Unit]
      gate <- Deferred[IO, Unit]
      startE <- Deferred[IO, Unit]
      faDone <- Ref.of[IO, Boolean](false)
      feDone <- Ref.of[IO, Boolean](false)
      first <- Ref.of[IO, Boolean](true)
      _ = store.beforeUiFlushWrite = _ => first.getAndSet(false).flatMap(isFirst =>
        if isFirst then entered.complete(()).void *> IO.uncancelable(_ => gate.get) else IO.unit)
      program = for
        _ <- store.appendUiMessages(sid, List(UiMessage.User("one")))
        _ <- IO.sleep(501.millis)
        _ <- entered.get
        fa <- (store.appendUiMessages(sid, List(UiMessage.User("two"))) *> faDone.set(true)).start
        fe <- (startE.get *> store.flushPendingUiWrites *> feDone.set(true)).start
        _ <- gate.get
        _ <- fa.joinWithNever *> fe.joinWithNever
        _ <- IO.sleep(1500.millis)
        cached <- store.getUiMessages(sid, 0, 100).map(_._1)
        cold = SessionStore(root / "sessions", root / "tasks")
        disk <- cold.getUiMessages(sid, 0, 100).map(_._1)
      yield (cached, disk)
      control <- TestControl.execute(program)
      _ <- control.tick
      _ <- control.advance(501.millis)
      _ <- control.tick
      tickUntil = (name: String, p: IO[Boolean], budget: FiniteDuration) => {
        def loop(deadline: FiniteDuration): IO[Unit] =
          p.flatMap { ok =>
            if ok then IO.unit
            else IO.monotonic.flatMap { now =>
              if now >= deadline then IO.raiseError(new AssertionError(s"等待信号 $name 超过截止时间"))
              else control.tick *> IO.cede *> loop(deadline)
            }
          }
        IO.monotonic.flatMap(now => loop(now + budget))
      }
      _ <- tickUntil("首次写盘进入", entered.tryGet.map(_.nonEmpty), 5.seconds)
      _ <- tickUntil("追加完成（可超时）", faDone.get, 100.millis).attempt.void
      _ <- startE.complete(())
      _ <- tickUntil("显式 flush 完成（可超时）", feDone.get, 100.millis).attempt.void
      _ <- gate.complete(())
      _ <- tickUntil("追加与显式 flush 均完成", (faDone.get, feDone.get).mapN(_ && _), 5.seconds)
      _ <- control.tick *> control.advance(2.seconds)
      _ <- tickUntil("测试程序完成", control.results.map(_.nonEmpty), 5.seconds)
      result <- control.results
      pair <- result match
        case Some(cats.effect.kernel.Outcome.Succeeded(value)) => IO.pure(value)
        case Some(cats.effect.kernel.Outcome.Errored(error)) => IO.raiseError(error)
        case _ => IO.raiseError(new AssertionError("测试纤程未完成"))
      (cached, disk) = pair
      _ = assertEquals(cached.collect { case u: UiMessage.User => u.text }, List("one", "two"))
      _ = assertEquals(disk, cached)
    yield ()
