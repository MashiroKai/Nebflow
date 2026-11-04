# 迁移映射表（design-scale 迁移批 2026-11-04）

数据源：`premap-<class>.json` —— 迁移器打在**纯净 HEAD 树**上的 dry-run 明细，
即「改前有多少处该迁」，与 apply 顺序/重复执行无关（可复算）。

判据：**只收解析后 computed 值完全相同的映射**（零像素变化）。换不到同值令牌的
一律不动，见文末「需作者裁定」清单。解析值列由 `generate-mapping-table.mjs` 现读
`design-system.css` 生成，不是手抄。

## 字号 font-size（已迁移 576 处，属性 = `font-size`）

| 原始字面量 | 令牌名 | 解析值 | 出现处数 |
|---|---|---|---|
| `12px` | `--ds-fs-hint` | `12px` | 215 |
| `11px` | `--ds-fs-meta` | `11px` | 163 |
| `13px` | `--ds-fs-body` | `13px` | 111 |
| `15px` | `--ds-fs-title` | `15px` | 30 |
| `12px` | `--ds-fs-btn` | `12px` | 20 |
| `16px` | `--ds-fs-mobile-callout` | `16px` | 18 |
| `13.5px` | `--ds-fs-row` | `13.5px` | 6 |
| `20px` | `--ds-fs-display` | `20px` | 4 |
| `17px` | `--ds-fs-lead` | `17px` | 3 |
| `14.5px` | `--ds-fs-bubble` | `14.5px` | 3 |
| `15px` | `--ds-fs-mobile-sub` | `15px` | 2 |
| `28px` | `--ds-fs-title-lg` | `28px` | 1 |

## 圆角 border-radius（已迁移 577 处，属性 = `border-radius`）

| 原始字面量 | 令牌名 | 解析值 | 出现处数 |
|---|---|---|---|
| `8px` | `--ds-radius-input` | `8px` | 170 |
| `6px` | `--ds-radius-chip` | `6px` | 102 |
| `4px` | `--ds-radius-badge` | `4px` | 71 |
| `10px` | `--ds-radius-button` | `10px` | 69 |
| `12px` | `--ds-radius-xl` | `12px` | 41 |
| `999px` | `--ds-radius-pill` | `999px` | 39 |
| `2px` | `--ds-radius-thin` | `2px` | 21 |
| `16px` | `--ds-radius-card-lg` | `16px` | 20 |
| `14px` | `--ds-radius-card-md` | `14px` | 15 |
| `20px` | `--ds-radius-shell` | `20px` | 13 |
| `10px` | `--ds-radius-card-sm` | `10px` | 9 |
| `18px` | `--ds-radius-2xl` | `18px` | 7 |

## 间距 padding / margin / gap（已迁移 2463 处，属性 = `padding·margin·gap 原子`）

| 原始字面量 | 令牌名 | 解析值 | 出现处数 |
|---|---|---|---|
| `8px` | `--ds-space-2` | `8px` | 545 |
| `6px` | `--ds-space-1h` | `6px` | 409 |
| `10px` | `--ds-space-glass-x` | `10px` | 357 |
| `4px` | `--ds-space-1` | `4px` | 349 |
| `2px` | `--ds-space-half` | `2px` | 292 |
| `12px` | `--ds-space-3` | `12px` | 280 |
| `16px` | `--ds-space-4` | `16px` | 147 |
| `20px` | `--ds-space-5` | `20px` | 37 |
| `24px` | `--ds-space-6` | `24px` | 34 |
| `32px` | `--ds-space-8` | `32px` | 11 |
| `48px` | `--ds-space-12` | `48px` | 2 |

## 需作者裁定（本批**未动**，理由 = 无解析值相同的令牌）

### 字号 font-size：201 处 / 15 个值

| 原始值 | 出现处数 | 主要落点 | 未迁原因 |
|---|---|---|---|
| `10px` | 59 | css/chat.css·css/diffView.css·css/executors.css·css/friends.css | 字号 10px 无同值令牌（改档即改像素） |
| `14px` | 46 | css/chat.css·css/dropbox.css·css/friends.css·css/glass.css | 14px 是语义具名槽（capsule/card-body 同值异义），本处语境不明确 |
| `12.5px` | 24 | css/chat.css·css/flowMap.css·css/friends.css·css/perception.css | 字号 12.5px 无同值令牌（改档即改像素） |
| `11.5px` | 23 | css/chat.css·css/flowMap.css·css/perception.css·css/projectPanel.css | 字号 11.5px 无同值令牌（改档即改像素） |
| `9px` | 14 | css/chat.css·css/nav.css·css/reminder.css·css/taskList.css | 字号 9px 无同值令牌（改档即改像素） |
| `10.5px` | 13 | css/chat.css·css/perception.css·css/projectPanel.css·css/reminder.css | 字号 10.5px 无同值令牌（改档即改像素） |
| `18px` | 6 | css/dropbox.css·css/nav.css·css/sidebar.css·css/split.css | 字号 18px 无同值令牌（改档即改像素） |
| `22px` | 4 | css/mobileLayer.css·css/perception.css·css/split.css·js/cardwinDirect.js | 字号 22px 无同值令牌（改档即改像素） |
| `8px` | 3 | css/delegatePanorama.css·css/input.css·css/reminder.css | 字号 8px 无同值令牌（改档即改像素） |
| `9.5px` | 2 | css/chat.css·css/keyboard.css | 字号 9.5px 无同值令牌（改档即改像素） |
| `8.5px` | 2 | css/chat.css·css/flowMap.css | 字号 8.5px 无同值令牌（改档即改像素） |
| `15.5px` | 2 | css/perception.css·js/activityBar.js | 字号 15.5px 无同值令牌（改档即改像素） |
| `7px` | 1 | css/chat.css | 字号 7px 无同值令牌（改档即改像素） |
| `19px` | 1 | css/mobileLayer.css | 字号 19px 无同值令牌（改档即改像素） |
| `26px` | 1 | js/activityBar.js | 字号 26px 无同值令牌（改档即改像素） |

### 圆角 border-radius：100 处 / 15 个值

| 原始值 | 出现处数 | 主要落点 | 未迁原因 |
|---|---|---|---|
| `7px` | 25 | css/chat.css·css/flowMap.css·css/projectPanel.css·css/reminder.css | 圆角 7px 无同值令牌（改档即改像素） |
| `3px` | 21 | css/base.css·css/chat.css·css/flowMap.css·css/friends.css | 圆角 3px 无同值令牌（改档即改像素） |
| `5px` | 16 | css/delegatePanorama.css·css/flowMap.css·css/friends.css·css/modal.css | 圆角 5px 无同值令牌（改档即改像素） |
| `9999px` | 14 | css/chat.css·css/dropbox.css·css/friends.css·css/sapphire.css | 圆角 9999px 无同值令牌（改档即改像素） |
| `9px` | 8 | css/chat.css·css/executors.css·css/perception.css·css/sidebar.css | 圆角 9px 无同值令牌（改档即改像素） |
| `1px` | 4 | css/cards.css·css/modal.css·css/split.css | 圆角 1px 无同值令牌（改档即改像素） |
| `11px` | 4 | css/delegatePanorama.css·css/perception.css·js/askAttachments.js·js/flowCss.js | 圆角 11px 无同值令牌（改档即改像素） |
| `13px` | 1 | css/mobileLayer.css | 圆角 13px 无同值令牌（改档即改像素） |
| `3.5px` | 1 | css/mobileLayer.css | 圆角 3.5px 无同值令牌（改档即改像素） |
| `17px` | 1 | css/split.css | 圆角 17px 无同值令牌（改档即改像素） |
| `0 12px 12px 0` | 1 | css/split.css | 圆角组合值（0 12px 12px 0）—— 组合形态无人裁定，保字面量 |
| `12px 12px 0 0` | 1 | css/split.css | 圆角组合值（12px 12px 0 0）—— 组合形态无人裁定，保字面量 |
| `var(--ds-radius-shell) var(--ds-radius-shell) 0 0` | 1 | css/timeWheel.css | 圆角组合值（var(--ds-radius-shell) var(--ds-radius-shell) 0 0）—— 组合形态无人裁定，保字面量 |
| `24px 24px 19px 19px` | 1 | css/todo-face.css | 圆角组合值（24px 24px 19px 19px）—— 组合形态无人裁定，保字面量 |
| `0 10px 0 8px` | 1 | js/askAttachments.js | 圆角组合值（0 10px 0 8px）—— 组合形态无人裁定，保字面量 |

### 间距 padding / margin / gap：371 处 / 59 个值

| 原始值 | 出现处数 | 主要落点 | 未迁原因 |
|---|---|---|---|
| `3px` | 75 | css/chat.css·css/delegatePanorama.css·css/diffView.css·css/executors.css | 间距档外值 3px（4px 栅格 + 两例外档均无同值令牌） |
| `5px` | 61 | css/chat.css·css/delegatePanorama.css·css/diffView.css·css/flowMap.css | 间距档外值 5px（4px 栅格 + 两例外档均无同值令牌） |
| `1px` | 44 | css/chat.css·css/design-system.css·css/dropbox.css·css/flowMap.css | 间距档外值 1px（4px 栅格 + 两例外档均无同值令牌） |
| `14px` | 29 | css/chat.css·css/delegatePanorama.css·css/flowMap.css·css/mobileLayer.css | 间距档外值 14px（4px 栅格 + 两例外档均无同值令牌） |
| `7px` | 28 | css/ask.css·css/chat.css·css/executors.css·css/flowMap.css | 间距档外值 7px（4px 栅格 + 两例外档均无同值令牌） |
| `1px 5px` | 12 | css/chat.css·css/delegatePanorama.css·css/flowMap.css·css/friends.css | 间距档外值 1px/5px（4px 栅格 + 两例外档均无同值令牌） |
| `9px` | 9 | css/chat.css·css/delegatePanorama.css·css/mobileLayer.css·css/perception.css | 间距档外值 9px（4px 栅格 + 两例外档均无同值令牌） |
| `-1px` | 9 | css/dropbox.css·css/mobileLayer.css·css/modal.css·css/projectPanel.css | 间距档外值 -1px（4px 栅格 + 两例外档均无同值令牌） |
| `18px` | 7 | css/cards.css·css/chat.css·css/delegatePanorama.css·css/design-system.css | 间距档外值 18px（4px 栅格 + 两例外档均无同值令牌） |
| `1px 7px` | 7 | css/chat.css·css/design-system.css·css/sidebar.css·css/split.css | 间距档外值 1px/7px（4px 栅格 + 两例外档均无同值令牌） |
| `5px 14px` | 6 | css/chat.css·css/friends.css·css/projectPanel.css·js/flowCss.js | 间距档外值 5px/14px（4px 栅格 + 两例外档均无同值令牌） |
| `-4px` | 5 | css/chat.css·css/sidebar.css·js/bgAgentWorkStrip.js·js/sidebar.js | 间距档外值 -4px（4px 栅格 + 两例外档均无同值令牌） |
| `1.5px` | 5 | css/friends.css·css/perception.css·css/split.css | 间距档外值 1.5px（4px 栅格 + 两例外档均无同值令牌） |
| `40px` | 4 | css/flowMap.css·css/sidebar.css·js/flowCss.js | 间距档外值 40px（4px 栅格 + 两例外档均无同值令牌） |
| `3px 7px` | 4 | css/flowMap.css·css/input.css·js/explorer.js | 间距档外值 3px/7px（4px 栅格 + 两例外档均无同值令牌） |
| `3px 9px` | 4 | css/friends.css·css/split.css·css/todo-face.css | 间距档外值 3px/9px（4px 栅格 + 两例外档均无同值令牌） |
| `14px 14px` | 4 | js/flowCss.js | 间距档外值 14px（4px 栅格 + 两例外档均无同值令牌） |
| `7px 14px` | 3 | css/chat.css·css/projectPanel.css | 间距档外值 7px/14px（4px 栅格 + 两例外档均无同值令牌） |
| `56px` | 3 | css/flowMap.css·css/projectPanel.css·css/split.css | 间距档外值 56px（4px 栅格 + 两例外档均无同值令牌） |
| `11px 14px` | 3 | css/flowMap.css·css/sidebar.css | 间距档外值 11px/14px（4px 栅格 + 两例外档均无同值令牌） |
| `30px` | 3 | css/friends.css·css/split.css | 间距档外值 30px（4px 栅格 + 两例外档均无同值令牌） |
| `11px` | 2 | css/chat.css | 间距档外值 11px（4px 栅格 + 两例外档均无同值令牌） |
| `11px 13px` | 2 | css/chat.css | 间距档外值 11px/13px（4px 栅格 + 两例外档均无同值令牌） |
| `1px 9px` | 2 | css/diffView.css | 间距档外值 1px/9px（4px 栅格 + 两例外档均无同值令牌） |
| `7px 9px` | 2 | css/flowMap.css·js/daemonPanel.js | 间距档外值 7px/9px（4px 栅格 + 两例外档均无同值令牌） |
| `26px` | 2 | css/glass.css·css/split.css | 间距档外值 26px（4px 栅格 + 两例外档均无同值令牌） |
| `-8px` | 2 | css/mobileLayer.css | 间距档外值 -8px（4px 栅格 + 两例外档均无同值令牌） |
| `28px` | 2 | css/mobileLayer.css·css/reminder.css | 间距档外值 28px（4px 栅格 + 两例外档均无同值令牌） |
| `14px 18px` | 2 | js/flowCss.js | 间距档外值 14px/18px（4px 栅格 + 两例外档均无同值令牌） |
| `9px 13px` | 1 | css/ask.css | 间距档外值 9px/13px（4px 栅格 + 两例外档均无同值令牌） |
| …其余 29 个低频值 | | | 见 needs-ruling-space.json |

