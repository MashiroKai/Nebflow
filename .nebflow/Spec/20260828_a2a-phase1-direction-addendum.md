# 方向补充规格 · A2A 好友功能一期（微信式流程 + agent 发消息 + 转发引用块）

**版本**：v1.1 · **日期**：2026-08-28 · **作者**：Backend（现状核对 + 方向补充）· Manager（v1.1 裁定落稿）
**状态：定稿 · 作者五项裁定已落定（2026-08-28 18:5x），可派实施**
**需求原话**（作者 2026-08-28 18:31）：好友功能「按微信的方式来就行」（好友验证/申请/通过等全按微信常识，不再逐项细说）；当前只多一个能力：**agent 能对好友发送消息**；**用户能把消息转发给 agent，转发走我们的引用设计**（#303 全局引用能力的引用块形态）。加好友搜索=邮箱或 nebflow 号。
**上位文档**：friends-messaging-arch.md v1.2（frozen，2026-08-18 U3 终审）· friends-messaging-spec.md v1.2（frozen，UI）· 20260825_global-reference-spec.md（引用模型，活文档）
**本文性质**：对一期冻结设计的**方向补充**（addendum），不推翻既有裁定；冲突处以本文「待作者裁定」显式标注项为准。

---

## 0 · 现状核对结论（代码事实，2026-08-28 全量走查）

一期设计（arch v1.2 + UI spec v1.2）的**实现完成度远高于预期**——「微信式好友流程 + agent 发消息」的服务端/网关/前端主干已在位：

| 层 | 现状 | 证据 |
|---|---|---|
| neblink-server（Rust friends.rs） | ✅ 微信式全链：lookup（neblink_id 单字段精确匹配）· 好友请求（note 附言）/accept/decline/DELETE/block/unblock · 会话/消息（keyset 分页）/read cursor · 三层限速 | src/friends.rs（lookup :136、PUT neblink-id :185、available :203）；store.rs（friendships 状态机 4 表） |
| 网关本地代理（Scala） | ✅ friends/conversations/lookup 全路由转发 neblink-server，契约由 FriendApiRoutesSpec 钉死 | RestApiRoutes.scala :996-1089 |
| 前端 contacts.js | ✅ 搜索/新的朋友（incoming+outgoing 分组）/验证附言（≤50 字，微信式可选）/同意/拒绝 | contacts.js :186（verify note）、:248-260（accept/decline） |
| 前端 messages.js | ✅ 聊天弹窗（560px 毛玻璃）/「Agent 代发」chip/「已转发」chip（session 级）/header+hover 双转发入口 | messages.js :230（fwdBtn）、:323（chips） |
| 客户端 FriendService.scala | ✅ 全链：收发/补拉/未读 cursor/事件去重/**sendAsAgent（auto/ask/off 三档+双层限速+超限降级 ask）** | FriendService.scala :135（sendAsAgent） |
| **SendFriendMessage 工具** | ❌ **未实现**——core/tools/ 无 FriendMessageTool.scala，未注册 ToolRegistry → **agent 实际发不了消息**（FriendService 入口空挂） | 全仓 grep 零命中 |
| 转发形态 | ⚠️ 现为 arch §8 冻结设计（文本前缀 `[来自 X] body` 走 `sendWs({type:'ask'})`）；作者本次裁定改走**引用块** → 本文 §3 重定义 | messages.js :364-382（forwardBubble） |
| 消息 origin（agent/user 来源标注） | ❌ 服务端 messages 表无 origin 字段 → 「Agent 代发」chip 仅 session 内存态，**reload 即丢**；多端不一致 | messages 表 DDL 无 origin 列 |
| 删除好友/拉黑 UI | ❌ 服务端 API 已有（DELETE/block/unblock），UI spec R8 一期裁定「不提供删除入口」→ 作者本次「按微信的方式来」后需补 | 前端 grep removeFriend 零命中 |

**一句话**：缺的不是链路，是**最后一米**——①SendFriendMessage 工具（agent 发消息能力的关键缺口）②消息 origin 持久化（代发标注的存活）③转发改引用块 ④删除好友/拉黑的微信式 UI 入口。

---

## 1 · （a）微信式好友流程 gap 分析

作者裁定「按微信的方式来就行」= 以微信常识补齐交互，不再逐项终审。逐条对照（✅=已实现，❌=缺失需补，➖=一期既定不做维持）：

| 微信常识项 | 现状 | 结论 |
|---|---|---|
| 搜索加好友（号/邮箱） | ✅ lookup 单字段二合一（默认=邮箱，自定义后=自定义号） | 无 gap（§4） |
| 申请附言（验证消息，可选一句） | ✅ API `note?` + UI verify-note 输入 ≤50 字 | 无 gap |
| 通过 / 拒绝 | ✅ accept/decline 双端 | 无 gap |
| 通过后自动建会话+系统提示 | ✅ accept 返回 conversationId + 「你们已成为好友」 | 无 gap |
| 被拒后重新申请 | ⚠️ 现状=declined 48h 冷却（服务端强制，store.rs:1829 + friends.rs:327 `cooldown_48h`）；**作者裁定：移除冷却，拒绝后可随时再申请** | **❌ 需改**：随 #290 服务端批移除（§2.3 附加裁定） |
| 删除好友 | ⚠️ 服务端 DELETE 已有（会话保留只读，重加恢复）；**UI 无入口**（UI spec §3.4 R8 一期裁定不提供） | **❌ 需补**：见 §1.1 |
| 拉黑 | ⚠️ 服务端 block/unblock 已有（被拉黑请求静默吞，防探测）；**UI 无入口** | **❌ 需补**：见 §1.2 |
| 申请被拒/通过的状态回显 | ✅ incoming/outgoing 分组状态字（等待验证/已同意/已拒绝） | 无 gap |
| 好友资料页（点头像看昵称/号/删/拉黑） | ❌ 无 | **❌ 需补**（§1.1 的自然载体） |
| 在线状态 | ➖ 一期裁定不做（UI spec §3.1 R4，presence 需拓扑定案） | 维持 |
| 已读回执 | ➖ 一期裁定不做（cursor 本地未读） | 维持 |
| 群聊 / 撤回 / 语音 | ➖ 二期展望（arch §11） | 维持 |

### 1.1 删除好友（微信式）

- **入口**：好友行右键菜单「删除好友」+（P2 可选）资料页按钮。一期最小形态=**列表行右键菜单**（与 §3 转发右键菜单同批，交互体系一致）。
- **确认**：微信式二次确认弹窗（「删除后，将解除好友关系，聊天记录保留」）。弹窗形态遵循铁律：无 overlay 暗化、毛玻璃面板。
- **行为**：DELETE /api/friends/{id}（已有）→ 好友列表移除、会话保留**只读**、输入框禁用+系统提示条（messages.js 既有非好友拦截逻辑，UI spec §3.3 已规格化——现状代码待核对是否已实现该拦截，实现时按 UI spec A13 补）。
- **对方侧**：无通知（微信式静默），对方好友列表下次刷新自然消失；对方发消息→服务端 403（accepted 强制）。

### 1.2 拉黑（微信式）

- **入口**：好友行右键菜单「加入黑名单」。
- **确认**：二次确认弹窗（「将不再收到对方的消息与好友请求」）。
- **行为**：POST /api/friends/{id}/block（已有）→ 同删除的好友列表/会话表现，但好友列表中保留该行并加「已拉黑」状态字（微信式：黑名单好友在列表可见但置灰）；右键菜单出现「移出黑名单」（unblock 已有）。
- **防探测语义**：被拉黑方发请求=200 静默吞（服务端既有），UI 无感知——零改动。

**微信式补齐量级**：纯前端（右键菜单组件复用 §3 的同一套 context-menu），后端零改动（API 全在）。

---

## 2 · （b）agent 对好友发送消息——机制设计

### 2.1 现状与缺口

FriendService.sendAsAgent（auto/ask/off + 双层限速 + 超限降级 ask）**已实现且冻结设计已终审**（arch §7，用户裁定 auto 默认）。唯一缺口=**LLM 可调用的工具壳**：arch §7.1 设计了 `SendFriendMessage(to, message)`，FriendService.scala 注释自称「SendFriendMessage 工具入口」，但 core/tools/ 下从未实现注册——设计冻结后客户端批次只交付了 Service 半区。

### 2.2 关键决策：专用内置工具 vs Mail 语义扩展

**推荐：按 arch §7.1 冻结设计实现专用内置工具 `SendFriendMessage`**，否决 Mail 语义扩展。理由：

| 维度 | 专用工具（推荐） | Mail 语义扩展（否决） |
|---|---|---|
| 语义纯度 | 好友消息=用户社交面，工具 description 天然承载用途约束（arch §7.1 已写好：「仅限已建立的好友关系，以用户身份送达」） | Mail 是 agent 编排通道（Manager/成员/queue/immediate），混入社交面会串权限模型——Mail 有 team 路由/gate/空闲投递一整套语义，好友消息一条都不该继承 |
| 权限与限速 | 走 FriendService.sendAsAgent 既有三档+双层限速（冻结裁定），choke point 唯一 | 需要在 Mail 投递层另插一套限速/确认，两套防线难对账 |
| 来源标注 | 工具调用天然打 agent 来源标（§2.4） | Mail 已有 sender 标注体系，复用会让「好友会话里的 Agent 代发」与「agent 间 Mail」语义纠缠 |
| token/description 成本 | 一个小工具 | Mail description 膨胀 |
| 一致性 | 与冻结设计零偏离（U2 终审通过的就是专用工具） | 推翻冻结裁定，需重新终审 |

实现要点（对齐 arch §7.1 + 现有代码事实）：

1. **注册**：`core/tools/FriendMessageTool.scala` 新建，ToolRegistry.registerTool；schema：`to`（好友昵称或 neblinkId，客户端本地解析）+ `message`（≤4000 字符）。
2. **to 解析层**（arch §7.1 遗留细节，实现时定案）：工具层拿 FriendService.refreshFriends() 快照 → 按 neblinkId 精确 → 昵称精确 → 昵称唯一前缀 三级解析（照 RemoteExecutor.resolvePeer 模式）；多命中/零命中返回候选列表让 agent 重试。
3. **发送**：解析命中 → friendService.sendAsAgent(friendUserId, body)——三档权限/限速/降级全部既有，工具层零重复实现。
4. **返回**：「已发送给 {好友名}（{时间}）」/ 结构化错误（非好友/被限速/用户拒绝/功能关闭/模式 off）。

### 2.3 工具授权策略（已裁定：作者特批）

**作者裁定（2026-08-28）：SendFriendMessage 只授权给 Nebula**——进全局 `agents/Nebula/agent.json` 的 `tools` 显式声明，其他 agent 一律不注入（比原推荐 A 更收紧：授权面收敛为单点）。实施接线走既有「agent.json tools 显式声明制（默认不注入）」体系，零新机制。

**附加裁定：declined 48h 冷却「不做」**——已核实服务端**已实现**（store.rs:1829-1837 强制检查 + friends.rs:327-328 `cooldown_48h` 429）。按裁定移除：store.rs 冷却检查块 + `FriendRequestError::Cooldown` 分支与枚举变体删除（declined → 直接允许重发，走既有 UPDATE 重置路径；declined_at 列保留无需 DDL 变更）。随 #290 服务端批（R2 origin 列同仓同部署窗口）一并交付。

### 2.4 消息来源标注=agent 身份（origin 持久化）

作者要点「消息来源标注=agent 身份」。现状：sendAsAgent 与 sendAsUser 走同一端点，服务端 messages 表**无 origin 字段**——「Agent 代发」chip 是前端 session 内存态（Set），reload 即丢、多端不一致。补法二选一：

- **A. 服务端 origin 列（推荐）**：messages 表 `ALTER ADD origin TEXT DEFAULT 'user'`；发送端点加可选 `origin` 字段（客户端 agent 通道传 "agent"，UI 通道不传）；渲染 chip 按 origin。「Agent 代发」标注跨 reload/多设备存活；Rust 侧改动 ~0.5 人日。服务端本就可见明文（一期无 E2E，已裁定），无新增暴露面。**对端不感知**：origin 仅本端渲染用，对方看到的仍是用户身份（arch §7.1 冻结语义「以主人身份送达」，维持）。
- **B. 客户端 localStorage**：按 messageId 存 origin。零服务端改动，但 reload 后本地补拉历史无法回填（补拉不经过本地存储的旧消息）、多端必不一致——**半吊子，不推荐**。

---

## 3 · （c）用户转发消息给 agent——引用块形态

### 3.1 方向变更声明

arch §8 冻结设计=文本前缀包装（`[来自好友 X 的转发消息 | 日期]` + 正文，走用户输入管线）。作者本次裁定**转发走 #303 全局引用能力的引用块形态**——从「文本拼接」升级为「结构化引用」。arch §8 的**三条本质裁定全部保留**：纯客户端行为（不经服务端，防 prompt injection 攻击面）、走用户消息角色（「我想让你看这个」）、单向语义（agent 回复不回流好友窗，回复在主聊天区）。

### 3.2 引用模型对齐（对 global-reference-spec 的增量）

引用数据模型（global-reference-spec §2）现有四类 refType（file/document/task/html-element）。好友消息转发需**新增一类**：

- **`refType: "friend-message"`**（聊天消息引用）：
  - `source`：`{ conversationId, messageId, friendName, friendNeblinkId, direction }`（direction=in/out——转发自己发出的消息同样合法）
  - `anchor`：无（单条消息即原子对象）
  - `content`：`{ preview: 摘要 ≤160 字符, fullText: 消息全文（≤4000 字符，全量随载荷） }`
  - 稳定 `id`：`ref:fm:{messageId}`
- **四层表示对齐**（global-reference-spec §2.3 同构）：JS 态进 `pendingAttachments` → 输入框渲染定高引用块（固定尺寸不随内容变长，08-20 精简裁定 + #303 铁律）→ 发送时 wire 载荷带 ref 对象 → 注入 agent 的文本层格式：

  ```
  [引用 · 好友消息 | 来自 {friendName}({friendNeblinkId}) | {日期}]
  {fullText}
  ```

  （文本层与 arch §8 旧格式几乎同形——**agent 侧体验零变化**，变的是前端结构化承载与渲染。）
- **单源管理**：refType 枚举与渲染归 global-reference-spec 单源维护——本文批准后需 design-engineer 在该 spec 登记第 5 类（登记动作 S 级，不阻塞实施）。

### 3.3 交互设计

- **入口（作者原话「消息上下文菜单/长按」）**：
  - **桌面主入口=消息气泡右键菜单**：「转发给 agent」（新增）；同菜单第二组=「删除好友」/「加入黑名单」（§1，对好友头像右键时）——右键菜单组件一批交付两用。
  - **长按**：触屏/触控板语义，桌面客户端一期以右键覆盖；触屏形态留待桌面触屏适配批（不阻塞）。
  - **既有入口保留**：hover 操作钮 + header「转发最近一条对方消息」（messages.js 已实现）——两个旧入口的行为从文本拼接**切换为**引用块注入，三入口同动作。
- **输入框引用块**：点转发 → 当前活跃会话输入框插入引用块（**不自动发送**——引用块进 pendingAttachments，用户可加一句话再发；这是引用形态相对旧「直接注入」的交互升级，转发即发送改为「转发入框」）。
  - **已裁定（R3=②）**：入框待发为最终形态；旧「点击即发」否决、不留兼容路径（三入口统一切换为入框）。
- **「已转发给 agent」chip**：保留，触发时机改为引用块实际随消息发出时（pendingAttachments 阶段不打标）；chip 持久化问题随 §2.4 origin 一并解决（chip 存活现状=session 级，维持现状不扩大范围，P2 再议持久化）。
- **单向语义**（冻结保留）：agent 回复只出现在主聊天区；好友窗无回流；无自动链路。转发目标=当前活跃会话（arch §8 裁定维持，会话选择器 P2）。

### 3.4 视觉

- 输入框引用块：定高（global-reference-spec §3.5 规格，含展开查看全文）、`--glass-etched-bg` + 1px etched border + 圆角 6-8px、header「来自 {好友名}」+ 角标 `{日期}`、正文摘要 2 行截断——零新色 token。
- 消息内引用块渲染：global-reference-spec §4 既有块级卡片规格，`friend-message` 图标用 lucide `message-circle`（既有体系）。

---

## 4 · （d）搜索=邮箱或 nebflow 号——结论：零改动

作者表述「加好友搜索=邮箱或 nebflow 号」与 [U3 2026-08-18] 终审裁定**完全一致**，且已实现：

- lookup 单字段精确匹配 `neblink_id`；**默认值=注册邮箱**（落库 lazy backfill，friends.rs :45/:50 实证）——搜邮箱=搜 NL 号（二合一）；
- 用户自定义 NL 号后（PUT /api/users/me/neblink-id，字母+数字 3-32、唯一检测、大小写不敏感），**邮箱退出查询键**（隐私回升，防通讯录撞库）——arch §4 冻结语义；
- 边界（维持，实现侧已知）：存量无邮箱账号（GitHub 时代）neblink_id=NULL，不可被搜到，自定义后获得号；防枚举=精确匹配+20/min 限速+无模糊。

**无需任何改动。**

---

## 5 · 作者裁定结果（2026-08-28 18:5x，全部落定）

| # | 决策点 | 作者裁定 |
|---|---|---|
| R1 | SendFriendMessage 工具授权（§2.3） | **特批：只授权 Nebula**（agents/Nebula/agent.json tools 声明制，其他 agent 一律不注入）；附加：**declined 48h 冷却移除**（拒绝后可随时再申请；已核实服务端已实现，随服务端批删） |
| R2 | 消息 origin 持久化落点（§2.4） | **A**——服务端 origin 列（~0.5 人日，跨 reload/多端一致） |
| R3 | 转发交互形态（§3.3） | **②**——引用块入框待发（可附言，与 #303 交互一致）；点击即发否决不留 |
| R4 | 删除好友/拉黑 UI 本批（§1） | **①**——一并做（右键菜单同组件） |
| R5 | 「已转发」chip 持久化 | **维持 session 级**，P2 再议 |

## 6 · 实施量级评估

| 域 | 内容 | 人日 |
|---|---|---|
| 后端（Scala 主仓） | FriendMessageTool（to 三级解析+FriendService 桥接+schema/description+授权接线）+ 单测 | 1-1.5 |
| 服务端（Rust） | messages origin 列+发送端点字段+读取透出（R2=A）+ declined 48h 冷却移除（§2.3 附加裁定，~10 行+测试） | 0.5 |
| 前端 | 右键菜单组件+转发引用块（pendingAttachments/输入块/注入文本层/三入口切换）+删除/拉黑入口（R4=①）+origin chip | 2-3 |
| 联调与验收 | 隔离实例双账号全链（申请→通过→agent 发消息→转发引用块→删除/拉黑） | 0.5-1 |
| **合计** | | **4-6 人日**（R4=②则 -1 人日） |

**验收锚点**（继承 arch §13 + UI spec §9 既有断言，新增）：① agent 经工具发消息→对方收到、我方带「Agent 代发」chip、reload 后 chip 仍在（R2=A）② 三档 mode 行为（off 拒绝/ask 确认/auto 限速降级）③ 转发→输入框引用块→发出→agent 收到 `[引用 · 好友消息 | 来自 X]` 文本层 ④ 右键删除好友→列表移除会话只读 ⑤ 拉黑→对方请求静默吞（服务端断言既有）⑥ declined 后立即重申→成功（48h 冷却移除断言）。

## 7 · 参考链接

1. friends-messaging-arch.md v1.2（后端冻结设计，§7 工具/§8 转发为本文§2/§3 的上位）
2. friends-messaging-spec.md v1.2（UI 冻结设计）
3. 20260825_global-reference-spec.md（引用模型，§2 数据模型/§3.5 输入框/§4 渲染）
4. 现状代码：src/main/scala/nebflow/neblink/FriendService.scala · src/main/scala/nebflow/gateway/RestApiRoutes.scala:996-1089 · src/main/resources/web/js/{contacts,messages,friendsApi}.js · ~/.nebflow/projects/neblink-server/src/friends.rs

---
*版本日志：v1.0（2026-08-28）初稿——现状全量走查 + 作者 18:31 方向补充四块（gap/工具/引用块/搜索）+ 五项待裁定。v1.1（2026-08-28 18:5x）作者五项裁定落定转定稿——R1 特批 Nebula 专属 + 48h 冷却移除（Manager 核实已实现：store.rs:1829/friends.rs:327，随服务端批删）/ R2=A / R3=② / R4=① / R5 维持。*
