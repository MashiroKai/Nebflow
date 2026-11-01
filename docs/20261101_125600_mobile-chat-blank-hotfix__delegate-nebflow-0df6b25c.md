---
type: batch
status: final
date: 2026-11-01
topic: 手机端消息区空白急诊批——#todo-page 手机层铺平规则漏收编作用域，在流挤塌 #chat（聊天根消息流/主对话整片空白）
---

# 手机端消息区空白急诊批（2026-11-01，作者 12:03 实拍 IMG_1660）

关联：作者 12:03 报障（手机端停在消息 tab，中部整片空白，桌面同账号正常）；待办体系改版批 `e51dabcdd`（引入 #todo-page 与本条铺平规则）；dock 批 mobileLayer.css「收编面统一页面化几何」的 `> ` 收编作用域先例；手机急诊批巡检 harness `tests/mobile-emergency-shots.mjs`（本次复现/复验主工具）；证据目录 `evidence/20261101_mobile-chat-blank-hotfix/`。

## 根因（文件：行）

`src/main/resources/web/css/todo-page.css` 改版批原形（256-270 行）：

```css
body.mobile-layer #todo-page { position: static; transform: none; width: 100%; height: 100%; ... }
```

这条规则的本意 = 「手机端收编进 pushPage 详情页后，页壳退役为满铺页面体」。但它钉的作用域是 `body.mobile-layer`（手机层恒真），**没有钉收编落点**。后果链：

1. #todo-page 的桌面家 = `#main` 直子（index.html:827，与 #reminder-panel/#daemon-panel 同层）；未收编时它一直住在那里。
2. 手机层激活 ⇒ 上述规则生效：`position: static`（覆盖基规则 `#todo-page { position: fixed }`，特异性 (1,1,1) > (1,0,0)）+ `height: 100%` ⇒ 面板变成 **#main（flex column）里的 702px 在流块**。
3. `#chat` 是 `#main` 里 flex:1 的兄弟 ⇒ 被挤成 **142px 残条**（实测几何：#todo-page y=0 h=702 static；#chat y=702 h=142）。
4. 视觉 = 聊天根（中键 home）消息流整片不可见——历史帧照常到达、.row 照常建（rowCount=12、rowsVisible=12），只是容器塌了；任务钮/搜索钮/输入卡/dock 全正常 ⇒ 作者实拍与「一行会话都没有」的描述完全吻合。
5. 桌面不受影响（规则挂 `body.mobile-layer`），与「桌面端同一账号有数据」一致。

判词：**JS 零报错、条件渲染零缺席、数据零缺失——纯 CSS 在流挤塌**。修前手机 emulation 全链（dev 树/生产 bundle 形态/WebKit）均可复现，`chatH=142` 为机械判据。

## 修法（唯一改动：todo-page.css 尾块两选择器加收编作用域）

```css
body.mobile-layer #mobile-pages .mobile-page-body > #todo-page { ... }   /* 原 body.mobile-layer #todo-page */
body.mobile-layer #mobile-pages .mobile-page-body > #todo-page.open { animation: none; }
```

与 dock 批 mobileLayer.css「收编面统一页面化几何」对 `#settings-modal/#reminder-panel/...` 九面的 `body.mobile-layer #mobile-pages .mobile-page-body > X` 作用域逐字同族（该先例块本就漏了 #todo-page——todo-page.css 自带规则，属双处维护走样）。收编态（在页面体内）命中规则逐字不变；未收编态（桌面家）回归 `position: fixed + visibility:hidden` 基规则 = 出流，#chat 恢复满高。

## 验证（全部实拍/实跑，证据目录 11 张）

- 修前复现：390×844 Chromium + `mobile-emergency-shots.mjs` 同款 12 条历史夹具 ⇒ chatH=142、消息流不可见（`before-chat-root-blank.png`）；WebKit（pw webkit-2336，iOS Safari 同源）+ 本地真服务器（http+ws mock，登录态+会话行夹具）同塌。
- 修后：`chatH 142 → 844`（dev 树与 `build/web-dist` 生产 bundle 形态一致）；WebKit boot 聊天根 + 点 dock 消息键 ⇒ 会话页（待办事项入口行 + 设备/好友/群 3 行）零 console error（`after-webkit-*.png`）。
- 全量巡检：`tests/mobile-emergency-shots.mjs` 22 张（5 面 × 亮暗）零 console error，聊天根/消息页几何与 10-31 22:08 急诊批 after 样张同形（`after-emergency-battery.png`）。
- 桌面回归（1280×800）：#chat 满高 800；#todo-page 在桌面家 = fixed+hidden（出流）✓；消息面板 = 待办入口行 + 会话行 ✓；点入口行 = #todo-page 玻璃 overlay（fixed/visible/560px）✓（`after-desktop-*.png`）。
- spec：`todo-split.spec.mjs` 6/6（含 ⑥ 手机层级下钻收编腿——收编态铺平语义未被本修破坏）；`mobile-dock.spec.mjs` 21/21；`mobile-v3.spec.mjs` 7/7；`cognition-phase2.spec.mjs` 独立跑 ALL PASS（R7a/R7b 零 pageerror/零 console）。
- 构建闸：`scripts/build-web.mjs` OK（orphans 0；css concat 新指纹 app-3e136591.css）。

## 边界

- 只动 todo-page.css 尾块两个选择器 + 块注，零新样式零新属性值；桌面规则零触碰；在飞批（#1270 长按退役、#1272 待办圆框+logo）工作区/分支未触碰。
- 遗留观察（非本批范围）：mobileLayer.css 收编统一块与 todo-page.css 自带手机规则构成双处维护——后续若 #todo-page 收编几何要动，两处需同步或收编进统一块。
