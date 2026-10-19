package nebflow.core.executor

import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * codex-spawn-fix 批（2026-10-19）验收 · 外部执行器 spawn 钉。
 *
 * 实证背景（调度方手动复现）：codex exec 发现 stdin 可读就
 * 「Reading additional input from stdin...」永等（引擎会话 40 秒变 orphan 的
 * 要因）；引擎三根 std 管全握在手里不关。修法 = spawn 后立即关闭子进程 stdin
 * （[[ExternalSessionRunner.sealChildStdin]]，三适配器统一——exec 模式
 * claude/zcode/codex 任务文本全走 argv，零 stdin 依赖）。
 */
class ExternalSpawnSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  test("sealChildStdin pin: spawned process gets immediate EOF on stdin (cat exits at once)") {
    assume(!ExecutorRegistry.isWindows, "cat pipe semantics — POSIX pin")
    // `cat` 是 stdin 永等的探针：不关 stdin 它永不退出（munit 超时即钉响）；
    // 关闭后读得立即 EOF、自行 exit 0。
    val pb = new java.lang.ProcessBuilder("cat")
    pb.redirectErrorStream(true)
    val p = pb.start()
    ExternalSessionRunner.sealChildStdin(p)
    assert(
      p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS),
      "stdin was not sealed — cat hung waiting for input"
    )
    assertEquals(p.exitValue(), 0)
  }

end ExternalSpawnSpec
