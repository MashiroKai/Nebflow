---
name: nebflow-qa-frontend
description: Nebflow 前端验证域知识——隔离真实例三步验证（隔离 sbt 实例/C2 Playwright 冒烟套件/资源可达性实测含动态 import 链）、禁静态服务器替身规则、FAIL 判定三条款、双端契约静态审核四维方法、只验不改纪律。Use when 验证 Nebflow 前端（src/main/resources/web/）交付：合并回归、资源可达性、WS/REST 契约一致性。
when_to_use: 每轮前端交付后的合并前独立验收；verify 节点分配此 skill。自验 harness 方法论（wire 探针/契约夹具/Playwright 坑）见同插件 frontend-verification；视觉硬规则见 nebflow-frontend-dev 插件的 visual-style。
language: zh
status: active
last_verified: 2026-09-03
---

# QA Frontend — 前端验证域

**只验收不修改**——Write 仅用于写验证报告，严禁编辑任何源码文件。

## 合并回归：三步验证（缺一不可）

### 1. 启动隔离真实实例

```bash
sbt "run --home /tmp/qa-<task> --port 8091 --no-browser"
```

- 端口从 8091 起递增（8091/8092/…），home 按任务名隔离。
- 等待就绪：`curl -s http://localhost:809x/api/health` 返回 200 后才开始。
- 所有验证必须打这个**真实 http4s 实例**——硬前提。

### 2. 跑 Playwright 冒烟套件

- 套件：主仓 `tests/smoke.spec.mjs`（以最新落地版本为准），基础 URL 指向隔离实例端口；全部用例绿才算过。
- FAIL 时逐用例记录：用例名 / 失败断言 / 截图路径。

### 3. 资源可达性实测

改动新引用的每个 JS/CSS/模块 URL——**含动态 import 的子模块链**（如懒加载 `js/viewers/*.js`）——逐个对隔离实例实测：

- `curl -o /dev/null -w '%{http_code}' http://localhost:809x/<path>` 断言 200，或 Playwright network 断言。
- 懒加载资源必须实际交互触发（打开对应面板/功能）后确认请求发出——只看首屏 networkidle 不够。
- **「文件存在于 resources/」不算通过依据**——文件存在 ≠ 路由可达（http4s 静态路由按目录逐条挂载、单段匹配，子目录路由缺失只有真实服务能暴露）。

### 禁替身规则（CRITICAL）

前端验证**禁止**用 python http.server、`npx serve` 等静态文件服务器做路由替身——静态服务器对任意深度路径都返回 200，掩盖真实路由缺口。跑在替身上的「全绿」一律无效，视为未验证。

### FAIL 判定（任一即 FAIL）

冒烟套件任何用例失败（含 console error / pageerror 断言）／任一资源 URL 实测非 200／隔离实例无法启动。FAIL 附逐条证据：URL、状态码、复现命令。

## 契约静态审核（双端比对方法）

纯静态代码审核——只读代码比对双端契约，不启动服务。四个维度全覆盖，每维度记录双端代码位置（后端 file:line ↔ 前端 file:line）：

1. **WebSocket 事件格式**：后端序列化 ↔ JS 事件解析——字段名、Option/null 缺失语义、嵌套结构、枚举序列化方式。
2. **REST API 响应格式**：handler ↔ 前端 fetch——字段/类型/嵌套、HTTP 状态码、错误响应格式、分页列表格式。
3. **静态文件服务完整性**：resources/ 文件是否全被服务端路由覆盖（Bash 统计对比）、MIME 映射、API 路由优先于静态 fallback、SPA fallback 行为。
4. **Token 认证流程**：后端 middleware ↔ 前端拦截器——Header 名称/格式、过期/无效响应格式、登录接口请求/响应。

报告结构：分维度 PASS/FAIL 总览表 + 逐项明细（维度 × 双端位置 × 差异）+ 问题清单（严重程度 + 建议）。

## 运行安全（红线）

- **环境表里的宿主 PID 绝对禁杀**（第一道防线，Process Safety 第 1 条）；**8080 端口识别是第二道防线**：禁止裸 `sbt run`（默认 home + 默认端口会抢宿主）、禁止 kill/信号宿主实例。
- **非宿主进程可按需管理**：隔离实例（809x）、静态文件服务允许 kill——kill 前 PID 验身（`lsof -ti :<端口>` 定位 + cwd 确认，fork 子进程 cmdline 不含路径）。
- 唯一合法启动方式 = 带完整隔离参数（`--home /tmp/qa-* --port 809x --no-browser`）。
- 验收结论（PASS/FAIL + 证据）必须可复现：附命令与原始输出。

## 进程清理纪律（2026-09-05 裁定）

- 三步验证结束**必须清理自己 spawn 的进程**：隔离实例（809x）、静态文件服务、Playwright browser 全部关掉——不留给宿主或下一个会话手清。
- 隔离实例起时留 PID（`echo $!` / nohup 落 pid 文件），用完 `kill <pid>` + `lsof` 复查端口释放；脚本包一层 `trap 'cleanup' EXIT INT TERM`（裸 EXIT trap 信号退出不触发）。
- 裸 playwright 脚本（不走 @playwright/test runner）`browser.close()` 必须进 `finally`——waitForSelector 超时等异常路径跳过 happy-path close，headless chromium 进程树会漏到脚本外；SIGINT/SIGTERM 时 Node 不走 finally，需显式信号处理器兜底。
- 清理前照旧 PID 验身（lsof 定位 + cwd 确认），确认 PID ≠ 环境表宿主 PID 再动手。
