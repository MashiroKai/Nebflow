package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.db.{DbHandle, DbHost, ReadResult}
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * db 接线第一阶 C6 —— 待办/定时任务宿主落库（写穿影子 + 坏档恢复读）：
 *
 *  1. 写穿：addTask / deleteTask / upsertTaskByName / markTriggered 的差量都落
 *     db schedule 流（`liveScheduleTasks` 折叠视图与旧档一致）；旧档仍是主读面。
 *  2. 坏档收编（D-5 标签③派生面）：损坏 `<sessionId>.json` ⇒ Quarantine 隔离
 *     （字节保全，改名 `.corrupt-<ts>`，扫描面自动跳过）+ db 影子恢复读；
 *     db 无档 ⇒ 缺席起步（WARN 不静默）。
 *  3. best-effort：db 句柄关闭后主链照常（写成功、影子腿 WARN、不抛）。
 *  4. 未接线零波及：临时根直构（无注入且非默认目录）⇒ 不碰 db。
 *  5. 生产形态判别：默认目录构造 ⇒ 自开共享句柄（同根恰一 handle）。
 */
class ScheduledTaskStoreDbSpec extends FunSuite:

  private def withDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val prev = PathUtil.dataRoot
    val root = os.Path(Files.createTempDirectory("sched-db-root"))
    for
      _ <- IO(PathUtil.setDataRoot(root))
      a <- f(root).guarantee(IO {
        DbHost.release(root.toNIO)
        PathUtil.setDataRoot(prev)
      })
    yield a

  private def task(id: String, sess: String, at: Long, name: Option[String] = None,
                   kind: Option[String] = None) =
    ScheduledTask(id = id, sessionId = sess, content = s"c-$id",
      triggerAt = at, createdAt = at, name = name, kind = kind)

  private def live(h: DbHandle, sess: String): List[ScheduledTask] =
    h.liveScheduleTasks(sess).unsafeRunSync() match
      case ReadResult.Present(rows) => rows.flatMap(_.taskDef.as[ScheduledTask].toOption)
      case other => fail(s"db live 应 Present，got $other")

  test("C6-1 写穿：add/delete/upsert/markTriggered 差量落 db，live 视图与旧档一致"):
    withDataRoot { root =>
      val db = DbHost.openBestEffort(root.toNIO)
      assert(db.isDefined, "前置：共享句柄在场")
      val store = new ScheduledTaskStore(root / "scheduled-tasks", db)
      for
        _ <- store.addTask(task("t1", "s1", 1000L))
        _ <- store.addTask(task("t2", "s1", 2000L, name = Some("例行")))
        a1 <- store.loadTasks("s1")
        d1 = live(db.get, "s1")
        _ = assertEquals(a1.map(_.id).toSet, Set("t1", "t2"))
        _ = assertEquals(d1.map(_.id).toSet, Set("t1", "t2"), "影子面同步在场")
        _ <- store.deleteTask("s1", "t1")
        a2 <- store.loadTasks("s1")
        _ = assertEquals(a2.map(_.id), List("t2"))
        _ = assertEquals(live(db.get, "s1").map(_.id), List("t2"), "tombstone 后 db 缺席")
        // upsert 同名替换：旧 id 消失 + 新 id 落库（跨面折叠单份）。
        reps <- store.upsertTaskByName(task("t3", "s1", 3000L, name = Some("例行")))
        _ = assertEquals(reps.map(_.id), List("t2"), "同名旧任务被替换返回")
        a3 <- store.loadTasks("s1")
        d3 = live(db.get, "s1")
        _ = assertEquals(a3.map(_.id), List("t3"))
        _ = assertEquals(d3.map(_.id), List("t3"), "db 面同名收敛单份")
        // markTriggered ⇒ 变更差量（同 id 取最后行）。
        _ <- store.markTriggered("s1", "t3")
        d4 = live(db.get, "s1")
        _ = assertEquals(d4.map(_.id), List("t3"), "折叠后 t3 单行")
        _ = assert(d4.head.triggered, "触发态写穿（折叠最后行带 triggered）")
      yield ()
    }

  test("C6-2 坏档：隔离+db 恢复读；无 db 档 ⇒ 隔离+缺席起步（坏档字节保全）"):
    withDataRoot { root =>
      val db = DbHost.openBestEffort(root.toNIO)
      val dir = root / "scheduled-tasks"
      val store = new ScheduledTaskStore(dir, db)
      for
        _ <- store.addTask(task("r1", "sr", 1000L))
        _ <- store.addTask(task("r2", "sr", 2000L))
        // 注入撕裂：整文件覆成非法 JSON。
        _ <- IO.blocking(os.write.over(dir / "sr.json", "{ 撕裂"))
        restored <- store.loadTasks("sr")
        _ = assertEquals(restored.map(_.id).toSet, Set("r1", "r2"), "坏档 ⇒ db 影子恢复读")
        iso = os.list(dir).filter(_.last.contains(".corrupt-")).toList
        _ = assertEquals(iso.size, 1, "隔离档在场（字节保全，改名不删）")
        _ = assertEquals(os.read(iso.head), "{ 撕裂", "隔离档=原字节")
        // 无 db 档的会话坏档 ⇒ 缺席起步。
        _ <- IO.blocking(os.write.over(dir / "sx.json", "[ 坏"))
        none <- store.loadTasks("sx")
        _ = assertEquals(none, Nil, "db 无档 ⇒ 按缺席起步")
      yield ()
    }

  test("C6-3 best-effort：句柄关闭后主链照常（写成功、影子腿不抛）"):
    withDataRoot { root =>
      val db = DbHost.openBestEffort(root.toNIO)
      val store = new ScheduledTaskStore(root / "scheduled-tasks", db)
      for
        _ <- store.addTask(task("b1", "sb", 1000L))
        _ <- IO(db.get.close())
        _ <- store.addTask(task("b2", "sb", 2000L)) // 影子腿失败 ⇒ WARN，不抛
        got <- store.loadTasks("sb")
        _ = assertEquals(got.map(_.id).toSet, Set("b1", "b2"), "旧档主链零阻断")
      yield ()
    }

  test("C6-4 未接线零波及：临时根直构 ⇒ 不碰 db、行为=现状"):
    withDataRoot { root =>
      val store = new ScheduledTaskStore(root / "elsewhere") // 非默认目录且未注入
      for
        _ <- store.addTask(task("n1", "sn", 1000L))
        got <- store.loadTasks("sn")
        _ = assertEquals(got.map(_.id), List("n1"))
        _ = assert(!os.exists(root / "db"), "默认根 db 树未被创建（零 db 产出）")
      yield ()
    }

  test("C6-5 生产形态判别：默认目录构造 ⇒ 自开共享句柄并写穿"):
    withDataRoot { root =>
      val store = new ScheduledTaskStore(ScheduledTaskStore.defaultBaseDir) // 无注入
      for
        _ <- store.addTask(task("p1", "sp", 1000L))
        got <- store.loadTasks("sp")
        _ = assertEquals(got.map(_.id), List("p1"))
        // 自开句柄 = DbHost 同根单例：再取同根句柄 ⇒ 同一 handle。
        again = DbHost.openBestEffort(root.toNIO)
        d = live(again.get, "sp")
        _ = assertEquals(d.map(_.id), List("p1"), "自开腿真实写穿")
        _ <- IO(DbHost.release(root.toNIO))
      yield ()
    }
end ScheduledTaskStoreDbSpec
