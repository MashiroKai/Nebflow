---
name: tech-architecture
description: 技术架构调研报告（graphviz 分层集群）；梳理架构、组件与数据流时使用。
---

# visual-report · tech-architecture — 技术架构调研场景

技术架构调研 / 组件梳理 / 数据流说明类报告的可视化规范。通用铁律、嵌入路径、dark-mode、渲染器兼容、降级总则一律见 `${SKILL_DIR}/../core/SKILL.md`，本文只写场景增量，禁止复制漂移。

## 触发条件

命中任一即用本场景：

- **架构调研**：摸清一个系统（开源项目 / 自有代码库 / 服务集群）的分层、进程、模块关系并产出报告；
- **组件梳理**：源码目录 → 模块职责清单 → 模块间依赖关系；
- **数据流说明**：一次请求 / 任务 / 事件穿过系统的路径（调用链、消息流、状态流转）。

不适用：数值对比与探索分析（→ eda）、信号/时序波形（→ timing）、学术图表（→ academic-research）。

## 报告骨架模板（四节顺序固定）

1. `## 架构总览` —— 一张总架构图（graphviz dot 分层集群图）+ ≤5 行结论先行：系统是什么、进程形态、分几大块。
2. `## 组件分解` —— 表格为主：模块 | 源码位置 | 职责 | 关键文件。每个模块的职责必须能指到具体文件或目录。
3. `## 数据流` —— 一张数据流图（编号步骤节点）+ 分步文字：每步标注 触发方 → 接收方 与载体（协议/文件/消息）。
4. `## 关键设计取舍` —— 表格：取舍点 | 选了什么 | 代价 / 边界。只写能在源码注释或官方文档里找到证据的取舍，禁止脑补设计动机。

图预算 ≤3 图（core §1.3）：总架构 1 + 数据流 1 为标配；结构特别复杂才加第 3 张（子域放大图），并在首屏声明图预算。

## 工具链与自检

架构图首选 graphviz dot（分层集群 + 依赖箭头），mermaid-cli 顶流程 / 状态类。检测命令 **2026-10-04 本机逐条实测**：

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| graphviz (dot) | 总架构图 / 数据流图 | `C:\Users\Kai\.nebflow\tools\graphviz\bin\dot.exe -V` → `dot - graphviz version 16.1.0 (20260904.0139)`（不在 PATH，须全路径） | https://graphviz.org/download/ | HTTP 200（2026-10-04，详见 README 验证表） | 改用 mermaid-cli；两者皆缺 → 纯文本缩进框线 |
| mermaid-cli | 流程 / 状态 / 时序 | `mmdc --version` → 未装（command not found，实测）；端点验活 `npm view @mermaid-js/mermaid-cli version` → `12.0.0` | https://mermaid.js.org/ · https://github.com/mermaid-js/mermaid-cli | 200 · ✗（github 超时；npm 官方 registry 端点与 npmmirror 镜像 200，详见 README 验证表） | node 已在位，可 `npx -y @mermaid-js/mermaid-cli` 直调；失败 → graphviz；再失败 → 纯文本缩进框线 |

- **降级链（不得空转，core §5）**：mermaid-cli → graphviz(dot) → 纯文本缩进框线（`├─` / `└─` 层级清单表达层次，禁止 ASCII 画箭头、禁止猜坐标）。
- **Mac 侧差异**：`brew install graphviz` 后 dot 进 PATH（实测 Mac 侧 `dot -V` → 14.1.5）；中文字体用 PingFang SC；mmdc 同样未装，npx 直调路径相同。
- **dark-mode 内嵌规范（core §3）**：优先 SVG（`currentColor` 或内嵌 `@media (prefers-color-scheme: dark)`）；PNG 无主题适配——透明底或浅色实底出图，节点浅底深字，亮色底对比度 ≥4.5:1；嵌文档前亮暗主题各目检一遍。

## 质量红线（场景追加）

1. **事实一致红线**：图中每个组件、每条连线必须与被调研系统的源码 / 官方文档事实对应——模块名照抄目录名或包名，职责描述须能落到具体文件；禁止臆造模块，禁止把「应该有」画成「有」。
2. **连线即证据**：数据流图每条边对应一次真实调用 / 消息 / 读写；不确定的交互不画——宁可少画，不可画错。
3. **工具真实渲染红线**：图必须由 dot / mmdc 真实执行渲染出文件后再嵌入，交付文本记录「渲染工具 + 版本 + 输出路径」；禁止手画 SVG、禁止文本框拼图（core §1.1）。
4. **版本与口径标注**：标注被调研系统的源码版本 / 读取日期（如「以 2026-10-04 工作树为准」）；跨设备侦查注明源码在哪台机器读取。

## 示例

- `${SKILL_DIR}/../core/references/demos/tech-architecture.md` —— Nebflow 自身架构调研 demo：graphviz 16.1.0 真实渲染总架构图 + 任务链数据流图，组件清单逐一对上源码事实（Mac 工作树只读侦查）。
