---
name: music
description: 乐理讲解（频谱/卷帘/语谱图+可听化）；音频分析与乐理可视化任务使用。
---

# music — 音乐·音频合成与频谱可视化场景 skill

> 上游公共规范：`../core/SKILL.md`（铁律 / 嵌入路径 / 暗色 / 渲染器兼容 / 缺装降级总则 / 自检模板）。本文件只写 music 场景增量，通用规则一律引用 core，不复制漂移。

> **独立成 skill 论证**：音乐场景的证据链是「频率 ↔ 音名 ↔ 和声结构」的三向逐字对应 + **可听化交付**（wav 与点按发声的交互页），其独特图语汇（FFT 频谱、钢琴卷帘、语谱图）与「听得见的验证闭环」为其余六场景所无，故独立成 skill。

## 触发条件

命中任一即用本场景骨架交付：

- **乐理讲解**：音程、律制（平均律/纯律）、泛音列、和弦结构、拍频等可定量乐理；
- **音频合成与交付**：要求产出真实可播放的音频文件（wav/pcm），或对一段音频做合成/复现；
- **音高内容鉴定**：「这段音频里有哪些音、峰值频率是多少」类频谱分析问题；
- **音乐结构可视化**：钢琴卷帘、和声进行、时序结构图 + 可听对照。

不命中即换场景：纯图表汇报用 office；概念讲解本体（如「什么是傅里叶变换」）用 education；带采样实验数据的研究报告用 academic-research。

## 报告骨架模板（五段，顺序固定）

**听感引入 → 乐理形式化 → 图解 → 可听化交付 → 校验与常见误区**

| 段 | 必含内容 | 硬约束 |
|---|---|---|
| 听感引入 | 1 段话说明听什么（音高内容/和声/节奏），并指向可听产件路径 | **显著声明**音频为合成还是实录 + 音律参数（标准音 A4=440 Hz、律制、采样率/位深）；类比 education 的类比失效边界不强制，但参数声明缺一即返工 |
| 乐理形式化 | 音名-频率表（$f(n) = 440 \times 2^{n/12}$，n = 相对 A4 半音数）、音程/和弦构成、节奏时值、合成配方（谐波/包络/步长） | 数值逐字（core §1.2）：表中频率必须可由公式复算到 4 位小数；配方写实现的确切算法，不写「类似钢琴音色」 |
| 图解 | ≤3 图，权重序：**频谱 > 钢琴卷帘 > 语谱图 > 波形**（core §1.3） | 频谱图峰值频率 ↔ 音名标注**逐字一致**；全部专业工具真实渲染落盘；命中类别 >3 时首屏加「图预算超限」声明 |
| 可听化交付 | wav（fs/位深/时长/落盘绝对路径）+ 自包含交互 HTML（点按发声说明） | HTML **零外部网络依赖**（CDN 库内联进文件，或 WebAudio 原生内联实现）；在线合成与 wav 同配方，电平差异要声明 |
| 校验与常见误区 | 实测峰频 vs 乐理值对照表（含音分偏差 $1200\log_2(f_{meas}/f_{th})$）+ 误区对照表 | 每条偏差如实带符号；每条纠正可验证（给公式或数值） |

骨架即验收单：缺任一段或段序颠倒，不算 music 交付。

## 工具链与自检

### 工具选择

- **合成与分析主链：viz-venv numpy + scipy**——加性谐波合成（`np.sin` 叠加 1/k 谐波 × ADSR 包络）→ `scipy.io.wavfile.write` 落 16-bit PCM wav；`scipy.signal.get_window('hann')` + `np.fft.rfft` 做峰值检测（抛物线插值亚 bin 精度）；`scipy.signal.spectrogram` 出语谱数据；
- **静态图：viz-venv matplotlib**——频谱（dB 轴 + 音名标注 + 理论频率虚线）、钢琴卷帘（`Rectangle` 补丁，坐标全部由音名/起始/门限数据驱动）、语谱图（`pcolormesh` + `rasterized=True`，否则 SVG 达数十 MB）；
- **可听可看交互页：tone.js（CDN 可达则内联）或 WebAudio 原生**——同一合成配方双实现：tone.js 走 `PolySynth`（`oscillator: {type:'custom', partials:[...]}` 线性包络对应配方）；降级路径 WebAudio 原生（`OscillatorNode`×k + Gain 包络），逐函数与 numpy 版一致；页面尾部内置 OfflineAudioContext 离线渲染 + JS FFT 自检；
- SVG 落盘必须注入 `@media (prefers-color-scheme: dark)` 暗色块（core §3）；matplotlib 默认 `svg.fonttype='path'` 把文字转轮廓导致暗色 text 规则空匹配，**必须设 `plt.rcParams['svg.fonttype'] = 'none'`**，且暗色覆写用 `#gid, #gid *` 后代选择器（gid 在父 `<g>`、颜色内联在子元素上）。

### 工具自检（实测 2026-10-04）

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| numpy | 波形合成/FFT | `<venv>\python.exe -c "import numpy; print(numpy.__version__)"` → 2.5.3 | https://pypi.org/project/numpy/ | HTTP 200（2026-10-04 实测） | 纯 Python `math`/`array` + `wave` 标准库合成与 DFT（慢但可行） |
| scipy | wavfile/spectrogram/窗函数 | `<venv>\python.exe -c "import scipy; print(scipy.__version__)"` → 1.18.1 | https://pypi.org/project/scipy/ | HTTP 200（2026-10-04 实测） | **scipy 缺则 numpy 直算**：`np.fft.rfft` + 手写 Hann（`0.5*(1-cos(2πi/M))`）；wav 用标准库 `wave` 写 16-bit |
| matplotlib | 频谱/卷帘/语谱静态图 | `<venv>\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2 | https://matplotlib.org/ | HTTP 200（2026-10-04 实测） | plotly（viz-venv 已装）；不得手画 SVG（core §1.1） |
| tone.js | 交互页音频引擎（内联） | `npm view tone version --registry https://registry.npmmirror.com` → 15.1.22；GET 构建文件 + `node --check` 语法验证 | https://tonejs.github.io/ · https://tonejs.github.io/docs/ | 均 HTTP 200（2026-10-04）；CDN：https://cdn.jsdelivr.net/npm/tone@15.1.22/build/Tone.min.js → 200（GET 345,768 B / 1.6 s）；unpkg 的 `build/Tone.min.js` → **404**（包内无此文件，须用 `build/Tone.js` → 200） | **tone.js 不可达则 WebAudio 内联**（同一配方、零依赖，且本就是内联 tone.js 方案的降级路径） |
| WebAudio | 交互页音频引擎（基线/降级） | 页内 `typeof (window.AudioContext\|\|window.webkitAudioContext)`；本机 Chrome headless 实测通过 | https://developer.mozilla.org/en-US/docs/Web/API/Web_Audio_API | HTTP 200（2026-10-04 实测） | 无 WebAudio 的环境不承载可听交付，明示降级为 wav 静态交付 |
| node | 交互页语法/冒烟验证 | `node --version` → v24.14.0（README 基线复测一致） | https://nodejs.org/ | 2026-10-04 在位实测（npm 11.9.0） | 仅浏览器无 node 时跳过 `node --check`，以 headless 冒烟为准 |

全工具链接验证表（六基础 + 各场景补充行）见套件 README，直接引用不复制。`<venv>` = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`。装包只进 viz-venv（core §5）。

### 交互页验证协议（「点按可发声」的实证）

1. 组装：交互页源为模板 + CDN 拉取的 `Tone.min.js` 内联（组装命令随示例 `../demos/music.md` 记录），产物零外部引用；
2. 语法：`node --check` 过一遍内联脚本可解析；
3. 冒烟：puppeteer-core（npmmirror 安装、不下载浏览器）驱动本机 Chrome headless（Remotion 实测同款 `--browser-executable` 路线）：监听 pageerror/console error 必须为零；真实 `page.click` 触发声部（AudioContext state 必须到 `running`）；`OfflineAudioContext` 离线渲染同一配方 + JS FFT 复算峰频，**全部 |音分偏差| ≤ 5 视为通过**；
4. 暗色目检：SVG 亮/暗双主题各截图一遍（core §3）；暗色必须用「暗卡底 + `<img>`」wrapper 截图（直接导航透明 SVG 落白页失真）。

## 质量红线

1. **合成声明强制**：任何音频产件必须在交付正文与页面内显著声明「合成/实录」+ 律制 + 标准音 + 采样率/位深/时长，缺一即返工；
2. **频率 ↔ 音名逐字**（core §1.2）：谱峰标注、表格、正文三处数值互为逐字；音分偏差如实带符号，不写「约」；理论值与实测值分列，不得混写；
3. **可听化必自包含**：交互 HTML 零外部网络请求（CDN 库内联或 WebAudio 内联），离线双击可发声；
4. 禁手画 SVG；图必须真实渲染落盘（core §1.1）；嵌入路径走 core §2 白名单（默认 `~/.nebflow/plots/`）；
5. 单文档 ≤3 图 + 权重序（频谱 > 钢琴卷帘 > 语谱图 > 波形）；marked v12 兼容（core §4）；
6. **图内音名用 ASCII 记号（`C#5`）**：YaHei 缺 U+266F（♯）字形（实测告警），正文可用 ♯ 但须注明即 `C#`。

## 示例

- `../demos/music.md` —— A 大三和弦分解琶音小样：真合成 wav（44.1 kHz/16-bit/2.40 s，A4=440 Hz 十二平均律）、FFT 频谱峰值与音名逐字核对（|偏差| ≤ 0.04 音分，880 Hz 叠加峰幅度比实测 1.50 = 理论 1.50）、钢琴卷帘 + 语谱图、Tone.js 15.1.22 内联 + WebAudio 降级的自包含交互页（headless Chrome 实测点按发声 + 离线自检通过）。