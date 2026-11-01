---
type: batch
status: published
date: 2026-11-01
scope: msg 身份牌取数面剥离渠道/平台来源注记（只留作者原生输入）＋主会话用户气泡同源剥离
---

# msg 牌剥渠道前缀批（作者令 2026-11-01 22:57）——牌窗只留最原生输入

作者令（逐字）：「以下消息来自网页控制台（渠道 console）· 设备：Mashiros-MacBook-Pro（已登记） 这种信息 在使用 msg 身份牌的时候应该被剥离 只保留最原生的输入」。

关联批：[[20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb]]（同 wave 邻位，零文件交集）；[[20261101_225500_dock-avatar-edge-unify__delegate-nebflow-9770a0a7]]（在飞批邻位，零交集）。上游机制：`msgchip` 批（msg 身份牌带短名引用，2026-11-01 20:17）——本批只改它的**牌文取数面**，铸牌/牌号/消歧零触碰。

## 一、问题与修前事实

渠道注入在**模型面**的文本头部带一段传输层注记（`BridgeOrigin.marker` / `DeviceProvenance.marker` 的 `<system-reminder>` 块、`NodeEngineContract.nodeMessageHeader` 的 `[NODE-MESSAGE]` 头、`MailTool.withChainAnnotation` 的 `[mail chainId:]` 头、附件指针行）。铸牌存的是**模型面全文**（`MsgTokenRegistry.mint` → `MsgTokenMapping.text`），所以牌窗点开显示的是含注记的原文。

现读读数（宿主机真实历史，只读盘点）：

| 面 | 命中 | 出处样本 |
|---|---|---|
| `.ui.json` 中带 `<system-reminder>以下消息来自…` 的用户行 | **5 处**（含飞书 4 条 + 控制台 4 条同形，另 1 条为作者把前缀抄进正文的反例） | `5cc7590a-…ui.json` 行 189/193/197/198；`d7c3cb54-…ui.json` 行 780 |
| `msg-tokens.jsonl` 带控制台前缀的 mapping | **17 条** | `d7c3cb54-…/msg-tokens.jsonl` |
| `.ui.json` 以 `[NODE-MESSAGE]` 开头的注入行 | **1421 行** | `node-*`/`delegate-*` 会话 |
| `.ui.json` 以 `[mail chainId:` 开头的注入行 | **12286 行** | 同上 |
| 附件指针 `[用户附加文件:` 的**用户轮牌文** | 0（附件帧不铸牌——`mintUserMsgToken` 的 `blocks.forall(_.isEmpty)` 闸） | 仅模型面出现 |

后两条读数是「范围裁量」的依据（见 §三）：注入气泡是引擎自己的投递面（带 sender/header 行），不是作者输入。

## 二、实现：一个清洗器，两个显示面

**新建单点** `web/js/channelProvenance.js`（185 行，纯函数、零依赖、绝不抛）：

```js
stripChannelProvenance(raw) -> { text, attachments, channels, senders, devices, stripped }
```

判据 = **位置 + 结构**，绝不按关键词（作者令硬约束 ④）：

1. **来源块**：只有文首的 `<system-reminder>` 块，且块体**恰好一行**、整行匹配模板
   `^以下消息来自(.+?)（(?:社交接口 · )?渠道 ([A-Za-z0-9_.-]+)）(?:· (发送方|设备)：(.*))?$`
   ⇒ 剥块 + 紧随的一个空行。块体多行（真 reminder：Merge turn / 冻结 / 各类引擎提醒）⇒ 一字不动。
2. **注入头行**（可叠加，逐层剥）：`[NODE-MESSAGE] …` 整行、`[mail chainId: …]` 整行、
   `[NODE-REPORT-REMINDER]` 只剥标签词（其正文是给 agent 的说明，不属注记骨架）。
3. **附件整行指针**：`[用户附加图片|文件: <path>]`、`[attachment lost: <path>]`、
   `[图片|文件|视频] 已存至 <path>…，可用 Read 查看`（**带尾句才认**——残行由 `social-media-unify` B4 钉为「保持文本」）、
   `[image|file: <name> (path unsafe|保存失败|无数据)]` ⇒ 剥行并计数。
4. **绝不掏空兜底**：剥完为空且无附件 ⇒ 回退原文（两个面共用同一条政策）。

**幂等**：剥过的文本不含上述头部结构 ⇒ 二次调用零变化（两条取数腿先后经过同一渲染点不会双剥，spec T13c 钉死）。

**接入点（三个，全部调同一个函数）**：

| 落点 | 文件 | 语义 |
|---|---|---|
| 牌窗正文 | `web/js/chat/msgTokenChip.js` `renderPopBody` | 两条取数腿（本地 DOM 对位 / 引擎端点）在此**汇合** ⇒ 一份清洗覆盖两腿 |
| 用户气泡（live） | `web/js/chat.js` `renderUserBubble` | 剥后再进 `appendTextWithMentions` |
| 用户气泡（历史恢复两腿） | `web/js/persistence.js` `restoreFromStorage` / `restoreFromBackendHistory` | 剥后的文本同时供气泡、引用菜单、复制钮、技能标签内容使用（显示/引用/复制三面同文） |

**附件指示**（要求 ①）：牌窗另起 `.msg-token-pop-attach` 节点显示「含 {n} 个附件」
（zh/en 双键 `chat.msgTokenAttachNote`），**在正文节点之外** ⇒ `body.textContent` 仍是纯正文，
路径/文件名噪声零出现（spec T14 三条钉：正文无路径、指示在 body 之外、指示自身无路径）。

## 三、气泡面也剥：做了，且申报属性差异

作者令 ④ 给了两个选项（保守=只改牌窗）。选择：**做**，理由三条：

1. 作者**既有裁定**就是「system reminder 永远不显示在会话中」（`BridgeOrigin.faces` 头注逐字引作者原话）；存量飞书入站行破例把块落进了 `.ui.json`（5 行），显示上本就是**漂移**——剥 = 回到原裁定，不是新增口径。
2. 同一个清洗器顺手接在 `renderUserBubble` + 两条恢复腿上，零新增实现面（违反「不写第二套」的成本为零）。
3. 读数支持：带注记的**用户行**全库仅 5 处，视觉变更面极小。

**申报的属性差异（牌窗与气泡不同档，非疏漏）**：

| 注记族 | 牌窗（msg popover） | 主会话气泡 |
|---|---|---|
| `<system-reminder>` 渠道来源块（console/feishu） | 剥 | 剥（同源） |
| 裸前缀行 / 作者正文里的同类句 | 不剥（结构不符） | 不剥 |
| 附件整行指针 | 剥 + 「含 N 个附件」指示 | 剥（媒体行/附件卡本就另起一行，不新增指示） |
| `[NODE-MESSAGE]` / `[mail chainId:]` / `[NODE-REPORT-REMINDER]` 头 | **剥** | **不动** |

差异理由：后一族的载体是**注入气泡**（`buildInjectedRow`，带 `sender`/`header` 四段式行，视觉上本就是引擎投递面而不是作者气泡），全库 1421 / 12286 行——把它们改成整行渲染是另一个批的视觉工程；作者令点名的是「msg 身份牌」。牌窗之所以连这一族也剥：牌窗**只装真人输入**（铸牌条件 `fromUser=true` 且无 blocks），一个牌窗里出现分发器注记永远是噪声。

## 四、后端面：零改动（并说明为什么不镜像）

**引擎 `GET /api/sessions/msg-token/{core}` 返回的 `text` 保持原样**，`MsgTokenRegistry` 一字未动。理由：

- 牌窗两条取数腿在 `renderPopBody` **同一个渲染点**汇合 ⇒ 一份 JS 实现即可覆盖全部取数路径，不存在「后端不洗 ⇒ 漏」的口子；
- 若在 Scala 侧镜像同一套正则，就是**第二套实现**（作者令 ② 明确不要），且必然引入 JS/Scala 漂移面；
- 更关键：`MsgTokenRegistry.resolve` 的消解面（`{{msg:…}}` → 映射全文，五个投递组包点共用）**必须**拿到原文——清洗一旦进引擎，模型上下文与映射本体都会被篡改。

## 五、验收证据

`tests/msg-token-chip.spec.mjs` 新增 T12–T16（双引擎 chromium + webkit）：

- **T12 正向**：真实历史消息逐字样本 7 例（控制台设备前缀 / 飞书社交前缀 / `[NODE-MESSAGE]` 头 / `[mail chainId:]` 头 / 两者叠加 / 附件行 / 存量媒体线）⇒ 牌窗正文 = 剥后原话逐字、注记零残留、无路径噪声；两条取数腿交叉幂等。
- **T13 反例**（作者令硬约束）：①文首**裸**「以下消息来自…」句（真实作者行 `d7c3cb54` 行 780）不剥；②正文**中段**引用块不剥（真实历史 `delegate-nebflow-9b828eae` 行 5 形态）；③**多行** `<system-reminder>`（真 reminder）不剥；④无括号句式不剥 —— 直探 + 端到端双钉，并钉剥离幂等。
- **T14 附件**：正文无路径、指示 = 「含 1 个附件」、指示在 `body` 之外、指示自身不含路径。
- **T15 气泡同源**：live `renderUserBubble` + 历史恢复腿 `restoreFromStorage` 双面读数（剥前缀 / 不误剥）。
- **T16 实拍对照**：牌窗**清洗前（raw 壳）/ 清洗后（真渲染）**双帧 × 暗/亮 × 桌面 1440 / 手机 375（`chromium-*` + `webkit-*` 各 12 张），另附「附件指示」实拍 8 张。

结果：**345 PASS / 0 FAIL**（双引擎全矩阵）。实拍存证：`evidence/20261102_msgchip-provenance/`（40 张 PNG，含前后对照）。

## 六、门禁读数

| 门 | 结果 |
|---|---|
| `node scripts/build-web.mjs` | OK —— 210 sources 全在 bundle graph（orphans 0），新文件被真实 import |
| `node scripts/check-js-types.mjs` | **红，但与本批无关**：基线 `tests/type-baseline.json` 现读 `total:0`（main 上同一读数即红，25 错散在 6 个未触碰文件）。本批 touched 文件的 tsc 错误集与 main **逐条 diff 相同**（`msgTokenChip.js` 的 2 条为新文件零基线 + 存量 2 条，`channelProvenance.js` / `chat.js` / `persistence.js` 零新错） |
| `node scripts/check-msgstyle-single-source.mjs` | PASS（7 规则，37 CSS）——本批 CSS 只新增 `.msg-token-pop-attach` 的 muted 小字档，无新增共享字面量 |
| `node scripts/check-circular.mjs` | 与 main 输出**逐字相同**（存量 SCC：mobile/navController ↔ taskPanel） |
| i18n parity | zh 2278 / en 2271，差集与 main **完全一致**（本批新增 1 键双侧同加） |
| 相邻 spec 回归 | `social-media-unify` 6/6、`askuser-history-attach` 10/10、`user-attach-canvas-open` 7/7、`send-bubble-independence` ALL PASS；`quote-message` 13/16 与 `evfmt-rc-header-persist` 1/3 —— 两条与 main 上**逐条相同**（存量红，非本批） |

`quote-message` 的存量红在改前改后完全一致（B2-B4 / B5 / B11 与 T1/T2），已用 main 树复跑对照确认。

## 七、边界与未做

- **不动**：铸牌逻辑、牌面语法、消解面、注入气泡渲染（`buildInjectedRow`）、引擎端点应答形状。
- **不改存量数据**：`.ui.json` / `msg-tokens.jsonl` 里的原文一字不改（清洗只在显示面；换设备/旧版本回看仍可取原文）。
- **未做**：`delegate-nebflow-73e50772` 提到的 stand-recall 索引侧同类前缀（那批已独立收口，索引面按 skip 前缀处理，与本显示面无关）。
- **已知存量红**：`check-js-types`（基线被清空）、`quote-message` 3 例、`evfmt-rc-header-persist` 2 例、`check-circular` 1 对 SCC、wiki-docs R1 1 条（`20261101_212200_mobile-dock-capsule` 缺文档头元数据块，见 [[glossary#^front-matter]]）—— 全部改前即红，与 main 读数逐条相同。
