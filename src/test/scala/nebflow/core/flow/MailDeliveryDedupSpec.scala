package nebflow.core.flow

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.shared.Defaults

/**
 * P0 投递层指纹去重 — 组件级五组场景（任务书验收①-⑤）：
 *  ① 窗口内重复丢弃
 *  ② 不同内容放行
 *  ③ 窗口过期放行
 *  ④ 重启重放场景去重（指纹文件跨重启边界存活）
 *  ⑤ 合法重发不误杀（窗口外同内容 / 不同发送方）
 */
class MailDeliveryDedupSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.root

  override def beforeEach(context: BeforeEach): Unit =
    tmp = os.temp.dir(prefix = "mail-dedup")
    PathUtil.setDataRoot(tmp / "data")
    MailDeliveryDedup.reset() // isolate object-global cache between tests

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private val W = Defaults.MailDedupWindowMs

  test("① duplicate fingerprint within window is suppressed") {
    val sid = "sess-recipient-1"
    val fp = MailDeliveryDedup.fingerprint("alice", sid, "same task content")
    val now = System.currentTimeMillis()
    val before = MailDeliveryDedup.suppressedTotal
    val first = MailDeliveryDedup.tryDeliver(sid, fp, now).unsafeRunSync()
    val second = MailDeliveryDedup.tryDeliver(sid, fp, now + 1000).unsafeRunSync()
    assert(first, "first delivery must pass")
    assert(!second, "same fingerprint within the window must be suppressed")
    assertEquals(
      MailDeliveryDedup.suppressedTotal - before, 1L,
      "suppression must bump the dedup counter (WARN+count observability)"
    )
  }

  test("② different content / sender / recipient all pass") {
    val sid = "sess-recipient-2"
    val now = System.currentTimeMillis()
    val a = MailDeliveryDedup.tryDeliver(sid, MailDeliveryDedup.fingerprint("alice", sid, "content A"), now).unsafeRunSync()
    val b = MailDeliveryDedup.tryDeliver(sid, MailDeliveryDedup.fingerprint("alice", sid, "content B"), now).unsafeRunSync()
    val c = MailDeliveryDedup.tryDeliver(sid, MailDeliveryDedup.fingerprint("bob", sid, "content A"), now).unsafeRunSync()
    val d = MailDeliveryDedup.tryDeliver("other-session", MailDeliveryDedup.fingerprint("alice", "other-session", "content A"), now).unsafeRunSync()
    assert(a && b && c && d, s"distinct fingerprints must all pass: a=$a b=$b c=$c d=$d")
  }

  test("③ same fingerprint outside the window passes again") {
    val sid = "sess-recipient-3"
    val fp = MailDeliveryDedup.fingerprint("alice", sid, "recurring report")
    val now = System.currentTimeMillis()
    val first = MailDeliveryDedup.tryDeliver(sid, fp, now).unsafeRunSync()
    // just inside the window — still suppressed
    val edge = MailDeliveryDedup.tryDeliver(sid, fp, now + W - 1).unsafeRunSync()
    // window expired — delivered again
    val after = MailDeliveryDedup.tryDeliver(sid, fp, now + W + 1).unsafeRunSync()
    assert(first, "first delivery must pass")
    assert(!edge, "delivery at window-1ms must still be suppressed")
    assert(after, "delivery after window expiry must pass (no false positive)")
  }

  test("④ restart replay: fingerprint file survives the restart boundary") {
    val sid = "sess-recipient-4"
    val fp = MailDeliveryDedup.fingerprint("alice", sid, "replayed mail")
    val now = System.currentTimeMillis()
    // Pre-restart: delivered once (records to memory AND disk)
    val pre = MailDeliveryDedup.tryDeliver(sid, fp, now).unsafeRunSync()
    assert(pre, "pre-restart delivery must pass")
    // The fingerprint file must exist on disk (persistence is the whole point)
    assert(os.exists(PathUtil.dataRoot / "mail-dedup.json"), "fingerprint file not persisted")
    // Simulate JVM restart: memory cache cleared, disk file survives
    MailDeliveryDedup.reset()
    // Post-restart replay of the same mail within the window — must suppress
    val replay = MailDeliveryDedup.tryDeliver(sid, fp, now + 5000).unsafeRunSync()
    assert(!replay, "replay within window after restart must be suppressed (disk-backed dedup)")
  }

  test("⑤ legitimate re-send not eaten: window expiry and different senders after restart") {
    val sid = "sess-recipient-5"
    val fpAlice = MailDeliveryDedup.fingerprint("alice", sid, "status update")
    val now = System.currentTimeMillis()
    assert(MailDeliveryDedup.tryDeliver(sid, fpAlice, now).unsafeRunSync(), "initial delivery must pass")
    // Restart, then a LEGITIMATE re-send after the window expired
    MailDeliveryDedup.reset()
    val resend = MailDeliveryDedup.tryDeliver(sid, fpAlice, now + W + 1000).unsafeRunSync()
    assert(resend, "re-send after window expiry must deliver (not a false positive)")
    // Restart again, then a DIFFERENT sender with identical content — distinct
    // fingerprint, must deliver even inside the window.
    MailDeliveryDedup.reset()
    val fpBob = MailDeliveryDedup.fingerprint("bob", sid, "status update")
    val bob = MailDeliveryDedup.tryDeliver(sid, fpBob, now + 2000).unsafeRunSync()
    assert(bob, "different sender's identical content is a distinct delivery")
  }

end MailDeliveryDedupSpec
