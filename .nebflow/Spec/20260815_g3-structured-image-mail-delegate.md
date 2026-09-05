# G3 设计方案：Mail/Delegate 结构化传图通道

日期：2026-08-15 03:00（闲时批处理，spec-only） · 基线：archive/scala @ 85053fa7
来源：视觉支持全链路审计缺口 G3（`20260814_vision-support-audit.md` :123 / :147）
状态：**已实施**（2026-08-15 09:37，用户批准按推荐方案 A 收窄版 + D1-D9 全部倾向值）。实施分支 `feat/g3-structured-images`，commit **8a39e34b**（worktree /tmp/nb-g3-images，基于 archive/scala@ff6063a8，G6 已合并），含 ImageInject 抽取、三工具 `images` 参数、queue 路径持久化（D6）、ImageAttachSpec 23 项测试；sbt 全量 572 通过 0 失败。待 Manager 审查合并（与 G5 同批）。设计文档正文保留原样供追溯；实施细节偏差：D2 远程路径提示落在 not-found 错误上叠加（PathUtil.isAbsolute 刻意接受 Windows 盘符，故不能在绝对性检查处拦截——Windows 本机盘符路径仍可用）。
性质：设计文档 + 实施记录。与并行的 G4/G5/G6（worktree `/private/tmp/nb-vision-gaps`）零文件交集；G6 的 ReadTool 压缩是本方案的**硬依赖**（见 §4.6）。

---

## 1. 背景

现状：Mail/Delegate 的 `message`/`prompt` 仅 string 参数，无附件通道。agent 间传图只能靠"path 文本 + 接收方自己 Read"的变通——这是设计上有意为之的双通道设计（`WebSocketRoutes.scala:2825` 注释 "Give LLM both the image and the path (forwardable via Mail)"）。B3（dd734e06）后 vision 链路稳定，变通能用但体验绕：每张图多一轮工具调用、依赖两层 LLM 行为自觉（发送方抄路径 + 接收方主动 Read）、tools 白名单受限的接收方可能根本没有 Read。

### 1.1 关键新发现：blocks 通道已全链路就绪（方案 A 成本远低于审计时预估）

审计时（2026-08-14）以为 G3 需要动 actor 协议。逐行核查后发现**结构化注入的基础设施已经存在**，只是 Mail/Delegate 没有使用：

| 环节 | 证据 | 状态 |
|---|---|---|
| `AgentCommand.UserInput.blocks: Option[List[ContentBlock]]` | protocol.scala:22 | ✅ 已有，默认 None |
| `AgentCommand.ImmediateInput.blocks` | protocol.scala:40 | ✅ 已有 |
| blocks → 接收方 `state.messages` | AgentActor.scala:296-299：`Some(bl) => Message(MessageRole.User, Right(bl))`，直接进消息历史 | ✅ 已通 |
| `ImmediateInput` 转发 blocks → `UserInput` | AgentActor.scala:581-583 | ✅ 已通 |
| 消息 ContentBlocks 持久化 | SessionStore.scala:430 `msgs.asJson`（Right(blocks) 完整序列化，:1095 佐证） | ✅ 已通 |
| 非 vision 接收方降级 | interface.scala:24-36 stripImages：Image→占位文本，随附 path 文本保留（审计 §2.2） | ✅ 已自洽 |
| Mail 填充 blocks | MailTool.scala:925-932 `ImmediateInput(message, ...)` 未传 | ❌ 缺口 1 |
| ask/fork 模式填充 | MailTool.scala:318 `UserInput(question, ...)` 未传 | ❌ 缺口 2 |
| queue 模式：`MailQueueItem` 无 blocks 字段 | MailQueueStore.scala:22-30；AgentActor.scala:590-599 投递时 blocks=None | ❌ 缺口 3 |
| Delegate/SubTask 填充 | DelegateTool.scala:426/:576、SubTaskTool.scala:283 `UserInput(prompt, ...)` 未传 | ❌ 缺口 4 |

即：**接收侧零改动**（AgentActor/持久化/vision 降级全部现成），工作量集中在发送侧 4 个填充点 + 一个共享的图片解析函数。

---

## 2. 现状链路（变通通道，供对比）

```
发送方 agent                       接收方 agent
────────────                       ────────────
用户消息里有
[用户附加图片: /uploads/sid/x.jpg]
+ Image block（发送方自己能看图）
        │
        └─ Mail(message="…见 /uploads/sid/x.jpg")   ← 纯文本，路径靠 LLM 抄写
                │
                └─ 接收方 LLM 自觉调用 Read(/uploads/sid/x.jpg)
                        │
                        └─ ReadTool.extractImages → imageBlocks
                                │
                                └─ 接收方 vision=true 才真正看到图
                                  （vision=false 则 strip 成占位，路径仍可用）
```

痛点：2 轮 LLM + 1 次工具调用才能等效 1 次"看到图"；路径抄写可错；接收方可能无 Read 工具；queue 模式下这轮 Read 还要等接收方上一个任务完成。

---

## 3. 方案对比

### 方案 A：Mail/Delegate 增可选 `attachments` 参数（发送侧解析 + 双通道注入）

发送时由**工具层（确定性代码，非 LLM）**读图、构建 blocks，接收方零改动。

```
Mail(address, message, attachments=["/uploads/sid/x.jpg"])
        │ MailTool 执行时：
        ├─ 对每个 path 复用 ReadTool 读图守卫 + G6 压缩 → ContentBlock.Image
        ├─ 同时注入 ContentBlock.Text("[Mail 附件图片: <path>]")   ← 双通道，对齐 WebSocketRoutes
        │    （路径文本保留 → 非 vision 接收方仍知道有图；接收方还能继续转发路径）
        └─ ImmediateInput(message, blocks=[Text(提示), Image, Text(path), …])
                │ 接收侧全现成：
                └─ Message(User, Right(blocks)) → vision ? 看图 : strip 成占位
```

- 优点：确定性管线，一次投递即达；接收侧零改动；错误在 Mail 调用点即时反馈（设计原则：descriptive errors at the point of action）；与用户上传图片的注入格式完全同构（同一 Message 结构）。
- 缺点：新增工具参数（认知面 +1）；queue 模式要扩 MailQueueItem（涉及持久化格式）；发送方上下文里图的 base64 不存在（sender 不必先 Read，省 token——这其实是优点）。
- 约束：v1 只接受图片扩展名（复用 ReadTool.imageExtensions 白名单），非图片附件报错引导走 path 文本（见决策点 D4）。

### 方案 B：不加参数，纯 prompt 强化既有约定（零代码）

在 Mail 工具描述 + agent 系统提示词里写明："转述图片时，把 `[用户附加图片: path]` 原样抄进 message，并告知对方用 Read 查看"。

- 优点：零代码、零 schema 变化、立即可用。
- 缺点：
  1. 可靠性押在**两层 LLM 行为**上（发送方抄对路径 + 接收方自觉 Read），每张图多一整轮工具调用（延迟 + token）；
  2. 接收方 tools 白名单若不含 Read 则**断链**（团队 worker 常见受限配置）；
  3. 接收方 vision=true 时仍要先 Read 才看到——比方案 A 多一步；
  4. prompt 约定无执行反馈，违反"结构化结果优先于文本约定"的工具设计原则。
- 适用场景：作为方案 A 落地前的过渡，或永久兜底（A 不覆盖的边角，如 SubTask v1）。

### 方案 C：消息文本内联引用语法（第三案，本报告补充）

不加参数，MailTool 对 message 文本做后处理：扫描 `@img:/abs/path` 形态的 token，自动提升为 Image block（原 token 替换为 path 文本）。

- 优点：schema 零变化；LLM 表达直觉（"提到图就传图"）。
- 缺点：
  1. **魔法语法误触发**：agent 讨论/引用路径 ≠ 想传图（如"请检查 /uploads/x.jpg 的元数据"），歧义无法静态消解；
  2. 仍是文本层约定，只是把"抄路径"变成"记语法"，可靠性问题与 B 同源；
  3. 隐式行为违反"最小惊讶"——同一参数产生两种语义。
- 结论：**不推荐**。列出仅为完整性。

---

## 4. 方案 A 详细设计

### 4.1 Schema 变更（MailTool；DelegateTool 同款）

```json
"attachments": {
  "type": "array",
  "items": { "type": "string" },
  "maxItems": 5,
  "description": "Optional local image file paths (absolute) to send as vision-ready attachments. PNG/JPG/JPEG/GIF/WEBP/BMP only, each ≤10MB before compression. The images are delivered inline (receiver sees them directly); the paths are also included as text for forwarding.",
  "default": []
}
```

参数命名与范围见决策点 D1/D4。

### 4.2 发送侧解析管线（新增共享函数）

```
resolveImageAttachments(paths: List[String]): IO[Either[ToolError, (List[ContentBlock], String)]]
```

对每个 path 依次执行（**全部复用 ReadTool 现有守卫，逐条对齐**）：

| 步骤 | 复用来源 | 失败语义（即时返回给发送方 LLM） |
|---|---|---|
| 绝对路径校验 | ReadTool PathUtil.isAbsolute（ReadTool.scala:102） | `Attachment path must be absolute, got: <p>` |
| 存在性/非目录 | ReadTool.scala:112-116 | `Attachment does not exist: <p>` / `… is a directory` |
| 扩展名白名单 → MIME | ReadTool.imageExtensions（:17-25） | `Unsupported attachment type '<ext>' — images only (PNG/JPG/JPEG/GIF/WEBP/BMP). For other files, reference the path in your message text.` |
| 大小上限 10MB | ReadTool.MAX_IMAGE_BYTES（:15） | `Attachment too large: <name> (<x>MB, limit 10MB)` |
| **G6 压缩**（长边>1920px 或 >2MB 时降采样 JPEG q0.8；压后仍 >5MB 拒绝） | G6 正在 ReadTool 落地（worktree /private/tmp/nb-vision-gaps，常量已提交：COMPRESS_MAX_EDGE/COMPRESS_TRIGGER_BYTES/POST_COMPRESS_MAX_BYTES） | 压后超限 → 引导发送方换图 |
| base64 编码 | ReadTool.extractImages（:209-221） | — |

**硬依赖**：G6 的压缩实现应抽为公共 util（如 `ImageInject.compressIfNeeded`），ReadTool 与 attachments 管线共用。**没有 G6，方案 A 不能上线**——否则 Mail 直传 8MB 原图，接收方连"先压缩"的机会都没有，比现状（接收方 Read 时至少在 G6 后有压缩）更糟，且必超 GLM 单图 5MB 限制。

产出 blocks 序列（对齐 WebSocketRoutes.scala:2826-2827 双通道格式）：

```
[Text("[Mail 附件图片: <path>]"), Image(base64, mime)] × N
```

注入位置：message 文本之后（`Message(User, Right(Text(message) ++ attachmentBlocks))`）。注意 AgentActor.scala:296-299 在 blocks 存在时**丢弃 text 参数**，所以 message 必须编入 blocks 首位，不能只传附件。

### 4.3 三种 delivery 模式 + Delegate/SubTask 的传导矩阵

| 通道 | 载体 | 现状 | 需要的改动 |
|---|---|---|---|
| immediate | `ImmediateInput(text, blocks, …)` | blocks 参数已有 | MailTool.sendMail（:925-932）计算并传入 |
| ask/fork | `UserInput(question, …)` | blocks 参数已有 | forkAndAsk → forkToSession → doFork（:318）透传 |
| queue | `MailQueued(item)` → UserInput | item 无 blocks；AgentActor.scala:590-599 传 None | ① `MailQueueItem` 增 `blocks: Option[List[ContentBlock]] = None`；② AgentActor MailQueued 两处（idle :586 与 processing drain :2001+）改为传 item.blocks；③ Encoder/Decoder 同步（MailQueueStore.scala:32-52，Decoder 手写 downField，新字段 `.orElse(Right(None))` 即可） |
| Delegate | `UserInput(prompt, Some(adapterRef))`（:426 ephemeral / :576 persistent） | blocks 参数已有 | DelegateTool 解析 attachments → 透传 |
| SubTask | `UserInput(prompt, Some(adapterRef))`（SubTaskTool.scala:283） | blocks 参数已有 | 同 Delegate（建议同批，见 D5） |

### 4.4 持久化与体积

- **接收方会话**：blocks 进入 `state.messages` 后由现有 SessionStore 序列化（含 base64，与用户上传图同等待遇），每轮重发、1500 tok/图估算、压缩时占位——全部复用现有生命周期语义，无新增膨胀面（上限 5 图/封）。
- **queue 文件**（`sessions/<sid>/mail-queue.json`）：两个候选——
  - (a) 直接持久化 blocks（含 base64）：重启不丢图，但单文件可能达数 MB（5 图 × 压后 ≤5MB → base64 ×1.33 ≈ 最坏 33MB JSON）；
  - (b) 持久化路径列表，**投递时（drain）重读重压**：文件轻，但 drain 时文件可能已被移动/删除（错误处理：投递为占位文本 + 警告）。
  - 推荐 (b)，理由：uploads 目录随会话删除、跨重启后路径失效率低但非零，而 (a) 的最坏体积已接近不可接受；(b) 失败时降级语义清晰（见 D6）。
- **MailQueueItem 兼容性**：老版本写的新文件含未知字段 `blocks` → 老 Decoder 手写 downField 逐字段读，**天然忽略未知字段**，降级安全；新 Decoder 读老文件 → `.orElse(Right(None))` 默认空。双向兼容已由现有代码风格保证。

### 4.5 权限与安全

- 发送侧解析 = 发送方"自己 Read 后转发"的等价物，**无新增攻击面**：能传的图必然是发送方能 Read 的图；接收方本来也能凭 path 文本自行 Read（现状已如此）。
- 路径守卫完整复用 ReadTool（绝对路径/白名单/大小），不另造一套。
- 边界情况：发送方 tools 白名单可能**不含 Read**（受限 worker）——attachments 是否因此禁用？见决策点 D3（推荐：不禁用，工具层代读不构成权限旁路，理由同上：接收方凭路径文本本就能读）。
- 跨设备：Mail 是同 JVM actor 消息，不存在跨设备投递。唯一相关场景是 path 指向**远程设备**的文件（NebLink device 语义）——发送侧本地读必失败，错误信息建议追加提示："remote device paths are not supported as attachments; copy the file locally (TransferFile) or reference it by path text"。见 D2。

### 4.6 G6 依赖关系（并行任务协调）

- G6（进行中，/private/tmp/nb-vision-gaps）：ReadTool 大图压缩。其压缩逻辑必须抽为公共函数供 G3 复用——**若 G6 以私有函数形式收尾，G3 实施时需先做一次小抽取**（预计 +20 行重构，无行为变化）。
- G3 与 G4（LlmLogWriter base64 占位，已在同 worktree 落地）无冲突；与 G5（压缩摘要 vision 候选）正交——但 G3 落地后 queue 持久化若选 (b) 路径方案，与 G5 无交集。
- 建议实施顺序：G6 合并 → G3（含抽取）。G3 若先行，其压缩点写 `TODO(g6)` 会在合并时产生冲突，不划算。

---

## 5. 决策点清单（需用户拍板）

| # | 决策点 | 选项与倾向 |
|---|---|---|
| D1 | 参数命名 | `attachments`（通用名，为未来非图片留口）vs `images`（语义精确，v1 实际只收图）。**倾向 `images`**——参数名即文档，避免"attachments 却拒绝 PDF"的惊讶；未来要支持文件时再加 `files` 语义更清晰 |
| D2 | 跨设备路径语义 | 拒绝并提示（推荐，Mail 同 JVM 无跨设备场景，远程路径显式报错优于静默失败）vs 尝试通过 RemoteExecutor 远读（复杂度高、引入设备权限维度，v1 不建议） |
| D3 | 发送方无 Read 工具时是否禁用 attachments | 禁用（严格）vs 允许（推荐——工具层代读等价于"转发自己已知的图"，且接收方凭路径本就能读，无旁路） |
| D4 | 非图片附件 | v1 拒绝 + 报错引导 path 文本（推荐）vs 允许并做 base64 文件透传（体积/价值比差，非图片接收方 Read 文本即可） |
| D5 | 覆盖范围 | Mail-only vs Mail+Delegate vs Mail+Delegate+SubTask 同批。**倾向三个同批**——填充点模式完全相同（都是 UserInput/ImmediateInput 的 blocks 位），分批省不了多少，却留下"Delegate 不能传图"的不一致心智 |
| D6 | queue 持久化 | (a) 持久化 blocks（重启不丢、文件大）vs (b) 持久化路径 drain 时重读（推荐，见 §4.4）。若选 (b)，drain 失败的降级语义需确认：占位文本 + `[attachment lost: path]` |
| D7 | 单封上限 | 5 图/封（推荐，对齐前端多图习惯与 token 预算：5×1500=7500 tok）vs 其他数值 |
| D8 | 双通道文本格式 | `[Mail 附件图片: <path>]`（推荐，对齐 `[用户附加图片: …]` 既有格式，链式转发时模式一致）vs 复用 `[用户附加图片: …]`（会混淆来源） |
| D9 | 注入前是否检测接收方 vision | 不检测、统一注入（推荐——PreSendChecker 已统一降级，检测会引入"发送方需知接收方能力"的耦合且与用户上传路径行为不一致）vs 检测并只发文本（省 token 但语义分叉） |

---

## 6. 推荐方案：A（v1 收窄版）

**Mail/Delegate/SubTask 增可选 `images: [绝对路径]` 参数，发送侧解析（复用 ReadTool 守卫 + G6 压缩），双通道注入现有 blocks 字段，接收侧零改动。**

安全 / 复杂度 / 兼容三角：

- **安全**：不新增攻击面。路径守卫逐条复用 ReadTool；内联 base64 等价于接收方自己 Read；非 vision 接收方由现有 stripImages 自洽降级。相比 B（押注 LLM 自觉）和 C（魔法语法），A 是确定性代码路径——工具设计原则"结构化结果优先"。
- **复杂度**：接收侧零改动是决定性因素——审计时预估"要动协议"，实际协议、持久化、降级全部现成。净改动 = 1 个共享解析函数 + 4 个填充点 + MailQueueItem 一个字段，预估 200-300 行含测试（§7）。方案 B 零代码但把复杂度转嫁给每次使用的运行时（多一轮调用 × 每张图 × 每次），G3 场景（agent 间视觉协作，如 UI 验收链路）恰是高频路径。
- **兼容**：可选参数默认空 → 存量调用零影响；MailQueueItem 双向兼容（§4.4）；同 JVM 无版本 skew；文本双通道保留意味着老心智（path 转发）永久可用作兜底。
- **否决 B 的关键论据**：tools 白名单受限的接收方（团队 worker 常态）在 B 下**断链**；A 下照常收图（甚至更需要 A——受限 worker 往往正是被传图验证 UI 的 Frontend/QA）。
- **否决 C 的关键论据**：讨论路径 ≠ 传图的歧义无法静态消解；隐式提升违反最小惊讶。

## 7. 实施切面（供后续派发，本文档不含实现）

1. `ImageInject` 公共 util：从 G6 落地后的 ReadTool 抽取压缩 + 读图守卫（依赖 G6 合并先行）
2. MailTool：schema + `images` 解析 + immediate/ask/queue 三路透传
3. MailQueueStore + AgentActor 两处 MailQueued drain：blocks 传导（按 D6 选型）
4. DelegateTool + SubTaskTool：prompt 侧同款透传
5. 工具描述更新：Mail/Delegate/SubTask description 增 attachments 用法一句话（保持精简）
6. 测试：老调用无参数回归 / 四类错误路径 / queue 重启恢复 / 非 vision 接收方 strip 自洽 / maxItems 拒绝
7. 文档同步：CODEBASE.md 工具章节一句话（若有）

预估：1-1.5 个工作日（含测试；不含 G6 等待）。

## 8. 后续动作

- 本文档 → 用户拍板 D1-D9（多数有明确倾向，可批量确认）
- 拍板后依赖 G6 合并 → 派发 Backend 实施（§7 切面）
- B 方案的 prompt 强化句子可**先行**落入 Mail 工具描述（零风险，与 A 不冲突，作为永久兜底约定）
