# Pop 文件的社交腿投递 · 靶向考古+修复报告

- 批次：pop-social-files（2026-10-20）
- 分支：delegate/delegate-nebflow-ef0eb5bf（merge 并 main，零 push）
- 作者令：Pop 的**文件类**产物投递社交腿 = md/html → 单张长图 PNG（微信/飞书可看）；转不动的形态降级为「文件名+本地路径+一句话摘要」文本卡；去重防轰炸（同一文件多次 Pop 不重复推）；飞书腿同口径。

## 一、考古结论（三态判定）

**判定 = 从未实现，USTC 合并未弄丢任何东西。**

| 考古点 | 结论 | 证据 |
|--------|------|------|
| 三快照 S0=a0f2edfc7（USTC 合并前 checkpoint）vs main | `src/main/scala/nebflow/social/` 整包 diff 仅 FeishuScanBind.scala 1 行；投递面 S0==S2 | `git diff --stat a0f2edfc7 main -- src/main/scala/nebflow/social/` |
| md 长图转换管线 | **不存在**（从未实现）：md 不在图片表（`ImageInject.imageExtensions`）也不在文档表（`NfFilePolicy.NfFileAllowedExt`，NfFilePolicy.scala:57-97 无 md/html）→ `RichKind.of` 判 Unknown → 微信腿 log-only（**微信零到达的根因**）、飞书腿 `[暂不支持]` 一行 | RichKind.scala:99-121；WeixinIlinkBridgePlugin.scala Unknown 臂 |
| html 曾有腿 | 有：social-webshot batch（2026-10-09）「文件直发、截图兜底」三腿链，10-09 14:0x 作者裁定落刀。本批按 2026-10-20 新裁定**整体退役**（同字节 html 文件消息微信侧实测零到达=今日实况） | 原 deliverWebPageMedia / RichFeishuAdapter.deliverWebPage |
| 可复用转换能力 | **存在**：`scripts/social-html-shot.mjs`（node playwright fullPage）+ `WebPageShot.NodePlaywright` 进程缝（发现/30s 预算/PNG inspect/kill 全套）。md 侧缺 marked 渲染壳 → 本批新增 `scripts/social-md-shot.mjs`（复用仓内前端同款 vendor `marked.min.js` v12——与聊天渲染同一份库，同一渲染口径） | WebPageShot.scala；scripts/social-html-shot.mjs |
| Pop→社交腿投递链定位 | toolEnd（`___POP_JSON___` 载荷）→ `RichProduct.of` 解析 items[]（image/file 全量、载荷序，pop batch-media 修）→ 桥 richRef 记忆 → done → `deliverRichMedia`（微信）/ `deliverRich`→`RichFeishuAdapter.deliver`（飞书）→ per-item `RichKind.of` 分类 | RichProduct.scala:58-115；WeixinIlinkBridgePlugin.scala:1025+；FeishuBridgePlugin.scala:1103+ |

## 二、修复面（按 10-09 口径补齐 + 10-20 裁定接管）

新增**单张长图腿**（分类层零改动，只在 Unknown 臂读取——沿用 webshot batch 的结构决定）：

1. **转换主腿**：md/markdown → `scripts/social-md-shot.mjs`（marked v12 渲染壳 + playwright fullPage）；html/htm → `scripts/social-html-shot.mjs`。进程缝整体复用 `WebPageShot.NodePlaywright`（新增 `detectScriptNamed` 通用化，零第二份进程管理）。产物过既有 `RichRenderer.inspect`（PNG magic+可解码+非纯色下限）。
2. **投递**：微信 `image_path` / 飞书 `image_key` 发长图 → 文字腿伴行注 `原件（桌面端可打开）：<path>`（`RichDegrade.originalNote`）。
3. **去重防轰炸**：`FileLongShot.Dedup` 进程级 LRU 台账（上限 512），键=绝对路径+size+mtime；**发送成功才落账**（转换失败/渠道拒绝不落账→下次 Pop 照常重试，防轰炸不吞投递）；文件重生成（新字节）⇒ 新键照推；两渠道共用台账但跨渠道各推一次（每渠道各有首推）。去重闸在转换**之前**（重复连渲染都不做）。
4. **降级文本卡**：转不动（渲染件不可用/超时/产物非法）⇒ `[未转图] <文件名> — 聊天里转不出长图…` + `内容摘要：<一句话>`（`FileLongShot.summarize`，md 取首行去记号/html 取 title，48 字符封顶）+ `原件在桌面端：<path>`。机器可匹配前缀 `[未转图]` 入 `RichDegrade` 封闭词表。
5. **退役面**：`deliverWebPageMedia`（微信）/ `deliverWebPage`+`webPageFileLeg`+`deliverWebPageShot`（飞书）及 `webPageNote` 文案整体移除；`webshot` 构造缝两处换名 `longshot`。

## 三、验证（全绿）

| 验证 | 结果 |
|------|------|
| `sbt Test/compile` | success（91s） |
| 聚焦 spec：FileLongShotSpec + RichOutboundSpec + WeixinOutboundSpec + FeishuRichMainLinkSpec + FeishuAskWireSpec + FeishuBridgePluginSpec + WeixinIlinkBridgeSpec + WeixinOutboundLogSpec | **Passed: Total 222, Failed 0** |
| 真管线 E2E（真实 node playwright） | `fixtures/pop-social-test.md` → `pop-social-test-md.png`（900x900，61-90KB，目检：标题/表格/代码块/引用块全对）；`fixtures/pop-social-test.html` → `pop-social-test-html.png`（框图渲染对）——见本目录 |
| RichDegradeProbe（真编译适配器降级链，4 案例） | 全 PASS：C 案例=`[未转图] diagram.html …内容摘要…原件在桌面端：…` 文本卡面；机制词零泄漏。`probe/degrade-reading.json` |

spec 钉（投递管线三要素全钉）：
- 转换：RichOutboundSpec W-1/W-1b（md/html→image 腿+伴行注、file 腿零调用）、W-5（可转换表+发现面）；WeixinOutboundSpec WO-W1/WO-W2
- 降级：RichOutboundSpec W-3（文本卡=文件名+路径+摘要）、W-3b（上传拒→[未渲染]+伴行注）、W-4（非可转换直落 b 层）；WeixinOutboundSpec WO-W4（文本卡+回合正文完好）；R-10b（`unconverted`/`originalNote` 构建器逐字钉）
- 去重：RichOutboundSpec W-2/W-2b（同字节抑制/新字节重推）；WeixinOutboundSpec WO-W3（二次 Pop 零二次投递）/WO-W5（发送失败不落账→重试）；FileLongShotSpec FL-3/4/5（键语义/不可读/有界）

## 四、分栏（候重启窗，如实）

- **已完成可验**：引擎侧管线（转换+降级+去重）离线 spec 全绿 + 真转换管线实跑出图（双格式目检）。两渠道**发送缝**（image_path/image_key 语义）为既有已验通道（图片单发历来可达）。
- **候重启窗**：活体引擎（在跑实例为旧编译产物）需重启加载新引擎后，方可做「Pop 测试 md+html → 作者微信实收/飞书实收」的真机双证（sidecar out.log + 收件截图）。重启窗后用本目录 `fixtures/` 两测试件直验。

## 五、改动清单

新增：`scripts/social-md-shot.mjs`、`src/main/scala/nebflow/social/outbound/FileLongShot.scala`、`src/test/scala/nebflow/social/outbound/FileLongShotSpec.scala`、本 evidence 目录。
修改：`WebPageShot.scala`（detectScriptNamed 通用化）、`RichPlanner.scala`（RichDegrade：+originalNote/+unconverted/−webPageNote）、`RichFeishuAdapter.scala`（webshot→longshot，网页三腿→长图单腿）、`WeixinIlinkBridgePlugin.scala`（同款）、`FeishuBridgePlugin.scala`（空读数日志措辞+注释对齐）、四个 spec/probe 文件（钉面随裁定面更新）。
零触碰：树上其他在飞并行批改动文件（web/mobile 面等）未入本批 pathspec。
