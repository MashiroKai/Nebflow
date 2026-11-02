# 续聊会话详情面板正文空白修复批 · 过程报告（2026-11-01）

批次：`delegate-nebflow-d86b41ad`（重派；前派批因 LLM 故障阵亡）
根因/结论文档：`docs/20261101_180000_continuation-panel-blank-rca__delegate-nebflow-d86b41ad.md`
证据目录：`evidence/20261101_continuation-panel-blank/`

## 一、作者口径（逐条守住）

1. **是 bug，不是缺功能**——判别变量 = 「会话是否由 Mail 续聊腿起」，修的是该分支的接线；
2. **与 output 块掩码无关**——本批零触碰 `outputTag.js` / `applyOutputFace` / `<output>` 语法面；
3. **徽章行能渲染、正文不渲染 ⇒ 死在取数或渲染环节**——断点在**取数腿**（三条腿分别取证，见下）。

## 二、判别与取证（两条腿分别落证）

| 腿 | 取证方式 | 结果 |
|---|---|---|
| 取数（接口/落盘） | `census.py` 全量普查：`~/.stand/subagent-tasks/*.json` 的每条 `delegate-*` × 各自 `.ui.json` 行类统计 | continuation 会话 **105/105 零 ai+tool 行**；非 continuation **1021/1021 有行**；仅 4 条 failed/cancelled 零工作会话为例外（无正文可失） |
| 取数（代码链） | `routeWsSend` 底座退化 + 录制层唯一写者 + 注入行旁路 | `DelegateTool.scala:229` `wsSend.getOrElse(no-op)` ⇒ 帧在发射处被吞；`WebSocketRoutes.scala:2779` 是 ai/tool/thinking 行的唯一写者；注入行走 `AgentActor.scala:866-871` 直写 ⇒ 只剩徽章行 |
| 渲染 | 真前端 + 改前真实载荷（场景 A） | 22 条注入行渲染、正文零行 ⇒ 渲染器对输入忠实，非渲染缺陷 |

## 三、修法

`src/main/scala/nebflow/core/tools/MailTool.scala` `continueDelegate`：`wsSend = None` → `wsSend = ctx.wsSend`（+ 归因注释，标注同族两腿的对照面）。一行有效改动，零新增参数/字段。

## 四、验收

- `sbt compile`：**success**（701 sources，4m26s）。
- `sbt "testOnly nebflow.core.tools.DelegateContinuationWsSendSpec"`：见下「实测行」（S1 吞帧机制 / S2 落盘键盖章 / S3 覆盖契约不破 / S4 防复活钉）。
- 面板实拍 `tests/continuation-panel-blank.spec.mjs`：**ALL GREEN（4/4 组合）**，Chromium + WebKit × 深/浅双主题，零 console.error / 零 pageerror。
  - A（改前实盘载荷）：注入行 22、正文 0 ⇒ 复现报障；
  - B（修后应有载荷）：ai 234 / tool 184 / thinking 113 行、归因行 1、注入行 22 不丢、正文体积 353 KB、展开后工具卡与思考块 `offsetHeight>0` 全可见。
  - 图：`shots/<engine>-<theme>-A-blank-before.png` / `-B-body-restored.png` / `-B2-body-top.png` / `-B3-process-expanded.png`。

## 五、未做 / 开放项

1. 已产生的 105 条续聊会话正文不可回溯（帧从未落盘），面板对它们仍空白；新起续聊即刻恢复正常。
2. 同类 `wsSend = None` 另两处未动：`DeviceDelegate.scala:531`（对端设备委托，对端无 viewer）、`ProjectCrashRecovery.scala:338`（boot 恢复挂载 flow 宿主 ⇒ 重启窗口 node-* 会话可能同病，需另批取证）。
3. 未做整包回归（`sbt test` 全量）；本批只跑新增 spec + 前端实拍脚本（测试源码 968 份整体编译通过，无连带编译破面）。
4. `check_wiki_docs.py --root docs` 退出码 = 1，两条违规全部落在**他批已并入的文档** `docs/20261101_172500_mobile-panel-title-b__delegate-nebflow-0c24df31.md`（R3 概念首现「样张」未解释 / orphan-doc 零出入链）；本批新文档零违规。按「不碰在飞批产物」纪律未就地代改，留待该批或文档债批收口。

## 六、实测行

```
$ sbt -batch compile
[info] compiling 701 Scala sources to …/target/scala-3.5.2/classes ...
[info] done compiling
[success] Total time: 266 s (0:04:26.0)

$ sbt -batch "testOnly nebflow.core.tools.DelegateContinuationWsSendSpec"
[info] compiling 968 Scala sources to …/target/scala-3.5.2/test-classes ...
[info] done compiling
DelegateContinuationWsSendSpec:
  + S1 wsSend=None（续聊腿改前形态）⇒ 帧在发射处即被吞，录制层零收帧   0.239s
  + S2 wsSend 在场 ⇒ 帧送到录制层且盖 nodeSessionId/sessionId/rootSessionId（落盘键）  0.037s
  + S3 帧自带 nodeSessionId ⇒ 路由不覆盖（跨层路由的既有契约不破）   0.002s
  + S4 防复活钉：MailTool 续聊腿（continueDelegate）必须桥接 ctx.wsSend，禁止回退 wsSend=None  0.006s
[info] Passed: Total 4, Failed 0, Errors 0, Passed 4
[success] Total time: 420 s (0:07:00.0)

$ node tests/continuation-panel-blank.spec.mjs
…（chromium-dark / chromium-light / webkit-dark / webkit-light 四组合逐条 PASS）
ALL GREEN        （45 行输出：A 场景 5 断言 + B 场景 10 断言 × 4 组合）

$ python3 evidence/20261101_continuation-panel-blank/census.py
continuation?  has ai+tool rows  count
  False        False          4
  False        True           1021
  True         False          105

$ python3 <dataRoot>/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py --root docs
wiki-docs 校验：2 条违规（docs）——**均为他批文档**（见开放项 4），本批新文档零违规、exit 判据按他批存量未达标
```

- 实拍断言摘录（chromium-dark，其余三组合同）：`A 注入行 22 条照常渲染 / A 正文零行（复现报障）/ A 归因徽章行在场`；`B ai=234 tool=184 thinking=113 / B 归因行 1 / B 注入行 22 不丢 / B 正文体积 353617 / B 展开后工具卡可见 184 / B 展开后思考块可见 113 / B 零 console error`。

