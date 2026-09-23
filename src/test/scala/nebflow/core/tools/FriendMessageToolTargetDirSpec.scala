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
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.dropbox.DropboxService
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.neblink.{NeblinkService, PeerInfo}

import scala.concurrent.duration.*

/**
 * 🔴 **本件的判据方向已随 mailunify-full 批（2026-09-23 作者裁定）反转**——
 * 原文钉的是「设备腿 `targetDir` **受控支持**」（契约升版批 2026-09-14：解除显式拒绝
 * ／对端未确认支持 ⇒ 请求不上 wire + 落缺省目录 + **显式回显**）。合面后该腿**退役**
 * （`SendMessage(to="device:…")` 的纯传输腿整体消失，设备面统一到 `Mail` 的 `device:`
 * 腿，语义反转为「投进对端会话」）⇒ `targetDir` 的**能力丧失**（作者已裁「接受丧失」）。
 *
 * ⇒ 判据改向（**强度不降**，且不静默）：
 *   - A1 工具边界：单 `to` 面下 `targetDir` **不再是设备腿的参数**——它既不产生拒绝面、
 *     也不产生承诺面（面外键由引擎零 schema 校验静默丢弃）；
 *   - A2 落地面：设备腿**结构上不再发目录请求** ⇒ 单一实现点断言（`None` 实参）
 *     + 运行读数（结果文本不得出现任何「请求目录 / 对端裁定目录」的承诺）；
 *   - A3 不静态面（§16 迁移说明的落面）：**能力丧失 + 语义反转必须逐字写明**在
 *     `Mail` 的模型可见描述里（否则 = 静默丧失，正是作者禁的形态）。
 *
 * 原文参照（已归档）：契约 `.nebflow/Spec/20260914_162723_devattach-targetdir-contract-upgrade__chain-n-d623bb5b.md`。
 */
class FriendMessageToolTargetDirSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def obj(fields: (String, Json)*): JsonObject = JsonObject.fromIterable(fields)

  private def callTool(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    MailTool.call(input, ctx)

  /** 原文冻结的拒绝文案指纹（契约升版批之前的形态）。 */
  private val frozenRefusalFingerprint = "not supported for device targets"

  // ===== A1：工具边界（零服务；纯前置分支） =====

  test("A1 工具边界：单 `to` 面下 `targetDir` 对设备腿**零语义**（既不拒绝也不承诺）"):
    val res = callTool(
      obj(
        "to"        -> Json.fromString("device:KAI"),
        "message"   -> Json.fromString("x"),
        "targetDir" -> Json.fromString("/tmp/nb-targetdir-pin")
      ),
      ToolContext(projectRoot = "/tmp")
    ).unsafeRunSync()
    val msg = res.fold(_.message, identity)
    assert(
      !msg.contains(frozenRefusalFingerprint),
      s"冻结契约拒绝早已解除，实际文案：$msg"
    )
    assert(
      msg.contains("Device messaging is unavailable"),
      s"服务缺席时必须落到设备腿自己的显式报错（= 面外键不改变控制流），实际文案：$msg"
    )

  // ===== A2：落地面（真服务栈 + level-1 桩对端） =====

  private def mkResources(tmp: os.Path, system: ActorSystem, ms: NeblinkService, svc: DropboxService): IO[SharedResources] =
    for
      dispatcher     <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter    <- RateLimiter.create()
      tracker        <- FileChangeTracker.create(os.pwd.toString)
      fileLocks      <- FileLockManager.create
      thinkingRef    <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted     <- Ref.of[IO, Boolean](false)
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

  test("A2 落地面：设备腿**结构上不再发目录请求**（单一实现点 = `None` 实参），结果文本零承诺"):
    // ① 单一实现点（静态判据，与 `SendMessageAskConfirmSpec` ⑥ 同款形态）：
    //    设备文件通道的调用**恒**以 `None` 作 targetDir ⇒ 「目录请求」这一能力
    //    在本腿**结构上**不存在（不是「可能不发」，而是没有产生它的代码路径）。
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "MailTool.scala")
    def occurrences(needle: String): Int = src.sliding(needle.length).count(_ == needle)
    assert(
      src.contains("sendLocalFiles(peer.deviceId, paths, None"),
      "🔴 设备腿必须恒以 None 请求落点（能力丧失的机械判据；出现非 None 即能力回涨）"
    )
    assertEquals(
      occurrences("input(\"targetDir\")"),
      1,
      "🔴 `targetDir` 在 MailTool 内只允许有 `to=\"local\"` 腿一处读取（设备腿不得读它）"
    )
    assert(
      src.contains("is nothing to request") || src.contains("no way to request a directory on a `device:` target"),
      "🔴 能力丧失必须在源码描述面写明（禁静默）"
    )

    // ② 运行读数：真服务栈 + level-1 桩对端 —— 结果文本**零目录承诺**。
    Dispatcher.parallel[IO].use { dispatcher =>
      val system = ActorSystem(s"td-retired-${java.util.UUID.randomUUID().toString.take(6)}")
      for
        _       <- IO(system)
        prev    <- IO(PathUtil.dataRoot)
        tmp     <- IO.blocking(os.temp.dir(prefix = "nb-targetdir-retired-"))
        _       <- IO(PathUtil.setDataRoot(tmp))
        ms      <- NeblinkService.create(0, dispatcher)
        _       <- ms.setSendDataFn((_, _, _) => IO.pure(true))
        _       <- ms.upsertPeer(PeerInfo(deviceId = "lvl1-stub", deviceName = "Level1Stub", platform = "macos", address = "http://127.0.0.1:9"))
        svc     <- DropboxService.createForTest(ms, new WsHub, 400.millis, 400.millis, 500.millis)
        res     <- mkResources(tmp, system, ms, svc)
        src2    <- IO.blocking(os.temp.dir(prefix = "nb-targetdir-retired-src-"))
        a       <- IO.blocking { val p = src2 / "a.bin"; os.write.over(p, Array.fill(3)('x'.toByte)); p }
        out     <- callTool(
                     obj(
                       "to"          -> Json.fromString("device:lvl1-stub"),
                       "message"     -> Json.fromString("hi"),
                       "attachments" -> Json.arr(Json.fromString(a.toString)),
                       "targetDir"   -> Json.fromString("/tmp/nb-targetdir-pin")
                     ),
                     ToolContext(projectRoot = src2.toString, sharedResources = Some(res))
                   )
        msg = out.fold(_.message, identity)
        _ <- IO {
          assert(!msg.contains(frozenRefusalFingerprint), s"冻结契约拒绝必须已解除，实际：$msg")
          // 🔴 能力丧失的两个方向都不得出现：既不得**承诺**目录请求，也不得**回显**请求结果。
          assert(!msg.contains("对端不支持指定目录"), s"退役腿不得再回显目录请求的结果：$msg")
          assert(!msg.contains("targetDir 请求"), s"退役腿不得再出现目录请求字样：$msg")
          assert(
            msg.contains("targetDir") == false,
            s"🔴 结果文本不得提及 targetDir（能力已丧失，提及即误导）：$msg"
          )
        }
        _ <- IO(PathUtil.setDataRoot(prev))
        _ <- IO(os.remove.all(tmp))
        _ <- IO(os.remove.all(src2))
      yield ()
    }

  // ===== A3：不静态面（§16 迁移说明的机械判据） =====

  /** 🔴 作者令（合面 §B-1.3 ① + 件③）：`targetDir` 能力丧失与语义反转**须写明** ——
    * 「不静默」的机械形态 = 两条事实都在 `Mail` 的模型可见描述里逐字可读。 */
  private val requiredLossClaims = List(
    "LOST CAPABILITY",
    "no directory request on this leg",
    "REVERSED SEMANTICS",
    "the peer's agent was NOT aware"
  )

  test("A3 不静态面：能力丧失 + 语义反转逐字在 `Mail` 描述面（三变体同现）"):
    val faces = List(
      "Mail.descriptionBase"       -> MailTool.descriptionBase,
      "Mail.descriptionNebulaRoot" -> MailTool.descriptionNebulaRoot,
      "Mail.descriptionDispatcher" -> MailTool.descriptionDispatcher
    )
    for
      (label, text) <- faces
      claim         <- requiredLossClaims
    do
      assert(
        text.replaceAll("\\s+", " ").contains(claim),
        s"$label 缺迁移说明锚「$claim」（作者令：丧失与反转须写明，禁静默）"
      )

  test("A3 不静态面：`Mail` 唯一 `local` 腿保留目录能力，且面外键不得被描述为设备腿可用"):
    val d = MailTool.description.replaceAll("\\s+", " ")
    assert(
      d.contains("copied into a destination directory on this machine"),
      "`local` 腿的目录落点语义必须在描述面在册（能力只在本地腿保留）"
    )
    assert(
      !d.contains("targetDir (string, optional): destination directory for `local` (created if missing). Device targets"),
      "🔴 旧的「设备腿也可请求 targetDir」描述不得残留在模型可见面"
    )

end FriendMessageToolTargetDirSpec
