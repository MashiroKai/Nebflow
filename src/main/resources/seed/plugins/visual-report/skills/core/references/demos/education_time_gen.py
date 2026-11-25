"""Source: education.md §形式化 and §图解: RC=1 ms, 125 Hz, 0–1 V square wave."""
import sys
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
fm.fontManager.addfont('/System/Library/Fonts/STHeiti Light.ttc')
plt.rcParams['font.family'] = fm.FontProperties(fname='/System/Library/Fonts/STHeiti Light.ttc').get_name()
t = np.linspace(0, 8, 1601)
u = np.where(t < 4, 1.0, 0.0)
y = np.where(t < 4, 1-np.exp(-t), (1-np.exp(-4))*np.exp(-(t-4)))
fig, ax = plt.subplots(figsize=(9, 5), dpi=160)
ax.step(t, u, where='post', label='输入方波（125 Hz）', color='#8a8f98', linestyle='--')
ax.plot(t, y, label='电容电压', color='#5e6ad2', linewidth=2.5)
for x, text in [(0, '① 开始充电'), (1, '② 按 τ 收敛'), (4, '③ 开始放电'), (6, '④ 按 τ 放电')]:
    ax.axvline(x, color='#c8cbd2', linewidth=.8)
    ax.text(x+.08, .1 if x < 4 else .7, text, fontsize=10)
ax.set(xlabel='时间 (ms)', ylabel='电压 (V)', title='RC 低通时域充放电（R = 1 kΩ，C = 1 µF）', xlim=(0, 8), ylim=(-.05, 1.15))
ax.grid(alpha=.2); ax.legend(); fig.tight_layout(); fig.savefig(sys.argv[1]); plt.close(fig)
