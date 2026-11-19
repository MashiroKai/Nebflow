"""motion-toolkit · mpl_anim.py 冒烟/起步示例：阻尼行波。

数值全部来自本文件公式，实时计算（core §1.2 数值逐字：不脑补、不硬编伪装实测值）。
y(x, t) = sin(2π(x/3 - t)) · exp(-x/8)
"""
import numpy as np

X = np.linspace(0, 10, 400)


def build(fig, ax):
    (line,) = ax.plot(X, np.zeros_like(X), lw=2.2, color="#5e6ad2")
    ax.set_xlim(0, 10)
    ax.set_ylim(-1.15, 1.15)
    ax.set_xlabel("x")
    ax.set_ylabel("y")
    ax.set_title("阻尼行波  $y=\\sin(2\\pi(x/3-t))\\cdot e^{-x/8}$")
    ax.grid(True, alpha=0.3)

    def update(frame):
        t = frame / 40.0
        y = np.sin(2 * np.pi * (X / 3.0 - t)) * np.exp(-X / 8.0)
        line.set_ydata(y)
        return (line,)

    return update, 40  # 40 帧
