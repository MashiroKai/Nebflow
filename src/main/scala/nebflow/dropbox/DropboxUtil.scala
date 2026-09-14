package nebflow.dropbox

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import nebflow.core.NebflowLogger

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

  private def writtenBytesSafe(path: os.Path): IO[Long] =
    IO.blocking(if os.exists(path) then os.size(path) else 0L).handleErrorWith(_ => IO.pure(0L))

  /**
   * Resolve the final save path, appending a timestamp suffix if the name already exists.
   * e.g. "report.pdf" → "report_20250115_143022.pdf"
   */
  def resolveFinalPath(dir: os.Path, fileName: String): os.Path =
    val target = dir / fileName
    if !os.exists(target) then target
    else
      val dot = fileName.lastIndexOf('.')
      val (base, ext) = if dot > 0 then (fileName.substring(0, dot), fileName.substring(dot)) else (fileName, "")
      val ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(ZonedDateTime.now())
      dir / s"${base}_$ts$ext"

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
