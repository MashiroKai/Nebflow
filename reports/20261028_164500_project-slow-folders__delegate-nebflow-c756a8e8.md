# 项目文件夹加载过慢排查+修复（DOSTEL 项目卡「加载中…」）

批次：project-slow-folders · 2026-10-28 · 作者报障（15:5x 截图：DOSTEL 项目卡文件区「加载中…」久久不出）

## 一、结论（热点在哪）

**文件列表本身不慢，慢（挂死）在「帧送达 + 前端无界等待」。** 三层证据：

1. **后端目录遍历早已是逐层懒加载，实测毫秒级**（`listDir` WS 帧 → `FsOps.listDirEntries`
   = 单层 `os.list`，无递归、无 git 命令）：

   | 测量 | DOSTEL（18k 文件在盘） | 小项目基线（likemeter） |
   |---|---|---|
   | REST `POST /api/command` listDir 根目录 | 1.9–6.6 ms | 1.3–2.4 ms |
   | 真实 WS 帧 listDir 根目录 | **1–2 ms** | 0–1 ms |
   | 子目录 `03_文献调研` | 1 ms | — |
   | `GET /api/projects`（卡片列表） | 3.5 ms | — |
   | `GET /api/projects/DOSTEL/flow-map`（卡摘要） | 8.5 ms | 7.8 ms |

   七类大整理后 ~1400 跟踪文件/目录加深**没有**放大首屏：首屏只列根一层 18 项。
   DOSTEL 与小项目同数量级 ⇒ 「目录遍历慢」假设排除。

2. **事故时段（15:5x）引擎日志取证 = 送达链拥塞**：
   - `tunnel-per-ip-cap` 429 风暴**峰值恰好 15:56–16:00**（53/94/48/74 条/分钟，
     全部来自同一 portal 末端 IP 210.45.118.11 —— 作者经 portal 中继浏览的控制台，
     其 /api/chains、/api/delegates 轮询族成批被 429）；
   - neblink 中继 `portal uplink inbox full` 同窗 231 次（64/64 满，"relay slower
     than the local peer"）——小帧（dirListing）在大流量后排队，**迟到数十秒**；
   - 本机侧同窗 listDir 零失败、零 outbound-queue 拆线 ⇒ 拥塞在 portal 链路。

3. **前端把「迟到/丢帧」放大成「永挂」**（真正的缺陷，本批修复）：
   - explorer `loadDir` 本机/远端腿**均无超时**：dirListing 不回来 ⇒ `.explorer-loading`
     行永挂；
   - 更重的是**死锁**：丢帧后 `pendingLoads`/`loadingDirs` 永不清理 ⇒ 三条自愈腿
     （fsChanged→reloadVisibleDir、focus→refreshExpandedDirs、慢对账 reconcileTick）
     全被各自 `!loadingDirs.has()` 闸挡住——**没有任何路径能把树救回来**，
     直到用户重开面板。这就是截图里「加载中…久久不出」的机制。

另发现（放大器，非首屏成因）：DOSTEL `.nebflow/worktrees/` 存 8 个已登记 + 2 个
残留 delegate 工作树（**6.7 GB / 14,579 文件**，含多份 `papers/figs/_tools/node_modules`
拷贝）。懒加载下不影响首屏，但污染树视图、放大整树重载与 watch 面；建议作者择机
跑引擎侧 worktree GC（DOSTEL 仓本批只读，未动）。

## 二、修复（改动面）

全部前端、零帧形变化、零 API 变化；在产引擎（pid 70481/6138）零触碰，随下一波包生效。

1. **explorer.js —— listDir 有界等待**（`LOAD_TIMEOUT_MS=10s`，1 次原始发送 + 1 次静默重发）：
   - 每次发帧挂定时器；dirListing 到达即摘表；
   - 一次超时 ⇒ **静默重发**（对端恢复后丢帧自愈，不闪错误）；
   - 再超时 ⇒ 既有错误行 + [重试] 钮（远端腿 B3 同款 UI），并释放 `pendingLoads`/
     `loadingDirs` ⇒ 三条自愈腿恢复工作（死锁解除）；
   - `openExplorerAt`/`refreshExplorer` 清场腿同步摘定时器；
   - e2e seam：`window.__nfExplorerLoadTimeoutMs`（生产恒默认）。
2. **nodeData.js —— fetchFlowMap 12s 有界等待**（AbortController，同 NODE_RESULT
   12s 先例）：挂死不再让项目卡摘要骨架永挂 —— 超时上抛 ⇒ `loadCardSummary` 既有
   catch 落 `unknown` 态（idle 文案 + title 保持可点）。e2e seam：
   `window.__nfFlowMapTimeoutMs`。
3. **locales**：新增 `explorer.listTimeout`（zh-CN / en）。

## 三、前后对比

| 场景 | 修复前 | 修复后（实测） |
|---|---|---|
| dirListing 丢失/迟到 | 「加载中…」**永挂**，自愈腿全死锁 | 静默重发自愈；仍无应答 ⇒ **≤20s 落错误终态 + [重试]**（e2e seam 350ms 档实测：自愈 ~0.9s、终态 ~1.3s、focus 自愈重拉 ~1.6s） |
| 卡摘要 fetchFlowMap 挂死 | 骨架脉冲永挂 | ≤12s 落 `unknown`（idle 文案，title 可点） |
| 正常路径（本机 DOSTEL 首屏） | 1–2 ms（WS listDir） | **不变**（应答到达即摘定时器，零新增往返） |

> 说明：本机首屏本就是毫秒级（上表一）；作者截图的「久久不出」是 portal 链路丢帧/
> 迟到 + 前端永挂的合成，修复把无界等待压成有界可恢复，链路恢复后树可自愈。

## 四、验收

- 新 spec：`tests/explorer-listdir-timeout.spec.mjs`（3 用例：静默重发自愈 / 错误终态
  + 重试钮 / 死锁回归钉=终态后 focus 腿能重拉根目录）**3 绿**；
  `tests/project-flowmap-timeout.spec.mjs`（挂死超时 AbortError / 预算内不误杀 /
  快速成功零回归 / 默认 12s 常量钉）**7 绿**。
- 既有文件树家族回归：nffile-three-tier、nffile-503-backoff、pickeruni-unified、
  ui-device-faces **4 绿**；explorer-nebflow-dir **2 红 = main 存量同判**（对照实验：
  4 个改动文件回写 HEAD 纯净版复跑，同样 2 红，失败点同为文件打开预览步，与本批
  无关；03dcc4789 提交信息已记录该存量红）。
- 门禁：`node scripts/check-js-types.mjs` **PASS**（257 < 基线 260，零新增）；
  `npm run build`（esbuild）**绿**；`sbt compile`（641 Scala 源）**绿**。

## 五、上报不修（越权边界外，交运营/后续批）

1. **DESKTOP-IJBBEOT presence 拨号风暴**：该机整天占满 64/64 per-IP WS 槽并在
   拒绝后无退避重拨（10 点档 9189 次拒绝/小时 ⇒ 16 点档 606），且 per-ip 桶为
   presence+console 共用 ⇒ 该机上若开控制台会被自己的风暴锁死。旋钮
   `GATEWAY_CONN_GUARD_WS_PER_IP_CAP` / trustedIps；根治在该机的引擎构建
   （#950/#960 僵尸回收之后的时代构建 + 拨号退避）。
2. **tunnel-per-ip-cap 共享桶**：campus NAT（210.45.118.11）后多查看者聚合即触发
   1024/min 429 风暴（全天 1669 次全出自该 IP）。旋钮
   `GATEWAY_CONN_GUARD_TUNNEL_PER_IP_CAP`；更公平方向 = 按会话/按脸分桶（引擎批）。
3. **中继 uplink 队头拥塞**（inbox-full 231 次/15min）：小控制帧在大载荷后排队，
   属 neblink 引擎侧队列公平性课题。
4. **DOSTEL 卫生**：`.nebflow/worktrees` 6.7 GB 残留（§一.3）——建议引擎侧 GC。

## 六、文件清单

- `src/main/resources/web/js/explorer.js`（有界等待 + 死锁解除）
- `src/main/resources/web/js/nodeData.js`（fetchFlowMap 12s 超时）
- `src/main/resources/web/js/locales/zh-CN.js`、`en.js`（explorer.listTimeout）
- `tests/explorer-listdir-timeout.spec.mjs`、`tests/project-flowmap-timeout.spec.mjs`（新）
- 本报告：`reports/20261028_164500_project-slow-folders__delegate-nebflow-c756a8e8.md`
