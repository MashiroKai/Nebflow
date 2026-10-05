# -*- coding: utf-8 -*-
"""电荷灵敏前置放大器（CSPA）原理图生成脚本 — visual-report-suite · eda 场景 demo

拓扑（核电子学标准接法）：
  探测器（电荷脉冲 Q）→ Cd 交流耦合 → Q1(N-JFET) 共源输入级 → RL 漏极负载
  反馈网络：Cf（电荷积分）∥ Rf（泄放/直流偏置）跨接输出-输入
  输出级：Q2(NPN) 射极跟随器 + RE 偏置 → Cc 输出耦合 → OUT

运行（viz-venv，schemdraw 真实渲染，禁手画 SVG）：
  C:\\Users\\Kai\\.nebflow\\tools\\viz-venv\\Scripts\\python.exe 本文件
输出（出图默认落 ~/.nebflow/plots/，core/SKILL.md §7）：
  C:\\Users\\Kai\\.nebflow\\plots\\<YYYYMMDD>_eda_cspa_light.png  亮色主题
  C:\\Users\\Kai\\.nebflow\\plots\\<YYYYMMDD>_eda_cspa_dark.png   暗色主题
"""
from datetime import date

import matplotlib
matplotlib.use('Agg')
matplotlib.rc_file('C:/Users/Kai/.nebflow/tools/mplconfig/matplotlibrc')  # YaHei 全局配置（README 迁移资产）
matplotlib.rcParams['axes.unicode_minus'] = False

import schemdraw
import schemdraw.elements as elm

OUTDIR = 'C:/Users/Kai/.nebflow/plots'
STAMP = date.today().strftime('%Y%m%d')
VDD = getattr(elm, 'Vdd', None)  # 0.23 具备；留 getattr 兜底旧版本


def add_vdd(d, label='+VDD'):
    if VDD is not None:
        d += VDD().label(label, loc='right')
    else:
        d += elm.Line().up(0.4).label(label, loc='right')


def build(color: str, bgcolor: str) -> schemdraw.Drawing:
    d = schemdraw.Drawing()
    d.config(color=color, bgcolor=bgcolor, lw=1.7, fontsize=13, font='sans-serif')

    # ── 输入：探测器端口 → Cd 耦合 → 栅极节点 G ──────────────────────────
    d += elm.Dot(open=True).at((0, 0)).label('探测器输入\n（电荷脉冲 $Q$）', loc='left')
    d += elm.Line().at((0, 0)).right(0.7)
    d += elm.Capacitor().right().label('$C_d$\n输入耦合', loc='top')
    G = d.add(elm.Dot())
    gxy = G.center

    # ── 输入级：Q1 N-JFET 共源放大 ───────────────────────────────────────
    # reverse() ⇒ 栅极引线朝左、漏极朝上（实测锚点：drain=gate+(+0.92,+0.92)）
    Q1 = d.add(elm.JFetN().reverse().anchor('gate').at((gxy[0], gxy[1] - 1.6))
               .label('$Q_1$ JFET\n输入级', loc='right'))
    d += elm.Line().at(gxy).to(Q1.gate)                 # 栅极竖引线（自 G 下引）
    d += elm.Line().at(Q1.drain).to((Q1.drain[0], 0))   # 漏极升至主电平（G 右侧输出节点）
    D1 = d.add(elm.Dot().at((Q1.drain[0], 0)))
    d += elm.Line().at(D1.center).right(2.2)
    D2 = d.add(elm.Dot())
    d2x = D2.center[0]

    # 漏极负载 + 电源（短 body + 长引线，让反馈桥从其上方跨过，零交叉）
    d += elm.Resistor().at(D1.center).up(1.2).label('$R_L$ 漏极负载', loc='right', ofst=(0.75, 0))
    d += elm.Line().at(D1.center).up(3.4)
    add_vdd(d)

    # 源极接地
    d += elm.Line().at(Q1.source).down(0.7)
    d += elm.Ground()

    # ── 反馈网络：Rf（泄放/直流偏置）∥ Cf（电荷积分），跨输出-输入 ────────
    d += elm.Line().at(gxy).up(4.4)
    d += elm.Resistor().right().tox(d2x).label('$R_f$ 泄放/偏置', loc='top')
    d += elm.Line().to((d2x, 4.4))
    d += elm.Line().down(4.4)
    d += elm.Dot().at((gxy[0], 4.4))
    d += elm.Dot().at((d2x, 4.4))

    d += elm.Line().at(gxy).up(5.4)
    d += elm.Capacitor().right().tox(d2x).label('$C_f$ 电荷积分', loc='top')
    d += elm.Line().to((d2x, 5.4))
    d += elm.Line().down(5.4)

    # ── 输出级：Q2 NPN 射极跟随器（阻抗变换/缓冲）────────────────────────
    Q2 = d.add(elm.BjtNpn().anchor('base').at((d2x + 2.0, 0))
               .label('$Q_2$ NPN\n射极跟随', loc='right'))
    d += elm.Line().at(D2.center).to(Q2.base)           # 输出节点 → 基极
    d += elm.Line().at(Q2.collector).up(0.6)
    add_vdd(d)
    d += elm.Line().at(Q2.emitter).down(0.6)
    E = d.add(elm.Dot())
    d += elm.Resistor().at(E.center).down(2.2).label('$R_E$ 偏置', loc='left')
    d += elm.Ground()

    # 输出耦合 → OUT
    d += elm.Line().at(E.center).right(0.9)
    d += elm.Capacitor().right().label('$C_c$\n输出耦合', loc='top')
    d += elm.Dot(open=True).label('OUT', loc='right')

    return d


def render(color, bgcolor, fname):
    d = build(color, bgcolor)
    d.save(fname, transparent=False, dpi=200)
    print('saved', fname)


if __name__ == '__main__':
    render('#1b1e26', '#ffffff', f'{OUTDIR}/{STAMP}_eda_cspa_light.png')
    render('#e8eaed', '#14161c', f'{OUTDIR}/{STAMP}_eda_cspa_dark.png')
