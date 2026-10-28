# 基础设施批（两条）closeout：nebflow/stand CLI 隔离 + restart --detached 官方面

批次：2026-10-27 作者令 21:0x · 执行 delegate-nebflow-585087f7（2026-10-28 续跑收尾）
commit：`a0ffbaf68`（branch `delegate/delegate-nebflow-585087f7`，基线 a5c3e2219）
设计件：`docs/design/20261027_cli-isolation-and-detached-restart-spec.zh-CN.md`

## 一、隔离前后对照（哪条命令起哪个 home/端口）

| 命令 | 修前 | 修后 |
|---|---|---|
| `stand start` | ~/.stand + 6138（但端口/home 链回落 `NEBFLOW_*` env） | ~/.stand + 6138，只认 `STAND_HOME`/`STAND_GATEWAY_PORT`/`--port` |
| `nebflow`（PATH） | **~/.stand + 6138（病根：弃用垫片 exec stand）** | ~/.nebflow + 8080（垫片换向 → `~/.nebflow/bin/nebflow` 真入口） |
| `stand stop` | 双探针含对家 env 回落风险 | 本品牌口 ∪ 本品牌 `.pid`，身份门按品牌收窄 |
| `nebflow stop` | —（经垫片实为 stand stop，能杀 6138） | 只探 8080 ∪ `~/.nebflow/.pid`，`stand-assembly` 按外姓拒杀 |

- 入口盘点：无 zsh 别名；PATH 唯一注入 `~/.zshrc:18`（`~/.stand/bin`，installer 写）。
  **rc 零改动**，隔离由垫片换向达成（纪律：不动用户 rc）。
- 结构：`ops/bin/{stand,nebflow}` = 薄品牌壳（BRAND+ENTRY_DIR），全部实现收进
  `ops/bin/stand-cli-lib.sh`（单一代码路径，两根各部署一份）；垫片权威副本
  `ops/bin/nebflow-stand-shim`。9.19 自有根契约逐条保留（jar winsort / JDK 钉
  `~/.stand/jdk-21` / 占口拒启不静默换口）；差异两处皆升级：撞自家活实例按
  2026-10-25 批语义 already-running exit 0；新增 detached 面。
- 部署：三份 `.bak-20261028-100746` 备份后，`~/.stand/bin/{stand,nebflow,stand-cli-lib.sh}`
  与 `~/.nebflow/bin/{nebflow,stand-cli-lib.sh}` 已落 live（在产引擎 70481 未动）。
- live 冒烟：`stand version` → `stand 2026.10.26-beta.2` rc=0；
  PATH `nebflow stop --port 25432`（空口）→ `nebflow is not running` rc=0。

## 二、restart --detached / --dry-run（假点火证据）

- `~/.stand/bin/stand restart --dry-run`（对在产引擎，2026-10-28 10:08:40）：
  `~/.stand/logs/restart-detached-20261028-100840.log` —— igniter pid 85732
  `ppid_at_entry=1`、`[self-check] ppid=1 OK`、探针见真实引擎 pid 70481
  （6138 ∪ .pid 双探针、signal-permission=ok）、`igniter ancestor chain = [ 1 ]`、
  `engine pid in igniter ancestor chain: NO → 树杀不可达`、`verdict: DRY-RUN OK`。
  调用方（本 delegate 会话）本身就在引擎进程树内，点火器仍孤儿化成功 ——
  正是 2026-10-26 20:55 事故的危险形态，机制成立。
- `~/.nebflow/bin/nebflow restart --dry-run`：`~/.nebflow/logs/restart-detached-20261028-100859.log`
  —— brand: nebflow / home: ~/.nebflow / port: 8080，探针空（9.19 实例未跑）⇒
  记录「真跑语义=直接点火，无 stop 腿」，`verdict: DRY-RUN OK`。
- 两侧守卫记账 `.restart-detached.pid` 均由点火器清账；引擎 6138 存活、8080 未动。
- 真重启面（stop→空闲闸→exec java start）已实现未投产使用（作者拍板：留好不赌）。

## 三、测试

`bash ops/test/stand-lifecycle.test.sh` → **PASS=111 FAIL=0**（沙箱化零真实 daemon）。
本批顺手修套件两病：`start_fixture` 缺 `exec`（$! 是等待子壳 ⇒ case4 假败 +
fixture 孤儿泄漏根，昨泄漏 5 只已清）；pty 横幅用例未钉 TERM（TERM=dumb 假败根）。
新增 case 18–25：env 隔离行为级证明（对家变量口上挂活监听也不探不杀）、双 home
双端口 stop 互不波及、品牌收窄身份门、bare restart 逐字透传（#916 语义零改动）、
两品牌假点火全证据链、陈年守卫 WARN 覆写。

## 四、开放项

- **KAI 同步**：本批只落 Mac 面（仓 + live 部署）；KAI/Windows 侧入口如需同契约
  launcher，另批移植（设计件 §四）。
- 引擎子进程端口 env：stand 侧保留 `STAND_/NEBFLOW_GATEWAY_PORT` 双钉（应用侧
  `Branding.env("GATEWAY_PORT")` 旧名兼容 + DetachedApply 同款先例）；写≠读，不构成跨窜。
- pre-rebrand `nebflow-assembly` 老引擎对 `stand stop` 现按外姓拒杀（--force 可越），登记不追修。
