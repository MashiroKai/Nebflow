# 统一按钮+玻璃设计实施批 · 报告（2026-11-01）

设计单源：`docs/design/20261101_141229_unified-button-design__cc28a33e.md`（作者已批，
实施批增章 §9 = 非悬浮墨玻璃包 token 定稿）。分支 `delegate/delegate-nebflow-ac7ffa98`，
基线 7dd5fe226（侦察 commit f9feaeb71 cherry-pick 为 0fcab99e5）。

## 一、三阶段落点（全部落在 `src/main/resources/web/css/glass.css`，556 行）

### 阶段〇 玻璃单源包（基础设施）

- 新建 `css/glass.css` 取代 `css/liquid-glass.css`（液态玻璃 v2/v3 F1 配方整体退役）；
  `index.html` 换链接（原位：sapphire 之后）、删 `#g2-refract-defs` SVG；删 `js/liquidGlass.js`
  与 `main.js` 的 initLiquidGlass import+调用。
- `css/stand-tokens.css`：`--g2-fab-*`/`--g2-input-*`/`--g2-ink-*` 三族退役（veil/fade 子族保留）。

### 阶段一 悬浮按钮包（面级② + 圆钮级③）

- **面级浮件档**（材质三件套 blur24 sat1.15 双写 + `--cv-shadow` + 顶缘折射线）：
  `#input-bar`（原配方回归 = 设计稿 D10）、`#queue-bar.visible`、对话框共享卡整块
  （modal.css 迁入）、`.ask-input`、slash/mention 下拉。
- **圆钮级浮件档**：canvas 四圆钮（source/ref/find/refresh）、`ask-fab`、`deck-fab`、
  `#canvas-head-close`、`.g2-fab` 全族——材质 + hover 1.05 + active 0.96 + 选中
  sapphire 12%/35% + disabled .35 + focus-visible；`.g2-fab` 40px/icon18；手机
  `body.mobile-layer` 48px/icon20 + 弹簧 0.88（180ms spring）+ reduced-motion 降级
  （mobileLayer.css 逐面弹簧块归本包）。`#deck-fab[aria-pressed]` 14% 特例档删除
  （统一选中档）。
- 状态面 `#stop-btn` 扁平化（去 blur8 孤儿玻璃底，hover color-mix 抬亮）。

### 阶段二 非悬浮墨玻璃包（第④层，设计稿增章 §9）

- `--ig-*` 六 token（值表见设计稿 §9.2）：墨面 = 不透底 + 镜面高光 + 浮影，
  **零 backdrop-filter**（显式双写摘除，防 `.g2-pill` 基材继承）。
- 落点：`.g2-pill-primary` / `#memory-modal-save`（配方基准件）、sapphire.css 主操作块
  （dropbox-send 非绿位/cfg-btn-primary/fm-*/fm-gs-save/fm-req-accept）、登录键族、
  slash-mention 墨高亮 `--g2-ink-*`→`--btn-ink-*`。
- **发送键绿色 = 强调档**：维持 `--ctl-send-*` 扁平实底不回翻（v3 拍板）。
- 气泡半透明实底档（第①层）：`.bubble.user/.ai`、`.ask-bub.*`、chat-sk 骨架、
  ob-ghost-seat、user-media pop 面全部消费 `--glass-bub-*`（color-mix 出 α，气泡
  禁独立 backdrop-filter 滚动性能纪律）。

## 二、验收数字（全部机器产出，落本目录）

| 项 | 结果 | 证据 |
|---|---|---|
| 机械探针 | **8 组合**（Chromium+WebKit × 390×844+1280 × 深浅）× **432 断言全绿**，零 FAIL | `probe-after-report.json`（`tools/probe-after.cjs`） |
| 实拍 | **20 张全矩阵**（all/canvas/chat/dialog/mobile × 双引擎 × 深浅）零 console error | `shots-after/` + `shot-report.json` |
| 滚动帧率 | chromium 46.7→45.1fps（dark）、webkit 56.1→55.8fps（dark），light 补测 44.8/55.4 —— 基线持平（±2fps 跑间噪声内） | `scrollfps-after-report.json` |
| CSS 审计 | backdrop 246→226 声明，**双写率 100%**（113/113，notDual=0）；未定义 var 引用零新增（no-fallback 11=11、with-fallback 23=23 逐名比对） | `cssaudit-after-report.json` vs `cssaudit-report.json` |
| checkJs | impl 树 tsc 365 条 = merge-base 7dd5fe226 快照 365 条，**零新增**（逐条 diff：仅 main.js 行号整体平移 2 行 = 删除 initLiquidGlass import+调用所致，错误码/列号逐条相同） | `baseline/checkjs-baseline.txt` |
| 迁移前后对照 | 迁移前 harness+基线截图（`baseline/`、`shots/`、`probe-report.json`）vs 迁移后全套同 fixture 对照 | 本目录 |

## 三、记账申报（Harness 伪影，非产品回归）

- **ResizeObserver loop pageerror**：queue-bar 展开夹具诱发，main 树同 fixture 同样
  触发（实证在案）= 产品既有良性通知；`lib.cjs` 单列 `roLoop` 记账不混入 error 计数。
- **/agents/ 404**：fixture 磁盘真文件打桩下产品 boot 链的正常探测，单列 `api404` 计数。

## 四、待裁三项（维持现状，未动）

1. **M9 渐隐带**（`#g2-edge-fade`）：逐字保留（桌面 display:none / 手机 display:block，
   探针 B5 组断言钉住）。
2. **D12 独立窗**：未动（不进本批收编面）。
3. **D11 `.ask-fab` 桌面 44px**：保留特例档未动（探针按 44 断言）。

另有 `.canvas-graph-toggle` 已零创建退役（JS 层），设计稿 D4 仅执行 find 半项。

## 五、关键假设

- 气泡消费「半透明实底变体」（`--glass-bub-*` color-mix α，≈90% 不透明度，零 blur）：
  设计稿 §3 气泡禁令的落地解释，对比度肉眼复核可读。
- `#stop-btn` 扁平化：stop 非主操作件，不吃墨玻璃包（详见 §9.3 表）。
- 真实合并 main（9596305d4）进分支解冲突后才合并回 main——合并 commit hash 见
  delegate 完工申报。

## 六、工具与复跑

`tools/`：`lib.cjs`（打桩 harness）、`probe.cjs`/`probe-after.cjs`（432 断言）、`shot.cjs`、
`scrollfps.cjs`、`cssaudit.cjs`。复跑示例：
`node tools/probe-after.cjs --engine chromium --theme both`。

## 七、合并后回归（main a17090124 并入分支 4e3da662b 后）

- 机械探针重跑：8 组合 × 54 断言全绿零 console error（`merge-regression/probe-mergeret-*.json`）。
- 实拍抽查：chat 双主题（`merge-regression/shots/`）——气泡半透明实底/玻璃材质/发送键绿色强调档全部在位。
- 冲突解法：index.html（glass.css+cards.css 双链共存，liquid-glass 不复活）、modal.css
  （材质迁 glass.css 照旧 + 卡片批 #takeover-box 摘除裁定并入，glass.css 三处选择器
  同步摘除 #takeover-box——cards.css M1 接管弹窗整面归卡片批，零越界）。
