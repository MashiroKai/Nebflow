package nebflow.actor

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * 刀四·监督关系落盘化（作者裁定3「整刀落盘」，2026-11-05 09:38）的机械验收。
 *
 * 裁定原文（审计 `actorization-gap-and-pekko-migration-audit__20261104_211833__
 * delegate-nebflow-18eb158f.md` §3 刀四 (a)）：「把 `watchers` 落盘成
 * `(watcherPath, targetSessionId, registeredAt)`，**boot 时重放重建 watch**」。
 * 故本 spec 钉六件事，缺一即判「没做到落盘」：
 *
 * - **P1 落盘面**：`watch()` 之后盘上**恰好**一条三元组，三字段逐字对上，
 *   `registeredAt > 0`（不是 0 占位）。
 * - **P2 重放面（核心）**：第一个「进程世界」建的边，在**全新**的 ActorSystem
 *   实例里经 `replayWatches` 重建为**活订阅**——目标 actor 死亡时 watcher 仍收到
 *   `Terminated`。⚠ 关键：watcher 全程**没有**自己调用过 `watch`，这条边只可能
 *   来自盘上重放。
 * - **P3 注销面**：`unwatch` 之后盘上边消失（不会重启后复活一条已注销的订阅）。
 * - **P4 收尸面**：目标死亡后盘上边被剪掉（不留「指向死靶心」的陈旧边）。
 * - **P5 设备隔离**：非本设备的 watcher 边重放时丢弃，并从盘上剪除。
 * - **P6 容错**：盘上内容损坏 ⇒ 重放是空操作、boot 不炸；后续 `watch` 照常落盘。
 *
 * 一律用临时目录 + 文件实现，**不碰真实数据根**；每个用例末尾 `stopAll`。
 */
class ActorWatchPersistenceSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 300.seconds

  private val device = "watchtest"

  private def watcherPath(name: String): ActorPath = ActorPath(device, name)
  private def targetPath(sid: String): ActorPath = ActorPath(device, sid)

  /**
   * 收尸 actor：收到 `Terminated` 就把死者名字记进 `seen`。
   * **它自己从不调用 `watch`** —— 这正是 P2 的判别力所在。
   */
  private def sinkBehavior(seen: Ref[IO, List[String]]): Behavior[String] =
    new Behavior[String]:
      def receive(ctx: ActorContext[String], msg: String): IO[Behavior[String]] =
        IO.pure(this)
      override def onSignal(
        ctx: ActorContext[String],
        signal: SystemSignal
      ): IO[Behavior[String]] =
        signal match
          case SystemSignal.Terminated(ref) => seen.update(_ :+ ref.path.name).as(this)

  private def idleBehavior: Behavior[String] =
    new Behavior[String]:
      def receive(ctx: ActorContext[String], msg: String): IO[Behavior[String]] = IO.pure(this)

  /** 有界等待（最多 30s），超时以当前值返回、交给断言判红——不是无限轮询。 */
  private def awaitSeen(seen: Ref[IO, List[String]], want: String): IO[List[String]] =
    seen.get
      .flatMap { xs =>
        if xs.contains(want) then IO.pure(xs) else IO.sleep(50.millis) *> awaitSeen(seen, want)
      }
      .timeoutTo(30.seconds, seen.get)

  /** 临时目录 + 文件落盘面（用例结束即删）。 */
  private def withStore[A](body: (os.Path, WatchStore, os.Path) => IO[A]): IO[A] =
    IO(os.temp.dir(prefix = "nf-watch-store")).bracket { dir =>
      val path = dir / "actor-watches.json"
      body(dir, WatchStore.file(path), path)
    }(dir => IO(os.remove.all(dir)).attempt.void)

  // ── P1 ────────────────────────────────────────────────────────────────
  test("P1 落盘：watch() 写出一条 (watcherPath, targetSessionId, registeredAt) 边") {
    withStore { (_, store, path) =>
      val system = ActorSystem(device, store)
      for
        _ <- system.watch(watcherPath("w1"), targetPath("t1"))
        edges <- WatchStore.file(path).load
        _ <- system.stopAll
      yield
        assertEquals(edges.size, 1, s"盘上应恰好一条边，实得 $edges")
        assertEquals(edges.head.watcherPath, watcherPath("w1").toString)
        // 靶心落的是**会话身份**（不是带 device 的 path）——ActorPath 是进程本地寻址
        assertEquals(edges.head.targetSessionId, "t1")
        assert(edges.head.registeredAt > 0L, s"registeredAt 必须是真实时刻，实得 ${edges.head.registeredAt}")
    }
  }

  // ── P2（核心：boot 重放重建活订阅）────────────────────────────────────
  test("P2 boot 重放：新 ActorSystem 经 replayWatches 重建活订阅——目标死亡时 watcher 收到 Terminated") {
    withStore { (_, store, _) =>
      val firstWorld = ActorSystem(device, store)
      for
        _ <- firstWorld.watch(watcherPath("w-boot"), targetPath("t-boot"))
        // 前置（防空洞）：边确实落盘了，否则「重放成功」可能只是因为它本来就在内存里
        persisted <- store.load
        _ = assertEquals(persisted.size, 1, "重放前提不成立：第一轮世界没有把边落盘")
        _ <- firstWorld.stopAll
        // ── 第二个进程世界：内存表全空，只有盘 ──
        secondWorld = ActorSystem(device, store)
        seen <- Ref.of[IO, List[String]](Nil)
        _ <- secondWorld.replayWatches
        _ <- secondWorld.spawn(sinkBehavior(seen), "w-boot")
        target <- secondWorld.spawn(idleBehavior, "t-boot")
        _ <- secondWorld.stop(target)
        got <- awaitSeen(seen, "t-boot")
        _ <- secondWorld.stopAll
      yield assertEquals(got, List("t-boot"), s"重放后的订阅没有送出 Terminated，实得 $got")
    }
  }

  // ── P3 ────────────────────────────────────────────────────────────────
  test("P3 注销面：unwatch() 把边从盘上删掉") {
    withStore { (_, store, _) =>
      val system = ActorSystem(device, store)
      for
        _ <- system.watch(watcherPath("w2"), targetPath("t2"))
        before <- store.load
        _ <- system.unwatch(watcherPath("w2"), targetPath("t2"))
        after <- store.load
        _ <- system.stopAll
      yield
        assertEquals(before.size, 1)
        assertEquals(after, Nil, s"unwatch 后盘上不应残留边，实得 $after")
    }
  }

  // ── P4 ────────────────────────────────────────────────────────────────
  test("P4 收尸面：目标死亡后盘上的边被剪掉（不留指向死靶心的陈旧边）") {
    withStore { (_, store, _) =>
      val system = ActorSystem(device, store)
      for
        seen <- Ref.of[IO, List[String]](Nil)
        _ <- system.spawn(sinkBehavior(seen), "w4")
        target <- system.spawn(idleBehavior, "t4")
        _ <- system.watch(watcherPath("w4"), targetPath("t4"))
        before <- store.load
        _ <- system.stop(target)
        _ <- awaitSeen(seen, "t4")
        after <- store.load
        _ <- system.stopAll
      yield
        assertEquals(before.size, 1)
        assertEquals(after, Nil, s"目标死后盘上不应残留边，实得 $after")
    }
  }

  // ── P5 ────────────────────────────────────────────────────────────────
  test("P5 设备隔离：非本设备的 watcher 边重放时丢弃并从盘上剪除") {
    withStore { (_, store, path) =>
      val foreign = ActorPath("other-device", "w-foreign").toString
      val local = watcherPath("w-local").toString
      // 直接写盘：一条异地边 + 一条本地边（同靶心）
      val raw = s"""[
        {"watcherPath":"$foreign","targetSessionId":"t5","registeredAt":111},
        {"watcherPath":"$local","targetSessionId":"t5","registeredAt":222}
      ]"""
      val system = ActorSystem(device, store)
      for
        _ <- IO(os.write.over(path, raw, createFolders = true))
        _ <- system.replayWatches
        after <- store.load
        _ <- system.stopAll
      yield
        assertEquals(
          after.map(_.watcherPath),
          List(local),
          s"重放后盘上应只剩本地边，实得 $after"
        )
        assertEquals(after.map(_.registeredAt), List(222L), "本地边的 registeredAt 必须原样保留")
    }
  }

  // ── P6 ────────────────────────────────────────────────────────────────
  test("P6 容错：盘上内容损坏 ⇒ 重放是空操作、后续 watch 照常落盘") {
    withStore { (_, store, path) =>
      val system = ActorSystem(device, store)
      for
        _ <- IO(os.write.over(path, "not json {{{ 半截文件", createFolders = true))
        broken <- store.load
        _ <- system.replayWatches
        _ <- system.watch(watcherPath("w6"), targetPath("t6"))
        healed <- store.load
        _ <- system.stopAll
      yield
        assertEquals(broken, Nil, "损坏文件必须读成空集，而不是抛异常")
        assertEquals(healed.size, 1, s"后续 watch 必须把盘面重写成合法内容，实得 $healed")
        assertEquals(healed.head.targetSessionId, "t6")
    }
  }

end ActorWatchPersistenceSpec
