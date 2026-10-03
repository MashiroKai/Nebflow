package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.FunSuite

/**
 * pop-upgrade batch (2026-10-03) — Pop's artifact payload must fail VISIBLY.
 *
 * Predecessor: the toolfail batch (2026-09-11) pinned the same visibility
 * contract on the Canvas `<img>` pass (a dropped reference must never look
 * like a clean `Opened X in Canvas.`). That face retired with the Canvas
 * direct-open leg; THIS spec pins the same discipline on the payload face the
 * chat renders (`___POP_JSON___` → media stack / file cards):
 *
 *  1. a shown item carries its bytes (`src` data URI) or its servable path;
 *  2. a dropped path is a structured `warnings` entry + a `fileRefs.failed`
 *     count — never silence;
 *  3. a servable item that merely missed the inline policy is `referenced`,
 *     counted but NOT warned (the ticket leg still renders it);
 *  4. the imgref-batch decoded-form disclosure rides the payload `notes`;
 *  5. the URL leg keeps its shipped plain-text face.
 */
class PopToolFileRefSpec extends FunSuite:

  // Phase 5 解耦接线:FileRefs 的端点判据窄端口(生产在 GatewayMain 装配;spec 自接线)。
  nebflow.core.FilePolicyPort.install(nebflow.gateway.NfFilePolicy)

  private val nebulaDef = nebflow.actor.AgentDef(name = "Nebula", description = "", tools = Nil)

  private def tempDir(name: String): os.Path =
    val d = os.pwd / "target" / s"test-poprefs-$name-${java.util.UUID.randomUUID().toString.take(6)}"
    os.makeDir.all(d)
    d

  private def captureCtx(buf: scala.collection.mutable.ListBuffer[Json]): ToolContext =
    ToolContext(
      sessionId = Some("pop-refs-test"),
      sessionStore = None,
      agentDef = Some(nebulaDef),
      agentLibrary = None,
      agentActorRef = None,
      actorSystem = None,
      sharedResources = None,
      depth = 0,
      messages = Nil,
      wsSend = Some((j: Json) => IO { buf += j }),
      projectRoot = ""
    )

  /** Pop `paths` (string or array) → the parsed payload (fails on Left / sentinel absence). */
  private def payloadOf(input: JsonObject, buf: scala.collection.mutable.ListBuffer[Json]): Json =
    PopTool.call(input, captureCtx(buf)).unsafeRunSync() match
      case Left(err) => fail(s"Pop failed: ${err.message}")
      case Right(raw) =>
        assert(raw.startsWith(PopTool.Sentinel), s"result must start with the sentinel: ${raw.take(80)}")
        io.circe.parser.parse(raw.substring(PopTool.Sentinel.length)) match
          case Right(json) => json
          case Left(err)   => fail(s"payload must be pure JSON: $err")

  private def itemsOf(p: Json): List[Json] =
    p.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil)

  private def warningsOf(p: Json): List[Json] =
    p.hcursor.downField("warnings").as[List[Json]].toOption.getOrElse(Nil)

  private def notesOf(p: Json): List[String] =
    p.hcursor.downField("notes").as[List[String]].toOption.getOrElse(Nil)

  private def counter(p: Json, field: String): Int =
    p.hcursor.downField("fileRefs").get[Int](field).toOption.getOrElse(-1)

  private def resolvedOf(w: Json): Option[String] = w.hcursor.get[Option[String]]("resolvedPath").toOption.flatten

  // ── ① 正控：内联零回归 ───────────────────────────────────

  test("positive control: an existing local image is embedded (src data URI) and the payload is clean"):
    val dir = tempDir("inline")
    os.write.over(dir / "shot.png", Array.fill(8)(0x42.toByte))
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val p = payloadOf(JsonObject("filePath" -> (dir / "shot.png").toString.asJson), buf)
    assertEquals(buf.size, 0, "files open no Canvas tab")
    val items = itemsOf(p)
    assertEquals(items.map(_.hcursor.get[String]("kind").toOption.getOrElse("")), List("image"))
    val src = items.head.hcursor.get[String]("src").toOption.getOrElse(fail("src expected"))
    assert(src.startsWith("data:image/png;base64,"), src.take(40))
    assertEquals(counter(p, "inlined"), 1)
    assertEquals(counter(p, "failed"), 0)
    assertEquals(warningsOf(p), Nil)

  // ── ② 负控：结构化告警 ──────────────────────────────────

  test("a missing path warns (not-found) and counts failed=1 — the raw ref and the resolved path both travel"):
    val dir = tempDir("missing")
    val ghost = s"$dir/ghost.png"
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val p = payloadOf(JsonObject("filePath" -> ghost.asJson), buf)
    assertEquals(itemsOf(p), Nil, "nothing to show")
    val w = warningsOf(p).headOption.getOrElse(fail("warnings expected"))
    assertEquals(w.hcursor.get[String]("ref").toOption, Some(ghost), "原始串")
    assertEquals(w.hcursor.get[String]("reason").toOption, Some("not-found"), "失败原因")
    assertEquals(resolvedOf(w), Some(ghost), "解析后路径")
    assertEquals(counter(p, "failed"), 1)

  test("`~`-rooted reference reports the path after expansion (原始串 → 解析后路径 differ)"):
    val dir = tempDir("tilde")
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val p = payloadOf(JsonObject("filePath" -> "~/__poprefs_missing__/plot.png".asJson), buf)
    val w = warningsOf(p).headOption.getOrElse(fail("warnings expected"))
    assertEquals(w.hcursor.get[String]("ref").toOption, Some("~/__poprefs_missing__/plot.png"))
    assertEquals(
      resolvedOf(w),
      Some(s"${sys.props("user.home")}/__poprefs_missing__/plot.png"),
      "resolvedPath is the post-expansion path"
    )

  test("aggregate counters: three failures collapse into counted, distinct warnings"):
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val p = payloadOf(
      JsonObject("filePath" -> Json.arr(
        "/tmp/poprefs-a.png".asJson, "/tmp/poprefs-a.png".asJson, "/tmp/poprefs-b.png".asJson
      )),
      buf
    )
    assertEquals(counter(p, "failed"), 2, "distinct references")
    assertEquals(warningsOf(p).size, 2)
    assertEquals(
      warningsOf(p).map(w => w.hcursor.get[Int]("count").toOption.getOrElse(0)).sum,
      3,
      "the repeated reference carries count=2"
    )

  test("a directory and a non-media file are reported with their own reasons"):
    val dir = tempDir("reasons")
    os.makeDir.all(dir / "bundle.png")
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val p = payloadOf(JsonObject("filePath" -> (dir / "bundle.png").toString.asJson), buf)
    assertEquals(
      warningsOf(p).map(w => w.hcursor.get[String]("reason").toOption.getOrElse("")),
      List("not-regular-file")
    )

  // ── ③ 不刷屏：引用腿只计数 ───────────────────────────────

  test("an oversized image (>5MB) is `referenced`, not warned — the ticket leg still serves it"):
    val dir = tempDir("oversize")
    os.write.over(dir / "big.png", Array.fill(5 * 1024 * 1024 + 1)(0x44.toByte))
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val p = payloadOf(JsonObject("filePath" -> (dir / "big.png").toString.asJson), buf)
    val items = itemsOf(p)
    assertEquals(items.map(_.hcursor.get[String]("kind").toOption.getOrElse("")), List("image"))
    assert(items.head.hcursor.get[String]("src").toOption.isEmpty, "outside the inline policy: no embedded bytes")
    assertEquals(items.head.hcursor.get[Long]("size").toOption, Some(5L * 1024 * 1024 + 1))
    assertEquals(counter(p, "inlined"), 0)
    assertEquals(counter(p, "referenced"), 1)
    assertEquals(counter(p, "failed"), 0)
    assertEquals(warningsOf(p), Nil, "a servable file is not a defect")

  test("a video is `referenced` (never inlined) and carries its path for the ticket leg"):
    val dir = tempDir("video")
    os.write.over(dir / "clip.mp4", Array.fill(64)(0x46.toByte))
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val p = payloadOf(JsonObject("filePath" -> (dir / "clip.mp4").toString.asJson), buf)
    val items = itemsOf(p)
    assertEquals(items.map(_.hcursor.get[String]("kind").toOption.getOrElse("")), List("video"))
    assertEquals(items.head.hcursor.get[String]("path").toOption, Some((dir / "clip.mp4").toString))
    assert(items.head.hcursor.get[String]("src").toOption.isEmpty, "videos always ride the ticket leg")
    assertEquals(counter(p, "referenced"), 1)
    assertEquals(counter(p, "failed"), 0)

  test("a browser-unrenderable image format (tiff) is a FILE card, not a media item"):
    val dir = tempDir("tiff")
    os.write.over(dir / "scan.tiff", Array.fill(8)(0x45.toByte))
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val p = payloadOf(JsonObject("filePath" -> (dir / "scan.tiff").toString.asJson), buf)
    assertEquals(itemsOf(p).map(_.hcursor.get[String]("kind").toOption.getOrElse("")), List("file"))

  // ── ④ imgref 批：解码形态披露 ────────────────────────────

  test("a `%20`-spelled path resolves through its decoded form and the notes disclose which form won"):
    val dir = tempDir("space")
    os.makeDir.all(dir / "space dir")
    os.write.over(dir / "space dir" / "shot.png", Array.fill(8)(0x42.toByte))
    val escaped = (dir / "space%20dir" / "shot.png").toString
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val p = payloadOf(JsonObject("filePath" -> escaped.asJson), buf)
    val items = itemsOf(p)
    assertEquals(items.size, 1, "the decoded form resolves the file")
    assert(items.head.hcursor.get[String]("src").toOption.exists(_.startsWith("data:image/png;base64,")), "it inlines too")
    assert(
      notesOf(p).exists(_.contains("decoded form")),
      s"the disclosure must name the decoded form: ${notesOf(p)}"
    )

  // ── ⑤ 摘要面 ────────────────────────────────────────────

  test("summarizeResult surfaces the counts (failed stays visible in the chat header)"):
    val dir = tempDir("summarize")
    os.write.over(dir / "here.png", Array.fill(8)(0x42.toByte))
    val buf = scala.collection.mutable.ListBuffer.empty[Json]
    val raw = PopTool
      .call(
        JsonObject("filePath" -> Json.arr((dir / "here.png").toString.asJson, (dir / "ghost.png").toString.asJson)),
        captureCtx(buf)
      )
      .unsafeRunSync()
      .toOption
      .getOrElse(fail("expected Right"))
    val input = JsonObject("filePath" -> Json.arr((dir / "here.png").toString.asJson, (dir / "ghost.png").toString.asJson))
    assertEquals(
      PopTool.summarizeResult(input, raw),
      "Pop: 1 media + 0 file card(s) shown in chat — 1 path(s) NOT shown"
    )
    // the URL leg keeps its shipped face
    assertEquals(
      PopTool.summarizeResult(JsonObject("filePath" -> "https://example.com".asJson), "Opened example.com in Canvas."),
      "Opened example.com in Canvas."
    )

  test("description promises the warning channel and the counters"):
    val d = PopTool.description
    assert(d.contains("warnings"), "the description must name the warnings field")
    assert(d.contains("fileRefs"), "the description must name the counters")
end PopToolFileRefSpec
