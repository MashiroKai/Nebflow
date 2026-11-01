package nebflow.shared

/**
 * SessionScope —— 「主会话 / 后台 agent 会话」判据的**唯一来源**（作者 2026-11-01
 * 17:29 裁定：「我们的主 agent 的聊天记录搜索，是不搜索后台 agent 产生的消息的，
 * 只搜主会话。」⇒ 会话搜索范围收窄批）。
 *
 * == 判据（会话来源，不是消息来源）==
 * 一条 `.ui.json` 的**会话 id 族**就是它的来源：主会话由用户/主 agent 建立、在会话
 * 索引（`_index.json`）里登记、侧边栏可见；后台 agent 会话由 Delegate / SubTask /
 * Subagent / Workflow / 项目节点 / 项目分发器 / 外部 CLI 执行器 / Flow DAG 各自
 * 生成，**从不登记进索引**（`SessionStore.listSessionsIncludeUnindexed` 的
 * 「盘上独有」分支正是为它们而存在——见该函数注释）。
 *
 * 因此判据取 id 前缀而不是逐条消息的 `source` 字段：
 *   - 后台 agent 的**整条会话流**（含它自己收到的注入行）都归它，不归主 agent；
 *   - 反过来，主会话里的注入行（节点结果 / mail / 后台任务通知 …）是**主会话自己的
 *     历史**，必须照旧可搜（作者令「主会话自身的历史消息一条不少」）——所以按消息
 *     `source` 过滤会把主会话历史误伤，按会话来源过滤才是正确切面。
 *
 * == 三个收口点（全部在服务端数据层，前端零事后剔除 ⇒ 所有入口自动一致）==
 *   ① 索引入库：[[nebflow.core.search.chat.ChatSearchAdapter]]（`extractSession` /
 *      指纹 ⇒ None）——后台会话的文档**从不进** `search-index/chat.db`；
 *   ② 查询层兜底：[[nebflow.core.search.SearchIndexService]] 把
 *      [[nebflow.core.search.SearchIndexAdapter.excludedSidPrefixes]] 落进 WHERE
 *      ——旧库残留行（本批之前入库的后台会话文档）在任何查询形状下都不出结果，
 *      total 按同一谓词计数不虚高，残留随会话重建自然收敛为零；
 *   ③ 搜索范围会话清单：[[nebflow.core.SessionStore.listSearchScopeSessions]] ——
 *      旧全扫引擎（`/api/sessions?includeUnindexed=1` + 逐会话 history）拿到的
 *      清单里没有后台 agent 会话。
 *
 * == 表内容（逐值可核）==
 * 前 7 项与 [[nebflow.agent.InjectedInboxMirror.SubAgentIdPrefixes]]（= 前端
 * `web/js/utils.js#isBgAgentId`，UI 里由 bg-agent 弹窗承载的会话族）逐值同源；
 * 第 8 项 `dag-` 是 FlowDagExecutor 的 DAG 步骤会话（后台 sub-agent，索引里同样
 * 从不登记）。`team-` **不在**表内：它是 MailTool 的**前端路由标记**（
 * `team-<sessionId>` 剥前缀后指向真实会话，见 `WebSocketRoutes`），不是会话 id 族。
 *
 * 漂移门：`SessionSearchScopeSpec` 钉住「本表 ⊇ InjectedInboxMirror 表」与
 * 「前端 `isBgAgentId` 逐值 ⊆ 本表」——新增一个后台族却漏进搜索面会直接验红。
 */
object SessionScope:

  /**
   * 后台 agent 会话 id 前缀（判据见本文件头注；顺序固定，供契约门逐值对账）。
   */
  val BackgroundAgentPrefixes: List[String] = List(
    "delegate-",
    "subtask-",
    "subagent-",
    "workflow-",
    "node-",
    "dispatcher-",
    "external-",
    "dag-"
  )

  /** 是否后台 agent 会话（= 不进主 agent 聊天检索面）。 */
  def isBackgroundAgentSession(sessionId: String): Boolean =
    sessionId.nonEmpty && BackgroundAgentPrefixes.exists(sessionId.startsWith)

  /** 是否主会话（检索面唯一准入判据；空 id 恒 false——无从判定即不入面）。 */
  def isMainSession(sessionId: String): Boolean =
    sessionId.nonEmpty && !isBackgroundAgentSession(sessionId)

end SessionScope
