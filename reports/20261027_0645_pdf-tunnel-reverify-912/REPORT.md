# REPORT · 台账 #912 件② — PDF「Cannot display PDF: network error」隧道饥饿复验（构建考古结论）

- 日期：2026-10-27（batchA 收口批）
- 作者令（2026-10-26 12:02）：「②『Cannot display PDF: network error』本地文件打开失败的体验面——根因疑似 #892 已修未生效（5MB+ 隧道饥饿，重启后须复验）；若复验仍败另案定因」

## 结论（先答）：现在复验 = 无效实验；复验前置 = 引擎换新构建

**在产构建考古（Mac 面，2026-10-27 06 时读数）：**

| 事实 | 读数 |
|---|---|
| 在产指针 | `~/.stand/bin/current-version` = `2026.10.26-beta.1` |
| 该构建出处 | commit `434585055`（2026-10-26 01:20:25 +0800，beta.1 发版批：KAI sbt assembly 出包，`stand-jar-pull` 拉回落位） |
| 在产引擎携入的修复上限 | **2026-10-26 01:20** 主线增量（38b81eb4f..01:20 main） |
| #892 报障时刻 | 2026-10-26 01:09（台账 #892 原文「大文件传输掉登录 + 长连接 403 泛滥」） |
| 隧道侧大文件修复合入 | `#944`（跨设备回报丢件腿，完成件先落盘 + relay 重连按序补投）与 `#949`（portal 上行背压，inbox 满排队等容量）——**2026-10-26 21:26 才经 `7d1453c39` 合入 Mac main** |

⇒ **「已修未生效」的机制坐实**：在产 beta.1 构建出土于 01:20，比 #892 报障（01:09）晚 11 分钟、比两笔隧道修复（21:26 合入）早 20 小时——**在产引擎从未运行过含修复的代码**。对它复验 5MB+ PDF，无论成败都不构成对修复的检验（败 = 复现旧缺陷，非「修后仍败」）。引擎 2026-10-27 02:44 波次稳定指的是**进程稳定**，不是**构建更新**——重启跑的还是旧包。

## 复验前置（等作者令的引擎面动作，本批不动引擎）

1. 新出包：KAI 仓 main（合入本批 + 既含 #944/#949）`sbt assembly`；
2. `bash ops/bin/stand-jar-pull`（SHA 对账 + 原子落位 + 指针切换，脚本不重启）；
3. 作者令 `stand stop && stand start` 换上新构建；
4. 复验路径（portal 真隧道腿）：mystand.space portal 开控制台 → 打开 >5MB PDF（此前失败件）→ 预期整本渲染（pdf.js Range 分页取数全程走票）；若仍「Cannot display PDF: network error」⇒ 另案定因（届时取 nfTicket 时序 + 隧道分片日志，属新案面）。

## 本批已落地面（件①，同一台账）

错误态卡片文字不得被 CanvasHeader 胶囊遮挡：`.canvas-tab-pane .canvas-error` 顶部让位带（padding-top 56px > 胶囊底缘 46px），PDF/image/docx/epub/pptx 五 viewer 失败面同享；spec 钉 `tests/canvas-error-capsule-clearance.spec.mjs`。体验面上作者看到的正是同一张实拍：PDF 打开失败的「Cannot display」文字被胶囊吞首行——件①修可见性，件②修根因（前置如上）。
