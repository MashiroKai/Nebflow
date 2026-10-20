# AskUser 社交腿答案回注（设计批 · 只设计不实施）

作者令：「可以派设计治本」。目标：作者微信/飞书纯文本回复 → 自动路由回注到发起 AskUser 的会话，作为该问题的答案解除阻塞，不经中转。

## 一句话结论

上行链路的七步（sidecar 收信 → 桥台账解析 → 网关汇聚 → hub 消费）**已存在且健康**；真正的缺口只有最后一公里——**发起会话已消亡时（后台批节点在 `node_report` 后会话销毁），答案在 `AskUserAnswerBridge.deliver` 的 `sessionAlive=false` 分支被 WARN + 丢弃**（`AskUserAnswerBridge.scala:134-137`），而 Mail 对 blocked（terminal 族）节点拒收（`NODE_TERMINAL_NO_MESSAGE`）＝「喂不进去」。设计核心＝在这唯一分支上加一个**答案着陆面**：节点归因在 → 引擎重入（复用 FeedbackRouter.ReenterDispatcher 族管线）；归因缺席/宿主不在 → 显式失败回执 + 审计，绝不静默。

## 前提修正（设计依据的事实基础）

任务叙述称「阻塞式 AskUser 期间会话没有轮次边界」。现实：**阻塞档已于 #462 裁①（2026-10-02）整体移除**——`AskUserQuestionTool` 现为非阻塞-only（`AskUserQuestionTool.scala:368-375` 注释「阻塞腿已整体移除」；`mode` 参数出现即拒 `:338-346`）。「回注为工具结果解除工具 fiber 等待」的形态已不存在。今日的回注形态只有两种：**活会话＝下一 turn 边界的用户输入注入（ImmediateInput）**；**亡会话＝引擎面重入**（本批设计的新面）。「阻塞」在今日语境＝后台批节点问完后以 blocked 终态收场、答案后到的窗口。

## 产出物

| 文件 | 内容 |
|---|---|
| `设计_答案回注链路__delegate-nebflow-294b9099.md` | 现状盘点（全锚点）＋缺口定位＋上行链路设计＋答案着陆面＋涉及文件清单 |
| `映射规则推荐表__delegate-nebflow-294b9099.md` | 编号/批名/短号映射、解析臂优先级、多 pending 并发规则、误路由判据、通道×状态处置矩阵 |
| `开放问题清单__delegate-nebflow-294b9099.md` | 候拍开放点（重答机制、多问在册、渠道回执面等） |
