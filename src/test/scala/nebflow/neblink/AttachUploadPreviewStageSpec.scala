package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import munit.FunSuite

/**
 * 预览申报**暂存面 + 发面方言收敛**单测 —— 字段方言双兼容批（2026-09-22）。
 *
 * 为什么需要它：网关的预览申报路由（`POST /api/attachments/preview`）里那**两条**判定 ——
 *   ① 收面**按键集识别**（识别结果即「客户端说的是哪一版」），
 *   ② 发面**方言三层收敛**（客户端另说 > 识别结果 > 默认）
 * —— 是本批「双兼容」真正落地的位置；路由本身要起完整实例才能打，但**这两条规则**是纯函数面，
 * 可在单测里逐条钉住（路由只做形态闸与信封，判定不落在路由里）。
 *
 * 🔴 判据源同一：`AttachmentPreviewContract` 的方言登记表（本 spec 不另立第二套键集）。
 */
class AttachUploadPreviewStageSpec extends FunSuite:

  private def jsonOf(s: String) =
    parse(s).fold(e => fail(s"bad test json: ${e.getMessage}"), identity)

  private val b64Nine = "AQIDBAUGBwgJ"
  private val sha64   = "a" * 64

  private val fiveKey = jsonOf(s"""{"mime":"image/webp","w":4,"h":3,"size":9,"b64":"$b64Nine"}""")
  private val sixKey  = jsonOf(s"""{"fmt":"webp","w":4,"h":3,"bytes":9,"sha256":"$sha64","b64":"$b64Nine"}""")

  private def staged(json: io.circe.Json, declared: Option[String] = None): PreviewStaged =
    val p = AttachmentPreviewContract.parsePreview(json)
    assert(p.isDefined, s"fixture must parse: $json")
    PreviewStaged(p.get, declared)

  // ── ① 收面识别：两版方言都认，识别结果即「客户端说的那一版」 ──────────

  test("identification: the staged preview records the dialect its OWN key set identifies") {
    assertEquals(staged(fiveKey).preview.dialectId, "mime5")
    assertEquals(staged(sixKey).preview.dialectId, "fmt6")
    // 识别点同时给出方言记录（路由的读数面用它）
    assertEquals(AttachmentPreviewContract.parsePreviewWith(fiveKey).map(_._2.id), Some("mime5"))
    assertEquals(AttachmentPreviewContract.parsePreviewWith(sixKey).map(_._2.id), Some("fmt6"))
    // 第三种方言：识别不出（⇒ 路由 400 + 已登记清单；调用方回落无预览）
    val thirdParty = jsonOf(s"""{"format":"image/webp","width":4,"height":3,"length":9,"data":"$b64Nine"}""")
    assertEquals(AttachmentPreviewContract.parsePreviewWith(thirdParty), None)
  }

  // ── ② 发面方言三层收敛（本批的唯一收敛点）──────────────────────────

  test("send dialect converges in three layers: declared > identified > default") {
    // 层 ①：客户端**另说**（已登记）⇒ 用它（覆盖识别结果）
    assertEquals(staged(fiveKey, Some("fmt6")).sendDialectId, "fmt6")
    assertEquals(staged(sixKey, Some("mime5")).sendDialectId, "mime5")
    // 层 ②：客户端没说 ⇒ 用**识别结果**（收-发往返恒等：说什么方言就还什么方言）
    assertEquals(staged(fiveKey).sendDialectId, "mime5")
    assertEquals(staged(sixKey).sendDialectId, "fmt6")
    // 层 ③：客户端另说的 id **未登记** ⇒ 不作数，落到识别结果（禁静默换方言）
    assertEquals(staged(sixKey, Some("never-heard-of-it")).sendDialectId, "fmt6")
    assertEquals(staged(fiveKey, Some("")).sendDialectId, "mime5")
    // 默认方言本身必须在登记表内（政策旋钮不能指到表外）
    assert(AttachmentPreviewContract.previewDialect(AttachmentPreviewContract.DefaultSendDialectId).isDefined)
  }

  // ── ③ 暂存表语义：同键替换 / 消费一次读 / 有界 ──────────────────────

  test("staging registry: same-key replaces, take consumes once, the table is bounded") {
    val reg = AttachUploadRegistry.create.unsafeRunSync()
    val p5  = staged(fiveKey).preview
    val p6  = staged(sixKey).preview
    // 缺席 ⇒ None（E1 无 preview 键）
    assertEquals(reg.takePreview("u-absent").unsafeRunSync(), None)
    // 暂存 → 取出（消费一次读）
    assert(reg.stagePreview("u1", p5).unsafeRunSync())
    assertEquals(reg.takePreview("u1").unsafeRunSync().map(_.preview.mime), Some("image/webp"))
    // **消费一次**：取走即删，第二次取是 None（不存在二次消费/串件）
    assertEquals(reg.takePreview("u1").unsafeRunSync(), None)
    // 同键重复暂存 = **替换**（浏览器重试申报天然幂等）；另说的方言一并更新
    assert(reg.stagePreview("u2", p5).unsafeRunSync())
    assert(reg.stagePreview("u2", p6, Some("fmt6")).unsafeRunSync())
    assertEquals(reg.takePreview("u2").unsafeRunSync().map(s => (s.preview.dialectId, s.declaredDialect)), Some(("fmt6", Some("fmt6"))))
    // 有界：表满**且**键不在表中 ⇒ 拒绝暂存（调用方按「无预览」收场，零阻塞）
    val cap = AttachUploadRegistry.PreviewStageCap
    val reg2 = AttachUploadRegistry.create.unsafeRunSync()
    (0 until cap).foreach(i => assert(reg2.stagePreview(s"k-$i", p5).unsafeRunSync(), s"slot $i"))
    assertEquals(reg2.stagePreview("k-overflow", p5).unsafeRunSync(), false)
    // 表满时**同键**替换仍然放行（不会把已在飞的上传的申报顶掉）
    assert(reg2.stagePreview(s"k-0", p6).unsafeRunSync())
  }

  // ── ④ 暂存面与取消位面互不干扰（同表不同槽）────────────────────────

  test("staging does not disturb the in-flight/cancel bookkeeping it shares the object with") {
    val reg = AttachUploadRegistry.create.unsafeRunSync()
    val p5  = staged(fiveKey).preview
    reg.register("u9").unsafeRunSync()
    assert(reg.stagePreview("u9", p5).unsafeRunSync())
    assertEquals(reg.inFlight.unsafeRunSync(), Set("u9"))
    assert(reg.cancel("u9").unsafeRunSync())
    assert(reg.isCancelled("u9").unsafeRunSync())
    // 取消位与暂存互不影响：暂存仍在（可被 E1 取走），取消位也仍在
    assertEquals(reg.takePreview("u9").unsafeRunSync().map(_.preview.mime), Some("image/webp"))
    assert(reg.isCancelled("u9").unsafeRunSync())
    assertEquals(reg.stagedPreviews.unsafeRunSync(), Vector.empty)
    reg.release("u9").unsafeRunSync()
    assertEquals(reg.inFlight.unsafeRunSync(), Set.empty)
    assert(!reg.isCancelled("u9").unsafeRunSync())
  }

  // ── ⑤ 未知方言在**发面**显式拒绝（不在表外静默取一个）──────────────

  test("the send side cannot be pointed at a dialect that is not registered") {
    val p5 = staged(fiveKey).preview
    assertEquals(AttachmentPreviewContract.encodeInDialect("never-heard-of-it", p5), None)
    assertEquals(AttachmentPreviewContract.encodeInDialect("mime5", p5).isDefined, true)
    // 带摘要的方言拒绝无摘要载荷（不发半截字段）
    assertEquals(AttachmentPreviewContract.encodeInDialect("fmt6", p5), None)
    assertEquals(AttachmentPreviewContract.encodeInDialect("fmt6", staged(sixKey).preview).isDefined, true)
  }
