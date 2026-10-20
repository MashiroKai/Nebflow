## 附录 A · 证据与复现

### A.1 关键测量证据

- `evidence/20261019_082500_imgslow538-segmented-timing.md`：#538 分段计时表——本机直连腿全链中位 ≈45ms（铸票 1.0ms + 兑换 2.8MB 11.7ms + 解码 2.5ms 等）；远程腿（neblink.nebflow.space）活窗中位 ≈2.79s（62×），大头 = 字节穿隧 1531ms（55%）+ 铸票/兑换两段隧道往返各 ~0.6s；另有死窗 15min 全程 403 的可用性叠加问题。
- `evidence/20261019_imgslow538-timing/local-timings.json`：N=12 HTTP 层 + N=10 浏览器层原始读数（304 = 1.6ms；Range 256KB = 17.9ms）。
- `evidence/20261020_imgdoor542-three-tier__delegate-nebflow-f7679358/`：#542 三档批证据目录。

### A.2 验收 spec 位置

- `tests/nffile-three-tier.spec.mjs`（站1-8：tab-first / 零取数 / 失败可见 / 进度环 / 铸票合帧 / LRU+配置 / 接线钉 / 缓存头引擎面）；票据 mock：`tests/nf-ticket-mock.mjs`。
- 服务端：`src/test/scala/nebflow/gateway/NfFileRoutesSpec.scala`（#538 缓存头钉 `:480-486`）、`NfTicketRoutesSpec.scala`、`NfPathEncodingProbeSpec.scala`。

### A.3 管线图复现

```bash
cd reports/20261020_154500_data-plane-local-accel-design
dot -Tsvg -o pipeline.svg pipeline.dot   # graphviz 14.1.5（Mac brew）
# 暗色适配已注入 SVG 内 <style> media query（亮暗双主题可读）
```
