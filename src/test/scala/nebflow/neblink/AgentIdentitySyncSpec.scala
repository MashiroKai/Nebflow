package nebflow.neblink

import cats.effect.IO
import io.circe.syntax.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.core.entity.AgentIdentityStore
import nebflow.shared.PathUtil

import java.util.Base64

/**
 * AgentIdentitySync spec（identity-sync 批 2026-10-23）：跨机收敛通道的写读路径
 * 单测——载荷构建/编码/宽容解析/逐对端验签/LWW 收敛/枚举外推（验收③）。
 */
class AgentIdentitySyncSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-agent-identity-sync"
  PathUtil.setDataRoot(tempRoot)

  private def reset(): IO[Unit] = IO.delay {
    if os.exists(tempRoot) then os.remove.all(tempRoot)
    os.makeDir.all(tempRoot)
  }

  /** 配对表（收发两视角同表：含对端条目；验签读的是「发送方」条目）。 */
  private def writePairing(entries: (String, String)*): Unit =
    val dir = tempRoot / "neblink"
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

  private def mkAgent(name: String, displayName: Option[String], avatar: Option[Array[Byte]]): Unit =
    val dir = tempRoot / "agents" / name
    os.makeDir.all(dir)
    displayName.foreach(dn =>
      os.write(dir / "agent.json", Json.obj("name" -> name.asJson, "displayName" -> dn.asJson).noSpaces)
    )
    avatar.foreach(b => os.write(dir / "avatar", b))

  private val bytes = Array[Byte](1, 2, 3, 4, 5)
  private val sha = AgentIdentityStore.sha256Hex(bytes)

  // ── 载荷：构建 → 编码 → 宽容解析 roundtrip ──────────────────────────────────

  test("buildPayload: identity-bearing agent → total-state payload; bare agent → None"):
    reset() *>
      IO.delay(mkAgent("Nebula", Some("小雨"), Some(bytes))) *>
      IO.delay {
        val rec = AgentIdentityStore.readRec("Nebula")
        val p = AgentIdentitySync.buildPayload(rec, "self", 1_800_000_000_000L)
        assert(p.isDefined)
        assertEquals(p.get.agent, "Nebula")
        assertEquals(p.get.displayName, Some("小雨"))
        assertEquals(p.get.avatarSha256, Some(sha))
        assertEquals(p.get.updatedAt, rec.updatedAt)
        // avatar b64 解码回原字节
        assertEquals(Base64.getDecoder.decode(p.get.avatarB64.get).toSeq, bytes.toSeq)
      } *>
      IO.delay {
        val bare = AgentIdentityStore.readRec("ghost") // 无文件 = 无身份
        assertEquals(AgentIdentitySync.buildPayload(bare, "self", 0L), None)
      }

  test("encode/parse roundtrip preserves every field; wrong kind parses to None"):
    reset() *>
      IO.delay(mkAgent("Nebula", Some("小雨"), Some(bytes))) *>
      IO.delay {
        val rec = AgentIdentityStore.readRec("Nebula")
        // 信封 roundtrip 需非空 sig（parsePayload fail-closed 只管结构；验签走下一测）
        val signed =
          AgentIdentitySync.buildPayload(rec, "self", 1_800_000_000_000L).get.copy(sig = "roundtrip-signature")
        val back = AgentIdentitySync.parsePayload(AgentIdentitySync.encodePayload(signed))
        assertEquals(back, Some(signed))
        // kind 信封不对 ⇒ None（数据通道上有别的流量，静默忽略）
        val wrongKind = AgentIdentitySync.encodePayload(signed).deepMerge(Json.obj("kind" -> "other".asJson))
        assertEquals(AgentIdentitySync.parsePayload(wrongKind), None)
        // 缺签名字段 ⇒ None（fail-closed）
        val noSig = AgentIdentitySync.encodePayload(signed).asObject.get.remove("sig")
        assertEquals(AgentIdentitySync.parsePayload(Json.fromJsonObject(noSig)), None)
      }

  // ── 验签：逐对端 key + ts 窗 + 篡改拒收 ─────────────────────────────────────

  test("signedFor/verifyPayload: paired sender passes; tamper/stale/unpaired rejected"):
    reset() *>
      IO.delay(mkAgent("Nebula", Some("小雨"), None)) *>
      // 配对 = 两侧各有一条共享同一 key 的条目：发方用「本机↔收端」key 签
      // （本机表里的 receiver 条目），收端用「本机↔发端」key 验（收端表里的
      // sender 条目）——同 key 两侧一致。
      IO.delay(writePairing("sender" -> "shared-key-1", "receiver" -> "shared-key-1")) *>
      IO.delay {
        val nowMs = System.currentTimeMillis()
        val unsigned = AgentIdentitySync
          .buildPayload(AgentIdentityStore.readRec("Nebula"), "sender", nowMs)
          .get
        val signed = AgentIdentitySync.signedFor(unsigned, "receiver")
        // 收端视角：sender 在收端配对表内（同 key）⇒ 通过
        assertEquals(AgentIdentitySync.verifyPayload(signed, nowMs), None)
        // 篡改内容（改 displayName 不改 sig）⇒ 拒
        val tampered = signed.copy(displayName = Some("伪名"))
        assert(AgentIdentitySync.verifyPayload(tampered, nowMs).isDefined)
        // 过期 ts（±90s 窗外）⇒ 拒
        val stale = signed.copy(tsSec = nowMs / 1000 - 3600)
        assert(AgentIdentitySync.verifyPayload(stale, nowMs).isDefined)
        // 未配对发送方 ⇒ 拒
        val stranger = unsigned.copy(fromDeviceId = "unpaired")
        val strangerSigned =
          stranger.copy(sig = nebflow.core.device.DeviceHmac.sign("other", AgentIdentitySync.canonicalOf(stranger)))
        assert(AgentIdentitySync.verifyPayload(strangerSigned, nowMs).isDefined)
      }

  // ── 头像字节闸 ──────────────────────────────────────────────────────────────

  test("decodeAvatar: None passes through, oversize rejected, valid decoded"):
    IO.delay {
      val base = AgentIdentitySync.IdentityPayload(
        agent = "Nebula",
        displayName = None,
        avatarB64 = None,
        avatarSha256 = None,
        updatedAt = 1L,
        fromDeviceId = "self",
        tsSec = 0L,
        nonce = "n",
        sig = "s"
      )
      assertEquals(AgentIdentitySync.decodeAvatar(base), Some(None))
      val withBytes = base.copy(avatarB64 = Some(Base64.getEncoder.encodeToString(bytes)), avatarSha256 = Some(sha))
      // Array[Byte] 无引用相等 ⇒ 比对 Seq
      assertEquals(AgentIdentitySync.decodeAvatar(withBytes).map(_.map(_.toSeq)), Some(Some(bytes.toSeq)))
      val oversize = base.copy(avatarB64 = Some(Base64.getEncoder.encodeToString(new Array[Byte](200_001))))
      assertEquals(AgentIdentitySync.decodeAvatar(oversize), None)
      val badB64 = base.copy(avatarB64 = Some("!!!not-base64!!!"))
      assertEquals(AgentIdentitySync.decodeAvatar(badB64), None)
    }

  // ── 推送外推：枚举 + 逐目标签署（seam 注入假发送）────────────────────────────

  test("pushIdentity fans out one signed payload per paired target; pushAllIdentity enumerates"):
    reset() *>
      IO.delay(mkAgent("Nebula", Some("小雨"), None)) *>
      IO.delay(mkAgent("bare", None, None)) *>
      IO.delay(writePairing("peerA" -> "keyA", "peerB" -> "keyB", "self" -> "keySelf")) *>
      IO.delay {
        val sent = scala.collection.mutable.ListBuffer.empty[(String, Json)]
        AgentIdentitySync.sendFn = (deviceId, payload) => IO { sent += ((deviceId, payload)); true }
        AgentIdentitySync.selfIdFn = IO.pure("self")
        AgentIdentitySync.panelBroadcastFn = _ => IO.unit
        sent
      }.flatMap { sent =>
        AgentIdentitySync.pushIdentity("Nebula") *> AgentIdentitySync.pushAllIdentity() *>
          IO {
            // pushIdentity 2 帧（peerA/peerB）+ reconcile 2 帧（bare 无身份不占帧）
            assertEquals(sent.map(_._1).toList.sorted, List("peerA", "peerA", "peerB", "peerB"))
            val payloads = sent.map { case (t, j) => t -> AgentIdentitySync.parsePayload(j).get }
            payloads.foreach { case (t, p) =>
              assertEquals(p.agent, "Nebula")
              assertEquals(p.displayName, Some("小雨"))
              assertEquals(p.fromDeviceId, "self")
              // 每份都按「本机与该目标的配对 key」签署（逐对端签署的可观测差异）。
              val expectedKey = if t == "peerA" then "keyA" else "keyB"
              assertEquals(p.sig, nebflow.core.device.DeviceHmac.sign(expectedKey, AgentIdentitySync.canonicalOf(p)))
            }
          }
      }

  test("unwired sync is a no-op (single-machine form)"):
    reset() *>
      IO.delay {
        AgentIdentitySync.sendFn = null
        AgentIdentitySync.selfIdFn = IO.pure("")
      } *>
      AgentIdentitySync.pushIdentity("Nebula").flatMap(_ => AgentIdentitySync.pushAllIdentity())
end AgentIdentitySyncSpec
