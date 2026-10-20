# bind-transactional 验收证据（2026-10-20）

事故：bind-through autowire（WeixinBindAutoWire）provision 失败（8787 被旧 shim
占用，停旧后立即 bootstrap 仍 exit 5），绑定继续半完成——卡片已写
defaultSessionId、桥已起、LaunchAgent 未装载 = 半绑定，回复静默丢
[no-return-address]。

三处硬化全部落地，验收映射：

## ① provision 重试 + 端口等待 + 活性确认

- `WeixinBindAutoWire.provisionLeg`：至多 4 次尝试（首次 + 3 重试，指数退避
  1s/2s/4s）；重试前等端口真正释放（lsof 轮询，10s 预算 / 500ms 步进）；
  exit 0 后必须过活性确认（端口 LISTEN + GET /healthz，15s 预算）才算成功。
- spec 钉死：
  - BT-6（事故复现→恢复）：attempt 1 exit 5（占端口）→ 端口释放 → attempt 2
    装载 + 活性通过 ⇒ 绑定 done、defaultSessionId 保留、退避梯子 [1s]。
  - BT-7（launchd 说装了但 shim 没起）：exit 0 + 死 sidecar = 失败尝试 ⇒ 重试
    穷尽 ⇒ 回滚（不允许"装了但没起"的半绑定读成已绑）。
  - BT-10（有界等待形状）：端口忙→释放的轮询步数、LISTEN∧/healthz 双闸、
    迭代数上限（无 wall-clock）。
  - BT-8（installer 缺失）：活性仲裁——活着 ⇒ 绑定成立；死了 ⇒ 绑定失败。

## ② 失败回滚（不允许半绑定态）

- `WeixinBindAutoWire.rollbackBinding`：清卡片 defaultSessionId、
  `BridgeManager.unregister`（stop 清路由表）撤销桥路由、卡片写 `bindError`
  （含每次尝试的具体失败步）；回滚完成后才 raise，scan-bind 终态 `Failed`。
- `SocialChannels.BindErrorKey/setBindError/bindError` + `channelsJson` 新增
  `bindError`/`health` 两个增量键（面板通道态数据面）；
  `WeixinIlinkScanBind.persistAndFinish` 的 failed reason 从「类名」升级为
  「类名+message」（错误含具体失败步）。
- 前端 `socialPanel.js`：`displayStatus` 组合 bindError ⇒ failed pill；
  `cardHint` 渲染 bindError 原文（失败步）与 health 告警行（黄）；locale 键
  `social.hint.health`（zh/en）。
- spec 钉死：
  - BT-3（半绑定不可达主断言）：4 次尝试穷尽 ⇒ failed 终态 + defaultSessionId
    已清 + 桥已注销（StopRecordingPlugin 停机计数=1、registeredNames 不含
    weixin-ilink）+ bindError 含 attempt 4 + channelsJson 透出 + 退避梯子
    [1s,2s,4s]。
  - BT-4（transport 级失败同权）：raising runner ⇒ 同样重试穷尽 + 回滚 + failed。
  - BT-9（错误生命周期）：旧 bindError 被下一次成功绑定清除。
  - WI-F6（面板面）：bindError + health 进 channelsJson 载荷；重新 save（新的
    绑定尝试）清 bindError——错误活到用户再试为止。

## ③ no-return-address 不再静默

- `WeixinIlinkBridgePlugin`：`servedRef`（本接缝注入过的会话）为判别器——
  - served 会话的 drop：ERROR 级升级日志（保留 no-return-address 稳定词）+
    健康面 strike 落盘（`<root>/tmp/social-health/weixin-ilink.json`，经
    `channels[].health` 透出 → 面板黄色告警行）+ 死信暂存最近被丢回复；
  - 非 served 会话（纯本地流量，dispatch 全员广播）：降为 debug（不再是噪音 WARN）；
  - sender id 首次注册（autoBind / rebuildRoutes 新 (sender→session) 对）⇒
    `[补投]` 前缀补投死信（TTL 30min，过期即弃）；该会话发送成功 ⇒ strike 清除。
- spec 钉死：WI-NR1（strike+stash、零发送）、WI-NR2（非 served 静默）、
  WI-NR3（autoBind 补投）、WI-NR4（成功即回绿）、WI-NR5（TTL 过期不补投）、
  WI-NR6（REST/绑定面 rebuild 补投 + 幂等）、WI-NR7（count 累积、firstAt 稳定、
  letter 取最新）。

## 回归

- WeixinIlinkScanBindSpec：19/19（WSB-1…8 绑定族原样通过；BT-1/2/5 原样通过，
  BT-3/4 按新契约反转并保留原文说明）。
- WeixinIlinkBridgeSpec：55/55（WI 原族 47 + 新 NR/F6 8）。
- **nebflow.social 全包回归：392/392**（`testOnly nebflow.social.*`，日志
  test-social-package.log——含 SocialChannels 消费方、飞书桥/适配器、boot seal、
  outbound 全族）。
- 邻接 gateway spec（SocialDefaultSessionRoutesSpec / SecretsRoutesSpec，
  读 `SocialChannels.readChannels`）：29/29。

## 运行安全边界（如实）

- 全量 `sbt test` 未在本 worktree 跑出结果：首次发起后本会话经历一次引擎侧
  crash，后台任务连回执一并丢失（孤儿 sbt 亦随 crash 终止）；重启后按本仓既定
  实践（全量测试在本环境不可靠 → 聚焦子集）改跑受影响面全集
  （nebflow.social 全包 392 + 邻接 gateway 29），全部通过。本 diff 触及面经
  grep 核对仅落在这两处（PresenceRoutes 仅注释变更）。
- `tests/social-panel.spec.mjs`（Playwright 面板 spec）在本环境基线即失败
  （headless 下 `#social-btn` 不可见，挂在 openPanel 等待），与本次改动无关
  （改动前面板 spec 即此结果）。前端改动仅消费载荷新增键，mock 载荷不含这些键
  ⇒ 现有面板断言面不变；面板 spec 的实机复核留给有头环境。
