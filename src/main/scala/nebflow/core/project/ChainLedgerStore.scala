package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.NebflowLogger

import java.security.MessageDigest

/**
 * ChainLedgerStore —— **链号台账的持久面与拍点**（chainmodel 批二）。
 *
 * 判据/算法全部在 [[ChainLedger]]（纯函数，可单测）；本类只负责三件事：
 *  ① **落盘与载入**（增量写 + 周期 checkpoint，见 [[persist]]；启动期载入，与 `flow-map.json`
 *     同生命周期）；
 *  ② **冷档读写**（`chain-ledger-archive/round-<n>.json`，只归档不删除）；
 *  ③ **拍点编排**（[[reconcile]] = 出生/承继/合并/拆分 → 计数复算 → 退役 → 压缩，
 *     以及轴 a 的两个归档绑定写点 [[onChainsArchived]] / [[onChainsRestored]]）。
 *
 * == 崩溃安全（四条机械保证；perf-481 A3 把第 1 条从「整份原子写」换成「增量写」）==
 *  1. **增量写 + 折叠**：热账 = `chain-ledger.json`（checkpoint，tmp + `ATOMIC_MOVE` 原子写）
 *     ＋ `chain-ledger.json.journal`（每行一条 [[ChainLedger.JournalRecord]] 增量记录）。每次
 *     变更**先 append 再考虑折叠**，折叠 = 「checkpoint 整写 → journal 截断」（[[AtomicJson.rotateSync]]
 *     的次序）⇒「checkpoint + journal 合起来 = 最新态」恒成立，绝不出现「载荷已搬走、账未记」。
 *     残尾（半条 append）在回放时丢弃；记录是完整快照 + 轮号去重 ⇒ 重放幂等。**不折叠时 checkpoint
 *     逐字节不变**，读侧仍只见完整旧/新文件（[[AtomicJson.writeSync]] 的 tmp + ATOMIC_MOVE）。
 *  2. **先冷档后热账**：任何一轮都是「冷档文件写好 → 热账原子写」。崩在两步之间 ⇒ 热账仍是
 *     上轮状态，下一拍以**同轮号**重放（轮号 = `rounds.size + 1`，候选集确定 ⇒ 内容逐字
 *     相同 ⇒ 幂等覆写）。反向顺序会在崩溃后丢载荷，故禁。
 *  3. **失败即中止、账不动**：任一步 IO 失败 ⇒ 整个 [[reconcile]] 失败（调用方 WARN，
 *     下一拍重试），**绝不**留下「载荷已搬走、热账未记」的半状态。
 *
 * == 轮的坐标口径（拍内链式；先例 D1-a/D1-c）==
 * 每轮的 `hotBefore` **不由手写读数给出**，而由「`hotAfter` + 本轮搬走量」机械推出
 * （[[hotBeforeOf]]）—— 与 [[ChainLedger.roundConservation]] 的 `expectAfter` **逐项互逆**
 * （两处必须同改；单点化正是为了禁「一处按复算前坐标、另一处按复算后坐标」的错位）。
 * 坐标基准 = **计数复算之后**的热态 ⇒ 同一拍内 `hotAfter(k) == hotBefore(k+1)`
 * **结构性成立**（判据 = [[ChainLedger.verifyLedger]] ② 查，**只对同拍相邻轮**断言）。
 * 各轮带 [[ChainLedger.RoundManifest.tick]] 拍标识（同拍恒同值、异拍恒异值）。
 *
 * == 与 `flow-map.json` 的同生命周期 ==
 * 落点 = `<workspace>/.nebflow/chain-ledger.json`（+ 同名 `.journal` 增量面；同目录、同 open 期
 * 载入、同进程存活期）。两文件皆缺 ⇒ 空账（全新项目，无 WARN）；**存在但损坏** ⇒ 空账起步 +
 * WARN（判据：台账是**可重建**的派生面 + 别名表；重建只丢「历史改号别名」，不丢任何图事实
 * —— 故损坏不阻断启动，但绝不静默）。
 */
class ChainLedgerStore private (
  val project: String,
  private val path: os.Path,
  private val archiveDir: os.Path,
  private val state: Ref[IO, ChainLedger.State],
  private val logger: NebflowLogger,
  /** Cold-archive byte budget for the open-time self-check (tests shrink it; see selfCheck). */
  private[project] val coldReadBudgetBytes: Long = 32L * 1024 * 1024,
  /** perf-481 A3: how many `rounds` manifests the checkpoint already carries. */
  initialCommittedRounds: Int = 0,
  /** perf-481 A3: journal bytes already pending a fold back into the checkpoint. */
  initialPendingBytes: Long = 0L,
  /** perf-481 A3: fold threshold (tests shrink it to exercise rotation cheaply). */
  rotateBytes: Long = ChainLedger.JournalRotateBytes
):

  import ChainLedger.*

  /** 台账热态快照（只读面）。 */
  def snapshot: IO[State] = state.get

  /** 台账落盘路径（只读面；供巡检/测试）：`<workspace>/.nebflow/chain-ledger.json`。 */
  def ledgerPath: os.Path = path

  /** 冷档目录（只读面；供巡检/测试）：`<workspace>/.nebflow/chain-ledger-archive/`。 */
  def archivePath: os.Path = archiveDir

  private def encode(st: State): String = st.asJson.noSpaces

  /** 当前热态落盘字节数（**轴 c 的字节阈值现读面**；与 checkpoint 逐字节同源）。 */
  def hotBytes: IO[Long] = state.get.map(st => encode(st).getBytes("UTF-8").length.toLong)

  // ── perf-481 A3：增量写（append journal + 周期 checkpoint）────────────────
  //
  // `persist` 原为「每次变更整份原子写」= 13.4 MB/次。实测量得该体量的 99.99% 是
  // `rounds`（39 071 条 manifest），而 `rounds` 是 **append-only** ⇒ 与本次变更无关的
  // 已落盘段每次都被重写。现改为：一次**小追加**（记录 = 更新后的非轮字段 + 自上次折叠
  // 以来的新增 manifest，通常 0–3 条 ≈ 2 KB），journal 累积到 [[ChainLedger.JournalRotateBytes]]
  // 才折叠回 checkpoint（整写）并截断 journal。
  //
  // 崩溃安全（与 [[AtomicJson]] 头注同一份判据，禁弱化）：
  //  ① 先 append（durable 副本先落）再考虑 rotation；折叠顺序 = write checkpoint → truncate journal
  //     ⇒ 「checkpoint + journal 合起来 = 最新态」恒成立，绝不出现「载荷已搬走、账未记」。
  //  ② 任意崩溃点 ⇒ 最坏是重放一条已折叠的记录（记录是完整快照 + 轮号去重 ⇒ 幂等）。
  //  ③ 没有 rotation 时 checkpoint 逐字节不变（崩了也读得回旧完整态）。
  private val persistLock = new java.util.concurrent.Semaphore(1)

  /** checkpoint 已有的 manifest 条数（记录里 `rounds` 段的下界）。 */
  @volatile private var committedRounds: Int = initialCommittedRounds

  /** journal 里待折叠的字节数（rotation 判据；载入时由现读回填）。 */
  @volatile private var pendingBytes: Long = initialPendingBytes

  /** 本拍要落的**增量记录**（相对 checkpoint）：非轮字段全量 + 新增 manifest 段。 */
  private def recordOf(st: State): ChainLedger.JournalRecord =
    ChainLedger.JournalRecord(base = committedRounds, state = st.copy(rounds = st.rounds.drop(committedRounds)))

  /**
   * 增量落盘：append 一条记录，累积到阈值再折叠 + 截断。**整条临界区串行化**
   * （`committedRounds` / `pendingBytes` 的读改写 + 文件两步操作必须原子，否则两个
   * 并发 persist 会交错 append/rotate 并让水位错位）。
   *
   * 🔴 **首次写入必建 checkpoint**：`chain-ledger.json` 是权威面，必须在**任何一次变更后**
   * 都存在于盘上（既有契约：T10「台账必须在盘」）。journal 相对 checkpoint 存在 —— 没有
   * checkpoint 的纯 journal 是畸形态。故 checkpoint 缺失时这一次走整写（[[AtomicJson.rotateSync]]）、
   * 不 append；此后每次变更才是「append + 可能折叠」。
   */
  private def persist(st: State): IO[Unit] =
    IO.blocking {
      persistLock.acquire()
      try
        if !os.exists(path) then
          // 无 checkpoint（全新台账，或此前从未折叠）⇒ 本次建立权威面。
          AtomicJson.rotateSync(path, encode(st))
          committedRounds = st.rounds.size
          pendingBytes = 0L
        else
          val line = recordOf(st).asJson.noSpaces
          AtomicJson.appendSync(path, line)
          pendingBytes += AtomicJson.recordBytes(line)
          if pendingBytes >= rotateBytes then
            // 折叠：checkpoint 写在前、journal 截断在后（AtomicJson.rotateSync 的次序）。
            AtomicJson.rotateSync(path, encode(st))
            // 折叠成功后才推进水位（崩溃在上一行与这一行之间 ⇒ 只是重放，幂等）。
            committedRounds = st.rounds.size
            pendingBytes = 0L
      finally persistLock.release()
    }

  /** 现读 journal 待折叠字节（诊断/量具面；不改变状态）。 */
  private[project] def journalPendingBytes: IO[Long] = IO.blocking(AtomicJson.noteBytes(path))

  // ── 冷档 ─────────────────────────────────────────────

  /**
   * 冷档内容摘要（hex sha-256；口径与 `SeedService.sha256*` 逐字同款 —— 逐字节摘要，
   * 供「冷档未被改写」事后核对）。
   */
  private def digestOf(content: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(content.getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString

  /** 冷档全量读入（按轮号升序；单档损坏只跳该档并 WARN，不拖垮台账）。 */
  def readRounds: IO[List[RoundFile]] =
    IO.blocking {
      if !os.exists(archiveDir) then Nil
      else
        os.list(archiveDir).filter(_.last.endsWith(".json")).toList.sortBy(_.last).flatMap { f =>
          jsonParse(os.read(f)).flatMap(_.as[RoundFile]) match
            case Right(r) => Some(r)
            case Left(e) =>
              logger.warnSync(s"chain-ledger[$project] cold file corrupt: ${f.last}: $e — skipped")
              None
        }
    }

  /** 写一轮冷档（**只归档不删除**；文件内容含该轮阈值与前后读数 ⇒ 离线可复算）。 */
  private def writeRound(f: RoundFile): IO[String] =
    val content = f.asJson.noSpaces
    val target = archiveDir / s"round-${f.round}.json"
    AtomicJson.write(target, content).as(digestOf(content))

  private def roundFileOf(
    round: Int,
    kind: String,
    at: Long,
    hotBefore: Totals,
    hotAfter: Totals,
    entries: List[Entry],
    aliases: List[AliasRow]
  ): RoundFile =
    RoundFile(
      round = round,
      at = at,
      kind = kind,
      thresholds = ThresholdsNow,
      hotBefore = hotBefore,
      hotAfter = hotAfter,
      entries = entries,
      aliases = aliases
    )

  private def manifestOf(
    f: RoundFile,
    digest: String,
    tick: Long
  ): RoundManifest =
    RoundManifest(
      round = f.round,
      at = f.at,
      kind = f.kind,
      file = s"round-${f.round}.json",
      movedEntries = f.entries.size,
      movedAliases = f.aliases.size,
      movedMembers = f.entries.map(_.members.size).sum,
      hotBefore = f.hotBefore,
      hotAfter = f.hotAfter,
      digest = digest,
      tick = tick
    )

  /**
   * **轮坐标的逆式**：`hotBefore` = `hotAfter` + 本轮搬走的量（条目 / 别名 / 成员 / 引用
   * 四项）。与 [[ChainLedger.roundConservation]] 的 `expectAfter` **逐项互逆** —— 单点化
   * 使「一轮的读数」只有一处算法（改一处必改另一处）；先例 D1-a 的病根正是两处手写读数
   * 各按不同坐标基准（一条按复算前、一条按复算后）⇒ 恒假 `Left`。
   *
   * 口径与 [[ChainLedger.roundConservation]] 逐字对齐：[[RoundCompact]] 只搬**载荷**
   * （条目读数与成员读数留热，不减），其余轮（retire / dissolve）整行移出，四项同减。
   */
  private def hotBeforeOf(
    hotAfter: Totals,
    kind: String,
    rows: List[Entry],
    aliasRows: List[AliasRow]
  ): Totals =
    val movedRows = if kind == RoundCompact then 0 else rows.size
    val movedMembers = if kind == RoundCompact then 0 else rows.map(_.memberCount).sum
    val movedEntryRef = if kind == RoundCompact then 0L else rows.map(_.refCount.toLong).sum
    val movedAliasRef = aliasRows.map(_.refCount.toLong).sum
    val movedRef = movedEntryRef + movedAliasRef
    hotAfter.copy(
      entries = hotAfter.entries + movedRows,
      aliases = hotAfter.aliases + aliasRows.size,
      members = hotAfter.members + movedMembers,
      refCount = hotAfter.refCount + movedRef
    )
  end hotBeforeOf

  // ── 轴(a)：生命周期绑定（挂既有链归档写点）──────────────────────────────

  /**
   * **轴(a) 绑定命中集**（判据：「哪条台账条目属于本次出库 / 拉回的链」；三条任一命中）：
   *  ① 链号**直接**命中条目；② 链号经**别名解析**命中（派生原型号 ≠ 稳定链号的历史面
   *     ——改号吸收后原型号仍可能在用）；③ **成员面**命中（原型号已变但成员仍在：条目
   *     载荷或锚点与该链本次出库的成员相交；压缩行以锚点代载荷 ⇒ 走 `compacted` 分支）。
   *
   * 三条都落在既有数据面（零新写点）且**都不依赖 sweep 与 reconcile 的先后** ⇒
   * 「归档同刻绑定」对任何出生/改号路径成立（禁把绑定做成「序号恰好相等」的巧合）。
   * 别名逐跳解析自带环守卫（[[ChainLedger.resolve]]）。
   */
  private def bindingTargets(st: State, chainIds: Set[String], memberIds: Set[String]): Set[String] =
    val byId = chainIds.flatMap(id => ChainLedger.resolve(st, id))
    st.entries.values
      .filter { e =>
        byId.contains(e.chainId) ||
        (if e.compacted || e.members.isEmpty then memberIds.contains(e.anchor)
         else e.members.exists(memberIds.contains))
      }
      .map(_.chainId)
      .toSet

  /**
   * **轴(a) 归档绑定**（写点 = `FlowMapStore.sweepCompletedChainsDetailed` 的**同一次
   * 调用**，即链出库的同一刻）：命中条目翻 `Archived` + 落 `archivedAt`（= 最早退役
   * 窗口；**已归档者保留其更早的 `archivedAt`** —— 重绑定不得改写归档刻）。此后
   * [[ChainLedger.planRetire]] 才会考虑这些行 ⇒「行不得早于所属链归档退役」是结构性的，
   * 不靠纪律。无命中 ⇒ 零写（不落空账文件）。
   *
   * @param chainIds  本次出库链的**派生原型链号**（`ChainInfo.id`）
   * @param memberIds 本次出库链的**成员 id**（`FlowMapStore` 现读，调用方零二次派生）
   */
  def onChainsArchived(chainIds: Set[String], memberIds: Set[String], now: Long): IO[Unit] =
    if chainIds.isEmpty && memberIds.isEmpty then IO.unit
    else
      state.get.flatMap { before =>
        val hits = bindingTargets(before, chainIds, memberIds)
        val entries = before.entries.map { case (k, e) =>
          if hits.contains(k) then k -> e.copy(status = StatusArchived, archivedAt = e.archivedAt.orElse(Some(now)))
          else k -> e
        }
        if entries == before.entries then IO.unit
        else
          val updated = before.copy(updatedAt = now, entries = entries)
          persist(updated) *> state.set(updated)
      }

  /**
   * **轴(a) 反向**（写点 = `FlowMapStore.restoreChainsFromArchiveDetailed`）：链拉回活动
   * 区 ⇒ 条目翻回 `Active`（归档绑定是可逆的绑定，不是单程删除）。命中判据与
   * [[onChainsArchived]] 同源（同一 [[bindingTargets]]）。已在活动态 ⇒ 零写。
   */
  def onChainsRestored(chainIds: Set[String], memberIds: Set[String], now: Long): IO[Unit] =
    if chainIds.isEmpty && memberIds.isEmpty then IO.unit
    else
      state.get.flatMap { before =>
        val hits = bindingTargets(before, chainIds, memberIds)
        val entries = before.entries.map { case (k, e) =>
          if hits.contains(k) then k -> e.copy(status = StatusActive, archivedAt = None)
          else k -> e
        }
        if entries == before.entries then IO.unit
        else
          val updated = before.copy(updatedAt = now, entries = entries)
          persist(updated) *> state.set(updated)
      }

  // ── 链控三原语的状态面（chainview 批 2026-10-01）────────────────────────

  /**
   * **链控状态读取单点**（`paused` / `cancelled` / `active`）：判据全在
   * [[ChainLedger.statusOf]]（别名逐跳解析 ⇒ 改号后旧号照样报对态）。
   */
  def chainControlOf(chainId: String): IO[ChainLedger.ChainControl] =
    state.get.map { st =>
      ChainLedger.ChainControl(
        chainId = chainId,
        status = ChainLedger.statusOf(st, chainId),
        pausedAt = ChainLedger.pausedAtOf(st, chainId),
        cancelledAt = ChainLedger.cancelledAtOf(st, chainId)
      )
    }

  /**
   * **链控写点**（`paused` / `active` / `cancelled`）：写点形态逐字同 [[onChainsArchived]]
   * 的既有模板（读 → 改 → **无变化 ⇒ 零写**，否则 `persist *> state.set`）——「重复暂停同
   * 一条链」「对活链 resume」都落在零写分支（禁落空账、禁无谓写盘）。
   *
   * 状态语义见 [[ChainLedger.State.pausedChains]] / [[ChainLedger.State.cancelledChains]]；
   * 本方法只负责持久化纯函数 [[ChainLedger.withChainControl]] 的结果。
   */
  def setChainControl(chainId: String, status: String, now: Long): IO[ChainLedger.ChainControl] =
    state.get.flatMap { before =>
      val updated = ChainLedger.withChainControl(before, chainId, status, now)
      val changed = updated.pausedChains != before.pausedChains ||
        updated.cancelledChains != before.cancelledChains ||
        // eng-deferred-cancel batch (2026-10-02): a cancel that lands within
        // the intent window also clears the in-progress marker — that diff
        // alone must reach disk (otherwise intent and terminal state silently
        // disagree on disk while agreeing in memory).
        updated.cancellingChains != before.cancellingChains
      val io = if changed then persist(updated) *> state.set(updated) else IO.unit
      io.as(
        ChainLedger.ChainControl(
          chainId = chainId,
          status = ChainLedger.statusOf(updated, chainId),
          pausedAt = ChainLedger.pausedAtOf(updated, chainId),
          cancelledAt = ChainLedger.cancelledAtOf(updated, chainId)
        )
      )
    }

  /**
   * **Cancel-intent registration write point** (eng-deferred-cancel batch): the
   * same "read → change → no change ⇒ zero write, otherwise `persist *>
   * state.set`" template as [[setChainControl]].
   *
   * 🔴 **Division of labour with [[chainControlOf]]**: this method ONLY registers
   * the in-progress intent; whether the chain is cancelled/paused is still
   * answered exclusively by the three-state projection in [[chainControlOf]]
   * (no second judgement face). The caller does not consume the success value
   * either — this is a trace, not a state.
   */
  def setChainCancelIntent(chainId: String, now: Long): IO[Unit] =
    state.get.flatMap { before =>
      val updated = ChainLedger.withChainCancelIntent(before, chainId, now)
      val changed = updated.cancellingChains != before.cancellingChains
      if changed then persist(updated) *> state.set(updated) else IO.unit
    }

  // ── 轴(b)：外部引用面计数（外部三面的落点）──────────────────────────────

  /**
   * 无籍计数的单一诊断构造点（[[noteReference]] / [[noteTextReferences]] 共用 ⇒ 两张
   * 入口的错误文案恒同，禁各写一份）。
   */
  private def unknownFaceError(faceId: String): String =
    s"unknown reference face '$faceId' — 引用面必须先登记在 ChainLedger.ReferenceFaces" +
      s"（在册：${ReferenceFaces.map(_.id).mkString(", ")}），禁无籍计数"

  /**
   * 外部引用面计账（面必须先经 [[ChainLedger.ReferenceFaces]] 登记 —— **禁无籍计数**；
   * 面 id 未登记 ⇒ `Left` 可行动错误）。轴(b) 四面由 [[reconcile]] 覆盖式复算，无需调用
   * 本方法；外部三面（`mail-usage` / `board-usage` / `report-usage`）在其归属批次里接本
   * API：**逐次引用**形态（每次引用发生各 +1，如 Mail 投递成功）走 [[noteReference]]，
   * **正文**形态（一段正文里逐字出现的已登记链号）走 [[noteTextReferences]]。
   * 两者都只写 [[State.externalRefs]]（只计不减），下一拍 [[reconcile]] 按账并入复算。
   */
  def noteReference(id: String, faceId: String, delta: Int): IO[Either[String, Unit]] =
    if !ReferenceFaces.exists(_.id == faceId) then IO.pure(Left(unknownFaceError(faceId)))
    else if id.trim.isEmpty then IO.pure(Left("reference id must be non-empty"))
    else
      state.get.flatMap { before =>
        val next = math.max(0, before.externalRefs.getOrElse(id, 0) + delta)
        val updated = before.copy(externalRefs = before.externalRefs.updated(id, next))
        persist(updated) *> state.set(updated).as(Right(()): Either[String, Unit])
      }

  /**
   * **正文引用面计账（批三+ 接线：`board-usage` / `report-usage`）**：把一段**正文**里逐字
   * 出现的**已登记**链号（判据单点 = [[ChainLedger.referencedIds]] × [[ChainLedger.knownIds]]
   * 的热面）**一次性**计入 `faceId`。
   *
   * 三条机械口径（全部可机械核对，零人工判断）：
   *  - 面未登记 ⇒ `Left`（与 [[noteReference]] 同一张闸，单点见 [[unknownFaceError]]）；
   *  - **零命中 ⇒ 零写**（不落空账、不新建 `externalRefs` 键 —— 防「扫一次就多一行」）；
   *  - 多命中 ⇒ **一次原子写**（不逐号落盘 ⇒ N 个引用不放大成 N 次 IO）。
   *
   * 🔴 **禁回填**：只对**已在热台账里**的号计数 —— 未登记号（悬空号 / 冷档已退役历史行）在正文
   * 里出现也不建条目、不建别名、不建计数键（[[ChainLedger.knownIds]] 是全部输入面）。
   * 🔴 **只计不减**：命中即 +1（正文是历史事实），与 `decWhen` 登记逐字一致。
   *
   * @return 命中的链号（升序；供调用方断言/留痕），或 `Left`（面未登记）
   */
  def noteTextReferences(text: String, faceId: String): IO[Either[String, List[String]]] =
    if !ReferenceFaces.exists(_.id == faceId) then IO.pure(Left(unknownFaceError(faceId)))
    else
      state.get.flatMap { before =>
        val hits = ChainLedger.referencedIds(text, ChainLedger.knownIds(before))
        if hits.isEmpty then IO.pure(Right(Nil): Either[String, List[String]])
        else
          val refs = hits.foldLeft(before.externalRefs) { (acc, id) =>
            acc.updated(id, math.max(0, acc.getOrElse(id, 0) + 1))
          }
          val updated = before.copy(externalRefs = refs)
          persist(updated) *> state.set(updated).as(Right(hits): Either[String, List[String]])
      }

  // ── 解析（旧号永久可达：热面 + 冷档两级）────────────────────────────────

  /** 链号解析（热面）：现链号 → 自身；别名 → 现链号。 */
  def resolve(id: String): IO[Option[String]] = state.get.map(st => ChainLedger.resolve(st, id))

  /** 链号解析（热面未命中 ⇒ 逐冷档回翻）：已下沉的条目/别名行**照旧可达**。 */
  def resolveDeep(id: String): IO[Option[String]] =
    state.get.flatMap { st =>
      ChainLedger.resolve(st, id) match
        case some @ Some(_) => IO.pure(some)
        case None =>
          // Same result as reading every round up front (newest round wins), but
          // files are parsed one at a time so memory stays bounded by a single
          // round file regardless of archive size.
          IO.blocking {
            if !os.exists(archiveDir) then Nil
            else os.list(archiveDir).filter(_.last.endsWith(".json")).toList
          }.flatMap { files =>
            files.foldLeft(IO.pure(List.empty[(Int, Option[String])])) { (acc, f) =>
              acc.flatMap { out =>
                IO.blocking {
                  jsonParse(os.read(f)).flatMap(_.as[RoundFile]) match
                    case Right(r) =>
                      val hit =
                        r.entries.find(_.chainId == id).map(_.chainId)
                          .orElse(r.aliases.find(_.alias == id).map(_.canonical))
                      (r.round, hit) :: out
                    case Left(e) =>
                      logger.warnSync(s"chain-ledger[$project] cold file corrupt: ${f.last}: $e — skipped")
                      out
                }
              }
            }.map { out =>
              out.sortBy(_._1).reverse.collectFirst { case (_, Some(canonical)) => canonical }
            }
          }
    }

  // ── 拍点：reconcile（出生/承继/合并/拆分 → 计数 → 退役 → 压缩）──────────

  def reconcile(
    chains: List[ChainInfo],
    activeIds: Set[String],
    declarations: Map[String, Set[String]],
    batchIds: Set[String],
    now: Long
  ): IO[Observation] =
    state.get.flatMap { st0 =>
      val obs0 = ChainLedger.observe(st0, chains, now)
      // 出生即归档面（轴 a）：本拍**新生**条目若其成员**全在活动区之外**（链已出库 ——
      // 存量归档数据首次 reconcile / 同一 30s 内出生又整链出库的短链），出生即刻翻
      // `Archived` + 记 `archivedAt`（归档刻 = 最早退役窗口）⇒「行不得早于所属链归档退役」
      // 对**任何**出生路径成立，不依赖 sweep 与 reconcile 的先后（禁留永不退役的活跃残行）。
      val bornGone = obs0.born.filter(cid =>
        obs0.state.entries.get(cid).exists(e => e.members.nonEmpty && !e.members.exists(activeIds.contains))
      )
      val stObs =
        if bornGone.isEmpty then obs0.state
        else
          obs0.state.copy(entries = obs0.state.entries.map { case (k, e) =>
            if bornGone.contains(k) then k -> e.copy(status = StatusArchived, archivedAt = Some(now))
            else k -> e
          })
      val obs = obs0.copy(state = stObs)
      // 面求值：活动区成员按**本拍裁决表**（分量 → 稳定链号）归集 —— 口径取自
      // `obs.assigned` + 分量成员，**不读行内载荷**（载荷可能已压缩下沉到冷档；
      // 读数面因此与 compacted 无关，禁「压缩后引用计数莫名归零」）。
      val activeMembers: Map[String, Set[String]] =
        chains.foldLeft(Map.empty[String, Set[String]]) { (acc, c) =>
          obs.assigned.get(c.id) match
            case Some(cid) =>
              val live = c.memberIds.filter(activeIds.contains).toSet
              if live.isEmpty then acc
              else acc.updated(cid, acc.getOrElse(cid, Set.empty) ++ live)
            case None => acc
        }
      val stCounted = ChainLedger.recomputeRefCounts(
        stObs,
        ChainLedger.FaceCounts(activeMembers = activeMembers, declarations = declarations, batchIds = batchIds)
      )
      val retire = ChainLedger.planRetire(stCounted, now)
      val stRetired = retire.state
      // ── 拍标识（[[ChainLedger.RoundManifest.tick]]）：本拍**首轮**的轮号 —— 由
      //    append-only 轮号派生 ⇒ 同拍恒同值、异拍恒异值（轮间链式只对同拍相邻轮断言）──
      val tick = st0.rounds.size + 1
      // ── 轮 1（dissolve：链已离场 ⇒ 整行下沉）──
      // 坐标口径（先例 D1-a）：`hotAfter` 取**计数复算之后**的热态（= 轮 2 的 `hotBefore`，
      // 逐项恒等 ⇒ 拍内链式结构性成立）；`hotBefore` 由「`hotAfter` + 本轮搬走量」机械推出。
      // 🔴 禁改回 `stObs`（复算之前）：那会让轮 1 与轮 2 站在两套坐标基准上 ⇒ 恒假 `Left`。
      val dissolveRows = obs.dissolved
      val orphanAliases = obs.orphanAliases
      val hasDissolve = dissolveRows.nonEmpty || orphanAliases.nonEmpty
      val roundDissolve = tick
      val hotAfterDissolve = ChainLedger.totals(stCounted)
      val fileDissolve = roundFileOf(
        roundDissolve,
        RoundDissolve,
        now,
        hotBeforeOf(hotAfterDissolve, RoundDissolve, dissolveRows, orphanAliases),
        hotAfterDissolve,
        dissolveRows,
        orphanAliases
      )
      for
        // ── 轮 1 落盘（冷档在前、热账在后；见类头注第 2 条）──
        digestD <- if hasDissolve then writeRound(fileDissolve) else IO.pure("")
        stAfterDissolve =
          if hasDissolve then stObs.copy(rounds = st0.rounds :+ manifestOf(fileDissolve, digestD, tick)) else stObs
        // ── 轮 2（retire：轴 a×b 联合判据；热行移除、冷档留全行）──
        hasRetire = retire.entries.nonEmpty || retire.aliases.nonEmpty
        roundRetire = stAfterDissolve.rounds.size + 1
        hotAfterRetire = ChainLedger.totals(stRetired)
        fileRetire = roundFileOf(
          roundRetire,
          RoundRetire,
          now,
          hotBeforeOf(hotAfterRetire, RoundRetire, retire.entries, retire.aliases),
          hotAfterRetire,
          retire.entries,
          retire.aliases
        )
        digestR <- if hasRetire then writeRound(fileRetire) else IO.pure("")
        stAfterRetire =
          if hasRetire then stRetired.copy(rounds = stAfterDissolve.rounds :+ manifestOf(fileRetire, digestR, tick))
          else stRetired.copy(rounds = stAfterDissolve.rounds)
        // ── 轮 3（compact：轴 c 双阈值 —— 条数 ∨ 字节）──
        bytes <- IO.blocking(encode(stAfterRetire).getBytes("UTF-8").length.toLong)
        capExceeded = ChainLedger.needsCompaction(stAfterRetire, bytes)
        plan =
          if capExceeded then ChainLedger.planCompaction(stAfterRetire, bytes)
          else ChainLedger.CompactPlan(stAfterRetire)
        hasCompact = capExceeded && !plan.isEmpty
        roundCompact = stAfterRetire.rounds.size + 1
        // 坐标口径同源：`plan.hotBefore = totals(stAfterRetire)`（= 轮 2 的 `hotAfter`，
        // `rounds` 字段不进读数）⇒ 拍内链式对轮 3 同样成立；`plan.hotAfter` 与
        // [[hotBeforeOf]] 互为逆式（compaction 只搬载荷 + 整行移出别名）。
        fileCompact = roundFileOf(
          roundCompact,
          RoundCompact,
          now,
          plan.hotBefore,
          plan.hotAfter,
          plan.compactedEntries,
          plan.retiredAliases
        )
        digestC <- if hasCompact then writeRound(fileCompact) else IO.pure("")
        compactManifest = if hasCompact then Some(manifestOf(fileCompact, digestC, tick)) else None
        stFinal =
          val base = if hasCompact then plan.state else stAfterRetire
          base.copy(project = project, rounds = stAfterRetire.rounds ++ compactManifest.toList, updatedAt = now)
        _ <- persist(stFinal)
        _ <- state.set(stFinal)
        _ <-
          if hasDissolve || hasRetire || hasCompact then
            IO(
              logger.infoSync(
                s"chain-ledger[$project] rounds: dissolve=${if hasDissolve then 1 else 0}" +
                  s" retire=${retire.entries.size}E/${retire.aliases.size}A" +
                  s" compact=${if hasCompact then 1 else 0}" +
                  s" hotBefore=${ChainLedger.hotRows(stAfterRetire)} rows/${bytes}B" +
                  s" hotAfter=${ChainLedger.hotRows(stFinal)} rows" +
                  s" caps=${ThresholdsNow.maxHotRows}rows/${ThresholdsNow.maxHotBytes}B"
              )
            )
          else if capExceeded then
            // 超阈值但**无行可搬**（全库载荷已下沉）⇒ 只记信号，禁每拍空转成风暴
            IO(
              logger.warnSync(
                s"chain-ledger[$project] over cap (${ChainLedger.hotRows(stAfterRetire)} rows / ${bytes}B)" +
                  s" but nothing movable (all payloads already archived) — signal only, no round"
              )
            )
          else IO.unit
      yield obs.copy(
        state = stFinal,
        retired = retire.entries.size + retire.aliases.size,
        capExceeded = capExceeded,
        compaction = compactManifest
      )
      end for
    }

  // ── 自检（启动期 / verify 位）────────────────────────

  /**
   * Cold-archive byte size (metadata only — file names and sizes, zero content reads).
   */
  private def coldArchiveBytes: Long =
    if !os.exists(archiveDir) then 0L
    else
      os.list(archiveDir)
        .filter(_.last.endsWith(".json"))
        .foldLeft(0L)((acc, f) => acc + os.size(f))

  /**
   * Mount-time self-check gate. The cold archive is append-only, so its size is
   * unbounded; reading it in full at every store open makes startup memory
   * unbounded (observed: ~600MB of archive JSON across the mounted projects
   * parsed into millions of ledger entries, OOM-killing the JVM during
   * startupMount). Over-budget archives skip the cold-file audit at open time —
   * the hot state is still loaded and [[verify]] remains the explicit
   * full-audit entrypoint.
   */
  private def selfCheck(tag: String): IO[Unit] =
    IO.blocking(coldArchiveBytes).flatMap { coldBytes =>
      if coldBytes > coldReadBudgetBytes then
        IO(
          logger.infoSync(
            s"chain-ledger[$project] self-check ($tag): cold archive $coldBytes bytes exceeds the " +
              s"$coldReadBudgetBytes-byte open-time read budget — skipping the cold-file audit " +
              s"(hot state loaded; run verify for the full conservation audit)"
          )
        )
      else
        verify.flatMap {
          case Right(()) => IO.unit
          case Left(diag) =>
            IO(
              logger.warnSync(
                s"chain-ledger[$project] self-check FAILED ($tag): $diag — 台账不自洽（只报不阻；" +
                  s"排查面：${path} + ${archiveDir}）"
              )
            )
        }
    }

  /** 台账全量自检：结构 + 每轮守恒 + 轮间链式 + 压缩镜像（判据全在 [[ChainLedger]]）。 */
  def verify: IO[Either[String, Unit]] =
    for
      st <- state.get
      files <- readRounds
    yield ChainLedger.verifyLedger(st, files, payloadIdsOf(files))

  /**
   * 压缩镜像面（**取每个 id 的最晚一轮口径**）：某 id 的载荷此刻是否应在冷档 ——
   * 最晚提到它的那一轮是 compact ⇒ 热面应有 `compacted=true` 的条目（镜像成对）；
   * 最晚一轮是 retire/dissolve ⇒ 热面不应有该条目。
   */
  private def payloadIdsOf(files: List[RoundFile]): Set[String] =
    files
      .sortBy(_.round)
      .foldLeft(Map.empty[String, String]) { (acc, f) =>
        acc ++ f.entries.map(_.chainId).map(_ -> f.kind)
      }
      .filter(_._2 == RoundCompact)
      .keySet

end ChainLedgerStore

object ChainLedgerStore:

  private val logger: NebflowLogger = NebflowLogger.forName("nebflow.chain-ledger")

  /**
   * **启动期载入**（与 `flow-map.json` 同生命周期）。两文件形态（perf-481 A3）：
   * checkpoint（`chain-ledger.json`）+ journal（`chain-ledger.json.journal`，每行一条
   * [[ChainLedger.JournalRecord]] 增量记录）。
   *
   * 判据（与旧写法的载入结果**逐字一致**是硬要求）：
   *  ① journal 里**最后一条完整记录**（残尾丢弃、解析失败即停 —— 见 `AtomicJson.readAll`）
   *     是更新的面；`checkpoint.rounds ++ 该记录的新增段`（[[ChainLedger.mergeRounds]] 按轮号
   *     去重）还原全量轮清单 ⇒ 载入态与「整份写」时代逐字节同值。
   *  ② checkpoint 缺失而 journal 有记录 ⇒ 以「空 checkpoint + 该记录」起步（首次折叠前
   *     崩溃的正常形态，不算损坏）。
   *  ③ journal 无记录 ⇒ 纯 checkpoint（旧格式文件、或刚折叠过）。
   *  ④ **两文件皆不可用**（都不存在 ⇒ 全新项目，静默空账）／**存在但损坏** ⇒ 空账 + WARN
   *     （台账是可重建派生面，不阻启动，但绝不静默 —— 与旧行为同款）。
   */
  def open(
    project: String,
    ledgerPath: os.Path,
    archivePath: os.Path,
    coldReadBudgetBytes: Long = 32L * 1024 * 1024,
    rotateBytes: Long = ChainLedger.JournalRotateBytes
  ): IO[ChainLedgerStore] =
    for
      read <- IO.blocking(AtomicJson.readAll(ledgerPath))
      loaded <- IO.blocking(loadState(project, ledgerPath, read))
      store = new ChainLedgerStore(
        project,
        ledgerPath,
        archivePath,
        Ref.unsafe[IO, ChainLedger.State](loaded._1),
        logger,
        coldReadBudgetBytes,
        initialCommittedRounds = loaded._2,
        initialPendingBytes = AtomicJson.noteBytes(ledgerPath),
        rotateBytes = rotateBytes
      )
      _ <- store.selfCheck("open")
    yield store
  end open

  /**
   * 折叠两文件为一个状态（纯读；WARN 由本方法按判据打）。返回 `(状态, checkpoint 已有轮数)`。
   *
   * 🔴 **损坏的 checkpoint 一律空账起步，且不采信 journal**（[[open]] 判据④）：checkpoint 是
   * 「最后一个完整态」的权威面，它本身不可用时的正确处置是走既有的「可重建 ⇒ 空账 + WARN」
   * 策略（T10 断言），而不是拿 journal 片段拼一个半信状态。**文件缺失**（`checkpointError`
   * 为 `None` 而 `checkpoint` 为空）是另一回事 —— 那是「首次折叠前崩溃」的正常形态，
   * 由 journal 恢复。
   */
  private def loadState(
    project: String,
    ledgerPath: os.Path,
    read: AtomicJson.JournalRead
  ): (ChainLedger.State, Int) =
    read.checkpointError match
      case Some(e) =>
        logger.warnSync(
          s"chain-ledger[$project] ${e} — starting empty " +
            "(台账可重建；历史别名丢失，图事实零影响)"
        )
        (ChainLedger.State(project = project), 0)
      case None =>
        val committed: Option[ChainLedger.State] =
          read.checkpoint.flatMap(t => jsonParse(t).flatMap(_.as[ChainLedger.State]).toOption)
        val parsedNotes: List[ChainLedger.JournalRecord] =
          read.records.flatMap(n => jsonParse(n.note).flatMap(_.as[ChainLedger.JournalRecord]).toOption)
        val committedRounds = committed.map(_.rounds.size).getOrElse(0)
        val base = committed.getOrElse(ChainLedger.State(project = project))
        parsedNotes.lastOption match
          case Some(rec) =>
            val state = rec.state.copy(
              project = project,
              rounds = ChainLedger.mergeRounds(base.rounds, rec.state.rounds)
            )
            (state, committedRounds)
          case None => (base.copy(project = project), committedRounds)
  end loadState
end ChainLedgerStore
