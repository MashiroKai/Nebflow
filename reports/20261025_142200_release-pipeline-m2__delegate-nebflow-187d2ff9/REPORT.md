# release.sh 发版流水线 —— 工程化 M2（② release.sh 流水线）

- 批次：工程化 M2（作者令 2026-10-25「工程实践按你的节奏实施」；按
  `reports/20261025_122221_engineering-practices/REPORT.md` §4 顺序第 2 项）
- 交付物：`ops/release.sh`（stage / approve / rollback / tag / status 五子命令）+
  `ops/test/release-pipeline.test.sh`（89 断言）+ 本报告
- 前置：M1 `scripts/boot-smoke.sh`（BOOT-SMOKE PASS/FAIL + exit 0/1 契约）——本流水线的
  stage 闸与 approve 晋级探针都吃这个契约
- 状态：**已完成并实测通过**（断言套件 89/89 全绿 + 全链真 E2E 五段全对，证据见 §4/§5）

## 1. 交付面（对任务书五点的逐条落实）

| 任务书要求 | 实现 |
|---|---|
| ① `stage`（可自动化） | 预检 **main 干净 + 与 origin 齐平**——不阻塞：dirty 文件数与 ahead/behind 明写进输出与候批卡（`preflight drift (NON-blocking)` 行；origin 比对用 `rev-parse -q --verify refs/remotes/origin/main`，不自动 fetch，明确声明「as of last fetch」）→ `( cd $REPO && sbt assembly )` → **boot-smoke 吃 exit code**（非 0 或结论行非 `BOOT-SMOKE: PASS` → `RELEASE-STAGE: FAIL stage=smoke … zero candidates produced`，exit 1）→ 装 `versions/<v>/stand-assembly-<v>.jar` + 写 `staged-release.json`（version/commit/commitShort/branch/jar/jarSha256/smokeEvidence/smokePort/smokedAt/stagedAt/previous/approved=false）→ 输出 `RELEASE-STAGE: OK` + `CANDIDATE-READY:` 候批摘要。**stage 全程不碰指针** |
| ② `approve`（作者逐次批准唯一入口） | 验 staged 存在 + smoke 证据在案且为 PASS 行 → **sha256 完整性对账**（staged jar 被换/截断在此拦，先于探针）→ **对 staged jar 再跑一次 boot-smoke 晋级探针**（FAIL = `RELEASE-APPROVE: REJECT`，指针不动，具名报错）→ 原子写（tmp+mv rename）`current-version` 切新 + `previous-version` 记旧 → 账本置 `approved=true`/`approvedAt` → 打印一行回退指令。已批且已 current → 幂等 no-op |
| ③ `rollback` | previous 取自 `staged-release.json`（回落 `previous-version` 文件）；验证目标版本包在盘才切；指针还原；**幂等**（current==previous → no-op rc=0）。回退不跑 smoke——回退是回到已知好版本的快路径 |
| ④ `tag`（10-24 死 tag 教训） | **只在 approve 之后允许**（账本 `approved=false` → 拒）；三方对账全过才打：tag 名(剥 `v`) == `git show <approved-commit>:VERSION` == **jar 内 runtime version**（`java -jar <jar> version` 输出取末 token）；任何一腿不匹配即拒（死 tag 类机械卡点）；tag 不存在才可打（tag 不可变）；annotated tag 打在 **approved commit** 上（不是当时的 HEAD）；**永不 push**（push 永远是作者显式动作） |
| ⑤ 铁律 | **零信号**：本脚本没有任何 kill/信号路径，绝不触碰在产进程/6138——指针切换只写文件，重启交给 `bin/stand`（#844 already-running 守卫在位）；**零 wall-clock 超时判死**：唯一的等待面是 boot-smoke（内部是状态探测 + 预算钟，M1 契约），本脚本不另设睡等循环；smoke 端口在入口就拒 6138/8080/特权口/占用口（与 M1 同判据，不浪费一次构建） |

## 2. 布局与生产接线（与 ops/bin/stand POINTER-SELECT 同源）

```
<root>/current-version                          ← bin/stand 启动时读的指针（approve/rollback 只写这两个文件）
<root>/previous-version                          ← 换手前指针值（live 惯例同款）
<root>/versions/<v>/stand-assembly-<v>.jar       ← 不可变版本包（已存在即拒，要发请 bump VERSION）
<root>/staged-release.json                       ← 候批卡 + approve/回退账本
```

默认 root = `~/.stand/bin`（生产位）。`--root`/`STAND_RELEASE_ROOT` 沙箱化；`--repo`/
`STAND_RELEASE_REPO` 指构建仓；env 接缝 `STAND_RELEASE_BUILD_CMD`（默认 `sbt assembly`，
cwd=repo，注入 `RELEASE_VERSION`/`RELEASE_REPO`）、`STAND_RELEASE_SMOKE_CMD`（默认 =
本脚本所在仓 `scripts/boot-smoke.sh`，**与 --repo 解耦**——丢手/fixture 仓没有 scripts/；
单一可执行路径可含空格直接 exec，含空格非文件按 shell 片段语义）、`STAND_RELEASE_JAVA_BIN`
（tag 对账用 java；pinned jdk-21 缺失时 WARN 后回落 PATH java，非静默降级——作者令 2026-09-21）。

## 3. 验证一：bash 断言套件（`ops/test/release-pipeline.test.sh`，#844 同款风格）

全沙箱（假构建/假冒烟/假 java + 真 git fixture 仓，零真实 JVM/sbt/boot-smoke）。
**89/89 全绿**（含 `RELEASE_TEST_REAL_SMOKE=1` 的可选真冒烟用例 92 断言面里 3 条）：

- stage happy（装包 + 候批卡字段逐项 + **指针不动**）；stage 冒烟 FAIL → 零候选；
- 版本包不可变拒；预检漂移（dirty）不阻塞但上卡；构建 rc≠0 具名 FAIL；
- approve happy（指针切 + previous 记账 + 回退指令在 + 确认再探针）；探针 FAIL → 拒、指针不动；
  无 staging 具名拒；**sha256 换包拦截**；二次 approve 幂等 no-op；
- rollback 还原 + 二次幂等；无 previous 记录具名拒；
- tag approve 前拒 / happy（annotated tag 落在 approved commit + NOT pushed 声明）/
  runtime version 不匹配拒 / VERSION@commit 脱钩拒（死 tag 卡点）/ 重名拒 / `--tag` 名义不匹配拒；
- 端口铁律入口拒（6138/8080，不进构建）；status 只读面；未知子命令/参数具名 rc=1；
- **路径含空格健壮性**（本仓 checkout 路径就带空格——E2E 揪出的 bug ①，见 §5）；
- 可选真冒烟：真 boot-smoke 吃截断 jar → stage FAIL 零候选（默认跳过，E2E 单独真跑）。

## 4. 验证二：全链真 E2E（真 sbt assembly + 真 boot-smoke；沙箱数据根 + 沙箱端口）

全量原文：同目录 `e2e-output.log`（复现脚本 `e2e-run.sh`）。root=/tmp/rel-e2e-*/bin（预置
「在产」旧版 2026.9.19 作 rollback 落点），端口 21xxx 随机三口。

| 段 | 动作 | 期望 | 实测 |
|---|---|---|---|
| [1] stage | 真 `sbt assembly`（4s 热增）+ 真 boot-smoke @21883 | PASS 装包、指针不动 | **`BOOT-SMOKE: PASS … boot_ready=4s checks=11`（真 WS 101）；jar 落 `versions/2026.10.21-beta.2/`；staged-release.json 十四字段齐（sha256=05c12f9e…、smoke 证据行、previous=2026.9.19）；指针仍 2026.9.19；STAGE_RC=0** |
| [2] approve | 真 boot-smoke 晋级探针 @21273 | 探针过→指针切→回退指令 | **探针 `PASS … boot_ready=2s`（真 WS 101）；指针→2026.10.21-beta.2；previous-version=2026.9.19；`RELEASE-APPROVE: OK` + 一行回退指令；APPROVE_RC=0** |
| [3] rollback | 指针还原 + 再跑一次 | 还原 + 幂等 | **指针→2026.9.19（ROLLBACK_RC=0）；第二次 no-op（ROLLBACK2_RC=0）** |
| [4] 坏 jar | 截断 jar（真 jar `head -c 4096`）走 stage + 真 boot-smoke @22089 | FAIL、零候选 | **`[log] Error: Invalid or corrupt jarfile` → `BOOT-SMOKE: FAIL stage=boot` → `RELEASE-STAGE: FAIL stage=smoke … zero candidates`；`versions/` 空、无 staged-release.json；BAD_STAGE_RC=1** |
| [5] 收尾 | 零残留 + 在产无扰 | 全净 | **三个沙箱端口零监听残留；在产 :8080 监听者（88102）全程零触碰；live 指针 `~/.stand/bin/current-version` 原样（2026.10.25-dev.8ae0e5317）** |

## 5. E2E 揪出的三个真 bug（首跑即红，修复后复跑全绿——E2E 的价值证明）

1. **路径含空格击穿冒烟调用**：默认 boot-smoke 路径被拼进 `sh -c` 字符串再切词，
   `/Users/kaiyu/Claude code/…` 断在空格（rc=126）。修：单一可执行路径（可含空格）直接
   exec 不经 shell 切词；含空格非文件才按 shell 片段语义（操作者自负责引号）。
2. **默认冒烟命令错挂在 `--repo`**：fixture/丢手仓没有 `scripts/boot-smoke.sh`（rc=127）。
   修：默认解析自 release.sh 所在仓，与 `--repo` 解耦。
3. **构建没显式 `cd $REPO`**（潜伏）：`sbt assembly` 跑在调用者 cwd——首跑纯属 cwd 巧合才过。
   修：`( cd "$REPO" && … sh -c "$BUILD_CMD" )`。

套件侧同轮修掉测试脚手架两处（rel() 未接假构建 env、假 java 的 `-jar <path> version`
参数扫描错位）与 release.sh 一处 git 误判（`rev-parse origin/main` 对缺失 ref 会把参数
原样打到 stdout——改 `rev-parse -q --verify refs/remotes/origin/main`）。

## 6. 覆盖面声明

- **已覆盖**：构建→冒烟→装包→候批→批准换手→回退→打 tag 的全链机械卡点；坏 jar/坏账本/
  换包/重复 tag/未批打 tag/端口铁律的拒绝面；幂等面（approve/rollback）；零信号红线；
  零残留。E2E 用真构建+真冒烟把 M1 契约（BOOT-SMOKE 行 + exit code）与 M2 闸门的对接
  实打实验证过。
- **未覆盖**（后续批次，非缺陷）：上传面（OSS/GitHub prerelease/manifest——实践报告 ②的
  后半段，按发布纪律属「上传 gated」，本次刻意不碰）；autostart 侧换手后健康探针（②的
  换手后探针 + 自动回退腿）；`release.sh` 的 launchd/周节拍编排（实践报告 ①）。
- 明确假设：①stage 构建产物约定 `target/scala-*/stand-assembly-<VERSION>.jar`（build.sbt
  现行命名，E2E 实证）；②`java -jar <jar> version` 输出 `stand <VERSION>`（CliRouter.scala:348，
  真 jar 实证）；③tag 打在 approved commit（本仓 tag 历史打 main，release-pipeline skill 的
  beta/release 分支模型以主仓 AGENTS.md 为权威、现已过时，本批按任务书口径实现）。

## 7. 文件清单

- `ops/release.sh` —— 发版流水线（唯一交付脚本；五子命令；bash-3.2 兼容；shellcheck -S warning 干净）
- `ops/test/release-pipeline.test.sh` —— 89 断言套件（沙箱化；`RELEASE_TEST_REAL_SMOKE=1` 追加真冒烟用例）
- `ops/README.md` —— 文件清单 + M2 用法节
- `reports/20261025_142200_release-pipeline-m2__delegate-nebflow-187d2ff9/` —— 本报告 + e2e-run.sh + e2e-output.log

## 8. 后续衔接

实践报告 §4 的第 4 项（①版本纪律卡点 + RELEASE.md：周节拍/转正规则）可踩着本批的
`tag` 子命令继续——机械卡点（tag==VERSION==jar 三方对账）已在位，缺的只是节拍承诺与
成文规则。上传面（OSS/prerelease/manifest）建议作为 M3，接在 approve 之后、以
「只 stage 不执行」纪律实现。
