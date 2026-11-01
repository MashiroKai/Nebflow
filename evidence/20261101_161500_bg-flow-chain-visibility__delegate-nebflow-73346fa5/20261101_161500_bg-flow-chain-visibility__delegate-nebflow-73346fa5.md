# Flow 会话可见性批 · 实拍证据（2026-11-01 16:19 · engine/theme × scene 矩阵）

批：控制台后台代理面板修复（分发器模式链会话可见性）· 分支 delegate/delegate-nebflow-73346fa5

## 矩阵

- 引擎 × 主题：Chromium / WebKit × dark / light（4 组合全拍）
- 场景：
  - **A 活链（延续态）**：delegate 注册镜像 completed + 作用域桶快照链行（dispatcher Idle + node Processing ×2）→ 合成延续组头琥珀「链运行中」（mode 徽标 dispatcher + 短名「链批」）+ 分发器/节点缩进子行（各带 kind 徽标/状态/运行时长）；徽标计数 4 = 主桶组头 1 ∪ 链行 3；面板节签「智能体 · 4」同值。
  - **B1 链全终态（恢复普通终态样式）**：链会话级 done 后组头回「已完成」灰化普通终态（琥珀徽标退场、子行随终态管线删除）。
  - **B2 面板回空（徽标回收）**：过 agentDone 2s 清理窗 → 行集回空 + 徽标隐藏（死链零滞留）。

## 审计

- 全部 4 组合 × 3 场景断言 PASS（44/44，脚本 tests/bg-flow-chain-visibility-shots.mjs 自审计输出）。
- **console 零 error** 全程（pageerror + console.error 双采集；harness 关注册 `stand_sw=0` SW 逃生门与 `/agents/<root>/agent.json` 探测 stub——后者 404 帧是此前 console 噪音源）。
- WebKit 侧注意：网络进程拒受限端口，实拍 base URL 用 127.0.0.1:8931（路由全 mock，host 任意）。

## 数据面

WS 帧直种（agentStart 三键同构 / activeAgents 快照恢复形状）+ delegateChainStub 夹具——与验收 spec tests/bg-flow-chain-visibility.spec.mjs 同源。零后端、零 sbt。

## 产物

- `shots/<engine>-<theme>-A-active-chain.png` ×4
- `shots/<engine>-<theme>-B1-chain-terminal-style.png` ×4
- `shots/<engine>-<theme>-B2-drained-badge-recycled.png` ×4
