# 官网账号体系四体验缺口：现状 + 方案 + 改动清单（只读调研）

> 2026-09-02 · Explorer 只读调研（**未改任何代码/配置**，未调 Management API 写操作）
> 证据源：nebflow-website 仓库代码（`~/.nebflow/projects/nebflow-website`）+ Logto **v1.42.0** 源码（GitHub tag v1.42.0，commit `3a8f4a6e`，与 VPS 部署镜像 label 一致）+ 线上公开端点探测（`GET /api/.well-known/sign-in-exp`）+ 历史实测文档（logto-account-api.md 等）
> 作者 2026-09-02 23:24 反馈四缺口：①注册不验邮箱 ②无忘记密码 ③社交注册账号无密码且无引导补设 ④改用户名还要输密码

---

## 0. 结论摘要

| # | 缺口 | 根因（一句话） | 推荐方案 | 依赖 email connector（SMTP 发信） | 纯代码可落 |
|---|------|--------------|---------|:---:|:---:|
| ④ | 改用户名要输密码 | Logto **硬性**要求 `identityVerified`（源码 assertThat，无 session 直通），且 verification record **不能**从已登录 session 免密创建 | 会话内验证缓存：一次密码验证后 10min 内复用 record，不再重复输入 | 否（纯代码） | ✅ |
| ③ | 社交账号无密码、无引导 | 社交 direct_sign_in 建号只落 email，无密码；官网无检测、无入口 | M2M 代设密码（Management API `PATCH /api/users/:id/password`）+ Settings banner 引导 | **否**（A 方案）/ 是（B 方案原生路径） | ✅（A） |
| ① | 注册不验邮箱 | MGMT 直通建号 + v1.42 **无 emailVerified 落库字段**，验证只在交互期有意义 | 注册链路切 Experience API Register 流程（验证码→建号→直接登录） | **是** | ❌ |
| ② | 无忘记密码 | 租户 `forgotPassword:{email:false}` 关闭 + 无 email connector，发码链路整体缺失 | 托管页方案：加 email connector + 开 `forgotPassword.email` + /login 加入口链接 | **是** | ❌ |

**共同前提**：①②（以及④③的邮箱验证码变体）全部依赖 Logto **email connector**。凭据不只 SMTP 一种——Logto 官方 connector 有 **SMTP**、**SendGrid（HTTP API，只需 API key，无需 SMTP 服务器）**、Aliyun DM 三选一。「SMTP 凭据日后供」可放宽为「任一发信 connector 凭据」。

---

## 1. 现状链路（代码级）

### 1.1 注册（缺口①现场）

```
官网 /register 自绘表单（app/register/RegisterContent.tsx）
  → POST /api/auth/register（app/api/auth/register/route.ts）
      ├─ validateEmail/validatePassword/validateConfirm（lib/register-validation.ts：仅 min8+字母+数字）
      ├─ per-IP 限速 5/min（lib/rate-limit.ts）
      └─ createUser()（lib/logto-admin.ts:173-218）
           └─ Management API POST /api/users {primaryEmail, password(原文), username=deriveUsername(email)}
               ※ 邮箱不验证，直接建号（MVP 直通裁定；lib/logto-admin.ts:169-171 注释实证
                  「本 build 无 emailVerified 字段」）
  → 响应 {ok, redirect:"/api/auth/logto"} → 客户端跳托管 OIDC 登录完成会话（二次跳转）
```

要点：
- **无任何验证码环节**；建号即生效，邮箱写错也注册成功（与缺口②叠加 = 永久丢失找回途径）。
- 防枚举：422 email_already_in_use 在服务端 log 内部分流，对外统一文案（route.ts 注释，刻意偏离 Backend §5）。
- username 派生（deriveUsername，logto-admin.ts:144-155）：local-part 清洗 + sha256 base36 后缀 + 数字开头 `u_` 前缀。

### 1.2 登录（含社交）

```
邮箱/密码：/login 自绘（LoginContent.tsx）
  → POST /api/auth/signin（app/api/auth/signin/route.ts）
      └─ signIn()（lib/logto-signin.ts:153-214）：服务端 cookie-jar 复刻 Experience API
         PUT /api/experience → POST /api/experience/verification/password
         → POST /api/experience/identification → POST /api/experience/submit → resume 跳 code

社交：/login SocialButton → GET /api/auth/logto（direct_sign_in=social:github|google，
      lib/logto.ts:42-61）→ Logto 托管页 connector → callback（/api/auth/logto/callback）
```

要点：
- **社交注册的账号：只落 primaryEmail + 社交 identity，无密码、无密码设置引导**（缺口③现场）。`automaticAccountLinking:true`（09-02 DB 实证）——同邮箱社交身份自动并号。
- 登录方法现状（线上 sign-in-exp）：`email+password`（isPasswordPrimary）、`username+password`，`verificationCode:false`（邮箱验证码登录**未启用**）。

### 1.3 Settings 页（缺口③④现场）

`app/settings/SettingsContent.tsx`（BYO 页，浏览器持自有 opaque token 直连 Logto Account API）：

| 操作 | 代码位置 | 验证要求 |
|------|---------|---------|
| 改昵称 | `saveName()` :165-177 → `patchAccount(token,{name})` | 无 |
| 改头像 | :217-256 → neblink-server 上传 + `patchAccount({avatar})` | 无 |
| **改用户名** | `submitUsername()` :179-215 → `verifyPassword()` → `patchAccount({username}, verificationRecordId)`；弹层「验证已过期请重新输入密码」:560-609 | **密码 step-up** |
| **解绑社交** | `submitUnbind()` :284-312 → 同样 `verifyPassword()` | **密码 step-up** |
| 绑定社交 | `startSocialBind()` :261-282 → `POST /api/verifications/social` → OAuth 回环 | （绑定时 :194 注释：`linkIdentity` 也要 fresh step-up） |
| 改/设密码 | **不存在**（无此区块） | — |

`lib/account-api.ts`：
- `verifyPassword()` :146-161 → `POST {LOGTO_API_BASE}/api/verifications/password`（userRouter 族，TTL 10min，DB 落 record）。
- `AccountInfo` :61-74 已声明 `hasSecurityVerificationMethod`（注意：该字段 = 有密码**或** email **或** phone——**不是**「有密码」指示器，见 §2.4）。
- `LOGTO_API_BASE` 默认 `https://auth.neblink.space`（旧域，env 可覆写）。

**缺口④的放大器**：社交注册用户（无密码）在 Settings 里**改用户名、解绑社交都会卡死在密码弹层**——不是「别扭」，是对该人群完全不可用。作者嫌别扭的密码弹层，同时是缺口③人群的死路。

### 1.4 Logto 租户配置现状（2026-09-02 线上 `GET /api/.well-known/sign-in-exp` 实测）

| 配置 | 值 | 对四缺口的意义 |
|------|-----|--------------|
| `signUp` | `{identifiers:["username"], password:true, verify:false}` | 注册策略 username、不验证；`verify` 开关存在（切 email + verify:true 即原生验证注册） |
| `signIn.methods` | email+password / username+password，`verificationCode:false` | 验证码登录未启用 |
| `forgotPassword` | `{phone:false, email:false}` | **忘记密码功能整体关闭**（缺口②直接原因） |
| `socialSignIn` | `automaticAccountLinking:true` | 社交同邮箱自动并号 |
| `socialConnectors` | github(`k06osgoft4hy`)、google(`gvri3utjvjm3`) | 仅社交，**无 email connector**（09-01 audit：`/api/connectors` 仅 github-universal+google-universal） |
| `passwordPolicy` | min8、**characterTypes≥3**、拒 pwned/重复序列/用户信息 | 与官网注册校验（2 类字符）**不一致**，见 §2.8 |
| `googleOneTap` | 已配置（clientId+connectorId） | 托管页有 One Tap（自绘页没有） |
| account-center fields | `enabled=true`，name/avatar/profile/username/customData=Edit + social=Edit（08-28 绑定上线时放开）；**password/email/phone 未放开**（08-27 文档 + 推断，实施时用 `GET /api/account-center` 复核） | 缺口③需要 `fields.password=Edit` |

---

## 2. Logto v1.42.0 能力核实（源码级，逐问回答）

### 2.1 【缺口④之问】username 修改是否强制 verification record？——**是，硬性**

`packages/core/src/routes/account/index.ts` PATCH `/api/my-account`：

```ts
if (username !== undefined) {
  assertThat(identityVerified,
    new RequestError({ code: 'verification_record.permission_denied', status: 401 }));
  ...冲突检查/策略检查
}
```

`name/avatar` 的 assertion 在此**之前**且不查 `identityVerified`——同一接口里「昵称随便改、用户名必须复核」是 Logto 的刻意分级。08-27 真租户实测（logto-account-api.md §5）同样实证：无 header → 401。

### 2.2 【缺口④之问】verification record 能否从已登录 session 创建（免再输密码）？——**不能**

`middleware/koa-auth/koa-oidc-auth.ts`：`identityVerified` **只**由请求头 `logto-verification-id` 指向的 DB record 计算，**不存在**「session 已登录即视为已验证」的通道：

```ts
const isUserPermissionVerificationRecord = (record) => {
  if (!record.isVerified) return false;
  switch (record.type) {
    case VerificationType.Password: return true;
    case VerificationType.EmailVerificationCode:
    case VerificationType.PhoneVerificationCode:
      return record.templateType === TemplateType.UserPermissionValidation;
    default: return false;   // Social ❌ WebAuthn ❌ Totp ❌
  }
};
```

可产出「合格 record」的端点（认证态 `/api/verifications` 族，`routes/verification/index.ts`）：

| 端点 | 前提 | 是否合格 |
|------|------|:---:|
| `POST /api/verifications/password` | 知道密码 | ✅（现用） |
| `POST /api/verifications/verification-code`（identifier=email） | **email connector**；identifier==primaryEmail 时 templateType 默认 **UserPermissionValidation** | ✅ |
| `POST /api/verifications/social` / `web-authn/registration` | — | ❌ 不合格 |

**结论（如实说明）**：API 层面密码验证（或邮箱验证码）不可绕过。但 record **10min TTL 内可复用**——08-27 e2e 同一 record 连打三发 PATCH（422 冲突→200 成功→200 还原）实证。「会话内记住一次验证」是纯官网侧行为，Logto 侧 record 本来就在 TTL 内持续有效（§3.1 方案 A）。

### 2.3 【缺口③之问】Account API set password 能力与验证要求

`routes/account/index.ts` POST `/api/my-account/password {password}`：

```ts
const user = await findUserById(userId);
if (hasSecurityVerificationMethod(user)) {          // passwordEncrypted || primaryEmail || primaryPhone
  assertThat(identityVerified, ...401);             // ← 我们所有用户都有 primaryEmail → 必中
}
assertThat(fields.password === Edit, ...);          // account-center 需放开（当前未放开）
// PasswordValidator 按 passwordPolicy 校验（policy 含 pwned 在线检查）
```

`utils/has-security-verification-method.ts`：`passwordEncrypted || primaryEmail || primaryPhone`。
**即**：「无密码用户免验证设密码」的豁免分支在我们租户**不成立**（社交用户也有 email）。无密码用户唯一合格验证 = 邮箱验证码（依赖 connector）。

**同字段红利**：GET `/api/my-account` 的 `hasPassword` 字段（`get-scoped-profile.ts`）**仅在 account-center `fields.password` 为 ReadOnly/Edit 时回传**（08-27 实测响应里没有它，正是因为当时 password 未放开）——放开字段（③本来就要做）即免费获得前端「无密码账号」检测位。

### 2.4 【缺口③无 SMTP 备选】Management API 直接设密码——**存在且不校验验证/策略**

`routes/admin-user/basics.ts`：`PATCH /api/users/:userId/password {password}`（200/422）——只 `min(1)`，**无 identityVerified、无 passwordPolicy、无 pwned 检查**，直接 `buildUserPasswordPayloadFromPassword`（argon2id）。官网后端已持 M2M 凭据（`LOGTO_M2M_*`，注册链路在用）、session 里有 `userId=claims.sub`（lib/session.ts:45）——「已登录用户让官网代设密码」的信任链与现有注册端点同级。

### 2.5 【缺口①②之问】Experience API 原生 Register / ForgotPassword 序列（v1.42 实测路径）

**Register（邮箱验证码注册，`routes/experience/index.ts` + `verification-routes/verification-code.ts`）**：

```
PUT  /api/experience                      {interactionEvent:"Register"}
POST /api/experience/verification-code    {identifier:{type:"email",value}}
                                          → 发码（Register 模板）→ {verificationId}
                                          ※ 源码要求 email ∈ signUp.identifiers（当前是
                                            ["username"] → 需 PATCH sign-in-exp）
POST /api/experience/verification-code/verify {verificationId, code, identifier}
POST /api/experience/profile              {type:"username", value}   ← 可暂存派生用户名
PUT  /api/experience/profile/password     {password}                 ← 按 passwordPolicy 校验
POST /api/experience/identification       {verificationId}           ← createUser（201）
POST /api/experience/submit               → {redirectTo} → resume 换 code → 会话
```

与现有 `logto-signin.ts` 同构（服务端 cookie-jar 复刻），**注册+验证+登录一步完成**（现流程建号后还要再跳一次托管登录）。

**ForgotPassword（`index.ts` submit + `profile-routes.ts` :202-233）**：

```
PUT  /api/experience                        {interactionEvent:"ForgotPassword"}
POST /api/experience/verification-code      {identifier:{type:"email",value}}   ← ForgotPassword 模板
POST /api/experience/verification-code/verify {verificationId, code, identifier}
POST /api/experience/identification         {verificationId}
PUT  /api/experience/profile/password       {password}   ← setPasswordDigestWithValidation(·, true) 按 policy 校验
POST /api/experience/submit                 → 200 无 redirectTo（密码已落库、interaction 清除、不自动续登）
```

### 2.6 【缺口①硬事实】v1.42 无 emailVerified 落库字段

后端 08 实测（logto-admin.ts:169-171 注释：GET 无 key、PATCH 422、schema grep 空）+ 源码 schemas 复核一致。**Logto v1.42 没有「邮箱已验证」的持久状态**——验证语义 = 「该 email 只有走完验证码交互才可能成为账号 primaryEmail」。因此缺口①的正确解法是把建号挪进验证交互内（§3.3 方案 A），而非给用户记录打标。

### 2.7 托管页 forgot-password 开关

`sign-in-exp.forgotPassword:{email:boolean}` 即托管页「Forgot password?」链接 + 托管重置流的总开关；前提 = 存在 email connector（否则开不住）。托管页品牌化已完成（`customCss` 全量 nebflow 玻璃风 + `logto-signin-branding.md`），跳托管的视觉成本可控。

### 2.8 附带发现：密码策略三方不一致

| 位置 | 规则 |
|------|------|
| 官网注册校验（register-validation.ts:28-33） | min8 + **2 类字符**（字母+数字） |
| Logto passwordPolicy（线上实测） | min8 + **3 类字符** + pwned/重复序列/用户信息 |
| MGMT POST /users、PATCH /users/:id/password | **不校验**（注册链路实际生效的是官网 2 类） |

后果：现有用户密码可能不满足 Logto policy；一旦走 Account API 改密或 experience 设密（按 policy 校验）会被拒。**任何涉及设/改密码的新功能，官网校验应统一升到 3 类**。

---

## 3. 四缺口方案（选项 × 改动清单 × 验收点）

### 3.1 缺口④：改用户名要输密码

| 方案 | 内容 | SMTP | 评价 |
|------|------|:---:|------|
| **A 会话验证缓存（推荐先行）** | 一次密码验证成功后，把 `verificationRecordId + expiresAt` 存 `sessionStorage`；TTL(10min) 内再改用户名/解绑/设密码直接复用 record，不弹密码层。过期或 401 再弹 | 否 | Logto record 本就 TTL 内可复用（§2.2 实证）；纯前端；安全边界合理（敏感操作首次仍需密码） |
| B 邮箱验证码 step-up | step-up 弹层加「用邮箱验证码验证」分支：`POST /api/verifications/verification-code`（发到 primaryEmail，UserPermissionValidation）→ verify → 同一 header 走 PATCH | **是** | 对忘密码/无密码用户是唯一 API 级出路；依赖 connector，随 P1 批次 |
| C 维持现状 | — | 否 | 不推荐（社交用户完全卡死） |

**改动清单（方案 A）**：
1. `app/settings/SettingsContent.tsx`：新增 `getCachedVerification()/storeCachedVerification()`（sessionStorage，key 带用户 id + expiresAt 判断）；`submitUsername()`/`submitUnbind()`/设密码(③) 先查缓存，miss 才弹层；401 `verification_record.permission_denied` 时清缓存重弹。
2. `lib/account-api.ts`：`verifyPassword` 返回值已含 `expiresAt`，无需改协议；可选封装 `withStepUp(token, action)`。
3. `lib/auth-i18n.ts`：弹层文案改「为保护账号安全，请确认一次密码（本次登录内 10 分钟有效）」。

**验收点**：改用户名输一次密码后 10min 内解绑社交不再弹密码（`sessionStorage` 命中 + 200）；过期后再操作重新弹层且旧密码可过；直接删 sessionStorage 模拟新会话仍强制弹层。

### 3.2 缺口③：社交账号无密码、无引导补设

| 方案 | 内容 | SMTP | 评价 |
|------|------|:---:|------|
| **A M2M 代设密码 + banner（推荐先行）** | ①租户开 `fields.password=Edit`（顺带 GET 回传 `hasPassword`，见 §2.3）；②Settings 检测 `!hasPassword`（或 identities 非空且无密码）→ 显示 banner「你的账号通过 GitHub/Google 注册，尚未设置密码」+ 设置入口；③输入新密码 → 官网后端新路由 `POST /api/auth/set-password`（session 鉴权）→ M2M `PATCH /api/users/:userId/password` | **否** | 主流做法（社交注册后引导补设密码）的最短路径；信任链与注册端点同级；官网侧自校验策略（升 3 类，见 §2.8） |
| B 原生 Account API 路径 | 前端 `POST /api/verifications/verification-code`（邮箱码）→ verify → `POST /api/my-account/password` | **是** | 无 M2M 参与、Logto 原生（含 pwned 检查）；依赖 connector，作为 P1 批次的 A 升级项（A 先上线，connector 就绪后切 B，UI 不变） |

**引导时机**：进 Settings 时 banner（持久，设置成功即消失）；不做弹层强阻断（作者只需「有一个流程」）。可选增强：社交注册后首次落地一次性提示（`localStorage` 标记）。

**改动清单（方案 A）**：
1. Logto 侧（一次性）：`PATCH /api/account-center {"fields":{"password":"Edit"}}`（实施时先 GET 复核现值）。
2. `app/settings/SettingsContent.tsx`：`account.hasPassword === false` 时渲染 banner + 「设置密码」区块（新密码+确认，沿用 step-up 弹层视觉）。
3. `lib/account-api.ts`：`AccountInfo` 增 `hasPassword?: boolean`；新增 `setPasswordViaSite(password)` → `POST /api/auth/set-password`。
4. 新路由 `app/api/auth/set-password/route.ts`：session（provider=logto）鉴权 + 限速 + 校验策略（3 类字符，复用/升级 register-validation）→ 复用 `logto-admin.ts` 模式调 `PATCH /api/users/:userId/password`（logto-admin.ts 新增 `setUserPassword()`，仿 `createUser` 的 token 缓存）。
5. `lib/auth-i18n.ts`：banner/成功/失败文案。

**验收点**：GitHub 注册新号 → Settings 出现 banner（`hasPassword` 缺省 falsy 兜底）；设置 2 类字符密码被拒、3 类通过 → 用新密码从 /login 邮箱+密码登录成功（端到端）；已设密码账号 banner 不再出现；未登录/legacy 会话调 set-password → 401。

### 3.3 缺口①：注册不验邮箱

| 方案 | 内容 | SMTP | 评价 |
|------|------|:---:|------|
| **A 注册链路切 Experience API（推荐）** | `POST /api/auth/register` 改两段式：`send-code`（PUT experience{Register} + POST verification-code）与 `confirm`（verify → profile 暂存派生 username → PUT profile/password → identification 建号 → submit → **直接返回会话 code URL**，省掉二次托管跳转）。租户 `PATCH sign-in-exp {signUp:{identifiers:["email"], password:true, verify:true}}` | **是** | Logto 原生验证语义（email 只能经验证码成为 primaryEmail）；防枚举语义保持（建号 422 仍服务端内部分流）；注册 UX 反而变顺（验证码即登录） |
| B 维持 MGMT 建号 + 事后提示验证 | Settings 邮箱未验证 banner → 邮箱码 step-up 证明可达 | **是** | 一样要 connector，但语义弱（v1.42 无落库验证位，只能自证「能收信」，等于白做）——不推荐 |
| C 自建 SMTP + 自建 verified 标志 | 官网直发邮件 + neblink-server 存 verified | 自建 | 双份发信设施 + 双份状态，运维面变大，不推荐 |

**改动清单（方案 A）**：
1. Logto 侧（一次性）：`PATCH /api/sign-in-exp` signUp.identifiers 加 `email`（保留 username 亦可，API 流程走 email identifier）。
2. `lib/logto-register.ts`（新，仿 logto-signin.ts 的 cookie-jar）：`sendRegisterCode(email, origin)` / `confirmRegister(email, code, password, origin)`。
3. `app/api/auth/register/route.ts`：拆 `POST /api/auth/register/code` 与 `POST /api/auth/register/confirm`；保留限速、防枚举分流、deriveUsername（挪到 confirm 段 profile 暂存）。
4. `app/register/RegisterContent.tsx`：表单加验证码输入步骤（email+密码+确认 → 发码 → 码+提交 → 直接进会话）。
5. `lib/register-validation.ts`：密码校验升 3 类字符。
6. `lib/auth-i18n.ts`：新步骤文案。

**验收点**：新邮箱注册 → 收码（真实信箱）→ confirm 200 返回 code URL → 跟随落地已登录；错误码 422 → 统一防枚举文案；已注册邮箱走全流程 → 与现行为一致的统一文案 + 内部日志分流；注册产物 `GET /api/auth/me` 有 email 且登录态成立；**冒烟**：`curl POST /api/auth/register/code` 429/400/200 三态、托管页回归（/api/auth/logto 仍 307）。

### 3.4 缺口②：无忘记密码

| 方案 | 内容 | SMTP | 评价 |
|------|------|:---:|------|
| **A 托管页方案（推荐先行）** | 加 email connector（SMTP 或 SendGrid）→ `PATCH sign-in-exp {forgotPassword:{email:true}}` → /login 密码框下加「忘记密码？」链接 → 指向 `/api/auth/logto`（托管 authorize，含品牌 customCss）→ 托管页完成重置 → 用新密码登录 → callback 回官网 | **是** | 成本≈0 代码（1 链接 + 2 项配置）；品牌面已做完；缺点 = 重置交互离开自绘页 |
| B 自绘 forgot-password 页 | 新 `/forgot-password` + `POST /api/auth/forgot-password/*` 两段式路由，服务端复刻 §2.5 的 6 步序列（submit 后提示「请用新密码登录」，无自动续登） | **是** | 全程自绘品牌一致；中等成本（约 1 页面 + 2 路由 + i18n）；可作为 A 的后续替换 |

**对比结论**：A 是 B 的严格子集成本（同一 connector 前提），先 A 后 B。

**改动清单（方案 A）**：
1. Logto Console：配置 email connector（SMTP 凭据或 SendGrid key）+ `PATCH /api/sign-in-exp {"forgotPassword":{"email":true}}`。
2. `app/login/LoginContent.tsx`：密码输入下加「忘记密码？」链接（`/api/auth/logto?...`，i18n）。
3. （可选门控）`GET /api/auth/providers` 或新增 flag：connector 未就绪时不渲染链接。

**验收点**：/login 显示链接 → 点击落地托管页有「Forgot password?」→ 全流程重置成功 → 新密码在官网 /login 登录成功（端到端）；未知邮箱提交托管流程不报存在性（Logto 默认不发码不泄露）；错误密码登录仍 401 回归。

---

## 4. 依赖矩阵

| 工作项 | email connector（SMTP/SendGrid 发信） | Logto 配置变更（非代码） | 官网纯代码可落 | 对应缺口 |
|--------|:---:|:---:|:---:|:---:|
| ④-A 会话验证缓存 | ❌ 不依赖 | 不需要 | ✅ | ④ |
| ③-A M2M 代设密码 + banner | ❌ 不依赖 | `fields.password=Edit`（一条 PATCH） | ✅ | ③ |
| 密码校验统一升 3 类 | ❌ 不依赖 | 不需要 | ✅ | ①③前置 |
| ②-A 托管忘记密码 | ✅ 依赖 | connector + `forgotPassword.email` | 链接部分✅ | ② |
| ①-A 注册切 Experience API | ✅ 依赖 | `signUp.identifiers`+email | ❌（需 connector 联调） | ① |
| ④-B 邮箱码 step-up 选项 | ✅ 依赖 | 不需要 | ❌（需 connector 联调） | ④ |
| ③-B 原生设密码路径 | ✅ 依赖 | 已含于③-A 的字段放开 | ❌（需 connector 联调） | ③ |
| ②-B 自绘忘记密码页 | ✅ 依赖 | connector（复用②-A） | ❌ | ② |
| 后续：改邮箱（fields.email=Edit + primary-email 端点族） | ✅ 依赖 | fields.email | ❌ | 追加项 |
| 后续：邮箱验证码登录（signIn.methods verificationCode） | ✅ 依赖 | signIn.methods | ❌ | 追加项 |

注：connector 凭据 = SMTP（host/port/user/pass/from）**或** SendGrid HTTP API key 二选一，后者不需要任何 SMTP 服务器。

---

## 5. 建议实施顺序（不依赖 SMTP 的先行）

```
P0（纯代码批次，无 connector 也能全部落地）
 ├─ ④-A 会话验证缓存（Settings 弹层复用，10min TTL）
 ├─ ③-A M2M 代设密码 + banner（含 fields.password=Edit 一条 PATCH + hasPassword 检测）
 └─ 密码校验统一升 3 类（register-validation.ts，①③共用地基）

P1（connector 凭据到位后的第一批）
 ├─ ②-A 托管忘记密码（connector + forgotPassword.email + /login 链接）——最小闭环
 ├─ ④-B 邮箱码 step-up（弹层加分支，覆盖无密码用户改用户名/解绑）
 └─ ③-B 设密码切原生路径（POST /api/my-account/password，去掉 M2M 热路径）

P2（第二批）
 ├─ ①-A 注册链路切 Experience API（两段式 + 直接登录，改动面最大单列）
 ├─ ②-B 自绘 forgot-password 页（品牌一致性增强，替换②-A）
 └─ 追加项评估：改邮箱 / 验证码登录
```

排序依据：P0 三项即刻消除「别扭」与「社交账号死路」（作者反馈 ④③），零外部依赖；②-A 是 connector 到位后性价比最高的一项（缺口②关断）；①-A 动注册主链路，放在验证码链路（connector+模板）被②-A④-B验证稳定之后。

---

## 6. 证据索引

| 论断 | 证据 |
|------|------|
| username PATCH 硬性 `identityVerified` | logto v1.42.0 `packages/core/src/routes/account/index.ts`（assertThat 分支）；logto-account-api.md §5 实测 401 |
| identityVerified 只认 Password/UserPermissionValidation 邮箱码；Social/WebAuthn 不合格 | `middleware/koa-auth/koa-oidc-auth.ts` `isUserPermissionVerificationRecord` |
| verification record 无 session 直通通道 | 同上（仅 header→DB record 计算）；`routes/verification/index.ts` 全族端点清单 |
| record TTL 10min 且可复用 | `routes/verification/index.ts`（insert 返回 expiresAt）；logto-account-api.md §7 e2e 同 record 三连 PATCH |
| POST /api/my-account/password 的条件豁免 + fields.password 校验 | `routes/account/index.ts` POST password 分支 |
| hasSecurityVerificationMethod = password‖email‖phone | `routes/account/utils/has-security-verification-method.ts` |
| hasPassword 仅在 fields.password 可读时回传 | `routes/account/utils/get-scoped-profile.ts` `getAccountCenterFilteredProfile` |
| MGMT PATCH /api/users/:id/password 无验证无策略 | `routes/admin-user/basics.ts`（body min(1) 直落 argon2id） |
| Experience API Register/ForgotPassword 序列、profile 暂存 username、submit 无续登 | `routes/experience/index.ts`、`verification-routes/verification-code.ts`、`profile-routes.ts`(:202-233)、`classes/experience-interaction.ts` submit ForgotPassword 分支 |
| v1.42 无 emailVerified 落库字段 | logto-admin.ts:169-171（后端实测注释）+ schemas 复核 |
| 租户 signUp/forgotPassword/connector/OneTap 现状 | `GET https://auth.nebflow.space/api/.well-known/sign-in-exp`（2026-09-02 探测） |
| account-center fields 现值 | logto-account-api.md §2（08-27）+ 社交绑定上线批次（08-28/09-01）；实施时 `GET /api/account-center` 复核 |
| 官网注册/登录/Settings 链路 | nebflow-website：`app/api/auth/register/route.ts`、`lib/logto-admin.ts`、`lib/logto-signin.ts`、`lib/logto.ts`、`app/settings/SettingsContent.tsx`、`lib/account-api.ts`、`lib/session.ts` |
| 密码策略不一致 | 线上 sign-in-exp `passwordPolicy.characterTypes.min=3` vs `lib/register-validation.ts:28-33`（2 类） |
| 相关历史决策 | 20260901_unified-domain-plan.md（域名统一）、logto-registration-api.md（注册契约）、logto-account-api.md（Account API 契约）、20260902_logto-email-signin-missing.md（env 事故复盘） |
