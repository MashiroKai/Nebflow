# 再生治本批 · 断点续作收口验收报告

- 批次：`~/.nebflow` 再生治本——安装器默认 home 治本（STAND_HOME 第一优先级 base）+ 数据根 18 挂载桥接收口。前批 `delegate-nebflow-2264179c` 被引擎重启打断于最终验证段，本批（`db64867a`）续作：盘点 → 最终验证 → 清理 → 防回归确认。
- 分支：`delegate/delegate-nebflow-db64867a`（已承接合并前批提交 a5719d1bb，合并点 5c84c9239）
- 日期：2026-10-22

## 结论

**GREEN。** 安装器默认 home 治本代码（a5719d1bb，7 文件 +166/−26）全量验证通过：沙箱全新安装双变体（默认 `~/.stand` / `STAND_HOME` 覆盖）全程零 `~/.nebflow` 触碰、装出 CLI 可跑（`stand 2026.10.21-beta.2`）、PATH 退役决定性面 PASS；防回归钉四变异全咬中；live 数据根 18 挂载桥全通、两先迁项目（视觉工作室 / stand-website）双向读写探针过且引擎重启后实测工作在新路径；live 配置面 legacy 残留清零（`nebflow.json` 两处悬空密钥路径本批修复）。

## 一、前批盘点（断点恢复）

- 前批工作树/分支干净，无未提交改动；全部成果在单一提交 a5719d1bb：install.sh/uninstall.sh（`STAND_BASE=${STAND_HOME:-$HOME/$HOME_DIR}` 单点，INSTALL_DIR/config/manifest/JDK/PATH/voice-model 全自 base 派生，setup_path 退役 legacy PATH 行）、install.ps1/uninstall.ps1（`$StandBase` 同规则）、zcode 垫片注释、新增 `scripts/check-installer-home.sh` 防回归钉 + `ci.yml` installer-home-gate 接线。
- live 安装面 10:25:54 已被前批用新安装器重装（manifest：scriptVersion 2.0.0 / version 2026.10.21-beta.2 / installDir `~/.stand/bin`）；18 挂载桥 10:22–10:25 已建。
- main 在前批基线后又前进 4 提交（web/docs 域），与本批 7 文件零重叠——合并无冲突（`git diff --stat a5fb3e7b3..6f79fadfe -- <7文件>` 为空实证）。

## 二、迁移前后挂载对照表

| 面 | 迁移前（≤ 2026-10-22 10:22） | 迁移后（本批收口实测） |
|---|---|---|
| 18 个挂载点实体 | 真实目录在 `~/.nebflow/projects/<name>`（10-20 数据根切换前建库的项目） | 真实目录在 `~/.stand/projects/<name>`；legacy 路径 = 符号链接桥（10:22–10:25 建）指向新根 |
| project.json `workspace` 字段 | 指向 legacy 根（据建桥事实与迁位作业推断；projects 注册表无快照存档，`~/.stand` git 不跟踪 projects/） | 52/52 全部指向 `~/.stand/projects/...`，legacy 引用零（全量 grep 实证） |
| 引擎写活动落点 | legacy 根（含 10-20 20:16 误落 153M 脚手架） | 重启（10:35:39）后 flow-maps / chain-ledger / boot-wake / doc-index 全落两项目新工作区（`find -newermt` 实证）；`~/.nebflow` 全树自 10:25 后零写入（冻结实证） |

**18 挂载点清单**（18/18 链接有效、0 悬空）：chisel-fpga、CZT、feishu-plugin、gamma-telescope、general、html-deck-studio、jev-test2、likemeter、link-server、NIMA-D-26-00151、ReminderIsland、slideblocks、Slideblocks Skill Dev、SlideBlocks Studio、stand-subs、stand-website、电离能损、视觉工作室

## 三、最终验证矩阵

| # | 验证项 | 结论 | 证据 |
|---|--------|------|------|
| 1 | 两先迁项目读写功能（视觉工作室 / stand-website） | **过** | 双向探针：legacy 桥面写 → 新根可见、新根写 → legacy 桥面可见（两项目均过，探针即写即清）；引擎级：重启后两项目 `.nebflow/` 内 flow-maps/chain-ledger.json.journal/boot-wake.json 均有 10:35 后新写入——引擎已在用新 workspace 工作 |
| 2 | `~/.nebflow` 再生物清理 | **过** | 153M 脚手架已清（前批），现 `du`=0B 仅剩桥接层；引擎重启后全树零新增（`find -newermt "10:35:39"` 空）；全部 shell profile 零 legacy 引用；`/usr/local/bin` 无旧垫片 |
| 3 | 全新安装零 `~/.nebflow` 触碰（沙箱 HOME 双变体，`--beta`） | **过** | exit 0/0；wrapper/nebflow 垫片/nebflow.json/install-manifest（installDir=沙箱 base）全部落 `$HOME/.stand`（变体 B 落 `$STAND_HOME`，且默认 home 不创建）；装出 CLI 跑通打印 beta.2；全程零 `.nebflow`、零 legacy 字面、零 legacy PATH 行。日志+驱动见本目录 |
| 4 | PATH 退役决定性面（再生根源） | **过** | 预置含 legacy PATH 行的 `.zshrc` 重跑安装器：旧行原位注释（`# retired legacy home PATH entry…`，历史保底）、`.zshrc.bak-nebflow-path` 备份生成、新行 `export PATH="<base>/bin:$PATH"` 自 STAND_BASE 派生、用户行保留、无活性 legacy 行、仍零 `.nebflow` |
| 5 | 防回归钉有效性 | **过** | `check-installer-home.sh` 本分支绿；CI 接线 `ci.yml:136-149`；`bash -n` 三脚本过。四变异全咬中（rc=1）：① HOME_DIR 钉回 `.nebflow` ② 拔掉 STAND_HOME 优先 ③ 删 PATH 退役逻辑 ④ 垫片改指 legacy 根 |
| 6 | 52 项目注册表 legacy 扫描 | **过** | `grep -rl /Users/kaiyu/.nebflow projects/*/project.json` 零命中 |

说明：全新 HOME 无任何 profile 文件时 setup_path 不创建 profile（`[ -n "$profile" ]` 守卫）——既有选点语义（本批未触碰，前批 diff 可证），真实机器 profile 常在故不受影响；face 4 的决定性测试覆盖了有 profile 的正路。

## 四、清理清单

**已清：**
1. `~/.nebflow` 153M 再生物（bin/旧 jar/JDK/日志/config 脚手架）——前批清，本批核验 0B
2. `~/.zshrc` legacy PATH 行——前批 10:21:54 退役，现为 `$HOME/.stand/bin` 新行；五 profile 全查零 legacy
3. `nebflow.json` 两处悬空 legacy 密钥路径（`~/.nebflow/secrets/social-feishu-app-secret` / `social-weixin-bot-token`）——**本批修复**改指 `~/.stand/secrets/`（密钥本体 15 件已在新根），改动前快照备份 `backups/nebflow.json.<ts>`，改后 JSON 校验过
4. 本批沙箱（/tmp 两 HOME）与驱动脚本临时物——已清，驱动脚本留档 evidence

**有意保留：**
1. `~/.nebflow/projects/` 18 桥接 symlink——兼容层；整体摘除条件 = 运行时不再解析 legacy 根（registry/paths 迁移面另批，见 paths.scala:338 `resolveDefaultDataRoot` 的 legacy 回退）
2. `nebflow.json` 的 `org.nebflow/tools.json`——插件命名空间摘要键，非文件系统路径，合法

**残留观察（非阻塞，另批候选）：**
1. 安装器头注声称支持 `env CHANNEL=beta`，旗标解析 `CHANNEL="stable"` 无条件覆盖之（与 a5fb3e7b3 逐字节一致=既有偏差，非本批回归）；本验证用 `--beta` 旗标绕行。候选修法一行：`CHANNEL="${CHANNEL:-stable}"`
2. stable 通道最新版 2026.9.19 的 jar 在 COS 404（下载 URL 面本批未动）——发版面对照项，建议随下次发版核
3. live 根无 `jdk-21`（系统 temurin-23 ≥ 21，安装器按设计跳过 tarball 下载）；wrapper 每次响 WARN 落 PATH java——既有语义（响亮不静默），如实记录
4. `tasks.json`/`tasks-v2.json` 内 legacy 路径均为历史任务文本引文（非功能解析面；冻结检查证无引擎写入）——不动引擎数据文本

## 五、生效条件（如实注记）

- **project.json（已生效）**：registry 改动以引擎进程重读为准。引擎已于今日 10:35:39 重启（PID 36826，sbt 启动），两项目重启后写入实证新 workspace 已生效。
- **nebflow.json 密钥路径修复（待下次重启生效）**：当前进程内存仍持旧值；旧值本就悬空（文件不在），修复前后行为无差异，下次引擎重启重读后指向新根。
- **安装器/垫片改动（a5719d1bb）**：脚本面即时生效，作用于下一次安装/更新执行；live 安装树 10:25 已按新代码重装过一轮。

## 六、证据路径

- 沙箱全新安装双变体：`fresh-install-sandbox-verify.log` + 驱动 `fresh-install-verify-driver.sh`（可复跑）
- PATH 退役决定性测试：`path-retire-verify.log` + 驱动 `path-retire-verify-driver.sh`
- 防回归钉本体：`scripts/check-installer-home.sh`（CI `installer-home-gate`；四变异记录见 §三-5）
- 前批代码：a5719d1bb（本分支 5c84c9239 合并点可溯）

## 七、落并

前批提交已并入本批分支（merge 5c84c9239，ort 无冲突），待 merge 队列 `--no-ff` 落 main。
