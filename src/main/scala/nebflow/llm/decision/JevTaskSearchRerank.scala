package nebflow.llm.decision

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.index.TaskSearchRerank
import nebflow.core.jev.{JevAllocation, JevAllocatorPort}
import nebflow.shared.NebflowLogger

/**
 * TaskSearch 的 Jev 重排执行体（task-rag 批 #1227 / §Batch 7「可选 Jev 重排」；
 * ⑦「Jev 优先＋兜底」家族在检索面的同族落地）。
 *
 * ==分层与接缝==
 * core 只见 `TaskSearchRerank` 注册器（core 零 llm import 纪律——
 * DelegateJudgePort 先例）；本件在 `llm.decision` 侧构造 Face；注册点 =
 * `agent.SharedResources` 构造行。🔴 本件是**全新文件**：`JevAllocator.scala`
 * 本体零触碰（#1226 并行批的触碰面，接缝避让）；接口即现读现状
 * `JevAllocatorPort.allocate(taskText, catalog)`，未配 Jev ⇒ `fromConfig` =
 * `None` ⇒ Face 恒 `None`（跳过重排照常返回——原文「可选」的缺省路径）。
 *
 * ==接口映射（现状接口的机械复用，语义适配如实申报）==
 * `allocate` 的原生语义是能力包成员判定（need_ 问题对 + ≥0.5 阈值），不是相关
 * 性排序。重排映射：query = taskText；候选条目 = `CatalogEntry(name = 净化后
 * entryId, description = 条目文本)` —— 判据句读作「该命中是否查询主旨所必需」，
 * 入选 = 相关。产出序 = **入选者在前（原余弦序），未入选者续后（原余弦序）**；
 * 返回全集排列，绝不删命中（重排是纯序换）。`FellBack` / 异常 ⇒ `None`（跳过）。
 *
 * ==判据句语义差异的申报==
 * JevAllocator 的判据句是固定文案（能力包措辞），本映射不改动它（改文案 =
 * 方法论变更，越 #1226 面）。措辞与检索语义的差异在此显式申报；若作者裁定
 * 不适，删除本注册行即整腿退役（零残留）。
 */
object JevTaskSearchRerank:

  private val logger = NebflowLogger.forName("nebflow.index.rerank")

  /** entryId → 判据句可读的名字（`task:12` → `task_12`；白名单字符集外替换）。 */
  private[decision] def sanitizeName(entryId: String): String =
    entryId.replaceAll("[^a-zA-Z0-9._-]", "_")

  /** 恒跳过的 Face（Jev 未配时的注册形态——语义与「未注册」一致）。 */
  def skipFace: TaskSearchRerank.Face = (query, candidates) => IO.pure(None)

  /** 生产构造：热读 `jev` 配置 ⇒ 在册 = 映射 Face；未配 = 恒跳过。 */
  def face(configPath: Option[String] = None): TaskSearchRerank.Face =
    faceFor(JevAllocator.fromConfig(health = None, configPath = configPath))

  /**
   * 热读形态（生产注册用，`SharedResources` 安装行）：每次 rerank 现读 `jev`
   * 配置（`JevConfigReader` / `PluginsConfig.enabled` 同款——配置改动即席生效，
   * 无重启门槛）；未配 = `faceFor(None)` = 恒 `None`（跳过重排照常返回）。
   */
  def hotFace(configPath: Option[String] = None): TaskSearchRerank.Face =
    (query: String, candidates: List[TaskSearchRerank.Candidate]) =>
      if candidates.isEmpty then IO.pure(None)
      else faceFor(JevAllocator.fromConfig(health = None, configPath = configPath)).rerank(query, candidates)

  /** 映射核（spec 以 stub port 注入驱动）：`Some(allocator)` = 机械映射形态。 */
  def faceFor(allocator: Option[JevAllocatorPort]): TaskSearchRerank.Face =
    allocator match
      case None => skipFace
      case Some(a) =>
        (query: String, candidates: List[TaskSearchRerank.Candidate]) =>
          if candidates.isEmpty then IO.pure(None)
          else
            val catalog = candidates.map(c => nebflow.core.jev.CatalogEntry(sanitizeName(c.id), c.text))
            a.allocate(query, catalog).map {
              case JevAllocation.Allocated(selected) =>
                val sel = selected.toSet
                // 入选者在前、未入选者续后（两组各自保持候选原序）；全集排列。
                val inSet = candidates.collect { case c if sel.contains(sanitizeName(c.id)) => c.id }
                val rest = candidates.map(_.id).filterNot(id => sel.contains(sanitizeName(id)))
                Some(inSet ++ rest)
              case _: JevAllocation.FellBack => None
            }.handleErrorWith { e =>
              IO(logger.warnSync(s"jev rerank allocate failed (${e.getClass.getSimpleName}) — skipped")).as(None)
            }
  end faceFor

end JevTaskSearchRerank
