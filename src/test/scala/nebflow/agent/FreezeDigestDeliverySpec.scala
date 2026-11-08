package nebflow.agent

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import nebflow.core.delegate.DelegateRegistry
import nebflow.shared.PathUtil

class FreezeDigestDeliverySpec extends CatsEffectSuite:
  private val previous = PathUtil.dataRoot
  private var root: os.Path = os.pwd

  override def beforeAll(): Unit =
    root = os.temp.dir(prefix = "freeze-digest-delivery-")
    PathUtil.setDataRoot(root)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(root)

  test("missing or failed sender does not suppress the next digest delivery") {
    val id = "delegate-digest-" + java.util.UUID.randomUUID().toString.take(8)
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id,
        "nebflow",
        root.toString,
        None,
        None,
        "parent",
        "provider task",
        1L,
        frozenSince = Some(System.currentTimeMillis()),
        frozenReason = Some("delegate-frozen:llm-failure"),
        frozenDetail = Some("All providers failed:\n [auth] p/model: Auth — permission denied")
      )
    )
    for
      captured <- Ref.of[IO, List[io.circe.Json]](Nil)
      _ <- FreezeAlertSweep.digestTick(null, None)
      _ <- FreezeAlertSweep.digestTick(null, Some(_ => IO.raiseError(new RuntimeException("connection lost"))))
      _ <- FreezeAlertSweep.digestTick(null, Some(frame => captured.update(_ :+ frame)))
      first <- captured.get
      _ = assertEquals(first.size, 1)
      _ = assert(first.head.hcursor.get[Int]("total").toOption.contains(1))
      _ <- FreezeAlertSweep.digestTick(null, Some(frame => captured.update(_ :+ frame)))
      second <- captured.get
      _ = assertEquals(second.size, 1)
    yield ()
  }
end FreezeDigestDeliverySpec
