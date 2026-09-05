# Logto SMTP Email Connector 接入 + forgotPassword.email 开关 —— 实施报告

> 2026-09-03 · 邮件模板接入 2/4 · Management API（M2M）实施 · **未实际发信**（DirectMail 量控，发信留给下游验收节点）
> 环境：Logto 自托管 v1.42.0（容器 neblink-logto，镜像 svhd/logto:latest，版本实测复核为 1.42.0），API `https://auth.nebflow.space`
> 凭据来源（均只读引用，值不入本报告）：`~/.nebflow/directmail.env`（SMTP）、`~/.nebflow/logto-registration.env`（M2M）、`~/.nebflow/logto-admin-credentials.txt`（本次未动用）

## 0. 结论摘要

| 项 | 状态 |
|---|---|
| connector-smtp 创建 | ✅ id `cmlj173ef4ho`（connectorId `simple-mail-transfer-protocol`，Email 类 / target `smtp`） |
| 四类英文模板 | ✅ SignIn / Register / ForgotPassword / Generic 齐备，与定稿源码**逐字节一致**（脚本比对 PASS） |
| `{{code}}` 在位 | ✅ 每类 subject 1 处 + 正文 2 处（首屏 preheader + 验证码位），logo 均为 `https://nebflow.space/email-logo.png` |
| forgotPassword.email 开关 | ✅ 回读三源一致（见 §3） |
| 实际发信 | ⛔ 未执行；未改其他租户配置与任何既有用户 |

## 1. Connector 创建证据

- `POST /api/connectors` 200 → `{"id": "cmlj173ef4ho", "connectorId": "simple-mail-transfer-protocol"}`
- `GET /api/connectors` 现存 connector：google-universal、github-universal、**simple-mail-transfer-protocol**（此前无 email connector，09-02 audit 基线一致）

配置回读（`GET /api/connectors/cmlj173ef4ho`，敏感值脱敏）：

| 字段 | 值 |
|---|---|
| host | `smtpdm.aliyun.com` |
| port | `465` |
| secure | `true`（465 隐式 SSL；guard 默认 false，已显式置 true） |
| auth.user | `noreply@mail.nebflow.space` |
| auth.pass | `Sh***`（脱敏，源 `~/.nebflow/directmail.env` `NEBLINK_DM_SMTP_PASSWORD`） |
| fromEmail | `noreply@mail.nebflow.space` |
| templates | 4 条，见下 |

模板回读核对（content 与 `~/.nebflow/docs/Nebflow/20260903_email-templates-preview.html` 源码块 `src-signin` / `src-register` / `src-forgot` 逐字节比对，Generic 按 D4 原样复用 SignIn）：

| usageType | subject | content 字节数 | {{code}} 正文/subject | logo URL |
|---|---|---|---|---|
| SignIn | nebflow sign-in code: {{code}} | 4354 | 2 / 1 | ✅ |
| Register | nebflow sign-up code: {{code}} | 4352 | 2 / 1 | ✅ |
| ForgotPassword | nebflow password reset code: {{code}} | 4365 | 2 / 1 | ✅ |
| Generic（＝SignIn 原样） | nebflow sign-in code: {{code}} | 4354 | 2 / 1 | ✅ |

注①：任务书「共 24 处 {{code}}」为预览文件全文口径（含桌面+手机两张渲染预览帧的重复渲染）；实配每类 3 处 × 4 类 = 12 处，与定稿源码一致，无缺失。
注②：connector-smtp 1.5.4（v1.42.0 monorepo 锁定版本）`templates` 校验器强制四类齐全，与预览文档「接入结构」节说明一致。

## 2. Schema 依据（写入前核对，非猜测）

- 运行版本实测 1.42.0；按同 tag monorepo 源码核对：`smtpConfigGuard` = `{host, port, auth:{user,pass}, fromEmail, templates[4类必须], secure, …}`（`packages/connectors/connector-smtp/src/types.ts`，1.5.4）
- 创建路由 `POST /connectors` body = `{connectorId, config, …}`，connectorId 取 factory metadata id `simple-mail-transfer-protocol`

## 3. forgotPassword.email 开关证据

⚠️ 文档步骤写的 `PATCH /api/sign-in-exp {"forgotPassword":{"email":true}}` **在 v1.42 不是存储字段形状**，直接发会 422（实测）。v1.42 的存储字段为 `forgotPasswordMethods`（jsonb 数组），`forgotPassword:{phone,email}` 是公开回读视图的派生形状。

实际执行：`PATCH /api/sign-in-exp {"forgotPasswordMethods": ["EmailVerificationCode"]}` → 200

| 回读源 | 结果 |
|---|---|
| Management `GET /api/sign-in-exp` | `forgotPasswordMethods: ["EmailVerificationCode"]` |
| DB（只读复核 `sign_in_experiences`，default 租户） | `["EmailVerificationCode"]`（改前 `[]`） |
| Public `GET /api/.well-known/sign-in-exp` | `forgotPassword: {"phone": false, "email": true}` |

路由侧 `getForgotPasswordAvailability` 校验通过的前提正是存在 email connector（本次已满足）；phone 保持关闭（`[]` → `["EmailVerificationCode"]`，未引入 PhoneVerificationCode）。

## 4. 与文档/任务书的偏差说明

1. **M2M 调用必须显式 `scope=all`**：`POST /oidc/token` 不带 `scope` 参数时 v1.42 签发的 M2M token 无 scope claim → Management API 一律 403（`auth.forbidden`）。DB 复核角色/绑定均完好（`neblink-registration` app → `neblink-registration` role → scope `all` on resource `management-api`），非权限丢失，仅调用方式问题。下游用 M2M 直调 Management API 时注意。
2. **开关字段形状**（见 §3）：语义与文档「缺口②托管方案步骤」完全一致，仅 PATCH body 键名不同。
3. **发件别名 nebflow 未能在 connector 侧生效**：connector-smtp 1.5.4 config guard 无 `fromName`/显示名字段（未知键被 zod 剥除），nodemailer `from` 用纯地址 → 邮件 From 头为 `noreply@mail.nebflow.space`，无 "nebflow" 友好名。仅观感项，不影响验证码链路；如需别名，待官网侧（`nebflow.space/email-logo.png` 同批）另行评估或升级 connector 版本。
4. **入口 URL 行为**：裸访问 `https://auth.nebflow.space/forgot-password` 会 302 → `/unknown-session`（无 OIDC 交互上下文，SPA 正常行为）。**正确入口必须经 authorize 链路**（见 §5）。

## 5. Hosted Forgot-Password 实际入口（供验收节点直连复核）

```
入口（推荐）:  https://nebflow.space/api/auth/logto
  → 302 OIDC authorize (client_id ih1w2ihuvwefvgvnkzqr3, PKCE)
  → https://auth.nebflow.space/sign-in?app_id=…   托管登录页（已品牌化）
  → 页面 "Forgot password?" 链接（forgotPassword.email=true 后渲染）
  → 托管重置流：输入邮箱 → ForgotPassword 模板发码 → 验证码 + 新密码 → 提交
```

直接 URL 参考：`https://auth.nebflow.space/forgot-password`（仅作为路由存在性证据，裸访问 302 → `/unknown-session` 属预期；UI 级「Forgot password? 链接已出现」的浏览器验证留给下游验收节点）。

## 6. 边界与清理

- ⛔ 未实际发信（DirectMail 量控）；未触发任何 send/test 端点
- 仅两项变更：新增 1 个 email connector + default 租户 `forgotPasswordMethods` 一处 PATCH；admin 租户与全部用户数据零改动
- 实施用临时脚本/凭据缓存置于 `/tmp/logto-smtp/`（无硬编码凭据，token 缓存已删），SSH 隧道已关闭
- 模板 logo 前置依赖：官网 `public/email-logo.png` 上线前，邮件 logo 位置按设计降级为 alt 文本 + wordmark，不破版

—— 实施完成 · Coder · 2026-09-03
