# R3 续批 —— 项目面 chain-ledger 账实修复 + 2.3GB 归档轮转治理 · 批报告

- 批次：R3 续批（接前批 `reports/20261029_flowmap-retirement-batch/REPORT.md` §5.1/§5.2 两个「独立批」遗留）
- 依据：审计报告二 §3.4-②③ ＋ 设计稿 §5.6/§8.3；前批 §5.2 根因定位（anchor 被归档区成员钉活 + refCount 只计不减）与 §5.1 四条建议
- 工作分支：`delegate/delegate-nebflow-880de30d`（独立 commit，未 push）
- 纪律：先证后改 / dry-run 先行 / 对账进报告 / 零在产触碰（盘面动作仅 chain-ledger-archive 的束化迁移，`chain-ledger.json` 未手工碰过一字节）

## 0. 结果速览

| 项 | 前 | 后 |
|---|---|---|
| Stand 台账 active 僵尸行 | **4**（图空、锚点在归档区、永不退役也不离场） | 机制修复后**下一拍自然清账**（全量 1,664 行走离场臂入批 → 一轮落档；热面归零 = 账实一致） |
| 冷档盘面 | **54,309 个 round 文件 / 2,418,105,559 B（2.3GB）** | **2 个按月束 / 313,499,962 B（299MB，7.7:1）**，54,309 轮逐行读回全数对上 |
| checkpoint | 20.1MB（54,309 条 manifest 内嵌） | 新代码首拍折叠后回落 **KB 级**（rounds 只带尾部 500 条窗口；完整史外置旁卷） |
| 逐拍落盘风暴 | retire 轮逐拍一文件（历史存量 53,962 个） | 结构断根（活性别闸）+ **攒批 flush**（行数/龄期双闸，事件驱动零定时器） |

## 1. ① 账实修复 —— 落点与机制

### 1.1 根因复核（本批复证过的实证）

`<ws>/.nebflow/chain-ledger.json`：entries=1,664（active=4 / archived=1,660），flow-map 活动区节点=0。四条 active 僵尸：

| 链号 | refCount | refCount 来源（本批拆解实测） |
|---|---|---|
| `chain-n-70916824` | 1 | **archive-batch 面**（`flow-map-archive/chain-n-70916824.json` 批文件在） |
| `chain-n-fb990b2f` | 4 | **externalRefs**（只计不减的外部面，历史计数） |
| `chain-n-527b1b92` | 1 | **archive-batch 面**（批文件在） |
| `zcode-484-fix` | 0 | ——（但 status 钉在 active：retire 判据要求 `Archived ∧ refCount≤0`，两支都不开） |

三重钉活的机械链（与前批 §5.2 一致，本批逐条实证）：
① 锚点躺在归档区 ⇒ `topologicalChains(活动∪归档)` 恒常派生该分量 ⇒ `observe` 承继臂每拍命中 ⇒ **离场臂永不打开**；
② refCount 被「归档批 / 只计不减外部面 / 归档区节点声明」钉住 ⇒ **退役窗（refCount≤0）基本不开**；
③ `zcode-484-fix` 连 Archived 都不是（sweep 绑定晚于行出生/漏接）⇒ 判据①不适用。

### 1.2 修复落点（文件:行）

| 判据 | 落点 | 语义 |
|---|---|---|
| **anchor 重估规则**（活性别闸） | `ChainLedger.observe`（ChainLedger.scala:797-824 新增 `isLive`/`liveComps` 段） | 闸开时（wired 路径恒传 `activeIds`）：**分量对条目存续的提名权只来自活动区成员**——归档区（终态）成员不再构成钉活理由。图空 ⇒ 一切分量非活 ⇒ 全部条目走**离场臂**（整行下沉冷档，「旧号永久可达」由冷档二级兜底：`resolveDeep`）。身份键（hitKeys）仍在全分量上匹配 ⇒「锚点已归档但链仍有活动成员」的活链照常承继，零误伤。`activeIds=None`（纯函数测试/旧路径）＝闸关，逐字旧行为 |
| **吸收目标防悬空** | `ChainLedger.observe` absorbedTargets 段（:908-921） | 顺带钉死既有隐患：吸收候选限制在「活 ∧ 本拍留热」分量（keptEntries），禁别名指向一个永不出生的分量号；无存活命中 ⇒ 孤儿别名下沉冷档（可达性不丢） |
| **refCount 只数活动区成员** | `FlowMapStore.reconcileChainLedger`（FlowMapStore.scala:249-260） | 声明面从 `combined`（活动∪归档）收窄为 `s.nodes`（活动区）——归档区节点的声明不再计入。`live-member` 面本就活动区口径；archive-batch / externalRefs 两面按在册语义保留（不属「成员」） |
| **spec 防复发** | `ChainLedgerSpec` T18（Stand 僵尸形态逐字复刻）+ T16/T17 重钉 + T11/T15/T13 更新 | T18 = 本批的承重断言：compacted 行 + 归档区锚点 + 外部面计数 + 空图 ⇒ 4 行全部离场入批、**跨重启不丢**、龄期整批落轮、冷号可达、后续拍零复活零新轮 |

**存量消化（不自作手术）**：热面清理不手工改台账——新代码部署后第一次 reconcile 自动把 1,664 行全部送入待落批（≥512 行 ⇒ 同拍整批落轮），`chain-ledger.json` 随后由引擎自己的折叠收口。热面归零＝「图没了账赖着」的项目面形态终结。

### 1.3 语义位移的如实声明

- 退役臂（`Archived ∧ refCount≤0`）在 wired 路径**结构性让位**给离场臂：活分量匹配的行必有 ≥1 活动成员 ⇒ refCount≥1 ⇒ 永不退役；图空行直接离场（**与引用数无关**——这正是本批的修复点）。退役臂保留为纯函数面与闸关路径的判据（T5/T6 照旧全绿）。
- 有引用的图空行（如 fb990b2f，refCount=4）**会**下沉冷档：热面 O(1) 解析降为 `resolveDeep` 冷档回翻（可达性保持、层级变深）。`knownIds`（正文引用计数面）随之收窄——已下沉号的后续正文引用不再计数（其 refCount 冻结在下沉刻）。这是「归档区不再钉活」的直接推论，Mail 校验路径的双级设计（`resolveMailChainId` → `resolveDeep` 兜底）在册支持该形态。
- T16/T17/D8/R4 四处既有断言按新语义重钉（sinking 仍「恰一次 + 零风暴」，臂名 retire→dissolve、落盘时机改批化）。

## 2. ② 归档轮转治理 —— 四件全落

### 2.1 (a) retire/离场轮批量化落盘（ChainLedgerStore.reconcile 重构）

- **攒批 flush 语义**：本拍离场行（observe.dissolved + 孤儿别名）与退役行（planRetire）先入 **State 内的持久待落批**（`pendingDissolve` / `pendingRetire` 两批分立——一轮一 kind，审计标签不混）；批达 `OfflineFlushRows=512` 行 ∨ 龄期 `OfflineFlushAgeMs=10min` ⇒ **整批落一轮**（不按行截断，批=守恒原子单位）。判据只在既有 30s 拍点求值——**事件驱动，零定时器**。
- **崩溃安全不断链**：批本体持久在台账热态（随 checkpoint/journal 落盘）⇒「先冷档后热账」的次序不变式改写为「行恒在某个持久面」（批内 or 冷档）；崩在落轮前 ⇒ 重启后批随台账回来继续攒/落（T18 断言跨重启）。`resolveDeep` 增加待落批回翻，批化窗口内旧号照旧可达。
- **坐标口径**：入批**读数中性**（`totals`/`hotRows` 计入待落批）⇒ 轮守恒与拍内链式（先例 D1-a）在「入批不落轮、落轮整批减」下结构性成立（T13 重钉：dissolve 批 flush + compact 同拍两轮，`hotAfter==hotBefore` 逐项相等）。
- 效果：54k 轮逐拍风暴的根（rebirth 环）早已由出生闸断掉（10-18 后零新轮）；本批再封「离场风暴」路径——每行至多滞留一个龄期，轻载 ≤144 轮/天，重载按 512 行/轮收敛。

### 2.2 (b) manifest 外置 + checkpoint 减重（`CheckpointRoundsWindow=500`）

- 完整 manifest 史的权威面 = 冷档旁卷 `chain-ledger-rounds.jsonl`（append-only 一行一条，写轮同刻**先于**热账落盘）；checkpoint/journal 只携带**尾部 500 条窗口** + `roundsTotal` 水位（journal 增量下界）。载入 = checkpoint ∪ journal 增量 ∪ 旁卷按轮号合并（`mergeRounds` 去重，T20 断言 505 轮 → checkpoint 带 505−x 条、旁卷 505 条、重启还原全量 + verify Right）。
- **升级安全**：open 期把「内存态有、旁卷没有」的 manifest 先回填旁卷（append-only + fsync）；回填未成 ⇒ 折叠**禁用**窗口视图（退回全量 encode，`sideVolumeComplete` 闸）——历史 manifest 无旁卷副本时截断 checkpoint＝丢史，禁。旧格式文件（无 roundsTotal）缺省回落 `rounds.size`（两值在旧格式恒相等）。
- `hotBytes` / 轴(c) 字节阈值改量 **checkpoint 实写视角**——前批记录的「`bytes > MaxHotBytes` 恒真」病灶（rounds 使 encode 恒超 1MB）就此消失，1MB 阈值恢复「热行字节」本义（复查结论：**维持 1MB 不抬**；每轮开销已由 gzip + 批化 + 窗口收口，阈值若再松动只会延迟压轮、不减盘面）。

### 2.3 (c) 冷档 gzip 压缩 + 透明读

- 新轮落盘即 `round-<n>.json.gz`（gzip tmp + fsync + ATOMIC_MOVE + 目录 fsync，与 `writeSyncDurable` 同款纪律）；digest 口径 = **未压缩 JSON 文本**的 sha-256（跨形态恒可比）。
- 读面四形态全收：`round-<n>.json`（旧）/ `.json.gz`（新单轮）/ `.jsonl` / `.jsonl.gz`（迁移束）——`readRounds`（manifest 驱动的守恒审计）与 `resolveDeep`（冷号回翻）共用。JSONL 束**逐行流式解压**（内存以单行为界，数万轮的束禁整读）；束内坏行跳行 + WARN，文件级损坏跳档 + WARN。
- spec：T21（手搓 `rounds-202609.jsonl.gz` 束 + 单轮 gz 混挂 ⇒ readRounds 全收 + resolveDeep 穿束命中 + 野束文件不进守恒审计面）。

### 2.4 (d) 存量一次性压缩迁移 —— 前后对账（已执行）

工具：`chain-ledger-archive-migrate.py`（dry-run / apply / verify 三态；apply 必须携带 dry-run manifest 且逐文件复核字节数）。

| 指标 | 执行前（20261030_010115 dry-run） | 执行后（20261030_010413 apply） |
|---|---|---|
| 文件数 | **54,309**（round-1 … round-54309） | **2**（按月束）+ 0 散件 |
| 字节 | **2,418,105,559 B** | **313,499,962 B**（**7.7:1**） |
| 轮 kind | retire=53,962 / compact=341 / dissolve=6 | 同左（逐轮读回核对一致） |
| 轮内容量 | —— | 54,309 轮全部读回：entries 合计 10,736,260 行 + aliases 16 行，逐行解析零损坏 |
| 删除 | —— | 54,309 原文件（**读回强校验全通过后**才删；束=tmp+fsync→读回→原子改名→删原→目录 fsync） |

- 束形态：`rounds-202609.jsonl.gz`（31,002 轮，177.8MB）+ `rounds-202610.jsonl.gz`（23,307 轮，135.7MB）；一行 = 原文件字节逐字（`hotBefore`/`hotAfter` 等守恒字段原样保留 ⇒ 引擎 `roundConservation` 对束照常可算）。
- **执行窗口声明（读面降级的临时态）**：在产实例此刻仍跑旧代码，其 `readRounds`/`resolveDeep` 只认 `.json` ⇒ **重启前**冷档读（旧号深解析 / 显式 verify）会落空，热面与一切写路径零影响。引擎重启加载本批代码后四形态透明读生效，窗口即闭。在产实测：最后一只 round 文件落盘于 10-18（12 天零新轮），迁移窗口内无写竞态。
- 回滚面：束内即原文件字节逐字（一行一文件），可逆打包未做工具化（如需逆向拆束可按行还原）。

## 3. 验证读数（全绿）

| 套件 | 读数 |
|---|---|
| `ChainLedgerSpec` + `ChainLedgerJournalSpec`（含新增 T18-T21、重钉 T11/T13/T15/T16/T17） | **29/29** |
| 邻接十二套（`DualModeDispatcherScopeSpec` D8-D11 / `DelegateFlowMapLifecycleSpec` / `DelegateTerminalSpec` / `DelegateFlowHostsSpec` / `ChainsReadFaceSpec` / `FlowMapScopeStoreSpec` / `MailChainBoundarySpec` / `ChainViewEngineSpec` / `ReferenceFacesWiringSpec` / `DeferredCancelLedgerSpec` / `ChainWriteToolSpec` / `ChainArchivedEventSpec`） | **86/86**（D8/R4 两处按新语义重钉后复跑绿） |
| `nebflow.core.delegate.*` 全部 + `ChainViewWsEntrySpec` | **159/159** |
| 九套扫尾（NodeReport/TaskBoard/MailTool 等） | **63/63** |
| `NodeMessageSpec` S2-RUNNING | 单跑 13/13 绿（合跑时一次超时为既有并发噪声，与本批无涉） |
| **提交态复跑**（本分支 d8bf012b4 上：台账两套 + 邻接九套 + `nebflow.core.delegate.*` 全量 + `ChainViewWsEntrySpec`） | **237/237** |

## 4. 边界与不做的事

- `chain-ledger.json` / `.journal` **零手工触碰**（热面清理交引擎首拍；迁移只入 `chain-ledger-archive/`）。
- 在产实例的 round 写路径零触碰（12 天零新轮实测；迁移静默窗口的读面降级已声明，重启即闭）。
- `MaxHotRows`/`CompactionBatchRows`/`MaxHotBytes` 阈值不动（复查结论见 §2.2）。
- `pausedChains`/`cancelledChains` 控制表不随离场清理（有界、独立语义，非本批病灶）。

## 5. 交付物

| 文件 | 说明 |
|---|---|
| `src/main/scala/nebflow/core/project/ChainLedger.scala` | 活性别闸（anchor 重估规则）+ 吸收防悬空 + 待落批模型/双闸常数 + `checkpointView` |
| `src/main/scala/nebflow/core/project/ChainLedgerStore.scala` | reconcile 批化重构 + gzip 冷档写 + 四形态透明读（JSONL 流式）+ manifest 旁卷 + 窗口折叠 + `resolveDeep` 待落批回翻 |
| `src/main/scala/nebflow/core/project/FlowMapStore.scala` | 声明面收窄活动区（refCount 只数活动区成员） |
| `src/main/scala/nebflow/core/AtomicJson.scala` | `forceParentDir` 放宽 `private[core]`（冷档 gzip 写/旁卷追加共用目录 fsync 纪律） |
| `src/test/scala/nebflow/core/project/ChainLedgerSpec.scala` | T18-T21 新增 + T11/T13/T15/T16/T17 重钉 |
| `src/test/.../ReferenceFacesWiringSpec.scala` / `DualModeDispatcherScopeSpec.scala` | R4/D8 批化语义对齐 |
| `reports/20261030_chain-ledger-hygiene/chain-ledger-archive-migrate.py` | 存量迁移工具（dry-run/apply/verify 三态，可重跑） |
| `reports/20261030_chain-ledger-hygiene/20261030_010115_archive-migrate-dry-run.json` | dry-run manifest（54,309 文件逐条记录） |
| `reports/20261030_chain-ledger-hygiene/20261030_010413_archive-migrate-apply-result.json` | 执行对账（前后读数 + 束清单） |
