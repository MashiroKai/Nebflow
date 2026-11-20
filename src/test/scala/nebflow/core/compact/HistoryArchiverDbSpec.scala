package nebflow.core.compact

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.FunSuite
import nebflow.db.{DbHost, ReadResult}
import nebflow.shared.{Message, MessageRole, PathUtil}
import nebflow.shared.given

import java.nio.file.Files

/**
 * db 接线第一阶 C7 —— 压缩快照宿主落库（写穿 + compactPoint + 读回校验）：
 *
 *  1. `archiveCompaction` 旧档（before.json/after.json）零改动，仍是主读面。
 *  2. db 影子腿：before/after 双快照 `writeSnapshot` + `readSnapshot` 读回逐字节
 *     对拍；`appendCompactPoint` 落会话 llm 流（refs = 旧档路径，tokens 摘要）。
 *  3. best-effort：句柄关闭 ⇒ 旧档照常、db 腿 WARN 不抛（返回值语义不变）。
 *  4. 未接线零波及：临时根直构 ⇒ 不碰 db；默认根构造 ⇒ 自开共享句柄（生产形态）。
 */
class HistoryArchiverDbSpec extends FunSuite:

  private def withDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val prev = PathUtil.dataRoot
    val root = os.Path(Files.createTempDirectory("archiver-db-root"))
    for
      _ <- IO(PathUtil.setDataRoot(root))
      a <- f(root).guarantee(IO {
        DbHost.release(root.toNIO)
        PathUtil.setDataRoot(prev)
      })
    yield a

  // 定 timestamp 保 asJson.spaces2 逐字节可对拍（默认值 = System.currentTimeMillis 会变）。
  private def msgs(prefix: String, n: Int): List[Message] =
    (1 to n).toList.map(i => Message(MessageRole.User, Left(s"$prefix-$i"), timestamp = 1700000000000L))

  test("C7-1 影子写：双快照落库+读回逐字节对拍+compactPoint 点位行"):
    withDataRoot { root =>
      val db = DbHost.openBestEffort(root.toNIO)
      assert(db.isDefined, "前置：共享句柄在场")
      val archiver = HistoryArchiver.fileSystem(root / "sessions", db)
      for
        res <- archiver.archiveCompaction("s-arc", Some("名字"), "agent-x", msgs("b", 3), msgs("a", 1), "auto")
        arc = res.getOrElse(fail(s"旧档腿必成：$res"))
        // 旧档零改动：两文件在场、内容 = asJson.spaces2。
        _ = assert(os.exists(os.Path(arc.beforeJsonPath)), "before.json 在场")
        _ = assert(os.exists(os.Path(arc.afterJsonPath)), "after.json 在场")
        _ = assertEquals(os.read(os.Path(arc.beforeJsonPath)), msgs("b", 3).asJson.spaces2)
        // db 快照读回（会话 snapshots/<n>.json；n=1/2 两次 writeSnapshot）。
        b <- db.get.readSnapshot("s-arc", 1)
        a <- db.get.readSnapshot("s-arc", 2)
        _ = b match
          case ReadResult.Present(c) => assertEquals(c, msgs("b", 3).asJson.spaces2)
          case other => fail(s"before 快照应 Present，got $other")
        _ = a match
          case ReadResult.Present(c) => assertEquals(c, msgs("a", 1).asJson.spaces2)
          case other => fail(s"after 快照应 Present，got $other")
        // compactPoint 落会话 llm 流。
        sr <- db.get.readSession("s-arc", "llm")
        recs = sr match
          case ReadResult.Present(r) => r.records
          case other => fail(s"llm 流应 Present，got $other")
        _ = assertEquals(recs.size, 1, "点位行恰一")
        _ = assertEquals(recs.head.kind, "compact-point")
        p = recs.head.payload.hcursor
        _ = assertEquals(p.downField("beforeRef").as[String].toOption, Some("snapshots/1.json"), "refs 指 db 快照位")
        _ = assertEquals(p.downField("afterRef").as[String].toOption, Some("snapshots/2.json"))
      yield ()
      end for
    }

  test("C7-2 best-effort：句柄关闭后旧档照常、返回值语义不变"):
    withDataRoot { root =>
      val db = DbHost.openBestEffort(root.toNIO)
      val archiver = HistoryArchiver.fileSystem(root / "sessions", db)
      for
        _ <- IO(db.get.close())
        res <- archiver.archiveCompaction("s-dead", None, "agent-y", msgs("b", 2), msgs("a", 1), "manual")
        arc = res.getOrElse(fail(s"句柄关闭不阻断旧档：$res"))
        _ = assert(os.exists(os.Path(arc.afterJsonPath)), "after.json 照常落盘")
      yield ()
    }

  test("C7-3 未接线零波及 + 默认根自开（生产形态判别）"):
    withDataRoot { root =>
      val plain = HistoryArchiver.fileSystem(root / "elsewhere") // 非默认根、无注入
      for
        r1 <- plain.archiveCompaction("s-plain", None, "a", msgs("b", 1), msgs("a", 1), "auto")
        _ = assert(r1.isRight, "旧档照常")
        _ = assert(!os.exists(root / "db"), "零 db 产出")
        // 默认根构造 ⇒ 自开共享句柄并真实写穿。
        prod = HistoryArchiver.fileSystem(HistoryArchiver.defaultSessionsRoot)
        r2 <- prod.archiveCompaction("s-prod", None, "a", msgs("b", 2), msgs("a", 1), "auto")
        _ = assert(r2.isRight)
        peek = DbHost.openBestEffort(root.toNIO)
        sp <- peek.get.readSnapshot("s-prod", 1)
        _ = sp match
          case ReadResult.Present(c) => assertEquals(c, msgs("b", 2).asJson.spaces2, "自开腿真实写穿")
          case other => fail(s"生产形态快照应 Present，got $other")
        _ <- IO(DbHost.release(root.toNIO))
      yield ()
      end for
    }
end HistoryArchiverDbSpec
