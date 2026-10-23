# 输入消息排队回退批（恢复 2026-10-21 设计）——批报告

作者令（2026-10-23 09:14，逐字）：「我们的输入消息排队功能采用我们之前的。大概两天以前的设计。现在的设计不好用」

## 1. 考古定界

- **BASE = `383f4e197`**（2026-10-21 23:36:27 +0800，Merge branch 'delegate/delegate-nebflow-a4657889'）——main 上 2026-10-21 23:59 前最后一个提交。
- BASE 之后触碰「排队面」的提交清单（131 个提交中筛出）：

| 提交 | 时间 | 排队面触碰 |
|---|---|---|
| `788765e6c` | 10-22 11:41 | #714 聊天输入两缺陷批：busy 排队腿补乐观气泡（入队即上屏）+ `item.ts` 数据钉；用户附件卡 chip 族（非队列面） |
| `207d43fa1`/`1319606e1` | 10-22 11:59 | 对话层活性流式批：conn 横幅计数、flipOfflineMark 补渲收口（碰 input.js 队列腿，非排队呈现面） |
| `7e2b8bd8f` | 10-22 12:22 | 排队信息量批：头行活秒表 + 位次标 + 已收到双呈现 |
| `9cdfb54c1` | 10-22 18:40 | 排队呈现回退批：队列条只留头行、行级明细退役、动作进气泡右键菜单 |

- 前置事实：**qattachvault 载荷库早于 BASE 已在位**（BASE chatQueue.js 已含失败注记/派发闸挂点），无需数据层裁决保留——回 BASE 即完整含它。

## 2. 恢复清单（UX 交互回 BASE）

| 文件 | 恢复方式 | 内容 |
|---|---|---|
| `src/main/resources/web/js/chatQueue.js` | 整文件 checkout BASE（BASE 后仅排队批触碰，核清） | 行级明细全面回归：折叠钮、行文本（点击展开）、附件缩略、qattachvault 失败注记、立即发送/撤回/移除行按钮、拖拽排序；头行回归「排队中 (N)」（秒表/条数/失败计数退役） |
| `src/main/resources/web/css/input.css` | 整文件 checkout BASE（同上核清） | `.queue-item*` 行族样式 + 折叠 + 失败红左缘回归；`.queue-bar-elapsed/.queue-bar-failed` 单行态样式退役 |
| `src/main/resources/web/js/input.js` | 手术式 | busy 排队腿回 BASE：`queueMessage(v, text, v.pendingAttachments)` 直入队，**零乐观气泡零 rendered**；`refreshQueue` 回三 handler 形态；删 `runQueuedAction`/`queuedItemByClientId`（右键菜单执行面） |
| `src/main/resources/web/js/msgQuote.js` | 手术式 | 右键菜单队列动作三件（立即发送/撤回/移除）退役，菜单回归 复制/引用 单形态 |
| `src/main/resources/web/js/chat.js` | 手术式（注释） | `createPendingSendMark`/`renderUserBubble` 文档注释回 BASE 语义（busy 排队腿零气泡；`pending:true` = 离线腿唯一形态）；代码仅存 `=== true` 防御（与 BASE 行为等价） |
| `locales/zh-CN.js`+`en.js` | 手术式 | 退役 10-22 批键：`chatQueue.waitingTurn/queuedCount/turnElapsed/failedCount`、`chat.queueRecall`；恢复 BASE 键：`chatQueue.dragReorder/clickExpand/clickCollapse`（撤回钮 title 维持字面 fallback 口径，`chat.recall` 键从不存在的口径不变） |

## 3. 保留清单（非排队面/纯数据修复层，不回退）

- **数据层真值钉**（#714 批的纯数据件）：`queueMessage` 的 `ts: Date.now()`、`persistQueue` 落盘 `ts`、sendImmediate/drain 补渲气泡时间戳 = `item.ts`（修「排队项呈现时刻 ≠ 发送时刻」，与 UX 形态无关）。
- **对话层活性批**（207d43fa1，非排队呈现面）：`state.updateConnBanner` 三处接线、flipOfflineMark 落空补渲收口（修「刷新后已受理消息凭空消失」，作用于离线腿）。
- **离线腿（WS 未开）乐观气泡 + 「待送达」时钟标**：BASE 既有（f1-send-queue 2026-10-19），语义与 busy 排队互斥，原样保留。
- **附件卡 chip 族**（#714 B 腿）、**diff 拆面板死 import 摘除**（chat.js）、**qattachvault 全部数据层与失败闸**：非排队面/早于 BASE，不动。
- busy/冻结/frozen 豁免、resend-dedup 幂等、cap100 溢出告警等发送主链：未触碰。

## 4. spec 族换代

| spec | 处置 | 结果 |
|---|---|---|
| `tests/busy-queue-ack.spec.mjs` | **删除**——钉的是「已收到」确认标 + 头行秒表 + 右键队列动作三张已退役面孔，BASE 无对应物；其余值被重写后的 queuebar/send-bubble 两 spec 覆盖 | — |
| `tests/queuebar-informative.spec.mjs` | 重写为 BASE 排队条契约（Q1-Q9 动态亮暗 + S1-S8 静态源钉：busy 腿零气泡、三 handler、键集双向钉） | ALL PASS |
| `tests/send-bubble-independence.spec.mjs` | 重写为 BASE 反并泡语义（busy 零气泡→排队条行独立→drain 逐条一行一气泡；保留反并泡本体与 ts 真值钉） | ALL PASS |
| `tests/msgqueue-recall-attach.spec.mjs` | checkout BASE（9cdfb54c1 重锚撤销） | 6 passed |
| `tests/msgqueue-image-mime.spec.mjs` | checkout BASE | 5 passed |
| `tests/queue-image-refresh.spec.mjs` | checkout BASE | ALL PASS |
| `tests/chatinput-two-defects-shots.mjs` | A 腿断言改 BASE 语义（busy=排队条行×2 → drain=两独立气泡）；B 腿（附件 chip）不动 | ALL PASS |

## 5. 验证输出

- **本族回归**：queuebar-informative ALL PASS（亮暗双跑）· send-bubble-independence ALL PASS · chatinput-two-defects-shots ALL PASS · queue-image-refresh ALL PASS · msgqueue-recall-attach 6/6 · msgqueue-image-mime 5/5。
- **邻族回归**：f1-send-queue ALL PASS · resend-dedup ALL PASS · busy-lifecycle ALL PASS · attach-bubble-unify 13/13。
- **checkJs**：`node scripts/check-js-types.mjs` → `257 errors (baseline 257)` PASS（BASE chatQueue 行族带回 3 条基线内既有告警，冻结基线内，无新文件新错）。
- **check-circular**：worktree 与 main HEAD 输出一致（同一既有 SCC[3]：popArtifacts/reference/msgQuote——main 既有状态，非本批引入，零回归）。
- **截图证据**（`evidence/20261023_queue-restore/`）：`after/Q-queuebar-rows-{light,dark}(-closeup).png` ×4（busy 中两条排队：头行「排队中 (1)」+ 行文本 + 立即发送/撤回/移除钮 + 折叠钮，恰为作者验收场景「busy 时输入第二条→出现在队列→当前回复完成后自动发送」的排队态与 drain 后态）；`twodefects/A-two-sends-{busy,after-drain}-dark.png`（排队条行态 + drain 后双气泡态）。已目检 light closeup：行级明细形态正确。

## 6. 边界红线自查

只退「输入消息排队」呈现面：乐观气泡的非队列面（附件卡 chip）、附件发送链、重发去重、busy/frozen 语义、离线待送达腿、conn 横幅、workline 秒表（非排队条的，未触碰）全部原样。locales 只动排队键集合。主 checkout 无残留（一次误写主仓路径已全量搬回 worktree 并还原，主仓 src/tests 已核干净）。

## 7. 假设与未证项

- 「大概两天以前的设计」取 2026-10-21 当天（BASE=10-21 23:36 后的 main 形态）——与任务步骤 1 的定界指令一致。
- check-circular 的 main HEAD 既有 SCC[3] 未在本批处理（非排队面、非本批引入）；若作者要修另开一批。
- busy-queue-ack.spec.mjs 删除是换代裁决（其四钉全部描述已退役面孔）；若作者要保留空壳档可从本批提交的 parent 恢复。
