# 对端头像代返 502（上游 301）· 本批实测读数

批次：2026-11-04 旧域 301 批（chain `delegate-nebflow-a6b3f239`）。
知识文档：`~/.stand/projects/stand/.stand/docs/avatar-peer-redirect-follow-and-byte-cache__20261104_210500__delegate-nebflow-a6b3f239.md`

| 文件 | 内容 |
|---|---|
| `upstream-301-curl.txt` | 上游事实：旧域今天 301 到 `staging.mystand.space`（2026-11-04 20:50:34 CST，curl 原始响应头） |
| `red-sbt.log` | **改前必红**：`sbt testOnly nebflow.gateway.PeerAvatarRouteSpec`，四条新用例全红，签名 `Status(502)` / `upstream HTTP 301` / `upstream HTTP 302`（2026-11-04 20:53:12 CST） |
| `green-sbt.log` | **改后必绿**：同 spec 30/30 通过 + `[EVIDENCE]` 读数（200 / bytes=5 / sha256=74f81fe1…60d0 / cache=hit / upstreamHits 停在 1） |
| `node-gates.log` | `make frontend-gates` exit 0 |
| `js-types.log` | `node scripts/check-js-types.mjs` ⇒ 0 errors（baseline 0，未抬高 `tests/type-baseline.json`） |

判据：`upstreamHits` 在第二次请求（含 `If-None-Match` 的 304 判定）后**仍为 1** ⇒ 走的是网关字节缓存，
零回源。sha256 与独立计算 `sha256(01 02 03 04 05)` 一致
（`74f81fe167d99b4cb41d6d0ccda82278caee9f3e2f25d5e5a3936ff3dcec60d0`）。
