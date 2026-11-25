"""Source: education.md §形式化 and §图解: RC low-pass, R=1 kΩ, C=1 µF."""
import sys
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
fm.fontManager.addfont('/System/Library/Fonts/STHeiti Light.ttc')
plt.rcParams['font.family'] = fm.FontProperties(fname='/System/Library/Fonts/STHeiti Light.ttc').get_name()
fc = 1 / (2*np.pi*1000*1e-6)
f = np.logspace(np.log10(fc/100), np.log10(fc*100), 700)
db = -10*np.log10(1+(f/fc)**2)
fig, ax = plt.subplots(figsize=(9, 5), dpi=160)
ax.semilogx(f, db, color='#5e6ad2', linewidth=2.5)
for factor in (1, 10):
    x=fc*factor; y=-10*np.log10(1+factor**2)
    ax.plot(x,y,'o',color='#5e6ad2')
    ax.annotate(f'{x:.2f} Hz\n{y:.2f} dB', (x,y), xytext=(12, -25), textcoords='offset points')
ax.axhline(-3.0103, color='#8a8f98', linestyle='--', linewidth=.8)
ax.set(xlabel='频率 (Hz)', ylabel='增益 (dB)', title='RC 低通幅频特性（R = 1 kΩ，C = 1 µF）')
ax.grid(which='both',alpha=.2); fig.tight_layout(); fig.savefig(sys.argv[1]); plt.close(fig)
