# 感知批 r3 设计-实现缺口审计与修复（任务令 2026-10-29 11:21）

> 基准：r3 设计报告逐条承诺（`reports/20261025_141024_mail-perception-design-r3/REPORT.md`）
> + r2 五裁决（`...-r2/REPORT.md`）+ r1 全章 + 2026-10-26 作者撤销裁定（红线）。
> 现状含近三日感知 commit：83fe7763e / 0323418f9 / f1a8b85e9 / 6720ed09f。
> 提交：`c30d88ed7`（分支 `delegate/delegate-nebflow-c58a5773`，未 merge 未 push）。

## 一、缺口清单全表

### P0（破坏核心链路 / 违背作者明裁决）——已修

| # | 缺口 | 设计承诺 | 差距定性 | 修复 |
|---|---|---|---|---|
| P0-1 | **L1 LLM 判定腿缺失** | 构想 1 逐字「…然后一次 LLM 调用判断这封邮件是否需要投递」；r2 §5.2 | 原代码 281 行自注「L1 本批为启发式占位」——非白名单/非账单/非 OTP 流量**永不投递**（held-no-pipeline），缺省流量全丢 | 新建 `MailImportanceJudge`（头部+正文 4KB 数据面锁定、30s 有界超时、DELIVER/DROP/Unavailable 三值、降级搁置 spool）；`processMails` 判定序接 r1 §5.1 逐字；kill-switch `nebflow.perception.llm.enabled` |
| P0-2 | **OTP 窗前端渲染时才开** | r1 §6.1 / r2 §6.2：T0 = ask 弹出，引擎同步开窗 | console 关闭/刷新 ⇒ 窗不开 ⇒ 验证码丢失（不可恢复态） | AskUserQuestionTool 发射腿同步 `openOtpWindow(requestId)`（幂等 by requestId）；`otpWindow:true` 显式参数面 + secret 题验证码问句隐式判据；开窗响应带 `hasHit` 命中元数据（无码值），渲染晚于命中的卡面立即补片 |

### P1（走样可修）——已修

| # | 缺口 | 设计承诺 | 修复 |
|---|---|---|---|
| P1-1 | 附件发送腿未开放（doSend 恒拒 + SMTP codec 闲置） | r3 裁决 4 + r2 §7.2/7.3 | doSend 接线；`prepareAttachments` 缺失逐路径前置拒发（`MissingAttachment`，SMTP 零触达） |
| P1-2 | 附件大小/缺失标红/禁发缺失 | r2 §7.3 | 画布 chip 带大小 + `.missing` 红态（`--st-error` 官网 token）+ 停发；移除缺失 chip 后解禁 |
| P1-3 | 快照卡 stale 不停用「照此发送」 | r2 §7（严格式）设计首层 | 双腿停用：载入 stale 即停用 + `draft.updated` 帧拉取停用；409 兜底保留 |
| P1-4 | 失败回执把全部按钮禁死 | r2 §7.5「失败卡带 [重试] 与 [放弃并告知 agent]」 | 失败卡三按钮（重试/画布/放弃）保持可用；仅成功终态禁用 |
| P1-5 | 黑名单 isBlocked 占位恒 false | r1 §5.1 判定 0.1 | `blacklist.json` 用户可编辑文件面；裸域/@domain/完整地址三形态、大小写不敏感、空表零行为变化 |
| P1-6 | UIDVALIDITY 解析入库但零消费 | r1 §4.1 文件夹重建 | 流内比对：变化 ⇒ 重置基线 + 补拉 `takeRight(200)`；台账去重防重投 |
| P1-7 | 退避 2s 起步无抖动 | r1 §4.2「1s→2s→4s…cap 5min ±20%」 | `backoffDelaySec(attempt, rand01)` 纯函数：1s 起步、倍增、cap 300s、±20% 抖动 |
| P1-8 | 信封格式走样（无分类/优先级行、4000 字截断、无注明） | r1 §5.3 逐字 | 信封 = [感知 · 邮件] from/HH:mm + 主题 + 分类行（whitelist→白名单直通 / llm→LLM 判定·投递）+ 优先级行 + 正文 ≤64KB 超出截断注明 |
| P1-9 | backoff notice 带倒计时字样 | r3 裁决 2（去倒计时） | 改「正在自动恢复，新邮件先存本机，恢复后即刻补上」 |
| P1-10 | removeAccount 残留清理缺失 | r1 §11 | state.json 条目 + accountRuntime + secret 三处清理 + audit residual |
| P1-11 | **IDLE 推送缺失**（轮询独扛） | 构想 2「不要用轮询，要像 hook 那样」+ r1 §4 全章 | 加性腿：单 fiber 轮转 + 跨轮存活会话 + EXISTS 踢门即取（Deferred kickSlot）+ capability 词元级探测 + 25min 重续 + 静默掐断 3 次冷却 10min 自停回落轮询（零回归；服务器不支持⇒冷却不刷错） |
| P1-12 | OTP 开窗响应无 hit 元数据（刷新后码片丢失） | r1 §6.1 同步窗语义 | `windowHitMeta`（只读不消费）→ 路由响应 + 前端 hasHit 补渲染 |
| P1-13 | GLOSSARY 无感知条目 | r1 §8 | 「感知 \| perception」条目：**独立于心跳进程**（2026-10-26 作者裁定分离；#daemon-btn 保持心跳） |

### P2 / 开放问题（设计已过时或需新设计决策——不动码，记录在案）

| # | 项 | 状态 |
|---|---|---|
| P2-1 | **进程形态**：r2 裁决 4「独立守护进程 daemon 托管」被作者 2026-10-26 撤销裁定推翻（禁 daemon 路线）；现状引擎内 boot 链 fiber = 折中 | 需作者再裁；**红线禁与心跳合并**（7a91a3253 还原件） |
| P2-2 | 提交/取消关窗 `reason=answered` 缺失（窗开满 TTL，无 UI 面） | 需设计决策 |
| P2-3 | `perception.mail.delivered` WS 事件无消费面（r3 零统计裁定的自然结果） | 记录：非缺陷，事件为预留 |
| P2-4 | 账单日结 09:05 vs 09:00；不可配 | 零 UI 锁定值范畴，差异无害 |
| P2-5 | r1 §8「心跳→感知」更名被撤销裁定推翻 | 现状（#daemon-btn 保持心跳）= 正确 |
| P2-6 | r1 §8 预留条目位被作者 2026-10-26「去掉占位」推翻 | 占位退役批已删净，现状正确 |
| P2-7 | r1 §8 0.5 关键词粗滤 | L1 就位后冗余，不实现 |
| P2-8 | 快照 TTL 过期→agent 超时事件（平台 ask 无 TTL；EmailSend 描述承诺 expired 无实现） | 需设计决策 |
| P2-9 | [添加附件] 画布入口（无路径拾取机制） | 需设计决策 |
| P2-10 | 个性化判定提示词 UI 入口（r3 高级折叠三件不含） | 开放问题 |
| P2-11 | 内置垃圾域表（不自拟——作者纪律） | 不做 |
| P2-12 | 移动端感知面（r3 无手机样张） | 未立项 |
| P2-13 | 多邮箱 UI / 历史重放换肤 / 日切时区可配 | 记录 |

### 符合项抽核（无需动码）

配置两问+17 家域预设+高级折叠+三项零 UI 锁定值；过程态全隐（OTP 等待零 UI/码片掩码/过期零 UI/面板无运行态）；零统计；Canvas 四件套+工具栏+引用块+玻璃胶囊+[放弃][发送]+「新邮件」头；成功回执 时间+收件人数+附件数+Message-ID；官网色单源 stand-tokens.css；面板锚定+幽灵玻璃族+aria-expanded；secrets 0600+connect 失败零写入；audit 掩码纪律；spool 回放；补拉去重；EmailSend 无 agent 自动发送语义；**红线合规**（perceptionBoot 在 GatewayMain 启动链上，无 detached boot，心跳面板独立）。

## 二、验证证据

| 面 | 结果 |
|---|---|
| `PerceptionCoreSpec` | **47/47**（基线 34 + 新增 13：parseVerdict 三值/buildUserContent 4KB/promptFor 身份/judgeViaLlm 三形态/isBlocked 三形态/isTrusted/backoff 界/开窗幂等+windowHitMeta/processMails Drop+台账去重/Unavailable 入 spool/spool 补判 Drop/questionLooksLikeOtp/prepareAttachments+附件拒发改钉） |
| `PerceptionRoutesAuthGateSpec` | 2/2 |
| `tests/mail-perception.spec.mjs`（playwright） | **16/16**（基线 12 钉 + 新增 O3 hasHit 补片 / S3 失败回执按钮可用 / S4 stale 双腿停用 / E2 附件 chip 大小+缺失红+停发解禁） |
| `checkJs` | 256 < baseline 260，零新增 |
| `build-web.mjs` | 绿（193 sources, orphans 0） |
| `sbt assembly` | 绿，jar hash `a8d19432e6b143fd4cba751c37b4c9e1e07f2673` |
| **LIVE 冒烟** | 隔离端口 18093 + 独立 --home 直启：health **200**（~4s）；`GET /api/perception/entries` 无凭据 = **403**（感知路由挂载+会话门在）；`/` = 200（面板 boot 不崩）；`logs/stand.log` 实证 **「perception daemon booted (poll/sweep/digest/spool/idle loops live)」**；零 crash 标记；杀前验身后已杀，现场已清 |

## 三、在产零触碰标注

- 本批全部改动落在 `delegate/delegate-nebflow-c58a5773` 分支（commit `c30d88ed7`），**未 merge、未 push**，在产实例零触碰。
- 其中 **AskUserQuestionTool 的 OTP 引擎开窗腿、PerceptionService 五循环（含 IDLE 腿）、PerceptionRoutes 响应面**均为引擎侧改动——**须引擎重启后生效**；前端三件（perception.js/css/locales）随 build-web 产物、同样待发布链生效。
- compile 后主工程与 spec 全绿；生产装配未替换。

## 四、修法要点（后续批可复用的坑）

- CE 3.6.1 **无 SignallingRef**——kickSlot 用 `Ref[IO, Option[Deferred]]`；`timeoutTo/timeout` 是 IO 直发方法（无 `cats.effect.syntax.timeout` 包）。
- Either 组合急切——`imapConnect` seam 的 match 臂必须全在 `IO.blocking` 内返 Either，IO 语义移外层 flatMap。
- 带默认参方法 eta 扩展（`connectImap`）要 lambda 包裹；schema obj 字符串值要 `.asJson`；circe `Json` 无 `++`（用 `JsonObject.fromIterable`）。
- 意图正则 `(?i)`（2FA/OTP 大写形常见，首版大小写敏感被 spec 钉抓出）。
