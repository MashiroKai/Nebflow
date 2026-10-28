# 修复：「连接邮箱」把 IMAP 成功响应当错误显示（报障 2026-10-28 15:1x）

## 根因（两个，均在本批修）

**P1 · 判定锚点错位（MailClient.scala `ImapSession.command`）**

旧判定：`if resp.startsWith(s"$tag OK") then Right(resp) else Left(...)`。
`resp` 是**完整多行响应**，而 RFC 3501 §7 规定 SELECT/SEARCH/FETCH 的响应以
untagged 数据行（`* …`）开头、tagged 完成行（`a2 OK [READ-WRITE] SELECT completed`）
**收尾**（最后一行）。因此：

- `LOGIN` 响应单行（`a1 OK …`）→ 首行即完成行 → 侥幸判对（这就是「认证已通」）；
- `SELECT INBOX` 首行是 `* 4497 EXISTS` → `startsWith("a2 OK")` 恒 false →
  **成功响应整段被塞进 Left**。同一误判波及 `searchUids`（`* SEARCH …`）与
  `fetchMessage`（`* n FETCH (…{n}…`）——收信轮询腿对任何真实服务器同样全挂。

**截断来源（后端，非前端、非读取超时）**：`connectAccount` 失败分支的兜底
`err.take(160)`。用截图文本逐字节复核：untagged 五行（含 `\r\n`）恰 137 字符，
加 `* FLAGS (\Answered \Fla` 23 字符 = **160**，与截图半截文本末端完全吻合。
上游 `command` 错误分支的 `resp.trim.take(300)` 是同一截断类的第二处。

**P2 · 字符流/字节流混流（同文件，spec 首跑实证的第二个真 bug）**

`ImapSession` 用 BufferedReader（字符流）读行、又从底层 `BufferedInputStream`
按精确长度直读 `{n}` 字面量：readLine 会把同一 TCP 分段里 tag 行之后的字节
（字面量本体+后续行）预取进**字符缓冲**，直读时字节已不在流里 ⇒ FETCH 挂死
（spec 实测撞 5s SoTimeout）或内容错位。生产影响：收信腿拉全文在多数真实
载荷形状下同样不可用。修法 = 行读取改**字节级**（读到 `\n`、剥尾 `\r`、
UTF-8 解码），字面量仍按长度直读——单一流所有者，顺序消费零混流。

## 改动面

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/perception/MailClient.scala` | ① `command` 判定改锚 **tagged 完成行**（响应最后一条非空行，`readUntilTag` 恰在完成行停笔）；NO/BAD ⇒ Left 可读短句（完成行本身，≤200 字符），OK ⇒ Right 全响应；发送/读取异常（超时、断连）映射人类可读 Left（不再向上抛原始异常）。② 新增纯函数 `imapVerdict(tag, resp)`（spec 直测）。③ `selectInbox(): Either[String, Int]` → `Either[String, InboxStats]`，新增 `InboxStats(exists, unseen, uidValidity)` 解析 EXISTS/UNSEEN/UIDVALIDITY。④ `ImapSession` 构造形参 `SSLSocket` → `Socket`（生产腿仍隐式 TLS，放宽仅为 spec 本地 mock 直构）。⑤ P2：`readLine` 字节级重写，移除 BufferedReader 混流。 |
| `src/main/scala/nebflow/perception/PerceptionService.scala` | ⑥ `connectAccount` 成功返回 `Right((account, InboxStats))`；失败统一走新增纯函数 `readableConnectError`：AUTHENTICATIONFAILED/LOGIN/AUTH → 授权码指引；timeout → 超时文案；UnknownHost/connect → 无法连接；**兜底不再 `take(160)` 倾倒 raw**，恒输出单行人类可读文案；授权码先擦除后分类（防回显驱动误分类）。**旧 err.take(160) 已删**。 |
| `src/main/scala/nebflow/gateway/PerceptionRoutes.scala` | ⑦ connect 成功响应新增 `"mailbox": {"exists": n, "unseen": m|null}`（unseen 缺席 = null，前端降级）。 |
| `src/main/resources/web/js/perception.js` | ⑧ 连接成功态按统计逐级显示：「已连接：收件箱 N 封、M 封未读」→ 无 UNSEEN →「已连接：收件箱 N 封」→ 旧引擎无 mailbox 字段 →「已连接」。仍走 `textContent`（setResult），零注入面。 |
| `src/main/resources/web/js/locales/zh-CN.js`、`en.js` | ⑨ 新增 `perception.cfgConnectedInbox` / `perception.cfgConnectedStats`（中英）。 |
| `src/test/scala/nebflow/perception/MailClientImapSpec.scala`（新增） | ⑩ 见下「验证」。 |
| `src/test/scala/nebflow/gateway/PerceptionRoutesAuthGateSpec.scala` | ⑪ **欠账补修**（非本 bug 引入）：该 spec 直书 `/api/perception/...` 请求路径，但生产挂载是 `Router("/api" -> restApiRoutes.routes)`（GatewayMain），域内 case 为 `/perception/...` 相对段 ⇒ 直调 routes 恒 fall-through，两条契约钉从未真正生效（authchain 批 REPORT §3.2 注记「sbt 运行延至队列空窗」）。改为 Router 相对路径（`api()` 去前缀助手），契约不变。 |

## 验证（MailClientImapSpec，20 例）

- **证据钉**：`qqSelectOk.take(160)` 末端 = `* FLAGS (\Answered \Fla`（截图半截文本复现，钉截断来源在后端）。
- **成功判定**：截图同形状 SELECT（`* 4497 EXISTS / * 0 RECENT / * OK [UNSEEN 429] / * OK [UIDVALIDITY 1540030357]…/ * FLAGS …` + `a2 OK [READ-WRITE] SELECT completed`）⇒ Right；带前置 untagged CAPABILITY 的 LOGIN ⇒ Right；统计解析 = 4497 封 / 429 未读 / UIDVALIDITY 1540030357。
- **错误判定**：tagged NO / NO [AUTHENTICATIONFAILED] / BAD ⇒ Left 可读、不含 untagged raw；服务器静默（SoTimeout 400ms）⇒「超时」可读错误；中途断连 ⇒「连接中断」。
- **截断防御**：无完成行（流截断）⇒ 防御句不倾倒 raw；病态超长完成行截 200。
- **会话全链路**：本地 scripted mock IMAP（loopback 明文，ImapSession 直构）LOGIN → SELECT → `UID SEARCH`+`UID FETCH`（含 `{n}` 字面量内联，P2 修复的实证面）全 Right。
- **connectAccount 失败契约**：连不上 ⇒「无法连接 …」可读单行 + **零落盘**（无 accounts.json / state.json / secret）。
- `sbt Test/compile` 绿；`MailClientImapSpec + PerceptionCoreSpec + PerceptionRoutesAuthGateSpec + DaemonPanelRoutesSpec` 全绿 77/77（AuthGateSpec 两条此前从未跑通过的契约钉现真实生效）。
- 前端 Playwright（`tests/mail-perception.spec.mjs` A1/A2、`perception-btn-ghost`）本检出未装 node_modules/browsers 不可跑；静态论证：本批只动 connect 成功臂，A1/A2 钉的 403/400 分支零改动；`mailbox` 字段缺席时成功臂回退「已连接」与旧行为逐字节一致；JS 语法与 locale 键经 node 实测加载。

## 任务 4 核实：脏状态

- 本次故障路径（login OK → selectInbox 误判 Left）发生在 `saveSecret`/`accounts.update`/`saveAccounts` **之前** ⇒ 该次失败**零持久化**，无半保存账户，重试幂等（同地址同 id 覆盖 + secret overwrite）。
- 若历史上有账户被存过（如旧版/手插），修复前每轮 poll 都会在 selectInbox 处失败 → `recordFailure` 把 `lastError`（raw，take(200)）写进 state.json 并显示「正在自动恢复」。**修复后自愈**：下一次成功 poll 走 `recordOk` 清空 lastError 并回到 Idle，无需手工清理。

## 纪律与边界

- 未触碰在产引擎（pid 70481/6138）；改动随下一波包生效。
- 测试夹具全部 mock 值/loopback，**零真实账号与授权码**；新增测试不写全局 dataRoot（saveSecret 成功腿未在 spec 跑——换根会污染并行套件，成功形态由 session 全链路 + 纯函数 + routes JSON 编译面覆盖，属记录在案的范围裁剪）。
- 已知残留（不在本批）：QQ 部分账号要求 LOGIN 前发 `ID` 命令（未实证本例触发，截图证明 LOGIN 已过）；UIDVALIDITY 变更时 `lastSeenUid` 未重置（本批已解析留存，待同步正确性批使用）。
- 范围外但顺手补账：PerceptionRoutesAuthGateSpec 路径形态欠账（见改动面⑪）。

