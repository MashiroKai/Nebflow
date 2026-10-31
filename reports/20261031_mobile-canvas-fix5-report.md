# 手机端 canvas 修复批 · 报告（mobile-canvas-fix5，2026-10-31 23:29/23:35 作者令五件）

分支：`delegate/delegate-nebflow-30f2400d`｜规格：docs/design/20261031_mobile-canvas-fix5-spec.zh-CN.md｜证据：evidence/20261031_233500_mobile-canvas-fix5__delegate-nebflow-30f2400d/

## 五件落点（全部实施）

1. **双页叠开（①）** — `navController.js adoptActiveDoc()` 归还腿 `insertBefore` 失败回退 `appendChild` + 收编前扫口（host 内非当前 pane 全送回 `#canvas-content`）+ `closePageFace('canvas')` 离页即归还 + `mobileLayer.css` pane `display:flex !important` 通配收窄为「`.mf-cv-host >` / `.mobile-page-body >` 直子」双保险。根因链：锚邻位被摘 → insertBefore 抛错被吞 → 旧 pane 滞留 + 新 pane 收编 = 双 pane，叠加手机层通配 flex 压掉 `.active` 显隐即截图实证的上下叠开。
2. **标签条胶囊同族（②）** — chip 材质换 `.canvas-header` 同源 token（glass-bg/blur saturate(1.15)/border 底缘透明/sapphire-refraction 折射线/cv-shadow），999px 圆角，选中态 = sapphire 14% 底 + 45% 描边（`.mf-stack-card.on` 同构造）。滚动交互 = 手机端左右横滑既有腿（chip 条横滚 + 内容区横滑切卡 + 圆环进深），桌面上下滚不复刻。
3. **悬浮控件排布（③）** — ask-fab 手机层座位让位：文档角落圆钮簇可见时 fab 抬至 `innerHeight − 簇顶 + 12`（输入条同距联动），childList 观察兜底重算（簇晚于切卡事件挂载）；条带左右内距 76px 让位顶带 ✕/堆叠钮，渐隐带 56px。
4. **canvas → 看板（④）** — dock/页面注册表 zh、空态标题、deck 标题回落、zh-CN 七词条（diffOpenFailed/popOpenCanvas/viewCompare/header.canvas/perception 三条）全部「看板」；英文键值逐字不动。
5. **输入框错误态极简（⑤）** — 三面补齐：`queue-item.failed` 红字/红注记 → 中性字 + 红左缘 + 注记行首红图标（chatQueue.js 注入 circle-alert）；`.att-error` 红 12% 底块 → etched 底 + 2px 红左缘 + 中性字；askUser 附件失败 chip 红 60% 描边/实红进度/红字/下划线 → 25% tint 描边 + 中性进度轨 + 行首图标 + amber 无下划线重试 chip。行为契约零改动。

## 验证

- `mobile-canvas-sheet` 12/12（含新增 T6 收口/T7 胶囊+让位/T8 座位）、`inputerror-minimal` 2/2（新增）、`mobile-dock` 28/28（D1 换钉看板）、`canvas-ask-dock` 58/58、`mail-perception` 全绿（stale 提示换钉看板）。
- 广回归：canvas-stack / cardhead-capsule / capsule-six / errorfrozen-minimal / askuser-attach / mobile-v3 / topbar / popup-retire / fab-search / pop-canvas-open（结果见下方回归段）。
- checkJs PASS（344 < baseline 350，零新增）；console 零错误（证据脚本双主题全程）。

## 证据

- before：作者实拍 IMG_1653（归档 `before-author-report_IMG_1653.jpg`）。
- after：暗亮双主题五面截图 + 横滑动画帧 ×5（translate3d(-162px) + ringOpacity=1 探针同落盘）+ 每帧 JSON 几何探针（hostPaneCount=1、fab 座位 122px、fab×簇不相交、dock「看板」）。
