# 基线同步节点（Sync）— 证据索引

工程：`delegate-nebflow-79768ef5` / 分支 `delegate/delegate-nebflow-79768ef5`
批：待办体系改版（2026-11-04 作者令）· 验收链「入口收敛 / 落库 / 跨端 / 动画 / 门禁 / 截图」
本节点职责：把本分支 merge 到 main 最新 tip 并解冲突，让下游 persist / entries / accept
节点在一棵不漂移的树上验收。

## 0. 一句话结论

merge **零冲突**（`git merge` exit 0，无人工解冲突）；合到 main tip **`d2fc65c82`**；
merge commit **`fa6526fb8`**（parents = `6b883302f` + `d2fc65c82`）。
五道门禁合后全绿；门禁初跑时 `component-contract` 曾红 16 条（`js/todoFace.js` 裸
`innerHTML` 计数 13→15），判定为**本批引入**并就地修两处调用点，复跑转绿——**baseline
文件零改动**。

## 1. 合到的 tip 与环境事实

| 项 | 值 |
|---|---|
| main tip（本地 ref） | `d2fc65c828960595c81dd3cdffdcad7e37b028e6` — `d2fc65c82 Merge branch 'delegate/delegate-nebflow-8b0a8e45'` |
| 本分支 merge 前 tip | `6b883302f`（panel：加号/空白建/重要度/完成撤回/删除/手机层胶囊） |
| merge-base | `0d9b1b874`（todoStore 换血 tip —— 已在 main 内） |
| merge commit | `fa6526fb8` |
| `git fetch` | **不通**（`origin` github 443 超时 75s）⇒ main tip 一律取本地 ref |

`941a11a13`（main 侧「Merge branch 'delegate/delegate-nebflow-79768ef5'」）与 `0d9b1b874`
在 main 内；作者那次 `--no-ff` 合入的**时点早于 panel 提交**（当时分支 tip 是 `0d9b1b874`），
所以 panel 成果（`6b883302f`）不在 main 里 —— 两端不存在并存，按普通 merge 处理。

## 2. 冲突处置（逐文件）

**0 个冲突**。两端同改的四个文件都落在不同 hunk 区间，git 自动合：

| 文件 | main 侧改动 | 本批侧改动 | 处置 |
|---|---|---|---|
| `web/js/main.js` | 文件头加 `import './dom.js' / './events.js' / './component.js'` 入口登记（ask-paradigm-a，构建孤儿守卫需要） | 4447 行区 `ResizeObserver` 回调改 `requestAnimationFrame(syncTopReserve)` 一跳（修 WebKit「RO loop」console error） | 两侧各留，无取舍 |
| `web/css/mobileLayer.css` | 4786 行区 `.ds-tool-card__toggle` → `.ds-tool-card__status`（过程泄漏修复批） | 2723 行区 手机层胶囊块（`#task-list` display:none 退役 + `.tfl-capsule` 命中面 + 44px 档） | 两侧各留，无取舍 |
| `web/js/locales/en.js` | 自解释批改写（44 行） | `todoface.del/fabAdd/completedToast/undo` 四新键 | 两侧各留 |
| `web/js/locales/zh-CN.js` | 自解释批改写（39 行） | 同上四新键 | 两侧各留 |

无冲突 ⇒ 无「谁让谁」的裁决；口径（本批面以本批为准、其余以 main 为准）仅作为冲突预案记录。

## 3. 范围核对（不回退）

- `git diff 6b883302f..HEAD -- <本批 9 文件>` → 只有 main 侧增量（`main.js` +8 入口登记、
  `mobileLayer.css` 1 行、两个 locales 的自解释改写），**零本批回退 hunk**。
  `todoFace.js` / `todoStore.js` / `todo-face.css` / `index.html` /
  `todo-capsule-create.spec.mjs` 在 diff 里**完全不出现**（= 与 panel 提交逐字节一致）。
- `git diff main..HEAD --stat -- src/main/resources/web src/main/scala tests` → 恰好本批 9 个
  文件，`src/main/scala` **0 个**（本批 Scala 内容早已在 main 内）。清单之外零 diff。

## 4. main 侧 API 变化 → 适配点清单

逐个核对本批面依赖的前端内核符号（main 重构过 `component/dom/events/state/utils/ws`）：

| 依赖 | main 侧是否改名/改签名 | 落点 |
|---|---|---|
| `utils.js` `createIconsIn(root)` | **无**（行零改动） | `todoFace.js:38` |
| `i18n.js` `t(key, params)` | **无**（`i18n.js` 零改动） | `todoFace.js:36` |
| `imeGuard.js` `isImeComposing(e, el)` | **无**（零改动） | `todoFace.js:37` |
| `ws.js` `sendWs(msg)` / `onMessage(type, handler)` / `onReconnect(cb)` | **无**（三者函数体在 main 侧 diff 中零命中） | `todoStore.js:52` |
| `state.js` `activeSessionId` | **无**（该行零改动） | `todoStore.js:135` |
| `taskList.js` `sessionShowsNodeEntries(sid)` | **无**（main 未动） | `todoFace.js:39` |

**结论：0 处需要适配的 API 变化。** 唯一改动是门禁驱动的调用点重写（见 §5）。

## 5. 门禁（合后复跑，逐条 exit code）

| 门禁 | 结果 | 原始日志 |
|---|---|---|
| `make frontend-gates`（design-tokens + msgstyle + self-explaining） | **exit 0** | `logs/15_gate_frontend_gates_postfix.log` |
| `node scripts/check-design-spec-sync.mjs` | **exit 0** | `logs/16_gate_design_spec_sync_postfix.log` |
| `node scripts/check-component-contract.mjs` | **exit 0**（首跑 exit 1，见下） | `logs/14_gate_component_contract_fixed.log` |
| `bash scripts/check-process-artifacts.sh` | **exit 0** | `logs/17_gate_process_artifacts_postfix.log` |

**component-contract 首跑红（已归因为本批引入并修复）**：`logs/12_gate_component_contract.log`
报 16 条，根因 `js/todoFace.js` 裸 `innerHTML` 赋值计数 **13 → 15**（基线 13，作者 main
四门禁全绿 ⇒ 这 2 条是本批新增调用点）。新增的两处 = 右下角加号图标（`.tfl-fab-add`）
与行尾删除 ×（`.tfl-del`）。修法（只改调用点、不重写本批实现）：
- 加号 → `fab.appendChild(lucideIcon('plus'))`（新增 3 行 helper，建 `<i data-lucide>` 元素，
  与文件内其它图标钮同一渲染腿）；
- 删除 × → `btn.appendChild(xIconEl())`（新增 helper 用 `createElementNS` 逐属性装配
  `viewBox 0 0 14 14` + 原 path `d`，**与字符串形态逐属性等价 ⇒ 渲染不变**），
  原 `X_SVG` 常量退役为 `X_VIEWBOX` / `X_PATH_D` 两个几何单源常量。

复跑 667 命中 / TOTAL 939（基线 941）**PASS**。`scripts/component-contract-baseline.json`
与 `scripts/design-token-baseline.json` **一行未动**（`git status` 可证）。

## 6. spec（合后复跑）

见同目录 `RUNS.md`（生成后补齐）。

## 7. 胶囊让位链实测

`yield_chain_probe.mjs`（自足：静态路由 + 最小 WS 夹具，零后端/零 6138）实测
DOM 归属 + 几何读数，产出 `yield_chain_probe.json` 与四张关键帧。
见 `RUNS.md` §让位链。

## 8. 未做 / 开放项

1. `ops/bin/sbt-gate` **已不在产品仓**（main 的 `3e06b7b4a`「本机构建预算外置」把它移到
   `~/.local/share/nebflow-local/bin/sbt-gate`，本仓只留指针，见 `ops/README.md` §本机构建预算）。
   故「用 `ops/bin/sbt-gate` 包装口径」改为：本机 `sbt` 即垫片
   （`~/.stand/bin/sbt` → symlink 到该外部 gate），命令原文照实记录。
2. `todo-capsule-redesign-shots.mjs`（600s 族）**未复跑**：panel 已证 ⑥「收起」族红是既有
   标尺（脚本固定 `waitForTimeout(800)` 压在弹簧落地边界，改前同样红），且该脚本单跑约
   数分钟 ×3；本次以 `todo-face-shots.mjs` + `todo-page-shots.mjs` 覆盖「相关既有 spec 不转红」。
3. 知识文档按批令由 land 节点落 `<dataRoot>/projects/<project>/.stand/docs/`，本节点不落。
