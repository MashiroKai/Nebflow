---
type: design-spec
status: frozen
date: 2026-10-31
scope: 桌面 canvas 顶栏——关签钮（#canvas-head-close）离开居中标题胶囊、升格 body 层左缘浮钮，与堆叠钮 #deck-fab 左右对称（js/canvas.js syncHeaderActions/syncDeckBox/openCanvas + css/split.css 批②块改写）；手机端零触碰
---

# 电脑端关签钮左移批 · 设计规格书（2026-10-31 21:37 作者令）

关联：[[20261031_170000_liquid-glass-ab-final__delegate-nebflow-a1031f13]]（.g2-fab 液体玻璃材质单源）、[[20261031_161500_mobile-topbar-batch__delegate-nebflow-53e293d1]]（手机工具条「左叉号 ↔ 右堆叠视角钮」语义来源——本批 = 同款语义桌面化）、[[20261031_193000_弹窗退场批__delegate-nebflow-4c0dfd9f]]（同日批②⑤「顶栏换代：左叉号右堆叠视角钮」前身，本批为其左移追改）。

## §0 一句话目标

标签条（桌面 canvas 顶栏）上的关闭标签页按钮从居中标题胶囊的左座移到面板左缘，与堆叠按钮（#deck-fab，顶右浮动）左右对称；堆叠钮现位照现；标签条其余元素序不变；只动桌面端，手机端不碰。

## §1 对称关系（自行度量结论）

- 堆叠钮现位（照现不动）：body 层 `position: fixed`，顶 = 面板顶 +10，右 = 视口宽 − 面板右 +12（= 面板右 −12），40px `.g2-fab` 液体玻璃圆钮，z-index 250，`body.canvas-open:not(.mobile-layer)` 显形。
- 关签钮镜像：顶 = 面板顶 +10（同），左 = 面板左 +12；尺寸/材质/z-index/显隐条件与堆叠钮逐字同款 ⇒ 左右对称守恒随面板盒逐帧成立。
- 几何来源：与 fab 同在 `syncDeckBox` 的盒变落笔块（缓存盒四元全等即跳过），逐帧零写纪律不破。

## §2 改动清单

### 2.1 js/canvas.js

- `syncHeaderActions`：✕ 创建从 `hdr.insertBefore(closeBtn, hdr.firstChild)`（胶囊左座）改 `document.body.appendChild`，className `canvas-head-btn` → `g2-fab`；disabled 态与 `deckFab` 同规则（`tabs.size === 0` 同禁）。
- `syncDeckBox`：盒变块内增 `hc.style.top = r.top + 10` / `hc.style.left = r.left + 12`（与 fab 同帧落笔）。
- `bindDeckInput` 滚轮捕获域 `inDeck` 增 `tgt.closest('#canvas-head-close')`（叠卡态浮在左钮上滚轮 = 擦洗轮换，与 fab 同语义）。
- **latent 缺陷收口（左移批显性化）**：面板 flex-basis 开合过渡（0.32s，左缘从右缘向左展开）期间 offset 盒逐帧漂移，而 `syncDeckBox` 只在 initDeck/deckFrame/openDeck/resize/col-resize 触发——boot 时收拢残宽盒（offsetLeft=1428/width=2，守卫 `!r.width` 拦不住 2px）被缓存后无人重推。右锚 fab 被「面板右缘过渡全程不动 + top 恒定」恰好掩盖；左锚 ✕ 直接暴露（滞留 left=1440 屏外）。修法：initDeck 挂面板 `transitionend`（propertyName flex-basis → syncDeckBox）主腿 + `openCanvas` `setTimeout(syncDeckBox, 380)` 兜底（reduced-motion/无过渡路径）。

### 2.2 css/split.css

- `#deck-fab` 块尾增 `#canvas-head-close` 对称块：fixed / top `var(--cv-head-top,10px)` / left 12px（均 JS 逐帧覆写）/ z-250 / 默认 `display:none` + `body.canvas-open:not(.mobile-layer)` 显形 + `svg 18px`（对齐 `#deck-fab svg`）。
- 退役批② `.canvas-head-btn`（24px ghost 档，唯一消费面随左移退役，含卡窗态隐藏规则）；胶囊规则 `justify-content: space-between` / `.canvas-title flex:1` 保留（t-count 面仍在胶囊内）。
- 手机层隐藏并线：`body.mobile-layer #deck-layer, #deck-fab` 选择器列增 `#canvas-head-close`；卡窗隐藏列（`html.card-window-boot`/`body.card-window`）同步增列。

### 2.3 不动面

- 手机端：`.mf-cv-close ↔ .mf-cv-stack` 工具条（navController.js）零触碰；`body.mobile-layer #canvas-head-close { display:none !important }` 双保险。
- 卡窗态：✕ 维持全隐（卡窗自有窗关）。
- 叠卡态：左钮钉死固定位语义与 fab 同档（deck 驱动期间不随面板 transform 动，z-250 恒高于 deck-layer）。

## §3 判据与证据

- 证据脚本：`tests/canvas-headclose-left-shots.mjs`（TAG=before/after 双跑），产出 `evidence/20261031_headclose-left/{before,after}/`（d1-header / d2-deck-open / m1-canvas-toolbar 截图 + probes.json）。
- after 断言 10/10：S1 body 层浮钮 + 胶囊可见钮零；S2 顶对齐 fab（Δ≤1px）；S3 左 = 面板左 +12 ±1；S3b fab 右镜像 = 面板右 −12 ±1；S4/S4b 40×40 同 .g2-fab；S5 点左钮关活动页签（n−1）；S6 显示档在挂；S7 手机腿不动（mf 双钮在挂 + head-close display:none）；S8 叠卡态同顶 fab 分居中线两侧。pageerror 零新增。
- 回归：canvas-cardhead-capsule 28/28、canvas-capsule-ring 43/43、canvas-stack 144/144、chat-diff-canvas 9/9、canvas-capsule-six、capsule-size-uniform、capsule-hover-chain、canvas-glass-unify 全绿；checkJs gate PASS（344 < baseline 350，无新增）。
- 改前照（before/ 目录）：✕ 24px ghost 坐胶囊左座（胶囊 180×36 内「× bravo.md」）；改后照：胶囊纯居中标题，40px 玻璃 ✕ 浮面板左缘与右 fab 对称。

## §4 坑位记录

- `position: fixed` 元素的 `display: inline-flex` 经块化计算值为 `flex`——探针断言勿写死 inline-flex。
- display:none 祖先内的按钮 `getComputedStyle(b).display` 仍报自身值——可见性判据用 `getClientRects().length > 0`。
- `syncDeckBox` 的 `!r.width` 守卫挡不住收拢残宽（2px）盒：任何新增左锚浮钮都会复现本批的滞留缺陷，transitionend + 超时兜底是现成范式。
