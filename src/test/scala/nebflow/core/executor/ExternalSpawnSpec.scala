package nebflow.core.executor

import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * codex-spawn-fix 批（2026-10-19）验收 · 外部执行器 spawn 两钉。
 *
 * 实证背景（调度方手动复现）：codex 冒烟三连败的两大死因——
 *   ① 网络：codex exec 不带代理无法达 OpenAI（挂死）；引擎 spawn 不注入代理
 *     环境。修法 = nebflow.json `externalExecutors.proxyEnv` 点名键值注入
 *     （[[ExternalSpawnConfig]]），缺省零注入、绝不扩大透传。
 *   ② stdin 挂起：codex exec 发现 stdin 可读就永等 input；引擎三根 std 管全握
 *     在手里不关。修法 = spawn 后立即关闭子进程 stdin
 *     （[[ExternalSessionRunner.sealChildStdin]]，三适配器统一——exec 模式
 *     claude/zcode/codex 任务文本全走 argv，零 stdin 依赖）。
 */
class ExternalSpawnSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def withTempDataRoot[A](f: os.Path => A): A =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-external-spawn-")
    PathUtil.setDataRoot(tmp)
    try f(tmp)
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(tmp)

  // ── 钉①：代理注入（点名注入 / 缺省零注入 / 坏形宽容）─────────────────────

  test("proxyEnv pin: config-named keys injected verbatim; absent config = zero injection") {
    withTempDataRoot { tmp =>
      // 缺省（无配置文件）= 零注入：构造面不新增任何代理键。
      assertEquals(ExternalSpawnConfig.proxyEnv, Map.empty)
      val pb0 =
        ExternalSessionRunner.configureProcessBuilder("/bin/echo", tmp.toString, List("hi"))
      assertEquals(pb0.environment().get("NEBFLOW_SPAWN_SENTINEL"), null)

      // 点名两键 ⇒ 逐字注入（http_proxy/https_proxy，SMOKE-OK 实测同款）。
      os.write.over(
        PathUtil.configJsonReadPath(tmp),
        """{"externalExecutors": {"proxyEnv": {"http_proxy": "http://127.0.0.1:7890", "https_proxy": "http://127.0.0.1:7890"}}}"""
      )
      assertEquals(
        ExternalSpawnConfig.proxyEnv,
        Map("http_proxy" -> "http://127.0.0.1:7890", "https_proxy" -> "http://127.0.0.1:7890")
      )
      val pb1 =
        ExternalSessionRunner.configureProcessBuilder("/bin/echo", tmp.toString, List("hi"))
      assertEquals(pb1.environment().get("http_proxy"), "http://127.0.0.1:7890")
      assertEquals(pb1.environment().get("https_proxy"), "http://127.0.0.1:7890")
      // 配置未点名的键绝不新增（不扩大透传）。
      assertEquals(pb1.environment().get("NEBFLOW_SPAWN_SENTINEL"), null)
    }
  }

  test("proxyEnv reader pin: malformed / wrong-shape / blank keys degrade to partial-or-empty, never throw") {
    withTempDataRoot { tmp =>
      os.write.over(PathUtil.configJsonReadPath(tmp), "{not json")
      assertEquals(ExternalSpawnConfig.proxyEnv, Map.empty)
      os.write.over(PathUtil.configJsonReadPath(tmp), """{"externalExecutors": {"proxyEnv": "oops"}}""")
      assertEquals(ExternalSpawnConfig.proxyEnv, Map.empty)
      // 非字符串值与空键名丢弃，合法键保留。
      os.write.over(
        PathUtil.configJsonReadPath(tmp),
        """{"externalExecutors": {"proxyEnv": {"good": "v", "bad": 42, "": "blank-key"}}}"""
      )
      assertEquals(ExternalSpawnConfig.proxyEnv, Map("good" -> "v"))
    }
  }

  // ── 钉②：spawn 后 stdin 立即关闭 ────────────────────────────────────────

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
