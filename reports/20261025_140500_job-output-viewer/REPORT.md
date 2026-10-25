# 后台任务输出查看补全批 · REPORT

日期：2026-10-25 ｜ 作者令（逐字）：「这个截图是我们后台任务的进展查看面板，现在就是远端任务看不到，本地任务细节好像也看不到。」（附图：远端任务详情卡空白 + 占位「远程任务暂不支持输出查看」，任务 = [KAI] KAI 全量 test5）

## 1. 需求 → 落地对照

| # | 需求（作者视角） | 落地 | 位置 |
|---|---|---|---|
| 1 | 本地后台任务：详情面板可见实时进展（stdout/stderr 合流尾窗、运行时长、完成 exit code） | **批前已具备**（2s 轮询尾窗 + 已运行时长 + exit chip，#753 族）；本批补**落盘兜底**：留存区（LRU 20）淘汰 / 引擎重启后的旧任务照样可回看（read 落盘兜底），subtitle 增补落盘路径展示 | `BgTaskOutputStore.read` 落盘兜底 / `bgTaskOutputPopup.js` renderStatus |
| 2 | 远端设备任务同样可见；参考路径 = 命令重定向的对端日志文件（test5 的 `/c/Users/Kai/stand-win-test5.log`），轮询拉尾即可 | 全链路：① 命令文本解析 outputPath（quote 感知，`RemoteBgOutputLog.parseRedirect`）→ job 记录（`BgTaskRegistry.ActiveTask.deviceName/outputPath`）；② 新 REST `GET /api/bg-tasks/:id/remote-output` 按 outputPath 经**既有**设备通道拉对端日志尾窗（`RemoteExecutor.readBgLogTail` = 对端 Bash `tail -c 64K`，P2P/relay dispatch 与远端命令执行同一族，零新 wire、零新权限口子；1.5s 单飞缓存防轮询叠加）；③ 前端 remote 模式 ~3s 轮询整窗替换渲染；④ 终态时引擎把 HTTP 拿到的全量输出**灌账**进 BgTaskOutputStore（fillRemote 先于 WS completed 帧）→ 详情卡终态回看有内容 + exit 码（只认对端回执末尾 `(exit N)` 标记，解析不到不虚报） | `RemoteBgOutputLog.scala`（新）/ `RemoteExecutor.scala` / `BgTaskRegistry.scala` / `PresenceRoutes.scala` / `bgTaskOutputPopup.js` |
| 3 | 「暂不支持」占位 → 真实内容或明确的「输出不可用」+ 原因 | 四态分叉（zh-CN/en 同步新增 6 个 locale key，删除旧 `bg.detail.remote`）：拉取中（`正在从设备KAI拉取输出尾窗…`）；无 outputPath = 永久态明示「命令未检测到输出重定向（需类似 `> run.log 2>&1` 落日志文件）」且**停轮询**；设备不可达 = 暂态展示原因 + 续轮询（预算内）；预算耗尽 = 「设备可能离线」。全程无「暂不支持」措辞 | `bgTaskOutputPopup.js` startRemotePoll 族 / `locales/zh-CN.js` / `locales/en.js` |
| 4 | job 框架本地侧未持久化输出 → 补上（输出落盘 + 记录路径） | `<dataRoot>/bg-task-logs/<jobId>.log`（行同步落盘，32MB/件 cap + truncated 记账）+ `<jobId>.meta.json`（终态原子写：状态/exit/行数字节/origin）；sweep 成对保留最近 128 对。开启点 = `Main.run`（数据根定案后一次性；**CLI 路由与 spec 不开启 = 纯内存，零测试污染**） | `BgTaskOutputDisk.scala`（新）/ `BgTaskOutputStore.scala` / `Main.scala:115` |

## 2. 关键设计决策

- **拉尾走对端 Bash `tail -c`，不走 FileTransfer get**：日志可无限增长，`direction=get` 是整件回拉（10MB 闸）；`tail -c 64K` 有界且 BSD/GNU tail 两侧同支持。单引号包裹 + `--` 防对端 shell 二次解释（内嵌 `'` POSIX 转义）。
- **重定向解析是启发式、解析不到不猜**：quote 感知扫描（`echo "a > b"` 不误判）；stdout 类末位胜出（shell last-wins）；`2>&1`/`>&2` fd 复制、`/dev/null`/`NUL`、`$VAR` 目标一律判「无路径」→ 面板走原因降级。
- **落盘层独立成 `BgTaskOutputDisk` 纯文件面**（temp dir 注入可测），store 只做接线；writer 由 BashTool 的 `lineSink` 复合口（内存 append + 盘写）在读取线程直连，无 Ref 查表开销。
- **终态灌账时序**：fillRemote → unregister → WS completed——前端详情卡对 remote 的终态首拍 /output 即命中留存，与 404 回退路径双保险。
- **别动在产进程**：全部为增量面（新路由/新文件/前端 remote 分叉），无 schema/迁移；作者下次重启自然吃上。

## 3. 验证

- **Scala**（sbt testOnly，47/47 PASS）：
  - `RemoteBgOutputLogSpec`（17 例：作者实证 test5 形态锚定、quote/fd/devnull/IO-number/tee/last-wins、tail 命令转义、exit 标记末尾判据）；
  - `BgTaskOutputDiskSpec`（7 例：cap 停写、meta 原子 round-trip、readSlice 字节游标行对齐、sweep 成对保留、store 级 fillRemote+落盘兜底 round-trip）；
  - `BgRemoteOutputRoutesSpec`（6 例，wire 级：/output 来源面字段、/remote-output 四臂——404/not-remote/noOutputPath/noExecutor 降级、403 鉴权）。设备腿「测试桩」= 不初始化 RemoteExecutor 单例的降级臂逐臂断言；真设备回环属集成面（见 §4 开放项）；
  - 回归：`BgTaskRegistrySpec`（10）/ `DeviceBgTaskSpec`（7）PASS；
  - 二批回归（40/40 PASS）：`BashAutoBackgroundSpec` / `BashPersistentBgTaskSpec` / `BashBackgroundHardTimeoutSpec` / `BgRateGateSpec` / `BgNotifyDeliverySpec` / `NoderptVerifyProbeSpec`（open+read 消费面）/ `ExternalOutputStoreSpec`（同构输出面对照）。
- **前端**（playwright，10/10 PASS）：新 `tests/bg-task-output-viewer.spec.mjs` 4 例——T1 远端拉尾真实内容+路径+运行光标+轮询在续；T2 无路径真实原因+无「暂不支持」+首拍停轮询；T3 终态 404→/output 收口（内容+exit+已完成）；T4 本地增量游标回归。面板族回归 `bg-device-origin-chip`（3）/ `bg-merged-panel`（3）PASS。
- **构建**：`npm run build`（esbuild bundle + 194 lazy chunks + 类型闸）OK。

## 4. 开放项 / 边界

- **真设备端到端**（真实 KAI 对端跑一次带 `> log 2>&1` 的远端后台任务并点开面板）未做——需要作者在产实例侧顺手一验；链路各段（解析/REST/前端渲染/设备 dispatch）已分段验证。
- 引擎重启后**运行中**的远端任务不可恢复（registry/store 均进程内存态）——面板行随重启消失，属既有生命周期语义，本批未扩展。
- 落盘 sweep 上限 128 对（约至多 128×32MB 理论上界；实际日志 KB–MB 级）。若作者要更长回看窗，调 `BgTaskOutputDisk.KeepPairs` 即可。
- remote-output 对 `persistent` 设备任务同样生效（长驻 dev server 日志可持续拉尾）。

## 5. 文件清单

新增：`src/main/scala/nebflow/core/tools/RemoteBgOutputLog.scala`、`src/main/scala/nebflow/core/tools/BgTaskOutputDisk.scala`、`src/test/scala/nebflow/core/tools/RemoteBgOutputLogSpec.scala`、`src/test/scala/nebflow/core/tools/BgTaskOutputDiskSpec.scala`、`src/test/scala/nebflow/gateway/BgRemoteOutputRoutesSpec.scala`、`tests/bg-task-output-viewer.spec.mjs`。

修改：`BgTaskOutputStore.scala`（origin/落盘接线/fillRemote/落盘兜底读）、`BgTaskRegistry.scala`（ActiveTask+deviceName/outputPath、find）、`RemoteExecutor.scala`（outputPath 解析登记、终态灌账、readBgLogTail）、`BashTool.scala`（origin+lineSink）、`Main.scala`（boot 开落盘）、`PresenceRoutes.scala`（/output 加宽 + /remote-output 新臂 + 单飞缓存）、`bgTaskOutputPopup.js`（remote 轮询模式）、`locales/zh-CN.js`、`locales/en.js`。
