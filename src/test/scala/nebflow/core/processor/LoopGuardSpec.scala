package nebflow.core.processor

import munit.FunSuite
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.Counters

/**
 * Block 3 循环检测器纯函数测试（supervision trio §D 2026-08-27；简化重构
 * 2026-08-30 作者拍板：S2 进展判定/轮预算删除，新增 R 精确重复检测）。
 *
 * 信号矩阵 × 处置阶梯 × 豁免矩阵。挂载点（AgentCore.pipeToolExecutions）
 * 的行为验证在 wiring 级 spec（LoopGuardWiringSpec：L0 提醒真的进下一轮
 * 请求消息）；本文件只测判定核心。
 *
 * 2026-11-02「循环检测误杀整批任务」批新增三组钉子：
 *  ① warn-before-terminate 阶梯（首次触阈只 Warn，第二次才终止/冻结）
 *  ② 暂时性故障区分（超时/网络抖动类阈放大，但终止仍有界）
 *  ③ 配置逐键兜底 + Warn 文案带工具名（不是 fp 哈希）
 */
class LoopGuardSpec extends FunSuite:

  private val cfg = LoopGuard.Config.Default // S1: 3/8, R: 5/10, R-text: 5/10, S3: 3

  private def failed(
    tool: String,
    args: Json = Json.obj(),
    err: String = "boom",
    denied: Boolean = false
  ): LoopGuard.RoundEvent =
    LoopGuard.RoundEvent(tool, args, isError = true, errorText = err, permissionDenied = denied)

  private def okEv(tool: String, args: Json = Json.obj()): LoopGuard.RoundEvent =
    LoopGuard.RoundEvent(tool, args, isError = false, errorText = "", permissionDenied = false)

  private def args(path: String): Json = Json.obj("path" -> path.asJson)

  /** 重复跑同一事件 n 轮，返回最终计数器与最终裁决。 */
  private def repeat(
    evs: List[LoopGuard.RoundEvent],
    n: Int,
    start: Counters = Counters.Empty,
    turn: String = "t1",
    c: LoopGuard.Config = cfg,
    text: String = ""
  ): (Counters, LoopGuard.Verdict) =
    (1 to n).foldLeft((start, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) { case ((cnt, _), _) =>
      LoopGuard.evaluate(evs, turn, cnt, c, text)
    }

  // ── S1 同参同败（turn 内） ────────────────────────────────

  test("S1: identical failure streak warns exactly at soft threshold (3)") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c1, v1) = LoopGuard.evaluate(List(e), "t1", Counters.Empty, cfg)
    assertEquals(v1, LoopGuard.Verdict.Pass)
    val (c2, v2) = LoopGuard.evaluate(List(e), "t1", c1, cfg)
    assertEquals(v2, LoopGuard.Verdict.Pass)
    val (c3, v3) = LoopGuard.evaluate(List(e), "t1", c2, cfg)
    v3 match
      case LoopGuard.Verdict.Warn(msg, _) => assert(msg.contains("failed 3 times"))
      case other => fail(s"expected Warn, got $other")
    assertEquals(c3.streakCount, 3)
  }

  test("S1: streak terminates at hard threshold (8) — the soft warn already granted grace") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (_, v) = repeat(List(e), 8)
    v match
      case LoopGuard.Verdict.Terminate(msg, fp) =>
        assert(msg.contains("failed 8 times"))
        assert(fp.nonEmpty)
      case other => fail(s"expected Terminate, got $other")
  }

  test("S1: success of the same call resets the streak (flaky fail-fail-ok never trips)") {
    // 隔离 S1 轴（关掉 R-call 软阈）：本用例只问「成功是否清零 streak」
    val iso = cfg.copy(identicalCallSoft = 99)
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c1, _) = LoopGuard.evaluate(List(e), "t1", Counters.Empty, iso)
    val (c2, _) = LoopGuard.evaluate(List(e), "t1", c1, iso)
    val (c3, v3) = LoopGuard.evaluate(List(okEv("Read", args("/x"))), "t1", c2, iso)
    assertEquals(v3, LoopGuard.Verdict.Pass)
    assertEquals(c3.streakCount, 0)
    // 再连败 2 次也不会撞 soft（被成功清零）
    val (c4, v4) = LoopGuard.evaluate(List(e), "t1", c3, iso)
    val (_, v5) = LoopGuard.evaluate(List(e), "t1", c4, iso)
    assertEquals(v4, LoopGuard.Verdict.Pass)
    assertEquals(v5, LoopGuard.Verdict.Pass)
  }

  test("S1: different arguments are different fingerprints — alternating failures never accumulate") {
    val e1 = failed("Read", args("/a"), "File does not exist: /a")
    val e2 = failed("Read", args("/b"), "File does not exist: /b")
    val (_, v) = (1 to 10).foldLeft((Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) { case ((cnt, _), i) =>
      LoopGuard.evaluate(List(if i % 2 == 0 then e1 else e2), "t1", cnt, cfg)
    }
    // streak 每轮被换参打断，永不 ≥2
    assertEquals(v, LoopGuard.Verdict.Pass)
  }

  test("S1: varying error signature (timeout 30s vs 31s) does not accumulate — flaky exemption") {
    val e30 = failed("Bash", Json.obj("cmd" -> "build".asJson), "command timed out after 30s")
    val e31 = failed("Bash", Json.obj("cmd" -> "build".asJson), "command timed out after 31s")
    val (c1, v1) = LoopGuard.evaluate(List(e30), "t1", Counters.Empty, cfg)
    assertEquals(v1, LoopGuard.Verdict.Pass)
    val (_, v2) = LoopGuard.evaluate(List(e31), "t1", c1, cfg)
    assertEquals(v2, LoopGuard.Verdict.Pass)
    assertNotEquals(LoopGuard.errorSignature("timeout after 30s"), LoopGuard.errorSignature("timeout after 31s"))
  }

  test("S1: fingerprint is key-order insensitive (same semantic call = same fp)") {
    val a = Json.obj("path" -> "/x".asJson, "offset" -> 0.asJson)
    val b = Json.obj("offset" -> 0.asJson, "path" -> "/x".asJson)
    assertEquals(LoopGuard.fingerprint("Read", a), LoopGuard.fingerprint("Read", b))
  }

  // ── R 精确重复检测（作者方案 2026-08-30，替代 S2） ─────────

  test("R-call: same tool+args consecutively x9 passes, exactly the 10th terminates") {
    val e = okEv("Bash", Json.obj("command" -> "sbt testOnly SuiteA".asJson))
    val (c9, v9) = repeat(List(e), 9)
    assertEquals(v9, LoopGuard.Verdict.Pass)
    assertEquals(c9.lastCallCount, 9)
    val (c10, v10) = LoopGuard.evaluate(List(e), "t1", c9, cfg)
    v10 match
      case LoopGuard.Verdict.Terminate(msg, fp) =>
        assert(msg.contains("10 times in a row"))
        assert(msg.contains("identical arguments"))
        assert(
          fp == LoopGuard.fingerprint("Bash", e.args),
          "Terminate must record the call fp (recurrence → Freeze chain)"
        )
        assert(c10.terminatedFps.contains(fp))
      case other => fail(s"expected Terminate at exactly the 10th, got $other")
  }

  test("R-call: the call axis now HAS a warning stage (previous kill was unannounced)") {
    // 2026-11-02 事故轴：撞死 Mashiro 的是 R-call hard(10)，而 warn 只在 S1 轴。
    // 修法钉子：第 identicalCallSoft(5) 次必须发出一条 Warn（不终止，turn 存活）。
    val e = okEv("Bash", Json.obj("command" -> "make -j8".asJson))
    val (c4, v4) = repeat(List(e), 4)
    assertEquals(v4, LoopGuard.Verdict.Pass, "below the call-soft threshold nothing fires")
    val (c5, v5) = LoopGuard.evaluate(List(e), "t1", c4, cfg)
    v5 match
      case LoopGuard.Verdict.Warn(msg, key) =>
        assert(msg.contains("called 5 times"), s"warn must state the count: $msg")
        assert(msg.contains("Bash"), s"warn must name the TOOL, not the fp hash: $msg")
        assert(key.nonEmpty, "warn must carry the grace key")
      case other => fail(s"expected Warn at the call-soft threshold, got $other")
    assertEquals(c5.lastCallCount, 5)
  }

  test("R-call: same args failing x10 still caught (S1 fires earlier at 8 — precedence check)") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    // S1 在第 8 次先触发（8 < 10）
    val (c8, v8) = repeat(List(e), 8)
    v8 match
      case LoopGuard.Verdict.Terminate(msg, _) =>
        assert(msg.contains("failed 8 times"), "S1 (8) must fire before R-call (10)")
      case other => fail(s"expected S1 Terminate at 8, got $other")
    // 第 9/10 次：同 fp 再败 → recurrence → Freeze（更强裁决，设计内）
    val (_, v10) = (1 to 2).foldLeft((c8, v8: LoopGuard.Verdict)) { case ((c, _), _) =>
      LoopGuard.evaluate(List(e), "t1", c, cfg)
    }
    assert(v10.isInstanceOf[LoopGuard.Verdict.Freeze], s"expected Freeze on recurrence, got $v10")
  }

  test("R-call: interruption by ANY different call resets the counter (author ruling 5: A A A B A A A)") {
    val a = okEv("Bash", Json.obj("command" -> "same".asJson))
    val b = okEv("Bash", Json.obj("command" -> "different".asJson))
    // 9×A
    val (c9, _) = repeat(List(a), 9)
    assertEquals(c9.lastCallCount, 9)
    // 插 1 次 B → A 计数必须从 1 重数
    val (cAfterB, _) = LoopGuard.evaluate(List(b), "t1", c9, cfg)
    val (cA1, _) = LoopGuard.evaluate(List(a), "t1", cAfterB, cfg)
    assertEquals(
      cA1.lastCallCount,
      1,
      "interruption must reset the consecutive counter (no cross-interruption accumulation)"
    )
    // 再续 8×A 仍不触发（凑不齐新的一段 10 连）
    val (c8, v8) = repeat(List(a), 8, start = cA1)
    assertEquals(v8, LoopGuard.Verdict.Pass)
    assertEquals(c8.lastCallCount, 9)
  }

  test("R-call: success does NOT reset — identical successful calls x10 still a loop") {
    val e = okEv("Read", args("/logs/fulltest.log"))
    val (_, v) = repeat(List(e), 10)
    v match
      case LoopGuard.Verdict.Terminate(msg, _) => assert(msg.contains("in a row"))
      case other => fail(s"expected Terminate, got $other")
  }

  test("R-call: verification-style work (varying args) never triggers — mutation-red target (a)") {
    // 08-28 事故场景：sbt test → 读不同日志行 → grep 不同模式——参数各异永不连击
    val rounds = (1 to 25).map { i =>
      if i % 3 == 0 then List(okEv("Bash", Json.obj("command" -> s"sbt testOnly Suite${i % 5}".asJson)))
      else if i % 3 == 1 then List(okEv("Read", args(s"/logs/fulltest.log#L$i")))
      else List(okEv("Grep", Json.obj("pattern" -> s"case-$i".asJson, "path" -> "/logs".asJson)))
    }
    val (_, v) = rounds.foldLeft((Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) { case ((c, _), evs) =>
      LoopGuard.evaluate(evs.toList, "t1", c, cfg)
    }
    assertEquals(v, LoopGuard.Verdict.Pass)
  }

  test("R-call: multi-event rounds (A,B) interleaved keep both counts at 1") {
    val a = okEv("Read", args("/a"))
    val b = okEv("Grep", args("pat"))
    val (_, v) = (1 to 15).foldLeft((Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) { case ((c, _), _) =>
      LoopGuard.evaluate(List(a, b), "t1", c, cfg)
    }
    assertEquals(v, LoopGuard.Verdict.Pass, "alternating A,B never reaches 10 consecutive")
  }

  test("R-text: identical assistant text x9 passes, exactly the 10th terminates (mutation-red target d)") {
    val (c9, v9) = repeat(Nil, 9, text = "我已经重试了，请稍候。")
    assertEquals(v9, LoopGuard.Verdict.Pass)
    assertEquals(c9.lastTextCount, 9)
    val (_, v10) = LoopGuard.evaluate(Nil, "t1", c9, cfg, assistantText = "我已经重试了，请稍候。")
    v10 match
      case LoopGuard.Verdict.Terminate(msg, fp) =>
        assert(msg.contains("identical text output"))
        assert(msg.contains("10 rounds"))
        assertEquals(fp, "", "text terminate has no call fp (not recorded in terminatedFps)")
      case other => fail(s"expected Terminate at the 10th identical text, got $other")
  }

  test("R-text: text axis warns at its soft threshold before it terminates") {
    val same = "我继续尝试。"
    val (_, v5) = repeat(Nil, 5, text = same)
    v5 match
      case LoopGuard.Verdict.Warn(msg, key) =>
        assert(msg.contains("identical text output"))
        assert(key.startsWith("text:"), s"text grace key must be namespaced, got '$key'")
      case other => fail(s"expected Warn at text-soft (5), got $other")
  }

  test("R-text: any different text resets; empty text neither counts nor resets") {
    // 隔离文本轴（关掉 R-call 软阈，避免同参工具轮自己触发警告）
    val iso = cfg.copy(identicalCallSoft = 99)
    val same = "好的，我继续。"
    val (c5, _) = repeat(Nil, 5, c = iso, text = same)
    assertEquals(c5.lastTextCount, 5)
    // 不同文本 → 刷新
    val (cReset, _) = LoopGuard.evaluate(Nil, "t1", c5, iso, assistantText = "换个说法。")
    assertEquals(cReset.lastTextCount, 1)
    // 空文本轮（纯工具轮）→ 不计也不刷新
    val (cEmpty, _) =
      LoopGuard.evaluate(List(okEv("Bash", Json.obj("command" -> "x".asJson))), "t1", cReset, iso, assistantText = "")
    assertEquals(cEmpty.lastTextCount, 1, "empty text must not reset the streak")
    // 续 4 次同文本 = 5 连（<10 不触发，且软阈已消耗 → 无新警告）
    val (_, v) = repeat(List(okEv("Bash", Json.obj("command" -> "x".asJson))), 4, start = cEmpty, c = iso, text = same)
    assertEquals(v, LoopGuard.Verdict.Pass)
  }

  // ── S3 跨 turn 同调用 ─────────────────────────────────────

  test("S3: 3 separate turns now WARN first (grace) and freeze on the 4th — 误杀修复") {
    // 2026-11-02 事故形态（案例 1）：每 turn 只败一次，S1 软阈永不触发 ⇒ 旧实现
    // 在第 3 个 turn 直接 Freeze（冻结 = 会话等人工，比终止更狠，且零提示）。
    // 修后：第 3 个 turn 发 Warn（模型看见"再失败一个 turn 就冻结"），第 4 个才冻。
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c1, v1) = LoopGuard.evaluate(List(e), "t1", Counters.Empty, cfg)
    assertEquals(v1, LoopGuard.Verdict.Pass)
    val (c2, v2) = LoopGuard.evaluate(List(e), "t2", c1, cfg) // turn 边界：S1/R 清零，S3 保留
    assertEquals(v2, LoopGuard.Verdict.Pass)
    assertEquals(c2.streakCount, 1) // S1 已被 turn 边界重置
    val (c3, v3) = LoopGuard.evaluate(List(e), "t3", c2, cfg)
    v3 match
      case LoopGuard.Verdict.Warn(msg, key) =>
        assert(msg.contains("3 separate turns"), s"warn must state the turn count: $msg")
        assert(msg.contains("Read"), s"warn must name the tool: $msg")
        assert(key.nonEmpty)
      case other => fail(s"expected Warn at the 3rd failing turn (grace), got $other")
    val (_, v4) = LoopGuard.evaluate(List(e), "t4", c3, cfg)
    v4 match
      case LoopGuard.Verdict.Freeze(msg) => assert(msg.contains("separate turns"))
      case other => fail(s"expected Freeze on the next failing turn, got $other")
  }

  test("S3: a success of the same fp in between clears the cross-turn record") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c1, _) = LoopGuard.evaluate(List(e), "t1", Counters.Empty, cfg)
    val (c2, _) = LoopGuard.evaluate(List(okEv("Read", args("/x"))), "t2", c1, cfg)
    assertEquals(c2.crossTurn.get(LoopGuard.fingerprint("Read", args("/x"))), None)
    val (_, v3) = LoopGuard.evaluate(List(e), "t3", c2, cfg)
    assertEquals(v3, LoopGuard.Verdict.Pass) // 只 1 个 turn 记录
  }

  test("L1 recurrence: fp terminated earlier then failing again in a later turn freezes immediately") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    // 第一 turn：8 连败 → Terminate（fp 记入 terminatedFps）
    val (ct1, vt1) = repeat(List(e), 8, turn = "t1")
    vt1 match
      case LoopGuard.Verdict.Terminate(_, fp) => assert(ct1.terminatedFps.contains(fp))
      case other => fail(s"expected Terminate, got $other")
    // 新 turn 同 fp 再败 → 直接 Freeze（不再给第二次 L1；grace 已在终止前消耗）
    val (_, v2) = LoopGuard.evaluate(List(e), "t2", ct1, cfg)
    v2 match
      case LoopGuard.Verdict.Freeze(msg) => assert(msg.contains("terminated earlier"))
      case other => fail(s"expected Freeze on recurrence, got $other")
  }

  // ── warn-before-terminate 阶梯（2026-11-02 批 ①） ──────────

  test("ladder: first touching the hard threshold only WARNS; the next round terminates") {
    // 关掉两条软阈（S1 soft=99 / R-call soft=99）以隔离 hard 档：模型从未被
    // 警告过这个指纹 ⇒ hard 不得直接杀。
    val c = cfg.copy(identicalFailureSoft = 99, identicalCallSoft = 99)
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c7, v7) = repeat(List(e), 7, c = c)
    assertEquals(v7, LoopGuard.Verdict.Pass)
    val (c8, v8) = LoopGuard.evaluate(List(e), "t1", c7, c)
    v8 match
      case LoopGuard.Verdict.Warn(msg, key) =>
        assert(msg.contains("failed 8 times"), s"grace warn must state the count: $msg")
        assert(msg.contains("one more identical failure terminates"), s"grace warn must state the consequence: $msg")
        assertEquals(key, c8.streakFp)
      case other => fail(s"expected Warn at hard without a prior grace, got $other")
    // 模型没纠正 → 下一轮才终止
    val (_, v9) = LoopGuard.evaluate(List(e), "t1", c8, c)
    v9 match
      case LoopGuard.Verdict.Terminate(msg, _) => assert(msg.contains("turn terminated"))
      case other => fail(s"expected Terminate after the grace round, got $other")
  }

  test("ladder: requireWarnBeforeTerminate=false restores the immediate kill (rollback switch)") {
    val c = cfg.copy(identicalFailureSoft = 99, identicalCallSoft = 99, requireWarnBeforeTerminate = false)
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (_, v8) = repeat(List(e), 8, c = c)
    assert(v8.isInstanceOf[LoopGuard.Verdict.Terminate], s"expected immediate Terminate, got $v8")
  }

  test("ladder: identicalCallHard is never eternal — no warn, warn, then terminate") {
    val c = cfg.copy(identicalCallSoft = 99) // 软阈关闭，硬阈必须自己先发警告
    val e = okEv("Bash", Json.obj("command" -> "watch".asJson))
    val (c9, v9) = repeat(List(e), 9, c = c)
    assertEquals(v9, LoopGuard.Verdict.Pass)
    val (c10, v10) = LoopGuard.evaluate(List(e), "t1", c9, c)
    assert(v10.isInstanceOf[LoopGuard.Verdict.Warn], s"10th must warn first, got $v10")
    val (_, v11) = LoopGuard.evaluate(List(e), "t1", c10, c)
    assert(v11.isInstanceOf[LoopGuard.Verdict.Terminate], s"11th must terminate, got $v11")
  }

  // ── 暂时性故障区分（2026-11-02 批 ③） ─────────────────────

  test("transient: timeout/network errors classify as transient, deterministic ones never do") {
    val p = cfg.transientPatterns
    assert(LoopGuard.isTransientError("command timed out after 30s", p))
    assert(LoopGuard.isTransientError("curl: (7) Failed to connect: Connection refused", p))
    assert(LoopGuard.isTransientError("HTTP 503 Service Unavailable", p))
    assert(LoopGuard.isTransientError("upstream request timeout", p))
    assert(!LoopGuard.isTransientError("File does not exist: /x", p))
    assert(!LoopGuard.isTransientError("compilation failed: syntax error at line 3", p))
    assert(!LoopGuard.isTransientError("Tool Read is not allowed by the permission policy", p))
    assert(
      !LoopGuard.isTransientError("File does not exist: /docs/503.md", p),
      "a path containing 503 is not an HTTP status"
    )
  }

  test("transient: a timeout streak does not terminate at 8 — the bound scales by transientFactor") {
    val e = failed("Bash", Json.obj("cmd" -> "build".asJson), "command timed out after 30s")
    val (_, v8) = repeat(List(e), 8)
    assert(v8 == LoopGuard.Verdict.Pass, s"transient streak must survive 8 (hard×3=24), got $v8")
    val (c23, v23) = repeat(List(e), 23)
    assert(
      !v23.isInstanceOf[LoopGuard.Verdict.Terminate],
      s"23 identical transient failures must not be terminal yet, got $v23"
    )
    val (_, v24) = LoopGuard.evaluate(List(e), "t1", c23, cfg)
    assert(v24.isInstanceOf[LoopGuard.Verdict.Terminate], s"24 must terminate (bounded, not eternal), got $v24")
  }

  test("transient: identical-args timeout retries are not killed at R-call 10 (scaled to 30)") {
    // 同时抬高 S1 阈以隔离 R-call 轴
    val c = cfg.copy(identicalFailureSoft = 99, identicalFailureHard = 999, identicalCallSoft = 5)
    val e = failed("Bash", Json.obj("cmd" -> "deploy".asJson), "connection reset by peer")
    val (_, v10) = repeat(List(e), 10, c = c)
    assert(!v10.isInstanceOf[LoopGuard.Verdict.Terminate], s"10 transient retries must not terminate, got $v10")
    val (c29, _) = repeat(List(e), 29, c = c)
    val (_, v30) = LoopGuard.evaluate(List(e), "t1", c29, c)
    assert(v30.isInstanceOf[LoopGuard.Verdict.Terminate], s"30 (10×3) is the hard bound, got $v30")
  }

  test("transient: one deterministic failure of the same fp removes the transient scaling") {
    val fp = LoopGuard.fingerprint("Bash", Json.obj("cmd" -> "build".asJson))
    val t = failed("Bash", Json.obj("cmd" -> "build".asJson), "command timed out after 30s")
    val d = failed("Bash", Json.obj("cmd" -> "build".asJson), "No such file or directory: build.sbt")
    val (c1, _) = LoopGuard.evaluate(List(t), "t1", Counters.Empty, cfg)
    assert(c1.transientFps.contains(fp), "a transient failure marks the fp as transient")
    assert(c1.streakTransient)
    val (c2, _) = LoopGuard.evaluate(List(d), "t2", c1, cfg)
    assert(!c2.transientFps.contains(fp), "a deterministic failure disqualifies the transient scaling")
    assert(!c2.streakTransient, "the streak is no longer transient-only")
  }

  test("transient: transientAware=false keeps the old (aggressive) threshold") {
    val c = cfg.copy(transientAware = false, identicalFailureSoft = 99, identicalCallSoft = 99)
    val e = failed("Bash", Json.obj("cmd" -> "build".asJson), "command timed out after 30s")
    val (c7, _) = repeat(List(e), 7, c = c)
    val (c8, v8) = LoopGuard.evaluate(List(e), "t1", c7, c)
    // 先给 grace（无软阈），阈未被放大：8 就是 8
    assert(v8.isInstanceOf[LoopGuard.Verdict.Warn], s"8th must hit hard (warn grace) with awareness off, got $v8")
    val (_, v9) = LoopGuard.evaluate(List(e), "t1", c8, c)
    assert(v9.isInstanceOf[LoopGuard.Verdict.Terminate], s"9th must terminate, got $v9")
  }

  // ── 配置 ─────────────────────────────────────────────────

  test("config: each key falls back to its own default (regression: whole-config fallback)") {
    // 旧实现：任一键缺失 → 整份配置静默回落 Default（"阈值可配置"名存实亡）
    val partial = io.circe.parser
      .parse("""{"supervision":{"loopGuard":{"identicalCallHard":20}}}""")
      .toOption
      .get
    val c = LoopGuard.parseConfig(partial).get
    assertEquals(c.identicalCallHard, 20, "the single provided key must take effect")
    assertEquals(c.identicalFailureHard, LoopGuard.Config.Default.identicalFailureHard, "missing keys keep defaults")
    assertEquals(c.crossTurnFailureTurns, LoopGuard.Config.Default.crossTurnFailureTurns)
    assertEquals(c.requireWarnBeforeTerminate, true)
    assertEquals(c.transientAware, true)
    assertEquals(c.exemptTools, LoopGuard.Config.Default.exemptTools)
  }

  test("config: absent loopGuard section → None; transientFactor clamped to >= 1") {
    assertEquals(LoopGuard.parseConfig(Json.obj("supervision" -> Json.obj())), None)
    val j = io.circe.parser.parse("""{"supervision":{"loopGuard":{"transientFactor":0}}}""").toOption.get
    assertEquals(LoopGuard.parseConfig(j).get.transientFactor, 1)
  }

  // ── 豁免矩阵 ─────────────────────────────────────────────

  test("exemptTools: TaskQuery polling never counts toward S1/S3/R-call (polling exemption)") {
    val q = failed("TaskQuery", Json.obj("status" -> "in_progress".asJson), "no tasks")
    // 同参 20 次（跨 3 turn）——S1/S3/R 全不触发
    val (cnt, v) = (1 to 20).foldLeft((Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) { case ((c, _), i) =>
      LoopGuard.evaluate(List(q), s"t${(i - 1) / 7 + 1}", c, cfg)
    }
    assertEquals(v, LoopGuard.Verdict.Pass)
    assertEquals(cnt.streakCount, 0)
    assertEquals(cnt.lastCallCount, 0, "exempt tools must not feed the R-call counter")
  }

  test("permissionDenied: policy denials are not loop signals (#12 independent governance)") {
    val d = failed(
      "Bash",
      Json.obj("cmd" -> "rm -rf /".asJson),
      "Tool Bash is denied by the session permission policy",
      denied = true
    )
    val (cnt, v) = repeat(List(d), 10)
    assertEquals(v, LoopGuard.Verdict.Pass)
    assertEquals(cnt.streakCount, 0)
    assertEquals(cnt.lastCallCount, 0)
  }

  test("disabled config: everything passes and counters are untouched") {
    val off = cfg.copy(enabled = false)
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (cnt, v) = repeat(List(e), 10, c = off)
    assertEquals(v, LoopGuard.Verdict.Pass)
    assertEquals(cnt, Counters.Empty)
  }

  test("turn boundary resets S1 AND R consecutive counters but preserves S3 and the grace ledger") {
    val e = failed("Read", args("/x"), "File does not exist: /x") // 同一 fp 同时喂 S1 与 R-call
    val (c1, _) = repeat(List(e), 5)
    assertEquals(c1.streakCount, 5)
    assertEquals(c1.lastCallCount, 5)
    assertEquals(c1.lastTextCount, 0)
    assert(c1.warnedKeys.contains(LoopGuard.fingerprint("Read", args("/x"))), "soft warn (3) consumed the grace")
    val (c2, v2) = LoopGuard.evaluate(List(e), "t2", c1, cfg, assistantText = "same line")
    assertEquals(v2, LoopGuard.Verdict.Pass)
    assertEquals(c2.streakCount, 1, "S1 reset by turn boundary")
    assertEquals(c2.lastCallCount, 1, "R-call reset by turn boundary")
    assertEquals(c2.lastTextCount, 1, "R-text starts fresh in the new turn")
    assert(c2.crossTurn.nonEmpty, "S3 cross-turn records preserved")
    assert(c2.warnedKeys.nonEmpty, "grace ledger is session-scoped (not a turn counter)")
  }

  test("verdict strength: Freeze beats Terminate beats Warn when simultaneous") {
    // 构造：S3 命中（3 turns）的同时 streak 达 hard——Freeze 必须赢
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c2, _) = repeat(List(e), 2, turn = "t1")
    // t2 凑第二个 turn 记录 + streak 7 次（此间 S1 软阈已发过 Warn = grace 已消耗）
    val (c2b, _) = repeat(List(e), 7, start = c2, turn = "t2")
    // t3 第一败：crossTurn 3 turns + streak 8 同时成立 → Freeze
    val (_, v3) = LoopGuard.evaluate(List(e), "t3", c2b, cfg)
    v3 match
      case LoopGuard.Verdict.Freeze(_) => // expected — strongest wins
      case other => fail(s"expected Freeze (strongest), got $other")
  }

  test("R1 resetCrossTurn: 唤醒清零观察窗后同 fp 再失败 1 次不冻；terminatedFps/grace 不受影响") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val fp = LoopGuard.fingerprint("Read", args("/x"))
    // 唤醒前：t1/t2 已各记 1 次（未达 3 turn）
    val (c1, _) = LoopGuard.evaluate(List(e), "t1", Counters.Empty, cfg)
    val (c2, _) = LoopGuard.evaluate(List(e), "t2", c1, cfg)
    assertEquals(c2.crossTurn.get(fp).map(_.size), Some(2))
    // 对照（未修原状）：不重置 → 唤醒 turn t3 第 1 败即 3 turns → 触阈
    val (c3raw, vRaw) = LoopGuard.evaluate(List(e), "t3", c2, cfg)
    assert(
      vRaw.isInstanceOf[LoopGuard.Verdict.Warn] || vRaw.isInstanceOf[LoopGuard.Verdict.Freeze],
      s"threshold hit expected (warn grace or freeze), got $vRaw"
    )
    // 修法：唤醒重置观察窗 → 同一 t3 第 1 败只重新记 1 个 turn，绝不触阈
    val (c3, v3) = LoopGuard.evaluate(List(e), "t3", c2.resetCrossTurn, cfg)
    assertEquals(c3.crossTurn.get(fp).map(_.size), Some(1))
    assertEquals(v3, LoopGuard.Verdict.Pass)
    // terminatedFps 刻意不随 resetCrossTurn 清（L1 终止过的 fp 复发仍即刻 Freeze）
    assertEquals(c2.copy(terminatedFps = Set(fp)).resetCrossTurn.terminatedFps, Set(fp))
    // grace 账本同样不清（警告是会话级事实）
    assert(c3raw.warnedKeys.nonEmpty)
  }

  test("reminderMessage carries structured, actionable guidance (not just a log line)") {
    val msg = LoopGuard.reminderMessage(LoopGuard.Verdict.Warn("something is looping"))
    assert(msg.startsWith("<system-reminder>"))
    assert(msg.contains("Loop guard: something is looping"))
    assert(msg.contains("TRANSIENT"), "must tell the model how to handle transient failures")
    assert(msg.contains("deterministically broken"), "must tell the model how to handle a real loop")
    assert(msg.contains("terminates this turn"), "must state the escalation consequence")
    assert(msg.endsWith("</system-reminder>"))
  }

  // ── bash overhaul 红相 R1–R5（作者令 2026-11-04「组织优化 bash，用业界成熟
  //    做法完整实现，一步到位」）────────────────────────────────────────────
  //
  // 事实源：`docs/20261104_110500_our-bash-tool-defects.md` D1/D3（现场 A/B：10 次
  // 同参 `Bash(background_job_id=…)` 查询 → R-call 第 10 拍 Terminate → 节点/delegate
  // 失败）+ 调研件 §5.3 的修法（查询/等待类调用不计 R-call，但**仍计 S1**，另加一条
  // 「只提醒」的正向指标）。
  //
  // 判据口径（本组断言即验收面）：
  //   - 查询类调用 = `Bash` 且 args 带 `background_job_id` 且 `command` 为空 ⇒ 不计
  //     R-call 连续计数（「不同调用才刷新计数」的旧折叠逻辑对定时采样失效）；
  //   - **补偿**（不得把失败也豁免掉）：查询返回 isError 时照旧计 S1/S3（8 连败 = Terminate）；
  //   - 正向指标：同参查询次数达 5（`queryWarnAfter` 缺省 5）只发一次 L0 nudge，
  //     文案必须指向 Bash 有界等待参数，**永不 Terminate**；
  //   - 非查询的 Bash 工作调用（`{"command":"ls"}`）口径**不得松动**（10 连 = Terminate）。

  private val bgQueryArgs: Json = Json.obj("background_job_id" -> "abc".asJson, "command" -> "".asJson)

  private def bgQueryEvent(isError: Boolean = false, err: String = ""): LoopGuard.RoundEvent =
    LoopGuard.RoundEvent("Bash", bgQueryArgs, isError = isError, errorText = err, permissionDenied = false)

  private def repeat(n: Int, ev: LoopGuard.RoundEvent): (Counters, LoopGuard.Verdict) =
    (1 to n).foldLeft((Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) { case ((cnt, _), _) =>
      LoopGuard.evaluate(List(ev), "t1", cnt, cfg)
    }

  private def assertNotTerminal(v: LoopGuard.Verdict, what: String): Unit =
    v match
      case t: LoopGuard.Verdict.Terminate => fail(s"$what must NOT Terminate (got: ${t.msg})")
      case f: LoopGuard.Verdict.Freeze => fail(s"$what must NOT Freeze (got: ${f.msg})")
      case _ => ()

  test("R1 (bash overhaul): 20 identical Bash QUERY calls in one turn never Terminate/Freeze") {
    // 现场 A/B 的精确形态：同参 `background_job_id` 查询，全部成功（isError=false）。
    // 旧代码第 10 次 R-call 命中 identicalCallHard ⇒ Terminate（红）。
    val (_, v) = repeat(20, bgQueryEvent())
    assertNotTerminal(v, "identical background_job_id polling (the incident shape)")
  }

  test("R2 (bash overhaul): 10 identical Bash WORK calls still Terminate — the old口径 is not loosened") {
    // 对照臂：查询豁免**不得**扩大到整个 Bash 工具（工作调用口径逐字不变）。
    val (_, v) = repeat(10, okEv("Bash", Json.obj("command" -> "ls".asJson)))
    v match
      case LoopGuard.Verdict.Terminate(msg, _) =>
        assert(msg.contains("10 times in a row"), s"work-loop keeps the R-call verdict text: $msg")
      case other => fail(s"identical work calls must still Terminate at 10, got $other")
  }

  test("R3 (bash overhaul): 8 identical FAILED queries still Terminate (S1 single point kept)") {
    // 补偿臂：查询豁免只免 R-call，**不免 S1**——`[Background job not found]` 连发 8 次照杀。
    val (_, v) = repeat(8, bgQueryEvent(isError = true, err = "[Background job not found] Job ID: abc"))
    v match
      case LoopGuard.Verdict.Terminate(msg, _) =>
        assert(msg.contains("failed 8 times"), s"S1 hard(8) must still fire on failed queries: $msg")
      case other => fail(s"failed queries must still Terminate at S1 hard(8), got $other")
  }

  test("R4 (bash overhaul): the 5th identical query nudges toward a bounded Bash wait") {
    // 正向指标 queryWarnAfter（缺省 5）：第 5 拍给一次 L0 提示，指向等待原语。
    val (c4, v4) = repeat(4, bgQueryEvent())
    assertNotTerminal(v4, "the 4th identical query")
    val (_, v5) = LoopGuard.evaluate(List(bgQueryEvent()), "t1", c4, cfg)
    v5 match
      case LoopGuard.Verdict.Warn(msg, _) =>
        assert(
          msg.contains("background_job_id") && msg.contains("timeout"),
          s"the nudge must point at Bash bounded wait, got: $msg"
        )
      case other => fail(s"expected Warn (L0 nudge) at the 5th identical query, got $other")
  }

  test("R5 (bash overhaul): 20 identical Bash bounded waits never Terminate") {
    val waitEv = okEv("Bash", Json.obj("background_job_id" -> "abc".asJson, "timeout" -> 5000.asJson))
    val (_, v) = repeat(20, waitEv)
    assertNotTerminal(v, "repeated bounded Bash waits")
  }

end LoopGuardSpec
