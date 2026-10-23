# console-fastpath 批 REPORT（作者令 2026-10-23 三腿：设备识别快速通道 + 请求打包 + 大文件 LAN 直传）

分支：`delegate/delegate-nebflow-791ae42c`（基于 d2b28ad97）
诊断依据：`evidence/20261023_234431_console-slow-diagnosis.md`（控制台整页 410 请求/14.1MB 全走浏览器→阿里云香港 relay→隧道→作者网关机，每请求两段跨境 RTT + 256KiB 分块；localhost 直开同页 DCL 392ms）

## 三腿实现要点（各一句话）

1. **设备识别快速通道**：复用「消息标记设备」同一判定面——浏览端 device stamp 的 `boundDeviceId`（deviceIdentity.js 设置页绑定）对照网关花名册（`GET /api/neblink/nearpath` 的 `enrolled` 标记，判定词表与 WebSocketRoutes 消息设备登记判定一致），命中才拿到本机 LAN 地址清单；ws.js 在每次 WS onopen（首连+重连）主动探测候选（环回 + LAN，nf-reach 验证），命中即带 console ticket 整页跳 LAN/环回直连，未登记或探测失败静默停留 relay（作者令「是就走快速通道，不是就走 relay」由服务端强制执行：未登记设备的响应 `lanAddresses` 恒空）。
2. **请求打包**：新增 `POST /api/console-assets`（≤64 路径/请求、单文件 ≤512KiB、整包 ≤8MiB、白名单前缀 css/js/vendor/brand+favicon，与静态树同一 classpath 源、同一 traversal 守卫）；隧道脸（TunnelOriginMark 进程自签标记）的 `GET /` 应答由服务端把 index 引用的全部 stylesheet 与 5 个 vendor defer 脚本**内联织进 HTML**（`StaticRoutes.packedIndexHtml`，media 属性保留、CSS 相对 url() 重写绝对、`</script` 突破加固）——首开静态墙 45 条请求变 1 条，直连脸应答逐字节不变。
3. **大文件 LAN 直传**：`POST /api/nf-ticket` 铸票响应并键 `size`（文件真实字节数）；浏览端 `nfTicket.largeFileDirectUrl()` 三键齐备（size ≥ **4 MiB** 阈值 ∧ 本会话近路探测已验证在册 ∧ 票据有效）⇒ 绝对 URL 跨源直取 `http://<near>/api/nf-file?…&ticket=…`；服务端 `/api/nf-file` 全出向补 CORS/PNA 头（ACAO `*` + Allow-Private-Network + 读数头 Expose）并新增 OPTIONS 预检腿——凭据语义零变化（ticket 仍是唯一凭据，坏票仍 403），直传只是字节路由变更。

## 设备识别命中判定逻辑

```
浏览端: deviceStamp().boundDeviceId          ← 设置页「消息设备标记」绑定（与消息帧 device 键同源）
网关:   bound == identity.deviceId            → enrolled=true, matched=self
        bound ∈ peers[].deviceId               → enrolled=true, matched=peer（花名册 = self + peers，
                                                   与 WebSocketRoutes.deviceProvenanceOf 同一词表）
        否则                                      → enrolled=false, lanAddresses=[]（服务端强制走 relay）
环回候选: 不受 enrolled 限制（ws.js 既有近路短路链自带 /api/health deviceId 锚，同机识别天然成立；
         本地加速批 2026-10-22 行为逐字保留）
```

## 请求打包实测数字

实测环境：隔离实例 `STAND_HOME=/tmp/stand-fastpath-verify`（`sbt run --port 18099`），harness `scripts/verify-console-fastpath.mjs`（对活实例实测 A/B/E 面 + 隧道 RTT 模型投影 F）。

| 项 | 请求数 | localhost 实测 | 隧道投影（150ms RTT，诊断件同口径：每请求两段 RTT） |
|---|---|---|---|
| A. 首开静态墙逐文件取（浏览器 6 连接并行波） | 37 | 244ms / 2.14MB | 7 波 × 2RTT = **2100ms** |
| B. `/api/console-assets` 打包取（全量 37 资产，0 missing） | **1** | 652→**见下** / 2.86MB base64 | 1 × 2RTT = **300ms（7×↓）** |
| C. 隧道脸内联打包 `GET /`（index + 全资产织进 HTML） | **1（含 HTML 本体）** | —（仅隧道脸应答） | 1 × 2RTT ≈ **300ms 拿满首开墙** |

- 隧道首开静态墙：**45+ 条请求 → 1 条**（C 面）；module 图（~140 个 ES module）不受打包影响——隧道桥逐请求本就并发（PortalTunnelBridge 每周期独立 fiber，mailbox 只跑 O(1) 注册，复核确认无串行瓶颈），但逐文件 RTT 墙被 C 面消灭。
- 作者问「400多个请求能打包一起发送吗」的直白回答：**静态段能**（css/vendor/favicon 45→1）；**整条瀑布里剩下的 ES module 图与 API 面不能合并传输**（无构建纪律下模块图不可合；API 面走 WS 单连接无逐请求 RTT），但桥侧并发已保证它们共享隧道不按序排队。
- B 面 652ms（localhost）→ 优化为并行读取后复测见下（隧道场景下 RTT 主导，本端 10ms 级差异可忽略）。

## 大文件直传阈值

**4 MiB**（`nfTicket.js LAN_DIRECT_MIN_BYTES`）：≥ 阈值才绕隧道——256KiB body_frame 下 4MiB = 16 帧跨境 vs 直传一次 LAN RTT 的净赚线；小于阈值走 relay 无感，直传预检反而是开销。nfFileCache 桶预算（local 128 / device 96 / server 32 MiB）在其下游不受影响。

## 鉴权等价性（边界纪律核对）

- 快速通道整页跳 = 复用既有 console ticket 铸/兑换链路（relay mint → `/?ticket=` 303 → 会话 cookie 落目标 origin，`local-ticket` 腿绑定环回 socket 的 GatewayAuth.localTicketOnLeg 判据原样生效）——零新鉴权语义。
- LAN 直传 fetch = 同一 ticket store / 同一 NfPathPolicy 判据 / 同一 NfServeGate 预算，只是字节路由从隧道换成 LAN；CORS 头不替代票（无票仍 401、坏票仍 403，spec ③钉死）。
- relay 公网兜底面未动：近路全部「探测失败/未登记 ⇒ 停留现状」。

## Spec 结果

| Spec | 面 | 结果 |
|---|---|---|
| `ConsoleFastpathSpec`（新） | ① nearpath 识别（命中/未命中/匿名 401）② console-assets（白名单字节一致/traversal/超体积/64 护栏）③ packedIndexHtml（内联/media/url 重写/module 保留/5 vendor 内联）rewriteCssUrls | 见下 |
| `NfFileLanDirectSpec`（新） | ① 铸票 size 并键 = 真实字节数 ② OPTIONS 204 + ACAO/PNA、无票 401 也带 ACAO ③ 坏票仍 403（CORS 不替代票） | 见下 |
| 回归（同域 spec） | `NfTicketRoutesSpec` `NfFileRoutesSpec` `NeblinkBootstrapLegSpec` IndexWithBrand 系 | 见下 |

（最终 green 清单在交付前跑完填入；789 批并行改 spec 的冲突按「先并者优先」处理，本批侧重跑。）

## 文件清单

服务端：
- `src/main/scala/nebflow/gateway/LanAddresses.scala`（新）—— 本机私网 IPv4 枚举（site-local 保守口径）
- `src/main/scala/nebflow/gateway/NeblinkRoutes.scala` —— `GET /neblink/nearpath` 识别面
- `src/main/scala/nebflow/gateway/ConsoleAssetBatchRoutes.scala`（新）—— `POST /console-assets` 打包面
- `src/main/scala/nebflow/gateway/StaticRoutes.scala` —— `indexResponseFor`（隧道分发）+ `packedIndexHtml`/`rewriteCssUrls`
- `src/main/scala/nebflow/gateway/WebSocketRoutes.scala` —— `/` 与 `/index.html` 四面改经 `indexResponseFor`
- `src/main/scala/nebflow/gateway/NfFileRoutes.scala` —— 铸票 size 并键 + nf-file 全出向 CORS/PNA + OPTIONS 腿
- `src/main/scala/nebflow/gateway/RestApiRoutes.scala` —— 挂载 console-assets

浏览端：
- `src/main/resources/web/js/nearpath.js`（新）—— 主动探测 + 会话结论记录 + `getNearPath()` 消费面
- `src/main/resources/web/js/ws.js` —— onopen 探测钩子 + `attemptLanNearPathJump()`（30s 防摆闸）
- `src/main/resources/web/js/nfTicket.js` —— size 缓存 + `largeFileDirectUrl()`（4MiB 阈值）
- `src/main/resources/web/js/nfFileCache.js` —— `nfViewerSource` 大文件直传腿（回落原票据腿）

Spec / harness：
- `src/test/scala/nebflow/gateway/ConsoleFastpathSpec.scala`（新）
- `src/test/scala/nebflow/gateway/NfFileLanDirectSpec.scala`（新）
- `scripts/verify-console-fastpath.mjs`（新）—— 活实例实测 harness

## 已知边界 / 未做

- **module 图不打包**：无构建纪律（vanilla ES modules）下 140 个模块无法合并传输；dist 装配（esbuild bundle）本就只有 index + /assets 两段，不受影响。
- **LAN 跳的设备级同构校验**：LAN 候选身份确认靠「地址来自网关自枚举 + nf-reach 品牌自证 + console ticket 兑换失败即拒」，不像环回腿有 deviceId 等值锚；同一 LAN 同端口存在第二个 stand 实例时，跳过去兑换会失败落登录面（既有降级面），不会建立错误会话。
- **https LAN 候选**：当前清单只有 http 基址（网关无 TLS）；LAN 证书面留后续。
- 789 批若先并入同域 spec，冲突解决以先并者优先，本批交付后 rebase 侧重跑。
