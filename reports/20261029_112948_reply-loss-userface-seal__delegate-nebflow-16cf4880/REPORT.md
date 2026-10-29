# 回复丢失告警·用户可见面摘除（过程态封存批）REPORT

- 日期：2026-10-29 11:29 · 会话：delegate-nebflow-16cf4880
- 触发：作者 2026-10-29 11:11 console 逐字「这个警告我不是让隐藏吗？回复丢失告警：bridge-session-dead ×1（将自动补投）」——作者此前已有隐藏意图，本批落死并钉牢。
- 判定（作者令）：过程性自愈状态（bridge 会话死亡→自动补投、屏障扣留→回合边界自动 drain）用户无杠杆 ⇒ **用户界面零显示**，降级纯引擎面（日志/JSON/WS 帧，全部未动）；仅**终态失败**（补投也丢、催注穷尽）保留用户提示。

## 改动面（4 文件 + 1 新 spec）

| 文件 | 改动 |
|---|---|
| `src/main/resources/web/js/socialPanel.js` | `cardHint()` 摘除健康面分支（原 759-762 行：`cfg.health.strikes` → `social.hint.health` 渲染于 `.social-card-hint` 告警行）——回复丢失 strike（bridge-dead 族）不再渲染任何渠道卡提示；注释记录作者令与去向 |
| `src/main/resources/web/js/locales/zh-CN.js` | 退役 `'social.hint.health'`（回复丢失告警…将自动补投）、`'heldReceipts.held'`（屏障扣留过程态）；留退役注释（不含被钉字面量） |
| `src/main/resources/web/js/locales/en.js` | 同上双语（`Reply-loss alert…auto-redelivery armed`、`receipt(s) held by the sub-agent barrier`） |
| `src/main/resources/web/js/main.js` | `renderHeldReceiptsCapsule()`：胶囊只渲染**终态** unread（催注穷尽），held 过程态零显示（计数、title 均不再含 held）；`state.sessionHeldReceipts` 记账保留（帧面不动，仅渲染面收口） |
| `src/test/scala/nebflow/gateway/UserFaceProcessStateSealSpec.scala` | **spec 钉（新增）**：①locale 无两键键定义；②整棵 web 文本资源树零出现 5 个过程文案 token（回复丢失告警/Reply-loss alert/将自动补投/auto-redelivery armed/bridge-session-dead，含注释）；③渲染面无 `cfg.health`/`social.hint.health`/`heldReceipts.held` 引用；④**终态保留钉**：`heldReceipts.unread` 双语言仍在且仍被 main.js 使用（防未来清扫误杀终态面） |

## 引擎面（未动，可查证）

- 插件日志：`WeixinIlinkBridgePlugin.scala` 探测/升级/回绿腿的 ERROR/WARN 日志（含 `bridge session DEAD` 升级行）原样。
- `channels[].health` JSON 面：`writeChannelHealth`/`channelsJson`（GET `/api/social/channels`，`PresenceRoutes.scala:1576`）原样——引擎可查，无 WS 推送，浏览器侧已无读者。
- `heldReceipts` WS 帧（hold/drain/flush + watchdog unread 广播）与前端记账原样。
- **确需用户知晓的失败终态既有面保留**：bridge 死亡的扫码重绑指引（bridgeDeadNotice）仍发微信会话（需作者扫码，补投救不了）；屏障催注穷尽（重试已穷尽）仍由胶囊 unread 行承载。

## 同族清扫清单（逐一过判）

| 候选 | 面 | 判定 |
|---|---|---|
| `social.hint.health` 渠道卡 hint（两种 strike reason：bridge-session-dead、no-return-address 同走此分支） | 社交渠道卡告警行 | **摘除**（主 subject，作者令直指） |
| `heldReceipts.held`（被子代理屏障扣留）胶囊计数+title | 头部胶囊 | **摘除过程态**（同判：自动 drain、无杠杆）；unread 终态保留 |
| `heldReceipts.unread`（重试已穷尽） | 头部胶囊 title | **保留**（终态失败提示，作者令允许） |
| bridgeDeadNotice 重绑指引 | 微信会话（非本 console UI） | **保留**（失败终态且需用户动作——扫码） |
| `messages.js` W13 补投队列（会话缓存缺失帧刷新后补投） | console.warn + 内部队列 | **不动**（无 UI 面，纯日志/自愈，本就符合判） |
| `fm-barrier-hint`（Flow Map 节点卡 barrier ×N） | Flow Map 拓扑卡 | **保留**（拓扑结构可视化，非告警条/通知；作者直接操作引擎的图面） |
| `bgAgents.js` barrier 显示 | 子代理下拉 | **早已按既有作者裁定隐藏**（注释「User rulings locked in the spec: barrier hidden」），未触碰 |

## 与 #1049（force-drain 补投批）的边界

- #1049 = `2474349c6`（已在 origin/main）：投递屏障扣留回执缺陷直修批，D1 改的是 **agent 会话面** force-drain 补投气泡的可见性（`emitForceDrainVisibility` 逐条 `[source·eventType·已等待N分钟]` 注入 LLM reminder），D2-D4 是 actor 台账/恢复/persist 调度面——**与本批零文件交集**（本批全部在 `web/` 资源 + 新 spec）。
- 本批主 subject（渠道卡健康 hint 的「回复丢失告警」）不在 #1049 brief 内；胶囊（`#held-receipts-indicator`，2026-10-21 可见化批引入）也不在 #1049 触碰面，由本批按同判收口过程态。
- fetch 核对：本分支领先 origin/main 13 commit、落后 0，#1049 已在 main，无覆盖冲突。

## 验证

- `sbt testOnly nebflow.gateway.UserFaceProcessStateSealSpec nebflow.gateway.WebJsModuleSyntaxSpec`（封存钉 + JS 语法/链路闸）——结果见提交时点；web 树 5 token grep 复核 0 命中。
- 服务端零触碰：本批无 Scala 主代码改动，`WeixinIlinkBridgeSpec` 健康面断言（服务端 strike 语义）不受影响。

## 假设

- 作者令「这类告警」覆盖全部过程性自愈提示（含 bridge-dead 与 no-return-address 两种 strike reason、胶囊 held 态）；「失败终态」= 补投也丢（催注穷尽 unread）与 bridge 死亡需人工扫码——两者用户提示保留。
- Flow Map 的 barrier ×N 拓扑芯片不属「告警」（是作者操作面的结构信息），判定保留并留档；若作者另有裁定，摘除点在 `fmNodeCard.js:370` 一行。
