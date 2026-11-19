package nebflow.core.tools

import cats.effect.IO
import munit.CatsEffectSuite

/**
 * 后台任务终态通知负载判读（作者报障 2026-10-24「后台任务通知很不详细甚至有截断
 * ——这里是不是存在问题」修复批；spec 三断言钉死）：
 *
 *  - ① **完成通知含 exit code 与输出尾段**：退出码恒在（exit 0 = 显式成功信号——
 *    旧形态只在非零才带，作者截图现场 = 成功任务通知只剩描述行 + 悬空冒号），
 *    输出尾段在 payload 正文；远程腿（exitCode=None）耗时段仍在；
 *  - ② **长输出截断到 tail 仍可跳全文**：超 tail 行数 ⇒ payload 只带末尾 tail 行 +
 *    截断标注（总行数 + jobId 指回）；全文照旧 BgTaskOutputStore 落盘可读
 *    （REST 详情卡数据源）——通知面不背大输出、回看面不丢字节；行数上限走
 *    `nebflow.bg.notifyTailLines` prop（默认 20，可配置）；
 *  - ③ **失败通知含错误尾段**：`[Background task failed]` + 错误串尾段 + 耗时段。
 */
class BgNotifyPayloadSpec extends CatsEffectSuite:

  // ── ① 完成通知：exit code 恒在 + 输出尾段 ─────────────────────────────

  test("①a 完成 payload 含 exit 0、耗时与输出尾段（成功任务一眼可判）") {
    val built = BgNotifyPayload.completed(
      description = "Render v4.pptx to PDF",
      exitCode = Some(0),
      durationMs = Some(6500L),
      stdout = "page-1.png written\npage-2.png written",
      stderr = "",
      jobId = "job-a1"
    )
    assert(built.payload.startsWith("[Background task completed] \"Render v4.pptx to PDF\" (exit 0 · 6.5s):"))
    assert(built.payload.contains("page-1.png written"))
    assert(built.payload.contains("page-2.png written"))
    assertEquals(built.metadata("exitCode").flatMap(_.asNumber.flatMap(_.toInt)), Some(0))
    assertEquals(built.metadata("durationMs").flatMap(_.asNumber.flatMap(_.toLong)), Some(6500L))
    assert(!built.truncated)
  }

  test("①b 非零退出码照常在（exit 2）且 stderr 段保留") {
    val built = BgNotifyPayload.completed("flaky build", Some(2), Some(1200L), "partial out", "boom: no rule", "job-b1")
    assert(built.payload.contains("(exit 2 · 1.2s)"))
    assert(built.payload.contains("[stderr]:\nboom: no rule"))
  }

  test("①c 远程腿（无退出码）耗时段仍在、不出现 exit 字样") {
    val built = BgNotifyPayload.completed("remote render", None, Some(61_000L), "done", "", "job-c1")
    assert(built.payload.contains("(1m 01s)"))
    assert(!built.payload.contains("exit"))
  }

  test("①d 零输出 = [no output] 占位（悬空冒号形态消灭——作者截图现场）") {
    val built = BgNotifyPayload.completed("quiet command", Some(0), Some(300L), "", "", "job-d1")
    assert(built.payload.endsWith(":\n[no output]"))
    assertEquals(built.metadata("totalLines").flatMap(_.asNumber.flatMap(_.toLong)), Some(0L))
  }

  // ── ② 长输出截尾 + 全文回看面 ─────────────────────────────────────────

  test("②a 30 行输出 tail=20：只带末尾 20 行 + 截断标注（总行数 + jobId）") {
    val full = (1 to 30).map(i => f"line-$i%02d").mkString("\n")
    val built = BgNotifyPayload.completed("big job", Some(0), None, full, "", "job-e1", tailN = 20)
    // 头部 10 行不在通知里；末尾 20 行在。
    assert(!built.payload.contains("line-01"))
    assert(!built.payload.contains("line-10\n"))
    assert(built.payload.contains("line-11"))
    assert(built.payload.contains("line-30"))
    // 截断标注：总行数 + jobId 指回（面板行点击 → 详情卡全文）。
    assert(built.payload.contains("[truncated: showing last 20 of 30 lines"))
    assert(built.payload.contains("job job-e1"))
    assert(built.truncated)
    assertEquals(built.metadata("totalLines").flatMap(_.asNumber.flatMap(_.toLong)), Some(30L))
  }

  test("②b 全文落盘面：通知截尾后 BgTaskOutputStore 仍可整段读回（跳全文入口）") {
    val jobId = "job-f1"
    val full = (1 to 30).map(i => f"line-$i%02d").mkString("\n")
    val built = BgNotifyPayload.completed("big job", Some(0), None, full, "", jobId, tailN = 20)
    // 通知只带尾段（同 ②a）——store 侧同刻落全文。
    assert(built.truncated)
    for
      _ <- BgTaskOutputStore.open(jobId).flatTap(buf => IO(full.split("\n").foreach(buf.append)))
      _ <- BgTaskOutputStore.finalizeTask(jobId, "completed", Some(0), None)
      read <- BgTaskOutputStore.read(jobId, 0L)
    yield
      val snap = read.getOrElse(fail("store entry missing"))
      assertEquals(snap.output, full) // 全文 30 行逐字可读
      assertEquals(snap.totalLines, 30L)
      assertEquals(snap.exitCode, Some(0))
  }

  test("②c 行数上限可配置（nebflow.bg.notifyTailLines prop）") {
    val key = "nebflow.bg.notifyTailLines"
    val old = sys.props.get(key)
    try
      sys.props.update(key, "3")
      val full = (1 to 5).map(i => s"row-$i").mkString("\n")
      val built = BgNotifyPayload.completed("prop job", Some(0), None, full, "", "job-g1")
      assert(!built.payload.contains("row-1"))
      assert(!built.payload.contains("row-2"))
      assert(built.payload.contains("row-3"))
      assert(built.payload.contains("row-5"))
      assert(built.payload.contains("[truncated: showing last 3 of 5 lines"))
    finally
      old match
        case Some(v) => sys.props.update(key, v)
        case None => sys.props.remove(key)
  }

  // ── ③ 失败通知：错误尾段 ─────────────────────────────────────────────

  test("③a 失败 payload 含错误尾段与耗时段") {
    val built =
      BgNotifyPayload.failed("deploy step", "git push rejected\nremote: permission denied", Some(1200L), "job-h1")
    assert(built.payload.startsWith("[Background task failed] \"deploy step\" (1.2s):"))
    assert(built.payload.contains("git push rejected"))
    assert(built.payload.contains("remote: permission denied"))
    assertEquals(built.metadata("description").flatMap(_.asString), Some("deploy step"))
  }

  test("③b 失败错误超行数同样截尾标注") {
    val err = (1 to 10).map(i => s"at frame-$i").mkString("\n")
    val built = BgNotifyPayload.failed("crashed job", err, None, "job-i1", tailN = 4)
    assert(built.payload.contains("[truncated: showing last 4 of 10 lines"))
    assert(built.payload.contains("at frame-10"))
    assert(!built.payload.contains("at frame-1\n"))
  }

  // ── 耗时文案（前端 formatDuration 同语义）───────────────────────────

  test("formatDuration 三档") {
    assertEquals(BgNotifyPayload.formatDuration(800L), "0.8s")
    assertEquals(BgNotifyPayload.formatDuration(9_990L), "10.0s") // <10s 档一位小数
    assertEquals(BgNotifyPayload.formatDuration(12_000L), "12s")
    assertEquals(BgNotifyPayload.formatDuration(65_000L), "1m 05s")
    assertEquals(BgNotifyPayload.formatDuration(3_723_000L), "1h 02m")
    assertEquals(BgNotifyPayload.formatDuration(0L), "0s")
    assertEquals(BgNotifyPayload.formatDuration(-5L), "0s")
  }
end BgNotifyPayloadSpec
