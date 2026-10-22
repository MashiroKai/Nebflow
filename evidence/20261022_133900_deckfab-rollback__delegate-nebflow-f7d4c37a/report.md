# 层叠浮钮回退批 · 修后报告（作者回访裁定：fab 迁回顶右原位）

分支 `delegate/delegate-nebflow-f7d4c37a` · 2026-10-22 · 三件全落前端（刷新即生效）。

---

## 裁定原文与语义

作者引用证据图 `…console-3symptoms/shots/03-deck-snapshots/after/desktop-light-5-deck-front2.png`
逐字：「堆叠按钮还是要放上面，我要改的是查看图片的时候，调整图片大小的那些控件，放到右下角。」

语义：三症状批 ② 的移位范围过宽——作者只要求**图片查看器缩放控件**（±/百分比/Fit）迁右下角；
**层叠浮钮（deck-fab）要回顶右原位**（top 10 / right 12）。缩放控件（右下）与文件名胶囊（顶部居左）
**不动**。回移安全性：原「顶行三件打架」的叠印前提已消失（缩放条已居底右），顶右 fab 与居左胶囊
分居面板顶行两端，无冲突。

## 改动清单（范围 = 仅 fab 回移；缩放控件/胶囊/底右簇槽位一律不动）

| 文件 | 改动 |
|---|---|
| `src/main/resources/web/css/split.css` | `#deck-fab` 底锚 `top:auto/bottom:16` → 回 **`top:10/right:12`**（原位）；注释改写（移位批历史保留 + 标注回退裁定）；`.canvas-header`/`.canvas-source-toggle`/`.canvas-zoom-bar`/html zoom/`.canvas-find-toggle,.canvas-graph-toggle` 五处注释中「fab 钉 right:12 底右」的失实陈述同步订正（**槽位数值零改动**） |
| `src/main/resources/web/js/canvas.js` | `syncDeckBox` fab 腿：`top = 面板底 − 56`（底锚）→ 回 **`top = 面板顶 + 10`**（顶右锚定，v5② 原式）；`right = 视口宽 − 面板右 + 12` 语义不变；头部模型注释同步（+ 回退批标注） |
| `src/main/resources/web/js/viewers/shared.js` | `addFindToggle` JSDoc 中「fab 迁底右 right:12」失实陈述订正（缺省 112 不动） |
| `tests/canvas-stack.spec.mjs` | **A1-5** 右下 `bottom16/right12` → 回右上 `top10/right12/40px`；**A13-3** 同步回顶右口径（uifix-quad **FAB-2** 本就钉 top10/right12，回退后自然复绿） |
| `tests/imgviewer-controls-shots.mjs` | **G3** 「fab 可见且底右」→ 「fab 可见且顶右（dt<40px）」；批头注释标注回退裁定（G1 胶囊居左 / G2 缩放条底右 / G4 三件零交叠不动） |
| `tests/canvas-html-toolbar-overlap.spec.mjs` | O-gap 行感知注释订正（簇 +48 维持、fab 已回顶右；断言逻辑本身零改动） |

## 验证（全绿）

`verify.log`（本目录）六段 + checkJs：

| 段 | 结果 |
|---|---|
| 1/6 `tests/canvas-stack.spec.mjs`（叠卡全家桶，含 A1-5/A13-3 fab 锚定钉） | **144 PASS / 0 FAIL** |
| 2/6 `tests/canvas-html-toolbar-overlap.spec.mjs`（底右簇正常/窄320/overlay 三态） | **22 PASS / 0 FAIL** |
| 3/6 `tests/canvas-cardhead-capsule.spec.mjs`（胶囊居左锚定，未动面回归） | **28 PASS / 0 FAIL** |
| 4/6 `tests/uifix-quad.spec.mjs`（FAB-2 钉面板右上 40×40 复绿） | **33 PASS / 0 FAIL** |
| 5/6 `tests/imgviewer-controls-shots.mjs`（亮暗双主题 G1-G4） | **10 PASS / 0 FAIL** — G3 实测 `fab dt=10`（顶右）；G2 缩放条 `db=27/dr=65`（底右不动）；G4 三件两两零交叠 |
| 6/6 `tests/canvas-stack-ui5-shots.mjs`（叠卡场景亮暗截图） | exit 0，零 pageerror |
| `scripts/check-js-types.mjs` | **PASS：257 errors = baseline 257，零新增**（gate：touched 文件零错误） |

关键证据帧（本目录 `shots/`）：

- `03-deck-snapshots/desktop-light-5-deck-front2.png` / `desktop-dark-5-deck-front2.png` —— 与作者裁定引用
  同名场景：**fab 回顶右**，叠卡快照层次保持（近亮远暗有梯度、深处卡可辨，三症状批 ③ 的 snapfix 不受影响）。
- `02-image-viewer/light-1-image-tab.png` / `dark-1-image-tab.png` —— 裁定目标态同框：fab 顶右 +
  缩放条（−/62%/+/Fit）底右 + 文件名胶囊顶部居左，三件互不打架。

## 未动面（如实）

- 缩放控件位（`bottom:18/right:64`，html pane 208/窄面板二行回落）、文件名胶囊（顶部居左）、
  底右圆钮簇 +48 槽位（source 64 · ref 112 · find 160 · 缩放条 208）——作者明令「不动」，全部原样。
- 顶右 fab 与 nf-findbar（pane 内 `top:10/right:14`）的共存关系 = 移位批之前的历史原状，本批照原状恢复，
  未重新设计。
