# presence 拨号并发化 + 搬离 compute-blocker —— 红/绿原始证据（2026-11-04）

作者 13:05 批「可以派」。四件事：P1 并发化 / P2 搬离 `io-compute-blocker` / P3 单一可达性真源 /
P4 退避与可观测。

## 目录

| 路径 | 内容 |
|---|---|
| `red/measure-legacy.json` | 改前口径（`PresenceDialSchedule.LegacySerial`：串行 + `IO.blocking` + 无共用真源）的全部实测行 |
| `green/measure-production.json` | 改后口径（`PresenceDialSchedule.Production`：并发上限 4 + `presence-dial-*` 专用池 + 共用 `P2pReachability`）的全部实测行 |
| `red/full-run-raw.log` / `green/full-run-raw.log` | 同一进程的完整原始输出（两臂在同一进程、同一方法、同一端点集内先后跑） |
| `specs/*.log` | spec 运行日志（红→绿判据的机器可判形态） |
| `regress-*.log` | 既有套件回归（Total/Failed） |

## 五项硬验收读数（速查）

| 项 | 改前 | 改后 |
|---|---|---|
| 一轮拨号总耗时（KAI 两候选，真机黑洞） | 4680 ms | **3021 ms**（≈max 而非 Σ） |
| compute 线程被占用 / 轮 | 4511 ms | **0 ms** |
| 负缓存命中单轮 | 4514 ms | **0 ms** |
| 同池文件打开同形任务 p99（12 线程池 × 12 路拨号） | 82 ms | **11 ms** |
| 端点全不可达整轮上限 | 5019 ms（风暴臂：12 路合计占 compute 30071 ms） | **2512 ms**（compute 占用 0 ms） |


## 方法（为什么这组数可对比）

1. **同一个方法**：两臂都跑生产代码本体 `NeblinkPresenceService.connect(peer)`，只翻
   `PresenceDialSchedule` 三个开关。不是两套实现对比。
2. **同一个端点集**：`kai` = 真机 KAI 两候选（`100.91.165.120:6138` tailnet /
   `192.168.1.145:6138` LAN，实测均为 SYN 黑洞）；`blackhole-rfc5737` = RFC 5737 两地址
   （确定性黑洞，任何机器可复现同一组数）。
3. **线程归属取自生产读数面**：`dialThreadStats` 的 `msOnCompute` 就是「compute 线程被这轮
   拨号占用的时长」，不是另埋的探针。
4. **每轮新账本**（冷轮）= 真冷；负缓存轮复用同一账本 ⇒ 直接读「命中即 0ms」。

重跑：
```
CP=$(cat target/streams/test/fullClasspath/_global/streams/export)
java -cp "$CP" nebflow.neblink.PresenceDialMeasureMain
```

## 硬约束遵守

- 未重启 / 未 kill 生产实例 pid 78176；未起第二个占 6138 的实例（`NeblinkService.createForTest`
  不绑定端口）。
- 实测夹具在 `/tmp` 独立 dataRoot 上跑，进程自退（`IORuntime.global.shutdown()`）。
