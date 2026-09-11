package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*

/**
 * T2（2026-09-11，Q1 裁定 = 方案 §5 隐含采纳 D5-b）：Bash cwd 不存在 / 非法
 * ⇒ **显式失败**，禁任何形式的静默回退（旧行为回退 `user.home`；「回退到专用
 * scratch 根」同样禁止）。
 *
 * 本 spec 的判据结构 = 「反例 + 正向」成对：
 *   - 反例：cwd 不存在 / 非目录 / 空串 / 相对不存在 ⇒ `InvalidCwdError`，
 *     错误文本含诊断三件套（原始入参 + 解析后绝对路径 + 触发点符号），
 *     且**命令未执行**（不出现命令输出标记——这正是静默回退的伪装面）。
 *   - 正向：合法 cwd（显式目录 / 默认 user.dir）⇒ 命令照常执行，结果 cwd 正确。
 *
 * 验红口径（回退 user.home 旧行为）：反例用例必须红（结果变 `Right` + 命令在
 * home 里跑出标记），正向用例保持绿。
 */
class BashToolSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 60.seconds

  private val Site = "ShellSession.buildProcessBuilder/safeCwd"

  private def newSid(tag: String): String =
    s"cwd-$tag-${java.util.UUID.randomUUID().toString.take(8)}"

  private def missingAbsPath(tag: String): String =
    s"/tmp/nb-t2-cwd-missing-$tag-${java.util.UUID.randomUUID().toString.take(8)}"

  private def absOf(p: String): String = new java.io.File(p).getAbsolutePath

  /** 诊断三件套断言：类型 + 原始入参 + 解析后绝对路径 + 触发点 + 判定依据。
    * `expectReason` = 期望的 reason 片段（does not exist / not a directory / …）。 */
  private def assertDiagnostic(e: Throwable, rawCwd: String, expectReason: String): Unit =
    e match
      case ice: InvalidCwdError =>
        assertEquals(ice.site, Site, "触发点符号必须逐字指向判定点（防漂移）")
        assertEquals(ice.rawCwd, rawCwd, "必须保留原始入参路径")
        val msg = ice.getMessage
        assert(msg.contains(rawCwd), s"错误文本缺原始入参路径: $msg")
        assert(msg.contains(Site), s"错误文本缺触发点符号: $msg")
        assert(msg.contains(expectReason), s"错误文本缺判定依据（$expectReason）: $msg")
        if rawCwd.nonEmpty then
          assert(msg.contains(absOf(rawCwd)), s"错误文本缺解析后绝对路径: $msg")
      case other =>
        fail(s"期望 InvalidCwdError，实得 ${other.getClass.getName}: ${other.getMessage}")

  /** 反例统一入口：会话初 cwd = rawCwd，跑 `echo <marker>`，断言显式失败。 */
  private def assertExplicitFailure(tag: String, rawCwd: String, expectReason: String): IO[Unit] =
    val sid = newSid(tag)
    val marker = s"t2-marker-$tag"
    ShellSession.forSession(sid, initialDir = Some(rawCwd)).flatMap { shell =>
      shell
        .execute(s"echo $marker", 10.seconds)
        .attempt
        .map { res =>
          res match
            case Left(e) => assertDiagnostic(e, rawCwd, expectReason)
            case Right(pr) =>
              // 静默回退的伪装面：命令在别的目录里跑成功了（旧行为 = user.home）。
              fail(
                s"cwd=$rawCwd 必须显式失败，却拿到成功结果（静默回退面）：" +
                  s"exit=${pr.exitCode} stdout=${pr.stdout.trim} stderr=${pr.stderr.trim} cwd=${pr.cwd}；" +
                  s"标记 '$marker' 出现 = 命令在回退目录里执行了"
              )
        }
        .guarantee(ShellSession.destroySession(sid))
    }

  // ── 反例（新增）────────────────────────────────────────────────────────

  test("cwd 不存在 ⇒ 显式失败：InvalidCwdError + 原始入参/绝对路径/触发点三件套") {
    assertExplicitFailure("missing", missingAbsPath("abs"), "does not exist")
  }

  test("cwd 存在但不是目录（普通文件） ⇒ 显式失败：not a directory") {
    val f = Files.createTempFile("nb-t2-cwd-file", ".txt")
    // 注意：assertExplicitFailure 返回的是**惰性 IO**，清理必须挂在 IO 上
    // （用 finally 会在 IO 求值前就删掉文件，反例退化成「路径不存在」）。
    assertExplicitFailure("file", f.toString, "not a directory")
      .guarantee(IO(Files.deleteIfExists(f)).void)
  }

  test("cwd 为空串 ⇒ 显式失败（不视为「用默认目录」）") {
    assertExplicitFailure("empty", "", "empty cwd")
  }

  test("相对 cwd 不存在 ⇒ 显式失败（诊断里的解析后绝对路径 = user.dir 拼接，非裸相对串）") {
    val rel = s"nb-t2-relative-missing-${java.util.UUID.randomUUID().toString.take(8)}"
    assertExplicitFailure("rel", rel, "does not exist").map { _ =>
      assertEquals(
        absOf(rel),
        Paths.get(System.getProperty("user.dir")).resolve(rel).toString,
        "相对 cwd 的解析基准必须是 JVM user.dir"
      )
    }
  }

  test("端到端（BashTool 面）：沙箱根不存在 ⇒ 工具返回可诊断错误（Left），而非静默在别处执行") {
    val sid = newSid("e2e")
    val missingRoot = missingAbsPath("root")
    val ctx = ToolContext(
      projectRoot = "/tmp",
      sessionId = Some(sid),
      sandbox = nebflow.core.sandbox.SandboxPolicy(os.Path(missingRoot), enabled = true)
    )
    val input = JsonObject(
      "command" -> "echo e2e-t2-marker".asJson,
      "description" -> "BashToolSpec e2e cwd".asJson
    )
    BashTool
      .call(input, ctx)
      .map {
        case Left(ToolError(msg)) =>
          assert(msg.contains(missingRoot), s"错误文本缺原始入参路径: $msg")
          assert(msg.contains(absOf(missingRoot)), s"错误文本缺解析后绝对路径: $msg")
          assert(msg.contains(Site), s"错误文本缺触发点符号: $msg")
          assert(!msg.contains("e2e-t2-marker"), s"命令不得执行: $msg")
        case Right(out) =>
          fail(s"cwd 不存在必须显式失败，实得成功输出（静默回退面）: $out")
      }
      .guarantee(ShellSession.destroySession(sid))
  }

  // ── 正向（回归：合法 cwd 仍绿）────────────────────────────────────────

  test("正向：合法 cwd（显式目录）⇒ 命令在该目录执行，结果 cwd = 该目录") {
    val dir = Files.createTempDirectory("nb-t2-cwd-ok")
    // macOS /var → /private/var 符号链接：bash 的 `pwd` 给物理路径，故按 realPath 比对。
    val realDir = dir.toRealPath().toString
    val sid = newSid("ok")
    ShellSession
      .forSession(sid, initialDir = Some(dir.toString))
      .flatMap { shell =>
        shell.execute("echo t2-ok && pwd", 10.seconds).map { pr =>
          assertEquals(pr.exitCode, 0, s"合法 cwd 必须照常执行: ${pr.stderr}")
          assert(pr.stdout.contains("t2-ok"), s"命令输出缺失: ${pr.stdout}")
          assert(pr.stdout.contains(realDir), s"命令必须跑在指定目录: ${pr.stdout}")
          assertEquals(pr.cwd, realDir, s"结果 cwd 必须是指定目录: ${pr.cwd}")
        }
      }
      .guarantee(ShellSession.destroySession(sid))
      .guarantee(IO(Files.deleteIfExists(dir)).void)
  }

  test("正向：默认会话（无 initialDir ⇒ JVM user.dir）仍绿——存量路径零改动") {
    val sid = newSid("default")
    ShellSession
      .forSession(sid)
      .flatMap { shell =>
        shell.execute("echo t2-default-ok", 10.seconds).map { pr =>
          assertEquals(pr.exitCode, 0, s"默认 cwd 必须照常执行: ${pr.stderr}")
          assert(pr.stdout.contains("t2-default-ok"), s"命令输出缺失: ${pr.stdout}")
          assertEquals(pr.cwd, System.getProperty("user.dir"), s"默认 cwd = user.dir: ${pr.cwd}")
        }
      }
      .guarantee(ShellSession.destroySession(sid))
  }

end BashToolSpec
