package nebflow.social

import munit.FunSuite
import nebflow.core.CredentialFileAcl
import nebflow.social.SocialChannels.Failure

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer

/**
 * F1 防回归（2026-09-19，判词位 `socpanel-verify` 回流）。
 *
 * 缺陷形态（改前 `SocialChannels.writeSecret`）：`Files.write` 先以默认 umask 模式把
 * 明文落到**最终路径**，随后才 `CredentialFileAcl.restrict` 收窄；`restrict` 抛异常时
 * 函数返回 `403 secret_mode`，但**明文文件以默认模式留在磁盘上**（无删除、无回滚）。
 *
 * 本 spec 钉的不变量（= 改法自身）：**明文永不落在未收窄的文件里**。
 *   · 新目标：创建属性即 `rw-------`（模式不来自「事后再收窄」）⇒ 窗口不存在；
 *   · 已存在目标：**先收窄、后写明文**（R3 用「第一次收窄调用时盘上仍是旧内容 + 仍是
 *     宽模式」证明这个次序）；
 *   · 失败零残留：本次创建/写入的文件被删除；只做「写前收窄」期间失败的目标保持原样
 *     （不可能掺进我们的明文）。
 *
 * 手法：`save` 的收窄步骤可注入（🔴 生产恒为共享模块 `CredentialFileAcl`，本文件没有
 * 第二套 ACL 逻辑）。旧行为（先写后收窄、失败不清理）在 R1 必红：
 * 收窄抛异常后目标文件会带着默认模式留在盘上。
 *
 * 🔴 变异安全：只读写本 spec 自己的临时 home，不触碰真实 `~/.nebflow`。
 */
class SocialChannelsSpec extends FunSuite:

  private def body(plain: String): io.circe.Json =
    io.circe.parser.parse(s"""{"enabled":true,"fields":{"bot_token":"$plain"}}""").toOption.get

  private def tmpRoot(): os.Path = os.temp.dir(prefix = "nb-social-cred-")

  private def target(root: os.Path): Path =
    (root / "secrets" / "social-telegram-bot-token").toNIO

  private def modeOf(p: Path): String =
    PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

  private def contentOf(p: Path): String = Files.readString(p)

  private def namesUnderSecrets(root: os.Path): List[String] =
    os.list(root / "secrets").map(_.last).toList

  /** 注入式收窄：必然失败 —— 缺陷复现位（改前该失败会留下默认模式明文件）。 */
  private val failingRestrict: Path => Unit =
    _ => throw new java.io.IOException("injected: narrowing refused")

  private val realRestrict: Path => Unit = p => CredentialFileAcl.restrict(p)

  /** 两条断言都只在 POSIX 语义下有义（Windows 无 POSIX 模式位）。 */
  private def posixOnly(): Unit =
    assume(
      !CredentialFileAcl.isWindows(CredentialFileAcl.currentOsName),
      "POSIX-only readback assertion"
    )

  test("F1-R1 新目标 + 收窄失败 ⇒ 目标零残留 + 既有错误码 + detail 含路径") {
    val root = tmpRoot()
    val plain = "123456:AAF-injected-plaintext"
    SocialChannels.save(root, "telegram", body(plain), failingRestrict) match
      case Left(Failure.SecretMode(field, detail)) =>
        assertEquals(field, "bot_token")
        assert(
          detail.contains(target(root).toString),
          s"修法要求错误 detail 含路径，实得：$detail"
        )
      case other => fail(s"期望 Left(Failure.SecretMode)（⇒ 403 secret_mode），实得 $other")
    assert(!Files.exists(target(root)), "收窄失败后目标文件不得存在（零明文残留）")
    assertEquals(namesUnderSecrets(root), List.empty[String], "secrets/ 不得留下任何残件")
  }

  test("F1-R2 新目标成功路径：创建即 0600（收窄换成 no-op 仍 rw-------）+ 内容写对") {
    posixOnly()
    val root = tmpRoot()
    val plain = "tok-abc-42"
    // no-op 收窄：模式只可能来自「创建属性」——若实现改回「先写后收窄」，这里必红。
    val res = SocialChannels.save(root, "telegram", body(plain), _ => ())
    assert(res.isRight, s"正常写入必须成功，实得 $res")
    assertEquals(
      modeOf(target(root)),
      CredentialFileAcl.PosixMode,
      "明文文件从第一个字节起就必须是 rw-------（窗口不存在的机械判据）"
    )
    assertEquals(contentOf(target(root)), plain)
  }

  test("F1-R3 已存在宽模式凭据 ⇒ 收窄在明文落盘之前（首调用时仍是旧内容 + 宽模式）") {
    posixOnly()
    val root = tmpRoot()
    val oldPlain = "old-token-111"
    val newPlain = "new-token-222"
    Files.createDirectories(target(root).getParent)
    Files.write(target(root), oldPlain.getBytes("UTF-8"))
    Files.setPosixFilePermissions(target(root), PosixFilePermissions.fromString("rw-r--r--"))
    val seen = ArrayBuffer.empty[String]
    val recordingRestrict: Path => Unit = p =>
      seen += s"${modeOf(p)}|${contentOf(p)}"
      CredentialFileAcl.restrict(p)
    val res = SocialChannels.save(root, "telegram", body(newPlain), recordingRestrict)
    assert(res.isRight, s"实得 $res")
    assertEquals(
      seen.toList,
      List(s"rw-r--r--|$oldPlain", s"rw-------|$newPlain"),
      "次序判据：第一次收窄必须发生在明文落盘之前（盘上仍是旧内容、模式尚未收窄），" +
        "第二次收窄是写后的权威步骤"
    )
    assertEquals(contentOf(target(root)), newPlain)
    assertEquals(modeOf(target(root)), CredentialFileAcl.PosixMode)
  }

  test("F1-R4 已存在凭据 + 收窄失败（写前）⇒ 旧凭据保持 0600，新明文不落盘") {
    posixOnly()
    val root = tmpRoot()
    val oldPlain = "keep-me-111"
    val newPlain = "must-not-land-222"
    assert(
      SocialChannels.save(root, "telegram", body(oldPlain), realRestrict).isRight,
      "前置：一次正常写入必须成功"
    )
    val res = SocialChannels.save(root, "telegram", body(newPlain), failingRestrict)
    assert(res.isLeft, s"收窄失败必须走错误通道，实得 $res")
    assertEquals(contentOf(target(root)), oldPlain, "新明文不得落盘")
    assertEquals(modeOf(target(root)), CredentialFileAcl.PosixMode, "旧凭据不得被降级")
    assertEquals(namesUnderSecrets(root), List("social-telegram-bot-token"), "零残件")
  }
end SocialChannelsSpec
