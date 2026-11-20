package nebflow.gateway.ws

import nebflow.gateway.*

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

  /**
   * 显示面裸正文行的**形状锚**：只钉两个契约实参（正文实参 = 裸 `content`、附件实参
   * = `Nil`），不钉整行的可选键尾。
   *
   * 形状沿革（本门唯一被钉的字面量，两次成因都记在此处）：
   *   - device-provenance 批 aefea8f77（2026-10-23）原形状：
   *     `List(UiMessage.User(content, Nil, timestamp = System.currentTimeMillis()))`
   *   - tool-card-leak-on-reload 批 85535b139（2026-11-02）在同一行尾追加正交键
   *     `midTurn = midTurn`（落盘这一瞬判「回合内插话」，供回放折叠段边界），并把
   *     整段落盘包进 `midTurnAtPersist(sessionId).flatMap`。正文实参仍是裸 `content`、
   *     附件仍是 `Nil` —— 两 face 分离的红线未被触碰，缺的只是本门的旧字面量。
   *
   * 为什么只钉前缀而不钉整行：`midTurn` / `quote` / `header` 这类可选键的增删与
   * 「标记会不会泄漏到显示面」无关，把整行配参钉死只会让这类无关批误伤本门
   * （2026-11-02 批即如此）。先例：同包 `UserTextGateSpec` 正控①「钉语义而非参数
   * 个数/字面，后续再加参不再误伤本门」。
   */
  private val BareUiRow =
    "List(UiMessage.User(content, Nil, timestamp = System.currentTimeMillis()"

  test("①a dispatchUserText: the UiMessage display row stays BARE content (marker can never leak to the chat stream)") {
    // 判据收在 dispatchUserText **体内**（不是全文件任意一处同名行）：裸正文行的
    // 语义是「这条唯一的普通消息入队路径落盘时写的是裸 content」。
    val defHead = "private def dispatchUserText"
    val start = wsRoutesCode.indexOf(defHead)
    assert(start >= 0, "dispatchUserText 定义丢失（普通消息唯一入队路径）")
    val nextDef = wsRoutesCode.indexOf("\n  private def ", start + defHead.length)
    val body = if nextDef > start then wsRoutesCode.substring(start, nextDef) else wsRoutesCode.substring(start)
    assert(
      body.contains(BareUiRow),
      s"显示面必须保持裸正文行 $BareUiRow （正文实参 content 裸、附件 Nil；标记只进模型面）"
    )
  }

  // The model/display separation is checked through the real message handler in
  // ConsoleQuoteDeliverySpec (device marker in ImmediateInput, bare UI row on disk).

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

  // ── 门户身份透传批（方案 A1，2026-10-28）：会话身份穿线契约 ──────────────
  //
  // 设计 §5 A1：SessionIdentity 在握手处提取（takeover 先例同点）随收流管闭包
  // 进消息面；Bearer 轨恒 None。四个结构面缺一即红——回退（提取点消失/线程断/
  // 消费点漏改回单参）都判「会话身份弃用复发」（断点表 B3 复辟）。

  test(
    "A1a the handshake extracts SessionIdentity at the SAME point as the takeover precedent (ViaSession projection)"
  ) {
    assert(
      wsRoutesCode.contains("val sessionIdentity: Option[nebflow.bridge.SessionIdentity] = cred match") &&
        wsRoutesCode.contains(
          "Some(nebflow.bridge.SessionIdentity(record.accountSub, record.origin, record.sessionId))"
        ),
      "握手处必须与会话轨同点提取 SessionIdentity 投影（accountSub/origin/sessionId）"
    )
  }

  test(
    "A1b the receive pipe threads the identity into handleMessage (per-connection closure, same scope as takeover)"
  ) {
    // df7bb5334（属主附件批）给调用追了第 5 参 Some(cred)——身份穿线语义不变，
    // 钉随调用形更新（仍要求 sessionIdentity 在收流管闭包内随帧进分发）。
    assert(
      wsRoutesCode.contains("handleMessage(text, perConnJsonSend, watchSession, sessionIdentity, Some(cred))"),
      "收流管必须把本连接的 sessionIdentity 穿进消息分发"
    )
  }

  test("A1c the typeless leg resolves with the session identity; the REST public face stays None (byte-identical)") {
    assert(
      wsRoutesCode.contains("deviceProvenanceOf(json, sessionIdentity).flatMap"),
      "typeless 腿必须携会话身份解析"
    )
    // REST headless 轨无连接作用域：handleMessagePublic 的调用不带身份（默认 None）。
    val publicLeg = wsRoutesCode.substring(wsRoutesCode.indexOf("def handleMessagePublic"))
    val publicSlice = publicLeg.substring(0, math.min(publicLeg.length, 600))
    assert(
      publicSlice.contains("handleMessage(text, wsSend, new ExplorerWatchSession(wsSend, logger, live = false))"),
      "REST 公共脸必须保持无身份形态（三参调用 = 默认 None）"
    )
  }

  test("A1d the immediateInput seam closes over the connection identity in the dispatch context") {
    assert(
      dispatchCode.contains("deviceProvenanceImpl: io.circe.Json => IO[Option[nebflow.bridge.ResolvedDevice]]"),
      "分发上下文必须携带 deviceProvenanceImpl 注入缝（既有契约不变）"
    )
    assert(
      wsRoutesCode.contains(
        "deviceProvenanceImpl = (json: io.circe.Json) => deviceProvenanceOf(json, sessionIdentity)"
      ),
      "分发上下文必须按本连接会话身份闭包（immediateInput 腿）"
    )
  }

  test("A1e the owner-anchor read is session-gated (Bearer rail pays zero credential-file IO)") {
    assert(
      wsRoutesCode.contains("if sessionIdentity.isDefined then OwnerIdentity.localAnchor else IO.pure(None)"),
      "owner 锚读取必须按会话身份门控（Bearer 轨零文件 IO，行为逐字节不变）"
    )
  }
end DeviceProvenanceInjectionGateSpec
