# 服务器切版批 —— **RED，未切版**（发布门禁红因报告）

- 批次：服务器切版批·生产构建就绪（作者令 2026-10-25 18:51「把服务器更新推生产」）
- 结论：**发布门禁第 2 关（全链真 E2E）红 → 按铁律停止，未切版、未动任何生产面**。
  main HEAD `7b41a525b` 存在**启动即崩**缺陷（boot-fatal），切版候选不可产出。
- 红因一句话：`PerceptionCore.sweepLoop`（`src/main/scala/nebflow/perception/PerceptionService.scala:150`）
  自引用 `*>` 链在**构造期**无限递归 → `java.lang.StackOverflowError` → daemon 子进程在就绪前
  退出（`listener_seen=0`）。数据根无关、确定性复现 2/2。

## 1. 基线与门禁读数

| 项 | 读数 |
|---|---|
| 切版基线（main HEAD，worktree 同点位） | `7b41a525b2d1a50836637cb0ee57151027f9e59a`（2026-10-25 18:53:54 +0800，Merge delegate-nebflow-23732543） |
| 候选版本号（拟，未采用） | `2026.10.25-dev.7b41a525b`（沿用在产 `-dev.<cut-sha>` 惯例） |
| 门禁 A：`ops/test/release-pipeline.test.sh`（89 断言，全沙箱） | **绿：PASS=89 FAIL=0，exit 0**（evidence-gateA-assertions.log） |
| 门禁 B：全链真 E2E（真 sbt assembly + 真 boot-smoke，沙箱根/沙箱端口） | **红：STAGE_RC=1**——build 73s 出 jar（99M）但 stage 冒烟闸 `BOOT-SMOKE: FAIL stage=boot reason=gateway not ready within 120s (listener_seen=0)`，零候选；后续 approve/rollback 腿因无 staging 级联拒绝（属预期防御面）；坏 jar 腿 rc=1 符合预期（evidence-gateB-e2e.log） |
| 门禁 C：boot-smoke M1 四连跑 | **未跑**（B 红即停；同一 jar 已两次实证无法 boot，无 battery 价值） |
| 长命令纪律 | 全部前台跑：perl alarm 包裹（A=600s/B=2700s 预算）+ 15s 心跳落盘（`*.log.hb`）；B 被 120s 前台预算自动转入后台属平台机制，命令未逃逸、alarm 预算仍生效 |
| 首跑冒烟 | 2026-10-25 19:02–19:06（E2E 内）；独立复现 19:07（手工冷启） |

## 2. 红因定位（证据链）

1. **E2E 首证**（沙箱隔离数据根，端口 23554）：daemon 子进程就绪前退出，日志尾 5 行全是
   `at nebflow.perception.PerceptionCore.sweepLoop(PerceptionService.scala:150)`。
2. **独立复现**（隔离数据根 `/tmp`，端口 21556，evidence-repro-daemon.out 全文在案）：
   ```
   java.lang.StackOverflowError
   	at scala.concurrent.duration.Duration$.apply(Duration.scala:45)
   	at nebflow.perception.PerceptionCore.sweepLoop(PerceptionService.scala:150)   ← ×N 纯构造帧
   ```
   崩溃发生在启动同秒（10s sleep 首次到点之前）⇒ **构造期递归**，非运行期：
   `IO.sleep(Duration(10,"seconds")) *> sweepOnce *> sweepLoop` 尾操作数经 `*>` 立即求值，
   每层递归都重新构造 sleep 节点直至栈尽；boot 线程上 `core.sweepLoop.start`
   （`PerceptionService.scala:72`，网关启动必经）把 SOE 直接带崩 daemon。同文件其余三环
   （`pollLoop`:140 / `digestLoop`:154 / `spoolLoop`:163）全用 for 推导式（递归调用在
   flatMap lambda 内，运行期递归）——只有 sweepLoop 是 `*>` 链形态，即病灶。
3. **引入点**：`183c375c2`「实施批·邮件感知功能落地（mail-perception-impl）：感知守护全栈 +
   三脸前端 + 官网色单源」（2026-10-25 16:36:29），经 `7746771a0`（Merge
   delegate-nebflow-9f913ccb，16:37:07）落 main；该文件仅此一个 commit。14:22 的 M2 E2E
   （当时 main）boot 冒烟是绿的 ⇒ 红因界线在 16:37 这次合入。
4. **严重度**：boot-fatal、数据无关（全新隔离根与任何存量根同样崩）。main HEAD 起的
   **任何**新 daemon 都起不来——不只 jar 位：`nebflow-restart.sh` / `nebflow-autostart.sh` v2
   现行都是从 main 树 `sbt run` 起 6138，同一份源码即同一崩溃。**此时点「重启待批」清单里的
   重启一旦执行，6138 stand daemon 直接起死——本批的红闸正好挡在它前面。**
5. **修复形态（供下批参考，本批未动代码）**：sweepLoop 改回与 pollLoop 同款 for 推导式
   （递归调用置末位生成器内）或 `IO.sleep(..) *> sweepOnce *> IO(sweepLoop).flatten` 形态；
   改后需 boot-smoke 实测冷启绿。

## 3. 生产面无扰核验（收尾扫描，全绿）

- live 指针 `~/.stand/bin/current-version` = `2026.10.25-dev.8ae0e5317` **原样**；
  `previous-version` = `2026.9.19` 原样；`versions/` 三包（2026.9.19 / 2026.10.21-beta.2 /
  2026.10.25-dev.8ae0e5317）原样，**零新增目录、零覆盖、零备份副产物**（未走到制品位，原定
  「旧 jar 带时间戳备份」无需发生——不可变版本包模型下旧件本就在 `versions/` 原位）。
- `staged-release.json` 未产生；沙箱三端口（23554/23520/21724）零监听残留。
- 在产进程零触碰：:6138 零监听（本就未运行）、:8080 监听者 pid 88102（宿主 gateway）全程原样；
  never-kill 台账（1105/88102/52985）全程未破。
- worktree 复原：VERSION 已还原为 `2026.10.21-beta.2`（字节级），`git status` 干净；
  在飞 delegate 分支零接触。

## 4. 重启待批清单（不批为妙——见 §2.4）

原清单未变，本次重启拟一次性吃全：邮件感知全栈 / 端口 6138 / 退避 / 引擎修复四腿 /
P0 复活双件 #809 #810。**新增前置堵点：邮件感知全栈（183c375c2）携带本 boot-fatal，
修复+冒烟绿之前，任何重启（无论 jar 位还是 main 树 sbt run）都会把 6138 起成当场崩——
切版顺延至修复批之后。**

## 5. 顺延下一版的在飞批次（零接触声明）

canvas 刷新 / 生图复活 / 机制双件 / bg 成对 / 面板批后续合入——本次切版基线
`7b41a525b` 不含其在飞 delegate 分支的未合内容，全部顺延下一版。

## 6. 文件清单与关键假设

- `reports/20261025_191111_prod-release-cut__delegate-nebflow-f12732c8/`：本报告 +
  `evidence-gateA-assertions.log`（89/89 全文）+ `evidence-gateB-e2e.log`（全链 E2E 全文）+
  `evidence-repro-daemon.out`（独立复现完整 daemon 日志，SOE 全帧在案）+ `evidence-repro-boot.log`。
- 复现脚本与告警包裹器在 `/tmp/cut-f12732c8/`（`fgwith.sh` perl-alarm 前台包裹、
  `e2e-run.sh` 形态复刻 M2 批、`battery-run.sh` M1 四连跑备用、`verify-placement.sh`
  只读指针解析器——均未投入生产使用）。
- 假设声明：①候选版本号 `2026.10.25-dev.7b41a525b` 与「VERSION 工作树临时覆写、不提交、
  不打 tag」是沿用今日 13:06 在产切版（`-dev.8ae0e5317`）的实测惯例，非本批发明；②红因
  机制判断（构造期 vs 运行期递归）以崩溃帧形态+崩溃时点双重实证为据，未做 CE 版本考古；
  ③本批零代码修复——修复属下批，且「红即停」铁律下本批不自行放宽。
