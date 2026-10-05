# visual-report-suite — 场景总目录（CATALOG）

> 拿到可视化任务**先查本表**选场景；套件落位、全工具链接验证、MCP 结论、扩展位见 [`README.md`](README.md)；全场景公共规范见 [`core/SKILL.md`](core/SKILL.md)。
> 维护约定：新增场景 → 新建 `<scene>/SKILL.md` + `demos/<scene>.md` → 补入本表与 README 工具验证表；表中工具版本均为本机实测值（一期 2026-10-04、二期 2026-10-04/05，来源各场景上游回执与收口复核），引用前超 30 天须复测。

## 1. 总目录表（一期 7 + 二期 10 + 共享动效模块）

| 场景 | skill 路径 | 核心工具（实测版本） | 典型报告形态 | demo |
|---|---|---|---|---|
| academic-research 学术调研 | academic-research\SKILL.md | pymupdf 1.28.2（两遍法裁图）· graphviz 16.1.0 · matplotlib 3.11.2 | 论文原图图集报告（四件套图注；原图为主体，自绘汇总 ≤3） | demos\academic-research.md |
| tech-architecture 技术架构 | tech-architecture\SKILL.md | graphviz 16.1.0（dot 分层集群，全路径调用）· mermaid-cli 未装（npx 可调 12.0.0） | 架构调研报告（总览图 → 组件分解 → 数据流 → 取舍） | demos\tech-architecture.md |
| engineering 工程测试 | engineering\SKILL.md | matplotlib 3.11.2 · numpy 2.5.3 / scipy 1.18.1 / pandas 3.0.5 · graphviz 16.1.0 | 工程/实验/性能验证报告（五节骨架 + 四列指标判定表） | demos\engineering.md |
| office 办公 | office\SKILL.md | matplotlib 3.11.2（自律 ≤1 图，MPLCONFIGDIR 已接线）· 表格为主 | 周报/汇报（结论先行四段）· 会议纪要（决议/待办/owner） | demos\office.md |
| education 教育讲解 | education\SKILL.md | 静态底座：matplotlib 3.11.2 · numpy 2.5.3 / scipy 1.18.1 · graphviz 16.1.0；**动效层**：Remotion 4.0.532 · three.js 0.186.1 · mpl_anim（一律经 motion-toolkit，引用不复制） | 概念讲义/课件（直觉→形式化→图解→误区→练习）；**动效三形态**：静态分步图 / 交互 3D HTML / Remotion 讲解视频 | demos\education.md · demos\education-motion.md（三形态对照） |
| eda 电路 | eda\SKILL.md | schemdraw 0.23（原理图唯一合规生成）· matplotlib 3.11.2 · graphviz 16.1.0 | 电路原理讲解报告（原理图 → 逐级原理 → 参数表） | demos\eda.md |
| timing 时序 | timing\SKILL.md | wavedrom-cli 3.2.0（npx 主途径）· wavedrom(py) 2.0.3.post3（备）· matplotlib（降级） | 接口/总线时序报告（信号表 → 波形图 → 逐段解读） | demos\timing.md |
| biology 生物分子 | biology\SKILL.md | rdkit 2026.03.6（2D 骨架/描述符/构象）· matplotlib 3.11.2 | 分子报告（结论先行 → 结构呈现 → 描述符定量 → 构象 3D → 复算） | demos\biology.md |
| chemistry 化学 | chemistry\SKILL.md | rdkit 2026.03.6（结构/反应引擎验证）· 3Dmol.js 2.5.5（自包含交互 HTML） | 反应报告（反应式引擎验证 + 物种网格 + 可旋转 3D） | demos\chemistry.md |
| architecture 建筑空间 | architecture\SKILL.md | matplotlib 3.11.2（patches 米制平面/轴测）· three.js 0.186.1（经 motion-toolkit 体块交互） | 建筑方案报告（米制平面+尺寸链 → 立面/体块轴测 → 交互体块） | demos\architecture.md |
| mathematics 数学 | mathematics\SKILL.md | matplotlib 3.11.2 + mpl_anim（motion-toolkit 封装）· manim 0.21.0（MarkupText 路线；MathTex 需 LaTeX 本机不可用） | 数学讲解（过程动画 + 误差定量 + manim 公式对照） | demos\mathematics.md |
| art 艺术色彩 | art\SKILL.md | p5.js 1.11.10（本地库内联自包含 HTML）· matplotlib 3.11.2（色轮/明度阶） | 生成艺术 + 色彩分析（算法+种子+参数可复现，双渲染像素 diff 实证） | demos\art.md |
| music 音乐 | music\SKILL.md | numpy 2.5.3 / scipy 1.18.1（FFT/频谱）· matplotlib 3.11.2 · tone.js 15.1.22 / WebAudio（可听化） | 乐理讲解（频谱/钢琴卷帘/语谱图 + 可听化交互 HTML） | demos\music.md |
| geo-map 地理 | geo-map\SKILL.md | cartopy 0.26.0（静态投影，离线主交付）· folium 0.20.0（交互地图） | 地理报告（choropleth 静态 PNG 主交付 + 交互地图 HTML） | demos\geo-map.md |
| astronomy 天文 | astronomy\SKILL.md | skyfield 1.55（星历真算，de421/hip 缓存就位）· matplotlib 3.11.2（极坐标星图） | 天文报告（星空圆顶图 + 天象数值表；天文数值禁降级手算） | demos\astronomy.md |
| finance 金融行情 | finance\SKILL.md | mplfinance 0.12.10b0（K线/量价/MA）· pandas 3.0.5 · matplotlib 3.11.2 | 行情复盘报告（K线主图 + 辅助图；模拟数据「首屏+图内」双声明） | demos\finance.md |
| network 图网络 | network\SKILL.md | networkx 3.7（建图/度量）· pyvis 0.3.2（交互 HTML，`in_line` 内联）· matplotlib 3.11.2 | 网络分析报告（静态布局 + 结构度量 + 交互探索） | demos\network.md |
| **motion-toolkit**（共享动效模块，非场景） | motion-toolkit\SKILL.md | Remotion 4.0.532（视频栈）· three.js 0.186.1（交互栈）· mpl_anim + imageio-ffmpeg 7.1（降级动画栈） | 非场景：MP4 视频 / 自包含交互 HTML / MP4·GIF 动画的**统一渲染封装与降级链**（环境说明唯一来源） | 样张 demos\education-motion.md；冒烟 `~\.nebflow\plots\motion-toolkit-smoke\` |

> **motion-toolkit 被引用方**：education（动效层三形态）、mathematics（mpl_anim 出片）、architecture（three.js 体块交互）；其余场景出现动效/交互 3D 需求时同样只引用不复制。

## 2. 选 skill 决策树

拿到任务按序执行：

1. **出口判定（先做）**：纯文字结论 / 纯表格 / 用户无图诉求 → **不进套件**，直接写文本交付（不为图而图）。
2. **查本表按内容形态路由**（拿不准时取报告主体形态；并存形态选承载结论的那类）：

| 内容形态 | 场景 |
|---|---|
| 讲义 / 概念讲解 | education（需动效叠加 motion-toolkit：过程叙事→MP4；空间探索→交互 3D；拿不准出三形态对照） |
| 研究报告 / 文献调研（论文原图） | academic-research |
| 软件架构 / 组件梳理 / 数据流 | tech-architecture |
| 数字时序 / 接口握手波形 | timing |
| 电路 / 原理图 | eda |
| 办公（周报 / 汇报 / 纪要） | office |
| 工程测试 / 实验 / 性能验证 | engineering |
| 分子 / 药物 | biology（结构+性质）· chemistry（反应+3D 交互） |
| 函数 / 收敛 / 几何构造 | mathematics |
| 建筑平面 / 体块 | architecture |
| 生成艺术 / 色彩理论 | art |
| 音频 / 乐理 | music |
| 地图 / 空间分布 | geo-map |
| 星历 / 星图 / 天象 | astronomy |
| 图网络 / 关系 | network |
| 行情 / K线 / 量价 | finance |

3. **动效/交互附加层**：主场景确定后如需过程动画或交互 3D，一律经 `motion-toolkit\SKILL.md`（引用不复制）；通用降级链与交付纪律以其 §4/§5 为准。
4. **动笔前 cross-check**：读对应 `demos\<scene>.md` 对齐体例（五段/四节骨架、图预算、声明位）。

**physics-sim 候选并入结论（2026-10-05 收口归档）**：不设独立 physics-sim skill——物理仿真没有独有图语汇与核心工具，其可见产物只有「过程动画」（视频/帧动画，由 motion-toolkit 的 Remotion·mpl_anim 承载）与「交互式 3D 场景」（由 education 动效层 Three.js 承载），数值与收敛曲线归 mathematics；需要时按 education·动效层 + mathematics 组合承接，无独立工具链即不立独立场景。
