---
name: nebflow-qa-backend
description: Nebflow Scala 后端验证域知识——五维审核结构（sbt compile/sbt test/验收条件逐项/代码质量三维/回归）、sbt 锁冲突错峰重试、运行安全红线（8080 宿主保护/PID 验身）、✅/❌ 审核报告格式、只审不改纪律。Use when 验证 Nebflow Scala 后端交付物：编译测试、隔离实例冒烟、验收条件核对、Scala 代码质量审查。
when_to_use: 每轮后端交付后的验证关卡；verify 节点分配此 skill 做五维全覆盖审核。验证方法论深化（验红实证/变异安全/冒烟法证）见同插件 verification-rigor 与 isolated-smoke-verification。
language: zh
status: active
last_verified: 2026-09-03
---

# QA Backend — Scala 后端验证域

**只审核不修改**——发现问题只报告，不修复；Write 仅用于写审核报告。

## 五维审核（每次全覆盖）

### 1. 编译验证（sbt compile）

在指定工作目录执行 `sbt compile`，确认零编译错误；记录全部 error 和 warning。

### 2. 测试验证（sbt test）

跑全部测试；记录通过/失败/忽略数量；失败用例记录用例名和错误输出。多 agent 并行时 sbt 可能锁冲突——错峰重试；仍失败**如实报告为阻塞（不是 FAIL）**。

### 3. 验收条件逐项核对

从任务描述或 plan 提取验收条件清单；逐条核对，每条给 ✅/❌ + 证据（文件路径/代码行/命令输出）；不跳过、不合并。

### 4. 代码质量审查（三维）

- **错误处理**：IO 错误路径显式（raiseError/handleErrorWith），不吞异常；危险操作（网络/超时/外部资源）有兜底与日志。
- **架构分层（铁律）**：「Keep your Actors out of your cats-effect, and your cats-effect out of your actors」——Actor 只管消息与状态，IO 只管副作用编排，两层不混用。
- **资源释放**：资源获取用 bracket/guarantee 保证释放；Actor 停止时的清理逻辑完整。

### 5. 回归验证

检查本次变更是否破坏已有功能；相关既有测试是否仍全绿。

## 运行安全（红线）

- **环境表里的宿主 PID 绝对禁杀**（第一道防线，Process Safety 第 1 条）；**禁止 `sbt run` 裸跑**（默认端口 8080 抢宿主）；**8080 端口识别是第二道防线**——对 8080 监听进程绝对禁发任何信号——宿主是所有会话的运行载体。
- 隔离实例（端口 ≠ 8080）/静态文件服务可按需管理，但 **kill 前 PID 验身**：`lsof -ti :<端口>` 定位 + `lsof -p <pid> | grep cwd` 确认工作目录（fork 的 sbt/java 子进程 cmdline 常不含项目路径）。
- 隔离实例冒烟仅当任务明确提供隔离环境（独立 worktree + 独立端口）时执行；默认只做 compile/test 验证。
- shutdown/Ctrl+C 行为验证必须用隔离进程，绝不动真实宿主。

## 进程清理纪律（2026-09-05 裁定）

- 测试/冒烟/e2e 结束**必须清理自己 spawn 的进程**——隔离 sbt 实例、mock server、静态文件服务用完即杀，不留给宿主或下一个会话手清。
- 脚本首选 `trap 'cleanup' EXIT INT TERM` 模式（后台 PID 登记 → EXIT 逐个 kill + wait + lsof 复查端口释放；裸 EXIT trap 信号退出不触发）；REPL/手跑用完显式 kill。
- 不得依赖「会自己退出」：`while True` 死循环、长 `sleep`、stdio 常驻进程不自终止——异常路径漏杀 = 永久残留。
- 清理自起进程前照旧 PID 验身（lsof 定位 + cwd 确认），确认 PID ≠ 环境表宿主 PID 再动手。

## 报告格式

```
## Scala 后端审核报告
**任务**: <任务名/分支>   **结果**: ✅ PASS / ❌ FAIL

### 审核维度总览
| 维度 | 状态 | 摘要 |
|------|------|------|
| 编译 (sbt compile) | ✅/❌ | <零错误 或 X errors> |
| 测试 (sbt test) | ✅/❌ | <X passed, Y failed> |
| 验收条件 | ✅/❌ | <N/M 条满足> |
| 代码质量 | ✅/❌ | <错误处理/分层/资源释放> |
| 回归验证 | ✅/❌ | <无回归 / X 项回归> |

### 验收条件逐项
| # | 验收条件 | 状态 | 证据 |
| 1 | <条件> | ✅/❌ | <文件:行 / 命令输出> |

### 问题清单（仅 FAIL 时）
1. [严重程度: 高/中/低] <维度> — <问题> + <证据> + <建议>
```

## 约束

- 编译/测试结论必须附实际命令输出，不能凭空判断。
- 代码质量审查必须引用具体文件路径和行号。
- 报告写入任务指定位置；未指定则 `/tmp/qa-backend-reports/<branch>-backend.md`。
- 工作目录以任务指定为准（主仓或 /tmp/nb-* worktree）；未指定时向触发方确认，不要猜。
