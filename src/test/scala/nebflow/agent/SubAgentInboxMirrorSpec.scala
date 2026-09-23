package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import munit.FunSuite

import nebflow.gateway.SessionStore
import nebflow.shared.UiMessage

import scala.io.Source

/**
 * 子代理收件（卡08 裁点 2，作者 2026-09-20 07:33 批「建」）——
 * **路由表 + 落盘形态 + 禁越面**三重契约门。
 *
 * 三层断言（禁自查自证：每层都给可复算读数）：
 *   ① **纯路由**（[[InjectedInboxMirror.targets]]）：白名单族命中 / 豁免族零命中 /
 *      跨 root 反例 / 自身排除 / 族前缀判据 / 开关关闭 ⇒ 零目标。
 *   ② **真落盘**（真 `SessionStore` + 临时目录 + 真 `appendUiMessages`）：镜像行
 *      **逐字段同形**落到目标子代理会话流；无关根的子代理**零行**；发射者自身会话
 *      **零增量**（禁重复显示）。
 *   ③ **禁越面 / 单点**：镜像腿在唯一发射点收口（`"injected" -> true` 仍单命中、
 *      11 处调用点零改）；镜像件零 `wsSend` / 零 `AgentCommand`（只落会话流、不投
 *      agent、零 WS 帧）；后端族前缀表与前端 `isBgAgentId` **逐值同源**（两侧对账）。
 *
 * **开关感知（变异验红用）**：本 spec 在 [[InjectedInboxMirror.enabled]] 两个态下都
 * 断言**相反**的数据面读数（开 ⇒ 逐族命中 + 目标会话流恰 1 行；关 ⇒ 全零）⇒ 同一
 * 份 spec 换开关跑两次即可证明「读数真在看镜像面」（读数落 `mirror_readings.json`）。
 * 演练形：`NEBFLOW_SUBINBOX_MIRROR=off <buildgate> run -- sbt … testOnly …`。
 */
class SubAgentInboxMirrorSpec extends FunSuite:

  private val repoRoot = os.pwd

  private def read(rel: String): String =
    val p = repoRoot / os.RelPath(rel).segments
    assert(os.exists(p), s"源码不存在：$p（本 spec 必须在仓根运行）")
    val src = Source.fromFile(p.toIO, "UTF-8")
    try src.mkString
    finally src.close()

  private lazy val agentActor: String = read("src/main/scala/nebflow/agent/AgentActor.scala")
  private lazy val mirrorSrc: String = read("src/main/scala/nebflow/agent/InjectedInboxMirror.scala")
  private lazy val utilsJs: String = read("src/main/resources/web/js/utils.js")
  private lazy val chatJs: String = read("src/main/resources/web/js/chat.js")

  /** 去注释后的**代码面**（禁越面判据只吃代码，注释里的说明文字不算命中）。 */
  private def codeOf(src: String): String =
    src.linesIterator
      .filterNot { l =>
        val t = l.trim
        t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/")
      }
      .mkString("\n")

  /** 开关态（本 spec 两侧断言的唯一分叉点）。 */
  private def sw: Boolean = InjectedInboxMirror.enabled

  /** 开关感知期望：开 ⇒ `on`，关 ⇒ `off`。 */
  private def gated[A](on: => A, off: => A): A = if sw then on else off

  private def toggleOff[A](body: => A): A =
    val prev = sys.props.get(InjectedInboxMirror.SwitchProp)
    try
      sys.props(InjectedInboxMirror.SwitchProp) = "off"
      body
    finally
      prev match
        case Some(v) => sys.props(InjectedInboxMirror.SwitchProp) = v
        case None => sys.props.remove(InjectedInboxMirror.SwitchProp)

  // ============================================================
  // ① 纯路由（路由表逐行）
  // ============================================================

  /** 注册表候选集（`sessionId -> rootSessionId`）——覆盖 4 个族前缀 + 两个 root + 非族会话。 */
  private val candidates: List[(String, String)] = List(
    "root-1" -> "root-1", // 发射者自身：永不入选
    "delegate-A" -> "root-1", // 命中（delegate- 族）
    "node-B" -> "root-1", // 命中（node- 族）
    "subtask-D" -> "root-1", // 命中（subtask- 族）
    "dispatcher-E" -> "root-1", // 命中（dispatcher- 族）
    "delegate-C" -> "root-2", // 反例：别的 root 的子代理 ⇒ 不入选
    "node-F" -> "", // 反例：无 root 归属 ⇒ 不入选
    "7f0d0e3a-1111-2222-3333-444455556666" -> "root-1", // 反例：非子代理族前缀（team 成员等）
    "team-abc" -> "root-1" // 反例：team- 不在前端 isBgAgentId 内
  )

  private val hitSet = List("delegate-A", "dispatcher-E", "node-B", "subtask-D")

  test("① -1 白名单族（mail / mail-queue）逐族命中：目标集 = parent 链 π 子代理族前缀，去重且稳定") {
    assertEquals(InjectedInboxMirror.targets("root-1", "mail", candidates), gated(hitSet, Nil))
    assertEquals(InjectedInboxMirror.targets("root-1", "mail-queue", candidates), gated(hitSet, Nil))
    // 白名单 pin（唯一来源；改集合 ⇒ 必须同步批报告路由表）
    assertEquals(InjectedInboxMirror.MirrorSources, Set("mail", "mail-queue"))
    assertEquals(
      InjectedInboxMirror.SubAgentIdPrefixes,
      List("delegate-", "subtask-", "node-", "dispatcher-")
    )
    // 开关定名 pin（读法 = system property → 环境变量）
    assertEquals(InjectedInboxMirror.SwitchProp, "nebflow.subinbox.mirror")
    assertEquals(InjectedInboxMirror.SwitchEnv, "NEBFLOW_SUBINBOX_MIRROR")
    assertEquals(InjectedInboxMirror.OffValues, Set("off", "false", "0"))
  }

  test("① -2 反例：豁免族逐族零命中（原生族 / node / ask / 非注入族）") {
    for src <- List(
        "tool",
        "system",
        "delegate",
        "subtask",
        "task",
        "dispatch",
        "flow",
        "background",
        "skill",
        "node",
        "ask",
        "chain",
        "deviceMail",
        ""
      )
    do
      assertEquals(
        InjectedInboxMirror.targets("root-1", src, candidates),
        Nil,
        s"豁免族 '$src' 被镜像 —— 路由表白名单漂移"
      )
  }

  test("① -3 反例：不相关子代理不收到（跨 root / 无归属 / 非子代理族）——逐条给读数") {
    val t = InjectedInboxMirror.targets("root-1", "mail", candidates)
    if sw then
      assert(!t.contains("delegate-C"), "别的 root 的子代理被镜像（跨 root 串流）")
      assert(!t.contains("node-F"), "无 root 归属的子代理被镜像（归属判据失效）")
      assert(!t.contains("7f0d0e3a-1111-2222-3333-444455556666"), "非子代理族会话被镜像（越出弹窗承载面）")
      assert(!t.contains("team-abc"), "team- 前缀会话被镜像（越出 isBgAgentId 外延）")
      assert(!t.contains("root-1"), "发射者自身被镜像（同一窗口重复显示）")
    assertEquals(
      InjectedInboxMirror.targets("root-2", "mail", candidates),
      gated(List("delegate-C"), Nil),
      "另一 root 的镜像集必须恰为其自己的子代理（对偶读数）"
    )
  }

  test("① -4 反例：发射者本身是子代理会话 ⇒ 零目标（镜像只在主控/根侧发射时生效）") {
    for emitter <- List("delegate-A", "subtask-D", "node-B", "dispatcher-E") do
      assertEquals(
        InjectedInboxMirror.targets(emitter, "mail", candidates),
        Nil,
        s"发射者 $emitter 是子代理会话 —— 不应再向下镜像"
      )
    assertEquals(InjectedInboxMirror.isSubAgentSession("delegate-A"), true)
    assertEquals(InjectedInboxMirror.isSubAgentSession("node-1"), true)
    assertEquals(InjectedInboxMirror.isSubAgentSession("root-1"), false)
    assertEquals(InjectedInboxMirror.isSubAgentSession("team-abc"), false)
    assertEquals(InjectedInboxMirror.isSubAgentSession(""), false)
    assertEquals(InjectedInboxMirror.isMirrorSource("mail"), true)
    assertEquals(InjectedInboxMirror.isMirrorSource("tool"), false)
  }

  test("① -5 变异验红（开关）：默认开 ⇒ 命中；开关关 ⇒ 同一输入归零（证明读数在看镜像面）") {
    val on = InjectedInboxMirror.targets("root-1", "mail", candidates)
    assert(on.nonEmpty, "默认态（开关开启）必须命中")
    assertEquals(InjectedInboxMirror.enabled, true)
    toggleOff {
      assertEquals(
        InjectedInboxMirror.targets("root-1", "mail", candidates),
        Nil,
        "开关=off 仍未归零 —— 开关未被判据读取（假绿风险）"
      )
      assertEquals(InjectedInboxMirror.enabled, false)
    }
    assertEquals(InjectedInboxMirror.targets("root-1", "mail", candidates), on, "开关恢复后未回到命中态")
  }

  // ============================================================
  // ② 真落盘形态（数据面 Δ）
  // ============================================================

  test("② 镜像行逐字段同形落入目标子代理会话流；无关根零行；发射者自身零增量") {
    val evidenceOverride = sys.env.get("NEBFLOW_SUBINBOX_EVIDENCE_DIR").map(d => os.Path(d))
    val tempRoot = evidenceOverride match
      case Some(d) => d / "green_store"
      case None => os.pwd / "target" / "test-subinbox-mirror"
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)

    val store = SessionStore(tempRoot / "sessions", tempRoot / "tasks")
    val row = UiMessage.User(
      "MIRROR-PROBE mail body (chain-subinbox acceptance 2)",
      injected = true,
      timestamp = 1700000000123L,
      source = Some("mail"),
      eventType = Some("result"),
      sender = Some("KAI"),
      senderTeam = None,
      delivery = Some("immediate"),
      intake = None,
      header = None
    )
    val records = List(
      "root-1" -> "root-1",
      "delegate-A" -> "root-1",
      "node-B" -> "root-1",
      "delegate-C" -> "root-2"
    )
    val append: InjectedInboxMirror.Append =
      (target, r) => store.appendUiMessages(target, List(r))

    val program: IO[List[String]] =
      for
        // 发射者自身会话的既有落盘腿（模拟改前行为：root 自己那份照写）
        _ <- store.appendUiMessages("root-1", List(row.copy(text = "ROOT-OWN-ROW")))
        // 无关根的子代理先落一行，证明它只受自己的 root 影响
        _ <- store.appendUiMessages("delegate-C", List(row.copy(text = "OTHER-ROOT-ROW")))
        hit <- InjectedInboxMirror.mirror(records, "root-1", "mail", row, append)
        _ <- store.flushPendingUiWrites
      yield hit

    val hits = program.unsafeRunSync()
    assertEquals(hits, gated(List("delegate-A", "node-B"), Nil))

    def rowsOrEmpty(sid: String): List[UiMessage] =
      val f = tempRoot / "sessions" / s"$sid.ui.json"
      if !os.exists(f) then Nil
      else decode[List[UiMessage]](os.read(f)).fold(e => fail(s"$sid.ui.json 解码失败：$e"), identity)

    val rootOwn = rowsOrEmpty("root-1").collect { case u: UiMessage.User => u }
    assertEquals(rootOwn.map(_.text), List("ROOT-OWN-ROW"), "发射者自身会话被镜像腿改动（主控回归）")
    val otherRoot = rowsOrEmpty("delegate-C").collect { case u: UiMessage.User => u }
    assertEquals(otherRoot.map(_.text), List("OTHER-ROOT-ROW"), "无关根的子代理被串流")

    val target = rowsOrEmpty("delegate-A").collect { case u: UiMessage.User => u }
    if sw then
      assertEquals(target.size, 1, "目标子代理会话流的镜像行数 ≠ 1（重复/漏写）")
      // 逐字段同形（同一实例落两处 ⇒ 同一事件在两侧窗口逐字节同形）
      assertEquals(target.head.text, row.text)
      assertEquals(target.head.timestamp, row.timestamp)
      assertEquals(target.head.injected, true)
      assertEquals(target.head.source, Some("mail"))
      assertEquals(target.head.eventType, Some("result"))
      assertEquals(target.head.sender, Some("KAI"))
      assertEquals(target.head.delivery, Some("immediate"))
      assertEquals(target.head.intake, None)
      assertEquals(target.head.header, None)
      val nodeRows = rowsOrEmpty("node-B").collect { case u: UiMessage.User => u }
      assertEquals(nodeRows.map(_.text), List(row.text), "多目标并发时每个窗口各得一行")
    else assertEquals(target, Nil, "开关关闭态下目标子代理会话流仍有镜像行（假绿）")
    end if

    // ② -变异（同批次内）：开关关 ⇒ 零目标 + 目标会话流零新增
    val before = rowsOrEmpty("delegate-A").size
    toggleOff {
      assertEquals(
        InjectedInboxMirror.mirror(records, "root-1", "mail", row.copy(text = "MUTANT"), append).unsafeRunSync(),
        Nil
      )
    }
    store.flushPendingUiWrites.unsafeRunSync()
    assertEquals(rowsOrEmpty("delegate-A").size, before, "变异关闭后目标会话流出现新行")

    evidenceOverride.foreach { d =>
      val readings = s"""{
  "batch": "chain-subinbox / subagent-inbox-mirror",
  "switch": ${if sw then "\"on\"" else "\"off\""},
  "sessions_dir": "${(tempRoot / "sessions").toString}",
  "emitter": "root-1",
  "mirror_source": "mail",
  "mirror_targets_hit": ${hits.map(h => s""""$h"""").mkString("[", ",", "]")},
  "mirror_rows_per_target": ${target.size},
  "root_own_rows": ${rootOwn.size},
  "unrelated_root_subagent_rows": ${otherRoot.size}
}
"""
      os.write.over(tempRoot / "mirror_readings.json", readings, createFolders = true)
      println(s"SUBINBOX-MIRROR-EVIDENCE ${(tempRoot / "mirror_readings.json").toString}")
    }
  }

  // ============================================================
  // ③ 禁越面 / 单点 / 两侧同源
  // ============================================================

  test("③ -1 前端登记面逐值同源：后端族前缀表 == utils.js#isBgAgentId 字面量") {
    val jsStart = utilsJs.indexOf("export function isBgAgentId")
    assert(jsStart > 0, "utils.js 未找到 isBgAgentId")
    val body = utilsJs.substring(jsStart, utilsJs.indexOf("\n}", jsStart))
    for p <- InjectedInboxMirror.SubAgentIdPrefixes do
      assert(body.contains(s"startsWith('$p')"), s"前端 isBgAgentId 缺前缀 '$p'（两侧表已漂移）")
    val jsPrefixes = "'([a-z]+-)'".r.findAllMatchIn(body).map(_.group(1)).toList.distinct
    assertEquals(
      jsPrefixes.sorted,
      InjectedInboxMirror.SubAgentIdPrefixes.sorted,
      "前端 isBgAgentId 前缀集与后端 SubAgentIdPrefixes 不一致（禁单侧扩面）"
    )
  }

  test("③ -2 单点收口：注入行唯一发射点仍在，11 处调用点零改，镜像腿挂在发射点内") {
    assertEquals(
      "\"injected\" -> true.asJson".r.findAllMatchIn(agentActor).size,
      1,
      "AgentActor 内 `\"injected\" -> true.asJson` 不再单命中 —— 注入行出现了第二个写者"
    )
    assertEquals(
      "emitInjectedUserEvent\\(".r.findAllMatchIn(agentActor).size,
      12,
      "`emitInjectedUserEvent(` 命中数漂移（应为 11 处调用 + 1 处 def）——本批禁改调用点"
    )
    val emitStart = agentActor.indexOf("private def emitInjectedUserEvent(")
    val applyStart = agentActor.indexOf("\n  def apply(", emitStart)
    assert(emitStart > 0 && applyStart > emitStart, "发射点边界定位失败")
    val emitBody = agentActor.substring(emitStart, applyStart)
    assert(
      emitBody.contains("InjectedInboxMirror.mirror("),
      "镜像腿未挂在唯一发射点内 —— 11 处调用点的镜像扩展落点漂移"
    )
    assert(
      emitBody.indexOf("wsSend(withWaiting)") < emitBody.indexOf("*> inboxMirror"),
      "镜像腿必须在 wsSend 之后（live 帧时序逐字不变）"
    )
    assert(
      emitBody.contains("reg.iterator.map((s, rec) => (s, rec.rootSessionId))"),
      "镜像目标解析未走注册表 parent 链（rootSessionId）"
    )
  }

  test("③ -3 禁越面：镜像件零 WS 帧、零 agent 投递、零注入文案/生成面改动") {
    val mirrorCode = codeOf(mirrorSrc)
    for banned <- List("wsSend", "wsHub", "broadcast", "AgentCommand", "ImmediateInput", "UserInput") do
      assertEquals(
        mirrorCode.contains(banned),
        false,
        s"镜像件代码面出现 '$banned' —— 越出「只落会话流」面（禁 WS 帧 / 禁投 agent）"
      )
    assert(mirrorCode.contains("UiMessage.User"), "镜像行类型漂移（禁新造行类型）")
    assert(mirrorCode.contains("append"), "镜像件未走既有落盘入口 append（禁新造写面）")
    // 既有注入文案面 tripwire：header 帧字段与前端 source 标签表逐字未动
    assert(agentActor.contains("""Json.obj("header" -> h.asJson)"""), "header 帧字段被改动")
    assert(chatJs.contains("mail: 'Mail'"), "前端 source 标签表被改动 —— 渲染侧零改是本批硬边界")
  }
end SubAgentInboxMirrorSpec
