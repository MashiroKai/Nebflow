package nebflow.agent

import munit.FunSuite

import scala.io.Source
import scala.util.matching.Regex

/**
 * bluebubble 批（2026-09-12）：注入来源标注的**单点定义 / 两侧同源**契约门。
 *
 * 背景：作者 2026-09-12 19:42 报「任务分发器会话看不到蓝气泡」。修法把注入
 * 来源字段的定名权收口到后端
 * [[InjectionAttribution]]（`nebflow.agent` 包顶层），前端只做**呈现**：
 *   - 判据：`web/js/persistence.js#isOutgoingInjection`（sender == 本会话 agent 名）
 *   - 呈现：`web/js/chat.js#INJECTED_SOURCE_LABELS` / `#injectedSourceLabel`
 * 后端是唯一定名源 ⇒ 后端每加一个 source 取值，前端**必须显式登记**（表项
 * 或 `injectedSourceLabel` 里的显式分支），**禁靠首字母大写兜底**——兜底对
 * 多词源名（`background` → 兜底恰好对，但语义是偶然）、大小写变体不设防，
 * 且新源名会静默以错误标签展示。
 *
 * 本 spec 是纯文本契约门（不起服务、不读运行时状态）：改后端集合而忘改前端
 * ⇒ 此处红。
 */
class InjectionSourceContractSpec extends FunSuite:

  private val repoRoot = os.pwd
  private val chatJsPath = repoRoot / "src" / "main" / "resources" / "web" / "js" / "chat.js"
  private val persistenceJsPath =
    repoRoot / "src" / "main" / "resources" / "web" / "js" / "persistence.js"

  private def read(path: os.Path): String =
    // 真实前端源码缺失 = 环境问题（spec 必须跑在仓根），显式失败而非静默跳过。
    assert(os.exists(path), s"前端源码不存在：$path（本 spec 必须在仓根运行）")
    val src = Source.fromFile(path.toIO, "UTF-8")
    try src.mkString
    finally src.close()

  private lazy val chatJs: String = read(chatJsPath)

  /** `const INJECTED_SOURCE_LABELS = { ... };` 的表体。 */
  private lazy val sourceLabelTableBody: String =
    val start = chatJs.indexOf("const INJECTED_SOURCE_LABELS")
    assert(start >= 0, "chat.js 里找不到 INJECTED_SOURCE_LABELS —— 前端登记面被搬迁/改名？")
    val open = chatJs.indexOf('{', start)
    val close = chatJs.indexOf('}', open)
    assert(open >= 0 && close > open, "INJECTED_SOURCE_LABELS 表体解析失败（不是对象字面量？）")
    chatJs.substring(open + 1, close)

  /** 表内显式登记的 source key（`name: 'Label'`）。 */
  private lazy val registeredSourceKeys: Set[String] =
    val keyRe: Regex = """(?m)([A-Za-z_][A-Za-z0-9_]*)\s*:\s*'[^']*'""".r
    keyRe.findAllMatchIn(sourceLabelTableBody).map(_.group(1)).toSet

  /** `injectedSourceLabel` 函数体里显式分支处理的 source 字面量
    * （`source === 'node'` 形态：表现格式与通用模板不同，故不走表）。 */
  private lazy val explicitBranchSources: Set[String] =
    val fnStart = chatJs.indexOf("export function injectedSourceLabel")
    assert(fnStart >= 0, "chat.js 里找不到 injectedSourceLabel —— 前端呈现入口被搬迁/改名？")
    val fnEnd = chatJs.indexOf("\nexport ", fnStart + 1) match
      case -1    => chatJs.length
      case other => other
    val body = chatJs.substring(fnStart, fnEnd)
    val branchRe: Regex = """source\s*===\s*'([A-Za-z_][A-Za-z0-9_]*)'""".r
    branchRe.findAllMatchIn(body).map(_.group(1)).toSet

  /** 前端登记面覆盖的 source（表 ∪ 显式分支）。 */
  private lazy val frontendRegistered: Set[String] =
    registeredSourceKeys ++ explicitBranchSources

  test("① 后端自定名集合被 pin —— 新增/删除 source 必须显式改本 spec（提醒同步前端登记面）"):
    assertEquals(
      InjectionAttribution.BackendNamedSources,
      Set(
        "mail",
        "task",
        "dispatch",
        "system",
        "node",
        "skill",
        "delegate",
        "subtask",
        "flow",
        "tool",
        "background"
      ),
      "BackendNamedSources 变更 ⇒ 同步检查 web/js/chat.js#INJECTED_SOURCE_LABELS 并更新本 pin"
    )
    assert(
      InjectionAttribution.BackendNamedSources.contains(InjectionAttribution.SourceSystem),
      s"SourceSystem='${InjectionAttribution.SourceSystem}' 必须在 BackendNamedSources 内（否则前端登记门覆盖不到它）"
    )

  test("② 前端登记面 ⊇ 后端自定名集合（禁首字母大写兜底）"):
    val missing = InjectionAttribution.BackendNamedSources.diff(frontendRegistered)
    assert(
      missing.isEmpty,
      s"后端自定的 source 未在前端显式登记（表项或 injectedSourceLabel 显式分支）：${missing.toList.sorted.mkString(", ")}" +
        s"｜已登记：${frontendRegistered.toList.sorted.mkString(", ")}"
    )

  test("③ 登记表标签非空且非兜底形态（每个 key 映射到显式字符串标签）"):
    val labelRe: Regex = """(?m)([A-Za-z_][A-Za-z0-9_]*)\s*:\s*'([^']*)'""".r
    val labels = labelRe
      .findAllMatchIn(sourceLabelTableBody)
      .map(m => m.group(1) -> m.group(2))
      .toList
      .toMap
    assert(registeredSourceKeys.nonEmpty, "INJECTED_SOURCE_LABELS 为空表 —— 登记面被清空？")
    registeredSourceKeys.foreach { k =>
      val v = labels.getOrElse(k, "")
      assert(
        v.nonEmpty && v.head.isUpper,
        s"INJECTED_SOURCE_LABELS['$k'] 标签必须是显式非空展示名（当前='$v'）"
      )
    }
    // 后端集合中的多值源必须逐个有标签（表内登记者），不允许只靠 charAt 兜底。
    InjectionAttribution.BackendNamedSources.intersect(registeredSourceKeys).foreach { k =>
      assert(labels.contains(k), s"$k 在表中但无标签")
    }

  test("④ 两侧同源字段名：后端写盘字段 ⇄ 前端 live 帧读取 / 历史恢复读取"):
    // 线格式字段名（后端唯一发射点写 WS 帧 + UiMessage.User 落盘的同一批名字）。
    val wireFields = List("injected", "source", "eventType", "sender", "senderTeam", "delivery")
    val mainJs = read(repoRoot / "src" / "main" / "resources" / "web" / "js" / "main.js")
    val persistenceJs = read(persistenceJsPath)

    // live 消费点（main.js#onMessage('user')）逐字段读取帧字段。
    wireFields.foreach { f =>
      assert(
        mainJs.contains(s"msg.$f"),
        s"main.js 的注入帧消费点未读字段 msg.$f —— 后端字段与 live 呈现面脱钩？"
      )
    }
    // 历史恢复点（persistence.js 两条 restore 路径）逐字段读 UiMessage 行。
    wireFields.foreach { f =>
      assert(
        persistenceJs.contains(s"m.$f"),
        s"persistence.js 的注入行恢复路径未读字段 m.$f —— 后端落盘字段与历史呈现面脱钩？"
      )
    }
    assert(
      chatJs.contains("injected-source-label"),
      "chat.js 缺 injected-source-label 类名 —— 注入气泡的标签容器被改名？"
    )
    // 判据（persistence.js#isOutgoingInjection）读 sender——本会话自身外发不渲染
    // （后端 sender 的取值域恒 = 发送方 agent 自身名，见 InjectionAttribution 契约注释）。
    assert(
      persistenceJs.contains("isOutgoingInjection"),
      "persistence.js 缺 isOutgoingInjection —— 注入气泡判据被搬迁/改名？"
    )
    assert(
      persistenceJs.contains("m.sender"),
      "persistence.js#isOutgoingInjection 判据必须读 sender（与后端 sender 字段同源）"
    )

end InjectionSourceContractSpec
