# visual-report — 插件使用指导（Plugin.md）

> 本文件是 visual-report 插件的**包根使用指导件**（Plugin.md）：**插件分配时注入给 agent 的提示词 = 本文件内容**（作者令 2026-10-21「plugin.md = 插件必要文件机制」批；SKILL.md 回归按需查阅件——面板胶囊点击打开，不再随分配整文注入）。选配判定仍看 plugin.json 的 description；逐 skill 作业指令见各 `skills/<skill>/SKILL.md`（按需查阅）。文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`）。

## 1. 速览

visual-report 是可视化报告包：节点按场景出图与人读报告。共 **17 个场景 skill**，外加注入面体例件 `skills/visual-report/SKILL.md`、全场景公共规范 `skills/core/SKILL.md` 与共享动效模块 `skills/motion-toolkit/`。

- **选场景**：先查 §5 总目录表 ＋ §6 决策树（拿到任务先做出口判定，不为图而图）。
- **公共规范**：`skills/core/SKILL.md`（场景 skill 必读；通用规则的唯一来源）。
- **样张与验收基准**：`skills/core/references/demos/`（场景示例落此）。
- **冲突裁决**：图表生成层各场景表述不一致时以 `skills/core/SKILL.md` 为准；人读交付体例以既有 visual-report skill 为准。

## 2. 安装与启用

- **安装**：本包属 Nebflow seed 官方分发，随实例 seed mirror 自动下发，无需手工安装。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（出图、图表、架构图、流程图、数据可视化、汇报、验收/调研/复盘报告等），派发时把各 SKILL.md 全文注入节点。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/visual-report/revoke` / `nebflow plugin revoke visual-report`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载）。

## 3. 目录结构

```
visual-report/           （包根）
  plugin.json
  Plugin.md              ← 本文件（包根使用指导件：速览 / 安装启用 / 工具验证 / 总目录 / 决策树）
  skills/
    visual-report/SKILL.md  ← 注入面体例件（人读交付体例唯一来源）
    core/SKILL.md        ← 全场景公共规范（铁律 / 嵌入路径 / 暗色 / 渲染器兼容 / 自检模板）
    core/references/demos/  ← 场景示例（17 场景样张 + education-motion 三形态对照）
    academic-research/   ← 场景 skill
    tech-architecture/
    engineering/
    office/
    education/           ← 含动效层（三形态交付，渲染一律经 motion-toolkit）
    eda/
    timing/
    biology/             ← 场景 skill
    chemistry/
    architecture/
    mathematics/
    art/
    music/
    geo-map/
    astronomy/
    finance/
    network/
    motion-toolkit/      ← 共享动效模块（Remotion 视频 / three.js 交互 / mpl_anim 降级；SKILL.md + scripts/）
```

## 4. 工具验证

- **依赖面**：本包各场景依赖运行环境中的可视化工具链（Python 科学可视化栈、Node.js 栈、graphviz 等系统工具）；逐场景工具清单见 §5 总目录表「核心工具」列，以各 `skills/<scene>/SKILL.md` 与 `skills/core/SKILL.md` 为准。
- **验证与安装**：工具是否就绪以最小命令自检（如 `python3 -c "import matplotlib"`、`dot -V`）；缺装时 Python 侧经 pip、Node 侧经 npm、系统工具按各官方下载页安装；检测与缺装降级的统一口径以 `skills/core/SKILL.md`（自检模板）为唯一来源。
- **边界**：本包不分发第三方工具本体，也不预设任何环境的安装状态；环境实测与链接验证记录属开发侧留档，不入本文件。

## 5. 各技能总目录（17 场景 + 共享动效模块）

| 场景 | skill 路径（自 skills/） | 核心工具 | 典型报告形态 | demo（自 skills/） |
|---|---|---|---|---|
| academic-research 学术调研 | academic-research/SKILL.md | pymupdf（两遍法裁图）· graphviz · matplotlib | 论文原图图集报告（四件套图注；原图为主体，自绘汇总 ≤3） | core/references/demos/academic-research.md |
| tech-architecture 技术架构 | tech-architecture/SKILL.md | graphviz（dot 分层集群）· mermaid-cli（npx 直调，可选） | 架构调研报告（总览图 → 组件分解 → 数据流 → 取舍） | core/references/demos/tech-architecture.md |
| engineering 工程测试 | engineering/SKILL.md | matplotlib · numpy / scipy / pandas · graphviz | 工程/实验/性能验证报告（五节骨架 + 四列指标判定表） | core/references/demos/engineering.md |
| office 办公 | office/SKILL.md | matplotlib（自律 ≤1 图）· 表格为主 | 周报/汇报（结论先行四段）· 会议纪要（决议/待办/owner） | core/references/demos/office.md |
| education 教育讲解 | education/SKILL.md | 静态底座：matplotlib · numpy / scipy · graphviz；**动效层**：Remotion · three.js · mpl_anim（一律经 motion-toolkit，引用不复制） | 概念讲义/课件（直觉→形式化→图解→误区→练习）；**动效三形态**：静态分步图 / 交互 3D HTML / Remotion 讲解视频 | core/references/demos/education.md · core/references/demos/education-motion.md（三形态对照） |
| eda 电路 | eda/SKILL.md | schemdraw（原理图唯一合规生成）· matplotlib · graphviz | 电路原理讲解报告（原理图 → 逐级原理 → 参数表） | core/references/demos/eda.md |
| timing 时序 | timing/SKILL.md | wavedrom-cli（npx 主途径）· wavedrom（Python 备选）· matplotlib（降级） | 接口/总线时序报告（信号表 → 波形图 → 逐段解读） | core/references/demos/timing.md |
| biology 生物分子 | biology/SKILL.md | rdkit（2D 骨架/描述符/构象）· matplotlib | 分子报告（结论先行 → 结构呈现 → 描述符定量 → 构象 3D → 复算） | core/references/demos/biology.md |
| chemistry 化学 | chemistry/SKILL.md | rdkit（结构/反应引擎验证）· 3Dmol.js（自包含交互 HTML） | 反应报告（反应式引擎验证 + 物种网格 + 可旋转 3D） | core/references/demos/chemistry.md |
| architecture 建筑空间 | architecture/SKILL.md | matplotlib（patches 米制平面/轴测）· three.js（经 motion-toolkit 体块交互） | 建筑方案报告（米制平面+尺寸链 → 立面/体块轴测 → 交互体块） | core/references/demos/architecture.md |
| mathematics 数学 | mathematics/SKILL.md | matplotlib + mpl_anim（motion-toolkit 封装）· manim（Text/MarkupText 路线；MathTex/Tex 需 LaTeX 环境） | 数学讲解（过程动画 + 误差定量 + manim 公式对照） | core/references/demos/mathematics.md |
| art 艺术色彩 | art/SKILL.md | p5.js（库内联自包含 HTML）· matplotlib（色轮/明度阶） | 生成艺术 + 色彩分析（算法+种子+参数可复现，双渲染像素 diff 实证） | core/references/demos/art.md |
| music 音乐 | music/SKILL.md | numpy / scipy（FFT/频谱）· matplotlib · tone.js / WebAudio（可听化） | 乐理讲解（频谱/钢琴卷帘/语谱图 + 可听化交互 HTML） | core/references/demos/music.md |
| geo-map 地理 | geo-map/SKILL.md | cartopy（静态投影，离线主交付）· folium（交互地图） | 地理报告（choropleth 静态 PNG 主交付 + 交互地图 HTML） | core/references/demos/geo-map.md |
| astronomy 天文 | astronomy/SKILL.md | skyfield（星历真算）· matplotlib（极坐标星图） | 天文报告（星空圆顶图 + 天象数值表；天文数值禁降级手算） | core/references/demos/astronomy.md |
| finance 金融行情 | finance/SKILL.md | mplfinance（K线/量价/MA）· pandas · matplotlib | 行情复盘报告（K线主图 + 辅助图；模拟数据「首屏+图内」双声明） | core/references/demos/finance.md |
| network 图网络 | network/SKILL.md | networkx（建图/度量）· pyvis（交互 HTML，`in_line` 内联）· matplotlib | 网络分析报告（静态布局 + 结构度量 + 交互探索） | core/references/demos/network.md |
| **motion-toolkit**（共享动效模块，非场景） | motion-toolkit/SKILL.md | Remotion（视频栈）· three.js（交互栈）· mpl_anim + imageio-ffmpeg（降级动画栈） | 非场景：MP4 视频 / 自包含交互 HTML / MP4·GIF 动画的**统一渲染封装与降级链**（环境说明唯一来源） | 样张 core/references/demos/education-motion.md |

> **motion-toolkit 被引用方**：education（动效层三形态）、mathematics（mpl_anim 出片）、architecture（three.js 体块交互）；其余场景出现动效/交互 3D 需求时同样只引用不复制。

## 6. 选 skill 决策树

拿到可视化任务按序执行：

1. **出口判定（先做）**：纯文字结论 / 纯表格 / 用户无图诉求 → **不进套件**，直接写文本交付（不为图而图）。
2. **查 §5 总目录表按内容形态路由**（拿不准时取报告主体形态；并存形态选承载结论的那类）：

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

3. **动效/交互附加层**：主场景确定后如需过程动画或交互 3D，一律经 `skills/motion-toolkit/SKILL.md`（引用不复制）；通用降级链与交付纪律以其 §4/§5 为准。
4. **动笔前 cross-check**：读对应 `skills/core/references/demos/<scene>.md` 对齐体例（五段/四节骨架、图预算、声明位）。

**physics-sim 并入说明**：不设独立 physics-sim skill——物理仿真没有独有图语汇与核心工具，其可见产物只有「过程动画」（视频/帧动画，由 motion-toolkit 的 Remotion·mpl_anim 承载）与「交互式 3D 场景」（由 education 动效层 Three.js 承载），数值与收敛曲线归 mathematics；需要时按 education·动效层 + mathematics 组合承接，无独立工具链即不立独立场景。

## 7. motion-toolkit 共享动效模块

- **定位**：唯一动效环境说明与渲染封装来源（`skills/motion-toolkit/SKILL.md`）——Remotion 视频栈（React 组件 → 本机 Chrome → MP4）、three.js 交互栈（npm 本地化 → 自包含交互 HTML）、备选动画栈（matplotlib FuncAnimation → MP4/GIF）三栈集中一处；场景需要动效/交互动画时**只引用该文件，禁止复制环境说明**（防漂移）。
- **引用方式**：场景 SKILL 写「动效走 motion-toolkit（`${SKILL_DIR}/../motion-toolkit/SKILL.md`）」；视频件走 `skills/motion-toolkit/scripts/render-remotion.mjs`，交互件走 `skills/motion-toolkit/scripts/three-template/build_three_html.py`，备选动画走 `skills/motion-toolkit/scripts/mpl_anim.py`（用户脚本只写 `build(fig, ax) -> (update, n_frames)`）。已引用方：education（动效层三形态）、mathematics（mpl_anim 出片）、architecture（three.js 体块交互）。
- **降级链（一句话）**：Remotion 失败 ⇒ matplotlib FuncAnimation + imageio-ffmpeg 出 MP4 ⇒ PillowWriter 出 GIF ⇒ 静态多帧 PNG；three.js 失败 ⇒ `--cdn` 版 ⇒ matplotlib 静态分步图——任何一步不许空转。
- **环境口径**：三栈的安装源、渲染浏览器与编码后端等环境口径，以 `skills/motion-toolkit/SKILL.md` 为唯一来源（本文件不重复环境细节，防漂移）。

## 8. 扩展位（未来候选）

| 未来场景 | 候选工具 | 前置条件 | 备注 |
|---|---|---|---|
| UML（类图/时序/状态） | plantuml；过渡用 mermaid / graphviz | Java 运行时 | mermaid 可作类图/状态图过渡 |
| 3D 网格/实体 | OpenSCAD CLI | OpenSCAD 安装 | 科学 3D 曲面由 matplotlib 3D 承载 |
| 分子文件格式互通 | Open Babel | 独立二进制安装 | 结构/反应/3D 交互由 rdkit + 3Dmol.js 承载 |
| 晶体结构 | pymatgen、ASE | Python 环境 pip 安装 | 材料类场景出现时评估 |

> 只登记候选与前置条件；候选落地后迁出本表、入 §5 总目录。

## 9. 维护约定

- **新增场景**：`skills/` 新建 `<scene>/SKILL.md` ＋ 样张落 `skills/core/references/demos/<scene>.md` → 补入 §5 总目录表与 §6 决策树。
- **新增工具**：先过 `skills/core/SKILL.md` 自检模板（检测 ＋ 降级策略），再同步对应场景 SKILL 与 §5 总目录表。
- **本文件定位**：Plugin.md 是按需 Read 面——装载器不注入；SKILL.md 是注入面权威，两者不一致时以 SKILL.md 为准并回改本文。
