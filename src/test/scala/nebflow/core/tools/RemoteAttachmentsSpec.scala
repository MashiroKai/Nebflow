package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.delegate.{DelegateAttachments, RemoteAttachments}
import nebflow.neblink.misc.FileTransferAction
import nebflow.shared.{AttachContract, PeerInfo}

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Base64

/**
 * 跨设备附件送达腿（跨设备附件批 2026-11-05，作者令逐字「这个也要支持」）。
 *
 * ## 桩/真跑边界（报告里逐条申报）
 *
 * - **假的**：网线。[[LoopbackPeer]] 只是把设备面入参原样交给仓库里**真实的接收端实现**
 *   （[[nebflow.neblink.misc.FileTransferAction]]，线上 P2P/relay 两条腿都路由到它）。
 * - **真的**：接收端的块摘要自算、整件摘要自算、offset 追加、归属判据、结构化错误体、
 *   探针回执——全部是被测代码对面那份真实现算出来的；发送侧也是真代码（真分块、真比对）。
 * - **没有跑的**：真设备（`KAI` / `DESKTOP-IJBBEOT`）。新代码要跑在设备腿上必须重启引擎，
 *   本轮红线禁止 ⇒ 如实申报「桩验证」，不以桩冒充实测。
 *
 * ## 覆盖的失败面（作者点名 6 类 + 1 类防御面）
 *
 *   ① 对端离线 ② 对端拒绝（接收端结构化拒绝码）③ 传输中断 ④ 校验和不符
 *   ⑤ 对端磁盘不足 ⑥ 对端引擎过旧（探针不认识 / 回执无自算摘要）⑦ 落点已有非本次字节
 *
 * 每一条都断言三件事：**整批失败**（`Left`）、**点名**（设备名 + 原件路径 + 对端路径）、
 * **reason code**；并且「已落盘几件」如实计数。
 */
class RemoteAttachmentsSpec extends CatsEffectSuite:

  // ---------- 夹具 ----------

  private def tempDir(tag: String): Path =
    val d = Files.createTempDirectory(s"remote-attach-$tag-")
    d.toFile.deleteOnExit()
    d

  private def writeText(dir: Path, name: String, body: String): Path =
    val p = dir.resolve(name)
    Files.writeString(p, body)
    p

  /** 确定性 200 KiB 载荷（多块路径用；内容按 index 变化避免同 sha 假绿）。 */
  private def writeBytes(dir: Path, name: String, size: Int): Path =
    val bytes = Array.tabulate[Byte](size)(i => ((i * 31 + 7) % 251).toByte)
    val p = dir.resolve(name)
    Files.write(p, bytes)
    p

  /** 1×1 透明 PNG（真图片字节 ⇒ 走 Read 的视觉通道）。 */
  private def writePng(dir: Path, name: String): Path =
    val bytes = Base64.getDecoder.decode(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )
    val p = dir.resolve(name)
    Files.write(p, bytes)
    p

  /** 独立实现的 sha256（不复用被测代码，否则等式两边同源 = 没断言）。 */
  private def expectSha(p: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).map("%02x".format(_)).mkString

  private def rm(dirs: Path*): Unit =
    dirs.foreach(d =>
      try os.remove.all(os.Path(d))
      catch case _: Exception => ()
    )

  private def ctx: ToolContext = ToolContext(projectRoot = "")

  private def peerInfo: PeerInfo = PeerInfo("dev-kai-0001", "KAI", "windows", "192.168.1.9")

  private def stagePlan(files: List[Path], dataRoot: Path, id: String): DelegateAttachments.Plan =
    DelegateAttachments
      .stage(files.map(_.toString), id, dataRoot)
      .unsafeRunSync()
      .fold(e => fail(s"staging refused: ${e.message}"), identity)

  private def targetFor(workspace: Path): RemoteAttachments.Target =
    RemoteAttachments.Target(peerInfo, workspace.toString)

  // ---------- 假对端 = 真接收端 ----------

  /**
   * 网线桩：入参转交**真实**接收端（[[FileTransferAction]]），落点 = 本机临时目录里扮演
   * 「设备盘 workspace」的那棵树。可注入故障（块号 → 直接失败/改写入参/先塞脏字节）。
   */
  private final class LoopbackPeer(
    /** 第 N 块**推之前**跑到对端身上的动作（模拟「落点上冒出了别的字节」）。 */
    beforePut: Int => IO[Unit] = _ => IO.unit,
    /** 改写推给接收端的入参（模拟接收端组出来的字节/声明与实际不符）。 */
    patchPut: (Int, JsonObject) => JsonObject = (_, p) => p,
    /** 通道层失败 / 假回执（模拟断流、磁盘满、旧引擎回执）。 */
    failPut: Int => Option[Either[String, Json]] = _ => None,
    /** 探针回执替换（模拟对端离线 / 旧引擎不认 probe）。 */
    probeOverride: Option[Either[String, Json]] = None,
    /** 旧引擎：不认分块键 ⇒ 走 legacy 整件 put（真实老接收端的行为）。 */
    legacyReceiver: Boolean = false
  ) extends RemoteAttachments.Transport:

    /** 观测面：真正推到接收端的块号序列。 */
    val pushedChunks: scala.collection.mutable.ListBuffer[Int] = scala.collection.mutable.ListBuffer.empty

    def probe(t: RemoteAttachments.Target, path: String): IO[Either[String, Json]] =
      probeOverride match
        case Some(o) => IO.pure(o)
        case None =>
          FileTransferAction.handle(JsonObject("direction" -> "probe".asJson, "path" -> path.asJson))

    def putChunk(
      t: RemoteAttachments.Target,
      path: String,
      contentB64: String,
      chunkIndex: Int,
      totalBytes: Long,
      chunkSize: Int,
      chunkSha256: String,
      wholeSha256: String,
      overwrite: Boolean
    ): IO[Either[String, Json]] =
      failPut(chunkIndex) match
        case Some(failure) => IO.pure(failure)
        case None =>
          val params: JsonObject =
            if legacyReceiver then
              // 老接收端：缺分块键 ⇒ legacy 整件 put（把第一块当整件写，回执不带任何摘要）
              JsonObject(
                "direction" -> "put".asJson,
                "path" -> path.asJson,
                "content" -> contentB64.asJson,
                "overwrite" -> overwrite.asJson
              )
            else
              patchPut(
                chunkIndex,
                JsonObject(
                  "direction" -> "put".asJson,
                  "path" -> path.asJson,
                  "content" -> contentB64.asJson,
                  "overwrite" -> overwrite.asJson,
                  "chunkIndex" -> chunkIndex.asJson,
                  "totalBytes" -> totalBytes.asJson,
                  "chunkSize" -> chunkSize.asJson,
                  "chunkSha256" -> chunkSha256.asJson,
                  "wholeSha256" -> wholeSha256.asJson
                )
              )
          beforePut(chunkIndex) *> IO {
            pushedChunks += chunkIndex
          } *> FileTransferAction.handle(params)

  // ============================================================
  // ① 成功路径（多块 + 图片 + 清单区分两侧 + delegate 真能 Read）
  // ============================================================

  test("success · multi-chunk: every file lands on the device workspace with a device-computed equal sha256"):
    val src = tempDir("ok-src")
    val data = tempDir("ok-data")
    val peerWs = tempDir("ok-peer")
    val notes = writeText(src, "notes.md", "# 跨设备\nhello ①\n")
    val blob = writeBytes(src, "blob.bin", 200 * 1024)
    val png = writePng(src, "shot.png")
    val plan = stagePlan(List(notes, blob, png), data, "t-ok")
    val peer = new LoopbackPeer()

    val delivered = RemoteAttachments
      .deliver(plan, targetFor(peerWs), peer, chunkSize = 64 * 1024)
      .unsafeRunSync()
      .fold(e => fail(s"delivery refused: ${e.message}"), identity)

    // 每件：落点 = <设备 workspace>/.nebflow/attachments/<id>/NN-<原名>，字节与原件逐件等值
    delivered.entries.zip(List(notes, blob, png)).foreach { case (e, orig) =>
      val rc = Path.of(e.remoteCopy.getOrElse(fail(s"no device path for ${e.original}")))
      assert(
        rc.startsWith(peerWs.resolve(".nebflow").resolve("attachments").resolve("t-ok")),
        s"device path must be inside the delegate's device seat: $rc"
      )
      assertEquals(expectSha(rc), expectSha(orig), s"device bytes differ from the original: $rc")
      assertEquals(e.sha256, expectSha(orig))
      assertEquals(e.readPath, e.remoteCopy.get, "the manifest's copy path must be the DEVICE path")
      assertEquals(e.original, orig.toString, "the local path stays as provenance")
      assert(Files.exists(orig), "the original must never be moved or deleted")
    }

    // 真的分了块（notes=1 块 · blob 200 KiB / 64 KiB = 4 块 · png=1 块），块号有序
    assertEquals(peer.pushedChunks.toList, List(0, 0, 1, 2, 3, 0), "blob.bin = 4 chunks; md/png = 1 chunk each")

    // 清单区分两侧：copy = 对端路径；本机副本只出现在 host: 溯源行；明示在哪台设备
    val manifest = DelegateAttachments.render(delivered)
    assert(manifest.startsWith(DelegateAttachments.ManifestHeader), manifest.take(120))
    assert(manifest.contains("ON DEVICE KAI"), manifest.take(400))
    delivered.entries.foreach { e =>
      assert(manifest.contains(s"copy: ${e.remoteCopy.get}"), s"manifest must carry the device path:\n$manifest")
      assert(manifest.contains(s"host: ${e.stableCopy}"), s"manifest must keep the local copy as provenance:\n$manifest")
      assert(manifest.contains(e.sha256), manifest)
    }
    assert(!manifest.contains(s"copy: ${delivered.entries.head.stableCopy}"), "a LOCAL path must never pose as the copy")
    assert(manifest.contains("image=yes"), manifest)

    // 终局消费者：delegate 用 Read 打开清单里那条**对端路径**，图片回来的是视觉内容
    val pngEntry = delivered.entries(2)
    val read = ReadTool.call(Json.obj("file_path" -> pngEntry.readPath.asJson).asObject.get, ctx).unsafeRunSync()
    val desc = read.getOrElse(fail(s"the delegate could not read the delivered file: ${read.left.map(_.message)}"))
    assert(desc.startsWith("[image: "), s"Read of the delivered PNG must answer an image descriptor: $desc")
    assert(desc.contains("image/png"), desc)

    // 回执不再说「stable copies in <本机目录>」了事
    val receipt = DelegateAttachments.receiptLine(delivered)
    assert(receipt.contains("delivered to device 'KAI'"), receipt)
    assert(receipt.contains("byte-verified"), receipt)

    rm(src, data, peerWs)

  test("success · 0-byte file: the device ends up holding an empty file (not a missing one)"):
    val src = tempDir("empty-src")
    val data = tempDir("empty-data")
    val peerWs = tempDir("empty-peer")
    val empty = writeText(src, "empty.txt", "")
    val plan = stagePlan(List(empty), data, "t-empty")
    val delivered = RemoteAttachments
      .deliver(plan, targetFor(peerWs), new LoopbackPeer())
      .unsafeRunSync()
      .fold(e => fail(s"delivery refused: ${e.message}"), identity)
    val rc = Path.of(delivered.entries.head.remoteCopy.get)
    assert(Files.isRegularFile(rc), s"an empty attachment must still exist on the device: $rc")
    assertEquals(Files.size(rc), 0L)
    rm(src, data, peerWs)

  // ============================================================
  // ② 六类失败面（全部：整批失败 + 点名 + reason code）
  // ============================================================

  test("① device offline ⇒ the whole dispatch fails, names the device and the file (DELEGATE_ATTACHMENT_REMOTE_OFFLINE)"):
    val src = tempDir("off-src")
    val data = tempDir("off-data")
    val peerWs = tempDir("off-peer")
    val f = writeText(src, "a.txt", "x")
    val plan = stagePlan(List(f), data, "t-off")
    val peer = new LoopbackPeer(
      probeOverride = Some(
        Left("p2p: Cannot reach KAI at http://192.168.1.9:6138: Connection refused; relay: Relay failed: timeout")
      )
    )
    val err = RemoteAttachments.deliver(plan, targetFor(peerWs), peer).unsafeRunSync().swap.getOrElse(
      fail("an unreachable device must FAIL the dispatch")
    )
    assert(err.message.contains(RemoteAttachments.CodeOffline), err.message)
    assert(err.message.contains("'KAI'"), err.message)
    assert(err.message.contains(f.toString), err.message)
    assert(err.message.contains("Nothing was dispatched"), err.message)
    assert(!Files.exists(Path.of(peerWs.resolve("a.txt").toString)), "no bytes may be written when the device is unreachable")
    rm(src, data, peerWs)

  test("② device refuses (real receiver's structured refusal) ⇒ refused code, nothing spawned"):
    val src = tempDir("ref-src")
    val data = tempDir("ref-data")
    val peerWs = tempDir("ref-peer")
    val f = writeBytes(src, "b.bin", 32 * 1024)
    val plan = stagePlan(List(f), data, "t-ref")
    // 首块推之前，对端落点上冒出 10 字节**本次不认领**的既有字节 ⇒ 真接收端
    // `pathOwnedBy` fail-closed（FILE_EXISTS_REFUSING_APPEND，零写零删）
    val landed = peerWs.resolve(".nebflow").resolve("attachments").resolve("t-ref").resolve("01-b.bin")
    val peer = new LoopbackPeer(
      beforePut = i =>
        if i == 0 then
          IO.blocking {
            Files.createDirectories(landed.getParent)
            Files.write(landed, Array.fill[Byte](10)(1))
            ()
          }
        else IO.unit
    )
    val err = RemoteAttachments.deliver(plan, targetFor(peerWs), peer).unsafeRunSync().swap.getOrElse(
      fail("a receiver-side refusal must FAIL the dispatch")
    )
    assert(err.message.contains(RemoteAttachments.CodeRefused), err.message)
    assert(err.message.contains(AttachContract.Codes.FileExistsRefusingAppend), err.message)
    assert(err.message.contains("Nothing was dispatched"), err.message)
    // 收端「永不覆盖/删除既有件」：那 10 字节原样还在
    assertEquals(Files.size(landed), 10L, "the receiver must leave foreign bytes untouched")
    rm(src, data, peerWs)

  test("③ transfer interrupted mid-way ⇒ interrupted code and an honest 'already landed' count"):
    val src = tempDir("int-src")
    val data = tempDir("int-data")
    val peerWs = tempDir("int-peer")
    val a = writeBytes(src, "a.bin", 10 * 1024)
    val b = writeBytes(src, "b.bin", 200 * 1024)
    val plan = stagePlan(List(a, b), data, "t-int")
    val peer = new LoopbackPeer(
      failPut = idx => if idx == 1 then Some(Left("relay: Connection reset by peer")) else None
    )
    val err = RemoteAttachments
      .deliver(plan, targetFor(peerWs), peer, chunkSize = 64 * 1024)
      .unsafeRunSync()
      .swap
      .getOrElse(fail("an interrupted transfer must FAIL the dispatch"))
    assert(err.message.contains(RemoteAttachments.CodeInterrupted), err.message)
    assert(err.message.contains("chunk 1"), err.message)
    assert(err.message.contains("1 of 2 file(s) had ALREADY landed"), err.message)
    assert(err.message.contains("Nothing was dispatched"), err.message)
    // 第一件确实已经在对端盘上（诚实计数不撒谎）
    assert(os.list(os.Path(peerWs.resolve(".nebflow").resolve("attachments").resolve("t-int"))).nonEmpty)
    rm(src, data, peerWs)

  test("④ digest mismatch (device recomputes different bytes) ⇒ digest code, never a silent success"):
    val src = tempDir("dig-src")
    val data = tempDir("dig-data")
    val peerWs = tempDir("dig-peer")
    val f = writeBytes(src, "c.bin", 150 * 1024)
    val plan = stagePlan(List(f), data, "t-dig")
    // 接收端组出来的整件与声明不符：把末块**同长度的字节**改一改，并把块摘要改成篡改后
    // 的值（块摘要自洽 ⇒ 走到末块整件比对：真接收端自算的整件 ≠ 声明的原件摘要）
    val peer = new LoopbackPeer(
      patchPut = (idx, p) =>
        if idx == 2 then
          val original = Base64.getDecoder.decode(p("content").flatMap(_.asString).getOrElse(""))
          val corrupted = original.clone()
          corrupted(0) = (corrupted(0) ^ 0xff).toByte
          val b64 = Base64.getEncoder.encodeToString(corrupted)
          val sha = MessageDigest.getInstance("SHA-256").digest(corrupted).map("%02x".format(_)).mkString
          p.add("content", b64.asJson).add("chunkSha256", sha.asJson)
        else p
    )
    val err = RemoteAttachments
      .deliver(plan, targetFor(peerWs), peer, chunkSize = 64 * 1024)
      .unsafeRunSync()
      .swap
      .getOrElse(fail("a whole-file digest mismatch must FAIL the dispatch"))
    assert(err.message.contains(RemoteAttachments.CodeDigest), err.message)
    assert(err.message.contains(AttachContract.Codes.WholeDigestMismatch), err.message)
    assert(err.message.contains("Nothing was dispatched"), err.message)
    rm(src, data, peerWs)

  test("⑤ device runs out of disk ⇒ disk-full code (receiver ENOSPC text is not swallowed)"):
    val src = tempDir("dsk-src")
    val data = tempDir("dsk-data")
    val peerWs = tempDir("dsk-peer")
    val f = writeBytes(src, "d.bin", 80 * 1024)
    val plan = stagePlan(List(f), data, "t-dsk")
    val peer = new LoopbackPeer(
      failPut = _ => Some(Left("java.io.IOException: No space left on device"))
    )
    val err = RemoteAttachments.deliver(plan, targetFor(peerWs), peer).unsafeRunSync().swap.getOrElse(
      fail("a disk-full device must FAIL the dispatch")
    )
    assert(err.message.contains(RemoteAttachments.CodeDiskFull), err.message)
    assert(err.message.contains("insufficient disk space"), err.message)
    assert(err.message.contains("Nothing was dispatched"), err.message)
    rm(src, data, peerWs)

  test("⑥ device engine too old · probe direction unknown ⇒ refused BEFORE any byte is written"):
    val src = tempDir("old-src")
    val data = tempDir("old-data")
    val peerWs = tempDir("old-peer")
    val f = writeBytes(src, "e.bin", 4096)
    val plan = stagePlan(List(f), data, "t-old")
    val peer = new LoopbackPeer(probeOverride = Some(Left("Remote error: Unknown direction: probe")))
    val err = RemoteAttachments.deliver(plan, targetFor(peerWs), peer).unsafeRunSync().swap.getOrElse(
      fail("an engine that cannot probe must FAIL the dispatch")
    )
    assert(err.message.contains(RemoteAttachments.CodeTooOld), err.message)
    assert(err.message.contains("upgrade the engine on that device"), err.message)
    assertEquals(peer.pushedChunks.toList, Nil, "no chunk may be pushed to an engine that cannot probe")
    rm(src, data, peerWs)

  test("⑥ device engine too old · receiver acks without self-computed digests ⇒ too-old code"):
    val src = tempDir("old2-src")
    val data = tempDir("old2-data")
    val peerWs = tempDir("old2-peer")
    val f = writeBytes(src, "f.bin", 32 * 1024)
    val plan = stagePlan(List(f), data, "t-old2")
    // 旧接收端：不认分块键 ⇒ 按 legacy 整件 put 处理，回执只有 {"size": n}（无任何自算摘要）
    val peer = new LoopbackPeer(legacyReceiver = true)
    val err = RemoteAttachments.deliver(plan, targetFor(peerWs), peer).unsafeRunSync().swap.getOrElse(
      fail("an ack without receiver-computed digests must FAIL the dispatch")
    )
    assert(err.message.contains(RemoteAttachments.CodeTooOld), err.message)
    assert(err.message.contains("self-computed chunkSha256") || err.message.contains("predates"), err.message)
    rm(src, data, peerWs)

  test("⑦ bytes this dispatch does not own are already at the landing path ⇒ explicit conflict, never overwrite"):
    val src = tempDir("par-src")
    val data = tempDir("par-data")
    val peerWs = tempDir("par-peer")
    val f = writeBytes(src, "g.bin", 4096)
    val plan = stagePlan(List(f), data, "t-par")
    // 落点上已有 32 字节（不是本次放的）⇒ 探针读到 32 ⇒ 本次不认领，显式失败、零写入
    val landed = peerWs.resolve(".nebflow").resolve("attachments").resolve("t-par").resolve("01-g.bin")
    Files.createDirectories(landed.getParent)
    Files.write(landed, Array.fill[Byte](32)(2))
    val peer = new LoopbackPeer()
    val err = RemoteAttachments.deliver(plan, targetFor(peerWs), peer).unsafeRunSync().swap.getOrElse(
      fail("pre-existing foreign bytes must FAIL the dispatch")
    )
    assert(err.message.contains(RemoteAttachments.CodePartial), err.message)
    assert(err.message.contains("does not own"), err.message)
    assert(err.message.contains("Nothing was dispatched"), err.message)
    assertEquals(Files.size(landed), 32L, "foreign bytes must never be overwritten or deleted")
    assertEquals(peer.pushedChunks.toList, Nil, "zero chunks may be pushed over foreign bytes")
    rm(src, data, peerWs)

  // ============================================================
  // 纯函数面（路径 / 分类 / 读数 / 本机腿零漂移）
  // ============================================================

  test("path planning: the device landing dir mirrors the local seat layout, per-OS separators"):
    val posix = RemoteAttachments
      .Target(peerInfo, "/Users/PC/Stand")
      .dirFor("task-7")
    assertEquals(posix, "/Users/PC/Stand/.nebflow/attachments/task-7")
    val win = RemoteAttachments
      .Target(peerInfo, "C:\\Users\\PC\\Stand")
      .dirFor("task-7")
    assertEquals(win, "C:\\Users\\PC\\Stand\\.nebflow\\attachments\\task-7")
    // 落尾分隔符/首部分隔符都被归一（不许出现 `//` 或 `\/` 混拼）
    assertEquals(
      RemoteAttachments.joinPeerPath("/a/b/", List("x", "/y")),
      "/a/b/x/y"
    )
    // dispatchId 里的分隔符被安全化（不许逃出目录）
    val sanitized = RemoteAttachments.Target(peerInfo, "/w").dirFor("../../etc/passwd")
    assert(sanitized.startsWith("/w/.nebflow/attachments/"), sanitized)
    assert(!sanitized.contains("/etc/passwd"), s"dispatchId must not escape the landing dir: $sanitized")

  test("classify: each device-side failure face maps to its own reason code (nothing flattened)"):
    def code(msg: String): String = RemoteAttachments.classify(msg)._1
    assertEquals(code("p2p: Cannot reach KAI at http://x: Connection refused (no relay client)"), RemoteAttachments.CodeOffline)
    assertEquals(code("Relay failed: timeout"), RemoteAttachments.CodeOffline)
    assertEquals(code("java.io.IOException: No space left on device"), RemoteAttachments.CodeDiskFull)
    assertEquals(code("Remote error: Unknown direction: probe"), RemoteAttachments.CodeTooOld)
    assertEquals(code("relay: Connection reset by peer"), RemoteAttachments.CodeInterrupted)
    assertEquals(
      code(s"""Remote error: {"code":"${AttachContract.Codes.TargetDirNotAllowed}","message":"outside roots"}"""),
      RemoteAttachments.CodeRefused
    )
    assertEquals(
      code(s"""{"code":"${AttachContract.Codes.ChunkDigestMismatch}","message":"declared x computed y"}"""),
      RemoteAttachments.CodeDigest
    )
    assertEquals(
      code(s"""{"code":"${AttachContract.Codes.InsufficientDisk}","message":"not enough room"}"""),
      RemoteAttachments.CodeDiskFull
    )
    assertEquals(
      code(s"""{"code":"${AttachContract.Codes.UnsupportedProtocol}","message":"too old"}"""),
      RemoteAttachments.CodeTooOld
    )

  test("readProbe: only a receiver-computed reading counts (missing fields ⇒ None ⇒ too-old)"):
    assertEquals(
      RemoteAttachments.readProbe(Json.obj("bytesReceived" -> 12.asJson, "prefixSha256" -> "ab".asJson)),
      Some((12L, "ab"))
    )
    assertEquals(RemoteAttachments.readProbe(Json.obj("bytesReceived" -> 12.asJson)), None)
    assertEquals(RemoteAttachments.readProbe(Json.obj("prefixSha256" -> "ab".asJson)), None)
    assertEquals(RemoteAttachments.readProbe(Json.obj("size" -> 12.asJson)), None)

  test("local leg is byte-identical: a plan with no device carries no device/host lines and points at the local copy"):
    val src = tempDir("loc-src")
    val data = tempDir("loc-data")
    val f = writeText(src, "local.txt", "本机腿\n")
    val plan = stagePlan(List(f), data, "t-loc")
    val section = DelegateAttachments.render(plan)
    assert(!section.contains("device:"), section)
    assert(!section.contains("host:"), section)
    assert(!section.contains("ON DEVICE"), section)
    assertEquals(plan.entries.head.readPath, plan.entries.head.stableCopy)
    assertEquals(plan.entries.head.remoteCopy, None)
    assertEquals(plan.device, None)
    assert(section.contains(s"copy: ${plan.entries.head.stableCopy}"), section)
    // 回执同样是旧文案（没有 device 那句）
    assert(!DelegateAttachments.receiptLine(plan).contains("delivered to device"), DelegateAttachments.receiptLine(plan))
    rm(src, data)

  test("source pin: the old blanket refusal is gone — no DELEGATE_ATTACHMENTS_REMOTE on the dispatch path"):
    val src = os.read(
      os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "DelegateTool.scala"
    )
    assert(
      !src.contains("DELEGATE_ATTACHMENTS_REMOTE"),
      "the device leg must no longer answer with the blanket DELEGATE_ATTACHMENTS_REMOTE refusal"
    )
    assert(!src.contains("cannot be delivered to a device"), "the old refusal wording must be gone")
    // 新路径确实接在派发点上
    assert(src.contains("RemoteAttachments.deliver"), "the device legs must deliver through RemoteAttachments")
    val remote = os.read(
      os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "delegate" / "RemoteAttachments.scala"
    )
    assert(remote.contains("AttachContract.ChunkSize"), "chunk size must come from the single point")
    assert(!remote.contains("4 * 1024 * 1024"), "no second chunk-size constant may be written here")

end RemoteAttachmentsSpec
