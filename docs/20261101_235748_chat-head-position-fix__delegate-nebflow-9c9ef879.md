---
type: design
status: implemented
---

# 会话身份组位置批：头像+名字回落「会话区顶部居中成组」（2026-11-01 23:00 作者报障）

作者报障原话（23:00，实拍 `evidence/20261101_dock-avatar-edge-unify/edge-sameframe-dark-avatar.png`）：

> 「中间 agent 头像和 agent name 的位置这么奇怪」

实拍复核所见两条：**① 看不到名字**（78px 大圆头像悬在上方，名字缺席）；**② 位置异常**
（头像上方一大块空白，头像压在消息区上方偏中间，读成「浮在页面中央」而不是「顶部居中
的会话身份头」）。本文件是这一批的事实与决策存档：根因链（两条，均有实测读数）、
修法、验收证据、存量红如实标注。上游形态见 [[20261101_221500_muse-avatar-restore-stack-smartcrop]]
（Muse 基座回归批落地了这套头像+名字）与 [[20261101_225500_dock-avatar-edge-unify__delegate-nebflow-9770a0a7]]
（作者实拍帧的来源批）。

落位提交：`d82cf3543`（分支 `delegate/delegate-nebflow-9c9ef879`，基线 `7fabc3eb6`）。

---

## 1. 根因一：位置下沉 —— 96px 顶让位把身份组推进视觉中段

### 1.1 病灶链（逐行）

| 环节 | 位置 | 事实 |
|------|------|------|
| ① 建层 | `src/main/resources/web/js/mobile/chatChrome.js:98` | `chat.insertBefore(chatHead, chat.firstChild)` —— `.g2-chat-head` 被插在 `#chat` **内容流首项**（`d4cc8d189` 液体玻璃 v2 批引入，Muse 批沿用） |
| ② 让位 | `src/main/resources/web/js/main.js:4286` / `:4295` | `reserve = max(8, ceil(bandBottom() - chatTop) + 8)` → `chat.style.setProperty('padding-top', reserve + 'px', 'important')`。手机层常态 = 渐隐带 88 + 基线 8 = **96px** |
| ③ 结果 | 实测 `getBoundingClientRect()` | 静止态 `headRect.top − chatRect.top = 96`，头像顶 = 104 |

因为 `.g2-chat-head` 是**流内首项**，它的静止位置就等于 `#chat` 的 `padding-top`
——96px 的纯空带整段挂在身份组上方。作者看到的「头像上方一大块空白」就是这段让位带；
「像浮在页面中央」是 96px 空带 + 78px 头像 + 名字 + 后续消息共同造成的视觉重心下移。

### 1.2 排查中排除的两个假设（有实证）

- **`--chat-top-clip` 是垃圾值**：不成立。`main.js:4280-4283` 在手机层强制
  `clipTop = 0`（10-31 白屏批的既有修复），实测 `clip=0px`。这条假设是上月同款白屏
  案例的残留记忆，本批实测否证。
- **名字被 `display:none` / 颜色同背景 / 被 sticky 容器裁掉**：都不是，见 §2。

### 1.3 修法（纯 CSS 派生，零新 JS 状态）

`src/main/resources/web/css/mobileLayer.css` 新增 **②-b** 块（L2647-2669）：

```css
body.mobile-layer #chat .g2-chat-head {
  margin-top: calc(var(--muse-head-top, -8px) + 64px + env(safe-area-inset-top, 0px));
}
```

取值推导：`--muse-head-top` 已知 = `-(#chat padding-top)`（`chatChrome.js:138-141`
同步写入），故「组顶 = 64 + safe」⇔ `margin-top = var(--muse-head-top) + 64px + safe`。
落点 64 = **悬浮双键行下缘**（`top: 16 + 48`，见 `mobileLayer.css` `#mobile-fab-layer`
块 `top: calc(16px + safe)`），组自带 `padding-top: 8` 成为与双键的呼吸缝，头像落 72。

三个设计约束都满足：
- **同帧跟随**：让位带随横幅/键盘变化时 JS 重写 `--muse-head-top`，上提量同帧跟随，
  不增量叠加、不写死 96。
- **首帧正确**：回退值 `-8px` 与 `chat.css:30` `#chat { padding: 8px 0 36px }` 的基线
  同值 ⇒ CSS 先于 JS 落地的首帧即 `-8 + 64 = 56`，加基线 `padding 8` = 组顶 64，
  不产生「先下推再上收」的跳变。
- **sticky 语义不变**：负 margin 只改静止态落点，不改 sticky 锚定阈值 —— 实测滚动态
  组顶仍 `= #chat 顶`（±1.5px）。

安全区：悬浮双键同为 `top: calc(16px + safe)`，上提量同加 `env(safe-area-inset-top)`，
刘海档组顶与双键下缘的 8px 关系逐像素保持。

---

## 2. 根因二：名字看似消失 —— 未命名 root 的幽灵玻璃胶囊

### 2.1 名字其实没消失

作者实拍帧出自 [[20261101_225500_dock-avatar-edge-unify__delegate-nebflow-9770a0a7]]
的 harness（`tests/dock-avatar-edge-unify-shots.mjs`），该 fixture 对
`/agents/Nebula/agent.json` 走 catch-all 返回 `{}` —— root 是**未命名**态。

未命名态下 `src/main/resources/web/js/sidebar.js:2014-2018`：

```js
const label = agentName === ROOT_AGENT_KEY
  ? (isRootNamed() ? rootDisplayName() : '')
  : agentDisplayName(agentName);
sessionNameEl.textContent = label;   // ← 空串
sessionNameEl.style.display = '';    // ← 但不隐藏
```

这是 2026-10-06 N1 的既有裁定（无名 root 标题区为空串，机制名永不作显示回落），
在桌面 `#header` 上是对的（保留中槽布局不跳）。但手机层的 `#session-name` 有胶囊样式
（`mobileLayer.css:2584` `padding: 3px 14px` + `border-radius: 999px` +
`background: var(--glass-bg)`）—— **空串 span 仍吃 padding**，于是画出一枚
28×6px 的幽灵玻璃条挂在头像下方：作者实拍里「头像下的小暗条」，既不是名字也不是间距，
所以读成「看不到名字」。

真实生产不受影响：Live `agents/Nebula/agent.json` 带 `"displayName": "Spider"`，
`isRootNamed()` 为真，名字正常渲染（已用生产路径脚本验证）。

### 2.2 修法

`mobileLayer.css` 新增 **②-c** 块（L2670-2678）：

```css
body.mobile-layer #chat .g2-chat-head #session-name:empty {
  display: none;
}
```

`:empty` 只命中无子节点无文本节点的元素，故不吃「加载中」态；名字一到即恢复。
组在未命名档收敛为「头像 + 克制下白」，不再有幽灵条。

---

## 3. 验收

### 3.1 新增 harness

`tests/chat-head-position-shots.mjs` → `evidence/20261101_chat-head-position/`：

| 帧 | 内容 |
|----|------|
| `rest-{light,dark}.png` | 375 静止态（头像+名字成组贴顶） |
| `before-{light,dark}.png` | 修前对照（注入 `margin-top:0` 还原 96px 病灶，仅目检） |
| `scrollmid-{light,dark}.png` | 滚动中（锚贴窗口顶，消息从锚上缘穿过） |
| `scrolled-{light,dark}.png` | 滚动后（停住仍锚定格，渐隐带 88px 在场） |
| `unnamed-{light,dark}.png` | 未命名 root 档（幽灵胶囊收口实证） |
| `desktop-1280.png` | 桌面回归（`#header` 头像行原样、零身份头） |

探针实测读数（双主题同值）：

- **P①** `headTop − chatTop = 64.0`（`muse-head-top=-96px`、`clip=0px`）
- **P②** `avTop − headTop = 8.0`、名字在头像正下且居中（`avCx = chatCx = 187.5`）
- **P③** 静止态 `rowsBehind = 0`（零消息行与身份组相交）
- **P④** 名字读数 `text="Spider"`、`h=23`、`display=block`
- **P⑤** 滚动中 `0.0` / `rowsBehind=2`；滚动后 `0.0` / `fadeH=88`
- **P⑥** 未命名档 `text=""`、`display=none`、`h=0`
- **P⑦** 桌面 `#chat` 内零 `.g2-chat-head`，`#header` 头像行可绘
- **R0** 每腿零 console error
- 修前对照帧读数 `headTop − chatTop = 96.0`（= 病灶让位，目检：头像明显下沉、上方空带）

### 3.2 相关 spec 全绿

| 目标 | 结果 |
|------|------|
| `tests/chat-head-position-shots.mjs`（新增） | **ALL PASS**（25 断言） |
| `tests/mobile-muse-avatar-shots.mjs` | **ALL PASS**（原 6 既有红一并转绿，见 §4） |
| `tests/muse-restore-smartcrop-shots.mjs` | **ALL PASS** |
| `tests/mobile-dock.spec.mjs` | **21 passed** |
| `tests/mobile-dock-shape-shots.mjs` | **ALL PASS** |
| `tests/mobile-dock-capsule-shots.mjs` | **ALL PASS** |
| `tests/dock-avatar-edge-unify-shots.mjs` | **ALL PASS** |
| `tests/composer-slim-shots.mjs` | **ALL PASS** |

### 3.3 存量红（**非本批引入**，如实标注）

在干净 main 基线工作树（`/tmp/verify-main-baseline` @ `7fabc3eb6`）复跑逐字同报：

| 目标 | 存量红内容 | 归因 |
|------|-----------|------|
| `tests/composer-mobile-slim-shots.mjs` | 6 FAIL：`G[before]` 条高，期望 78 实测 58；padding 期望 6px 实测 4px；光球足印期望 64 实测 48 | 这是 **"before" 夹具**，它把收窄前的旧值（78/6px/64）钉成断言，而今天的实值已被收窄批改掉 —— 该夹具按定义会随每次运行失败（[[20261101_222500_composer-mobile-slim__delegate-nebflow-6e995fd9]] 的 before 腿） |
| `tests/mobile-v3.spec.mjs` | T2 失败：任务键徽标期望 `'10'` 实测 `'8'` | 徽标读数依赖当日在册任务数（运行/排队链），与手机层几何无关 |
| `tests/mobile-muse-avatar-shots.mjs` | 原 6 FAIL（静止态判据 + `rowsBehind=0`） | 本批处理，见 §4 |
| `tests/liquid-glass.spec.mjs` | 5 FAIL：M3 圆钮 48px / M4 CSSOM `:active scale` 过渡 / D3 亮档 hover Δ / T1 `--g2-fab-bg` 三态 / R1 折射层滤镜契约 | 同一基线工作树复跑**逐条同报**（5 failed / 6 passed）—— 液体玻璃 token 族与手机圆钮探针的存量漂移，本批只动 `.g2-chat-head` / `#session-name`，不触这些 token |
| `tests/chat-header-crossing-shots.mjs` | 2 FAIL（`无蒙层压字②：顶带对穿顶行零像素贡献` 等，drift=41.67） | 同一基线工作树复跑**同值同报** —— 顶带像素探针的存量红 |

> 复跑基线：`/tmp/verify-main-baseline`（`git worktree` 检出 `7fabc3eb6`，与本批共同祖先，
> 未含本批任何改动；`node_modules` 符号链接到主检出）。判定口径 = 同树同命令复跑，
> 结果逐字一致即判存量红。

---

## 4. 随批修正的两处判据（原地重钉）

### 4.1 静止态判据钉的是病灶本身

`tests/mobile-muse-avatar-shots.mjs` 与 `tests/muse-restore-smartcrop-shots.mjs` 原判据：

```
rest.head.top >= rest.chat.top + 88   // 「组顶在渐隐带之下」
```

这条**正是病灶**：它把「身份组被 96px 让位推到视觉中段」写成了契约。本批重钉为：

```
Math.abs(rest.head.top - rest.chat.top - 64) <= 2   // 组顶 = 悬浮双键行下缘
```

并新增「头像+名字成组居顶」断言（`avTop − headTop ∈ [6,10]`）。未锚定判定保留。

### 4.2 `rowsBehind` 探针口径错误（存量红，非本批引入）

原探针只数 `.row.ai`：

```js
const rowsBehind = [...chat.querySelectorAll('.row.ai')]...
```

但滚动到底时跨过锚点的那一行恰好是 `.row.user`，于是恒判 0。在干净 main 基线工作树
用还原脚本复跑，结果**完全相同**（同为 0）—— 确认是存量红而非本批引入。判据本意是
「消息行从锚上缘穿过」，行类型与语义无关 ⇒ 改数全部 `.row`（`.row.ai` 计数单列备查）。

---

## 5. 归因小结（作者要求的两条逐行归因）

| 现象 | 归因行 | 提交 | 修法落点 |
|------|--------|------|----------|
| **位置下沉**（头像浮在页面中部、上方一大块空白） | `js/mobile/chatChrome.js:98` 把 `.g2-chat-head` 插成 `#chat` 流内首项 + `js/main.js:4286/4295` 写入 `padding-top: 96px` | `d4cc8d189`（chatChrome 建层）/ `a1853f9ff`（顶让位 reserve） | `css/mobileLayer.css` ②-b（L2647-2669） |
| **名字消失**（实为幽灵玻璃条） | `js/sidebar.js:2017-2018` 未命名 root 写空串但保留 `display:''` + `css/mobileLayer.css:2584` 胶囊 `padding: 3px 14px` | 命名裁定批 / 基座回归批 `b4b6d8745` | `css/mobileLayer.css` ②-c（L2670-2678） |

## 6. 交叉引用

- [[20261101_221500_muse-avatar-restore-stack-smartcrop]] —— Muse 基座回归批（头像+名字
  落地的那一批，本批在其上修正落位）。
- [[20261101_225500_dock-avatar-edge-unify__delegate-nebflow-9770a0a7]] —— 作者实拍帧
  来源批（其 fixture 的未命名 root 是幽灵条显现的触发条件）。
- [[20261101_222500_composer-mobile-slim__delegate-nebflow-6e995fd9]] —— 存量红
  `composer-mobile-slim-shots` 的 before 腿来源。
- [[20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb]] —— 同批期动的
  手机层几何（本批与其零交叠：只动 `.g2-chat-head` 与 `#session-name`）。
