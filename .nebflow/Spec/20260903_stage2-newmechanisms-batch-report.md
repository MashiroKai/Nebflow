# Nebflow 阶段 2 新机制批收尾报告（归档按钮 + ProjectCreate 交互面板）

- 日期：2026-09-03
- 收尾节点：合并-ProjectCreate进main（n-1f76ccf6，本批 barrier）
- 批次范围：迁移方案文档 v2 ＋ 迁移方案 v2 §6 两项新机制（6.1 Project 面板归档按钮 / 6.2 ProjectCreate 未知路径交互面板）

## 结论总览

| 项 | 状态 | 一句话 |
|---|---|---|
| 迁移方案文档 v2 | **完成** | 五条作者裁定全部落档，~/.nebflow commit `481771d` |
| Project 面板归档按钮 | **未收口** | 实施完成，QA 判定缺失（验证节点 result 0 字符），合并节点 BLOCKED，未进 main |
| ProjectCreate 交互面板 | **完成收口** | 实施→独立验证 PASS→合并进 main `7426bd8f`，合并后子集全绿，现场清理完毕 |

---

## ① 迁移方案文档 v2

- 文档：`/Users/dev/.nebflow/docs/Nebflow/20260903_stage2-migration-plan.md`（v1→v2，+96/-53 行）
- commit：v1 `9552714`（初版）；**v2 `481771d`**（作者 2026-09-03 12:18 拍板落档）
- 五条裁定落点摘要（节点 n-1563ee11）：

| 裁定 | 落点 |
|---|---|
| 1 决策点全落定（均采纳建议 a） | §3.3 改题「决策点（v2 已全部拍板）」+ 每行补结论列，原选项表原样保留存档 |
| 2 废除预定 node 模板路线 | §1.3（5 flow 重判二选一：蒸馏 skill / 直接归档）+ §2 整体改写为「分发器按任务自主建图」+ §4.3 判据 2 + §5 M1/M2；配方库（project-node-patterns.md）计划取消 |
| 3 Task 落为 project 唯一触发入口 | §3.1 T0/T2/双轨语义③（Mail(→project) 兼容入口删除宣告 + Mail(旧 team 名) fallback 安全网按 DP7-a 保留 + 存续 team 仍走 Mail）+ §2.1/§2.3/§2.4 触发改 `Task(project=…)` + §4.1 Mail 路由删除行 |
| 4 新增 §6 新机制章 | §6.1 归档按钮 / §6.2 ProjectCreate 交互面板 / §6.3 「无 delegate、全走 Project」影响分析 |
| 5 里程碑调整 | §5 M1 插入新机制实施+验收⑤ / M3「存续任务终态即迁、不等活动期」+ 总量 ~11-15 闭环日 + §1.2/§1.5 sipm/czt 窗口口径修正 |

---

## ② Project 面板归档按钮（迁移方案 v2 §6.1）

### 三段 hash

| 段 | commit | 状态 |
|---|---|---|
| 实施 | 后端 `79f4fd0c` + 前端 `5654aca9`（分支 `proj-archive-btn`） | 完成（实施自测绿） |
| QA | —— **判定缺失**（验证节点 n-a92af6db 引擎标记 completed 但 result 0 字符：宿主日志 `turn-ended-empty`，验证会话 node-456de313 在隔离实例搭建中途截断） | **未产出 PASS/FAIL** |
| 合并 | 合并节点 n-4741ee7d 以 **BLOCKED（upstream-incomplete）** 结束，零仓库变更 | **未合并** |

### 实施内容摘要（节点 n-bb119ecd）

- 持久化：project.json **手术式原位插键**（读原始 Json → deepMerge `archived:true`+`archivedAt` → AtomicJson 原子写回），不走 codec 全量 re-encode——插键保证存量字段逐字节稳定，解码侧 `ProjectDef` 加两 Option 字段，存量零迁移
- 过滤：`ProjectStore.list()` **源头过滤**——面板 API 与 startupMount（GatewayMain `mountAll`）两个消费方语义同源，未来消费方默认安全
- 前端：卡片归档入口 + 确认弹层 + 列表同页实时移除（`nodeData.js` / `projectTab.js`）
- 清扫机制审计：9 项既有清扫路径全排除误伤（节点 TTL sweep / taskTtlSweep / 孤儿收殓 / V8 重投 / ScheduledTaskActor / NebflowBackup / ContextRefresher / filewatch 等）

### 验收结论

**不能视为已验收**。QA 闸门输入不存在（既非 PASS 也非 FAIL），按「QA 判定为闸门」铁律未合并。现场完好可复用：分支 `proj-archive-btn@5654aca9`（实施 2 commit、无验证残留）、worktree 干净、未 push。**遗留动作：重派「验证-面板归档按钮」节点（retries 预算未动用），拿到真实判定后重投合并节点。**

---

## ③ ProjectCreate 未知路径 AskUser 式交互面板（迁移方案 v2 §6.2）

### 三段 hash

| 段 | commit | 状态 |
|---|---|---|
| 实施 | 后端 `c8f60d9a` + 前端验证 `0f71bb6b`（分支 `proj-create-tool`） | 完成 |
| QA | 报告 `~/.nebflow/docs/Nebflow/20260903_project-create-panel-verify-report.md`，~/.nebflow commit `af5e932`；**最终判定 PASS（7/7 验收项）** | 完成 |
| 合并 | **`7426bd8f`**（no-ff merge，'ort' 策略） | 完成 |

### 实施内容摘要（节点 n-aaef56ac）

- ProjectCreateTool 重写为双分支：**已知路径直建**（原链：ProjectStore.create → registry.mount 幂等，rootSessionId=上链根）/ **未知路径（workspace 缺省或不可解析）弹路径面板**——复用 AskUser pending 机制（`AgentCommand.AskUser` → InteractionHub → 前端 AskUserQuestion 卡片），零新增问答通道
- 候选 = `~/Claude code/` 一级目录排除已占用 workspace 与点目录（上限 8）+ Other 自由输入（支持 `~` 展开）；取消/空答案 → `__cancelled__` 哨兵 → 搁置消息不创建
- 补齐同名冲突语义（同 workspace 幂等 / 异 workspace 明确报错且旧定义不改指）、name 缺省 basename 派生、成功消息附 `Task(project=…)` 触发提示
- 前端产品代码**零改动**（面板 = 既有 AskUserQuestion 卡片本体）；#43 三层语义（槽形状校验 / pending 排队 / 前端 injected 非答案）结构性继承

### QA 判定复述

**PASS（可合并）**。7/7 验收项全过：①已知路径直建（project.json 五字段断言+脚手架+mount+幂等重放）②未知路径弹面板（pending 实证+真实 hub 帧+候选排除+allowOther）③面板选择后创建（Other 自由输入差异化场景）④创建后 Task 可触发（真实分发器会话拉起全链）⑤#43 兼容（agent 形态负载被 RETAINED、用户帧正常消费）⑥取消/超时/冲突语义（零残留、无悬挂出口）⑦回归+审查（独立 spec 5/5 + 上游 spec 重跑 7/7 + 改动域回归子集 85/85 + Playwright 5/5 + i18n 成对完整 + 无越界）。

### 合并结果（本节点）

- 冲突预判：分支侧 7 文件 × main 侧 5 文件（自 merge-base `7ba589f3`），交集仅 **`src/main/scala/nebflow/core/tools/NodeTools.scala`**（main 侧 Node hold × 分支侧 ProjectCreateTool 重写）
- 实际合并：'ort' 自动合并，**零冲突文件**——双方改动同文件不同区域，语义融合无丢失；QC 自动审查复核确认 Node hold 语义（`holdProvided`/`releaseNode`/`NodeLifecycle.Held`）全部在位无回退
- merge commit：`7426bd8f`

### 合并后子集复跑（主仓，逐 spec）

| 组 | spec | 结果 |
|---|---|---|
| ProjectCreate 新 spec | ProjectCreatePanelSpec | ✅（7 用例内含） |
| AskUser/#43 相关 | AskUserPendingInjectionSpec / InteractionHubSpec / InteractionHubReplaySpec / WaitTimeoutAskUserWiringSpec / AskUserQuestionToolSpec | ✅ |
| TaskTool/ProjectActor 等既有 | NodeToolsSpec / ProjectStoreSpec / ProjectCreateRootSessionSpec / ProjectActorSpec / ProjectDispatcherSingletonSpec / ProjectDispatcherLifecycleSpec / ProjectSessionCancelPanelFrameSpec | ✅ |
| **sbt 小计** | 13 spec | **92/92 绿，0 失败 0 错误（120s）** |
| i18n sweep | `chat.confirm/cancel/other` | ✅ zh-CN(502/504/512) ↔ en(498/500/508) 成对完整 |
| 前端面板 spec | Playwright `tests/project-create-panel.spec.mjs` | ✅ **5/5 绿（28.2s，一次过，未触发 node 降级）** |

### 清理确认（子集全绿后执行）

- `git worktree remove .nebflow/worktrees/proj-create-tool` → ✅（worktree list 已无此项）
- 软链 `.nebflow/proj-create-tool` → ✅ 已 rm
- `git branch -d proj-create-tool` → ✅ `-d` 直接接受（曾指 `0f71bb6b`，已完整并入 main）
- 其余在途 worktree（proj-archive-btn / flowmap-archive-panel / timeout-evict-fix）属其他流程，未触碰
- 全程未 push、未动 origin、未触碰宿主进程（PID 87216 / 端口 8080）

---

## 全部生效说明（统一口径）

**本批两项机制均需前端产物重建 + 宿主重启才生效；本批未做任何重启。** 精确注记：ProjectCreate 面板为后端 ProjectCreateTool 新语义（前端零产品改动，面板即既有 AskUserQuestion 卡片），生效点在宿主重启加载新 jar；归档按钮含前端 js 改动，且**尚未合并**——待其验证过闸、合并进 main 后，与 ProjectCreate 一并按统一口径生效。

## 遗留问题汇总

1. **归档按钮收口未完成**：QA 判定缺失 → 合并 BLOCKED → 需重派验证节点后重投合并（分支 `proj-archive-btn@5654aca9` 现场完好，零重建成本）
2. **ProjectCreate QC 三条低严重度跟进**（合并时 QC 自动审查产出，不阻塞）：① `"///"` 自由输入有 name 时穿透为异常而非 ToolError（`parsePanelAnswer` 建议对 strip 后空串判 BadPath）② 直建分支路径守卫对相对路径也成功，「不可用→面板兜底」实际不可达，注释与守卫强度不符 ③ `scanCandidates` 的 `os.list` 权限不可读时可抛，建议 `Try(...).getOrElse(Nil)`
3. ProjectCreate QC 信息级两条：面板 pending 期间新建项目不进候选排除（单用户可忽略）；`CancelSentinel` 跨层魔法串无单一事实源
4. 候选根硬编码 `~/Claude code`（未来可挂 nebflow.json 配置化）
5. 面板问题文本中文硬编码（多语言需 i18n 化）
6. 无 project 定义的手工目录出现在候选（预期行为，非 bug）

## 本批 hash 汇总（各一行）

- **迁移方案文档**：v1 `9552714` → **v2 `481771d`**（~/.nebflow）
- **归档按钮链**：实施 `79f4fd0c`+`5654aca9`（分支 proj-archive-btn，未合并）｜QA 无判定｜合并 BLOCKED 无 hash
- **ProjectCreate 链**：实施 `c8f60d9a`+`0f71bb6b` → QA 报告 commit `af5e932`（判定 PASS）→ 合并 **`7426bd8f`**（main）
