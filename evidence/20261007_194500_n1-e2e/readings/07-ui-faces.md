# ⑤ UI 三面读数（rig2 · ae2432039 构建 · Playwright chromium 真浏览器 · port 27819）

驱动：`/tmp/n1-e2e-rig/ui/faces.js`（A/B）＋ `faces3.js`（A 两段式补答 + C）。鉴权 `?token=` URL 注入（ws.js :479-483 落 localStorage）。帧落 frames/，DOM 读数落 frames/readings.json。

## A · AskUser badge 条件分组（badge 批 10a95018c 新口径）— PASS
- 真实 AskUserQuestion 卡（requestId=asknb-e26e2df379c24a06，问题「N1 E2E 演示：选哪个标记色?」red/blue 两选项）。
- 作答前（badge-before.png）：`.turn-header[data-turn-state]` 仅既有 4 枚 done（onboarding 2 + BRIEF-U2 链 2）；live 问询 turn **无 data-turn-state 字段＝未终态**（turnGroup settle 才落字段，与 priorTurnBadgeSettled 判据一致）；卡未 resolved，确认钮灰显。
- 作答（两段式：radio 选中「red」→「确认」）：`resolved=true`；第 5 枚 header 落 `done`（settle 实证）；答案行 `-> red` 绿字与已闭卡同组内联（badge-after.png 目检）。
- 页面真点击作答，零 API 代答；AI 侧收「收到答案：标记色选 red（选项 A）。问题卡已闭环」。

## B · 工具行细节流式（toolline-detail 批）— PASS
- live turn 中（root 派 delegate BRIEF-U2）：`.nf-wl-item`（无 is-done）上实时捕获 spinner + `.nf-wl-label`（滚动尾锚）+ **`.nf-wl-detail` 流式 span**＝Delegate 工具参数逐帧流入（tool-live.png，label=「Delegate(【BRIEF-U2】…」detail=全参文本）。
- 完成翻转：`is-done` + resultText 单行摘要原位替换（「Delegate task started in the background (task #2). Executor: nebflow; workspace: …/worktrees/delegate-nebflow-d423a0e4; branch: delegate/delegate-neb…」）＝工具行完成态（tool-done.png）。

## C · 插件派发 toggle（29ca386ad 面）— PASS
- 插件页（#agents-btn，canvas 右栏「插件」tab）：智能体 3 行（Nebula/nebflow/subagent）＋ 插件卡 7 张（browser-use/computer-use/document-production/nebflow-plugin-creator/slideblocks/visual-report/web-search-toolkit）。
- `.plugins-card-state .nb-toggle` 实测 14 枚（=7 卡 × 展开态+收起态行尾**一处状态源两处视图**，与 29ca386ad 设计一致）。
- 点击首卡（browser-use）：`aria-checked true→false`、class `nb-toggle on→nb-toggle`（panel-toggle-flipped.png 目检：首卡 toggle 变灰、余卡保持绿）＝乐观翻转＋POST enable/disable＋registry 收敛；再点还原 `aria=true`。

页面 pageErrors：零（faces.js 挂了 pageerror 监听，全程无输出）。

## rig2 journal 服务端双确认（停机前尾日志）

- A 面：`event=answer requestId=asknb-e26e2df379c24a06 kind=AskUser … via=card answersJoined=red` ＋ `askUser answer bridge: … injected as a user message`——页面真卡作答走通桥接。
- C 面：`Plugin 'browser-use' dispatch disabled by panel/rest — affects FUTURE dispatches only` → 随后 `enabled by panel/rest`——toggle 翻转/还原真实打到后端 enable/disable 端点（in-flight 节点 grant 不受影响语义同现）。
- B 面同段：`mounted scope 'delegate-nebflow-d423a0e4' … seat=…/worktrees/delegate-nebflow-d423a0e4`＋`Write(ui-probe.md) OK`＋`delegate_report(finish)`——工具行所映 delegate 真在 worktree 干活收尾。

