package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.FunSuite

/**
 * #309 ·「客户端尚无该会话 cursor 时的首条推送」未读 +1 静默丢失 —— 回归钉子。
 *
 * ## 缺陷（既有行为；波3 ①opt-A2 接缝修复让它**可达**）
 * `FriendMessagingGuard.bumpUnread` 是 cursor 变更面**唯一**不 materialize 条目的
 * 入口——cursor 缺席时 `case None => s`（no-op）。而缺席是**常态**：cursor map 在
 * 进程 boot 时为空（`Ref.unsafe(St(…, Map.empty))`），要等
 * `refreshAll → refreshConversations → mergeUnread` 才逐会话填充；relay 隧道推送
 * 先于/独立于该刷新到达（重连窗口、全新会话的首条推送）时，这条 +1 被静默吞掉，
 * 未读角标永不亮。修前 ①opt-A2 的 payload 下钻未落地 ⇒ conversationId 恒 None，
 * `bumpUnread` 根本不被调用；接缝修好后才真正跑到这个 no-op 分支。
 *
 * ## 三层钉子
 *  - **L1** `bumpUnread` 单元：缺席 ⇒ 未读落 delta（不再缺席于 snapshot）+ 回落信号 true；
 *  - **L2** 反向断言：穷举「产生 cursor 的既有入口」× 增量，**没有任何**组合丢 +1；
 *  - **L3** 真通路：未 seed cursor 的真 `FriendService.onFriendEvent` ⇒ 未读 == 1
 *    （修前 `unreadCounts` 恒为空 map），且同路径的 keyset 补拉不被吞。
 *
 * ## 验红自证（供复核节点实证）
 * 反转修法 = 把 `bumpUnread` 的 `state.modify` 恢复为修前形状（只改这一处，调用点
 * 不必动——返回 `false` 即不打印留痕）：
 * {{{
 *   def bumpUnread(conversationId: String, delta: Int): IO[Boolean] =
 *     state.modify { s =>
 *       s.cursors.get(conversationId) match
 *         case Some(c) => (s.copy(cursors = s.cursors.updated(
 *                            conversationId, c.copy(unreadCount = c.unreadCount + delta))), false)
 *         case None    => (s, false)   // ← 修前 no-op：这条 +1 被吞
 *     }
 * }}}
 * 预期（已实测，见交付报告「验红实证」）：L1（`Some(1)` vs `None`）、L2（boot
 * 空态那三行 `post != pre + d`）、L3（`Some(1)` vs `None`）**变红**；L4 因刻意
 * 只走既有入口（mergeUnread）而**保持绿** —— 它是本 spec 的对偶，证明变红是钉子
 * 有效而非"什么都会红"。命令：
 * `sbt "testOnly nebflow.neblink.FriendUnreadCursorRebuildSpec"`。
 *
 * ## 明确未证（见交付报告「未证项」）
 * 留痕日志**行本身**未被断言（其载体 = `bumpUnread` 的返回值，此处已断言）；
 * 日志行不在承载它的 `NeblinkLogger` 里做 appender 捕获。
 * 本 spec 不触网、不起服务、不碰宿主 8080。
 */
class FriendUnreadCursorRebuildSpec extends FunSuite:

  private def conv(id: String, unread: Int): ConversationSummary =
    ConversationSummary(id, FriendSummary("u-peer", "peer", "Peer"), None, unread)

  private def envelope(eventId: String, event: Json): Json =
    Json.obj(
      "type" -> Json.fromString("friend_event"),
      "eventId" -> Json.fromString(eventId),
      "event" -> event
    )

  /** 真服务端信封形状：`conversationId` 在 payload 内（fmw3 ①opt-A2 接缝）。 */
  private def serverEvent(convId: String, messageId: Long): Json =
    Json.obj(
      "type" -> Json.fromString("message_new"),
      "payload" -> Json.obj(
        "messageId" -> Json.fromLong(messageId),
        "conversationId" -> Json.fromString(convId),
        "sender" -> Json.obj("userId" -> Json.fromString("u-peer"), "username" -> Json.fromString("peer")),
        "kind" -> Json.fromString("text"),
        "body" -> Json.fromString("hi"),
        "createdAt" -> Json.fromLong(1700000000L)
      )
    )

  /** 记录型 stub：捕获 (conversationId, after) 并回一条消息。 */
  private final class RecordingClient(pulls: Ref[IO, List[(String, Long)]]) extends NeblinkClient(
    NeblinkServerConfig(url = "http://127.0.0.1:1", networkId = "n", secret = "s"),
    serverPort = 1
  ):
    override def listMessages(
      conversationId: String,
      after: Long,
      limit: Int
    ): IO[Either[String, List[MessageSummary]]] =
      pulls.update(_ :+ ((conversationId, after))).as(
        Right(List(MessageSummary(after + 1L, "u-peer", "text", "hi", 1700000000L)))
      )

  /** 真 `FriendService` + 真 guard，**不预置任何 cursor**（boot 期形态）。 */
  private def mkService(pulls: Ref[IO, List[(String, Long)]], guard: FriendMessagingGuard): FriendService =
    new FriendService(IO.pure(Some(new RecordingClient(pulls))), AgentMessagingConfig(), guard = guard)

  test("#309-L1 cursor 缺席：首条推送的 +1 必须落账（不再 no-op），并给出回落信号") {
    val g = new FriendMessagingGuard()
    val prog = for
      pre <- g.unreadSnapshot
      materialized <- g.bumpUnread("c-first", 1)
      post <- g.unreadSnapshot
      anchor <- g.localMaxId("c-first")
    yield (pre, materialized, post, anchor)
    val (pre, materialized, post, anchor) = prog.unsafeRunSync()

    assertEquals(pre.get("c-first"), None, "前置：该会话此前无任何本地 cursor（boot 期空 map 形态）")
    assertEquals(
      post.get("c-first"),
      Some(1),
      "未读必须落账为 1 —— 修前 `case None => s` ⇒ unreadSnapshot 里恒无该键（这就是被静默丢弃的 +1）"
    )
    assert(materialized, "留痕信号：缺席回落必须上报（修前返回 IO[Unit]，全程零可观测痕迹）")
    assertEquals(anchor, 0L, "回落条目的锚点 = 0（与 mergeUnread/advanceAnchor/setRead 的缺省构造逐字一致）")
  }

  test("#309-L2 反向断言：穷举 cursor 状态 × 增量，不存在任何「未读 +1 被静默吞掉」的路径") {
    // 「产生 cursor 的既有入口」逐条列举（含 boot 期空态 = 压根没有 cursor）
    val seeds: List[(String, FriendMessagingGuard => IO[Unit])] = List(
      "boot 期空 cursor map（无条目）" -> (_ => IO.unit),
      "mergeUnread(0)：刷新会话，服务端权威 0" -> (g => g.mergeUnread(conv("c", 0)).void),
      "mergeUnread(5)：刷新会话，服务端权威 5" -> (g => g.mergeUnread(conv("c", 5)).void),
      "advanceAnchor(7)：重连补拉推进锚点" -> (g => g.advanceAnchor("c", 7L)),
      "setRead(9)：已读清零" -> (g => g.setRead("c", 9L))
    )
    val deltas = List(1, 2, 5)
    val cases = for
      s <- seeds
      d <- deltas
    yield (s, d)

    val prog = cases.traverse { case ((label, seed), d) =>
      val g = new FriendMessagingGuard()
      for
        _ <- seed(g)
        pre <- g.unreadSnapshot
        _ <- g.bumpUnread("c", d)
        post <- g.unreadSnapshot
      yield (label, d, pre.getOrElse("c", 0), post.getOrElse("c", -1))
    }
    val rows = prog.unsafeRunSync()

    assertEquals(rows.size, seeds.size * deltas.size, "组合面必须被完整穷举（无跳过）")
    rows.foreach { case (label, d, pre, post) =>
      assertEquals(
        post,
        pre + d,
        s"[$label] delta=$d：+$d 必须逐字落账（pre=$pre post=$post）——出现下溢即「静默吞掉 +1」路径复活"
      )
    }
  }

  test("#309-L3 真通路：boot 期推送先于 refreshConversations 到达 ⇒ 未读落账 1 且补拉发生") {
    val prog = for
      pulls <- Ref.of[IO, List[(String, Long)]](Nil)
      g = new FriendMessagingGuard()
      svc = mkService(pulls, g)
      pre <- svc.unreadCounts
      _ <- svc.onFriendEvent(envelope("e-boot", serverEvent("c-boot", 7L)))
      post <- svc.unreadCounts
      pulled <- pulls.get
    yield (pre, post, pulled)
    val (pre, post, pulled) = prog.unsafeRunSync()

    assertEquals(pre, Map.empty[String, Int], "前置：进程 boot 期 cursor map 为空（本批前此处吞掉整条 +1）")
    assertEquals(
      post.get("c-boot"),
      Some(1),
      "未 seed cursor 的首条推送必须让未读落账 —— 修前 unreadCounts 恒为空 map（静默丢失）"
    )
    assertEquals(pulled.map(_._1), List("c-boot"), "同一路径的 keyset 补拉不得被回落分支吞掉")
    assertEquals(pulled.map(_._2), List(0L), "回落条目锚点 0 ⇒ 首次补拉覆盖全量（after=0）")
  }

  test("#309-L4 回归（验红下的绿对偶）：mergeUnread / setRead / advanceAnchor / localMaxId / unreadSnapshot 语义未破") {
    // 本用例刻意**不依赖缺席回落**：cursor 一律由既有入口（mergeUnread）建 —— 故
    // 反转 #309 修法后它必须**保持绿**。它是本 spec 的对偶：证明 L1–L3 的变红是
    // 钉子有效，而非"什么都会红"。
    val g = new FriendMessagingGuard()
    val prog = for
      _ <- g.mergeUnread(conv("c", 3)) // 既有入口建 cursor（服务端权威 3）
      seeded <- g.unreadSnapshot
      _ <- g.bumpUnread("c", 1) // 已有条目上的增量 ⇒ 4（#309 前后行为一致）
      afterBumpExisting <- g.unreadSnapshot
      _ <- g.mergeUnread(conv("c", 0)) // 服务端权威 0 ⇒ 全量覆盖（不叠加、不重复计数）
      afterMerge <- g.unreadSnapshot
      _ <- g.advanceAnchor("c", 7L)
      afterAnchor <- g.unreadSnapshot // advanceAnchor 不动未读
      maxId <- g.localMaxId("c")
      _ <- g.setRead("c", 9L) // 清零 + 推进锚点
      afterRead <- g.unreadSnapshot
      maxIdAfterRead <- g.localMaxId("c")
      _ <- g.setRead("c", 4L) // 锚点取 max，不得回退
      maxIdAfterBack <- g.localMaxId("c")
      _ <- g.bumpUnread("c", 1) // 已读之后的新消息 ⇒ 1
      afterPostRead <- g.unreadSnapshot
      missingMax <- g.localMaxId("c-none")
    yield (
      seeded, afterBumpExisting, afterMerge, afterAnchor, maxId,
      afterRead, maxIdAfterRead, maxIdAfterBack, afterPostRead, missingMax
    )
    val (
      seeded, afterBumpExisting, afterMerge, afterAnchor, maxId,
      afterRead, maxIdAfterRead, maxIdAfterBack, afterPostRead, missingMax
    ) = prog.unsafeRunSync()

    assertEquals(seeded.get("c"), Some(3), "mergeUnread 建条目并按服务端权威置数")
    assertEquals(afterBumpExisting.get("c"), Some(4), "已有条目上的增量照旧（#309 未触碰这条路径）")
    assertEquals(afterMerge.get("c"), Some(0), "mergeUnread 仍是服务端权威全量覆盖（不叠加 ⇒ 不重复计数）")
    assertEquals(afterAnchor.get("c"), Some(0), "advanceAnchor 不得触碰未读（补拉不等于已读）")
    assertEquals(maxId, 7L, "advanceAnchor 推进锚点")
    assertEquals(afterRead.get("c"), Some(0), "setRead 清零未读")
    assertEquals(maxIdAfterRead, 9L, "setRead 推进已读锚点")
    assertEquals(maxIdAfterBack, 9L, "已读锚点取 max，不得回退")
    assertEquals(afterPostRead.get("c"), Some(1), "已读后到达的新消息重新计数")
    assertEquals(afterPostRead.keySet, Set("c"), "unreadSnapshot 面 = 恰好有 cursor 的会话（无幽灵条目）")
    assertEquals(missingMax, 0L, "localMaxId 缺省仍是 0（缺席回落不改这一语义）")
  }

end FriendUnreadCursorRebuildSpec
