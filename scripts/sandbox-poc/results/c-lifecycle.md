# PoC-c per-task 容器生命周期（image=nb-poc-sbx:alpine, retain=15s, ttl=20s）

| phase | ms | note |
|---|---|---|
| create | 119 |  |
| start_cold | 200 |  |
| exec_task | 117 | 写状态+8MB blob |
| stop | 1155 |  |
| start_warm | 147 |  |
| auto_destroy | 156 | TTL 到期 docker rm -f |

PASS=8 FAIL=0
