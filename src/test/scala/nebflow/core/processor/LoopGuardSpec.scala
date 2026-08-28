package nebflow.core.processor

import munit.FunSuite
import io.circe.Json
import io.circe.syntax._

/** Block 3 循环检测器纯函数测试（supervision trio §D，2026-08-27）。
  *
  * 三信号 × 处置阶梯 × 豁免矩阵。挂载点（AgentCore.pipeToolExecutions）的
  * 行为验证在 wiring 级 spec；本文件只测判定核心。
  */
class LoopGuardSpec extends FunSuite:

  private val cfg = LoopGuard.Config.Default // 3 / 8 / 60 / 3

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

  // ── S2 高活动零进展（turn 内轮预算） ──────────────────────

  test("S2: non-progress rounds accumulate; progress rounds (successful Edit) do not") {
    val readRound = List(okEv("Read", args("/x")))
    val editRound = List(okEv("Edit", args("/x")))
    val (c1, _) = LoopGuard.evaluate(readRound, "t1", LoopGuard.Counters.Empty, cfg)
    assertEquals(c1.roundCount, 1)
    val (c2, _) = LoopGuard.evaluate(editRound, "t1", c1, cfg)
    assertEquals(c2.roundCount, 1) // Edit 成功 = 进展轮，不计
    val (c3, _) = LoopGuard.evaluate(readRound, "t1", c2, cfg)
    assertEquals(c3.roundCount, 2)
  }

  test("S2: warns at 70% budget and terminates beyond max") {
    // 70% of 60 = 42；从 41 起步到 42 exact 触发 Warn
    var cnt = LoopGuard.Counters.Empty.copy(turnKey = "t1", roundCount = 41)
    val readRound = List(okEv("Read", args("/x")))
    val (_, v42) = LoopGuard.evaluate(readRound, "t1", cnt, cfg)
    v42 match
      case LoopGuard.Verdict.Warn(msg) => assert(msg.contains("42 of 60"))
      case other                       => fail(s"expected budget Warn at 42, got $other")
    // 60 轮（>60 → terminate）
    cnt = LoopGuard.Counters.Empty.copy(turnKey = "t1", roundCount = 60)
    val (_, v61) = LoopGuard.evaluate(readRound, "t1", cnt, cfg)
    v61 match
      case LoopGuard.Verdict.Terminate(msg, fp) =>
        assert(msg.contains("budget exceeded"))
        assertEquals(fp, "") // S2 终止不记 terminatedFps
      case other => fail(s"expected budget Terminate, got $other")
  }

  test("S2: s2Exempt (save-turn) skips round accumulation entirely") {
    val readRound = List(okEv("Read", args("/x")))
    val (c1, _) = LoopGuard.evaluate(readRound, "t1", LoopGuard.Counters.Empty, cfg, s2Exempt = true)
    assertEquals(c1.roundCount, 0)
    // 已在 60 的计数器 + 豁免 → 不终止
    val cnt = LoopGuard.Counters.Empty.copy(turnKey = "t1", roundCount = 60)
    val (_, v) = LoopGuard.evaluate(readRound, "t1", cnt, cfg, s2Exempt = true)
    assertEquals(v, LoopGuard.Verdict.Pass)
  }

  // ── S3 跨 turn 同调用 ─────────────────────────────────────

  test("S3: same fp failing in 3 separate turns with no success freezes") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c1, v1) = LoopGuard.evaluate(List(e), "t1", LoopGuard.Counters.Empty, cfg)
    assertEquals(v1, LoopGuard.Verdict.Pass)
    val (c2, v2) = LoopGuard.evaluate(List(e), "t2", c1, cfg) // turn 边界：S1 清零，S3 保留
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
      case other                               => fail(s"expected Terminate, got $other")
    // 新 turn 同 fp 再败 → 直接 Freeze（不再给第二次 L1）
    val (_, v2) = LoopGuard.evaluate(List(e), "t2", ct1, cfg)
    v2 match
      case LoopGuard.Verdict.Freeze(msg) => assert(msg.contains("terminated earlier"))
      case other                         => fail(s"expected Freeze on recurrence, got $other")
  }

  // ── 豁免矩阵 ─────────────────────────────────────────────

  test("exemptTools: TaskQuery failures never count toward S1/S3 (polling exemption)") {
    val q = failed("TaskQuery", Json.obj("status" -> "in_progress".asJson), "no tasks")
    // 同参同败 20 次（跨 3 turn）——永不触发
    val (cnt, v) = (1 to 20).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), i) => LoopGuard.evaluate(List(q), s"t${(i - 1) / 7 + 1}", c, cfg)
    }
    assertEquals(v, LoopGuard.Verdict.Pass)
    assertEquals(cnt.streakCount, 0)
    // 但 S2 轮预算仍适用（非进展轮照计）——turn 边界重置后 t3 段累计 6 轮
    assertEquals(cnt.roundCount, 6)
  }

  test("permissionDenied: policy denials are not loop signals (#12 independent governance)") {
    val d = failed("Bash", Json.obj("cmd" -> "rm -rf /".asJson),
      "Tool Bash is denied by the session permission policy", denied = true)
    val (cnt, v) = (1 to 10).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(d), "t1", c, cfg)
    }
    assertEquals(v, LoopGuard.Verdict.Pass)
    assertEquals(cnt.streakCount, 0)
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

  test("turn boundary resets S1/S2 but preserves S3 (crossTurn/terminatedFps)") {
    val e = failed("Read", args("/x"), "File does not exist: /x")
    val (c1, _) = (1 to 5).foldLeft((LoopGuard.Counters.Empty, LoopGuard.Verdict.Pass: LoopGuard.Verdict)) {
      case ((c, _), _) => LoopGuard.evaluate(List(e), "t1", c, cfg)
    }
    assertEquals(c1.streakCount, 5)
    assertEquals(c1.roundCount, 5)
    val (c2, v2) = LoopGuard.evaluate(List(e), "t2", c1, cfg)
    assertEquals(v2, LoopGuard.Verdict.Pass)
    assertEquals(c2.streakCount, 1) // 重置后从 1 重新计
    assertEquals(c2.roundCount, 1)
    assert(c2.crossTurn.nonEmpty) // S3 跨 turn 保留
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
