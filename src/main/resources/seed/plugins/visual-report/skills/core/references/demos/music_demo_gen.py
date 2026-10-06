# -*- coding: utf-8 -*-
"""music 场景 demo 生成 —— A 大三和弦分解琶音：真合成 wav + 频谱/语谱/钢琴卷帘 3 图。
音律：十二平均律，A4 = 440.000 Hz，f(n) = 440 * 2^(n/12)。
配方：谐波 k=1..6 幅度 1/k；包络 A 8 ms(线性) / D 120 ms(1->0.6 线性) / S 0.6 / R 150 ms(线性)；
      琶音步长 0.25 s，门限统一至 2.000 s；fs 44100 / 16-bit PCM mono / 总时长 2.40 s / 峰值 0.85 FS。
频谱分析窗：0.900–1.900 s（N=44100，Hann，df=1 Hz），四音门限全程处于 sustain 段。
输出：wav + 3 张 SVG → ~/.nebflow/plots/vrs2-music/；PNG 副本 → 本工作区 .nebflow/tmp/（目检用）。
"""
import os
os.environ.setdefault('MPLCONFIGDIR', r'C:\Users\Kai\.nebflow\tools\mplconfig')
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle
from scipy.io import wavfile
from scipy.signal import spectrogram, get_window

plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'SimHei', 'sans-serif']
plt.rcParams['axes.unicode_minus'] = False
plt.rcParams['font.size'] = 12
plt.rcParams['axes.titlesize'] = 14
plt.rcParams['axes.labelsize'] = 13
plt.rcParams['axes.spines.top'] = False
plt.rcParams['axes.spines.right'] = False
plt.rcParams['text.color'] = '#1b1e26'
plt.rcParams['axes.labelcolor'] = '#1b1e26'
plt.rcParams['xtick.color'] = '#1b1e26'
plt.rcParams['ytick.color'] = '#1b1e26'
plt.rcParams['axes.titlecolor'] = '#1b1e26'
plt.rcParams['axes.edgecolor'] = '#6f7480'
plt.rcParams['svg.fonttype'] = 'none'   # 保留真 <text> 元素（默认 path 会把文字转轮廓，暗色 CSS 无法覆写）

ACCENT = '#5e6ad2'      # 亮色主题数据色（SVG 暗色块覆写为 #a0a8e8）
NEUTRAL = '#6f7480'     # 次级线条/文字（暗色覆写 #7a8194）
GRID = '#8a8f98'

OUT = r'C:\Users\Kai\.nebflow\plots\vrs2-music'
TMP = r'C:\Users\Kai\.nebflow\projects\general\.nebflow\tmp'
os.makedirs(OUT, exist_ok=True)
os.makedirs(TMP, exist_ok=True)

# ---------- 音律与配方（唯一数据源，下文所有数值由此计算） ----------
FS = 44100
A4 = 440.0
NOTES = [  # (音名, 相对 A4 半音数, 起始时刻 s)
    ('A4', 0, 0.00),
    ('C#5', 4, 0.25),
    ('E5', 7, 0.50),
    ('A5', 12, 0.75),
]
GATE_END, REL = 2.000, 0.150          # 门限统一释放时刻与 release 时长
DUR = 2.40                            # 总时长
ATK, DEC, SUS = 0.008, 0.120, 0.6     # 包络：attack / decay->sustain / sustain 电平
N_PART, ARP_STEP = 6, 0.25            # 谐波数与琶音步长
PEAK_FS = 0.85                        # 归一化峰值电平（满幅比例）

freqs_th = {nm: A4 * 2 ** (sn / 12) for nm, sn, _ in NOTES}
midi_of = {'A4': 69, 'C#5': 73, 'E5': 76, 'A5': 81}

def adsr(n, onset):
    """attack 线性 0->1 (8ms)；decay 线性 1->SUS (120ms)；sustain 至 GATE_END；release 线性 SUS->0 (150ms)"""
    t = np.arange(n) / FS - onset
    e = np.zeros(n)
    m = (t >= 0) & (t < ATK)
    e[m] = t[m] / ATK
    m = (t >= ATK) & (t < ATK + DEC)
    e[m] = 1 + (SUS - 1) * (t[m] - ATK) / DEC
    m = (t >= ATK + DEC) & (t < GATE_END)
    e[m] = SUS
    m = (t >= GATE_END) & (t < GATE_END + REL)
    e[m] = SUS * (1 - (t[m] - GATE_END) / REL)
    return e

# ---------- 真合成（相位相对各自起始点，与 WebAudio OscillatorNode 行为一致） ----------
n_smp = round(DUR * FS)
t = np.arange(n_smp) / FS
x = np.zeros(n_smp)
for nm, _, onset in NOTES:
    env = adsr(n_smp, onset)
    for k in range(1, N_PART + 1):
        x += env * (1.0 / k) * np.sin(2 * np.pi * k * freqs_th[nm] * (t - onset))

peak_raw = float(np.max(np.abs(x)))
x = x / peak_raw * PEAK_FS
assert np.max(np.abs(x)) <= PEAK_FS + 1e-12
wav_path = os.path.join(OUT, 'amaj_arpeggio_44k16.wav')
wavfile.write(wav_path, FS, (x * 32767.0).astype(np.int16))

# ---------- scipy FFT：0.900–1.900 s 稳态窗 ----------
i0, i1 = round(0.900 * FS), round(1.900 * FS)
seg = x[i0:i1]
N = seg.size                            # 44100
win = get_window('hann', N)
X = np.fft.rfft(seg * win)
fr = np.fft.rfftfreq(N, 1 / FS)
amp = 2 * np.abs(X) / win.sum()         # Hann 窗幅度修正
DF = FS / N                             # 1 Hz

def measure(f_th, span=35.0):
    """窗内找峰（±span Hz）+ 抛物线插值（对数幅度）→ 实测频率/幅度"""
    band = np.abs(fr - f_th) <= span
    idx = int(np.argmax(np.where(band, amp, 0.0)))
    a, b, c = np.log(amp[idx - 1]), np.log(amp[idx]), np.log(amp[idx + 1])
    delta = 0.5 * (a - c) / (a - 2 * b + c)
    return (idx + delta) * DF, amp[idx]

meas = {nm: measure(f) for nm, f in freqs_th.items()}
cents = {nm: 1200 * np.log2(meas[nm][0] / freqs_th[nm]) for nm in freqs_th}
a880 = meas['A5'][1] / meas['A4'][1]     # 880 Hz 合并峰幅度比（理论：1 + 1/2 = 1.50）

print('===== DEMO-VALUES（以下字符串逐字用于 demo 文档与图内标注） =====')
print('[theory] ' + ' | '.join(f'{nm} {freqs_th[nm]:.4f} Hz' for nm, _, _ in NOTES))
print('[measured] ' + ' | '.join(
    f'{nm} {meas[nm][0]:.2f} Hz ({round(cents[nm], 2) + 0.0:+.2f} cent)' for nm, _, _ in NOTES))
print(f'[merged] 880Hz 峰幅度 / 440Hz 峰幅度 = {a880:.2f}（理论 1.50）')
print(f'[wav] {wav_path} | {os.path.getsize(wav_path)} bytes | fs=44100 | 16-bit PCM mono | 2.40 s | 峰值 {PEAK_FS} FS（归一前峰 {peak_raw:.4f}）')
print(f'[fft] 窗 0.900-1.900 s N={N} Hann df={DF} Hz')

# ---------- SVG 暗色块注入（亮色值内联烘焙在子元素上、gid 在父 g 上 → 必须用后代选择器强制覆写） ----------
DARK_CSS = """
@media (prefers-color-scheme: dark) {
  text { fill: #e8eaed !important; }
  #bartext, #bartext text, #bartext * { fill: #14161c !important; }
  #curve, #curve * { stroke: #a0a8e8 !important; }
  #bars, #bars * { fill: #a0a8e8 !important; }
  #peaks, #peaks * { stroke: #a0a8e8 !important; fill: #a0a8e8 !important; }
  #thline, #thline * { stroke: #7a8194 !important; }
  #onsetline, #onsetline * { stroke: #8a8f98 !important; }
  #fspan, #fspan * { fill: #a0a8e8 !important; }
  #axline, #axline * { stroke: #4a5164 !important; }
  #gridline, #gridline * { stroke: #33384a !important; }
}
"""

def finish(fig, name):
    svg = os.path.join(OUT, name)
    png = os.path.join(TMP, 'music_' + name.replace('.svg', '_check.png'))
    fig.savefig(svg, transparent=True, dpi=150)
    fig.savefig(png, dpi=150)
    plt.close(fig)
    with open(svg, encoding='utf-8') as f:
        s = f.read()
    head = s.index('>', s.index('<svg')) + 1
    with open(svg, 'w', encoding='utf-8') as f:
        f.write(s[:head] + '\n<style type="text/css">' + DARK_CSS + '</style>' + s[head:])
    print(f'[file] {svg} | {os.path.getsize(svg)} bytes')

def darkify(ax):
    for sp in ax.spines.values():
        sp.set_gid('axline')
    for axis in (ax.xaxis, ax.yaxis):
        for tk in axis.get_major_ticks():
            tk.gridline.set_gid('gridline')

# ---------- 图 1：FFT 频谱（峰值频率 ↔ 音名逐字一致） ----------
fig, ax = plt.subplots(figsize=(8.6, 4.6))
band = (fr >= 350) & (fr <= 2350)
db = 20 * np.log10(np.maximum(amp, amp.max() * 10 ** (-50 / 20)) / amp.max())
ax.plot(fr[band], db[band], color=ACCENT, lw=1.4, gid='curve')
for nm, _, _ in NOTES:
    ft = freqs_th[nm]
    dbp = 20 * np.log10(meas[nm][1] / amp.max())
    ax.vlines(ft, -48, dbp - 1.5, color=NEUTRAL, lw=0.9, ls=':', gid='thline')
    fm, am = meas[nm]
    ax.plot([fm], [dbp], 'o', color=ACCENT, ms=6, zorder=5, gid='peaks')
    labels = {'A4': (395, 6), 'C#5': (495, 2.5), 'E5': (700, 6), 'A5': (800, 2.5)}
    tx, ty = labels[nm]
    ax.annotate(f'{nm} {fm:.2f} Hz', xy=(fm, dbp), xytext=(tx, ty), fontsize=11,
                arrowprops=dict(arrowstyle='->', color=NEUTRAL, lw=1))
ax.text(0.985, 0.10,
        f'880 Hz 峰 = A5 基频 ⊕ A4 二次泛音（同频叠加，幅度比实测 {a880:.2f}，理论 1.50）',
        transform=ax.transAxes, ha='right', fontsize=10.5, color=NEUTRAL)
ax.set_xlabel('频率 (Hz)')
ax.set_ylabel('幅度 (dB，相对最强峰)')
ax.set_title('A 大三和弦琶音·稳态段频谱（FFT：0.900–1.900 s，Hann，Δf = 1 Hz）')
ax.set_xlim(350, 2350)
ax.set_ylim(-48, 10)
ax.yaxis.grid(True, alpha=0.35, color=GRID)
darkify(ax)
fig.tight_layout(pad=1.5)
finish(fig, '20261004_music_demo_spectrum.svg')

# ---------- 图 2：语谱图（琶音 ①②③④ 台阶） ----------
fig, ax = plt.subplots(figsize=(8.6, 4.4))
fg, tt, Sxx = spectrogram(x, fs=FS, window='hann', nperseg=4096, noverlap=3072)
pc = ax.pcolormesh(tt, fg, 10 * np.log10(Sxx + 1e-12), shading='auto',
                   cmap='viridis', vmin=-100, vmax=-25, rasterized=True)
cb = fig.colorbar(pc, ax=ax, pad=0.015)
cb.set_label('功率谱密度 (dB/Hz)')
for i, (nm, _, onset) in enumerate(NOTES):
    ax.axvline(onset, color='w', lw=1.0, ls='--', alpha=0.9, gid='onsetline')
    ax.annotate(f'{"①②③④"[i]} {nm} {onset:.2f} s',
                xy=(onset, 2250 if i % 2 == 0 else 2080), fontsize=11, color='w', ha='left')
ax.set_xlabel('时间 (s)')
ax.set_ylabel('频率 (Hz)')
ax.set_title('A 大三和弦琶音·语谱图（四音依次进入，2.0 s 起统一释放）')
ax.set_ylim(0, 2400)
ax.set_xlim(-0.03, 2.43)
darkify(ax)
fig.tight_layout(pad=1.5)
finish(fig, '20261004_music_demo_spectrogram.svg')

# ---------- 图 3：钢琴卷帘 ----------
fig, ax = plt.subplots(figsize=(8.6, 4.2))
ax.axvspan(0.900, 1.900, color=ACCENT, alpha=0.10, lw=0, gid='fspan')
ax.text(1.400, 82.8, '频谱分析窗 0.900–1.900 s', ha='center', fontsize=10.5, color=NEUTRAL)
for nm, _, onset in NOTES:
    y = midi_of[nm]
    ax.add_patch(Rectangle((onset, y - 0.38), GATE_END - onset, 0.76,
                           facecolor=ACCENT, alpha=0.88, edgecolor='none', gid='bars'))
    ax.add_patch(Rectangle((GATE_END, y - 0.38), REL, 0.76,
                           facecolor=ACCENT, alpha=0.30, edgecolor='none', gid='bars'))
    ax.text(onset + 0.06, y, f'{nm}（{freqs_th[nm]:.2f} Hz）', va='center',
            fontsize=10.5, color='#ffffff', gid='bartext')
    ax.plot([onset, onset], [y - 0.55, y + 0.55], color=NEUTRAL, lw=1, gid='thline')
ax.set_yticks([midi_of[nm] for nm, _, _ in NOTES])
ax.set_yticklabels([nm for nm, _, _ in NOTES])
ax.set_ylim(66.5, 83.5)
ax.set_xlim(-0.05, 2.43)
ax.set_xlabel('时间 (s)')
ax.set_ylabel('音高（MIDI 音符号）')
ax.set_title('A 大三和弦琶音·钢琴卷帘（实心=门限段，浅色=release 尾，步长 0.25 s）')
ax.xaxis.grid(True, alpha=0.35, color=GRID)
darkify(ax)
fig.tight_layout(pad=1.5)
finish(fig, '20261004_music_demo_pianoroll.svg')

print('===== 落盘完成 =====')
