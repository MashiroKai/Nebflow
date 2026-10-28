package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.agent.SubAgentTaskStore
import nebflow.shared.{PathUtil, SubAgentTask}

import scala.concurrent.duration.*

/**
 * AgentSessionSync spec（台账 #929 · 后台子智能体跨设备透明批 2026-10-26）：
 * 远端会话摘要投影通道的写读路径单测——载荷构建/编码/宽容解析/逐对端验签/
 * <2KB 闸/LWW 收敛（含首见即收乱序守卫）/投影仓持久与剪裁/推送外推/写后外推钩/
 * UI 帧过滤（终态揭示窗·墓碑·在线角标）。
 */
class AgentSessionSyncSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-agent-session-sync"
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

  private def mkTask(
    taskId: String = "delegate-abc123",
    status: String = "running",
    description: String = "批次标题：修复跨设备可见性",
    spawnedAt: Long = 1_000L,
    completedAt: Option[Long] = None,
    statusAt: Option[Long] = Some(1_000L),
    retryCount: Int = 0,
    source: String = "delegate"
  ): SubAgentTask =
    SubAgentTask(
      taskId = taskId,
      parentSessionId = "root-1",
      agentName = "Nebula",
      prompt = "SECRET-PROMPT-全文绝不上机",
      description = description,
      status = status,
      retryCount = retryCount,
      spawnedAt = spawnedAt,
      completedAt = completedAt,
      lastError = None,
      source = source,
      statusAt = statusAt
    )

  private def signed(p: AgentSessionSync.SessionPayload, key: String = "shared-key-1"): AgentSessionSync.SessionPayload =
    p.copy(sig = nebflow.core.device.DeviceHmac.sign(key, AgentSessionSync.canonicalOf(p)))

  // ── 载荷：构建 → 编码 → 宽容解析 roundtrip ──────────────────────────────────

  test("buildPayload: summary fields only (no prompt), title first-line + truncation"):
    val longTitle = "首行标题" + "x" * 500 + "\n第二行不该上机" // 截断批注：x 列在首行内，第二行独立
    val p = AgentSessionSync.buildPayload(mkTask(description = longTitle), "kai", "KAI", 1_800_000_000_000L)
    assertEquals(p.sessionId, "delegate-abc123")
    assertEquals(p.title, "首行标题" + "x" * (AgentSessionSync.MaxTitleChars - "首行标题".length)) // 首行 + 截断 200
    assert(!p.title.contains("第二行不该上机"), "second line must not travel")
    assertEquals(p.source, "delegate")
    assertEquals(p.status, "running")
    assertEquals(p.startedAt, 1_000L)
    assertEquals(p.endedAt, None)
    assertEquals(p.updatedAt, 1_000L)
    assert(!AgentSessionSync.encodePayload(p).noSpaces.contains("SECRET-PROMPT"), "prompt must never travel")

  test("encode/parse roundtrip preserves every field; wrong kind / missing sig parses to None"):
    val unsigned = AgentSessionSync.buildPayload(
      mkTask(status = "completed", completedAt = Some(2_000L), statusAt = Some(2_000L), retryCount = 2),
      "kai", "KAI", 1_800_000_000_000L
    )
    val signedP = signed(unsigned)
    assertEquals(AgentSessionSync.parsePayload(AgentSessionSync.encodePayload(signedP)), Some(signedP))
    // kind 信封不对 ⇒ None（数据通道上有别的流量，静默忽略）
    val wrongKind = AgentSessionSync.encodePayload(signedP).deepMerge(Json.obj("kind" -> "other".asJson))
    assertEquals(AgentSessionSync.parsePayload(wrongKind), None)
    // 缺签名字段 ⇒ None（fail-closed）
    val noSig = AgentSessionSync.encodePayload(signedP).asObject.get.remove("sig")
    assertEquals(AgentSessionSync.parsePayload(Json.fromJsonObject(noSig)), None)

  // ── 验签：逐对端 key + ts 窗 + 篡改拒收 ─────────────────────────────────────

  test("signedFor/verifyPayload: paired sender passes; tamper/stale/unpaired rejected"):
    reset() *>
      IO.delay(writePairing("kai" -> "shared-key-1", "mac" -> "shared-key-1")) *>
      IO.delay {
        val nowMs = System.currentTimeMillis()
        val unsigned = AgentSessionSync.buildPayload(mkTask(), "kai", "KAI", nowMs)
        val signedP = AgentSessionSync.signedFor(unsigned, "mac") // 用「本机↔mac」key 签
        // 收端视角：kai 在收端配对表内（同 key）⇒ 通过
        assertEquals(AgentSessionSync.verifyPayload(signedP, nowMs), None)
        // 篡改内容（改 status 不改 sig）⇒ 拒
        val tampered = signedP.copy(status = "completed")
        assert(AgentSessionSync.verifyPayload(tampered, nowMs).isDefined)
        // 过期 ts（±90s 窗外）⇒ 拒
        val stale = signedP.copy(tsSec = nowMs / 1000 - 3600)
        assert(AgentSessionSync.verifyPayload(stale, nowMs).isDefined)
        // 未配对发送方 ⇒ 拒
        val stranger = unsigned.copy(fromDeviceId = "unpaired")
        assert(AgentSessionSync.verifyPayload(signed(stranger, "other"), nowMs).isDefined)
      }

  // ── 摘要闸：单载荷 <2KB ────────────────────────────────────────────────────

  test("payload bytes stay under the 2KB summary gate even with a maxed title"):
    val maxed = AgentSessionSync.buildPayload(
      mkTask(description = "t" * 1000),
      "device-with-a-long-name-0123456789abcdef", "Device With A Long Name", 1_800_000_000_000L
    )
    assert(AgentSessionSync.payloadBytes(maxed) < 2048, s"bytes=${AgentSessionSync.payloadBytes(maxed)}")

  // ── 投影仓：LWW 收敛（首见即收乱序守卫）+ 剪裁 + 墓碑 ────────────────────────

  test("RemoteAgentSessions LWW: first-seen accepts older stamp; strictly older dropped; equal applied"):
    // 复验批注（batchA 2026-10-27）：组合式重写——首轮超时根因不在本测试形态，
    // 而在 RemoteAgentSessions 锁内嵌套起 runtime（apply → ensureLoaded →
    // load().unsafeRunSync() inside IO.blocking，见 AgentSessionSync.loadLocked
    // 批注）；本测试同步改组合式并顺带修正「先建帧后播种」的构造序陷阱
    // （remoteRowsFrame 已 IO.defer 化，见该函数批注）。
    val s = RemoteAgentSessions.at(None)
    val newer = AgentSessionSync.buildPayload(mkTask(status = "running", statusAt = Some(200L)), "kai", "KAI", 0L)
    val older = AgentSessionSync.buildPayload(mkTask(status = "running", statusAt = Some(100L)), "kai", "KAI", 0L)
    // 乱序守卫：更旧行先到（首见）⇒ 收；更新行 ⇒ 收；旧行重放 ⇒ 丢；等戳重放 ⇒ 收（幂等无害）。
    s.apply(older).flatMap { r1 =>
      assertEquals(r1, true)
      s.apply(newer).flatMap { r2 =>
        assertEquals(r2, true)
        s.apply(older).flatMap { r3 =>
          assertEquals(r3, false)
          s.apply(newer).map { r4 =>
            assertEquals(r4, true)
            assertEquals(s.rows().map(_.updatedAt), List(200L))
          }
        }
      }
    }

  test("RemoteAgentSessions: rows are per-device keyed and persisted+reloaded from disk"):
    reset() *> IO.delay(writePairing()) *> {
      val path = tempRoot / "neblink" / "remote-agent-sessions.json"
      val s = RemoteAgentSessions.at(Some(path))
      s.apply(AgentSessionSync.buildPayload(mkTask(taskId = "delegate-1"), "kai", "KAI", 0L)).flatMap { _ =>
        s.apply(AgentSessionSync.buildPayload(mkTask(taskId = "delegate-1"), "mac", "MacBook", 0L)).flatMap { _ =>
          IO(assertEquals(s.rows().map(_.fromDeviceId).sorted, List("kai", "mac"))) *>
            // 新实例载盘恢复（断联语义的持久承载）
            IO(RemoteAgentSessions.at(Some(path))).flatMap { s2 =>
              s2.load() *> IO(assertEquals(s2.rows().map(_.sessionId).sorted, List("delegate-1", "delegate-1")))
            }
        }
      }
    }

  test("RemoteAgentSessions: per-device cap prunes oldest terminal first, then oldest"):
    val s = RemoteAgentSessions.at(None)
    // 3 行（帽 200 不触发）——这里直接压小面验证剪除次序：手工灌 MaxRowsPerDevice+2
    val base = 0L
    (1 to AgentSessionSync.MaxRowsPerDevice + 2).toList.foldLeft(IO.unit) { (acc, i) =>
      val status = if i <= 2 then "completed" else "running" // 旧行做成终态 → 先剪
      val t = mkTask(taskId = s"delegate-$i", status = status, statusAt = Some(i.toLong), completedAt = Some(i.toLong))
      acc *> s.apply(AgentSessionSync.buildPayload(t, "kai", "KAI", base)).void
    } *> IO {
      val rows = s.rows().filter(_.fromDeviceId == "kai")
      assertEquals(rows.size, AgentSessionSync.MaxRowsPerDevice)
      // 最旧的两条终态（i=1,2）被剪
      assert(!rows.exists(_.sessionId == "delegate-1"))
      assert(!rows.exists(_.sessionId == "delegate-2"))
      assert(rows.exists(_.sessionId == "delegate-3"))
    }

  // ── UI 帧：终态揭示窗 + 墓碑过滤 + 在线角标 ─────────────────────────────────

  test("remoteRowsFrame: in-flight always; terminal within reveal window; old terminal/tombstone excluded; online flag carried"):
    reset() *>
      IO.delay(writePairing("kai" -> "k")) *> IO.delay {
        val now = System.currentTimeMillis()
        val s = RemoteAgentSessions.at(None)
        AgentSessionSync.store = s
        AgentSessionSync.onlineFn = _ => IO.pure(false) // 断联语义：离线角标
        val running = mkTask(taskId = "delegate-run", status = "running", statusAt = Some(now))
        val freshDone = mkTask(taskId = "delegate-done", status = "completed", statusAt = Some(now), completedAt = Some(now - 1000))
        val oldDone = mkTask(taskId = "delegate-old", status = "completed", statusAt = Some(now - 60_000), completedAt = Some(now - 60_000))
        val removed = mkTask(taskId = "delegate-gone", status = SubAgentTaskStore.StatusRemoved, statusAt = Some(now))
        (now, List(running, freshDone, oldDone, removed))
      }
        .flatMap { case (now, rows) =>
          val s = RemoteAgentSessions.at(None)
          AgentSessionSync.store = s // 复验批注：接缝必须指向**本腿**播种的实例（第一腿 delay 里那个是空仓）
          val seeded = rows.foldLeft(IO.unit)((acc, t) => acc *> s.apply(AgentSessionSync.buildPayload(t, "kai", "KAI", now)).void)
          seeded *>
            AgentSessionSync.remoteRowsFrame(now).flatMap {
              case None => IO.raiseError(new AssertionError("expected a non-empty frame"))
              case Some(frame) =>
                IO {
                  val agents = frame.hcursor.downField("agents").as[List[Json]].toOption.get
                  val ids = agents.map(_.hcursor.downField("sessionId").as[String].toOption.get)
                  assertEquals(ids.sorted, List("delegate-done", "delegate-run")) // 旧终态 + 墓碑不进帧
                  val runRow = agents.find(_.hcursor.downField("sessionId").as[String].toOption.get == "delegate-run").get
                  assertEquals(runRow.hcursor.downField("deviceOnline").as[Boolean].toOption.get, false)
                  assertEquals(runRow.hcursor.downField("deviceName").as[String].toOption.get, "KAI")
                }
            } *>
            // 行集空 ⇒ 零帧（None——不造空噪帧）
            IO(AgentSessionSync.store = RemoteAgentSessions.at(None)) *>
            AgentSessionSync.remoteRowsFrame(now).flatMap {
              case None => IO.unit
              case Some(_) => IO.raiseError(new AssertionError("empty store must not emit a frame"))
            }
        }

  // ── 推送面：逐目标签署外推 + 未装配 no-op ───────────────────────────────────

  test("pushTask fans out one signed payload per paired target; pushAllSessions enumerates the store"):
    reset() *>
      IO.delay(writePairing("peerA" -> "keyA", "peerB" -> "keyB", "self" -> "keySelf")) *>
      IO.delay {
        val sent = scala.collection.mutable.ListBuffer.empty[(String, Json)]
        AgentSessionSync.sendFn = (deviceId, payload) => IO { sent += ((deviceId, payload)); true }
        AgentSessionSync.selfIdFn = IO.pure("self")
        AgentSessionSync.deviceNameFn = IO.pure("SELF")
        AgentSessionSync.allTasksFn = IO.pure(List(mkTask(taskId = "delegate-1"), mkTask(taskId = "delegate-2")))
        sent
      }
        .flatMap { sent =>
          AgentSessionSync.pushTask(mkTask(taskId = "delegate-9")) *>
            AgentSessionSync.pushAllSessions() *>
            IO {
              // push 2 帧（peerA/peerB）+ reconcile 4 帧（2 会话 × 2 目标）
              assertEquals(sent.count(_._1 == "peerA"), 3)
              assertEquals(sent.count(_._1 == "peerB"), 3)
              val payloads = sent.map { case (t, j) => t -> AgentSessionSync.parsePayload(j).get }
              // 每份都按「本机与该目标的配对 key」签署（逐对端签署的可观测差异）。
              payloads.foreach { case (t, p) =>
                val expectedKey = if t == "peerA" then "keyA" else "keyB"
                assertEquals(p.sig, nebflow.core.device.DeviceHmac.sign(expectedKey, AgentSessionSync.canonicalOf(p)))
                assertEquals(p.deviceName, "SELF")
              }
              assertEquals(payloads.map(_._2.sessionId).distinct.sorted.toList, List("delegate-1", "delegate-2", "delegate-9"))
            }
        }

  test("unwired sync is a no-op (single-machine form)"):
    IO.delay {
      AgentSessionSync.sendFn = null
      AgentSessionSync.allTasksFn = IO.pure(List(mkTask()))
    } *> AgentSessionSync.pushTask(mkTask()).flatMap(_ => AgentSessionSync.pushAllSessions())

  // ── 收件面：验签落地 → 投影仓 → 变更即广播 ───────────────────────────────────

  /** 面板广播捕获面（spec 级——flatMap 两段共用）。 */
  private val broadcasts = new java.util.concurrent.CopyOnWriteArrayList[Json]()

  test("handlePayload: verified frame applies + broadcasts; bad sig rejected silently; stale frame no broadcast"):
    reset() *>
      IO.delay(writePairing("kai" -> "shared-key-1", "mac" -> "shared-key-1")) *> IO.delay {
        broadcasts.clear()
        val s = RemoteAgentSessions.at(None)
        AgentSessionSync.store = s
        AgentSessionSync.panelBroadcastFn = j => IO { broadcasts.add(j); () }
        AgentSessionSync.onlineFn = _ => IO.pure(true)
        val nowMs = System.currentTimeMillis()
        val good = AgentSessionSync.encodePayload(
          AgentSessionSync.signedFor(AgentSessionSync.buildPayload(mkTask(), "kai", "KAI", nowMs), "mac")
        )
        // 伪帧：改 status 不改 sig
        val forged = good.deepMerge(Json.obj("status" -> "failed".asJson))
        // 旧帧：updatedAt 严格小于已收行
        val staleUnsigned = AgentSessionSync.buildPayload(mkTask(), "kai", "KAI", nowMs)
          .copy(updatedAt = -1L)
        val stale = AgentSessionSync.encodePayload(
          staleUnsigned.copy(sig = nebflow.core.device.DeviceHmac.sign("shared-key-1", AgentSessionSync.canonicalOf(staleUnsigned)))
        )
        (good, forged, stale)
      }
        .flatMap { case (good, forged, stale) =>
          AgentSessionSync.handlePayload(good) *>
            IO {
              assertEquals(AgentSessionSync.store.rows().map(_.sessionId), List("delegate-abc123"))
              assertEquals(broadcasts.size(), 1) // 变更即广播
            } *>
            AgentSessionSync.handlePayload(forged) *>
            IO {
              // 伪帧被拒：仓内行 status 不变、零新增广播
              assertEquals(AgentSessionSync.store.rows().map(_.status), List("running"))
              assertEquals(broadcasts.size(), 1)
            } *>
            AgentSessionSync.handlePayload(stale) *>
            IO {
              assertEquals(broadcasts.size(), 1) // LWW 空转 ⇒ 不广播
              assertEquals(AgentSessionSync.store.rows().map(_.updatedAt), List(1_000L))
            }
        }

  // ── 写后外推钩：记账面 → 钩（fire-and-forget、行镜像）────────────────────────

  /** 钩捕获面（spec 级唯一消费者 = 下一个用例；CopyOnWriteList 跨 fiber 安全）。 */
  private val fired = new java.util.concurrent.CopyOnWriteArrayList[SubAgentTask]()

  test("SubAgentTaskStore write hook fires on record/update/remove/cancel with the fresh row"):
    reset() *>
      IO.delay {
        fired.clear()
        val store = new SubAgentTaskStore(tempRoot / "subagent-tasks")
        store.setWriteHook(t => IO { fired.add(t); () })
        store
      }
        .flatMap { store =>
          val t0 = mkTask(taskId = "delegate-x", spawnedAt = 5_000L, statusAt = None)
          store.recordTask(t0) *>
            IO.sleep(150.millis) *>
            IO {
              assertEquals(fired.size(), 1)
              assertEquals(fired.get(0).taskId, "delegate-x")
              assertEquals(fired.get(0).statusAt, Some(5_000L)) // recordTask 补缺省戳
            } *>
            store.updateStatus("root-1", "delegate-x", "completed", completedAt = Some(6_000L)) *>
            IO.sleep(150.millis) *>
            IO {
              assertEquals(fired.size(), 2)
              assertEquals(fired.get(1).status, "completed")
              assertEquals(fired.get(1).completedAt, Some(6_000L))
              assert(fired.get(1).statusAt.nonEmpty) // updateStatus 刷新 statusAt
            } *>
            store.removeTask("root-1", "delegate-x") *>
            IO.sleep(150.millis) *>
            IO {
              assertEquals(fired.size(), 3)
              assertEquals(fired.get(2).status, SubAgentTaskStore.StatusRemoved) // 墓碑
            } *>
            store.recordTask(mkTask(taskId = "delegate-y", spawnedAt = 7_000L, statusAt = Some(7_000L))) *>
            store.cancelRunningForParent("root-1", "cascade") *>
            IO.sleep(150.millis) *>
            IO {
              // 复验批注（batchA 2026-10-27）：recordTask(y) 本身也是一次记账写入 ⇒
              // 钩照发（第 4 发）；cancelRunningForParent 级联取消 y 是第 5 发 ——
              // 原稿漏数 recordTask 腿（期望 4 实得 5）。
              assertEquals(fired.size(), 5)
              assertEquals(fired.get(3).taskId, "delegate-y") // record 腿
              assertEquals(fired.get(4).status, "cancelled") // 级联取消腿
            }
        }

  test("SubAgentTaskStore without hook stays no-op (existing single-machine behavior)"):
    reset() *>
      IO.delay(new SubAgentTaskStore(tempRoot / "subagent-tasks-2"))
        .flatMap { store =>
          store.recordTask(mkTask(taskId = "delegate-z", spawnedAt = 1L)) *>
            store.updateStatus("root-1", "delegate-z", "completed") *>
            store.loadTasks("root-1").map(tasks => assertEquals(tasks.map(_.status), List("completed")))
        }

end AgentSessionSyncSpec
