# 修复批 · 社交入站图片无声丢失（weixin-ilink 腿）— 根因、修复与钉住（2026-10-24）

作者报障（11:06）：「我之前发了22张图你没收到吗」。引擎日志里零入站痕迹，微信出站同时反复 502（`sendmessage ret=-2 prepare failed`）。

## 0. 根因一句话

**22 张图根本没走微信腿**——同会话 11:08 作者已澄清是手机网页版（link.mystand.space）直发，那一路的静默丢失由并行批（delegate-nebflow-81af85aa，#810）承接；weixin-ilink 腿真正的病是**桥会话服务器侧死亡（出站 502 ret=-2 从 10-23 16:57Z 持续 18h+、零自愈提示）+ 入站媒体的三个静默丢失点**（见 §1），本批修后者并给前者装上升级探测器。

调查锚点（全部实测）：

- 6138 网关引擎日志 = `/Users/kaiyu/.stand/logs/stand.log`（`~/.nebflow/logs/nebflow.log` 是**另一个实例**的——作者 grep 的"引擎日志"文件本身就不对，这是"零痕迹"错觉的一半来源）。
- sidecar `~/Library/Logs/nebflow-weixin-sidecar/err.log`：10-23T16:57Z 起 `send: iLink refused … ret=-2 errmsg=prepare failed` 持续至今（数百条）。
- sidecar `out.log`：msgloop 最后一条投递 = 10-23T15:18Z（verdict=injected 成功）；此后锁心跳照打（11:10 仍在更新）、游标静止、**零日志行**——getUpdates 返回空，作者今天没从微信发过消息（11:08 澄清），入站"没丢"是"没发"。
- stand.log 今日 `weixin-ilink bridge` 行：全部是出站 502（`reply send failed` / `image group send failed` / `degrade notice send failed`），零 `sender -> session` 注入行。
- 出站 502 的 engine 侧形态与 2026-10-08 21:52 事故相同（WeixinIlinkBridgePlugin.scala:1118-1124 注释里的历史），但那次修的是"WARN 没打出来"，这次 WARN 打出来了、**没有任何升级**。

## 1. weixin-ilink 入站链路的三个真静默丢失点（本批修复）

1. **媒体下载失败只进 sidecar 私志**：`processInboundMedia` 失败 ⇒ WARN 落 sidecar 日志 + 占位标签 `[图片]` 照常注入（msgloop.mjs 旧 catch 块）——引擎侧零媒体痕迹，作者永远不知道图没进来。
2. **图文同发丢随文**：媒体落地后引擎 mediaArm 传 `text = None`（WeixinIlinkBridgePlugin.scala 旧 :448-452）——图文消息只送图，caption 无声消失。
3. **多媒体项消息只送第一项**：`processInboundMedia`/`mediaArm` 契约是单件，第 2..N 项零日志零回执。
4. **（观测面）长轮询静默超时零痕迹**：getUpdates 客户端超时返回空（msgloop.mjs 旧 :782）不打任何日志——轮询层停摆 = 游标静止 + 心跳照打 + 零日志，正是"零痕迹"的标准形态。

桥会话失效（-14 / ret=-2 长跑）本身已有 sidecar ERROR，但**引擎与作者都看不见、无自愈提示**——10-23 16:57Z 起 18h 数百条回复全灭，作者毫无感知。

## 2. 修复（最小改动、向后兼容）

### sidecar（tools/weixin-sidecar/msgloop.mjs）

- `processInboundMedia` 增加可选 `deps.onFailure(err)` 审计缝：**返回值纪律逐字不变**（null ⇒ 占位降级、游标不持握），每个"存在但没落地"的形状（无账号/无 CDN 引用/无 aes_key/下载/解密/写盘）都报出带 `kind msg=id` 标签的可读原因。
- 新增纯函数 `analyzeInboundMedia(msg)`：在册媒体项普查（2/4/5，语音不算）——v1 单件契约不送第 2..N 项，但**计数**。
- `toInboundBody(msg, media, report)`：第三个可选参数把 `mediaError`（≤240B）与 `mediaExtra` 两个**可选**审计字段挂上电线——旧引擎忽略未知字段，旧 sidecar 不发 = 引擎无回执（双向兼容）。
- `deliverToIngress`：串起以上——失败原因上电线、多项计数上电线、>1 项时 sidecar 自己先 WARN 一条。
- `getUpdates` 客户端超时打 `silentTimeout` 标记；`liveLoop` 连超时 **每 10 个打一条 WARN**（轮询层停摆不再零痕迹）。

### 引擎（src/main/scala/nebflow/social/WeixinIlinkBridgePlugin.scala）

- `Inbound` + `mediaError: Option[String]` / `mediaExtra: Int`；`parseInbound` 读取（缺省 None/0）。
- **mediaArm 随文保留**：真 caption（≠ 占位标签）随指针一起走统一媒体缝的 content 槽；纯媒体消息注入文本保持 "" 原状。
- **`mediaFailureNotice`**：intake 尾部（gate 之后、containment 之内）——媒体未送达恒打 WARN（无论 gate）；gate 通过且 routeKey 在场时经出站缝**回执作者一句话**（哪类媒体、为什么、其余几项、替代路径）；回执发送失败再降级 WARN，绝不翻案。gate 外的发送者**不回执**（fail-closed，WI-M12 钉住）。
- **桥会话失效探测器**：`outbound`/`outboundMedia`/`outboundGroup` 三个出站缝统一包 `noteSendHealth`——纯判定 `bridgeDeadStreak`（成功回零；502+ret=-2 累进；其它失败中立）≥5 连拒 ⇒ **一次性升级**：ERROR（"bridge session DEAD"）+ 健康面 `[bridge-session-dead]` strike（面板标红，writeStrike 泛化 reason 参数）+ 把 `bridgeDeadNotice`（重绑指引）注入发送者绑定的会话（网页端可见——扫码卡就在那）。成功即复位（streak 与告警闸、清 strike 回绿）；文本/媒体腿都喂同一探测器（媒体腿单死也算死）。真"重登"需作者扫一次码，探测器负责把人带到扫码动作前。
- `-14` 自愈现状不动（msgloop 自愈批的索引重读/账号切换已覆盖），由探测器补"无人重绑时的有声升级"。

## 3. spec 结果（三条路径全部钉住）

**① 入站图片注入成功路径**（既有 + 本批增强）：
- sidecar：LA5 健康落盘回归（mediaKind/mime/字节）、LA8 四字段上电线且无审计噪音。
- 引擎：WI-M3/M5/M6 指针缝（回归）、**WI-M9 真 caption 随媒体缝送达（content 槽）/ WI-M9b 占位标签仍被滤除**。

**② 下载失败有声路径**（新）：
- sidecar：LA2 网络失败 onFailure 带 `kind msg=id` 原因、LA3 三种确定性拒绝各自报因、LA4 无媒体项不误报、LA6 电线形状（mediaError/mediaExtra 可选、占位标签仍在）、LA7 端到端（失败 ⇒ wire 带 mediaError；多项 ⇒ mediaExtra=n-1）。
- 引擎：WI-M10 WARN+回执作者+占位仍送达；WI-M11 mediaExtra 回执点名"其余 N 个" + parseInbound 两字段契约；WI-M12 gate 外发送者零回执（WARN 照打）。

**③ 桥重登路径**（新）：
- WI-BD1 `bridgeDeadStreak` 纯判定表（成功回零/502+ret=-2 累进/其它中立/码精确匹配）。
- WI-BD2 5 连拒升级一次性（ERROR + strike + 会话内重绑提示各恰好一份）、成功窗后二次死亡**再**告警（re-arm）、健康面 strike 落盘（sender 键）。
- WI-BD3 媒体缝同喂探测器（媒体单死也算死）。
- sidecar：LA9 长轮询超时打标记（liveLoop 连超时 WARN 每 10 个）。

运行面：sidecar `node --test` 7 个文件 94 用例全绿（新增 9）；引擎 spec 见交付报告（WeixinIlinkBridgeSpec 新增 WI-M9..M12、WI-BD1..BD3）。

## 4. 已知残面（候批，未动刀）

| 残面 | 说明 |
|---|---|
| v1 单件媒体契约 | 一条消息多个媒体项仍只送第 1 项——现在有 mediaExtra 计数+回执，**丢失可见但仍在**；扩成多件契约（mediaPaths 列表或逐件投递）是电线变更，候作者裁 |
| 出站 ret=-2 频控 vs 态坏的区分 | 探测器对"频控连发"也会累计（5 连拒阈值下短爆不触发）；sidecar 退避语义未动 |
| 引擎侧 -14 不可见 | -14 只存在于 sidecar 日志；桥死探测器从出站面反推，已覆盖"作者在使用"的场景；纯入站死法（作者只发不收）靠 silentTimeout WARN（sidecar 日志）+ 面板 strike 间接覆盖 |
| 22 张图本体 | 网页 WS 腿（link.mystand.space）整帧超限静默截断——并行批 delegate-nebflow-81af85aa（#810）承接，与本批无重叠 |

## 5. 同族案例并入（作者令 2026-10-24 11:1x）：飞书入站图必达

**实证**（2026-10-24 11:10:39 起，stand.log）：作者从飞书发图，**每一张**下载失败
`JsonIOException: Failed making field 'java.io.ByteArrayOutputStream#buf' accessible`
（message_id=om_x100b62f6542df0accfdb07e3e90de16，image_key=img_v3_0216p_219d4d33-…）；
日志全史零 `landed`——这条腿自上线从未成功过。报错即"第 4 张同样的错""第 7 张仍在门外"的
确定性来源。

**根因（对 oapi-sdk 2.8.5 反编译确诊）**：SDK 把下载建模为
`GetImageResp extends BaseResponse<java.io.ByteArrayOutputStream>`——**流对象进了 Gson
序列化 DTO**。任何非二进制（错误 JSON）响应都会走 `unmarshalResp(raw, GetImageResp.class)`，
Gson 为 DTO 建反射适配器时对 `ByteArrayOutputStream#buf` 做 `setAccessible`，
JDK 23 强封装下直接抛 JsonIOException——**飞书的真实 code/msg 永远读不出来**，
拒绝不可读、失败确定化。（作者令的诊断方向"DTO 里存 byte[] 或注册 TypeAdapter"完全正确；
病灶在 SDK 的 DTO，本仓够不着 jar，故绕行。）

**修法**：`FeishuChannel.getImage` 弃用 SDK、自有电线——tenant_access_token
（对象内 90min 缓存，键=appId+region，secret 不上任何日志面）+ `java.net.http`
裸 GET `im/v1/images/:image_key`，字节进出、错误 JSON 用 circe 读出飞书原生 code/msg。
**签名与 `ImageResult` 面逐字不动**（FeishuBridgePlugin 零改动）；
`RawHttp` 传输缝可注入（离线 spec 桩）。顺带把 `baseUrlFor`（返回 SDK 枚举）拆出
字符串面 `baseOriginOf`。

**同桥同类点位审计（一并交付）**：
- 出站上传腿（`RichFeishuAdapter.liveUploadImage/liveUploadFile`）：请求 DTO 内嵌
  `java.io.File`（`CreateImageReqBody.image`/`CreateFileReqBody.file`）。成功路径无害；
  但 SDK 的 callError 日志会对 req 做 `Gson.toJson` → 反射 `java.io.File` 字段同样是
  JDK 23 强封装雷——**上传被拒时真实错误会被 InaccessibleObjectException 掩盖**。
  候批：上传腿亦迁裸 HTTP（multipart），本批未动（非本次事故路径）。
- 文本/卡片发送（`sendText`/`liveSendMsg`）：双向纯数据 DTO，无流字段，安全。
- 本仓自有代码（两腿桥）：全部 circe + `Array[Byte]`，零流对象入 JSON，安全。

**spec（飞书入站图必达，`FeishuInboundImageSpec`，7 用例）**：
- FI1 200 二进制 ⇒ 字节逐字落地（无重编码、无流 DTO）；
- FI2 **事故形状钉死**：错误 JSON（429 + 飞书 code/msg）⇒ `ImageResult` 带飞书原生
  code/msg，绝不抛、绝不出现 JsonIOException/ByteArrayOutputStream 字样（red-by-construction）；
- FI3 token 拒绝显式可读（code/msg，secret 不上线）；FI4 传输死亡显式可读；
- FI5 非 JSON 错误体诚实形状注记；FI6 空 200 是失败非空成功；FI7 token 缓存只取一次。
