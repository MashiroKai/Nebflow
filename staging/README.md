# staging — 宿主落地内容（dream-agent 批，2026-09-05）

本目录是 ~/.nebflow 定义层改动的 staging（工作区沙箱不可直写 ~/.nebflow，
宿主执行 cp + git commit）。上游批次 memory-mech 的 staging（flow scanner-only
化 / skill / Nebula system / memory.md 规则节替换）见其支 `staging/`，
**先落 memory-mech 再落本批**（落地命令互不覆盖；Nebula system.md 与
memory.md 规则节属 memory-mech 批交付，本批零叠加）。

| staging 源 | 宿主目标 | 说明 |
|---|---|---|
| `agents-dream/agent.json` | `~/.nebflow/agents/dream/agent.json` | 第 4 个 keeper 定义（tools=Read/Grep/Glob/Bash，无写面） |
| `agents-dream/system.md` | `~/.nebflow/agents/dream/system.md` | 梦境审计员身份/铁律/报告格式/对象面（含项目记忆预留段） |
| `agents-project-dispatcher-system-addendum.md` | `~/.nebflow/agents/project-dispatcher/system.md` | **追加式**（见下）：`<!-- dream-rules:start/end -->` 锚定节 |
| `dream-run-0-report.md` | （非 cp——交 Nebula 执行） | run-0 审计报告全文；Nebula 按合并执行清单用 MemoryEdit 落实 |

## 宿主落地命令全集（commit-ready）

> 落地顺序：**memory-mech 先并 main → dream-agent 后并**（同内容 diff 自动
> 消解）；定义层 cp 在合并后执行。sbt 私有缓存配方见 spec §5；仅 compile/test。

```bash
# ① 分支合并（宿主；逐级 --no-ff，顺序不可倒）
cd "/Users/dev/Claude code/Nebflow"
git merge --no-ff memory-mech -m "merge memory-mech: MemoryEdit budget gate + audit read exception + hygiene notice + scanner-only flow"
git merge --no-ff dream-agent -m "merge dream-agent: dream keeper + MemorySnapshot write guard + e2e"

# ② dream 定义 cp（面板磁盘加载即见，重启后生效）
mkdir -p ~/.nebflow/agents/dream
cp <worktree>/staging/agents-dream/agent.json ~/.nebflow/agents/dream/agent.json
cp <worktree>/staging/agents-dream/system.md ~/.nebflow/agents/dream/system.md

# ③ 分发器 system.md 锚定节——追加式：落地前重读现文件，
#    确认无 dream-rules 锚（merge-node/dispatcher-ctx 各有锚定节已在其内，只追加本节）
grep -q "dream-rules:start" ~/.nebflow/agents/project-dispatcher/system.md || \
  cat <worktree>/staging/agents-project-dispatcher-system-addendum.md >> ~/.nebflow/agents/project-dispatcher/system.md

# ④ ~/.nebflow git 提交（按文件，禁 git add -A；memory.md 本体在 gitignore 排除层不涉及）
cd ~/.nebflow
git add agents/dream/agent.json agents/dream/system.md agents/project-dispatcher/system.md
git commit -m "dream-agent 批：第 4 个 keeper（梦境审计）定义 + 分发器梦境日审处理节"

# ⑤ 备份目录创建（MemorySnapshot 首写也会自建；预先建好并可见）
mkdir -p ~/.nebflow/memory-backups

# ⑥ Schedule 每日注册（Nebula 会话内执行， weekly-summary 外部驱动同款；
#    Nebula 自行调用 Schedule 工具，参数）：
#   content: 「梦境日审：先查活跃守卫——Bash 检查 ~/.nebflow/logs/router/<今日>_summary.jsonl
#             最近 60 分钟内是否有用户轮次（agent=Nebula & channel=web & messages_count<=4）；
#             有 → 当日放弃，只回一句『用户活跃，梦境顺延明日』；
#             无 → Task(project="nebflow", task="梦境日审（今日）：按 dream 配方建单节点 agent=dream 只读审计")」
#   triggerAt: "04:45"（本地 UTC+8；4 日 router 实测 04:00-05:00 用户轮次为零、总量最静桶）
#   repeat: "daily"
#  手动触发口（验收/补跑）：Task(project=…, task="梦境日审（手动触发，跳过活跃守卫）…")

# ⑦ 周接线退役：Sunday 21:30 memory-consolidation 注册（若 memory-mech 批已建）
#    → 删除该 scheduled task（默认退役，理由见 spec §3.4；作者要保留周深度班可改挂
#    「梦境周审」任务文本，dream 定义零变更）
```

## run-0 报告交接

`staging/dream-run-0-report.md` 全文随本批结果投递 Nebula：机械批（M1 去重 /
M4×6 节 / M7×4 节 / 规则节整节替换 / M11-M13 / D-1/D-4 / U2/U3/U5）逐字执行，
裁定批逐条裁量，迁移批等 project-memory 批落地后按 R 表执行。
