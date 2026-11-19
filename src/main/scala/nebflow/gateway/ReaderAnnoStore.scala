/* 阅读器标注库服务端面（reader P3 生态批，设计稿 20261030_181655 §4.2 存储面）。
 *
 * 载体定夺（实现批裁）：**json 件**——`<projectRoot>/.nebflow/reader-annotations.json`
 * 单文件整件读写，原则对齐设计稿「跟人走、可备份、零服务端迁移」：
 *   · 跟人走/随工作区落库：件随项目工作区落盘，不在浏览器 localStorage；
 *   · 可备份/零服务端迁移：普通 JSON 件，任何文件工具可读（电子书库项目以
 *     「卡片 ↔ annotation 查询」消费本件，本批不消费、只落载体）；
 *   · 零新重依赖：整件读写给既有 WS 面加一条窄 op（reader.anno），路径由
 *     **服务端构建**（客户端零路径操控），containment 与 writeFile 同族。
 *
 * 形态（前端 annotations.js 为真源，此处只过字节）：
 *   { "v": 1, "annotations": [ { id, bookRef, format, spine, locator,
 *                               color, note, createdAt, updatedAt }, ... ] }
 *
 * 语义：load = 整件读出（件不存在 = 空库 ok:true content:null，非错误）；
 * save = 整件覆盖（> MaxBytes 拒写）。服务端不解析 JSON（字节透传），
 * 损坏件的容错在前端（解析失败 = 空库起步，不阻塞阅读）。
 */
package nebflow.gateway

import io.circe.syntax.*

/** Pure core of the reader-annotation store WS op (unit-tested without WS). */
private[gateway] object ReaderAnnoStore:

  /** 服务端构建的唯一合法落点（相对项目根；客户端不送路径）。 */
  val RelPath: String = ".nebflow/reader-annotations.json"

  /** 整件保存上限（标注库是 kb 级数据面；4MB 防爆写护栏）。 */
  val MaxBytes: Int = 4 * 1024 * 1024

  /** 项目根 → 标注件绝对路径（os.Path 归一，根形态无关）。 */
  def storePath(projectRoot: os.Path): os.Path =
    os.Path(RelPath, projectRoot)

  /** 保存裁定：Right(()) = 可写；Left(reason) = 拒写原因。 */
  def saveVerdict(byteSize: Int): Either[String, Unit] =
    if byteSize > MaxBytes then Left(s"annotation store exceeds $MaxBytes bytes")
    else Right(())

  /** load 应答帧（content = 件文本；None = 件不存在/空库起步）。 */
  def loadedFrame(reqId: String, content: Option[String]): io.circe.Json =
    io.circe.Json.obj(
      "type"    -> "readerAnno".asJson,
      "reqId"   -> reqId.asJson,
      "ok"      -> true.asJson,
      "content" -> content.asJson
    )

  /** save 成功应答帧。 */
  def savedFrame(reqId: String): io.circe.Json =
    io.circe.Json.obj(
      "type"  -> "readerAnno".asJson,
      "reqId" -> reqId.asJson,
      "ok"    -> true.asJson
    )

  /** 失败应答帧（load/save 共用；拒绝也落 ok:false + error，禁静默）。 */
  def errorFrame(reqId: String, error: String): io.circe.Json =
    io.circe.Json.obj(
      "type"  -> "readerAnno".asJson,
      "reqId" -> reqId.asJson,
      "ok"    -> false.asJson,
      "error" -> error.asJson
    )

end ReaderAnnoStore
