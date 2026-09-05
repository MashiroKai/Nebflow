# Mail delivery 标签前端显示方案（#325）

> 版本历史（活文档，原地迭代）
> - v1.0 2026-08-19 Explorer 出品：探索确认前端 badge 已实现，改进点收敛为 prompt 层 delivery 选择指导。
> - v1.1 2026-08-27 Backend：**Mail ask 模式整体移除**（用户裁定，feat/mail-ask-remove）——delivery 收敛为 queue/immediate 两模式；本文档中所有「三种 delivery（immediate/queue/ask）」的历史表述按此理解；前端 ask badge 与 i18n 已随之移除（旧历史消息中的 delivery=ask 不再渲染 badge）。Issue #30 以移除 ask 终结。

---

## 1. 结论摘要

| 问题 | 状态 |
|---|---|
| 前端注入气泡的 delivery 标签（immediate/queue/ask） | **已实现**（commit `b78f63ad` + `2cdfcc83`，2026-08-14） |
| i18n / 持久化 / 历史恢复中的 delivery | 已贯通 |
| 「INTERRUPT 邮件未被 immediate 投递」的行为缺口 | **根因在 prompt 层无 delivery 选择指导**（本方案核心改动） |
| 关联方案 #326（INTERRUPT 真打断 + mailbox pending 展开 + 计数校正） | **已存在** `interrupt-pending-mail.md`（v1.1，draft·待确认冻结），与本方案互补不重叠 |

## 2. 现状分析

### 2.1 前端 delivery 标签 —— 已实现，无需改动

Mail delivery（v1.1 起为 immediate/queue 两模式；v1.0 时代含已移除的 ask）在前端**蓝色注入气泡**上已有标签，链路完整：

| 环节 | 文件 · 位置 | 状态 |
|---|---|---|
| 后端解析 delivery | `MailTool.scala` L146-150 | ✓ |
| `AgentCommand` 携带 delivery | `agent/protocol.scala` L29-35, L49-51 | ✓ |
| `emitInjectedUserEvent` 合并 delivery 到 WS | `AgentActor.scala` L186-211 | ✓ |
| WS `user` 事件回读持久化 | `gateway/WebSocketRoutes.scala` L3760-3781 | ✓ |
| `UiMessage.User.delivery` 编解码 | `shared/protocol.scala` L254-256, L307, L356 | ✓ |
| 前端 badge 渲染 | `web/js/chat.js` L382-389, L410, L442-447 | ✓ |
| 历史/恢复透传 | `web/js/persistence.js` L256, L578 | ✓ |
| i18n | `zh-CN.js` / `en.js` L183-188 | ✓ |
| badge 样式 | `web/css/chat.css` L628-654 | ✓ |
| Mailbox Pending 区 Queue 标签 | `flowViewers.js` L136 | ✓（pending 区只有 queue） |

### 2.2 行为缺口 —— prompt 层无 delivery 选择指导

`~/.nebflow/prompts/system-prefix-for-teams.md` 的 `## Mail Handling — Don't Auto-Interrupt`（L101-116）
只解释了 `type` 各值含义（INFO/FOLLOW_UP/PARALLEL/INTERRUPT/RESULT），**对 `delivery` 参数如何选择只字未提**。

反映到真实行为：Manager 发 `[INTERRUPT]`（urgent, handle now）时，LLM 在两个正交维度间做选择——
- `type="INTERRUPT"` 已写在 prompt → 知道要发紧急邮件
- `delivery`（immediate/queue）无指导 → LLM 凭 Service 描述自行判断，错误命中了 `queue`
  （queue 的 "do this, then that, then that" 与「按序做完再处理」语义相近，最容易被误选）

期望：`[INTERRUPT]` 必须 `delivery="immediate"`（即时注入），立即处理。

> 更底层的机制事实（关联 #326）：即使 LLM 正确选了 `immediate`，`AgentActor.processing` 对所有 `ImmediateInput`
> 一律 append 到 `pendingImmediateInputs`，**要到 turn 结束才 drain**——长 turn 下 INTERRUPT 仍会延迟数分钟。
> 那是 `interrupt-pending-mail-spec.md`（#326）的真打断方案要解决的，与本方案互补：**prompt 让 LLM 选对 delivery（本方案），
> #326 让 delivery=immediate 被真正即时处理**。

## 3. 改动方案

**核心：只在 prompt 增加 delivery 选择指导，不改任何代码/后端/前端文件。**

### 3.1 `~/.nebflow/prompts/system-prefix-for-teams.md`（team 全部成员生效）

在 `## Mail Handling — Don't Auto-Interrupt` 节（L116 之后）追加：

```markdown
## Mail Delivery — Choose the Right Mode

每次 Mail 调用都要根据「时效性」选择 `delivery` 参数（immediate / queue）：

- `immediate`（默认）— 即时注入到对方对话。用于 `[INTERRUPT]` 与任何时效性强、
  需要对方马上处理的邮件。
- `queue` — 持久化排队，对方当前任务完成后再逐条处理。仅用于**可延后的串行任务链**
  （"做完 A 再做 B 再做 C"）。

规则：
- `[INTERRUPT]` 类型 **必须** `delivery="immediate"`，禁止 queue。
- 普通派工（INFO / FOLLOW_UP / PARALLEL / RESULT）默认 immediate。
- 只有明确是「串行链、可延后」时才用 queue，其余一律 immediate。
- 不确定时选 immediate——不会错，queue 是可显式表达的降级。
```

### 3.2 `~/.nebflow/prompts/manager-prefix.md`（仅 Manager 生效）

在「沟通纪律」节补一条：

```markdown
- 紧急打断用 `Mail(type="[INTERRUPT]", delivery="immediate", ...)`，禁止用 delivery="queue"——
  queue 会等对方当前任务完成，INTERRUPT 语义失效。
```

### 3.3 （可选辅助，非阻塞）MailTool description

`src/main/scala/nebflow/core/tools/MailTool.scala` L106-111 delivery 描述可补一句
「`[INTERRUPT]` must use immediate」，作为 prompt 之外的双保险。不改行为，仅文本。

## 4. 涉及文件

| 文件 | 改动类型 |
|---|---|
| `~/.nebflow/prompts/system-prefix-for-teams.md` | **新增** `## Mail Delivery` 小节（核心交付） |
| `~/.nebflow/prompts/manager-prefix.md` | **新增** 1 条沟通纪律（可选但建议） |
| `src/main/scala/nebflow/core/tools/MailTool.scala` | （可选辅助，非阻塞）delivery 描述补映射 |

> 前端/后端代码一律不动。前端 badge 已实现；字段已贯通；无需新增字段。

## 5. 预期变更展示（行为对比）

| 场景 | 现状（LLM 自作主张） | 改后（prompt 指导） |
|---|---|---|
| 紧急打断 | 可能选 queue → 邮件排队数分钟 → 气泡上显示「排队」badge，用户误以为已即时送达 | 必选 immediate → 气泡显示「即时」badge，语义正确 |
| 普通派工 | immediate（默认） | 不变 |
| 串行任务链 | 不确定，可能误用 immediate | 明确只有此场景用 queue |

## 6. 验收条件

> 约束：前端 QA 简化，用户自验；本改动为纯 prompt，不涉及服务启动流程变更，冒烟测试不适用。

1. **prompt 落位（自动化，二值）**：`system-prefix-for-teams.md` 含新增 `## Mail Delivery` 段，
   且包含「`[INTERRUPT]` **必须** `delivery="immediate"`，禁止 queue」映射。用
   `Grep "INTERRUPT.*immediate"`（-i）断言，命中 = PASS。
2. **Manager prefix（若做）**：`manager-prefix.md` 含「紧急打断禁止 queue」一条，`Grep` 断言。
3. **行为抽查（用户自验，手动一次）**：触发 Manager→成员 `[INTERRUPT]` Mail，
   断言请求体/气泡显示 `delivery=immediate`，且该邮件**未**进入目标 `mail-queue.json`
   （即未被 queue，`Grep` 队列文件无该项）。
4. **回归（用户自验）**：一条明确「串行链」语义的 Mail 仍可显式带 `queue`——确认规则没把 queue 一刀切禁掉。
5. **（可选辅助改动时）**：`MailTool.scala` 文本改动不破坏编译——`sbt compile`（若构建环境可用）退出码 0。
   若环境不可用，此条降级为「静态文本审查通过」。

## 7. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 规则写太硬，LLM 连正当 queue 场景都弃用 | 文案保留「queue 仍可用」，仅 INTERRUPT 语义强制 immediate |
| prompt 过长稀释注意力 | 段落 ≤6 行，紧跟在现有 Mail Handling 节后，语义连贯 |
| 对现有 immediate 行为冲击 | 无——immediate 本就是默认，规则只是把「何时用」讲清楚 |

**回滚**：删除新增 prompt 段落即可恢复原状。无代码、无迁移、无数据变更、无构建影响。

## 8. 与 #326 的关系（避免重复工作）

`interrupt-pending-mail-spec.md`（v1.1）已覆盖「INTERRUPT 真打断 + mailbox pending 展开 + 计数校正」。
本方案（#325）**不重复** #326 的技术改动，两者分工：

- **#325（本方案）**：prompt 指导 LLM 选对概览维度 ——「什么时候 delivery 该用 immediate/queue」。
- **#326**：系统机制 ——「即使 delivery=immediate，processing 真打断、pending 可展开、计数校正」。

建议落地顺序：先 #325（纯 prompt，零风险，立即改善 LLM 选型）→ 再评估是否批准 #326（机制层修复）。
若 #326 已冻结进入实施，本方案的 prompt 段与其互补，无冲突。

## 9. 不做的事（明确排除）

- 不改 `FlowMailStore.MailRecord` 加 delivery 字段（Mailbox History 区标签）——用户裁定不纳入；
  `interrupt-pending-mail-spec.md` §7 同样明确「不改 History」。
- 不改 `onMailDelivered` 传参 / 不改 `forkAndAsk` 补入 mailbox。
- 不改任何前端代码（chat.js / persistence.js / flowViewers.js / css）——已实现。
