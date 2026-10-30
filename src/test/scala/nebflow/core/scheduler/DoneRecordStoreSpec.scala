package nebflow.core.scheduler

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

/**
 * 认知二期产品批③④：打卡 done 历史（只增 JSONL）——周报聚合数据源。
 * 钉：record/load roundtrip / 窗口查询 / 坏行跳过 / via 双写口标记。
 */
class DoneRecordStoreSpec extends CatsEffectSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-done-records"

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)
    PathUtil.setDataRoot(tempRoot)

  private def rec(taskId: String, doneAt: Long, via: String): DoneRecord =
    DoneRecord(taskId, Some("fitness-checkin"), "今天：深蹲 5×5", "sess-1", doneAt, via)

  test("record → loadAll roundtrip（含 name/via）") {
    val store = DoneRecordStore(tempRoot)
    for
      _ <- store.record(rec("a", 1000L, DoneRecord.ViaTool))
      _ <- store.record(rec("b", 2000L, DoneRecord.ViaWs))
      all <- store.loadAll
      _ = assertEquals(all.length, 2)
      _ = assertEquals(all.head.name, Some("fitness-checkin"))
      _ = assertEquals(all(1).via, "ws")
    yield ()
  }

  test("between 窗口查询：半开区间 [from, to)") {
    val store = DoneRecordStore(tempRoot)
    for
      _ <- store.record(rec("a", 1000L, DoneRecord.ViaTool))
      _ <- store.record(rec("b", 5000L, DoneRecord.ViaTool))
      _ <- store.record(rec("c", 9000L, DoneRecord.ViaTool))
      win <- store.between(2000L, 9000L)
      _ = assertEquals(win.map(_.taskId), List("b"))
    yield ()
  }

  test("坏行跳过不炸整卷；空文件 = Nil") {
    val store = DoneRecordStore(tempRoot)
    for
      empty <- store.loadAll
      _ = assertEquals(empty, Nil)
      _ <- store.record(rec("a", 1000L, DoneRecord.ViaTool))
      _ <- IO.delay {
        os.write.append(tempRoot / "checkin-records" / "done-records.jsonl", "{corrupt\n")
      }
      all <- store.loadAll
      _ = assertEquals(all.length, 1)
      _ = assertEquals(all.head.taskId, "a")
    yield ()
  }

end DoneRecordStoreSpec
