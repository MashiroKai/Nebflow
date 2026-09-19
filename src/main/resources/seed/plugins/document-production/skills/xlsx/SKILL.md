---
name: xlsx
description: "电子表格制作与分析：以 .xlsx / .xlsm / .csv / .tsv 为主要输入或输出的任务——新建表格、读取与编辑既有工作簿、数据分析出表、带图表交付、表格格式互转、清洗 / 合并 / 透视 / 转换。用于做 Excel 表格、写报表与数据模型、把数据整理成可交付的工作簿等任务。"
---

# XLSX — Scene-Driven Spreadsheet Workbench

## Environment Setup

**Quick check** — run this first. If it exits 0, skip to [Pre-Flight](#pre-flight-intent-gate):

```bash
source "${SKILL_DIR}/env_setup/env_check.sh"
```

This auto-detects and exports `SKILL_DIR` and `FONT_DIR`. No manual variable setup needed.

**Only if the check fails**, read [`env_setup/setup.md`](env_setup/setup.md) for full platform-specific installation instructions (dependencies, fonts, China mirrors).

| Variable | Auto-set by env_check.sh | Description |
|----------|--------------------------|-------------|
| `SKILL_DIR` | skill root directory | Parent of this file |
| `FONT_DIR` | macOS: `~/Library/Fonts`, Linux: `/usr/share/fonts` | Font base directory |

> **Local-font-first.** Inspect fonts available in the user's local environment and prefer a suitable
> installed font. Use bundled or downloaded fonts only as fallbacks; do not install fonts without the
> user's confirmation.

---
## Pre-Flight: Intent Gate

Before touching any code, confirm the user actually needs a spreadsheet:

- Report / analysis summary (述职, 调研报告) → **docx skill**
- Presentation (汇报, 演示, pitch deck) → **pptx skill**
- Formal print document (合同, 证书, "PDF") → **pdf skill**
- Charts only, no data table needed → **charts skill**
- User explicitly says a format → respect it

If confirmed xlsx → proceed to Scene Router below.

**Request Decomposition** (do this every time):
- **Explicit needs**: sheets, columns, formulas, metrics the user stated
- **Implicit needs**: business context, downstream use (filter? sort? input?)
- **Multi-part requests**: generate ALL parts — never silently drop a component

**Multi-Intent Detection** — some requests combine multiple scenes:

```
"Create a financial model with charts and export a PDF summary"
 → scenes/finance.md + engines/chart.md + (hand off PDF to pdf skill)

"Analyze this CSV, build a dashboard, and make it look professional"
 → scenes/analyze.md + engines/chart.md + engines/design.md

"Edit this budget file, add a new quarter column, and create a pivot"
 → scenes/edit.md + quality/pipeline.md (pivot command)

"Convert these 5 CSVs into one xlsx with a summary sheet"
 → scenes/convert.md + scenes/create.md (for summary)
```

When multiple intents detected, load all matching files and execute in logical order: data preparation → analysis → visualization → styling → QA.

---

## File Loading Rules (MANDATORY)

**Always load ALL matched files. No shortcuts, no lazy loading, no "on demand".**

```
User Request
│
├─ 1. Read SKILL.md (this file) — always
├─ 2. Route to scene file(s) via Scene Router below — read COMPLETELY
├─ 3. If scene involves charts → ALSO read engines/chart.md
├─ 4. If scene produces styled output → ALSO read engines/design.md
├─ 5. If scene is analyze → ALSO read scenes/analyze-recipes.md
├─ 6. If scene is edit → ALSO read scenes/edit-patterns.md
├─ 7. If scene is VBA → ALSO read engines/vba-templates.md
└─ 8. QA: always run full pipeline (quality/pipeline.md)
```

**Rule: when in doubt, read the file.** The cost of reading an extra file is a few hundred tokens. The cost of NOT reading it is a broken output that needs to be redone.

**Chart + Design engines are loaded by default** unless the task is purely read-only (inspect/validate with no output file). If you are creating or editing an xlsx, you MUST read `engines/design.md`.

---

## Scene Router

```
User Request
│
├─ Involves an existing file?
│  ├─ Yes → Modify content or structure?
│  │         ├─ Yes ──────────────────── → scenes/edit.md
│  │         └─ No (read/analyze only) ─ → scenes/analyze.md
│  │
│  └─ Format conversion (CSV↔XLSX, JSON, PDF tables)?
│     └─ Yes ────────────────────────── → scenes/convert.md
│
├─ Create from scratch?
│  ├─ Financial / budget / forecast / cost tracking?
│  │  ├─ Complex (DCF / LBO / three-statement linkage (三表联动) / sensitivity / IB model)?
│  │  │  └─ Yes ─────────────────────── → scenes/finance.md
│  │  └─ Simple (budget table (预算表) / expense report (费用报表) / revenue vs cost (收支对比) / project cost (项目成本) / personal finance (个人记账))?
│  │     └─ Yes ─────────────────────── → scenes/finance_lite.md
│  └─ General table / report / template
│     └─ ──────────────────────────── → scenes/create.md
│
├─ Batch processing / large files / protection / validation?
│  └─ Yes ───────────────────────────── → scenes/advanced.md
│
├─ VBA / macros / automation inside Excel?
│  └─ Yes ───────────────────────────── → scenes/vba.md + engines/vba-templates.md
│
├─ Needs charts or data visualization?
│  └─ Yes ───────────── append ────────→ engines/chart.md
│
└─ Needs styling / design system?
   └─ Yes ───────────── append ────────→ engines/design.md
```

**Mixed requests**: load all matching files. Engine files always **append** to a scene.

**Finance detection**:
- **finance.md** (complex): DCF, LBO, P&L, 利润表, 资产负债, valuation, 估值, IRR, 三表联动, sensitivity, scenario
- **finance_lite.md** (simple): 预算, budget, 费用, expense, 收支, 记账, 项目成本, cost tracking, 报销, ROI

**VBA detection**: 宏, macro, VBA, 自动化, automation, .xlsm, 按钮, button, auto-run, 批量处理脚本

---

## Design Principles

### 1. Live Formula Guarantee
Every derived value SHOULD be an Excel formula so the spreadsheet stays dynamic.

**Exception — Programmatic Verification**: When the output file will be verified by Python (not opened in Excel), TOTAL/SUM rows should write **computed values** instead of formulas, because openpyxl cannot evaluate formulas and `data_only=True` returns `None` for newly-written formulas. Optionally add the formula as a cell comment for reference.

### 2. Zero Error Tolerance
Deliverables must have zero formula errors. All divisions wrapped with `IFERROR` or `IF(denom=0,...)`. Absolute references (`$C$42`) for shared denominators.

### 3. Compatibility First
No dynamic array functions (`FILTER`, `UNIQUE`, `XLOOKUP`, `SORT`, `SORTBY`, `XMATCH`, `SEQUENCE`, `LET`, `LAMBDA`, `RANDARRAY`). No implicit array formulas — use `SUMPRODUCT` alternatives.

### 4. Preserve & Match
When editing existing files: study and exactly match format, style, conventions. Existing patterns always override defaults. Text starting with `=` must be prefixed with `'`.

### 5. Language Mirror
Output language (sheet names, headers, labels) matches user's input language.

### 6. Data Consistency Over Instructions
When user instructions conflict with the actual data patterns in the existing file:
- **First priority**: match the existing data pattern (e.g., if existing data uses `0` for empty, don't switch to `-`)
- **Second priority**: follow user instructions literally
- Always flag the conflict to the user

Example: User says "show hyphen for zero" but existing data and answer key use numeric `0` → Use `0` and notify user of the discrepancy.

---

## Toolchain

### Script Path Setup (MANDATORY before any script call)

All CLI tools live relative to this skill's directory. Before calling any script, resolve the absolute path once:

```bash
SKILL_DIR="${SKILL_DIR}"   # ← parent directory of this SKILL.md

# Then all commands use absolute paths:
python3 "${SKILL_DIR}/xlsx.py" inspect data.xlsx --pretty
python3 "${SKILL_DIR}/xlsx.py" pivot data.xlsx output.xlsx --rows Region --values Revenue
python3 "${SKILL_DIR}/xlsx.py" validate output.xlsx
```

**For Python imports** (when generation code needs to import skill modules):

```python
import sys, os
SKILL_DIR = "${SKILL_DIR}"
for sub in [SKILL_DIR, os.path.join(SKILL_DIR, "templates")]:
    if sub not in sys.path:
        sys.path.insert(0, sub)
```

**⚠️ NEVER use bare `python3 xlsx.py ...`** — it only works if cwd happens to be the skill directory. Always use the absolute path.

### Tool Reference

| Tool | Use |
|------|-----|
| **openpyxl** | Formulas, formatting, charts, cell-level control |
| **pandas** | Data analysis, bulk operations, CSV/TSV |
| `load_workbook(read_only=True)` | Large file reads |
| `Workbook(write_only=True)` | Large file writes |
| **templates/base.py** | Design tokens, font resolution, style factories, utilities (single source of truth) |
| **xlsx.py** | QA commands (see `quality/pipeline.md`) |

Workbook metadata: `wb.properties.creator = "Nebflow"`

> **All code MUST import from `templates/base.py`** for colors, fonts, and style helpers. Never hardcode hex values or font names.

---

## Quality Gate

Every deliverable must pass the full integrity pipeline before delivery.

→ **Load `quality/pipeline.md` for the role-based integrity workflow.**

Quick reference:
```
Blueprint → Build & Self-check (per-sheet) → Inspect → Pivot (if needed) → Release
```

---

## Capability Matrix

| Capability | Supported | Scene/Engine |
|-----------|-----------|-------------|
| Create from scratch | ✅ | scenes/create |
| Edit existing file | ✅ | scenes/edit |
| Data analysis & EDA | ✅ | scenes/analyze |
| Format conversion | ✅ | scenes/convert |
| Financial models (DCF/LBO/P&L) | ✅ | scenes/finance |
| Simple budgets & expenses | ✅ | scenes/finance_lite |
| VBA macros & automation | ✅ | scenes/vba + engines/vba-templates |
| Batch processing | ✅ | scenes/advanced |
| Embedded charts | ✅ | engines/chart |
| Smart chart recommendation | ✅ | engines/chart |
| Design system & styling | ✅ | engines/design |
| PivotTable creation | ✅ | quality/pipeline (pivot cmd) |
| Formula validation | ✅ | quality/pipeline |
| Structural validation | ✅ | quality/pipeline |
| Data provenance tracking | ✅ | scenes/analyze |
| Large file handling | ✅ | scenes/advanced |
| Data protection & locking | ✅ | scenes/advanced |

## 交付与引用（Nebflow 口径）

产出件即交付件：在正文里用**绝对路径**点到每个最终文件（一处一次），不另列文件名清单，也不重复贴正文内容。

- **生成 / 编辑**：每个最终文件点名一次，概括代表性改动；不逐节逐页复述，不重复写文件名或路径。
- **问答 / 只读**：不改写、不重导出源文件。
- **定位只写已核实读数**：页号、单元格等定位必须先核对最新一次渲染或读取结果；核不准则退到更粗的定位（文件级）——禁猜页号、单元格、对象 id。
- **中间件不报**：渲染 PNG、临时件、构建器、QA 中间产物不进交付文本（明确索要除外）。
- **原件保留**：非原地编辑时保留源文件、另存副本；确认未改动则按原件引用。

| 交付形态 | 定位口径 |
|---|---|
| 文档（docx / pdf） | 页级证据先核对最新渲染；保留标题、表/图标签、脚注、来源、样本量；每个所需页面点名一次。PDF 与渲染图只做文件级定位，不给页级定位符 |
| 演示（pptx） | 一次点到该页，同页多条主张合并；点具体图/表时给出已核实的页号 + 可读标签，核不准就只到页号 |
| 表格（xlsx） | 整表结论按文件级点到；数值给最窄可靠位置（工作表 + 区域 / 单个离散单元格）；不相邻区域分开点；算式只点答案需要的输入、驱动、公式或结果 |
