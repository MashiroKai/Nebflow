# boot-smoke —— 上线冒烟门禁（工程化 M1 · 第一项 ③）

- 批次：工程化 M1（作者令 2026-10-25 13:18；按 `reports/20261025_122221_engineering-practices/REPORT.md` §4 顺序第 1 项）
- 交付物：`scripts/boot-smoke.sh`（单文件，bash 3.2 兼容，无新增依赖）
- 验证对象：任意给定 stand assembly jar
- 状态：**已完成并实测通过**（证据见 §4）

## 1. 交付面（对任务书五点的逐条落实）

| 任务书要求 | 实现 |
|---|---|
| ① 隔离启动 | 数据根 = `mktemp -d`，三通道钉住：`--home`（CLI 最高优先）+ `STAND_HOME` + `NEBFLOW_HOME`（env 兜底，防 launchd 式旧链路误读）；启动 cwd = 临时根本身（boot-OOM 归因实验的死亡因子 `CWD=/Users/kaiyu` 在结构上不可复现）；沙箱高位端口默认 **8089**（`--port` 可参数化）；命令面与生产一致：`java -jar <jar> start --port <p>`（附生产同款 `--add-opens`，另加 `--no-browser` 防冒烟实例在作者机器弹浏览器）；`NEBFLOW_DAEMON_READY_TIMEOUT_SEC` 与脚本的 boot 预算同钟 |
| ② datura 守卫 + 铁律 | 见 §2（"datura" 在仓内零命中，按可实证语义落地，假设声明见 §6） |
| ③ 健康探测 | 就绪轮询 `GET /api/health`（无令牌门，watchdog 同款），默认上限 120s、2s 步进；**boot 死亡类早期失败**：监听者出现过又消失（含 OOM 风暴秒杀形态）立即判 FAIL 并附最后 50 行日志；超时同样附 50 行 |
| ④ 深检 | portal 页 `GET /` → 200（brand 注入控制台，39124 字节级证据）；`/api/health` → 200 且载荷含 `deviceId`；**WS 真握手**：用新实例自铸的 `<home>/auth.json` token 走 GatewayAuth Bearer 轨，RFC6455 Upgrade → **101 Switching Protocols**（python3 stdlib 最小握手客户端，内联 heredoc，无新依赖）。降级路径：python3 缺失 / token 缺失 / 握手非 101 → 改探第二接口 `/api/health/loopback-info` → 200，输出行明写 `degraded` 与覆盖面 |
| ⑤ 定时拆除 + 输出 | `trap teardown EXIT/INT/TERM`（信号路径显式 exit code，复用 smoke-headless 的 zsh/bash 教训）；teardown 幂等（`TEARDOWN_DONE` 闸）：验身-杀监听者（TERM×10 轮→KILL）→杀 launcher 残留→`wait` 收尸→端口复查→临时根删除（`--keep-home` 可保留取证）；输出单行结论 `BOOT-SMOKE: PASS …` / `BOOT-SMOKE: FAIL stage=<…> reason=<…>` + `[smoke]` 证据行；**exit 0/1**（refusal 同样归 1，严格兑现 release.sh 可消费契约） |

## 2. 铁律的落地：三层守卫（绝不动在产 6138/PID）

1. **入口拒绝**：`--port 6138`（在产端口）、`--port 8080`（宿主 gateway）、`<1024`（特权端口）一律 FAIL exit 1，带具名理由；沙箱端口已被占（不论被谁）一律拒绝、绝不收编外来实例（沿用 e2e-onboarding-v2 的前置闸教训）。
2. **never-kill 台账**：preflight 采集三类 PID 入账——在产 6138 监听者、宿主 8080 监听者、`~/.stand/nebflow.pid` 内容。任何 kill 动作前先对账，命中台账即拒杀并报告。
3. **kill 前验身**（isolated-smoke-verification SKILL §2）：目标 PID 必须 cwd == 本冒烟临时根，或 cmdline 携带临时根/jar 路径，二者皆不成立则**只报告不杀**（`GUARD: … NOT signalled`），宁可留证也不误伤。

## 3. "datura 守卫" 的解释（任务书术语 → 仓内实证语义）

全仓（src/docs/tools/scripts/reports/`~/.stand/bin`/launchd/Spotlight）对 `datura` 零命中（本批只读取证）。按可实证语义落地为两层：
- **jar 内置隔离守卫**（`EnrollGuard`，`src/main/scala/nebflow/neblink/NeblinkEnrollment.scala:33`）：数据根被重定向时自动生效，冒烟实例拒绝自动注册生产 neblink 网络（boot-OOM 报告 E7 实验同款）；本脚本的数据根三通道钉法正是它的触发条件。
- **脚本层守卫**：§2 的三层（入口拒绝 + never-kill 台账 + 验身杀）。
若作者另有所指（外部 watchdog 之类），本脚本的守卫面与其正交，可后补挂接。

## 4. 验证证据（真实运行，2026-10-25）

对象 jar：`~/.stand/bin/versions/2026.10.21-beta.2/stand-assembly-2026.10.21-beta.2.jar`（作者令指名的"活过的构建"）。

四连跑一次完成（脚本最终版；全量原文：同目录 `battery-output.txt`）：真 jar PASS ×2 幂等 + 坏输入 FAIL + 铁律拒绝 FAIL + 残留清零扫描。（开发期另跑过两轮同形 battery：v1 首轮即四判全对但暴露一个 Darwin sed 坑（§6.3），v2 复证——最终证据以本轮为准。）

### 4.1 RUN A″ —— 真 jar 首跑（expect PASS → **PASS, exit 0**）

```
[smoke] jar=/Users/kaiyu/.stand/bin/versions/2026.10.21-beta.2/stand-assembly-2026.10.21-beta.2.jar (105M )
[smoke] port=8089 timeout=120s java=/Users/kaiyu/.stand/jdk-21/bin/java
[smoke] never-kill ledger: 1105 88102 52985
[smoke] home=/var/folders/.../stand-boot-smoke.als31O (isolated data root; cwd of the boot = the home itself)
[smoke] ready: GET /api/health -> 200 after 4s
[smoke] portal: GET / -> 200 (39124 bytes)
[smoke] health: payload carries deviceId (own instance identity)
[smoke] ws: handshake HTTP/1.1 101 Switching Protocols (RFC6455 101, bearer token)
[boot] stand started (pid 12376) — https://mystand.space/portal?device=4fbdbce3-…
[smoke] teardown: port 8089 free
[smoke] teardown: isolated home removed (/var/folders/.../stand-boot-smoke.als31O)
[smoke] no leftover processes on :8089; temp root cleaned (idempotent — safe to rerun)
BOOT-SMOKE: PASS jar=stand-assembly-2026.10.21-beta.2.jar port=8089 boot_ready=4s checks=11 (portal+health+ws-or-degraded+teardown)
EXIT_A=0
```

要点：冷数据根 4 秒就绪；WS 握手是**真 101**（token 来自冒烟实例自铸的 auth.json，非降级）。never-kill 台账（跑前建立）：`88102` = 宿主 gateway（:8080，本 Nebflow 实例）、`52985` = `~/.stand/nebflow.pid` 内容、`1105` = QQ——它当时有一条任一端带 6138 端口的 TCP 连接，被台账的**故意过含**读法（`lsof -ti tcp:6138`，不带 -sTCP:LISTEN）收进来；台账宁滥勿缺，方向安全（最坏是拒杀一个本来也不该杀的东西）。与台账相对，**kill 集与占用判定一律用 `-sTCP:LISTEN` 精读**（只有真监听者进 kill 集/触发拒绝）——否则无关 app 的偶发连入会让 preflight 误拒沙箱端口。

### 4.2 RUN B″ —— 幂等连跑（expect PASS → **PASS, exit 0**）

```
[smoke] home=/var/folders/.../stand-boot-smoke.<新随机串> (isolated data root; …)
[smoke] ready: GET /api/health -> 200 after 4s
[smoke] ws: handshake HTTP/1.1 101 Switching Protocols (RFC6455 101, bearer token)
BOOT-SMOKE: PASS jar=stand-assembly-2026.10.21-beta.2.jar port=8089 boot_ready=4s checks=11 (…)
EXIT_B=0
```

同一端口、紧接首跑：第一次的拆除没有留任何尸体，第二次照样全绿——幂等成立。

### 4.3 RUN C″ —— 故意坏输入（不存在路径，expect FAIL → **FAIL, exit 1**）

```
BOOT-SMOKE: FAIL stage=preflight reason=jar not found or unreadable: /nonexistent/stand-assembly-bad.jar
EXIT_C=1
```

preflight 即拒：JVM 未启动、无临时根残留（teardown 行确认端口空、根已清）。

### 4.4 RUN D″ —— 铁律拒绝（--port 6138，expect FAIL → **FAIL, exit 1**）

```
BOOT-SMOKE: FAIL stage=preflight reason=refusing port 6138 = production port (iron rule: smoke never touches :6138)
EXIT_D=1
```

### 4.5 收尾扫描（零残留 + 在产无扰）

```
-- port 8089:                (no 8089 listener)
-- temp homes:               (no temp homes)
-- production listeners:     java 88102 … TCP *:8080 (LISTEN)   ← 唯一在产监听者，全程未被触碰
```

四跑共计起停 2 个完整实例（A″ pid 12376 / B″ 随后一个），各自被本脚本按验身-杀 + 端口复查拆除；对宿主 gateway（PID 88102, :8080）零信号。

## 5. 覆盖面声明

- **已覆盖**：进程能起、端口能听、HTTP 面全通（portal `/` 200、`/api/health` 200）、认证面活着（自铸 token + Bearer WS 握手 101）、拆除零残留。这一组正是 boot-OOM 事故类（起不来/起而暴毙）的拦截面。
- **未覆盖**（boot smoke 的边界，非缺陷）：LLM provider 真实回合、neblink 对外链路、插件/技能装载深度——那些是 e2e 冒烟（`smoke-headless.sh` 等）的职责。M2 的 release.sh 只应把本脚本当**上线前必要条件**，不是充分条件。

## 6. 假设与边界条件（显式声明）

1. "datura 守卫" 解释为 jar 内置隔离守卫 + 脚本三层守卫（§3），仓内无该名词的任何实体。
2. 默认堆参数（不加 -Xmx）：与生产启动形态一致；boot-OOM 的修复面（遍历守卫）在 beta.2 之后的构建里，本冒烟对 beta.2 通过恰好证明"新数据根 + 新 cwd"下旧构建也安全——与 boot-oom-attribution 报告的归因一致。
3. 机器本机 sed = Darwin 25.4 BSD sed 存在解析 bug：`s/<re>/p`（空替换+p 旗标）被误判 newline-in-pattern；脚本内已用显式空替换 `s/^n//p` 绕开并留注释。后续脚本写手注意。
4. `lsof -ti tcp:<port>` 不带 `-sTCP:LISTEN` 时匹配**任一端**带该端口的 socket（本批实测捕到 QQ 的一条偶发连入）——台账读法故意保留过含（宁滥勿缺）；kill 集/占用判定一律加 `-sTCP:LISTEN` 精读（§4.1）。
5. `--no-browser` 为冒烟专用附加旗标（生产 `bin/stand` 不带）；其余命令面与生产逐字一致。

## 7. 文件清单

- `scripts/boot-smoke.sh` —— 唯一交付脚本（辅助逻辑全部内联：WS 握手 python3 heredoc、teardown、台账）
- `reports/20261025_133204_boot-smoke__delegate-nebflow-282811e8/REPORT.md` —— 本报告

## 8. 后续衔接（下一棒 M2）

release.sh 封装：`scripts/boot-smoke.sh <freshly-built.jar> --port 8089 && …`，以 `BOOT-SMOKE: PASS` 行与 exit 0 为闸。本脚本对坏 jar/占用端口/在产端口均已给出稳定 exit 1 与单行 FAIL 结论，可直接 gate。
