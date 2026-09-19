package nebflow.core.project

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}

/**
 * ChainLedger —— **链号台账**（chainmodel 批二：链号台账 + 链号不变 + 退出机制三轴）。
 *
 * 设计正本（只读引用）：`~/.nebflow/docs/Nebflow/20260919_121852_chainmodel-design.md`
 * （设计位 n-c7e4aa2f，PLAN-ONLY 只读取证）§一 取证 1/2/5/6 + §二(b) + §三「接缝/同批必补」；
 * 过程件 `.nebflow/reports/20260919_121852_chainmodel-design.md` §2（chainId 稳定性与
 * 悬空面）/ §5（TTL 归档与链）/ §9（未取证项）。作者 2026-09-19 13:10 终裁令 = 成员制 +
 * **台账照案实施** + **退出机制三轴强制内置**。
 *
 * == 本类只做三件事（第 14 条反过度设计；批界逐条钉死）==
 *  1. **持久台账**：链号 → {成员集, 状态, **别名表**}，落盘（原子写）+ 启动期载入 +
 *     与 `flow-map.json` 同生命周期 + 崩溃/重启后自洽（可复算校验）。
 *  2. **链号出生即定、永不重归**：链号在链**出生那一刻**由本类登记；此后任何「按分量
 *     重算」的路径都不得改写一个已出生链号（[[observe]] 是唯一裁决点）。
 *  3. **拆分/合并 ⇒ 显式改号 + 旧号保留别名**：改号走 [[observe]] 的显式路径并留痕
 *     （逐节点 `chain-membership-changed`，字段 = 旧号/新号/原因，与批一同值域）；
 *     旧号进 [[AliasRow]] **永久可达**（[[resolve]] 热面单点 + 冷档兜底）。
 *
 * 🔴 **本批不做**（批界，禁越界；第 14 条）：不改 Mail 校验（`MailTool.scala:629-646`）、
 * 不改 Flow Map 载荷与 `FlowMapStore.mergeChainIds`、不动合并窗痛点③判据、不动 `deps`
 * 节点引用语义与既有闸 —— 三者归档三。本类只提供判据与数据面，**不接管任何既有消费方**。
 *
 * == 判据（机械、零启发式）==
 * **出生**：某派生分量在台账里**无命中条目** ⇒ 出生，链号 = 该分量派生值（与批一
 * `topologicalChains` 逐字同值：声明值逐字 / 兜底 `chain-<分量内 createdAt 最早节点 id>`）。
 * **承继**：分量恰命中一条既有条目 ⇒ 该条目链号逐字保留（**成员集可生长/收缩，链号不动**
 * —— 现状病根「读时现算 + 分量合并改号」的反面）。
 * **合并**（一个分量命中 ≥2 条既有条目）：生还者 = 命中集中 `(bornAt, chainId)` 最小者
 * （**先出生者保号**；含该条目锚点的优先），其余 id **改为别名**指向生还者（显式改号 + 留痕）。
 * **拆分**（一条既有条目的成员落在 ≥2 个分量里）：**锚点所在分量**承继原链号，其余分量
 * 各自出生 ⇒ 拆出去的节点各记一条 `re-id` 留痕（旧号 → 新号）。
 * **离场**（条目在本次派生里无任何命中分量 ⇒ 该链已不在图上）：条目离场（轴 a 的
 * 「禁留悬空条目」臂），其**载荷整行下沉冷档**（只归档不删除）。
 * 确定性：分量按 id 排序、命中集按 `(bornAt, chainId)` 排序、别名目标取 id 升序首项 ——
 * 同输入恒同输出。
 *
 * == 「归属 vs 排队」的边界（与批一的责任分界，禁重复记账）==
 * 本类只发射**改号**留痕（`old`/`new` 都非空且不等 ⇒ [[ReasonReId]]）。`None ↔ X` 的跃迁
 * （首次归属 / 声明 / 撤销后回落 / 编外重归）是批一写点
 * `NodeTools.emitChainMembershipChanges` 的责任面，本类**不重复计账**（同一次归属变化出
 * 两条事件 = 审计噪声，且必有一条是假事实）。
 *
 * == 退出机制三轴（出生即有，非事后补丁）==
 * **(a) 生命周期绑定**：条目随所属链**归档同刻**翻 [[StatusArchived]]（挂点 =
 * `FlowMapStore.sweepCompletedChainsDetailed` 的同一次写事务；反向
 * `restoreChainsFromArchiveDetailed` 翻回 [[StatusActive]]）；别名行**不得早于**所属链
 * 归档退役（归档刻 = 最早退役窗口）；条目/别名行**一律不得留悬空引用**（[[structureCheck]]）。
 * **(b) 引用计数退役**：退役判据 = `refCount == 0`，计数由 [[ReferenceFaces]] 枚举的面在
 * 每一拍 reconcile 上**覆盖式全量复算**（🔴 禁增量自减 —— 丢更新不可复算；🔴 禁「人工
 * 判断无引用」）。计数持久化在台账本体（[[Entry.refCount]] / [[AliasRow.refCount]]）。
 * **(c) 硬上限压缩**：[[MaxHotRows]] / [[MaxHotBytes]] **双阈值**触发压缩轮：载荷搬进
 * `chain-ledger-archive/round-<n>.json`（**只归档不删除**），每轮留 [[RoundManifest]]
 * （阈值 + 搬迁计数 + 前后读数 + 内容摘要）⇒ 留痕可复算、压缩后可再复算与原账一致
 * （[[roundConservation]] / [[mirrorCheck]] / [[verifyLedger]]）。
 *
 * == 可复算校验（崩溃/重启后自洽）==
 * [[verifyLedger]] 四查（任一不过 ⇒ `Left` 可行动诊断；调用方 WARN，**绝不静默吞掉**、
 * 也不因它崩服务）：① 每轮守恒（`hotAfter` 由 `hotBefore` + 搬迁量机械推出）；
 * ② 轮间链式（`k.hotBefore == (k-1).hotAfter`）；③ 压缩镜像一一对应（`compacted=true`
 * 的热行在冷档有载荷副本，反之亦然）；④ 结构（同 id 不同时作条目与别名；别名不悬空）。
 */
object ChainLedger:

  // ── 落点（引擎自管面：`<workspace>/.nebflow/**` 已 gitignore）──────────────
  /** 台账本体文件名（与 `flow-map.json` 同目录、同生命周期）。 */
  val FileName: String = "chain-ledger.json"

  /** 冷档目录名（压缩轮 / 退役 / 离场行的唯一下沉面；**只归档不删除**）。 */
  val ArchiveDirName: String = "chain-ledger-archive"

  val Version: Int = 1

  // ── 条目状态 ─────────────────────────────────────────
  val StatusActive: String = "active"
  val StatusArchived: String = "archived"

  // ── 冷档轮类型（留痕形态判据）────────────────────────
  /** 轴(c)：载荷搬迁（热行保留身份，`members` 清空 + `compacted=true`）。 */
  val RoundCompact: String = "compact"
  /** 轴(a)+(b)：整行退役（热行移除，冷档保留全行）。 */
  val RoundRetire: String = "retire"
  /** 轴(a) 禁悬空臂：链已离场 ⇒ 整行下沉（热行移除，冷档保留全行）。 */
  val RoundDissolve: String = "dissolve"

  // ── 变更原因码（与 FlowMapEventLog.chainMembershipChangedSummary 同值域）──
  val ReasonReId: String = "re-id"

  // ── 轴(c) 双阈值（**在册可查**：常量 + 随每轮冷档留痕）──────────────────
  /** 条数阈值：热台账行数（`entries + aliases`）上限。 */
  val MaxHotRows: Int = 4096
  /** 字节阈值：热台账 JSON（= 落盘字节）上限。 */
  val MaxHotBytes: Int = 1024 * 1024
  /** 单轮搬迁行数上限（有界轮：一轮不搬空全库 ⇒ 可观测、可中断复跑）。 */
  val CompactionBatchRows: Int = 512

  /** 阈值在册形态（随每轮冷档落盘 ⇒「这一轮按哪组阈值触发」事后可查、可复算）。 */
  final case class Thresholds(maxHotRows: Int, maxHotBytes: Int, batchRows: Int)

  object Thresholds:
    given Configuration = Configuration.default.withDefaults
    given Codec[Thresholds] = ConfiguredCodec.derived

  val ThresholdsNow: Thresholds = Thresholds(MaxHotRows, MaxHotBytes, CompactionBatchRows)

  // ── 模型 ─────────────────────────────────────────────

  /** **台账条目** = 一条**已出生**链。
    *
    * @param chainId     出生即定的链号（本类是其唯一裁决点；永不按分量重算）
    * @param anchor      出生时刻分量内 `(createdAt, id)` 最小成员 —— **身份锚**：载荷已
    *                    压缩时命中判据退化为「分量是否含锚点」（见 [[observe]]）
    * @param bornAt      出生时刻（生还者判定第一键：先出生者保号）
    * @param members     最近一次观察到的成员集（`(createdAt, id)` 升序；压缩后为 `Nil`）
    * @param memberCount 成员数（**压缩不改读数**：载荷搬迁不动它）
    * @param status      [[StatusActive]] | [[StatusArchived]]（轴 a，由归档写点翻）
    * @param archivedAt  归档时刻（轴 a 绑定点；也是压缩排序的冷度键）
    * @param refCount    活引用数（轴 b；覆盖式复算，持久化）
    * @param compacted   载荷已下沉（`members` 在冷档）——身份/状态/计数**不随压缩变化** */
  final case class Entry(
      chainId: String,
      anchor: String,
      bornAt: Long,
      members: List[String] = Nil,
      memberCount: Int = 0,
      status: String = StatusActive,
      archivedAt: Option[Long] = None,
      refCount: Int = 0,
      compacted: Boolean = false
  )

  object Entry:
    given Configuration = Configuration.default.withDefaults
    given Codec[Entry] = ConfiguredCodec.derived

  /** **别名行** = 改号遗留的旧链号 → 现链号（合并被吸收者 / 显式改号的历史名）。
    *
    * 🔴 旧号在本行存在期间（热面）或冷档存在期间（`ChainLedgerStore.resolveDeep`）
    * **永久可达** —— 供批三 Mail 校验与一切引用面解析，禁静默丢弃。 */
  final case class AliasRow(alias: String, canonical: String, createdAt: Long, refCount: Int = 0)

  object AliasRow:
    given Configuration = Configuration.default.withDefaults
    given Codec[AliasRow] = ConfiguredCodec.derived

  /** 台账读数（可复算载体）：行数 / 成员数 / 引用数；压缩前后**恒等**（轴 c 不变量）。 */
  final case class Totals(entries: Int = 0, aliases: Int = 0, members: Int = 0, refCount: Long = 0L)

  object Totals:
    given Configuration = Configuration.default.withDefaults
    given Codec[Totals] = ConfiguredCodec.derived

  /** **压缩轮清单**（热台账里的一行账）：一轮恰一条，留痕可复算。
    *
    * @param round        轮号（1 起 = `rounds.size + 1`；崩在「冷档已写、热账未写」之间 ⇒
    *                     下一拍以同轮号重放（候选集确定 ⇒ 内容相同 ⇒ 幂等覆写））
    * @param kind         [[RoundCompact]] | [[RoundRetire]] | [[RoundDissolve]]
    * @param file         冷档文件名（`round-<n>.json`）
    * @param movedEntries 本轮搬走的条目数
    * @param movedAliases 本轮搬走的别名行数
    * @param movedMembers 本轮搬走的成员 id 数（载荷量）
    * @param hotBefore    本轮前的热读数
    * @param hotAfter     本轮后的热读数（守恒判据见 [[roundConservation]]）
    * @param digest       冷档文件字节 sha-256（留痕 +「冷档未被改写」核对面） */
  final case class RoundManifest(
      round: Int,
      at: Long,
      kind: String,
      file: String,
      movedEntries: Int,
      movedAliases: Int,
      movedMembers: Int,
      hotBefore: Totals,
      hotAfter: Totals,
      digest: String
  )

  object RoundManifest:
    given Configuration = Configuration.default.withDefaults
    given Codec[RoundManifest] = ConfiguredCodec.derived

  /** 台账热态（落盘 `<workspace>/.nebflow/chain-ledger.json`；原子写）。 */
  final case class State(
      v: Int = Version,
      project: String = "",
      updatedAt: Long = 0L,
      entries: Map[String, Entry] = Map.empty,
      aliases: Map[String, AliasRow] = Map.empty,
      /** 外部引用面已计的增量（`noteReference` 写入；面登记见 [[ReferenceFaces]]） */
      externalRefs: Map[String, Int] = Map.empty,
      /** 压缩/退役/离场轮清单（append-only，按轮号升序） */
      rounds: List[RoundManifest] = Nil
  )

  object State:
    given Configuration = Configuration.default.withDefaults
    given Codec[State] = ConfiguredCodec.derived

  /** **归属变更留痕**（字段与批一 `ChainMembershipChangedType` 逐字同构：节点 id + 旧号 +
    * 新号 + 原因；`reason` 恒 [[ReasonReId]]，批界见类头注）。 */
  final case class Change(nodeId: String, from: Option[String], to: Option[String], reason: String)

  object Change:
    given Configuration = Configuration.default.withDefaults
    given Codec[Change] = ConfiguredCodec.derived

  /** 一次 reconcile 的结构化产出（调用方据此发射事件 / 记日志；**派生只发生一次**）。 */
  final case class Observation(
      state: State,
      changes: List[Change] = Nil,
      born: List[String] = Nil,
      absorbed: List[String] = Nil,
      /** **本拍裁决表**：派生原型链号（`ChainInfo.id`）→ 该分量本拍的**稳定链号**
        * （= 出生号 / 承继号 / 生还者号）。调用方据此把「分量成员」折算到稳定链号上
        * （轴 b 的 `live-member` 面**不依赖**行内载荷 —— 载荷可能已压缩下沉，读数口径
        * 因此与 [[Entry.compacted]] 无关）。 */
      assigned: Map[String, String] = Map.empty,
      /** 本拍离场（链已不在图上）的条目**全行** —— 只归档不删除（下沉冷档） */
      dissolved: List[Entry] = Nil,
      /** 本拍失去 canonical（所属链已离场且无吸收目标）的别名行**全行** —— 同款下沉 */
      orphanAliases: List[AliasRow] = Nil,
      /** 轴(a)×(b)：本拍退役行数（条目 + 别名行；0 = 无行满足「已归档 ∧ 零引用」） */
      retired: Int = 0,
      /** 轴(c)：本拍热读数是否越过双阈值（true 且 `compaction` 为空 = 超限但无行可搬，
      * 已落 WARN 信号 —— 见 `ChainLedgerStore.reconcile`，禁空转成风暴） */
      capExceeded: Boolean = false,
      /** 轴(c)：本拍压缩轮清单（有 ⇒ 冷档已落 + 热账 `rounds` 已追加） */
      compaction: Option[RoundManifest] = None
  )

  /** **冷档文件**（`chain-ledger-archive/round-<n>.json`）：搬迁行的**逐字载荷**
    * （只归档不删除的落点）+ 该轮自证读数（阈值 / 前后读数）⇒ 离线可复算。 */
  final case class RoundFile(
      round: Int,
      at: Long,
      kind: String,
      thresholds: Thresholds,
      hotBefore: Totals,
      hotAfter: Totals,
      entries: List[Entry] = Nil,
      aliases: List[AliasRow] = Nil
  )

  object RoundFile:
    given Configuration = Configuration.default.withDefaults
    given Codec[RoundFile] = ConfiguredCodec.derived

  /** 引用面登记（[[ReferenceFaces]] 的元素形态）。 */
  final case class ReferenceFace(
      id: String,
      label: String,
      /** 本批是否已接线（false ⇒ 计数钩子归 `owner` 批次） */
      wired: Boolean,
      owner: String,
      /** 计引用写点（`path:line` 锚点，可机械核对） */
      writePoint: String,
      incWhen: String,
      decWhen: String
  )

  /**
   * **引用面枚举（轴 b 的判据正本；机械可核）**
   *
   * 计数口径：每一拍 reconcile 对**全部**条目/别名行按本表逐面求值并**覆盖式复算**
   * （`refCount = Σ 已接线面`）。🔴 禁增量自减、禁「人工判断无引用」。
   *
   * **已接线四面**（数据源全在引擎自管状态内 ⇒ 零新写点即机械可求）：
   *   1. `live-member`    链上仍有**活动区**成员（该成员载荷带 chainId）。
   *   2. `declaration`    有节点**显式声明**该链号（`NodeDef.chainId`，批一字段）。
   *   3. `archive-batch`  归档批以该链号命名（批文件 `<chainId>.json` + 批次索引）。
   *   4. `alias-target`   别名行仍解析到本链号（别名表自身是引用者）。
   *
   * **未接线三面（如实登记；🔴 禁以人工判断代替计数）**：本批红线禁改 Mail 校验、禁改
   * Flow Map 载荷/板卡/报告面 ⇒ 三面只登记**写入点 + 计数时机 + 归属批次**，由
   * `ChainLedgerStore.noteReference` 的显式 API 在对应批次接线（接线前的引用不计数 =
   * 登记在册的**已知缺口**，见批报告「未决/风险」栏，不是静默省略）。
   */
  val ReferenceFaces: List[ReferenceFace] = List(
    ReferenceFace(
      id = "live-member",
      label = "链上仍有活动区成员（该成员载荷带 chainId）",
      wired = true,
      owner = "batch2",
      writePoint = "src/main/scala/nebflow/core/project/FlowMapStore.scala (reconcileChainLedger ← 活动区快照 state.nodes)",
      incWhen = "节点属该链且仍在活动区（本链落库/拉回后，下一拍复算即计）",
      decWhen = "该链活动区成员清零（整链出库 sweep 同拍；见 sweepCompletedChainsDetailed）"
    ),
    ReferenceFace(
      id = "declaration",
      label = "有节点显式声明该链号（NodeDef.chainId）",
      wired = true,
      owner = "batch2",
      writePoint = "src/main/scala/nebflow/core/tools/NodeTools.scala (NodeEdit chainId 声明写回；chainmodel 批一 ①)",
      incWhen = "节点声明值 = 该链号（声明即归属；含单成员声明链）",
      decWhen = "声明被撤销（chainId=null）或改号为其它链号"
    ),
    ReferenceFace(
      id = "archive-batch",
      label = "归档批以该链号命名（批文件 + 批次索引）",
      wired = true,
      owner = "batch2",
      writePoint = "src/main/scala/nebflow/core/project/FlowMapStore.scala (sweepCompletedChainsDetailed → persistBatchFiles)",
      incWhen = "链出库注册归档批（批 id = 链号，批文件名 = <chainId>.json）",
      decWhen = "批被拉回（restoreChainsFromArchiveDetailed 清索引）或批文件删除（persistBatchFiles 成员全移除臂）"
    ),
    ReferenceFace(
      id = "alias-target",
      label = "别名行仍解析到本链号（别名表自身是引用者）",
      wired = true,
      owner = "batch2",
      writePoint = "src/main/scala/nebflow/core/project/ChainLedger.scala (observe 的别名表写入臂)",
      incWhen = "一条别名行以该链号为 canonical（改号吸收）",
      decWhen = "别名行退役（轴 b 计数归零）或重指向"
    ),
    ReferenceFace(
      id = "mail-usage",
      label = "Mail 正文注入 [mail chainId: <id>]（收件人可回引）",
      wired = false,
      owner = "batch3",
      writePoint = "src/main/scala/nebflow/core/tools/MailTool.scala:621-626 (withChainAnnotation；校验点 :629-646 validateChainId)",
      incWhen = "Mail 携带 chainId 且投递成功（批三：与 Mail 校验集合并兜底派生同批接线）",
      decWhen = "（正文为历史事实，只计不减；容量退役交轴 c 硬上限）"
    ),
    ReferenceFace(
      id = "board-usage",
      label = "板卡/任务书引用链号（任务书正文与板卡条目）",
      wired = false,
      owner = "batch3+",
      writePoint = "src/main/scala/nebflow/core/project/TaskBoardStore.scala (板卡写入点) / <workspace>/.nebflow/tasks/<nodeId>.md",
      incWhen = "任务书或板卡条目正文引用该链号（批三+：引用面写入点接线）",
      decWhen = "（正文为历史事实，只计不减；容量退役交轴 c 硬上限）"
    ),
    ReferenceFace(
      id = "report-usage",
      label = "节点报告 / 结果正文引用链号",
      wired = false,
      owner = "batch3+",
      writePoint = "src/main/scala/nebflow/core/tools/NodeReportTool.scala → NodeReportRegistry (node-reports.jsonl) / results/<nodeId>.md",
      incWhen = "node_report detail / 结果正文引用该链号（批三+：引用面写入点接线）",
      decWhen = "（正文为历史事实，只计不减；容量退役交轴 c 硬上限）"
    )
  )

  /** 已接线引用面 id 集（计数判据的机械白名单；改本表 = 改计数口径，须同步测试）。 */
  val WiredFaceIds: List[String] = ReferenceFaces.filter(_.wired).map(_.id)

  /** 未接线引用面 id 集（已知缺口清单；批三接线后本表必须随之缩短）。 */
  val PendingFaceIds: List[String] = ReferenceFaces.filterNot(_.wired).map(_.id)

  // ── 派生视图与解析 ───────────────────────────────────

  /** 条目成员视图（nodeId → 链号）。压缩行以锚点代成员（身份恒在，见 [[hitKeys]]）。 */
  def entryView(e: Entry): Map[String, String] =
    hitKeys(e).map(_ -> e.chainId).toMap

  def viewOf(entries: Iterable[Entry]): Map[String, String] =
    entries.foldLeft(Map.empty[String, String])((acc, e) => acc ++ entryView(e))

  /** **链号解析单点（热面）**：现链号 → 自身；旧链号（别名）→ 现链号（逐跳，带环守卫）。
    * 冷档内（已下沉）的行由 `ChainLedgerStore.resolveDeep` 兜底 ⇒「旧号永久可达」分两级。 */
  def resolve(st: State, id: String): Option[String] =
    var cur = id
    var hops = 0
    var result: Option[String] = None
    var done = false
    while !done do
      if st.entries.contains(cur) then
        result = Some(cur)
        done = true
      else
        st.aliases.get(cur) match
          case Some(r) if r.canonical != cur && hops <= st.aliases.size =>
            cur = r.canonical
            hops += 1
          case _ =>
            done = true
    result

  // ── 轴：出生 / 承继 / 合并 / 拆分（链号稳定化的唯一裁决点）──────────────

  /** **命中面判据单点**：条目在「分量 ↔ 条目」比对里暴露的键 —— 载荷已压缩（或载荷为
    * 空）⇒ 只剩**锚点**（身份恒在，见 [[Entry.compacted]]）；否则 = 成员集。命中比对、
    * 成员视图、别名目标求值三处**共用本函数**（禁各写一份 `if compacted ...` 分支）。 */
  private def hitKeys(e: Entry): List[String] =
    if e.compacted || e.members.isEmpty then List(e.anchor) else e.members

  /**
   * **稳定化**（纯函数）：把 `topologicalChains` 的**派生原型链**折算成**出生即定的链号**。
   *
   * 这是「链号出生即定、永不重归」的唯一落点：已出生链号的改写**只能**经本方法的显式
   * 路径（承继 = 不改；合并/拆分 = 显式改号 + 别名 + 留痕），不存在任何「按分量重算
   * 覆盖旧号」的分支。
   *
   * @param chains 派生原型链（`FlowMapStore.topologicalChains` 的现读结果，判据单点）
   */
  def observe(st: State, chains: List[ChainInfo], now: Long): Observation =
    val comps = chains.sortBy(_.id)
    val live = st.entries.values.toList
    // ① 命中：分量 ↔ 既有条目（载荷在热面比成员集；载荷已压缩比锚点）
    //    成员集每分量**只建一次**，命中面走**倒排索引**（`节点/锚点 → 条目`）——
    //    逐「分量 × 条目」笛卡尔遍历会放大成 O(分量 × 条目 × 成员) 次比较/分配，
    //    而本函数每 30s 一拍，大图上不可接受（等价性：条目成员集两两不交，见
    //    下方 `keptEntries` 构造；命中面两支由 [[hitKeys]] 单点给出）。
    val compMembers: Map[String, Set[String]] = comps.map(c => c.id -> c.memberIds.toSet).toMap
    val byPayload: Map[String, Entry] = live.flatMap(e => hitKeys(e).map(_ -> e)).toMap
    val compMatches: Map[String, List[Entry]] =
      comps.map(c => c.id -> compMembers.getOrElse(c.id, Set.empty).flatMap(byPayload.get).toList.distinct).toMap
    // ② 逐分量裁决（两档序保证确定性，且「锚点持有者先得号」——拆分时原链号随锚点走）：
    //    档 0 = 该分量含某命中条目的锚点；档 1 = 其余。档内按常量 id 排序。
    val ordered = comps.sortBy { c =>
      val hs = compMatches.getOrElse(c.id, Nil)
      (if hs.exists(e => c.memberIds.contains(e.anchor)) then 0 else 1, c.id)
    }
    val assigned = scala.collection.mutable.LinkedHashMap.empty[String, String] // compId -> stable id
    val used = scala.collection.mutable.HashSet.empty[String]                   // 已被占用的稳定链号
    def free(id: String): Boolean = !used.contains(id)
    def freshId(proto: String): String =
      if free(proto) then proto
      else
        var k = 1
        while !free(s"$proto.$k") do k += 1
        s"$proto.$k"
    ordered.foreach { c =>
      val ms = compMatches.getOrElse(c.id, Nil).filterNot(e => used.contains(e.chainId))
      val pool =
        val hs = ms.filter(e => c.memberIds.contains(e.anchor))
        if hs.nonEmpty then hs else ms
      val owner = if pool.isEmpty then None else Some(pool.minBy(e => (e.bornAt, e.chainId)))
      val chosen = owner.map(_.chainId).getOrElse(freshId(c.id))
      assigned.update(c.id, chosen)
      used += chosen
    }
    // ③ 承继 / 出生
    val nextEntries = scala.collection.mutable.LinkedHashMap.empty[String, Entry]
    val born = scala.collection.mutable.ListBuffer.empty[String]
    comps.foreach { c =>
      val cid = assigned(c.id)
      st.entries.get(cid) match
        case Some(prev) if prev.compacted =>
          // 承继（**载荷已下沉**）：链号/锚点/出生时刻/状态/归档时刻/成员读数/计数一律
          // 不动 —— 载荷在冷档（`RoundFile.entries`），**不回热**。回热会让压缩每拍被
          // 撤销（轴 c 失效）+ 压缩镜像面翻转（`verifyLedger` 的 ③ 查），故压缩对载荷是
          // **终局**：只有退役/离场会移除该行（其载荷副本恒在冷档 ⇒ 只归档不删除）。
          // 身份恒在（锚点留热）⇒ 链号继续按 [[hitKeys]] 命中，零重归。
          nextEntries.update(cid, prev)
        case Some(prev) =>
          // 承继（载荷在热面）：链号/锚点/出生时刻/状态/归档时刻/计数**一律不动**，只
          // 刷新成员读数（成员集可生长/收缩；链号不动 = 「永不重归」的可执行判据）。
          nextEntries.update(cid,
            prev.copy(members = c.memberIds, memberCount = c.memberIds.size))
        case None =>
          born += cid
          nextEntries.update(cid, Entry(
            chainId = cid,
            anchor = c.memberIds.headOption.getOrElse(cid),
            bornAt = now,
            members = c.memberIds,
            memberCount = c.memberIds.size))
    }
    val keptEntries = nextEntries.toMap
    // ④ 别名：被吸收 / 已离场的旧号 → 生还者（显式改号；旧号永久可达）
    val superseded = live.map(_.chainId).filterNot(keptEntries.contains).sorted
    val absorbedTargets: Map[String, String] = superseded.flatMap { old =>
      val e = st.entries(old)
      val touch = hitKeys(e).toSet
      val cands = comps.filter(c => c.memberIds.exists(touch.contains)).map(c => assigned(c.id)).distinct.sorted
      if cands.isEmpty then None else Some(old -> cands.head)
    }.toMap
    // 既有别名行：canonical 尚在 ⇒ 保行（吸收则重指向）；canonical 已离场且无吸收目标
    // ⇒ 本行失去目标，**下沉冷档**（既不留悬空行，也不静默删除）
    val repointed: (Map[String, AliasRow], List[AliasRow]) =
      st.aliases.foldLeft((Map.empty[String, AliasRow], List.empty[AliasRow])) {
        case ((keep, orphan), (k, r)) =>
          val t =
            if keptEntries.contains(r.canonical) then r.canonical
            else absorbedTargets.getOrElse(r.canonical, "")
          if t.nonEmpty then (keep.updated(k, r.copy(canonical = t)), orphan)
          else (keep, r :: orphan)
      }
    val (keptAliases, orphanAliases) = repointed
    val aliasesAfter: Map[String, AliasRow] =
      keptAliases ++ absorbedTargets.map { case (old, target) =>
        old -> st.aliases.get(old)
          .map(_.copy(canonical = target))
          .getOrElse(AliasRow(alias = old, canonical = target, createdAt = now))
      }
    // ⑤ 改号留痕（只记 X→Y（X≠Y）：None↔X 归批一写点，见类头注批界）
    val beforeView = viewOf(live)
    val afterView = viewOf(nextEntries.values)
    val changes = (beforeView.keySet ++ afterView.keySet).toList.sorted.flatMap { n =>
      (beforeView.get(n), afterView.get(n)) match
        case (Some(a), Some(b)) if a != b => Some(Change(n, Some(a), Some(b), ReasonReId))
        case _                            => None
    }
    Observation(
      state = st.copy(entries = keptEntries, aliases = aliasesAfter, updatedAt = now),
      changes = changes,
      born = born.toList,
      absorbed = absorbedTargets.keys.toList.sorted,
      assigned = assigned.toMap,
      dissolved = superseded.filterNot(absorbedTargets.contains).flatMap(st.entries.get),
      orphanAliases = orphanAliases.sortBy(_.alias)
    )

  // ── 轴(b)：引用计数复算（机械、覆盖式；禁增量自减）──────────────────────

  /** 引用面求值的机械输入（全部来自引擎自管状态；零人工判断）。 */
  final case class FaceCounts(
      /** 链号 → 该链在**活动区**的成员 id（face `live-member`） */
      activeMembers: Map[String, Set[String]] = Map.empty,
      /** 链号 → 声明该链号的节点 id（face `declaration`） */
      declarations: Map[String, Set[String]] = Map.empty,
      /** 归档批 id 全集（face `archive-batch`） */
      batchIds: Set[String] = Set.empty
  )

  object FaceCounts:
    given Configuration = Configuration.default.withDefaults
    given Codec[FaceCounts] = ConfiguredCodec.derived

  /** 覆盖式复算全部行计数（轴 b）。**纯函数**：同输入恒同输出 ⇒ 可复算、可测试、崩溃后
    * 下一拍自然纠正（不存在丢更新）。外部引用面（`noteReference` 写入的
    * [[State.externalRefs]]）按账并入。 */
  def recomputeRefCounts(st: State, in: FaceCounts): State =
    val entries = st.entries.map { case (k, e) =>
      val c = in.activeMembers.getOrElse(k, Set.empty).size +
        in.declarations.getOrElse(k, Set.empty).size +
        (if in.batchIds.contains(k) then 1 else 0) +
        st.externalRefs.getOrElse(k, 0)
      k -> e.copy(refCount = c)
    }
    val aliases = st.aliases.map { case (k, a) =>
      // 别名行的面：声明面 + 归档批面 + 外部面 +「仍有别名行以本链号为 canonical」面
      val self = in.declarations.getOrElse(k, Set.empty).size +
        (if in.batchIds.contains(k) then 1 else 0) +
        st.externalRefs.getOrElse(k, 0)
      k -> a.copy(refCount = self + (if st.entries.contains(a.canonical) then 1 else 0))
    }
    st.copy(entries = entries, aliases = aliases)

  // ── 轴(a)+(b)：退役计划 ──────────────────────────────

  final case class RetirePlan(
      state: State,
      entries: List[Entry] = Nil,
      aliases: List[AliasRow] = Nil
  ):
    def isEmpty: Boolean = entries.isEmpty && aliases.isEmpty

  /**
   * **退役计划（纯函数；轴 a × 轴 b 的联合判据）**
   *
   * 判据（机械）：`所属链已归档（或已离场）∧ refCount == 0`。
   *  - 轴 (a) 生命周期绑定：行**不得早于**所属链归档退役（`status == Archived` 或所属链
   *    已离场）—— 归档刻 = 最早退役窗口；同时退役 0 行是合法结论（存在活引用就不退，
   *    这正是「旧号永久可达」）。
   *  - 轴 (b) 引用计数：`refCount == 0` 才退；计数由 [[recomputeRefCounts]] 覆盖式复算。
   *  - 别名行随其 canonical 条目同刻退役；离场条目的别名行一并退 ⇒ **禁留悬空条目**。
   */
  def planRetire(st: State, now: Long): RetirePlan =
    val goneEntries = st.entries.filter { case (_, e) =>
      e.status == StatusArchived && e.refCount <= 0
    }.keySet
    val retiredAliases = st.aliases.filter { case (_, a) =>
      val ownerGone = !st.entries.contains(a.canonical) || goneEntries.contains(a.canonical)
      ownerGone && a.refCount <= 0
    }
    RetirePlan(
      state = st.copy(entries = st.entries -- goneEntries, aliases = st.aliases -- retiredAliases.keySet),
      entries = goneEntries.toList.sorted.flatMap(st.entries.get),
      aliases = retiredAliases.values.toList.sortBy(_.alias)
    )

  // ── 轴(c)：硬上限压缩 ────────────────────────────────

  /** 热读数（轴 c 触发面 = 行数两条 + 字节一条，字节由调用方量现读落盘长度）。 */
  def totals(st: State): Totals =
    Totals(
      entries = st.entries.size,
      aliases = st.aliases.size,
      members = st.entries.values.map(_.memberCount).sum,
      refCount = st.entries.values.map(_.refCount.toLong).sum + st.aliases.values.map(_.refCount.toLong).sum
    )

  def hotRows(st: State): Int = st.entries.size + st.aliases.size

  /** **双阈值触发判据**（条数 ∨ 字节；阈值在册见 [[MaxHotRows]] / [[MaxHotBytes]]）。 */
  def needsCompaction(st: State, bytes: Long): Boolean =
    hotRows(st) > MaxHotRows || bytes > MaxHotBytes

  final case class CompactPlan(
      state: State,
      /** 载荷搬迁的条目（热行保留身份，`members` 清空 + `compacted=true`） */
      compactedEntries: List[Entry] = Nil,
      /** 整行下沉的别名行（热面移除；冷档保留） */
      retiredAliases: List[AliasRow] = Nil,
      hotBefore: Totals = Totals(),
      hotAfter: Totals = Totals()
  ):
    def isEmpty: Boolean = compactedEntries.isEmpty && retiredAliases.isEmpty

  /**
   * **压缩轮计划（纯函数；轴 c）**
   *
   * 搬迁顺序（确定性「冷度」序 = 最不会再变的先下沉）：
   *   ① 已归档条目（`archivedAt` → `bornAt` → `chainId` 升序）；
   *   ② 其余条目（`bornAt` → `chainId` 升序）。
   * 逐条：条目**载荷**下沉（身份/状态/计数/成员读数留热 —— 压缩只搬字节，不改读数），
   * 其**别名行**（`compacted` 条目的历史名）整行下沉（旧号由
   * `ChainLedgerStore.resolveDeep` 永久可达）。单轮上限 [[CompactionBatchRows]] 行
   * （有界轮 ⇒ 可观测、可中断复跑）。
   *
   * 无可搬行（全库皆 `compacted` 且无别名行）⇒ 空计划：调用方**只记超限信号**，
   * 禁把「无事可搬」变成每拍空转。
   */
  def planCompaction(st: State, bytes: Long): CompactPlan =
    val ordered = st.entries.values.toList.sortBy(e =>
      (if e.status == StatusArchived then 0 else 1, e.archivedAt.getOrElse(e.bornAt), e.bornAt, e.chainId))
    val movableEntries = ordered.filterNot(_.compacted).take(CompactionBatchRows)
    val movableIds = movableEntries.map(_.chainId).toSet
    val movableAliases = st.aliases.values
      .filter(a => movableIds.contains(a.canonical))
      .toList.sortBy(_.alias)
      .take(math.max(0, CompactionBatchRows - movableEntries.size))
    val nextEntries = st.entries ++ movableEntries.map(e => e.chainId -> e.copy(members = Nil, compacted = true))
    val nextAliases = st.aliases -- movableAliases.map(_.alias)
    CompactPlan(
      state = st.copy(entries = nextEntries, aliases = nextAliases),
      compactedEntries = movableEntries,
      retiredAliases = movableAliases,
      hotBefore = totals(st),
      hotAfter = totals(st.copy(entries = nextEntries, aliases = nextAliases))
    )

  // ── 可复算校验（崩溃/重启后自洽）──────────────────────

  /** 轮守恒：`hotAfter` 必须由 `hotBefore` 与本轮搬迁量**机械推出**（禁事后改写读数）。
    *
    * 两支口径：[[RoundCompact]] 只搬**载荷**（条目行留在热面 ⇒ 条目读数不减；
    * 但与载荷同走的别名行整行移出 ⇒ 别名/引用读数照减）；[[RoundRetire]] /
    * [[RoundDissolve]] 整行移出 ⇒ 条目与别名读数同减。 */
  def roundConservation(f: RoundFile, m: RoundManifest): Either[String, Unit] =
    val droppedEntries = if f.kind == RoundCompact then 0 else f.entries.size
    // 载荷搬迁（compact）**不动条目读数**（身份/成员数/引用数留热）；整行移出（retire/dissolve）
    // 条目与别名读数同减。别名行在任何一支里都是整行移出（故其读数恒减）。
    val droppedMembers = if f.kind == RoundCompact then 0 else f.entries.map(_.memberCount).sum
    val droppedRef =
      (if f.kind == RoundCompact then 0L else f.entries.map(_.refCount.toLong).sum) +
        f.aliases.map(_.refCount.toLong).sum
    val expectAfter = f.hotBefore.copy(
      entries = f.hotBefore.entries - droppedEntries,
      aliases = f.hotBefore.aliases - f.aliases.size,
      members = f.hotBefore.members - droppedMembers,
      refCount = f.hotBefore.refCount - droppedRef)
    if m.kind != f.kind then
      Left(s"round ${m.round}: kind mismatch manifest=${m.kind} file=${f.kind}")
    else if m.hotBefore != f.hotBefore then
      Left(s"round ${m.round}: hotBefore mismatch manifest=${m.hotBefore} file=${f.hotBefore}")
    else if m.hotAfter != f.hotAfter then
      Left(s"round ${m.round}: hotAfter mismatch manifest=${m.hotAfter} file=${f.hotAfter}")
    else if f.hotAfter != expectAfter then
      Left(s"round ${m.round}: conservation broken expected=$expectAfter got=${f.hotAfter}")
    else if m.movedEntries != f.entries.size || m.movedAliases != f.aliases.size then
      Left(s"round ${m.round}: moved-count mismatch manifest=${m.movedEntries}/${m.movedAliases} " +
        s"file=${f.entries.size}/${f.aliases.size}")
    else if m.movedMembers != f.entries.map(_.members.size).sum then
      Left(s"round ${m.round}: moved-members mismatch manifest=${m.movedMembers} " +
        s"file=${f.entries.map(_.members.size).sum}")
    else Right(())

  /** 压缩镜像一一对应：`compacted=true` 的热行在冷档须有载荷副本；冷档载荷须有热行。 */
  def mirrorCheck(st: State, coldPayloadIds: Set[String]): Either[String, Unit] =
    val hot = st.entries.values.filter(_.compacted).map(_.chainId).toSet
    if hot != coldPayloadIds then
      Left(s"mirror mismatch hot=${hot.toList.sorted.mkString(",")} cold=${coldPayloadIds.toList.sorted.mkString(",")}")
    else Right(())

  /** 台账结构不变量：同 id 不得兼作条目与别名；别名不得悬空；锚点不得为空。 */
  def structureCheck(st: State): Either[String, Unit] =
    val dup = st.entries.keySet.intersect(st.aliases.keySet)
    if dup.nonEmpty then Left(s"ids present as both entry and alias: ${dup.toList.sorted.mkString(",")}")
    else
      st.entries.values.find(_.anchor.isEmpty) match
        case Some(e) => Left(s"entry ${e.chainId} has empty anchor")
        case None =>
          st.aliases.values.find(a => !st.entries.contains(a.canonical)) match
            case Some(a) => Left(s"alias ${a.alias} points to unknown chain ${a.canonical} (dangling)")
            case None    => Right(())

  /** **全量台账校验**（启动期 / verify 位用）：结构 + 每轮守恒 + 轮间链式 + 压缩镜像。 */
  def verifyLedger(
      st: State,
      files: List[RoundFile],
      coldPayloadIds: Set[String]
  ): Either[String, Unit] =
    val byRound = files.map(f => f.round -> f).toMap
    val ordered = st.rounds.sortBy(_.round)
    var err: Option[String] = None
    ordered.foreach { m =>
      if err.isEmpty then
        byRound.get(m.round) match
          case None    => err = Some(s"round ${m.round}: cold file missing (冷档是每轮的证据面)")
          case Some(f) => err = roundConservation(f, m).left.toOption
    }
    if err.isEmpty then
      ordered.sliding(2).foreach { w =>
        if err.isEmpty && w.size == 2 && w.head.hotAfter != w.last.hotBefore then
          err = Some(s"round chain broken: round ${w.head.round}.hotAfter=${w.head.hotAfter} " +
            s"!= round ${w.last.round}.hotBefore=${w.last.hotBefore}")
      }
    err.toLeft(()).flatMap(_ => structureCheck(st)).flatMap(_ => mirrorCheck(st, coldPayloadIds))
