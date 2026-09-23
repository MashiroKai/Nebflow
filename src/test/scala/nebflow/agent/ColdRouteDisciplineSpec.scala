package nebflow.agent

import nebflow.agent.PromptSections.*
import nebflow.core.PathUtil

/**
 * 冷启动路由纪律批（2026-09-17）——order-370 根代理段的定向断言。
 *
 * 存在理由：提示词字面量（`AgentLibrary.Seeds.Nebula`）里作者六句 spec 的
 * ④⑤⑥ 零落地，冷启动实例以 10×Read 探地开场、0 次 Mail（诊断件
 * `.nebflow/reports/20260917_coldroute-diag.md`）。本 spec 只钉三条机制面
 * 不变量，不重复诊断件的行为层结论：
 *
 *   1. 存在性 —— 根身份（`isRootAgent = true`）装配结果含本段；
 *   2. 隔离性 —— 非根身份（项目分发器 / 节点会话）装配结果不含本段；
 *   3. 文本纪律 —— 纯静态、与 PromptContext 无关、零绝对路径、零 hostname。
 *
 * 复用既有套件的隔离手法（`PromptSectionsSpec.withIsolatedDataRoot` 同款）：
 * 文件版条件段（`<dataRoot>/prompts/sections/` 下的 .md 与子目录，同 order 可覆盖内建段）不得
 * 影响内建段的注入断言。
 */
class ColdRouteDisciplineSpec extends munit.FunSuite:

  private val sectionHeader = "## Routing discipline (root agent)"

  private def withIsolatedDataRoot[A](body: => A): A =
    val prevRoot = PathUtil.dataRoot
    val tempRoot = os.pwd / "target" / "test-coldroute-370-isolation"
    try
      os.remove.all(tempRoot)
      os.makeDir.all(tempRoot)
      PathUtil.setDataRoot(tempRoot)
      body
    finally
      PathUtil.setDataRoot(prevRoot)
      os.remove.all(tempRoot)

  // ============================================================
  // ① 存在性：根身份必达（装配链 = buildConditionalBlocks + assembleSystemPrompt）
  // ============================================================

  test("order-370 段在根代理身份下进入装配结果（isRootAgent = true）"):
    withIsolatedDataRoot {
      val ctx = PromptContext(isRootAgent = true, agentName = "Nebula")
      val blocks = buildConditionalBlocks(ctx)
      assert(blocks.contains(sectionHeader), s"根身份必须收到路由纪律段：$blocks")
      val assembled = assembleSystemPrompt("SYSTEM-MD", blocks)
      assert(assembled.contains(sectionHeader), "装配结果（system.md + 条件块）必须含本段")
      assert(
        assembled.indexOf("SYSTEM-MD") < assembled.indexOf(sectionHeader),
        "段在 system.md 之后——条件段契约"
      )
      // 段体逐字来自常量（非渲染拼接）
      assert(blocks.contains(rootRoutingDisciplineSection), "段体 = rootRoutingDisciplineSection 常量")
    }

  // ============================================================
  // ② 隔离性：非根身份（项目分发器 / 节点会话）零命中
  // ============================================================

  test("order-370 段对非根身份零注入（项目分发器 / 节点会话 / 团队 / SubTask）"):
    withIsolatedDataRoot {
      val nonRootContexts = List(
        PromptContext(isRootAgent = false, agentName = "project-dispatcher"),
        PromptContext(
          isRootAgent = false,
          agentName = "general",
          availableTools = Set(nebflow.core.tools.NodeReportToolDef.Name)
        ),
        PromptContext(isRootAgent = false, agentName = "Backend", agentCategory = "team"),
        PromptContext(isRootAgent = false, agentName = "Explorer", isSubTaskWorker = true)
      )
      nonRootContexts.foreach { ctx =>
        val assembled = assembleSystemPrompt("SYSTEM-MD", buildConditionalBlocks(ctx))
        assert(
          !assembled.contains(sectionHeader),
          s"非根身份不得收到路由纪律段（agentName=${ctx.agentName}）：$assembled"
        )
      }
    }

  test("order-370 段与 order-360 节点申报段互斥共存（条件维度不同，同上下文可各自命中）"):
    withIsolatedDataRoot {
      // 根身份 ⇒ 有 370；节点身份（带 node_report）⇒ 有 360。两者是不同条件
      // 维度，本用例防「新段把 360 挤掉」这一类回归。
      val nodeCtx = PromptContext(
        isRootAgent = false,
        availableTools = Set(nebflow.core.tools.NodeReportToolDef.Name)
      )
      val nodeBlocks = buildConditionalBlocks(nodeCtx)
      assert(nodeBlocks.contains("Node terminal report"), "order-360 段在节点身份下仍应注入")
      assert(!nodeBlocks.contains(sectionHeader), "order-370 段对节点身份仍不注入")

      val rootBlocks = buildConditionalBlocks(PromptContext(isRootAgent = true))
      assert(rootBlocks.contains(sectionHeader), "order-370 段对根身份注入")
    }

  // ============================================================
  // ③ 文本纪律：静态、零绝对路径、零 hostname、六条判据齐备
  // ============================================================

  test("order-370 段是静态体（render 与 content 相同，与 PromptContext 无关）"):
    withIsolatedDataRoot {
      val at370 = PromptSections.all.filter(_.order == 370)
      assertEquals(at370.size, 1, "注册表中恰一个 order-370 段")
      val sec = at370.head
      assertEquals(sec.content, rootRoutingDisciplineSection, "静态体 = 常量")
      assertEquals(sec.render(PromptContext()), rootRoutingDisciplineSection)
      assertEquals(
        sec.content,
        sec.render(PromptContext(isRootAgent = true, agentName = "Nebula", deviceInfo = "local (X)")),
        "任意上下文渲染结果相同 ⇒ 无动态字段"
      )
      assert(sec.shouldInclude(PromptContext(isRootAgent = true)))
      assert(!sec.shouldInclude(PromptContext(isRootAgent = false)))
    }

  test("order-370 段体零绝对路径形态、零 hostname（不得把本机标识写回请求面）"):
    withIsolatedDataRoot {
      val body = rootRoutingDisciplineSection
      // 绝对路径形态 = `/` 紧跟字母（"Read / Glob" 这类分隔符写法不命中）
      val slashThenLetter = """/[A-Za-z]""".r
      assert(
        slashThenLetter.findFirstIn(body).isEmpty,
        s"段体不得含绝对路径形态：${slashThenLetter.findFirstIn(body).getOrElse("")}"
      )
      // hostname 形态：本机 hostname / ComputerName 的任一非空片段都不得出现
      val hostNames = List(
        scala.util.Try(java.net.InetAddress.getLocalHost.getHostName).toOption,
        scala.util.Try(java.net.InetAddress.getLocalHost.getCanonicalHostName).toOption
      ).flatten.filter(_.nonEmpty).flatMap(_.split('.').toList).filter(_.length >= 4)
      hostNames.foreach { h =>
        assert(!body.contains(h), s"段体不得含本机 hostname 片段 '$h'")
      }
      // 数据根绝对路径（隔离实例下为实例 home）不得出现
      assert(!body.contains(PathUtil.dataRoot.toString), "段体不得含本机数据根路径")
      // 用户数据面零依赖：段体不含任何占位符/插值痕迹
      assert(!body.contains("${"), "段体不得含插值占位符")
      assert(!body.contains("{{"), "段体不得含模板占位符")
    }

  test("order-370 段体逐条覆盖六句 spec（①路由首位 / ②Mail 已有项目 / ③ProjectsCreate 兜底 / ④Read 用途 / ⑤缺信息交项目 / ⑥禁猜测路径）"):
    withIsolatedDataRoot {
      val body = rootRoutingDisciplineSection
      // ① 第一个工具调用 = 路由
      assert(body.contains("first tool call routes the task"), "① 首动作 = 路由 未落地")
      // ② 已有项目 ⇒ Mail(project:<name>)
      assert(
        body.contains("""Mail(address="project:<name>", message=<task>)"""),
        "② 已有项目 ⇒ Mail 未落地"
      )
      // ③ 没有 ⇒ ProjectCreate 再转发
      assert(body.contains("`ProjectCreate` and then that Mail"), "③ 无项目 ⇒ ProjectCreate 后 Mail 未落地")
      // ④ Read 的用途 = 读回项目返回结果
      assert(body.contains("`Read` reads back results only"), "④ Read 用途 未落地")
      // ⑤ 缺信息 ⇒ 派给项目让项目感知（根不亲自探文件系统）
      assert(body.contains("Missing information goes to the project"), "⑤ 缺信息出口 未落地")
      // ⑥ 禁由设备名/主机名推断路径 + 禁探测猜测路径
      assert(body.contains("never derive a username or home directory"), "⑥ 禁推断主机路径 未落地")
      assert(body.contains("No filesystem exploration"), "⑥ 禁探地 未落地")
      // 显式抵消 "Recon: Read only" 读法并声明本段优先（任务书点名缺口①）
      assert(body.contains("Recon: Read only"), "缺口①：未显式抵消字面量里的 Recon 读法")
      assert(body.contains("this section governs over that reading"), "缺口①：未声明本段优先")
      // 缺口③：工作区路径不由根侧猜
      assert(body.contains("Workspace paths are never guessed"), "缺口③：未禁自行推导工作区路径")
    }

end ColdRouteDisciplineSpec
