# imagegen — 包根使用指导（Plugin.md）

> 本文件是包根使用指导件；逐 skill 作业指令见 `skills/imagegen/SKILL.md`（注入面权威，不一致以它为准并回改本文）。

## 速览

imagegen 是**正式产品面**生图能力包（imagegen 双层批 2026-10-24 之腿②；功能开放批 2026-10-30 作者令「直接开放出来」起解除调试标记）：delegate 等下层会话经 MCP 模式获得 `generate_image` 工具——**阻塞调用、直接返回结果**（落盘图片绝对路径），前端不显示任何占位。与腿①（根会话内置非阻塞 `generate_image` + 前端占位动画）共用同一 provider 层。

## 安装与启用

- **不在 seed 分发内**（seed 插件随实例下发，本包手工安装；引擎侧生图能力由编译期 `ImageGenSeal` 管，缺省编入）。
- 安装 = 把本目录拷入实例数据根 `plugins/imagegen/`（在位即生效）。
- 前提：nebflow.json 已配 `imagegen` 节（providers: cogview / openai-images / imagen，keyRef 指针指 secrets/，或在设置页「生图」节直接填 API Key）——未配时工具调用返回人话可行动配置文案（哪个 provider 缺 key、怎么配；不静默失败）。引擎构建显式 `STAND_IMAGEGEN=0` 时本机路由缺位，调用返回连接失败文案。

## 组件面

- `mcp.json`：stdio MCP server（`scripts/imagegen_mcp_server.py`，纯 stdlib）→ 本机环回 `POST /api/imagegen`（引擎侧 seal 门控路由，缺省在场）。
- 凭据：复用实例 `imagegen` 节 `keyRef`（secrets/ 指针），本包零凭据持有。

## 停用

面板「更多 → 封禁该插件」/ `POST /api/plugins/imagegen/revoke`。

## 维护约定

- 正式面：description 首段不得再出现「调试中 / experimental」类标记；版本号 ≥ 1.0.0。
- MCP server 改动后跑：`python3 scripts/imagegen_mcp_server.py` 自测（tools/list 冒烟，见 REPORT 试点示例）。
