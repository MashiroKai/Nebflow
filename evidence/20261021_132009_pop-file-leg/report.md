# Pop 文件自动过社交桥 · 文件腿直推 + 账号同权 + 体积闸 + 去重

- 批次：pop-file-leg（2026-10-21）
- 分支：delegate/delegate-nebflow-d7333b2a（零 push，候合并窗）
- 作者令（原话）：「那应该修复这个问题啊，这样你使用 pop 把这个文件发给我，就自动传过来了呀」——Pop 发文件 = 自动传到微信，无需手动走 sidecar。

## 一、考古结论（桥面现状与真实缺口）

**判定：图片/文档（PDF/办公件）的投递链在 HEAD 已在位（10-08 weixin-media 批 + 10-09 pop multi-group fan-out 修复 + 10-20 长图批），作者当日实况 = 活体引擎为旧编译产物（10-20 批报告已注记候重启窗）+ 三处真实缺口未补齐。**

| # | 缺口 | 证据（HEAD file:line） |
|---|------|----------------------|
| 1 | **压缩包类不过桥**：zip/7z/rar/tar 族不在两张权威表（图片表/文档表），`RichKind.of` 判 Unknown ⇒ 微信腿只落日志一行 | `RichKind.scala:99-119`（表外⇒Unknown）；`NfFilePolicy.scala:59-99`（NfFileAllowedExt 无压缩包）；`WeixinIlinkBridgePlugin.scala` Unknown 臂原样（仅 longshot 可转换分支） |
| 2 | **文件腿无去重**：Document 臂裸发 `file_path`，同文件重复 Pop 会重复推（长图腿 10-20 已有 `FileLongShot.Dedup`，文件腿没接） | `WeixinIlinkBridgePlugin.scala` Document 臂（改前）`deliverMedia(file)` 直发 |
| 3 | **文件腿无体积闸**：超限件照发，等 sidecar 400 回来再兜一圈降级 | `WeixinOutboundAdapter.scala`（无闸）；sidecar `send.mjs:120` `MEDIA_MAX_BYTES=25MB` sanity bound |
| 4 | **账号同腿不同权**：sidecar `/send` 多账号无 hint ⇒ `ambiguous_account` 拒绝；文字腿因引擎每次 send 都冷读 `fields.ilink_bot_id` 带 hint（`WeixinOutboundAdapter.scala:266-282/376`），裸调（手动 curl、脚本）必踩 | `send.mjs` `resolveAccount`（改前 ambiguous 分支）；msgloop 反例：`msgloop.mjs:33`（默认 newest indexed）+ `msgloop.mjs:646-654` `latestValidAccountId` |

本机实况佐证：`~/.openclaw/openclaw-weixin/accounts.json` 挂 3 个账号（58dc…/ac37…/5925…），msgloop 锁在最新 `5925e75c0164-im-bot`；`~/.stand/nebflow.json` 的 weixin-ilink 卡带 `ilink_bot_id: 5925e75c0164@im.bot` ⇒ 引擎侧文字/媒体腿历来带 hint 不踩坑，裸调文件腿必被 `ambiguous_account` 拒——与作者当日实况逐字吻合。

## 二、修复面（四处）

1. **压缩包收编进文件腿**：新增 `FileLeg.ArchiveExtensions`（zip/7z/rar/tar/gz/tgz/bz2/xz/zst）。分类层零改动（两张权威表不因出站能力扩表——longshot 批同一结构决定），只在 Unknown 臂追加一个读取分支：可转换（md/html）→ 长图（口径不变）⇒ **压缩包 → 文件腿直推** ⇒ 其余仍 v1 日志边界。
2. **文件腿统一投递面 `deliverFileMedia`**（Document 臂 + 压缩包分支共用）：⓪ 去重（`FileLongShot.Dedup` 同一台账，键=路径+size+mtime，发送成功才落账，重生成⇒新键照推）；① 体积闸（`FileLeg.MaxBytes=25MB`，超限不发必被 400 拒的发送，降级 `[文件过大]` 文本卡：文件名+上限/实大小+原件路径+**控制台取件指引**；文本卡发成功=交付义务已了⇒落账防重复轰炸）；② `file_path` 腿 + 既有保底（`[文件「x」发送失败 code=…]`），失败不落账（下次照常重试）。
3. **账号同权（sidecar 侧，二选一论证见 §三）**：`resolveAccount` 无 hint + 多账号 ⇒ **自动采用最新可用账号**（`latestValidAccountId`：newest→oldest 走，首个 token 非空者；与 msgloop 自愈同一条规则、同一函数形状的 twin），`ambiguous_account` 手工指定面退役。拒绝只留两态：空索引 `no_host_account`、全不可用 `no_usable_account`。显式 `account` hint 照旧最高优先（钉死不猜）。
4. **降级文案入封闭词表**：`RichDegrade.OversizePrefix="[文件过大]"` + `Reason.FileOversize` + `oversizeFile` 构建器（§7.3 机器可匹配纪律）。

类型策略终表：PDF/办公件/epub/字体 = 文档表 Document 臂 → 文件腿直推（本批补闸+去重）；压缩包 = FileLeg 表 → 文件腿直推；md/markdown/html/htm = 长图（10-20 口径零触碰，断言 WO-W1/WO-W2 在位）；其余 = v1 日志边界。

## 三、体积上限依据（作者令「先查 iLink 文件消息真实上限」）

- **公开权威文档：不存在。** 联网核查（2026-10-21）：社区收集 API 参考（`XTmai/WeChat-iLinkBot`，getuploadurl/file_item 全参数+错误码表 ret 0/-1/-2/-14/-22）**无任何尺寸项**；官方 npm `@tencent-weixin/openclaw-weixin` v2.4.10 的公开文档页无尺寸上限；社区实测帖（juejin 153-bot 实测）原话「微信的限制，没写在文档里，实测出来的」，且只测了图片 CDN 场景。微信客户端侧 100MB→1GB（2022-12）为二手报道，无官方 kf.qq.com 原文佐证。
- **仓内收敛读数**：sidecar 发送腿 sanity `MEDIA_MAX_BYTES` 默认 **25MB**（env `WEIXIN_SIDECAR_MEDIA_MAX_BYTES` 可调，weixin-media 批，注释自认「NOT a protocol limit」）+ 入站媒体腿 100MB（weixin-inbound-media 批）。
- **取值**：引擎闸 = **25MB**，与发送方向实际生效的 sidecar 默认对齐——超限在引擎侧降级（文本说明+控制台指引），不让必被 400 拒的请求出门。真协议上限若有官方数字，改 `FileLeg.MaxBytes` 一处 + sidecar env 即收口（单点闸）。

## 四、账号同权：为何选 sidecar 侧（而非调用方带默认）

1. **引擎侧本来就带 hint**（`accountHint` 冷读每次 send 都带上）——多账号拒绝只咬**裸调方**（手动 curl、脚本工具、未来新调用点）。调用方带默认治不了「新调用方忘了带」，sidecar 侧一次收口所有调用面。
2. **msgloop 已立规则**：入站循环默认「newest indexed」、自愈采用「最新可用」——出站腿对齐同一条规则，in 监听哪个号、out 就走哪个号，语义闭环。在引擎侧再造一个「默认账号」读数是第二份状态（违反单源纪律）。
3. **拒绝面保留最小集**：显式 hint 最高优先；空索引/全不可用仍 fail-closed 拒绝——自动解析不是猜，是既定规则的执行。

## 五、验证（全绿）

| 验证 | 结果 |
|------|------|
| sidecar 单测 `node --test tools/weixin-sidecar/send.test.mjs` | **22/22 PASS**（新增 4 钉：多账号自动采用最新可用+token 断言、最新号失效滑到次新+全不可用 500、`latestValidAccountId` 纯函数、hint 归一/空 hint=自动采用；原 ambiguous 钉按新口径改写。egress 纪律：死环回端口钉死，零外联——一次失误（未钉 base）已当场改钉并复核） |
| `sbt Test/testOnly nebflow.social.WeixinOutboundSpec` | **48/48 PASS**（新增 WO-W6~W10 全绿；一次红修正：体积卡 MB 显示口径 MiB→十进制，与 25_000_000 字节闸严格一致） |
| 社交包全家 `sbt Test/testOnly nebflow.social.*` | **417/417 PASS**（RichDegrade/deliverMedia 签名变更零回归；含长图批 222 钉面、桥 spec 113、日志 spec） |
| 链路断言 | WO-W6（Pop PDF→桥→file_path 直推+落账）、WO-W7（zip 走 FileLeg 分支直推）、WO-W8（同字节二次 Pop 抑制）、WO-W9（超限零发送+`[文件过大]`卡+控制台指引+防重复）、WO-W10（发送失败不落账→重试） |
| md 转图口径 | WO-W1/WO-W2（既有，零改动照旧绿） |
| 去重台账语义 | FileLongShotSpec FL-3/4/5（既有，键=路径+size+mtime/不可读不挡投递/有界） |

## 六、分栏（候重启窗，如实）

- **即时生效（sidecar 侧）**：`tools/weixin-sidecar/send.mjs` 重启 shim（launchd）后生效——账号自动解析消掉裸调的 `ambiguous_account`；此后手动/脚本 `/send` 文件腿不再要求显式 account。
- **候重启窗（引擎侧）**：`WeixinIlinkBridgePlugin`（Document 臂闸+去重、Unknown 压缩包分支）与 `FileLeg`/`RichDegrade` 为编译产物，活体引擎候重启加载后生效；重启窗后真机双证（Pop 一个 zip/PDF → 作者微信实收；Pop 超 25MB 件 → `[文件过大]` 卡实收）用本报告口径直验。
- **未做**：飞书腿的压缩包收编（任务面=sidecar 文件腿；飞书 rich 腿未动）；`FileLeg.MaxBytes` 与 sidecar env 的联动闸（两处常量同值，改一处需同步另一处——已各自注释互指）；图片臂不去重（任务面=文件卡，图片重复 Pop 维持既有行为）。

## 七、改动清单

新增：`src/main/scala/nebflow/social/outbound/FileLeg.scala`、本 evidence 目录。
修改：`WeixinIlinkBridgePlugin.scala`（Document 臂→deliverFileMedia；Unknown 臂+压缩包分支；deliverMedia 返回 reading；deliverRichMedia 文档）、`RichPlanner.scala`（RichDegrade +OversizePrefix/+FileOversize/+oversizeFile）、`tools/weixin-sidecar/send.mjs`（latestValidAccountId twin + resolveAccount 自动采用 + 契约注释）、`tools/weixin-sidecar/send.test.mjs`（账号 4 新钉+ambiguous 钉改写）、`src/test/scala/nebflow/social/WeixinOutboundSpec.scala`（WO-W6~W10）。
零触碰：飞书腿、分类层权威表、PopTool、长图腿管线。
