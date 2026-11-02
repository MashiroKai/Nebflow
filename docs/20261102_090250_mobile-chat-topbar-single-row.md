---
type: design
status: implemented
---

# 手机端会话顶栏单行三段式批：头像+名字并入顶栏同一行（2026-11-02 07:23 作者令）

作者令原话（07:23，实拍 `evidence/20261102_muse-avatar-shrink/rest-light.png`）：

> 「手机端会话顶部，头像为什么不在顶栏与左右两颗按钮**同一行**居中，而是掉到下面一行。
> 现在给出明确要求并实施。」
>
> 1. 手机端会话页顶栏改为**单行三段式**：左＝菜单/返回钮，中＝头像 + 名字（水平居中对齐），
>    右＝搜索钮；三者垂直中线对齐（同一条水平轴线）；
> 2. 头像尺寸按上一批的 56px 档？——单行内空间有限，若 56px 撑不开，按 36-40px 档收小
>    以适配顶栏高度（给出选定值与理由…）；名字在头像右侧同行（横排）或头像下方极小字？
>    ——**优先横排**；若横排放不下则申报并说明替代方案；
> 3. 顶栏高度按手机标准（≥56px 内容高 + 安全区），毛玻璃材质走既有玻璃包单源（浅深双主题）；
> 4. 顶栏**固定在顶部**（不随内容滚动、不做 sticky 变形），内容从顶栏下方开始，
>    顶栏不占额外空白带（原来的 96/64px 留白带一并收掉）；
> 5. 之前那条「未命名 root 幽灵玻璃条」修复（`:empty` 隐藏）保留；名字缺失时只显示头像并保持居中；
> 6. 桌面端零触碰。

被取代的上游形态：[[20261102_muse-avatar-shrink__delegate-nebflow-d616c46a]]（56px 大头像收小批）与
[[20261101_235748_chat-head-position-fix__delegate-nebflow-9c9ef879]]（位置批，64px 内容流锚 +
sticky 顶锚 + 名字下置）。本批把这两批的几何形态**整条退役**（节点迁移链、气味守望链、
玻璃单源、logo 回落、`:empty` 收口等语义全部保留），只重写「位置与形态」一层。

---

## 1. 取值理由

### 1.1 头像 40px（36-40 档取上沿）

| 候选 | 在 56px 单行内的余量 | 视宽占比（375） | 与名字（14px）字身比 | 判断 |
|------|---------------------|-----------------|---------------------|------|
| 56（上批现役） | 上下各 **0px** | 14.9% | 4.00 | 顶栏 56 高装 56 头像 = 零呼吸，且挤压左右钮的 72px 足印，撑不开 |
| **40（选定）** | 上下各 **8px** | **10.7%** | **2.90** | 与 48px 圆钮同处 56px 行内仍读出「中段身份、两端按钮」三段式；字身比 2.9 保持「头像为主、名字为辅」 |
| 36 | 上下各 10px | 9.6% | 2.57 | 已贴近两倍字高，读成小图标；与左右 48px 圆钮的量级差拉大到 1.33 倍，三段式重心倒向按钮 |

取上沿 40 而非 36：作者「调小」的意图是**别太大**（56 阶段的判断），不是「越小越好」；
40 是「不与 48px 圆钮同档、但仍明显大于名字字高」的最大可行档，视觉重量与三段式语义同时成立。
头像圆/描边/高光/浮影/芯片底全部沿用 `chat.css` 全局 `#agent-avatar-btn` 档，本批只改外径
（`mobileLayer.css:2895-2900`）。

### 1.2 名字：横排（作者令「优先横排」），容量申报

名字用 `position:absolute` 锚在「中线 + 头像半宽 20 + 间距 8」（`mobileLayer.css:2911-2933`），
`max-width: calc(50vw - 100px)` = **375 下 87.5px**，`white-space:nowrap` + 省略号收口。

- 容量实测：`Spider`（6 拉丁字）实测宽 **42px**；中文 4 字（14px×4 = 56px）也在容量内。
- **申报**：超过约 6 个中文字 / 10 个拉丁字的名字会走省略号（`text-overflow: ellipsis`），
  与左钮之间仍保留 8px 呼吸、绝不侵入右搜索钮足印（右足印 16+48、顶栏 `padding-right:72px`）。
  若未来作者要求「长名完整显示」，替代方案 = 名字改下置极小字（11px）两行式 —— 本批不做，
  因为那与「同行」要求直接冲突。
- 为什么名字**不参与 flex 居中**：作者验收 2 要求「头像水平中心 − 屏幕中线 = 0±1px」。
  若名字是同排 flex 子节点，整组（头像 + 8 + 名字）居中会把头像推离中线（375 下 `Spider`
  即偏 26px）。故头像单独居中（唯一流内子节点 + `justify-content:center`），名字绝对定位
  从轴线右侧起排 —— 视觉是「头像为轴、名字挂右」，机械上是「头像严格居中」。
  名字缺失（未命名 root）时只剩头像，仍居中。

## 2. 落地

### 2.1 CSS（`src/main/resources/web/css/mobileLayer.css`）

- 中块 ②/②-b（sticky 顶锚 + 负 margin 上提）**整条删除**（L2645-2656 留退役注记）；
  `--muse-head-top` 变量链退役。
- 文件尾批次块（L2825 起）六条：① 顶栏本体 `position:fixed; top:0; left/right:0;
  height:calc(56px + safe)`、`display:flex/row/center`、`gap:8px`、`padding:0 72px`、`z-index:26`、
  `background: var(--glass-bg)` + `blur(var(--glass-blur,24px)) saturate(var(--cv-saturate,1.15))`
  + `border-bottom: 1px solid var(--glass-border)`；② 头像 40px；③ 名字绝对定位横排；
  ④ `#g2-edge-fade` mask 收到顶栏下缘（元素尺寸 88px / blur18 / sat1.5 与材质**不动**）；
  ⑤ `.mh-chrome-btn { top: calc(4px + safe) }`（钮中心 safe+28 = 头像中心）；
  ⑥ `#top-overlays { top: calc(64px + safe) }`（真顶栏之下）。
- `glass.css` 降级双保险：`@supports not` 与 `html.no-backdrop` 两块各加一行
  `body.mobile-layer #chat > .g2-chat-head`（实底档 = `--glass-bg` 原值、摘 blur）。

### 2.2 JS

- `js/main.js` 顶让位观察器：新增惰性取节点 `getMobileHead()` + `headRo` 挂进既有 RO；
  手机层分支改成 `reserve = max(BASE_TOP_PAD, ceil(headBottom − chatTop) + BASE_TOP_PAD)`
  （按顶栏 rect 实测），桌面公式逐字不变；`chatChrome` 建/拆层时广播
  `nebflow-chat-head-mounted` / `-unmounted` 让观察器重取节点。
- `js/mobile/chatChrome.js`：删除 `syncHeadTop` / `--muse-head-top` setter / `headTopRo`
  （原位留退役注记），teardown 保留一次 `removeProperty('--muse-head-top')` 兜底；
  建层/拆层成功处各广播一拍。

### 2.3 轴线几何（375，safe=0）

| 量 | 值 | 断言 |
|----|----|------|
| 顶栏 rect | 0 → 56 | `fixed` / 高 56 ±0.6 |
| 头像 rect | 7.5 → 47.5（40px，中心 27.5） | 水平中心 187.50 = 屏幕中线（偏差 **0.00**） |
| 双钮 rect | 4 → 52（48px，中心 28.0） | 头像中心 − 钮中心 = **0.50**（≤1 容差；40px 在 56px 行内 flex 居中落在半像素上） |
| 名字 | top 19.1 → 35.9，left = 头像右缘 + 8.0 | 完全落在头像垂直区间内（同行）+ 间距 8.0 |
| 顶栏下缘 → 首条消息泡顶 | 56 → 70 = **14px** | `#chat padding-top` = 64（原 96） |

「14px」的推导：`#chat` 基线让位 8px（`main.js` `BASE_TOP_PAD`，顶栏下缘之下的最小呼吸）
+ `chat.css:1037` `.row { margin: 6px 0 }` 的行自身外边距 6px。行 margin 属消息行之间的既有
节奏（首行也吃同一份），摘掉会让首行与后续行不一致 —— 不动，**14px 即本批合理最小值**
（原 96/64px 留白带净收 50px 屏占比给内容）。

## 3. 验收读数（`tests/mobile-chat-topbar-single-row-shots.mjs`，ALL PASS）

375×812 × 浅/深 × 三态（rest / scrolled / unnamed）+ 桌面 1280 回归，零 console error：

- ① 头像与左右双钮同一水平轴线：`|avCy−taskCy| = 0.50`、`|avCy−searchCy| = 0.50`（≤1）—— 双主题一致。
- ② 头像水平中心 = 屏幕中线：偏差 **0.00**（`avCx = 187.50 = vw/2`）。
- ③ 头像 **40×40**。
- ④ 名字横排：`nameTop 19.1 / nameBottom 35.9` 全落在头像区间 `7.5..47.5` 内，间距 **8.0**。
- ⑤ 顶栏 `fixed` / 高 56 / `top:0` / `blur(24px) saturate(1.15)` / 浅 `rgba(255,255,255,.55)`、深
  `rgba(24,28,38,.68)`（`--glass-bg` 双主题真值）；滚动到 `scrollTop=833` 后 rect 逐值不变，
  无名态/桌面腿同绿。
- ⑥ 顶栏下缘 → 首条消息 **14.0px**；`#chat padding-top` **64**（原 96）。
- ⑦ 名字缺失：`display:none` + 盒高 0（`:empty` 收口保留），头像仍居中（0.00）。
- ⑧ 桌面零触碰：1280 下无 `.g2-chat-head`、`#header` 头像钮 28×28、header `flex/absolute`、
  渐隐带 `display:none`。
- 截图：`evidence/20261102_mobile-chat-topbar/{rest,scrolled,unnamed}-{light,dark}.png` +
  `desktop-1280.png`。

## 4. 既有 spec 按新契约改写（全绿）

| 文件 | 改写面 |
|------|--------|
| `tests/muse-avatar-shrink-shots.mjs` | 56 → 40；64px 内容流锚 → `fixed`/`headTop 0`；名字置于头像右（8px）而非正下；sticky → fixed |
| `tests/chat-head-position-shots.mjs` | P① 贴窗口顶 + 高 56；P② 单行成组（头像行内居中 + 名字横排）；P⑤ 滚动 rect 不变 + `--muse-head-top` 已退役（空串）；删「修前对照帧」注入；未命名腿改判顶栏形态 |
| `tests/mobile-muse-avatar-shots.mjs` | `assertCommon` ② sticky → fixed；③ 40px / 屏幕中线 / 名字横排；静止态改判 `headTop 0` + `avTop−headTop=8`；滚动态改判 rect 不变 |
| `tests/muse-restore-smartcrop-shots.mjs` | `assertMuse`：`flex/column` → `flex/row`、56 → 40、名字下置 → 横排、sticky → fixed；名字材质四条改写为「零自绘底/零 border/零 shadow/零 backdrop-filter/圆角 0」（玻璃叠玻璃退役） |
| `tests/dock-avatar-edge-unify-shots.mjs` | 顶部头像 `56 → 40`（dock 中键 44 与边缘 token 零改动） |
| `tests/mobile-v3.spec.mjs` T5 | 新增 `.g2-chat-head` `position:fixed` 断言（节点归属/可见性契约不变） |
| `tests/mobile-fab-search.spec.mjs` F1 | 聊天根 chrome 键 `top: 16 → 4`（页面壳按钮仍 16，本轮不改） |
| `tests/mobile-topline-clear.spec.mjs` T1 | `#chat` mask 手机层为 `none`（mask 释放块有意置空，旧口径在 main 同树即存量红） |
| `tests/mobile-chat-topclip.spec.mjs` C2 | 同上，mask 消费端在手机层为 `none`（锚点归零断言不变） |

存量红申报（**非本批引入**，main 同树复跑同样红）：`mobile-v3.spec.mjs` T2
（`.mh-chrome-badge` = 8 ≠ 10，任务夹具口径漂移）、`liquid-glass.spec.mjs` 的
M3/M4/M5/D3/T1/R1（`.g2-fab` 折射层与 token 探针，main 上 6 红）。本批未触碰这些面。

## 5. 未触碰面

dock（五栏位/中键 44px/胶囊几何/边缘 token）、输入条、面板标题（B 批大标题）、桌面端
（`#header` 28px 头像行 + 顶部消失点 mask + `#chat padding-top` 114 实测值全部原样）。
`#g2-edge-fade` 的元素尺寸与材质零改动 —— 只把 mask 可视区收到顶栏下缘以下透明
（滚入内容由顶栏本体 backdrop 先糊、顶栏玻璃后没）。

相关概念见 [[glossary]]；并批沿革见 [[20261101_221500_muse-avatar-restore-stack-smartcrop]]、
[[20261031_160300_mobile-fab-unify-search]]。

---

## 6. 合并记录

- 批主题提交（分支 `delegate/delegate-nebflow-15c0bcbf`）：`221391120`。
- 真合并 main：`9c59050b0`（`Merge branch 'delegate/delegate-nebflow-15c0bcbf'`）。
- 就地解冲突两处：
  1. `tests/mobile-fab-search.spec.mjs` —— main 侧已由「常驻搜索条批」（`870929a69`）
     退役并改名为 `tests/mobile-pinned-search.spec.mjs` ⇒ 取删除态，本批对 F1 的
     chrome 键 `top 16 → 4` 改写随之落到新文件的 P7；
  2. `tests/mobile-pinned-search.spec.mjs` P7 —— 同步为 `top: 4`（钮中心 = safe+28
     = 40px 头像中心），尺寸 48×48 / right 359 / 圆钮材质按该批契约不变。
  `mobileLayer.css` 为语义无交叠的自动合并（常驻搜索条块 + 顶栏批次块并存）。
- 合并树复跑（main）：`mobile-chat-topbar-single-row-shots`（ALL PASS）、
  `muse-avatar-shrink-shots`（ALL PASS）、`chat-head-position-shots`（ALL PASS）、
  `mobile-pinned-search.spec` P1-P10b（16 全绿）、`mobile-topline-clear.spec` T1-T3、
  `mobile-chat-topclip.spec` C1-C2、`mobile-dock.spec` D1-D19 —— 全绿。
- 存量红（非本批引入，main 合并前同树同样红）：`mobile-v3.spec.mjs` T2
  （`.mh-chrome-badge` 8≠10）、`liquid-glass.spec.mjs` M3/M4/M5/D3/T1/R1（`.g2-fab`
  折射层与 token 探针）。
