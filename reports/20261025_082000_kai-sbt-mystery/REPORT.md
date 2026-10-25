# KAI sbt 启动猝死侦查档案（2026-10-25 凌晨）

## 症状
- sbt 任何调用（含 2 行最小 build.sbt 的沙盒）在项目装载前静默猝死，唯一遗言：
  `RuntimeException: Session not initialized`（sbt.Project.getOrError ← setParser 解析期 ← MainLoop 首命令分发）。
- 无 welcome banner、无 "loading project definition"、project/target 零写入——装载从未开始。
- 类加载追踪（-Xlog:class+load）：boot→Terminal 探测（jline 三连试探）→ShutdownHooks→Boot 移交后，**sbt.internal.Load$/ProjectLoading/config-classes 一个都没加载过**。

## 消除清单（全部实证排除）
| 假设 | 排除手段 |
|---|---|
| 引擎环境污染 | schtasks /it 全新登录环境照样死 |
| HOME=D:\Cadence\SPB_Data（系统级 env，Zcode 遗留） | 压回 HOME=/c/Users/Kai 照样死（但此污染本身该修） |
| boot 目录陈类/半解压 | -Dsbt.boot.directory= 全新目录（现场重下 50MB）照样死 |
| sbt 1.10.10 特异 | -sbt-version 1.10.7 全新 boot 照样死 |
| 陈年组件锁 | 删 sbt.components.lock（0 字节，mtime=10-24 22:53 最后绿编译时刻）照样死 |
| stand 构建定义坏 | 2 行 build.sbt 沙盒照样死 |
| 项目/工作树脏 | git status 干净（KAI main=a03fd0ba3） |
| 控制台上下文 | bash/cmd/detached/schtasks 四路全死 |
| JDK 变更 | java=Temurin 21.0.12.1，rt-ext jar 完好（9-14 备好），未变 |
| 磁盘/网络 | 92G 空闲；boot 重下成功 |

## 时间线
- 10-24 22:53 最后一次 sbt 绿编译（Test/compile）。
- 10-24 23:14 起（test3）全部 sbt 调用死；test3 死于命名管道锁 error 5（ServerAlreadyBooting），其后统一死法为 Session not initialized。
- 分界与作者 23:00 前后在 KAI 上做引擎恢复/重启吻合。作者原话：「我用 windows 来修你 然后不知道为什么它进程退出了 我去给它 nebflow 进程重启过一次」。

## 未走完的路（需 Windows 侧眼睛）
- 23:00 前后的系统级变更：Windows 事件日志 / Defender ASR 历史 / 远控工具（作者用什么「修」的？向日葵/RustDesk/…？其驱动/服务可影响非交互进程的命名管道与控制台 API）。
- 引擎重启后是否进了不同会话（session 0 vs 交互会话）——schtasks /it 理论上落在交互会话但依旧死，此路存疑未决。
- ProcMon 对 sbt 启动的文件/注册表/管道操作追踪。

## 快速分验建议（给 Kai 的 5 分钟实验）
在 KAI 上亲手开一个交互 PowerShell：`cd C:\Users\Kai\stand; sbt "show version"`。
- 活 ⇒ 人会话 OK、引擎孵的进程死 ⇒ 引擎启动上下文/会话问题，改引擎拉起方式即愈。
- 死 ⇒ 纯机器状态问题，查 23:00 系统变更/远控软件。

## 侦查产物（KAI 侧留档）
- /c/Users/Kai/sbt-debug-full.log（wrapper debug 全量）
- /c/Users/Kai/cl-trace.log（类加载追踪）
- /c/Users/Kai/probe-sbt.log + probe-env.txt（schtasks 全新环境证据）
- /c/Users/Kai/run-win-test3.cmd / run-win-test4.cmd + 两份死log
- 探针任务 nebflow-sbt-probe 已建（可 schtasks /delete /tn nebflow-sbt-probe /f 清除）
