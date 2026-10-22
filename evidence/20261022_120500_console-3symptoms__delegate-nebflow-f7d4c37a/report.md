# 控制台三症状批 · 修后报告（① 登出级 bug 诊断 + ②③ 前后对照）

分支 `delegate/delegate-nebflow-f7d4c37a` · 2026-10-22 · 三件全部落前端（刷新即生效）。

---

## ① 上滑看历史触发「登录状态已过期」被迫重登 — 诊断结论

### 请求链（逐环）

| 环 | 载体 | 凭据 | 过 link.mystand.space 中继？ |
|---|---|---|---|
| 1. 上滑触发 | 滚轮/拖动，`main.js` `_primScrollGate`（`#chat.scrollTop < 100`）→ WS 帧 `getHistory` | 无单帧凭据 — WS 握手时一次性带 cookie | **否**（gateway 本地会话页，不出网） |
| 2. 补页渲染 | `historyPage` 帧 → `chat/historyReplay.js` → `persistence.js restoreFromBackendHistory` 前插 | — | — |
| 3. **铸票（凶腿）** | 页内 Card 工具行（`___CARD_HTML___` 载荷含 `/api/nf-file?path=…`）经 `cardRegistry` 渲染 → `nfTicket.mintTickets` → **`POST /api/nf-ticket`** | **cookie 一直带**（`credentials:'same-origin'` 先行；401/403 才补 `Bearer` 重试——凭据链本来就对，本批未改） | relay 源（控制台经 link.mystand.space 同源服务）时**过**：请求先撞 relay 门的 `require_user` |
| 4. 图片本体 | `GET /api/nf-file?path=…&ticket=…`（`<img>` 自动带 cookie） | ticket + cookie | 同上 |

**哪一环 401/失败**：`getHistory`（WS）从不 401；401/403 出在环 3 的 `POST /api/nf-ticket` ——
relay 源 = link-server 15 分钟短会话 cookie（`neblink_session`）过期后，relay 门对一切同源 `/api/*` 裸 403
（Bearer 是 host 网关票，过不了 relay 门）；本地源 = host 会话 cookie 失效且长期票已被
`ws.js purgeLongLivedCopies` 清空（`storedToken()` 为空 ⇒ Bearer 重试自动跳过）⇒ 双发全拒。

**全局登出处理器为何被它触发**：修前 `nfTicket.js` 的 `mintRound` 对终态 401/403 一发就调
`noteAuthRejected('nf-ticket')` → `sessionGate.js` 出「登录状态已过期，正在重新进入控制台…」横幅
+ 1.5s 后 `location.reload()`。该设计是 #520（2026-10-18）为「WS 活着 + HTTP 死了」盲区补的可见面，
但它把**读历史途中的一次铸票拒绝**当成了会话死亡——上滑补页渲染一出票就整页 reload、WS 状态全毁、
落回登录门 = 被迫重登。

### 修复（全部在前端 `web/js/nfTicket.js`）

- **移除铸票腿的 `noteAuthRejected` 调用**（sessionGate 的可见面+自愈 reload 只保留 12h 保活腿
  `armSessionKeepalive` —— 闲置拍点自愈，不在阅读途中杀会话）。
- **静默重试带退避**：401/403 记入指数退避（0.5s 起拍 ×2、30s 封顶），退避窗内 `mintTickets`
  直接吃缓存降级（无票 URL → 既有逐元素失败面），窗开自然重试；成功即清零；手动重铸
  (`reMintAll`) 不吃旧退避窗。

### Spec 钉（`tests/history-scroll-auth401.spec.mjs`，新增）

真实 web/ 树 + WS 脚本分页 + `/api/nf-ticket` 全程 401：
上滑补页（3→6→9 行、到头收口）＋ 铸票 401 被真实踩到（6 hit = 3 轮 × cookie+Bearer 双发）
＋ 退避后重试仍在 ＋ **零 `#stand-session-gate` 横幅、零 reload**。
绿腿 7/7 PASS；红腿（`git archive HEAD` 修前树，`NB_UX_ROOT`）**boot 后即 reload、标记被抹——缺陷在场**，
双向全绿。

现场截图：`shots/01-history-auth401/final-state.png`（401 全程拒绝下历史正常补齐的终态）。

---

## ② 图片查看器控制钮移位 — 前后对照

修前（`shots/02-image-viewer/before/dark-1-image-tab.png`，复现作者截图1）：
文件名胶囊顶部**居中**、缩放条顶右 `top:10/right:12`、层叠浮钮顶右同位 —— `zoom∩fab` 实测
**交叠 1404px²**，胶囊还压住 `.image-info` 文件名行。

修后（`shots/02-image-viewer/after/`，亮暗双主题各三张）：

- 文件名胶囊 → **顶部居左**（左缝 14px；`.canvas-header` 去掉 `translateX(-50%)`，右缘 170px 让给
  findbar；md scroller 顶距让出胶囊带，H1 不再被罩）。
- 缩放控件（±/百分比/Fit）→ **右下角** `bottom:18/right:64`（fab 左侧第一槽；HTML pane 槽位全簇
  +48：source 64 · ref 112 · find 160 · 缩放条 208，窄面板 <356px 二行回落 `@container nfpane`）。
- 层叠浮钮 → **右下角** `bottom:16/right:12`（JS `syncDeckBox` 同步式改写，CSS 底锚缺省同源）。
- 亮暗双主题四条几何钉全绿（胶囊左 1/3 内、两件底右、三件两两零交叠）。

---

## ③ 叠卡堆叠快照不可见 — 前后对照

修前（`shots/03-deck-snapshots/before/desktop-dark-3-deck-open.png`，复现作者截图2）：
面板后面两层只余暗影——深处卡透明度坡道无底（d=3→0.54、d=4→0.16、趋 0）沉入暗背景，
`.card-shade` 纵向渐变停点 0.9/0.62/0.46/0.56 近全黑，暗色骨架 mock 白度 0.20/0.11/0.06 几不可辨。

修后（`shots/03-deck-snapshots/after/`，暗亮双主题 + 擦洗中间帧/换卡帧）：

- 透明度坡道带底（`canvas.js deckPoseOf`）：d≤1 恒 1，此后每层 −0.12、**0.62 封底**
  ⇒ 1 → 0.88(d2) → 0.76(d3) → 0.64(d4+)，≥3 层逐层递暗有梯度。
- shade 渐变收幅：停点 0.40/0.28/0.14/0.22 ⇒ 深处（shade=0.30）顶缘实压 ≤0.12、内容带 ≤0.04
  —— 层次仍靠明暗差（9.19 无边语言：暗但不糊），标题栏与快照内容可辨。
- 暗色标题条/骨架对比度抬档：`--th-bg` 0.045→0.07、`--th-border` 0.06→0.09、
  `--bar-1/2/3` 0.20/0.11/0.06 → 0.30/0.17/0.10（mock 兜底面同样可辨）。

---

## 验证矩阵（全部在本树实跑）

| 验证 | 结果 |
|---|---|
| `tests/history-scroll-auth401.spec.mjs`（新增·绿腿） | 7/7 PASS |
| 同上红腿（`NB_UX_ROOT`=修前树） | 缺陷在场（boot 后 reload），抓得住 |
| `tests/imgviewer-controls-shots.mjs`（新增·本树） | 10/10 PASS（亮暗×4 几何钉） |
| 同上红腿（修前树） | 8 FAIL（zoom∩fab=1404px² 等），抓得住 |
| `tests/canvas-stack.spec.mjs` | 144/144 PASS |
| `tests/canvas-html-toolbar-overlap.spec.mjs` | 22/22 PASS（O-gap 钉改行感知） |
| `tests/canvas-cardhead-capsule.spec.mjs` | 28/28 PASS（CC0-3 钉改左锚） |
| `tests/canvas-html-zoom.spec.mjs` | 23/23 PASS |
| `tests/card-image-zoom.spec.mjs` | 54/54 PASS |
| `tests/canvas-stack-ui5-shots.mjs`（前后各一轮） | 零 JS 运行时错误，窄窗/移动层形态不回归 |
| `scripts/check-js-types.mjs` | 257 = baseline 257，零新增 |

## 改动文件

- `web/js/nfTicket.js` — ① 静默退避重试，移除登出触发
- `web/css/split.css` — ② 胶囊左锚/缩放条底右/圆钮簇 +48/fab 底锚 + ③ shade 渐变收幅、暗色对比度 token
- `web/js/canvas.js` — ② fab 钉区同步式 ③ op 坡道带底
- `web/js/viewers/{shared,markdown,html}.js` — ② JS 槽位 +48（64→112、112→160、160 缺省）
- `tests/canvas-stack.spec.mjs` · `tests/canvas-html-toolbar-overlap.spec.mjs` · `tests/canvas-cardhead-capsule.spec.mjs` — 钉随批改锚（意图不变：零交叠 + ≥8px 缝）
- `tests/history-scroll-auth401.spec.mjs` · `tests/imgviewer-controls-shots.mjs` — 新增
