package nebflow.core.tools

import munit.FunSuite
import nebflow.shared.PeerInfo

/**
 * xdev 批（2026-09-15）③ 歧义即拒绝（作者裁定 A）：`resolvePeer` 分层匹配 +
 * 多命中显式拒绝（附全部候选名单）；单命中 / 零命中语义不变。
 *
 * 🔴 双向钉（红绿对照）：
 *   - 红：旧实现 = `peers.find(...)`（首个命中，静默）—— 在两候选夹具下它会
 *     **选中列表第一台**。本文件的红证通过显式断言「旧行为 = 列表首元素」与
 *     新行为的「显式报错」对照表达（旧实现已不在代码路径上，红证由对照夹具
 *     + 注释锚定，等价于方案卡 4.A(5) 负控形态）。
 *   - 绿：新实现同夹具 ⇒ ToolError 携带全部候选。
 */
class RemoteExecutorResolveSpec extends FunSuite:

  private def p(name: String, id: String): PeerInfo =
    PeerInfo(deviceId = id, deviceName = name, platform = "windows", address = s"http://127.0.0.1:1")

  /** 旧实现逐字复刻（红证锚点）：首命中即选，静默。 */
  private def legacyResolve(deviceName: String, peers: List[PeerInfo]): Option[PeerInfo] =
    peers.find(p =>
      p.deviceName.equalsIgnoreCase(deviceName) ||
        p.deviceId.startsWith(deviceName) ||
        p.deviceName.toLowerCase.contains(deviceName.toLowerCase)
    )

  // ── 模糊多命中（真歧义）：红证 + 绿 ────────────────────────────────

  private val twoCandidates = List(
    p("kai-windows", "aaaaaaaa-1111-2222-3333-444444444444"),
    p("kai-windows-2", "bbbbbbbb-1111-2222-3333-444444444444")
  )

  test("RED: legacy find would silently pick the FIRST candidate under the same two-candidate fixture"):
    val picked = legacyResolve("kai-windows", twoCandidates)
    assertEquals(
      picked.map(_.deviceId),
      Some("aaaaaaaa-1111-2222-3333-444444444444"),
      "old behavior: first match wins silently — the misfire this batch removes"
    )

  test("GREEN: new resolvePeer rejects ambiguity with explicit error + full candidate list"):
    // 查 `kai`：精确层零命中 ⇒ 模糊层（contains）命中两台 ⇒ 真歧义 ⇒ 拒绝 + 候选名单。
    // （查 `kai-windows` 是精确唯一命中第一台的正常路径，不算歧义。）
    RemoteExecutor.resolvePeer("kai", twoCandidates) match
      case Left(err) =>
        assert(err.message.contains("ambiguous"), s"must be explicit: ${err.message}")
        assert(err.message.contains("2 peers match"))
        assert(err.message.contains("kai-windows(aaaaaaaa)"), s"candidate 1 listed: ${err.message}")
        assert(err.message.contains("kai-windows-2(bbbbbbbb)"), s"candidate 2 listed: ${err.message}")
        assert(err.message.contains("deviceId"), s"must tell caller the exact-id escape hatch: ${err.message}")
      case Right(peer) => fail(s"must NOT silently resolve ambiguity; picked ${peer.deviceName}")

  test("RED-side note: same fixture queried by exact name 'kai-windows' resolves to the exact machine (not ambiguous)"):
    RemoteExecutor.resolvePeer("kai-windows", twoCandidates) match
      case Right(peer) => assertEquals(peer.deviceId, "aaaaaaaa-1111-2222-3333-444444444444")
      case Left(err) => fail(s"exact unique match must keep working: ${err.message}")

  test("GREEN: exact-name unique match still works even when another peer contains the name"):
    // `kai` 精确命中 1 台；`kai-windows` contains 命中 2 台 ⇒ 分层判定：精确层
    // 恰一命中 ⇒ 用它（非歧义）。这是「歧义即拒绝」的最小破坏实现。
    val peers = List(p("kai", "cccccccc-1111-2222-3333-444444444444"), twoCandidates(0), twoCandidates(1))
    RemoteExecutor.resolvePeer("kai", peers) match
      case Right(peer) => assertEquals(peer.deviceId, "cccccccc-1111-2222-3333-444444444444")
      case Left(err) => fail(s"exact unique match must not be rejected: ${err.message}")

  // ── 精确多命中（真同名设备）：拒绝 ────────────────────────────────

  test("exact duplicate names (two machines, same name) are rejected with candidates"):
    val peers =
      List(p("lab-box", "dddddddd-1111-2222-3333-444444444444"), p("lab-box", "eeeeeeee-1111-2222-3333-444444444444"))
    RemoteExecutor.resolvePeer("lab-box", peers) match
      case Left(err) =>
        assert(err.message.contains("ambiguous"))
        assert(err.message.contains("dddddddd") && err.message.contains("eeeeeeee"))
      case Right(peer) => fail(s"true same-name collision must be rejected; picked ${peer.deviceId}")

  // ── 单命中 / 零命中：语义不变（回归） ─────────────────────────────

  test("single fuzzy match resolves as before (prefix / contains)"):
    val peers = List(p("kai-windows", "aaaaaaaa-1111-2222-3333-444444444444"))
    RemoteExecutor.resolvePeer("kai-win", peers) match
      case Right(peer) => assertEquals(peer.deviceId, "aaaaaaaa-1111-2222-3333-444444444444")
      case Left(err) => fail(s"single fuzzy match must keep working: ${err.message}")

  test("deviceId-prefix targeting still resolves to the exact machine"):
    val peers =
      List(p("kai-windows", "aaaaaaaa-1111-2222-3333-444444444444"), p("other", "bbbbbbbb-2222-2222-3333-444444444444"))
    RemoteExecutor.resolvePeer("aaaaaaaa", peers) match
      case Right(peer) => assertEquals(peer.deviceName, "kai-windows")
      case Left(err) => fail(s"deviceId prefix targeting broke: ${err.message}")

  test("zero-match error text unchanged (carries Available list; empty peers gets the config hint)"):
    val peers = List(p("kai-windows", "aaaaaaaa-1111-2222-3333-444444444444"))
    RemoteExecutor.resolvePeer("no-such-device", peers) match
      case Left(err) =>
        assert(err.message.contains("not found among 1 peer(s)"))
        assert(err.message.contains("Available: kai-windows"))
      case Right(_) => fail("zero match must stay an error")
    RemoteExecutor.resolvePeer("kai", List.empty) match
      case Left(err) => assert(err.message.contains("No peer devices discovered after scan"))
      case Right(_) => fail("empty peers must stay an error")

  test("case-insensitive exact match preserved"):
    val peers = List(p("KAI-Windows", "aaaaaaaa-1111-2222-3333-444444444444"))
    RemoteExecutor.resolvePeer("kai-windows", peers) match
      case Right(peer) => assertEquals(peer.deviceName, "KAI-Windows")
      case Left(err) => fail(s"case-insensitive exact match broke: ${err.message}")

end RemoteExecutorResolveSpec
