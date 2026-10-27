# REPORT · #910 追补 — 已关闭 Canvas 页签反复自动复活（作者报障 2026-10-27 11:27）

- 报障：「现在标签页还是老是会加载我已经关闭的」
- 分支：`delegate/delegate-nebflow-1f745a93` → 合并队列落 Mac main
- 变更：canvas.js 前端恢复/记账链修复 + 服务端 spec 两钉 + 前端 spec 三钉

## 1. 根因一句话

**`openTab`（canvas.js:709 原 705-709）把「任何一次新建页签」都当成显式重开落 `sessionReopens` 账，而恢复/投影路径（localStorage 缓存腿、readFile 恢复管线）能把带生效墓碑的 id 送进 `openTab`——服务端 `CanvasTabs.merge` 以 `reopenedAt > closedAt` 把墓碑翻成惰性，于是自动恢复不仅复活页签，还顺手注销了关闭意图，复活变永久、每次加载重演。**

## 2. 定位证据（归档取证，非推测）

- `~/.stand/canvas_tabs.json`（Mac 引擎权威存档，2026-10-27 11:21 mtime）：43 条墓碑**全部** `reopenedAt > closedAt`（惰性）；33 个开放页签含大量作者已关文件。
- 墓碑 `closedAt` = 2026-10-27 04:04 +8（作者批量关闭，dev=Win32）；`reopenedAt` 集中在 **11:19:55–11:21:00 +8**（报障前 6 分钟），间隔 4-5 秒/个——非人工节奏，是 readFile 恢复管线逐个自动重开并把重开账 PUT 上去的痕迹。
- 方向排除：①恢复时序成立（已修，见下）；②跨设备投影不存在——全仓无 canvas tabs 传输面（neblink 包仅附件/邮件/升级，与 #910 报告开放项一致；本存档 Win32 墓碑证明 Windows 控制台直连 Mac 引擎，单存档无对端行）；③墓碑持久化正常（引擎重启后墓碑在场）；④「找少的那个认吧」=关闭意图优先在服务端 merge 实现正确——**被客户端无差别重开账反向架空**才是真根因。

## 3. 修复（canvas.js，全部锚注释「#910 追补」）

| 点 | 语义 |
|---|---|
| `openTab` 新增 `noRevive` 选项 | 恢复/投影腿传入：命中生效墓碑**拒开**（返回 null），且**不落 reopened 账**。显式打开（用户点开）不走闸、照落账——「显式重开才恢复」原语义不变 |
| `openWorkspaceItem` 认领恢复来历 | `requestedFilePaths`（readFile 恢复管线专用集）在应答到达时一次性消费为 `viaRestore` ⇒ `noRevive`；骨架错误腿同闸 |
| `openPanelTab` | 恢复/reconcile 腿恒 `noRevive: true` |
| localStorage 记录自带墓碑账 | `persistTabs` 把 `sessionTombstones`（重开已镜像 `reopenedAt`）写进缓存记录——缓存写入不受服务端过滤，陈旧窗口回写后本地恢复腿才有判据 |
| `restoreFromData` 恢复前过滤 | 按「缓存自带墓碑 ∪ 本会话墓碑 ∪ 服务端已播种集合」滤掉生效墓碑页签 |
| `seedKnownTombstones` + `suppressTombstonedTabs`（迟到清扫） | 服务端 GET 到达后播种权威墓碑集，把「先恢复进来、带生效墓碑」的页签摘掉（`teardownTab(id,{silent:true})` 只摘 UI 不落新墓碑）；本会话显式重开过的 id 跳过 |
| `recordTombstone` | 本会话关闭即刻进恢复闸集合 |

## 4. 门禁读数

- `sbt testOnly nebflow.core.CanvasTabStoreSpec nebflow.core.CanvasTabTombstoneSpec` → **Total 42, Failed 0**（存量 40 + 新增 2）。
- 新增 `tests/canvas-tab-tombstone-revive.spec.mjs` → **3/3**（B1 缓存带账：不开签/不发 readFile/零重开账/零 PUT 重加；B2 老缓存无账：瞬时恢复→迟到墓碑清扫→缓存收敛→零重开账；B3 显式重开：照落 reopened 账+重开态跨重载不丢）。
- 回归：`canvas-file-refresh` 9/9、`canvas-stack` + `canvas-capsule-ring` + `mobile-canvas-sheet` 全绿（teardownTab 加 opts 参数向后兼容）。
- `node --check canvas.js` PASS。checkJs 未跑：本 worktree `node_modules/typescript` 缺失，gate 拒绝 npx 回退（环境级，同 #910 报告 §6.3 类）。

## 5. 任务书三条钉的落点

- **关闭→重载不复活**：B1（前端）+ 新服务端钉 `close → reload: GET archive excludes the closed tab...`。
- **关闭→对端广播同页签不复活**：服务端存量「陈旧全量 PUT 不可复活」两测 + 新钉 `restart (fresh store instance...) keeps close intent`（新实例=重启，陈旧 PUT 仍滤）+ B2 前端陈旧源面。
- **重启（持久层）不复活**：即上述新实例钉（墓碑落盘、GET 不含、重启后仍压陈旧 PUT）。

## 6. 开放项（诚实清单）

1. **现网存量**：本次被翻惰性的 43 条墓碑对应的 33 个页签在存档里已是「合法打开」态（重开账是真实 PUT 语义）。修复上线后作者**再关一次即永久关闭**（新墓碑不会再被任何恢复腿注销）。未动生产数据。
2. **生效面**：canvas.js 是前端件——引擎若以 jar 内嵌 bundle 供服，需随下一次发布/重启到达浏览器（本批遵守「不重启引擎」）；Scala 侧无改动。
3. 跨设备墓碑传输面仍不存在（#910 开放项 1 原样）——本批证明单存档场景下已无复活路径；将来若真做多引擎存档收敛，`CanvasTabs.merge` 可直接复用。
4. checkJs 与 scalafmtOnly 环境崩溃同 #910 报告 §6（非本批引入）。
