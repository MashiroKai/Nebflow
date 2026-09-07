---
name: verification-rigor
description: 验证严谨性纪律——验红实证、变异安全、spec 载力、sbt/异步排障、日志断言韧性、字段改名四面验证。Use when 修复 bug 后需要证明 spec 真的能抓住回归、跑变异验红、调试 sbt 挂起/OOM 或异步 hang、写日志断言，或验证 wire 字段改名/全量 suite 红绿归因。
audience: nebflow-project
language: zh
status: active
last_verified: 2026-08-24
---

# Verification Rigor — 验证严谨性纪律

从真实事故中沉淀的验证方法论。核心信念：**绿测试不证明防线有效——只证明这次测到了**；一切验证结论要有独立证据链。

## 1. 验红实证（修复后必做）

修复 bug 后，必须「故意注回 bug 验红 → 恢复验绿」实证新 spec 真的能抓住回归：

- **验红于当前树注入 bug，优于 checkout pre-fix**：checkout 按文件回滚到 HEAD，会把同文件里未提交的正式修复一起抹掉。正确做法：先 commit 正式版，再变异。
- **还原变异用 python 定点替换，不用 checkout**——精确还原，不碰其他未提交改动。
- 变异验红跑出**意外全绿**时，不要采信该轮、不要急着改测试——先怀疑编译时序假象（增量编译缓存/mtime 会让 sbt 跑到旧产物，变异根本没进 class 文件）：重注同一变异 + 测试内加 `DIAG println` 打印实际观测值，再跑一次；确凿红后再恢复并复绿。一次重注的成本远低于按假绿结论返工。

## 2. Spec 载力（防止巧合绿）

- **顺序/"最近 N"语义的 spec 用真实感随机 id（UUID/hex），不用规则序号 id**：`Set.takeRight` 等哈希迭代序实现下，规则序号 id 的字符串哈希序可能恰好=插入序，测试靠巧合绿掩盖 bug。
- **spec-first 不只验修复，还暴露共伴断点**：修 bug 前先写会红的 spec，条件化旧行为后每个隐藏分支都会显形——红测试是最便宜的静态分析（先例：完成通知条件化后暴露出 single-immediate 分支丢计数器的第二个真断点）。

## 3. 中断可恢复的实现节奏

多文件 feature 实现以「结构化提交边界 + spec 锚点注释」抗会话中断：

- 增量顺序：定义域类型 → client API → 纯状态守卫 service → 向后兼容默认参接入既有设施
- 每步注释标注 spec §引用（如 `// §6.1`）；纯状态机与 IO 分离，可独立单测、可作默认构造参无缝接入
- 工作树活过会话死亡，但内存上下文活不过——提交边界 + §锚点让任何 agent 能从任意文件恢复

## 4. 异步/sbt 排障

- **异步 hang 二分定位**：用逐层简化的探针测试收窄范围（bare fiber → 简单 actor → 真实 actor），每层通过就排除一层；timeout handler 里加 `Thread.getAllStackTraces` 看挂起点（async suspension 全是 parked 线程，不是死锁）。
- **线程锁死锁（Python 等）**：SSE/mock 服务器「完全不响应/超时」且 lsof/手动探测均正常时，用 `faulthandler.dump_traceback_later` 抓线程栈看是否卡在二次 `acquire`——默认 `threading.Lock` 非重入，同一线程在锁内调用另一个也加锁的函数会永久阻塞且无异常；修法=内层移除锁或改 `RLock`（先例：do_POST 的 `with LOCK:` 内调 log_req()，log_req 内部又 `with LOCK:` 死锁）。
- **sbt test 挂起（~0% CPU、日志无 summary）**：先 `grep OutOfMemoryError` 确认测试 JVM OOM（sbt 不会自动退出），再用 `SBT_OPTS="-Xmx3g"` + 聚焦子集重跑。
- **全量 suite 红但目标 spec 单独绿**：先 `testOnly <目标Spec>` 独立确认，再全量 grep 精确 `*** FAILED ***` 行定位；用「失败用例与改动文件的相关性」区分环境 flake 与真回归（spec 单绿 + 失败面与 diff 零交集 = 环境性）。

## 5. 日志断言韧性

- 日志内容断言失败但代码里明确有该日志 → **先疑日志层重载/应用顺序，再疑领域逻辑**（zio-logging 的 LogAnnotation 有 `(m)(th)` 与 `(m, th)` 两种应用序；deprecated alias 被移除后重载解析静默改变）。
- 日志断言避免 `messages.last`，用 `messages.exists`——边界 reminder 等追加消息会使 last 误报。
- circe 里 `\` 不是 Json 成员，需 `hcursor.get`，无同名做 `exists`。

## 6. 字段改名/契约对齐的四面验证

wire 字段改名有四个独立失败模式，各需专门检查，缺一即留盲区：

1. **diff 纯改名确认**——codec/行为未被顺带修改
2. **受影响测试用例更新断言后重跑**
3. **双端字段名对齐**——前端消费端与后端 deriveCodec 一致
4. **残留 grep**——旧名引用（含未测试代码路径，是唯一能抓孤儿引用的检查面）

## 7. 测试进程清理（2026-09-05 裁定）

- 验证结束**必须清理验证过程 spawn 的进程**：隔离实例、mock server、静态文件服务、裸 playwright browser——验证结论有效不等于进程已清；残留的旧进程会污染下一轮验证（旧代码占端口、日志计数错位）。
- 收尾三步：杀（kill + `pgrep -f` 兜底）→ `wait` 收尸 → `lsof` 复查端口释放；脚本包 `trap 'cleanup' EXIT INT TERM`。
- 变异验红/对照实验中起的进程同样适用——实验结束时的进程表应与实验前一致（`ps` 快照对比是 cheapest 检查）。
- spec 侧：测试自己 spawn 的 OS 进程要挂异常路径销毁守卫（`IO.guarantee`/bracket），尾部 destroy 只覆盖 happy path——断言失败/超时/取消路径都会跳过。
- 环境表里的宿主 PID 绝对禁杀（第一道防线）；8080 端口识别是第二道防线；清理前照旧 PID 验身。

## Evidence

- #341 qa FAIL：keepRecent 用 Set.takeRight 取哈希序非消息序，原 spec 的 tu-1..tu-4 id 哈希序恰=插入序而巧合绿；修复后注回 bug 形态跑新 UUID spec 得到确定性红（:118 最新 UUID 被归档），恢复后 13/13 绿。
- Save-turn guard 批变异：首轮变异下全绿被当作防线失效信号，重注 + DIAG println 后同一用例 0.155s 确凿红——首轮是编译时序假象。
- 变异验证连踩两次同坑：`git checkout -- UsageTracker.scala` 把未 commit 的死日志修复批次整体还原（第二次靠新守卫脚本当场红旗抓回）；改 python 定点替换后无复发。
- #25 修复：完成通知条件化后暴露 single-immediate 分支丢 newOutstanding（counter 永卡 1）——pre-fix 完全不可见的第二个真断点，由 NestedDelegateNotifySpec 写作过程挖出。
- StopHangTurnSpec 挂 cancel：CancelProbe2Spec（bare fiber）与 CancelProbe3Spec（real actor）通过，排除 forkTurn/cancel 机制，thread dump 全 parked 确认 async suspension。
- 任务 1 sbt 挂 11+ 分钟 0.1% CPU：`grep -c OutOfMemoryError` 找到 2 hits，kill 后 `-Xmx3g` + 9 spec 子集通过。
- SavePhaseZeroToolTurnSpec 三次红→绿：根因是 zio-logging deprecated `UserAgent` alias 重载解析改变；`messages.last` 改 `messages.exists` 后 0.65s 链路稳定。
- fix/note-wire-field 验证：四面对齐（836/0/8 含 legacy compat），残留 grep 双跑零残留。
- #341 复验：全量 sbt test exit 1 但 ToolResultTtlSpec 13/13 单独绿，重跑 grep `FAILED|Passed: Total` 归因为环境性。
