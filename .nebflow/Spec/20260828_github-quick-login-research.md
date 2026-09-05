# 调研 · GitHub 快捷登录路径——技术边界文档（任务 #444）

**版本**：v1.0 · **日期**：2026-08-28 · **作者**：Backend（调研产出，无实施）
**状态：调研完毕 · 实施清单待作者批准后转 nebflow-website（官网侧）+ VPS 运维窗口（Logto 侧）**
**背景**：作者定位 GitHub=hub 绑定用途+快捷注册/登录（「一般软件都有快速注册和快速登录的路径」）。登录主体保持邮箱+密码 nebflow 账号（Logto 自托管 auth.neblink.space）。GitHub connector 已配（P1 绑定链在用，08-28 真人验收 PASS）。客户端 beta.53 PKCE 走 hosted sign-in 页。
**调研方法**：部署实例 Management API 只读取证（connectors / sign-in-exp 实际配置）+ Logto v1.42.0 core 源码行为验证（social-verification.ts / social.ts / direct sign-in 官方文档）。全程只读，未动任何配置。

---

## 0 · 部署现状取证（2026-08-28 Management API 实测）

| 项 | 现状 | 意义 |
|---|---|---|
| GitHub connector | ✅ 在位：`target=github`，connector id `k06osgoft4hy`，config={clientId, clientSecret}；`enableTokenStorage=false`（P1 隐私面裁定保持）、`syncProfile=false` | 快捷登录的连接器零新建 |
| `signIn.methods` | `[{email+password, isPasswordPrimary:true}, {username+password}]`——**无 social 项** | 登录主体=邮箱+密码（符合作者定位） |
| `socialSignInConnectorTargets` | **`[]`（空）** | **hosted 页无 GitHub 按钮的直接原因**——不是 connector 缺失，是 sign-in experience 开关未开 |
| `signInMode` / `signUp` | `SignInAndRegister` / `identifiers:["username"]`、`password:true`、`verify:false` | 新用户注册需补 username（见 §2/§5 交互影响） |
| 部署版本 | svhd/logto:latest（2026-08 拉取，schema 与 v1.42 一致——socialSignInConnectorTargets/one-time-token 等新 schema 均在） | 源码行为验证以 v1.42.0 tag 为准 |

---

## 1 · Hosted 页 GitHub 入口：现状与启用路径

**结论：一个配置开关，客户端侧零代码成立。**

- **现状**：beta.53 客户端 PKCE 打开的 hosted sign-in 页没有 GitHub 按钮，根因= `socialSignInConnectorTargets: []`——hosted 页按此列表渲染社交按钮，列表为空即不渲染。**不是 connector 问题**。
- **启用路径**：把 `"github"` 加入 `socialSignInConnectorTargets`（Console UI：Console > Sign-in experience > Social sign-in 勾选 GitHub；或 Management API `PATCH /api/sign-in-exp`）。启用后：
  - 客户端 PKCE hosted 页**自动出现「Continue with GitHub」按钮**——hosted 页完全由租户配置驱动，客户端的 authorize URL、回调处理、token 交换全部不变（identity source 与 OIDC 协议层解耦）。**客户端零代码 ✅**（预期「是」由配置语义+Logto hosted 页渲染模型双重确认）。
  - `direct_sign_in=social:github` 参数同时被激活（见 §3——Logto 文档明示 direct sign-in 要求 connector「found or enabled」，未启用时优雅回退标准 hosted 页）。
- **连带注意**：`/api/sign-in-exp` 同端点承载品牌化资产（Phase-1/2 的 customCss/color 等，08-27 已落地）——实施时若走 API PATCH，只提交社交两键，PATCH 后回归验证品牌化 8 项渲染断言（复用 nb-logto 品牌化验证脚本）；**优先 Console UI 勾选**（ Console 提交路径对品牌字段天然保真）。

## 2 · 同邮箱账号合并策略

**结论：Logto v1.42 默认不按邮箱自动合并（源码实锤），且无配置项可开自动合并；合并的正确路径=既有 P1 手动绑定。这是安全上正确的默认。**

源码级行为（v1.42.0 `social-verification.ts` + `libraries/social.ts`）：

1. **社交登录的账号识别只看社交身份**：`identifyUser()` 用 `(target=github, 社交 uid)` 查用户。命中→登录；**未命中→抛 `user.identity_not_exist`（404）**，并附 `findSocialRelatedUser()` 的结果作提示——该函数按社交资料的 phone→email 顺序查找**同邮箱/同手机号的既有用户**，但**只用于提示（relatedUser payload），绝不自动合并**（无任何 merge 分支）。
2. **新用户注册的邮箱同步也有冲突保护**：`toSyncedProfile(isNewUser=true)` 同步 GitHub email 到新账号前先查 `hasUserWithEmail`——**邮箱已被其他用户占用就不同步**（宁缺勿撞），不存在借邮箱注册吞并既有账号的路径。
3. **合并路径选项**：
   - **A. 维持 P1 手动绑定（推荐，已在用）**：用户先登录自己的 nebflow 账号 → 账号中心绑定 GitHub（08-28 真人验收 PASS 的链路）。合并=社交身份挂到既有账号，一次绑定永久生效，之后 GitHub 按钮登录直接命中该账号（social identity 已在）。**零新代码、零接管面扩大**。
   - B. 官网 BYO-UI 做「登录后关联」引导（增强，可选）：social 404 (`identity_not_exist`) 且带 relatedUser 提示时，官网提示「该邮箱已有账号，请用密码登录后到设置关联」——把 P1 绑定路径的前置引导自动化。属官网侧体验增强，非必需。
   - ~~C. 邮箱自动合并~~：**v1.42 无此配置**（调研确认），即便未来版本提供也应默认拒绝——GitHub 邮箱可被用户在 GitHub 侧改绑，自动合并=账号接管面（email reassignment 攻击），与「安全第一」底线冲突。
4. **风控语义明确**：GitHub 侧邮箱虽经 GitHub 验证，但邮箱归属可变；「验证过的邮箱」≠「身份证明」。Logto 的设计（社交身份唯一键=connector+uid）与我们 P1 绑定语义一致。

## 3 · 官网自绘页（BYO-UI /login /register）加 GitHub 快捷入口的技术路径

**结论：一个 URL 参数，官网侧改动极小。** Logto 官方机制 **Direct sign-in**（docs.logto.io/end-user-flows/authentication-parameters/direct-sign-in）：

- **调用方式**：官网现有 authorize URL 原样保留（client_id/redirect_uri/scope/PKCE...），**追加 `direct_sign_in=social:github`**（idp-name=connector target=`github`）→ Logto 跳过 hosted 页直连 GitHub 授权页 → GitHub 回调 Logto → Logto 走既有 redirect_uri 带回 code。官网回调处理 **零改动**（同一 code exchange 链）。
- **SDK**：Logto SDK 为 `logtoClient.signIn({ redirectUri, directSignIn: 'social:github' })`；官网若手拼 URL 则直接加 query 参数（URL 编码）。
- **优雅降级**：connector 未启用/参数错误 → Logto 回落标准 hosted sign-in 页（文档明示 fallback 语义），不会 4xx 卡死用户。
- **`/login`（登录）**：加「Continue with GitHub」钮 → authorize+direct_sign_in → 已绑定用户直达登录成功；未绑定但同邮箱 → `identity_not_exist` 404 → hosted 页展示错误提示（见 §2，可后续在官网侧做 B 选项引导）。
- **`/register`（注册）**：同参数即可快捷注册——但受现 signUp 配置影响：**首次 GitHub 用户会被 hosted 页要求补 username**（`signUp.identifiers=["username"]`，无邮箱验证因 verify=false）。「快捷注册」体验取舍见 §5 清单第 4 条（作者裁定项）。
- **给 nebflow-website 的实施级输入（可直接开工判定见 §6）**：
  1. 前置条件：§5 清单第 1 条（Logto 侧开 socialSignInConnectorTargets）先落地——否则按钮点了只会回退 hosted 页（能用但不「直连」）。
  2. `/login` 页与 `/register` 页各加一个社交按钮组件（复用 GitHub logo 素材），点击=现有 authorize 构造函数+`direct_sign_in=social:github` 参数。
  3. 回调页零改动；错误分支（用户在 GitHub 侧取消授权）沿用既有 error 处理（回调带 error 参数的路径官网已处理过 device flow/登录链）。
  4. 验收脚本建议：新增 e2e 用例断言 authorize URL 含 `direct_sign_in=social%3Agithub`；真实 GitHub 往返属人工验收（需 GitHub 账号）。

## 4 · prompt=consent / offline_access 交互

**结论：社交链与 PKCE 主链共享同一 authorize 语义，consent 屏与 refresh token 行为完全一致，无新裁定需求。**

- `direct_sign_in` 与 `prompt` 正交：GitHub 链的完整屏幕序列 = **GitHub 自己的授权页**（仅首次授权时出现，GitHub 记住 grant）→ 回到 Logto → **Logto 的 consent 屏**（因 `prompt=consent`，每次都出现——R7 修复合入后的既有语义，作者已知情接受）→ redirect 回客户端/官网。
- **offline_access / refresh token 语义不变**：refresh token 的发放条件在 Logto 层（scope 含 offline_access + prompt=consent，qa R7 五对照实验实锤），与用户用哪种身份来源完成认证无关。GitHub 登录的用户与密码登录的用户拿到的 token 形态一致。
- **注意一个小体验点**：`prompt=consent` 每次多一道同意屏对社交链同样生效——快捷登录的「快捷」体现在身份验证段（GitHub 一次授权后近乎无感），consent 屏是既有裁定的全局成本，不因本功能新增。

## 5 · VPS 配置动作清单（实施阶段逐条执行）+ 风险表

> 铁律重申：本节只有动作，无凭据。所有凭据操作在 VPS/Console 内进行。

| # | 动作 | 端点/位置 | 风险 | 缓解 |
|---|---|---|---|---|
| 1 | **启用 hosted 页 GitHub 按钮**：`socialSignInConnectorTargets += "github"`（推荐 Console UI 勾选） | Console > Sign-in experience（或 PATCH /api/sign-in-exp 只提交社交键） | 同端点承载品牌化 customCss——API PATCH 有覆盖风 | 优先 Console UI；若 API，PATCH 后回归品牌化 8 项渲染断言（nb-logto 脚本可复用） |
| 2 | **新建 GitHub OAuth App（登录专用）**，callback=`https://auth.neblink.space/callback/k06osgoft4hy` | github.com/settings/developers（作者手动） | **GitHub OAuth App 单回调限制**——不能复用 P1 绑定 App（其 callback=neblink.space/settings/github-callback 全透传） | 新建独立 App，与绑定 App 互不影响；与记忆中「绑定时需换回调或转 GitHub App」属同一约束家族，本条先行落地登录 App |
| 3 | **更新 connector 凭据**：把 #2 新 App 的 clientId/clientSecret 填入 GitHub connector | Console > Connectors > GitHub（或 PATCH connector config） | 填错 callback → GitHub 报 redirect_uri_mismatch（用户可见） | 实施后立即跑一次真实登录往返验证 |
| 4 | ~~（作者裁定项）快捷注册取舍~~ **已裁定+已实施验证（2026-08-28 晚）：作者拍板 A 接受现状**——实施实测部署版 Logto `signUp.identifiers` 枚举仅 username/email/phone（PATCH 400 `invalid_enum_value` 实证），social-only 免填注册**搁置**；新用户 GitHub 快捷注册=**补填 username 形态**（仍免密码一键，账号字段完整）。远期有需求再议升级镜像。官网联调验收按补填形态口径 | Console > Sign-in experience > Sign-up | 加=首次 GitHub 用户**免填 username** 秒注册；但账号会无 username（官网 Management API 直建路径不受影响，直建时始终带 username）。不加=注册多一步填 username（现状语义，账号字段完整性更好） | ~~建议先不加~~ **作者裁定=接受现状，与原建议一致**；本条完全可逆 |
| 5 | （可选）`syncProfile` 开关：GitHub 昵称/头像同步 | connector 设置 | 每次登录覆盖本地 name/avatar（用户在官网改的头像被 GitHub 覆盖） | 默认 false 保持；头像我们已有自有通道（P0 avatar 全链） |
| 6 | （可选）官网侧「登录后关联」引导（§2 选项 B） | nebflow-website | 无 | P2 |
| — | ~~邮箱自动合并~~ | — | **无此配置（v1.42）**；即便有也不开（§2 风险） | 维持 P1 手动绑定为唯一合并路径 |

## 6 · 官网侧可开工判定

**可以开工，但有顺序依赖**：官网代码改动（§3 四点）随时可写；**真实链路验收依赖 §5 #1-3 落地**（Logto 开关+新 OAuth App+凭据接线）。建议派发顺序：①本清单 #1-#3 一次 VPS 窗口做完（约 15 分钟，含验证往返）→ ②官网按钮实现（纯前端+URL 参数，半天内含测试）→ ③作者真人验收（GitHub 账号真实往返）。

---

## 附：证据索引

- 部署配置：GET /api/connectors、GET /api/sign-in-exp（2026-08-28 只读取证，本文 §0 表）
- 源码行为：logto-io/logto @ v1.42.0——`packages/core/src/routes/experience/classes/verifications/social-verification.ts`（identifyUser :180 / toSyncedProfile :238 / findRelatedUserBySocialIdentity :341）、`packages/core/src/libraries/social.ts`（findSocialRelatedUser :161）
- Direct sign-in：docs.logto.io/end-user-flows/authentication-parameters/direct-sign-in（参数语法+回退语义+SDK 形态）
- 既有链路引用：P1 GitHub 绑定（08-28 真人验收 PASS）、PKCE R7 修复（prompt=consent，a91335f5）、注册直通通道（username 必带教训）

*版本日志：v1.0（2026-08-28）初稿——五块结论+实施清单+可开工判定。v1.1（2026-08-28 晚）实施收官注记——#1/#3/#5 已生效（品牌化逐字节零变化），#4 作者拍板 A 接受现状：部署版 Logto 枚举不支持 social（PATCH 400 实证），免填注册搁置、按补填 username 形态验收。*
