---
name: education
description: 概念讲义与课件（静态+动效三形态）；讲解、教学、课件任务使用。
---

# education — 教育·概念讲解场景 skill

> 上游公共规范：`../core/SKILL.md`（铁律 / 嵌入路径 / 暗色 / 渲染器兼容 / 缺装降级总则 / 自检模板）。本文件只写 education 场景增量，通用规则一律引用 core，不复制漂移。

## 触发条件

命中任一即用本场景骨架交付：

- **概念讲解**：向非本领域读者解释一个概念、定理或机制（如 RC 低通、傅里叶变换、哈希冲突）；
- **讲义/课程材料**：成体系的章节讲义、课件底稿、培训材料；
- **学习笔记类报告**：读论文/读文档后的消化型笔记，目标是「讲给别人听」的完整闭环。

不命中即换场景：纯结论汇报用 office；带实验数据的研究报告用 academic-research；操作手册用 engineering。

## 报告骨架模板（五段，顺序固定）

**直觉引入 → 形式化 → 图解 → 常见误区 → 练习**

| 段 | 必含内容 | 硬约束 |
|---|---|---|
| 直觉引入 | 1 个类比或场景切入，建立「为什么关心 + 直觉图景」 | 类比后必须紧跟「类比失效边界」块（≥1 条，见 §工具链与自检的类比规范） |
| 形式化 | 定义、核心公式、关键数值表 | 公式只用 `$...$`（core §4.3）；数值逐字（core §1.2） |
| 图解 | 直觉图 + 定量图，单文档 ≤3 图（core §1.3） | 全部专业工具真实渲染落盘；分步图编号 ①②③ 清晰且正文给出与编号一一对应的分步说明 |
| 常见误区 | 误区 vs 实际 对照表 | 每条纠正必须可验证（给公式或数值），不许只写「这是错的」 |
| 练习 | 2–3 题 + 答案 | 答案含推导过程，数值必须可由形式化段公式复算 |

骨架即验收单：缺任一段或段序颠倒，不算 education 交付。

## 工具链与自检

### 工具选择

- **主渲染器：viz-venv matplotlib**——概念图解与分步示意图全部覆盖：函数图像（指数响应、幅频曲线）、分步示意（`annotate` 标注箭头 + ①②③ 编号圆标）、数值对比图；
- 概念之间的关系图（流程/分类/依赖）可用 graphviz dot（不在 PATH，全路径调用，core §7）;
- 电路原理图/数字时序图：由套件 eda / timing 场景承接（schemdraw 0.23 / wavedrom 已装，README 实测 2026-10-04）；缺装时按 core §5 降级，不得手画。

### 工具自检（实测 2026-10-04）

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| matplotlib | 主渲染器：函数图像/分步示意/幅频曲线 | `<venv>\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2 | https://matplotlib.org/ | HTTP 200（2026-10-04，README 链接验证表） | plotly（viz-venv 已装）或纯文字+公式推演 |
| numpy | 数值生成（指数曲线/频率轴/采样） | `<venv>\python.exe -c "import numpy; print(numpy.__version__)"` → 2.5.3 | https://pypi.org/project/numpy/ | HTTP 200（2026-10-04 实测） | 纯 Python `math` 模块替代（采样点手工生成） |
| scipy | 特殊函数/信号处理（按需） | `<venv>\python.exe -c "import scipy; print(scipy.__version__)"` → 1.18.1 | https://pypi.org/project/scipy/ | HTTP 200（2026-10-04 实测） | matplotlib + numpy 已覆盖讲义级需求 |
| graphviz | 概念关系/流程图（备用） | `C:\Users\Kai\.nebflow\tools\graphviz\bin\dot.exe -V` → dot - graphviz version 16.1.0 | https://graphviz.org/download/ | HTTP 200（2026-10-04，README 链接验证表） | mermaid-cli（npx 直调）或文字层级列表 |
| Remotion | 分步讲解视频（React→Chrome→MP4，教育动效主力） | `cd <套件>\motion-toolkit\scripts\remotion-scaffold && node -e "console.log(require('./node_modules/remotion/package.json').version)"` → 4.0.532 | https://www.remotion.dev/docs/ | HTTP 200（2026-10-04 motion-toolkit 实测）；同日 23:16 本场景复测 HEAD/GET 均 000×4（域名暂不可达，渲染走本机 Chrome 不受影响）→ 待通路验证复核 | matplotlib FuncAnimation + imageio-ffmpeg 出 MP4（motion-toolkit §4 降级链） |
| three.js | 可交互 3D 引导场景（自包含 HTML，双击可开、离线可用） | `cd <套件>\motion-toolkit\scripts\three-template && node -e "console.log(require('./node_modules/three/package.json').version)"` → 0.186.1 | https://threejs.org/ | HTTP 200（2026-10-04 本场景 `curl -sIL` 实测） | `--cdn` 版（需联网）⇒ matplotlib 静态分步图 |
| imageio-ffmpeg | MP4 编码后端（本机 PATH 无 ffmpeg，引用自带 exe 绝对路径） | `<venv>\python.exe -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_version())"` → 7.1-essentials_build-www.gyan.dev | https://pypi.org/project/imageio-ffmpeg/ | HTTP 200（2026-10-04 本场景 `curl -sIL` 实测） | PillowWriter 出 GIF ⇒ 静态多帧 PNG |

全工具链接验证表（六基础 + numpy/scipy 场景补充）见套件 README，直接引用不复制。`<venv>` = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`。装包只进 viz-venv（core §5）。

### 类比规范（本场景第一红线）

- 每个类比必须紧跟「**类比失效边界**」块，≥1 条，写清该类比在哪类问题上给出错误结论；
- 类比只承载直觉，不承载定量结论——一切数值与公式以形式化段为准；
- 不叠加多个类比互相补洞（第二个类比同样要标失效边界，通常不如回到公式）。

### 降级策略

- matplotlib 不可用 ⇒ plotly（已装）⇒ **纯文字 + 公式推演**：图解段改写为「读图指南」文字（坐标轴含义、曲线走向、关键点数值），配形式化段公式分步推导；不得空转、不得手画 SVG（core §1.1/§5）；
- graphviz 不可用 ⇒ 文字层级列表；
- 专用图（电路/时序）缺工具 ⇒ 文字网表/引脚表、文字时序表，缺什么降什么，不空转。

## 动效层（引导式动效，增量于静态底座之上）

### 理念

- **视觉优先**：人类擅长理解视觉内容而非文字——动效把「过程」变成可感知的时间序列，比静态图多一维（时间），比文字少一层翻译。视觉是增强不是替代：动效层永远架在既有静态分步图底座之上，**静态能力原样保留，不因引入动效而改写既有结论**。

### 引导式动效规范（三要件）

- **分步推进**：连续过程切成有限步（①②③…），每步一个焦点、一步一个可复算的数值；交互件由观看者控制步进，视频件按节拍推进；步序编号与正文/字幕一一对应（继承质量红线第 3 条）；
- **重点高亮**：每步只高亮一个（组）焦点元素，其余减淡；高亮对象必须在该步字幕中被点名；
- **旁白字幕**：每步 1–2 句文案，讲「现在发生什么」；字幕内数值必须与公式复算一致（如 τ=RC），并与静态图同源。

### 选用口径（Remotion→MP4 vs Three.js→交互 3D）

- **过程叙事**（机制随时间演化：充放电、收敛、信号流动、逐相推进）→ **Remotion → MP4** 视频；
- **空间结构 / 自主探索**（可旋转端详的 3D 结构、观看者要自己操作的关系）→ **Three.js → 可交互 3D HTML**；
- 拿不准就交付三形态对照（静态 / 交互 / 视频），样板见 `../demos/education-motion.md`。

### 渲染通路（只引用，不复制环境说明）

- 一律经套件共享动效模块 **`../motion-toolkit/SKILL.md`**——环境事实、封装命令、降级链、可交付性的唯一来源，本场景禁止重复环境说明（防漂移）；
- 视频件走 `motion-toolkit/scripts/render-remotion.mjs`（其 §3.1）；交互件走 `three-template/build_three_html.py`（其 §3.2）；备选动画走 `mpl_anim.py`（其 §3.3）。

### 动效降级链（静态底座兜底，不空转）

- 动效栈不可用 ⇒ **备选动画**（matplotlib FuncAnimation + imageio-ffmpeg 出 MP4 ⇒ PillowWriter 出 GIF）⇒ **既有静态分步图**（本场景原有能力，原样保留）；
- 三级判定细则以 motion-toolkit §4 为准；任何一步不许空转、不得手画 SVG（core §1.1）。

### 动效交付纪律

- 源工程留场景 `../demos/<demo>-src/`；**交互件与视频件必须复制一份进 `~/.nebflow/plots/**` 白名单**（前端可服务，供通路验证与展示）；MP4/HTML 走 `~/...` 绝对路径文件交付（core §2），GIF/PNG 可 `![...]()` 直嵌；**Pop 只挂静态图源**（core §3，交互 HTML 不进 Pop）；
- 动效内数值与静态图同源逐字（τ=RC 可复算）；演示用教学示例值时，必须在件内与对照页**显著声明**。

## 展示通路（2026-10-04 本机 headless 实测）

与上方「动效层」互补：那边定选用口径（过程叙事→MP4 / 空间探索→交互 3D），本节只回答「每种形态能否真实送达用户、不能时拿什么替代」。方法与环境口径、量化证据索引一律见 `../motion-toolkit/SKILL.md` §5.1（本机 Chrome 154 `--headless=new` + CDP 真实输入驱动；chrome-headless-shell 自动下载不可用），本节不重复。

| 形态 | 通路结论（2026-10-04 实测） | 判定依据（要点） |
|---|---|---|
| ① 静态分步图 SVG/PNG | **可交付**（正文直嵌；Pop 可挂） | SVG XML 合法、无外链、暗色 media 块在位；PNG PIL 校验通过；对照页嵌入全部 `~/...` 白名单绝对路径且文件在盘（core §2） |
| ② 交互 3D 自包含 HTML | **可交付**（`~/...` 文件交付；不进 Pop，core §3） | 5.9 MB 全文件 0 外链、离线可开；拖拽旋转+滚轮缩放后截图像素差 = 静置基线 38 倍、点选步进 ①→② 字幕数值逐字同源；WebGL 2.0 真实上下文（本机走硬件加速）；0 致命错误 |
| ③ Remotion MP4 | **可交付**（`~/...` 文件交付） | h264 1280×720 30fps 13.33s 全流解码 0 错；内嵌 video 标签 headless 播放 currentTime 前进、抽帧非黑、seek 正常、0 致命错误 |

- **替代口径**（细则以「动效降级链」与 motion-toolkit §4 为准，不重复）：交互件不可用 ⇒ 静态分步图（旋转视角逐帧式）；视频件不可用 ⇒ matplotlib MP4 ⇒ GIF ⇒ 帧序列 PNG；一切动效的兜底 = 既有静态底座（原样保留）。
- **主推交付形态**：过程叙事 ⇒ **MP4**；空间结构/自主探索 ⇒ **交互 HTML**；底座与 Pop 预览 ⇒ **静态分步图**。三形态 demo（`../demos/education-motion.md`）逐形态通路 2026-10-04 全部实测可用、未触发降级；实机复核清单见 motion-toolkit §5.1。

## 质量红线

1. **概念表述不得有科学性错误**：不确定的表述（近似条件、适用范围）必须写明边界，宁窄勿错；
2. **每个类比必须标注失效边界**（见类比规范）；
3. **分步图步骤编号清晰**：①②③ 顺序与正文说明一一对应，编号不得跳步；
4. **禁止手画 SVG；图必须真实渲染**：一切图由专业工具生成落盘（core §1.1），嵌入路径走 core §2 白名单（默认 `~/.nebflow/plots/`）；
5. **数值逐字**（core §1.2）：讲义例题数值必须可复算，答案与公式推导一致；
6. 单文档 ≤3 图、marked v12 兼容（core §1.3/§4）：`## ` 带空格、GFM 表分隔行列数==表头列数、只用 `$...$`、`breaks:true` 段间空行。
7. **动效件纪律**：渲染必经 `../motion-toolkit` 封装（环境说明只引用不复制）；动效数值与静态图同源逐字、教学示例值显著声明；交互件与视频件双份落盘（场景源工程 + plots 白名单副本）；Pop 只挂静态图源（core §3）。

## 示例

- `../demos/education.md` —— RC 低通滤波概念讲义小样：五段骨架完整、2 图（时域充放电分步图 + 频域幅频曲线，viz-venv matplotlib 真实生成）、类比失效边界标注、误区对照表、练习带推导答案。
- `../demos/education-motion.md` —— 同一 RC 充电主题的**三形态对照** demo（matplotlib 静态分步图 / three.js 可交互引导 / Remotion 分步讲解视频），动效层（§动效层）配套样板；源工程在 `../demos/education-motion-src/`。
