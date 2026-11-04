---
name: website-frontend
description: Stand 官网（mystand.space）前端开发域知识——Next.js 16 App Router + React 19 + TypeScript strict + Tailwind CSS v4 @theme tokens、i18n [lang] 动态路由与 dictionaries、组件复用纪律、API Route Handlers 与 JWT 认证模式、页面文案自解释硬规则（四类禁止清单/保留清单/词典面/机械判据）、性能规范（3D/图表懒加载、next/image）、验证流（dev server/tsc --noEmit/build）。Use when 开发 nebflow-website 项目（React 组件、页面布局、API 路由、TypeScript 逻辑、页面文案与空态文案、构建问题）。
when_to_use: nebflow-website 仓库前端开发任务；简单文案/小样式调整可直接做；部署与双域发布纪律见仓库 AGENTS.md 与同插件 deploy-website。
language: zh
status: active
last_verified: 2026-09-03
---

# Website Frontend — Nebflow 官网前端开发域知识

## 技术标准

- **Next.js 16 App Router**：`app/` 目录、server/client components、layouts、loading states。
- **React 19**：现代特性（use、Suspense、优先 server components）。
- **TypeScript**：严格类型，无 any；正经 interface/type。
- **Tailwind CSS v4**：`@theme` tokens + utility classes 优先于自定义 CSS；品牌色用 CSS 变量（`--color-primary`、`--color-accent` 等）。
- **i18n**：路由用 `[lang]` 动态段；词典在 `lib/dictionaries.ts`；**永远同时支持 zh 和 en**。
- **组件复用**：新建前先查 `components/` 有无既有组件——有则复用并匹配既有模式与命名约定。
- **API Routes**：Next.js Route Handlers（`app/api/*/route.ts`）；遵循既有 auth 与错误处理模式。
- **性能**：重组件（3D、图表）懒加载；`next/image` 优化图片；最小化客户端 JS。

## 代码安全

- **XSS**：用 React 内建转义；`dangerouslySetInnerHTML` 仅在内容已消毒时用。
- **Auth**：API 路由内校验 JWT（jose）；密钥绝不暴露给客户端。
- **Env vars**：`NEXT_PUBLIC_*` 前缀只给真正公开的值（build 时烘焙进产物——测试功能标记机制依赖此语义）。
- **env 值严禁带引号**（Vercel 存裸值；`vercel env pull` 文件里的引号是 dotenv 书写格式不是存储内容）——详见仓库 AGENTS.md「部署与环境变量」。

## 自解释（文案硬规则 · 作者令 2026-11-04 12:46「好的前端设计应该是自解释的」）

通用规则（与主仓前端同源，同一条作者裁定）：**文案只承载用户当下需要的事实与决策（状态、错误、后果），不承载教学；界面的用法由界面自己说明。** 本节只写 mystand.space 官域侧的落地细则——通用规则的正文不在本包复述，口径不得与主仓各自长歪。

### 四类禁止（页面文案命中即不合，review 逐条过）

| 类别 | 可辨识特征 |
|---|---|
| **能力说明** | 用人话解释界面/设备能干什么（「可远控更新」「支持云端热更」）——能力本该由徽标、按钮态、状态点承载 |
| **路径指引** | 「到某页面 → 某菜单 → 某开关」式动线指令（zh/en 双版本同禁），如 `Open the console → Settings → Devices` |
| **行为教学** | 教用户在当前处境怎么办：host 离线、设备不可达、加载慢、更新中该等还是该刷新 |
| **重复说明** | 徽标/状态点/按钮已经把事实说完，紧随其后再用一句话说一遍 |

反例（2026-11-04 批已删，实测依据=作者截图）：个人主页设备卡每行下方挂的五行小字——`可远控更新 · 直接推送`、`需手动 · 到该设备上打开控制台 → 设置 → 设备…`（含 en 版 `Open the console on that device → Settings → …`）；`/portal` 中转页的 host 离线行为教学句。

### 保留（不许误删）

- **状态事实**：版本号 `v1.x.y`、在线/离线、最近在线时间（`Last online 1d ago`）、进行中的阶段名。
- **失败收口句**：失败原因 + 下一步（`下载失败` / `设备失联` / `校验不过` 这类失败码与收口句按事实留，不许合并成一句「出错了」）。
- **动作标签**：`更新此设备`、`重试失败项`、`关闭`——按钮名即动作，不再配「点击此按钮以…」。
- **空态指向**：空态允许一句话，但必须指向下一步动作（`在任意一台设备上登录 stand 账号，它就会出现在这里`），不是说明书。
- **tooltip 里的**事实句：`版本低于 v1.x.y，只能在本机更新`——事实不是说明书，悬浮层同样受本规则约束。

### 官域落地细则

- **双域**：同一句文案在测试域（`*.staging`）与正式域（`mystand.space`）逐条同口径；文案改动先在测试域目检，正式域按两步发布走，禁「正式域先上、测域再补」。
- **i18n 词典面**：官域文案同时活在渲染面与词典面（`lib/dictionaries.ts`、`lib/auth-i18n.ts` 的 `zh:`/`en:` 两段）——**删/改一句文案必须同批落 zh+en 词典**，留半套或留孤立键会在下一次渲染复活；只改渲染面不改词典 = 不合。
- **机械判据**：改前先跑 `tests/unit/self-explaining-copy.test.mjs`（词典面）与 `tests/e2e/settings-self-explaining.mjs`（`/settings` 渲染面），**红即不合**；改后两套全绿 **且状态事实断言仍在**（版本号/在线态/最近时间至少各一处可见）。
- **边界判例**（既像教学又像事实时怎么判）：问一句「**删掉它用户还能不能知道现在是什么状态？**」——能 → 删；不能 → 它其实是状态，留。实例：设备行末尾的徽标 `可远控更新` / `直接推送` = 状态事实（这行现在是什么能力、会不会真的被推），**留**；同屏那整行小字 `可远控更新 · 直接推送` = 同一事实的重复说明 + 能力说明，**删**。
- **正例指针**：`/settings`（`app/settings/`）与 `/portal`（`app/portal/`）两面已按本规则清理完（2026-11-04 批）——改文案前先看这两面的现状，照着它的密度改，别照旧稿。

## 验证流

- **改前**：读现状代码；查 console/build 既有问题。
- **改后**：dev server 起着（`npx next dev --turbopack -p 3000`）验证页面渲染（Pop 打开或 curl）；构建检查（`npx next build` 或 dev server 输出）；TypeScript 检查 `npx tsc --noEmit`。
- 不像样立即迭代；**无法验证时（服务没起/build 挂）必须明说**，不许声称已验证。
- 功能验证按双域纪律：需真实环境的功能（登录、设备互联）直接推 neblink.space 测试域在线验证——localhost 预览交差已废弃（2026-08-27 裁定）；正式域 mystand.space 严格两步发布。

## 交付口径

- 展示改动 before/after diff；解释 WHY 不只 WHAT；解释简洁让代码说话。
