# foundation-motion — 「液态形变 / Liquid Morph」命名裁定 + 单源落地（证据件）

批：liquid-morph · 2026-11-06 · worktree `delegate/todo-mobile-revamp`
（作者令逐字：「把这种效果起个名 我们以后都用这种效果」）

交付面：命名裁定 / 单源模块 / 令牌组 / 规范文档 / glossary 词条 / 单测 + 门禁读数。
本件只放**过程证据**（命令与输出）；规范正文在 `design/liquid-morph__20261106/DESIGN.md`。

---

## 1. 命名裁定

**采用：液态形变 / Liquid Morph（代码拼写 `liquidMorph`，令牌前缀 `--lm-`）。不撞名，未改名。**

实跑核查（命令原样，全在 worktree 根执行）：

```
$ grep -n -i "液态\|liquid\|morph\|形变\|胶囊" GLOSSARY.md
(零命中)
$ grep -rn -i "液态\|liquid\|morph\|形变\|胶囊" design/
design/toggle-options/page.js:31:  name: '胶囊滑块'                     ← 开关造型方案名（无关）
design/toggle-options/toggle.css:203/204/253                            ← 同上（胶囊 = 开关造型）
design/20261006_askuser_redesign/index.html:189 / style-1/DESIGN.md:5 /
design/20261006_askuser_redesign/BRIEF.md:78 / hybrid/HYBRID.md:17,53   ← chips 造型词「胶囊」（无关）
design/20261026_212752_…portal-ws-shard-compress…md:63: 「无帧形变化」    ← 中继**帧形状**勘误（无关）
$ grep -rn -i "liquidmorph\|liquid-morph\|液态形变\|Liquid Morph" --include=*.{md,css,js,scala,json} .
src/main/resources/web/js/mobile/taskPanel.js:24:// v3⑤ 的「圆钮二级液态形变任务浮层」形态退役   ← 同族效果的**历史叫法**（退役形态说明）
$ grep -rn -- "--lm-" src/main/resources/web/css/      ⇒ 零命中（符号面前缀零占用）
```

裁定要点：

| 项 | 结论 |
|---|---|
| 是否撞名 | **否**。GLOSSARY 无该族词条；`design/` 无同名文档；`--lm-` / `liquidMorph` 符号零占用 |
| 既有同族用法 | `js/mobile/taskPanel.js:24,30`「液态形变任务浮层」「圆钮原地形变加宽」；`tests/*morph*`（`mobile-title-morph` / `island-morph` / `todo-island-morph`）与类名 `mh-morph-anim` / `mh-morph-close`。**它们是同一族效果的实例与历史叫法，不是竞争词条** ⇒ 沿用该名 = 对齐既有语境 |
| 为什么这个名字最准 | 效果的可识别特征 =「同一个节点在两端之间**连续可插值**（液态）+ **形状改变**（形变）」；「淡入/滑入/弹出」都不描述这件事。英文/代码拼写一一对应，可直接做模块名与令牌前缀 |
| 可复用性 | 名字与词条已入 `GLOSSARY.md` §14（带锚点 `^liquid-morph`），后续批次直接引用，不再另起名 |

---

## 2. 令牌读数（新增段 = `css/design-system.css` §㉒，39 行）

| 令牌 | 值 | 模块导出 | 既有读数锚点 |
|---|---|---|---|
| `--lm-dur-fast` | `var(--dur-2)` = 160ms | `DURATION.fast` | `base.css:183` `--dur-2`（= `--ds-dur-hover`）；本仓 morph 交接腿 160ms（`tests/_mobile-panel-settle.mjs:9`） |
| `--lm-dur` | `280ms` | `DURATION.base` | 原型 `mobileSearchFlat.css:74` `--mhsc-dur`；另有 4 处独立同档读数：`projectPanel.css:300` / `mobileLayer.css:1792`（令牌声明）、`mobileLayer.css:1783` / `timeWheel.css:107`（同值用法） |
| `--lm-dur-slow` | `var(--ds-dur-slow)` = 400ms | `DURATION.slow` | `design-system.css:241`（Carbon duration-slow-01 = 大展开档） |
| `--lm-ease` | `var(--ease-std)` = `cubic-bezier(0.4, 0, 0.2, 1)` | `EASING` | 原型 `:75` 与会话级 `base.css:186` 逐字同值 |
| `--lm-props` | 14 项 | `PROPERTY_WHITELIST` | 原型 `:150-176` 清单 13 项 + 追加 `transform` |
| （CSS 内 `1ms`） | 1ms | `morphTransition(…,{reduced:true})` | 原型 `:210` `--mhsc-dur: 1ms` |

属性白名单逐项来源：`width` / `height`（`:151-152`，图标 `:167-168`）、`margin-left` / `margin-right`
（`:153-154`）、`padding-left` / `padding-right`（`:155-156`）、`border-radius`（`:157`）、`border-color`
（`:158`）、`background-color`（`:159`）、`box-shadow`（`:160`）、`backdrop-filter`（`:161`）、`opacity`
（`:174`）、`max-width`（`:175`）—— **13 项逐字抄录**；第 14 项 `transform` **是唯一追加项**，理由：
原型是流内单节点（位移由 margin 表达），本批复用面 ②二级面板浮起 / ④新建表单从顶部展开 的宿主是
绝对/固定定位浮件（layout 盒不许动）⇒ 位移只能走 transform；既有读数 `mobileLayer.css:2238` 同款
（transform + 160ms 档）。排除项与依据见 DESIGN.md §3。

---

## 3. 单测输出（`node tests/liquid-morph.spec.mjs`，exit 0）

```
PASS  A1 导出面恰好 5 个（其余一律不导出）
PASS  A2 DURATION = {fast:160, base:280, slow:400}（三档各有既有读数锚点）
PASS  A3 DURATION 冻结（不可被消费面改写）
PASS  A4 EASING = cubic-bezier(0.4, 0, 0.2, 1)
PASS  A5 PROPERTY_WHITELIST 逐项（原型清单顺序 + 末位 transform）
PASS  A6 白名单冻结
PASS  A7 白名单不含不可插值/布局级属性（display/visibility/height auto/max-height/all）
PASS  A8 默认串 = 逐条属性各一份（14 条，永不含 all）
PASS  A9 默认串每条 = "<prop> 280ms cubic-bezier(0.4, 0, 0.2, 1)"
PASS  A10 默认串零 "all"
PASS  A11 子集 + fast 档
PASS  A12 slow 档 + delay
PASS  A13 降级：reduced:true ⇒ 1ms（与原型 :210 同值）
PASS  A14 显式毫秒数可覆盖档位
PASS  A15 白名单外属性 ⇒ 抛错（不许给不可插值属性做过渡）
PASS  A16 空数组 ⇒ 抛错
PASS  A17 第二套缓动 ⇒ 抛错（禁止第二套缓动）
PASS  A18 不存在的时长档 ⇒ 抛错
PASS  A19 负 delay ⇒ 抛错
PASS  A20 node 环境 reducedMotion() 恒 false（模块可被 node 直接 import 的原因）
PASS  B1 design-system.css 有 --lm-* 令牌组
PASS  B2 --lm-dur = 280ms（= DURATION.base）
PASS  B3 --lm-dur-fast = var(--dur-2)（= DURATION.fast 的源）
PASS  B4 --lm-dur-slow = var(--ds-dur-slow)（= DURATION.slow 的源）
PASS  B5 --lm-ease = var(--ease-std)（唯一曲线的既有出生点）
PASS  B6 --dur-2 解引用 = DURATION.fast
PASS  B7 --ds-dur-slow 解引用 = DURATION.slow
PASS  B8 --ease-std 解引用 = EASING
PASS  B9 --lm-props ≡ PROPERTY_WHITELIST（同项同序）
PASS  B10 定位到 ㉒ 令牌组文本段
PASS  B11 ㉒ 组零色值字面量
PASS  B12 ㉒ 组 reduced-motion 降级为 1ms（三档全压近零）
PASS  C1 原型时长读数 --mhsc-dur = DURATION.base
PASS  C2 原型曲线读数 --mhsc-ease = EASING
PASS  C3 原型 transition 清单覆盖 12 项（transform 为追加项，原型无）
PASS  C4 原型 reduced-motion 降级仍为 1ms

liquid-morph: PASS 36 · FAIL 0
```

**A 组** = 模块面（导出面恰好 5 个 / 逐值 / 三道闸）；**B 组** = 模块 ⇄ 令牌**逐值镜像**
（改一边不改另一边即红 = 单源判据）；**C 组** = 原型锚（原型读数变了也红 ⇒ 逼人回来重新对锚）。

## 4. 门禁与静态读数

| 项 | 结果 |
|---|---|
| `node scripts/check-design-tokens.mjs --json` | **1 项红，非本批**：`js/ws.js` 颜色字面量 12 > 基线 10。`js/ws.js` 在 `git status` 里**未修改**（== HEAD 内容）⇒ 该红在本分支 HEAD 上已存在（别的批次遗留的基线漂移），本批未触碰、也未抬基线。`design-system.css` 零命中（新增段零色值字面量） |
| 新增段零色值（定点复核） | `awk '/㉒ 液态形变/,/@media \(hover: hover\)/' design-system.css \| grep -E "#hex\|rgba?(\|hsla?("` ⇒ **ZERO color literals**（与单测 B11 双证） |
| `node scripts/check-circular.mjs` | PASS（256 files，静态 import 图无环；模块零 import） |
| `node scripts/check-component-contract.mjs` | PASS（五条契约零新增） |
| `node scripts/check-self-explaining.mjs` | PASS（27 命中 = 基线 27，零新增） |
| `node scripts/check-glass-unify.mjs` | PASS（`backdrop-filter` 只作为**属性名**出现在 `--lm-props` 值清单里，不是声明，未触发配方唯一性） |
| `node scripts/check-flat-buttons.mjs` | PASS（654 规则块 0 违规） |
| `node scripts/check-msgstyle-single-source.mjs` | PASS |
| `node scripts/check-design-spec-sync.mjs` | PASS（spec 表 65 行 / 账本 46 条同值；`--lm-*` 不属刻度族，无漂移判据） |
| `node scripts/check-js-types.mjs` | **环境受限**：worktree 无 `node_modules/typescript`（该闸明确拒绝 npx 回退）。改用**同版本**固定 tsc 直接检查：`/Users/kaiyu/Claude code/Stand/node_modules/.bin/tsc -p jsconfig.json --noEmit` → **exit 0，0 条输出**（基线 `tests/type-baseline.json` total = 0 ⇒ 新文件 checkJs-clean） |

## 5. 产物

| 文件 | 说明 |
|---|---|
| `src/main/resources/web/js/motion/liquidMorph.js` | **单源模块**（新目录）：导出且只导出 `DURATION` / `EASING` / `PROPERTY_WHITELIST` / `morphTransition()` / `reducedMotion()`；零 DOM、零副作用、node 可直接 import |
| `src/main/resources/web/css/design-system.css` §㉒ | `--lm-dur-fast` / `--lm-dur` / `--lm-dur-slow` / `--lm-ease` / `--lm-props` + reduced-motion 三档压 1ms（39 行，零色值） |
| `design/liquid-morph__20261106/DESIGN.md` | 规范：命名裁定 / 一句话定义 / 读数表 / 属性白名单（含 transform 申报与排除项）/ 两端几何可插值四条判据 / 六条禁止项 / 本批 4 处复用清单 / reduced-motion 口径 / 消费方式 |
| `GLOSSARY.md` §14 | 新词条「液态形变 / Liquid Morph」+ 撞名核查说明（只增不改，锚点 `^liquid-morph`） |
| `tests/liquid-morph.spec.mjs` | 36 项单测（模块面 / 令牌镜像 / 原型锚），零浏览器零端口，exit code 可判 |
| 本文件 | 命名裁定与理由 / 令牌读数 / 单测输出 / 门禁读数 |

提交：`feat(motion)` 前缀（只 add 上述 5 个文件 + 本 README）。

## 6. 未做 / 开放项（诚实申报）

1. **4 处复用面未接线**：本批只交语言 + 单源 + 规范；①⋯ 菜单展开 ②二级面板浮起 ③条目→互动框
   ④新建表单从顶部展开 的具体改造属后续节点，本批未改任何既有动画（`floatController` 的 spring
   腿等**仍在旧实现上跑**，迁移时按 DESIGN.md §6/§7 口径换轨，不许两套并跑）。
2. **`js/ws.js` 的 design-tokens 红是入场前既有**（12 vs 基线 10，文件未修改）——本批不动别人面，
   也未用 `--update-baseline` 抬基线（抬基线 = 放水）。
3. **未做真机/截图验证**：本批是「语言落地」不是「观感验收」，无视觉变更（新增段只出生令牌、零规则
   命中任何选择器）；4 处复用面接线时须补 375×812 双引擎双主题四拍 + reduced-motion 帧。
4. **`check-js-types.mjs` 无法在本 worktree 原样跑**（缺 `node_modules/typescript`），用同版本
   tsc 5.5.4 直接 `-p jsconfig.json --noEmit` 代替（exit 0）；CI/主仓装齐依赖后该闸应同样绿。
5. **知识文档 vault 未落**：`<dataRoot>/projects/todo-mobile-revamp/` 无注册库（`project.json` 不存在），
   故 `DESIGN.md` 按任务书指定路径落在仓内 `design/liquid-morph__20261106/`；若后续该 project 建库，
   请把本 DESIGN.md 镜像进 `.stand/docs/`（Obsidian frontmatter + wikilink 需另加）。
