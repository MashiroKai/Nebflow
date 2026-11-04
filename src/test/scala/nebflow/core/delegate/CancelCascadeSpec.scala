package nebflow.core.delegate

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord, AgentStatus}

import scala.concurrent.duration.*

/**
 * 缺陷七 · cancel 级联 + 孤儿子会话检测的机械断言（引擎会话治理专项，作者
 * 2026-11-03 15:09 报障「你杀掉父会话，子会话也应该一并关闭……子会话变成孤儿」）。
 *
 * 改前红（两测对 HEAD 旧代码）：
 *   - ①「cancel 父 ⇒ 子会话 N 秒内全终态」——旧 doCancel 零子会话级联，子行原地
 *     不动 ⇒ `childrenAllTerminal` 断言失败；
 *   - ②「父终态子仍活 ⇒ 孤儿检测命中并处置」——旧代码无 ChildOrphanWatch ⇒
 *     无处置、无审计 ⇒ 断言失败。
 * 改后绿：CancelCascade.cascade（生产装配 = AgentControlTool.doCancel 级联腿）+
 * ChildOrphanWatch.scan（挂 TaskStuckWatcher 拍尾）在假端口上全绿。
 */
class CancelCascadeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def rec(
    id: String,
    parent: String,
    status: AgentStatus = AgentStatus.Processing,
    supervisor: Boolean = true
  ): AgentRecord =
    AgentRecord(
      sessionId = id,
      ref = null, // 假端口不经 actor——ref 只被生产腿触碰
      kind = AgentKind.SubTask,
      rootSessionId = "root-x",
      parentSessionId = parent,
      status = status,
      supervisorRef = if supervisor then Some(null) else None
    )

  /** 可变假注册表：id → AgentRecord（Ref 承载，级联/扫描按真实语义读写）。 */
  private final class FakePort(
    reg: Ref[IO, Map[String, AgentRecord]],
    tombstones: Ref[IO, Set[String]],
    val stopped: Ref[IO, List[String]],
    val rows: Ref[IO, List[String]],
    val audits: Ref[IO, List[Map[String, String]]],
    val notifies: Ref[IO, List[String]]
  ) extends ChildOrphanWatch.Port:
    def registry = reg.get
    def tombstonedParents = tombstones.get

    /** 级联端口视图（同一份假注册表；cancelChild 返回 Either 形态）。 */
    def asCascadePort: CancelCascade.Port = new CancelCascade.Port:
      def registry = reg.get
      def tombstoned = tombstones.get
      def cancelChild(r: AgentRecord, why: String) = FakePort.this.cancelChild(r, why).as(Right(()))
    def cancelChild(r: AgentRecord, reason: String) =
      stopped.update(_ :+ r.sessionId) *> reg.update(_ - r.sessionId) *>
        tombstones.update(_ + r.sessionId)
    def settleTaskRow(r: AgentRecord, reason: String) = rows.update(_ :+ r.sessionId)
    def notifyRoot(r: AgentRecord, text: String) = notifies.update(_ :+ text)
    override def audit(values: Map[String, String]) = audits.update(_ :+ values)

  private def mkPort(initial: Map[String, AgentRecord]): IO[(FakePort, Ref[IO, Map[String, AgentRecord]])] =
    for
      reg <- Ref.of[IO, Map[String, AgentRecord]](initial)
      ts <- Ref.of[IO, Set[String]](Set.empty)
      stopped <- Ref.of[IO, List[String]](Nil)
      rows <- Ref.of[IO, List[String]](Nil)
      audits <- Ref.of[IO, List[Map[String, String]]](Nil)
      notifies <- Ref.of[IO, List[String]](Nil)
    yield (FakePort(reg, ts, stopped, rows, audits, notifies), reg)

  // ── 断言 a：带 ≥2 子会话的父 cancel ⇒ 子会话全终态、无 Processing 残留 ────
  test("① 级联：cancel 带两个子会话的父 ⇒ 子会话全被处置、注册表无 Processing 残留、报告逐条") {
    for
      port <- mkPort(
        Map(
          "father" -> rec("father", "root-x"),
          "kid-1" -> rec("kid-1", "father"),
          "kid-2" -> rec("kid-2", "father")
        )
      )
      (fake, _) = port
      steps <- CancelCascade.cascade(fake.asCascadePort, "father")
      reg <- fake.registry
      stopped <- fake.stopped.get
    yield
      // 全部子会话被处置（逐条 killed）；父记录本身的结算归 doCancel 主腿（生产语义），
      // 这里只断言**子层**无 Processing 残留。
      assertEquals(steps.map(_.sessionId), List("kid-1", "kid-2"))
      assert(steps.forall(_.outcome == CancelCascade.Outcome.Killed), s"all children killed, got: $steps")
      // N 秒内全终态（假端口同步处置 ⇒ 立即达成；等价于真实现 CancelReceipt 窗内的终态收敛）
      assertEquals(reg.get("kid-1"), None)
      assertEquals(reg.get("kid-2"), None)
      assert(
        !reg.values.filterNot(_.sessionId == "father").exists(_.status == AgentStatus.Processing),
        s"no child Processing residue, got: ${reg.values}"
      )
      assertEquals(stopped.sorted, List("kid-1", "kid-2"))
      // 报告逐条（killed 计数 2）
      val rep = CancelCascade.report(steps)
      assert(rep.contains("kid-1") && rep.contains("kid-2") && rep.contains("2 killed"), rep)
  }

  test("①b 级联孙子层：cancel 祖父 ⇒ 孙先于子被处置（后序），报告含全代") {
    for
      port <- mkPort(
        Map(
          "grand" -> rec("grand", "root-x"),
          "mid" -> rec("mid", "grand"),
          "leaf" -> rec("leaf", "mid")
        )
      )
      (fake, _) = port
      steps <- CancelCascade.cascade(fake.asCascadePort, "grand")
      stopped <- fake.stopped.get
    yield
      // 后序：leaf 先停，mid 后停（同一 IO 链序）
      assertEquals(stopped, List("leaf", "mid"))
      assertEquals(steps.map(_.sessionId).sorted, List("leaf", "mid"))
  }

  test("①c 已有墓碑的子 = already-terminal，不重复处置") {
    for
      port <- mkPort(Map("kid" -> rec("kid", "father")))
      (fake, _) = port
      steps <- CancelCascade.cascade(new CancelCascade.Port:
        def registry = fake.registry
        def tombstoned = IO.pure(Set("kid"))
        def cancelChild(r: AgentRecord, why: String) = IO.raiseError(new RuntimeException("must not cancel a tombstoned child"))
      , "father")
      reg <- fake.registry
      stopped <- fake.stopped.get
    yield
      assertEquals(steps.map(_.outcome), List(CancelCascade.Outcome.AlreadyTerminal))
      assertEquals(stopped, Nil)
      assertEquals(reg.get("kid").map(_.sessionId), Some("kid"))
  }

  // ── 断言 b：父已终态、子仍活 ⇒ 孤儿检测命中并处置 ─────────────────────
  test("② 孤儿检测：父墓碑在案 + 子仍 Processing ⇒ 标孤儿 + 处置 + 审计 + 根通知") {
    for
      port <- mkPort(
        Map(
          "dead-parent" -> rec("dead-parent", "root-x", status = AgentStatus.Idle),
          "orphan-kid" -> rec("orphan-kid", "dead-parent")
        )
      )
      (fake, _) = port
      _ <- fake.tombstonedParents // touch
      steps <- ChildOrphanWatch.scan(new ChildOrphanWatch.Port:
        def registry = fake.registry
        def tombstonedParents = IO.pure(Set("dead-parent"))
        def cancelChild(r: AgentRecord, reason: String) = fake.cancelChild(r, reason)
        def settleTaskRow(r: AgentRecord, reason: String) = fake.settleTaskRow(r, reason)
        def notifyRoot(r: AgentRecord, text: String) = fake.notifyRoot(r, text)
        override def audit(values: Map[String, String]) = fake.audit(values)
      )
      reg <- fake.registry
      audits <- fake.audits.get
      notifies <- fake.notifies.get
      rows <- fake.rows.get
    yield
      assertEquals(steps.map(_.sessionId), List("orphan-kid"))
      assertEquals(steps.headOption.map(_.outcome), Some(CancelCascade.Outcome.Killed))
      assertEquals(reg.get("orphan-kid"), None) // 面板不再显示运行中
      assertEquals(audits.map(_("sessionId")), List("orphan-kid")) // 审计留档
      assert(notifies.nonEmpty && notifies.head.contains("orphan-kid"), s"root notified: $notifies")
      assertEquals(rows, List("orphan-kid")) // 任务行终态化
  }

  test("②b 负控：父无墓碑/归档 ⇒ 子绝不误判孤儿（防误杀铁律）") {
    for
      port <- mkPort(Map("kid" -> rec("kid", "alive-parent")))
      (fake, _) = port
      steps <- ChildOrphanWatch.scan(fake)
      reg <- fake.registry
    yield
      assertEquals(steps, Nil)
      assertEquals(reg.get("kid").map(_.sessionId), Some("kid"))
  }

  test("②c report 档：只上报不终止（现场保留）") {
    for
      port <- mkPort(Map("kid" -> rec("kid", "dead-parent")))
      (fake, reg0) = port
      steps <- setPropIo("nebflow.session.orphan.action", "report") {
        ChildOrphanWatch.scan(new ChildOrphanWatch.Port:
          def registry = fake.registry
          def tombstonedParents = IO.pure(Set("dead-parent"))
          def cancelChild(r: AgentRecord, reason: String) = IO.raiseError(new RuntimeException("must not cancel in report mode"))
          def settleTaskRow(r: AgentRecord, reason: String) = fake.settleTaskRow(r, reason)
          def notifyRoot(r: AgentRecord, text: String) = fake.notifyRoot(r, text)
        )
      }
      reg <- fake.registry
      notifies <- fake.notifies.get
    yield
      assertEquals(steps.headOption.map(_.outcome), Some("reported"))
      assertEquals(reg.get("kid").map(_.sessionId), Some("kid")) // 现场保留
      assert(notifies.nonEmpty, s"root still notified: $notifies")
  }

  /** 在 IO 内设置 sys prop 并在结束后复原（munit 的 test body 构造 = 先于执行，绝不能在
    * 构造期设 prop 再在 finally 复原：执行时已还原，判定走错分支）。 */
  private def setPropIo[A](key: String, value: String)(body: => IO[A]): IO[A] =
    val prev = sys.props.get(key)
    IO(sys.props(key) = value) *> body.guarantee(
      IO(prev match { case Some(v) => sys.props(key) = v; case None => sys.props.remove(key) }).void
    )

end CancelCascadeSpec
