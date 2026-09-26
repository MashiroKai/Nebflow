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
 * 🔴 **本件的判据方向经历两次反转，现钉 = 2026-09-26 支上对账重算批形态**：
 *
 *   - 契约升版批（2026-09-14）：钉「设备腿 `targetDir` 受控支持」；
 *   - mailunify-full 批（2026-09-23）：钉「能力丧失」（A1 面外键零语义 / A2 单一
 *     实现点 / A3 描述面作者令逐字锚）；
 *   - **mailmodel 退役令收编（main `6adf76ed5` ruling (e)，2026-09-26 对账调和入支）**：
 *     `to="device:…"` **整腿退役** ⇒ `MAIL_DEVICE_RETIRED` 墓碑**先于一切**
 *     （含服务缺席与任何参数面判读）⇒ 按 verify2 判词（20260926_081155）改钉清单 ②：
 *
 *     - **A1（re-pin）**：面外键 `targetDir` **零语义**的判据保留、形式升级为
 *       「带 / 不带 `targetDir` 两次调用产出**逐字同一**的墓碑文本」（控制流不变性）；
 *     - **A2（re-pin）**：「设备腿结构上不发目录请求」的原机械判据随腿消亡
 *       （`sendLocalFiles(…, None)` 已不在树上，如实登记）；保留仍活的三个面：
 *       `targetDir` 全文件唯一读取点（`local` 腿）、模型可见 schema 面在册退役声明、
 *       真服务栈 + 真附件 + `targetDir` 齐备仍纯墓碑（零承诺零回显）；
 *     - **A3（退役，登记待裁）**：四条作者令文案锚在调和后描述面不再携带 ⇒
 *       是否补 / 如何补**归 §16 作者裁决**（🔴 禁自创文案，本件不代拟；锚原文逐字
 *       引用保留在 A3 注释块供裁决参照）。第二条 A3（`local` 腿目录能力在册 +
 *       旧设备 targetDir 描述不残留）与退役令无冲突，原样保留。
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

  test("A1 工具边界（2026-09-26 re-pin）：`to=\"device:…\"` 一律 MAIL_DEVICE_RETIRED 墓碑先行，面外键 `targetDir` 零语义（带/不带逐字同一）"):
    def callWith(targetDir: Option[String]): Either[ToolError, String] =
      val fields = List(
        "to"      -> Json.fromString("device:KAI"),
        "message" -> Json.fromString("x")
      ) ++ targetDir.map(v => "targetDir" -> Json.fromString(v)).toList
      callTool(obj(fields*), ToolContext(projectRoot = "/tmp")).unsafeRunSync()

    val withKey    = callWith(Some("/tmp/nb-targetdir-pin"))
    val withoutKey = callWith(None)
    List("with targetDir" -> withKey, "without targetDir" -> withoutKey).foreach { (tag, res) =>
      val msg = res.fold(_.message, identity)
      assert(
        !msg.contains(frozenRefusalFingerprint),
        s"[$tag] 冻结契约拒绝早已解除，实际文案：$msg"
      )
      assert(
        msg.contains(MailTool.ErrDeviceLegRetired),
        s"[$tag] 退役令收编后 `device:` 一律落 MAIL_DEVICE_RETIRED 墓碑（先于服务缺席与一切参数面判读），实际文案：$msg"
      )
      assert(
        !msg.contains("targetDir"),
        s"[$tag] 墓碑面不得提及 targetDir（面外键零语义；提及即暗示该键有判读位），实际文案：$msg"
      )
    }
    assertEquals(
      withKey.fold(_.message, identity),
      withoutKey.fold(_.message, identity),
      "面外键 `targetDir` 不得改变控制流：带/不带的墓碑文本必须逐字同一"
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

  test("A2 落地面（2026-09-26 re-pin）：目录请求能力随整腿退役消亡——唯一 targetDir 读取点 = `local` 腿，schema 面在册退役声明，真服务栈 + 真附件仍纯墓碑"):
    // ① 静态判据（保留仍活的面；死者如实登记）：
    //    a. 🔴 已消亡（登记）：原「设备腿恒以 None 请求落点」的单一实现点判据
    //       （`src.contains("sendLocalFiles(peer.deviceId, paths, None")`）随 mailmodel
    //       退役令收编**整腿消亡**——`sendLocalFiles` 在现树 0 命中，断言对象不复存在，
    //       该面由墓碑（服务栈从不被触达）结构性接管；「设备腿描述面措辞」判据同批
    //       消亡（`is nothing to request` / `no way to request a directory…` 均 0 命中）。
    //    b. 保留：`targetDir` 全文件唯一读取点 = `to="local"` 腿——计数若 >1 ⇒
    //       有任何新腿偷读 `targetDir` = 目录请求能力经侧门回涨（本 spec 防的形态）。
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "MailTool.scala")
    def occurrences(needle: String): Int = src.sliding(needle.length).count(_ == needle)
    assertEquals(
      occurrences("input(\"targetDir\")"),
      1,
      "🔴 `targetDir` 全文件唯一读取点 = `to=\"local\"` 腿（第二读取点 = 能力回涨）"
    )
    //    c. 保留（不静默丧失的现行形态）：模型可见 schema 面**在册退役声明**——
    //       `to` 参数描述写明 RETIRED + 墓碑码（2026-09-26 对账调和后的现行文案逐字锚）。
    val toDesc = MailTool
      .inputSchema("properties").flatMap(_.asObject)
      .flatMap(_("to")).flatMap(_.asObject)
      .flatMap(_("description")).flatMap(_.asString)
      .getOrElse(fail("Mail schema 的 `to` 参数缺 description"))
    assert(
      toDesc.contains("RETIRED (2026-09-25)") && toDesc.contains(MailTool.ErrDeviceLegRetired),
      s"🔴 模型可见 schema 面必须在册退役声明（RETIRED + 墓碑码，禁静默），实际：$toDesc"
    )

    // ② 运行读数（墓碑先行）：真服务栈 + level-1 桩对端 + 真附件 + targetDir 齐备
    //    ⇒ 仍得纯墓碑（服务面从不被触达；结果文本零目录承诺、零 targetDir 提及）。
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
          assert(
            msg.contains(MailTool.ErrDeviceLegRetired),
            s"真服务栈 + 真附件在册仍必须纯墓碑（服务面不被触达），实际：$msg"
          )
          assert(msg.contains("nothing was sent"), s"fail-closed：必须声明零副作用，实际：$msg")
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

  // ===== A3：不静态面 =====

  // 🔴 2026-09-26 登记待裁（verify2 判词改钉清单 ②）：原「能力丧失 + 语义反转逐字在
  // `Mail` 描述面（三变体同现）」测试**退役**（此为本件唯一的红点销项 = 退役而非改钉）。
  // 其四条 requiredLossClaims 是 mailunify-full 批**作者令**的文案锚——
  //     "LOST CAPABILITY"
  //     "no directory request on this leg"
  //     "REVERSED SEMANTICS"
  //     "the peer's agent was NOT aware"
  // ——对账调和（main `6adf76ed5` 收编）后的描述面不再携带这四锚；是否补、如何补、
  // 以何种措辞补**归 §16 作者裁决面**（🔴 禁自创文案：本件不代拟、不回插，四锚原文
  // 逐字引用如上仅供裁决参照）。不静默丧失的**现行**机械形态由两条仍活的判据钉死：
  // A2①c（模型可见 schema 面在册退役声明 RETIRED + MAIL_DEVICE_RETIRED）与下一条
  // A3（`local` 腿目录能力在册 + 旧设备 targetDir 描述不残留）。

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
