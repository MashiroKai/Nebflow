# askuser-dragdrop 实施批（#551 批）恢复收尾 · 终版读数 R2

时间：2026-10-19 10:40 · 会话：delegate-nebflow-017653fb

## 一、验证读数（全绿面）

| 验证 | 读数 |
|---|---|
| sbt compile | ✅ success（增量 1s；test 编译在 spec 首轮完成） |
| AskUserAttachmentRoutesSpec（新，上传域判据面） | ✅ **12/12 passed**（纯闸 3 / 清洗与命名 3 / 台账 1 / recJson 1 / 真盘清扫 2 / 上限尺同源 2） |
| AskUserAnswerPersistSpec + AskUserHistoryAttachmentsSpec（相邻层回归） | ✅ **15/15 passed** |
| tests/askuser-attach.spec.mjs（新 E2E，隔离实例 :8094） | ✅ **16/16 passed**（31s）：端点三腿（403 未鉴权 / 400 无 requestId / 410 未知卡）+ 入口三态 6 腿（开放题挂面+multiple、全选项卡静默、混合卡挂面、secret 静默、dirPicker 静默、历史腿无活入口）+ 多文件/发送闸 4 腿（双件上传→帧带 attachments 清单、拖放双件、失败→重试、选项卡附件不替代选项）+ 已答徽标腿 + 桌面/375 几何腿 |
| askuser 家族回归（8 个 PW spec，--workers=1） | ✅ **41 passed**；2 腿（askuser.spec:138/240 严格 toEqual 捕获帧）见下「预存抖动」判定 |
| 前端 JS 语法 | ✅ 6/6（chat/askAttachments/main/persistence/locales×2） |
| `node scripts/build-web.mjs` | ✅ 164 sources 全入图（orphans: 0），css 25 文件拼接 |
| 截图证据 | ✅ 桌面 1280×800 + 375×812 双图（目检：2/9 计数、双 chip Attached、缩略图、× 钮、Confirm 开闸；375 chip 竖排换行零横溢） |

## 二、两腿非绿判定（非本批回归，证据在案）

1. **askuser.spec.mjs:138/240（多选两腿）**：捕获帧数组混入 `{type:"listDir", path:"", rootPath:null}`（explorer.js:752 loadDir 根列举）——ws 桩先于 explorer 异步刷新就位所致的**测试基建时序竞态**。判据：竞态涉及文件（explorer.js / ws.js / state.js / chatView.js / canvasDrop.js / askuser.spec.mjs / helpers/session-rail.mjs）`git diff d8a3d2aa6..HEAD` **逐字节为空** ⇒ 与本批零交集；且本批卡片改动不进全选项卡任何代码路径。属预存 strict-equal 脆弱面。
2. **askuser-lockline-history.spec.mjs（自跑型）**：D0 等真问卡 120s 超时——该 spec 驱动**真实 AskUser agent 回合**，需 provider 已配实例；隔离 smoke home `providers:{}` 无法满足 = 环境约束（非回归）。

## 三、E2E 基建注意（后续批次复用）

- **console-ticket 铸票预算 = 10 票/60s 窗**（ConsoleTicketStore.MintBudgetWindowMs/PerWindow）：多腿 spec 需「单铸 + 会话 cookie 复用」（本批 establishShared 先例：首腿全量捕 cookie 罐，后续腿 addCookies+goto）+ `--workers=1`。
- 背靠背多 spec 跑会互踩窗口：批间 sleep ≥70s 或容忍 429 重试。
- 上传桩 mime 按扩展名回（真端点以 part Content-Type 为准）；并发双件 seq 竞争 ⇒ 断言按 name 匹配。
- 隔离实例 locale 随系统（zh-CN）：文案断言面统一 `addInitScript` 预置 en。

## 四、交付清单（本会话 commit 链）

- 60cfcd27c docs(evidence): WIP 盘点读数 R1
- 6383cb460 test(gateway)+test(web): 配对 spec（Scala 纯闸/真盘清扫 + Playwright 三态/多文件/375px/截图）
- 5dea71db6 test(web): E2E 稳定化（cookie 复用/en locale/桩 mime/disabled 断言/落定等待）
- 7c3e49c1b docs(evidence): 桌面/375px 截图
- 427e0ed1b feat(web): 卡片作用域答复附件面（三态 + 多文件拖入即传）
- 4b1a3933e feat(gateway): 上传域（闸链 + sha 去重 + 惰性清扫 + /uploads 扩段）
- db1c17e96 feat(engine): 附件作答引擎链（AttachGateOf + 终验 + 会话注入 + .answered）
- f66407ef1 test(gateway): spec 断言按实现语义收敛

工作树干净（git status 零残留）；前轮 WIP 15 项全部落位上列 commit。
