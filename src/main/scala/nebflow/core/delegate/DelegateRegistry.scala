package nebflow.core.delegate

import io.circe.Json
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.PathUtil

/**
 * DelegateRegistry —— 统一 delegate 的持久地址簿（unified-delegate 批
 * 2026-10-03）。
 *
 * 大改后的使用形态：`Delegate(task, project?)` 是 Nebula 唯一的派发入口，
 * 执行器可以是内置 agent 或外部 CLI（executor-registry 批的 default）。每个
 * delegate 实例在这里落一条**持久记录**（`<dataRoot>/delegates/<id>.json`）：
 *
 *   - **id** = 续聊地址的 payload（`delegate:<id>`）——内部实例即会话 id
 *     （`delegate-<agent>-<8hex>`，与既有 Sub-Agents 面板前缀兼容）；
 *   - executor / project / cwd / taskId / parentSessionId / task 摘要——
 *     Mail 续聊、AgentControl 地址列、任务列表渲染的统一数据源；
 *   - 跨重启存活（JSON 落盘）：Mail 对一个终态实例的补充信息靠它找到
 *     实例的执行器与工作目录再续起（live 状态本身仍以内存 agentRegistry
 *     为准——重启后一律视为不 live）。
 *
 * 状态**不落本表**：live 与否查 `agentRegistry`（kill -9 后自动视为不 live），
 * 终态结果状态查 `subAgentTaskStore`（既有机制，per-parent 会话文件）。本表
 * 只做「地址 → 实例元数据」的持久映射，绝无第二份可漂移的状态机。
 *
 * 读写纪律：读 = 每次现读盘（热读，仿 JevConfigReader）；写 = 同步整写
 * （AtomicJson.writeSync）。记录量级 = 每 delegate 一个小 JSON 文件，无索引
 * 需求（list 全扫目录）。
 */
object DelegateRegistry:

  /** 一条持久 delegate 记录。 */
  final case class Record(
      id: String,
      executor: String,
      /** 派发时解析的工作目录（项目 workspace 或默认 general 目录）。 */
      cwd: String,
      /** 挂载项目名（缺省派发 = None → cwd 为默认 general 目录）。 */
      project: Option[String],
      /** 关联的 TaskLedger 条目 id（"12" 形态，无 #）。 */
      taskId: Option[String],
      /** 派发者（Nebula 根）会话 id——结果回投与 Mail 排队路由锚点。 */
      parentSessionId: String,
      /** 任务摘要（列表显示用；全文在 TaskLedger）。 */
      title: String,
      createdAt: Long
  )

  private def dir: os.Path = PathUtil.dataRoot / "delegates"

  private def fileOf(id: String): os.Path = dir / s"$id.json"

  /** id 安全校验：只允许落盘文件名白名单字符（防路径穿越）。 */
  def validId(id: String): Boolean =
    id.nonEmpty && id.length <= 128 && id.matches("^[a-zA-Z0-9._-]+$")

  def put(record: Record): Unit =
    os.makeDir.all(dir)
    AtomicJson.writeSync(fileOf(record.id), record.asJson.noSpaces)

  /** 持久查找（每次现读；坏形/缺失 → None，绝不抛）。 */
  def find(id: String): Option[Record] =
    if !validId(id) then None
    else
      try
        val p = fileOf(id)
        if !os.exists(p) then None
        else io.circe.parser.parse(os.read(p)).toOption.flatMap(_.as[Record].toOption)
      catch case _: Throwable => None

  /** 全量列出（按 createdAt 倒序；坏文件跳过）。 */
  def list(): List[Record] =
    try
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .filter(_.ext == "json")
          .flatMap(p => io.circe.parser.parse(os.read(p)).toOption.flatMap(_.as[Record].toOption))
          .toList
          .sortBy(-_.createdAt)
    catch case _: Throwable => Nil

  /** 续聊地址行——Delegate 回执与 Mail 地址面提示共用一份措辞。 */
  def continuationLine(id: String): String =
    s"\nContinuation address: \"delegate:$id\" — later communication about THIS instance (supplements, corrections) goes through Mail(address=\"delegate:$id\"). Its result is delivered back to your session when it finishes."

  /** JSON 编解码（circe generic，字段即 case class 形态）。 */
  given io.circe.Encoder[Record] = io.circe.generic.semiauto.deriveEncoder
  given io.circe.Decoder[Record] = io.circe.generic.semiauto.deriveDecoder

end DelegateRegistry
