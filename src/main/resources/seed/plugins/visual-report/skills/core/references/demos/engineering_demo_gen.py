# -*- coding: utf-8 -*-
"""engineering 场景 demo 图表生成 —— 演示样例数据（合成，非实测）。
图 1：放大器幅频响应（合成模型：中频增益 26.0 dB，一阶高通 100 Hz × 一阶低通 2 MHz）
图 2：基准电压温漂（合成模型：2.50000 V + 25 µV/°C + 固定种子高斯噪声 σ=30 µV）
输出：SVG → ~/.nebflow/plots/，PNG 副本 → 本工作区 .nebflow/tmp/（目检用）
"""
import os
os.environ.setdefault('MPLCONFIGDIR', r'C:\Users\Kai\.nebflow\tools\mplconfig')
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.ticker import MultipleLocator

plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'SimHei', 'sans-serif']
plt.rcParams['axes.unicode_minus'] = False
plt.rcParams['font.size'] = 12
plt.rcParams['axes.titlesize'] = 15
plt.rcParams['axes.labelsize'] = 13
plt.rcParams['axes.spines.top'] = False
plt.rcParams['axes.spines.right'] = False

NEUTRAL = '#8a8f98'
ACCENT = '#5e6ad2'
ACCENT_LIGHT = '#a0a8e8'
OUT = r'C:\Users\Kai\.nebflow\plots'
TMP = r'C:\Users\Kai\.nebflow\projects\general\.nebflow\tmp'

print('===== DEMO-VALUES（以下字符串逐字用于 demo 文档与图内标注） =====')

# ---------- 图 1：放大器幅频响应（演示样例数据） ----------
G_MID, F_HP, F_LP = 26.0, 100.0, 2e6   # dB, Hz, Hz
f = np.logspace(1, 8, 701)              # 10 Hz ~ 100 MHz，701 点
gdb = (G_MID
       + 10 * np.log10((f / F_HP) ** 2 / (1 + (f / F_HP) ** 2))
       - 10 * np.log10(1 + (f / F_LP) ** 2))
th = G_MID - 3.0                        # -3 dB 判据线 23.0 dB

m_lo = (f >= 10) & (f <= 1e4)           # 上升沿（单调）
m_hi = (f >= 1e5) & (f <= 1e8)          # 下降沿（单调）
fL = 10 ** np.interp(th, gdb[m_lo], np.log10(f[m_lo]))
fH = 10 ** np.interp(th, gdb[m_hi][::-1], np.log10(f[m_hi])[::-1])
bw = fH - fL

s_fL = f'fL = {fL:.1f} Hz'
s_fH = f'fH = {fH / 1e6:.3f} MHz'
s_bw = f'-3 dB 带宽 = {bw / 1e6:.3f} MHz'
s_gain = f'{G_MID:.1f}'
s_th = f'{th:.1f}'
for s in (s_fL, s_fH, s_bw, s_gain, s_th):
    print(s)

fig, ax = plt.subplots(figsize=(8.6, 4.5))
ax.axvspan(fL, fH, color=ACCENT_LIGHT, alpha=0.10, lw=0)
ax.semilogx(f, gdb, color=ACCENT, lw=2, label='幅频响应（演示样例）')
ax.axhline(G_MID, color=NEUTRAL, lw=1, ls='--', label='中频增益 26.0 dB')
ax.axhline(th, color=NEUTRAL, lw=1, ls=':', label='-3 dB 判据线 (23.0 dB)')
ax.plot([fL, fH], [th, th], 'o', color=ACCENT, ms=6, zorder=5)
ax.annotate(s_fL, xy=(fL, th), xytext=(fL * 0.12, 15.0),
            arrowprops=dict(arrowstyle='->', color=NEUTRAL))
ax.annotate(s_fH, xy=(fH, th), xytext=(fH * 1.4, 15.0),
            arrowprops=dict(arrowstyle='->', color=NEUTRAL))
ax.annotate(s_bw, xy=(np.sqrt(fL * fH), 24.4), xytext=(np.sqrt(fL * fH), 30.5),
            ha='center', arrowprops=dict(arrowstyle='->', color=NEUTRAL))
ax.set_xlabel('频率 (Hz)')
ax.set_ylabel('增益 (dB)')
ax.set_title('放大器幅频响应（演示样例数据）')
ax.set_ylim(5, 33)
ax.yaxis.grid(True, alpha=0.3)
ax.legend(loc='upper left', bbox_to_anchor=(1, 1), frameon=False)
plt.tight_layout(pad=1.5)
fig.savefig(os.path.join(OUT, '20261004_210345_eng_demo_amp_freq.svg'))
fig.savefig(os.path.join(TMP, 'eng_demo_amp_freq_check.png'), dpi=160)
plt.close(fig)

# ---------- 图 2：基准电压温漂（演示样例数据） ----------
rng = np.random.default_rng(42)
T = np.arange(-40, 90, 5)                                   # -40~85 °C，n=26
V = 2.50000 + 25e-6 * (T - 25) + rng.normal(0, 30e-6, T.size)
k, b = np.polyfit(T, V, 1)
slope_uV = k * 1e6
ppm = k / 2.5 * 1e6
vmin, vmax = V.min(), V.max()
res = V - (k * T + b)
i_bad = int(np.argmax(np.abs(res)))
res_bad = res[i_bad] * 1e6
fit_lo, fit_hi = k * T.min() + b, k * T.max() + b

s_n = f'{T.size}'
s_slope = f'{slope_uV:.1f} µV/°C'
s_ppm = f'{ppm:.2f} ppm/°C'
s_range = f'{vmin:.5f} ~ {vmax:.5f} V'
s_bad = f'最大偏差 {res_bad:+.1f} µV（T = {T[i_bad]} °C）'
for s in (s_n, s_slope, s_ppm, s_range, s_bad):
    print(s)

fig, ax = plt.subplots(figsize=(8.6, 4.5))
tt = np.array([T.min(), T.max()])
ax.plot(tt, [fit_lo, fit_hi], color=NEUTRAL, lw=1.5, ls='--', label='最小二乘拟合')
ax.plot(T, V, 'o', color=ACCENT, ms=5.5, label=f'样例测量点（n = {s_n}）')
ax.plot(T[i_bad], V[i_bad], 'o', color=ACCENT, ms=9, mfc='none', mew=1.6, zorder=5)
ax.annotate(s_bad, xy=(T[i_bad], V[i_bad]), xytext=(-38, 2.50155),
            arrowprops=dict(arrowstyle='->', color=NEUTRAL))
ax.text(-38, 2.50225, f'拟合温漂 {s_slope}（{s_ppm}）', fontsize=12)
ax.set_xlabel('温度 (°C)')
ax.set_ylabel('输出电压 (V)')
ax.set_title('基准电压温漂（演示样例数据）')
ax.set_ylim(2.4973, 2.5028)
ax.yaxis.set_major_locator(MultipleLocator(0.001))
ax.yaxis.set_major_formatter('{x:.3f}')
ax.yaxis.grid(True, alpha=0.3)
ax.legend(loc='upper left', bbox_to_anchor=(1, 1), frameon=False)
plt.tight_layout(pad=1.5)
fig.savefig(os.path.join(OUT, '20261004_210345_eng_demo_temp_drift.svg'))
fig.savefig(os.path.join(TMP, 'eng_demo_temp_drift_check.png'), dpi=160)
plt.close(fig)

print('===== SVG 已落盘 =====')
for fn in ('20261004_210345_eng_demo_amp_freq.svg', '20261004_210345_eng_demo_temp_drift.svg'):
    p = os.path.join(OUT, fn)
    print(p, os.path.getsize(p), 'bytes')
