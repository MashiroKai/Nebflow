# ask mode 三修 · 证据包（2026-10-29，delegate-nebflow-e4382883）

任务：作者 2026-10-29 10:27 截图报障（console 渠道）三件——①ask LLM 等不到回复（接线）②FAB
通知点改绿色未读条件显示 ③ask 风格并入 canvas 统一档。分支 `delegate/delegate-nebflow-e4382883`，
三个独立 commit；未 push；在产实例（pid 63637 @6138）零触碰。

## ① fix(ask-gateway)：ensureAgent ask 会话分支

- 根因（引擎侧，网关路由层）：`WebSocketRoutes.ensureAgent` 对 ask id 走
  `sessionStore.getSessionMeta`，而 ask 会话**不进** session _index ⇒ 恒 None ⇒
  「Dropping message to unknown session」，ask agent 永不 spawn、LLM 永不调用。
- 修复：ensureAgent 在查 meta 前先问 ask 绑定表（`<dataRoot>/ask/index.json`）——
  有绑定 = 真会话 → `ensureRootAgent`（其 resolveAgentDef 已有 ask 分支：组装提示词 +
  ask 工具早退集）；无绑定仍拒（fail-closed 口径不变）。
- LIVE 证据（真实 WS 发问 → 真实流式回复）：
  - `fix1-live-server-log-excerpt.log`：/tmp/ask-live-home（@8299）日志行 50-64——spawn →
    FlowTreeActor → tool_use `message_record_search` → end_turn → `turn-complete
    textLen=327 textStreamed=true model=zhipu/glm-5.3-flash`（11:21:51→11:22:25，34s）。
  - `fix1-live-reply.txt`：327 字回复全文（会话 ask-30316e1f 转录）。
  - `fix1-live-ws-frames.jsonl`：askOpen→askState(+205ms)→askQuestion→textDelta×73→done(+33.8s) 全帧。
  - 客户端：`tests/ask-live-client.mjs`（真 WS 协议：askOpen→askState→askQuestion→done）。

## ② fix(ask-dock)：FAB 通知点 = 绿色未读点（无未读不显示）

- 作者令：「小点应为绿色通知点，只在有（未读）消息时显示，无消息不显示」。
- 改动 `web/js/askDock.js`：session 记录加 `unread`；`syncUnreadDot()`（has-unread 类 +
  pulse 仅未读）；open() 清未读；close()/收起用 syncUnreadDot；textDelta/done/error/
  injected-user 在「不在看」（dock 收起 ∨ 非当前文件）时置未读；askState 开态清未读；
  删除旧 markSess/has-sess（「有过消息即常显紫点」退役）。
- 底色 = `--color-primary`（#07c160 品牌绿单源）；spec：T3h（在看流→无未读）、
  T5a/b/c（已读收起不亮 / 闭窗推流绿点+pulse / 再开即读）。
- 截图：`shots-after/t5-unread-dot.png`。

## ③ style(ask)：输入条/气泡/FAB 并入 canvas 统一档（不新造风格）

- 自造毛玻璃材质（--ask-bub-*/--ask-input-*、品牌蓝渐变面）整体退役；材质全部改引
  sapphire.css `--cv-*` 单源（= 聊天 #header 玻璃模板）；hover/active 用 --cv-hover-*；
  圆角 --cv-radius-bar；用户气泡 = rgb(var(--sapphire)/0.14)+描边 0.35（canvas 活性语言）；
  发送钮 = 全站发送键绿家族 #07C160/#06AD56（input.css #send-btn 逐字）。
- spec T12a-e：fab/input/bub 计算材质与 #header 逐项相等 + 发送钮/通知点绿。
- 截图：`shots-after/t12-unified-style.png`（另 t3/t4/t6/t9/t11 同批全绿）。
- spec 总运行：**48 pass / 0 fail**（clean 全量构建后复跑，PORT=8233）。

## 修复前（作者现场）

- `author-before-screenshot.jpg`：作者 10:27:58 截图（紫点常显、自造毛玻璃）。
- `fix1-before-prod-log-excerpt.log`：在产实例 /Users/kaiyu/.stand/logs/stand.log 行
  31297-31298——10:27:40 `User text (ask-dock) ask-7712978b (10 chars)` → `Dropping
  message to unknown session ask-7712978b (not a real session)`（缺陷①现场）。

## 开放缺陷（非本批引入，未修，已封存分析）

`open-defect-file-leg-none.md`——ask 组装「文件腿」在部分沙箱 daemon 进程恒 None
（按进程非确定；离线直跑同一代码正常；八项因素已排除；探针因 zinc 增量不一致失效，
复查须 clean 构建 + stdout 探针）。

## 沙箱实例清单（作者可自行清理；本批未杀任何进程）

| pid | 端口 | home | 用途 |
|---|---|---|---|
| 78336 | 8299 | /tmp/ask-live-home | 修复① LIVE 验证 |
| 84191 | 8300 | /tmp/ask-live-home2 | 早期探针轮 |
| 89806 | 8303 | /tmp/ask-live-home5 | 早期探针轮 |
| 92563 | 8304 | /tmp/ask-live-home6 | 探针诊断轮 |
| 1561（daemon child） | 8305 | /tmp/ask-live-home7 | None 缺陷复测（两问均 None） |
| 8467（daemon child） | 8306 | /tmp/ask-live-home8 | None 缺陷对照（正常引用原文） |
| — | 8301/8302 | — | 可能有残留 daemon（/tmp/ask-live-boot3.log、boot4.log） |

清理：`kill <pid>` + `rm -rf /tmp/ask-live-home*`（均在本机 /tmp，无共享状态）。
