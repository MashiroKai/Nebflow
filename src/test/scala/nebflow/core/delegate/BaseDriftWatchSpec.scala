package nebflow.core.delegate

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite

/**
 * `[base-moved]` **通知泛滥降噪**（作者报障 2026-11-05 10:30）。
 *
 * 作者三条语义（逐字）：「如果是其他 delegate 就不应该，因为有隔离，不需要通知。
 * 如果是子节点的，也不需要通知。它在派发时，应该要考虑各节点并行时不冲突。」
 *
 * 改前（每例「改前」括注 = 红形态）：任何 tip 漂移 ⇒ 注入一条，文案只说「base
 * 动了，收尾前复查」——O(在飞任务数 × 合并数) 的平方级噪音（实测今日 189 条 / 34
 * 个任务 / 21 次移动）。改后：默认静默；只在**可行动**（三段合并真冲突 / base 历史
 * 被改写）时注入，且同一任务 ≥[[BaseDriftWatch.WindowMs]] 最多一条、内容聚合。
 *
 * 五条验收（作者令）逐条对应下面 ①–⑤ 五例。
 */
class BaseDriftWatchSpec extends CatsEffectSuite:

  private def rec(id: String, branch: String = "delegate/x", base: String = "main"): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = "/tmp/ws",
      project = None,
      taskId = None,
      parentSessionId = "root-x",
      title = "t",
      createdAt = 1L,
      branch = Some(branch),
      baseBranch = Some(base),
      projectWorkspace = Some("/tmp/ws"),
      worktree = Some("/tmp/wt")
    )

  private final case class FakePort(
    delegatesIo: IO[List[DelegateRegistry.Record]],
    live: Set[String],
    tip: Option[String],
    conflicts: List[String],
    rewritten: Boolean,
    state: Ref[IO, Map[String, BaseDriftWatch.Entry]],
    sent: Ref[IO, List[(String, String)]],
    clock: Ref[IO, Long]
  ) extends BaseDriftWatch.Port:
    def delegates = delegatesIo
    def liveSessionIds = IO.pure(live)
    def headTip(workspace: String, branch: String): Option[String] = tip
    def conflictFiles(workspace: String, branch: String, baseTip: String): List[String] = conflicts
    def baseRewritten(workspace: String, oldTip: String, newTip: String): Boolean = rewritten
    def notify(sessionId: String, text: String): IO[Boolean] = sent.update(_ :+ (sessionId, text)).as(true)
    override def nowMs = clock.get
    override def loadState = state.get
    override def saveState(s: Map[String, BaseDriftWatch.Entry]) = state.set(s)

  /** 单个在飞 delegate 的夹具（基线 a1，时钟 1000ms，从未通知过）。 */
  private def rig(delegateId: String = "delegate-a") =
    for
      state <- Ref.of[IO, Map[String, BaseDriftWatch.Entry]](
                 Map(delegateId -> BaseDriftWatch.Entry(baselineTip = "a1", lastSeenTip = "a1"))
               )
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      clock <- Ref.of[IO, Long](1000L)
    yield (state, sent, clock)

  private def port(
    delegate: DelegateRegistry.Record,
    tip: Option[String],
    conflicts: List[String],
    rewritten: Boolean,
    state: Ref[IO, Map[String, BaseDriftWatch.Entry]],
    sent: Ref[IO, List[(String, String)]],
    clock: Ref[IO, Long],
    live: Set[String] = Set("delegate-a")
  ): FakePort =
    FakePort(IO.pure(List(delegate)), live, tip, conflicts, rewritten, state, sent, clock)

  // ── 验收 ① 5 次其他 delegate 推进 base（不含我改过的文件）⇒ 通知数 = 0 ────────
  test("① 默认静默：5 次推进全不与我分支冲突 ⇒ [base-moved] 通知数 = 0（改前 = 5）") {
    for
      state <- Ref.of[IO, Map[String, BaseDriftWatch.Entry]](
                 Map("delegate-a" -> BaseDriftWatch.Entry(baselineTip = "a1", lastSeenTip = "a1"))
               )
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      clock <- Ref.of[IO, Long](1000L)
      d = rec("delegate-a")
      _ <- (1 to 5).toList.traverse_ { i =>
             BaseDriftWatch.scan(port(d, Some(s"tip$i"), Nil, false, state, sent, clock))
           }
      s <- sent.get
      st <- state.get
    yield
      assertEquals(s, Nil, "非可行动的 base 前进必须零通知（改前每拍一条 = 5 条）")
      assertEquals(st("delegate-a").moves, 5, "移动次数照数（面板状态面「base 已前进 5 次」）")
      assertEquals(st("delegate-a").pendingConflicts, 0)
  }

  // ── 验收 ② 3 次推进，其中 1 次与我改过的文件冲突 ⇒ 通知数 = 1（聚合），内容列明文件 ──
  test("② 可行动才通知 + 聚合：3 次推进里 1 次真冲突 ⇒ 通知数 = 1，文案含 N=3 / M=1 / 冲突文件") {
    for
      state <- Ref.of[IO, Map[String, BaseDriftWatch.Entry]](
                 Map("delegate-a" -> BaseDriftWatch.Entry(baselineTip = "a1", lastSeenTip = "a1"))
               )
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      clock <- Ref.of[IO, Long](1000L)
      d = rec("delegate-a")
      _ <- BaseDriftWatch.scan(port(d, Some("b2"), Nil, false, state, sent, clock))
      _ <- BaseDriftWatch.scan(port(d, Some("c3"), Nil, false, state, sent, clock))
      _ <- BaseDriftWatch.scan(
             port(d, Some("d4"), List("src/mine/M.scala", "src/mine/N.scala"), false, state, sent, clock)
           )
      s <- sent.get
      st <- state.get
    yield
      assertEquals(s.size, 1, s"3 次推进只许一条聚合通知（改前 = 3 条），实际：${s.map(_._2)}")
      val text = s.head._2
      assert(text.startsWith("[base-moved] main advanced 3 time(s)"), s"聚合计数 N=3 必须在文案里：$text")
      assert(text.contains("1 of those moves now conflict with your branch"), s"M=1 必须在文案里：$text")
      assert(text.contains("src/mine/M.scala") && text.contains("src/mine/N.scala"), s"冲突文件必须列明：$text")
      assertEquals(st("delegate-a").pendingMoves, 0, "通知后挂账清零")
  }

  // ── 验收 ③ 子节点推进 base ⇒ 通知数 = 0 ─────────────────────────────────────
  test("③ 子节点不参与这条路：节点记录（无分支/无 worktree，跑在 dispatcher 的 seat 内）⇒ 通知数 = 0") {
    val nodeSeat = rec("node-p3").copy(branch = None, baseBranch = None, worktree = None)
    for
      state <- Ref.of[IO, Map[String, BaseDriftWatch.Entry]](
                 Map("node-p3" -> BaseDriftWatch.Entry(baselineTip = "a1", lastSeenTip = "a1"))
               )
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      clock <- Ref.of[IO, Long](1000L)
      _ <- (1 to 3).toList.traverse_ { i =>
             BaseDriftWatch.scan(
               port(nodeSeat, Some(s"tip$i"), List("src/mine/M.scala"), false, state, sent, clock, live = Set("node-p3"))
             )
           }
      s <- sent.get
    yield assertEquals(s, Nil, "节点不建分支/worktree、不写 DelegateRegistry ⇒ 结构上零命中")
  }

  // ── 验收 ④ 面板上仍能看到「base 已前进 N 次」 ────────────────────────────────
  test("④ 面板状态面：base 前进 5 次（零通知）仍投影出 moves=5；没动过的行不出场") {
    val st = Map(
      "delegate-a" -> BaseDriftWatch.Entry(baselineTip = "a1", lastSeenTip = "f6", moves = 5),
      "delegate-b" -> BaseDriftWatch.Entry(baselineTip = "a1", lastSeenTip = "a1", moves = 0)
    )
    val j = BaseDriftWatch.statusProjection(st)
    val a = j.hcursor.downField("delegate-a")
    val b = j.hcursor.downField("delegate-b")
    assertEquals(a.get[Int]("moves").toOption, Some(5), "「base 已前进 N 次」的唯一供给")
    assert(!b.succeeded, s"moves = 0 的行不占面板（缺键纪律）：$j")
  }

  // ── 验收 ⑤ 改前/改后条数对比（按实测口径的机制化重放）─────────────────────
  test("⑤ 量级对比重放：34 个在飞任务 × 21 次合并、零冲突 ⇒ 改后 0 条（实测改前 189 条）") {
    val fleet = (1 to 34).toList.map(i => rec(s"delegate-f$i"))
    val baseline = fleet.map(d => d.id -> BaseDriftWatch.Entry(baselineTip = "a1", lastSeenTip = "a1")).toMap
    for
      state <- Ref.of[IO, Map[String, BaseDriftWatch.Entry]](baseline)
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      clock <- Ref.of[IO, Long](1000L)
      _ <- (1 to 21).toList.traverse_ { i =>
             BaseDriftWatch.scan(
               FakePort(IO.pure(fleet), fleet.map(_.id).toSet, Some(s"tip$i"), Nil, false, state, sent, clock)
             )
           }
      s <- sent.get
      st <- state.get
    yield
      assertEquals(s, Nil, "整体量级：改前 34 × 21 ≈ 189 条，改后 0 条")
      assert(st.values.forall(_.moves == 21), "每个任务的移动计数器都推进到 21（面板可见，消息静默）")
  }

  // ── 聚合窗：同一任务窗内最多一条 ──────────────────────────────────────────
  test("⑥ 聚合窗：窗内连续两次可行动 ⇒ 仍只有 1 条；窗过后再次可行动 ⇒ 第 2 条") {
    for
      state <- Ref.of[IO, Map[String, BaseDriftWatch.Entry]](
                 Map("delegate-a" -> BaseDriftWatch.Entry(baselineTip = "a1", lastSeenTip = "a1"))
               )
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      clock <- Ref.of[IO, Long](1000L)
      d = rec("delegate-a")
      _ <- BaseDriftWatch.scan(port(d, Some("b2"), List("src/mine/M.scala"), false, state, sent, clock))
      _ <- BaseDriftWatch.scan(port(d, Some("c3"), List("src/mine/M.scala"), false, state, sent, clock))
      s1 <- sent.get
      _ <- clock.update(_ + BaseDriftWatch.WindowMs)
      _ <- BaseDriftWatch.scan(port(d, Some("d4"), List("src/mine/M.scala"), false, state, sent, clock))
      s2 <- sent.get
    yield
      assertEquals(s1.size, 1, "窗内两条可行动移动 ⇒ 只一条")
      assertEquals(s2.size, 2, "窗过后再可行动 ⇒ 第二条")
      assert(s2(1)._2.contains("advanced 2 time(s)"), s"窗后那条聚合的是「上次通知以来的全部移动」（move2+move3）：${s2(1)._2}")
  }

  test("⑦ base 历史被改写（旧 tip 不再是新 tip 祖先）⇒ 即使零冲突也通知") {
    for
      state <- Ref.of[IO, Map[String, BaseDriftWatch.Entry]](
                 Map("delegate-a" -> BaseDriftWatch.Entry(baselineTip = "a1", lastSeenTip = "a1"))
               )
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      clock <- Ref.of[IO, Long](1000L)
      d = rec("delegate-a")
      _ <- BaseDriftWatch.scan(port(d, Some("z9"), Nil, true, state, sent, clock))
      s <- sent.get
    yield
      assertEquals(s.size, 1)
      assert(s.head._2.contains("history was rewritten"), s"改写警示必须在文案里：${s.head._2}")
  }

  test("⑧ 负控：会话不 live ⇒ 零通知但记账推进（唤醒后不补发过期漂移）") {
    for
      state <- Ref.of[IO, Map[String, BaseDriftWatch.Entry]](
                 Map("delegate-a" -> BaseDriftWatch.Entry(baselineTip = "a1", lastSeenTip = "a1"))
               )
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      clock <- Ref.of[IO, Long](1000L)
      d = rec("delegate-a")
      _ <- BaseDriftWatch.scan(
             port(d, Some("b2"), List("src/mine/M.scala"), false, state, sent, clock, live = Set.empty)
           )
      s <- sent.get
      st <- state.get
    yield
      assertEquals(s, Nil)
      assertEquals(st("delegate-a").lastSeenTip, "b2", "记账仍推进")
      assertEquals(st("delegate-a").pendingConflicts, 0, "挂账丢弃（唤醒后不补发）")
      assertEquals(st("delegate-a").moves, 1, "面板计数照数")
  }

  test("⑨ 负控：已归档/已取消的 delegate 不跟踪") {
    val archived = rec("delegate-b").copy(archivedAt = Some(1L))
    val cancelled = rec("delegate-c").copy(cancelRequestedAt = Some(1L))
    for
      state <- Ref.of[IO, Map[String, BaseDriftWatch.Entry]](
                 Map(
                   "delegate-b" -> BaseDriftWatch.Entry(baselineTip = "old1", lastSeenTip = "old1"),
                   "delegate-c" -> BaseDriftWatch.Entry(baselineTip = "old2", lastSeenTip = "old2")
                 )
               )
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      clock <- Ref.of[IO, Long](1000L)
      out <- BaseDriftWatch.scan(
               FakePort(
                 IO.pure(List(archived, cancelled)),
                 Set("delegate-b", "delegate-c"),
                 Some("new3"),
                 List("src/mine/M.scala"),
                 false,
                 state,
                 sent,
                 clock
               )
             )
      s <- sent.get
    yield
      assertEquals(out, Nil)
      assertEquals(s, Nil)
  }

  test("⑩ 首见只记基线：无记账 + tip 已前进 ⇒ 不通知（不知道「从哪来」）") {
    for
      state <- Ref.of[IO, Map[String, BaseDriftWatch.Entry]](Map.empty)
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      clock <- Ref.of[IO, Long](1000L)
      _ <- BaseDriftWatch.scan(
             port(rec("delegate-a"), Some("b2"), List("src/mine/M.scala"), false, state, sent, clock)
           )
      s <- sent.get
      st <- state.get
    yield
      assertEquals(s, Nil)
      assertEquals(st("delegate-a"), BaseDriftWatch.Entry(baselineTip = "b2", lastSeenTip = "b2"))
  }

end BaseDriftWatchSpec
