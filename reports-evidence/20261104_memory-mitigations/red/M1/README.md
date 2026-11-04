# M1 RED（改前基线）— 2026-11-04 13:27:56–13:27:57

场景: 同机 3 个并发 `sbt -batch about`（/tmp/m1/{a,b,c} 三个最小工程，sbt 1.10.10），
每 0.25s 采样一次进程组 RSS（ps pid-walk，sbt 家族 = sbt-launch / sbt-args / xsbt.boot / sbt.xMain）。

读数（sample.csv 全量见同目录）:
- 本组（3 个并发调用）sbt 家族进程数峰值 **3**（同时存活 3 个重活 JVM —— 无并发闸）
- 本组 JVM RSS 峰值 **1123MB**（含宿主生产实例时段背景时 3 个全跑到 ~370MB each）
- 全机 sbt 家族（含生产 pid 78176）峰值 **6 个 / 4925MB**

采样粒度 0.25s，150s 预算窗实际 18 样本（sbt 冷启动+退出的实际活跃窗）。
采样器: /tmp/m1/sample.py（ps 快照 → 家族匹配 → CSV）。
