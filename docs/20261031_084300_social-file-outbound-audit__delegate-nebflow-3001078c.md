---
type: audit-report
status: published
date: 2026-10-31
scope: 社交腿（微信 iLink / 飞书）文件出站能力核查 + 压缩包飞书补齐
---

# 社交腿文件出站能力核查批（作者 2026-10-31 飞书令：「你去查一下 微信和飞书应该是支持发文件的」）

> 结论一句话：**两个渠道都支持发文件，且主线早已打通**——微信走 sidecar `file_path` 腿，飞书走 `im/v1/files` + `msg_type=file`。本次核查发现的唯一渠道不对称 = **压缩包（zip/7z/rar/tar 族）**：微信腿 2026-10-21 已按作者令直推，飞书腿同一天只落了微信半边、漏了飞书半边——本批补齐（唯一代码改动）。

## 能力矩阵（Pop 文件产物 → 各渠道今天实际走的路）

| 产物类 | 扩展名（权威表） | 微信（iLink sidecar） | 飞书（lark SDK） |
|---|---|---|---|
| 文档 | pdf/doc/docx/xls/xlsx/xlsm/ppt/pptx/epub + 字体/代码类（ttf/otf/js/mjs/css/json） | **文件腿直推**（`file_path` → getuploadurl(FILE) → CDN → sendmessage file_item） | **文件腿直推**（`im/v1/files` → file_key → `msg_type=file`） |
| 音视频 | mp4/webm/mov/mp3/wav/… | 同上（Document 分类 → 文件腿） | 同上 |
| 压缩包 | zip/7z/rar/tar/gz/tgz/bz2/xz/zst | **文件腿直推**（pop-file-leg 批 2026-10-21） | **本批前 = `[暂不支持]` 文本卡**；本批后 = 文件腿直推（与微信同作者令补齐） |
| md/markdown/html/htm | 不在两张权威表 | 单张长图 PNG（image_path 腿 + 原件路径伴行注，去重防轰炸） | 同口径（长图腿 2026-10-20 两渠道同批落地） |
| 其余 off-table | 如 xyz | `[暂不支持]` 文本卡（v1 边界，两渠道一致） | 同左 |

## 各半边证据（真实现状，非设计稿）

**微信半边**（引擎 → `tools/weixin-sidecar/send.mjs` → iLink）：
- 引擎面：`WeixinOutboundAdapter` 媒体缝 `WeixinMedia.file(file_path)`（`WeixinOutboundAdapter.scala:127-138`）；sidecar 契约 `file_path` 与 `image_path` 互斥、一消息一媒体（`send.mjs:23-29`）。
- sidecar 面：`ITEM_TYPE_FILE = 4`、`UPLOAD_MEDIA_TYPE_FILE = 3`、`buildFileItem`（`send.mjs:150,155,289`）——文件上传管线真实实现，非桩。
- 投递面：`WeixinIlinkBridgePlugin.deliverFileMedia`（`:1804-1841`）三道闸 = 去重（`FileLongShot.Dedup`，路径+size+mtime 指纹）→ 体积闸 25MB（`FileLeg.MaxBytes`，与 sidecar sanity `WEIXIN_SIDECAR_MEDIA_MAX_BYTES` 默认对齐，`send.mjs:146`）→ `file_path` 发送 + 失败保底文本。
- 历史坑已 closure：2026-10-09/10-20 两次实测「微信文件消息零到达」是 sidecar 文件腿未实现的旧实况；2026-10-21 批当日手动 curl 文件腿实达后按作者令直推为口径。

**飞书半边**（`RichFeishuAdapter`，lark SDK）：
- 文件腿真实实现：`liveUploadFile` = `im/v1/files` CreateFile（`fileTypeOf` 按扩展名映射 SDK 枚举，无名类型落 `STREAM` = 按字节直发面，`RichFeishuAdapter.scala:599-615`）→ `msg_type=file` send（`:253`）。
- 体积闸用 `AttachContract.MaxFileBytes` = 1GiB（作者 2026-09-14 冻结数，控制台附件口径）；渠道真超限由上传拒绝保底降级（可见 `[未渲染] 渠道拒绝(code=…)` 文本，绝不静默）。
- Pop 主链已接线：两桥同形——toolEnd 记 `RichProduct`（`FeishuBridgePlugin.scala:701` / `WeixinIlinkBridgePlugin.scala:1118`），回合末 flush 逐件分类 → 适配器。

## 本批改动（小修，唯一面）

`RichFeishuAdapter.deliver` 的 Unknown 臂新增压缩包分支（`RichFeishuAdapter.scala:106-127`）：`onDisk.filter(FileLeg.isPushable)` → `deliverDocument`（与文档腿完全同路：体积闸 → 上传 → file 消息）。结构决定与微信半边一致：**分类层两张权威表不扩**（`FileLeg` 文件头既有裁定），只在投递面读压缩包表；md/html 优先于本臂；渠道拒绝走既有保底。离线钉：`RichOutboundSpec` 新增 W-4b（压缩包 = 恰好一次 UploadFile + 一次 msg_type=file、零长图缝、零文本卡；摘臂即红）。**41/41 全绿**（RichOutboundSpec 全套回归同跑）。

## 残留点 / 挂账（本批未动，非阻塞）

1. **飞书文件腿无去重**：微信 `deliverFileMedia` 有同字节指纹去重（防重复 Pop 轰炸），飞书 file 腿（richcontent 批 2026-09-30 原状）没有——同一 PDF 重复 Pop，微信抑制、飞书重发。要做成「每渠道各有首推」需渠道域指纹键（现台账跨渠道共享、直接复用会互斥），超出本批「小修」口径，挂账。
2. **飞书无引擎侧渠道体积预闸**：1GiB 闸是控制台口径，飞书 `im/v1/files` 的真实渠道上限低于它时靠上传拒绝保底（用户可见、不静默）。若实况出现大文件 bounce，建议拿渠道权威数后再钉引擎侧预闸（作者数纪律：禁自选）。
3. **ask 附件腿不对称**：`FeishuBridgePlugin.deliverAskMedia` 的 Unknown 臂（`:918`）与微信 ask 面同样只保路径行不发文件——非 Pop 主链，挂账待作者令。
4. **入站方向未核查**：本批只查出站（作者令「发文件」）；用户从微信/飞书发文件进来的入站面（如 `FeishuInboundImageSpec` 覆盖的图片入站）不在范围。

## 验证

- `sbt "testOnly nebflow.social.outbound.RichOutboundSpec"`：41/41 PASS（含新 W-4b，2m50s，2026-10-31 08:45）。
- 受影响邻面人工甄别：`FeishuRichMainLinkSpec` / `RichDegradeProbe` 的 Unknown 夹具均为 `.xyz`（非压缩包），不走新臂；`WeixinOutboundSpec` 微信半边零改动。

相关：[[glossary]]、[[20261031_074500_login-expiry-singleflight__delegate-nebflow-25c36738]]（同日社交腿修复批）、[[20261030_211200_coldstart-batch-8fixes__delegate-nebflow-7ceafc0d]]。
