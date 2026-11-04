# 三档选路（Tailscale 直连 → 自研 P2P → relay）· 证据索引

作者裁定 2026-11-04 16:51（逐字）：「然后我们再加一条 就是tailscale是专门做这个的 我们也检测 如果有tailscale的直连服务 就用它的 然后再用我们的 最后relay」。

知识文档：`<dataRoot>/projects/stand/.stand/docs/20261104_173000_route-tiers-tailscale-p2p-relay__delegate-nebflow-4d537ce5.md`

## 取数命令（任何人可重跑同一组数字）

```bash
# ① 真机探测判决 / 缓存「不重复收税」/ 三态 A·B·C 改前→改后对照
sbt "Test/runMain nebflow.core.tools.RouteTriStateMeasureMain" | tee reports-evidence/20261104_route-tiers/tristate.log

# ② IPv6 优先的真机候选面（改前只取 Inet4Address 的等价读数在 preV6Behaviour 字段）
sbt "Test/runMain nebflow.neblink.LocalEndpointReadoutMain" | tee reports-evidence/20261104_route-tiers/ipv6.log

# ③ 判据 spec（三态 / 缓存退避 / 解析 / 平台路径 / IPv6 排序）
sbt "testOnly nebflow.core.tools.DirectRouteSpec nebflow.neblink.PresenceCandidateProbeSpec nebflow.neblink.EndpointPreferenceSpec"
```

## 读数的判据（对着哪条验收看）

| 文件 | 验收项 |
|---|---|
| `tristate.log` 的 `MEASURE real_probe` | 真机 Tailscale = `Stopped` 下探测判决 + 有界耗时（预算 800ms） |
| `tristate.log` 的 `MEASURE cache` / `cache_prod` | 连续 10 次选路 = 1 次真探测；命中缓存 `probeMs=0` |
| `tristate.log` 的 `MEASURE tristate_A/B/C` | 三态改前（fail-open）/改后（门控）的 `via` / `reasons` / 白付毫秒 |
| `tristate.log` 的 `MEASURE tristate_lan_regression` | tailscale 停时 LAN 直拨零回归（`via=direct`） |
| `ipv6.log` | v6 前置 + v4 留槽 + 无 v6 环境恒等 |

> 边界：本批**没有**跑「真机端到端远端命令」——那需要重启生产网关实例（本批明令禁重启），
> 运行中的实例跑的是 main 的代码。命令腿上的**增量成本**因此用「选路判据本身」的耗时读数代替
> （见 `tristate.log` 的 `before.paidMs` vs `after.paidMs`）。
