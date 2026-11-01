---
type: batch
status: done
related: "[[20261101_172500_mobile-panel-title-b__delegate-nebflow-0c24df31]]"
---

# 默认头像主题化+居中 & 面板标题-内容间距修整批（2026-11-01 20:42 作者令）

> 作者令（手机端截图反馈两问题，kernel 单会话）：①agent 默认头像「浅色主题=白底黑 logo，深色主题=黑底白 logo」，现状颜色不对且图案没居中；②手机端面板标题（如 token 用量）与下方内容间距过大（面板头 B 批 [[20261101_172500_mobile-panel-title-b__delegate-nebflow-0c24df31]] 后遗留），收到设计合理值、只动手机档、逐面板过一遍。验收 = 双主题×双引擎手机档实拍 + 零 console error + 真实合并 main。

## 1. 根因考古（逐条实证）

**① 颜色不对**：默认头像兜底 = stand logo 双主题 `<picture>`（`agentProfile.js standLogoNode` 等 7 处同货源，logo 黑白翻面本身正确随 `prefers-color-scheme`）——错在**芯片底色**：`#agent-avatar-btn`（chat.css，桌面 28px/手机 78px 共用）与 `.ap-avatar-box .agent-avatar-stand-logo`（档案预览盒）还是首字母牌时代的蓝渐变 `linear-gradient(145deg,#5b7fbf,#3c568c)`，dock 中键 `.dock-center` 是 sapphire 实底 ⇒ 浅色主题 = 蓝底黑 logo（低对比、非白底）、与「白底黑/黑底白」规格双主题皆不符。brand app-icon 双档（`stand-app-icon-light/dark.svg`）即规格真值：#FFFFFF 底+#000000 形 / #000000 底+#FFFFFF 形。

**② 图案没居中（dock 中键，像素实锤）**：dock logo = 裸 inline `<picture>` 包 `img.dock-center-logo`（width/height 62%）。修前 Playwright 实测 img 盒 `left=0 / right=16.7`（顶/底 10.9 等分）——62% 宽解析成功、62% 高因双基准问题落回固有比（27.3×22.3），弹性布局把裸 picture 件顶到主轴起点 ⇒ 墨迹偏左 **7.8px**（修前截图墨迹扫描 left 2 / right 17.5 在案，`tmp-shotscan` 读数）。会话头 `.agent-avatar-stand-logo` 是成熟模式（尺寸+flex 都在 picture 上）故实测 17/17/17/17 全等分——修法即向其对齐。另证：stand symbol SVG 本体 bbox 四向 8px 边距严格对称（canvas 墨迹扫描 bbox center = 画布 center，双引擎一致），偏移全在 CSS 链不在图。

**③ 标题-内容间距**（B 批大标题入流后的三处遗留）：
- **usage 24px**：`#usage-dashboard` 自身 pt12 叠在壳层标题 pb10 + 面内 gap12 上，双层垫（姊妹面板标准 12px 的一半都不到——反了，是两倍）；
- **projects 68px**：`projectPanel.css` 桌面规则 `.canvas-tab-pane[data-type=projects]{padding-top:56px}` + `.team-scroll{top:56px}` 是桌面悬浮胶囊带的纯定位让位，B 批标题入流后成纯增量空带；
- **设置页标题被裁字（顺带修红）**：`.mobile-page-title` 是页面体 flex column 的可收缩件，`#settings-modal` 等定高 adoptee 争抢收缩 ⇒ 标题盒被压到 31px、28px 字面裁顶裁底（B 批验收照 chromium-light-settings-expanded 在案）——flex 布局病，非间距本值。

## 2. 改动面

- `css/stand-tokens.css`：新增 `--agent-avatar-chip-bg`（亮 #ffffff / 暗 #000000，配对 = brand app-icon 双档；三态解析结构同 --ctl-\* 族：`:root,html[data-theme=light]` 默认 / `html[data-theme=dark]` 强制 / 无属性回退 prefers-color-scheme）。零裸色值进消费方。
- `css/chat.css`：`#agent-avatar-btn` 与 `.ap-avatar-box .agent-avatar-stand-logo` 蓝渐变退役换 token。自定义头像零回归：`.agent-avatar-img` cover 铺满 100% 圆面，底色不可见（`.ap-avatar-initial` 退役档 L5406 保留不动作回滚参照）。
- `css/mobileLayer.css`：
  - dock 中键居中修正：尺寸上移 `picture`（62% 盒 + flex 双轴居中），img 100%×100% contain（会话头同款模式）；
  - dock 芯片翻色：`.dock-center:has(.dock-center-logo)` 限定 **logo 回落态**才换 token——自定义头像态 sapphire 底与内缩 2.5px 描边环零回归（`:has` 需 Safari 15.4+/Chromium 105+，App WebView 与 Playwright 双引擎均满足；不支持时维持现状 = 优雅降级）；
  - `.mobile-page-title` 补 `flex: none`（标题恒全额占位，收缩全额归 min-height:0 滚动面——与 B 批「主滚轴在内」同滚型）；
  - usage 面板顶距 12→0（节奏交面内 gap:12 承担，改后标题→筛选行 12px）；
  - 设置面头 25px 纯占位退役（面头两子件在手机档全隐：标题 B 批同名退役 + X 关闭钮双返回清查批隐 ⇒ 头只剩空带，`display:none` 整头摘除）+ 首分组节 mt24 归零（首节份叠 content pt16 = 40px 空带；改后标题→首卡 16px；节间 mt40 节奏不动）；
  - 文件尾批次块加项目面手机档双维归零（`padding-top:0` + `.team-scroll top:0`，规则全挂 `body.mobile-layer #mobile-pages` 页面体链，桌面零感知）。

## 3. 修后实测（Playwright DOM 读数，chromium/webkit 双引擎一致）

| 面 | 修前标题→首块 | 修后 | 口径 |
|---|---|---|---|
| usage | 24px（筛选行） | **12px** | 精确档 |
| projects | 68px（首卡） | **12px** | 精确档 |
| settings | 40px（首分组节）+标题盒 31px 裁字 | **16px**（首分组节）+标题盒 **52px** | 内容面 |
| todo/daemon/reminder | 14/12/12px | 不变 | 控制行 |
| plugins/background | 12/6px | 不变 | 内容面 |
| dock logo 盒边距 | left 0 / right 16.7 | **8.4 四向等分** | 双引擎 |
| 会话头 logo 盒边距 | 17 四向等分 | 17 四向等分（零回归） | — |

## 4. 验证实证

- `tests/mobile-avatar-chip-title-gap.spec.mjs`（新建，panel-title 同款自包含 harness，零后端零 sbt）：**双引擎（chromium/webkit）× 双主题（light/dark）× 390×844 手机档全腿 ALL PASS / 零 console error**——
  - A1 会话头：芯片底色随主题黑白、logo src 随主题换源、img 盒四向 ±1px、picture flex、圆形；
  - A2 dock：芯片随主题（logo 态）、换源、盒四向 ±1px、**元素截图墨迹扫描**墨心偏移 ≤1.5px + 左右墨距差 ≤2px（居中硬证）；
  - B1/B2 九面 sweep：usage/projects 精确 12±2，settings 16（首分组节），其余首可见件 ≤16，全站标题盒 52±2（防裁字回归闸）；
  - B3 实拍：chat-home 头像 + usage/projects/settings/todo 展开态 × 引擎 × 主题；
  - C1 自定义头像零回归腿：注入头像 → has-img、dock 照片在场、dock 底色保持 sapphire `rgb(70,99,156)`；
  - C2 桌面腿 ×2 主题：mobile-layer 不激活、桌面头芯片随主题 + logo 居中（有意全局生效）、桌面 projects 让位带 56px 原值（同选择器探针子树读证，手机档限定不越界）。
- 证据：`evidence/20261101_132819_avatar-chip-title-gap/`（修后实拍 23 张 + shots；修前对照存 `pre/` 5 张：蓝底 dock 全页/放大、usage 24px、chat-home 蓝底、B 批 settings 裁字体照）。

## 5. 边界与申报

- meFace/设置页账号头像框（`mf-me-avatar-*` / `settings-avatar-frame`）**未动**：账号头像语义（非 agent 默认头像），现状白卡/黑页自随主题已成立，零触碰。
- `:has(.dock-center-logo)` 降级路径：老 WebView 无 `:has` 时 dock logo 态维持旧 sapphire 底（现状），居中修正不受影响（纯几何规则不依赖 `:has`）。
- dock 中键 logo 态底色从品牌蓝改黑白 = 本令规格（「双主题各自成立」），亮暗两态 dock 键失去彩色识别——作者令明文要求，如实申报。
- 8 面间距 sweep 中 settings/daemon/reminder/todo 的「首可见件」= 面板功能控制行（B 批「计数/新建/关闭功能钮全留」在先裁定），盒距 0 + 行内 pt12 = 光学 ~22px 与全站一致；首列表行距 40-50px 为控制行 legitimately 占位，未动。
- 遗留观察（非本批）：logo 黑白翻面走 `prefers-color-scheme`（7 处 picture 同源）；未来若引入应用内强制主题（html[data-theme] setter），picture 需同批换 `--agent-avatar-chip-*` 式 token 化翻面（token 已按三态结构预留）。
