package nebflow.neblink

import nebflow.neblink.social.ProfileSyncMetaStore

import nebflow.neblink.social.ProfileDescriptionStore

import nebflow.neblink.social.ProfileDescriptions

import nebflow.neblink.social.ProfileSync

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

/**
 * ProfileSync spec（profile-sync 批 2026-11-06，作者令「描述信息需要支持云同步」）。
 *
 * 钉死六件（验收逐条对应）：
 *
 *  P1 **载荷**：内容键 ∪ 墓碑键（清空要能传播）、往返编解码、畸形条目 fail-closed；
 *  P2 **验签**：配对 key 的 HMAC + ts ±90s 窗——篡改/过期/未配对一律拒（零写入）；
 *  P3 **LWW**：旧戳丢弃 / 新戳应用 / 首见即收 / 未知键与超长值 rejected；
 *  P4 **回声抑制靠结构**：收帧应用后**外推函数一次都不被调用**（不回推）；
 *  P5 **版本兼容**：旧版 `profile-descriptions.json`（无元数据段）+ 缺 meta 文件
 *     照旧解码可用；远端带戳帧可覆盖「无戳的旧内容」；
 *  P6 **无 NebLink ⇒ local-only**：状态出口四类字段全 `local-only`（不假装 synced）。
 *
 * 隔离：每用例独占临时 dataRoot（`ProfileDescriptionStoreSpec` / `AgentIdentitySyncSpec`
 * 同形），并由 `ProfileSync.resetRuntimeState()` 清空单例缝，绝不串味。
 */
class ProfileSyncSpec extends CatsEffectSuite:

  private var tmpDir: java.nio.file.Path = null
  private var savedRoot: os.Path = scala.compiletime.uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    savedRoot = PathUtil.dataRoot
    tmpDir = java.nio.file.Files.createTempDirectory("profile-sync-spec")
    PathUtil.setDataRoot(os.Path(tmpDir, os.pwd))
    ProfileSync.resetRuntimeState()

  override def afterEach(context: AfterEach): Unit =
    ProfileSync.resetRuntimeState()
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(os.Path(tmpDir, os.pwd))
    super.afterEach(context)

  // ── 夹具 ───────────────────────────────────────────────────────────────────

  /** 配对表（收端视角：`sender` 条目 = 验签读的那一份）。 */
  private def writePairing(entries: (String, String)*): Unit =
    val dir = PathUtil.dataRoot / "neblink"
    os.makeDir.all(dir)
    val json = Json.arr(
      entries.map { case (id, key) =>
        Json.obj(
          "deviceId" -> id.asJson,
          "deviceName" -> id.asJson,
          "sharedKey" -> key.asJson,
          "allowSpawn" -> true.asJson,
          "createdAt" -> 0L.asJson
        )
      }*
    )
    os.write(dir / "devices.json", json.noSpaces)

  end writePairing

  private def entry(key: String, value: String, ts: Long, origin: String = "peer"): ProfileSync.Entry =
    ProfileSync.Entry(key, value, ts, origin)

  /** 已签名的上机帧（`peer` 的名字在配对表里 ⇒ 收端验签读同一把 key）。 */
  private def signedFrame(
    entries: List[ProfileSync.Entry],
    from: String = "peer",
    nowMs: Long = System.currentTimeMillis(),
    nonce: String = scala.util.Random.alphanumeric.take(12).mkString
  ): Json =
    val unsigned = ProfileSync.ProfilePayload(entries, from, nowMs / 1000L, nonce, "")
    ProfileSync.encodePayload(ProfileSync.signedFor(unsigned, from))

  private def detached: IO[Ref[IO, ProfileDescriptions]] =
    Ref.of[IO, ProfileDescriptions](ProfileDescriptions.empty)

  // ── P1 载荷 ────────────────────────────────────────────────────────────────

  test("P1 entriesOf = 内容键 ∪ 墓碑键；无任何键 ⇒ buildPayload None"):
    val d = ProfileDescriptions(
      friendBio = Map("u1" -> "我是老林"),
      groupRemark = Map("g1" -> "球局群"),
      groupDescription = Map("g1" -> "每周六晚八点"),
      selfBio = "我在写 Nebflow"
    )
    // g2 只有元数据（价值为空）：墓碑——清空必须能传播到对端
    val meta = Map(
      "friendBio:u1" -> ProfileSync.SyncMeta(100L, "mac"),
      "groupRemark:g1" -> ProfileSync.SyncMeta(200L, "mac"),
      "groupRemark:g2" -> ProfileSync.SyncMeta(300L, "mac")
    )
    val entries = ProfileSync.entriesOf(d, meta)
    assertEquals(
      entries.map(_.key).sorted,
      List("friendBio:u1", "groupDescription:g1", "groupRemark:g1", "groupRemark:g2", "selfBio")
    )
    assertEquals(entries.find(_.key == "groupRemark:g2").map(_.value), Some(""))
    assertEquals(entries.find(_.key == "groupRemark:g1").map(_.updatedAt), Some(200L))
    assertEquals(
      ProfileSync.buildPayload(ProfileDescriptions.empty, Map.empty, "self", 0L),
      None,
      "零描述零戳 ⇒ 无可外推（不造空帧）"
    )

  test("P1 encode/parse 往返；kind 不对 / 条目畸形 ⇒ None（fail-closed）"):
    val p = ProfileSync.ProfilePayload(
      entries = List(entry("selfBio", "我在写 Nebflow", 42L), entry("friendBio:u1", "老林", 43L)),
      fromDeviceId = "peer",
      tsSec = 1_800_000_000L,
      nonce = "n1",
      sig = "sig-1"
    )
    assertEquals(
      ProfileSync.parsePayload(ProfileSync.encodePayload(p)),
      Some(p.copy(entries = p.entries.sortBy(_.key))),
      "编码按 key 排序（确定性签名串）⇒ 解析回来的条目序同序"
    )
    val wrongKind = ProfileSync.encodePayload(p).deepMerge(Json.obj("kind" -> "other".asJson))
    assertEquals(ProfileSync.parsePayload(wrongKind), None)
    val missingField =
      ProfileSync.encodePayload(p).deepMerge(Json.obj("entries" -> Json.arr(Json.obj("key" -> "selfBio".asJson))))
    assertEquals(ProfileSync.parsePayload(missingField), None, "条目缺 value/updatedAt ⇒ 整帧拒收")

  // ── P2 验签 ────────────────────────────────────────────────────────────────

  test("P2 验签：配对发送方通过；篡改/过期/未配对拒收"):
    writePairing("peer" -> "shared-key-1", "self" -> "shared-key-self")
    val nowMs = System.currentTimeMillis()
    val good = ProfileSync.parsePayload(signedFrame(List(entry("selfBio", "我在写 Nebflow", 10L)), "peer", nowMs)).get
    assertEquals(ProfileSync.verifyPayload(good, nowMs), None)
    // 篡改内容（改 value 不改 sig）⇒ 拒
    val tampered = good.copy(entries = List(entry("selfBio", "伪介绍", 10L)))
    assert(ProfileSync.verifyPayload(tampered, nowMs).isDefined)
    // 过期 ts（±90s 窗外）⇒ 拒
    assert(ProfileSync.verifyPayload(good.copy(tsSec = nowMs / 1000L - 3600L), nowMs).isDefined)
    // 未配对发送方 ⇒ 拒
    assert(ProfileSync.verifyPayload(good.copy(fromDeviceId = "unpaired"), nowMs).isDefined)

  // ── P3 LWW 裁定（纯函数）──────────────────────────────────────────────────

  test("P3 decide：旧戳丢弃 / 等戳丢弃 / 新戳应用 / 首见即收 / 未知键与超长值 rejected"):
    val meta = Map("selfBio" -> ProfileSync.SyncMeta(1_000L, "mac"))
    val content = Map("selfBio" -> "旧介绍", "friendBio:u1" -> "无戳旧内容")
    val outcomes = ProfileSync.decide(
      content,
      meta,
      List(
        entry("selfBio", "更旧", 999L), // 旧戳
        entry("selfBio", "同戳", 1_000L), // 等戳（不新于）
        entry("selfBio", "更新", 1_001L), // 新戳
        entry("friendBio:u1", "远端新值", 5L), // 本机无戳但远端有戳 ⇒ 应用
        entry("friendBio:u2", "首见", 0L), // 首见即收
        entry("groupRemark:g1", "未知但合法", 7L), // 首见
        entry("bogus", "x", 9L), // 未知键形状
        entry("selfBio", "x" * (ProfileSync.MaxValueChars + 1), 9_999L) // 超长
      )
    )
    assertEquals(
      outcomes.map(o => o.key -> o.result),
      List(
        "selfBio" -> "stale",
        "selfBio" -> "stale",
        "selfBio" -> "applied",
        "friendBio:u1" -> "applied",
        "friendBio:u2" -> "applied",
        "groupRemark:g1" -> "applied",
        "bogus" -> "rejected",
        "selfBio" -> "rejected"
      )
    )

  test("P3 applyEntries：空值 = 清除（删键而非存空串），selfBio 同理"):
    val d = ProfileDescriptions(
      friendBio = Map("u1" -> "老林"),
      groupRemark = Map("g1" -> "球局群"),
      selfBio = "我的介绍"
    )
    val next = ProfileSync.applyEntries(
      d,
      List(entry("friendBio:u1", "", 1L), entry("groupRemark:g1", "", 2L), entry("selfBio", "", 3L))
    )
    assertEquals(next, ProfileDescriptions.empty)

  // ── P4 / P3 收帧落地（应用 + 回声不回推）──────────────────────────────────

  test("P4 收帧应用：Ref + 磁盘 + 元数据三处同源；外推函数零调用（回声不回推）"):
    writePairing("peer" -> "k1", "self" -> "k-self")
    val sent = scala.collection.mutable.ListBuffer.empty[(String, Json)]
    for
      ref <- detached
      _ <- IO.delay {
        ProfileSync.attachDescriptions(ref)
        ProfileSync.sendFn = (dev, json) => IO { sent += ((dev, json)); true }
        ProfileSync.selfIdFn = IO.pure("self")
      }
      outcomes <- ProfileSync.applyVerified(
        ProfileSync.parsePayload(signedFrame(List(entry("groupRemark:g1", "球局群", 500L)))).get
      )
      snap <- ref.get
      disk <- ProfileDescriptionStore.load
      meta <- ProfileSyncMetaStore.all()
    yield
      assertEquals(outcomes.map(_.result), List("applied"))
      assertEquals(snap.groupRemark, Map("g1" -> "球局群"))
      assertEquals(disk, snap, "远端收敛必须落盘（重启后不丢）")
      assertEquals(meta.get("groupRemark:g1"), Some(ProfileSync.SyncMeta(500L, "peer")))
      assertEquals(sent.toList, Nil, "回声抑制靠结构：收帧路径绝不外推")

    end for

  test("P3 旧戳帧：本地逐字不动（stale，零写入、零推送）"):
    writePairing("peer" -> "k1", "self" -> "k-self")
    val sent = scala.collection.mutable.ListBuffer.empty[(String, Json)]
    for
      ref <- Ref.of[IO, ProfileDescriptions](ProfileDescriptions(selfBio = "我本地的介绍"))
      _ <- IO.delay {
        ProfileSync.attachDescriptions(ref)
        ProfileSync.sendFn = (dev, json) => IO { sent += ((dev, json)); true }
        ProfileSync.selfIdFn = IO.pure("self")
      }
      _ <- ProfileSyncMetaStore.update(_ + ("selfBio" -> ProfileSync.SyncMeta(9_000L, "self")))
      outcomes <- ProfileSync.applyVerified(
        ProfileSync.parsePayload(signedFrame(List(entry("selfBio", "对端的旧介绍", 8_999L)))).get
      )
      snap <- ref.get
      disk <- ProfileDescriptionStore.load
    yield
      assertEquals(outcomes.map(_.result), List("stale"))
      assertEquals(snap.selfBio, "我本地的介绍")
      assertEquals(disk, ProfileDescriptions.empty, "stale 帧零写入（磁盘不该被碰）")
      assertEquals(sent.toList, Nil)

    end for

  test("P2 伪造帧经 handlePayload：拒收且零写入"):
    writePairing("peer" -> "k1", "self" -> "k-self")
    for
      ref <- detached
      _ <- IO.delay(ProfileSync.attachDescriptions(ref))
      forged = ProfileSync
        .encodePayload(
          ProfileSync.ProfilePayload(
            List(entry("selfBio", "伪造", 1L)),
            "peer",
            System.currentTimeMillis() / 1000L,
            "n-forged",
            "not-a-valid-signature"
          )
        )
      _ <- ProfileSync.handlePayload(forged)
      snap <- ref.get
    yield assertEquals(snap, ProfileDescriptions.empty)
    end for

  // ── P5 版本兼容 ────────────────────────────────────────────────────────────

  test("P5 旧版 profile-descriptions.json（无元数据段）+ 缺 meta 文件 ⇒ 照旧可解码，首见帧覆盖"):
    writePairing("peer" -> "k1", "self" -> "k-self")
    val legacy =
      """{"friendBio":{"u1":"老林"},"groupRemark":{"g1":"球局群"},"selfBio":"我的介绍"}"""
    for
      _ <- IO.blocking(os.write.over(PathUtil.dataRoot / "profile-descriptions.json", legacy, createFolders = true))
      loaded <- ProfileDescriptionStore.load
      meta <- ProfileSyncMetaStore.all()
      // 旧内容 + 无戳：带戳的远端帧必须能覆盖（否则升级后旧值成为不可收敛的死值）
      ref <- Ref.of[IO, ProfileDescriptions](loaded)
      _ <- IO.delay(ProfileSync.attachDescriptions(ref))
      outcomes <- ProfileSync.applyVerified(
        ProfileSync.parsePayload(signedFrame(List(entry("friendBio:u1", "新介绍", 1_000L)))).get
      )
      snap <- ref.get
    yield
      assertEquals(loaded.friendBio, Map("u1" -> "老林"), "旧文件逐键折叠解码：其余字段零丢失")
      assertEquals(loaded.selfBio, "我的介绍")
      assertEquals(meta, Map.empty[String, ProfileSync.SyncMeta], "缺 meta 文件 = 空元数据回落（不砸读面）")
      assertEquals(outcomes.map(_.result), List("applied"))
      assertEquals(snap.friendBio, Map("u1" -> "新介绍"))
      assertEquals(snap.groupRemark, Map("g1" -> "球局群"), "未在本帧的键不得被碰")
    end for

  // ── P6 状态出口 ────────────────────────────────────────────────────────────

  test("P6 未装配 NebLink ⇒ 四类字段全 local-only"):
    for
      ref <- Ref.of[IO, ProfileDescriptions](ProfileDescriptions(selfBio = "我的介绍"))
      _ <- IO.delay(ProfileSync.attachDescriptions(ref))
      json <- ProfileSync.status
    yield
      val fields = json.hcursor.downField("fields").focus.get.asObject.get
      assertEquals(fields.keys.toList.sorted, ProfileSync.Kinds.sorted)
      ProfileSync.Kinds.foreach { k =>
        assertEquals(
          json.hcursor.downField("fields").downField(k).downField("state").as[String].toOption,
          Some("local-only"),
          s"$k 未配置 NebLink 时必须报 local-only"
        )
      }
      assertEquals(json.hcursor.downField("neblink").as[Boolean].toOption, Some(false))

  test("P6 已装配 + 外推全部送达 ⇒ synced；送达失败 ⇒ 如实回落 local-only"):
    writePairing("peerA" -> "kA", "self" -> "k-self")
    for
      ref <- Ref.of[IO, ProfileDescriptions](ProfileDescriptions(selfBio = "我的介绍"))
      _ <- IO.delay {
        ProfileSync.attachDescriptions(ref)
        ProfileSync.sendFn = (_, _) => IO.pure(true)
        ProfileSync.selfIdFn = IO.pure("self")
      }
      _ <- ProfileSync.afterLocalWrite(List(ProfileSync.KindSelfBio))
      okJson <- ProfileSync.status
      _ <- IO.delay(ProfileSync.sendFn = (_, _) => IO.pure(false))
      _ <- ProfileSync.afterLocalWrite(List(ProfileSync.KindSelfBio))
      badJson <- ProfileSync.status
      _ <- IO.delay(ProfileSync.resetRuntimeState())
      unwired <- ProfileSync.status
    yield
      val state = (j: Json) => j.hcursor.downField("fields").downField("selfBio").downField("state").as[String].toOption
      assertEquals(okJson.hcursor.downField("peers").as[Int].toOption, Some(1))
      assertEquals(state(okJson), Some("synced"))
      assertEquals(
        okJson.hcursor.downField("fields").downField("selfBio").downField("updatedAt").as[Long].toOption.exists(_ > 0L),
        true,
        "synced 的字段必须带真实的网关时钟戳"
      )
      assertEquals(state(badJson), Some("local-only"), "送达失败绝不说 synced")
      assertEquals(state(unwired), Some("local-only"))
    end for

  // ── 写后外推 ───────────────────────────────────────────────────────────────

  test("写后外推：逐目标签署（每对端一把 key）+ 只推非空状态"):
    writePairing("peerA" -> "keyA", "peerB" -> "keyB", "self" -> "keySelf")
    val sent = scala.collection.mutable.ListBuffer.empty[(String, Json)]
    for
      ref <- Ref.of[IO, ProfileDescriptions](ProfileDescriptions(groupRemark = Map("g1" -> "球局群")))
      _ <- IO.delay {
        ProfileSync.attachDescriptions(ref)
        ProfileSync.sendFn = (dev, json) => IO { sent += ((dev, json)); true }
        ProfileSync.selfIdFn = IO.pure("self")
      }
      _ <- ProfileSync.afterLocalWrite(List(ProfileSync.keyOf(ProfileSync.KindGroupRemark, "g1")))
      meta <- ProfileSyncMetaStore.all()
    yield
      assertEquals(sent.map(_._1).toList.sorted, List("peerA", "peerB"), "self 不在推送目标里")
      sent.foreach { case (target, json) =>
        val p = ProfileSync.parsePayload(json).get
        assertEquals(p.fromDeviceId, "self")
        assertEquals(p.entries.map(_.key), List("groupRemark:g1"))
        val expectedKey = if target == "peerA" then "keyA" else "keyB"
        assertEquals(p.sig, nebflow.core.device.DeviceHmac.sign(expectedKey, ProfileSync.canonicalOf(p)))
      }
      val stamp = meta.get("groupRemark:g1")
      assert(stamp.isDefined && stamp.get.updatedAt > 0L, "本地写必须打网关时钟戳")
      assertEquals(stamp.map(_.originDeviceId), Some("self"))
    end for
end ProfileSyncSpec
