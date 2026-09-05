> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Flow Map 归档面板 v3 — 视觉评审报告（对照原型）

- 日期：2026-09-04 · 评审人：design-engineer（vision 逐张看图 + 独立渲染取证）
- 评审对象：worktree `flowmap-archive-panel` 分支 @ `b4a831a2`（3987dd1e 实施 → 0000de17 修复 → b4a831a2 spec），只读参照，零改动
- 依据：① 规格 frozen `20260903_flowmap-archive-panel-spec.md`（§17 C1–C9 / §18 C10–C13）；② 原型 `assets/20260903_flowmap-archive-panel/prototype.html` + `shots/`（作者 2026-09-03 21:23 拍板为视觉基准）；③ `.glass-control` / `.canvas-ref-select` 玻璃语言；④ 暗亮双主题 + reduced-motion + 375px
- 上游自评：`20260903_archive-panel-impl-report.md`（4 passed / 4 failed + 降级静态断言 21/21）
- 独立取证（本评审自跑，非上游产物）：Playwright 静态拦截 harness 引 worktree 真实 js/css + 夹具，1920 与 375 视口几何探针 + hover 探针 + T8 独立重跑。取证截图：`assets/20260903_flowmap-archive-panel/review/`

---

## 1. 逐项评审表

| # | 评审项 | 判定 | 证据描述 |
|---|---|---|---|
| 1 | 主图过滤链语义视觉 | **PASS** | 上游截图 t2/t4/t5：活动卡正常（运行中徽章）；链未齐终态卡 opacity 降 + 绿勾 + 名 dim 可辨（「诊断–登录超时」「取证–构建产物」）；边正常渲染（in 边代理）。链齐整链淡出为瞬态（350ms），无中帧截图，由上游 T5 动态 13 断言 PASS 覆盖（合理）。 |
| 2 | 悬浮钮（位置/玻璃语言/徽章） | **FAIL**（D3） | 位置与玻璃语言达标（探针：两视口 fabTop=16/fabRight=16 精确；CSS 与 `.canvas-ref-select` 逐值一致；徽章=8=面板条目数 ✓）。**但钮体+徽章与头部摘要文字持续重叠**：探针 w1920 实测徽章 [1881..1897] × 摘要右缘 1891 重叠 10px；上游截图 t1/t4/t5/t7 均可见「链未齐终态保留 3」尾部被徽章/钮体遮盖——被遮的恰是 v3 新增的 retained 计数。原型摘要左对齐无此碰撞。 |
| 3 | 归档面板 mailbox 形态 | **PASS** | 链状态徽章最坏优先（✕红 LogtoRequestError/og-image、−灰 忘记密码误直登、✓绿）；链名三级推导生效（无 task 字段 → ②③级：「LogtoRequestError」「console-404」「品牌官网footer修订」）；N 节点徽标、完成时间倒序（9:41→4:05 PM）、摘要单行省略、独占展开（t2 + 评审 w1920 探针截图成员行）均符合；「即将过期」warning 标签见于 t4/t7 与评审 hover 图。展开指示符为 chevron SVG（›/⌄），与 §5.3 的 ▾/▸ 同语言，可接受。 |
| 4 | 右侧详情面板（位置/并排/层级） | **FAIL**（D2） | top64/right16/宽360/z70 数值达标（探针实测）。**dock-left 条件性失效**：评审探针 w1920（host 宽 790px）下 dock-left 并排零重叠成立（detail [1145..1505] × panel [1513..1893]，间隙 8px，elementFromPoint 命中详情自身——见 review/w1920-dock-left-ok.png，与原型 light-detail-dock 同构）；**但 host≈470px（就地分栏常见布局，上游 T2 环境）下 `right:404px` 把详情推出 host 左缘，被 `overflow:hidden` 裁切 + 相邻面板遮挡**（上游 A9.6 动态失败 + 页面快照取证 elementFromPoint 命中相邻 team-panel）。§5.8b 核心裁定「详情面板必须完整可见」在窄 host 布局不成立。根因：显隐口径按**视口**宽度（@media max-width:799px），而约束实为 **host 宽度**。另注：上游截图 `t2-detail-dock` 实为面板关闭、详情 right:16 的非 dock 态，图名与内容不符（证据纪律备注，不计缺陷）。 |
| 5 | 双主题 / reduced-motion / 375px | **FAIL**（D1） | 双主题 **PASS**：t4 暗色玻璃成立不发闷、对比可读；T4 动态 PASS。reduced-motion **PASS**：t7 + T7 动态 PASS + CSS 0.01s opacity-only。**375px FAIL**：评审独立重跑 T8（当前已提交代码 b4a831a2）A20.1/A20.2 双双 FAIL；自截图 review/w375-detail-clipped.png 实测详情面板左缘被 host `overflow:hidden` 裁掉 **~49px**（「修复-LogtoRequestError」→「复-LogtoRequestError」、「Backend」→「nd」、「9/3/2026」→「/3/2026」）；探针数值：host [73..391] 右缘溢出视口 16px、hostScrollW 333 > clientW 318、徽章右缘 379 超出视口 4px。对照原型 narrow-375-panel-detail.png 完整可见——明确回归。 |
| 6 | hover 条目联动主图高亮 | **PASS** | 上游 8 图无此证据（T3 降级）；评审自跑 hover 探针补足：逐条 hover 8 条目，**「Logto 邮件链」→ 跨批引用节点 x1 获 `.fm-adj` 高亮**，其余 7 条不误亮（含无下游链）——与 §5.6 / §17-C8 语义注记精确吻合。取证 review/w1920-hover-linkage.png（x1 在图可视区外，证据以 DOM 命中为准）。 |
| 7 | 上游自评与实图一致性抽查 | **FAIL** | **发现声称与实况不符**：上游称「A20.1/A20.2 系本批 container-type 实验扰动，实验已回退，首轮 PASS 证据保留」——评审在**实验回退后的当前提交**独立重跑，A20.1/A20.2 仍双双 FAIL，375 破版为现状而非实验残留。其余自评（T1/T4/T5/T7 PASS、静态断言 21/21、双主题/reduced-motion 表现）与实图一致。 |

## 2. 截图核验清单（逐张过目记录）

| 截图 | 已过目 | 要点 |
|---|---|---|
| t1-panel-open | ✓ | 面板头部/8 条目倒序/最坏优先徽章/徽章 8 正确；**徽章遮摘要尾字**（D3 首证） |
| t2-chain-expanded | ✓ | console-404 独占展开成员行（倒序、agent、时间）；终态保留卡 dim + 绿勾；图例含「链未齐·终态保留」 |
| t2-detail-dock | ✓ | 详情内容构型正确（状态徽章/meta/所属链/结果）；**实为面板关闭的非 dock 态，未展示 §5.8b 并排**（图名不符） |
| t4-dark | ✓ | 暗色玻璃成立、条目可读、终态保留卡暗色成立；「即将过期」标签；徽章遮摘要（D3 暗色同现） |
| t5-terminal-retained | ✓ | 链未齐 toast「链未齐（1/2）· 终态卡保留主图」；保留卡 + 边连接 + 等待行 ✓ |
| t5-all-archived | ✓ | 全归档空态文案「全部节点已完成，结果收入右上角归档」+ 悬浮钮可用 ✓；备注：底部两 toast 并存略显拥挤（瞬态，不计缺陷） |
| t7-reduced-motion | ✓ | 面板开合可用、无位移（T7 动态 PASS 佐证） |
| t8-narrow-375 | ✓ | **详情左裁、内容残缺**（D1）；评审复跑证实为现状 |
| 评审自截 w1920-dock-left-ok | ✓ | host 790px 下 dock-left 并排零重叠成立（D2 的正面证据） |
| 评审自截 w375-detail-clipped | ✓ | D1 现状取证（左裁 ~49px） |
| 评审自截 w1920-hover-linkage | ✓ | hover 联动 DOM 命中 x1 |
| 原型 shots（panel-open/detail-dock/narrow-375/dark） | ✓ | 对照基准：摘要左对齐无碰撞、dock 并排、375 完整可见 |

## 3. 与原型差异表

| 项 | 原型（视觉基准） | 产品实现 | 判定 |
|---|---|---|---|
| 悬浮钮 vs 摘要 | 摘要左对齐，钮区无碰撞 | 摘要右对齐延伸至右缘，**与钮/徽章重叠** | 差异=缺陷（D3） |
| dock-left 并排 | 1440 全宽画布并排零重叠 | host≥780px 成立；**host≈470px 就地分栏失效（详情被裁/被盖）** | 条件性缺陷（D2） |
| 375 窄视口 | 详情完整可见、无破版 | **左裁 ~49px、内容残缺** | 缺陷（D1） |
| 面板/条目/徽章/玻璃材质 | mailbox 链条目、玻璃、无遮罩 | 一致 ✓（含暗色、reduced-motion、TTL 标签） | 一致 |
| 计数口径 | 徽章 10（真实快照 + TTL 演示链） | 徽章 8（产品夹具 8 链 21 节点） | 数据差异，非缺陷 |

## 4. 缺陷清单与数值级修正建议

- **D1 · 375px 详情/面板左裁 ~49px**（清单 5）：`.fm-archive-panel` 与 `.fm-detail` 的 `max-width: calc(100vw - 24px)` 以视口为口径，而定位上下文（host）仅 318px 宽且自身右缘溢出视口 16px → 面板右缘贴 host 右缘（=视口右缘）、左缘 24px 落在 host 左缘（73px）之外被裁。
  - 建议：两处 `max-width` 改 `calc(100% - 32px)`（% 相对 host；right:16 不变 → 左缘 ≥ hostLeft+16）。按实测推算改后 375 下详情为 [89..375]，完整落入可视区。
  - 附带（超出本特性面，建议另行排查）：`.flowmap-view-body` 在 375 视口下宽 318px、右缘 391px 溢出视口 16px（分栏 min-width 问题），hostScrollW 333 > clientW 318。
- **D2 · host<780px 时 dock-left 失效**（清单 4）：`@media (max-width: 799px)` 按视口判定，但并排可行性取决于 host 宽（需求 = 380 面板 + 8 间隙 + 360 详情 + 16+16 边距 = **780px**）。
  - 建议：`updateDetailDock` 改为按 `ctx.host.clientWidth >= 780` 切换 `.dock-left`（JS 口径），CSS 媒体查询删除；host<780 → 详情同位 right:16、z-70 浮前（现窄视口行为），满足「完整可见」。避免复用 container-type 方案（上游已证其扰动 flex 收缩语义）。
  - 此条与上游遗留①同源，**需作者拍板**（dock 口径改 host 宽度是规格 §5.8b 的修订）。
- **D3 · 悬浮钮/徽章遮挡头部摘要**（清单 2）：钮区 [hostRight−56 .. hostRight] 与右对齐摘要常驻重叠（实测重叠 10px，遮蔽 retained 计数尾字）。
  - 建议（推荐）：`.flowmap-card-header` 加 `padding-right: 56px`（40 钮 + 16 间距），摘要让出钮区，保住 §4.1 的 16/16 锚定。
  - 备选：fab `top: 16px → 56px`（让出 ~40px 头行），但偏离规格 16/16 口径，不推荐。
- **证据纪律备注**（不计缺陷）：① `t2-detail-dock.png` 图名声称 dock 实为非 dock 态；② 降级断言引用的「首轮 PASS 证据」须可复跑佐证，否则视为未验证（本次 A20.1/A20.2 即因此暴露）。

## 5. 判定与依据

**最终判定：FAIL**

依据：评审清单 7 项中 3 项 FAIL——D1（375px 破版，规格 §8.7/A20 与原型基准双重对照不成立，且上游「实验扰动已回退」的声称经独立复跑证伪）、D2（§5.8b 作者拍板「详情必须完整可见」在 host≈470px 常见就地分栏布局不成立）、D3（悬浮钮遮挡 v3 新增 retained 摘要，全部面板截图可复现）。D1/D3 为数值级可修（建议见 §4），D2 需作者拍板 dock 口径。下游「QA验收-归档面板」按闸门规则不验收，直接转述本判定。

**上游实施要点一句话转述**：纯前端派生的整链归档（批次窗口 120s 聚簇 + 24h TTL + 墓碑防复活）与三层悬浮 UI（悬浮钮/mailbox 面板/右侧详情）已按规格落地，功能语义与材质语言大体忠实，但三处布局几何缺陷（375 裁切 / 窄 host dock-left / 钮撞摘要）需在 QA 验收前修复或拍板。
