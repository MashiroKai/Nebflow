# M1 GREEN（改后）— 2026-11-04 13:32:24–13:32:33

同一实验（3 个并发 `sbt -batch about`，/tmp/m1/{a,b,c}），sbt 现解析到 PATH 首位
`~/.stand/bin/sbt`（= ops/bin/sbt-gate 全局闸）。

读数（sbt-concurrent-sample.csv）:
- 本组 sbt JVM 并发峰值 **1**（改前 3）—— 闸串行化生效
- 单个 sbt JVM RSS 峰值 **369MB**；堆帽改前默认 -Xmx3g → 改后 `-Xmx1500m`
- 全机 sbt 家族计数仍可见他人会话的 sbt（-Xmx3g），见报告「已知边界」

闸日志（~/.stand/logs/sbt-gate.log 片段）:
  13:32:24 waiting for lock (timeout 900s; escape hatch SBT_GATE=off) [cwd=/tmp/m1/b]
  13:32:24 acquired after 0s   [cwd=/tmp/m1/c]  heap inject SBT_OPTS+=-Xmx1500m
  13:32:27 release after 3s / acquired after 3s [cwd=/tmp/m1/b]
  13:32:31 acquired after 7s   [cwd=/tmp/m1/a]

selftest-output.txt = ops/test/sbt-gate.test.sh 全绿（6 组断言：串行 / 排队可见 / 逃生口 /
服务形态旁路 / 堆帽与 override / 超时放行）。
