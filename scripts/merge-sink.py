#!/usr/bin/env python3
"""merge-sink — 参数化合并 sink 驱动（**模板**；合并节点专用，见 `.nebflow/Spec/20260907_merge-node-design-guide.md`）

把一次「先验后合」的合并事务按设计指南 §1-§6 的判据机械化：**串行锁 → 锁后复读 → 与在飞
sbt 错峰 → 预门禁 → `merge --no-ff` → 双父核验 → 合并态后门禁 → worktree/分支清理**，
全程**零 push / 零 tag / 零 VERSION 改动 / 零重启 / 零信号**（脚本内不含 push/tag/kill/pkill）。

## 为什么要在飞 sbt 判据（本脚本的由来，2026-09-12 R4）

首版 sink 驱动把「在飞 sbt」判据写成「进程命令行里出现 sbt」——把**长命 `sbt run` 服务**
（宿主/隔离实例的 fork 子 JVM）也计入，于是错峰等待永远不结束、被 60min 超时收割（门禁与
合并**零动作**；见 wd-fix 批报告 §6.2 R4）。修正后的判据（`sbt_compile_test_busy`）：
**只把「含 `sbt-launch.jar` 且尾参 ∈ compile / testOnly / test / assembly / scalafmt / scalafix」
的进程计为在飞**，`run` 型长期服务与 `sbt-args` 包装进程排除在外。本脚本把该判据**固化进
仓内跟踪路径**（`scripts/`），并有 `--selftest` 直接对合成命令行回归该判据。

## 用法

```
# 完整体（合并 + 清理）
python3 scripts/merge-sink.py --branch wd-fix-impl \
    --specs "nebflow.core.processor.TaskStuckWatcherSpec nebflow.core.tools.ToolPhaseStuckAxisSpec"

# 只验不合（预检 + 预门禁后停手，不产生任何合并）
python3 scripts/merge-sink.py --branch <B> --specs "<suite …>" --dry-run

# 在飞判据自测（不碰 git / sbt）
python3 scripts/merge-sink.py --selftest
```

## 参数（全部可覆盖；脚本内**无**硬编码分支名 / 无硬编码绝对路径 / 不依赖某一批的 spec 清单）

| 参数 | 缺省 | 说明 |
|---|---|---|
| `--branch B` | （必需，`--selftest` 除外） | 待合并的特性分支 |
| `--into` | `main` | 合并目标分支（只读用，`--into` 的 tip 会前后复核） |
| `--repo` | `git rev-parse --show-toplevel`（当前目录） | 主仓路径（合并节点沙箱 = 主仓工作区） |
| `--worktree` | `<repo>/.nebflow/worktrees/<branch>`（存在才清） | 该分支的 worktree，清理用 |
| `--specs` | 空 = 只跑 `compile`（头部打 `NOTE:`） | 空格分隔的 suite 全名 |
| `--specs-file` | 无 | 每行一个 suite（批自己的清单放批内文件，别写进脚本） |
| `--merge-msg` | `merge(<branch>) into <into>` | 合并提交信息 |
| `--lock` | `<repo>/.nebflow/tmp/merge-gate.lock` | 互斥锁（BSD `flock(2)` `LOCK_EX`，与 `flock(1)` 同原语） |
| `--lock-wait-max` | `600` s | 等锁上限；超时 = `BLOCKED`（零动作） |
| `--sbt-wait-max` | `1200` s | 与在飞 sbt 错峰的上限；超时打 `NOTE:` 后放行（有界等待，不无限饿死） |
| `--merge-attempts` | `5` | 瞬时 `index.lock` 的有界前台重试次数（每次退避 20s） |
| `--dir` | 脚本所在仓根 | 报告日志目录（缺省 `<repo>/.nebflow/tmp/`，gitignore 面） |
| `--dry-run` | 关 | 预检 + 预门禁通过后**停手**（不 merge、不清场） |
| `--no-cleanup` | 关 | 合并后保留 worktree / 分支（默认清理） |
| `--selftest` | 关 | 只跑在飞判据自测（合成命令行），不碰 git / sbt |

退出码：`0` 成功；`1` 工作区脏（锁后复读）；`2` 预门禁红（未合并）；`3` 合并失败/冲突（已
`merge --abort`）；`4` `--into` tip ≠ 合并提交；`5` 合并态后门禁红（**已落地**，不做破坏性回滚）；
`9` 等锁超时。

## 纪律（与设计指南一致）

- **零 push / 零 tag / 零 VERSION 改动**：脚本不调 `git push`、不调 `git tag`、不写 `VERSION`
  （前后各打印一次 `VERSION` 读数供对账）；合并目标分支只被 `merge --no-ff` 推进。
- **`--no-ff` 无条件**、最小写 `-d` 自证全合并（拒 = 未完全合并 ⇒ 留置上报，脚本不 `-D`）。
- **宿主安全**：脚本**不含** kill/pkill/信号；只读 `lsof` 记录 8080 监听集合，前后不一致即
  打 `VIOLATION:` 并停手（AGENTS.md「隔离实例标准启动配方」的 post-flight 判据）。
- **有界等待**：等锁 / 等 sbt / 合并重试都有上限，超时一律「打印原因 + 明确退出码」或
  `NOTE:` 放行——不无限饿死、不静默。
- 上游 commit-ready 未落（worktree 脏）时**先由合并节点按上游申报清单代提交**（指南 §2.2 /
  §7.7），本脚本的锁后 `status` 复读会把脏工作区判为 `BLOCKED`（退出码 1）。
"""
from __future__ import annotations

import argparse
import datetime
import fcntl
import os
import subprocess
import sys
import threading
import time

LOG_FH = None


def ts() -> str:
    return datetime.datetime.now().strftime("%H:%M:%S")


def log(msg: str) -> None:
    line = f"[{ts()}] {msg}"
    sys.stdout.write(line + "\n")
    sys.stdout.flush()
    if LOG_FH is not None:
        LOG_FH.write(line + "\n")
        LOG_FH.flush()


def emit(chunk: str) -> None:
    sys.stdout.write(chunk)
    sys.stdout.flush()
    if LOG_FH is not None:
        LOG_FH.write(chunk)
        LOG_FH.flush()


def gout(cmd, cwd) -> str:
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    return (r.stdout or "").strip()


def run(cmd, cwd, label="") -> int:
    """前台流式跑一个子进程 + 30s 心跳（父进程只读子进程输出，不发送任何信号）。"""
    log("$ " + " ".join(cmd))
    t0 = time.time()
    p = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                         bufsize=1)
    done = threading.Event()

    def hb():
        n = 0
        while not done.wait(30):
            n += 1
            log(f"  ..[{label}] heartbeat {n * 30}s (child pid {p.pid})")

    threading.Thread(target=hb, daemon=True).start()
    try:
        for line in p.stdout:
            emit(line)
    finally:
        p.wait()
        done.set()
    log(f"  rc={p.returncode}  elapsed={time.time() - t0:.0f}s")
    return p.returncode


# ── 在飞判据（本脚本的固化对象，2026-09-12 R4）────────────────────────────────

#: 尾参白名单：只有这些才是「真在跑的 sbt 编译/测试」。
BUSY_TAILS = ("compile", "testOnly", "test", "assembly", "scalafmt", "scalafix")


def classify_sbt_cmd(cmd: str):
    """纯函数：一行进程命令行 → `None`（不算在飞）或 `(why)` 字符串（算在飞）。

    与 `flock` 无关，供 `--selftest` 直接回归。判据（逐条，缺一不可）：
      1. 命令行含 `sbt-launch.jar`（sbt 启动器的唯一标识；长命 `sbt run` 服务与
         `sbt-args` 包装进程同样含它，故还要看尾参）；
      2. 尾参**不以 `run` 开头**（`sbt run` 服务是长命的，不是「在飞编译/测试」）；
      3. 命令行**不含 ` --port`**（隔离实例服务的形态）；
      4. 命令行**不含 `sbt-args`**（sbt 的 fork 包装层，其真实工作在子 JVM 里）；
      5. 尾参里出现 `BUSY_TAILS` 中任一个 token ⇒ 在飞。
    """
    if "sbt-launch.jar" not in cmd:
        return None
    tail = cmd.split("sbt-launch.jar", 1)[1].strip()
    if tail.startswith("run"):
        return None
    if " --port" in tail or "sbt-args" in cmd:
        return None
    for k in BUSY_TAILS:
        if k in tail:
            return f"tail~{k}: {tail[:100]}"
    return None


def sbt_compile_test_busy():
    """扫全进程表，返回第一处在飞的 (pid, why) 或 None（只读 `ps`，不发信号）。"""
    out = subprocess.run(["ps", "-Ao", "pid=,command="], capture_output=True, text=True).stdout
    for ln in out.splitlines():
        ln = ln.strip()
        if not ln:
            continue
        pid, _, cmd = ln.partition(" ")
        why = classify_sbt_cmd(cmd)
        if why:
            return pid, why
    return None


def selftest() -> int:
    """在飞判据自测：合成命令行 → 期望分类。不碰 git / sbt / 网络。"""
    cases = [
        # (命令行, 期望算在飞?)
        ("/usr/bin/java -jar /Users/x/.sbt/launchers/1.10.10/sbt-launch.jar -batch "
         "testOnly nebflow.core.processor.TaskStuckWatcherSpec", True),
        ("java -jar sbt-launch.jar compile", True),
        ("java -Dsbt.override.build.repos=true -jar sbt-launch.jar test", True),
        ("java -jar sbt-launch.jar assembly", True),
        # 长命服务（宿主/隔离实例）——必须排除，否则错峰等待永不结束（R4 事故形态）
        ("java -jar sbt-launch.jar run --home /tmp/qa-x --port 8095 --no-browser start", False),
        ("java -jar sbt-launch.jar run", False),
        # sbt fork 包装层（真实工作在其子 JVM 里）
        ("java -jar sbt-launch.jar @/var/folders/T/sbt-args123.tmp", False),
        # 与本仓无关的进程
        ("/usr/bin/java -jar other.jar compile", False),
        ("python3 scripts/merge-sink.py --branch x", False),
    ]
    bad = 0
    for cmd, want in cases:
        got = classify_sbt_cmd(cmd) is not None
        ok = got == want
        bad += 0 if ok else 1
        print(("PASS " if ok else "FAIL ") + f"want_busy={want} got_busy={got} | {cmd[:96]}")
    print(f"--selftest: {len(cases) - bad}/{len(cases)} PASS")
    return 0 if bad == 0 else 1


# ── 主流程 ───────────────────────────────────────────────────────────────────


def main() -> int:
    global LOG_FH
    ap = argparse.ArgumentParser(add_help=True, description="参数化合并 sink 驱动（先验后合）")
    ap.add_argument("--branch", help="待合并的特性分支")
    ap.add_argument("--into", default="main", help="合并目标分支（缺省 main）")
    ap.add_argument("--repo", help="主仓路径（缺省 = git rev-parse --show-toplevel）")
    ap.add_argument("--worktree", help="该分支的 worktree 路径（缺省 .nebflow/worktrees/<branch>）")
    ap.add_argument("--specs", default="", help="空格分隔的门禁 suite 全名（缺省只跑 compile）")
    ap.add_argument("--specs-file", help="每行一个 suite 的文件（与 --specs 合并）")
    ap.add_argument("--merge-msg", help="合并提交信息")
    ap.add_argument("--lock", help="互斥锁文件（缺省 <repo>/.nebflow/tmp/merge-gate.lock）")
    ap.add_argument("--lock-wait-max", type=int, default=600)
    ap.add_argument("--sbt-wait-max", type=int, default=1200)
    ap.add_argument("--merge-attempts", type=int, default=5)
    ap.add_argument("--dir", help="报告日志目录（缺省 <repo>/.nebflow/tmp）")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--no-cleanup", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    if a.selftest:
        return selftest()
    if not a.branch:
        ap.error("--branch 必需（或用 --selftest）")

    repo = a.repo or gout(["git", "rev-parse", "--show-toplevel"], os.getcwd())
    if not repo or not os.path.isdir(repo):
        print("BLOCKED: 无法定位仓根（--repo 指错或不在 git 仓内）")
        return 9
    B, INTO = a.branch, a.into
    WT = a.worktree or os.path.join(repo, ".nebflow", "worktrees", B)
    LOCKP = a.lock or os.path.join(repo, ".nebflow", "tmp", "merge-gate.lock")
    LOGDIR = a.dir or os.path.join(repo, ".nebflow", "tmp")
    os.makedirs(LOGDIR, exist_ok=True)
    LOG_FH = open(os.path.join(LOGDIR, f"merge-sink-{B.replace('/', '_')}.log"), "a")
    MSG = a.merge_msg or f"merge({B}) into {INTO}"

    specs = [s for s in a.specs.split() if s]
    if a.specs_file:
        with open(a.specs_file, encoding="utf-8") as f:
            specs += [l.strip() for l in f if l.strip() and not l.startswith("#")]
    only_compile = not specs
    sbt_cmd = ["sbt", "-batch", "compile"] + (["testOnly " + " ".join(specs)] if specs else [])

    ver_before = gout(["git", "show", f"{INTO}:VERSION"], repo)
    host8080_before = gout(["bash", "-c",
                            "lsof -nP -iTCP:8080 -sTCP:LISTEN -t | sort | tr '\\n' ' '"], repo)
    log(f"=== merge-sink start: {B} → {INTO} ({'DRY-RUN' if a.dry_run else 'LIVE'}) ===")
    log(f"repo = {repo}")
    log(f"lock = {LOCKP}  worktree = {WT}  dir = {LOGDIR}")
    log(f"VERSION before = {ver_before}")
    log(f"8080 listeners before = [{host8080_before}]")
    if only_compile:
        log(f"NOTE: 未给 --specs/--specs-file ⇒ 门禁只跑 compile（建议给出受影响面 suite 清单）")

    # ── ① BSD flock 串行（LOCK_EX；与 flock(1) 同原语）──
    os.makedirs(os.path.dirname(LOCKP), exist_ok=True)
    lf = open(LOCKP, "a+")
    t0, got = time.time(), False
    while time.time() - t0 < a.lock_wait_max:
        try:
            fcntl.flock(lf.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            got = True
            break
        except OSError:
            log(f"  lock busy, retry (waited {time.time() - t0:.0f}s)")
            time.sleep(3)
    if not got:
        log(f"BLOCKED: {LOCKP} 未在 {a.lock_wait_max}s 内取得（零动作退出）")
        return 9
    log(f"LOCK acquired (waited {time.time() - t0:.0f}s)")
    try:
        # ── ② 锁后复读（status / merge-base / tip / 交付预览）──
        st = gout(["git", "status", "--porcelain"], repo)
        log(f"G1 status --porcelain = [{st[:400]}]")
        if st:
            log("BLOCKED: 工作区脏（上游 commit-ready 未落？先按申报清单代提交）——零动作退出")
            return 1
        mb = gout(["git", "merge-base", INTO, B], repo)
        tip_locked = gout(["git", "rev-parse", INTO], repo)
        log(f"G2 merge-base({INTO},{B}) = {mb}")
        log(f"G2 {INTO} tip (post-lock) = {tip_locked}")
        log("G2 交付预览 " + f"git log --oneline {INTO}..{B}:")
        emit(gout(["git", "log", "--oneline", f"{INTO}..{B}"], repo) + "\n")
        emit(gout(["git", "diff", "--stat", f"{mb}..{B}"], repo) + "\n")
        log(f"G2 {INTO} 侧自 merge-base 起变更的文件: " +
            gout(["bash", "-c", f"git diff --name-only {mb}..{INTO} | tr '\\n' ' '"], repo))
        # ── ③ 冲突预检（设计指南 §1②）──
        mt = subprocess.run(["git", "merge-tree", "--write-tree", INTO, B], cwd=repo,
                            capture_output=True, text=True)
        log(f"G3 merge-tree --write-tree rc={mt.returncode} "
            f"(0 = CLEAN; 输出 {((mt.stdout or '').strip())[:80]})")
        if mt.returncode != 0:
            log("BLOCKED: merge-tree 预检非 CLEAN（冲突面）——零动作退出")
            return 3
        # ── ④ 与在飞 sbt compile/test 错峰（有界等待 + 超时 NOTE 放行）──
        tw = time.time()
        while True:
            b = sbt_compile_test_busy()
            if not b:
                log("sbt compile/test contention: none")
                break
            if time.time() - tw > a.sbt_wait_max:
                log(f"NOTE: sbt contention window exhausted ({a.sbt_wait_max}s), proceeding; "
                    f"last busy pid={b[0]} ({b[1]})")
                break
            log(f"  sbt busy (pid {b[0]}: {b[1]}) -- stagger wait 15s")
            time.sleep(15)
        # ── ⑤ 预门禁（合并前基线树）──
        if run(sbt_cmd, repo, label="pre-gate") != 0:
            log("BLOCKED: 预门禁红（**未合并**；代码未落地，零副作用退出）")
            return 2
        if a.dry_run:
            log(f"DRY-RUN: 预检 + 预门禁全过，停手（未 merge、未清场）；{INTO} tip 仍 = "
                f"{gout(['git', 'rev-parse', INTO], repo)}")
            log("=== DONE DRY-RUN OK ===")
            return 0
        # ── ⑥ 落地：git merge --no-ff（瞬时 index.lock 有界前台重试）──
        ok, out = False, ""
        for attempt in range(1, a.merge_attempts + 1):
            log(f"--- merge attempt {attempt} ---")
            st = gout(["git", "status", "--porcelain"], repo)
            if st:
                log(f"  pre-merge status dirty = [{st[:200]}] -- backoff 20s")
                time.sleep(20)
                continue
            log(f"  merge-base now = {gout(['git', 'merge-base', INTO, B], repo)}")
            r = subprocess.run(["git", "merge", "--no-ff", B, "-m", MSG], cwd=repo,
                               capture_output=True, text=True)
            out = (r.stdout or "") + (r.stderr or "")
            emit(out + "\n")
            if r.returncode == 0:
                ok = True
                break
            if "index.lock" in out and not os.path.exists(os.path.join(repo, ".git", "MERGE_HEAD")):
                log(f"  瞬时 index.lock（第 {attempt} 次）-- 前台退避 20s 重试")
                time.sleep(20)
                continue
            break
        if not ok:
            if os.path.exists(os.path.join(repo, ".git", "MERGE_HEAD")):
                log("merge 留下 MERGE_HEAD → git merge --abort")
                emit(subprocess.run(["git", "merge", "--abort"], cwd=repo,
                                    capture_output=True, text=True).stdout)
            log("BLOCKED: 合并失败 / 冲突（已 abort；未落地）")
            return 3
        post = gout(["git", "rev-parse", "HEAD"], repo)
        parents = gout(["git", "log", "-1", "--format=%P", post], repo)
        log(f"POST MERGE sha = {post}")
        log(f"parents = {parents}  ({len(parents.split())} 父；期望 2 = --no-ff 合并提交)")
        if len(parents.split()) != 2:
            log("ALERT: 合并提交非双父（--no-ff 语义未成立）——按设计指南 §2.3 上报")
        if gout(["git", "rev-parse", INTO], repo) != post:
            log(f"BLOCKED: {INTO} tip ≠ 合并提交")
            return 4
        # ── ⑦ 后门禁（合并态树重跑）──
        if run(sbt_cmd, repo, label="post-gate") != 0:
            log("ALERT: 合并态后门禁红（**合并已落地**；不做破坏性回滚，交合并节点/分发器处置）")
            return 5
        # ── ⑧ 清场（worktree remove + branch -d 自证）──
        if a.no_cleanup:
            log("NOTE: --no-cleanup ⇒ 保留 worktree / 分支")
        else:
            if os.path.isdir(WT) and not gout(["git", "status", "--porcelain"], WT):
                run(["git", "worktree", "remove", WT], repo, label="wt-remove")
            elif os.path.isdir(WT):
                log(f"NOTE: {WT} 工作区脏 ⇒ 不做 worktree remove（留置上报）")
            else:
                log(f"NOTE: {WT} 不存在 ⇒ 跳过 worktree remove")
            run(["git", "branch", "-d", B], repo, label="branch-del")
        # ── ⑨ 对账 ──
        log(f"git log --oneline {INTO}..{B} = [{gout(['git', 'log', '--oneline', f'{INTO}..{B}'], repo)}]")
        log(f"git branch --list {B} = [{gout(['bash', '-c', f"git branch --list '{B}' | tr '\\n' ' '"], repo)}]")
        log(f"git status --porcelain = [{gout(['git', 'status', '--porcelain'], repo)[:200]}]")
        ver_after = gout(["git", "show", f"{INTO}:VERSION"], repo)
        log(f"VERSION after = {ver_after}  (before = {ver_before})")
        if ver_after != ver_before:
            log("VIOLATION: VERSION 被改动（本脚本零 VERSION 纪律）")
        host8080_after = gout(["bash", "-c",
                               "lsof -nP -iTCP:8080 -sTCP:LISTEN -t | sort | tr '\\n' ' '"], repo)
        log(f"8080 listeners after = [{host8080_after}]")
        if host8080_after != host8080_before:
            log("VIOLATION: 8080 宿主监听集合发生变化——停手上报（本脚本零信号纪律）")
            return 6
        log("=== DONE OK （零 push / 零 tag / 零 VERSION / 零重启 / 零信号）===")
        return 0
    finally:
        fcntl.flock(lf.fileno(), fcntl.LOCK_UN)
        lf.close()
        log("LOCK released")
        if LOG_FH is not None:
            LOG_FH.close()


if __name__ == "__main__":
    sys.exit(main())
