# DESIGN — 作者令批（接管必达 × 卡片同族 × 入场加载覆盖）11-01 10:07

三件委托、一条根因、一个同族语言。本文记设计裁定；过程与验收矩阵见同目录 REPORT.md。

## ① 重新接管不一定有效 → 必达 + 失败明确报错

### 根因（账本面 + 通道面，双修）

**账本面（本批根因）**：`ConsoleTakeover.onDisconnect` 用 `m.collect { … }` 重写连接账
——`collect` 只保留命中键 ⇒ **任一会话断连，把其他会话的连接账整键抹掉**。
后果链：新会话 fresh-claim / retake 的「反向踢旧端」读到的旧端连接集合恒空 ⇒
无人可踢 ⇒ 旧端无感继续看 = 「重新接管不一定生效」的主病灶。

修法：`touched.foldLeft` 只重写被触碰的键（他键原样保留）。回归钉 = spec A3b。

**通道面（结构性缺口，HTTP 腿封死）**：原 retake 走 ws 帧（sendWs）：
死连接静默入队、回执可被出站队列溢出拆连吞掉、4s 定时超时与服务器实际状态脱节——
三处都让「接管是否成功」失去回执。修法：新增 HTTP 腿

```
POST /api/takeover/retake        （ConsoleTakeoverRoutes.scala，session-rail only）
  200 {ok:true, kicked:n}        接管成立，n = 反向踢掉的旧端连接数（可为 0）
  200 {ok:false, error:"disabled"} 接管面关闭
  403 + X-Nf-Reason: credential-required
```

回执随 HTTP 响应**必达**。服务器侧共享核 `claimAndReverseKick` 与 ws 腿逐字同源
（`retakeBySession`，不要求本会话有活连接；disabled → Disabled）。

### 失败明确报错（不静默，五类）

`takeoverModal.js` 错误分类 `retakeErrorText`：

| 情形 | 文案（zh） |
|---|---|
| 401/403 | 登录态失效，请重新登录后再试 |
| error=disabled | 接管面已关闭 |
| 其他 error | 主机拒绝了本次接管 |
| Abort/Timeout | 接管超时，请重试 |
| TypeError（网络断） | 无法连接主机——请检查网络后重试 |

失败 = 恢复按钮可点 + 卡面错误行（S9 同族红左缘行 `#takeover-error`，role=alert）
+ toast。单飞（进行中禁重入），10s AbortSignal 兜底。ws 帧 `takeoverResult`
消费保留（兼容旧缓存页）。

## ② 接管卡 = 玻璃族同款（g2 药丸）

**假设声明（重要）**：作者指认的参考卡「正在检查主机」不在本仓（全仓 + data root
检索确认，为 mystand.space / link.my stampa space 网站面）。本批以**仓内玻璃族**定义
同族：`--glass-*` 卡材质（亮 rgba(255,255,255,.72) / 暗 rgba(30,32,37,.82)、radius 18、
blur 24 saturate 1.15）+ liquid-glass.css 的 g2 药丸按钮语。接管卡重做为同族三件之一：

- 卡体：padding 24 / width 360 / 标题 15/600 —— `.nf-entry-card` 同材质语言；
- 按钮语：`g2-pill`（次）+ `g2-pill g2-pill-primary`（主），接管卡内 height 34 / padding 0 18px；
- 错误行：S9 同族红左缘行；
- `.takeover-blur` 毛玻璃背景维持四修批裁定不动。

## ③ 入场加载覆盖（真实项，就绪才揭幕）

### 真实加载项四件（不是假进度条）

| 项 | 信号源 | 判定 |
|---|---|---|
| 样式表 css | 门脚本三态判定 | 全部 head stylesheet ok（cssRules>0）|
| 界面模块 ui | main.js | 模块图装载 + 壳装配完成 |
| 连接主机 host | ws.js onopen | ws 首次握手成功 |
| 同步数据 data | main.js onMessage('configData') | 首帧配置数据落地 |

单缝 `window.__nfEntry.mark(id, state)`；面 = `.nf-entry-card`（②同材质），四行
清单 + 状态点（pending/active/done/fail），标题跟随活动项动作文案
（「正在检查主机…」/「正在同步数据…」/「就绪」）。

### 揭幕语义（就绪才揭，但有界）

- 全 done ⇒ crossfade 揭幕（`html.nf-boot-reveal`，260ms，reduced-motion 直切）；
- **数据宽限**：css/ui/host 齐、唯 data 未到 ⇒ 3s 有界等待后让位——真实引擎
  connect 即推 configData（生产零感知）；替身/病态服务端不扣入口；
- **安全阀**：入场卡 20s 看门狗（任一项卡死也揭幕，宁可揭幕不可扣死入口）；
- **fail 腿**：握手被拒 ⇒ host 项标 fail + 立即揭幕（让位给登录/重连面）；
- **铁律不破**：CSS 任一失败 = 裸渲染批的失败面（`fail()` 置 `failedHard`
  一票否决揭幕）——CSS 全断时模块图照跑、ws 照握手，fail 腿绝不击穿「永不摘门」；
- **脸保留**：卡窗脸（card-window-boot）不进延长门（CSS 窗即门，逐字保留）；
  nfEmbedded 内嵌脸不进门。

### `.nf-boot-card[hidden]` 修复（实拍抓到的既有瑕疵）

`display:flex` 压过 UA 的 `[hidden]` ⇒ 空失败卡裸浮。补 `.nf-boot-card[hidden]{display:none}`。

## 验收面

spec 三件：`ConsoleTakeoverSpec`（19/19，含 A3b/A4b/A4c/A4d/A7-HTTP 腿）、
`tests/console-takeover.spec.mjs`（F1 双主题药丸 / F2 HTTP 回执 / F2b 三腿失败分类）、
`tests/boot-render-gate.spec.mjs`（45，T1 重写 + T3 容差）、`tests/entry-veil.spec.mjs`
（新，V1-V5）。样张六张：`evidence/20261101_103300_takeover-entry__delegate-nebflow-ce004aa9/after/`
（takeover-light/dark、retake-fail-refused、retake-success-reentered、
entry-progress-light/dark）。
