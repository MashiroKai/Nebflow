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

  /**
   * 一条持久 delegate 记录。
   *
   * worktree-judge-fifo 批（#1224 增补 §3.4）**扩面**：末四字段 = delegate_report
   * 的**状态槽**（仅状态单写，不携汇报——汇报仍走终态消息直传）。旧记录无此四键
   * ⇒ 解码按缺失 = None 容忍（手工 Decoder 单点，见文件尾——deriveDecoder 不吃
   * 缺键，会让存量记录在 list 里静默消失，禁）。
   */
  final case class Record(
      id: String,
      executor: String,
      /** 派发时解析的工作目录（项目 workspace 或默认 general 目录；worktree 腿 = worktree 路径）。 */
      cwd: String,
      /** 挂载项目名（缺省派发 = None → cwd 为默认 general 目录）。 */
      project: Option[String],
      /** 关联的 TaskLedger 条目 id（"12" 形态，无 #）。 */
      taskId: Option[String],
      /** 派发者（Nebula 根）会话 id——结果回投与 Mail 排队路由锚点。 */
      parentSessionId: String,
      /** 任务摘要（列表显示用；全文在 TaskLedger）。 */
      title: String,
      createdAt: Long,
      /** 工作分支（S13 分支纪律：恒 `delegate/<id>`；非 git 工作区 = None）。 */
      branch: Option[String] = None,
      /** worktree 目录（R6 判是腿；判否腿 = None——分支建在项目内）。 */
      worktree: Option[String] = None,
      /** spawn 时基分支（合并轮的 `merge --no-ff` 目标；非 git 工作区 = None）。 */
      baseBranch: Option[String] = None,
      /** 基工作区（合并执行的 `git -C` 目标；worktree 腿 = 项目 workspace，就地腿 = cwd）。 */
      projectWorkspace: Option[String] = None,
      /** delegate_report 状态槽：finish | blocked（未申报 = None）。 */
      reportStatus: Option[String] = None,
      /** 申报 detail（blocked 的原因承载）。 */
      reportDetail: Option[String] = None,
      /** 申报 suggestion（可选）。 */
      reportSuggestion: Option[String] = None,
      /**
       * 外部执行器的 CLI 侧会话 id（external-executors 批 #1225）——resume 续聊
       * 锚点（claude session_id / codex thread_id / zcode session_id），从适配流
       * 首帧取得后回写。None = 内部执行器或外部首帧尚未到达（续聊退化形态见
       * ExternalSessionRunner.startResumeTurn：带前文的续聊 brief，仍在同地址
       * 同执行器内，零回落）。缺省 None：旧盘面文件无此字段照常解码（向后兼容）。
       */
      cliSessionId: Option[String] = None,
      /**
       * 分配插件集（jev-plugins 批 #1226 · S9 可查面）：本 delegate spawn 时分配面
       * 授予的包名集（task 行结构帧的 plugins 摘要供源；渲染归 #1228）。**分配动作
       * 发生即记**（`Some(Nil)` = 显式空分配）；`None` = 分配未发生（plugins 总闸关 /
       * 外部执行器腿——外部执行器零注入，恒 None）。缺省 None：旧盘面文件无此字段
       * 照常解码（向后兼容，cliSessionId 同款纪律）。
       */
      plugins: Option[List[String]] = None
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

  /**
   * delegate_report 状态槽单写（#1224 增补 §3.4）：读-改-写（同步整写），只动
   * report* 四键，其余字段原样保真。查无记录 = `Left`（不凭空造记录——申报的
   * 身份必须来自真实 spawn）。
   */
  def updateReport(id: String, status: String, detail: String, suggestion: String): Either[String, Unit] =
    if !validId(id) then Left(s"delegate_report: invalid session id '$id'")
    else
      writeLock.synchronized {
        find(id) match
          case None => Left(s"delegate_report: no registry record for '$id' — report after a real Delegate spawn")
          case Some(rec) =>
            try
              put(
                rec.copy(
                  reportStatus = Some(status),
                  reportDetail = Some(detail),
                  reportSuggestion = if suggestion.nonEmpty then Some(suggestion) else None
                )
              )
              Right(())
            catch case e: Throwable => Left(s"delegate_report: write failed: ${e.getMessage}")
      }

  /** 进程内读-改-写串行化（updateReport 临界区；与 ChainStore.writeLock 各管各件）。 */
  private val writeLock = new Object

  /** JSON 编解码（circe）：Encoder = generic 派生；Decoder = **手工容错形态**——
    * 新扩的 Option 字段缺键时按 None 读（存量记录零迁移）。deriveDecoder 缺键即
    * 失败会让 `find`/`list` 把旧记录静默吞掉（catch → None），禁。 */
  given io.circe.Encoder[Record] = io.circe.generic.semiauto.deriveEncoder

  given io.circe.Decoder[Record] = (c: io.circe.HCursor) =>
    for
      id <- c.get[String]("id")
      executor <- c.get[String]("executor")
      cwd <- c.get[String]("cwd")
      project <- c.get[Option[String]]("project")
      taskId <- c.get[Option[String]]("taskId")
      parentSessionId <- c.get[String]("parentSessionId")
      title <- c.get[String]("title")
      createdAt <- c.get[Long]("createdAt")
      branch <- c.get[Option[String]]("branch")
      worktree <- c.get[Option[String]]("worktree")
      baseBranch <- c.get[Option[String]]("baseBranch")
      projectWorkspace <- c.get[Option[String]]("projectWorkspace")
      reportStatus <- c.get[Option[String]]("reportStatus")
      reportDetail <- c.get[Option[String]]("reportDetail")
      reportSuggestion <- c.get[Option[String]]("reportSuggestion")
      cliSessionId <- c.get[Option[String]]("cliSessionId")
      plugins <- c.get[Option[List[String]]]("plugins")
    yield Record(
      id,
      executor,
      cwd,
      project,
      taskId,
      parentSessionId,
      title,
      createdAt,
      branch,
      worktree,
      baseBranch,
      projectWorkspace,
      reportStatus,
      reportDetail,
      reportSuggestion,
      cliSessionId,
      plugins
    )

end DelegateRegistry
