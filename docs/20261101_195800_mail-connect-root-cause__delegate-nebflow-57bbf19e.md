---
type: audit-note
status: published
date: 2026-11-01
scope: 邮箱连接「Host did not answer in time」根治批（作者 10-26 起反复报障，10-31 21:42 与 11-01 19:07 两次复发）——错误出处钉死在隧道中转层 / 引擎侧三缺陷（响应内串行同步、无上限回填、裸英文透传）根治 / 分阶段预算+分类文案+幂等在飞闸 / 真实账号端到端验证
---

# 邮箱连接「Host did not answer in time」根治记（2026-11-01）

关联：[[index]]、[[20261031_215855_smtp-send-timeout-hardening__delegate-nebflow-32dfdadb]]（10-31 前批——治了 SMTP 发送侧症状，未触及本批钉死的真正出处）。

## 一、根因链（三层，逐层实证）

「Host did not answer in time」**不是本仓文案，也不是邮件服务器回执**——它是 **neblink 隧道中转层（link-server，Rust 跨仓）的 portal ingress 错误**：

```
浏览器（面板/经云端中转） → link-server portal_ingress.rs → 隧道 WS → 本机引擎 → 感知 daemon → imap.qq.com:993
                                    ↑
   portal_ingress.rs:53  DEFAULT_RESP_HEADER_TIMEOUT = 15s
   portal_ingress.rs:4016 ErrorResponse::new("Host did not answer in time").with_reason("portal_cycle_timeout")
   ⇒ host 的 http_response 15s 未到 ⇒ relay 抢答 504（英文）给浏览器
```

引擎侧旧路径（`connectAccount`）在同一个 HTTP 周期里串行做三件事，总时长轻松突破 15s：

1. **拨号/读各 15s**（`connectImap` dial 与 SoTimeout 同值 15s，坏路径最坏 30s+ 才回报）；
2. **LOGIN + SELECT 之后**，还有「首轮同步」**串行在 HTTP 响应里**（`_ <- pollAccount(account)`，响应等它跑完）；
3. 轮询同步在状态角落（uidvalidity≠0 ∧ lastSeenUid=0，作者实例 state.json 11-01 实录）走 **无上限回填**：`UID SEARCH UID 1:*` 逐封 FETCH，数千封 = 分钟级。

⇒ 中转 15s 预算必先爆：**面板永远只见 relay 的英文 504，引擎侧任何分类文案到不了屏**；而 daemon 侧连接实际成功（audit.jsonl 11-01 19:03:38 `state connected`，live daemon entries `Idle · 已连接`）——「服务器说连上了、浏览器说超时」的劈叉就是它。

**为什么前两批没修好**：10-26 修的是面板认证链（Missing token 403，另一层）；10-31 批调查结论「报错文案全仓+全历史零命中——非本仓文案」就停了手，转向 SMTP 发送侧超时硬化——方向没错但没找到这句英文的**产生点**（跨仓 relay），作者侧报错面（连接）一行未动，故复发。

**本机硬证据对账**（作者令引述的四端口直连全通）：与根因链不矛盾——直连快（0.04-0.19s）恰证明拨号本身没问题，慢在**响应路径里的串行同步**与坏路径下的长预算；另注：本机 Clash TUN 环境下 TCP connect 对任意地址秒连（203.0.113.1/10.255.255.1 实测均「连上后静默」），真停顿一律表现成 greeting/读停顿而非拨号停顿。

## 二、修复（四层）

### ① 引擎：连接测试腿分阶段紧预算（`MailClient.connectImap` + `PerceptionService`）

- `connectImap(host, port, timeoutMs, soTimeoutMs)`：拨号/读预算分离。
- 连接测试腿：dial 4s + 命令读 3s ⇒ 最坏 ~13s < 15s 中转预算，**引擎侧分类文案抢先于 relay 504 上屏**。
- 后台腿（轮询/IDLE）：读上限放宽 30s（`PollSoTimeoutMs`），压 QQ 服务器偶发 >15s 停顿的「IMAP 响应超时」误报（state.json 11-01 19:06 实录那类）。
- SMTP `send()`：拨号 20s→5s、握手读 20s→12s（DATA 阶段分级超时语义不变，见前批）。

### ② 引擎：首轮同步踢出 HTTP 周期

`connectAccount` 成功腿 `pollAccount(account).attempt.void` → `.attempt.start.void`（fiber 承载）：HTTP 响应只等 LOGIN+SELECT 统计，全量回填不再押着浏览器；同步失败照旧走 recordFailure → 面板 entries 实时反映。

### ③ 引擎：回填上限（`backfillSelection` 纯函数）

基线丢失态（uidvalidity 在而 lastSeenUid=0）与 UIDVALIDITY 重建同受 `RebuildFetchCap`=200 上限、取最新端——旧行为对该角落无上限逐封回填（根因链第 3 环）。

### ④ 引擎+前端：错误分级（中文为主+英文对照）与幂等

- `readableConnectError` v2：按腿注入阶段标（connect/greeting/login/select），输出「拨到哪、卡在哪 + host:port + 英文阶段对照」——DNS 解析失败 / 服务器不应答（dial timeout / greeting timeout） / TLS 握手失败 / 认证被拒 / SELECT 超时各归各文案，raw 永不上屏、授权码强制擦除（既有纪律保持）。
- 幂等在飞闸：`connecting: Ref[IO, Set[String]]` 按地址单飞（modify 原子占位 + guarantee 释放），连接中重复点击得「正在连接该邮箱 · 请勿重复点击」，不叠连接不叠拨号。
- 前端 `authErrMsg` 分类：relay 504 / `portal_cycle_timeout` / 「did not answer」⇒ 中文提示（云端通道未在时限内收到本机应答…连接可能已在后台完成，引导看面板状态）；fetch 级网络失败（status 0）⇒ 网络请求失败文案。
- 自动配置显形：`GET /perception/mail/preset` 携带解析出的 host:port/TLS 面，两问卡输入地址 300ms 防抖后回显「已识别 …：将连接 imap.qq.com:993 · smtp.qq.com:465（SSL）」并预填高级设置占位符；不可识别域明说「请在高级设置手填」。

## 三、验证

- **spec**：感知域三套件 87/87（MailClientImapSpec 28 含新 6 例、PerceptionCoreSpec、MailClientSendSpec 13）。新钉面：阶段注入四分支+英文对照、TLS 单列、拨号/读预算分离（静默服务器 700ms 读回报）、greeting 停顿 3.0s 内 Left（预算内）、幂等在飞闸（二击得提示/闸释放）、backfillSelection 上限。全量 `sbt test` 回归见批次末报告附录。
- **真实账号端到端**（隔离 daemon，worktree 构建，`--home /tmp/perception-e2e-home --port 18471`；授权码经子 shell 注入，零打印零落盘零进 commit；**只 LOGIN+SELECT INBOX，未发信**）：
  - 真连 kaimashiro@qq.com（imap.qq.com:993）：**200 ok 3.63s**，mailbox `{exists:4539, unseen:468}`——3.6s ≪ 15s 中转预算；
  - 错授权码：0.27s ⇒「授权码无效…(authentication failed)」（真实服务器 NO 分级）；
  - 坏 host（TUN 秒连后静默）：3.0s ⇒「已连上 …：993，但服务器迟迟不应答（阶段：等待问候 · greeting timeout）」；
  - 本地静默监听（TLS 黑洞）：3.0s ⇒ 同上分级文案；
  - 凭据卫生：daemon 日志/响应体/audit/accounts/state 八处 grep 授权码 **0 命中**；spool 0 件（零注入）。
- **UI 三态实拍**（双端=电脑 1280×860 / 手机 390×844，真 perception.js+i18n+css，API mock）：`.nebflow/evidence/mail-connect-states/` 8 张 + live daemon entries JSON（`Idle · 已连接`）。504 态上屏为中文分类文案+英文对照；成功态「已连接：收件箱 4497 封、429 封未读」。

## 四、诚实边界

1. **中转 15s 预算本体在跨仓 link-server**（Rust，本机 `~/local/link-server-wt-338d3814`）：本批在引擎侧把周期压进预算并分类了 relay 504 文案，但 relay 的 `PORTAL_RESP_HEADER_TIMEOUT` 本值、`Host did not answer the open in time`（WS open 变体）与隧道断链场景的 relay 侧行为属 link-server 仓——如需「中转超时值自适应/中文透传」须另开 link-server 批。
2. **发送腿经中转的长 DATA 周期**：DATA 分级 60-300s > 15s 预算，慢大件发送经中转面板仍会先见 relay 504（本地面板不受影响；发送结果以 Ambiguous 语义与收件箱为准）——根修需异步发送+回执推送设计，另批。
3. **拨号超时分支**在本机 Clash TUN 环境实测不可达（TCP 全秒连），dial-timeout 文案由纯函数 spec 钉死、未做真网验证；greeting/认证/成功三路已真网实测。
4. **域覆盖**：预设表 QQ/foxmail/vip.qq/163/126/vip.163/yeah.net/Gmail/Outlook/Hotmail/Live/iCloud/me/新浪/搜狐/阿里云 17 域；Gmail 等境外域在无代理出口的本机 daemon 侧本来就拨不通（代理出场属环境议题）；表外域 = 明示「未能自动识别，请高级手填」（本批新文案）。
5. live 控制台面板实拍未做（gateway 会话令牌非可重放 bearer，属另一层认证面）——已连接态证据 = live daemon entries JSON + 同形状面板渲染实拍。

## 五、改动面

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/perception/MailClient.scala` | connectImap 拨/读预算分离；send() 拨号 5s/握手读 12s |
| `src/main/scala/nebflow/perception/PerceptionService.scala` | 常量四枚（Connect/Poll 预算、InFlightMsg）；readableConnectError v2（阶段+双语）；connectAccount 幂等在飞闸+分阶段+同步后台化；backfillSelection 上限；imapConnect seam 四参 |
| `src/main/scala/nebflow/gateway/PerceptionRoutes.scala` | preset 端点携带 host:port/TLS 面 |
| `src/main/resources/web/js/perception.js` | authErrMsg 分类（504/portal_cycle_timeout/status 0）；自动配置显形+占位符预填 |
| `src/main/resources/web/js/locales/{zh-CN,en}.js` | 新键四枚×2 |
| `src/test/scala/nebflow/perception/MailClientImapSpec.scala` | 新 6 例（阶段/预算/在飞闸/回填上限） |
| `tests/mail-connect-states-shots.spec.mjs` | 新：双端三态实拍 spec |
