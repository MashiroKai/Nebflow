package nebflow.core.tools

import cats.effect.IO
import munit.CatsEffectSuite

/**
 * bash overhaul T4 红相（作者令 2026-11-04「先安排重启，然后组织优化 bash，
 * 用业界成熟做法完整实现，一步到位」）：节点完成门腿 1（BgGateCompletionHold）
 * 缺省**必须为 true**。
 *
 * 事实源：`docs/20261104_110500_our-bash-tool-defects.md` 缺陷 D2 ——
 * `Defaults.scala:546-547` 现为
 * `sys.props.getOrElse("nebflow.bgGate.completionHold", "false")`（缺省 false = 封存），
 * 与工具描述承诺（`BashTool.scala:52`「Project 节点会等所有等待型后台任务结束
 * 再交付结果」）**相反**：agent「结束回合等通知」= 节点立刻终态化，完成通知投给
 * 已被收殓的会话（现场 A `stand.log:117135` `bg-notify-undelivered`）。
 *
 * 本 spec 钉的是**缺省面**：旧缺省 false ⇒ 红；显式 `-Dnebflow.bgGate.completionHold=false`
 * 仍 false（逃生门保留）、`=true` 仍 true（显式覆写保留）。消费点
 * `NodeStarter.scala:962` / `NodeEngine.bgGateHoldEnabled`（`Defaults.BgGateCompletionHold`
 * 单点读）随缺省翻转自动生效——本 spec 只钉取值单点，不重复钉消费面。
 *
 * 每个 test 用 `guarantee` 清 system property——本 suite 与其他 suite 串行共享
 * JVM（build.sbt `Test / parallelExecution := false`），全局 prop 跨 suite 污染
 * 是既有纪律红线（BashForegroundBudgetSpec withProps 同款）。
 */
class BashOverhaulGateSpec extends CatsEffectSuite:

  private val Prop = "nebflow.bgGate.completionHold"

  /** Set the prop for the duration of `io`, always cleared after. */
  private def withProp[A](v: String)(io: IO[A]): IO[A] =
    IO(System.setProperty(Prop, v)).flatMap(_ => io.guarantee(IO(System.clearProperty(Prop))))

  test("T4: default face — BgGateCompletionHold must default to TRUE (old default false ⇒ RED)") {
    IO {
      // 唯一事实源：Defaults 单点读（NodeStarter / NodeEngine 都经它取值）。
      // 旧代码缺省 "false" ⇒ 本断言红（这就是 D2 的机械证据）。
      assert(
        nebflow.shared.Defaults.BgGateCompletionHold == true,
        s"completion-hold gate must default ON (the tool description promises the hold), got ${nebflow.shared.Defaults.BgGateCompletionHold}"
      )
    }
  }

  test("T4: explicit -Dnebflow.bgGate.completionHold=false keeps it false (opt-out preserved)") {
    withProp("false") {
      IO {
        assert(
          nebflow.shared.Defaults.BgGateCompletionHold == false,
          "an explicit false must disable the hold (escape hatch stays)"
        )
      }
    }
  }

  test("T4: explicit -Dnebflow.bgGate.completionHold=true keeps it true (opt-in identical)") {
    withProp("true") {
      IO {
        assert(
          nebflow.shared.Defaults.BgGateCompletionHold == true,
          "an explicit true must keep the hold"
        )
      }
    }
  }

end BashOverhaulGateSpec
