package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*

/**
 * presence 接受面 per-device 顶替（console-reconnect-stability 批 2026-10-28）：
 *
 * 生产病灶（2026-10-28 本机读数）：同 device 并存 64/64 条 presence WS，
 * 全天 per-ip-cap 拒绝 44,860 次；旧连接 finalizer 无条件 removePeer 把新注册
 * 拍掉 ⇒ 对端再拨号 ⇒ 并存更多 ⇒ 灌满 64 槽 ⇒ 「拒—拨」风暴。
 *
 * 本 spec 钉三条：
 *  1. 注册表代际语义：新会话 register 返还被顶替者 close；旧代际 finish=false
 *     且不摘新册项；现役代际 finish=true 并摘册。
 *  2. `PresenceWsStreams.close` 真收口：旧会话收流（对端永久沉默 =
 *     `IO.never` 入站）被 close 打断，finalizer 到达。
 *  3. **反拍摆**：被顶替旧会话的 finalizer 不得 removePeer（新注册必须活着）；
 *     现役会话正常 close 的 finalizer 仍必须 removePeer 恰好一次。
 */
class PresenceSessionSupersedeSpec extends CatsEffectSuite:

  private val deviceId = "device-under-test"
  private val peerIp = "100.118.229.97"

  test("注册表：顶替返旧 close、旧代际 finish 不动新册、现役 finish 摘册") {
    for
      registry <- IO(new PresenceSessionRegistry)
      closed <- Ref.of[IO, List[String]](Nil)
      close1 = closed.update("gen1" :: _)
      close2 = closed.update("gen2" :: _)
      gen1 <- registry.nextGen
      gen2 <- registry.nextGen
      _ = assert(gen2 > gen1, "代际号必须单调")
      first <- registry.register(deviceId, gen1, close1)
      _ = assertEquals(first, None)
      second <- registry.register(deviceId, gen2, close2)
      _ = assert(second.isDefined, "同 device 再受理必须返还被顶替者 close")
      _ <- second.get // 顶替旧会话
      staleFinish <- registry.finish(deviceId, gen1)
      _ = assert(!staleFinish, "被顶替代际不得再摘册")
      sizeAfterStale <- registry.size
      _ = assertEquals(sizeAfterStale, 1, "旧代际收尾不得误删现役册项")
      currentFinish <- registry.finish(deviceId, gen2)
      _ = assert(currentFinish, "现役代际收尾必须摘册")
      sizeAfterCurrent <- registry.size
      _ = assertEquals(sizeAfterCurrent, 0)
      closedNow <- closed.get
      _ = assertEquals(closedNow, List("gen1"), "close 动作幂等且只落到被顶替者")
    yield ()
  }

  test("顶替关闭旧会话收流、旧 finalizer 不 removePeer；现役 close 仍 removePeer 一次") {
    for
      registry <- IO(new PresenceSessionRegistry)
      removed <- Ref.of[IO, List[String]](Nil)
      gen1 <- registry.nextGen
      streams1 <- session(registry, removed, gen1)
      _ <- registry.register(deviceId, gen1, streams1.close)
      fiber1 <- streams1.receive(Stream.eval(IO.never[WebSocketFrame])).compile.drain.start
      _ <- IO.sleep(100.millis)
      gen2 <- registry.nextGen
      streams2 <- session(registry, removed, gen2)
      previous <- registry.register(deviceId, gen2, streams2.close)
      _ <- previous.getOrElse(IO.unit) // 受理面：先 register 再 close 旧会话
      _ <- fiber1.joinWithNever.timeout(3.seconds) // 旧收流被打断 ⇒ finalizer 到达
      removedAfterSupersede <- removed.get
      _ = assertEquals(
        removedAfterSupersede,
        Nil,
        "被顶替旧会话的 finalizer 不得摘掉新注册（拍摆环必须断开）"
      )
      fiber2 <- streams2.receive(Stream.eval(IO.never[WebSocketFrame])).compile.drain.start
      _ <- IO.sleep(50.millis)
      _ <- streams2.close
      _ <- fiber2.joinWithNever.timeout(3.seconds)
      removedFinal <- removed.get
      _ = assertEquals(removedFinal, List(deviceId), "现役会话正常收尾仍须 removePeer 恰好一次")
      sizeFinal <- registry.size
      _ = assertEquals(sizeFinal, 0)
    yield ()
  }

  /** 与生产接受面同装配：真实 presenceWsStreams + 代际化 removePeer。 */
  private def session(
    registry: PresenceSessionRegistry,
    removed: Ref[IO, List[String]],
    gen: Long
  ): IO[PresenceRoutes.PresenceWsStreams] =
    PresenceRoutes.presenceWsStreams(
      connGuard = ConnGuard.disabled,
      ip = peerIp,
      deviceId = deviceId,
      removePeer = id =>
        registry.finish(id, gen).flatMap {
          case true => removed.update(id :: _)
          case false => IO.unit
        },
      handleData = _ => IO.unit,
      onZombieRecycle = _ => IO.unit,
      // 生产拍长（10s/30s）：本 spec 只考「外部 close 打断」，不考判死计时。
      liveness = PresenceRoutes.PresenceWsLiveness()
    )

end PresenceSessionSupersedeSpec
