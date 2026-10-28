---
type: design-note
status: done
---

# CLI 品牌隔离 + `restart --detached` 官方面（2026-10-27 基础设施批设计件）

日期：2026-10-27 · 作者令 21:0x（两条）
状态：已实现（`ops/bin/{stand,nebflow,stand-cli-lib.sh,nebflow-stand-shim}` +
`ops/test/stand-lifecycle.test.sh` 111 断言全绿）；实现批报告见 `reports/`。

相关件：[[20261026_detached-apply-restart-update-spec.zh-CN]]（#916 地基，本文承其出 `restart --detached` 官方面）、[[20261027_hot-reload-foundation-spec.zh-CN]]（同族热更新线）

## 一、nebflow 与 stand 命令彻底隔离

### 1.1 盘点结论（修前病根链）

| 入口 | 真身 | 病 |
|---|---|---|
| `stand` | `~/.stand/bin/stand`（348 行单文件 launcher，权威副本 `ops/bin/stand`） | 端口/home 解析链回落 `NEBFLOW_*` 变量（env 互窜） |
| `nebflow`（PATH 命中） | `~/.stand/bin/nebflow` = 139B「弃用→转发 stand」垫片（2026-10-22） | **跨品牌拉起的总根**：`~/.zshrc:18` 只把 `~/.stand/bin` 加进 PATH ⇒ 命令行 `nebflow …` 实际起的是 stand 6138 |
| `nebflow`（全路径） | `~/.nebflow/bin/nebflow`（2026.9.19 自有根 launcher，2011B） | 不在 PATH；契约正确（NEBFLOW_HOME 钉 `~/.nebflow`、缺省 8080、JDK 钉 `~/.stand/jdk-21`） |

`which -a`/zsh 别名：无别名；唯一 PATH 注入是 `~/.zshrc:18`（installer 写入，
纪律：**不编辑用户 rc**，隔离由换掉垫片达成，rc 零改动）。

### 1.2 隔离契约（修后）

| 命令 | home（只读自家 env） | 端口（`--port > 自家 env > 缺省`） | jar 位 | JDK |
|---|---|---|---|---|
| `stand` | `STAND_HOME > ~/.stand` | `STAND_GATEWAY_PORT > 6138` | 入口目录 `versions/` 指针 + winsort 兜底 | `STAND_JAVA_BIN > $STAND_HOME/jdk-21` |
| `nebflow` | `NEBFLOW_HOME > ~/.nebflow` | `NEBFLOW_GATEWAY_PORT > 8080` | 入口目录 `nebflow-assembly-*.jar` winsort | `NEBFLOW_JAVA_BIN > ~/.stand/jdk-21`（9.19 契约：JDK 资产在 stand 根） |

- **互不拉起**：垫片换向（`~/.stand/bin/nebflow` → `exec ~/.nebflow/bin/nebflow`）。
- **互不探活**：端口解析只认自家变量 + `--port`；对家变量在场也不读（case 19 行为级证明）。
- **互不误杀**：stop 双探针 = 本品牌端口 LISTEN ∪ 本品牌 home `/.pid`（家/口天然收窄）；
  身份门**按品牌收窄**（stand 认 `*stand-assembly*|*sbt*`，nebflow 认
  `*nebflow-assembly*|*nebflow*|*sbt*`）——对家 jar 标记按外姓拒杀（case 20/21）。
- **单一代码路径**：两入口只是品牌壳（BRAND + ENTRY_DIR），全部实现在
  `stand-cli-lib.sh`（守卫/横幅/winsort/boot-prep/detached 全在库内）；库与入口
  同目录部署两份根各一份。
- 保留面（非跨窜）：`stand start` 对**子进程**双钉 `STAND_/NEBFLOW_GATEWAY_PORT`
  同值（引擎 `Branding.env("GATEWAY_PORT")` 旧名兼容面，应用侧 `DetachedApply`
  生成脚本同款先例）；写≠读，env 进程私有，不构成跨品牌可见性。

### 1.3 兼容性裁决

- 旧 `~/.nebflow/bin/nebflow` launcher 的全部契约逐条保留（jar 位/端口优先级/JDK
  钉位/端口被占拒启不静默换口）；差异两处且皆为升级：start 撞**自家**活实例时按
  2026-10-25 批语义报 already-running exit 0（旧版一律拒启）；新增 detached 面。
- 前改版品牌横幅（2026-10-25 作者批三款）原样入库（仅 stand 品牌 + start 放行路径打印）。
- pre-rebrand 的 `nebflow-assembly` 老引擎对 `stand stop` 现按外姓拒杀（--force 可越）；
  属历史形态，登记不追修。

## 二、`restart --detached` CLI 官方面（自重启事故 2026-10-26 20:55 的 CLI 腿）

### 2.1 事故语义（作者令原文归纳）

定时器把 `stand stop && stand start` 挂在引擎进程树内（Bash 工具子进程）——定点杀
侥幸跑完，树杀（`ProcessTree.killProcessTree`，ppid 维度）必连带点火器 ⇒「停了起不回」。

### 2.2 机制（`ops/bin/stand-cli-lib.sh` RESTART-DETACHED v1）

1. **双 fork 孤儿化**：arm 层立即退出，点火器经 `( exec … ) &` 过继 launchd（ppid=1），
   脱离调用方 ppid 链——树杀够不着。
2. **点火前自检**：点火器等 reparent 稳定（≤2s）后 ppid 必须 =1，不达标 ABORT
   rc=3 且引擎分毫未动。
3. **全程日志**：`<dataRoot>/logs/restart-detached-YYYYMMDD-HHMMSS.log`（arm 头 +
   点火器 `exec >>` 追加；探针/裁决全落盘）。
4. **防双点火**：`<dataRoot>/.restart-detached.pid`——活着的 `__ignite-restart`
   在飞 ⇒ 拒绝；活 pid 非点火器（陈年记账）⇒ WARN 覆写放行；点火器 EXIT trap 清账
   （真跑 exec 前显式清，exec 不走 trap）。
5. **真跑腿**：探针（双探针并集）非空 ⇒ stop 腿（硬化 stop 同语义：TERM→10s→KILL、
   身份门、--force 可越）→ 端口空闲闸（≤10s）→ boot-prep（JDK 门 + jar 指针/winsort）
   → `exec java -jar … start`（子进程端口/家钉 env 与 start 放行路径同源）。
   探针空 = 引擎本来没跑 ⇒ 直接点火。

### 2.3 验证面 = 假点火（`--dry-run`，作者拍板：真重启面留好不拿生产赌）

`stand restart --dry-run`（隐含 detached）：arm → 孤儿点火器只写**四件证据**进日志，
不停不启：
`[self-check] ppid=1 OK`；双探针 pids + `signal-permission`；点火器祖先链；
`engine pid in igniter ancestor chain: NO` ⇒ `verdict: DRY-RUN OK`（树杀不可达，
机制成立）。判据不成立则 `verdict: DRY-RUN INCONCLUSIVE`，仍零触碰。

### 2.4 与 jar 面 #916 的关系（互补不冲突，见 20261026 设计件）

| | jar 面（`stand restart` bare） | CLI 面（`restart --detached`） |
|---|---|---|
| 分离机制 | launchd 一次性 agent 注册（需 java 可用） | 双 fork 孤儿化（零 JVM 依赖：JDK 坏/缺也能点火） |
| stop 腿 | launcher 硬化 stop（apply 脚本） | launcher 硬化 stop（同一 `cli_stop_impl`） |
| 健康闸/回滚 | 有（apply-restart.log + apply/state.json） | 无（v1：点火即交棒；日志在 restart-detached-*.log） |
| 旗标归属 | `--inline/--force/--tree` | `--detached/--dry-run`；其余参数透传 stop 腿（`--port`/`--force`） |

bare `stand restart`（含 `run --inline …`）**逐字透传** jar 面，#916 语义零改动
（case 22）。`nebflow restart --detached/--dry-run` 同代码路径（9.19 jar 无
restart 命令，bare 报错 rc=2 并指路 detached 面）。

## 三、测试与证据（`ops/test/stand-lifecycle.test.sh`，111 断言全绿）

- 修套件自身两病（本批定因）：`start_fixture` 无 `exec` ⇒ `$!` 是等待子壳而非
  监听器（case4 假败 + fixture 孤儿泄漏根）；pty 横幅用例未钉 `TERM`（套件继承
  TERM=dumb 时 case14 假败）。并补 kill 后 `wait_gone` 断言堵泄漏。
- 新增 18–25：nebflow 品牌面透传/钉 env、**env 隔离行为级证明**（对家变量口上
  挂活监听也不探不杀）、双 home 双端口 stop 互不波及、品牌收窄身份门、bare restart
  逐字透传、**dry-run 假点火全证据链**（ppid=1/树杀不可达/引擎分毫未动/零 JVM/
  守卫清账）、nebflow 品牌假点火、陈年守卫覆写。
- 沙箱纪律不变：零真实 daemon、端口 21000+ 随机段、缺省口（6138/8080）一律不进测试。

## 四、边界与不做

- 不动在产引擎进程（部署只换盘上 launcher 文件；live 证据仅 `--dry-run` 假点火）。
- 不编辑用户 shell rc（`~/.zshrc:18` PATH 原样；隔离由垫片换向达成）。
- KAI（Windows）侧同批同步：本件只落 Mac 面；Windows 入口（若有对应 launcher）
  需按同契约另批移植（登记为开放项）。
