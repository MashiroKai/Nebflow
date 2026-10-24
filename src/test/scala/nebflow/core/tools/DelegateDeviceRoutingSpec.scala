package nebflow.core.tools

import munit.CatsEffectSuite
import nebflow.core.tools.DelegateTool.WorkspaceResolution
import nebflow.shared.PathUtil

import java.nio.file.Files

/**
 * 派生路由（v2.1 阶段 1 §A.2/§A.3，G.12）的解析面承重断言——delegate **无**
 * device 参数，`project.origin` 即派生事实：
 *   - `project` 缺省 = Local 默认 general 座位（存量逐字节）；
 *   - 未挂载且无注册表定义 = `DELEGATE_PROJECT_UNMOUNTED` 显式错误（存量逐字）；
 *   - **remote-origin 定义（未挂载是预期形态）= 路由判定**——host 无设备网络时
 *     `DELEGATE_DEVICE_OFFLINE` 显式失败：既不是 UNMOUNTED、也**绝不静默落
 *     host**（G.12 路径错觉防护的解析端锚）。
 *
 * 执行模型纪律：副作用 `unsafeRunSync` 落定后走纯断言（munit-cats-effect 只跑
 * 测试体返回值——体中段落 assertIO 静默丢弃，见 FileTransferActionListSpec 注）。
 */
class DelegateDeviceRoutingSpec extends CatsEffectSuite:

  private var savedRoot: os.Path = null
  private var tmp: os.Path = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    savedRoot = PathUtil.dataRoot
    tmp = os.Path(Files.createTempDirectory("nb-dd-route"))
    PathUtil.setDataRoot(tmp)

  override def afterEach(context: munit.AfterEach): Unit =
    PathUtil.setDataRoot(savedRoot)
    os.remove.all(tmp)

  private val ctx = ToolContext(projectRoot = "")

  test("project omitted = Local default general workspace (existing semantics)") {
    val r = DelegateTool.resolveWorkspace(None, ctx).unsafeRunSync()
    r match
      case Right(WorkspaceResolution.Local(ws, p)) =>
        assertEquals(p, None)
        assert(ws.endsWith("/general"))
      case other => fail(s"expected Local resolution, got: $other")
    assertEquals(os.exists(PathUtil.dataRoot / "general"), true)
  }

  test("unmounted project with no registry definition = DELEGATE_PROJECT_UNMOUNTED (verbatim)") {
    val r = DelegateTool.resolveWorkspace(Some("no-such-project"), ctx).unsafeRunSync()
    assert(r.fold(_.message, _ => "").contains("DELEGATE_PROJECT_UNMOUNTED"))
  }

  test(
    "remote-origin definition (unmounted = expected) routes to the origin device; no device network = DELEGATE_DEVICE_OFFLINE, never a silent local seat"
  ) {
    val ws = "/remote-machines/kaiyu/repo"
    val defJson = s"""{
      |"name": "cross-dev",
      |"workspace": "$ws",
      |"agentFile": "$ws/AGENTS.md",
      |"createdAt": 1,
      |"origin": {"device": "dev-remote-9", "path": "$ws"}
      |}""".stripMargin
    val pj = nebflow.core.project.ProjectStore.projectJsonPath("cross-dev")
    os.makeDir.all(pj / os.up)
    os.write.over(pj, defJson)

    val r = DelegateTool.resolveWorkspace(Some("cross-dev"), ctx).unsafeRunSync()
    val msg = r.fold(_.message, _ => "")
    // 两个离线分支（NebLink 未配置 / 名册无此设备）文案不同，但同为显式
    // DELEGATE_DEVICE_OFFLINE + 指名设备（fail-closed——绝非 UNMOUNTED/静默本地）。
    assert(msg.contains("DELEGATE_DEVICE_OFFLINE"))
    assert(msg.contains("dev-remote-9"))
    assert(!msg.contains("DELEGATE_PROJECT_UNMOUNTED"))
  }

  test("self-literal origin on a definition = local semantics path (not routed)") {
    // 绑 self 的定义但未挂载：走「本机定义但未挂载」分支 = UNMOUNTED（不是 OFFLINE）。
    val ws = tmp / "local-proj"
    os.makeDir.all(ws)
    val defJson = s"""{
      |"name": "self-proj",
      |"workspace": "${ws.toString}",
      |"agentFile": "${(ws / "AGENTS.md").toString}",
      |"createdAt": 1,
      |"origin": {"device": "self", "path": "${ws.toString}"}
      |}""".stripMargin
    val pj = nebflow.core.project.ProjectStore.projectJsonPath("self-proj")
    os.makeDir.all(pj / os.up)
    os.write.over(pj, defJson)
    val r = DelegateTool.resolveWorkspace(Some("self-proj"), ctx).unsafeRunSync()
    assert(r.fold(_.message, _ => "").contains("DELEGATE_PROJECT_UNMOUNTED"))
  }

end DelegateDeviceRoutingSpec
