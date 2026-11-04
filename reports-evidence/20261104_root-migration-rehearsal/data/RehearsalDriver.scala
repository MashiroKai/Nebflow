package nebflow.core.dataroot

import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files

import nebflow.core.root.*
import nebflow.shared.PathUtil

/**
 * B1 根迁移演练驱动（**临时文件，不入库** —— 演练结束即删除；原件存档于
 * `reports-evidence/20261104_root-migration-rehearsal/data/RehearsalDriver.scala`）。
 *
 * 只调用 B1 既有公开 API（`RootLayout.migrate` / `RootLayout.rollback` / `RootLadder.resolve`），
 * **零生产逻辑改动**。两种模式：
 *  - `bigroot <root> [--skip-rollback]` —— 在真根副本上跑 迁移 → 启动路径 → 回滚；
 *  - `synth <base>` —— 合成小根上的分岔/边界场景（待认领、账号、指针、幂等、符号链接）。
 */
object RehearsalDriver:

  private def sha256(p: os.Path): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(os.read.bytes(p)).map("%02x".format(_)).mkString

  private var failures = 0

  private def check(label: String, ok: Boolean, detail: => String = ""): Unit =
    if ok then println(s"  PASS  $label ${if detail.nonEmpty then "- " + detail else ""}")
    else
      failures += 1
      println(s"  FAIL  $label ${if detail.nonEmpty then "- " + detail else ""}")

  def main(args: Array[String]): Unit =
    args.toList match
      case "bigroot" :: root :: rest =>
        bigroot(os.Path(root, os.pwd), skipRollback = rest.contains("--skip-rollback"))
      case "synth" :: base :: Nil => synth(os.Path(base, os.pwd))
      case "rollbackonly" :: root :: Nil => rollbackOnly(os.Path(root, os.pwd))
      case other =>
        println(s"usage: RehearsalDriver bigroot <root> [--skip-rollback] | synth <base>")
        sys.exit(2)
    println(s"\n[driver] completed with $failures failure(s)")
    if failures > 0 then sys.exit(1)

  // ── 只回滚：从已落盘的 migration-report.json 重建 MigrationReport ─────────
  // rollback() 只用 machineRoot / accountRoot / pendingClaim / movedEntries / reportPath
  // 五个字段（RootLayout.scala:155-190），足以从记账文件重建，无需重跑 migrate。
  private def rollbackOnly(root: os.Path): Unit =
    PathUtil.setMachineRoot(root)
    val reportPath = root / RootLayout.ReportFileName
    require(os.exists(reportPath), s"no migration report at $reportPath")
    val json = io.circe.parser.parse(os.read(reportPath)).getOrElse(sys.error("unparseable migration report"))
    val c = json.hcursor
    val target = os.Path(c.get[String]("targetRoot").getOrElse(sys.error("missing targetRoot")))
    val report = MigrationReport(
      machineRoot = root,
      accountSub = c.get[String]("accountSub").toOption,
      accountRoot = target,
      pendingClaim = c.get[Boolean]("pendingClaim").getOrElse(false),
      movedEntries = c.get[List[String]]("movedEntries").getOrElse(Nil),
      reportPath = reportPath
    )
    println(s"[rollbackonly] report=${reportPath} entries=${report.movedEntries.size} target=$target pending=${report.pendingClaim}")
    val t0 = System.nanoTime()
    RootLayout.rollback(report)
    println(f"[rollbackonly] ROLLBACK finished in ${(System.nanoTime() - t0) / 1e9}%.1f s")
    check("layout.json removed by rollback", !os.exists(AccountRoot.layoutMarkerPath(root)))
    check("pointer absent after rollback", AccountRoot.readPointer(root).isEmpty)
    check("pending-claim container removed", !os.exists(root / RootLayout.PendingClaimDirName))
    val back = RootLadder.resolve(root)
    check("post-rollback ladder is flat", back.name == RootLayer.FlatName, back.name)
    check("post-rollback effective root == machine root", PathUtil.dataRoot == root)

  // ── 真根副本：迁移 → 启动路径 → 回滚 ────────────────────────────────────
  private def bigroot(root: os.Path, skipRollback: Boolean): Unit =
    PathUtil.setMachineRoot(root) // 先钉机器根：任何 PathUtil 读取都落在副本上（绝不落到真根）
    println(s"[bigroot] rehearsal root = $root")
    val pre = RootLadder.resolve(root)
    println(s"[bigroot] pre-migrate ladder = ${pre.name} (effective=${pre.root}), PathUtil.dataRoot=${PathUtil.dataRoot}")
    check("pre-migrate ladder is flat (no layout.json)", pre.name == RootLayer.FlatName, pre.name)
    check("pre-migrate effective root == machine root", PathUtil.dataRoot == root)

    val topBefore = os.list(root).map(_.last).toSet
    println(s"[bigroot] top-level entries before migrate = ${topBefore.size}")

    val t0 = System.nanoTime()
    val report = RootLayout.migrate(root)
    val migrateSec = (System.nanoTime() - t0) / 1e9
    println(f"[bigroot] MIGRATE finished in $migrateSec%.1f s")

    val reportBytes = os.size(report.reportPath)
    println(s"[bigroot] pendingClaim=${report.pendingClaim} accountSub=${report.accountSub}")
    println(s"[bigroot] target=${report.accountRoot}")
    println(s"[bigroot] movedEntries=${report.movedEntries.size} -> ${report.movedEntries}")
    println(f"[bigroot] migration-report.json = ${reportBytes / 1024.0 / 1024.0}%.1f MiB at ${report.reportPath}")

    check("migration-report.json written", os.exists(report.reportPath))
    check("layout.json written", AccountRoot.readLayoutMarker(root).isDefined)
    check("pointer NOT written on migrate (pending-claim branch)", AccountRoot.readPointer(root).isEmpty)
    check(
      "movedEntries covers every pre-migrate top-level entry except layout-managed ones",
      report.movedEntries.toSet == topBefore -- Set(RootLayout.ReportFileName, AccountRoot.LayoutFileName, RootLayout.PendingClaimDirName)
    )

    // ── 启动路径等价：boot 只调 resolve（纯读），绝不调 migrate ──
    val t2 = System.nanoTime()
    val post = RootLadder.resolve(root)
    val resolveSec = (System.nanoTime() - t2) / 1e9
    println(f"[bigroot] post-migrate STARTUP PATH (RootLadder.resolve) = ${post.name} in $resolveSec%.1f s, effective=${post.root}")
    check("startup path resolves the unauthenticated layer (layout marker + no pointer)", post.name == RootLayer.UnauthenticatedName)
    val claimDirs = if os.exists(root / RootLayout.PendingClaimDirName) then os.list(root / RootLayout.PendingClaimDirName).map(_.last) else Nil
    check("startup path does NOT create a second pending-claim copy", claimDirs.size <= 1, s"dirs=$claimDirs")

    if skipRollback then
      println("[bigroot] --skip-rollback: stopping after migrate")
    else
      val t3 = System.nanoTime()
      RootLayout.rollback(report)
      val rollbackSec = (System.nanoTime() - t3) / 1e9
      println(f"[bigroot] ROLLBACK finished in $rollbackSec%.1f s")
      check("layout.json removed by rollback", !os.exists(AccountRoot.layoutMarkerPath(root)))
      check("pointer absent after rollback", AccountRoot.readPointer(root).isEmpty)
      check("pending-claim container removed", !os.exists(root / RootLayout.PendingClaimDirName))
      check("no accounts/ created", !os.exists(root / RootLadder.AccountsDirName))
      val back = RootLadder.resolve(root)
      check("post-rollback ladder is flat again", back.name == RootLayer.FlatName, back.name)
      check("post-rollback effective root == machine root", PathUtil.dataRoot == root)
      check("migration-report.json kept for audit after rollback", os.exists(report.reportPath))

  // ── 合成小根：分岔与边界 ────────────────────────────────────────────────
  private def fresh(dir: os.Path): os.Path =
    if os.exists(dir) then os.remove.all(dir)
    os.makeDir.all(dir)
    dir

  private def seedFlat(root: os.Path): Unit =
    val files = Map(
      "projects/p1/project.json" -> """{"name":"p1"}""",
      "secrets/dummy" -> "dummy-value-not-a-real-secret",
      "tasks-v2.json" -> """{"version":1}""",
      "sessions/s1.json" -> """{"id":"s1"}""",
      "memory/User.md" -> "# User\n"
    )
    files.foreach { case (rel, body) =>
      val p = root / os.RelPath(rel)
      os.makeDir.all(p / os.up)
      os.write.over(p, body)
    }

  private def seedAccount(root: os.Path, sub: String): os.Path =
    val d = AccountRoot.ensureAccountDir(root, sub)
    AccountRoot.writeProfile(d, AccountProfile(sub, s"$sub@example.com", "2026-11-04T00:00:00Z", None))
    d

  private def synth(base: os.Path): Unit =
    println(s"[synth] base = $base")
    os.makeDir.all(base)

    // S1 —— 未登录：待认领区；再调一次 migrate = 第二份副本（无幂等守卫）
    println("\n[S1] flat + no account => pending-claim/<ts>; second migrate call = second copy?")
    locally {
      val root = fresh(base / "s1")
      seedFlat(root)
      val before = Map(
        "projects/p1/project.json" -> sha256(root / "projects" / "p1" / "project.json"),
        "tasks-v2.json" -> sha256(root / "tasks-v2.json")
      )
      val r1 = RootLayout.migrate(root)
      check("S1 pendingClaim = true", r1.pendingClaim)
      check("S1 accountSub = None", r1.accountSub.isEmpty)
      check("S1 target under pending-claim/", r1.accountRoot.toString.contains("/pending-claim/"), r1.accountRoot.toString)
      check("S1 layout.json written", AccountRoot.readLayoutMarker(root).isDefined)
      check("S1 pointer NOT written", AccountRoot.readPointer(root).isEmpty)
      before.foreach { case (rel, d) =>
        check(s"S1 copy digest == original ($rel)", sha256(r1.accountRoot / os.RelPath(rel)) == d)
        check(s"S1 original untouched ($rel)", sha256(root / os.RelPath(rel)) == d)
      }
      Thread.sleep(1100) // ts granularity is seconds; ensure the 2nd dir gets a different stamp
      val r2 = RootLayout.migrate(root)
      val dirs = os.list(root / RootLayout.PendingClaimDirName).map(_.last)
      println(s"[S1] second migrate issued a second pending-claim dir: $dirs")
      check("S1-FINDING no idempotence guard: a 2nd migrate call creates a 2nd pending-claim copy", dirs.size == 2, s"dirs=$dirs")
      check("S1 second report points at the new dir", r2.pendingClaim && r2.accountRoot != r1.accountRoot)
      RootLayout.rollback(r1)
      check("S1 rollback removed layout.json", !os.exists(AccountRoot.layoutMarkerPath(root)))
      check("S1-FINDING rollback of report#1 leaves copy#2 behind", os.exists(r2.accountRoot) && os.list(root / RootLayout.PendingClaimDirName).nonEmpty)
      check("S1 originals still intact after rollback", before.forall { case (rel, d) => sha256(root / os.RelPath(rel)) == d })
    }

    // S2 —— 有账号根：迁进账号目录；启动路径 = account 层
    println("\n[S2] account root present => migrate into accounts/<dir>; startup path = account layer")
    locally {
      val root = fresh(base / "s2")
      seedFlat(root)
      val acct = seedAccount(root, "acct-one")
      val origins = Map("sessions/s1.json" -> sha256(root / "sessions" / "s1.json"))
      val r = RootLayout.migrate(root)
      check("S2 pendingClaim = false", !r.pendingClaim)
      check("S2 accountSub = acct-one", r.accountSub.contains("acct-one"), r.accountSub.toString)
      check("S2 target == account root", r.accountRoot == acct, s"${r.accountRoot} vs $acct")
      check("S2 no pending-claim dir created", !os.exists(root / RootLayout.PendingClaimDirName))
      origins.foreach { case (rel, d) =>
        check(s"S2 copy digest == original ($rel)", sha256(acct / os.RelPath(rel)) == d)
        check(s"S2 original untouched ($rel)", sha256(root / os.RelPath(rel)) == d)
      }
      check("S2 profile.json still present in account root", AccountRoot.isAccountRoot(acct))
      val filesAfterMigrate = os.list(acct).map(_.last).toSet
      PathUtil.setMachineRoot(root)
      val beforePointer = RootLadder.resolve(root)
      check("S2-FINDING migrate alone does NOT flip the ladder to account (pointer still missing)",
        beforePointer.name == RootLayer.UnauthenticatedName, beforePointer.name)
      // 登录腿语义（契约 §4.1）：先 migrate，再写指针，然后重启进程才生效。
      AccountRoot.writePointer(root, ActiveAccount("acct-one", "acct-one@example.com", "2026-11-04T00:00:00Z"))
      val layer = RootLadder.resolve(root)
      check("S2 startup path resolves account layer after pointer write", layer.name == RootLayer.AccountName, layer.name)
      check("S2 effective root == account root", PathUtil.dataRoot == acct)
      println(s"[S2] startup path produced no new entries in account root: ${os.list(acct).map(_.last).toSet -- filesAfterMigrate} emptied")
      RootLayout.rollback(r)
      check("S2 rollback removed copies", !os.exists(acct / "sessions" / "s1.json"))
      check("S2 rollback left the account dir itself", os.exists(acct))
      check("S2 rollback removed layout.json", !os.exists(AccountRoot.layoutMarkerPath(root)))
      PathUtil.setMachineRoot(root)
      check("S2 post-rollback ladder = flat", RootLadder.resolve(root).name == RootLayer.FlatName)
      check("S2 originals intact after rollback", origins.forall { case (rel, d) => sha256(root / os.RelPath(rel)) == d })
    }

    // S3 —— 指针已在：迁进指针账号；rollback 是否连既有指针一并清掉
    println("\n[S3] pre-existing pointer => migrate targets that account; does rollback clear the pre-existing pointer?")
    locally {
      val root = fresh(base / "s3")
      seedFlat(root)
      val acct = seedAccount(root, "acct-old")
      AccountRoot.writePointer(root, ActiveAccount("acct-old", "old@example.com", "2026-11-04T00:00:00Z"))
      val r = RootLayout.migrate(root)
      check("S3 migrate targets pointer account", r.accountRoot == acct, s"${r.accountRoot}")
      check("S3 accountSub from profile", r.accountSub.contains("acct-old"))
      check("S3 pointer untouched by migrate", AccountRoot.readPointer(root).exists(_.accountSub == "acct-old"))
      RootLayout.rollback(r)
      check("S3-FINDING rollback clears a pointer that predates migration (clearPointer is unconditional)",
        AccountRoot.readPointer(root).isEmpty)
      check("S3 originals intact", os.exists(root / "sessions" / "s1.json"))
    }

    // S4 —— 符号链接危害：broken / socket / loop / dir-escape
    println("\n[S4] symlink hazards that a real root really contains (playwright evidence dirs, jdk-21)")
    def hazardRoot(name: String, build: os.Path => Unit): Unit =
      val root = fresh(base / s"s4-$name")
      seedFlat(root)
      build(root)
      try
        val r = RootLayout.migrate(root)
        println(s"[S4-$name] migrate SUCCEEDED (no exception), movedEntries=${r.movedEntries.size}")
        check(s"S4-$name handled without crash", r.movedEntries.nonEmpty)
        check(s"S4-$name layout.json written", AccountRoot.readLayoutMarker(root).isDefined)
      catch
        case e: Throwable =>
          val marker = os.exists(AccountRoot.layoutMarkerPath(root))
          val report = os.exists(root / RootLayout.ReportFileName)
          println(s"[S4-$name] migrate THREW ${e.getClass.getName}: ${Option(e.getMessage).getOrElse("")}")
          println(s"[S4-$name] layout.json written before crash = $marker ; migration-report.json present = $report")
          check(s"S4-$name failure is contained: layout.json NOT written => restart still resolves flat", !marker)
          check(s"S4-$name no migration-report.json written", !report)
          check(s"S4-$name originals untouched after crash", os.exists(root / "sessions" / "s1.json"))

    // 链接一律用 NIO 显式参数序：createSymbolicLink(link, target)
    def link(linkPath: os.Path, target: os.Path): Unit =
      os.makeDir.all(linkPath / os.up)
      Files.createSymbolicLink(linkPath.toNIO, target.toNIO)

    hazardRoot("broken-link", r => link(r / "projects" / "broken-link", r / "gone" / "nothing"))
    hazardRoot("loop", r => link(r / "projects" / "loop", r / "projects" / "loop"))
    hazardRoot("dir-escape", r => link(r / "projects" / "escape-file", os.root / "etc" / "hosts"))
    locally {
      // socket symlink：真实根里 playwright 证据目录下就有 SingletonSocket
      val root = fresh(base / "s4-socket")
      seedFlat(root)
      val sockPath = base / "live.sock"
      if os.exists(sockPath) then os.remove(sockPath)
      val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
      server.bind(UnixDomainSocketAddress.of(sockPath.toNIO))
      try
        Files.createSymbolicLink((root / "projects" / "SingletonSocket").toNIO, sockPath.toNIO)
        try
          val r = RootLayout.migrate(root)
          println(s"[S4-socket] migrate SUCCEEDED, movedEntries=${r.movedEntries.size}")
          val copy = r.accountRoot / "projects" / "SingletonSocket"
          println(s"[S4-socket] copied socket node exists=${os.exists(copy)} isFile=${if os.exists(copy) then os.isFile(copy) else false}")
        catch
          case e: Throwable =>
            println(s"[S4-socket] migrate THREW ${e.getClass.getName}: ${Option(e.getMessage).getOrElse("")}")
            check("S4-socket failure contained: layout.json NOT written", !os.exists(AccountRoot.layoutMarkerPath(root)))
            check("S4-socket originals untouched", os.exists(root / "sessions" / "s1.json"))
      finally
        server.close()
        if os.exists(sockPath) then os.remove(sockPath)
    }

    println("\n[synth] done")
