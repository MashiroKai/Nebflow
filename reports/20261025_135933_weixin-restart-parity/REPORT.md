# 修复批 · 微信腿两件：引擎默认网关端口 8080→6138 + side-car 入站退避

## 结论（一句话）

10-23 的「默认改 6138」裁定只落在 ops 显式传参、引擎代码默认值没跟——本批把引擎默认网关口收拢到**单一来源** `Defaults.GatewayPort = 6138`（绑定/CLI/doctor/prompt 兜底/热更回滚/构造兜底/neblink 对端口兜底/钩子 URL 兜底全部引用它），并给 msgloop 入站投递失败加上**指数退避**（1s→2s→…封顶 60s+向上 jitter，成功即复位，只退避「不可达」，对答得上的引擎不退避）——两件合起来，下次重启后作者裸 `sbt run` 与微信 side-car 天然同口，引擎宕窗内不再出现每秒多条 `tcp_refused` 狂轰。

## 事故链（2026-10-25 复盘）

**① 端口分叉**：作者手动 `sbt run` 起引擎 → `GatewayConfig.DefaultPort`（时值 8080）生效 → 微信 side-car 投递目标 `127.0.0.1:6138/api/social/channels/weixin-ilink/ingress`（引擎 autobind 注入的 env，`WeixinBindAutoWire.ingressUrl`）全部 tcp_refused → 微信入站全断。ops 面（`ops/nebflow-autostart.sh:10`、`ops/nebflow-restart.sh:92`、`ops/bin/stand:35`）10-23 起已显式 6138，唯独引擎代码默认值留在 8080——裸启动即分叉。

**② 退避缺失**：引擎不可达窗内，msgloop 投递失败仅 `break` 出消息循环、**无任何 sleep** 直接进下一轮——cursor 未前进 ⇒ iLink 侧积压消息让 `getUpdates` 立即返回 ⇒ 投递立即再失败。13:29 起日志呈**每秒多条** `ingress: tcp_refused — fetch failed`（fixture 腿同病：失败后只睡固定 1s，对死 seam 1Hz 狂敲）。

## 根因（文件:行号）

- **①** `src/main/scala/nebflow/shared/Defaults.scala`（本批新增 `GatewayPort = 6138` 前不存在该常量）；绑定默认 `src/main/scala/nebflow/gateway/gatewayConfig.scala:10`（改前 `Port.fromInt(8080)`）；CLI 解析兜底 `src/main/scala/nebflow/cli/GatewayClient.scala`（`readPort` 改前 `.getOrElse(8080)`）。
- **②** `tools/weixin-sidecar/msgloop.mjs` `liveLoop` 消息投递段（改前 `if (!r.delivered) { allDelivered = false; break; }` 后直落 `while` 头，零等待）；`fixtureLoop`（改前失败仅 `consume=false` + 固定 `FIXTURE_POLL_GAP_MS=1s`）。

## 修复（两件）

### ① 引擎默认网关端口 8080→6138（单一来源）

新增 `Defaults.GatewayPort = 6138`（`Defaults.scala`，含事故链/边界注释），以下九处默认/兜底全部改引该常量（字面量 8080 清零）：

| # | 点位 | 语义 |
|---|---|---|
| 1 | `gatewayConfig.scala:13` `DefaultPort` | **绑定默认**（`--port` ＞ env ＞ 此） |
| 2 | `cli/GatewayClient.scala` `readPort` | CLI 全部网关面命令的端口解析兜底 |
| 3 | `cli/SystemCommands.scala`（doctor 端口读数） | 诊断显示兜底 |
| 4 | `cli/ProcessManager.scala` `resolveGatewayPort` 失败兜底 | stop 降级身份链端口兜底 |
| 5 | `agent/PromptSections.scala:672` | `nebflow.gateway.port` sysprop 缺席时 data.sh 变量兜底 |
| 6 | `core/hotupdate/Rollback.scala` `port` 缺省 | 热更新回滚复起缺省口 |
| 7 | `core/flow/FlowTreeActor.scala` `TreeConfig.gatewayPort` 缺省 | 构造兜底（生产由 GatewayMain 注真值） |
| 8 | `gateway/RestApiRoutes.scala` `faces`/`gatewayPort` 缺省 | 同上 |
| 9 | `neblink/NeblinkService.create` `serverPort` 缺省 + `gateway/PresenceRoutes.scala`/`gateway/NeblinkRoutes.scala` 对端 port 字段缺席兜底 | 对端口缺报时按舰队默认补齐 |

配套：`core/hooks/HookRunner.scala` `NEBFLOW_URL` 兜底字面量改由常量拼（活网关内 sysprop 恒预置，纯 belt-and-braces 收口）；CI/Dockerfile/README/CONTRIBUTING/ops 注释同步（CI 裸 `-s` 起引擎走的正是默认口，curl 目标随之 6138：`ci.yml` 12 处、`Dockerfile` EXPOSE+HEALTHCHECK）。

**逐个甄别后不动的 8080**（合法用途，非遗漏）：

| 点位 | 不动理由 |
|---|---|
| `cli/ProcessManager.scala:87` `ProtectedPorts = List(8080, 8097)` | dev-host 硬闸保护面：8080 让渡 9.19 自有根实例（其启动器缺省 8080），保护语义照旧。**跟进项**：对调生效后 dev fork 落 6138，是否把 6138 纳入 ProtectedPorts 属 stop 安全语义变更，且文本面与 #852（Windows 探针/stop 降级链）相邻——留给独立批，本批不踩 |
| `llm/decision/DecisionProvider.scala:277` `DefaultLayaEndpoint :8080/predict` | Laya 预测服务自有端口，与网关无关；网关让出 8080 反而消除潜在争用 |
| `WindowsProcsSpec`/`StaleProcessGuardSpec` 等测试夹具里的 8080 | netstat/命令行**解析器夹具**（真实样本），非引擎默认 |
| `Main.scala:229`、`GatewayMain.scala:352`、`StaleProcessGuard.scala:11`、`GatewaySessionStore.scala:215` 注释 | 历史事故叙事（叙述当时形态），改写即篡改史 |
| `docs/production-ops-checklist`、`docs/parallel-session-resources`、`docs/glossary` | AGENTS.md 保护清单/时点运维记录，归作者/Nebula 管（见「待办」） |
| `scripts/sandbox-poc/*` | 沙箱 PoC 一次性取证脚本（「宿主 8080 无恙」红线），时点证据不改写；沙箱默认口本就不走本常量（高位随机） |

spec 钉：`src/test/scala/nebflow/gateway/GatewayPortDefaultSpec.scala`（常量=6138、绑定默认与常量同源、端口合法域）+ `src/test/scala/nebflow/cli/GatewayClientPortSpec.scala`（readPort 兜底档=常量、override 优先级链）。

### ② msgloop 入站投递退避

`tools/weixin-sidecar/msgloop.mjs`：

- 新增纯函数 **`ingressBackoffMs(failures)`**（导出供 spec）：核步 `min(cap, base·2^(n-1))`（1s→2s→…→封顶 60s），jitter **只向上加** `+[0,25%)`——实现等待恒 ≥ 核步 ⇒ 封顶段每次等待 ≥ 60s ⇒ **持续不可达期重试频率 ≤ 1/60s**（具证言钉进 spec）。
- 新增分类器 **`isTransportFailure(status)`**：`status===0`（fetch 抛：tcp_refused/timeout/dns/tls）或 5xx 才退避；**答得上的引擎（4xx、200+hold verdict）不退避**——数据驱动（#692 哲学）：目标可达立即恢复，不可达才逐轮拉长等待，不判死。
- 接线：`liveLoop` 投递失败且 transport 类 ⇒ `ingressFailures+1` → 按表 sleep 后再进下一轮 poll；`fixtureLoop` 同类失败 ⇒ 退避 sleep 替代 1Hz 重敲。**成功即复位**（含非 transport 失败——引擎应答即可达证据，作废失效的退避表）；stop/abort 信号可打断退避睡眠（`sleep(wait, stop)`），不阻退出。
- 语义不回归：cursor 纪律（at-least-once）、403 单次换 token 梯、getUpdates 自身 2s/30s 退避、policy-drop（wx_mallory 先例）全保持原样——本批只动「失败后的等待节奏」。

spec 钉：`tools/weixin-sidecar/msgloop-backoff.test.mjs` 7 条（BO1 表形 / BO2 jitter 只向上 / **BO3 具证言：N 连败最大重试频率 ≤ 1/60s** / BO4 分类器 / BO5 事故面端到端：ECONNREFUSED⇒status 0⇒退避臂 / BO6 5xx / BO7 应答引擎不退避）。

## 验证

- **node（sidecar 全量）**：`node --test tools/weixin-sidecar/*.test.mjs` ⇒ **92/92 pass**（含本批新增 7 条；verdict/tokenfile/selfheal/media/send 既有面零回归）。BO5 的日志行逐字复现事故形态 `ingress: tcp_refused — fetch failed`。
- **sbt（新 spec + 全量编译）**：`sbt "Test/testOnly nebflow.gateway.GatewayPortDefaultSpec nebflow.cli.GatewayClientPortSpec"` ⇒ **Compile 606 + Test 831 源全量编译绿，5/5 pass**（110s，14:00:42 完成）。附证：`grep gatewayPort src/test` 零命中断言默认值的既有 spec——默认口换值无隐式依赖面。
- **检查门**：checkJs/circular-deps 门本批未触 .js 前端文件，不适用；msgloop 为 .mjs 不入 jsconfig 收集面。

## 待重启生效 + 交接项（E3 纪律）

- **待重启生效**：①⑨默认口收拢与 ②退避均需**下次引擎重启**（或换 jar）才在线上生效；重启窗归作者/Nebula 统一安排。生效前线上形态不变（8080 裸起仍会分叉）。
- **Logto allow-list**：默认口换 6138 后，`http://127.0.0.1:6138/auth/callback`（及 `/auth/logged-out`）须加入 Logto app 的 redirect/post-logout allow-list，否则默认口实例的 OAuth 登录 400（`AuthRoutes.scala` 注释已标注）。**ops 动作，非代码**。
- **保护表入表**：重启生效后建议在 `docs/parallel-session-resources.zh-CN.md` §2 与 AGENTS.md 端口保护条目补 6138（生产宿主新口）；8080（9.19）/8090/8097 保护语义不变。
- **ProtectedPorts 是否纳 6138**：见上表——独立批决策（涉 stop 安全语义，与 #852 文本面相邻）。
- **9.19 与沙箱零影响**：9.19 自有根启动器缺省 8080 不受本批影响；沙箱口=高位随机，不走本常量。

## 与 #852（Windows 探针）的边界

本批未触任何 stop 降级链/dev-host 探针**语义**（`ProtectedPorts`、`degradedIdentity`、`WindowsProcs` 解析面零改动）；`ProcessManager.scala`/`StopSupport.scala` 仅注释与 `resolveGatewayPort` 兜底常量替换——与探针批无逻辑交叠，文本冲突可安全解。
