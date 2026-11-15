---
name: core
description: 全场景公共规范（铁律/嵌入路径/暗色/渲染器兼容/自检模板）；所有场景动笔前必读。
---

# visual-report · core — 全场景公共规范

本文件是七个场景 skill（academic-research / tech-architecture / engineering / office / education / eda / timing）的公共上游规范。场景 SKILL.md 只写场景增量，通用规则一律引用本文，禁止复制漂移。工具链现状与链接验证记录见 `${SKILL_DIR}/../README.md`。

## 1. 铁律（违反即返工）

1. **禁止手画 SVG（铁律）**：一切图表必须由专业工具生成——matplotlib / graphviz(dot) / mermaid-cli / schemdraw / wavedrom / plotly 等，输出 SVG/PNG 落盘。禁止手写 `<path>`/`<rect>` 拼图、禁止文本框+箭头伪画、禁止 ASCII 画图、禁止猜坐标。工具缺失走 §5 降级，不空转。
2. **数值逐字红线**：图内与正文数值必须与数据源逐字一致——不四舍五入、不写「约」、不脑补缺失值、不改单位与口径；源里没有的数不画不写。来源口径（实测/拟合/引用）标注跟数走。
3. **单文档 ≤3 图（硬性）**：命中类别 n≤3 全画；n>3 只画权重前 3（结论依赖的数据关系 > 时间线 > 流程/调用链 > 三方对比 > 结构/分层），其余降级表格/文字并在首屏加一行「图预算超限」声明。被省略类别是结论唯一载体 ⇒ 拆文档。
4. **语言跟随用户**：图内标题/轴标签/图例/注释与交付正文同语言，不混用。
5. **展示权归 Nebula**：节点只落盘 + 在交付文本写绝对路径；禁止指示人类打开路径，禁止调用 Pop。

## 2. 图片嵌入路径规范

> **适用面（作者令 2026-11-05 09:24「文档相关的图片要放文档里」）**：本节管**交付文本 /
> 卡片里的嵌图**（聊天面渲染，后端按白名单直服绝对路径）。**vault 知识文档**
> （`.stand/docs/**` 的 md/HTML）里的图片是另一个渲染面、另一套解析器——那里一律
> **vault 内文件 + vault 相对路径**，绝对路径 `~/...`/`file://` 反而不合规；规则与
> 校验见 wiki-docs 强制条款 ⑥（`check_wiki_docs.py` 规则 `image-outside-vault`）。
> 一件产物两用时的正解：出图落白名单 → 同批把图拷进 vault（`attachments/` 或
> `assets/`）→ 文档里引用 vault 内副本。两节不合并、不改写。

- 一律使用 `~/...` 形式的绝对路径（`~` = `C:\Users\name`）嵌入：`![信号对比](~/.stand/plots/20261004_chart.svg)`。禁止相对路径、裸文件名、`file://`。
- **出图落位 = 数据根下 `plots/`**：命名空间固定是 `plots`，**根随实例走**——默认数据根 = `~/.stand`（2026-10-20 数据根迁移后的口径，运行时解析点 `PathUtil.dataRoot`，见 `src/main/scala/nebflow/shared/paths.scala`）；隔离实例（`--home` / `STAND_HOME`）换根时前缀随之替换。`~/.nebflow/plots/` 是迁移前的旧根，只在老机器未迁移时才是数据根。
- 可服务面白名单（出图落这些位置，前端才能加载）——与引擎侧 `FileRefs.DataRootServedNamespaces`（`src/main/scala/nebflow/core/tools/FileRefs.scala:135`）和 `NfFilePolicy.NfDataRootAllowlist`（`src/main/scala/nebflow/gateway/NfFilePolicy.scala:152`）是同一张清单，三处不许各写一份：
  - `~/.stand/plots/**`（默认出图位）
  - `<ws>/.nebflow/evidence*/**`（工程工作区的 `.nebflow` 目录名是历史事实，不随数据根改名）
  - `~/.stand/{projects,uploads,workspace-items,voice-models}/**`
  - `~/.stand/docs/**` 只放人读件文档本体（图不入这里，图归 `plots/` 或证据树）。
- 路径含空格或中文时整体用 `<...>` 包裹：`![图](~/<带 空格 目录>/a.svg)`。
- 大图集（>20 张）：正文只嵌决策相关子集（对照拼图 `cmp-*`、逐项配对 `adj-*`），全量清单进附录 MANIFEST/index。

## 3. dark-mode 规范与 Card/Pop 嵌入约定

**CSS 模板（HTML 卡片外壳统一用这一份变量表）：**

```css
:root {
  --color-bg: #ffffff; --color-card: #ffffff; --color-text: #1b1e26;
  --color-muted: #8a8f98; --color-accent: #5e6ad2; --color-border: #e8eaed;
}
@media (prefers-color-scheme: dark) {
  :root {
    --color-bg: #14161c; --color-card: #1b1e26; --color-text: #e8eaed;
    --color-muted: #8a8f98; --color-accent: #a0a8e8; --color-border: #2a2e38;
  }
}
body { background: var(--color-bg); color: var(--color-text); }
```

**SVG 内容层约定：**
- 线条/文字优先 `currentColor` 或内嵌 `<style>` + CSS 变量；独立 SVG 文件被 `<img>` 引用时必须内嵌 `@media (prefers-color-scheme: dark)` 块；
- 深色底填充（面板/节点底色）可硬编码；文字/线条对比度在亮暗两主题下都 ≥4.5:1；
- matplotlib 出图用调色板常量（中性灰 `#8a8f98`、强调 `#5e6ad2`），禁止纯黑 `#000` / 纯白 `#fff` 作数据色。

**Card/Pop 嵌入约定：**
- **Card**：SVG 用内联方式嵌（继承卡片 CSS 变量，随主题走）；确需 `<img>` 嵌 SVG 时，SVG 必须自带暗色 media query；PNG/JPG 无主题适配——用透明底出图，或 light/dark 双份 + CSS 切换；
- **Pop**（悬浮预览）：只挂静态图源（SVG/PNG 的 `~/...` 绝对路径），不放交互内容与脚本；
- 嵌入后亮暗两主题各目检一遍再交付。

## 4. 渲染器兼容规则（本部署 marked v12）

1. `##` 后必须带一个空格：写 `## 标题`，不写 `##标题`（后者不识别为标题）。
2. GFM 表格：分隔行（`|---|---|`）的列数必须等于表头列数，列数不齐整表渲染失败。
3. 数学只用 `$...$` 行内形式；独立公式也用 `$...$` 单独成段（前后各留空行）。不用 `$$...$$`、不用 `\(...\)` / `\[...\]`。
4. `breaks: true` ⇒ 单个换行渲染为强制换行而不是分段：**段落之间必须空一行**，不要靠单换行分段。
5. **缩进只认 `&emsp;&emsp;`（排版批，作者裁定 2026-11-05 11:09）**：Markdown 没有布局，Markdown 里"看起来缩进了"的写法在渲染器里两种都会塌——两个半角空格被 HTML 折叠吃掉（`<p>  文</p>`，视觉零缩进），四个半角空格把这一行变成代码块（`<pre><code>`）。本部署 marked v12.0.2 实测，`&emsp;&emsp;`（HTML 实体）是唯一稳定生效的段落缩进；Obsidian 同为实体解码面，一份源两边都成立。正文段落首行缩进、续行不缩进；标题/列表/表格/引用/围栏/图片行与 <40 字符短行不缩进。
6. **彩色只认三个语义色、一篇 ≤4 处（同批）**：颜色只能来自内联 HTML `<span style="color:…">`——蓝 `#5e6ad2` = 结论/判定、红 `#d0342c` = 风险/警告、绿 `#12874a` = 已验证/通过（三色亮/暗两主题对比度均 ≥3.6:1）。一般强调用加粗，不许满篇彩色；单处着色不超过 120 字符。
7. **缩写首次出现必须展开（缩写批，作者裁定 2026-11-05 11:11）**：全大写 2-6 字母 token（GCR / ICRP / IAEA / CERN / NIST / MC / NIEL…）与带点专有缩写（`E.C.`）在文档首次出现处给全称（`银河宇宙线（GCR）`）或挂词条（`[[GCR]]（全称）`）。
8. **平实语言（写作风格批 v2，作者令 2026-11-05 12:09）**：能用平实动词+名词说清
   的，不用抽象名词包裹。禁用词表（`人面子` / `印象` / `抓手` / `赋能` / `闭环` /
   `颗粒度` / `叙事` / `心智` / `沉淀` / `对齐` …）住在 `skills/wiki-docs/SKILL.md` 的
   `<!-- wiki-plainwords:start -->` 标记块，校验器逐条报 `文件:行` + 建议替换。
9. **结论先行 + 细节折叠（同批）**：文档三层——开头一段总体结论 → 每个 `##` 章节
   首行一句加粗的分点结论 → 论证细节放进 `<details><summary>…</summary>` 折叠块。
   `<details>` 是两台渲染器（本部署 marked v12.0.2 实测 + Obsidian 原生 HTML）都认
   的唯一折叠形态；Obsidian 的 callout 折叠（`> [!note]-`）在 marked 里退化成引用块
   + 字面标记，不要用。
10. **卡片分区（同批）**：每部分内容一张卡——```` ```human ```` 围栏就是卡片（产品侧
    渲染成 `.nf-human-card`，围栏外统一收进 `.nf-human-details` 抽屉），不要另造卡片
    组件。卡里放结论与关键数字，论证进紧随其后的折叠块。
11. **图的简约风格与图注（同批）**：图只画形状，不写解释——图内只有轴标签、刻度、
    短图例（每条≤一行）与最多一个短标注；配色走灰阶 + 虚实线区分，无网格、无图内标题。
    解释文字放图注（`图 N …`，一句结论，≤120 字符）与正文。基准 = DOSTEL `figures/
    20261103_cabin_neutron_energy_weight_flux/` 那批（参数单源见 `fig_style.py`）。

> 第 5-7 条的条款原文、允许清单与正反例在 `skills/wiki-docs/SKILL.md`（强制条款 ⑦⑧ ＋「Document typography」「Abbreviations」两节）；第 8-11 条同文件条款 ⑨⑩⑪ ＋「Plain language」「Document skeleton」「Card face」「Figures」四节。机器面是 `check_wiki_docs.py` 的 R7-R12；本节只写渲染器事实与骨架，不重复规范原文。

## 5. 缺装降级总则

- 优先级：换已有工具 > 纯文本降级；禁止因缺工具空转。
- 常用替换：流程/架构图 mermaid-cli → graphviz(16.1.0 已装)；时序图 wavedrom → matplotlib 阶梯线或文本时序表；电路图 schemdraw → 文字网表/引脚表（**不得因此手画 SVG**）。
- Python 包只允许装进 viz-venv：`C:\Users\name\.nebflow\tools\viz-venv\Scripts\python.exe -m pip install <pkg>`（网络慢加 `-i https://pypi.tuna.tsinghua.edu.cn/simple`）；禁止动系统 Python，禁止重复安装已有包。
- node 系工具经 `npx -y <pkg>` 直调，无需全局安装。

## 6. 工具自检小节模板（场景 skill 必含）

> 每个外部工具一条记录；「本机检测命令」必须逐条实测（which / --version / pip show），禁止凭记忆填。下方示例为实测真值（2026-10-04）。

```markdown
## 工具自检

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| graphviz | 流程/架构图 | `C:\Users\name\.nebflow\tools\graphviz\bin\dot.exe -V` → dot - graphviz version 16.1.0 | https://graphviz.org/download/ | HTTP 200（2026-10-04） | 改用 mermaid-cli 或文字层级列表 |
```

基础工具的完整实测记录见 `${SKILL_DIR}/../README.md` 链接验证表（含场景补充行），场景 skill 直接引用，不复制。

## 7. 本机基线速查（Windows / MSYS）

- Python（唯一绘图环境）：`C:\Users\name\.nebflow\tools\viz-venv\Scripts\python.exe`（3.12.9；numpy/scipy/matplotlib/pandas/plotly/pymupdf 已装；CairoSVG 装而不可用——缺原生 cairo DLL，PNG 目检走渲染器原生 PNG 输出）
- dot：`C:\Users\name\.nebflow\tools\graphviz\bin\dot.exe`（16.1.0，**不在 PATH**，必须全路径调用）
- matplotlib 全局配置：`C:\Users\name\.nebflow\tools\mplconfig\matplotlibrc`（YaHei 已配，中文默认可读）
- MSYS 传 Windows 路径参数：命令前加 `MSYS2_ARG_CONV_EXCL='*'`
- 出图默认落 `~/.stand/plots/`（= 数据根下 `plots/`；隔离实例换根时前缀随之替换，口径见 §2）
