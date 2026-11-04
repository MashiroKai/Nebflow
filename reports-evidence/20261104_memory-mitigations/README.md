# 20261104 内存即时缓解四条（M1-M4）证据

| 项 | 红（改前） | 绿（改后） |
|---|---|---|
| M1 sbt 全局串行闸 | red/M1（并发 3 个 sbt / 本组 RSS 1123MB / 全机 6 个 4925MB） | green/M1（并发 1 / RSS 369MB / 自测 6 组全绿） |
| M2 重启脚本可观测性 | red/M2（调用方死读管道 ⇒ 落盘 0 行，全程无日志） | green/M2（18 行含终态；失败场景 13 行含失败原因） |
| M3 工具输出溢写 | red/M3（100MB → 堆 +13027KB，可见 10MB，全量丢弃） | green/M3（100MB → 堆 +499KB，可见 128KB，全量 100MB 落盘） |
| M4 memsample 探针端口 | red/M4（hostRSS=NA，无 compressed 列） | green/M4（hostRSS 701.8 / compressed 3862.0 / swapUsed 2597.7 同列） |

采集时刻: 2026-11-04 13:18–13:35（本机 MacBook, 16GB, 生产实例 pid 78176 **全程未被重启/kill**）。
