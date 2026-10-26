# REPORT · 台账 #910 — canvas 标签页多设备收敛墓碑语义

- 日期：2026-10-26（KAI 设备原生批）
- 作者令：2026-10-26 11:44 飞书逐字「现在就是我关闭过的标签页，又会显示，可能是多设备，一个设备关了，一个设备没关，不知道认哪个导致的吧。**找少的那个认吧**」
- 分支：`delegate/delegate-nebflow-c2e6b6d0` → 自合并 KAI main（本批合并职责在 delegate，任务书第 4 条授权）
- 变更：`ccd028b1c` fix(canvas-tabs) — 4 files，+506/−9

## 1. 根因链（代码锚）

1. 服务端存档 = **整档覆盖**：`CanvasTabStore.save` 对 `~/.stand/canvas_tabs.json` 全量重写，无任何合并语义（`CanvasTabStore.scala`，原 save 路径）。
2. 关闭动作**没有独立表达**：前端 `persistTabs()` 只 PUT「当前开放集」，服务端无法区分「已关闭」与「该窗口从未见过」。
3. 复活路径（多窗口，单设备即可触发）：窗口 W1 关标签 T → W1 PUT 无 T；陈旧窗口 W2 后续任意 persist（setActive/pin/开关别的标签/断线重连重放）→ W2 PUT 带 T → **T 复活**。
4. 跨设备同构：Mac 引擎与 KAI 引擎各持独立 `canvas_tabs.json`（已取证：两文件各自纯本机路径、无污染），A 端关闭后 B 端存档仍持 T，B 端 UI 照常显示——「一个设备关了，一个设备没关」。
5. 前端无跨设备同步面（canvas.js 无 device/peer/sync 逻辑；neblink 包内 NeblinkFiles=附件分块、DeviceMailInbox=agent 邮件、UpdateConvergence=应用升级，均不携带 canvas tabs）——任务书「经 neblink 同步」为疑点假设，实况=无传输面。

## 2. 语义决策（作者裁定→实现）

| 裁定 | 实现 |
|---|---|
| 关闭意图优先 | 关闭动作显式落**墓碑** `{id, closedAt, dev}`，随每次 PUT 上交（`sessionTombstones`） |
| 墓碑并集压过开放集（=开放集收敛取交集的等价实现） | `CanvasTabs.merge`：PUT.tabs 命中生效墓碑的 id 一律滤除——陈旧全量 PUT、老客户端 v2 PUT 都无法复活 |
| 显式重开才恢复 | `openTab` 新建成功落 `reopened:[{id, reopenedAt}]`；`reopenedAt > closedAt` 才清墓碑 |
| （设计自加，重放护城河） | 老墓碑**惰性保留不删除**（记 reopenedAt）：旧账重放的 closedAt 既压不回重开态、也复活不了再关态，全按时间戳幂等裁决 |
| TTL 防膨胀 | 30d（latestTs 起算）回收 + 数量上限 500（淘汰最旧）；PUT 账目上限 2000（400 拒绝） |
| 老格式平滑升级 | schema **仍 v2**，墓碑/重开账全是加法可选字段；老引擎「未知字段忽略」、老前端 GET 照常读 v:2+tabs；无 tombstones 字段的旧文件零迁移 |

## 3. 关键实现点

- **`CanvasTabStore.mergeSave`（路由 PUT 唯一入口）**：公平 FIFO 闸内 `load→merge→save`，单个 `IO.blocking` 包临界区。锁是**必需**而非镀金：双窗口并发 PUT 时后写者基于旧 prev merge 会把先写者的墓碑整段丢掉（读-改-写竞态；原子写只保崩溃安全，不保此竞态）。
- **前端四关闭站点**（全量枚举过 `tabs.delete`/`canvas-tab-closed`）：`teardownTab`（× 按钮+deck 侧滑+手机层 closeTab 共用）、`openTab` 预览替换、`openFile` 预览替换、`deckCloseOthers`。**拖拽排序与文件改名不算关闭**，不落账。
- 重开账不清理：账目幂等（时间戳 max），断线重连重放/防抖重发都安全；超 2000 客户端侧淘汰最旧，保 PUT 永不 400。
- `activeTabId`：命中生效墓碑则丢弃（PUT 带了用 PUT 的，否则沿用 prev 的）。注意 PUT 载荷历史上就不带 activeTabId（仅 localStorage 带），维持原状。

## 4. 门禁读数

- `sbt testOnly nebflow.core.CanvasTabStoreSpec nebflow.core.CanvasTabTombstoneSpec` → **Total 40, Failed 0**（存量 18 + 新增 22：关闭意图过滤/陈旧重放不可复活/老客户端不可复活/显式重开恢复且墓碑转惰性/旧账重放不可再关/重开后再关/早于 closedAt 的重开无效/同载荷先关后开/未知 id 重开忽略/TTL 回收/上限淘汰/旧格式升级/去重/activeTabId×2/validate 拒绝面 9 例/超限拒绝/mergeSave 落盘往返/老格式载荷）。
- `node --check canvas.js`（ESM）PASS。
- 全仓 Test 树编译通过（-Werror 下无警告；`Map.retain` 弃用已改 `filterInPlace`）。
- boot-smoke 未跑：本批不触 boot 装配面，汇编 jar 构建非本批门禁（#901 因触 project-device boot 路径才跑）；如需可后补 `scripts/boot-smoke.sh <jar>`。

## 5. KAI main 与 Mac main 拓扑口径

- **dev main = KAI**：本批已 `step-0` 把 Mac main `21f19dfaf`（挂载修复合并）先同步进 KAI（base64 单对象传输 + `update-ref` FF，SHA 对账 MATCH），再落 `ccd028b1c`，自合并推进 KAI main。
- **Mac = 发布集成面**：Mac 侧仓只读（bundle 源）；反向同步（KAI main → Mac）留 ops 例行，不在本批。
- 推进方式：`delegate/delegate-nebflow-c2e6b6d0` 上开发 → `git merge` 自合并进 KAI main。

## 6. 开放项（诚实清单）

1. **跨设备墓碑传输面不存在**：本批把两端各自存档的复活路径钉死（多窗口竞态根治），但「A 端关、B 端存档仍有」的跨端收敛需要两端引擎都跑新代码且**墓碑账目有传输通道**。现成通道均不匹配（附件/邮件/升级）。建议承载面：neblink presence/roster 周期载荷加一个 `canvas_tombstones` 键（量小、幂等、可 Gossip 合并——merge 语义本批已就绪，`CanvasTabs.merge` 直接可复用），或 friend_event 新事件型。待作者裁定后再排批。
2. **两端运行时验证**：Mac 引擎在产未重启（仍跑旧代码，其浏览器 PUT 老格式——本批已兼容）；KAI daemon 重启后才加载新代码。KAI 重启必须走 `stand.cmd restart` 的 **schtasks 模式**（直跑会被 ssh 会话回收），留待作者在场时执行；重启后可观测：关标签→看 canvas_tabs.json 出现 tombstones 记录→刷新页面不复活。
3. **scalafmtOnly 环境崩溃**：`scalafmtOnly <files>` 在枚举含非 ASCII 文件名（`evidence/...报告.md`，git quotepath 引号形态）时 InvalidPathException 崩溃——环境级问题非本批引入；本批 Scala 改动按仓内 2 空格风格手对齐。
4. **文件改名边角**：改名后标签换 id（旧 id 若有墓碑不清理、新 id 若有生效墓碑会在下次 PUT 被滤）——改名稀频，观作者是否需要专门批。
5. 预览替换也落墓碑（预览标签本就瞬态，防陈旧复活优先）——如作者觉得预览替换不该算「关闭意图」，一处常量即可回调。
