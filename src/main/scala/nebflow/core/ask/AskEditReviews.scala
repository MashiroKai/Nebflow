package nebflow.core.ask

import cats.effect.IO
import com.github.difflib.DiffUtils.diff
import nebflow.shared.NebflowLogger

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * File-bound Canvas Ask · 内联审阅账本（canvas-inline-diff-review 批，
 * 2026-11-16；调研结论见 delivery/canvas-inline-diff-review/research-inline-diff.md）。
 *
 * 模型 = Cursor 式「先应用、后审阅」（作者原话「agent编辑都是直接成功的。它
 * 可以每条同意 也可以一起统一」）：
 *   ask_edit / ask_write 调用即**直接落盘**（capture 闸全保留），同时入账一条
 *   待审变更；WS `askEditApplied` 推**聚合审阅态**（baseText=审阅基线、
 *   currentText=盘上现文、hunks=可审块表）→ 前端在 canvas 文件面渲染原文档
 *   内联 diff。审阅动作（WsAskHandlers 域）：
 *     accept（缺省 hunkId）= 全部接受：清账零写盘；
 *     accept(hunkId)       = 逐块接受：基线前移（该块标记已审，零写盘）；
 *     reject（缺省 hunkId）= 全部拒绝：回写 baseText（整体回退）；
 *     reject(hunkId)       = 逐块拒绝：回写「current 去掉该块」；
 *     dismiss              = 保留盘上现状、清账（冲突逃生口，零写盘）。
 *
 * 过期/并发闸（承接 canvas-ask-restricted-edit 的 stale 纪律）：任何 resolve
 * 动作前先验「盘上内容 == currentText」——审阅期间文件被外部改动（agent 他
 * 腿/用户编辑器/他机）即显式 stale 拒（Left 带 retry 指引），**绝不静默覆
 * 盖**。外部改动后的**下一次** ask_edit/ask_write 落盘前会把基线 rebase 到
 * 外部改后的内容（旧基线对应的回退已不安全——回写会抹掉外部改动）。
 *
 * 路径纪律（承接 CAE-SCN-002）：仅接受与绑定文件**逐字相等**的绝对路径；
 * 绑定文件是符号链接 → 拒；内容二进制 → 拒；不创建、不删除、不移动文件。
 * 账本只在内存（进程重启即清——未决审阅是会话内语义）。
 * ═══════════════════════════════════════════════════════════════════════════ */

/** 一条已落盘的变更（审计行；审阅面看的是聚合态）。 */
final case class AskEditChange(
  id: String,
  sessionId: String,
  path: String,
  fileName: String,
  oldText: String,
  newText: String,
  createdAt: Long
)

/**
 * 一个可审块（0 起行号，按 base/current 各自文本切片；跨度含并块夹缝的
 * 未变行——夹缝两侧同文，回退无害；added/removed 仅计数面）。
 */
final case class AskEditHunk(
  id: String,
  oldStart: Int,
  oldCount: Int,
  newStart: Int,
  newCount: Int
):
  def added: Int = newCount
  def removed: Int = oldCount

/** 一个 ask 会话（= 一个绑定文件）的聚合审阅态。 */
final case class AskEditReview(
  path: String,
  fileName: String,
  baseText: String,
  currentText: String,
  changes: List[AskEditChange],
  updatedAt: Long
):
  def hunks: List[AskEditHunk] = AskEditReviews.hunksOf(baseText, currentText)

object AskEditReviews:

  /** 每会话在审变更上限（内存面防灌爆；超出要求先了结在审变更）。 */
  val MaxPendingChanges: Int = 20

  /** 单文件读写帽（chars；超过直接拒）。 */
  val MaxFileChars: Int = 512 * 1024

  /** 相邻 delta 间未变行 ≤ 此值并入同一可审块（与 unified diff context=2 直觉一致）。 */
  val HunkMergeGap: Int = 2

  private val logger = NebflowLogger.forName("nebflow.ask.edits")

  private val seq = new AtomicLong(0L)
  private val store = new ConcurrentHashMap[String, AskEditReview]().asScala
  private val locks = new ConcurrentHashMap[String, AnyRef]().asScala

  private def lockOf(sid: String): AnyRef = locks.getOrElseUpdate(sid, new Object)

  /** resolve 结果：仍在审（余下态）或已了结。 */
  sealed trait ResolveResult

  object ResolveResult:
    final case class Kept(review: AskEditReview) extends ResolveResult
    final case class Cleared(path: String) extends ResolveResult

  // ── capture：目标闸 + 快照（承接 CAE 逐字判据，语义零改动）────────────────

  final case class Capture(path: String, fileName: String, oldText: String)

  def capture(sid: String, binding: AskBinding, path: String): IO[Either[String, Capture]] =
    IO.blocking {
      val target = binding.file
      val normalized = Option(path).map(_.trim).getOrElse("")
      def refuse(why: String): Left[String, Capture] =
        Left(s"ask edit refused: $why (bound file: $target)")
      if sid != binding.id then refuse("session does not own this binding")
      else if normalized.isEmpty then refuse("path is required")
      else if !normalized.startsWith("/") then refuse("path must be absolute")
      else if normalized.split('/').contains("..") then refuse("path traversal (`..`) is not accepted")
      else if normalized != target then refuse("only the bound file of this ask session is editable")
      else
        try
          val p: Path = Path.of(normalized)
          if !Files.exists(p) then refuse("bound file no longer exists")
          else if Files.isSymbolicLink(p) then refuse("symbolic links are not editable")
          else if !Files.isRegularFile(p) then refuse("bound file is not a regular file")
          else
            val bytes = Files.readAllBytes(p)
            if bytes.length > MaxFileChars then refuse(s"file larger than ${MaxFileChars} chars (ask edit face cap)")
            else if bytes.contains(0.toByte) then refuse("binary content (NUL byte) is not editable")
            else
              val decoder = StandardCharsets.UTF_8.newDecoder()
              try
                val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString
                Right(Capture(normalized, binding.fileName, text))
              catch case NonFatal(_) => refuse("binary content (not valid UTF-8) is not editable")
        catch
          case NonFatal(e) =>
            logger.warn(s"ask edit capture failed: ${e.getMessage}")
            refuse(s"could not read the bound file (${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)})")
      end if
    }
  end capture

  // ── 行机制（split 语义 = DiffUtil.splitLines 同式；core.ask 不引 core.tools，
  //    机制内联保持模块边界）──────────────────────────────────────────────────

  private def splitLines(content: String): List[String] =
    content.split("\\r?\\n", -1).toList

  private def detectLineSep(content: String): String =
    if content.contains("\r\n") then "\r\n" else "\n"

  /**
   * tmp + 原子 rename 写盘（分隔符随基线检测——split 已吃掉 \r\n，join 用原
   * 分隔符还原；混合退化为主导分隔符，DiffUtil.writeFile 同语义）。
   */
  private def writeAtomic(path: Path, content: String, lineSep: String): Unit =
    val tmp = Files.createTempFile(path.getParent, "ask-edit-", ".tmp")
    Files.write(tmp, content.getBytes(StandardCharsets.UTF_8))
    Files.move(
      tmp,
      path,
      java.nio.file.StandardCopyOption.REPLACE_EXISTING,
      java.nio.file.StandardCopyOption.ATOMIC_MOVE
    )

  /**
   * 可审块表：difflib deltas → 相邻未变行 ≤ HunkMergeGap 并块。**确定性单源**
   * ——askEditApplied 推送与 resolve 重算同一函数，同输入必同输出（id 序
   * h0..hN 稳定，前端逐块动作的 hunkId 由此对齐）。纯函数（局部可变量）。
   */
  def hunksOf(base: String, current: String): List[AskEditHunk] =
    val oldLines = splitLines(base).asJava
    val newLines = splitLines(current).asJava
    val deltas = diff(oldLines, newLines).getDeltas.asScala.toList.sortBy(_.getSource.getPosition)
    val out = ListBuffer.empty[AskEditHunk]
    var prevSrcEnd = -1
    deltas.foreach { delta =>
      val srcPos = delta.getSource.getPosition
      val srcEnd = srcPos + delta.getSource.size()
      val tgtPos = delta.getTarget.getPosition
      val tgtEnd = tgtPos + delta.getTarget.size()
      if prevSrcEnd >= 0 && srcPos - prevSrcEnd <= HunkMergeGap then
        // 并块：把上一块跨度拉到本 delta 尾（夹缝未变行进上下文，两侧同文，
        // 回退无害）。
        val prev = out.last
        out(out.length - 1) = prev.copy(
          oldCount = srcEnd - prev.oldStart,
          newCount = tgtEnd - prev.newStart
        )
      else out += AskEditHunk(s"h${out.length}", srcPos, srcEnd - srcPos, tgtPos, tgtEnd - tgtPos)
      prevSrcEnd = srcEnd
    }
    out.toList
  end hunksOf

  /** apply：capture → transform → **直接落盘** → 入账聚合审阅态。 */
  def applyChange(
    sid: String,
    binding: AskBinding,
    path: String,
    transform: String => Either[String, String]
  ): IO[Either[String, AskEditReview]] =
    capture(sid, binding, path).flatMap {
      case Left(why) => IO.pure(Left(why))
      case Right(cap) =>
        IO.blocking {
          transform(cap.oldText) match
            case Left(why) => Left(why)
            case Right(newText) =>
              if newText == cap.oldText then Left("ask edit refused: no change (old and new content are identical)")
              else if newText.length > MaxFileChars then
                Left(s"ask edit refused: new content exceeds ${MaxFileChars} chars cap")
              else
                lockOf(sid).synchronized {
                  val prev = store.get(sid)
                  val chain = prev.map(_.changes).getOrElse(Nil)
                  if chain.size >= MaxPendingChanges then
                    Left(s"ask edit refused: ${MaxPendingChanges} changes already awaiting review — resolve them first")
                  else
                    try
                      writeAtomic(Path.of(cap.path), newText, detectLineSep(cap.oldText))
                      val change = AskEditChange(
                        id = s"e${seq.incrementAndGet()}",
                        sessionId = sid,
                        path = cap.path,
                        fileName = cap.fileName,
                        oldText = cap.oldText,
                        newText = newText,
                        createdAt = System.currentTimeMillis()
                      )
                      // 基线续链：盘上仍 == 上一链顶 ⇒ 基线不动（聚合 diff 跨多次
                      // 编辑）；外部改动过 ⇒ rebase 到外部改后内容。
                      val baseText = prev match
                        case Some(p) if p.currentText == cap.oldText => p.baseText
                        case _ => cap.oldText
                      val review = AskEditReview(
                        path = cap.path,
                        fileName = cap.fileName,
                        baseText = baseText,
                        currentText = newText,
                        changes = (chain :+ change).takeRight(MaxPendingChanges),
                        updatedAt = change.createdAt
                      )
                      store.update(sid, review)
                      logger.info(s"ask edit applied: ${change.id} -> ${cap.path}")
                      Right(review)
                    catch
                      case NonFatal(e) =>
                        Left(
                          s"ask edit failed: could not write ${cap.path} (${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)})"
                        )
                  end if
                }
        }
    }
  end applyChange

  /** 在列审阅态（askState 回放面）。 */
  def reviewFor(sid: String): Option[AskEditReview] = store.get(sid)

  /** 过期判锚：盘上内容是否仍等于审阅 current（不等 = 审阅期间被外部改动）。 */
  private def diskMatches(review: AskEditReview): Option[String] =
    try
      val disk = new String(Files.readAllBytes(Path.of(review.path)), StandardCharsets.UTF_8)
      if disk == review.currentText then None
      else
        Some(
          s"file changed since the review began (stale) — the action was NOT applied to avoid overwriting. " +
            s"Retry once the file settles, dismiss the review to keep the on-disk state, or ask again: ${review.path}"
        )
    catch
      case NonFatal(e) =>
        Some(s"could not read ${review.path} (${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)})")

  /**
   * 全部/逐块接受：hunkId=None 全收（清账零写盘）；Some(h)=该块标记已审
   * （基线前移，零写盘）。接受前同验盘上 == current——接受动作对过期审阅
   * 也必须显式冲突（不静默盖章一个已不成立的 diff）。
   */
  def accept(sid: String, hunkId: Option[String]): IO[Either[String, ResolveResult]] =
    IO.blocking {
      lockOf(sid).synchronized {
        store.get(sid) match
          case None => Left("accept failed: no pending review for this session")
          case Some(review) =>
            diskMatches(review) match
              case Some(why) => Left(why)
              case None =>
                hunkId match
                  case None =>
                    store.remove(sid)
                    Right(ResolveResult.Cleared(review.path))
                  case Some(hid) =>
                    review.hunks.find(_.id == hid) match
                      case None => Left(s"accept failed: no such hunk '$hid' in the pending review")
                      case Some(hunk) =>
                        val oldL = splitLines(review.baseText)
                        val newL = splitLines(review.currentText)
                        val newBase = (
                          oldL.slice(0, hunk.oldStart)
                            ++ newL.slice(hunk.newStart, hunk.newStart + hunk.newCount)
                            ++ oldL.slice(hunk.oldStart + hunk.oldCount, oldL.length)
                        ).mkString(detectLineSep(review.baseText))
                        val updated = review.copy(baseText = newBase, updatedAt = System.currentTimeMillis())
                        if updated.hunks.isEmpty then
                          store.remove(sid)
                          Right(ResolveResult.Cleared(review.path))
                        else
                          store.update(sid, updated)
                          Right(ResolveResult.Kept(updated))
      }
    }
  end accept

  /**
   * 全部/逐块拒绝：hunkId=None 整体回退（回写 baseText）；Some(h)=回写
   * 「current 去掉该块」。写前同验盘上 == current——不等即显式 stale 拒，
   * 绝不覆盖外部改动。
   */
  def reject(sid: String, hunkId: Option[String]): IO[Either[String, ResolveResult]] =
    IO.blocking {
      lockOf(sid).synchronized {
        store.get(sid) match
          case None => Left("reject failed: no pending review for this session")
          case Some(review) =>
            diskMatches(review) match
              case Some(why) => Left(why)
              case None =>
                hunkId match
                  case None =>
                    try
                      writeAtomic(Path.of(review.path), review.baseText, detectLineSep(review.baseText))
                      store.remove(sid)
                      logger.info(s"ask edit review rejected (all reverted): ${review.path}")
                      Right(ResolveResult.Cleared(review.path))
                    catch
                      case NonFatal(e) =>
                        Left(
                          s"reject failed: could not write ${review.path} (${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)})"
                        )
                  case Some(hid) =>
                    review.hunks.find(_.id == hid) match
                      case None => Left(s"reject failed: no such hunk '$hid' in the pending review")
                      case Some(hunk) =>
                        val oldL = splitLines(review.baseText)
                        val newL = splitLines(review.currentText)
                        val newCurrent = (
                          newL.slice(0, hunk.newStart)
                            ++ oldL.slice(hunk.oldStart, hunk.oldStart + hunk.oldCount)
                            ++ newL.slice(hunk.newStart + hunk.newCount, newL.length)
                        ).mkString(detectLineSep(review.baseText))
                        try
                          writeAtomic(Path.of(review.path), newCurrent, detectLineSep(review.baseText))
                          val updated = review.copy(currentText = newCurrent, updatedAt = System.currentTimeMillis())
                          if updated.hunks.isEmpty then
                            store.remove(sid)
                            Right(ResolveResult.Cleared(review.path))
                          else
                            store.update(sid, updated)
                            Right(ResolveResult.Kept(updated))
                        catch
                          case NonFatal(e) =>
                            Left(
                              s"reject failed: could not write ${review.path} (${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)})"
                            )
      }
    }
  end reject

  /** dismiss：保留盘上现状、清账（冲突逃生口，零写盘）。 */
  def dismiss(sid: String): IO[Either[String, ResolveResult]] =
    IO.blocking {
      lockOf(sid).synchronized {
        store.get(sid) match
          case None => Left("dismiss failed: no pending review for this session")
          case Some(review) =>
            store.remove(sid)
            Right(ResolveResult.Cleared(review.path))
      }
    }

  /** spec 注入/卫生口：清某会话在审账。 */
  def resetForTest(sid: String): Unit = store.remove(sid)

end AskEditReviews
