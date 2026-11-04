package nebflow.db.chaos

import cats.effect.unsafe.implicits.global
import io.circe.Json
import nebflow.db.{Db, DbConfig, DbHandle, PendingRecordTest}
import nebflow.db.format.GuardDocs

import java.nio.file.{Files, Path, StandardCopyOption}

/**
 * OS 级 kill -9 chaos 批（db 接线二阶 B4）——子进程侧。
 *
 * 由 [[DbKill9ChaosSpec]] 以 `java -cp <test classpath> nebflow.db.chaos.DbChaosChild
 * <mode> <dataRoot> <markerDir> [count]` 直启（forked 测试 JVM 的 java.class.path
 * 携带完整测试面），marker **文件**握手（stdout 可能被缓冲/吞掉，文件面可靠；
 * 出现即完整——tmp 名写 + ATOMIC_MOVE 落正名）：
 *  - `writer`：开库 → guard 档 + permission/ask 待审单 → marker `ready`(gen) →
 *    批量 appendEvent（每 25 条 marker `w<idx>`）→ 完成后 marker `done`；
 *  - `hold`：开库持锁 → marker `ready`(gen) → 长睡（供跨进程双开/被杀）；
 *  - `tryopen`：尝试开库 → marker `result`（`OK <gen>` / `REFUSED <msg>`）→ 退出。
 */
object DbChaosChild:

  private def writeMarker(dir: Path, name: String, content: String): Unit =
    Files.createDirectories(dir)
    val tmp = dir.resolve(s"$name.mkr")
    Files.writeString(tmp, content)
    try
      Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE)
    catch
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)

  private def kernelEvent(i: Int): Json = Json.obj(
    "type" -> Json.fromString("node-created"),
    "occurredAt" -> Json.fromLong(System.currentTimeMillis()),
    "sessionId" -> Json.Null,
    "seqInSession" -> Json.Null,
    "payload" -> Json.obj("summary" -> Json.fromString(s"chaos-$i"))
  )

  private def openOrDie(root: Path): DbHandle =
    Db.open(DbConfig(root)).unsafeRunSync()

  def main(args: Array[String]): Unit =
    args.toList match
      case mode :: rootStr :: markerStr :: rest =>
        val root = Path.of(rootStr)
        val markers = Path.of(markerStr)
        mode match
          case "writer" =>
            val count = rest.headOption.map(_.toInt).getOrElse(100000)
            val h = openOrDie(root)
            // 崩前事实面：guard 档 + 两类待审单（V8/V6 进程级不丢断言的输入）。
            h.writeAvoidGuard(GuardDocs.AvoidGuardDoc(
              version = 1, source = Db.AvoidGuardSource,
              avoidUntil = Map("chaos/p1" -> (System.currentTimeMillis() + 3_600_000L)),
              updatedAt = System.currentTimeMillis())).unsafeRunSync()
            h.openPending(PendingRecordTest.card("chaos-perm-1", kind = "Permission"))
              .unsafeRunSync()
            h.openPending(PendingRecordTest.card("chaos-ask-1", kind = "AskUser"))
              .unsafeRunSync()
            writeMarker(markers, "ready", h.instanceLockGeneration.toString)
            var i = 0
            while i < count do
              h.appendEvent(kernelEvent(i), Some(s"chaos-$i")).unsafeRunSync()
              if (i + 1) % 25 == 0 then writeMarker(markers, s"w${i + 1}", (i + 1).toString)
              i += 1
            writeMarker(markers, "done", count.toString)
            h.close()
          case "hold" =>
            val h = openOrDie(root)
            writeMarker(markers, "ready", h.instanceLockGeneration.toString)
            Thread.sleep(120_000L) // 持锁等待被 taskkill（正常退出走不到 close）
            h.close()
          case "tryopen" =>
            val outcome =
              try
                val h = openOrDie(root)
                val gen = h.instanceLockGeneration
                h.close()
                s"OK $gen"
              catch
                case e: Throwable => s"REFUSED ${e.getClass.getSimpleName}: ${e.getMessage}"
            writeMarker(markers, "result", outcome)
          case other =>
            System.err.println(s"unknown mode: $other")
            sys.exit(2)
      case _ =>
        System.err.println("usage: DbChaosChild <writer|hold|tryopen> <dataRoot> <markerDir> [count]")
        sys.exit(2)

end DbChaosChild
