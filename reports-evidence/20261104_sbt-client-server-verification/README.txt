sbt 常驻 server（sbt --client）· 独立复核取证包
================================================
批: 2026-11-04 sbt 常驻 server 复核（delegate-nebflow-3f40b03d）
判定: 不落地（默认维持现状＝每次冷启）。设计与读数见
      <dataRoot>/projects/stand/.stand/docs/20261104_150500_sbt-client-server-verification__delegate-nebflow-3f40b03d.md
      条件性建议（ops 侧）见 ops/README.md「sbt 常驻 server」节。

环境（测量当时，本机 16 GB / 12 核 / JDK23 / sbt 1.10.10）
  swap: total 4096.00M used 2518.62M (61%) free 1577.38M
  compressed: Pages occupied by compressor 267432 (~4.2 GB)
  load1: 11–14
  并发背景: 另有生产实例 JVM ~1.8–2.6 GB + 其他 delegate 会话在编译（全部读数含此背景）

文件
----
baseline-3wt.log / .summary.tsv
    现状模式（每调用冷启）在 3 个**全新 worktree** 上各跑一次 sbt compile 的原始输出与汇总。
    用途: 「新 worktree 首次调用」这一类（server 帮不上的一类）的基线。
    读数: 98.7 / 84.0 / 88.5 s（总 271.2 s），单 JVM 峰值 RSS 2 013–2 145 MB，
          全部 java 合计峰值 4.3–4.5 GB，闸排队 0 s。
    原始输出确证: `[info] compiling 741 Scala sources to …/target/scala-3.5.2/classes`

ab-server.txt
    A 组（同 worktree 重复调用 · 现状）: 6.7 / 4.9 / 5.1 s（mean 5.6 s），峰值 RSS 481–641 MB。
    注: 本文件 B 段的首轮因「常驻 server 经闸垫片启动 → 永久占住重活锁」而作废（见下条），
        该坑本身是重要发现，已写进知识文档 §3.2。

b-server.txt
    B 组（同 worktree 重复调用 · 常驻 server，真 sbt 启动）:
      boot→ready 4.0 s；空闲 RSS 369 MB；首连 1.6 s；重复 0.6 / 0.6 s；
      编译后空闲 RSS 617 MB → 636 MB。

c-agg.txt
    C 组（聚合形态：1 个 JVM 承载 3 个构建根，RootProject 聚合）:
      冷启 116.6 s（3 构建根；单构建根 4.0 s）；空闲 RSS 587 MB；
      每构建根首触达 8.4–9.5 s（**比冷启更慢**）；重复 0.6–0.9 s。
    用途: 证伪「跨构建根共享物理上不可能」，同时给出不采用它的理由（成本）。
    注: 首轮 boot 因 `build.sbt` 里 `IO.read(file("VERSION"))` 的相对路径失败
        （java.io.FileNotFoundException: VERSION），向聚合根摆 VERSION/brand.conf 副本后通过——
        这条坑写进知识文档 §4。

d-isolation.txt
    隔离实证（最硬验收：多 worktree 不串台；且是在**同一 JVM 多构建根**这个更难的形态上做的）:
      三 worktree 各植入唯一标记类 → 只编 wt1 ⇒ wt1 标记类 2 个、wt2/wt3 各 0 个；
      wt1 target 新/改动文件 18 个，wt2/wt3 **各 0 个**；
      再编 wt2 ⇒ 标记类各归各位（wt1 只有 Probe1、wt2 只有 Probe2）；
      同构建根再编一次 ⇒ 0 s 成功（增量热态各自保留）；
      产物根路径证据: 各自落在 /private/tmp/sbtbench-N 下。

闸日志真实流量（独立复算，非估算）
    样本 ~/.stand/logs/sbt-gate.log（2026-11-04 13:32 起）:
      n = 68 次调用, median 11 s, mean 50.4 s, 总墙钟 3 429 s
      分桶: 0–5s:4 · 5–15s:36 · 15–30s:6 · 30–60s:8 · 60–180s:5 · 180s+:9
      ≤15 s 短调用占 59%; 85 分钟内出现 14 个不同 cwd（活跃 6–8 个 worktree）
    与另一批（n=55 / median 11 s / mean 49.7 s / 短桶 56%）**分布同形**。

复测
----
采集器已随闸归本机设置层（作者裁定 2026-11-04 14:31：构建预算属本机、不进产品仓）:
  BENCH=~/.local/share/nebflow-local/bin/sbt-server-bench.sh
  bash "$BENCH" <label> -- <worktree> "compile" -- <worktree2> "compile"
产出 $BENCH_OUT/<label>.log + .summary.tsv（wall / sbtRssMB / allRssMB / gateWait / rc）

归属迁移说明: 读数采集于 14:24–14:46，期间 14:37 闸从 ops/bin/sbt-gate 外置到
~/.local/share/nebflow-local/bin/sbt-gate（部署位 ~/.stand/bin/sbt 改为 symlink）。
前后语义逐字一致（并发 1 / -Xmx1500m / 900s / SBT_GATE=off）⇒ 本目录读数跨迁移有效。
