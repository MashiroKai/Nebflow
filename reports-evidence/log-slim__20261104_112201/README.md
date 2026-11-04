# log-slim__20261104_112201 —— 日志降噪批 C 证据目录

作者令 2026-11-04 10:43（「日志那个也要修 + 必须交付真实实测结果对比」）的实施证据。
报告正文：`~/.stand/projects/stand/.stand/docs/log-noise-reduction__20261104_110500.md`。

## 目录

| 文件 | 是什么 |
|---|---|
| `log-noise-stats.sh` | 统计脚本（可复现；任意日志文件，无需引擎） |
| `red/baseline-snapshot-1052.txt` | **改前基线**（冻结快照 112 125 行，一次原子通过） |
| `red/baseline-20261104.txt` | 同一脚本在活文件上的一次读数（10:52:47，111 084 行 —— 说明活文件一路在长） |
| `red/traffic-model.txt` | 两族流量画像（设备/通道/连接态/状态分布/字节区间/会话前缀） |
| `red/sample-lines.txt` | 改前原文取样 + 失败面取样（本批不降级的那些行） |
| `green/aggregate-run.log` | **改后实测 A 轮**（缺省安静形态）盘上全部 6 行 |
| `green/per-line-run.log` | **改后实测 B 轮**（`per-line` 回切形态）盘上原文头 5 + 尾 3 |
| `green/line-counts.txt` | A/B 同流量行数与字节对照（`wc -lc` 实测） |
| `green/tests-neblink.log` | `TransportLogSpec` + neblink 包回归 spec 的输出 |

## 复现改前基线

```bash
cp ~/.stand/logs/stand.log /tmp/logslim-snapshot.log     # 冻结（活文件一路在长）
shasum -a 256 /tmp/logslim-snapshot.log                  # 7f012e25028f…b368f
bash log-noise-stats.sh /tmp/logslim-snapshot.log
```

## 复现改后实测（真机 A/B，隔离，绝不碰生产）

跑车 = `src/test/scala/nebflow/shared/LogNoiseHarness.scala`：独立 JVM，用**生产 logback.xml 原文**
写 `$STAND_HOME/logs/stand.log`，每 125 ms 各打一条 presence + session-sync（每族 8 行/s，
对齐现网 7.9 行/s），跑 125 s。

```bash
D="$PWD"   # 在仓库根跑

# A 轮：缺省（安静）
STAND_HOME=/tmp/logslim-c-agg2 sbt -batch \
  "set Test / fork := true" \
  "set Test / javaOptions ++= Seq(\"-Dlogback.configurationFile=$D/src/main/resources/logback.xml\", \"-DSTAND_HOME=/tmp/logslim-c-agg2\")" \
  "Test/runMain nebflow.shared.LogNoiseHarness --durationSec 125 --label aggregate"

# B 轮：回切开关
STAND_HOME=/tmp/logslim-c-line2 sbt -batch \
  "set Test / fork := true" \
  "set Test / javaOptions ++= Seq(\"-Dlogback.configurationFile=$D/src/main/resources/logback.xml\", \"-DSTAND_HOME=/tmp/logslim-c-line2\", \"-Dnebflow.log.transport.mode=per-line\")" \
  "Test/runMain nebflow.shared.LogNoiseHarness --durationSec 125 --label per-line"

wc -lc /tmp/logslim-c-agg2/logs/stand.log /tmp/logslim-c-line2/logs/stand.log
```

结果：A = 6 行 / 1 897 B；B = 2 000 行 / 358 861 B（同 2 000 次调用）。

## 隔离纪律（本批实际遵守）

- `STAND_HOME=/tmp/logslim-*`（独立数据根），`sbt Test/runMain` 的 fork JVM 自跑自退；
- **从未调用 `stand stop` / `stand restart`**；生产实例 pid 41715 全程零信号；
- 未使用任何生产账号 token 发往作者其它设备（跑车只走日志面，不发网络帧）；
- 跑完清理 `/tmp/logslim-*`。
