> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Logto Sign-in 品牌化交付包（nebflow 官网风格）

> P2 低优交付件。本文档收拢全部规格与应用手册；CSS 草稿见同目录 `logto-signin-custom.css`（可直接粘贴进 Console > Sign-in & account > Branding > Custom CSS 编辑器）。
> 应用动作（程序化或人工粘贴）待 M2M 凭据到位后执行，**本文档不含任何已生效的配置变更**。
> 基准版本：以 svhd/logto 当前部署镜像为准（见 §7 版本差异确认项）。

---

## 0. TL;DR

| 项 | 结论 |
|---|---|
| Light 主色 | `#07c160`（微信绿，与客户端 token 一致） |
| Dark 主色 | `#07c160`（对客户端暗底锚点 `#111419` 对比度 7.6:1，无需提亮） |
| 交互态 | Logto 由主色自动派生 hover/pressed（lighten/darken 10），pressed 推导值 ≈ 客户端 hover `#06ad56`——基本天然对齐 |
| 图形槽位 | Logto 仅 4 个 URL 槽：logo / darkLogo / **favicon / darkFavicon**（注意 favicon 键名无 Url 后缀）；无 apple-touch 槽位 |
| Custom CSS 字段 | `customCss`（字符串），Management API `PATCH /api/sign-in-exp` |
| 暗色模式 | 开关字段 `color.isDarkModeEnabled`；开启后按用户系统偏好自动切换（官方文档明示） |

---

## 1. 素材清单（已生成，位于 `~/.nebflow/docs/Nebflow/assets/logto/`）

源图：`assets/logo/bright.png`（黑色字形+绿块）/ `assets/logo/dark.png`（白色字形+绿块），224×224 RGBA，底层为 **7×7 像素网格**（每格 32px）。

### 缩放方法说明

任务指定的 `magick -filter point` nearest-neighbor 思路不变；本机未装 ImageMagick，实际用 Pillow 做**网格重建渲染**（采样 7×7 各格中心像素→按目标尺寸重绘矩形）——比 NEAREST 重采样更强的无损缩放：任意尺寸下边缘绝对锐利（NEAREST 在非整数倍缩放时仍可能有格宽抖动）。生成脚本逻辑已固化在本节描述中，可复现。

### 生成物清单

| 文件 | 尺寸 | 源图 | 用途 / Logto 槽位 |
|---|---|---|---|
| `logo.png` | 448×448 | **bright**（黑字形） | 亮色主题品牌 logo（`branding.logoUrl`） |
| `logo-dark.png` | 448×448 | **dark**（白字形） | 暗色主题品牌 logo（`branding.darkLogoUrl`） |
| `favicon.png` | 64×64 | **bright**（黑字形） | Favicon 默认槽（`branding.favicon`） |
| `favicon-dark.png` | 64×64 | **dark**（白字形） | Favicon 暗色变体槽（`branding.darkFavicon`） |
| `favicon.ico` | 内嵌 16/32/48 | bright | 备用经典 ICO（若某些浏览器场景 PNG 展示不佳时换用） |
| `apple-touch-icon.png` | 180×180（白底压平） | bright | ⚠️ 非 Logto 槽位——供宿主页（auth 子域根路径等）自托管 apple-touch 使用 |

全部文件 <2KB（500KB 上限富余巨大）；透明背景保留（唯 apple-touch 按 Apple 惯例压平到白底，避免 iOS 把透明合成成黑底）。

### 源图选择理由

- **亮色登录表面配黑字形（bright）**：亮面 `#f5f6f8`/`#fff` 上黑字形的形态对比最高；这与官网 Navbar（白底黑像素标）、客户端亮色主题完全同构。
- **标签页 favicon 配 bright**：沿用客户端既有标准「标签页 favicon 用 bright」（2026-08-25 v2 定稿口径），保持跨产品一致；Logto 提供 darkFavicon 变体槽应对系统暗色标签栏，配 dark（白字形）正好复刻官网 favicon 的自适应策略。
- 绿点缀两版同源（微信绿族），不区分。

### 尺寸依据

- 官方约束只有两条：**≤500KB；格式 SVG/PNG/JPG/JPEG/ICO；favicon 建议方形**（出处见 §6-2）。未规定像素尺寸 → 我们取 7×7 网格的整数倍（448 = 64px/格 × 7）保证像素完美，且 448≈150px 显示位 @3x retina 富余。
- 64 用于 favicon（高频显示尺寸 16–32px，上传大图由浏览器缩放即可，64 兼顾清晰与极小体积）。
- **实测确认项 S1**：logo/favicon 各槽在实际渲染中的最大显示尺寸，可在 Live Preview 的 DevTools 中核（`img[alt='logo']` 的 CSS 高度），必要时再出对应倍数图。

---

## 2. 色彩方案（基于客户端准确 token）

### 2.1 最终建议值

| 槽位（Management API 字段） | Light | Dark |
|---|---|---|
| `color.primaryColor` / `color.darkPrimaryColor` | **#07c160** | **#07c160** |
| 页面背景（customCss 自定） | #f5f6f8 | #111419（客户端暗底锚点） |
| 卡片表面 | #fff | #171b22 *（derived，锚点轻提亮）* |
| 正文 | #1b1e26 | #e8eaee *（derived）* |
| 辅助文字 | #8b8e96 | #9aa0a8 *（derived）* |
| 边框 | #ddd | rgba(255,255,255,0.08) *（derived）* |

**为什么 Dark 不换色**：微信绿 #07c160 对暗底 #111419 的 WCAG 对比度约 **7.6:1**（普通文本 AA 要求 4.5:1），原值直接成立；提亮反而偏离客户端现行暗色主题观感。

### 2.2 可交互态分层

**关键机制（实证，出处 §6-5）**：Logto experience 前端从单一主色自动派生交互态并写成 body 内联样式变量：

- `--color-brand-default` = 主色
- `--color-brand-hover` = 主色 absoluteLighten(+10)
- `--color-brand-pressed` = 主色 absoluteDarken(-10)
- overlay 组 `rgba(#07c160, .16/.08/.12)`

代入 #07c160 的推导值：

| 状态 | Light/Dark（同主色时） | 说明 |
|---|---|---|
| default | #07c160 | — |
| hover | ≈ #20c770（lighten 10） | Logto 自动 |
| pressed | ≈ #06ae56（darken 10） | **≈ 客户端 hover #06ad56**，差 1 位绿色通道属舍入 |
| loading | #20c770（light）/ #06ae56（dark） | 自动 |

**决策点（M2M 执行前拍板其一）**：

- **方案 A（推荐，零覆盖）**：接受 Logto 语义——hover 提亮、按下压暗。与客户端「hover=#06ad56」仅在视觉亮度方向上不同，但 touched-up 观感一致且后续升级零维护。
- **方案 B（严格客户端语义）**：在 customCss 中强制 `button[type='submit']:hover:not(:active){ background:#06ad56 }` 等——覆盖需要对抗 body 内联变量（见 §3.4 特异性说明），代价是每次主色调整都要同步手改 CSS。

⚠️ 已知取舍（记录在案）：#07c160 上白字的按钮文案对比度仅 ≈2.4:1（< AA 3:1 大字号门槛）——这是微信绿作为全行业品牌按钮绿的通病，客户端也在沿用；缓解手段是 label ≥600 字重 + ≥15px。如未来要求严格达标，备选把按钮背景压至 #05a352 一档。

---

## 3. Custom CSS（配套文件 `logto-signin-custom.css`）

要点速览，选择器细节和完整规则见 CSS 文件注释：

1. **字段**：`customCss`（任意字符串）。手工入口 = Console > Sign-in & account > Branding > Custom CSS，左侧编辑右侧实时预览；保存后点 Live Preview 全页面检查（出处 §6-1）。
2. **DOM 对抗规则**：Logto 用 CSS Modules，类名带 hash——官方示例统一用尾匹配属性选择器，如 `div[class$='viewBox']`、`img[alt='logo']`、`button[type='submit']`（出处 §6-1 示例 + §6-6 组件源码提示）。
3. **已实证的真实 token（出处 §6-5/§6-7）**：按钮组件 = 高 44px、padding 0 unit(4)=16px、圆角 `var(--radius)`、背景 `var(--color-brand-default)`、文字 `var(--color-static-white)`；状态组 `--color-brand-hover/-pressed`；禁用 `--color-bg-state-disabled` / `--color-type-disable`。
4. **特异性注意**：品牌三兄弟变量是运行时写到 `<body>` 的内联 style（use-color-theme.ts `document.body.style.setProperty`）——`:root` 层面的同名覆盖**无效**；要改单元素只能用更高特异性规则直指 background/color（即 §2.2 方案 B 的实现难点）。
5. **glass-control 翻译**（sapphire.css 实测 token）：控件一律 `backdrop-filter: blur(10px) saturate(1.2)`；亮态 bg `rgba(255,255,255,0.45)` / 边框 `rgba(255,255,255,0.40)` / 顶缘高光 `rgba(255,255,255,0.50)`（inset shadow）/ 底缘 `rgba(0,0,0,0.08)`；暗态 `0.05/0.08/0.08 + rgba(0,0,0,0.25)`。主按钮用品牌玻璃变体 `rgba(7,193,96,0.42)` 底 + `rgba(7,193,96,0.15)` 边框（客户端发送按钮的标准翻译）。
6. **尺度**：控件 radius 8px（覆盖 Logto 默认 `--radius`）；按钮 padding-block 10px / padding-inline 16px（对齐站点 login 按钮 py-2.5 px-4 语义，同时满足按钮自身 44px 最小高度）；输入框 8px 圆角。
7. **字体栈**：`-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif`，作用于 body 与表单控件。
8. **暗色切换方式**：CSS 草稿按官方明示行为（跟随系统偏好）使用 `@media (prefers-color-scheme: dark)` 翻转自定义变量；同时文末附了一段**注释掉**的 `.dark` / `[data-theme='dark']` 替代块——因为 theme 挂载 DOM 形态（class vs attribute）未能从静态源码确证，见确认项 S2。

---

## 4. 登录页标语候选

对照站内语感（Hero：「Agents that render. / An agent for everyone.」会渲染的智能体/每个人的智能体）——短陈述句、零形容词堆砌、功能向。

| | 主选 | 备选 |
|---|---|---|
| **EN** | Continue to your agents. | One account, every agent. |
| **中文** | 继续与你的智能体协作。 | 一个账号，所有智能体。 |

- 主选直白接续式（登录动作的心智），备选强调多 agent 产品事实。
- 克制底线：不用感叹号、不用营销词（free/powerful/seamless 类）。

**应用机制（实测确认项 S3）**：新版 signInExperience schema 无 slogan 字段（§6-3 源码实锤），且官方文档声明浏览器标题改为按流程自动取名而非常规自定义标题（§6-2 原话引在 §5）。落地路径二选一：
- a) customCss 注入标语文本（`.viewBox` 区域 ::before 定位排版）——CSS 文件尾部附了草案段（默认注释掉）；
- b) 若部署镜像版本仍带旧版 slogan/标题配置项则在 Console 直接填。
执行阶段先用 GET `/api/sign-in-exp` 看返回对象里有无残留可用字段再定。

---

## 5. 官方原文引用（关键裁定依据）

> "**Dark mode**: Enable dark mode to automatically adjust the sign-in page's appearance based on the user's system preferences." —— match-your-brand（出处 §6-2）

> "There are some limitations for images: they must be under 500KB and in SVG, PNG, JPG, JPEG, or ICO format. … Uploading a square image is recommended … A dark mode version of the logo can also be uploaded." —— match-your-brand（§6-2）

> "You also can use the Management API `PATCH /api/sign-in-exp` with body `{ \"customCss\": \"arbitrary string\" }`." —— custom-css（§6-1）

> "the browser title for different flows (Sign in/Sign up/Forgot password, etc.) is now used instead of a custom title." —— match-your-brand（§6-2）

---

## 6. 出处链接列表（全部实抓验证可访问，2026-08-27）

| # | 来源 | URL | 采信内容 |
|---|---|---|---|
| 1 | Logto docs · Custom CSS | https://docs.logto.io/customization/custom-css | Console 路径、CSS-only、优先级顺序、CSS Modules `[class$=]` 规则、Night City 官方示例的选择器用法、PATCH /api/sign-in-exp + customCss |
| 2 | Logto docs · Match your brand | https://docs.logto.io/customization/match-your-brand | 品牌/暗色配色槽、logo 与 favicon 双主题变体、500KB/格式约束、方形建议、Dark mode 随 OS、浏览器标题行为 |
| 3 | logto-io/logto master · schemas foundations jsonb-types sign-in-experience.ts | https://github.com/logto-io/logto/blob/master/packages/schemas/src/foundations/jsonb-types/sign-in-experience.ts | `color{primaryColor,isDarkModeEnabled,darkPrimaryColor}`、`brandingGuard{logoUrl,darkLogoUrl,favicon,darkFavicon}` 均 z.string().url().partial() |
| 4 | 同仓 · core mocks sign-in-experience.ts | https://github.com/logto-io/logto/blob/master/packages/core/src/__mocks__/sign-in-experience.ts | SignInExperience 完整对象形状（customCss、hideLogtoBranding 等） |
| 5 | 同仓 · experience shared/components/Button/index.module.scss | https://github.com/logto-io/logto/blob/master/packages/experience/src/shared/components/Button/index.module.scss | 按钮 44px/unit(4)/var(--radius)/var(--color-brand-default)/var(--color-static-white)/disabled token 名 |
| 6 | 同仓 · experience AppBoundary/use-color-theme.ts | https://github.com/logto-io/logto/blob/master/packages/experience/src/Providers/AppBoundary/use-color-theme.ts | hover=lighten(10)/pressed=darken(10) 派生公式；变量写死在 body 内联 style；dark 主色缺省=lighten(light,10) |
| 7 | Logto OpenAPI 参考（SPA 不便深查） | https://openapi.logto.io/group/endpoint-sign-in-experience | Get/Patch sign-in experience 操作分组存在性佐证 |

> GitHub raw 抓取经本地代理完成；docs.logto.io 直连抓取成功。

---

## 7. 实测确认项清单（M2M 凭据到位后第一件事）

> **落地状态（2026-08-27 M2M 执行后回填）**：Phase-1 已生效——`color`（#07c160 / #07c160 / isDarkModeEnabled=true）+ `customCss` 全套（8.6KB，含 S2 双钩子）已 PATCH 进租户；渲染层实证：登录页 HTML 注入 `--nf-green`×10、`body[class$='_dark']`×6、`eAa6M_light` 类在位。回滚快照 `~/.nebflow/backups/logto/signinexp-before-20260827.json`。
>
> **Phase-2 已生效（同日素材到位批）**：四槽位（logo/darkLogo/favicon/darkFavicon → neblink.space/logto/*）+ customCss v2（卡片玻璃 blur(12px) saturate(1.25)+立体边缘、S6 方案A 收口删除 hover 覆盖、修正 `img[alt='app logo']` 死选择器）。渲染断言 8/8 PASS（light 用 logo.png、**dark 自动换 logo-dark.png**、favicon 同理双主题、玻璃计算样式逐项命中）。before/after 截图八张入仓 `docs/Nebflow/assets/logto/screenshots-phase2/`。

| # | 事项 | 动作 | 结果 |
|---|---|---|---|
| S1 | logo/favicon 各槽实际渲染显示尺寸 | Live Preview DevTools 查 `img[alt='logo']` / link[rel~=icon]，必要时补倍数图 | ✅ 渲染链路实证（src 断言 light/dark 各自命中 448px/64px 素材）；像素位精确测量未做——448=64px/格×7 对任何 ≤150px @3x 位均富余，如出现锯齿再补倍数图 |
| S2 | 暗色主题挂载形态：`html.dark` / `[data-theme=…]` / 纯 media query | 部署上开启 isDarkModeEnabled 后看 DOM；决定启用 CSS 文件中哪一段暗色块 | ✅ **已解**：body hash class 尾缀 `_light/_dark`（二段再证 dark 形态实例 `GsPfC_dark`）；CSS 用 `body[class$='_dark']` 主钩子 + media query 兜底 |
| S3 | 标语落点：GET /api/sign-in-exp 返回是否含 slogan/layout 类遗留字段；否则走 CSS ::before 方案 | curl 取回核对后选 §4.a 或 b | ✅ GET 实证无 slogan/layout 遗留字段 → 走 §4.a；CSS 文件尾部草案保持注释状态（文案主选/备选待产品拍板后再开） |
| S4 | 镜像版本对 `branding.favicon/darkFavicon` 键名的兼容（无 Url 后缀这一命名随版本可能漂移） | 先 PATCH 一个空跑 payload 看 400 校验信息，或抓 version endpoint | ✅ **实测兼容**：四键 PATCH 200 入库且 favicon link href 断言双主题各自命中——本镜像键名与 master schema 一致 |
| S5 | API 写 URL 槽位的托管前提：图床地址必须公网可达（schema 是 url() 校验）——Console 手动上传才自带托管 | 决定挂 neblink VPS 静态路径或临时 OSS；assets/logto/ 产物可直接上传 | ✅ **两阶段收官**：测试域素材经官网 public/ 上线后 PATCH 四槽成功，零临时图床 |
| S6 | 派生色观感目检（hover 提亮方向是否符合预期，方案 A/B 二选一收口） | Live Preview 全页面走查 light+dark | ✅ **收口=方案 A**（Manager 建议）：hover/:active 覆盖规则从 CSS 删除，接受 Logto 派生（pressed≈#06ae56≈客户端 hover #06ad56） |

## 9. customCss 能力边界（如实记录，BYO-UI 升级评估输入）

1. **「Powered By Logto」水印移除不可行（customCss 层面）**——双证据：
   - 配置层：OSS 构建 `PATCH hideLogtoBranding:true` 直接 400 `"Hide Logto branding is not supported in this environment"`；
   - DOM 层：徽标带 `<style data-logto-signature-guard="true">` 注入的双属性选择器 + `!important` 强制回拉 display。
   要彻底移除只能升级 **BYO-UI 自绘登录页**（customUiAssets 托管自建 UI，API 流程照旧）。
2. 卡片容器等关键节点是 CSS Modules hash class（`vJ4aC_wrapper` 等）——Logto 镜像升级可能漂移。相对稳定的钩子只有少量官方语义类：`.logto_page-container/.logto_main-content/.logto_branding-header/.logto_signature` 与属性选择器（`img[alt='app logo']`、`button[type='submit']`、`[class$='_inputField']` 尾匹配族）。
3. 未盘点的深层组件（手机区号下拉浮层、MFA 面板、忘记密码弹层等交互浮出物）类名同样 hash 化——本次不覆盖也不猜；出现视觉违和再定点处理。
4. 直开 `/sign-in` 会 302 到 `/unknown-session`（无 app 上下文壳）——所有验证必须走 `/oidc/auth` 完整启动链（本次截图即此口径）。

---

## 8. M2M 执行草稿（凭据到位后再动，当前不执行）

```
# 读现状
GET   {logto}/api/sign-in-exp          # Authorization: Bearer <M2M token>

# 写品牌（URL 托管确定后）
PATCH {logto}/api/sign-in-exp
{
  "color": {
    "primaryColor": "#07c160",
    "isDarkModeEnabled": true,
    "darkPrimaryColor": "#07c160"
  },
  "branding": {
    "logoUrl":     "<host>/logto/logo.png",
    "darkLogoUrl": "<host>/logto/logo-dark.png",
    "favicon":     "<host>/logto/favicon.png",
    "darkFavicon": "<host>/logto/favicon-dark.png"
  },
  "customCss": "<logto-signin-custom.css 全文>"
}

# 回滚：逐字段 PATCH 回原值（GET 快照先行存档）
```

注意：PATCH 为部分更新语义的对象合并——务必先 GET 存档原始值；测试环境先行。
