---
name: browser-session
description: 持久浏览器会话与人在环登录——用本机 Chrome（有头窗口、独立持久用户配置文件）跨调用保持登录态；检测到登录页/验证码/2FA 时把截图与状态交回会话，由用户在可见窗口人工完成验证，脚本轮询登录成功判据（URL 正则/元素选择器，可按站点配置）确认后继续；含拟人化操作原语与反爬拦截识别（识别即如实报告，不假装成功）。Use when 任务需要登录态或 SSO 平台操作、需要人工配合过验证码、需要跨多次调用保持同一浏览器会话、或需要判断页面是否被反爬拦截。
when_to_use: 需要登录后才能操作的网站（校园网订阅平台、SSO 门户）、需要人工过验证码/2FA 的登录流程、需要持久会话（登录一次后续多次复用）、需要识别 Cloudflare/PerimeterX 拦截页并如实报告时。不需要：公开页面的无状态交互与验收（control-browser / web-gui-tester）；只读取正文（WebFetch）。
audience: nebflow-project
language: zh
status: active
---

# Browser Session — 持久会话与人在环登录

本 skill 管两件事：**会话怎么活**（有头 Chrome + 持久配置文件，跨调用登录态留存）和
**登录怎么过**（人过机：检测登录墙/验证码 → 交人 → 轮询判据确认）。它是纪律件 + 可执行脚本，
与 control-browser 的交互纪律配合使用（定位、观测、失败姿态均沿其规约）。

## 一、会话模型（先懂再开）

- 浏览器 = **本机 Chrome，有头**（可见窗口是验证码人工配合的前提，禁无头跑登录流程）。
- 配置文件 = **专用持久 profile**（用户目录下 `~/.nebflow/browser-use/profiles/<会话名>/`，
  非 Chrome 主 profile）：登录一次，cookie/会话落盘，跨调用、跨任务复用。
  Chrome 新版禁止对默认 profile 开远程调试 ⇒ 本 skill 永远用独立 profile，这是特性不是妥协。
- 进程模型：Chrome 以**独立进程**存活（由 launch 以分离方式启动）；每条命令都是新 node 进程，
  经 **CDP attach** 控制后退出——浏览器、标签页、登录态原样留存。断开纪律：**绝不**在脚本里
  调用会关掉浏览器/页面的收尾（本 skill 脚本已内置此纪律）。
- 运行时：脚本依赖 playwright 包但**不依赖其浏览器**（attach 本机 Chrome，免下载 chromium）。
  缺装时按错误提示执行一次 bootstrap（装到用户目录运行时区，不进插件包、不进仓库）。
- 数据布局（全部在用户目录，测试可用 `NEBFLOW_BROWSER_USE_HOME` 覆盖）：profiles / sessions /
  screenshots / downloads / exports / config。**这些目录永不入 git**（仓库 .gitignore 亦有兜底条目）。

## 二、开工自检与命令速查

```bash
# 0) 运行时缺失时执行一次（错误信息里也会给出本路径）
node ${SKILL_DIR}/scripts/chrome-session.mjs bootstrap

# 1) 启动/复用会话（幂等；已活则直接返回端口与 profile）
node ${SKILL_DIR}/scripts/chrome-session.mjs launch --session paper --url https://example.com

# 2) 状态快照（URL/标题/登录态/反爬识别 + 截图路径）
node ${SKILL_DIR}/scripts/chrome-session.mjs state --session paper

# 3) 导航（输出 state=OK|LOGIN|CAPTCHA|ANTIBOT）
node ${SKILL_DIR}/scripts/chrome-session.mjs goto <url> --session paper

# 4) 拟人交互（点击带轨迹与节奏；输入逐字符抖动）
node ${SKILL_DIR}/scripts/chrome-session.mjs click "<selector>" --session paper
node ${SKILL_DIR}/scripts/chrome-session.mjs type "<selector>" "<text>" --session paper

# 5) 标签清单 / 关闭（关闭浏览器进程；profile 与登录态保留，下次 launch 即复用）
node ${SKILL_DIR}/scripts/chrome-session.mjs pages --session paper
node ${SKILL_DIR}/scripts/chrome-session.mjs close --session paper
```

每条命令以一行 `RESULT {json}` 结尾（机器可读）；过程行以 `STATUS` 前缀。截图必须用 `Read`
读回才算"看过"（control-browser 视觉通道铁律）。

## 三、人在环登录（核心流程）

```bash
# 步骤 1：detect —— 打开登录入口，识别页面状态，落判据交接文件与截图
node ${SKILL_DIR}/scripts/login.mjs detect <登录入口URL> --session paper \
  --success-url "<登录成功后URL特征正则>" --success-selector "<成功后可见元素选择器>"
# 输出 ASSIST {json}：含截图绝对路径、识别到的状态（LOGIN/CAPTCHA/ANTIBOT）、判据来源
```

**收到 ASSIST 后你要做的（不可跳过）**：

1. `Read` 该截图，确认页面确实是登录页/验证码（不是反爬拦截页——若是 ANTIBOT，走 §四，如实报告）。
2. 用 AskUserQuestion 通道向用户提问：说明"请在屏幕上已打开的 Chrome 窗口中人工完成
   验证码/2FA/登录"，把截图路径作为提问上下文附上（支持图片预览则内嵌；不支持则明确告知窗口已打开）。
   同时问一句预计耗时（选项式：已完成 / 需要几分钟 / 放弃），**不要**替用户输入任何凭据。
3. 用户答复后运行步骤 2。

```bash
# 步骤 2：wait —— 轮询成功判据（每轮刷新 login-watch.png，可随时 Read 查看进展）
node ${SKILL_DIR}/scripts/login.mjs wait --session paper --timeout 300
# LOGIN_OK ⇒ 继续；LOGIN_TIMEOUT ⇒ 回问用户（不臆断成功）
```

**判据配置（用户可自定义成功判据）**：写在 `~/.nebflow/browser-use/config/login-rules.json`：

```json
{"rules": [
  {"name": "example-portal", "hostPattern": "portal\\.example\\.edu",
   "successUrl": "portal|welcome|home", "successSelector": ".user-name"}
]}
```

优先序：CLI 旗标 > login-rules.json（按 host 正则匹配）> 内置弱启发式（无密码框 + URL 不像
登录页 + 未被拦）。**启发式弱，正式任务建议显式配置**。`auto` 子命令 = detect+wait 连跑，
仅适合无验证码的自动跳转 SSO 或测试。

## 四、反爬拦截识别与诚实边界

- `state`/`goto` 的输出含 guard 识别：cloudflare / perimeterx / datadome / radware / challenge-text
  （页面文案特征）。**guard.blocked 的判据是「拦截证据」而非「站点部署了防护」**：markers 命中
  且（页面近空白 <500 字 或 challenge 文案命中）才算 ANTIBOT——正常文章页内嵌 PerimeterX 探针
  脚本不构成拦截（2026-10-13 IOP 实测修订）。识别到拦截 ⇒ 状态 `ANTIBOT`，**如实报告，不得假装成功、不得重试轰炸**。
- 本 skill 的正当应对 = 真实用户配置文件 + 有头窗口 + 拟人化节奏 + **人工**通过人机检查
  （把窗口交给用户）。**不做、不声称做**任何绕过：不破验证码、不伪造指纹、不密集重试。
- 诚实边界：Cloudflare/PerimeterX 等高防护站点对自动化控制链路仍可能持续拦截
  （TLS/HTTP2 指纹、IP 信誉等在浏览器之上），此时**报告"被拦截，建议用户在窗口手动完成后
  再继续或改用人工路径"**，并把该任务点记为阻塞——这是正确结果，不是失败姿态问题。
- 同一目标连续两次失败 ⇒ 停手（control-browser §五），报确切错误与截图。

## 五、安全与使用边界（写进交付的话）

1. **只用于用户有合法访问权限的内容**（如本校订阅）；插件不提供也不建议任何越权访问。
2. **人工解决验证码**：人过机，不破机——验证码永远由用户本人完成，脚本只轮询结果。
3. **遵守站点速率**：操作串行、动作间随机延时、篇间大间隔；绝不并发轰同一站点。
4. **凭据只存本地**：登录态只存在于本机 profile；`cookies` 命令可导出（敏感！默认落
   exports 目录不打印，永不入 git、永不进报告正文）。不要把密码写进任何脚本参数或文件。
5. **不碰宿主**：宿主的 :8080 网关监听者永不触碰；自起服务用自有端口自有目录，收尾自清。

## 六、配套件

- `references/login-recipes.md` — 各平台登录判据配置示例、验证码配合话术、已知限制详表。
  写新站点的判据前先读它。
