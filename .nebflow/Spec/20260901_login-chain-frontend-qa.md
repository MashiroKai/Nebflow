# #37 登录链修复 · 前端域 — QA 验收报告（PASS 放行）

- **日期**：2026-09-01
- **验收人**：qa-frontend
- **产出者**：Frontend
- **交付**：worktree `/tmp/nb-login-frontend`，分支 `feat/login-chain-frontend`，commit **65187d69**（基于 main @1f681c4f）
- **改动**：4 文件，全在 web/ + scripts/：
  1. `js/brand.js` — 新增 `getProfileUrl()` + typedef
  2. `js/activityBar.js:251` — `window.open(getProfileUrl(), '_blank', 'noopener')`
  3. `js/neblink.js:64-73` — 删 `githubLogin` 字段映射
  4. `scripts/verify-profile-url.mjs` — 新增 7 例契约守卫（已 git-tracked）
- **验收基座**：隔离真实 http4s 实例 `http://localhost:8095`（worktree /tmp/nb-login-frontend，java PID **31422**，health 200）
- **宿主保护**：8080 = PID 66194 我宿主全程未触碰；8095 已 PID 验身为独立 java 实例（cwd=`/private/tmp/nb-login-frontend`）
- **结论**：**PASS —— 放行。**

---

## 验收条件逐项核对

### ① profile 链接：登录态点头像 → 注入 profileUrl；未注入 fallback；绝不出现 neblink.example ✅
独立契约矩阵（真实实例、fresh module eval 每例）7/7：

| 场景 | 结果 | 占位符泄漏 |
|---|---|---|
| debug 注入 `https://neblink.space/profile` | **原样透传** | 无 |
| publish 注入 `https://nebflow.space/profile` | **原样透传**（env override 穿透） | 无 |
| 字段缺失 | `https://neblink.space/profile` | 无 |
| 无 __BRAND__ | `https://neblink.space/profile` | 无 |
| javascript: | → fallback | 无 |
| http: | → fallback | 无 |
| 空串 | → fallback | 无 |

**任何结果不含 `neblink.example`**（零占位符泄漏断言通过）。

### ② 打开方式：window.open 第三参 `noopener` ✅
LOGGED-IN 分支真实点击（route 强制 loggedIn=true）：
```
window.open("https://neblink.space/profile", "_blank", "noopener")
→ thirdArg="noopener" ✓（profile 页拿不到 opener 句柄）
```
源码 activityBar.js:256 亦确认第三参为字面 `'noopener'`。

### ③ 恶意值防护：javascript:/http:/空串一律 fallback，不导航到非 https ✅
brand.js `getProfileUrl()`：
```js
return typeof url === 'string' && url.startsWith('https://') ? url : PROFILE_URL_FALLBACK;
```
`PROFILE_URL_FALLBACK = 'https://neblink.space/profile'`。契约矩阵三例 hostile 全部 fallback（见 ①）。

### ④ GitHub 清理：web/ 无 GitHub 登录入口/按钮/回调，state 无 githubLogin ✅
```bash
grep -rni github src/main/resources/web --include=*.js | grep -v "/vendor/"
# 命中全部为注释：
#   neblink.js:64,66   — "githubLogin is deliberately NOT mapped: Logto-only now"
#   usageDashboard.js:2,756 — "GitHub-style heatmap" 措辞（无关）
```
- **零 `githubLogin` 标识符**在业务代码（neblink.js:64 仅为注释）
- 排除 `vendor/monaco/*` 第三方 license 注释后，无入口/按钮/回调
- neblink.js 已删 `githubLogin: d.githubLogin || ''` 字段映射

### ⑤ 登录弹窗：Logto PKCE 全程 + 玻璃面板视觉一致 ✅
- **PKCE primary**：neblink.js 有完整 `startPkceLogin`（Logto Authorization Code + PKCE）+ `pollPkceState` 轮询循环；注释明确"Logto-only now"、"not configured → 404 logto-not-configured → fallback startDeviceFlow"
- **device-flow fallback**：`startDeviceFlow` 仍在，provider 无关（RFC 8628）
- **玻璃样式未改动**：`git diff` 确认仅动 `import` + `bindAvatar`，未触碰 `injectLoginModalStyles` / `showLoginModal`
- **真实渲染（两主题）**：
  - 亮色：`backdrop-filter: blur(24px) saturate(1.15)`、`background: rgba(255,255,255,0.55)`、fixed 居中 z-1000、无 overlay 遮罩、refraction 高光 ✓
  - 暗色：`blur(24px) saturate(1.15)`、`background: rgba(24,28,38,0.68)`、同理 ✓
  - 截图 `/tmp/qa-login-frontend/modal-light.png` / `modal-dark.png`，无 console/page error

### ⑥ 头像显示：前端消费 `st.device.avatarUrl`，不阻断 ✅
- `activityBar.js:567` `renderAvatar()` 读 `st.device?.avatarUrl`
- `neblink.js:74` 映射 `avatarUrl: d.avatarUrl || ''`
- 依赖 Backend C1/C2 落字段，前端无涉（交付声明一致）

---

## 附加独立核验
- **checkJs 门禁**：`326 = baseline`，`checkJs PASS: no new errors above baseline` ✓
- **verify-profile-url.mjs**：独立跑 **7/7 PASS**，exit=0，零占位符泄漏 ✓
- **静态资源合同**：真实实例 **278/278 PASS**（全部 web asset 200）✓
- **改动范围**：`git diff 1f681c4f..HEAD --stat` = 4 文件 / 105+/8-，全在 web/ + scripts/，零 Scala ✓
- **模块图**：登录弹窗/页面加载控制台零导入错误（过滤 403/WS/api 未认证噪音后）✓
- **本分支 HEAD**：65187d69（feat/login-chain-frontend），main @1f681c4f 为祖先 ✓

---

## 结论
**PASS —— 放行。** 六项验收条件全绿：profile 链接走注入字段（零 neblink.example）、window.open 带 noopener、恶意值全 fallback、GitHub 清理干净（零标识符）、Logto PKCE 全程 + 玻璃样式未改视觉一致、前端正确消费 avatarUrl。

安全：8095 = java PID 31422（cwd=/private/tmp/nb-login-frontend），8080 = 66194 宿主全程未触碰。隔离实例确认在跑新代码（served brand.js 含 getProfileUrl，非快照陈旧）。

已知边界（交付已声明，接受）：① PKCE scope 在 Scala 侧（LogtoAuthCode.scala:100）非前端点；② 头像端到端需 Backend C1/C2 + 重启实例，前端无可验点；③ 官网 repo 不在范围。

报告：~/.nebflow/docs/Nebflow/20260901_login-chain-frontend-qa.md
