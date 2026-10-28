# 生图（imagegen）能力现状审计 + 使用指南

> 审计日期：2026-10-28 16:1x（作者问：「生图能力功能做的如何了，我该如何测试和使用？」）
> 纯只读审计——未触碰在产引擎（pid 70481 / gateway 6138），未读取任何 key 值（只查配置位存在性与空否）。

## 0. 现状一句话

**代码全量落地、测试全绿（含真连冒烟 PASS），但本机三道开关全关：在产引擎是 seal=OFF 构建（生图面整体未编入）+ 实例 nebflow.json 无 `imagegen` 节 + secrets/ 无对应 key 文件——所以现在一台机器上「能测」的只有 `scripts/imagegen-smoke.py` 直连冒烟（用已配的智谱聊天 key，5 分钟）；引擎内生图（腿①/腿②）需先起 seal=ON 构建并补配置，纯配置工作、零代码缺口，半小时级。**

## 1. 设计面（docs/design/imagegen-dual-layer-spec.zh-CN.md，已读全文）

- **形态 = 双层架构**（作者令 2026-10-24 08:22 逐字裁定）：
  - **腿① 内置工具**：`generate_image` 编译进引擎，**Nebula 根会话专属**（`RootOrchestrationTools` 单点授能 + `RootExclusiveTools` 防声明逃逸 + 工具内 `isRootAgent` 身份闸双保险）；**非阻塞**——提交即返回 `{jobId, status:"running"}`，后台 fiber 生成，前端占位 shimmer 卡片（Codex 风格）经 WS `imagegenUpdate` 帧原位替换为真图，agent 新回合收 ExternalEvent 带落盘路径。
  - **腿② 插件（MCP）**：`plugins/imagegen/` 包（seed 外，发版产物默认不含），delegate/节点会话经 stdio MCP server 调本机环回 `POST /api/imagegen`，**阻塞**等完返路径，无前端语义。
- **供应商（按协议划分、广兼容）**：`cogview`（智谱，OpenAI 兼容 + 自家 size 枚举）、`openai-images`（gpt-image-1 及一切 OpenAI images 兼容端点——豆包 Seedream/火山方舟兼容模式同走此适配器）、`imagen`（**预留位**：Gemini generateImages 协议已实现但未验证——imagen-3.0 系 2026-06-30 停服）。SD 本地（Draw Things/ComfyUI/mflux）**不在本批**——20261024 另有独立调研稿（根目录 `20261024_071038_mac-local-image-gen-research__flow-59c1e3ad.md`），仅调研未接。
- **凭据**：nebflow.json 顶层 `imagegen` 节，`keyRef` 指针指数据根 `secrets/`，token 永不落配置/日志/返回值。
- **验收标准**（spec 钉死）：① provider 纯函数面（尺寸归一化/请求体/b64+url 双形态解析）；② 配置 graceful degrade（缺省关）；③ 非阻塞 job 台账生命周期；④ **编译期 seal 门控「release build 零痕迹」**（`STAND_IMAGEGEN=1` 才编入；release 构建工具注册段/Nebula 交付面/REST 路由/前端 js 全消失，断言锚在 ImageGenSpec）；⑤ 身份闸（Nebula 专属）；⑥ 调试标记（工具描述+前端徽标+插件描述全标「调试中」）。另要求真连冒烟（provider ≥2 实调）钉批 REPORT。

## 2. 实现面（对照设计逐项落地度）

| 设计项 | 落地 | 证据（行号级） |
|---|---|---|
| 腿① 工具 + 非阻塞 job | ✅ | `src/main/scala/nebflow/core/tools/GenerateImageTool.scala`（:76-106 立即返 job 句柄；:109-139 后台 fiber + 三段回填；:141-180 WS 帧 + BgNotifyDelivery 唤醒） |
| provider 共享层 | ✅ | `nebflow/llm/imagegen/`：ImageGenService（resolve 热读 :34-59 / 凭据缺件腿自动关 :62-80 / magic-bytes 嗅探 :158-169）、ImageGenAdapters（cogview :87-107 / openai-images :59-82 / imagen :114-130）、ImageGenTypes（错误面 :20-39）、ImageGenJobs（台账） |
| REST 腿②路由 | ✅ | `nebflow/gateway/ImageGenRoutes.scala`（POST /api/imagegen 阻塞一击 :40-87 + GET /api/imagegen/job/:id :90-106；环回门零凭据 :33-34）；级联挂载 `RestApiRoutes.scala:95`（seal 门控，OFF=HttpRoutes.empty） |
| 前端占位动画 | ✅ | `src/main/resources/web/js/imagegen.js`（shimmer 占位卡 :26-49 / WS 原位替换 :52-73）；接线 `chat.js:1042`（renderTool 特判）+ `main.js:1163`（imagegenUpdate）；css 在 chat.css |
| seal 编译期剔除 | ✅ | `build.sbt:47-59` sourceGenerators 产出 ImageGenSeal.scala；消费点：registry.scala:188（注册段）、AgentCore.scala:511-512（imageGenSealedStrip）+:819（Nebula 面剥除）、RestApiRoutes.scala:95（路由）、chat.js:1034-1035（`__NEBFLOW_RELEASE__` DCE，build-web.mjs:113 define 注入） |
| 腿② 插件包 | ✅ | `plugins/imagegen/`：plugin.json（描述首段「调试中」）+ skills/imagegen/SKILL.md（分工铁律/参数面）+ mcp.json（stdio python3）+ scripts/imagegen_mcp_server.py（纯 stdlib，MCP 2025-06-18 子集，:15-18 端口解析序） |
| 测试锚 | ✅ | `ImageGenSpec.scala` 200 行 10 组断言（含 release-strip 镜像组）；resume 批宽面电池：**seal=OFF 1131/1131、seal=ON 119/119 双形态全绿**（reports/20261025_175913_imagegen-resume/REPORT.md §2/§6，证据 log 在案） |
| 真连冒烟 | ✅（原批 PASS） | `scripts/imagegen-smoke.py`：两适配器各打一次真实 HTTP（复用 `llm.providers.zhipu.apiKey`，cogview-3-flash 免费档），原批产出真字节并 sniff 通过（原批 REPORT §6 钉案）；本机 /tmp 产物已被清理 |
| 插件机械校验 | ✅ | validate_plugin creator profile 30/30（原批 §6） |

**结论：不是半成品——设计六项验收全部有实现与测试锚，imagegen 双层批（9776d3ce1）+ resume 合批（83788191f/ca4ab6c79）均已入 main。**

## 3. 本机可用性现状（三道开关全关 + 插件未装）

| 开关 | 现状 | 证据 |
|---|---|---|
| ① seal=ON 构建 | **关**：在产引擎构建 `CompiledIn=false` | 主仓 target 生成件 `target/scala-3.5.2/src_managed/main/nebflow/core/ImageGenSeal.scala`（2026-10-28 09:36 生成）实读 `final val CompiledIn: Boolean = false`；在产引擎经 sbt-args 从主仓 target 起 → 无 generate_image、无 /api/imagegen、前端无占位代码 |
| ② 实例配置节 | **缺**：`~/.stand/nebflow.json` 无 `imagegen` 节 | 现读解析确认 ABSENT（=面关，即使编入也 Disabled） |
| ③ 凭据 keyRef | **缺**：`~/.stand/secrets/` 无 imagegen key 文件 | secrets/ 目录现存 14 个文件，无 zhipu/openai 生图 key；**但** `llm.providers.zhipu.apiKey` 非空（在配）——冒烟脚本可直接用，引擎 keyRef 只需把该值落一个 secrets 文件 |
| ④ 腿②插件安装 | **未装**：数据根 `~/.stand/plugins/` 22 个插件无 imagegen | 仓内 `plugins/imagegen/` 在（源），拷到数据根才生效 |
| ⑤ 产物目录 | 空：`~/.stand/imagegen/` 不存在 | 本机从未生成过 |

## 4. 使用/测试指南

### 4a. 今天立刻能测（零改动，5 分钟）：provider 直连冒烟

```bash
python3 scripts/imagegen-smoke.py
# 用已配的 llm.providers.zhipu.apiKey 打 cogview-3-flash 免费档两次
# （cogview 原生形态 + openai-images 兼容形态各一击）
# 产物：/tmp/imagegen-smoke-cogview.*、/tmp/imagegen-smoke-openai-compat.*
# 输出 SMOKE PASS 即 provider 层协议面在本机全通
```
（原批已 PASS 钉案；本轮未代跑——会消耗真实 API 配额，留作者自跑。）

### 4b. 腿①（Nebula 非阻塞生图，前端占位动画）——补齐三步

1. **seal=ON 起 dev 构建**：仓库根 `STAND_IMAGEGEN=1 sbt run`（或 `-Dstand.imagegen=1`）；resume 批实证双形态互切增量编译 4s 级感知。**不要**把 STAND_IMAGEGEN 带进发版流程（发版默认零痕迹是设计行为）。
2. **写配置节**（`~/.stand/nebflow.json` 顶层加）：
   ```json
   "imagegen": { "enabled": true, "defaultProvider": "zhipu",
                 "providers": { "zhipu": { "type": "cogview",
                   "model": "cogview-3-flash", "keyRef": "zhipu-api-key" } } }
   ```
3. **建 key 指针文件**：把智谱 API key 值写入 `~/.stand/secrets/zhipu-api-key`（文件名=keyRef；值永不进配置/日志）。
4. 重启引擎 → 面板 Nebula 会话里直接说「画一张：雾中山坡上的玻璃房子」→ 工具卡即时返 `{jobId, status:"running"}`，下方出现 shimmer 占位卡（带「调试中」徽标）→ 完成原位替换为图片；**产物落盘** `~/.stand/imagegen/img-<jobId>.<png|jpeg|webp>`（扩展名按字节嗅探，cogview 实测回 JPEG）；agent 新回合收到路径。

**失败看哪**：① 工具卡错误文案（`IMAGEGEN_CONFIG`=配置/凭据缺席在提交前 fail-fast；`IMAGEGEN_ROOT_ONLY`=非根会话误调）；② `curl http://127.0.0.1:6138/api/imagegen/job/<jobId>`（环回免凭据 job 状态读面）；③ 日志器 `nebflow.imagegen` / `nebflow.imagegen.tool`（凭据零出现）。

### 4c. 腿②（delegate/节点阻塞生图）——在 4b 之上加一步

```bash
cp -r plugins/imagegen ~/.stand/plugins/   # 在位即生效；面板可封禁/停用
```
delegate 会话里模型经 MCP 调 `generate_image` → **阻塞**至完成，返回绝对路径 + `debug=调试中` 行。端口解析序 `--port > $STAND_GATEWAY_PORT > $NEBFLOW_GATEWAY_PORT > 8080`——本机 autostart 脚本已 `export NEBFLOW_GATEWAY_PORT=6138`（`~/.stand/bin/nebflow-autostart.sh:5`），且 MCP 子进程经 ProcessBuilder 继承引擎 env（transports.scala:192 put 进继承副本），经 autostart 起的引擎此腿端口解析无需额外动作。

## 5. 缺口清单（升可测/可用的最短路径）

1. **在产引擎未编入生图面**（seal=OFF 是发版缺省——设计行为非缺陷）：要引擎内测试必须起 seal=ON dev 构建。工作量 ≈ 重建+重启，配置工作量；**代码零缺口**。
2. **配置节 + keyRef 两件未建**（§4b 步 2/3）：写一个 JSON 节 + 落一个 secrets 文件，10 分钟内。
3. **插件未装到数据根**（§4c）：一条 cp。
4. **gpt-image-1 / Seedream 未真连验证**：适配器已在、原批只真连了 cogview 两形态——需各家长期 key 后各打一击钉案。
5. **Imagen 预留位未真连**（Google 停服窗口；迁移目标 gemini-2.5-flash-image 留后续批）——设计已如实声明。
6. **历史重放不找回图片**（job 句柄瞬时态，重启后台账清空、磁盘文件仍在）——升稳定批候选修法，spec §8 已声明。
7. 小笔误：ImageGenSpec 头注 `imagegen-smoke.sh` 实为 `.py`（注释级，resume 批已登记未顺手改）。
8. SD 本地（Draw Things/ComfyUI/mflux）未接：只有调研稿（根目录 20261024_071038），接入属后续批。

## 6. 横对比：视觉工作室项目与本能力的关系

`/Users/kaiyu/.stand/projects/视觉工作室/`（挂载项目，project.json：「视频制作与剪辑（Final Cut 等工具链）、社交卡片/视频封面等自媒体视觉物料、素材归档」）——**与本能力零依赖、零调用**：
- 其「生成」腿是**程序化 SVG/HTML 脚本**（`generate_transformer_showcase.py` 手搓 SVG 渐变几何，28KB，非 AI 生图）+ `render_showcase.py` 渲染管线 → transformer_showcase_4k.png；
- 其「素材」腿是**真实照片归档**（figure-collection-20261028/：NASA/CNSA/SpaceX 公开图 + manifest.md）；
- 视频走 Final Cut 外部工具链。
- 关系定位：视觉工作室是本能力的**潜在消费方**（封面/配图物料可用腿②插件在项目会话里阻塞生图），现状未接；生图能力升可用后是它的自然增量，不构成迁移。

## 7. 证据清单

- 设计：docs/design/imagegen-dual-layer-spec.zh-CN.md（frontmatter 卫生批已补：type/status=open）
- 实现：§2 表格行号级路径（worktree delegate-nebflow-f7507085 @ delegate/delegate-nebflow-f7507085）
- 测试：reports/20261025_175913_imagegen-resume/REPORT.md（seal=OFF 1131/1131 + seal=ON 119/119 + 双形态实读核验）+ 原批 reports/20261024_090719_imagegen-dual-layer__delegate-nebflow-dca51539.md §6（冒烟 PASS + MCP 握手自测 PASS）
- 本机现读：ImageGenSeal 生成件实读 CompiledIn=false；nebflow.json imagegen 节 ABSENT、llm.providers.zhipu.apiKey 非空（仅存在性，未读值）；secrets/ 文件名清单；数据根 plugins/ 目录；视觉工作室项目目录
- 未做：未触碰在产引擎进程；未代跑冒烟（消耗配额）；未读取任何 key 值
