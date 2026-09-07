---
name: visual-report
description: 可视化汇报工具。用专业工具（matplotlib/graphviz/plotly 等）生成 SVG 图表，再用 Pop 工具在 Canvas 中展示。当需要制作图表、架构图、流程图、数据可视化、可视化报告时使用。
---

# Visual Report — 可视化汇报

用专业工具生成高质量可视化内容，通过 Pop 展示。

## 核心原则

**永远用专业工具生成图表，不要手写 SVG/HTML 画图。** 人类处理视觉信息远快于文字段落，一张好的图表能一眼传达段落才能解释的信息。

**语言跟随用户。** 图表中的标题、轴标签、图例、注释等所有文字，必须使用用户当前对话使用的语言。用户用中文则全中文标注，用英文则全英文。不要混用。

**样式嵌入和暗色模式适配参见 `design-cards` 插件的 `card-design`。** 本 skill 专注图表生成层的审美——配色、排版、标注、布局。

## 工作流

1. 用 Bash 运行专业工具 → **输出 SVG 格式**
2. 用 Pop 工具在 Canvas 中展示 → `Pop(filePath="/tmp/output.svg")`
3. 如需多张图，重复以上步骤

## 什么时候用可视化

**适合：**
- 空间结构：架构图、流程图、组织架构图、网络拓扑
- 数据可视化：图表、曲线图、热力图、频谱、直方图
- 对比展示：并排对比、前后对比、标注截图
- 科学可视化：等高线图、矢量场、极坐标图、3D 曲面

**不适合：**
- 简单的文本摘要（直接说）
- 无数据支撑的装饰性图形
- 可以用表格清晰表达的少量数据

## 专业工具对照表

| 场景 | 推荐工具 |
|------|---------|
| **折线/柱状/散点/热力图** | matplotlib, gnuplot, plotly |
| **科学绘图**（等高线、矢量场、极坐标、3D 曲面） | matplotlib, plotly |
| **流程图 & 框图** | graphviz (dot), mermaid-cli |
| **架构图 & 网络拓扑** | graphviz, networkx + matplotlib |
| **UML（类图/时序图/状态图）** | plantuml, mermaid |
| **时序图** | wavedrom |
| **电路原理图** | schemdraw (Python) |
| **频谱 / 波特图 / 眼图** | matplotlib + scipy |
| **史密斯圆图** | matplotlib (smithplot) |
| **分子结构** | rdkit, OpenBabel |
| **晶体结构** | pymatgen, ASE |
| **地图 & 空间分布** | folium, cartopy + matplotlib |
| **3D 模型** | OpenSCAD CLI, matplotlib 3D |
| **甘特图 / 时间线** | matplotlib, plotly, mermaid |
| **桑基图 / 树状图 / 雷达图** | plotly, squarify |

## 常见错误 — 正确做法 vs 错误做法

| 错误做法 | 正确做法 |
|---------|---------|
| 用 ASCII 画图表/曲线 | matplotlib/gnuplot → SVG |
| 用文本框+箭头拼流程图 | graphviz/mermaid → SVG |
| 手写 SVG `<path>` 画电路 | schemdraw/wavedrom → SVG |
| 猜坐标画数据图 | 用专业工具从数据生成 |
| 彩虹色编码分类数据 | 限制调色板，用 1 个强调色 |
| 图表内硬编码 hex 颜色 | 用调色板变量，通过 CSS 变量适配主题 |

## 图表设计规范

### 配色：单一强调色原则

**一张图表只有一个视觉故事。** 用一种强调色突出关键数据，其余数据用中性灰退到背景。

**分类调色板**（最多 5 类，超过 5 类考虑分组或换图表类型）：

```python
# matplotlib 调色板 — 通过 CSS 变量名引用，生成时用具体值
NEUTRAL = '#8a8f98'       # 非强调系列
ACCENT  = '#5e6ad2'       # 强调系列（sapphire blue）
ACCENT_LIGHT = '#a0a8e8'  # 强调系列浅色（填充/半透明）

# 序列型（热力图、渐变）：单一色相明度渐变
SEQUENTIAL = ['#e8eaed', '#b8bfd4', '#8893b3', '#586792', '#3a4a75', '#1e2e58']
```

**禁止：**
- 彩虹色 / jet 色谱编码分类数据——视觉上无序
- 每个数据系列用不同色相——竞争注意力
- 纯黑 `#000` 或纯白 `#fff` 作数据色——与主题冲突

### 排版

- **字体**：生成时用系统字体栈，不嵌入自定义字体
  ```python
  plt.rcParams['font.family'] = '-apple-system, BlinkMacSystemFont, "SF Pro Display", sans-serif'
  plt.rcParams['font.size'] = 12        # 正文
  plt.rcParams['axes.titlesize'] = 16   # 标题
  plt.rcParams['axes.labelsize'] = 13   # 轴标签
  ```
- **标题**：16-20px / weight 600 / 负字距（`plt.rcParams['axes.titlepad'] = 16`）
- **轴标签**：13px / weight 400，简洁——能省则省
- **刻度**：11-12px / weight 400，`font-variant-numeric: tabular-nums`（等宽数字）
- **图例**：不要放图表内，用 `ax.legend(loc='upper left', bbox_to_anchor=(1, 1))` 放右侧或底部
- **图例标题**：省略——直接在图例标签中体现分组

### 标注规范

- **直接标注 > 图例**：线条少时（≤3 条），直接在线条末端标注系列名，不用图例
- **数值标注**：仅在关键点标注，不要每个数据点都标
- **单位**：在轴标签中标注（如"能量"），不要在刻度数字后重复
- **注释**：用箭头指向关键点，文字简洁
  ```python
  ax.annotate('峰值', xy=(3, 4.2), xytext=(3.5, 4.8),
              arrowprops=dict(arrowstyle='->', color=NEUTRAL))
  ```

### 留白与布局

- **figure 大小**：宽 8-10 inch，高 4-5 inch（16:9 到 2:1）
- **边距**：`plt.tight_layout(pad=1.5)` 或 `constrained_layout=True`
- **网格线**：仅水平网格（`ax.yaxis.grid(True, alpha=0.3)`），不画垂直网格
- **轴线**：去掉上右边框（`ax.spines['top'].set_visible(False)`, `ax.spines['right'].set_visible(False)`）
- **数据墨水比**：每一滴墨水都要传达信息。去掉装饰性边框、背景色、3D 效果

### graphviz 架构图规范

- **节点配色**：用语义色，不用随机色
  - 客户端/前端 → 中性灰
  - 服务端/核心 → 强调蓝
  - 数据存储 → 暖色（与冷色形成对比）
  - 外部服务 → 虚线边框
- **统一形状**：同类节点用同形状，不要一个 box 一个 oval
- **方向**：`rankdir=LR`（左到右）适合流程，`rankdir=TB`（上到下）适合层级
- **标签**：edge label 简短（协议名、数据类型），不要放完整句子

```bash
cat > /tmp/arch.dot << 'EOF'
digraph {
  rankdir=LR
  node [shape=box, style="filled,rounded", fontname="-apple-system", fontsize=12]
  edge [fontname="-apple-system", fontsize=10, color="#8a8f98"]

  Client  [fillcolor="#e8eaed", fontcolor="#1b1e26"]
  Server  [fillcolor="#5e6ad2", fontcolor="#ffffff"]
  DB      [shape=cylinder, fillcolor="#e8eaed", fontcolor="#1b1e26"]

  Client -> Server [label="HTTP"]
  Server -> DB     [label="SQL"]
}
EOF
dot -Tsvg -o /tmp/arch.svg /tmp/arch.dot
```

## SVG 生成示例

### matplotlib 折线图

```python
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

plt.rcParams['font.family'] = '-apple-system, BlinkMacSystemFont, sans-serif'
plt.rcParams['font.size'] = 12
plt.rcParams['axes.titlesize'] = 16
plt.rcParams['axes.titleweight'] = 600
plt.rcParams['axes.spines.top'] = False
plt.rcParams['axes.spines.right'] = False

NEUTRAL = '#8a8f98'
ACCENT  = '#5e6ad2'

fig, ax = plt.subplots(figsize=(8, 4))
ax.plot([1,2,3,4], [1,4,2,3], color=ACCENT, linewidth=2, marker='o', markersize=5, label='实测值')
ax.plot([1,2,3,4], [1.5,3,2.5,3.5], color=NEUTRAL, linewidth=1.5, linestyle='--', label='理论值')

ax.set_xlabel('时间')
ax.set_ylabel('幅度')
ax.set_title('信号对比')
ax.yaxis.grid(True, alpha=0.3)
ax.legend(loc='upper left', bbox_to_anchor=(1, 1), frameon=False)
plt.tight_layout(pad=1.5)
plt.savefig('/tmp/chart.svg', format='svg')
```

## 什么时候生成完整 HTML 报告

满足以下**任一**触发条件时，产出完整 HTML 报告（而非裸 SVG + 文字回复）：

1. **内容需要视觉确认**——报告本身是给人审阅/验收的成品（设计方案、评审结论、数据分析交付物）；
2. **可视化的可读性显著优于 Markdown**——信息有空间结构或大量图表，平铺文字会丢失关系。

两个条件都不满足时，直接文字/单图回答，不为汇报而汇报。

## SVG 的暗色模式适配

生成的 SVG 文件本身要能在亮/暗主题下都可读（HTML 外壳的 CSS 变量规范见 `design-cards` 插件的 `card-design`，此处只管 SVG 内容层）：

- **不在 SVG 里硬编码前景色**：线条/文字优先用 `currentColor`，或在 SVG 内嵌 `<style>` 用 CSS 变量引用；
- **SVG 内嵌媒体查询**（独立 SVG 文件被 `<img>` 引用时也生效）：
  ```xml
  <style>
    .lbl { fill: #1b1e26; }
    @media (prefers-color-scheme: dark) { .lbl { fill: #e8eaed; } }
  </style>
  ```
- **matplotlib 等工具生成时**：前景元素用中性色常量（如本 skill 的 `NEUTRAL`/`ACCENT` 调色板），避免纯黑 `#000` 文字在暗色底上消失；输出前在暗色背景下目检一遍；
- **深色底填充**（面板、节点底色）可以硬编码，但文字/线条对比度两条主题下都要 ≥4.5:1。

## 可视化 HTML 报告

创建完整 HTML 报告时，将 SVG 图表 + HTML 布局 + CSS 写入一个 `.html` 文件，用 Pop 打开。

**样式规范参见 `design-cards` 插件的 `card-design`** — CSS 变量、圆角、字体、暗色模式适配、SVG 嵌入策略。以下仅展示结构：

```bash
# 1. 生成图表
python3 /tmp/plot.py  # 输出 /tmp/chart.svg

# 2. 创建 HTML 报告（样式用 CSS 变量，不要硬编码 hex）
cat > /tmp/report.html << 'HTMLEOF'
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><style>
body {
  font-family: -apple-system, BlinkMacSystemFont, sans-serif;
  max-width: 900px; margin: 0 auto; padding: 32px;
  background: var(--color-bg); color: var(--color-text);
}
h1 { font-size: 20px; font-weight: 600; letter-spacing: -0.02em; }
.chart { margin: 24px 0; }
.chart svg { width: 100%; height: auto; }
</style></head>
<body>
<h1>分析报告</h1>
<div class="chart">
  <!-- 内联 SVG 内容 -->
</div>
</body>
</html>
HTMLEOF

# 3. 用 Pop 展示
Pop(filePath="/tmp/report.html")
```
