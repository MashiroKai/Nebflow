package nebflow.db.chaos

import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.db.{Db, DbConfig, DbError, PendingStream, ReadResult}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

/**
 * OS 级 kill -9 chaos 批（db 接线二阶 B4）——**真实进程级**（forked SubProcess 组，
 * build.sbt testGrouping 增量；子进程 = 真 JVM 直启，真杀 = `taskkill /F /PID`）：
 *
 *  - 场景 A（kill 撕裂窗）：writer 子进程批量写 → 中途 taskkill /F → 重开同根 ⇒
 *    无半行入库（全帧合法）、无 tmp 残留、guard 档/待审单不丢（V8/V6 进程级）、
 *    锁世代感知（新世代 = 旧世代 + 1）、崩溃后 append 不拒（V9 ack 面）；
 *  - 场景 B（跨进程双开，D-7）：hold 持锁 + 第二进程 tryopen ⇒ 拒启
 *    （InstanceLockHeld）；真杀后新进程开库成功且世代接续；
 *  - 场景 C（三态可辨，进程级）：Present / Absent / Corrupt（坏帧注入副本根）三分。
 */
class DbKill9ChaosSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  // ── 子进程基建 ────────────────────────────────────────────────────────────

  private def javaBin: String =
    Path
      .of(
        sys.props("java.home"),
        "bin",
        if sys.props("os.name").toLowerCase.contains("win") then "java.exe" else "java"
      )
      .toString

  private def childCp: String = sys.props("java.class.path")

  private case class Child(process: java.lang.Process, markerDir: Path, logFile: Path):
    def pid: Long = process.pid()

  private def spawn(mode: String, root: Path, tag: String, count: Option[Int] = None): Child =
    val markers = Files.createTempDirectory(s"chaos-marker-$tag")
    val logFile = Files.createTempFile(s"chaos-$tag-", ".log")
    val args =
      List(javaBin, "-cp", childCp, "nebflow.db.chaos.DbChaosChild", mode, root.toString, markers.toString) ++ count
        .map(_.toString)
    val process = new java.lang.ProcessBuilder(args*)
      .redirectErrorStream(true)
      .redirectOutput(logFile.toFile)
      .start()
    Child(process, markers, logFile)

  /** 有界轮询 marker 文件出现（deadline 默认 60s，步 100ms）。 */
  private def awaitMarker(child: Child, name: String, deadlineMs: Long = 60_000L): String =
    val f = child.markerDir.resolve(name)
    val t0 = System.currentTimeMillis()
    while !Files.exists(f) && System.currentTimeMillis() - t0 < deadlineMs do
      if !child.process.isAlive && !Files.exists(f) then
        fail(s"child died before marker $name (log: ${Files.readString(child.logFile)})")
      Thread.sleep(100L)
    assert(
      Files.exists(f),
      s"marker $name not produced within ${deadlineMs}ms " +
        s"(log: ${if Files.exists(child.logFile) then Files.readString(child.logFile) else "-"})"
    )
    var text = ""
    var tries = 0
    while text.isBlank && tries < 50 do
      text = Files.readString(f).trim
      if text.isBlank then Thread.sleep(50L)
      tries += 1
    text

  end awaitMarker

  /** 已达成进度的最大 w<idx> marker（0 = 尚无进度 marker）。 */
  private def maxProgress(child: Child): Int =
    Option(child.markerDir.toFile.listFiles).toList.flatten
      .map(_.getName)
      .flatMap(n => n.stripPrefix("w").toIntOption)
      .maxOption
      .getOrElse(0)

  /** 真杀（OS 级 SIGKILL）：Windows = taskkill /F /PID（任务面指定形）；POSIX = kill -9。 */
  private def kill9(child: Child): Unit =
    val isWindows = sys.props("os.name").toLowerCase.contains("win")
    if isWindows then
      val out = Seq("taskkill", "/F", "/PID", child.pid.toString).!!
      assert(
        out.toLowerCase.contains("成功") || out.toLowerCase.contains("success")
          || out.toLowerCase.contains("已终止") || out.contains(child.pid.toString),
        s"taskkill output unexpected: $out"
      )
      child.process.waitFor()
    else
      // POSIX：kill -9 成功 = 零退出（`.!` 返回退出码，非零由 assert 拦下）；
      // 被 SIGKILL 的 JVM 退出码不限（137/1/…），waitFor 仅作收割。
      assert(Seq("kill", "-9", child.pid.toString).! == 0, s"kill -9 ${child.pid} failed")
      child.process.waitFor()
  end kill9

  // ── 场景 A：kill 撕裂窗 ───────────────────────────────────────────────────

  test("A: writer 批量写中途被 taskkill /F ⇒ 重开无半行/无 tmp/事实面不丢/世代接续"):
    val root = Files.createTempDirectory("chaos-kill9-root")
    val writer = spawn("writer", root, "writer", count = Some(100000))
    val gen1 = awaitMarker(writer, "ready").toLong
    // 等 ≥100 条已 ack（marker = append 返回后落 ⇒ 读回数下界）。
    val t0 = System.currentTimeMillis()
    while maxProgress(writer) < 100 && System.currentTimeMillis() - t0 < 60_000L do Thread.sleep(100L)
    val acked = maxProgress(writer)
    assert(acked >= 100, s"writer made no progress (acked=$acked, log=${Files.readString(writer.logFile)})")
    kill9(writer)
    assert(!writer.process.isAlive, "taskkill /F 后进程确死")

    // 「重启」：同根重开（真进程死后 OS 锁自动释放）。
    val h = Db.open(DbConfig(root)).unsafeRunSync()
    try
      assertEquals(h.instanceLockGeneration, gen1 + 1L, "世代感知：新世代 = 旧世代 + 1")
      // 无 tmp 残留（open 已清扫；复扫为 0）。
      val tmpLeft = Files
        .walk(root.resolve("db"))
        .iterator()
        .asScala
        .count(p => Files.isRegularFile(p) && p.getFileName.toString.contains(".tmp-"))
      assertEquals(tmpLeft, 0, "无 tmp 残留")
      // 无半行入库：全帧合法 + 读回 ≥ 已 ack 数（V9：ack 即耐久）。
      h.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assert(sr.records.size >= acked, s"已 ack 行不丢：read=${sr.records.size} acked=$acked")
          assertEquals(sr.records.size, sr.records.map(_.seq).distinct.size, "无重号（半行/重复帧皆非法）")
        case other => fail(s"events 应 Present，got $other")
      // 崩溃后追加不拒（撕裂尾若在已被 open 修复；V3 拒写仅对坏尾帧）。
      h.appendEvent(io.circe.Json.obj("type" -> io.circe.Json.fromString("post-crash")), Some("post-crash"))
        .unsafeRunSync()
      // 事实面不丢（V8/V6 进程级）：guard 档 + 两类待审单。
      h.readAvoidGuard(Db.AvoidGuardSource).unsafeRunSync() match
        case ReadResult.Present(doc) =>
          assert(doc.avoidUntil.contains("chaos/p1"), "guard 档跨 kill 不丢")
        case other => fail(s"guard 档应 Present，got $other")
      h.readPending(PendingStream.Permission).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assert(sr.records.exists(_.requestId == "chaos-perm-1"), "permission 单跨 kill 不丢")
        case other => fail(s"permission 应 Present，got $other")
      h.readPending(PendingStream.Ask).unsafeRunSync() match
        case ReadResult.Present(sr) =>
          assert(sr.records.exists(_.requestId == "chaos-ask-1"), "ask 单跨 kill 不丢")
        case other => fail(s"ask 应 Present，got $other")
    finally h.close()
    end try

  // ── 场景 B：跨进程双开（D-7）+ 真杀后世代接续 ────────────────────────────

  test("B: hold 持锁 ⇒ 第二进程拒启；真杀后新进程开库成功且世代接续"):
    val root = Files.createTempDirectory("chaos-dbl-open")
    val holder = spawn("hold", root, "hold")
    val gen1 = awaitMarker(holder, "ready").toLong
    // 第二个真实进程尝试开同一根。
    val second = spawn("tryopen", root, "second")
    val refused = awaitMarker(second, "result")
    assert(refused.startsWith("REFUSED"), s"第二进程应拒启，got $refused")
    assert(refused.contains(classOf[DbError.InstanceLockHeld].getSimpleName), s"拒启类型 = InstanceLockHeld，got $refused")
    kill9(holder)
    // 真杀后：新进程开库成功，世代 = 旧 + 1。
    val third = spawn("tryopen", root, "third")
    val ok = awaitMarker(third, "result")
    assert(ok.startsWith("OK"), s"杀后新进程应开库成功，got $ok (log=${Files.readString(third.logFile)})")
    assertEquals(ok.stripPrefix("OK ").trim.toLong, gen1 + 1L, "世代接续 = 旧世代 + 1")

  // ── 场景 C：三态可辨（进程级）─────────────────────────────────────────────

  test("C: Present / Absent / Corrupt 三分（真进程写入的数据根上）"):
    val root = Files.createTempDirectory("chaos-threestate")
    val writer = spawn("writer", root, "writer3", count = Some(50))
    awaitMarker(writer, "done")
    writer.process.waitFor()
    val h = Db.open(DbConfig(root)).unsafeRunSync()
    try
      // Present：事件面在且全帧合法。
      h.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Present(_) => ()
        case other => fail(s"events 应 Present，got $other")
      // Absent：从未存在的会话流。
      h.readSession("never-existed", "llm").unsafeRunSync() match
        case ReadResult.Absent() => ()
        case other => fail(s"未写流应 Absent，got $other")
    finally h.close()
    // Corrupt：副本根注入坏帧 ⇒ 显式 Corrupt（非静默空视图）。
    val copy = Files.createTempDirectory("chaos-threestate-copy")
    osCopy(root, copy)
    val eventsDir = copy.resolve("db").resolve("events")
    // 🔴 确定性选**真 shard**（events.<digits>）：Files.list 目录序无保证，旧形
    // `startsWith("events.")` 会命中 events.idx——idx 坏 = 删了重建（读面照旧
    // Present），坏帧就注不进事件流了（本测首修前在 darwin 上即此形态）。
    val shardName = Files
      .list(eventsDir)
      .iterator()
      .asScala
      .map(_.getFileName.toString)
      .filter(_.matches("events\\.\\d+\\.jsonl"))
      .toList
      .sorted
      .headOption
      .getOrElse(fail("events shard missing"))
    val shard = eventsDir.resolve(shardName)
    Files.writeString(shard, Files.readString(shard) + "{\"broken\"\n")
    val h2 = Db.open(DbConfig(copy)).unsafeRunSync()
    try
      h2.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Corrupt(_, _) => ()
        case other => fail(s"坏帧注入应 Corrupt，got $other")
    finally h2.close()

  private def osCopy(from: Path, to: Path): Unit =
    Files.walk(from).forEach { src =>
      val dst = to.resolve(from.relativize(src).toString)
      if Files.isDirectory(src) then Files.createDirectories(dst)
      else
        Files.createDirectories(dst.getParent)
        Files.copy(src, dst)
    }

end DbKill9ChaosSpec
