# 设计：AskUser 社交腿答案回注链路（架构＋数据流）

纪律声明：本批只设计不实施。每条现状论断带 `路径:行号` 锚点（主仓 `/Users/kaiyu/Claude code/Nebflow`，行号为 2026-10-20 快照）。

---

## §1 现状链路盘点

### 1.1 下行（已通，本批零触碰）

| 步 | 事实 | 锚点 |
|---|---|---|
| D1 | `AskUserQuestionTool` 非阻塞-only：`requestId=asknb-<16hex>`（`forAskUserNonBlocking`），`replyTo`=一次性桥引用 `AskUserAnswerBridge`，派发后立即 ack（「不要等待」） | `core/tools/AskUserQuestionTool.scala:377-392`；`shared/InteractionRequestId.scala:45` |
| D2 | 会话 → hub：`AgentCommand.AskUser` → `InteractionHubCommand.Request`；槽位 = `PendingRequest{reply, rootSessionId, sourceAgent, sourceSession, kind, payload, createdAt}`；`awaitsAnswer` 恒 false（工具面），仅 `ProjectTool.pathPanel` 置 true | `agent/AgentProcessing.scala:1269-1304`；`agent/InteractionHub.scala:51-59`；`:1281-1292` |
| D3 | 提问载荷自带归因：`project`（项目链名）＋`nodeName`（节点名/分发器标 "dispatcher"）随 payload 携带；节点提问另落 `node-ask` 审计事件（FlowMapEventLog） | `AgentProcessing.scala:1220-1235`；`:1244-1262` |
| D4 | hub 扇出：rootWsSend 渲染桌面卡 ＋ WsHub 广播 → `BridgeManager.dispatchAgentEvent` → 各桥插件 `onAgentEvent`；帧字段 type/sessionId/requestId/sourceAgent/sourceSession/agentName | `bridge/BridgeManager.scala:97-101`；`agent/InteractionHub.scala:247-258` |
| D5 | 微信腿下行：`onAskUserFrame` → B-3 新问顶旧问（旧问按 requestId 精确退役，hub 槽位保留桌面可答）→ per-sender 台账 slot → 编号纯文本卡（问头 N/M＋选项编号＋指令页脚；>4000 字案甲降级＝摘要＋「全文」拉取） | `social/WeixinIlinkBridgePlugin.scala:542+`；`social/WeixinAskLeg.scala:135-185`、`:48`、`:54` |
| D6 | 飞书腿下行：静态卡（按钮按 rid 消歧，多卡并存合法）＋文本降级 | `social/FeishuBridgePlugin.scala:951-1006` 附近；`social/FeishuAskCard.scala` |

### 1.2 上行（六步已通，第七步断裂）

| 步 | 事实 | 锚点 |
|---|---|---|
| U1 | sidecar 收信 → 桥 ingest → `Inbound{senderId, routeKey, text, mediaLanded,…}`；`routeKey` = senderId（trim 非空） | `tools/weixin-sidecar/msgloop.mjs`；`WeixinIlinkBridgePlugin.scala:1258-1268` |
| U2 | 身份闸：`allowed_ilink_user_ids` 非空 ⇒ fail-closed（发送者不在册即丢）；空＝不限 | `WeixinIlinkBridgePlugin.scala:29`、`:52`、`:313` |
| U3 | ask 臂前置臂（在 allowlist 闸与路由/注入臂之间）：①案甲「全文」拉取臂 → ②「取消问题」取消臂（hub CancelRequest，幂等裁决）→ ③编号解析臂 `parseReply`（裸编号=第一题、`问.编` 寻址、选项标签精确匹配〔大小写/空白容忍〕、`0 `/`他` 前缀自由文本、超界拒绝不钳制） | `WeixinIlinkBridgePlugin.scala:308-319`、`:440-488`；`WeixinAskLeg.scala:241-276`、`:224-226` |
| U4 | 汇聚：`answerAsk` → 校验/定长槽展开 → `ctx.forwardInteractionAnswer(requestId, rootSessionId, {"answers":[…]})` | `WeixinIlinkBridgePlugin.scala:513-528` |
| U5 | 网关：`forwardBridgeInteractionAnswer` → hub `Answered` ＋ 桌面反射（`.ui.json` 作答行 `answerOf` ＋ `askUserAnsweredRemote` 翻面帧；秘密槽脱敏） | `gateway/WebSocketRoutes.scala:531-570`；`gateway/BridgeAnswerReflection.scala:39-84` |
| U6 | hub 消费（原子 modify＝先到先得）：requestId 命中＋形状匹配 → 消费槽位 → `complete` → `slot.reply`；重复/未知 → `unknown-request-id` 拒收（幂等 no-op）；形状不匹配/秘密槽明文 → **卡保留不消费**（#12 语义）；无 requestId 旧前端 → 同 root 最老 kind 兼容槽（带 multi-card 警告） | `agent/InteractionHub.scala:487-558`、`:578+` |
| U7 | **最后一公里（断裂点）**：`complete` → `AskUserReply(replyTo)` → `AskUserAnswerBridge.deliver`：取消信号/派发失败信号/附件清单槽分支后，`sessionAlive`（agentRegistry 查询）→ **活** ⇒ `ImmediateInput`（下一 turn 边界注入，fromUser=true）→ **死 ⇒ WARN "answer NOT delivered"，答案终点站** | `agent/AskUserAnswerBridge.scala:89-160`、`:245-248`、`:134-137` |

---

## §2 缺口定位（根因，非现象）

1. **后台批节点的会话生命周期**：项目/委托链节点会话在终态即销毁——loop 节点 `guarantee(destroyLoopSessions…)`（`core/project/NodeStarter.scala:457-463`）、`NodeCanceller.destroyNodeSessions`（`core/project/NodeCanceller.scala:759-764`）、`BgTaskRegistry.markSessionFinalized`。节点问完问题、以 blocked 终态收场后，**hub 槽位仍在册**（hub 等待无超时，R1 起；blocked 不触发 `CleanupForSession`——该命令只覆盖 cancelled/abandon/dead-session reap 族，`actor/InteractionHubCommand.scala:38-49`）＝「槽位活着、会话没了」的僵尸窗口。
2. 作者此时在微信回复 → U1-U6 全部正常 → U7 `sessionAlive=false` → **答案 WARN＋静默丢**（有日志无回执，作者端零感知）。
3. Mail 侧同样进不去：blocked 属 terminal 族，`node:<id>` 路由拒收（`NODE_TERMINAL_NO_MESSAGE`）——即任务叙述的「Mail 喂不进去」。引擎侧唯一合法复活入口＝节点翻转 Running 时的重激活（`NodeCanceller.scala:756-757` 注释「死会话不可复活……合法复活唯一入口」）。
4. 结论：**hub 收得到、判得对、但送达面在 U7 单点断裂**。治本＝在 U7 加答案着陆面，不动 hub 权威、不动桥台账、不动桌面反射。

---

## §3 设计总图（上行链路，新增面以【新】标注）

```
作者微信/飞书回复（纯文本）
  │ U1 sidecar 收信 → 桥 ingest（既有）
  │ U2 渠道 allowlist 闸 fail-closed（既有）
  │ U3 台账命中 → parseReply（编号/问.编/标签/0·他 前缀/【新】短号 N <文本>）
  │ U4 answerAsk → forwardInteractionAnswer(requestId, root, answers)（既有）
  │ U5 网关 forwardBridgeInteractionAnswer → hub Answered ＋ 桌面反射（既有）
  │ U6 hub complete 先到先得·幂等（既有）
  ▼ U7 AskUserAnswerBridge.deliver
      ├─ 会话活 ──→ ImmediateInput 下一 turn 边界注入（既有，零改动）
      └─ 会话亡 ──→ 【新】答案着陆面 AskAnswerLanding：
            ├─ 归因在（构造时快照 project/flowNodeId/flowChainId，§5）
            │    → 查引擎宿主：ProjectRuntimeRegistry（项目链）
            │              / DelegateFlowHosts（委托链，core/project/DelegateFlowHosts）
            │    → 宿主在 ⇒ 引擎重入：推荐甲案＝ReenterDispatcher 同族管线，
            │      答案文本作为本轮反馈/分发器补充随重入注入
            │      （FlowMapEventLog "answer-reentry" 留痕，先例 "reentry-triggered"
            │       core/project/FeedbackRouter.scala:168-175）
            │    → 宿主不在（项目卸载/链归档）⇒ 失败出口 ③
            └─ 归因缺席（root/delegate 直会话已删等）
                 ⇒ 失败出口 ③：审计行 ＋ 桌面 toast（interactionAnswerRejected
                   广播族既有面）＋【P1】渠道回执（payload 自带 via 渠道地址，
                   经 BridgeContext outbound 回发「答案未送达：会话已结束」）
```

要点：
- **单一改动主点**＝`AskUserAnswerBridge.deliver` 的 `sessionAlive=false` 分支。不新开第二汇聚点（hub 仍是唯一答案权威，U5 的桌面反射照旧两面落库）。
- **着陆面对渠道自动同效**：着陆挂在 hub complete 之后、渠道无关——微信与飞书（点⑦）同批获得，飞书侧唯一增量＝文本解析臂补短号＋卡头批名（见映射规则文档）。
- **答案语义不变**：重入注入的文本仍走 `AskUserQuestionTool.formatAnswer`（与阻塞/非阻塞/渠道三面同一实现，`AskUserAnswerBridge.scala:131`），格式无第二把尺。

## §4 答案着陆面（AskAnswerLanding）规格

- **位置**：建议新件 `agent/AskAnswerLanding.scala`（或 core/project 侧 `AnswerReentry.scala`，实施批按依赖方向裁量——注意 shared←core←gateway 依赖铁律，hub 在 agent 包，引擎宿主注册表在 core/project，反向引用需经端口注入）。
- **输入**：`(requestId, ctx, answers, 归因三元组, 答复文本)`。
- **三条出口**：
  1. **引擎重入成功**：审计 `answer-reentry` 事件（workspace/project/nodeId 先例齐备）＋ INFO；hub 槽位已消费，无后续。
  2. **归因在、宿主不在**：降级出口 ③ ＋ WARN 指明「链已归档/项目已卸载」。
  3. **失败出口**：WARN ＋ 审计行 ＋ 桌面 toast（P0）；P1 渠道回执（见开放问题 #2）。**任何出口都不静默**。
- **不做的**：不复活已删除的 root/delegate 直会话（超范围）；不重开已归档链（对齐 restoreChain 需显式授权的精神）；不引入等待/超时。

## §5 节点归因快照（构造时固化）

- `ToolContext` 已带引擎侧身份：`projectName` / `flowNodeId` / `flowChainId` / `isDispatcher` / `taskId`（`core/tools/types.scala:83-111`、`:150`）。`AskUserQuestionTool.askUser` 构造桥引用时（`AskUserQuestionTool.scala:388`）把归因三元组 `(projectName, flowNodeId, flowChainId)` 快照进 `AskUserAnswerBridge.ref` 参数——快照语义，不信运行期回查（hub 槽位 complete 后即消，回查不可得）。
- 归因缺席（root 自问/Nebula）＝合法状态，直接映射失败出口 ③。

## §6 双通道竞争（点③）——零新机制，依据如下

- 控制台卡片作答（web 腿 `handleAskUserAnswer`）与微信回复在 **hub `Answered` 单点收敛**，原子 modify 先到先得（`InteractionHub.scala:492-496`）；后到者 requestId 无在册槽 → `unknown-request-id` 拒收（`:528`），**幂等忽略，无二次注入**。
- 桥侧墓碑：已答条目重复编号回复 → `ToastAlreadyAnswered` 拒绝，绝不二次注入（`WeixinIlinkBridgePlugin.scala:471-480`）。
- 桌面翻面幂等：`askUserAnsweredRemote` → 前端 `closeAskUserCard` 已答跳过（`BridgeAnswerReflection.scala:71-82`）。
- hub 层 `unknown-request-id` 的 rejectAnswer 广播只达桌面 toast，微信作者收到的拒绝来自桥侧墓碑层（先挡在前面）——两层面分工已闭合，无需新增。

## §7 身份闸（点④）——存量复用

- 微信：作答资格＝**台账键命中（routeKey==entry.routeKey，即只有收到过问题的账号）∧ 渠道 allowlist 通过**。二者与，缺一即 fall-through（聊天透传或丢弃），不存在误路由到 pending 问题的路径。台账键天然是「点对点」闸——question 发给谁，谁的回复才进解析臂。
- 飞书：`allowed_open_ids` 同构闸，文本 intake 与卡片点击同闸（`FeishuBridgePlugin.scala:296-300`、`:970`）。
- 设计只固定判据句，不加新机制。autoBind 默认会话臂与 pending 问题无关，不变。

## §8 零超时纪律＋可见性（点⑥）

- **明文保持**：hub 等待无超时（R1 起，`InteractionHubCommand.scala:27-36` 注释链）；本设计任何一环不引入超时/自动过期/定时杀。着陆面只处置「答案后到」，不处置「问题久等」。
- 可见性增补（P1，实施批拆卡）：
  1. 健康面/任务面板 pending-ask 视图：`ListAllPendingAsks` 全局快照已有（`InteractionHubCommand.scala:52-58`；前端 `getPendingAsks`，`gateway/WsSkillsAsksHandlers.scala:193-214`）→ 每条增补显示**批名（payload.project）＋节点名＋问题摘要＋等待时长＋发起会话状态**（活/已结束→将走重入面）。
  2. 审计链闭合：`node-ask`（提问，`AgentProcessing.scala:1244-1262`）→ `answer-reentry`（着陆成功）→ 失败出口审计行——三事件同一 FlowMapEventLog 基座，健康面可回放「哪批在等什么、答案落到了哪」。

## §9 涉及文件清单（实施批预盘点；本批零代码改动）

| 文件 | 改动性质 |
|---|---|
| `agent/AskUserAnswerBridge.scala` | 修改：`sessionAlive=false` 分支接着陆面（唯一主点） |
| `agent/AskAnswerLanding.scala`（新，名候选） | 新增：着陆面三出口 |
| `core/tools/AskUserQuestionTool.scala`＋`types.scala` | 修改：构造时归因三元组快照入桥引用 |
| `core/project/NodeEngine.scala`（或 DelegateFlowHosts 对应面） | 修改：重入命令入口（ReenterDispatcher 族，携答案文本） |
| `social/WeixinAskLeg.scala` | 修改：短号解析臂＋批名头行（纯函数） |
| `social/WeixinIlinkBridgePlugin.scala` | 修改：无（或仅台账 entry 增补批名透传） |
| `social/FeishuAskCard.scala` / 飞书文本腿 | 修改：批名头行＋文本短号臂同款 |
| spec（Scala 单测＋渠道解析 spec） | 新增：着陆面三出口、短号臂、幂等回归 |

## §10 未决项

见 `开放问题清单__delegate-nebflow-294b9099.md`（8 项候拍）。
