# RC 电路充电 · 同一主题三形态对照（静态分步图 / 可交互 3D / 分步讲解视频）

> education 场景「动效层」配套 demo：同一主题（RC 电路充电）分别用既有静态能力与两条动效通路出件对照。
> 规范上游：`../education/SKILL.md`（§动效层）→ `../core/SKILL.md`（铁律/嵌入/暗色）；渲染一律经 `../motion-toolkit/SKILL.md` 封装（环境事实唯一来源，本页不重复）。源工程：`../education-motion-src/`（复现命令见其 README）。

## 演示数据声明（教学示例值，显著声明）

**R = 1 kΩ，C = 1 µF，U = 1 V，均为教学示例值，非实测数据。** τ = RC = 1 kΩ × 1 µF = 1 ms。
三形态全部数值由充电公式实时复算，逐字一致：$u_C(t) = U(1 - e^{-t/\tau})$，$i = \frac{U - u_C}{R}$。

## 共用分步口径（三形态同源，①–⑥）

| 步 | t | $u_C$ = $U(1-e^{-t/\tau})$ | 达标百分比 | $i$ = $(U-u_C)/R$ |
|---|---|---|---|---|
| ① | 0 ms | 0.000 V | 0.0% | 1.000 mA（最大） |
| ② | 1 ms（1τ） | 0.632 V | 63.2% | 0.368 mA |
| ③ | 2 ms（2τ） | 0.865 V | 86.5% | 0.135 mA |
| ④ | 3 ms（3τ） | 0.950 V | 95.0% | 0.050 mA |
| ⑤ | 4 ms（4τ） | 0.982 V | 98.2% | 0.018 mA |
| ⑥ | 5 ms（5τ） | 0.993 V | 99.3% | 0.007 mA |

三形态共用电路锚点（schemdraw 0.23 真实渲染，亮色内嵌 + `@media` 暗色块；视频内嵌其暗色 PNG 版）：

![RC 充电电路原理图](~/.nebflow/plots/education-motion/20261004_edu_rc_charge_schematic.svg)

## 形态①：静态分步图（既有 matplotlib 能力）

渲染栈：viz-venv **matplotlib 3.11.2**（YaHei 中文、SVG 注入暗色 `@media` 块）。一张六面板图，t = 0/τ/2τ/3τ/4τ/5τ 逐步充电，完整响应为浅灰底稿、已充段强调色、当前步大圆标、历史步空心点；面板标题即 ①–⑥ 编号：

![RC 充电静态分步图](~/.nebflow/plots/education-motion/20261004_edu_rc_charge_steps_static.svg)

分步说明（与图中编号一一对应）：

1. ① t = 0：开关闭合，输入从 0 V 跳到 1 V；电容还空着（$u_C$ = 0.000 V），电流最大 $i = U/R$ = 1.000 mA，充电最快；
2. ② t = 1τ = 1 ms：走完剩余缺口的 63.2%，$u_C$ = 0.632 V，$i$ = 0.368 mA；
3. ③ t = 2τ = 2 ms：$u_C$ = 0.865 V（86.5%），$i$ = 0.135 mA，增速放缓——缺口按指数收缩；
4. ④ t = 3τ = 3 ms：$u_C$ = 0.950 V（95.0%），工程上自此视为基本充满；
5. ⑤ t = 4τ = 4 ms：$u_C$ = 0.982 V（98.2%），$i$ = 0.018 mA；
6. ⑥ t = 5τ = 5 ms：$u_C$ = 0.993 V（99.3%），剩余缺口不足 1%，视为充满。

## 形态②：Three.js 可交互引导场景

渲染栈：**three.js 0.186.1**，经 `motion-toolkit` 构建器内联为**自包含 HTML**（5.9 MB，双击即开、离线可用）。操作：拖拽旋转、滚轮缩放、点击画面或「下一步 ▶」分步推进（← → 键可用），右上 ①–⑥ 圆点可直达，URL `?step=N` 可跳第 N 步。每步：焦点元件呼吸高亮（其余减淡）、底部旁白字幕、电容液位与电流珠速度按 $i$ 实时联动、电容上方标签实时显示百分比与电压。交互件为**文件交付**（core §2 白名单路径；Pop 只挂静态图源，core §3）：

- `~/.nebflow/plots/education-motion/20261004_edu_rc_charge_3d.html`

旁白字幕文案（形态②③逐字共用，数值由公式实时复算）：

| 步 | 字幕标题 | 旁白文案 |
|---|---|---|
| ① | t = 0 · 开关闭合 | 开关闭合，输入从 0 V 跳到 1 V。电容还空着（u_C = 0.000 V），此刻电流最大 i = U/R = 1.000 mA——充电最快的一刻。 |
| ② | t = 1τ = 1 ms · 充电 1τ | 电容充到 u_C = 0.632 V（63.2%），电流降到 i = 0.368 mA。每过一个 τ，就走完「剩余缺口」的 63.2%。 |
| ③ | t = 2τ = 2 ms · 充电 2τ | u_C = 0.865 V（86.5%），i = 0.135 mA。缺口按指数收缩，充电越来越慢。 |
| ④ | t = 3τ = 3 ms · 充电 3τ | u_C = 0.950 V（95.0%），电流只剩 i = 0.050 mA。限流的 R 决定了每一步的快慢；工程上从 3τ 起视为基本充满。 |
| ⑤ | t = 4τ = 4 ms · 充电 4τ | u_C = 0.982 V（98.2%），i = 0.018 mA。 |
| ⑥ | t = 5τ = 5 ms · 充满 | u_C = 0.993 V（99.3%）≈ u_in = 1 V，剩余缺口不足 1%，充电结束。 |

## 形态③：Remotion 分步讲解视频 → MP4

渲染栈：**Remotion 4.0.532**（react 19.0.0）+ 本机 Chrome 154.0.8037.93，经 `render-remotion.mjs` 封装渲染。产物 `20261004_edu_rc_charge_steps.mp4`：1280×720 / h264 / 30fps / 400 帧 / **13.33 s** / 482,887 B。内容：片头声明示例值 → ①–⑥ 分步推进（曲线逐帧由 $u_C(t)$ 实时采样绘制、每步步末弹出值牌）→ 复盘段（1τ 63.2% → 5τ 99.3% 里程碑）；底部旁白字幕与形态②逐字同源，右上内嵌 schemdraw 暗色原理图。视频件同为**文件交付**：

- `~/.nebflow/plots/education-motion/20261004_edu_rc_charge_steps.mp4`

**可用性与替代口径说明**：本机 Remotion 通路实测可用（motion-toolkit 冒烟 + 本片完整渲染，exit 0），**未触发降级链**；若不可用则按 motion-toolkit §4 降级：matplotlib FuncAnimation + imageio-ffmpeg 出 MP4 ⇒ PillowWriter 出 GIF ⇒ 静态多帧 PNG，并在本页改写替代口径与原因。

## 三形态对照小结（选用口径，SKILL §动效层）

| 形态 | 产物 | 渲染栈 | 选用口径 |
|---|---|---|---|
| ① 静态分步图 | SVG/PNG（正文直嵌） | viz-venv matplotlib 3.11.2 | 底座：一切报告的默认起法，动效不可用时的兜底 |
| ② 交互引导 | 自包含 HTML（文件交付） | three.js 0.186.1 | 空间结构 / 自主探索：要旋转端详、自己控制节奏 |
| ③ 讲解视频 | MP4（文件交付） | Remotion 4.0.532 + 本机 Chrome | 过程叙事：机制随时间演化，按节拍带旁白推进 |

## 产件绝对路径全表（plots 白名单副本 + 套件源双份）

| 产物 | 形态/用途 | 套件源（源工程/注册位） | plots 白名单副本 |
|---|---|---|---|
| 20261004_edu_rc_charge_schematic.svg | 共用电路锚点（本页嵌入） | `C:\Users\Kai\.nebflow\skills\visual-report-suite\demos\education-motion-src\rc_schematic.py` | `C:\Users\Kai\.nebflow\plots\education-motion\20261004_edu_rc_charge_schematic.svg` |
| 20261004_edu_rc_charge_steps_static.svg / .png | 形态① | `C:\Users\Kai\.nebflow\skills\visual-report-suite\demos\education-motion-src\rc_static_steps.py` | `C:\Users\Kai\.nebflow\plots\education-motion\20261004_edu_rc_charge_steps_static.svg`（PNG 同名 .png） |
| 20261004_edu_rc_charge_3d.html | 形态② | `C:\Users\Kai\.nebflow\skills\visual-report-suite\demos\education-motion-src\rc_charge_scene.js`（构建器：`motion-toolkit\scripts\three-template\build_three_html.py`） | `C:\Users\Kai\.nebflow\plots\education-motion\20261004_edu_rc_charge_3d.html` |
| 20261004_edu_rc_charge_steps.mp4 | 形态③ | `C:\Users\Kai\.nebflow\skills\visual-report-suite\demos\education-motion-src\RcChargeSteps.tsx`（渲染注册位：`motion-toolkit\scripts\remotion-scaffold\src\RcChargeSteps.tsx` + `Root.tsx`） | `C:\Users\Kai\.nebflow\plots\education-motion\20261004_edu_rc_charge_steps.mp4` |
| 20261004_edu_rc_charge_schematic_dark.png | 形态③内嵌素材 | `motion-toolkit\scripts\remotion-scaffold\public\rc_schematic_dark.png` | `C:\Users\Kai\.nebflow\plots\education-motion\20261004_edu_rc_charge_schematic_dark.png` |
| _verify\*.png | 通路验证证据（headless 亮/暗实拍、MP4 抽帧） | — | `C:\Users\Kai\.nebflow\plots\education-motion\_verify\` |

## 待通路验证点清单（交验证节点/人工复核）

1. 前端白名单能否直接服务交互件 `~/.nebflow/plots/education-motion/20261004_edu_rc_charge_3d.html`（本节点仅 headless Chrome 实拍验证渲染与交互逻辑，未走前端通路）；
2. 视频件 `~/.nebflow/plots/education-motion/20261004_edu_rc_charge_steps.mp4` 的前端点播通路；
3. 交互件暗色跟随（`prefers-color-scheme` 切换）在实机浏览器的表现（headless 已拍亮/暗两态，见 `_verify`）；
4. remotion.dev 官方链接本节点 23:16 复测 HEAD/GET 均 000（同日早间 motion-toolkit 实测 200）——纯链接可达性波动，本地渲染通路不依赖该域名，待复核；
5. Pop 只挂静态图源约定复核：本页交互件/视频件仅路径交付，未进 Pop。

---

图预算声明：本页嵌图 2 张（共用原理图 + 形态①分步图），HTML/MP4 为文件交付不占图预算；本页未改动 `demos/education.md` 及其他场景目录。
