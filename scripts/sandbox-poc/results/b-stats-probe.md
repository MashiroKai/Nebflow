# PoC-b docker stats --no-stream 采样（n=32）

| 指标 | 值 |
|---|---|
| 单次调用延迟 min/median/p95/max | 1061 / 2069 / 2089 / 2106 ms |
| 满载容器 CPUPerc mean±stdev | 100.06% ± 0.16%（理论单核 ≈8.3%）|
| 满载容器 CPUPerc min/max | 99.83% / 100.48% |
| idle 容器 CPUPerc mean/max | 0.000% / 0.000% |
| 对照：全容器一次调用 | 2089 ms |
| 对照：单容器限定一次调用 | 1965 ms |
