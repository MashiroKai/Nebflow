package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * N1 刀1 · FlowMapStore 作用域切换（设计 v3 §6/A1 + v3-⑧）store 层验收：
 *
 *  - S1 作用域落盘：openScope 建 `flow-maps/<scope>/flow-map.json`（store-owned 首写）；
 *    per-project 旧面（open）路径族逐字节不动（两族并存互不重叠）。
 *  - S2 作用域往返：mutate → snapshot → 重开恢复（write-through 落盘/恢复往返）；
 *    chain-ledger.json 随迁进作用域目录（A1「chain-ledger.json 同切」）。
 *  - S3 一 delegate 一图·隔离：同一工作区两个作用域互不可见（隔离证据）。
 *  - S4 v3-⑧ 归档不迁移：存量 per-project flow-map.json 在 delegate 作用域挂载 +
 *    sweep 根集枚举之后**逐字节原样**（零读取零改写零迁移——旧挂载面停用前只读留档，
 *    delegate 链自新作用域起）。
 *  - S5 作用域 id fail-closed：非法 id（路径穿越/空/超长/越界字符）拒开显式报错，
 *    零静默改名（存储层字符集契约单点 [[FlowMapStore.validScopeId]]）。
 */
class FlowMapScopeStoreSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def freshWorkspace(): String =
    os.temp.dir(prefix = "nb-fmscope-", deleteOnExit = false).toString

  private val now = System.currentTimeMillis()

  private def node(id: String, name: String, status: String = NodeLifecycle.Wiring): NodeDef =
    NodeDef(id = id, name = name, agent = "Backend", status = status, createdAt = now)

  // ── S1 作用域落盘 + 旧面零变化 ───────────────────────────────────────

  test("S1: openScope creates flow-maps/<scope>/flow-map.json; legacy open path family unchanged") {
    val ws = freshWorkspace()
    for
      scoped <- FlowMapStore.openScope("delegate-test-agent-aa11", "proj", ws)
      _ <- scoped.mutate(s => s.copy(nodes = s.nodes ++ Map("n1" -> node("n1", "scan"))))
      legacy <- FlowMapStore.open("proj", ws)
      legacySnap <- legacy.snapshot
      scopeSnap <- scoped.snapshot
      nebflowFiles <- IO.blocking(os.list(os.Path(ws) / ".nebflow").map(_.last).toList)
      scopeFiles <- IO.blocking(
        os.list(os.Path(ws) / ".nebflow" / "flow-maps" / "delegate-test-agent-aa11").map(_.last).toList
      )
    yield
      assertEquals(scopeSnap.nodes.size, 1, "scoped store sees its own node")
      assertEquals(legacySnap.nodes.size, 0, "legacy per-project store is a disjoint family (zero overlap)")
      assert(
        nebflowFiles.contains("flow-map.json") && nebflowFiles.contains("flow-maps"),
        s"legacy flow-map.json stays at the old path; flow-maps/ is the new family root, got: $nebflowFiles"
      )
      assert(
        scopeFiles.contains("flow-map.json"),
        s"scope dir owns its flow-map.json, got: $scopeFiles"
      )
      assert(
        !scopeFiles.contains("flow-maps"),
        "no nested flow-maps/ inside the scope dir (self-contained family)"
      )
    end for
  }

  // ── S2 作用域往返 + chain-ledger 随迁 ─────────────────────────────────

  test("S2: scoped write → reopen restores (recovery roundtrip); chain-ledger.json follows into the scope dir") {
    val ws = freshWorkspace()
    val scope = "delegate-test-agent-bb22"
    for
      s1 <- FlowMapStore.openScope(scope, "proj", ws)
      _ <- s1.mutate { st =>
        st.copy(nodes = st.nodes ++ Map(
          "a" -> node("a", "up", NodeLifecycle.Completed).copy(result = Some("up done"), ttlExpireAt = Some(now + 100000)),
          "b" -> node("b", "down", NodeLifecycle.Pending).copy(in = List("a"))
        ))
      }
      _ <- s1.reconcileChainLedger(System.currentTimeMillis())
      s2 <- FlowMapStore.openScope(scope, "proj", ws)
      snap <- s2.snapshot
      scopeDir = os.Path(ws) / ".nebflow" / "flow-maps" / scope
      ledgerThere <- IO.blocking(os.exists(scopeDir / "chain-ledger.json"))
      raw <- IO.blocking(os.read(scopeDir / "flow-map.json"))
      parsed <- IO.fromEither(jsonParse(raw).flatMap(_.as[FlowMapState]))
    yield
      assertEquals(snap.nodes("a").result, Some("up done"), "result survives the scoped roundtrip (write-through)")
      assertEquals(snap.nodes("b").in, List("a"))
      assertEquals(parsed.nodes.size, 2, "disk JSON carries the scoped graph")
      assert(ledgerThere, "chain-ledger.json must follow the scope (A1 同切)")
    end for
  }

  // ── S3 一 delegate 一图·隔离 ─────────────────────────────────────────

  test("S3: two delegate scopes in one workspace are fully isolated (per-delegate mount evidence)") {
    val ws = freshWorkspace()
    for
      a <- FlowMapStore.openScope("delegate-test-agent-cc33", "proj", ws)
      b <- FlowMapStore.openScope("delegate-test-agent-dd44", "proj", ws)
      _ <- a.mutate(s => s.copy(nodes = s.nodes ++ Map("only-a" -> node("only-a", "a-node"))))
      snapA <- a.snapshot
      snapB <- b.snapshot
      dirA <- IO.blocking(os.list(os.Path(ws) / ".nebflow" / "flow-maps").map(_.last).toList)
      rawB <- IO.blocking(os.read(os.Path(ws) / ".nebflow" / "flow-maps" / "delegate-test-agent-dd44" / "flow-map.json"))
      parsedB <- IO.fromEither(jsonParse(rawB).flatMap(_.as[FlowMapState]))
    yield
      assertEquals(snapA.nodes.size, 1)
      assertEquals(snapB.nodes.size, 0, "scope B must not see scope A's node (isolation)")
      assertEquals(parsedB.nodes.size, 0, "B's disk file carries zero A nodes (disk-level isolation)")
      assertEquals(
        dirA.sorted,
        List("delegate-test-agent-cc33", "delegate-test-agent-dd44"),
        "one dir per delegate (one delegate one map)"
      )
    end for
  }

  // ── S4 v3-⑧ 存量归档不迁移 ───────────────────────────────────────────

  test("S4: legacy per-project flow-map.json stays byte-identical across scope mount + sweep enumeration (no migration)") {
    val ws = freshWorkspace()
    // 存量面：由**旧面 store 自己**写一份含节点的 per-project flow-map.json（真存量形态）。
    for
      legacy <- FlowMapStore.open("oldproj", ws)
      _ <- legacy.mutate(s => s.copy(nodes = s.nodes ++ Map("old-1" -> node("old-1", "legacy-node"))))
      before <- IO.blocking(os.read(os.Path(ws) / ".nebflow" / "flow-map.json"))
      beforeArchive <- IO.blocking {
        val p = os.Path(ws) / ".nebflow" / "flow-map-archive.json"
        if os.exists(p) then Some(os.read(p)) else None
      }
      // delegate 作用域面全套动作：挂载 + 目录枚举 + sweep 根集（本 test 只到 store/枚举层；
      // sweep 全链在 DelegateFlowHostsSpec T9）。
      _ <- FlowMapStore.openScope("delegate-test-agent-ee55", "newproj", ws)
      _ <- DelegateFlowHosts.scopeDirs(ws)
      _ <- DelegateFlowHosts.sweepRoots
      after <- IO.blocking(os.read(os.Path(ws) / ".nebflow" / "flow-map.json"))
      afterArchive <- IO.blocking {
        val p = os.Path(ws) / ".nebflow" / "flow-map-archive.json"
        if os.exists(p) then Some(os.read(p)) else None
      }
      nebflowFiles <- IO.blocking(os.list(os.Path(ws) / ".nebflow").map(_.last).toList)
    yield
      assertEquals(after, before, "legacy active-zone file byte-identical (零读取零改写)")
      assertEquals(afterArchive, beforeArchive, "legacy archive file untouched (零迁移)")
      assert(nebflowFiles.contains("flow-map.json"), "legacy file still at its old path (只读留档)")
      assert(
        !nebflowFiles.exists(f => f.contains("migrat")),
        "no migration artifact at the .nebflow root"
      )
    end for
  }

  // ── S5 作用域 id fail-closed ─────────────────────────────────────────

  test("S5: invalid scope ids are refused fail-closed (no silent rename, no dir side effects)") {
    val ws = freshWorkspace()
    val bad = List("", "../escape", "a/b", "sp ace", "x" * 129, "中文scope")
    for
      _ <- bad.traverse_(id => FlowMapStore.openScope(id, "proj", ws).attempt.void)
      leftovers <- IO.blocking {
        val root = os.Path(ws) / ".nebflow" / "flow-maps"
        if os.exists(root) then os.list(root).toList else Nil
      }
      escaped <- IO.blocking(os.exists(os.Path(ws) / ".nebflow" / "escape"))
    yield
      assertEquals(leftovers, Nil, s"no directories created for invalid ids, got: $leftovers")
      assert(!escaped, "path traversal must not escape the flow-maps root")
      assert(FlowMapStore.validScopeId("delegate-general-9f8e7d6c"), "well-formed delegate session ids are valid scopes")
    end for
  }

end FlowMapScopeStoreSpec
