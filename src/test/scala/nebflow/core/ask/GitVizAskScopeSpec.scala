package nebflow.core.ask

import cats.effect.IO
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.core.AskHandoffPort
import nebflow.core.tools.{GitVizGraphTool, ToolContext}

import java.nio.file.Files

/* ═══════════════════════════════════════════════════════════════════════════
 * 分支管理问答批（gitviz，2026-11-19 第二轮）· 硬约束红面
 *
 * 作者令（逐字）：「这个是专门设计的分支管理问答 agent，它也是像 canvas ask 一样只
 * 问答，做事就转发给主会话」+「这个 agent 没有任何 git 写能力 —— 数据面沿用只读子
 * 命令白名单」+「两个入口收敛到同一个出口（不许两条各写一套）」。
 *
 * 本 spec 把四条**能机器判的**硬约束钉死（其余靠编译与闸）：
 *   G1 工具面：`GitVizAllowedTools` 里没有 Bash、没有写工具；唯一数据面 = gitviz_graph。
 *   G2 会话身份：`ask-gv-` 落在 `ask-` 族内（既有拦截面/回执帧照旧命中），且 id 形状闸
 *      只放行 8..16 hex。
 *   G3 绑定域：kind=gitviz 的绑定跨重启复现（persist → 新 registry 载入后仍是 gitviz），
 *      存量 index.json（无 kind 字段）读回来仍是 file（不破坏旧语义）。
 *   G4 交办出口：同一份 `AskHandoffPort.Request`（面板多选 / 问答对话两条入口产出的同一个
 *      结构）在 wire 面上给出 git 四件；`git=None` 时机器可读形**不含** git 键（文件 ask
 *      逐字不变）。
 * ═══════════════════════════════════════════════════════════════════════════ */
final class GitVizAskScopeSpec extends CatsEffectSuite:

  // ── G1 工具面 ────────────────────────────────────────────────────────────
  test("G1 gitviz 工具面没有 Bash / 没有写工具，数据面只有 gitviz_graph") {
    val face = AskSessions.GitVizAllowedTools
    assert(face.contains("gitviz_graph"), "数据面必须挂着 gitviz_graph")
    assert(!face.contains("Bash"), "不得有 Bash —— 它是 git 写操作的唯一入口")
    assert(!face.contains("Write"), "不得有 Write")
    assert(!face.contains("Edit"), "不得有 Edit")
    assert(!face.contains("ask_edit"), "不得有 ask_edit（零落盘）")
    assert(!face.contains("ask_write"), "不得有 ask_write（零落盘）")
    assert(!face.contains("send_message_to_main_chat"), "交办只走结构化帧，不走自由文本腿")
    // 与文件 ask 面相比：严格更窄（真子集），不是「另开一面同样大」
    assert(face.subsetOf(AskSessions.AllowedTools), s"gitviz 面必须是文件 ask 面的子集，实际差集=${face -- AskSessions.AllowedTools}")
    // 名字本身是权限边界：进 AskExclusiveTools ⇒ 其余身份一律拿不到
    assert(AskSessions.AskExclusiveTools.contains("gitviz_graph"))
  }

  test("G1b gitviz_graph 对非 gitviz 会话 fail-closed（工具内侧身份闸）") {
    val root = Files.createTempDirectory("gitviz-gate").toFile
    val ctx = ToolContext(
      projectRoot = root.getAbsolutePath,
      sessionId = Some("ask-0123abcd"), // 文件 ask 会话（不是 ask-gv-）
      agentDef = None,
      wsSend = None
    )
    GitVizGraphTool.call(io.circe.JsonObject.empty, ctx).map { res =>
      assert(res.isLeft, "文件 ask 会话不得使用 gitviz_graph")
      assert(res.left.exists(_.message.contains("GITVIZ_FORBIDDEN")), s"错误码必须可读: $res")
    }
  }

  // ── G2 会话身份 ──────────────────────────────────────────────────────────
  test("G2 ask-gv- 落在 ask- 族内，且 id 形状闸只放行 8..16 hex") {
    val sid = AskSessions.gitVizSessionIdFor("stand")
    assert(sid.startsWith("ask-gv-"), sid)
    assert(AskSessions.isAskSession(sid), "必须同时命中既有 ask 判据（拦截面/回执帧靠它）")
    assert(AskSessions.isGitVizSession(sid))
    assert(AskSessions.isGitVizSessionIdShape(sid), s"后端派生的 id 必须自身合法: $sid")
    assert(!AskSessions.isGitVizSessionIdShape("ask-gv-XYZ"), "非 hex 必须拒")
    assert(!AskSessions.isGitVizSessionIdShape("ask-gv-abc"), "短于 8 必须拒")
    assert(!AskSessions.isGitVizSession("ask-0123abcd"), "文件 ask 会话不得被判成 gitviz")
  }

  // ── G3 绑定域跨重启复现 ──────────────────────────────────────────────────
  test("G3 kind=gitviz 的绑定落盘后重载仍是 gitviz；存量无 kind 字段读回 file") {
    val dataRoot = os.Path(Files.createTempDirectory("gitviz-bind").toFile)
    val repo = os.Path(Files.createTempDirectory("gitviz-repo").toFile)
    val sid = AskSessions.gitVizSessionIdFor("stand")
    val reg = new AskSessionRegistry(dataRoot)
    for
      b <- reg.bind(repo.toString, "stand", explicitId = sid, kind = AskBinding.KindGitViz)
      _ = assert(b.id == sid, s"显式 id 必须被采用: ${b.id}")
      _ = assert(b.isGitViz)
      // 重启面：新 registry 从同一 dataRoot 载入
      reloaded <- new AskSessionRegistry(dataRoot).byId(sid)
      _ = assert(reloaded.exists(_.isGitViz), "跨重启必须仍是 gitviz 域")
      _ = assert(reloaded.exists(_.fileName == "stand"))
      // 存量兼容：手工写一条无 kind 的旧记录
      legacy = dataRoot / "ask" / "index.json"
      _ <- IO.blocking {
        os.write.over(
          legacy,
          """{"bindings":[{"id":"ask-deadbeef","file":"/tmp/a.md","fileName":"a.md","createdAt":1,"mainSessionId":""}]}""",
          createFolders = true
        )
      }
      old <- new AskSessionRegistry(dataRoot).byId("ask-deadbeef")
      _ = assert(old.exists(!_.isGitViz), "无 kind 字段的存量记录必须读回 file 域")
    yield ()
    end for
  }

  // ── G4 交办出口 ──────────────────────────────────────────────────────────
  private def gitRequest(source: String) = AskHandoffPort.GitRequest(
    project = "stand",
    branches = List("feat/a", "feat/b"),
    target = "feat/base",
    intent = "merge",
    notes = List("feat/a 先预演 merge-tree"),
    source = source
  )

  test("G4 两个入口（panel / ask）产出同一份结构化交办：wire 面含分支清单·目标线·意图·理由") {
    for source <- List("panel", "ask") do
      val req = AskHandoffPort.Request(
        askSessionId = "ask-gv-1234abcd",
        file = "/repo",
        fileName = "stand",
        question = s"把 feat/a、feat/b 合进 feat/base（$source）",
        leg = "ask-dock",
        task = "把 feat/a、feat/b 合进 feat/base",
        paths = List("feat/base", "feat/a", "feat/b"),
        reason = "面板/问答 agent 只读，写操作唯一执行者 = 主会话",
        expected = "feat/base 上出现这些分支的合并结果（按落支规则）",
        git = Some(gitRequest(source))
      )
      val h = AskHandoffWire.of(req)
      val j = AskHandoffWire.json(h)
      val g = j.hcursor.downField("git")
      assertEquals(g.downField("project").as[String].toOption, Some("stand"))
      assertEquals(g.downField("branches").as[List[String]].toOption, Some(List("feat/a", "feat/b")))
      assertEquals(g.downField("target").as[String].toOption, Some("feat/base"))
      assertEquals(g.downField("intent").as[String].toOption, Some("merge"))
      assertEquals(g.downField("notes").as[List[String]].toOption, Some(List("feat/a 先预演 merge-tree")))
      assertEquals(g.downField("source").as[String].toOption, Some(source), "入口来源必须可分辨，但结构同一份")
      // 面板帧（askHandoffAck）与注入 metadata 同一份 json
      val ack = AskHandoffWire.ackFrame("ask-gv-1234abcd", ok = true, "", h)
      assertEquals(
        ack.hcursor.downField("handoff").downField("git").focus,
        Some(AskHandoffWire.gitJson(gitRequest(source)))
      )
      // 人读面：摘要与注入正文都要说清「哪几支 → 哪条线」
      val summary = AskHandoffWire.summary(h)
      assert(summary.contains("feat/a") && summary.contains("feat/base"), summary)
      val body = AskHandoffWire.injectedBody(h, "ask:ask-gv-1234abcd")
      assert(body.contains("【git 交办件】"), body)
      assert(body.contains("分支清单：feat/a、feat/b"), body)
      assert(body.contains("目标线：feat/base"), body)
      assert(body.contains("意图：merge"), body)
      assert(body.contains("写操作唯一执行者 = 本会话"), "注入面必须声明这是请求不是执行")
    end for
  }

  test("G4b 非 gitviz 交办（git=None）机器可读形不含 git 键 —— 文件 ask 逐字不变") {
    val req = AskHandoffPort.Request(
      askSessionId = "ask-1234abcd",
      file = "/tmp/a.md",
      fileName = "a.md",
      question = "解释这一段",
      leg = "ask-dock",
      task = "解释这一段"
    )
    val j = AskHandoffWire.json(AskHandoffWire.of(req))
    assertEquals(j.hcursor.downField("git").focus, None)
    val body = AskHandoffWire.injectedBody(AskHandoffWire.of(req), "ask:ask-1234abcd")
    assert(!body.contains("【git 交办件】"))
  }
end GitVizAskScopeSpec
