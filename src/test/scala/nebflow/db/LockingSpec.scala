package nebflow.db

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * V5 —— 单写者/世代感知：双实例同 dataRoot ⇒ 第二实例拒启；hot-restart 换代窗内
 * 旧实例翻只读（丢锁后写拒绝），新实例持锁后开写（世代号 +1）。
 */
class LockingSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private def kernelEvent(n: Int): Json = Json.obj(
    "type" -> Json.fromString("node-failed"),
    "occurredAt" -> Json.fromLong(1762224000000L + n),
    "sessionId" -> Json.Null,
    "seqInSession" -> Json.Null,
    "payload" -> Json.obj("summary" -> Json.fromString(s"sample-$n"))
  )

  test("V5: second Db.open on the same dataRoot is refused (single writer)"):
    val root = Files.createTempDirectory("db-lock-spec")
    val a = Db.open(DbConfig(root)).unsafeRunSync()
    try
      intercept[DbError.InstanceLockHeld] {
        Db.open(DbConfig(root)).unsafeRunSync()
      }
      // 双线程竞争开库：第二个拒（同判据——本 JVM 内 FileLock 互斥折算同错误）。
      val second = scala.util.Try(Db.open(DbConfig(root)).unsafeRunSync()).isFailure
      assert(second, "concurrent second open must be refused")
    finally a.close()

  test("V5: hot-restart generation window — stale handle turns read-only, new handle writes"):
    val root = Files.createTempDirectory("db-lock-spec2")
    val a = Db.open(DbConfig(root)).unsafeRunSync()
    val genA = a.instanceLockGeneration
    a.appendEvent(kernelEvent(0), None).unsafeRunSync()
    // 模拟旧实例失锁（进程濒死：OS 锁先行消失，会话残留）。
    a.simulateCrash()
    // 新实例持锁开写：世代号 = 旧世代 + 1。
    val b = Db.open(DbConfig(root)).unsafeRunSync()
    try
      assertEquals(b.instanceLockGeneration, genA + 1L, "generation must advance on re-claim")
      b.appendEvent(kernelEvent(1), None).unsafeRunSync()
      // 旧实例（同一对象仍被引用）写 ⇒ 只读拒绝。
      intercept[DbError.ReadOnly] {
        a.appendEvent(kernelEvent(2), None).unsafeRunSync()
      }
      // 只读态粘滞：后续写同样拒绝。
      intercept[DbError.ReadOnly] {
        a.appendEvent(kernelEvent(3), None).unsafeRunSync()
      }
      // 新实例读面完整：两代写入都在。
      b.readEvents(0L, Int.MaxValue, logical = false).unsafeRunSync() match
        case ReadResult.Present(sr) => assertEquals(sr.records.size, 2)
        case other => fail(s"unexpected $other")
    finally b.close()
    end try

end LockingSpec
