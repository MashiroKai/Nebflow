# 回归面 RESULTS — fm 三面输入条对齐批（2026-11-02）

产物目录：`evidence/20261102_fm-threeface-align/regression/`
- `RESULTS.md`（本文件）
- `logs/` — 9 套完整 stdout + `exitcodes.txt`

复跑命令（逐条，工作目录 = worktree 根）：

```bash
cd "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-c1142b9b"
ln -sfn "/Users/kaiyu/Claude code/Stand/node_modules" node_modules   # 幂等、gitignored、勿提交

node node_modules/@playwright/test/cli.js test tests/file-bytes-single-fetch.spec.mjs
NODE_PATH=/opt/homebrew/lib/node_modules node tests/imgmsg-send.spec.mjs
NODE_PATH=/opt/homebrew/lib/node_modules node tests/attcard-unify-shots.mjs
NODE_PATH=/opt/homebrew/lib/node_modules node tests/msgmenu.spec.mjs
NODE_PATH=/opt/homebrew/lib/node_modules node tests/ime-guard.spec.mjs
NODE_PATH=/opt/homebrew/lib/node_modules node tests/busy-lifecycle.spec.mjs
NODE_PATH=/opt/homebrew/lib/node_modules node tests/mvp2-device-client.spec.mjs
NODE_PATH=/opt/homebrew/lib/node_modules node tests/rcptcode-friendterminal.spec.mjs
NODE_PATH=/opt/homebrew/lib/node_modules node tests/friends-dev-default-on.spec.mjs
```

## 结果总表

| # | spec | 类型 | 退出码 | PASS/FAIL | 备注 |
|---|------|------|--------|-----------|------|
| 1 | `file-bytes-single-fetch.spec.mjs` | @playwright/test 套件 | **0** | **7 / 0** | 字节链硬门达标，T1–T7 逐条 PASS |
| 2 | `imgmsg-send.spec.mjs` | 独立脚本（playwright） | **0** | **115 / 0** | failures=0；17 条「无 pageerror」全绿 |
| 3 | `attcard-unify-shots.mjs` | 独立脚本（playwright） | **0** | **47 / 0** | failures=0；5 条零 console error + 5 条零 pageerror；37 帧落 `/tmp/attcard-unify-shots` |
| 4 | `msgmenu.spec.mjs` | 独立脚本（playwright） | **1** | **85 / 1** | 唯一红 S6 光球挂载点 = **既有红** |
| 5a | `ime-guard.spec.mjs` | 独立脚本（playwright-core） | **0** | **37 / 0** | ALL PASS |
| 5b | `busy-lifecycle.spec.mjs` | 独立脚本（playwright-core） | **0** | **39 / 0** | ALL PASS；非 instance-backed（自起随机端口）⇒ 无需跳过 |
| 6 | `mvp2-device-client.spec.mjs` | 独立脚本（playwright-core） | **1** | **43 / 1** | 唯一红 R4b 下载路由 = **既有红** |
| 7 | `rcptcode-friendterminal.spec.mjs` | 独立脚本（playwright） | **0** | **24 / 0** | ALL PASS |
| 8 | `friends-dev-default-on.spec.mjs` | 独立脚本（playwright-core） | **0** | **13 / 0** | ALL PASS |

合计 **410 PASS / 2 FAIL**，9 套零跳过。

## 字节链 T1–T7（硬门，exit 0 · 7 passed 11.2s）

| 断言 | 结果 |
|---|---|
| T1 设备面内联直显 + Canvas 打开 ⇒ `nf-file` 恰一次抓取（修前 = 两次） | PASS |
| T2 设备面重渲 ×2 + Canvas 打开 ×2 ⇒ 仍恰一次抓取 | PASS |
| T3 好友面直显 ⇒ 鉴权路恰一次 + `attach:<id>` 入账共享 | PASS |
| T4 灯箱打开已入账同件 ⇒ 零追加抓取（修前每开必 reMint 全量重拉） | PASS |
| T5 Markdown 文档图两 pane 渲染 + Canvas 打开 ⇒ 恰一次抓取 | PASS |
| T6 好图直显后内容换成坏字节（事件失效）⇒ blob 解码败逐出账本、票腿自愈恰一次、终态摘槽 | PASS |
| T7 HTML 文档 SVG 内联化 ⇒ 同一 SVG 恰一次网络抓取 | PASS |

## 失败摘录与归因

### ① `msgmenu S6 单一光球挂载点`（既有红，非本批引入）

首行报错（`logs/msgmenu.log:105`）：

```
FAIL  S6 修正④① 🔴 单一光球挂载点（DOM 面）：宿主/光球钮/画布/css-orb 各恰一件 + 三个 id 全在册 + MicOrb 单例绑定产品画布
  — {"wrap":2,"bubble":2,"canvas":2,"css":2,"ripple":0,"ids":["mic-canvas","mic-css-orb","mic-orb-wrap"],"bound":true}
```

同段上一行 `S6 修正④①…光球形态面` 是 **PASS**（`present:true` / `64×64` / `radius 50%` / `orbCanvasInBtn:true` / `cssOrbInBtn:true` / `singleton:true` / `singletonBound:true`）。

谓词要求「宿主 / 光球钮 / 画布 / css-orb **各恰 1**」（`tests/msgmenu.spec.mjs:1155`），实测各 2。

**根因**：`src/main/resources/web/index.html:938-944` 的全双工 overlay `#vmo-orb-wrap` 同样携带 `.mic-orb-wrap` / `.micbubble` / `.orb-canvas` / `.css-orb` 四类 —— 页面上有两套光球 DOM（主会话光球 + 全双工 overlay）。该文件与本批零交集（本批只改 `messages/fmComposer.js`、`messages.js`、`friends.css`、`popArtifacts.js`）。

**三树对照**：改后树 85/1、同树还原树（`git show HEAD:` 还原本批三文件 + 删 `fmComposer.js`）85/1、main 仓 web 82/1 —— 三树读数逐字相同 ⇒ 与本批无关。`ripple:0`、三 id 全在册、`singletonBound:true` 均满足本批判据。

**建议（spec 侧，非本批）**：把计数收窄到 `#mic-orb-wrap` 子树，或把 `#vmo-orb-wrap` 显式排除。

### ② `mvp2 R4b 附件下载走既有鉴权路由`（既有红，非本批引入）

首行报错（`logs/mvp2-device-client.log:29`）：

```
FAIL  R4b 附件下载走既有鉴权路由 /api/friends/attachments/{id}  — count=2
```

要求 `attDownload === 1`（`tests/mvp2-device-client.spec.mjs:327`），实测 2。

**推断**：设备附件卡的下载键与媒体面字节填充走同一鉴权路由 ⇒ 一次点击腿 + 一次填充腿被同一 stub 计数两次。本批只去掉 `fm-att-media` 类（只作用叠卡组，`messages.js:1930`），不涉及设备单件卡的下载键数量。

**三树对照**：改后树 43/1、同树还原树 43/1、main 34/1 —— 同读数 ⇒ 与本批无关。（`mvp2` 自带 BEFORE-TREE 模式按设计必红，故不采用该模式做基线。）

**建议（spec 侧，非本批）**：区分「用户点击腿 vs 填充腿」两个计数。

## 附加门（零 console error / 零 pageerror）

| spec | 零 console error | 零 pageerror | 读数 |
|---|---|---|---|
| `imgmsg-send` | — | 17 条 | 全 `[]` |
| `attcard-unify-shots` | 5 条 | 5 条 | 全 `[]` |
| `msgmenu` | — | 10 条 | 全 `[]` |
| `mvp2-device-client` | — | 3 条 | 全 `[]` |
| `rcptcode-friendterminal` | — | 5 条 | 全 `[]` |
| `busy-lifecycle` | — | 1 条 | `[]` |
| `friends-dev-default-on` | — | 3 条 | 全 `[]` |
| `file-bytes-single-fetch` | 脚本内无此门 | 无 | **显式申报不适用** |
| `ime-guard` | 脚本内无此门 | 无 | **显式申报不适用** |

## 开放项

- 两处既有红是否随批换代 spec 口径 —— 需另立批（改 spec 判据），非本批范围；本批**未**改源码凑绿。
- `attcard-unify-shots` 的 37 帧留在 `/tmp/attcard-unify-shots`（非本批产物，未收编；重跑加 `ATT_SHOTS=<dir>` 可落任意目录）。
- 本批未跑 webkit / firefox（脚本均只跑 chromium）。

## 环境

- 平台：macOS（darwin 25.4.0）· node + playwright 1.62.0 · `NODE_PATH=/opt/homebrew/lib/node_modules`（全局 playwright 供独立脚本解析）
- 未起 sbt/java；未碰 main；证据仅落 `evidence/20261102_fm-threeface-align/`
