# 阶段 1 试点事故复盘——NodeCancel 验证循环 spawn（2026-09-01）

> 事故复盘（阶段文档，日期前缀）。试点恢复后如需更新，in-place 修订并重新 commit。

## 一、现象

- 09:33:49 起，分发器会话（qa-backend @95eeea2c，第二阶段试点链执行者）循环 spawn Explorer 调研节点：`lit-survey-1 → cancel-test → cancel-test-2 … → cancel-test-11`（09:45:31 仍在建），每 30s-2.5min 一个，共 12 个
- 每个节点真实执行调研任务（WebSearch 文献检索 / WebFetch httpbin delay / 本地目录读取），烧真实 token
- 09:46 用户报告后台 agent 无法结束 → Nebula AgentControl cancel 分发器会话止血（递归源已断）

## 二、根因（非架构 bug，执行逻辑循环）

**架构侧全部正常**：
- NodeEdit 创建即运行、completed 结果注入、TTL 5min 自动归档——12 节点全部 completed 收敛（活动图 3 + 归档 9），0 running / 0 failed / **0 cancelled**
- 「Node 会话 parent=-」= 设计使然（NodeRunner.spawnAgentActor 经 system.spawn 顶级 actor，Node 是独立 leaf 会话，非分发器子会话）——非异常，无需修

**执行侧循环机制**：
1. qa-backend 验证 NodeCancel（步骤 2.3）：需「创建运行中节点 → 取消 → 确认 Cancelled」
2. 「创建即运行」语义下入口节点 LLM 秒级-分钟级完成，completed 事件注入**早于** NodeCancel 工具往返 → 每次取消都命中已终态节点（NodeCancel 对 completed 是 no-op）
3. qa-backend 每次失败换策略重试：sleep 拖住（LLM 跳过）→ httpbin delay（外部依赖，节点未真正等待）→ WebFetch delay/30（未生效）→ 「创建后下一轮只调 NodeCancel」（实际仍建新节点 cancel-test-10/11）——12 次未止损
4. 每次重试 = 新建真实调研节点 = 真实 token 消耗 + completed 注入打断行动意图

**责任分层**：
- 分发器会话（qa-backend）：验证执念无止损纪律——同类验证失败 ≥2 次应标记观察项上报，而非无限换策略重试；且把「轻量 task」执行成「WebSearch 4 次 + 全目录读取」（非轻量）
- 派工指令（Manager）：NodeCancel 验证步骤未给止损纪律与轻量化强约束；真实实例跑验证类操作未声明「试 2 次不中即上报」

## 三、NodeCancel 未命中的设计难点（架构观察项）

「入口节点创建即运行」下，取消验证的窗口极窄：
- LLM 驱动的节点任务秒级-分钟级完成，completed 注入速度 > 工具往返
- 外部依赖造窗口（httpbin/WebFetch delay）不可靠：LLM 节点未必真执行 delay
- 需可靠构造「运行中」窗口的方式：task 文本强制「先 Bash sleep 60 再输出」（LLM 直接执行）——但 LLM 可能跳过；更可靠：Node 工具层/测试钩子注入（如 NodeEdit 支持 `startDelay` 测试参数）——**建议 Backend 评估**（不阻塞试点：NodeCancel 止损场景 = 长任务节点，真实长任务窗口足够）

## 四、止血与收敛

- 09:45:31 Nebula cancel 分发器会话 ✅（AgentControl，递归源已断）
- 12 节点全部 completed 自然收敛 ✅，结果全文保留（活动图 + flow-map-archive.json），无泄漏无卡死
- 节点图当前：活动 3 个 completed（TTL 5min 自动归档）+ 归档 9 个——无需手动清理，试点恢复前确认活动图清空（TTL 自然清）

## 五、恢复建议

1. **试点恢复前置**：活动图节点等 TTL 自然归档清空（或确认无 running 后继续）
2. **派工纪律**（写进试点派工模板）：
   - 节点 task 强制轻量（≤1 次检索、≤1 个文件读取、输出 ≤200 字要点）——真实实例试点节点一律轻量
   - 验证类操作止损纪律：同类验证失败 ≥2 次 → 标记观察项上报，禁止无限重试
   - 需要反复试的机制验证（NodeCancel 窗口/竞态）→ 隔离实例做（TTL 测试档 10s 同环境），真实实例只做正向链路
3. **NodeCancel 验证改隔离实例**：构造可控长任务（或 Backend 评估测试钩子）后验证，真实实例不再反复建节点
4. **架构观察项**：Node 会话顶级独立（parent=-）是设计；completed 注入快于工具往返是「创建即运行」的固有语义——分发器做「取消验证」时应先 NodeList 确认状态再决定是否 cancel，而非盲发
