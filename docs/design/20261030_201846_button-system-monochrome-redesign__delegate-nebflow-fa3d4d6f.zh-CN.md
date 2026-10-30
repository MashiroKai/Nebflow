---
type: design-spec
status: draft（呈作者拍板 2026-10-30；作者令 20:09「按钮不要绿色，官网 logo 那种科技感黑白，像 SpaceX」）
---

# 全局按钮体系单源重设计——黑白科技向（去交互绿）

> 结论先行：把全局按钮从「微信绿实色主操作」迁移到「官网 mystand.space 视觉语言」——**主操作 = 近黑实色墨（暗面板反转为浅墨），强调色去绿后落点 = 蓝仅作 focus/链接、红仅作危险、绿降级为纯身份/状态色**（与官网 `--color-pixel` 的既有裁定同构）。四态（主/次/幽灵/危险）沿用 `sapphire.css:916-945` 五类具名 token 单源架构，只改值不改结构；唯一结构性新增 = 按钮 token 按**面板主题**（`html[data-theme]`）取双档，落实「主题跟随面板」。主发送钮等 7 处字面量绿/红/蓝逐个换面。呈作者拍板，只设计零产品代码。
>
> 与既有认知方案（[[20261030_human-readable-presentation.zh-CN|human 围栏/五档呈现档]]）的关系：**零冲突**——那套方案管内容呈现层（人面子/机器面），本稿只管 chrome 控件视觉层；唯一接合点即「主题跟随面板」：按钮不自带主题，读所在面板的 `data-theme`。

## 词条

- 墨族（ink family）— 本稿主操作色族：近黑实色 `#111827`（亮面板）/ 浅墨 `#e6edf3`（暗面板），白/深字，随面板主题墨水互换（ink-swap），取自官网认证按钮 `--auth-btn-bg` 双档。 ^term-ink-family
- 降级绿（demoted green）— `#07c160` 从「交互主色」降级为「身份/状态专用」：终端 glyph、块光标、复制成功、录音脉动等**非可交互控件**场景保留，与官网 `--color-pixel` 语义逐字对齐。 ^term-demoted-green
- 面板主题（panel theme）— `html[data-theme="light|dark"]` 属性主题系统（`css/stand-tokens.css:60-120`），区别于全局 `prefers-color-scheme`；按钮 token 的值域按面板主题双档化是本稿唯一结构新增。 ^term-panel-theme
- 反馈分档（feedback split）— 既有裁定 B（`sapphire.css:906`）：亮档玻璃材质用「边框+投影」表达 hover/active（白 alpha 填充递进在亮底衬上 Δ≤0.67 不可辨），暗档允许填充递进；实色墨族不受此限（深填充在亮底衬 Δ 大、天然可辨）。 ^term-feedback-split

## §0 一句话目标

全局按钮体系从绿实色迁移到官网黑白科技语言：主操作近黑/浅墨墨水互换、强调色去绿（蓝=focus/链接、红=危险、绿=身份/状态）、四态语义齐备、token 单源、主题跟随面板——把「SpaceX 感」落到每个可点控件上。

## §1 参考与依据

1. **作者令（2026-10-30 20:09）**：按钮风格不要现在的绿色，采用官网 logo 那种科技感黑白感觉（「就像 SpaceX 一样」）——本稿最高优先级输入。
2. **官网 mystand.space 视觉语言**（参照系，`/Users/kaiyu/.stand/projects/stand-website`，只读）：
   - 认证主按钮近黑实色：亮主题 `bg #111827` / hover `#374151` / 白字 / radius 8px（`app/globals.css` `--auth-btn-bg/-hover`；`app/login/LoginContent.tsx:202-205`）；暗主题**反转为浅墨** `#e6edf3` 底 + `#0d1117` 字（`globals.css:237-239`）——即墨族双档的出处。
   - 次级钮 = 玻璃感描边：1px `--color-card-border`（亮 `#e5e7eb` / 暗 `#2d333b`）+ 半透明填充（`components/shared/SocialButton.tsx:69-72`）。
   - **蓝仅作功能色**：`--color-primary #3b82f6` 只用于 focus ring 与链接（`globals.css` 全文 focus `outline: 2px solid var(--color-primary)`）；紫 accent 基本不用。
   - **绿是身份色不是控件色**：`--color-pixel #07c160` 注释明令「identity-only——terminal glyph / eyebrow prompt / block cursor / copy-success，不作可交互控件色」（`globals.css:17-21`）——降级绿的直接先例。
   - Logo 是**墨水互换而非重着色**：黑墨版配亮主题、白墨版配暗主题（`globals.css:959-962`）——墨族「ink-swap」的图形学出处。
   - 几何：radius 8px（按钮/输入）、`text-sm font-medium`、过渡 160–240ms ease、hairline 边框无处不在。
3. **仓内既有 token 资产**（禁新 token 原则下全部复用）：
   - 五类按钮具名 token 单源 `css/sapphire.css:916-945`（主/次/幽灵/危险/禁用/掉线）——本稿只重定值；
   - 官网镜像 token 族 `--st-*`（`css/stand-tokens.css`，含 `--st-primary: #07c160`、几何 `--st-radius-btn: 8px`、`--st-btn-pad: 7px 14px`）——本稿把墨族落进这套已有镜像，不另起炉灶；
   - 面板主题属性系统 `html[data-theme]`（`stand-tokens.css:60-120`，消费先例 `css/ask.css:51-55`、`css/perception.css:64,141`）。
4. **视觉铁律 `visual-style`（frontend-dev 插件 SKILL.md）**：铁律 2 现行锁定「发送钮 = 微信绿实色族 #07C160」；其「维护」节明定「新视觉裁定到达 → 替换对应铁律，不并存」。**本稿即该替换请求的完整内容**——见 §10 裁定替换申请。
5. **外部范式（检索源）**：Apple HIG（控件以填充/描边层级区分层级）、shadcn/ui（default/secondary/outline/ghost/destructive 五类与本仓五类同构）、Material 3（暗主题 on-color 反转）。冲突取舍：以作者 taste（黑白科技感）与官网实装为准，范式仅备证。

## §2 架构与信息架构（token 策略）

### 2.1 总原则

- **结构零新增，值域重定**：`--btn-*` 五类 token 块（sapphire.css:916-945）名字、消费者、`:is()` 选择器列表一律不动；只改 `:root` 值与新增主题双档（§2.3）。
- **字面量纪律继承（R4′）**：按钮规则体维持零字面量色值；新增墨族 token 的值在 token 定义处一次写明，规则只写 `var()`。
- **单源三族**：交互色只有三族——墨族（主操作）、红族（危险，单源 `--color-danger`）、蓝族（focus/链接，单源新具名 `--btn-focus-ring`）；绿退出交互面。

### 2.2 墨族具名 token（新增，值取自官网实装）

```
/* 墨族 — 主操作（值逐字取自官网 globals.css --auth-btn-* 双档） */
--btn-ink-bg:            #111827;   /* 亮面板 rest（官网 --auth-btn-bg）      */
--btn-ink-bg-hover:      #374151;   /* 亮面板 hover（官网 --auth-btn-bg-hover）*/
--btn-ink-bg-active:     #0b1220;   /* 亮面板 active：比 rest 深一档的按压   */
--btn-ink-ink:           #ffffff;   /* 亮面板字墨                            */
--btn-ink-bg-dark:       #e6edf3;   /* 暗面板 rest（官网暗档反转）           */
--btn-ink-bg-hover-dark: #ffffff;   /* 暗面板 hover（官网暗档）              */
--btn-ink-bg-active-dark:#c9d1d9;   /* 暗面板 active：比 rest 深一档         */
--btn-ink-ink-dark:      #0d1117;   /* 暗面板字墨（官网暗档）                */
```

几何与字体直接引用官网镜像 token（`stand-tokens.css:143-156`）：radius = `var(--st-radius-btn)`（8px）、padding = `var(--st-btn-pad)`（7px 14px）、字号 13px / 字重 500（字重沿用铁律 3「按钮 500」，与官网 font-medium 一致）。

### 2.3 主题跟随面板（唯一结构新增）

现状缺口：按钮 token 只有 `prefers-color-scheme` 双档，而感知/邮件面板已走 `html[data-theme]`（面板可强制主题、chrome 跟随 OS）——同一按钮在两套主题下同框时会穿帮。**改法**：

```
html[data-theme="light"] { /* --btn-ink-* 亮档值 */ }
html[data-theme="dark"]  { /* --btn-ink-* 暗档值 */ }
html:not([data-theme])   { /* 回退：按 prefers-color-scheme 取档（现状语义不变）*/ }
```

即每个按钮色 token 三态解析：面板强制亮 / 面板强制暗 / 跟随系统。`--btn-*` 既有五类全部照此补档（红/蓝族亮暗值本就经 `--color-danger` 等既有双档 token 间接解析，自动继承）。

### 2.4 五类角色 × 新值域

| 角色 | token 前缀 | 新值域（亮面板 / 暗面板） | 语义不变项 |
|---|---|---|---|
| 主操作 primary | `--btn-primary-*` | 重指向墨族（`--btn-ink-*`） | 实色填充 + 反白字 |
| 次操作 secondary | `--btn-secondary-*` | 不变：中性玻璃描边 `--glass-send-*` | 描边有无承担层级（既有） |
| 幽灵 ghost | `--btn-ghost-*` | 不变：无底 + 弱化字 | hover 升 `--color-text`（既有） |
| 危险 danger | `--btn-danger-*` | 不变：红描边 + 红字，`--color-danger` 单源 | 红系统一（§5.3） |
| 禁用/掉线 | `--btn-disabled-*` / `--btn-unavailable-*` | 不变：灰玻璃族 | 语义与判据（`!state.connected`）不动 |

## §3 交互状态机表

| 角色 | rest | hover | active | disabled | loading（新增） |
|---|---|---|---|---|---|
| 主操作 | 墨实色 + 反白字 + inset 顶折线/底缘 + 中性接触影（沿用 `#send-btn` 阴影槽位，零绿） | 填充沿墨阶升一档（亮 `#111827→#374151` / 暗 `#e6edf3→#fff`）+ 外投影抬一档 | 填充再深一档 + 按压 inset（`inset 0 1px 3px rgba(0,0,0,.18)` 槽位沿用） | 灰玻璃族 `--btn-disabled-*`，`cursor:not-allowed`，图标 0.45 | 图标换 spinner（中性灰旋转环，18px 同 glyph 尺寸），填充保持 rest，禁重复提交 |
| 次操作 | 透明底 + `--glass-send-border-active` 描边 | 亮档：描边升 `--color-frame-text-muted` + 投影抬升（反馈分档，[[#^term-feedback-split]]）；暗档：允许 `--glass-control-bg-hover` 填充递进 | inset 内陷（亮档）/ 填充再深（暗档） | 同主操作禁用档 | 同主操作 |
| 幽灵 | 透明底 + `--btn-ghost-ink`（亮 `--color-frame-text` / 暗 `--color-text`） | 字升 `--color-text` | 字保持 + 微压暗 | 字降 `--color-text-muted` | 同主操作 |
| 危险 | 透明底 + 红描边 + 红字 | hover 底铺 `--btn-danger-tint`（8% 红 wash，token 既有） | tint 加深 + inset | 灰玻璃禁用（**红不降饱和**——危险语义须残存，禁用表达交给 cursor+灰） | 同主操作 |
| 掉线 | `--btn-unavailable-*` 灰玻璃 | 同 rest（一字不改，既有判据） | 同 rest | —（自身即不可用态） | — |

焦点态（全角色统一）：`outline: 2px solid var(--color-primary)` 蓝环 + `offset 2px`——与官网 focus 语言逐字一致；蓝族全 UI 只此一处与链接色两处使用。

## §4 动效规范

- 触发：hover/active/focus/disabled 切换、loading 进出。
- 时长/缓动：`transition: background .2s, box-shadow .2s, border-color .2s, color .2s`（沿用 `#send-btn:943` 槽位；官网口径 160–240ms ease，`.2s` 在其中段，不另起值）。
- 位移：零位移（层级靠填充/描边/投影，不靠移动），与官网一致。
- loading spinner：`animation: rotate .8s linear infinite`（中性灰环，无绿）。
- `prefers-reduced-motion`：全部过渡降为 0.01s（既有 reduce 块模式，`input.css:905-909` 同构），spinner 换静态「…」字。

## §5 视觉规格（全 token 引用）与换面清单

### 5.1 色彩令牌总表（去绿后的落点）

| 令牌 | 亮面板 | 暗面板 | 用途落点 |
|---|---|---|---|
| 墨族 `--btn-ink-*` | `#111827`/`#374151`/`#0b1220` + 白字 | `#e6edf3`/`#fff`/`#c9d1d9` + `#0d1117` 字 | **唯一主操作色** |
| 蓝 `--color-primary`（既有 `#3b82f6`/暗 `#60a5fa`，经 `--st-primary` 镜像族对齐） | `#3b82f6` | `#60a5fa` | 只作 focus ring + 链接（含 `.fm-device-desc-save` 一类文字链） |
| 红 `--color-danger`（既有 `#c0392b`/暗 `#f26b6b`） | 同左 | 同左 | 危险四态 + `#stop-btn`（录音/生成停止） |
| 灰玻璃族 `--glass-send-*` / `--glass-control-*` | 既有 | 既有 | 次操作/幽灵/禁用/掉线材质（不动） |
| 降级绿 `#07c160` | 同左 | 暗变体 `#07c25f` | **仅**身份/状态：终端 glyph、块光标、caret、复制成功 toast、`.icon-btn.recording` 录音脉动（见 5.2-B⑧） |
| 强调紫 | — | — | 官网即弃用，本仓本就不用，零动作 |

### 5.2 换面清单（全仓侦察：A=token 单点 / B=字面量逐个）

**A. token 单点（一次改值，全域传播）**

| # | 位置 | 动作 |
|---|---|---|
| A1 | `css/sapphire.css:916-945` `--btn-primary-*` | 重指向墨族 `--btn-ink-*`（含 `--btn-primary-tint` 开关 ON 档改墨 tint，见 B⑨） |
| A2 | `css/sapphire.css:27` `--brand-green` | **不删**：降级为身份/状态色源（ recording 脉动、copy-success 继续引用） |
| A3 | `css/base.css:2-3` `--color-primary/-hover` | **不改名**：语义从「交互主色」改注为「focus/链接蓝」，值对齐官网 `--st-primary` 蓝档（`stand-tokens.css:18` 的 `--st-primary: #07c160` 一并改蓝并注明）；或更稳方案——交互面全部改走 `--btn-ink-*`/`--btn-focus-*` 新名，`--color-primary` 冻结留作历史引用。**推荐后者**（零「同名词不同义」陷阱），实现批二选一，定稿前不锁 |
| A4 | `css/stand-tokens.css:60-120` | 补 `html[data-theme]` 三态解析块（§2.3） |

**B. 字面量逐个换面（绕过 token，必须手改）**

| # | 位置 | 现状 | 换面 |
|---|---|---|---|
| B1 | `css/input.css:911-1008` `#send-btn` | 字面绿五态 + 暗档 `#22CE78/#0BA95A` | 改 `var(--btn-primary-*)` 全引用；阴影槽位（inset 折线/底缘/接触影）保留——它们本就是零绿中性槽位；disabled 降灰玻璃（褪绿 wash 作废） |
| B2 | `css/input.css:1010-1024` `#stop-btn` | `var(--color-error)` 红实色 | 语义正确，**不动**（危险实色 = 停止，与墨主操作形成唯一一对实色对立） |
| B3 | `css/ask.css:204-228` `.ask-send` | 字面绿复制体 | 同 B1，改 `var(--btn-primary-*)` |
| B4 | `css/modal.css:258-275` `#modal-confirm` | 绿实色 + 字面绿 glow | 墨族；hover 绿 glow 改中性抬升影 |
| B5 | `css/modal.css:180-213` cancel/save 蓝幽灵 | 蓝宝石蓝字面 `rgb(91,127,191)` | save→墨族主操作；cancel→`--btn-ghost-*`（蓝退出对话框） |
| B6 | `css/modal.css:215-233` 破坏 trio | 字面红 `rgba(220,60,60,…)`（非 `--color-danger`） | 改 `--btn-danger-*`（红系统一，见 5.3） |
| B7 | `css/sidebar.css:948-961` `.btn-save` | 蓝描边 | 墨族主操作（设置内主保存 = 主操作语义） |
| B8 | `css/input.css:701-710,1514` `.icon-btn.recording` | 绿实色 + 绿脉动 keyframes | **保留绿**——录音是「状态/身份」非「可交互主操作」，落降级绿（[[#^term-demoted-green]]） |
| B9 | `css/sidebar.css:1135`、`css/sapphire.css:1229-1235` 开关 ON | 绿填充 `--color-primary`/`--brand-green` | ON 档改墨 tint（`--btn-primary-tint` 重定后自动跟随）；开关本体豁免按钮规则（既有设计，不动结构） |
| B10 | `css/base.css:432` `.global-perm-toast-btn.approve` | 字面绿 | 墨族（权限批准 = 主操作） |
| B11 | `css/sapphire.css:1171` `.mchain-chip.playing`、B12 `css/base.css:219`/`css/ask.css:199` caret | 绿描边/绿 caret | 播放态描边改中性 `--color-frame-text-muted`；caret 保留绿（文本身份色） |
| B13 | `css/sidebar.css:1197-1230` `.cfg-btn-primary` | 绿实色 | 墨族；`.cfg-btn`（次/幽灵）与 `.cfg-btn-add`（虚线）不动 |

**C. 容器内按钮**（`ob-actions`、`error-escalation-actions`、friends/social/secrets 行动作）全部已走 `--btn-*` token 或 `.cfg-btn` 族——A1 落地即自动换面，零手改；`.canvas-zoom-btn`、`.lt-toolbar`、`.nf-lightbox-close`、`.nav-item`/`.activity-btn`/`.icon-btn` 玻璃族本就无色相，不动。

### 5.3 红系统一（顺手债，低成本高一致性）

三套红并一套：字面红（B6）→ `--btn-danger`；`.fm-gs-danger`/`.social-btn-danger` hover 红 → `--color-danger`；`.pp-btn.danger-line` → `--color-danger`。危险红与墨主操作并存：对话框里 confirm=墨、destructive=红描边，层级对位官网「墨钮 + 描边钮」。

### 5.4 与明暗两主题的搭配总表

| | 亮面板（`#f5f6f8` 底） | 暗面板（`#13161c` 底） |
|---|---|---|
| 主操作 | `#111827` 墨 + 白字，inset 折线保留 | `#e6edf3` 浅墨 + `#0d1117` 字（官网暗档反转） |
| 次操作 | 白玻璃 + hairline 描边，hover 边框+投影 | 暗玻璃 + `#2a2d35` 描边，hover 允许填充递进 |
| 危险 | `#c0392b` 红描边 | `#f26b6b` 红描边（既有双档） |
| focus | `#3b82f6` 蓝环 | `#60a5fa` 蓝环 |
| 状态绿 | `#07c160` 身份/状态 | `#07c25f`（官网暗变体） |

## §6 边界与异常

- **空态/超长文本**：按钮文案 i18n key 不动；超长文本 `white-space: nowrap; overflow: hidden; text-overflow: ellipsis`（既有 `.cfg-btn` 行为保持），max-width  none 不设限（既有惯例）。
- **窄视口（375px）**：发送钮 36px 圆形与输入条工具钮尺寸不动；墨族对比度在窄屏无衰减，零动作。
- **多语言**：字墨反白对 CJK/拉丁无差异；13px/500 保持。
- **主题仲裁冲突**：面板 `data-theme` 与 OS 偏好不一致时，以面板为准（§2.3 三态解析顺序）；chrome 无 `data-theme` 时回退 OS——与感知/邮件面板现行行为一致。
- **例外登记**：① 开关（toggle）虽具按钮形态，豁免本四态（既有裁定，ON=墨 tint）；② 录音钮绿脉动、caret 绿为身份色残留，不视为违规；③ `#stop-btn` 红实色是唯一非墨实色主操作位（停止=危险语义优先）。
- **降级路径**：若作者拍板时只要「去绿」不要「蓝 focus」，蓝环回退为墨环 offset（对比仍 ≥3:1 于填充外），§8 断言 7 相应改口径——**默认按蓝环写**。

## §7 无障碍

- 键盘：全角色 `Tab` 可达，focus 蓝环 `2px/offset 2px`（官网同款）；`Esc` 关对话框后焦点归还触发钮（既有 `js/modal.js` 行为保持）。
- 对比度：墨 `#111827`/白字 15.7:1、浅墨 `#e6edf3`/`#0d1117` 15.5:1、幽灵暗档 `--color-text` 5.83:1（既有计算，`sapphire.css:947-949`）、红描边 `#c0392b` 4.6:1——全部 ≥4.5:1 ✔。
- 非鼠标：disabled `cursor:not-allowed` + 图标 0.45（既有）；loading 期间 `aria-busy="true"` + `aria-disabled`（实现批补，本稿锁定语义）。
- 色弱：层级由「描边有无 + 填充/透明 + 字墨强弱」三通道承担，去绿后色相通道只剩红（危险）单点，风险更低。

## §8 可断言验收点（供 QA 转 Playwright）

1. 主操作填充二值：computed background of `#send-btn`（连接态）∈ {`#111827`（亮）, `#e6edf3`（暗）}；hover 后 ∈ {`#374151`, `#ffffff`}——**需截图**（亮/暗各一）。
2. 全局零交互绿：对 `document` 全按钮元素扫描，computed background/border-color 的 green-dominant（G-R > 30 且 G-B > 30）计数 = 例外白名单数（`.icon-btn.recording` 及其 keyframes、copy-success toast）——纯 DOM 断言，**不需截图**；白名单逐项列于测试注释。
3. 危险单源：computed color/border-color of `#modal-confirm[tone=danger]`、`button.danger`、`.social-btn-danger` 均解析自 `--color-danger`（亮 `#c0392b` / 暗 `#f26b6b`）——DOM 断言。
4. focus 蓝环：Tab 聚焦 `#send-btn` 后 computed outline-color = `rgb(59,130,252)`（亮）/`rgb(96,165,250)`（暗），offset = 2px——DOM 断言 + 截图各一。
5. 主题跟随面板：同一文档设 `html[data-theme="dark"` 而 OS=light，`#send-btn` computed background 仍为 `#e6edf3`；移除属性回退 OS 档——DOM 断言。
6. 掉线语义不回归：`#send-btn.disconnected` computed background = `var(--glass-send-bg-active)` 解析值，`cursor:not-allowed`——DOM 断言。
7. 五类结构不回归：`--btn-primary-bg/-hover/-ink`、`--btn-secondary-*`、`--btn-ghost-*`、`--btn-danger-*` 六个具名 token 全部存在于 `:root` computed 集（A1 只改值不删名）——DOM 断言。
8. 既有约束测试归零影响申报：`tests/visup-b-token-parity.mjs`、`scripts/check-msgstyle-single-source.mjs` 中锁定绿值的断言须同步改墨值（实现批负责，QA 复核 diff）——CI 断言。

## §9 参考链接

- 官网实装（只读参照）：`/Users/kaiyu/.stand/projects/stand-website/app/globals.css`（`@theme` 3-32、暗档 110-168、auth 双档 177-257、focus 729/815/897、logo ink-swap 959-962）；`app/login/LoginContent.tsx:202-205`；`components/shared/SocialButton.tsx:69-72`。
- 仓内基准：`css/sapphire.css:916-945`（五类 token）、`:893-913`（分类纪律块）、`css/input.css:911-1008`（发送钮五态）、`css/stand-tokens.css:18,60-120,143-156`、`css/ask.css:51-55`（面板主题先例）。
- 方法论：design-spec 插件 SKILL.md（四道防线、模板 §0-9、R4′ 字面量纪律）。
- 认知方案接缝：[[20261030_human-readable-presentation.zh-CN]]（human 围栏/五档呈现档——本稿不动其内容层，仅共享「主题跟随面板」接合点）。

## §10 裁定替换申请（视觉铁律 amendment）

`src/main/resources/seed/plugins/frontend-dev/skills/visual-style/SKILL.md` 按「维护」节**替换式**更新（不并存）：

1. 铁律 2 首段「主发送按钮 `#send-btn` 用微信绿实色族（#07C160，白字）」→ 改为「主发送按钮及全部主操作控件用**墨族**：亮面板近黑 `#111827` 白字、暗面板浅墨 `#e6edf3` 深字（ink-swap，官网 `--auth-btn-*` 双档），token 单源 `--btn-ink-*` / `--btn-primary-*`」。
2. 铁律 2 灰族登记段「严禁并入微信绿实色族」→ 语义保留（次级发送仍中性灰玻璃），措辞改「严禁并入墨族以外的任何色相族」。
3. Evidence「发送按钮用绿色」条 → 由本令（2026-10-30 20:09）取代，注明绿降级为身份/状态色。
4. 新增铁律 7（或并入铁律 2）：**主题跟随面板**——控件 token 按 `html[data-theme]` 三态解析（面板强制亮/暗 + OS 回退）。
5. 蓝族使用规约新增：focus ring 与链接色单源 `--color-primary`（蓝），禁止入主操作/危险。

批注：A3 存在「`--color-primary` 改名 vs 冻结」二选一，建议作者拍板时一并裁定；默认推荐「冻结旧名、新名 `--btn-ink-*`/`--btn-focus-*` 走墨/蓝」，理由 = 避免同名词不同义的历史引用陷阱。
