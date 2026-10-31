# 渠道文件出站收口 + SendMessage/Pop 边界批（Pop 文件渠道投递缺口 + 工具说明改写 + 提示词源边界）

- 批次：channel-file-outbound-sendmessage-scope（2026-10-31，作者 22:10 令两件 + 22:30 追加令一件）
- 分支：delegate/delegate-nebflow-e64f1030（零 push，候合并窗）

## 一、实证根因（作者 10-31 21:56–22:00 实况复盘）

作者令原文：「他让我发测试文件，我 Pop 了 /tmp/stand_file_channel_test.txt 与 /tmp/stand_wx_file_test.txt，微信和飞书都没收到——文件卡只落在面板会话，渠道出站腿没捎」。

stand.log 复核（2026-10-31 21:59:25–28，本会话同链实证）：

```
WARN  nebflow.social.outbound.feishu - outbound: product not carriable by this channel — extension is in neither authority table: txt
INFO  weixin-ilink bridge: rich item not carriable (extension is in neither authority table: txt) — the text reply carries the turn
```

**判定：文件/图片出站主线（10-08 weixin-media 批、10-20 长图批、10-21 pop-file-leg 批、10-31 压缩包飞书补齐批）全部在位且已随 17:57 jar 上线——出站腿看到了 Pop 产物并做了分类，缺口在分类层的 v1 边界：`.txt` 既不在图片权威表也不在文档权威表（`NfFileAllowedExt`），也不在 10-21 批的压缩包收编表（`FileLeg.ArchiveExtensions`）里 ⇒ 两腿双双落「not carriable」日志，文件未出站。** 作者点名怀疑的「出站整条缺失」不成立；真缺口 = off-table 扩展名的 v1 日志边界。

## 二、修复面

### 2.1 文件腿直推面 widening（微信 + 飞书）

作者令「Pop 触达的文件/图片随渠道腿出站到微信/飞书」收口为：**off-table 且原件在盘的普通文件 = 一律文件腿直推**（分类层两张权威表零扩表，投递面读取，与 10-21 批同结构决定）。

- `FileLeg.isPushable`：压缩包表闸 ⇒ **任何在盘普通文件**（`Try(os.isFile(p))`）。`ArchiveExtensions` 保留为具名类目权威（W-4b/WO-W7 同面）。
- `WeixinIlinkBridgePlugin.deliverClassifiedItem` Unknown 臂：压缩包闸摘掉，off-table 在盘件 → `deliverFileMedia`（dedup + 25MB 体积闸 + sidecar `file_path`，既有）；文件不在盘 ⇒ 维持 v1 日志边界（无件可发）。
- `RichFeishuAdapter.deliver` Unknown 臂：同 widening，off-table 在盘件 → `deliverDocument`（AttachContract 体积闸 → im/v1/files → msg_type=file）；文件不在盘 ⇒ 维持 [未送达] 降级文本。
- md/markdown/html/htm 长图臂、图片组卡臂零触碰（优先级不变）。

### 2.2 SendMessage 工具说明改写（作者裁定口径）

- `FriendMessageTool.FullDescription` / `SealedDescription`：新增 `## Scope (author ruling 2026-10-31)` 节——SendMessage 只发 NebLink 好友/群/设备；给用户走渠道（微信/飞书等社交腿）的消息与文件一律 Pop 或正常回复，不经 SendMessage。
- `inputSchema.to.description`：同口径首行（参数面 = 模型最常读面）。
- seed 同步（双副本纪律）：`~/.stand/agents/Nebula/system.md` 的 SendMessage 行补同边界句（备份 `system.md.bak-20261031-sendmsgscope`）。注：该磁盘面对四件收敛 agent 已是死信（builtin-def 批，提示词从代码解析），同步仅为盘面一致。

### 2.3 提示词源边界（22:30 追加令，order-375 同款机制）

不能只进画像记忆——存在性保证进编译注入面：

- `Defaults.SendMessageScopeRule`：段体单点常量。
- `PromptSections` order-376 条件段（isRootAgent，逐字引用上常量）——根会话 systemStable 携带；分发器/节点零注入。
- `BuiltinAgents.NebulaPromptTemplate` Tool surface 行：SendMessage 句补边界从句（与 376 段同口径并存）。

## 三、验证

### 3.1 造例实测（微信腿真收文件，作者令点名）

sidecar `/send` 文件腿（= 引擎 `deliverFileMedia` 修后走的同一条腿）实测发 `/tmp/stand_fileleg_live_test.txt`（191 B）到作者微信：

```json
{"ok": true, "delivered": true, "message_id": "7522302865927571976",
 "media": {"kind": "file", "bytes": 191, "name": "stand_fileleg_live_test.txt"}}
```

iLink 文件消息接受 .txt 实测为真（无类型白名单拦截）。引擎分类修随合并+重启生效后，Pop .txt 即自动 riding 同一条腿。

### 3.2 离线钉（sbt，全部通过）

| 套件 | 结果 |
|------|------|
| `RichOutboundSpec`（W-4 改写 + W-4c txt repro 钉 + W-4d 缺件边界钉 + R-2/R-12b 按 widening 新语义改写 + W-4b 原绿） | 44/44 PASS |
| `WeixinOutboundSpec`（WO-W11 txt repro 钉 + WO-W12 缺件边界钉 + 原 48 钉回归） | 50/50 PASS |
| `FriendsSealSpec`（开放/封禁两面 Scope 节钉） | 随下批合计 |
| `PromptSectionsSpec`（376 根注入 + 段序 375<376 + 非根零注入 2 条） | 随下批合计 |
| 定向四套件合计 | **154/154 PASS** |
| 社交包全家 `nebflow.social.*` + 提示词邻域（ColdRoute/NebulaPromptVoiceRouting/SleepPollStartupRule）回归 | **461/461 PASS** |

一次红修正记录：首轮 RichOutboundSpec 2 红 = 旧 v1 边界的预置钉（R-2「Unknown 永不静默丢」原断言降级文本卡、R-12b-C 同面）——按 widening 新语义改写为「off-table 在盘件 → 文件腿直推、零透出、count conservation 不变」，语义更强（文件本身到达，连降级文本都不需要）。

## 四、渠道文件出站能力边界（合并申报，如实）

**微信腿（weixin-ilink sidecar）**
- 图片：`image_path` 腿，连续图并组（≥2 图一条消息，短尾文字作 caption 图文同发）。
- 文件：`file_path` 腿（getuploadurl media_type=3 → file_item）。**引擎侧体积闸 25MB**（`FileLeg.MaxBytes`，与 sidecar sanity 默认对齐；iLink 协议真实上限无公开权威文档）。**无公开类型白名单文档**——本次实测 .txt 通过；渠道拒绝时按既有保底降级（`[文件「x」发送失败 code=…]` 可见卡）。
- md/html：转单张长图 PNG（30s 预算），转不动降文本卡。

**飞书腿（RichFeishuAdapter）**
- 图片：im/v1/images → msg_type=image。
- 文件：im/v1/files，`file_type` 枚举 = opus/mp4/pdf/doc/xls/ppt/**STREAM**；off-table 件（zip/txt/log/…）走 **STREAM = 原字节直发**，无类型白名单可卡。体积闸 = `AttachContract.MaxFileBytes`（1 GiB）；**渠道侧实际上限可能更小**（飞书文档未逐项核），超限/拒绝时上传腿返回 code → 既有保底降级文本（`[未渲染]/[文件过大]` 族）。
- md/html：同微信长图口径。

**共同边界**：Pop payload 只携带 image/file 两类 item（video/audio 不随富腿出站）；分类层两张权威表不因投递能力扩表；文件不在盘 ⇒ 微信 v1 日志边界 / 飞书 [未送达] 文本。

## 五、候重启窗（如实）

- 引擎侧分类 widening（2.1）与提示词源边界（2.3）为编译产物，**候引擎重启加载后生效**；sidecar 文件腿能力（3.1 实测）即时已通。
- 未做：video/audio 随渠道腿出站（两渠道 API 均需转码或类型支持面扩展，超本批口径）；`[暂不支持]`（UnsupportedPrefix）词表保留未删（仍服务缺件/卡片等降级面）。

## 六、改动清单

- `src/main/scala/nebflow/social/outbound/FileLeg.scala`（isPushable widening + 头部口径）
- `src/main/scala/nebflow/social/WeixinIlinkBridgePlugin.scala`（Unknown 臂 widening）
- `src/main/scala/nebflow/social/outbound/RichFeishuAdapter.scala`（Unknown 臂 widening + 日志位调整）
- `src/main/scala/nebflow/core/tools/FriendMessageTool.scala`（Full/Sealed description Scope 节 + schema `to` 行）
- `src/main/scala/nebflow/shared/Defaults.scala`（`SendMessageScopeRule` 单点）
- `src/main/scala/nebflow/agent/PromptSections.scala`（order-376 段）
- `src/main/scala/nebflow/core/entity/BuiltinAgents.scala`（Tool surface SendMessage 句）
- `src/test/scala/nebflow/social/outbound/RichOutboundSpec.scala`（W-4 改写 / W-4c / W-4d）
- `src/test/scala/nebflow/social/WeixinOutboundSpec.scala`（WO-W11 / WO-W12）
- `src/test/scala/nebflow/core/tools/FriendsSealSpec.scala`（两面 Scope 钉）
- `src/test/scala/nebflow/agent/PromptSectionsSpec.scala`（376 注入钉 2 条）
- 数据根：`~/.stand/agents/Nebula/system.md`（seed 同步一行，含 .bak）
