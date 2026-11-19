# Agent instructions

## Worktrees 与临时产物

项目 worktree 一律放 `<project>/.stand/worktrees/<wtname>/`；交付物不可放 worktree 内，放 `<project>/.stand/tmp/<wtname>/delivery/<batch>/`，过程证据放同级 `evidence/`。tmp TTL 为 7 天，证据永不提交；需要入库的交付物随合并进仓 `delivery/<batch>/`。`MANIFEST.md` 逐文件记录路径、字节数、mtime、工具对账生成的未入库或入库时间与 commit SHA；不要手填。用 `python3 scripts/stand-wt.py new|list|audit|clean` 管理，clean 默认 dry-run，未入库默认保留。忽略 `.stand/worktrees/` 和 `.stand/tmp/`。

## Branch and release discipline

- Use `feat/*` or `bug/*` for every batch branch. Existing `fix/*` branches are grandfathered; do not rename them retroactively.
- Do not push directly to USTC. Only the author opens USTC PRs and pushes release branches.
- After upstream verification, only the author lands changes on `main`; a promotion is recorded as a **merge commit** (`merge: promote <line> to main`) (author ruling 2026-11-19). For release defects, create `bug/*` from `main`; after the fix reaches `main`, the author chooses which release line to update.
- Follow the complete branch model, guard instructions, and server-protection checklist in `CONTRIBUTING.md`. An override flag does not grant permission.

## 信号纪律

- 禁线的唯一目的是保护正在运行的实例不被误杀：按 PID、端口和数据根识别，不要 kill 或重启我们正在跑的实例（例如 PID 50915、端口 6138）。
- 为测试或验证自己启动的隔离实例（JVM）由创建者负责回收：记录启动返回的精确 PID，先执行 `kill -TERM <pid>`，核验 PID 不存在且端口无监听；必要时再执行 `kill -KILL <pid>`，并再次核验退出。
- 禁止按模式大范围杀进程，例如 `pkill -f java`、`killall java`、`pkill sbt`，以免误伤正在运行的实例和他人的构建。
- 隔离实例必须核验退出，并保留核验输出作为证据；放宽信号限制不取消此要求。
