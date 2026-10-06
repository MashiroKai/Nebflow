# -*- coding: utf-8 -*-
"""finance 场景 demo 图表生成 —— mplfinance 真画 K线 + 成交量 + MA20。

数据：固定种子（seed=42）程序合成的 60 个交易日 OHLCV（几何随机游走 + 波动率放量），
     图内 title 与角标两处显著声明「模拟数据，非真实行情」。
配色：A股惯例红涨绿跌（图注显式写明约定）。
输出：SVG → plots/vrs2-netfin/（暗色 media query 注入）；亮/暗 PNG → tmp 目检。
"""
import os
os.environ.setdefault('MPLCONFIGDIR', r'C:\Users\Kai\.nebflow\tools\mplconfig')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
import numpy as np
import pandas as pd
import mplfinance as mpf

SEED = 42
N = 60
UP, DOWN, MA_C = '#d64541', '#2e9e5b', '#5e6ad2'   # 红涨绿跌 + MA 线强调色（两底可读）
OUT = r'C:\Users\Kai\.nebflow\plots\vrs2-netfin'
TMP = r'C:\Users\Kai\.nebflow\projects\general\.nebflow\tmp'
os.makedirs(OUT, exist_ok=True)
STAMP = '20261004_231500'
SVG_NAME = f'{STAMP}_fin_ohlcv_ma20.svg'

# ---------- 合成 OHLCV（固定种子可复现） ----------
rng = np.random.default_rng(SEED)
dates = pd.bdate_range('2026-06-01', periods=N)
ret = rng.normal(0.0008, 0.018, N)                       # 对数收益 N(0.0008, 0.018)
close = 100.0 * np.exp(np.cumsum(ret))
gap = rng.normal(0.0, 0.004, N)                          # 开盘跳空
open_ = np.empty(N)
open_[0] = 100.0
open_[1:] = close[:-1] * np.exp(gap[1:])
rng_hi = np.abs(rng.normal(0.0, 0.008, N))
rng_lo = np.abs(rng.normal(0.0, 0.008, N))
high = np.maximum(open_, close) * np.exp(rng_hi)
low = np.minimum(open_, close) * np.exp(-rng_lo)
volume = (2_000_000 * np.exp(rng.normal(0.0, 0.35, N)) * (1 + 25 * np.abs(ret))).round(-3)

df = pd.DataFrame({'Open': open_, 'High': high, 'Low': low, 'Close': close,
                   'Volume': volume}, index=dates)
# OHLC 合法性断言：高低必须包住开收；量必须为正
assert bool((df['High'] >= df[['Open', 'Close']].max(axis=1)).all())
assert bool((df['Low'] <= df[['Open', 'Close']].min(axis=1)).all())
assert bool((df['Volume'] > 0).all())

ma20 = df['Close'].rolling(20).mean()

hi_px, hi_day = float(df['High'].max()), df['High'].idxmax().strftime('%Y-%m-%d')
lo_px, lo_day = float(df['Low'].min()), df['Low'].idxmin().strftime('%Y-%m-%d')
chg = close[-1] / close[0] - 1
ann_vol = float(np.std(ret, ddof=1) * np.sqrt(252))
up_days = int((df['Close'] > df['Open']).sum())
down_days = int((df['Close'] < df['Open']).sum())
avg_vol = float(df['Volume'].mean())
vol_peak_day = df['Volume'].idxmax().strftime('%Y-%m-%d')
vol_peak = float(df['Volume'].max())

print('===== DEMO-VALUES（以下字符串逐字用于 demo 文档与图内标注） =====')
print(f'[数据] 合成区间 {dates[0].strftime("%Y-%m-%d")} ~ {dates[-1].strftime("%Y-%m-%d")}，{N} 个交易日，seed={SEED}')
print(f'[数据] 模型：对数收益 N(0.0008, 0.018)，开盘跳空 N(0, 0.004)，高低影线 |N(0, 0.008)|，成交量 200万·e^N(0,0.35)·(1+25|ret|)')
print(f'[行情] 期初收盘 {close[0]:.2f} → 期末收盘 {close[-1]:.2f}，区间涨跌 {chg:+.2%}')
print(f'[行情] 区间最高 {hi_px:.2f}（{hi_day}），区间最低 {lo_px:.2f}（{lo_day}）')
print(f'[指标] 末日 MA20 = {ma20.iloc[-1]:.2f}；MA20 最后 5 日 = ' + ' '.join(f'{v:.2f}' for v in ma20.iloc[-5:]))
print(f'[形态] 阳线 {up_days} 根 / 阴线 {down_days} 根；年化波动率 {ann_vol:.2%}')
print(f'[量能] 日均量 {avg_vol:.0f}；峰值量 {vol_peak:.0f}（{vol_peak_day}）')


def draw(dark: bool, path: str) -> None:
    """亮 / 暗双份渲染；暗份色值与注入 SVG 的 dark CSS 一一对应，供目检。"""
    if dark:
        face, figc, fg, grid, edge = '#1b1e26', '#14161c', '#e8eaed', '#33384a', '#4a5164'
    else:
        face, figc, fg, grid, edge = '#ffffff', '#ffffff', '#1b1e26', '#d9dce3', '#1b1e26'
    mc = mpf.make_marketcolors(up=UP, down=DOWN,
                                edge={'up': UP, 'down': DOWN},
                                wick={'up': UP, 'down': DOWN},
                                volume={'up': UP, 'down': DOWN})
    s = mpf.make_mpf_style(base_mpf_style='classic', marketcolors=mc, mavcolors=[MA_C],
                           facecolor=face, figcolor=figc, edgecolor=edge,
                           gridcolor=grid, gridstyle='--', gridaxis='both',
                           rc={'font.sans-serif': ['Microsoft YaHei', 'SimHei', 'sans-serif'],
                               'axes.unicode_minus': False,
                               'text.color': fg, 'axes.labelcolor': fg,
                               'xtick.color': fg, 'ytick.color': fg,
                               'axes.edgecolor': edge, 'svg.fonttype': 'none',
                               'font.size': 11})
    fig, axlist = mpf.plot(df, type='candle', volume=True, mav=20, style=s, returnfig=True,
                           figsize=(10.8, 6.6), tight_layout=True,
                           datetime_format='%m-%d', xrotation=0,
                           title='合成 OHLC 演示：K线 + 成交量 + MA20（模拟数据，非真实行情）',
                           ylabel='价格（模拟）', ylabel_lower='成交量（股）')
    axlist[0].legend(handles=[Line2D([0], [0], color=MA_C, lw=2.4,
                                     label='MA20（20日简单移动平均）')],
                     loc='upper left', frameon=False, labelcolor=fg, fontsize=10)
    fig.text(0.5, 0.012,
             '声明：本图全部行情为 seed=42 程序合成之模拟数据，非真实行情，不构成任何投资参考',
             ha='center', fontsize=9.5, color='#8a8f98')
    fig.savefig(path, facecolor=figc)
    plt.close(fig)


draw(False, os.path.join(TMP, 'fin_ohlcv_light_check.png'))
draw(True, os.path.join(TMP, 'fin_ohlcv_dark_check.png'))
draw(False, os.path.join(OUT, SVG_NAME))

DARK_CSS = ('<style type="text/css">/* vrs2-netfin dark */\n'
            '@media (prefers-color-scheme: dark) {\n'
            '  path[style*="fill: #ffffff"] { fill: #1b1e26 !important; }\n'
            '  rect[style*="fill: #ffffff"] { fill: #1b1e26 !important; }\n'
            '  text { fill: #e8eaed !important; }\n'
            '  path[style*="stroke: #1b1e26"] { stroke: #4a5164 !important; }\n'
            '  use[style*="stroke: #1b1e26"] { stroke: #4a5164 !important; }\n'
            '  path[style*="fill: #1b1e26"] { fill: #e8eaed !important; }\n'
            '  use[style*="fill: #1b1e26"] { fill: #e8eaed !important; }\n'
            '  path[style*="stroke: #d9dce3"], path[style*="stroke: #e8eaed"] '
            '{ stroke: #33384a !important; }\n'
            '}\n</style>')

svg_path = os.path.join(OUT, SVG_NAME)
svg = open(svg_path, encoding='utf-8').read()
assert '</svg>' in svg and '@media' not in svg
open(svg_path, 'w', encoding='utf-8').write(svg.replace('</svg>', DARK_CSS + '</svg>', 1))

print('===== 产件 =====')
p = os.path.join(OUT, SVG_NAME)
print(p, os.path.getsize(p), 'bytes')
