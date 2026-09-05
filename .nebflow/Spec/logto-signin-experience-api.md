# Logto 登录 Experience API 调用序列（自绘登录页·官网侧实现参考）

> BYO-UI Backend 半场交付（2026-08-27，Nebula 裁定）。风格对齐 `logto-registration-api.md`。
> 本文所有端点/错误码/时序均为 **svhd/logto 部署镜像当日实测**（含真浏览器 network trace），非文档转抄。
> 注册（建号）走 `logto-registration-api.md` 的 Management API 直建通道；本文只管**登录面**。

---

## 0. 一页纸边界盘点（先读这个）

| 维度 | 当前租户真相 | 自绘页需覆盖 |
|---|---|---|
| 登录方式 | email+password 主位 / username+password 次位 | ✅ 两种标识符 + 密码输入 |
| 注册引导 | 租户 signUp=username-only；主路径=官网中继直建号（registration-api） | ✅ 「创建账号」链接 → 官网自有 /register 页 |
| MFA | 未启用 | ❌ 无分支 |
| 验证码/SMS | 无邮件 connector、无短信 | ❌ 无分支 |
| 社交登录 | 零 connector | ❌ 无入口 |
| 忘记密码 | 依赖 SMTP（未配，SMTP 升级路径在 backlog） | ❌ 暂不做入口，只保留「联系支持」文案可选 |
| 错误态 | 平台原生统一模糊（§5 实证） | ✅ 单一文案即可，无需区分账号不存在/密码错 |

**传输架构硬约束（决定性）**：`auth.neblink.space` 对 `/api/*` **不下发任何 CORS 头**（OPTIONS 预检实测无 `access-control-allow-*`）。nebflow.space 页面**浏览器直连不可行**——必须二选一：
1. **推荐：官网后端中继代理** —— 表单 POST 到自家 `/api/auth/experience/*`，后端用 http 客户端（持 cookie jar per 登录尝试）转发到 Logto；全程同源，Cookie 属性无关。下方序列即后端要复放的请求。
2. 备选：Logto customUiAssets 同源托管自绘包（视觉同现在托管页，只是页面代码全换成我们写的）。

⚠️ **线上现状观察**（供官网半场核查）：username-only（无邮箱）账号经生产站 callback 时报 `error=logto_auth_failed`——网站 callback 逻辑疑似强依赖邮箱字段。自绘页上线前需修（email 或 sub 建站内映射）。

**现有托管页品牌化配置全部保留不删**：作为 fallback + 桌面端 Device Flow 兜底面继续服务。

---

## 1. 凭据与常量

- Web App OIDC client：env 键名 `LOGTO_WEB_APP_ID` / secret 仅官网侧使用且**不参与本流程**（登录序列对 public client 免密）
- `LOGTO_ENDPOINT = https://auth.neblink.space`
- 注册 redirect_uri 现表：`https://neblink.space/api/auth/logto/callback`（正式）、`http://localhost:3000/api/auth/logto/callback`（本地调试）
- 测试账号：qa-e2e@neblink.space（或现挂一个 si_probe 临时号实测）

## 2. 序列总览（一次完整登录）

```
[导航] GET {LOGTO}/oidc/auth?client_id=…&redirect_uri=…&response_type=code&scope=openid
        └ 交互开始，浏览器获 interaction cookie（跟随重定向到登录 UI 即可）
[XHR1] PUT  /api/experience           {"interactionEvent":"SignIn"}
[XHR2] POST /api/experience/verification/password
              {"identifier":{"type":"email"|"username","value":…},"password":…}
[XHR3] POST /api/experience/identification   {"verificationId":"<XHR2 返回>"}
[XHR4] POST /api/experience/submit           → 200 {"redirectTo":"/oidc/auth/<resumeId>"}
[导航] window.location = redirectTo          ← 全页跳转！
        → 自动两跳 resume（/oidc/auth/<id1>→/oidc/auth/<id2>）
        → 302 到 redirect_uri?code=<authCode>&iss=https://auth.neblink.space/oidc
```

- 两条真实 trace（2026-08-27，Playwright 抓取）：email 与 username 完全同构；唯一差异是 email 流会先发一条 `GET /api/experience/sso-connectors?email=` 预检（企业 SSO 探测，租户无 connector 返回空——可忽略不复制）。
- resume 两跳是**服务端 303 重定向**，前端只管整页跳转 `redirectTo`，不用自己解析中间 hop。

### 中继代理版注意
- cookie jar 必须从步骤 [导航] 开始到最终 callback 前**同一个 jar**（interaction 态在其中）；最后把 302 目标 URL（含 code）返回给浏览器做整页跳转即可。
- authCode 是一次性且短命的：拿到即换 token（§4），不要落库。

## 3. 响应形态速查

| 步骤 | 成功响应 |
|---|---|
| XHR1 PUT | `204` |
| XHR2 password verify | `200 {"verificationId":"<uuid样>"}` |
| XHR3 identify | `204` |
| XHR4 submit | `200 {"redirectTo":"/oidc/auth/<resumeId>"}` |

## 4. 回调处（官网 callback 端点）

```http
POST https://auth.neblink.space/oidc/token
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=<authCode>
&redirect_uri=https://neblink.space/api/auth/logto/callback   ← 必须与授权起始完全一致
&client_id={LOGTO_WEB_APP_ID}
&client_secret={LOGTO_WEB_APP_SECRET}                          ← confidential 代换在后端
```

成功 → `{access_token, id_token, refresh_token?, …}`。
校验要点：解码 id_token（ES384，JWKS=`/oidc/jwks`），确认 `iss=https://auth.neblink.space/oidc`、`sub` 即用户唯一 id。**建议以 sub 为站内账号主键、email 作展示字段**（见 §0 username-only 观察教训）。

## 5. 错误语义表（当日实测）

| 场景 | 状态码 | body code | message | 备注 |
|---|---|---|---|---|
| 密码错 | 422 | `session.invalid_credentials` | "Incorrect account or password. Please check your input." | 统一模糊 |
| 账号不存在 | 422 | `session.invalid_credentials` | 同上，逐字节一致 | **平台原生模糊，无需归一化** ✅ |
| 交互过期/cookie 丢失 | 400 | `session.not_found` | "Session not found. Please go back and sign in again." | 引导刷新重来 |
| profile 缺失历史遗留 | — | （管理面预置 username 后不会出现） | — | registration-api 已带 username 直建 |

爆破防护：sentinelPolicy 默认值兜底（连续失败锁定），官网层 IP 频控另计。

## 6. 幽灵 consent 分支（观测记录 + 逃生通道）

脚本化测试曾观测到 submit 后 resume 跳 `/consent`（首方应用正常不应出现，疑为残留交互态）；浏览器正常流未复现。如遇到：
1. 整页进入 `/consent?app_id=…`；
2. `POST /api/interaction/consent`（旧交互命名空间，该构建仍存活；匿名调用返回 400 `session.not_found` 为路由存在性证据）；
3. 以其返回的 `redirectTo` 继续 resume。
自绘页若永远复放 §2 标准链路则不会触发；此条仅供排障备案。

## 7. 配置快照（本文写作时刻，变更以租户为准）

```jsonc
signUp.identifiers=["username"]            // 主路径不经此，仅兜底自助注册
signIn.methods=[{email, password:true, primary:true},{username, password:true}]
color={primaryColor:"#07c160", isDarkModeEnabled:true, darkPrimaryColor:"#07c160"}
branding=neblink.space/logto/{logo,logo-dark,favicon,favicon-dark}   // 托管页 fallback 面
connectors=[]  mfa 未启用  account-center 未启用（见 logto-account-api.md 另案）
```
