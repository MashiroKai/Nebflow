package nebflow.core.search

/**
 * SearchIndexAdapter —— 数据层检索骨架的 adapter 注册面（设计稿 §4.1 接口形态
 * 的落地；datalayer-spec 批 2026-10-30，C1）。
 *
 * 一个数据面（chat / files / logs / wiki…）= 注册一个 adapter，不复制一套链路。
 * 索引层只消费这个窄接口：真源在哪、怎么抽文档、怎么指纹，全部由 adapter 回答。
 *
 * == 通用纪律（设计稿 §4.1 三条，每个接入场景必须满足）==
 *   1. 索引是派生物，真源永不被写（损坏 = 删库重建，无恢复负担）。
 *   2. 增量靠写路径钩子，不靠轮询；兜底 = 惰性对账（指纹不一致的会话查询前重建）。
 *   3. 查询必带分片谓词（类型片/时间片/会话范围至少一个）；查询侧负责把关。
 */
trait SearchIndexAdapter:

  /** 面 id（"chat" | "files" | …）——索引文件名与查询 API 路径段。 */
  def face: String

  /**
   * 会话级指纹（增量对账键）：行数 + 内容 hash 前缀。内容未变 ⇒ 指纹不变 ⇒
   * 跳过重建。指纹必须廉价（adapter 自定实现：mtime/size 或抽样 hash 均可，
   * 但**错进方向只允许漏重建，不允许错重建后漏更新**——漏重建由查询侧
   * 「会话 mtime 落后于索引水位」二次闸门兜住）。
   */
  def sessionFingerprint(sessionId: String): Option[String]

  /**
   * 抽取一个会话的检索文档流（只读真源；实现必须是纯读取，零写入）。
   * 返回 None = 该会话没有检索面（无 .ui.json / 全空），索引侧原样删旧。
   * `forceFull = false` 时允许 adapter 走增量快路（实现自选；缺省实现忽略之）。
   */
  def extractSession(sessionId: String, forceFull: Boolean = false): Option[List[SearchIndexService.IndexDoc]]

  /** 会话真源 mtime（ms）——惰性对账水位（「索引落后于真源」判向）。缺省 None。 */
  def sessionMtimeOpt(sessionId: String): Option[Long] = None

  /** 会话月时间片键（§4.2）：当月 = "current"，历史 = yyyymm。缺省 ""。 */
  def monthKey(sessionId: String): String = ""

  /**
   * 检索面**排除**的会话 id 前缀（缺省 Nil = 不排除）。
   *
   * 语义 = 「这些族的会话一律不出结果」；由 adapter 声明、索引层统一执行两处：
   *   ① 入库面：adapter 自己在 `extractSession` / `sessionFingerprint` 里按同一
   *      判据回答 None（本 trait 不代劳，抽取口径是 adapter 的地盘）；
   *   ② 查询面：[[SearchIndexService.query]] 把本表落进 WHERE（`NOT LIKE '前缀%'`）
   *      ——**旧库残留行兜底**：本表启用之前入库的文档在任何查询形状下都不出
   *      结果，total 用同一谓词计数（不虚高、翻页不塌），残留随会话重建收敛为零。
   *
   * 单点能力、多入口一致：查询 API（`/api/search/chat`）与其上所有前端入口共用
   * 这一处谓词，前端零事后剔除。
   */
  def excludedSidPrefixes: List[String] = Nil
