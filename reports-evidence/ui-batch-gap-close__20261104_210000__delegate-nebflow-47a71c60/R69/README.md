# R69 输入框收窄（两侧留一致留白）— 证据

条目：UI 批次审计 2026-11-04 D 档 R69 / 台账 #1475⑤（作者 recall 2026-11-03 18:30 + 2026-11-04 11:09）
批：ui-batch-gap-close（delegate-nebflow-47a71c60）

## 改前（红）读数
| 档位 | `#input-bar` L 侧距 | R 侧距 | 目标（令牌） | 结论 |
|---|---|---|---|---|
| 1440×900 桌面 | **0** | **0** | `--ds-space-4` = 16 | 顶满（审计同口径） |
| 375×812 手机层 | **0** | **0** | `--dock-inset-x` = 12 | 顶满；dock 胶囊本身 left=12/right=363 |

## 改后（绿）读数
| 档位 | L | R | 目标 | |L−R| | 结论 |
|---|---|---|---|---|---|
| 1440×900 | **16** | **16** | 16 | 0 | 两侧留白一致、未顶满 |
| 375×812 | **12** | **12** | 12（= dock 胶囊 left=12） | 0 | 与 dock 同竖轴边距 |

## 命令原文（在本 worktree 根跑）
```bash
# 改前腿（导出的改前树：git archive HEAD src/main/resources/web）
CHATFACE_WEB_ROOT=/tmp/uibatch-before-web/src/main/resources/web \
EVID_DIR=$PWD/reports-evidence/ui-batch-gap-close__20261104_210000__delegate-nebflow-47a71c60/R69 \
EVID_TAG=before node node_modules/@playwright/test/cli.js test tests/input-bar-inset.spec.mjs   # → 2 failed（红）

# 改后腿（本仓树）
EVID_DIR=$PWD/reports-evidence/ui-batch-gap-close__20261104_210000__delegate-nebflow-47a71c60/R69 \
EVID_TAG=after node node_modules/@playwright/test/cli.js test tests/input-bar-inset.spec.mjs    # → 2 passed（绿）
```

## 落地改动
- `css/input.css` `#input-area`：`padding: 0 var(--ds-space-4) env(safe-area-inset-bottom, 0px)`（横向留白 = 令牌档）。
- `css/mobileLayer.css` `body.mobile-layer #input-area`：`padding-left/right: var(--dock-inset-x)`（手机档与 dock 胶囊同边距）。

## 文件
- `r69-desktop.before.json` / `r69-desktop.after.json` / `r69-desktop-1440.*.png`
- `r69-mobile.before.json` / `r69-mobile.after.json` / `r69-mobile-375.*.png`
