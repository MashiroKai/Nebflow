# 四实体生态设计 · 决策总览

> 6 份报告（R1-R6）已产出。本页是汇总导览：先看硬发现，再逐项拍板 27 个决策点。
> 每份报告全文见文末路径表，均已展示过 Canvas。

---

## 一、现状核实的硬发现（不拍板也要知道）

### P0 级缺陷（建议无条件清理）
| 发现 | 位置 | 后果 |
|------|------|------|
| extends 残留模板 ×4 | entity-creator/flow-creator prompt | builder 产出"无 system.md 空壳 agent"，reviewer 假阳性 PASS |
| slash 教错工具 | WebSocketRoutes:3629 | `/flow-name` 教 agent 用 Mail(flow)，MailTool 明文不路由——必先撞墙 |
| opt-in 漏 modelInvocable 过滤 | SkillService | 声明 disable 的 skill 仍可能被模型调用 |
| when_to_use 解析后丢弃 | SkillService | 防误用的关键元数据没有进 catalog |
| 跨 team 半透膜后门 | MailTool:777 | 短名被拦，显式 `team/agent` 格式放行——与"不能跨 team"认知出入 |
| profile 错位 | CompactService | team 成员 depth=1 被套 Manager profile，skill 沉淀文案收不到 |

### 断路与死代码
- **skill 层全线断路**：opt-in 机制已在代码里但 **0/36 agent 采用**；skill-proposals 目录零产出零消费者；12 个全局 skill 无人引用
- **"Nebula 垄断"是自律不是机制**：Write/Bash 全员可用，任何 agent 都能写实体文件
- 死代码：buildSkillCatalog、EntityLoader.write 系、ExperienceExtractor（LLM 旁路零产出）
- **涌现协作零发生**：345 封 team Mail 全走 Manager 中转；对照 flow 内部节点直连正常——**缺的是协议不是传输层**
- 复制漂移：nebflow-project qa-backend 描述仍写"Rust 迁移审核员"

### ⚠️ 与在途工作的冲突提示
**R4 的"NodeTimeout 节点级可配"与今晚已派发的超时移除方案（v2.1）冲突**：v2.1 定稿是删除三处超时（编排层不该有超时，activity 由 actor 自愈兜底）。若 v2.1 落地，节点级 timeoutMinutes 不再需要——决策点 16 请结合 v2.1 一并考虑。

---

## 二、决策清单（27 项，附报告倾向）

> 用法：逐项 ✅ 倾向 / ❌ 改选 / 💬 讨论。多数项报告倾向已较成熟，可快速过。

### A. 触发与工具（R1）

| # | 决策点 | 报告倾向 |
|---|--------|---------|
| 1 | FlowTrigger 是否 Nebula 专属 | **不专属**——flows 白名单驱动，权限解耦正是拆分目的 |
| 2 | Team 成员（非 Manager）能否触发 flow | **分两步**：P1 仅放 Manager（现状 Manager 无任何 flow 触发能力）；成员等 Team 自建 flow 跑稳再放 |
| 3 | slash `/flow-name` 是否绕过 LLM 直触发 | **保持 agent 中介**（改指令即可）；直连省 token 但丢参数加工 |
| 4 | team.json `flows` 字段本次接不接 | **不接**，与成员级 flows 声明二选一，在 R2 体系里统一定 |

### B. 实体创建权限（R2）

| # | 决策点 | 报告倾向 |
|---|--------|---------|
| 5 | Team 创建是否绝对垄断（含合并/派生） | **绝对垄断**——Team 是权限域的根，自建 = 自我扩权 |
| 6 | Team 自建实体要不要 Nebula 审计 | **轻量通报 + 周期巡检**，不前置审批（审批会让"自建"退化回"垄断"） |
| 7 | 普通成员能否直接触发 entity-creator | **P1 不放**——成员→Manager 提名→Manager 触发 |
| 8 | skill 创建权 | **成员自服务**——纯知识注入无提权面，opt-in 本身已兜风险 |

### C. Skill 体系（R3）

| # | 决策点 | 报告倾向 |
|---|--------|---------|
| 9 | opt-in 默认值 | **默认空** + 沉淀时自动提议订阅；反对"推荐包" |
| 10 | 订阅是否运行时强校验 | **只影响可见性**——skill 是知识不是权限，强校验是假安全 |
| 11 | catalog 注入粒度 | **名字 + description + when_to_use** |
| 12 | 复审周期 | **90 天兜底 + 闲时 Schedule**；按事件触发 P2 再议 |
| 13 | project 层 skills（cwd）去留 | **保留**——定位"随 git 仓库分发的项目 skill" |
| 14 | 防过时责任主体 | **园丁（Manager）持队列 + 订阅者反馈 + 作者仅创建时义务** |

### D. Flow 设计（R4）

| # | 决策点 | 报告倾向 |
|---|--------|---------|
| 15 | 节点数上限形式 | **软指导 ≤5 + 代码 warning（>8 节点/深度>6）**，不硬 reject |
| 16 | NodeTimeout 节点级可配 | ⚠️ **与 v2.1 超时移除冲突**——v2.1 落地则此题消失 |
| 17 | 失败节点输出落盘 | **落盘快照**——支撑断点重试，低成本高价值 |
| 18 | verdict 契约漂移检测 | entity-creator reviewer 增一项 C2 一致性检查 |
| 19 | 单节点 flow 目录格式 | 维持现状（loadFlowAgent 回退全局已优雅解决） |

### E. Team 组织学（R5）

| # | 决策点 | 报告倾向 |
|---|--------|---------|
| 20 | 显式 `team/agent` 跨 team 路由 | **留但收紧**——非 lead 默认关，team rules.md 显式开 |
| 21 | Manager 全局记忆方案 | **F2 CC 纪律 + F3 周报 digest**，F1 直读留痕作过渡 |
| 22 | 成员上限 20 进不进代码 | reviewer + checklist 承载；**硬线 20 做创建时 blocker** |
| 23 | 项目状态记忆唯一权威 | **rules.md 唯一权威**，Manager memory 只存派发经验 |

### F. Agent 进化（R6）

| # | 决策点 | 报告倾向 |
|---|--------|---------|
| 24 | agent 改自己 system.md 审批 | **分级混合制**：行为段自治+留痕 / 自述自助 / 红线审批；prompt-engineer 转型"体检医生+审批者" |
| 25 | ExperienceExtractor 旁路 | **降级可选**，两个体检周期零产出后移除 |
| 26 | memory git 化范围 | **只 git 化 entities 目录**；memory.md 含隐私需你点头 |
| 27 | 独立 preference-store | **否**——三层分置（User.md/rules.md/memory）已覆盖，先跑三个月 |

---

## 三、建议实施波次（拍板后按此排）

| 波次 | 内容 | 性质 |
|------|------|------|
| **Wave 0 清障** | extends 残留 ×4、slash 指令修正、skill 三小修、跨 team 警告文案、qa-backend 描述漂移 | 全是 P0 小修，零争议 |
| **Wave 1 机制** | FlowTrigger 拆分、超时移除 v2.1（已派发）、失败落盘快照、涌现协作协议 prompt 层（成员互 Mail 指导 + CC 纪律） | 决策点 1-3、17、20-21 |
| **Wave 2 生态** | entity-creator 方案 B 重构、Team 自建实体放权、skill 防过时四件套、成员上限 reviewer | 决策点 4-8、12-14、22 |
| **Wave 3 进化** | Agent 三层进化循环、分级混合制审批、skill 采用牵引 | 决策点 24-26 |

---

## 四、报告全文

| 报告 | 路径 |
|------|------|
| R1 Delegate 拆分 | /tmp/ecosystem-R1-delegate-split.md |
| R2 创建权限 + entity-creator | /tmp/ecosystem-R2-creation-permissions.md |
| R3 Skill 体系 | /tmp/ecosystem-R3-skill-system.md |
| R4 Flow 设计指导 | /tmp/ecosystem-R4-flow-guide.md |
| R5 Team 组织学 | /tmp/ecosystem-R5-team-org.md |
| R6 Agent 设计指导 | /tmp/ecosystem-R6-agent-guide.md |
