# C 盘点：launchd 标签 / plist 与在役服务残余 nebflow 面（只盘点不动手）

日期：2026-10-20 12:42（更名收尾批 v2 · C 项）。范围：本机（Mashiros-MacBook-Pro）`~/Library/LaunchAgents/` 与在役进程中的 nebflow 残余面。**纪律：纯只读盘点，零修改；8787 微信 sidecar 零触碰（未读其 plist 全文，仅确认标签与存活）。**

## 1. launchd 清单

| 标签 / 文件 | 状态 | 程序面 | 残余 nebflow 面 |
|---|---|---|---|
| `com.nebflow.gateway`（com.nebflow.gateway.plist，4 处） | 已加载、**未运行**（上次退出信号 -9，无 PID） | 调 `~/.nebflow/bin/nebflow-autostart.sh` → 旧 `nebflow` 启动器；日志 `~/.nebflow/logs/autostart.log` | 标签、脚本名、日志路径全旧名 |
| `com.nebflow.weixin-sidecar`（plist + `.bak-20261008` + `.bak-20261020-105148` 两备份，5 处） | **运行中**（PID 97486，node shim.mjs，监听 8787） | `tools/weixin-sidecar/shim.mjs` | 标签旧名；**零触碰** |
| `com.nebflow.memsample`（plist，2 处） | 已加载、运行中（exit 0） | `~/.nebflow/bin/memsample.sh` | 标签、脚本名旧名 |
| `com.nebflow.mail-monitor.plist.disabled-20260913` | 已禁用（文件改名） | — | 死文件，可随清理批删除 |
| `application.com.nebflow.reminderisland.*` | 运行中（PID 661） | `~/.nebflow/projects/ReminderIsland/...app` | GUI app 标签（launchctl 视角），程序体在数据根内 |

## 2. PATH / 二进制残余

- `/Users/kaiyu/.nebflow/bin/nebflow`：**旧名真启动器仍在 PATH**（STABLE-ISOLATED 2026-10-05 包装，独立数据根自隔离）。
- `~/.nebflow/bin/` 同居：`nebflow-assembly-2026.9.19.jar`（现役）、`nebflow-assembly-1.4.1-beta.54.jar.bak`、`coldstart-8080.sh`、`memsample.sh`。
- **`stand` 命令尚未安装**（本机未跑过 stand 命名的安装器）——本批装好的垫片要在下次执行安装时才落盘。

## 3. 在役端口

- **8787** = weixin-sidecar（node，PID 97486）——零触碰 ✓
- **8080** = java PID 49177（当前 Nebflow 引擎实例本身，非 launchd 托管）——零触碰 ✓

## 4. 仓库侧标签唯一写点

`src/main/scala/nebflow/core/RestartHelper.scala:21`：`val LaunchAgentLabel = "com.nebflow.gateway"`——标签在树内单点定义（好改），但**改标签≠迁移**：launchd 按标签索引已加载服务，改标签后旧 plist 仍按旧标签挂载，会出现双服务/僵尸服务。

## 5. 风险分级

| 级别 | 面 | 风险 | 建议（后续批） |
|---|---|---|---|
| 🔴 高 | `com.nebflow.gateway` 标签+plist 改名 | launchd 已加载实例按旧标签索引；直接改名造成双服务或 autostart 失联（且 -9 退出码显示该服务本就不稳） | 专用迁移批：`launchctl bootout` 旧标签 → 写新 plist（`com.stand.gateway`）→ `bootstrap`，RestartHelper 标签常量同步，附带 plist 原子迁移与回滚脚本 |
| 🔴 高 | 8787 weixin-sidecar 任何触碰 | 微信在役链路 | 永久禁区（本批已零触碰）；若未来要改标签，必须走 sidecar 专属变更批+停机窗 |
| 🟡 中 | `~/.nebflow/bin/nebflow` 旧真启动器在 PATH | 与新 `stand` 命令同名冲突：新安装器垫片会**覆盖**它（本批 install.sh 已设计为覆写）→ 旧 STABLE-ISOLATED 隔离数据根语义丢失，老脚本行为变（多了弃用告警+换根） | 安装前明示 changelog；如需保隔离语义，垫片转发前检测并提示（后续批可加） |
| 🟡 中 | `nebflow-autostart.sh`/`coldstart-8080.sh`/`memsample.sh`（home 侧） | 内容引用旧命令名/jar 名，属数据根面（在飞批 85130dd3 辖区） | 归 85130dd3 批清单，不在本批动 |
| 🟢 低 | `com.nebflow.memsample` | 采样类辅助服务，改名影响面小 | 随 gateway 迁移批同模式处理 |
| 🟢 低 | `mail-monitor` 死 plist、sidecar 两份 .bak | 死文件占位 | 清理批一次删 |
| 🟢 低 | `application.com.nebflow.reminderisland.*` | GUI app 标签（由 app bundle 生成，数据根内工程） | 随 ReminderIsland 工程自身迭代，不急 |

## 6. 本批边界声明

以上全部**只读**；未 bootout/bootstrap 任何服务，未改任何 plist/home 文件；8787 sidecar 与 8080 引擎实例零触碰。标签改名（RestartHelper.scala:21 + plist 迁移）记录为后续批工作项，本批不实施。
