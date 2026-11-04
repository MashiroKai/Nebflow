# 复核判「已实现」的 4 条 —— 亲跑读数（不是引用别人的结论）

批：ui-batch-gap-close（delegate-nebflow-47a71c60）· 复核对象：审计 D/C 档 4 条

审计报告自陈其快照之后 main 又前进了 29 次，并明确写「这四条新 spec 的绿红读数本审计未跑」
（`ui-batch-delivery-audit__20261104_151123` 附录 A ②）。所以本批**亲跑**，读数如下（全程本仓树，
零宿主实例、零 sbt）：

| 条目 | 实现在树证据 | spec | 亲跑读数 | 判定 |
|---|---|---|---|---|
| **R83** 手机搜索扁平化 + 默认展开 + 上滑收右上悬浮钮 | `css/mobileSearchFlat.css`（158 行）、`js/mobile/socialSearch.js` 滚动收合腿（`COLLAPSE_AT=24 / EXPAND_AT=4`）、`tests/mobile-search-flat.spec.mjs` | `mobile-search-flat.spec.mjs`（@playwright/test） | **1 passed (9.2s)** | 已实现 |
| **R100** 消息下方 footer（时间/徽章/复制·转发键）几何与行为 | `tests/msg-footer-align.spec.mjs`（707 行 / 31 条断言）+ `js/messages.js` +9 | `msg-footer-align.spec.mjs`（独立 node 脚本） | **GREEN failures=0 / assertions=31**（chromium 与 mobile 两腿） | 已实现 |
| **R42** 手机顶部横滑切签（审计点名的真虚报） | `js/mobile/navController.js` `bindStripSwipe`（`STRIP_AXIS_PX=8 / RATIO=1.2 / SWITCH_PX=48`）+ `tests/canvas-top-tabs-swipe.spec.mjs`（493 行） | `canvas-top-tabs-swipe.spec.mjs`（独立 node 脚本） | **chromium ALL PASS / webkit ALL PASS → RESULT: GREEN**（各 8 帧） | 已实现 |
| **R37** 顶部两侧标签对比度（复核腿） | `tests/side-tab-legibility.spec.mjs` | `side-tab-legibility.spec.mjs`（独立 node 脚本） | **52 PASS / 0 FAIL → RESULT: GREEN** | 已实现 |

## 命令原文

```bash
node node_modules/@playwright/test/cli.js test tests/mobile-search-flat.spec.mjs   # → 1 passed
node tests/msg-footer-align.spec.mjs          # → GREEN failures=0 / assertions=31
node tests/canvas-top-tabs-swipe.spec.mjs     # → chromium ALL PASS / webkit ALL PASS / RESULT: GREEN
node tests/side-tab-legibility.spec.mjs       # → 52 PASS / 0 FAIL / RESULT: GREEN
```

（后三支是 `playwright.config.mjs` 列入 standalone 名单的独立脚本 —— 用 `node tests/x.spec.mjs`
直跑；用 `npx playwright test <路径>` 会报 `No tests found`，这是既有收集面口径，不是失败。）

## 日志
`R83-mobile-search-flat.log` / `R100-msg-footer-align.log` / `R42-canvas-top-tabs-swipe.log` /
`R37-side-tab-legibility.log`

## 结论
审计把 R83 / R100 判 D、把 R42 判 C，是**其快照时刻**的正确判定；`0960a0898` 把那四条真做出来之后，
D 类实际只剩 6 条（R05 / R06 / R08 / R69 / R70 / R71），本批已全部落地（见同级各子目录）。
R112 仍缺、未做，理由见知识文档 §4。
