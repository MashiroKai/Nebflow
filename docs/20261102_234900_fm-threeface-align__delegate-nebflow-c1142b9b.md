---
type: batch
status: published
date: 2026-11-02
scope: 好友/群/设备三面 composer 收口单组件（messages/fmComposer.js 新增）+ 叠卡「展开 N」钮与消息 footer 对齐主会话（messages.js / friends.css / popArtifacts.js），含三面输入框能力差异清单与双主题实拍、真录音、9 套回归证据
---

# fm 三面输入条对齐批 · 收口文档（作者令 2026-11-02）

关联：[[20261101_222500_composer-mobile-slim__delegate-nebflow-6e995fd9]]（上一批手机输入卡垂直收窄——同一「输入条几何」谱系，本批改的是**能力清单**与**桌面三窗装配**，两批作用域不重叠）、[[20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb]]（手机 dock 胶囊，本批输入条悬浮其上的同族宿主）。

## §0 令文与口径

作者令（本批三次表述合并）：

1. **主令**：「好友 / 群聊 / 设备三面的 composer 收口为**同一组件**（同布局、同能力、同行为）；现状**设备面板缺语音输入** ⇒ 以能力最全面的面为基准补齐，三面最终**能力清单一致**。」
2. **叠加批评 A**：叠卡（multimedia stack）的「展开 N」钮位置与主会话不同 ⇒ 改回与主会话逐字同款（同角、同边距、同形态），**fm 调用点不许传位置类 opts 覆盖**。
3. **叠加批评 B**：消息 footer（时间 + 分隔符 + 动作钮）参考主会话设计对齐——**同构同语言**。
4. **叠加批评 C**：输入条「三件套」——麦克风键 / 输入框 / 纸夹键 / 发送键的位序、几何、取色三面一致。

验收命题（本批的定义）：**三面之间逐格一致**（好友 = 群 = 设备，逐能力项、逐几何值、逐取色）；**主会话不是同一个实现**（主会话是 `input.js` + `input/composer.js` 全家桶），只共享视觉语言——主会话列的差异必须逐项写明原因，不得冒充一致。

## §1 改动清单

### §1.1 新增：`src/main/resources/web/js/messages/fmComposer.js`（1-197 行）

三面输入条的**唯一**装配点。导出 `buildFmComposer(opts)`（`fmComposer.js:49`），宿主只传钩子，不复制任何分流判据：

| 要点 | 行 | 说明 |
|---|---|---|
| 能力清单头注（本批口径单源） | 1-25 | ①文本 ②语音 ③附件 ④发送 ⑤停止态；并显式申报「与主会话非同一实现、只共享视觉语言」，差异表指向本文件 |
| `input.cfg-input.fm-input`，单行、`maxLength 2000` | 55-60 | 三面同款；形态与主会话 `textarea` 的差异在 §2 表里逐项写明 |
| 语音键 `.icon-btn.fm-mic-btn` + lucide `mic` + `aria-label/aria-pressed` | 74-84 | `setMicState` 单点：`recording`（listening/speaking）/ `processing` / `aria-pressed` 三态收口 |
| `stopVoice()`：丢弃未落定 interim 占位 → `processing` → `stopDictation()` | 85-97 | 与主窗 `stopVoice` 同一约定（`input.js:2044`） |
| 点击契约：开/停二态、STT 停用态 toast、锚点补分隔空格、interim 原位刷新、终稿落定 + 尾随空格 | 98-151 | 逐条对齐主窗 `startVoice` 契约；出错走 `toast`（禁 console-only） |
| 发送键 `.cfg-btn.cfg-btn-primary.fm-send-btn` | 157 | 沿用发送族 `:is()` 成员（`sapphire.css:326`），取色单源 |
| 附件键 `.icon-btn.dropbox-attach-btn.fm-attach-btn` + 隐藏 `input[type=file]` | 164-181 | `canAttach === false` ⇒ **不挂**假入口 |
| 裸 Enter 发送 + IME 组字守卫 | 187-194 | `bindImeGuard(input)` + `isImeComposing(e, input)` 短路后再判 Enter |

### §1.2 `src/main/resources/web/js/messages.js`（收口 + footer 对齐 + 叠卡标记）

| 位置 | 要点 |
|---|---|
| `messages.js:1394-1404` | `renderChatModal` 的输入条装配整体换血：`buildFmComposer({ canAttach: conv.kind === 'device' \|\| attachAvailable(conv), onSend: () => dispatchSend(conv), onFiles, onTextChange: syncComposerSend, toast: modalToast })`；删除 146 行内联装配（旧 `if (conv.kind !== 'device')` 分支 = 设备面缺语音的根因） |
| `messages.js:1416` | `modalEls` 的 `stopVoice` 改指 `composer.stopVoice`（设备面从此也有停止语义） |
| `messages.js:1455-1457` | 原 `doSend` + click/Enter 接线整体删除，只留分派单点注释（`dispatchSend` 仍是 `messages.js:4365` 一处） |
| `messages.js:3062-3068` | `bubbleEl` 时间文本换 `formatFooterTime(timeMs)`（原 `fmtTime`），并挂 `data-ts` / `data-ts-text` / `data-ts-text-format="day"` ⇒ 12/24 切换按主会话**同一分层 formatter** 就地重排；`bindTimeToggle` 改为恒绑 |
| `messages.js:3108-3112` | meta 新增**真节点** `.fm-msg-divider`（退役 `.fm-msg-actions::before` 伪元素）——DOM 序 `时间 → 分隔符 → 动作组` 与主会话 `duration-badge-time → duration-badge-divider → msg-copy` 同构 |
| `messages.js:1930` | `buildMediaStackGroup` 去掉 `fm-att-media` 类，只留 `fm-att` + `fm-att-stack` |
| `messages.js:1902-1910` | 该函数头注补「🔴 禁挂 `fm-att-media`」口径 + 根因（叠卡组被宿主卡根 `column` 规则压成竖排 ⇒ 展开钮掉到栈卡下方） |

### §1.3 `src/main/resources/web/css/friends.css`

| 位置 | 要点 |
|---|---|
| `friends.css:1054-1066` | `.fm-att.fm-att-media` 加**作用域钉**注释：本规则只覆盖平面媒体卡根，叠卡组不是本类（`flex-direction: column` 就是「展开钮位置不对」的根因） |
| `friends.css:1071-1082` | 叠卡宿主绑定段补口径：组件默认位 = 主会话逐字同款（钮在朝会话中轴一侧、垂直居中、盒间距 48px = margin 32 + gap 16）；`out` = `margin-right:32`，`in` = `order:3` + `margin-left:32` |
| `friends.css:1213-1219` | `.fm-msg-actions::before` 伪元素**整块退役**（死规则不留），改由 `.fm-msg-divider` 真节点承载（几何单源 `sapphire.css:865-872`） |
| `friends.css:1258-1263` | `.fm-msg-act.copy-failed` 取色 `--color-error` → `--status-error-icon`（对齐主会话 `chat.css:1065` `.msg-copy.copy-failed`，同 token 同失败色） |

### §1.4 `src/main/resources/web/js/popArtifacts.js`（注释口径，零行为）

`popArtifacts.js:635-648`：`buildMediaStack` 头注新增一条硬约束——**「展开 N」钮的位置不是 API 参数位**；默认位 = 主会话逐字同款，侧别镜像与 220×280 卡面几何全在 `chat.css` 单源，宿主只做同值镜像；`opts` **不得**长出位置类键（`pillSide` / 钮角位 / 边距覆盖），fm 调用点只传 `onOpen` + `cornerFor`。

## §2 三面输入框能力差异清单（本批点名要出的表）

图例：✅ = 有且三面逐格一致；➖ = 三面一致地**没有**（同属「三面零分叉」）；△ = 主会话有而三面无（原因必写）。
「三面」列合并写 = 该格好友 / 群 / 设备**逐格相同**（差异为零，这正是本批验收命题）。

| # | 能力项 | 好友面 | 群面 | 设备面 | 主会话 | 主会话差异原因 |
|---|---|---|---|---|---|---|
| 1 | 文本输入 | ✅ `input.cfg-input.fm-input` maxLength 2000（`fmComposer.js:55-60`） | ✅ 同左（同函数同实例化路径 `messages.js:1394`） | ✅ 同左（`messages.js:1397` 判据不再排除 device） | △ `textarea#input` rows=1（`index.html:884`） | 主会话要支持多行 prompt 与 slash 参数，`input` 单行装不下；三面是消息面单行语义 |
| 2 | 多行 | ➖ 单行 | ➖ | ➖ | △ 自动长高 ≤200px（`input.js:1849`、`input.js:1858` scrollHeight 封顶） | 三面消息正文按 Enter 即发，长文本走 shift 语义缺失是有意取舍；主会话 Enter 发送但需容纳多行任务描述 |
| 3 | IME 组字守卫 | ✅ `bindImeGuard` + `isImeComposing` 短路（`fmComposer.js:190-193`，实现 `imeGuard.js:51/86`） | ✅ 同左 | ✅ 同左 | ✅ 同一 `imeGuard` 实现，但经 `bindComposerKeys` 包装（`input/composer.js:74-86`，调用 `input.js:1755`） | 无行为差异：**同一守卫模块**，主会话多的只是「键级级联」外壳（mention / 模式取消 / slash / 历史），守卫本身单源 |
| 4 | 语音输入 | ✅ `.icon-btn.fm-mic-btn` + `lucide mic`（`fmComposer.js:74-79`） | ✅ 同左 | ✅ **本批补齐**（旧实现 `if (conv.kind !== 'device')` 直接不挂） | △ 光球 `#mic-orb-wrap > .micbubble`（`index.html:872-877`，`input.js:2035-2040`） | 主会话用全双工光球（sketchfab 式球体 + 拖动手势史），是三面禁用的形态（voicefix 批口径「气泡是主窗形态，不进 fm 窗」）；**管线单源**：两侧都调 `voiceEngine.startDictation` |
| 5 | 录音中态 | ✅ `.recording`（listening/speaking）+ `.processing` + `aria-pressed`（`fmComposer.js:80-84`，CSS `friends.css:1529-1530`） | ✅ 同左 | ✅ 同左 | △ `setMicState` + `input.classList.add('voice-dictating')`（`input.js:2036`） | 同一套语义（recording/processing 词形与主窗一致），主会话额外挂 `voice-dictating` 给光球做整体态；三面无光球故无此挂点 |
| 6 | 附件入口 | ✅ `.icon-btn.dropbox-attach-btn.fm-attach-btn` 32×32（`fmComposer.js:172-181`；宿主判据 `messages.js:1397`） | ✅ 同左（`attachAvailable(conv)` 判据单源） | ✅ 同左（设备面恒 true，走 `sendDeviceFiles` 单点） | △ `#attach-btn` `.icon-btn` 36×36（`index.html:887`） | 几何差 = 宿主档不同（fm 复用设备面既有 32px 纸夹档，主会话 36px）；**动作面不共享**：三面差异全收在 `dispatchFiles`（`messages.js:4371`），主会话走 `pendingAttachments` 暂存再随帧发出 |
| 7 | 发送键 | ✅ `.cfg-btn.cfg-btn-primary.fm-send-btn` 50×36，可用态 `rgb(7,193,96)`（`fmComposer.js:157`；发送族 `sapphire.css:326`） | ✅ 同左 | ✅ 同左 | △ `#send-btn` 46×32，**不属**发送族 `:is()` 列表（`index.html:888`；名单见 `friends.css:1808`） | 取色**同源同值**（两侧可用态实测都是 `rgb(7,193,96)`），几何差来自主会话胶囊条（radius 999px / 32 高）与 fm 条（radius 10px / 36 高）两条不同的条规范；主会话键因历史原因未并入族列表，本批**不改**（越界） |
| 8 | 裸 Enter | ✅ `keydown` → `preventDefault` + `onSend`（`fmComposer.js:191-194`） | ✅ 同左 | ✅ 同左 | ✅ `commitEnter`（`imeGuard.js:112`，装配 `input/composer.js:86`） | 无差异（同一判定语义：组字中让位输入法、裸 Enter 发送） |
| 9 | 引用条 | ✅ `.fm-quote-strip` 挂输入条**之后**（`messages.js:1410-1412`，落面 `messages.js:4013-4024`），入口 = 气泡右键「引用」（`messages.js:3026-3034`） | ✅ 同左 | ✅ 同左 | △ `#attachment-preview` 复用为引用 chip 载体 + `renderQuoteStrip`（`index.html:880`；`js/msgQuote.js:259`；调用 `input.js:392/556`） | 同一数据语义（`ref` 附件型 + `renderRefBlock` 同一渲染器），差异在承载容器：主会话把引用与附件预览塞进同一条带（`pendingAttachments` 数据面），三面用独立 strip 节点 |
| 10 | 多选转发 | ✅ `.fm-select-bar` = 转发 / 逐个转发 / 合并转发（`messages.js:1348-1367`，进入 `messages.js:4086`） | ✅ 同左 | ✅ 同左 | ➖ 无同族：右键菜单只有「复制 + 引用」（`js/msgQuote.js:295-306`） | fm 窗有「把若干条消息转发给别的会话 / 逐条转发」的产品需求（msgmenu 一期）；主会话的同类动作由「转发给 agent」承担（单条）与检索面 `chatSearch.js` 的多选（另一语义）承担，**不**是三面能力的缺失而是不同产品面 |
| 11 | 发送中排队 | ➖ 无排队：直发 + 乐观态（`messages.js:4384-4400` 清空输入 → `armOptimisticText`） | ➖ 同左 | ➖ 同左（设备腿 `sendDeviceCurrent` 同序，`messages.js:6532`） | △ `queueMessage` + 排队条 + 「立即发送」（`input.js:234/249`，条带 `input.js:853` → `js/chatQueue.js`） | 主会话对面是 agent 回合，busy 时消息必须排队（否则丢 prompt）；fm 三面是人对人/人对设备的消息流，无「回合」概念 ⇒ 直发 + 失败重试面即可 |
| 12 | agent-busy 中断键 | ➖ 无（组件头注 `fmComposer.js:14-15` 显式申报） | ➖ 同左 | ➖ 同左 | △ `#stop-btn` → `requestTurnInterrupt(sessionId)`（`index.html:889`；`input.js:1665-1668`；出口 `input/composer.js:133`） | fm 窗是**消息面不是回合面**——没有可中断的进行中回合；加中断键只会造出假入口 |
| 13 | 输入历史 ↑↓ | ➖ 无（`messages.js` 全文零 `inputHistory` 引用） | ➖ 同左 | ➖ 同左 | △ `state.inputHistory` + `ArrowUp/ArrowDown` 召回（`input.js:505` 写入、`input.js:1840-1860` 召回） | 历史召回是「对同一 agent 反复下指令」的效率件；fm 窗多会话并行、召回会把 A 会话的话带进 B 会话（语义错误），故不做 |
| 14 | slash + mention 菜单 | ➖ 无（`fmComposer.js` 只 import `i18n / voiceEngine / imeGuard / elements`，零 slash/mention 依赖，`fmComposer.js:27-30`） | ➖ 同左 | ➖ 同左 | △ `handleSlash` + `updateSlashDropdown`（`input.js:51-52`、`input.js:2205-2211`）+ `bindMention`（`input.js:2207-2211`，实现 `js/mentionComplete.js`） | slash 命令 = agent 会话操作（`/ask`、`/compact`、skill 激活）；mention = @项目/文件寻址，只在有 agent 上下文时成立。fm 三面无此语义，挂上即空菜单 |

**结论（验收命题成立性）**：第 1–14 行中，**好友 / 群 / 设备三列逐格完全相同**（✅/➖ 对齐，无一处分叉）；与主会话的差异共 11 项（#2 多行、#4 语音形态、#5 录音态挂点、#6 附件几何、#7 发送键几何与族籍、#9 引用承载容器、#10 多选转发、#11 排队、#12 中断键、#13 输入历史、#14 菜单），**全部有产品面原因**，无一项是「漏做」。共享层：`imeGuard`（#3）、`voiceEngine`（#4 管线）、`formatFooterTime`（§3 footer）、发送族取色（#7）、`renderRefBlock`（#9）、`showPopupMenu`（#10 入口壳）均为**同一实现单源**。

补充小项（不在 14 行内，一并申报）：Shift+Enter 换行（三面 ➖ / 主会话 ✅，`input/composer.js:22-23`）；附件条带预览（三面 ➖ / 主会话 ✅ `index.html:880`）；草稿持久化（三面 ➖ / 主会话 ✅ `input.js:394 saveInputDraft`）。

## §3 验收与证据

### §3.1 三面双主题实拍（`tests/fm-threeface-shots.mjs` · `evidence/20261102_fm-threeface-align/shots/`）

**checks = 155 · failures = 0 · frames = 38（md5 去重后 30）**，两次整跑读数一致，零 console error / 零 pageerror。帧分组：

| 组 | 帧数 | 去重 | 内容 |
|---|---|---|---|
| A0 | 6 | 6 | 三面板（好友 `cF` / 群 `g-1` / 设备 `dev:peer-1`）× 双主题整窗 |
| A1 | 6 | **2** | 三面板 × 双主题**输入条特写** —— 去重后仅 2（每主题 1 张）⇒ 三面输入条**像素级同款**（md5 实测：light 三面同为 `d7203b1f…`，dark 三面同为 `eb63888a…`） |
| A2 | 6 | 6 | 三面板 × 双主题 叠卡区 |
| A3 | 6 | 4 | 三面板 × 双主题 footer（`today` / `earlier` 两档） |
| B1 | 4 | 4 | **对照帧①** fm 输入条 vs 主会话输入条（双主题） |
| B2 | 4 | 4 | **对照帧②** 叠卡「展开 N」钮 vs 主会话（出向/入向 × 双主题） |
| B3 | 6 | 6 | **对照帧③** footer vs 主会话（fm today / fm earlier / main × 双主题） |

三面几何硬读数（双主题同值，`readings.json → schemes.*.composer`）：mic / input / send 三键高 = **36**，attach = **32**；键位 DOM 序三面一致 = `mic → input → attach(input) → send`。三面可用态发送键取色 = `rgb(7, 193, 96)`，`sendFamilyMember = true`。

### §3.2 三个对照帧的数值

**① 输入条（B1）**：fm `input.cfg-input` radius **8px** / 高 **36px** / fontSize 12px；主会话 `textarea` radius **18px** / 高 **32px** / fontSize 14.5px（形状差异**照录，不冒充同值**）。fm `.fm-mic-btn` **36×36** == 主会话 `#attach-btn` **36×36**；fm 纸夹 **32×32** = 设备面既有 `.dropbox-attach-btn` 档。两侧可用态发送键**同色** `rgb(7,193,96)`；fm 命中发送族 `:is()`、主会话 `#send-btn` 不属该列表。

**② 叠卡「展开 N」钮（B2）**：

| 读数 | fm 出向 | 主会话出向 | fm 入向 | 主会话入向 |
|---|---|---|---|---|
| 净距（pill→view） | **48px** | **48px** | **48px** | **48px** |
| 垂直中心偏差 vDelta | **0** | **0** | **0** | **0** |
| `.pop-stack-area` flex-direction | row | row | row | row |
| flex gap | 16px | 16px | 16px | 16px |
| 侧别 | 钮在左（`margin-right:32`） | 同 | 钮在右（`order:3` + `margin-left:32`） | 同 |
| 卡面 | 220×280 ×4 行 | 同 | 同 | 同 |

净距差 = 48 − 48 = **0.00**；垂直中心偏差 ≤1px 门槛，实测 **0 / 0**。**验红（判别力证明）**：页内注回 `.fm-att-media` ⇒ `flex-direction` row→**column**、vDelta 0→**161px**、净距 48→**−105.75**；移走后全部复原（row / 0 / 48）⇒ 断言确有判别力，不是恒真。

**③ footer（B3）**：四档时间文本 fm 与主会话**逐字相等** —— `14:30` / `昨天 15:30` / `星期一 15:30` / `9月25日 15:30`。formatter 换血证据：旧 `fmtTime` 在同三档输出不同（`昨天` / `11/2` / `9/25`，today 同值 `14:30`）⇒ 确实换了 formatter，非同值换汤。分隔符两侧同值：**1×10 / opacity 0.2 / flex-shrink 0 / 真节点**（`.fm-msg-divider`，`dividerIsElement: true`）；`.fm-msg-actions::before` 计算值为 **none**（伪元素已退役）。meta 子节点 fm = `[fm-msg-time, fm-msg-divider, fm-msg-actions]`，主会话 = `[duration-badge-time, duration-badge-divider, msg-copy]`（同构）；两侧 gap 同为 **6px**（时间→钮 = 6 + 1 + 6 = 13px）。

### §3.3 设备面板 mic 真录音链路（`tests/fm-device-mic-live.mjs` · `evidence/20261102_fm-threeface-align/mic/`）

**M1–M7 全 PASS，failures=0，exit 0。** 链路非桩：Chromium `--use-file-for-fake-audio-capture` 吃现场合成的 16kHz/mono/16bit 语音型时变信号（384044B / 12000ms / rms 0.10639），`getUserMedia` 真采集 → `voiceEngine` 真切段 → 真编码 WAV → 真 WS `transcribe` 帧（**WS 桩解码断言后**才回文本）。

| 断言 | 读数 |
|---|---|
| M1 mic 键在场 | `icon-btn fm-mic-btn`，lucide `mic`（`<svg class="lucide-mic">`），`aria-label="语音输入"`，`aria-pressed="false"`，可见 36px |
| M2 录音中态 | `icon-btn fm-mic-btn recording`，`aria-pressed="true"` |
| M3 帧数 / 解码 | **3 段**，均 RIFF/WAVE · 16000Hz · mono · 16bit · 非全零 |
| M3 RMS 实测 | 段1 **0.2549**（peak 0.9999，34133 samples / 2133ms）、段2 **0.09684**（peak 0.4081）、尾段 **0.10642**（2730 samples / 171ms）—— 门槛 0.01 |
| M3 主频 / 调制 | 首段主频 **240Hz**（合成源基频带 185–267Hz）；段内分段 RMS 高低差 **217.58×** ⇒ 时变真信号，非稳态噪声 |
| M4 态机 → 落字 | trace `idle → recording → recording → processing → idle`；最终 `input.fm-input.value` = `"设备面板语音实测设备面板语音实测设备面板语音实测 "`（25 字符） |
| M5 发送键放开 | `.fm-send-btn` `disabled=false`（valueLen 25） |
| M6 零错误 | console error **0** / pageerror **0**（仅 WebGL GPU stall warn，与业务无关） |
| M7 三面同款 | `dev:peer-1` / `cF` / `g-1` 三面 `.fm-mic-btn` 全在，class 与 aria 逐值相同 |

**一条值得记的坑**：`getUserMedia` 的 AGC/降噪会把**稳态单音**当噪声压掉——首版喂 440Hz 正弦，首段 RMS 0.27、次段直落 0.025，证据变成「勉强过线」。改成语音型时变信号（音节包络 4.5/s + 基频滑动 + 谐波 + 词间静音）后每段都是硬读数。已写进测试文件注释与 `mic/RUN.txt`。

### §3.4 回归面（`evidence/20261102_fm-threeface-align/regression/RESULTS.md` + `logs/`）

**9 套零跳过，410 PASS / 2 FAIL**：

| # | spec | 退出码 | PASS/FAIL |
|---|---|---|---|
| 1 | `file-bytes-single-fetch.spec.mjs` | **0** | **7 / 0**（字节链硬门） |
| 2 | `imgmsg-send.spec.mjs` | 0 | 115 / 0 |
| 3 | `attcard-unify-shots.mjs` | 0 | 47 / 0 |
| 4 | `msgmenu.spec.mjs` | **1** | 85 / 1（S6 光球挂载点＝既有红） |
| 5a | `ime-guard.spec.mjs` | 0 | 37 / 0 |
| 5b | `busy-lifecycle.spec.mjs` | 0 | 39 / 0 |
| 6 | `mvp2-device-client.spec.mjs` | **1** | 43 / 1（R4b 下载路由＝既有红） |
| 7 | `rcptcode-friendterminal.spec.mjs` | 0 | 24 / 0 |
| 8 | `friends-dev-default-on.spec.mjs` | 0 | 13 / 0 |

**字节链 T1–T7（硬门，exit 0 · 7 passed 11.2s）逐条 PASS**：T1 设备面内联直显 + Canvas 打开 ⇒ 恰一次抓取（修前两次）· T2 设备面重渲×2 + Canvas×2 ⇒ 仍恰一次 · T3 好友面直显 ⇒ 鉴权路恰一次 + `attach:<id>` 入账共享 · T4 灯箱打开已入账同件 ⇒ 零追加抓取 · T5 Markdown 两 pane + Canvas ⇒ 恰一次 · T6 坏字节事件失效 ⇒ 逐出账本 + 票腿自愈恰一次 · T7 HTML SVG 内联化 ⇒ 同一 SVG 恰一次。

**两处红均为既有红（三树对照判定，非本批引入）**：
- `msgmenu S6 单一光球挂载点`：读数 `{wrap:2, bubble:2, canvas:2, css:2, ripple:0, bound:true}`，谓词要求各恰 1（`msgmenu.spec.mjs:1155`）。根因 = `index.html:938-944` 的全双工 overlay `#vmo-orb-wrap` 同样带 `.mic-orb-wrap/.micbubble/.orb-canvas/.css-orb` 四类；该文件与本批零交集。三树读数逐字相同（85/1、还原树 85/1、main 82/1）。建议 spec 侧把计数收窄到 `#mic-orb-wrap` 子树。
- `mvp2 R4b 附件下载走既有鉴权路由`：读数 `count=2`，要求 `attDownload===1`（`mvp2-device-client.spec.mjs:327`）。推断 = 一次点击腿 + 一次媒体面字节填充腿被同一 stub 计数两次；本批只去掉 `fm-att-media` 类（只作用叠卡组，`messages.js:1930`），不涉及设备单件卡下载键数量。三树同读数（43/1、43/1、main 34/1）。

**零 console error 附加门**：imgmsg 17 条、attcard 5 条 console + 5 条 pageerror、msgmenu **9 条 pageerror**（该 spec 无 console-error 门，只采集 pageerror）、mvp2 3 条、rcptcode 5 条、busy-lifecycle 1 条、friends-dev 3 条 —— 全 PASS，读数均为 `[]`。`file-bytes-single-fetch` 与 `ime-guard` 脚本内无 console/pageerror 门，**显式申报不适用**（非静默略过）。

## §4 未做 / 申报项

1. **视频 Range 流式腿未做**：好友/群视频字节腿 = 鉴权路由**整件**下载（`/api/friends/attachments/{id}` 无 Range），设备 path 腿才是票据 URL 流式。本批只做 footer/几何对齐，不动传输腿（`messages.js:1905-1906` 头注已申报「形态两腿同款」）。
2. **webkit 只覆盖零腿**：`fm-threeface-shots.mjs` / `fm-device-mic-live.mjs` 均只跑 chromium（`--use-file-for-fake-audio-capture` 是 chromium 专有开关）。跨引擎（webkit/firefox）本批未跑，申报不适用。
3. **主会话 `#send-btn` 未并入发送族 `:is()` 列表**：本批只读主会话几何、不改编主会话实现（越界）。
4. **两处既有红未随批换代 spec 口径**（§3.4）：属 spec 判据过宽/腿未区分，改 spec 需另立批；本批未改源码凑绿。
5. **`attcard-unify-shots` 的 37 帧留在 `/tmp/attcard-unify-shots`** 未收编进本批 evidence（非本批产物，重跑加 `ATT_SHOTS=<dir>` 可落任意目录）。
6. **wiki 校验入口有一次既有违规**：`docs/20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb.md`（前批文档）缺 YAML frontmatter，导致 vault 校验 exit 1。本批**最小修复**：仅补 frontmatter（type/status/date/scope），正文一字未动、未改名未移动——见 §5。
7. **设备面 `attachAvailable()` 原语返回 false**（设备腿走自己的传输链，绝不进 `attachUpload`），宿主判据是 `conv.kind === 'device' || attachAvailable(conv)` ⇒ 设备面纸夹在场。harness 按宿主判据断言并同时报出原语读数（`readings.json → schemes.*.attachVerdict`）。

## §5 校验与提交

### §5.1 wiki 校验（exit 0）

```
$ python3 /Users/kaiyu/.stand/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py \
    --root "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-c1142b9b/docs"
wiki-docs 校验：通过（/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-c1142b9b/docs，133 篇文档，零违规）
```

exit code = **0**。首跑曾有 1 条 R1 违规（前批 `docs/20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb.md` 缺 frontmatter），本批按「最小修复」只补 YAML frontmatter 头部（并在正文首段加一行「仅补格式」的说明），正文一字未改、未改名未移动。

### §5.2 提交

单次提交，分支 `delegate/delegate-nebflow-c1142b9b`，**未** push、**未** merge main、**未** amend。add 走显式路径（非 `git add -A`）。

## §6 合并落地（2026-11-02 07:1x，裁判复核后收口）

### §6.1 合并前独立复验

| 项 | 做法 | 结果 |
|---|---|---|
| 字节链硬门 | 亲跑 `file-bytes-single-fetch` | **7 passed / exit 0**，T1–T7 逐条绿 |
| mic 真录音 | **独立用 python `wave` 解码**三段 `transcribe-seg*.wav` | `16000Hz / mono / 16bit`，RMS **0.25490 / 0.09684 / 0.10642**（门槛 0.01），与 `readings.json` 逐位一致 ⇒ 确为真采集字节，非桩 |
| 两处既有红 | 用官方 **before-tree 开关**跑纯 main web 树（`git archive main`） | `msgmenu` failures=1（同一 S6）、`mvp2` `[BEFORE-TREE] failures=1`（同一 R4b `count=2`），与本支读数逐字相同 ⇒ **既有红确认，非本批引入** |

复跑命令见 `evidence/20261102_fm-threeface-align/regression/RESULTS.md` 末节「既有红的独立复验」。

### §6.2 合并

- 合入提交：`91c48ab0c`（`Merge branch 'delegate/delegate-nebflow-c1142b9b'`，parents = `eec3feb60` + `c9579e415`），`--no-ff` 保留批形态。
- 本支提交链：`cdf3ef77f`（主批）+ `c9579e415`（判据校订 + 既有红复验归档）。
- **一处合并冲突及解法**：`docs/20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb.md` —— 两支各自独立发现并修补了同一处 R1 违规（缺 frontmatter）。取**合集**：frontmatter 保留信息量更大的四行（type/status/date/scope），两条补注均保留并追加一行合并说明；正文一字未改。
- 合并后 `node --check` 三个改动 JS 全通过、全仓零冲突标记残留；main 上复跑字节链 _（见下条）_。

### §6.3 合并后复核

main 树 `git status` clean；`docs` vault 在**仓根**口径下仍有 29 条既有 `orphan-doc` 违规（全部集中在 `design/` 等历史目录，**不含**本批两份文档），该存量面非本批范围。
