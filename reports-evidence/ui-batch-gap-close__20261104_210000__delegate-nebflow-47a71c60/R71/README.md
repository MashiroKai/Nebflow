# R71 主会话底部消息圆角化（末条气泡死角归零）— 证据

条目：UI 批次审计 2026-11-04 D 档 R71 / 台账 #1475⑥（11:49《canvas-ask-resize-supps》曾以「实锚错位」核对后关闭，实测为虚报）
批：ui-batch-gap-close（delegate-nebflow-47a71c60）

## 改前（红）读数（computed `border-radius` 四角，1440×900 与 375×812 同口径）
| 夹具 | TL | TR | BR | BL | 判 |
|---|---|---|---|---|---|
| 末条 user 气泡 | 16 | 16 | **4** | 16 | 底右 4px 死角恒在 |
| 末条 ai 气泡 | 16 | 16 | 16 | **4** | 底左 4px 死角恒在 |
| 末条 ai（375 手机层） | 16 | 16 | 16 | **4** | 同上，无平台差 |

## 改后（绿）读数
| 夹具 / 态 | TL | TR | BR | BL |
|---|---|---|---|---|
| 末条 user（停靠） | 16 | 16 | **16** | **16** |
| 末条 user（滚动中 scrollTop=120） | 16 | 16 | 16 | 16 |
| 末条 user（键盘态 视口 900→560） | 16 | 16 | 16 | 16 |
| 末条 ai（停靠 / 折叠件展开 / 收起） | 16 | 16 | **16** | **16** |
| 末条 ai（375 手机层） | 16 | 16 | 16 | **16** |
| **非末条** user 气泡 | — | — | **4**（保留尾角） | — |

## 命令原文
```bash
CHATFACE_WEB_ROOT=/tmp/uibatch-before-web/src/main/resources/web EVID_DIR=…/R71 EVID_TAG=before \
node node_modules/@playwright/test/cli.js test tests/chat-last-bubble-round.spec.mjs   # → 3 failed（红）
EVID_DIR=…/R71 EVID_TAG=after node node_modules/@playwright/test/cli.js test tests/chat-last-bubble-round.spec.mjs   # → 3 passed（绿）
```

## 落地改动
- `css/chat.css`：新增 `#chat .row:last-child .bubble.{user,injected,ai}` ⇒ 底两角 = `--ds-radius-card-lg`。
  只认末行位置，不挂态类 ⇒ 滚动/展开/键盘三态与折叠件两态顶不掉；非末条尾角原样保留（反回退判据）。

## 文件
`r71-user-docked.*` / `r71-user-three-states.*` / `r71-user-keyboard.*` / `r71-ai-docked.*` / `r71-ai-fold.*` / `r71-mobile.*`（before/after 成对）+ `r71-user-1440.*.png` / `r71-ai-1440.*.png` / `r71-mobile-375.*.png`
