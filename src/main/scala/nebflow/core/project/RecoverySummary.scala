package nebflow.core.project

import cats.effect.IO
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.core.delegate.{DelegateRegistry, DelegateRoundLedger}
import nebflow.core.mail.SessionMailbox
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * RecoverySummary —— delegate 作用域的**可验证恢复摘要**（启动加载惰性化 · 硬前提门禁批
 * feat/recovery-summary-20261116；规格 = 启动加载清单
 * `startup-load-inventory__20261116_081800.md` §「后续裁决与验证缺口」#1 与 §「按阶段的
 * 加载与裁决清单」行「后台：delegate 作用域扫盘及崩溃恢复」）。
 *
 * == 本批是什么 / 不是什么 ==
 * 只建**摘要机制本体**：生成（[[generate]]）／校验+回退（[[inspect]]）／持久化形态
 * （`<基工作区>/.nebflow/flow-maps/<scope>/recovery-summary.json`，随作用域目录族——
 * 与 flow-map.json 同目录同寿命）。**不落地任何延后加载档**：boot sweep / 挂载链零改动，
 * 摘要的生产接线（何时何地调 [[generate]]）与消费接线（「命中→可延后」）留给后续批次。
 * 后续批的查询面 = [[inspect]] + [[Summary.quiet]]：
 *
 * {{{
 * RecoverySummary.inspect(baseWorkspace, scope) match
 *   case Verdict.Trusted(s) if s.quiet => // 摘要命中 → 该作用域可延后完整打开
 *   case _                              => // 保守回退 → 按全量加载处理（口径见下）
 * }}}
 *
 * == 摘要覆盖的五个状态面（规格逐字）==
 *  1. 非终态节点（[[Facets.nonTerminalNodes]]）——活动区 status ∉
 *     [[NodeLifecycle.Terminal]] 的节点（含 legacy `blocked` 与非终态 `interrupted`；
 *     判据 = 终态集的补集单点，不在此复刻状态清单）。
 *  2. 人工保持（[[Facets.humanHolds]]）——活动区持久执行保持
 *     （`FlowMapState.manualRecoveryNodes`，显式恢复才清；**全集计数**——引用了已不
 *     在图内节点的保持同样算「有人工恢复门」，保守面）。
 *  3. 孤儿暂停（[[Facets.orphanSuspension]]）——属主 dispatcher 不在 delegate 注册表
 *     （[[OrphanSuspension.orphanCandidate]]，与
 *     `ProjectCrashRecovery.recoverDelegateScope` 的孤儿分支同判据：`DelegateRegistry
 *     .find(scope).isEmpty`）＋ 链台账已落暂停的链（`pausedChains`）。
 *  4. 轮次（[[Facets.rounds]]）——未结算轮（`delegates/<id>.rounds.jsonl` 现值行
 *     `settledAt` 为空者）＋ 注册表当前轮指针（观测面）。
 *  5. 待投递（[[Facets.pendingDeliveries]]）——四路欠账：node-message 台账未消费件
 *     （queued/injected/replayed，作用域在图节点的）；完成债未结算
 *     （`sessions/<id>/completion-debt.json` phase ∉ {settled, cancelled}——**读面损坏
 *     按欠账计**，保守）；dispatch-notify 未上报终态（[[DispatchNotify.redeliverCandidate]]
 *     单点）；根结果未达（[[NodeEngine.rootResultUndelivered]] 单点，活动+归档双区）。
 *
 * == 可验证 = 自完整性 + 新鲜度双闸 ==
 *  - **自完整性（digest）**：`digest = sha256(canonical(payload))`——payload 由编码器
 *     从解码值重编（排序键/定序列表，跨 JVM 确定性）。读面重算比对，任何字节面篡改/
 *    半写 ⇒ `Fallback(corrupt)`。
 *  - **新鲜度（sources 指纹）**：生成时对全部事实源文件记 `(path, present, size,
 *     mtime)` 指纹（flow-map.json、归档族、chain-ledger checkpoint+journal、delegate
 *     注册记录、轮次台账、完成债、node-message 台账）；校验时现读重比，任何漂移 ⇒
 *     `Fallback(stale)`——「摘要说的是过去」不可能被当成「摘要说的是现在」。
 *
 * == 保守回退语义（规格：缺失/损坏按全量加载，绝不因摘要残缺漏加载控制面）==
 * [[Verdict.Fallback]] 的消费纪律恒为**按全量加载处理**（后续批的唯一合法用途）；缺失
 * （`missing`）、坏形（`corrupt`）、schema 升格（`schema`）、指纹漂移（`stale`）、读失败
 * （`unreadable`）五路一律回退，[[inspect]] 永不抛、永不返回假 quiet。**`archivedAt`
 * 不参与 quiet 判定**（只作 [[OrphanSuspension.registryArchivedAt]] 观测面落档）——
 * 「归档 ≠ 安全跳过」是清单 §「作用域」的实证结论（687 个已归档身份中 13 个仍带非终态
 * 节点 / 人工保持），quiet 只由五个状态面派生。
 *
 * == 与既有机制的关系 ==
 * 判据全部引单点，本文件零复刻：非终态 = [[NodeLifecycle.Terminal]] 补集；孤儿 =
 * `recoverDelegateScope` 同判据；轮次 = [[DelegateRoundLedger.currentRounds]]；完成债
 * 原始读（`CompletionDebtStore` 的读面带隔离区副作用，摘要只读故直读文件、Debt 编解码
 * 语义一致）；投递候选 = DispatchNotify / NodeEngine 伴生单点（本批随摘要落地抽取）。
 * node-message 台账锚点 = 引擎座位（注册记录 `cwd`，缺记录回落基工作区——与
 * `recoverDelegateScope` 的座位解析逐字同款）。
 */
object RecoverySummary:

  private val logger = NebflowLogger.forName("nebflow.project.recovery-summary")

  /** 摘要文件名（作用域目录内，与 flow-map.json 同目录）。 */
  val SummaryFileName: String = "recovery-summary.json"

  /** 摘要 schema 版本（升格 ⇒ 旧摘要整体 `Fallback(schema)` 回退，不试图兼容读）。 */
  val SchemaVersion: Int = 1

  // ── 模型 ─────────────────────────────────────────────────────────────────

  /** 单个事实源文件的指纹（present=false = 当时缺席；size/mtime 为 0）。 */
  final case class FileStamp(path: String, present: Boolean, size: Long, mtime: Long)

  /** 一个命名事实源的指纹组（有序；如 chainLedger = checkpoint + journal 两件）。 */
  final case class SourceStamps(name: String, files: List[FileStamp])

  /** 状态面①非终态节点（ids / byStatus 键均定序，编码确定性）。 */
  final case class NonTerminalNodes(count: Int, ids: List[String], byStatus: List[(String, Int)])

  /** 状态面②人工保持。 */
  final case class HumanHolds(count: Int, ids: List[String])

  /**
   * 状态面③孤儿暂停。`registryArchivedAt` 是**纯观测面**（落档「生成时身份已归档」
   * 这一事实），不参与 [[Summary.quiet]]——quiet 不看 archivedAt（见对象头注）。
   */
  final case class OrphanSuspension(
    orphanCandidate: Boolean,
    pausedChains: List[String],
    registryArchivedAt: Option[Long]
  )

  /** 状态面④轮次（openRoundNos = 未结算轮号升序；指针 = 注册表当前轮观测面）。 */
  final case class Rounds(
    currentRoundNo: Option[Int],
    currentRoundId: Option[String],
    openRoundNos: List[Int]
  )

  /** 状态面⑤待投递（四路欠账；ids 均升序）。 */
  final case class PendingDeliveries(
    nodeMessages: Int,
    completionDebt: Boolean,
    unnotifiedTerminals: List[String],
    undeliveredRootResults: List[String]
  ):

    def total: Int =
      nodeMessages + (if completionDebt then 1 else 0) + unnotifiedTerminals.size + undeliveredRootResults.size

  /** 五个状态面的全集。 */
  final case class Facets(
    nonTerminalNodes: NonTerminalNodes,
    humanHolds: HumanHolds,
    orphanSuspension: OrphanSuspension,
    rounds: Rounds,
    pendingDeliveries: PendingDeliveries
  )

  /**
   * 一份恢复摘要。[[quiet]] = 五个状态面全净——后续延后加载批的唯一裁决面
   * （「摘要命中 → 可延后」）；一切非 quiet 或 [[Verdict.Fallback]] = 全量加载。
   */
  final case class Summary(
    schema: Int,
    scope: String,
    generatedAt: Long,
    facets: Facets,
    sources: List[SourceStamps],
    digest: String
  ):

    def quiet: Boolean =
      facets.nonTerminalNodes.count == 0 &&
        facets.humanHolds.count == 0 &&
        !facets.orphanSuspension.orphanCandidate &&
        facets.orphanSuspension.pausedChains.isEmpty &&
        facets.rounds.openRoundNos.isEmpty &&
        facets.pendingDeliveries.total == 0
  end Summary

  /**
   * 校验裁决。[[Trusted]] = 摘要完整且新鲜（五状态面可信）；[[Fallback]] = 保守回退
   * （reason 单点分类：missing / corrupt / schema / stale / unreadable / invalid-scope），
   * 消费纪律恒为按全量加载处理。
   */
  enum Verdict:
    case Trusted(summary: Summary)
    case Fallback(reason: String)
  end Verdict

  // ── 路径（与 FlowMapStore.openScope / DelegateFlowHosts.scopeDirs 同源派生）────

  def scopeDirOf(baseWorkspace: String, scope: String): os.Path =
    os.Path(baseWorkspace, PathUtil.dataRoot) / ".nebflow" / FlowMapStore.FlowMapsDirName / scope

  def summaryPath(baseWorkspace: String, scope: String): os.Path =
    scopeDirOf(baseWorkspace, scope) / SummaryFileName

  /** 引擎座位解析（`recoverDelegateScope` 逐字同款：注册记录 cwd，坏/缺回落基工作区）。 */
  private def engineCwdOf(baseWorkspace: String, record: Option[DelegateRegistry.Record]): String =
    record
      .map(r => scala.util.Try(os.Path(r.cwd)).map(_.toString).getOrElse(baseWorkspace))
      .getOrElse(baseWorkspace)

  // ── 生成 ─────────────────────────────────────────────────────────────────

  /**
   * 生成并落盘一份作用域恢复摘要（幂等：重复生成覆盖旧档）。
   *
   * 事实读取**不经宿主挂载**（零 NodeEngine / 零 ActorSystem——那是后续批要省的成本本体）：
   * `FlowMapStore.openScope` 只读面（活动区 + 归档区 + 链台账，归档/台账语义由 store 自家
   * 载入器保证——摘要不复刻双区/journal 合并逻辑）＋注册表/轮次台账/完成债/node-message
   * 台账四个直读。指纹在**全部事实读毕之后**采集（openScope 的首写收敛先发生，指纹锚定
   * 收敛后的盘面）。生成失败 = `Left`（调用方决定；不写半档——AtomicJson 整写原子）。
   */
  def generate(baseWorkspace: String, scope: String): IO[Either[String, Summary]] =
    if !FlowMapStore.validScopeId(scope) then
      IO.pure(Left(s"recovery-summary: invalid scope id '$scope' (charset contract [a-zA-Z0-9._-], ≤128)"))
    else
      val record = DelegateRegistry.find(scope)
      val engineCwd = engineCwdOf(baseWorkspace, record)
      val projectName = record.flatMap(_.project).getOrElse(os.Path(baseWorkspace).last)
      val dir = scopeDirOf(baseWorkspace, scope)
      (for
        store <- FlowMapStore.openScope(scope, projectName, baseWorkspace)
        snap <- store.snapshot
        arch <- store.archiveSnapshot
        ledgerState <- store.chainLedgerStore.snapshot
        // ── 状态面①：非终态节点（Terminal 补集单点；legacy blocked / interrupted 自然入集）──
        nonTerminal = snap.nodes.values.filter(n => !NodeLifecycle.Terminal.contains(n.status)).toList
        // ── 状态面②：人工保持（全集——含引用已出图节点的保持，见 Facets.HumanHolds 注）──
        holds = snap.manualRecoveryNodes
        // ── 状态面③：孤儿暂停 ──
        orphan = record.isEmpty
        paused = ledgerState.pausedChains.keys.toList.sorted
        // ── 状态面④：轮次 ──
        openRounds = DelegateRoundLedger.currentRounds(scope).filter(_.settledAt.isEmpty).map(_.roundNo)
        // ── 状态面⑤：待投递（四路，候选判据全部单点引用）──
        activeIds = snap.nodes.keySet
        ledgerEntries <- NodeMessageLedger.entries(engineCwd)
        pendingMsgs = ledgerEntries.count(e => e.pending && activeIds.contains(e.nodeId))
        debtPending = completionDebtPending(scope)
        unnotified = snap.nodes.values.filter(DispatchNotify.redeliverCandidate(_)).map(_.id).toList.sorted
        undeliveredRoot =
          (snap.nodes.values ++ arch.nodes.values).filter(NodeEngine.rootResultUndelivered(_)).map(_.id).toList.sorted
        // ── 指纹最后采集（事实读毕后的盘面）──
        sources = currentStamps(baseWorkspace, scope, engineCwd)
        now = System.currentTimeMillis()
        summary = Summary(
          schema = SchemaVersion,
          scope = scope,
          generatedAt = now,
          facets = Facets(
            nonTerminalNodes = NonTerminalNodes(
              count = nonTerminal.size,
              ids = nonTerminal.map(_.id).sorted,
              byStatus = nonTerminal.groupBy(_.status).view.mapValues(_.size).toList.sortBy(_._1)
            ),
            humanHolds = HumanHolds(count = holds.size, ids = holds.toList.sorted),
            orphanSuspension = OrphanSuspension(
              orphanCandidate = orphan,
              pausedChains = paused,
              registryArchivedAt = record.flatMap(_.archivedAt)
            ),
            rounds = Rounds(
              currentRoundNo = record.flatMap(_.roundNo),
              currentRoundId = record.flatMap(_.roundId),
              openRoundNos = openRounds.sorted
            ),
            pendingDeliveries = PendingDeliveries(
              nodeMessages = pendingMsgs,
              completionDebt = debtPending,
              unnotifiedTerminals = unnotified,
              undeliveredRootResults = undeliveredRoot
            )
          ),
          sources = sources,
          digest = ""
        )
        sealed0 = withDigest(summary)
        _ <- IO.blocking(AtomicJson.writeSync(summaryPath(baseWorkspace, scope), sealed0.asJson.noSpaces))
      yield Right(sealed0)).handleErrorWith { e =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        logger
          .warn(s"[recovery-summary] generate failed for scope '$scope' (dir=$dir): $msg — no summary written")
          .as(Left(s"recovery-summary generate failed for '$scope': $msg"))
      }
  end generate

  /**
   * 完成债欠账判定（**原始读**：`CompletionDebtStore.pending` 的读面带隔离区副作用——
   * 摘要是只读面，不制造 `.corrupt` 迁移）。判定语义与其一致：文件缺席 = 无欠账；
   * 可解 ⇒ phase ∉ {settled, cancelled} 为欠账；**在场但不可解 = 欠账**（保守：读面
   * 损坏的债不能因摘要而漏加载）。
   */
  private def completionDebtPending(scope: String): Boolean =
    val p = PathUtil.dataRoot / "sessions" / SessionMailbox.safeId(scope) / "completion-debt.json"
    if !os.exists(p) then false
    else
      io.circe.parser.parse(os.read(p)).flatMap(_.as[nebflow.core.mail.CompletionDebtStore.Debt]) match
        case Right(debt) => debt.phase != "settled" && debt.phase != "cancelled"
        case Left(_) => true // 在场但坏形 ⇒ 保守按欠账

  // ── 校验 + 回退 ──────────────────────────────────────────────────────────

  /**
   * 摘要查询面（后续延后加载批的裁决入口）：读 `recovery-summary.json` → 五闸校验
   * （在场 → 可解 → schema → digest → 指纹新鲜度）→ [[Trusted]] 或 [[Fallback]]。
   * 永不抛：一切异常折叠为 `Fallback(unreadable)`。**quiet 只在 [[Trusted]] 上有意义**，
   * `Fallback` 一律全量加载。
   */
  def inspect(baseWorkspace: String, scope: String): IO[Verdict] =
    if !FlowMapStore.validScopeId(scope) then IO.pure(Verdict.Fallback(s"invalid-scope: '$scope'"))
    else
      IO.blocking {
        val p = summaryPath(baseWorkspace, scope)
        if !os.exists(p) then Verdict.Fallback(s"missing: no $SummaryFileName on disk — conservative full load")
        else
          io.circe.parser.parse(os.read(p)) match
            case Left(err) =>
              Verdict.Fallback(s"corrupt: summary JSON unparseable (${err.getMessage})")
            case Right(json) =>
              json.as[Summary] match
                case Left(err) =>
                  Verdict.Fallback(s"corrupt: summary undecodable (${err.getMessage})")
                case Right(s) =>
                  if s.schema != SchemaVersion then
                    Verdict.Fallback(s"schema: summary v${s.schema} ≠ current v$SchemaVersion — conservative full load")
                  else if digestOf(s) != s.digest then
                    Verdict.Fallback("corrupt: digest mismatch (payload drifted from its digest)")
                  else
                    // 指纹重比：座位解析按现时注册记录（记录漂移本身已被 delegateRecord 指纹捕获）。
                    val cur =
                      currentStamps(baseWorkspace, s.scope, engineCwdOf(baseWorkspace, DelegateRegistry.find(s.scope)))
                    if cur != s.sources then
                      val drifted = cur
                        .zipAll(s.sources, null, null)
                        .collect { case (a, b) if a != b => Option(a).map(_.name).getOrElse("(extra)") }
                        .distinct
                        .mkString(",")
                      Verdict.Fallback(s"stale: source stamp drift ($drifted) — conservative full load")
                    else Verdict.Trusted(s)
        end if
      }.handleErrorWith(e =>
        IO.pure(
          Verdict.Fallback(
            s"unreadable: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)} — conservative full load"
          )
        )
      )
  end inspect

  // ── 指纹 ─────────────────────────────────────────────────────────────────

  /** 事实源名（指纹组定序；drift 诊断按名报）。 */
  private val SourceFlowMap = "flowMap"
  private val SourceFlowMapArchive = "flowMapArchive"
  private val SourceChainLedger = "chainLedger"
  private val SourceDelegateRecord = "delegateRecord"
  private val SourceRoundsLedger = "roundsLedger"
  private val SourceCompletionDebt = "completionDebt"
  private val SourceNodeMessages = "nodeMessages"

  /** 现读全部事实源指纹（生成尾采与校验重比共用单点）。 */
  private def currentStamps(baseWorkspace: String, scope: String, engineCwd: String): List[SourceStamps] =
    val dir = scopeDirOf(baseWorkspace, scope)
    val archiveMonolith = dir / "flow-map-archive.json"
    val archiveBatches =
      val d = dir / "flow-map-archive"
      if os.exists(d) then os.list(d).filter(_.last.endsWith(".json")).toList.sortBy(_.last) else Nil
    def debtFile = PathUtil.dataRoot / "sessions" / SessionMailbox.safeId(scope) / "completion-debt.json"
    List(
      SourceStamps(SourceFlowMap, List(stamp(dir / "flow-map.json"))),
      SourceStamps(SourceFlowMapArchive, (archiveMonolith :: archiveBatches).map(stamp)),
      SourceStamps(
        SourceChainLedger,
        List(stamp(dir / ChainLedger.FileName), stamp(AtomicJson.journalPathOf(dir / ChainLedger.FileName)))
      ),
      SourceStamps(SourceDelegateRecord, List(stamp(PathUtil.dataRoot / "delegates" / s"$scope.json"))),
      SourceStamps(SourceRoundsLedger, List(stamp(PathUtil.dataRoot / "delegates" / s"$scope.rounds.jsonl"))),
      SourceStamps(SourceCompletionDebt, List(stamp(debtFile))),
      SourceStamps(SourceNodeMessages, List(stamp(NodeMessageLedger.path(engineCwd))))
    )
  end currentStamps

  private def stamp(p: os.Path): FileStamp =
    if !os.exists(p) then FileStamp(path = p.toString, present = false, size = 0L, mtime = 0L)
    else
      val st = os.stat(p)
      FileStamp(path = p.toString, present = true, size = st.size, mtime = st.mtime.toMillis)

  // ── 编解码 + digest（确定性编码：全部集合定序后才进 payload）────────────────

  /** payload（digest 之外的全部字段）——键序固定、集合定序，跨 JVM 确定。 */
  private def payloadOf(s: Summary): Json =
    Json.obj(
      "schema" -> s.schema.asJson,
      "scope" -> s.scope.asJson,
      "generatedAt" -> s.generatedAt.asJson,
      "facets" -> s.facets.asJson,
      "sources" -> s.sources.asJson
    )

  private def digestOf(s: Summary): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(payloadOf(s).noSpaces.getBytes("UTF-8"))
    "sha256:" + bytes.map(b => f"${b & 0xff}%02x").mkString

  /** 给 Summary 填 digest（构造收口——摘要只能经生成路径成档）。 */
  private def withDigest(s: Summary): Summary = s.copy(digest = digestOf(s))

  private given Encoder[FileStamp] = semiauto.deriveEncoder
  private given Encoder[SourceStamps] = semiauto.deriveEncoder
  private given Encoder[NonTerminalNodes] = semiauto.deriveEncoder
  private given Encoder[HumanHolds] = semiauto.deriveEncoder
  private given Encoder[OrphanSuspension] = semiauto.deriveEncoder
  private given Encoder[Rounds] = semiauto.deriveEncoder
  private given Encoder[PendingDeliveries] = semiauto.deriveEncoder
  private given Encoder[Facets] = semiauto.deriveEncoder

  private given Encoder[Summary] = (s: Summary) => payloadOf(s).mapObject(_.add("digest", s.digest.asJson))

  // 手工容错解码（DelegateRegistry 纪律：新字段的 Option 读缺失按缺省，坏档回退由
  // digest 闸兜底——解码宽容不等于信任）。
  private given Decoder[FileStamp] = (c: io.circe.HCursor) =>
    for
      path <- c.get[String]("path")
      present <- c.get[Option[Boolean]]("present").map(_.getOrElse(false))
      size <- c.get[Option[Long]]("size").map(_.getOrElse(0L))
      mtime <- c.get[Option[Long]]("mtime").map(_.getOrElse(0L))
    yield FileStamp(path, present, size, mtime)

  private given Decoder[SourceStamps] = (c: io.circe.HCursor) =>
    for
      name <- c.get[String]("name")
      files <- c.get[Option[List[FileStamp]]]("files").map(_.getOrElse(Nil))
    yield SourceStamps(name, files)

  private given Decoder[NonTerminalNodes] = (c: io.circe.HCursor) =>
    for
      count <- c.get[Option[Int]]("count").map(_.getOrElse(0))
      ids <- c.get[Option[List[String]]]("ids").map(_.getOrElse(Nil))
      byStatus <- c.get[Option[List[(String, Int)]]]("byStatus").map(_.getOrElse(Nil))
    yield NonTerminalNodes(count, ids, byStatus)

  private given Decoder[HumanHolds] = (c: io.circe.HCursor) =>
    for
      count <- c.get[Option[Int]]("count").map(_.getOrElse(0))
      ids <- c.get[Option[List[String]]]("ids").map(_.getOrElse(Nil))
    yield HumanHolds(count, ids)

  private given Decoder[OrphanSuspension] = (c: io.circe.HCursor) =>
    for
      orphan <- c.get[Option[Boolean]]("orphanCandidate").map(_.getOrElse(true))
      paused <- c.get[Option[List[String]]]("pausedChains").map(_.getOrElse(Nil))
      archivedAt <- c.get[Option[Long]]("registryArchivedAt")
    yield OrphanSuspension(orphan, paused, archivedAt)

  private given Decoder[Rounds] = (c: io.circe.HCursor) =>
    for
      no <- c.get[Option[Int]]("currentRoundNo")
      id <- c.get[Option[String]]("currentRoundId")
      open <- c.get[Option[List[Int]]]("openRoundNos").map(_.getOrElse(Nil))
    yield Rounds(no, id, open)

  private given Decoder[PendingDeliveries] = (c: io.circe.HCursor) =>
    for
      msgs <- c.get[Option[Int]]("nodeMessages").map(_.getOrElse(0))
      debt <- c.get[Option[Boolean]]("completionDebt").map(_.getOrElse(true))
      unnotified <- c.get[Option[List[String]]]("unnotifiedTerminals").map(_.getOrElse(Nil))
      undelivered <- c.get[Option[List[String]]]("undeliveredRootResults").map(_.getOrElse(Nil))
    yield PendingDeliveries(msgs, debt, unnotified, undelivered)

  private given Decoder[Facets] = semiauto.deriveDecoder

  private given Decoder[Summary] = (c: io.circe.HCursor) =>
    for
      schema <- c.get[Int]("schema")
      scope <- c.get[String]("scope")
      generatedAt <- c.get[Option[Long]]("generatedAt").map(_.getOrElse(0L))
      facets <- c.get[Option[Facets]]("facets")
      sources <- c.get[Option[List[SourceStamps]]]("sources").map(_.getOrElse(Nil))
      digest <- c.get[String]("digest")
    yield Summary(schema, scope, generatedAt, facets.getOrElse(emptyFacets), sources, digest)

  /** 解码兜底面（facets 缺档 ⇒ 全「脏」缺省——随后 digest 闸照拦，绝不静默 quiet）。 */
  private def emptyFacets: Facets =
    Facets(
      NonTerminalNodes(0, Nil, Nil),
      HumanHolds(0, Nil),
      OrphanSuspension(orphanCandidate = true, Nil, None),
      Rounds(None, None, Nil),
      PendingDeliveries(0, completionDebt = true, Nil, Nil)
    )

end RecoverySummary
