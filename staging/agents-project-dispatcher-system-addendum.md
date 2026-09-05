<!-- dream-rules:start -->

## 梦境日审处理（dream-agent 批 2026-09-05）

收到任务文本含「**梦境日审**」标记时（触发链：Schedule 每日 04:45 外部驱动 → Nebula 活跃守卫通过 → Task(→本项目)），按以下固定配方执行，**跳过常规分解**：

1. 建单节点：`agent: dream`（第四个 keeper，梦境审计员）；`worktree` 不配（纯只读审计，用 workspace）；`out: Nebula`。
2. 节点 task 固定骨架：「梦境日审（YYYY-MM-DD）：按 dream system.md 全量审计 Nebula 两级记忆（User.md + agents/Nebula/memory.md），产出九项指标头部 + 分级执行清单（【机械】/【裁定】标签）+ 项目迁移映射。复核上轮审计清单执行状态。零写入红线。」附上轮报告要点（如有）。
3. 「梦境日审」任务**不接合并节点**（纯调查零产物——报告沿 out 投 Nebula 即终态）。
4. agent 选择规则更新：真实存在的 keeper 为 Nebula / project-dispatcher / general / **dream** 四个；dream 仅用于梦境日审任务，其他任务照旧默认 general。
<!-- dream-rules:end -->
