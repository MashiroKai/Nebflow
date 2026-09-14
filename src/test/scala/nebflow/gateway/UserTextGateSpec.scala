package nebflow.gateway

import munit.FunSuite

import scala.io.Source

/**
 * 输入框直通退役源契约门（本批新增判据 · 2026-09-14）。
 *
 * 沿革：本文件原为「第六件 QC (2026-08-30)」的 `probesPassthrough` 值域门 ——
 * 它钉的是「WS 输入框族才探 AskUser 直通、headless REST 不探」。作者 2026-09-14
 * 下令「把 AskUserQuestion 通过输入框回答的功能关了」⇒ 两条探直通腿
 * （`handleUserText` 侧 / typeless 帧侧）+ `askPassthroughOrDispatch` /
 * `probesPassthrough` + hub `AnswerViaChatInput` / `handleChatInputAnswer`
 * 整体删除（U2 死码全删口径）。原门所钉的符号已不存在 ⇒ 本文件改为**源契约
 * 门**：负控 = 「输入框文本不再成为卡片答案」的符号面已摘净；正控 = 卡片入口
 * 与普通消息通道仍在。
 *
 * 为什么是纯文本契约门：直通腿的行为面需要完整 gateway 栈才可观测（原文件
 * 亦自述「instance 侧的 timeout 回退不可单测」）；腿已删 ⇒ 残留的可机械复核
 * 判据 = 源码文本面。先例：`nebflow.agent.InjectionSourceContractSpec`（同为
 * 「不起服务、只读源码文本」的契约门）。
 *
 * 覆盖边界（🔴 逐字）：本门只证明**符号面/调用点面**已摘除（编译期即可判：
 * 任何残留调用点都会编译失败）。「关闭后输入框文本确实走普通消息通道、且不丢
 * 消息」的**行为读数**归 #515 联合轮——本批禁构建 ⇒ **未跑 ≠ 已绿**。
 *
 * 范围（🔴 有意）：负控 ③ 只扫 `src/main/scala`（后端）。前端 `web/js/**` 内
 * 仍有 `askUserAnswered` 的接收点与直通相关注释，但本批口径 = **前端零改动**
 * ⇒ 不在本门断言面内（见交付报告「未做/未决」）。
 */
class UserTextGateSpec extends FunSuite:

  private val repoRoot = new java.io.File(".").getAbsoluteFile
  private val wsRoutesFile =
    new java.io.File(repoRoot, "src/main/scala/nebflow/gateway/WebSocketRoutes.scala")
  private val hubFile =
    new java.io.File(repoRoot, "src/main/scala/nebflow/agent/InteractionHub.scala")
  private val mainScalaDir = new java.io.File(repoRoot, "src/main/scala")

  private def read(f: java.io.File): String =
    // 真实源码缺失 = 环境问题（spec 必须跑在仓根），显式失败而非静默跳过。
    assert(f.isFile, s"源码不存在：${f.getPath}（本 spec 必须在仓根运行）")
    val src = Source.fromFile(f, "UTF-8")
    try src.mkString
    finally src.close()

  /** 剥掉块注释与 `//` 行注释后的**代码面** —— 判据只认代码：注释里提到符号名
    * 不得算「还在」，也不得靠注释凑出「已删」的假绿。 */
  private def codeOnly(src: String): String =
    val noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "")
    noBlock.linesIterator.map(l => l.replaceAll("//.*$", "")).mkString("\n")

  private lazy val wsRoutes: String = read(wsRoutesFile)
  private lazy val hub: String = read(hubFile)
  private lazy val wsRoutesCode: String = codeOnly(wsRoutes)
  private lazy val hubCode: String = codeOnly(hub)

  /** 递归收集目录下的 .scala 源文件（java.io 实现，零新依赖面）。 */
  private def scalaSourcesUnder(dir: java.io.File): List[java.io.File] =
    val out = scala.collection.mutable.ListBuffer.empty[java.io.File]
    def go(d: java.io.File): Unit =
      val kids = d.listFiles()
      if kids != null then
        kids.foreach { k =>
          if k.isDirectory then go(k)
          else if k.getName.endsWith(".scala") then out += k
        }
    go(dir)
    out.toList

  // ---------------------------------------------------------------
  // 负控：直通腿 + 其符号面整体消失
  // ---------------------------------------------------------------

  test("负控 ①：gateway 侧两条探直通腿的符号面已摘净（probesPassthrough / askPassthroughOrDispatch）") {
    for sym <- List("probesPassthrough", "askPassthroughOrDispatch") do
      assert(
        !wsRoutesCode.contains(sym),
        s"$sym 仍出现在 WebSocketRoutes 的代码面 —— 直通退役未完成（U2 口径：不接受只删一半）"
      )
  }

  test("负控 ②：hub 侧 AnswerViaChatInput / handleChatInputAnswer 已删 —— 输入框文本不再被消费为卡片答案") {
    for sym <- List("AnswerViaChatInput", "handleChatInputAnswer") do
      assert(
        !hubCode.contains(sym),
        s"$sym 仍出现在 InteractionHub 的代码面 —— 输入框文本仍可能成为卡片答案"
      )
  }

  test("负控 ③：全 main 源树（src/main/scala）无任何 ask 直通残留调用点") {
    val offenders = scalaSourcesUnder(mainScalaDir).flatMap { f =>
      val code = codeOnly(read(f))
      val hits = List("AnswerViaChatInput", "askPassthroughOrDispatch", "probesPassthrough").filter(code.contains)
      if hits.isEmpty then Nil
      else List(s"${f.getPath.stripPrefix(repoRoot.getPath + "/")}: ${hits.mkString(", ")}")
    }
    assert(
      offenders.isEmpty,
      s"残留调用点（越界即停：任一被卡片入口路径共用的代码不得删）:\n  ${offenders.mkString("\n  ")}"
    )
  }

  test("负控 ④：hub 不再广播 askUserAnswered（该帧曾只由直通腿发出）") {
    assert(
      !hubCode.contains("askUserAnswered"),
      "InteractionHub 仍在广播 askUserAnswered —— 直通腿的收尾信号未随之删除"
    )
  }

  test("负控 ⑤：handleUserText 体不再探 hub —— 文本无条件直投 dispatchUserText") {
    val start = wsRoutesCode.indexOf("private def handleUserText")
    val end = wsRoutesCode.indexOf("end handleUserText", start)
    assert(start >= 0 && end > start, "handleUserText 定义体定位失败（签名/收尾被改名或搬迁）")
    val body = wsRoutesCode.substring(start, end)
    assert(
      !body.contains("interactionHubRef"),
      "handleUserText 仍在读 interactionHubRef —— 输入框直通分支没摘净"
    )
    assert(
      body.contains("dispatchUserText(sessionId, content, source, fromUser)"),
      "handleUserText 必须把文本无条件交给 dispatchUserText（普通消息通道）"
    )
  }

  // ---------------------------------------------------------------
  // 正控：卡片入口与普通消息通道仍在（退役不得波及这两条）
  // ---------------------------------------------------------------

  test("正控 ①：卡片入口作答仍在（Answered 命令 + handleAnswered + requestId 形态校验）") {
    assert(hubCode.contains("case class Answered"), "卡片作答命令 Answered 丢失（越界删除）")
    assert(hubCode.contains("handleAnswered"), "卡片作答处理 handleAnswered 丢失（越界删除）")
    assert(hubCode.contains("answerCompletes"), "requestId 形态校验 answerCompletes 丢失（越界删除）")
  }

  test("正控 ②：普通消息通道仍在（dispatchUserText = 唯一入队路径 + ImmediateInput 投放）") {
    val start = wsRoutesCode.indexOf("private def dispatchUserText")
    assert(start >= 0, "普通消息入队路径 dispatchUserText 丢失（越界删除）")
    val body = wsRoutesCode.substring(start)
    assert(
      body.contains("AgentCommand.ImmediateInput(content, fromUser = fromUser)"),
      "dispatchUserText 不再投 ImmediateInput —— 输入框文本的「普通消息」承接被改坏"
    )
  }

end UserTextGateSpec
