# 缺陷修复 · Project 跨设备挂载（台账 #901）——direction=list 版本偏斜 + stray 误报

日期：2026-10-26 ｜ 分支：`fix/project-device-verify-901`（基 `7a147afa1`）｜ 开发位：KAI（`C:/Users/Kai/stand`）

## 0. 结论一句话

`Project(action=create, device=KAI)` 晨间失败**不是「目录核验实现缺失」**——实现早已存在且走设备通道——而是**对端设备引擎版本偏斜**：KAI 在产 daemon 跑 2026.9.19 stable（早于 2026-10-10 `direction=list` 落位），其 legacy 接收分支原样回 `"Unknown direction: list"`，旧文案又把用户引去「先建设备目录」的错误补救路径。本批：①list 偏斜改诊为升级指路（新错误码 `DEVICE_ENGINE_TOO_OLD`）；②`Project(device=…)` 的合法入参不再被 stray 判据误报；③顺手去重（创建/挂载腿的 scaffold 不再二次 list）；④把一例主机依赖的基线红（DeviceProjectMountSpec 铁律②在 Windows 主机假绿转红）改为主机无关。**生产 remediation 的主路径是升级 KAI daemon 引擎**（见 §6），代码修复保证的是「失败时诊断诚实、成功路径不回归」。

## 1. 根因链（实证）

1. **报错原文**（2026-10-26 晨，台账 #901）：`REMOTE_DIR_UNVERIFIED: cannot verify directory 'C:/Users/Kai/stand' on device 'KAI' — Unknown direction: list`。
2. **发送侧**：Mac 引擎 device-project-mount 批（`ProjectCreateService.createRemote` ② 目录校验）经 `RemoteExecutor.transferListScoped`（`RemoteExecutor.scala`）发 `FileTransfer direction=list scope=browse`（P2P 优先 → relay 回落）。
3. **接收侧**：两条设备通道接收腿（`NeblinkRoutes.scala:1005` remote-exec、`NeblinkRelayTunnel.scala:896` relay tunnel）都汇入 `FileTransferAction.handle`；**当前代码** list 是入口第一分支（`FileTransferAction.scala:57`），不可能产出该错。
4. **版本考古**：`direction=list` 落位于 2026-10-10 夜批（`a0f2edfc7` checkpoint，v2.1 阶段 1）；legacy 分支 `case other => Left(s"Unknown direction: $other")` 自 2026-09 起逐字稳定（`9c218231c` 时代即如此，`FileTransferAction.scala:88`）。
5. **对端实况**：KAI 设备 daemon = `C:\Users\Kai\AppData\Local\Nebflow\nebflow-assembly-2026.9.19.jar`（install-manifest：2026-09-20 stable 通道，java PID 15844 在跑）——**早于 list 方向十个月**。
6. **第二缺陷（平台提示半边）**：`AgentSessionExecution` 的 stray 判据（S3 三条语义）按「非 remoteable 六件却带 `device`」告警——但 `Project` 的 schema **自陈** `device` 为自己的业务入参（`ProjectTool.scala:64`），告警文本「nothing was routed to that device」对它是误导（device 参数实际驱动了工具内部的设备通道腿）。

## 2. 方案取舍（为什么不做 legacy 回退）

任务书给的选项 (b)「改走已 remoteable 原语组合出 verify+scaffold」被**否**，理由三条：

- **救不全**：创建腿之外，挂载腿（`DeviceProjectMount.prepare` ③）与文件浏览器设备腿/Wiki 浏览面同样消费 `direction=list`——只回退创建腿 = create 成功而 mount 失败的半态；全部腿回退 = 把整个 device-family 的元数据面都绑到 legacy 文本解析（Bash exit code / Glob 空集歧义）。
- **覆盖风险**：`WriteTool` **缺省覆盖**（`WriteTool.scala:28`）；scaffold「既有永不覆盖」的正确性完全依赖 list 给出的 existing 集——任何降级 listing（entries=[] 兜底）都会让 scaffold 对既有 AGENTS.md/.gitignore 落笔。legacy `FileTransfer get/put/probe` 都无法核验目录存在性（put 带 createFolders 会静默造目录，get 对目录与缺失同错，probe 对目录/缺失同错）。
- **fleet 现实**：配对设备面 = 作者自有两台，正确动作是对端升级（beta.1 jar 已在 KAI 出包），回退代码是为版本考古养的永久第二路径（违反「能删则删」）。

选**诚实诊断**：签名命中（小写包含 `"unknown direction: list"`）⇒ 错误改诊为版本偏斜 + 升级指路；不命中 ⇒ 既有文案逐字节不变。

## 3. 改动面

| 文件 | 改动 |
|---|---|
| `RemoteExecutor.scala` | +`schemaDeclaresDeviceParam(schema)`（stray 豁免判据单点）；+`listDirectionSkewNote(err, deviceName)`（版本偏斜诊断单点，签名不中 = None = 调用方文案零变化） |
| `AgentSessionExecution.scala` | stray 判据收窄：`filterNot(remoteable \|\| schemaDeclaresDevice)`——S3 的①不投对端/②参数原样原样保留，仅③告警对「schema 自陈 device」的工具豁免 |
| `ProjectCreateService.scala` | createRemote ② 校验失败分支：偏斜签名命中 ⇒ `DEVICE_ENGINE_TOO_OLD` 专属文案；scaffoldRemote 改收 ② 的同一 listing（去二次 list） |
| `DeviceProjectMount.scala` | prepare ③ list 失败：偏斜签名 ⇒ 追加升级指路；scaffold 同样改收 ③ listing（去二次 list） |
| `DeviceChannelListSkewSpec.scala`（新） | 两判据的签名/负控/边界 8 例 |
| `DeviceProjectMountSpec.scala` | 铁律② 主机无关化（见 §4 归因） |

约束核对：**脑/台账留宿主**未动（project.json 仍最后落笔、远端只落 AGENTS.md/.gitignore）；**create 幂等语义**未动（既有不覆盖——scaffold 的 existing 判据来源从「二次 list」换成「同一 list」，判据本体未变）；**unmount 零删除**未动（零触碰）。

## 4. 基线红归因（DeviceProjectMountSpec 铁律②）

该例在 `C:\Users\Kai\stand`（Windows 风格路径）上断言本机腿拒绝——在作者 Mac（POSIX）为真；**KAI（Windows 主机）上该路径真实存在**（本仓自身），本机腿预检通过 ⇒ create 成功 ⇒ 假绿转红。**净基线 `7a147afa1`（不含本批）对照同红**（9/10，同例同位，`/c/Users/Kai/kai-phase2/baseline-dpm-*.log`）。本批将路径改为主机无关的 `C:\no-such-incident-dir-901\nb`（任何主机都不存在），零写入断言改用生产同款解析（`os.Path(ghostWin, base = tmp)` + Try 包裹）——判据本体（Windows 风格路径误走本机腿被拒 + device 指引 + 零写入）不变。

## 5. 门禁读数

| 门禁 | 读数 |
|---|---|
| KAI 仓同步 | Mac main `ba6963d72` → KAI：真合并（KAI 独有发版提交 `434585055` 与 main 分叉于 `3dfa54b03`，ff-only 不可行）⇒ merge commit `7a147afa1`，VERSION=2026.10.26-beta.1，树净 |
| sbt compile（main） | 绿（SBT_EXIT=0，log `/c/Users/Kai/kai-phase2/compile-ec5c-*.log`） |
| 定向 spec 电池 | 8 套：DeviceChannelListSkewSpec(新) / RemoteableDeviceParamSpec / DeviceProjectMountSpec / ProjectCreatePanelSpec / ProjectCreateRootSessionSpec / ProjectStartupMountSpec / ProjectUnmountSpec / RootSixBaseToolsSpec —— 终轮读数见 §5.1 |
| boot-smoke | 隔离数据根（`scripts/boot-smoke.sh`，mktemp home + 沙箱端口 + PID 验身）——对 assembly jar 执行，读数见 §5.2 |
| 在产触碰 | 零（Mac 6138 / KAI daemon PID 15844 全程未动） |

### 5.1 spec 电池终轮

`Passed: Total 49, Failed 0, Errors 0`（8 套全绿；log `/c/Users/Kai/kai-phase2/testonly-ec5c-r3.log`）：
DeviceChannelListSkewSpec（新 8 例）/ RemoteableDeviceParamSpec / DeviceProjectMountSpec（含铁律②主机无关化后复绿）/ ProjectCreatePanelSpec / ProjectCreateRootSessionSpec / ProjectStartupMountSpec / ProjectUnmountSpec / RootSixBaseToolsSpec。
基线对照：净 `7a147afa1` 单跑 DeviceProjectMountSpec = 9/10（铁律②同位同红，`baseline-dpm-*.log`）——归因见 §4，非本批回归。

### 5.2 boot-smoke

`BOOT-SMOKE: PASS jar=stand-assembly-2026.10.26-beta.1.jar port=8089 boot_ready=4s checks=11 (portal+health+ws-or-degraded+teardown)`——Mac 侧执行（脚本 macOS-only：MINGW64 无 lsof，preflight 显式拒跑，见 §8 登记），隔离数据根 + 沙箱端口 + PID 验身，teardown 零残留零进程（log `/tmp/boot-smoke-ec5c.log`）。jar 在 KAI assembly 出包（106,305,686 B，`ASSEMBLY_EXIT=0`）经 scp 拉回（sha256 前缀 `7194eb7cc9f08ced`）。

## 6. E2E 待办（Nebula 在合并+重启后执行）

1. **Mac 在产引擎换包**：beta.2 基建流程不变——`ops/bin/stand-jar-pull`（本批合并后需在 KAI 重新 assembly 出包，或直接用本次 boot-smoke 的 jar），stop/start 生效（脚本零触碰在产）。
2. **KAI daemon 升级（本缺陷的生产 remediation 主键）**：把新 assembly 部署到 KAI 设备引擎位（现跑 `nebflow-assembly-2026.9.19.jar`）并重启 KAI daemon。重启会瞬断设备通道（本会话所属链不受影响——会话在 Mac 引擎侧）。
3. **真 E2E**：KAI daemon 升级后，Mac 侧 Nebula 调 `Project(action=create, name=stand, workspace=C:/Users/Kai/stand, device=KAI)`：
   - 预期成功文案含 scaffold 读数（`Scaffold: AGENTS.md … .gitignore …`）；
   - 若 KAI daemon 未升级先试：预期错误改判 `DEVICE_ENGINE_TOO_OLD`（含版本偏斜说明与升级指路），**不再**出现「Create/verify the directory on that device first」误导文案；
   - 幂等重试：同参重调 = 可续创（缺件才补，既有 AGENTS.md/.gitignore 零覆盖）。
4. **挂载面**：create 后复检面板项目卡（mounted 态）、文件浏览器设备腿（KAI 目录列举）、会话默认设备路由（挂载项目会话的 Read/Bash 带 device 默认走 KAI）。
5. **stray 面回归观察**：任一含 `Project(device=…)` 的调用不应再出现 `device-stray` 事件/WARN；非 remoteable 工具误带 device（如 `TaskBoard(device=x)`）仍应告警（S3 负面未动）。

## 7. 关键假设

- 「Unknown direction: list」签名在老版本对端的 legacy 分支逐字稳定（考古至 2026-09 时代源码 `9c218231c`；KAI 实跑 2026.9.19 命中晨间实录原文）。
- 发送侧错误串可能带包裹前缀（`Remote error: ` / `p2p: …; relay: …`），故判据用小写包含匹配（spec 已钉三种包裹形态 + put/get 负控）。
- KAI 在产 daemon 的升级属作者/Nebula 的运维动作，本批不代做（在产零触碰纪律）。
- Mac 仓本批只读（对账/合并面）：KAI 分支经 bundle 回传 Mac 工作树分支，走合并队列落 main。

## 8. 登记面（不属本批、留观/候选）

- **boot-smoke 的 Windows 移植**：`scripts/boot-smoke.sh` 硬依赖 lsof（9 处），MINGW64 preflight 显式拒跑——KAI 本地冒烟当前不可用，冒烟面留 Mac（与既有流程一致）；若要 KAI 本地化，候选 = netstat -ano + PowerShell cwd 探针的 shim 层（动验证 harness，需作者裁）。
- **KAI 设备 daemon 升级路径**：设备引擎位（`C:\Users\Kai\AppData\Local\Nebflow\`）与开发仓出包位无联动脚本，升级 = 手工换包 + 重启；候选 = 写入 ops 日常流第四步。
- **PeerInfo 引擎版本自陈**（peer 广播 engineVersion 字段）＝版本偏斜的结构化解法候选：老对端不自陈 ⇒ 无法用于本批，仅作前向登记。
- **WikiService 浏览面**同消费 `direction=list`（vault 面），对老对端同样受偏斜影响——未动（项目面任务边界），升级对端后同愈。
