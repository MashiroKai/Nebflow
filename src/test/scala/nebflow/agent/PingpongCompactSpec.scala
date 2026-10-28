/* 乒乓压缩一期（主会话无感上下文压缩，2026-10-09）验收 spec。
 * 权威规格 = proto-pingpong/20261009_102847_pingpong-compaction-design-spec.md。
 * 覆盖：触发门矩阵（§G1 其他会话零 diff 的静态面）、S 解析 pingpong 分支（§C）、
 * 换入构建 Δ 零丢失 + 断言格（§D3/§E-6）、恢复幂等闸（§E-8）、state.json 读写
 * 与崩溃恢复三格（§E-7/8/9）、排队位/退避/回落判定算术（§A4/§E-1）、配置开关
 * 默认值（§G2）。 */
package nebflow.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.{
  AgentDef,
  AgentState,
  PingpongShadowJob,
  PingpongSReady,
  PingpongState,
  messages,
  withForkQueued,
  withPpFailures,
  withPingpongCleared,
  withShadowPending,
  withSReadyPending
}
import nebflow.core.compact.{CompactConfig, FullCompact, PingpongConfig, TokenEstimator}
import nebflow.shared.{Message, MessageRole, PathUtil}
import nebflow.shared.given

class PingpongCompactSpec extends FunSuite:

  // ── fixtures ──────────────────────────────────────────────────────────────

  private def rootDef: AgentDef = AgentDef(name = "Nebula", description = "root")
  private def otherDef: AgentDef = AgentDef(name = "Helper", description = "standalone")

  private def userMsg(text: String): Message = Message(MessageRole.User, Left(text))
  private def asstMsg(text: String): Message = Message(MessageRole.Assistant, Left(text))

  private def summaryResponse(body: String): String =
    s"""<analysis>thinking scratchpad</analysis>
       |<summary>
       |$body
       |</summary>
       |<files>
       |src/main/scala/A.scala
       |</files>""".stripMargin

  // ── §G1 触发门矩阵：非根会话 / 开关关 ⇒ 门恒关（行为零漂移的静态锚）────────

  test("gate: root × switch-on opens") {
    assert(AgentPingpong.gateOpen(rootDef, depth = 0, switchOn = true))
  }

  test("gate: switch off closes for everyone (default-off contract, §G2)") {
    assert(!AgentPingpong.gateOpen(rootDef, depth = 0, switchOn = false))
  }

  test("gate: non-root names never open (standalone / team / social)") {
    assert(!AgentPingpong.gateOpen(otherDef, depth = 0, switchOn = true))
  }

  test("gate: depth > 0 never opens (node / dispatcher / delegate / team member)") {
    assert(!AgentPingpong.gateOpen(rootDef, depth = 1, switchOn = true))
    assert(!AgentPingpong.gateOpen(rootDef, depth = 2, switchOn = true))
  }

  // ── §G2 配置开关：默认 false；热读 nebflow.json ────────────────────────────

  test("PingpongConfig.enabled defaults to false and hot-reads nebflow.json") {
    val prevRoot = PathUtil.dataRoot
    val tmp = os.temp.dir()
    try
      PathUtil.setDataRoot(tmp / "data")
      // 缺文件 / 缺键 ⇒ false（默认关——一期灰度主会话）
      assertEquals(PingpongConfig.enabled.unsafeRunSync(), false)
      os.write.over(
        tmp / "data" / "nebflow.json",
        Json.obj("other" -> Json.fromBoolean(true)).noSpaces,
        createFolders = true
      )
      assertEquals(PingpongConfig.enabled.unsafeRunSync(), false)
      // 置 true ⇒ 热读生效（无需重启）
      os.write.over(
        tmp / "data" / "nebflow.json",
        Json.obj("pingpong" -> Json.obj("enabled" -> Json.fromBoolean(true))).noSpaces
      )
      assertEquals(PingpongConfig.enabled.unsafeRunSync(), true)
      os.write.over(
        tmp / "data" / "nebflow.json",
        Json.obj("pingpong" -> Json.obj("enabled" -> Json.fromBoolean(false))).noSpaces
      )
      assertEquals(PingpongConfig.enabled.unsafeRunSync(), false)
    finally PathUtil.setDataRoot(prevRoot)
    end try
  }

  // ── 刀一① 影子腿摘要模型配置（spec §H2 预留位落地）：缺省主腿链，点名钉死 ──

  test("刀一①: PingpongConfig.shadowModel defaults to None (main-leg chain) and hot-reads nebflow.json") {
    val prevRoot = PathUtil.dataRoot
    val tmp = os.temp.dir()
    try
      PathUtil.setDataRoot(tmp / "data")
      // 缺文件 / 缺键 ⇒ None = 沿用主腿 agentDef.model 链（一期 §B3 行为逐字）
      assertEquals(PingpongConfig.shadowModel.unsafeRunSync(), None)
      os.write.over(
        tmp / "data" / "nebflow.json",
        Json.obj("pingpong" -> Json.obj("enabled" -> Json.fromBoolean(true))).noSpaces,
        createFolders = true
      )
      assertEquals(PingpongConfig.shadowModel.unsafeRunSync(), None)
      // 点名 ⇒ 热读生效（影子腿改走钉死单模型链，脱离主腿廉价回落链）
      os.write.over(
        tmp / "data" / "nebflow.json",
        Json
          .obj(
            "pingpong" -> Json
              .obj("enabled" -> Json.fromBoolean(true), "shadowModel" -> Json.fromString("zhipu/glm-5.3"))
          )
          .noSpaces
      )
      assertEquals(PingpongConfig.shadowModel.unsafeRunSync(), Some("zhipu/glm-5.3"))
      // 空白点名 ⇒ None（防手误：空白串不得生成空 preferred 的钉死链）
      os.write.over(
        tmp / "data" / "nebflow.json",
        Json.obj("pingpong" -> Json.obj("shadowModel" -> Json.fromString("   "))).noSpaces
      )
      assertEquals(PingpongConfig.shadowModel.unsafeRunSync(), None)
    finally PathUtil.setDataRoot(prevRoot)
    end try
  }

  // ── §C S 解析：pingpong 分支 ───────────────────────────────────────────────

  private val forkSeq = 42
  private val history: List[Message] = (0 until 10).map(i => userMsg(s"round $i")).toList

  test("parse pingpong: label carries mode/forkSeq/preservedRounds=0 (§C2)") {
    val outcome = FullCompact.parsePingpongResponse(summaryResponse("LEDGER"), history, forkSeq = forkSeq).toOption.get
    val text = outcome.messages.head.content.left.toOption.get
    assert(text.startsWith(s"""<context-compact mode="pingpong" forkSeq=$forkSeq preservedRounds=0>"""))
    // preservedRoundsOf 正则继续可读（§G3 向后兼容）
    assertEquals(FullCompact.preservedRoundsOf(outcome.messages), 0)
  }

  test("parse pingpong: NO preserved tail even with round starts in history (§C2)") {
    val outcome = FullCompact.parsePingpongResponse(summaryResponse("LEDGER"), history, forkSeq = forkSeq).toOption.get
    // 恒单条摘要消息 —— Δ 由换入结构本身接续，双重保留被禁止
    assertEquals(outcome.messages.size, 1)
    assertEquals(outcome.preservedRounds, 0)
  }

  test("parse pingpong: continuation prompt present; files section restores a real read file (§B1-5/§C1)") {
    // 真实存在的已读文件 ⇒ <files> 恢复段落盘进 S
    val root = os.temp.dir()
    os.write.over(root / "src" / "main" / "scala" / "A.scala", "object A // restored", createFolders = true)
    val outcome = FullCompact
      .parsePingpongResponse(
        summaryResponse("LEDGER"),
        history,
        projectRoot = root.toString,
        recentReadPaths = List("src/main/scala/A.scala"),
        forkSeq = forkSeq
      )
      .toOption
      .get
    val text = outcome.messages.head.content.left.toOption.get
    assert(text.contains("Resume directly"), "ContinuationPrompt 保留（消息形态与现行摘要同构）")
    assert(text.contains("A.scala") && text.contains("object A // restored"), "files 恢复段带上文件内容")
    assert(text.endsWith("</context-compact>"))
    // 不存在的文件 ⇒ 恢复段为空（readPaths 缺席时不注入空段）
    val outcome2 =
      FullCompact.parsePingpongResponse(summaryResponse("LEDGER"), history, root.toString, Nil, forkSeq).toOption.get
    val text2 = outcome2.messages.head.content.left.toOption.get
    assert(!text2.contains("Restored file contents after compaction"))
  }

  test("parse pingpong: missing </summary> is a Left (hard-cap truncation, §C3/§E-2)") {
    val truncated = "<analysis>draft</analysis>\n<summary>\npartial ledger without closing tag"
    val result = FullCompact.parsePingpongResponse(truncated, history, forkSeq = forkSeq)
    assert(result.isLeft, "truncated output must fail the parse, never leak raw text into S")
  }

  test("parse pingpong: summary over the 8192-token hard cap is a Left (§C3)") {
    // Latin 文本 4 chars/token ⇒ >32768 chars 即超 8192 tokens
    val huge = "a" * 40_000
    val result = FullCompact.parsePingpongResponse(summaryResponse(huge), history, forkSeq = forkSeq)
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("hard cap"))
  }

  test("parse pingpong: summary under the cap parses (boundary sanity)") {
    val small = "b" * 1000
    val result = FullCompact.parsePingpongResponse(summaryResponse(small), history, forkSeq = forkSeq)
    assert(result.isRight)
  }

  test("parse full mode unchanged: preserved tail still applies (old-path anchor)") {
    val outcome = FullCompact.parseResponseDetailed(summaryResponse("LEDGER"), history).toOption.get
    // 老路径：mode="full" + 尾部 N 轮 verbatim 保留（默认 2，受 char 预算约束）
    val text = outcome.messages.head.content.left.toOption.get
    assert(text.startsWith("<context-compact mode=\"full\" preservedRounds="))
    assert(outcome.preservedRounds >= 1, "full mode keeps tail-fidelity preservation")
    assert(outcome.messages.size > 1)
  }

  test("parse full mode unchanged: missing summary tags still fall back to raw text (legacy)") {
    val result = FullCompact.parseResponseDetailed("raw text no tags", history)
    assert(result.isRight, "legacy fallback behavior must stay byte-identical")
  }

  // ── §E-8 恢复幂等闸：标签 + forkSeq 匹配 ──────────────────────────────────

  test("isPingpongSummaryWithForkSeq: matches label+forkSeq only (idempotence guard)") {
    val outcome = FullCompact.parsePingpongResponse(summaryResponse("LEDGER"), history, forkSeq = forkSeq).toOption.get
    assert(FullCompact.isPingpongSummaryWithForkSeq(outcome.messages.head, forkSeq))
    assert(!FullCompact.isPingpongSummaryWithForkSeq(outcome.messages.head, forkSeq + 1))
    val fullOutcome = FullCompact.parseResponseDetailed(summaryResponse("LEDGER"), history).toOption.get
    assert(!FullCompact.isPingpongSummaryWithForkSeq(fullOutcome.messages.head, forkSeq))
    assert(!FullCompact.isPingpongSummaryWithForkSeq(userMsg("hello"), forkSeq))
  }

  // ── §D3 换入构建：Δ 零丢失 + §E-6 断言格 ──────────────────────────────────

  test("swap build: newList = S :: drop(forkMsgCount) — Δ preserved verbatim in place (§D3-①)") {
    val c0 = (0 until 6).map(i => userMsg(s"c0-$i")).toList
    val delta = (6 until 12).map(i => asstMsg(s"delta-$i")).toList
    val stateMsgs = c0 ++ delta
    val forkMsgCount = c0.size
    val sMessage = userMsg("<context-compact mode=\"pingpong\" forkSeq=6 preservedRounds=0>S</context-compact>")
    val newList = sMessage :: stateMsgs.drop(forkMsgCount)
    // 断言：S 打头；Δ 逐条原位（引用相等）；总长 = 1 + Δ
    assertEquals(newList.size, 1 + delta.size)
    assertEquals(newList.head, sMessage)
    newList.drop(1).zip(delta).foreach { case (a, b) =>
      assert(a eq b, "Δ messages must be structurally shared, not rebuilt")
    }
    // 幂等闸（§E-8）：崩溃后磁盘头部即本 forkSeq 的 pingpong 摘要 ⇒ 只清档不重换
    assert(FullCompact.isPingpongSummaryWithForkSeq(newList.head, 6))
  }

  test("swap invalid: forkMsgCount > size is the §E-6 state-disorder predicate") {
    val msgs = (0 until 4).map(i => userMsg(s"m$i")).toList
    val ready =
      PingpongSReady("ppc-s-9", List(userMsg("S")), forkMsgCount = 9, forkedAt = 0L, sTokensEst = 1, latencyMs = 0)
    assert(ready.forkMsgCount > msgs.size, "换入断言：违例 ⇒ 放弃换入走 §E-6，绝不截断历史")
  }

  // ── §A4/§E-1 状态机算术：排队位 / 退避 / 三连败回落 ────────────────────────

  private val emptyPingpong = PingpongState()

  test("pingpong state defaults: all bits empty (non-pingpong sessions are byte-neutral)") {
    assertEquals(emptyPingpong, PingpongState(None, None, false, 0, 0L))
    val st = AgentState(messages = Nil, status = nebflow.actor.AgentStatus.Idle)
    assertEquals(st.pingpong, emptyPingpong)
  }

  test("queued bit / cleared: withForkQueued and withPingpongCleared (§A4, §E-10)") {
    val st = AgentState(messages = Nil, status = nebflow.actor.AgentStatus.Idle)
      .withForkQueued(true)
      .withShadowPending(Some(PingpongShadowJob("ppc-x-3", 3, 7L)))
      .withSReadyPending(Some(PingpongSReady("ppc-x-3", List(userMsg("S")), 3, 7L, 10, 5L)))
      .withPpFailures(2, 9L)
    assert(st.pingpong.forkQueued)
    assert(st.pingpong.shadowPending.isDefined)
    // withPingpongCleared = 全清（换入完成 / ResetSession / 回落复位共用）
    val cleared = st.withPingpongCleared
    assertEquals(cleared.pingpong, emptyPingpong)
    // 旁车清位不触碰消息面
    assertEquals(st.messages, cleared.messages)
  }

  test("shadow backoff: 30s×2^n — within window blocked, past window re-fork (§E-1)") {
    val cfg = CompactConfig()
    assertEquals(cfg.backoffMs(1), 30000L)
    assertEquals(cfg.backoffMs(2), 60000L)
    assertEquals(cfg.backoffMs(3), 120000L)
    val failures1: Long = 10_000L // 失败后 10s
    val failures2: Long = 40_000L // 失败后 40s
    // 与触发点判定同式：ppFailures>0 && !isBackoffSatisfied ⇒ 本派发跳过 fork
    assert(!cfg.isBackoffSatisfied(1, failures1), "1 败后退避 30s 未满 ⇒ 不 fork")
    assert(cfg.isBackoffSatisfied(1, failures2), "退避窗满 ⇒ 重新 fork")
    assert(cfg.isBackoffSatisfied(0, 0L), "无失败 ⇒ 恒可 fork")
  }

  test("three strikes fall back to legacy sync once (§E-1, aligned circuitBreakerMax)") {
    val cfg = CompactConfig()
    val failures = 3
    assert(failures >= cfg.circuitBreakerMax, "三连败 ⇒ 本次触发回落老同步路径一拍")
    assert(failures - 1 < cfg.circuitBreakerMax, "两败仍走乒乓 fork")
  }

  // ── §E-1/刀一② fork 内重试：容忍窗放大（调用级）而熔断/退避参数不变 ────────

  test(
    "刀一②: send-beat retries amplify the tolerance window 3→9 call-level failures; circuitBreakerMax/backoff unchanged"
  ) {
    assertEquals(AgentPingpong.ShadowSendRetryBeats, 2, "fork 内跨供应商重试 2 拍")
    val cfg = CompactConfig()
    // 单次 send 内部已遍历整条候选链（跨供应商重试由链承担）；fork 内 1+2 拍全灭
    // 才计 1 次 ppFailures ⇒ 三败熔断前的调用级失败上限 = 3×3=9（原为 3）
    assertEquals((1 + AgentPingpong.ShadowSendRetryBeats) * cfg.circuitBreakerMax, 9)
    assertEquals(cfg.circuitBreakerMax, 3, "熔断参数不变（刀一②只放大 fork 内容忍）")
    assertEquals(cfg.backoffMs(1), 30000L, "30s×2ⁿ 退避不变")
  }

  // ── 刀一③ 解析截断续写拍：判据单点 + 拼接重解析 ────────────────────────────

  test("刀一③: isMissingSummaryPairError matches the real parse Left (and nothing else)") {
    val truncated = "<analysis>draft</analysis>\n<summary>\npartial ledger without closing tag"
    val result = FullCompact.parsePingpongResponse(truncated, history, forkSeq = forkSeq)
    assert(result.isLeft)
    assert(FullCompact.isMissingSummaryPairError(result.left.toOption.get), "missing-pair 类 = 续写拍适用面")
    // 反例：hard-cap 超限不是 missing-pair 类（内容性问题，不适用续写）
    val overCap = FullCompact.parsePingpongResponse(summaryResponse("a" * 40_000), history, forkSeq = forkSeq)
    assert(overCap.isLeft)
    assert(!FullCompact.isMissingSummaryPairError(overCap.left.toOption.get))
    // 反例：空响应
    assert(!FullCompact.isMissingSummaryPairError("Compact LLM returned empty response"))
  }

  test("刀一③: truncated partial + continuation beat composes into a parseable S (seam noise absorbed)") {
    val partial = "<analysis>draft</analysis>\n<summary>\npartial ledger without closing tag"
    val continuation = " and the rest of the ledger\n</summary>\n<files>\nsrc/A.scala\n</files>"
    // 影子腿拼接形态 = partial + "\n" + 续写产出，重走同一 pingpong 解析
    val result = FullCompact.parsePingpongResponse(s"$partial\n$continuation", history, forkSeq = forkSeq)
    val text = result.toOption.get.messages.head.content.left.toOption.get
    assert(text.contains("partial ledger without closing tag"), "首个 <summary> 对的正文 = 断点前内容")
    assert(text.contains("the rest of the ledger"), "续写段并入同一 S")
    assert(text.startsWith(s"""<context-compact mode="pingpong" forkSeq=$forkSeq preservedRounds=0>"""))
  }

  // ── 刀一④ fork 触发预置（×0.9）：纯函数算术钉 ─────────────────────────────

  test("刀一④: fork pre-position threshold = ceil(threshold × 0.9), strictly below threshold at realistic sizes") {
    assertEquals(PingpongConfig.ForkPrePositionRatio, 0.9)
    assertEquals(PingpongConfig.forkPrePositionThreshold(256000), 230400, "今天实测量级：256k 阈值 → 230.4k 预 fork")
    assertEquals(PingpongConfig.forkPrePositionThreshold(1000), 900)
    assertEquals(PingpongConfig.forkPrePositionThreshold(10), 9)
    assertEquals(PingpongConfig.forkPrePositionThreshold(9), 9, "极小阈值退化为原阈值（ceil 保守，不超前触发）")
    List(10, 1000, 100000, 256000, 1000000).foreach { t =>
      val p = PingpongConfig.forkPrePositionThreshold(t)
      assert(p < t && p > t / 2, s"预置线必须落在 (t/2, t) 内: t=$t p=$p")
    }
  }

  // ── 刀二 彻底零痕迹：emergency 安全阀帧契约（完成卡唯一可见例外）──────────

  test("刀二: compactComplete frame carries the emergency flag (safety-valve contract)") {
    import nebflow.actor.AgentStreamEvent
    val normal = AgentStreamEvent.CompactComplete(359, 4).toJson("neb", isSubagent = false, sessionId = Some("s1"))
    assertEquals(normal.hcursor.get[String]("type").toOption, Some("compactComplete"))
    assertEquals(normal.hcursor.get[Boolean]("emergency").toOption, Some(false), "常规回落完成帧 emergency=false（前端停渲染）")
    assertEquals(normal.hcursor.get[Int]("before").toOption, Some(359))
    assertEquals(normal.hcursor.get[Int]("after").toOption, Some(4))
    val emergency = AgentStreamEvent
      .CompactComplete(359, 4, emergency = true)
      .toJson("neb", isSubagent = false, sessionId = Some("s1"))
    assertEquals(emergency.hcursor.get[Boolean]("emergency").toOption, Some(true), "0.95 硬守卫帧 emergency=true（安全阀保留渲染）")
  }

  test("PingpongStateFile: roundtrip compacting / s-ready / clear / corrupt (§E-7/8/9)") {
    val prevRoot = PathUtil.dataRoot
    val tmp = os.temp.dir()
    try
      PathUtil.setDataRoot(tmp / "data")
      val sid = "nebula-pp-spec"
      // §E-9：无 state.json ⇒ None（正常启动语义）
      assertEquals(PingpongStateFile.load(sid).unsafeRunSync(), None)
      // §E-7 格：compacting 在途
      val compacting = PingpongStateFile.Record(PingpongStateFile.Compacting, "ppc-sid-6", 6, 123L, None, 0, 0)
      PingpongStateFile.save(sid, compacting).unsafeRunSync()
      assertEquals(PingpongStateFile.load(sid).unsafeRunSync(), Some(compacting))
      // §E-8 格：s-ready 带 S 文本（续用换入的依据）
      val sReady = PingpongStateFile.Record(
        PingpongStateFile.SReady,
        "ppc-sid-6",
        6,
        123L,
        Some("<summary>LEDGER</summary>"),
        1200,
        1
      )
      PingpongStateFile.save(sid, sReady).unsafeRunSync()
      assertEquals(PingpongStateFile.load(sid).unsafeRunSync(), Some(sReady))
      // S 消息重建（不重算）
      val rebuilt = PingpongStateFile.sMessageOf(sReady)
      assert(rebuilt.isDefined)
      assertEquals(rebuilt.get.role, MessageRole.User)
      assertEquals(rebuilt.get.content.left.toOption.get, "<summary>LEDGER</summary>")
      // 清档 ⇒ None（换入完成 / 作废共用的终态）
      PingpongStateFile.clear(sid).unsafeRunSync()
      assertEquals(PingpongStateFile.load(sid).unsafeRunSync(), None)
      // 损坏文件 ⇒ None（不抛、不误恢复）
      val f = tmp / "data" / "sessions" / sid / "pingpong" / "state.json"
      os.write.over(f, "{not json")
      assertEquals(PingpongStateFile.load(sid).unsafeRunSync(), None)
      // 未知 phase ⇒ None（旧二进制前向兼容丢弃）
      os.write.over(f, """{"phase":"future","shadowId":"x","forkMsgCount":1,"forkedAt":1}""")
      assertEquals(PingpongStateFile.load(sid).unsafeRunSync(), None)
    finally PathUtil.setDataRoot(prevRoot)
    end try
  }

  test("SessionStore.durableSwapMessages: pending-map coherence + durable write (§D3-②)") {
    import nebflow.core.SessionStore
    val prevRoot = PathUtil.dataRoot
    val tmp = os.temp.dir()
    try
      PathUtil.setDataRoot(tmp / "data")
      val store = SessionStore(tmp / "sessions", tmp / "tasks")
      val sid = "nebula-swap-spec"
      val c0 = (0 until 3).map(i => userMsg(s"c0-$i")).toList
      val delta = List(asstMsg("d1"), asstMsg("d2"))
      // 常规轮末存盘（debounce 在队）——模拟换入前的在队旧列表
      store.saveMessagesForSession(sid, c0 ++ delta).unsafeRunSync()
      // 换入：S + Δ durable 直写（撤销在队 debounce 项）
      val sMessage = userMsg("""<context-compact mode="pingpong" forkSeq=3 preservedRounds=0>S</context-compact>""")
      val swapped = sMessage :: (c0 ++ delta).drop(3)
      store.durableSwapMessages(sid, swapped).unsafeRunSync()
      // 读面（pending map + 磁盘两路）：见新绝无半态
      val loaded = store.loadMessagesForSession(sid).unsafeRunSync()
      assertEquals(loaded, swapped)
      // 磁盘真身：durable 写已落盘（不依赖 debounce 定时器）——store 的会话目录
      // = 构造参 sessionsDir（tmp/sessions），与 dataRoot 无关
      val disk = io.circe.parser.decode[List[Message]](os.read(tmp / "sessions" / s"$sid.json")).toOption.get
      assertEquals(disk, swapped)
    finally PathUtil.setDataRoot(prevRoot)
    end try
  }
end PingpongCompactSpec
