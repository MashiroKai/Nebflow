# 裁定④「TTL 分开」实施批 — 设计文档（2026-09-07）

> 任务：Flow Map 主图即时离场 + Flow Archive 分批落盘 + 前端归档链入口改读归档落盘内容。
> 作者口径原文：「Flowmap 上立即消失，就像目前的 Flowmap 显示一样，Flow Achieve 上 24h，
> 然后右上角的已归档任务链显示的是 Flow achieve 的内容」+「一批文件切分落盘，方便按需读取」。
> 开工依据：.nebflow/Spec/20260907_nodelist-flowmap-context-audit.md §3.4。

## 1. 现状盘点（代码锚点）

| 面 | 现状 | 锚点 |
|---|---|---|
| 活动区滞留 | 终态转换点写 `ttlExpireAt = now + 24h`；TtlTick(30s) 驱动 `sweepExpired`：终态且到期 → 移归档 | NodeEngine.scala:198/1375/1592/1614；FlowMapStore.scala:111-124；ProjectActor.scala:256 |
| 归档落盘 | 单一大文件 `flow-map-archive.json`（每次 mutateArchive 全量重写；宿主实测 316 节点/426KB） | FlowMapStore.scala:149-156 |
| 归档读取 | `findNode` 活动区优先、归档 Ref（内存全量水合）兜底；detail 工具 / REST result 端点同源 | FlowMapStore.scala:42-46；NodeTools.scala:1288；RestApiRoutes.scala:343 |
| 主图整链语义 | **纯前端派生**：clusterBatches（createdAt 升序、相邻间隔 >120s 分批、id=`chain-<首节点id>`）；整链全终态（completed/failed/cancelled，**blocked 不算**）→ 同帧 fm-exit 淡出 + 进归档面板；链未齐终态成员保留主图 | flowMapArchive.js:218-234/243-301；flowMapTab.js:1424-1441 |
| 归档面板数据源 | 快照节点 + nodeRemoved 墓碑派生——**重启/刷新后归档链全丢**（快照无归档节点、墓碑为空） | flowMapArchive.js:243-301/350-353 |
| 归档显示 TTL | 前端常量 ARCHIVE_TTL_MS=24h，按链 completedAt  purgeExpired | flowMapArchive.js:38/329-344 |

关键观察：**主图「整链全终态→同帧淡出」视觉行为前端今天已实现**（派生驱动，不依赖后端出库）。
本批后端改动对齐同一链语义，把「出库」从 24h 计时改为整链全终态即时；前端归档面板改读后端归档。

## 2. 设计决策

### D1 主图即时离场（引擎侧）：链级 sweep 取代计时 sweep

- `FlowMapStore.sweepExpired` → **`sweepCompletedChains(now)`**：活动区节点按批次聚簇
  （算法与前端 clusterBatches 严格同源：createdAt 升序、相邻间隔 >120s 开新批、
  批 id = `chain-<批内 createdAt 最早节点 id>`）；**批内全终态（completed/failed/cancelled）
  → 整批立即移归档**；任一非终态（含 blocked——待办语义 §1.4）→ 整批保留。
- 驱动点不变：ProjectActor.TtlTick 30s（零新调度器；崩溃/重启后下个 tick 自愈）。
  视觉「立即消失」由前端既有派生管线承担（终态事件当帧刷新派生 → 整链淡出），
  引擎出库滞后 ≤30s 不可见（nodeRemoved 到达时前端墓碑机制幂等）。
- 终态集合**不复用** `NodeLifecycle.Terminal`（含 blocked）；新增
  `FlowMapStore.ChainTerminalStatuses = {completed, failed, cancelled}`，与前端
  TERMINAL_STATUSES 逐字对齐。
- `ttlExpireAt` 字段保留照写（NodeEngine 零改动——交叠纪律）：语义收缩为
  「归档显示 TTL 剩余」的 payload 展示源（ttlLeftSec），不再驱动出库。
- 不在 4 处终态转换点挂即时触发：多挂点 = 与在飞支 n-b5672345 交叠面扩大；
  30s tick 单挂点 + 前端派生已覆盖视觉即时性。

### D2 归档分批落盘：一批一文件

- 新布局：`<workspace>/.nebflow/flow-map-archive/<batchId>.json`，内容
  `{project, batch, archivedAt, nodes}`——result 摘要+resultFile 指针、task 剥除
  （复用既有 slimNodeResults/stripNodeTasks 手术，写 surgery 零新增）。
- **内存归档 Ref 保持全量水合**（findNode / 投递链 / 重投扫描 / detail / REST result
  零改动——验收②③零风险）。「按需读取」的落点：写路径按批增量写（不再每次
  全量重写 426KB 单文件）+ open 按批读 + REST 按批组织响应。
  - 不选全懒加载（内存只留索引、命中才读批文件）：settle sweep / deps 校验每 30s
    经 findNode 触归档上游（NodeEngine:429/506/1275/1451/1521），懒加载需引入缓存
    +失效层，收益（省 ~2MB 内存）不抵复杂度与 barrier 回归风险。
- 内存新增批次索引 `batches: Ref[IO, Map[String, ArchiveBatchMeta]]`
  （meta = batchId + archivedAt + nodeIds），落盘写粒度判定的唯一来源。
- `mutateArchive` 改 diff 落盘：old/new 对比出变更节点 → 映射批次 → 只重写受影响
  批文件（out 改线/notifySentAt/nebulaDeliveredAt 三处单点更新各只触一个批文件）；
  变更节点无批次归属（防呆，不应发生）→ 整档重写兜底。
- **存量零丢失迁移**（hydrateAndMigrate 模式扩展）：open 时若单文件
  `flow-map-archive.json` 存在 → 解码 + 结果水合 → 聚簇分批 → 逐批落分文件
  （已存在的批文件不覆盖——幂等）→ 单文件改名 `flow-map-archive.json.split-bak`
  （不覆盖既有 .bak 链）。崩溃中段重入安全：分文件与单文件并存时单文件节点
  去重（分文件优先）后再迁移。

### D3 REST 聚合端点（前端面板数据源）

- `GET /api/projects/<name>/flow-map/archive` →
  `{batches:[{id, archivedAt, completedAt, members:[NodePayload 元数据]}], ttlMs, count}`。
- **选聚合不选按批拉取**，理由：① 面板渲染需要全量列表，按批拉取 = 先索引请求再
  N 个批次请求，24h 窗口规模（数十节点）下零收益多往返；② 与既有 flow-map 快照
  一次性拉取模式一致（前端缓存 + WS 增量 + 对账的同一形态）；③ 聚合在内存 Ref 上
  组装，成本 O(窗口内节点)。
- 服务端按「批 completedAt（成员最晚完成时间）距今 ≤24h」过滤——显示 TTL 单点
  在服务端把关，前端 purgeExpired 保留本地时钟兜底（双端同值 24h，
  后端复用 NodeEngine.TtlDisplayMs）。

### D4 前端：归档面板改读后端归档

- store 新增 `remoteChains: Map<chainId, Chain>` + `remoteMembers: Map<nodeId, ChainMember>`；
  拉取后经既有 `buildChain` 冻结成 Chain（链名三级推导/状态最坏优先全部复用）。
- **面板条目 = remoteChains ∪ 派生 chains（按 id 去重，remote 优先）**：派生链补
  「链刚齐、后端 sweep 在途（≤30s）」窗口；重启/刷新后 remote 是全量数据源。
  两侧批 id 同源（同一聚簇算法 + 同一 createdAt 数据）→ 去重可靠。
- **派生链机制整体保留不动**——它仍驱动主图淡出动画与可见性（isVisibleNode），
  这是「主图立即消失」视觉语义的在场承担者。
- openDetail / chainOfNode / hover 联动扩展查 remoteMembers（重启后归档成员详情可开，
  结果全文走既有 fetchNodeResult 按需通道——后端 findNode 归档兜底已在）。
- purgeExpired 同步清 remoteChains（成员 id 入 expiredIds 防派生复活，语义延展）。
- 拉取时机：悬浮层创建首拉（每项目一次）+ nodeRemoved 事件防抖（1.5s，sweep
  按成员逐条广播）+ 面板打开时刷新。零新增轮询（既有 30s TTL interval 只跑本地 purge）。
- 无新 UI 控件、无新 i18n key、无新样式——纯数据源切换（设计系统纪律：复用既有面板）。

## 3. 改动面与交叠控制

| 文件 | 改动 | 交叠风险 |
|---|---|---|
| FlowMapStore.scala | sweep 语义替换 + 分批落盘 + 迁移 + 批次索引 | 无（在飞支动 NodeEngine） |
| ProjectTypes.scala | +FlowMapArchiveBatch codec + ArchiveBatchMeta；FlowMapArchive/NodeDef/NodePayload **不动** | 零（只增不改） |
| ProjectActor.scala | TtlTick 一处调用改名 + 注释（:256 一带） | 极小（非 newTaskPrompt/reentryPrompt） |
| NodeEngine.scala | **零改动**（TtlDisplayMs :1923 一带不动） | 零 |
| RestApiRoutes.scala | +1 GET 端点 + 契约注释 | 无 |
| NodeTools.scala | 零改动 | 零 |
| flowMapArchive.js / nodeData.js | 数据源切换（D4） | 无 |
| 测试 | FlowMapStoreSpec 改写 sweep 用例 + 新增分批/迁移用例；FlowMapResultFilesSpec / FlowMapTaskFilesSpec 归档路径更新 | — |

## 4. 验收点对照

- 验收①（整链全终态后主图立即消失）：前端既有行为（派生同帧淡出）+ 引擎链级 sweep
  spec 断言（全终态批即时归档、链未齐保留、blocked 不算终态）+ 隔离实例 e2e
  （种子全终态链 → ≤30s+ 出批文件 + nodeRemoved）。
- 验收②（分文件落盘正确；detail/REST result 对归档节点仍 200）：spec 断言批文件
  布局/摘要/指针/剥 task + 迁移幂等零丢失；隔离实例 curl REST result 200。
- 验收③（in/deps 引用归档节点照常启动）：findNode 语义零改动 + 既有 barrier/deps
  spec 全绿回归 + 隔离实例种子「归档上游 + pending 下游」核对下游可启动。
- 硬闸：前端改动 Vision 截图留证（shot-archive-split-source.cjs，自包含打桩路线
  同 shot-node-detail-config.cjs）→ 作者点头前不申报 commit-ready。
