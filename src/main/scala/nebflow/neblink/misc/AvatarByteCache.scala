package nebflow.neblink.misc

import cats.effect.{IO, Ref}
import nebflow.neblink.misc.AvatarProxy

import scala.concurrent.duration.*

/**
 * 网关侧「头像字节」短时缓存（2026-11-04 旧域 301 批）。
 *
 * 为什么需要（缺陷链条，不是性能优化）：代理腿
 * `GET /api/avatars/{userId}` 每次请求都**无条件回源**（`AvatarProxy.fetch`
 * 在 `If-None-Match` 判定**之前**执行，见 `NeblinkRoutes` 该 case 的旧体）。
 * 于是上游一旦回 301（好友头像 URL 仍指旧域 `mashiro.staging.nebflow.space`，
 * 域名迁移后该域已 301 跳走），客户端拿到 502，本地字节缓存永远建不起来，
 * **每次进设置页/列表都要再回源一次**——这正是本批要闭的环。
 *
 * 语义（三条，逐条可测）：
 *  1. **命中即零上游往返**：`get` 新鲜（`ttl` 内）⇒ 路由直接出字节，连
 *     `If-None-Match` 的 304 都**不需要回源算 sha**（sha 随条目存着）。
 *  2. **失败不入缓存**：`getOrFetch` 的 `Left` 原样上抛（路由 `502`），
 *     不写任何条目——失败退避由客户端负责（`localStore.ensureAvatar` 1h），
 *     网关不替它记忆失败态。
 *  3. **有界**：条目数 / 总字节 / 单条上限三道上限 + 按 `fetchedAtMs` 淘汰
 *     最旧（TTL 条目下 ≈ 插入序，够用；不做真 LRU 是**有意的**——见下）。
 *
 * 为什么不缓存「按 userId」而按 **URL**：URL 是源标记（`FriendSummary.avatar`），
 * 好友换头像 ⇒ URL 变 ⇒ 自然 miss，无需失效逻辑；同 URL 必然同内容。
 *
 * 不做真 LRU 的代价（如实记）：并发/高基数时可能淘汰一个刚被读过但较旧的
 * 条目（下次 miss 再取一次）。头像域基数 = 好友数级（≤ 几十），TTL 10 min，
 * 代价是「多回源一次」，方向安全（永不吐错字节）——故不为它引入访问序表。
 *
 * 单飞（single-flight）同样**不做**：同 URL 并发 miss 会各取一次（最多
 * `并发度` 次重复回源，结果一致）。理由同上：错代价是少量重复读，不是错答；
 * 引入 `Deferred` 表会带来「求值异常卡死后续等待者」的新失败面。
 */
final class AvatarByteCache private (
  val ttl: FiniteDuration,
  val maxEntries: Int,
  val maxTotalBytes: Long,
  val maxEntryBytes: Long,
  ref: Ref[IO, Map[String, AvatarByteCache.Entry]]
):
  import AvatarByteCache.Entry

  /** 新鲜条目；过期条目按未命中处理并顺手剔除（TTL 之外不再被读到）。 */
  def get(key: String): IO[Option[Entry]] =
    IO.realTime.map(_.toMillis).flatMap { now =>
      ref.modify { m =>
        m.get(key) match
          case Some(e) if now - e.fetchedAtMs <= ttl.toMillis => (m, Some(e))
          case Some(_) => (m - key, None)
          case None => (m, None)
      }
    }

  /** 落条目（超单条上限 ⇒ 不落，仍由调用方原样返回；缓存是 best-effort）。 */
  def put(key: String, entry: Entry): IO[Unit] =
    if entry.bytes.length.toLong > maxEntryBytes then IO.unit
    else ref.update(m => evict(m.updated(key, entry)))

  /**
   * 缓存优先取字节：命中 ⇒ 只用缓存；未命中 ⇒ 执行 `fetch` 一次并落盘。
   * `fetch` 的 `Left` 不写缓存（失败不污染既有条目）。
   */
  def getOrFetch(key: String)(
    fetch: IO[Either[String, (String, Array[Byte])]]
  ): IO[Either[String, Entry]] =
    get(key).flatMap {
      case Some(hit) => IO.pure(Right(hit))
      case None =>
        fetch.flatMap {
          case Left(err) => IO.pure(Left(err))
          case Right((contentType, bytes)) =>
            IO.realTime.map(_.toMillis).flatMap { now =>
              val entry = Entry(contentType, bytes, AvatarProxy.sha256Hex(bytes), now)
              put(key, entry).as(Right(entry))
            }
        }
    }

  /** 可见条目数（观测/自证用；不含过期未剔的条目）。 */
  def size: IO[Int] = ref.get.map(_.size)

  /** 三道上限：条目数 / 总字节；按 `fetchedAtMs` 从最旧开始丢。 */
  private def evict(m: Map[String, Entry]): Map[String, Entry] =
    val ordered = m.toSeq.sortBy(_._2.fetchedAtMs) // oldest first
    var kept = Map.from(ordered)
    var total = ordered.foldLeft(0L)((acc, kv) => acc + kv._2.bytes.length.toLong)
    val it = ordered.iterator
    while (kept.size > maxEntries || total > maxTotalBytes) && it.hasNext do
      val (k, v) = it.next()
      kept -= k
      total -= v.bytes.length.toLong
    kept

end AvatarByteCache

object AvatarByteCache:

  /** 一条被缓存的头像字节；`sha256` 随条目存 ⇒ 304 判定无需回源。 */
  final case class Entry(contentType: String, bytes: Array[Byte], sha256: String, fetchedAtMs: Long):
    def etag: String = "\"" + sha256 + "\""

  /** 10 min：≫ 状态轮询拍（10 s / 45 s），≪ 身份切换窗 ⇒ 换头像最多 10 min 内可见。 */
  val DefaultTtl: FiniteDuration = 10.minutes
  val DefaultMaxEntries: Int = 64
  val DefaultMaxTotalBytes: Long = 16L * 1024 * 1024

  /** 与 web 侧 `avatarCache.js` 的 `MAX_BYTES`（1.5 MB）同数量级，略宽。 */
  val DefaultMaxEntryBytes: Long = 2L * 1024 * 1024

  /** 独立实例（测试用：与生产共享实例隔离）。 */
  def create(
    ttl: FiniteDuration = DefaultTtl,
    maxEntries: Int = DefaultMaxEntries,
    maxTotalBytes: Long = DefaultMaxTotalBytes,
    maxEntryBytes: Long = DefaultMaxEntryBytes
  ): IO[AvatarByteCache] =
    Ref
      .of[IO, Map[String, Entry]](Map.empty)
      .map(ref => new AvatarByteCache(ttl, maxEntries, maxTotalBytes, maxEntryBytes, ref))

  /**
   * 网关进程内共享实例（生产路径）。`Ref.unsafe` 在 IO 上只做不可变地图的
   * 初始值构造（无副作用、无求值），与 `OutboundHttpClients` 的「进程内单实例」
   * 先例同精神：路由体无需为它增加接线参数。
   */
  lazy val shared: AvatarByteCache =
    new AvatarByteCache(
      DefaultTtl,
      DefaultMaxEntries,
      DefaultMaxTotalBytes,
      DefaultMaxEntryBytes,
      Ref.unsafe[IO, Map[String, Entry]](Map.empty)
    )

end AvatarByteCache
