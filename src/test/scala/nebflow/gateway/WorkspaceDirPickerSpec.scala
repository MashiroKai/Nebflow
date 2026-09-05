package nebflow.gateway

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** WorkspaceDirPicker 三态 + 单飞护栏验收（workspace-picker 批次）。
  *
  * 全部走注入 Env（headless 探测 + opener）——spec 层永不构造真实 AWT 对话框
  * （作者屏幕禁弹真框）。真实 GUI 链路由《作者真机走查清单》人工覆盖；隔离实例
  * 只实测 isHeadless 值与 Toolkit 初始化，见报告。
  *
  * 变异验红锚点：test ① headless → fallback。摘除 pick 的 headless gate 后，
  * 该用例走 opener 注入路径返回 path 事件（绝不会真开对话框——opener 已注入），
  * 断言 reason=="headless-jvm" 失败 → 红；恢复 gate → 绿。
  */
class WorkspaceDirPickerSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  override def beforeEach(context: munit.BeforeEach): Unit = WorkspaceDirPicker.resetForTest()
  override def afterEach(context: munit.AfterEach): Unit = WorkspaceDirPicker.resetForTest()

  private def capture(): IO[(Ref[IO, List[Json]], Json => IO[Unit])] =
    Ref.of[IO, List[Json]](Nil).map(r => (r, (j: Json) => r.update(_ :+ j)))

  private def firstFrame(frames: Ref[IO, List[Json]]): IO[Json] =
    frames.get.map(_.headOption.getOrElse(fail("no frame captured")))

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 20.millis)(
      cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError("waitUntil: timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  // ① headless gate（变异验红锚点）——opener 注入保证摘 gate 变红时也不弹真框
  test("① headless=true → 即时 fallback 事件 reason=headless-jvm；gate 未被占用") {
    for
      (frames, wsSend) <- capture()
      opened <- Ref.of[IO, Boolean](false)
      env = WorkspaceDirPicker.Env(
        headless = () => true,
        opener = Some(() => opened.set(true).as(Some("/should-not-open")))
      )
      _ <- WorkspaceDirPicker.pick("sid-1", "rid-1", wsSend, env)
      f <- firstFrame(frames)
      openedFlag <- opened.get
      busy <- IO(WorkspaceDirPicker.busy)
    yield
      assertEquals(f.hcursor.get[String]("type").toOption, Some("workspaceDirPicked"))
      assertEquals(f.hcursor.get[String]("reason").toOption, Some("headless-jvm"), "mutation anchor: removing the headless gate turns this red (path event instead of fallback)")
      assertEquals(f.hcursor.get[Boolean]("fallback").toOption, Some(true))
      assertEquals(f.hcursor.get[String]("sessionId").toOption, Some("sid-1"))
      assertEquals(f.hcursor.get[String]("requestId").toOption, Some("rid-1"))
      assert(!openedFlag, "opener must NOT be invoked when headless gate fires")
      assert(!busy, "gate must stay released on the fallback path")
    end for
  }

  // ② 选中 → path 事件（三态之一）
  test("② headless=false + opener 选中 → path 事件（sessionId/requestId 透传）") {
    for
      (frames, wsSend) <- capture()
      env = WorkspaceDirPicker.Env(headless = () => false, opener = Some(() => IO.sleep(10.millis).as(Some("/tmp/ws-chosen"))))
      _ <- WorkspaceDirPicker.pick("sid-2", "rid-2", wsSend, env)
      f <- firstFrame(frames)
      busy <- IO(WorkspaceDirPicker.busy)
    yield
      assertEquals(f.hcursor.get[String]("path").toOption, Some("/tmp/ws-chosen"))
      assertEquals(f.hcursor.get[String]("sessionId").toOption, Some("sid-2"))
      assertEquals(f.hcursor.get[String]("requestId").toOption, Some("rid-2"))
      assertEquals(f.hcursor.get[Boolean]("cancelled").toOption, None)
      assertEquals(f.hcursor.get[Boolean]("fallback").toOption, None)
      assert(!busy, "gate must release after the opener returns")
    end for
  }

  // ③ 取消 → cancelled 事件（三态之二）
  test("③ opener 取消（None）→ cancelled 事件") {
    for
      (frames, wsSend) <- capture()
      env = WorkspaceDirPicker.Env(headless = () => false, opener = Some(() => IO.pure(None)))
      _ <- WorkspaceDirPicker.pick("sid-3", "rid-3", wsSend, env)
      f <- firstFrame(frames)
    yield
      assertEquals(f.hcursor.get[Boolean]("cancelled").toOption, Some(true))
      assertEquals(f.hcursor.get[String]("path").toOption, None)
      assertEquals(f.hcursor.get[Boolean]("fallback").toOption, None)
    end for
  }

  // ④ 对话框异常 → fallback 事件带 reason（三态之三）
  test("④ opener 抛异常 → fallback 事件携带 reason") {
    for
      (frames, wsSend) <- capture()
      env = WorkspaceDirPicker.Env(
        headless = () => false,
        opener = Some(() => IO.raiseError(new RuntimeException("dialog exploded")))
      )
      _ <- WorkspaceDirPicker.pick("sid-4", "rid-4", wsSend, env)
      f <- firstFrame(frames)
      busy <- IO(WorkspaceDirPicker.busy)
    yield
      assertEquals(f.hcursor.get[Boolean]("fallback").toOption, Some(true))
      assert(f.hcursor.get[String]("reason").toOption.exists(_.contains("dialog exploded")), s"reason must carry the failure: $f")
      assert(!busy, "gate must release on the error path")
    end for
  }

  // ⑤ 单飞护栏：对话框在开时重复 pick 被忽略（不回 fallback、不发帧、不占 gate）
  test("⑤ 单飞：首个 pick 在飞时第二个 pick 无帧无副作用；释放后 gate 归零") {
    for
      (frames1, wsSend1) <- capture()
      (frames2, wsSend2) <- capture()
      gate <- Deferred[IO, Unit]
      slowOpener = () => gate.get.as(Some("/in-flight-path"))
      env = WorkspaceDirPicker.Env(headless = () => false, opener = Some(slowOpener))
      // 良性 opener：即便单飞护栏被变异破坏，重复 pick 也绝不触发真对话框
      benign = WorkspaceDirPicker.Env(headless = () => false, opener = Some(() => IO.pure(Some("/duplicate-must-not-run"))))
      fib1 <- WorkspaceDirPicker.pick("sid-5", "rid-A", wsSend1, env).start
      _ <- waitUntil(5.seconds)(IO(WorkspaceDirPicker.busy)) // gate 已被首个占用
      _ <- WorkspaceDirPicker.pick("sid-5", "rid-B", wsSend2, benign) // 重复点击 → 忽略
      _ <- IO.sleep(50.millis)
      dupFrames <- frames2.get
      _ <- gate.complete(())
      _ <- fib1.join
      f1 <- firstFrame(frames1)
      busy <- IO(WorkspaceDirPicker.busy)
    yield
      assertEquals(dupFrames, List.empty[Json], "duplicate pick must be ignored silently (real dialog still on screen)")
      assertEquals(f1.hcursor.get[String]("path").toOption, Some("/in-flight-path"))
      assertEquals(f1.hcursor.get[String]("requestId").toOption, Some("rid-A"))
      assert(!busy, "gate must release after the in-flight dialog closes")
    end for
  }

end WorkspaceDirPickerSpec
