# Logto 托管 UI 品牌适配 —— 独立验收与认证回归报告

> 2026-09-03 · Coder（验收节点）· 上游：「logto-托管UI品牌适配配置」（报告 `20260903_logto-brand-visual-config.md`）
> 对象：staging `https://mashiro.staging.nebflow.space` + Logto 托管面 `https://auth.nebflow.space`
> 方法：真实浏览器流（Playwright/Chromium 1.62，prefers-color-scheme 亮暗模拟）+ Management API（M2M `scope=all`）+ curl
> 红线遵守：**全程 0 发信**（密码登录、忘记密码仅到表单可达、零表单提交；测试账号 Management API 直建带已知密码）· Logto 配置只读+截图 · 凭据不入本报告

## 0. 结论速览

| # | 验收项 | 判定 | 摘要 |
|---|---|---|---|
| ① | 品牌生效（亮暗双版） | **PASS**（含 1 项边界 ⚠️） | logo/favicon/主色/暗色切换全部字节级验证通过；**页面标题 "nebflow" 无法达成**（v1.42 无 pageTitle 字段，Logto 按屏写死 `<title>`，见 §4 边界） |
| ② | 认证链回归（最高优先） | **PASS 全绿** | 密码登录 200、有/无会话忘记密码均落 reset-password 表单（7eae871 不回归）、307 链原样、认证字段逐项 unchanged |
| ③ | 不破版（亮暗） | **PASS** | 无拉伸、无破版、对比度 亮 17.1:1 / 暗 8.9:1（均超 WCAG AA 4.5:1） |
| — | 测试账号清理 | **完成** | 直建 1 个 → DELETE 204 → GET 404 → 前缀搜索 0 剩余 |

---

## 1. ① 品牌生效 —— PASS（⚠️ 页面标题为能力边界）

### 1.1 logo（亮暗双版，字节级 + DOM + 目检三重验证）

| 模式 | 页面 `<img src>` 实测解码 md5 | 与现行素材 | 目检（截图） |
|---|---|---|---|
| light | `614d4495…`（598B） | = bright.png 224px ✅ | 黑绿像素 n 标，非 Logto 默认 logo |
| dark | `26b8058d…`（604B） | = dark.png ✅ | 白绿变体自动切换 |

- natural 224×224 → 渲染 40×40，纵横比偏差 <0.02，**无拉伸变形**（`image-rendering:pixelated` 为 customCss 有意像素风）。
- dark 模式 `bodyClass` 尾缀 `GsPfC_dark`、light 为 `eAa6M_light` —— prefers-color-scheme 模拟下主题态切换正确。

### 1.2 favicon

`<link rel="shortcut icon, apple-touch-icon">` href = data URI；light `f742478f…` / dark `2e211348…`，与 bright/dark 64×64 源逐一匹配 ✅（暗色页签 favicon 随主题切换）。

### 1.3 品牌主色 #07C160

- 登录页 `button[type=submit]` 计算样式 `background-color: rgba(7, 193, 96, 0.42)`（#07C160 玻璃态 CTA），亮暗同值；
- "Forgot your password?" / "Create account" / 忘记密码 / 注册 链接均为品牌绿；
- reset-password 表单页：Email 输入 **绿色 focus 环** + 绿色 Continue 按钮。
- 配置回读 `color: {primaryColor:"#07c160", isDarkModeEnabled:true, darkPrimaryColor:"#07c160"}` ✅

### 1.4 reset-password 表单页品牌落点

该屏 DOM **本无 logo 节点**（Logto 固有结构，非本次回归，与上游记录一致）；品牌落点 = 毛玻璃卡片 + 绿色系控件 + favicon，截图确认视觉统一。

### 1.5 ⚠️ 页面标题 —— 能力边界，未达成 "nebflow"

实测 `<title>`：登录屏 `Sign in to your account` / `登录你的账号`，重置屏 `Reset password` / `忘记密码`。v1.42 Management API 无 pageTitle 字段（上游 probe 422 实测），`<title>` 由 Logto 前端按屏写死。**本节点只读约束下未重试配置，如实报告为能力边界项**——如需自定义标题待 Logto 升级或 BYO-UI 评估。

### 1.6 截图组（en 为主，双语×亮暗 = 8 张 + 认证流 3 张）

`/Users/dev/.nebflow/projects/nebflow-website/.nebflow/shots-logto-brand-after/`（git-ignored）：

| 文件 | 内容 |
|---|---|
| `verify-signin-{en,zh}-{light,dark}.png` ×4 | hosted 登录页（经 `/login → /api/auth/logto` 真实链路进入） |
| `verify-forgotpw-{en,zh}-{light,dark}.png` ×4 | reset-password 表单页（hosted 页 "Forgot your password?" 链入） |
| `verify-auth-a-loggedin-staging.png` | ②a 密码登录成功后 staging 页 |
| `verify-auth-b-forgotpw-with-session.png` | ②b 有会话忘记密码落点 |
| `verify-auth-c-forgotpw-fresh.png` | ②c 无会话忘记密码落点 |
| `verify-evidence-*.json` ×10 + `verify-summary.json` | DOM 证据（logo src/尺寸/favicon/submit 样式/标题/对比度） |

**before/after 对照**：上游改前组 `shots-logto-brand-before/`（signin/forgotpw/staging-en × light/dark + evidence）。对照结论：before 并非"默认脸"——旧 448px 像素 n 标 + 绿系 + 毛玻璃当时已生效（customCss 08-27 批次），**病灶是 4 张品牌图全挂已退役 neblink.space 域**；after 的视觉突变轻微（同为像素 n 标），本质改进 = 资产 data URI 内嵌、脱离外域、暗色变体字节级正确切换。

---

## 2. ② 认证链回归 —— PASS 全绿（最高优先）

### 2a. Management API 直建账号 + staging /login 表单密码登录 ✅

- 直建：`POST /api/users` → username `nfbrandval0903a` + 已知密码（HTTP 200，直建不发邮件）；`PATCH primaryEmail` 直设（无验证信）。
- Playwright 真实表单流：`/login` 填 identifier+password → 提交 → OIDC 回环 → **`GET /api/auth/me` = 200**，body `userId: 6ejl69cdkjcd, email: nfbrandval0903a@mail.example.com, login: nfbrandval0903a` —— 即本批测试账号本人。
- 证据：`verify-auth-a-loggedin-staging.png` + `verify-summary.json` auth.login。

### 2b. 活跃会话点「忘记密码」→ 落 reset-password 表单，无静默直登 ✅（7eae871 不回归）

同一 context（staging 已登录）→ `/login` 点 "Forgot password?"（href=`/api/auth/forgot-password`）→ 最终落 **`https://auth.nebflow.space/reset-password?app_id=ih1w2ihuvwefvgvnkzqr3`**：heading "Reset password"、邮箱输入可见、验证码文案、未回跳 staging 变已登录。`prompt=login` 防静默 SSO 行为保持。

### 2c. 全新无会话 context 点「忘记密码」→ 同样落邮箱首屏 ✅

干净 context 同链路 → 同 URL/同表单形态（`verify-auth-c-forgotpw-fresh.png`）。

### 2d. curl 端点行为 ✅

| 检查 | 实测 | 判定 |
|---|---|---|
| `GET /api/auth/logto` | **307** → `auth.nebflow.space/oidc/auth?client_id=ih1w2ihuvwefvgvnkzqr3…`（裸 authorize，无 prompt/first_screen） | ✅ |
| `GET /api/auth/forgot-password` | **307** → Location 含 **`prompt=login&first_screen=reset_password`** | ✅（7eae871 基线保持） |
| 首页 `/` | 307 → `/en` → **200**（locale 重定向链） | ✅ |
| `/en`、`/login` | 200 / 200 | ✅ |
| OIDC discovery | 200 | ✅ |

### 2e. sign-in-exp 认证字段逐项比对（live vs 上游 before 备份）✅

`GET /api/sign-in-exp`（M2M）与 `sign-in-exp-before-20260903.json` 逐字段 diff：

```
UNCHANGED signIn（methods: email+username 密码）
UNCHANGED signUp / signInMode / socialSignIn
UNCHANGED forgotPasswordMethods = ["EmailVerificationCode"]   ← 红线字段
UNCHANGED socialSignInConnectorTargets = ["github","google"]
UNCHANGED singleSignOnEnabled / mfa / hideLogtoBranding / agreeToTermsPolicy
```

另：live 与上游 after 快照 **零 diff**；branding 4 个 data URI md5 与上游报告逐一吻合；customCss 长度 10,109 字符未动。**品牌 PATCH 未碰任何认证字段，独立复核成立。**

---

## 3. ③ 不破版 —— PASS

- **无拉伸**：logo aspect delta <0.02；无元素溢出/错位（8 张截图目检）。
- **关键元素可见**：identifier/password 输入、主 CTA、社交按钮（GitHub/Google）、语言切换文案（en/zh 全渲染）、页脚均完整。
- **暗色对比度**：heading 白字像素采样 248 vs 卡片底 70 → **≈8.9:1**；亮色 DOM 计算值 **17.14:1**（白卡黑字）——均超 WCAG AA 4.5:1，暗色可读性良好。
- 中文暗色版（`verify-signin-zh-dark.png`）目检：无乱码、无叠字、控件边框清晰。

---

## 4. 能力边界（转述上游报告 §2，本节点实测未见矛盾）

| 不支持项 | 上游证据 | 对验收的影响 |
|---|---|---|
| 资产上传 `POST /api/assets` | 404（无 storage provider） | 采用 data URI（已验证成立） |
| 页面标题 `pageTitle` | probe 422，`<title>` 前端写死 | **验收项①的"标题 nebflow"不可达成** ⚠️ |
| `hideLogtoBranding=true` | 400 entitlement 门控 | "Powered by Logto" 页脚保留（非缺陷） |
| customCss 付费门槛 | 无（本部署可用） | 现状毛玻璃样式即证 |

## 5. 测试账号清理 ✅

- 本批直建：`nfbrandval0903a`（id `6ejl69cdkjcd`，前缀唯一，前缀搜索确认未误伤他人）。
- `DELETE /api/users/6ejl69cdkjcd` → **204**；复查 GET → **404**；`search=nfbrandval0903` → **0 剩余**。
- 本地临时凭据/token/脚本已删（`/tmp/nfbrand-*`）。

## 6. 回滚方法（转述上游 §5，未执行）

```bash
# M2M token: POST https://auth.nebflow.space/oidc/token, grant_type=client_credentials,
#            resource=https://default.logto.app/api, 显式 scope=all（否则 403）
# 仅回滚品牌图（本次唯一实际变更字段）：
curl -X PATCH https://auth.nebflow.space/api/sign-in-exp \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"branding":{"logoUrl":"https://neblink.space/logto/logo.png","darkLogoUrl":"https://neblink.space/logto/logo-dark.png","favicon":"https://neblink.space/logto/favicon.png","darkFavicon":"https://neblink.space/logto/favicon-dark.png"}}'
```

- 备份：`/tmp/logto-brand-backup.json`（易失）+ 持久副本 `projects/nebflow-website/.nebflow/logto-brand-config/sign-in-exp-before-20260903.json`；`color`/`customCss`/`hideLogtoBranding` 未变更、无需回滚；整包回滚以 before 快照逐键 PATCH。

## 7. 提醒作者（人工验收）

1. **租户级生效**：品牌配置在 Logto default 租户——生产 `nebflow.space` 的 hosted 页已同步本外观（走 Logto 层裁定之预期，非遗漏）。
2. 建议本机真实浏览器过一眼 staging 登录页与忘记密码页确认观感（截图见 §1.6）。
3. 如需微调（**暗色主色/字体/圆角**可直接改；**标题措辞**受 §4 pageTitle 边界限制，当前版本改不了）直接说——均为视觉字段 PATCH，不碰认证链。

—— 验收完成 · Coder · 2026-09-03 · ① PASS（⚠️ 标题边界）· ② PASS 全绿 · ③ PASS · 账号已清理
