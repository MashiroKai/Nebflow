# education-motion-src · 三形态对照 demo 源工程

主题：RC 电路充电（教学示例值 **R = 1 kΩ，C = 1 µF，U = 1 V ⇒ τ = RC = 1 ms**，件内显著声明）。
对照页：`../education-motion.md`。渲染一律经套件共享动效模块 `../../../../motion-toolkit/`（环境说明唯一来源）。

## 文件 → 产物映射与复现命令

| 源文件 | 产物（`~/.nebflow/plots/education-motion/`） | 复现命令（实测 2026-10-04） |
|---|---|---|
| `rc_schematic.py` | `20261004_edu_rc_charge_schematic.svg`（亮色+暗块，页面嵌入）、`20261004_edu_rc_charge_schematic_dark.png`（暗色透明底，视频内嵌） | `<venv>\python.exe rc_schematic.py` |
| `rc_static_steps.py` | `20261004_edu_rc_charge_steps_static.svg` / `.png`（形态①） | `<venv>\python.exe rc_static_steps.py` |
| `rc_charge_scene.js` | `20261004_edu_rc_charge_3d.html`（形态②，自包含） | `cd <套件>\motion-toolkit\scripts\three-template && <venv>\python.exe build_three_html.py --scene <本文件绝对路径> --out <产物路径> --title "RC 电路充电 · 3D 分步引导（R=1 kΩ，C=1 µF，τ=RC=1 ms · 教学示例值）"` |
| `RcChargeSteps.tsx` | `20261004_edu_rc_charge_steps.mp4`（形态③，1280×720 / h264 / 30fps / 400 帧 / 13.33 s） | 副本已注册进 `motion-toolkit\scripts\remotion-scaffold\src\`（Root.tsx 挂 `RcChargeSteps`，原理图在 `remotion-scaffold\public\rc_schematic_dark.png`），渲染：`cd <套件>\motion-toolkit\scripts && node render-remotion.mjs remotion-scaffold RcChargeSteps <产物路径>` |

`<venv>` = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`。通路验证截图（headless Chrome 实拍亮/暗两态、MP4 抽帧）在 `~/.nebflow/plots/education-motion/_verify/`。

## 数值纪律

一切数值由 `u_C(t) = U(1 − e^(−t/τ))`、`i = (U − u_C)/R` 在各源文件内实时计算（τ = RC = 1 ms），
里程碑：1τ 63.2% → 2τ 86.5% → 3τ 95.0% → 4τ 98.2% → 5τ 99.3%（与 `../education.md` 充电进度表同源）。
改示例值只需改各文件顶部的 `R / C / U` 常量。
