# askuser-dragdrop 实施批（#551 批）恢复收尾 · 盘点读数 R1

时间：2026-10-19 10:00 · 会话：delegate-nebflow-017653fb（续聊，前轮压缩崩断后恢复）

## 一、WIP 盘点（以 diff 为准，非记忆）

工作树 15 项改动（13 M + 2 新文件），563 行新增。逐项对照验收清单：

| 验收项 | 状态 | 锚点 |
|---|---|---|
| 前端三态（活卡挂面/静默隐藏/已答徽标） | ✅ 已实现 | chat.js:2580（活卡 mount，判据 `answerAttachmentsAllowed`）；askAttachments.js:62（单一判据：secret/dirPicker/全选项卡 false）；chat.js:3203 + askAttachments.js:486（`mountAnsweredBadge` 历史徽标） |
| 端点（POST /api/askuser/attachments） | ✅ 已实现 | AskUserAttachmentRoutes.scala（新文件 349 行：闸①auth→②hub AttachGateOf→③计数/并发→④尺寸流式；sha 去重；惰性孤儿清扫 24h；`.answered` 标记）；RestApiRoutes.scala:82 挂载；StaticRoutes.scala:229（GET /uploads 扩 4 段 `<sid>/ask-<rid>/<file>`） |
| 引擎注入（作答附件→会话） | ✅ 已实现 | protocol.scala:267/402（User.answerAttachments 出向键 + AskUser 入向 attachments）；askUser.scala:225（manifestSlot）；WsSessionChatHandlers.scala:91-191（答案侧终验：secret 卡拒 / 路径越界 / 超限 / sha 比对）+ :225（markAnswered）；InteractionHubCommand.scala:132 + InteractionHub.scala:116（AttachGateOf 闸查询） |
| 修正①附件入口只给开放题 | ✅ | askAttachments.js:67（至少一道无 options 题）；chat.js checkAllAnswered 三段闸（附件不替代选项） |
| 修正②不支持场景静默隐藏 | ✅ | 前端零入口零提示；服务端 fail-closed 纵深（403 secret 卡 / 410 非活卡） |
| 修正③多文件真支持 | ✅ 卡面 | askAttachments.js:157（`multiple = true`）；逐件入队 ≤9/100MB；拖/粘/点三入口均多件 |
| 修正③聊天输入框同症核查 | ✅ 无缺陷 | input.js:1065（attach 按钮 `f.multiple = true`）；input.js:928（paste 逐件 `files.forEach`）；input/attachments.js:316（drop 逐件 `Array.from(files).forEach`）——输入框三入口本就多文件，无需改动，本条为核查结论 |
| 修正④移动端 375px 适配 | ⚠️ 待验证 | 触屏主路径=隐藏多选 input 点选（已实现）；375 无横溢读数未测 → E2E §5 腿 |
| E2E | ❌ 缺 | tests/askuser-attach.spec.mjs 本轮补 |
| Scala spec | ❌ 缺 | AskUserAttachmentRoutesSpec.scala 本轮补 |
| 桌面/375px 截图 | ❌ 缺 | E2E 截图腿落证据目录 |

i18n：en.js/zh-CN.js 各 +19/18 行（askUser.attach* 键 15 枚 + rejectAttach），两侧数量对齐。

## 二、本轮补齐动作

1. AskUserAttachmentRoutesSpec.scala（纯闸/清洗/台账/真盘清扫，换根先例 = ReloadStaleSessionModelSpec 的 original 存复位式）
2. tests/askuser-attach.spec.mjs（端点三腿 + 入口三态 + 多文件/拖放/失败重试 + 选项卡不替代闸 + 已答徽标 + 桌面/375 截图）
3. 小步提交：证据快照 → spec → 前端面 → 引擎链 → 截图，各自 pathspec 独立成 commit。

## 三、验证读数

（待补：sbt testOnly 读数；E2E 读数；截图路径）
