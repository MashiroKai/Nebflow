import os, sys
try:
    import matplotlib
    matplotlib.use('Agg')
except ImportError:
    pass

# ── User code ──
import numpy as np
import matplotlib.pyplot as plt
x = np.linspace(0, 2*np.pi, 200)
plt.plot(x, np.sin(x), label='sin(x)', linewidth=2)
plt.plot(x, np.cos(x), label='cos(x)', linewidth=2)
plt.legend(fontsize=12)
plt.title('Trigonometric Functions', fontsize=14)
plt.xlabel('x', fontsize=12)
plt.ylabel('y', fontsize=12)
plt.grid(True, alpha=0.3)
# ── End user code ──

# Auto-save all open matplotlib figures
try:
    import matplotlib.pyplot as _plt
    _dir = '$WORKSPACE'
    _fmt = 'png'
    _dpi = 150
    for _i, _num in enumerate(_plt.get_fignums()):
        _path = os.path.join(_dir, f'nebflow_plot_{_i}.{_fmt}')
        _plt.figure(_num).savefig(
            _path, dpi=_dpi, bbox_inches='tight',
            facecolor='white', transparent=False
        )
        print(f'[nebflow] Saved: {_path}')
    if not _plt.get_fignums():
        print('[nebflow] No matplotlib figures found', file=sys.stderr)
except Exception as _e:
    print(f'[nebflow] Auto-save error: {_e}', file=sys.stderr)
