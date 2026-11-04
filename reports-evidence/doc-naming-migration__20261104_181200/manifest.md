# 文档命名改制 · 阶段 1：迁移清单（只读扫描）

- vault：`/Users/kaiyu/.stand/projects/stand/.stand/docs`
- 生成脚本：`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-71767a56/reports-evidence/doc-naming-migration__20261104_181200/build-manifest.py`（本清单一律由脚本复跑生成，勿手改）
- 快照时刻：2026-11-04 18:27:59（本地；本节点全程只读——未重命名 / 未改内容 / 未删文件）
- **快照会漂移**：扫描时刻检测到 **20** 个文件的 mtime 落在近 3 小时内（vault 有并发写入的会话），本清单是那一刻的读数；复跑脚本即得新读数，条目数可能小幅变动。

## 1. 摘要（关键读数）

| 指标 | 读数 |
| --- | --- |
| vault 条目总数（顶层） | 218（208 文件 + 10 子目录） |
| 文件总数（全 vault 递归） | 404 |
| 文档（`.md`）总数 | 336 |
| 旧制待改（日期打头 `.md`） | **239** |
| 不动（已是 topic 打头 / 无日期） | 97 |
| 非 `.md` 资产（png/html/patch/log/…，不在命名制范围） | 68 |
| 换算后新名重名组 | **0** |
| 新名与不动文件撞名 | 0 |
| topic 需从正文重拟 | **0** |
| 缺时间分量（`YYYYMMDD_topic`） | 29 |
| 时间仅 4 位 HHMM | 3 |
| 带 chainId 的旧制文件 | 186 |
| 零入链（orphan）旧制文件 | 62 |
| 被 `index.md` 引用的旧制文件 | 39 |
| 其他项目待迁移（有 `.stand/docs` 的项目数） | **3** |

### 最热被引 top5（入链次数）

| # | 旧文件名 | 入链次数 | 来源文档数 | 前 3 来源 |
| --- | --- | --- | --- | --- |
| 1 | `20261101_165531_dispatcher-chain-lifecycle-rca__kernel.md` | 16 | 5 | `20261102_200551_dispatcher-node-failure-notification-rca__delegate-nebflow-dfab3d21.md`、`20261101_174600_delegate-status-audit__kernel.md`、`20261101_193000_delegate-standby-status-matrix__delegate-nebflow-c6c62891.md` |
| 2 | `20261028_canvas-ask-mode-design.zh-CN.md` | 14 | 9 | `20261029_111029_term-citation-research__delegate-nebflow-253ec234.zh-CN.md`、`20261030_181655_reader-experience-design__delegate-nebflow-38afccc8.zh-CN.md`、`20261030_human-readable-presentation.zh-CN.md` |
| 3 | `20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN.md` | 14 | 8 | `20261029_174545_plugin-remote-source-design__delegate-nebflow-585363ad.zh-CN.md`、`20261031_223533_stand-dev-plugin-merge.md`、`20261102_143609_ptc-mode-two-plugins__delegate-nebflow-226f28aa.md` |
| 4 | `20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN.md` | 14 | 8 | `20261101_141229_unified-button-design__cc28a33e.md`、`20261031_170000_liquid-glass-ab-final__delegate-nebflow-a1031f13.md`、`20261104_143000_design-scale-spec__delegate-nebflow-be8349d0.md` |
| 5 | `20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4.md` | 13 | 12 | `20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN.md`、`20261030_100600_projcreate-device-flat-three-fixes__delegate-nebflow-a97665b1.md`、`20261030_154701_prompt-health-audit__delegate-nebflow-1876a80d.md` |

## 2. 口径与假设

1. **换算方向**：旧 `<YYYYMMDD>_<HHMMSS>_<topic>__<chainId>.md` → 新 `<topic>__<YYYYMMDD>_<HHMMSS>__<chainId>.md`；无 chainId 则无第二段 `__`。
2. **多点扩展名**：`.zh-CN.md` 整体保留在最后（例 `20261030_191500_reader-p0-implementation__delegate-nebflow-a1e28e75.zh-CN.md` → `reader-p0-implementation__20261030_191500__delegate-nebflow-a1e28e75.zh-CN.md`）。
3. **只换日期打头的**：已是 topic 打头的（`index.md` / `glossary.md` / `archive.md` / `design/` 下无日期文件 / `reviews/parity/*.md` / `issues/*.md` / `templates/*.md` 等）一律**不动**，单列 §8。
4. **非 `.md` 资产不在本制范围**：证据截图（`assets/`、`evidence/*/*.png`）、patch、log、html 样张等按原样保留；仅在 §8 附注其数量，避免误改索引里的图片路径。
5. **同主题系列共用 topic 前缀**：本清单只做机械换算（保留原 topic 段），系列归并参考见 §4「同词根候选系列」。
6. **入链口径**：Obsidian wikilink，容忍 `![[…]]`、`[[target|alias]]`、`[[target#锚点]]`、`[[dir/target]]`、省略 `.md` / 省略 `.zh-CN`。入链数 = 链接出现次数（排除自引用），来源文档数单列。
7. **目录名有意偏差**：任务书原文写的落盘目录是旧制 `20261104_doc-naming-migration`，本清单实际落在新制 `doc-naming-migration__20261104_181200/`（目录名自身示范新制，两处不一致是**有意的**）。

## 3. 统计段 A：总量与分布

| 范围 | 文件总数 | `.md` | 旧制待改 | 不动 |
| --- | --- | --- | --- | --- |
| `architecture` | 2 | 2 | 0 | 2 |
| `assets` | 4 | 0 | 0 | 0 |
| `assets/readme` | 7 | 0 | 0 | 0 |
| `attachments` | 6 | 0 | 0 | 0 |
| `attachments/20261104_local-budget-extraction` | 8 | 0 | 0 | 0 |
| `attachments/canvas-ask-mode` | 8 | 0 | 0 | 0 |
| `attachments/wiki-graph-redesign` | 4 | 0 | 0 | 0 |
| `behavior-parity` | 2 | 2 | 0 | 2 |
| `design` | 82 | 71 | 42 | 29 |
| `evidence` | 1 | 1 | 1 | 0 |
| `evidence/20261101_time-wheel-picker__delegate-nebflow-0c50686f` | 8 | 0 | 0 | 0 |
| `evidence/20261101_unified-button-design` | 2 | 0 | 0 | 0 |
| `evidence/20261102_project-unmount` | 7 | 0 | 0 | 0 |
| `issues` | 18 | 18 | 0 | 18 |
| `issues/templates` | 11 | 10 | 0 | 10 |
| `reviews` | 2 | 1 | 0 | 1 |
| `reviews/parity` | 20 | 20 | 0 | 20 |
| `runbooks` | 2 | 2 | 0 | 2 |
| `templates` | 2 | 2 | 0 | 2 |
| `（根）` | 208 | 207 | 196 | 11 |
| **合计** | **404** | **336** | **239** | **97** |

顶层 218 条目里旧制 `.md` 实测 **196** 个（任务书估值约 179，偏低；以实测为准）；其余 43 件旧制在子目录（`design/` 42 + `evidence/` 1），全库旧制合计 **239**。

## 4. 统计段 B：换算后新名重名清单

**新名重名 0 组**——239 个旧制文件的 `(topic, date, time, chainId)` 四元组互不相同，机械换算不产生任何冲突；与「不动」文件也零撞名。

仍需注意的**语义重名**（新制下 topic 段近似、属同主题系列，后续需共用同一 topic 前缀）：

（无 topic 段完全相同的文件。）

同词根候选系列（`-` 前缀相同 ≥3 件，供 topic 归并参考）：

- `mobile*` (17 件)： `mobile-askuser-whitescreen`、`mobile-canvas-ring-tabbar`、`mobile-canvas-topbar-passthrough`、`mobile-chat-blank-hotfix`、`mobile-chat-initial-scroll`、`mobile-chat-topbar-single-row`、`mobile-dock-capsule`、`mobile-fab-unify-search`、`mobile-gesture-tribunal`、`mobile-hig-unification`、`mobile-image-gesture-viewer`、`mobile-panel-title-b`、`mobile-pinned-search`、`mobile-projects-single-column`、`mobile-send-stop-narrowing`、`mobile-topbar-batch`、`mobile-two-stage-title`
- `delegate*` (12 件)： `delegate-actor-evaluation`、`delegate-dual-mode-redesign`、`delegate-finish-semantics`、`delegate-frozen-state-machine`、`delegate-rcpt-diffstat`、`delegate-silent-death-analysis`、`delegate-stall-audit`、`delegate-standby-semantics-audit`、`delegate-standby-status-matrix`、`delegate-status-audit`、`delegate-status-audit-reader`、`delegate-terminal-mailbox-design`
- `canvas*` (10 件)： `canvas-ask-fade-content-opacity`、`canvas-ask-mode-design`、`canvas-ask-mode-tokens`、`canvas-ask-resize-supps`、`canvas-ask-single-row`、`canvas-hover-zero-layout`、`canvas-mobile-overlay-fix`、`canvas-ring-tabbar-align`、`canvas-stack-view`、`canvas-top-tabs-swipe`
- `plugin*` (7 件)： `plugin-panel-layout`、`plugin-panel-uninstall-migration-security`、`plugin-protocol-audit`、`plugin-remote-source-design`、`plugin-repo-registry`、`plugin-selfcontainment-m16-c19`、`plugin-source-hot-update`
- `dispatcher*` (5 件)： `dispatcher-chain-lifecycle-rca`、`dispatcher-node-failure-notification-rca`、`dispatcher-notify-fix-p012`、`dispatcher-notify-p012-closeout`、`dispatcher-seat-prompt-919-doctrine`
- `liquid*` (4 件)： `liquid-glass-ab-final`、`liquid-glass-effect`、`liquid-glass-effect-v2`、`liquid-glass-effect-v3`
- `ustc*` (4 件)： `ustc-cicd-migration-assessment`、`ustc-git-survey`、`ustc-gitlab-cicd-rollout`、`ustc-zhang-merge`
- `process*` (4 件)： `process-artifacts-gate`、`process-leak-fold-roots`、`process-recorder-design`、`process-recorder-f1f2`
- `muse*` (4 件)： `muse-avatar-restore-stack-smartcrop`、`muse-avatar-shrink`、`muse-head-restore`、`muse-head-same-row`
- `reader*` (3 件)： `reader-experience-design`、`reader-p0-implementation`、`reader-p2-large-file`
- `stand*` (3 件)： `stand-dev-methods-design-merge`、`stand-dev-plugin-merge`、`stand-restart-hot-path`
- `output*` (3 件)： `output-block-channel-wire`、`output-block-gate`、`output-block-gate-prompt-face`
- `search*` (3 件)： `search-return-pill`、`search-scope-main-only`、`search-slow-attribution-datalayer-spec`
- `doc*` (3 件)： `doc-and-term-system-survey`、`doc-ask-fade`、`doc-reading-editing-audit`
- `ask*` (3 件)： `ask-input-composer-package`、`ask-readonly-five-tool-redesign`、`ask-readonly-tool-design`
- `project*` (3 件)： `project-device-parity-batches`、`project-device-parity-design`、`project-unmount-console`
- `quote*` (3 件)： `quote-icon-unify`、`quote-iconify`、`quote-identity-badge`
- `profile*` (3 件)： `profile-dual-desc`、`profile-settings-batch-landing`、`profile-settings-closeout`
- `design*` (3 件)： `design-language-recon`、`design-language-reference`、`design-scale-spec`
- `merge*` (3 件)： `merge-lane-state-machine`、`merge-queue-fifo-notify`、`merge-queue-write-ahead-channel-spec`
- `memory*` (3 件)： `memory-audit-and-management`、`memory-dimension-profiles`、`memory-mitigations`
- `term*` (3 件)： `term-ask-mdhtml-gate-batch3`、`term-citation-batch1-spec`、`term-citation-research`

## 5. 统计段 C：topic 需从正文推断重拟

**0 件**——239 个旧制文件的 topic 段全为具体词：无 `report`/`doc`/`notes`/`报告`/`总结`/`汇总` 这类空词，无一长度 ≤ 4 字符，无纯数字/纯符号 topic。机械换算后 topic 段可直接沿用，**本阶段无一条需要从正文重拟**。

### 5.1 边界观察名单（含泛词但不属空词，本阶段保留原 topic）

34 件 topic 里含泛词，但泛词只作**后缀/限定**，主题本身可辨识 ⇒ 不属新制禁止的空词，本阶段保留原 topic。下表列出若后续收紧可采用的「截尾」建议（不强制）：

| # | 旧文件名 | topic 段 | 尾部泛词 | 建议 topic（截尾） |
| --- | --- | --- | --- | --- |
| 1 | `20261026_detached-apply-restart-update-spec.zh-CN.md` | `detached-apply-restart-update-spec` | `spec` | `detached-apply-restart-update` |
| 2 | `20261027_cli-isolation-and-detached-restart-spec.zh-CN.md` | `cli-isolation-and-detached-restart-spec` | `spec` | `cli-isolation-and-detached-restart` |
| 3 | `20261027_hot-reload-foundation-spec.zh-CN.md` | `hot-reload-foundation-spec` | `spec` | `hot-reload-foundation` |
| 4 | `20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4.md` | `审计报告二五机制` | — | `审计报告二五机制` |
| 5 | `20261029_123653_term-citation-batch1-spec__delegate-nebflow-dbfd1ffc.zh-CN.md` | `term-citation-batch1-spec` | `spec` | `term-citation-batch1` |
| 6 | `20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN.md` | `plugin-protocol-audit` | `audit` | `plugin-protocol` |
| 7 | `20261029_171456_term-ask-mdhtml-gate-batch3__delegate-nebflow-8312db45.zh-CN.md` | `term-ask-mdhtml-gate-batch3` | — | `term-ask-mdhtml-gate-batch3` |
| 8 | `20261029_180000_search-slow-attribution-datalayer-spec.zh-CN.md` | `search-slow-attribution-datalayer-spec` | `spec` | `search-slow-attribution-datalayer` |
| 9 | `20261029_1815_model-schedule-unavailable-spec__delegate-nebflow-b3020083.zh-CN.md` | `model-schedule-unavailable-spec` | `spec` | `model-schedule-unavailable` |
| 10 | `20261029_200944_merge-queue-write-ahead-channel-spec__delegate-nebflow-3ad3478e.zh-CN.md` | `merge-queue-write-ahead-channel-spec` | `spec` | `merge-queue-write-ahead-channel` |
| 11 | `20261029_errorfrozen-dialog-minimal-spec.zh-CN.md` | `errorfrozen-dialog-minimal-spec` | `spec` | `errorfrozen-dialog-minimal` |
| 12 | `20261030_154701_prompt-health-audit__delegate-nebflow-1876a80d.md` | `prompt-health-audit` | `audit` | `prompt-health` |
| 13 | `20261030_211200_coldstart-batch-8fixes__delegate-nebflow-7ceafc0d.md` | `coldstart-batch-8fixes` | — | `coldstart-batch-8fixes` |
| 14 | `20261031_084300_social-file-outbound-audit__delegate-nebflow-3001078c.md` | `social-file-outbound-audit` | `audit` | `social-file-outbound` |
| 15 | `20261031_105500_agentcontrol-query-upgrade-spec__delegate-nebflow-b536b26a.zh-CN.md` | `agentcontrol-query-upgrade-spec` | `spec` | `agentcontrol-query-upgrade` |
| 16 | `20261031_112344_delegate-standby-semantics-audit__delegate-nebflow-c0a2b939.md` | `delegate-standby-semantics-audit` | `audit` | `delegate-standby-semantics` |
| 17 | `20261031_161500_mobile-topbar-batch__delegate-nebflow-53e293d1.md` | `mobile-topbar-batch` | `batch` | `mobile-topbar` |
| 18 | `20261031_193000_弹窗退场批__delegate-nebflow-4c0dfd9f.md` | `弹窗退场批` | — | `弹窗退场批` |
| 19 | `20261101_163000_time-wheel-picker-spec__delegate-nebflow-0c50686f.zh-CN.md` | `time-wheel-picker-spec` | `spec` | `time-wheel-picker` |
| 20 | `20261101_172500_doc-reading-editing-audit__delegate-nebflow-582581bb.md` | `doc-reading-editing-audit` | `audit` | `doc-reading-editing` |
| 21 | `20261101_174600_delegate-status-audit__kernel.md` | `delegate-status-audit` | `audit` | `delegate-status` |
| 22 | `20261101_180000_delegate-status-audit-reader.md` | `delegate-status-audit-reader` | — | `delegate-status-audit-reader` |
| 23 | `20261101_193505_prompt-context-refresh-audit__delegate-nebflow-d32fca10.md` | `prompt-context-refresh-audit` | `audit` | `prompt-context-refresh` |
| 24 | `20261102_084500_bootcard-brandicon-and-emoji-audit__delegate-nebflow-f555a161.md` | `bootcard-brandicon-and-emoji-audit` | `audit` | `bootcard-brandicon-and-emoji` |
| 25 | `20261102_121500_host-binding-audit__delegate-nebflow-7d8475af.md` | `host-binding-audit` | `audit` | `host-binding` |
| 26 | `20261102_182500_profile-settings-batch-landing__delegate-nebflow-16c2e54c.md` | `profile-settings-batch-landing` | — | `profile-settings-batch-landing` |
| 27 | `20261104_103020_local-acceleration-audit.md` | `local-acceleration-audit` | `audit` | `local-acceleration` |
| 28 | `20261104_104918_project-device-parity-batches.md` | `project-device-parity-batches` | — | `project-device-parity-batches` |
| 29 | `20261104_115529_actor-fsm-backend-spec__actor-fsm-batch.md` | `actor-fsm-backend-spec` | `spec` | `actor-fsm-backend` |
| 30 | `20261104_130303_memory-audit-and-management.md` | `memory-audit-and-management` | — | `memory-audit-and-management` |
| 31 | `20261104_143000_design-scale-spec__delegate-nebflow-be8349d0.md` | `design-scale-spec` | `spec` | `design-scale` |
| 32 | `20261104_151123_ui-batch-delivery-audit.md` | `ui-batch-delivery-audit` | `audit` | `ui-batch-delivery` |
| 33 | `20261104_152500_ui-final-four-independent-review__ui-final-four.md` | `ui-final-four-independent-review` | `review` | `ui-final-four-independent` |
| 34 | `20261104_164800_delegate-stall-audit__delegate-nebflow-8d6d3cba.md` | `delegate-stall-audit` | `audit` | `delegate-stall` |

> 判定口径：空词 = topic **本身就是** `report`/`doc`/`notes`/`报告`/`总结`/`批` 等，或长度 ≤ 4 字符、或纯数字符号。带具体限定词的复合 topic（如 `审计报告二五机制` = 审计主题落在「二五机制」上、`弹窗退场批` = 弹窗退场）按具体词处理。
> 两条中文 entry 需人工注意：`审计报告二五机制`（前缀是泛词、后缀是具体词，保留）、`弹窗退场批`（后缀是量词「批」，建议新制下写作 `弹窗退场`）。

## 6. 统计段 D：时间分量异常（换算前必须补值）

### 6.1 缺时间分量（29 件）：`YYYYMMDD_<topic>.md`

新制要求 `HHMMSS` 分量。本清单换算结果按 `000000` 占位展示，**真值需补**：建议取文件首行/git 首次提交时间，或由作者统一裁定为 `000000`（表示「日级精度」）。

| # | 旧路径 | 占位新名 |
| --- | --- | --- |
| 1 | `20261030_cmdcode-provider-removal.md` | `cmdcode-provider-removal__20261030_000000.md` |
| 2 | `20261030_imagegen-blocking-semantics.md` | `imagegen-blocking-semantics__20261030_000000.md` |
| 3 | `20261031_mobile-askuser-whitescreen.md` | `mobile-askuser-whitescreen__20261031_000000.md` |
| 4 | `20261031_owner-lost.md` | `owner-lost__20261031_000000.md` |
| 5 | `20261031_portal-session-shortlife-rootfix.md` | `portal-session-shortlife-rootfix__20261031_000000.md` |
| 6 | `20261102_bg-panel-single-scroll__delegate-nebflow-ef2390e1.md` | `bg-panel-single-scroll__20261102_000000__delegate-nebflow-ef2390e1.md` |
| 7 | `20261102_canvas-ring-tabbar-align__delegate-nebflow-ea997ea1.md` | `canvas-ring-tabbar-align__20261102_000000__delegate-nebflow-ea997ea1.md` |
| 8 | `20261102_center-tab-no-fade__delegate-nebflow-e1c239be.md` | `center-tab-no-fade__20261102_000000__delegate-nebflow-e1c239be.md` |
| 9 | `20261102_mobile-pinned-search__delegate-nebflow-43af6929.md` | `mobile-pinned-search__20261102_000000__delegate-nebflow-43af6929.md` |
| 10 | `20261102_muse-avatar-shrink__delegate-nebflow-d616c46a.md` | `muse-avatar-shrink__20261102_000000__delegate-nebflow-d616c46a.md` |
| 11 | `20261102_muse-head-restore__delegate-nebflow-accdcd3b.md` | `muse-head-restore__20261102_000000__delegate-nebflow-accdcd3b.md` |
| 12 | `20261102_muse-head-same-row__delegate-nebflow-8c0b5ca5.md` | `muse-head-same-row__20261102_000000__delegate-nebflow-8c0b5ca5.md` |
| 13 | `20261102_plugin-panel-layout__delegate-nebflow-f1fc2f78.md` | `plugin-panel-layout__20261102_000000__delegate-nebflow-f1fc2f78.md` |
| 14 | `20261102_title-capsule-height__delegate-nebflow-f003855e.md` | `title-capsule-height__20261102_000000__delegate-nebflow-f003855e.md` |
| 15 | `20261102_todo-capsule__delegate-nebflow-35eeaf1e.md` | `todo-capsule__20261102_000000__delegate-nebflow-35eeaf1e.md` |
| 16 | `20261104_time-wheel-picker-implementation__delegate-nebflow-4bff07ce.md` | `time-wheel-picker-implementation__20261104_000000__delegate-nebflow-4bff07ce.md` |
| 17 | `design/20260928_nodeprogress-node-task-card.md` | `nodeprogress-node-task-card__20260928_000000.md` |
| 18 | `design/20261026_detached-apply-restart-update-spec.zh-CN.md` | `detached-apply-restart-update-spec__20261026_000000.zh-CN.md` |
| 19 | `design/20261027_cli-isolation-and-detached-restart-spec.zh-CN.md` | `cli-isolation-and-detached-restart-spec__20261027_000000.zh-CN.md` |
| 20 | `design/20261027_hot-reload-foundation-spec.zh-CN.md` | `hot-reload-foundation-spec__20261027_000000.zh-CN.md` |
| 21 | `design/20261028_canvas-ask-mode-design.zh-CN.md` | `canvas-ask-mode-design__20261028_000000.zh-CN.md` |
| 22 | `design/20261028_canvas-ask-mode-tokens.zh-CN.md` | `canvas-ask-mode-tokens__20261028_000000.zh-CN.md` |
| 23 | `design/20261028_triple-line-integration-contract.zh-CN.md` | `triple-line-integration-contract__20261028_000000.zh-CN.md` |
| 24 | `design/20261029_errorfrozen-dialog-minimal-spec.zh-CN.md` | `errorfrozen-dialog-minimal-spec__20261029_000000.zh-CN.md` |
| 25 | `design/20261030_background-task-lifecycle.zh-CN.md` | `background-task-lifecycle__20261030_000000.zh-CN.md` |
| 26 | `design/20261030_human-readable-presentation.zh-CN.md` | `human-readable-presentation__20261030_000000.zh-CN.md` |
| 27 | `design/20261030_memory-dimension-profiles.zh-CN.md` | `memory-dimension-profiles__20261030_000000.zh-CN.md` |
| 28 | `design/20261030_merge-lane-state-machine.zh-CN.md` | `merge-lane-state-machine__20261030_000000.zh-CN.md` |
| 29 | `design/20261030_orchestration-execution-split.zh-CN.md` | `orchestration-execution-split__20261030_000000.zh-CN.md` |

### 6.2 时间仅 4 位 `HHMM`（3 件）

新制要求 6 位 `HHMMSS`。本清单**原样保留 4 位**（不臆造秒），建议补 `00` 或核对原文时间。

| # | 旧路径 | 4 位换算新名 | 建议新名 |
| --- | --- | --- | --- |
| 1 | `20261102_2010_profile-settings-closeout__delegate-nebflow-16c2e54c.md` | `profile-settings-closeout__20261102_2010__delegate-nebflow-16c2e54c.md` | `profile-settings-closeout__20261102_201000__delegate-nebflow-16c2e54c.md` |
| 2 | `design/20261029_1815_model-schedule-unavailable-spec__delegate-nebflow-b3020083.zh-CN.md` | `model-schedule-unavailable-spec__20261029_1815__delegate-nebflow-b3020083.zh-CN.md` | `model-schedule-unavailable-spec__20261029_181500__delegate-nebflow-b3020083.zh-CN.md` |
| 3 | `evidence/20260929_0200_brief-transport-silent-truncation.md` | `brief-transport-silent-truncation__20260929_0200.md` | `brief-transport-silent-truncation__20260929_020000.md` |

## 7. 逐行迁移表（旧制 239 件 → 新名）

列说明：`chainId` = 文件名 `__<chainId>` 段（`—` = 无）；`入链` = 全库 wikilink 指向该文件的次数（`来源数` = 不同来源文档数）；`前 3 来源` = 按引用次数降序；`index.md` = 主索引是否直接链接。

| # | 目录 | 旧文件名 | → 新文件名 | chainId | 入链 | 来源数 | 前 3 来源 | index.md | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `design` | `20260928_nodeprogress-node-task-card.md` | `nodeprogress-node-task-card__20260928_000000.md` | — | 1 | 1 | `archive.md` | — | 缺时间分量 |
| 2 | `design` | `20261026_detached-apply-restart-update-spec.zh-CN.md` | `detached-apply-restart-update-spec__20261026_000000.zh-CN.md` | — | 4 | 3 | `20261030_background-task-lifecycle.zh-CN.md`<br>`20261027_cli-isolation-and-detached-restart-spec.zh-CN.md`<br>`20261027_hot-reload-foundation-spec.zh-CN.md` | — | 缺时间分量 |
| 3 | `design` | `20261027_cli-isolation-and-detached-restart-spec.zh-CN.md` | `cli-isolation-and-detached-restart-spec__20261027_000000.zh-CN.md` | — | 7 | 6 | `20261030_background-task-lifecycle.zh-CN.md`<br>`20261103_085500_dual-root-split-nebflow-home__delegate-nebflow-a3ca2bc7.md`<br>`20261026_detached-apply-restart-update-spec.zh-CN.md` | — | 缺时间分量 |
| 4 | `design` | `20261027_hot-reload-foundation-spec.zh-CN.md` | `hot-reload-foundation-spec__20261027_000000.zh-CN.md` | — | 2 | 2 | `20261026_detached-apply-restart-update-spec.zh-CN.md`<br>`20261027_cli-isolation-and-detached-restart-spec.zh-CN.md` | — | 缺时间分量 |
| 5 | `design` | `20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN.md` | `delegate-dual-mode-redesign__20261028_213056__delegate-nebflow-192d10a5.zh-CN.md` | `delegate-nebflow-192d10a5` | 11 | 7 | `20261101_101138_dispatcher-seat-prompt-919-doctrine__delegate-nebflow-dd0d529e.zh-CN.md`<br>`20261102_200551_dispatcher-node-failure-notification-rca__delegate-nebflow-dfab3d21.md`<br>`20261030_orchestration-execution-split.zh-CN.md` | ✅ |  |
| 6 | `design` | `20261028_215449_thinking-config-research__delegate-nebflow-ca5f09f4.zh-CN.md` | `thinking-config-research__20261028_215449__delegate-nebflow-ca5f09f4.zh-CN.md` | `delegate-nebflow-ca5f09f4` | 1 | 1 | `index.md` | ✅ |  |
| 7 | `design` | `20261028_canvas-ask-mode-design.zh-CN.md` | `canvas-ask-mode-design__20261028_000000.zh-CN.md` | — | 14 | 9 | `20261029_111029_term-citation-research__delegate-nebflow-253ec234.zh-CN.md`<br>`20261030_181655_reader-experience-design__delegate-nebflow-38afccc8.zh-CN.md`<br>`20261030_human-readable-presentation.zh-CN.md` | ✅ | 缺时间分量 |
| 8 | `design` | `20261028_canvas-ask-mode-tokens.zh-CN.md` | `canvas-ask-mode-tokens__20261028_000000.zh-CN.md` | — | 3 | 3 | `20261103_004146_design-language-reference__delegate-nebflow-878e1617.zh-CN.md`<br>`20261028_canvas-ask-mode-design.zh-CN.md`<br>`index.md` | ✅ | 缺时间分量 |
| 9 | `design` | `20261028_triple-line-integration-contract.zh-CN.md` | `triple-line-integration-contract__20261028_000000.zh-CN.md` | — | 4 | 1 | `20261030_orchestration-execution-split.zh-CN.md` | — | 缺时间分量 |
| 10 | `design` | `20261029_111029_term-citation-research__delegate-nebflow-253ec234.zh-CN.md` | `term-citation-research__20261029_111029__delegate-nebflow-253ec234.zh-CN.md` | `delegate-nebflow-253ec234` | 0 | 0 | — | — |  |
| 11 | `design` | `20261029_123653_term-citation-batch1-spec__delegate-nebflow-dbfd1ffc.zh-CN.md` | `term-citation-batch1-spec__20261029_123653__delegate-nebflow-dbfd1ffc.zh-CN.md` | `delegate-nebflow-dbfd1ffc` | 5 | 2 | `20261030_human-readable-presentation.zh-CN.md`<br>`20261028_canvas-ask-mode-design.zh-CN.md` | — |  |
| 12 | `design` | `20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN.md` | `plugin-protocol-audit__20261029_131610__delegate-nebflow-14e75897.zh-CN.md` | `delegate-nebflow-14e75897` | 14 | 8 | `20261029_174545_plugin-remote-source-design__delegate-nebflow-585363ad.zh-CN.md`<br>`20261031_223533_stand-dev-plugin-merge.md`<br>`20261102_143609_ptc-mode-two-plugins__delegate-nebflow-226f28aa.md` | ✅ |  |
| 13 | `design` | `20261029_132723_perception-unified-daemon__delegate-nebflow-0b171af0.zh-CN.md` | `perception-unified-daemon__20261029_132723__delegate-nebflow-0b171af0.zh-CN.md` | `delegate-nebflow-0b171af0` | 0 | 0 | — | — |  |
| 14 | `design` | `20261029_171456_term-ask-mdhtml-gate-batch3__delegate-nebflow-8312db45.zh-CN.md` | `term-ask-mdhtml-gate-batch3__20261029_171456__delegate-nebflow-8312db45.zh-CN.md` | `delegate-nebflow-8312db45` | 2 | 2 | `20261031_113700_ask-readonly-tool-design__delegate-nebflow-09ef7b2e.zh-CN.md`<br>`index.md` | ✅ |  |
| 15 | `design` | `20261029_174545_plugin-remote-source-design__delegate-nebflow-585363ad.zh-CN.md` | `plugin-remote-source-design__20261029_174545__delegate-nebflow-585363ad.zh-CN.md` | `delegate-nebflow-585363ad` | 2 | 2 | `20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN.md`<br>`index.md` | ✅ |  |
| 16 | `design` | `20261029_180000_search-slow-attribution-datalayer-spec.zh-CN.md` | `search-slow-attribution-datalayer-spec__20261029_180000.zh-CN.md` | — | 0 | 0 | — | — |  |
| 17 | `design` | `20261029_1815_model-schedule-unavailable-spec__delegate-nebflow-b3020083.zh-CN.md` | `model-schedule-unavailable-spec__20261029_1815__delegate-nebflow-b3020083.zh-CN.md` | `delegate-nebflow-b3020083` | 3 | 1 | `20261101_163000_time-wheel-picker-spec__delegate-nebflow-0c50686f.zh-CN.md` | — | 时间仅 4 位 HHMM |
| 18 | `design` | `20261029_190500_plugin-panel-uninstall-migration-security__delegate-nebflow-4ccea858.zh-CN.md` | `plugin-panel-uninstall-migration-security__20261029_190500__delegate-nebflow-4ccea858.zh-CN.md` | `delegate-nebflow-4ccea858` | 0 | 0 | — | — |  |
| 19 | `design` | `20261029_200944_merge-queue-write-ahead-channel-spec__delegate-nebflow-3ad3478e.zh-CN.md` | `merge-queue-write-ahead-channel-spec__20261029_200944__delegate-nebflow-3ad3478e.zh-CN.md` | `delegate-nebflow-3ad3478e` | 2 | 2 | `20261101_083400_delegate-silent-death-analysis__delegate-nebflow-b618531a.md`<br>`20261101_090600_delegate-terminal-mailbox-design__delegate-nebflow-4a58b3db.zh-CN.md` | — |  |
| 20 | `design` | `20261029_errorfrozen-dialog-minimal-spec.zh-CN.md` | `errorfrozen-dialog-minimal-spec__20261029_000000.zh-CN.md` | — | 0 | 0 | — | — | 缺时间分量 |
| 21 | `design` | `20261030_181655_reader-experience-design__delegate-nebflow-38afccc8.zh-CN.md` | `reader-experience-design__20261030_181655__delegate-nebflow-38afccc8.zh-CN.md` | `delegate-nebflow-38afccc8` | 5 | 3 | `20261030_190200_reader-p2-large-file__delegate-nebflow-f8da1d4a.md`<br>`20261030_191500_reader-p0-implementation__delegate-nebflow-a1e28e75.zh-CN.md`<br>`20261101_172500_doc-reading-editing-audit__delegate-nebflow-582581bb.md` | — |  |
| 22 | `design` | `20261030_201846_button-system-monochrome-redesign__delegate-nebflow-fa3d4d6f.zh-CN.md` | `button-system-monochrome-redesign__20261030_201846__delegate-nebflow-fa3d4d6f.zh-CN.md` | `delegate-nebflow-fa3d4d6f` | 7 | 3 | `20261031_082100_liquid-glass-effect__delegate-nebflow-764d8fb0.zh-CN.md`<br>`20261101_170223_toggle-green-revert__delegate-nebflow-16e74801.md`<br>`20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN.md` | — |  |
| 23 | `design` | `20261030_background-task-lifecycle.zh-CN.md` | `background-task-lifecycle__20261030_000000.zh-CN.md` | — | 4 | 4 | `20261030_190200_reader-p2-large-file__delegate-nebflow-f8da1d4a.md`<br>`20261030_human-readable-presentation.zh-CN.md`<br>`20261030_memory-dimension-profiles.zh-CN.md` | — | 缺时间分量 |
| 24 | `design` | `20261030_human-readable-presentation.zh-CN.md` | `human-readable-presentation__20261030_000000.zh-CN.md` | — | 7 | 4 | `20261030_memory-dimension-profiles.zh-CN.md`<br>`20261030_201846_button-system-monochrome-redesign__delegate-nebflow-fa3d4d6f.zh-CN.md`<br>`20261101_121500_output-block-channel-wire__delegate-nebflow-8f4a92ec.md` | — | 缺时间分量 |
| 25 | `design` | `20261030_memory-dimension-profiles.zh-CN.md` | `memory-dimension-profiles__20261030_000000.zh-CN.md` | — | 1 | 1 | `20261030_204500_boot-render-gate__delegate-nebflow-ca1a7b93.md` | — | 缺时间分量 |
| 26 | `design` | `20261030_merge-lane-state-machine.zh-CN.md` | `merge-lane-state-machine__20261030_000000.zh-CN.md` | — | 7 | 5 | `20261030_background-task-lifecycle.zh-CN.md`<br>`20261030_orchestration-execution-split.zh-CN.md`<br>`20261101_083400_delegate-silent-death-analysis__delegate-nebflow-b618531a.md` | — | 缺时间分量 |
| 27 | `design` | `20261030_orchestration-execution-split.zh-CN.md` | `orchestration-execution-split__20261030_000000.zh-CN.md` | — | 2 | 1 | `20261102_143609_ptc-mode-two-plugins__delegate-nebflow-226f28aa.md` | — | 缺时间分量 |
| 28 | `design` | `20261031_082100_liquid-glass-effect__delegate-nebflow-764d8fb0.zh-CN.md` | `liquid-glass-effect__20261031_082100__delegate-nebflow-764d8fb0.zh-CN.md` | `delegate-nebflow-764d8fb0` | 3 | 1 | `20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN.md` | — |  |
| 29 | `design` | `20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN.md` | `liquid-glass-effect-v2__20261031_084400__delegate-nebflow-a9ba5eff.zh-CN.md` | `delegate-nebflow-a9ba5eff` | 14 | 8 | `20261101_141229_unified-button-design__cc28a33e.md`<br>`20261031_170000_liquid-glass-ab-final__delegate-nebflow-a1031f13.md`<br>`20261104_143000_design-scale-spec__delegate-nebflow-be8349d0.md` | — |  |
| 30 | `design` | `20261031_105500_agentcontrol-query-upgrade-spec__delegate-nebflow-b536b26a.zh-CN.md` | `agentcontrol-query-upgrade-spec__20261031_105500__delegate-nebflow-b536b26a.zh-CN.md` | `delegate-nebflow-b536b26a` | 1 | 1 | `20261031_105500_agentcontrol-query-upgrade__delegate-nebflow-b536b26a.zh-CN.md` | — |  |
| 31 | `design` | `20261031_105500_agentcontrol-query-upgrade__delegate-nebflow-b536b26a.zh-CN.md` | `agentcontrol-query-upgrade__20261031_105500__delegate-nebflow-b536b26a.zh-CN.md` | `delegate-nebflow-b536b26a` | 1 | 1 | `20261031_105500_agentcontrol-query-upgrade-spec__delegate-nebflow-b536b26a.zh-CN.md` | — |  |
| 32 | `design` | `20261031_113700_ask-readonly-tool-design__delegate-nebflow-09ef7b2e.zh-CN.md` | `ask-readonly-tool-design__20261031_113700__delegate-nebflow-09ef7b2e.zh-CN.md` | `delegate-nebflow-09ef7b2e` | 2 | 2 | `20261028_canvas-ask-mode-design.zh-CN.md`<br>`20261031_124045_ask-readonly-five-tool-redesign__delegate-nebflow-6cf27f86.zh-CN.md` | — |  |
| 33 | `design` | `20261031_121500_liquid-glass-effect-v3__delegate-nebflow-9e155df5.zh-CN.md` | `liquid-glass-effect-v3__20261031_121500__delegate-nebflow-9e155df5.zh-CN.md` | `delegate-nebflow-9e155df5` | 4 | 2 | `20261101_141229_unified-button-design__cc28a33e.md`<br>`20261031_170000_liquid-glass-ab-final__delegate-nebflow-a1031f13.md` | — |  |
| 34 | `design` | `20261031_124045_ask-readonly-five-tool-redesign__delegate-nebflow-6cf27f86.zh-CN.md` | `ask-readonly-five-tool-redesign__20261031_124045__delegate-nebflow-6cf27f86.zh-CN.md` | `delegate-nebflow-6cf27f86` | 1 | 1 | `20261031_113700_ask-readonly-tool-design__delegate-nebflow-09ef7b2e.zh-CN.md` | — |  |
| 35 | `design` | `20261031_213900_dual-brand-port-separation__delegate-nebflow-51b364b1.zh-CN.md` | `dual-brand-port-separation__20261031_213900__delegate-nebflow-51b364b1.zh-CN.md` | `delegate-nebflow-51b364b1` | 1 | 1 | `kai-stand-migration.md` | — |  |
| 36 | `design` | `20261101_090600_delegate-terminal-mailbox-design__delegate-nebflow-4a58b3db.zh-CN.md` | `delegate-terminal-mailbox-design__20261101_090600__delegate-nebflow-4a58b3db.zh-CN.md` | `delegate-nebflow-4a58b3db` | 1 | 1 | `20261101_174600_delegate-status-audit__kernel.md` | — |  |
| 37 | `design` | `20261101_141229_unified-button-design__cc28a33e.md` | `unified-button-design__20261101_141229__cc28a33e.md` | `cc28a33e` | 4 | 2 | `20261104_143000_design-scale-spec__delegate-nebflow-be8349d0.md`<br>`20261101_162000_cards-family-design__delegate-nebflow-67f23d42.zh-CN.md` | — |  |
| 38 | `design` | `20261101_162000_cards-family-design__delegate-nebflow-67f23d42.zh-CN.md` | `cards-family-design__20261101_162000__delegate-nebflow-67f23d42.zh-CN.md` | `delegate-nebflow-67f23d42` | 2 | 2 | `20261102_084500_bootcard-brandicon-and-emoji-audit__delegate-nebflow-f555a161.md`<br>`20261102_103000_stackcard-ref-and-synccard-center__delegate-nebflow-def82876.md` | — |  |
| 39 | `design` | `20261101_163000_time-wheel-picker-spec__delegate-nebflow-0c50686f.zh-CN.md` | `time-wheel-picker-spec__20261101_163000__delegate-nebflow-0c50686f.zh-CN.md` | `delegate-nebflow-0c50686f` | 3 | 2 | `20261104_time-wheel-picker-implementation__delegate-nebflow-4bff07ce.md`<br>`index.md` | ✅ |  |
| 40 | `design` | `20261101_192000_process-recorder-design__delegate-nebflow-24e5c4c3.md` | `process-recorder-design__20261101_192000__delegate-nebflow-24e5c4c3.md` | `delegate-nebflow-24e5c4c3` | 1 | 1 | `20261101_203500_process-recorder-f1f2__delegate-nebflow-bf078e5e.md` | — |  |
| 41 | `design` | `20261102_084500_cursor-tab-intent-suggestion-design__delegate-nebflow-18a4e786.md` | `cursor-tab-intent-suggestion-design__20261102_084500__delegate-nebflow-18a4e786.md` | `delegate-nebflow-18a4e786` | 0 | 0 | — | — |  |
| 42 | `design` | `20261102_090600_fleet-unified-update__kernel.md` | `fleet-unified-update__20261102_090600__kernel.md` | `kernel` | 0 | 0 | — | — |  |
| 43 | `evidence` | `20260929_0200_brief-transport-silent-truncation.md` | `brief-transport-silent-truncation__20260929_0200.md` | — | 1 | 1 | `archive.md` | — | 时间仅 4 位 HHMM |
| 44 | `（根）` | `20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4.md` | `审计报告二五机制__20261028_171800__delegate-nebflow-a13975e4.md` | `delegate-nebflow-a13975e4` | 13 | 12 | `20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN.md`<br>`20261030_100600_projcreate-device-flat-three-fixes__delegate-nebflow-a97665b1.md`<br>`20261030_154701_prompt-health-audit__delegate-nebflow-1876a80d.md` | ✅ |  |
| 45 | `（根）` | `20261029_113217_外部md渲染器锚点与hover预览调研__flow-7c703875.md` | `外部md渲染器锚点与hover预览调研__20261029_113217__flow-7c703875.md` | `flow-7c703875` | 3 | 2 | `20261102_213000_fm-md-social__delegate-nebflow-f4d3e0f8.md`<br>`20261104_101500_isolated-instance-kill__delegate-nebflow-e7c1a868.md` | — |  |
| 46 | `（根）` | `20261029_140000_mobile-projects-single-column__delegate-nebflow-ea10c90a.md` | `mobile-projects-single-column__20261029_140000__delegate-nebflow-ea10c90a.md` | `delegate-nebflow-ea10c90a` | 2 | 2 | `20261030_211200_coldstart-batch-8fixes__delegate-nebflow-7ceafc0d.md`<br>`20261031_161500_mobile-topbar-batch__delegate-nebflow-53e293d1.md` | — |  |
| 47 | `（根）` | `20261029_175151_plugin-repo-registry.md` | `plugin-repo-registry__20261029_175151.md` | — | 7 | 6 | `20261101_151034_ustc-cicd-migration-assessment__delegate-nebflow-7f8e8dec.md`<br>`20261030_cmdcode-provider-removal.md`<br>`20261101_230000_plugin-source-hot-update__delegate-nebflow-4dfd4b86.md` | ✅ |  |
| 48 | `（根）` | `20261029_221500_jev-imagegen-test-buttons__delegate-nebflow-48dfd701.md` | `jev-imagegen-test-buttons__20261029_221500__delegate-nebflow-48dfd701.md` | `delegate-nebflow-48dfd701` | 0 | 0 | — | — |  |
| 49 | `（根）` | `20261030_084500_dock-message-badge__delegate-nebflow-12dfb591.md` | `dock-message-badge__20261030_084500__delegate-nebflow-12dfb591.md` | `delegate-nebflow-12dfb591` | 4 | 4 | `20261031_160300_mobile-fab-unify-search.md`<br>`20261031_161500_mobile-topbar-batch__delegate-nebflow-53e293d1.md`<br>`20261101_082000_phone-access-entry-removal-reverify__delegate-nebflow-956a86c8.md` | — |  |
| 50 | `（根）` | `20261030_092600_preset-chain-migration__delegate-nebflow-acc72f03.md` | `preset-chain-migration__20261030_092600__delegate-nebflow-acc72f03.md` | `delegate-nebflow-acc72f03` | 4 | 3 | `20261102_143609_ptc-mode-two-plugins__delegate-nebflow-226f28aa.md`<br>`20261104_095200_flysin_provider_error_recon.md`<br>`20261104_101500_isolated-instance-kill__delegate-nebflow-e7c1a868.md` | — |  |
| 51 | `（根）` | `20261030_100600_projcreate-device-flat-three-fixes__delegate-nebflow-a97665b1.md` | `projcreate-device-flat-three-fixes__20261030_100600__delegate-nebflow-a97665b1.md` | `delegate-nebflow-a97665b1` | 3 | 3 | `20261031_093600_device-peer-mobile-chat-windows__delegate-nebflow-002f6988.md`<br>`20261031_160300_mobile-fab-unify-search.md`<br>`20261101_203500_project-unmount-console__delegate-nebflow-a5e474bc.md` | — |  |
| 52 | `（根）` | `20261030_131012_web-frontend-todo-inbox-recon__flow-19b831b3.md` | `web-frontend-todo-inbox-recon__20261030_131012__flow-19b831b3.md` | `flow-19b831b3` | 0 | 0 | — | — |  |
| 53 | `（根）` | `20261030_131201_todo-inbox-recon__flow-19b831b3.md` | `todo-inbox-recon__20261030_131201__flow-19b831b3.md` | `flow-19b831b3` | 0 | 0 | — | — |  |
| 54 | `（根）` | `20261030_154701_prompt-health-audit__delegate-nebflow-1876a80d.md` | `prompt-health-audit__20261030_154701__delegate-nebflow-1876a80d.md` | `delegate-nebflow-1876a80d` | 0 | 0 | — | — |  |
| 55 | `（根）` | `20261030_190200_reader-p2-large-file__delegate-nebflow-f8da1d4a.md` | `reader-p2-large-file__20261030_190200__delegate-nebflow-f8da1d4a.md` | `delegate-nebflow-f8da1d4a` | 1 | 1 | `20261101_172500_doc-reading-editing-audit__delegate-nebflow-582581bb.md` | — |  |
| 56 | `（根）` | `20261030_191500_reader-p0-implementation__delegate-nebflow-a1e28e75.zh-CN.md` | `reader-p0-implementation__20261030_191500__delegate-nebflow-a1e28e75.zh-CN.md` | `delegate-nebflow-a1e28e75` | 2 | 2 | `20261030_202253_chat-search-convo-only-kind-colors__delegate-nebflow-115ab498.md`<br>`20261101_172500_doc-reading-editing-audit__delegate-nebflow-582581bb.md` | — |  |
| 57 | `（根）` | `20261030_202253_chat-search-convo-only-kind-colors__delegate-nebflow-115ab498.md` | `chat-search-convo-only-kind-colors__20261030_202253__delegate-nebflow-115ab498.md` | `delegate-nebflow-115ab498` | 2 | 1 | `20261031_160300_mobile-fab-unify-search.md` | — |  |
| 58 | `（根）` | `20261030_204500_boot-render-gate__delegate-nebflow-ca1a7b93.md` | `boot-render-gate__20261030_204500__delegate-nebflow-ca1a7b93.md` | `delegate-nebflow-ca1a7b93` | 2 | 2 | `20261102_084500_bootcard-brandicon-and-emoji-audit__delegate-nebflow-f555a161.md`<br>`20261104_144555_msg-footer-geometry__ui-final-four.md` | — |  |
| 59 | `（根）` | `20261030_211200_coldstart-batch-8fixes__delegate-nebflow-7ceafc0d.md` | `coldstart-batch-8fixes__20261030_211200__delegate-nebflow-7ceafc0d.md` | `delegate-nebflow-7ceafc0d` | 2 | 2 | `20261031_074500_login-expiry-singleflight__delegate-nebflow-25c36738.md`<br>`20261031_084300_social-file-outbound-audit__delegate-nebflow-3001078c.md` | — |  |
| 60 | `（根）` | `20261030_cmdcode-provider-removal.md` | `cmdcode-provider-removal__20261030_000000.md` | — | 7 | 5 | `20261030_092600_preset-chain-migration__delegate-nebflow-acc72f03.md`<br>`20261030_imagegen-blocking-semantics.md`<br>`20261030_154701_prompt-health-audit__delegate-nebflow-1876a80d.md` | — | 缺时间分量 |
| 61 | `（根）` | `20261030_imagegen-blocking-semantics.md` | `imagegen-blocking-semantics__20261030_000000.md` | — | 0 | 0 | — | — | 缺时间分量 |
| 62 | `（根）` | `20261031_074500_login-expiry-singleflight__delegate-nebflow-25c36738.md` | `login-expiry-singleflight__20261031_074500__delegate-nebflow-25c36738.md` | `delegate-nebflow-25c36738` | 2 | 2 | `20261031_084300_social-file-outbound-audit__delegate-nebflow-3001078c.md`<br>`20261102_130500_profile-dual-desc__delegate-nebflow-16c2e54c.md` | — |  |
| 63 | `（根）` | `20261031_084300_social-file-outbound-audit__delegate-nebflow-3001078c.md` | `social-file-outbound-audit__20261031_084300__delegate-nebflow-3001078c.md` | `delegate-nebflow-3001078c` | 1 | 1 | `20261104_174558_fm-media-bubble-color-unify__bubble-color-unify.md` | — |  |
| 64 | `（根）` | `20261031_093600_device-peer-mobile-chat-windows__delegate-nebflow-002f6988.md` | `device-peer-mobile-chat-windows__20261031_093600__delegate-nebflow-002f6988.md` | `delegate-nebflow-002f6988` | 2 | 2 | `20261031_112344_delegate-standby-semantics-audit__delegate-nebflow-c0a2b939.md`<br>`20261102_171500_neblink-device-identity-and-ownership.md` | — |  |
| 65 | `（根）` | `20261031_112344_delegate-standby-semantics-audit__delegate-nebflow-c0a2b939.md` | `delegate-standby-semantics-audit__20261031_112344__delegate-nebflow-c0a2b939.md` | `delegate-nebflow-c0a2b939` | 8 | 7 | `20261104_164800_delegate-stall-audit__delegate-nebflow-8d6d3cba.md`<br>`20261031_123300_bg-panel-hier-name-split__delegate-nebflow-4cbce603.md`<br>`20261101_083400_delegate-silent-death-analysis__delegate-nebflow-b618531a.md` | — |  |
| 66 | `（根）` | `20261031_123300_bg-panel-hier-name-split__delegate-nebflow-4cbce603.md` | `bg-panel-hier-name-split__20261031_123300__delegate-nebflow-4cbce603.md` | `delegate-nebflow-4cbce603` | 3 | 3 | `20261031_193000_弹窗退场批__delegate-nebflow-4c0dfd9f.md`<br>`20261031_235200_bg-badge-homecoming__delegate-nebflow-7f69c44f.md`<br>`20261031_owner-lost.md` | — |  |
| 67 | `（根）` | `20261031_160300_mobile-fab-unify-search.md` | `mobile-fab-unify-search__20261031_160300.md` | — | 10 | 8 | `20261101_172500_mobile-panel-title-b__delegate-nebflow-0c24df31.md`<br>`20261102_mobile-pinned-search__delegate-nebflow-43af6929.md`<br>`20261031_193000_弹窗退场批__delegate-nebflow-4c0dfd9f.md` | — |  |
| 68 | `（根）` | `20261031_161500_mobile-topbar-batch__delegate-nebflow-53e293d1.md` | `mobile-topbar-batch__20261031_161500__delegate-nebflow-53e293d1.md` | `delegate-nebflow-53e293d1` | 3 | 3 | `20261031_193000_弹窗退场批__delegate-nebflow-4c0dfd9f.md`<br>`20261031_220500_desktop-headclose-left__delegate-nebflow-87cbdee0.md`<br>`20261101_151543_mobile-gesture-tribunal__delegate-nebflow-2a780c56.md` | — |  |
| 69 | `（根）` | `20261031_170000_liquid-glass-ab-final__delegate-nebflow-a1031f13.md` | `liquid-glass-ab-final__20261031_170000__delegate-nebflow-a1031f13.md` | `delegate-nebflow-a1031f13` | 5 | 3 | `20261101_141229_unified-button-design__cc28a33e.md`<br>`20261031_193000_弹窗退场批__delegate-nebflow-4c0dfd9f.md`<br>`20261031_220500_desktop-headclose-left__delegate-nebflow-87cbdee0.md` | — |  |
| 70 | `（根）` | `20261031_193000_弹窗退场批__delegate-nebflow-4c0dfd9f.md` | `弹窗退场批__20261031_193000__delegate-nebflow-4c0dfd9f.md` | `delegate-nebflow-4c0dfd9f` | 3 | 3 | `20261031_220500_desktop-headclose-left__delegate-nebflow-87cbdee0.md`<br>`20261101_151543_mobile-gesture-tribunal__delegate-nebflow-2a780c56.md`<br>`20261101_171500_search-return-pill__delegate-nebflow-9fcc3f05.md` | — |  |
| 71 | `（根）` | `20261031_215855_smtp-send-timeout-hardening__delegate-nebflow-32dfdadb.md` | `smtp-send-timeout-hardening__20261031_215855__delegate-nebflow-32dfdadb.md` | `delegate-nebflow-32dfdadb` | 1 | 1 | `20261101_195800_mail-connect-root-cause__delegate-nebflow-57bbf19e.md` | — |  |
| 72 | `（根）` | `20261031_220500_desktop-headclose-left__delegate-nebflow-87cbdee0.md` | `desktop-headclose-left__20261031_220500__delegate-nebflow-87cbdee0.md` | `delegate-nebflow-87cbdee0` | 0 | 0 | — | — |  |
| 73 | `（根）` | `20261031_223533_stand-dev-plugin-merge.md` | `stand-dev-plugin-merge__20261031_223533.md` | — | 6 | 4 | `20261101_143000_stand-dev-methods-design-merge__delegate-nebflow-cc246cd7.md`<br>`20261101_230000_plugin-source-hot-update__delegate-nebflow-4dfd4b86.md`<br>`20261101_180900_search-scope-main-only__delegate-nebflow-e9fe550d.md` | — |  |
| 74 | `（根）` | `20261031_235200_bg-badge-homecoming__delegate-nebflow-7f69c44f.md` | `bg-badge-homecoming__20261031_235200__delegate-nebflow-7f69c44f.md` | `delegate-nebflow-7f69c44f` | 1 | 1 | `20261101_083400_delegate-silent-death-analysis__delegate-nebflow-b618531a.md` | — |  |
| 75 | `（根）` | `20261031_mobile-askuser-whitescreen.md` | `mobile-askuser-whitescreen__20261031_000000.md` | — | 0 | 0 | — | — | 缺时间分量 |
| 76 | `（根）` | `20261031_owner-lost.md` | `owner-lost__20261031_000000.md` | — | 3 | 3 | `20261031_235200_bg-badge-homecoming__delegate-nebflow-7f69c44f.md`<br>`20261031_mobile-askuser-whitescreen.md`<br>`20261104_110500_our-bash-tool-defects.md` | — | 缺时间分量 |
| 77 | `（根）` | `20261031_portal-session-shortlife-rootfix.md` | `portal-session-shortlife-rootfix__20261031_000000.md` | — | 1 | 1 | `20261103_221500_host-binding-and-account-switch-recon.md` | — | 缺时间分量 |
| 78 | `（根）` | `20261101_082000_phone-access-entry-removal-reverify__delegate-nebflow-956a86c8.md` | `phone-access-entry-removal-reverify__20261101_082000__delegate-nebflow-956a86c8.md` | `delegate-nebflow-956a86c8` | 0 | 0 | — | — |  |
| 79 | `（根）` | `20261101_083400_delegate-silent-death-analysis__delegate-nebflow-b618531a.md` | `delegate-silent-death-analysis__20261101_083400__delegate-nebflow-b618531a.md` | `delegate-nebflow-b618531a` | 7 | 6 | `20261101_090600_delegate-terminal-mailbox-design__delegate-nebflow-4a58b3db.zh-CN.md`<br>`20261102_004500_delegate-frozen-state-machine__delegate-nebflow-b27760ba.md`<br>`20261103_160500_引擎会话治理七缺陷__delegate-nebflow-3037d497.md` | — |  |
| 80 | `（根）` | `20261101_101138_dispatcher-seat-prompt-919-doctrine__delegate-nebflow-dd0d529e.zh-CN.md` | `dispatcher-seat-prompt-919-doctrine__20261101_101138__delegate-nebflow-dd0d529e.zh-CN.md` | `delegate-nebflow-dd0d529e` | 1 | 1 | `20261103_085500_dual-root-split-nebflow-home__delegate-nebflow-a3ca2bc7.md` | — |  |
| 81 | `（根）` | `20261101_121500_output-block-channel-wire__delegate-nebflow-8f4a92ec.md` | `output-block-channel-wire__20261101_121500__delegate-nebflow-8f4a92ec.md` | `delegate-nebflow-8f4a92ec` | 0 | 0 | — | — |  |
| 82 | `（根）` | `20261101_125600_mobile-chat-blank-hotfix__delegate-nebflow-0df6b25c.md` | `mobile-chat-blank-hotfix__20261101_125600__delegate-nebflow-0df6b25c.md` | `delegate-nebflow-0df6b25c` | 2 | 2 | `20261101_151543_mobile-gesture-tribunal__delegate-nebflow-2a780c56.md`<br>`20261101_180000_continuation-panel-blank-rca__delegate-nebflow-d86b41ad.md` | — |  |
| 83 | `（根）` | `20261101_143000_stand-dev-methods-design-merge__delegate-nebflow-cc246cd7.md` | `stand-dev-methods-design-merge__20261101_143000__delegate-nebflow-cc246cd7.md` | `delegate-nebflow-cc246cd7` | 2 | 1 | `20261101_154500_delegate-rcpt-diffstat__delegate-nebflow-6043f217.md` | — |  |
| 84 | `（根）` | `20261101_151034_ustc-cicd-migration-assessment__delegate-nebflow-7f8e8dec.md` | `ustc-cicd-migration-assessment__20261101_151034__delegate-nebflow-7f8e8dec.md` | `delegate-nebflow-7f8e8dec` | 8 | 4 | `20261101_172033_ustc-git-survey__kernel.md`<br>`20261101_160000_secrets-trio-cli-prompt-panel.md`<br>`20261101_171500_ustc-gitlab-cicd-rollout__delegate-nebflow-1f99803a.md` | — |  |
| 85 | `（根）` | `20261101_151543_mobile-gesture-tribunal__delegate-nebflow-2a780c56.md` | `mobile-gesture-tribunal__20261101_151543__delegate-nebflow-2a780c56.md` | `delegate-nebflow-2a780c56` | 4 | 3 | `20261101_165500_pop-footer-family__delegate-nebflow-a4d10fbb.md`<br>`20261102_083638_mobile-canvas-ring-tabbar__delegate-nebflow-807819e2.md`<br>`20261102_canvas-ring-tabbar-align__delegate-nebflow-ea997ea1.md` | — |  |
| 86 | `（根）` | `20261101_154500_delegate-rcpt-diffstat__delegate-nebflow-6043f217.md` | `delegate-rcpt-diffstat__20261101_154500__delegate-nebflow-6043f217.md` | `delegate-nebflow-6043f217` | 2 | 2 | `20261101_165700_canvas-ask-single-row__delegate-nebflow-b08ffc5d.md`<br>`20261103_160500_引擎会话治理七缺陷__delegate-nebflow-3037d497.md` | — |  |
| 87 | `（根）` | `20261101_160000_secrets-trio-cli-prompt-panel.md` | `secrets-trio-cli-prompt-panel__20261101_160000.md` | — | 0 | 0 | — | — |  |
| 88 | `（根）` | `20261101_162000_bg-flow-chain-visibility__delegate-nebflow-73346fa5.md` | `bg-flow-chain-visibility__20261101_162000__delegate-nebflow-73346fa5.md` | `delegate-nebflow-73346fa5` | 1 | 1 | `20261101_174600_delegate-status-audit__kernel.md` | — |  |
| 89 | `（根）` | `20261101_165500_pop-footer-family__delegate-nebflow-a4d10fbb.md` | `pop-footer-family__20261101_165500__delegate-nebflow-a4d10fbb.md` | `delegate-nebflow-a4d10fbb` | 1 | 1 | `20261101_172500_mobile-panel-title-b__delegate-nebflow-0c24df31.md` | — |  |
| 90 | `（根）` | `20261101_165531_dispatcher-chain-lifecycle-rca__kernel.md` | `dispatcher-chain-lifecycle-rca__20261101_165531__kernel.md` | `kernel` | 16 | 5 | `20261102_200551_dispatcher-node-failure-notification-rca__delegate-nebflow-dfab3d21.md`<br>`20261101_174600_delegate-status-audit__kernel.md`<br>`20261101_193000_delegate-standby-status-matrix__delegate-nebflow-c6c62891.md` | ✅ |  |
| 91 | `（根）` | `20261101_165700_canvas-ask-single-row__delegate-nebflow-b08ffc5d.md` | `canvas-ask-single-row__20261101_165700__delegate-nebflow-b08ffc5d.md` | `delegate-nebflow-b08ffc5d` | 0 | 0 | — | — |  |
| 92 | `（根）` | `20261101_170223_toggle-green-revert__delegate-nebflow-16e74801.md` | `toggle-green-revert__20261101_170223__delegate-nebflow-16e74801.md` | `delegate-nebflow-16e74801` | 0 | 0 | — | — |  |
| 93 | `（根）` | `20261101_171500_search-return-pill__delegate-nebflow-9fcc3f05.md` | `search-return-pill__20261101_171500__delegate-nebflow-9fcc3f05.md` | `delegate-nebflow-9fcc3f05` | 1 | 1 | `20261101_180900_search-scope-main-only__delegate-nebflow-e9fe550d.md` | — |  |
| 94 | `（根）` | `20261101_171500_ustc-gitlab-cicd-rollout__delegate-nebflow-1f99803a.md` | `ustc-gitlab-cicd-rollout__20261101_171500__delegate-nebflow-1f99803a.md` | `delegate-nebflow-1f99803a` | 0 | 0 | — | — |  |
| 95 | `（根）` | `20261101_172033_ustc-git-survey__kernel.md` | `ustc-git-survey__20261101_172033__kernel.md` | `kernel` | 4 | 2 | `20261101_175329_ustc-zhang-merge__delegate-nebflow-eed3ad45.md`<br>`20261104_144500_zhang-anqi-container-system-eval__delegate-nebflow-5022b4e0.md` | — |  |
| 96 | `（根）` | `20261101_172500_doc-reading-editing-audit__delegate-nebflow-582581bb.md` | `doc-reading-editing-audit__20261101_172500__delegate-nebflow-582581bb.md` | `delegate-nebflow-582581bb` | 0 | 0 | — | — |  |
| 97 | `（根）` | `20261101_172500_mobile-panel-title-b__delegate-nebflow-0c24df31.md` | `mobile-panel-title-b__20261101_172500__delegate-nebflow-0c24df31.md` | `delegate-nebflow-0c24df31` | 5 | 2 | `20261102_085500_mobile-two-stage-title__delegate-nebflow-1bae7308.md`<br>`20261101_210500_avatar-chip-theme-centering-title-gap__delegate-nebflow-adb6986f.md` | — |  |
| 98 | `（根）` | `20261101_174600_delegate-status-audit__kernel.md` | `delegate-status-audit__20261101_174600__kernel.md` | `kernel` | 6 | 5 | `20261101_180000_delegate-status-audit-reader.md`<br>`20261101_183000_nebula-retired__delegate-nebflow-cd7a9fbd.md`<br>`20261102_223615_worktree-reuse-selection__delegate-nebflow-e2f461bb.md` | ✅ |  |
| 99 | `（根）` | `20261101_175329_ustc-zhang-merge__delegate-nebflow-eed3ad45.md` | `ustc-zhang-merge__20261101_175329__delegate-nebflow-eed3ad45.md` | `delegate-nebflow-eed3ad45` | 0 | 0 | — | — |  |
| 100 | `（根）` | `20261101_180000_continuation-panel-blank-rca__delegate-nebflow-d86b41ad.md` | `continuation-panel-blank-rca__20261101_180000__delegate-nebflow-d86b41ad.md` | `delegate-nebflow-d86b41ad` | 4 | 3 | `20261101_192000_process-recorder-design__delegate-nebflow-24e5c4c3.md`<br>`20261101_183000_crash-recovery-ws-send__delegate-nebflow-7f75c4c6.md`<br>`20261101_203500_process-recorder-f1f2__delegate-nebflow-bf078e5e.md` | — |  |
| 101 | `（根）` | `20261101_180000_delegate-status-audit-reader.md` | `delegate-status-audit-reader__20261101_180000.md` | — | 0 | 0 | — | — |  |
| 102 | `（根）` | `20261101_180900_search-scope-main-only__delegate-nebflow-e9fe550d.md` | `search-scope-main-only__20261101_180900__delegate-nebflow-e9fe550d.md` | `delegate-nebflow-e9fe550d` | 0 | 0 | — | — |  |
| 103 | `（根）` | `20261101_181500_protocol-compat-cmdcode-rootcause__delegate-nebflow-5be497f9.md` | `protocol-compat-cmdcode-rootcause__20261101_181500__delegate-nebflow-5be497f9.md` | `delegate-nebflow-5be497f9` | 2 | 1 | `20261104_095200_flysin_provider_error_recon.md` | — |  |
| 104 | `（根）` | `20261101_183000_crash-recovery-ws-send__delegate-nebflow-7f75c4c6.md` | `crash-recovery-ws-send__20261101_183000__delegate-nebflow-7f75c4c6.md` | `delegate-nebflow-7f75c4c6` | 2 | 2 | `20261101_203500_process-recorder-f1f2__delegate-nebflow-bf078e5e.md`<br>`20261101_192000_process-recorder-design__delegate-nebflow-24e5c4c3.md` | — |  |
| 105 | `（根）` | `20261101_183000_nebula-retired__delegate-nebflow-cd7a9fbd.md` | `nebula-retired__20261101_183000__delegate-nebflow-cd7a9fbd.md` | `delegate-nebflow-cd7a9fbd` | 1 | 1 | `20261101_193916_output-block-gate__delegate-nebflow-c7740727.md` | — |  |
| 106 | `（根）` | `20261101_193000_ask-input-composer-package.md` | `ask-input-composer-package__20261101_193000.md` | — | 0 | 0 | — | — |  |
| 107 | `（根）` | `20261101_193000_delegate-standby-status-matrix__delegate-nebflow-c6c62891.md` | `delegate-standby-status-matrix__20261101_193000__delegate-nebflow-c6c62891.md` | `delegate-nebflow-c6c62891` | 0 | 0 | — | — |  |
| 108 | `（根）` | `20261101_193505_prompt-context-refresh-audit__delegate-nebflow-d32fca10.md` | `prompt-context-refresh-audit__20261101_193505__delegate-nebflow-d32fca10.md` | `delegate-nebflow-d32fca10` | 0 | 0 | — | — |  |
| 109 | `（根）` | `20261101_193916_output-block-gate__delegate-nebflow-c7740727.md` | `output-block-gate__20261101_193916__delegate-nebflow-c7740727.md` | `delegate-nebflow-c7740727` | 1 | 1 | `20261101_201301_output-block-gate-prompt-face__delegate-nebflow-a6c613c8.md` | — |  |
| 110 | `（根）` | `20261101_195800_mail-connect-root-cause__delegate-nebflow-57bbf19e.md` | `mail-connect-root-cause__20261101_195800__delegate-nebflow-57bbf19e.md` | `delegate-nebflow-57bbf19e` | 0 | 0 | — | — |  |
| 111 | `（根）` | `20261101_200500_explorer-devmenu-float.md` | `explorer-devmenu-float__20261101_200500.md` | — | 0 | 0 | — | — |  |
| 112 | `（根）` | `20261101_201301_output-block-gate-prompt-face__delegate-nebflow-a6c613c8.md` | `output-block-gate-prompt-face__20261101_201301__delegate-nebflow-a6c613c8.md` | `delegate-nebflow-a6c613c8` | 0 | 0 | — | — |  |
| 113 | `（根）` | `20261101_203300_usage-mobile-scroll.md` | `usage-mobile-scroll__20261101_203300.md` | — | 0 | 0 | — | — |  |
| 114 | `（根）` | `20261101_203500_process-recorder-f1f2__delegate-nebflow-bf078e5e.md` | `process-recorder-f1f2__20261101_203500__delegate-nebflow-bf078e5e.md` | `delegate-nebflow-bf078e5e` | 0 | 0 | — | — |  |
| 115 | `（根）` | `20261101_203500_project-unmount-console__delegate-nebflow-a5e474bc.md` | `project-unmount-console__20261101_203500__delegate-nebflow-a5e474bc.md` | `delegate-nebflow-a5e474bc` | 0 | 0 | — | — |  |
| 116 | `（根）` | `20261101_210500_avatar-chip-theme-centering-title-gap__delegate-nebflow-adb6986f.md` | `avatar-chip-theme-centering-title-gap__20261101_210500__delegate-nebflow-adb6986f.md` | `delegate-nebflow-adb6986f` | 0 | 0 | — | — |  |
| 117 | `（根）` | `20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb.md` | `mobile-dock-capsule__20261101_212200__delegate-nebflow-7fa8a6bb.md` | `delegate-nebflow-7fa8a6bb` | 6 | 6 | `20261101_222500_composer-mobile-slim__delegate-nebflow-6e995fd9.md`<br>`20261101_225500_dock-avatar-edge-unify__delegate-nebflow-9770a0a7.md`<br>`20261101_235500_msgchip-provenance-strip__delegate-nebflow-53a7b7ea.md` | — |  |
| 118 | `（根）` | `20261101_221500_muse-avatar-restore-stack-smartcrop.md` | `muse-avatar-restore-stack-smartcrop__20261101_221500.md` | — | 5 | 3 | `20261101_235748_chat-head-position-fix__delegate-nebflow-9c9ef879.md`<br>`20261102_muse-avatar-shrink__delegate-nebflow-d616c46a.md`<br>`20261102_090250_mobile-chat-topbar-single-row.md` | — |  |
| 119 | `（根）` | `20261101_222500_composer-mobile-slim__delegate-nebflow-6e995fd9.md` | `composer-mobile-slim__20261101_222500__delegate-nebflow-6e995fd9.md` | `delegate-nebflow-6e995fd9` | 8 | 6 | `20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb.md`<br>`20261101_235748_chat-head-position-fix__delegate-nebflow-9c9ef879.md`<br>`20261101_225500_dock-avatar-edge-unify__delegate-nebflow-9770a0a7.md` | — |  |
| 120 | `（根）` | `20261101_225500_dock-avatar-edge-unify__delegate-nebflow-9770a0a7.md` | `dock-avatar-edge-unify__20261101_225500__delegate-nebflow-9770a0a7.md` | `delegate-nebflow-9770a0a7` | 5 | 3 | `20261101_235748_chat-head-position-fix__delegate-nebflow-9c9ef879.md`<br>`20261101_235500_msgchip-provenance-strip__delegate-nebflow-53a7b7ea.md`<br>`20261102_muse-avatar-shrink__delegate-nebflow-d616c46a.md` | — |  |
| 121 | `（根）` | `20261101_230000_plugin-source-hot-update__delegate-nebflow-4dfd4b86.md` | `plugin-source-hot-update__20261101_230000__delegate-nebflow-4dfd4b86.md` | `delegate-nebflow-4dfd4b86` | 0 | 0 | — | — |  |
| 122 | `（根）` | `20261101_233954_composer-attach-icon__delegate-nebflow-4251fd3d.md` | `composer-attach-icon__20261101_233954__delegate-nebflow-4251fd3d.md` | `delegate-nebflow-4251fd3d` | 3 | 3 | `20261102_103000_stackcard-ref-and-synccard-center__delegate-nebflow-def82876.md`<br>`20261102_124055_quote-iconify__delegate-nebflow-7297e18b.md`<br>`20261102_133000_quote-icon-unify__delegate-nebflow-1818bb74.md` | — |  |
| 123 | `（根）` | `20261101_235500_msgchip-provenance-strip__delegate-nebflow-53a7b7ea.md` | `msgchip-provenance-strip__20261101_235500__delegate-nebflow-53a7b7ea.md` | `delegate-nebflow-53a7b7ea` | 1 | 1 | `20261102_175113_quote-identity-badge__delegate-nebflow-cc920bea.md` | — |  |
| 124 | `（根）` | `20261101_235748_chat-head-position-fix__delegate-nebflow-9c9ef879.md` | `chat-head-position-fix__20261101_235748__delegate-nebflow-9c9ef879.md` | `delegate-nebflow-9c9ef879` | 7 | 6 | `20261102_muse-avatar-shrink__delegate-nebflow-d616c46a.md`<br>`20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb.md`<br>`20261102_090250_mobile-chat-topbar-single-row.md` | — |  |
| 125 | `（根）` | `20261102_004500_delegate-frozen-state-machine__delegate-nebflow-b27760ba.md` | `delegate-frozen-state-machine__20261102_004500__delegate-nebflow-b27760ba.md` | `delegate-nebflow-b27760ba` | 0 | 0 | — | — |  |
| 126 | `（根）` | `20261102_083638_mobile-canvas-ring-tabbar__delegate-nebflow-807819e2.md` | `mobile-canvas-ring-tabbar__20261102_083638__delegate-nebflow-807819e2.md` | `delegate-nebflow-807819e2` | 5 | 4 | `20261102_190000_canvas-mobile-overlay-fix__delegate-nebflow-f1cfeb61.md`<br>`20261102_canvas-ring-tabbar-align__delegate-nebflow-ea997ea1.md`<br>`20261102_center-tab-no-fade__delegate-nebflow-e1c239be.md` | — |  |
| 127 | `（根）` | `20261102_084500_bootcard-brandicon-and-emoji-audit__delegate-nebflow-f555a161.md` | `bootcard-brandicon-and-emoji-audit__20261102_084500__delegate-nebflow-f555a161.md` | `delegate-nebflow-f555a161` | 2 | 2 | `20261102_103000_stackcard-ref-and-synccard-center__delegate-nebflow-def82876.md`<br>`index.md` | ✅ |  |
| 128 | `（根）` | `20261102_085500_mobile-two-stage-title__delegate-nebflow-1bae7308.md` | `mobile-two-stage-title__20261102_085500__delegate-nebflow-1bae7308.md` | `delegate-nebflow-1bae7308` | 13 | 7 | `20261102_102525_contacts-device-update-retire__delegate-nebflow-16c2e54c.md`<br>`20261102_114128_settings-identity-card__delegate-nebflow-16c2e54c.md`<br>`20261102_bg-panel-single-scroll__delegate-nebflow-ef2390e1.md` | — |  |
| 129 | `（根）` | `20261102_090250_mobile-chat-topbar-single-row.md` | `mobile-chat-topbar-single-row__20261102_090250.md` | — | 3 | 2 | `20261102_muse-head-restore__delegate-nebflow-accdcd3b.md`<br>`20261102_170000_conversation-heads-unify__delegate-nebflow-16c2e54c.md` | — |  |
| 130 | `（根）` | `20261102_102525_contacts-device-update-retire__delegate-nebflow-16c2e54c.md` | `contacts-device-update-retire__20261102_102525__delegate-nebflow-16c2e54c.md` | `delegate-nebflow-16c2e54c` | 2 | 2 | `20261102_182500_profile-settings-batch-landing__delegate-nebflow-16c2e54c.md`<br>`20261102_2010_profile-settings-closeout__delegate-nebflow-16c2e54c.md` | — |  |
| 131 | `（根）` | `20261102_103000_stackcard-ref-and-synccard-center__delegate-nebflow-def82876.md` | `stackcard-ref-and-synccard-center__20261102_103000__delegate-nebflow-def82876.md` | `delegate-nebflow-def82876` | 2 | 2 | `20261102_124055_quote-iconify__delegate-nebflow-7297e18b.md`<br>`20261102_133000_quote-icon-unify__delegate-nebflow-1818bb74.md` | — |  |
| 132 | `（根）` | `20261102_114128_settings-identity-card__delegate-nebflow-16c2e54c.md` | `settings-identity-card__20261102_114128__delegate-nebflow-16c2e54c.md` | `delegate-nebflow-16c2e54c` | 4 | 2 | `20261102_182500_profile-settings-batch-landing__delegate-nebflow-16c2e54c.md`<br>`20261102_2010_profile-settings-closeout__delegate-nebflow-16c2e54c.md` | — |  |
| 133 | `（根）` | `20261102_121500_host-binding-audit__delegate-nebflow-7d8475af.md` | `host-binding-audit__20261102_121500__delegate-nebflow-7d8475af.md` | `delegate-nebflow-7d8475af` | 3 | 1 | `20261103_221500_host-binding-and-account-switch-recon.md` | — |  |
| 134 | `（根）` | `20261102_124055_quote-iconify__delegate-nebflow-7297e18b.md` | `quote-iconify__20261102_124055__delegate-nebflow-7297e18b.md` | `delegate-nebflow-7297e18b` | 2 | 1 | `20261102_133000_quote-icon-unify__delegate-nebflow-1818bb74.md` | — |  |
| 135 | `（根）` | `20261102_130500_profile-dual-desc__delegate-nebflow-16c2e54c.md` | `profile-dual-desc__20261102_130500__delegate-nebflow-16c2e54c.md` | `delegate-nebflow-16c2e54c` | 6 | 3 | `20261102_2010_profile-settings-closeout__delegate-nebflow-16c2e54c.md`<br>`20261102_182500_profile-settings-batch-landing__delegate-nebflow-16c2e54c.md`<br>`20261102_170000_conversation-heads-unify__delegate-nebflow-16c2e54c.md` | — |  |
| 136 | `（根）` | `20261102_133000_quote-icon-unify__delegate-nebflow-1818bb74.md` | `quote-icon-unify__20261102_133000__delegate-nebflow-1818bb74.md` | `delegate-nebflow-1818bb74` | 2 | 2 | `20261102_124055_quote-iconify__delegate-nebflow-7297e18b.md`<br>`20261102_175113_quote-identity-badge__delegate-nebflow-cc920bea.md` | — |  |
| 137 | `（根）` | `20261102_143609_ptc-mode-two-plugins__delegate-nebflow-226f28aa.md` | `ptc-mode-two-plugins__20261102_143609__delegate-nebflow-226f28aa.md` | `delegate-nebflow-226f28aa` | 3 | 2 | `20261102_180000_network-search-plugin-survey.md`<br>`index.md` | ✅ |  |
| 138 | `（根）` | `20261102_161500_keyboard-layer-monaco-quickinput__delegate-nebflow-17658d72.md` | `keyboard-layer-monaco-quickinput__20261102_161500__delegate-nebflow-17658d72.md` | `delegate-nebflow-17658d72` | 0 | 0 | — | — |  |
| 139 | `（根）` | `20261102_170000_conversation-heads-unify__delegate-nebflow-16c2e54c.md` | `conversation-heads-unify__20261102_170000__delegate-nebflow-16c2e54c.md` | `delegate-nebflow-16c2e54c` | 2 | 2 | `20261102_182500_profile-settings-batch-landing__delegate-nebflow-16c2e54c.md`<br>`20261102_2010_profile-settings-closeout__delegate-nebflow-16c2e54c.md` | — |  |
| 140 | `（根）` | `20261102_171500_neblink-device-identity-and-ownership.md` | `neblink-device-identity-and-ownership__20261102_171500.md` | — | 7 | 4 | `20261103_221500_host-binding-and-account-switch-recon.md`<br>`20261104_104918_project-device-parity-design.md`<br>`20261104_114857_multi-tenant-dataroot-design.md` | ✅ |  |
| 141 | `（根）` | `20261102_175113_quote-identity-badge__delegate-nebflow-cc920bea.md` | `quote-identity-badge__20261102_175113__delegate-nebflow-cc920bea.md` | `delegate-nebflow-cc920bea` | 1 | 1 | `20261102_180425_sidebar-unread-indicator__delegate-nebflow-7f8e9c98.md` | — |  |
| 142 | `（根）` | `20261102_180000_network-search-plugin-survey.md` | `network-search-plugin-survey__20261102_180000.md` | — | 0 | 0 | — | — |  |
| 143 | `（根）` | `20261102_180425_sidebar-unread-indicator__delegate-nebflow-7f8e9c98.md` | `sidebar-unread-indicator__20261102_180425__delegate-nebflow-7f8e9c98.md` | `delegate-nebflow-7f8e9c98` | 0 | 0 | — | — |  |
| 144 | `（根）` | `20261102_180500_doc-and-term-system-survey.md` | `doc-and-term-system-survey__20261102_180500.md` | — | 0 | 0 | — | — |  |
| 145 | `（根）` | `20261102_182500_profile-settings-batch-landing__delegate-nebflow-16c2e54c.md` | `profile-settings-batch-landing__20261102_182500__delegate-nebflow-16c2e54c.md` | `delegate-nebflow-16c2e54c` | 2 | 1 | `20261102_2010_profile-settings-closeout__delegate-nebflow-16c2e54c.md` | — |  |
| 146 | `（根）` | `20261102_190000_canvas-mobile-overlay-fix__delegate-nebflow-f1cfeb61.md` | `canvas-mobile-overlay-fix__20261102_190000__delegate-nebflow-f1cfeb61.md` | `delegate-nebflow-f1cfeb61` | 2 | 2 | `20261103_000044_mobile-image-gesture-viewer__delegate-nebflow-12aef298.md`<br>`index.md` | ✅ |  |
| 147 | `（根）` | `20261102_193947_process-artifacts-gate__delegate-nebflow-5ed48ddc.md` | `process-artifacts-gate__20261102_193947__delegate-nebflow-5ed48ddc.md` | `delegate-nebflow-5ed48ddc` | 8 | 6 | `20261103_004146_design-language-reference__delegate-nebflow-878e1617.zh-CN.md`<br>`20261102_190000_canvas-mobile-overlay-fix__delegate-nebflow-f1cfeb61.md`<br>`20261102_210500_delegate-finish-semantics__delegate-nebflow-e1b96a7d.md` | ✅ |  |
| 148 | `（根）` | `20261102_200551_dispatcher-node-failure-notification-rca__delegate-nebflow-dfab3d21.md` | `dispatcher-node-failure-notification-rca__20261102_200551__delegate-nebflow-dfab3d21.md` | `delegate-nebflow-dfab3d21` | 9 | 5 | `20261102_210500_delegate-finish-semantics__delegate-nebflow-e1b96a7d.md`<br>`20261102_213054_dispatcher-notify-fix-p012__p012-notify-fix.md`<br>`20261104_164800_delegate-stall-audit__delegate-nebflow-8d6d3cba.md` | ✅ |  |
| 149 | `（根）` | `20261102_2010_profile-settings-closeout__delegate-nebflow-16c2e54c.md` | `profile-settings-closeout__20261102_2010__delegate-nebflow-16c2e54c.md` | `delegate-nebflow-16c2e54c` | 3 | 2 | `20261102_223000_agent-panel-row-dedup__delegate-nebflow-4e1fad0a.md`<br>`index.md` | ✅ | 时间仅 4 位 HHMM |
| 150 | `（根）` | `20261102_210500_delegate-finish-semantics__delegate-nebflow-e1b96a7d.md` | `delegate-finish-semantics__20261102_210500__delegate-nebflow-e1b96a7d.md` | `delegate-nebflow-e1b96a7d` | 3 | 3 | `20261102_213054_dispatcher-notify-fix-p012__p012-notify-fix.md`<br>`20261102_221500_dispatcher-notify-p012-closeout__delegate-nebflow-c4e7352c.md`<br>`index.md` | ✅ |  |
| 151 | `（根）` | `20261102_213000_fm-md-social__delegate-nebflow-f4d3e0f8.md` | `fm-md-social__20261102_213000__delegate-nebflow-f4d3e0f8.md` | `delegate-nebflow-f4d3e0f8` | 4 | 3 | `20261104_174558_fm-media-bubble-color-unify__bubble-color-unify.md`<br>`20261104_180802_fm-media-bubble-color.md`<br>`index.md` | ✅ |  |
| 152 | `（根）` | `20261102_213054_dispatcher-notify-fix-p012__p012-notify-fix.md` | `dispatcher-notify-fix-p012__20261102_213054__p012-notify-fix.md` | `p012-notify-fix` | 5 | 2 | `20261102_221500_dispatcher-notify-p012-closeout__delegate-nebflow-c4e7352c.md`<br>`index.md` | ✅ |  |
| 153 | `（根）` | `20261102_221500_dispatcher-notify-p012-closeout__delegate-nebflow-c4e7352c.md` | `dispatcher-notify-p012-closeout__20261102_221500__delegate-nebflow-c4e7352c.md` | `delegate-nebflow-c4e7352c` | 2 | 2 | `20261102_223615_worktree-reuse-selection__delegate-nebflow-e2f461bb.md`<br>`index.md` | ✅ |  |
| 154 | `（根）` | `20261102_223000_agent-panel-row-dedup__delegate-nebflow-4e1fad0a.md` | `agent-panel-row-dedup__20261102_223000__delegate-nebflow-4e1fad0a.md` | `delegate-nebflow-4e1fad0a` | 1 | 1 | `index.md` | ✅ |  |
| 155 | `（根）` | `20261102_223615_worktree-reuse-selection__delegate-nebflow-e2f461bb.md` | `worktree-reuse-selection__20261102_223615__delegate-nebflow-e2f461bb.md` | `delegate-nebflow-e2f461bb` | 2 | 2 | `glossary.md`<br>`index.md` | ✅ |  |
| 156 | `（根）` | `20261102_225500_plugin-selfcontainment-m16-c19__delegate-nebflow-47c59129.md` | `plugin-selfcontainment-m16-c19__20261102_225500__delegate-nebflow-47c59129.md` | `delegate-nebflow-47c59129` | 1 | 1 | `index.md` | ✅ |  |
| 157 | `（根）` | `20261102_230027_mobile-chat-initial-scroll__delegate-nebflow-3723511e.md` | `mobile-chat-initial-scroll__20261102_230027__delegate-nebflow-3723511e.md` | `delegate-nebflow-3723511e` | 1 | 1 | `index.md` | ✅ |  |
| 158 | `（根）` | `20261102_234250_panel-title-capsule-morph__delegate-nebflow-e3055989.md` | `panel-title-capsule-morph__20261102_234250__delegate-nebflow-e3055989.md` | `delegate-nebflow-e3055989` | 1 | 1 | `index.md` | ✅ |  |
| 159 | `（根）` | `20261102_234900_fm-threeface-align__delegate-nebflow-c1142b9b.md` | `fm-threeface-align__20261102_234900__delegate-nebflow-c1142b9b.md` | `delegate-nebflow-c1142b9b` | 0 | 0 | — | — |  |
| 160 | `（根）` | `20261102_234917_design-language-recon__dlc-frontend.md` | `design-language-recon__20261102_234917__dlc-frontend.md` | `dlc-frontend` | 10 | 3 | `20261103_004146_design-language-reference__delegate-nebflow-878e1617.zh-CN.md`<br>`20261104_140825_frontend-vue-paradigm-eval__delegate-nebflow-79c40e17.md`<br>`index.md` | ✅ |  |
| 161 | `（根）` | `20261102_bg-panel-single-scroll__delegate-nebflow-ef2390e1.md` | `bg-panel-single-scroll__20261102_000000__delegate-nebflow-ef2390e1.md` | `delegate-nebflow-ef2390e1` | 0 | 0 | — | — | 缺时间分量 |
| 162 | `（根）` | `20261102_canvas-ring-tabbar-align__delegate-nebflow-ea997ea1.md` | `canvas-ring-tabbar-align__20261102_000000__delegate-nebflow-ea997ea1.md` | `delegate-nebflow-ea997ea1` | 2 | 2 | `20261102_190000_canvas-mobile-overlay-fix__delegate-nebflow-f1cfeb61.md`<br>`20261102_center-tab-no-fade__delegate-nebflow-e1c239be.md` | — | 缺时间分量 |
| 163 | `（根）` | `20261102_center-tab-no-fade__delegate-nebflow-e1c239be.md` | `center-tab-no-fade__20261102_000000__delegate-nebflow-e1c239be.md` | `delegate-nebflow-e1c239be` | 0 | 0 | — | — | 缺时间分量 |
| 164 | `（根）` | `20261102_mobile-pinned-search__delegate-nebflow-43af6929.md` | `mobile-pinned-search__20261102_000000__delegate-nebflow-43af6929.md` | `delegate-nebflow-43af6929` | 0 | 0 | — | — | 缺时间分量 |
| 165 | `（根）` | `20261102_muse-avatar-shrink__delegate-nebflow-d616c46a.md` | `muse-avatar-shrink__20261102_000000__delegate-nebflow-d616c46a.md` | `delegate-nebflow-d616c46a` | 6 | 5 | `20261102_mobile-pinned-search__delegate-nebflow-43af6929.md`<br>`20261102_090250_mobile-chat-topbar-single-row.md`<br>`20261102_170000_conversation-heads-unify__delegate-nebflow-16c2e54c.md` | — | 缺时间分量 |
| 166 | `（根）` | `20261102_muse-head-restore__delegate-nebflow-accdcd3b.md` | `muse-head-restore__20261102_000000__delegate-nebflow-accdcd3b.md` | `delegate-nebflow-accdcd3b` | 2 | 1 | `20261102_muse-head-same-row__delegate-nebflow-8c0b5ca5.md` | — | 缺时间分量 |
| 167 | `（根）` | `20261102_muse-head-same-row__delegate-nebflow-8c0b5ca5.md` | `muse-head-same-row__20261102_000000__delegate-nebflow-8c0b5ca5.md` | `delegate-nebflow-8c0b5ca5` | 0 | 0 | — | — | 缺时间分量 |
| 168 | `（根）` | `20261102_plugin-panel-layout__delegate-nebflow-f1fc2f78.md` | `plugin-panel-layout__20261102_000000__delegate-nebflow-f1fc2f78.md` | `delegate-nebflow-f1fc2f78` | 0 | 0 | — | — | 缺时间分量 |
| 169 | `（根）` | `20261102_title-capsule-height__delegate-nebflow-f003855e.md` | `title-capsule-height__20261102_000000__delegate-nebflow-f003855e.md` | `delegate-nebflow-f003855e` | 7 | 3 | `20261102_todo-capsule__delegate-nebflow-35eeaf1e.md`<br>`20261102_230027_mobile-chat-initial-scroll__delegate-nebflow-3723511e.md`<br>`20261102_234250_panel-title-capsule-morph__delegate-nebflow-e3055989.md` | — | 缺时间分量 |
| 170 | `（根）` | `20261102_todo-capsule__delegate-nebflow-35eeaf1e.md` | `todo-capsule__20261102_000000__delegate-nebflow-35eeaf1e.md` | `delegate-nebflow-35eeaf1e` | 2 | 2 | `20261104_151123_ui-batch-delivery-audit.md`<br>`index.md` | ✅ | 缺时间分量 |
| 171 | `（根）` | `20261103_000044_mobile-image-gesture-viewer__delegate-nebflow-12aef298.md` | `mobile-image-gesture-viewer__20261103_000044__delegate-nebflow-12aef298.md` | `delegate-nebflow-12aef298` | 2 | 2 | `20261104_140500_tool-card-image-face__delegate-nebflow-c4696e5c.md`<br>`index.md` | ✅ |  |
| 172 | `（根）` | `20261103_004146_design-language-reference__delegate-nebflow-878e1617.zh-CN.md` | `design-language-reference__20261103_004146__delegate-nebflow-878e1617.zh-CN.md` | `delegate-nebflow-878e1617` | 1 | 1 | `index.md` | ✅ |  |
| 173 | `（根）` | `20261103_085500_dual-root-split-nebflow-home__delegate-nebflow-a3ca2bc7.md` | `dual-root-split-nebflow-home__20261103_085500__delegate-nebflow-a3ca2bc7.md` | `delegate-nebflow-a3ca2bc7` | 1 | 1 | `20261103_181404_stand-restart-hot-path.md` | — |  |
| 174 | `（根）` | `20261103_110500_console-single-instance-identity-inband__delegate-nebflow-01b78ec9.md` | `console-single-instance-identity-inband__20261103_110500__delegate-nebflow-01b78ec9.md` | `delegate-nebflow-01b78ec9` | 1 | 1 | `20261103_115500_event-delivery-identity-binding__delegate-nebflow-3c9f1ce2.md` | — |  |
| 175 | `（根）` | `20261103_115500_event-delivery-identity-binding__delegate-nebflow-3c9f1ce2.md` | `event-delivery-identity-binding__20261103_115500__delegate-nebflow-3c9f1ce2.md` | `delegate-nebflow-3c9f1ce2` | 3 | 2 | `20261103_141500_askanswer-placement__delegate-nebflow-3c9f1ce2.md`<br>`index.md` | ✅ |  |
| 176 | `（根）` | `20261103_141500_askanswer-placement__delegate-nebflow-3c9f1ce2.md` | `askanswer-placement__20261103_141500__delegate-nebflow-3c9f1ce2.md` | `delegate-nebflow-3c9f1ce2` | 1 | 1 | `index.md` | ✅ |  |
| 177 | `（根）` | `20261103_160500_引擎会话治理七缺陷__delegate-nebflow-3037d497.md` | `引擎会话治理七缺陷__20261103_160500__delegate-nebflow-3037d497.md` | `delegate-nebflow-3037d497` | 2 | 2 | `20261103_174500_会话续命名与位__delegate-nebflow-748bec7c.md`<br>`20261104_152704_interrupt-context-recovery__interrupt-recovery-20261104.md` | — |  |
| 178 | `（根）` | `20261103_174500_会话续命名与位__delegate-nebflow-748bec7c.md` | `会话续命名与位__20261103_174500__delegate-nebflow-748bec7c.md` | `delegate-nebflow-748bec7c` | 2 | 1 | `20261103_181404_stand-restart-hot-path.md` | — |  |
| 179 | `（根）` | `20261103_181404_stand-restart-hot-path.md` | `stand-restart-hot-path__20261103_181404.md` | — | 0 | 0 | — | — |  |
| 180 | `（根）` | `20261103_184500_mobile-hig-unification__delegate-nebflow-bb1a8bbb.md` | `mobile-hig-unification__20261103_184500__delegate-nebflow-bb1a8bbb.md` | `delegate-nebflow-bb1a8bbb` | 2 | 2 | `20261103_204900_mobile-canvas-topbar-passthrough__delegate-nebflow-bb1a8bbb.md`<br>`20261104_151123_ui-batch-delivery-audit.md` | — |  |
| 181 | `（根）` | `20261103_191215_session-four-state-completion-matrix.md` | `session-four-state-completion-matrix__20261103_191215.md` | — | 1 | 1 | `20261103_205351_merge-queue-fifo-notify.md` | — |  |
| 182 | `（根）` | `20261103_204900_mobile-canvas-topbar-passthrough__delegate-nebflow-bb1a8bbb.md` | `mobile-canvas-topbar-passthrough__20261103_204900__delegate-nebflow-bb1a8bbb.md` | `delegate-nebflow-bb1a8bbb` | 4 | 4 | `20261103_215500_canvas-hover-zero-layout__delegate-nebflow-bb1a8bbb.md`<br>`20261103_222441_doc-ask-fade__ui-unification-20261103-supp3.md`<br>`20261103_223600_send-stop-parity__ui-unification-20261103.md` | — |  |
| 183 | `（根）` | `20261103_205351_merge-queue-fifo-notify.md` | `merge-queue-fifo-notify__20261103_205351.md` | — | 2 | 1 | `20261104_164800_delegate-stall-audit__delegate-nebflow-8d6d3cba.md` | — |  |
| 184 | `（根）` | `20261103_210845_docs-landing-ledger-migration__delegate-nebflow-cceb5d85.md` | `docs-landing-ledger-migration__20261103_210845__delegate-nebflow-cceb5d85.md` | `delegate-nebflow-cceb5d85` | 1 | 1 | `20261103_210845_docs-migration-sha256-table__delegate-nebflow-cceb5d85.md` | — |  |
| 185 | `（根）` | `20261103_210845_docs-migration-sha256-table__delegate-nebflow-cceb5d85.md` | `docs-migration-sha256-table__20261103_210845__delegate-nebflow-cceb5d85.md` | `delegate-nebflow-cceb5d85` | 1 | 1 | `20261103_210845_docs-landing-ledger-migration__delegate-nebflow-cceb5d85.md` | — |  |
| 186 | `（根）` | `20261103_215500_canvas-hover-zero-layout__delegate-nebflow-bb1a8bbb.md` | `canvas-hover-zero-layout__20261103_215500__delegate-nebflow-bb1a8bbb.md` | `delegate-nebflow-bb1a8bbb` | 1 | 1 | `20261104_151123_ui-batch-delivery-audit.md` | — |  |
| 187 | `（根）` | `20261103_221500_host-binding-and-account-switch-recon.md` | `host-binding-and-account-switch-recon__20261103_221500.md` | — | 2 | 2 | `20261104_104918_project-device-parity-design.md`<br>`20261104_114857_multi-tenant-dataroot-design.md` | — |  |
| 188 | `（根）` | `20261103_222441_doc-ask-fade__ui-unification-20261103-supp3.md` | `doc-ask-fade__20261103_222441__ui-unification-20261103-supp3.md` | `ui-unification-20261103-supp3` | 3 | 3 | `20261104_093201_canvas-stack-view__ui-unification-20261103-supp4.md`<br>`20261104_095500_canvas-ask-fade-content-opacity__delegate-nebflow-9f2b15e1.md`<br>`20261104_151123_ui-batch-delivery-audit.md` | — |  |
| 189 | `（根）` | `20261103_223600_send-stop-parity__ui-unification-20261103.md` | `send-stop-parity__20261103_223600__ui-unification-20261103.md` | `ui-unification-20261103` | 4 | 4 | `20261104_093201_canvas-stack-view__ui-unification-20261103-supp4.md`<br>`20261104_104000_askuser-attach__ui-unification-20261103-supp5.md`<br>`20261104_141500_mobile-send-stop-narrowing__delegate-nebflow-f6385ac8.md` | — |  |
| 190 | `（根）` | `20261104_091322_mail-heartbeat-process-seal__delegate-nebflow-cfc822d8.md` | `mail-heartbeat-process-seal__20261104_091322__delegate-nebflow-cfc822d8.md` | `delegate-nebflow-cfc822d8` | 2 | 2 | `20261104_152704_interrupt-context-recovery__interrupt-recovery-20261104.md`<br>`index.md` | ✅ |  |
| 191 | `（根）` | `20261104_093201_canvas-stack-view__ui-unification-20261103-supp4.md` | `canvas-stack-view__20261104_093201__ui-unification-20261103-supp4.md` | `ui-unification-20261103-supp4` | 6 | 4 | `20261104_121000_canvas-ask-resize-supps__delegate-nebflow-bb1a8bbb.md`<br>`20261104_132000_console-ask-grow__delegate-nebflow-bb1a8bbb.md`<br>`20261104_104000_askuser-attach__ui-unification-20261103-supp5.md` | — |  |
| 192 | `（根）` | `20261104_095200_flysin_provider_error_recon.md` | `flysin_provider_error_recon__20261104_095200.md` | — | 0 | 0 | — | — |  |
| 193 | `（根）` | `20261104_095500_canvas-ask-fade-content-opacity__delegate-nebflow-9f2b15e1.md` | `canvas-ask-fade-content-opacity__20261104_095500__delegate-nebflow-9f2b15e1.md` | `delegate-nebflow-9f2b15e1` | 0 | 0 | — | — |  |
| 194 | `（根）` | `20261104_101500_isolated-instance-kill__delegate-nebflow-e7c1a868.md` | `isolated-instance-kill__20261104_101500__delegate-nebflow-e7c1a868.md` | `delegate-nebflow-e7c1a868` | 1 | 1 | `20261104_152704_interrupt-context-recovery__interrupt-recovery-20261104.md` | — |  |
| 195 | `（根）` | `20261104_103020_local-acceleration-audit.md` | `local-acceleration-audit__20261104_103020.md` | — | 7 | 6 | `20261104_113647_kai-probe-tax.md`<br>`20261104_104918_project-device-parity-design.md`<br>`20261104_110500_log-noise-reduction.md` | — |  |
| 196 | `（根）` | `20261104_104000_askuser-attach__ui-unification-20261103-supp5.md` | `askuser-attach__20261104_104000__ui-unification-20261103-supp5.md` | `ui-unification-20261103-supp5` | 5 | 4 | `20261104_132000_console-ask-grow__delegate-nebflow-bb1a8bbb.md`<br>`20261104_121000_canvas-ask-resize-supps__delegate-nebflow-bb1a8bbb.md`<br>`20261104_151123_ui-batch-delivery-audit.md` | — |  |
| 197 | `（根）` | `20261104_104918_project-device-parity-batches.md` | `project-device-parity-batches__20261104_104918.md` | — | 0 | 0 | — | — |  |
| 198 | `（根）` | `20261104_104918_project-device-parity-design.md` | `project-device-parity-design__20261104_104918.md` | — | 8 | 2 | `20261104_114857_multi-tenant-dataroot-design.md`<br>`20261104_104918_project-device-parity-batches.md` | — |  |
| 199 | `（根）` | `20261104_110500_log-noise-reduction.md` | `log-noise-reduction__20261104_110500.md` | — | 0 | 0 | — | — |  |
| 200 | `（根）` | `20261104_110500_our-bash-tool-defects.md` | `our-bash-tool-defects__20261104_110500.md` | — | 3 | 1 | `20261104_113008_external-agent-bash-design-survey.md` | — |  |
| 201 | `（根）` | `20261104_113008_external-agent-bash-design-survey.md` | `external-agent-bash-design-survey__20261104_113008.md` | — | 0 | 0 | — | — |  |
| 202 | `（根）` | `20261104_113647_kai-probe-tax.md` | `kai-probe-tax__20261104_113647.md` | — | 9 | 5 | `20261104_142000_multi-device-shared-compute__delegate-nebflow-01df9dc9.md`<br>`20261104_140600_presence-dial-overhaul.md`<br>`20261104_130303_memory-audit-and-management.md` | — |  |
| 203 | `（根）` | `20261104_114857_multi-tenant-dataroot-design.md` | `multi-tenant-dataroot-design__20261104_114857.md` | — | 3 | 2 | `20261104_130303_memory-audit-and-management.md`<br>`20261104_124800_b1-dataroot-impl.md` | — |  |
| 204 | `（根）` | `20261104_115529_actor-fsm-backend-spec__actor-fsm-batch.md` | `actor-fsm-backend-spec__20261104_115529__actor-fsm-batch.md` | `actor-fsm-batch` | 4 | 2 | `20261104_121517_delegate-actor-evaluation__actor-fsm-batch.md`<br>`glossary.md` | — |  |
| 205 | `（根）` | `20261104_121000_canvas-ask-resize-supps__delegate-nebflow-bb1a8bbb.md` | `canvas-ask-resize-supps__20261104_121000__delegate-nebflow-bb1a8bbb.md` | `delegate-nebflow-bb1a8bbb` | 3 | 2 | `20261104_132000_console-ask-grow__delegate-nebflow-bb1a8bbb.md`<br>`20261104_151123_ui-batch-delivery-audit.md` | — |  |
| 206 | `（根）` | `20261104_121517_delegate-actor-evaluation__actor-fsm-batch.md` | `delegate-actor-evaluation__20261104_121517__actor-fsm-batch.md` | `actor-fsm-batch` | 2 | 1 | `20261104_130303_memory-audit-and-management.md` | — |  |
| 207 | `（根）` | `20261104_124800_b1-dataroot-impl.md` | `b1-dataroot-impl__20261104_124800.md` | — | 2 | 2 | `20261104_130303_memory-audit-and-management.md`<br>`20261104_133700_memory-mitigations.md` | — |  |
| 208 | `（根）` | `20261104_130303_memory-audit-and-management.md` | `memory-audit-and-management__20261104_130303.md` | — | 8 | 4 | `20261104_133700_memory-mitigations.md`<br>`20261104_142000_multi-device-shared-compute__delegate-nebflow-01df9dc9.md`<br>`20261104_144000_local-budget-extraction__delegate-nebflow-6f8eab41.md` | — |  |
| 209 | `（根）` | `20261104_132000_console-ask-grow__delegate-nebflow-bb1a8bbb.md` | `console-ask-grow__20261104_132000__delegate-nebflow-bb1a8bbb.md` | `delegate-nebflow-bb1a8bbb` | 1 | 1 | `20261104_151123_ui-batch-delivery-audit.md` | — |  |
| 210 | `（根）` | `20261104_133700_memory-mitigations.md` | `memory-mitigations__20261104_133700.md` | — | 5 | 4 | `20261104_142000_multi-device-shared-compute__delegate-nebflow-01df9dc9.md`<br>`20261104_144000_local-budget-extraction__delegate-nebflow-6f8eab41.md`<br>`20261104_144500_zhang-anqi-container-system-eval__delegate-nebflow-5022b4e0.md` | — |  |
| 211 | `（根）` | `20261104_140500_tool-card-image-face__delegate-nebflow-c4696e5c.md` | `tool-card-image-face__20261104_140500__delegate-nebflow-c4696e5c.md` | `delegate-nebflow-c4696e5c` | 0 | 0 | — | — |  |
| 212 | `（根）` | `20261104_140600_presence-dial-overhaul.md` | `presence-dial-overhaul__20261104_140600.md` | — | 8 | 3 | `20261104_142000_multi-device-shared-compute__delegate-nebflow-01df9dc9.md`<br>`20261104_153209_presence-direct-root-cause.md`<br>`20261104_163000_p2p-direct-service-design__delegate-nebflow-40d3b47a.md` | — |  |
| 213 | `（根）` | `20261104_140825_frontend-vue-paradigm-eval__delegate-nebflow-79c40e17.md` | `frontend-vue-paradigm-eval__20261104_140825__delegate-nebflow-79c40e17.md` | `delegate-nebflow-79c40e17` | 3 | 1 | `20261104_161245_frontend-paradigm-a-ask-component__ask-paradigm-a.md` | — |  |
| 214 | `（根）` | `20261104_141402_self-explain-plugin-rule__delegate-nebflow-4d30d543.md` | `self-explain-plugin-rule__20261104_141402__delegate-nebflow-4d30d543.md` | `delegate-nebflow-4d30d543` | 3 | 2 | `20261104_154500_self-explaining-cleanup__delegate-nebflow-8b0a8e45.md`<br>`index.md` | ✅ |  |
| 215 | `（根）` | `20261104_141500_mobile-send-stop-narrowing__delegate-nebflow-f6385ac8.md` | `mobile-send-stop-narrowing__20261104_141500__delegate-nebflow-f6385ac8.md` | `delegate-nebflow-f6385ac8` | 1 | 1 | `20261104_142800_canvas-top-tabs-swipe__ui-final-four.md` | — |  |
| 216 | `（根）` | `20261104_142000_multi-device-shared-compute__delegate-nebflow-01df9dc9.md` | `multi-device-shared-compute__20261104_142000__delegate-nebflow-01df9dc9.md` | `delegate-nebflow-01df9dc9` | 7 | 3 | `20261104_144000_local-budget-extraction__delegate-nebflow-6f8eab41.md`<br>`20261104_144500_zhang-anqi-container-system-eval__delegate-nebflow-5022b4e0.md`<br>`20261104_150500_sbt-client-server-verification__delegate-nebflow-3f40b03d.md` | — |  |
| 217 | `（根）` | `20261104_142800_canvas-top-tabs-swipe__ui-final-four.md` | `canvas-top-tabs-swipe__20261104_142800__ui-final-four.md` | `ui-final-four` | 5 | 4 | `20261104_150049_side-tab-legibility__ui-final-four.md`<br>`20261104_144555_msg-footer-geometry__ui-final-four.md`<br>`20261104_152500_ui-final-four-independent-review__ui-final-four.md` | — |  |
| 218 | `（根）` | `20261104_143000_design-scale-spec__delegate-nebflow-be8349d0.md` | `design-scale-spec__20261104_143000__delegate-nebflow-be8349d0.md` | `delegate-nebflow-be8349d0` | 0 | 0 | — | — |  |
| 219 | `（根）` | `20261104_144000_local-budget-extraction__delegate-nebflow-6f8eab41.md` | `local-budget-extraction__20261104_144000__delegate-nebflow-6f8eab41.md` | `delegate-nebflow-6f8eab41` | 5 | 4 | `20261104_142000_multi-device-shared-compute__delegate-nebflow-01df9dc9.md`<br>`20261104_133700_memory-mitigations.md`<br>`20261104_150500_sbt-client-server-verification__delegate-nebflow-3f40b03d.md` | — |  |
| 220 | `（根）` | `20261104_144500_zhang-anqi-container-system-eval__delegate-nebflow-5022b4e0.md` | `zhang-anqi-container-system-eval__20261104_144500__delegate-nebflow-5022b4e0.md` | `delegate-nebflow-5022b4e0` | 0 | 0 | — | — |  |
| 221 | `（根）` | `20261104_144555_msg-footer-geometry__ui-final-four.md` | `msg-footer-geometry__20261104_144555__ui-final-four.md` | `ui-final-four` | 2 | 2 | `20261104_152500_ui-final-four-independent-review__ui-final-four.md`<br>`20261104_173200_ui-final-four__delegate-nebflow-183b016d.md` | — |  |
| 222 | `（根）` | `20261104_150049_side-tab-legibility__ui-final-four.md` | `side-tab-legibility__20261104_150049__ui-final-four.md` | `ui-final-four` | 3 | 3 | `20261104_152500_ui-final-four-independent-review__ui-final-four.md`<br>`20261104_152600_process-leak-fold-roots__delegate-nebflow-1737f0ec.md`<br>`20261104_173200_ui-final-four__delegate-nebflow-183b016d.md` | — |  |
| 223 | `（根）` | `20261104_150500_sbt-client-server-verification__delegate-nebflow-3f40b03d.md` | `sbt-client-server-verification__20261104_150500__delegate-nebflow-3f40b03d.md` | `delegate-nebflow-3f40b03d` | 2 | 1 | `20261104_144000_local-budget-extraction__delegate-nebflow-6f8eab41.md` | — |  |
| 224 | `（根）` | `20261104_151123_ui-batch-delivery-audit.md` | `ui-batch-delivery-audit__20261104_151123.md` | — | 6 | 3 | `20261104_173200_ui-final-four__delegate-nebflow-183b016d.md`<br>`20261104_152600_process-leak-fold-roots__delegate-nebflow-1737f0ec.md`<br>`20261104_154500_self-explaining-cleanup__delegate-nebflow-8b0a8e45.md` | — |  |
| 225 | `（根）` | `20261104_152500_ui-final-four-independent-review__ui-final-four.md` | `ui-final-four-independent-review__20261104_152500__ui-final-four.md` | `ui-final-four` | 2 | 1 | `20261104_173200_ui-final-four__delegate-nebflow-183b016d.md` | — |  |
| 226 | `（根）` | `20261104_152600_process-leak-fold-roots__delegate-nebflow-1737f0ec.md` | `process-leak-fold-roots__20261104_152600__delegate-nebflow-1737f0ec.md` | `delegate-nebflow-1737f0ec` | 0 | 0 | — | — |  |
| 227 | `（根）` | `20261104_152704_interrupt-context-recovery__interrupt-recovery-20261104.md` | `interrupt-context-recovery__20261104_152704__interrupt-recovery-20261104.md` | `interrupt-recovery-20261104` | 0 | 0 | — | — |  |
| 228 | `（根）` | `20261104_153209_presence-direct-root-cause.md` | `presence-direct-root-cause__20261104_153209.md` | — | 6 | 2 | `20261104_163000_p2p-direct-service-design__delegate-nebflow-40d3b47a.md`<br>`20261104_173000_route-tiers-tailscale-p2p-relay__delegate-nebflow-4d537ce5.md` | — |  |
| 229 | `（根）` | `20261104_154500_self-explaining-cleanup__delegate-nebflow-8b0a8e45.md` | `self-explaining-cleanup__20261104_154500__delegate-nebflow-8b0a8e45.md` | `delegate-nebflow-8b0a8e45` | 1 | 1 | `index.md` | ✅ |  |
| 230 | `（根）` | `20261104_161245_frontend-paradigm-a-ask-component__ask-paradigm-a.md` | `frontend-paradigm-a-ask-component__20261104_161245__ask-paradigm-a.md` | `ask-paradigm-a` | 0 | 0 | — | — |  |
| 231 | `（根）` | `20261104_163000_p2p-direct-service-design__delegate-nebflow-40d3b47a.md` | `p2p-direct-service-design__20261104_163000__delegate-nebflow-40d3b47a.md` | `delegate-nebflow-40d3b47a` | 2 | 1 | `20261104_173000_route-tiers-tailscale-p2p-relay__delegate-nebflow-4d537ce5.md` | — |  |
| 232 | `（根）` | `20261104_164800_delegate-stall-audit__delegate-nebflow-8d6d3cba.md` | `delegate-stall-audit__20261104_164800__delegate-nebflow-8d6d3cba.md` | `delegate-nebflow-8d6d3cba` | 1 | 1 | `20261104_174500_mergequeue-three-gaps-r2-r1-r5__delegate-nebflow-b3bdf8d3.md` | — |  |
| 233 | `（根）` | `20261104_170934_circular-gate-import-cycle-cut__delegate-nebflow-b1d91721.md` | `circular-gate-import-cycle-cut__20261104_170934__delegate-nebflow-b1d91721.md` | `delegate-nebflow-b1d91721` | 1 | 1 | `index.md` | ✅ |  |
| 234 | `（根）` | `20261104_173000_route-tiers-tailscale-p2p-relay__delegate-nebflow-4d537ce5.md` | `route-tiers-tailscale-p2p-relay__20261104_173000__delegate-nebflow-4d537ce5.md` | `delegate-nebflow-4d537ce5` | 0 | 0 | — | — |  |
| 235 | `（根）` | `20261104_173200_ui-final-four__delegate-nebflow-183b016d.md` | `ui-final-four__20261104_173200__delegate-nebflow-183b016d.md` | `delegate-nebflow-183b016d` | 0 | 0 | — | — |  |
| 236 | `（根）` | `20261104_174500_mergequeue-three-gaps-r2-r1-r5__delegate-nebflow-b3bdf8d3.md` | `mergequeue-three-gaps-r2-r1-r5__20261104_174500__delegate-nebflow-b3bdf8d3.md` | `delegate-nebflow-b3bdf8d3` | 0 | 0 | — | — |  |
| 237 | `（根）` | `20261104_174558_fm-media-bubble-color-unify__bubble-color-unify.md` | `fm-media-bubble-color-unify__20261104_174558__bubble-color-unify.md` | `bubble-color-unify` | 2 | 1 | `20261104_180802_fm-media-bubble-color.md` | — |  |
| 238 | `（根）` | `20261104_180802_fm-media-bubble-color.md` | `fm-media-bubble-color__20261104_180802.md` | — | 0 | 0 | — | — |  |
| 239 | `（根）` | `20261104_time-wheel-picker-implementation__delegate-nebflow-4bff07ce.md` | `time-wheel-picker-implementation__20261104_000000__delegate-nebflow-4bff07ce.md` | `delegate-nebflow-4bff07ce` | 1 | 1 | `index.md` | ✅ | 缺时间分量 |

## 8. 不动清单（已是新制形态 / 无日期，97 件 `.md`）

按现有约束「已有文档不重命名 / 不移动 / 不回溯改正文」，这些文件本阶段一律不动；列出入链情况备查。

| # | 路径 | 入链 | 来源数 | 前 3 来源 | index.md |
| --- | --- | --- | --- | --- | --- |
| 1 | `actor-mailbox-pattern-review.md` | 1 | 1 | `archive.md` | — |
| 2 | `agent-icon-design-guide.md` | 1 | 1 | `archive.md` | — |
| 3 | `architecture/JARVIS_PHASE1_SPEC.md` | 1 | 1 | `archive.md` | — |
| 4 | `architecture/JARVIS_PHASE1_SUMMARY.md` | 1 | 1 | `archive.md` | — |
| 5 | `archive.md` | 5 | 2 | `20261103_210845_docs-landing-ledger-migration__delegate-nebflow-cceb5d85.md`<br>`index.md` | ✅ |
| 6 | `behavior-parity/README.md` | 0 | 0 | — | — |
| 7 | `behavior-parity/_INSTRUCTIONS.md` | 1 | 1 | `README.md` | — |
| 8 | `context-memory-analysis.md` | 1 | 1 | `20261101_193505_prompt-context-refresh-audit__delegate-nebflow-d32fca10.md` | — |
| 9 | `design-memory-v2.md` | 1 | 1 | `archive.md` | — |
| 10 | `design/analysis.md` | 1 | 1 | `archive.md` | — |
| 11 | `design/architecture-optimization-proposal.md` | 1 | 1 | `archive.md` | — |
| 12 | `design/card-rendering-protocol.md` | 1 | 1 | `archive.md` | — |
| 13 | `design/cardwin-origin-resolution-spec.zh-CN.md` | 2 | 1 | `20261104_103020_local-acceleration-audit.md` | — |
| 14 | `design/ci-testing.md` | 1 | 1 | `archive.md` | — |
| 15 | `design/cli-design.md` | 1 | 1 | `archive.md` | — |
| 16 | `design/delegate-supervision-revival-design.zh-CN.md` | 14 | 8 | `20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN.md`<br>`20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4.md`<br>`20261030_background-task-lifecycle.zh-CN.md` | ✅ |
| 17 | `design/design.md` | 0 | 0 | — | — |
| 18 | `design/device-provenance-spec.zh-CN.md` | 5 | 4 | `20261030_orchestration-execution-split.zh-CN.md`<br>`20261102_171500_neblink-device-identity-and-ownership.md`<br>`index.md` | ✅ |
| 19 | `design/flow-engine.md` | 1 | 1 | `archive.md` | — |
| 20 | `design/flow-tree.md` | 1 | 1 | `archive.md` | — |
| 21 | `design/git-management.md` | 1 | 1 | `archive.md` | — |
| 22 | `design/held-receipt-drain-silence-spec.zh-CN.md` | 5 | 4 | `20261030_background-task-lifecycle.zh-CN.md`<br>`20261101_083400_delegate-silent-death-analysis__delegate-nebflow-b618531a.md`<br>`20261030_orchestration-execution-split.zh-CN.md` | — |
| 23 | `design/imagegen-dual-layer-spec.zh-CN.md` | 0 | 0 | — | — |
| 24 | `design/inter-agent-mail.md` | 1 | 1 | `archive.md` | — |
| 25 | `design/memory-system.md` | 1 | 1 | `archive.md` | — |
| 26 | `design/mesh-sync-v2.md` | 1 | 1 | `archive.md` | — |
| 27 | `design/model-chain-anchor-spec.zh-CN.md` | 2 | 2 | `20261030_092600_preset-chain-migration__delegate-nebflow-acc72f03.md`<br>`index.md` | ✅ |
| 28 | `design/multi-view-refactor.md` | 1 | 1 | `archive.md` | — |
| 29 | `design/plugin-md-required-file-spec.zh-CN.md` | 6 | 5 | `20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN.md`<br>`20261102_225500_plugin-selfcontainment-m16-c19__delegate-nebflow-47c59129.md`<br>`20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN.md` | ✅ |
| 30 | `design/session-reminder.md` | 1 | 1 | `archive.md` | — |
| 31 | `design/subagent-row-display-name-spec.zh-CN.md` | 6 | 4 | `20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN.md`<br>`turn-liveness-spec.zh-CN.md`<br>`delegate-supervision-revival-design.zh-CN.md` | ✅ |
| 32 | `design/sync-design.md` | 1 | 1 | `archive.md` | — |
| 33 | `design/sync-mvp.md` | 1 | 1 | `archive.md` | — |
| 34 | `design/telemetry-dashboard.md` | 1 | 1 | `archive.md` | — |
| 35 | `design/turn-liveness-spec.zh-CN.md` | 7 | 5 | `20261030_background-task-lifecycle.zh-CN.md`<br>`held-receipt-drain-silence-spec.zh-CN.md`<br>`20261101_090600_delegate-terminal-mailbox-design__delegate-nebflow-4a58b3db.zh-CN.md` | ✅ |
| 36 | `design/visual-report-gate-plan.zh-CN.md` | 5 | 4 | `production-ops-checklist.zh-CN.md`<br>`20261103_000044_mobile-image-gesture-viewer__delegate-nebflow-12aef298.md`<br>`plugin-md-required-file-spec.zh-CN.md` | ✅ |
| 37 | `design/wiki-graph-paragraph-ref-design.zh-CN.md` | 6 | 4 | `20261029_111029_term-citation-research__delegate-nebflow-253ec234.zh-CN.md`<br>`20261029_123653_term-citation-batch1-spec__delegate-nebflow-dbfd1ffc.zh-CN.md`<br>`visual-report-gate-plan.zh-CN.md` | ✅ |
| 38 | `design/wiki-graph-redesign-design.zh-CN.md` | 7 | 4 | `20261029_111029_term-citation-research__delegate-nebflow-253ec234.zh-CN.md`<br>`wiki-graph-paragraph-ref-design.zh-CN.md`<br>`20261028_canvas-ask-mode-design.zh-CN.md` | ✅ |
| 39 | `flow-redesign-plan.md` | 1 | 1 | `archive.md` | — |
| 40 | `glossary.md` | 302 | 118 | `20261104_114857_multi-tenant-dataroot-design.md`<br>`20261104_104918_project-device-parity-design.md`<br>`20261104_115529_actor-fsm-backend-spec__actor-fsm-batch.md` | ✅ |
| 41 | `index.md` | 82 | 66 | `20261103_210845_docs-landing-ledger-migration__delegate-nebflow-cceb5d85.md`<br>`archive.md`<br>`20261029_113217_外部md渲染器锚点与hover预览调研__flow-7c703875.md` | — |
| 42 | `issues/000-issue-template.md` | 0 | 0 | — | — |
| 43 | `issues/010-plugin-system-architecture.md` | 0 | 0 | — | — |
| 44 | `issues/011-mcp-tool-auto-discovery.md` | 0 | 0 | — | — |
| 45 | `issues/012-extensible-tool-cards.md` | 1 | 1 | `20261104_140500_tool-card-image-face__delegate-nebflow-c4696e5c.md` | — |
| 46 | `issues/014-chat-history-persistence-redesign.md` | 0 | 0 | — | — |
| 47 | `issues/015-merge-session-agent-actor.md` | 0 | 0 | — | — |
| 48 | `issues/016-flatten-agent-state.md` | 0 | 0 | — | — |
| 49 | `issues/017-actor-io-async-cleanup.md` | 0 | 0 | — | — |
| 50 | `issues/018-context-manage-bug.md` | 0 | 0 | — | — |
| 51 | `issues/019-edit-tool-single-mode-refactor.md` | 0 | 0 | — | — |
| 52 | `issues/020-slash-ask-command.md` | 0 | 0 | — | — |
| 53 | `issues/021-remove-stagnation-detection.md` | 0 | 0 | — | — |
| 54 | `issues/022-mcp-production-hardening.md` | 0 | 0 | — | — |
| 55 | `issues/023-decouple-core-from-agent.md` | 1 | 1 | `archive.md` | — |
| 56 | `issues/024-tool-lifecycle-hooks.md` | 0 | 0 | — | — |
| 57 | `issues/025-agent-centric-architecture.md` | 0 | 0 | — | — |
| 58 | `issues/025-external-event-system.md` | 0 | 0 | — | — |
| 59 | `issues/026-unified-agent-registration.md` | 0 | 0 | — | — |
| 60 | `issues/templates/README.md` | 0 | 0 | — | — |
| 61 | `issues/templates/_base.md` | 0 | 0 | — | — |
| 62 | `issues/templates/_glossary.md` | 0 | 0 | — | — |
| 63 | `issues/templates/bug.md` | 0 | 0 | — | — |
| 64 | `issues/templates/feature.md` | 0 | 0 | — | — |
| 65 | `issues/templates/perf.md` | 0 | 0 | — | — |
| 66 | `issues/templates/quickfix.md` | 0 | 0 | — | — |
| 67 | `issues/templates/refactor.md` | 0 | 0 | — | — |
| 68 | `issues/templates/tech-debt.md` | 0 | 0 | — | — |
| 69 | `issues/templates/test.md` | 0 | 0 | — | — |
| 70 | `parallel-session-resources.zh-CN.md` | 10 | 6 | `production-ops-checklist.zh-CN.md`<br>`20261102_121500_host-binding-audit__delegate-nebflow-7d8475af.md`<br>`20261102_161500_keyboard-layer-monaco-quickinput__delegate-nebflow-17658d72.md` | ✅ |
| 71 | `production-ops-checklist.zh-CN.md` | 8 | 6 | `20261102_121500_host-binding-audit__delegate-nebflow-7d8475af.md`<br>`parallel-session-resources.zh-CN.md`<br>`20261102_161500_keyboard-layer-monaco-quickinput__delegate-nebflow-17658d72.md` | ✅ |
| 72 | `reviews/parity/01-tool-execution.md` | 1 | 1 | `README.md` | — |
| 73 | `reviews/parity/02-agent-runtime.md` | 1 | 1 | `README.md` | — |
| 74 | `reviews/parity/03-compaction-memory-maintenance.md` | 1 | 1 | `README.md` | — |
| 75 | `reviews/parity/04-flow-engine.md` | 1 | 1 | `README.md` | — |
| 76 | `reviews/parity/05-teams.md` | 1 | 1 | `README.md` | — |
| 77 | `reviews/parity/06-system-prompt.md` | 1 | 1 | `README.md` | — |
| 78 | `reviews/parity/07-memory-skills-external-tools.md` | 1 | 1 | `README.md` | — |
| 79 | `reviews/parity/08-session-management.md` | 1 | 1 | `README.md` | — |
| 80 | `reviews/parity/09-ws-and-rest.md` | 1 | 1 | `README.md` | — |
| 81 | `reviews/parity/10-scheduler.md` | 1 | 1 | `README.md` | — |
| 82 | `reviews/parity/11-daemon.md` | 1 | 1 | `README.md` | — |
| 83 | `reviews/parity/12-neblink-dropbox.md` | 1 | 1 | `README.md` | — |
| 84 | `reviews/parity/13-llm-layer.md` | 1 | 1 | `README.md` | — |
| 85 | `reviews/parity/14-mcp.md` | 1 | 1 | `README.md` | — |
| 86 | `reviews/parity/15-hooks.md` | 1 | 1 | `README.md` | — |
| 87 | `reviews/parity/16-delegate-mail-permissions.md` | 1 | 1 | `README.md` | — |
| 88 | `reviews/parity/17-plan-ask-empty-response.md` | 1 | 1 | `README.md` | — |
| 89 | `reviews/parity/18-misc-subsystems.md` | 1 | 1 | `README.md` | — |
| 90 | `reviews/parity/README.md` | 0 | 0 | — | — |
| 91 | `reviews/parity/REMAINING-AUDIT.md` | 1 | 1 | `README.md` | — |
| 92 | `reviews/scala-rust-diff.md` | 1 | 1 | `archive.md` | — |
| 93 | `runbooks/kai-dispatch-route.md` | 5 | 4 | `20261104_103020_local-acceleration-audit.md`<br>`20261104_113647_kai-probe-tax.md`<br>`20261031_213900_dual-brand-port-separation__delegate-nebflow-51b364b1.zh-CN.md` | — |
| 94 | `runbooks/kai-stand-migration.md` | 1 | 1 | `20261031_213900_dual-brand-port-separation__delegate-nebflow-51b364b1.zh-CN.md` | — |
| 95 | `scala-context-management-report.md` | 1 | 1 | `20261101_193505_prompt-context-refresh-audit__delegate-nebflow-d32fca10.md` | — |
| 96 | `templates/console-403-diagnosis.md` | 3 | 2 | `console-load-perf.md`<br>`index.md` | ✅ |
| 97 | `templates/console-load-perf.md` | 3 | 2 | `console-403-diagnosis.md`<br>`index.md` | ✅ |

另有非 `.md` 资产 **68** 个（png/gif/svg/html/patch/log/txt/sh/py），不在文档命名制范围，不动：

| 扩展名 | 数量 |
| --- | --- |
| `.png` | 37 |
| `.html` | 12 |
| `.log` | 7 |
| `.patch` | 4 |
| `.svg` | 3 |
| `.gif` | 2 |
| `.sh` | 1 |
| `.txt` | 1 |
| `.py` | 1 |

## 9. 附：引用健康度（换名前基线）

- 全库 wikilink 命中现有文档的不同目标：273
- 悬空链接目标（指向不存在的文件）：24 个不同目标 / 47 次
- 悬空 top10（换名后若按旧名导航会进一步失联的候选）：

| 目标键 | 出现次数 |
| --- | --- |
| `目标` | 10 |
| `GCR` | 5 |
| `Three laws of motion` | 3 |
| `entities` | 3 |
| `DCL` | 3 |
| `doc` | 3 |
| `2023-01-01` | 2 |
| `<本档>` | 2 |
| `About Obsidian` | 1 |
| `Help and support` | 1 |

## 10. 其他项目待迁移清单（只扫不改）

### 10.1 主口径：`~/.stand/projects/*/.stand/docs/`

| 项目 | 文件总数 | `.md` | 旧制待迁移 | 占比 |
| --- | --- | --- | --- | --- |
| `DOSTEL` | 2 | 2 | **1** | 50% |
| `stand` | 404 | 336 | **239** | 71% |
| `stand-website` | 6 | 6 | **5** | 83% |
| **合计** | **412** | **344** | **245** | — |

> 任务书估值「约 28 个 docs 目录 / 共约 338 文件」与实测不符：严格限定 `.stand/docs/` 只有 **3** 个项目、共 **412** 个文件（`.md` 344）。28 这个数字对应的是「projects 下 maxdepth≤4 所有名为 `docs` 的目录」的原始 find 计数（含 `node_modules`、`.git`、`.nebflow/worktrees` 等噪声目录），见 §10.2。以本表为准。

### 10.2 噪声过滤后的全量 `docs/` 目录盘点

排除 `node_modules/`、`.git/`、`.nebflow/worktrees`、`.nebflow/reports` 后实测：

| # | 目录 | 文件总数 | `.md` | 旧制待迁移 |
| --- | --- | --- | --- | --- |
| 1 | `CZT/docs` | 12 | 7 | 0 |
| 2 | `CZT/kai/docs` | 39 | 14 | 0 |
| 3 | `DOSTEL/.stand/docs` | 2 | 2 | 1 |
| 4 | `chisel-fpga/docs` | 5 | 3 | 0 |
| 5 | `general/nebflow-docs-20260922/docs` | 22 | 8 | 8 |
| 6 | `html-deck-studio/ai-fpga-deck-src/docs` | 17 | 5 | 0 |
| 7 | `jev-test/docs` | 6 | 1 | 0 |
| 8 | `likemeter/.nebflow/docs` | 2 | 2 | 0 |
| 9 | `link-server/.nebflow/docs` | 1 | 1 | 0 |
| 10 | `link-server/.nebflow/nebflow-fix/docs` | 5 | 0 | 0 |
| 11 | `link-server/docs` | 2 | 2 | 1 |
| 12 | `slideblocks/dist/web/docs` | 7 | 0 | 0 |
| 13 | `slideblocks/docs` | 53 | 41 | 0 |
| 14 | `stand-website/.stand/docs` | 6 | 6 | 5 |
| 15 | `stand-website/app/[lang]/docs` | 3 | 0 | 0 |
| 16 | `stand-website/components/docs` | 6 | 0 | 0 |
| 17 | `stand-website/content/docs` | 28 | 0 | 0 |
| 18 | `stand-website/docs` | 4 | 4 | 3 |
| 19 | `stand/.stand/docs` | 404 | 336 | 239 |
| 20 | `voice-recognition-test/hf-s2s/demo/docs` | 1 | 1 | 0 |
| 21 | `voice-recognition-test/hf-s2s/docs` | 2 | 0 | 0 |
| 22 | `视觉工作室/docs` | 4 | 4 | 3 |
| 23 | `逻辑/docs` | 7 | 5 | 0 |
| — | **合计（23 个目录）** | **638** | **442** | **260** |

### 10.3 非 stand 项目的旧制文件名明细（其余项目 `old` > 0 者）

**`DOSTEL`**（1 件）：

- `20261103_215300_cabin_neutron_three_figures_tutorial.md`

**`stand-website`**（5 件）：

- `20261104_154911_旧域兜底实测与兜底层定位__legacy-callback-cleanup-r2.md`
- `20261104_161205_旧域callback不删裁定与影响面__legacy-callback-cleanup-r2.md`
- `20261104_162500_旧域callback零改动验收读数__legacy-callback-cleanup-r2.md`
- `20261104_162800_legacy-idp-callback-cleanup__legacy-callback-cleanup-r2.md`
- `20261104_171318_旧域提示层落层实测与不落地裁定__legacy-domain-paving.md`

> 本清单**只扫不改**；这些 vault 的迁移排期由派单侧决定，未在本节点触碰任何一个字节。

### 10.4 非 `.stand/docs` 工作仓里的旧制文件名明细（同样只扫不改）

**`general/nebflow-docs-20260922/docs`**（8 件 / 共 22 文件）：

- `20260922_083445_nebflow-repo-overview__chain-n-e605092f.md`
- `20260922_084831_node-customization-depth__chain-n-e605092f.md`
- `20260922_091500_build-verify-chain__chain-n-e605092f.md`
- `20260922_102055_code-audit-reuse-and-cycles__chain-n-5b785121.md`
- `20260922_103023_coding-agent-workflow-differences__chain-n-5b785121.md`
- `20260922_110036_target-layering-and-violations__chain-n-5b785121.md`
- `20260922_110929_large-file-split-plan-and-wheel-app-layout__chain-n-5b785121.md`
- `20260922_125200_p3-pilot-nodetools-split__chain-n-5b785121.md`

**`link-server/docs`**（1 件 / 共 2 文件）：

- `20261028_2255_portal-identity-passthrough__delegate-nebflow-0c670d46.md`

**`stand-website/docs`**（3 件 / 共 4 文件）：

- `20261101_151705_github-exposure-audit-staging-fix.md`
- `20261102_193000_device-remove-capability__delegate-nebflow-e2801b7b.md`
- `20261103_104000_device-capabilities-ledger__delegate-nebflow-20368f18.md`

**`视觉工作室/docs`**（3 件 / 共 4 文件）：

- `20261102_224000_deepseek-xhs-template-audit.md`
- `20261102_230000_deepseek-xhs-textcard-rebuild.md`
- `20261103_001500_stand-xhs-text-card-v2-cover-body.md`


## 11. 复跑方式

```bash
python3 '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-71767a56/reports-evidence/doc-naming-migration__20261104_181200/build-manifest.py'   # 只读扫描 vault，重写本文件
```

脚本不做任何写操作落在 vault 内（唯一写入目标是本目录的 `manifest.md`）；改 topic 判定或口径时改脚本顶部的常量，再复跑即可。

