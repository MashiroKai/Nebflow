---
type: design
status: implemented
---

# 会话身份头「始终同一行」批：静止落点 ≡ 贴顶落点（2026-11-02 11:08 作者令）

## 0. 认错摘要

作者问：「为什么会出现有的情况，这个头像会和两侧按钮不在同一行的情况？」

**上一批（[[20261102_muse-head-restore__delegate-nebflow-accdcd3b]]，a99ae5ee2）是我判错的。** 我在那份文档「关键假设 ①」里写「断言 C 取贴顶态……静止态 spread 60 属 Muse 形态不判」，把**静止态 60px 的错位写在案上、然后豁免出了断言之外**——于是它成了「形态本来的样子」，测出来是绿的，作者手上是错的。这不叫形态，这叫 bug 被自己的判据放过了。

本批**取消该豁免**：静止（scrollTop=0）/ 滚动中（≈400）/ 贴顶（≈892）/ 无名字，**四态全部判**「头像 cy ↔ 双钮 cy ≤1px」，容差不再分状态。

病根（实测定位，不是猜）：`.g2-chat-head` 是 `position: sticky`，而它的**两个落点由两个不同表达式给定**：

```
静止落点 = #chat padding-top + margin-top   （96 + (-32)  = 64）
贴顶落点 = #chat padding-top + top          （96 + (-96)  =  0）
```

二者恒差 64 ⇒ 身份组只在**滚动之后**才落到与双钮（cy 40）同一行；静止态头像 cy = 100、双钮 cy = 40，**偏差 60**。sticky 不是「对齐机制」，它只是让这个错位在滚动后 converge。

修法一行：让两式**相等**。`top` 与 `margin-top` 取同一表达式 ⇒ 静止落点 ≡ 贴顶落点 ≡ `--muse-head-anchor + safe`（375 无刘海 = 4），sticky 位移恒 0，滚动全程零跳动。

---

## 1. 落地（文件与行号）

- `src/main/resources/web/css/mobileLayer.css`
  - token 块（L309-324，紧跟 `--mchrome-inset`）：新增 `--muse-avatar: 56px` / `--muse-head-pad-t: 8px` / **`--muse-head-anchor`**（= `--mchrome-top + --mchrome-btn/2 − --muse-avatar/2 − --muse-head-pad-t` = 16+24−28−8 = **4**，纯派生、零字面量）。
  - 中块 ② sticky 本体（L3166-3175）：`top: calc(var(--muse-head-top, 0px) + var(--muse-head-anchor) + env(safe-area-inset-top, 0px))`；`padding: var(--muse-head-pad-t) 2px 12px`。
  - 中块 ②-b 负 margin（L3176-3192）：**同一表达式**（`var(--muse-head-top, -8px) + var(--muse-head-anchor) + env(...)`）。
  - 中块 ③ 头像（L3205-3213）：`width/height: var(--muse-avatar)`（原 `56px` 字面量）。
  - 文件头批次注记：L120-122（②-b 锚值 64→4 的沿革注）、L138-152（本批四条：病灶 / 修法 / 尺寸不动 / 让位解耦）、L3889-3895（⑤「同一视觉行」由近似升级为机械等式）。
- `src/main/resources/web/js/main.js`（L4371-4382）：`reserve` **取值未改**（仍 `bandBottom() − chatTop + BASE_TOP_PAD` = 96），只重写注释说明「身份组位置本批起与让位解耦」。
- `tests/mobile-muse-head-same-row-shots.mjs`（新增，七条断言 + R0，四态 + 安全区 + 桌面腿）。
- 六条既有 harness 按新契约改写（§5）。

**没有动**：形态（悬浮 / 透明底 / 无顶栏带 / 无描边 / 名字胶囊在头像正下方居中 / 渐隐带 88 / 内容从后穿过）、双钮尺寸与左右落点、dock、输入条、消息样式、头像 56 与双钮 48 两个尺寸档。

---

## 2. 四态读数表（375×812，`safe = 0`，实跑）

| 状态 | 头像 top→bottom | 头像 cy | 双钮 cy（tasks/search） | **偏差 ①** | 水平偏 ② | 胶囊 top→bottom | 首行顶 |
|---|---|---|---|---|---|---|---|
| 改前·静止 | 72→128 | 100 | 40 / 40 | **60.00** ← 被旧口径豁免 | 0.00 | 134→158 | 176 |
| 改前·贴顶 | 8→64 | 36 | 40 / 40 | 4.00 | 0.00 | 70→94 | — |
| **改后·静止** | **12→68** | **40** | 40 / 40 | **0.00** | 0.00 | 74→98 | **116** |
| **改后·滚动中**（scrollTop 400） | 12→68 | 40 | 40 / 40 | **0.00** | 0.00 | 74→98 | — |
| **改后·贴顶**（scrollTop 892） | 12→68 | 40 | 40 / 40 | **0.00** | 0.00 | 74→98 | — |
| **改后·无名字** | 12→68 | 40 | 40 / 40 | **0.00** | 0.00 | 不渲染（`display:none`，组高 76） | — |
| **改后·安全区 safe=44** | 56→112 | **84** | 84 / 84 | **0.00** | 0.00 | 118→142 | 160 |

组顶（`head.top`）：改前 静止 64 / 贴顶 0（**差 64**）→ 改后 **恒 4**（四态 + 安全区恒 48 = 4+44）。

逐帧采样（⑥）：滚程 0→1561，**67 帧**，头像 `rect.top` 恒 12、**极差 0px**、逐帧 spread 最大值 **0px**。

---

## 3. 作者要判的四件事

### ① 对齐后头像会不会顶到安全区 / 刘海

不会。`env(safe-area-inset-top)` 在 `top` 与 `margin-top` **两式同源出现**，所以 `组顶 = 锚 + safe` 这个恒等式在**任意 safe 下都成立**（不是只在 safe=0 凑出来的）。带刘海推演（safe = 44，CDP `Emulation.setSafeAreaInsetsOverride` 实测）：

```
组顶 = 4 + 44 = 48   头像顶 = 48 + 8 = 56   头像底 = 112   头像 cy = 84
双钮 cy = (16 + 44) + 24 = 84                ⇒ 偏差 0.00
```

头像顶 56 ≥ 刘海 44 ⇒ **不重叠**，还剩 12px 余量；组顶 48 也在安全区下方。安全区腿已进 harness 断言（⑦ 三条）。

### ② 头像 56 vs 双钮 48 的 8px 差（**我没有自己拍，请作者裁**）

居中后头像上下各突出 **4px**。三种取值对比：

| 方案 | 头像 | 双钮 | 代价 | 结论 |
|---|---|---|---|---|
| **A（本批现状）** | 56 | 48 | 上下各突出 4px（视觉可接受：头像为视觉主体、双钮为控制件） | **建议保留** —— 两个数都各有既有口径，本批零改动 |
| B 收头像到 48 | 48 | 48 | 破「收小批 06:58」的 56 档；实拍（09:14）折算 50.6，48 落到实拍**之下**，观感偏小 | 不建议 |
| C 双钮升到 56 | 56 | 56 | 破 `--mchrome-btn` / `--mtap-lg` 全局悬浮钮 48 基准 → 牵连页面壳「<」、canvas 工具钮、`--mchrome-clear` / `--mchrome-inset` 让位链 | 不建议 |

本批**按 A 落地**（不改尺寸），因为「对齐」不需要动尺寸；要改档属另一批的形态决定，代价已列。作者若要 B/C，改 `--muse-avatar` 或 `--mchrome-btn` 一处即可，`--muse-head-anchor` 会自动重算（零字面量）。

### ③ 名字胶囊跟着头像走之后，与首条内容的间距

新实测（375，safe=0）：胶囊 **74→98**，首条消息行顶 **116** ⇒ **名字 → 首行 = 18.0px**（改前 178−158 = 20px，但改前那个数是「让位 96 + 行 margin」凑的、与头像无耦合）。

首行 116 的构成：`锚 4 + 组高 106 + 行距 6`，组高 106 = `pad-t 8 + 头像 56 + gap 6 + 胶囊 24 + pad-b 12`。**恒定**，与 reserve 取值无关。安全区下整体下移 44：胶囊底 142 → 首行 160，间距仍 18。

### ④ 内容区顶部让位公式重算

`bandBottom()` 系列公式**结构与取值都不改**（手机层仍 = 渐隐带 88 + 基线 8 = **96**），理由是本批把身份组位置**与让位解耦**了：`padding-top` 与负 `margin-top` 同增同减、1:1 抵消 ⇒

```
head.top  = padding-top + margin-top = --muse-head-anchor + safe   （恒 4，与 reserve 无关）
firstRow  = head.top + 组高 + 行距 = 4 + 106 + 6 = 116             （恒 116，与 reserve 无关）
```

所以 **reserve 由「把头像顶到 64 的那条 96 空带」降级为「只承担渐隐带让位」**。收紧到 88 的收益为零（首行从 116 拖到 108，胶囊底 98 ⇒ 只剩 10px 呼吸），还要破与桌面同源的单公式 ⇒ 保留 96（`main.js:4382` 注释写明了这条推理）。

---

## 4. 七条机械断言（`tests/mobile-muse-head-same-row-shots.mjs`，ALL PASS）

四态 = 静止 / 滚动中（≈400）/ 贴顶（≈892）/ 无名字；另加安全区腿（safe 44）与桌面腿。

| # | 断言 | 判据 | 实测 |
|---|---|---|---|
| ① | 头像 cy ↔ 双钮 cy | ≤1px，**四态各测，静止不豁免** | 四态 + 安全区 + 无名字滚动 **全部 0.00** |
| ② | 头像 cx ↔ 屏幕中线 | ≤1px，四态 | 四态 **0.00**（avCx 187.5 / 187.5） |
| ③ | 胶囊中心↔头像中心 / 胶囊 top ≥ 头像 bottom −1 | ≤1px / ≥ −1 | **0.00 / gap 6.00**（74 − 68） |
| ④ | 不存在顶栏带 | ≥40 高 × ≥90% 宽 × 有墨迹，全文档扫描 | **0 条**（四态） |
| ⑤ | 内容仍从头像后穿过 | 滚动态消息行盒 × 头像盒相交 | scrollTop 400：1 行 / 2641px²；892：2 行 / 1720px² |
| ⑥ | 滚动全程无可见跳动 | 逐帧 `avatar rect.top` 极差 ≤2px | **67 帧，极差 0**（恒 12）；逐帧 spread 最大 0 |
| ⑦ | 安全区模拟（safe 44） | 头像顶 ≥ 44 / 首行 ≥ 胶囊底 / 锚随 safe 整体下移 48±1 | 56 / 160 ≥ 142 / 48 ✅，且 ① 仍 0.00 |
| R0 | 零 console error | 每腿 | 全绿 |

另加两条事实断言：静止「组顶 4 ±1 / 头像顶 12 ±1」、无名字「组高收敛 76（56+8+12，不留空洞）」。

---

## 5. 既有 spec 按新契约再改一次（逐条）

上一批把「静止态 spread 60」当合法值钉死的 9 条，本批逐条改写：

| # | 文件 | 改动 | 结果 |
|---|---|---|---|
| 1 | `tests/mobile-muse-head-restore-shots.mjs` | 头注 C 条 `≤12px` → **`≤1px`（全状态）**；L274-281 旧豁免注释（「属 Muse 静止形态，仅打印不判红」）整段重写为**口径修订+认错**；**新增**静止态 C 断言（`spread ≤1`，修前 60.00）与静止锚落位断言（4/12）；贴顶 C `12` → **`1`**；E 条「贴顶 avTop=8 / headTop=0」→ **「落点 == 静止落点（4/12，零位移）」** | ALL PASS |
| 2 | `tests/chat-head-position-shots.mjs` | 头注探针表 P①（64 → **4**）/ P⑤（0/8 → **4/12**）重写；P① `headTop 64±1` → **`4±1`**；P⑤ `headTop 0±1 & avTop 8±1` → **`4±1 & 12±1` + 与静止逐值相同**；P①′ 无名档 `64±1` → **`4±1`** | ALL PASS |
| 3 | `tests/mobile-muse-avatar-shots.mjs` | 头注 ③-b / ④ 重写；静止 `组顶 64±1 / 头像顶 72±1` → **`4±1 / 12±1`**；滚动态 `组顶 0 / 头像顶 8` → **`4 / 12` + 与静止一致** | ALL PASS（chromium+webkit × light/dark × fallback + 桌面） |
| 4 | `tests/muse-avatar-shrink-shots.mjs` | ③ 追加 `headTop 4±1`；⑤ `avTop 8±1 & headTop 0` → **`avTop 12±1 & headTop 4±1` + 与静止一致** | ALL PASS |
| 5 | `tests/muse-restore-smartcrop-shots.mjs` | 头注 A①（「组顶 64px 悬浮双键行下缘」→ **「组顶 4px = --muse-head-anchor」**）；A① 静止 `64/72` → **`4/12`**；A① 滚动态 `0/8` → **`4/12`** | ALL PASS |
| 6 | `tests/dock-avatar-edge-unify-shots.mjs` | 「静止态落在 64px 顶带」注释 → **「落点 = --muse-head-anchor 4px，锚双钮中心行」**（无硬断言，仅注记） | ALL PASS |
| 7 | `tests/mobile-v3.spec.mjs` T5 | 判定**无需改**（只断言 `position: sticky` / `z-index: 26` / `background: transparent` / 头像与名字在场，与落点无耦合） | 6 passed / 1 failed（T2 徽标 **8≠10**，见存量红） |
| 8 | `tests/mobile-topbar.spec.mjs` | 判定无需改（页头退役面，零身份头几何） | 3 passed |
| 9 | `tests/mobile-pinned-search.spec.mjs` P7 | 判定无需改（双钮 48×48 / right 359 / top **16** 本批零触碰） | 11 passed |

**另外新增** harness `tests/mobile-muse-head-same-row-shots.mjs`（七条断言本体）。

---

## 6. 证据（`evidence/20261102_muse-head-same-row/`）

375×812，亮/暗，零 console error：

- `rest-{light,dark}.png` —— **静止态（本批重点：头像与双钮在同一条线上）**
- `cropped-{light,dark}.png` —— 静止态顶部 0..170 裁剪放大（同一条水平线的直观证据）
- `scrollmid-{light,dark}.png` —— 滚动中（scrollTop 400）
- `pinned-{light,dark}.png` —— 贴顶（scrollTop 892）：与静止**逐像素同位**
- `unnamed-{light,dark}.png` —— 无名字态
- `safe-area-{light,dark}.png` —— 安全区模拟（CDP `setSafeAreaInsetsOverride` top=44）
- `before-rest-{light,dark}.png` —— **改前同视口对照**（源 `evidence/20261102_muse-head-restore/rest-*.png`，md5 与 git 版逐字节相同）
- `desktop-1280.png` —— 桌面回归（无 `.g2-chat-head`、`#header` 头像 28×28、渐隐带不渲染）

对照读法：`before-rest-light.png` 里头像压在消息列中段（cy 100）、双钮在顶（cy 40）；`rest-light.png` 里头像与双钮**同一条线**（cy 40/40），名字胶囊紧随其下。

---

## 7. 给 #1367（Profile/设置面大件）的组件化对接点

`#1367` 要把「群 / 设备 / 好友会话窗统一成主对话窗设计，头像和 name 悬浮在中央」——直接套这一组即可，**不需要抄几何数字**：

| 面 | 选择器 / 变量 | 说明 |
|---|---|---|
| 组本体 | `body.mobile-layer #chat .g2-chat-head`（同构面用等价容器） | `position: sticky` + `z-index: 26` + `gap: 6px` + `padding: var(--muse-head-pad-t) 2px 12px` |
| 头像 | `#agent-avatar-btn`（56 = `var(--muse-avatar)`） | 外径单源，改一处锚自动重算 |
| 名字胶囊 | `#session-name`（`:empty { display: none }` 收口） | 空名不留幽灵条 |
| 组顶锚 | **`--muse-head-anchor`** | = 由「头像 cy == 双钮 cy」反推（当前 4） |
| 偏移补偿 | `--muse-head-top`（= `-(容器 padding-top)`，`chatChrome.js:141-149` 同步） | 容器让位变化时同帧跟随 |
| 安全区 | `env(safe-area-inset-top)` | 已进两式同源，任意 safe 下恒等 |

**唯一要点（这是本批的全部技术内容）**：任何会话窗要让「静止落点 == 贴顶落点」，它的 `top`（sticky 钳位）与 `margin-top`（静止上提）**必须是同一个表达式**；写成两个式子，就一定复现 64px 错位。

---

## 8. 测试与存量红申报

跑法：每个 harness/套件独立进程。`make check-visual` 本机不可用 —— GNU Make 3.81 解析 `Makefile:27` 的 `$(shell …)` 报 `missing ')'`（main 同错，属工具链），改直调 `node scripts/visual-baseline.mjs --check --max-ratio 0.0005`。

- 本批 harness：`mobile-muse-head-same-row-shots` **ALL PASS**。
- 改写后复跑：`mobile-muse-head-restore-shots` / `chat-head-position-shots` / `mobile-muse-avatar-shots` / `muse-avatar-shrink-shots` / `muse-restore-smartcrop-shots` / `dock-avatar-edge-unify-shots` **全部 ALL PASS**。
- 相邻面：`mobile-pinned-search` 11 + `mobile-dock` + `mobile-canvas-sheet` + `mobile-topbar` 3 合跑 **49 passed**；`mobile-panel-title`、`ime-guard` 独立脚本 ALL PASS。
- 存量红（**main 同树同值，非本批**）：`mobile-v3` T2 徽标 8≠10；`liquid-glass.spec` M3/M4/M5/D3/T1/R1 六红；visual-baseline 8 项 DIFF（差异像素数 worktree 与 main 逐个相同）。

---

## 9. 文档库校验

`docs/` 在 `.gitignore` 中，按**入库快照**校验：`git archive HEAD docs | tar -x -C /tmp/vaultchk` 后跑

```
python3 /Users/kaiyu/.stand/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py --root /tmp/vaultchk/docs
```

（工作区直跑会报既有 orphan，属仓库现状，需申报。）

相关概念见 [[glossary]]；上一批（认错对象）见 [[20261102_muse-head-restore__delegate-nebflow-accdcd3b]]；更早沿革见 [[20261102_muse-avatar-shrink__delegate-nebflow-d616c46a]]、[[20261101_235748_chat-head-position-fix__delegate-nebflow-9c9ef879]]。
