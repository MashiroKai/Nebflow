# QA 方法论（qa-frontend 实战沉淀）

> **状态**：living · 持续维护 ｜ **维护者**：qa-frontend ｜ **域**：Nebflow
>
> **版本日志**
> - 2026-08-17 v1 初版：更名批次（批1/批3）+ 消息搜索 v3.1 验收实战中验证的四条方法论

QA 侧在验收任务中反复验证有效的方法论。每条含：做法、出处（哪次任务验证）、适用场景。

## 1. 规格质疑处理——FAIL 先核实目标存在性

断言 FAIL 时，先区分**产品缺陷**与**规格笔误**，再决定上报口径：

1. `git grep` 核实断言目标在代码库是否存在（例：任务书写 `#chat-input`，全仓零命中，实际元素是 `#input`）
2. 若是规格笔误：实测断言**意图**最近的替代元素取证（存在+可见+尺寸+截图）
3. 按 **FAIL-as-specified / PASS-as-intended 双口径**上报 Manager 裁定——**不自行改判 PASS**

出处：更名批1 红线验收（2026-08-17），Manager 确认为正确处理方式。
适用：一切按任务书/规格书逐条断言的验收任务。

## 2. "mock 比生产严格"发现路径

写 mock 时按**规格原文的理想语义**实现（如游标真分页切片），若被测实现按**生产现实契约**走（后端忽略游标参数全量返回、前端客户端窗口化），mock 会大面积 FAIL——这恰好暴露"规格原文 vs 既定设计"的兑现缺口。

处置纪律：

1. 先查真实后端代码确认现行契约（例：RestApiRoutes history 端点忽略 before/after/limit）
2. 将 mock 对齐生产契约重跑，§8 二值断言按现行契约判 PASS/FAIL
3. 兑现缺口作为**裁决项**双口径上报（非打回）——误诊风险：把既定设计当实现 bug

出处：消息搜索 v3.1 stream 套件（2026-08-17）。首版 cursor-honoring mock 致 B9/B13 七连 FAIL，查证后确认系既定设计（21:38 游标契约裁定），Manager 裁决接受为已知边界并认定该发现路径值得沉淀。
适用：带 mock 的独立验证，尤其是规格含前瞻性契约（前向兼容参数）的场景。

## 3. jar 副本手术——黑盒触发编译期常量分支

品牌/配置类常量编译进 jar（brand.conf 在 jar 根，classloader 读取），无法运行时改。**黑盒触发法**：

1. 复制 jar 副本到 /tmp（主线产物不动）
2. `zip <副本.jar> brand.conf` 替换条目（zip 原地更新，CRC warning 无害）
3. 副本 jar + `-Duser.home=<夹具父目录>` 起隔离实例 → 走默认数据根解析，触发真实迁移分支

配套夹具：只读复制真实 ~/.nebflow 关键子集（auth.json/client-id/device.json/neblink/、会话 _index.json 裁到已复制的 2 个会话、预写 onboarding.json skipped 免遮罩），manifest 记 sha256 供字节保留断言。

出处：更名批3 验收 Run C（2026-08-17），Manager 评价"漂亮的黑盒验证设计"。
适用：改名日迁移、编译期开关、brand.conf 类单源常量的端到端验证。

## 4. 工具与实例纪律

### playwright 双装坑

仓内 `node_modules/playwright` 与全局 `@playwright/test` 并存时，全局 CLI 跑仓内 spec 报 "No tests found"（test() 注册进仓内库、runner 是全局库）。**用仓内 `node_modules/playwright/cli.js` 跑**。ESM 脚本不认 NODE_PATH，全局 playwright 用绝对路径 import（`/opt/homebrew/lib/node_modules/playwright/index.mjs`）。

### 隔离实例规范起停

- 起：`--home /tmp/qa-<task> --port 809x --no-browser`（8091 起递增）；后台任务监视器会误杀静默 fork 型进程——启动命令带 keepalive 子 shell 每 15s 输出 + `</dev/null`
- 注意 shell `&` 优先级：`(loop) & cd X && cmd`，cd 必须在 `&` 之后
- 停：`lsof -nP -i :<port> -sTCP:LISTEN -t` 找 PID → `ps -p <PID> -o command=` 核对含 QA home 名 → 才 kill → 确认端口释放再离场
- 8080 活实例绝对不碰；8081+ 他人关卡实例不碰（端口错峰）

出处：msg-search A1-A10 实战四坑 + 更名批次三轮实例起停（2026-08-17）。
适用：一切需要真实实例的 QA 任务。
