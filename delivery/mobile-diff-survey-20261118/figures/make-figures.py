#!/usr/bin/env python3
# make-figures.py — figures for the mobile diff-view survey report.
# Every number plotted is MEASURED (playwright / esbuild readings in
# /Users/kaiyu/stand-evidence/mobile-diff-survey-20261118/). No estimates.
import json, os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

EVID = "/Users/kaiyu/stand-evidence/mobile-diff-survey-20261118"
OUT = "/Users/kaiyu/.stand/projects/stand/.stand/docs/assets/mobile-diff-survey-20261118"
os.makedirs(OUT, exist_ok=True)

cur = json.load(open(f"{EVID}/current/measurements.json"))
cand = json.load(open(f"{EVID}/candidates/measurements-candidates.json"))
bund = json.load(open(f"{EVID}/../mobile-diff-survey-20261118/meta/bundle.json")) if os.path.exists(f"{EVID}/../mobile-diff-survey-20261118/meta/bundle.json") else json.load(open("/private/tmp/stand-mobile-diff-survey-20261118/delivery/mobile-diff-survey-20261118/prototypes/measurements-bundle.json"))

from matplotlib import font_manager
_have = {f.name for f in font_manager.fontManager.ttflist}
for _cjk in ("PingFang SC", "Hiragino Sans GB", "Songti SC", "Arial Unicode MS", "Heiti SC"):
    if _cjk in _have:
        plt.rcParams["font.family"] = [_cjk, "DejaVu Sans"]
        break
plt.rcParams["axes.unicode_minus"] = False

plt.rcParams.update({"font.size": 9, "axes.grid": True, "grid.alpha": 0.25,
                     "figure.facecolor": "#0e1116", "axes.facecolor": "#0e1116",
                     "axes.edgecolor": "#3a4150", "text.color": "#e8e8ea",
                     "axes.labelcolor": "#e8e8ea", "xtick.color": "#c9ccd4", "ytick.color": "#c9ccd4",
                     "grid.color": "#3a4150"})

# ── Figure 1: measured contrast of the current diff token colours vs WCAG AA ──
fig, ax = plt.subplots(figsize=(7.2, 3.4), dpi=200)
groups = ["delegate dark", "delegate light", "ask dark", "ask light"]
rows = ["addContrast", "delContrast", "ctxContrast"]
labels = ["+ added line", "− deleted line", "unchanged (ctx)"]
colors = ["#4caf50", "#f26b6b", "#8b8e96"]
import numpy as np
x = np.arange(len(groups)); w = 0.26
for i, (r, lab, c) in enumerate(zip(rows, labels, colors)):
    vals = [cur["faces"][g.replace(" ", "-")][r]["ratio"] for g in groups]
    b = ax.bar(x + (i - 1) * w, vals, w, label=lab, color=c, edgecolor="#0e1116", linewidth=0.6)
    ax.bar_label(b, fmt="%.2f", fontsize=7, padding=1, color="#e8e8ea")
ax.axhline(4.5, color="#d0342c", lw=1.4, ls="--")
ax.text(3.42, 4.62, "WCAG AA 4.5:1", color="#d0342c", fontsize=8, ha="right")
ax.set_ylim(0, 6.4); ax.set_ylabel("contrast ratio (measured)")
ax.set_title("图1  现状两面 diff 配色的实测对比度（375×812 真机形态，暗/亮各一组）", fontsize=10)
ax.set_xticks(x); ax.set_xticklabels(groups); ax.legend(frameon=False, fontsize=8, loc="upper left")
fig.tight_layout(); fig.savefig(f"{OUT}/fig1-contrast.png"); plt.close(fig)
print("fig1 ok")

# ── Figure 2: measured min+gzip bundle of each candidate ─────────────────────
res = bund["results"]
names = ["diff2html", "jsdiff (compute only)", "codemirror-merge-min", "codemirror-merge",
         "git-diff-view-core", "shiki", "pierre-diffs"]
keymap = {"diff2html": "diff2html", "jsdiff (compute only)": "jsdiff", "codemirror-merge-min": "codemirror-merge-min",
          "codemirror-merge": "codemirror-merge", "git-diff-view-core": "git-diff-view-core",
          "shiki": "shiki", "pierre-diffs": "pierre-diffs"}
vals = [res[keymap[n]]["gzipKB"] for n in names]
fig, ax = plt.subplots(figsize=(7.2, 3.2), dpi=200)
bar_colors = ["#12874a" if v < 100 else ("#5e6ad2" if v < 400 else "#d0342c") for v in vals]
logvals = [max(v, 0.05) for v in vals]
b = ax.barh(names, logvals, color=bar_colors, edgecolor="#0e1116", linewidth=0.6)
ax.bar_label(b, labels=[f"{v} KB" for v in vals], fontsize=8, padding=2, color="#e8e8ea")
ax.set_xscale("log"); ax.set_xlabel("min+gzip bundle size (KB, log scale, measured by esbuild)")
ax.set_title("图2  候选渲染件的实测打包体积（最小用法入口，含其全部传递依赖）", fontsize=10)
ax.invert_yaxis()
fig.tight_layout(); fig.savefig(f"{OUT}/fig2-bundle.png"); plt.close(fig)
print("fig2 ok")

# ── Figure 3: measured render cost on the real payloads ──────────────────────
fig, ax = plt.subplots(figsize=(7.2, 3.0), dpi=200)
cases = ["current\ndelegate 651 lines", "current\nask 1380 lines", "CodeMirror\n(mount 75+32 lines)", "diff2html\n(mount 75+32 lines)"]
ms = [cur["faces"]["delegate-large-dark"]["fullMountMs"],
      cur["faces"]["ask-large-dark"]["totalMs"],
      cand["candidates"]["a-codemirror-dark"]["reported"]["mountMs"],
      cand["candidates"]["b-diff2html-dark"]["reported"]["mountMs"]]
b = ax.bar(cases, ms, color=["#d0342c", "#12874a", "#5e6ad2", "#5e6ad2"], edgecolor="#0e1116", linewidth=0.6)
ax.bar_label(b, fmt="%.0f ms", fontsize=8, padding=2, color="#e8e8ea")
ax.set_ylabel("measured wall time (ms)")
ax.set_title("图3  同一台机器上的实测出图耗时（现状两面 vs 两候选；payload 不同，见标注）", fontsize=10)
ax.tick_params(axis="x", labelsize=8)
fig.tight_layout(); fig.savefig(f"{OUT}/fig3-timing.png"); plt.close(fig)
print("fig3 ok")
