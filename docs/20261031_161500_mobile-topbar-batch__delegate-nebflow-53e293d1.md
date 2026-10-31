---
type: fix-report
status: published
date: 2026-10-31
scope: 手机端工作台顶栏批（navController.js / meFace.js / sidebar.js / activityBar.js / mobileLayer.css；仅手机层，桌面零触碰）
---

# 手机端工作台顶栏批（作者令 2026-10-31 15:31，四件）

作者令（逐字四件）：

> ① 手机端各页顶部标题文字全去掉：工作台的「工作台」大字+胶囊、消息页顶部、联系人页顶部的标题文字——全部移除（内容直接上移吃回空间），不只工作台一处，三个 tab 页统一。
> ② 设置里的用户头像入口挪到工作台顶部：工作台页顶部放用户头像（圆形，与设置页现头像同源同态），设置页里原头像位同步撤掉。
> ③ 工作台列表里的「设备」行去掉——设备已在联系人里展示，工作台不再重复（确认联系人页设备区完好后再删）。
> ④ 点头像进个人主页：学官网（link.mystand.space）个人界面的配置方式——用户名、头像、绑定等配置项，苹果层级式点进下一层铺满屏配置、左上返回。复用官网个人页已有字段与接口，不新造配置面。

## 结论（四件对应四段）

1. **根页顶栏退役**：`navController.js buildSection` 对 dock 根页（消息/联系人/canvas/工作台同族，批② 2026-10-31 同族口径）不再建 `.mobile-page-header`——标题胶囊与页头整体移除，内容上移吃回 ~46px；安全区顶距改由页面壳自管（`.mobile-page-nohead { padding-top: env(safe-area-inset-top) }`，真机留刘海净空、桌面视口 0 高）。详情页顶栏逐字不变（「<」+ 标题胶囊 = iOS 层级导航面，个人主页层级复用它）。范围判读：作者令枚举的三个 tab 页 + canvas 同族；**详情页标题保留**（「三个 tab 页统一」的枚举面 + ④ 的层级页明确要左上返回的壳）。
2. **头像搬家**：工作台页顶部新增 `.mf-me-entry` 头像入口卡（圆形 40px 双态框 = 设置页 `.settings-avatar-frame` 同族缩档；logo/photo 双态、绘制走 `avatarViewState` + `paintAvatarSlot` 就绪门 = 与设置页/Activity Bar **同池同源**，失败 URL 进共享 latch）。名/副行 = neblink status 读数（displayName/email），状态拍订阅重绘（`onNeblinkStatus`）。点击 = 账号统一行为：已登录 → push `me` 个人主页；未登录 → `showLoginModal`（activityBar 导出口径，同步手势栈内调用）。设置页（手机层）渲染期按 `body.mobile-layer` 判别撤掉 `#settings-avatar-entry` 与 `#settings-devices-slot`——**桌面逐字不变**（`sidebar.js renderSettings` 条件模板）。
3. **设备行撤除**：设置账号段的设备区（friendseal ①-A 许可槽位 `#settings-devices-slot`，值统一批后一直是「工作台列表里的设备行」）在手机层整槽不渲染；设置页账号块保留 neblink 登录态只读行（`登录账号：…`）。联系人页设备段**零触碰**（`.fm-devices-entry[data-devices-entry="1"]` 完好，`mobile-topbar.spec.mjs` T2 钉在场）。
4. **个人主页层级**：新 `js/mobile/meFace.js` + PAGES 注册 `me / me-user / me-avatar / me-bind` 四个详情页——苹果层级式点进下一层铺满屏、左上「<」返回 = **既有手机详情页壳**（pushPage/popPage，零新建导航语义）。页面体：me = 资料卡（大头像双态 + displayName/email）+ 账号分组行（用户名/头像/绑定，右值读数）；子页 = 只读字段行（用户名=displayName、邮箱、设备绑定=本机+n 台、登录态）。**数据纪律**（作者令「复用官网个人页已有字段与接口，不新造配置面」）：全部字段 = `/api/neblink/status` 既有账号读数（neblink.js 单点访问器，零新端点）；一切修改 defer 到官网个人主页（`brand.js getProfileUrl()` 既有接口 + `from=client` 契约，`window.open noopener`）——客户端零账号编辑控件（与 2026-09-05 裁定「Username 统一经官网账号中心」同口径）。设备绑定只读展示，管理入口 = 联系人页既有设备段。

## 关键实现点

- **showLoginModal 导出**（`activityBar.js`）：原函数模块私有；手机层工作台头像的未登录腿必须与 Activity Bar/设置头像同一登录面（PKCE 弹窗预留依赖同步用户手势，禁动态 import 转发）。新增静态边 navController/meFace → activityBar，check-circular 无新环（既有 taskPanel↔navController 环为存量）。
- **meFace 不回边成环**：导航经 `buildMeFace(pageId, body, { push })` 由 navController 注入 `pushPage`（函数声明提升，调用时机在 ensureContainer 运行期）。
- **渲染期判别而非 CSS 隐藏**：设置页头像/设备槽在 `renderSettings` 模板层按 `body.mobile-layer` 条件输出——桌面 DOM 逐字不变（unifyfix R1b / settings-cleanup 桌面断言面零扰动）；3s 刷新 tick 的 `renderSettingsAvatar`/`mountSettingsDevices` 均为元素在场判空，缺席零成本。
- **_specs 修订**（三处存量 stale + 两处口径更新，全部是既有红/枚举面漂移，非本批回归）：`mobile-v3` T1 消息根页标题断言改为「无页头无标题 + nohead 壳标记」；`mobile-v3`/`mobile-p12` 工作台根页「<」断言（批② 起根页无返回钮，存量红）改走中键回聊天根；`mobile-canvas-sheet` T1 canvas 标题断言改 null；`settings-cleanup` T3 设备行断言对齐 unifyfix R1b 口径（槽位外零行，桌面保留许可槽）。新增 `mobile-topbar.spec.mjs` T1–T3 钉四件行为。

## 验证

- `tests/mobile-topbar.spec.mjs`（新，T1 根页顶栏退役 / T2 头像搬家 + 设置撤除 + 联系人设备段完好 / T3 个人主页层级）+ mobile-dock / mobile-v3 / mobile-p12 / mobile-canvas-sheet / settings-cleanup / unifyfix 全量见提交内报告。
- checkJs：meFace.js 零错（351 total = HEAD 存量 351，零净增）；check-circular 无新环。
- 双主题截图：`tests/mobile-topbar-shots.mjs`（工作台新顶 / 个人主页 / 用户名子页 / 设置页 × dark/light），视觉确认内容与顶栏吃回空间。

## 假设与开放项

- **① 的范围判读**为「dock 根页（含 canvas 同族）」；详情页标题胶囊保留。若作者意图「详情页标题也去掉」，改动面 = buildSection 非 dock 分支一行 + 对应 spec，十分钟内可跟。
- **④ 的「用户名」读数**：本地 status API 不暴露 NL 号（官网 Username）字段，子页以 displayName（OIDC name claim）+ email 呈现，修改统一走官网账号中心。若后续官网/引擎暴露 username 字段，me-user 子页加一行只读值即可。
- 未登录态工作台头像点击 = 登录面（与设置/Activity Bar 一致）；me 页亦提供登录键（会话中途登出时的出口）。

关联：[[20261030_084500_dock-message-badge__delegate-nebflow-12dfb591]]（dock 批基线）、[[20261029_140000_mobile-projects-single-column__delegate-nebflow-ea10c90a]]（手机层页面壳先例）。
