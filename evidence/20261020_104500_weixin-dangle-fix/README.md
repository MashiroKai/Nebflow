# 微信通道密钥悬空 + 消息丢失残面 · 修复批取证

时间：2026-10-20 10:10–10:50（本地）；执行：delegate-nebflow-2585e94d（worktree 分支）
原始备份（含密钥值，不入仓）：`~/.nebflow/backups/weixin-dangle-fix-20261020_104500/`（0600，快照 sha 见下）

## 一、事故链（判断链）

1. **10-08 18:29**：weixin 扫码绑定在**冷启动实例**（home=/tmp/nebflow-coldstart）上完成：
   引擎 `SocialChannels.writeSecret`（root=数据根，`SocialChannels.scala:311-313`）把
   bot_token 写进 `/tmp/nebflow-coldstart/secrets/social-weixin-bot-token`，配置面引用
   即该 /tmp 绝对路径（`renderRoot` 对非默认 home 渲染绝对路径，`SocialChannels.scala:269-271`）。
   同批 `~/.nebflow/secrets/social-weixin-bot-token` 也落了一份（mtime 同为 18:29）。
2. **值找回（未重扫码）**：`~/.nebflow/secrets/social-weixin-bot-token`（58B）与 sidecar
   活跃账号文件 `~/.openclaw/openclaw-weixin/accounts/ac3714d759d1-im-bot.json` 的 token
   字段 **sha256 全等**（c85bd36a78e091e8…）⇒ 持久位副本即活账号现值。
3. **macOS /tmp 清理**（≤10-15）：`/tmp/nebflow-coldstart/secrets/` 整目录蒸发 ⇒
   - 主 home 配置 `~/.nebflow/nebflow.json` 的 `bot_token_ref` 悬空 ⇒ 面板
     「Invalid configuration / Referenced file does not exist: /tmp/…」；
   - sidecar LaunchAgent 的 `WEIXIN_MSGLOOP_TOKEN_FILE=/tmp/.../weixin-ilink-ingress-token`
     一并失踪 ⇒ msgloop「token 文件缺席，投递持握」（out.log 10-15T07:22:55Z 实证 `(absent)`）。
4. **配置漂移**：10-08 批把 /tmp 引用的通道块并进了主 home（主实例由此显示悬空）；
   10-19 21:06 冷启动实例注销 feishu+weixin 桥（其配置现仅剩 feishu 一条），
   微信生产腿事实上归属主实例，但主实例 `enabled=false` 从未接起。

## 二、修复动作（本批）

| # | 动作 | 验证 |
|---|------|------|
| 1 | 主配置手术修复：`bot_token_ref → ~/.nebflow/secrets/social-weixin-bot-token`，`enabled → true`（快照 before/after 在 snapshots/） | probe 三元全 true（exists/modeOk/readable） |
| 2 | 走引擎自带的**免重启重臂**：`POST /api/social/channels/weixin-ilink {"enabled":true}`（`WeixinIlinkBridgePlugin.sync`，Scala:1391-1403） | `GET /api/social/probe` → **adapterRegistered: true** ⇒ 面板 Connected |
| 3 | sidecar LaunchAgent 重写（快照先存）+ launchctl 重启：ingress URL → `127.0.0.1:8080`（生产主实例）；移除指向 /tmp 的 `WEIXIN_MSGLOOP_TOKEN_FILE`；**不设** `WEIXIN_SEND_TOKEN[_FILE]`；移除 `NEBFLOW_HOME=/tmp/...` | out.log：`token file ~/.nebflow/secrets/weixin-ilink-ingress-token (43B)`、`live mode → http://127.0.0.1:8080/...` |
| 4 | 入站 seam 鉴权实测：持久 ingress token POST 8080 → 200 `{"verdict":"dropped","reason":"adapter-not-started"}`（当时桥未注册）；错 token → 403 | 轨2通、fail-closed 同形 |
| 5 | 出站凭证对齐实测：shim /send 持久 send token + 空体 → **400**（鉴权过，体校验拒）；错 token → 401 | 引擎↔shim send token 同源 |
| 6 | coldstart.sh 根治（本分支 commit）：`<home>/secrets` → `$MAIN_HOME/coldstart-secrets`（0700）符号链接 + 清理步保留集 + 历史实目录一次性搬迁 + DRY_RUN 预览 + /tmp 落盘审计（文件头 9)） | bash -n；DRY_RUN/实跑双验（链接创建/幂等/跨清理存活）；scratch 实例已清、8080/8097 零触碰 |
| 7 | msgloop 判决面修复（本分支 commit）：200+dropped(`adapter-not-started`/`inject-failed`) **不再推进游标**（原有：任何 2xx 即 delivered ⇒ 游标推进 = 静默丢失） | node --test 7 新例 + 既有 29 例全绿 |

## 三、⚠️ 部署教训（实证）：`WEIXIN_SEND_TOKEN_FILE` 一名双腿冲突

该 env 名被 msgloop（入站腿：期望 **ingress** token 路径）与 send.mjs（出站腿：期望
**send** token 路径）双读——指向任一文件另一腿必 401（本批 10:44 实证：指 ingress 文件 ⇒
/send 401）。两腿默认值本就是持久路径（`~/.nebflow/secrets/weixin-ilink-{ingress,send}-token`），
**零 env 即两腿全对**。plist 里已留注释；env 归一化的语义收敛候批（报告「候批」节）。

## 四、未竟面（诚实分栏）

- **端到端一条测试消息**：不可行于本批。入站闭环需 (a) 作者真发一条微信 且 (b) 通道绑一个
  default session（现无 ⇒ 任何入站 verdict=dropped[no-session-bound]，与 feishu C4 同型）；
  出站闭环需一个活会话回复。两件都是作者的一次点击/一条消息，不是缺陷。
- **引擎重启窗**：本批以 `sync` 免重启接桥，主引擎**未重启**；重启后 boot 链
  （enabled∧verified ⇒ 注册）与现态一致，无额外动作。
- **iLink 账号会话**：getUpdates 09:16-09:22 有一轮 tcp_timeout 风暴后自愈；QR 登录会话
  10-18/10-20 各两次「expired ×4 → giving up」（无人扫码）。若后续再出 -14/tcp_timeout，
  重扫码一次即愈（列作者操作单，非本批可修）。

## 五、快照与备份

- 入仓（无密钥值）：`snapshots/`（配置面 before/after、plist before/after）
- 不入仓（含密钥值，0600）：`~/.nebflow/backups/weixin-dangle-fix-20261020_104500/`
  - nebflow.json.before sha256 前16=2a15783efc30e2f1
  - com.nebflow.weixin-sidecar.plist.before sha256 前16=a70ee89d688065d0
