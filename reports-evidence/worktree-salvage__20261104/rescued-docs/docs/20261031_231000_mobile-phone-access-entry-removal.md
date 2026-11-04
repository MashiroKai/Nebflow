---
type: fix-report
status: published
date: 2026-10-31
scope: 手机端联系人页「手机接入」入口撤除（仅手机端；桌面同入口不动）
---

# 手机端联系人页顶「手机接入」入口撤除批（2026-10-31 22:55 作者令）

> 令：联系人栏顶部的手机接入行/卡去掉，仅手机端；电脑端如显示同入口不动。连带清该入口的专属样式/i18n（无他处引用时）。截图取证，相关 spec 全绿，回归合入 main。

## 撤除面（手机端整链，桌面零触碰）

入口行唯一 push 路径 = 联系人页顶行 → `nf:mobile-social-entry` 事件环 → navController `pushPage('social')`；social 详情页的全部生命周期腿都只服务该入口（PAGES 注册 / overlay 面收编 / 外部关闭对账 / 弹栈关闭）。撤除 = 整链摘除，不留不可达死码：

1. **`js/mobile/socialFaces.js`**：`ensureSocialEntry`（入口行本体 + 事件外发）整函数摘除，`ensureSocialPage` 联系人臂不再调它；`createIconsIn` import 随之退役（全文唯一使用点）。头注/函数注同步记批。
2. **`js/mobile/navController.js`**：PAGES 表 `social` 注册行（zh「手机接入」/en「Phone Access」内联 i18n 随行的唯一载体）、`overlayFaceByPage.social`、`ensurePanelContent` 的 `case 'social'`（clickDesktopBtn('social-btn') 腿）、弹栈关闭 `case 'social'`、`CLOSE_WATCH` 的 social 对账行、`nf:mobile-social-entry` 监听腿——六处全摘。栈模型头注两个清单同步抹「手机接入」。
3. **`css/mobileLayer.css`**：mobile 作用域三条伴生规则零残留死样式清除（`#social-modal` 收编几何选择子、`#social-overlay` 遮罩 pointer-events、`#social-modal-close` 窗头钮退场）——撤除后手机层 `#social-overlay` 永不可达（桌面活动栏整栏 `display:none`），三条规则条件永假。头注三处清单/块注同步。
4. **`tests/mobile-dock.spec.mjs`**：D6 验收换代——原「入口行 → social 页 → 返回」正向链改为撤除判据：`.mf-social-entry` 0 个、`[data-page="social"]` 页 0 个、已撤事件环外发 `nf:mobile-social-entry` 不触发 push。
5. **连带修复（撤除暴露的既有暗伤）**：`mobileLayer.css` 联系人页搜索提交键让位——入口行原行高偶然把桌面搜索块（含右端 `.fm-search-btn`）垫到浮搜索钮 footprint 之下方；撤除后提交键落进 fab 悬浮带（top16+right16、48px、z20）被截走点按（F3 几何探针实证：btn y18 全覆盖于 fab y16-64，命中 = fab）。现给 `.fm-search` 行显式 `padding-right:72px`（fab 占位 64 + 8 呼吸，mobile 作用域桌面零改动），`mobile-fab-search.spec.mjs` F3 复绿（5/5）。回归批另两红均为既有环境件：`mobile-p3-reminder` T4 = 零点窗时间翻车（main 基线同红实证）、`mobile-pop-canvas-open` D1 = 串行负载偶发（本分支 3/3 复跑全绿）。

## 不动的面（桌面同入口 + 相邻功能）

- 桌面 `#social-btn`（index.html 活动栏 smartphone 键）/ `#social-overlay` / `#social-modal` / socialPanel 渠道配置面：零字节改动，点键开面行为原样（见证据截图）。
- `socialSearch.js`（消息/联系人页右上浮搜索钮）名字带 social 但属搜索批，不触碰。

## 验证

- `node scripts/check-js-types.mjs`：PASS（344 < 基线 350，撤除即降基线）。
- `npx playwright test tests/mobile-dock.spec.mjs`：21/21 全绿（含换代 D6）。
- 回归：mobile-*.spec.mjs × 21 件 + friend-*.spec.mjs × 8 件 + rcptcode-friendterminal = 88 用例，82 绿；3 红处置：F3 撤除连带暗伤（已修复复绿）、p3-T4 零点窗时间翻车（main 基线同红）、D1 负载偶发（3/3 复跑绿）。
- 截图取证：`evidence/20261031_phone-access-entry-removal/`——手机联系人页顶无入口行（页首行 = 新的朋友/设备/发起群聊）、桌面活动栏 social 键在 + 点开社交接口面；两端 probe 零 console error。

关联：[[20261030_084500_dock-message-badge__delegate-nebflow-12dfb591]]（dock 批主 spec D6 判据所属批）、[[20261031_193000_弹窗退场批__delegate-nebflow-4c0dfd9f]]（social 详情页同为收编进页面壳的层级页先例）。
