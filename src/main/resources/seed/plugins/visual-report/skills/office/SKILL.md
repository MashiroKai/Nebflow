---
name: office
description: 周报/汇报/会议纪要（结论先行、表格为主）；办公报告任务使用。
---

# office — 办公报告（周报 / 汇报 / 会议纪要）

七个场景之一。铁律、图片嵌入路径、dark-mode、渲染器兼容、缺装降级总则一律见 `../core/SKILL.md`，本文只写 office 场景增量；工具链现状与全量链接验证见套件 `../README.md`。

## 1. 触发条件

- 用户要写**周报 / 周总结 / weekly report**，或**进展汇报 / 项目汇报 / 述职材料**：产出周报/汇报件，用 §2.1 骨架。
- 用户要整理**会议纪要 / meeting notes / 评审结论**：产出纪要，用 §2.2 骨架。
- 判据：读者是同事或上级，内容围绕「做了什么、什么状态、谁接下来做什么」即命中本场景。学术论文、技术方案、教学材料等分别归对应场景 skill，不套用本文。

## 2. 报告骨架模板

### 2.1 周报 / 汇报（结论先行，四段固定顺序）

```markdown
# <项目/团队>周报 · <周期>

## 本周结论

（≤5 行，第一句就是本周最重要的一句话；只讲结果与程度，不堆过程流水账）

## 关键数据

（表格为主：任务/指标 | owner | 进度 | 状态；需图时 ≤1 张简洁单图，见 §3）

## 风险与求助

（每条：风险/阻塞 + 影响 + 需要谁在何时做什么；没有就写「无」）

## 下周计划

（表：待办 | owner | 期限）
```

### 2.2 会议纪要（决议 / 待办 / owner / 期限）

```markdown
# <会议名>纪要 · <日期>

## 决议

（逐条编号，每条一句话可执行；有分歧的写明结论依据）

## 待办

（表：待办 | owner | 期限 | 备注；owner 缺位或无期限的项升入「风险与求助」，不留在表里）

## 讨论要点（可选）

（只记影响决议的分歧与依据，放最后，不记流水账）
```

## 3. 工具链与自检

- **以表格为主、图表为辅**：office 数据量小（个位数任务/指标），表格即可逐字传达；仅当「对比 / 进度 / 趋势」一图胜十表时出图，且**单份报告 ≤1 张简洁单图**（core 硬上限 3 张，本场景自律收紧到 1）。
- 需图时用 viz-venv matplotlib（唯一绘图环境）出横向条形图 / 折线图，落 `~/.nebflow/plots/`，嵌入用 `~/...` 绝对路径。出图命令**必须带** `MPLCONFIGDIR=C:\Users\Kai\.nebflow\tools\mplconfig`（不设则回退默认 rc，中文变豆腐块——2026-10-04 实测踩坑）。配色用 core §3 常量：数据色强调 `#5e6ad2`；文字/轴线用 `#3d4350`（纯黑禁用，且中性灰 `#8a8f98` 在白底仅 3.2:1 不达标）。
- **降级策略（合法出口）**：本场景天然可**零图纯 markdown** 交付——工具缺失、图无增益或读者只要结论时，直接表格 + 文字，不空转、不因图返工、更不手画 SVG。流程/架构类配图需求不属于本场景（归 tech-architecture / engineering）。
- 全工具全量自检与链接验证见套件 `../README.md`「全工具链接验证表」（2026-10-04 实测：通过 19 / 失效 3 均已补验；超 30 天引用须复测）。office 场景直接使用的工具逐条实测如下。

### 工具自检

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| viz-venv Python | 唯一绘图环境 | `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts\python.exe -V` → Python 3.12.9（2026-10-04 实测） | https://www.python.org/downloads/ | 官网未单项复测；包经 pip 清华源装回 viz-venv | 纯 markdown 交付（本场景合法出口） |
| matplotlib | 进度/趋势简洁单图 | `...\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2（2026-10-04 实测） | https://matplotlib.org/ | HTTP 200（2026-10-04，README 表） | 纯表格 + 文字，不手画 SVG |
| YaHei 中文字体配置 | 图内中文可读 | 出图命令前缀 `MPLCONFIGDIR=C:\Users\Kai\.nebflow\tools\mplconfig`（本机实测生效，2026-10-04） | 配置文件：`C:\Users\Kai\.nebflow\tools\mplconfig\matplotlibrc`（本机配置，无下载页） | 不适用（本地文件） | 图内改英文标注或放弃出图 |

独立 SVG 被 `<img>` 引用时必须内嵌暗色 media query（core §3）。matplotlib 把颜色写进内联 `style` 属性，普通 CSS 压不过，须子串选择器 + `!important`，且 fill / stroke 分开覆写（避免给文字字形强加描边）：

```python
# 出图时：文字/轴线/刻度全设 #3d4350，数据色 #5e6ad2；savefig(transparent=True)
# 落盘后在 <svg> 开标签后注入：
DARK = '''<style>
@media (prefers-color-scheme: dark) {
  [style*="fill: #3d4350"] { fill: #c9cdd6 !important; }
  [style*="stroke: #3d4350"] { stroke: #8a8f98 !important; }
  [style*="fill: #5e6ad2"] { fill: #a0a8e8 !important; }
  [style*="stroke: #5e6ad2"] { stroke: #a0a8e8 !important; }
}
</style>'''
```

## 4. 质量红线

1. **结论先行不堆过程**：第一段必须给结论；过程叙事、会议流水账不进正文。
2. **数据可追溯**：正文与图内数值逐字来自可查来源（看板 / 日志 / 台账），并标注来源与口径；拿不到来源的数不写不画；示例 / 样例数据必须显式标注「演示样例」。
3. **待办必须有 owner 与期限**：缺任一项的待办不得进计划表，升入「风险与求助」并写明卡点。
4. **禁止手画 SVG**（core 铁律 1）：缺工具走 §3 降级——零图交付是合法出口，手画不是。

## 5. 示例

- `../demos/office.md` —— 周报样张：结论先行四段 + 1 张进度条形图（viz-venv matplotlib 真实生成）+ 3 张规范表格，数据已标注为演示样例。
