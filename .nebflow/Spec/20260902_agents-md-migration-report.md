# AGENTS.md 迁移实施报告（子任务 A）

> 依据：`20260902_agents-md-standard-survey.md`（commit 6954bdf，AC-1..7 七条二值验收点，作者已裁定实施）
> 执行：Coder · 2026-09-02 · 改动全部在各项目 repo 内 commit，未 push

---

## 0. 结论

**迁移完成，AC-1..7 全部通过（7/7）。** Project 工作区指令文件已从 `.nebflow/Agent.md` 迁移到工作区根 `AGENTS.md`，API URL（`/api/projects/<name>/agent.md`）不变，前端零契约变更。4 个存量项目工作区全部迁移并各自 commit；旧位置留 `symlink → ../AGENTS.md` 兼容未升级实例（一个版本后可清理，survey §4.2.6）。

---

## 1. 迁移改动清单

### 1.1 Nebflow 主仓（`/Users/dev/Claude code/Nebflow`，main 分支）

| commit | 内容 |
|---|---|
| `2a0dada0` | **实现**：13 文件，+348/−39 |
| `e83da58b` | **工作区迁移**：`.nebflow/Agent.md`（98 行）→ 根 `AGENTS.md`，内容 diff 为空 |

`2a0dada0` 明细：

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/gateway/RestApiRoutes.scala` | GET `agent.md`：读工作区根 `AGENTS.md`，**旧 `.nebflow/Agent.md` 存在且根不存在时回落读旧**（AC-5 兼容）；PUT：写工作区根 `AGENTS.md`（旧文件保留不动，保存即迁移）；错误文案 `has no AGENTS.md` |
| `src/main/scala/nebflow/core/project/ProjectStore.scala` | `create` 脚手架：模板写 `ws/AGENTS.md`（仅缺省时写），`agentFile` 元数据 = `ws/AGENTS.md`；`.gitignore` R6 逻辑不变（`.nebflow/` 继续忽略，根 `AGENTS.md` 天然进 git） |
| `src/main/scala/nebflow/core/tools/NodeTools.scala` | ProjectCreate 工具描述 + 脚手架模板标题改为 AGENTS.md（仅此 2 处 hunk，**其余为另一在途任务的未提交改动，未混入提交**） |
| 前端 `nodeData.js` / `agentFileViewer.js` / `projectTab.js` / `projectPanel.css` / `locales/en.js` / `locales/zh-CN.js` | 注释/标题/hint/字段标签全部改为 AGENTS.md；404 缺省文本 `# AGENTS.md`；URL 常量不变 |
| `src/test/scala/nebflow/core/project/ProjectStoreSpec.scala` | 断言改为根 `AGENTS.md`（8/8 过） |
| `src/test/scala/nebflow/core/project/NodeAcceptanceSpec.scala` | 仅 fixture `agentFile` 路径 1 行（**其余 +159 行为另一在途任务的未提交改动，未混入提交**） |
| `src/test/scala/nebflow/gateway/ProjectAgentFileRoutesSpec.scala` | **新增** routes 级契约测试 5 条（新位置优先/读旧兼容/两无 404/写根+旧文件保留/无 token 403），5/5 过 |
| `tests/project-agents-md.spec.mjs` | **新增** Playwright 回归（卡片标签/overlay 标题与 hint/保存 PUT body），1/1 过 |

### 1.2 三个外部项目工作区

| 项目 | commit | 方式 | 内容验证 |
|---|---|---|---|
| nebflow-website（staging） | `336eea5` | `git mv .nebflow/Agent.md AGENTS.md`（原文件已跟踪） | diff 与原文**逐字节一致** |
| phd-notebook（main） | `3b15a56` | `mv` + `git add`（原文件未被跟踪，首次进版本控制） | diff 与原文**逐字节一致** |
| slideblocks（fix/deck-workbench） | `2f0b650` | **合并**（见 §3 偏差①）：根已有英文版 AGENTS.md，Nebula 中文项目指令按段并入文末，删除 `.nebflow/Agent.md` | 并入部分与原文**逐字节一致**（仅标题行换为迁移说明标题） |

### 1.3 运行时数据（`~/.nebflow/projects/`，不在 git 跟踪层，无需 commit）

- 4 个 `project.json` 的 `agentFile` 字段更新为 `<workspace>/AGENTS.md`（nebflow / nebflow-website / slideblocks / phd-notebook）。该字段纯展示性（读写路径后端硬编码+回落，不依赖此字段），更新保持元数据真实。

---

## 2. AC-1..7 逐条验收

| # | 验收点 | 结果 | 证据 |
|---|---|---|---|
| AC-1 | 根 `AGENTS.md` 存在，内容与迁移前 `.nebflow/Agent.md` 一致 | **过** | 4 工作区全部 `diff 原文 AGENTS.md`：nebflow 主仓/website/phd-notebook **IDENTICAL**；slideblocks 并入段 IDENTICAL（`tail -n +78` 对账） |
| AC-2 | `git ls-files AGENTS.md` 非空；`git check-ignore .nebflow/Agent.md` 退出码 0 | **过** | 4 repo：tracked 非空 ✓；check-ignore 均 exit 0（symlink 亦被 `.nebflow/` 忽略）✓ |
| AC-3 | GET `/api/projects/<name>/agent.md` 返回根内容（200）；PUT 后磁盘为根文件 | **过** | 隔离实例（`--home /tmp/nb-agentsmd-home --port 8097`，e83da58b 构建）：GET demo-new → `200 {"content":"# root content v1"}`；PUT → `{"saved":true}`，磁盘 `AGENTS.md` = 新内容 ✓ |
| AC-4 | `createProject` 新建项目在工作区根生成 `AGENTS.md` 模板 | **过** | `ProjectStoreSpec`"create writes project.json + workspace AGENTS.md" 8/8 过（断言 `os.exists(ws / "AGENTS.md")`）；`ProjectAgentFileRoutesSpec` 亦走真实 `ProjectStore.create` 路径 |
| AC-5 | 仅存 `.nebflow/Agent.md` 时 GET 仍 200（读旧）；保存后写根 `AGENTS.md` | **过** | 隔离实例 demo-legacy（仅旧文件）：GET → `200` 旧内容 ✓；PUT → 根 `AGENTS.md` 生成、旧文件字节不变 ✓；再 GET → 新位置优先 ✓；routes 单测同款断言 5/5 ✓ |
| AC-6 | 前端入口正常（标题/文案更新为 AGENTS.md），保存回写 | **过** | `tests/project-agents-md.spec.mjs` 1/1 过：卡片标签 `AGENTS.md`、overlay 标题 `demo · AGENTS.md`、h3 `AGENTS.md`、hint 含 AGENTS.md 且无 `.nebflow/Agent.md` 残留、保存 PUT body 精确匹配；磁盘落点由 AC-3 curl 实证 |
| AC-7 | 无残留 `.nebflow/Agent.md` 硬编码（或仅剩兼容注释/代码） | **过** | `git grep "\.nebflow.*Agent\.md" HEAD` 仅剩：①AC-5 要求的回落兼容代码+注释（RestApiRoutes:327）②兼容说明注释 ③回落行为的测试 fixture。业务/前端/模板零残留 |

---

## 3. 偏差与说明

1. **slideblocks 为合并迁移**：该工作区根已有英文版 `AGENTS.md`（2026-08-07，仓库自有标准指令，已进 git）。直接覆盖会丢失外部 agent 标准入口；直接跳过会让分发器读到英文版而丢中文项目纪律（用户规则/协作规则/用户裁定）。故将 `.nebflow/Agent.md` 中文指令按段并入文末（分隔线 + 迁移说明标题），内容逐字节保留。**若需拆分为两文件请示下。**
2. **旧位置留 symlink**（`.nebflow/Agent.md → ../AGENTS.md`，4 工作区均建）：survey §4.2.6 推荐的过渡方案。线上 8080 实例仍在跑迁移前代码（硬编码读旧路径），symlink 使其 GET/编辑继续可用；新代码读时根优先，不受影响。**下一版本可清理**（4 处 symlink 均被 gitignore，不进版本库）。
3. **主仓工作树存在另一在途任务的未提交改动**（NodeRunner/NodeEngine/ProjectActor/ProjectTypes/GatewayMain/chat.js/main.js/utils.js/NodeAcceptanceSpec +159 行/NodeGhostRowSpec/NodeEventPushSpec 新文件）。已按 hunk 级精确暂存提交，**仅混入我的改动**（提交经逐行 review + `git ls-tree HEAD` 新旧文件甄别；NodeEventPushSpec 系对方新文件，已从暂存剔除）。我对 NodeEventPushSpec 的 1 行 fixture 修正留在工作树未提交（将随对方任务自然入库，且使工作树 AC-7 干净）。
4. **NodeAcceptanceSpec "Task①" 失败归属在途任务**：该测试本身即为未提交新增代码（diff 实证 +159 行含 Task①-⑬），其失败（dispatcher session 注册轮询为空）属在途功能域，与本迁移无关。本迁移涉测套件全绿（ProjectStoreSpec 8/8、ProjectAgentFileRoutesSpec 5/5、NodeEventPushSpec 全过、Playwright 1/1）。
5. **AC-4 以单测+create 真实路径验收**（隔离实例的 ProjectCreate 工具需完整 agent 会话，REST 无该路由）；AC-3/AC-5 以隔离实例 curl 实测。线上 8080 实例需下次发版重启后生效新读写路径（过渡期由 symlink 兜底）。

---

## 4. 涉及 repo/commit 总表

| repo | 分支 | commit |
|---|---|---|
| Nebflow 主仓 | main | `2a0dada0`（实现）· `e83da58b`（工作区文件） |
| nebflow-website | staging | `336eea5` |
| slideblocks | fix/deck-workbench | `2f0b650` |
| phd-notebook | main | `3b15a56` |
| ~/.nebflow | — | 无跟踪层改动（projects/ 运行时数据不在白名单），无需 commit |

*报告完 · 2026-09-02*
