package nebflow.core.hotrestart

import cats.effect.IO
import cats.effect.unsafe.implicits.global

/**
 * 四档健康自检 + 指针化回滚的定向 spec（hotupdate 批 2 G3/G4）。
 *
 * 覆盖面（逐条对判据）：
 *  - 四档**逐档独立**红/绿/未取证第三态（含真 socket + 真 HTTP + 真载荷的第三/四档，
 *    经 [[ProbeEndpoint]] 在 loopback 临时端口上供既有健康载荷）；
 *  - 第四档期望版本的真值取序（安装指针优先、回执包路径兜底）；
 *  - 指针布局的读/切/包定位（[[InstallPointer]]）；
 *  - 回滚动作：指针切回上一版 + **只替换包路径**的复起（[[nebflow.core.hotupdate.Rollback]]）。
 *
 * 纪律：全部用临时目录 + 临时 loopback 端口；**不触真实安装目录、不 spawn 真进程**
 * （`spawnPrevious` 注入）。
 */
class HealthGateSpec extends munit.CatsEffectSuite:

  private val monitor = nebflow.llm.ProviderHealthMonitor(null) // registry not needed for state queries (既有 spec 同款)
  private val version = nebflow.Version.string

  /** Either[String, A] → IO[A]（spec 失败即断言失败，不静默）。 */
  private def orDie[A](label: String)(e: Either[String, A]): IO[A] =
    e.fold(err => IO.raiseError(new AssertionError(s"$label: $err")), IO.pure)

  private def tmpDir(prefix: String): IO[os.Path] =
    IO.blocking(os.temp.dir(prefix = prefix))

  private def writeIntentFile(dir: os.Path, intent: HotRestartIntent): IO[os.Path] =
    val p = dir / "intent.json"
    SuccessorGate.writeIntent(p, intent).as(p)

  private def mkIntent(
    probePort: Option[Int],
    phase: String,
    failure: Option[String] = None,
    spawnCmd: String = ""
  ): HotRestartIntent =
    HotRestartIntent(
      generation = System.currentTimeMillis(),
      oldPid = 4242L,
      host = "0.0.0.0",
      port = 8098,
      home = "/tmp/none",
      form = "jar",
      spawnCmd = spawnCmd,
      triggerSource = "spec",
      phase = phase,
      ts = System.currentTimeMillis(),
      failure = failure,
      probePort = probePort
    )

  private def atDoor(
    intentPath: os.Path,
    expected: Option[String],
    alive: Boolean = true,
    connectProbe: Option[Int => IO[Boolean]] =
      Some(p => IO.blocking(nebflow.cli.SingleInstanceGuard.connectProbeAccepted(p)))
  ): IO[HealthReport] =
    HealthCheck.atDoor(
      isAlive = IO.pure(alive),
      readIntent = SuccessorGate.readIntent(intentPath),
      expected = expected,
      t1HoldMs = 60L,
      t2DeadlineMs = 300L,
      t3DeadlineMs = 400L,
      t4DeadlineMs = 300L,
      pollMs = 20L,
      connectProbe = connectProbe
    )

  // ── 第三/四档：真 socket + 真 HTTP + 真载荷 ──────────────────────────

  test("tier 3+4 green (real socket/HTTP/payload): probe endpoint serves the health payload; version matches") {
    for
      dir <- tmpDir("nb-health-a")
      ep <- ProbeEndpoint.start(monitor).flatMap(orDie("probe endpoint up"))
      // 取证：健康载荷含版本号（设计 §12 未取证项 #2 的答案）+ 路径与既有端点一致
      payload <- HealthHttp.fetch("127.0.0.1", ep.port, 2000).flatMap(orDie("health fetch"))
      _ = assertEquals(HealthPayload.versionOf(payload), Some(version), "health payload must carry the version field")
      _ = assert(payload.hcursor.downField("product").as[String].toOption.contains("nebflow"))
      _ = assertEquals(
        ProbeEndpoint.Path,
        "/api/health",
        "single health path for both the real endpoint and the door probe"
      )
      p <- writeIntentFile(dir, mkIntent(Some(ep.port), "readyToBind"))
      report <- atDoor(p, Some(version))
      _ = assertEquals(report.tierWire(HealthTier.SuccessorAlive), Some("pass"))
      _ = assertEquals(report.tierWire(HealthTier.DoorReceipt), Some("pass"))
      _ = assertEquals(report.tierWire(HealthTier.PortServing), Some("pass"))
      _ = assertEquals(report.tierWire(HealthTier.VersionMatch), Some("pass"))
      _ = assert(report.isHealthy)
      _ = assert(report.detail.contains("port-serving=pass"), report.detail)
      _ <- ep.stop
    yield ()
  }

  test("tier 4 red: served version != expected version ⇒ version-match fails (gate unhealthy)") {
    for
      dir <- tmpDir("nb-health-b")
      ep <- ProbeEndpoint.start(monitor).flatMap(orDie("probe endpoint up"))
      p <- writeIntentFile(dir, mkIntent(Some(ep.port), "readyToBind"))
      report <- atDoor(p, Some("0.0.0-not-the-served-one"))
      _ = assertEquals(report.tierWire(HealthTier.PortServing), Some("pass"))
      _ = assertEquals(report.tierWire(HealthTier.VersionMatch), Some("fail"))
      _ = assert(!report.isHealthy, "a version mismatch must make the gate unhealthy")
      _ = assert(report.failed.exists(_.tier == HealthTier.VersionMatch))
      _ <- ep.stop
    yield ()
  }

  test("tier 3 red: announced probe port with nothing listening ⇒ port-serving fails after its own deadline") {
    for
      dir <- tmpDir("nb-health-c")
      // 先占一个端口再关掉：拿到一个「本机确定无监听」的端口号
      dead <- IO.blocking {
        val s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val p = s.getLocalPort
        s.close()
        p
      }
      p <- writeIntentFile(dir, mkIntent(Some(dead), "readyToBind"))
      report <- atDoor(p, None)
      _ = assertEquals(report.tierWire(HealthTier.PortServing), Some("fail"))
      _ = assertEquals(
        report.tierWire(HealthTier.VersionMatch),
        Some("unverified"),
        "later tiers are skipped once one fails"
      )
      _ = assert(!report.isHealthy)
    yield ()
  }

  test(
    "legacy successor (no probePort announced) ⇒ tiers 3/4 unverified, and the gate stays healthy (declared, not silent)"
  ) {
    for
      dir <- tmpDir("nb-health-d")
      p <- writeIntentFile(dir, mkIntent(None, "readyToBind"))
      report <- atDoor(p, Some(version))
      _ = assertEquals(report.tierWire(HealthTier.SuccessorAlive), Some("pass"))
      _ = assertEquals(report.tierWire(HealthTier.DoorReceipt), Some("pass"))
      _ = assertEquals(report.tierWire(HealthTier.PortServing), Some("unverified"))
      _ = assertEquals(report.tierWire(HealthTier.VersionMatch), Some("unverified"))
      _ = assert(
        report.isHealthy,
        "unverified is a third state: it never pretends to be a pass, and never blocks the existing handover path"
      )
      _ = assert(report.unverified.size == 2 && report.unverified.forall(_.detail.nonEmpty))
    yield ()
  }

  test("tier 2 red: successor reported failure ⇒ door receipt fails with the recorded reason (no timeout guessing)") {
    for
      dir <- tmpDir("nb-health-e")
      p <- writeIntentFile(dir, mkIntent(None, "failed", failure = Some("zone A boot error: boom")))
      report <- atDoor(p, None)
      _ = assertEquals(report.tierWire(HealthTier.DoorReceipt), Some("fail"))
      _ = assert(report.failed.exists(_.detail.contains("zone A boot error: boom")), report.detail)
      _ = assert(!report.isHealthy)
    yield ()
  }

  test("tier 1 red: successor dies inside the liveness window ⇒ alive tier fails and later tiers are skipped") {
    for
      dir <- tmpDir("nb-health-f")
      p <- writeIntentFile(dir, mkIntent(Some(1), "readyToBind"))
      report <- atDoor(p, None, alive = false)
      _ = assertEquals(report.tierWire(HealthTier.SuccessorAlive), Some("fail"))
      _ = assertEquals(report.tierWire(HealthTier.DoorReceipt), Some("unverified"))
      _ = assert(!report.isHealthy)
    yield ()
  }

  test("port-probe capability not wired ⇒ tiers 3/4 unverified (never a silent pass, never a false fail)") {
    for
      dir <- tmpDir("nb-health-g")
      p <- writeIntentFile(dir, mkIntent(Some(65535), "readyToBind"))
      report <- atDoor(p, Some(version), connectProbe = None)
      _ = assertEquals(report.tierWire(HealthTier.PortServing), Some("unverified"))
      _ = assert(report.isHealthy)
      _ = assert(report.detail.contains("not wired"), report.detail)
    yield ()
  }

  // ── 期望版本真值取序（第四档的判据来源）─────────────────────────────

  test("expected version: the install pointer wins; otherwise the package path in the successor's spawn command") {
    // unsafeRunSync: the assertions inside IO must actually execute (a bare IO
    // value would be returned unevaluated and the test would be vacuous).
    IO {
      assertEquals(
        HealthCheck.expectedVersion(Some("2026.9.19"), Some("java -jar /x/nebflow-assembly-2026.1.1.jar start")),
        Some("2026.9.19")
      )
      assertEquals(
        HealthCheck.expectedVersion(None, Some("java -jar /x/nebflow-assembly-2026.1.1.jar start")),
        Some("2026.1.1")
      )
      assertEquals(
        HealthCheck.expectedVersion(Some("  "), Some("-jar /x/nebflow-assembly-1.4.1-beta.56.jar")),
        Some("1.4.1-beta.56")
      )
      assertEquals(HealthCheck.expectedVersion(None, Some("cannot resolve run JAR (sbt run / dev classpath?)")), None)
      assertEquals(HealthCheck.expectedVersion(None, None), None)
    }.unsafeRunSync()
  }

  // ── 指针布局（G4 盘上机制）───────────────────────────────────────────

  private def mkInstallDir(
    prefix: String,
    current: String,
    previous: Option[String],
    versions: List[String]
  ): IO[os.Path] =
    for
      dir <- tmpDir(prefix)
      _ <- IO.blocking {
        versions.foreach { v =>
          val vd = dir / InstallPointer.VersionsDirName / v
          os.makeDir.all(vd)
          os.write(vd / InstallPointer.jarName(v), s"jar-$v", createFolders = true)
        }
        os.write(dir / InstallPointer.CurrentFileName, current + "\n", createFolders = true)
        previous.foreach(p => os.write(dir / InstallPointer.PreviousFileName, p + "\n"))
      }
    yield dir

  test("install pointer: read / flip / retained-package lookup (and the missing-pointer branches)") {
    for
      dir <- mkInstallDir("nb-ptr-a", "2026.9.20", Some("2026.9.19"), List("2026.9.19", "2026.9.20"))
      cur <- InstallPointer.readCurrent(dir)
      prev <- InstallPointer.readPrevious(dir)
      _ = assertEquals(cur, Some("2026.9.20"))
      _ = assertEquals(prev, Some("2026.9.19"))
      jar <- InstallPointer.jarIn(dir, "2026.9.19").flatMap(orDie("retained package"))
      _ = assert(jar.endsWith("versions/2026.9.19/nebflow-assembly-2026.9.19.jar"), jar)
      flipped <- InstallPointer.flip(dir)
      _ = assertEquals(flipped, Right(("2026.9.19", "2026.9.20")))
      after <- InstallPointer.layout(dir)
      _ = assertEquals(after.current, Some("2026.9.19"), "the rollback target is now the version the launcher will run")
      _ = assertEquals(after.previous, Some("2026.9.20"))
      _ = assertEquals(
        after.versions,
        List("2026.9.19", "2026.9.20"),
        "both versions stay on disk (retention keeps two)"
      )
      // 缺指针 / 缺上一版：明确 Left，不猜
      bare <- tmpDir("nb-ptr-b")
      flipBare <- InstallPointer.flip(bare)
      _ = assert(flipBare.isLeft, flipBare.toString)
      onlyCurrent <- mkInstallDir("nb-ptr-c", "2026.9.20", None, List("2026.9.20"))
      flipOnly <- InstallPointer.flip(onlyCurrent)
      _ = assert(flipOnly.isLeft, "no previous version ⇒ nothing to roll back to")
      _ = assert(InstallPointer.jarIn(onlyCurrent, "2026.9.19").unsafeRunSync().isLeft)
    yield ()
  }

  // ── 回滚动作（G4）────────────────────────────────────────────────────

  test("rollback: pointer flips to the previous version and the relaunch swaps ONLY the package path") {
    for
      dir <- mkInstallDir("nb-rb-a", "2026.9.20", Some("2026.9.19"), List("2026.9.19", "2026.9.20"))
      dataRoot <- tmpDir("nb-rb-home")
      captured <- cats.effect.Ref.of[IO, Option[(String, HotRestartIntent)]](None)
      // (A typed val declaration is NOT a legal for-enumerator in Scala 3 — the
      // spawn function is therefore bound inline below, where the parameter type
      // of `Rollback.spawnPrevious` supplies the expected type.)
      rb = new nebflow.core.hotupdate.Rollback(
        installDir = dir,
        dataRoot = dataRoot,
        spawnPrevious = (intent, jar) =>
          captured.set(Some((jar, intent))).as(Right(new FakeProc): Either[String, HotRestart.SpawnedProcess]),
        host = "0.0.0.0",
        port = 8098
      )
      out <- rb.run("spec: new version failed its health self-check").flatMap(orDie("rollback"))
      _ = assertEquals(out.fromVersion, "2026.9.20")
      _ = assertEquals(out.toVersion, "2026.9.19")
      layout <- InstallPointer.layout(dir)
      _ = assertEquals(layout.current, Some("2026.9.19"), "green: the launcher now runs the previous version")
      _ = assertEquals(layout.previous, Some("2026.9.20"))
      seen <- captured.get
      _ = assert(seen.exists(_._1.endsWith("versions/2026.9.19/nebflow-assembly-2026.9.19.jar")), seen.toString)
      // 「只替换包路径」的可核判据：回执/意图里的复起命令**逐字等于既有纯函数命令构造**
      // 对同一 form/javaBin **只把包路径换成上一版**的产物（不新造命令构造器）。
      // ⚠️ 不直接断言 spawnCmd 含版本号：`HotRestart.detectForm` 在测试 JVM（sbt 类路径、
      // 无可信 run jar）里解析不出形态，`buildCommand` 此时按既有契约返回**大声失败文本**
      // ——那种环境下的正确值是失败文本，不是命令。生产（jar/bundled 形态）下该文本即
      // 携带上一版包路径，下面的等式两种环境都成立，故判据与形态无关、且永不放宽。
      _ = assert(
        seen.exists { case (jar, i) =>
          i.spawnCmd == HotRestart
            .buildCommand(
              i.form,
              nebflow.core.RestartHelper.resolveJavaBin(),
              Some(jar),
              SuccessorGate.intentPath(dataRoot).toString,
              dataRoot.toString,
              8098
            )
            .fold(msg => msg, _.mkString(" "))
        },
        s"the relaunch command must be the EXISTING pure builder with only the package path swapped: ${seen.map(_._2.spawnCmd)}"
      )
      _ = assert(seen.forall { case (_, i) => i.probePort.isEmpty })
      record <- rb.readRecord
      _ = assertEquals(record.flatMap(_.hcursor.downField("toVersion").as[String].toOption), Some("2026.9.19"))
      // 回滚记录落在 restart 目录（诊断面，与 intent 同根）
      _ = assert(os.exists(SuccessorGate.restartDir(dataRoot) / "rollback.json"))
      intentRead <- SuccessorGate.readIntent(SuccessorGate.intentPath(dataRoot))
      _ = assertEquals(
        intentRead.map(_.phase),
        Some("spawned"),
        "the relaunch reuses the successor protocol (intent write-ahead)"
      )
      _ = assertEquals(intentRead.map(_.triggerSource).map(_.startsWith("rollback:")), Some(true))
    yield ()
  }

  test("rollback red control: with no previous version on disk the pointers stay untouched (no silent downgrade)") {
    for
      dir <- mkInstallDir("nb-rb-b", "2026.9.20", None, List("2026.9.20"))
      dataRoot <- tmpDir("nb-rb-home2")
      rb = new nebflow.core.hotupdate.Rollback(
        installDir = dir,
        dataRoot = dataRoot,
        spawnPrevious = (_, _) => IO.pure(Left("must not be called")),
        host = "0.0.0.0",
        port = 8098
      )
      out <- rb.run("spec")
      _ = assert(out.isLeft, out.toString)
      layout <- InstallPointer.layout(dir)
      _ = assertEquals(
        layout.current,
        Some("2026.9.20"),
        "red: nothing changed ⇒ a broken new version would be picked again"
      )
      _ = assert(!os.exists(SuccessorGate.restartDir(dataRoot) / "rollback.json"))
    yield ()
  }

  private class FakeProc extends HotRestart.SpawnedProcess:
    def pid: Long = 555L
    def isAlive: IO[Boolean] = IO.pure(true)
    def destroy: IO[Unit] = IO.unit

end HealthGateSpec
