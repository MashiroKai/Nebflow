# Nebflow 视觉支持全链路审计

日期：2026-08-14 · 审计基线：archive/scala @ 71def292（已含 B3 判定反转）· 审计人：Frontend（纯代码审读 + provider 文档核对）

> 用户问题：视觉是模型自带能力，Nebflow 现有设计能不能很好支持视觉模型（Kimi、GLM-5V-Turbo）？
> 结论速览：**链路主骨架是健康的**——base64 data URI 的 payload 构造对 Kimi/GLM 是正确选择；B3 后未标注模型默认按有视觉处理、失败自动降级并持久化。主要缺口在**会话恢复后图片在前端不可见**、**Mail/Delegate 无法结构化传图**、**日志/base64 体积管理**三处。

---

## 一、链路图

```
前端 input.js                      WebSocketRoutes.scala                AgentActor                  LLM Adapter
─────────────                      ─────────────────────                ──────────                  ──────────
addFileAttachment                  attachments[] (WS JSON)              state.messages              OpenAI-compat:
 ├ image/*: compressImage          ├ image → 存盘                       (List[Message],             image_url:
 │  → JPEG ≤1920px q0.8, ≤10MB     │   ~/.nebflow/uploads/<sid>/        ContentBlock.Image          data:<mime>;base64,…
 │  → {data: base64, preview,      │   + ContentBlock.Image(data,mime)  常驻内存，每轮重发)          Anthropic 原生:
 │     name, mimeType}             │   + ContentBlock.Text(             state → PreSendChecker ──▶ image/base64 block
 └ 非图片: base64 或本地文件匹配    │     "[用户附加图片: <path>]")            │
     (hash+size, Spotlight)        └ 非图片 → 文本路径引用                    │ effectiveVision =
                                                                    │   config.vision (config.json inline)
 Record: ui.json                   appendUiMessages:                  │   > models.json (ModelRegistry)
 attachments 只记 {name,type,path}  attachments=[{name,type,path}]    │   > 默认 true (B3)
 （不含 base64）                                                       │   && runtimeOverride (EmptyCompletionTracker)
                                                                      ▼
                                                              !vision → stripImages:
                                                              Image → Text("[image omitted:
                                                              model does not support vision]")
                                                              （伴随的 path 文本块保留）
```

生命周期支线：

```
会话持久化   ui.json: attachments={name,type,path} → 前端恢复无 preview → 图片不可见（缺口 G1）
压缩        CompactUtils.stripImages → 摘要子 agent 只见 "[image: mediaType]"；
            HistoryArchiver 归档可读文本，图片同样占位化；TokenEstimator 1500 tok/图
agent 间    Mail/Delegate 仅文本参数 → 图片不能结构化转发；
            唯一通道 = "[用户附加图片: path]" 文本 → 接收方 ReadTool 读图 → imageBlocks 注入
日志        LlmLogWriter 把 Image block 的完整 base64 写进 LLM 日志（缺口 G4）
清理        删除会话时 uploads/<sid>/ 一并删除；NebflowBackup 跳过 uploads/
```

---

## 二、分项审计

### 1. 前端：图片上传 → 消息结构

| 环节 | 现状（input.js:346-440） |
|---|---|
| 入口 | 粘贴 / 附件按钮 / 拖拽 |
| 预处理 | `compressImage`：canvas 重编码 **JPEG**、最长边 1920、质量 0.8；>10MB 拒绝；失败回退原图 base64 |
| 附件结构 | `{type:'image', mimeType:'image/jpeg', data: base64, name, preview: dataURL}` |
| 多图 | 支持，`pendingAttachments` 数组逐个 push，WS 一次发出 `attachments[]` |
| 竞态防护 | `pendingAttCount` 计数器防止压缩未完成就发送 |
| 渲染 | 活体消息用 preview dataURL 渲 `<img.att-img>`；`img.draggable=false` 已防拖拽卡死 |

**发现**：
- F1（小，已修）：前端把所有图重编码为 JPEG，但 `name` 保留原名（如 `shot.png`）——后端按 MIME 算出 `ext` 却没用上（见 §4 已修项）。
- F2（建议项）：PNG 截图/带透明通道的图被强制转 JPEG，文字边缘有损、透明度丢失；GIF 动图变静态。可考虑"PNG 且 <2MB 时保持 PNG"，但这会增大 3-5 倍请求体，需权衡。

### 2. 后端判定：PreSendChecker vision 判定链

**B3 前（用户今天活案例的来源）**：`ModelCandidate.vision` 默认 **false**（registry.scala:17 字段默认值），未被 inline/models.json 标注的模型一律视为无视觉 → `stripImages` 把 Image 块换成占位文本 `[image omitted: model does not support vision]`。

**B3 后（当前 HEAD 71def292）**：

```
effectiveVision = candidate.vision && runtimeVision.getOrElse(true)

candidate.vision（registry.scala resolveCapabilities, L149-158）:
  config.json inline vision          ← 服务商配置里模型行的 vision 勾选
  > models.json (ModelRegistry)      ← 运行时降级的持久化写回
  > 默认 true                        ← B3 Phase 1：乐观默认

runtimeVision（EmptyCompletionTracker）:
  降级信号①：同一模型空回复 ≥2 次且请求含图
  降级信号②：provider 报错信息命中 "image"/"multimodal"/"not support"
  → setVisionOverrideFalse + 写回 models.json（重启不丢）
  解除条件：仅"含图成功完成"才解除（防振荡），无图成功不算数
```

**strip 行为细节**（interface.scala:24-36）：
- 只替换 `ContentBlock.Image` → `Text("[image omitted: model does not support vision]")`
- **伴随的 `[用户附加图片: <path>]` 文本块保留**——非视觉模型仍知道有图、拿到路径，可用 Read 工具（但 Read 注入的 imageBlocks 下一轮又会被同一判定 strip，行为自洽）
- strip 作用于发送副本，`state.messages` 原样保留
- PostEmptyRecovery：流式空回复且含图且非视觉 → strip 后**原地重试同一候选**再走 failover；空回复+含图+非视觉的错误分类改写为 `CapabilityMismatch`（永久，跳过该 provider）
- 占位文本只进 LLM 上下文，不进前端 UI；用户今天"会话里看到"该文本，最可能来自 LLM 日志查看器或归档转录

### 3. Provider payload 构造（核心）

**Nebflow 当前两种格式**（均内联 base64，不使用公网 URL）：

| 协议 | 格式 | 代码 |
|---|---|---|
| OpenAI 兼容 | `{"type":"image_url","image_url":{"url":"data:<mime>;base64,<data>"}}` content 为数组 | OpenAiAdapter.scala:117-122 |
| Anthropic 原生 | `{"type":"image","source":{"type":"base64","media_type","data"}}` | AnthropicAdapter.scala:42-50 |

**各 provider 兼容性核对**（代码推断 + 官方文档）：

| Provider | 视觉模型 | base64 data URI | 公网 URL | 结论 |
|---|---|---|---|---|
| Kimi / Moonshot | kimi-k3、kimi-k2.5/2.6、moonshot-v1-*-vision-preview | ✅ **仅支持 base64 / 文件 ID**，URL 明确不支持 | ❌ | **正确**——Nebflow 恰好用对了唯一受支持的方式；content 数组格式符合要求（Kimi 明确要求 content 为 array，Nebflow 含图时走数组分支 ✅）；注意 SVG 被拒（Nebflow 前端压缩后一律转 JPEG，天然规避） |
| 智谱 GLM | glm-4v 系列 / glm-4.5v / glm-4.6v（GLM-5V-Turbo 同族接口） | ✅ | ✅ | **正确**，OpenAI 兼容 image_url，base64 可用 |
| DeepSeek | 无视觉模型 | — | — | 发图预期 400/空回复 → B3 信号②或①自动降级写回 models.json，之后自动 strip，行为闭环 |
| USTC 网关 | 取决于后端模型 | 网关透传（OpenAI 兼容） | — | 网关本身不拦截 data URI；正确性取决于所选后端模型是否视觉 |
| Anthropic | Claude 全系 | ✅（原生 base64 block） | n/a | 正确 |

**payload 层面无需改动**。唯一注意点：图片在每个后续 turn 都随 `state.messages` 全量重发（Anthropic 式上下文设计），多图长会话的请求体体积会累积——靠 TokenEstimator（1500 tok/图）触发压缩缓解。

### 4. 图片生命周期

| 阶段 | 现状 | 评价 |
|---|---|---|
| 存盘 | `~/.nebflow/uploads/<sessionId>/<nanoTime>_<name>`，路径同时作为文本块给 LLM | ✅ 双通道设计（视觉 + 可转发路径）是好的 |
| 会话持久化 | ui.json 只记 `{name, type, path}`，不含 base64 | ✅ 合理（防 ui.json 膨胀） |
| **前端恢复** | persistence.js 两条恢复路径只认 `att.preview` dataURL；localStorage sanitize 会把 preview 剥掉；ui.json 的 `path` 前端**没有使用**，也没有任何 HTTP 路由服务 uploads 目录 | ❌ **缺口 G1**：刷新/切换会话后图片变成 `[file: name]` 文本标签，图片视觉丢失 |
| LLM 侧多轮 | Image 块常驻 `state.messages` 全量重发 | ⚠️ 已知成本，1500 tok/图估算 |
| 压缩 | 摘要子 agent 收到的是 `[image: mediaType]` 占位（CompactUtils.stripImages）——**摘要丢失视觉内容**；压缩后上下文不再有图 | ⚠️ 缺口 G5（建议项）：含图会话压缩时摘要质量下降 |
| 归档 | HistoryArchiver 文本转录中图片占位 `[image: mediaType]` | ✅ 合理 |
| Mail/Delegate | message/prompt 仅 string 参数，无附件通道 | ⚠️ 缺口 G3：只能靠 path 文本 + ReadTool 间接传图（设计上有意为之："Give LLM both the image and the path (forwardable via Mail)"，WebSocketRoutes.scala:2819 注释） |
| ReadTool | 图片扩展名→MIME，≤10MB 直接 base64 注入 imageBlocks，**不压缩** | ⚠️ 大图（如 8MB PNG）原样进请求体，可能超 provider 单图限制（GLM 单图 5MB） |
| 清理 | 删会话连带删 uploads/<sid>/；备份跳过 uploads/ | ✅ |

---

## 三、缺口清单（按严重度）

| # | 缺口 | 严重度 | 位置 |
|---|---|---|---|
| G1 | 会话恢复后历史消息中的图片在前端不可见（ui.json 有 path 但无服务路由、前端不消费） | **中**（用户可感知的功能残缺） | persistence.js:230-249/574+ + 缺 uploads 静态路由 |
| G2 | 保存图片扩展名与内容不符（前端重编码 JPEG 但沿用原文件名 .png；后端算出的 ext 未使用）→ ReadTool 按扩展名判 MIME 会错 | 小（**本批次已修**） | WebSocketRoutes.scala:2807-2813 |
| G3 | Mail/Delegate 无法结构化传图，只能文本路径 + 对方 Read | 低（有变通通道，体验绕） | MailTool.scala / DelegateTool |
| G4 | LlmLogWriter 把图片完整 base64 写进 LLM 日志且每轮重复 | 低（日志体积/隐私） | LlmLogWriter.scala:260-268 |
| G5 | 压缩摘要丢失视觉内容（摘要模型收到占位文本） | 低（边缘场景） | CompactUtils.scala:11-20 |
| G6 | ReadTool 大图不压缩（≤10MB 原样 base64），可能超 provider 单图限制 | 低 | ReadTool.scala:15 |
| F2 | 前端强制 JPEG 重编码：PNG 文字截图有损、透明度/动图丢失 | 低（设计权衡） | input.js:346-370 |

## 四、修复建议（大问题列方案，未实施）

1. **G1 恢复图片可见**（建议独立任务，前端+后端小联动）：
   - 后端：加 `GET /uploads/<sid>/<file>` 静态路由（复用 voice-models 同款防穿越模式，~15 行）
   - 前端：恢复路径渲 `att.type==='image' && att.path` 时用 `<img src="/uploads/<sid>/<file>">`；localStorage sanitize 可保留 path 字段
   - 收益：刷新/切会话后图片历史完整可见，且不占 localStorage
2. **G3 agent 间传图**：Mail/Delegate 增可选 `attachments: [path]` 参数，接收侧注入 imageBlocks（复用 ReadTool 的读图逻辑）；或不加参数、在系统提示词里强化"图片用 [用户附加图片: path] + Read"的既有约定
3. **G4 日志**：Image block 写日志时 data 替换为 `"<base64 omitted, N bytes>"`
4. **G5 压缩**：含图会话选摘要候选时优先 vision=true 的模型，且不 strip（让摘要能描述图内容）
5. **G6 ReadTool**：>2MB 的图片先降采样（后端 Java ImageIO）再注入

## 五、本批次已修（worktree feat/vision-audit）

- **G2**：WebSocketRoutes.scala 图片存盘改用 MIME 派生扩展名（`nanoTime_stem.jpg`），原文件名扩展只作 stem。消除"JPEG 字节存成 .png"导致的 ReadTool MIME 误判。`sbt compile` 验证。

## 六、给 Backend B3 实测的观察点建议

- Kimi：直接发截图即可（base64 路径必通）；观察 GLM 单图大小限制（>5MB 场景走 ReadTool 可能触发）
- DeepSeek 发图 → 确认降级信号生效（空回复×2 或报错命中）且 models.json 写回 `vision: false`
- 降级后再发图 → 确认模型回复中提到 `[image omitted]` 占位但仍有 `[用户附加图片: path]` 可用
- 切换/刷新会话 → 复现 G1（图片变文本标签），供排期参考

---

Sources:
- Kimi 视觉文档（base64-only，content 必须为数组，SVG 拒绝）: https://platform.moonshot.cn/docs/guide/use-kimi-vision-model
- GLM-4V 系列（image_url 支持 URL 与 base64）: https://docs.bigmodel.cn/cn/guide/models/vlm/ 及 GLM-4V-Flash 文档页
