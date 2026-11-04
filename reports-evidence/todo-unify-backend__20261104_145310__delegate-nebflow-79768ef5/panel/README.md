# 胶囊面交互实现（面板节点）— 证据索引

工程：`delegate-nebflow-79768ef5` / 分支 `delegate/delegate-nebflow-79768ef5`
批：待办体系改版（2026-11-04 作者令）· 胶囊面交互实现节点（渲染层 + CSS）
本目录 = 过程证据（原始日志 + 关键帧）；知识文档按批令由 land 节点落 `.stand/docs/`。

## 1. 新 spec（本节点交付）

`tests/todo-capsule-create.spec.mjs` — 8 例（①加号几何+聚焦 ②空白建两落点 ③防误触①②
④防误触③位移>8px ⑤priority 落库帧 ⑥完成/撤回/删除三帧 ⑦动画护栏 spring+计数翻牌+逾期翻面
⑧手机 375 胶囊可见可展开 + 44px 加号）。
跑法：`node <stand>/node_modules/@playwright/test/cli.js test tests/todo-capsule-create.spec.mjs`
原始输出：`logs/01_new_spec_todo_capsule_create.log` → **8 passed (33.4s)**

## 2. 门禁（原始输出）

| 门禁 | 退出码 |
|---|---|
| `node scripts/check-design-tokens.mjs` | **0**（PASS 无新增设计字面量；todo-face.css color 73→70） |
| `node scripts/check-msgstyle-single-source.mjs` | **0**（7 rules / 40 css files） |
| `node scripts/check-self-explaining.mjs` | 本分支**不存在该脚本**（main 侧新门禁，未合并到本分支）——新文案已按铁律 8 人工自检：`删除` / `新建待办` / `已完成` / `撤回` 四条均为「命名」而非教学或路径指引 |

## 3. 既有回归（原始输出）

| 用例 | 结果 |
|---|---|
| `tests/todo-split.spec.mjs` | 6 passed |
| `tests/todo-store-backend.spec.mjs` | 6 passed |
| `tests/todo-face-shots.mjs`（改前 / 改后） | **96 PASS / 8 FAIL 两侧逐条同名**（`diff` 为空，见 `logs/10_`、`logs/14_`） |
| `tests/todo-page-shots.mjs` | `SHOTS OK`（exit 0） |
| `tests/mobile-dock.spec.mjs` + `mobile-chrome-clearance` + `mobile-topclip` | 20 passed / 1 failed（D18，改前既有） |
| `tests/mobile-chat-initial-scroll.spec.mjs` | `mode=green failures=0` |

## 4. `todo-capsule-redesign-shots.mjs` 改前/改后（600s 族）

| 运行 | PASS / FAIL |
|---|---|
| 改前 #1（HEAD web + HEAD 脚本） | 159 / 19 |
| 改前 #2（同上，重跑） | 158 / 20 |
| 改前 #3（`CAPSULE_WEB` 官方口径） | 158 / 20 |
| 改后 #1（首版） | 154 / 24 |
| 改后 #2（final） | 151 / 27 |
| 改后 #3（final + 手机断言按新口径改写） | 152 / 28 |

**差异全部落在 ⑥「收起」族**，且改前两次同样红（`Esc 收起（焦点在输入框）`、`收起钮收起`、
`收起后胶囊尺寸不变`）。实测关合形变耗时（探针 5 次/侧）：改前 759–859ms、改后 746–796ms，
脚本固定 `waitForTimeout(800)` 恰压在弹簧落地边界 ⇒ 该族在改前就是**抛硬币**，改后只是
多级联了几条（首条 Esc 未落地 ⇒ 胶囊仍 `visibility:hidden` ⇒ 后续 click 超时 throw）。
另：手机两条断言「桌面胶囊槽位不显示」在本批**按作者令 ⑤ 有意翻新**为「手机层显示且胶囊在场」。

## 5. 关键帧（改前/改后各一套，同名可比）

`shots-before/` vs `shots-after/`：`d-1280-light-open`（展开面板）、`d-1280-light-open-panel`
（面板单帧）、`d-1280-dark-open`、`d-1440-light-open-panel`、`d-add-mid-chromium-light`
（行内新建行）、`d-1280-light-overdue` / `-todo`（两态翻面）、`m-375-*-todo-page`（手机任务页）。
本节点自拍（final 代码）：`01_fab_desktop_open.png`、`02_fab_click_createrow.png`、
`03_blank_footer_createrow.png`、`04_mobile_capsule.png`、`05_mobile_panel_open.png`。
