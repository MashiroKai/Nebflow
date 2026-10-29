# REPORT — beta.1 发版批（2026.10.26-beta.1）

> 作者令 2026-10-26 01:15「beta.1 今晚发布」，走 KAI 主线全链。执行：delegate-nebflow-1a35df61（stand）。

## 结论

**发版成立**：出包 → 快验 → 拉回 → boot-smoke 全链绿；在产 daemon 零触碰，新包下次 `stand stop && stand start` 生效。

## 执行账（按批令五步）

### 1. 版本推进
- `2026.10.21-beta.2` → `2026.10.26-beta.1`，单提交 `434585055`（仅 VERSION 一文件）。
- **落位说明**：main 检出当时处于他批在飞合并中态（`MERGE_HEAD=delegate-nebflow-b5646a58`，`UU AgentIdle.scala`）——不可在 main 检出提交（会裹挟他批 staged 冲突态）。版本提交落本批 delegate 分支（基线=main HEAD `3dfa54b03`），经合并队列落 main，终态等价（main 上 VERSION 单提交不变）。提交信息全文记录此口径。

### 2. bundle 通道同步（Mac → KAI）
- 薄包 `git bundle create 38b81eb4f..delegate/delegate-nebflow-1a35df61`（7,840 B）→ scp `kai:C:/Users/Kai/kai-phase2/kai-mig-beta1.bundle`。
- KAI 侧 `git bundle verify` OK → `git fetch <bundle> refs/heads/delegate/…` → `merge --ff-only` → KAI HEAD = `434585055`，VERSION=`2026.10.26-beta.1`，树干净（仅 KAI 本地 `?? reports-kai/`）。
- 范围内容：`148e183fa`（kai-migration-p2 ops 资产）+ `3dfa54b03`（merge 7177e775）+ 版本提交。**今晚其余 main 增量（感知 boot 修复、回执去重、P0 复活、生图复活等）全在 `38b81eb4f` 基线内，KAI 已有**。
- 注：fetch 需显式 refspec（bundle 记 delegate 分支名，无 HEAD 裸名）——README 惯例命令 `git fetch <bundle> main` 在非 main 名 bundle 上会 `couldn't find remote ref HEAD`，首试踩到，二试带 refspec 通过。

### 3. KAI 正式出包（`kai-phase2/run-build-beta1.sh`，前台+心跳落盘）
- Stage A `sbt -batch compile + testOnly`（快验固定面 5 spec）：**Total 83 / Failed 4 / Passed 79**，失败套件仅 `nebflow.perception.PerceptionCoreSpec`（4 红，`NoSuchElementException: None.get` 草稿快照族）——**与 phase-2 基线 `38b81eb4f` 读数逐位一致，即已知 Windows 环境差在案 4 红，照实记录不阻塞**（作者令明示）。其余快验全绿：`ImageGenSpec` / `FriendsSealSpec` / `RootSixBaseToolsSpec` / `Phase2dToolRefactorSpec`；感知 boot 修复回归钉（sweepLoop/spoolLoop 求值不炸栈等）在本机绿。
- Stage B `sbt -batch assembly`：**rc=0**。
- 产物：`C:/Users/Kai/stand/target/scala-3.5.2/stand-assembly-2026.10.26-beta.1.jar`，**106,360,136 B**，**SHA256 `e706ebbfabc3e5029138b2da73f5f8b0813711f3d17f1e6e4502b813af794794`**（certutil/sha256sum 双录）。
- 证据：`kai-phase2/beta1-{heartbeat,tests,assembly,summary}.*`（HEAD/VERSION/心跳/逐套读数/assembly 日志）。

### 4. 拉回 + 冒烟（Mac）
- `ops/bin/stand-jar-pull`：KAI certutil ↔ Mac shasum **SHA256 双端对账一致**（`e706ebbf…`）→ `.part` 原子落位 `~/.stand/bin/versions/2026.10.26-beta.1/` → `current-version ← 2026.10.26-beta.1`（`previous-version ← 2026.10.26-dev.38b81eb4f`）→ 指针解析只读复演 OK（下次 start 将运行该 jar，106,360,136 B）。
- **boot-smoke（放行门）实测该 jar**：`BOOT-SMOKE: PASS jar=stand-assembly-2026.10.26-beta.1.jar port=8089 boot_ready=4s checks=11`——隔离 mktemp 数据根起 daemon，portal `/` 200（45,117 B）、`/api/health` 200（载荷带 deviceId）、**真 WS RFC6455 101**（自铸 token Bearer）、teardown 零残留零进程。在产 pid 72160 入 never-kill 台账，全程未被触碰。

### 5. 在产 daemon 不动
- 在产实例 = sbt 前台实例 pid 72160（6138，00:53 起，`stand status` 只读核对）：本批零触碰。新包已落发布位并切指针，**下次 `stand stop && stand start` 生效**（批令明示口径）。

## 发布登记
- `RELEASE-NOTES.md`（同目录）：本版 9 件入列 + #891/#892 不 included 边界 + 回滚指引（`current-version` 写回 `2026.10.21-beta.2` 或见 `previous-version`）。

## 未做 / 开项
- **#891（UI 修整）/ #892（会话稳定）**：在飞，不 included，ride 下一版（批令边界）。
- **在飞合并 `delegate-nebflow-b5646a58`（bg-notify 批）**：批内其 main 提交不属今晚 main 已落增量，不进 beta.1，随下次同步/版本。
- **tag**：本批未打（批令五步无 tag 步；KAI 主线流指针切换即发布动作）。如需 tag，建议作者走 `ops/release.sh tag` 三方对账（VERSION@commit==jar runtime==tag 名）补拍。
- KAI 惯例白天全量电池（test7）照旧在 KAI 另行跑，本批仅快验面（批令口径）。

## 关键假设
- 版本提交经合并队列落 main 被接受为「main 上单提交」的等价达成（main 检出物理不可提交——他批合并冲突在案，reset 类处置被工作区纪律禁止）。
- boot-smoke 绿 = 批令「绿才放行」的放行判据（与 release.sh stage/approve 同一 `BOOT-SMOKE: PASS` 契约）。
