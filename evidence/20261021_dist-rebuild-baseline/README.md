# 合并后 main 重建 web-dist · 基线读数（收尾小批 2026-10-21）

- 触发：收尾小批「并 128000 上限分支 + 重建 dist」步骤 2；合并提交 `4bde9649a`（`delegate/delegate-nebflow-0b640e55` @ `f1a10aad2`，--no-ff 双父 `acebb974c`+`f1a10aad2`）之后从合并后 main 重建
- 重建：`npm run build`（scripts/build-web.mjs）→ `build/web-dist/`；js entry `assets/app-EE6L54KN.js`，mobile bootstrap `assets/mobile/bootstrap-YVXYHWQ4.js`，160 lazy chunks，175 sources 全入图（orphans 0）
- 新鲜度：`find src/main/resources/web -type f -newer build/web-dist/index.html` = **0**；对照 dist 内最新文件（vendor/xlsx.full.min.js）再核 = **0** —— web 源零超前
- playwright 软链：main worktree node_modules 里 `playwright → /opt/homebrew/lib/node_modules/playwright`（+playwright-core）已就位（合并批先例在位，未补）

## 基线读数（同法同器复测 perf 批口径）

- 方法：perf 批原器原法 —— `serve.mjs`（stand-in：gzip+ETag+no-cache，/assets/** 仿 W2 immutable）喂 `build/web-dist` @127.0.0.1:8098；`measure.mjs`（Chrome headless CDP，fresh profile 真冷，+3s settle）
- 读数（direct-cold 真冷，两次采样）：

| | 请求数 | 线上字节 | js 请求 | DCL / loadEvent |
|---|---|---|---|---|
| perf 批基线（dist-predict） | 62 | 0.87MB | 45 | 134/147ms |
| **本次重建 dist** | **63** | **0.88MB** | **46** | 163-172/187-196ms |

- **+1 差异定位**（哈希归一化逐请求 diff）：非 /assets 面 21 请求与基线逐条相同（含同样的 404 探针组与 `/api/neblink/status`、`/api/canvas-tabs` ×2 重复）；assets 面 41→42，**匿名 lazy `chunk.js` 34→35 个** —— perf 基线之后 main 新并批（mobile key-badge 等 6+ 提交）带来的 boot 图多一块 chunk，**零新分类、零缺席**；chunk 聚合线上字节 415.0→418.9KB（+3.9KB），总量 0.873→0.877MB
- DCL 134→163-172ms：+1 chunk 解析增量 + 机器采样方差；本地冷阈值 <2s 内，非回归面
- tunnel-cold 腿（同 profile 第二航）0.00MB 线上字节 = SW SWR 缓存即答，与 perf 批 README 注记语义一致（非异常）

## 目录

- `results/direct-cold.json`、`results/tunnel-cold.json`（首采样，sbt 回归并行期）· `results/direct-cold-idle/direct-cold.json`（空闲复测采样）
- 器具沿用 `evidence/20261021_console-load-perf/`（serve.mjs / measure.mjs 未改动）

## 同批回归读数（步骤 1b，合并树 llm+gateway 家族）

- `sbt "testOnly nebflow.llm.* nebflow.gateway.*"`：**1066 测 0 失败 0 错误**（133s，合并提交 `4bde9649a` 树）——分支树既有 3 红（CliRouterSpec×1、BackoffSupervisorEmptyBurnSpec×2）均不在 llm+gateway 家族内，本家族全绿
