package nebflow.core.delegate

import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.PathUtil

/**
 * DelegateRegistry 分配集字段（jev-plugins 批 #1226）：编码往返 ＋ **旧盘面文件
 * 向后兼容**（缺键 = None——deriveDecoder 缺键即败会让存量记录静默消失的禁令，
 * cliSessionId 批同款纪律）。
 */
class DelegateRegistryPluginsSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-registry-plugins"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "delegates")

  private def seed(id: String, json: String): Unit =
    val dir = tempRoot / "delegates"
    os.makeDir.all(dir)
    os.write.over(dir / s"$id.json", json, createFolders = true)

  test("plugins field round-trips through put/find (allocated set is queryable)") {
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = "delegate-nebflow-abc12345",
        executor = "nebflow",
        cwd = "/tmp/w",
        project = None,
        taskId = Some("12"),
        parentSessionId = "root",
        title = "t",
        createdAt = 1L,
        plugins = Some(List("backend-dev", "visual-report"))
      )
    )
    assertEquals(
      DelegateRegistry.find("delegate-nebflow-abc12345").flatMap(_.plugins),
      Some(List("backend-dev", "visual-report"))
    )
  }

  test("an explicit empty allocation (Some(Nil)) survives the round trip — 显式空 ≠ 未分配") {
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = "delegate-nebflow-empties1",
        executor = "nebflow",
        cwd = "/tmp/w",
        project = None,
        taskId = None,
        parentSessionId = "root",
        title = "t",
        createdAt = 2L,
        plugins = Some(Nil)
      )
    )
    assertEquals(DelegateRegistry.find("delegate-nebflow-empties1").flatMap(_.plugins), Some(Nil))
  }

  test("a pre-batch on-disk record without the plugins key decodes with plugins=None (never silently dropped)") {
    // 存量字节形态：无 plugins 键（也无 cliSessionId 之前的旧字段同样容忍）。
    seed(
      "delegate-nebflow-old0001",
      """{"id":"delegate-nebflow-old0001","executor":"nebflow","cwd":"/tmp/w","project":null,"taskId":"7","parentSessionId":"root","title":"legacy","createdAt":3}"""
    )
    val rec = DelegateRegistry.find("delegate-nebflow-old0001")
    assert(rec.isDefined, "the legacy record must decode")
    assertEquals(rec.flatMap(_.plugins), None)
  }

  test("the list projection carries the set (task-row face feeds from the record as-is)") {
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = "delegate-nebflow-list0001",
        executor = "nebflow",
        cwd = "/tmp/w",
        project = None,
        taskId = None,
        parentSessionId = "root",
        title = "t",
        createdAt = 4L,
        plugins = Some(List("p1"))
      )
    )
    val row = DelegateRegistry.list().find(_.id == "delegate-nebflow-list0001")
    assertEquals(row.flatMap(_.plugins), Some(List("p1")))
  }

end DelegateRegistryPluginsSpec
