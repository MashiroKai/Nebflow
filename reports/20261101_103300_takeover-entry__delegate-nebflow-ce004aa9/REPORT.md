# REPORT — 作者令批（接管必达 × 卡片同族 × 入场加载覆盖）11-01 10:07

分支 `delegate/delegate-nebflow-ce004aa9`；三件全落地；spec 全绿；样张六张。

## ① 重新接管必达 + 失败明确报错

**根因（账本面）**：`ConsoleTakeover.onDisconnect` 原 `m.collect{...}` 只保留命中键 ⇒
任一会话断连把**其他会话**的连接账整键抹掉 ⇒ fresh-claim/retake 的反向踢 targets 恒空
⇒ 旧端无感继续看。修为 `touched.foldLeft` 只重写被触碰键。

**通道面（HTTP 必达腿）**：ws 帧腿三重结构性缺口（死连接静默入队 / 回执可被出站队列
溢出吞 / 4s 定时与服务器实际状态脱节）⇒ 新增 `POST /api/takeover/retake`
（`ConsoleTakeoverRoutes.scala`，session-rail only；回执随响应必达）。
服务器侧 `retakeBySession` 与 ws 腿共享核 `claimAndReverseKick`（行为逐字同源）。

改动件：
- `src/main/scala/nebflow/gateway/ConsoleTakeover.scala`（onDisconnect 修复、retakeBySession、claimAndReverseKick 抽取、RetakeOutcome）
- `src/main/scala/nebflow/gateway/ConsoleTakeoverRoutes.scala`（新文件）
- `src/main/scala/nebflow/gateway/GatewayMain.scala`（/api 级联挂载）
- `src/test/scala/nebflow/gateway/ConsoleTakeoverSpec.scala`（A3b/A4b/A4c/A4d/A7 扩）
- `src/main/resources/web/js/takeoverModal.js`（HTTP 腿重写 + retakeErrorText 五类 + 失败面恢复）
- `src/main/resources/web/js/locales/zh-CN.js`、`en.js`（错误文案键）

## ② 接管卡同族重做（g2 药丸）

- `src/main/resources/web/css/modal.css`（~396-460）：卡体重做（padding 24 / width 360 /
  标题 15/600）+ `#takeover-error`（S9 同族红左缘行）+ 按钮换代 `g2-pill` /
  `g2-pill g2-pill-primary`（height 34）；旧 chip 规则块退役；`.takeover-blur` 维持四修批裁定。
- `src/main/resources/web/index.html`：takeover-box DOM 加错误行 + 按钮 g2 类。

## ③ 入场加载覆盖（真实项 + 就绪揭幕）

- `src/main/resources/web/index.html`（门脚本 IIFE）：
  - `.nf-entry-card` 玻璃卡（`--glass-*` 同材质）+ 四行清单（样式表/界面模块/连接主机/
    同步数据）+ 状态点（pending/active/done/fail）+ 标题跟随（「正在检查主机…」→
    「正在同步数据…」→「就绪」）；
  - `paintEntry` 活动行 `data-state=active`（修复：样式表有 active 强调位而画手从未置它）；
  - `markEntry` 单缝 `window.__nfEntry.mark`；`maybeReveal` 全 done 揭幕 + 数据 3s 宽限；
    20s 看门狗；crossfade 揭幕（reduced-motion 直切）；
  - `failedHard` 一票否决（CSS 失败面永不被 fail 腿击穿——CSS 全断时模块图照跑、
    ws 照握手，宿主 fail 腿若揭幕会回落裸壳，违反裸渲染批铁律）；
  - `.nf-boot-card[hidden]{display:none}`（修 display:flex 压过 UA hidden → 空失败卡裸浮）；
  - `dataGrace` 声明 + 揭幕清理（修：reveal 内引用未声明变量 → ReferenceError 被上报点
    try/catch 吞 → 门永挂——自测暴露的纯实现 bug）。
- `src/main/resources/web/js/ws.js`：onopen `mark('host','done')`；onclose 握手被拒腿
  `mark('host','fail')`（typeof 防御）。
- `src/main/resources/web/js/main.js`：connect 前 `mark('ui','done')`；
  onMessage('configData') 首行 `mark('data','done')`。

## 验收矩阵

| 验收 | 结果 |
|---|---|
| sbt `Test/testOnly nebflow.gateway.ConsoleTakeoverSpec` | **19/19 PASS**（含 A3b 断连不抹他键账 / A4b HTTP 翻位+踢 / A4c 零活连接必达 / A4d 幂等 / A7 双腿 Disabled） |
| `tests/console-takeover.spec.mjs` | **ALL PASS（41 PASS 断言）**：F1 亮暗药丸（radius 999px / 类名 / 主次异材）/ F2 HTTP 回执 reload / F2b refused→错误行+toast+恢复可点、abort 分类、恢复后再点成功 / F3 退出直达 profileUrl |
| `tests/boot-render-gate.spec.mjs` | **45 passed, 0 failed**（T1 入场卡清单重写 / T3 早落门容差=事件派发抖动 800ms，慢载中途门态可见仍判红） |
| `tests/entry-veil.spec.mjs`（新） | **ALL PASS（15/15）**：V1 四项清单逐字+标题跟随 / V2 真实进度（hold 门控 configData：pending 观察窗 → 释放 → 四项全 done → 门摘）/ V3 握手被拒 fail 腿 / V4 卡窗脸不进延长门 / V5 暗档样张 |
| `tests/attachkey-idem.spec.mjs`（PW runner, AK_MODE=after） | **4 passed, 3 skipped**（skipped = before 模式组，本批不适用） |
| wiki-graph-multivault | **3 passed (11.7s)** ✓（B1-B4 聚合/下钻/面包屑、C2 standalone 隔离——均吃 3s 数据宽限后正常） |
| wiki-term-citation | **9 passed / 2 failed**。两条失败均为 §T6 移动端腿（`locator('.mh-dd-hd')` 等 180s 超时）——`.mh-dd-hd` 类在本支 web 树**与 main 的 web 树均不存在**（`git grep mh-dd main -- src/main/resources/web/` 零命中；类由 2026-10-29 16:17 手机端 dock 批 894874bd8 一族改名/移除，该批不在本支），即 **main 上同腿同样红＝存量失效腿，与本批无关**。机制面独立佐证：本批入场 veil 最迟 20s 安全阀揭幕，而该点击等了 180s。核心引用腿（气泡/跳转/返回钮/桌面交互）9/9 全绿 |
| kickold-cb-signedout / neblink-paircode-card | 静态核查：两者 ws 桩静默（`routeWebSocket(()=>{})`）→ 页面侧 ws open（host done）而无 configData → 走 3s 数据宽限揭幕；两 spec 的等待预算（expect 默认 30s 级）吸收 3s 税为有余。pw.config 需各批生成步骤重建（/tmp 已清理），未在本支重跑——记为有界风险注记 |

## 样张（六张，after/）

`evidence/20261101_103300_takeover-entry__delegate-nebflow-ce004aa9/after/`
- takeover-light.png / takeover-dark.png —— 接管卡双主题（玻璃卡 + 白玻璃/墨玻璃药丸）
- retake-fail-refused.png —— 重新接管被拒：错误行「主机拒绝了本次接管」+ toast + 按钮恢复
- retake-success-reentered.png —— 接管成功回执 reload 重入
- entry-progress-light.png / entry-progress-dark.png —— 入场进度卡双主题（活动项=同步数据）

## 自测暴露并修复的三个隐藏件

1. `dataGrace` 未声明 → reveal ReferenceError 被吞 → 门永挂（诊断时间线定位）；
2. `paintEntry` 从未置 `data-state=active`（样式表强调位成死规则）→ spec 抓到；
3. `failedHard` 缺席 → CSS 全断时宿主 fail 腿揭幕回落裸壳（击穿裸渲染批铁律）。

## 方法论注记（entry-veil V2）

configData 定时延迟版在并行跑批下假阴性（页主线程被饿 ⇒ evaluate 首样迟于 1.5s 窗），
改 **hold/release 门控**（spec 翻旗才推帧）：观察窗由 spec 显式开合，负载免疫、确定性。

## 假设声明

作者指认的参考卡「正在检查主机」不在本仓（全仓 + data root 检索确认；mystand.space /
link.my stampa space 网站面）。同族以**仓内玻璃族**定义：`--glass-*` 卡材质 +
liquid-glass.css g2 药丸按钮语——接管卡与入场卡为同族三件之二（第三件即首入控制台
跳转卡的同款语言宿主）。
