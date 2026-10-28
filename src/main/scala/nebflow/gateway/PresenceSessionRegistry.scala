package nebflow.gateway

import cats.effect.{IO, Ref}

import java.util.concurrent.atomic.AtomicLong

/**
 * presence 接受面 per-device 单会话注册表（console-reconnect-stability 批 ·
 * 2026-10-28，台账 #950/#960 同族收尾）。
 *
 * 病灶（2026-10-28 生产读数）：同一对端设备（DESKTOP-IJBBEOT
 * 100.118.229.97）在本机保持 **64/64 条并存 presence WS**，全天
 * `presence WS upgrade rejected reason=per-ip-cap` **44,860 次**（另含
 * accepted≈100）。机制：接受面只 `upsertPeer` 不摘除同 device 的旧 socket
 * ——旧连接照常回 pong（对端进程内泄漏的连接是「活」的，#950 僵尸判死帮不上），
 * 槽位永不释放；而任一旧连接终结时其 finalizer 又无条件 `removePeer(deviceId)`，
 * 把**新**注册的 peer 摘掉 ⇒ 对端看到自己不在册 ⇒ 再拨号 ⇒ 更多并存连接，
 * 直到 per-ip-cap 打满，进入「拒—拨」无退避风暴。
 *
 * 修法 = 镜像拨号侧既有语义（NeblinkPresenceService 的
 * `retireConnection(incumbent, "superseded")`）：同 device 新会话受理即
 * **顶替**旧会话（关闭旧 socket），旧会话 finalizer 凭代际判定
 * 「我已不是现役」⇒ 不再 `removePeer`。于是并存连接恒 ≤1，per-ip 槽随旧
 * 会话 scope 终结立即复纳，拍摆环断开。
 *
 * 线程安全：全部状态在单个 [[Ref]] 内 CAS 更新；代际号由 AtomicLong 单调
 * 发放。close 动作本身幂等（`Deferred.complete` 重复调用为 no-op）。
 */
final class PresenceSessionRegistry:

  import PresenceSessionRegistry.Entry

  private val genCounter = new AtomicLong(0L)
  private val ref = Ref.unsafe[IO, Map[String, Entry]](Map.empty)

  /** 受理前取一个代际号（单调递增；只用于「谁更新」的比较）。 */
  def nextGen: IO[Long] = IO(genCounter.incrementAndGet())

  /**
   * 登记现役会话。返回**被顶替者**的 close（若同 device 已有更旧的现役会话）。
   * 调用方纪律：先 register 再 close 旧会话——旧会话 finalizer 据此判出
   * 「自己已非现役」而跳过 removePeer（见 [[finish]]）。
   */
  def register(deviceId: String, gen: Long, close: IO[Unit]): IO[Option[IO[Unit]]] =
    ref.modify { st =>
      val previous = st.get(deviceId).filter(_.gen != gen).map(_.close)
      (st.updated(deviceId, Entry(gen, close)), previous)
    }

  /**
   * 会话终结（finalizer 唯一入口）。返回 true = 本会话仍是现役且已摘册，
   * 调用方应继续 `removePeer`；false = 本会话已被顶替（或重复收尾），
   * **不得**动现役注册，也不得摘除他人的册项。
   */
  def finish(deviceId: String, gen: Long): IO[Boolean] =
    ref.modify { st =>
      st.get(deviceId) match
        case Some(e) if e.gen == gen => (st - deviceId, true)
        case _ => (st, false)
    }

  /** 只读读数（spec/诊断面）：当前在册设备数。 */
  def size: IO[Int] = ref.get.map(_.size)

end PresenceSessionRegistry

object PresenceSessionRegistry:
  private final case class Entry(gen: Long, close: IO[Unit])
