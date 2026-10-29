package nebflow.agent

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * 补投静默批（作者 2026-10-29 11:05 console 报障）——回执补投的**用户可见面**规格。
 *
 * 症状：HeldDeliveryWatchdog 催注补投（`AgentCommand.DrainHeldReceipts`）时曾发
 * 「[投递补注] …」机械卡（source=system / eventType=force-drain → 注入气泡 header
 * 逐字渲染「SYSTEM · NEBULA · FORCE-DRAIN」），把引擎内部机制名暴露给作者，且逐字
 * 复述接收时刻已见过的回执。作者令：补投通知**静默并入正常消息流**（回执本体在
 * 接收时刻已走正常 delegate/subtask 气泡；补投对作者面零新增），内部机制名不得
 * 出现在任何用户可见面。
 *
 * 本件把作者口径钉成回归闸（同 [[nebflow.neblink.DeviceFlowRetiredSpec]] 源扫描
 * 模式，断言只对准**代码面**——注释里的历史性记事允许存在）：
 *
 *  - N1 注入气泡发射面（emitInjectedUserEvent 的全部宿主文件）代码面不得再现
 *    机械卡的用户可见字面量：卡体（`[投递补注]` / `屏障槽位已归还`）与
 *    机制 eventType 实参（`Some("force-drain")`）。
 *  - N2 heldReceipts 徽标词条：held 过程态键已退役（title/unread 在位），
 *    值面不带机制词（屏障 / barrier / force-drain）。
 *  - P 正面钉（防误删活面）：催注补投链路本体（log 留痕、LLM reminder、徽标帧、
 *    重试策略、title/unread 词条键）必须仍在——本批只摘「可见卡」，不拆投递
 *    可靠性机制；held 键退役随 #1050 HEAD 口径（合并收尾批 2026-10-29 调整，
 *    seal 同 [[nebflow.gateway.UserFaceProcessStateSealSpec]]）。
 *
 * 范围外（有意不闸）：`held-receipts-force-drain` lifecycle 日志事件名（操作者
 * 诊断面，stand.log，非作者聊天面）；`.ui.json` 存量历史行（历史留档不回写）。
 */
class HeldReceiptNoticeFaceSpec extends FunSuite:

  private def repoRoot: Path =
    val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath
    // sbt 以模块基目录为 cwd；向上找含 build.sbt 的根（子模块安全）。
    Iterator
      .iterate(cwd)(_.getParent)
      .take(6)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(cwd)

  private def read(rel: String): String =
    val p = repoRoot.resolve(rel)
    if !Files.exists(p) then fail(s"规格找不到活面文件: $p（文件被移动时请同步本清单）")
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  /** 剥注释（块注释与行注释，JS/Scala 通用形态）——断言只对准**代码/值面**。 */
  private def stripComments(src: String): String =
    val sb = new StringBuilder(src.length)
    var i = 0
    var inBlock = false
    var inLine = false
    while i < src.length do
      val rest = src.substring(i)
      if inBlock then
        if rest.startsWith("*/") then { inBlock = false; i += 2; sb.append(' ') }
        else { i += 1 }
      else if inLine then
        if src.charAt(i) == '\n' then { inLine = false; sb.append('\n'); i += 1 }
        else i += 1
      else if rest.startsWith("/*") then { inBlock = true; i += 2; sb.append(' ') }
      else if rest.startsWith("//") then { inLine = true; i += 2 }
      else { sb.append(src.charAt(i)); i += 1 }
    sb.toString
  end stripComments

  private def codeOf(rel: String): String = stripComments(read(rel))

  /** N1：注入气泡发射宿主（emitInjectedUserEvent 调用面全集）。 */
  private val emitFaceFiles = List(
    "src/main/scala/nebflow/agent/AgentActor.scala",
    "src/main/scala/nebflow/agent/AgentIdle.scala",
    "src/main/scala/nebflow/agent/AgentProcessing.scala",
    "src/main/scala/nebflow/agent/AgentFinishTurn.scala",
    "src/main/scala/nebflow/agent/AgentFrozen.scala"
  )

  /** 用户可见的机械卡字面量（卡体 + 机制 eventType 实参形态）。 */
  private val cardLiterals = List(
    "[投递补注]",
    "屏障槽位已归还",
    "Some(\"force-drain\")"
  )

  test("N1 注入气泡发射面：机械卡字面量零命中（补投通知无内部 token）"):
    emitFaceFiles.foreach { rel =>
      val code = codeOf(rel)
      cardLiterals.foreach { lit =>
        assert(!code.contains(lit), s"$rel 代码面含机械卡字面量「$lit」 — 补投可见卡复活了？")
      }
    }

  test("N2 heldReceipts 徽标词条：held 过程态键已退役（title/unread 在位）+ 值面零机制词（屏障/barrier/force-drain）"):
    for rel <- List(
        "src/main/resources/web/js/locales/zh-CN.js",
        "src/main/resources/web/js/locales/en.js"
      )
    do
      val valueLines = codeOf(rel).linesIterator.filter(_.contains("'heldReceipts.")).toList
      assert(valueLines.exists(_.contains("'heldReceipts.title'")), s"$rel 的 heldReceipts.title 词条丢失")
      assert(valueLines.exists(_.contains("'heldReceipts.unread'")), s"$rel 的 heldReceipts.unread 词条丢失")
      assert(
        !valueLines.exists(_.contains("'heldReceipts.held'")),
        s"$rel 的 heldReceipts.held 键应已退役（过程态零用户面，seal: UserFaceProcessStateSealSpec）"
      )
      valueLines.foreach { line =>
        val lower = line.toLowerCase
        assert(!line.contains("屏障"), s"$rel 词条值面含机制词「屏障」: $line")
        assert(!lower.contains("barrier"), s"$rel 词条值面含机制词 barrier: $line")
        assert(!lower.contains("force-drain"), s"$rel 词条值面含机制词 force-drain: $line")
      }

  test("P1 催注补投链路仍在：DrainHeldReceipts 处理器 + LLM reminder + 日志留痕"):
    val idle = codeOf("src/main/scala/nebflow/agent/AgentIdle.scala")
    assert(idle.contains("case AgentCommand.DrainHeldReceipts =>"), "催注补投处理器不得拆除")
    assert(idle.contains("buildEventReminder(held)"), "补投的 LLM reminder 合批腿不得拆除")
    assert(idle.contains("held-receipts-force-drain"), "投递事实的 lifecycle 日志留痕不得拆除")

  test("P2 投递可靠性机制面仍在：watchdog / 台账 / 重试策略 / 徽标帧"):
    assert(
      codeOf("src/main/scala/nebflow/core/processor/HeldDeliveryWatchdog.scala").contains(
        "val FrameType: String = \"heldReceipts\""
      ),
      "徽标帧发射面不得拆除"
    )
    // HeldDeliveryPolicy 与台账同件（HeldReceiptStore.scala 的伴生 object）。
    val storeCode = codeOf("src/main/scala/nebflow/core/processor/HeldReceiptStore.scala")
    assert(storeCode.contains("object HeldDeliveryPolicy"), "催注重试策略面不得拆除")
    assert(storeCode.contains("HeldDeliveryFirstRetryMs"), "催注首催间隔定义不得拆除")
    assert(Files.exists(repoRoot.resolve("src/main/scala/nebflow/core/processor/HeldReceiptStore.scala")), "回执台账不得拆除")

  test("P3 徽标词条正面钉：held 过程态键已退役（作者可见面不复活）"):
    assert(
      !codeOf("src/main/resources/web/js/locales/zh-CN.js").contains("'heldReceipts.held'"),
      "zh 的 heldReceipts.held 键应保持退役（过程态零用户面），不得复活"
    )
    assert(
      !codeOf("src/main/resources/web/js/locales/en.js").contains("'heldReceipts.held'"),
      "en 的 heldReceipts.held 键应保持退役（过程态零用户面），不得复活"
    )

end HeldReceiptNoticeFaceSpec
