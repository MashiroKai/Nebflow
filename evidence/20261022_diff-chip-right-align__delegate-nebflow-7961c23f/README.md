# diff 摘要 chip 右对齐微调批 2026-10-22（作者回访令，带截图证据）

作者原话：「把diff胶囊靠右对齐」——交付气泡顶部的 diff 摘要 chip（`.nf-diff-chip`，
拆面板批交付物）从左对齐改为**靠右对齐**（与交付气泡右缘对齐）。**只动对齐**：
chip 形态（玻璃 pill / 图标 / `N files · +X −Y`）/ 交互（点击开关左栏面板）/ 其余
全部不动。

## 现状参照与改后

- 现状（改前）：chip 左对齐——参照 `evidence/20261022_diff-summary-split__delegate-nebflow-7961c23f/shots/1-chip-light.png`。
- 改后：chip 右缘贴交付气泡内容右缘（`rightGap = 0.00px`，实测亮暗双版），左缘
  自动留白（实测 129.6px）；pill 本征宽零变形（fit-content 收缩，非拉伸非百分比）。

## 实现（一处 CSS，零 JS 改动）

`css/diffView.css` `.nf-diff-chip`：

- 气泡 `.bubble` = **block 容器**（原 `align-self: flex-start` 本就无效，属死样式），
  chip 原以 `inline-flex` 落在行盒左侧。
- 改法：`display: inline-flex → flex`（块级化）+ `width: fit-content`（收缩至
  pill 本征宽）+ `margin: 4px 0 8px → 4px 0 8px auto`（左 auto margin 推右）；
  `align-self: flex-start → flex-end`（语义随行，本布局下不生效，容器若改 flex
  列时保语义）。移动层 css 无 `.bubble` 覆写，同一机制全层成立。

## 证据（shots/，`node tests/diff-chip-align-shots.mjs`，6/6 PASS）

- `1-chip-<dark|light>.png` — 对话流全景：交付气泡顶部 chip 靠右，右缘贴气泡右缘。
- `2-chip-<dark|light>-closeup.png` — 气泡特写：右对齐 + pill 形态/着色计数不变。
- stdout 断言：① `rightGap < 1px`；② `leftGap > 8px`（真右对齐非撑满）；
  ③ `display=flex` 且 auto margin 恰吸满剩余（fit-content 生效，形态未变）。

## 回归（全绿）

- `chat-diff-canvas.spec.mjs`（换代规格，本批 T1 加**右对齐几何断言**：
  `rightGap < 1px` + `display=flex`）+ 邻族 `chain-detail-diff` / `badge-injected-turn`
  / `bgagent-injected-bubbles` —— **21/21 passed**（同上批基线）。
- checkJs：**257 = 基线 257**。
- check-circular：与 main HEAD 输出逐字节同（既有 SCC[3]
  popArtifacts/reference/msgQuote 原样，本批 diff 纯 CSS + tests + evidence，
  不入 import 图，零新增环）。
- `npm run build`：**179 sources 全量入图（orphans: 0）**。

## 明确不做

旧批证据目录（`20261022_diff-summary-split`）与其 shots 脚本不改写——那是该批
合并时点的历史记录；本批证据独立成目录（本目录）。
