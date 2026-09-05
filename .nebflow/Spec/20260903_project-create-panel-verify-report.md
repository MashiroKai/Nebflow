# ProjectCreate 交互面板——独立验证报告（QA 节点）

- 日期：2026-09-03
- 验证者：qa-backend 角色（与实施会话独立，全部证据自采）
- 对象：分支 `proj-create-tool`（worktree `/Users/dev/Claude code/Nebflow/.nebflow/worktrees/proj-create-tool`），commits `c8f60d9a`（后端）+ `0f71bb6b`（前端验证）
- 方法：不信任上游自测结论——独立自写验证 spec（5 用例，验证后删除、不进分支）+ 独立重跑上游 spec + 改动域回归子集 + 独立 Playwright 取证脚本（14 断言，/tmp 不进分支）

## 逐项验收表

| # | 验收项 | 判定 | 证据（自采） |
|---|--------|------|--------------|
| ① | 已知路径直建 | **PASS** | 独立 spec V1：ProjectCreate(临时已知路径)→ project.json 五字段全断言（name/description/workspace/agentFile/createdAt>0）+ AGENTS.md/.nebflow/.gitignore 脚手架 + mount（rootSessionId=nebula-root）+ 成功消息含 `Task(project='…')` 提示；幂等重放（首次带 name、重放缺省 name 异形调用）→ "already exists" 且挂载保持 |
| ② | 未知路径弹面板 | **PASS** | 独立 spec V2：缺省 workspace → 工具 2s 窗口不自行完成（pending 实证）+ 真实 InteractionHub 渲染 askUser 帧带 requestId + 候选排除已占用目录与点目录（仅剩 free，排序）+ allowOther 兜底；取消收尾搁置 |
| ③ | 面板选择后创建 | **PASS** | 独立 spec V3：以 **Other 自由输入非候选路径**（与上游点按钮差异场景）→ 创建+挂载全通，name=basename；上游 spec ③ 点选候选按钮场景独立重跑亦绿 |
| ④ | 创建后 Task 可触发 | **PASS** | 独立 spec V4：Task(project=…) → 真实分发器会话 `dispatcher-aaaccda5` 拉起（agentStart.nodeSessionId 前缀 dispatcher-）→ RecordingLlm 单 delta 终态 → registry 拆除，全链收尾 |
| ⑤ | #43 兼容 | **PASS** | 独立 spec V3：pending 期间以真实 requestId 注入 agent 形态负载（`text` 字段、无 `answers`）→ hub 日志 `answer shape does not match kind=AskUser — card RETAINED`，工具保持 pending；随后用户形态 `answers` 帧正常消费完成创建。#43 三层语义（槽形状校验/pending 排队/前端 injected 非答案）结构性继承：面板派发同一 `AgentCommand.AskUser`，AgentActor/InteractionHub/前端均零改动；`AskUserPendingInjectionSpec` 回归绿 |
| ⑥ | 取消/超时/冲突语义 | **PASS** | 独立 spec V5：取消哨兵 → 搁置消息 + store 与文件系统双侧零残留（无半成品 project.json/目录）；V1：同名异 workspace → 明确报错且旧定义不被改指；无会话缺省 path → 立即报错（上游 ⑦ 重跑绿）；无悬挂出口齐备：作答/取消/无会话/headless guard/hub 缺席（AgentActor 回 Nil → Shelved） |
| ⑦ | 回归 + 代码审查 | **PASS** | 见下节 |

## 回归结论

- 独立 spec（隔离临时 HOME）：**5/5 绿**（`ProjectCreateVerifySpec`，取证后已删除）
- 上游新 spec 独立重跑：**7/7 绿**（`ProjectCreatePanelSpec`）
- 改动域回归子集：**85/85 绿**——AskUserPendingInjectionSpec（#43 契约）、InteractionHubSpec、InteractionHubReplaySpec、WaitTimeoutAskUserWiringSpec、AskUserQuestionToolSpec、NodeToolsSpec、ProjectStoreSpec、ProjectCreateRootSessionSpec、ProjectActorSpec、ProjectDispatcherSingletonSpec、ProjectDispatcherLifecycleSpec、ProjectSessionCancelPanelFrameSpec
- 未跑全量 sbt（上游分支已全量 2188 绿；按纪律只跑改动域子集，无疑点）
- Playwright：上游 spec 独立重跑 **5/5 绿**（3.5s）+ 我的独立取证脚本 **14/14 断言过**（渲染/载荷/锁定/双主题）
- i18n：`chat.confirm` / `chat.cancel` / `chat.other` 中英（zh-CN.js 502/504/512 ↔ en.js 498/500/508）成对完整；面板=AskUserQuestion 卡片本体，零新增 key
- 代码审查：改动面 = NodeTools.scala（ProjectCreateTool 重写）+ AskUserQuestionTool.scala（`restoreRegistryAfterAnswer` 仅 `private`→`private[tools]`）+ registry.scala（注释）+ 新增测试 4 文件；**前端产品代码零改动**（`git diff 7ba589f3..HEAD -- src/main/resources/web/` 为空）、既有 spec 零语义改动（测试目录全部新增文件）、工具 description/schema 自包含更新到位（双路径语义 + Task 提示 + required 移除）；无越界改动
- 注：`git diff main..HEAD` 现含 NodeEngine/NodeHoldSpec 等反向补丁——系验证期间 main 前移（Node hold 合并 a97d69b0）所致，非本分支改动；真实改动面以 merge-base `7ba589f3..HEAD` 为准（7 文件，与实施报告一致）

## 独立截图（自采，非上游复用）

- 暗色：`/Users/dev/.nebflow/docs/Nebflow/20260903_project-create-panel-verify-dark.png`（面板渲染 + 点选高亮 + 答案回显锁定态）
- 亮色：`/Users/dev/.nebflow/docs/Nebflow/20260903_project-create-panel-verify-light.png`（未点选态：确认禁用/取消可用）

## [verify-fix] 提交

无——未发现需要修复的小问题（i18n 无漏 key、断言无遗漏、文案无 bug）；亦未发现结构性问题。

## 遗留（承接上游，非阻塞）

1. 未合并回 main（下游合并节点执行；main 已前移 Node hold，需一并处理）
2. 候选根硬编码 `~/Claude code`（未来可挂 nebflow.json）
3. 面板问题文本中文硬编码（多语言需 i18n 化）
4. 无 project 定义的手工目录会出现在候选（预期行为）

## 最终判定

**PASS（可合并）**
