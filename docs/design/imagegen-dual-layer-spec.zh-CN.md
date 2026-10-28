---
type: design-note
status: open
---

# 生图能力双层架构 spec（imagegen dual-layer）

- 批次：imagegen 双层批（2026-10-24，作者裁定 08:22 逐字：「分两层做 主agent使用内置工具
  因为要设计前端 像codex那样 生图的时候有个占位动画 我们也是 但是我们要非阻塞
  然后模型兼容记得广一点 能适配现在比较新的生图模型 然后delegate等 就使用插件的模式。
  前端不显示 就是工具调用 返回结果 可阻塞 这个功能和插件要标记调试状态 发版自动剔除」）

相关件：[[glossary]]（delegate/插件机制等概念词条单源——本文双层形态的术语出处）
- 状态：调试中 / experimental（两腿同标）

## 1. 双层总览

| | 腿① 内置工具 | 腿② 插件（MCP） |
|---|---|---|
| 消费者 | Nebula 根会话 | delegate / 节点等下层会话 |
| 调用语义 | **非阻塞**：提交即返回 job 句柄，回合零阻塞 | **阻塞**：调用停在生成完，结果直接返回 |
| 前端 | 占位动画卡片（Codex 风格克制 shimmer），WS 原位替换 | 无前端语义（不显示占位） |
| 产物 | 图片落盘 `<dataRoot>/imagegen/<jobId>.<ext>`（magic-bytes 嗅探定格式：cogview 实测回 JPEG 非 PNG）+ nf-file 票据展示链 | 同左（`mcp-<ts>-<bytes>.<ext>`） |
| 交付形态 | `generate_image` 内置工具（编译期 seal 门控注册） | `plugins/imagegen` 包（seed 外，不进发版产物） |
| 调试标记 | 工具 description + 占位卡/成品卡「调试中」徽标 | plugin.json description + SKILL.md 首段 |

## 2. 共享 provider 层（广兼容）

`nebflow.llm.imagegen` 包，两腿共用一行 provider 代码：

- **适配器按协议划分**：`cogview`（智谱 OpenAI 兼容 + 自家 size 枚举/ Bearer 直用 key）、
  `openai-images`（gpt-image-1 及**一切** OpenAI images 兼容端点——豆包 Seedream /
  火山方舟兼容模式走这条，显式 "WxH" 原样传）、`imagen`（预留位：Gemini
  generateImages 协议已实现但标未验证——imagen-3.0 系 2026-06-30 停服，迁移目标
  gemini-2.5-flash-image 留后续批）。
- **双形态收束**：各家 url / b64_json 两种响应都解析（gpt-image-1 只给 b64；
  cogview/seedream-4 默认给 url），服务层统一下载/解码成图片字节，magic-bytes
  嗅探定真实格式（不臆造 PNG——cogview-3-flash 实测回 JPEG）。
- **尺寸归一化单点**：入参一律 "WxH"；cogview-4 任意分辨率、flash 吸附三枚举、
  gpt-image-1 按长宽比吸附三档、imagen 换算 aspectRatio。
- **配置**：nebflow.json 顶层 `imagegen` 节（全字段可选、缺省关、半节 graceful
  degrade——jev/embedding 同先例）；凭据 `keyRef` 指针指数据根 `secrets/`，
  token 永不落配置/日志/返回值；解析方现读在位性，缺件即该 provider 腿自动关。
- **HTTP**：sttp client4 + SharedBackend 连接池（CurlTool 同款）。

## 3. 腿① 非阻塞 job 生命周期

1. `call`：身份闸（isRootAgent）→ 参数校验 → **面级 fail-fast**（配置/凭据缺席在
   提交前给可行动文案）→ 注册台账（Running）→ 后台 fiber（`IO.start`，回合零阻塞）
   → 立即返回 `{jobId, status:"running"}`。
2. 完成三段回填（互相独立、各带自愈）：
   - PNG 落盘 + `ImageGenJobs` 台账翻转（内存有界 100，磁盘文件不依赖台账）；
   - WS `imagegenUpdate` 帧（BashTool `backgroundTaskUpdate` 同款内联帧先例）——
     前端按 jobId 原位替换占位卡；
   - `BgNotifyDelivery.deliverAgentEvent`（D1 steward 批交付面：直投/复活/持久补投
     三态全审计）——agent 新回合收到 ExternalEvent（携带路径）。
3. 失败同构三段（failed 状态 + error 文案，凭据零出现）。

## 4. 腿② 阻塞面

- 插件包 `plugins/imagegen/`（**seed 外**——发版产物默认不存在本件）：plugin.json
  （描述首段「调试中」标记）+ SKILL.md（纪律）+ mcp.json（stdio）+
  `scripts/imagegen_mcp_server.py`（纯 stdlib，MCP 2025-06-18 子集）。
- MCP server 把 `generate_image` 翻译成本机环回 `POST /api/imagegen`（引擎侧
  与腿①同 provider 层，阻塞等完返路径）。鉴权面 = 环回地址门、零凭据（与
  /health/conn 零凭据先例同向；比例原则：能调本路由的本机进程同样能直读
  secrets——不造第二把假尺）。
- 安装 = 拷目录到数据根 `plugins/`（在位即生效）；停用 = 面板封禁/revoke。

## 5. 调试标记（硬要求①）

- 腿①：工具 description 标「调试中 / experimental」；前端占位卡与成品卡带
  「调试中」描边徽标（低调、无强调条，visual-style 口径）。
- 腿②：plugin.json description 首段标记 + SKILL.md 首段声明 + 工具返回文本
  `debug=调试中` 行。
- 单点来源：Scala 侧 `ImageGenSeal.DebugLabel`（编译期生成）；前端徽标文案静态。

## 6. 发版自动剔除（硬要求②，机制裁定与论证）

**裁定：编译期 flag**（三选一论证）：

1. ~~配置门缺省关~~：能力件仍在产物内，一个配置键即复活——不满足「release
   build 零痕迹」，否。
2. ~~发布脚本剥离~~：字节码/文件手术脆弱、无可复习单点、易静默半剥，否。
3. **编译期 flag**：build.sbt `sourceGenerators` 产出 `ImageGenSeal.scala`
   （`STAND_IMAGEGEN=1` 或 `-Dstand.imagegen=1` 时 `CompiledIn=true`；与 BUILD_SHA /
   webdist 生成器先例同构）。`final val` 常量折叠使全部交付点在 release 构建中消失：
   - 工具注册（registry.scala 的 seal 门控注册段）；
   - Nebula 固定面（`imageGenSealedStrip`，friendsSealedStrip 同款消费点条件化——
     静态集与件数常量不动）；
   - REST 路由（级联位 = `HttpRoutes.empty`）；
   - 前端 js/css 面（`__NEBFLOW_RELEASE__` esbuild define DCE，friendsEnabled 同款）。
   - 腿②插件：不进 seed ⇒ 任何发布渠道默认不含。

**可验证（spec 钉死，`ImageGenSpec` 第 4/5/6 断言组 + 发版流程锚）**：
- seal=false 构建（release 形态，不带 STAND_IMAGEGEN）跑 `sbt test`——
  「注册表无 generate_image / Nebula 交付面无 / 非 Nebula 身份永无」三断言以
  CompiledIn=false 形态全绿；seal=true 构建镜像在場。任一侧红 = 门控破损。
- 发版流程锚：CI/安装器构建不带 `STAND_IMAGEGEN` ⇒ 产物零工具、零路由、
  前端 bundle 零占位代码；面板工具清单（`builtinToolNames`）无此项。

## 7. 授权面

- 腿①：Nebula 专属——`RootOrchestrationTools` 单点授能 + `RootExclusiveTools`
  防声明逃逸 + 工具内 `isRootAgent` 身份闸（POP_NEBULA_ONLY 同款双保险）。
- 腿②：插件授予面（delegate/节点会话 MCP 分配）；根会话不经此路径。

## 8. 已知边界（实验面如实声明）

- 历史重放：job 句柄是瞬时态——重放旧会话时占位卡不会自动找回图片（重启后台账
  清空；磁盘 PNG 仍在，路径在后续 agent 回合文本中）。升稳定批的候选修法。
- MCP server 端口解析序：--port > $STAND_GATEWAY_PORT > $NEBFLOW_GATEWAY_PORT > 8080。
- Imagen 适配器未真连验证（Google 停服窗口）；gpt-image-1/Seedream 真连需各家长
  期 key——免费档 cogview-3-flash 为默认试点。

## 9. 试点调用示例

- 腿①（根会话工具调用）：
  `generate_image(prompt="a minimalist glass house on a foggy hillside at dawn, soft light", size="1344x768")`
  → 立即返回 `{"jobId":"img-a1b2c3d4","status":"running",...}`；前端占位 shimmer；
  完成后占位原位替换为图片（「调试中」徽标保留）。
- 腿②（delegate 会话 MCP 调用）：
  `mcp__plugin_imagegen_imagegen__generate_image(prompt="a red panda sketch, ink style")`
  → 阻塞至生成完，返回 `Image generated (zhipu): /Users/.../.stand/imagegen/mcp-….png`。
