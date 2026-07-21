package nebflow.core.flow

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.actor.{ActorPath, ActorRef}

import scala.concurrent.duration.FiniteDuration

class FlowTreeRegistrySpec extends CatsEffectSuite:

  private def fakeRef(name: String): ActorRef[TreeCommand] = new ActorRef[TreeCommand]:
    val path = ActorPath("local", List(name))
    def !(msg: TreeCommand): IO[Unit] = IO.unit
    def ?[Reply](makeMsg: ActorRef[Reply] => TreeCommand, timeout: Option[FiniteDuration]): IO[Reply] =
      IO.never[Reply]

  test("register and get returns the actor ref"):
    for
      _ <- FlowTreeRegistry.clear
      _ <- FlowTreeRegistry.register("session-1", fakeRef("flow-tree-test"))
      result <- FlowTreeRegistry.get("session-1")
    yield assert(result.isDefined, "Should return registered ref")

  test("get returns None for unregistered session"):
    for
      _ <- FlowTreeRegistry.clear
      result <- FlowTreeRegistry.get("nonexistent")
    yield assert(result.isEmpty, "Should return None for unregistered session")

  test("unregister removes the mapping"):
    for
      _ <- FlowTreeRegistry.clear
      _ <- FlowTreeRegistry.register("session-1", fakeRef("ft-1"))
      before <- FlowTreeRegistry.get("session-1")
      _ <- FlowTreeRegistry.unregister("session-1")
      after <- FlowTreeRegistry.get("session-1")
    yield
      assert(before.isDefined, "Should exist before unregister")
      assert(after.isEmpty, "Should be gone after unregister")

  test("register overwrites existing mapping"):
    for
      _ <- FlowTreeRegistry.clear
      _ <- FlowTreeRegistry.register("session-1", fakeRef("ft-1"))
      _ <- FlowTreeRegistry.register("session-1", fakeRef("ft-2"))
      result <- FlowTreeRegistry.get("session-1")
    yield assert(result.exists(_.path.name == "ft-2"), "Should return the latest ref")

  test("multiple sessions registered independently"):
    for
      _ <- FlowTreeRegistry.clear
      _ <- FlowTreeRegistry.register("session-a", fakeRef("ft-a"))
      _ <- FlowTreeRegistry.register("session-b", fakeRef("ft-b"))
      resultA <- FlowTreeRegistry.get("session-a")
      resultB <- FlowTreeRegistry.get("session-b")
    yield
      assert(resultA.exists(_.path.name == "ft-a"), "Session A has correct ref")
      assert(resultB.exists(_.path.name == "ft-b"), "Session B has correct ref")
end FlowTreeRegistrySpec
