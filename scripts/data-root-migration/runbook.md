# 数据根迁移 runbook：`~/.nebflow` → `~/.stand`（#535 · 两段式）

> 日期：2026-10-19 · 段一（预拷贝+脚本+演练）已由笨笨完成 · 段二（秒切）由 Kai 在停机窗亲手执行
> 铁律：引擎重启必须 Kai 亲手；原目录**只改名、零删除**；`~/.nebflow-stable`（8090 稳定线）**永不动**。

---

## ⭐ Kai 三步操作卡（停机窗当天照抄即可）

**前置自检（10 秒）**：`ls ~/.stand/tasks-v2.json` 能看到文件（= 段一预拷贝已就位）。

### ① 停引擎（亲手）
- 在跑引擎的终端停掉（平时的方式，Stand 仓的 sbt 会话 Ctrl-C 即可）。
- 确认：`lsof -iTCP:8080 -sTCP:LISTEN` **无输出** = 停干净了。
- 8090 稳定线如果在跑，**不用管它**，全程不受影响。

### ② 跑切换脚本（秒级，Kai 执行）
```bash
cd "/Users/kaiyu/Claude code/Stand"
bash scripts/data-root-migration/finalize.sh
```
- 脚本自带守卫：8080 还在监听会**拒绝执行**（防止带电切换）。
- 做的事：rsync 增量补差 → `mv ~/.nebflow ~/.nebflow-migrated-<时间戳>`（保底改名）→ plist/配置补丁（逐个 .bak）。**绝不 reload launchd，绝不做任何删除**。
- 可安全重跑（幂等）；万一中途报错，原目录原样不动，把输出发给笨笨。

### ③ 按原方式启动引擎（亲手），只多一个 `NEBFLOW_HOME`
```bash
cd "/Users/kaiyu/Claude code/Stand"
NEBFLOW_HOME=/Users/kaiyu/.stand GATEWAY_CONN_GUARD_TUNNEL_PER_IP_CAP=4096 <你平时那套 env 原样带上> sbt run
```
- 你平时启动前export 的所有变量**原样保留**（4096 只是已知项之一，以你的 shell 惯例为准）。
- 起来后自查三件事：`lsof -iTCP:8080 -sTCP:LISTEN` 有监听；任务台账能打开、条数与迁移前一致；`~/.stand/logs/` 在出新日志。
- **④ 完事喊笨笨做核验**（笨笨跑 `verify.sh --strict` 对账 + 引擎侧读数）。

---

## 本次迁移改了什么、没改什么

### finalize.sh 补丁明细（全部先 .bak 再改；只写 patch，不 reload）
| 对象 | 改动 |
|---|---|
| `~/Library/LaunchAgents/com.nebflow.weixin-sidecar.plist` | `NEBFLOW_HOME` → `/Users/kaiyu/.stand`（作者明令；原值 `/tmp/nebflow-coldstart` 保留在 .bak） |
| `com.nebflow.gateway.plist` / `com.nebflow.memsample.plist` | 内部 `/Users/kaiyu/.nebflow` 路径 → `.stand` |
| `~/.stand/daemons.json` | 三个 daemon 的 `cwd` 路径 → `.stand` |
| `~/.stand/install-manifest.json`、`~/.stand/bin/nebflow-autostart.sh`、`bin/memsample.sh` | 同上路径改写 |

### 明确不动
- `~/.nebflow-stable`（8090 稳定线数据根）、`~/.stand/bin/nebflow`（stable 启动器，STABLE-ISOLATED 2026-10-05 隔离设计，保持原样）；
- 所有 `.bak-*`、`.disabled-*` 历史文件；`tasks-v2.json` / `tasks.json` / 会话与委托记录等**历史记录内容**（旧路径字符串是历史，不改写；finalize 只列报告）；
- 符号链接体检已完成：数据根内无指向自身旧路径的绝对软链，改名后无悬空风险。

### launchd reload（可选，Kai 自行决定时机）
plist 补丁已落盘但**未 reload**（本批纪律）。不 reload 也不影响引擎启动（引擎由你手工起）；影响的是：
- **weixin-sidecar**（KeepAlive 常驻，旧 env `NEBFLOW_HOME=/tmp/nebflow-coldstart` 仍在内存）：想让它改用 `~/.stand` 时执行
  ```bash
  launchctl bootout gui/$(id -u)/com.nebflow.weixin-sidecar 2>/dev/null
  launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.nebflow.weixin-sidecar.plist
  ```
  （会重启微信 shim，建议低峰做；`.bak` 都留着。）
- gateway/memsample 的补丁在下次注销/重启登录时自然生效，无需现在 reload。

## 段一技术偏差声明（重要）
任务书原定 `rsync -aHAX` 全量预拷贝。实测：`~/.nebflow` ≈ **212G**，容器仅剩 **37.8G** —— 全量副本物理放不下。已改为：
- **Phase A**：`cp -Rc` 逐顶层条目 APFS **clonefile 克隆**（写时共享，瞬时近零额外空间；原生保留权限/属主/mtime/xattr/ACL；对运行中引擎纯只读，逐文件原子快照）；
- **Phase B**：`rsync -aH`（系统 openrsync 不支持 `-A/-X`，自动降级；xattr/ACL 已由克隆保住）补齐克隆窗口期新建/失败文件；
- **权威同步**不变：finalize 停机后 `rsync -aH --delete` 增量收口。
代价：克隆侧不保留硬链接共享关系（不影响数据内容与正确性）。语义目标（全量、安全、可回滚）不变。

## 磁盘空间须知（切完后）
- 切换完成时新旧两份通过 clone 共享数据块，实际占用≈一份；随 `~/.stand` 新写入逐渐分叉增长。
- `~/.nebflow-migrated-<ts>` 是保底备份：**什么时候删由 Kai 决定**（建议稳定运行数日后）；脚本永远不会删它。

## 核验与回滚
```bash
bash scripts/data-root-migration/verify.sh --strict   # 30 文件 sha256 对账 + SQLite integrity + 台账读数（引擎启动后跑）
bash scripts/data-root-migration/rollback.sh          # 一条命令把名字换回来（~/.stand 保留不删）
bash scripts/data-root-migration/rollback.sh --full   # 连 plist/配置 .bak 一并恢复，再按原方式启动引擎即可
```

## 证据索引（`scripts/data-root-migration/evidence/`）
- `precopy-<ts>.summary.log`：段一预拷贝清单与对账基数
- `verify-precopy-<ts>.log`：预拷贝抽样 sha256（live 模式）+ 完整性读数
- `dryrun-<ts>.log`：/tmp 假数据四脚本全流程演练（含断言 ALL-PASS）
