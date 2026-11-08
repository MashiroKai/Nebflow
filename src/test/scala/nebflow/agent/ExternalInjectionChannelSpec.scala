package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite
import nebflow.core.{InjectionChannel, SessionStore}
import nebflow.shared.UiMessage

import java.util.concurrent.atomic.AtomicInteger
import scala.io.Source

/**
 * 外部注入统一通道批（2026-11-05）判据门。
 *
 * 作者报障逐字：「所有的外部注入消息应该有统一的途径和蓝色气泡通知，我刚刚看到你好
 * 多条消息触发你，我都看不到是什么」。四条可见性判据（本 spec 逐条落成断言）：
 *   ① 会话里出现一条对应气泡行  → 投递 = 落盘一条 `UiMessage.User(injected=true)`
 *   ② 气泡带来源标识            → 行/帧都带 `source`(且 `visibleExternalEventSource` 有值)
 *   ③ 正文与注入内容一致        → 落盘/帧文本 **逐字等于** 注入正文（不是摘要替换）
 *   ④ 刷新后仍在                → 从盘上回读（`getUiMessages`）仍是同一条
 * 外加「不许刷屏」：同来源同状态的重复注入必须折叠（键与策略见
 * [[nebflow.core.InjectionChannel.dedupKey]]）。
 *
 * 改前必红锚：改前 `visibleExternalEventSource` 对 bg-lifecycle /
 * waiting-dock-care / chain-orphan / team 恒 None ⇒ 这些外部事件**只有**
 * `<system-reminder>` 进 LLM 上下文、前端零气泡（= 作者「我又被触发了但看不到」）。
 */
class ExternalInjectionChannelSpec extends FunSuite:

  private val repoRoot = os.pwd

  private def readSrc(rel: String): String =
    val p = repoRoot / os.RelPath(rel)
    assert(os.exists(p), s"源文件不存在：$p（本 spec 必须在仓根运行）")
    val s = Source.fromFile(p.toIO, "UTF-8")
    try s.mkString
    finally s.close()

  /** 临时会话库（每个用例独立，判据 ④ 的落盘面读数）。 */
  private def withStore[A](f: (SessionStore, os.Path) => A): A =
    val root = os.temp.dir(prefix = "injection-channel-spec")
    val store = new SessionStore(root / "sessions", root / "tasks")
    try f(store, root)
    finally
      try os.remove.all(root)
      catch case _: Throwable => ()

  /** 一条外部注入行的标准形态（与 `AgentActor#emitInjectedUserEvent` 的构造面同形）。 */
  private def row(text: String, source: String, eventType: String = "failed", sender: Option[String] = None) =
    UiMessage.User(
      text,
      Nil,
      injected = true,
      timestamp = 1700000000000L,
      source = Some(source),
      eventType = Some(eventType),
      sender = sender
    )

  private val Sid = "delegate-ext-inject-spec"

  // ── 判据 ②：可见性判据必须**覆盖**外部事件全集（改前红）────────────────

  test("① 所有外部事件来源都得到可见气泡标签（改前：bg-lifecycle/waiting-dock-care/chain-orphan/team 恒 None）"):
    val mustBeVisible = List(
      "bg-lifecycle" -> "failed",
      "bg-lifecycle" -> "nudge",
      "waiting-dock-care" -> "failed",
      "chain-orphan" -> "chain-orphaned",
      "team" -> "error-escalated",
      "delegate" -> "completed",
      "delegate" -> "frozen",
      "subtask" -> "completed",
      "background-task" -> "completed"
    )
    val invisible = mustBeVisible.filter { case (s, et) => AgentActor.visibleExternalEventSource(s, et).isEmpty }
    assertEquals(
      invisible,
      Nil,
      "以下外部事件只有 <system-reminder> 进 LLM、前端零气泡（作者报障形态）：" + invisible.mkString(", ")
    )

  test("② 来源标签口径：delegate-frozen / background 保留专属标签，其余取原 source（前端逐值可登记）"):
    assertEquals(AgentActor.visibleExternalEventSource("delegate", "frozen"), Some("delegate-frozen"))
    assertEquals(AgentActor.visibleExternalEventSource("background-task", "completed"), Some("background"))
    assertEquals(AgentActor.visibleExternalEventSource("bg-lifecycle", "failed"), Some("bg-lifecycle"))
    assertEquals(AgentActor.visibleExternalEventSource("bg-lifecycle", "nudge"), Some("bg-lifecycle"))
    // API 注入源由调用方提供（用户域），恒可见。
    assertEquals(AgentActor.visibleExternalEventSource("custom-source", "inject"), Some("custom-source"))

  test("③ 自写行腿不重复算（scheduled-task / todo-surface 由调度器自己落卡，走本门会双泡）"):
    assertEquals(AgentActor.visibleExternalEventSource("scheduled-task", "trigger"), None)
    assertEquals(AgentActor.visibleExternalEventSource("scheduled-task", "trigger-batch"), None)
    assertEquals(AgentActor.visibleExternalEventSource("todo-surface", "digest"), None)

  // ── 判据 ①②③④：单点投递同时满足落盘 + 广播 + 正文一致 + 刷新仍在 ────────

  test("④ deliver 一调用 ⇒ 落盘一行 + 广播一帧，且正文逐字一致（判据 ①②③）"):
    withStore { (store, _) =>
      InjectionChannel.resetForTest()
      val frames = new AtomicInteger(0)
      var lastFrame: Option[Json] = None
      val send: InjectionChannel.Send = j =>
        IO { frames.incrementAndGet(); lastFrame = Some(j) }

      val body = "[bg-lifecycle] background job 'x' has been idle for a while: nudge"
      val out = InjectionChannel.deliver(store, send, Sid, row(body, "bg-lifecycle"), dedup = true).unsafeRunSync()

      assertEquals(out, InjectionChannel.Outcome.Emitted)
      assertEquals(frames.get(), 1, "每次投递必须且只能广播一帧")
      val (rows, _) = store.getUiMessages(Sid, 0, 0).unsafeRunSync()
      assertEquals(rows.size, 1, "每次投递必须且只能落一条注入行")
      val stored = rows.head.asInstanceOf[UiMessage.User]
      assertEquals(stored.injected, true, "判据 ①：气泡行必须是注入行")
      assertEquals(stored.source, Some("bg-lifecycle"), "判据 ②：行必须带来源标识")
      assertEquals(stored.text, body, "判据 ③：落盘正文必须与注入内容逐字一致")
      val frame = lastFrame.getOrElse(fail("没有广播帧"))
      assertEquals(frame.hcursor.get[String]("text").toOption, Some(body), "判据 ③：帧正文逐字一致")
      assertEquals(frame.hcursor.get[String]("source").toOption, Some("bg-lifecycle"), "判据 ②：帧带来源")
      assertEquals(frame.hcursor.get[Boolean]("injected").toOption, Some(true))
      assertEquals(frame.hcursor.get[String]("sessionId").toOption, Some(Sid))
    }

  test("⑤ 判据 ④ 刷新后仍在：新开一个 store 读同一目录，行仍在（落盘不是纯内存渲染）"):
    val root = os.temp.dir(prefix = "injection-channel-spec-persist")
    try
      InjectionChannel.resetForTest()
      val body = "[bg-lifecycle] job 123 is still running"
      val store1 = new SessionStore(root / "sessions", root / "tasks")
      InjectionChannel.deliver(store1, _ => IO.unit, Sid, row(body, "bg-lifecycle", "nudge"), dedup = true)
        .unsafeRunSync()
      store1.flushPendingUiWrites.unsafeRunSync()
      // 新实例 = 刷新后的进程视图（缓存清空，只能从盘上读）。
      val store2 = new SessionStore(root / "sessions", root / "tasks")
      val (rows, _) = store2.getUiMessages(Sid, 0, 0).unsafeRunSync()
      assertEquals(rows.size, 1)
      assertEquals(rows.head.asInstanceOf[UiMessage.User].text, body)
      assertEquals(rows.head.asInstanceOf[UiMessage.User].source, Some("bg-lifecycle"))
    finally
      try os.remove.all(root)
      catch case _: Throwable => ()

  // ── 不许刷屏：去重/折叠 ────────────────────────────────────────────────

  test("⑥ 同来源同状态重复投递 ⇒ 折叠（零落盘零广播）；状态变化 ⇒ 追加"):
    withStore { (store, _) =>
      InjectionChannel.resetForTest()
      val frames = new AtomicInteger(0)
      val send: InjectionChannel.Send = _ => IO(frames.incrementAndGet())
      val first = "[bg-lifecycle] member 'a' blocked: base workspace busy"
      val changed = "[bg-lifecycle] member 'a' blocked: granted to another delegate"

      val o1 = InjectionChannel.deliver(store, send, Sid, row(first, "bg-lifecycle"), dedup = true).unsafeRunSync()
      val o2 = InjectionChannel.deliver(store, send, Sid, row(first, "bg-lifecycle"), dedup = true).unsafeRunSync()
      val o3 = InjectionChannel.deliver(store, send, Sid, row(first, "bg-lifecycle"), dedup = true).unsafeRunSync()
      val o4 = InjectionChannel.deliver(store, send, Sid, row(changed, "bg-lifecycle"), dedup = true).unsafeRunSync()

      assertEquals(o1, InjectionChannel.Outcome.Emitted)
      assertEquals(o2, InjectionChannel.Outcome.Collapsed)
      assertEquals(o3, InjectionChannel.Outcome.Collapsed)
      assertEquals(o4, InjectionChannel.Outcome.Emitted, "状态变化（正文变）必须追加，不得折叠")
      assertEquals(frames.get(), 2)
      val (rows, _) = store.getUiMessages(Sid, 0, 0).unsafeRunSync()
      assertEquals(rows.size, 2, "折叠不得落盘（否则面板还是一排蓝）")
      assertEquals(rows.map(_.asInstanceOf[UiMessage.User].text), List(first, changed))
    }

  test("⑦ 去重键含来源与发送方：不同来源/不同成员的同文不互相吞"):
    val same = "identical body"
    assertNotEquals(
      InjectionChannel.dedupKey(Sid, row(same, "bg-lifecycle", "failed", Some("delegate-a"))),
      InjectionChannel.dedupKey(Sid, row(same, "bg-lifecycle", "failed", Some("delegate-b")))
    )
    assertNotEquals(
      InjectionChannel.dedupKey(Sid, row(same, "bg-lifecycle", "failed", Some("delegate-a"))),
      InjectionChannel.dedupKey(Sid, row(same, "team", "failed", Some("delegate-a")))
    )
    assertEquals(
      InjectionChannel.dedupKey(Sid, row(same, "bg-lifecycle")),
      InjectionChannel.dedupKey(Sid, row(same, "bg-lifecycle")),
      "同一行必须给同一把键（否则永远折叠不到）"
    )

  test("⑧ 窗口可关（-Dnebflow.inject.dedup.windowMs<=0）：逐条放行 —— 变异验红面"):
    withStore { (store, _) =>
      InjectionChannel.resetForTest()
      val prev = sys.props.get(InjectionChannel.WindowProp)
      sys.props(InjectionChannel.WindowProp) = "0"
      try
        val frames = new AtomicInteger(0)
        val send: InjectionChannel.Send = _ => IO(frames.incrementAndGet())
        val body = "same notice"
        val outs = (1 to 3)
          .map(_ => InjectionChannel.deliver(store, send, Sid, row(body, "bg-lifecycle"), dedup = true).unsafeRunSync())
          .toList
        assertEquals(outs, List.fill(3)(InjectionChannel.Outcome.Emitted))
        assertEquals(frames.get(), 3)
      finally
        prev.fold(sys.props.remove(InjectionChannel.WindowProp))(v => sys.props(InjectionChannel.WindowProp) = v)
        InjectionChannel.resetForTest()
    }

  test("⑨ 非外部事件族不参与去重（Mail/skill 等改动点默认 dedup=false ⇒ 行为逐字不变）"):
    withStore { (store, _) =>
      InjectionChannel.resetForTest()
      val frames = new AtomicInteger(0)
      val send: InjectionChannel.Send = _ => IO(frames.incrementAndGet())
      val body = "same mail body"
      InjectionChannel.deliver(store, send, Sid, row(body, "mail", "info", Some("worker"))).unsafeRunSync()
      InjectionChannel.deliver(store, send, Sid, row(body, "mail", "info", Some("worker"))).unsafeRunSync()
      assertEquals(frames.get(), 2, "默认不去重：Mail 同文两次是两次合法投递")
    }

  // ── 单点纪律：所有外侧投递腿都必须走同一入口（禁各写一份）──────────────

  test("⑩ 调度器两条腿改走 InjectionChannel（禁再手拼 user 帧 + 各自 appendUiMessages）"):
    val src = readSrc("src/main/scala/nebflow/core/scheduler/ScheduledTaskActor.scala")
    assert(
      "InjectionChannel\\.deliver".r.findAllIn(src).size >= 2,
      "injectScheduleCard / injectTodoDigest 必须各走一次 InjectionChannel.deliver"
    )
    assert(
      !src.contains("\"type\" -> \"user\".asJson"),
      "调度器不得再手拼注入 user 帧（帧形态单点 = InjectionChannel.frame）"
    )

  test("⑪ agent 侧唯一发射点走同一入口（落盘/广播单点，禁止第三条腿）"):
    val src = readSrc("src/main/scala/nebflow/agent/AgentActor.scala")
    assert(src.contains("InjectionChannel.deliver("), "emitInjectedUserEvent 必须经 InjectionChannel.deliver")
    assert(
      !src.contains(".appendUiMessages(sid, List(injectedUiRow))"),
      "发射点不得自带第二份落盘实现（会与通道漂移）"
    )

end ExternalInjectionChannelSpec
