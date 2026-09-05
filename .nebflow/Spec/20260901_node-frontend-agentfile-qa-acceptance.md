# #27 收尾 Agent.md 真实 GET/PUT 端点前端交付验收报告（QA Pass）

- **日期**：2026-09-01
- **验收人**：qa-frontend
- **产出者**：Frontend
- **交付**：分支 `feat/node-agentfile`，commit `0d7b1af67fbf3735833f172a21f1e99103bd3c25`，worktree `/tmp/nb-node-agentfile`
- **前置基线**：`git merge-base --is-ancestor 499d86d4 HEAD` = YES（基于已 PASS 的 499d86d4）
- **验收基准**：隔离静态实例 `http://127.0.0.1:8324`（Python http.server，PID 29504，非宿主，cwd = `/private/tmp/nb-node-agentfile/src/main/resources/web`）+ Playwright 拦截 agent.md GET/PUT + 404 分支
- **结论**：**PASS（4/4 验收条件 + 独立复验通过）**

---

## 交付边界核对

`git show 0d7b1af6 --stat`：2 文件改动，**全部在 `src/main/resources/web/js/`**（`nodeData.js`、`agentFileViewer.js`），无越界。**无新增静态文件**。

## 契约对齐核对（nodeData.js ↔ Backend #28 0b 契约 §1）

`~/.nebflow/docs/Nebflow/20260901_project-node-contract.md` §1 后端已补 agent.md 端点（commit 65cdb880）：

| 契约点 | 契约文档 | 前端实现 | 一致 |
|---|---|---|---|
| GET agent.md | `GET /api/projects/<name>/agent.md` → 200 `{content:"..."}`（工作区 `.nebflow/Agent.md`） | `API.agentFile(name)`（encodeURIComponent）；`fetchAgentFile()` GET + `authHeaders()` → `data.content` | ✅ |
| PUT agent.md | `PUT /api/projects/<name>/agent.md` → 200 `{saved:true}`（body `{content}` 写回） | `saveAgentFile()` PUT body `{content}` → 校验 `r.ok` → `{ok:true}` | ✅ |
| GET 404 | 项目不存在 / 无 Agent.md → 404 | 404 → 返回缺省文本 `# Agent.md\n\n（暂无内容）\n` | ✅ |

## 验收条件逐项结果

| # | 验收条件 | 结果 | 证据 |
|---|---------|------|------|
| 1 | GET 载入编辑器：textarea 显示 GET 返回 content | ✅ PASS | 见 A |
| 2 | PUT 保存：发 PUT body `{content}` 为编辑后文本，状态栏「✓ 已保存」 | ✅ PASS | 见 B |
| 3 | 404 分支：GET 404 → 编辑器显示缺省文本（非空、不报错） | ✅ PASS | 见 C |
| 4 | mock 撤净：nodeData 无 MOCK_AGENT_FILES、agentFileViewer 无 authHeaders 悬空导入 | ✅ PASS | 见 D |

### A. GET 载入编辑器（条件 1）
- 产者 harness `/tmp/nb-node-agentfile/verify-agentfile.mjs` **独立复跑 4/4 PASS**：`Agent.md GET loads content into editor — # Agent.md\n\nOriginal content for alpha-demo.`
- 独立盲区探针 `qa-agentfile-blindspots.mjs`：`Agent.md overlay present`、`Agent.md overlay visible (not hidden 0x0) — w=550 h=805`、`Agent.md overlay in ACTIVE pane — pane=projects active=projects`（**overlayRoot 修复对本路径仍成立**）、`GET loads original content`。

### B. PUT 保存（条件 2）
- harness：`Agent.md PUT called with edited content — status=✓ 已保存 body=# Agent.md\n\nEdited content.`、`Agent.md save shows saved status — ✓ 已保存`。
- 独立探针：`PUT used (method=PUT) — puts=2`（捕获真实请求 method=PUT）；**round-trip 复验**：编辑→保存→关闭→重开，`GET returns persisted content after PUT`（stateful mock 持久化，验证 GET 读回 PUT 写的内容）。

### C. 404 分支（条件 3）
- harness：`Agent.md GET 404 → default text (no file) — # Agent.md\n\n（暂无内容）`.
- 独立探针：`GET 404 -> default text (not empty)`、`404 editor NOT stuck loading`（无 `flow-mail-empty` 残留）、`PUT on 404-project (file create) still saves — ✓ 已保存`。

### D. mock 撤净（条件 4）
- `grep -rn "MOCK_AGENT_FILES" src/main/resources/web/js/` → **空**（mock 移除）。
- `grep -n "authHeaders" src/main/resources/web/js/agentFileViewer.js` → **空**（qa 上次 minor 备注已修复，import 从 `{ esc, authHeaders }` 精简为 `{ esc }`）。

## 独立质量复验

- **checkJs 门禁**：worktree `node scripts/check-js-types.mjs` → `checkJs: 326 errors (baseline 326, zero-gate files: 3)` / `checkJs PASS: no new errors above baseline.`
- **契约 §1 与前端逐项核对**：GET/PUT 路径、body、成功/404 响应均一致。
- **encodeURIComponent 处理**：独立探针含空格项目名 `beta demo project`，请求 URL 正确编码（editor 打开/GET 404/PUT 保存均正常）。

### 遗留说明（非阻塞）
1. **真实 E2E 依赖后端**：agent.md 端点实现在 Backend worktree（nb-node-runner @65cdb880）**未合 main**。本次验收基座为「虚构后端」（Playwright 拦截 agent.md GET/PUT + 404），验证的是**前端↔契约对齐**。真实端到端随 #28 0b 合并后补验（届时由 qa-backend / 集成验收覆盖）。
2. **生效需重启 JVM**（前端资源改动）。
3. 本增量不改任何静态路由（无新增文件），合并门禁 verify-web-assets 无新缺口；产者已报 276/276。

## 复现命令

```bash
# 交付基线
git -C /tmp/nb-node-agentfile merge-base --is-ancestor 499d86d4 0d7b1af6 && echo OK
git -C /tmp/nb-node-agentfile show 0d7b1af6 --stat

# 隔离实例 PID 验身
lsof -i :8324   # PID 29504, Python, cwd = /private/tmp/nb-node-agentfile/src/main/resources/web

# 产者 harness（4/4）
cd /tmp/nb-node-agentfile && BASE=http://127.0.0.1:8324 node verify-agentfile.mjs

# 独立盲区探针（round-trip / overlay / 404 / encoding）
BASE=http://127.0.0.1:8324 node /tmp/nb-node-agentfile/qa-agentfile-blindspots.mjs

# checkJs 门禁
cd /tmp/nb-node-agentfile && node scripts/check-js-types.mjs
```
