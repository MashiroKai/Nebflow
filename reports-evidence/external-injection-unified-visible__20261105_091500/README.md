# external-injection-unified-visible（外部注入统一通道 + 蓝气泡可见化）

作者报障 2026-11-05 08:44 逐字：「我还发现一个问题，就是所有的外部注入消息应该有统一的途径和蓝色气泡通知，我刚刚看到你好多条消息触发你，我都看不到是什么」。

判据与读数逐条对应批报告 §判据；本目录只放**过程证据**，结论在 vault
`/Users/kaiyu/.stand/projects/stand/.stand/docs/external-injection-unified-visible__20261105_091500.md`。

| 文件 | 是什么 | 读数 |
|---|---|---|
| `scala-red.log` | 改前必红（可见性门 + 去重闸临时还原为重写前形态后跑新 spec） | `Failed: Total 11, Failed 3` —— ①可见性总函数 / ②来源标签口径 / ⑥折叠（`sbt` EXIT=1） |
| `scala-green.log` | 改后必绿（6 个 spec 聚合） | `Passed: Total 45, Failed 0, Errors 0`（EXIT=0） |
| `frontend-red.log` | 前端改前必红（摘要行临时摘除后的反向变异） | `2 failed / 6 passed`，失败读数为 `summary: null` |
| `frontend-green.log` | 前端改后必绿（Playwright，两条渲染腿 × 四条判据） | `8 passed`（EXIT=0） |
| `frontend-static-gates.log` | 五道前端静态门禁（component-contract / design-tokens / self-explaining / msgstyle / circular） | 全部 PASS，零基线抬升 |
| `scalafmt.log` | `sbt scalafmtCheckAll` | EXIT=1，但失败仅 2 个**存量无关文件**：`perception/PerceptionService.scala:1211`、`gateway/PerceptionDaemonLifecycleSpec.scala:62`（本批零改动，改前即红）；本批所改文件零 scalafmt 报错 |
