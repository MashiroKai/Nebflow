# R70 输入条底缘与 sidebar / canvas 齐平 — 证据

条目：UI 批次审计 2026-11-04 D 档 R70 / 台账 #1475⑤
批：ui-batch-gap-close（delegate-nebflow-47a71c60）

## 改前（红）读数（1440×900，画布闭态与开态同值）
| 量 | 值 |
|---|---|
| `#input-bar`.bottom | **896** |
| `#input-area`.bottom | **900**（= 视口底） |
| `#sidebar`.bottom | **890** |
| `#canvas-panel`.bottom | **890** |
| 差值 bar→sidebar / bar→canvas | **+6px / +6px**（area 差 +10px） |

## 改后（绿）读数
| 量 | 值 |
|---|---|
| `#input-bar`.bottom | **890** |
| `#input-area`.bottom | **890** |
| `#sidebar`.bottom | **890** |
| `#canvas-panel`.bottom | **890** |
| 差值 | **0 / 0**（闭态、开态两腿各自 0） |

## 命令原文
```bash
# 改前腿（导出树）
CHATFACE_WEB_ROOT=/tmp/uibatch-before-web/src/main/resources/web EVID_DIR=…/R70 EVID_TAG=before \
node node_modules/@playwright/test/cli.js test tests/input-area-flush.spec.mjs   # → 2 failed（红）
# 改后腿
EVID_DIR=…/R70 EVID_TAG=after node node_modules/@playwright/test/cli.js test tests/input-area-flush.spec.mjs   # → 2 passed（绿）
```

## 落地改动
- `css/split.css` `#main`：`margin: 0 var(--ds-space-glass-x) var(--ds-space-glass-x)`（底距 0 → 10px，与侧栏/画布同档）。
- `css/input.css` `#input-area`：底缘外留白 4px → 0（让位改由 `#main` 的 10px 承担；`#chat` 让位读 `offsetHeight` 仍自洽，末条消息仍在条上方 —— R71 spec 的 `#chat` 让位腿同时覆盖）。

## 文件
`r70-desktop-closed.*.json` / `r70-desktop-open.*.json` / `r70-desktop-1440.*.png` / `r70-desktop-1440-canvas-open.*.png`
