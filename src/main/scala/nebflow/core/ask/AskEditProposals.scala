package nebflow.core.ask

import cats.effect.IO
import nebflow.shared.NebflowLogger

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * File-bound Canvas Ask · 内存中的待审编辑提案（openspec canvas-ask-restricted-edit，
 * CAE-REQ-001）。
 *
 * 生命周期：ask_edit / ask_write 生成提案（快照 = 提案时刻的盘上全文，diff 基线
 * 也是过期判锚）→ 前端 diff 卡逐卡 Accept/Reject →
 *   Accept：仅当盘上内容仍等于快照（未被外部改动）才写盘（tmp + 原子 rename），
 *           否则显式报 stale 拒绝；写盘后提案出列。
 *   Reject：出列丢弃，零写盘。
 * 提案只在内存（进程重启即清——未决审阅本就是会话内语义）；每会话待审上限
 * `MaxPendingPerSession`（防灌爆）。
 *
 * 路径纪律（CAE-SCN-002）：仅接受与绑定文件**逐字相等**的绝对路径（穿越/他路
 * 径一律拒）；绑定文件本身是符号链接 → 拒；内容二进制（NUL 字节或非 UTF-8）→ 拒。
 * 不创建、不删除、不移动文件（ask 面没有那些动词）。
 * ═══════════════════════════════════════════════════════════════════════════ */

/** 一条待审提案（oldText = 快照基线；newText = 应用后的全文）。 */
final case class AskEditProposal(
  id: String,
  sessionId: String,
  path: String,
  fileName: String,
  oldText: String,
  newText: String,
  createdAt: Long
)

object AskEditProposals:

  /** 每会话待审上限（内存面防灌爆；超出要求先处理在列提案）。 */
  val MaxPendingPerSession: Int = 20

  /** 单文件读写帽（chars；超过直接拒——ask 面不伺候巨型文档的全量改写）。 */
  val MaxFileChars: Int = 512 * 1024

  private val logger = NebflowLogger.forName("nebflow.ask.edits")

  private val seq = new AtomicLong(0L)
  private val store = new ConcurrentHashMap[String, List[AskEditProposal]]().asScala
  private val locks = new ConcurrentHashMap[String, AnyRef]().asScala

  private def lockOf(sid: String): AnyRef = locks.getOrElseUpdate(sid, new Object)

  /** 快照读数（写侧 transform 在快照上应用，不与盘上二次竞态）。 */
  final case class Capture(path: String, fileName: String, oldText: String)

  /** 目标闸 + 快照：路径逐字相等、绝对路径、无 `..` 段、非符号链接、存在、
   * 是常规文件、文本非二进制、在尺寸帽内。 */
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
            if bytes.length > MaxFileChars then
              refuse(s"file larger than ${MaxFileChars} chars (ask edit face cap)")
            else if bytes.contains(0.toByte) then refuse("binary content (NUL byte) is not editable")
            else
              val decoder = StandardCharsets.UTF_8.newDecoder()
              try
                val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString
                Right(Capture(normalized, binding.fileName, text))
              catch
                case NonFatal(_) => refuse("binary content (not valid UTF-8) is not editable")
        catch
          case NonFatal(e) =>
            logger.warn(s"ask edit capture failed: ${e.getMessage}")
            refuse(s"could not read the bound file (${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)})")
    }
  end capture

  /** 生成提案：capture（闸+快照）→ transform（快照上算 newText）→ 入列。 */
  def propose(
    sid: String,
    binding: AskBinding,
    path: String,
    transform: String => Either[String, String]
  ): IO[Either[String, AskEditProposal]] =
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
                  val pending = store.get(sid).getOrElse(Nil)
                  if pending.size >= MaxPendingPerSession then
                    Left(s"ask edit refused: ${MaxPendingPerSession} proposals already pending for this file — accept or reject them first")
                  else
                    val proposal = AskEditProposal(
                      id = s"e${seq.incrementAndGet()}",
                      sessionId = sid,
                      path = cap.path,
                      fileName = cap.fileName,
                      oldText = cap.oldText,
                      newText = newText,
                      createdAt = System.currentTimeMillis()
                    )
                    store.update(sid, pending :+ proposal)
                    Right(proposal)
                }
        }
    }
  end propose

  /** 会话在列提案（创建序）。 */
  def pendingFor(sid: String): List[AskEditProposal] =
    store.get(sid).getOrElse(Nil)

  /** Accept：盘上仍等于快照才写盘（tmp + 原子 rename）；任何偏差 = stale 显式拒。 */
  def accept(sid: String, proposalId: String): IO[Either[String, AskEditProposal]] =
    IO.blocking {
      lockOf(sid).synchronized {
        val pending = store.get(sid).getOrElse(Nil)
        pending.find(_.id == proposalId) match
          case None =>
            Left(s"accept failed: no pending proposal $proposalId for this session")
          case Some(proposal) =>
            try
              val p = Path.of(proposal.path)
              val bytes = Files.readAllBytes(p)
              val current = new String(bytes, StandardCharsets.UTF_8)
              if current != proposal.oldText then
                Left(s"accept failed: file changed since the proposal was made (stale snapshot) — ask again for a fresh proposal: ${proposal.path}")
              else
                val tmp = Files.createTempFile(p.getParent, "ask-edit-", ".tmp")
                Files.write(tmp, proposal.newText.getBytes(StandardCharsets.UTF_8))
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                store.update(sid, pending.filterNot(_.id == proposalId))
                logger.info(s"ask edit accepted: ${proposal.id} -> ${proposal.path}")
                Right(proposal)
            catch
              case NonFatal(e) =>
                Left(s"accept failed: could not write ${proposal.path} (${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)})")
      }
    }
  end accept

  /** Reject：出列丢弃，零写盘。 */
  def reject(sid: String, proposalId: String): IO[Either[String, AskEditProposal]] =
    IO.blocking {
      lockOf(sid).synchronized {
        val pending = store.get(sid).getOrElse(Nil)
        pending.find(_.id == proposalId) match
          case None => Left(s"reject failed: no pending proposal $proposalId for this session")
          case Some(proposal) =>
            store.update(sid, pending.filterNot(_.id == proposalId))
            Right(proposal)
      }
    }
  end reject

  /** spec 注入/卫生口：清某会话在列提案。 */
  def resetForTest(sid: String): Unit = store.remove(sid)

end AskEditProposals
