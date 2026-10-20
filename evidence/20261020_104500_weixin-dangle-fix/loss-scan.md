# 消息丢失残面 · 日志扫描与归因（2026-10-20）

范围：作者报「还是存在丢失」（F1 已修 web 端 WS 断线入队腿，commit 9c2beee9c/f59e0aa0a）。
方法：引擎侧日志（主实例 `~/.nebflow/logs/stand*.log` 10-18/19/20）+ sidecar 日志
（`~/Library/Logs/nebflow-weixin-sidecar/{out,err}.log`，10-07 起）+ 冷启动实例日志
（`/tmp/nebflow-coldstart/logs/stand*.log`）。

## 一、丢失证据时间线（微信腿）

| 时间（本地） | 证据 | 出处 |
|---|---|---|
| 10-10 09:05 | **最后一次成功出站发送**（`send: to=o9cq80y…`），此后微信腿零成功交互 | sidecar out.log |
| 10-10 09:13–13:19 | login session #2-#15 连续 begin（登录churn，未确认） | sidecar out.log |
| 10-07 起 | `bot token stale (-14) — pausing 1h`（session-guard 镜像）间歇出现 | sidecar err.log 首行 |
| ≤10-15 | `/tmp/nebflow-coldstart/secrets/` 已消失（macOS /tmp 清理） | 推断：10-15T07:22:55Z 重启日志 `token file …(absent)` |
| 10-15 15:22 | sidecar 重启：**ingress token 文件缺席** ⇒ msgloop 投递持握（即使取到消息也发不进引擎） | sidecar out.log |
| 10-18 / 10-20 | QR 登录 session ×2 批次「expired ×4 → giving up」（无人扫码） | sidecar out.log |
| 10-19 21:06 | 冷启动实例注销 feishu+weixin-ilink 桥；其配置中 weixin 条目已被移除 | 冷启动实例 stand.log |
| 10-20 09:16–09:22 | getUpdates tcp_timeout 风暴（后自愈；QR 拉取同宿主成功 ⇒ 会话/长轮询层问题） | sidecar err.log/out.log |
| 全程 | 主实例（8080）weixin 桥从未注册（enabled=false，每次 boot `unregistered`）⇒ 期间若有入站投递到 8080 会 200+dropped[adapter-not-started] | 主实例日志 + PresenceRoutes.scala:1308-1315 |

## 二、归因

**通道悬空期间（≈10-10 → 本批修复）的丢失集中在微信腿**，三个串联缺口：

1. **账号会话面**：bot 会话 -14 暂停/QR 无人扫 ⇒ getUpdates 停摆/暂停期间消息积压在上游。
2. **凭证悬空面**（本批主修）：/tmp token 文件蒸发 ⇒ 入站投递持握（10-15 起），配置面
   Invalid configuration ⇒ 面板不可用、出站腿关。
3. **判决面**（本批新修，机械小刀）：引擎对未注册桥回 **200 + dropped[adapter-not-started]**，
   而 msgloop 旧逻辑「任何 2xx ⇒ delivered ⇒ 游标推进」⇒ 一旦 getUpdates 恢复且有消息到达，
   **静默丢失且上游不重投**（游标已过）。修复=该两个 reason 持握游标（at-least-once），
   策略性 dropped（not-on-allowlist / no-session-bound / no-text-body / ask-*）照旧推进
   （wx_mallory 先例：防毒丸消息卡死环）。

主实例日志 10-18/19/20 扫描（social/ingress/deliver/enqueue/flush 关键词，剔除本会话噪音）：
**无 web 端 WS 腿丢失证据**（F1 修复后的断线入队+重连补发路径无异常日志）。

## 三、已知残面清单（候批，未动刀）

| 残面 | 状态 | 说明 |
|---|---|---|
| /ask 帧型断线不入队 | 已申报二期 | F1 批明确「/ask 帧型队列无承载保持早退」（9c2beee9c 批注）；结构面=ask 帧需离线承载语义，非小刀 |
| F3 发送在途账本 | 候建 | 出站 send 无在-flight 台账，断线窗口的已受理未知送达无法对账 |
| F2 增量游标 | 候建 | 补发幂等依赖 clientMessageId 判重（#569），无增量游标前重连全量对账 |
| `WEIXIN_SEND_TOKEN_FILE` 一名双腿 | 本批实证，候语义收敛 | msgloop（入站）与 send.mjs（出站）双读同名 env 期望不同 token；现役部署以「零 env + 双默认路径」规避；机制收敛（env 分名或单 token 合一）候作者裁 |
| 引擎 200+dropped[adapter-not-started] 应答形态 | 候批 | 本批在 msgloop 侧守住；引擎侧改 503 是契约面变更（phase-1 闭环测试钉住 200 形态），候批另议 |
| 微信通道 default session 未绑 | 作者一键 | 通道面板绑 default session 后入站闭环即通（feishu C4 同型） |
