---
name: network
description: 网络分析报告（networkx 度量+pyvis 交互）；图网络与关系结构任务使用。
---

# network — 网络·关系结构场景 skill

> 上游公共规范：`../core/SKILL.md`（铁律 / 嵌入路径 / 暗色 / 渲染器兼容 / 缺装降级总则 / 自检模板）。本文件只写 network 场景增量，通用规则一律引用 core，不复制漂移。

**独立成 skill 论证（一句）**：节点-边关系数据的核心信息在拓扑结构（社区、中心性、路径），坐标轴折线/柱状图无法承载，必须由图布局算法把拓扑映射到 2D 空间、以节点-边图元表达——这是一套独立的「图布局/社区结构图语汇」，故与通用 2D 图表分场景。

## 触发条件

命中任一即用本场景骨架交付：

- **关系网络分析**：实体间关系数据（社交/通信/引用/合作/依赖/传播路径），问题指向结构而非单体属性；
- **图结构指标**：聚类系数、最短路径、中心性、社区发现、连通性/直径等图算法产出是结论载体；
- **拓扑可视化**：需求本身是「画出这张网」（静态布局图 / 交互探索）。

不命中即换场景：层级/流程/依赖树（有向无环、适合分层）用 tech-architecture 的 graphviz 流程图语汇；纯属性对比用 office/eda；带地理坐标的地图网络属地图场景（folium/cartopy，README 扩展位，未装不接）。

## 报告骨架模板（五段，顺序固定）

**数据与建图 → 结构度量 → 静态布局图解 → 交互探索 → 结论与误区**

| 段 | 必含内容 | 硬约束 |
|---|---|---|
| 数据与建图 | 节点/边定义口径（节点是什么、边是什么、有向无向、权重含义）+ 规模（n、m） | 建图口径必须显式成文；随机图必须固定 seed 并写明（可复现硬性） |
| 结构度量 | 图算法真实计算值：聚类系数/平均路径/模块度社区/度分布等（按结论取舍） | 数值逐字（core §1.2）且由脚本真实算出；推荐给对照基线（如同边数随机图） |
| 静态布局图解 | matplotlib 布局图：颜色/大小编码属性，图例齐全 | 布局算法与 seed 写进图内标注；单文档 ≤3 图（core §1.3） |
| 交互探索 | pyvis 交互 HTML 产物（落 core §2 白名单，正文写绝对路径） | 自包含单文件（`cdn_resources='in_line'` 资源内联）；只落盘写路径，不指示人类打开（core §1.5） |
| 结论与误区 | 结构结论 + 误读对照表 | 每条结论可回溯到度量段数值；布局坐标解读仅限定性（见场景规范） |

骨架即验收单：缺任一段或段序颠倒，不算 network 交付。

## 工具链与自检

### 工具选择

- **主工具链：networkx（建图/图算法/布局坐标）+ matplotlib（静态布局渲染）+ pyvis（交互 HTML）**——networkx 出指标与布局坐标，matplotlib 出嵌入正文的双主题静态图，pyvis 出可拖拽/缩放/悬停的交互版独立产物；
- 布局选择：无先验结构用 spring 力导向（`spring_layout(G, seed=…)`，seed 必须固定）；社区结论用颜色编码社区、节点面积编码度；层级明显的有向图可换 graphviz dot（core §7，全路径调用）；
- matplotlib 全局配置（YaHei 中文）与出图落位走 core §7 / core §2。

### 工具自检（实测 2026-10-04 晚）

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| networkx | 建图/图算法/布局坐标 | `<venv>\python.exe -c "import networkx; print(networkx.__version__)"` → 3.7 | https://networkx.org/ · https://pypi.org/project/networkx/ | HTTP 200 · 200（2026-10-04 实测） | graphviz dot/neato 排布 + 指标文字表；小图（n≤20）纯 Python 邻接表手算指标 |
| pyvis | 交互网络 HTML | `<venv>\python.exe -c "import pyvis; print(pyvis.__version__)"` → 0.3.2 | https://pyvis.readthedocs.io/en/latest/ · https://pypi.org/project/pyvis/ | HTTP 200 · 200（2026-10-04 实测） | **networkx + matplotlib 静态布局图顶交互版**（交互段改为静态读图指南并声明交互版不可用） |
| matplotlib | 静态布局渲染器 | `<venv>\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2 | https://matplotlib.org/ | HTTP 200（2026-10-04 实测，README 链接验证表同） | plotly（viz-venv 已装）；再缺 ⇒ 邻接矩阵表 + 文字结构描述，不得手画（core §1.1） |

numpy（2.5.3）/ pandas（3.0.5）为套件共用基线，实测记录见套件 README 链接验证表，直接引用不复制。`<venv>` = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`。装包只进 viz-venv（core §5），pyvis 0.3.2 的枚举值是 `cdn_resources='in_line'`（带下划线，`'inlined'` 会 assert 失败）。

### 场景规范（本场景红线细则）

- **布局坐标无绝对语义**：spring 坐标只有「有边者趋于靠近」的倾向性，禁止在正文给出距离/角度/方向的定量解读；定量结论一律回到结构度量段；
- **可复现**：随机图生成与布局的 seed 必须写明且真实生效（`seed=…` 进建图与 `spring_layout` 两处）；
- **视觉编码有图例**：颜色=什么、面积=什么逐项写明，禁止无图例着色；
- **交互 HTML 暗色适配**：页框按 core §3 CSS 变量模板注入 `<head>`；vis-network 画布底色由 JS 固定（白底面板），交付说明写明「画布白底面板、页框随主题」。

### 降级策略

- pyvis 不可用 ⇒ networkx + matplotlib 静态布局顶上（正图即交付）；
- networkx 不可用 ⇒ graphviz dot/neato（edge list 直排）出布局 + 指标文字表；小图可纯 Python 手算（度、连通片、三角形计数），不空转；
- matplotlib 不可用 ⇒ plotly ⇒ 文字邻接表；任何一级都不得手画 SVG（core §1.1）。

## 质量红线

1. **建图口径显式**：节点/边定义、有向无向、权重含义三要素缺一不算交付；
2. **可复现且真算**：seed 全程固定；文中结构指标必须由脚本真实算出，禁止抄教科书数值；
3. **布局语义不越界、视觉编码有图例**（见场景规范）；
4. **交互 HTML 自包含单文件**（资源内联），落 core §2 白名单；
5. **禁止手画 SVG；数值逐字**（core §1.1/§1.2）；单文档 ≤3 图、marked v12 兼容（core §1.3/§4）。

## 示例

- `../demos/network.md` —— 30 节点小世界网络小样：`connected_watts_strogatz_graph(n=30, k=4, p=0.3, seed=42)` 真实计算 C/L/直径/模块度社区，同边数 ER 对照给出小世界商 σ≈1.73；matplotlib 静态布局图（社区着色 + 度定大小，亮暗双主题适配）+ pyvis 自包含交互 HTML，脚本 `demos/network_demo_gen.py` 重跑逐值可复现。
