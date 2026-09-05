# 阶段 1 试点验收 —— phd-notebook 真实运行（场景① 单节点调研）

> 阶段文档（2026-09-02，v5 方案 §4 阶段 1 验收）。权威方案：`20260831_project-node-architecture.md`（v5 冻结）。
> 验收范围：阶段 1 场景①（单节点调研，真实 provider）；隔离冒烟前置（⑩ mountProject 盲区）。
> 前置闭环：阶段 0 后端（qa-backend PASS，1932/1932）+ 前端（qa-frontend PASS，九项验收）均已合并 main。

---

## 1. 验收结论

**✅ PASS —— 阶段 1 场景① 端到端真实运行通过，新架构核心机制实证。**

真实试点节点「归档-ASTREA调研」（n-cffd5b71）完整跑通：Nebula Mail(→phd-notebook) 触发 → ProjectActor spawn 分发器 → 分发器 NodeEdit 建入口节点 → 节点真实执行（Explorer + 真实 provider，读调研报告/按 phd-note skill 整理/写文献笔记/git 提交）→ 完成 → 结果沿 out 边自动投递回 Nebula（非 Mail 机制实证）→ 结果全文落盘 flow-map.json → 归档产物 commit 938160d。

## 2. 场景① 验收点逐条核对（方案 §4 阶段 1）

| 验收点 | 结果 | 证据 |
|---|---|---|
| ① Mail project → 分发器建节点 → 节点跑 → 结果 → Nebula 验收 | ✅ | Nebula 侧真实触发；分发器会话 spawn；NodeEdit 建入口节点创建即运行；gateway.log「Node '归档-ASTREA调研' completed (result …)」；Nebula 收到结果沿 out 边投递 |
| ② 改接竞态三断言（运行中改接/完成后缓冲改接/旧目标已启动拒） | ✅（单测+冒烟覆盖） | NodeAcceptanceSpec（NodeEdit 改接/断开/悬空化）；隔离冒烟附验「悬空结果保留 + 接线自动投递（不重跑）」；旧目标已启动拒由 §2.2 状态守卫单测覆盖 |
| ③ barrier 时序（3 路全到才启动合并） | ✅（单测覆盖） | NodeAcceptanceSpec barrier 3 路全到才启动；真实多路合并留后续场景（场景③） |
| ④ 5min TTL（测试档 10s）：完成节点消失 + 归档保留可接线投递 | ✅（冒烟附验 + 真实数据核对） | 冒烟：首节点 5min 后活动区消失、归档保留；真实：ttlExpireAt = completedAt + 5min 计算正确（1788290874701 → 1788291174701）；归档区 flow-map-archive.json 历史节点（cancel-test/race-c-source 等 #28 数据）完好 |
| ⑤ 重启恢复：kill 后 flow-map.json 恢复 | ✅ | 宿主重启（pid 59335）后项目自动挂载（冒烟日志「Startup mount: 1 project(s)」）；本次真实试点即重启后首个任务，flow-map.json 完整恢复 |
| ⑥ 用户真实使用 2-3 个任务 | ⏳ 部分 | 已完成 1 个真实任务（场景①）；其余场景②-⑤与更多真实任务待后续轮次（试点不一次铺开，作者裁定「试点通过前不铺开」） |

## 3. 完整链实证（五环 + 核心断言）

隔离冒烟（qa-backend，8093 + mock LLM）已前置验证，真实试点复验：

| 环节 | 隔离冒烟（mock） | 真实试点（真实 provider） |
|---|---|---|
| 1. Mail(→project) 触发 | ✅ "Project 'qa-smoke' dispatcher triggered" | ✅ Nebula Mail(→phd-notebook) 真实触发 |
| 2. ProjectActor spawn 分发器 | ✅ dispatcher-<uuid> | ✅ 同机制 |
| 3. 分发器 NodeList→NodeEdit 建入口节点 | ✅ 创建即运行 | ✅ 节点 n-cffd5b71 创建即运行 |
| 4. 节点执行 | ✅ "Node … completed (result 40 chars)" | ✅ Explorer 真实执行完成（含 git 提交） |
| 5. 结果保存 + NodeList/REST 可查 | ✅ flow-map.json completed+result 全文 | ✅ flow-map.json result 全文落盘（归档路径/commit/摘要/衔接结论） |

核心断言 **mount 后 actorRef 非 None** ✅（三重证据：Mail 返回 triggered / Startup mount 日志 / TriggerDispatcher 真实执行）——⑩ spec 盲区关闭。

## 4. 阶段 0+1 交付清单

### 代码（Nebflow 主仓，main @0514e593）
| commit | 内容 | 验证 |
|---|---|---|
| 77157827 | 阶段 0 前端：Project 按钮取代 Team/Flow + Project 面板 + Flow Map 标签页 + WS 订阅（11 文件全 web/） | qa-frontend PASS（九项验收，真实隔离实例） |
| c4a764fb（merge 0514e593） | 阶段 0 后端：MailTool routeToProject 双入口对称 + NodeTools loop detect + NodeAcceptanceSpec 11 项验收测试 | qa-backend PASS（1932/1932 零回归、14/14 验收） |

### 定义与数据
| 路径 | commit | 内容 |
|---|---|---|
| ~/.nebflow agents/project-dispatcher | 78112d7 | 分发器全局 agent（NodeEdit/NodeList/NodeCancel + BaseTools(读) + Bash，无 Mail） |
| ~/.nebflow/projects/phd-notebook project.json | 5ca5749 | 试点项目定义（§1.1 字段齐全） |
| phd-notebook repo | 938160d | 真实试点归档产物：literature/2026-09-02_astrea-spaceborne-agent.md + index.md（+101 行） |

### 测试统计
- 全量 sbt test：**1932/1932** 零回归（含 NodeAcceptanceSpec 17 + MailTool 20 = 37 专项）
- qa-frontend：九项验收全绿 + 静态 278/278 + 动态 import 链全 200
- qa-backend：验收 14/14 + NodeEngine 资源释放复核 + 旧路由零回归（CheckTeamScope 9 + QueueNebula 4 + RootSender 7）
- 隔离冒烟：完整链五环 + 附验（悬空保留/接线投递不重跑/TTL 归档）
- 真实试点：场景①端到端（本报告）

## 5. 观察项（阶段 1 后续跟进）

1. **前端真实渲染验证** ✅（Frontend，真实实例 8080 只读验证）：Project 面板真实字段与 REST /api/projects 逐字段一致（name/workspace/描述/运行中 agent=0/摘要「1 节点已完成」）；Flow Map 节点卡片与 REST flow-map 载荷一致（agent=Explorer/status=completed/result 摘要 46 字/TTL 倒计时/无 wt 徽标/edges=[]）；**TTL 消失语义实证**：ttlLeftSec 231→0 逐秒递减 → 03:33:15 活动区移除（active nodes=[]，archived 27→28）→ 归档区 flow-map-archive.json n-cffd5b71 完整保留（result 全文 1586 字符、status=completed）→ TTL 后 REST 仍可查（归档 findNode 兜底）——「显示消失≠数据删除」真实实证；亮暗主题截图取证（/tmp/fm-render-check/）；无 WS 报错（console 4 项均为既有 F1/NebLink 行为 + headless WebGL 无害警告，非本项目回归）。附观察项：前端空态三分（本地 TTL 归零→backend sweep 间隙短暂「archived」，sweep 后「empty」）为设计内行为。
2. 场景②-⑤（串联/并行 barrier 真实/改接真实/worktree 合并）留后续轮次派发；barrier 与改接机制已被单测+冒烟覆盖，真实跑通是补强
3. loop detect 真实环境验证（同 task 重复派发拒绝）尚未真实触发——留待后续
4. 用户真实使用 2-3 个任务计数：目前 1 个（场景①）
5. 阶段 2（迁移/退役）按作者后续指示，不在今晚范围

## 6. 风险与遗留

- 无阻塞项；QC 429（配额 2026-09-02 10:57 重置）已按裁定人工 QA 覆盖等价处理（前端/后端 merge 均适用）
- 旧体系（Team/Flow）试点期并行运行未受影响（1932/1932 零回归实证）
- 节点无 Mail 身份、结果沿 out 边投递——机制已在真实试点实证（Nebula 收到结果非 Mail）
