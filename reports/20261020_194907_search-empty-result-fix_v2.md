# 搜索消息记录返回空（零结果）修复批 v2 — 实施报告

日期：2026-10-20 19:49 · 分支：delegate/delegate-nebflow-2ea9aac2 · 性质：bugfix + 真形状集成腿

## 一、结论

控制台「搜消息记录零结果」的机制 = **fetchHistory 把一切非 2xx 吞成 `[]`**
（`src/main/resources/web/js/chatSearch.js:820`，修复前），叠加作者日常接入面
（手机层十屏 P2 搜索面 + 桌面 current 面）恰好只走 fetchHistory 这一条取数路：

- 中继隧道抖动窗内（实测 2026-10-20 16:50 隧道断连，28 条在途 portal http cycle
  同时 "Output closed"，控制台收 502/504/截断体），`/history` 全部非 2xx ⇒
  被吞成 `[]` ⇒ 面板渲染**假的「0 条结果」**，传输故障与真空数据不可分辨。
- 同族取数点 `fetchSessionList`（:798）与 `chatSearchFloat` 跳转腿
  （chatSearchFloat.js:324）本来就是「非 2xx 即抛」——**唯一吞错点就是
  fetchHistory**。scope=all 面在故障窗反而报错（fetchSessionList 先抛），
  手机钉 current 面 / 桌面 current 面则稳定假零——与「作者日常被堵」的
  症状面完全吻合（作者日常 = 手机控制台，P2 面恒钉主会话）。

修复：非 2xx 诚实化（404=合法空，其余抛 ⇒ 既有 catch ⇒ showError+重试钮；
2xx 但 `messages` 缺失/非数组=截断体同罪抛）。共 13 行，单文件单函数。

## 二、真后端复现（不许只靠 mock 夹具——本批的在役实测）

在役实例 = 本机 8080（PID 24919，静态资源与 HEAD 逐字节一致，diff=0）。
真浏览器（Playwright chromium，console-ticket 链建立会话）三轮只读探针：

| 面 | 关键词源 | 结果 | 耗时 |
|---|---|---|---|
| 桌面 scope=current（active=Nebula, 800 msgs） | 真实 user 行取词 | 280 条结果 | 169ms |
| 桌面 scope=all（**6362 会话**全量扫描） | 高频词 | 33552 条结果 | ~30s，6364 次 /history，**零错误** |
| 手机层 P2（375×812，钉 current） | 同桌面 | 主会话 · 100 条结果 | <1s |

⇒ **传输健康时三个面全部正常**——排除了「P2 重写把搜索写坏」的方向；
真形状普查（REST 全量 6361 会话 + 40 会话抽样 /history）：全部 200 +
`messages` 数组在场，类型 user/tool/ai/system/askUser，行键含
attachments/eventType/header/injected/sender/source——真形状健康路径不会
误伤修复后的抛错腿。零结果的唯一可达机制 = 传输故障窗 × fetchHistory 吞错。

## 三、根因与修复（file:line）

- 根因：`src/main/resources/web/js/chatSearch.js:820`（修复前）
  `if (!resp.ok) return [];` —— 吞一切传输故障。
- 修复（同文件 :820-832）：404 ⇒ `[]`（scope=all 扫 6362 会话时单会话消失
  不炸全队）；其余非 2xx ⇒ `throw new Error(\`HTTP ${resp.status}\`)`；
  2xx 但体损坏（`messages` 缺失/非数组）⇒ 抛 `malformed history payload`。
  下游零改动：runSearch/runStream 既有 catch（:1087/:1313）⇒
  `showError`（:1492）=「搜索失败: … + 重试」钮，同面板点重试即恢复。

## 四、真数据形状集成腿 spec

- `tests/search-realshape.spec.mjs`（4 用例，全绿）：夹具
  `tests/fixtures/search-realshape/{history,sessions}.json` = 在役真实 API
  payload 抓取后脱敏保形（键集合/在缺性/类型/嵌套逐行原样，107 行
  systematic 1/8 抽样五类，注入行/附件行/无 ts 行在场；sessions 行键
  agentName/createdAt/hasUnread/id/name/safetyMode/updatedAt 与在役实测逐键一致）。
  - T1 真形状关键词搜索三腿可检索（user/ai/tool 种植行全中）；
  - T2 真形状 browse 流（101 可检索行、首页 cap 100、RENDER_BATCH 50）；
  - **T3 传输诚实化（本批回归腿）**：502 ⇒ 「搜索失败…502 + 重试钮」，绝不
    假零；重试腿故障解除同面板恢复；截断体（200 无 messages）同罪；
    404 ⇒ 合法空态无错误面；
  - T4 手机 P2 面真形状（主会话 · N 条结果 + 角色筛选缝）。

## 五、回归盘点

| 族 | 结果 |
|---|---|
| search-realshape（新） | 4/4 绿 |
| mobile-p2-search | 4/4 绿 |
| msg-search-v3-smoke / stream-smoke | 18/18 + 18/18 绿 |
| pr41-locale-search-jump | 11/11 绿 |
| friend-search-layout | 全绿（RESULT: PASS） |
| busy-lifecycle | ALL PASS |
| mobile-v3 | 7/7 绿 |
| quote-message | 12/16 —— **4 红腿为存量**：干净 HEAD 暂存本批修复后 A/B 同跑同样 4 failed/12 passed（chip 几何三腿 + 换行溢出一腿，eae95e3be 引用宽度批自带面），与本批零交集 |
| card-persist-fe-search | 17/18 —— ③ zero console.error（404 资源）同为存量：干净 HEAD A/B 同跑同样 17/18 |
| checkJs 闸 | 本批触碰件 chatSearch.js **零报错**；闸面红清单（agentProfile/bgStatusFace/floatController/memoryFace/socialPanel/canvas）全为并行批已落库存量，本批零触碰 |
| build-web | 绿（orphans 0） |

## 六、在役验证与遗留

- 修复后代码未上在役（在役实例归作者重启窗管）：但修复仅改错误路径语义，
  健康路径行为与 HEAD 逐字节等价（200+messages ⇒ 返回 messages），在役
  三面健康探针即健康路径回归证据。
- 遗留①（存量，非本批）：quote-message 4 红腿、card-persist ③ 404 资源腿、
  checkJs 闸六件库存红——建议单独开批。
- 遗留②：scope=all 健康路径也要 6362 次 /history ≈30s（本机回环）；隧道面
  更慢。诚实化后故障窗会显式报错而非假零，但全量扫描的量级面归
  「全中继面本地加速」设计批（1389b4de5/eaa8444e4）M1+ 分期处置。
