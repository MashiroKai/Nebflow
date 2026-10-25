# 修复批 · 顶栏胶囊触控板手感（作者报障 2026-10-25 13:07）

工单逐字：「使用 mac 的触控板进行滑动的时候，不够灵敏和丝滑。」
（微信 + 截图：顶栏胶囊堆叠 hover 态 —— 文档图谱/项目/插件 三态切换；组件 =
canvas 顶栏胶囊面板切换器，#840 刚修过 hover 连续性，同组件）

交付：全前端单点修复（canvas.js 滚轮语义重做 + split.css 步进过渡档）＋
验收 spec 一件 16/16 绿（工单五条逐条钉）＋ 回归四件套绿（既有失败全部实证
与本批无关）＋ checkJs 零新增错误。

---

## 一、手感病拆解（修前行为学）

触控板滑动产生**高频小增量** wheel 事件（macOS 触控板指纹 1~20px/事件）+
惯性末段连发；鼠标滚轮产生**离散大步进**（单事件 |dy|≈±100）。修前实现
（canvas.js `onDocWheel`）：**单事件过恒定阈值即切**（纵 100px / 横 56px，
`while` 循环跨阈连切）——两类输入下参数两难：

- 阈值高（100px）：触控板轻扫总增量常常不过阈 ⇒ 「不灵敏」；用力甩才切。
- 阈值低：轻扫过了，但惯性末段一冲 `while` 连切三格 ⇒ 「不丝滑」（超调）。
- 且步进动画用环收放的 0.36s 温和 morph：连拨时每步重定相位、永远拖在手指
  后面 —— 「丝滑」的第二缺失。

## 二、修法（canvas.js `initCapsuleRing` + split.css §ring）

### ① 设备分档累积器（工单方向 ①+②）
- **分档判据**（deck `DECK_NOTCH_PX=40` 同口径，canvas.js:1354 滚轮重做同族）：
  单事件 |d|（deltaMode 归一 px，1=线×40 / 2=页×100）≥ `RING_MOUSE_DELTA(40)`
  = 鼠标离散格（一格意图明确）→ 低阈 `RING_WHEEL_STEP_MOUSE(32)` 即切；
  否则触控板高频小增量 → 按方向累积过 `RING_WHEEL_STEP_TRACKPAD(48)` 才切
  （阈下微漂不移游标）。X/Y 同档同规（横滑副手势一并受益）。
- **单次过阈恰切一格、余量清零**（事件不跨账）：单事件无论多大至多换一格 ——
  多格合并的大事件/惯性都不再超调（旧 `while` 连切腿退役）。
- **反向归零**：累积中途反向 = 清账重积（反转跟手，不吃旧账死区）。

### ② 切换冷却（工单方向 ①后半）
- 切换后 `RING_WHEEL_COOLDOWN_MS(200)` 内事件**只消费不累积** —— 轻扫一挥
  恰切一格、惯性末段不连跳；冷却后重新累积。冷却在开环/收环/换签（明确意图）
  三处清零，不留旧环余威。

### ③ 步进动画快跟手档（工单方向 ③，「丝滑」主缺）
- split.css 新增 `#canvas-capsule-ring.open.steady .cr-capsule { transition-
  duration: 0.18s }`：steady 态游标步进 transform/opacity/filter 全 0.18s
  （150-200ms 档）—— 换选一击即至、连拨不断链。
- **收放 morph 档不收窄**：环展开/收起保持 0.36s 温和缓出（2026-10-23 作者令
  「动画放慢、自然、流畅」原档）—— 本规则只在 `.steady` 后生效，收放路径照旧。
- `prefers-reduced-motion` 直切由既有 §动效降级 `0.01s !important` 兜底
  （importance 压过特异性），TC9 实证 0.01s + 功能不失效。

### ④ hover 区外零响应（工单方向 ④）
- 旧「面板矩形内一律消费」退役（它会抢面板内容滚动）；消费区收窄为 **hover
  区**（环开点 ∪ 标题头 ∪ 非弧外胶囊，各外扩 14px —— 与收环判定 `inHoverZone`
  同一几何）。区外滚轮不 preventDefault 不换选，页面滚动照旧。
- 上缘位越出面板顶缘的探出段天然在区内（区按胶囊 hit 盒算，不受面板裁切盒影
  响 —— 真越出批几何的正确延续）。

## 三、验收断言（工单五条 ↔ spec 断言逐条对应）

`tests/capsule-trackpad-feel.spec.mjs`（16/16 绿；触控板时序无法用真触控板自
动化 —— 按工单用合成 wheel 事件序列断言行为学，页内真 setTimeout 时序）：

| 工单验收 | spec 断言 | 实证 |
|---|---|---|
| 轻扫序列（多个小 delta）恰切 1 次 | TC1 单个 12px 不切（阈下）＋ TC2 8×12px@14ms 恰 1 次 | delta→alpha，惯性尾被冷却吞掉 |
| 惯性序列（30 个小 delta 连发）≤2 次不超调 | TC4 30×6px@10ms（180px）| 实证 1 次（首阈一切、末段全入冷却）；TC4b 末态恰好一格 |
| 鼠标单格 delta 切 1 次 | TC5 120px 单事件恰 1 次 | charlie→delta |
| 冷却期内事件不叠加切换 | TC3 同一求值内切一格后立即连发 6×60px（360px，无冷却会再切 ≥2 次）| 恰 +1 |
| 过渡动画 CSS 断言 | TC7 steady 态 duration 首值 0.18s + 含 transform/opacity；TC8 步进在途中段≠落定位（真滑非瞬跳）；TC9 reduce 0.01s 直切＋功能不失效 | 全过 |
| （追加）④区外零响应 | TC6 面板内容区滚轮游标不动 + 零 preventDefault；TC6b 区内对照照常消费 | 全过 |

证据：`shots/1-ring-after-swipe-dark.png`（轻扫后环开态：上缘 delta / 中心
alpha / 下缘 bravo，环序几何正确）。

## 四、回归（全部实跑）

| spec | 结果 | 备注 |
|---|---|---|
| tests/capsule-trackpad-feel.spec.mjs（新） | **16/16 绿** | 本批验收 |
| tests/canvas-capsule-ring.spec.mjs | **37/38 绿** | CR3-1/1b/1c/2/3 五断言按新契约重钉后全绿；CR2-5 FAIL 为**既有失败** |
| tests/capsule-hover-chain.spec.mjs（#840） | **9/9 绿** | 滚动链/缘位点击/deck 让位全保持 |
| tests/wiki-graph-ux.spec.mjs | **13/14** | §G 环横滑不劫持图谱平移/捏合（#716）绿；§F FAIL 为**既有失败** |
| tests/canvas-cardhead-capsule.spec.mjs | **25/28** | CC5 分区腿绿；CC6-1/2/4 FAIL 为**既有失败** |
| scripts/check-js-types.mjs | **零新增错误** | total 260 = baseline 257 + nearpath.js 既有 3 |

**既有失败实证**（与本批无关，均用 `git show 8e24d6c48` 版文件换入复跑复现，
与本批改后结果逐字一致）：
- `canvas-capsule-ring` CR2-5 / `wiki-graph-ux` §F：frosted 材质对比（胶囊 vs
  chat header 的 bg/blur/border/shadow 逐项相等断言）—— 材质 token 漂移，
  早于本批存在。
- `canvas-cardhead-capsule` CC6-1/CC6-2/CC6-4：手机腿直滑换卡/长按触感 ——
  mobile 层手势（navController 腿），本批零触碰（桌面环改动全被
  `isMobileLayer()` 让位）。
- `check-js-types` nearpath.js「new file with 3 errors」：近path批基线未登记，
  main 检出同报。

## 五、契约换代说明（需作者知悉的一处语义收紧）

- **单事件至多一格**：旧钉 CR3-1「deltaY -200 单事件 = 退两位」（余量跨事件
  随动）被本批取代 —— 单事件无论多大至多换一格，连拨逐格靠冷却窗后再拨
  （新 CR3-1/1b/1c/2 已重钉，文件头注明换代依据）。这是「惯性不连跳」的
  必然推论：合并派发的大事件与惯性事件在管线里不可区分。
- **滚动＝换选、点击＝切换**的 2026-10-22 令语义**保持不变**（CR3「激活不变
  （滚动≠切换）」断言保持绿）。若作者本意是「滚动直接切换激活页签」，那是
  CR3-1 的再换代，需新令。
- **滚轮消费区从「面板矩形」收窄为「hover 区」**：环开后指针移到面板内容区
  滚动 = 原生页面滚动（不再换选）。指针停在环开点走廊（±56px）/胶囊/缘位
  胶囊上的滚动换选照旧。

## 六、假设与边界

- 阈值档位（触控板 48px 累积 / 鼠标 32px 单格即切 / 冷却 200ms）为工单给的
  150-250ms 冷却档取中 + 轻扫一挥累积量经验档；真机手感若有微调，改
  canvas.js 四常量即可（spec 断言不锁具体数值，锁行为学）。
- 验收 spec 的触控板/鼠标序列为合成 WheelEvent（deltaMode=0 像素档）；deltaMode
  1/2 归一口径与 deck 滚轮重做一致（×40/×100），无自动化覆盖（浏览器合成事件
  构造即可覆盖，已隐含在 TC5 的 120px 像素档行为里）。
- 既有失败四件（§四）未修 —— 均与本批无关且各有归属批次，不越权代修。
