package nebflow.core.project

import cats.effect.std.Mutex
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.core.PathUtil
import nebflow.shared.NebflowLogger

import java.nio.charset.StandardCharsets

/**
 * 节点终态语义申报登记表（20260909 blocked-signal 设计 spec 方案 A 改造点 #1；
 * 同日作者裁定泛化：BlockedSignalRegistry → NodeReport 统一三语义命名——
 * Blocked/Pass/Failed 同为「类似的语义判断」，迁移一起迁移）。
 *
 * 节点会话内调用 node_report 工具 → register(...)；会话完成时点 NodeEngine drain
 * （take-and-remove）→ 按申报类别分流到既有链（零新链）：blocked（细分六类/泛值）→
 * blockedNode 终态化 + FeedbackRouter 重入；pass → 既有 completed 语义链；fail →
 * 既有 failed/verdict 语义链。未申报走既有文本锚定降级面（行为零变化、零放宽）。
 *
 * 形态照抄 BgTaskRegistry（nebflow.core.tools.BgTaskRegistry）的**进程内工作集**
 * 部分：Ref 单例、session 键控、consume-on-read。语义要点（spec §6 误判面）：
 *  - last-write-wins 单槽：同会话多次申报，最终意图优先；
 *  - drain 即移除：跨 turn 不残留，重复消费不可能；
 *  - cancelled/failed 路径不消费：NodeEngine cleanupRunTables 统一 remove 对称清理。
 *
 * ── 跨宿主重启持久化（#239② 面①，2026-09-15；本文件头注随新语义更新）──────────
 * **旧口径（本批关闭的缝）**：登记表是**纯进程内** Ref（头注原文「内存态不持久」）
 * ⇒ 会话申报后进程被 kill -9 / 宿主重启 / 崩溃，**申报在结构上消失**：内存没了、
 * 磁盘上从未有过副本、事件流里也没有它 —— 节点随后按「从未申报」的降级面终态化
 * （续跑输出不含 BLOCKED 锚定时走 completed 闸门链），申报意图无人知晓。
 * 缝本体判据 = `.nebflow/reports/20260915_engine-defects-impl.md:104`（三面表 面①）。
 *
 * **新语义**：每次 register / drain / remove 同步落一份 **append-only JSONL 日志**
 * （`<workspace>/.nebflow/node-reports.jsonl`，与 `flow-map-events.jsonl` 同目录同
 * 纪律：append-only、grep 友好、0 schema 迁移、独立文件不碰 flow-map.json 契约）；
 * 进程启动后**首次触碰**该日志的工作区做一次重放（replay）：未消费的 declare 行
 * 重新水合进内存工作集 ⇒ 续跑/恢复的**同一 sessionId** 在终态点的 `drain` 仍能取回
 * 申报 ⇒ 终态链照旧分流（重启不再把申报降级成「从未申报」）。
 *
 * **fail-closed 方向（本批硬要求①，方向不可倒）**：
 *  - 无持久化状态（日志缺失）⇒ `LoadOutcome.Absent`：**不据此把任何会话判成「已消费 /
 *    已处理」**——缺失只表示「本进程没有可恢复的申报」，绝不表示「申报已被处理」；
 *  - 读失败 / 件损坏 / 半写（撕裂尾行）⇒ `LoadOutcome.Degraded`：**保守**——可读的
 *    完整行照常恢复、未解析部分**原样留在盘上**（本模块**从不**截断 / 重写 / 自修复
 *    日志文件）、事件 + WARN 双留痕；🔴 禁把它静默降级成「无待处理」；
 *  - 保守的**行为**落点：本登记表为空时，既有消费者一律走既有安全方向——桥的完成门
 *    hold（不终态化、不投递、不杀会话）+ 提醒阶梯（`node-report-missing` 事件）、
 *    终态点的文本锚定降级面。即「缺申报」在这一层永远不等于「可以终态化了」。
 *
 * **写失败必须可见（硬要求②，零新静默路径）**：日志 append 失败**不改变控制流**
 * （内存工作集照常更新——比磁盘更保守的方向是保住申报），但必须留下**可机械检索**
 * 的痕迹：WARN 日志（稳定 token `NODE-REPORT-STORE-WRITE-FAILED`）+ `FlowMapEventLog`
 * 事件 `node-report-store`（`kind=write-failed`，含 session/node/路径/原文）。
 * 🔴 禁 `catch ⇒ 忽略`、禁 `.void` 吞掉失败。取证：
 *   `grep node-report-store <ws>/.nebflow/flow-map-events.jsonl`
 *   `grep NODE-REPORT-STORE-WRITE-FAILED <gateway 日志>`
 *
 * 载荷复用 BlockedFeedback（ProjectTypes.scala）零转换：category 字段承载按角色分化的
 * 值域；仅 blocked 类申报落库 blockedFeedback 字段（pass/fail/finish 申报在 drain
 * 分流处消费，不落库为 blockedFeedback）。
 */
object NodeReportRegistry:

  private val logger = NebflowLogger.forName("nebflow.node-report-registry")

  /** 持久化日志文件名（工作区数据目录下；与 flow-map-events.jsonl 同目录）。 */
  val JournalFileName: String = "node-reports.jsonl"

  /**
   * 登记表自身运维面事件类型（本批新增；`kind=` 分流，注册式扩展零 schema 迁移）：
   *   - `kind=recovered`：重放日志恢复了一条**未消费**申报（跨宿主重启/崩溃的申报
   *     仍在——重启语义的机械读数）；
   *   - `kind=degraded`：日志读失败 / 损坏 / 半写 ⇒ **保守**（未解析部分原样留盘、
   *     已解析部分照常恢复、绝不判成「无待处理」）；
   *   - `kind=write-failed`：append 失败（写失败可见面，见头注②）；
   *   - `kind=unbound`：register 未带工作区（无持久化位置）⇒ 申报只在内存，同样留痕。
   * 取证：`grep node-report-store <ws>/.nebflow/flow-map-events.jsonl`。
   */
  val StoreEventType: String = "node-report-store"

  /**
   * 稳定 grep token：append 失败的 WARN 日志标记（事件面之外的第二条可见路径，
   * 两路互备——事件写自身也可能因同一磁盘问题失败，日志行仍在）。
   */
  val WriteFailedLogToken: String = "NODE-REPORT-STORE-WRITE-FAILED"

  /** 未绑工作区的 WARN 标记（同上，第二条可见路径）。 */
  val UnboundLogToken: String = "NODE-REPORT-STORE-UNBOUND"

  /** 日志加载判词（#239② 面① fail-closed 的**方向载体**：三种取值都不是「已处理」）。 */
  sealed trait LoadOutcome

  object LoadOutcome:
    /**
     * 无日志文件 ⇒ 无持久化状态。**方向**：不据此把任何会话判成「已消费 / 已处理」；
     * 缺失状态只意味着「本进程没有可恢复的申报」，消费者照走既有安全方向（hold /
     * 提醒 / 文本锚定降级面）。
     */
    case object Absent extends LoadOutcome

    /** 日志完整读入（`recovered` = 恢复的未消费申报条数；0 = 日志存在但无可恢复项）。 */
    final case class Loaded(recovered: Int) extends LoadOutcome

    /**
     * 读失败 / 件损坏 / 半写 ⇒ 保守：已解析部分照常恢复、未解析部分原样留盘、
     * 事件与 WARN 双留痕；**禁**静默降级为「无待处理」。
     */
    final case class Degraded(reason: String) extends LoadOutcome

  end LoadOutcome

  /** 登记表项 = 申报内容 + 归属键（归属键只服务持久化与审计留痕，不参与既有语义判据）。 */
  private final case class Entry(
    sessionId: String,
    nodeId: String,
    project: String,
    workspace: String,
    feedback: BlockedFeedback,
    /**
     * true = 本项是**从磁盘日志重放恢复**的（跨宿主重启/崩溃的申报）。保守补偿口径只对
     * 它启用（见 [[remove]]）：进程内残留的对称清理是既有行为，零回归；重放恢复项被
     * 清理路径丢弃而未经终态消费 ⇒ 全文补偿写回（跨重启的申报不得无声消失）。
     */
    recovered: Boolean
  )

  /** 进程内工作集（既有语义的唯一载体；持久化只是它的镜像）。 */
  private val signals: Ref[IO, Map[String, Entry]] = Ref.unsafe(Map.empty)

  /** 每工作区日志的加载判词（记忆化：一个工作区在本进程内**恰好加载一次**）。 */
  private val loaded: Ref[IO, Map[String, LoadOutcome]] = Ref.unsafe(Map.empty)

  /**
   * append 串行化（同一日志文件的多 fiber 并发追加不交错；与 shell.scala:1116 同款
   * `Mutex[IO].memoize.flatten` 单点创建惯用法）。
   */
  private val appendLock: IO[Mutex[IO]] = Mutex[IO].memoize.flatten

  /**
   * 日志文件路径：与 [[FlowMapEventLog]] 同款构式（同一工作区数据目录，同一纪律）。
   * `private[project]`：供 spec / 证据脚本读**生产口径的同一个路径**（不复制公式）。
   */
  private[project] def journalPath(workspace: String): os.Path =
    os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / JournalFileName

  // ── 公开 API（register 之外与批前逐字同形：drain / peek / remove 的签名与语义未变）──

  /**
   * 登记申报（last-write-wins：同会话重复调用覆盖，最终意图优先）+ **同步落日志**。
   *
   * 参数 `workspace`/`project`/`nodeId` 只服务持久化与审计留痕（register 是唯一新增
   * 归属键的入口——drain/peek/remove 仍只按 sessionId 取用）。`workspace` 为空 ⇒ 无
   * 持久化位置：**照常登记**（内存语义零变化）但**必须可见**（WARN + `kind=unbound`
   * 事件）——「没有位置可写」不得等同于「悄悄不持久」。
   */
  def register(
    workspace: String,
    project: String,
    nodeId: String,
    sessionId: String,
    feedback: BlockedFeedback
  ): IO[Unit] =
    if workspace.trim.isEmpty then
      unboundVisible(project, nodeId, sessionId) *>
        signals.update(_.updated(sessionId, Entry(sessionId, nodeId, project, "", feedback, recovered = false)))
    else
      ensureLoaded(workspace) *>
        appendLine(
          workspace,
          project,
          nodeId,
          Json.obj(
            "op" -> "declare".asJson,
            "ts" -> Json.fromLong(System.currentTimeMillis()),
            "project" -> project.asJson,
            "node" -> nodeId.asJson,
            "session" -> sessionId.asJson,
            "category" -> feedback.category.asJson,
            "detail" -> feedback.detail.asJson,
            "suggestion" -> feedback.suggestion.asJson
          )
        ) *>
        // 注：append 失败（appendLine 内已 WARN + 事件留痕）**不改变**内存语义，也不改变
        // 本项的 recovered 归属——归属由「是否从磁盘重放恢复」单点决定（见 [[Entry]]）。
        signals.update { m =>
          val prior = m.get(sessionId)
          // recovered 取并（粘性）：重放恢复项被同会话新申报覆盖时，它**仍是**跨重启来的
          // 申报（盘上旧 declare 未消费）⇒ remove 的保守补偿口径不得因此失效。
          m.updated(
            sessionId,
            Entry(sessionId, nodeId, project, workspace, feedback, recovered = prior.exists(_.recovered))
          )
        }

  /**
   * 完成时点消费（take-and-remove）：命中返回申报并清槽；未命中 None。消费同样落
   * 日志（consume 行）——否则重启会把已消费的申报**复活**成待消费。
   */
  def drain(sessionId: String): IO[Option[BlockedFeedback]] =
    ensureLoadedMounted *>
      signals.modify(m => (m - sessionId, m.get(sessionId))).flatMap {
        case Some(e) => consumeLine(e, "drain").as(Some(e.feedback))
        case None => IO.pure(None)
      }

  /**
   * 非消费读（noderpt 批 A 段 2026-09-11）：观察桥的 Completed 分支与未申报提醒
   * 扫描腿用它判「该会话是否已申报」，**不消费**——消费语义仍唯一保留在终态点
   * （`NodeEngine` 桥放行后的 `drain` → completeNode 分流），二者拆开后「申报 ⇒
   * 放行」与「未申报 ⇒ hold + 起表」才能在同一 Completed 事件里共存。
   *
   * 跨重启面（#239②）：读之前先保证已挂载项目的日志已重放 ⇒ 重启后的 `peek` 能
   * 看到恢复的申报（桥的 hold 腿与提醒腿据此按「已申报」放行，而不是把节点永久
   * hold 到人工处置）。
   */
  def peek(sessionId: String): IO[Option[BlockedFeedback]] =
    ensureLoadedMounted *> signals.get.map(_.get(sessionId).map(_.feedback))

  /**
   * 清理钩子（不消费）：NodeEngine cleanupRunTables 对称清理点调用——
   * cancelled/failed/异常退出路径的残留申报在此兜底移除（幂等 no-op 安全）。
   *
   * #239② 增补（保守方向）：若被移除的项**在磁盘日志上有未消费的 declare**
   * （= 跨宿主重启恢复来的申报，随后被清理路径丢弃而**未经**任何终态消费），则把
   * 申报全文补偿写回审计流（复用 #239① 的 `node-report-unconsumed` 类型，`kind=
   * recovered-discard`）+ WARN ⇒ 「跨重启的申报不会无声消失」。进程内残留的对称
   * 清理（`recovered=false`）保持既有行为逐字不变（零回归）。
   */
  def remove(sessionId: String): IO[Unit] =
    ensureLoadedMounted *>
      signals.modify(m => (m - sessionId, m.get(sessionId))).flatMap {
        case Some(e) if e.recovered =>
          FlowMapEventLog
            .append(
              e.workspace,
              e.project,
              e.nodeId,
              NodeEngine.ReportUnconsumedEventType,
              s"kind=recovered-discard node_report NOT consumed — a declaration recovered from the journal " +
                s"(cross-restart) was dropped by a cleanup path without any terminal consumption; " +
                s"session=$sessionId category=${e.feedback.category} detail=${e.feedback.detail} " +
                s"suggestion=${e.feedback.suggestion}"
            )
            .handleErrorWith(t =>
              logger.warn(
                s"${WriteFailedLogToken} could not append the recovered-discard audit line " +
                  s"for session=$sessionId (${Option(t.getMessage).getOrElse(t.toString)})"
              )
            ) *>
            logger.warn(
              s"Node '${e.nodeId}' declaration (${e.feedback.category}) recovered from the journal " +
                "was discarded by a cleanup path without a terminal consumption — compensated into " +
                "node-report-unconsumed (kind=recovered-discard)"
            ) *>
            consumeLine(e, "remove")
        case Some(e) => consumeLine(e, "remove")
        case None => IO.unit
      }

  /**
   * 加载判词（证据 / 门禁读面）：读失败 / 损坏 ⇒ [[LoadOutcome.Degraded]]，
   * **绝不**返回「已处理」。
   */
  def loadOutcome(workspace: String): IO[LoadOutcome] =
    if workspace.trim.isEmpty then IO.pure(LoadOutcome.Absent) else ensureLoaded(workspace)

  /**
   * 对当前**已挂载**项目逐个加载日志（boot 可用的一次性入口；`drain/peek/remove`
   * 内部已自动调用，此入口供 boot 链/证据脚本显式触发）。
   */
  def loadMounted(): IO[Unit] =
    mountedProjects.flatMap(_.traverse_ { case (ws, _) => ensureLoaded(ws).void })

  /**
   * **仅测试**：进程内「重启」仿真——清空工作集与加载记账，使下一次读从磁盘日志
   * 重新水合（真重启 = 新 JVM；本钩子只为单测提供等价入口）。生产**无调用点**。
   */
  private[project] def resetForRestartSimulation(): IO[Unit] =
    signals.set(Map.empty) *> loaded.set(Map.empty)

  // ── 日志重放（fail-closed 的读面单点） ──────────────────────────────────────

  private def ensureLoaded(workspace: String): IO[LoadOutcome] =
    loaded.get.map(_.get(workspace)).flatMap {
      case Some(o) => IO.pure(o)
      case None =>
        appendLock.flatMap(_.lock.surround {
          loaded.get.map(_.get(workspace)).flatMap {
            case Some(o) => IO.pure(o)
            case None =>
              loadJournal(workspace).flatMap { o =>
                loaded.update(_ + (workspace -> o)).as(o)
              }
          }
        })
    }

  /** 已挂载项目 → (workspace, projectName)（boot 与 lazy 加载共用的枚举面单点）。 */
  private def mountedProjects: IO[List[(String, String)]] =
    ProjectRuntimeRegistry.all
      .map(_.map(rt => rt.project.workspace -> rt.project.name).distinct)
      .handleErrorWith(t =>
        logger
          .warn(
            s"node-report registry: mounted-project enumeration failed " +
              s"(${Option(t.getMessage).getOrElse(t.toString)}) — journal load skipped this round"
          )
          .as(Nil)
      )

  /** 读面：只把**已挂载**项目的日志载入（工作区集合 = 挂载面；未挂载项目没有活会话）。 */
  private def ensureLoadedMounted: IO[Unit] =
    mountedProjects.flatMap(_.traverse_ { case (ws, _) => ensureLoaded(ws).void })

  private def loadJournal(workspace: String): IO[LoadOutcome] =
    val path = journalPath(workspace)
    IO.blocking {
      if os.exists(path) then Some(os.read.bytes(path)) else None
    }.attempt
      .flatMap {
        case Left(t) =>
          degraded(
            workspace,
            path,
            s"journal unreadable (${t.getClass.getSimpleName}: " +
              s"${Option(t.getMessage).getOrElse(t.toString)})"
          )
        case Right(None) => IO.pure(LoadOutcome.Absent)
        case Right(Some(bytes)) => replay(workspace, path, bytes)
      }

  end loadJournal

  /**
   * 重放：declare 入表 / consume 出表；任何不可解析或不可识别的内容 ⇒ 判词降级
   * （但**已解析部分照常生效**、盘上字节一个都不动）。
   */
  private def replay(workspace: String, path: os.Path, bytes: Array[Byte]): IO[LoadOutcome] =
    val text = new String(bytes, StandardCharsets.UTF_8)
    val parts = text.split("\n", -1)
    val (completeLines, tornTail) =
      if parts.isEmpty then (List.empty[String], "")
      else if parts.last.isEmpty then (parts.dropRight(1).toList, "")
      else (parts.dropRight(1).toList, parts.last)
    // 重放：不可解析 / 不可识别的行**计入 badLines**（判词降级），但已解析部分照常生效。
    var badLines: Int = 0
    val replayed: Map[String, Entry] =
      completeLines.foldLeft(Map.empty[String, Entry]) { (acc, line) =>
        if line.trim.isEmpty then acc
        else
          parse(line) match
            case Left(_) =>
              badLines += 1
              acc
            case Right(js) =>
              def str(k: String): Option[String] = js.hcursor.get[String](k).toOption
              str("op") match
                case Some("declare") =>
                  (str("session"), str("category")) match
                    case (Some(sid), Some(cat)) if sid.nonEmpty && cat.nonEmpty =>
                      acc.updated(
                        sid,
                        Entry(
                          sessionId = sid,
                          nodeId = str("node").getOrElse(""),
                          project = str("project").getOrElse(""),
                          workspace = workspace,
                          feedback = BlockedFeedback(cat, str("detail").getOrElse(""), str("suggestion").getOrElse("")),
                          recovered = true
                        )
                      )
                    case _ =>
                      badLines += 1
                      acc
                case Some("consume") => str("session").fold(acc)(acc - _)
                case _ =>
                  badLines += 1
                  acc
              end match
      }
    val recovered = replayed.size
    // 逐条恢复留痕（可 grep 的「申报仍在」读数；全量 category/detail，不截断）
    val announce = replayed.toList.sortBy(_._1).traverse_ { case (sid, e) =>
      FlowMapEventLog
        .append(
          workspace,
          e.project,
          e.nodeId,
          StoreEventType,
          s"kind=recovered session=$sid node=${e.nodeId} category=${e.feedback.category} " +
            s"detail=${e.feedback.detail} suggestion=${e.feedback.suggestion} " +
            "(unconsumed declaration restored from the journal after a restart/crash)"
        )
        .handleErrorWith(t =>
          logger.warn(
            s"${WriteFailedLogToken} could not append the recovered-declaration audit line " +
              s"for session=$sid (${Option(t.getMessage).getOrElse(t.toString)})"
          )
        )
    }
    val degradedBy: Option[String] =
      if tornTail.nonEmpty then
        Some(
          s"torn tail line at offset ${text.length - tornTail.length} " +
            s"(${tornTail.length} byte(s) kept on disk, never truncated)"
        )
      else if badLines > 0 then
        Some(
          s"$badLines journal line(s) unparseable or carrying an unknown/incomplete shape " +
            "(kept on disk, never truncated)"
        )
      else None
    // 内存工作集合并：内存优先（同进程内新申报胜过日志里的旧副本）
    signals.update { m =>
      replayed.foldLeft(m) { case (acc, (sid, e)) => if acc.contains(sid) then acc else acc.updated(sid, e) }
    } *>
      (degradedBy match
        case Some(reason) => degraded(workspace, path, reason, recovered)
        case None => announce.as(LoadOutcome.Loaded(recovered)))

  end replay

  /** 读面降级留痕（保守方向：不动盘上字节，只报告 + 保留可读部分）。 */
  private def degraded(workspace: String, path: os.Path, reason: String, recovered: Int = 0): IO[LoadOutcome] =
    FlowMapEventLog
      .append(
        workspace,
        "",
        "",
        StoreEventType,
        s"kind=degraded path=$path reason=$reason recoveredKept=$recovered — CONSERVATIVE: the journal is left " +
          "byte-for-byte untouched (never truncated/repaired) and the unreadable part is NOT treated as " +
          "\"nothing pending\"; consumers keep the existing safe direction (completion hold / reminder ladder / " +
          "text-anchor fallback)"
      )
      .handleErrorWith(t =>
        logger.warn(
          s"${WriteFailedLogToken} could not append the degraded-load audit line " +
            s"(${Option(t.getMessage).getOrElse(t.toString)})"
        )
      ) *>
      logger.warn(
        s"node-report journal DEGRADED for workspace '$workspace' ($path): $reason — " +
          s"conservative direction kept: no truncation, $recovered recoverable declaration(s) still loaded, " +
          "the unreadable part is not read as \"nothing pending\""
      ) *>
      IO.pure(LoadOutcome.Degraded(reason))

  // ── 写面（append-only；失败必须可见，永不改变控制流） ────────────────────────

  private def consumeLine(e: Entry, by: String): IO[Unit] =
    if e.workspace.trim.isEmpty then IO.unit
    else
      appendLine(
        e.workspace,
        e.project,
        e.nodeId,
        Json.obj(
          "op" -> "consume".asJson,
          "ts" -> Json.fromLong(System.currentTimeMillis()),
          "session" -> e.sessionId.asJson,
          "by" -> by.asJson
        )
      ).void

  /**
   * 日志 append（唯一写点）：`Mutex` 串行化；失败 ⇒ WARN + 事件双留痕，**不改变控制流**
   * （内存工作集仍持有申报——比「丢掉申报」更保守的方向）。
   */
  private def appendLine(workspace: String, project: String, nodeId: String, line: Json): IO[Unit] =
    appendLock.flatMap(_.lock.surround {
      IO.blocking(os.write.append(journalPath(workspace), line.noSpaces + "\n", createFolders = true))
        .attempt
        .flatMap {
          case Right(_) => IO.unit
          case Left(t) =>
            val msg = Option(t.getMessage).getOrElse(t.toString)
            logger.warn(
              s"${WriteFailedLogToken} node-report journal append failed for workspace " +
                s"'$workspace' (${t.getClass.getSimpleName}: $msg) — the declaration stays in the in-process " +
                "registry only (it will NOT survive a restart/crash); this failure is visible by design"
            ) *>
              FlowMapEventLog
                .append(
                  workspace,
                  project,
                  nodeId,
                  StoreEventType,
                  s"kind=write-failed path=${journalPath(workspace)} op=append error=${t.getClass.getSimpleName}: $msg " +
                    "— the declaration is IN-MEMORY ONLY (not durable); visible by design, never swallowed"
                )
                .handleErrorWith(t2 =>
                  logger.warn(
                    s"${WriteFailedLogToken} could not append the write-failed audit line either " +
                      s"(${Option(t2.getMessage).getOrElse(t2.toString)}) — the WARN line above is the visible record"
                  )
                )
        }
    })

  /**
   * 未绑工作区（无持久化位置）的可见留痕：控制流不变（照常登记），但必须可机械检索
   * （WARN 稳定 token；此处**没有**可写事件流的工作区 ⇒ 日志面是唯一路径）。
   */
  private def unboundVisible(project: String, nodeId: String, sessionId: String): IO[Unit] =
    logger.warn(
      s"${UnboundLogToken} node_report declaration for session=$sessionId node=$nodeId " +
        s"(project=$project) has NO workspace binding — the declaration is IN-MEMORY ONLY and will NOT " +
        "survive a host restart/crash; visible by design, never silent"
    )
end NodeReportRegistry
