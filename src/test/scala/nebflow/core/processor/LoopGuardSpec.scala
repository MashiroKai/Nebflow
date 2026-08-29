package nebflow.core.processor

import munit.FunSuite
import io.circe.Json
import io.circe.syntax._

/** Block 3 循环检测器纯函数测试（supervision trio §D 2026-08-27；简化重构
  * 2026-08-30 作者拍板：S2 进展判定/轮预算删除，新增 R 精确重复检测）。
  *
  * 信号矩阵 × 处置阶梯 × 豁免矩阵。挂载点（AgentCore.pipeToolExecutions）
  * 的行为验证在 wiring 级 spec；本文件只测判定核心。
  */
class LoopGuardSpec extends FunSuite:

  private val cfg = LoopGuard.Config.Default // S1: 3/8, R: 10/10, S3: 3

  private def failed(tool: String, args: Json = Json.obj(), err: String = "boom",
                     denied: Boolean = false): LoopGuard.RoundEvent =
    LoopGuard.RoundEvent(tool, args, isError = true, errorText = err, permissionDenied = denied)

  private def okEv(tool: String, args: Json = Json.obj()): LoopGuard.RoundEvent =
    LoopGuard.RoundEvent(tool, args, isError = false, errorText = "", permissionDenied = false)

  private def args(path: String): Json = Json.obj("path" -> path.asJson)

  // ── S1 同参同败（turn 内） ────────────────────────────────

  test("S1: identical failure streak warns exactly at soft threshold (3)") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c1, v1) = LoopGuard.evaluate(List(e), "t1", LoopGuard.Counters.Empty, cfg)
    assertEquals(v1, LoopGuard.Verdict.Pass)
    val (c2, v2) = LoopGuard.evaluate(List(e), "t1", c1, cfg)
    assertEquals(v2, LoopGuard.Verdict.Pass)
    val (c3, v3) = LoopGuard.evaluate(List(e), "t1", c2, cfg)
    v3 match
      case LoopGuard.Verdict.Warn(msg) => assert(msg.contains("failed 3 times"))
      case other                       => fail(s"expected Warn, got $other")
    assertEquals(c3.streakCount, 3)
  }

  test("S1: streak terminates at hard threshold (8)") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (_, v) = (1 to 8).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((cnt, _), _) => LoopGuard.evaluate(List(e), "t1", cnt, cfg)
    }
    v match
      case LoopGuard.Verdict.Terminate(msg, fp) =>
        assert(msg.contains("failed 8 times"))
        assert(fp.nonEmpty)
      case other => fail(s"expected Terminate, got $other")
  }

  test("S1: success of the same call resets the streak (flaky fail-fail-ok never trips)") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c1, _) = LoopGuard.evaluate(List(e), "t1", LoopGuard.Counters.Empty, cfg)
    val (c2, _) = LoopGuard.evaluate(List(e), "t1", c1, cfg)
    val (c3, v3) = LoopGuard.evaluate(List(okEv("Read", args("/x"))), "t1", c2, cfg)
    assertEquals(v3, LoopGuard.Verdict.Pass)
    assertEquals(c3.streakCount, 0)
    // 再连败 2 次也不会撞 soft（被成功清零）
    val (c4, v4) = LoopGuard.evaluate(List(e), "t1", c3, cfg)
    val (_, v5) = LoopGuard.evaluate(List(e), "t1", c4, cfg)
    assertEquals(v4, LoopGuard.Verdict.Pass)
    assertEquals(v5, LoopGuard.Verdict.Pass)
  }

  test("S1: different arguments are different fingerprints — alternating failures never accumulate") {
    val e1 = failed("Read", args("/a"), "File does not exist: /a")
    val e2 = failed("Read", args("/b"), "File does not exist: /b")
    val (_, v) = (1 to 10).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((cnt, _), i) => LoopGuard.evaluate(List(if i % 2 == 0 then e1 else e2), "t1", cnt, cfg)
    }
    // streak 每轮被换参打断，永不 ≥2
    assertEquals(v, LoopGuard.Verdict.Pass)
  }

  test("S1: varying error signature (timeout 30s vs 31s) does not accumulate — flaky exemption") {
    val e30 = failed("Bash", Json.obj("cmd" -> "build".asJson), "command timed out after 30s")
    val e31 = failed("Bash", Json.obj("cmd" -> "build".asJson), "command timed out after 31s")
    val (c1, v1) = LoopGuard.evaluate(List(e30), "t1", LoopGuard.Counters.Empty, cfg)
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
    val (c9, v9) = (1 to 9).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(e), "t1", c, cfg)
    }
    assertEquals(v9, LoopGuard.Verdict.Pass)
    assertEquals(c9.lastCallCount, 9)
    val (c10, v10) = LoopGuard.evaluate(List(e), "t1", c9, cfg)
    v10 match
      case LoopGuard.Verdict.Terminate(msg, fp) =>
        assert(msg.contains("10 times in a row"))
        assert(msg.contains("identical arguments"))
        assert(fp == LoopGuard.fingerprint("Bash", e.args), "Terminate must record the call fp (recurrence → Freeze chain)")
        assert(c10.terminatedFps.contains(fp))
      case other => fail(s"expected Terminate at exactly the 10th, got $other")
  }

  test("R-call: same args failing x10 still caught (S1 fires earlier at 8 — precedence check)") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    // S1 在第 8 次先触发（8 < 10）
    val (c8, v8) = (1 to 8).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(e), "t1", c, cfg)
    }
    v8 match
      case LoopGuard.Verdict.Terminate(msg, _) => assert(msg.contains("failed 8 times"), "S1 (8) must fire before R-call (10)")
      case other                               => fail(s"expected S1 Terminate at 8, got $other")
    // 第 9/10 次：同 fp 再败 → recurrence → Freeze（更强裁决，设计内）
    val (_, v10) = (1 to 2).foldLeft((c8, v8: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(e), "t1", c, cfg)
    }
    assert(v10.isInstanceOf[LoopGuard.Verdict.Freeze], s"expected Freeze on recurrence, got $v10")
  }

  test("R-call: interruption by ANY different call resets the counter (author ruling 5: A A A B A A A)") {
    val a = okEv("Bash", Json.obj("command" -> "same".asJson))
    val b = okEv("Bash", Json.obj("command" -> "different".asJson))
    // 9×A
    val (c9, _) = (1 to 9).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(a), "t1", c, cfg)
    }
    assertEquals(c9.lastCallCount, 9)
    // 插 1 次 B → A 计数必须从 1 重数
    val (cAfterB, _) = LoopGuard.evaluate(List(b), "t1", c9, cfg)
    val (cA1, _) = LoopGuard.evaluate(List(a), "t1", cAfterB, cfg)
    assertEquals(cA1.lastCallCount, 1, "interruption must reset the consecutive counter (no cross-interruption accumulation)")
    // 再续 8×A 仍不触发（凑不齐新的一段 10 连）
    val (c8, v8) = (1 to 8).foldLeft((cA1, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(a), "t1", c, cfg)
    }
    assertEquals(v8, LoopGuard.Verdict.Pass)
    assertEquals(c8.lastCallCount, 9)
  }

  test("R-call: success does NOT reset — identical successful calls x10 still a loop") {
    val e = okEv("Read", args("/logs/fulltest.log"))
    val (_, v) = (1 to 10).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(e), "t1", c, cfg)
    }
    v match
      case LoopGuard.Verdict.Terminate(msg, _) => assert(msg.contains("in a row"))
      case other                               => fail(s"expected Terminate, got $other")
  }

  test("R-call: verification-style work (varying args) never triggers — mutation-red target (a)") {
    // 08-28 事故场景：sbt test → 读不同日志行 → grep 不同模式——参数各异永不连击
    val rounds = (1 to 25).map { i =>
      if i % 3 == 0 then List(okEv("Bash", Json.obj("command" -> s"sbt testOnly Suite${i % 5}".asJson)))
      else if i % 3 == 1 then List(okEv("Read", args(s"/logs/fulltest.log#L$i")))
      else List(okEv("Grep", Json.obj("pattern" -> s"case-$i".asJson, "path" -> "/logs".asJson)))
    }
    val (_, v) = rounds.foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), evs) => LoopGuard.evaluate(evs.toList, "t1", c, cfg)
    }
    assertEquals(v, LoopGuard.Verdict.Pass)
  }

  test("R-call: multi-event rounds (A,B) interleaved keep both counts at 1") {
    val a = okEv("Read", args("/a"))
    val b = okEv("Grep", args("pat"))
    val (_, v) = (1 to 15).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(a, b), "t1", c, cfg)
    }
    assertEquals(v, LoopGuard.Verdict.Pass, "alternating A,B never reaches 10 consecutive")
  }

  test("R-text: identical assistant text x9 passes, exactly the 10th terminates (mutation-red target d)") {
    val (c9, v9) = (1 to 9).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(Nil, "t1", c, cfg, assistantText = "我已经重试了，请稍候。")
    }
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

  test("R-text: any different text resets; empty text neither counts nor resets") {
    val same = "好的，我继续。"
    val (c5, _) = (1 to 5).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(Nil, "t1", c, cfg, assistantText = same)
    }
    assertEquals(c5.lastTextCount, 5)
    // 不同文本 → 刷新
    val (cReset, _) = LoopGuard.evaluate(Nil, "t1", c5, cfg, assistantText = "换个说法。")
    assertEquals(cReset.lastTextCount, 1)
    // 空文本轮（纯工具轮）→ 不计也不刷新
    val (cEmpty, _) = LoopGuard.evaluate(List(okEv("Bash", Json.obj("command" -> "x".asJson))), "t1", cReset, cfg, assistantText = "")
    assertEquals(cEmpty.lastTextCount, 1, "empty text must not reset the streak")
    // 续 4 次同文本 = 5 连（<10 不触发）
    val (_, v) = (1 to 4).foldLeft((cEmpty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(Nil, "t1", c, cfg, assistantText = same)
    }
    assertEquals(v, LoopGuard.Verdict.Pass)
  }

  // ── S3 跨 turn 同调用 ─────────────────────────────────────

  test("S3: same fp failing in 3 separate turns with no success freezes") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c1, v1) = LoopGuard.evaluate(List(e), "t1", LoopGuard.Counters.Empty, cfg)
    assertEquals(v1, LoopGuard.Verdict.Pass)
    val (c2, v2) = LoopGuard.evaluate(List(e), "t2", c1, cfg) // turn 边界：S1/R 清零，S3 保留
    assertEquals(v2, LoopGuard.Verdict.Pass)
    assertEquals(c2.streakCount, 1) // S1 已被 turn 边界重置
    val (_, v3) = LoopGuard.evaluate(List(e), "t3", c2, cfg)
    v3 match
      case LoopGuard.Verdict.Freeze(msg) => assert(msg.contains("3 separate turns"))
      case other                         => fail(s"expected Freeze, got $other")
  }

  test("S3: a success of the same fp in between clears the cross-turn record") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c1, _) = LoopGuard.evaluate(List(e), "t1", LoopGuard.Counters.Empty, cfg)
    val (c2, _) = LoopGuard.evaluate(List(okEv("Read", args("/x"))), "t2", c1, cfg)
    assertEquals(c2.crossTurn.get(LoopGuard.fingerprint("Read", args("/x"))), None)
    val (_, v3) = LoopGuard.evaluate(List(e), "t3", c2, cfg)
    assertEquals(v3, LoopGuard.Verdict.Pass) // 只 1 个 turn 记录
  }

  test("L1 recurrence: fp terminated earlier then failing again in a later turn freezes immediately") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    // 第一 turn：8 连败 → Terminate（fp 记入 terminatedFps）
    val (ct1, vt1) = (1 to 8).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((cnt, _), _) => LoopGuard.evaluate(List(e), "t1", cnt, cfg)
    }
    vt1 match
      case LoopGuard.Verdict.Terminate(_, fp) => assert(ct1.terminatedFps.contains(fp))
      case other                              => fail(s"expected Terminate, got $other")
    // 新 turn 同 fp 再败 → 直接 Freeze（不再给第二次 L1）
    val (_, v2) = LoopGuard.evaluate(List(e), "t2", ct1, cfg)
    v2 match
      case LoopGuard.Verdict.Freeze(msg) => assert(msg.contains("terminated earlier"))
      case other                         => fail(s"expected Freeze on recurrence, got $other")
  }

  // ── 豁免矩阵 ─────────────────────────────────────────────

  test("exemptTools: TaskQuery polling never counts toward S1/S3/R-call (polling exemption)") {
    val q = failed("TaskQuery", Json.obj("status" -> "in_progress".asJson), "no tasks")
    // 同参 20 次（跨 3 turn）——S1/S3/R 全不触发
    val (cnt, v) = (1 to 20).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), i) => LoopGuard.evaluate(List(q), s"t${(i - 1) / 7 + 1}", c, cfg)
    }
    assertEquals(v, LoopGuard.Verdict.Pass)
    assertEquals(cnt.streakCount, 0)
    assertEquals(cnt.lastCallCount, 0, "exempt tools must not feed the R-call counter")
  }

  test("permissionDenied: policy denials are not loop signals (#12 independent governance)") {
    val d = failed("Bash", Json.obj("cmd" -> "rm -rf /".asJson),
      "Tool Bash is denied by the session permission policy", denied = true)
    val (cnt, v) = (1 to 10).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(d), "t1", c, cfg)
    }
    assertEquals(v, LoopGuard.Verdict.Pass)
    assertEquals(cnt.streakCount, 0)
    assertEquals(cnt.lastCallCount, 0)
  }

  test("disabled config: everything passes and counters are untouched") {
    val off = cfg.copy(enabled = false)
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (cnt, v) = (1 to 10).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(e), "t1", c, off)
    }
    assertEquals(v, LoopGuard.Verdict.Pass)
    assertEquals(cnt, LoopGuard.Counters.Empty)
  }

  test("turn boundary resets S1 AND R consecutive counters but preserves S3") {
    val e = failed("Read", args("/x"), "File does not exist: /x") // 同一 fp 同时喂 S1 与 R-call
    val (c1, _) = (1 to 5).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(e), "t1", c, cfg)
    }
    assertEquals(c1.streakCount, 5)
    assertEquals(c1.lastCallCount, 5)
    assertEquals(c1.lastTextCount, 0)
    val (c2, v2) = LoopGuard.evaluate(List(e), "t2", c1, cfg, assistantText = "same line")
    assertEquals(v2, LoopGuard.Verdict.Pass)
    assertEquals(c2.streakCount, 1, "S1 reset by turn boundary")
    assertEquals(c2.lastCallCount, 1, "R-call reset by turn boundary")
    assertEquals(c2.lastTextCount, 1, "R-text starts fresh in the new turn")
    assert(c2.crossTurn.nonEmpty, "S3 cross-turn records preserved")
  }

  test("verdict strength: Freeze beats Terminate beats Warn when simultaneous") {
    // 构造：S3 命中（3 turns）的同时 streak 达 hard——Freeze 必须赢
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c2, _) = (1 to 2).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(e), "t1", c, cfg)
    }
    // t2 凑第二个 turn 记录 + streak 7 次
    val (c2b, _) = (1 to 7).foldLeft((c2, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(e), "t2", c, cfg)
    }
    // t3 第一败：crossTurn 3 turns + streak 8 同时成立 → Freeze
    val (_, v3) = LoopGuard.evaluate(List(e), "t3", c2b, cfg)
    v3 match
      case LoopGuard.Verdict.Freeze(_) => // expected — strongest wins
      case other                       => fail(s"expected Freeze (strongest), got $other")
  }

  test("reminderMessage renders system-reminder envelope") {
    val msg = LoopGuard.reminderMessage(LoopGuard.Verdict.Warn("something is looping"))
    assert(msg.startsWith("<system-reminder>"))
    assert(msg.contains("Loop guard: something is looping"))
    assert(msg.endsWith("</system-reminder>"))
  }

end LoopGuardSpec
