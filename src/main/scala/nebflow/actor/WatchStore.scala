package nebflow.actor

import cats.effect.IO
import io.circe.{Json, parser}
import io.circe.syntax.*
import nebflow.shared.NebflowLogger

/**
 * 一条**持久化的死亡监视边**（刀四·监督关系落盘化，作者裁定3「整刀落盘」，2026-11-05 09:38）。
 *
 * 审计原件 `actorization-gap-and-pekko-migration-audit__20261104_211833__
 * delegate-nebflow-18eb158f.md` §3 刀四 (a) 的逐字形态：
 * 「把 `watchers` 落盘成 `(watcherPath, targetSessionId, registeredAt)`，boot 时重放
 * 重建 watch」——三个字段即本条 case class，一个不多、一个不少。
 *
 * **为什么 target 落 `sessionId` 而不是完整 path**：`ActorPath` 带 `localDevice`
 * 限定、是**进程本地**的寻址（`ActorPath.toString` = `nebflow://<device>/<name>`），
 * 重启后设备名相同但进程换了；真正跨重启稳定的靶心是**会话身份**。所以落盘存
 * `targetSessionId`，重放时再按当前 `localDevice` 还原成 `ActorPath(localDevice, sid)`。
 *
 * **注意边界（诚实申报）**：`target actor` 若在重启前已经死掉，这条重放边指向一个
 * 永不再生的名字——落盘不制造假事件（重放只重建订阅，不伪造 `Terminated`）。
 */
final case class WatchEdge(watcherPath: String, targetSessionId: String, registeredAt: Long)

object WatchEdge:

  def toJson(edge: WatchEdge): Json =
    Json.obj(
      "watcherPath" -> edge.watcherPath.asJson,
      "targetSessionId" -> edge.targetSessionId.asJson,
      "registeredAt" -> edge.registeredAt.asJson
    )

  /** 解析一条边：三字段缺一即视为不可读（不猜默认值——落盘格式即契约）。 */
  def fromJson(json: Json): Option[WatchEdge] =
    json.asObject.flatMap { obj =>
      for
        watcherPath <- obj("watcherPath").flatMap(_.asString)
        targetSessionId <- obj("targetSessionId").flatMap(_.asString)
        registeredAt <- obj("registeredAt").flatMap(_.asNumber).flatMap(_.toLong)
      yield WatchEdge(watcherPath, targetSessionId, registeredAt)
    }
end WatchEdge

/**
 * 监督边的落盘面（刀四）。
 *
 * 只有两个动作——`load`（boot 重放读）与 `save`（每次订阅变更全量重写）。全量重写
 * 而非追加日志：监督边是**小集合 + 会被注销**的状态（`unwatch` / 目标死亡都要删
 * 条目），追加日志会需要一条压缩腿，而 1 个文件 + 原子替换已足够，且读侧无重放
 * 顺序问题。
 */
trait WatchStore:
  def load: IO[List[WatchEdge]]
  def save(edges: List[WatchEdge]): IO[Unit]

object WatchStore:

  /**
   * 零落盘（默认）：行为与刀四之前**逐字一致**。
   *
   * 刻意不做成「默认写数据根」——`ActorSystem.apply(device)` 在全套测试里被大量
   * 构造，隐式落盘会让单测写真实数据根。生产装配点显式传 [[file]]
   * （`GatewayMain`），测试传内存/临时文件实现。
   */
  val disabled: WatchStore = new WatchStore:
    def load: IO[List[WatchEdge]] = IO.pure(Nil)
    def save(edges: List[WatchEdge]): IO[Unit] = IO.unit

  /** 内存实现（测试/嵌入式用；进程退出即失）。 */
  def memory(initial: List[WatchEdge] = Nil): IO[WatchStore] =
    cats.effect.Ref.of[IO, List[WatchEdge]](initial).map { ref =>
      new WatchStore:
        def load: IO[List[WatchEdge]] = ref.get
        def save(edges: List[WatchEdge]): IO[Unit] = ref.set(edges)
    }

  /**
   * 文件实现：`<path>` 上的人可读 JSON 数组，写入走「临时文件 + 原子替换」，
   * 进程崩在写入中途不会留下半截文件（要么旧内容、要么新内容）。
   *
   * 读侧容错口径：文件缺失 / 损坏 / 单条字段不全 ⇒ 视为无（**绝不因为盘上脏数据
   * 让 boot 挂掉**，失败只 WARN）。这是落盘面而非真源——真源仍是内存 `watchers`。
   */
  def file(path: os.Path): WatchStore = new WatchStore:
    private val log = NebflowLogger.forName("nebflow.actor.watch-store")

    def load: IO[List[WatchEdge]] =
      IO.blocking {
        if !os.exists(path) then Nil
        else
          val raw = os.read(path)
          parser.parse(raw).toOption.flatMap(_.asArray) match
            case Some(items) => items.toList.flatMap(WatchEdge.fromJson)
            case None => Nil
      }.handleErrorWith { e =>
        log.warn(s"watch store load failed (${path}): ${e.getMessage} — treating as empty").as(Nil)
      }

    def save(edges: List[WatchEdge]): IO[Unit] =
      IO.blocking {
        val parent = path / os.up
        if !os.exists(parent) then os.makeDir.all(parent)
        val tmp = parent / (path.last + ".tmp")
        os.write.over(tmp, Json.arr(edges.map(WatchEdge.toJson)*).spaces2, createFolders = true)
        java.nio.file.Files.move(
          tmp.toNIO,
          path.toNIO,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
        ()
      }.handleErrorWith { e =>
        log.warn(s"watch store save failed (${path}): ${e.getMessage} — in-memory watch state is authoritative")
      }
  end file
end WatchStore
