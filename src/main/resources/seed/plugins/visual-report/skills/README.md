# visual-report-suite — 可视化报告套件

场景 skill **一期 7 + 二期 10**：一期 academic-research / tech-architecture / engineering / office / education / eda / timing；二期 biology / chemistry / architecture / mathematics / art / music / geo-map / astronomy / finance / network——外加共享 core 与共享动效模块 motion-toolkit。各场景 SKILL.md 均已由场景节点落盘；本 README 是套件入口：落位与迁移映射、全工具链接验证表、MCP 评估结论、motion-toolkit 说明、扩展位。**选场景先查 [`CATALOG.md`](CATALOG.md)**（总目录表 + 选 skill 决策树，二期收口新增）。

- 套件根：`C:\Users\Kai\.nebflow\skills\visual-report-suite\`
- 公共规范：`core/SKILL.md`（场景 skill 必读；通用规则的唯一来源）
- 落位判定：**upgrade**（VR-现状定位，chain-n-92b37acf）——既有插件与工具资产全部复用，套件只做扩展面，不重建环境。

## 目录结构

```
visual-report-suite/
  README.md            ← 本文件（迁移映射 / 工具验证表 / MCP 结论 / motion-toolkit 说明 / 扩展位）
  CATALOG.md           ← 场景总目录 + 选 skill 决策树（二期收口新增 2026-10-05）
  core/SKILL.md        ← 全场景公共规范（铁律 / 嵌入路径 / 暗色 / 渲染器兼容 / 自检模板）
  academic-research/   ← 一期场景 skill（7 个全部在盘）
  tech-architecture/
  engineering/
  office/
  education/           ← 含动效层（三形态交付，渲染一律经 motion-toolkit）
  eda/
  timing/
  biology/             ← 二期场景 skill（10 个全部在盘，2026-10-04/05 实测）
  chemistry/
  architecture/
  mathematics/
  art/
  music/
  geo-map/
  astronomy/
  finance/
  network/
  motion-toolkit/      ← 共享动效模块（Remotion 视频 / three.js 交互 / mpl_anim 降级；SKILL.md + scripts\）
  demos/               ← 场景示例落此（一期 7 + 二期 10 + education-motion 三形态对照）
```

## 落位与迁移映射（upgrade 模式）

既有 visual-report 插件 v1.2.0（`C:\Users\Kai\.nebflow\plugins\visual-report\`，双侧一致、被 seed 链预装注入）**原样保留不回写**；套件在其之上扩展：

| 既有资产 | 现状（实测 2026-10-04） | 套件落位 | 动作 |
|---|---|---|---|
| 插件 SKILL.md（审美规范 / ≤3 图预算 / md 规则 / 人读体例） | v1.2.0，随会话注入 | `core/SKILL.md` §1–§4 继承并加固 | 继承，插件本体不改 |
| `tools\viz-venv` | Python 3.12.9 + numpy/scipy/matplotlib/pandas/plotly/pymupdf 等 | 套件唯一 Python 环境 | 复用，禁重复安装 |
| `tools\mplconfig\matplotlibrc` | YaHei 已配（font.sans-serif 首位） | matplotlib 全局配置直接生效 | 复用 |
| `tools\graphviz` | dot 16.1.0（bin 下，不在 PATH） | 流程/架构图基线渲染器 | 复用，全路径调用 |
| SiPM 图集（158 图）与裁图库（207 png，projects/Sipm 一带） | 原位在库 | demos 素材来源 | 不搬移，按 `~/...` 路径引用 |

冲突裁决：图表生成层与插件表述不一致时以 `core/SKILL.md` 为准（演进基准）；人读交付体例仍按插件 skill（注入生效）。

## 全工具链接验证表

验证方式：`curl -sIL` 读末位状态行（本机禁 `curl -w`）；URL 含 `[]` 加 `-g`；query 中 `+` 转义为 `%2B`。实测日期 **2026-10-04**，引用前超 30 天须复测。网络基线（同日实测）：Google / DuckDuckGo / Wikipedia 不可达，Bing 可用；github.com 超时；npmjs.com 网页 403（registry API 正常）。`<venv>` = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`。

| 工具 | 用途 | 本机已有 | 本机检测命令（实测） | 官方下载/文档链接 | 状态码 | 镜像/备用端点（官方失效时） | 状态码 | Mac 差异 | 缺装降级策略 |
|---|---|---|---|---|---|---|---|---|---|
| pymupdf | PDF 解析/裁图/渲染出图 | ✅ viz-venv 1.28.2 | `<venv>\python.exe -m pip show pymupdf` → 1.28.2 | https://pypi.org/project/PyMuPDF/ · https://pymupdf.readthedocs.io/en/latest/ | 200 · 200 | — | — | 同 pip 安装，无平台差异 | 已有无需降级；缺则 pip 装回 viz-venv |
| graphviz (dot) | 流程/架构/拓扑图 | ✅ tools\graphviz 16.1.0（不在 PATH） | `C:\Users\Kai\.nebflow\tools\graphviz\bin\dot.exe -V` → dot - graphviz version 16.1.0 | https://graphviz.org/download/ | 200 | — | — | `brew install graphviz`（dot 进 PATH）；中文字体用 PingFang SC | 已有；mermaid 未装时由它顶流程图 |
| mermaid-cli | mermaid → SVG/PNG | ❌ 未装（node 24.14.0 / npm 11.9.0 在位） | 装后 `mmdc -V`；端点验活 `npm view @mermaid-js/mermaid-cli version` → 12.0.0 | https://github.com/mermaid-js/mermaid-cli（超时）· https://mermaid.js.org/ | ✗ · 200 | 安装端点 https://registry.npmjs.org/@mermaid-js%2Fmermaid-cli · 镜像 https://registry.npmmirror.com/@mermaid-js%2Fmermaid-cli · https://npmmirror.com/package/@mermaid-js/mermaid-cli | 200 · 200 · 200 | `brew install node`；mmdc 建议 npx 直调 | graphviz 已覆盖流程/架构；类图/状态图降级为文字层级 |
| schemdraw | 电路原理图 | ✅ viz-venv 0.23（2026-10-04 装入） | `<venv>\python.exe -m pip show schemdraw` → schemdraw 0.23 | https://schemdraw.readthedocs.io/en/latest/ · https://pypi.org/project/schemdraw/ | 200 · 200 | — | — | 同 pip；字体随 matplotlib（PingFang SC） | 已有；缺则文字网表/引脚表降级，不得手画 SVG |
| wavedrom (wavejson) | 数字时序图 | ✅ npx wavedrom-cli 3.2.0（主）· viz-venv wavedrom 2.0.3.post3（备），2026-10-04 装入 | `npx -y wavedrom-cli --version` → 3.2.0；`<venv>\python.exe -m pip show wavedrom` → 2.0.3.post3；`npm view wavedrom version` → 3.7.0（registry 验活） | https://wavedrom.com/ · https://wavedrom.com/tutorial.html · https://github.com/wavedrom/wavedrom（超时）· https://pypi.org/project/wavedrom/ | 200 · 200 · ✗ · 200 | 安装端点 https://registry.npmjs.org/wavedrom · 镜像 https://registry.npmmirror.com/wavedrom | 200 · 200 | brew node；`npx wavedrom-cli` 或 pip wavedrom | 已有；缺则 matplotlib 阶梯线画时序 / 文本时序表 |
| matplotlib | 通用 2D/科学可视化 | ✅ viz-venv 3.11.2 + YaHei 已配 | `<venv>\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2 | https://matplotlib.org/ · https://matplotlib.org/stable/users/installing/index.html | 200 · 200 | — | — | `brew install python`；rcParams 字体首位换 PingFang SC | plotly（已装）或文字表格 |
| numpy | 数值生成/拟合/统计（education 主用，eng/eda 辅助） | ✅ viz-venv 2.5.3 | `<venv>\python.exe -c "import numpy; print(numpy.__version__)"` → 2.5.3 | https://pypi.org/project/numpy/ | 200 | — | — | 同 pip | 纯 Python `math` 模块替代（采样点手工生成） |
| scipy | 信号处理/特殊函数（education·eng 按需） | ✅ viz-venv 1.18.1 | `<venv>\python.exe -c "import scipy; print(scipy.__version__)"` → 1.18.1 | https://pypi.org/project/scipy/ | 200 | — | — | 同 pip | matplotlib + numpy 已覆盖讲义级需求 |
| pandas | 表格数据读写/整理（eng 辅助） | ✅ viz-venv 3.0.5 | `<venv>\python.exe -c "import pandas; print(pandas.__version__)"` → 3.0.5 | https://pypi.org/project/pandas/ | 200 | — | — | 同 pip | 纯 csv/文本表格读写替代 |
| rdkit | 分子结构/描述符/构象/反应（biology·chemistry 主力） | ✅ viz-venv 2026.03.6（2026-10-04 装入） | `<venv>\python.exe -c "import rdkit; print(rdkit.__version__)"` → 2026.03.6 | https://www.rdkit.org/ · https://pypi.org/project/rdkit/ | 200 · 200 | — | — | 同 pip | 描述符/性质表格 + 文字；禁手画结构式 |
| py3Dmol / 3Dmol.js | 分子 3D 交互（自包含 HTML 内联，chemistry） | ✅ viz-venv 2.5.5；3Dmol-min.js 537,792 B 已缓存本地 | `<venv>\python.exe -c "import py3Dmol; print(py3Dmol.__version__)"` → 2.5.5 | https://3dmol.csb.pitt.edu/ · https://pypi.org/project/py3Dmol/ | 200 · 200 | CDN https://cdn.jsdelivr.net/npm/3dmol@2.5.5/build/3Dmol-min.js（10-04 GET 实拉成功、同日 HEAD 3 次超时→首选官网+本地缓存） | GET ✓ · 200（10-05 复验） | 同 pip | rdkit 静态构象 3D PNG |
| folium | 交互地图 HTML（geo-map 交互面） | ✅ viz-venv 0.20.0（2026-10-04 装入） | `<venv>\python.exe -c "import folium; print(folium.__version__)"` → 0.20.0 | https://python-visualization.github.io/folium/ · https://pypi.org/project/folium/ | 200 · 200 | 瓦片 tile.openstreetmap.org 当日超时 → folium 内建 tile.openstreetmap.de 镜像图层（GET 真瓦片 30,855 B 实测可用） | ✗ 超时 · 镜像 200 | 同 pip | cartopy 静态 PNG（离线主交付） |
| cartopy | 静态投影地图/choropleth（geo-map 主交付） | ✅ viz-venv 0.26.0；NE 110m/50m 缓存 `~\.local\share\cartopy` | `<venv>\python.exe -c "import cartopy; print(cartopy.__version__)"` → 0.26.0 | https://scitools.org.uk/cartopy/docs/latest/ · https://pypi.org/project/cartopy/ | 200 · 200 | readthedocs 根 200 但 /en/stable、/en/latest 子路径 404——引用一律用 scitools 域名 | 404 ×2（勿引） | 同 pip；数据走 naturalearth S3 端点 | folium 交互页 / matplotlib 手绘边界 |
| skyfield | 星历/星图/轨道（astronomy） | ✅ viz-venv 1.55；de421.bsp（16,788,480 B）与 hip_main.dat（53,316,318 B）已缓存 `tools\skyfield-cache\` | `<venv>\python.exe -c "import skyfield; print(skyfield.__version__)"` → 1.55 | https://rhodesmill.org/skyfield/ · https://pypi.org/project/skyfield/ | 200 · 200 | de421 直下 ssd.jpl.nasa.gov 当日 HEAD 超时（缓存命中无需下载，恢复后复测） | ✗ 超时（未阻塞） | 同 pip | 无手算降级（天文数值禁降级手算，SKILL 红线）——缓存缺失如实标注待复测 |
| mplfinance | K线/量价/均线（finance 主渲染） | ✅ viz-venv 0.12.10b0（2026-10-04 装入） | `<venv>\python.exe -c "import mplfinance; print(mplfinance.__version__)"` → 0.12.10b0 | https://github.com/matplotlib/mplfinance · https://pypi.org/project/mplfinance/ | 10-04 晚 200 · 200；10-05 复验 github 无响应 · pypi 200 | https://pypi.org/project/mplfinance/（github 时段性不可达时用） | 200 | 同 pip；API 为 `make_marketcolors` | matplotlib 手绘 OHLC 蜡烛图 |
| networkx | 图网络建模/度量（network 主库） | ✅ viz-venv 3.7（2026-10-04 装入） | `<venv>\python.exe -c "import networkx; print(networkx.__version__)"` → 3.7 | https://networkx.org/ · https://pypi.org/project/networkx/ | 200 · 200 | — | — | 同 pip；`greedy_modularity_communities` 无 seed 参数（贪心确定性） | 邻接表 + 度量表格文字交付 |
| pyvis | 交互网络 HTML（network 交互面） | ✅ viz-venv 0.3.2（`cdn_resources='in_line'` 单文件自包含） | `<venv>\python.exe -c "import pyvis; print(pyvis.__version__)"` → 0.3.2 | https://pyvis.readthedocs.io/en/latest/ · https://pypi.org/project/pyvis/ | 200 · 200 | — | — | 同 pip；枚举值是 `'in_line'` 非 `'inlined'` | networkx 静态布局图 |
| manim | 公式/过程动画（mathematics） | ✅ viz-venv 0.21.0（2026-10-04 装入；未进 motion-toolkit 封装） | `<venv>\python.exe -c "import manim; print(manim.__version__)"` → 0.21.0；`-ql` 渲染 exit 0 实测 | https://docs.manim.community/en/stable/ · https://pypi.org/project/manim/ | 200 · 200 | — | — | 同 pip | **MathTex/Tex 不可用**（本机无 LaTeX/MiKTeX，`FileNotFoundError` 实测）——公式动画走 Text/MarkupText；再退 mpl_anim |
| tone.js | 网页音频/可听化（music） | ✅ 15.1.22（jsdelivr GET 345,768 B，`node --check` 通过，内联本地） | jsdelivr GET 实拉 + `node --check Tone.min.js`（10-04 实测） | https://tonejs.github.io/ · https://tonejs.github.io/docs/ | 200 · 200 | https://cdn.jsdelivr.net/npm/tone@15.1.22/build/Tone.min.js · unpkg（Tone.js 200；Tone.min.js 404——包内无此文件） | 200 · 200 · 404 | 无（浏览器运行时库） | WebAudio 原生内联（music SKILL 内置同配方降级 + OfflineAudioContext 自检） |
| three.js | 可交互 3D 自包含 HTML（education·architecture 等） | ✅ 0.186.1（motion-toolkit\scripts\three-template\node_modules 本地化，构建器内联自包含） | `node -e "console.log(require('./node_modules/three/package.json').version)"`（three-template 下）→ 0.186.1 | https://threejs.org/ | 200 | CDN https://cdn.jsdelivr.net/npm/three@0.186.1/build/three.module.js · unpkg three@0.180.0 min（备选引用） | 200 · 200 | 无（npm 本地化内联为默认） | `--cdn` importmap 版（需联网）⇒ matplotlib 静态分步/3D |
| remotion | React→本机 Chrome→MP4 讲解视频（education 动效主力） | ✅ 4.0.532（motion-toolkit\scripts\remotion-scaffold，node_modules 252 包经 npmmirror 装入） | `node -e "console.log(require('./node_modules/remotion/package.json').version)"`（remotion-scaffold 下）→ 4.0.532；冒烟渲染 exit 0 实测 | https://www.remotion.dev/docs/ | 10-04 早 200；同日 23:16/23:59 复测 000×4；**10-05 收口复验 200（已恢复）** | 安装 https://registry.npmmirror.com（npmjs 直连 stall）；渲染浏览器本机 Chrome `--browser-executable`（chrome-headless-shell 自动下载经 storage.googleapis.com ~17KB/s 不可用；Edge 后备） | 200 · ✗ | 无（同参数指本机 Chrome） | mpl_anim MP4 ⇒ PillowWriter GIF ⇒ 静态多帧 PNG（motion-toolkit §4） |
| imageio-ffmpeg | MP4 编码后端（PATH 无 ffmpeg 的降级成立项） | ✅ viz-venv 0.6.0（自带 ffmpeg 7.1 exe，10-04 实测可执行） | `<venv>\python.exe -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_version())"` → 7.1-essentials_build-www.gyan.dev；exe：`<venv>\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe` | https://pypi.org/project/imageio-ffmpeg/ | 200 | — | — | `brew install ffmpeg`（PATH 直用） | PillowWriter 出 GIF ⇒ 静态多帧 PNG |

**计数：通过 19（官方网页/文档 14 + npm 官方 registry 端点 2 + npmmirror 镜像 3）/ 失效 3（github.com×2 超时、npmjs.com 网页 403 反爬）——失效项均已换镜像或备用端点补验通过，无悬空失效。numpy / scipy / pandas 三行为 education·engineering 场景补充行（同日实测）。**

**二期补充行计数（上表 13 行，rdkit → imageio-ffmpeg）：状态码 2026-10-04 由各场景上游回执逐条转抄；2026-10-05 收口节点对本批新写入的 26 条外部链接全部 `curl -sIL` 复验——通过 25 / 无响应 1（github.com/matplotlib/mplfinance，属 github.com 时段性不可达，pypi 备端 200 已验，无悬空失效）；remotion.dev 前日 000 已恢复 200。转抄中的已记录失效项（unpkg Tone.min.js 404=包内无此文件、cartopy readthedocs 子路径 404、ssd.jpl/OSM 瓦片超时）均有镜像或替代口径，不影响交付。**

### MSYS 注意

- 传 Windows 路径参数加 `MSYS2_ARG_CONV_EXCL='*'`；
- `curl` 禁 `-w`：用 `-sIL` 并读取末位 `HTTP/...` 状态行；
- URL 含 `[]` 加 `-g`；query 中 `+` 转义为 `%2B`。

## MCP 评估结论（2026-10-04）

| 能力 | 是否建常驻 server | 理由 |
|---|---|---|
| 批量 PDF 裁图 | 不需要 | pymupdf 是库不是服务：viz-venv 单进程跑完整批（既有 207 png 裁图库即此模式产物）；常驻只添端口/生命周期管理成本，无摊薄收益 |
| mermaid 渲染服务 | 不需要 | mmdc 单图秒级、node 24 在位，npx 直调即可；仅当出现「高频批量渲染且冷启动成为瓶颈」再评估 puppeteer 常驻池（触发器记录在案） |
| schemdraw 渲染服务 | 不需要 | 纯 Python 库，装进 viz-venv 即脚本直调；无服务形态收益 |
| wavedrom 渲染服务 | 不需要 | 库/CLI 单次转换模式；现调用量级摊不薄常驻成本 |

**一句话**：四项能力均不建常驻 MCP 服务——全部存在「库直调 / CLI 单次调用」路径，调用频次摊不薄常驻进程的运维成本，强行加属过度工程；唯一复核触发器是 mermaid 高频批量渲染。

## motion-toolkit 共享动效模块（二期新增，2026-10-04 实测 / 2026-10-05 收口）

- **定位**：套件唯一动效环境说明与渲染封装来源（`motion-toolkit/SKILL.md`）——Remotion 视频栈（React 组件 → 本机 Chrome → MP4）、three.js 交互栈（npm 本地化 → 自包含交互 HTML）、备选动画栈（matplotlib FuncAnimation → MP4/GIF）三栈集中一处；场景需要动效/交互动画时**只引用该文件，禁止复制环境说明**（防漂移）。
- **引用方式**：场景 SKILL 写「动效走套件 motion-toolkit（`../motion-toolkit/SKILL.md`）」；视频件走 `scripts\render-remotion.mjs`，交互件走 `scripts\three-template\build_three_html.py`，备选动画走 `scripts\mpl_anim.py`（用户脚本只写 `build(fig, ax) -> (update, n_frames)`）。已引用方：education（动效层三形态）、mathematics（mpl_anim 出片）、architecture（three.js 体块交互）。
- **降级链（一句话）**：Remotion 失败 ⇒ matplotlib FuncAnimation + imageio-ffmpeg 出 MP4 ⇒ PillowWriter 出 GIF ⇒ 静态多帧 PNG；three.js 失败 ⇒ `--cdn` 版 ⇒ matplotlib 静态分步图——任何一步不许空转。
- **环境三条硬口径（实测）**：npm 一律 `--registry https://registry.npmmirror.com`（npmjs 直连 stall，12s 装 252 包实测）；Remotion 渲染用 `--browser-executable` 指本机 Chrome `C:\Program Files\Google\Chrome\Application\chrome.exe`（chrome-headless-shell 自动下载不可用，Edge 后备）；ffmpeg 引用 viz-venv 内 imageio-ffmpeg 自带 exe 绝对路径（PATH 无 ffmpeg）。

## 扩展位（本轮不实现）

| 未来场景 | 候选工具 | 前置条件 | 备注 |
|---|---|---|---|
| UML（类图/时序/状态） | plantuml（需 Java，未装）；过渡用 mermaid / graphviz | Java 运行时 | mermaid 已可顶类图/状态图 |
| 3D | matplotlib 3D（已装）；OpenSCAD CLI（未装） | OpenSCAD 安装 | 科学 3D 曲面现已可用 |
| 地图/空间分布 | folium、cartopy —— **已装（二期实测 2026-10-04：folium 0.20.0 · cartopy 0.26.0，场景 `geo-map/` 已落地）** | 已满足（NE 海岸线/国界缓存在 `~\.local\share\cartopy`） | cartopy 静态主交付 + folium 交互 |
| 分子结构 | rdkit —— **已装（二期实测 2026-10-04：2026.03.6，场景 `biology/` `chemistry/` 已落地）**；Open Babel（未装） | rdkit 已满足；Open Babel 需独立二进制 | 反应验证 + 3Dmol.js 自包含交互已由二期场景承接 |
| 晶体结构 | pymatgen、ASE（未装） | pip 进 viz-venv | 材料场景触发再议 |

新增工具一律先过 `core/SKILL.md` §6 自检模板（实测检测命令 + 链接验证 + 降级策略）再补入上表。
