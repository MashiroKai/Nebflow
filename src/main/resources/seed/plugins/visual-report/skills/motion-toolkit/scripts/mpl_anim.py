#!/usr/bin/env python3
"""motion-toolkit · matplotlib FuncAnimation 渲染封装（降级栈，Remotion 不可用时顶上）

MP4 走 imageio-ffmpeg 自带 exe（本机 PATH 无 ffmpeg，见 2026-10-04 实测）；GIF 走
PillowWriter（全程不碰 ffmpeg，最终降级位）。一律用 viz-venv python 运行。

用户脚本约定（--script 指向的 .py 必须定义且仅依赖 viz-venv 已装包）:

    def build(fig, ax):
        '''返回 (update, n_frames)；update(frame) 画第 frame 帧。'''
        def update(frame): ...
        return update, 60

用法:
  <venv>/python.exe mpl_anim.py --script mpl_examples/demo_sine.py --out out.gif  --fps 15 --dpi 110
  <venv>/python.exe mpl_anim.py --script mpl_examples/demo_sine.py --out out.mp4  --fps 30 --dpi 110
  <venv>/python.exe mpl_anim.py --script my_anim.py --out out.mp4 --figsize 8,4.5
"""
import argparse
import importlib.util
import os
import sys
from pathlib import Path

# 套件全局 matplotlibrc（YaHei 中文字体）只在 MPLCONFIGDIR 指向它时生效；
# 节点 shell 常不带该环境变量（2026-10-04 实测为空）→ 在 import matplotlib 前补接线。
# setdefault：外部已设 MPLCONFIGDIR 时尊重外部值。
_SUITE_MPLCONFIG = Path(r"C:\Users\Kai\.nebflow\tools\mplconfig")
if (_SUITE_MPLCONFIG / "matplotlibrc").exists():
    os.environ.setdefault("MPLCONFIGDIR", str(_SUITE_MPLCONFIG))


def load_user(script: Path):
    spec = importlib.util.spec_from_file_location("user_anim", script)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    if not hasattr(mod, "build"):
        sys.exit("用户脚本必须定义 build(fig, ax) -> (update, n_frames)")
    return mod


def main() -> None:
    ap = argparse.ArgumentParser(description="matplotlib FuncAnimation → MP4/GIF")
    ap.add_argument("--script", required=True, help="用户动画脚本（定义 build(fig, ax)）")
    ap.add_argument("--out", required=True, help="输出路径，后缀 .gif 或 .mp4")
    ap.add_argument("--fps", type=int, default=15)
    ap.add_argument("--dpi", type=int, default=110)
    ap.add_argument("--figsize", default="8,4.5")
    a = ap.parse_args()

    out = Path(a.out)
    if out.suffix.lower() not in (".gif", ".mp4"):
        sys.exit("输出后缀仅支持 .gif / .mp4")
    out.parent.mkdir(parents=True, exist_ok=True)
    figsize = tuple(float(x) for x in a.figsize.split(","))

    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.animation import FFMpegWriter, FuncAnimation, PillowWriter

    fig, ax = plt.subplots(figsize=figsize, dpi=a.dpi)
    user = load_user(Path(a.script))
    update, n_frames = user.build(fig, ax)
    anim = FuncAnimation(fig, update, frames=n_frames, interval=1000 / a.fps, blit=False)

    if out.suffix.lower() == ".gif":
        anim.save(str(out), writer=PillowWriter(fps=a.fps))
        print(f"[mpl_anim] GIF 完成: {out}（PillowWriter，未触碰 ffmpeg）")
    else:
        import imageio_ffmpeg

        ff = Path(imageio_ffmpeg.get_ffmpeg_exe())
        # 本机 PATH 无 ffmpeg：必须显式指到 imageio-ffmpeg 自带 exe，否则 WinError 2
        matplotlib.rcParams["animation.ffmpeg_path"] = str(ff)
        writer = FFMpegWriter(
            fps=a.fps,
            codec="libx264",
            bitrate=-1,
            metadata={"title": out.stem, "artist": "motion-toolkit"},
            # yuv420p 要求偶数宽高：scale 滤镜统一裁到偶数
            extra_args=["-pix_fmt", "yuv420p", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2"],
        )
        anim.save(str(out), writer=writer)
        print(f"[mpl_anim] MP4 完成: {out}")
        print(f"[mpl_anim] ffmpeg = {ff}（imageio_ffmpeg 自带 exe；本机 PATH 无 ffmpeg）")

    plt.close(fig)


if __name__ == "__main__":
    main()
