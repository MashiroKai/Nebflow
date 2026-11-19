# manim 公式动画对照段：泰勒级数逐阶逼近 sin x（公式视角）
# 本机无 LaTeX（MathTex 实测 FileNotFoundError），故用 MarkupText/Text（Pango 渲染）。
# 教学参数与主片/静态图一致：中心点 a=0，x0=π/2，项数 1..5（阶数 1,3,5,7,9），
# 误差数值与 taylor_error_static.py 输出逐字一致。
from manim import *

ACCENT = "#a0a8e8"   # 暗底强调（core 调色板暗色 accent）
WARM = "#d8a25e"
BASE = "#e8eaed"
MUTED = "#8a8f98"

ERRS = ["5.7080e-01", "7.5168e-02", "4.5249e-03", "1.5690e-04", "3.5426e-06"]
TERMS = ["x", "−x³/3!", "+x⁵/5!", "−x⁷/7!", "+x⁹/9!"]
SIGNS = ["−", "+", "−", "+"]

class TaylorFormula(Scene):
    def construct(self):
        title = Text("泰勒级数逐阶逼近 sin x（公式视角）", font="Microsoft YaHei",
                     font_size=30, color=BASE).to_edge(UP, buff=0.4)

        head = Text("sin x =", font="Microsoft YaHei", font_size=34, color=BASE)
        pieces = [head] + [
            Text(t, font="Microsoft YaHei", font_size=34,
                 color=ACCENT if i % 2 == 0 else WARM)
            for i, t in enumerate(TERMS)
        ]
        dots = Text("− ⋯", font="Microsoft YaHei", font_size=34, color=MUTED)
        row = VGroup(*pieces, dots).arrange(RIGHT, buff=0.24).move_to(UP * 0.7)

        cap = Text("中心点 a = 0　·　评估点 x₀ = π/2　·　对照：数值过程动画（matplotlib 主片）",
                   font="Microsoft YaHei", font_size=18, color=MUTED).to_edge(DOWN, buff=0.35)

        self.play(FadeIn(title), run_time=0.8)
        self.play(Write(head), run_time=0.8)
        self.play(LaggedStart(*[FadeIn(p, shift=RIGHT * 0.3) for p in pieces[1:] + [dots]],
                              lag_ratio=0.22), run_time=2.2)
        self.play(FadeIn(cap), run_time=0.6)

        # 逐项高亮 ↔ x0=π/2 处误差逐阶缩小（数值与静态图/主片逐字一致）
        # 标签排布：先按实际宽度 arrange 成整行，再与说明文字拼行居中——不重叠、不出框
        tags = VGroup(*[
            Text(e, font="Consolas", font_size=20, color=ACCENT) for e in ERRS
        ]).arrange(RIGHT, buff=0.28)
        err_label = Text("x₀=π/2 截断误差：", font="Microsoft YaHei",
                         font_size=22, color=BASE)
        line = VGroup(err_label, tags).arrange(RIGHT, buff=0.3).to_edge(DOWN, buff=1.05)
        self.play(FadeIn(err_label), run_time=0.5)
        prev = None
        for i, (p, tag) in enumerate(zip(pieces[1:], tags)):
            anims = [Indicate(p, color=YELLOW, scale_factor=1.25), FadeIn(tag, scale=0.7)]
            if prev is not None:
                anims.append(prev.animate.set_opacity(0.35))
            self.play(*anims, run_time=1.1)
            prev = tag
        self.wait(1.6)
