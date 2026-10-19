# scalafix 裁定 — 修复批 #566（2026-10-19）

树状态：恢复重放 + `sbt scalafmtAll` + 无关格式化文件还原后（= 本批提交面）。

## 读数

- `sbt "scalafix --check"`（Compile）：**11 处违规 / 4 文件**——
  `cli/DaemonSpawn.scala:141-143`、`cli/ProcessManager.scala:129,147,163,176`、
  `core/AtomicJson.scala:399`、`social/WeixinIlinkScanBind.scala:384,385,387`。
- `sbt "Test / scalafix --check"`：**7 处违规 / 6 文件**——
  `neblink/FriendMessageFooterBadgeSpec.scala:146`、`neblink/NeblinkRelayFrameSenderSendCycleSpec.scala:340`、
  `neblink/RelayAuthFixtureServer.scala:648,652`、`social/FeishuLiveProbe.scala:157`、
  `social/WeixinAskLiveProbe.scala:353`、`social/WeixinOutboundLiveProbe.scala:172`
  （全部 `DisableSyntax.return`）。

## 裁定

**本批 25 个触点文件（main + test）零违规**（Compile 与 Test 两相均未出现）。
上述被标文件全部为本批零触碰的 HEAD 基线内容（其中 DaemonSpawn/ProcessManager/
AtomicJson/WeixinIlinkScanBind 在 scalafmt 噪声还原集中，已 `git checkout --` 回 HEAD），
属**预存基线红**，非本批引入，本批不修（越面改动违背 pathspec 纪律）。

备注：重置前转录记录的「基线 63 文件违规」读数系在 scalafmtAll 刚全量格式化过的树上
测得（87 文件被重排后计数口径混入），本读数（干净 HEAD 面 + 本批改动）为最终口径；
两读数共同结论一致：基线即红、本批零新增。
