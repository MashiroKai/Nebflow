# 修复批 · 胶囊固定尺寸（作者令 2026-10-25 13:18 逐字）

工单：「现在每个胶囊的大小还是不是固定的呀，还是会根据内容自适应，我要保持一个固定的大小。」

要求四项：①顶栏胶囊全脸统一固定宽高（高沿用 36px；宽取设计 token 单一来源并写明依据）②超长内容省略号截断（绝不溢出、绝不撑开几何）③#840 size-uniform spec 扩展为固定尺寸断言（跨面板同宽同高、超长标题不变形）④hover/trackpad 交互（#846）不受影响——只动几何与截断，不碰事件层。

交付：纯 CSS 面（零 JS diff、零 sbt）+ spec 扩展/重钉全绿 + 证据截图三张（亮暗 × 超长标题/环开）。

---

## 范围：胶囊脸盘点

| 脸 | 元素 | 处置 |
|---|---|---|
| 标题头主胶囊（文件卡 / 插件 / 图谱 / 任务列表 / 设置——页签/图谱同脸换题） | `.canvas-header`（桌面 docked / overlay / 卡窗 / deck 叠卡同元素） | **固定 180×36**（`body:not(.mobile-layer)` 规则；deck 叠卡态既有 `--stack-scale-inv` 反量回乘不受影响） |
| 环页签胶囊（面板切换 hover 环：中心 + 上下缘位） | `.cr-capsule` | **固定 180×36**（修前 fit-content / max-width 150 自适应） |
| deck 叠层浮钮 | `#deck-fab` | 不动——40×40 图标钮，无标签非胶囊（既有固定几何） |
| 手机层通栏卡头 | `body.mobile-layer .canvas-header` | 不动——mobileLayer.css §⑨ 自有全宽形态，`:not(.mobile-layer)` 让位（#840 同口径） |

## 固定值与依据（要求①）

单一来源 = `sapphire.css :root` 新 token（亮暗同值，不入暗档块）：

- **`--capsule-h: 36px`** —— 沿用 #840 C-① 钉的全面板档（值不变，单一来源自 split.css 规则升格到 token；split.css 两处消费 `var(--capsule-h)`）。
- **`--capsule-w: 180px`** —— 按最长常规标签反推：最宽常驻面板标签「文档图谱 / 任务列表」48px 文本 + 图标 14 + pop 钮 26 + gap 13 + padding 19 ≈ 120px，取一倍余量整档；且落在两个既有基准之间——#840 长名可读下限（size-uniform C3c ≥180）与主窗面板 cap 最小档 220px（居中批 `max(220px, …)`），**标准面板永不触发收缩护栏**；180 = 4px 基数栅格 45 格。

## 修复（split.css，两处）

- **C-③ 标题头宽度自适应**：`body:not(.mobile-layer) .canvas-header` 加 `width: var(--capsule-w, 180px)`（修前 `width: fit-content` 是 UI 批⑨ 胶囊化遗产，随内容伸缩 = 作者不要的自适应）。超长内容由既有 `.t-name` ellipsis 链（title/t-inner/t-name 三级 `min-width:0`，#840 C-② 修）兜底；既有 `max-width` cap（min(76%,…)/max(220px,…)）保留为窄面板极值护栏——触发时同走 ellipsis，非循环约束（对 #canvas-panel/#canvas-slide）。
- **环胶囊**：`.cr-capsule` 修前 `max-width:150px` + fit-content，改 `height/width: var(--capsule-h/w)` + `justify-content:center` + `max-width: min(var(--capsule-w), 92vw)` 窄窗护栏；超长页签名既有 `.cr-name` ellipsis 截断。**环几何零 JS 改动**：canvas.js `layout()` 的 `h = offsetHeight` / `edgeY` 反解 / 越出量全部动态读数，36px 面自动流入；`anchor()` 仍锚 `#canvas-title` 中心——中心环胶囊（180 宽）内容正落标题位，环开/收零跳变（无缝 morph 语义保持，F4c 实测中心与标题头同盒）。

## 越出界重钉（几何换挡，非回归）

36px 统一面后 `layout()` 的 `edgeY` 反解被 **h+2 防叠底线**主导（面板顶→环锚 40.4 < 10+out），上缘位越出量从「~0.5×胶囊高」换挡为 `h+2−headerTop` 档（实测 out=15.6px，cap 36px）。canvas-capsule-ring.spec **CR2-6 / L1 两界重钉**：`≤ capH×0.5+2`（24px 面代界）→ `≤ capH−6`（2px 舍入余量），文件头注明换代依据；公式与 JS 零改动。「顶部超出不要太多」语义保持（越出仍 < 全高，下缘位不越底缘腿保留）。

## 验证（全部实跑）

| 套件 | 结果 | 备注 |
|---|---|---|
| **tests/capsule-size-uniform.spec.mjs**（扩展） | **31/31 PASS** | C1-C6（#840 原钉全保持）＋ 新增 F 系：**F1** 跨面板同宽 180（文件卡/插件/图谱）；**F2** 盒不随内容变（52px vs 250px 文本同 180×36）；**F3a-d** 223 字符超长注入标题盒恒 180×36 + ellipsis 生效 + 右缘不出面板 + 全文保留 DOM；**F4a-c** hover 环全胶囊（中心+缘位）180×36、中心与标题头同盒；**F5** 卡窗 180×36；**F6** token 单源静态钉（声明唯一 + 两脸 var() 消费 + 桌面规则零 fit-content）＋ 亮色腿几何恒定 |
| canvas-capsule-ring.spec.mjs（重钉 CR2-6/L1） | **36 PASS / 1 FAIL** | 唯一 FAIL＝CR2-5 材质断言，**HEAD 既有基点**（#840 批记录「CR2-5 材质=HEAD stash 逐位同败既有基点」；本批零材质面改动）；CR2-6 重钉后实跑 out=15.6px ≤ 30 ✓，CR4/CR4b/CR4c hover 连续性（#846 邻族）全绿 |
| **capsule-hover-chain.spec.mjs**（交互回归 = 要求④） | **9/9 PASS** | 光标不离胶囊连续滚动-点击链零失效——只动几何未碰事件层的实证 |
| canvas-capsule-six.spec.mjs | **19/19 PASS** | S5 叠卡态反量回乘（视觉尺寸 = 常态基准）活值比对，固定宽无损 |
| canvas-card-window.spec.mjs | **22/22 PASS** | 卡窗面回归（F5 邻族） |
| canvas-cardhead-capsule.spec.mjs | 25 PASS / 3 FAIL | CC6-1/2/4 三红＝**HEAD 逐位同败**（#840 批 stash 对照实证的存量，直滑/长按腿，非本批引入） |
| canvas-stack.spec.mjs | **144/144 PASS** | deck 滚轮/叠卡邻族全量绿（deck 前卡胶囊固定宽 180 档回归无损） |
| checkJs gate | **260 = HEAD 260，零新增** | 本批 git diff 零 JS 面（门禁同值构造性成立）；260−基线 257 的 3 错全为 nearpath.js 存量（#840 批已记录） |

证据截图：`evidence/20261025_capsule-fixed-size/shots/`——1-xl-title-fixed-dark / 2-ring-open-36-dark / 3-xl-title-fixed-light（超长标题 180px 盒内省略号收口 + 环开三胶囊同宽 36px 档）。

## 未做 / 边界

- CR2-5 材质、CC6 直滑/长按三红、nearpath.js 基线行：均 HEAD 既有存量（前批 stash 对照实证），不属本批工单，未越界代修。
- `#deck-fab`（40×40 图标钮）与手机层通栏卡头：非「胶囊脸」（无标签/自有全宽形态），不在固定几何射程——见上表盘点。
- 环缘位越出量 15.6px 为当前面板几何的确定档（h+2 底线主导），若作者要回到「~半胶囊高」视觉需动 edgeY 反解公式（JS 面），属新令。

## 假设声明

- 「全部胶囊面」取**有标签的顶栏胶囊**义（标题头主胶囊 + hover 环页签胶囊，含其在 docked/overlay/卡窗/叠卡四态）；`#deck-fab` 图标钮与手机通栏卡头按上表理由排除。
- 宽度 180px 为本批从既有基准（#840 实测长名下限 180 / 面板 cap 220 / 常规标签 120px 需求宽）推得的 token 档；如作者要另一档，改 `--capsule-w` 一处即全站生效（F6 单源钉保证）。
