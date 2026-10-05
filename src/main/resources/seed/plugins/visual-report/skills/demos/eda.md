# 示例 · 电荷灵敏前置放大器（CSPA）——eda 场景 demo

schemdraw 真实渲染的电路原理图示例，核电子学标准拓扑：JFET 输入级 + 反馈电容 $C_f$（并联泄放电阻 $R_f$）+ 射极跟随输出级。公共规范见 `../core/SKILL.md`，场景增量见 `../eda/SKILL.md`。

## 原理图（亮色主题）

![电荷灵敏前置放大器原理图——亮色主题](~/.nebflow/plots/20261004_eda_cspa_light.png)

## 原理图（暗色主题）

![电荷灵敏前置放大器原理图——暗色主题](~/.nebflow/plots/20261004_eda_cspa_dark.png)

## 信号路径

探测器电荷脉冲 $Q$ → Cd 交流耦合进输入级 → Q1 共源放大（反相）→ 漏极负载 RL 取出 → Q2 射极跟随缓冲 → Cc 耦合输出。反馈网络 Cf∥Rf 从漏极输出节点跨回栅极：Cf 收集电荷、设定电荷-电压增益；Rf 泄放积分电荷、稳定直流工作点。

## 逐元件说明

| 元件 | 名称 | 作用 |
|---|---|---|
| Cd | 输入耦合电容 | 把探测器电荷脉冲交流耦合进输入级，隔离探测器偏置直流 |
| Q1 | N-JFET 输入级 | 低噪声共源放大；栅极为高阻输入点，电荷信号在此转换为电压 |
| Cf | 反馈积分电容 | 收集输入电荷，输出幅度 $V_{out} \approx Q/C_f$——增益由 Cf 设定 |
| Rf | 泄放/偏置电阻 | 与 Cf 并联，泄放电荷、建立栅极直流偏置；衰减时间常数 $\tau \approx R_f C_f$ |
| RL | 漏极负载电阻 | 设定 Q1 漏极静态工作点，取出放大后的信号 |
| +VDD | 供电轨 | 两级共用正电源 |
| Q2 | NPN 射极跟随器 | 输出级阻抗变换 / 电流缓冲，驱动后级电缆或主放大器 |
| RE | 发射极偏置电阻 | 设定 Q2 静态工作电流 |
| Cc | 输出耦合电容 | 隔直流输出信号 |
| IN / OUT | 端口 | 探测器侧输入 / 前放输出 |

上表只述拓扑功能，不含元件数值——阻容值必须来自实际图纸 / 网表 / BOM（见 `../eda/SKILL.md` 第 4 节红线），示例拓扑不含臆造参数。

## 复现

```bash
C:\Users\Kai\.nebflow\tools\viz-venv\Scripts\python.exe C:\Users\Kai\.nebflow\skills\visual-report-suite\demos\eda_cspa_schematic.py
```

schemdraw 0.23 实测渲染（viz-venv，2026-10-04），亮 / 暗两张 PNG 落 `~/.nebflow/plots/`；脚本即本 demos 目录内 `eda_cspa_schematic.py`。
