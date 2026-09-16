---
name: visual-report
description: 可视化汇报工具。用专业工具（matplotlib/graphviz/plotly 等）生成 SVG 图表，落盘后在交付文本里给绝对路径（展示权归 Nebula，节点不调用 Pop）。当需要制作图表、架构图、流程图、数据可视化、可视化报告时使用。
---

# Visual Report — 可视化汇报

用专业工具生成高质量可视化内容，落盘交付（展示与打开归 Nebula——节点侧无展示面，禁在交付文本里指示人类打开路径）。

**读者体验（结论先行、体量上限、术语翻译、读者轴落位、图预算声明）见本文「人读交付体例」节**；本 skill 专注图表生成层。

## 核心原则

**永远用专业工具生成图表，不要手写 SVG/HTML 画图。** 人类处理视觉信息远快于文字段落，一张好的图表能一眼传达段落才能解释的信息。

**语言跟随用户。** 图表中的标题、轴标签、图例、注释等所有文字，必须使用用户当前对话使用的语言。用户用中文则全中文标注，用英文则全英文。不要混用。

**样式嵌入和暗色模式适配参见 `design-cards` 插件的 `card-design`。** 本 skill 专注图表生成层的审美——配色、排版、标注、布局。

## 工作流

1. 用 Bash 运行专业工具 → **输出 SVG 格式**
2. 输出到文件（如 `/tmp/output.svg`）→ 把绝对路径写进交付文本
3. 单文档 **≤3 图**（硬性）：命中类别 n ≤3 全画，n >3 只画权重前 3（结论依赖的数据关系 > 时间线 > 流程/调用链 > 三方对比 > 结构/分层），其余降级表格/文字并在首屏加一行「图预算超限」声明；被省略类别是结论唯一载体 ⇒ 拆文档

## 什么时候用可视化

**适合：**
- 空间结构：架构图、流程图、组织架构图、网络拓扑
- 数据可视化：图表、曲线图、热力图、频谱、直方图
- 对比展示：并排对比、前后对比、标注截图
- 科学可视化：等高线图、矢量场、极坐标图、3D 曲面

**不适合：**
- 简单的文本摘要（直接说）——纯文字交付的体量与骨架约束见本文「人读交付体例」节（首屏 <8 KB/80 行、正文硬顶 20,300 字符、四段骨架）
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

只有在**信息本身需要视觉承载**、且产出物确实是**人读交付件**时，才产出完整 HTML 报告（而非裸 SVG + 文字回复）：

1. **信息有空间结构或大量图表**——平铺文字会丢失关系。这是**必要条件**：不满足即直接文字/单图回答；
2. **且**下列其一：① 该件是给人读的交付/汇报件（人读件落 `~/.nebflow/docs/<域>/`，体量与四段骨架见本文「人读交付体例」节）；② 交付形态本身要求 HTML（交互式报告、需直选回传的视觉选择题）。

**过程件不是 HTML 报告的触发对象**：设计方案、评审结论、取证记录、台账等过程件走 `<ws>/.nebflow/` 或本项目既有阶段文档路径，需要配图时按本 skill 出图。不为汇报而汇报。

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

创建完整 HTML 报告时，将 SVG 图表 + HTML 布局 + CSS 写入一个 `.html` 文件，落盘后把**绝对路径**写进交付文本（人读件落 `~/.nebflow/docs/<域>/`；展示权归 Nebula，节点不调用 Pop）。

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

# 3. 交付：把绝对路径写进交付文本（展示权归 Nebula）
echo /tmp/report.html
```

---

## 人读交付体例（节点 result 的一屏摘要与人读件共用）

给**人**看的文档与给机器看的取证件是两类东西：人读件落 `~/.nebflow/docs/<域>/`（活文档 `<topic>.md`、阶段件 `<YYYYMMDD>_<HHMMSS>_<topic>.md`），取证件/过程件落 `<ws>/.nebflow/{Spec,evidence,reports,tasks,results,tmp}/`；写在 `<ws>/.nebflow/**` 下再声明「人读」无效，要给人看必须外移。

**首屏四段骨架（顺序固定）**：

1. `### 结论` —— ≤5 行，**第一句就是结论**（不是背景、不是「本次任务」、不是过程叙述）。
2. `### 要做什么 / 有什么影响` —— 两栏表；影响必须回答「要不要你决定」「有什么功能会变」。
3. `### 大白话` —— **≤150 字符**、零术语：不含反引号、不含 `file:line`、不含 `n-xxxxxxxx`/`chain-`、不含未翻译英文词（`agent`/`Canvas` 等）。
4. `### 术语对照` —— 每个非日常词一句人话（无则写「无」）；术语只在此处第二次出现（第一次在正文首用时就地解释）。

**体量预算**：首屏 **< 8 KB 且 < 80 行**；正文（首字节 → `## 附录` 之前）按场景封顶——验收 ≤4,000 字符、改 bug·加功能 ≤8,000、调研·复盘 ≤12,764、产品设计 = 绝对硬顶 **20,300 字符**；`## 附录` 之后 ≤ 正文 5×、须有目录、**不得载唯一结论**。超出即先砍到上限再交付（禁「先发后砍」），长取证内容进附录。

**交付尾行**：完整件落盘 → result 文本 = 一屏摘要（骨架同上）+ **一句**「完整交付物：`<绝对路径>`」，禁粘长正文；禁在交付文本里指示人类打开路径（展示权归 Nebula，节点不调用 Pop）。

**该画必须画**：数值对比/趋势（≥2 组数值，或含 vs/对比/相差/倍数）⇒ 横向条形图；流程/调用链/状态机（全文箭头 ≥3）或结构/分层/拓扑 ⇒ graphviz；时间线（≥4 事件）⇒ 时间轴。预算与取舍见上文「工作流」第 3 条（单文档 ≤3 图 + 权重前 3 + 首屏「图预算超限」声明）；被省略类别是结论唯一载体 ⇒ 拆文档。

## Markdown 编写规则（图片嵌入 / 可跳转目录 / LaTeX 公式）

**① 图片与材料尽量嵌入，不要只给路径。** 用图语法把载体内联进正文：`![信号对比](</abs/path/chart.svg>)`。反例 = 路径文本冒充内容（`图表见 /abs/path/chart.svg`）——路径/文件清单只能作附录或清单件（取证批次的 `MANIFEST.md` / `index.json`）。大图集（> 20 张）：正文只嵌**决策相关子集**（对照拼图 `cmp-*`、逐项配对图 `adj-*`），全量清单进附录。内嵌路径须落在可服务面：`<ws>/.nebflow/evidence*/**` 或 `~/.nebflow/{projects,uploads,plots,workspace-items,voice-models}/**`（`~/.nebflow/docs/**` 会被拒）。

**② 内容多加可跳转目录。** 触发阈值 = 文档含 ≥3 个二级章节，或正文 > 6,000 字符；在正文开头列锚点目录，标题即锚点，标题改名时目录同批更新：

```markdown
- [结论](#结论)
- [改动面](#改动面)
```

短于一屏的文档不加目录（目录本身占首屏预算）。

**③ 公式一律用 LaTeX。** 行内写法 `$p = \frac{n}{N}$`；独立公式 `$$ … $$` 单独成段：

```markdown
$$
p = \frac{n}{N}
$$
```

反例：`E = m*c^2`（纯文本，上下标丢失）、把公式截图成 PNG 贴进文档（不可检索、不可复制）。
