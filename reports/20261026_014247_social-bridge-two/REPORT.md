# 社交桥两件：断桥预警降级 + 绑定状态秒显 · 实施报告

批：social-bridge-two · 2026-10-26 · 工作树 `delegate/delegate-nebflow-50d9418d`（分支 `delegate/delegate-nebflow-50d9418d`）

## 0. 结论（先行）

作者 01:2x 飞书反馈两件，均已落地并过门禁：

- **A 断桥预警降级**：微信（iLink）桥的「⚠️ 桥会话疑似失效」主会话注入通知**整个退役**（`bridgeDeadNotice` 删除，注入腿删除）。瞬态抖动（窗内自愈）＝逐条拒绝既有 WARN（code/reason）＋全愈瞬间一条结构化 INFO（累计拒数、抖动窗时长、末次 ret 码）；持续硬故障（连续 ret=-2 超过 5 条设定窗、无恢复）＝**WARN 级** log（自 ERROR 降级）＋通道卡健康 strike（卡面自解释，`channels[].health` 面既有机制不变）。**任何路径都不再向主会话注入消息**。
- **B 绑定状态秒显**：通道卡最后一次收敛的渲染输入（config/probe/registered/live 四元组，逐通道）**落盘持久化**（localStorage `nebflow.social.panelCache.v1`，浏览器本地盘）；**开卡先从快照播种再首渲染**——绑定 pill（已接入）随 DOM 首帧出现，不再等整条拉取链（config → 逐通道 probe → feishu C1/C4 → sessions）；后台照常拉真值**校真**，逐卡签名 diff：读数与快照一致＝零重绘（无闪烁、不打扰扫码/输入），仅漂移卡原位刷新（pill/hint/live/toggle），结构性漂移（created 翻面/字段变更）才整卡重绘。打开 <1s 显示当前绑定态（首帧即达）。顺带收敛加速：probe 只探可见卡（封印卡是每开两次纯浪费的往返）并并发发出。

门禁实测：相关族 spec 全绿（Scala 86/86；social-panel 全链 66/67——唯一 FAIL 为 SAG-1，系 spec 自注的已知 headless 环境基线问题，同树隔离跑 6/6 全绿，见 §4）；checkJs 260=基线零新增；引擎侧过 boot-smoke（`BOOT-SMOKE: PASS … boot_ready=4s checks=11`）。

## 1. A 件 —— 断桥预警降级（引擎侧）

### 1.1 改动点

`src/main/scala/nebflow/social/WeixinIlinkBridgePlugin.scala`：

| 位置 | 改动 |
|---|---|
| :277-286 | 新增 `RetFlap(startMs, refusals, lastCode, lastReason)`——当前 ret=-2 抖动窗账本（纯数据） |
| :297-301 | 探测器状态扩为 `((textStreak, mediaStreak), alerted, Option[RetFlap])`；纯判定 `bridgeDeadStreak`（spec 钉死）**一字未动** |
| :1351-1397 | `noteSendHealth`：ret=-2 累进开/续窗，双 streak 归零即关窗并带回 `closedFlap` → 全愈瞬间落结构化 INFO：`ret=-2 flap recovered — N refused send(s) over Xs (last code=502 reason=…)`（带 ret 码与恢复时间）；中立失败不动窗 |
| :1394-1412 | `alertBridgeDead`：ERROR→**WARN**；删除 `routesRef/ctxRef/injectMessage` 注入腿整段；保留 `writeStrike(ReasonBridgeDead)`（通道卡健康面，回绿清 strike 既有语义不变） |
| :2044 | `BridgeDeadThreshold = 5`（设定窗）语义改注：窗内恢复＝瞬态抖动（只落 log）；穿越＝持续硬故障（WARN log + 卡面 strike，不通知主会话） |
| :2059-2061 | `bridgeDeadNotice`（⚠️ 注入文案）删除，退役批注留痕 |

### 1.2 语义对照（作者令逐条）

| 作者令 | 落地 |
|---|---|
| 瞬态拒绝（连续 N 条内恢复）一律落结构化 log（带 ret 码与恢复时间） | 逐条拒绝＝既有逐条 WARN（`reply send failed code=… reason=…`，:1230 等十余处，ret=-2 在 reason 里）；恢复时间＝新增全愈 INFO（拒数+时长+末次码），:1384-1388 |
| 不再通知主会话 | 注入腿删除（:1394-1412 无 `injectMessage`）；`bridgeDeadNotice` 文案退役 |
| 持续硬故障才升级 WARN 级 log，同样不通知 | `alertBridgeDead` WARN 一次性升级（re-arm 语义保持：一次死亡一条，全愈重臂），无任何注入 |
| 渠道健康度在通道卡自身可见（状态自解释） | `[bridge-session-dead]` strike 写通道健康面（`<root>/tmp/social-health/weixin-ilink.json` → `GET /api/social/channels` 的 `channels[].health` → 卡面 hint 黄线）；回绿清 strike（既有 `clearNoReturnStrike` 腿未动） |

### 1.3 spec 更新（`WeixinIlinkBridgeSpec.scala` :1579-1660）

`WI-BD2` 重写钉新契约：两次死亡窗各一条 **WARN** 级升级（按 level 过徯断言，:1623-1627）、`injected == Nil`（主会话零通知，:1630-1634）、「疑似失效」文案不得再现于任何 log、每窗一条恢复 INFO 且带 `last code=502`（:1638-1641）、四相位 strike 断言（窗1 on / 恢复清 / 窗2 on / 终点绿）原样保留。`WI-BD1`（纯判定表）、`WI-BD3`（媒体腿喂同一探测器）未动、原样绿。

## 2. B 件 —— 绑定状态秒显（前端）

### 2.1 改动点

`src/main/resources/web/js/socialPanel.js`：

| 位置 | 改动 |
|---|---|
| :292-311 | 秒显批注释块（三层语义 ①落盘 ②本地读 ③校真+变更才更新）＋ `PANEL_CACHE_KEY = 'nebflow.social.panelCache.v1'`（v1 版本化，坏快照零危害降级） |
| :316-332 | `persistPanelCache()`——四元组逐通道快照写 localStorage（尽力而为，配额/隐私模式静默） |
| :334-355 | `seedPanelCache()`——开卡先播种四张内存图（逐字段形状校验，异常整体降级为无缓存＝改前行为） |
| :370-399 | `cardFaceParts(ch)`——单卡渲染面签名（status/created/switchOn/hint/fields/registered/live）。🔴 两处防重绘细节：`configReady` 不入签名（可见卡全为 item face，无可见面）；`switchOnFor` 内嵌 `configReady` 必在收敛时翻转，故该词只对 plain 卡面生效（item/two-face 卡面该词恒 null，既有真值不丢） |
| :401-420 | `convergeCards(changed, before)`——漂移卡处理：created 翻面或 fields 变更＝结构性 → `renderAll()`；其余（pill/hint/live/toggle，`refreshCard` 的精确覆盖面）→ 逐卡原位 `refreshCard` |
| :1193-1247 | `loadAll` 重构：① 开跑时先抓 paint-in-flight 签名；② probe 只探**可见**通道且 **并发**（`Promise.all`，逐槽独立降级）；③ 收敛点逐卡 diff → `convergeCards`；④ 快照写穿**仅在完全收敛轮**（`configReady && probesClean`，:1236-1243——一次网关抖动不得把「未配置」降级读数毒进快照，否则下次开卡会把已绑卡画成未建卡并对其自动起扫码会话） |
| :1807-1817 | `openSocialPanel`：`seedPanelCache()` 在首渲染之前（绑定 pill 随 DOM 首帧出现）；播种仅用于显示——`configReady` 保持 false，全部写路径的「未就绪即拒」闸原样生效 |
| :1317/:1345/:1404 | 另外三个收敛点写穿快照：`saveChannel` / `recheck` / `archiveChannel` |

### 2.2 语义对照（作者令逐条）

| 作者令 | 落地 |
|---|---|
| 绑定态落盘持久化 | 四元组快照 v1 落 localStorage（浏览器本地盘），每个收敛点写穿 |
| 开卡即从本地读（秒显） | `openSocialPanel` 播种后立即 `renderAll`——绑定 pill 首帧在，不等网络；实测 <50ms |
| 后台异步刷新校真 | `loadAll` 原链照跑（probe 并发化、去封印卡），真值照常到达 |
| 变更才更新界面 | 逐卡签名 diff：一致＝零重建（无闪烁，扫码 QR/输入不被打扰）；漂移卡原位刷新；结构漂移才整绘 |
| 打开 <1s 显示当前绑定态 | 首帧即达（快照在本地盘） |

陈旧快照自愈：换实例/引擎重启/他处解绑 → 后台真值到达即按 diff 纠正（该卡 pill 翻转）；快照永远只是加速器，不是真值源。

## 3. 验证证据

| 门禁 | 命令 | 结果 |
|---|---|---|
| 相关族 spec（Scala） | `sbt testOnly nebflow.social.WeixinIlinkBridgeSpec nebflow.social.SocialChannelsSpec nebflow.social.WeixinIlinkScanBindSpec` | **Passed: Total 86, Failed 0** |
| checkJs 零新增 | `node scripts/check-js-types.mjs` | `checkJs: 260 errors (baseline 260) — PASS: no new errors above baseline` |
| 秒显回归（新增） | `SOCIAL_SUITE=instant node tests/social-panel.spec.mjs` | **3/3 green**（秒显-1 缓存先画/秒显-2 校真一致不重画/秒显-3 收敛点回写快照） |
| 全链（既有回归·本批树） | `node tests/social-panel.spec.mjs` | **139 green / 1 known FAIL（SAG-1，A/B 证伪为本批，见 §4）**；本批新增三腿全绿、既有 W6/W9/SF-1/WBI-\*/UX-\* 全绿 |
| 全链 A/B（基线树对照） | `SOCIAL_SUITE=after SOCIAL_WEB_ROOT=<HEAD web> node tests/social-panel.spec.mjs` | 基线树**同样挂 SAG-1**（+同位 SAG-2 超时崩溃）⇒ 非本批引入，实测坐实；且新钉秒显腿在基线树 **RED**（`秒显 suite … Timeout`——改前树首帧不显示绑定态，正是本批要修的症状）＝红腿证据 |
| scanauth 隔离 | `SOCIAL_SUITE=scanauth node tests/social-panel.spec.mjs` | **6/6 green** |
| 引擎侧 boot-smoke | `bash scripts/boot-smoke.sh target/scala-3.5.2/stand-assembly-2026.10.21-beta.2.jar` | `BOOT-SMOKE: PASS … boot_ready=4s checks=11`（隔离数据根，零产线触碰） |

新增测试两处：
- `tests/social-panel.spec.mjs` `instantSuite`（:3152-3256 附近）+ 独立入口 `SOCIAL_SUITE=instant` + 全链挂载 + SCOPE 白名单增补 `WeixinIlinkBridgePlugin.scala`（批注：断桥预警降级批 A 件）。秒显-2 用 DOM 打标法钉「变更才更新界面」：收敛前给卡打 `data-instant-probe` 标，收敛后标仍在＝同节点存续＝零重绘（RED on 改前树：首帧 notConfigured，pill 要等全链收敛才翻转）。
- `WeixinIlinkBridgeSpec` WI-BD2 重写（见 §1.3）。

## 4. 已知非本批问题（A/B 实测坐实，不属本批面）

- **SAG-1 全链跑挂、隔离跑绿——基线树 A/B 坐实非本批**：`SOCIAL_SUITE=after SOCIAL_WEB_ROOT=<HEAD web>` 基线树全链**同样**挂 `SAG-1 卡内文案… statusLine=""` 并在同一位置（`scanAuthGateSuite:3301` 的裸 `waitForFunction`，该腿本就无 `leg()` 守护——既有写法）超时崩溃；本批树与基线树在该腿上逐字节同败 ⇒ 与本批 diff 无关（本批亦零触碰 scan-auth/begin/status 路径）。隔离跑（`SOCIAL_SUITE=scanauth`，天然新鲜 `apiState`）本批树 **6/6 全绿**——全链下 SAG 排最后，继承前置 suite 留下的共享 `apiState` 残态而其入口不做复位（`:3231` 起的 SAG 头只有 boot + begin 路由），属既有 suite 状态流脆弱性；spec 主分发注释本就记录「full AFTER chain hangs on the environment's known headless baseline failure」并因此给 SCANAUTH 设了独立入口。修法（SAG 入口补 apiState 复位 / 该腿补 `leg()` 守护）留给后续批。
- 无头 macOS 上 `#social-btn` 可见性基线问题见 spec 内既有注释（非本批引入）。
- SCOPE whitelist 脚注：该检查审计的是「在途工作面」（`git status` + `diff HEAD`）——本报告文件在工作期间未提交时会读成 outside-face（A/B 跑里即如此）；落盘提交后该面读空，检查照常过。白名单本批增补 `WeixinIlinkBridgePlugin.scala`（A 件面）。

## 5. 未做 / 边界

- 未动 `FeishuBridgePlugin` 的任何告警面（作者令只点名微信 iLink 桥）。
- 未动 side-car（`tools/weixin-sidecar/msgloop.mjs`）——ret=-2 的退避重试仍在 side-car 侧，本批只改引擎侧预警语义。
- 通道卡健康 hint 的既有文案（`social.hint.health`）未改字；「状态自解释」由既有 strike→hint 链承担。
- probe 并发化后封印卡（wechat/telegram，`hidden:true`）不再被探测——它们从不渲染（`visibleChannels` 单一过滤面），无消费方。

## 6. 关键文件

- `src/main/scala/nebflow/social/WeixinIlinkBridgePlugin.scala`（A 件：探测器降级 + 通知退役）
- `src/test/scala/nebflow/social/WeixinIlinkBridgeSpec.scala`（WI-BD2 重写钉新契约）
- `src/main/resources/web/js/socialPanel.js`（B 件：快照/播种/签名 diff/并发 probe/写穿守卫）
- `tests/social-panel.spec.mjs`（instantSuite + 入口 + 白名单增补）
- 证据物：assembly jar `target/scala-3.5.2/stand-assembly-2026.10.21-beta.2.jar`（boot-smoke PASS，jar hash 085b1adbbcf5557d25a1808c7ee2917174d544b1）
