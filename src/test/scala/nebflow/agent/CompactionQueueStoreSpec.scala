package nebflow.agent

import munit.FunSuite
import cats.effect.unsafe.implicits.global
import nebflow.core.PathUtil
import io.circe.parser.decode
import io.circe.syntax.*
import scala.compiletime.uninitialized

/** F2 (2026-08-30, compact-injection-shield batch 2): durable queue store
  * round-trip, clear-on-empty, corrupt-tolerance, and the codec field matrix
  * (blocks / source / metadata survive the round trip). */
class CompactionQueueStoreSpec extends FunSuite:

  private var prevRoot: os.Path = uninitialized
  private var tmp: os.Path = uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    tmp = os.temp.dir()
    prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(tmp)

  private val imm1 = AgentCommand.ImmediateInput(
    text = "imm-text-1",
    blocks = Some(List(nebflow.shared.ContentBlock.Text("block-1"))),
    source = Some("mail"),
    eventType = Some("delegate"),
    sender = Some("sender-a"),
    senderTeam = Some("team-t"),
    delivery = Some("queue")
  )
  private val imm2 = AgentCommand.ImmediateInput("plain-imm", source = None)
  private val ev1 = AgentCommand.ExternalEvent(
    source = "subtask",
    eventType = "completed",
    payload = "payload-1",
    metadata = io.circe.JsonObject("agentName" -> "sender-b".asJson),
    correlationId = Some("corr-1")
  )
  private val ev2 = AgentCommand.ExternalEvent("bridge", "user-message", "payload-2")

  test("save/load round-trip preserves every field (imms + events)") {
    val q = CompactionQueueStore.PersistedQueues(imms = List(imm1, imm2), events = List(ev1, ev2))
    CompactionQueueStore.save("sid-rt", q).unsafeRunSync()
    val loaded = CompactionQueueStore.load("sid-rt").unsafeRunSync()
    assert(loaded.isDefined, "load must return the persisted queues")
    val got = loaded.get
    assertEquals(got.imms.size, 2)
    assertEquals(got.imms.head.text, "imm-text-1")
    assertEquals(got.imms.head.blocks.map(_.size), Some(1))
    assertEquals(got.imms.head.source, Some("mail"))
    assertEquals(got.imms.head.eventType, Some("delegate"))
    assertEquals(got.imms.head.sender, Some("sender-a"))
    assertEquals(got.imms.head.senderTeam, Some("team-t"))
    assertEquals(got.imms.head.delivery, Some("queue"))
    assertEquals(got.imms(1).text, "plain-imm")
    assertEquals(got.imms(1).source, None)
    assertEquals(got.events.size, 2)
    assertEquals(got.events.head.source, "subtask")
    assertEquals(got.events.head.payload, "payload-1")
    assertEquals(got.events.head.metadata("agentName").flatMap(_.asString), Some("sender-b"))
    assertEquals(got.events.head.correlationId, Some("corr-1"))
    assertEquals(got.events(1).source, "bridge")
    assertEquals(got.events(1).payload, "payload-2")
  }

  test("save of empty queues clears the file") {
    val file = tmp / "data" / "sessions" / "sid-empty" / "injection-queues.json"
    CompactionQueueStore.save("sid-empty", CompactionQueueStore.PersistedQueues(List(imm1), Nil)).unsafeRunSync()
    assert(os.exists(file), "non-empty save must write the file")
    CompactionQueueStore.save("sid-empty", CompactionQueueStore.PersistedQueues()).unsafeRunSync()
    assert(!os.exists(file), "empty save must delete the file")
    assertEquals(CompactionQueueStore.load("sid-empty").unsafeRunSync(), None)
  }

  test("load of a missing file returns None") {
    assertEquals(CompactionQueueStore.load("sid-missing").unsafeRunSync(), None)
  }

  test("load of a corrupt file returns None (warn, not crash)") {
    val file = tmp / "data" / "sessions" / "sid-corrupt" / "injection-queues.json"
    os.makeDir.all(file / os.up)
    os.write(file, "{ not json !")
    assertEquals(CompactionQueueStore.load("sid-corrupt").unsafeRunSync(), None)
  }

  test("save/load with empty sessionId is a no-op") {
    CompactionQueueStore.save("", CompactionQueueStore.PersistedQueues(List(imm1), List(ev1))).unsafeRunSync()
    assertEquals(CompactionQueueStore.load("").unsafeRunSync(), None)
  }

  test("legacy file missing imms/events decodes with empty defaults (forward compat)") {
    val file = tmp / "data" / "sessions" / "sid-legacy" / "injection-queues.json"
    os.makeDir.all(file / os.up)
    os.write(file, """{"imms":[]}""")
    val loaded = CompactionQueueStore.load("sid-legacy").unsafeRunSync()
    assert(loaded.isDefined)
    assertEquals(loaded.get.imms, Nil)
    assertEquals(loaded.get.events, Nil)
  }

end CompactionQueueStoreSpec
