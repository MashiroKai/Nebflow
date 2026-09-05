> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# 分支治理 + CI/CD 三分支发布流方案（勘察实证版）

- 日期：2026-08-25
- 类型：阶段文档（勘察 + 方案，完成即冻结）
- 状态：**draft · 待用户确认**（文末 §6 待确认项清单）
- 勘察方式：只读（未改任何代码/分支/远程；gh API 仅查询）
- 用户计划原文要义：scala 版为主产品；rust 不推 GitHub（rust-standalone 本地保留）；仓库内容分离；网站/server 独立仓库（现状已符合）；GitHub 仅 dev/beta/release 三分支；CI/CD 自动三平台桌面打包；官网仅桌面下载；桌面包捆绑 JRE

> **2026-08-25 21:31 用户最终裁定修订**：推翻 dev 分支方案——「不用把main改为dev了，就保持还是main。但是main是dev的功能。不改命名」。
> **终态拓扑改为：GitHub 仅 main / beta / release 三分支，main 承载 dev 功能**（本地 main = scala 开发主线，日常 push main 直接触发 CI；远程 main 内容 = scala 版，旧 rust 远程 main 已删除重建）。
> 执行顺序硬约束改为：**先推 main → 切默认分支 → 才删旧分支**。
> 本修订已落实到 §2 目标态 / §3 A-2 步骤与验收 / §3 C-1 CI 重定向 / §4 不可逆表 / §6 待确认项（决策 1/8 落地：本地远程均 main，保护加在 main）。

---

## 0. 结论先行

1. **现状与目标态差距比预期小**——CI 三件套与 jpackage 三平台打包链**已存在且完整**，核心工作不是从零建设，而是：分支重命名映射 + workflow 触发目标重定向 + README/官网残留清理 + **首次实跑**（auto-release 从未运行过）。
2. **关键澄清（与用户记忆的差异）**：`feat/flowexecute` 分支**已不存在**——#406 阶段 1 已于本次勘察前 fast-forward 合并进 `archive/scala` HEAD（`ea8262a4`）后删除；备份 worktree 在 `/private/tmp/nb-teamtask-bk`（`feat/teamtask-bk`，同 commit）。「在途保护」实际含义 = **保护 archive/scala HEAD 不被重写**（非破坏性重命名即可满足）。
3. **另一个关键发现**：本地 `main` 分支**本身就是 rust 版**（151 个 .rs / 0 个 .scala，src/ 只剩 web 资源）；`rust-standalone` = `main` + 14 提交（merge-base = main HEAD）。即 **main 的 rust 内容完全被 rust-standalone 包含**，本地 main 可安全改名归档。
4. **远程 main/beta/release 均有保护规则**（禁止 force push / 删除，无 PR/状态检查要求）；删除远程 main 需先改默认分支 + 解除保护——**不可逆清单见 §4**。
5. **CI 已死亡 19 天**：ci.yml 最后运行 08-06（触发分支 main 自 dev 线迁到 archive/scala 后无人推送）；beta 发布（release.yml）活跃但仅 jar；stable 全链路（auto-release.yml）从未运行。这是当前最大的实际损失。
6. **打包（阶段 E）实质已实现**：jlink 裁剪 runtime + jpackage 三平台脚本 + runner 矩阵全部就绪，beta.51 dmg 本地实打 77MB（含捆绑 JRE，用户无需装 Java）。缺口只在「从未在 CI 实跑 + 官网 Linux 仍走 CLI」。

---

## 1. 现状盘点（实证）

### 1.1 分支血缘（主仓 `/Users/dev/Claude code/Nebflow`）

| 分支 | 角色 | 位置 | 证据 |
|---|---|---|---|
| `archive/scala`（HEAD） | **Scala 开发主线** | 本地 + 远程（远程滞后 485 提交） | `ea8262a4` feat(flow): FlowExecute tool (#406 stage 1)；`rev-list --count archive/scala..origin/archive/scala` = 485 |
| `main`（本地） | **Rust 实现**（151 .rs / 0 .scala） | 仅本地（远程 main = `c296cb90` 08-06 最后推送，scala 时代） | `git ls-tree -r main \| grep -c "\.rs$"` = 151；`.scala` = 0；143 未推送提交 |
| `rust-standalone` | Rust 版快照（430 文件） | **仅本地**（无远程） | = main + 14（merge-base = main HEAD `052e4e08`）；VERSION 1.4.1-beta.38 |
| `beta` | beta 发布线 | 本地 = 远程 `b174c3f4` | VERSION 1.4.1-beta.51；系列 merge 提交 "merge: archive/scala into beta for vX" |
| `release` | stable 发布线（陈旧） | 本地 = 远程 `beeb285c` | v1.2.0 时代；**含 72MB 已提交 jar**（nebflow-assembly-1.00.009.jar）+ nebflow-plot-test 目录 + 根级 uninstall 脚本 |
| `fix/canvas-source-toggle-bugs` | 已合并 | 本地 | HEAD `08bcae26` 是 archive/scala 祖先（602/0）→ 可安全删除 |
| `feat/p5p6-stellar-viz` | rust 时代可视化 | 本地 | = main HEAD `052e4e08`；内容 ⊆ rust-standalone |
| `refactor/Pekko-Only` / `refactor/actor-io-layered` | **活跃 refactor worktree** | 本地 worktree | `Nebflow-pekko-only` / `Nebflow-refactor`，与 scala 线分叉 1783/651 → **本次不动** |
| `archive/nebflow-v1/v2`、`archive/mesh-sync-with-session-sync` | 历史归档 | 本地 + worktree | 保留 |
| `feat/teamtask-bk` | #406 备份 | worktree `/private/tmp/nb-teamtask-bk` | = archive/scala HEAD 同 commit，保留作保险 |

三个时代的拓扑（实证）：

![现状分支拓扑](assets/20260825_branch-governance-current.svg)

- `origin/main`（c296cb90，08-06）→ `857d375b`（08-07 "fix: frontend compatibility F1-F13"）为分叉点
- Scala 线：857d375b → archive/scala（627 提交，含 #406 stage 1）
- Rust 线：857d375b → main（123 提交：rust 重写 + parity 修复）→ rust-standalone（+14）
- 发布链：archive/scala → beta（--no-ff merge + tag）→ release（v1.2.0 后断链）

### 1.2 CI 现状（三个 workflow 触发目标全部错位）

| Workflow | 触发 | 实际状态 | 证据 |
|---|---|---|---|
| `ci.yml` | push/PR → **main** | **死亡 19 天**（08-06 后无运行；dev 线已迁 archive/scala） | gh run list --workflow=CI 最后 = 08-06 |
| `release.yml`（Beta Release） | push → **beta** | 活跃（beta.40-51 全绿），**仅 jar** + COS | 运行历史全为 beta push |
| `auto-release.yml`（Auto Release） | push → **release** | **从未运行**（release 分支自 v1.2.0 未推送） | gh run list 无记录 |

ci.yml 能力完备（JS 类型门/循环依赖门/用户知识泄漏门/死日志门/编译双 OS/测试/assembly+web bundle/真实 jar 冒烟+Playwright/前端冒烟/Docker 冒烟）——触发分支保持 `[main]` 即正确目标（main 承载 dev 功能），推 main 即复活。

auto-release.yml 已含**完整三平台桌面矩阵**（macOS arm64+x64 dmg / Windows x64 msi / Linux deb+app-image / tag+Release+COS+版本同步）——但「Sync version to main」步骤在远程 main 删除后会 404，必须重定向或删除（阶段 C）。

### 1.3 打包现状（阶段 E 前置证据）

- `packaging/build-dmg.sh` / `build-msi.sh` / `build-linux.sh`：sbt-assembly fat jar → **jlink 裁剪 runtime**（`jlink-modules.txt`：java.base, java.desktop, java.logging, java.management, java.naming, java.net.http, jdk.crypto.ec, jdk.unsupported）→ jpackage 三平台产物。**JRE 已捆绑，用户无需装 Java**（用户计划第 8 条已满足）。
- 实证：`Nebflow-1.4.1-beta.51-arm64.dmg` = **77MB**（46MB jar + 裁剪 runtime；裁剪比默认 java.se 省 ~40MB，研究文档 20260815 记录）。
- 平台约束（研究文档核验）：jpackage 无跨平台交叉编译 → 必须 runner 矩阵（已实现）；Windows 需 WiX 3.14（JDK 21 时代，EOL）+ choco；Linux 需 dpkg/fakeroot；macOS ad-hoc 签名不公证（Sequoia+ 需手动放行）。
- **从未 CI 实跑**：dmg 本机实打验证过，msi/deb/app-image 与 GitHub Release 挂载从未执行。

### 1.4 官网现状（`~/.nebflow/projects/nebflow-website`，独立仓库 ✅）

- **main 分支**（59b52c2）：旧 stable/beta 频道切换，**纯 CLI**（install.sh / install-beta.sh），无任何桌面下载。
- **feat/logto-stage1**（当前分支，未合并 main）：已含桌面下载区（`3d8ac4c`）——macOS arm64/x64 dmg + Windows msi 从 GitHub releases/latest 拉取 + COS 中国镜像（latest-version.txt）+ Gatekeeper/SmartScreen 引导；但 **Linux tab 仍是 CLI**（"Linux · CLI"），且 Hero/CTA 仍带 install.sh 复制框。
- `public/` 托管 6 个安装脚本：install.sh / install.ps1 / install-beta.sh / install-beta.ps1 / uninstall.sh / uninstall.ps1。
- `content/docs/getting-started/installation.mdx` 有完整 CLI 章节。
- 在途分支 5+（logto-stage1、docs-architecture、hub、profile、logo）——阶段 D 需协调合入路径。

### 1.5 server 仓库（`~/.nebflow/projects/neblink-server`，独立仓库 ✅）

Rust 实现，main 分支活跃（Logto auth、friends A2A）。与主仓完全分离，符合用户计划第 4 条。注意：rust-standalone 内还有一份 neblink-server 副本（12 文件）+ deploy/neblink-server（8 文件）——阶段 B 建议清理。

### 1.6 内容分离扫描结论（阶段 B 前置证据）

**Scala 主线（archive/scala checkout）**：
- ✅ tracked 无 rust：`nebflow-rs/` 已删除（提交 `5d3179f9` "chore: 清理 archive/scala 混入的 rust 内容"）；`.github/workflows` 仅 3 个 scala workflow；CODEBASE.md 干净。
- ❌ **README.md 陈旧 rust 内容**（唯一 tracked 残留）：rust badge（L10）、Migration Notice（L20 "migrating from Scala to Rust... Rust version will eventually replace it"）、Rust (In Progress) 构建章节（L139/L185）、Migration Status: Scala→Rust 表（L197-213）、引用已不存在的 rust-ci.yml。**与用户裁定（scala 为主）直接矛盾，必须重写。**
- 磁盘未跟踪残留：`nebflow-rs/target`（构建产物，gitignore 已挡）、`neblink-server/target`——无害可清。

**Rust 线（rust-standalone）**：
- ❌ `release/` 4 文件（install.sh/ps1 + uninstall.sh/ps1）是 **scala jar 安装器**（`JAR_NAME="nebflow-assembly-..."`、`java -jar`）——rust 分支上的 scala 残留。
- ❌ VERSION = 1.4.1-beta.38（scala 版本号体系）。
- ⚠️ neblink-server 副本（12 文件）+ deploy/neblink-server（8 文件）——与独立 server 仓库重复。
- ✅ 无 .scala / build.sbt / .scalafmt 文件。

### 1.7 release-stable flow（`~/.nebflow/flows/release-stable/`）

reviewer → coder → packager 三节点。coder system.md 记录现行发布纪律：VERSION bump 在 archive/scala → `--no-ff` merge 进 beta/release → tag 必须打在 beta/release 的 merge commit 上 → 未经明示不 push。**文档中分支名写死 "archive/scala"**、版本方案写死日期制（YYYY.MM.DD-beta.N）但**实际一直用 1.4.1-beta.N**——两处与现状脱节，阶段 C 需更新。

---

## 2. 目标态

![目标分支拓扑](assets/20260825_branch-governance-target.svg)

| 维度 | 目标 |
|---|---|
| 本地分支 | `main` = Scala 开发主线（archive/scala 角色迁移）；`beta`/`release` 为发布 merge 分支；`rust-standalone` 仅本地保留不推送；`archive/*` 历史保留 |
| GitHub 远程 | **仅 main / beta / release**（main 承载 dev 功能——用户 08-25 21:31 裁定，原 dev 方案已否决；default branch = main） |
| 推送语义 | 日常开发 commit 到本地 main → `push origin main:main` → 触发 CI 全量 |
| beta 发布 | main → beta `--no-ff` merge + tag → push 触发 beta 发布（**含三平台桌面资产**，非仅 jar） |
| stable 发布 | beta → release `--no-ff` merge + tag → push 触发 stable 发布（全资产 + COS） |
| 发布执行 | 保留 release-stable flow 人工闸门（reviewer 验证 → coder 执行 merge → packager 验证），**不自动晋升**（dev→beta→release 链式由 flow 手动 merge 驱动） |
| 仓库内容 | scala 主线 0 rust；rust-standalone 0 scala |
| 官网 | 仅桌面端下载（dmg/msi/deb + app-image），0 CLI 引用 |
| 桌面包 | 捆绑裁剪 JRE，用户零 Java 依赖（已实现，待 CI 首跑验证） |

---

## 3. 分阶段实施步骤 + 验收

> 以下命令均为**实施阶段草稿**，本任务（勘察）未执行任何写操作。

### 阶段 A：分支治理

**A-1 本地重命名（非破坏，秒级回滚）**

```bash
git branch -m main archive/rust-main        # rust 版 main 改名归档（内容 ⊆ rust-standalone）
git branch -m archive/scala main            # scala 主线占位 main
# 验证
git branch --show-current                   # 预期 main
git log -1 --format=%s main                 # 预期 "feat(flow): FlowExecute tool ... (#406 stage 1)"
git log -1 --format=%H archive/rust-main    # 预期 052e4e08（rust 线完好）
```

**A-2 远程三分支收敛**

```bash
# 2026-08-25 21:31 用户裁定修订：远程保留 main 命名（main 承载 dev 功能），不建 dev 分支。
# 实际执行序列（零 force push，已完成 ✅）：
# 1) 解 main 保护：gh api -X DELETE repos/MashiroKai/Nebflow/branches/main/protection
# 2) 切默认分支到 beta（过渡）：gh api -X PATCH repos/MashiroKai/Nebflow -f default_branch=beta
# 3) 删旧远程 main（rust 内容）：gh api -X DELETE repos/MashiroKai/Nebflow/git/refs/heads/main
# 4) 推本地 scala main 重建远程 main：git push origin main:main
# 5) 切回默认分支 main：gh api -X PATCH repos/MashiroKai/Nebflow -f default_branch=main
# 6) 恢复 main 保护：gh api -X PUT repos/MashiroKai/Nebflow/branches/main/protection
#    -F required_status_checks=null -F enforce_admins=false -F required_pull_request_reviews=null
#    -F restrictions=null -F allow_force_pushes=false -F allow_deletions=false
# 7) 删远程 archive/scala（用户已确认）：gh api -X DELETE "repos/MashiroKai/Nebflow/git/refs/heads/archive%2Fscala"
# 8) 临时 dev 分支（曾误推）已删：gh api -X DELETE repos/MashiroKai/Nebflow/git/refs/heads/dev
```

**A-3 本地杂枝处置**

| 分支 | 处置 | 理由 |
|---|---|---|
| `fix/canvas-source-toggle-bugs` | 删除 | 已完全合并进 scala 线（HEAD 是祖先） |
| `feat/p5p6-stellar-viz` | 删除或改名 archive/ | 内容 ⊆ rust-standalone |
| `feat/teamtask-bk` | 保留 | #406 阶段 1 备份（可随后续确认删除） |
| `archive/*`、`refactor/*` | **保留不动** | 历史归档 + 活跃 refactor worktree |

**阶段 A 验收**（二值）：
- [x] `git branch --show-current` = main；`git log -1 --format=%H main` = `a6fdfcc9`（任务 D 合并后主线，scala 内容）
- [x] `git ls-remote origin` 的 refs/heads 仅 `main`、`beta`、`release`（+ tags）
- [x] 本地 `rust-standalone` 存在且 HEAD = `5b64443d`；`archive/rust-main` = `052e4e08`
- [x] `git worktree list` 与勘察前一致（refactor/* 未受影响；teamtask-bk 已由 Manager 清理）
- [x] GitHub default branch = main（承载 dev 功能）；main 保护恢复（allow_force_pushes=false, allow_deletions=false）
- [ ] 推送 main 触发 ci.yml（阶段 C 联动验证，ci.yml 触发分支待改回 [main]）

### 阶段 B：仓库内容分离

**B-1 scala 主线（本地 main）**
1. 重写 `README.md`：删除 Migration Notice / Rust badge / Rust 构建章节 / Migration Status 表；改为 scala 产品定位 + 桌面端安装说明（dmg/msi/deb）。
2. `git grep -in "nebflow-rs\|rust-ci\|cargo" main` 清零（tracked 文件层面）。
3. 磁盘清理（可选）：删除未跟踪残留 `nebflow-rs/`、`neblink-server/`（仅剩 target 构建产物）。
4. 在 main 上提交并 push 到 origin/main（直接推 main 触发 CI）。

**B-2 rust-standalone（仅本地）**
1. 删除 `release/` 4 个 scala 安装器（install.sh/ps1、uninstall.sh/ps1）。
2. 删除 `neblink-server/`（12 文件）与 `deploy/neblink-server/`（8 文件）——与独立 server 仓库重复。
3. VERSION/README 清理（可选：去 scala 版本号、去 GitHub Release badge——该仓库本就不推送）。
4. 确认无远程跟踪（`git branch -vv` rust-standalone 无 [origin/...]）。

**阶段 B 验收**（二值）：
- [ ] `git ls-tree -r --name-only main | grep -cE "\.rs$|Cargo\.toml|nebflow-rs"` = 0
- [ ] `git grep -in "migration.*rust\|rust.*migration\|nebflow-rs" main -- README.md` 无匹配
- [ ] `git ls-tree -r --name-only rust-standalone | grep -cE "\.scala$|build\.sbt|\.scalafmt"` = 0
- [ ] rust-standalone 的 release/ 目录不存在（或已清空 scala 安装器）
- [ ] rust-standalone 无 origin 跟踪

### 阶段 C：CI/CD 三分支发布流

**C-1 workflow 重定向（最小 diff）**

| 文件 | 改动 |
|---|---|
| `ci.yml` | 用户裁定后远程 main 承载 dev 功能——`on.push.branches` 保持 `[main]`、`on.pull_request.branches` 保持 `[main]`（无需改动；当前 [main] 即正确目标） |
| `release.yml` | 从 auto-release.yml 复制三平台打包 jobs（package-macos arm64+x64 / package-windows / package-linux）挂到 beta；release job 收集 jar+dmg+msi+deb+app-image 全资产；保留 `prerelease: true`；COS 上传加桌面包 |
| `auto-release.yml` | 删除「Sync version to main」步骤（VERSION 本就在 main 上 bump，同步步骤冗余且会 404——推荐删除） |

**C-2 发布语义（推荐：手动 merge 链，不自动晋升）**

```
日常:  main(本地) --push main--> origin/main --触发--> ci.yml 全量
beta:  VERSION bump 于 main → push main → main→beta --no-ff merge + tag → push beta --触发--> release.yml beta 桌面发布
stable: beta→release --no-ff merge + tag → push release --触发--> auto-release.yml stable 全资产发布 + COS
```

- 每一步由 release-stable flow 人工执行（reviewer 验证 → coder merge/tag → packager 验证），**不做 main→beta 自动 merge**（避免未验证代码自动进入发布线）。
- tag 打在 beta/release 的 merge commit 上（沿用现行纪律）；auto-release 的 tag 存在去重 guard 防重复发布。

**C-3 release-stable flow 更新**
- coder system.md：分支名 `archive/scala` → `main`（本地与远程同名，main 承载 dev 功能）；版本方案二选一（见 §6 决策 3，已定：保留 1.4.1-beta.N 并同步文档）。
- packager 角色定位更新：CI 为主打包通道；本地 packager = 抽查验证 + `upload-release-assets.sh` 补传（该脚本已带 "仅 stage 命令不执行" 门禁，保留）。

**C-4 首次实跑（验收核心）**
1. push main → ci.yml 全绿（compile ubuntu+win / test / assembly / smoke / frontend-smoke / docker）。
2. 执行一次 main→beta：release.yml 产出 **含 dmg×2 + msi + deb + app-image** 的 prerelease + COS 同步。
3. 执行一次 beta→release：auto-release.yml 产出 stable Release 全资产 + tag + COS + latest-version.txt。

**阶段 C 验收**（二值）：
- [ ] `gh run list --workflow=CI` 出现 main push 触发的绿色 run（含全部 job）
- [ ] beta 发布 Release 资产含：`Nebflow-<v>-arm64.dmg`、`-x64.dmg`、`-x64.msi`、`-x64.deb`、`-x64-app-image.tar.gz`（命名规范见 packaging 脚本）
- [ ] stable 发布同全资产且 `prerelease: false`；COS `latest-version.txt` 更新
- [ ] release-stable flow 文档与现状一致（分支名、版本方案、packager 职责）
- [ ] 冒烟（人工窗口）：下载 dmg 安装启动 → `/api/health` 200；无 Java 环境可运行（阶段 E 联动）

### 阶段 D：官网下载区（仅桌面端）

改造基线：**feat/logto-stage1 上的桌面下载区代码（3d8ac4c）已具备 80% 目标**（GH releases/latest 拉取、dmg/msi 检测、COS 镜像、Gatekeeper/SmartScreen 引导）——在此之上做减法 + 补 Linux，避免在 main 上重写。

1. `Hero.tsx`：删除 CLI_MAC_LINUX / CLI_WINDOWS / CliBox / CliSection / `orCli`；Linux tab 由 "Linux · CLI" 改为 "Linux"，资产匹配加 `-x64\.deb$` 与 `-x64-app-image\.tar\.gz$`（主下载 deb，app-image 为备选）。
2. `CTA.tsx`：删 install.sh 复制框 → 改为桌面下载按钮（链接 #install 区）。
3. `installation.mdx`：删 CLI 章节，仅保留桌面（dmg/msi/deb 下载 + 首启引导）。
4. `public/`：删除 install.sh / install.ps1 / install-beta.* / uninstall.*（或保留但不挂链——见 §6 决策 5，推荐删除）。
5. i18n dict：清理 orCli / CLI 相关文案。
6. 合入路径：与 logto-stage1 等 5 个在途分支协调（推荐：logto 合入后以其为基做阶段 D，或阶段 D 独立小分支合 main）。

**阶段 D 验收**（二值）：
- [ ] `grep -rn "install\.sh\|install\.ps1" components/ app/ content/` 无匹配（tracked 源码层）
- [ ] `curl -sI https://nebflow.space/install.sh` 返回 404（若删除 public 脚本）
- [ ] Hero 三平台 tab = macOS（arm64+x64 dmg）/ Windows（x64 msi）/ Linux（x64 deb+app-image）
- [ ] CTA 无终端复制框；桌面按钮跳转 #install
- [ ] 桌面下载 URL 指向 GitHub Releases 资产（版本号来自 latest-version.txt / releases API 双源）
- [ ] 中英双语 SSR + Playwright tab 切换断言通过（沿用 3d8ac4c 验证方式）

### 阶段 E：打包依赖（实质已实现，缺口 = 首跑验证 + 文档）

| 项 | 现状 | 缺口 |
|---|---|---|
| JRE 捆绑 | ✅ jlink 裁剪 runtime（jlink-modules.txt 8 模块）+ jpackage；dmg 77MB 本地实打 | CI 首跑验证 msi/deb/app-image |
| 三平台配置 | ✅ build-dmg/msi/linux.sh + auto-release 矩阵（macos-15×2 / windows-2022 / ubuntu-24.04） | 从未整体执行；macos-15-intel runner 存在性首跑确认 |
| 体积 | 46MB jar → 77MB dmg（裁剪省 ~40MB） | msi/deb 实际体积记录 |
| 签名 | ad-hoc（macOS）/ 无（Windows SmartScreen） | 接受取舍（研究文档 §3/§4） |
| 质量门 | jdeps 模块清单 + 打包冒烟建议 | 依赖 bump 后须重跑 jdeps + 包内启动冒烟 |

**阶段 E 验收**（二值）：
- [ ] 从 GitHub Release 下载 dmg/msi/deb 安装，在**无 Java 环境**启动成功（包内含 runtime/ 目录，`java -version` 无需）
- [ ] 三平台包体积记录进文档（dmg 77MB 基线已有）
- [ ] 依赖升级流程含 jdeps 重跑 + 打包冒烟步骤（写入 README 或 CI 注释）

---

## 4. 不可逆操作确认清单（逐项影响 + 回滚）

| # | 操作 | 影响 | 回滚 | 需确认 |
|---|---|---|---|---|
| 1 | 删除远程 `origin/archive/scala` | 失去远程引用（本地 485 提交完好） | `git push origin archive/scala` 重建 | ✅ |
| 2 | 删除远程 `origin/main` | 失去远程引用；rust 内容本地双份（main→archive/rust-main + rust-standalone） | `git push origin archive/rust-main:main` 重建 | ✅ |
| 3 | GitHub default branch → beta（过渡）再切回 main | 重定向默认分支；需先完成 main 推送 | Settings 改回 | ✅（已执行） |
| 4 | 解除 main 保护后删除 | 保护规则被删（可重建） | 重建保护规则 + 推回分支 | ✅ |
| 5 | 本地 `main` 改名 `archive/rust-main` | 引用重命名，无内容丢失 | `git branch -m archive/rust-main main` | ✅ |
| 6 | 本地 `archive/scala` 改名 `main` | 引用重命名，无内容丢失 | `git branch -m main archive/scala` | ✅ |
| 7 | 删除本地 `fix/canvas-source-toggle-bugs` | 分支引用消失（提交已在 scala 线） | reflog 或从祖先重建 | ✅ |
| 8 | 删除本地 `feat/p5p6-stellar-viz` | 引用消失（内容 ⊆ rust-standalone） | 从 rust-standalone/main 重建 | ✅ |
| 9 | （不推荐）release 分支历史重写清 72MB jar | force push + 解保护；破坏已发布 v1.2.0 关联 | 无（故不推荐） | — |
| 10 | rust-standalone 删除 release/ 等文件 | 仅本地分支内容变化 | 从 reflog/其他分支恢复 | ✅ |

> 原则：**全程零 force push、零历史重写**。所有"删除"均为引用级操作（ref deletion），提交对象全部保留于本地其他引用/对象库中。

---

## 5. 风险与取舍

| 风险 | 等级 | 缓解 |
|---|---|---|
| 远程 main 删除前未完成默认分支切换 → 仓库失去默认分支 | 高 | 执行顺序硬约束：先推 main → 再切默认 → 再删；§4 回滚表兜底（已按此执行：切 beta 过渡→删旧 main→推新 main→切回） |
| auto-release「Sync version to main」在 main 删除后 404 失败 | 中 | 阶段 C 同步修改（删除该步骤），先于任何 release push |
| 首跑 CI 桌面矩阵失败（msi/deb 从未实跑；macos-15-intel 标签存在性） | 中 | 阶段 C-4 首跑即验收；失败项按平台逐个修（WiX/fakeroot 依赖已预置） |
| 在途 refactor worktree（Pekko-Only / actor-io-layered）与改名后 main 的合并基线漂移 | 低 | 分支改名不影响既有 merge-base；本次完全不动这两个 worktree |
| #406 后续阶段（stage 2+）基于 archive/scala 旧名开发 | 低 | 改名后统一用 main；teamtask-bk 备份留存至 #406 全部落定 |
| 官网 5 个在途分支与阶段 D 改动冲突 | 中 | 阶段 D 以 logto-stage1 合入后的 main 为基（或独立小分支），避免重写桌面区代码 |
| release 分支 72MB jar 永久留在历史 | 低 | 接受（一次性 blob，改历史不划算）；新 release 内容不含垃圾文件 |
| 代理 127.0.0.1:7890 不可用影响推送 | 低 | 实施期用 `git -c http.proxy= -c https.proxy=` 直连或 gh 通道 |

---

## 6. 待用户确认项清单（附推荐）

| # | 决策点 | 推荐 | 备选 |
|---|---|---|---|
| 1 | 本地主线分支名 | **main**（用户 08-25 21:31 最终裁定：远程也保持 main，main 承载 dev 功能——「不用把main改为dev了，就保持还是main。但是main是dev的功能。不改命名」） | ~~dev~~（已否决） |
| 2 | 远程旧分支（main / archive/scala） | **删除**（内容本地全保留，§4 可回滚） | 改名 archive/* 保留引用 |
| 3 | 版本方案 | **保留 1.4.1-beta.N**（与现状/tag 一致，同步更新 flow 文档） | 改为日期制 YYYY.MM.DD-beta.N（flow 文档现行写法，需换 VERSION 体系） |
| 4 | 发布语义 | **手动 merge 链**（main→beta→release 由 release-stable flow 逐步驱动，CI 自动打包） | main→beta 自动 merge（GitHub Actions 自动晋升，风险高不推荐） |
| 5 | 官网 public/ 安装脚本 | **删除**（install.sh / install-beta.* / uninstall.*） | 保留文件但不挂任何链接 |
| 6 | beta 发布是否含桌面资产 | **含**（用户计划第 6 条精神；beta 是安装包测试通道） | 仅 stable 含桌面（beta 维持 jar-only） |
| 7 | rust-standalone 清理范围 | **删 scala 安装器 + neblink-server 副本**；VERSION/README 顺手清理 | 仅删 scala 安装器（最小动作） |
| 8 | main 分支保护 | **已恢复**（与 beta/release 同级：禁 force push/删除；不加 PR 强制以免打断日常推送） | 不加（维持现状宽松） |

---

## 7. 附：证据索引

- 分支血缘：`git merge-base archive/scala main` = 857d375b；`rev-list --left-right --count archive/scala...main` = 627/123；rust-standalone...main = 14/0
- rust 清理提交：`5d3179f9`（archive/scala）；`bb4c5d51`（main 删 scala）
- CI 死亡证据：gh run list --workflow=CI 最后运行 08-06；auto-release 无运行记录
- 打包实证：`Nebflow-1.4.1-beta.51-arm64.dmg` 77MB；研究文档 `~/.nebflow/docs/Nebflow/20260815_research-jpackage-desktop.md`
- 官网：Hero/CTA/installation.mdx 引用 install.sh 位置见 §1.4；`3d8ac4c` 桌面下载区在 feat/logto-stage1（main 未含）
- 保护规则：gh api branches/main|beta|release/protection（allow_force_pushes=false, allow_deletions=false，无 PR/状态要求）
