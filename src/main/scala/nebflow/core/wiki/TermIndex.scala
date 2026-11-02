package nebflow.core.wiki

import cats.effect.IO

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ListBuffer

/**
 * Incremental term registry index（B5-2 文档增强腿，2026-11-01）。
 *
 * 改造前：`GET /api/wiki/term` 走 `graph(project)`——那是**整个 vault 的图谱构建**
 * （逐文件读盘 + 解析段落 + 建边），30s TTL 到期后**任何一次词条单查都触发一次
 * 全库重扫**。词条查询本该是毫秒级点查，却背着一张图谱的代价；TTL 越长越慢、
 * 越短越抖，两头都不对。
 *
 * 改造后：本索引只在**文件指纹变化时**重解析该文件（`(mtimeMillis, size)` 二元组
 * 做指纹），未变的文件复用已解析出的 `List[WikiIndex.WikiTerm]`。查询是
 * 「stat 一遍目录 → 变化文件重解析 → 在内存词条表上 selectTerm」：
 *   · 稳态（无改动）：一次 stat 走查 + 一次 selectTerm，零读盘零解析；
 *   · 单文件改动：只读、只解析那一个文件；
 *   · 删除/新增文件：条目被剔除/追加（按 rel 集合对账）。
 *
 * 与图谱腿的关系（职责边界，别搞混）：
 *   · 本索引**只服务词条单查**（`WikiService.term` 的本地快路径），不产出图谱、
 *     不参与边消解——图谱仍走 `graph()` 的 30s 缓存，两者互不污染；
 *   · 只索引**词条可能声明的文档**（md/markdown；`.html` 无块锚段落语义，
 *     `WikiIndex.termsOf` 对其恒空，扫了也是空跑）；
 *   · 范围 = 该工程的**根 vault**（子 vault 是独立注册表，与 `graph(project, None)`
 *     同口径：子 vault 根目录从走查里剪掉）。
 *
 * 线程模型：`AtomicReference` 持不可变快照，重扫在 `IO.blocking` 里做、结束后
 * 整体换快照（读侧永远看到一致的一份）。并发重扫不做互斥锁——最坏结果是同一
 * 指纹被算两次，结果相同（幂等），比给热路径加锁便宜。
 */
final class TermIndex:
  import TermIndex.*

  /** vaultKey → (rel → 指纹 + 已解析词条) */
  private val state = new AtomicReference[Map[String, Map[String, FileEntry]]](Map.empty)
  /** vaultKey → 累计重解析次数（观测面：稳态下应停止增长） */
  private val reparsedTotal = new AtomicReference[Map[String, Long]](Map.empty)

  /**
   * 对一个 vault 做增量刷新 + 词条消解。
   * @param vaultKey 缓存键（工程名 + vault rel，调用方决定粒度）
   * @param vault    本地 vault 根目录
   * @param skipRels 需要剪掉的子 vault 根（vault 相对路径）
   * @param name     查询键（词条名或别名，大小写不敏感）
   */
  def lookup(vaultKey: String, vault: os.Path, skipRels: Set[String], name: String): IO[(WikiIndex.TermSelection, Int)] =
    IO.blocking {
      val previous = state.get().getOrElse(vaultKey, Map.empty)
      val (next, reparsed, changed) = refresh(vault, skipRels, previous)
      // 换快照的判据是「**集合或内容变了**」，不是「重解析过」——纯删除的刷新
      // 重解析数是 0，若只认重解析数，被删文件的条目会永远留在表里（TI-5 抓到的
      // 真 bug：删文件后仍能查到已不存在的词条）。
      if changed then state.set(state.get() + (vaultKey -> next))
      if reparsed > 0 then
        reparsedTotal.updateAndGet(m => m + (vaultKey -> (m.getOrElse(vaultKey, 0L) + reparsed)))
      val terms = next.valuesIterator.flatMap(_.terms).toList
      (WikiIndex.selectTerm(name, terms), reparsed)
    }

  /** 观测面：`(文件数, 词条数, 累计重解析次数)`。 */
  def stats(vaultKey: String): (Int, Int, Long) =
    val entries = state.get().getOrElse(vaultKey, Map.empty)
    (entries.size, entries.valuesIterator.map(_.terms.size).sum, reparsedTotal.get().getOrElse(vaultKey, 0L))

  /** 走查 + 指纹对账；返回新快照、实际重解析的文件数、条目集合是否变化。 */
  private def refresh(vault: os.Path, skipRels: Set[String], previous: Map[String, FileEntry]): (Map[String, FileEntry], Int, Boolean) =
    if !os.isDir(vault) then (Map.empty, 0, previous.nonEmpty)
    else
      val next = scala.collection.mutable.LinkedHashMap.empty[String, FileEntry]
      var reparsed = 0
      eachDocFile(vault, skipRels) { f =>
        val rel = f.relativeTo(vault).toString.replace('\\', '/')
        val stamp = (os.mtime(f), os.size(f))
        previous.get(rel) match
          case Some(e) if e.stamp == stamp => next(rel) = e
          case _ =>
            reparsed += 1
            next(rel) = FileEntry(stamp, parseTerms(rel, f))
      }
      val changed = reparsed > 0 || next.keySet != previous.keySet
      (next.toMap, reparsed, changed)

  private def parseTerms(rel: String, f: os.Path): List[WikiIndex.WikiTerm] =
    if os.size(f) > WikiIndex.MaxDocBytes then Nil
    else
      try
        val parsed = WikiIndex.parseDoc(rel, os.read(f))
        WikiIndex.termsOf(rel, parsed)
      catch case _: Exception => Nil   // 读失败 = 该文件无词条（下轮指纹不变则不再重试）

  /** 深度/文件数有界的走查（与 `WikiService.listVaultFiles` 同预算，剪掉子 vault）。 */
  private def eachDocFile(vault: os.Path, skipRels: Set[String])(f: os.Path => Unit): Unit =
    var seen = 0
    def walk(dir: os.Path, depth: Int): Unit =
      if depth <= WikiIndex.MaxScanDepth && seen < WikiIndex.MaxFilesPerVault then
        os.list(dir).foreach { p =>
          if os.isDir(p) then
            val rel = p.relativeTo(vault).toString.replace('\\', '/')
            // 子 vault 根（`<sub>/docs`，与 graph 的 per-vault 隔离同口径）一律剪掉：
            // 子 vault 有自己的注册表，父 vault 的词条表不吸收它。
            if !skipRels.contains(rel) && p.last != WikiIndex.VaultDirName then walk(p, depth + 1)
          else if TermIndex.DocExtensions.contains(WikiIndex.extOf(p.last)) then
            seen += 1
            f(p)
        }
    walk(vault, 0)

object TermIndex:
  /** 词条只可能声明在 markdown 里（`.html` 无块锚段落语义 ⇒ `termsOf` 恒空）。 */
  val DocExtensions: Set[String] = Set(".md", ".markdown")

  /** 一个文件的指纹 + 它声明的词条。 */
  final case class FileEntry(stamp: (Long, Long), terms: List[WikiIndex.WikiTerm])

  /** 进程级单例（索引跨请求共享；与 `WikiService.current` 同生命周期口径）。 */
  private val shared = new TermIndex
  def current: TermIndex = shared
