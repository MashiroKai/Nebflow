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

- 一律使用 `~/...` 形式的绝对路径（`~` = `C:\Users\Kai`）嵌入：`![信号对比](~/.nebflow/plots/20261004_chart.svg)`。禁止相对路径、裸文件名、`file://`。
- 可服务面白名单（出图落这些位置，前端才能加载）：
  - `~/.nebflow/plots/**`（默认出图位）
  - `<ws>/.nebflow/evidence*/**`
  - `~/.nebflow/{projects,uploads,workspace-items}/**`
  - `~/.nebflow/docs/**` 只放人读件文档本体，作为图片源会被拒。
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

## 5. 缺装降级总则

- 优先级：换已有工具 > 纯文本降级；禁止因缺工具空转。
- 常用替换：流程/架构图 mermaid-cli → graphviz(16.1.0 已装)；时序图 wavedrom → matplotlib 阶梯线或文本时序表；电路图 schemdraw → 文字网表/引脚表（**不得因此手画 SVG**）。
- Python 包只允许装进 viz-venv：`C:\Users\Kai\.nebflow\tools\viz-venv\Scripts\python.exe -m pip install <pkg>`（网络慢加 `-i https://pypi.tuna.tsinghua.edu.cn/simple`）；禁止动系统 Python，禁止重复安装已有包。
- node 系工具经 `npx -y <pkg>` 直调，无需全局安装。

## 6. 工具自检小节模板（场景 skill 必含）

> 每个外部工具一条记录；「本机检测命令」必须逐条实测（which / --version / pip show），禁止凭记忆填。下方示例为实测真值（2026-10-04）。

```markdown
## 工具自检

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| graphviz | 流程/架构图 | `C:\Users\Kai\.nebflow\tools\graphviz\bin\dot.exe -V` → dot - graphviz version 16.1.0 | https://graphviz.org/download/ | HTTP 200（2026-10-04） | 改用 mermaid-cli 或文字层级列表 |
```

基础工具的完整实测记录见 `${SKILL_DIR}/../README.md` 链接验证表（含场景补充行），场景 skill 直接引用，不复制。

## 7. 本机基线速查（Windows / MSYS）

- Python（唯一绘图环境）：`C:\Users\Kai\.nebflow\tools\viz-venv\Scripts\python.exe`（3.12.9；numpy/scipy/matplotlib/pandas/plotly/pymupdf 已装；CairoSVG 装而不可用——缺原生 cairo DLL，PNG 目检走渲染器原生 PNG 输出）
- dot：`C:\Users\Kai\.nebflow\tools\graphviz\bin\dot.exe`（16.1.0，**不在 PATH**，必须全路径调用）
- matplotlib 全局配置：`C:\Users\Kai\.nebflow\tools\mplconfig\matplotlibrc`（YaHei 已配，中文默认可读）
- MSYS 传 Windows 路径参数：命令前加 `MSYS2_ARG_CONV_EXCL='*'`
- 出图默认落 `~/.nebflow/plots/`
