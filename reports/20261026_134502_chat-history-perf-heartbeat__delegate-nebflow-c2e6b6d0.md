# 台账 #922 · 上滑加载聊天记录慢 + 触发刷新/重连（渲染饿死心跳）修复批

日期：2026-10-26 · 分支：`delegate/delegate-nebflow-c2e6b6d0` · 面：聊天渲染（上滑补页）/ 分页闸 / WS 心跳

## 作者报障（2026-10-26 12:44 逐字）

「向上滑加载聊天记录的速度，很慢，而且还有可能会触发刷新，重连」。

## 根因链（实测证实）

假设链**成立**，且主元比预期更重：

```
上滑（scrollTop<100 触顶才拉）→ getHistory 整页 50 条（重会话单页 payload MB 级）
→ WS 大帧 JSON.parse（快，~3ms/3MB 实测，非主元）
→ restoreFromBackendHistory 整页同步构建：大工具正文 renderHighlightedContent
   （实测 60KB ~0.6s/条，正则重灾；每页可有多条）+ 卡片 iframe + markdown
→ 整页一枚主线程长任务（红腿实测 3.2s，fixture 单页）
→ 心跳宽限（5s）在阻塞中被吃掉 / pong 压在大帧后
→ miss 计入 → 连续 3 miss 自逐出 → 重连重放（作者感知 = 「刷新/重连」）
```

服务端核对：`SessionStore.getHistoryPage` = 热缓存内存切片 + `WsSessionChatHandlers.handleGetHistory`
直编帧，查询面非瓶颈（**未改服务端**；大页裁字段会破坏工具卡渲染语义，本批不做，见开放项）。

## 修复面（四刀，全部客户端）

1. **上滑预取**（main.js `_primScrollGate`）：触顶 `<100px` 才拉 → 预取带 `<400px`（`HISTORY_PREFETCH_PX`）；
   单飞闸不变。
2. **停顶续拉**（main.js `historyPage` 处理器 `onDone`）：用户已滚到顶且还有更旧页 ⇒ 不等下一次滚动手势，
   直接补下一页（`hasMore` 收口 / 非本会话 / 非停顶不续）。
3. **分帧构建挂载腿**（persistence.js `mountHistoryPageChunked` + `buildHistoryRow` 抽取）：
   - `buildHistoryRow` 从 `restoreFromBackendHistory` 的 `msgs.forEach` 逐字抽出（codemod 机械搬家，
     仅 3 处机械替换：skipMsg→markSkip/chat→chatEl/skip 消费移驱动器）——同步腿（初载/弹窗）与分帧腿
     **同一枚构建器，零第二实现**；
   - 补页按 8 条/片跨 rAF 切片构建；行在脱管容器就绪后由调用方**原子 prepend + 相对补偿**
     （绝对值式会夺走构建切片期间的手势滚动）；
   - 大工具正文（>16KB）进空闲升级批（4 任务/帧），markdown 升级批同队；批间对真实 chat 做
     **相对**补偿（阅读位钉死；同步腿的批间漂移是改前既有形态，本腿只吃自己的增量）；
   - **大正文跳语法高亮**（`TOOL_HEAVY_BODY=16384`，`toolBodyHtml` 单源）：>16KB 纯 `pre`
     （文字逐字同形，仅色面让位）——60KB+ 的 `renderHighlightedContent` 实测 ~0.6s/条，高亮一面纯成本；
     同步腿同享此刀（初载卡顿同源同修）；
   - 陈页守卫：`resetPagination()` 整替 pagination 对象（chatView.js:175 / sidebar.js:2194），
     `onBuilt` 用**对象身份**比对（数值比对在「新会话初载恰回同 offset」窄竞态下漏判）；
   - `loading` 闸持到 `onDone`（升级批 drain 完）——期间不会发出第二页请求。
4. **心跳渲染遮蔽门**（ws.js + 新 `chat/renderGate.js`）：
   - 门 = 引用计数「渲染在途」标记（零依赖叶子模块，静态环零增量）；补页挂载全程持门，
     ws.js 大帧（>256KB）`parse+派发` 持门；
   - 宽限回调读到 busy ⇒ miss 走**既有传输遮蔽同款旁路**（同一 `shelteredMisses` 计数、
     同一 `HB_TRANSFER_SHELTER_MAX=12` 硬上限、pong/新连接照常清零）——
     **不放松 `HB_MAX_MISSES=3`，不放松 sessionGate 重座阈值**（稳定性批 2026-10-26 语义原样；
     真死连接在遮蔽上限内照常判死）；
   - 诊断/spec 缝：`hbStats()`（misses/sheltered/maxRtt/renderBusy）、`_hbTestHooks().probe()`
     （受控触发真实 hbProbe，免等 30s 自然拍）、`window.__nfRenderGate`（begin/end/busy/depth）。

### 验证过程中揪出的第二只虫（同族，一并修）

帧到即放闸：改前 handler 入口**无条件** `view.pagination.loading = false`——补页改异步挂载后，
挂载窗内任何滚动事件都会打出**同游标重复请求** ⇒ 同页双挂载 ⇒ 历史行成对重复（spec 实测
`histReqs=["init",250,250,200,200,150,150,…]`、DOM 行 600/360）。修法 = 放闸收窄到初载腿与
迟到/重复帧两处，补页腿的闸跨挂载保持（main.js `historyPage` 处理器）。修后三连跑
`histReqs=["init",250,200,150,100,50]` 恰 6 请求零重复。

## 验证（spec `tests/chat-history-perf.spec.mjs`，300 条重会话夹具：60KB 工具正文 ×60 + markdown + 卡片图）

| 指标 | 修前树（git archive HEAD 红腿） | 修后（终态，三连跑稳定） |
|---|---|---|
| 单页主线程最长任务 | **3191ms**（整页一枚） | **0 个 longtask**（n=0，无 >50ms 任务） |
| 单页上滑落定 | 触顶才拉 + 无续拉 | **344–410ms/页**（含停顶续拉收口） |
| 分页全程 WS 连接数 | 1 | **1**（零重连） |
| 请求轨迹 | 无预取/续拉 | `init,250,200,150,100,50` 恰 6 发零重复 |
| 全程 heartbeat miss | 0（fixture 本地回环 pong 快；3-miss 逐出未端到端复现，见边界） | **0** + 受控遮蔽窗 sheltered=1 |
| sessionGate 横幅 / reload | 0 / 0 | 0 / 0 |
| JS 运行时错误 | 0 | 0 |

P4 遮蔽腿（受控窗，11/11 PASS）：渲染门持门 + 桩端压 pong 6.5s（>5s 宽限）⇒ 读数窗 `shelteredMisses=1,
misses=0`；pong 到场后计数清零（活性证据，maxRtt 记 6506ms）、连接不死。

### 回归（本机可跑面全绿 / 环境外两笔如实记）

- `reconnect-collapse` **9/9**（historyReplay 初载重放腿 = 构建器抽取的最大消费者，全绿）；
- `history-scroll-auth401` **7/7**（H1-b 断言随停顶续拉语义更新：首轮 3→9=两页并进，收口/零登出原钉保留）；
- `mdscroll-toc-scroll` 30/30、`busy-lifecycle` ALL PASS、`ws-card-lock` **12/12**（ws.js onmessage 面）；
- `history-replay-cards`：baseline 腿 PASS；parity 腿的 innerHTML 逐字比对在**修前树上同败**（红腿对照复验：
  askUser 卡 `style`/`disabled` **属性序**序列化差异，chromium-1243/Windows 环境伪差，非本批——
  属性写入次序属 live/replay 两腿既有差异，候归属批核）；
- `askuser-history-attach` 9/10：N1「两树逐字相等」撞 2026-10-06 askuser-hybrid-code 批的 `opt-other`
  类引入（56a44587e，基线 ref 更旧）——该批报告已自证此 spec 有既有 flake，非本批；
- `askuser-lockline-history`：需本机活实例（隔离 LLM 家环境依赖），本机未起实例，未跑。

环境顺手修（spec 基建，KAI Windows 检出可跑化，行为面不变）：`history-replay-cards` /
`askuser-history-attach` / `ws-card-lock` 的 `python3 -m http.server` → node 静态伺服（本机无 python）；
两处 `git archive | tar -C` 反斜杠路径被 bsdtar 吃掉的 POSIX 化；`history-replay-cards` 加 `NB_UX_ROOT`
红腿旋钮。

回归门：`check-circular` 仅同一既有 SCC[3]（popArtifacts/reference/msgQuote，与 main HEAD 同，零回归）；
`check-js-types` 257 < 基线 260（-3，renderGate.js 新文件 checkJs-clean）；`build-web.mjs` 186 源全入图
orphans:0。

## 边界与开放项

- **服务端未动**：无 Scala 改动（`sbt` 面零触碰）。大页「裁重字段」（Tool.input/content）会改变工具卡
  历史渲染语义（候作者裁）；字节预算分页（超预算缩页）牵动 beforeIndex 连续性语义，本批未做。
- **3-miss 逐出未端到端复现**：fixture 本地回环 pong 快，修前 3.2s 阻塞 < 5s 宽限 ⇒ 未凑满 3 连 miss
  （需要 >5s×3 连续阻塞或慢链整形）。机制代码可证（hbMisses≥3 → close），修复面同时移除阻塞源与
  遮蔽门内窗口。
- **弹窗分页腿未升级**：bgAgentPopup / flowAgentPopup 的上滑补页仍走同步 `restoreFromBackendHistory`
  （量小、面板场景）；需时可换 `mountHistoryPageChunked`。
- **Mac 仓只读**：本批全部改动在 KAI 工作区；bundle 已同步（build/web-dist，未跟踪文件）。
- 并行批共仓：工作区另有 #919/#920/#921/#926 未提交改动，本批提交**只圈自己文件**。

## 文件清单

- `src/main/resources/web/js/chat/renderGate.js`（新，零依赖）
- `src/main/resources/web/js/ws.js`（渲染遮蔽旁路 + 大帧持门 + hbStats/_hbTestHooks）
- `src/main/resources/web/js/persistence.js`（buildHistoryRow 抽取 + mountHistoryPageChunked + toolBodyHtml/TOOL_HEAVY_BODY）
- `src/main/resources/web/js/main.js`（预取带 + 停顶续拉 + 分帧挂载接线 + 陈页守卫 + 补页闸跨挂载）
- `src/main/resources/web/js/vendor-globals.d.ts`（__nfRenderGate 声明）
- `tests/chat-history-perf.spec.mjs`（新 spec，11 断言）
- spec 基建顺手修：`tests/history-replay-cards.spec.mjs`、`tests/askuser-history-attach.spec.mjs`、
  `tests/ws-card-lock.spec.mjs`（python3→node 静态伺服 / tar 路径 POSIX 化 / NB_UX_ROOT 旋钮）、
  `tests/history-scroll-auth401.spec.mjs`（H1-b 断言随停顶续拉语义更新）
