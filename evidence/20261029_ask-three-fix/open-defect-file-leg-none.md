# 开放缺陷：ask 上下文组装「文件腿」在部分沙箱 daemon 进程中恒为 None（非本批引入，未修）

状态：**未定根因**（2026-10-29 12:4x，时间盒用尽即封存）。修复①放行 ask 链路后暴露；
非本批三修的回归（ask core 文件与 HEAD 零 diff，行为在更早的进程上同样出现）。

## 现象（确定性事实）

| 进程 | 启动时刻 | 类构建 | 提问 | 文件腿 |
|---|---|---|---|---|
| home1 pid 78336 @8299 | 11:21:01 | 10:55 编译 | 11:21「核心结论」 | **None**（答「读不了原文」，327 字） |
| home7 pid（8305 daemon） | 12:09:57 | 12:03 编译（HEAD 等价） | 12:11「KS 通过线」 | **None**（同上，178 字） |
| home7 同进程第二问 | — | 同上 | 12:40「Φ 是多少 MV」 | **None**（同进程持续失败） |
| home8 pid 8467 @8306 | 12:22:07 | 12:22:03 编译 | 12:22「KS 通过线」 | **正常**——逐字引用原文「验证以 KS 统计量 < 0.01 为通过线」（msgs=2，零工具调用，空 memory-db） |
| 离线 JVM（sbt Test/runMain，12:33） | — | 12:22 编译 | 直接调 assemble | **正常**——fileStat=Some、fileText=Some(323)、sources=List(文件)、block 含全文 |

要点：同代码、同夹具（/tmp/ask-live-fixture/能量谱抽样说明.md，323 字节，mtime 11:20）、
同客户端、同问题，**按进程非确定**（None 进程内持续；正常进程逐字引用）。None 时模型
引用的是 AskContextAssembler.scala:130-131 的占位文案「文件暂不可读」（transcript thinking
原文证实其 system prompt 即该占位块）。

## 已排除（均有直接证据）

1. **类路径陈旧**：jcmd VM.command_line ×3——daemon classpath = worktree
   `target/scala-3.5.2/classes`（92563 启动 11:48:14 晚于 11:41 探针编译；8467 同）。
2. **装配 stub / 接线缺失**：GatewayMain.scala:1609 实装 `AskLiveSources.make`，无第二实现。
3. **路径畸形**：`<home8>/ask/index.json` 绑定路径逐字正确（/tmp/ask-live-fixture/能量谱抽样说明.md）。
4. **locale / sun.jnu 编码**：daemon 8467 `sun.jnu.encoding=UTF-8`（jcmd 实测）。
5. **seatbelt / sandbox-exec**：无——`--daemon-child` 为普通 JVM 严格端口闸（Main.scala StrictChild）。
6. **fileText 内部异常被吞**：HEAD 代码 throw 时会 `logger.warn("ask file read failed")`——
   home1/home7 日志 0 条；且离线直跑同一代码全绿。
7. **logback 静音**：logback.xml 无 `nebflow.ask` 级别覆盖，root INFO，WARN 应出。
8. **记忆/图谱腿串扰**：home8 memory-db 0 sessions 0 messages；回复内容来自逐字引用，
   非检索拼凑。

## 探针失效的教训（下一步的必要输入）

TEMP-DIAG 探针在 daemon 与离线 JVM **均未输出**——字节码级查证：12:22:03 的 zinc
增量输出**内部不一致**（`AskLiveSources$$anon$1.fileText` 方法体只有
`IO.blocking(invokedynamic)` 转发、无任何 logger 调用；探针串仅在常量池/伴生类）。
前置一次 **failed compile（12:21:07，NonFatal isInstanceOf 笔误）** 后的增量状态不可信。
⇒ 复查此缺陷必须 `sbt clean compile` 全量构建 + 新 home 实例 + **println 直写 stdout**
的探针（绕开一切日志配置），勿再依赖增量编译产物。

## 未排除的 suspect（下一步 first shot）

worktree 长会话增量编译链（10:55→12:03 两次「正常」编译均伴 None 进程；12:22
failed-compile 后的重编进程反而正常）——怀疑增量产物跨源不一致使 daemon 装载了
旧语义的 fileText 路径。**在产实例（作者终端 zh_CN.UTF-8 启动）从未出现本现象的
报告；本缺陷对交付代码是否成立，应以 clean 构建的新实例复测为准。**
