package nebflow.core.desktop

import cats.syntax.traverse.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

import nebflow.core.tools.{DesktopOpenTool, DesktopScreenshotTool, ToolContext}

/*
 * cubu-round2 · 宿主永拒回归基准（门控红线第 1 条：「对宿主操作 100% 被拒」先于任何写类动词合并）。
 *
 * 量化口径：宿主目标（引擎/sbt/java 及其大小写变体、带后缀变体）经动词 call 路径（身份闸之后）
 * 的每一次尝试，都必须得到 HOST-PROTECTED 且不发起系统调用（hostRefusal 在参数校验之前即返回）。
 * 另：list_apps 的真实输出里不得出现宿主进程名（剔除在执行体层）。
 * 已知过度拒绝（记录，不算失败）：名字里含宿主子串的普通应用（如 JavaScript Editor）同样被拒，fail-closed 设计。
 *
 * 纯 call 路径、无副作用：宿主目标在任何 osascript/screencapture/open 之前即被拒；list_apps 为只读。
 */
class HostProtectRegressionSpec extends CatsEffectSuite:

  override def munitIOTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(5, "min")

  private val ctx = ToolContext(projectRoot = "/tmp", sessionId = Some("host-regression-probe"))

  private val hostTargets: List[String] = List(
    "Stand",
    "stand",
    "STAND",
    "nebflow",
    "NebFlow Engine",
    "Nebula",
    "sbt",
    "SBT",
    "java",
    "Java",
    "openjdk 21",
    "Stand Helper"
  )

  test("宿主永拒（窗口截图路径）：全部宿主名变体 → HOST-PROTECTED，零例外") {
    hostTargets
      .traverse { app =>
        DesktopScreenshotTool
          .call(JsonObject("mode" -> "window".asJson, "app" -> app.asJson), ctx)
          .map(r => app -> r)
      }
      .map { results =>
        val leaked = results.collect { case (app, Right(out)) => s"$app => OK($out)" }
        assert(leaked.isEmpty, s"host targets were NOT refused: $leaked")
        val wrongCode = results.collect { case (app, Left(e)) if !e.message.contains("HOST-PROTECTED") => app }
        assert(wrongCode.isEmpty, s"refused without HOST-PROTECTED code: $wrongCode")
      }
  }

  test("宿主永拒（后台启动路径）：全部宿主名变体 → HOST-PROTECTED，零例外") {
    hostTargets
      .traverse(app => DesktopOpenTool.call(JsonObject("app" -> app.asJson), ctx).map(app -> _))
      .map { results =>
        val leaked = results.collect { case (app, Right(out)) => s"$app => OK($out)" }
        assert(leaked.isEmpty, s"host targets were NOT refused on open: $leaked")
      }
  }

  test("宿主剔除（清单路径）：list_apps 真实输出不含宿主进程名") {
    DesktopObserve.listApps.map {
      case Left(e) => fail(s"list_apps failed: $e")
      case Right(apps) =>
        val hostLeak = apps.filter(a => DesktopPolicy.isHostName(a.name)).map(_.name)
        assert(hostLeak.isEmpty, s"host names leaked into list: $hostLeak")
    }
  }

  test("过度拒绝（已知，记录）：名含宿主子串的普通应用同样被拒（fail-closed 设计）") {
    DesktopOpenTool.call(JsonObject("app" -> "JavaScript Editor".asJson), ctx).map { r =>
      assert(r.left.toOption.exists(_.message.contains("HOST-PROTECTED")), "over-refusal expected under fail-closed policy")
    }
  }

end HostProtectRegressionSpec
