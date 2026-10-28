package nebflow.gateway

import munit.FunSuite

import scala.io.Source

/**
 * 来源设备标记注入点的**源契约门**（device-provenance 批，2026-10-23）。
 *
 * 标记的行为读数（帧上行 → 花名册对照 → 模型面标注）需要完整 gateway 栈；
 * 腿的**结构面**可机械复核的判据在此钉死（先例 = `UserTextGateSpec` /
 * `nebflow.agent.InjectionSourceContractSpec`：「不起服务、只读源码文本」的
 * 契约门；覆盖边界 = 符号面/调用点面，行为读数归联调轮）。
 *
 * 钉的三件事（🔴 缺一即红）：
 *   ① 两 face 分离不被回流——`dispatchUserText` 的 UiMessage 行保持裸正文、
 *      标记只进 `ImmediateInput`（与 source-marker relocation 批同一条红线：
 *      system reminder 永不出现在会话气泡）；
 *   ② typeless 腿的标记走**块前插**（`prependMarker`），不重写正文块；
 *   ③ 前端五+一处真人发送点全部随帧带 `device` 声明（漏一处 = 该端消息无标注）。
 */
class DeviceProvenanceInjectionGateSpec extends FunSuite:

  private val repoRoot = new java.io.File(".").getAbsoluteFile

  private def read(rel: String): String =
    val f = new java.io.File(repoRoot, rel)
    assert(f.isFile, s"源码不存在：${f.getPath}（本 spec 必须在仓根运行）")
    val src = Source.fromFile(f, "UTF-8")
    try src.mkString
    finally src.close()

  /** 剥块注释与行注释后的代码面（判据只认代码，与 UserTextGateSpec 同法）。 */
  private def codeOnly(src: String): String =
    val noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "")
    noBlock.linesIterator.map(l => l.replaceAll("//.*$", "")).mkString("\n")

  private lazy val wsRoutesCode: String = codeOnly(read("src/main/scala/nebflow/gateway/WebSocketRoutes.scala"))

  private lazy val chatHandlersCode: String = codeOnly(
    read("src/main/scala/nebflow/gateway/WsSessionChatHandlers.scala")
  )
  private lazy val dispatchCode: String = codeOnly(read("src/main/scala/nebflow/gateway/WsDispatch.scala"))
  private lazy val inputJs: String = read("src/main/resources/web/js/input.js")
  private lazy val recoveryJs: String = read("src/main/resources/web/js/errorRecovery.js")

  private val BareUiRow =
    "List(UiMessage.User(content, Nil, timestamp = System.currentTimeMillis()))"

  test("①a dispatchUserText: the UiMessage display row stays BARE content (marker can never leak to the chat stream)") {
    assert(
      wsRoutesCode.contains(BareUiRow),
      s"显示面必须保持裸正文行 $BareUiRow（标记只进模型面）"
    )
  }

  test("①b dispatchUserText: the marker reaches the model via ImmediateInput only (device.fold ⇒ compose)") {
    val markerLeg = "device.fold(content)(nebflow.bridge.DeviceProvenance.compose(_, content))"
    assert(
      wsRoutesCode.contains(markerLeg),
      s"模型面前缀合成缺席：期望含 `$markerLeg`"
    )
  }

  test("② the typeless leg prepends the marker BLOCK (prependMarker), never rewrites the content") {
    assert(
      wsRoutesCode.contains("deviceResolved.fold(blocks.toList)(") &&
        wsRoutesCode.contains("nebflow.bridge.DeviceProvenance.prependMarker(_, blocks.toList)"),
      "typeless 腿必须块前插（prependMarker），正文块逐字不动"
    )
  }

  test("②b the immediateInput leg resolves the frame's device stamp and threads it into handleUserText") {
    assert(
      chatHandlersCode.contains("deviceProvenance(immJson).flatMap") &&
        chatHandlersCode.contains("fromUser = true, device"),
      "immediateInput 腿必须先解析 device 读数再下发 handleUserText"
    )
    assert(
      dispatchCode.contains("deviceProvenanceImpl: io.circe.Json => IO[Option[nebflow.bridge.ResolvedDevice]]"),
      "分发上下文必须携带 deviceProvenanceImpl 注入缝"
    )
  }

  test("③ every real-human send site in the frontend stamps device: deviceStamp()") {
    // input.js 的随帧声明面（attach-split 帧构建收敛后的现状拓扑）：
    //   buildFrame 中央腿（四腿共用：send / 离线补发 / 队列 drain v2 / busy 队列
    //   drain，经 dispatchUserMessageFrames 汇流）+ 附件分片腿 + 队列「立即发送」
    //   immediateInput + 插件卡注入 = 4 处；errorRecovery 重发腿独立 1 处。
    val stamped = "device: deviceStamp()"
    val inputCount = inputJs.sliding(stamped.length).count(_ == stamped)
    assert(inputCount >= 4, s"input.js 随帧声明钉 4 处，实测 $inputCount 处")
    // 中央腿必须自带声明（漏此一处 = 四条汇流腿全部裸奔），且四腿确经它汇流。
    assert(
      inputJs.contains("const buildFrame = (attachments, cmid, withExtras) => ({"),
      "帧构建中央腿（buildFrame）必须在位"
    )
    val buildFrameBody = inputJs.drop(inputJs.indexOf("const buildFrame"))
    assert(
      buildFrameBody.contains(stamped),
      "buildFrame 必须自带 device 声明（四腿共用的收敛点）"
    )
    // 四腿汇流锚：1 处定义 + 4 处调用（send/离线补发/队列 drain v2/busy drain）。
    val dispatchHead = "dispatchUserMessageFrames({"
    val dispatchCount = inputJs.sliding(dispatchHead.length).count(_ == dispatchHead)
    assert(dispatchCount >= 5, s"dispatchUserMessageFrames 定义+四腿调用钉 5 处，实测 $dispatchCount 处")
    assert(recoveryJs.contains(stamped), "errorRecovery 重发腿缺 device 声明")
  }
end DeviceProvenanceInjectionGateSpec
