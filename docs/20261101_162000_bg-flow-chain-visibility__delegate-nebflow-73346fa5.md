---
type: batch
status: done
related: "[[index]]"
---

# 后台代理面板 Flow 会话可见性批：链会话溯源分组 + 延续态徽标 + 计数模型单点（2026-11-01）

> 作者令 2026-11-01（控制台后台代理面板修复）：分发器模式的链会话（kind=Flow，dispatcher-/node- 前缀会话）对用户完全不可见——引擎 AgentControl 全部在册，前端面板零渲染；spawn 它们的 delegate 终态=completed 后活链无任何痕迹（用户视角=批死了）。四条：① 渲染 Flow 会话（溯源分组/无主直显 + kind 徽标 + 状态/运行时长）；② delegate 终态而链在跑 → 「链运行中」延续态徽标，链全终态恢复普通终态样式；③ 数据源用现役面板同源接口（不新增后端面）；④ 深/浅 × Chromium/WebKit 双引擎实拍两组场景，零 console error。图谱：[[index]] · 词表 [[glossary]]。

## 0. 结论先行

- **后端零改动**：[[#2 数据源]]实证现役列接口（`getActiveAgents`，WebSocketRoutes `filterActiveAgents`）已直吐 AgentKind.Flow 会话——要求③以「同源零改动」满足。
- 根因两面（全前端）：C1 过渡过滤整面滤除 + delegate 作用域桶非任何视图桶（[[#3 根因与修复]]）。
- 验收：新 spec 5 用例 + 存量六套件全绿（38 用例同跑）；实拍 4 引擎主题组合 × 3 场景 44/44 审计 PASS、全程 console 零 error（[[#4 验收与证据]]）。
- 合并：分支 `delegate/delegate-nebflow-73346fa5`，批提交 `d9be29a14`，reconcile merge `9d7e91256`（main=`92ad94893` 已含）。

## 1. 根因与修复

根因两面：

1. **C1 过渡过滤**（delegate-ui 批 2026-10-08）：`dispatcher-*`/`node-*` 整体滤出陈列面（isC1InternalOrchestration）——终态内部件删除从未发生，过滤成了永久不可见。
2. **作用域桶不可见**：引擎 `DelegateFlowHosts.mount` 以 delegate 会话 id 自根（rootSessionId=delegateId），链会话行归桶 `sessionBgAgents[delegateSid]`——该桶不是任何视图的桶，整桶无渲染路径；视图徽标亦无从计数。

修复（全落 [[#5 落点]]所列前端文件）：

- **C1 退役**：isC1InternalOrchestration 恒 false——Flow 会话与 Delegate/SubTask 同一陈列标准；活性/注册语义零触碰。
- **面板模型单点 bgPanelModel**：badge 计数/头部计数/空态/行集四消费面共读一模型（total = 主桶可见行 ∪ 作用域桶链子行 ∪ 延续态组头）——「签与列表永不打架」口径随单点延续。
- **溯源分组（第⑤认领源）**：bgDelegateScopeChainRows 受控读口读 delegate 作用域桶（self 守卫 + 分发器置顶 + startedAt 升序），挂主桶 delegate 组头缩进子行；flow-step 结构投影与 live 链行互斥（桶空时结构投影照旧兜底）。
- **延续态合成组头（第⑥组源）**：注册行镜像（spawn 落盘跨重启）∧ live 行已离场 ∧ 作用域桶非空 → 合成组头（短名/mode 徽标承镜像）+ 琥珀「链运行中」；链全终态后判据自然失效，组头随行集消失——徽标绝不滞留死链。
- **延续态覆写**：live delegate 行终态在场而链仍活 → 行「链运行中」（workflow 图标 + warn token + active 点）+ 不挂 .done 灰化；链全终态后覆写入参缺席 → 行回普通终态样式。
- **kind 显示词 bgFlowKindWord**：`dispatcher-`→分发器、`node-`→节点（引擎协议 kind 一律 "Flow" 无区分度，按会话 id 前缀单点判读）；无主行顶层直显同款徽标。
- **徽标自动刷新缝修正（T1 实证）**：反查门从「查无视图」改「查无可刷新视图」——ws.js 拦截器 ensureStepView 为 delegate 会话先建隐藏 step 视图（无指示器元素），旧代码在 el 检查处静默 return，bgScopeRootForBucket 反查回退从未执行，宿主徽标恒滞留旧值。
- **终态回收缝**：会话级 done 收尾桶键两步解析（直查 → 作用域桶反查宿主；同款「无可刷新视图」判读）+ refreshAllBgAgentIndicators 全视图重算兜底——链全终态拍作用域桶整桶清空、桶键再无可反查行，徽标归零回收同一拍收口。

## 2. 数据源（要求③）

现役面板同源接口 = WS `agentStart` 三键帧（routeSubagentWsSend 注入 rootSessionId/sessionId/nodeSessionId）+ `activeAgents` 快照恢复。后端 `getActiveAgents`（WebSocketRoutes.scala filterActiveAgents 段）已含 AgentKind.Flow 会话——列接口本就直吐，**零后端改动**。

## 3. 数据面事实（溯源判据的引擎侧依据）

- delegate 作用域链：`DelegateFlowHosts.mount` rootSessionId=delegateId（自根事实）；delegate 名下挂载项目同桶。
- 项目域挂载根 = 顶层会话 → dispatcher-/node- 行落主桶、无 parentSessionId → 无主顶层行直显（要求① fallback 覆盖）。
- ws.js convertAgentEvent + interceptBgAgentStep 对链帧二次分发转换帧（无 rootSessionId）→ 幻影桶 `bucket[nodeSessionId]`（含自指行）——scope 链读口 self 守卫排除；幻影桶不入面板模型。

## 4. 验收与证据

- **spec**：新增 `tests/bg-flow-chain-visibility.spec.mjs`——T1 溯源分组（组头 + 分发器置顶子行 + kind 徽标 + 运行时长 + 缩进 + 计数 4）/ T2 延续态（live 行终态在场）/ T3 延续态（快照恢复合成组头 + 徽标计数 > 0）/ T4 链全终态回空 / T5 无主行顶层直显；每用例全程 console 零错误断言。存量六套件同跑全绿（merge-status 7 / cancel-realtime 3 / merged-panel / project-sessions / twolayer / dedupe 2），合计 38 用例。
- **存量红清欠**：bgagent-dedupe 选择器重锚（后台合并批分节 DOM 后旧 `>:last-child` 直子锚恒空——干净 HEAD 同红，与本批无关）。
- **实拍**：`evidence/20261101_161500_bg-flow-chain-visibility__delegate-nebflow-73346fa5/`——Chromium+WebKit × 深/浅 × 活链（A）/链全终态恢复普通样式（B1）/面板回空徽标回收（B2）共 12 帧 + 批报告；44/44 审计 PASS、console 零 error。注记：WebKit 网络进程拒受限端口（Safari bad-port），实拍 base URL 用 127.0.0.1:8931；console 噪音源 = `/agents/<root>/agent.json` 探测 404 帧（harness stub 化）。

## 5. 落点

- `src/main/resources/web/js/main/bgAgents.js`（模型单点/溯源读口/延续态/kind 词/反查缝/refreshAll）
- `src/main/resources/web/js/main.js`（done 收尾两步解析 + 全视图兜底）
- `src/main/resources/web/js/locales/zh-CN.js` + `en.js`（kindDispatcher/kindNode/chainRunning/chainRunningTip）
- `src/main/resources/web/css/chat.css`（.bg-task-chain 琥珀延续徽标，零新色）
- `tests/bg-flow-chain-visibility.spec.mjs`（新）+ 三存量 spec 口径更新/重锚
- `tests/bg-flow-chain-visibility-shots.mjs`（实拍脚本）+ evidence/ 批目录
