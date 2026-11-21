# 毛玻璃运行时判据（R1–R3 · `scripts/check-glass-runtime.mjs`）

> 仓内落点：原 stand-dev 插件包 `references/frontend/glass-spec.md` §9（插件包已无该文件，
> 见 af404938c 整包删除）。本页是阈值块 `glass-runtime-criteria` 的**仓内单源**，闸脚本
> `scripts/glass-runtime-criteria.mjs` 与严格腿 `tests/mobile-overlay-glass.spec.mjs` 直接读本页。
> 章节号沿用原 §9，便于与历史批次记录对照。
>
> glass-runtime 批（2026-11-18）作者报障逐字：「为什么现在我都是要求使用毛玻璃
> 效果 有些就是正常的不透字的毛玻璃模糊效果 而有些就是像这样透明的呢？这个是
> canvas手机端的标题 并且边框还没完全包裹住」

## 9.1 判据与不变式

G1–G8 判的是**源码里的配方**（单一源 / α / 配方逐值）。它们**读不到运行时**：一个
面的 `backdrop-filter` 写对了、`computed` 值也逐字正确，但浏览器**根本没在采样**
时，静态闸照样绿。本批 P0 正是这种形态 —— 手机 canvas 主胶囊
`.mf-strip-chip.on::after` 的配方逐字正确，但它的**宿主**带 `filter` 与
`will-change: transform, opacity`，宿主遂成为 **backdrop root**，把子件那层
`backdrop-filter` 的采样整条切断 ⇒ 台上只剩 α .16 洗色底、正文原文穿出。故另立
一道**看真像素**的运行时闸：

| 判据 | 命中形态 | 判红条件 |
|---|---|---|
| R1 **采样式生效** | 声明了毛玻璃但**什么都没做**（玻璃纸） | 对每个玻璃面拍两枪（本相 / 全站摘掉 `backdrop-filter` 的对照相），面内逐像素差 < §9.2 的 `effectMin`（真采样下限）⇒ 红；红时再看读数落在哪一档：< `inertMax` 记 `inert`（一个像素都没动）、≥ `inertMax` 记 `weak`（动了一点但不够）——**两档都红** |
| R2 **backdrop root 切断** | 玻璃面的祖先链上有会新建 backdrop root 的属性 | 祖先出现 `filter`≠none / `backdrop-filter`≠none / `opacity`<1 / `will-change` 含 `opacity`·`filter`·`backdrop-filter` ⇒ 红并指名那一层（R1 红时的根因行） |
| R3 **降级档一致性** | 无 backdrop 采样引擎里留下 α 低的玻璃纸 | 加 `html.no-backdrop` 后，本相是半透明玻璃底（α ∈ (0, §9.2 的 `degradeMinAlpha`)）的面，其底必须落不透明（α ≥ `degradeMinAlpha`）⇒ 红 |

**判据不变式（复测批 2026-11-18 收口，替代按数字调）：玻璃贡献只在背后样本可判时才成立。**

- 背后有可辨内容 ⇒ 该面**自己**的 `backdrop-filter` 必须改像素（Δpx ≥ `effectMin`）；
- 背后是平色背板 ⇒ 模糊常量色仍得常量色，该样本**不可判**（豁免，逐样本具名记录）；
- 该面被不透明层完全覆盖 ⇒ 它的贡献到不了最终画面，该样本**不可判**（豁免，逐样本具名记录）。

R1 的 Δpx 取**本相与对照相（全站摘掉 `backdrop-filter`）在该面自己盒内的逐像素差** ——
量的是这一片区域里玻璃贡献有多少；该面盒内若还叠着别的玻璃面，它的变化也计进这一读数。
要分辨读数来自哪一面，得另拍一枪「**只摘该面自身**（含其 `::before`/`::after`）」的对照相
（复测批 2026-11-18 的证据脚本给的就是这一对照）—— 闸的那一读数是「区间贡献」，**不等于**
「该面自己的贡献」。

**豁免不是阈值放宽**：§9.2 的数字一律不下调、不抬上限；豁免样本一律记「元素 + Δpx 原值 +
背板平整度 / 遮挡元素身份与几何」。

## 9.2 阈值（唯一出生地）

阈值的**唯一出生地是本节（§9.2）**：下面机器可读块里的键值就是全仓唯一的判据数字，由
`scripts/glass-runtime-criteria.mjs` 解析成 `CRITERIA` 交给两个消费者 —— R1
（`scripts/check-glass-runtime.mjs`）与严格腿（`tests/mobile-overlay-glass.spec.mjs` 的
`NEB_OVERLAY_STRICT_GLASS`），二者**共用同一份实现**。任何脚本、测试、散文**不得**再写一份
字面量；本节以外只许**引用键名**（本页其他小节也照此写），数值一律回本节读。

```glass-runtime-criteria
# 唯一阈值来源；消费者：scripts/glass-runtime-criteria.mjs
# effectMin        该面玻璃贡献必须改到的逐像素差（「真采样」下限）
# inertMax         低于此值 ⇒ 一个像素都没动（玻璃纸）
# flatEdgeMax      背板边缘能量低于此值 ⇒ 平色背板，样本不可判
# occluderMinAlpha 压在该面之上的层，底色 α ≥ 此值 ⇒ 视为「不透明遮挡层」（遮挡模型输入）
# degradeMinAlpha  降级档（html.no-backdrop）下的底不透明度下限
effectMin=3.0
inertMax=1.0
flatEdgeMax=2.0
occluderMinAlpha=0.9
degradeMinAlpha=0.9
```

**键义（散文只许这样引用键名，不许复述数字）**：

- `effectMin` —— **真采样下限**：该面自己的玻璃贡献必须把面内逐像素差改到 ≥ 它，改不到即 R1 红。
- `inertMax` —— **噪声底 / 玻璃纸下限**：Δpx < 它 ⇒ 一个像素都没动（档位记 `inert`）；它同时是
  **遮挡豁免**的判据（藏起该面后盒内逐像素差 < 它 ⇒ 该面被不透明层盖住，样本 `n/a`）。
- `flatEdgeMax` —— **平色背板判据**：藏起该面后其盒内背板边缘能量 < 它 ⇒ 模糊常量色仍得常量色，样本 `n/a`。
- `occluderMinAlpha` —— **遮挡模型输入**：压在该面之上、且非其祖先/后代的层，底色 α ≥ 它 ⇒ 视为
  不透明遮挡层（它与上一条共同决定 `covered` 豁免的名单与身份）。
- `degradeMinAlpha` —— **降级档底不透明度下限**：本相 α ∈ (0, 它) 的面，在 `html.no-backdrop`
  档下其底必须 ≥ 它，否则 R3 红。

> 判定顺序（消费者一律照此序，不得各写一份）：Δpx 缺测 ⇒ `n/a`；可见度 < `inertMax` ⇒
> `covered`（n/a）；背板 edge < `flatEdgeMax` ⇒ `flat-backdrop`（n/a）；否则 `Δpx ≥ effectMin`
> ⇒ `effective`，再按 < `inertMax` 分 `inert` / `weak`（后两档判红）。

## 9.3 实测边界与具名例外

**backdrop root 触发点（本机 chromium 实测，2026-11-18）**：`filter` 非 none
（含 `blur(0px)`·`saturate(1)` 这种视觉恒等值）/ `will-change: opacity` /
`opacity` < 1 / `backdrop-filter` 非 none ⇒ **切断**；`will-change: transform` /
`perspective` / `transform` / `contain: paint` / `isolation: isolate` ⇒ **不切断**。
故 R2 只认实测会切断的那几个（多认 = 假阳性，漏认 = 假阴性）。

**平色背板豁免**：背板是平色时，模糊一个常量色仍得常量色 —— 那不是缺陷。R1 把该
面自己藏起来再拍一枪，量它背后背板的边缘能量；低于阈值记 `n/a`，不判红。

**具名例外**：少数真缺陷面的修复需重排另一片 UI 的材质层（超出批次范围），以
具名例外登记在脚本 `NAMED_EXCEPTIONS`，带理由与证据，并计入「例外命中」行（例外
不命中 = 陈旧例外，会告警）。**禁止静默放宽**：任何未登记的 R1/R2/R3 红都会让闸
FAIL。当前登记：`desktop-canvas` 的 `.canvas-header` 坐在玻璃面 `#canvas-panel` 的
backdrop 子树里，实测其 blur 完全未采样（`blur(0px) ≡ blur(24px) ≡ blur(60px)`）——
修复需重排桌面面板/表头材质层，属独立批次。
