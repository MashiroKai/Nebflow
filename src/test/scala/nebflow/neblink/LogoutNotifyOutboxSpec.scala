package nebflow.neblink

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * 登出收尾批 · 「我登出了」上行通知的 stand 侧发送接口 + 排队点
 * （[[LogoutNotifyOutbox]] 的单测面；设计 §3.5.2 ⑤「离线也记账」）。
 *
 * == 红绿态登记（测试纪律）==
 * 本件是**结构红**的载体：引用尚未存在的 `LogoutNotifyOutbox` ⇒ `Test/compile` 红
 * （本链先红后绿的既有形态，b1-guards 批 T1 同款）；实现落盘后转绿。
 * route 级的**行为红**（现状登出零上行记账）在
 * `nebflow.gateway.LogoutAccountSurfaceSpec` —— 该件零符号引用、按排队点契约字面
 * `<machineRoot>/logout-notify-pending/` 读盘；本件末条用例把该字面与本对象的
 * 单源常量**交叉钉死**（两处任一漂移即红）。
 *
 * == 隔离纪律 ==
 * 机器层根 = 临时目录（绝不用 `~/.stand`），用毕恢复原值并删净（`dir` 每次现读
 * `PathUtil.machineRoot`，钉根后所有读写都落临时目录）。
 */
class LogoutNotifyOutboxSpec extends CatsEffectSuite:

  private var savedMachine: os.Path = scala.compiletime.uninitialized
  private var tmpDir: java.nio.file.Path = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedMachine = PathUtil.machineRoot
    tmpDir = Files.createTempDirectory("logout-uplink-spec")
    PathUtil.setMachineRoot(os.Path(tmpDir, os.pwd))

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setMachineRoot(savedMachine)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  test("enqueue 落一件：accountSub/email/reason 全在盘、id 有序、空 accountSub 是 no-op（不记假账）"):
    for
      _ <- LogoutNotifyOutbox.enqueue("logto|user-A", "a@example.com", LogoutNotifyOutbox.ReasonLiveNotifyFailed)
      rows <- LogoutNotifyOutbox.pending
      _ <- LogoutNotifyOutbox.enqueue("", "", LogoutNotifyOutbox.ReasonNoLiveClient) // 空 sub ⇒ no-op
      rowsAfter <- LogoutNotifyOutbox.pending
    yield
      assertEquals(rows.length, 1, "一条入队 = 一件落盘")
      val e = rows.head
      assertEquals(e.accountSub, "logto|user-A")
      assertEquals(e.email, "a@example.com")
      assertEquals(e.reason, LogoutNotifyOutbox.ReasonLiveNotifyFailed)
      assertEquals(e.attempts, 0, "新件零补投尝试")
      assertEquals(rowsAfter, rows, "空 accountSub = 没有账号域身份 ⇒ no-op，绝不记假账")

  test("入队序 = 字典序：id 以 20 位零填充序号开头（补投按序是文档化不变量）"):
    for
      _ <- LogoutNotifyOutbox.enqueue("logto|user-A", "a@example.com", LogoutNotifyOutbox.ReasonNoLiveClient)
      _ <- LogoutNotifyOutbox.enqueue("logto|user-B", "b@example.com", LogoutNotifyOutbox.ReasonLiveNotifyFailed)
      rows <- LogoutNotifyOutbox.pending
    yield
      assertEquals(rows.length, 2)
      assertEquals(rows.map(_.accountSub), List("logto|user-A", "logto|user-B"), "字典序 = 入队序")
      assert(rows.forall(e => e.id.length >= 20), s"id 必须带 20 位零填充序号前缀: ${rows.map(_.id)}")

  test("drain：send 成功 ⇒ 出队（盘上删净）；send 失败 ⇒ 留盘 + attempts 计数，at-least-once"):
    for
      _ <- LogoutNotifyOutbox.enqueue("logto|user-A", "a@example.com", LogoutNotifyOutbox.ReasonNoLiveClient)
      _ <- LogoutNotifyOutbox.enqueue("logto|user-B", "b@example.com", LogoutNotifyOutbox.ReasonLiveNotifyFailed)
      report1 <- LogoutNotifyOutbox.drain(e =>
        if e.accountSub == "logto|user-A" then IO.unit
        else IO.raiseError(new java.io.IOException("relay down"))
      )
      rows <- LogoutNotifyOutbox.pending
      report2 <- LogoutNotifyOutbox.drain(_ => IO.unit) // 第二轮全部成功 ⇒ 盘清零
      empty <- LogoutNotifyOutbox.pending
    yield
      assertEquals(report1.replayed.length, 1, "成功件出队")
      assertEquals(report1.kept.length, 1, "失败件留盘")
      // id 尾 = safeId(accountSub)：`|` 非白名单字符 ⇒ 替换为 `_`（防穿越的既有纪律）
      assert(report1.replayed.head.endsWith("logto_user-A"), s"出队的是 A: ${report1.replayed.head}")
      assert(rows.exists(_.accountSub == "logto|user-B"), "失败件必须还在盘上")
      assertEquals(
        rows.find(_.accountSub == "logto|user-B").map(_.attempts),
        Some(1),
        "失败 ⇒ attempts 计数 +1（纯读数，不参与判死 —— 零墙钟纪律）"
      )
      assertEquals(report2.replayed.length, 1, "第二轮补投成功")
      assertEquals(empty, Nil, "补投成功才删除 —— at-least-once 的出队臂")

  test("并发入队唯一且序号连续，删除后新事件不重用序号"):
    for
      _ <- (1 to 30).toList.parTraverse_(i => LogoutNotifyOutbox.enqueue(s"u-$i", "", "test"))
      rows <- LogoutNotifyOutbox.pending
      _ = assertEquals(rows.size, 30)
      _ = assertEquals(rows.map(_.id).distinct.size, 30)
      _ = assertEquals(rows.map(_.id.take(20)), rows.map(_.id.take(20)).sorted)
      _ <- LogoutNotifyOutbox.delivered(rows.last.id)
      _ <- LogoutNotifyOutbox.enqueue("last", "", "test")
      next <- LogoutNotifyOutbox.pending
      _ = assert(next.last.id.take(20) > rows.last.id.take(20))
    yield ()

  test("发送失败阻止后续事件越序发送"):
    for
      _ <- LogoutNotifyOutbox.enqueue("first", "", "test")
      _ <- LogoutNotifyOutbox.enqueue("second", "", "test")
      called <- cats.effect.Ref.of[IO, List[String]](Nil)
      report <- LogoutNotifyOutbox.drain(e => called.update(_ :+ e.accountSub) *> IO.raiseError(new Exception("offline")))
      seen <- called.get
      rows <- LogoutNotifyOutbox.pending
      _ = assertEquals(seen, List("first"))
      _ = assertEquals(report.kept.size, 1)
      _ = assertEquals(rows.size, 2)
    yield ()

  test("外部进程持有 drain 锁时拒绝重放，释放后可恢复"):
    for
      _ <- LogoutNotifyOutbox.enqueue("first", "", "test")
      lockPath = LogoutNotifyOutbox.dir / ".drain-lock"
      process <- IO.blocking(new ProcessBuilder("python3", "-u", "-c",
        "import fcntl,sys; f=open(sys.argv[1], 'w'); fcntl.lockf(f, fcntl.LOCK_EX); print('locked',flush=True); sys.stdin.read()",
        lockPath.toString).start())
      result <- (for
        line <- IO.blocking(new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream)).readLine())
        _ = assertEquals(line, "locked")
        blocked <- LogoutNotifyOutbox.drain(_ => IO.unit).attempt
        _ = assert(blocked.isLeft)
        _ <- IO.blocking(process.getOutputStream.close())
        exit <- IO.blocking(process.waitFor())
        _ = assertEquals(exit, 0)
        report <- LogoutNotifyOutbox.drain(_ => IO.unit)
        _ = assertEquals(report.replayed.size, 1)
      yield ()).guarantee(IO.blocking(process.getOutputStream.close()).handleErrorWith(_ => IO.unit))
    yield result

  test("发送后出队失败保留同一幂等键，恢复后再次投递"):
    for
      _ <- LogoutNotifyOutbox.enqueue("first", "", "test")
      rows <- LogoutNotifyOutbox.pending
      first = rows.head
      file = LogoutNotifyOutbox.dir / s"${first.enqueuedAt}-${first.id}.json"
      backup = LogoutNotifyOutbox.dir / "held"
      seen <- cats.effect.Ref.of[IO, List[String]](Nil)
      failed <- LogoutNotifyOutbox.drain(e => seen.update(_ :+ e.id) *> IO.blocking(os.move(file, backup)))
      _ = assertEquals(failed.replayed, Nil)
      _ = assertEquals(failed.kept.size, 1)
      _ <- IO.blocking { if os.exists(file) then os.remove(file); os.move(backup, file) }
      retried <- LogoutNotifyOutbox.drain(e => seen.update(_ :+ e.id))
      ids <- seen.get
      _ = assertEquals(ids, List(first.id, first.id))
      _ = assertEquals(retried.replayed, List(first.id))
    yield ()

  test("损坏记录不会被伪报为空队列"):
    for
      _ <- LogoutNotifyOutbox.enqueue("first", "", "test")
      _ <- IO(os.write.over(LogoutNotifyOutbox.dir / "bad.json", "bad"))
      result <- LogoutNotifyOutbox.drain(_ => IO.unit).attempt
      _ = assert(result.isLeft)
    yield ()

  test("排队目录不可写时显式报告失败"):
    // 机器层根占成文件，入队失败必须可见。
    val fakeRoot = os.Path(tmpDir, os.pwd) / "machine-root-as-file"
    os.write.over(fakeRoot, "not a dir", createFolders = true)
    PathUtil.setMachineRoot(fakeRoot)
    LogoutNotifyOutbox.enqueue("logto|user-A", "a@example.com", LogoutNotifyOutbox.ReasonNoLiveClient)
      .attempt.map(result => assert(result.isLeft))

  test("交叉钉：排队点目录名与 route 级 spec 的盘面读数字面同源（logout-notify-pending）"):
    // LogoutAccountSurfaceSpec 按契约字面读盘（零符号引用以承载行为红）；
    // 本条把该字面与单源常量钉在一起 —— 任一侧漂移即红。
    assertEquals(LogoutNotifyOutbox.PendingDirName, "logout-notify-pending")
    assertEquals(LogoutNotifyOutbox.dir.last, LogoutNotifyOutbox.PendingDirName)

end LogoutNotifyOutboxSpec
