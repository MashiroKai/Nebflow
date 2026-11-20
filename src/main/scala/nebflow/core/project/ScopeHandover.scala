package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * ScopeHandover —— delegate 作用域图的**显式归属移交**（2026-11-03 作者报障批 ·
 * 缺陷⑨「scope 不可迁移」）。
 *
 * 背景：一个 delegate 一张作用域图（`<基工作区>/.nebflow/flow-maps/<delegateId>/`，
 * [[DelegateFlowHosts.mount]] 自根挂载）。续命派生的新会话挂载的是**一张全新的空图**
 * ——旧 16 节点链仍属已死的旧 scope，新会话 `NodeList` 报 `DELEGATE_SCOPE_FOREIGN`
 * 拿不到任何节点、旧节点的完成/补充消息也永远投不到活实例（作者截图实证：
 * delegate-nebflow-5dcbf6c5 两条硬错误）。移交让派生体**合法继承**旧链：
 *
 *   ① 盘面搬移：`flow-map.json`（活动区全图）＋ `flow-map-archive/`（归档区分批
 *      文件）＋ `chain-ledger.json`（链号台账）＋ `tasks/` / `results/`（task 全文
 *      与结果全文 per-node 文件）从旧 scope 目录复制到新 scope 目录——**先复制后清空**
 *      （搬移 = 数据换址，非删除；旧目录留下空图 + `scope-handed-over` 标记行）。
 *   ② 所有权切换：旧图清空（活动区 0 节点）⇒ 全引擎对节点 id 的解析唯一命中新
 *      scope（`deliverToRootNodeRef` 的跨域歧义不会出现）。
 *   ③ 审计：ChainStore `owner-migration` 行（from/to/节点数/文件数）＋ 旧 scope 的
 *      `scope-handed-over` 标记。事件随后由新 scope 的引擎照常投递（活实例在册）。
 *
 * 幂等：新图已有节点（移交过/派生体自己建过图）⇒ 零动作（绝不覆盖活图）。
 * fail-soft：源目录缺失/复制失败 ⇒ `Left(reason)`，调用方照旧完成派生（行为回退
 * 到移交前形态，绝不阻断派生主链路）——报告口径如实。
 */
object ScopeHandover:

  private val logger = NebflowLogger.forName("nebflow.core.scope-handover")

  /** 作用域目录读数（与 [[DelegateFlowHosts]] 的 scopeDir 同构）。 */
  def scopeDir(baseWorkspace: String, delegateId: String): os.Path =
    os.Path(baseWorkspace, PathUtil.dataRoot) / ".nebflow" / FlowMapStore.FlowMapsDirName / delegateId

  private val FlowMapFile = "flow-map.json"
  private val LedgerFile = "chain-ledger.json"

  /** 移交读数（审计行 payload 数据源）。 */
  final case class HandoverReport(
    fromScope: String,
    toScope: String,
    activeNodes: Int,
    archivedNodes: Int,
    filesMoved: Int
  ):
    /** 是否真的搬了东西（源图非空且复制成功）。 */
    def moved: Boolean = activeNodes > 0 || archivedNodes > 0

  private def nodeCountOf(json: Option[Json]): Int =
    json
      .flatMap(_.hcursor.get[Map[String, Json]]("nodes").toOption)
      .map(_.size)
      .getOrElse(0)

  private def countArchiveNodes(archiveDir: os.Path): Int =
    try
      if !os.exists(archiveDir) then 0
      else
        os.list(archiveDir)
          .filter(_.ext == "json")
          .map(p => nodeCountOf(io.circe.parser.parse(os.read(p)).toOption))
          .sum
    catch case _: Throwable => 0

  private def copyTree(src: os.Path, dst: os.Path): Int =
    if !os.exists(src) then 0
    else
      os.makeDir.all(dst)
      os.list(src).toList.foldLeft(0) { (n, p) =>
        if os.isDir(p) then n + copyTree(p, dst / p.last)
        else
          os.copy.over(p, dst / p.last, createFolders = true)
          n + 1
      }

  /**
   * 执行移交（盘面 + 审计）。纯盘面动作，不碰引擎实例——重挂由
   * [[handoverAndRemount]] 负责。
   *
   * @return Right(report)；`report.moved == false` = 源图本来就空（无可移交，零动作）。
   *         Left = 失败原因（新图已被占用 / 读盘失败——派生照旧完成）。
   */
  def handover(
    fromDelegateId: String,
    fromBaseWorkspace: String,
    toDelegateId: String,
    toBaseWorkspace: String,
    taskId: Option[String]
  ): IO[Either[String, HandoverReport]] =
    val srcDir = scopeDir(fromBaseWorkspace, fromDelegateId)
    val dstDir = scopeDir(toBaseWorkspace, toDelegateId)
    IO.blocking {
      val srcMap = srcDir / FlowMapFile
      if !os.exists(srcMap) then
        // 源图不存在 = 旧会话本来就没有图（纯 kernel 用法）⇒ 无可移交。
        Right(HandoverReport(fromDelegateId, toDelegateId, 0, 0, 0))
      else
        val srcJson = io.circe.parser.parse(os.read(srcMap)).toOption
        val active = nodeCountOf(srcJson)
        val archived = countArchiveNodes(srcDir / FlowMapStore.ArchiveDirName)
        if active == 0 && archived == 0 then Right(HandoverReport(fromDelegateId, toDelegateId, 0, 0, 0))
        else
          val dstJson =
            if os.exists(dstDir / FlowMapFile) then io.circe.parser.parse(os.read(dstDir / FlowMapFile)).toOption
            else None
          val dstActive = nodeCountOf(dstJson)
          if dstActive > 0 then
            Left(s"target scope '$toDelegateId' already holds $dstActive active node(s) — refusing to overwrite")
          else if srcDir == dstDir then Right(HandoverReport(fromDelegateId, toDelegateId, active, archived, 0))
          else
            var moved = 0
            os.makeDir.all(dstDir)
            // ① 活动区图。
            os.copy.over(srcMap, dstDir / FlowMapFile, createFolders = true)
            moved += 1
            // ② 归档区分批文件 / ③ 链号台账 / ④ task·result per-node 全文。
            moved += copyTree(srcDir / FlowMapStore.ArchiveDirName, dstDir / FlowMapStore.ArchiveDirName)
            val srcLedger = srcDir / LedgerFile
            if os.exists(srcLedger) then
              os.copy.over(srcLedger, dstDir / LedgerFile, createFolders = true)
              moved += 1
            moved += copyTree(srcDir / FlowMapStore.TasksDirName, dstDir / FlowMapStore.TasksDirName)
            moved += copyTree(srcDir / FlowMapStore.ResultsDirName, dstDir / FlowMapStore.ResultsDirName)
            // ②' 所有权切换：旧图清空（换址不删除——数据已在新 scope）。
            val project = srcJson.flatMap(_.hcursor.get[String]("project").toOption).getOrElse("stand")
            val emptied =
              Json.obj(
                "v" -> 1.asJson,
                "project" -> project.asJson,
                "updatedAt" -> System.currentTimeMillis().asJson,
                "nodes" -> io.circe.JsonObject.empty.asJson
              )
            os.write.over(srcMap, emptied.noSpaces)
            // ③' ChainStore 审计行（链号键 + 新旧 delegate 键三处可查）。
            val payload = Json.obj(
              "action" -> "scope-handover".asJson,
              "from" -> fromDelegateId.asJson,
              "to" -> toDelegateId.asJson,
              "activeNodes" -> active.asJson,
              "archivedNodes" -> archived.asJson,
              "filesMoved" -> moved.asJson,
              "fromScopeDir" -> srcDir.toString.asJson,
              "toScopeDir" -> dstDir.toString.asJson
            )
            nebflow.core.delegate.ChainStore.appendSync(
              nebflow.core.delegate.ChainStore.keyFor(taskId, fromDelegateId),
              "owner-migration",
              payload
            )
            nebflow.core.delegate.ChainStore.appendSync(toDelegateId, "owner-migration", payload)
            nebflow.core.delegate.ChainStore.appendSync(
              fromDelegateId,
              "scope-handed-over",
              Json.obj(
                "action" -> "scope-handed-over".asJson,
                "to" -> toDelegateId.asJson,
                "at" -> System.currentTimeMillis().asJson
              )
            )
            logger.info(
              s"[scope-handover] '$fromDelegateId' → '$toDelegateId' moved (active=$active, archived=$archived, files=$moved)"
            )
            Right(HandoverReport(fromDelegateId, toDelegateId, active, archived, moved))
          end if
        end if
      end if
    }.handleErrorWith(e =>
      logger
        .warn(
          s"[scope-handover] '$fromDelegateId' → '$toDelegateId' failed (derivation continues without handover): ${e.getMessage}"
        )
        .as(Left(s"scope handover failed: ${e.getMessage}"))
    )

  end handover

  /**
   * 派生腿的完整接线：卸旧宿主（如在册）→ 移交盘面 → 重挂新宿主（重开已换图的
   * scope 目录；`mount` 幂等但**内存里的旧空图不会自动刷新**——故先 unmount 再
   * mount）。任何一步失败都不阻断派生主链路（fail-soft）。
   */
  def handoverAndRemount(
    fromDelegateId: String,
    fromRecord: nebflow.core.delegate.DelegateRegistry.Record,
    toDelegateId: String,
    toBaseWorkspace: String,
    toProject: Option[String],
    toEngineCwd: String,
    system: nebflow.actor.ActorSystem,
    resources: nebflow.core.AgentRuntimePort,
    wsSend: Option[Json => IO[Unit]]
  ): IO[Either[String, HandoverReport]] =
    for
      _ <- DelegateFlowHosts.unmount(fromDelegateId).handleError(_ => ())
      _ <- DelegateFlowHosts.unmount(toDelegateId).handleError(_ => ()) // 空图宿主先摘，重挂才换得上去
      report <- handover(
        fromDelegateId = fromDelegateId,
        fromBaseWorkspace = fromRecord.projectWorkspace.getOrElse(fromRecord.cwd),
        toDelegateId = toDelegateId,
        toBaseWorkspace = toBaseWorkspace,
        taskId = fromRecord.taskId
      )
      _ <- report match
        case Right(r) if r.moved =>
          DelegateFlowHosts
            .mount(
              delegateId = toDelegateId,
              project = toProject,
              baseWorkspace = toBaseWorkspace,
              engineCwd = toEngineCwd,
              system = system,
              resources = resources,
              wsSend = wsSend
            )
            .handleErrorWith(e =>
              logger
                .warn(s"[scope-handover] remount after handover failed for '$toDelegateId': ${e.getMessage}")
                .as(None)
            )
            .void
        case Right(_) =>
          // 无可移交：把派生会话自己的宿主挂回去（unmount 摘掉了它）。
          DelegateFlowHosts
            .mount(
              delegateId = toDelegateId,
              project = toProject,
              baseWorkspace = toBaseWorkspace,
              engineCwd = toEngineCwd,
              system = system,
              resources = resources,
              wsSend = wsSend
            )
            .handleErrorWith(_ => IO.pure(None))
            .void
        case Left(_) => IO.unit
    yield report

end ScopeHandover
