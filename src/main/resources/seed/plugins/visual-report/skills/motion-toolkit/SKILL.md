---
name: motion-toolkit
description: 共享动效模块（Remotion/three.js/mpl_anim 降级链）；需要动效时只引用不复制。
---

# motion-toolkit — 套件共享动效模块

> 上游公共规范：`../core/SKILL.md`（铁律 / 嵌入路径 / 暗色变量表 / 渲染器兼容 / 缺装降级总则）。本模块是套件内**唯一**动效环境说明与渲染封装来源：场景（education / engineering / …）需要视频或交互动画时只引用本文件，**禁止在各场景 SKILL 里重复环境说明**（防漂移）。踩坑细节见 `references/probe-notes.md`。

## 1. 模块定位

visual-report-suite 的共享动效层，三个栈集中一处：

| 栈 | 通路 | 产物 | 定位 |
|---|---|---|---|
| Remotion 视频栈 | React 组件 → Chrome 渲染 → MP4 | 视频（MP4 等） | 带排版/字幕/数据动画的成片，教育动效主力 |
| three.js 交互栈 | npm 本地化 three → 自包含 HTML | 单文件交互 HTML | 可拖拽 3D/交互演示，双击即开、离线可用 |
| 备选动画栈（降级） | matplotlib FuncAnimation → MP4/GIF | MP4 / GIF | Remotion 不可用时顶上；轻量数据动画 |

## 2. 环境说明（全部实测 2026-10-04；禁止凭记忆改写）

来源：chain-n-cdc177a5 环境实测节点（`C:\Users\Kai\.nebflow\projects\general\env_test_results_20261004.md`）+ 本模块当日冒烟复跑。

| 组件 | 实测版本 | 绝对路径 / 端点 | 实测结论 |
|---|---|---|---|
| node / npm | 24.14.0 / 11.9.0 | PATH 内 | `npx` 正常；README 基线有效 |
| npm registry | — | `https://registry.npmmirror.com` | registry.npmjs.org 直连 stall（3.5min 无进展）；npmmirror 实测 12s 装 252 包。**一律加 `--registry https://registry.npmmirror.com`**（各 setup.cmd 已固化） |
| Remotion + CLI | 4.0.532（react 19.0.0） | `scripts\remotion-scaffold\`（node_modules 已装） | 60 帧 1280×720 渲染全程 16s，exit 0，h264 30fps ✓ |
| 渲染浏览器 | Chrome 154.0.8037.93 | `C:\Program Files\Google\Chrome\Application\chrome.exe` | 必须 `--browser-executable` 指本机 Chrome；`browser ensure` 自动下载走 storage.googleapis.com 仅 ~17KB/s **不可用**。Edge `msedge.exe` 为后备（同标准路径） |
| three | 0.186.1 | `scripts\three-template\node_modules\three\` | npmmirror 3s 装完；**双文件构建**（module→core 相对导入），自包含内联需递归改写（封装已处理） |
| Python | 3.12.9 | `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts\python.exe` | 套件唯一 Python 环境（core §5/§7），只装进 venv |
| matplotlib | 3.11.2 | viz-venv 内 | 中文需 MPLCONFIGDIR 接线：`C:\Users\Kai\.nebflow\tools\mplconfig\`（mpl_anim.py 已固化；节点 shell 实测该环境变量为空） |
| ffmpeg | 7.1（imageio_ffmpeg 0.6.0 自带） | `C:\Users\Kai\.nebflow\tools\viz-venv\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe` | **PATH 无 ffmpeg**；引用一律用此 exe 绝对路径（封装已固化） |
| Pillow / numpy | 12.3.0 / 2.5.3 | viz-venv 内 | GIF 走 PillowWriter，不触碰 ffmpeg |
| manim（未封装） | 0.21.0 | viz-venv 内 | 上游实测 `-ql` 渲染 exit 0（854×480）。venv 里现成可用，本模块未做封装；要更高表现力可直接 `python -m manim`，用前自行跑一次冒烟 |

外部链接验证（`curl -sIL` 读末位状态行，README 纪律，2026-10-04）：

| 链接 | 状态码 |
|---|---|
| https://www.remotion.dev/docs/ | 200 |
| https://threejs.org/ | 200 |
| https://matplotlib.org/stable/api/animation_api.html | 200 |
| https://cdn.jsdelivr.net/npm/three@0.186.1/build/three.module.js | 200（CDN 备选通路） |
| https://unpkg.com/three@0.180.0/build/three.module.min.js | 200 |
| https://registry.npmmirror.com | 200 |

> 引用前超 30 天须复测（README 规则）。CDN 仅作 three 的备选引用；**默认走 npm 本地化内联**（持续吞吐实测不可靠，本地最稳）。

## 3. 渲染封装（scripts，一节一例；`<venv>` = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`）

### 3.1 Remotion → MP4（主栈）

工程在 `scripts\remotion-scaffold\`（node_modules 已装好即渲染；新机器先跑 `setup.cmd`）。加内容 = 在 `src\Root.tsx` 复制 `MotionSmoke` 改几何/动画（interpolate / spring / AbsoluteFill），再注册一个新 `Composition`。

```bash
# 渲染（封装自动带 --browser-executable 本机 Chrome + --log=error）
cd C:/Users/Kai/.nebflow/skills/visual-report-suite/motion-toolkit/scripts
node render-remotion.mjs remotion-scaffold MotionSmoke C:/Users/Kai/.nebflow/plots/motion-toolkit-smoke/out.mp4

# 带参数（复杂 props 写 json 文件传路径，别走命令行内联 JSON——Windows 引号转义深）
node render-remotion.mjs remotion-scaffold MotionSmoke out.mp4 --props=C:/abs/path/props.json
# 换浏览器：REMOTION_BROWSER_EXECUTABLE="C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
```

冒烟样张：`C:\Users\Kai\.nebflow\plots\motion-toolkit-smoke\remotion_smoke.mp4`（2.00s / 1280×720 / h264 30fps / 53,933 B，exit 0）。

### 3.2 three.js → 自包含交互 HTML（交互栈）

```bash
cd C:/Users/Kai/.nebflow/skills/visual-report-suite/motion-toolkit/scripts/three-template
# 新机器先: setup.cmd   （three 0.186.1 已装）
<venv>/python.exe build_three_html.py --scene scene_default.js --out C:/Users/Kai/.nebflow/plots/motion-toolkit-smoke/out.html --title 标题
# 网络可靠时的备选：加 --cdn（importmap 指 jsdelivr，不内联）
```

场景 = 复制 `scene_default.js` 改几何/交互（`import * as THREE from "three"` 不变，构建器负责内联）。产物单文件、双击即开、离线可用（默认场景：扭矩结 + 拖拽旋转，配色 #5e6ad2）。

冒烟样张：`C:\Users\Kai\.nebflow\plots\motion-toolkit-smoke\three_smoke.html`（5.9MB 自包含）+ headless Chrome 实拍 `three_smoke_headless.png`（WebGL 正常出图、无报错）。

### 3.3 matplotlib → MP4 / GIF（降级栈）

用户脚本只需定义 `build(fig, ax) -> (update, n_frames)`（范例 `scripts\mpl_examples\demo_sine.py`）：

```bash
cd C:/Users/Kai/.nebflow/skills/visual-report-suite/motion-toolkit/scripts
<venv>/python.exe mpl_anim.py --script mpl_examples/demo_sine.py --out C:/Users/Kai/.nebflow/plots/motion-toolkit-smoke/out.mp4 --fps 30
<venv>/python.exe mpl_anim.py --script mpl_examples/demo_sine.py --out C:/Users/Kai/.nebflow/plots/motion-toolkit-smoke/out.gif --fps 15
```

封装已固化：MPLCONFIGDIR 接线（中文可读）、ffmpeg 指 imageio-ffmpeg 自带 exe、yuv420p 偶数尺寸裁剪。

冒烟样张：`mpl_smoke.mp4`（1.33s / 880×494 / h264 yuv420p 30fps，抽帧目检中文正常）+ `mpl_smoke.gif`（880×495 / 40 帧，PIL 校验通过）。

## 4. 降级链（任何一步不许空转，core §5）

- **视频栈**：Remotion 渲染失败（浏览器缺失/网络劣化且无本机 Chrome）⇒ matplotlib FuncAnimation + imageio-ffmpeg 出 MP4 ⇒ 仍失败（无 x264/写盘受限）⇒ PillowWriter 出 GIF ⇒ GIF 也不行 ⇒ 静态多帧 PNG 分步呈现（场景既有渲染器）。
- **交互栈**：three.js 不可用（npm/构建失败）⇒ CDN importmap 版（`--cdn`，需联网）⇒ 仍不可用 ⇒ matplotlib 静态分步图（旋转视角逐帧截图式）或场景既有 3D 通路（matplotlib 3D 已装）。
- 判定规则：**冒烟失败当场改判降级并同步改本文件**，禁止写「应该可以」。

## 5. 各栈可交付性初判（2026-10-04 初判；**同日通路验证已复核**，结论见 §5.1）

| 栈 | 能交付 | 不能交付 | 初判 |
|---|---|---|---|
| Remotion | MP4 视频（数据动画、字幕动效、分步讲解片头）；数据驱动动画逐帧确定性（帧号即时间） | 实时交互（产物是视频）；WebGL 3D（`@remotion/three` 未装未测） | **可用**（冒烟通过）——同日通路验证已复核（§5.1） |
| three.js | 自包含交互 HTML（拖拽 3D、离线双击可开、前端白名单目录可服务） | 嵌入 Pop（core §3：Pop 只挂 SVG/PNG 静态图源）；视频文件 | **可用**（冒烟通过）——同日通路验证已复核（§5.1） |
| 备选栈 | MP4 / GIF 数据动画；与套件 matplotlib 出图同源同字体 | 交互；3D 复杂场景表现力弱 | **可用**（冒烟通过）——同日通路验证已复核（§5.1） |

交付形式注意：视频/交互 HTML 是**文件交付**（正文写 `~/...` 绝对路径，走 core §2 白名单，默认 `~/.nebflow/plots/`）； Pop 悬浮预览只挂静态图源（core §3）。GIF/PNG 可被正文 `![...]()` 直接嵌入；MP4/HTML 写路径交付。

### 5.1 通路验证结论（教育三形态 demo，2026-10-04 实测；场景侧判定见 education SKILL「展示通路」）

对照页 `../../demos/education-motion.md`，产件白名单副本 `~/.nebflow/plots/education-motion/`。方法口径：本机 Chrome 154 `--headless=new` + `--remote-debugging-port` CDP 驱动（`Input.dispatchMouseEvent` 真实输入；chrome-headless-shell 自动下载不可用，见 §2，本机无独立 headless-shell）。§5 前两栈当轮实测复核：

| 栈 | 实测方法与量化判定 | 结论 |
|---|---|---|
| Remotion | imageio-ffmpeg 7.1 读容器 + 全流解码：h264 High / 1280×720 / 30fps / 13.33s / 0 解码错误；内嵌 video 标签最小页 headless：readyState=4、play() 2.2s 内 currentTime 前进 2.21s、canvas 抽帧非黑（meanLuma 17.7，65% 亮像素）、seek 6s 正常、0 致命错误 | MP4 通路**可用** |
| three.js | 5.9 MB 自包含 HTML 全文件 0 外链（0 `<script src>`，仅内联 data: import）；headless 实拍首帧后 CDP 真实输入：拖拽旋转+滚轮缩放截图像素差 = 静置基线 **38 倍**（mean 10.28 vs 0.27，17.2% 像素变化），点选「下一步」①→② DOM 状态推进且字幕数值逐字同源（0.632 V·63.2%·0.368 mA）；WebGL 2.0 真实上下文（本机 headless=new 直接硬件加速 ANGLE/D3D11，非 SwiftShader）；0 致命错误（86× Chromium 纹理拷贝 warning + 1× THREE.Clock 弃用提示，均非致命、渲染正常） | 交互 HTML 通路**可用** |
| 备选动画栈 | 本轮未单测（无独立产件）；其 MP4 播放面与 Remotion 栈同容器同编码族（Remotion 行已验证同一播放面）；GIF（PillowWriter）未单测 | 维持初判，下次出件时顺带复核 |

证据：`~/.nebflow/plots/education-motion/_verify/pathway_*`（3D 四态截图 ×4、video 播放/seek 截图 ×2、ffmpeg 抽帧 ×1）＋驱动脚本与结果 JSON：`C:\Users\Kai\.nebflow\projects\general\edu_pathway_scratch\`（result_3d / result_video / result_pixdiff / result_static .json）。

仍需 Nebula Pop / 实机复核（本机 headless 覆盖不到）：① 前端白名单服务面直开交互件与 MP4 点播（文件级已验证，前端通路未测）；② 实机 `prefers-color-scheme` 暗色切换（headless 已拍亮/暗，上游 `_verify_3d_dark_step5.png`）；③ remotion.dev 链接（当日 23:16 与 23:59 两次复测均 000，本地渲染不依赖）；④ Pop 只挂静态图源约定执行（对照页交互件/视频件均为路径交付）。

## 6. 扩展纪律

- 新增渲染通路（如 manim 封装、@remotion/three）先按 core §6 自检模板实测，再补入本文件 §2/§3，禁止只写文档不落封装。
- 环境表述一律以实测为准；本文件数值改动必须带实测日期与来源（探针路径见 `references/probe-notes.md`）。
- 场景 SKILL 引用示例：「动效走套件 motion-toolkit（`../motion-toolkit/SKILL.md`），本场景不重复环境说明」。
