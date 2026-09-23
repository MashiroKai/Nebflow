package nebflow.neblink

import munit.FunSuite
import nebflow.dropbox.{AttachContract, DropboxUtil, FileTransfer}

/**
 * relay 腿落点 —— **纯函数 pin**（dropnam 批 · 判据⑤a）。零 IO、零端口、零进程。
 *
 * 缺陷（真漏 B）：relay 兜底腿直写对端 `~/Downloads/<裸名>`、零改名
 * （`DropboxChunkTransports` 的 `s"~/Downloads/${target.fileName}"`）⇒ 撞对端同名件时
 * 接收端按 offset **追加**到既有件上，整件摘要不符时再 `os.remove.all` **删掉对端原件**。
 *
 * 本 spec 钉住修法**唯一算名点**的输出形态：
 *   - 新接收端（对端等级 ≥ `ProtoRelayTemp`）⇒ 落点 = `<落点目录>/.<名>.dropbox-<tid8>`
 *     （与接收端 `derivedReceiverTempPath` **同一名字生成器**）；🔴 **不得**等于 `~/Downloads/<裸名>`；
 *   - 旧接收端（对端等级 < `ProtoRelayTemp`，含缺省）⇒ 逐字节保留今天的裸名形态 ——
 *     这是**旧收端兼容回退**（旧端没有 `Absent ⇒ 派生 temp` 的 commit 入口，改写真 temp 名
 *     会让字节留在隐藏 temp 而永不现身），**不是**「可关闭本次修复」的开关：门只判**对端**等级，
 *     新接收端上的每条路径都走新形态（无例外）。
 *
 * 口径（禁读成端到端）：本 spec 只 pin **纯函数取值**；命名 / 落盘 / commit / 通报全链的
 * 进程内验证在 `SavedPathLandedReadbackSpec`（格⑥）、接收端安全网在 `FileTransferChunkSpec`。
 * relay 隧道本身的端到端（真 relay 客户端 + 真隧道）**未覆盖**，见结果里的未覆盖面声明。
 */
class RelayLandingPathSpec extends FunSuite:

  private val Tid = "tid1234567890"
  private val Tid8 = "tid12345"

  /** 新接收端（对端自报等级 = 3）；未显式给 level 的格子默认走这一格。 */
  private val NewPeer = Some(AttachContract.ProtoRelayTemp)

  private def transfer(
    fileName: String,
    transferId: String = Tid,
    targetDir: Option[String] = None,
    targetDirCode: Option[String] = None,
    peerProto: Option[Int] = NewPeer
  ): FileTransfer =
    FileTransfer(
      transferId = transferId,
      direction = "out",
      peerDeviceId = "peer-device",
      peerAddress = "",
      fileName = fileName,
      fileSize = 4L,
      mimeType = "application/octet-stream",
      msgId = "m-1",
      status = "accepted",
      targetDir = targetDir,
      targetDirCode = targetDirCode,
      peerProto = peerProto
    )

  test("⑤a 新接收端缺省落点：== ~/Downloads/.<名>.dropbox-<tid8>，且 != ~/Downloads/<裸名>") {
    val p = DropboxUtil.relayLandingPath(transfer("x.bin"))
    assertEquals(p, s"~/Downloads/.x.bin.dropbox-$Tid8")
    assertNotEquals(p, "~/Downloads/x.bin", "落点不得是旧形态的裸名（真漏 B 的缺陷面）")
    // 名字 = 接收端派生 temp 的同一生成器（两侧同名 ⇒ 接收端 commit 找得到字节）
    assertEquals(DropboxUtil.receiverTempName("x.bin", Tid), s".x.bin.dropbox-$Tid8")
  }

  test("⑤a targetDir 已获接收端接受 ⇒ 落点 = 该目录下的 temp 名（旧形态无视 targetDir）") {
    val dir = "/tmp/nb-relay-landing"
    val p = DropboxUtil.relayLandingPath(transfer("报告 v2.pdf", targetDir = Some(dir)))
    assertEquals(p, s"$dir/.报告 v2.pdf.dropbox-$Tid8")
    assertNotEquals(p, s"~/Downloads/报告 v2.pdf")
  }

  test("⑤a targetDir 被拒（targetDirCode 非空）⇒ 不得用于落点（回缺省 temp 名）") {
    val p = DropboxUtil.relayLandingPath(
      transfer("x.bin", targetDir = Some("/tmp/rejected"), targetDirCode = Some("TARGET_DIR_NOT_ALLOWED"))
    )
    assertEquals(p, s"~/Downloads/.x.bin.dropbox-$Tid8")
  }

  test("⑤a 非绝对 targetDir 不得进落点（形态拒的请求不应生效）") {
    val p = DropboxUtil.relayLandingPath(transfer("x.bin", targetDir = Some("relative/x")))
    assertEquals(p, s"~/Downloads/.x.bin.dropbox-$Tid8")
  }

  test("⑤a 每个 transfer 的 temp 名互异（同秒同名件不再折叠到同一落点）") {
    val a = DropboxUtil.relayLandingPath(transfer("same.bin", transferId = "aaaaaaaa-1"))
    val b = DropboxUtil.relayLandingPath(transfer("same.bin", transferId = "bbbbbbbb-2"))
    assertNotEquals(a, b)
    assertEquals(a, "~/Downloads/.same.bin.dropbox-aaaaaaaa")
    assertEquals(b, "~/Downloads/.same.bin.dropbox-bbbbbbbb")
  }

  test("① 门控只做**旧收端**回退：level None / 2 / 1 一律今天的裸名形态，且不出现 .dropbox- 名") {
    // None = 对端等级不可知（旧端不报 proto）⇒ 按旧行为，fail-safe 方向 = 不改对端可见形态
    for level <- List(None, Some(AttachContract.ProtoAssignDir), Some(AttachContract.ProtoChunked)) do
      val t = transfer("legacy.bin", peerProto = level)
      val p = DropboxUtil.relayLandingPath(t)
      assertEquals(p, "~/Downloads/legacy.bin", s"peerProto=$level 必须逐字节同今天")
      assert(!p.contains(".dropbox-"), s"peerProto=$level 不得出现新 temp 名")
      assert(!DropboxUtil.peerSupportsRelayTemp(t), s"peerProto=$level 不得判为支持落点收口")
  }

  test("① 门控不弱化：新接收端（= ProtoRelayTemp 及以上）恒走新形态，无「关掉修复」的分支") {
    for level <- List(AttachContract.ProtoRelayTemp, AttachContract.ProtoRelayTemp + 1, 9) do
      val t = transfer("x.bin", peerProto = Some(level))
      assertEquals(DropboxUtil.relayLandingPath(t), s"~/Downloads/.x.bin.dropbox-$Tid8", s"peerProto=$level")
      assert(DropboxUtil.peerSupportsRelayTemp(t))
  }

end RelayLandingPathSpec
