# 「压缩中」可见指示拆除 · 兑现作者 2026-10-09 令①（compaction-pill-remove 批）

**判定（三行）**

1. 令①「『压缩中』可见指示列为拆除项」本期落地：活动起卡/胶囊的**全部 UI 呈现面**拆除——chat 起卡（renderCompactStartCard + spinner + active 态样式 + `chat.compactingCard` 双语键）、侧栏会话行压缩 spinner、侧栏文件夹行压缩 spinner（元素 + CSS + `compactPulse` 帧 + `getSessionStatusClass`/`getFolderStatusClass` 的 compacting 分支）。legacy / §E-1 回落 / 手动 / 非根全覆盖——呈现点已不存在，任一触发种类都不再起卡。
2. **CompactStart 事件流按「最小拆除原则」保留**：核查证实事件另有功能消费者——前端 `setCompacting` 供输入闸（input.js `isBusy = busy || compacting`）与排队闸（main.js 队列 drain `!compactingSessionIds.has(sid)`），`TERMINAL_MSG_TYPES` 保后台会话帧可达，`resetStreamTimeout` 喂看门，后端 WebSocketRoutes 落盘历史记录；故只拆 UI 呈现腿、发出口 `AgentSessionExecution.scala:359-370` 与事件定义零改动（后端 diff = 0）。审计建议 C 的 silent 位**不需要**：呈现腿全拆已使一切触发种类静默，且零后端管线改动。
3. 完成态零改动实证：完成/失败卡照出，完成卡「· 用时 Ns」后缀保留——起卡原先经 `data-start-ts` 供数，改由 `main.js` compactStart handler 盖 `view.compactStartTs` 供数（live 驱动实测 `上下文已压缩：184 → 1 条消息…· 用时 1s`）。历史重放面零改动：起条目照旧抑制（persistence.js `restoreCompactCardRow`），只渲染终态卡。

## 拆除面清单（全部前端，后端零改动）

| 文件 | 拆除 | 保留 |
|---|---|---|
| chat.js | `renderCompactStartCard`、`findActiveCompactCard`、`buildCompactCardRow` 的 spinner 分支与 startTs 参、done/fail 卡的 morph 分支 | done/error 卡、`buildCompactCardRow`（重放共用单源）、完成卡 elapsed（改读 `view.compactStartTs`） |
| main.js | import 与 `renderCompactStartCard(view)` 调用 | `setCompacting`、`resetStreamTimeout`、`saveMsg(chat.compacting)`（历史记录，重放腿照旧抑制）、新增 `view.compactStartTs` 盖戳 |
| chat.css | `.compact-card-spinner`、`.compact-card[data-state="active"]` | done/error/icon/label 全套 |
| sidebar.js | 会话行/文件夹行 `.status-compact-spinner` 元素、`getSessionStatusClass`/`getFolderStatusClass` compacting 分支、状态机注释 | `compactingSessionIds` 维护与读取（输入闸/排队闸/删除清理）、agent 行 `compressing` 簿记（本无 CSS，不可见） |
| sidebar.css | compacting 显示规则、压缩 spinner 规则、`compactPulse` 帧 | busy/attention/unread 全套 |
| zh-CN.js / en.js | `chat.compactingCard` 键 | `chat.compacting`（saveMsg + 后端 i18nKey 落盘 + 重放字面量匹配仍在用） |
| ws.js / Scala 全部 | — | 零改动（事件流保留） |

## 消费者核查（择面依据）

`CompactStart`/`compactStart`/`agentCompactStart` 全量清点：后端发出点唯一（AgentSessionExecution:359-370，起卡分支内部）；前端消费者 = main.js compactStart handler（功能闸 + 记录 + 起卡）与 ws.js TERMINAL_MSG_TYPES；**子代理变体 `agentCompactStart` 前端零消费者**（纯死线，但属 §G1 非根会话面，本批不碰）；取消闸无消费者（中断孤儿由 CompactionAbandon 批在服务端收口）。⇒ 按任务令「有其他消费者 ⇒ 只拆 UI 呈现腿留事件流」执行。

## 验收

- **本批验收 spec `tests/compaction-pill-remove.spec.mjs` 3/3 PASS**：
  - S-A 静态钉：拆除面（函数/键/spinner CSS/侧栏元素/compacting 分支）全无，事件流保留面（`onMessage('compactStart')`、`setCompacting(sid,true)`、`view.compactStartTs`、`saveMsg(t('chat.compacting'))`、TERMINAL 三帧、`chat.compacting` 键、done/fail 卡）全在；
  - S-B 重放腿（= 浏览器刷新路径 `restoreFromBackendHistory`）：起条目抑制、恰一张 done 卡、零 active 胶囊、零 spinner、零 error 回落卡、无用时后缀（历史无时间戳，与改前一致）；
  - S-C live 腿（WS 帧路径）：compactStart 零起卡、compactComplete 照出 done 卡**带「用时」后缀**（完成态零改动活证）。
- 回归：`checkJs` PASS（250 < 基线 273，-23）；esbuild 构建 PASS（163 源全图，orphans 0）；`tests/legacy-ui-retire.static.mjs` ALL PASS；`tests/stream-ux.spec.mjs` + `tests/msgqueue-recall-attach.spec.mjs` 22 passed；语法 `node --check` 5 文件全 OK。
- **dev 刷新实测**（S-B/S-C 即刷新/帧双腿，截图在本目录 `replay-no-pill-done-card.png` / `live-no-pill-done-card.png`）：刷新后完成行照出、无起卡；帧路径无感起卡、完成卡带用时。影子腿 §E-1 回落与今晨同形（CompactStart → 完成卡）——呈现点已不存在，回落腿**构造性**不再起卡；完成行照出由 S-C 直接覆盖。

## 存量红项（非本批，均已 HEAD 复现/stash 取证）

- `tests/history-replay-cards.spec.mjs` fixed 腿 :204 AskUser 卡 innerHTML 不等——**属性序/属性有无**差异（`style=""` 位置、live 侧 `option-confirm` 多一个 `disabled=""`），属 askUser 重放/活体路径存量分歧；stash 后对 pristine HEAD 复跑同样失败。本批压缩断言（该 spec :154-161、:191-197）全过。
- `tests/slash-clear-compact.spec.mjs` S1b——门键字面量 `stand_slash.enabled` ≠ spec 期望的 `nebflow_slash.enabled`，品牌键漂移存量；压缩相关 S1c/S3c/S3d 全 PASS。

## 边界与假设

- 后端零改动 ⇒ 「压缩机制本体零改动」红线满足；provider/分配面未触碰（影子三连败另案）。
- 真实影子腿失败回落无法在本批安全复现（需在作者活体上制造 provider 全挂 + 真压缩窗，属红线外操作）；以「呈现点不存在 ⇒ 构造性静默」+ 双腿实测覆盖，如作者要求实机回落复演，建议在影子腿修后另批做。
- `agentCompactStart` 死线（前端零消费者）仅登记不动，§G1 零漂移。
