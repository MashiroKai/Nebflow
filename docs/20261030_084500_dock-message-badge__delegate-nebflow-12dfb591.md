---
type: fix-report
status: published
date: 2026-10-30
scope: 手机 dock 消息页（js/mobile/socialFaces.js + js/mobile/navController.js + css/mobileLayer.css）
---

# 手机 dock 消息页通知段退役 + 消息键数字角标批（作者令 2026-10-30 08:29）

作者令（附手机 dock 消息页截图 `evidence/dock-center/messages-light.png`，逐字）：

> 「这个图的消息的底部，通知是干嘛的，我觉得不用显示。就像微信一样就行了，有新消息在消息按钮的右上角加数字角标就行。」

## 结论（三行）

1. **通知段整段退役**：`js/mobile/socialFaces.js` 的 `ensureNotifySection` / `renderNotifyList` / `notifMo`（站内通知镜像段 + MutationObserver 镜像腿）全部摘除，`ensureSocialPage('messages')` 尾调用一并删掉；`css/mobileLayer.css` 的 `.mf-notify-sec/.mf-notify-cap/.mf-notify-list/.mf-notify-empty/.mf-msgrow*` 八条样式随段落摘除（`mf-msgrow*` 只有该段使用，零孤儿）。该段文案原本走 `tt('通知'/'暂无消息通知')` 内联字面（不经 locales 表），因此**locales 零词条改动**——已 Grep 确认 `zh-CN.js`/`en.js` 无对应键。
2. **消息键数字角标**：`navController.applyDockBadges` 原先把桌面徽标当**布尔**镜像（纯红点）。现改为消息键写**读数字面 + `.is-count` 形态类**，读数与归一（`>99 ⇒ 99+`、0/空串 ⇒ hidden）**全部沿用桌面 `#messages-badge`**（`messages.js:434 updateBadge` → `activityBar.js:411 setActivityBadge` 已是唯一归一实现），手机层零换算、零第二判据。联系人键保持布尔红点（该键语义 = 好友待处理请求，非「新消息」，作者令只针对消息键）。
3. **通知数据去向（不留孤儿）**：源头 `notificationBanner.js addNotification` 共四类调用（`reminder` main.js:1652 / `device-mail` main.js:2837 / `task` scheduled-task.js:499,566,586 / `conn` ws.js:1145）照旧写 `state.notifications` 并渲染进 `#notification-banner`；该节点位于 `#top-overlays`（index.html:345-350）内，**手机层从未被隐藏**——聊天根顶带仍可见。退役的只是它的移动镜像面，通知本体与其唯一展示面无损。

## 落点（文件:行）

| 文件 | 行 | 改动 |
|---|---|---|
| `js/mobile/socialFaces.js` | 15-22 | 文件头注释：通知段口径改为「退役」+ 数据去向说明 |
| `js/mobile/socialFaces.js` | 40-42 | 删 `notifMo` 模块变量 |
| `js/mobile/socialFaces.js` | 109-116 | `ensureSocialPage` 删 `ensureNotifySection(body)` 尾调用 |
| `js/mobile/socialFaces.js` | 159-158 | 删 `ensureNotifySection` + `renderNotifyList`（56 行整段） |
| `js/mobile/navController.js` | 1086-1115 | `applyDockBadges`：消息键写数字 + `.is-count`；幂等写回守卫 |
| `css/mobileLayer.css` | 369-412 | dock 徽标块：纯红点基线 + `.is-count` 数字胶囊形态（暗/亮档描边 = dock 底色） |
| `css/mobileLayer.css` | 1216-1219 | 删通知段八条样式（含 `.mf-msgrow*`），留一段退役说明 |
| `js/mobile/bootstrap.js` | 19-23 | 架构注释同步（通知段退役 + 角标口径） |

## 🔴 本轮实测踩到的坑（必记）

`applyDockBadges` 的写回**必须幂等**。`watchDockBadges` 在 `document.body` 上挂了
`childList: true, subtree: true` 观察器；`b.textContent = x` 无论值是否相同都会重建子节点，
于是「观察回调 → 写 textContent → 触发观察回调」自激成无限循环：D3 由 0.8s 涨到 **3 分钟
超时**、页面假死（Chromium renderer 吃满）。修法 = 同值不写（`hidden` / `textContent` 各
加一道 `!==` 守卫），并在函数注释里把这根因果写死。第二条：`.dock-badge[hidden]` 的
`display:none` 被 `.is-count` 的 `display:flex` 以特异度压过 ⇒ 清零后角标仍可见；该规则
补 `!important`（与 `mobileLayer.css:523` 页面体 `[hidden]` 守卫同族口径）。

## spec 读数（挂现役 `tests/mobile-dock.spec.mjs` 族）

| 相位 | 读数 |
|---|---|
| 修前基线（HEAD `9a1e6a8e4`） | **12 passed (18.1s)** — D1/D2/D3/D4/D5(旧「保留通知段」口径)/D6/D7/D8/D9/D10/D11/D12 |
| 修后 | **14 passed (44.2s)** — D5 反转为「无通知段」，新增 D13（数字角标形态/计数/99+/清零）+ D14（真实未读链 1→清零） |
| 同族回归 | mobile-v3 / mobile-canvas-sheet / mobile-topline-clear / mobile-projects-single-column：**20 passed (1.0m)** |
| 社交面回归 | social-media-unify / social-panel / projcreate-fab：见下 |

### 新钉断言

- **D5（反转）**：消息页 `.mf-notify-sec/.mf-notify-cap/.mf-notify-list/.mf-notify-empty`
  计数 = 0、`.mf-msgrow` = 0、页面文本不含「通知」「暂无消息通知」、页面体最后子节点 = 封存卡、
  `#notification-banner` 仍在（源头非孤儿）。
- **D13（数字角标，作者令三条逐条钉）**：
  ① 出现 —— 无未读时角标不可见（条件显示）；
  ② 计数 —— 读数 `3`/`12`/`99+` 逐字镜像桌面；形态 = `border-radius:999px` 胶囊、白字、非透明底、
  `is-count` 类在位；几何 = 右缘越过键中线 ∧ 顶缘落在键上半（「消息按钮的右上角」）；
  ③ 清零 —— `hidden + 空串` ⇒ 不可见且 `is-count` 摘除（不是显示 `0`）。
- **D14（真实未读链）**：`fm_api_mock` 会话 `unreadCount:1` ⇒ 消息键角标读 `1`（不直写徽标，
  走 messages.js 真实取数链）；点会话开窗 ⇒ `markConvRead` 清零 ⇒ 角标熄灭；返回消息页
  通知段仍为 0、会话行徽标亦归零。
