# 手机端顶栏环形模糊批 · 证据（2026-10-31 作者令五件）

## 帧率/长帧对比（tests/capture-deck-perf.mjs，1440×900，8 卡组）

同一探针脚本两棵树各跑一遍（改前 = 本批 HEAD 干净树 git worktree，改后 = 本批工作树）：

| 相 | 主题 | 改前 fps | 改前长帧(>50ms) | 改前 p95 | 改后 fps | 改后长帧 | 改后 p95 |
|---|---|---|---|---|---|---|---|
| 开卡 1.4s | dark | **27.3** | **4** | **161.1ms** | 114.9 | 0 | 10.3ms |
| 开卡 1.4s | light | 105.3 | 1 | 10.2ms | 119.0 | 0 | 10.3ms |
| 滚轮擦洗 3s | dark | 120.5 | 0 | 10.3ms | 120.5 | 0 | 10.3ms |
| 滚轮擦洗 3s | light | 120.5 | 0 | 10.2ms | 120.5 | 0 | 9.8ms |
| 卡面横拖 1.6s | dark | 119.0 | 0 | 10.3ms | 120.5 | 0 | 10.3ms |
| 卡面横拖 1.6s | light | 119.0 | 0 | 10.3ms | 120.5 | 0 | 10.2ms |
| 收卡 0.7s | dark | 120.5 | 0 | 10.3ms | 120.5 | 0 | 10.3ms |
| 收卡 0.7s | light | 120.5 | 0 | 10.2ms | 120.5 | 0 | 10.3ms |

掉帧源定位：**开卡相（dark 27fps、4 长帧）** = deckFrame 热路径每帧两趟
`querySelectorAll('.deck-card[data-r]:not(.ghost)')` + **每帧每卡** `.card-shade`
深子树查询（卡内挂 markdown 克隆快照，子树深）+ 签名去重块之外每帧每卡 2 写
（`classList.toggle('front')` + `style.pointerEvents`）。修法（纯主线程热路径，
零视觉变化）：卡列表脏缓存（四处 mutation 点置脏）/ shade 引用随卡缓存
（模板静态子节点，快照只换 .card-thumb 槽）/ front 与 pointerEvents 纳入位姿
签名全零写。改后开卡 114.9fps 零长帧；擦洗/拖/收各相本已满载 120fps 保持。

读数文件：perf-before.json / perf-after.json。

## 双主题截图（shots/）

- m1-strip-top-{dark,light}：canvas 页顶带全景——页头胶囊退役、标签条置顶、chips 悬浮无条面、下为 ✕/计数/堆叠钮工具条
- m2-strip-fade-left-{dark,light} / m3-strip-fade-right-{dark,light}：**标签横向环形左右两端渐隐特写**（28px 双侧 mask 渐隐，溢出门控）
- m4-stack-expand-mid-{dark,light}：堆叠展开中段帧（首张已升、末张未动 = stagger 实证）；m5-stack-open-*：终态
- d1-ring-open-{dark,light}：桌面竖向胶囊环回退态（上下对称 ±38px、中心覆层文字让位、顶部真越出）

探针断言：probes-mobile-{dark,light}.json（24/24 PASS，capture-topbar-batch.mjs）。

## ③ 考古结论（一句）

改动源头 = `fd9134d54`（2026-10-30 21:32「顶部多文件视图胶囊环→文件扇卡片翻」，
作者令 20:09）——它将 10-23~10-30 的竖向胶囊环（上下对称、滚轮换选游标、点击切换）
整体改写为横向文件扇；作者 10-31 15:19 令回退，按裁定还原该批父版
（fd9134d54~1）的环交互确切行为，扇形新增件（canvas-card-fan spec /
capture-canvas-fan / fan i18n / cr-hint 等）随之退役，10-31 批无关面
（wikiGraph latch 清欠等）保留。
