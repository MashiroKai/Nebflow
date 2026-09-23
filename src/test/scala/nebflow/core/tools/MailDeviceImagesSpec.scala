package nebflow.core.tools

import cats.effect.IO
import cats.effect.Ref
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.FunSuite
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.gateway.SessionStore
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.neblink.{NeblinkService, PeerInfo}

/**
 * MailTool 设备腿 `images`：**禁静默忽略**（同根族第三件，2026-09-16 A1 批）。
 *
 * 缺陷（`devread-forensic` §④ 同根类第二实例）：设备分支（`MailTool.scala:319-328`）
 * 不调 `ImageInject.parseImagesParam`、`deliverToDevice` 无附件参数 ⇒ `images` 被
 * **静默吞掉**（零报错、零投递）。修法 = A1 同族口径：**校验前置于一切投递副作用**
 * （复用非设备腿的同一单一判据点），投递腿复用**既有**设备文件通道
 * （`DropboxService.sendLocalFiles` → 分块 FileTransfer 承载，零新 wire 字段）。
 *
 * 本 spec 钉的是「零静默」这一层（离线可复跑，零网络、零真实投递）：设备腿只要
 * 收了 `images` 就必须给出**可判读的处置**（校验错 / 通道不可用错），绝不静默放行。
 */
class MailDeviceImagesSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-mail-device-images"

  private val dispatcherResource: Dispatcher[IO] =
    Dispatcher.parallel[IO].allocated.unsafeRunSync()._1

  private def resourcesWith(ns: Option[NeblinkService]): SharedResources =
    new SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = tempRoot,
      thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = null,
      taskStore = null,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      neblinkService = ns,
      // dropboxService 取默认 None —— 设备文件通道未接线（本 spec 的判据面之一）
      voiceMutedRef = Ref.unsafe[IO, Boolean](false)
    )

  private def bareCtx: ToolContext = ToolContext(projectRoot = tempRoot.toString)

  /** ctx 带真 NeblinkService + 名册，但**不带** Dropbox 设备文件通道、不带 relay client。 */
  private def rosterCtx(deviceName: String): ToolContext =
    val ns = NeblinkService.create(0, dispatcherResource).unsafeRunSync()
    ns.upsertPeer(PeerInfo("peer-1", deviceName, "macos", "http://127.0.0.1:9")).unsafeRunSync()
    ToolContext(projectRoot = tempRoot.toString, sharedResources = Some(resourcesWith(Some(ns))))

  private def err(ctx: ToolContext, input: JsonObject): String =
    MailTool.call(input, ctx).unsafeRunSync() match
      case Left(err) => err.message
      case Right(msg) => fail(s"expected an explicit error, got success: $msg")

  override def beforeEach(context: BeforeEach): Unit =
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)

  test("设备腿 + 相对路径 images ⇒ 显式拒绝（禁静默忽略；零投递副作用）"):
    val msg = err(
      bareCtx,
      JsonObject(
        "device" -> "KAI-MBP".asJson,
        "message" -> "hi".asJson,
        "images" -> Json.arr("shot.png".asJson)
      )
    )
    assert(msg.contains("must be absolute"), s"相对路径必须显式拒绝并指名形态: $msg")
    assert(msg.contains("shot.png"), s"错误文案必须带回问题路径: $msg")

  test("设备腿 + 超件数 images（6 > MAX_ATTACHMENTS=5）⇒ 显式拒绝（复用既有单一判据点）"):
    val six = (1 to 6).map(i => s"/tmp/img-$i.png".asJson)
    val msg = err(
      bareCtx,
      JsonObject(
        "device" -> "KAI-MBP".asJson,
        "message" -> "hi".asJson,
        "images" -> Json.arr(six*)
      )
    )
    assert(msg.contains("Too many image attachments: 6"), s"超件数必须走 ImageInject 既有词表: $msg")
    assert(msg.contains(s"max ${ImageInject.MAX_ATTACHMENTS}"), s"上限必须来自既有常量: $msg")

  test("设备腿 + images 非空但设备文件通道未接线 ⇒ 显式拒绝（禁半投递、禁静默放行）"):
    val png = tempRoot / "shot.png"
    os.write(png, Array[Byte](1, 2, 3))
    val msg = err(
      rosterCtx("KAI-MBP"),
      JsonObject(
        "device" -> "KAI-MBP".asJson,
        "message" -> "hi".asJson,
        "images" -> Json.arr(png.toString.asJson)
      )
    )
    assert(
      msg.contains("images") && (msg.contains("file channel") || msg.contains("Dropbox")),
      s"附件通道不可用必须显式指名（且先于任何投递）: $msg"
    )
    assert(
      !msg.contains("relay client is not initialized"),
      s"images 的通道闸必须先于投递腿判定（否则即半投递前的静默面）: $msg"
    )

  test("负控：设备腿**不带** images ⇒ 行为不变（既有投递腿判定逐字保留）"):
    val noImages = err(
      rosterCtx("KAI-MBP"),
      JsonObject("device" -> "KAI-MBP".asJson, "message" -> "hi".asJson)
    )
    assert(
      noImages.contains("relay client is not initialized"),
      s"无附件时既有判定不得被附件闸改写: $noImages"
    )

end MailDeviceImagesSpec
