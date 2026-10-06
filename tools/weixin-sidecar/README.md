# weixin-sidecar — Nebflow 微信扫码控制端点（b 案 shim）

作者裁决 2026-10-05（b 案）：官方 `@tencent-weixin/openclaw-weixin@2.4.9` 发行物
不带 HTTP side-car（官方登录路径为 CLI 终端二维码），授权按 Nebflow README
「Weixin (iLink) channel」契约自建 `127.0.0.1:8787` 控制端点。

- 契约面：`src/main/scala/nebflow/social/WeixinIlinkScanBind.scala`
  （`GET /login/begin` + `GET /login/status`，双 GET 无 POST；验收基线
  `WeixinIlinkScanBindSpec` WSB-1…WSB-8）。
- QR 源：官方插件登录流的**报文级镜像**——`POST ilink/bot/get_bot_qrcode`
  → `qrcode_img_content`（即官方 `displayQRCode` 喂给 qrcode-terminal 的同一个
  串）作为 `qrUrl` 吐给卡片；卡片端 `web/vendor/qrcode.js` 把该串直接编码成
  二维码，微信扫码即走官方登录。不走模块 import（插件依赖宿主 SDK，
  工程外不可加载）。
- 零 npm 依赖；Node ≥ 22.13.0（与插件同门槛）。

## 端点

| 端点 | 方法 | 行为 |
|---|---|---|
| `/login/begin` | GET | 重启登录会话；QR 已取到则即时回 `{"state":"qr_ready","qrUrl":…,"expireIn":…}`，否则 `{"state":"starting"}`（≤4s 等首码） |
| `/login/status` | GET | 会话投影：`starting / qr_ready / polling / done / failed`；`done` **仅首次**携带 `credentials{bot_token,ilink_bot_id,ilink_user_id}`，此后不再出现 token |
| `/healthz` | GET | 运维探针（契约外，仅本机） |

安全面：只绑 loopback（该契约缝无鉴权，按设计仅本机可达）；token 不落日志
（只记长度）；`done` 后凭证只出一次。

## 手动起停（最小可用 / 今日验证用）

```bash
# 起（前台）
node tools/weixin-sidecar/shim.mjs

# 起（后台保活，日志追加）
mkdir -p ~/Library/Logs/nebflow-weixin-sidecar
nohup node "$(cd tools/weixin-sidecar && pwd)/shim.mjs" \
  >> ~/Library/Logs/nebflow-weixin-sidecar/manual.log 2>&1 &

# 停
lsof -ti tcp:8787 | xargs kill
```

依赖清单：Node ≥ 22.13.0、可达 `ilinkai.weixin.qq.com`、宿主 state 目录
`~/.openclaw/openclaw-weixin/` 可写（账号镜像开时）。无其他依赖。

## launchd 自持（与 Nebflow watchdog 无关）

```bash
bash tools/weixin-sidecar/install-launchd.sh        # 安装并立即拉起
launchctl kickstart -k gui/$(id -u)/com.nebflow.weixin-sidecar   # 重启
launchctl bootout gui/$(id -u)/com.nebflow.weixin-sidecar        # 停止
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.nebflow.weixin-sidecar.plist  # 再起
tail -f ~/Library/Logs/nebflow-weixin-sidecar/{out,err}.log      # 日志
```

`KeepAlive=true + RunAtLoad=true`：崩溃自动拉起、开机自启；独立 LaunchAgent，
不依赖任何 Nebflow watchdog（watchdog 移除不受影响）。安装脚本对既有 plist
先备份（`*.bak-<ts>`），循配置面备份纪律。

## 登录成功后的宿主侧镜像（默认开，`--no-account-sync` 关）

镜像官方插件 confirmed 后的落盘行为（`channel.ts` → `saveWeixinAccount` +
`registerWeixinAccountId`）：写
`~/.openclaw/openclaw-weixin/accounts/<ilink_bot_id>.json`（0600，覆盖前备份）
并登记 `accounts.json` 索引——不做这一步，重登会撞 `binded_redirect` 死角
（服务端认定已绑定、又无本地凭证）。与官方的差异：同 userId 旧账号官方会删，
本 shim 只告警不删；openclaw.json 的 `channelConfigUpdatedAt` 戳本 shim 不写
（宿主插件下次重载/重启时自然拾取账号文件——此为候示项）。

## 已知边界（最小可用范围）

- `need_verifycode`（手机出验证码数字、官方 CLI 要求回输）：卡片缝无输入面，
  本 shim 判 `failed`，理由原样上卡；重开卡重扫即可。
- `binded_redirect`（机器人已绑定本宿主）：无新凭证下发，判 `failed` 并说明
  「已有凭证仍有效」；处置方式候示。
- 二维码过期由服务端驱动，shim 自动换码至多 3 次（与官方一致），换码后
  `qrUrl` 变化，卡片自动重渲染新码。
