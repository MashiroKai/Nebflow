package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * 好友域装配缝的结构哨兵（2026-09-11 boot 快照修复，A 案）。
 *
 * `NeblinkWiring.friendService` 是生产装配的唯一入口（GatewayMain 也走它），
 * 它构建的服务**不依赖**任何 boot 期 client 快照：
 *  - `clientProvider = IO.pure(None)`（全新 home / 未登录）时仍返回一个可用的
 *    `FriendService`，只是每次调用得 `Left("Not logged in")` —— 存在性 ≠ 登录态；
 *  - provider 被 hot-swap（enrollment 换 client）后，同一实例跟随新 client
 *    （provider 是 per-call 读，与 F1 的 `NeblinkDiscovery.currentClient` 同构）。
 *
 * 这是**结构哨兵**（不是红线）：把装配重新塞回 `neblinkClient.map { … }`
 * （存在性依赖 boot 快照）时它不会红——行为级红线在
 * `FriendBootSnapshotRedlineSpec`（该 spec 的装配行走同一个缝）。
 */
class FriendWiringSpec extends FunSuite:

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

  test("no boot client: the wiring still yields a usable FriendService (Not logged in, not 'absent')") {
    val svc = NeblinkWiring.friendService(IO.pure(None), AgentMessagingConfig())
    assertEquals(
      svc.listFriends.unsafeRunSync(),
      Left("Not logged in"),
      "存在性不再由 boot 快照决定；未登录由服务层的 Left 表达"
    )
    // 后台折叠面同样可用（不抛）。
    assertEquals(svc.refreshFriends().unsafeRunSync(), FriendListResponse(Nil))
  }

  test("provider swap: the wired service follows the hot-swapped client") {
    val dead = stubClient("dead-0", deadReply)
    val live = stubClient("live-1", Right(friendsOk))
    val ref  = Ref.unsafe[IO, Option[NeblinkClient]](Some(dead))
    val svc  = NeblinkWiring.friendService(ref.get, AgentMessagingConfig())

    assertEquals(svc.listFriends.unsafeRunSync(), deadReply, "初始 provider")

    // enrollment hot-swap 等价物：只换权威 Ref
    ref.set(Some(live)).unsafeRunSync()
    assertEquals(svc.listFriends.unsafeRunSync(), Right(friendsOk), "hot-swap 后必须跟随新 client")
  }

  test("boot client present does not change anything (slot is unconditional)") {
    val client = stubClient("live-0", Right(friendsOk))
    val svc    = NeblinkWiring.friendService(IO.pure(Some(client)), AgentMessagingConfig())
    assertEquals(NeblinkWiring.sharedResourcesSlot(Some(client), svc), Some(svc))
    assertEquals(NeblinkWiring.sharedResourcesSlot(None, svc), Some(svc), "全新 home 也必须是 Some")
  }

end FriendWiringSpec
