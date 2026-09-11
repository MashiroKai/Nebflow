package nebflow.agent

import cats.effect.unsafe.implicits.global

/**
 * agentdef-tidy 批（2026-09-11）验红探针 —— **raw stdout，零断言**。
 *
 * 用法（同一命令跑两遍 = 改前 / 改后）：
 * {{{
 *   sbt -batch "Test/runMain nebflow.agent.AgentDefCategoryBackdoorProbe"
 * }}}
 *
 * 构造：隔离 scratch home `/tmp/agentdef-tidy-probe/home` 内的 agents 副本——
 * 4 个收敛名 keeper（AgentCore.ConvergedAgentNames）+ 2 个非收敛对照，全部声明
 * `"category":"team"`（后门形态）。**真实 `~/.nebflow/agents` 零触碰**：探针只在
 * 临时目录读写，退出前 `os.remove.all` 自清。
 *
 * 读数面（每条 = 引擎真实代码路径，非复述）：
 *   - `AgentLibrary.loadAll` → `loadFromDir`（唯一 JSON category 读取点）
 *   - `AgentCore.fixedToolsFor`（唯一固定工具注入点）
 *   - `PromptSections.all` order-395 section 的 `shouldInclude` + `render`
 *     （= buildConditionalBlocks 的同款 filter+render 两段，team 成员身份段真实产物）
 *
 * 末行 `VERDICT=` 为二值总结：RED = 后门可达（任一收敛名落在 team 面）；
 * GREEN = 后门堵死且非收敛对照逐字节 parity。
 */
object AgentDefCategoryBackdoorProbe:

  private val Home = os.Path("/tmp/agentdef-tidy-probe/home")

  /** 收敛名 → 机制固定集（**只引 AgentCore 常量**，零裸清单/零裸数字）。 */
  private val ExpectedFixed: Map[String, Set[String]] = Map(
    "Nebula" -> AgentCore.NebulaOrchestrationTools,
    "project-dispatcher" -> AgentCore.DispatcherFixedTools,
    "general" -> AgentCore.GeneralFixedTools,
    "kernel" -> AgentCore.KernelFixedTools
  )

  /** team 面遗留件（legacyFixedTools 的 category=team 分支产物）。 */
  private val TeamFace = Set("Mail", "SubTask", "TeamTaskCreate", "TeamTaskUpdate", "TeamTaskList")

  private val NonConvergedControls = List("LegacyTeamThing", "LegacyFlowThing")

  def main(args: Array[String]): Unit =
    os.remove.all(Home)
    val agentsDir = Home / "agents"

    val declared = (ExpectedFixed.keySet.toList.sorted ++ NonConvergedControls).map { n =>
      n -> "team"
    }.toMap

    declared.foreach { (name, cat) =>
      val dir = agentsDir / name
      os.makeDir.all(dir)
      os.write.over(
        dir / "agent.json",
        s"""{"name":"$name","description":"probe fixture","category":"$cat","tools":[]}"""
      )
    }

    val lib = new AgentLibrary(agentsDir, None)
    val loaded = lib.loadAll().unsafeRunSync()

    println("PROBE scratch-home            = " + Home.toString)
    println("PROBE declared JSON category  = \"team\" for all " + declared.size + " fixtures")
    println("PROBE ConvergedAgentNames     = " + AgentCore.ConvergedAgentNames.toList.sorted.mkString("[", ", ", "]"))
    println("PROBE identity section order  = " + PromptSections.all.find(_.order == 395).map(_.order).toString)
    println("")

    var red = false

    declared.toList.sortBy(_._1).foreach { (name, _) =>
      val defn = loaded(name)
      val fixed = AgentCore.fixedToolsFor(defn)
      // order-395 段=身份段载体：条件 `guardrailsOn && !isSubTaskWorker`（PromptSections.scala:281），
      // 渲染体内层分支 `agentCategory == "team"`（:284）。guardrailsOn=true 是
      // 该段真实的在飞形态（guardrails 开启的节点会话）；此处按 buildConditionalBlocks
      // 同款 filter + render 两段取值。
      val ctx = PromptSections.PromptContext(
        agentCategory = defn.category,
        agentName = defn.name,
        guardrailsOn = true
      )
      val identityRendered = PromptSections.all
        .filter(_.order == 395)
        .map(s => if s.shouldInclude(ctx) then s.render(ctx) else "")
        .mkString
      val identityOn = identityRendered.nonEmpty

      println(s"-- $name")
      println(s"   AgentDef.category            = ${defn.category}")
      println(s"   AgentDef.tools               = ${defn.tools}")
      println(s"   fixedToolsFor                = ${fixed.toList.sorted.mkString("[", ", ", "]")}")
      println(s"   fixedToolsFor.size           = ${fixed.size}")
      println(s"   team-face tools present      = ${TeamFace.intersect(fixed).toList.sorted.mkString("[", ", ", "]")}")
      println(s"   PromptContext.agentCategory  = ${ctx.agentCategory}")
      println(s"   identity段渲染 (order 395)   = $identityOn")
      if identityOn then
        println(s"   identity段首行               = ${identityRendered.linesIterator.next()}")

      ExpectedFixed.get(name) match
        case Some(mech) =>
          val ok = defn.category == "standalone" && fixed == mech && !identityOn
          if !ok then red = true
          println(s"   [converged] expected         = category=standalone, fixedToolsFor==机制常量(${mech.size} 件), identity段空")
          println(s"   [converged] verdict          = ${if ok then "OK" else "BACKDOOR REACHABLE"}")
        case None =>
          // 非收敛对照：category=team 必须原样落 legacyFixedTools（parity）
          val ok = defn.category == "team" &&
            fixed == AgentCore.BaseTools ++ Set("Mail", "SubTask") ++ AgentCore.TeamTaskTools &&
            identityOn
          if !ok then red = true
          println(s"   [control]   expected         = category=team, legacyFixedTools(team), identity段非空")
          println(s"   [control]   verdict          = ${if ok then "OK (parity)" else "PARITY BROKEN"}")
      println("")
    }

    println("PROBE NebulaOrchestrationToolsExpectedSize = " + AgentCore.NebulaOrchestrationToolsExpectedSize)
    println("PROBE VERDICT = " + (if red then "RED (category 后门可达)" else "GREEN (收敛名无视 JSON category，非收敛 parity 保持)"))

    os.remove.all(Home)
  end main

end AgentDefCategoryBackdoorProbe
