# diff chip 同线微调批 2026-10-22（作者回访令，带截图证据）

作者原话：「①『N FILES +X −Y』计数与 COMPLETED 同行（接在其后；窄面板允许截断
策略但常规宽必须一行）② chip 里 NEBFLOW 改 STAND」——交付气泡顶部摘要 chip
（`.nf-diff-chip`）不再独占一行：与四段式 header（… · COMPLETED 段）装进同一
flex 行（`.nf-diff-headline`），chip 接在 COMPLETED 段之后、右缘贴行右缘；夹具
项目段 NEBFLOW → STAND（品牌归一）。

## 现状参照与改后

- 现状（改前）：chip 独占一行右对齐——参照
  `evidence/20261022_diff-chip-right-align__delegate-nebflow-7961c23f/shots/2-chip-light-closeup.png`
  （header 一行 + chip 第二行右缘）。
- 改后：同一行 `DELEGATE · STAND · DIFF-SPLIT · COMPLETED  [∎ 5 files +351 −140]`
  （本目录 `2-chip-*-closeup.png`）；常规宽一行（行高 = chip 高，实测 0.00px 差），
  右对齐语义保持（rightGap = 0.00px 实测）。

## 实现

- `js/chat/injectedRows.js` `buildInjectedRow`：chip 挂载点改 `.nf-diff-headline`
  行容器（`headline.append(label, chip)`）；缺载荷气泡 label 原位直挂（逐字节
  不变）。live 与历史恢复同走本 builder，两路同构。
- `css/diffView.css`：新增 `.nf-diff-headline`（flex 行 + align-items:center +
  gap 8px）与行内 label 截断条款（flex:1 + min-width:0 + nowrap + ellipsis）；
  `.nf-diff-chip` 改行项形态（flex:0 0 auto 保本征宽，margin-left:auto 退役——
  右对齐由 label 弹性占满自由空间天然成立，右对齐批语义不变）。
- 窄面板截断策略 = label 省略号（溢出只截 header 文本，chip 永不失行、本征宽
  零变形）；手机层 CSS 无 `.bubble` 覆写，同一机制全层成立。
- 品牌归一 ②：夹具 header 项目段 `nebflow → stand`（`chat-diff-canvas.spec.mjs`
  三处 + 本批 shots 脚本）。该段在真实运行时 = 实际项目名（引擎
  `NotificationHeader` 单一来源不动），夹具即演示数据——旧批 shots 脚本与其
  证据目录不改写（合并时点的历史记录）。

## 证据（shots/，`node tests/diff-chip-headline-shots.mjs`，16/16 PASS）

- `1-chip-<dark|light>.png` — 对话流全景：交付气泡 header 与 chip 同行。
- `2-chip-<dark|light>-closeup.png` — 气泡特写：STAND 段 + chip 接在其后、
  右缘贴气泡右缘。
- `3-narrow-<dark|light>.png` — 窄面板特写（气泡压至 320px 模拟）：header
  省略号截断、chip 完整、仍一行。
- stdout 断言：① 同行（垂直中心差 0.01px < 3）；② 常规宽一行（行高 = chip 高
  0.00px）；③ chip 在 label 之后（gap = 8.00px）；④ 品牌段 STAND；⑤ 右缘贴
  气泡内容右缘（rightGap 0.00px）；⑥ 窄面板仍一行 + 省略号生效
  （scroll 252px / client 146px）+ chip 本征宽零变形（138.3px 恒定）。

## 回归（全绿）

- `chat-diff-canvas.spec.mjs`（本批 T1 加同线几何断言：垂直中心差 / 行高=chip高 /
  chip-after-label / label 可截断条款，右对齐 rightGap<1px 保留）+ 邻族
  `chain-detail-diff` / `badge-injected-turn` / `bgagent-injected-bubbles`
  —— **21/21 passed**（与前两批基线同）。
- checkJs：**257 = 基线 257**。
- check-circular：与 main HEAD 输出逐字节同（既有 SCC[3]
  popArtifacts/reference/msgQuote 原样；本批零 import 变更，不入 import 图）。
- `npm run build`：**179 sources 全量入图（orphans: 0）**。

## 明确不做

- 旧批证据目录（`20261022_diff-summary-split` / `20261022_diff-chip-right-align`）
  与其 shots 脚本不改写——历史记录。
- `tests/fixtures/bgagent-bluebubble/dispatcher-history-rows.json` 内的 nebflow
  项目段不动（bluebubble 历史行夹具，非本批面；其 spec 全绿）。
- 引擎 `NotificationHeader.RootProject`（NEBULA 回落段）不动——本批只动演示
  夹具品牌段，不改任何真实数据呈现规则。
