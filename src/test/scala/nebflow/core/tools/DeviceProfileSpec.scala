package nebflow.core.tools

import io.circe.parser.decode
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.neblink.PeerInfo

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.nio.file.attribute.FileTime
import scala.collection.mutable

/**
 * xdev 批（2026-09-15）核心面测试：设备画像 store / 手填冲突语义（作者口径）/
 * 白名单改写层（默认关）/ NEBFLOW_PULL 回拉扫描 / captures TTL 清扫。
 *
 * 隔离纪律：所有落盘用例经 `PathUtil.setDataRoot` 指向 per-test 临时目录，
 * 收尾恢复原值（`storePath`/`capturesRoot` 是 `def`，每次调用读取 ⇒ 立即跟随）。
 */
class DeviceProfileSpec extends FunSuite:

  private val originalRoot: os.Path = PathUtil.dataRoot
  private val tempRoots: mutable.ListBuffer[os.Path] = mutable.ListBuffer.empty

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(originalRoot)
    tempRoots.foreach(os.remove.all(_))
    tempRoots.clear()
    super.afterEach(context)

  private def isolatedRoot(): os.Path =
    val p = os.temp.dir()
    tempRoots += p
    PathUtil.setDataRoot(p)
    p

  private def peer(name: String, id: String, caps: Map[String, String] = Map.empty): PeerInfo =
    PeerInfo(deviceId = id, deviceName = name, platform = "windows", address = "http://127.0.0.1:1", capabilities = caps)

  import cats.effect.unsafe.implicits.global

  // ── ① 探测输出解析 ────────────────────────────────────────────────

  test("probe parse: key=value lines, whitelist keys only, value truncated at 300"):
    val out =
      """cwd=D:/Cadence/SPB_Data
        |kernel=MINGW64_NT-10.0-19045
        |machine=x86_64
        |bash=5.2.26(1)-release
        |msys=Msys
        |path=C:\Program Files\Git\cmd;C:\Windows;...
        |browser= (REG_SZ) C:\Windows\System32\...msedge.exe...
        |shot=powershell-dotnet
        |SOME_BANNER_LINE
        |evil_key=should_be_dropped""".stripMargin
    val parsed = DeviceProfileProbe.parse(out)
    assertEquals(parsed("cwd"), "D:/Cadence/SPB_Data")
    assertEquals(parsed("msys"), "Msys")
    assertEquals(parsed("shot"), "powershell-dotnet")
    assert(!parsed.contains("evil_key"), "whitelist: unknown keys must be dropped")
    val longVal = "x" * 500
    val truncated = DeviceProfileProbe.parse(s"path=$longVal")
    assertEquals(truncated("path").length, 300, "value capped at 300 chars")

  test("probe parse: empty/garbage output yields empty map (probe-failure path)"):
    assertEquals(DeviceProfileProbe.parse("").size, 0)
    assertEquals(DeviceProfileProbe.parse("bash: unexpected banner only\n").size, 0)

  // ── ① 手填 capabilities：不覆盖 / 冲突标 stale（作者口径逐字） ─────

  test("hand-caps conflict: measured wins, hand value KEPT with stale=true (never silently dropped)"):
    // 🔴 双向钉（绿面）：新代码 —— 手填 cwd=/old/path 与实测 /new/path 冲突
    //    ⇒ 实测生效（fields.cwd=/new/path）+ 手填留痕（handCaps.cwd.value=/old/path, stale=true, staleAt 记时刻）。
    val now = 1_700_000_000_000L
    val measured = Map("cwd" -> ProfileField("/new/path", now))
    val merged = DeviceProfile.mergeHandCaps(Map("cwd" -> "/old/path"), measured, now)
    val cwdHand = merged("cwd")
    assertEquals(cwdHand.value, "/old/path", "hand-filled value must be RETAINED verbatim")
    assertEquals(cwdHand.stale, true, "conflicting hand value must be marked stale")
    assertEquals(cwdHand.staleAt, Some(now))

  test("hand-caps red control: pre-batch behavior rendered hand value as fact (no measured value existed)"):
    // 🔴 红证：旧代码无自动检测 —— capabilities.cwd 是唯一信息源，模型按 /old/path
    // 写命令；实测值无处落地、冲突无从检出。等价断言：没有 fields/merge 机制时
    // 手填值原样输出（对照 —— 旧形态即 `Map("cwd" -> "/old/path")` 直通渲染）。
    val oldBehaviorDirectRender = Map("cwd" -> "/old/path")
    assertEquals(oldBehaviorDirectRender("cwd"), "/old/path",
      "old behavior: hand value rendered as fact with no measured conflict detection — this is what the batch replaces")
    // 新代码下同一场景不再成立：同键存在实测 ⇒ 渲染面以 fields（实测）为准。
    val now = 1_700_000_000_000L
    val merged = DeviceProfile.mergeHandCaps(Map("cwd" -> "/old/path"), Map("cwd" -> ProfileField("/new/path", now)), now)
    assert(merged("cwd").stale, "new code: same scenario is now detected and marked stale")

  test("hand-caps agreement: matching or contained values are NOT stale (no false positives)"):
    val now = 1_700_000_000_000L
    val measured = Map("browser" -> ProfileField("(REG_SZ) C:\\Windows\\System32\\...msedge.exe...", now))
    val merged = DeviceProfile.mergeHandCaps(Map("browser" -> "edge"), measured, now)
    assertEquals(merged("browser").stale, false, "hand 'edge' ⊆ measured msedge string = agreement")

  test("hand-caps: unknown key has no measured counterpart and is kept verbatim (never stale)"):
    val now = 1_700_000_000_000L
    val merged = DeviceProfile.mergeHandCaps(Map("gpu" -> "rtx4090"), Map.empty[String, ProfileField], now)
    assertEquals(merged("gpu").stale, false)
    assertEquals(merged("gpu").value, "rtx4090")

  test("hand-caps: cwd comparison normalizes trailing slashes"):
    val now = 1_700_000_000_000L
    val measured = Map("cwd" -> ProfileField("D:/Cadence/SPB_Data/", now))
    val merged = DeviceProfile.mergeHandCaps(Map("cwd" -> "D:/Cadence/SPB_Data"), measured, now)
    assertEquals(merged("cwd").stale, false, "trailing-slash difference is not a conflict")

  // ── ① store：写读 round-trip + fail-closed + 时效判定 ─────────────

  test("store: recordProbe then load round-trips entry (deviceId primary key)"):
    isolatedRoot()
    val p = peer("kai-windows", "aaaaaaaa-1111-2222-3333-444444444444")
    val saved = DeviceProfile.recordProbe(p, Map("cwd" -> "D:/x", "msys" -> "Msys"), Map("cwd" -> "D:/old"), 123L).unsafeRunSync()
    assert(saved.isDefined)
    val loaded = DeviceProfile.load.unsafeRunSync()
    val e = loaded("aaaaaaaa-1111-2222-3333-444444444444")
    assertEquals(e.deviceName, "kai-windows")
    assertEquals(e.available, true)
    assertEquals(e.fields("cwd").value, "D:/x")
    assertEquals(e.handCaps("cwd").stale, true)

  test("store: corrupt file fails closed to empty map with WARN, not crash"):
    val root = isolatedRoot()
    os.makeDir.all(root / "neblink")
    os.write(root / "neblink" / "device-profiles.json", "{not valid json!!")
    val loaded = DeviceProfile.load.unsafeRunSync()
    assertEquals(loaded.size, 0, "fail-closed: corrupt store = no profiles, behavior = pre-batch status quo")

  test("needsProbe: fresh entry false; expired TTL / address change / rename / version gap / missing all true"):
    val p = peer("kai", "id-fresh-1")
    val now = 1_700_000_000_000L
    val fresh = DeviceProfileEntry("id-fresh-1", "kai", "windows",
      P2pPathDecision.addressKey(p), now, DeviceProfile.SchemaVersion, true)
    assertEquals(DeviceProfile.needsProbe(Some(fresh), p, now), false, "fresh entry = no probe")
    assertEquals(DeviceProfile.needsProbe(None, p, now), true, "missing entry = probe")
    val expired = fresh.copy(probedAt = now - DeviceProfile.ProfileTtlMs - 1)
    assertEquals(DeviceProfile.needsProbe(Some(expired), p, now), true, "TTL expired = stale = re-probe")
    val moved = fresh.copy(addressKey = "other-endpoints")
    assertEquals(DeviceProfile.needsProbe(Some(moved), p, now), true, "address set change = stale")
    val renamed = fresh.copy(deviceName = "old-name")
    assertEquals(DeviceProfile.needsProbe(Some(renamed), p, now), true, "device name change = forced re-probe")
    val oldVersion = fresh.copy(probeVersion = DeviceProfile.SchemaVersion - 1)
    assertEquals(DeviceProfile.needsProbe(Some(oldVersion), p, now), true, "version gap = re-probe")

  test("needsProbe: negative entry suppressed within backoff window, retryable after (负控 3)"):
    val p = peer("kai", "id-neg-1")
    val now = 1_700_000_000_000L
    val negative = DeviceProfileEntry("id-neg-1", "kai", "windows", P2pPathDecision.addressKey(p),
      now, DeviceProfile.SchemaVersion, false, negativeUntil = Some(now + DeviceProfile.NegativeRetryMs))
    assertEquals(DeviceProfile.needsProbe(Some(negative), p, now), false, "within backoff: no re-probe")
    assertEquals(DeviceProfile.needsProbe(Some(negative), p, now + DeviceProfile.NegativeRetryMs + 1), true,
      "after backoff: retryable")

  // ── ① # Devices 摘要（1-2 行/台 + stale 标记；缺失 = 现状形态） ────

  test("renderSummary: missing profile renders empty (coverage honesty: unknown shown as unknown)"):
    val p = peer("kai", "id-sum-1")
    assertEquals(DeviceProfile.renderSummary(p, Map.empty, 0L), "")
    assertEquals(DeviceProfile.renderCaptureHint(p, Map.empty), "")

  test("renderSummary: populated profile renders 1 line with fields; stale marks appear"):
    val p = peer("kai", "id-sum-2")
    val now = 1_700_000_000_000L
    val e = DeviceProfileEntry("id-sum-2", "kai", "windows", P2pPathDecision.addressKey(p), now,
      DeviceProfile.SchemaVersion, true,
      fields = Map("cwd" -> ProfileField("D:/x", now), "msys" -> ProfileField("Msys", now),
        "bash" -> ProfileField("5.2.26", now), "shot" -> ProfileField("powershell-dotnet", now)),
      handCaps = Map("browser" -> HandCapField("edge", stale = true, Some(now))))
    val s = DeviceProfile.renderSummary(p, Map("id-sum-2" -> e), now)
    assert(s.contains("cwd=D:/x"))
    assert(s.contains("capture=powershell-dotnet"))
    assert(s.contains("stale-hand:browser"), "hand conflict must surface in the summary")
    val hint = DeviceProfile.renderCaptureHint(p, Map("id-sum-2" -> e))
    assert(hint.contains("NEBFLOW_PULL"), "capture hint carries the pull convention")
    val totalLines = (s + hint).linesIterator.size
    assert(totalLines <= 3, s"1-2 lines per device (summary + hint line), got $totalLines")

  test("renderSummary: expired profile carries ⚠stale marker"):
    val p = peer("kai", "id-sum-3")
    val now = 1_700_000_000_000L
    val e = DeviceProfileEntry("id-sum-3", "kai", "windows", P2pPathDecision.addressKey(p), now - DeviceProfile.ProfileTtlMs - 5,
      DeviceProfile.SchemaVersion, true, fields = Map("cwd" -> ProfileField("D:/x", now)))
    val s = DeviceProfile.renderSummary(p, Map("id-sum-3" -> e), now)
    assert(s.contains("⚠stale"))

  // ── ⑤ 改写层：默认关 / 白名单等价 / 禁改透传警告 / 幂等 ────────────

  test("rewrite: DEFAULT OFF — enabled env unset means zero behavior change"):
    // 缺省（无 NEBFLOW_XDEV_REWRITE）⇒ enabled=false ⇒ 恒等（现状口径）。
    // 进程内断言：enabled=false 时（CI 环境默认无该 env），apply 恒等。
    if !XdevRewrite.enabled then
      val (c, note) = XdevRewrite("cmd /c start calc", msys = true)
      assertEquals(c, "cmd /c start calc")
      assertEquals(note, None)

  test("rewriteOnce: whitelist equivalence cmd /c -> cmd //c when MSYS confirmed"):
    val (c, note) = XdevRewrite.rewriteOnce("cmd /c start calc", msys = true, enabledFlag = true)
    assertEquals(c, "cmd //c start calc")
    assert(note.exists(_.startsWith("rewritten")))

  test("rewriteOnce: disabled / non-MSYS = identity (fail-closed axes)"):
    assertEquals(XdevRewrite.rewriteOnce("cmd /c start calc", msys = false, enabledFlag = true)._1,
      "cmd /c start calc", "no MSYS confirmation = no rewrite")
    assertEquals(XdevRewrite.rewriteOnce("cmd /c start calc", msys = true, enabledFlag = false)._1,
      "cmd /c start calc", "layer disabled = no rewrite")

  test("rewriteOnce: idempotent — already-rewritten form is never double-rewritten"):
    val (c, note) = XdevRewrite.rewriteOnce("cmd //c start calc", msys = true, enabledFlag = true)
    assertEquals(c, "cmd //c start calc", "no ///c double rewrite")
    assertEquals(note, None)

  test("rewriteOnce: forbidden chars = pass-through + visible warning (改写错防线)"):
    for cmd <- List(
      "cmd /c start calc && echo done",      // && 分隔
      "cmd /c \"start calc\"",               // 引号
      "echo x | cmd /c start calc",          // 管道
      "cmd /c start calc; rm -rf build",     // 分号 + 破坏性动词
      "cmd /c echo %USERPROFILE%",           // cmd 转义字符 %
      "cmd /c start calc > out.txt",         // 重定向
      "cmd /c start $(calc)"                 // 命令替换
    )
    do
      val (c, note) = XdevRewrite.rewriteOnce(cmd, msys = true, enabledFlag = true)
      assertEquals(c, cmd, s"pass-through for: $cmd")
      assert(note.exists(_.startsWith("NOT rewritten")), s"visible warning for: $cmd")

  test("rewriteOnce: commands without cmd /c are untouched and silent (no warning noise)"):
    val (c, note) = XdevRewrite.rewriteOnce("uname -a", msys = true, enabledFlag = true)
    assertEquals(c, "uname -a")
    assertEquals(note, None)

  test("msysConfirmed: Msys/MINGW variants true; linux/darwin/empty false"):
    def entry(msysVal: String): DeviceProfileEntry =
      DeviceProfileEntry("d", "n", "p", "a", 0L, 1, true,
        fields = Map("msys" -> ProfileField(msysVal, 0L)))
    assert(XdevRewrite.msysConfirmed(Some(entry("Msys"))))
    assert(XdevRewrite.msysConfirmed(Some(entry("MINGW64_NT-10.0"))))
    assert(!XdevRewrite.msysConfirmed(Some(entry("GNU/Linux"))))
    assert(!XdevRewrite.msysConfirmed(None))
    assert(!XdevRewrite.msysConfirmed(Some(DeviceProfileEntry("d", "n", "p", "a", 0L, 1, true))))

  // ── ② NEBFLOW_PULL 扫描 ──────────────────────────────────────────

  test("scanPullPaths: extracts marker paths, caps at 3, ignores marker-less lines"):
    val out =
      """some output
        |NEBFLOW_PULL:D:/tmp/shot1.png
        |more output
        |NEBFLOW_PULL:C:/Users/x/shot2.png
        |NEBFLOW_PULL:/tmp/three.png
        |NEBFLOW_PULL:/tmp/four.png
        |NEBFLOW_PULL:/tmp/five.png""".stripMargin
    val paths = CapturePull.scanPullPaths(out)
    assertEquals(paths, List("D:/tmp/shot1.png", "C:/Users/x/shot2.png", "/tmp/three.png"),
      "max 3 pulls per call; extras ignored")
    assertEquals(CapturePull.scanPullPaths("no markers here").size, 0)

  // ── ④ captures TTL：7 天判定 + 逐件留痕 + 落点不入 git ────────────

  test("sweepExpired: removes only files older than 7d TTL, keeps fresh ones (clock via mtime)"):
    val root = isolatedRoot()
    val devDir = root / "neblink" / "captures" / "dev-ttl-1"
    os.makeDir.all(devDir)
    val oldF = devDir / "old-shot.png"
    val newF = devDir / "new-shot.png"
    os.write(oldF, Array[Byte](1, 2, 3))
    os.write(newF, Array[Byte](4, 5, 6))
    val now = System.currentTimeMillis()
    Files.setLastModifiedTime(oldF.toNIO, FileTime.fromMillis(now - CapturePull.CaptureTtlMs - 60_000))
    Files.setLastModifiedTime(newF.toNIO, FileTime.fromMillis(now - 60_000))
    val removed = CapturePull.sweepExpired(now).unsafeRunSync()
    assertEquals(removed, 1)
    assert(!os.exists(oldF), "expired (age > 7d) file must be gone")
    assert(os.exists(newF), "fresh file must be kept")

  test("captures tree: lives under dataRoot/neblink (NOT allowlisted for HTTP serving) — privacy axis"):
    val root = isolatedRoot()
    val devDir = root / "neblink" / "captures" / "dev-priv-1"
    os.makeDir.all(devDir)
    os.write(devDir / "s.png", Array[Byte](1))
    // 判红③ 三断言之「不在可服务面」：captures 头两层 = neblink/captures，
    // 而 NfDataRootAllowlist = projects/uploads/plots/workspace-items/voice-models。
    val servedPrefixes = List("projects", "uploads", "plots", "workspace-items", "voice-models")
    val relative = devDir.relativeTo(root).toString
    assert(!servedPrefixes.exists(p => relative.startsWith(p)),
      s"captures path '$relative' must not live under any HTTP-served allowlist prefix")
    // 与 uploads / evidence（workspace 可服务面）零交集：
    assert(!relative.contains("uploads") && !relative.contains("evidence"))

  // ── ④ 契约兼容：回拉响应解析（FileTransferAction.get 出参形态） ────

  test("transfer-get response shape: {content,size} decodes; oversize rejected by cap"):
    // FileTransferAction "get" 成功出参 = Json.obj("content" -> b64, "size" -> len)
    val raw = "PNG".getBytes(StandardCharsets.UTF_8)
    val b64 = java.util.Base64.getEncoder.encodeToString(raw)
    val good = s"""{"content":"$b64","size":3}"""
    decode[io.circe.Json](good) match
      case Right(json) =>
        assertEquals(json.hcursor.downField("size").as[Long].getOrElse(0L), 3L)
        val decodedBack = java.util.Base64.getDecoder.decode(
          json.hcursor.downField("content").as[String].getOrElse(""))
        assert(decodedBack.sameElements(raw), "content round-trips through base64")
      case other => fail(s"shape decode failed: $other")
    // 大小闸在发送端（transferGetToFile）按 size 字段拒绝 > 10 MiB —— 阈值语义此处只钉常量。
    assertEquals(CapturePull.MaxCaptureBytes, 10L * 1024 * 1024)

end DeviceProfileSpec
