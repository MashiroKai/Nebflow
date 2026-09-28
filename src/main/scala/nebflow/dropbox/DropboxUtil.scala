package nebflow.dropbox

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import nebflow.shared.{AttachContract, FileTransfer, NebflowLogger}

import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Pure utility functions for Dropbox file handling. Extracted for testability. */
object DropboxUtil:
  private val logger = NebflowLogger.forName("nebflow.dropbox")

  /** Stream bytes to a file while computing SHA-256. Returns the hex hash. */
  def streamToFileWithHash(stream: Stream[IO, Byte], path: os.Path): IO[String] =
    IO.blocking {
      if os.exists(path) then os.remove(path)
      ()
    } *> {
      val digest = MessageDigest.getInstance("SHA-256")
      stream.chunks
        .evalTap { chunk =>
          IO.blocking {
            val bytes = chunk.toArray
            digest.update(bytes)
            os.write.append(path, bytes)
          }
        }
        .compile
        .drain *> IO {
        digest.digest().map(b => f"$b%02x").mkString
      }
    }

  /** Compute SHA-256 of an existing file. Returns the hex hash. */
  def hashFile(path: os.Path): IO[String] =
    IO.blocking {
      val digest = MessageDigest.getInstance("SHA-256")
      val bytes = os.read.bytes(path)
      digest.update(bytes)
      digest.digest().map(b => f"$b%02x").mkString
    }

  /**
   * Stream bytes to a file while computing SHA-256, **自带上限**：累计字节一旦超过
   * `maxBytes` 立即中止（不再继续写盘）并返回结构化错误（`actual` 实际值 + `limit`）。
   *
   * WHY：声明大小会撒谎 —— offer 阶段按 `fileSize` 过闸后，真实 body 仍可能超限。
   * 闸位必须在**字节流上**再判一次，否则上限可被「少报 size」绕过（fail-fast 面）。
   *
   * ⚠️ `actual` 的量纲（R7）：超限是**流式提前中止**，那一刻流的总长度尚不可知 ——
   * 错误体里的 `actual` = **已接受并落盘的字节数（下界）**，不是真实流长。
   * 例：1,073,741,825 B 的流在 1,073,737,728 B 处止步 ⇒ `actual = 1073737728`
   * （`limit = 1073741824`；止步点恒 ≤ limit，与 limit 的差 < 那个被拒的块的字节数）。
   * 文案里逐字注明「lower bound」，避免被读成「实际流长 = 99,942,400」。
   */
  def streamToFileWithHashBounded(
    stream: Stream[IO, Byte],
    path: os.Path,
    maxBytes: Long
  ): IO[Either[AttachContract.AttachError, String]] =
    val tooLarge = new java.io.IOException("STREAM_TOO_LARGE")
    (IO.blocking {
      if os.exists(path) then os.remove(path)
      ()
    } *> {
      val digest = MessageDigest.getInstance("SHA-256")
      // 计数器：单流串行消费（每个传输一个流），var 足够且避免多余 Ref 分配。
      var totalWritten = 0L
      stream.chunks
        .evalMap { chunk =>
          val bytes = chunk.toArray
          if totalWritten + bytes.length > maxBytes then IO.raiseError(tooLarge)
          else
            IO.blocking {
              digest.update(bytes)
              os.write.append(path, bytes)
              totalWritten += bytes.length
            }
        }
        .compile
        .drain
        // ⚠️ 终判必须在 IO 运行期（`flatMap` 里）——`io *> { if … }` 的 `{…}` 是急求值，
        // 会在构造期看到 totalWritten == 0，把上限判定整个变成死代码（自环测试同族缺陷）。
        .flatMap { _ =>
          if totalWritten > maxBytes then
            // 防御性分支（逐块前置判定已在上游拦下超限块 ⇒ 实际不可达）；此处 `totalWritten`
            // 若真超限，它是精确值，与 tooLarge 分支的「下界」语义不同，故文案不加 lower bound。
            IO.pure(
              Left(
                AttachContract.AttachError(
                  AttachContract.Codes.AttachTooLarge,
                  s"Attachment too large: $totalWritten bytes exceeds the $maxBytes-byte limit",
                  phase = "transfer",
                  actual = Some(totalWritten),
                  limit = Some(maxBytes)
                )
              )
            )
          else IO.pure(Right(digest.digest().map(b => f"$b%02x").mkString))
        }
    }).handleErrorWith {
      case e if e eq tooLarge =>
        writtenBytesSafe(path).map { total =>
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.AttachTooLarge,
              // R7：`actual` 是**下界**（已接受字节），不是真实流长 —— 流在此处被提前中止，
              // 剩余字节从未被读出。文案逐字注明，避免被读成精确值。
              s"Attachment too large: already accepted $total bytes (lower bound — the stream was aborted at the first chunk that would exceed the limit) exceeds the $maxBytes-byte limit",
              phase = "transfer",
              actual = Some(total),
              limit = Some(maxBytes)
            )
          )
        }
      case e =>
        IO.pure(
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.InvalidArgument,
              s"Failed to receive attachment stream: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}",
              phase = "transfer"
            )
          )
        )
    }

  end streamToFileWithHashBounded

  private def writtenBytesSafe(path: os.Path): IO[Long] =
    IO.blocking(if os.exists(path) then os.size(path) else 0L).handleErrorWith(_ => IO.pure(0L))

  // ===== 落名收口（dropnam 批）—— **唯一算名点** =====
  //
  // 三契约（root）：
  //   ① 最终名字**只在一处算**  ⇒ 本区块的 `finalNameCandidate`（候选序）+ `reserveAndPlace`（**占据**）；
  //   ② 撞名**必改名且既有件零删除** ⇒ 唯一性判据 = 内核 `link(2)`（EEXIST ⇒ 换下一候选），
  //      既不是 `os.exists` 预判、也不是时间戳推断 ⇒ 判定与占名是**同一个原子操作**（无 TOCTOU）；
  //   ③ 通报名与落盘名**同源同一次观测** ⇒ 调用方只能用 `reserveAndPlace` 返回的路径，
  //      禁任何第二处名字推断（`DropboxService.landedPathFor` 的预测分支已删除）。

  /** 「缺省 Downloads」的**字面形态** —— 与历史 relay 腿写法逐字一致（`~` 由**接收端**展开）。 */
  val RelayDefaultDirTilde: String = "~/Downloads"

  /**
   * 第 k 候选名（k=0 ⇒ 裸名；k=1 ⇒ 历史冲突口径；k≥2 ⇒ 序号后缀）。**纯函数，不触盘**。
   *
   * k=1 与历史口径**逐字符一致** ⇒ 历史名形态、既有 spec 的形态断言全部不变；
   * 新序号后缀（`_<k>`）只在**第二次以上**冲突时出现。
   */
  def finalNameCandidate(fileName: String, k: Int, now: ZonedDateTime): String =
    if k == 0 then fileName
    else
      val dot = fileName.lastIndexOf('.')
      val (base, ext) = if dot > 0 then (fileName.substring(0, dot), fileName.substring(dot)) else (fileName, "")
      val ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(now)
      if k == 1 then s"${base}_$ts$ext" else s"${base}_${ts}_$k$ext"

  /**
   * 接收端 temp 名（`.<名>.dropbox-<tid8>`）—— **唯一 temp 名生成器**：
   * P2P 腿的派生 temp（`DropboxService.derivedReceiverTempPath`）与 relay 腿的落点
   * （[[relayLandingPath]]）共用本函数 ⇒ 两侧名字同源。
   */
  def receiverTempName(fileName: String, transferId: String): String =
    s".$fileName.dropbox-${transferId.take(8)}"

  /**
   * relay 腿落点（**纯函数**，两侧同名同形）。
   *
   * 新形态（对端 = **新接收端**，等级 ≥ [[AttachContract.ProtoRelayTemp]]）= 同一 transfer 的
   * **确定性 temp 名** ⇒ ① 不可能命中他人既有件；② 经接收端 `commitTempFile` 得到与 P2P 腿
   * **同一套**落名/占据/回读（撞名 ⇒ 改名保留）；③ 顺带修掉「relay 腿无视 `targetDir`」。
   *
   * 🔴 `else` 分支是**旧接收端兼容回退，不是「关掉修复」的开关**（作者 2026-09-19 终裁①）：
   * 旧接收端没有 `Absent ⇒ 派生 temp` 的 commit 入口，改写真 temp 名会让字节留在隐藏 temp
   * 而**永不现身**。门只判**对端**等级 ⇒ 新接收端上的每条路径都走新形态（无例外）。
   *
   * 落点目录：接收端裁定的 `targetDir`（**已获接受**时才用；被拒 ⇒ `targetDirCode` 非空 ⇒ 回到缺省）
   * 否则 `~/Downloads`（`~` 由**接收端** `expandTilde` 展开成接收端 home）。
   */
  def relayLandingPath(t: FileTransfer): String =
    if !peerSupportsRelayTemp(t) then s"$RelayDefaultDirTilde/${t.fileName}" // 旧接收端：逐字节同今天
    else
      val name = receiverTempName(t.fileName, t.transferId)
      val acceptedDir =
        t.targetDir.map(_.trim).filter(_.nonEmpty).filter(d => t.targetDirCode.isEmpty && d.startsWith("/"))
      acceptedDir match
        case Some(dir) => s"$dir/$name"
        case None => s"$RelayDefaultDirTilde/$name"

  /** 对端（**接收端**）是否支持 relay 落点收口 —— 唯一判据点（`put` / `probe` / 归属 token 共用）。 */
  def peerSupportsRelayTemp(t: FileTransfer): Boolean =
    t.peerProto.exists(_ >= AttachContract.ProtoRelayTemp)

  /** 一次「占据」尝试的四种结局（显式化；禁 null 哨兵）。 */
  private enum OccupyOutcome:
    /** 硬链接已建立：`p` 与 temp 同一 inode，内容已完整、零占位残留。 */
    case Occupied(path: os.Path)

    /** 0 字节占位已用 `O_EXCL` 原子建立（硬链接不可用时的回退）⇒ 调用方须把 temp 搬上来。 */
    case Placeholder(path: os.Path)
    case Collision
    case Failed(reason: String)

  /**
   * **唯一落名 + 原子占据**（唯一落盘点）—— 判定与占名同一步，禁时间戳推断。
   *
   * `Right(p)` = 本次已占据 `p`（内容已就位）；`Left(reason)` = 有界重试耗尽 / 占据失败
   * （temp 缺失、名字空间耗尽…）⇒ **显式失败，禁覆盖**。
   *
   * 机制（两条，首选 = 方案件的 hardlink 推荐版）：
   *   ① `Files.createLink(cand, temp)`（`link(2)`）：目标已存在 ⇒ `FileAlreadyExistsException`
   *      ⇒ 换下一候选；成功即占据（内容已完整），随后撤掉 temp 名 ⇒ **零占位残留**；
   *   ② 硬链接不可用（跨设备 `EXDEV` / 文件系统不支持）⇒ 回退方案件基线的 `O_EXCL` 0 字节占位
   *      + `os.move(temp, cand, replaceExisting = true)`（只替换**本次刚占位**的名字）。
   *      该回退路径在「占位与搬移之间崩溃」时会留 0 字节件（登记为已知窗口，见结果）。
   * 两条都要求 temp 与落点同盘对①成立；同盘时**永远**走①（本仓正常路径：temp 与落点同目录，
   * 见 `derivedReceiverTempPath`）。
   */
  def reserveAndPlace(
    landDir: os.Path,
    fileName: String,
    tempPath: os.Path,
    now: ZonedDateTime,
    maxTries: Int = 64
  ): IO[Either[String, os.Path]] =
    def tryAt(k: Int): IO[Either[String, os.Path]] =
      if k >= maxTries then
        IO.pure(
          Left(s"cannot reserve a unique name for '$fileName' in $landDir after $maxTries tries"): Either[
            String,
            os.Path
          ]
        )
      else
        val cand = landDir / finalNameCandidate(fileName, k, now)
        val attempt: IO[OccupyOutcome] = IO.blocking {
          try
            java.nio.file.Files.createLink(cand.toNIO, tempPath.toNIO)
            OccupyOutcome.Occupied(cand)
          catch
            case _: java.nio.file.FileAlreadyExistsException => OccupyOutcome.Collision
            case _: java.nio.file.NoSuchFileException =>
              OccupyOutcome.Failed(s"cannot place '$fileName': the temp file $tempPath does not exist")
            case _: java.io.IOException =>
              // 硬链接不可用 ⇒ 0 字节占位（O_EXCL，原子且不覆盖）。
              try
                java.nio.file.Files
                  .newByteChannel(
                    cand.toNIO,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE
                  )
                  .close()
                OccupyOutcome.Placeholder(cand)
              catch
                case _: java.nio.file.FileAlreadyExistsException => OccupyOutcome.Collision
                case e2: java.io.IOException =>
                  OccupyOutcome.Failed(
                    s"cannot place '$fileName' as $cand: ${Option(e2.getMessage).getOrElse(e2.getClass.getSimpleName)}"
                  )
        }
        attempt.flatMap {
          case OccupyOutcome.Collision => tryAt(k + 1)
          case OccupyOutcome.Failed(r) => IO.pure(Left(r): Either[String, os.Path])
          case OccupyOutcome.Occupied(p) =>
            // 占据成功 ⇒ 立即撤掉 temp 名（内容已由硬链接保住）⇒ 正常路径零 temp 残留。
            IO.blocking(java.nio.file.Files.deleteIfExists(tempPath.toNIO))
              .handleErrorWith(e => logger.warn(s"temp cleanup after occupying $p failed: ${e.getMessage}").as(false))
              .as(Right(p): Either[String, os.Path])
          case OccupyOutcome.Placeholder(p) =>
            IO.blocking {
              os.move(tempPath, p, replaceExisting = true)
              if os.exists(p) then Right(p): Either[String, os.Path]
              else Left(s"temp $tempPath did not land at $p after the move"): Either[String, os.Path]
            }.handleErrorWith { e =>
              // 失败时清掉**本次刚占位**的 0 字节件（best-effort），绝不触碰任何既有件。
              IO.blocking {
                if os.exists(p) && os.size(p) == 0L then java.nio.file.Files.deleteIfExists(p.toNIO)
                ()
              }.attempt
                .as(
                  Left(
                    s"cannot place '$fileName' as $p: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
                  ): Either[String, os.Path]
                )
            }
        }
    tryAt(0)

  end reserveAndPlace

  /**
   * 撞名时的**改名落点**（单次请求内可用的原子占据；调用方随后把内容写进去）。
   * 名字仍由 [[finalNameCandidate]] 一处生成 ⇒ **不新增算名点**。
   * 用于 legacy 整件 put 的「撞已有件 ⇒ 改名保留新件、原件零损」。
   */
  def occupyConflictFreeName(
    dir: os.Path,
    fileName: String,
    now: ZonedDateTime,
    maxTries: Int = 64
  ): Either[String, os.Path] =
    def tryAt(k: Int): Either[String, os.Path] =
      if k >= maxTries then Left(s"cannot reserve a unique name for '$fileName' in $dir after $maxTries tries")
      else
        val cand = dir / finalNameCandidate(fileName, k, now)
        try
          java.nio.file.Files
            .newByteChannel(
              cand.toNIO,
              java.nio.file.StandardOpenOption.CREATE_NEW,
              java.nio.file.StandardOpenOption.WRITE
            )
            .close()
          Right(cand)
        catch
          case _: java.nio.file.FileAlreadyExistsException => tryAt(k + 1)
          case e: java.io.IOException =>
            Left(
              s"cannot occupy a conflict-free name for '$fileName' in $dir: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
            )
        end try
    tryAt(0)

  end occupyConflictFreeName

  /**
   * Resolve the final save path, appending a timestamp suffix if the name already exists.
   * e.g. "report.pdf" → "report_20250115_143022.pdf"
   *
   * ⚠️ **兼容别名（测试用；零生产调用点）**：落名决策的唯一生产入口 = [[reserveAndPlace]]
   * （占据而非预判）。本函数保留的是**纯候选形态**（走 [[finalNameCandidate]] ⇒ 无第二份算名逻辑），
   * `now` 可注入（判据④确定性）。落盘一律不得再用它。
   */
  def resolveFinalPath(dir: os.Path, fileName: String, now: ZonedDateTime = ZonedDateTime.now()): os.Path =
    val target = dir / fileName
    if !os.exists(target) then target else dir / finalNameCandidate(fileName, 1, now)

  /** Platform-aware Downloads directory. */
  def downloadsDir: os.Path =
    val home = os.Path(System.getProperty("user.home"), os.pwd)
    val osName = System.getProperty("os.name", "").toLowerCase
    osName match
      case s if s.contains("win") => home / "Downloads"
      case s if s.contains("mac") => home / "Downloads"
      case _ =>
        Option(System.getenv("XDG_DOWNLOAD_DIR")) match
          case Some(p) => os.Path(p, os.pwd)
          case None => home / "Downloads"

end DropboxUtil
