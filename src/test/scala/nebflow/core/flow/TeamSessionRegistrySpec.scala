package nebflow.core.flow

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.gateway.SessionStore

/**
 * Covers the Mail address-semantics rule (user requirement, on top of the
 * b9d427c6 ambiguity fix):
 *
 *   - Senders WITHOUT a team context (Nebula root / standalone) mail TEAM
 *     names only — the team Manager dispatches to members. Bare short names
 *     are rejected by rule (never a non-deterministic global pick).
 *   - Senders INSIDE a team use short names: same-team first, and on a
 *     same-team miss the global fallback still runs ambiguity detection.
 *   - "team/agent" is an explicit scoped route valid from anywhere.
 */
class TeamSessionRegistrySpec extends CatsEffectSuite:

  private def dummyStore: SessionStore =
    SessionStore(os.temp.dir(), os.temp.dir())

  test("no team context + short name rejected by routing rule (unique)"):
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.registerSession("team-a", "Backend", "sid-a-backend")
      res <- TeamSessionRegistry.resolveSessionId("root-1", "Backend", dummyStore)
    yield res match
      case Left(msg) =>
        assert(msg.contains("outside a team"), s"should cite the routing rule: $msg")
        assert(msg.contains("TEAM name"), s"should suggest a team name: $msg")
      case Right(_) => fail("short name must be rejected for no-team senders")

  test("no team context + short name rejected (ambiguous too — rule, not guess)"):
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.registerSession("nebflow-project", "Backend", "sid-proj-backend")
      _ <- TeamSessionRegistry.registerSession("nebflow-rust", "Backend", "sid-rust-backend")
      res <- TeamSessionRegistry.resolveSessionId("root-1", "Backend", dummyStore)
    yield res match
      case Left(msg) =>
        assert(msg.contains("outside a team"), s"should cite the routing rule: $msg")
        assert(!msg.contains("ambiguous"), s"rule rejection, not ambiguity guess: $msg")
      case Right(_) => fail("short name must be rejected for no-team senders")

  test("no team context + Nebula is exempt (returns None, caller routes to root)"):
    for
      _ <- TeamSessionRegistry.clear
      res <- TeamSessionRegistry.resolveSessionId("root-1", "Nebula", dummyStore)
    yield assert(res == Right(None))

  test("same-team sender resolves own team's agent first (regression)"):
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.registerSession("nebflow-project", "Backend", "sid-proj-backend")
      _ <- TeamSessionRegistry.registerSession("nebflow-rust", "Backend", "sid-rust-backend")
      _ <- TeamSessionRegistry.registerSession("nebflow-project", "Manager", "sid-proj-manager")
      res <- TeamSessionRegistry.resolveSessionId("sid-proj-manager", "Backend", dummyStore)
    yield assert(res == Right(Some("sid-proj-backend")))

  test("team-internal sender same-team miss + ambiguous globally → ambiguity error"):
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.registerSession("nebflow-project", "Backend", "sid-proj-backend")
      _ <- TeamSessionRegistry.registerSession("nebflow-rust", "Backend", "sid-rust-backend")
      _ <- TeamSessionRegistry.registerSession("slideblocks", "Manager", "sid-slide-manager")
      res <- TeamSessionRegistry.resolveSessionId("sid-slide-manager", "Backend", dummyStore)
    yield res match
      case Left(msg) =>
        assert(msg.contains("ambiguous"), s"should detect ambiguity on same-team miss: $msg")
        assert(
          msg.contains("nebflow-project") && msg.contains("nebflow-rust"),
          s"should list candidate teams: $msg"
        )
        assert(msg.contains("team/agent"), s"should suggest team/agent format: $msg")
      case Right(_) => fail("expected ambiguity error, got a resolution")

  test("team-internal sender same-team miss + globally unique → resolves"):
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.registerSession("team-a", "Backend", "sid-a-backend")
      _ <- TeamSessionRegistry.registerSession("team-b", "Manager", "sid-b-manager")
      res <- TeamSessionRegistry.resolveSessionId("sid-b-manager", "Backend", dummyStore)
    yield assert(res == Right(Some("sid-a-backend")))

  test("team-internal sender no match anywhere returns None"):
    for
      _ <- TeamSessionRegistry.clear
      _ <- TeamSessionRegistry.registerSession("team-a", "Manager", "sid-a-manager")
      res <- TeamSessionRegistry.resolveSessionId("sid-a-manager", "Nobody", dummyStore)
    yield assert(res == Right(None))

  test("team/agent format resolves exactly (valid from anywhere)"):
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
end TeamSessionRegistrySpec
