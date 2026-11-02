---
type: design-spec
status: implemented
title: 卡片家族统一样式包（cards 模块——基准=官网过渡卡）
created: 2026-11-01
author: stand (delegate-nebflow-67f23d42)
batch: 卡片家族统一样式包批（作者 16:05 令，三张参考实拍）
related:
  - "[[20261101_141229_unified-button-design__cc28a33e]]"
  - "[[20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN]]"
tags: [design, visual, cards, tokens, boot-gate, takeover]
---

# 卡片家族统一样式包设计稿（基准 = 官网过渡卡）

## §0 作者令（逐字要点）

「基准=第一张：stand 首次启动时官网检测主机的过渡卡片——居中 STAND logo+字标、粗黑标题、灰色说明文、极简文字动作、大留白、白卡大圆角软阴影。以它为所有加载卡片/提示卡片/弹窗卡片的设计基准，做成统一样式包（与按钮包/玻璃包同族的设计系统成员）。」

- 对齐对象：接管弹窗「控制台已在其他位置打开」（现状裸文字无卡形态）；界面加载卡「正在检查主机...」（已有卡但未对齐）。
- 验收：三参考面迁移前后对照 + 家族清单，深浅双主题 × Chromium+WebKit 双引擎实拍，零 console error；基准卡自身像素级不动（它在官网仓，本仓零触碰即达标）。

## §1 考古（基准 DNA 的四个证据源，全部带出处）

1. **基准实拍逐像素测量**（`669358390463000_2522.jpg`，474×335）：
   卡体 x≈31..414 ⇒ 宽 ≈385px；圆角弧 ≈16–18px；上距 ≈34 / 侧距 ≈36 / 下距 ≈40；
   字标块 101×24（S 高 24px）；字标→标题 ≈39；标题墨高 20（字号 ≈21–22）；标题→正文 16；
   正文两行行距 20–21（≈14px×1.5）、换行宽 ≈310；正文→动作 ≈41；动作墨高 12（≈14px）带下划线。
2. **官网 auth 域 token 逐值**（mystand.space `/_next/static/chunks/0q_cyuwfuh.99.css` 实抓 2026-11-01）：
   亮档 `--auth-card-bg:#fff; --auth-card-border:#e5e7eb; --auth-heading:#111827; --auth-subtle:#9ca3af;
   --auth-link:#6b7280; --auth-link-hover:#374151; --auth-link-deco:#e5e7eb; --auth-page-from:#f9fafb`；
   暗档 `card:#161b22; border:#2d333b; heading:#e6edf3; subtle:#6e7681; link:#98a0aa; deco:#2d333b;
   page:#0d1117; panel-shadow:0 8px 32px #00000080, 0 2px 8px #00000059`。
   与实拍墨色三方对上：标题实测 (13,16,25)≈#111827 ✓ / 正文 darkest #9a9b9f≈#9ca3af ✓ /
   动作 darkest #696c71≈#6b7280 ✓ / 下划线浅灰≈#e5e7eb ✓。
3. **官方横排字标 lockup**（`mystand.space/brand/stand/email/lockup-light|dark.png`，504×126=@3x，
   官方邮件件注记「字标属于 logo——禁字体模拟」；已按原字节 vendor 至 `web/brand/stand/`）。
   基准字标块 101×24 ↔ lockup 4:1 在 h24 时宽 96 ✓（同物同形）。
4. **官方 email 品牌卡**（仓内 `web/brand/stand/email/templates/SignIn.html`）：
   白卡 radius 12、标题 21px/600/-0.2px、正文 15px/1.75、lockup 图载入——标题字号档 21 与实拍 21–22 对上。

结论：基准卡 = **官网 auth/link 域的实色面卡**（白底 + 1px 灰边 + 大圆角 + 大软投影），非玻璃族。
家族材质据此定为实色 surface（弹窗毛玻璃铁律在本家族面被作者本令替换，见 §5 裁定注）。

## §2 家族 token 表（`css/cards.css`，`--nfc-*`，前缀空闲已查）

| token | 亮档（=auth 域 :root 逐值） | 暗档（=auth 域 dark 块逐值） | 出处 |
|---|---|---|---|
| --nfc-bg | #ffffff | #161b22 | --auth-card-bg 双档 |
| --nfc-border | #e5e7eb | #2d333b | --auth-card-border 双档 |
| --nfc-heading | #111827 | #e6edf3 | --auth-heading 双档 |
| --nfc-subtle | #9ca3af | #6e7681 | --auth-subtle 双档 |
| --nfc-link | #6b7280 | #98a0aa | --auth-link（dark=--color-muted） |
| --nfc-link-hover | #374151 | #e6edf3 | --auth-link-hover |
| --nfc-link-deco | #e5e7eb | #2d333b | --auth-link-deco（dark=--color-card-border） |
| --nfc-page | #f9fafb | #0d1117 | --auth-page-from / --color-background |
| --nfc-shadow | 0 2px 8px rgba(17,24,39,.04), 0 8px 24px rgba(17,24,39,.06) | 0 8px 32px rgba(0,0,0,.50), 0 2px 8px rgba(0,0,0,.35) | 亮=站内官方三档栈外影两档（sapphire/input 官方转录档，基准软影实测同档）；暗=--auth-panel-shadow 逐值 |
| --nfc-radius | 18px（双主题同值） | 〃 | 实测 16–18 + 同族档 18 |
| --nfc-width | 384px（max-width 86vw） | 〃 | 实测 385 → 4px 栅格档 |
| --nfc-pad | 32px 顶 / 32px 侧 / 40px 底 | 〃 | 实测 34/36/40 → 栅格档 |
| --nfc-gap-block | 40px（字标→标题、正文→动作） | 〃 | 实测 39/41 |
| --nfc-gap-title | 16px（标题→正文） | 〃 | 实测 16 |
| --nfc-lockup-h | 24px | 〃 | 实测 S 高 24（4:1 ⇒ 宽 96≈实测 101） |
| --nfc-title | 21px/600/-0.2px | 〃 | 官方 email 卡标题档 + 实测 |
| --nfc-body | 14px/1.5，max-width 320px | 〃 | 实测 14×1.5、换行宽 310 |
| --nfc-link-size | 14px/500 | 〃 | 实测 13.5 → 14 |

主题解析三腿同构 --st-*：`:root` 亮档默认 → `html[data-theme="dark"]` 强制档 →
`@media(prefers-color-scheme:dark)` 回退档（门面 boot 期无 data-theme 时走第三腿）。

## §3 组件类（cards.css）

- `.nfc` 基准卡体：居中列、width/max-width/padding/radius/边/底/影/heading 色（§2 全表）。
- `.nfc-lockup-light` / `.nfc-lockup-dark`：官方双档 lockup 图，主题互换显隐（email 件同法，禁 filter 反色）。
- `.nfc-title` / `.nfc-body`：家族标题/正文档（正文 max-width 320 居中换行）。
- `.nfc-actions`（默认右对齐双按钮行）/ `.nfc-actions.is-center`（居中）：变体修饰符。
- `.nfc-link`：极简文字动作（auth-link 下划线档，deco 色随主题；hover 提亮）。
- `.nfc-steps`：加载步骤清单形态（左对齐、行距 8px、label 14px）。
- `.nfc-error`：S9 同族错误行迁入（红左缘 2px + 8% 红底，`--status-*` 语义单源不变）。
- 面具名：`.nfc-loading`（入场加载卡）/ `.nfc-dialog`（弹窗卡）/ `.nfc-hint`（提示卡）/ `.nfc-offline`（离线卡）——面具只做布局差异，材质/字阶全走基准 DNA。

## §4 迁移落点（本批实施）

| # | 落点 | 迁移 |
|---|---|---|
| M1 | 接管弹窗 `#takeover-box`（index.html DOM + modal.css） | 家族卡化：+字标行、标题/正文换 `.nfc-title/.nfc-body`、错误行换 `.nfc-error`、动作行 `.nfc-actions`（g2 药丸语保留——按钮包边界）；从 modal.css 共享玻璃列表摘除（家族=实色，非玻璃）；`.takeover-blur` 背景模糊裁定维持 |
| M2 | 入场加载卡 `.nf-entry-card`（index.html 门） | +字标行、家族几何/字阶；样式体从门内联块迁 cards.css（该面只在 CSS 窗全 ok 后显示，可安全外迁）；`display:none` 预藏线留内联（防慢载窗空卡裸浮）；清单行 `.nf-entry-item` 机制不动（真实信号驱动）、label 升 14px 家族档、四态点 sapphire 语义色维持 |
| M3 | 门失败卡 `.nf-boot-card`（内联，零请求纪律） | 值级对齐家族（radius 18 / 边 #e5e7eb→暗 #2d333b / 标题 21/600 家族档 / hint 14px #9ca3af→暗 #6e7681 / 阴影双档）——字面量内联手抄 + 同步批注（它是 CSS 失败脸，永不外迁） |
| M4 | 门整面底色 `#nf-boot-gate`（内联） | 对齐 `--nfc-page` 官方档（#f2f3f5→#f9fafb / #141519→#0d1117），内联字面量 + 出处注 |

盘点在册、本批不动（弹窗大件与横幅面，家族类已为其备好，后续批逐面迁）：
`#modal-box` / `#delete-box` / `#conflict-box`（通用确认族，玻璃材质+多 spec 钉面，回归面大）；
`#onboarding-panel`（首启向导大面板）；`#memory-modal` / `#rules-modal` / `#settings-modal` / `#search-modal`（大面板）；
`#conn-banner`（顶栏横幅面——形态为通栏条非居中卡，不属卡片射程，材质对齐留后续批）；
toast（角落 chip 面，非卡）。卡窗失败卡（split.css 脸）与门失败卡同族，值已对齐档。

## §5 裁定注（视觉铁律变更申报）

- visual-style 铁律 1「弹窗面板必须毛玻璃」在**卡片家族面**被作者本令（16:05 基准=实色卡）替换：
  接管弹窗面板改实色家族卡（[USER-RULING] 申报，随批报告上报终审）；`takeover-blur` 整页背景模糊
  是四修批独立裁定，维持不动。glass/buttons 批（delegate-nebflow-8bbf8cf7）未落 main——本批全部
  消费现役 token（sapphire `--status-*` / 官方转录档），互不写对方文件（liquid-glass.css / sapphire.css
  零触碰），合并时与最新 main reconcile。

## §6 可断言验收点（全部二值）

1. `#takeover-box` computed：background=#fff（暗 #161b22）、border-radius=18px、border 1px #e5e7eb（暗 #2d333b）、box-shadow 含 `rgba(17,24,39, 0.06)` 外影（暗 0.50）、width=384px。
2. `#takeover-box .nfc-lockup-light` 在场且可见（暗档切换为 dark 图可见/亮图隐），alt="STAND"。
3. `#takeover-title` computed font-size=21px / font-weight=600 / color=#111827（暗 #e6edf3）；`#takeover-msg` 14px/#9ca3af（暗 #6e7681）。
4. `.nf-entry-card`：同 1-3 族断言 + 步骤清单 4 行 + `data-state` 四态机制原样（entry-veil.spec V1-V3 保持绿）。
5. 门失败卡内联值与家族值逐值一致（radius 18 / 边 #e5e7eb / 标题 21/600）。
6. 门面（entry）样式体在 cards.css：`index.html` 内联块不再含 `.nf-entry-card` 视觉规则（仅 `display:none` 预藏线）；boot-render-gate/entry-veil 全绿。
7. 三参考面双主题 × Chromium+WebKit 实拍存证 `evidence/20261101_cards-family__delegate-nebflow-67f23d42/`，零 pageerror。

## §7 参考

- 同族设计系统成员：[[20261101_141229_unified-button-design__cc28a33e]]（悬浮件统一设计稿——按钮/玻璃语言的上游裁定）；[[20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN]]（g2 药丸族出处）。
- 基准实拍：`~/.stand/uploads/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff/669358390463000_2522.jpg`（官网过渡卡）
- 官网 auth 域 chunk 实抓（本批考古件，临时件未入库）：`0q_cyuwfuh.99.css` @ mystand.space（抓取脚本过程见批报告）
- 官方 lockup 资产：`web/brand/stand/lockup-light.png` / `lockup-dark.png`（mystand.space 原字节 vendor）
- 同族前批：`reports/20261101_103300_takeover-entry__delegate-nebflow-ce004aa9/DESIGN.md`（g2 药丸按钮语、真实加载项机制——本批承继不回翻）
