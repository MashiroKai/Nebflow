# 门户身份透传实施批——实施报告（方案 A 全量 + 方案 B ST 侧三小刀）

任务链：delegate-nebflow-22c29d3d · 作者拍板 2026-10-28 23:28（五条裁定全按主控推荐）· 设计稿 = link-server 仓 `docs/design/20261028_2255_portal-identity-passthrough__delegate-nebflow-0c670d46.md`。

## 0. 结论先行

- **方案 A（核心）全绿**：会话身份进连接（takeover 先例同点）→ owner 等值判定（引擎本机 id_token 解码单点加 `sub`）→ 三级标记优先级（已登记 ＞ 已认证 ＞ 默认名兜底逐字）。scala spec **103/103 绿**（bridge 三件套 33 + 网关回归子集 67 + StaticRoutesFingerprint 3）。
- **方案 B ST 侧三小刀落地**：Safari 双 host 探测（命中哪个跳哪个）、miss 诊断曝光（sessionStorage 诊断键 + 引擎侧两行日志）、陈旧 SPA 指纹提示（serverConfig 带 `webFingerprint` + 页内注入比对 + 顶栏刷新提示）。`tests/nearpath-dualhost.spec.mjs` **9/9 绿**；既有 `loopback-nearpath.spec.mjs` 回归 PASS（#1013/#1020 心跳遮蔽腿 T4 含在内）。
- **隔离引擎 + 真浏览器 e2e**（见 §3）：伪造门户铸票会话（假中继下发 ConsoleTicketMint 帧）→ owner sub ⇒ 判词 `Mashiro · Safari（经官网）` `(authenticated=true)`；非 owner sub ⇒ `authenticated=false` 默认名兜底；指纹不匹配 ⇒ 顶栏提示条出现。截图进 `.nebflow/evidence/20261028_portal-identity-passthrough/`。
- **在产零触碰**：e2e 全程隔离 home `/tmp/nb-portal-identity/home` + 端口 8687；在产引擎 pid 70481 存活、网关 6138 应答正常（e2e 运行中实测）。

## 1. 收割了什么（合并入本批前工作区已有的）

收割检查 = 开工时工作树零未提交改动（`git status` clean），无在途半成品可收割；本批全部为新增实施。

## 2. 新做什么（逐刀）

### 方案 A（commit 28a0df6bf）
| 刀 | 文件 | 说明 |
|---|---|---|
| A1 会话身份进连接 | `gateway/WebSocketRoutes.scala` | 握手处 `takeoverSession` 同点提取 `SessionIdentity(accountSub, origin, sessionId)`（SessionRecord 投影）；收流管闭包穿线 → typeless 腿 + immediateInput 腿（`wsDispatchContext` 按连接闭包）双消费。Bearer 轨/REST headless 恒 None，行为逐字节不变 |
| A2 owner 等值 | `gateway/OwnerIdentity.scala`（新） | 引擎本机凭据 id_token → `LogtoAuthCode.decodeIdTokenClaims(Seq("sub","email","name"))` 解码单点 + token 键控内存缓存；凭据缺席/坏档 fail-open None；sub 只等值不出对象 |
| A3 三级优先级 | `bridge/DeviceProvenance.scala` | `resolve(client, self, peers, session=None, owner=None)`——①boundDeviceId 花名册命中（已登记）②owner 等值（已认证，账号轨）③默认名兜底逐字；`ResolvedDevice` 加 `sessionAuthenticated`；marker 状态词双轨（已登记/已认证/未登记，作者裁定②） |
| A4 标签词源 | 同上 | `accountLabel`：name → email 本地段 → 「门户账号」；sub/完整 email 不进标记（作者裁定①显示词随门户显示名，现值 Mashiro） |
| A6 后缀剥离 | 同上 | `stripIsoNameSuffix`（与前端 `deviceRoster.js` 同词表）进 resolve 显示面；身份/配对面保原值 |

### 方案 B 三小刀（commit 74ce057ef）
| 刀 | 文件 | 说明 |
|---|---|---|
| B① 双 host 探测 | `web/js/ws.js` 环回近路腿 | 探测双试 `127.0.0.1` → `localhost`（Safari PNA/混合内容头号嫌疑）；命中哪个 host 跳哪个（不回退硬编码）；nearpath.js 的 LAN 腿既有双候选，零改动 |
| B② miss 诊断曝光 | `ws.js` + `gateway/HealthRoutes.scala` + `gateway/NeblinkRoutes.scala` | 判定链每环 miss 原因写 sessionStorage 诊断键 + console.info；引擎侧两行日志：`nearpath: loopback-info served`（relay 腿页面走到近路第 2 步）+ `nearpath probe miss: enrolled=false bound=…`（bound 空 = roster 读不可用 B2 特征形态 / 非空 = 不在册，可分辨） |
| B③ 指纹提示 | `gateway/StaticRoutes.scala` + `web/js/staleTabHint.js`（新）+ `web/js/main.js` | `webFingerprint` 惰性单值（当前 static tree 规范 index 源 SHA-256 前 16 hex）→ brand 注入同点注入 `window.__WEB_FINGERPRINT__` + serverConfig 帧加法字段下行 → 页内比对不等 ⇒ 顶栏「检测到控制台有新版本」+ 刷新钮（治 B1 陈旧标签页续命）；任一缺席静默零假阳 |

### 明确不做（作者裁定 + 设计边界）
- 隧道闸豁免/降权（裁定③；per-IP cap 问题随包 F3 cap=4096 另治）
- link-server 侧改动（LS 核心零改动；mint 帧 `account_label` 可选项不做——A4-P0 本地标签已可答）
- stand-website 仓 0.5d 项（近路 WEB 侧对齐 + 设置面板只读行）——见 §4 遗留

## 3. 测试证据

**scala spec（sbt Test/testOnly，103/103 绿）**
- bridge 三件套 33/33：`DeviceProvenanceSpec`（三级红绿：tier1 登记名优先 + 不冒已登记；tier2 owner 等值形态 + 非 owner 不升 + 经官网/本机票括注；tier3 逐字兜底 + 锚缺席不升；owner 三态；双轨状态词；accountLabel 词表；stripIsoNameSuffix；词表对齐 GatewaySessionStore）+ `OwnerIdentitySpec`（解码面 sub 在场/缺席/坏 token + 标签链 + token 键控缓存 + 真实凭据文件形态端到端）+ `DeviceProvenanceInjectionGateSpec`（A1a-A1e 五断言钉穿线点，flatMapN 修正后复跑绿）
- 网关回归子集 67/67：`BrandInjectionSpec` / `IndexWithBrandServeSpec` / `ConsoleTicketRoutesSpec` / `GatewayAuthSpec` / `ConsoleTakeoverSpec` / `ConsoleWsLifecycleSpec` / `ConsoleFastpathSpec`
- `StaticRoutesFingerprintSpec`（新）3/3：指纹纯函数面（确定性/字节敏感/SHA-256 前缀）+ webFingerprint 非空惰性 + 注入形态钉死

**前端 spec（node playwright）**
- `tests/nearpath-dualhost.spec.mjs`（新）9/9：T1 首 host 拒答 ⇒ localhost 腿赢并跳 localhost；T2/T3/T4 miss 诊断键逐环可读（probe_unreachable / device_mismatch (127.0.0.1) / mint_http_500）
- 既有回归：`loopback-nearpath.spec.mjs` PASS（含 #1013/#1020 心跳传输遮蔽 T4 全腿）、`device-provenance.spec.mjs` / `session-rail.spec.mjs` / `console-takeover.spec.mjs` PASS；checkJs 259 < 基线 260 零新增

**隔离引擎 + 真浏览器 e2e**（`tests/portal-identity-e2e-isolated.mjs`）
- 拓扑：隔离引擎 :8687（home=/tmp/nb-portal-identity/home，锻造 id_token 凭据）+ 假中继 :18999（进程内 WS 服务，收引擎拨入、下发 ConsoleTicketMint、收 ConsoleTicketResult 取票——跳②门户铸票的引擎侧对端伪造面）
- **e2e run10 全绿 10/10**（exit 0）：隔离引擎 :8687 启动可达（在产 6138 零接触）✓；伪造铸票 owner/非 owner 票各 43 字符 ✓；**A-owner 判词** `Device provenance: 'Mashiro · Safari（经官网）' (enrolled=false, authenticated=true)` ✓；**A-other 判词** `Device provenance: '未登记-Mac Safari' (enrolled=false, authenticated=false)`（非 owner 不升已认证）✓；B①② 近路 miss 诊断键 `portal_status_http_404` 可读 ✓；B③ 指纹不匹配 ⇒ 顶栏提示条出现 + 文案「检测到控制台有新版本——本页是旧版标签页，部分功能未启用。刷新页面」✓；引擎侧兑换日志 ✓（`console ticket redeemed` 携 `accountSub=sub-real-owner-e2e`，owner 等值对账闭环）✓。
- 截图：`.nebflow/evidence/20261028_portal-identity-passthrough/A1-owner-authenticated-console.png`（owner 会话控制台 + 探针消息）、`A2-other-sub-default-console.png`（非 owner 会话同现场）、`B3-stale-tab-hint.png`（顶栏「检测到控制台有新版本…[刷新页面]」提示条）。

### 3.1 取证副产物：两处孤儿 IO 日志缺陷（本批修正）

e2e 判词调试（run8 零兑换日志之谜）的最小探针（`fetch /?ticket=` 直探：303 + Set-Cookie 正常、日志零行）定位到 **cats-effect 孤儿 IO**——裸 `logger.info(...)` 构造了 IO 描述但没绑进链，永不执行：

1. `ConsoleTicketRoutes.scala:95`（**存量缺陷**，最后触及 5c7d44238/e12668813，非本批引入）：兑换 Right 路径的 `console ticket redeemed` 行从未发射过——兑换本身正常（303 + cookie），纯观测洞。修法 `logger.info(...) *> IO.pure(...)`。
2. `WebSocketRoutes.scala` provenance 判词行（本批 28a0df6bf 引入，同款坑）：`(deviceRosterEntries, anchorIO).mapN { ... logger.info(...); Some(resolved) }` 纯函数块里裸 IO。修法 `mapN` → `flatMapN` + `.as(Some(resolved))`。源契约门 A1a-A1e 断言不受影响（33/33 复跑绿）。

同机姿态观察（run9 实证，正确行为非缺陷）：同机直连浏览器经 `/api/neblink/status` 花名册读到 self ⇒ 自动绑定 `boundDeviceId` ⇒ tier1 机器名（`Mashiros-MacBook-Pro`，enrolled=true）。真门户远端设备（手机经隧道）无此直连面 ⇒ tier2/tier3 接管。e2e 的 A 腿按远端姿态拦截花名册读（404）以落到账号轨判词。

## 4. 遗留清单

1. **WEB 仓 0.5d 项**（设计 §7 #9，不在本批）：`stand-website` 的 `lib/portal-nearpath.ts` 双 host 探测对齐（现只有 `localhost` 腿）+ miss reason 设置面板只读行。ST 页内腿已双 host，入口腿在 WEB 仓。
2. **A4-P2 可选**：LS mint 帧补 `account_label`（仅当本地 name 标签不合意时立项；现值 Mashiro 与裁定①一致，暂无必要）。
3. **B1 全量收口依赖一次刷新**：指纹提示只对「至少跑过本批构建」的标签页生效——作者的当前陈旧标签页需手动刷新一次，此后跨重启续命即被提示面接住（机制性鸡生蛋，设计已知）。
4. **隧道闸 per-IP cap**（裁定③另治）：风暴窗内 roster 读仍会 429；A1-A3 已让标记不再依赖该读点（boundDeviceId 仅剩「已登记显示名」增强意义）。
5. **近路双 host 的 Safari 实测定因**（裁定⑤）：本批带隔离引擎 e2e 双 host 命中/miss 诊断验证；作者 Safari 真机一次实测（miss reason 读数回贴）即可定 127.0.0.1/localhost 何者为 Safari 病腿。

## 5. 关键假设（照实申报）

- 任务简报写「deviceIdentity.js 补 127.0.0.1 腿」；设计稿（权威）钉的是 `ws.js` 页内腿 + `nearpath.js`。实况：`deviceIdentity.js` 无探测面，`nearpath.js` 已双候选，唯一单 host 点 = `ws.js` 环回腿 ⇒ 小刀落在 `ws.js`（与设计稿一致）。
- e2e 的「伪造门户铸票」= 假中继下发 `ConsoleTicketMint` 帧（wire JSON 帧形，proto=1），票经引擎真核销链（binding gate 默认 bound、预算、`ConsoleTicketResult` 回票）→ `?ticket=` 真兑换 → relay-mint 会话。link-server 本体零参与（LS 核心零改动设计边界内）。
- 标记词只进模型面（两 face 分离原律），e2e 的机器可读判据 = 引擎侧 `Device provenance:` 日志行 + 兑换日志 accountSub 对账；截图为会话现场上下文。
