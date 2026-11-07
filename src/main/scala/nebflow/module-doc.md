# nebflow — 模块总索引

> 各级模块的 `module-doc.md` 入口（2026-11-06 生成）。先读本页，再进子模块。

## 顶层包

- [`actor/`](actor/module-doc.md) — 自研 actor 内核：ActorSystem / Behavior / AgentState / 命令与事件。
- [`agent/`](agent/module-doc.md) — agent 运行时：主循环、会话执行、交互枢纽、子代理与崩溃恢复。
- [`bridge/`](bridge/module-doc.md) — 外部消息桥（Telegram 等）与桥接上下文。
- [`cli/`](cli/module-doc.md) — 命令行入口与子命令：CLI 路由、进程管理、单实例守卫。
- [`core/`](core/module-doc.md) — 核心域：工具、项目/Flow、会话、处理器、插件、MCP、调度等（33 个子模块）。
- [`db/`](db/module-doc.md) — 嵌入式数据层：五步写 / 三态读 / 分片 / 保留策略。
- [`dropbox/`](dropbox/module-doc.md) — 跨设备消息与文件传输服务。
- [`gateway/`](gateway/module-doc.md) — HTTP/WS 网关：路由装配、鉴权、会话与连接守卫。
- [`llm/`](llm/module-doc.md) — 模型调用层：provider 适配、回退链、健康监控（3 个子模块）。
- [`neblink/`](neblink/module-doc.md) — 设备互联子系统（客户端半边）：登录、中继、门户、好友、同步（9 个子模块）。
- [`perception/`](perception/module-doc.md) — 感知守护进程：邮件/计费/Otp 等后台感知能力。
- [`pilogic/`](pilogic/module-doc.md) — 循环内核与位点/hook 机制（pilogic 接线批）。
- [`prompts/`](prompts/module-doc.md) — 提示词资源与模板。
- [`service/`](service/module-doc.md) — 配置服务与快照（ConfigService / ConfigSnapshot）。
- [`shared/`](shared/module-doc.md) — 跨域共享：模型、配置、路径、品牌、日志。
- [`social/`](social/module-doc.md) — 社交通道：飞书/微信出站与桥接。
