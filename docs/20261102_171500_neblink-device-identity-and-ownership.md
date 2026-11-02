---
type: audit
status: current
date: 2026-11-02
scope: NebLink 云端「不同机器」的识别键、机器↔用户归属的建立点与存储位置；两侧客户端派生算法的实证复核 + link.mystand.space 只读实测；服务端源码不在本机
---

```human
**结论（三句话）**
1. 云端区分机器用一个**客户端自报的设备串** `deviceId`，它由本机机器码派生（scala 线 UUIDv5、r 线 sha256 前 128 位），同一台物理机装两个不同 OS 就是两台设备。
2. 归属在 **`POST /api/device/register` 这一次调用**上建立：调用方带 Logto access_token，服务端用 token 的 subject 把这一行设备挂到那个用户/网络上；成功的响应体里故意不含任何 user id。
3. 归属关系只存在**服务端的设备行**里（键 = `deviceId` + `networkId`），两个 token 都是不透明随机串、不是 JWT，客户端无法从凭据里反推出账号。

**关键数字**
- 2 条派生算法：UUIDv5(ns=6f1a2c3d…, `machine|default`) 与 sha256(`neblink-r-line/device-id/v1|machine|scope:dir|gen`) 前 128 位 —— 两者**均已用本机真实凭据复算出逐字相同的结果**。
- 1 个物理机 = 2 个机器码：Windows `MachineGuid 25f12119…` ≠ WSL `/etc/machine-id 74e06be7…`。
- 43 字符：`deviceToken` 与会话 token 同形（base64url(32 B)），实测均非 JWT（0 个 `.` 分段）。
- 3 元组：`/api/device/session` 逐次校验 `deviceId` + `networkId` + `deviceToken`，任一不符即 403。
- 4 台设备共享 1 个 `networkId`（`ad71113b-…`）⇒ 网络是账号面，不是设备面。
- 5/8：本次探针与在跑的 r 线引擎抢同一 (deviceId, networkId) 会话位的胜率 —— 顶号是逐次发生的。

**发现指针**
- 派生算法与 file:line → §1；服务端键面 → §2；归属建立点 → §3；换账号/顶号 → §4、§5；两类 token → §6；networkId → §7；服务端可见性 → §8；原始响应 → §9。

**未决**
- 服务端源码不在本机 ⇒ `machineFingerprint` 是否真参与判定、token 在库里的存储形态（哈希/明文）、`INSERT OR REPLACE` 的逐字语义，都只能到「协议反推 + 二手行号」这一步，未一手核实。
- 审计中途本机 6138 实例于 17:24:29 走了**本地登出**（见 §10），归因未能闭合。

![[neblink-device-identity-chain.svg]]
上图即全链结论：左侧是两条互不相容的机器派生，右侧是服务端行的键与两次换票；归属只在 register 那一格建立。
```

# NebLink 设备身份与归属审计（deviceId / networkId / 归属映射）

审计对象：`link.mystand.space` 上「不同机器」的判据，以及「机器 ↔ 用户」归属的建立时机与存储位置。
方法：两侧客户端源码逐行读码（一手）+ 用本机真实凭据把派生算法**复算一遍**（一手实测）+ 对生产服务端做只读协议实测（一手响应原文）+ 本仓注释里的跨仓只读行号（**二手**，明确标注）。
关联：[[index]]、[[20261031_093600_device-peer-mobile-chat-windows__delegate-nebflow-002f6988]]（设备面既有批）、[[design/device-provenance-spec.zh-CN]]（消息来源设备标记，同为设备面读取者）。

## 0. 八问速答

| # | 问题 | 一句话答案 | 证据强度 |
|---|---|---|---|
| 1 | deviceId 从哪来 | 由**本机机器码**派生，不是随机：scala 线 UUIDv5、r 线 sha256 前 128 位；两算法互不相容 | 一手（源码 + 本机复算双线命中） |
| 2 | 服务端靠什么区分机器 | 行键是 `deviceId`（+`networkId`）；`machineFingerprint` 只在「同机同账号第二个实例」那道闸上用 | 一手实测键面 + 二手行号 + 客户端钉字面量 |
| 3 | 归属在哪一步建立 | `POST /api/device/register` 那一腿，用 Bearer 里 token 的 subject 建立 | 一手（协议形状）+ 二手（服务端不可见） |
| 4 | 一个设备能否绑多账号 | 不能：同 deviceId 换账号 = 409 `device_bound_other_account` | 客户端钉字面量 + 假服务端同构；**真服务端未复现** |
| 5 | 顶号机制 | 同一 (deviceId, networkId) 只留一个活会话，新登录顶掉旧的 | 一手实测（5/8 抢输） |
| 6 | deviceToken 与 access_token | 前者长期不透明串（43 字符），后者 1 小时；前者每次请求都被逐条校验 | 一手实测 + id_token exp−iat=3600 |
| 7 | networkId 是什么 | **账号网络**：同账号多台设备共用一个 networkId | 一手实测（4 设备同串） |
| 8 | 服务端代码在不在本地 | **不在**（多路检索确认），无 `neblink-server` / `link-server` 仓、无 `store.rs`/`routes.rs` | 检索记录见 §8 |

## 1. Q1 — deviceId 从哪来

### 1.1 不是随机 128 位，是「机器码 + 作用域」的确定性派生

两条线各有一套算法，**形状互斥**（这正是作者 2026-11-01 的裁定：r 线与 scala 线是两台独立设备，不许共用 deviceId）。

**scala 线（stand，本机 6138 实例）**

- 命名空间常量钉死：`DeviceIdNamespace = 6f1a2c3d-4e5b-4a7c-8d9e-0f1a2b3c4d5e`（`src/main/scala/nebflow/neblink/NeblinkModel.scala:218-219`，注释「changing it re-mints every client's id」）。
- 派生公式：`deriveDeviceId(machineCode, scope) = uuidV5(DeviceIdNamespace, s"$machineCode|$scope")`（`NeblinkModel.scala:280-281`），`uuidV5` 是 RFC 4122 §4.3 的 SHA-1 name-based（`:289-297`）。
- `scope`：默认数据根恒为字面量 `"default"`（`:221-222`、`:248-249`）；重定向数据根用 `home|<路径摘要>` 形态（`:272-274`，2026-10-14 熵修复）。
- 机器码探针顺序（`:327-331` → `:358-379`）：`NEBLINK_MACHINE_ID`/`NEBFLOW_MACHINE_ID` 环境变量覆盖 → macOS `IOPlatformUUID`（ioreg）→ Windows `MachineGuid`（`reg query`）→ Linux `/etc/machine-id`，退化 `/sys/class/dmi/id/product_uuid`；占位值 `uninitialized|none|unknown` 视为读不到（`:313-318`）。**读不到 ⇒ 回退随机 UUID 并照样落盘**（`:502-518`）。

**r 线（stand-rust）**

- 常量：前缀 `r-`（`cap/apps/stand/src/neblink/identity.rs:46`）、hex 长度 32（`:48`）、命名空间串 `neblink-r-line/device-id/v1`（`:50`）、上报 platform `stand-rust`（`:52`）。
- 派生公式：`"r-" + sha256("{ns}|{machine}|scope:{scope}|gen:{generation}")[..32]`（`identity.rs:97-102`）。
- `machine` 探针顺序（`:125-164`）：环境变量 → Linux `/etc/machine-id` → Windows `MachineGuid`（只读 `reg query`）→ 主机名 `host:{COMPUTERNAME|HOSTNAME}` 兜底。
- `scope` = 身份目录绝对路径（`:298-301`），故**一目录 = 一台设备**；`generation` 轮换即换 ID（`:356-385`）。

### 1.2 复算实证（两条线都用本机真实凭据算了一遍，逐字命中）

用 Python 独立实现两个派生，输入本机真实机器码与作用域：

| 线 | 输入 | 算出 | 本机落盘值 | 命中 |
|---|---|---|---|---|
| scala | `MachineGuid=25f12119-9e58-4b3c-8396-537e57035d3b`，scope=`default` | `00c44b24-40b0-5f55-beec-9ecb3c749135` | `~/.stand/neblink/device.json` 的 `deviceId` = `00c44b24-40b0-5f55-beec-9ecb3c749135` | ✅ |
| r 线 | `/etc/machine-id=74e06be7c2974a49afab449fc2fc168c`，scope=`/home/ysm123456/.stand/neblink-r`，gen=0 | `r-ed651066c3bf8d10e4a037a65dc78fa7` | `~/.stand/neblink-r/credential.json` 的 `device_id` | ✅ |

副产物两条（同一次复算）：`gen=1` 得 `r-f306cd5077ee43fe298b9340931d44a5`（轮换即换 ID）；scope 尾斜杠不同得 `r-802b575bb0a5d3ed0f63500da5862671`（r 线 `Store::scope` 会 `trim_end_matches(['/','\\'])`，`identity.rs:298-301`，所以带不带尾斜杠收敛到同一台）。

### 1.3 重装系统 / 换 WSL 发行版会不会变

- **换 WSL 发行版或 `wsl --unregister` 重建 ⇒ 会变**：`/etc/machine-id` 由该发行版安装时生成，换了就是新串 ⇒ 新 deviceId。同一条链也解释本机现状：**同一台物理机，Windows 侧机器码 `25f12119…`、WSL 侧 `74e06be7…`，因此是两台设备**（实测，不是推论）。
- **重装 Windows ⇒ 会变**：`MachineGuid` 在系统安装时生成。此条为机制推论，**未实测重装**。
- **删 `device.json` / 丢凭据 ⇒ 不变**：派生是纯函数，重算得同一 ID（`identity.rs:27`「落盘记录才是权威：派生只在首次创建时用一次」；`NeblinkModel.scala:187-189` 同义）。
- **主动轮换 ⇒ 变**：r 线 `rotate()` 把 `generation += 1`、旧 ID 进 `history`（最近 8 个）、并删掉旧凭据（`identity.rs:356-385`）。
- **不是 CPU/主板/MAC 指纹**：没有任何一处读 SMBIOS 序列号、网卡 MAC 或磁盘序列号；只有 r 线的**兜底档**才落到主机名（`identity.rs:160-163`），那是最弱的一档。

## 2. Q2 — 服务端靠什么区分机器

### 2.1 请求面（一手，源码）

`POST /api/device/register`（路径常量 `Protocol.scala:30-36`），body 由 `LogtoTransport.registerRequest` 拼（`src/main/scala/nebflow/neblink/LogtoTransport.scala:89-117`）：

```json
{"deviceId":"…","deviceName":"…","platform":"…","machineFingerprint":"…"}
```

`machineFingerprint` **缺席时不发该键**（不是 null、不是空串，`:100-106`），来源与 deviceId 同源同时刻（`NeblinkModel.scala:333-350`），调用点在 `gateway/AuthRoutes.scala:360-370`。注释把设计面写得很直：`None ⇒ server fail-opens with a WARN`（`LogtoTransport.scala:85-88`）。

### 2.2 服务端接受了什么（一手，实测）

对生产服务端做 body 模式探测（伪造 Bearer，**鉴权在反序列化之后**，所以只暴露 schema、不写任何行）：

| 探测 | 响应 |
|---|---|
| `{}`（无 Authorization 头） | `422 Failed to deserialize … missing field \`deviceId\`` |
| `{}`（伪造 Bearer） | 同上 422 —— **先解 body，后验 token** |
| 只有 `deviceId` | `403 {"error":"Invalid or expired token"}` ⇒ **`deviceName`/`platform` 可选**，只有 `deviceId` 强制且必须是字符串 |
| `deviceId` + 未知名 `totallyUnknownField` | 403（同样通过反序列化）⇒ **未知键被静默忽略** |
| `deviceId` + `machineFingerprint` | 403（通过反序列化） |
| `deviceId: 123` | `422 deviceId: invalid type: integer \`123\`, expected a string` |

**这条实测的一个硬结论**：因为未知键不报错，**线级探测无法区分「`machineFingerprint` 是声明字段但不用」与「根本不认识这个键」**。要把这一格钉死，只能读服务端源码（见 §8）。

### 2.3 键在哪儿（一手实测 + 二手行号）

- **会话面的键是三元组**：`POST /api/device/session` 必须同时给对 `deviceId` + `networkId` + `deviceToken`，任一错位都是 `403 {"error":"Invalid device credential"}`（§9 S1–S4）。这直接说明服务端的设备行按 **(deviceId, networkId)** 定位、凭据挂在行上。
- **二手行号**（本仓注释里的跨仓只读引用，非我本人读到的服务端源码）：enroll 按 `(deviceId, networkId)` `INSERT OR REPLACE`（`neblink-server/src/store.rs:2951-2969`，引于 `NeblinkEnrollment.scala:78-85`）；`devices.id` 是 TEXT、无长度/字符集约束（`store.rs:145-153`，引于 `NeblinkModel.scala:211-212`）；踢线发生在同一 `(device_id, network_id)` 维度（`store.rs:1039` / `:1801`）；403 `Invalid device credential` 出自 `routes.rs:1366-1379`（引于 `NeblinkClient.scala:408-409`、`:1893-1894`）——**这一条与我实测到的响应体逐字相同**，是该注释可信的一个旁证。
- **`machineFingerprint` 的用途**：客户端把它钉成「同机第二个实例」的判据——`CredentialDiagnostics.scala:70-75` 逐字：「同一账号的**另一实例**已在这台机器上登录（同机双 home 双实例——隔离 home 有意派生不同 deviceId，**deviceId 维度判不出「同一台机器」，指纹维度才判得出**，设计 §5.1）」。即：`deviceId` 是**实例/行**维度，`machineFingerprint` 是**机器**维度，后者只服务 409 `device_already_logged_in` 这一道闸。

**答**：唯一性用 `deviceId`（与 `networkId` 组成行键）；`machineFingerprint` 参与判定，但只参与「同机同账号第二实例」那一道闸，且缺字段时服务端 fail-open（后者是客户端注释的断言，未一手核实，见 §8）。

## 3. Q3 — 机器 → 用户的归属在哪一步、用什么键建立

### 3.1 建立点：register 这一腿

- 调用方带 `Authorization: Bearer <Logto access_token>`（`LogtoTransport.scala:97-117` 的 `bearer = Some(accessToken)`；`AuthRoutes.scala:360-370` 传的是 PKCE 换来的 `tokens.accessToken`）。
- 服务端回 200 时只给 `{deviceToken, networkId, avatarUrl?, githubUsername?}`（实测同形；客户端解析见 `LogtoTransport.scala:130-139`、`onboard.rs:315-343`）。**没有任何 user id / sub / email**。
- 客户端确实**没有**把 sub 传给服务端：register body 只有四个键（§2.1）。
- 所以归属只能是**服务端从 Bearer token 的主体（`sub`）推出来的**，在建行那一刻写进设备行。

### 3.2 两个 token 都不是 JWT ⇒ 归属不可能在凭据里

实测：`deviceToken` 43 字符、会话 token 43 字符，都**没有 `.` 分段**（不是 JWT，无可解声明）。43 字符 = base64url(32 字节) = 256 位随机串的典型长度。含义很硬：**客户端一侧完全无法从凭据反推账号；`设备→用户` 只能是服务端的表关系**。

### 3.3 归属的落点（凭据面实测 + 反推）

- 本机 scala 线 `~/.stand/neblink/device.json` 里留着 Logto `idToken`（读于 17:14；该文件在 §10 的本地登出事件里已被删除，读数以当次为准），其 payload 实测：`sub=63mdczgnd8gy`、`iss=https://auth.mystand.space/oidc`、`aud=csxh16cas0x03bgk6w7ej`（Logto client id）、`picture=https://neblink.nebflow.space/avatars/63mdczgnd8gy-….jpg`——**头像路径以 sub 命名**，说明服务端侧的用户主键就是 sub。
- r 线把同一件事落在 `credential.json` 的 `auth_subject`（`onboard.rs:527-533`、`:786-809`：`id_token_subject(&tokens.id_token)`）。本机该字段为**空串**——即这次入网用的登录姿势没有拿到 id_token，客户端就没记下 sub；**这不影响服务端归属**（服务端看的是 access_token，不是客户端记的账）。
- 社交面实测：`GET /api/friends` 返回的行主键是 `userId = ncpiui0ot85n` 形状（12 字符、与 sub 同形），**不是 deviceId**。⇒ 设备面（deviceId）与用户面（sub）是两个键族，桥是网络（§7）。

**答**：归属在 `POST /api/device/register` 这一次调用上建立，键 = Logto access_token 的 subject；存在服务端的设备行里（行键 `(deviceId, networkId)`，行上挂所属用户与 deviceToken）。**具体表名/列名服务端不可见，未查明。**

## 4. Q4 — 一个设备能不能绑多个账号

### 4.1 两个错误码的判定条件（客户端钉字面量 + 用户可见文案）

稳定码逐字钉死（`LogtoTransport.scala:63-74` 的注释、`web/js/neblink.js:93-96` 的反查表、`web/js/locales/zh-CN.js:597-601`）：

| 码 | 判定条件 | 作者可见文案（逐字） |
|---|---|---|
| `device_bound_other_account` | **同一个 `deviceId`** 已绑给**另一个账号**（409） | 「此设备已登录另一个账号：同一台设备同一时间只能登录一个账号」；处置：「先在本机退出原账号…，或到官网「设备管理」移除该设备后再登录」 |
| `device_already_logged_in` | **同一台机器（`machineFingerprint`）**上，同一账号的**另一个实例**已在登录态（409） | 「此设备已有一个登录中的实例：同一台机器同一时间只能登录一个账号」 |

`CredentialDiagnostics.scala:62-75` 把这两者定义成两个独立的失败族（`DeviceBoundOtherAccount` / `DeviceAlreadyLoggedIn`），码值即 wire reason。

### 4.2 换账号登录会发生什么

同一 `deviceId` 换账号 ⇒ 命中第一道闸：409 `device_bound_other_account`，登录被拒；要换账号必须先在本机退出原账号，或在官网设备管理里移除该设备（文案即产品既定动作）。若只是**同机同账号再开一个实例**（隔离 home ⇒ 新 deviceId、同 machineFingerprint）⇒ 命中第二道闸。**这两条是设计口径 + 客户端钉字面量；真服务端的 409 我没有复现**（复现需要第二个账号的 access_token，且注册会按行覆盖，有顶掉作者在跑实例的风险，属本次红线）。r 线侧的假服务端只实现了第一道（`cap/crates/neblink-fake/src/oidc.rs:375-386`），**它是测试替身，不是服务端证据**。

## 5. Q5 — 顶号机制（这条实测到位了）

### 5.1 「同 (deviceId, networkId) 只一个活会话」——一手实测

做法：拿 r 线真实凭据反复 mint 会话，每次立刻用该会话 token 打 `GET /api/friends`。此时 r 线引擎（WSL 里 `linkd`，pid 59326，实测在跑）也在维持自己的会话，于是形成抢位：

```
round0..7: mint=200 每次都换新的 43 字符 token
  friends 200 / 403 交替出现，403 体恒为 {"error":"Missing or invalid token"}
抢赢 5/8；每轮往返 ~1.1 s
```

结论（一手）：**同一 (deviceId, networkId) 只有一个会话 token 有效**；新一次 mint 会把前一个顶掉，被顶掉的那个立刻 403。这正是 §1 里「两线不许共用 deviceId」裁定的机制根因。

### 5.2 「服务器对同一 deviceId 是 INSERT OR REPLACE」——半证

- 支持：本仓三处独立注释同口径引用 `store.rs:2951-2969`（`NeblinkEnrollment.scala:78-85`、`NeblinkSingleFlight.scala:16`、`NeblinkModel.scala:181`），且 r 线注释把同一结论写进设计前提（`identity.rs:96`「服务端按 deviceId 做 INSERT OR REPLACE，两边会互相顶掉」）。
- 不能升格为一手：我没有复现注册覆盖（拒绝用真实账号去覆盖在跑实例的行），服务端源码不在本机。
- **未推翻**。准确写法：`(deviceId, networkId)` 维度的覆盖/顶替是**二手行号 + 一手会话行为**共同支持的一致结论，注册腿本身未一手复现。

## 6. Q6 — deviceToken 与 access_token 的关系

| 维度 | access_token（Logto） | deviceToken（NebLink） | 会话 token |
|---|---|---|---|
| 寿命 | 1 小时（实测 `idToken` 的 `exp-iat = 1793614473-1793610873 = 3600`） | 长期，直到被覆盖/撤销 | 短，随每次 mint 换代 |
| 形态 | JWT（三段 base64url，`alg ES384`） | **43 字符不透明串，非 JWT** | **43 字符不透明串，非 JWT** |
| 谁校验 | 服务端对 Logto 侧验签 | 每次请求逐条比对 | 每次业务请求逐条比对 |
| 客户端存放 | 仅内存 + Logto 刷新链 | `~/.stand/neblink-r/credential.json`（0600）/ scala 侧 `config.json` 的 `neblinkServer.deviceToken` | 不落盘（进程内 `sessionToken`，`NeblinkClient.scala:251`） |

- **校验是每请求一次、无客户端缓存**：实测把一个已失效的会话 token 拿去打 `/api/friends`，立即 403；同一时刻新 mint 的 token 拿到 200。设备凭据侧同理：三元组逐次校验（§9 S1–S3）。
- **服务端怎么存（哈希？明文？）——未查明**：服务端源码不可见，线级探测也无法区分（token 是不透明随机串，回显与否看不出存储形态）。
- 一处可核实的旁证：token 长度 43 = base64url(32 B)，与「服务端随机生成 256 位、库内存散列/明文皆可能」相容，但**这不构成存储形态的结论**。

## 7. Q7 — networkId 是什么

**答：账号网络（identity 是用户/网络的，不是设备的）。同一个账号的多台设备共用同一个 networkId。**

一手实测三条：

1. 本机两条线的凭据里 `networkId` **逐字相同**：`ad71113b-b480-462f-badf-dfed5632df22`（r 线 `credential.json` 与 scala `config.json` 的 `neblinkServer.networkId`），而两者的 `deviceId` 完全不同（`r-ed651066…` vs `00c44b24-…`）。
2. r 线自己的会话响应里 `networkId` 就是这个串，`peers[]` 一次给出 3–4 台**跨平台**设备（`DESKTOP-IJBBEOT`/windows、`KAI`/windows、`Mashiros-MacBook-Pro-iso-a659864a`/macos，先前一拍还含 `DESKTOP-V7EUCHT`/windows）——**名册按 network 聚合，不按 device**。
3. 会话响应的 `peers[]` 里出现 scala 线的 `00c44b24-…`，而这次会话是用 **r 线凭据**认证的 ⇒ 两台 deviceId 不同的设备同属一个网络 ⇒ 「同账号 = 同网络」成立（这同时补上了 §3.2 里 r 线 `auth_subject` 为空、无法直接读 sub 的缺口）。

语义来源（二手）：网络由首台设备 enroll 时自动创建（`store.rs:697 get_or_create_default_network`，注释「auto-created on first device enrollment」，引于既有分诊报告与 `NeblinkEnrollment` 注释族）；「设备列表」= 该网络的活会话名册（`store.rs:3130-3134` 的 `devices_for_user`，引于 `NeblinkDiscovery.scala:201-213`）。**即：账号即网络，登录即入网，没有单独的「建网」步骤。**

## 8. Q8 — 服务端代码在不在本地（明确回答：不在）

检索范围与结果（均为只读检索）：

| 检索 | 结果 |
|---|---|
| 全盘 `store.rs`（`-name store.rs`） | 命中的只有 `triple*/crates/triple-terminal/src/session/store.rs`（终端会话库，与本主题无关） |
| 全盘文件内容 `device_bound_other_account` | 命中全部在本仓客户端与文档：`LogtoTransport.scala`、`CredentialDiagnostics.scala`、`LoginCommand.scala`、`web/js/neblink.js`、`web/js/locales/{zh-CN,en}.js`、两份 spec，以及 r 线的 `onboard.rs` 与**假服务端** `neblink-fake/src/oidc.rs` |
| `INSERT OR REPLACE INTO devices` / `fn enroll_device` | 零命中 |
| `~/.stand/projects/` 与 `/d/workspace@zcode/nebflow/repos/` 下有无 `link-server` / `neblink-server` 仓 | 无（`triple-star/crates/` 只有 star-* / triple-* 共 21 个 crate，无中继服务） |
| WSL 侧是否有服务端仓 | 无 |

**因此：服务端源码不可见。** 本文所有服务端侧结论分三档标注：
- **一手实测**（响应原文，§9）：行的键面（三元组）、会话唯一性/顶号、register body schema 与鉴权次序、peers/networkId/friends 的出参形状。
- **二手行号**（本仓注释里的跨仓只读引用，仓名 `neblink-server`，文件 `src/store.rs` / `src/routes.rs` / `src/main.rs` / `src/relay.rs`）：覆盖语义、devices.id 列约束、踢线维度、403 文案出处。
- **客户端钉字面量/设计断言**（未一手核实）：`machineFingerprint` 的服务端参与度与 fail-open、两个 409 的精确判定条件、token 存储形态。

`cap/crates/neblink-fake` 是**客户端自带的测试替身**，它实现的 register/session 语义与真身「同构」是作者侧的设计主张，**不能当服务端证据**（`oidc.rs:16-21` 自己就这么写：真服务端是否接受带前缀的 deviceId 仍属「一次真机试连才能证伪」的事）。

## 9. 实测原始响应（2026-11-02，`link.mystand.space`，TLS 校验收默认开）

脚本与完整 transcript：`reports/20261102_neblink-device-identity/probe.py`、`…/probe-transcript.log`（**不含任何 token 值**）。

```
[A1] GET /api/health                                  200  {"status":"ok"}
[S1] 正确 deviceId + 正确 networkId + 伪造 token        403  {"error":"Invalid device credential"}
[S2] 伪造 deviceId + 正确 networkId + 正确 token        403  {"error":"Invalid device credential"}
[S3] 正确 deviceId + 伪造 networkId + 正确 token        403  {"error":"Invalid device credential"}
[S4] 三元组全对                                        200  token_len=43 networkId=ad71113b-… deviceId=r-ed651066… peers=3
      peer ad1016bb-… | DESKTOP-IJBBEOT | windows | online=True
      peer 40742cd4-… | KAI | windows | online=True
      peer 6a64d249-… | Mashiros-MacBook-Pro-iso-a659864a | macos | online=True
[R1] register {} 无 Authorization 头                  422  missing field `deviceId`
[R2] register {} 伪造 Bearer                          422  missing field `deviceId`   ← 解 body 先于验 token
[R3] register 只有 deviceId                           403  {"error":"Invalid or expired token"}
[R4] register + 未知字段                               403  同 R3（未知键被忽略）
[R5] register + machineFingerprint                   403  同 R3
[R6] register deviceId=123                           422  expected a string
[F*] mint → GET /api/friends                          200/403 交替（抢位；200 体含 friends/incoming/outgoing，
                                                      userId=ncpiui0ot85n 形状；403 体恒为 Missing or invalid token）
```

三条控制面读数（同一时段）：`GET /api/me`、`/api/devices`、`/api/version` 等未匹配路由在**无凭据时一律 403 `{"error":"Missing token"}`**，故**不能用 404/403 判路由存在性**；`/api/health` 是唯一匿名 200。

红线遵守：只用 r 线 deviceToken 打只读接口；**未打印、未落盘任何 deviceToken / 会话 token**；**未用 scala(6138) 凭据注册任何东西**；未关 TLS 校验；未触碰 6138 端口与 pid 21516（对 6138 只发过两次只读 GET）。

## 10. 审计期间的观测事件（如实记录，归因未闭合）

- 17:22 时段首拍：`peers[]` 里 `00c44b24-…`（本机 scala 实例）**online=True**。
- 17:24:29 本机 scala 实例发生**本地登出**：`~/.stand/neblink/device.json` 被删、`config.json` 的 `enabled` 由 `true` 变 `false`（deviceToken 字段仍在）；引擎日志逐字 `2026-11-02 17:24:29.887 … nebflow.neblink.relay - Relay tunnel disconnected: stop() — tunnel torn down (logout local teardown)` + `nebflow.neblink.discovery - NebLink client hot-swapped`。
- 此后该实例 `/api/neblink/status` 报 `loggedIn:false`，`authRejected` / `signedOutElsewhere` / `autoReconnectParked` **三项均为 false**；`peers[]` 里 `00c44b24-…` 消失。
- 判读：`stop()` 是引擎**登出路径**的日志（`NeblinkRelayTunnel.scala:420-426`），不是服务端拒绝态（拒绝态会走 authRejected/park 而不动本地凭据）。我对 6138 的两次请求都是 17:25 的只读 GET，**在事件之后**。故记录为「本地登出，触发方未查明」，**不归因、也不排除**与我方 session mint 的间接关系。

## 11. 局限与未查明

1. **服务端源码不可见**（§8）⇒ `machineFingerprint` 参与度、token 存储形态、覆盖语义的逐字实现，全部停在「一手行为 + 二手行号」。
2. **两个 409 未在真服务端复现**（需第二账号 access_token，且注册会覆盖行，触红线）。
3. **重装 OS 后 deviceId 是否变**为机制推论，未实测重装。
4. 会话抢占的**精确时间窗**未测（实测只是「1.1 s 往返内被顶掉」这一量级）。
5. 服务端是否有「删除设备/解绑」接口、其键面如何，未探测（需写面权限，超出只读范围）。

## 12. 复现步骤

```bash
# 1) 两条派生算法复算（只需本机机器码与作用域）
python -c "import uuid,hashlib;ns=uuid.UUID('6f1a2c3d-4e5b-4a7c-8d9e-0f1a2b3c4d5e');\
h=bytearray(hashlib.sha1(ns.bytes+b'25f12119-...|default').digest()[:16]);h[6]=(h[6]&0x0f)|0x50;\
h[8]=(h[8]&0x3f)|0x80;print(uuid.UUID(bytes=bytes(h)))"
# 2) 只读协议实测（在跑 r 线凭据，脚本不打印任何 token）
python reports/20261102_neblink-device-identity/probe.py
```
