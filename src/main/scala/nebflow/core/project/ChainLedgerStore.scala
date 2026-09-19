package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger}
import java.security.MessageDigest

/**
 * ChainLedgerStore —— **链号台账的持久面与拍点**（chainmodel 批二）。
 *
 * 判据/算法全部在 [[ChainLedger]]（纯函数，可单测）；本类只负责三件事：
 *  ① **落盘与载入**（原子写 [[AtomicJson]]、启动期载入、与 `flow-map.json` 同生命周期）；
 *  ② **冷档读写**（`chain-ledger-archive/round-<n>.json`，只归档不删除）；
 *  ③ **拍点编排**（[[reconcile]] = 出生/承继/合并/拆分 → 计数复算 → 退役 → 压缩，
 *     以及轴 a 的两个归档绑定写点 [[onChainsArchived]] / [[onChainsRestored]]）。
 *
 * == 崩溃安全（三条机械保证）==
 *  1. **原子写**：热账经 `AtomicJson`（tmp + `ATOMIC_MOVE`）⇒ 读侧只见完整旧文件或完整新
 *     文件，绝无半损状态（先例：`AtomicJson` 头注、issue #23）。
 *  2. **先冷档后热账**：任何一轮都是「冷档文件写好 → 热账原子写」。崩在两步之间 ⇒ 热账仍是
 *     上轮状态，下一拍以**同轮号**重放（轮号 = `rounds.size + 1`，候选集确定 ⇒ 内容逐字
 *     相同 ⇒ 幂等覆写）。反向顺序会在崩溃后丢载荷，故禁。
 *  3. **失败即中止、账不动**：任一步 IO 失败 ⇒ 整个 [[reconcile]] 失败（调用方 WARN，
 *     下一拍重试），**绝不**留下「载荷已搬走、热账未记」的半状态。
 *
 * == 与 `flow-map.json` 的同生命周期 ==
 * 落点 = `<workspace>/.nebflow/chain-ledger.json`（同目录、同 open 期载入、同进程存活期）。
 * 文件缺失/损坏 ⇒ 空账起步 + WARN（判据：台账是**可重建**的派生面 + 别名表；重建只丢
 * 「历史改号别名」，不丢任何图事实 —— 故损坏不阻断启动，但绝不静默）。
 */
class ChainLedgerStore private (
    val project: String,
    private val path: os.Path,
    private val archiveDir: os.Path,
    private val state: Ref[IO, ChainLedger.State],
    private val logger: NebflowLogger
):

  import ChainLedger.*

  /** 台账热态快照（只读面）。 */
  def snapshot: IO[State] = state.get

  /** 台账落盘路径（只读面；供巡检/测试）：`<workspace>/.nebflow/chain-ledger.json`。 */
  def ledgerPath: os.Path = path

  /** 冷档目录（只读面；供巡检/测试）：`<workspace>/.nebflow/chain-ledger-archive/`。 */
  def archivePath: os.Path = archiveDir

  private def encode(st: State): String = st.asJson.noSpaces

  /** 当前热态落盘字节数（**轴 c 的字节阈值现读面**；与 [[persist]] 逐字节同源）。 */
  def hotBytes: IO[Long] = state.get.map(st => encode(st).getBytes("UTF-8").length.toLong)

  private def persist(st: State): IO[Unit] = AtomicJson.write(path, encode(st))

  // ── 冷档 ─────────────────────────────────────────────

  /** 冷档内容摘要（hex sha-256；口径与 `SeedService.sha256*` 逐字同款 —— 逐字节摘要，
    * 供「冷档未被改写」事后核对）。 */
  private def digestOf(content: String): String =
    MessageDigest.getInstance("SHA-256").digest(content.getBytes("UTF-8"))
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
    RoundFile(round = round, at = at, kind = kind, thresholds = ThresholdsNow,
      hotBefore = hotBefore, hotAfter = hotAfter, entries = entries, aliases = aliases)

  private def manifestOf(
      f: RoundFile,
      digest: String
  ): RoundManifest =
    RoundManifest(
      round = f.round, at = f.at, kind = f.kind, file = s"round-${f.round}.json",
      movedEntries = f.entries.size, movedAliases = f.aliases.size,
      movedMembers = f.entries.map(_.members.size).sum,
      hotBefore = f.hotBefore, hotAfter = f.hotAfter, digest = digest)

  // ── 轴(a)：生命周期绑定（挂既有链归档写点）──────────────────────────────

  /** **轴(a) 绑定命中集**（判据：「哪条台账条目属于本次出库 / 拉回的链」；三条任一命中）：
    *  ① 链号**直接**命中条目；② 链号经**别名解析**命中（派生原型号 ≠ 稳定链号的历史面
    *     ——改号吸收后原型号仍可能在用）；③ **成员面**命中（原型号已变但成员仍在：条目
    *     载荷或锚点与该链本次出库的成员相交；压缩行以锚点代载荷 ⇒ 走 `compacted` 分支）。
    *
    * 三条都落在既有数据面（零新写点）且**都不依赖 sweep 与 reconcile 的先后** ⇒
    * 「归档同刻绑定」对任何出生/改号路径成立（禁把绑定做成「序号恰好相等」的巧合）。
    * 别名逐跳解析自带环守卫（[[ChainLedger.resolve]]）。 */
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

  /** **轴(a) 归档绑定**（写点 = `FlowMapStore.sweepCompletedChainsDetailed` 的**同一次
    * 调用**，即链出库的同一刻）：命中条目翻 `Archived` + 落 `archivedAt`（= 最早退役
    * 窗口；**已归档者保留其更早的 `archivedAt`** —— 重绑定不得改写归档刻）。此后
    * [[ChainLedger.planRetire]] 才会考虑这些行 ⇒「行不得早于所属链归档退役」是结构性的，
    * 不靠纪律。无命中 ⇒ 零写（不落空账文件）。
    *
    * @param chainIds  本次出库链的**派生原型链号**（`ChainInfo.id`）
    * @param memberIds 本次出库链的**成员 id**（`FlowMapStore` 现读，调用方零二次派生） */
  def onChainsArchived(chainIds: Set[String], memberIds: Set[String], now: Long): IO[Unit] =
    if chainIds.isEmpty && memberIds.isEmpty then IO.unit
    else
      state.get.flatMap { before =>
        val hits = bindingTargets(before, chainIds, memberIds)
        val entries = before.entries.map { case (k, e) =>
          if hits.contains(k) then
            k -> e.copy(status = StatusArchived, archivedAt = e.archivedAt.orElse(Some(now)))
          else k -> e
        }
        if entries == before.entries then IO.unit
        else
          val updated = before.copy(updatedAt = now, entries = entries)
          persist(updated) *> state.set(updated)
      }

  /** **轴(a) 反向**（写点 = `FlowMapStore.restoreChainsFromArchiveDetailed`）：链拉回活动
    * 区 ⇒ 条目翻回 `Active`（归档绑定是可逆的绑定，不是单程删除）。命中判据与
    * [[onChainsArchived]] 同源（同一 [[bindingTargets]]）。已在活动态 ⇒ 零写。 */
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

  // ── 轴(b)：外部引用面计数（未接线三面的落点）────────────────────────────

  /** 外部引用面计账（面必须先经 [[ChainLedger.ReferenceFaces]] 登记 —— **禁无籍计数**；
    * 面 id 未登记 ⇒ `Left` 可行动错误）。本批已接线的面由 [[reconcile]] 覆盖式复算，
    * 无需调用本方法；未接线三面（mail/board/report）在其归属批次里接本 API。 */
  def noteReference(id: String, faceId: String, delta: Int): IO[Either[String, Unit]] =
    if !ReferenceFaces.exists(_.id == faceId) then
      IO.pure(Left(
        s"unknown reference face '$faceId' — 引用面必须先登记在 ChainLedger.ReferenceFaces" +
          s"（在册：${ReferenceFaces.map(_.id).mkString(", ")}），禁无籍计数"))
    else if id.trim.isEmpty then IO.pure(Left("reference id must be non-empty"))
    else
      state.get.flatMap { before =>
        val next = math.max(0, before.externalRefs.getOrElse(id, 0) + delta)
        val updated = before.copy(externalRefs = before.externalRefs.updated(id, next))
        persist(updated) *> state.set(updated).as(Right(()): Either[String, Unit])
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
          readRounds.map { files =>
            files.sortBy(_.round).reverse.foldLeft(Option.empty[String]) { (acc, f) =>
              acc
                .orElse(f.entries.find(_.chainId == id).map(_.chainId))
                .orElse(f.aliases.find(_.alias == id).map(_.canonical))
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
        obs0.state.entries.get(cid).exists(e => e.members.nonEmpty && !e.members.exists(activeIds.contains)))
      val stObs =
        if bornGone.isEmpty then obs0.state
        else
          obs0.state.copy(entries = obs0.state.entries.map { case (k, e) =>
            if bornGone.contains(k) then
              k -> e.copy(status = StatusArchived, archivedAt = Some(now))
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
      val stCounted = ChainLedger.recomputeRefCounts(stObs,
        ChainLedger.FaceCounts(activeMembers = activeMembers, declarations = declarations, batchIds = batchIds))
      val retire = ChainLedger.planRetire(stCounted, now)
      val stRetired = retire.state
      // ── 轮 1（dissolve：链已离场 ⇒ 整行下沉；行已由 observe 移出热面 ⇒ hotBefore 补回）──
      val dissolveRows = obs.dissolved
      val orphanAliases = obs.orphanAliases
      val hasDissolve = dissolveRows.nonEmpty || orphanAliases.nonEmpty
      val roundDissolve = st0.rounds.size + 1
      val hotBeforeDissolve = Totals(
        entries = ChainLedger.totals(stObs).entries + dissolveRows.size,
        aliases = ChainLedger.totals(stObs).aliases + orphanAliases.size,
        members = ChainLedger.totals(stObs).members + dissolveRows.map(_.memberCount).sum,
        refCount = ChainLedger.totals(stObs).refCount +
          dissolveRows.map(_.refCount.toLong).sum + orphanAliases.map(_.refCount.toLong).sum)
      val fileDissolve = roundFileOf(roundDissolve, RoundDissolve, now,
        hotBeforeDissolve, ChainLedger.totals(stObs), dissolveRows, orphanAliases)
      for
        // ── 轮 1（dissolve：链已离场 ⇒ 整行下沉；行已由 observe 移出热面 ⇒ hotBefore 补回，
        //    使「hotAfter = hotBefore − moved」守恒式对该轮同样成立）──
        digestD <- if hasDissolve then writeRound(fileDissolve) else IO.pure("")
        stAfterDissolve = if hasDissolve then
          stObs.copy(rounds = st0.rounds :+ manifestOf(fileDissolve, digestD)) else stObs
        // ── 轮 2（retire：轴 a×b 联合判据；热行移除、冷档留全行）──
        hasRetire = retire.entries.nonEmpty || retire.aliases.nonEmpty
        roundRetire = stAfterDissolve.rounds.size + 1
        hotBeforeRetire = ChainLedger.totals(
          stRetired.copy(
            entries = stRetired.entries ++ retire.entries.map(e => e.chainId -> e),
            aliases = stRetired.aliases ++ retire.aliases.map(a => a.alias -> a)))
        fileRetire = roundFileOf(roundRetire, RoundRetire, now, hotBeforeRetire,
          ChainLedger.totals(stRetired), retire.entries, retire.aliases)
        digestR <- if hasRetire then writeRound(fileRetire) else IO.pure("")
        stAfterRetire =
          if hasRetire then stRetired.copy(rounds = stAfterDissolve.rounds :+ manifestOf(fileRetire, digestR))
          else stRetired.copy(rounds = stAfterDissolve.rounds)
        // ── 轮 3（compact：轴 c 双阈值 —— 条数 ∨ 字节）──
        bytes <- IO.blocking(encode(stAfterRetire).getBytes("UTF-8").length.toLong)
        capExceeded = ChainLedger.needsCompaction(stAfterRetire, bytes)
        plan =
          if capExceeded then ChainLedger.planCompaction(stAfterRetire, bytes)
          else ChainLedger.CompactPlan(stAfterRetire)
        hasCompact = capExceeded && !plan.isEmpty
        roundCompact = stAfterRetire.rounds.size + 1
        fileCompact = roundFileOf(roundCompact, RoundCompact, now, plan.hotBefore, plan.hotAfter,
          plan.compactedEntries, plan.retiredAliases)
        digestC <- if hasCompact then writeRound(fileCompact) else IO.pure("")
        compactManifest = if hasCompact then Some(manifestOf(fileCompact, digestC)) else None
        stFinal =
          val base = if hasCompact then plan.state else stAfterRetire
          base.copy(project = project, rounds = stAfterRetire.rounds ++ compactManifest.toList,
            updatedAt = now)
        _ <- persist(stFinal)
        _ <- state.set(stFinal)
        _ <-
          if hasDissolve || hasRetire || hasCompact then
            IO(logger.infoSync(
              s"chain-ledger[$project] rounds: dissolve=${if hasDissolve then 1 else 0}" +
                s" retire=${retire.entries.size}E/${retire.aliases.size}A" +
                s" compact=${if hasCompact then 1 else 0}" +
                s" hotBefore=${ChainLedger.hotRows(stAfterRetire)} rows/${bytes}B" +
                s" hotAfter=${ChainLedger.hotRows(stFinal)} rows" +
                s" caps=${ThresholdsNow.maxHotRows}rows/${ThresholdsNow.maxHotBytes}B"))
          else if capExceeded then
            // 超阈值但**无行可搬**（全库载荷已下沉）⇒ 只记信号，禁每拍空转成风暴
            IO(logger.warnSync(
              s"chain-ledger[$project] over cap (${ChainLedger.hotRows(stAfterRetire)} rows / ${bytes}B)" +
                s" but nothing movable (all payloads already archived) — signal only, no round"))
          else IO.unit
      yield obs.copy(
        state = stFinal,
        retired = retire.entries.size + retire.aliases.size,
        capExceeded = capExceeded,
        compaction = compactManifest
      )
    }

  // ── 自检（启动期 / verify 位）────────────────────────

  /** 台账全量自检：结构 + 每轮守恒 + 轮间链式 + 压缩镜像（判据全在 [[ChainLedger]]）。 */
  def verify: IO[Either[String, Unit]] =
    for
      st <- state.get
      files <- readRounds
    yield ChainLedger.verifyLedger(st, files, payloadIdsOf(files))

  /** 压缩镜像面（**取每个 id 的最晚一轮口径**）：某 id 的载荷此刻是否应在冷档 ——
    * 最晚提到它的那一轮是 compact ⇒ 热面应有 `compacted=true` 的条目（镜像成对）；
    * 最晚一轮是 retire/dissolve ⇒ 热面不应有该条目。 */
  private def payloadIdsOf(files: List[RoundFile]): Set[String] =
    files.sortBy(_.round).foldLeft(Map.empty[String, String]) { (acc, f) =>
      acc ++ f.entries.map(_.chainId).map(_ -> f.kind)
    }.filter(_._2 == RoundCompact).keySet

  /** 启动期自检（best-effort：不过只 WARN，**绝不**因台账不自洽阻断项目挂载）。 */
  private def selfCheck(tag: String): IO[Unit] =
    verify.flatMap {
      case Right(()) => IO.unit
      case Left(diag) =>
        IO(logger.warnSync(
          s"chain-ledger[$project] self-check FAILED ($tag): $diag — 台账不自洽（只报不阻；" +
            s"排查面：${path} + ${archiveDir}）"))
    }

object ChainLedgerStore:

  private val logger: NebflowLogger = NebflowLogger.forName("nebflow.chain-ledger")

  /** **启动期载入**（与 `flow-map.json` 同生命周期）：文件缺失 ⇒ 空账；损坏 ⇒ WARN + 空账
    * 起步（台账是派生面 + 别名表，重建只丢历史别名，不丢图事实 ⇒ 不阻启动，但绝不静默）。 */
  def open(project: String, ledgerPath: os.Path, archivePath: os.Path): IO[ChainLedgerStore] =
    for
      loaded <- IO.blocking {
        if !os.exists(ledgerPath) then ChainLedger.State(project = project)
        else
          jsonParse(os.read(ledgerPath)).flatMap(_.as[ChainLedger.State]) match
            case Right(s) => s.copy(project = project)
            case Left(e) =>
              logger.warnSync(
                s"chain-ledger[$project] ${ledgerPath.last} corrupt: $e — starting empty (台账可重建；历史别名丢失，图事实零影响)")
              ChainLedger.State(project = project)
      }
      store = new ChainLedgerStore(project, ledgerPath, archivePath,
        Ref.unsafe[IO, ChainLedger.State](loaded), logger)
      _ <- store.selfCheck("open")
    yield store
