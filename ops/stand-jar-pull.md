# stand-jar-pull — KAI → Mac jar 流转（一页用法）

> KAI 主线迁移批（2026-10-26）落地件。方向：**KAI 出包，Mac 按需拉取替换 `stand start` 构建位**。
> 脚本与仓绑定、从仓内跑（同 `ops/release.sh` 分类，不入 live）。**实际替换等作者令**；
> 本页写的命令都可以先用 `--verify` / `--dry-run` 零触碰预演。

```bash
REPO="/Users/kaiyu/Claude code/Stand"   # 任意 stand 仓 checkout

# ① 只读对照: KAI 最新产物 + Mac 指针现状（零写入）
bash "$REPO/ops/bin/stand-jar-pull" --verify

# ② 全流程演练: 下载→SHA 对账→落位→切指针 全在临时沙箱 root
bash "$REPO/ops/bin/stand-jar-pull" --dry-run

# ③ 实际替换（等作者令）: 拉 KAI 最新 jar → versions/<v>/ 原子落位 → 切 current-version
bash "$REPO/ops/bin/stand-jar-pull"
```

## 它做什么（替换面）

1. `ssh kai` 列 KAI 固定出包位取最新：`C:/Users/Kai/stand/target/scala-3.5.2/stand-assembly-<v>.jar`
   （sbt assembly 标准出包，版名来自仓 VERSION 文件；脚本认文件名）。
2. `scp` 到 `<root>/incoming/*.part`，**certutil(KAI) ↔ shasum(Mac) SHA256 逐字节对账**，不合即弃。
3. 原子落位 `versions/<v>/stand-assembly-<v>.jar`（同卷 `.part` → `mv` rename）；同名旧包先
   时间戳备份 `.bak-YYYYMMDD-HHMMSS`。
4. 指针原子切换 `current-version ← <v>`，旧指针记入 `previous-version`（`release.sh` 同语义）。
5. **`stand start` 指针解析验证**：按 `ops/bin/stand` POINTER-SELECT 同源逻辑只读复演，打印
   下次 start 将运行的 jar 绝对路径。**绝不 exec jar、绝不触碰 6138/在产 daemon**。

新 jar 生效仍需显式 `stand stop && stand start`（already-running 守卫会拦双启动；替换脚本不重启）。

## 常用变体

```bash
bash ops/bin/stand-jar-pull --version 2026.10.25-dev.86e67a91f   # 指定版本（须在 KAI 上存在）
bash ops/bin/stand-jar-pull --root /tmp/sandbox-bin              # 覆写发布根（测试/演练）
bash ops/bin/stand-jar-pull --host kai2                          # 换 ssh 别名（默认 kai, ~/.ssh/config）
KAI_JAR_DIR="D:/build/out" ops/bin/stand-jar-pull               # 换 KAI 出包目录
```

发布根默认 `$STAND_HOME/bin`（`STAND_HOME` > `NEBFLOW_HOME` > `~/.stand`，与 launcher 同优先级），
即 `ops/bin/stand` 的 POINTER-SELECT 读的同一布局。

## KAI 侧前置

- 出包：KAI 仓 `sbt assembly`（迁移基线起 KAI 构建链已证活，读数见
  `reports/20261026_002700_kai-migration-phase1/REPORT.md`）。
- ssh 通道：Mac `~/.ssh/config` `Host kai`（Tailscale 100.91.165.120）；BatchMode 免密。
- 基线同步：Mac→KAI 走 bundle 通道（`git-receive-pack` 在 Windows PATH 缺位，push 直连不可用）：
  `git bundle create /tmp/x.bundle <kai-head>..main && scp` → KAI `git fetch <bundle> main && git merge --ff-only FETCH_HEAD`。

## 回滚

```bash
printf '%s\n' "$(cat ~/.stand/bin/previous-version)" > ~/.stand/bin/current-version
# 或直接把指针写回任一还在 versions/ 下的版本包（版本包不可变，放回即用）
```
