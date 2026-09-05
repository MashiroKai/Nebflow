> **本目录是 nebflow 项目规格文档的唯一标准源**（作者 2026-09-05 裁定：docs→Spec 迁移，git 管理版本，无 V2/V3 后缀；同文档版本族归一为单一标准源）。
> 迁移批次：2026-09-05 docs2spec（基线 main@533a2a0a）。旧位置 `~/.nebflow/docs/Nebflow/` 退役由宿主命令执行（待本分支合并后）。

# Spec 索引

**统计**：活文档/规格/设计/审计/报告等 md 文档 264 份 + 关键附件 101 件 = 共 365 文件。📎 = 文档内引用的关联截图/原型仍在 `~/.nebflow/docs/Nebflow/assets/`（暂留组，见文末去向表）。

**归并说明（去版本化）**：同文档版本族已归一为单一标准源，共 2 族 3 件旧版退役（内容已取代/并入，git 历史 ~/.nebflow repo 可溯）：
- `msg-window-interaction-spec.md` ← 归并 `msg-window-interaction-spec-tmp.md`（v1.0 初稿）
- `20260830_roadshow-script.md` ← 归并 `20260830_roadshow-script-draft.md`（v0.1 草稿；基文=20260830_roadshow-script-final.md 改名去后缀）

**保留多阶段记录（非版本族，不归并）**：message-search-spec.md 与 20260817_message-search-spec-v2.md 并存——活文档自身声明 v2 为「阶段文档归档，内容不并入仅引用」；flowmap-graphview 三稿（design/galaxy-design/v3-final）为显式封存链（sealed→sealed→frozen），各稿有独有内容与继任指针。

## 活文档 · 归并标准源

| 文件 | 性质 | 旧路径（~/.nebflow/docs/） |
|---|---|---|
| `20260830_roadshow-script.md` | 讲稿（最终稿 v1.0）· **归并标准源**：含 20260830_roadshow-script-draft.md（v0.1 草稿，已取代） | Nebflow/20260830_roadshow-script-final.md |
| `msg-window-interaction-spec.md` | 规格书（活文档 v1.2）· **归并标准源**：含 msg-window-interaction-spec-tmp.md（v1.0 初稿，已取代） | Nebflow/msg-window-interaction-spec.md |

## 迁移文档（原样迁入，文件名保持）

| 文件 | 性质 | 旧路径（~/.nebflow/docs/） |
|---|---|---|
| `20250116_permission-passthrough-spec.md` | 规格书 | Nebflow/20250116_permission-passthrough-spec.md |
| `2026-08-17_subtask-usage-audit.md` | 审计 ·📎引用assets | Nebflow/2026-08-17_subtask-usage-audit.md |
| `2026-08-18_logto-stage1-integration.md` | 记录 | Nebflow/2026-08-18_logto-stage1-integration.md |
| `20260806_actor-performance-analysis.md` | 分析 | Nebflow/20260806_actor-performance-analysis.md |
| `20260806_agent-model-config-not-working.md` | 文档 | Nebflow/20260806_agent-model-config-not-working.md |
| `20260806_delegate-realtime-window.md` | 文档 | Nebflow/20260806_delegate-realtime-window.md |
| `20260806_rust-migration-analysis.md` | 分析 | Nebflow/20260806_rust-migration-analysis.md |
| `20260806_rust-migration-plan.md` | 规划 | Nebflow/20260806_rust-migration-plan.md |
| `20260811_permission-diagnosis.md` | 分析 | Nebflow/20260811_permission-diagnosis.md |
| `20260813_neblink-relay-design.md` | 设计 | Nebflow/20260813_neblink-relay-design.md |
| `20260814_ecosystem-R1-delegate-split.md` | 文档 | Nebflow/20260814_ecosystem-R1-delegate-split.md |
| `20260814_ecosystem-R2-creation-permissions.md` | 文档 | Nebflow/20260814_ecosystem-R2-creation-permissions.md |
| `20260814_ecosystem-R3-skill-system.md` | 文档 | Nebflow/20260814_ecosystem-R3-skill-system.md |
| `20260814_ecosystem-R4-flow-guide.md` | 记录 | Nebflow/20260814_ecosystem-R4-flow-guide.md |
| `20260814_ecosystem-R5-team-org.md` | 文档 | Nebflow/20260814_ecosystem-R5-team-org.md |
| `20260814_ecosystem-R6-agent-guide.md` | 记录 | Nebflow/20260814_ecosystem-R6-agent-guide.md |
| `20260814_ecosystem-R7-user-correction-loop.md` | 文档 | Nebflow/20260814_ecosystem-R7-user-correction-loop.md |
| `20260814_ecosystem-R8-flow-engine-upgrade.md` | 文档 | Nebflow/20260814_ecosystem-R8-flow-engine-upgrade.md |
| `20260814_mail-delivery-protocol.md` | 文档 | Nebflow/20260814_mail-delivery-protocol.md |
| `20260814_subagent-task-bug-report.md` | 报告 | Nebflow/20260814_subagent-task-bug-report.md |
| `20260814_subagent-task-status-fix.md` | 文档 | Nebflow/20260814_subagent-task-status-fix.md |
| `20260814_vision-live-test.md` | 文档 | Nebflow/20260814_vision-live-test.md |
| `20260814_vision-support-audit.md` | 审计 | Nebflow/20260814_vision-support-audit.md |
| `20260815_g3-structured-image-mail-delegate.md` | 文档 | Nebflow/20260815_g3-structured-image-mail-delegate.md |
| `20260815_g5-compaction-vision-retention.md` | 文档 | Nebflow/20260815_g5-compaction-vision-retention.md |
| `20260815_overnight-report.md` | 报告 | Nebflow/20260815_overnight-report.md |
| `20260815_overnight-taskbooks.md` | 文档 | Nebflow/20260815_overnight-taskbooks.md |
| `20260815_research-jpackage-desktop.md` | 调研 | Nebflow/20260815_research-jpackage-desktop.md |
| `20260815_tool-opt-edit.md` | 文档 | Nebflow/20260815_tool-opt-edit.md |
| `20260815_tool-opt-task-inventory.md` | 文档 | Nebflow/20260815_tool-opt-task-inventory.md |
| `20260816_frontend-quality-report.md` | 报告 | Nebflow/20260816_frontend-quality-report.md |
| `20260816_qa-toolopt-acceptance.md` | 评审/验收 | Nebflow/20260816_qa-toolopt-acceptance.md |
| `20260817_activity-bar-v1-1-visual-review.md` | 评审/验收 | Nebflow/20260817_activity-bar-v1-1-visual-review.md |
| `20260817_batch3-rebrand-acceptance.md` | 评审/验收 | Nebflow/20260817_batch3-rebrand-acceptance.md |
| `20260817_message-search-spec-v2.md` | 规格书 | Nebflow/20260817_message-search-spec-v2.md |
| `20260817_msg-search-v31-visual-review.md` | 评审/验收 | Nebflow/20260817_msg-search-v31-visual-review.md |
| `20260817_msg-search-visual-review.md` | 评审/验收 | Nebflow/20260817_msg-search-visual-review.md |
| `20260817_rebrand-parameterization.md` | 文档 | Nebflow/20260817_rebrand-parameterization.md |
| `20260818_107-api-test.md` | 文档 | Nebflow/20260818_107-api-test.md |
| `20260818_api-concurrency-design.md` | 设计 | Nebflow/20260818_api-concurrency-design.md |
| `20260818_cache-miss-analysis.md` | 分析 | Nebflow/20260818_cache-miss-analysis.md |
| `20260818_cache-optimization-plan.md` | 规划 | Nebflow/20260818_cache-optimization-plan.md |
| `20260818_history-pagination-assessment.md` | 文档 | Nebflow/20260818_history-pagination-assessment.md |
| `20260818_msg-search-v31-re-review.md` | 评审/验收 ·📎引用assets | Nebflow/20260818_msg-search-v31-re-review.md |
| `20260818_turn-budget-fix.md` | 文档 | Nebflow/20260818_turn-budget-fix.md |
| `20260819_default-preset-semantics.md` | 文档 ·📎引用assets | Nebflow/20260819_default-preset-semantics.md |
| `20260819_let-it-crash-agent-supervision.md` | 文档 | Nebflow/20260819_let-it-crash-agent-supervision.md |
| `20260819_reminder-audit.md` | 审计 | Nebflow/20260819_reminder-audit.md |
| `20260819_rpm-reference.md` | 文档 | Nebflow/20260819_rpm-reference.md |
| `20260819_user-knowledge-leak-audit.md` | 审计 | Nebflow/20260819_user-knowledge-leak-audit.md |
| `20260820_107-api-stability.md` | 文档 | Nebflow/20260820_107-api-stability.md |
| `20260820_107-longoutput-hang.md` | 文档 | Nebflow/20260820_107-longoutput-hang.md |
| `20260820_delegate-completion-no-turn.md` | 文档 | Nebflow/20260820_delegate-completion-no-turn.md |
| `20260820_router-no-user-msg-audit.md` | 审计 | Nebflow/20260820_router-no-user-msg-audit.md |
| `20260820_system-reminder-audit.md` | 审计 ·📎引用assets | Nebflow/20260820_system-reminder-audit.md |
| `20260820_tool-result-ttl.md` | 文档 | Nebflow/20260820_tool-result-ttl.md |
| `20260821_qwen-fragment-flood.md` | 文档 ·📎引用assets | Nebflow/20260821_qwen-fragment-flood.md |
| `20260822_cache-reconciliation-report.md` | 分析 | Nebflow/20260822_cache-reconciliation-report.md |
| `20260822_write-only-loop-analysis.md` | 分析 | Nebflow/20260822_write-only-loop-analysis.md |
| `20260823_deepseek-270-vs-205-reconciliation.md` | 分析 | Nebflow/20260823_deepseek-270-vs-205-reconciliation.md |
| `20260823_qa-vision-capability.md` | 评审/验收 | Nebflow/20260823_qa-vision-capability.md |
| `20260823_token-call-discrepancy.md` | 分析 ·📎引用assets | Nebflow/20260823_token-call-discrepancy.md |
| `20260823_websearch-audit-and-plan.md` | 规划 ·📎引用assets | Nebflow/20260823_websearch-audit-and-plan.md |
| `20260824_entity-icons-visual-spec.md` | 规格书 | Nebflow/20260824_entity-icons-visual-spec.md |
| `20260824_flow-dynamic-fanout-plan.md` | 规划 | Nebflow/20260824_flow-dynamic-fanout-plan.md |
| `20260824_frozen-error-recovery-plan.md` | 规划 | Nebflow/20260824_frozen-error-recovery-plan.md |
| `20260824_frozen-input-visual-spec.md` | 规格书 | Nebflow/20260824_frozen-input-visual-spec.md |
| `20260824_jingwei-application-v4.md` | 文档 | Nebflow/20260824_jingwei-application-v4.md |
| `20260824_website-publish-flow.md` | 文档 | Nebflow/20260824_website-publish-flow.md |
| `20260825_bash-tool-resilience-design.md` | 设计 | Nebflow/20260825_bash-tool-resilience-design.md |
| `20260825_branch-governance-ci-cd-plan.md` | 规划 ·📎引用assets | Nebflow/20260825_branch-governance-ci-cd-plan.md |
| `20260825_flow-redesign-research.md` | 设计 | Nebflow/20260825_flow-redesign-research.md |
| `20260825_global-reference-spec.md` | 规格书 | Nebflow/20260825_global-reference-spec.md |
| `20260825_header-collision-spec.md` | 规格书 | Nebflow/20260825_header-collision-spec.md |
| `20260825_mail-queue-idle-delivery.md` | 文档 | Nebflow/20260825_mail-queue-idle-delivery.md |
| `20260825_pi-agent-insights.md` | 记录 ·📎引用assets | Nebflow/20260825_pi-agent-insights.md |
| `20260825_slideblocks-ai-fpga-dev-separation.md` | 文档 ·📎引用assets | Nebflow/20260825_slideblocks-ai-fpga-dev-separation.md |
| `20260825_subagents-panel-spec.md` | 规格书 | Nebflow/20260825_subagents-panel-spec.md |
| `20260825_team-manager-task-tool-spec.md` | 规格书 | Nebflow/20260825_team-manager-task-tool-spec.md |
| `20260825_websearch-fetch-optimization.md` | 文档 | Nebflow/20260825_websearch-fetch-optimization.md |
| `20260826_flow-complement-design.md` | 设计 | Nebflow/20260826_flow-complement-design.md |
| `20260826_flow-dag-compiler-design.md` | 设计 | Nebflow/20260826_flow-dag-compiler-design.md |
| `20260826_flow-node-llm-supervision.md` | 文档 ·📎引用assets | Nebflow/20260826_flow-node-llm-supervision.md |
| `20260826_permission-autoall-leak.md` | 文档 | Nebflow/20260826_permission-autoall-leak.md |
| `20260827_agent-supervision-trio-design.md` | 设计 | Nebflow/20260827_agent-supervision-trio-design.md |
| `20260827_dedicated-agents-taxonomy-design.md` | 设计 | Nebflow/20260827_dedicated-agents-taxonomy-design.md |
| `20260827_logto-deployment.md` | 记录 ·📎引用assets | Nebflow/20260827_logto-deployment.md |
| `20260828_a2a-phase1-direction-addendum.md` | 文档 | Nebflow/20260828_a2a-phase1-direction-addendum.md |
| `20260828_github-quick-login-research.md` | 调研 | Nebflow/20260828_github-quick-login-research.md |
| `20260828_logto-client-login-plan.md` | 规划 | Nebflow/20260828_logto-client-login-plan.md |
| `20260828_loop-detected-systemic-analysis.md` | 分析 | Nebflow/20260828_loop-detected-systemic-analysis.md |
| `20260828_mail-queue-unified-idle-and-cleanup.md` | 文档 | Nebflow/20260828_mail-queue-unified-idle-and-cleanup.md |
| `20260828_nebula-loop-guard-protocol.md` | 文档 | Nebflow/20260828_nebula-loop-guard-protocol.md |
| `20260828_shell-session-restart-rebuild.md` | 文档 | Nebflow/20260828_shell-session-restart-rebuild.md |
| `20260830_apple-dictation-integration.md` | 文档 | Nebflow/20260830_apple-dictation-integration.md |
| `20260830_bash-foreground-ruling-audit.md` | 审计 | Nebflow/20260830_bash-foreground-ruling-audit.md |
| `20260830_canvas-tabs-backend-audit.md` | 审计 | Nebflow/20260830_canvas-tabs-backend-audit.md |
| `20260830_compact-injection-shield-analysis.md` | 分析 | Nebflow/20260830_compact-injection-shield-analysis.md |
| `20260830_loop-guard-simplification-eval.md` | 文档 | Nebflow/20260830_loop-guard-simplification-eval.md |
| `20260830_mic-orb-layer-breakdown.md` | 文档 ·📎引用assets | Nebflow/20260830_mic-orb-layer-breakdown.md |
| `20260830_obsidian-research.md` | 调研 | Nebflow/20260830_obsidian-research.md |
| `20260830_prompt-memory-audit.md` | 审计 | Nebflow/20260830_prompt-memory-audit.md |
| `20260830_restart-script-stability.md` | 文档 | Nebflow/20260830_restart-script-stability.md |
| `20260830_roadshow-data-research.md` | 调研 | Nebflow/20260830_roadshow-data-research.md |
| `20260830_tab-restore-analysis.md` | 分析 | Nebflow/20260830_tab-restore-analysis.md |
| `20260830_task-tools-completion-audit.md` | 审计 | Nebflow/20260830_task-tools-completion-audit.md |
| `20260830_unified-panel-design-spec.md` | 规格书 ·📎引用assets | Nebflow/20260830_unified-panel-design-spec.md |
| `20260831_flow-syntax-and-delegate-unification.md` | 文档 ·📎引用assets | Nebflow/20260831_flow-syntax-and-delegate-unification.md |
| `20260831_memory-system-redesign.md` | 设计 | Nebflow/20260831_memory-system-redesign.md |
| `20260831_project-node-architecture.md` | 文档 ·📎引用assets | Nebflow/20260831_project-node-architecture.md |
| `20260901_account-data-and-logto-admin.md` | 记录 ·📎引用assets | Nebflow/20260901_account-data-and-logto-admin.md |
| `20260901_agent-recursion-analysis.md` | 分析 | Nebflow/20260901_agent-recursion-analysis.md |
| `20260901_agentic-auto-experiment-architecture.md` | 文档 | Nebflow/20260901_agentic-auto-experiment-architecture.md |
| `20260901_agentic-auto-experiment-research-landscape.md` | 调研 | Nebflow/20260901_agentic-auto-experiment-research-landscape.md |
| `20260901_login-chain-analysis.md` | 分析 | Nebflow/20260901_login-chain-analysis.md |
| `20260901_login-chain-frontend-qa.md` | 评审/验收 | Nebflow/20260901_login-chain-frontend-qa.md |
| `20260901_logo-v4-qa.md` | 评审/验收 | Nebflow/20260901_logo-v4-qa.md |
| `20260901_logto-dashboard-and-telemetry.md` | 记录 | Nebflow/20260901_logto-dashboard-and-telemetry.md |
| `20260901_mhs-model-hardware-standard.md` | 文档 | Nebflow/20260901_mhs-model-hardware-standard.md |
| `20260901_node-cancel-recursion-incident.md` | 文档 | Nebflow/20260901_node-cancel-recursion-incident.md |
| `20260901_node-frontend-agentfile-qa-acceptance.md` | 评审/验收 | Nebflow/20260901_node-frontend-agentfile-qa-acceptance.md |
| `20260901_node-frontend-contract-qa-acceptance.md` | 评审/验收 | Nebflow/20260901_node-frontend-contract-qa-acceptance.md |
| `20260901_node-frontend-qa-acceptance.md` | 评审/验收 | Nebflow/20260901_node-frontend-qa-acceptance.md |
| `20260901_panel-reliability-fix-qa.md` | 评审/验收 | Nebflow/20260901_panel-reliability-fix-qa.md |
| `20260901_phase1-dispatch-discipline.md` | 文档 | Nebflow/20260901_phase1-dispatch-discipline.md |
| `20260901_project-node-contract.md` | 文档 | Nebflow/20260901_project-node-contract.md |
| `20260901_project-node-stage0-frontend-qa.md` | 评审/验收 | Nebflow/20260901_project-node-stage0-frontend-qa.md |
| `20260901_restart-postverify-agentmd.md` | 文档 | Nebflow/20260901_restart-postverify-agentmd.md |
| `20260901_restart-postverify-frontend.md` | 文档 | Nebflow/20260901_restart-postverify-frontend.md |
| `20260901_rm-minchars-frontend-qa.md` | 评审/验收 | Nebflow/20260901_rm-minchars-frontend-qa.md |
| `20260901_tool-result-size-protection.md` | 文档 | Nebflow/20260901_tool-result-size-protection.md |
| `20260901_unified-domain-plan.md` | 规划 ·📎引用assets | Nebflow/20260901_unified-domain-plan.md |
| `20260902-history-replay-1-degraded.png` | 附件（png） | Nebflow/20260902-history-replay-1-degraded.png |
| `20260902-history-replay-2-replay-fixed.png` | 附件（png） | Nebflow/20260902-history-replay-2-replay-fixed.png |
| `20260902-history-replay-3-live.png` | 附件（png） | Nebflow/20260902-history-replay-3-live.png |
| `20260902-history-replay-report.html` | 附件（html） | Nebflow/20260902-history-replay-report.html |
| `20260902_account-flow-improvement-plan.md` | 规划 | Nebflow/20260902_account-flow-improvement-plan.md |
| `20260902_agents-md-migration-report.md` | 报告 | Nebflow/20260902_agents-md-migration-report.md |
| `20260902_agents-md-standard-survey.md` | 记录 | Nebflow/20260902_agents-md-standard-survey.md |
| `20260902_avatar-upload-deadlink-findings.md` | 分析 | Nebflow/20260902_avatar-upload-deadlink-findings.md |
| `20260902_deps-dependency-connection-design.md` | 设计 | Nebflow/20260902_deps-dependency-connection-design.md |
| `20260902_dispatcher-reentry-feedback-design.md` | 设计 | Nebflow/20260902_dispatcher-reentry-feedback-design.md |
| `20260902_dispatcher-singleton-verification.md` | 文档 | Nebflow/20260902_dispatcher-singleton-verification.md |
| `20260902_explorer-nebflow-dir-dark.png` | 附件（png） | Nebflow/20260902_explorer-nebflow-dir-dark.png |
| `20260902_explorer-nebflow-dir-light.png` | 附件（png） | Nebflow/20260902_explorer-nebflow-dir-light.png |
| `20260902_flowmap-blocked-badge-dark.png` | 附件（png） | Nebflow/20260902_flowmap-blocked-badge-dark.png |
| `20260902_flowmap-blocked-badge-light.png` | 附件（png） | Nebflow/20260902_flowmap-blocked-badge-light.png |
| `20260902_flowmap-blocked-detail-dark.png` | 附件（png） | Nebflow/20260902_flowmap-blocked-detail-dark.png |
| `20260902_flowmap-blocked-detail-light.png` | 附件（png） | Nebflow/20260902_flowmap-blocked-detail-light.png |
| `20260902_flowmap-engine-evolution-design.md` | 设计 | Nebflow/20260902_flowmap-engine-evolution-design.md |
| `20260902_logto-dashboard-admin-audit.md` | 审计 ·📎引用assets | Nebflow/20260902_logto-dashboard-admin-audit.md |
| `20260902_logto-email-signin-missing.md` | 记录 | Nebflow/20260902_logto-email-signin-missing.md |
| `20260902_micorb-9state-matrix-dark.png` | 附件（png） | Nebflow/20260902_micorb-9state-matrix-dark.png |
| `20260902_micorb-9state-matrix-light.png` | 附件（png） | Nebflow/20260902_micorb-9state-matrix-light.png |
| `20260902_micorb-presets-design.md` | 设计 | Nebflow/20260902_micorb-presets-design.md |
| `20260902_micorb-presets-preview.html` | 附件（html） | Nebflow/20260902_micorb-presets-preview.html |
| `20260902_micorb-verify-9state-matrix-dark.png` | 附件（png） | Nebflow/20260902_micorb-verify-9state-matrix-dark.png |
| `20260902_phase2a-sandbox-verification.md` | 文档 | Nebflow/20260902_phase2a-sandbox-verification.md |
| `20260902_project-architecture-phase2-design.md` | 设计 | Nebflow/20260902_project-architecture-phase2-design.md |
| `20260902_project-node-stage1-pilot.md` | 文档 | Nebflow/20260902_project-node-stage1-pilot.md |
| `20260902_quotetag-redesign-dark.png` | 附件（png） | Nebflow/20260902_quotetag-redesign-dark.png |
| `20260902_quotetag-redesign-light.png` | 附件（png） | Nebflow/20260902_quotetag-redesign-light.png |
| `20260902_quotetag-redesign-tooltip.png` | 附件（png） | Nebflow/20260902_quotetag-redesign-tooltip.png |
| `20260902_tasklist-node-entries-dark.png` | 附件（png） | Nebflow/20260902_tasklist-node-entries-dark.png |
| `20260902_tasklist-node-entries-light.png` | 附件（png） | Nebflow/20260902_tasklist-node-entries-light.png |
| `20260902_tasklist-node-entries-v2-dark.png` | 附件（png） | Nebflow/20260902_tasklist-node-entries-v2-dark.png |
| `20260902_tasklist-node-entries-v2-light.png` | 附件（png） | Nebflow/20260902_tasklist-node-entries-v2-light.png |
| `20260902_tasklist-team-retirement-cleanup.md` | 文档 | Nebflow/20260902_tasklist-team-retirement-cleanup.md |
| `20260903-askuser-answer-source-1-polluted-baseline.png` | 附件（png） | Nebflow/20260903-askuser-answer-source-1-polluted-baseline.png |
| `20260903-askuser-answer-source-2-fixed-pending.png` | 附件（png） | Nebflow/20260903-askuser-answer-source-2-fixed-pending.png |
| `20260903-reftag-filename-dup-fix-dark.png` | 附件（png） | Nebflow/20260903-reftag-filename-dup-fix-dark.png |
| `20260903-reftag-filename-dup-fix-light.png` | 附件（png） | Nebflow/20260903-reftag-filename-dup-fix-light.png |
| `20260903_archive-panel-impl-report.md` | 报告 ·📎引用assets | Nebflow/20260903_archive-panel-impl-report.md |
| `20260903_archive-panel-merge-report.md` | 报告 | Nebflow/20260903_archive-panel-merge-report.md |
| `20260903_archive-panel-qa-RESIZE-LATCH-BUG.png` | 附件（png） | Nebflow/20260903_archive-panel-qa-RESIZE-LATCH-BUG.png |
| `20260903_archive-panel-qa-report.md` | 评审/验收 | Nebflow/20260903_archive-panel-qa-report.md |
| `20260903_archive-panel-t1-panel-open.png` | 附件（png） | Nebflow/20260903_archive-panel-t1-panel-open.png |
| `20260903_archive-panel-t2-chain-expanded.png` | 附件（png） | Nebflow/20260903_archive-panel-t2-chain-expanded.png |
| `20260903_archive-panel-t2-detail-dock.png` | 附件（png） | Nebflow/20260903_archive-panel-t2-detail-dock.png |
| `20260903_archive-panel-t4-dark.png` | 附件（png） | Nebflow/20260903_archive-panel-t4-dark.png |
| `20260903_archive-panel-t5-all-archived.png` | 附件（png） | Nebflow/20260903_archive-panel-t5-all-archived.png |
| `20260903_archive-panel-t5-terminal-retained.png` | 附件（png） | Nebflow/20260903_archive-panel-t5-terminal-retained.png |
| `20260903_archive-panel-t7-reduced-motion.png` | 附件（png） | Nebflow/20260903_archive-panel-t7-reduced-motion.png |
| `20260903_archive-panel-t8-narrow-375.png` | 附件（png） | Nebflow/20260903_archive-panel-t8-narrow-375.png |
| `20260903_archive-panel-visual-review.md` | 评审/验收 ·📎引用assets | Nebflow/20260903_archive-panel-visual-review.md |
| `20260903_askuser-pending-agent-injection-fix-report.md` | 报告 | Nebflow/20260903_askuser-pending-agent-injection-fix-report.md |
| `20260903_askuser-refresh-survive-dark.png` | 附件（png） | Nebflow/20260903_askuser-refresh-survive-dark.png |
| `20260903_askuser-refresh-survive-light.png` | 附件（png） | Nebflow/20260903_askuser-refresh-survive-light.png |
| `20260903_canvas-html-interactive-dark.png` | 附件（png） | Nebflow/20260903_canvas-html-interactive-dark.png |
| `20260903_canvas-html-interactive-fix-report.md` | 报告 ·📎引用assets | Nebflow/20260903_canvas-html-interactive-fix-report.md |
| `20260903_canvas-html-interactive-light.png` | 附件（png） | Nebflow/20260903_canvas-html-interactive-light.png |
| `20260903_collapse-keep-text-dark.png` | 附件（png） | Nebflow/20260903_collapse-keep-text-dark.png |
| `20260903_collapse-keep-text-impl.md` | 文档 | Nebflow/20260903_collapse-keep-text-impl.md |
| `20260903_collapse-keep-text-light.png` | 附件（png） | Nebflow/20260903_collapse-keep-text-light.png |
| `20260903_collapse-keep-text-merge-report.md` | 报告 | Nebflow/20260903_collapse-keep-text-merge-report.md |
| `20260903_collapse-keep-text-verify-dark.png` | 附件（png） | Nebflow/20260903_collapse-keep-text-verify-dark.png |
| `20260903_collapse-keep-text-verify-light.png` | 附件（png） | Nebflow/20260903_collapse-keep-text-verify-light.png |
| `20260903_collapse-keep-text-verify-report.md` | 报告 | Nebflow/20260903_collapse-keep-text-verify-report.md |
| `20260903_compaction-prompt-by-level-impl-report.md` | 报告 | Nebflow/20260903_compaction-prompt-by-level-impl-report.md |
| `20260903_compaction-prompt-by-level.md` | 文档 | Nebflow/20260903_compaction-prompt-by-level.md |
| `20260903_contributing-rewrite-report.md` | 报告 | Nebflow/20260903_contributing-rewrite-report.md |
| `20260903_deps-edge-dark.png` | 附件（png） | Nebflow/20260903_deps-edge-dark.png |
| `20260903_deps-edge-light.png` | 附件（png） | Nebflow/20260903_deps-edge-light.png |
| `20260903_deps-maintree-leaked.patch` | 附件（patch） | Nebflow/20260903_deps-maintree-leaked.patch |
| `20260903_email-templates-preview.html` | 附件（html） ·📎引用assets | Nebflow/20260903_email-templates-preview.html |
| `20260903_flowmap-archive-panel-spec.md` | 规格书 ·📎引用assets | Nebflow/20260903_flowmap-archive-panel-spec.md |
| `20260903_flowmap-graphview-design.md` | 设计 ·📎引用assets | Nebflow/20260903_flowmap-graphview-design.md |
| `20260903_flowmap-graphview-galaxy-design.md` | 设计 ·📎引用assets | Nebflow/20260903_flowmap-graphview-galaxy-design.md |
| `20260903_flowmap-graphview-v3-final.md` | 文档 ·📎引用assets | Nebflow/20260903_flowmap-graphview-v3-final.md |
| `20260903_logto-brand-acceptance-report.md` | 评审/验收 | Nebflow/20260903_logto-brand-acceptance-report.md |
| `20260903_logto-brand-customcss-live.txt` | 附件（txt） | Nebflow/20260903_logto-brand-customcss-live.txt |
| `20260903_logto-brand-visual-config.md` | 记录 | Nebflow/20260903_logto-brand-visual-config.md |
| `20260903_logto-smtp-connector-setup.md` | 记录 | Nebflow/20260903_logto-smtp-connector-setup.md |
| `20260903_m1-execution-log.md` | 记录 | Nebflow/20260903_m1-execution-log.md |
| `20260903_micorb-iceberg-9states-dark.png` | 附件（png） | Nebflow/20260903_micorb-iceberg-9states-dark.png |
| `20260903_micorb-iceberg-9states-light.png` | 附件（png） | Nebflow/20260903_micorb-iceberg-9states-light.png |
| `20260903_micorb-iceberg-verify3-colormatrix.txt` | 附件（txt） | Nebflow/20260903_micorb-iceberg-verify3-colormatrix.txt |
| `20260903_micorb-iceberg-verify3-matrix-dark.png` | 附件（png） | Nebflow/20260903_micorb-iceberg-verify3-matrix-dark.png |
| `20260903_micorb-iceberg-verify3-matrix-light.png` | 附件（png） | Nebflow/20260903_micorb-iceberg-verify3-matrix-light.png |
| `20260903_micorb-iceberg-verify3-render-evidence.txt` | 附件（txt） | Nebflow/20260903_micorb-iceberg-verify3-render-evidence.txt |
| `20260903_micorb-iceberg-verify3-settings-evidence.txt` | 附件（txt） | Nebflow/20260903_micorb-iceberg-verify3-settings-evidence.txt |
| `20260903_micorb-settings-hide-impl-report.md` | 报告 | Nebflow/20260903_micorb-settings-hide-impl-report.md |
| `20260903_micorb-settings-hide-merge-report.md` | 报告 | Nebflow/20260903_micorb-settings-hide-merge-report.md |
| `20260903_micorb-settings-hide-verify-report.md` | 报告 | Nebflow/20260903_micorb-settings-hide-verify-report.md |
| `20260903_micorb-volume-large.png` | 附件（png） | Nebflow/20260903_micorb-volume-large.png |
| `20260903_micorb-volume-small.png` | 附件（png） | Nebflow/20260903_micorb-volume-small.png |
| `20260903_morning-report.md` | 报告 | Nebflow/20260903_morning-report.md |
| `20260903_node-hold-mvp-impl-report.md` | 报告 | Nebflow/20260903_node-hold-mvp-impl-report.md |
| `20260903_node-pause-human-in-loop-design.md` | 设计 | Nebflow/20260903_node-pause-human-in-loop-design.md |
| `20260903_nodeedit-worktree-param-fix.md` | 文档 | Nebflow/20260903_nodeedit-worktree-param-fix.md |
| `20260903_pr41-close-draft.md` | 文档 | Nebflow/20260903_pr41-close-draft.md |
| `20260903_pr41-fix-en-edit-jump.png` | 附件（png） | Nebflow/20260903_pr41-fix-en-edit-jump.png |
| `20260903_pr41-fix-zh-bash-jump.png` | 附件（png） | Nebflow/20260903_pr41-fix-zh-bash-jump.png |
| `20260903_pr41-fix-zh-edit-jump.png` | 附件（png） | Nebflow/20260903_pr41-fix-zh-edit-jump.png |
| `20260903_pr41-fix-zh-edit-settled.png` | 附件（png） | Nebflow/20260903_pr41-fix-zh-edit-settled.png |
| `20260903_pr44-after-dark.png` | 附件（png） | Nebflow/20260903_pr44-after-dark.png |
| `20260903_pr44-after-light.png` | 附件（png） | Nebflow/20260903_pr44-after-light.png |
| `20260903_pr44-before-dark.png` | 附件（png） | Nebflow/20260903_pr44-before-dark.png |
| `20260903_pr44-before-light.png` | 附件（png） | Nebflow/20260903_pr44-before-light.png |
| `20260903_pr44-review.md` | 评审/验收 | Nebflow/20260903_pr44-review.md |
| `20260903_project-archive-btn-dark.png` | 附件（png） | Nebflow/20260903_project-archive-btn-dark.png |
| `20260903_project-archive-btn-impl-report.md` | 报告 | Nebflow/20260903_project-archive-btn-impl-report.md |
| `20260903_project-archive-btn-light.png` | 附件（png） | Nebflow/20260903_project-archive-btn-light.png |
| `20260903_project-archive-btn-merge-report.md` | 报告 | Nebflow/20260903_project-archive-btn-merge-report.md |
| `20260903_project-archive-btn-verify-after-dark.png` | 附件（png） | Nebflow/20260903_project-archive-btn-verify-after-dark.png |
| `20260903_project-archive-btn-verify-after-light.png` | 附件（png） | Nebflow/20260903_project-archive-btn-verify-after-light.png |
| `20260903_project-archive-btn-verify-report.md` | 报告 | Nebflow/20260903_project-archive-btn-verify-report.md |
| `20260903_project-create-panel-dark.png` | 附件（png） | Nebflow/20260903_project-create-panel-dark.png |
| `20260903_project-create-panel-impl-report.md` | 报告 | Nebflow/20260903_project-create-panel-impl-report.md |
| `20260903_project-create-panel-light.png` | 附件（png） | Nebflow/20260903_project-create-panel-light.png |
| `20260903_project-create-panel-verify-dark.png` | 附件（png） | Nebflow/20260903_project-create-panel-verify-dark.png |
| `20260903_project-create-panel-verify-light.png` | 附件（png） | Nebflow/20260903_project-create-panel-verify-light.png |
| `20260903_project-create-panel-verify-report.md` | 报告 | Nebflow/20260903_project-create-panel-verify-report.md |
| `20260903_result-delivery-fix-impl-report.md` | 报告 | Nebflow/20260903_result-delivery-fix-impl-report.md |
| `20260903_result-delivery-loss-audit.md` | 审计 | Nebflow/20260903_result-delivery-loss-audit.md |
| `20260903_sandbox2a-merge-report.md` | 报告 | Nebflow/20260903_sandbox2a-merge-report.md |
| `20260903_sendbtn-green-active-dark.png` | 附件（png） | Nebflow/20260903_sendbtn-green-active-dark.png |
| `20260903_sendbtn-green-active-light.png` | 附件（png） | Nebflow/20260903_sendbtn-green-active-light.png |
| `20260903_sendbtn-green-delivery.md` | 文档 | Nebflow/20260903_sendbtn-green-delivery.md |
| `20260903_sendbtn-green-disabled-dark.png` | 附件（png） | Nebflow/20260903_sendbtn-green-disabled-dark.png |
| `20260903_sendbtn-green-disabled-light.png` | 附件（png） | Nebflow/20260903_sendbtn-green-disabled-light.png |
| `20260903_sendbtn-green-enabled-dark.png` | 附件（png） | Nebflow/20260903_sendbtn-green-enabled-dark.png |
| `20260903_sendbtn-green-enabled-light.png` | 附件（png） | Nebflow/20260903_sendbtn-green-enabled-light.png |
| `20260903_sendbtn-green-frozen-dark.png` | 附件（png） | Nebflow/20260903_sendbtn-green-frozen-dark.png |
| `20260903_sendbtn-green-frozen-light.png` | 附件（png） | Nebflow/20260903_sendbtn-green-frozen-light.png |
| `20260903_sendbtn-green-hover-dark.png` | 附件（png） | Nebflow/20260903_sendbtn-green-hover-dark.png |
| `20260903_sendbtn-green-hover-light.png` | 附件（png） | Nebflow/20260903_sendbtn-green-hover-light.png |
| `20260903_sendbtn-green-impl.md` | 文档 | Nebflow/20260903_sendbtn-green-impl.md |
| `20260903_sendbtn-green-visual-review.md` | 评审/验收 | Nebflow/20260903_sendbtn-green-visual-review.md |
| `20260903_settings-no-orb-config.png` | 附件（png） | Nebflow/20260903_settings-no-orb-config.png |
| `20260903_stage2-migration-plan.md` | 规划 | Nebflow/20260903_stage2-migration-plan.md |
| `20260903_stage2-newmechanisms-batch-report.md` | 报告 | Nebflow/20260903_stage2-newmechanisms-batch-report.md |
| `20260903_timeout-askuser-freeze-audit.md` | 审计 | Nebflow/20260903_timeout-askuser-freeze-audit.md |
| `20260903_timeout-evict-fix-batch2-report.md` | 报告 | Nebflow/20260903_timeout-evict-fix-batch2-report.md |
| `20260903_timeout-mechanisms-audit.md` | 审计 | Nebflow/20260903_timeout-mechanisms-audit.md |
| `20260903_toast-glass-dark.png` | 附件（png） | Nebflow/20260903_toast-glass-dark.png |
| `20260903_toast-glass-impl.md` | 文档 | Nebflow/20260903_toast-glass-impl.md |
| `20260903_toast-glass-light.png` | 附件（png） | Nebflow/20260903_toast-glass-light.png |
| `20260903_toast-glass-merge-report.md` | 报告 | Nebflow/20260903_toast-glass-merge-report.md |
| `20260903_toast-glass-visual-review.md` | 评审/验收 ·📎引用assets | Nebflow/20260903_toast-glass-visual-review.md |
| `20260903_tool-failure-logging-audit.md` | 审计 | Nebflow/20260903_tool-failure-logging-audit.md |
| `20260903_toolline-truncate-dark.png` | 附件（png） | Nebflow/20260903_toolline-truncate-dark.png |
| `20260903_toolline-truncate-light.png` | 附件（png） | Nebflow/20260903_toolline-truncate-light.png |
| `20260903_toolslog-structured-logging-report.md` | 报告 | Nebflow/20260903_toolslog-structured-logging-report.md |
| `20260903_vibe-creating-strategy.md` | 规划 | Nebflow/20260903_vibe-creating-strategy.md |
| `20260903_wait-timeout-fix-report.md` | 报告 | Nebflow/20260903_wait-timeout-fix-report.md |
| `20260903_worktree-param-fix-impl-report.md` | 报告 | Nebflow/20260903_worktree-param-fix-impl-report.md |
| `20260904_archive-panel-v3-dqa1-qa3-report.md` | 评审/验收 | Nebflow/20260904_archive-panel-v3-dqa1-qa3-report.md |
| `20260904_archive-panel-v3-dqa1-visual3-review.md` | 评审/验收 | Nebflow/20260904_archive-panel-v3-dqa1-visual3-review.md |
| `20260904_canvas-html-zoom-dark-reset.png` | 附件（png） | Nebflow/20260904_canvas-html-zoom-dark-reset.png |
| `20260904_canvas-html-zoom-dark-zoomed.png` | 附件（png） | Nebflow/20260904_canvas-html-zoom-dark-zoomed.png |
| `20260904_canvas-html-zoom-light-reset.png` | 附件（png） | Nebflow/20260904_canvas-html-zoom-light-reset.png |
| `20260904_canvas-html-zoom-light-zoomed.png` | 附件（png） | Nebflow/20260904_canvas-html-zoom-light-zoomed.png |
| `20260904_canvas-html-zoom-report.md` | 报告 | Nebflow/20260904_canvas-html-zoom-report.md |
| `20260904_flowmap-galaxy-v2-verify-closeout.md` | 记录 ·📎引用assets | Nebflow/20260904_flowmap-galaxy-v2-verify-closeout.md |
| `20260904_login-handover-guide.md` | 记录 ·📎引用assets | Nebflow/20260904_login-handover-guide.md |
| `20260904_merge3-archive-panel-record.md` | 记录 | Nebflow/merge-records/20260904_merge3-archive-panel-record.md |
| `20260904_phase2b-plugins-implementation.md` | 文档 | Nebflow/20260904_phase2b-plugins-implementation.md |
| `20260904_phase2c-agent-convergence-report.md` | 报告 | Nebflow/20260904_phase2c-agent-convergence-report.md |
| `20260904_phase2d-tool-refactor-report.md` | 报告 | Nebflow/20260904_phase2d-tool-refactor-report.md |
| `20260904_phase2e-finale-report.md` | 报告 | Nebflow/20260904_phase2e-finale-report.md |
| `20260904_sandbox-2a-read-whitelist.md` | 文档 | Nebflow/20260904_sandbox-2a-read-whitelist.md |
| `20260904_sandbox-readlist-verify-report.md` | 报告 | Nebflow/20260904_sandbox-readlist-verify-report.md |
| `20260905_planmode-deadchain-audit.md` | 规划 | Nebflow/20260905_planmode-deadchain-audit.md |
| `CONTRIBUTING.md` | 文档 | Nebflow/20260903_contributing-original-snapshot/CONTRIBUTING.md |
| `CONTRIBUTING.zh-CN.md` | 文档 | Nebflow/20260903_contributing-original-snapshot/CONTRIBUTING.zh-CN.md |
| `INDEX.md` | 文档 ·📎引用assets | Nebflow/INDEX.md |
| `activity-bar-spec.md` | 规格书 | Nebflow/activity-bar-spec.md |
| `actual-model-display-spec.md` | 规格书 ·📎引用assets | Nebflow/actual-model-display-spec.md |
| `agent-benchmark-plan.md` | 规划 ·📎引用assets | Nebflow/agent-benchmark-plan.md |
| `agent-control-tool-spec.md` | 规格书 | Nebflow/agent-control-tool-spec.md |
| `agent-doc-management.md` | 文档 ·📎引用assets | Nebflow/agent-doc-management.md |
| `askuser-canvas-integration-spec.md` | 规格书 | Nebflow/askuser-canvas-integration-spec.md |
| `canvas-zoom-follow-cursor-spec.md` | 规格书 | Nebflow/canvas-zoom-follow-cursor-spec.md |
| `collapse-intermediate-spec.md` | 规格书 | Nebflow/collapse-intermediate-spec.md |
| `deepseek-harness-study.md` | 调研 | Nebflow/deepseek-harness-study.md |
| `device-pairing-spec.md` | 规格书 | Nebflow/device-pairing-spec.md |
| `ecosystem-decision-board.md` | 文档 | Nebflow/ecosystem-decision-board.md |
| `ecosystem-master-plan.md` | 规划 ·📎引用assets | Nebflow/ecosystem-master-plan.md |
| `entity-ecosystem-design.md` | 设计 | Nebflow/entity-ecosystem-design.md |
| `esbuild-design.md` | 设计 ·📎引用assets | Nebflow/esbuild-design.md |
| `feat-master-overview.md` | 规划 | Nebflow/feat-master-overview.md |
| `file-drag-drop-spec.md` | 规格书 ·📎引用assets | Nebflow/file-drag-drop-spec.md |
| `flow-timeout-redesign.md` | 设计 ·📎引用assets | Nebflow/flow-timeout-redesign.md |
| `freeze-schedule-spec.md` | 规格书 ·📎引用assets | Nebflow/freeze-schedule-spec.md |
| `friends-messaging-arch.md` | 文档 ·📎引用assets | Nebflow/friends-messaging-arch.md |
| `friends-messaging-spec.md` | 规格书 | Nebflow/friends-messaging-spec.md |
| `frontend-quality-roadmap.md` | 规划 ·📎引用assets | Nebflow/frontend-quality-roadmap.md |
| `headless-design.md` | 设计 | Nebflow/headless-design.md |
| `icp-beian-action-plan.md` | 规划 | Nebflow/icp-beian-action-plan.md |
| `interrupt-pending-mail-spec.md` | 规格书 | Nebflow/interrupt-pending-mail-spec.md |
| `logto-account-api.md` | 记录 ·📎引用assets | Nebflow/logto-account-api.md |
| `logto-registration-api.md` | 记录 | Nebflow/logto-registration-api.md |
| `logto-signin-branding.md` | 记录 ·📎引用assets | Nebflow/logto-signin-branding.md |
| `logto-signin-custom.css` | 附件（css） | Nebflow/logto-signin-custom.css |
| `logto-signin-experience-api.md` | 记录 | Nebflow/logto-signin-experience-api.md |
| `mail-delivery-tag-spec.md` | 规格书 | Nebflow/mail-delivery-tag-spec.md |
| `message-search-spec.md` | 规格书 | Nebflow/message-search-spec.md |
| `message-truncation-report.md` | 报告 ·📎引用assets | Nebflow/message-truncation-report.md |
| `mic-bubble-spec.md` | 规格书 ·📎引用assets | Nebflow/mic-bubble-spec.md |
| `mic-bubble-visual-v2.html` | 附件（html） | Nebflow/mic-bubble-visual-v2.html |
| `mic-bubble-visual-v3.html` | 附件（html） | Nebflow/mic-bubble-visual-v3.html |
| `mic-bubble-visual-v4.html` | 附件（html） | Nebflow/mic-bubble-visual-v4.html |
| `mic-bubble-visual-v5.html` | 附件（html） | Nebflow/mic-bubble-visual-v5.html |
| `mic-bubble-visual-v6.html` | 附件（html） | Nebflow/mic-bubble-visual-v6.html |
| `mic-bubble-visual-v7.html` | 附件（html） | Nebflow/mic-bubble-visual-v7.html |
| `mic-bubble-visual-v8.html` | 附件（html） | Nebflow/mic-bubble-visual-v8.html |
| `mic-bubble-visual.html` | 附件（html） | Nebflow/mic-bubble-visual.html |
| `nebflow-paper-plan.md` | 规划 ·📎引用assets | Nebflow/nebflow-paper-plan.md |
| `onboarding-redesign-spec.md` | 规格书 ·📎引用assets | Nebflow/onboarding-redesign-spec.md |
| `popup-input-polish-spec.md` | 规格书 | Nebflow/popup-input-polish-spec.md |
| `process-sandbox-plan.md` | 规划 ·📎引用assets | Nebflow/process-sandbox-plan.md |
| `qa-methodology.md` | 评审/验收 | Nebflow/qa-methodology.md |
| `rebrand-param-plan.md` | 规划 ·📎引用assets | Nebflow/rebrand-param-plan.md |
| `stt-protocols.md` | 文档 | Nebflow/stt-protocols.md |
| `subagent-panel-cancel-realtime-report-20260903.html` | 附件（html） | Nebflow/subagent-panel-cancel-realtime-report-20260903.html |
| `subagent-thinking-bubble-spec.md` | 规格书 | Nebflow/subagent-thinking-bubble-spec.md |
| `thinking-display-openai-spec.md` | 规格书 | Nebflow/thinking-display-openai-spec.md |
| `todo-panel-spec.md` | 规格书 | Nebflow/todo-panel-spec.md |
| `token-dashboard-spec.md` | 规格书 | Nebflow/token-dashboard-spec.md |
| `trinity-plan.md` | 规划 | Nebflow/trinity-plan.md |
| `uiux-quality-system.md` | 文档 ·📎引用assets | Nebflow/uiux-quality-system.md |
| `unified-login-design.md` | 设计 ·📎引用assets | Nebflow/unified-login-design.md |

## 暂留组去向表（未迁入本目录）

| 组 | 计数 | 去向 |
|---|---|---|
| docs/Nebflow/assets/ | 1074 | 归档性质，暂留原地；待作者裁定（建议保持 docs 或另立归档处） |
| docs/SiPM/ | 44 | 未来 sipm project 的 .nebflow/Spec/，待项目创建后迁移 |
| docs/skill-proposals-archive/ | 80 | proposal 环节已废止的归档，留原地 |
| docs/Presentations/ | 3 | 建议 phd-notebook .nebflow/Spec/，待作者确认 |
| docs/nebflow-website/ · slideblocks/ · NebLink/ | 3+6+1 | 各自项目 .nebflow/Spec/，待项目侧认领 |
| docs/reports/ · research/ · memory-archive/ | 3+2+1 | 暂留原地（README 标注去向建议） |
| docs/CONVENTIONS.md | 1 | 留原地，继续管辖暂留项；后续由 Spec 惯例取代 |
