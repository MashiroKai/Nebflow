package nebflow.core

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * 记忆检索库自动入库守护（memory-recall 批 2026-10-22）回归钉。
 *
 * 作者令：「这个进库 应该要是我们使用stand自动的能力」。本 spec 用**假数据根 +
 * 注入消息**驱动真实守护纤维与真实 `tools/stand-recall --update` 子进程（不依赖
 * 真网关、不触真 ~/.stand）：
 *   - 端到端：引擎形态下主会话落新消息 → 等节流窗 → 库自动前进、source=auto；
 *   - 节流窗 / 脏探测 CleanSkip / NoData / NoTool / kill-switch 各门序分支；
 *   - 排除面保持既有：external- 执行器会话与 team 子代理会话不入库；
 *   - 工具解析三处候选的优先级。
 *
 * 隔离纪律：每个用例 `withTempDataRoot` 换根（guarantee 还原）——绝不读写真
 * ~/.stand；sqlite 断言经 python3 标准库子进程（仓内零 sqlite 依赖）。
 */
class MemoryRecallDaemonSpec extends CatsEffectSuite:

  override val munitIOTimeout = 180.seconds

  // ── fixtures ────────────────────────────────────────────────────────────

  /** 换根执行 + guarantee 还原（ReloadStaleSessionModelSpec 同款；suite 串行）。 */
  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-memory-recall-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private val MainId = "nbtest-nebula-0001"
  private val ExternalId = "external-nbtest-0001"
  private val TeamId = "nbtest-team-manager-0001"

  /** 消息 JSON（引擎 sessions/<id>.json 实测形态：role/content/timestamp）。 */
  private def msg(role: String, text: String, ts: Long): String =
    s"""{"role":"$role","content":${io.circe.Json.fromString(text).noSpaces},"timestamp":$ts}"""

  /**
   * 假数据根：sessions/_index.json + Nebula 主会话（1 条用户消息）+
   * external- 执行器会话 + Manager 团队会话（排除面探针）。返回主会话文件路径。
   */
  private def seedRoot(root: os.Path, now: Long): os.Path =
    val sessions = root / "sessions"
    os.makeDir.all(sessions)
    val entries = collection.mutable.ArrayBuffer.empty[String]
    def sessionFile(id: String, name: String, agentName: Option[String], lines: Seq[String]): os.Path =
      val agent = agentName.map(a => s""""agentName":"$a",""").getOrElse("")
      entries += s"""{$agent"id":"$id","name":"$name","createdAt":$now,"updatedAt":$now,"hasUnread":false}"""
      val p = sessions / s"$id.json"
      os.write(p, "[" + lines.mkString(",") + "]")
      p
    val main = sessionFile(MainId, "测试主会话", Some("Nebula"), Seq(msg("user", "自动入库测试：假数据根首条用户消息", now)))
    sessionFile(ExternalId, "外部执行器会话", None, Seq(msg("user", "外部执行器任务正文，绝不可入库", now)))
    sessionFile(TeamId, "team/Manager", Some("Manager"), Seq(msg("user", "团队子代理任务正文，绝不可入库", now)))
    os.write(sessions / "_index.json", s"""{"activeId":"$MainId","sessions":[${entries.mkString(",")}],"folders":[]}""")
    main

  /** 追加一条用户消息到主会话（=「主会话发消息落盘」的注入面）。 */
  private def appendUserMessage(sessionFile: os.Path, text: String): Unit =
    val trimmed = os.read(sessionFile).trim.stripSuffix("]")
    os.write.over(sessionFile, s"$trimmed,${msg("user", text, System.currentTimeMillis())}]")

  /**
   * sqlite 断言面：python3 标准库子进程查库（仓内零 sqlite 依赖）。取首行首列。
   * 输出走临时文件 + waitFor（runUpdate 同款）——杜绝「读管道不等进程退出」的
   * EOF 竞态（与守护子进程并发时实测会让读端悬挂到超时）。
   */
  private def dbScalar(root: os.Path, sql: String): IO[String] = IO.blocking(dbScalarSync(root, sql))

  /** 同步形态（轮询/断言共用）：输出走临时文件 + waitFor（runUpdate 同款）。 */
  private def dbScalarSync(root: os.Path, sql: String): String =
    val out = Files.createTempFile("nb-memory-recall-query", ".log")
    try
      val pb = new ProcessBuilder(
        "python3",
        "-c",
        "import sqlite3,sys;c=sqlite3.connect(sys.argv[1]);" +
          "r=c.execute(sys.argv[2]).fetchone();print('NULL' if r is None else str(r[0]))",
        (root / "memory-db" / "memory.db").toString,
        sql
      )
      pb.redirectErrorStream(true)
      pb.redirectOutput(out.toFile)
      val p = pb.start()
      val done = p.waitFor(30, TimeUnit.SECONDS)
      if !done then
        p.destroyForcibly()
        "<subprocess-timeout>"
      else
        val text =
          try Files.readAllLines(out).asScala.mkString("\n").trim
          catch case _: Throwable => "<unreadable>"
        if p.exitValue() == 0 then text else s"<exit-${p.exitValue()}>$text"
    finally Files.deleteIfExists(out)

  /** 库未建（守护首跑前）= 0；查询失败 = -1（轮询继续等）。 */
  private def dbMessages(root: os.Path): IO[Int] = IO.blocking(dbMessagesSync(root))

  private def dbMessagesSync(root: os.Path): Int =
    val db = root / "memory-db" / "memory.db"
    if !os.exists(db) then 0
    else
      val raw = dbScalarSync(root, "SELECT COUNT(*) FROM messages")
      raw.trim.toIntOption.getOrElse(-1)

  /**
   * 有界轮询（等节流窗）。**刻意用 `Thread.sleep` + 同步查询**（IO.blocking 真线程）：
   * 本 harness 下测试体纤维的 `IO.sleep` 曾实测被搁置 ~60s（守护纤维的 sleep 正常）
   * ——轮询律不依赖 CE timer。condSync 在阻塞线程上直跑（python 子进程本就阻塞型）。
   */
  /**
   * 有界轮询（等节流窗）。**刻意用 `Thread.sleep` + 同步查询**（IO.blocking 真线程）：
   * 本 harness 下测试体纤维的 `IO.sleep` 曾实测被搁置 ~60s（守护纤维的 sleep 正常）
   * ——轮询律不依赖 CE timer。condSync 在阻塞线程上直跑（python 子进程本就阻塞型）。
   */
  private def awaitUntil(
    desc: String,
    condSync: () => Boolean,
    timeout: FiniteDuration = 60.seconds,
    onTimeout: IO[String] = IO.pure("")
  ): IO[Unit] =
    IO.blocking {
      val deadline = timeout.fromNow
      var ok = condSync()
      while !ok && !deadline.isOverdue do
        Thread.sleep(300)
        ok = condSync()
      if !ok then throw new java.util.concurrent.TimeoutException(s"timeout awaiting: $desc")
      ()
    }.handleErrorWith { e =>
      if e.isInstanceOf[java.util.concurrent.TimeoutException] then
        onTimeout.flatMap(diag => IO.raiseError(new AssertionError(s"${e.getMessage}; $diag")))
      else IO.raiseError(e)
    }

  private def manifestSource(root: os.Path): Option[String] =
    io.circe.parser
      .parse(os.read(root / "memory-db" / "index-manifest.json"))
      .toOption
      .flatMap(_.hcursor.downField("last_update_source").as[String].toOption)

  private def expectRun(out: MemoryRecallDaemon.TickOutcome, what: String): Unit = out match
    case _: MemoryRecallDaemon.TickOutcome.Updated => ()
    case other => fail(s"$what (got $other)")

  // ── 用例 ────────────────────────────────────────────────────────────────

  test("auto 入库端到端：假数据根+注入消息 → 守护纤维 → 节流窗内库自动前进且 source=auto") {
    withTempDataRoot { root =>
      val sessionFile = seedRoot(root, System.currentTimeMillis())
      // 探针必须无锁无 IO（守护纤维内同步回调）——禁 unsafeRunSync（线程池自锁）
      val ticks = new java.util.concurrent.ConcurrentLinkedDeque[String]()
      def record(o: MemoryRecallDaemon.TickOutcome): Unit =
        val _ = ticks.addLast(o.toString)
      MemoryRecallDaemon
        .spawn(tick = 200.millis, minIntervalMs = 1000L, onTick = record)
        .flatMap { fiber =>
          // 断言失败也要收纤维（守护读的是全局 dataRoot——泄漏纤维会串扰后续用例）
          // diag = 失败取证面：纤维拍轨 + 库盘面 + 数据根归属（一次定位守护/轮询哪侧的锅）
          val diag = IO.blocking {
            val it = ticks.iterator()
            val all = List.newBuilder[String]
            while it.hasNext do all += it.next()
            val xs = all.result()
            val db = root / "memory-db" / "memory.db"
            val dbFiles =
              if os.exists(root / "memory-db") then os.list(root / "memory-db").map(_.last).mkString(",")
              else "(no memory-db dir)"
            s"ticks=${xs.size} head=${xs.take(4)} tail=${xs.takeRight(4)} " +
              s"dbExists=${os.exists(db)} dbFiles=[$dbFiles] dataRootNow=${PathUtil.dataRoot} root=$root"
          }
          val body =
            for
              // 首拍（boot 补扫语义）：首条用户消息入库
              _ <- awaitUntil(
                "首条消息自动入库",
                () => dbMessagesSync(root) == 1,
                onTimeout = diag
              )
              // 「主会话发消息」：注入第二条 → 等节流窗 → 自动前进
              _ <- IO.blocking(appendUserMessage(sessionFile, "注入的第二条用户消息：节流窗后应自动前进"))
              _ <- awaitUntil(
                "第二条消息自动入库",
                () => dbMessagesSync(root) == 2,
                onTimeout = diag
              )
              // 入库来源标记 = auto（meta 表 + manifest 双面）
              src <- dbScalar(root, "SELECT value FROM meta WHERE key='last_update_source'")
              _ = assertEquals(src, "auto")
              _ = assertEquals(manifestSource(root), Some("auto"))
            yield ()
          body.guarantee(fiber.cancel)
        }
    }
  }

  test("节流窗：同窗内第二次拍 Throttled（不重复起子进程）") {
    withTempDataRoot { root =>
      val _ = seedRoot(root, System.currentTimeMillis())
      val state = MemoryRecallDaemon.unsafeState
      for
        first <- MemoryRecallDaemon.tickRun(state, minIntervalMs = 60_000L)
        _ = expectRun(first, "首拍应真实尝试（boot 补扫语义）")
        second <- MemoryRecallDaemon.tickRun(state, minIntervalMs = 60_000L)
        _ = assertEquals(second, MemoryRecallDaemon.TickOutcome.Throttled: MemoryRecallDaemon.TickOutcome)
      yield ()
    }
  }

  test("脏探测：指纹无变化 → CleanSkip（零子进程）；会话文件有写入 → 再跑 Updated") {
    withTempDataRoot { root =>
      val sessionFile = seedRoot(root, System.currentTimeMillis())
      val state = MemoryRecallDaemon.unsafeState
      for
        _ <- MemoryRecallDaemon.tickRun(state, minIntervalMs = 0L) // 首跑建库
        skip <- MemoryRecallDaemon.tickRun(state, minIntervalMs = 0L)
        _ = assertEquals(skip, MemoryRecallDaemon.TickOutcome.CleanSkip: MemoryRecallDaemon.TickOutcome)
        n1 <- dbMessages(root)
        _ = assertEquals(n1, 1)
        _ <- IO.blocking(appendUserMessage(sessionFile, "指纹应因 mtime 变化而失效"))
        again <- MemoryRecallDaemon.tickRun(state, minIntervalMs = 0L)
        _ = expectRun(again, "脏指纹应触发真实入库")
        n2 <- dbMessages(root)
        _ = assertEquals(n2, 2)
      yield ()
    }
  }

  test("排除面保持既有：external- 执行器会话与 team 子代理会话不入库") {
    withTempDataRoot { root =>
      val _ = seedRoot(root, System.currentTimeMillis())
      val state = MemoryRecallDaemon.unsafeState
      for
        _ <- MemoryRecallDaemon.tickRun(state, minIntervalMs = 0L)
        ext <- dbScalar(root, s"SELECT COUNT(*) FROM messages WHERE session_id='$ExternalId'")
        team <- dbScalar(root, s"SELECT COUNT(*) FROM messages WHERE session_id='$TeamId'")
        main <- dbScalar(root, s"SELECT COUNT(*) FROM messages WHERE session_id='$MainId'")
        _ = assertEquals(ext, "0", "external- 执行器会话必须不入库")
        _ = assertEquals(team, "0", "team 子代理会话必须不入库")
        _ = assertEquals(main, "1", "Nebula 主会话应入库 1 条")
      yield ()
    }
  }

  test("NoData：空数据根（无 _index.json）→ NoData，不产生 memory-db") {
    withTempDataRoot { root =>
      val state = MemoryRecallDaemon.unsafeState
      for
        out <- MemoryRecallDaemon.tickRun(state, minIntervalMs = 0L)
        _ = assertEquals(out, MemoryRecallDaemon.TickOutcome.NoData: MemoryRecallDaemon.TickOutcome)
        _ = assert(!os.exists(root / "memory-db" / "memory.db"))
      yield ()
    }
  }

  test("NoTool：三处候选全落空 → NoTool（不占节流窗，工具补上后下拍即可恢复）") {
    withTempDataRoot { root =>
      IO {
        val originalUserDir = sys.props("user.dir")
        sys.props("user.dir") = root.toString // 掐掉 <cwd>/tools 候选（prop/数据根候选本就无脚本）
        val state = MemoryRecallDaemon.unsafeState
        try
          val outcome = MemoryRecallDaemon.tickRun(state, minIntervalMs = 0L).unsafeRunSync()
          assertEquals(outcome, MemoryRecallDaemon.TickOutcome.NoTool: MemoryRecallDaemon.TickOutcome)
          val st = state.get.unsafeRunSync()
          assertEquals(st.lastAttemptMs, 0L, "NoTool 不是尝试，不得推进节流窗")
          assertEquals(st.warnedNoTool, true, "首见应置 WARN 簿记（连见降 debug）")
        finally sys.props("user.dir") = originalUserDir
      }
    }
  }

  test("kill-switch：nebflow.memoryRecall.enabled=false → tickRun Disabled 且 launch 零纤维空转") {
    withTempDataRoot { _ =>
      IO {
        val original = sys.props.get("nebflow.memoryRecall.enabled")
        sys.props("nebflow.memoryRecall.enabled") = "false"
        try
          val state = MemoryRecallDaemon.unsafeState
          val out = MemoryRecallDaemon.tickRun(state, minIntervalMs = 0L).unsafeRunSync()
          assertEquals(out, MemoryRecallDaemon.TickOutcome.Disabled: MemoryRecallDaemon.TickOutcome)
          MemoryRecallDaemon.launch(1.millis, 1L).unsafeRunSync() // 开关关 ⇒ 零纤维空转
        finally
          original match
            case Some(v) => sys.props("nebflow.memoryRecall.enabled") = v
            case None    => sys.props.remove("nebflow.memoryRecall.enabled")
      }
    }
  }

  test("工具解析：prop 显式覆盖 > <dataRoot>/tools > <cwd>/tools（仓根兜底命中真脚本）") {
    IO {
      val tmp = os.temp.dir(prefix = "nb-memory-recall-tool-")
      val original = sys.props.get("nebflow.memoryRecall.toolPath")
      try
        val fake = tmp / "fake-stand-recall"
        os.write(fake, "#!/bin/sh\nexit 0\n")
        sys.props("nebflow.memoryRecall.toolPath") = fake.toString
        assertEquals(MemoryRecallDaemon.resolveToolPath(tmp), Some(fake))
        sys.props.remove("nebflow.memoryRecall.toolPath")
        // dataRoot 候选（tmp/tools/stand-recall）不存在 → 回落 <cwd>/tools（仓根真脚本）
        val fallback = MemoryRecallDaemon.resolveToolPath(tmp)
        assertEquals(fallback.map(_.last), Some("stand-recall"))
        assert(fallback.exists(p => p.toString.endsWith("tools/stand-recall")), s"仓根兜底应命中真脚本: $fallback")
      finally
        original match
          case Some(v) => sys.props("nebflow.memoryRecall.toolPath") = v
          case None    => sys.props.remove("nebflow.memoryRecall.toolPath")
        os.remove.all(tmp)
    }
  }
end MemoryRecallDaemonSpec
