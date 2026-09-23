package nebflow.agent

import munit.FunSuite

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.shared.{Message, UiMessage}

import scala.io.Source

/**
 * mailbadge 批（2026-09-13，作者裁定「必须显示 MAIL」⇒ 选项 C）：
 * **收件判别字段两侧同源**契约门（与 `InjectionSourceContractSpec` 同族）。
 *
 * 契约：`Mail(address="project:<name>")` 的收件气泡标签显示 `Mail`，而
 * `source` 恒为 `"task"`（桥的消费计数口径——`ProjectActor.DispatcherInjectedSources`
 * / `:622` 计数单点 / `idleSince` 30 min 空闲窗**逐行不动**，改 `source` 会静默
 * 打坏 D5）。判别走**新增可选字段** `intake`：
 *
 *   置位侧  `MailTool#mailAttribution(…, intake = Some(IntakeMail))`（**仅腿①**）
 *     ↓ `ProjectActor.TriggerDispatcher(attribution)` → `UserInput(intake = …)`
 *   帧字段  `AgentActor#emitInjectedUserEvent` 的 `deepMerge`（与 senderTeam/delivery 同款）
 *   落盘    `UiMessage.User.intake` 编码器/解码器（历史恢复面）
 *   消费侧  `web/js/chat.js#injectedSourceLabel`（`intake || source` 优先 + 回落表）
 *           `main.js#onMessage('user')` / `persistence.js` 两条 restore 路径读该字段
 *
 * 本 spec 是纯文本契约门 + 一处**编解码行为断言**（不起服务、不读运行时状态）：
 * 任一侧漂移（字段改名 / 置位点越界到腿②③ / 消费侧漏读 / `source` 被动过）
 * ⇒ 此处红。批量行为门（正例 + 负控 + 变异验红）= `tests/injected-intake-label.mjs`。
 */
class InjectionIntakeContractSpec extends FunSuite:

  private val repoRoot = os.pwd

  private def read(rel: String): String =
    val p = repoRoot / os.RelPath(rel).segments
    assert(os.exists(p), s"源码不存在：$p（本 spec 必须在仓根运行）")
    val src = Source.fromFile(p.toIO, "UTF-8")
    try src.mkString
    finally src.close()

  private lazy val mailTool: String = read("src/main/scala/nebflow/core/tools/MailTool.scala")
  private lazy val projectActor: String = read("src/main/scala/nebflow/core/project/ProjectActor.scala")
  private lazy val agentActor: String = read("src/main/scala/nebflow/agent/AgentActor.scala")
  private lazy val sharedProtocol: String = read("src/main/scala/nebflow/shared/protocol.scala")
  private lazy val chatJs: String = read("src/main/resources/web/js/chat.js")
  private lazy val mainJs: String = read("src/main/resources/web/js/main.js")
  private lazy val persistenceJs: String = read("src/main/resources/web/js/persistence.js")

  private val WireField = "intake"

  test("① 定名 pin：收件通道取值域 = {mail}，且 ∈ 后端自定名 source 词表（前端登记面因而覆盖它）"):
    assertEquals(
      InjectionAttribution.IntakeMarkers,
      Set("mail"),
      "IntakeMarkers 变更 ⇒ 同步检查 web/js/chat.js#INJECTED_SOURCE_LABELS 并更新本 pin"
    )
    assertEquals(InjectionAttribution.IntakeMail, "mail", "IntakeMail 定名 = 'mail'")
    assert(
      InjectionAttribution.BackendNamedSources.contains(InjectionAttribution.IntakeMail),
      s"IntakeMail='${InjectionAttribution.IntakeMail}' 必须在 BackendNamedSources 内" +
        "（intake 经同一张 INJECTED_SOURCE_LABELS 解析 ⇒ 必须被既有登记门覆盖）"
    )

  /** 字面子串出现次数（不当作正则——调用形态含括号/点号）。 */
  private def countLiteral(hay: String, needle: String): Int =
    if needle.isEmpty then 0 else hay.sliding(needle.length).count(_ == needle)

  test("② 置位侧单点：intake 只在腿①（→project）置位，腿②（→node）/腿③（非 project 面）不置位"):
    val leg1Call = "mailAttribution(mailType, ctx, Some(InjectionAttribution.IntakeMail))"
    val bareCall = "mailAttribution(mailType, ctx)"
    assertEquals(
      countLiteral(mailTool, leg1Call),
      1,
      s"`$leg1Call` 必须**恰好一处**（腿① routeToProject）——腿②（node，source='system'⇒标签 System）" +
        "与腿③（source='mail'⇒标签 Mail）呈现口径不同：在共用构造点统一置位会把节点收件面标签抬成 Mail（越界扩面）"
    )
    assertEquals(
      countLiteral(mailTool, bareCall),
      1,
      s"`$bareCall`（默认 intake=None）必须恰好一处（腿② deliverToNode）——腿① 应用带 IntakeMail 的重载"
    )
    assert(
      mailTool.contains(
        "private def mailAttribution(\n      mailType: String,\n      ctx: ToolContext,\n      intake: Option[String] = None\n  )"
      ),
      "mailAttribution 必须保留 `intake: Option[String] = None` 默认参数（默认 None ⇒ 未置位腿零行为变化）"
    )
    // 腿③（sendMail → ImmediateInput，source='mail'）不得被本批碰：仍无 intake 键。
    val sendMailBody = mailTool.substring(
      mailTool.indexOf("private def sendMail("),
      mailTool.indexOf("end sendMail")
    )
    assert(
      !sendMailBody.contains("intake"),
      "腿③ sendMail 被塞入 intake —— 非 project 面呈现零漂移（作者验收反向第 3 例：仍 Mail）"
    )

  test("③ 透传侧：intake 随 UserInput 透传，且 `source` 与桥的计数口径逐字未动（禁动面 tripwire）"):
    assert(
      projectActor.contains("intake = attribution.flatMap(_.intake)"),
      "ProjectActor 未把 attribution.intake 透传进 UserInput —— 置位侧与帧字段脱钩？"
    )
    assert(
      projectActor.contains("source = Some(source)"),
      "ProjectActor 的注入源标记被改动 —— source 语义必须保持（D-5：值不改名）"
    )
    assert(
      projectActor.contains("private val DispatcherInjectedSources: Set[String] = Set(SourceTask, SourceDispatch)"),
      "DispatcherInjectedSources 集合成员被改动 —— 本批禁动面（改它 ⇒ 桥计数漂移 ⇒ idleSince 永不置位）"
    )
    assert(
      projectActor.contains("messages.count(m => m.source.exists(DispatcherInjectedSources.contains))"),
      "桥的消费计数单点（ProjectActor:622 形态）被改动 —— 本批禁动面"
    )
    assert(
      projectActor.contains("idleSince = Some(System.currentTimeMillis())"),
      "idleSince 置位点被改动 —— 本批禁动面（30 min 空闲窗判据）"
    )

  test("④ 帧字段 / 落盘字段同源：发射点 deepMerge + 编码器 + 解码器 + 前端两侧读取面"):
    assert(
      agentActor.contains("""deepMerge(Json.obj("intake" -> i.asJson))"""),
      "AgentActor#emitInjectedUserEvent 缺 intake 的 deepMerge 帧字段（与 senderTeam/delivery 同款）"
    )
    assert(
      sharedProtocol.contains("""deepMerge(Json.obj("intake" -> i.asJson))"""),
      "UiMessage.User 编码器未落盘 intake —— 历史恢复面拿不到该字段？"
    )
    assert(
      sharedProtocol.contains("""cursor.downField("intake").as[Option[String]]"""),
      "UiMessage 解码器未读 intake —— 落盘行读不回？"
    )
    // 消费侧同源（live 帧 + 两条历史恢复路径）。
    assert(mainJs.contains(s"msg.$WireField"), s"main.js 注入帧消费点未读 msg.$WireField")
    assert(persistenceJs.contains(s"m.$WireField"), s"persistence.js 注入行恢复路径未读 m.$WireField")
    assertEquals(
      s"m.$WireField".r.findAllMatchIn(persistenceJs).length >= 2,
      true,
      "persistence.js 两条 restore 路径（restoreFromStorage / batch restore）都必须读 m.intake"
    )
    assert(
      chatJs.contains("const key = intake || source;"),
      "chat.js#injectedSourceLabel 缺 intake 优先判据（`intake || source`）—— 呈现侧未接该字段"
    )
    assert(
      chatJs.contains("INJECTED_SOURCE_LABELS[key] || key.charAt(0).toUpperCase() + key.slice(1)"),
      "chat.js 标签解析表达式被改写 —— 缺席回落路径必须逐字保持（回落表语义不改）"
    )

  test("⑤ 编解码行为：置位 ⇒ 落 `intake:mail`；缺席 ⇒ 不落键且解码回落 None（向后兼容 / 负控）"):
    val withIntake: UiMessage = UiMessage.User("t", injected = true, source = Some("task"), intake = Some("mail"))
    val encoded = withIntake.asJson
    assertEquals(
      encoded.hcursor.downField(WireField).as[String],
      Right("mail"),
      "置位侧落盘缺 intake 键"
    )
    assertEquals(
      decode[UiMessage](encoded.noSpaces),
      Right(withIntake),
      "intake 编码/解码不成对（历史恢复面会丢字段）"
    )
    val withoutIntake: UiMessage = UiMessage.User("t", injected = true, source = Some("task"))
    val oldRow = withoutIntake.asJson
    assert(
      oldRow.hcursor.downField(WireField).failed,
      "intake 缺席时不得落键（旧 .ui.json 行的字节形态与旧读法必须逐字不变）"
    )
    assertEquals(
      decode[UiMessage](oldRow.noSpaces),
      Right(withoutIntake),
      "旧行（无 intake 键）必须解码为 None ⇒ 前端走回落路径（负控）"
    )
    // 旧历史行（真实 .ui.json 形态：无 intake 键）解码不失败。
    val legacyRow =
      """{"type":"user","text":"x","injected":true,"source":"task","sender":"Nebula","eventType":"info"}"""
    decode[UiMessage](legacyRow) match
      case Right(u: UiMessage.User) =>
        assertEquals(u.intake, None, "旧行默认必须为 None（回落）")
        assertEquals(u.source, Some("task"), "旧行 source 语义不变")
      case other => fail(s"旧 .ui.json 注入行解码失败：$other")

  test("⑥ 字段中文档面：intake 不得进入任何会计/去重判据（只做呈现判别）"):
    // 计数/生命周期面不得出现 intake（禁动面在代码上可 grep 验证）。
    val accountingFaces = List(
      "src/main/scala/nebflow/core/project/ProjectActor.scala" -> projectActor,
      "src/main/scala/nebflow/gateway/SessionStore.scala" -> read("src/main/scala/nebflow/gateway/SessionStore.scala"),
      "src/main/scala/nebflow/core/flow/MailQueueStore.scala" ->
        read("src/main/scala/nebflow/core/flow/MailQueueStore.scala")
    )
    accountingFaces.foreach { case (path, src) =>
      val offenders = src.linesIterator.zipWithIndex
        .filter { case (l, _) =>
          l.contains(WireField) && (l.contains("DispatcherInjectedSources") || l.contains("pendingInjected"))
        }
        .map { case (l, i) => s"$path:${i + 1}: ${l.trim}" }
        .toList
      assert(offenders.isEmpty, s"intake 混入会计/生命周期判据（必须只做呈现判别）：\n${offenders.mkString("\n")}")
    }
    // Message（转录）层不得携带 intake —— 它不参与会计，转录侧零改动。
    val msgFields = classOf[Message].getDeclaredFields.map(_.getName).toSet
    assert(!msgFields.contains(WireField), "Message（转录层）被塞入 intake —— 会计面污染，本批禁")
    val uiFields = classOf[UiMessage.User].getDeclaredFields.map(_.getName).toSet
    assert(uiFields.contains(WireField), "UiMessage.User 缺 intake 字段（落盘面未接）")

end InjectionIntakeContractSpec
