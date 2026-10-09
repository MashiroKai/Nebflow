# 泰勒级数逐阶逼近 sin/cos 过程动画 —— motion-toolkit 备选动画栈用户脚本
# 渲染一律经封装（禁止绕开自起炉灶）：
#   cd C:/Users/Kai/.nebflow/skills/visual-report-suite/motion-toolkit/scripts
#   <venv>/python.exe mpl_anim.py --script <本文件> \
#       --out C:/Users/Kai/.nebflow/plots/vrs2-math/taylor_sin_cos_approx.mp4 \
#       --fps 20 --figsize 12,4.6
# 契约：build(fig, ax) -> (update, n_frames)；环境（MPLCONFIGDIR/ffmpeg exe）由封装固化。
# 教学参数（与 demo md、静态误差图一致）：中心点 a=0，x∈[-π, π]，评估点 x0=π/2，
#   项数 k=1..5：sin 阶数 1,3,5,7,9；cos 阶数 0,2,4,6,8。
# 动画结构：每阶 = 36 帧部分和沿 x 逐步展开 + 12 帧定格，共 5×48=240 帧 @20fps = 12.0s。
import math

import numpy as np

ACCENT = "#5e6ad2"   # core §3 强调色（sin）
WARM = "#b0713d"     # cos（非纯色，亮暗底可读）
NEUTRAL = "#8a8f98"  # core §3 中性灰（真值虚线）
TEXT = "#1b1e26"

X = np.linspace(-math.pi, math.pi, 721)
X0 = math.pi / 2
K_MAX = 5
REVEAL, HOLD = 36, 12
N_FRAMES = K_MAX * (REVEAL + HOLD)   # 240

def sin_taylor(xs, deg):
    s = np.zeros_like(xs)
    for k in range(0, deg + 1, 2):
        s = s + (-1) ** (k // 2) * xs ** (k + 1) / math.factorial(k + 1)
    return s

def cos_taylor(xs, deg):
    s = np.zeros_like(xs)
    for k in range(0, deg + 1, 2):
        s = s + (-1) ** (k // 2) * xs ** k / math.factorial(k)
    return s

def _panel(ax, name, ref, tfn, color):
    ax.plot(X, ref(X), "--", color=NEUTRAL, lw=1.6, label=f"{name}(x) 真值")
    line, = ax.plot([], [], color=color, lw=2.4, label="泰勒部分和")
    dot, = ax.plot([], [], "o", color=color, ms=6)
    ax.set_xlim(-math.pi - 0.2, math.pi + 0.2)
    ax.set_ylim(-2.2, 2.2)
    ax.axhline(0, color=NEUTRAL, lw=0.6, alpha=0.5)
    ax.grid(True, alpha=0.25)
    ax.set_xlabel("x")
    ax.set_title(name)
    ax.legend(loc="upper left", fontsize=8)
    return line, dot

def build(fig, ax):
    fig.delaxes(ax)                      # 封装给的是单面板占位，改双面板
    axs, axc = fig.subplots(1, 2)
    sin_line, sin_dot = _panel(axs, "sin", np.sin, sin_taylor, ACCENT)
    cos_line, cos_dot = _panel(axc, "cos", np.cos, cos_taylor, WARM)
    fig.suptitle("泰勒级数逐阶逼近 sin / cos（中心点 a = 0）", fontsize=13)
    order_txt = fig.text(0.5, 0.015, "", ha="center", fontsize=10, color=TEXT)
    fig.tight_layout(rect=(0, 0.06, 1, 0.93))

    def update(frame):
        k = frame // (REVEAL + HOLD) + 1          # 当前项数 1..5
        inner = frame % (REVEAL + HOLD)
        frac = min(1.0, inner / (REVEAL - 1))     # 展开进度 0..1
        ds, dc = 2 * k - 1, 2 * (k - 1)           # sin 奇数阶 / cos 偶数阶
        xmax = -math.pi + frac * 2 * math.pi
        m = X <= xmax
        sin_line.set_data(X[m], sin_taylor(X[m], ds))
        cos_line.set_data(X[m], cos_taylor(X[m], dc))
        es = abs(float(np.sin(X0)) - sin_taylor(np.array([X0]), ds)[0])
        ec = abs(float(np.cos(X0)) - cos_taylor(np.array([X0]), dc)[0])
        sin_dot.set_data([X0], [sin_taylor(np.array([X0]), ds)[0]])
        cos_dot.set_data([X0], [cos_taylor(np.array([X0]), dc)[0]])
        order_txt.set_text(
            f"项数 k={k}/5（sin 至 {ds} 阶，cos 至 {dc} 阶）　"
            f"在 x=π/2 处误差 sin {es:.4e}　cos {ec:.4e}"
        )

    return update, N_FRAMES
