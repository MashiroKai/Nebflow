# 小扫尾批 2026-10-22 ② — secretsPanel 眼图标单源收敛 · 渲染对照证据

改动：`js/secretsPanel.js` 私有 `EYE_SVG`/`EYE_OFF_SVG`（lucide v0.454 眼形）退役，
改走 `js/sidebar/providersUi.js` 单源 `eyeSvg`/`eyeOffSvg`（chat.js AskUser 密钥卡
2026-10-21 设计语言批已先行，设置页 provider 密码域 sidebar.js 更早）——全应用眼形
自此唯一。信息架构、揭示语义（一次 GET / 烧回占位 / 草稿纯表现层切换）零变化。

## 断言脚本

`tests/secrets-eye-converge-shots.mjs`（静态服务 + WS/REST mock + Playwright，
模式照搬 design-language-shots.mjs 先例；同一脚本 TAG=before / TAG=after 各跑一遍）。

比对口径备注：innerHTML 读回做 DOM 归一化（`<path/>` → `<path></path>`，HTML 序列化
器对非 void 元素不自闭合），故单源串先过同一归一化再比——等式成立即「同一 SVG 源 ⇒
同一 DOM ⇒ 同一光栅化像素」。

## 结果

- **after（本批，单源）**：22/22 PASS（light+dark 各 11）——行眼/草稿眼/AskUser 卡眼
  三方 innerHTML 逐字节互等且等于 providersUi 导出（DOM 归一化）；揭示/重掩码/草稿
  切换语义 + aria-pressed 全部保持；console 零错误（仅 mock 宇宙 8 条 /api/ 探针
  404 夹具噪声，脚本内列名静噪）。
- **before（HEAD，私有副本）**：恰 12 条「≠单源」断言 FAIL（lucide 眼形 ≠ providersUi
  feather 眼形），功能语义断言全 PASS——改前状态的诚实存证。

## 像素对照（本目录 before/ vs after/，sha256 前 16 位）

| 对 | 结果 | 含义 |
|---|---|---|
| `*-4-ask-card.png` before vs after | **逐字节相同**（light `b2b63a3903d1a659` / dark `c845f21e8779faf6`） | chat.js 卡面本批零触碰 → 零附带损伤 |
| `*-eye-masked/revealed.png` ×4 | 逐一不同 | 唯一预期视觉变化：lucide→feather 眼形统一（见 16px 元素级特写对） |

面板族回归：`tests/design-language-shots.mjs`（TAG=post-eye-converge）——
eye-toggle=ok / answer-gate=ok 亮暗双主题，零 pageerror。
生产 bundle：`npm run build` PASS（177 sources, orphans: 0）。
