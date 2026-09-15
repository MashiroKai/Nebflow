package nebflow.neblink

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.FunSuite

import java.util.regex.Pattern

/**
 * 好友消息修复批 **B+C** · 网关侧回归钉子（§3.6 对账拍 + §3.7 计数暴露 + 两条「顺手修」）。
 *
 * ## 本 spec 钉的判据（逐条对应用例）
 *  - **③ 丢帧 ⇒ ≤1 个对账周期内自动补齐**：`reconcileConversations` 探测到
 *    「服务端 keyset 水位 > 本地 `dispatchedMax`」⇒ 留痕（行内含 `conversationId=` +
 *    `diff=`）**且**复用唯一派发腿补齐（帧真的出来、锚真的前进）。用例 [[R2]]。
 *  - **无差拍零噪声**：空页 ⇒ **零日志零动作**（45s 周期上不允许常态刷行）。用例 [[R1]]。
 *  - **冷锚不入对账面**：`dispatchedMax == 0` 的会话**不得**被探测（`after=0` 的 keyset
 *    从最旧页返回，那是历史不是差态）。用例 [[R4]]。
 *  - **探针失败可见**：上游失败必须留痕（否则「对不上账」与「没对上账」同形）且**不中断
 *    整轮**。用例 [[R3]] / [[R3b]]。
 *  - **计数暴露面（§3.7）**：`pullCountersJson` 逐会话 + 总计 + **判据式自述** + 有界
 *    留痕环；且与 `pullLine` 的行内读数**同源**（同一份 `recordPull` 累加值）。用例 [[R5]]。
 *  - **顺手修 · W2 补键**：空拍对账行现在**含 `reason=`**（修前只有 `reasons=[]`，
 *    「键缺席」与「不是本格式」同形）。用例 [[R6]]。
 *  - **顺手修 · W1 去歧义**：同族 `W1` 的**两处**（派发面 / 取数面）取值**唯一**
 *    （`W1a` / `W1b`）⇒「应报数 == 出现数」可机械计数。用例 [[R7]]。
 *  - **触发源契约**：`reconcile` **不是** `event:` 族 ⇒ 不参与批 A 判据①的
 *    「事件触发 pull ⟺ processed」配对（`isEventTriggered` 单点未改）。用例 [[R8]]。
 *
 * ## 未证（如实申报，窗口纪律①：今晚全形态禁 sbt）
 *  - 本 spec **未执行**（编译/运行读数归 #515 联合轮）。
 *  - `NeblinkService.runMessageReconcile` 的**接线**（45s 拍）未用例化：构造真
 *    `NeblinkService` 需要完整设备身份/配置夹具，属「重夹具」；本 spec 只钉
 *    `FriendService` 侧的对账语义与 `NeblinkRelayTunnel.friendService` 这条
 *    **既有**装配缝的类型可达性（未装配 ⇒ `None` ⇒ no-op，见 [[R9]] 的判读口径）。
 *  - 日志**载体**（logback appender）未断言；断言的是同时进环的同一行文本
 *    （与批 A `FriendPulledDispatchSpec` 同口径）。
 */
class FriendMessageReconcileSpec extends FunSuite:

  // ── 构造夹具（与批 A spec 同形，独立复制：spec 文件之间不共享夹具）─────

  private def msg(id: Long, senderId: String = "u-peer"): MessageSummary =
    MessageSummary(id, senderId, "text", s"m$id", 1700000000L)

  /** 脚本化 stub：记录 `(conversationId, after, limit)`，按 `after` 给页。 */
  private final class ScriptedClient(
      calls: Ref[IO, List[(String, Long, Int)]],
      script: (String, Long) => List[MessageSummary]
  ) extends NeblinkClient(
    NeblinkServerConfig(url = "http://127.0.0.1:1", networkId = "n", secret = "s"),
    serverPort = 1
  ):
    override def listMessages(
        conversationId: String,
        after: Long,
        limit: Int
    ): IO[Either[String, List[MessageSummary]]] =
      calls.update(_ :+ ((conversationId, after, limit))) *> IO.pure(Right(script(conversationId, after)))

  /** 取数永远失败（探针失败面）。 */
  private final class FailingClient(calls: Ref[IO, List[(String, Long, Int)]]) extends NeblinkClient(
    NeblinkServerConfig(url = "http://127.0.0.1:1", networkId = "n", secret = "s"),
    serverPort = 1
  ):
    override def listMessages(
        conversationId: String,
        after: Long,
        limit: Int
    ): IO[Either[String, List[MessageSummary]]] =
      calls.update(_ :+ ((conversationId, after, limit))) *> IO.pure(Left("boom"))

  private def mkService(
      calls: Ref[IO, List[(String, Long, Int)]],
      frames: Ref[IO, List[Json]],
      g: FriendMessagingGuard,
      script: (String, Long) => List[MessageSummary] = (_, a) => List(msg(a + 1L))
  ): FriendService =
    new FriendService(
      IO.pure(Some(new ScriptedClient(calls, script))),
      AgentMessagingConfig(),
      guard = g,
      onFriendEvent = Some(ev => frames.update(_ :+ FriendEvent.frontendFrame(ev)))
    )

  // ── 行内字段的机械读取（判据②③的判据式建立在这两个之上）────────────

  /** `name=<value>` 取值（值不含空白；行格式保证如此）。 */
  private def field(line: String, name: String): Option[String] =
    val m = Pattern.compile(s"\\b" + Pattern.quote(name) + "=(\\S+)").matcher(line)
    if m.find() then Some(m.group(1)) else None

  private def num(line: String, name: String): Option[Long] = field(line, name).flatMap(_.toLongOption)

  private def linesWith(ls: List[String], needle: String): List[String] = ls.filter(_.contains(needle))

  // ══ R1 · 无差拍：空页 ⇒ 零日志零动作 ═════════════════════════════════
  test("R1 对账拍无差：探针空页 ⇒ 零留痕、零派发、零锚点推进（45s 周期上不得刷噪声）") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- g.advanceAnchor("c", 114L)
      svc = mkService(calls, frames, g, (_, _) => Nil)
      _ <- svc.reconcileConversations()
      ls <- g.recentPullLines
      fs <- frames.get
      anchor <- g.localMaxId("c")
      cs <- calls.get
    yield (ls, fs, anchor, cs)

    val (ls, fs, anchor, cs) = prog.unsafeRunSync()
    assertEquals(ls, Nil, s"无差拍必须零留痕（对账拍是兜底，不是噪声源）：${ls.mkString("\n")}")
    assertEquals(fs, Nil, "无差 ⇒ 不派发")
    assertEquals(anchor, 114L, "无差 ⇒ 锚点不动")
    // 探针**确实跑过**（否则「零输出」与「压根没探测」同形 —— 本仓缺陷族形态）
    assertEquals(cs.map(_._2), List(114L), s"探针必须用 dispatchedMax 作 after：${cs}")
  }

  // ══ R2 · 差态：留痕（conversationId + diff）+ 复用唯一派发腿补齐 ═══════
  test("R2 人为丢帧：服务端水位 > dispatchedMax ⇒ WARN 含 conversationId+diff，且 ≤1 拍内补齐（帧 + 锚）") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- g.advanceAnchor("c", 112L) // 本地水位 112；丢帧 ⇒ 服务端有 113/114
      svc = mkService(calls, frames, g, (_, a) => if a >= 112L then List(msg(113L), msg(114L)) else Nil)
      _ <- svc.reconcileConversations()
      ls <- g.recentPullLines
      fs <- frames.get
      anchor <- g.localMaxId("c")
      dmax <- g.dispatchedMaxOf("c")
      st <- g.pullStats
    yield (ls, fs, anchor, dmax, st)

    val (ls, fs, anchor, dmax, st) = prog.unsafeRunSync()

    val rec = linesWith(ls, "friend_pull_reconcile")
    assertEquals(rec.size, 1, s"差态必须恰好报 1 行对账 WARN：${ls.mkString("\n")}")
    val r = rec.head
    assert(r.contains("conversationId=c"), s"WARN 必须含 conversationId=：$r")
    assert(field(r, "diff").isDefined, s"WARN 必须含差值 diff=：$r")
    assertEquals(num(r, "diff"), Some(2L), s"diff = serverMax - dispatchedMax = 2：$r")
    assertEquals(field(r, "trigger"), Some(FriendPullTrigger.Reconcile), s"对账拍必须声明自己的触发源：$r")
    assertEquals(field(r, "reason"), Some("watermark_gap"), s"必须给出可判读原因：$r")

    // 「自动补齐」的可机械判读数：帧真的出来 + 锚真的前进到服务端水位。
    assertEquals(fs.map(_.hcursor.get[Long]("messageId").toOption), List(Some(113L), Some(114L)),
      s"差态必须**复用派发腿**补齐（拉取即派发）：${fs.mkString("\n")}")
    assertEquals(anchor, 114L, "补齐后 pullAnchor 前进到服务端最大 id")
    assertEquals(dmax, 114L, "dispatchedMax 与 pullAnchor 同值（批 A 不变量）")
    // 恒等式与批 A 同一份（对账行也在 assertIdentity 覆盖面内）。
    val pull = linesWith(ls, "friend_pull ")
    assert(pull.nonEmpty, s"补齐必须落一条结构化对账行：${ls.mkString("\n")}")
    pull.foreach { l =>
      val p = num(l, "pulled").getOrElse(fail(s"pulled 不可解析：$l"))
      val d = num(l, "dispatched").getOrElse(fail(s"dispatched 不可解析：$l"))
      val k = num(l, "skipped").getOrElse(fail(s"skipped 不可解析：$l"))
      assert(p == d + k, s"恒等式 pulled == dispatched + skipped 不成立：$l")
    }
    assertEquals(st.get("c").map(_.pulled), Some(2L), "计数与日志行同源（pulled=2）")
  }

  // ══ R3 · 探针失败：留痕、不上抛、下拍自然重来（禁自旋）═══════════════
  test("R3 探针失败：留痕（conversationId + gate=probe_failed）、不抛异常、不补拉") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- g.advanceAnchor("c", 7L)
      svc = new FriendService(
        IO.pure(Some(new FailingClient(calls))),
        AgentMessagingConfig(),
        guard = g,
        onFriendEvent = Some(ev => frames.update(_ :+ FriendEvent.frontendFrame(ev)))
      )
      // 关键：不得抛（否则 45s 拍的捕获层会把整轮对账吃掉）
      _ <- svc.reconcileConversations()
      ls <- g.recentPullLines
      fs <- frames.get
      cs <- calls.get
    yield (ls, fs, cs)

    val (ls, fs, cs) = prog.unsafeRunSync()
    val rec = linesWith(ls, "friend_pull_reconcile")
    assertEquals(rec.size, 1, s"探针失败必须留痕：${ls.mkString("\n")}")
    assert(rec.head.contains("gate=probe_failed"), s"必须标出失败相位：${rec.head}")
    assert(rec.head.contains("conversationId=c"), s"必须含 conversationId=：${rec.head}")
    assertEquals(cs.size, 1, "失败即停（禁自旋重试）：只一次探针调用")
    assertEquals(fs, Nil, "探针失败 ⇒ 不派发（不猜内容）")
  }

  test("R3b 单会话故障不中断整轮：两个会话，一个探针失败、另一个仍被补齐") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- g.advanceAnchor("bad", 5L)
      _ <- g.advanceAnchor("good", 5L)
      svc = mkService(
        calls, frames, g,
        (conv, a) => if conv == "bad" then throw new RuntimeException("upstream down") else List(msg(a + 1L))
      )
      _ <- svc.reconcileConversations()
      ls <- g.recentPullLines
      fs <- frames.get
    yield (ls, fs)

    val (ls, fs) = prog.unsafeRunSync()
    assert(linesWith(ls, "gate=reconcile_failed").nonEmpty,
      s"整轮捕获层必须留一行（逐会话失败可见）：${ls.mkString("\n")}")
    assertEquals(fs.map(_.hcursor.get[Long]("messageId").toOption), List(Some(6L)),
      s"另一个会话必须仍被补齐：${fs.mkString("\n")}")
  }

  // ══ R4 · 冷锚不入对账面 ══════════════════════════════════════════════
  test("R4 冷锚会话（dispatchedMax == 0）不得被探测：after=0 的 keyset 返回的是历史，不是差态") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      svc = mkService(calls, frames, g, (_, _) => List(msg(1L), msg(2L)))
      _ <- svc.reconcileConversations()
      cs <- calls.get
      ls <- g.recentPullLines
      fs <- frames.get
    yield (cs, ls, fs)

    val (cs, ls, fs) = prog.unsafeRunSync()
    assertEquals(cs, Nil, s"无派发水位的会话不得进对账面（否则会把一页历史当丢帧补拉）：$cs")
    assertEquals(ls, Nil, "零会话 ⇒ 零留痕")
    assertEquals(fs, Nil, "零会话 ⇒ 零派发")
  }

  // ══ R5 · §3.7 计数暴露面 ═════════════════════════════════════════════
  test("R5 /api/neblink/status 计数暴露：逐会话 + 总计 + 判据式自述 + 有界留痕环，且与日志行同源") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- g.advanceAnchor("c1", 10L)
      _ <- g.advanceAnchor("c2", 20L)
      svc = mkService(
        calls, frames, g,
        (conv, a) => if conv == "c1" then List(msg(a + 1L), msg(a + 2L)) else Nil
      )
      _ <- svc.reconcileConversations()
      js <- svc.pullCountersJson
      ls <- g.recentPullLines
    yield (js, ls)

    val (js, ls) = prog.unsafeRunSync()
    val c1 = js.hcursor.downField("conversations").downField("c1")
    assertEquals(c1.get[Long]("pulled").toOption, Some(2L), s"逐会话 pulled：$js")
    assertEquals(c1.get[Long]("dispatched").toOption, Some(2L), s"逐会话 dispatched：$js")
    assertEquals(c1.get[Long]("skipped").toOption, Some(0L), s"逐会话 skipped：$js")
    assertEquals(js.hcursor.downField("totals").get[Long]("pulled").toOption, Some(2L), s"总计：$js")
    assertEquals(js.hcursor.get[String]("identity").toOption,
      Some("pulled == dispatched + skipped"), s"判据式必须自述（消费方不必自己推）：$js")
    // 留痕环：暴露的 `recentLines` 与 guard 的环**同一份**（不另建第二份读数）。
    val recent = js.hcursor.downField("recentLines").as[List[String]].getOrElse(Nil)
    assertEquals(recent, ls, s"暴露面无第二份读数（必须等于 guard 的环）：$js")
    assert(recent.exists(_.startsWith("friend_pull ")), s"环内应含结构化对账行：$recent")
    assertEquals(js.hcursor.get[Int]("recentLinesMax").toOption, Some(64), "有界：上限自述")
  }

  // ══ R6 · 顺手修 ①：W2 对账行补 `reason=` 键 ═══════════════════════════
  test("R6 W2 空拍对账行必须含 reason= 键（修前只有 reasons=[] ⇒ 「键缺席」与「非本格式」同形）") {
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- g.advanceAnchor("c", 5L)
      svc = mkService(calls, frames, g, (_, _) => Nil)
      _ <- svc.pullConversation("c", FriendPullTrigger.RefreshAll)
      ls <- g.recentPullLines
    yield ls

    val ls = prog.unsafeRunSync()
    val line = linesWith(ls, "friend_pull ").head
    assert(field(line, "reason").isDefined, s"空拍对账行必须含 reason= 键：$line")
    assertEquals(field(line, "reason"), Some("<none>"), s"缺省必须显式写 <none>（不是省键）：$line")
    assert(line.contains("reasons=[]"), s"空列表形态不变（加性扩键）：$line")
    // 既有键序零变动：新增键在**行尾**（`dispatchedMax=` 之后）。
    assert(line.indexOf("reason=<none>") > line.indexOf("dispatchedMax="), s"新增键必须在行尾：$line")
  }

  // ══ R7 · 顺手修 ②：W1 去歧义（一名两处 ⇒ 子码唯一）═══════════════════
  test("R7 W1 一名两处去歧义：派发面 W1a / 取数面 W1b，各自恰好 1 行（应报数 == 出现数）") {
    val prog = for
      callsA <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      gA = new FriendMessagingGuard()
      _ <- gA.advanceAnchor("c", 1L)
      svcA = new FriendService(
        IO.pure(Some(new ScriptedClient(callsA, (_, a) => List(msg(a + 1L))))),
        AgentMessagingConfig(),
        guard = gA,
        onFriendEvent = Some(_ => IO.raiseError(new RuntimeException("ws hub down")))
      )
      _ <- svcA.pullConversation("c", FriendPullTrigger.RefreshAll)
      lsA <- gA.recentPullLines
      callsB <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      gB = new FriendMessagingGuard()
      _ <- gB.advanceAnchor("c", 1L)
      svcB = new FriendService(IO.pure(Some(new FailingClient(callsB))), AgentMessagingConfig(), guard = gB)
      _ <- svcB.pullConversation("c", FriendPullTrigger.RefreshAll)
      lsB <- gB.recentPullLines
    yield (lsA, lsB)

    val (lsA, lsB) = prog.unsafeRunSync()
    assertEquals(linesWith(lsA, "branch=W1a").size, 1, s"派发面 W1a 恰好 1 行：${lsA.mkString("\n")}")
    assertEquals(linesWith(lsA, "branch=W1b").size, 0, "派发面不得出现取数面子码")
    assertEquals(linesWith(lsB, "branch=W1b").size, 1, s"取数面 W1b 恰好 1 行：${lsB.mkString("\n")}")
    assertEquals(linesWith(lsB, "branch=W1a").size, 0, "取数面不得出现派发面子码")
    // 精确值判据（`field` 走 `\bbranch=(\S+)`）⇒ 不存在「一个 needle 命中两行」的形态。
    assertEquals(field(linesWith(lsA, "branch=W1a").head, "branch"), Some("W1a"), "子码取值唯一")
    assertEquals(field(linesWith(lsB, "branch=W1b").head, "branch"), Some("W1b"), "子码取值唯一")
    // 族前缀保留 ⇒ 与取证稿 §3.3 的 W 编号对照关系不丢；三字段同行判据不受影响。
    List(linesWith(lsA, "branch=W1a").head, linesWith(lsB, "branch=W1b").head).foreach { l =>
      assert(l.contains("conversationId="), s"三字段（conversationId）不可少：$l")
      assert(l.contains("messageId=") || l.contains("eventId="), s"三字段（messageId|eventId）不可少：$l")
      assert(l.contains("reason="), s"三字段（reason）不可少：$l")
    }
  }

  // ══ R8 · 触发源契约：reconcile 不是事件族 ════════════════════════════
  test("R8 触发源契约：reconcile 非事件族（不参与判据①配对），且对账行 eventId=<none>") {
    assert(!FriendPullTrigger.isEventTriggered(FriendPullTrigger.Reconcile),
      "对账拍**不得**被判成事件触发（否则批 A 判据①的配对不变式会在补拉侧出现无配对行）")
    assertEquals(FriendPullTrigger.isEventTriggered(FriendPullTrigger.EventMessageNew), true,
      "事件族判据单点未改")
    val prog = for
      calls <- Ref.of[IO, List[(String, Long, Int)]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
      g = new FriendMessagingGuard()
      _ <- g.advanceAnchor("c", 1L)
      svc = mkService(calls, frames, g, (_, a) => List(msg(a + 1L)))
      _ <- svc.reconcileConversations()
      ls <- g.recentPullLines
    yield ls

    val ls = prog.unsafeRunSync()
    val pull = linesWith(ls, "friend_pull ").head
    assertEquals(field(pull, "trigger"), Some(FriendPullTrigger.Reconcile), s"trigger 必须显式：$pull")
    assertEquals(field(pull, "eventId"), Some("<none>"), s"非事件触发 ⇒ eventId 显式 <none>：$pull")
  }

  // ══ R9 · 对账扫描面（冷锚排除 + 一次读取给出 id+水位）════════════════
  test("R9 扫描面 dispatchedAnchors：只含已有派发水位的会话；冷锚（从未派发 / 只被读过）一律排除") {
    val prog = for
      g <- IO(new FriendMessagingGuard())
      _ <- g.advanceAnchor("hot", 5L)   // 有派发水位 ⇒ 入面
      _ <- g.bumpUnread("cold", 1)      // materialize 了条目但 dispatchedMax == 0 ⇒ 不得入面
      _ <- g.setRead("readonly", 9L)    // 只动**已读**水位 ⇒ 同样不得入面
      as <- g.dispatchedAnchors
    yield as

    val as = prog.unsafeRunSync()
    assertEquals(as.map(_._1).sorted, List("hot"),
      s"只有「已有派发水位」的会话可进对账面；冷锚的 after=0 会返回历史而非差态：$as")
    assertEquals(as.find(_._1 == "hot").map(_._2), Some(5L), s"一次读取同时给出 id 与水位：$as")
  }

end FriendMessageReconcileSpec
