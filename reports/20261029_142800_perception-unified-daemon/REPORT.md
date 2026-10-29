# 感知进程形态改造：统一 daemon 管理进程（任务令 2026-10-29 13:02）

> 作者裁定逐字：「独立daemon吧 正好和我们心跳进程合并 那就是管daemon的」。
> 红线变更：**推翻 2026-10-26 02:00「撤销心跳与感知合并」裁定**（7a91a3253 还原件），
> 本批按新裁定执行；其余红线照旧（零超时设计、在产零触碰、不 push）。
> 基座：感知缺口批 `delegate/delegate-nebflow-c58a5773`（c30d88ed7+c3e3d806b，
> spec 47/47+16/16）——本分支自该分支并出（merge commit 3d81c0192），其 main 合并
> 由收尾批负责；本批 merge 入 main 时自然包含其 commits（收尾批「已在/需并」判定）。
> 设计件：`docs/design/20261029_132723_perception-unified-daemon__delegate-nebflow-0b171af0.zh-CN.md`。

## 一、改造前后对比

**改造前（折中态）**：感知五循环（poll/sweep/digest/spool/idle）+ MailImportanceJudge
以引擎进程内 fiber 形态挂在 GatewayMain 启动链 `perceptionBoot` 腿；心跳（daemon
管理 = `#daemon-btn`「心跳进程」面板 + DaemonService）只管用户 dev server，与感知
互不相干。r3 报告 P2-1 记录为「需作者再裁」。

**改造后（统一 daemon 管理进程）**：

```
引擎进程 GatewayMain ──DaemonService 声明托管（autoStart/restartOnExit/端口健康
  探针/pid marker/stopAll——现行心跳语义零改动）──→ 统一 daemon 管理进程
  PerceptionDaemonMain（独立 JVM）
    职责A：感知五循环 + MailImportanceJudge 判定逻辑（裁决解析在 daemon）
    职责B：自心跳 watchdog（30s 拍）+ /health 健康面（token 门）
通道：引擎 /api/perception/* → 逐字代理（会话门照旧）；EmailSend/AskUser 开窗 →
  token 门工具腿；daemon → 引擎 ingest 三腿（deliver/broadcast/llm，token 门）。
```

一句话拓扑：**引擎拉起统一 daemon（DaemonService 声明托管）、并管它的一生
（watchdog/健康面/随引擎起停）；统一 daemon 管感知五循环 + 自心跳，回投递/WS/
LLM 三腿入引擎。**

## 二、提交清单（本批 commit 与要点）

| 文件 | 要点 |
|---|---|
| `nebflow/perception/PerceptionDaemonMain.scala`（新） | 统一 daemon 进程入口：`--home/--port/--engine-port`；logback 编程式隔离（`logs/perception-daemon/logs/stand.log`，引擎 stand.log 单写者不破）；token fail-fast（exit 2）；五循环 boot + 自心跳 watchdog（30s 拍，IO.defer 防构造期 SOE——sweepLoop 同案） |
| `nebflow/perception/PerceptionEngineLeg.scala`（新） | daemon→引擎出域三腿：deliver（不可达=false⇒spool）/broadcast（3 次退避）/llmJudge（邮件面 transport，裁决解析留 daemon，40s 界>判定 30s 界）；kill-switch spawn 时 `-D` 透传 |
| `nebflow/perception/PerceptionPanelRoutes.scala`（新） | 面板 API 权威处理表（自 PerceptionRoutes 整体迁入，形状逐字节）——单源实现防双端漂移 |
| `nebflow/perception/PerceptionDaemonRoutes.scala`（新） | daemon HTTP 面：token 门（常数时间比较）+ /health 健康面 + 面板表挂载 + 引擎工具腿（has-account/create-draft/attach-request） |
| `nebflow/perception/PerceptionClient.scala`（新） | 引擎→daemon 客户端：endpoint Ref（dock 时 bind）；面板代理无人工超时；工具腿 5s 有界优雅降级；probeHealth |
| `nebflow/gateway/PerceptionDaemonDock.scala`（新） | dock 腿：token 0600 落盘（每 boot 轮换）+ 托管条目 upsert（id=`perception-daemon`，autoStart/restartOnExit/port 健康探针；用户条目零触碰）+ 命令面拼装（jpackage 形态拒 spawn，同 DaemonSpawn 判据）+ /health 握手（10s 有界 WARN-only） |
| `nebflow/gateway/PerceptionIngestRoutes.scala`（新） | 引擎 ingest 三腿承接（token 门非会话门）；deliver=原 injectEnvelope 引擎半边（resolveRoots+ImmediateInput 回引擎） |
| `nebflow/gateway/PerceptionRoutes.scala`（改） | 面板 API → 逐字代理（preset 保持引擎本地：纯函数，daemon 停机窗向导仍可用）；会话门 403 形状逐字 |
| `nebflow/gateway/GatewayMain.scala`（改） | 旧 `perceptionBoot` 腿删除；dock 两半腿（prepare→autoStart→handshake）进既有 2s 延迟 daemon 腿；stopAll/JVM hook 既有退出清理零改动（daemon 随引擎走） |
| `nebflow/core/tools/PerceptionMailTool.scala`（改） | EmailSend 三腿（hasAccount/createDraft/attachRequest）走 PerceptionClient；错误文案逐字 |
| `nebflow/core/tools/AskUserQuestionTool.scala`（改） | OTP 开窗腿走 PerceptionClient（5s 有界，停机窗静默跳过——原「失败不阻塞 ask」契约） |
| `nebflow/perception/PerceptionService.scala`（改） | PerceptionCore 去引擎耦合：`resources` 参数移除（恒未触达）+ `injectEnvelope` 的 resolveRoots 进程内直调改为 `deliverEnvelope` seam——本类对引擎零编译期依赖（进程可移植） |
| `nebflow/gateway/RestApiRoutes.scala`（改） | ingest 腿挂载于感知代理腿之前（前缀先到先得） |
| `GLOSSARY.md`（改） | 感知条目更新为统一 daemon 管理进程（裁定变更如实记录，推翻字样保留） |
| spec：`PerceptionCoreSpec`/`MailClientImapSpec`（改） | 构造点 seam 同步（`deliverEnvelope` 桩），47 基线零语义变化 |
| spec：`PerceptionDaemonLifecycleSpec`（新） | 命令面形状钉/jpackage 拒 spawn/token 0600+轮换/条目 upsert 旗标+用户条目零触碰+幂等/握手 dock+超时 WARN-only/**真实起停**（真 JVM：/health 200+403 对照+stop 收尸）7/7 |

## 三、验证读数

| 面 | 结果 |
|---|---|
| scala 感知族 + daemon 族 + AuthGate + DaemonSpawn | **153/153 通过，0 失败**（7 ignored = DaemonSpec 既有真进程族，非本批引入；含 PerceptionCoreSpec 基线 47/47 零回退） |
| playwright 感知三脸（tests/mail-perception.spec.mjs） | **16/16 通过**（前端零触碰自证；`git diff --stat HEAD -- src/main/resources/web/` = 空） |
| checkJs | **252 < 260 基线，零新增** PASS |
| sbt compile / Test/compile | rc=0 |
| sbt assembly | rc=0，`stand-assembly-2026.10.26-beta.2.jar`（hash 193eb39696ab3220f501d48b52162f09656419b9） |
| **LIVE 冒烟**（隔离实例，boot-smoke 家族纪律） | **PERCEPTION-DAEMON-SMOKE: PASS** boot_ready=4s checks=11111111（8/8）：引擎 boot→dock 握手线→daemon 家族 marker（pid 存活）→daemon /health 200（token 门 403 对照）→**感知五循环 live 日志**（poll/sweep/digest/spool/idle boot 行 + serving 行）→**心跳腿 live**（/api/daemons 托管行 running+portOpen=true）→面板代理 200→**干净退出零孤儿**（引擎 SIGTERM ⇒ daemon 随引擎走，端口释放）。证据：`live-smoke.log` |

## 四、红线合规

- **在产零触碰**：冒烟走 mktemp 数据根 + 沙箱 8091（6138/8080 拒绝 + never-kill
  台账 + PID 身份三重验证）；全程未触 :6138。
- **零超时设计**：面板代理逐字转发无人工超时（daemon 侧操作自持既有界：L1 判定
  30s 等）；工具腿 5s 有界 + 优雅降级（不阻塞 agent 轮）；dock 握手 10s 有界
  WARN-only（原 perceptionBoot「失败只 WARN 绝不阻塞启动」契约逐字）。无新增
  用户可见超时。
- **UI 零触碰**：前端五件（`#daemon-btn`/daemons.js/perception.js/index.html/
  perception.css）零字节改动。
- **不 push**；merge --no-ff 入 main。

## 五、开放问题（不擅自改，列报作者）

1. 面板行对「感知守护进程」的 stop 语义 = 任意 daemon 同款（面板停 → 引擎重启
   后 autoStart 拉回）。若需「停用后不再自启」的持久开关 → 另批。
2. 若作者「合并」实指 DaemonService 本体搬出引擎进新进程（/api/daemons 改代理），
   与「心跳照旧」冲突且回归面大，本批未采（现行=心跳机制零改动 + 感知 daemon
   归族托管）。如需 → 另立批。
3. OTP 开窗时 daemon 恰处停机窗（秒级）：该 ask 无窗，验证码走 L1/白名单/spool
   既有兜底（watchdog 秒级拉回）。
4. OTP 开窗 reason=answered 缺失（P2-2 遗留）与引擎-daemon 拆分无涉，原状保留。

## 六、重启后生效面标注

- **引擎重启**：感知 daemon 随引擎走（现行心跳语义）——干净停机 stopAll；异常
  死亡 ⇒ 下次 boot reclaimStaleDaemons 按 marker+startTime 回收重启。生效 =
  引擎重启后感知域自动恢复，无需手工。
- **token 每 boot 轮换**：`<dataRoot>/perception/daemon.token`（0600）。
- **日志面**：daemon 自有日志 `<dataRoot>/logs/perception-daemon/logs/stand.log`
  （引擎 stand.log 单写者不破）；daemon stdout 入面板 recentOutput（面板健康面）。
- **健康面**：/api/daemons 托管行（running/crashed + portOpen 探针）+ daemon
  /health（token 门）。
