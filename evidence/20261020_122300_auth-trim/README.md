# auth-trim 批 · 控制台认证面收缩 + 设置更新功能退役（作者令 2026-10-20）

两件直接落地的验收截图与 DOM 佐证：

- **①控制台去掉「退出登录」「切换账号」**：设置页账号区两键 + 切换账号弹窗 +
  knownAccounts.js 账号记忆整链退役；登录态只留**只读身份行**
  （`.neblink-account-ro`，`登录账号：{name}`）。登出/切号只保留官网门户面。
- **②设置里去掉「更新」功能**：检查更新/立即更新/进度面/绿点 + updateCheck.js
  模块 + `update.*` 键组整链退役（未来由官网统一检测更新取代，另批设计）。
  「重启服务」键与其状态行（restartStatus/restartResult 消费）保留。

## 截图清单（light 主题，登录态 mock：测试用户 / me@example.com）

| 文件 | 内容 |
|---|---|
| `01-topbar-activitybar.png` | 顶栏/活动栏新形态 —— 设置钮**无更新绿点** |
| `02-settings-btn-closeup.png` | 设置钮特写（无绿点） |
| `03-settings-full-loggedin.png` | 设置页全貌 —— 账号区只有头像 + 只读身份行，无两键 |
| `04-account-block-ro.png` | 账号块特写 —— 「登录账号：测试用户」只读行 |
| `05-about-section-no-update.png` | 关于区特写 —— 版本/连接状态/**重启服务**在位；检查更新/立即更新/进度行零残留 |

`dom-proof.json` = 截图同帧的 DOM 断言（两键 false、更新三件套 false、
restartBtn true、绿点 false、身份行文本在位）。

## 复现

```sh
node evidence/20261020_122300_auth-trim/capture-evidence.mjs
```

静态 harness（隔离静态服务器 :8187 + mock WS/API，零真实后端），
形态与 `tests/settings-cleanup.spec.mjs` 同款。
