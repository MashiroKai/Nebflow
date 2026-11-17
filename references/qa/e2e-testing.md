# E2E 测试与发布前全量门禁

> 2026-11-16 批（feat/release-cicd-e2e-20261116）：E2E harness 接入发布流水线。
> 作者裁定 2026-11-16 11:54（harness 文档「要合入进行使用」）：全量测试安排在发布前 CI/CD。

## Harness 本体

三件套在 stand-dev 插件仓（`git.ustc.edu.cn:kaiyu/stand-dev.git`，管线以
`STAND_DEV_REF` 源钉），部署面位于
`~/.stand/plugins/stand-dev/skills/stand-dev/scripts/`：

- `build_e2e_fixture.py` — 离线沙箱编译 fixture（seatbelt 断网 + JDK23 硬检 +
  `$CACHE_HOME/.stand/bin/sbt` 门禁包装器 + 依赖缓存只读拷贝）
- `run_e2e.py` — 套件执行器（fail-closed；`--list` 为计划/dry-run 原语）
- `isolated_harness.py` — protocol-v1 适配器（E01 已证 READY；内建引擎侧源钉
  BASELINE `592299ce`，merge-base 校验）

场景目录 `assets/e2e-scenarios.json`：F01–F04（quick 层：node 门禁 ×3 +
rulebook ×1）+ E01–E10（adapter 层，isolated-engine）。新场景入目录即被
`--full` 自动纳入，管线不需要改。

## 发布前全量门禁（阻塞段）

接入点：`release.yml`（beta 线，push `release/beta`）与 `auto-release.yml`
（stable 线，push `release/stable`）各内联同一 `e2e` job（沿 build-jar 双份
先例，两文件同步维护）：

```
build-jar → e2e → release（tag / GH Release / COS）
```

任一环节红即中止发布（`release` job `needs: [build-jar, e2e]`）。

e2e job 跑在自托管 Mac runner（`[self-hosted, macOS, ARM64]`，ci-selfhosted
先例）：harness fail-closed 依赖 seatbelt（sandbox-exec 正负对照）、
`$E2E_CACHE_HOME/.stand/bin/sbt` 门禁包装器、JDK23 与依赖缓存——GitHub 托管
runner 均不满足，且自托管分钟不计费。步骤链：

1. checkout（`fetch-depth: 0` —— adapter 源钉 merge-base 与套件 fingerprint
   需要完整历史）
2. JDK23 探测：`/usr/libexec/java_home -v 23`（环境自适应，不硬编码本机路径；
   缺失即红并报因）
3. cache-home 解析：默认 `$HOME`（`E2E_CACHE_HOME` 可覆盖），断言
   `.stand/bin/sbt` 门禁包装器在位
4. harness 源钉：clone USTC stand-dev → checkout `STAND_DEV_REF` →
   rev-parse 相等校验，漂移即红
5. 计划打印：`run_e2e.py $E2E_SUITE_ARGS --list`
6. 缓存自举（在线）：门禁包装器 `sbt -batch compile e2eClasspath`（全局锁
   `~/.stand/run/sbt.lock`，与作者本机 sbt 互斥安全），依赖落入 cache-home
7. 离线 fixture 编译：`build_e2e_fixture.py`（缓存只读拷贝 + seatbelt 断网）
8. 套件执行：`run_e2e.py $E2E_SUITE_ARGS $E2E_READINESS_FLAG --adapter
   isolated_harness.py`（语义见上节：就绪感知，TODO 待回传 flag 名）
9. 前端门禁：`npm ci && npm install --no-save @playwright/test@1.62.0 &&
   npm run check:frontend`（25 项；playwright 仓规不入 devDependencies、由运行
   环境自带，CI 形态 = `npm install --no-save @playwright/test`，钉主仓配对
   版本，浏览器缺缓存时退回系统 Edge/Chrome）
10. evidence 上传 artifact（3 天保留；仓内不入库）

**门禁语义（作者裁定 2026-11-16：就绪感知）**：管线只跑已实现场景——READY
失败=红，NOT READY=跳过并在结果/计划中显式列出，E02–E10 adapter 落地即随
场景目录自动纳入（全量枚举 + readiness 过滤执行）。管线换参点两处
（两文件同步改）：

- `E2E_SUITE_ARGS`（现 `--suite functional --full`）：套件与范围；
- `E2E_READINESS_FLAG`（现为空，**TODO 待回传**）：readiness 开关，harness
  批（`--require-ready` 形态：READY 失败=红、NOT READY=跳过+显式列出、
  `--list` 带 readiness 列）落地后回填确切 flag 名。

过渡期（flag 未填、源钉未换 readiness 版）：等价 `--full` 全量语义，E02 起
NOT READY 即红——发布链路验证走 dry_run dispatch。源钉 `STAND_DEV_REF`
同为 **TODO**：readiness 批 sha（`92d376e` 之上，作者代推 USTC）回传后替换。

## dry-run 路径

- **CI 侧**：两线均支持 `workflow_dispatch`，`dry_run=true`（默认）跑
  build+e2e 全链但跳过 tag/release/COS（auto-release #437 先例；release.yml
  于本批复补）。
- **本地侧**（与管线各步「Same logic locally」注释一一对应）：

```bash
# 计划（dry-run 原语，无执行）
python3 ~/.stand/plugins/stand-dev/skills/stand-dev/scripts/run_e2e.py \
  --suite functional --full --list

# 缓存自举 + fixture 编译（含门禁 sbt → 会话权限外，作者代跑）
JAVA_HOME="$(/usr/libexec/java_home -v 23)" SBT_GATE=on SBT_GATE_SERIALIZE=on \
  "$HOME/.stand/bin/sbt" -batch compile e2eClasspath
python3 ~/.stand/plugins/stand-dev/skills/stand-dev/scripts/build_e2e_fixture.py \
  --repo . --output /tmp/e2e-fixture-<ts> \
  --java-home "$(/usr/libexec/java_home -v 23)" --cache-home "$HOME"

# 全场景执行（输出目录必须不存在、不在 ~/.stand 下）
python3 ~/.stand/plugins/stand-dev/skills/stand-dev/scripts/run_e2e.py \
  --suite functional --full --repo . --output /tmp/e2e-results-<ts> \
  --adapter ~/.stand/plugins/stand-dev/skills/stand-dev/scripts/isolated_harness.py

# 前端门禁（新树需先无痕装 playwright，主 checkout 由环境自带）
npm ci && npm install --no-save @playwright/test@1.62.0 && npm run check:frontend
```

## 日常开发

不跑全量；定向跑照旧：

```bash
python3 ~/.stand/plugins/stand-dev/skills/stand-dev/scripts/run_e2e.py --suite functional                 # quick 层（F01–F04）
python3 ~/.stand/plugins/stand-dev/skills/stand-dev/scripts/run_e2e.py --suite functional --scenario E01 # 点名单场景
```
