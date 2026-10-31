# 手机端任务列表活跃过滤批 — 验证证据

作者令（2026-10-31 11:49）：手机端任务列表不要显示全部历史（含已完成），只显示进行中的。

## 变更面

- `src/main/resources/web/js/mobile/taskPanel.js`：已完成组（呈现层补组）整组退役——
  手机端列表面 = `collectChains()` 现役行（pending/running/ready-to-merge/interrupted，
  未归档），与桌面面板现役窗同口径；`collectDone()` 删除。interrupted（重启打断灰态）
  保留在运行组内以灰点显形（重启对账批语义不借 done 勾绿）。
- `src/main/resources/web/css/mobileLayer.css`：退役死类 `.tp-dim` / `.tp-g-done` / `.tp-g-fail`。
- `locales/zh-CN.js` / `en.js`：退役 `taskpanel.grpDone` 键。
- 桌面端任务面板（`taskList.js` / `#task-list`）：零触碰，维持全量现役窗口径。

## 375 视口截图（本目录 m375-*.png）

夹具 = 运行 4 + 排队 1 + 中断 1 + 终态 2（completed/failed 反证）。两主题读数一致：

- 分组只出「运行中 · 5」「排队 · 1」，无「已完成」组；
- `#70 completed` / `#71 failed` 零进列表；`doneCards = 0`；
- `#72 interrupted` 在运行组内，灰点（tp-g-stop）+「已中断」状态词；
- 头部统计 `12 · 5 运行中`（unitCount 不再计终态行）。

## 桌面回归（desk1440-tasklist.png）

同夹具下桌面 `#task-list` 现役窗：#61–#64 运行、#67 待合并、#72 已中断（重启打断
chip 在位）——桌面口径不变，终态行仍走结构视图/归档抽屉。

## 测试

- `tests/mobile-v3.spec.mjs`：7/7 过（T2 换代：活跃过滤断言 + interrupted 灰点钉）。
- `tests/taskpanel-blocked-retire.spec.mjs` + `tests/tasklist-chain-blocks.spec.mjs` +
  `tests/mobile-dock.spec.mjs`：35 例中 34 过；唯一失败
  `tasklist-chain-blocks.spec.mjs:195` 为亚像素对位阈值 flake（干净树复现证实，
  与本批无关）。
- 复跑 harness：`node tests/mobile-taskpanel-active-only-shots.mjs <outDir>`。
