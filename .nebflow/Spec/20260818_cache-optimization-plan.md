# 缓存命中优化实施计划（2026-08-18）

- 日期：2026-08-18
- 作者：Backend（nebflow-project）
- 基准：`20260818_cache-miss-analysis.md`（任务③产物）+ Nebula 四点强化
- 状态：实施中（阶段文档，计划冻结后补充实施结果）
- 分支：feat/cache-opt（worktree /tmp/nb-cache-opt，基于 archive/scala @ 0e02a16e）

## 1. 目标

把"大上下文 × 长间隔"唤醒组合的 token 成本降到接近零：
- 唤醒请求（间隔 >30min）平均 input 68.6K，97% 全额计费
- 缓存 TTL ≈ 30min（实测：<5min 92.8% 命中 → 5-30min 53.3% → >30min 4.4%）
- 4 个大上下文 agent（Nebula/Frontend/Backend/Manager）单请求全量 180-250K

## 2. 分项盘点（难度/收益/负责/状态）

| # | 项 | 机制 | 难度 | 收益 | 负责 | 状态 |
|---|----|------|------|------|------|------|
| A | 首唤醒 LowCost | 闲置 >30min agent 唤醒 turn 路由 107 免费通道 | 低（规则）/中（自动代码） | 唤醒成本→0（用户痛点直接解决） | Nebula/Manager 规则；Backend 自动路由待批 | **待批**（设计见 §5.1） |
| C | 批量唤醒错峰 | 批量任务排队/串行，107 RPM=15 天然错峰 | 低（规则） | 避免 N×250K 突发 | Nebula/Manager + ConcurrencyGate（P0 已建） | **规则已具备**（P0 完成） |
| ① | 统一 system prompt 前缀 | 跨 agent 共享前缀；**结论：现状已分层共享，hoist 不推荐**（见 §4） | — | 现状共享 ~500 token（all）/~3K（teams） | — | **测量结论完成** |
| B | 基线上下文精简 | 4 大上下文 agent system 65K→50K | 中（提示词） | 全量基数 -23%，所有请求受益 | prompt-engineer | **待派** |
| D/N3a | 冷缓存历史收缩 | **FastMicroCompact TTL 2h→30min（对齐 provider TTL）+ 最低收益守卫 + 可配置** | 低 | 30min-2h 窗口唤醒 input 显著下降（当前该窗口完全不收缩） | Backend | **本次实施** |
| ③ | 会话历史按需加载 | 冷缓存时仅发最近 N 消息 + 摘要 | 中-高 | 唤醒 input -60-70% | Backend | **设计见 §5.2**（与 D 重叠，D 先行） |
| ② | 保活成本权衡结论 | **结论：E 保活不推荐，成本 ≈16× 唤醒**（见 §6） | — | — | Backend | **结论完成** |
| ④ | 简单任务 LowCost 路由 | 简单/低成本任务走 107 preset | 低（规则） | 日常 token 下降 | Nebula/Manager | **规则待布**（preset 机制已就绪） |

## 3. Backend 本次实施：D/N3a 冷缓存历史收缩

### 现状

`FastMicroCompact`（nebflow/core/compact/FastMicroCompact.scala）：
- **TTL 硬编码 2h** —— 与实测 provider 缓存 TTL（~30min）严重不匹配
- 仅在冷缓存（末条 assistant 消息距今 >2h）时触发，把旧 tool_result 内容替换为占位符，保留最近 5 条
- **30min-2h 窗口完全不触发**：缓存已冷（全量计费）但历史不收缩

### 改动（已实施，commit 见 §8）

1. `CacheTtlMs` 硬编码 2h → **可配置参数，默认 30min**（`FastMicroCompact(coldAfterMs: Long = 30 * 60 * 1000L)`）
2. **最低收益守卫**：收缩后尺寸 > 原尺寸的 60% 时不触发（避免为微小收益销毁旧工具结果，保护"继续分析"场景的可用信息）
3. 调用点 AgentCore.pipeLlmCall:298 走默认参数，零行为变化于热缓存路径

### 收益量化

- 今日 30min-2h 窗口 26 个请求、平均 68.6K —— 其中 tool_result 内容占比高（Read/Bash 输出），收缩后输入显著下降
- 与 A 组合：A 把成本归零、D 降低非 LowCost 路径（如 107 不可用回退）的全量基数
- 风险：旧 tool_result 变占位符 → agent 需重读（占位符明示）；最低收益守卫 + 保留最近 5 条缓解

### 测试（已实施，全部绿）

FastMicroCompactSpec（11 用例）+ PromptSectionsSpec（24 用例）+ compact 包全量（50 用例）：
- coldTs 从 3h 改 40min（30min TTL 下为冷）
- 新增：30min 边界（<30min 不触发 / >30min 触发）、自定义 TTL（10min 生效/30min 默认不触发）、最低收益守卫（大文本+小结果 → 不触发）、共享前缀 pinning（assembleSystemPrompt 前缀恒为首块）

## 4. ① 统一前缀：测量结论

### 现状测量（代码 + 文件实测）

| 层 | 文件 | 大小 | 共享范围 |
|----|------|------|---------|
| all | system-prefix-for-all.md | 1852B（dataRoot 覆盖）/ 5054B（JAR 默认） | 全部 agent |
| teams | system-prefix-for-teams.md | 11547B | team agent |
| flows | system-prefix-for-flows.md | 260B | flow agent |
| manager | manager-prefix.md | 4680B | Manager |

装配顺序（AgentCore.buildSystemPrompt）：`allPrefix + categoryPrefix + managerPrefix + agent system.md + 条件 sections（工具指南/voice/language/catalogs/memory/rules）`。

- **跨 agent 共享前缀 = allPrefix ≈ 500 token**（首个分叉点 = agent system.md）
- team agent 额外共享 teams 前缀 ≈ 3K token
- 条件 sections（env/工具指南/voice/language）**在 agent.md 之后** → 不参与跨 agent 共享

### 结论：hoist 方案不推荐，现状保持

把公共条件 sections 提升到 agent.md 之前可把跨 agent 共享前缀扩展到 ~10-20K，但：
1. **收益场景窄**：仅"同 provider 上多个 agent 密集批次唤醒"受益（~2%/唤醒）
2. **一次性全量失效**：所有 agent 的 system prompt 顺序改变 → 全部缓存冷启动一次
3. **分叉风险**：工具指南按 availableTools 条件包含，不同 agent 在第一个差异处即分叉——共享段收益不确定
4. 与 2026-08-12 缓存优化定案（"system prompt 只放稳定内容，保持精简"）方向冲突

**保持现状 + 增加回归钉死**：`systemPrefixForAll` 必须恒为 system prompt 首块（防止未来改动破坏共享前缀位置）。

## 5. 待决策项

### 5.1 A 自动冷启动路由（Backend 代码，需批准）

检测 agent 末次活动 >30min → 其唤醒 turn（第一请求）自动路由 LowCost（107/deepseek-v4-flash-ascend）。

- 实现点：AgentActor LlmComplete 记录 lastActiveAt（任务 1 的 usage 埋点已有时间戳，可直接复用 UsageRecordStore）；模型解析层检查 lastActive 间隔
- 约束：仅首请求路由（后续请求回正常链）；107 能力差异（vision=false/推理延迟/限流 20/60s）→ 仅对允许 LowCost 的 agent 生效（agent.json 可配 `allowLowCostColdStart`，默认 false，逐步放开）
- 风险：唤醒 turn 恰好需要 vision/强推理 → 走 107 质量下降。缓解：Nebula/Manager 调度层已有手动 LowCost 能力，自动路由仅作为兜底；先做手动规则（零代码）验证效果再决定自动

### 5.2 ③ 会话历史按需加载（Backend 代码，需设计批准）

冷缓存时仅发送"最近 N 消息 + 摘要"，替代全量历史。

- 与 D 的关系：D（tool_result 收缩）先行落地后测量收益，若仍不足再实施 ③
- 实现路径：请求组装处（pipeLlmCall 附近）增加冷缓存窗口：最近 30 条消息 + 压缩摘要（复用现有 CompactionSummary）
- 风险：LLM 失去窗口外上下文（需 agent 主动检索；Nebflow 无检索工具时窗口外信息不可达）——**与现有全量压缩机制重叠，需产品决策**

### 5.3 B 基线精简（prompt-engineer）与 ④ LowCost 路由（Nebula/Manager）

- B：4 大上下文 agent（Backend 65K/Manager 62K/Nebula 65K/Frontend 65K）逐 agent 瘦身，目标 50K（-23%）
- ④：任务分派时简单/低成本任务（文档撰写、数据查询、格式转换等）默认走 LowCost preset —— preset 机制已就绪，属调度规则

## 6. ② 保活成本权衡结论（E 不推荐，量化）

保活（keep-alive）维持 provider 缓存的成本测算（以大上下文 agent 250K input 为例）：

| 方案 | 机制 | 8h 空闲期成本 |
|------|------|--------------|
| 不做（现状） | 唤醒时全量 1 次 | **250K**（1 次全量） |
| 保活 5min 间隔 | 96 次请求维持缓存 | ≈ **3.9M**（96×(18K 有效+232K×0.1)） |
| 保活 30min 间隔 | 16 次请求，TTL 边界反复 | ≈ **4M**（16×250K，TTL 30min 每次全量） |

- 保活成本是唤醒的 **15-16 倍**——缓存省下的钱远不够覆盖保活本身的消耗
- 结论：**E 保活不推荐**（分析文档 §10 一致）；正确方向是 A（唤醒走免费通道）+ D（唤醒前收缩历史），把"唤醒成本"本身归零/压低，而不是维持缓存

## 7. 验收标准

1. FastMicroCompact 默认 TTL=30min，可配置，边界测试绿（<30min 不触发 / >30min 触发）✅
2. 最低收益守卫生效（小收益不触发，测试绿）✅
3. FastMicroCompactSpec 全绿 + compact 包全量 50/50 不回归 ✅
4. 前缀共享回归钉死（systemPrefixForAll 恒为首块，assembleSystemPrompt pinning 测试）✅
5. 保活结论与前缀结论已沉淀（本文档 §4/§6）✅
6. 待决策项（A 自动路由/③ 历史加载/B/④）已列明设计要点与责任人 ✅

## 8. 实施记录

- 2026-08-18：计划文档 + D/N3a 实施（FastMicroCompact TTL/守卫 + 前缀 pinning）→ commit 见 feat/cache-opt
