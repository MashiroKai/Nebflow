---
name: mathematics
description: 数学讲解（过程动画+误差定量+manim）；函数、收敛、几何构造任务使用。
---

# mathematics — 数学·函数与收敛可视化场景 skill

> 上游公共规范：`../core/SKILL.md`（铁律 / 嵌入路径 / 暗色 / 渲染器兼容 / 缺装降级总则 / 自检模板）。本文件只写 mathematics 场景增量，通用规则一律引用 core，不复制漂移。动画渲染一律经 `../motion-toolkit/SKILL.md` 封装，本场景不重复其环境说明。

**独立成 skill 的论证**：mathematics 承载套件内独有的三类图语汇——函数变换（参数扫描 / 坐标系与级数变换）、几何构造（真实坐标的几何对象与轨迹）、收敛过程（逐阶逼近与误差衰减）——其核心交付动作是「把证明直觉动画化」，这是 education（概念讲解骨架）、timing/eda（工程信号）均不覆盖的能力面。

## 触发条件

命中任一即用本场景骨架交付：

- **函数与变换**：函数图像、参数扫描（伸缩平移 / 级数取项 / 变换对比）、极坐标与参数曲线；
- **几何构造**：真实坐标下的几何对象、轨迹与包络（示意框图不在此列，归 tech-architecture）;
- **概率与统计**：分布（PDF/CDF）、抽样与大数定律 / CLT 演示；
- **收敛与逼近过程**：级数部分和、迭代法（牛顿 / 不动点 / 梯度下降）、误差衰减——需要过程动画或对数误差图。

不命中即换场景：概念讲解（非过程演示）用 education；信号/时序用 timing；电路用 eda；结论汇报用 office。

## 报告骨架模板（五段，顺序固定）

**直觉引入 → 形式化 → 图解 → 常见误区 → 练习**（五段结构与 education 样例对齐，硬约束按数学场景收紧）

| 段 | 必含内容 | 硬约束 |
|---|---|---|
| 直觉引入 | 1 个可计算的数值例子或一张真实渲染图切入 | 类比（若用）必须紧跟失效边界（沿 education 类比规范） |
| 形式化 | 定义、定理、公式、记号约定 | 公式只用 `$...$`（core §4.3）；数值逐字（core §1.2）；定理表述写明成立条件（宁窄勿错） |
| 图解 | ≤3 图预算内的函数/几何/收敛图 + 过程动画 | 全部工具真实渲染落盘（core §1.1）；动画选用口径见下；误差图必须对数轴并逐点标值 |
| 常见误区 | 误区 vs 实际 对照表 | 每条纠正给公式或可复算数值，不许只写「这是错的」 |
| 练习 | 2–3 题 + 答案 | 答案含推导，数值可由形式化段公式复算 |

### 动画选用口径（图解段内）

- **收敛 / 逼近 / 参数扫描的过程叙事** → matplotlib.animation，经 `../motion-toolkit` 封装出片（`scripts\mpl_anim.py`，MP4/GIF）；数学场景默认这条栈；
- **公式推演 / 逐项镜头**（对照讲解）→ manim 0.21.0 直调 `python -m manim`（不在 motion-toolkit 三栈内，其 SKILL §2 标注「未封装」）；**本机无 LaTeX：MathTex/Tex 不可用**，公式一律 `Text`/`MarkupText`（Pango 渲染）；
- **静态结论 / 误差图** → matplotlib 单帧（对数轴误差图是本场景标配）；
- 动画计入 ≤3 图预算（core §1.3）；MP4 是文件交付（正文写 `~/...` 路径，core §2），GIF/PNG 可直接嵌入。

## 工具链与自检

### 工具选择

- **主渲染器：viz-venv matplotlib**——函数图像、几何构造（真实坐标 patches）、概率分布、对数误差图全覆盖；中文经套件全局 matplotlibrc（YaHei，避免 U+2080 等生僻字形）；
- **过程动画：matplotlib.animation → motion-toolkit 封装**——用户脚本只需定义 `build(fig, ax) -> (update, n_frames)`，MPLCONFIGDIR / ffmpeg exe（imageio-ffmpeg 自带 v7.1）/ yuv420p 偶数尺寸均由封装固化（`../motion-toolkit/SKILL.md` §3.3）；
- **公式动画：manim**（可行时用于对照镜头）——渲染命令 `python -m manim render -ql --disable_caching -o <名>.mp4 <脚本>.py <场景类>`；用前跑一次冒烟；
- 几何关系/流程类图不归本场景：graphviz / mermaid 走对应场景 skill。

### 工具自检（实测 2026-10-04）

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| matplotlib | 主渲染器：函数/几何/概率/误差图 | `<venv>\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2 | https://matplotlib.org/ | HTTP 200（2026-10-04 本场景复测） | plotly（已装）或纯文字+公式推演 |
| numpy | 数值生成（采样/部分和/误差） | `<venv>\python.exe -c "import numpy; print(numpy.__version__)"` → 2.5.3 | https://pypi.org/project/numpy/ | HTTP 200（2026-10-04，README 链接验证表） | 纯 Python `math` 模块替代 |
| manim | 公式动画对照镜头 | `<venv>\python.exe -c "import manim; print(manim.__version__)"` → 0.21.0 | https://docs.manim.community/en/stable/ · https://pypi.org/project/manim/ | 200 · 200（2026-10-04 实测） | 未装 ⇒ matplotlib 动画独担，公式改图内 mathtext 标注 |
| imageio-ffmpeg | 动画出片的 ffmpeg 二进制（封装内引用） | `<venv>\python.exe -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())"` → `...\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe` | https://pypi.org/project/imageio-ffmpeg/ | HTTP 200（2026-10-04 实测） | 封装降级链：MP4 ⇒ GIF（PillowWriter）⇒ 静态多帧分步图 |
| LaTeX（manim 公式排版依赖） | MathTex/Tex 必需 | MSYS：`command -v latex pdflatex xelatex` → 均无；MiKTeX 常见路径不存在；`python -m manim render`（MathTex 场景）实测 `FileNotFoundError: [WinError 2]` | https://miktex.org/ | 未装，不适用 | **现状即降级**：manim 公式一律 `Text`/`MarkupText`（已实测出片）；装 LaTeX 后方可启用 MathTex |
| motion-toolkit | 动画渲染封装层（备选动画栈） | `ls ..\motion-toolkit\scripts\mpl_anim.py` → 在盘（2026-10-04） | —（套件内模块） | 见 `../motion-toolkit/SKILL.md` §2 外部链接验证表 | 封装缺失 ⇒ 按上行动画降级链，不复制其环境说明 |

全工具链接验证表见套件 README 与 `../motion-toolkit/SKILL.md`，直接引用不复制。`<venv>` = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`。装包只进 viz-venv（core §5）。

### 数值复算规范（本场景第一红线）

- 误差 / 收敛图必须同时给出**复算表**（图内标注与表格由同一格式化函数生成，逐字一致）与**复算命令**（读者可直接运行的一行脚本或 ≤6 行代码块）；
- 注明基准口径：参考值用的库函数与精度（如 numpy 双精度 `np.sin`）、评估点、中心点、阶数集合；
- 交错级数「下一项上界」只在项递减时成立——凡引用上界必须写适用条件（见示例 demo 误区 2）。

### 降级策略

- matplotlib 不可用 ⇒ plotly（已装）⇒ **纯文字 + 公式推演**；不得空转、不得手画 SVG（core §1.1/§5）；
- 动画栈不可用 ⇒ 沿 motion-toolkit §4 降级链（MP4 ⇒ GIF ⇒ 静态多帧 PNG 分步呈现，分步 ①②③ 对应正文说明）；
- manim 不可用 ⇒ matplotlib 动画独担，公式镜头改为图内 mathtext 公式 + 分步高亮；
- LaTeX 缺失（**现状**）⇒ manim 用 `Text`/`MarkupText`；Unicode 上标缺字形时改写为 `/` 与 `^` 记法（如 `x^3/3!`）。

## 质量红线

1. **数值逐字 + 可复算**（core §1.2 + 本场景复算规范）：误差图、动画状态行、复算表三处数值必须同源同格式；
2. **收敛图必须对数轴**：误差跨数量级是收敛主题的常态，线性轴一律视为不合格；
3. **动画必须真渲染出片**：MP4/GIF 落 `~/.nebflow/plots/`，帧数/fps/时长写进交付文本；禁止占位框、占位帧、「示意动画」文字代替；
4. **动画渲染一律经 `../motion-toolkit` 封装**（manim 除外，直调但需冒烟）；禁止在场景内复制动效环境说明（motion-toolkit §6）；
5. **manim 只做公式/推演镜头**，数值曲线主图归 matplotlib 栈——两栈各司其职，不重复造轮子；
6. 单文档 ≤3 图（含视频）、marked v12 兼容（core §1.3/§4）：`## ` 带空格、GFM 表分隔行列数==表头列数、只用 `$...$`、`breaks:true` 段间空行。

## 示例

- `../demos/mathematics.md` —— 泰勒级数逐阶逼近 sin/cos 小样（五段骨架完整）：双面板过程动画 MP4（motion-toolkit 封装出片，240 帧 @20fps）+ manim 公式对照段（MarkupText 路线，无 LaTeX 实测）+ 对数轴误差图（数值表逐字复算）；教学参数（中心点 a=0、阶数集合、评估点 x=π/2）全声明。
