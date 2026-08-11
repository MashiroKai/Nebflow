package nebflow.core.flow

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.gateway.SessionStore

/**
 * Covers the short-name resolution fix: Nebula (no team context) mailing a
 * short name like "Backend" used to hit a NON-DETERMINISTIC global
 * collectFirst — with "Backend" present in 4 teams, the winner depended on
 * Map iteration order (observed: nebflow-rust/Backend). Now ambiguous names
 * fail with the candidate list, and "team/agent" format routes exactly.
 */
class TeamSessionRegistrySpec extends CatsEffectSuite:

  private def dummyStore: SessionStore =
    SessionStore(os.temp.dir(), os.temp.dir())

  test("no team context + unique short name resolves"):
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.registerSession("team-a", "Backend", "sid-a-backend")
      res <- TeamSessionRegistry.resolveSessionId("root-1", "Backend", dummyStore)
    yield assert(res == Right(Some("sid-a-backend")))

  test("no team context + ambiguous short name fails with candidate list"):
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.registerSession("nebflow-project", "Backend", "sid-proj-backend")
      _ <- TeamSessionRegistry.registerSession("nebflow-rust", "Backend", "sid-rust-backend")
      _ <- TeamSessionRegistry.registerSession("slideblocks", "Backend", "sid-slide-backend")
      res <- TeamSessionRegistry.resolveSessionId("root-1", "Backend", dummyStore)
    yield res match
      case Left(msg) =>
        assert(msg.contains("ambiguous"), s"should mention ambiguity: $msg")
        assert(
          msg.contains("nebflow-project") && msg.contains("nebflow-rust") && msg.contains("slideblocks"),
          s"should list candidate teams: $msg"
        )
        assert(msg.contains("team/agent"), s"should suggest team/agent format: $msg")
      case Right(_) => fail("expected an ambiguity error, got a resolution")

  test("same-team sender resolves own team's agent first (regression)"):
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.registerSession("nebflow-project", "Backend", "sid-proj-backend")
      _ <- TeamSessionRegistry.registerSession("nebflow-rust", "Backend", "sid-rust-backend")
      _ <- TeamSessionRegistry.registerSession("nebflow-project", "Manager", "sid-proj-manager")
      res <- TeamSessionRegistry.resolveSessionId("sid-proj-manager", "Backend", dummyStore)
    yield assert(res == Right(Some("sid-proj-backend")))

  test("team/agent format resolves exactly"):
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.registerSession("nebflow-project", "Backend", "sid-proj-backend")
      _ <- TeamSessionRegistry.registerSession("nebflow-rust", "Backend", "sid-rust-backend")
      res <- TeamSessionRegistry.resolveSessionId("root-1", "nebflow-rust/Backend", dummyStore)
    yield assert(res == Right(Some("sid-rust-backend")))

  test("team/agent format with unknown team returns error"):
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.registerSession("nebflow-project", "Backend", "sid-proj-backend")
      res <- TeamSessionRegistry.resolveSessionId("root-1", "nosuchteam/Backend", dummyStore)
    yield res match
      case Left(msg) => assert(msg.contains("nosuchteam"), s"should name the unknown team: $msg")
      case Right(_)  => fail("expected a team-not-found error")

  test("no match anywhere returns None"):
    for
      _ <- TeamSessionRegistry.clear
      res <- TeamSessionRegistry.resolveSessionId("root-1", "Nobody", dummyStore)
    yield assert(res == Right(None))
end TeamSessionRegistrySpec
