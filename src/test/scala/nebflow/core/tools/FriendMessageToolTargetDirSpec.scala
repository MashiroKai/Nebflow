package nebflow.core.tools

import cats.effect.IO
import cats.effect.Ref
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.dropbox.DropboxService
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.neblink.{NeblinkService, PeerInfo}
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * 钉 A（spec §⑦ 钉 A）—— 设备腿 `targetDir` 工具面闸位翻转：**修正前实红 / 修正后实绿**。
 *
 * 契约来源 = `.nebflow/Spec/20260914_162723_devattach-targetdir-contract-upgrade__chain-n-d623bb5b.md`
 * （§⑥① 解除显式拒绝 / §⑦ 钉 A / §4.1 禁静默回显 / §4.2 候选 1「未确认等级 ⇒ 不发」）。
 *
 * 本文件在修正前后**同 harness 可执行**（不引用任何本批新增符号），故可两侧留读数：
 *   - A1 工具边界：device + `targetDir` 不再命中冻结契约拒绝文案；
 *   - A2 设备腿：对端等级未确认（level-1 桩）⇒ 请求**不上 wire**，落缺省目录，且结果**显式回显**
 *     「对端不支持指定目录，已落对端 Downloads」（禁静默降级，spec §4.1）。
 */
class FriendMessageToolTargetDirSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def obj(fields: (String, Json)*): JsonObject = JsonObject.fromIterable(fields)

  private def callTool(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    FriendMessageTool.call(input, ctx)

  /** 修正前该拒绝文案的指纹（spec §⑥① 现读锚 `FriendMessageTool.scala:417-422`）。 */
  private val frozenRefusalFingerprint = "not supported for device targets"

  // ===== A1：工具边界（零服务；纯前置分支） =====

  test("A1 工具边界：device + targetDir 不再命中冻结契约拒绝文案"):
    val res = callTool(
      obj(
        "to" -> Json.fromString("device:KAI"),
        "message" -> Json.fromString("x"),
        "targetDir" -> Json.fromString("/tmp/nb-targetdir-pin")
      ),
      ToolContext(projectRoot = "/tmp")
    ).unsafeRunSync()
    val msg = res.fold(_.message, identity)
    assert(
      !msg.contains(frozenRefusalFingerprint),
      s"device 腿 targetDir 的显式拒绝必须已解除，实际文案：$msg"
    )
    assert(
      msg.contains("Device messaging is unavailable"),
      s"解除拒绝后必须**真的进入设备腿**（服务缺席时是设备腿自己的显式报错），实际文案：$msg"
    )

  // ===== A2：设备腿（真服务栈 + level-1 桩对端） =====

  private def mkResources(
    tmp: os.Path,
    system: ActorSystem,
    ms: NeblinkService,
    svc: DropboxService
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = null,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted,
      neblinkService = Some(ms),
      dropboxService = Some(svc)
    )

  test("A2 设备腿：对端等级未确认 ⇒ 请求不上 wire + 显式回显（禁静默降级）"):
    Dispatcher.parallel[IO].use { dispatcher =>
      val system = ActorSystem(s"td-echo-${java.util.UUID.randomUUID().toString.take(6)}")
      for
        _ <- IO(system)
        prev <- IO(PathUtil.dataRoot)
        tmp <- IO.blocking(os.temp.dir(prefix = "nb-targetdir-echo-"))
        _ <- IO(PathUtil.setDataRoot(tmp))
        ms <- NeblinkService.create(0, dispatcher)
        // 投递桩：sendText / file-offer 都「已送达」，从而真的走到附件腿；
        // 但**没有任何对端回带 file-response.proto** ⇒ 等级 = 未知（§1.4 Q1 侧）。
        _ <- ms.setSendDataFn((_, _, _) => IO.pure(true))
        _ <- ms.upsertPeer(
          PeerInfo(
            deviceId = "lvl1-stub",
            deviceName = "Level1Stub",
            platform = "macos",
            address = "http://127.0.0.1:9"
          )
        )
        svc <- DropboxService.createForTest(ms, new WsHub, 400.millis, 400.millis, 500.millis)
        res <- mkResources(tmp, system, ms, svc)
        src <- IO.blocking(os.temp.dir(prefix = "nb-targetdir-src-"))
        a <- IO.blocking { val p = src / "a.bin"; os.write.over(p, Array.fill(3)('x'.toByte)); p }
        out <- callTool(
          obj(
            "to" -> Json.fromString("device:lvl1-stub"),
            "message" -> Json.fromString("hi"),
            "attachments" -> Json.arr(Json.fromString(a.toString)),
            "targetDir" -> Json.fromString("/tmp/nb-targetdir-pin")
          ),
          ToolContext(projectRoot = src.toString, sharedResources = Some(res))
        )
        msg = out.fold(_.message, identity)
        _ <- IO {
          assert(!msg.contains(frozenRefusalFingerprint), s"冻结契约拒绝必须已解除，实际：$msg")
          assert(
            msg.contains("对端不支持指定目录，已落对端 Downloads"),
            s"§4.1 禁静默：对端等级未确认时必须显式回显，实际：$msg"
          )
        }
        _ <- IO(PathUtil.setDataRoot(prev))
        _ <- IO(os.remove.all(tmp))
        _ <- IO(os.remove.all(src))
      yield ()
      end for
    }

  // ===== A3：模型可见文案钉（r2 返工项，判词 `fail` §F） =====

  /**
   * 复核位判词 `fail`（`n-f1e658c7` §F）：**模型可见契约**仍逐字写着 device 目标
   * 「Rejected for device targets」（prose `:81` + JSON schema `:105`），与已落地的
   * **受控支持**行为直接矛盾 ⇒ 新能力对主消费面不可达。本钉防该失效断言回归
   * —— 🔴 两处必须**同时**改（只改一处即红）。
   */
  private val staleDeviceRefusalClaims = List(
    "Rejected for device targets",
    "not supported for device targets"
  )

  /**
   * 🔴 口径锚（spec §4.1 禁静默 / §4.2 候选 1 / §⑥① 受控支持）：发送端只发**请求**、
   * 落点由**接收端**判定；对端未确认支持 ⇒ 请求不上 wire、落对端 Downloads。
   */
  private val requiredDeviceClaims = List("the receiver decides", "Downloads")

  test("A3 文案钉：description 与 targetDir schema 不再含失效 device 拒绝断言，且含接收端裁定口径"):
    val schemaDirDesc = FriendMessageTool
      .inputSchema("properties")
      .flatMap(_.asObject)
      .flatMap(_.apply("targetDir"))
      .flatMap(_.hcursor.get[String]("description").toOption)
      .getOrElse(fail("inputSchema.properties.targetDir.description 缺失"))
    val surfaces = List(
      "FriendMessageTool.description" -> FriendMessageTool.description,
      "inputSchema.properties.targetDir.description" -> schemaDirDesc
    )
    for
      (label, text) <- surfaces
      stale <- staleDeviceRefusalClaims
    do
      assert(
        !text.contains(stale),
        s"$label 仍含已失效的 device 拒绝断言「$stale」—— 必须改述为受控支持：$text"
      )
    for
      (label, text) <- surfaces
      claim <- requiredDeviceClaims
    do
      assert(
        text.contains(claim),
        s"$label 缺接收端裁定口径锚「$claim」（spec §4.1 禁静默 / §⑥① 受控支持）：$text"
      )
    assert(
      FriendMessageTool.description.contains("off the wire"),
      "prose description 必须写明「对端未确认支持 ⇒ 请求不上 wire + 落对端 Downloads」（§4.2 候选 1）"
    )
end FriendMessageToolTargetDirSpec
