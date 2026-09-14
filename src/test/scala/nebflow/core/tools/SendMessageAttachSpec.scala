package nebflow.core.tools

import cats.effect.IO
import cats.effect.std.Dispatcher
import io.circe.Json
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.agent.AgentCore
import nebflow.agent.AgentDef
import nebflow.core.PathUtil
import nebflow.dropbox.{AttachContract, DropboxService}
import nebflow.gateway.WsHub
import nebflow.neblink.{NeblinkService, PeerInfo}

import scala.concurrent.duration.*

/**
 * SendMessage 附件腿 + TransferFile 退役批（#145，2026-09-14）的判据面。
 *
 * 覆盖（对应任务书验证面，全部**通道内可判定**；真实设备往返 = 未端到端验证，
 * peers 名册为空，禁写「可用」）：
 *   - `to` 三分类语法（device:/friend:/local/裸名）；
 *   - 设备解析负控：未知设备列可用名册、歧义列逐条命中依据（**禁静默首命中**）；
 *   - 非设备前置拒：`targetDir`+device（冻结契约面，归作者）、friend+attachments
 *     （跨项目 4b 缺口）、NebLink/Dropbox 服务缺席（禁静默）；
 *   - 服务级闸位复用：>9 件 / 非法路径 ⇒ fail-fast 回显实际值（offer 之前，零网络）；
 *   - 对端不可达 ⇒ 显式失败（零静默本地执行、零半投递）；
 *   - `local` 显式分支（R3=3b）：复制/覆盖闸/必填 targetDir；
 *   - 退役读数：注册表无 TransferFile、迁移指引、件数常量、量纲（十进制）。
 */
class SendMessageAttachSpec extends CatsEffectSuite:

  // ===== to 语法（纯） =====

  test("to grammar: device:/friend:/local/bare 分派正确"):
    assertEquals(FriendMessageTool.parseToKind("device:KAI"), Right(FriendMessageTool.ToKind.Device("KAI")))
    assertEquals(FriendMessageTool.parseToKind("DEVICE:kai"), Right(FriendMessageTool.ToKind.Device("kai")))
    assertEquals(FriendMessageTool.parseToKind("friend:alice"), Right(FriendMessageTool.ToKind.Friend("alice")))
    assertEquals(FriendMessageTool.parseToKind("alice"), Right(FriendMessageTool.ToKind.Friend("alice")))
    assertEquals(FriendMessageTool.parseToKind("local"), Right(FriendMessageTool.ToKind.Local))
    assertEquals(FriendMessageTool.parseToKind("Local"), Right(FriendMessageTool.ToKind.Local))
    assert(FriendMessageTool.parseToKind("device:").isLeft, "device: 空参必须显式报错")
    assert(FriendMessageTool.parseToKind("friend:").isLeft, "friend: 空参必须显式报错")
    assert(FriendMessageTool.parseToKind("").isLeft)

  // ===== 设备解析（纯，peers 显式传入——原 TransferFileToolSpec 同款口径） =====

  private def peer(id: String, name: String): PeerInfo =
    PeerInfo(deviceId = id, deviceName = name, platform = "macos", address = "http://127.0.0.1:9")

  test("device resolve: 未知设备 ⇒ 列可用名册（禁静默回落）"):
    val peers = List(peer("d1", "MacBook"), peer("d2", "KAI"))
    val err = FriendMessageTool.resolveDevice("nope", peers).left.toOption.get
    assert(err.message.contains("not found among 2 peer(s)"), err.message)
    assert(err.message.contains("MacBook") && err.message.contains("KAI"), err.message)

  test("device resolve: 名册为空 ⇒ 显式无对端（不假装发送成功）"):
    val err = FriendMessageTool.resolveDevice("KAI", Nil).left.toOption.get
    assert(err.message.contains("No peer devices discovered"), err.message)

  test("device resolve: 歧义 ⇒ 列逐条命中依据（禁静默首命中）"):
    val peers = List(peer("dev-1", "KAI-Desk"), peer("dev-2", "KAI-Lap"))
    val err = FriendMessageTool.resolveDevice("dev", peers).left.toOption.get
    assert(err.message.contains("is ambiguous (2 matches)"), err.message)
    assert(err.message.contains("deviceId prefix 'dev'"), err.message)
    assert(err.message.contains("Use the exact deviceId"), err.message)

  test("device resolve: 唯一命中五档（精确/前缀/包含）"):
    assertEquals(FriendMessageTool.resolveDevice("d2", List(peer("d1", "MacBook"), peer("d2", "KAI"))).map(_.deviceId), Right("d2"))
    assertEquals(FriendMessageTool.resolveDevice("kai", List(peer("d1", "MacBook"), peer("d2", "KAI"))).map(_.deviceId), Right("d2"))

  // ===== 工具级负控（无 sharedResources ⇒ 服务缺席显式报错；前置拒在零网络处） =====

  private def callTool(input: JsonObject): IO[Either[ToolError, String]] =
    FriendMessageTool.call(input, ToolContext(projectRoot = "/tmp"))

  private def obj(fields: (String, Json)*): JsonObject = JsonObject.fromIterable(fields)

  test("device 目标 + 服务缺席 ⇒ 显式报错（禁静默成功/本地执行）"):
    val res = callTool(obj("to" -> Json.fromString("device:KAI"), "message" -> Json.fromString("hi"))).unsafeRunSync()
    assert(res.isLeft)
    assert(res.left.toOption.get.message.contains("Device messaging is unavailable"), res.left.toOption.get.message)

  test("device 目标 + 相对附件串 ⇒ 原始串闸 fail-fast（服务访问之前，零网络）"):
    val res = callTool(
      obj(
        "to"          -> Json.fromString("device:KAI"),
        "message"     -> Json.fromString("hi"),
        "attachments" -> Json.arr(Json.fromString("relative.bin"))
      )
    ).unsafeRunSync()
    assert(res.isLeft)
    val msg = res.left.toOption.get.message
    assert(msg.contains("must be absolute"), msg)
    assert(msg.contains("relative.bin"), msg)

  test("friend 目标 + attachments ⇒ 显式 4b 缺口拒绝（纯文本通道不变）"):
    val res = callTool(
      obj(
        "to"          -> Json.fromString("alice"),
        "message"     -> Json.fromString("hi"),
        "attachments" -> Json.arr(Json.fromString("/tmp/a.bin"))
      )
    ).unsafeRunSync()
    assert(res.isLeft)
    val msg = res.left.toOption.get.message
    assert(msg.contains("not supported for friend targets"), msg)
    assert(msg.contains("4b"), msg)

  test("device 目标 + targetDir ⇒ 冻结契约面显式拒绝（归作者，不自裁）"):
    val res = callTool(
      obj(
        "to"        -> Json.fromString("device:KAI"),
        "message"   -> Json.fromString("hi"),
        "targetDir" -> Json.fromString("/tmp/whatever")
      )
    ).unsafeRunSync()
    assert(res.isLeft)
    assert(res.left.toOption.get.message.contains("frozen-contract"), res.left.toOption.get.message)

  // ===== 服务级：闸位/校验/不可达（AttachGateServiceSpec 同款 harness） =====

  private def withStack[A](use: (NeblinkService, DropboxService) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        prevRoot <- IO(PathUtil.dataRoot)
        tempDir  <- IO.blocking(os.temp.dir(prefix = "nb-sendmsg-attach-spec-"))
        _        <- IO(PathUtil.setDataRoot(tempDir))
        ms  <- NeblinkService.create(0, dispatcher)
        svc <- DropboxService.createForTest(ms, new WsHub, 400.millis, 400.millis, 500.millis)
        out <- use(ms, svc)
        _   <- IO { PathUtil.setDataRoot(prevRoot); os.remove.all(tempDir) }
      yield out
    }

  private def tmpFile(dir: os.Path, name: String, bytes: Int): os.Path =
    val p = dir / name
    os.write.over(p, Array.fill(bytes)('x'.toByte))
    p

  test("service gate: 10 件 ⇒ ATTACH_TOO_MANY 且回显 actual/limit（offer 之前，零网络）"):
    withStack { (_, svc) =>
      IO.blocking(os.temp.dir(prefix = "nb-sendmsg-attach-files-")).flatMap { dir =>
        val files = (1 to 10).map(i => tmpFile(dir, s"f$i.bin", 1)).toList
        svc.sendLocalFiles("no-such-device", files).map {
          case Left(err) =>
            assertEquals(err.code, AttachContract.Codes.AttachTooMany)
            assertEquals(err.actual, Some(10L))
            assertEquals(err.limit, Some(9L))
          case Right(_) => fail("expected ATTACH_TOO_MANY")
        } *> IO(os.remove.all(dir))
      }
    }

  test("service validation: 不存在的绝对路径/目录 ⇒ INVALID_ARGUMENT 回显实际路径"):
    withStack { (_, svc) =>
      IO.blocking(os.temp.dir(prefix = "nb-sendmsg-attach-dir-")).flatMap { dir =>
        // 注：os.Path 构造会把相对段绝对化，Path 类型 API 下「相对」分支不可达；
        // 相对串的拒绝在工具边界（FriendMessageTool，见下方工具级负控）。
        val ghost = dir / "no-such-file.bin"
        val rel = svc.sendLocalFiles("d", List(ghost)).map {
          case Left(err) =>
            assertEquals(err.code, AttachContract.Codes.InvalidArgument)
            assert(err.message.contains("does not exist"), err.message)
            assert(err.message.contains(ghost.toString), err.message)
          case Right(_) => fail("expected INVALID_ARGUMENT (nonexistent)")
        }
        val dirPath = svc.sendLocalFiles("d", List(dir)).map {
          case Left(err) =>
            assertEquals(err.code, AttachContract.Codes.InvalidArgument)
            assert(err.message.contains("directory, not a file"), err.message)
          case Right(_) => fail("expected INVALID_ARGUMENT (directory)")
        }
        rel *> dirPath *> IO(os.remove.all(dir))
      }
    }

  test("service: 未知设备 + 合法文件 ⇒ PEER_UNREACHABLE（零 offer、零本地写入）"):
    withStack { (_, svc) =>
      IO.blocking(os.temp.dir(prefix = "nb-sendmsg-attach-unk-")).flatMap { dir =>
        val f = tmpFile(dir, "a.bin", 3)
        svc.sendLocalFiles("ghost-device", List(f)).map {
          case Left(err) =>
            assertEquals(err.code, AttachContract.Codes.PeerUnreachable)
            assert(err.message.contains("nothing was offered or written locally"), err.message)
          case Right(_) => fail("expected PEER_UNREACHABLE")
        } *> IO(os.remove.all(dir))
      }
    }

  test("service: 名册内有但对端不可达 ⇒ 附件显式 failed（禁静默成功）；sendText 返回投递真值 false"):
    withStack { (ms, svc) =>
      IO.blocking(os.temp.dir(prefix = "nb-sendmsg-attach-dead-")).flatMap { dir =>
        val f = tmpFile(dir, "a.bin", 5)
        for
          _   <- ms.upsertPeer(peer("dead-1", "DeadPeer"))
          res <- svc.sendLocalFiles("dead-1", List(f), acceptWait = 2.seconds, uploadWait = 5.seconds)
          txt <- svc.sendText("dead-1", "hello")
        yield
          res match
            case Right(outcomes) =>
              assertEquals(outcomes.size, 1)
              assert(!outcomes.head.delivered, "unreachable peer must NOT report delivered")
              assert(outcomes.head.error.exists(_.contains("could not be delivered")), outcomes.head.error)
            case Left(err) => fail(s"expected per-file outcome, got ${err.render}")
          assert(!txt, "sendText must report delivery truth (false on dead peer)")
          os.remove.all(dir)
      }
    }

  // ===== local 显式分支（R3=3b） =====

  test("local: 复制进 targetDir（零网络；message 缺席也成立）"):
    IO.blocking {
      val src = os.temp.dir(prefix = "nb-sendmsg-local-src-")
      val dst = src / "out"
      (src, dst)
    }.flatMap { case (src, dst) =>
      val a = tmpFile(src, "a.txt", 3)
      val b = tmpFile(src, "b.bin", 7)
      callTool(
        obj(
          "to"          -> Json.fromString("local"),
          "attachments" -> Json.arr(Json.fromString(a.toString), Json.fromString(b.toString)),
          "targetDir"   -> Json.fromString(dst.toString)
        )
      ).unsafeRunSync() match
        case Right(msg) =>
          assert(msg.contains("已复制 2 件"), msg)
          assert(os.exists(dst / "a.txt") && os.exists(dst / "b.bin"))
          assertEquals(os.size(dst / "b.bin"), 7L)
        case Left(err) => fail(s"expected copy success, got ${err.message}")
      IO(os.remove.all(src))
    }

  test("local: 目标已存在且未 overwrite ⇒ 显式拒绝并回显路径；overwrite=true ⇒ 替换"):
    IO.blocking {
      val src = os.temp.dir(prefix = "nb-sendmsg-local-ow-src-")
      val dst = src / "out"
      os.makeDir.all(dst)
      (src, dst)
    }.flatMap { case (src, dst) =>
      val a = tmpFile(src, "a.txt", 3)
      os.write.over(dst / "a.txt", Array.fill(9)('y'.toByte))
      val refused = callTool(
        obj(
          "to"          -> Json.fromString("local"),
          "attachments" -> Json.arr(Json.fromString(a.toString)),
          "targetDir"   -> Json.fromString(dst.toString)
        )
      ).unsafeRunSync()
      assert(refused.isLeft)
      assert(refused.left.toOption.get.message.contains("Target already exists"), refused.left.toOption.get.message)
      assertEquals(os.size(dst / "a.txt"), 9L, "refused copy must not touch the existing target")

      val replaced = callTool(
        obj(
          "to"          -> Json.fromString("local"),
          "attachments" -> Json.arr(Json.fromString(a.toString)),
          "targetDir"   -> Json.fromString(dst.toString),
          "overwrite"   -> Json.fromBoolean(true)
        )
      ).unsafeRunSync()
      assert(replaced.isRight, replaced.left.toOption.get.message)
      assertEquals(os.size(dst / "a.txt"), 3L, "overwrite=true must replace")
      IO(os.remove.all(src))
    }

  test("local: 缺 targetDir / 缺 attachments ⇒ 显式必填报错"):
    val noDir = callTool(obj("to" -> Json.fromString("local"), "attachments" -> Json.arr(Json.fromString("/tmp/x")))).unsafeRunSync()
    assert(noDir.isLeft && noDir.left.toOption.get.message.contains("requires `targetDir`"))
    val noFiles = callTool(obj("to" -> Json.fromString("local"), "targetDir" -> Json.fromString("/tmp/x"))).unsafeRunSync()
    assert(noFiles.isLeft && noFiles.left.toOption.get.message.contains("requires `attachments`"))

  // ===== 退役读数 =====

  test("registry: TransferFile 已摘除；SendMessage 在册（工具面 −1 行为读数）"):
    assert(!ToolRegistry.TOOL_MAP.contains("TransferFile"), "TransferFile must be unregistered")
    assert(ToolRegistry.TOOL_MAP.contains("SendMessage"))
    assert(!ToolRegistry.ALL_TOOLS.exists(_.name == "TransferFile"))

  test("migration guide: RetiredToolGuides 含 TransferFile 且指向 SendMessage"):
    val guide = AgentCore.RetiredToolGuides.get("TransferFile")
    assert(guide.isDefined, "TransferFile migration guide missing")
    assert(guide.get.contains("SendMessage"), guide.get)
    assert(guide.get.contains("device:"), guide.get)

  test("orchestration set: Nebula 面不再含 TransferFile（−1 后 15 件）"):
    val fixed = AgentCore.fixedToolsFor(AgentDef(name = "Nebula", description = "", tools = Nil))
    assert(!fixed.contains("TransferFile"))
    assert(fixed.contains("SendMessage"))
    assertEquals(fixed.size, AgentCore.NebulaOrchestrationToolsExpectedSize)

  test("dimension guard: 100 MB 十进制 / ≤9 件 / 标签含 100,000,000（量纲写死）"):
    assertEquals(AttachContract.MaxFileBytes, 100_000_000L)
    assert(AttachContract.MaxFileBytes != (100 * 1024 * 1024).toLong, "must be decimal 100 MB, not 100 MiB")
    assertEquals(AttachContract.MaxAttachmentsPerMessage, 9)
    assert(AttachContract.MaxFileBytesLabel.contains("100,000,000"), AttachContract.MaxFileBytesLabel)

  test("schema surface: attachments/targetDir/overwrite 参数在面；to+message 必填"):
    val schema = FriendMessageTool.inputSchema
    val props  = schema("properties").flatMap(_.asObject).getOrElse(JsonObject.empty)
    assert(props.contains("attachments"))
    assert(props.contains("targetDir"))
    assert(props.contains("overwrite"))
    val required = schema("required").flatMap(_.asArray).getOrElse(Vector.empty).map(_.asString.getOrElse(""))
    assert(required.contains("to"))
    assert(required.contains("message"))

end SendMessageAttachSpec
