# 登录判据配置与验证码配合（login-recipes）

本文是 browser-session 的按需 Read 面：给新站点配登录判据前读 §一；验证码配合流程与话术
见 §二；安全边界与已知限制见 §三/§四。与 SKILL.md 冲突时以 SKILL.md 为准。

## 一、login-rules.json 配置说明

文件位置：`~/.nebflow/browser-use/config/login-rules.json`（Windows 即用户目录下同路径）。
首次使用可从同目录 `login-rules.example.json` 复制改名。schema：

```json
{"rules": [
  {
    "name": "human-readable-label",
    "hostPattern": "portal\\.example\\.edu",   // JS 正则字符串，对 URL host 匹配
    "loginUrl": "https://portal.example.edu/login",  // 可选：detect 的默认入口
    "successUrl": "portal/home|welcome",       // 可选：登录成功后 URL 特征（正则）
    "successSelector": ".user-name, #logout"   // 可选：成功后可见元素（CSS 选择器）
  }
]}
```

- 匹配：按条目顺序取第一条 `hostPattern` 命中的规则。
- 判据优先序：CLI 旗标 > 规则 > 弱启发式。`successUrl` 与 `successSelector` 满足其一即成功；
  两个都配 = 双保险，推荐。
- 写判据的方法（沿 control-browser 观测纪律）：在浏览器里人工登录一次，记下登录后
  URL 的稳定特征段与登录前没有、登录后可见的元素（用户名/退出按钮），把**观测到的事实**
  写进规则。禁凭想象编选择器。
- 弱启发式（无配置时的兜底）＝「页面无可见密码框 + URL 不像登录页 + 未被反爬拦截」，
  对 SPA 门户可能误判，正式任务务必显式配置。

### 配置示例（形态模板，host/判据按目标站实测填写）

```json
{"rules": [
  {"name": "university-sso",
   "hostPattern": "sso\\.[a-z.]*univ\\.edu",
   "successUrl": "portal|dashboard|home",
   "successSelector": "[data-testid='user-menu'], .user-name"},
  {"name": "publisher-personal",
   "hostPattern": "id\\.example-publisher\\.com",
   "successSelector": "a[aria-label='Signed in']"}
]}
```

## 二、验证码配合流程（人过机，不破机）

1. `detect` 报 ASSIST 后，先 `Read` 截图，向用户确认页面状态属于哪类：
   账号密码表单 / 图形验证码 / 短信或 TOTP 两步验证 / 反爬拦截页（最后一类转 §四）。
2. 用 AskUserQuestion 向用户发提问，要点：
   - 明确「请在屏幕上已打开的 Chrome 窗口完成验证码/2FA，脚本会自动检测登录成功」；
   - 附截图路径（通道支持图片预览则内嵌）；
   - 选项式收尾：已完成 / 需要几分钟 / 放弃本次。
3. 用户答复「已完成」后跑 `wait`；`LOGIN_TIMEOUT` 时再问一次而不是臆断。
4. 插件侧**永远不**代填验证码答案、不代收短信、不识别图形码——只轮询结果。

## 三、安全边界（交付即承诺）

- 仅用于用户有合法访问权限的内容（本校订阅、本人账号）；不提供任何越权路径。
- 验证码由人完成：脚本只检测「人已通过」，不做任何识别/绕过。
- 速率：操作串行 + 随机延时；登录尝试不重试轰炸。
- 凭据本地化：登录态只落本机 profile（`~/.nebflow/browser-use/profiles/<会话名>/`）；
  `cookies` 导出文件敏感——**永不入 git、永不进报告正文、用完可删**。
- 浏览器只打用户授权的目标；宿主 :8080 网关永不触碰。

## 四、已知限制（如实登记，不假装）

| 限制 | 说明 | 正确姿态 |
|---|---|---|
| 高防护站点仍可能拦 | Cloudflare/PerimeterX 等在浏览器之上还看 TLS/HTTP2 指纹与 IP 信誉；本链路（真实 Chrome + 真实 profile）能过大部分，但不是全部 | 识别到拦截 ⇒ 报告并请用户人工通过；连续两次失败 ⇒ 停手如实报 |
| 企业/机构 SSO 变体多 | CAS/OAuth/SAML 跳转链形态各异，启发式可能中途误判 | 为该域配显式判据（§一） |
| 会话过期无提示 | cookie 过期后页面回到登录墙，脚本不会预知 | 下载/操作流程遇到 NEEDS_LOGIN 即回登录流程 |
| 多账号 | 一个会话名 = 一个 profile = 一套登录态 | 需要分账号时用 `--session` 开不同会话名 |
| 有头硬依赖 | 验证码需要人看窗口，禁无头跑登录 | 保持窗口可见；远程/无人值守场景不适用本流程 |
