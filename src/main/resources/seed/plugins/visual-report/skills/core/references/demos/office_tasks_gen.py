"""Source: office.md §关键数据 task/owner/progress table; all values are demo data."""
import sys
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
fm.fontManager.addfont('/System/Library/Fonts/STHeiti Light.ttc')
plt.rcParams['font.family'] = fm.FontProperties(fname='/System/Library/Fonts/STHeiti Light.ttc').get_name()
tasks=['报表自动化 v1.0（张三）','数据接口联调（李四）','权限模块改造（王五）','文档站迁移（赵六）','性能压测（张三）']
values=[100,80,60,40,20]
fig, ax = plt.subplots(figsize=(9, 5), dpi=160)
bars=ax.barh(tasks[::-1], values[::-1], color='#5e6ad2')
ax.bar_label(bars, labels=[f'{v}%' for v in values[::-1]], padding=4)
ax.set(xlim=(0, 110), xlabel='完成度（%）', title='本周任务进度（演示样例数据）')
ax.grid(axis='x', alpha=.2); ax.set_axisbelow(True); fig.tight_layout(); fig.savefig(sys.argv[1]); plt.close(fig)
