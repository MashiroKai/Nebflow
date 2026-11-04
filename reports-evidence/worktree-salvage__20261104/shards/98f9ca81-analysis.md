# 98f9ca81（delegate-nebflow-98f9ca81）6 个未合提交 · 逐提交定性与处置建议

- 树：`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-98f9ca81`
- 分支：`delegate/delegate-nebflow-98f9ca81` · pre_head = HEAD = `5c046b9ee08d8da729a2da442c7088952130e5f0`
- main tip（判定基线）：`0fffa26b51e130f012329269d27f70be19fa3ab5`
- merge-base(main, HEAD)：`9d7d35bba28e593733b577ef1bf251c1219c49f8`
- 工作区：`git status --porcelain` = **0 行**；`git diff --stat HEAD..HEAD` = **0 行** ⇒ 无可保全改动，`commit_sha=-`，保全 0 / 判废 0，行数对齐 0==0（**无 MISMATCH**）
- 分支净改动：`git diff --stat 9d7d35bba..HEAD` = **34 文件 +2776/−70**

> 硬约束复核：本节点**未**对 main 执行任何 merge / cherry-pick / rebase / checkout / commit；未删分支或树；未动主仓工作区（`git -C 主仓 status --porcelain` 仍 0 行、HEAD 未变）；未 kill 任何进程；未触 `/private/tmp/x1367-*`。

---

## 1. 6 个提交全量（`git -C <树> log --oneline --stat main..HEAD`，旧→新）

| # | 提交 | 时间 | 标题要点 | 改动面 |
|---|---|---|---|---|
| 1 | `ef74c3f75` | 2026-11-04 11:37:29 | B1 第二腿：V1–V6 的 5 个 Scala spec（红证据阶段，只写测试不写实现） | 5 A · **+976** |
| 2 | `ff03bf890` | 2026-11-04 11:43:46 | 红证据 red-scala.log + AccountRootSpec 补 IORuntime import | 2（A 277 / M 2）· **+279** |
| 3 | `6132990f4` | 2026-11-04 11:44:17 | red-scala.log 勘误（spec 修补提交号写成 ff03bf890） | 1 · **+1/−1** |
| 4 | `5f04afa67` | 2026-11-04 11:44:43 | AccountRootSpec 隔离纪律措辞校正 | 1 · **+3/−2** |
| 5 | `5ae680e99` | 2026-11-04 12:19:35 | B1 第三腿：三层数据根实现（机器层根 + 有效根阶梯 + owner/热切守卫 + 账号层 0700 + device.json 归账号层 + deviceIdScope=acct\|&lt;sub&gt;） | **26 文件 · +1270/−70** |
| 6 | `5c046b9ee` | 2026-11-04 12:23:50 | 转绿证据：V1/V2/V3/V5(+V4/V6) 全绿 21/21、回归 shared 178 / cli 241 / 合并 440 全 0 失败、样本账号根 stat + 目录树 | 2 A · **+250** |

逐提交文件清单（`git -C <树> log --reverse --format='--- %h %s' --name-status main..HEAD`）：

- `ef74c3f75`（全 A，`src/test/scala/nebflow/core/dataroot/`）：`AccountIsolationSpec.scala` +192 / `AccountRootSpec.scala` +344 / `AutoStartHomeSpec.scala` +158 / `LegacyRootMigrationSpec.scala` +193 / `OwnerGuardSpec.scala` +89
- `ff03bf890`：`reports-evidence/20261104_b1-dataroot/red/red-scala.log` A +277；`src/test/.../AccountRootSpec.scala` M +2
- `6132990f4`：`reports-evidence/20261104_b1-dataroot/red/red-scala.log` M +1/−1
- `5f04afa67`：`src/test/.../AccountRootSpec.scala` M +3/−2
- `5ae680e99`（26 文件 = 4 A + 22 M）：A `core/root/{AccountRoot +263, Guards +134, RootLadder +175, RootLayout +255}`；M `Main.scala 37`、`cli/{GatewayClient 12, LoginCommand 53, PidMeta 2, ProcessManager 2}`、`core/{AutoStartService 51, RestartHelper 6}`、`core/daemon/{DaemonPanelStore 2, DaemonService 2, DaemonStore 2}`、`core/hotrestart/HotRestart 2`、`core/secrets/SecretsStore 2`、`gateway/{AuthRoutes 5, GatewayMain 45, PerceptionDaemonDock 4, auth 2, gatewayConfig 3}`、`neblink/{DeviceCredentialStore 4, NeblinkEnrollment 73, NeblinkModel 90}`、`shared/{InstanceScope 12, paths 102}`
- `5c046b9ee`（全 A）：`reports-evidence/20261104_b1-dataroot/green/tree-accounts.txt` +59；`reports-evidence/20261104_b1-dataroot/green/v1_v2_v5_impl-root.log` +191

---

## 2. 逐提交定性表（取证证据类 / 代码 spec 类）

| 提交 | 类别 | 判断依据 | main 是否已含 | 是否仍需要 |
|---|---|---|---|---|
| `ef74c3f75` | **代码 spec 类**（纯测试） | 5 个文件全在 `src/test/scala/nebflow/core/dataroot/`，只写 spec 不写实现（标题自述 + name-status 全 A / 全 test 路径） | **已含，逐字节相同**：main 的 `b21a9a16f` 同 5 文件；`git diff --numstat main..HEAD -- <每个 spec>` 全 `IDENTICAL` | **不需要** |
| `ff03bf890` | **取证证据类** + 代码 spec（1 行 import） | 主体是 `reports-evidence/20261104_b1-dataroot/red/red-scala.log`（红证据读数 277 行），附带 spec 的 `cats IORuntime` import 修补 | **已含**：main `33ac54a61`；`red-scala.log` 与 `AccountRootSpec.scala` 两份 numstat 均 `IDENTICAL` | **不需要** |
| `6132990f4` | **取证证据类** | 只改 `red-scala.log` 里一处提交号笔误（+1/−1） | **已含**：main `73d081e93`；numstat `IDENTICAL` | **不需要** |
| `5f04afa67` | **代码 spec 类** | 只改 `AccountRootSpec.scala` 注释措辞（+3/−2） | **已含**：main `ea8c4d0f0`；numstat `IDENTICAL` | **不需要** |
| `5ae680e99` | **代码 spec 类**（src/main 实现 + 接线，26 文件） | 全部 `src/main/scala/`：新增 `core/root` 四件套 + 22 个既有文件改根路径/守卫/端口归属 | **部分**：7 文件 main 有等价实现（`872bcdae3` 平行实现）；**19 文件 main 零等价** | **部分仍需要**（详 §3） |
| `5c046b9ee` | **取证证据类** | 纯 `reports-evidence/` 下两份读数（样本账号根 stat/目录树 + spec 绿证与回归读数），零 src 改动 | **main 无这两份文件**（`git ls-tree -r --name-only main -- reports-evidence/20261104_b1-dataroot` 只有 `design-api.md`/`design-inventory.md`/`red/red-scala.log`/`green/{five-specs, five-specs-per-suite, full-suite-oometaspace, baseline-pristine-main/*, regress-cli-gateway-config}*） | 证据唯一，但**对应分支变体实现**，单独价值有限（详 §5） |

小结：**6 个提交里 4 个（1/2/3/4）是「已并入 main 的空 patch」——纯 spec 与红证据，main 上逐字节相同；第 6 个是取证证据类（唯一）；第 5 个是代码 spec 类，是本批唯一有实物价值的一个，但它是 B1 实现腿的「平行姊妹版」。**

---

## 3. `5ae680e99` 26 文件逐项：main 是否已有等价内容

判定方法：先按「main 自 merge-base 起是否动过该文件」切两半（`comm -23 / -12`），再对 main 侧内容做符号级取证（`git show main:<file> | grep -c -e <symbol>`）。

### 3.1 main **已有等价内容**（7 文件）⇒ 不需要

| 文件 | main 侧快照 | 依据 |
|---|---|---|
| `core/root/AccountRoot.scala` | main 由 `872bcdae3`（2026-11-04 **12:19:37**，与本提交 12:19:35 相差 2 秒）新增，同契约 `design-api.md` §3、同符号族（`ActiveAccount/AccountProfile/LayoutMarker/pointerPath/readPointer/writePointer/clearPointer/accountsDir/accountRoot/ensureAccountDir/dirNameOf/isAccountRoot/findBySub/readProfile/writeProfile/layoutMarkerPath/readLayoutMarker/writeLayoutMarker`） | `git diff --numstat main..HEAD` = 192/166（**平行实现**，非超集：分支另有 `LayoutMarkerFileName/PointerDamagedMarker/removeLayoutMarker`，main 另有 `PointerFileName/LayoutFileName/mapped/unusable/direct`） |
| `core/root/Guards.scala` | 同 `872bcdae3` 新增，`OwnerGuard.check` / `LoginGuard.checkLoginAllowed` / `checkHotSwitch` | numstat 84/74（分支另有 `checkLoginAllowedOn`、`AccountSwitchRequiresLogout`） |
| `core/root/RootLadder.scala` | 同 `872bcdae3` 新增，`machineRootOf` / `resolve` / `current` / 三档 `RootLayer` | numstat 126/72（分支另有 `announce` / `layer` / `target`） |
| `core/root/RootLayout.scala` | 同 `872bcdae3` 新增，`migrate` + `rollback` + `MigrationReport` + `PendingClaimDirName` | numstat 205/219（分支另有 `manifest` / `pendingFromManifest` / `reportPathOf`） |
| `shared/paths.scala` | main 已定义并使用 `machineRoot`（`grep -c machineRoot` = **10**） | 差异 93/83，属两版实现函数名/注释差异 |
| `neblink/NeblinkModel.scala` | main 已实现 `deviceIdScope` ×4、`acct&#124;` ×4、`machineRoot` ×5、`loadOrCreate` 守卫 ×6 | numstat 60/102 |
| `core/AutoStartService.scala` | main 已实现 `accountHome` ×12、`--home` ×8（autostart 带账号根，§8.2 / A12） | numstat 34/64 |

**关键佐证**：5 个 spec 在 main 与分支**逐字节相同**（`AccountRootSpec / AccountIsolationSpec / AutoStartHomeSpec / LegacyRootMigrationSpec / OwnerGuardSpec` 全部 `IDENTICAL`），而 main 有绿证 `reports-evidence/20261104_b1-dataroot/green/five-specs.log`（`AccountRootSpec` 逐条 `+` 绿：V1 未登录层 / 指针坏档降级 flat / `ensureAccountDir` 0700 幂等 / `dirNameOf` 单射 / 指针 roundtrip 四字段 + 0600 等）。⇒ **main 的平行实现已满足同一份验收规格**，分支这 4 个文件是姊妹版而非增量。

### 3.2 main **零等价改动**（19 文件）⇒ 仍需要

判据：这些文件 main 自 merge-base 起**完全没动**（`comm -23` 命中），且 main 侧内容**不含**分支引入的接线符号/根面。

| 文件 | 分支引入的接线面 | main 侧反证读数 |
|---|---|---|
| `gateway/GatewayMain.scala` | owner 拒启闸 `ownerGate`（`Main.run` 定根后、Ember `server` build 前 `IO.raiseError`） | `git show main:...GatewayMain.scala \| grep -c ownerGate` = **0**（main 该文件自 merge-base 起的改动是 presence-dial 的 `DirectRoute.installSystemProbe()` / `reachLedger`，与本批无关） |
| `neblink/NeblinkEnrollment.scala` | `writeActiveAccountPointer`（登录后写指针，调用点 :273）+ 平铺机首登先 `RootLayout.migrate` | `writeActiveAccountPointer`=0、`RootLayout.migrate`=0 |
| `gateway/AuthRoutes.scala` | `performLocalLogout` 第 4b 步 `AccountRoot.clearPointer(machineRoot)`（登出只删指针） | `clearPointer`=0、`writeActiveAccount`=0 |
| `cli/LoginCommand.scala` | 登录前置门 `LoginGuard.checkLoginAllowedOn(GatewayClient.readPortSyncOrBrandDefault, None)`（复用 `SingleInstanceGuard.checkPort`，不新增端口） | `checkLoginAllowed`=0、`SingleInstanceGuard`=0 |
| `shared/InstanceScope.scala` | 端口归属走 `machineRoot`（账号档不再因「非默认根」被拒 6138，§9.3） | `machineRoot`=0 |
| `cli/ProcessManager.scala` / `cli/PidMeta.scala` | `.pid` / `.pid.meta` 搬机器层（§9.1） | main 自 merge-base 未动该文件 |
| `gateway/auth.scala` / `cli/GatewayClient.scala` | `auth.json` 路径搬机器层（§9.1） | main 自 merge-base 未动该文件 |
| `core/daemon/DaemonStore.scala` / `DaemonService.scala` / `DaemonPanelStore.scala` | daemon 面根搬机器层 | main 自 merge-base 未动该文件 |
| `core/secrets/SecretsStore.scala` | secrets 面根搬机器层 | main 自 merge-base 未动该文件 |
| `core/RestartHelper.scala` / `core/hotrestart/HotRestart.scala` | 重启 / 热重启面根搬机器层 | main 自 merge-base 未动该文件 |
| `gateway/PerceptionDaemonDock.scala` | Perception daemon 面根搬机器层 | main 自 merge-base 未动该文件 |
| `gateway/gatewayConfig.scala` | 点名根 / 端口归属 | main 自 merge-base 未动该文件 |
| `neblink/DeviceCredentialStore.scala` | 凭据路径归账号层 | main 自 merge-base 未动该文件 |
| `Main.scala` | `setMachineRoot` / `dataRoot` 定根 + 热切守卫自检（:152-159） | main 自 merge-base 未动该文件 |

**决断性证据（最强一条）**：`git grep -n -e OwnerGuard -e LoginGuard main -- src/main/scala` 在 main 上**只有 6 处命中，全在 `core/root/Guards.scala` 自身定义 + 1 行 `paths.scala` 注释，零调用点**。⇒ main 虽落了 `core/root` 四件套，但**账号层尚未接线**：登录不写指针、登出不清指针、owner 不拒启、登录门/热切门未挂、机器层文件未搬家。这 19 个文件正是这条接线的另一半，**main 里没有等价物**（改名/搬家也查过：`git ls-tree -r --name-only main -- src/main/scala | grep -iE 'core/(root|dataroot)'` 只有那 4 个文件，无第二套根面；`reports-evidence/20261104_b1-dataroot/` 同理）。

> 因此：**98f9ca81 是「取证类 + 代码 spec 类」的混合体，代码类重心在接线面（main 缺）；取证类与 spec 是已并入的空 patch。** 不能说整棵只需要取证，也不能说整棵都是未落地的代码。

---

## 4. cherry-pick 可行性（只读判定，未执行任何 pick）

### 4.1 三方合并冲突检测

- 按任务书命令：`git merge-tree 9d7d35bba main delegate/delegate-nebflow-98f9ca81` ⇒ exit 0，输出 2168 行，**25 个 `<<<<<<< .our` 冲突标记**，集中在 3 文件（`AutoStartService.scala` 19 处、`NeblinkModel.scala` 5 处、`paths.scala` 1 处）。
- 交叉复核（全程只读：blob `git show` 落 `/tmp` 后 `git merge-file --diff3`，零 repo 写入）：两侧自 merge-base 起**都动过**的文件 14 个，其中 **7 个冲突**：
  - `core/AutoStartService.scala` **9 处**、`neblink/NeblinkModel.scala` **5 处**、`shared/paths.scala` **3 处**
  - `core/root/{AccountRoot,Guards,RootLadder,RootLayout}.scala` **各 1 处**（两侧各自「新增同路径文件」⇒ 空 base 整文件冲突）
  - 7 个干净：5 个 `dataroot` spec + `red/red-scala.log`（内容 `IDENTICAL`，patch 为空）+ `gateway/GatewayMain.scala`（改的是文件不同区域）

### 4.2 结论

**不是「零冲突」，是「可能冲突（7 文件）」**，而且有比冲突更硬的问题：

1. **前 4 个提交 pick 到 main 等于空操作**（5 spec + red-scala.log 在 main 上逐字节相同）⇒ 无意义。
2. **`5ae680e99` 整提交 pick 会「回退」main 已落地实现**：`core/root` × 4 + `paths.scala` + `NeblinkModel` + `AutoStartService` 这 7 个文件在 main 上已被 `872bcdae3` / `86554dc67` 的平行实现占位，pick 分支版 = 用姊妹版覆盖 main 已绿资产，并叠加 7 文件冲突。**不可原样 pick。**
3. **可零冲突摘取的部分**：§3.2 的 19 个文件里，18 个 main 自 merge-base 起完全未动 ⇒ 分支 patch 可直接应用（`git merge-file` 0 标记）；`GatewayMain.scala` 虽两侧都动过但不同区域 ⇒ 0 标记。**即：19 文件单独取 = 零冲突。**
4. **`5c046b9ee` 的两份证据文件**在 main 不存在 ⇒ 可零冲突新增，但描述的是分支变体的运行读数（`tree-accounts.txt` 的 0700/0600 stat 与目录树、`v1_v2_v5_impl-root.log` 的 21/21 绿与 shared 178 / cli 241 / 合并 440 全 0 失败），引用时须注明「实现 = 分支姊妹版，非 main 现行实现」。

---

## 5. 需合并候选（**由作者裁定，本节点未动 main**）

**候选 C1 —— 账号层接线面**
> 一句话产品价值：把已落在 main 的 `core/root` 四件套**真正接上电** —— 登录写 `active-account` 指针（平铺机首登先迁移）、登出只清指针、网关启动前 owner 拒启闸、登录前置门复用单实例判活、`machineRoot` 承担 `.pid`/`.pid.meta`/`auth.json`/daemon/secrets/restart/hotrestart/autostart 等机器层文件、账号档端口归属不再被「非默认根」误拒、`deviceIdScope` 保持 `acct|<sub>`。
> 改动面：§3.2 的 **19 文件**，与 main 三方合并 **0 冲突**。
> 前置约束：接线调用的是**分支版** `core/root` 的符号（`LoginGuard.checkLoginAllowedOn`、`RootLadder.announce`、`AccountRoot.removeLayoutMarker`、`RootLayout.manifest/pendingFromManifest`）。摘取时须二选一：**(a) 只摘接线、把调用点对齐 main 现行 `core/root` 符号**（不覆盖 main 已绿实现，需改 19 文件里的调用点）；**(b) 连 `core/root` 一起换成分支姊妹版**（干净但对齐 main 的既有绿资产需重跑 5 个 dataroot spec + 回归）。

**候选 C2 —— 两份 green 证据（可选，建议随 C1 走）**
> `reports-evidence/20261104_b1-dataroot/green/{tree-accounts.txt, v1_v2_v5_impl-root.log}`：补上 main 目前**缺失**的「真实实现面」样本账号根读数（账号根 700 / profile.json·active-account·layout.json 600、`dirNameOf` 单射 `x-c2a815bc9fe71468fb980438`、`findBySub` 反查、`devices minted: List()` 零 device.json）。零冲突，但必须附「对应分支姊妹版实现」的说明。价值低于 C1。

**不建议合并**：`ef74c3f75` / `ff03bf890` / `6132990f4` / `5f04afa67`（main 已逐字节相同）。

---

## 6. 证据命令与读数（可复现）

```bash
T="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-98f9ca81"
M="/Users/kaiyu/Claude code/Stand"
git -C "$T" rev-parse HEAD                      # 5c046b9ee08d8da729a2da442c7088952130e5f0
git -C "$T" status --porcelain | wc -l          # 0
git -C "$T" merge-base main HEAD                # 9d7d35bba28e593733b577ef1bf251c1219c49f8
git -C "$T" log --oneline --stat main..HEAD     # 6 commits
git -C "$T" diff --stat 9d7d35bba..HEAD         # 34 files changed, 2776 insertions(+), 70 deletions(-)

# main 已含（逐字节相同）
git -C "$T" diff --numstat main..HEAD -- src/test/scala/nebflow/core/dataroot                                  # 全 IDENTICAL
git -C "$T" diff --numstat main..HEAD -- reports-evidence/20261104_b1-dataroot/red/red-scala.log               # IDENTICAL

# main 的平行实现（同契约）
git log --oneline -5 "$M" -- src/main/scala/nebflow/core/root/AccountRoot.scala
#   872bcdae3 B1 实现腿：三层数据根落地 —— RootLadder 阶梯 + AccountRoot 指针/账号层 + RootLayout 迁移/回滚 + OwnerGuard/LoginGuard 双守卫
git show --stat --oneline 872bcdae3             # 11 files, +4935/-42（core/root 4 件套 + AutoStartService/NeblinkModel/paths + 3 份 green 日志）

# main 的账号层零接线（最强一条）
git grep -n -e OwnerGuard -e LoginGuard main -- src/main/scala | wc -l                                      # 6（全为 Guards.scala 定义 + paths.scala 注释）
git show main:src/main/scala/nebflow/gateway/GatewayMain.scala | grep -c ownerGate                           # 0
git show main:src/main/scala/nebflow/neblink/NeblinkEnrollment.scala | grep -c writeActiveAccountPointer     # 0
git show main:src/main/scala/nebflow/gateway/AuthRoutes.scala | grep -c clearPointer                         # 0
git show main:src/main/scala/nebflow/cli/LoginCommand.scala | grep -c checkLoginAllowed                      # 0
git show main:src/main/scala/nebflow/shared/InstanceScope.scala | grep -c machineRoot                        # 0
git show main:src/main/scala/nebflow/core/AutoStartService.scala | grep -c accountHome                       # 12（main 已含 autostart 账号根）
git show main:src/main/scala/nebflow/shared/paths.scala | grep -c machineRoot                                # 10

# 冲突只读判定
git merge-tree 9d7d35bba main delegate/delegate-nebflow-98f9ca81 | grep -c '<<<<<<< \.our'                   # 25
comm -12 <(git -C "$T" diff --name-only 9d7d35bba..HEAD|sort) <(git diff --name-only 9d7d35bba..main|sort)    # 14 文件（两侧都动过）
comm -23 <(git -C "$T" diff --name-only 9d7d35bba..HEAD|sort) <(git diff --name-only 9d7d35bba..main|sort)    # 20 文件（18 src + 2 份证据）
# 逐文件三方冲突（blob 落 /tmp，零 repo 写入）：
#   git show <ref>:<file> > /tmp/{o,b,t}; git merge-file -p --diff3 /tmp/o /tmp/b /tmp/t | grep -c '^<<<<<<<'
#   ⇒ AutoStartService 9 / NeblinkModel 5 / paths 3 / core/root×4 各 1 / GatewayMain 0 / 5 spec 0 / red-scala.log 0
```

---

## 7. 未做 / 不确定 / 假设

- **未做**：任何写操作（merge / cherry-pick / rebase / checkout / reset / commit / stash）、删树、删分支、动主仓工作区、kill 进程、碰 `/private/tmp/x1367-*`。未在 750143b4 树内提交（与上游 recon 节点一致，保持工作区干净）。
- **不确定（不替作者拍板）**：
  1. main 的平行实现与分支姊妹版**哪一版更合作者意图** —— 两版都满足同一份 spec（spec 文件逐字节相同），但符号面不同（分支多 `announce`/`removeLayoutMarker`/`manifest`/`pendingFromManifest`，main 多 `direct`/`unmapped`/`rollback` 拆法），产品取舍需作者定。
  2. 候选 C1 走「只摘接线 + 对齐 main 符号」还是「整批换姊妹版」—— 前者不覆盖 main 已绿资产但需改 19 文件调用点，后者干净但要重跑 5 spec + 回归。两案都能零冲突**起步**，取舍需作者定。
  3. main 上账号层未接线（`OwnerGuard`/`LoginGuard` 零调用点）是**故意分期**还是**漏接**，本节点无法判断 —— `design-inventory.md` 与 `green/five-specs.log` 只证明「根面实现已绿」，不证明「接线已排期」。
- **假设**：
  - 「main 已含」判定 = merge-base `9d7d35bba` 切分 + main 侧符号级 grep；**未运行任何 sbt 测试**（避免与在飞的 sbt/java 进程争用），main 的绿读数直接引用仓内 `reports-evidence/20261104_b1-dataroot/green/five-specs.log`（同一份 spec 全绿）。
  - `git diff main..HEAD`（整树）会混入 main 侧他人批次的删除/新增，**不能**当「分支净改动」用；本报告全部改用 `git diff <merge-base>..HEAD` + 双边文件切分。
  - 本节点两文件按任务书落在 `reports-evidence/.../shards/`（过程证据分片），非知识文档，故不进 `<dataRoot>/projects/<project>/.stand/docs/` vault。
