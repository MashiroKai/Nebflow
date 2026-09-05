package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.PromptSections.*
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.project.ProjectStore
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * §E AGENTS.md 自动注入 + 双轨移除 spec（project-architecture phase2 §E，2026-09）。
 *
 * 覆盖（任务书 Spec 清单 ①②③④⑥；⑤ REST 只落根文件见
 * ProjectAgentFileRoutesSpec；⑦ 变异验红在收口报告记录原文）：
 *  ① order 895 注入且先于 rulesMd 900（renderer 序断言）
 *  ② gating 三态——project 分发器/node 注入（任意 agent 名，名単无关性由
 *     refreshTurn 端到端用例钉死）、Nebula 不注入、双轨 team/flow 不注入
 *  ③ >16KB 截断 + 尾注 [AGENTS.md truncated]
 *  ④ resolveAgentsMd 分支——根文件在 / 仅旧位（WARN+回落，禁止搬家）/
 *     并存 AGENTS.md 胜出 / 空·缺路径
 *  ⑥ ProjectStore.load 迁移三分支——仅旧位（真文件+symlink）/并存/旧位缺席
 *     + symlink→根文件已迁移稳态 no-op
 *
 * E.4（不设 ~/.nebflow/AGENTS.md 全局层）为零代码裁定，无 spec 断言面。
 */
class AgentsMdInjectionSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-agents-md-injection"

  // 全局 dataRoot 指向临时目录（同 ProjectAgentFileRoutesSpec 模式），类级设一次
  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  // ── ① order 895 注册 + renderer 序 ──────────────────────────────

  test("order 895 section registered, condition=agentsMd.isDefined, renders AGENTS.md header") {
    val sec = PromptSections.all.find(_.order == 895)
    assert(sec.isDefined, "order 895 AGENTS.md section missing from PromptSections.all")
    val ctxSome = PromptContext(agentsMd = Some("instr-1\ninstr-2"))
    val ctxNone = PromptContext.empty
    assert(sec.get.shouldInclude(ctxSome))
    assert(!sec.get.shouldInclude(ctxNone))
    assertEquals(
      sec.get.render(ctxSome),
      "# Project Instructions (AGENTS.md)\n\ninstr-1\ninstr-2"
    )
  }

  test("895 renders before 900 rulesMd in buildConditionalBlocks output") {
    val blocks = PromptSections.buildConditionalBlocks(
      PromptContext(agentsMd = Some("AGENTS-BODY"), rulesMd = Some("RULES-BODY"))
    )
    val at895 = blocks.indexOf("# Project Instructions (AGENTS.md)")
    val at900 = blocks.indexOf("## Project Rules")
    assert(at895 >= 0, "AGENTS.md section not rendered")
    assert(at900 >= 0, "rulesMd section not rendered")
    assert(at895 < at900, s"order wrong: AGENTS.md@$at895 must precede rulesMd@$at900")
    assert(blocks.contains("AGENTS-BODY") && blocks.contains("RULES-BODY"))
  }

  // ── ② gating 三态 ────────────────────────────────────────────────

  test("gate: node session (sandboxEnabled=true + projectRoot) -> inject") {
    assert(ContextRefresher.agentsMdEnabledFor(sandboxEnabled = true, projectRoot = Some("/ws/a")))
  }

  test("gate: Nebula shape (WS root fallback projectRoot, sandboxEnabled=false) -> no inject") {
    // Nebula 经 WebSocketRoutes 拿到 fallback projectRoot=~/.nebflow/projects（恒 Some）——
    // 排除它靠 sandboxEnabled=false（spawn 形态位），这正是 projectRoot.isDefined
    // 单基准被否决的原因。
    assert(!ContextRefresher.agentsMdEnabledFor(sandboxEnabled = false, projectRoot = Some("/x/.nebflow/projects")))
  }

  test("gate: dual-track team/flow shape (no projectRoot) -> no inject") {
    assert(!ContextRefresher.agentsMdEnabledFor(sandboxEnabled = false, projectRoot = None))
    assert(!ContextRefresher.agentsMdEnabledFor(sandboxEnabled = true, projectRoot = None))
    assert(!ContextRefresher.agentsMdEnabledFor(sandboxEnabled = true, projectRoot = Some("")))
  }

  test("gate: Nebula sandboxed root session (2026-09-05 sandbox batch) -> no inject") {
    // Nebula 会话沙箱启用后（第三置位点 WebSocketRoutes.doSpawnRootAgent），
    // sandboxEnabled=true ∧ projectRoot=Some(fallback ~/.nebflow/projects) 恒成立
    // ——若无 agentName 排除必误注入。AGENTS.md 接收面维持 project 分发器 +
    // node 会话，Nebula 根会话按名字排除。
    assert(
      !ContextRefresher.agentsMdEnabledFor(
        sandboxEnabled = true,
        projectRoot = Some("/x/.nebflow/projects"),
        agentName = "Nebula"
      )
    )
    // 默认参数 ""（既有两参调用形态）语义不变：非 Nebula 名照旧放行
    assert(
      ContextRefresher.agentsMdEnabledFor(
        sandboxEnabled = true,
        projectRoot = Some("/ws/a"),
        agentName = "project-dispatcher"
      )
    )
  }

  // ── ③ 长度护栏 ──────────────────────────────────────────────────

  private def wsWithAgentsMd(name: String, content: String): os.Path =
    val ws = tempRoot / name
    os.makeDir.all(ws)
    os.write.over(ws / "AGENTS.md", content)
    ws

  test("resolveAgentsMd: >16KB truncated with trailer; <=16KB full content") {
    val big = wsWithAgentsMd("ws-trunc-big", "a" * 20000)
    ContextRefresher.resolveAgentsMd(Some(big.toString)).map { got =>
      val text = got.getOrElse(fail("expected Some"))
      val trailer = "\n\n[AGENTS.md truncated]"
      assertEquals(text.length, ContextRefresher.AgentsMdMaxBytes + trailer.length)
      assertEquals(text.take(ContextRefresher.AgentsMdMaxBytes), "a" * ContextRefresher.AgentsMdMaxBytes)
      assert(text.endsWith(trailer))
    } *> {
      val small = wsWithAgentsMd("ws-trunc-small", "short body")
      ContextRefresher.resolveAgentsMd(Some(small.toString)).map { got =>
        assertEquals(got, Some("short body"))
      }
    }
  }

  test("resolveAgentsMd: multibyte UTF-8 truncation does not throw, keeps trailer") {
    val ws = wsWithAgentsMd("ws-trunc-cjk", "汉" * 10000) // 30000 bytes
    ContextRefresher.resolveAgentsMd(Some(ws.toString)).map { got =>
      val text = got.getOrElse(fail("expected Some"))
      assert(text.endsWith("[AGENTS.md truncated]"))
    }
  }

  // ── ④ resolveAgentsMd 分支 ──────────────────────────────────────

  test("resolveAgentsMd: root present -> root content") {
    val ws = wsWithAgentsMd("ws-res-root", "# root instructions\n")
    ContextRefresher.resolveAgentsMd(Some(ws.toString)).map { got =>
      assertEquals(got, Some("# root instructions"))
    }
  }

  test("resolveAgentsMd: only legacy present -> WARN+fallback, NO file move (migration lives in ProjectStore.load)") {
    val ws = tempRoot / "ws-res-legacy"
    os.makeDir.all(ws / ".nebflow")
    os.write.over(ws / ".nebflow" / "Agent.md", "# legacy only")
    ContextRefresher.resolveAgentsMd(Some(ws.toString)).map { got =>
      assertEquals(got, None, "root AGENTS.md absent -> None even with legacy present")
      assertEquals(os.read(ws / ".nebflow" / "Agent.md"), "# legacy only", "refreshTurn must not migrate")
      assert(!os.exists(ws / "AGENTS.md"), "refreshTurn must not create root file")
    }
  }

  test("resolveAgentsMd: both present -> AGENTS.md (root) wins, legacy untouched") {
    val ws = tempRoot / "ws-res-both"
    os.makeDir.all(ws / ".nebflow")
    os.write.over(ws / "AGENTS.md", "# root wins")
    os.write.over(ws / ".nebflow" / "Agent.md", "# legacy loses")
    ContextRefresher.resolveAgentsMd(Some(ws.toString)).map { got =>
      assertEquals(got, Some("# root wins"))
      assertEquals(os.read(ws / ".nebflow" / "Agent.md"), "# legacy loses")
    }
  }

  test("resolveAgentsMd: None / empty / missing dir -> None") {
    ContextRefresher.resolveAgentsMd(None).assertEquals(None) *>
      ContextRefresher.resolveAgentsMd(Some("")).assertEquals(None) *>
      ContextRefresher.resolveAgentsMd(Some((tempRoot / "no-such-ws").toString)).assertEquals(None) *>
      ContextRefresher.resolveAgentsMd(Some(tempRoot.toString)).assertEquals(None) // dataRoot 无 AGENTS.md
  }

  // ── ② 端到端：refreshTurn 接线（gating 真被消费 + 任意 agent 名可注入）──

  private def mkResources(agentsDir: os.Path): SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = cats.effect.Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(agentsDir),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tempRoot / "agents-md-spec-archives"),
      fileLockManager = FileLockManager.create.unsafeRunSync(),
      sessionModelOverrides = cats.effect.Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = cats.effect.Ref.unsafe[IO, Boolean](false),
      friendService = None
    )

  private def nodeShapedState(ws: os.Path, sandbox: Boolean): AgentState =
    AgentState(
      sessionId = None, // node/分发器会话形态：无 folderId、无 team 注册
      folderId = None,
      projectRoot = Some(ws.toString),
      sandboxEnabled = sandbox
    )

  test("refreshTurn e2e: node-shaped session with ARBITRARY agent name injects AGENTS.md") {
    val ws = wsWithAgentsMd("ws-e2e-node", "# e2e project instructions")
    val res = mkResources(tempRoot / "e2e-agents-lib")
    // 故意用非 converged 名（节点 agent = NodeDef.agent 任意声明值）——
    // 钉死「全部 node 会话」不依赖 agent 名单
    val defn = AgentDef(name = "swift-dev", description = "arbitrary node agent")
    ContextRefresher.refreshTurn(nodeShapedState(ws, sandbox = true), res, defn).map { tc =>
      assertEquals(tc.agentsMd, Some("# e2e project instructions"))
    }
  }

  test("refreshTurn e2e: Nebula/dual-track shape (sandboxEnabled=false) not injected") {
    val ws = wsWithAgentsMd("ws-e2e-nebula", "# should not appear")
    val res = mkResources(tempRoot / "e2e-agents-lib")
    val defn = AgentDef(name = "Nebula", description = "orchestrator")
    ContextRefresher.refreshTurn(nodeShapedState(ws, sandbox = false), res, defn).map { tc =>
      assertEquals(tc.agentsMd, None)
    }
  }

  // ── ⑥ ProjectStore.load 迁移三分支 ─────────────────────────────

  private def mkProject(name: String, ws: os.Path): IO[Unit] =
    ProjectStore.create(name, ws.toString, None, s"# $name template\n").map {
      case Right(_)  => ()
      case Left(err) => fail(s"create failed: $err")
    }

  test("load migration ①a: legacy real file only -> content moved to root, legacy removed") {
    val ws = tempRoot / "ws-mig-real"
    os.makeDir.all(ws / ".nebflow")
    mkProject("am-mig-real", ws) *> IO(os.remove(ws / "AGENTS.md")) *> IO {
      os.write.over(ws / ".nebflow" / "Agent.md", "# legacy chinese instructions\n")
    } *> ProjectStore.load("am-mig-real").flatMap { pdOpt =>
      assert(pdOpt.isDefined)
      IO {
        assertEquals(os.read(ws / "AGENTS.md"), "# legacy chinese instructions\n")
        assertEquals(os.exists(ws / ".nebflow" / "Agent.md"), false)
      }
    }
  }

  test("load migration ①b: legacy symlink (target != root) -> root gets target content, link removed, TARGET UNTOUCHED") {
    val ws = tempRoot / "ws-mig-link"
    os.makeDir.all(ws / ".nebflow" / "docs")
    mkProject("am-mig-link", ws) *> IO(os.remove(ws / "AGENTS.md")) *> IO {
      os.write.over(ws / ".nebflow" / "docs" / "real.md", "# real target content\n")
      java.nio.file.Files.createSymbolicLink(
        (ws / ".nebflow" / "Agent.md").toNIO,
        java.nio.file.Paths.get("docs/real.md")
      )
    } *> ProjectStore.load("am-mig-link").flatMap { pdOpt =>
      assert(pdOpt.isDefined)
      IO {
        assertEquals(os.read(ws / "AGENTS.md"), "# real target content\n")
        assertEquals(os.exists(ws / ".nebflow" / "Agent.md"), false, "symlink itself removed")
        assertEquals(
          os.read(ws / ".nebflow" / "docs" / "real.md"),
          "# real target content\n",
          "symlink target must be untouched"
        )
      }
    }
  }

  test("load migration ②: both exist -> AGENTS.md wins, legacy left untouched") {
    val ws = tempRoot / "ws-mig-both"
    os.makeDir.all(ws / ".nebflow")
    mkProject("am-mig-both", ws) *> IO {
      os.write.over(ws / "AGENTS.md", "# canonical root\n")
      os.write.over(ws / ".nebflow" / "Agent.md", "# legacy loser\n")
    } *> ProjectStore.load("am-mig-both").flatMap { pdOpt =>
      assert(pdOpt.isDefined)
      IO {
        assertEquals(os.read(ws / "AGENTS.md"), "# canonical root\n")
        assertEquals(os.read(ws / ".nebflow" / "Agent.md"), "# legacy loser\n")
      }
    }
  }

  test("load migration ② steady state: legacy symlink -> ../AGENTS.md is a no-op (no link removal, no warning path)") {
    val ws = tempRoot / "ws-mig-steady"
    os.makeDir.all(ws / ".nebflow")
    mkProject("am-mig-steady", ws) *> IO {
      os.write.over(ws / "AGENTS.md", "# migrated root\n")
      java.nio.file.Files.createSymbolicLink(
        (ws / ".nebflow" / "Agent.md").toNIO,
        java.nio.file.Paths.get("../AGENTS.md")
      )
    } *> ProjectStore.load("am-mig-steady").flatMap { pdOpt =>
      assert(pdOpt.isDefined)
      IO {
        assertEquals(os.isLink(ws / ".nebflow" / "Agent.md"), true, "steady-state link preserved")
        assertEquals(os.read(ws / "AGENTS.md"), "# migrated root\n")
      }
    }
  }

  test("load migration ③: no legacy -> no-op (root untouched)") {
    val ws = tempRoot / "ws-mig-none"
    os.makeDir.all(ws)
    mkProject("am-mig-none", ws) *> ProjectStore.load("am-mig-none").flatMap { pdOpt =>
      assert(pdOpt.isDefined)
      IO(assertEquals(os.read(ws / "AGENTS.md"), s"# am-mig-none template\n"))
    }
  }

end AgentsMdInjectionSpec
