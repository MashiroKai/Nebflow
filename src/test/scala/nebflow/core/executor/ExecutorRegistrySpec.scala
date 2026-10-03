package nebflow.core.executor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.service.ConfigService
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * executor-registry 批（2026-10-03）验收：检测核、配置热读宽容度、
 * effectiveDefault 的兜底方向、以及 setExecutorSection 的白名单/值校验。
 *
 * 中心性质：**内置执行器是零依赖兜底面**——无论配置怎么写（缺块、坏形、
 * 未知 id、adapter 未就绪、disabled），effectiveDefault 永远落在可用的
 * `nebflow` 上；且检测面对坏配置绝不抛出。
 */
class ExecutorRegistrySpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-exec-reg-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  // ── 检测核（纯函数；用带假二进制的临时目录驱动，不依赖宿主安装）─────────

  private def withFakeBin[A](name: String)(f: os.Path => A): A =
    val dir = os.temp.dir(prefix = "nb-exec-bin-")
    os.write(dir / name, "#!/bin/sh\n")
    os.perms.set(dir / name, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    try f(dir)
    finally os.remove.all(dir)

  test("resolveBinFrom: PATH leg hits an existing file") {
    withFakeBin("zcode") { dir =>
      val hit = ExecutorRegistry.resolveBinFrom(pathEnv = dir.toString, probeDirs = Nil, binName = "zcode", windows = false)
      assertEquals(hit, Some((dir / "zcode").toString))
    }
  }

  test("resolveBinFrom: probe-dir leg fires when PATH misses") {
    withFakeBin("zcode") { dir =>
      val hit = ExecutorRegistry.resolveBinFrom(pathEnv = "/nonexistent-legacy-path", probeDirs = List(dir.toString), binName = "zcode", windows = false)
      assertEquals(hit, Some((dir / "zcode").toString))
    }
  }

  test("resolveBinFrom: total miss is None, never throws") {
    val miss = ExecutorRegistry.resolveBinFrom(pathEnv = "", probeDirs = List("/nonexistent-a", "/nonexistent-b"), binName = "definitely-not-a-bin-xyz", windows = false)
    assertEquals(miss, None)
  }

  test("resolveBinFrom: windows form probes .exe/.cmd suffixes") {
    withFakeBin("zcode.cmd") { dir =>
      val hit = ExecutorRegistry.resolveBinFrom(pathEnv = dir.toString, probeDirs = Nil, binName = "zcode", windows = true)
      assertEquals(hit, Some((dir / "zcode.cmd").toString))
      // 非 windows 形态不吃 .cmd
      val nix = ExecutorRegistry.resolveBinFrom(pathEnv = dir.toString, probeDirs = Nil, binName = "zcode", windows = false)
      assertEquals(nix, None)
    }
  }

  test("detect: built-in executor is always Some(built-in) regardless of environment") {
    val defn = ExecutorRegistry.defs.find(_.id == "nebflow").get
    assertEquals(ExecutorRegistry.detect(defn, pathEnv = "", probeDirs = Nil), Some("(built-in)"))
  }

  // ── 配置热读宽容度 ────────────────────────────────────────────────────

  test("readSettings: no config file degrades to defaults") {
    withTempDataRoot { tmp =>
      IO {
        assertEquals(ExecutorRegistry.readSettings, ExecutorRegistry.Settings(None, Nil))
      }
    }.unsafeRunSync()
  }

  test("readSettings: partial and malformed blocks degrade without throwing") {
    withTempDataRoot { tmp =>
      IO {
        os.write(tmp / "nebflow.json", "not-an-object")
        assertEquals(ExecutorRegistry.readSettings, ExecutorRegistry.Settings(None, Nil))
      }
    }.unsafeRunSync()
  }

  test("readSettings: reads default and disabled from the executor block") {
    withTempDataRoot { tmp =>
      IO {
        os.write(
          tmp / "nebflow.json",
          """{"executor": {"default": "codex", "disabled": ["hermes"]}}"""
        )
        assertEquals(ExecutorRegistry.readSettings, ExecutorRegistry.Settings(Some("codex"), List("hermes")))
      }
    }.unsafeRunSync()
  }

  // ── effectiveDefault：兜底方向 ────────────────────────────────────────

  test("effectiveDefault: unset falls back to nebflow") {
    assertEquals(ExecutorRegistry.effectiveDefault(ExecutorRegistry.Settings(None, Nil)), "nebflow")
  }

  test("effectiveDefault: unknown id and disabled-id and not-adapterReady id all fall back to nebflow") {
    val unknown = ExecutorRegistry.effectiveDefault(ExecutorRegistry.Settings(Some("no-such-executor"), Nil))
    assertEquals(unknown, "nebflow")

    val disabled = ExecutorRegistry.effectiveDefault(ExecutorRegistry.Settings(Some("codex"), List("codex")))
    assertEquals(disabled, "nebflow")

    val notReady = ExecutorRegistry.effectiveDefault(ExecutorRegistry.Settings(Some("hermes"), Nil))
    assertEquals(notReady, "nebflow")
  }

  test("effectiveDefault: a valid adapter-ready external id is honored") {
    val ok = ExecutorRegistry.effectiveDefault(ExecutorRegistry.Settings(Some("claude-code"), List("hermes")))
    assertEquals(ok, "claude-code")
  }

  // ── snapshot：内置面恒可用 ────────────────────────────────────────────

  test("snapshot: built-in is detected+enabled+default even with hostile settings") {
    val rows = ExecutorRegistry.snapshot(pathEnv = "", probeDirs = Nil)
    val builtin = rows.find(_.id == "nebflow").get
    assertEquals(builtin.detected, true)
    assertEquals(builtin.enabled, true)
    assertEquals(builtin.isDefault, true)

    // 外部执行器在空 PATH 下未检测到,但 adapterReady 的仍标 enabled（可选,
    // 派发时才因缺二进制失败——检测态与可选态是两个面）。
    val zcode = rows.find(_.id == "zcode").get
    assertEquals(zcode.detected, false)
    assertEquals(zcode.enabled, true)
  }

  // ── setExecutorSection：白名单与值校验 ────────────────────────────────

  test("setExecutorSection: unknown key / unknown default / builtin-disable are all refused") {
    ConfigService.setExecutorSection(Map("apiKey" -> "x".asJson)).map {
      case Left(err) => assert(err.contains("unknown key"))
      case other     => fail(s"expected refusal, got $other")
    }
    ConfigService.setExecutorSection(Map("default" -> "no-such".asJson)).map {
      case Left(err) => assert(err.contains("unknown default"))
      case other     => fail(s"expected refusal, got $other")
    }
    ConfigService.setExecutorSection(Map("disabled" -> List("nebflow").asJson)).map {
      case Left(err) => assert(err.contains("cannot be disabled"))
      case other     => fail(s"expected refusal, got $other")
    }
  }

  test("setExecutorSection: valid write lands in nebflow.json and readSettings observes it") {
    withTempDataRoot { tmp =>
      for
        _ <- ConfigService.setExecutorSection(Map("default" -> "codex".asJson, "disabled" -> List("hermes").asJson))
        read <- IO(ExecutorRegistry.readSettings)
      yield
        assertEquals(read, ExecutorRegistry.Settings(Some("codex"), List("hermes")))
        assertEquals(ExecutorRegistry.effectiveDefault, "codex")
    }
  }
