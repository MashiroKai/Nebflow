---
name: isolated-smoke-verification
description: 隔离实例冒烟验证法——验证多 agent 交互修复/后端改动时，用临时 NEBFLOW_HOME+独占端口+mock 路由起隔离实例，断言锚定持久化产物而非易失 stdout；含沙盒泄漏三信号三角定位、kill 前 PID 验身、易失证据替代法证。Use when 验证 Delegate/SubTask 链、重启恢复、fallback 等跨 agent 行为，或怀疑冒烟测试污染真实数据根。
audience: nebflow-project
language: zh
status: active
last_verified: 2026-08-24
---

# Isolated Smoke Verification — 隔离实例冒烟验证

验证多 agent 交互（子任务通知/聚合/清理链、重启恢复、fallback 路径）的标准冒烟法。核心信念：**验收证据不依赖单一易失通道**——stdout/日志可能被缓冲、SIGKILL 或降采样吞掉，持久化产物（会话 JSONL、任务库终态）才是可靠法证。

## 1. 隔离实例搭建

- 临时 `NEBFLOW_HOME`（独立目录）+ 独占端口 + 会话路由 mock（按 session 前缀区分角色行为）
- 断言锚定**持久化产物**：会话 JSONL 消息内容、taskStore 终态——而非 stdout 日志
- nohup 块缓冲 + SIGKILL 会丢 stdout 法证（先例：issue #31 冒烟 smoke.log/mock.log 0 字节，靠根会话 msg[6] 注入内容 + 任务库终态独立核验兜回全链结论）
- 实例参数坑：ModelConfig 必填 contextWindow；`/api/command` 用户输入形状 = `type:"userMessage"` + content；root sessionId=UUID 仅显示名

## 2. kill 前验明 PID 正身

fork 出的 sbt/java 子进程 **cmdline 不含项目路径**——按端口或按路径匹配可能误杀并发 agent 的实例：

- `lsof -ti :<port>` 定位 + `cwd`（`lsof -p <pid> | grep cwd`）确认目录是目标实例
- 双保险：`kill <pid>` 后 `lsof` 复查端口释放，必要时再清
- 隔离 worktree + 端口隔离（如 8097）是防止误伤宿主（8080）的前提

## 3. 沙盒泄漏验证（三信号三角定位）

验证冒烟/沙盒运行是否泄漏进真实数据根（如 ~/.nebflow）时，三角定位三个独立信号，单一信号可被欺骗（env 缺失、cwd-relative fallback、静默回退链），三信号一致才算隔离：

1. **源码 data-root 优先级**：flag override > env/home > default，确认实际取哪个
2. **实例日志 config provenance**：从实例日志的模型/上下文窗口值反推它读了哪个 home（先例：日志出现 `paid/paid-model` 而真实 home 默认是 `107/glm-5.2-107`，证明非真实 home）
3. **字节级对比**：`cmp` real-home vs test-home 关键文件 + 扫 cwd 残留（`ls ~ | grep <artifact>`）

## 4. 易失证据通道的替代法证

当真实环境当轮未触发目标帧形态（触发条件依赖 provider 端内部行为，无法按需诱导）：

- 接受「生产未复现 + spec 生产帧序回放钉正例 + 负例日志计数归零」组合，不要求生产正例复现
- 回放真实捕获帧序在 spec 里等效覆盖同一代码路径（真实 WARN 日志捕获帧形原样回放）
- 对偶模式：stdout 证据丢失时改用持久化产物（会话文件/任务库）法证——按「哪个通道持久/可复现」重构验证路径，而不是放弃验证

## 5. 确定性真实 fallback 夹具

验证 fallback 路径的 QA 夹具：配置 bogus primary provider（如 `127.0.0.1:9` 必挂）→ fallback 到真实免费模型，每 turn 必走 modelChanged 切换路径。mock 路由无法覆盖真实 WS 帧时序与 fallback 后的 label 切换，只有端到端真实 LLM 调用才能验证 done/modelChanged/usageUpdate 帧完整链路。老后端兼容用 wire 级 strip 字段模拟；弱模型 agentic 失控场景用无工具 qa-mini agent。

## 6. Mock LLM 驱动的工具链冒烟（#28 0b 实战沉淀，2026-09-01）

工具层（无 REST 写端点，靠 agent 会话 WS 调用）的 E2E 冒烟用 mock LLM 状态机驱动。五条坑（全部实战踩过，每条约 1-2 轮排查）：

1. **Anthropic 协议 tool_result 在 role=user 消息里**：content 是 list 时找 `type=tool_result` 块——**不是 role=tool**（那是 OpenAI 格式）。mock 决策若按 `role=="tool"` 解析永远看不到工具结果 → 死循环首工具。解析：`for m in reversed(messages): if isinstance(m.content, list): for x in reversed(x): if x.type=="tool_result": ...`
2. **节点/子 agent 请求标识在 metadata.session_id**：`agentId` 字段可能为 None——节点 agent（NodeEngine spawn，sessionId="node-xxx"）的请求靠 `metadata.session_id.startswith("node-")` 区分；只查 agentId 会把节点请求当 root 处理（mock 返回错误 tool_use 污染节点结果）。
3. **残留 mock 进程清理**：nohup 起的 mock 其 **cwd 继承启动 shell**（可能是工作目录而非 mock 目录）——lsof+cwd 匹配会漏杀 → 旧 bug 代码占端口继续处理请求、新 mock 起不来、日志计数混乱。cleanup 必须加 `pgrep -f <脚本路径>` 兜底杀。
4. **mock 序列必须符合产品工具校验语义**：状态机发出的调用若被产品校验拒绝（如 NodeEdit 新节点必须 task 和/或 in；out 数组被 schema 拒），序列断链。设计序列前先读工具校验代码（createNode/editNode/parseOut）。
5. **决策状态机用 tool_result 内容驱动 + 状态标志，不用请求计数**：注入（ImmediateInput）与工具结果可能合并进同一请求、节点请求与 root 请求交错——计数会错位。内容匹配（如 `"Node 'A' completed" in text`）+ 标志推进（b_created→b_cycle→...）；节点 id 提取用**历史扫描**（创建结果可能与其他消息合并，不能假设独立请求）。

## 7. 进程清理纪律（2026-09-05 裁定）

- 冒烟结束**必须清理自己 spawn 的全部进程**：隔离实例、mock server（nohup 起的也要）、端口占用者一个不留——不留给宿主或下一个会话手清。
- 收尾动作固定三步：① 杀（kill 注册的 PID + `pgrep -f <脚本路径>` 兜底，见 §6.3）② `wait` 收尸 ③ `lsof -ti :<port>` 复查端口释放。
- 脚本包 `trap 'cleanup' EXIT INT TERM`——裸 EXIT trap 在 Ctrl+C/kill 退出路径不触发（正是冒烟最常被打断的时刻）。
- 不得依赖「会自己退出」：`while True` 死循环、长 `sleep`、stdio 常驻进程不自终止；mock 路由若被旧残留占端口，冒烟结论直接作废（§6.3 先例）。
- 环境表里的宿主 PID 绝对禁杀（第一道防线）；8080 端口识别是第二道防线；清理前照旧 PID 验身（§2）。

## Evidence

- issue #31 冒烟：smoke.log/mock.log 0 字节但验收不降级——msg[6] 双结果注入 + msg[7] 42ms 触发 + fast=completed/stall=failed(2 restarts=maxRestarts) 三重持久化证据链；qa 复核采用同一法证路径通过。
- Nebflow cold-start 冒烟：`cmp /tmp/nb-s3-smoke/home/auth.json ~/.nebflow/auth.json` → "differ: char 2"；实例日志 "Context window: 128000 tokens (from paid/paid-model)" 不可能来自真实 home（默认 107/glm-5.2-107）；cwd 扫无 stray auth.json——三信号一致证明隔离。
- qwen 碎片聚合修复交验：真实端点本轮只发 name 缺失形态，name:"" 延续帧未触发；以 sendMessageStream 生产帧序回放 spec + 变异验红×2 钉死聚合路径，负例以实例日志 0 条退役串验证。
- #31 对偶：stdout 因 SIGKILL 块缓冲全丢，改用持久化产物核验——两案共同模式：证据不依赖单一易失通道。
- 大合并关卡：/tmp/nb-final-gate worktree + 8097 端口 + nohup + kill PID + lsof 双保险，全链路零冲突。
- #28 0b node-runner 复验：mock LLM 状态机驱动 NodeEdit/NodeList/NodeCancel/ProjectCreate 全链路（隔离 8102 + mock 18493），五坑见 §6——修复 2 P1（ttlScanner SOE/双前缀）后 V2 五验收点全过；多轮失败全为 mock/环境问题，产品代码全程按契约工作。
