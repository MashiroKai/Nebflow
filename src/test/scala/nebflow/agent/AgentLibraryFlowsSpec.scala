package nebflow.agent

import cats.effect.IO
import munit.CatsEffectSuite

/**
 * 2026-08-15 FlowTrigger outage — global-agent parse gap.
 *
 * AgentLibrary had its own hand-written agent.json decoder (AgentJson) that
 * predated the `skills`/`flows` fields: global agents (Nebula included)
 * loaded through it lost both fields at the AgentDef layer, so NEITHER the
 * LLM schema NOR the executor ever saw FlowTrigger for them ("Nebula schema
 * 层就没有 FlowTrigger"). The decoder now parses both; these tests pin it.
 *
 * 🔴 重锚（2026-10-14 redfix 批，报告 #22）：builtin-def 批（2026-10-03，作者令①）
 * 后四个收敛名 **code-only**——`AgentLibrary.get` 对 `BuiltinAgents.isBuiltin(name)`
 * 直取代码定义，`loadFromDir` 对收敛名显式返回 None（磁盘目录对这四名是死信；
 * 代码侧 Nebula def 的 flows = Nil）。原「向临时 agents 目录写 Nebula agent.json
 * 期望被解析」的前提已被废除 ⇒ wildcard 解析例改用**非收敛名**承载；收敛名改钉
 * 代码侧 def（flows=Nil）+ 磁盘死信负控。
 */
class AgentLibraryFlowsSpec extends CatsEffectSuite:

  private def withAgentsDir(jsons: Map[String, String])(test: AgentLibrary => IO[Unit]): IO[Unit] =
    val tmp = os.temp.dir()
    IO.delay {
      jsons.foreach { (name, json) =>
        val dir = tmp / name
        os.makeDir.all(dir)
        os.write(dir / "agent.json", json)
      }
    }.bracket(_ => test(new AgentLibrary(tmp)))(_ => IO.delay(os.remove.all(tmp)).attempt.void)

  test("global agent.json flows + skills are parsed into AgentDef"):
    withAgentsDir(
      Map(
        "FlowCaller" -> """{"name":"FlowCaller","description":"x","tools":["Read"],"skills":["visual-style"],"flows":["code-review","release-beta"]}"""
      )
    ) { lib =>
      lib.get("FlowCaller").map {
        case Some(defn) =>
          assertEquals(defn.flows, List("code-review", "release-beta"))
          assertEquals(defn.skills, List("visual-style"))
        case None => fail("FlowCaller must load")
      }
    }

  test("wildcard flows [\"*\"] survive the parse (non-converged name carries the Nebula flows:['*'] case)"):
    // 🔴 重锚（报告 #22）：wildcard 解析例原钉在磁盘写 Nebula 上——builtin-def 批后
    // 收敛名是代码侧死信，解析面由非收敛名承载（FlowCaller 同构）。
    withAgentsDir(
      Map(
        "WildCaller" -> """{"name":"WildCaller","description":"x","tools":["*"],"flows":["*"]}"""
      )
    ) { lib =>
      lib.get("WildCaller").map {
        case Some(defn) =>
          assertEquals(defn.flows, List("*"), "the '*' wildcard must reach AgentDef verbatim")
        case None => fail("WildCaller must load")
      }
    }

  test("converged name Nebula resolves from code only: flows=Nil def + disk dir is a dead letter"):
    // 🔴 重锚（报告 #22）：收敛名改钉 BuiltinAgents 代码侧 def——即使磁盘上存在
    // Nebula/agent.json（flows:["*"]），get("Nebula") 也必须返回代码定义
    // （flows = Nil，defn.flows 不受磁盘影响），磁盘文件是死信。
    withAgentsDir(
      Map(
        "Nebula" -> """{"name":"Nebula","description":"root","tools":["*"],"flows":["*"]}"""
      )
    ) { lib =>
      lib.get("Nebula").map {
        case Some(defn) =>
          assertEquals(
            defn.flows,
            Nil,
            "converged names resolve from code: flows comes from BuiltinAgents (Nil), not the disk"
          )
          assertEquals(
            defn.skills,
            List("*"),
            "code-side Nebula def carries the wildcard skills face (BuiltinAgents.entry)"
          )
        case None => fail("Nebula must resolve from the code-side builtin def")
      }
    }

  test("agent.json without flows/skills fields defaults to Nil (back-compat)"):
    withAgentsDir(
      Map(
        "Legacy" -> """{"name":"Legacy","description":"old schema","tools":["Read"]}"""
      )
    ) { lib =>
      lib.get("Legacy").map {
        case Some(defn) =>
          assertEquals(defn.flows, Nil)
          assertEquals(defn.skills, Nil)
        case None => fail("Legacy must load")
      }
    }
end AgentLibraryFlowsSpec
