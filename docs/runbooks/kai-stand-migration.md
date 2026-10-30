---
type: runbook
status: active
---

# Runbook：KAI 迁移到 `stand` 命令（双实例隔离 · KAI 主线批）

- 批次：20261030 双实例并存批（作者令 2026-10-30 08:29 逐字「开启动 stand 的命令是 nebflow。
  我们需要把 KAI 迁移到正常的 stand 使用。和 nebflow 隔离。这样 nebflow 和 stand 可以同时使用。」）。
- 适用范围：KAI（`C:\Users\Kai`，Windows，引擎构建主线）本机实例的启动命令切换。属于
  **作者重启窗操作** —— 由作者在方便的时刻执行；本手册只固化动作与判据。
- 契约落点：`ops/bin/stand-cli-lib.sh` 头注释（隔离契约表）+ `ops/README.md` 部署清单 +
  `REBRAND.md`「双命令并存」节。本手册零代码。

相关件：[[kai-dispatch-route]]（KAI 引擎派发路由——派发到 KAI 的那个 6138 实例正是本手册的迁移对象）、
[[glossary]]。

## 1. 现状（2026-10-30 实测读数）

- KAI 引擎数据根 = `C:\Users\Kai\.stand`，网关口 = **6138**；构建出包位 =
  `C:\Users\Kai\stand\target\scala-3.5.2\stand-assembly-<VERSION>.jar`（`ops/README.md`
  「KAI 主线构建约定」）。
- KAI 启动器 = `C:\Users\Kai\AppData\Local\stand\stand.cmd`（独立安装器包装，无端口
  守卫）。旧命令 `nebflow` 经 `C:\Users\Kai\AppData\Local\stand\nebflow.cmd` 转发到
  stand.cmd** → 数据口/家与 stand 完全相同，因此 **KAI 上 `nebflow` 与 `stand` 实际是同一
  实例、同一口（6138）**。
- KAI 会话 env 常驻（作者面板注入，关机自清）：`STAND_HOME=C:\Users\Kai\.stand`、
  `NEBFLOW_GATEWAY_PORT=6138`、`STAND_GATEWAY_PORT=6138`、
  `NEBFLOW_HOME=C:\Users\Kai\.nebflow`。

## 2. 双命令默认值契约（两机一致）

| 命令 | 数据根（无 env） | 默认端口 | 入口（权威副本） |
|---|---|---|---|
| `stand` | `~/.stand`（KAI: `C:\Users\Kai\.stand`） | **6138** | `ops/bin/stand` → `~/.stand/bin/stand` |
| `nebflow` | `~/.nebflow`（KAI: `C:\Users\Kai\.nebflow`） | **8080** | `ops/bin/nebflow` → `~/.nebflow/bin/nebflow` |

两命令**缺省口错开**，所以双实例同机并存、互不干扰。引擎自身只绑定 6138；8080 只是
legacy 命令线的缺省读数/报错文案，引擎绝不绑定 8080。两个命令入口共用一份实现库
`stand-cli-lib.sh`（单一实现路径，库文件即 [[glossary#^citation-edge]] 的落点），按品牌壳
只认自家 env。

## 3. 切换动作（作者重启窗）

> 重启 = **停引擎 → 换启动器 → 再启**。切换当天只把启动器换成新版（数据/构建流程不变），
> 引擎在作者自己的重启窗里被新的 `stand` 命令拉起来。

1. **停引擎**（在 KAI 上，任选其一）：
   - 经网关：`stand stop`（当前 launchers 下是 `stand.cmd stop`，port 由会话 env 钉 6138）；或
   - 硬停：`Get-Process java` 找到占 6138 的进程，`Stop-Process`（与 `ops/README.md` 重构语
     义一致，重启窗专用）。

2. **换启动器**（在 KAI 上，用新一份 bash CLI 覆盖/新增）：
   ```bat
   copy "C:\Users\Kai\stand\ops\bin\stand-cli-lib.sh" "%USERPROFILE%\.stand\bin\stand-cli-lib.sh"
   copy "C:\Users\Kai\stand\ops\bin\stand"           "%USERPROFILE%\.stand\bin\stand"
   copy "C:\Users\Kai\stand\ops\bin\nebflow-stand-shim" "%USERPROFILE%\.stand\bin\nebflow"
   ```
   并给 `~/.nebflow\bin\` 放入真入口（如果你以后想让 `nebflow` 真去动 8080 的 legacy 实例）：
   ```bat
   copy "C:\Users\Kai\stand\ops\bin\nebflow"     "%USERPROFILE%\.nebflow\bin\nebflow"
   copy "C:\Users\Kai\stand\ops\bin\stand-cli-lib.sh" "%USERPROFILE%\.nebflow\bin\stand-cli-lib.sh"
   ```
   （Windows 下推荐经 Git Bash 跑 bash 入口；cmd 包装脚本见 §4 备选。）

3. **冒烟（零引擎长跑）**：
   ```bash
   bash "$HOME/.stand/bin/stand" __brand-defaults
   ```
   读数应为 `home=…\.stand`、`port=6138`、`other=nebflow`、`other_port=8080`。镜像对
   `nebflow` 侧应读出 `home=…\.nebflow`、`port=8080`。任何一侧读数不符 ⇒ 启动器没换到位，
   **不要重启引擎**。

4. **重启引擎（作者窗内）**：`bash "$HOME/.stand/bin/stand" start` —— 新引擎在
   `C:\Users\Kai\.stand`（STAND_HOME 已钉），6138 起监听。

5. **验证双实例隔离（可选，作者可另开一台跑 legacy `nebflow`，或在重启前先把 8080 口空出来）**：
   双开后，`stand start` 与 `nebflow start` 各占一个口/一个家，`stop` 双探针（自家端口 ∪
   自家 `.pid`）按品牌收窄 —— `stand stop` 不碰 8080，`nebflow stop` 不碰 6138。

## 4. 备选（cmd 包装脚本 · 无 bash 时的退路）

KAI 无独立 cmd 包装（旧 `stand.cmd` 是 Python installer 产物、无守卫）。若不想用 bash，
可手工写一个 thin wrapper（把 bash 入口包进 cmd），让 `stand` / `nebflow` 仍走
`stand-cli-lib.sh` 的守卫：

```bat
:: %USERPROFILE%\.stand\bin\stand.cmd
@echo off
"C:\Program Files\Git\bin\bash.exe" "%USERPROFILE%\.stand\bin\stand" %*
```

`nebflow.cmd` 同理指向 `~/.nebflow\bin\nebflow`。**不要**沿用旧「转发到 stand.cmd」的
垫片 —— 那是跨品牌拉起的总根（`ops/bin/nebflow-stand-shim` 头注释的病根链）。

## 5. 验证面

- 本批的**纯脚本断言**：`bash ops/test/stand-lifecycle.test.sh`（`ops/test/`，零真实
  daemon，覆盖双品牌默认值解析 `stand→~/.stand+6138` / `nebflow→~/.nebflow+8080`、
  端口冲突点名「另一实例」、隔离守卫）。全绿再重启。
- 引擎侧 spec：`nebflow.cli.CliBrandDefaultsSpec` / `SingleInstanceGuardSpec` /
  `DaemonSpawnSpec` / `StaleProcessGuardSpec` / `GatewayPortDefaultSpec`（见仓内
  `src/test/scala/nebflow/`）。
- 隔离面逐项核对见 `REBRAND.md`「双命令并存」节 + `ops/bin/stand-cli-lib.sh` 头注释契约表。

## 6. 不做的事 / 开放项

- **不 push**；改动落仓内工作分支，合并走合并队列。
- 引擎在产运行期间（作者面板会话）不重启 —— 本切换只在作者自己的重启窗执行。
- Mac 侧已就位（`~/.stand/bin/stand` + 垫片 `~/.stand/bin/nebflow`，2026-10-27 隔离批），
  本批未动其部署。
