package nebflow.core.processor

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.{AgentKind, AgentRecord, AgentStatus}
import nebflow.core.project.CancelSource
import nebflow.shared.Defaults

import scala.concurrent.duration.*

/**
 * 取消静默死锁修复批（2026-09-10）**纯函数面**单测：R6（工具相位判据尊重命令声明
 * 时长）+ R7（取消触发源两态）。
 *
 * 覆盖：
 *  - U1 R7 `CancelSource.code` / `classify`（引擎特征串 vs 用户/Agent 主动取消）
 *  - U2 R7 `CancelSource.reasonFromBridgeMessage`（桥消息反解原因，不再嗅探即丢）
 *  - U3 R6 `Defaults.declaredToolTimeoutMs`（只读 `timeout`；缺失/0/负/非数 → 0；
 *       `run_in_background` **不**被当作授权时长）
 *  - U4 R6 `ToolStuckJudgment.effectiveToolPhaseMs` 有效阈值口径
 *  - U5 R6 `TaskStuckWatcher.assess` 三例（大 timeout 持续推进不判 / 不带 timeout
 *       仍 10min 判死 / 超声明时长仍判死），以及「未声明 → 文案逐字不变」
 *
 * ⚠ 本文件不测 R1–R5（引擎/通知面）——见 CancelDeadlockFixSpec。
 */
class CancelSourceAndToolDeadlineSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def withProp[A](kvs: (String, String)*)(body: => A): A =
    val saved = kvs.map((k, _) => k -> sys.props.get(k))
    kvs.foreach((k, v) => sys.props.update(k, v))
    try body
    finally saved.foreach { case (k, v) => v match
      case Some(old) => sys.props.update(k, old)
      case None      => sys.props.remove(k) }

  private def rec(now: Long, toolAgeMs: Long, deadlineMs: Long): AgentRecord =
    AgentRecord(
      sessionId = "cd-sess",
      ref = null,
      kind = AgentKind.Delegate,
      rootSessionId = "root-1",
      startedAt = now - 60 * 60 * 1000L,
      status = AgentStatus.Processing,
      lastActivityMs = now, // agent 侧判据刻意新鲜：只有工具相位轴能命中
      turnStartedAt = now - toolAgeMs,
      currentToolName = Some("Bash"),
      currentToolStartedAt = now - toolAgeMs,
      currentToolDeadlineMs = deadlineMs
    )

  // ── U1 R7：触发源分类 ─────────────────────────────────────────────

  test("U1 R7: CancelSource.code / classify — engine marker vs user-initiated (two-state)") {
    assertEquals(CancelSource.code(CancelSource.Engine), "engine")
    assertEquals(CancelSource.code(CancelSource.User), "user")
    // TaskStuckWatcher 两处桥取消的 reason 尾注（L3 / giveUp）
    assert(CancelSource.classify(
      "stuck 25m+ (L3 hard-recovery: released by TaskStuckWatcher)") == CancelSource.Engine)
    assert(CancelSource.classify(
      "stuck (L4 giveUp: released by TaskStuckWatcher; consider re-delegating)") == CancelSource.Engine)
    // 人/Agent/父会话级联取消
    assertEquals(CancelSource.classify("cancelled by NodeCancel"), CancelSource.User)
    assertEquals(CancelSource.classify("user cancelled from session panel"), CancelSource.User)
    // engine 特征串是唯一判据（子串匹配、大小写敏感）
    assertEquals(CancelSource.StuckWatcherMarker, "released by TaskStuckWatcher")
  }

  // ── U2 R7：桥消息 → reason 反解 ───────────────────────────────────

  test("U2 R7: reasonFromBridgeMessage — strips 'cancelled:' prefix, keeps other forms verbatim") {
    assertEquals(CancelSource.reasonFromBridgeMessage("cancelled: stuck 25m"), "stuck 25m")
    assertEquals(CancelSource.reasonFromBridgeMessage("  cancelled:   spaced reason "), "spaced reason")
    // 无前缀但含 cancelled（NodeCancel 形态）→ 原样保留
    assertEquals(CancelSource.reasonFromBridgeMessage("cancelled by NodeCancel"), "cancelled by NodeCancel")
    // 非 cancelled 文本（防御：不该发生但不得丢信息）
    assertEquals(CancelSource.reasonFromBridgeMessage("boom"), "boom")
  }

  // ── U3 R6：命令自报授权时长取数口 ─────────────────────────────────

  test("U3 R6: declaredToolTimeoutMs reads ONLY the declared `timeout` (never run_in_background)") {
    assertEquals(Defaults.declaredToolTimeoutMs(Json.obj("timeout" -> Json.fromLong(900_000L)).asObject.get), 900_000L)
    // 未声明 / 非正 / 非数 → 0（= 未声明，判据回落 10min 档）
    assertEquals(Defaults.declaredToolTimeoutMs(Json.obj().asObject.get), 0L)
    assertEquals(Defaults.declaredToolTimeoutMs(Json.obj("command" -> Json.fromString("x")).asObject.get), 0L)
    assertEquals(Defaults.declaredToolTimeoutMs(Json.obj("timeout" -> Json.fromInt(0)).asObject.get), 0L)
    assertEquals(Defaults.declaredToolTimeoutMs(Json.obj("timeout" -> Json.fromInt(-5)).asObject.get), 0L)
    assertEquals(Defaults.declaredToolTimeoutMs(Json.obj("timeout" -> Json.fromString("900000")).asObject.get), 0L)
    // run_in_background 不是授权时长（把后台语义并进前台判死会扩大误杀面）
    assertEquals(
      Defaults.declaredToolTimeoutMs(Json.obj("run_in_background" -> Json.fromBoolean(true), "command" -> Json.fromString("sleep 1")).asObject.get),
      0L)
    // 声明与后台并存 → 仍取 timeout
    assertEquals(
      Defaults.declaredToolTimeoutMs(Json.obj("timeout" -> Json.fromLong(120_000L), "run_in_background" -> Json.fromBoolean(true)).asObject.get),
      120_000L)
  }

  // ── U4 R6：有效阈值口径 ──────────────────────────────────────────

  test("U4 R6: effectiveToolPhaseMs — declared only widens; undeclared (or small declared) keeps the default band") {
    val d = 600_000L
    val slack = 60_000L
    // 未声明 → 默认档（10min）零变化
    assertEquals(ToolStuckJudgment.effectiveToolPhaseMs(d, 0L, slack), d)
    // 声明 > 默认档 → 声明 + 宽限（案例 1：timeout=900000ms → 960000ms）
    assertEquals(ToolStuckJudgment.effectiveToolPhaseMs(d, 900_000L, slack), 960_000L)
    // 声明 + 宽限 < 默认档（小声明）→ 仍取默认档：判据只**放宽**不放严，
    // 声明时长不得把 10min 安全网缩到声明值之下
    assertEquals(ToolStuckJudgment.effectiveToolPhaseMs(d, 30_000L, slack), d)
    assertEquals(ToolStuckJudgment.effectiveToolPhaseMs(d, 30_000L, 1_000L), d)
    // 负 slack 被夹到 0（防御：不得把阈值缩到声明时长之下）
    assertEquals(ToolStuckJudgment.effectiveToolPhaseMs(d, 900_000L, -1L), 900_000L)
  }

  test("U4b R6: effectiveToolPhaseMs reads Defaults.ToolDeadlineSlackMs prop at call time (kill-switch)") {
    withProp("nebflow.stuck.toolDeadlineSlackMs" -> "1000") {
      assertEquals(Defaults.ToolDeadlineSlackMs, 1_000L)
    }
    // prop 撤销后回落默认 60000
    assertEquals(Defaults.ToolDeadlineSlackMs, 60_000L)
  }

  // ── U5 R6：assess 三例 ───────────────────────────────────────────

  test("U5-a R6 (case 1 反面): declared timeout=900000ms, 11.2min elapsed → NOT judged stuck (no more false kill)") {
    val now = System.currentTimeMillis()
    val elevenMin = (11.2 * 60 * 1000).toLong
    // 改造前：11.2min > 10min 默认档 → 判死（案例 1 误杀）；改造后：declared 900000+60000
    // > 11.2min → 不判
    withProp("nebflow.stuck.toolDeadlineSlackMs" -> "60000") {
      assertEquals(TaskStuckWatcher.assess(rec(now, elevenMin, 900_000L), now), None)
    }
    // 同刻未声明时长 → 仍按 10min 判死（新判据不放走真卡死）
    withProp("nebflow.stuck.toolDeadlineSlackMs" -> "60000") {
      assert(TaskStuckWatcher.assess(rec(now, elevenMin, 0L), now).isDefined,
        "undeclared deadline must keep the 10min band")
    }
  }

  test("U5-b R6: no declared timeout + 10min+ elapsed → judged stuck, message wording byte-identical (no deadline note)") {
    val now = System.currentTimeMillis()
    withProp("nebflow.stuck.toolDeadlineSlackMs" -> "60000") {
      val r = TaskStuckWatcher.assess(rec(now, 700_000L, 0L), now)
      assertEquals(r.isDefined, true, "undeclared >10min must still be judged")
      val (secs, msg) = r.get
      assert(secs >= 700L, s"secs must reflect elapsed tool phase, got $secs")
      assertEquals(msg, "tool 'Bash' running 700s in an unfinished turn",
        "undeclared-deadline message must not gain a new note (zero wording drift)")
    }
  }

  test("U5-c R6: declared timeout exceeded (past declared+slack) → judged stuck, message carries the declared-timeout note") {
    val now = System.currentTimeMillis()
    withProp("nebflow.stuck.toolDeadlineSlackMs" -> "60000") {
      val r = TaskStuckWatcher.assess(rec(now, 1_200_000L, 900_000L), now) // 20min > 15min+1min
      assertEquals(r.isDefined, true, "truly stuck tool must still be judged after the declared window")
      val (_, msg) = r.get
      assert(msg.contains("declared timeout 900s"), s"message must carry the declared timeout, got: $msg")
      assert(msg.contains("Bash") && msg.contains("unfinished turn"), s"tool + turn context expected, got: $msg")
    }
  }

  test("U5-d R6: prop nebflow.stuck.toolPhaseMs still shortens the band for undeclared tools (e2e lever)") {
    val now = System.currentTimeMillis()
    withProp("nebflow.stuck.toolPhaseMs" -> "1500") {
      assertEquals(TaskStuckWatcher.assess(rec(now, 1_000L, 0L), now), None, "within shortened band → not stuck")
      assert(TaskStuckWatcher.assess(rec(now, 3_000L, 0L), now).isDefined, "past shortened band → stuck")
    }
    // 声明时长为 0 时读数与 prop 同步（判据不缓存）
    withProp("nebflow.stuck.toolPhaseMs" -> "1500", "nebflow.stuck.toolDeadlineSlackMs" -> "60000") {
      assertEquals(ToolStuckJudgment.effectiveToolPhaseMs(Defaults.ToolPhaseStuckMs, 0L, Defaults.ToolDeadlineSlackMs), 1_500L)
    }
  }

end CancelSourceAndToolDeadlineSpec
