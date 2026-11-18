package nebflow.gateway

import cats.effect.{IO, Ref}

import java.nio.file.{Files, LinkOption, Path}
import java.security.MessageDigest

/**
 * One content-addressed object registration.
 *
 * `cid` is the CONTENT identity of the bytes at `realPath` at registration
 * time; `size`/`mtimeMs` are the cheap identity proxy re-checked on every
 * serve (a file that changed since registration no longer backs this cid).
 *
 * @param cid        content id = lowercase sha256 hex, first [[NfObjStore.CidHexChars]] chars
 * @param realPath   the toRealPath-normalized path the bytes were read from
 * @param size       byte size at registration
 * @param mtimeMs    last-modified millis at registration
 * @param registeredAt epoch millis of registration (TTL anchor)
 */
final case class NfObjEntry(cid: String, realPath: String, size: Long, mtimeMs: Long, registeredAt: Long)

/**
 * `GET /api/nf-obj/<cid>/<name>` 的 cid→object 索引（作者令 2026-11-18「按对象
 * 传输 / 文件存储复用也按对象来」批·第 1 件）。
 *
 * Why an index exists at all: the content-addressed exit is only useful if a
 * URL is a function of CONTENT, independently of the ticket that happened to
 * be current (`/api/nf-file?path=…&ticket=…` re-mints its ticket, so the same
 * object had as many URLs as there were mounts — the browser HTTP cache could
 * never reuse one leg's bytes for another). `/api/nf-obj/<cid>` is stable for
 * as long as the bytes are, so the second leg of the same object is a pure
 * browser-cache hit.
 *
 * Where the cid comes from: the SERVER computes it, at `POST /api/nf-ticket`
 * time, from the very file the verdict just allowed ([[NfObjStore.register]]).
 * A client-supplied cid is never trusted — content identity must not be
 * forgeable, or a cid would become an unauthenticated cross-path read token.
 *
 * Cost control (申报): hashing runs once per (path, size, mtime) — the
 * reverse map makes a re-mint of an unchanged file a map lookup, not a
 * re-read. Files above [[NfObjStore.MaxCidBytes]] are NOT content-addressed
 * (the mint returns `cid: null`) and keep their ticket URL: video/Range
 * streaming is deliberately out of this batch's scope, and hashing a
 * multi-GiB object on the mint path would be a latency regression.
 *
 * Freshness: entries expire after the store TTL; every serve re-stats and
 * refuses a cid whose file no longer matches (size+mtime) — the TTL is the
 * bound on how long a stale registration could linger in memory, not a
 * license to serve changed bytes.
 */
final class NfObjStore private (
  ref: Ref[IO, NfObjStore.State],
  ttlMs: Long
):
  import NfObjStore.*

  /**
   * Register (or refresh) the content id of `realPath`.
   *
   * Streams the file through SHA-256 unless the (path, size, mtime) triple is
   * already known. Returns `None` when the object is too large to
   * content-address (see class doc) or unreadable (fail-safe: the caller
   * simply emits a ticket URL without a cid).
   */
  def register(realPath: String, size: Long, mtimeMs: Long): IO[Option[String]] =
    if size <= 0L || size > MaxCidBytes then IO.pure(None)
    else
      IO.realTime.map(_.toMillis).flatMap { now =>
        ref.get.flatMap { st =>
          st.byPath.get(realPath) match
            case Some((cid, knownSize, knownMtime)) if knownSize == size && knownMtime == mtimeMs =>
              // Unchanged file: reuse the cid, refresh only the TTL anchor.
              ref
                .update(s => s.copy(byCid = s.byCid.updated(cid, NfObjEntry(cid, realPath, size, mtimeMs, now))))
                .as(Some(cid))
            case _ =>
              IO.blocking(hashFile(java.nio.file.Paths.get(realPath))).attempt.flatMap {
                case Left(_) => IO.pure(None)
                case Right(hex) =>
                  val cid = hex.take(CidHexChars)
                  ref
                    .update { s =>
                      s.copy(
                        byCid = s.byCid.updated(cid, NfObjEntry(cid, realPath, size, mtimeMs, now)),
                        byPath = s.byPath.updated(realPath, (cid, size, mtimeMs))
                      )
                    }
                    .as(Some(cid))
              }
        }
      }

  /**
   * Resolve a cid to a still-valid entry.
   *
   * `None` for an unknown/expired cid AND for a registration whose file no
   * longer matches (size+mtime) — the caller answers the same uniform 404 for
   * both, so the route is not an existence oracle.
   */
  def lookup(cid: String): IO[Option[NfObjEntry]] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      ref.get.map(_.byCid.get(cid)).flatMap {
        case None => IO.pure(None)
        case Some(e) if now - e.registeredAt > ttlMs =>
          ref.update(s => s.copy(byCid = s.byCid - cid, byPath = s.byPath - e.realPath)).as(None)
        case Some(e) =>
          IO.blocking {
            val p = java.nio.file.Paths.get(e.realPath)
            if Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) then
              Some((Files.size(p), Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis))
            else None
          }.attempt.map {
            case Right(Some((sz, mt))) if sz == e.size && mt == e.mtimeMs => Some(e)
            case _ => None
          }
      }
    }

  /** 读数面（诊断/规格）：在场条目数。 */
  def size: IO[Int] = ref.get.map(_.byCid.size)

object NfObjStore:

  /** cid 位数：sha256 前 32 hex = 128 bit。理由：URL 与 ETag 都要短到可读，
   *  而 128 bit 的碰撞界（生日界 ≈ 2^64 件）远超任何本地对象池规模；16 hex
   *  （64 bit）在同一台机器的重活场景已可接受，但 32 hex 是零成本的安全余量
   *  （只多 16 个字符，且与磁盘对象路径 `objects/<前2>/<sha256>` 的前两级切分
   *  天然对齐）。 */
  val CidHexChars: Int = 32

  /** cid 形态（路由只认这一种；不匹配 = 统一 404，不做存在性 oracle）。 */
  val CidPattern: String = "^[0-9a-f]{32}$"

  /** 内容寻址的尺寸上限 64 MiB：超过则不算 cid（保留票据腿）。 */
  val MaxCidBytes: Long = 64L * 1024 * 1024

  /** 索引条目 TTL：24h（内存索引；陈旧注册的兜底上界）。 */
  val DefaultTtlMs: Long = 24L * 60 * 60 * 1000

  private[gateway] final case class State(
    byCid: Map[String, NfObjEntry] = Map.empty,
    byPath: Map[String, (String, Long, Long)] = Map.empty
  )

  /** 流式 sha256（64 KiB 块；大件不整读入内存）。 */
  def hashFile(path: Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(path)
    try
      val buf = new Array[Byte](64 * 1024)
      var n = in.read(buf)
      while n >= 0 do
        if n > 0 then md.update(buf, 0, n)
        n = in.read(buf)
    finally in.close()
    md.digest().map(b => "%02x".format(b & 0xff)).mkString

  def create(ttlMs: Long = DefaultTtlMs): IO[NfObjStore] =
    Ref.of[IO, State](State()).map(r => new NfObjStore(r, ttlMs))

  def unsafeCreate(ttlMs: Long = DefaultTtlMs): NfObjStore =
    create(ttlMs).unsafeRunSync()(using cats.effect.unsafe.implicits.global)

  /** 记忆化实例（缺省注入缝；测试与既有构造点零改动）。 */
  lazy val memoized: NfObjStore = unsafeCreate()
