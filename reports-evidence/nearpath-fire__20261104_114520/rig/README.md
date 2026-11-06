# nearpath-fire rig（本机近路实测台）

**读 `../red/FINDINGS.md` 先**。本目录是可复跑的台子本体，FINDINGS 是结论与证据索引。

## 一条命令

```bash
sh setup-engine.sh      # 一次性：git archive HEAD → /tmp/nearpath-fire/engine-src + sbt 编译 + classpath
sh make-cert.sh         # 一次性：自签证书（SAN: mystand.space / localhost / 127.0.0.1 / [LAN IP]）
node gates.mjs          # 全矩阵（Chromium 16 轮 + WebKit 8 轮，约 45 秒），产出 ../red/
node summarize.mjs      # ../red/summary.json → markdown 表
node assert-zero-presence.mjs ../red/engine-<ts>.log   # PASS/exit 0 = 闸链零 presence 参与
```

## A/B（改前/改后）

```bash
RIG_WEB=/abs/path/to/web-override node gates.mjs
```
`RIG_WEB` 里的文件命中即本地直出（如 `js/ws.js`），未命中回落隧道（引擎 serve 注入页）。
**别把 index.html 放进 RIG_WEB**（服务端注入的 `__BRAND__ / __WEB_FINGERPRINT__ / NF_HOME` 会丢）。

## 端口

6410 隔离引擎 · 6411 闸③ 观测代理 · 7443 门户 ingress(https) · 7444 转发代理 · 18990 假中继。
**6138 = 生产引擎，永不使用。**

## 纪律

- 只 kill 自己 spawn 的引擎 pid（`startRig` 内部 `process.kill(-pid)`），绝不 `stand stop/restart`。
- 每次起台都重建 `/tmp/nearpath-fire/rig-home`（唯一 STAND_HOME）。
- 台子放宽了隧道 per-IP 预算（生产默认 1024/60s）——只放宽预算，不放宽判据。
