# 泰勒级数截断误差静态图（对数轴）——teaching demo 配套
# 参数（教学声明）：中心点 a=0（Maclaurin），评估点 x0=π/2，
#   sin 取奇数阶 n=1,3,5,7,9；cos 取偶数阶 n=0,2,4,6,8。
# 数值逐字红线：图内标注 = stdout 表格 = demo md 表格，同一 fmt 函数生成。
import math
from pathlib import Path

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

OUT = Path(r"C:\Users\Kai\.nebflow\plots\vrs2-math")
OUT.mkdir(parents=True, exist_ok=True)
X0 = math.pi / 2
ACCENT = "#5e6ad2"      # core §3 强调色
NEUTRAL = "#8a8f98"     # core §3 中性灰
WARM = "#b0713d"        # cos 曲线色（非纯色，双主题可读）

def fmt(v: float) -> str:
    return f"{v:.4e}"

def sin_taylor(x: float, deg: int) -> float:
    """sin 的 Maclaurin 截断，deg 为最高次幂（奇数）。"""
    s = 0.0
    for k in range(0, deg + 1, 2):
        s += (-1) ** (k // 2) * x ** (k + 1) / math.factorial(k + 1)
    return s

def cos_taylor(x: float, deg: int) -> float:
    """cos 的 Maclaurin 截断，deg 为最高次幂（偶数）。"""
    s = 0.0
    for k in range(0, deg + 1, 2):
        s += (-1) ** (k // 2) * x ** k / math.factorial(k)
    return s

SIN_DEG = [1, 3, 5, 7, 9]
COS_DEG = [0, 2, 4, 6, 8]

f_sin, f_cos = float(np.sin(X0)), float(np.cos(X0))  # 基准：numpy 双精度
rows_sin = [(n, sin_taylor(X0, n), abs(f_sin - sin_taylor(X0, n))) for n in SIN_DEG]
rows_cos = [(n, cos_taylor(X0, n), abs(f_cos - cos_taylor(X0, n))) for n in COS_DEG]

print(f"x0 = pi/2 = {X0!r}")
print(f"ref sin(x0) = {f_sin!r}   ref cos(x0) = {f_cos!r}")
print("| degree | T_n(x0) | err |")
for n, t, e in rows_sin:
    print(f"| sin n={n} | {fmt(t)} | {fmt(e)} |")
for n, t, e in rows_cos:
    print(f"| cos n={n} | {fmt(t)} | {fmt(e)} |")

# 交错级数下一项上界：sin 余项 ≤ x0^(n+2)/(n+2)!；cos 余项 ≤ x0^(n+1)/(n+1)!
b_sin = [X0 ** (n + 2) / math.factorial(n + 2) for n, _, _ in rows_sin]
b_cos = [X0 ** (n + 1) / math.factorial(n + 1) for n, _, _ in rows_cos]

fig, ax = plt.subplots(figsize=(8.6, 5.4), dpi=150)
ns_s = [r[0] for r in rows_sin]
ns_c = [r[0] for r in rows_cos]
es_s = [r[2] for r in rows_sin]
es_c = [r[2] for r in rows_cos]

ax.semilogy(ns_s, es_s, "o-", color=ACCENT, lw=2, ms=6, label="sin：误差 $|\\sin x_0 - T_n(x_0)|$")
ax.semilogy(ns_c, es_c, "s-", color=WARM, lw=2, ms=6, label="cos：误差 $|\\cos x_0 - T_n(x_0)|$")
ax.semilogy(ns_s, b_sin, "--", color=ACCENT, lw=1, alpha=0.55, label="下一项上界 $x_0^{n+2}/(n+2)!$")
ax.semilogy(ns_c, b_cos, ":", color=WARM, lw=1, alpha=0.55, label="下一项上界 $x_0^{n+1}/(n+1)!$")

for i, (n, _, e) in enumerate(rows_sin):
    ax.annotate(fmt(e), (n, e), textcoords="offset points", xytext=(6, 7), fontsize=8, color=ACCENT)
for i, (n, _, e) in enumerate(rows_cos):
    ax.annotate(fmt(e), (n, e), textcoords="offset points", xytext=(6, -13), fontsize=8, color=WARM)

ax.set_xticks(sorted(set(ns_s + ns_c)))
ax.set_xlabel("展开阶数 n（sin 取奇数阶 / cos 取偶数阶）")
ax.set_ylabel("截断误差（对数轴）")
ax.set_title("泰勒级数在 $x_0=\\pi/2$ 处的逐阶截断误差（中心点 $a=0$）")
ax.grid(True, which="both", alpha=0.25)
ax.legend(fontsize=9, loc="upper right")
fig.tight_layout()
p = OUT / "taylor_error_logscale.png"
fig.savefig(p)
print(f"saved: {p}")
