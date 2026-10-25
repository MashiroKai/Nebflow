# KAI 迁移批·第一阶段：开发主线同步与就绪核验

批次：20261026_002700_kai-migration-phase1
作者令：00:11「现在着手开始把开发工作迁移到Kai上」；定版方向（10-25 作者令）＝ KAI 成为开发主线，Mac 按需从 KAI 拉 jar 替换 `stand start` 构建位。
状态：**第一阶段完成**——基线对齐 / KAI 同步 / 快验 / 终验收账 / 工具链 / jar 流转约定全落地。全量电池未跑（半夜，按令留白天）；**Mac 侧运行中引擎与构建位零触碰**（替换动作等作者令）。

## 1. 迁移基线（写死）

- **基线 = `86e67a91fb285aa7346c1e68d1381ff6bffdc514`**（Mac stand 仓本地 main HEAD，2026-10-25 23:41:22 +0800，Merge delegate-nebflow-f817af85；含今晚 imagegen `86e67a91f` 等全部工作）。
- 按「以实际 HEAD 为准」口径取本地 main：origin/main 落后本地 **72 commits**（fetch 时 github `Empty reply from server`，本地 main 为权威）。
- KAI 同步前 HEAD = `4c866d61d`（今日终验所在树），是基线的祖先 ⇒ 快进可行。

## 2. KAI 同步（改道如实报告）

- **SSH 通道**：`~/.ssh/config` `Host kai`（Tailscale 100.91.165.120, User Kai）——通，BatchMode 免密，与今日终验同款。注意远端默认 shell = cmd.exe（MSYS 风格路径要转 `C:\...` 或走 git-bash）。
- **直连 push 不可用**：`git push ssh://kai/...` → 远端 `'git-receive-pack' is not recognized`（Windows 非交互会话 PATH 无 git-core）。**改道 bundle 通道**：
  `git bundle create /tmp/kai-mig-baseline.bundle 4c866d61d..main`（1,413,152 B 薄包）→ `scp` → KAI `git fetch <bundle> main` + `git merge --ff-only FETCH_HEAD`。
- **结果**：KAI 仓 `C:\Users\Kai\stand` main `4c866d61d` → **`86e67a91f` 快进落位**（73 files，ff 零冲突）；working tree 干净（仅既有未跟踪 `reports-kai/`）。
- **`.sbtrc` 毒文件隔离仍生效**：`C:\Users\Kai\.sbtrc.quarantined-20261025`（161 B，内含两条全局 `Test/testOptions += Tests.Exclude(...)` 毒 set），home 与仓内均无活跃 `.sbtrc`。旁证：终验 b15b 批跑到了 `UpdateOrchestratorSpec`（毒 set 排除名单内）且实跑实红——没有被静默跳过，隔离真实生效。

## 3. KAI 快验（实测 ~3 分钟）

单 sbt 会话（`SBT_OPTS=-Xmx4g sbt -batch`）：compile → testOnly(5 spec) → assembly。日志与心跳：`C:\Users\Kai\kai-phase1\{quickverify,sbt-full,assembly,assembly-full}.log`。

- **compile**：绿（增量；快验段总时长 52s）。
- **代表族 testOnly @86e67a91f**：**Total 83 / Failed 4 / Passed 79**
  - `ImageGenSpec` **20/20 全绿**（seal=OFF release 形态，与 imagegen 双层批 49/49 同族形态）；
  - **agent 三锚件全绿**：`RootSixBaseToolsSpec` 2/2、`FriendsSealSpec` 10/10、`Phase2dToolRefactorSpec` 17/17（三锚件取 imagegen 双层批原批电池口径）；
  - `PerceptionCoreSpec` **30/34，4 红**：全部 `NoSuchElementException: None.get`，集中在 mail 账户存取与草稿三件（spec 行 233/392/408/418）。
    - **判读：非合并回归，KAI Windows 环境差**——同 spec 在 Mac 侧 34/34 全绿（perception-boot-fix-recut 批分支证据），树内 perception 面自 `127ff2c59` 后零触碰；4 红集中在持久化路径行为面。白天电池顺带复核。
    - **重要绿面**：recut 批新钉 4 个 SOE 回归钉（sweepLoop/spoolLoop boot 必经点）KAI 全绿——**boot 崩溃修复在新树上活着**。
  - `sbt -batch` fail-fast：testOnly 红 → assembly 短路未跑 → **assembly 单独补跑 EXIT=0（15s）**。
- **assembly 出包**：`target/scala-3.5.2/stand-assembly-2026.10.21-beta.2.jar`，**106,326,426 B**，SHA256 `b17d3bcc365bda239179969c6331bca41b37562cf2ad95ed5bc33a4c5dca569e`——版名来自仓 VERSION 文件。**KAI 构建链在新树上证活。**

## 4. 今日 18:24 终验 summary 收账（`/c/Users/Kai/kai-verify/`）

- 跑于 KAI 旧树 `4c866d61d`（同步前），18:24:11–18:36:04，21 批。
- **总账：Total 1365 / Failed 157 / Passed 1173 / Skipped 35；4/21 批全绿**（b02-socialA 157、b02b-inbound 19、b09-tools2 53、b12b-gitfix2 52），17 批带红。
- 红批明细（批：Total/Failed）：b01 64/18、b03 57/2、b04 46/3、b05 55/12、b06 43/5、b07 66/2、b08 79/8、b10 70/9、b11 60/17、b12a 46/1、b13 35/3、b14 71/18、b15a 93/14、b15b 59/22、b15c 59/5、b15d 46/8、b16 129/10。
- 当晚 Mac 侧已有对应 triage 报告族（`reports/20261025_154255_windows-test-triage` 等）；本批只收账不重 triage。
- env.log 附注：run.sh 的 python/py 探测行 `command not found`（KAI 无 python，仅影响 env 收集行）。

## 5. 工具链核验（KAI）

| 件 | 读数 | 判定 |
|---|---|---|
| JDK | Temurin OpenJDK 21.0.12.1 LTS（PATH java；JAVA_HOME 未设但 sbt 链不依赖） | ✅ |
| sbt | `C:\Users\Kai\sbt\bin`（18:24 终验与本批快验同一链路） | ✅ |
| node | v24.14.0 | ✅ |
| npm | 11.9.0 | ✅ |
| node_modules | 原缺失 → 本批 `npm install` EXIT=0 补齐 | ✅ 已闭合 |
| checkJs 260 基线门 | `node scripts/check-js-types.mjs` EXIT=0，**「260 errors (baseline 260)」精确复现** | ✅ |
| build-web | EXIT=0，185 js 全入 bundle graph（orphans 0） | ✅ |
| git-bash | `C:\Program Files\Git\bin\bash.exe`（sbt/脚本执行面） | ✅ |
| python | 无 | 缺口（低影响，仅 env 探测行） |
| git-receive-pack（SSH push 面） | Windows 非交互 PATH 缺位 | 已绕行：Mac→KAI 同步走 bundle 通道（§2，可约定化） |
| 数据根 | KAI 侧 legacy `C:\Users\Kai\.nebflow`（`.stand` 不存在；测试日志有迁移提示） | 备注，不阻塞构建 |

**结论：KAI 工具链无阻塞缺口**——sbt 构建链、node/npm 前端双门（checkJs 260 基线 / build-web）全部在位且实测过。

## 6. jar 流转约定落地

- **KAI 固定出包位**：`C:/Users/Kai/stand/target/scala-3.5.2/stand-assembly-<VERSION>.jar`（sbt assembly 标准出包，版名=仓 VERSION 文件；脚本认文件名）。
- **Mac 拉取替换脚本**：`ops/bin/stand-jar-pull`（仓内权威副本，不入 live——与 `release.sh` 同分类）。保证：
  - scp → 同卷 `.part` → `mv` **原子落位**（rename 语义，断电半包不落位）；
  - 同名旧包 **`.bak-YYYYMMDD-HHMMSS` 时间戳备份**；
  - **certutil(KAI) ↔ shasum(Mac) SHA256 逐字节对账**，不合即弃、指针不动；
  - 指针**原子切换** `current-version ← <v>`，旧指针记入 `previous-version`（release.sh 同语义）；
  - 替换后按 `ops/bin/stand` POINTER-SELECT **同源逻辑只读复演** `stand start` 将运行的 jar；
  - **绝不 exec jar、绝不触碰 6138 与在产 daemon**（在产 listener 只打印 advisory）。
- **用法一页**：`ops/stand-jar-pull.md`（`--verify`/`--dry-run`/替换三态 + 变体 + KAI 前置 + 回滚一行）；`ops/README.md` 文件清单已登记。
- **实测**（本批，零触碰 live）：
  - `--verify`（live root 只读）：KAI 产物 `b17d3bcc…` 对照 + Mac 指针 `2026.10.25-dev.8ae0e5317` 解析与在产 advisory（pid 51211）全对，零写入；
  - `--dry-run`（沙箱 root）：**全管道 PASS（EXIT=0）**——真拉 106,326,426 B jar → SHA256 对账一致 → 原子落位 → `previous-version ← 2026.10.25-dev.8ae0e5317` → 沙箱内 `current-version ← 2026.10.21-beta.2` → start 解析验证命中新包。真实构建位零字节触碰。
  - 开发中修掉两处脚本缺陷（均由演练暴露）：CJK 全角括号紧跟 `$VAR` 的 bash 名扫描坑；dry-run 曾跳过指针切换致验证段读空——现演练在沙箱内把指针切换一并走全。
- **纪律：替换动作等作者令**——本批未动 `~/.stand/bin` 任何字节。

## 7. 未做 / 待白天

- **全量测试电池未跑**（半夜，按令留白天）；白天可在 KAI @`86e67a91f` 复用 `/c/Users/Kai/kai-verify/run.sh` 骨架改批面。
- PerceptionCoreSpec 4 红的 Windows 环境差根因（mail 账户/草稿持久化面）未深挖。
- Mac 构建位实际替换与引擎重启：**等作者令**（脚本已就绪且演练过）。
- KAI 侧 daemon 部署位/数据根迁移未立项（KAI 目前定位纯构建机）。

## 附：本批产物

- `ops/bin/stand-jar-pull`、`ops/stand-jar-pull.md`、`ops/README.md`（+1 行登记）
- KAI 侧日志：`C:\Users\Kai\kai-phase1\`（quickverify/sbt-full/assembly/assembly-full/npm-install/checkjs/buildweb/node-chain）
- 同步通道样例：`/tmp/kai-mig-baseline.bundle`（Mac 侧薄 bundle 用法，§2）
