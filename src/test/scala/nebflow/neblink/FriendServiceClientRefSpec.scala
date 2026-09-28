package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite

/** F1 of the 2026-09-10 friend-search incident: client reference unification.
  *
  * Failure being nailed (report §五-2, structural root cause): FriendService
  * captured a constructor-time NeblinkClient while enrollment hot-swap only
  * replaced the discovery's clientRef — after a UI re-login / account switch
  * the two references split (discovery: live session; FriendService: kicked
  * session → permanent 403 until restart).
  *
  * The fix makes FriendService resolve the client per call from the
  * authoritative source (`discovery.currentClient`). These specs pin the
  * contract:
  *  - swapping the Ref is enough for FriendService to follow (mutation
  *    check: reverting to a constructor-time capture turns the swap spec
  *    red — the second phase would still hit the dead client);
  *  - `listFriends` (REST direct path, F4) surfaces upstream Left, while
  *    `refreshFriends` (background chain) still folds it to an empty list;
  *  - client-less states surface "Not logged in".
  */
class FriendServiceClientRefSpec extends FunSuite:

  private val deadReply: Either[String, FriendListResponse] =
    Left("HTTP 403: {\"error\":\"Missing or invalid token\"}")
  private val friendsOk = FriendListResponse(Nil)

  private def stubClient(tag: String, reply: Either[String, FriendListResponse]): NeblinkClient =
    new NeblinkClient(
      NeblinkServerConfig(url = "http://127.0.0.1:1", networkId = "n", secret = "s"),
      serverPort = 1
    ):
      override def listFriends: IO[Either[String, FriendListResponse]] =
        IO.println(s"[$tag] listFriends").as(reply)

  test("F1 nail: swapping the authoritative client is enough — FriendService follows the hot-swap") {
    val dead = stubClient("dead-0", deadReply)
    val live = stubClient("live-1", Right(friendsOk))
    val ref  = Ref.unsafe[IO, Option[NeblinkClient]](Some(dead))
    val svc  = new FriendService(ref.get, AgentMessagingConfig())

    // Phase 0: the constructor-time client is dead — 403s (the incident state).
    val before = svc.listFriends.unsafeRunSync()
    assertEquals(before, deadReply)

    // Enrollment hot-swap equivalent: ONLY the authoritative Ref is replaced.
    // (Mutation check: a constructor-time capture would keep serving `dead`
    // here and this assertion goes red.)
    ref.set(Some(live)).unsafeRunSync()

    val after = svc.listFriends.unsafeRunSync()
    assertEquals(after, Right(friendsOk), "FriendService must serve via the hot-swapped client")
  }

  test("F4 nail: listFriends (REST direct) surfaces Left; refreshFriends folds to empty") {
    val dead = stubClient("dead-0", deadReply)
    val ref  = Ref.unsafe[IO, Option[NeblinkClient]](Some(dead))
    val svc  = new FriendService(ref.get, AgentMessagingConfig())

    val direct     = svc.listFriends.unsafeRunSync()
    val background = svc.refreshFriends().unsafeRunSync()

    assertEquals(direct, deadReply, "REST path must NOT fold upstream failures — the frontend distinguishes them")
    assertEquals(background, FriendListResponse(Nil), "background refresh keeps its folded-empty semantics")
  }

  test("client-less state (logged out) surfaces Not logged in") {
    val ref = Ref.unsafe[IO, Option[NeblinkClient]](None)
    val svc = new FriendService(ref.get, AgentMessagingConfig())

    val out = svc.listFriends.unsafeRunSync()
    assertEquals(out, Left("Not logged in"))
  }

end FriendServiceClientRefSpec
