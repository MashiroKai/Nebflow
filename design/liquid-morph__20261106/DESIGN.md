# 液态形变 / Liquid Morph — 动效语言规范

批：liquid-morph · 2026-11-06 · 作者令（逐字）：「把这种效果起个名 我们以后都用这种效果」。
单源模块 = `src/main/resources/web/js/motion/liquidMorph.js`｜令牌组 = `css/design-system.css` §㉒｜
原型 = `css/mobileSearchFlat.css`｜词条 = `GLOSSARY.md` §14｜单测 = `tests/liquid-morph.spec.mjs`。

---

## 0. 命名裁定（先裁后写）

| 项 | 结论 |
|---|---|
| 名字 | **液态形变 / Liquid Morph**（代码拼写 `liquidMorph`，令牌前缀 `--lm-`） |
| 是否撞名 | **不撞名** ⇒ 直接采用，不另取 |
| 核查范围 | ① `GLOSSARY.md` 全文 × ② 全仓 `design/` 文档 × ③ `web/js`、`web/css`、`tests/` 全量词面 |

核查明细（2026-11-06 实跑）：

- `grep -n -i "液态\|liquid\|morph\|形变\|胶囊" GLOSSARY.md` ⇒ **零命中**（本表此前无该族词条）。
- `grep -rn -i … design/` ⇒ 命中两处**语义无关**：「胶囊滑块」是开关造型方案名（`design/toggle-options/`），
  「无帧形变化」是中继契约的**帧形状**勘误（`design/20261026_…portal-ws-contract`），都不是动效语言。
- 仓内**既有同族用法**（不是竞争词条，是同一族效果的历史叫法与实例）：
  `js/mobile/taskPanel.js:24,30`「圆钮二级液态形变任务浮层」「圆钮原地形变加宽」；
  `tests/*morph*`（`mobile-title-morph.spec.mjs`「大标题 → 胶囊」、`island-morph-frames.mjs`、
  `todo-island-morph-shots.mjs`）与类名 `mh-morph-anim` / `mh-morph-close`（`tests/_mobile-panel-settle.mjs`）。
  ⇒ 「液态」在这个仓里已经就是这类效果的叫法，沿用正是**对齐既有语境**；本发明只是把
  散落的叫法收成一个有定义、有落点、有门禁的词。
- 符号面零占用：`--lm-` 前缀在 `web/css/` 内零命中，`liquidMorph` 标识符零命中 ⇒ 无覆盖风险。

**名字的判据（为什么是这个名）**：效果的可识别特征是「同一个节点**在两端之间连续变形**」，
不是「淡入」「滑入」「弹一下」——那两个字的语义分别是**连续可插值**（液态）与**形状改变**（形变），
未见更贴的词；英文 `Liquid Morph` 与代码拼写 `liquidMorph` 一一对应，可直接做目录名 / 模块名 / 类名前缀。

---

## 1. 一句话定义

> **同一个 DOM 节点在两端几何之间连续插值的一段过渡**：一条曲线、一个基准时长、
> 一份可插值属性白名单、reduced-motion 降级 1ms；不是「闪一下换一个元素」。

原型（唯一灵感来源，不许另造缓动）= 手机搜索「扁平长条 ⇄ 右上角圆钮」：
`css/mobileSearchFlat.css`（`:74-75` 时长/曲线、`:133-144` 展开几何、`:183-196` 收起几何、
`:149-176` transition 清单、`:207-212` 降级、`:214-227` 实测验收读数）。
三条结构性不变量随语言一起继承：**单节点**（收/展都是同一个元素上的类切换）、
**滚动容器几何零变化**（收/展全程不改宿主高度）、**不泄漏**（节点住在页面体内，随页面一起退场）。

---

## 2. 读数表（令牌 ⇄ 模块，逐值同源）

| 令牌（`css/design-system.css` §㉒） | 值 | 模块导出 | 既有读数锚点（为什么是这个值） |
|---|---|---|---|
| `--lm-dur-fast` | `var(--dur-2)` = 160ms | `DURATION.fast` | `base.css:183` `--dur-2`（= `--ds-dur-hover`）＝色彩/描边/底纹档；本仓 morph 交接腿亦取 160ms（`tests/_mobile-panel-settle.mjs:9` 记「`.on` 翻转 → 160ms 延时 → morphIn」）。只用于**不改变几何**的子腿。 |
| `--lm-dur` | `280ms` | `DURATION.base` | 原型 `mobileSearchFlat.css:74` `--mhsc-dur`。**基准档**，几何形变主腿，默认一律用这一档。独立收敛到同一档的既有读数另有四处：令牌声明 `projectPanel.css:300`（`--project-card-out-ms`）/ `mobileLayer.css:1792`（`--dur-switch`），同值用法 `mobileLayer.css:1783`（`var(--dur-switch, 280ms)` 回退档）/ `timeWheel.css:107`（`tw-sheet-in` 280ms）—— 四个面各自走到 280 是既有事实，本语言把它立成唯一基准。 |
| `--lm-dur-slow` | `var(--ds-dur-slow)` = 400ms | `DURATION.slow` | `design-system.css:241`（Carbon duration-slow-01，行内注释语义即「大展开/重要通知档」）。只给**大位移**腿（≥ 半屏整块浮起 / 从顶部展开）；默认不用。 |
| `--lm-ease` | `var(--ease-std)` = `cubic-bezier(0.4, 0, 0.2, 1)` | `EASING` | 原型 `:75` `--mhsc-ease` 与会话级 `base.css:186` `--ease-std` **逐字同值**——本语言不是新造曲线，是既有曲线的一次正式收编。 |
| `--lm-props` | 14 项清单（见 §3） | `PROPERTY_WHITELIST` | 原型 `:150-176` transition 清单逐项抄录 + 1 项追加（`transform`）。 |
| （无令牌，CSS 侧写死 1ms） | `1ms` | `morphTransition(…, {reduced:true})` | 原型 `:210` `--mhsc-dur: 1ms`：二值语义保留（「有没有过渡」不变），过渡压近零。 |

**单源判据**：`tests/liquid-morph.spec.mjs` 的 B 组把上表两侧钉死——`--lm-dur` 必须等于
`DURATION.base`，`--lm-dur-fast` 必须 `var()` 到 `--dur-2`（解引用后 = `DURATION.fast`），
`--lm-props` 必须与 `PROPERTY_WHITELIST` 同项同序。**改一边不改另一边即红。**
C 组再反向钉原型（`:74` / `:75` / transition 清单 / `:210` 四组读数仍在原位），原型被改
也会红——逼改动者回到本文件重新对锚。

**加档纪律**：三档已经覆盖「子腿 / 主腿 / 大位移腿」三个真实用途；**不得**再开第四档，
也不得为某一处面单独写毫秒数（要新档 = 先在 §2 补一行「既有读数锚点」，否则不收）。

---

## 3. 属性白名单（14 项）

顺序 = 原型 transition 清单顺序；逐项来源如下（`excluded` 项亦有据）。

| # | 属性 | 原型行 | 承担什么腿 |
|---|---|---|---|
| 1-2 | `width` / `height` | `:151-152`（条）、`:167-168`（图标） | 几何主腿：条 343→48、44→48；图标 16→20 |
| 3-4 | `margin-left` / `margin-right` | `:153-154` | 落位腿：`margin-left: calc(100% - 16 - 48)` 把条推到右上角 |
| 5-6 | `padding-left` / `padding-right` | `:155-156` | 内距 16→0，让图标居中 |
| 7 | `border-radius` | `:157` | 形态腿：`--ds-radius-xl`（12）→ `50%` |
| 8 | `border-color` | `:158` | 描边换档（透明 ⇄ 玻璃描边） |
| 9 | `background-color` | `:159` | 底色换档（`--ds-wash-16` ⇄ `--cv-bg`） |
| 10 | `box-shadow` | `:160` | 浮影换档（无影 ⇄ `--cv-shadow`） |
| 11 | `backdrop-filter` | `:161` | 毛玻璃换档（none ⇄ `--cv-blur`）；`-webkit-` 前缀版不单列（同一属性，浏览器前缀由书写层决定） |
| 12 | `opacity` | `:174` | 输入行淡出 |
| 13 | `max-width` | `:175` | 输入行宽度收到 0（`100%` → `0`，长度对百分比可插值） |
| 14 | `transform` | **追加** | 见下 |

**唯一追加项 `transform` 的申报**：原型是「流内单节点」，位移天然由 `margin` 表达，故不需要
transform；本批 4 处复用面里 ②二级面板浮起、④新建表单从顶部展开 的宿主是绝对/固定定位浮件
（layout 盒不许动），位移只能走 `transform`（合成器腿，不触发布局、不整页重排）。既有读数亦支持：
`mobileLayer.css:2238` 的手机档面板形变腿就是 `transform` + 160ms 档。`transform` 两端均可插值
（translate/scale/rotate 同族），与清单内任何属性不冲突。

**明确排除（禁入白名单，判据 = 不可插值或触发布局）**：`display` / `visibility`（不插值，二值跳变）、
`height: auto` 与 `max-height`（值不可插值，且逐帧 reflow；面板展开请用 `transform: translateY` +
`opacity`）、`content`、`transition: all`（隐式把不可插值属性一并卷进来）。
运行期由 `morphTransition()` 兜底：白名单外的属性直接 `TypeError`，错误信息带合法集。

---

## 4. 两端几何可插值原则（本语言成立的四条判据）

任一「液态形变」候选用例，必须四条全过，才允许登记进复用清单：

1. **两端都显式声明**：起止态的几何各自写在一条 CSS 规则里（原型 `:133-144` / `:183-196`），
   **中间态零声明** —— 中间由浏览器插值。任一端缺声明 ⇒ 浏览器按 `auto`/初值插值 ⇒ 观感是跳变，不是形变。
2. **单位可插值**：两端要么同为长度（44px → 48px），要么能由 `calc(...)` 混合（`100% - 16 - 48` 对
   `calc(...)`；`12` 对 `50%` 由 calc 混合规则处理）。一端是关键字（`auto` / `fit-content`）即不成立。
3. **不触发布局**：几何腿优先用 `transform`（浮件）或 `width/height/margin/padding`（流内单节点，
   且**宿主几何零变化**——原型 bug①的教训：收/展写 `host.style.height` 会让滚动容器几何逐帧变化，
   scrollTop 被钳回 → 事件回流 → 观感「卡死」）。
4. **同节点**：收/展是**同一个元素**上的类切换，不许「删一个节点、插一个新节点」。这是「液态」
   与「换皮」的分界，也是原型 ③ 判据（`:22-24`「整条只有 `.mh-search-bar` 一个 DOM 节点」）。

---

## 5. 禁止项（做到即回退）

| # | 禁止 | 为什么 | 怎么被拦住 |
|---|---|---|---|
| 1 | **JS 定时器逐帧**（`setInterval`/`requestAnimationFrame` 手写补间、spring 数值积分） | 与浏览器合成器抢帧、掉帧时不可预测、reduced-motion 无处降级 | 本批复用面前提 = 纯 CSS 过渡；代码评审判据，`morphTransition()` 只吐 transition 串（无 tick 面） |
| 2 | **给不可插值属性做过渡**（`display`/`visibility`/`max-height`/`content`/`all`） | 浏览器只能跳变或在每帧 reflow，形变退化成闪烁 | `morphTransition()` 白名单闸（`TypeError`）；`check-design-tokens` 另判 `transition: all` 为红 |
| 3 | **第二套缓动** | 同一个动作族出现两条曲线 ⇒ 观感「一段动画两种性格」 | `morphTransition()` 只接受 `EASING` 同值，其余抛错（A17）；消费面禁写字面 `cubic-bezier` |
| 4 | **第二份时长/清单**（面内再写 280ms / 再抄一遍属性列表） | 单源一旦分叉，改一处不改另一处就漂移 | `--lm-*` 与模块**镜像断言**（单测 B 组）；`check-design-tokens` 严格面 |
| 5 | **改两端之间的中间态**（给 `:not(...)` / 过渡中态写几何） | 中间态由浏览器算，人写中间态 = 与插值打架，掉帧处出现折角 | 原型结构照抄：只写两端，中间零声明（§4 判据 1） |
| 6 | **为了动画改宿主几何**（滚动容器/页面壳高度、`overflow` 切换） | 原型 bug① 的根因：宿主几何变化 → scrollTop 钳回 → 事件回流 | §4 判据 3；复用面验收必须含「收/展全程 host clientHeight/scrollHeight 不变」 |

---

## 6. 复用清单（本批 4 处 = 本语言的首批消费面）

| # | 场景 | 两端几何（起点 → 终点） | 档 | 备注 |
|---|---|---|---|---|
| ① | **⋯ 菜单展开** | 圆钮（48×48 / radius 50%）→ 菜单卡（宽 200+ / radius 12+ / 有浮影） | base 280 | 位移腿走 `transform: translate`，几何腿走 width/height/radius；条目淡入用 `opacity`（fast 160） |
| ② | **二级面板浮起** | 屏外（`translateY(100%)` / `opacity 0`）→ 在位（`translateY(0)` / `opacity 1`） | slow 400（大位移）或 base 280 | 宿主是浮件 ⇒ 全程 `transform` + `opacity`，layout 盒不动 |
| ③ | **条目 → 互动框** | 列表行（行高/单行圆角/无浮影）→ 互动框（多行高/卡片圆角/有浮影） | base 280 | 同节点就地长大：height / radius / box-shadow / background-color 同段 |
| ④ | **新建表单从顶部展开** | `translateY(-100%)` → `translateY(0)`（+ `opacity`） | slow 400 | 从页顶滑入；**禁用 `max-height` 补间**（§3 排除项） |

四处的共同验收口径（照抄原型 `:214-227` 的归档方式）：
两端逐值读数（宽/高/位置/圆角/底/描边/浮影/图标尺寸）＋ reduced-motion 下一帧落位 ＋
**收/展全程宿主 clientHeight / scrollHeight 零变化** ＋ 结束后 `element.getAnimations()` 为空（无残留动画）。

---

## 7. reduced-motion 口径

- **语义**：降的是「过渡时长」，不是「有没有反馈」。二值语义保留 —— 状态变化照旧发生，
  只是瞬时落位（原型 `:207-212` 的 `1ms`，与会话级 `.ds-*` 的 `0.01ms` 同理）。
- **CSS 侧（默认路径）**：`design-system.css` §㉒ 内 `@media (prefers-reduced-motion: reduce)`
  把 `--lm-dur-fast` / `--lm-dur` / `--lm-dur-slow` 三档全压到 `1ms` ⇒ 消费面零改动。
- **JS 侧（手拼 transition 串时）**：`morphTransition(props, { reduced: reducedMotion() })`
  ⇒ 时长变 `1ms`（同值同语义）。`reducedMotion()` 每次调用读一次 `matchMedia`，用户运行中可切换。
- **禁止**：reduced-motion 下改成「不改变任何视觉」（那会让用户失去「发生了什么」的反馈），
  或改成另一条曲线 / 另一个时长档。

---

## 8. 消费方式与改动流程

CSS 侧（静态属性清单）：

```css
.my-morph-surface {
  transition-property: var(--lm-props);
  transition-duration: var(--lm-dur);
  transition-timing-function: var(--lm-ease);
}
/* 只要几何腿时，用显式子集（顺序即插值顺序，可读）： */
.my-morph-icon { transition: width var(--lm-dur) var(--lm-ease), height var(--lm-dur) var(--lm-ease); }
```

JS 侧（需要子集 / 动态档位）：

```js
import { morphTransition, reducedMotion } from './motion/liquidMorph.js';
el.style.transition = morphTransition(['width', 'border-radius'], { duration: 'base' });
el.style.transition = morphTransition(null, { reduced: reducedMotion() }); // 降级腿
```

> 注：**不要**把 `--lm-props` 塞进 `transition:` 简写 —— 自定义属性的逗号清单无法展开成
> 逐属性 timing（会退化成一个 0s 的无效过渡）。简写只在显式子集时用，全量走三件套。

**改值的路径（单源铁律）**：改时长/曲线/属性集 = ①改 `js/motion/liquidMorph.js`（模块面）
＋ ②改 `css/design-system.css` §㉒（CSS 面）＋ ③跑 `node tests/liquid-morph.spec.mjs`（两侧镜像断言必须仍绿）。
只改一处 = 单测红；只想改某处的观感 = 换档位（`fast`/`base`/`slow`），**不许**在消费面写字面量。
