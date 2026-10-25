# 手机端 P1 快赢批 · 五件全修（2026-10-25）

- **依据**：`reports/20261025_mobile-ui-audit/REPORT.md` §6 P1 快赢五件（作者 2026-10-25 对账批派修）
- **范围**：②26/⑥38 busy 排队项断连补发轨 · ②27 初载骨架卡 · CC6-4 触感缩放 · spec 判据换代四连 · 两路验收
- **代码态**：worktree `delegate-nebflow-1e783ed0`（main 同源 `7ff4f6656`），五件全落地

---

## 1. 结论一段话

P1 五件全修、全绿收官：capsule node-runner **29/29**（批前 25/3）、Playwright 手机族 **55/55**（p9×3/p11×3 判据换代后即绿）、排队族邻域六件 **ALL PASS**、新增骨架卡 spec **16/16**、checkJs 我方触碰文件**零错误**（门面 260 = 冻结基线 257 + 未跟踪 nearpath.js 3，与 20261024_164337 批申报同源）。**批内最大勘误**：CC6-4 经活体探针实证**不是 CSS 缺陷**——触感缩放（scale 0.97）一直在位且真实渲染，红因是 Playwright/Chromium 下 `#canvas-panel` 子树 `getComputedStyle().transform` 恒谎报 `'none'`（断言通道失真）；修法 = 断言通道换 `computedStyleMap()` 真值 + rect 收缩比交叉验证，顺补 `:active` 按压缩放档（真触感增强，作者令口径）。busy 断连补发轨按闸形推演落地为「补触发 drain + 确认网换新窗口基点重挂」双腿，e2e 实证：断连吞终态 → 重连不抢跑（投递未决闸守恒）→ 20s 幻影收口 → 恰投一帧。

## 2. 逐件账

### ②26/⑥38 busy 排队项断连补发轨（input.js）

- **根因链**：busy 排队项不带 `offline` 标记（两轨隔离设计，input.js:740 注），唯一投递挂点 = 终态帧族（done/sessionBusy{false} → clearBusyFor → scheduleQueueDrain）。WS 断连期终态帧全数被吞 ⇒ 重连后无人触发；且引擎 onopen 引导帧族**不重放** per-session busy 态（WsHub 无 busy 查询帧，仅轮次起止广播）⇒ 客户端 busy 镜像/开轮事实陈旧，drain 闸 ②/③ 按陈旧事实把一切补触发挡到「永远不来的终态」上（`lastQueueDispatchAt=0` 的用户直发形态下必死锁）。
- **修法**（`src/main/resources/web/js/input.js` 新增 `rearmBusyQueueDrains`，挂 `onReconnect`，flushOfflineQueue 之后）：对含 busy 轨项（非 offline、非 failed）的会话 ① `scheduleQueueDrain` 补触发（正确性闸全在 main.js `armQueueDrainTimer` 内裁决，queue-one-per-turn 语义零放松）；② `turnStartTimes` 推进到 now + `armTurnConfirm` 重挂确认网——断连**前**的开轮事实不再计入对账，引擎真在跑的轮由重连后的流帧/心跳持续记账（网不误杀），真空闲轮 20s 无新事实 ⇒ 幻影分支收 busy + 落权威无-turn 戳 + drain（与派发幻影腿 main.js:518 同款收口）。纯 offline 轨会话不进本腿（双轨不混）。
- **spec**：`tests/f1-send-queue.spec.mjs` 新增第⑤腿 6 断言（桩持轮 `holdTurn`：5a 持轮首条出线终态扣发 / 5b busy 轨入队无 offline 标 / **5c 重连即刺不抢跑** / 5d 20s 幻影收口后恰投一帧 / 5e 排空 / 5f 源钉）。邻域回归 busy-lifecycle、queue-idle-flush、queue-one-per-turn、queuebar-informative、send-bubble-independence、resend-dedup 全绿。

### ②27 初载骨架卡（historyIndicators + sidebar + main + chat.css）

- **挂载点**：`resetChatForActiveSession`（sidebar.js:2175，boot 首进/会话切换/sessionList 重置三路全经此）清空聊天区后，**无缓冲流内容**时 `showChatSkeleton()`；有 thinking/text 缓冲的会话直渲真内容不出骨架（占位不与真内容同帧闪现）。
- **让位点**：main.js historyPage 处理器 `hideHistoryLoader()` 旁同帧 `hideChatSkeleton()`（与渲染同任务帧 ⇒ 让位零跳变）；`clearHistoryIndicators` 收口同摘。
- **形态**：`.chat-skeleton` 4 条渐变占位泡（AI 左×3 / 用户右×1、16px 圆角族、80% 宽上限），AI 泡 sapphire 弱 tint 扫 `--color-bubble-ai` 底、用户泡 `--color-bubble-user` 底，`prefers-reduced-motion` 降静态；aria-hidden + 纯呈现件。截图证据：`evidence/20261025_mobile-p1-skeleton/mobile-skeleton-visible.png`（在场态）/ `mobile-history-loaded.png`（让位后真内容，气泡色对齐实证）。
- **spec**：新增 `tests/chat-skeleton.spec.mjs` 16 断言（boot 在场/形 4+1/让位/真行顶上；空历史即摘不滞留；缓冲恢复腿零骨架；手机层 375 等效链；源面四钉）。施工注：单会话架构下会话侧栏列表 DOM 已移除（sidebar.js:1928），切换走 `switchToSession` 模块入口驱动。

### CC6-4 触感缩放（勘误 + CSS 腿 + 断言通道）

- **活体探针实证**（复刻手机腿 + 规则匹配 walk + 内联/!important 注入对照 + 分深度边界）：长按态 `--head-scale` 计算值 0.97 正确在场、`computedStyleMap().get('transform')` = `translate3d(0,0,0) scale(0.97,0.97)` 与真实渲染一致（rect 收缩比 0.970 同证）；而 `getComputedStyle(pill).transform` 恒返 `'none'`——该失真覆盖 `#canvas-panel` 整个子树（新造裸 div、内联 transform、运行时 `!important` 注入全部谎报），非动画/backdrop-filter/position:fixed 所致，body 直接子级正常。**批前审计「CSS 触感规则与现选择器失配」的判读不成立**。
- **修法三件**：① 断言通道换真值（`computedStyleMap().toMatrix().a`）+ rect 收缩比（0.9<r<0.999）交叉验证，判定强度只增不减（canvas-cardhead-capsule.spec.mjs CC6-4）；② mobileLayer.css 补 `:active` 按压缩放档（0.97 + brightness，与 is-longpress/is-pressed 同档——触屏真实按压即刻有反馈，合成事件断言不触发 `:active` 故由 ① 通道判定）；③ 过渡沿用基座 `transition: transform 0.18s`（split.css:695）无需新值。
- **spec**：capsule 29/29（CC6-4 输出 `feel=true scale=0.970 rect=0.970 btn=true`）。

### spec 判据换代四连（红转绿，零删测试）

| spec | 旧判据（代际） | 新判据（现行语义） | 结果 |
|---|---|---|---|
| canvas-cardhead-capsule CC6-1 | 直滑下滑=下一张（批⑨旧语义） | **直滑下滑=收起整叠**（⑦令：canvas 收层、页签数据保留 n=2）+ 新增 CC6-1b 收起再开状态保留（活动卡 bravo 不变） | PASS |
| canvas-cardhead-capsule CC6-2 | 直滑上滑=上一张 | **直滑上滑=下一张**（⑦令保留上向，bravo→alpha） | PASS |
| mobile-p9-graph T1/T2/T3 | `.wiki-toolbar-status` 文案 / `.wx-zoom` 三玻璃珠+安全区 / `.wiki-toolbar-btn` 在场 | 控制面已迁左栏（8a2c703f9 作者令 2026-10-21）：图谱页=画布本体——工具条族/缩放珠页内**零存留**（防复活钉）+ pane 收编 + 画布满页 + 返回契约 `.mobile-back-btn` | 3/3 PASS |
| mobile-p11-usage T3 | `#usage-dashboard .mobile-back-injected`（ensureInjectedBack，②17 已退役为死代码） | 页面壳 `.mobile-back-btn` 单点 + 注入选择器零存留钉 | PASS |
| resend-dedup D3（顺手） | `content:\s*item\.text,\s*clientMessageId` 紧邻正则 | device-provenance 批（aefea8f77）帧中段插 `device:` 字段致正则失配（HEAD 实证存量红）——放宽为中段任意帧字段，仍钉死幂等键在挂 | PASS |

### 验收面（P1-5）

| 路 | 批前 | 批后 |
|---|---|---|
| capsule node-runner（canvas-cardhead-capsule） | 25 PASS / 3 FAIL | **29 PASS / 0 FAIL**（+CC6-1b） |
| Playwright 手机族（mobile-* 14 件 + bg-merged-panel） | 35/4（审计日 39 test 口径） | **55/55 全绿**（族随批次自然增长，p9×3/p11×3 换代后即绿） |
| 排队族邻域六件（busy-lifecycle / queue-idle-flush / queue-one-per-turn / queuebar-informative / send-bubble-independence / resend-dedup） | — | **ALL PASS ×6** |
| 新增 chat-skeleton.spec.mjs | — | **16/16** |
| checkJs（scripts/check-js-types.mjs） | 基线 257 + nearpath 3 | **我方触碰文件零错误贡献**（260 = 257 + nearpath 3 既有申报，`20261024_164337` 批同判） |

审计 §4 所列 7 红：CC6-1/2/4 + p9×3 + p11-T3 六红本批清零；canvas-sheet-T3 审计日已实证自愈（本次家族跑含 mobile-canvas-sheet 全绿）。**两路全绿达标。**

## 3. 边界与未做

- P2（①7 桌面会话头横纵裁断、navController ensureInjectedBack 死代码清理——p11-T3 换代后已可删）/ P3（真机 A/B、纸夹×AskUser 联动、③21 手感确认）候作者拍板，不在本批。
- ⑥39 收口（作者真机刷新复验）仍待真机侧确认，码面证据已闭合（mobile-topline-clear 3/3 绿）。
- checkJs 门面 260 的 3 错全部来自未跟踪 `nearpath.js`（其他在活会话在制件），本批零新增、未触碰该文件。
- `evidence/20261023_queue-restore/after/*.png` 因本批跑排队系 spec 被自动重摄（字节漂移），已还原不混入本批 diff。

## 4. 文件清单

**源码（6）**：`js/input.js`（reconnect busy 轨双腿）、`js/main/historyIndicators.js`（骨架组件族）、`js/sidebar.js`（挂载腿）、`js/main.js`（摘除腿）、`css/chat.css`（骨架样式块）、`css/mobileLayer.css`（:active 触感档）。
**spec（6）**：`tests/chat-skeleton.spec.mjs`（新增 16 断言）、`tests/canvas-cardhead-capsule.spec.mjs`（CC6 换代+1b/断言通道）、`tests/mobile-p9-graph.spec.mjs`（×3）、`tests/mobile-p11-usage.spec.mjs`（T3）、`tests/f1-send-queue.spec.mjs`（⑤腿 6 断言 + 桩持轮旗）、`tests/resend-dedup.spec.mjs`（D3 判据换代）。
**证据**：`evidence/20261025_mobile-p1-skeleton/`（骨架在场/让位双截图）。

## 5. 关键假设

- busy 断连补发的 20s 幻影判定延迟 = 沿用确认网既有预算（turnConfirmMs 默认 20000），与 queue-one-per-turn 作者裁定的「事实闸非计时器」相容：投递跟随回合边界，20s 只在「引擎永不开轮/终态被吞且引擎已空闲」时走满，真轮由流帧/心跳/终态帧更早收口。
- 骨架卡口径取「初次进会话且无缓冲流内容」——boot 首进/切换/重连重拉全盖；有缓冲内容的会话（重连恢复场景）直渲真内容不出骨架，避免占位与真内容同帧。
- `:active` 触感档在胶囊内子钮（关闭/合并钮）按压时同样整泡缩放——与桌面 is-pressed 的「钮群直通」分支存在差异，取整面反馈口径（iOS 同律）；若作者要钮群免缩可后续加 `:has(button:active)` 排除腿。
