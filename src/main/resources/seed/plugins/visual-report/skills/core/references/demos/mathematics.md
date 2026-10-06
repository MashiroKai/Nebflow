# 泰勒级数逐阶逼近 sin / cos —— 数学场景小样

> 本文件是 `../mathematics/SKILL.md` 的配套示例，演示五段骨架（直觉引入 → 形式化 → 图解 → 常见误区 → 练习）与三类产件（过程动画 MP4 / manim 公式对照段 / 对数轴误差图）。公共规范见 `../core/SKILL.md`；过程动画经 `../motion-toolkit` 封装出片。图预算：3/3（含视频）。

## 直觉引入

计算机算 sin 从不「直接算 sin」——它算多项式。泰勒级数把 sin 拆成一次次「加弯」：1 阶是过原点的直线，3 阶开始有了像样的弯曲，到 9 阶（5 项）在 $[-\pi, \pi]$ 内肉眼已分不出它与真值曲线的差别。本样从中心点 $a=0$ 出发（Maclaurin 级数），在图 3 的对数轴上看误差随阶数近似沿直线塌缩。

**类比失效边界**：「加弯」只承载图像形状的直觉。阶数与误差的定量关系（余项界、收敛速度）必须回到形式化段的公式；从「弯多了就像了」推不出任何误差数值。

## 形式化

sin 与 cos 在 $a=0$ 的泰勒级数：

$\sin x = x - \frac{x^3}{3!} + \frac{x^5}{5!} - \cdots$（奇数阶）

$\cos x = 1 - \frac{x^2}{2!} + \frac{x^4}{4!} - \cdots$（偶数阶）

$n$ 阶截断记 $T_n$。项递减时交错级数余项界：$|f(x_0) - T_n(x_0)| \le \frac{|x_0|^{n+2}}{(n+2)!}$。

教学参数（本样三个产件共用，全部声明）：

| 参数 | 值 |
|---|---|
| 中心点 $a$ | 0（Maclaurin） |
| 展开阶数 | sin：1, 3, 5, 7, 9；cos：0, 2, 4, 6, 8 |
| 评估点 $x_0$ | $\pi/2$（双精度 1.5707963267948966） |
| 定义域 | $[-\pi, \pi]$ |
| 误差基准 | numpy 双精度：$\sin(x_0)=1.0$，$\cos(x_0)=6.123233995736766 \times 10^{-17}$ |
| 数值格式 | `%.4e`（图内标注 / 动画状态行 / 下表同源同格式） |

截断误差表（图 3 与动画状态行的数据源）：

| $f$ | 阶数 $n$ | $T_n(x_0)$ | 误差 $|f(x_0)-T_n(x_0)|$ |
|---|---|---|---|
| sin | 1 | 1.5708e+00 | 5.7080e-01 |
| sin | 3 | 9.2483e-01 | 7.5168e-02 |
| sin | 5 | 1.0045e+00 | 4.5249e-03 |
| sin | 7 | 9.9984e-01 | 1.5690e-04 |
| sin | 9 | 1.0000e+00 | 3.5426e-06 |
| cos | 0 | 1.0000e+00 | 1.0000e+00 |
| cos | 2 | -2.3370e-01 | 2.3370e-01 |
| cos | 4 | 1.9969e-02 | 1.9969e-02 |
| cos | 6 | -8.9452e-04 | 8.9452e-04 |
| cos | 8 | 2.4737e-05 | 2.4737e-05 |

## 图解

图 1：过程动画（MP4 文件交付，motion-toolkit `scripts\mpl_anim.py` 封装出片；240 帧 @20fps = 12.0s，1320×506，h264 yuv420p）：

`~/.nebflow/plots/vrs2-math/taylor_sin_cos_approx.mp4`

[▶ 播放过程动画 taylor_sin_cos_approx.mp4](~/.nebflow/plots/vrs2-math/taylor_sin_cos_approx.mp4)

分步说明（与动画五个阶段一一对应，底部状态行实时显示当前阶与误差）：

1. ① $k=1$（sin 1 阶 / cos 0 阶）：sin 是过原点直线、cos 是水平线，误差 5.7080e-01 / 1.0000e+00；
2. ② $k=2$（3 / 2 阶）：sin 出现 S 形、cos 弯成抛物线状，误差 7.5168e-02 / 2.3370e-01；
3. ③ $k=3$（5 / 4 阶）：中部区间已贴合，误差 4.5249e-03 / 1.9969e-02；
4. ④ $k=4$（7 / 6 阶）：仅端点可见偏离，误差 1.5690e-04 / 8.9452e-04；
5. ⑤ $k=5$（9 / 8 阶）：部分和与虚线真值肉眼不可分，误差 3.5426e-06 / 2.4737e-05。

图 2：manim 公式对照段（MP4，854×480 @15fps，12.2s。本机无 LaTeX，MathTex 实测不可用，公式经 Pango `MarkupText`/`Text` 渲染）：

`~/.nebflow/plots/vrs2-math/taylor_formula_manim.mp4`

[▶ 播放公式对照段 taylor_formula_manim.mp4](~/.nebflow/plots/vrs2-math/taylor_formula_manim.mp4)

说明：公式行 $\sin x = x - x^3/3! + x^5/5! - x^7/7! + x^9/9! - \cdots$ 逐项点亮，底部误差数字逐阶缩小，与图 1 的五个阶段一一对应——「公式推进」与「几何逼近」是同一件事的两面。

图 3：静态收敛误差图（对数轴，1290×810）：

![泰勒级数在 x0=pi/2 处的逐阶截断误差](~/.nebflow/plots/vrs2-math/taylor_error_logscale.png)

读图要点：两条误差曲线在对数轴上近似直线 = 指数式塌缩；细虚线/点线为「下一项上界」$x_0^{n+2}/(n+2)!$（sin）与 $x_0^{n+1}/(n+1)!$（cos），每条实测误差都压在上界之下；cos 的 $n=0$ 点误差恰为 1.0000e+00（$T_0=1$，真值 $\approx 0$）。

复算命令（sin 部分，输出与上表逐字一致；cos 把 $(k+1)$ 换成 $k$、阶数取 0,2,4,6,8、基准换 $\cos$）：

```python
import math
x0 = math.pi / 2
for n in (1, 3, 5, 7, 9):
    T = sum((-1)**(k//2) * x0**(k+1) / math.factorial(k+1) for k in range(0, n+1, 2))
    print(n, f"{T:.4e}", f"{abs(1.0 - T):.4e}")
```

## 常见误区

| 常见误区 | 实际情况 |
|---|---|
| 「阶数越高部分和一定越接近真值」 | 只在项递减时单调改善。$x=\pi$ 处 cos 低阶部分和先变差：$T_2$ 误差 2.9348e+00 比 $T_0$ 的 2.0000e+00 更大，到 $T_6$ 才降到 2.1135e-01 |
| 「下一项上界公式随时能用」 | 交错级数余项界要求项递减。$x=0.5$、$n=3$ 时成立（实测 2.5887e-04 ≤ 上界 2.6042e-04）；$x$ 大时低阶情形该界失效（见上条） |
| 「中心点选哪都一样」 | 同阶误差可差数量级：本样 $a=0$ 在 $x_0=\pi/2$ 要 9 阶才进 1e-5；评估点离中心越远，同阶误差越大 |
| 「对数轴上的直线是匀速下降」 | 对数轴等距 = 一个数量级；直线意味着每加两阶误差乘约固定倍数（$\times x_0^2/((n+3)(n+4))$ 量级），不是线性下降 |

## 练习

1. 用 3 阶泰勒多项式估计 $\sin 0.5$，给出实际误差并与下一项上界比较。
2. 沿用本样参数（$a=0$，$x_0=\pi/2$）：cos 的误差要进 $10^{-3}$，至少需要几阶？
3. 同样 $x_0=\pi/2$：sin 的误差要进 $10^{-4}$，至少需要几阶？

### 答案

1. $T_3(0.5) = 0.5 - 0.5^3/3! = 0.4791666666666667$；$\sin 0.5 = 0.479425538604203$；实际误差 2.5887e-04 ≤ 下一项上界 $0.5^5/5!$ = 2.6042e-04，界成立 ✓。
2. 查误差表：$n=4$ 误差 1.9969e-02 不够；$n=6$ 误差 8.9452e-04 $< 10^{-3}$ ⇒ 至少 6 阶。
3. $n=7$ 误差 1.5690e-04 $> 10^{-4}$ 不够；$n=9$ 误差 3.5426e-06 ⇒ 至少 9 阶。

## 产出与验证

| 产件 | 绝对路径 | 规格（ffprobe/脚本实测） |
|---|---|---|
| 过程动画 MP4 | `C:\Users\Kai\.nebflow\plots\vrs2-math\taylor_sin_cos_approx.mp4` | 12.00s / 1320×506 / h264 yuv420p / 20fps / 240 帧；motion-toolkit `mpl_anim.py` 封装出片 |
| manim 公式对照 MP4 | `C:\Users\Kai\.nebflow\plots\vrs2-math\taylor_formula_manim.mp4` | 12.20s / 854×480 / h264 yuv420p / 15fps；manim 0.21.0 `-ql` |
| 静态误差图 PNG | `C:\Users\Kai\.nebflow\plots\vrs2-math\taylor_error_logscale.png` | 1290×810（8.6×5.4 in @150dpi），对数轴 |

渲染脚本（`C:\Users\Kai\.nebflow\skills\visual-report-suite\mathematics\`）：`taylor_anim.py`（motion-toolkit 用户脚本，`build(fig, ax)` 契约）、`manim_taylor.py`、`taylor_error_static.py`。

manim 终判：**可行**（0.21.0，`-ql` 渲染 exit 0、产物合法 mp4）；但 **MathTex/Tex 不可用**——本机无 LaTeX 发行版，实测 `FileNotFoundError: [WinError 2]`；本样公式镜头走 `Text`/`MarkupText`（Pango）路线，已实测出片。

链接验证记录（`curl -sIL` 读末位状态行，2026-10-04 实测）：

| 链接 | 状态码 |
|---|---|
| https://matplotlib.org/ | 200 |
| https://docs.manim.community/en/stable/ | 200 |
| https://pypi.org/project/manim/ | 200 |
| https://pypi.org/project/imageio-ffmpeg/ | 200 |

基础工具（numpy/scipy/graphviz 等）链接验证见套件 `../README.md` 全工具链接验证表（同日实测，直接引用不复制）。
