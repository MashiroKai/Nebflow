package nebflow.core.secrets

import io.circe.parser.parse
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.CredentialFileAcl
import nebflow.core.secrets.AskUserSecretSink.StoreRequest

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * secret 型 AskUser 落库 Sink 的验收面（askuser-secret 批，2026-10-08 · 规格 §6）。
 *
 * 全部用例跑在一次性 dataRoot 上（`secretsDir` 由 dataRoot 派生），**绝不**触碰
 * 真实 `~/.nebflow/secrets/`。钉住的判据（对照规格 §2/§5）：
 *
 *   - 验收口径「桌面问 key → secrets 出 0600 文件」：`.askuser/` 内出现 mode=0600
 *     的落盘文件（POSIX 读回）；
 *   - F1 写序的 fail-closed doubles（同 `SecretsStoreSpec` 形态）：注入「收窄失败」
 *     的 restrict ⇒ 文件零残留、返回 Failure（值绝不留在宽文件里）；
 *   - 命名：显式 hint 合法即用；非法/缺省 ⇒ 派生 `askuser-<rid>-<idx>` /
 *     `otp-<rid>-<idx>`（过 `SecretsStore.NamePattern` 同一契约）；
 *   - 大小上限复用 64KiB（`SecretsStore.MaxValueBytes`，禁第二份常量）；
 *   - ephemeral：`.otp/` 落盘 + 消费一次成功、第二次 `UnknownSecret`（原子
 *     rename-out —— 防两次 Bash 同时读到，规格 §5.6）；TTL 过期 ⇒ 消费面给同一
 *     「不可得」终态（过期明文绝不回读）+ `sweepExpired` 清文件与台账；
 *   - 受管命名空间：`askuser-`/`otp-` 前缀单一判据（规格 §4.4），且受管项落在
 *     secrets 的**子目录**里 ⇒ `SecretsStore.list` / `SecretsCatalog.rows`（顶层
 *     枚举）结构性地不列受管项（「面板不列受管项」）。
 */
class AskUserSecretSinkSpec extends FunSuite:

  private val MockValue = "test-askuser-secret-value-1"

  private def newRoot(): os.Path =
    val root = os.temp.dir()
    root

  private def modeOf(p: Path): String =
    PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

  private def store(root: os.Path, req: StoreRequest): Either[SecretsStore.Failure, AskUserSecretSink.Receipt] =
    AskUserSecretSink.storeSync(root, req)

  // ── 长期 secret：0600 落盘 + 命名 ─────────────────────────────────────

  test("S1 验收口径：桌面问 key → secrets/.askuser 内出现 0600 文件，回执只含指针字段") {
    val root = newRoot()
    val r = store(root, StoreRequest("asknb-abc123", 0, MockValue)).toOption.get
    val file = AskUserSecretSink.secretsDir(root) / ".askuser" / r.keyRef
    assertEquals(Files.exists(file.toNIO), true, "secret 文件必须存在于 secrets 命名空间内")
    assertEquals(modeOf(file.toNIO), "rw-------", "落盘即 0600（F1 ①：mode 来自创建属性）")
    // 回执 = 纯指针（规格 §2 Agent 回执词表），零值字段。
    assertEquals(r.keyRef, "askuser-asknb-abc123-0")
    assertEquals(r.location, s"${root}/secrets/.askuser/${r.keyRef}")
    assertEquals(r.variableName, SecretsCatalog.deriveVariableName(r.keyRef))
    assertEquals(r.ephemeral, false)
    // 回执/失败面永不回显值 —— 文件在，值只落盘（这里由测试自己读盘核对）。
    assertEquals(Files.readString(file.toNIO, StandardCharsets.UTF_8), MockValue)
  }

  test("S2 命名：合法 hint 即用；非法 hint 回落派生名；缺省派生 askuser-<rid>-<idx>") {
    val root = newRoot()
    val r1 = store(root, StoreRequest("asknb-abc", 2, MockValue, nameHint = Some("my-api-key"))).toOption.get
    assertEquals(r1.keyRef, "my-api-key")
    val r2 = store(root, StoreRequest("asknb-abc", 3, MockValue, nameHint = Some("Bad Name!"))).toOption.get
    assertEquals(r2.keyRef, "askuser-asknb-abc-3", "非法 hint 回落派生名（不炸整卡）")
    val r3 = store(root, StoreRequest(" Rid_9.Z ", 0, MockValue)).toOption.get
    assertEquals(r3.keyRef, "askuser-_rid_9.z_-0", "requestId 片段折 `_`（名字契约内）")
  }

  test("S3 大小上限复用 SecretsStore.MaxValueBytes（64KiB，禁第二份常量）") {
    val root = newRoot()
    val big = "x".repeat(SecretsStore.MaxValueBytes + 1)
    val f1 = store(root, StoreRequest("asknb-big", 0, big))
    assert(f1.left.toOption.get.toString.contains("value exceeds"), "超限 ⇒ Failure.InvalidValue（SecretsStore 词表）")
    assertEquals(Files.exists((AskUserSecretSink.secretsDir(root) / ".askuser").toNIO), false, "拒存路径零残留（上限在触碰文件系统前判定）")
  }

  // ── F1 fail-closed doubles（SecretsStoreSpec 同款注入 seam 形态） ─────────

  test("S4a fail-closed double：收窄失败 ⇒ 值零残留 + Failure（绝不报 stored）") {
    val root = newRoot()
    // 注入「收窄必失败」的 restrict（SecretsStoreSpec.failingRestrict 同构）：
    // 创建腿 ④ 步抛出 ⇒ catch 清除本调用创建的文件（ours=true）⇒ Failure + 零残留。
    val failing: Path => Unit = _ => throw new java.io.IOException("injected: narrowing refused")
    val f = AskUserSecretSink.storeSyncWith(root, StoreRequest("asknb-z", 0, MockValue), failing)
    assert(f.left.toOption.get.toString.contains("could not store secret"), "失败走 Failure.SecretMode（类别+路径，无值）")
    val dir = AskUserSecretSink.secretsDir(root) / ".askuser"
    val leftovers =
      if Files.isDirectory(dir.toNIO) then os.list(dir).filter(_.last.startsWith("askuser-")) else Nil
    assertEquals(leftovers.toList, Nil, "本调用创建的文件被清除——明文绝不停留在收窄失败的文件里")
  }

  test("S4b 已存在目标：②先收窄再覆写（pre-existing 文件不残留旧值、终态 0600）") {
    val root = newRoot()
    val dir = AskUserSecretSink.secretsDir(root) / ".askuser"
    Files.createDirectories(dir.toNIO)
    val pre = dir / "askuser-pre-existing-0"
    Files.write(pre.toNIO, "old".getBytes(StandardCharsets.UTF_8))
    CredentialFileAcl.restrict(pre.toNIO)
    val r = store(root, StoreRequest("x", 0, MockValue, nameHint = Some("askuser-pre-existing-0")))
    assertEquals(r.isRight, true, "已存在目标走 ②先收窄再写 腿")
    assertEquals(modeOf(pre.toNIO), "rw-------", "覆写后仍 0600")
    assertEquals(Files.readString(pre.toNIO, StandardCharsets.UTF_8), MockValue)
  }

  // ── ephemeral：一次性 + TTL ──────────────────────────────────────────

  test("S5 验收口径：验证码读一次即失效（原子 rename-out）——第二次消费 UnknownSecret") {
    val root = newRoot()
    val r = store(root, StoreRequest("asknb-otp", 0, MockValue, ephemeral = true, ttlSec = Some(600))).toOption.get
    assertEquals(r.keyRef, "otp-asknb-otp-0")
    assertEquals(r.ephemeral, true)
    val file = AskUserSecretSink.secretsDir(root) / ".otp" / r.keyRef
    assertEquals(modeOf(file.toNIO), "rw-------", "验证码同样 0600 落盘")
    assertEquals(AskUserSecretSink.consumeEphemeral(root, r.keyRef), Right(MockValue))
    // 第一次消费后目录项已消失（rename-out），第二次拿不到 = 已消费。
    assertEquals(
      AskUserSecretSink.consumeEphemeral(root, r.keyRef).swap.toOption.get,
      SecretsStore.Failure.UnknownSecret(r.keyRef)
    )
    assertEquals(Files.exists(file.toNIO), false, "消费后文件不在原位")
    // 消费残壳（.consumed-*）零残留。
    val leftovers = os
      .list(AskUserSecretSink.secretsDir(root) / ".otp")
      .filter(_.last.startsWith(".consumed-"))
    assert(leftovers.isEmpty, "rename-out 的临时壳必须被清掉")
  }

  test("S6 TTL 过期：消费面给同一「不可得」终态（过期明文不回读）；sweepExpired 清文件与台账") {
    val root = newRoot()
    val r = store(root, StoreRequest("asknb-ttl", 0, MockValue, ephemeral = true, ttlSec = Some(600))).toOption.get
    // 把台账到期时间拨到过去（模拟 TTL 流逝；缺条目回退 mtime+缺省 TTL 的惰性臂
    // 语义同源，不另测）。
    val dir = AskUserSecretSink.secretsDir(root) / ".otp"
    AskUserSecretSink.writeDeadlines(
      dir,
      AskUserSecretSink.loadDeadlines(dir).updated(r.keyRef, System.currentTimeMillis() - 1000L)
    )
    assertEquals(
      AskUserSecretSink.consumeEphemeral(root, r.keyRef).swap.toOption.get,
      SecretsStore.Failure.UnknownSecret(r.keyRef),
      "过期 ⇒ 与已消费同一 UnknownSecret 终态"
    )
    assertEquals(Files.exists((dir / r.keyRef).toNIO), false, "过期值已销毁（不回读）")
    // sweep：过期条目清文件 + 清台账。
    val r2 = store(root, StoreRequest("asknb-ttl", 1, MockValue, ephemeral = true, ttlSec = Some(600))).toOption.get
    AskUserSecretSink.writeDeadlines(
      dir,
      AskUserSecretSink.loadDeadlines(dir).updated(r2.keyRef, System.currentTimeMillis() - 1000L)
    )
    AskUserSecretSink.sweepExpired(root)
    assertEquals(Files.exists((dir / r2.keyRef).toNIO), false, "清扫删除过期文件")
    assertEquals(AskUserSecretSink.loadDeadlines(dir).contains(r2.keyRef), false, "清扫清台账条目")
  }

  // ── 受管命名空间 + 面板不列受管项 ─────────────────────────────────────

  test("S7 受管命名空间：askuser-/otp- 前缀单一判据；受管项结构性不进 SecretsStore.list / 目录册") {
    val root = newRoot()
    assertEquals(AskUserSecretSink.managedName("askuser-a-0"), true)
    assertEquals(AskUserSecretSink.managedName("otp-a-0"), true)
    assertEquals(AskUserSecretSink.managedName("social-x"), false, "既有命名空间判据不变")
    assertEquals(AskUserSecretSink.managedName("my-key"), false)
    store(root, StoreRequest("asknb-abc", 0, MockValue)).toOption.get
    store(root, StoreRequest("asknb-abc", 1, MockValue, ephemeral = true)).toOption.get
    val store1 = SecretsStore(AskUserSecretSink.secretsDir(root), dataRoot = root)
    val listed = store1.list().unsafeRunSync().toOption.get.map(_.name)
    assertEquals(listed, Nil, "顶层枚举不见受管项（子目录结构性隔离 ⇒ 面板不列受管项）")
    assertEquals(
      SecretsCatalog.rows(AskUserSecretSink.secretsDir(root), root).map(_.name),
      Nil,
      "目录册同样不列受管项"
    )
  }

  // ── Bash 消费面（一次性语义落地） ────────────────────────────────────

  test("S8 consumeOtpReferences：无 otp 引用 ⇒ 命令逐字节透传（零开销面）") {
    val root = newRoot()
    val cmd = """curl -s -H "Authorization: Bearer $(cat ~/.nebflow/secrets/my-key)" https://x"""
    val Right((rewritten, env)) = AskUserSecretSink.consumeOtpReferences(cmd, root): @unchecked
    assertEquals(rewritten, cmd)
    assertEquals(env, Map.empty)
  }

  test("S9 consumeOtpReferences：cat 形态消费一次——值折进当条命令 env，命令文本零值") {
    val root = newRoot()
    val r = store(root, StoreRequest("asknb-otp", 0, MockValue, ephemeral = true)).toOption.get
    val loc = r.location
    val cmd = s"""curl -H "Authorization: Bearer $$(cat $loc)" https://x"""
    val Right((rewritten, env)) = AskUserSecretSink.consumeOtpReferences(cmd, root): @unchecked
    assert(!rewritten.contains(MockValue), "改写后的命令文本零值")
    assert(!rewritten.contains("secrets/.otp/"), "otp 路径引用已折成变量")
    assert(env.values.toSeq.contains(MockValue), "值经环境变量送达本条命令")
    // 消费即焚：第二条同引用命令被拒。
    val Left(refusal) = AskUserSecretSink.consumeOtpReferences(cmd, root): @unchecked
    assert(refusal.contains(r.keyRef), "拒绝文案点名 keyRef")
    assert(!refusal.contains(MockValue), "拒绝文案零值")
  }

  test("S10 consumeOtpReferences：已消费/过期 ⇒ 整条命令显式拒绝（不部分消费）") {
    val root = newRoot()
    val r1 = store(root, StoreRequest("asknb-otp", 0, "v1", ephemeral = true)).toOption.get
    val r2 = store(root, StoreRequest("asknb-otp", 1, "v2", ephemeral = true)).toOption.get
    val cmd = s"echo $$(cat ${r1.location}) && echo $$(cat ${r2.location})"
    // 先单独消费掉 r1 —— 双引用命令随后整体拒绝（不部分消费）。
    AskUserSecretSink.consumeEphemeral(root, r1.keyRef)
    val Left(refusal) = AskUserSecretSink.consumeOtpReferences(cmd, root): @unchecked
    assert(refusal.contains(r1.keyRef))
    // r2 未被部分消费：单独消费仍成功。
    assertEquals(AskUserSecretSink.consumeEphemeral(root, r2.keyRef), Right("v2"))
  }
end AskUserSecretSinkSpec
