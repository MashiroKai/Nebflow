> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# v3.1 消息搜索 · 复评审报告（四道防线 · 第三道 · F-1 修复后复审）

- 日期：2026-08-18 00:20 · 评审人：design-engineer · 对象：feat/msg-search-v3 @**033616f1**（worktree /tmp/nb-msg-search-v3，未改动）
- 前次报告：`20260817_msg-search-v31-visual-review.md`（FAIL：F-1 阻断 + N1/N2 非阻断）
- 依据：规格工作副本 `assets/20260817_msg-search-v31-fix/message-search-spec-v3.with-n1-f1-clauses.md`（§6.1/§6.3 补条款）+ visual-style 铁律
- 范围：只测修复点（F-1 两路径、N1 短弹窗态）+ 前次 PASS 关键项回归抽查 ×3（玻璃弹层/暗色/375px）
- **结论：PASS**（F-1 两路径全过、N1 双主题全过、3 项回归无破坏；0 阻断 0 非阻断新增）

## 交叉验证声明

不裸看图：每条结论 = 截图 + DOM computed/几何 + 请求级取证三证。独立重跑自有探针（未使用 Frontend 自证截图作判据；其 b13-path-a-fixed-dark.png / n1-shortmodal-*.png 与本评审独立采集结果一致）。qa 全套重跑并行中，其 B13 子断言结果由 Manager 关卡汇合。

## 环境事件与处置（重要，影响前次探针可信度）

复测首次重跑原探针 `/tmp/dr-v31-b13-probe2.mjs` 时两路径**均零请求**——非修复失效，而是**种子数据午夜漂移**：探针用 `NOW-1000000ms` 作为"today"基准，跨午夜后（00:04）"today"60 条实际落在昨日，锚点日（昨日）成为最新有内容的一天，`s.start=0` → `loadAfterPages` 守卫（`start>0`）正确空转，语义上本就无更晚内容可取。处置：重写固定种子探针 `dr-v31-rereview-b13.mjs`（正午基准绝对日期：今日×60 / 昨日×60 / 前日×30，锚昨日→今日为"更晚"）。**原探针结论仍以固定种子版为准**。[qa 教训：时间相对种子在午夜 ±20min 窗口内日期归属漂移，建议种子一律用正午基准绝对日期]

## ① F-1 · B13 锚点态向上触发死区 —— PASS（两路径）

修复方案 = 前次报告方向 2（wheel/touch 溢出兜底）：chatSearch.js:175-190，`streamMode==='anchor' && !pageInFlight && deltaY<0 && scrollTop≤2` → 直接 `loadAfterPages()`；触摸兜底 dy>12 同条件。与规格 §6.3 补条款（「触顶」触发面 = scroll 监听 + wheel/上拉触摸溢出兜底）**逐字一致**。

| 子项 | 结果 | 证据（探针 `dr-v31-rereview-b13.mjs`，暗色） |
|---|---|---|
| 锚点生于顶 | born: days=[17], scrollTop=0，锚点请求 `before=1786982399999&limit=100`（=8/17 23:59:59.999 ✓） | 探针日志 |
| **路径 a：直达 wheel-up 必发 after 请求** | 单次 wheel-up(0,-300) → **即发** `after=1786939200000&limit=100`（=锚点日最新条目 8/17 12:00，游标正确），**days [17]→[17,18]**，更晚日前置插入 | `b13-path-a-fixed-dark.png`（连续上滑后视口平滑进入 8/18 条目，"5 号滑到 6 号中间不断"R1 达成） |
| 路径 a · 滚动位置保持 | 同行（8/17 12:00 首行）prepend 前后视口偏移 **4px→4px（Δ=0）**，scrollTop 0→3150=60 行×52.5px 精确补偿（timeline 探针：t=51ms 补偿一次到位、2500ms 内无二次跳动；fonts.ready 排除字体交换干扰） | `dr-v31-rereview-scrollkeep2.mjs` / `-timeline.mjs` 日志 |
| **路径 b：先下后上不重复请求** | 路径 a 后游标耗尽（start=0），下滚×6+回顶×12 → **零新请求**、条目 110→110、days 恒 [17,18]——符合 §6.3「游标耗尽即停，不再发请求」 | `b13-path-b-nodup-dark.png` + 探针日志 |
| 副产物 | pageErrors=[] 全程；5 连 wheel-up 场景补偿后后续手势自然滚入新内容（3150→1950=4×300 逐 gesture 递减），连续流手感正确 | timeline 探针 |

触摸兜底路径（touchstart/touchmove dy>12）未在 headless 下实测，经代码审查确认与 wheel 路径同守卫同触发点，由 qa 流式冒烟 5 项新增断言覆盖。

## ② N1 · 短弹窗态弹层裁切 —— PASS（双主题）

修复 = 报告选项 3（`#search-modal` overflow:visible 弹层悬出；clamp 路线实测验伪——弹层 318px 高于短弹窗内剩余空间，clamp 会反裁顶部箭头，与规格 §6.1 补条款「禁止 clamp 进弹窗」一致）。探针 `dr-v31-rereview-n1.mjs`，2 条结果短弹窗态 + 日历弹层开 + 等 400ms 淡入（N2 教训）：

| 断言（双主题结果一致） | 值 | 判 |
|---|---|---|
| modal overflow computed | `visible` | ✓ |
| 弹层悬出 | popBottom 795 > modalBottom 619（弹层 318px 完整悬出底缘） | ✓ |
| 弹层底缘完整渲染 | 底缘上 2px 命中测试命中弹层自身（bottomEdgeHit=true） | ✓ |
| 确定按钮可点 | confirm 完整位于弹窗下方且命中可点（confirmHit=true） | ✓ |
| 弹层不出视口 | top 477 / bottom 795 ∈ [0,900] | ✓ |
| 玻璃质感 | popover backdrop `blur(24px) saturate(1.15)` 双主题 | ✓ |

截图：`n1-shortmodal-light.png` / `n1-shortmodal-dark.png`——弹层底部圆角/边框/取消+确定完整，悬出段玻璃压底可读，无裁切。

## ③ 回归抽查（前次 PASS 项抽 3）—— 全 PASS

| 抽查项 | 结果 | 证据 |
|---|---|---|
| 玻璃弹层 | PASS | popover backdrop-filter `blur(24px) saturate(1.15)` 双主题 computed（见②）；n1 双主题截图玻璃质感与 v3.1 前次评审一致 |
| 暗色主题 | PASS | b13 两路径 + n1 暗色截图全部可读，无透字/对比度异常；pageErrors 零 |
| 375px 窄视口 | PASS | 弹层 left=55.5 ≥0、right=319.5 ≤375 在视口内（`regress-375-open-dark.png`）；overflow:visible **未引入新横滚**——modal-only 与 popover-open 两态 documentElement.scrollWidth 恒 650，越界源全部为背景外壳（body.canvas-open 左移 191px 的 activity-bar 等，body overflow-x:hidden 无用户可见横滚），与弹窗子树无关（探针 `dr-v31-rereview-375hunt.mjs`） |

## 规格-实现一致性（§6.1/§6.3 补条款核对）

- §6.1 短弹窗态补条款（overflow:visible 悬出、禁 clamp、底缘完整+确定可点）↔ modal.css:630-634 + 实测：✓ 一致
- §6.3 触发面补条款（scroll + wheel-up/上拉触摸溢出兜底、scrollTop≤2 视作触顶、游标耗尽即停）↔ chatSearch.js:166-190 + 实测：✓ 一致
- 规格工作副本已含两条款（`assets/20260817_msg-search-v31-fix/message-search-spec-v3.with-n1-f1-clauses.md` L189/L218），可随合并关迁移为 `docs/Nebflow/message-search-spec.md`

## 证据清单（全部持久化，零 /tmp 依赖）

`~/.nebflow/docs/Nebflow/assets/20260818_msg-search-v31-re-review/`：
- 截图 ×4：`b13-path-a-fixed-dark.png` · `b13-path-b-nodup-dark.png` · `n1-shortmodal-light.png` · `n1-shortmodal-dark.png` · `regress-375-open-dark.png`
- 探针 ×6（可复跑）：`dr-v31-boot.mjs` + `dr-v31-rereview-{b13,n1,scrollkeep2,timeline,375hunt}.mjs`

## 结论路由

**PASS** → 交 Manager 合并关卡：本评审 PASS + qa 全套重跑 PASS 即合入；规格书按既定收尾迁移至 `docs/Nebflow/message-search-spec.md`（工作副本两补条款随迁）。qa 侧建议将固定种子版路径 a 子断言（锚点后**不先下滚**直接 wheel-up → 必发 after 且 D+1 入 DOM、滚动位置 Δ≤4px）固化进 stream 冒烟防回归；时间相对种子一律改正午基准绝对日期。
