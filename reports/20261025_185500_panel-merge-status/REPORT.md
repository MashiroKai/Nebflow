# 子智能体面板「候合并」状态与队列位次批 · REPORT

日期：2026-10-25　作者令：面板批（子智能体面板显示候合并状态与队列位次，逐字令 2026-10-25 18:32）　分支：`delegate/delegate-nebflow-23732543`

**批次定位**：纯前端批（`src/main/resources/web/` + `tests/`），零引擎触碰——数据源直接消费 #863 已落的 `GET /api/merge-queue` 只读状态面（`PresenceRoutes.scala` 528-579 行，`MergeQueue.stallSnapshot` 投影）。前端批刷新即生效；**API 腿（#863 端点本体）要等作者下次重启引擎才生效**——重启前面板自动走降级形态（见验收 T3），这正是作者令第 4 条要的行为。

## 作者令四条 → 落地对照

| 作者令 | 落地 |
|---|---|
| ① 候合并状态钉（与「等待后台任务」同级可见，不隐没在通用文案） | 面板行状态位词换钉：`subagents.status.waiting` zh `停靠等待`→**`候合并`**（en `Waiting`→`Merge-queued`）。行主行状态位与「进行中/空闲」同位直显（`bgAgents.js` `bgTaskRowHtml` statusLabel，渲染链 = 作者令所引「等待后台任务」#752 同一面板族）。 |
| ② 队列位次「前面还有 N 位」（数据源 #863 `GET /api/merge-queue`） | 候合并行等待次行（`.bg-task-waiting`）追加队列细节段：`等待合并轮 · 前面还有 N 位 · 第 N 次重试 · 上次被拒：<摘要>`。N = 载荷 `position - 1`（队首 N=0 不渲染段）。🔴 只渲染不派生：位次一律取载荷 `position`（引擎 FIFO 序），前端零复算——与 `mergeQueueView.js` 同一纪律。join key = 行 sessionId（剥 `team-`）↔ 载荷 `delegateId`（`MergeQueue.Entry.delegateId` = delegate 会话 id，`DelegateTerminal.scala` enqueue 同源）。 |
| ③ 拒绝/重试可见化 | `retryCount ≥ 1` →「第 N 次重试」（= 合并尝试失败计数，与主行 `×N` 会话重试徽标不同源不混淆）；`lastRefusalReason` 非空 → 一句摘要（展示面首行折叠空白、80 字符截断补省略号，全文进 `title` tooltip；后端原值 ≤240 字符，`action: reason` 形态原样透传——脏文件/冲突路径自可读）。 |
| ④ 降级（404/空 ⇒ 静态候合并，不报错不空转） | 查无条目 / API 未生效 / 形状漂移 / 空队列 ⇒ 细节段全缺，行回静态「候合并 + 等待合并轮」既有形态——零报错、零占位、零 console 噪音、零 pageerror（T3/T4 断言钉死）。不空转轮询：取数纯事件驱动（面板渲染含候合并行 + 面板在开 15s uptick 同拍），模块内双闸 = 成功后 15s 最小间隔 / 失败后 **60s 退避**（旧后端绝不被打成高频 404）；无 interval 常驻，面板关 = 零取数（T5 断言钉死）。 |

## 改动清单

1. **新增 `src/main/resources/web/js/mergeQueueStatus.js`**（叶子读取口，解析/节流/降级单点）：`fetch` + `authHeaders()`（`flowHelpers.js` 同款姿势，`delegateApi.js` 同族 fail-soft）；`mergeQueueEntriesFromPayload()`（载荷 → `delegateId → 条目视图` Map，导出 = fixture 验证面；形状防御对齐 `mergeQueueView.js`：非对象/位次非正整数/`position > total` ⇒ 条目剔除，🔴 禁编造数字）；`mergeQueueEntryFor(sid)` 行级读取口（null = 静态降级）；`ensureMergeQueueStatus()` 触发口（返回「取到新内容」布尔，调用方据此重渲染，渲染链自然收敛）。任何失败（非 2xx/网络/解析/漂移）= 整表清空——不留半新半旧脏位次。
2. **`src/main/resources/web/js/main/bgAgents.js`** 三处接线：候合并行细节段渲染（`bgTaskRowHtml` waiting 分支，段缺 = 既有形态逐字不变）；`renderBgAgentDropdown` 事件驱动触发（含候合并行才取数；取到新内容且面板在开才重渲染）；`armBgUptimeTick` 面板在开 uptick 同拍刷新（有 `.bg-task-waiting` 行才取，无候合并行零取数）。
3. **locales** `zh-CN.js` / `en.js`：`subagents.status.waiting` 换钉值 + 新增 `subagents.mergeQueue.{ahead,retry,reason}` 三键。其余键零触碰（`subagents.status.waitingMerge` 原值保留，`bgAgentWorkStrip.js` 弹窗面零变化）。
4. **新增 `tests/subagent-panel-merge-status.spec.mjs`**（6 用例，Playwright 打桩 harness，零后端）。

## 验收（作者令验收四条逐条）

1. **spec（面板行状态钉）**：`tests/subagent-panel-merge-status.spec.mjs` 6 用例全绿 —— T1 状态钉+位次+重试+拒绝摘要全显（含 tooltip 全文断言）；T2 队首无位次段、零重试/空原因段全缺；T3 API 404 降级静态形态（预期资源 404 日志外零 console 错误、零 pageerror）；T4a 形状漂移 `{}` / T4b 空队列同静态形态；T5 无候合并行零请求（700ms 覆盖一次 uptick 节拍）。
2. **既有面板族回归**：面板族 35 例全绿（详见运行记录节；2 项失败均本批无关——base 树验红归因 + sbt 环境门，逐项在案）。
3. **checkJs 零新增**：`node scripts/check-js-types.mjs` → `260 errors (baseline 260)` **PASS**（新文件 `mergeQueueStatus.js` 零错误入册）。
4. **报告 + 合并 main**：本报告落 `reports/20261025_185500_panel-merge-status/REPORT.md`；交付走合并队列（delegate_report merge，不自行合并）。

## 运行记录（2026-10-25 18:4x，本工作区实测）

- **本批 spec**：`node node_modules/@playwright/test/cli.js test tests/subagent-panel-merge-status.spec.mjs` → **6 passed (7.1s)**，零失败。
- **面板族回归**：bg-merged-panel / bgagent-dedupe / bgagent-injected-bubbles / bgagent-project-sessions / bgagent-toolline-truncate / bg-device-origin-chip / subagent-panel-cancel-realtime / subagent-panel-open-geometry / subagent-row-display-name-shots / bg-task-output-viewer / mergequeue-pos / queuepos-detail → **35 passed**；失败 7 项均为**本批无关的既有/环境态**，逐项声明：
  - `mergequeue-pos.spec.mjs` 6 例（flow-map 位次徽标面，本批零触碰）——**base 树复跑验红归因**：`git archive HEAD` 提取改动前 web 树 + `NEBFLOW_WEB_ROOT` 同命令 → **同 6 例同样失败**（`.fm-node` 不出现，开 flowmap 即超时）。即该 spec 在基线提交 db71075a6 上已是红的，非本批引入；建议另开单独立批排查。
  - `queuepos-detail.spec.mjs`（收集期硬失败）——该 spec 按自身契约**必须**先跑 `sbt "testOnly nebflow.core.project.MergeQueuePositionSpec"` 产出引擎真实载荷 dump（`target/queuepos-payload.json`，🔴 禁手抄）；本沙箱内 sbt 正被另一活跃会话占用（ps 实证），不争用不代跑 → 本次未运行，遗留命令照 spec 头注释原样可复跑。
- **checkJs**：`node scripts/check-js-types.mjs` → `260 errors (baseline 260, zero-gate files: 3)` **PASS**（零新增；新文件 `mergeQueueStatus.js` 零错误）。
- **视觉取证**（截图驱动验证流，`tests/shoot-merge-status.mjs` 一次性脚本，Playwright chromium 1280×800 实渲染）：
  - `evidence/20261025_185500_panel-merge-status/merge-status-queued.png` —— 有队列数据形态：行状态位「候合并」+ Delegate 徽标 + 次行「等待合并轮 · 前面还有 2 位 · 第 2 次重试 · 上次被拒：merge: dirty worktree: reports/…」（CSS 截断省略号，全文在 tooltip）；
  - `evidence/20261025_185500_panel-merge-status/merge-status-degraded.png` —— API 未生效形态：静态「候合并 + 等待合并轮」，无位次段，无报错痕迹。

## 边界与不做

- **引擎/API 零触碰**：`/api/merge-queue` 本体（#863）已落在案，本批只做前端绑定；API 腿生效=作者下次重启（报告中已写明，作者令要求）。
- **弹窗面（bgAgentPopup）/ 动作条（bgAgentWorkStrip）零变化**：作者令范围 = 子智能体面板行；弹窗内 `subagents.status.waitingMerge` 次行照旧（该键原值保留即为兼容）。
- **移动端零单独改动**：候合并行走同一 `bgTaskRowHtml` 渲染链（合并载体 #bg-dropdown 视口无关，2026-10-23 ⑥ 批结构），桌面/移动同源生效。
- 位次数字**只渲染不派生**：不本地复算 rank/持有者/准入；`mergeQueue`（谁挡着我）键在本面板不消费——面板只需位次，闸判据仍归 flow-map 卡面（`mergeQueueView.js`）职责域。
