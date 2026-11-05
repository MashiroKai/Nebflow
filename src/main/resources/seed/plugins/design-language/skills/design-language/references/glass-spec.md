---
type: spec
status: active
---

# 毛玻璃规范（glass spec）—— 全站唯一术语 · 唯一标准

> 作者令（2026-11-05 16:46，逐字）：
> 「我们的毛玻璃效果没有统一，我们stand只允许一个毛玻璃效果。但是现在不知道是怎么
> 是怎么回事，你看图1，我它这个是半透明的，能看见底色的。然后图二这种就是不透明的
> 毛玻璃效果。我说了很多次但是还是没有统一到图2这种。……把毛玻璃效果作为一个唯一的
> 标准和术语，放到插件和前端组件。这样我说毛玻璃效果，就能找到那个统一的设计标准。」
>
> 本文是**「毛玻璃」这三个字的唯一落点**：谁听到/读到「毛玻璃」，答案就在这一页，
> 不再靠看图解释。可执行面 = `scripts/check-glass-unify.mjs`（前端门禁链
> `make frontend-gates` + CI）。**改判据改本文，改豁免也改本文**，脚本只是执行器。

## 0. 词条（术语表条目）

| 词条 | 别名 | 唯一落点 |
|---|---|---|
| **毛玻璃**（glass） | 玻璃、玻璃质感、frosted、glassmorphism | ① 令牌 `--nf-glass-*`（`css/stand-tokens.css`）② 类 `.nf-glass`（`css/glass.css`）③ 机检 `scripts/check-glass-unify.mjs` ④ 本文件 |

一句话标准：**高不透明白底（α ≥ 0.90）+ 模糊 + 细边 + 内高光，全站只此一档。**

## 1. 唯一形态（作者实拍图2）

- **底**：近不透明白（亮 `#ffffffF7` = α .97 / 暗 `#181c26F0` = α .94）。
  正文字号的内容在玻璃底下**不可读**，这是唯一的判据 —— 不是"看起来像磨砂"。
- **模糊**：`blur(24px) saturate(1.15)`，全站同一个值。
- **边**：`1px` 细边（亮 `#00000012` / 暗 `#ffffff17`）。
- **内高光**：顶缘 1px inset 高光 + 两档外投影（`--nf-glass-shadow`）。
- **顶缘折射线**（可选）：有横向上缘的面可加 `--nf-glass-refraction` 1px 渐变细线；
  圆钮等小面不加（1px 线在 40px 圆上不可读）。

## 2. 已判死刑的形态（作者实拍图1）

| 死因 | 症状 | 旧值（已退役） |
|---|---|---|
| 半透明透底 | 底下的正文大字以 45%/40% 强度透上来 = 作者说的「脏」 | `--glass-bg` 亮 .55 / 暗 .68 |
| 控件另立一档 | 同一屏里按钮比面板更透 | `--glass-control-bg` .60 / 暗档白色洗色 .14 |
| 各面手调白度 | 同一个毛玻璃在四处有四种白度 | .72（权限提示）/ .88（搜索日期浮层）/ .92（暗档浮层）/ .55（兜底字面量） |
| 按龄渐隐 | 「只有最新一条清楚，前面的半透明」 | `.ask-bub[data-age]{opacity:var(--ask-age-alpha)}` + JS 写 α |
| 模糊值各处自拟 | 8/10/12/14/16/20/24/26px、saturate 1.1–1.8 共 30 种写法 | 全部收敛到 `--nf-glass-filter` |

## 3. 怎么用（两条路径，第三条不存在）

| 场景 | 写法 |
|---|---|
| 能改标记 | 元素挂 `class="nf-glass"`（唯一工具类）。`.glass` / `.glass-control` 是它的**历史别名**，同一份配方、零自有值，新代码一律用 `.nf-glass` |
| 不能改标记（纯 CSS 面） | 把选择器加进 `css/glass.css` 的「成员登记表」，配方由该表统一供给 |
| ~~自己写 backdrop-filter~~ | **禁止**。`scripts/check-glass-unify.mjs` G1 判红 |

幕布（takeover 遮罩/灯箱底）**不是**毛玻璃面：它没有玻璃底与描边，只借模糊
把背景压下去，走 `.nf-glass-scrim` + `--nf-glass-scrim*`，不参与 `.nf-glass`。

## 4. 判据（机检五条，`scripts/check-glass-unify.mjs`）

| 编号 | 判据 | 命中形态 |
|---|---|---|
| G1 | `css/glass.css` 之外出现非 `none` 的 `backdrop-filter` ⇒ 红 | 第二套配方 |
| G2 | `glass.css` 内的非 `none` 值不是 `var(--nf-glass-filter)` ⇒ 红 | 配方走样（数值重新长回文件） |
| G3 | `--nf-glass-bg` 解析出的 α < 0.90 ⇒ 红 | **标准被稀释**（把 .97 调回 .55 = 图1 平静复活） |
| G4 | `--nf-glass-*` 在 `stand-tokens.css` 之外被**定义**（非别名） ⇒ 红 | 令牌双源 |
| G5 | canvas ask 的气泡/输入条缺 `.nf-glass` ⇒ 红 | 绕过唯一工具类 |

`backdrop-filter: none` 全网放行：那不是第二套毛玻璃，是**摘除**毛玻璃
（`@supports` 降级档 / 无 backdrop 引擎回落实底），性质相反。

## 5. 例外清单

**本批零例外。** 需要例外 = 说明标准有洞，先改标准（本文 + 令牌），不要开豁免口子。
（若将来确有必要，格式沿用 `flat-button-spec.md`：`| 选择器片段 | 判据号 | 理由 |`，
理由为空即为无效例外 ⇒ 脚本判红。）

## 6. 落点索引（新人只看这一节就能找回唯一标准）

| 面 | 文件 | 说明 |
|---|---|---|
| 设计令牌单一源 | `src/main/resources/web/css/stand-tokens.css` | `--nf-glass-*` 段（唯一出生地） |
| 前端唯一工具类 | `src/main/resources/web/css/glass.css` | `.nf-glass` + 成员登记表 |
| 机检 | `scripts/check-glass-unify.mjs` | 门禁链 `make frontend-gates` / `make glass` |
| 规范正文 | 本文件 | 术语、形态、判据、落点 |
