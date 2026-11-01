---
type: design
status: implemented
---

# 会话头 Muse 头像收小批：78px → 56px（2026-11-02 06:58 作者令）

作者令原话（06:58，实拍 `/Users/kaiyu/.stand/uploads/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff/722923094985583_IMG_1674.jpg`）：

> 「手机端会话顶部居中的 Muse 头像**调小一点**。」

口径：现值 78px，收小到 56px 一档（52-60 内可微调）；头像与名字的间距、成组贴顶位置
（64px 锚）、居中与 sticky 语义全部保持；名字字号随比例微调但克制；dock 中键头像
（44px）与桌面端零触碰；相关 spec 里钉死 78px 的判据同步改写。

上游形态见 [[20261101_221500_muse-avatar-restore-stack-smartcrop]]（Muse 基座回归批，
78px 就是这一批落地的）、[[20261101_235748_chat-head-position-fix__delegate-nebflow-9c9ef879]]
（位置批，钉下 64px 贴顶锚与 6px 呼吸缝 —— 本批全部保持）。

---

## 1. 取值：为什么是 56

| 候选 | 视宽占比（375） | 与名字（14px）字身比 | 判断 |
|------|-----------------|---------------------|------|
| 78（原值） | 20.8% | 5.57 | 作者判「大」——头像单体成为页面第一视觉重心，压过消息区 |
| 60 | 16.0% | 4.29 | 接近原档，收得不够（作者令是「调小一点」实拍观感明显偏大） |
| **56（选定）** | **14.9%** | **4.00** | 头像仍是会话头的唯一视觉锚，字号比落到 4 倍档，与消息正文字号（13px）的比例关系自然 |
| 52 | 13.9% | 3.71 | 进入「图标」观感区：头像对名字不再有量级优势，成组读成「小图标 + 标题」而非「头像 + 名字」 |

取 56 而非区间下沿 52 的理由：**78 → 56 是 −28% 的面积收缩（−45%），视觉重量降一档但
头像仍稳居第一层**；再往下（52-54）头像与名字胶囊的高度差趋近 3 倍以内，在 375 视宽
下会与顶带双键（48px 圆钮）混成「三枚同档圆钮」，反而削弱「这是会话身份」的语义。
56 与顶带双键 48px 保持 8px 的档差，层级仍然清楚。

**名字字号留 14px 不动**：原 14px 是「随大头像升半档」（13 → 14）的派生值；头像收到
56 后字身比 4.0 仍在「头像为主、名字为辅」区间，降到 13px 会让名字与消息正文同字号、
削弱身份组的抬升感 —— 作者令要求「克制」，不调即最克制的一档（且零改动面）。

---

## 2. 落地

### 2.1 唯一几何改动（1 处声明）

`src/main/resources/web/css/mobileLayer.css:2687-2690`（文件尾 Muse 批 ③ 块）：

```css
body.mobile-layer #chat .g2-chat-head #agent-avatar-btn {
  width: 56px;
  height: 56px;    /* 原 78px；底/描边/浮影/glass-control 缘全部沿用基座档 */
}
```

选择器、作用域（`body.mobile-layer` + `#chat .g2-chat-head` 子树）、其余所有属性零改动
⇒ 桌面 28px 档（`chat.css:5348`）与 dock 中键 44px 档（`--dock-center-size`）天然零触碰。

### 2.2 保持不动的语义（本批零改动，逐条已复验）

| 契约 | 落点 | 本批状态 |
|------|------|----------|
| 头像-名字间距 6px | `.g2-chat-head { gap: 6px }`（L2644） | 保持（实测 `nameTop − avBottom = 6.0`） |
| 成组贴顶 64px 锚 | ②-b 负 margin（L2668）：`calc(var(--muse-head-top) + 64px + safe)` | 保持（实测 `headTop − chatTop = 64.0`） |
| 水平居中 | 基座 `align-items: center`（L2572-2577） | 保持（`avCx = chatCx = 187.5`） |
| sticky 顶锚 | `top: var(--muse-head-top)` + chatChrome.js 同步（L2640-2646） | 保持（滚动中 `headTop − chatTop = 0.0`） |
| 名字玻璃胶囊材质 | 基座 `--glass-bg` + blur（L2586-2588） | 未触碰 |
| 无名档幽灵条收口 | ②-c `#session-name:empty`（L2676-2678） | 未触碰 |

### 2.3 注释口径同步（零行为影响）

- `mobileLayer.css`：文件头批次索引新增「会话头头像收小批」一条；基座块 L2560/L2564/L2570
  与 Muse 块 L2623/L315 的 `78px` 字样改为当前值（大号头像 56px / 「44 vs 顶 56」）。
- `stand-tokens.css:249`：芯片 token 消费者注记 `桌面 28px / 手机 78px` → `手机 56px`。
- `chat.css:5342`：会话头中簇注记「52px 大头像」→「56px 大头像」（该注释已漂两档，一并校准）。

### 2.4 判据同步改写（钉死 78px 的三处 + 一处注记）

| 文件 | 原判据 | 现判据 |
|------|--------|--------|
| `tests/mobile-muse-avatar-shots.mjs:250-251` | `③ 大号圆头像 78px`（`\|avW−78\| ≤ 0.8`） | `③ 大号圆头像 56px`（`\|avW−56\| ≤ 0.8`） |
| `tests/muse-restore-smartcrop-shots.mjs:407` | `A① 大号圆头像 78px` | `A① 大号圆头像 56px` |
| `tests/dock-avatar-edge-unify-shots.mjs:186-189` | `⑤ 顶部 Muse 大头像 78px`（`\|topW−78\| ≤ 0.6`） | `⑤ … 56px`（`\|topW−56\| ≤ 0.6`） |
| `tests/mobile-avatar-chip-title-gap.spec.mjs:9` | 注记「桌面 28px / 手机 78px 同规则」 | 注记「手机 56px」（该 spec 不断言尺寸，仅口径） |

`tests/chat-head-position-shots.mjs` 不含尺寸判据（只断言位置/间距/居中/sticky），**未改一行**
—— 本批的「位置/间距/语义保持」正是靠它原样复跑全绿来证明。

---

## 3. 验收

### 3.1 新增 harness

`tests/muse-avatar-shrink-shots.mjs` → `evidence/20261102_muse-avatar-shrink/`：

| 帧 | 内容 |
|----|------|
| `rest-{light,dark}.png` | 375×812 修后静止态（含消息区比例观感） |
| `scrolled-{light,dark}.png` | 375×812 修后滚动态（sticky 锚贴窗口顶） |
| `desktop-1280.png` | 桌面回归（`#chat` 零身份头、`#header` 头像钮 28px） |

探针读数（双主题同值）：

- ① `avW × avH = 56.0 × 56.0`
- ② `nameTop − avBottom = 6.0`（间距保持）
- ③ `headTop − chatTop = 64.0`（贴顶锚保持）
- ④ `avCx = chatCx = 187.5`、`nameCx = 187.5`（居中保持）
- ⑤ `position = sticky`、`font-size = 14px`；滚动中 `headTop − chatTop = 0.0` 且头像仍 56
- ⑦ 桌面 `#chat` 零 `.g2-chat-head`、头像钮 `28 × 28`
- R0 每腿零 console error

### 3.2 相关 spec 复跑

| 目标 | 结果 |
|------|------|
| `tests/muse-avatar-shrink-shots.mjs`（新增） | **ALL PASS**（18 断言） |
| `tests/chat-head-position-shots.mjs` | **ALL PASS**（25 断言，零改动复跑） |
| `tests/mobile-muse-avatar-shots.mjs` | **ALL PASS**（144 断言，判据改 56 后） |
| `tests/muse-restore-smartcrop-shots.mjs` | **ALL PASS**（148 断言） |
| `tests/dock-avatar-edge-unify-shots.mjs` | **ALL PASS**（29 断言） |
| `tests/mobile-avatar-chip-title-gap.spec.mjs` | **ALL PASS**（152 断言） |
| `tests/mobile-dock-shape-shots.mjs` | **ALL PASS**（断言全绿） |
| 零 console error | 全部腿通过（含桌面 1280 回归） |

---

## 4. 未触碰面（边界声明）

- **桌面端**：`chat.css:5348` 的 `#agent-avatar-btn { 28px }`、`#header .header-center` 横排
  一行未改；本批所有改动挂在 `body.mobile-layer` 下。
- **dock 中键**：44px 外径、`--dock-center-size`、呼吸缝 10px 未改；其 spec 判据改成
  「顶部 56px 形态零触碰」，即本批改动后两件头像的**边缘语言一致性**仍全等（该 spec 的
  ① 断言逐字比对两件的 border/box-shadow）。
- **在飞批工作区**：只在本 delegate worktree（`delegate/delegate-nebflow-d616c46a`）改动，
  未进入 fm 三面续跑 / 全设备统一更新 的工作区与分支。

## 5. 交叉引用

- [[20261101_221500_muse-avatar-restore-stack-smartcrop]] —— 78px 的来源批（本批收小其上）。
- [[20261101_235748_chat-head-position-fix__delegate-nebflow-9c9ef879]] —— 位置批：64px 贴顶锚、
  6px 呼吸缝、`--muse-head-top` sticky 补偿，本批全部原样保留。
- [[20261101_225500_dock-avatar-edge-unify__delegate-nebflow-9770a0a7]] —— dock 中键与顶部头像
  边缘统一批（本批只改顶部头像的**尺寸**，边缘规格未动）。
