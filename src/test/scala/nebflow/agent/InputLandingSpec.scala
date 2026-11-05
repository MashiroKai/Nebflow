package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.core.mail.InputMailbox
import nebflow.shared.PathUtil

/**
 * P3 节点 3（主腿最小闭环）验收 —— `InputLanding` 门的三条承重断言。
 *
 * 作者 2026-11-05 10:46 裁定的最小集，逐条对应：
 *   - 正常路径：落盘 → 取走（taken 驻留）→ turn 成功后 consume ⇒ taken 清空 + 墓碑；
 *   - 同 cid 重发同一帧 ⇒ **不得产生第二次执行**（Duplicate 短路）；
 *   - 顺序：同会话连续 N 条不乱。
 *
 * 「crash 在 turn 中途」= 只 land 不 consume（条目驻留 taken-input）⇒ 重放面可见；
 * 真实进程级崩溃注入（Runtime.halt）属节点 4 的隔离夹具，本 spec 只验存储面语义。
 */
class InputLandingSpec extends CatsEffectSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    tmp = os.Path(java.nio.file.Files.createTempDirectory("nb-input-landing"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    if tmp != null && os.exists(tmp) then os.remove.all(tmp)

  private val sid = Some("sess-landing")

  private def imm(text: String, cid: Option[String]): AgentCommand.ImmediateInput =
    AgentCommand.ImmediateInput(text = text, fromUser = true, clientMessageId = cid)

  test("正常路径：land 后条目驻留 taken-input，consume 后清空并落墓碑") {
    (for
      landed <- InputLanding.landImmediate(sid, imm("hello", Some("cid-1")))
      during <- InputMailbox.scanTaken(sid.get)
      _ <- InputLanding.consume(sid, landed)
      after <- InputMailbox.scanTaken(sid.get)
      delivered <- InputMailbox.deliveredContains(sid.get, Set("cid-1"))
    yield (landed, during.map(_.identity), after.map(_.identity), delivered)).map {
      case (landed, during, after, delivered) =>
        assertEquals(landed, InputLanding.LandOutcome.Landed("cid-1"))
        assertEquals(during, List("cid-1"), "turn 执行期间条目必须仍在盘上（D3 前提）")
        assertEquals(after, Nil, "turn 成功后 taken-input 清空")
        assertEquals(delivered, Set("cid-1"), "墓碑记录已执行身份")
    }
  }

  test("同 cid 重发 ⇒ 第二次为 Duplicate（执行计数恒 = 1）") {
    (for
      first <- InputLanding.landImmediate(sid, imm("hello", Some("cid-2")))
      // turn 在飞（未 consume）时客户端重发同一帧
      second <- InputLanding.landImmediate(sid, imm("hello", Some("cid-2")))
      taken <- InputMailbox.scanTaken(sid.get)
      // turn 完成后再重发
      _ <- InputLanding.consume(sid, first)
      third <- InputLanding.landImmediate(sid, imm("hello", Some("cid-2")))
    yield (first, second, taken.size, third)).map { case (first, second, takenCount, third) =>
      assertEquals(first, InputLanding.LandOutcome.Landed("cid-2"))
      assertEquals(second, InputLanding.LandOutcome.Duplicate, "turn 在飞时重发必须被挡（不得造第二份）")
      assertEquals(takenCount, 1, "盘上只有一份副本 —— 执行计数语义 = 1")
      assertEquals(third, InputLanding.LandOutcome.Duplicate, "已执行后重发也必须被墓碑挡住")
    }
  }

  test("无 cid 的注入件走 K3 铸造键：不误判重复（每件独立执行）") {
    (for
      a <- InputLanding.landImmediate(sid, imm("sys-1", None))
      b <- InputLanding.landImmediate(sid, imm("sys-1", None))
      taken <- InputMailbox.scanTaken(sid.get)
    yield (a, b, taken.size)).map { case (a, b, takenCount) =>
      assert(a.isInstanceOf[InputLanding.LandOutcome.Landed])
      assert(b.isInstanceOf[InputLanding.LandOutcome.Landed], "同类内容两条合法注入不得被吞")
      assertEquals(takenCount, 2)
    }
  }

  test("顺序：同会话连续 5 条 land 保序（taken-input 名字序 = 入队序）") {
    val texts = List("m1", "m2", "m3", "m4", "m5")
    (for
      _ <- texts.traverse(t => InputLanding.landImmediate(sid, imm(t, Some(s"ord-$t"))))
      taken <- InputMailbox.scanTaken(sid.get)
    yield taken.map(_.payload.text)).map(ids => assertEquals(ids, texts))
  }

  test("无 sid ⇒ Passthrough（不阻断 turn，不写盘）") {
    (for
      out <- InputLanding.landImmediate(None, imm("x", Some("cid-x")))
      depth <- InputMailbox.depth("sess-landing")
    yield (out, depth)).map { case (out, d) =>
      assertEquals(out, InputLanding.LandOutcome.Passthrough("no-session-id"))
      assertEquals(d, 0)
    }
  }
