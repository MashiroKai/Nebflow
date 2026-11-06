# M4 GREEN（改后）— 2026-11-04 13:32:02（launchd 自动取到新脚本）/ 13:32:09 实测

新探针端口解析链（不写死）: MEMSAMPLE_PORT > $STAND_HOME/.pid → lsof -a -p <pid> -iTCP -sTCP:LISTEN
> $STAND_HOME/nebflow.pid > 已知缺省端口（6138/8080）> NA。
新增列（append-only，前 9 列位置不动）: hostPid, hostPort, compressedMB, totalMemMB。

生产实例实跑（同一行三真值）:
  hostRSS_MB=701.8 / compressedMB=3862.0 / swapUsedMB=2597.7（hostPid=78176 hostPort=6138）
  → 改前同一列 hostRSS=NA、无 compressed 列。

旁证: 无 .pid 场景（STAND_HOME=/tmp/emptyhome）回落缺省 6138 仍取到 78176/6138（见 m4fallback.log）。
