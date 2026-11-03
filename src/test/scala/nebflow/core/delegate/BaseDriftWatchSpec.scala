package nebflow.core.delegate

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite

/**
 * 缺陷四 · main 漂移无通知（引擎会话治理专项，作者令 2026-11-03）：
 * 「向在飞 delegate 注入 `[base-moved] old→new，涉及文件列表`（复用 Mail/注入通道，
 * 同一 tip 只通知一次）」。
 *
 * 改前红：引擎对 base tip 漂移零感知（无 BaseDriftWatch、无注入）⇒ 断言「漂移后
 * 会话收到一条事件」「同一 tip 只发一次」全红。
 * 改后绿：首次见只记账（baseline 不通知）→ tip 变了通知一次 → 同 tip 再扫零通知。
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
      projectWorkspace = Some("/tmp/ws")
    )

  private final case class FakePort(
    delegatesIo: IO[List[DelegateRegistry.Record]],
    live: Set[String],
    tips: String => Option[String],
    files: List[String],
    state: Ref[IO, Map[String, String]],
    sent: Ref[IO, List[(String, String)]]
  ) extends BaseDriftWatch.Port:
    def delegates = delegatesIo
    def liveSessionIds = IO.pure(live)
    def headTip(workspace: String, branch: String): Option[String] = tips(branch)
    def changedFiles(workspace: String, from: String, to: String): List[String] = files
    def notify(sessionId: String, text: String): IO[Boolean] = sent.update(_ :+ (sessionId, text)).as(true)
    override def loadState = state.get
    override def saveState(s: Map[String, String]) = state.set(s)

  test("① 首次见只记账（baseline），不通知") {
    for
      state <- Ref.of[IO, Map[String, String]](Map.empty)
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      port = FakePort(IO.pure(List(rec("delegate-a"))), Set("delegate-a"), _ => Some("aaaa1111"), List("src/A.scala"), state, sent)
      out <- BaseDriftWatch.scan(port)
      st <- state.get
      s <- sent.get
    yield
      assertEquals(out, Nil)
      assertEquals(s, Nil)
      assertEquals(st.get("delegate-a"), Some("aaaa1111"))
  }

  test("② tip 漂移 ⇒ 注入一次 [base-moved] old→new + 文件列表；同一 tip 再扫零通知（节流）") {
    for
      state <- Ref.of[IO, Map[String, String]](Map("delegate-a" -> "aaaa1111"))
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      port = FakePort(
        IO.pure(List(rec("delegate-a"))),
        Set("delegate-a"),
        _ => Some("bbbb2222"),
        List("src/A.scala", "src/B.scala"),
        state,
        sent
      )
      out <- BaseDriftWatch.scan(port)
      s1 <- sent.get
      st1 <- state.get
      // 第二拍：tip 未变 ⇒ 零通知
      port2 = port.copy(tips = _ => Some("bbbb2222"))
      out2 <- BaseDriftWatch.scan(port2)
      s2 <- sent.get
    yield
      assertEquals(out.size, 1, s"one drift notice expected, got: $out")
      assertEquals(s1.size, 1)
      val (target, text) = s1.head
      assertEquals(target, "delegate-a")
      assert(text.contains("[base-moved] main advanced aaaa1111 → bbbb2222"), s"notice text carries the anchor: $text")
      assert(text.contains("src/A.scala") && text.contains("src/B.scala"), s"file list present: $text")
      assertEquals(st1.get("delegate-a"), Some("bbbb2222"), "throttle anchor advanced")
      assertEquals(out2, Nil, "same tip must not notify twice")
      assertEquals(s2.size, 1)
  }

  test("③ 负控：会话不 live ⇒ 零通知（不打扰已收尾会话），但记账推进") {
    for
      state <- Ref.of[IO, Map[String, String]](Map("delegate-a" -> "aaaa1111"))
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      port = FakePort(IO.pure(List(rec("delegate-a"))), Set.empty, _ => Some("bbbb2222"), Nil, state, sent)
      out <- BaseDriftWatch.scan(port)
      s <- sent.get
    yield
      assertEquals(out, Nil)
      assertEquals(s, Nil)
  }

  test("④ 负控：已归档/已取消的 delegate 不跟踪") {
    val archived = rec("delegate-b").copy(archivedAt = Some(1L))
    val cancelled = rec("delegate-c").copy(cancelRequestedAt = Some(1L))
    for
      state <- Ref.of[IO, Map[String, String]](Map("delegate-b" -> "old1", "delegate-c" -> "old2"))
      sent <- Ref.of[IO, List[(String, String)]](Nil)
      port = FakePort(IO.pure(List(archived, cancelled)), Set("delegate-b", "delegate-c"), _ => Some("new3"), Nil, state, sent)
      out <- BaseDriftWatch.scan(port)
      s <- sent.get
    yield
      assertEquals(out, Nil)
      assertEquals(s, Nil)
  }

end BaseDriftWatchSpec
