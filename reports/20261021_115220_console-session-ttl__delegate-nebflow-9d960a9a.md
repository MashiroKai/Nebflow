# 控制台登录态过期时间（session TTL 批）— 现状全链读数 + 「安全前提下尽量长」落地

2026-10-21 · 执行 delegate `delegate-nebflow-9d960a9a` · 引擎侧改动候重启窗生效

## 一、现状全链（三层读数）

### 第 1 层：引擎 gateway 会话 cookie（本仓，改动面）

| 面 | 现值（改前） | 代码锚点 |
|---|---|---|
| cookie | `<brand>_session`，HttpOnly + SameSite=Lax + host-only + Path=/ + `Max-Age=43200`（12h），`Secure` 按消费源（#522：plain-http 直连腿不带） | `GatewaySessionStore.newCookie` (src/main/scala/nebflow/gateway/GatewaySessionStore.scala:208) |
| 服务端绝对上限 | **12h**（43200s），永不可续 | `AbsoluteTtlSeconds` (GatewaySessionStore.scala:175) |
| 服务端滑动闲置窗 | **2h**（7200s），每个过鉴权的请求 `verifyAndTouch` 续窗，封顶绝对上限；寿命 = min(绝对, 滑动) | `IdleTimeoutSeconds` + `verifyAndTouch` (GatewaySessionStore.scala:113/178) |
| 签发面 | `GET /?ticket=` 一次性票兑换 → 303 + Set-Cookie，**仅此一处签发**（值终身不变） | `ConsoleTicketRoutes.scala:102` |
| 签发后 | 改前**从不重签**：浏览器 jar 的 12h Max-Age 是固定悬崖（活跃用户也在 12h 被踢） | — |
| 存储形态 | 纯内存 Ref（**无持久化**，既定权衡）：引擎重启 = 全会话清零 | GatewaySessionStore 类注释 |
| 过期后跳哪 | 前端 `sessionGate.js` 统一收口：首个 401/403 ⇒ 可见过期横幅 ⇒ 1.5s reload ⇒ 本地源落未登录卡（`ws.js showTicketEntry`）；relay 源走 console_shell 登录腿静默重铸 | src/main/resources/web/js/sessionGate.js |
| 兜底安全面（已在 main，本批复用不动） | ① AuthAdmin 面（铸票/换 token/吊销会话）**仅文件 token Bearer**——会话 cookie 永远够不到（R1-1）；② relay-mint 会话绑定 networkId，绑定吊销即刻 `revokeRelayMinted`（v3 :184「无残留窗」）；③ local-ticket 会话绑定真实环回腿（防搬运重放） | `GatewayAuth.Rails.BearerFileOnly`、`NeblinkRelayTunnel` 绑定死信号、`localTicketOnLeg` |

### 第 2 层：relay（link-server，独立仓，不在本仓改动面）

- 浏览器有效凭据 = `neblink_session` **15min** access cookie，无滑动（`ACCESS_TOKEN_TTL_SECS=900`）；
- refresh cookie **30d** 在位但前端零调用 → 死窗 15min（#520 取证批根因）；
- relay 侧根修已提交（link-server 分支 `fix/ingress-sliding-refresh` commit `6782d3b`，**待 VPS 重部署**）：`require_user` 对「access 过期 + refresh 行存活」滑动准入 ⇒ relay 死窗从 15min 退到 30d。证据：`evidence/20261019_001800_imgdoor520-forensics.md`。

### 第 3 层：Logto SSO 会话（auth.mystand.space 租户）

- 租户读数：OIDC discovery `issuer = https://auth.mystand.space/oidc`（auth.nebflow.space 为同租户别名前置，2026-10-21 实测）；
- 活探针：`_interaction` / `_interaction_resume` cookie = **1h**；SSO `_session` cookie 仅成功登录后签发，无凭据不可外读；
- 在仓权威读数：#520 取证批现役记录 = **Logto `_session` 30d**（sessionGate.js:9「30d refresh cookie / Logto 30d `_session`」，与 relay refresh cookie 同长）；
- 租户配置（sign-in session TTL）在 Logto 控制台，本仓无 Management API 凭据，不可改、只可荐。

## 二、改动（现值 → 新值）

| 值 | 现值 | 新值 | 面 |
|---|---|---|---|
| 引擎会话绝对上限 | 12h（43200s） | **30d（2592000s）** | `GatewaySessionStore.AbsoluteTtlSeconds` |
| 引擎滑动闲置窗 | 2h（7200s） | **7d（604800s）** | `GatewaySessionStore.IdleTimeoutSeconds` |
| cookie Max-Age | 43200（12h，签发一次定死） | **2592000（30d）+ 活跃重签**（见下） | `newCookie` + 新签发点 |
| 浏览器 cookie 重签点 | 无（仅兑换 303 一次） | **`GET /api/nf-authcheck` 204 会话轨命中即重签**（同 sid、全新 Max-Age、冻结属性集、#522 secure 按消费源重算；Bearer 轨不签） | `NfFileRoutes.nfAuthcheckRoutes`（A15-slide 钉） |
| 前端保活腿 | 无 | **入座证实后武装 12h 一拍 authcheck 保活**：204 拍=服务端滑动窗+浏览器 Max-Age 双续；403 拍=既有 noteAuthRejected 自愈腿；网络错拍=静默跳过；未入座页面永不武装（不误报横幅） | `sessionGate.js armSessionKeepalive` + `ws.js` 入座点接入 |

行为不变式（逐条不动）：滑动续期机制本体（`verifyAndTouch` 单事务续窗）、过期即删、三种吊销面（masked-id / all / relay-mint-only）、AuthAdmin 仅 Bearer、绑定吊销即时性、#522 secure 按源、cookie 值终身不变。

## 三、为什么是 30d + 7d（安全论证）

1. **30d 不是拍脑袋，是全链锚点对齐。** 链上另两个长锚——Logto SSO `_session` 与 relay refresh cookie——都是 30d。引擎 cookie 超过 30d 买不到任何可用性（到 30d 链上层本来就要求重新走一次登录），只单方面拉长被窃 cookie 的暴露窗。三层同帽 30d = 语义一致：「最坏每月重新登录一次，开着就常驻」。
2. **实际寿命的真实上界是引擎重启节奏，不是 TTL。** 会话存储是既定的纯内存形态（无持久化=缩小被窃凭据面，plan §2.6 注册权衡）：每次引擎重启全会话清零，冷启动门户回投链在 Logto 会话存活时静默重座。TTL 抬得再高也越不过这条线——所以取「与全链锚点一致」而不是无限长。
3. **7d 闲置窗是零 UX 成本的纵深防御。** 活跃即续（每拍 12h，活跃页面 48 拍裕量）⇒ 正常使用永远摸不到闲置踢出；而「安静死掉的会话」不能被复活——7d 无活动后 store 行已过期即删、浏览器 jar 也已丢，后来翻出旧 cookie 的人拿不到任何面。
4. **被窃 cookie 的最坏情形有界且可即时收口。** 30d 内最坏 = 攻击者拿到交互式只读+控制台操作面；但 (a) cookie HttpOnly+Secure(按源)+SameSite=Lax+host-only，偷取面本就是浏览器漏洞/物理接触级；(b) 会话 id 不是授权根——铸票/换 token/吊销会话的 AuthAdmin 面只认本机文件 token（R1-1），会话 cookie 无法自我续命、无法造新凭据；(c) 绑定吊销一键 `revokeRelayMinted` 即刻清场，CLI `sessions revoke` 随时可全清；(d) local-ticket 会话只活在本机环回腿。对照：文件 token 与 Logto PAT 本来就是长期凭据，30d 滑动会话没有抬高信任档位。
5. **重签面不放大攻击面。** 重签只把**已验证的同一个值**发还给**刚通过该值鉴权的同源客户端**，secure 按消费腿重算（#522 表达式逐字复用）；攻击者能让服务端重签的前提是他已经持有活会话——此时 jar 写入对他无意义（直接重放值即可）。吊销即时性不受影响：store 行没了，重签 403 不发生，jar 里的死值再也过不了闸。

## 四、SSO 层建议（作者侧可选项，本仓不可改）

Logto 控制台（auth.mystand.space 租户）sign-in session TTL 若抬到 90d：relay 源静默重登窗从 30d 拉长到 90d，代价 = 被劫持的浏览器在 90d 内可静默重座。**建议维持 30d**：与本批引擎 30d、relay refresh 30d 对齐，语义统一；90d 的增益只覆盖「refresh 行死了但 SSO 还活着」的边角，而该边角的重登摩擦本就极低（一次点击）。

## 五、验收证据

- `GatewaySessionStoreSpec` 10/10 绿（含新钉：`AbsoluteTtlSeconds==2592000`、`IdleTimeoutSeconds==604800`、Max-Age=2592000 逐字断言）；
- `NfTicketRoutesSpec` 19/19 绿（含新增 **A15-slide**：会话轨 204 重签同 sid + Max-Age=30d + 冻结属性 + plain-http 无 Secure/https 有 Secure；Bearer 轨零 Set-Cookie）；
- `ConsoleTicketRoutesSpec` / `GatewayAuthSpec` / `LocalAccelBudgetSpec` 相邻面回归 **33/33 绿**（含 Max-Age 钉改 `AbsoluteTtlSeconds` 常量引用、本地加速预算 p95=5.23ms<50ms 不受影响）；
- `tests/session-gate.spec.mjs` 15/15 绿（新增 ⑤ 保活腿 5 断言：12h 拍、幂等武装、204 零动作、网络错静默、403 走自愈）；
- checkJs：触文件（sessionGate.js/ws.js）**零新错**（全量 277 与 pristine HEAD 逐字同值，HEAD 既有基线漂移非本批引入）；
- `tests/session-rail.spec.mjs`（需活实例的 e2e）：Max-Age 钉已更新 43200→2592000 + 增 authcheck 重签断言，**待部署窗跑**。

## 六、遗留 / 注记

- **引擎侧改动候重启窗生效**（照旧注记）；前端保活腿随页加载即生效。
- relay 侧滑动准入（commit `6782d3b`）仍待 VPS 重部署——在那之前 relay 源的死窗仍是 15min+横幅自愈；与本批正交但叠加后链路才完整。
- 引擎重启 = 会话清零（既定权衡未动）；relay 源由门户回投静默重座兜住，本地源需重兑一次票。若未来要重启存活，备选形态=落盘「哈希后的 sid」+0600 权限，另批论证。
- Logto 租户 TTL 维持 30d 的建议见 §四。
