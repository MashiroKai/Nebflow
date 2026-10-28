---
type: design-note
status: done
---

# 跨平台 detached apply：自重启/自更新地基（台账 #916 设计件）

日期：2026-10-26 · 作者令 12:37（「更新和迁移不影响工作、不失败、绝不起不来」）
状态：已实现（`nebflow.cli.DetachedApply` / `UpdateStage` / RestartCommand detached 面 /
UpdateCommand `--from` 两阶段 / InstallPointer 品牌修正 + promote）

相关件：[[20261027_cli-isolation-and-detached-restart-spec.zh-CN]]（10-27 基础设施批承本地基出官方面）、[[20261027_hot-reload-foundation-spec.zh-CN]]（同承 #916 的热更新族）

## 0. 今天事故为镜（实证根因链，2026-10-26 12:06 Mac）

| 时刻 (stand.log / 盘上证据) | 事实 |
|---|---|
| 00:53:41 | 旧引擎 `gateway listening on 0.0.0.0:6138`（**6138**） |
| 12:06:51 | dispatcher bg 作业「分离式重启：旧引擎 TERM→等待→新引擎 start（**NEBFLOW_RESTART_PORT=8080**）」 |
| 12:06:52 | `shutdown-marker.json {kind:graceful, cause:"SIGINT/SIGTERM"}`——旧引擎收到信号开始优雅退场 |
| 12:06:52–12:22 | **零 start 腿痕迹**：bg 日志 0 行、`daemon.out` mtime 仍是 10-25、stand.log 无 boot 线、/tmp 无产物 |
| 12:22 | 作者手动 sbt 拉起（Mac 生产形态退回 sbt dev 面） |

根因三线合一：
1. **编排活在引擎外的一把临时脚本里**——端口知识陈旧（8080 vs 实听 6138）；
2. **等退场闸探错端口** → start 腿与垂死旧实例赛跑：`stand start` 的 already-running
   守卫撞上仍在 LISTEN 的旧实例 → 假成功 exit 0（或 identity/refuse 静默失败）；
3. **产物全落在无人观测的 stdout**——失败不可见，作者 16 分钟后才救。
结构性结论：进程树/会话维度的手搓 fork（double-fork+setsid）永远差一层；只有
**OS 级托管**（launchd / schtasks）才对树杀、会话回收、ssh 掉线全部免役。

## 1. 设计总则

- **apply 阶段一律注册给 OS 托管一次性任务**：macOS = launchd 一次性 agent
  （`bootstrap gui/<uid>` + `kickstart`，脚本自 `bootout` + 删 plist）；Windows =
  计划任务（`schtasks /Create /F` 固定名 + `/Run`，脚本自删除）；Linux = 未内置
  （显式拒绝，不假装分离）。`stand restart` / `stand update` 的 apply 统一走此机制；
  CLI 只注册、立即返回，**任何调用形态（ssh/console/daemon 内）注册后即可死**。
- **脚本永不瞎**：注册时把全部事实注入（端口、launcher 绝对路径、目标版本、布局、
  日志位、期限），全程写 `<dataRoot>/logs/apply-restart.log` + `<dataRoot>/apply/state.json`
  （AtomicJson tmp+rename，phase 逐相推进）——失败必留痕（事故 3 的直接反面）。
- **零盲等**：一切等待 = 条件 + 有界 deadline（stop 90s / health 180s，逐秒轮询，
  超时报因退出非零）。
- **中断安全**：选取面只有两个崩不坏的锚——pointer 布局 = 指针文件（原子切），
  winsort 扁平布局 = 「目录里 winsort 可解析的最大版本胜」。任何时刻断电/被杀，
  重启后非旧版可用即新版可用。state.json 只是证据不承载选取；引擎 boot 对账
  （`reconcileOnBoot`）只记 `interrupted` 相 + WARN，**绝不**据 state 自启/自停。

## 2. apply 脚本语义（bash / powershell 双面同构）

```
stop 腿   <launcher> stop（硬化 TERM→KILL，既有语义）→ 端口空闲有界闸（lsof/TcpClient）
          └ 超时 → update: 回滚腿 → exit 3；restart: state=failed → exit 3
start 腿  <launcher> start（jar 形态=DaemonSpawn 守护子+单行回执）
health 闸 GET /api/health（200 + status=ok；update 再断言 version==target）
          └ 超时 → update: 回滚腿 → exit 4；restart: state=failed → exit 4
done      state=done；自清理注册（bootout / schtasks /Delete）
回滚腿    pointer: current←previous（原子写）；winsort: 隔离新包（.bak-时间戳）
          → stop（杀掉已起的新版）→ start → health → state=rolled-back / failed
```

「不影响在跑工作」：stop 腿走 launcher 硬化停机 = 引擎优雅链（在飞节点
running→interrupted 非终态，重启后既有复活机制回魂）；引擎内更新另有
`HotRestart.drainForUpdate` 五域排空闸（既有件，本批不动）。

## 3. `stand update --from <jar>` 两阶段

- **Phase1（stage）**：`UpdateStage.stage` —— 版本名解析 → sha256 → 同卷
  `.part` → 复核 sha → **ATOMIC_MOVE** 进选取面（pointer: `versions/<v>/`；
  winsort: launcher 目录）。同名包 sha 同 = 幂等命中零写盘；sha 异 = 拒绝
  （版本目录不可变）。winsort 布局额外要求版本形状 winsort 可解析
  （`date[.seq][-beta.N]`，`-dev.<sha>` 形状在指针布局才合法）。**不碰指针、
  不碰在跑引擎**。`--stage-only` 到此为止。
- **Phase2（apply）**：pointer 布局先 `InstallPointer.promote`（current←新、
  previous←旧，双指针各自原子写；包不在盘即拒绝晋升）→ 注册 detached apply
  （health 闸含版本断言，失败自动回滚旧版）。注册失败 → `writePointers`
  原样放回（staged 包留盘无害）。引擎未在跑 → 不注册 apply，下次 start 直接吃新选取面。

## 4. 附带修正：InstallPointer.defaultInstallDir 品牌账

`$HOME/.nebflow/bin` 硬编码是 rebrand 前旧账：stand 品牌安装上健康自检「期望版本」
读空、G4 指针回滚切的是 launcher 永不读的孤儿指针。改为 `$HOME/<homeDirName>/bin`
（brand.conf 单一来源）。

## 5. 验证梯（本批实测记录进 REPORT）

1. 单元：`DetachedApplySpec`（渲染事实注入/两平台同构/注册命令序列/unsafe 字面
   拒绝/reconcile）+ `UpdateStage` staging/promote 契约 + 既有 hotrestart/hotupdate/cli 族。
2. bash：`ops/test/stand-lifecycle.test.sh` 回归（launcher 零改动应全绿）。
3. KAI 真机沙箱：真 jar + 真 schtasks 分离 apply（stop→start→health→done 全链）。
4. KAI 生产：新链路重启在产引擎（6138）。
5. Mac 压轴：stand-jar-pull 新包 → 新链路重启（预期自身会话死亡 → 复活核验）。
