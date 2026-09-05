# Logto 托管 UI 品牌视觉适配 —— 配置实施报告

> 2026-09-03 · Coder · 全程 Management API（M2M，`scope=all`）· **0 发信**（仅页面浏览/截图，未提交任何表单、未触发任何 send 端点）
> 环境：Logto 自托管 v1.42.0，API `https://auth.nebflow.space`（default 租户）；凭据只读引用 `~/.nebflow/logto-registration.env`（值不入本报告）
> 官网 repo **零改动**（未 push、未 commit——data URI 方案使 repo 改动非必要）

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| logo / darkLogo / favicon / darkFavicon | ✅ 全部换成 nebflow 现行品牌素材（bright/dark 224px + 64×64 favicon），**data URI 内嵌**，字节级 md5 校验一致，脱离一切外域依赖 |
| 品牌色 | ✅ 无需改动——08-27 批次已配置且与官网 repo 逐值一致（`#07c160`，暗色已开启，darkPrimaryColor=`#07c160`） |
| customCss | ✅ 保持 08-27 v1 草稿全文不动（字体栈/圆角与官网 token 实测一致，见 §7） |
| 页面标题（pageTitle） | ⛔ 能力边界：v1.42 Management API 无此字段（probe 422，见 §2） |
| hideLogtoBranding | ⛔ 能力边界：本部署拒绝（400 "not supported in this environment"），"Powered by Logto" 页脚保留 |
| 资产上传（POST /api/assets） | ⛔ 能力边界：本部署未启用 assets 插件（GET/POST 均 404），改用 data URI（见 §4） |
| 认证链回归 | ✅ 零误伤：`/api/auth/logto` 307、`/api/auth/forgot-password` 307 且带 `prompt=login&first_screen=reset_password`、首页 200、OIDC discovery 200（§6.4） |
| 认证流字段 | ✅ 逐字段 diff：`signIn`/`signUp`/`signInMode`/`forgotPasswordMethods`/`socialSignInConnectorTargets`/`singleSignOnEnabled`/`mfa` 与改前**逐字节一致** |

**before 基线真相**：改前并非"纯默认脸"——08-27 批次的 customCss + 品牌色已在生效；实际病灶是 4 个 branding 图片 URL 全挂在**已退役的 neblink.space** 域（该域今日仍 200 属退役前残余，随时可能消失）。本次修复的本质 = 品牌素材与退役域解耦。

## 1. 现状判定（改前 GET /api/sign-in-exp）

- `color`: `{primaryColor:"#07c160", isDarkModeEnabled:true, darkPrimaryColor:"#07c160"}` — 已配置
- `branding`: 4 字段全指 `https://neblink.space/logto/*.png`（448px 旧导出）
- `customCss`: v1 草稿（毛玻璃 + 品牌绿 + 8px 圆角），10,109 字符（原始长度；JSON 转义态 14,526），已生效
- `hideLogtoBranding`: false → 页脚 "Powered by Logto"
- `forgotPasswordMethods: ["EmailVerificationCode"]`（红线字段，未触碰）

## 2. 能力边界清单（v1.42.0 实测）

| 能力 | 支持 | 证据 |
|---|---|---|
| `branding.logoUrl/darkLogoUrl/favicon/darkFavicon` | ✅（值为 URL；**data URI 亦可**） | PATCH 200 + 回读一致 + 页面渲染 |
| `color.primaryColor/isDarkModeEnabled/darkPrimaryColor` | ✅ | 现状即证（08-27 配置生效至今） |
| `customCss` | ✅（**非付费档位**，本部署可用） | 现状即证（毛玻璃样式渲染于截图） |
| 资产上传 `POST/GET /api/assets` | ⛔ 未启用（404 Not Found，无 S3 存储 provider） | 实测 404 |
| 页面标题 `pageTitle`/`title` 字段 | ⛔ 不存在；PATCH probe 422 `entity.invalid_input` | 实测 422×2 |
| `hideLogtoBranding=true` | ⛔ 本部署拒绝：400 `"Hide Logto branding is not supported in this environment"`（entitlement 门控） | 实测 400；配置保持 false |
| 托管页 URL 形态 | 仅 URL 值形态受限说明：favicon 键名为 `favicon`/`darkFavicon`（无 `Url` 后缀），logo 为 `logoUrl`/`darkLogoUrl` | 回读 shape |

> 页面标题边界说明：v1.42 托管页 `<title>` 由前端按屏写死（sign-in 屏 = "Sign in to your account"，reset-password 屏 = "Reset password"），API 无配置槽。要自定义标题需升级 Logto（若有新版本引入）或 BYO-UI（`customUiAssets`，本部署亦未启用 storage，同样受限）。不强求，维持现状。

## 3. 逐字段 before → after

| 字段 | BEFORE | AFTER |
|---|---|---|
| `branding.logoUrl` | `https://neblink.space/logto/logo.png`（448px 旧导出，挂退役域） | `data:image/png;base64,…`（= bright.png 224px，598B，md5 `614d4495…`） |
| `branding.darkLogoUrl` | `https://neblink.space/logto/logo-dark.png` | data URI（= dark.png，604B，md5 `26b8058d…`） |
| `branding.favicon` | `https://neblink.space/logto/favicon.png` | data URI（bright 缩 64×64，sips；667B，md5 `f742478f…`） |
| `branding.darkFavicon` | `https://neblink.space/logto/favicon-dark.png` | data URI（dark 缩 64×64；675B，md5 `2e211348…`） |
| `color.*` | `#07c160` / true / `#07c160` | **不变**（与官网 repo `lib/config.ts` THEME 一致：primary `#07c160`，暗色主题 primary 同值；hover `#06ad56`、light 变体 `#5cd88a` 属 hover/辅助色，不作 primary） |
| `customCss` | v1 草稿 14,526 字符 | **不变**（字体栈/圆角已对齐官网：`-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif`、8px） |
| `hideLogtoBranding` | false | **不变**（true 被 400 拒绝） |
| 其余全部字段 | — | 逐字节不变（§6.1 diff） |

PATCH 执行形态：`PATCH /api/sign-in-exp`，body 仅 `{branding:{…}}`（视觉字段）；首次尝试附带 `hideLogtoBranding:true` 被整体原子拒绝（400），**无半改状态**；剔除该键后 200。

## 4. 资产方案：data URI（资产 ID/URL 说明）

- 资产上传 API 不可用（404）→ 无 Logto 托管 URL、无资产 ID。
- 采用 **data URI 内嵌**（`data:image/png;base64,…`）：随 `sign-in-exp` 配置存储，随 `/.well-known` 公开面下发，**零外域依赖**（对退役域 neblink.space 与未上线的 nebflow.space 均无依赖）。
- 字节等价性：live 配置解码后与本地源文件 md5 逐一比对 **4/4 match**（§3 表）。
- 渲染验证：logo `<img src>` 与 favicon `<link href>` 实测均为 data URI（DOM evidence JSON）；暗色模式自动切换 white 变体（截图 §6.3）。
- 体量：4 项合计 payload 3,597 字节，对 jsonb 配置无压力；素材变更时重跑同样 PATCH 即可（生成脚本模式见 `patch-applied-branding.json`）。

## 5. 备份与回滚

**备份文件**：
- 会话要求路径：`/tmp/logto-brand-backup.json`（tmp 易失）
- 持久副本：`~/.nebflow/projects/nebflow-website/.nebflow/logto-brand-config/sign-in-exp-before-20260903.json`（git-ignored，勿入仓）
- 改后快照：同目录 `sign-in-exp-after-20260903.json`；实际 PATCH body：`patch-applied-branding.json`

**逐字段回滚**（M2M token 获取：`POST https://auth.nebflow.space/oidc/token`，`grant_type=client_credentials` + `resource=https://default.logto.app/api` + **显式 `scope=all`**，否则 403）：

```bash
# 仅回滚品牌图（本次唯一实际变更字段）：
curl -X PATCH https://auth.nebflow.space/api/sign-in-exp \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"branding":{"logoUrl":"https://neblink.space/logto/logo.png","darkLogoUrl":"https://neblink.space/logto/logo-dark.png","favicon":"https://neblink.space/logto/favicon.png","darkFavicon":"https://neblink.space/logto/favicon-dark.png"}}'
```

- `color` / `customCss` / `hideLogtoBranding` 未变更，无回滚动作。
- 若需整包回滚：以 before JSON 各字段为 body 逐键 PATCH 即可（before 快照即权威源）。
- 全量核对：`GET /api/sign-in-exp` 与 before 快照 diff 应为空。

## 6. 回读与快检证据

**6.1 Management 回读 diff**（before vs after，仅列关注字段）：`branding` 一项变更；`color`/`customCss`/`hideLogtoBranding`/`forgotPasswordMethods`/`signIn`/`signUp`/`signInMode`/`socialSignInConnectorTargets`/`singleSignOnEnabled`/`mfa` 全部 `unchanged`。

**6.2 公开面**：`GET /api/.well-known/sign-in-exp`（注意前缀 `/api`，裸 `/.well-known/...` 为 404）→ 200，`branding` 4 字段 data URI、`forgotPassword:{phone:false,email:true}`、`color` 正确下发。

**6.3 截图**（Playwright Chromium 1.62，en locale，`prefers-color-scheme` 模拟暗色；入口 `https://mashiro.staging.nebflow.space/en` → 点击 Sign in → hosted 页 → 点击 "Forgot your password?"）：
- BEFORE：`~/.nebflow/projects/nebflow-website/.nebflow/shots-logto-brand-before/`（signin/forgotpw/staging-en × light/dark + evidence JSON ×4）
- AFTER：`…/shots-logto-brand-after/`（同名全套）
- AFTER 实测 DOM：`logoSrc` = data URI（bright）、暗色自动换 white 变体、`submitBg`=`rgba(7,193,96,0.42)`、`submitRadius`=`8px`、`bodyFont`=`-apple-system, "system-ui", "Segoe UI", Roboto, sans-serif`、标题维持 Logto 写死值（§2 边界）
- ⚠️ reset-password 屏无 logo `<img>` 节点（Logto 该屏 DOM 本就不含 logo），品牌落点为玻璃卡片/绿色系/favicon；favicon 已验证 data URI。

**6.4 认证链 curl（0 发信）**：

| 检查 | 结果 |
|---|---|
| `GET mashiro.staging.nebflow.space/api/auth/logto` | **307** → `auth.nebflow.space/oidc/auth?client_id=ih1w2ihuvwefvgvnkzqr3…`（PKCE 链正常） |
| `GET …/api/auth/forgot-password` | **307** → Location 含 `prompt=login&first_screen=reset_password` ✅（官网 commit 7eae871 行为保持） |
| `GET …/en` | 200 |
| `GET auth.nebflow.space/oidc/.well-known/openid-configuration` | 200 |

## 7. customCss 全文（未改动，现网生效值）

见本文件同目录导出：`20260903_logto-brand-customcss-live.txt`（从 after 快照逐字节导出，10,109 字符）。要点：`nf-` 前缀 token、`body[class$='_dark']` 主题钩子、官网毛玻璃 token 翻译、`button[type='submit']` 品牌玻璃 CTA、`img[alt='app logo']{image-rendering:pixelated}`。

## 8. 租户级影响声明（作者裁定预期）

品牌配置存于 Logto **default 租户** `sign_in_experiences`——凡走该租户 hosted 页的入口一律同步生效：staging（mashiro.staging.nebflow.space）、生产 nebflow.space（发布后同 client `ih1w2ihuvwefvgvnkzqr3`）、客户端 PKCE 入口。**生产 hosted 页即刻获得本品牌外观，无需等官网发布**——此为「走 Logto 层」裁定的预期行为，非遗漏。

## 9. 移交下游验收注意

1. 认证链回归与视觉细验由「logto-brand-验收与回归」节点执行；本节点未做任何表单提交/发信。
2. repo `public/logto/*.png` 为**旧字节**（md5 ≠ 现行 bright/dark 素材），staging `/logto/logo.png` 随之过期——托管页已不依赖它们；但官网站内若有引用需官网侧自行决定是否更新（本任务未动 repo）。
3. 邮件模板 logo（`https://nebflow.space/email-logo.png`）与本次无关，维持 09-03 SMTP 批次状态。
4. "Powered by Logto" 页脚与默认屏<title>为当前版本硬边界（§2），验收时勿列为缺陷；如需移除待 Logto 升级或 BYO-UI 评估。

—— 实施完成 · Coder · 2026-09-03
