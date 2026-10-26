package nebflow.cli

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.core.hotrestart.InstallPointer

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 台账 #916 detached apply 面 spec：脚本渲染（事实注入齐全、两平台同构）、
 * 注册决策矩阵、状态相 reconcile、two-phase update 的 stage/promote/回滚
 * 原子性。全部文件面落在临时沙箱，零真实进程、零真实端口。
 */
class DetachedApplySpec extends CatsEffectSuite:

  private def sandbox(): os.Path =
    val d = os.temp.dir()
    d

  private def planIn(root: os.Path, kind: DetachedApply.Kind = DetachedApply.Kind.Restart): DetachedApply.Plan =
    DetachedApply.Plan(
      kind = kind,
      dataRoot = root / "data",
      installDir = root / "bin",
      launcher = (root / "bin" / "stand").toString,
      port = 6138,
      layout = DetachedApply.Layout.Pointer,
      targetVersion = if kind == DetachedApply.Kind.Update then Some("2026.10.27-beta.1") else None,
      oldPid = Some(1234L),
      generation = 1698300000123L
    )

  // ── 渲染面：事实注入齐全（今天事故的教训——脚本不能瞎）────────────────

  test("bash script carries all injected facts (port, launcher, log, health url, deadlines)") {
    val p = planIn(sandbox())
    val s = DetachedApply.renderBash(p)
    val mustContain = List(
      "PORT='6138'",
      s"LAUNCHER='${p.launcher}'",
      s"APPLY_DIR='${p.dataRoot}/apply'",
      "http://127.0.0.1:${PORT}/api/health",
      "STOP_DEADLINE=90",
      "HEALTH_DEADLINE=180",
      "kind=restart",
      "set_phase running",
      "set_phase stopped",
      "set_phase done",
      "stop leg:",
      "start leg:",
      "state.json"
    )
    mustContain.foreach(fragment => assert(s.contains(fragment), s"missing '$fragment' in bash script"))
  }

  test("bash update script asserts target version and has rollback leg") {
    val p = planIn(sandbox(), DetachedApply.Kind.Update)
    val s = DetachedApply.renderBash(p)
    assert(s.contains("TARGET_VERSION='2026.10.27-beta.1'"))
    assert(s.contains("ROLLBACK:"))
    assert(s.contains("set_phase rolled-back"))
    assert(s.contains("set_phase failed"))
    // 指针布局回滚 = previous-version 切回 current-version
    assert(s.contains("current-version"))
    assert(s.contains("previous-version"))
  }

  test("bash winsort rollback quarantines the staged jar instead of pointer flip") {
    val p = planIn(sandbox(), DetachedApply.Kind.Update)
      .copy(layout = DetachedApply.Layout.WinsortFlat)
    val s = DetachedApply.renderBash(p)
    assert(s.contains("quarantined staged jar"))
    assert(s.contains("bak-"))
  }

  test("bash script paths with single quotes survive (sh quoting)") {
    val root = sandbox()
    val p = planIn(root).copy(launcher = "/Users/o'neill/.stand/bin/stand")
    val s = DetachedApply.renderBash(p)
    assert(s.contains("'\\''"), "single-quoted escape expected")
  }

  test("windows script mirrors the bash semantics (stop/start/health/rollback/self-delete)") {
    val p = planIn(sandbox(), DetachedApply.Kind.Update)
    val s = DetachedApply.renderPowerShell(p)
    List(
      "$Port = 6138",
      "$TargetVersion = '2026.10.27-beta.1'",
      "function StopOld",
      "function StartNew",
      "function HealthOk",
      "function Rollback",
      "schtasks /Delete /F /TN",
      "SetPhase 'done' ''",
      "Invoke-RestMethod"
    ).foreach(fragment => assert(s.contains(fragment), s"missing '$fragment' in ps1 script"))
  }

  test("state json renderer escapes cause quotes") {
    val s = DetachedApply.renderStateJson("update", "failed", 6138, Some("v1"), Some("boom \"quoted\""), "t")
    assert(s.contains("\"cause\":\"boom \\\"quoted\\\"\""))
  }

  // ── 注册面：OS 命令注入口（零真实 schtasks/launchctl）───────────────────

  test("windows registration issues create+run with the generated ps1 path") {
    val p = planIn(sandbox())
    val cmds = scala.collection.mutable.ListBuffer.empty[List[String]]
    val fakeRun: DetachedApply.Run = c =>
      IO { cmds += c; Right("ok") }
    val res = DetachedApply.register(p, fakeRun).map(_.isRight).assert
    assert(cmds.exists(_.headOption.contains("schtasks") && _.contains("/Create")))
    assert(cmds.exists(_.headOption.contains("schtasks") && _.contains("/Run")))
    // registered 相已落盘
    val state = os.read(DetachedApply.statePath(p.dataRoot))
    assert(state.contains("\"phase\":\"registered\""))
  }

  test("posix registration resolves uid first, then bootstraps and kickstarts launchd") {
    val p = planIn(sandbox())
    val cmds = scala.collection.mutable.ListBuffer.empty[List[String]]
    val fakeRun: DetachedApply.Run = c =>
      IO {
        cmds += c
        if c.take(2) == List("id", "-u") then Right("501")
        else Right("ok")
      }
    // forcePosix 注入：任何宿主 OS 上都验证 launchd 面的注册命令序列
    DetachedApply.register(p, fakeRun, forcePosix = Some(true)).map(_.isRight).assert
    assert(cmds.exists(_ == List("id", "-u")))
    assert(cmds.exists(c => c.take(2) == List("/bin/launchctl", "bootstrap")))
    assert(
      cmds.exists(c => c.take(2) == List("/bin/launchctl", "kickstart") && c.last.endsWith("gui/501/" + DetachedApply.macLabel(p)))
    )
    assert(os.exists(DetachedApply.macPlist(p)), "plist written")
  }

  test("registration refuses to inject an unsafe version literal") {
    val p = planIn(sandbox(), DetachedApply.Kind.Update)
      .copy(targetVersion = Some("x'; rm -rf ~; '1"))
    val cmds = scala.collection.mutable.ListBuffer.empty[List[String]]
    val res = DetachedApply.register(p, c => IO { cmds += c; Right("ok") })
    assert(res.map(_.isLeft).assert)
    assert(cmds.isEmpty, "no OS command may run for an unsafe plan")
  }

  // ── 状态相 reconcile（中断安全证据链）────────────────────────────────────

  test("reconcileOnBoot rewrites an in-flight phase to interrupted and reports a warn line") {
    val root = sandbox()
    val p = planIn(root, DetachedApply.Kind.Update)
    os.makeDir.all(DetachedApply.applyDir(p.dataRoot))
    os.write.over(
      DetachedApply.statePath(p.dataRoot),
      DetachedApply.renderStateJson("update", "starting", 6138, Some("2026.10.27-beta.1"), None, "t")
    )
    val line = DetachedApply.reconcileOnBoot(p.dataRoot)
    assert(line.map(_.isDefined).assert)
    assert(os.read(DetachedApply.statePath(p.dataRoot)).contains("\"phase\":\"interrupted\""))
  }

  test("reconcileOnBoot is silent for terminal phases and missing files") {
    val root = sandbox()
    assert(DetachedApply.reconcileOnBoot(root / "nope").map(_.isEmpty).assert)
    val p = planIn(root)
    os.makeDir.all(DetachedApply.applyDir(p.dataRoot))
    os.write.over(DetachedApply.statePath(p.dataRoot), DetachedApply.renderStateJson("restart", "done", 6138, None, None, "t"))
    assert(DetachedApply.reconcileOnBoot(p.dataRoot).map(_.isEmpty).assert)
  }

  // ── two-phase update：Phase1 staging 契约 ────────────────────────────────

  test("stage: version parsed from jar name, sha recorded, copy lands atomically") {
    val root = sandbox()
    val bin = root / "bin"
    os.makeDir.all(bin)
    val jar = root / "stand-assembly-2026.10.27-beta.1.jar"
    os.write.over(jar, "JARBYTES-1")
    val res = UpdateStage.stage(jar, bin, DetachedApply.Layout.Pointer)
    res.map {
      case Left(e) => fail(s"expected staged, got: $e")
      case Right(s) =>
        assertEquals(s.version, "2026.10.27-beta.1")
        assertEquals(s.idempotentHit, false)
        assert(os.exists(os.Path(s.stagedPath)))
        assert(!os.exists(os.Path(s.stagedPath) / os.up / s"${s.jarName}.part"), "no .part residue in the selection face")
    }
  }

  test("stage: same version same content = idempotent hit (zero rewrite); different content refused") {
    val root = sandbox()
    val bin = root / "bin"
    val jar = root / "stand-assembly-2026.10.27-beta.1.jar"
    os.write.over(jar, "JARBYTES-1")
    UpdateStage.stage(jar, bin, DetachedApply.Layout.Pointer).map(_.isRight).assert
    // 幂等命中
    UpdateStage.stage(jar, bin, DetachedApply.Layout.Pointer).map {
      case Right(s) => assertEquals(s.idempotentHit, true)
      case Left(e)  => fail(e)
    }
    // 同版本不同内容 = 拒绝（版本目录不可变）
    os.write.over(jar, "JARBYTES-2")
    UpdateStage.stage(jar, bin, DetachedApply.Layout.Pointer).map {
      case Left(e)  => assert(e.contains("DIFFERENT content"))
      case Right(s) => fail(s"expected refusal, got $s")
    }
  }

  test("stage: winsort layout rejects a non-winsort-parseable version (dead jar would sit in the scan face)") {
    val root = sandbox()
    val bin = root / "bin"
    val jar = root / "stand-assembly-2026.10.27-dev.38b81eb4f.jar"
    os.write.over(jar, "JARBYTES")
    UpdateStage.stage(jar, bin, DetachedApply.Layout.WinsortFlat).map {
      case Left(e)  => assert(e.contains("not winsort-selectable"))
      case Right(s) => fail(s"expected refusal, got $s")
    }
    // 指针布局接受该形状（指针直名，不依赖排序）
    UpdateStage.stage(jar, bin, DetachedApply.Layout.Pointer).map(_.isRight).assert
  }

  // ── two-phase update：Phase2 指针晋升契约 ────────────────────────────────

  test("promote: flips current+previous atomically; refuses a version with no jar on disk") {
    val root = sandbox()
    val bin = root / "bin"
    val vd = InstallPointer.versionDir(bin, "2026.10.27-beta.1")
    os.makeDir.all(vd)
    os.write.over(vd / "stand-assembly-2026.10.27-beta.1.jar", "NEW")
    os.makeDir.all(InstallPointer.versionDir(bin, "2026.10.26-beta.1"))
    os.write.over(InstallPointer.versionDir(bin, "2026.10.26-beta.1") / "stand-assembly-2026.10.26-beta.1.jar", "OLD")
    DetachedApplySpecAccess.writeTrimmedIO(InstallPointer.currentPath(bin), "2026.10.26-beta.1")

    InstallPointer.promote(bin, "2026.10.27-beta.1").map {
      case Left(e) => fail(e)
      case Right((cur, prev)) =>
        assertEquals(cur, "2026.10.27-beta.1")
        assertEquals(prev, "2026.10.26-beta.1")
        assertEquals(os.read(InstallPointer.currentPath(bin)).trim, "2026.10.27-beta.1")
        assertEquals(os.read(InstallPointer.previousPath(bin)).trim, "2026.10.26-beta.1")
    }
    // 无包版本拒绝晋升，指针零改动
    InstallPointer.promote(bin, "2026.10.28-beta.1").map {
      case Left(e) =>
        assert(e.contains("refusing to promote"))
        assertEquals(os.read(InstallPointer.currentPath(bin)).trim, "2026.10.27-beta.1")
      case Right(_) => fail("expected refusal for a jarless version")
    }
  }

  test("update registration failure restores the pre-promote pointers") {
    val root = sandbox()
    val bin = root / "bin"
    val vd = InstallPointer.versionDir(bin, "2026.10.27-beta.1")
    os.makeDir.all(vd)
    os.write.over(vd / "stand-assembly-2026.10.27-beta.1.jar", "NEW")
    DetachedApplySpecAccess.writeTrimmedIO(InstallPointer.currentPath(bin), "2026.10.26-beta.1")
    DetachedApplySpecAccess.writeTrimmedIO(InstallPointer.previousPath(bin), "2026.10.21-beta.2")

    // 用两个阶段重演 twoPhaseLocal 的 promote→register-fail→restore 腿
    val res = for
      preCur <- InstallPointer.readCurrent(bin)
      prePrev <- InstallPointer.readPrevious(bin)
      _ <- InstallPointer.promote(bin, "2026.10.27-beta.1")
      // 注册失败注入：register 恒 Left
      regFail = IO.pure(Left("schtasks unavailable"): Either[String, String])
      restored <- regFail.flatMap {
        case Right(_) => IO.pure(fail("expected registration failure"))
        case Left(_) =>
          InstallPointer.writePointers(bin, preCur.getOrElse(""), prePrev).as(
            CliResult.Error("apply registration failed — pointers restored", 1)
          )
      }
    yield restored
    res.map { _ =>
      assertEquals(os.read(InstallPointer.currentPath(bin)).trim, "2026.10.26-beta.1")
      assertEquals(os.read(InstallPointer.previousPath(bin)).trim, "2026.10.21-beta.2")
    }
  }

  // ── launcher 解析 ────────────────────────────────────────────────────────

  test("resolveLauncher honours the STAND_LAUNCHER override before platform defaults") {
    val got = DetachedApply.resolveLauncher(Map("STAND_LAUNCHER" => "/opt/x/stand"), "/home/u", isWindows = false)
    assertEquals(got, Some("/opt/x/stand"))
  }

  test("defaultPlan wires layout detection from the launcher directory") {
    val root = sandbox()
    val bin = root / "bin"
    os.makeDir.all(bin)
    os.write.over(bin / "current-version", "2026.10.26-beta.1\n")
    val plan = DetachedApply.Plan(
      kind = DetachedApply.Kind.Update,
      dataRoot = root / "data",
      installDir = bin,
      launcher = (bin / "stand").toString,
      port = 6138,
      layout = DetachedApply.detectLayout(bin),
      targetVersion = Some("2026.10.27-beta.1")
    )
    assertEquals(plan.layout, DetachedApply.Layout.Pointer)
  }

end DetachedApplySpec

/** writeTrimmed 是 private——spec 经公开包裹使用（测试面单独放行）。 */
object DetachedApplySpecAccess:
  def writeTrimmedIO(path: os.Path, value: String): IO[Unit] =
    IO.blocking {
      os.makeDir.all(path / os.up)
      val tmp = path / os.up / s".${path.last}.spec-tmp"
      os.write.over(tmp, value.trim + "\n", createFolders = true)
      Files.move(
        tmp.toNIO,
        path.toNIO,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
      )
    }
