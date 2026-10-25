# 生图能力双层架构实现 REPORT（imagegen dual-layer）

- 批次：imagegen 双层批（2026-10-24，作者裁定 08:22 逐字见 `docs/design/imagegen-dual-layer-spec.zh-CN.md` §1）
- 交付分支：`delegate/delegate-nebflow-dca51539`
- 状态：**调试中 / experimental**（两腿同标；发版自动剔除机制在 §5）

## 1. 双层各自实现要点（一句话各一）

- **腿① 内置工具**：`generate_image`（`src/main/scala/nebflow/core/tools/GenerateImageTool.scala`）Nebula 专属，调用即返回 job 句柄（回合零阻塞，`IO.start` 后台 fiber），完成三段回填——图片落盘数据根 `imagegen/`（magic-bytes 嗅探定扩展名：cogview 实测回 JPEG 非 PNG）+ `ImageGenJobs` 台账翻转、WS `imagegenUpdate` 帧推前端、经 `BgNotifyDelivery`（D1 steward 交付面）唤醒 agent 新回合。
- **腿② 插件模式**：`plugins/imagegen/`（plugin.json + SKILL.md 纪律 + mcp.json + 纯 stdlib Python stdio MCP server）把 `generate_image` 翻译成本机环回 `POST /api/imagegen`（`gateway/ImageGenRoutes.scala`）的**阻塞**调用——路由侧与腿①共用 `ImageGenService` 一行 provider 代码不重复，delegate 会话无前端语义（前端零占位）。

## 2. Provider 清单与适配方式（广兼容，按协议划分非按厂商）

| type | 落地 | 端点形态 | 实测 |
|---|---|---|---|
| `cogview` | `ImageGenAdapters.CogView` | `{base}/images/generations`（智谱 OpenAI 兼容层，Bearer 直用 key），size 枚举吸附（cogview-4 任意分辨率原样传） | ✅ 真连（cogview-3-flash 免费档，136KB JPEG） |
| `openai-images` | `ImageGenAdapters.OpenAiImages` | `{base}/images/generations`——**gpt-image-1 及一切 OpenAI 兼容新模型**（豆包 Seedream/火山方舟兼容模式走这条，显式 "WxH" 原样传）；gpt-image-1 三档按长宽比吸附 | ✅ 真连（同一智谱兼容端点 exercise 本协议路径：n/response_format/吸附 size，121KB） |
| `imagen` | `ImageGenAdapters.Imagen` | **预留位**：Gemini generateImages 协议已实现（instances/parameters、x-goog-api-key、b64 收束），标未验证——imagen-3.0 系 2026-06-30 停服，迁移目标 gemini-2.5-flash-image 留后续批 | 未真连（如实标注） |

共享层 = `nebflow.llm.imagegen`（Types/Adapters/Service/Jobs 四件）：b64+url 双形态收束、URL 二次下载、尺寸归一化单点、sttp+SharedBackend 连接池、keyRef→secrets 现读在位性（缺件即该 provider 腿自动关，jev 四修③同口径）、token 永不落日志/返回值/配置。凭据配置进 nebflow.json 顶层新 `imagegen` 节（`shared/config.scala` 的 `ImageGenConfig`，全字段可选、缺省关、半节 graceful degrade）。

接入点/价格（自调研，原调研报告文件缺失——reports/ 全仓无 imagegen 件，本批以 web 调研补齐）：cogview-3-flash 免费 / cogview-4 ¥0.06 张；gpt-image-1 $0.011–0.129/张按尺寸质量；Seedream 4.0 $0.03/张起；Imagen $0.03/张（停服窗口）。endpoint 向后兼容，换代模型（gpt-image-2.x/seedream-4.5/5.0）走同适配器改 model 名即可。

## 3. 前端占位动画（腿①）

- `web/js/imagegen.js` + `chat.css` 追加块：toolEnd 特判渲染占位卡（玻璃 shimmer 1.8s + 14px 细 spinner + 「调试中」描边徽标 + prompt 字幕，max-width 420px，prefers-reduced-motion 停动效——克制省资源，visual-style 口径）；`imagegenUpdate` 帧按 jobId **原位替换**为真实图片（`ticketUrl` nf-file 票据链， Pop 展示链同款）；失败原位翻错误面。
- 消息流挂点 = 既有 `renderTool` 特判（Mail/Pop 同款内联分支），复用 pending 卡槽位，无新行类型污染 turnGroup。

## 4. 调试标记（硬要求①）

- 腿①：工具 description 标「调试中 / experimental」（`ImageGenSeal.DebugLabel` 编译期单点）；占位卡与成品卡均带「调试中」徽标（占位动画在场 = 标记在场）。
- 腿②：plugin.json description 首段「调试中 / experimental」+ SKILL.md 首段声明 + 工具返回文本 `debug=调试中` 行 + Plugin.md 速览首句。

## 5. 发版自动剔除（硬要求②）——机制裁定：**编译期 flag**

三选一论证（详版见设计 spec §6）：配置门缺省关❌（一个配置键即复活，非零痕迹）；发布脚本剥离❌（字节码手术脆弱无可复习单点）；**编译期 flag✅**——build.sbt `sourceGenerators` 产出 `ImageGenSeal.scala`（`STAND_IMAGEGEN=1` / `-Dstand.imagegen=1` 构建 ⇒ `CompiledIn=true`；BUILD_SHA/webdist 生成器先例同构），`final val` 常量折叠使 release 构建中：工具注册段消失（registry.scala 门控段）、Nebula 交付面剥除（`imageGenSealedStrip`，friendsSealedStrip 同款消费点条件化——静态集与件数常量不动）、REST 级联位 = `HttpRoutes.empty`、前端 js 面经 `__NEBFLOW_RELEASE__` esbuild DCE（friendsEnabled 同款）。腿②插件不进 seed ⇒ 任何发布渠道默认不含。

可验证（spec 钉死）：`ImageGenSpec` 第 4/5/6 断言组——seal=false 构建跑 `sbt test` 三断言（注册表无此工具 / Nebula 交付面无 / 非 Nebula 身份永无）以 CompiledIn=false 形态全绿，seal=true 镜像在場，任一侧红 = 门控破损；发版 CI 不带 `STAND_IMAGEGEN` ⇒ 产物零工具零路由、面板 `builtinToolNames` 无此项。

## 6. Spec 结果

- `src/test/scala/nebflow/llm/imagegen/ImageGenSpec.scala`（新，10 组断言）：尺寸归一化 4 组 / 请求体纯函数 4 组（含 token 不进 body 面）/ 响应解析双形态 3 组 / materialize+sniffExt 2 组 / 配置 decode 2 组 / job 台账 1 组 / seal 门控+身份面 4 组（release-strip 锚）/ 件数常量 19 锚。
- 既有锚按「作者令按面变更」纪律同步（非放宽）：`RootSixBaseToolsSpec` 第二锚 18→19（沿革补记）、`FriendsSealKit.expectedNebulaSize` 扩展为双剥离单点派生（friends+imagegen，presence-based 零裸数字）、`Phase2dToolRefactorSpec` 期望值并扣 imageGenStrip、`FriendsSealSpec` unseal 件数改单点派生。
- 真连冒烟：`scripts/imagegen-smoke.py` PASS——两适配器各打一次真实 HTTP（智谱 key 复用 llm.providers.zhipu.apiKey），产出真实图片字节并 sniff 通过（`/tmp/imagegen-smoke-*.jpeg`）。
- MCP server 自测 PASS：initialize + notifications/initialized + tools/list 按 MCP 2025-06-18 子集握手回帧（stdio newline-delimited JSON，与引擎 `StdioTransport` 读行口径一致）。
- 编译与测试（本 worktree 实跑）：
  - `sbt compile`（末轮）干净通过；
  - **seal=OFF（release 形态）**：`testOnly ImageGenSpec + RootSixBaseToolsSpec + FriendsSealSpec + Phase2dToolRefactorSpec` ⇒ **49/49 全绿**——release-strip 断言组以 CompiledIn=false 形态全绿（注册表无此工具 / Nebula 交付面无 / 非 Nebula 身份永无 / 件数常量锚 19）；
  - **seal=ON（`STAND_IMAGEGEN=1`）**：`testOnly ImageGenSpec` ⇒ **20/20 全绿**——镜像在場形态（注册表含工具、Nebula 面交付、行为断言两组尺寸/协议/解析全覆盖）；
  - 插件包 creator 机械校验 **30/30 PASS**（`validate_plugin.py --profile creator`；M07 描述长度与 `__pycache__` 已处置）。

## 7. 试点调用示例（各一条）

- 腿①（根会话）：`generate_image(prompt="a minimalist glass house on a foggy hillside at dawn, soft light", size="1344x768")` → 立即返回 `{"jobId":"img-a1b2c3d4","status":"running"}`；前端占位 shimmer 在场；完成后 WS 帧原位替换为真实图片（「调试中」徽标保留），agent 新回合收到 `ExternalEvent(imagegen, completed, <path>)`。
- 腿②（delegate 会话）：`mcp__plugin_imagegen_imagegen__generate_image(prompt="a red panda sketch, ink style")` → 阻塞至生成完，返回 `Image generated (zhipu): <dataRoot>/imagegen/mcp-<ts>-<bytes>.jpeg` + `debug=调试中`。

## 8. 未做 / 开放项

- `sbt test` 全量回归未在本 worktree 跑完（体量半小时级，合并队列统一跑）；本批触碰的既有 spec 锚已按纪律更新并逐一核过引用面。
- Imagen 适配器未真连（Google 停服窗口，预留位语义）；gpt-image-1/Seedream 各家专属参数（watermark/sequential_image_generation 等）未铺参数面——实验面按最小参数面纪律，后续按需扩。
- 历史重放不找回图片（job 句柄瞬时态，磁盘文件仍在）——升稳定批候选修法，已在 spec §8 如实声明。
- 调研原报告 `reports/20261024_071249_imagegen-capability__delegate-nebflow-0ae466e0.md` 在主仓与 worktree 均不存在（已检索），接入点/价格结论由本批 web 调研补齐（来源：docs.bigmodel.cn / platform.openai.com / volcengine docs / 第三方价目聚合，未验证项已在 §2 标注）。

## 9. 产物清单

- 新件：`src/main/scala/nebflow/llm/imagegen/{ImageGenTypes,ImageGenAdapters,ImageGenService,ImageGenJobs}.scala`、`src/main/scala/nebflow/core/tools/GenerateImageTool.scala`、`src/main/scala/nebflow/gateway/ImageGenRoutes.scala`、`src/main/resources/web/js/imagegen.js`、`src/test/scala/nebflow/llm/imagegen/ImageGenSpec.scala`、`plugins/imagegen/`（plugin.json/mcp.json/Plugin.md/skills/imagegen/SKILL.md/scripts/imagegen_mcp_server.py）、`scripts/imagegen-smoke.py`、`docs/design/imagegen-dual-layer-spec.zh-CN.md`。
- 改件：`build.sbt`（seal 生成器）、`shared/config.scala`（imagegen 节）、`core/tools/registry.scala`（门控注册）、`agent/AgentCore.scala`（RootOrchestrationTools+1=19 / RootExclusiveTools+1 / imageGenSealedStrip / 件数常量 19）、`gateway/RestApiRoutes.scala`（级联位）、`web/js/chat.js`（renderTool 特判 + import）、`web/js/main.js`（WS 帧处理）、`web/css/chat.css`（占位动画块）、测试锚四件（RootSixBaseToolsSpec / FriendsSealKit / Phase2dToolRefactorSpec / FriendsSealSpec）。
