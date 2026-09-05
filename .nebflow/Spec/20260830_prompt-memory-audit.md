# Nebflow 提示词注入体系与记忆规模审查报告

- 日期: 2026-08-30
- 类型: 纯只读分析（未修改任何 agent 定义 / 记忆文件 / 源码）
- 范围: system prompt 注入体系（ContextRefresher / PromptSections / AgentCore）+ 记忆规模 + 冗余质量 + 上下文占比
- 方法: 源码走读（`/Users/dev/Claude code/Nebflow`）+ 磁盘文件实测（`~/.nebflow`）+ 注入逻辑重建（会话文件不持久化 system prompt，仅存 user/assistant 消息，故按注入代码确定性重建）

---

## 1. 注入体系清单

### 1.1 组装链路（源码位置）

```
AgentCore.refreshTurn → ContextRefresher.refreshTurn（每 turn 收集来源）
  → buildSystemPrompt(agentDef, systemPrefix, PromptContext)   [AgentCore.scala:1752]
      = PromptSections.assembleSystemPrompt(prefix, agentPrompt, conditionalBlocks)
  → PromptSections.buildConditionalBlocks(ctx)                  [PromptSections.scala:674]
      = 注册表 all 按 order 排序过滤渲染
```

systemStable 缓存机制（AgentCore.scala:565-578）：system prompt **只在生命周期节点重建**（session 启动 / compaction / restart），turn 之间 byte-for-byte 复用缓存串（provider prefix cache 命中）。记忆块随 systemStable 一起缓存——**memory.md 的改动要到下一个生命周期节点才生效**。

### 1.2 各层注入清单

| 层 | 文件 | 何时注入 | 注入对象 | 大小 |
|---|---|---|---|---|
| ① 全 agent 前缀 | `prompts/system-prefix-for-all.md` | 每 turn（进 systemStable） | 全部 agent | 4,530 B |
| ② team 前缀 | `prompts/system-prefix-for-teams.md` | 每 turn | category=team agent | 13,278 B |
| ③ flow 前缀 | `prompts/system-prefix-for-flows.md` | 每 turn | category=flow agent | 260 B |
| ④ Manager 前缀 | `prompts/manager-prefix.md` | 每 turn | name=="Manager" | 5,725 B |
| ⑤ agent 主体 | `agents/<name>/system.md`（teams/flows 同理） | 每 turn | 对应 agent | 2–24 KB |
| ⑥ agent.json | tools / flows / skills / description | 每 turn（白名单热加载） | 对应 agent | — |
| ⑦ 条件块 400-415 | askUser / readLive / visualReporting 内置段 | 有对应工具时 | 全 agent | 1.7+1.2+2.3 KB |
| ⑧ 条件块 500 | voice 段 | voiceEnabled | 全 agent | 2.0 KB |
| ⑨ 条件块 600-620 | Devices / 活跃 sessions / Language | 运行时状态 | 全 agent | ~0.3 KB |
| ⑩ 条件块 630 | tasksGuide | category=team | team 成员 | 0.9 KB |
| ⑪ 条件块 800 | skillCatalog（name+description+when，每 skill ≤200 字符） | 声明了 skills 的 agent | Nebula=`*` 全量 | 5.1 KB |
| ⑫ 条件块 808 | flowCatalog（name+description） | 声明了 flows 的 agent | Nebula=`*` 全量 | 2.2 KB |
| ⑬ 条件块 816 | teamCatalog（全局 or 本 team + rules） | 非 worker | Nebula=全局 / team agent=本 team | 6.2 KB |
| ⑭ **记忆块 810** | **User.md + agent memory.md 全文** | **Nebula + team agent 每 turn（重建于生命周期节点）** | **见 §2** | **200.9 KB（Nebula）** |
| ⑮ 条件块 900 | rulesMd（项目 NEBFLOW.md + folder rules 链） | folderId 命中时 | 有 folder 的 agent | 0–12 KB |
| ⑯ 条件块 999 | SubTask worker 身份块 | isSubTaskWorker | worker | 10 KB |
| ⑰ Tools | LLM 请求 tools 参数（描述并入 input token） | 每请求 | 按工具白名单 | ~22 KB（估） |

### 1.3 记忆注入门控（ContextRefresher.shouldInjectMemory）

```scala
!headless && !isWorker && (isTeamAgent || agentName == "Nebula")
```

- **Nebula + 全部 team agent（72 个）**：User.md + 各自 memory.md **全文注入**（buildMemoryBlock 无任何截断/选择逻辑，见 MemoryStore.scala / ContextRefresher.scala:272-294）
- **standalone agent（Explorer/Coder/design-engineer 等）**：无记忆注入
- **SubTask worker**：无记忆注入（prompt 是唯一上下文）
- **headless 基准模式**：全跳过（确定性）

### 1.4 progressive disclosure 现状

- 机制存在：memory.md 条目带 `→{id}` 时，详情在 `~/.nebflow/memory/{id}.md`，提示词头部注明"场景匹配时读取"
- **但仅 Nebula 的 25 个条目用了 →id；team 成员记忆 0 个条目用 →id**（全部内容内联注入）
- 跨全部记忆共 161 个 →ref，其中 **27 个指向不存在的文件（broken）**；`memory/` 下 **86 个详情文件无任何记忆引用（孤儿）**，占 218 个文件中的 39%

---

## 2. 记忆规模统计（量化）

### 2.1 核心文件

| 文件 | 字节 | 行数 | 说明 |
|---|---|---|---|
| `~/.nebflow/User.md` | **112,433** | 406 | 全 agent 共享，注入 73 个 agent（Nebula+72 team 成员） |
| `~/.nebflow/agents/Nebula/memory.md` | **88,293** | 273 | Nebula 自己 |
| `~/.nebflow/memory/` 详情目录 | **254,917** | 218 文件 | 其中 86 个孤儿 + 27 个 broken ref |
| 全部 memory.md 合计（agents+teams） | **998,254** | — | ≈ 975 KB |

### 2.2 standalone agent memory.md（不注入，仅存档）

| agent | 字节 | 行数 |
|---|---|---|
| Nebula | 88,293 | 273 |
| Manager（全局） | 35,891 | 341 |
| Backend（全局） | 2,918 | 50 |
| Frontend（全局） | 3,173 | 41 |
| Coder | 2,021 | 21 |
| Explorer | 2,158 | 12 |
| design-engineer | 1,772 | 13 |

### 2.3 team 成员 memory.md（每 turn 全量注入！）

| team/agent | 字节 | 行数 | +User.md 后记忆块 |
|---|---|---|---|
| nebflow-project/Backend | **195,355** | 297 | **307,788** |
| nebflow-project/Frontend | **188,875** | 476 | **301,308** |
| nebflow-project/Manager | 62,301 | 295 | 174,734 |
| nebflow-project/qa-frontend | 56,427 | 150 | 168,860 |
| nebflow-project/qa-backend | 30,374 | 70 | 142,807 |
| nebflow-project/prompt-engineer | 19,962 | 111 | 132,395 |
| html-deck-studio/html-builder | 66,990 | 260 | 179,423 |
| html-deck-studio/visual-reviewer | 12,624 | 50 | 125,057 |
| html-deck-studio/content-planner | 10,065 | 54 | 122,498 |
| html-deck-studio/Manager | 11,426 | 75 | 123,859 |
| slideblocks/Frontend | 26,147 | 159 | 138,580 |
| slideblocks/Manager | 8,432 | 49 | 120,865 |
| ReminderIsland/swift-dev | 23,566 | 66 | 135,999 |
| ReminderIsland/Manager | 10,786 | 57 | 123,219 |
| nebflow-website/Frontend | 19,463 | 38 | 131,896 |
| sipm-paper/Manager | 13,338 | 52 | 125,771 |
| czt-project/Manager | 11,790 | 90 | 124,223 |
| …（其余 team 成员均 < 10 KB） | | | |

flows/ 下各 agent **无 memory.md**（flow 节点一次性执行，不注入记忆）。

### 2.4 前缀 / system.md 规模

| 文件 | 字节 |
|---|---|
| system-prefix-for-all.md | 4,530 |
| system-prefix-for-teams.md | 13,278 |
| system-prefix-for-flows.md | 260 |
| manager-prefix.md | 5,725 |
| agents/Nebula/system.md | 13,561 |
| agents/Explorer/system.md | 23,700 |
| agents/design-engineer/system.md | 5,206 |
| team system.md（51 个） | 2,023–8,057 |

### 2.5 Nebula system prompt 总量（按注入逻辑重建）

| 组件 | 字节 |
|---|---|
| system-prefix-for-all | 4,530 |
| Nebula system.md | 13,561 |
| askUser + readLive + visualReporting | 5,209 |
| voice + Devices + Language | 2,287 |
| skillCatalog（`*`→16 skills） | 5,136 |
| flowCatalog（`*`→9 flows） | 2,248 |
| teamCatalog（全局） | 6,193 |
| **记忆块（User 112,433 + Agent 88,293 + 头）** | **200,946** |
| 文本小计 | **240,110 B** |
| Tools（≈16 工具描述，估） | ~22,000 B |
| **合计** | **≈262,000 B（≈256 KB）** |

Token 估算（中英混合 ≈ 3 B/token）：文本 ≈ 80K，记忆块 ≈ 67K，工具 ≈ 7K，**总计 ≈ 87K token/请求**。

---

## 3. 冗余与质量审查（冗余清单）

### 3.1 User.md 内部矛盾 / 过时（11 条）

| # | 位置 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| U1 | L149 `官网认证统一（2026-08-14）纯 GitHub OAuth，邮箱/密码登录全删` | 与 L27/L37 **直接矛盾**：Logto 定案（08-18）邮箱+密码为主体、GitHub 降级为绑定，08-27 全链落地 | L27 `登录体系 Logto 定案` / L37 `登录体系 2026-08-27 全链落地` | 删 L149 或标注"已废止（08-18 Logto 定案取代）" |
| U2 | L150 `Mail 三模式协议（08-14）：ask/queue/immediate` | 与 L56 **矛盾**：08-27 ask 模式整体移除 | L56 `Mail ask 模式移除（2026-08-27 18:51 用户裁定）` | 删 L150 的 ask 部分，保留 queue/immediate 历史注记或直接归档 |
| U3 | L30 待办区五状态（needs_confirmation / 用户确认 / 打回） | 08-30 任务工具重做已改为四态机（agent 直接标 completed），确认环节删除 | L94 `Team Manager 任务工具…四态机`；Nebula memory L69 已标注"已被任务工具重做取代" | L30 压缩为一句"人类待办=Apple 提醒式圆圈确认；agent 任务四态（见 L94）" |
| U4 | L53 `Onboarding 重设计（08-18）…规格书已落盘` | 与 L21 **矛盾**：08-29 Onboarding 封存不启用 | L21 `Onboarding 与 /slash 封存（2026-08-29 22:56）` | 删 L53 或改标注"已封存（08-29），规格书作废" |
| U5 | L47 `entity-creator 自动挂载期望…任务 B 实施中` | "实施中"状态过时；entity-creator 已是成熟 flow | 实体系统已 v2.1（agent/team/flow 三分支） | 删除或改写为终态 |
| U6 | L93 `flow 体系重设计…重新出方案中（Explorer 分析中）` | 08-26 00:09 已定案"互补非取代" | Nebula memory L254/L268/L273 三处互补修正 | 更新为终态（互补 + 8 留 1 转） |
| U7 | L140 `当前只做 Scala 版（archive/scala）` | archive/scala 已删（08-25 main 保留裁定），rust 线已恢复 nebflow-rust team | L33 分支治理裁定；nebflow-rust team 存在且活跃 | 删除或更新为"主线=main（scala），rust 独立团队" |
| U8 | L46 `API 并发管理 P0 设计批准（ConcurrencyGate 并发默认 3）` | 08-25 限流功能全部移除（#395） | L55 同文件内"provider 限流功能全部去掉" | 删 L46 或标"已废止 08-25" |
| U9 | L164 `WebSearch 待升级：对 JS 渲染/反爬能力不足，关注 crawlee` | WebSearch P0（三级链+反爬指纹）08-23/24 已上线 | Nebula memory L88 `WebSearch 三级链+路由优化（08-23 全链落地）` | 更新为现状或删除 |
| U10 | L8 `开发模型: GLM 5.1` | preset 体系（08-12 定案）已替代裸模型声明，实际链为 general/deepseek/107 等 | L146 Model Preset 方案；memory L60 | 更新为"开发模型: 默认 preset general（zhipu/GLM-5.3）"，或直接删除 |
| U11 | L173-175 `使用模式：活跃时段 00:00,01:00…上次分析 2026-08-30 10:45` | 7419 条记录统计的时段表对 LLM 决策无价值，属噪音 | 无使用方 | 删除（若前端看板需要，从 usage 数据实时算） |

### 3.2 User.md ↔ Nebula memory 重复 / 版本漂移（10 条）

| # | 位置 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| X1 | 进程保护铁律 4 处 | 同一事实记 4 遍：User L61 + Nebula memory L53 + system-prefix-for-all L3-8 + User Dream Extract 08-25 FACT3 | 逐字对比 | 保留 system-prefix-for-all（全员注入层）为唯一权威；User.md 与 Nebula memory 各删冗余副本 |
| X2 | vps.env 凭据 3 处 | User L160 + Nebula memory L242 + User Dream 08-27 FACT1 | 内容雷同 | 保留 User.md 一份（含"严禁入代码"红线），删其余 |
| X3 | Mail queue 全空闲投递 2 处且**版本矛盾** | User L32 记"08-28 01:00 统一修订：废除按发送者区分，统一为当事 agent 子树全空闲"；Nebula memory L261 仍是旧版"19:57 按发送者区分：Nebula→Manager 等全 team 停" | 两文件同事实不同版本 | 以 User L32（新裁定）为准，删/改 memory L261 |
| X4 | 分支治理与发布流 2 处 | User L33 与 Nebula memory L262 几乎全文重复（同一裁定四段演化） | 逐字对比 | memory 版压缩为"分支治理终态已落地（详见 User L33）" |
| X5 | dynamic flow 优先 2 处 | User L39 + memory L264 | 同裁定 | memory 压缩为一行+→User 引用 |
| X6 | PPT 统一 slideblocks 2 处 | User L34 + memory L263 | 同裁定 | 同上 |
| X7 | 经纬项目 3-4 处 | User L89 + memory L122 + Dream 08-24 FACT5/6 | 同一项目状态 | 合并到 User.md 一条（申请已截止 08-30，应直接归档） |
| X8 | 桌面 app 形态 3 处 | User L69 + memory L217 + Dream 08-27 FACT2 | 同一裁定 | 合并 |
| X9 | Logto PKCE 登录 4 处 | User L71 + L37 + memory L235 + L237 | 同一终态（08-28 收官） | 压缩为 User.md 一条终态，memory 只留排障要点 |
| X10 | deck 审美 8 条硬标准 3 处 | User L40-41 + Dream 08-25 FACT4 + Dream 08-26 FACT1 | 同一 8 条清单 | 保留一处权威（User L41），Dream 段删除 |

### 3.3 Nebula memory.md 内部问题（9 条）

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| N1 | L220 与 L223 | **逐字重复**："查代码也 Delegate（08-28 10:15 用户二次强化）"两条一字不差 | 删一条 |
| N2 | L254 / L268 / L273 | **同一裁定记 3 遍**："flow 体系互补修正（08-26 00:09）"三条同内容 | 留一条，删两条 |
| N3 | L62 `bash 无自动超时：移除 autoBackgroundThreshold（30s/300s 全删）` | **过时且自相矛盾**：08-25 已恢复 300s 自动转后台（本文件 L100/L236 自己都写了"前台 >300s 自动转后台"） | 更新为 08-25 终态（300s 自动转后台 + sleep 单独检测 + 30min 硬超时） |
| N4 | L43 `` `/` 触发 flow：用户用 /flow-name 前缀直接触发 `` | **过时**：08-29 /slash 封存（User L21） | 删除或标注已封存 |
| N5 | L7 `项目级配置位置…buildMemoryBlock 加载 projectMem 与全局 agent memory 合并注入…Valid if buildMemoryBlock 有 projectMemory 参数` | **与代码不符**：ContextRefresher.buildMemoryBlock 只有 User + Agent 两级，无 projectMemory 参数——Valid if 失效且未核对 | 删改（项目记忆机制实际不存在或已改名） |
| N6 | L39 `只可用 Read/Grep 少量文件做快速路由判断` 与 L220/L223 `查代码一律 Delegate` | 版本演进未清理（08-27 22:20 用户纠偏"别自己找答案"后旧条目仍在） | L39 更新为最新口径 |
| N7 | L132 / L160 ask 模式相关 | ask 已退役（08-27），fork 机制随 ask 一并退役 | 删除或标注历史 |
| N8 | L69 五状态任务栏条目 | 已标注"已被任务工具重做取代"但仍全文保留 | 删除正文，只留"旧机制已被 08-29 任务工具重做取代"一句 |
| N9 | L92 `博士开题调研收官（08-23）` | 一次性任务终态，已无"开题推进"后续触发价值 | 按"难获取+可复用"标准评估：若学术 survey 流程已沉淀进 skill，本条可删 |

### 3.4 格式合规（Use when / Valid if）

- Nebula memory：273 行中 128 行有 Use when/Read when，**32 条 bullet 缺失**；`→id` 详情仅 25 条
- **team 成员 memory 全部 0 条 Use when/Read when**（抽查 nebflow-project/Backend 41 个【批次】工作日志条目、html-builder、slideblocks/Frontend 均为 0）
- User.md 仅 1 个 Valid if；24 段 Dream Extract（54,053 B，占 48.1%）完全无 Use when 结构，是"倾倒式"日志，非记忆
- nebflow-project/Backend memory 是典型工作日志：41 个【】批次恢复点 + commit 清单 + worktree 路径 + 测试数——全部**能从 git/code 取回**，违反"难获取"标准，且零 →id 引用

### 3.5 错位条目

| 位置 | 内容 | 应该在哪 |
|---|---|---|
| User.md L186-187（Dream 精华） | 前端 CSS `#agents-content 需 flex:1;min-height:0`、JS const TDZ | 前端 agent 记忆 / code comment，不是用户信息 |
| User.md L192-193 | sbt test 锁冲突、sbt 增量编译 mtime 陷阱 | 工程 agent 记忆 |
| User.md Dream 08-14 FACT 1-8 / 08-14 10:14 FACT 等 | mcp analyze_image 工具细节、NebLink JWT 探测、beta.51 攒包 | 过期操作事实 → 归档，不进 User.md |

---

## 4. 上下文占比结论与优化建议

### 4.1 结论

1. **Nebula system prompt ≈ 262 KB（≈87K token/请求），其中记忆块 200.9 KB 占 76.7%（文本占比 83.7%）**。记忆是 system prompt 的绝对主体。
2. **72 个 team agent 每个都注入完整 112 KB User.md**——其中绝大概率与各自领域无关（czt-physicist 不需要 Nebflow 产品决策与 beta 发版记录），这是最大的系统性浪费。
3. 最大的 4 个记忆块：nebflow-project/Backend 308 KB、Frontend 301 KB、html-builder 179 KB、Manager 175 KB——**这些 agent 的单请求 input 达到 110-125K token 量级**。
4. progressive disclosure 机制存在但被弃用：161 个 →ref 中 27 个 broken；86 个孤儿详情文件；team 成员 0 个 →ref。设计是"精简常驻 + 详情按需"，**实际是"全文常驻"**——偏离在记忆文件本身不遵守约束，而非机制缺失。
5. 记忆更新只在生命周期节点生效（systemStable 缓存），**修完记忆后不重启/不压缩的 agent 会一直用旧记忆**——记忆维护流程与生效机制脱节。

### 4.2 优化建议（按优先级）

| 优先级 | 改动 | 风险 | 收益 | 估算效果 |
|---|---|---|---|---|
| **P0-1** | 压缩 nebflow-project/Backend（195 KB→<20 KB）与 Frontend（189 KB→<20 KB）记忆：工作日志/恢复点/commit 清单迁 `docs/` 或删，只留"难获取+可复用"定案 | 低（纯删冗余，git 可回溯） | 高 | 两个最重 agent 每请求省 ~90K token |
| **P0-2** | User.md 大扫除：删 24 段 Dream Extract 过期部分（48% 内容）、合并 10 组重复、修 4 处内部矛盾，112 KB→40-50 KB | 低 | **极高**（73 个 agent 同时受益） | 全部 agent 每请求省 ~20-25K token |
| **P1-3** | Nebula memory.md 去重去旧：删 N1/N2 重复、修 N3/N4/N5 矛盾、压缩至 40-50 KB | 低 | 高 | Nebula 每请求省 ~13-15K token |
| **P1-4** | 修复 27 个 broken →ref + 清理 86 个孤儿详情文件（或补引用） | 低 | 中 | 恢复 progressive disclosure 可信度 |
| **P1-5** | team 成员记忆补齐 Use when/Valid if 并把长条目收敛为"一行摘要 + →detail"（对照 system-prefix-for-teams.md 已有的格式规范执行） | 低 | 高 | 3 个 >60 KB 的记忆块按规范拆分后常驻部分大幅缩小 |
| **P1-6** | 记忆维护纪律：对记忆 >30 KB 的 agent 定期跑 memory-consolidation flow（Backend/Frontend 的日志式记忆明显从未被整理过） | 低 | 中 | 防复发 |
| **P2-7** | User.md 分层评估：产品决策/用户偏好（常驻）vs 运维事实/项目状态（→detail 或 team 级文件），或给 team 注入裁剪视图 | 中（需改 buildMemoryBlock） | 高 | 系统性解决"112 KB 全员注入" |
| **P2-8** | 记忆生效时机：考虑在 memory.md mtime 变化时（而非仅生命周期节点）重建 systemStable——当前"改完不重启不生效"易造成记忆维护与行为脱节 | 中 | 中 | 修正认知偏差 |

**改动顺序建议**：P0-1 → P0-2 → P1-3 为第一梯队（纯删减、零机制改动、git 可回溯、立即生效于下次生命周期重建）；P1-4/5/6 第二梯队（恢复规范）；P2-7/8 需设计评审后实施。

---

## 5. 附：原始数据

### 5.1 字节数/行数原始统计

```
User.md                          112,433 B / 406 行
agents/Nebula/memory.md           88,293 B / 273 行
memory/ 详情目录                  254,917 B / 218 文件
全部 memory.md 合计（agents+teams） 998,254 B

standalone memory.md:
  Manager 35,891 / Backend 2,918 / Frontend 3,173 / Coder 2,021 / Explorer 2,158 / design-engineer 1,772

team memory.md（>10KB）:
  nebflow-project/Backend 195,355 / Frontend 188,875 / Manager 62,301 / qa-frontend 56,427
  qa-backend 30,374 / prompt-engineer 19,962 / slideblocks/Frontend 26,147 / ReminderIsland/swift-dev 23,566
  nebflow-website/Frontend 19,463 / sipm-paper/Manager 13,338 / html-deck-studio/visual-reviewer 12,624
  html-deck-studio/Manager 11,426 / czt-project/Manager 11,790 / sipm-physicist 10,441 / sipm-verifier 10,289
  html-deck-studio/content-planner 10,065 / ReminderIsland/Manager 10,786 / sipm-writer 9,497
  slideblocks/Manager 8,432 / czt-researcher 6,359 / qa-swift 6,427 / cache-engineer 5,183 / tool-engineer 5,999
  voice-recognition-test/Manager 5,019 / nebflow-rust/Manager 3,103 / czt-physicist 7,053（漏列补）等

前缀/系统:
  system-prefix-for-all 4,530 / system-prefix-for-teams 13,278 / system-prefix-for-flows 260
  manager-prefix 5,725 / Nebula system.md 13,561 / Explorer system.md 23,700
  team system.md 51 个 2,023-8,057 / flow system.md 2,990-13,056

详情目录引用健康度:
  →ref 总数（agents+teams）161 | broken 27 | 孤儿文件 86（/218 = 39%）
  Nebula memory 引用 25 个（其中 4 个 broken: 62a39d09/74b14c6d/854b06e5/e5f1eac6）
```

### 5.2 Nebula system prompt 组件明细（重建）

```
system-prefix-for-all 4,530 + Nebula system.md 13,561
+ askUser 1,746 + readLive 1,191 + visualReporting 2,272 + voice 1,996 + devices 45 + language 246
+ skillCatalog 5,136 + flowCatalog 2,248 + teamCatalog 6,193
+ memoryBlock 200,946（User 112,433 + Agent 88,293 + 头 ~220）
= 240,110 B 文本
+ tools ~22,000（估，16 工具）
≈ 262,000 B ≈ 87K token（÷3 中英混合）
记忆占比：文本 83.7% / 含工具 76.7%
```

### 5.3 team agent 记忆块占比（估算）

```
nebflow-project/Backend:   记忆 307,788 / 总 ~374,180 ≈ 82.3%
nebflow-project/Frontend:  记忆 301,528 / 总 ~367,700 ≈ 82.0%
html-deck-studio/html-builder: 179,643 / ~245,815 ≈ 73.1%
slideblocks/Frontend:       138,800 / ~204,972 ≈ 67.7%
```

---

*报告完。本报告未修改任何文件。冗余清单按"位置 + 证据 + 修改建议"给出，P0/P1 项可直接作为 prompt-engineer 实施的任务输入。*
