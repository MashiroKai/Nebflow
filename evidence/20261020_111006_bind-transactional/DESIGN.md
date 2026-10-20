# 绑定事务化硬化设计（bind-transactional，2026-10-20）

事故：bind-through autowire 的 provision 失败（8787 被旧 shim 占，停旧后立即
bootstrap 仍 exit 5），绑定流程继续半完成——卡片已写 defaultSessionId、桥已起、
LaunchAgent 未装载 = 半绑定，回复静默丢 [no-return-address]。

三处硬化，语义变化点各一条：

## ① provision 重试 + 端口等待 + 活性确认（WeixinBindAutoWire）

- provision 失败（bootstrap 非零退出 / transport Left / 活性确认失败）不再一次即弃：
  等端口真正释放（`lsof` 轮询，预算 `PortFreeWait=10s`，步进 500ms）+ 指数退避
  （1s/2s/4s）后重跑 installer，共 `MaxProvisionAttempts=4` 次（首次 + 3 次重试）。
- 成功（exit 0）后必须过活性确认：端口 LISTEN（lsof）+ 首次探活
  （GET /healthz，2s 超时），预算 `LivenessWait=15s`（launchd ThrottleInterval=10s
  的现实）；不过 = 该次尝试失败，进重试/回滚路径——launchd 说装载了但 shim 没起
  也算失败，不再制造半绑定。
- 三个新缝（全部可注入，离线 spec 零网络）：`PortProbe`（端口有 LISTEN?）、
  `HealthProbe`（/healthz 通?）、`Sleeper`（退避睡眠）。默认实现即上述生产腿。

## ② 失败回滚（productionActivate 事务化）

重试穷尽 ⇒ 回滚整个绑定，然后才向 scan-bind 状态机 raise：
1. 清卡片 defaultSessionId（`SocialChannels.setDefaultSessionId(root, ch, None)`）；
2. 桥路由撤销（`BridgeManager.unregister` —— stop 清路由/缓冲，接缝下线）；
3. 卡片写入 `bindError`（surgical entry 级写，同 defaultSessionId 形态；含具体失败步
   ——每次尝试的 step+detail 拼接），`GET /api/social/channels` 面板载荷透出；
   scan-bind status 以 `failed("bridge sync failed: provision failed …")` 显式报错。
   不允许半绑定态存在：要么全绑上，要么明确告诉用户没绑上。
- `bindError` 在下一次成功绑定时清除；卡片重新 save（新绑定尝试）天然覆盖旧错误
  （save 的 entry 是整体重建）。
- sync 腿的 raise 语义不变（旧契约逐字保留）；事务化只包住两张新腿。

## ③ no-return-address 不再静默（WeixinIlinkBridgePlugin）

判别器：本进程内该会话是否被本接缝注入过（`servedRef`，inject 时记录）——
dispatchAgentEvent 把所有会话事件广播给所有插件，纯本地会话的 done 也会进
reply 腿；只有「被本渠道服务过却无回路」的会话才是真实用户可见丢消息。

- served + 无 sender ⇒ 升级告警：ERROR 级日志（保留 no-return-address 稳定词）+
  健康面 strikes 落盘（`<root>/tmp/social-health/weixin-ilink.json`，reason/
  sessionId/count/firstAt/lastAt，面板 `channels[].health` 透出 → 标黄）+
  死信暂存最近一条被丢回复（每会话一条，TTL 30min）。
- 非 served ⇒ debug 级（那不是微信流量，WARN 是噪音）。
- 补投：sender id 首次注册进路由表时（autoBind / rebuildRoutes 检出新 (sender→
  session) 对），以 `[补投]` 前缀补投死信（TTL 内）；发送失败只 WARN，死信丢弃
  （告警面已立）。该会话下一次发送成功 ⇒ 清除 strike（健康面回绿）。

## 面板态

- `channelsJson` 每卡新增 `bindError`（null=无）与 `health`（null=健康）两个键，
  纯增量、向后兼容。
- scan-bind status 的 failed reason 含具体失败步（persistAndFinish 的 Left 臂从
  「类名」升级为「类名+message」）。

## 验收映射

- spec 模拟 provision 失败（占端口=注入 fake runner 非零退出 + fake PortProbe 常占）
  → 断言重试次数、回滚效果（defaultSessionId 清、bindError 在、桥注销）、面板错误态
  （channelsJson.bindError + scan-bind failed reason）。
- 半绑定不可达断言：回滚后 {defaultSessionId 已清 ∧ 桥已注销 ∧ bindError 已写}
  三条同时成立，且绑定终态 = failed。
- 既有微信绑定族回归：WeixinIlinkScanBindSpec（WSB-*、BT-1/2/5 保持）+
  WeixinIlinkBridgeSpec 全量。
- evidence/20261020_111006_bind-transactional/。
