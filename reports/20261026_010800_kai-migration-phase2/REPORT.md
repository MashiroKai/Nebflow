# KAI 迁移批·第二阶段：构建主线正式切换

批次：`20261026_010800_kai-migration-phase2`（revival 续跑收口）
作者令：2026-10-26 00:55「我重启了，把STAND的开发转移到Kai上去」；上一批：`reports/20261026_002700_kai-migration-phase1/REPORT.md`
状态：**完成**——KAI 增量同步 / 正式出包+快验 / Mac 构建位真替换（含一次版名事故的当批更正）/ 日常流约定写死，全落地。**在产 daemon（6138, pid 72160）全程零触碰。**

## 0. 批次续跑说明（如实）

- 原执行者 `delegate-nebflow-7177e775` 00:56 领批，00:57–01:03 完成 §1/§2/首替与 ops 批注，
  merge 报告发出后遇引擎重启，会话死亡；重启后引擎已将其分支合入 main（`148e183fa`，merge `3dfa54b03`）。
- revival 后 root 会话接手收口：**前段工作以 KAI/Mac 盘上证据逐项核对计账（reflog、p2-\*.log、slot mtime），不重跑**；
  补齐版名事故更正（§3）、报告与纪律批注（§5）。

## 1. KAI 增量同步（00:57:27）

- Mac main `38b81eb4f`（含今晚回执去重修复批）→ 薄 bundle 36,247 B（`/tmp/kai-mig-p2.bundle`，bundle verify 过）→ scp → KAI `git fetch` + `merge --ff-only`；
- KAI HEAD `86e67a91f`（phase-1 基线）→ **`38b81eb4f`**（KAI reflog 实证：`merge 38b81eb4f…: Fast-forward` @ 00:57:27 +0800）；
- tree 干净（仅既有未跟踪 `reports-kai/`）。

## 2. KAI 正式出包 + 快验（01:00–01:02）

单 sbt 会话（`SBT_OPTS=-Xmx4g sbt -batch`，前台 + 心跳落盘 `C:/Users/Kai/kai-phase2/p2-{build,heartbeat}.log`）：

- **compile 绿**；
- **testOnly 5-spec：Total 83 / Failed 4 / Passed 79**（01:01:04）
  - `ImageGenSpec` **20/20 全绿**；agent 三锚 `RootSixBaseToolsSpec` 2/2、`FriendsSealSpec` 10/10、`Phase2dToolRefactorSpec` 17/17 全绿；
  - `PerceptionCoreSpec` **30/34，4 红** = 上轮**同位同因**（`NoSuchElementException: None.get` @ spec 行 233/392/408/418：mail 账户存取与草稿三件）→ **与 phase-1 判读一致：KAI Windows 环境差，非合并回归**；白天全量电池（test7）顺带定因复核。
- testOnly 红 fail-fast 短路 assembly → **assembly 单独补跑 EXIT=0**（01:02:32，11s）：106,360,135 B，SHA256 `fa459011…`（该构建未覆写 VERSION，误挂 release 名 → 见 §3）。

## 3. 版名事故与当批更正（01:03 → 01:07）

- **事故**：delegate 01:03 以 release 版名真替换——`versions/2026.10.21-beta.2/` 内 Mac 原发布包被挤成
  `.bak-20261026-010357`，KAI dev 构建（@`38b81eb4f`）占用 release 名，指针切至 `2026.10.21-beta.2`。
  违反「版本包不可变」纪律（`ops/release.sh` 口径）。
- **更正**（revival 后三步）：
  1. `.bak` 发布包复核（runtime 探针 `stand 2026.10.21-beta.2`，SHA256 `502fcf04…`，110,274,860 B，10-22 原装）→ **字节级还原回正名**；
  2. KAI 以 dev 版名重建：VERSION 覆写 `2026.10.26-dev.38b81eb4f` → `sbt -batch assembly` 12s **EXIT=0**（01:05:32）→ VERSION 还原，tree 仍干净（同树同码，测试读数沿用 §2）；
  3. `stand-jar-pull` 重拉：SHA256 双端对账一致 `fa94a929…`（106,360,361 B）→ 原子落位 `versions/2026.10.26-dev.38b81eb4f/` → 指针切换。
- **根因两件、均已写死**：脚本落位补 `chmod 644`（scp 自 Windows 落 600；delegate 已修，随 `148e183fa` 入库）；**dev 出包必须 `-dev.<sha>` 版名**（本批复笔入 README §版本名纪律 + 用法页）。

## 4. Mac 构建位终态（01:07 复核）

| 项 | 值 |
|---|---|
| `current-version` | **`2026.10.26-dev.38b81eb4f`**（runtime 探针 `stand 2026.10.26-dev.38b81eb4f` == 指针名；SHA256 `fa94a929…`，106,360,361 B） |
| `previous-version` | `2026.10.21-beta.2`（原发布包，回退目标有效） |
| versions/ 清点 | `2026.9.19` / `2026.10.21-beta.2`（原发布包已还原）/ `2026.10.25-dev.8ae0e5317` / `2026.10.26-dev.38b81eb4f`（新） |
| start 指针解析验证 | 解析到新包（POINTER-SELECT 同源只读复演；未 exec jar） |
| 在产面 | 6138 listener pid 72160 全程不变，**零触碰**；新包**下次 `stand stop && stand start` 生效**（含回执去重修复） |

## 5. 约定写死（本批入库件）

- `ops/README.md`「KAI 主线构建约定」节（delegate 批已合 `148e183fa`）+ **本批复笔**：首替更正事实、版本名纪律两条 bullet；
- `ops/bin/stand-jar-pull`：`chmod 644` 修复（delegate 批已合）；
- `ops/stand-jar-pull.md`：首替状态更新（「等作者令」→ 日常流出包即拉）+ 版本名纪律节；
- 日常流三步（bundle 同步 → KAI 出包 → `stand-jar-pull`）以 README 节为准；白天全量电池 test7 在 KAI 跑，Mac 侧不再跑全量。

## 6. 未做 / 待白天

- KAI 全量测试电池 **test7**（对照本报告 §2 基线读数 + phase-1 §4 终验账）；
- `PerceptionCoreSpec` 4 红 Windows 定因（mail 账户/草稿持久化面）；
- 新包生效需显式 `stand stop && stand start`（等作者令；替换脚本与本批均不重启）；
- KAI 侧 daemon 部署位 / 数据根迁移未立项（KAI 现定位纯构建机）。

## 附：产物

- **KAI**：`/c/Users/Kai/kai-phase2/`（`kai-mig-p2.bundle`、`run-build.sh`、`run-build-devver.sh`、`p2-build.log`、`p2-assembly.log`、`p2-assembly-devver.log`、`p2-heartbeat.log`、`p2-tree-status.txt`、`VERSION.bak`）；出包位 `stand-assembly-2026.10.26-dev.38b81eb4f.jar`（+ 历史 `stand-assembly-2026.10.21-beta.2.jar` @01:02，同码 release 名，留存备查）
- **Mac**：本报告；`ops/README.md` / `ops/stand-jar-pull.md` 批注（分支 `fix/kai-mig-p2-correction`）；构建位终态见 §4
