package nebflow.core.scheduler

import munit.FunSuite

/**
 * 定时任务触发**单次身份键**（事件投递绑定批 2026-11-03 · 作者报障逐字
 * 「现在这定时任务怎么都没触发你的轮次了」）。
 *
 * 实证病灶（11-03 10:55，task b65f1355【stand 巡检 · 每小时】，session d7c3cb54）：
 * 触发事件旧 `correlationId = task.id`（跨期恒定），而会话收件口的 intake 闸按
 * 身份键查「已投递回执台账」（DeliveredReceiptLedger，注入即标记、持久）——
 * 首炮（07:55）入账后，08:55 / 09:55 / 10:55 三期**全部**被
 * `external-event-duplicate-suppressed` 静默丢弃：卡片照常落盘（写在路由之前），
 * 但**轮次永不启动**。作者 10:58 手动发言才把卡片内容带进同一轮。
 *
 * 修法：身份键升为单次触发 `<taskId>@<firedAtMs>`。本 spec 钉三面：
 *  ① 键形与唯一性：同任务逐炮键必不同、同炮键恒同（重复投递仍去重）；
 *  ② 逐炮键在 ledger 判重面上的行为：第 2 炮不再撞第 1 炮的已投标记
 *     （改前红：`b65f1355` 二次入站即判重）；
 *  ③ 同窗合并组键同理（同窗重复注入仍去重，跨窗必不同）。
 */
class ScheduledTaskFiringKeySpec extends munit.FunSuite:

  private def ev(correlationId: Option[String]) =
    nebflow.actor.AgentCommand.ExternalEvent(
      source = "scheduled-task",
      eventType = "trigger",
      payload = "【stand 巡检 · 每小时】静默式：一切正常就一行带过",
      metadata = io.circe.JsonObject.empty,
      correlationId = correlationId
    )

  test("① firingKey: 同任务逐炮唯一、同炮恒同") {
    val k1 = ScheduledTaskService.firingKey("b65f1355", 1000L)
    val k2 = ScheduledTaskService.firingKey("b65f1355", 2000L)
    assert(k1 != k2, "🔴 同任务的下一炮必须有不同身份键（改前恒 = 任务 id ⇒ 撞已投台账）")
    assertEquals(ScheduledTaskService.firingKey("b65f1355", 1000L), k1, "同一炮的重复投递键恒同")
    assert(k1.startsWith("b65f1355@"), "键形 <taskId>@<firedAtMs>，taskId 前缀保留可读性")
    assert(k1 != ScheduledTaskService.firingKey("9d0e41d6", 1000L), "不同任务不同键")
  }

  test("② 逐炮键过 DeliveredReceiptLedger 收件闸：第 2 炮放行、同炮重复仍判重（改前红）") {
    import cats.effect.unsafe.implicits.global
    import nebflow.core.processor.DeliveredReceiptLedger
    import nebflow.shared.PathUtil
    val prev = PathUtil.dataRoot
    val tmp = os.temp.dir()
    PathUtil.setDataRoot(tmp / "data")
    val sid = "sched-key-spec"
    try
      val fired1 = 1000L
      val fired2 = 3600000L
      // 身份键**全部走生产函数**（firingKey）——生产若回退成裸任务 id，本测即红。
      val first = ev(Some(ScheduledTaskService.firingKey("b65f1355", fired1)))
      val second = ev(Some(ScheduledTaskService.firingKey("b65f1355", fired2)))
      val io = for
        a1 <- DeliveredReceiptLedger.intakeAccepts(sid, Nil, first)
        _ <- DeliveredReceiptLedger.markDelivered(sid, DeliveredReceiptLedger.identityKeys(first))
        a2 <- DeliveredReceiptLedger.intakeAccepts(sid, Nil, second)
        aReplay <- DeliveredReceiptLedger.intakeAccepts(sid, Nil, first)
        // 病灶锚：模拟修前落盘的已投标记（裸任务 id），看旧键形如何被吞
        _ <- DeliveredReceiptLedger.markDelivered(sid, List("legacy-task-id"))
        aLegacy <- DeliveredReceiptLedger.intakeAccepts(sid, Nil, ev(Some("legacy-task-id")))
      yield (a1, a2, aReplay, aLegacy)
      val (a1, a2, aReplay, aLegacy) = io.unsafeRunSync()
      assertEquals(a1, true, "首炮放行（台账空）")
      assertEquals(
        a2,
        true,
        "🔴 下一期触发必须放行（改前：correlationId = 任务 id ⇒ 撞已投标记，三期触发全部静默丢弃）"
      )
      assertEquals(aReplay, false, "同一炮的重复投递仍判重（exactly-once 语义保留）")
      assertEquals(
        aLegacy,
        false,
        "病灶锚：旧键形（裸任务 id）一旦入账，同任务后续每炮必判重——这就是 11-03 10:55 那一炮被丢的机制"
      )
    finally
      PathUtil.setDataRoot(prev)
      os.remove.all(tmp)
    end try
  }

  test("③ mergedFiringKey: 同窗任务集 + 触发时刻；跨窗必不同、同窗重复恒同") {
    val a = ScheduledTaskService.mergedFiringKey(List("t-2", "t-1"), 5000L)
    val same = ScheduledTaskService.mergedFiringKey(List("t-1", "t-2"), 5000L)
    val other = ScheduledTaskService.mergedFiringKey(List("t-1", "t-2"), 6000L)
    assertEquals(a, same, "任务集排序归一 ⇒ 同窗重复注入仍去重")
    assert(a != other, "跨窗触发键必不同")
    assert(a.startsWith("merged[t-1+t-2]@"), "键形 merged[<sorted ids>]@<firedAtMs>")
    assert(
      ScheduledTaskService.mergedFiringKey(Nil, 1L) != ScheduledTaskService.mergedFiringKey(Nil, 2L),
      "空集退化形态仍随时刻唯一"
    )
  }

end ScheduledTaskFiringKeySpec
