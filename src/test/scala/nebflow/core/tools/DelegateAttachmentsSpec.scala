package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.delegate.DelegateAttachments
import nebflow.shared.AttachContract

import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.util.Base64

/**
 * Delegate 附件传输通道（功能批 2026-11-05，作者令逐字：「你的delegate支持附件传输吗？
 * 不支持的话要补充上，因为我发给你的这些图，你要能传给delegate，才不会丢失信息。」）。
 *
 * 六例机械断言（验收 ④）：
 *   ① 正常 3 附件        ⇒ 清单齐全 + 副本 sha256 == 原件
 *   ② 路径不存在         ⇒ **派发失败**并指名该路径
 *   ③ 超过 9 件          ⇒ 明确报错（件数闸单点 = AttachContract）
 *   ④ 超大文件           ⇒ 明确报错并指名文件
 *   ⑤ 图片               ⇒ 清单标 image=yes + delegate 的 Read 真把它读成视觉内容
 *   ⑥ 无 worktree        ⇒ 副本落 dataRoot 分支且可用
 * 另加两条面钉：brief 末尾清单段与作者手写正文可分（手写路径逐字保留）、schema 上限。
 *
 * 全部零网络、零 ActorSystem、零实例端口：只用本地小文件夹具 + 真实 `Read` 工具。
 */
class DelegateAttachmentsSpec extends CatsEffectSuite:

  // ---------- 夹具 ----------

  private def tempDir(tag: String): Path =
    val d = Files.createTempDirectory(s"delegate-attach-$tag-")
    d.toFile.deleteOnExit()
    d

  /** 写一个小文本附件（内容含随机后缀，避免同一 sha 造成假绿）。 */
  private def writeText(dir: Path, name: String, body: String): Path =
    val p = dir.resolve(name)
    Files.writeString(p, body)
    p

  /** 1×1 透明 PNG（最小合法 PNG 字节；`Read` 的 `.png` 分支据此走视觉通道）。 */
  private def writePng(dir: Path, name: String): Path =
    val bytes = Base64.getDecoder.decode(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )
    val p = dir.resolve(name)
    Files.write(p, bytes)
    p

  /** 独立实现的 sha256（**不复用被测代码**——否则等式两边同源，等于没断言）。 */
  private def expectSha(p: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).map("%02x".format(_)).mkString

  /** 清掉夹具目录（含内容）；失败只忽略——测试断言的绿不靠清理。 */
  private def rm(dirs: Path*): Unit =
    dirs.foreach(d =>
      try os.remove.all(os.Path(d))
      catch case _: Exception => ()
    )

  private def ctx: ToolContext = ToolContext(projectRoot = "")

  private def props: Set[String] =
    DelegateTool.inputSchema("properties").flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  private def attachInput(task: String, paths: List[String]): JsonObject =
    Json
      .obj(
        "task" -> task.asJson,
        "attachments" -> paths.map(_.asJson).asJson
      )
      .asObject
      .get

  // ---------- ① 正常 3 附件 ----------

  test("① 3 attachments: manifest is complete and every copy's sha256 equals its original"):
    val src = tempDir("src1")
    val dataRoot = tempDir("data1")
    val worktree = tempDir("wt1")
    val a = writeText(src, "notes.md", "# notes\nhello ①\n")
    val b = writeText(src, "data.json", """{"k":1}""")
    val c = writePng(src, "shot.png")
    val originals = List(a, b, c)
    val paths = originals.map(_.toString)

    for plan <- DelegateAttachments.stage(paths, "t1", dataRoot)
    yield
      val staged = plan.fold(e => fail(s"stage refused: ${e.message}"), identity)
      assertEquals(staged.size, 3)
      assertEquals(staged.copyDir, dataRoot.resolve("delegate-attachments").resolve("t1").toString)

      // 副本存在 + 逐件 sha256 == 原件（独立实现算的期望值）
      originals.zip(staged.entries).foreach { case (orig, e) =>
        val copy = Path.of(e.stableCopy)
        assert(Files.isRegularFile(copy), s"stable copy missing: $copy")
        assertEquals(e.sizeBytes, Files.size(orig))
        assertEquals(e.sha256, expectSha(orig))
        assertEquals(expectSha(copy), expectSha(orig))
        assertEquals(e.original, orig.toString) // 原件路径逐字保留
        assert(Files.exists(orig), "the ORIGINAL must never be moved or deleted")
      }

      // 清单五读数齐全（副本路径 / 原路径 / 字节数 / sha256 / MIME / 是否图片）
      val manifest = DelegateAttachments.render(staged)
      assert(manifest.startsWith(DelegateAttachments.ManifestHeader), manifest.take(80))
      originals.zip(staged.entries).foreach { case (orig, e) =>
        assert(manifest.contains(e.readPath), s"manifest must list the copy path ${e.readPath}")
        assert(manifest.contains(orig.toString), s"manifest must list the original path $orig")
        assert(manifest.contains(s"${e.sizeBytes} B"), "manifest must carry the byte count")
        assert(manifest.contains(e.sha256), "manifest must carry the sha256")
        assert(manifest.contains(e.mime), "manifest must carry the MIME/type")
      }
      assert(manifest.contains("image=yes"))
      assert(manifest.contains("image=no"))
      assert(manifest.contains(DelegateAttachments.DisciplineHeader))

      // 座位树镜像：另一份副本，sha 仍然等值
      val seated = DelegateAttachments.mirrorToSeat(staged, Some(worktree.toString)).unsafeRunSync()
      seated.entries.foreach { e =>
        val seat = Path.of(e.seatCopy.getOrElse(fail(s"no seat copy for ${e.original}")))
        assert(seat.startsWith(worktree.resolve(".nebflow").resolve("attachments").resolve("t1")))
        assertEquals(expectSha(seat), expectSha(Path.of(e.original)))
      }
      assertEquals(seated.entries.head.readPath, seated.entries.head.seatCopy.get)

      rm(src, dataRoot, worktree)
    end for

  // ---------- ② 路径不存在 ----------

  test("② a missing path FAILS the whole dispatch and names that path"):
    val ok = writeText(tempDir("src2"), "fine.txt", "ok")
    val missing = "/tmp/definitely-not-here-" + java.util.UUID.randomUUID().toString.take(8) + "/gone.png"
    DelegateTool.call(attachInput("do the thing", List(ok.toString, missing)), ctx).map { res =>
      val err = res.swap.getOrElse(fail("dispatch must FAIL when an attachment path does not exist"))
      assert(err.message.contains(missing), s"the error must name the missing path:\n${err.message}")
      assert(err.message.contains("does not exist"), err.message)
      assert(err.message.contains("Nothing was dispatched"), err.message)
      assert(!err.message.contains(ok.toString), "the healthy path must NOT be blamed")
    }

  // ---------- ③ 超过 9 件 ----------

  test("③ more than 9 attachments is an explicit error (no silent truncation)"):
    val paths = (1 to AttachContract.MaxAttachmentsPerMessage + 1).toList.map(n => s"/tmp/att-$n.bin")
    DelegateTool.call(attachInput("do the thing", paths), ctx).map { res =>
      val err = res.swap.getOrElse(fail("11 attachments must be refused"))
      assert(err.message.contains("ATTACH_TOO_MANY") || err.message.contains("Too many"), err.message)
      assert(err.message.contains(AttachContract.MaxAttachmentsPerMessage.toString), err.message)
      assert(err.message.contains("Nothing was dispatched"), err.message)
    }

  // ---------- ④ 超大文件 ----------

  test("④ an oversized file is an explicit error naming the file (sparse fixture, no real GiB)"):
    val dir = tempDir("src4")
    val big = dir.resolve("huge.bin")
    // 稀疏文件：尺寸 = 上限 + 1，占用近零磁盘（只验证闸位读数，不写 1 GiB）。
    val raf = new java.io.RandomAccessFile(big.toFile, "rw")
    try raf.setLength(AttachContract.MaxFileBytes + 1)
    finally raf.close()
    assert(Files.size(big) > AttachContract.MaxFileBytes)

    DelegateTool.call(attachInput("do the thing", List(big.toString)), ctx).map { res =>
      val err = res.swap.getOrElse(fail("an over-limit file must be refused"))
      assert(err.message.contains("ATTACH_TOO_LARGE") || err.message.contains("too large"), err.message)
      assert(err.message.contains(big.toString), s"the error must name the offending file:\n${err.message}")
      assert(err.message.contains(AttachContract.MaxFileBytesLabel), err.message)
      assert(Files.exists(big), "the rejected file must be left untouched")
      rm(dir)
    }

  // ---------- ⑤ 图片 ⇒ 视觉通道 ----------

  test("⑤ an image is marked image=yes AND the delegate's own Read returns vision content"):
    val src = tempDir("src5")
    val dataRoot = tempDir("data5")
    val worktree = tempDir("wt5")
    val png = writePng(src, "screenshot.png")

    for
      stagedEither <- DelegateAttachments.stage(List(png.toString), "t5", dataRoot)
      seated = stagedEither.fold(e => fail(s"stage refused: ${e.message}"), identity)
      mirrored <- DelegateAttachments.mirrorToSeat(seated, Some(worktree.toString))
    yield
      val e = mirrored.entries.head
      assert(e.isImage, "a .png must be classified as an image")
      assertEquals(e.mime, "image/png")
      val manifest = DelegateAttachments.render(mirrored)
      assert(manifest.contains("image=yes"), manifest)
      assert(manifest.contains("image/png"), manifest)

      // 视觉通道证据：delegate 侧的 Read 把副本读成 **vision-ready 图像内容**，
      // 不是二进制文本、不是报错。
      val read = ReadTool.call(Json.obj("file_path" -> e.readPath.asJson).asObject.get, ctx).unsafeRunSync()
      val desc = read.getOrElse(fail(s"Read of the copy failed: ${read.left.map(_.message)}"))
      assert(desc.startsWith("[image: "), s"Read must answer an image descriptor, got: $desc")
      assert(desc.contains("image/png"), desc)

      rm(src, dataRoot, worktree)
    end for

  // ---------- ⑥ 无 worktree ⇒ dataRoot 分支 ----------

  test("⑥ no worktree (read-only dispatch): copies land in the dataRoot branch and stay usable"):
    val src = tempDir("src6")
    val dataRoot = tempDir("data6")
    val doc = writeText(src, "readme.txt", "read-only brief ⑥\n")

    for
      stagedEither <- DelegateAttachments.stage(List(doc.toString), "t6", dataRoot)
      seated <- DelegateAttachments.mirrorToSeat(
        stagedEither.fold(e => fail(s"stage refused: ${e.message}"), identity),
        None
      )
    yield
      assertEquals(seated.copyDir, dataRoot.resolve("delegate-attachments").resolve("t6").toString)
      val e = seated.entries.head
      assertEquals(e.seatCopy, None, "no worktree ⇒ no seat copy (the dataRoot copy is authoritative)")
      assertEquals(e.readPath, e.stableCopy)
      assert(e.stableCopy.startsWith(dataRoot.toString), e.stableCopy)
      assertEquals(expectSha(Path.of(e.stableCopy)), expectSha(doc))
      // 「可用」= delegate 能按清单路径真读到内容
      val read = ReadTool.call(Json.obj("file_path" -> e.readPath.asJson).asObject.get, ctx).unsafeRunSync()
      assert(read.isRight, s"the copy must be readable: ${read.left.map(_.message)}")
      assert(read.toOption.exists(_.contains("read-only brief ⑥")), read.toString)
      rm(src, dataRoot)
    end for

  // ---------- 面钉：brief 末尾清单段 ----------

  test("brief: the manifest is appended at the END, and hand-written paths stay verbatim"):
    val handwritten = "/Users/kaiyu/.stand/uploads/dead-beef/shot-1.png"
    val task = s"按这些图改 — see $handwritten and /tmp/other.png"
    val plan = DelegateAttachments
      .Plan(
        List(
          DelegateAttachments.Entry(
            original = handwritten,
            stableCopy = "/data/delegate-attachments/t7/01-shot-1.png",
            seatCopy = None,
            sizeBytes = 42L,
            sha256 = "0" * 64,
            mime = "image/png",
            isImage = true
          )
        ),
        "/data/delegate-attachments/t7"
      )
    val section = DelegateAttachments.render(plan)
    assert(section.startsWith(DelegateAttachments.ManifestHeader))

    val brief = DelegateTool.assembleBrief(
      workspaceLine = "[workspace] /w",
      bindingLine = None,
      task = task,
      injectedBlock = "",
      includeOutputRule = false,
      attachmentSection = section
    )
    assert(brief.endsWith(section), "the manifest must be the LAST block of the brief")
    assert(brief.contains(task), "the hand-written task text must survive byte-for-byte")
    assert(brief.contains(handwritten), "a hand-written path must NOT be rewritten")
    assert(brief.indexOf(task) < brief.indexOf(DelegateAttachments.ManifestHeader))

    // 空计划 ⇒ brief 逐字节不变（未带附件的派发零漂移）
    val plain = DelegateTool.assembleBrief("[workspace] /w", None, task, "", includeOutputRule = false)
    assert(!plain.contains(DelegateAttachments.ManifestHeader), "no attachments ⇒ no manifest section")
    assert(plain.endsWith(task), "no attachments ⇒ the brief still ends with the task text")

  // ---------- 面钉：schema / 描述 ----------

  test("schema: `attachments` is a model-visible array capped by AttachContract"):
    assert(props.contains("attachments"), s"attachments must be in the schema, got ${props.mkString(",")}")
    val schema = DelegateTool.inputSchema("properties").flatMap(_.asObject).flatMap(_("attachments")).get
    assertEquals(schema.hcursor.downField("type").as[String].toOption, Some("array"))
    assertEquals(
      schema.hcursor.downField("maxItems").as[Int].toOption,
      Some(AttachContract.MaxAttachmentsPerMessage)
    )
    // 失败语义必须写在模型可见文案里（上限 / 显式失败 / 稳定副本 / 视觉通道）
    val doc = DelegateTool.description
    assert(doc.contains("`attachments`"), "the parameter must be documented")
    assert(doc.contains("1 GiB"), doc)
    assert(doc.contains("EXPLICIT error") || doc.contains("explicit error"), doc)
    assert(doc.contains("delegate-attachments"), doc)
    assert(doc.contains("sha256"), doc)
    assert(doc.contains("NEVER") || doc.contains("never moved or deleted"), doc)
end DelegateAttachmentsSpec
