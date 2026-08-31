package nebflow.core.project

import cats.effect.{IO, Ref}
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}

/**
 * FlowMapStore —— 每项目 Flow Map 存储（#28 阶段 0，方案 §2.6）。
 *
 * - 磁盘 write-through：活动区 `<workspace>/.nebflow/flow-map.json` +
 *   归档区 `<workspace>/.nebflow/flow-map-archive.json`（原子写，重启恢复）
 * - 内存 Ref 持当前状态；所有变更走 `mutate`（Ref.update 原子性 + 落盘）
 * - 环检测：NodeEdit 建边（from→to）前 DFS（to 的传递下游沿 out 边可达 from → 拒）
 * - TTL：终态节点 ttlExpireAt 到期 → 移入归档区（结果全文保留）+ 活动区删除
 * - barrier：节点启动条件 = 全部 in 上游 deliveredTo 含本节点（NodeEngine 裁决）
 *
 * 并发纪律：边/计数的变更必须在单个 mutate 内完成（§2.3「单事务更新」）。
 */
class FlowMapStore private (
  val project: String,
  private val statePath: os.Path,
  private val archivePath: os.Path,
  private val state: Ref[IO, FlowMapState],
  private val archive: Ref[IO, FlowMapArchive]
):
  private val logger = NebflowLogger.forName("nebflow.flowmap")

  /** 当前活动区快照。 */
  def snapshot: IO[FlowMapState] = state.get

  /** 当前归档区快照。 */
  def archiveSnapshot: IO[FlowMapArchive] = archive.get

  def getNode(id: String): IO[Option[NodeDef]] = state.get.map(_.nodes.get(id))

  /** 查节点：活动区优先，归档区兜底（§2.1 归档结果仍可接线投递）。 */
  def findNode(id: String): IO[Option[NodeDef]] =
    state.get.map(_.nodes.get(id)).flatMap {
      case some @ Some(_) => IO.pure(some)
      case None => archive.get.map(_.nodes.get(id))
    }

  /** 事务变更：f 应用到当前状态 → Ref 更新 → 落盘。返回新活动区。 */
  def mutate(f: FlowMapState => FlowMapState): IO[FlowMapState] =
    for
      newState <- state.updateAndGet { s =>
        val ns = f(s)
        ns.copy(updatedAt = System.currentTimeMillis())
      }
      _ <- persistState(newState)
    yield newState

  /** 归档区事务（TTL 移除时用）。 */
  def mutateArchive(f: FlowMapArchive => FlowMapArchive): IO[FlowMapArchive] =
    for
      newArc <- archive.updateAndGet { a =>
        val na = f(a)
        na.copy(project = project)
      }
      _ <- persistArchive(newArc)
    yield newArc

  /** 环检测：设 from.out = to（或给 to 加 in=from）是否成环。
    * to 的传递下游（沿 out 边，跳过 Nebula）可达 from → 成环。 */
  def wouldCreateCycle(from: String, to: String): IO[Boolean] =
    if to == "Nebula" || to.isEmpty then IO.pure(false)
    else
      state.get.map { s =>
        def reachable(start: String, visited: Set[String]): Boolean =
          if start == from then true
          else if visited.contains(start) then false
          else
            s.nodes.get(start).flatMap(_.out).filter(_ != "Nebula") match
              case Some(nxt) => reachable(nxt, visited + start)
              case None => false
        reachable(to, Set.empty)
      }

  /** TTL sweep：终态节点 ttlExpireAt ≤ now → 从活动区移入归档区（结果全文保留）。
    * 返回被移除的节点 id 列表（ProjectActor 据此发 WS nodeRemoved）。 */
  def sweepExpired(now: Long): IO[List[String]] =
    for
      s <- state.get
      expired = s.nodes.values
        .filter(n => NodeLifecycle.Terminal.contains(n.status))
        .filter(n => n.ttlExpireAt.exists(_ <= now))
        .toList
      _ <- if expired.isEmpty then IO.unit
      else
        val ids = expired.map(_.id).toSet
        mutate(st => st.copy(nodes = st.nodes -- ids)) *>
          mutateArchive(a => a.copy(nodes = a.nodes ++ expired.map(n => n.id -> n).toMap))
      _ <- if expired.isEmpty then IO.unit else IO(logger.infoSync(s"FlowMap[$project] TTL sweep: ${expired.map(_.name).mkString(", ")} → archive"))
    yield expired.map(_.id)

  // ── 持久化 ─────────────────────────────────────────────

  private def persistState(s: FlowMapState): IO[Unit] =
    IO.blocking(AtomicJson.writeSync(statePath, s.asJson.noSpaces))

  private def persistArchive(a: FlowMapArchive): IO[Unit] =
    IO.blocking {
      if a.nodes.nonEmpty then AtomicJson.writeSync(archivePath, a.asJson.noSpaces)
    }

  private def loadInitial(): IO[FlowMapState] =
    IO.blocking {
      if os.exists(statePath) then
        jsonParse(os.read(statePath)).flatMap(_.as[FlowMapState]) match
          case Right(s) => s
          case Left(e) =>
            logger.warnSync(s"flow-map.json corrupt: $e — starting empty")
            FlowMapState(project = project, updatedAt = System.currentTimeMillis())
      else FlowMapState(project = project, updatedAt = System.currentTimeMillis())
    }

  private def loadArchive(): IO[FlowMapArchive] =
    IO.blocking {
      if os.exists(archivePath) then
        jsonParse(os.read(archivePath)).flatMap(_.as[FlowMapArchive]) match
          case Right(a) => a
          case Left(_) => FlowMapArchive(project = project)
      else FlowMapArchive(project = project)
    }

object FlowMapStore:
  /** 打开（或初始化）项目的 Flow Map store。workspace 为项目工作区绝对路径。 */
  def open(project: String, workspace: String): IO[FlowMapStore] =
    val base = os.Path(workspace, PathUtil.dataRoot) / ".nebflow"
    val statePath = base / "flow-map.json"
    val archivePath = base / "flow-map-archive.json"
    for
      s <- IO.blocking(os.makeDir.all(base))
      store = new FlowMapStore(project, statePath, archivePath, Ref.unsafe[IO, FlowMapState](FlowMapState(project = project, updatedAt = 0L)), Ref.unsafe[IO, FlowMapArchive](FlowMapArchive(project = project)))
      initial <- store.loadInitial()
      arch <- store.loadArchive()
      _ <- store.state.set(initial)
      _ <- store.archive.set(arch)
      // 首写：确保 flow-map.json 存在（验收①「只有 flow-map.json 被 store 写」）
      _ <- store.persistState(initial)
    yield store
