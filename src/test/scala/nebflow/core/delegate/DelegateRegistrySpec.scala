package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.EncoderOps
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * unified-delegate 批（2026-10-03）验收：持久地址簿的落盘/读取/列表、id 安全面、
 * 续聊地址行形态。
 *
 * 中心性质：地址簿是 `delegate:<id>` 唯一的持久数据源——find 对缺失/坏形/越权 id
 * 一律 None（绝不抛），list 对坏文件跳过（绝不炸），跨重启（重新读盘）可见。
 */
class DelegateRegistrySpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-delegate-reg-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private def rec(id: String, createdAt: Long = 1L) =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = "/tmp/ws",
      project = None,
      taskId = Some("12"),
      parentSessionId = "root-1",
      title = "t",
      createdAt = createdAt
    )

  test("put/find round-trips through disk; a fresh read (simulated restart) still sees it") {
    withTempDataRoot { tmp =>
      IO {
        DelegateRegistry.put(rec("delegate-kernel-abc12345"))
        // 模拟重启：新读（内部无缓存——单点仍是盘）
        val hit = DelegateRegistry.find("delegate-kernel-abc12345")
        assert(hit.isDefined)
        assertEquals(hit.get.cwd, "/tmp/ws")
        assertEquals(hit.get.taskId, Some("12"))
      }
    }.unsafeRunSync()
  }

  test("find: missing / malformed / path-traversal ids are None, never throw") {
    withTempDataRoot { tmp =>
      IO {
        assertEquals(DelegateRegistry.find("no-such-id"), None)
        assertEquals(DelegateRegistry.find(""), None)
        assertEquals(DelegateRegistry.find("../escape"), None)
        assertEquals(DelegateRegistry.find("a/b"), None)
      }
    }.unsafeRunSync()
  }

  test("find: a corrupted record file degrades to None") {
    withTempDataRoot { tmp =>
      IO {
        os.makeDir.all(tmp / "delegates")
        os.write(tmp / "delegates" / "delegate-kernel-bad.json", "not-json{")
        assertEquals(DelegateRegistry.find("delegate-kernel-bad"), None)
        // list 跳过坏文件不炸
        DelegateRegistry.put(rec("delegate-kernel-good", createdAt = 5L))
        assertEquals(DelegateRegistry.list().map(_.id), List("delegate-kernel-good"))
      }
    }.unsafeRunSync()
  }

  test("mergeDiffStat does not attribute the base branch's latest merge to an unmerged delegate") {
    val repo = os.temp.dir(prefix = "nb-merge-diff-stat-")
    def git(args: String*): String =
      os.proc("git", "-C", repo.toString, args).call().out.trim()
    try
      os.proc("git", "init", "-b", "main", repo.toString).call()
      git("config", "user.name", "Diff Stat Spec")
      git("config", "user.email", "diff-stat-spec@example.invalid")
      os.write.over(repo / "base.txt", "base\n")
      git("add", "base.txt")
      git("commit", "-m", "base")
      git("checkout", "-b", "other-batch")
      os.write.over(repo / "other.txt", (1 to 45).map(i => s"line $i").mkString("\n") + "\n")
      git("add", "other.txt")
      git("commit", "-m", "other batch")
      git("checkout", "main")
      git("merge", "--no-ff", "other-batch", "-m", "other batch merge")

      val rec = DelegateRegistry.Record(
        id = "not-yet-merged",
        executor = "nebflow",
        cwd = repo.toString,
        project = None,
        taskId = None,
        parentSessionId = "root",
        title = "unmerged batch",
        createdAt = 1L,
        baseBranch = Some("main"),
        projectWorkspace = Some(repo.toString),
        reportDetail = Some("work completed without a merge sha")
      )
      assertEquals(DelegateWorkReport.mergeDiffStat(rec), None)
    finally os.remove.all(repo)
  }

  test("aggregateDiffStat counts duplicate merge OIDs once and preserves each child detail") {
    val first = DelegateWorkReport.MergeDiffStat("abc1234", 10, 4, "a" * 40, List(DelegateWorkReport.FileDiffStat("same.scala", 10, 4)))
    val duplicate = first.copy(commit = "abc1234")
    val children = List(
      DelegateWorkReport.ChildDiffStat("child-a", "root", "/a", None, Some(first), partial = false),
      DelegateWorkReport.ChildDiffStat("child-b", "root", "/b", None, Some(duplicate), partial = false)
    )
    val aggregate = DelegateWorkReport.aggregateChildren(children)
    assertEquals((aggregate.added, aggregate.removed), (10L, 4L))
    assertEquals(aggregate.children, children)
    assert(!aggregate.partial)
  }

  test("aggregateDiffStat counts same file once across distinct merge commits") {
    val a = DelegateWorkReport.MergeDiffStat("aaa1111", 8, 2, "a" * 40, List(DelegateWorkReport.FileDiffStat("shared.scala", 8, 2)))
    val b = DelegateWorkReport.MergeDiffStat("bbb2222", 3, 7, "b" * 40, List(DelegateWorkReport.FileDiffStat("shared.scala", 3, 7)))
    val childA = DelegateWorkReport.ChildDiffStat("writer-a", "root", "/a", None, Some(a), partial = false)
    val childB = DelegateWorkReport.ChildDiffStat("writer-b", "root", "/b", None, Some(b), partial = false)
    val aggregate = DelegateWorkReport.aggregateChildren(List(childA, childB))
    // Deterministic overlap policy selects largest total line-change contribution.
    assertEquals((aggregate.added, aggregate.removed), (8L, 2L))
    assertEquals(aggregate.children.map(_.merge), List(Some(a), Some(b)))
  }

  test("aggregateDiffStat sums disjoint files, resolves ties deterministically, and retains opaque legacy totals") {
    val a = DelegateWorkReport.MergeDiffStat("aaa1111", 10, 0, "a" * 40, List(
      DelegateWorkReport.FileDiffStat("shared.scala", 5, 0),
      DelegateWorkReport.FileDiffStat("only-a.scala", 5, 0)
    ))
    val b = DelegateWorkReport.MergeDiffStat("bbb2222", 10, 0, "b" * 40, List(
      DelegateWorkReport.FileDiffStat("shared.scala", 5, 0),
      DelegateWorkReport.FileDiffStat("only-b.scala", 5, 0)
    ))
    val opaque = DelegateWorkReport.MergeDiffStat("ccc3333", 2, 3, "c" * 40)
    val rows = List(a, b, opaque).map(s => DelegateWorkReport.ChildDiffStat(s.commit, "root", "/ws", None, Some(s), partial = false))
    val aggregate = DelegateWorkReport.aggregateChildren(rows)
    // Tie on shared.scala uses the lexicographically smaller OID; distinct paths add.
    assertEquals((aggregate.added, aggregate.removed), (17L, 3L))
    assert(aggregate.partial)
    assertEquals(aggregate.children, rows)
  }

  test("aggregateDiffStat follows nested delegate ownership and marks missing diff data partial") {
    withTempDataRoot { tmp =>
      IO {
        DelegateRegistry.put(rec("root"))
        DelegateRegistry.put(rec("child").copy(parentSessionId = "root", cwd = "/tmp/child"))
        DelegateRegistry.put(rec("grandchild").copy(parentSessionId = "child", cwd = "/tmp/grandchild"))
        DelegateRegistry.put(rec("independent").copy(parentSessionId = "elsewhere"))
        val aggregate = DelegateWorkReport.aggregateDiffStat(DelegateRegistry.find("root").get)
        assertEquals(aggregate.children.map(_.delegateId), List("child", "grandchild"))
        assertEquals(aggregate.children.map(_.workspace), List("/tmp/child", "/tmp/grandchild"))
        assert(aggregate.partial)
        assertEquals((aggregate.added, aggregate.removed), (0L, 0L))
      }
    }.unsafeRunSync()
  }

  test("aggregateDiffStat includes DAG flow nodes and nested descendants as partial when stats are unavailable") {
    withTempDataRoot { _ =>
      IO {
        DelegateRegistry.put(rec("root"))
        FlowNodeOwnership.register("dag-root-node", "root")
        FlowNodeOwnership.register("dag-nested-node", "dag-root-node")
        DelegateRegistry.put(rec("delegate-under-node").copy(parentSessionId = "dag-nested-node"))

        val aggregate = DelegateWorkReport.aggregateDiffStat(DelegateRegistry.find("root").get)
        assertEquals(aggregate.children.map(_.delegateId).toSet, Set("dag-root-node", "dag-nested-node", "delegate-under-node"))
        assertEquals(aggregate.children.map(_.parentSessionId).toSet, Set("root", "dag-root-node", "dag-nested-node"))
        assert(aggregate.children.forall(_.partial))
        assert(aggregate.partial)
        assertEquals((aggregate.added, aggregate.removed), (0L, 0L))
      }
    }.unsafeRunSync()
  }

  test("aggregateDiffStat surfaces crashed DAG nodes from their durable ownership edge") {
    withTempDataRoot { tmp =>
      IO {
        DelegateRegistry.put(rec("root"))
        FlowNodeOwnership.register("dag-crashed", "root")
        // No agent-registry entry, report, or delegate record remains after a crash.
        val aggregate = DelegateWorkReport.aggregateDiffStat(DelegateRegistry.find("root").get)
        assertEquals(aggregate.children.map(_.delegateId), List("dag-crashed"))
        assert(aggregate.children.head.partial)
        assert(aggregate.partial)
      }
    }.unsafeRunSync()
  }

  test("list: newest first") {
    withTempDataRoot { tmp =>
      IO {
        DelegateRegistry.put(rec("d-old", createdAt = 10L))
        DelegateRegistry.put(rec("d-new", createdAt = 20L))
        assertEquals(DelegateRegistry.list().map(_.id), List("d-new", "d-old"))
      }
    }.unsafeRunSync()
  }

  test("continuationLine: delegate: form only — the retired kernel: face must not resurface") {
    val line = DelegateRegistry.continuationLine("delegate-kernel-abc12345")
    assert(line.contains("\"delegate:delegate-kernel-abc12345\""), line)
    assert(line.contains("Mail"), line)
    assert(!line.contains("kernel:delegate-kernel"), line)
  }

  // ── 面板命名批（2026-10-31 · 作者令②）：批次短名 name 字段的落盘/向后兼容 ──

  test("name: put/find round-trips the batch short name through disk") {
    withTempDataRoot { tmp =>
      IO {
        DelegateRegistry.put(rec("d-named").copy(name = Some("液体玻璃批")))
        val hit = DelegateRegistry.find("d-named")
        assertEquals(hit.flatMap(_.name), Some("液体玻璃批"))
        // Encoder 派生 ⇒ JSON 含 name 键（REST 行 rec.asJson 透传的数据面）
        val json = hit.get.asJson
        assertEquals(json.hcursor.get[String]("name"), Right("液体玻璃批"))
      }
    }.unsafeRunSync()
  }

  test("name: legacy record JSON without the key decodes as None (zero migration)") {
    import io.circe.parser.decode
    withTempDataRoot { tmp =>
      IO {
        // 旧盘面形：无 name 键（手工 Decoder 缺键容忍——deriveDecoder 会让存量
        // 记录静默消失，禁）。
        val legacy = """{"id":"d-legacy","executor":"nebflow","cwd":"/tmp/ws","project":null,"taskId":null,"parentSessionId":"root-1","title":"t","createdAt":1}"""
        val decoded = decode[DelegateRegistry.Record](legacy)
        assertEquals(decoded.map(_.name), Right(None))
        assertEquals(decoded.map(_.id), Right("d-legacy"))
      }
    }.unsafeRunSync()
  }

  test("name: absent by default (old call sites construct records without it)") {
    val r = rec("d-plain")
    assertEquals(r.name, None)
    assertEquals(r.asJson.hcursor.get[Option[String]]("name"), Right(None))
  }
end DelegateRegistrySpec
