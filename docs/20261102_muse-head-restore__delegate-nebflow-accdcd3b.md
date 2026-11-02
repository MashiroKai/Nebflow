---
type: design
status: implemented
---

# Muse 悬浮身份头恢复批：整条回退单行三段式顶栏（2026-11-02 09:14 作者令）

## 0. 认错摘要

上一批（[[20261102_090250_mobile-chat-topbar-single-row]]，07:23 令）我把作者说的「手机端会话顶部，头像为什么不在顶栏与左右两颗按钮同一行居中」读成了「造一条真顶栏」，于是把 `.g2-chat-head` 从内容流里的 sticky 悬浮锚改成了 `position: fixed` 的 56px+safe 毛玻璃通栏带（下缘 1px 描边、头像 40px 夹在左右两钮之间、名字横排挂头像右侧）——形态与作者手上的 Muse 截图相反：**作者要的是「头像居中悬浮、名字小胶囊在正下方、没有任何横贯带」**。

作者 09:14 实拍（深色 1134×246，`evidence/20261102_muse-head-restore/prev-batch-rest-{light,dark}.png` 是上一批同视口现场）里根本没有横贯带：头像单独压在消息流上、消息文字从头像两侧穿过、名字是头像正下方一枚深底白字小胶囊，左右两钮各自独立悬浮。**这一批是判断错误，不是实现错误**——上一批的每一条断言都钉住了它自己的（错误）契约，所以绿也是绿的错的那条。

本批把上一批**整条回退**为悬浮形态，并按新实拍重新取值、补六条机械断言。回退的六条、保留的六条、实测几何与 9 条 spec 的改法全在下面。

---

## 1. 三列对照：上一批做了什么 / 本批保留什么 / 回退什么

| 上一批做了什么（07:23，`221391120` / 合入 `903e0f69c`） | 本批保留 | 本批回退 |
|---|---|---|
| `.g2-chat-head` = `position: fixed; top:0; left/right:0; height: calc(56px + safe)`，`background: var(--glass-bg)` + blur24/sat1.15 + `border-bottom: 1px --glass-border` | 玻璃包单源（`--glass-bg` / `--glass-blur` / `--cv-saturate` / `--glass-border` token 全不动） | **整块删除**：文件内已无任何 `#chat .g2-chat-head` 的 fixed / 通栏尺寸 / 背景 / backdrop / 下缘描边声明 |
| 头像 40px（36-40 档取上沿，理由是「56 撑不开 56px 单行」） | 头像圆/描边/高光/浮影/芯片底沿用 `chat.css` 全局 `#agent-avatar-btn` 档 | **56px**（实拍 50.6 CSS 的上一档既有取值；40 只有实拍的 79%，读成图标） |
| 名字横排：`position:absolute; left: calc(50% + 28px)`，杀胶囊底（零 padding / 零底 / 零圆角 / 14px） | 名字字色 `--color-frame-text-bright`（两档对比度成立）与 `:empty` 收口 | **名字回头像正下方玻璃胶囊**：基座块 `flex-direction: column` + `--glass-bg` 底 + `r999` + 零 border + `padding: 3px 14px` |
| `#g2-edge-fade` 的 mask 收到顶栏下缘（56+safe）以下透明 | 渐隐带元素本体（88px / blur18 / sat1.5）零改动 | **mask 回到 88px 两段式原值**（`0% → 55%@50% → transparent`），上一批的覆盖块整条删除 |
| 双钮 `.mh-chrome-btn { top: calc(4px + safe) }`（钮中心 = safe + 28 = 40 头像中心） | 双钮 48px / 圆钮玻璃配方 / 角标 / 左右足印（left 16 / right 16）零改动 | **`top: calc(16px + safe)`**（`#mobile-fab-layer` 原基准；4px 是「与顶栏中线同轴」的派生值，顶栏拆掉后失去依据） |
| `#top-overlays { top: calc(64px + safe) }` | 桌面 `#header` / 顶部消失点 mask 零触碰 | **回到 `chat.css` 的 68px 基准**（上一批覆盖块删除） |
| `main.js` 顶让位改由顶栏 rect 实测（`headBottom − chatTop + 8`）+ `getMobileHead()` 惰性取节点 + `headRo` + `nebflow-chat-head-mounted/unmounted` 广播 | 顶让位观察器本体、RO 链、桌面公式 | **让位回到统一公式 `bandBottom() − chatTop + BASE_TOP_PAD`**（手机层 = 渐隐带 88 + 基线 8 = 96），惰性取节点 / RO 挂载 / 两条广播链整条摘除 |
| `chatChrome.js` 摘除 `syncHeadTop` / `--muse-head-top` / `headTopRo` | 节点迁移链、清空守望（`headMo`）、头像/名字搬运、teardown 结构 | **`syncHeadTop()` + `headTopRo`（观察 `#chat`）恢复**，写 `--muse-head-top = -(#chat padding-top)`；`mobileLayer.css` 中块 ②/②-b 以 `var()` 消费 |
| `glass.css` 两块降级列名 `body.mobile-layer #chat > .g2-chat-head`（把顶栏当实底面） | 两块降级块本体（`@supports not` 与 `html.no-backdrop`）与其余列名 | **列名换成 `body.mobile-layer #chat > .g2-chat-head #session-name`**——顶栏没了，需要实底回落的是名字胶囊 |

---

## 2. 落地（文件与行号）

- `src/main/resources/web/css/mobileLayer.css`
  - 文件头批次注记（L81-110）：新批六条回退 + 六条恢复 + 实拍量化；上一批单行三段式降为留档（L110-112）。
  - 中块 ② 悬浮锚本体（L2985-2998）：`position: sticky` + `top: var(--muse-head-top, 0px)` + `z-index: 26` + `gap: 6px` + `padding: 8px 2px 12px`；②-b（L2999-3010）`margin-top: calc(var(--muse-head-top, -8px) + 64px + env(safe-area-inset-top, 0px))`。
  - ③ 头像（L3020-3034）`width/height: 56px`；④ 名字（L3035-3040）`font-size: var(--mfs-sub)`（= 15px，L214）+ `max-width: 220px`。
  - 文件尾（L3621-3672）：上一批的整块可执行规则（fixed 顶栏 / 头像 40 / 名字横排 / 渐隐带 mask / 双钮 4+safe / `#top-overlays` 64+safe）**删除**，替换为 Muse 恢复批说明块（零声明，只留交叉索引）。
  - 基座回归块（L2896-2934）与 ②-c `:empty { display: none }`（L3011-3019）、`#chat` mask 释放块保留不动。
- `src/main/resources/web/js/main.js`（L4320-4329 / L4371 / L4400-4401）：删 `mobileHead` / `getMobileHead` / `headRo` / 两条 mounted-unmounted 监听；`reserve` 回到单公式。
- `src/main/resources/web/js/mobile/chatChrome.js`（L16-22 头注、L116-118、L137-155、L219-220、L235-236）：删建层/拆层广播；恢复 `syncHeadTop()` + `headTopRo` 并在 `unsubs` 里 `disconnect`；teardown 保留一次 `removeProperty('--muse-head-top')`。
- `src/main/resources/web/css/glass.css`（L517-521、L557-559）：两处降级列名换成 `#session-name`。

**考古依据**：悬浮形态不是本批新造的 —— `49e2e7477`（Muse 基座块合入）、`6438f440b`、`b4b6d8745`（`liquid-glass.css:330-402` 原基座）、`d82cf3543` / `ac68cac51`（sticky + 88px 渐隐带 + `z26 > 25` 层序）逐条实证「sticky 悬浮 + 渐隐带 + 名字下置」在本仓历史上成立且自洽，**本批考古判定无技术冲突**（没有第二种形态与作者实拍互相矛盾）。

---

## 3. 几何数值表（375×812，`safe = 0`，实跑读数）

| 量 | 实拍折算（1134×246 ÷ 3.024） | 本批实测 | 断言 / 说明 |
|---|---|---|---|
| 头像直径 | 50.6 CSS | **56** | 取「≥ 实拍且仍在既有档」的最近一档；56 = 视宽 14.9%，与 15px 名字字身比 3.7 |
| 头像 top（静止） | 5.9（贴窗口顶的滚动态） | **72** | 静止态锚在内容流原位：组顶 64 + 组 padding-top 8（`64 ±1` / `72 ±1` 双钉） |
| 头像 top（贴顶） | 5.9 | **8** | sticky 贴窗口顶后 = 组 padding-top；`|avTop − 8| ≤ 1` |
| 头像水平中心 | 185.8（中线偏 −1.7，手抖采样） | **187.5**（偏差 0.00） | `abs(avCx − vw/2) ≤ 1` |
| 胶囊尺寸 | 47.3 × 15.2 | 74.88 × 24 | 宽度随内容（`Spider` + `padding 3px 14px`）；高度 24 = 字号 15 的 normal 行高 + 上下 padding 3 |
| 胶囊 top / bottom | 60.8 / 75.7 | 134 / 158 | `胶囊 top ≥ 头像 bottom − 1` |
| 头像底 → 胶囊顶 | 4.3 | **6.0** | 基座 `gap: 6px`（既有 token 档，落在作者令 6–10 区间下沿） |
| 胶囊中心 ↔ 头像中心 | 0.0 | **0.00** | `≤ 1px` |
| 首条消息行顶 | —（实拍为消息从其两侧穿过） | 176（距胶囊底 18.0） | `firstRowTop ≥ nameBottom`；176 = 96 让位 + 行 margin |
| `#chat` padding-top | — | 96 | 渐隐带 88 + 基线 8（`--muse-head-top = -96px`） |
| 双钮 | 48px 圆、cy 24.8（右搜索） | 48px、top 16、cy 40 | 贴顶态 `abs(avCy 36 − 钮 cy 40) = 4`（`spread ≤ 12`） |
| 渐隐带 | 有（消息在其下先糊后没） | 88px，mask `0% / 55% / 100%` | 两段式原值回归 |

两条**有意保留的差**（不是漏改，写在这里备案）：

1. 胶囊高 24 vs 实拍 15.2 —— 实拍那颗字面约 13px、内距更紧；本批沿用基座档 `padding: 3px 14px` + `--mfs-sub`（15px），理由：① 15px 与 56px 头像的字身比 3.7 落在「头像为主、名字为辅」；② 改 padding/字号会把名字的可读性压到 11-12px 档，与既有 `--mfs-sub` 单源纪律冲突（字号 token 只有一处定义）。作者若要更薄，下一批改 `#session-name` 的 padding 即可，不动其它面。
2. 头像 56 vs 实拍 50.6 —— 差 5.4 CSS（约 10%）。56 是本仓「收小批」定的档（`06:58` 令），且是实拍的上一档；再收一档（52-54）会同时破 56 档与 dock 中键 44px 的边缘统一批口径。

---

## 4. 六条机械断言与实测读数

harness：`tests/mobile-muse-head-restore-shots.mjs`（新增），375×812 × 浅/深 × 静止/滚动/无名 + 桌面 1280，**ALL PASS**，每腿零 console error。

| 断言 | 判据 | 实测 |
|---|---|---|
| A 不存在顶栏 | 顶部无「高 ≥ 40 × 宽 ≥ 90% × 有可见墨迹（背景 α>0.02 或边框 α>0.02）或 fixed」的横贯带；`.g2-chat-head` 本体零背景 / 零描边 / 零 backdrop | 全文档扫描 **0 条**；本体 `pos=sticky bgA=0 bdBottom=0 backdrop=none size=343×106` |
| B 胶囊在头像正下方 | `abs(nameCx − avCx) ≤ 1` 且 `nameTop ≥ avBottom − 1` | `0.00` / `gap 6.00`（nameTop 134 / avBottom 128） |
| C 头像与双钮同带 | 贴顶态 `max(abs(avCy − taskCy), abs(avCy − searchCy)) ≤ 12` | `spread 4.00`（avCy 36 / 双钮 cy 40）。静止态 spread 60 属 Muse 静止形态（头像在流内原位），不判 |
| D 头像水平居中 | `abs(avCx − vw/2) ≤ 1` | `0.00`（187.5 / 187.5） |
| E 内容从头像后穿过 | 滚动态存在消息行渲染盒与**头像盒**相交（面积 > 4） | scrollTop 892：相交 1 行、最大交叠 3136px²；贴顶后头像顶 8 |
| F 无名态 | 胶囊 `display:none` + 宽高 0 + 头像仍居中 | `display=none w=0 h=0 text=""`、`abs(avCx − vw/2) = 0.00`、组高 76（56 + pad 8/12，无空洞） |

口径说明（写进 harness 注释，避免下一代误解）：A 只认「有墨迹」的带——裸 `position:fixed` 零背景容器（页面栈 `#mobile-pages`）不算顶栏；C 在**贴顶态**量（作者实拍就是贴顶滚动态）；E 量**头像盒**而非组盒（组盒是不画像素的容器，量它会假阳性）。

---

## 5. 9 条 spec 按新契约改写（逐条 + 结果）

| # | 文件 | 改动 | 结果 |
|---|---|---|---|
| 1 | `tests/mobile-v3.spec.mjs` T5 | `position: fixed` → `sticky`；新增 `z-index: 26` 与 `background-color: rgba(0,0,0,0)` 两条（「无顶栏」的最短机械表达） | 6 passed / 1 failed（T2 徽标 8≠10，**main 同树同红，非本批**）；T5 绿 |
| 2 | `tests/mobile-pinned-search.spec.mjs` P7 | 聊天根搜索键垂直落点 `top: 4` → `16`（4 是「与顶栏中线同轴」的派生值）；48×48 / right 359 / 圆钮材质不变 | 11 passed |
| 3 | `tests/mobile-topline-clear.spec.mjs` T1 | 判定无需改（断言 `#header` display none + `#chat` mask 手机层 none，顶栏有无都不影响） | 3 passed（复跑确认） |
| 4 | `tests/mobile-chat-topclip.spec.mjs` C2 | 判定无需改（clip 归零 + mask none 与顶栏无耦合） | 4 passed（复跑确认） |
| 5 | `tests/muse-avatar-shrink-shots.mjs` | 40 → **56**；名字横排（垂直同行 + 8px 间距）→ **下置**（`nameTop ≥ avBottom − 1` + 中心 ≤1px）；`headPos fixed` → `sticky` + `--muse-head-top` 非空；贴顶判 **avTop = 8**；字号 14 → **15px** | ALL PASS |
| 6 | `tests/chat-head-position-shots.mjs` | P① 顶栏贴顶高 56 → **组顶 64±1 + `--muse-head-top` 在场**；P② 单行成组 → **下置成组**；P③ 静止零叠压 → **首行顶 ≥ 胶囊底**；P⑤ 顶栏 rect 不变 → **sticky 贴顶（组顶 0、头像顶 8）+ 消息行穿头像盒**；P①′ 无名档组高 56 → 76；新增 `rowsBehindAvatar` 探针 | ALL PASS（25 项） |
| 7 | `tests/mobile-muse-avatar-shots.mjs` | ② fixed → **sticky（非 fixed）**；③ 40 → **56**、名字横排 → **正下方**；③-b 顶栏贴顶 → **组顶 64 + 头像顶 72**；④ 顶栏 rect 不变 → **sticky 贴顶 + 头像盒相交**；新增 `rowsBehindAvatar` | ALL PASS（chromium + webkit × light/dark + fallback + 桌面） |
| 8 | `tests/muse-restore-smartcrop-shots.mjs` | A① `flex/row` → `flex/column`、40 → **56**、名字横排 → **下置**、fixed → **sticky**；名字材质四条改回「`--glass-bg` 真值 + 零 border + 零 shadow + 有 blur + 999px」 | ALL PASS |
| 9 | `tests/dock-avatar-edge-unify-shots.mjs` | 顶部头像 `40 → 56`（dock 中键 44 与边缘 token 零改动） | ALL PASS |

**退役**：`tests/mobile-chat-topbar-single-row-shots.mjs`（`git rm`）——它钉的是被回退的契约（fixed 顶栏 / 头像 40 / 名字横排），保留只会变成「错的绿」。`evidence/20261102_mobile-chat-topbar/` 目录**保留作对照证据**（本批把其中 `rest-{light,dark}.png` 复制为 `prev-batch-rest-{light,dark}.png` 放同视口并排）。

**相邻面复跑（防误伤）**：`mobile-dock.spec` 21 passed、`mobile-canvas-sheet.spec` 14 passed、`mobile-panel-title.spec`（独立脚本）ALL PASS、`mobile-topbar.spec` 3 passed、`ime-guard.spec`（独立脚本）ALL PASS。

**存量红申报（非本批引入，main 同树同值）**：

- `mobile-v3.spec.mjs` T2 —— `.mh-chrome-badge` = 8 ≠ 10（任务夹具口径漂移）；worktree 与 main 逐字同红。
- `liquid-glass.spec.mjs` M3/M4/M5/D3/T1/R1（6 红）——`.g2-fab` 折射层与 token 探针；worktree 与 main 红名单与失败行号逐条相同。
- `make check-visual` —— 本机 GNU Make 3.81 解析 `Makefile:27` 的 `$(shell …)` 报 `missing ')'`（worktree 与 main 同错，属构建工具链问题，与本批零关系）。改直调其等价命令 `node scripts/visual-baseline.mjs --check --max-ratio 0.0005`：**8 项 DIFF，worktree 与 main 的差异像素数逐个完全相同**（`app-empty-*` 16963/17003、`chat-messages-*` 126043/126986、`settings-llm-empty-*` 78803/79182、`bgtask-panel-*` 尺寸 378×146 vs 338×144）⇒ 桌面基线漂移**先于本批存在**，本批对桌面渲染零影响（本批全部规则挂在 `body.mobile-layer` 之下）。

---

## 6. 证据

目录 `evidence/20261102_muse-head-restore/`（375×812，零 console error）：

- `rest-light.png` / `rest-dark.png` —— 静止态：无顶栏、56px 头像悬浮 + 名字胶囊在下、首条消息在下方
- `scrolled-light.png` / `scrolled-dark.png` —— 滚动态：头像贴顶、消息文字从头像后穿过
- `unnamed-light.png` / `unnamed-dark.png` —— 无名态：胶囊不渲染、头像仍居中、组高 76 不留空洞
- `prev-batch-rest-light.png` / `prev-batch-rest-dark.png` —— 上一批同视口对照（通栏毛玻璃带形态）
- `desktop-1280.png` —— 桌面回归（无 `.g2-chat-head`、`#header` 头像 28px）

---

## 7. 未触碰面

dock（五栏位 / 中键 44px / 胶囊几何 / 边缘 token）、输入条、左右钮的尺寸与材质（只回退垂直落点）、气泡与消息行节奏、面板标题 B 批大标题、桌面端（`#header` 28px 头像行 + 顶部消失点 mask + `#chat padding-top` 114 实测值）。

相关概念见 [[glossary]]；上游形态沿革见 [[20261102_090250_mobile-chat-topbar-single-row]]、[[20261102_muse-avatar-shrink__delegate-nebflow-d616c46a]]、[[20261101_235748_chat-head-position-fix__delegate-nebflow-9c9ef879]]。

---

## 8. 文档库校验

`python3 /Users/kaiyu/.stand/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py --root <workspace>/docs`

```
wiki-docs 校验：通过（…/docs，148 篇文档，零违规）
exit code = 0
```
