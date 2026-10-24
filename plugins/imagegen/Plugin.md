# imagegen — 包根使用指导（Plugin.md）

> 本文件是包根使用指导件；逐 skill 作业指令见 `skills/imagegen/SKILL.md`（注入面权威，不一致以它为准并回改本文）。

## 速览

imagegen 是**调试中 / experimental** 生图能力包（imagegen 双层批 2026-10-24 之腿②）：delegate 等下层会话经 MCP 模式获得 `generate_image` 工具——**阻塞调用、直接返回结果**（落盘 PNG 绝对路径），前端不显示任何占位。与腿①（根会话内置非阻塞 `generate_image` + 前端占位动画）共用同一 provider 层。

## 安装与启用

- **不在 seed 分发内**（发版自动剔除口径：release 产物默认不存在本件——seed 插件随实例下发，本包不进 seed；腿①引擎侧另经编译期 `ImageGenSeal` 剔除）。
- 安装 = 把本目录拷入实例数据根 `plugins/imagegen/`（在位即生效，面板可见「调试中」描述标记）。
- 前提：引擎构建带 `STAND_IMAGEGEN=1`（编译期 seal 开）且 nebflow.json 已配 `imagegen` 节——二者缺一，工具调用返回可行动配置文案（不静默失败）。

## 组件面

- `mcp.json`：stdio MCP server（`scripts/imagegen_mcp_server.py`，纯 stdlib）→ 本机环回 `POST /api/imagegen`（引擎侧 seal 门控路由）。
- 凭据：复用实例 `imagegen` 节 `keyRef`（secrets/ 指针），本包零凭据持有。

## 停用

面板「更多 → 封禁该插件」/ `POST /api/plugins/imagegen/revoke`。

## 维护约定

- 升稳定前：description 首段保留「调试中 / experimental」标记；版本号 < 1.0.0。
- MCP server 改动后跑：`python3 scripts/imagegen_mcp_server.py` 自测（tools/list 冒烟，见 REPORT 试点示例）。
