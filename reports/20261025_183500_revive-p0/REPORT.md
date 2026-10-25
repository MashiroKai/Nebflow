# revive-p0 批 · #809 飞书入站图片 + #810 带附件消息丢失 · 双件续做收口 REPORT

- 批次：revive-p0（stranded-triage §3/§4 P0 两件续做：992a3c8c + 81af85aa）
- 日期：2026-10-25
- 工作分支：`delegate/delegate-nebflow-9f02d0a3`（基于 main@4c866d61d，两 stranded 分支 `--no-ff` 并入 + 收口提交）
- 对账基线：reports/20261025_180431_stranded-triage/REPORT.md §3⑤⑥

## 0. 结论一句话

两件全绿收口：#809 hotfix（飞书入站图自握下载）落地 + weixin loud-fail 续作收尾（4 处快照未验证红全数修净）；#810 分片收发快照本身即完工态、零改码验收绿。全部 spec 通过（三新 spec 74/74、social+gateway 族 485/485、前端族与 main 基线持平、sidecar 101/101、checkJs 260=260 零新增、bundle 构建通过），等合并队列落 main，台账 #809/#810 complete。

## 1. #809 社交入站图片无声丢失（分支 992a3c8c：hotfix da594518c + wip 1455a2f10）

### 1.1 合并冲突

无文本冲突。三处 auto-merge 需语义核对，核对结果：

| 文件 | main 侧改动（b87dfc3da..main） | 判定 |
|---|---|---|
| WeixinIlinkBridgePlugin.scala | 5d8c1b975（通知面文件名反斜杠归一，:1633 区域）+ d792187f7 | 与分支改动面（探测器/媒体臂）不相交，共存 ✓（485 绿实证） |
| msgloop.mjs | d792187f7（ingress backoff） | 与分支 loud-fail 改动共存 ✓（sidecar 101 绿实证） |
| FeishuChannel.scala | 零提交（10-20 起） | 干净快进 |

### 1.2 main 现行 bug 形态验证（修前）

`main@4c866d61d:src/main/scala/nebflow/social/FeishuChannel.scala:488` 仍为 SDK 腿
`client.im().v1().image().get(req)`（oapi-sdk `GetImageResp extends
BaseResponse<ByteArrayOutputStream>`，错误 JSON 走 Gson 反射适配器 → JDK 23 强封装下
`JsonIOException` 必炸、飞书真实 code/msg 永不可读）——与 stranded-triage §3⑤ 实证一致。
合并后该腿整体移除（diff 中 `GetImageReq` import 与 `.get(req)` 调用均为删除行）。

### 1.3 落地内容（hotfix da594518c 逐字保留）

- `FeishuChannel.scala`：`getImage` 弃 SDK 自握电线——`RawHttp` 传输缝（:491，可注入）+
  `tenantToken`（:535，90min 内存缓存，secret 不上线）+ `getImageVia`（:607，裸 GET
  `im/v1/images/:image_key`，circe 读错误 JSON）+ `imageErrorReading`（:570）；
  公开签名与 `ImageResult` 面不动，FeishuBridgePlugin 零改动。
- `FeishuInboundImageSpec.scala`（154 行，7 用例 FI1-FI7）：字节逐字落地 / 事故形状
  red-by-construction / token 拒绝可读 / 传输死亡可读 / 非 JSON 形状注记 / 空 200 非空成功 /
  token 缓存。
- wip 续作（weixin-ilink 腿，续作会话 79a1f1d8/3619d5cc 进度）：sidecar
  `msgloop.mjs` 媒体失败 `onFailure` 审计缝 + `mediaError`/`mediaExtra` 上电线 +
  silentTimeout 标记；引擎 `mediaArm` 随文保留（真 caption 随媒体缝）+
  `mediaFailureNotice`（WARN+回执）+ 桥会话失效探测器（`noteSendHealth` 包三出站缝，
  5 连拒 ERROR + [bridge-session-dead] strike + 会话内重绑指引）；新
  `msgloop-loudfail.test.mjs`（9 用例）。

### 1.4 收尾清单（本批实修——快照 spec 从未跑过，4 红全数修净）

| # | 失败 | 根因 | 修法 | 位置 |
|---|---|---|---|---|
| 1 | FI5 非 JSON 形状注验断 | 测试字面差一：`"<html>gateway</html>"` 是 20B 手写 21B；且第二断言 `(429,"busy")` 与 FI2 钉死的「feishu 原生 code 上浮」语义相抵（应为 99991400） | 字面勘误 21B→20B、429→99991400（注释注明勘误依据） | FeishuInboundImageSpec.scala:117 |
| 2 | WI-M12 gate 外发送者 WARN 缺席 | 实现把 WARN+回执整体闸在 `gateOpen` 后（`if gateOpen then mediaFailureNotice(in)`），与自身注释及 spec 相抵（设计=WARN 恒打、仅回执受闸） | `mediaFailureNotice(in, receiptAllowed)` 双参数化：WARN 恒打（gate 关时后缀改「gated out, no receipt (fail-closed)」），回执仅 `receiptAllowed=true` 发送；调用点无条件调用 | WeixinIlinkBridgePlugin.scala:526 + intake 尾 |
| 3 | WI-BD2 终点 strike 断言空 | 测试在 16 连发**终点**断言 strike 在场；实现按批内设计「成功即复位、清 strike 回绿」在恢复窗已清——断言相位错，非实现错 | 测试重构为逐窗断言：死亡窗 strike 在场（key=sender）→ 恢复窗清零（回绿腿显式钉住）→ 再死亡窗 strike 重现（re-arm 钉住）→ 终点回绿；ERROR×2/回执×2 断言保留 | WeixinIlinkBridgeSpec.scala:1541 |
| 4 | WI-BD3 媒体单死零升级 | 单一共享 streak +「任一成功回零」：ask 卡 = 附件失败与卡文本成功**交替**，媒体 streak 永远到不了 5——「媒体腿单死也算死」在共享 streak 下不可达 | streak 按腿分开（text/media 各持，`bridgeDeadStreak` 纯函数签名不动）；告警闸共享（一次死亡一次升级）；**双 streak 同时归零才判全愈**（re-arm + 清 strike 回绿=桥整体回活）；`outbound`/`outboundMedia`/`outboundGroup` 各报腿 | WeixinIlinkBridgePlugin.scala:1331（noteSendHealth）、:2017（doc） |

### 1.5 spec 读数（#809 族）

- `FeishuInboundImageSpec` 7/7 绿（FI5 勘误后）
- `WeixinIlinkBridgeSpec` 63/63 绿（WI-M1-M12、WI-BD1-BD3、WI-AB/NR/F 全数）
- sidecar `node --test`（tools/weixin-sidecar）**101/101** 绿（含 msgloop-loudfail 9/9）
- **邻族回归** `nebflow.social.*` + gateway 静态/WS 面（OversizeFrame/AssetVersioning/HttpCache/JsStaticRoutes/AssetsRoutes）= **485/485 全绿**（含 Feishu 族 13 套件、Weixin 族 7 套件、SocialChannels 族）

## 2. #810 网页端带附件消息整体丢失（分支 81af85aa：wip cc1e41ee5）

### 2.1 合并冲突

无文本冲突。四处 auto-merge 语义核对：input.js 与 main 的 queue-one-per-turn（13b6864be）、
queue-idle-return（ef525f662）、mobile-P1（754745869）改动**不同函数段**（四腿派发点在
`send()`/`dispatchOfflineItem`/`sendImmediate`/`drainMessageQueueNow`，main 批改的是
drain 闸/对账网——共存实证=queue 族 spec 全绿）；WebSocketRoutes 与 cache-busting 批
（4df51ddcd）合并结果=分支的 64MB+有声拒绝 + main 的 `assetCacheControl` 版本化头并存。

### 2.2 快照判定：完工态，零改码收尾

wip 内容经逐面审读+运行验证即完整（reportStatus=null 系会话被停机保护扫落前未及汇报，
非残片）：

- `input.js`：`WS_SPLIT_MAX_BYTES=8MB`（:110，刻意低于旧 10MB 服务端上限）+
  `dispatchUserMessageFrames` 派发台（:130）——低于阈值单帧逐字原样；超阈拆「正文必达
  首帧 + 附件按字节分批帧」，附件帧幂等键 = 主 id + `/aN`（躲 AgentActor
  recentMessageIds 判重）；**四条派发腿全部改道**（send/drain/immediate/offline，⑥b 钉
  ≥4 调用点，实际 5 处）。
- `WebSocketRoutes.scala`：`MaxMessageSize` 10MB→64MB（:1684）+ 超限支从静默 warn 改为
  `messageTooLarge` 有声回执（:1741-1753，带 sessionId/clientMessageId 对账键）。
- `main.js`：`onMessage('messageTooLarge')`（:1493）——用户可见错误行（locale
  `send.messageTooLarge`，en/zh-CN 各一键）+ 幻影 busy 收口（撤确认网/清看门/清
  busy/drain，messageDuplicate 同款口径）。
- spec：`tests/attach-split.spec.mjs`（223 行，25 断言）+ `OversizeFrameLoudRejectSpec`
  （4 断言源码契约钉）。

### 2.3 spec 读数（#810 族）

- `tests/attach-split.spec.mjs` **25/25 ALL PASS**（22 图大帧：①a-d 正文必达先行/②a-e
  分批守恒/③a-b 独立幂等键/④a-b 小消息零回归/⑤a-c 超限有声+幻影 busy 收口+零
  pageerror/⑥a-g 源面静态钉）
- `OversizeFrameLoudRejectSpec` 4/4 绿
- **input/send 族回归**（前端 26 套件全量点名）：
  - node 自包含形态 11 套件 ALL PASS：attachkey-idem 外的 f1-send-queue、
    queue-one-per-turn、queue-idle-flush、queue-idle-return、busy-lifecycle、
    send-bubble-independence、resend-dedup（含 D3 既有过期钉，
    aefea8f77 已申报非本批）、imgmsg-send、queue-image-refresh、queuebar-informative +
    attach-split
  - @playwright/test 形态 9 套件经 runner 执行：**42 passed / 24 failed**——与 main 基线
    **逐字持平**（同 9 套件对 main@4c866d61d 复跑同为 42/24；失败全为环境预存：instance-backed
    需真实例、differential 需 before 树、queuepos 需引擎载荷 dump，非本批引入，零回归）

## 3. 全局面验证

| 门 | 读数 | 判定 |
|---|---|---|
| checkJs（scripts/check-js-types.mjs） | **260 errors = 冻结基线 260，零新增**（257 冻结 + 3 nearpath 既有漂移，与 ef525f662/ddb74b7b9 批口径一致） | PASS |
| 生产 bundle（scripts/build-web.mjs） | js entry + 194 lazy chunks，orphans: 0 | PASS |
| scalafmt | 本批 3 个 Scala 文件 scalafmtAll 落盘；**无关文件的 331 文件 main 既有格式漂移未纳入本批**（#817 round-2 未落地导致，属 #817 续做批范围，刻意不夹带） | PASS（限定面） |
| 工作树洁度 | 仅 3 个 Scala 收口文件 + 本报告；evidence png 复渲染噪音已还原 | 干净 |

## 4. 台账与落地

- 台账 `~/.stand/tasks-v2.json`：#809、#810 → complete（completedAt=本批）。
- 落地：本分支等合并队列 `--no-ff` 落 main（分支含 main 尖，无冲突可能）。
- 遗留候批（不在本件范围，均有显式缝）：#809 wip 报告 §4 残面表（v1 单件媒体契约多件
  投递、上传腿 Gson/`java.io.File` 雷迁裸 HTTP、频控 vs 态坏区分）；#810 的
  messageTooLarge 仅在「未升级前端撞 64MB」时出现（拆分阈值双保险下的防守纵深，
  设计如此）。

## 5. 关键文件

- `src/main/scala/nebflow/social/FeishuChannel.scala`（hotfix 本体，:491/:535/:570/:603/:607）
- `src/main/scala/nebflow/social/WeixinIlinkBridgePlugin.scala`（续作收口：:526 mediaFailureNotice 双参、:1331 noteSendHealth 按腿、:2017 bridgeDeadStreak doc）
- `src/test/scala/nebflow/social/FeishuInboundImageSpec.scala`（FI5 勘误 ：117）
- `src/test/scala/nebflow/social/WeixinIlinkBridgeSpec.scala`（WI-BD2 相位重构 ：1541）
- `src/main/scala/nebflow/gateway/WebSocketRoutes.scala`（:1684 上限、:1741 有声拒绝）
- `src/main/resources/web/js/input.js`（:110 阈值、:130 派发台）
- `src/main/resources/web/js/main.js`（:1493 回执处理）、`locales/en.js`/`zh-CN.js`（send.messageTooLarge）
- `src/test/scala/nebflow/gateway/OversizeFrameLoudRejectSpec.scala`、`tests/attach-split.spec.mjs`、`tools/weixin-sidecar/msgloop.mjs`+`msgloop-loudfail.test.mjs`
