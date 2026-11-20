package nebflow.core.project

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.FunSuite
import nebflow.db.{DbHost, ReadResult}
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * db 接线第一阶 C5/P5 —— 事件流宿主写 db（KernelEvent 对接，D-12 对拍）：
 *
 *  1. `append` 主档（旧 JSONL）零改动；共享句柄在场 ⇒ 同笔追加 db events 流
 *     （存储层 envelope {seq,checksum,dedupKey} 包内核 KernelEvent 原样）。
 *  2. 内核 wire 字段对拍 pilogic KernelEvent 定稿五键（type/occurredAt/
 *     sessionId/seqInSession/payload；subject 归 payload——pilogic 包零改动，
 *     只 import）。
 *  3. dedupKey = `project:node:type:ts`（重放锚）。
 *  4. 影子失败不动主档（best-effort）。
 *
 * 激活口径判据：影子写只在 DbHost **已注册**句柄时跑（peek 只查不开）——
 * 未注册 ⇒ 主档照常、db 零产出（测试 JVM 常态）。
 */
class FlowMapEventLogDbSpec extends FunSuite:

  // PathUtil.dataRoot 是全局换根缝——本 suite 换根必还原（TargetDirNoSideEffectSpec 先例）。
  private def withDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val prev = PathUtil.dataRoot
    val root = os.Path(Files.createTempDirectory("evlog-db-root"))
    for
      _ <- IO(PathUtil.setDataRoot(root))
      a <- f(root).guarantee(IO {
        DbHost.release(root.toNIO)
        PathUtil.setDataRoot(prev)
      })
    yield a

  test("C5: append writes legacy JSONL unchanged AND db events shadow with KernelEvent wire shape"):
    withDataRoot { root =>
      val ws = root / "proj-ws"
      os.makeDir.all(ws)
      val db = DbHost.openBestEffort(root.toNIO)
      assert(db.isDefined, "前置：共享句柄已注册（生产同形）")
      for
        _ <- FlowMapEventLog.append(ws.toString, "ev-proj", "n-1", "node-failed", "boom one")
        _ <- FlowMapEventLog.append(
          ws.toString,
          "ev-proj",
          "n-2",
          "node-completed",
          "done two",
          chainId = Some("chain-x")
        )
        // 主档零改动：两行旧形 {ts,type,project,nodeId,summary(,chainId)}。
        legacy = os.read.lines(ws / ".nebflow" / "flow-map-events.jsonl").toList
        _ = assertEquals(legacy.size, 2, "主档照常逐行")
        l1 = io.circe.parser.parse(legacy(0)).toOption.get.hcursor
        _ = assertEquals(l1.downField("type").as[String].toOption, Some("node-failed"))
        _ = assertEquals(l1.downField("project").as[String].toOption, Some("ev-proj"))
        // db 影子面读回路。
        readBack <- db.get.readEvents(0L, 100, logical = true)
        envs = readBack match
          case ReadResult.Present(sr) => sr.records
          case other => fail(s"影子面应 Present，got $other")
        _ = assertEquals(envs.size, 2, "db events 流两行在场")
        e1 = envs.head
        _ = assert(e1.seq < envs(1).seq, s"存储层 seq 单调（${e1.seq} < ${envs(1).seq}）")
        _ = assertEquals(
          e1.dedupKey,
          Some(
            "ev-proj:n-1:node-failed:" +
              io.circe.parser.parse(legacy(0)).toOption.get.hcursor.downField("ts").as[Long].toOption.get
          ),
          "dedupKey = project:node:type:ts（重放锚）"
        )
        // 内核 wire 对拍（KernelEvent Encoder 五键）。
        inner = e1.event
        keys = inner.asObject.map(_.keys.toList).getOrElse(Nil).sorted
        _ = assertEquals(
          keys,
          List("occurredAt", "payload", "seqInSession", "sessionId", "type"),
          s"内核 envelope 五键逐字对拍（subject 归 payload），got=$keys"
        )
        c = inner.hcursor
        _ = assertEquals(c.downField("type").as[String].toOption, Some("node-failed"))
        _ = assertEquals(c.downField("sessionId").as[String].toOption, Some("ev-proj"))
        _ = assertEquals(c.downField("seqInSession").as[Long].toOption, Some(1L), "会话内序从 1 起")
        _ = assertEquals(
          c.downField("payload").downField("subject").as[String].toOption,
          Some("n-1"),
          "subject 归 payload（KernelEvent 定稿裁定）"
        )
        _ = assertEquals(c.downField("payload").downField("summary").as[String].toOption, Some("boom one"))
        _ = assertEquals(
          envs(1).event.hcursor.downField("payload").downField("chainId").as[Option[String]].toOption,
          Some(Some("chain-x")),
          "chainId 透传入 payload"
        )
      yield ()
      end for
    }.unsafeRunSync()

  test("C5: no registered handle ⇒ legacy append untouched, zero db output (peek 只查不开)"):
    val root = os.Path(Files.createTempDirectory("evlog-nodb-root"))
    val prev = PathUtil.dataRoot
    try
      PathUtil.setDataRoot(root)
      val ws = root / "ws2"
      os.makeDir.all(ws)
      val io = for
        _ <- FlowMapEventLog.append(ws.toString, "quiet", "n-1", "escalated", "no db here")
        legacy = os.read.lines(ws / ".nebflow" / "flow-map-events.jsonl").toList
        _ = assertEquals(legacy.size, 1, "主档照常（影子缺席零影响）")
        _ = assert(!java.nio.file.Files.exists(root.toNIO.resolve("db")), "未注册句柄 ⇒ 影子腿完全不落任何东西（peek 不开根——测试安全边界）")
      yield ()
      io.unsafeRunSync()
    finally PathUtil.setDataRoot(prev)

end FlowMapEventLogDbSpec
