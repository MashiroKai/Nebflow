# REPORT — 卡片家族统一样式包批（作者 2026-11-01 16:05 令）

delegate-nebflow-67f23d42 · 分支 `delegate/delegate-nebflow-67f23d42` · 设计稿
`docs/design/20261101_162000_cards-family-design__delegate-nebflow-67f23d42.zh-CN.md`
（§1 考古 / §2 token 出处 / §4 迁移清单 / §5 铁律裁定注）。

## 交付面

1. **家族包**（新 `css/cards.css`，token `--nfc-*` + `.nfc` 族类，命名空间空闲已查）：
   - token 双档逐值转录自官网 auth 域 chunk（`0q_cyuwfuh.99.css` 实抓 2026-11-01）——
     基准实拍墨色三方对上（标题 #111827 / 正文 #9ca3af / 动作 #6b7280 / 下划线 #e5e7eb）；
   - 三腿主题解析（:root 亮档 / data-theme=dark 强制档 / prefers 回退档）同构 --st-*；
   - 组件类：`.nfc` 基准卡体、`.nfc-lockup-light|dark`（官方 lockup 双档显隐）、
     `.nfc-title/.nfc-body`、`.nfc-actions`(+`.is-center`)、`.nfc-link`（下划线文字动作）、
     `.nfc-steps/.nfc-step`、`.nfc-error`（S9 配方自 modal.css 迁入）；面具名
     loading/dialog/hint/offline。
2. **官方字标资产**：`web/brand/stand/lockup-light.png` + `lockup-dark.png`（mystand.space
   原字节 vendor，504×126=@3x；email 件注记「字标属于 logo——禁字体模拟」，故双图显隐
   而非 filter 反色）。伺服面 = StaticRoutes /brand/ 任意深度路由（:811-839）。
3. **迁移落点**（设计稿 §4）：
   - M1 接管弹窗 `#takeover-box`：家族卡化 + 字标行 + 家族字阶 + 动作行归位；从
     modal.css 共享玻璃三列表摘除（面板=实色非玻璃）；`takeover-blur` 维持；
   - M2 门入场加载卡 `.nf-entry-card`：+字标 +家族几何/字阶；样式体内联块迁 cards.css
     （该面只在 CSS 窗全 ok 后显示）；`display:none` 预藏线留门内联（防慢载窗裸浮）；
     清单四态机制零回翻（真实信号驱动，行类双挂 `.nf-entry-item.nfc-step`）；
   - M3 门失败卡 `.nf-boot-card`：内联字面量对齐家族值（radius 18/#e5e7eb 边/标题
     21px/600/hint 14px #9ca3af/影两档；暗 #161b22/#2d333b/#6e7681/官方 panel-shadow）——
     零请求纪律不外迁，手抄同步批注在两侧；
   - M4 门整面底色：#f2f3f5→#f9fafb / #141519→#0d1117（官方 --auth-page-from 档）。
4. **机制修复随批**（迁移引发的耦合面）：`takeoverModal.js show()` 显式 display
   'block'→'flex'（家族卡列布局）；`modal.js takeoverBlocking()` 在场判别改形态无关
   （原字面 `'block'` 耦合会在 flex 下漏判，F4 强制二选一回归钉住）。

## 盘点清单（其余加载/提示/弹窗/离线面——本批不动，理由随列）

| 面 | 形态 | 处置 |
|---|---|---|
| `#modal-box` / `#delete-box` / `#conflict-box` | 通用确认弹窗（玻璃） | 在册不迁：多 spec 钉面、玻璃族共享列表成员，回归面大，后续批逐面迁 |
| `#onboarding-panel` | 首启向导大面板 | 在册不迁：多步大件，家族类已备好 |
| `#memory-modal` / `#rules-modal` / `#settings-modal` / `#search-modal` | 大面板 | 在册不迁：同上 |
| `#conn-banner`（断连横幅） | 顶栏通栏条 | 射程外申报：横幅条非居中卡，材质对齐留后续批 |
| toast | 角落 chip | 射程外：非卡 |
| 卡窗失败卡（split.css 脸） | 独立窗失败面 | 与门失败卡同族；本批随 M3 档对齐口径（值级） |

## 验收矩阵（全部二值，自动化 = tests/cards-family.spec.mjs 78 断言 ALL PASS）

- C1 token 三腿：亮档默认 / data-theme=dark 强制（亮系统偏好下仍暗）/ prefers 回退 ✓（双引擎）
- C2 弹窗卡家族 DNA（bg #fff·radius 18·width 384·边 #e5e7eb·影两档·内距 32/32/40·
  字标 24px·标题 21/600·正文 14/#9ca3af·动作行右对齐）✓（双引擎）
- C3 加载卡同 DNA + 四行清单 + 行类双挂 + 活动行 heading 色 ✓（双引擎）
- C4 失败卡内联值 = 家族值逐值 ✓（双引擎）
- C5 变体探针（.nfc-link 下划线双档 / .nfc-error 红左缘 / .is-center / .nfc-step）✓（双引擎×双主题）
- 邻族回归：boot-render-gate 45/45 ✓ · console-takeover ALL PASS（含 F1 家族重钉）✓ ·
  entry-veil ALL PASS（含 V1/V5 家族重钉）✓
- 零 pageerror / 零 console error（全部脸）✓
- build-web OK（36 表并取、孤儿守卫过）✓
- **基准卡自身像素级不动** ✓——基准在官网仓（本仓零触碰即达标）；本仓消费的只有
  基准的 DNA 转录值与官方 lockup 原字节。

## 证据

`evidence/20261101_cards-family__delegate-nebflow-67f23d42/`
- `before/`：接管弹窗与加载卡改前样张（自上一批 after 定格拷贝）
- `after/`：family-dialog-light/dark-forced ×chromium+webkit、family-loading-light/
  dark-fallback ×2 引擎、family-fail-light ×2 引擎、family-variants-light/dark ×2 引擎、
  family-dialog-mobile-390、takeover-light/dark、entry-progress-light/dark、
  retake-fail-refused / retake-success-reentered（行为回归随拍）

## 协作边界与裁定申报

- glass/buttons 批（delegate-nebflow-8bbf8cf7）未落 main：本批零触碰 liquid-glass.css /
  sapphire.css / 按钮 token；按钮语消费现状役 `.g2-pill`（接管/入场同族批已落）；合并时
  与最新 main reconcile。
- **[USER-RULING] 申报**：visual-style 铁律 1「弹窗面板必须毛玻璃」在卡片家族面被作者
  本令（16:05 基准=实色卡）替换——接管弹窗面板改实色家族卡；`takeover-blur`（背景模糊）
  为四修批独立裁定，维持不动。请终审更新 visual-style 对应铁律（家族面豁免/替换口径）。

## 已知边界

- 基准图的官网 CSS（byui 域 chunk）本机网络拉不到（0 字节），DNA 以「基准实拍逐像素 +
  auth 域 token 实抓 + 官方 email 品牌卡 + lockup 原件」四源三角定位；若后续拿到 byui
  chunk，可逐值复核 `--nfc-*`（预期一致——墨色已三方对上）。
- `.nfc-link`/`.nfc-offline` 变体已建并钉（探针断言），实消费落点（断连离线卡）属盘点
  在册项，后续批接。
