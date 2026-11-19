# academic-research 场景示例：C-06 PDOZ 单方案缩影

> **本示例怎么读**：这是 [`academic-research/SKILL.md`](../../../academic-research/SKILL.md) §2 骨架的最小可运行形态——从既有 SiPM 裁图库取一个方案做缩影：2 张论文原图裁片**全部复用、未重新裁图**，图注四件套齐全，marked v12（`breaks:true`）兼容，无占位框。全量范例（158 图图集）：`~/.nebflow/projects/Sipm/reports/20261004_095255_星际航行辐射监测_探测器与读出结构图集.md` §2.2。

## 方案档案（出处可回溯）

| 项 | 值 |
|---|---|
| 方案 | C-06 PDOZ——塑闪 + 双 SiPM 符合个人剂量计（β / γ / 中子三道） |
| 论文 | C-06-1：Salvi L., Rossi G., Bartolini G., Alpat A.B., Bozkurt A., Cegil M.D., Guleryuz A.T.，「PDOZ: innovative personal electronic dosimeter for electron and gamma H∗(d) dosimetry」, Journal of Instrumentation 18 P08010, 2023，DOI 10.1088/1748-0221/18/08/P08010 |
| PDF | `~/.nebflow/projects/Sipm/papers/2023_Salvi_PDOZ_personal_dosimeter_SiPM.pdf`（17 页，已落盘） |
| 状态 | 地面试验（原型）；基线引用「塑闪+双 SiPM（PDOZ）」 |

## 结构图（detector）

**`C-06_detector_01.png`** ｜ C-06-1 · Figure 1 · p4

![C-06_detector_01](~/.nebflow/projects/Sipm/papers/figs/C/C-06_detector_01.png)

> - **英文图注（逐字）**：Figure 1: Geometry of the PDOZ. The scintillators are in purple, blue and green, whereas the SiPMs, two for each scintillator, are in red.
> - **中译**：PDOZ 几何：闪烁体为紫/蓝/绿三色，每闪烁体底部 2 个 SiPM 为红色
> - **出处**：C-06-1 · Figure 1 · p4（Salvi et al., JINST 18 P08010, 2023，DOI 10.1088/1748-0221/18/08/P08010；PDF 见方案档案）
> - **看图要点**：PDOZ 个人剂量计内部几何（Geant4 线框）：三种闪烁体并排同高装于 PED 外壳内，底部各耦合 2 SiPM（库内注：旧图 PDOZ_geometry_p4.png 的同图重制高清版）

**`C-06_detector_02.png`** ｜ C-06-1 · Figure 11 · p13

![C-06_detector_02](~/.nebflow/projects/Sipm/papers/figs/C/C-06_detector_02.png)

> - **英文图注（逐字）**：Figure 11: Geometry of the plastic scintillator with two holes of depth 5 mm each in which two SiPMs are inserted and geometry of the source. The red disk is made of plexiglass whereas the blue one of epoxy.
> - **中译**：塑闪几何：两个深 5 mm 的孔内插 2 个 SiPM；源几何为红盘（有机玻璃）与蓝盘（环氧）
> - **出处**：C-06-1 · Figure 11 · p13（同 C-06-1，见方案档案）
> - **看图要点**：BC408 塑闪 10×5×15 mm³ 双孔（SiPM 插入）+ 90Sr 源几何（plexiglass/epoxy 圆盘）模拟视图

## 方案要点（数值逐字）

- 结构：三闪烁体（BC408 塑闪测 β、CsI(Tl) 测 γ、¹⁰B/⁶LiF 涂层晶体测中子）+ 底部双 SiPM（ASD-NUV1C-P）；塑闪 10×5×15 mm³；ICRU 球（Ø30 cm、76.2% O）。
- 读出：双 SiPM 符合触发（两个同时超阈才计一次）；p.e.0/1/2 三档阈值；CF = X/R 转换曲线；计数 → $H^*(10)$。
- 缺类注：论文仅以文字描述双 SiPM 符合逻辑，**无读出框图**（如实标注，未凑数）；亦无安装图。

## 资产与预算

| 项 | 值 |
|---|---|
| 嵌入原图 | 2 张（自绘 0 张；原图裁片不计自绘图预算，口径见 SKILL.md §1） |
| 复用资产 | `papers/figs/C/C-06_detector_01.png`（1840×1310 px）· `papers/figs/C/C-06_detector_02.png`（2750×2288 px），均在 `~/.nebflow/projects/Sipm/` 裁图库原位 |
| 重新裁图 | 0 张——本示例全部复用既有裁图库，未重新裁图 |
| 登记表 | `papers/figs/FIGS_INDEX.md` F-131 / F-132 行（英文图注 · 中译 · 看图要点与本示例逐字同源） |
