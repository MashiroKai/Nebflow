# Nebula 记忆管理机制设计方案

- 日期: 2026-09-05
- 类型: 方案设计（纯纸面；本单不改任何记忆文件）
- 作者: memory-plan 分支审计节点（沙箱会话，Caller: project-dispatcher）
- 输入: router 日志取证（09-02~09-05 注入快照）+ 主仓机制代码走读 @533a2a0a + 前人文档（20260830_prompt-memory-audit / 20260831_memory-system-redesign）+ memory-consolidation skill
- 任务背景: 作者 09-05 11:29 指示——记忆「只增不删」缺遗忘/删除/审计机制，出方案保持记忆简练；**本方案待作者确认后实施**

---

## 0. 结论速览

| 问题 | 实测 | 机制答案 |
|---|---|---|
| 只增不删 | 观察窗内 MemoryEdit 25 次 = 14 append + 6 update + **0 remove + 0 replace_section**（§1.3） | 三级生命周期 + 「取代而非追加」写入纪律（§2/§3） |
| 无遗忘 | Dream 稳定节 3 天 27→101 条（~20 条/天），零过期 | T2 状态类 N=7 天、T3 精华 M=14 天+60 条 FIFO（§2.1） |
| 无审计 | 08-30 审计在案的逐字重复对 6 天未修；consolidation flow 存在但未执行（§1.4） | 周期审计任务（日志重建通道起步）+ 审计指标（§4） |
| 预算无执行 | User.md 84.4KB / memory.md 64.5KB，分别超 50KB/30KB 预算 65%/110%；代码零 enforcement（§1.5） | 写入侧 MemoryEdit 预算校验（第二批）+ 80% 阈值触发整理（§2.2/§5.2） |

预期效果（第一批清理后）：User.md 84.4→~49KB、memory.md 64.5→~18KB，双双回落预算内，且每级生命周期机制防止反弹。

---

## 1. 现状审计（量化，只读实测）

### 1.1 记忆块总量 vs 预算（最新注入快照）

来源：`~/.nebflow/logs/router/2026-09-05_full.jsonl` 最新 Nebula 请求（2026-09-05T03:40Z，request_id d43e1faa）的 `system_ref` 对象重建注入记忆段；字节为注入时点实测。**宿主权威回填命令见 §6.3 / report.md。**

| 项 | 实测 | 预算（现行裁定） | 超出 |
|---|---|---|---|
| 记忆块总计 | **149,088 B** | — | — |
| User.md（user 级） | **84,449 B** | 50KB（51,200 B） | **+64.9%** |
| agents/Nebula/memory.md | **64,500 B** | 30KB（30,720 B） | **+109.9%** |
| 全块条目数（"- " 行） | **610 条** | — | — |

### 1.2 分区分布（同上快照）

**User.md（84,449 B）：**

| 分区 | 字节 | 条目 | 生命周期分级（§2.1） |
|---|---|---|---|
| 产品决策 | 27,288 | 92 | T1 为主（含待核对降级项） |
| **旧时间戳 Dream 段 ×23**（08-14~08-30） | **30,606** | **144** | **T3 全部超期/待消化** |
| Dream Extract 稳定节（合并式，09-02 起累计） | 16,976 | 101 | T3（M 天未晋升即删） |
| Dream 历史精华（08-02~08-12，前人已消化一轮） | 794 | 16 | T3 尾巴 |
| Nebflow 设计偏好 | 5,505 | 32 | T1 |
| 工具环境 / 个人信息 / 工作风格 / 研究 / DAMPE / 碳化硅 / 其他 / 使用模式 | ~2,800 | ~30 | T1 |

**memory.md（64,500 B）：**

| 分区 | 字节 | 条目 | 分级 |
|---|---|---|---|
| **日期批次段 ×11**（08-15~09-03：晨间/午后/白天/夜间/运维/事故） | **~43,300** | **~105** | **T2 全部超期（最大两块：08-26 夜间 13,804 B/30 条、白天 9,878 B/24 条）** |
| Teams + Flows + Standalone + When-to-use（目录描述节 ×4） | 5,626 | 19 | 冗余（与注入条件块重复） |
| 记忆管理规则 | 1,015 | 4 | 3/4 过时（§1.4） |
| Delegate / 架构方向 / 模型层 / NebLink / VPS / SiPM / SwiftUI / 工具系统 / 监督体系 等永久节 | ~14,500 | ~60 | T1/T2 混合 |

### 1.3 增长曲线（日志重建）与写入行为

**逐日快照**（每天首个含记忆注入的 Nebula 请求，router objects 重建；User/Agent 拆分按头部 139 B 假设，±几十 B）：

| 时点（UTC） | 块总计 | User.md ≈ | memory.md ≈ | 来源 |
|---|---|---|---|---|
| 2026-08-30（基线） | 200,946 | 112,433 | 88,293 | 前人审计实测（权威文档值） |
| 09-02 02:08 | 131,275 | 68,777 | 62,359 | router 日志重建 |
| 09-03 00:08 | 135,115 | 72,617 | 62,359 | 同上 |
| 09-04 00:00 | 141,012 | 77,262 | 63,611 | 同上 |
| 09-05 00:05 | 145,443 | 80,804 | 64,500 | 同上 |
| 09-05 03:40 | 149,088 | 84,449 | 64,500 | 同上 |

- 08-30→09-02：**−69,671 B（−34.7%）**——08-31 记忆系统重构落地了 P0 清理（team 记忆移除 + User.md 瘦身），证明清理有效。
- 09-02→09-05（3.6 天）：**+17,813 B（+13.6%）**反弹。归因（章节级 diff）：
  - User.md Dream 稳定节 **+12,799 B（27→101 条，~20 条/天）**——主导增长源；
  - User.md 产品决策 +2,795 B（82→92 条）；
  - memory.md「09-03 深夜」新批次段 +1,441 B；
  - 期间另有少量手工修剪（Teams −167 B、Standalone −307 B）——**修剪速率 < 增长速率 1/60**。
- 按当前速率 User.md ~11 天翻倍；清理收益半衰期若无遗忘机制约 4 天。

**写入行为**（`~/.nebflow/logs/tools/*.jsonl` + 应用日志，可观测窗 09-04 02:19~09-05 11:40）：
MemoryEdit 全部 25 次可观测调用 = user/append ×14 + user/update ×6 + **remove ×0 + replace_section ×0**。删除动作在能力面上存在（MemoryEditTool.scala 完整实现）但**观察窗内零使用**。更早窗口应用日志无 MemoryEdit 留痕（tools JSONL 仅存 09-03 起 3 天），全史分布待宿主回填（§6.3 命令 C）。

### 1.4 过期条目识别清单（判据逐条）

判据沿用 memory-consolidation skill 四判据（stale / 冗余 / 错位 / 低价值），加时间维度（分级 TTL §2.1）。以下为**类别级认定 + 代表条目**（条目引用 ≤20 字符前缀，完整清单见 §6.1）：

**A. 日状态类堆积（T2，判据=低价值+TTL 超期/事件已闭环）**
- memory.md 日期批次段 11 节 ~105 条 ~43.3KB：事件批次的恢复点记录，批次收官后即失效。代表：「Nebflow / 2026-08-26 夜间批」「Nebflow / 2026-08-26 白天批」（合计 23.7KB/54 条，时点已 10 天）。
- User.md 稳定节状态类 ≥6 条：「重启包待 JVM 重启才生」「待重启窗口生效的变更清单」「09-04 夜间 main HEAD 推进」等——重启已发生（09-04 00:29 实例 77854），清单闭环。
- 佐证：批次段内条目多为 commit hash/HEAD 链/worktree 路径——git 可取回，违反 hard-to-obtain 准入。

**B. Dream Extract 历史精华区未消化（T3）**
- 旧时间戳段 23 节 144 条 30,606 B（占 User.md 36%）：08-31 重构前的追加式遗产。DreamMode.scala:53-55 注释明言「timestamped headers … are matched by P0 cleanup」——**P0 清理实际未执行**。抽样显示大量一次性状态（「Deck 第十三批意见已转发」「deck 现为 63 页候选终版」）与已被正文节收录的重复裁定。
- 稳定节 101 条中：目录描述副本 0 条（在 memory.md）、逐字重复 0 对，但含状态类 ≥6 条（见 A）与一次性任务细节（「pixil 像素画素材处理管线」「deck 封面可填写为：作者/」等——工具操作细节/项目状态，违反可复用或现状优先）。

**C. 被后续条目取代的旧条目残留（stale/冗余）**
- memory.md「查代码也 Delegate（08-28」：**逐字 identical 双胞胎**（各 173 B，同节内，前人审计 N1 在案 6 天未修——consolidation 未执行的直接证据）。
- 记忆管理规则节 3/4 过时：「记忆分层: User (~/.nebflow/NEBF」（路径错，应为 User.md）、「项目级配置位置: agents/tools/fl」（引用 `projectMemory` 参数——ContextRefresher.buildMemoryBlock（scala:331-353）实证只有 User+Agent 两级，无此参数）、「记忆维护四步循环（2026-08-1」（依赖 save turn，save turn 已于 08-31 裁定②删除——CompactionProfile.scala:20 Root=NebulaMemoryHook 唯一）。
- 「压缩方案 v3.1（2026-08-1」（两阶段模型描述，同上 stale）、「Nebflow / Rust 迁移（2026-08-1」（主线回 Scala，Rust 独立团队）、「Nebflow / 运维模式（2026-08-11 ~」（archive/scala 已删）、「bash 无自动超时：移除 autoBackgrou」（User.md 稳定节；与 08-25 「Bash 卡死防护方案（设计文档 c0883」300s 自动转后台裁定矛盾——同块内自相矛盾）。
- memory.md 目录描述节 ×4（Teams/Flows/Standalone/When-to-use，19 条 5,626 B）：与每轮注入的 teamCatalog/flowCatalog/skillCatalog 条件块**逐字重复**——记忆文件不该承载注入系统已承载的内容。

### 1.5 机制代码现状（方案落点依据）

| 机制 | 现状 | 位置 |
|---|---|---|
| 记忆注入 | User.md + memory.md 全文拼接，**无截断/无预算**；门控 Nebula-only（裁定①已落地） | ContextRefresher.scala:312-317（门控）、331-353（buildMemoryBlock） |
| 预算 50KB/30KB | **仅存在于提示词裁定，代码零 enforcement**（唯一截断=AGENTS.md 16KB） | ContextRefresher.scala:143-163 |
| MemoryEdit | append/update/remove/replace_section 四动作 + 单条目 guard + per-file 锁；**无预算校验** | MemoryEditTool.scala |
| Dream 写入 | 合并式写入稳定节（08-31 改造），exact-dedup 挡逐字重复；**无过期/晋升/消化机制**；压缩前触发（messages≥20） | DreamMode.scala:75-103、NebulaMemoryHook.scala |
| consolidation | flow 存在（scanner 只读→consolidator 执行）+ skill 方法论在，**基本未被执行**（N1 六天未修为证）；无定时接线 | flows/memory-consolidation/、skills/memory-consolidation/ |
| 生效时机 | systemStable 缓存——记忆改动下一生命周期节点（session 启动/压缩/重启）生效 | AgentCore（缓存链） |

---

## 2. 遗忘机制（分级生命周期）

### 2.1 条目三级（判定归条目自身内容，不归所在节）

| 级 | 定义 | 生命周期 | N/M 建议值与理由 |
|---|---|---|---|
| **T1 裁定类** | 用户裁定、产品决策、偏好、身份、环境事实（含「为什么」） | **永久**。唯一例外：被新裁定明确取代时**同轮删/改旧条目**（取代而非追加，§3.2） | 无 TTL。检查点：审计轮核对「推翻/取代/反转」语义链 |
| **T2 状态类** | 落地队列、当日批次、晨间清单、待重启窗口、在途批次、验收清单等事件状态 | **事件闭环即删**（重启已发生/批次已收官/清单已清）；兜底 TTL | **N=7 天**。理由：实测批次段从产生到失效通常 ≤3 天（3.6 天窗口已见「09-03 深夜」段闭环），7 天覆盖一个重启+发版周期并容忍周末间隔；再长必然重演 43KB 批次段堆积 |
| **T3 历史精华** | Dream Extract 稳定节条目（hook 自动抽取，未经作者/Nebula 显式晋升） | **晋升或删除**：M 天内被显式晋升为 T1（挪进正文永久节）则转 T1；逾期未晋升即删 | **M=14 天 + 稳定节条目上限 60 条（FIFO 淘汰最老）**。理由：双保险——14 天是「真正 hard-to-obtain 的裁定应在两周内被作者或 Nebula 显式确认」的合理等待期，逾期可证伪其价值；60 条上限对齐预算（按均值 ~170 B/条 ≈ 10KB，留缓冲），在 ~20 条/天流速下单靠 M 会滞留 280 条，FIFO 才能钉住上限 |

分级落点调整：memory.md 废除「日期批次段」形态——状态类条目统一进单一「当前状态」节滚动清零（新批次进，旧批次按 T2 出），教训在批次收官时**晋升**进「运维教训（永久）」节。

### 2.2 触发时机（三层互补）

1. **定时周期**：Schedule 周任务触发 memory-consolidation flow（先例：weekly-summary 每周日 22:00 外部驱动）。周期任务只产报告，执行按 §4 职权边界。
2. **生命周期节点**：
   - Nebula 压缩后（NebulaMemoryHook 已在场——追加一步「T2/T3 清扫」提示，不改 hook 逻辑，先提示后机制）；
   - 宿主重启后（重启使重启包/待重启清单类 T2 批量闭环——重启后首会话触发一轮审计）。
3. **大小阈值**：任一文件 > 预算 80%（User 40KB / memory 24KB）→ consolidation 从「周节奏」升级为**即时任务**（Nebula 当轮安排，不等周日）。80% 而非 100%：留出整理期间的写入余量，且实测证明从 80% 到 120% 只需数天。

---

## 3. 删除与归档机制

### 3.1 删除纪律

- **外科手术式删除**：一律走 MemoryEdit remove / replace_section（后者用于节级批量清理，防多次 remove 漏删）；replace_section 前 git 快照。
- **直接删，不做影子归档**：~/.nebflow 是 git 仓，删除即留史（git 可回溯）——影子归档文件=双份存储=新税，且归档副本会漂移失真。唯一例外：整节 >20 条的大消化，执行前 `git -C ~/.nebflow add <file> && git commit` 打快照点（宿主命令或 Nebula git 流程），给回溯一个语义锚。
- 拿不准按判据裁，倾向删（skill 纪律沿用）：stale 与冗余条目对每个未来会话都是税。

### 3.2 写入门槛（「三问」+ 取代规则）

写入前三问（沿用 08-11 用户要求的三质量标准，即 memory.md「记忆维护四步循环」条目中仍有效的部分）：
1. **hard-to-obtain**：能从一次 Read/Grep/git 恢复的不记（commit hash、HEAD 链、worktree 路径、代码事实）；
2. **reusable**：一次性任务细节不记（某批 deck 页数、某次 PR 状态）；
3. **current-state-first**：以现状为准——新状态进既有条目 **update**，不新开条目；新裁定推翻旧裁定时，append 与 remove **成对**执行（同一轮，不留「已被取代但还躺着」的旧条目）。

「取代而非追加」是 T1 永久级唯一的收缩路径，也是现状「裁定推翻链」条目越积越多的根治：审计抽查到的「官网 logo 全站统一 24px（推翻」「空白点击翻页裁定冲突裁决」都是推翻发生时旧条目未同轮清理的产物。

### 3.3 预算的执行方式（结合现行注入行为）

- 现行注入侧**无截断**（§1.5）——本方案**不建议**给注入加截断：截断=静默丢记忆（尾部条目无声失效），比超预算更危险且不可观测。预算纪律放在**写入侧**。
- 第一批（提示词规则，零代码）：写入门槛三问+取代规则+N/M 分级写入 Nebula 记忆管理规则节；append 前自查当前文件大小。
- 第二批（机制改动，MemoryEditTool）：append/update 落盘前校验新文件大小，>预算 → 结构化拒绝（错误消息附当前 top-3 最大节字节数+「先整理再写」指引）——把「预算」从裁定变成可执行约束。 DreamMode.updateMemory 同样加预算闸（超限则跳过合并并 WARN，防 hook 侧绕过）。

---

## 4. 审计机制

### 4.1 周期审计任务设计

- **触发**：Schedule 周任务（建议周日 21:30，错开 weekly-summary 22:00）+ §2.2 生命周期/阈值触发。
- **节点只读**：审计节点扫描记忆（日志重建通道，见 4.2）+ 核对 git 历史/代码现状验证条目真伪 → 产出**条目级审计报告**：每条 = 定位（节+前缀）+ 动作（keep/update/merge/delete）+ 理由（四判据+TTL 哪条）+ 建议新文本（update/merge 时）。
- **职权边界（红线）**：**审计节点不直接改 Nebula 记忆**——报告投递 Nebula，由 **Nebula 本人执行 MemoryEdit** 落实（Nebula 可整单执行、可逐条裁量）。理由：记忆是 Nebula 的自我模型，外部节点直写破坏职权单点；Nebula 执行时还能顺带执行「取代规则」的连带清理。注：现行 memory-consolidation flow 的 consolidator 节点会直写文件——第一批改造为 scanner-only 模式（consolidator 退役或改为仅产出可执行清单），与本法对齐。
- **审计报告格式**（沿用 skill 动作清单格式）：编号项 / 按文件分组 / SKIP 显式标注 / OBSERVATION 留下轮。

### 4.2 取证通道三案对比与取舍

审计节点同样是沙箱会话，同样结构性读不到 `~/.nebflow/User.md` 与 `agents/Nebula/memory.md`（凭据层红线 + e511a614 负向规则）。三案：

| 案 | 做法 | 优点 | 缺点 | 取舍 |
|---|---|---|---|---|
| **A. 日志重建** | 与本单相同：router objects system_ref → 注入记忆段（本单已全程验证：布局摸底→ref 定位→章节切分→条目统计，~10 分钟手工） | **今天就能跑，零机制改动**；注入时点精确（所见即所得）；顺带拿到增长曲线 | 依赖 router 日志保留策略（当前 objects 302MB/49k 文件，滚动清理会吃掉历史）；~/.nebflow git 权威字节需宿主命令补 | **✅ 第一批采用** |
| **B. FileSandbox 审计只读例外** | 沙箱策略为审计类节点开两记忆文件的只读白名单 | 最干净：直读磁盘真身、无日志依赖、可做字节级 diff | 机制改动（权限面扩大，需 spec+变异验红+权限审计）；记忆文件含敏感事实（凭据路径等），只读面也要最小化 | **✅ 第二批实施**（A 的长期替代——A 依赖日志留存，B 才是稳态） |
| **C. Nebula 注入快照** | Nebula 每次派审计任务时把两文件内容贴进 task prompt | 无任何机制改动 | 每次手动、易腐（快照≠实时）、占 task 体积、审计独立性丧失（Nebula 可无意识筛选） | ❌ 仅作 A/B 均不可用时的 fallback |

**取舍理由**：A 零风险起步、立即可用，但把审计寿命绑在日志保留策略上是隐患；B 是稳态正解但权限改动必须走完整机制批次（spec→变异验红→审计）；C 的易腐性与独立性缺陷是结构性的，只兜底。git 历史验证能力边界：主仓（代码引用类条目核对）沙箱内 `git -C <主仓>` 可查；~/.nebflow 仓历史属宿主命令（§6.3 B 组），审计报告标注哪些结论待宿主回填。

### 4.3 审计指标（报告固定头部）

| 指标 | 健康线 | 现状（09-05） |
|---|---|---|
| User.md 字节 / 预算 | ≤80% | 165%（红） |
| memory.md 字节 / 预算 | ≤80% | 210%（红） |
| 全块条目数 | User ≤200 / memory ≤120 | 610（红） |
| 逐字重复对 | 0 | ≥1（红） |
| 死条目（~~删除线~~/已被取代未删） | 0 | ≥3（红） |
| T2 超期条目（>N 天未闭环） | 0 | ~105（红） |
| T3 未晋升条目（>M 天） | 0 | 144+（红） |
| 删除/append 比（滚动 30 天） | ≥0.5 | 0（红） |
| 审计间隔达标率（实际间隔 ≤ 计划间隔 ×1.5） | 100% | 无记录 |

指标由审计报告自带（每轮重算），前四项可脚本化（第二批随 B 通道一起给一个只读统计脚本）。

---

## 5. 简练标准

### 5.1 条目格式

- 单行条目：`- <事实，含日期戳>（→<id> 详情在 ~/.nebflow/memory/<id>.md）`——沿用现行 progressive disclosure 机制（注入头自带读取指引，ContextRefresher.scala:348-352）。
- 长度纪律：单条 >500 B 强制拆分——正文留一行摘要，细节进 `→id` 详情文件。**详情文件落点建议：`~/.nebflow/memory/<id>.md`（既有机制目录，零新机制；建议修订待作者确认）**——备选 `~/.nebflow/docs/memory/`（文档区，可 git tracked 便于审计，但脱离注入头指引，需改条目格式约定）。
- Use when / Valid if：技术教训类条目保留该约定（可验证性），裁定/偏好类不强制。
- 章节：User.md 固定七节（个人信息 / 工作风格 / 产品决策 / 研究领域 / 设计偏好 / 工具环境 / Dream 待消化），memory.md 固定五节（记忆管理规则 / 路由规则 / 运维教训（永久） / 当前状态（T2 滚动） / Dream 待消化）；**禁目录描述节**（Teams/Flows 类与注入条件块重复，§1.4-C）。

### 5.2 章节组织与预算执行（汇总）

- 预算现行裁定 50KB/30KB **可修订**：建议维持硬顶不变，增加 80% 软触发（40KB/24KB，§2.2-3）与目标稳态（User ~45KB / memory ~20KB，第一批清理后自然落位）。若作者希望更紧，可降至 40KB/20KB（预算数字本身不是本方案的关键杠杆，写入侧强制才是——见 §3.3）。
- 执行批次：§3.3（第一批提示词规则 → 第二批 MemoryEdit/ DreamMode 预算闸 + 注入侧不加截断的裁定随之固化进工具 description）。

---

## 6. 实施批次

### 6.1 第一批：立即清理（**条目级清单，等作者确认后由 Nebula 执行**）

红线说明：以下引用只用条目前缀定位（MemoryEdit match 语义=条目内精确子串首个命中；执行时按前缀补全定位串）。全部动作按文件分组；`DELETE-节` = replace_section 置空（先晋升后删）；预期净效果 User.md ~49KB / memory.md ~18KB。

**文件 1：`~/.nebflow/agents/Nebula/memory.md`**

| # | 动作 | 定位（前缀） | 理由（判据） |
|---|---|---|---|
| M1 | DELETE ×1 | 「查代码也 Delegate（08-28」（「2026-08-26 夜间批次」节内两条逐字 identical，各 173 B，留一条） | 冗余；N1 六天未修 |
| M2 | DELETE-节 | 「Nebflow / 2026-08-26 夜间批」（13,804 B/30 条） | T2 超期 10 天；晋升：权限劫持案、循环熔断、查代码也 Delegate（若正文节未有）→「运维教训（永久）」 |
| M3 | DELETE-节 | 「Nebflow / 2026-08-26 白天批」（9,878 B/24 条） | 同 M2（flow 体系收官终态已由裁定条目承载） |
| M4 | DELETE-节 ×6 | 「Nebflow / 2026-08-24 运维与活跃」「Nebflow / 2026-08-25 晨间（重启」「Nebflow / 2026-08-25 午后（deck」「Nebflow / 2026-08-23 运维与活跃」「Nebflow / 2026-08-21 运维与活跃」「Nebflow / 2026-09-03 深夜（重启」（合计 ~14.9KB） | T2 超期/已闭环；晋升个别仍有效教训后删壳 |
| M5 | UPDATE-节 | 「Nebflow / 2026-08-18~19 事故与」「Nebflow / 并发管理与运行稳定」→ 并入新节「运维教训（永久）」，节名去日期化；保留 2 亿 token、宿主误杀、静默死亡三教训 | 错位修正（教训属 T1，日期批次形态致其无法被生命周期识别） |
| M6 | DELETE-节 | 「Nebflow / 2026-08-20 失能模式与」（1,812 B/3 条） | T2 超期；诊断方法论部分晋升后删 |
| M7 | DELETE-节 ×4 | 「When to use what」「Standalone Agents (Mail」「Teams」「Flows」（19 条/5,626 B） | 冗余：与每轮注入的 teamCatalog/flowCatalog/skillCatalog 逐字重复 |
| M8 | UPDATE | 「记忆分层: User (~/.nebflow/NEBF」→ 路径改为 `~/.nebflow/User.md` | stale（路径错误） |
| M9 | DELETE | 「项目级配置位置: agents/tools/fl」 | stale（projectMemory 参数不存在——ContextRefresher.scala:331-353 实证） |
| M10 | UPDATE | 「记忆维护四步循环（2026-08-1」→ 去 save turn 依赖，保留三问准入标准 | stale 部分（save turn 已删）+ 与 §3.2 对齐 |
| M11 | DELETE-节 | 「Nebflow / Rust 迁移（2026-08-1」（224 B） | stale（主线回 Scala，Rust 独立团队） |
| M12 | UPDATE | 「压缩方案 v3.1（2026-08-1」→ 压缩只压缩现状（save turn 已删） | stale（两阶段描述失效） |
| M13 | DELETE-节 | 「Nebflow / 运维模式（2026-08-11 ~」（353 B，含 archive/scala beta 规范） | stale（archive/scala 已删 08-25） |
| M14 | DELETE-节 | 「Nebflow / 2026-08-15 状态」「Nebflow / 三位一体（2026-08-1」「Nebflow / 更名与品牌参数」「Nebflow / UI-UX 质量体系」 | T2 超期/终态已由产品决策节承载（如「三位一体」若仍是现行战略→UPDATE 进架构方向节，作者裁） |
| M15 | 保留 | 记忆管理规则（修后）/ Scala-Pekko 坑 / Delegate / Pop / 模型层 / NebLink / 架构方向 / VPS / SiPM / SwiftUI / 工具系统 / 外部监督 / 工作风格 | T1 |

**文件 2：`~/.nebflow/User.md`**

| # | 动作 | 定位（前缀） | 理由（判据） |
|---|---|---|---|
| U1 | DELETE-节 ×21 | 旧时间戳段「## Dream Extract (2026-08-14」…「(2026-08-28」（08-14~08-28 全部，~28.8KB/133 条） | T3 超 M=14 天；逐节 5 分钟扫捕：未沉淀的永久事实（如「语音输入最终取向」若正文未有）晋升稳定节，其余删；git 快照先行 |
| U2 | DELETE-节 ×2 | 「(2026-08-29T13:45」「(2026-08-30T02:45」（~3.4KB/17 条） | 旧格式遗产：整节内容与稳定节合并去重后删壳（6 天内事实不丢失） |
| U3 | DELETE | 稳定节内「重启包待 JVM 重启才生」「Task 工具已实施合入 main（Neb」「09-04 夜间 main HEAD 推进至」「待重启窗口生效的变更清单（09-」「avatar24 默认头像分配已上线 sta」 | T2 闭环（09-04 重启已发生；HEAD 链 git 承载） |
| U4 | UPDATE | 稳定节「slideblocks 编辑错位 bug 根因」→ 修复落地后闭环；本轮 UPDATE 为一行现状或删 | T2 |
| U5 | DELETE | 稳定节「logo 缩小 30% 已被用户撤销」（若独立成条；「官网 logo 全站统一 24px（推翻」为现行保留） | 冗余（被取代旧口径） |
| U6 | UPDATE | 「bash 无自动超时：移除 autoBackgrou」→ 与 08-25 300s 自动转后台终态合并为一条 | stale+同块自相矛盾（与「Bash 卡死防护方案（设计文档 c0883」冲突） |
| U7 | DELETE-节 | 「Dream Extract 历史精华（2026-08-0」（794 B/16 条）→ 仍有效 3-5 条并入稳定节后删壳 | T3 尾巴 |
| U8 | 抽查组（本轮不删，标 TODO） | 产品决策节内「mashiro.staging.nebflow.space」「slideblocks PR #7 已开」「目标演示 deck 的正式标题已定」「deck 封面可填写为：作者/」 | 一次性项目状态（T2 降级候选），等 deck 交付/staging 终态后下一轮删；产品决策主体 92 条 T1 保留 |

**第一批验收点**：`wc -c` 两文件 ≤ 预算（≤50KB/30KB）；「查代码也 Delegate」唯一；memory.md 无日期批次节、无 Teams/Flows 目录节；User.md 无时间戳 Dream 段；`grep -c '^## Dream Extract (2026' User.md` = 0。全部动作前 git 快照、动作后 `git -C ~/.nebflow diff --stat` 复核字节变化与清单一致。

### 6.2 第二批：机制改动（逐项验收点）

| # | 改动 | 验收点 |
|---|---|---|
| 2.1 | Schedule 周审计任务（memory-consolidation flow，scanner-only 模式：报告→Nebula 执行） | 隔离实例冒烟：任务触发→报告落盘→无节点直写记忆；指标头部 9 项齐全 |
| 2.2 | MemoryEdit 预算闸（append/update 落盘前校验，>预算拒绝+整理指引错误消息） | spec：预算内通过/超限拒绝两路；变异验红（去掉校验→测试红）；DreamMode.updateMemory 同闸（超限 WARN+跳过） |
| 2.3 | FileSandbox 审计只读例外（审计节点白名单直读两记忆文件，只读） | 权限 spec+变异验红；写路径仍 DENIED；负向规则（agents/**/memory.md）对非审计节点不变 |
| 2.4 | DreamMode T3 淘汰（M=14 天+60 条 FIFO，合并写入时执行） | spec：过期条目被淘/FIFO 淘最老/晋升条目（正文节存在同事实）豁免；压缩回归全绿 |
| 2.5 | 重启后审计触发 + 压缩后清扫提示（提示词层） | 重启后首会话含审计触发记录；T2 闭环条目在下轮报告消失 |
| 2.6 | 提示词更新（Nebula 记忆管理规则节+skill 同步：三问准入/取代规则/分级/N/M 值） | 提示词、skill、本文档三方一致；旧「四步循环」表述清除 |

### 6.3 第三批：产品文档化（仅列不实施）

- 官网 nebflow.space「记忆管理」章节：两级记忆、生命周期分级、审计机制——素材与本方案共用（§1 数据+§2/§4 设计图）。
- 随发版更新 CODEBASE.md 记忆系统段（若第二批机制改动合入）。

### 6.4 宿主回填命令全集（沙箱不可得项，执行后将结果回填本节）

```bash
# A. 权威字节/条目数（对照 §1.1 日志重建值）
wc -c ~/.nebflow/User.md ~/.nebflow/agents/Nebula/memory.md
grep -c '^- ' ~/.nebflow/User.md ~/.nebflow/agents/Nebula/memory.md

# B. ~/.nebflow 仓增长曲线（权威版图 1.3；观察 hooks 是否自动 commit）
git -C ~/.nebflow log --format='%h %ad %s' --date=short -- User.md agents/Nebula/memory.md | head -40
git -C ~/.nebflow log --numstat --format='C %h %ad' --date=short -- User.md agents/Nebula/memory.md | grep -E '^C|User.md|memory.md' | head -80

# C. MemoryEdit 全窗口动作分布（扩展 §1.3 观察窗）
grep -h '"tool":"MemoryEdit"' ~/.nebflow/logs/tools/*.jsonl \
  | python3 -c "import sys,json,collections;c=collections.Counter();[c.update([(j.get('inputSummary') or 'MemoryEdit(?)').rstrip(')').split('(')[1] and (j['inputSummary'])]) for j in map(json.loads,sys.stdin)];print(c)"

# D. 日志保留策略（router objects 最早时点——决定 A 通道历史深度）
ls -lt ~/.nebflow/logs/router/objects/ | tail -3; du -sh ~/.nebflow/logs/router/objects/
```

---

## 7. 边界与未尽事项

- 本方案全部数字为日志重建近似值（注入时点口径），权威字节以 §6.4-A 宿主回填为准；两口径预计差 <1%（注入头 139 B 假设）。
- 第一批清单按 09-05 03:40 快照定位；执行时若条目已被移动/修改，按 skill 纪律 SKIP 并记录，不猜删。
- M14「三位一体」条目、U8 抽查组等低置信动作已显式标注「作者裁」——宁缺毋滥。
- 观察窗局限：MemoryEdit 动作分布仅 36h 可观测，全史「是否曾用过 remove」待 §6.4-C 回填（不影响本方案结论：即便历史上用过，现状增长率证明频度不足）。
