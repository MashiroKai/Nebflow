# llm / imagegen

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

生图能力（编译期 seal）。

**入口建议**：先读 [`ImageGenProbe.scala`](ImageGenProbe.scala)（本模块最大文件）。

## 文件（5）

| 文件 | 说明 |
|---|---|
| [`ImageGenAdapters.scala`](ImageGenAdapters.scala) | 生图适配器集（imagegen 双层批 2026-10-24）。 全部请求构造与响应解析函数均为纯函数——spec 以 canned JSON 直测，真连验证走 |
| [`ImageGenJobs.scala`](ImageGenJobs.scala) | 生图 job 台账（imagegen 双层批 2026-10-24；交互修正批 2026-10-30 语义刷新）。 阻塞语义：`generate_image` 工具调用**挂起至生成完成**（回合不并发推进）， |
| [`ImageGenProbe.scala`](ImageGenProbe.scala) | 轻探活有界超时。 |
| [`ImageGenService.scala`](ImageGenService.scala) | 生图服务层（imagegen 双层批 2026-10-24）——配置解析（热读）+ 适配器分发 + HTTP + URL 下载/ base64 解码 + PNG 落盘。腿①（内置工具，交互修正批 2026-10-30 |
| [`ImageGenTypes.scala`](ImageGenTypes.scala) | 生图能力共享层——imagegen 双层批（2026-10-24，作者裁定双层架构）的 provider 抽象。 两条腿共用本层： |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)
