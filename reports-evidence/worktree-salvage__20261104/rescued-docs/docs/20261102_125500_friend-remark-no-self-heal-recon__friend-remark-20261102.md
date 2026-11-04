---
type: recon
status: complete
date: 2026-11-02
scope: friend-remark
supersedes: 旧结论「备注未上云 / 唯一缺陷=会话签名闸」
---

# 好友备注「设了又不显示」— 根因侦察（无自愈腿，2026-11-02）

**证据与探针**：`evidence/20261102_friend-remark/RECON2.md` + `recon2-*.mjs` + `shots/`（同仓，可独立复跑）。旧档 `RECON.md` 的结论两处撤回，见 §1。

相关背景档：[[20261031_084300_social-file-outbound-audit__delegate-nebflow-3001078c]]、[[20261030_084500_dock-message-badge__delegate-nebflow-12dfb591]]。

---

## 0. 一句话根因

**数据与取名表达式都是对的（实测 9 行里 8 行带 `remark`，联系人主行当场显示 `张安齐`）；真正的缺口是「没有任何一条自愈腿」——备注变更只能靠**一次全新渲染**才能到达屏幕，而三个渲染面各有一处让新值到不了的死区：**

1. **联系人主行**：`contacts.js` **只派发** `fm-remark-changed`（`:706`）、**从不监听**它（全仓唯一监听在 `messages.js:5337`）⇒ 备注变了联系人行不动；且无轮询、无 TTL 回源 ⇒ 页面早于写入那一刻加载的，就**永久**停在显示名。
2. **会话列表行**：`messages.js:571-584 rosterSignature` 的判定字段里**没有 `remark`** ⇒ 备注到达但 `conversationId / lastMessage.id / unreadCount / memberCount` 不变时，`renderListIfChanged`（`:587-590`）判「没变」⇒ **零 DOM 操作**，行停在旧名。实测已造成**永久分歧**（§3 G2）。
3. **搜索命中卡**：`contacts.js:900` 走 `r.displayName || r.username`，上游 `/api/users/search` **根本不注入 `remark`** ⇒ 这一面结构性地永远看不到备注。

作者那张照片（412×102，深色，大头像 + 大字「安之则」+ 小字「anzhize」无 `@` 前缀）就是第 1 条的死区产物：那一拍页面上的好友快照没有该条 remark，而它之后再也没有重取的理由。

---

## 1. 撤回旧结论（`RECON.md` 的两处）

| 旧档说法 | 处置 | 依据 |
|---|---|---|
| 「Q2 上不上云 —— **不上云**……KAI 数据根无该文件」 | **判定方向不变，但不得用来解释本次报障** | 作者原话「本机也无法显示」，与跨设备无关。旧档把「另一台设备没有数据」当成噪声排除掉了，本次必须反过来：**本机有数据、本机界面当时不显示**。 |
| （隐含）「Q3 = 会话列表签名闸是**唯一**缺陷」 | **撤回「唯一」** | 现在测出的是**三处**独立死区（联系人行无自愈 / 会话行签名缺字段 / 搜索卡上游无值），且**联系人行那条才是作者照片的归属面**（§4 几何判据）。 |

**新增的可判事实（推翻旧档的时间线假设）**：`/Users/kaiyu/.stand/sessions/d7c3cb54-.../compaction/20261101-115102-before.json` 里已含 `安之则 () [remark: 张安齐]` ⇒ **8 条备注早在 11-01 11:51 就全在了**。11-02 11:17:55 那次 `friend-remarks.json` 全量重写**不是**「张安齐」的首次创建，而是任意一次 `setRemark` 触发的整表 `os.write.over`。所以「备注比渲染晚」这条解释**不成立**，必须换成「渲染之后没有重取」。

---

## 2. 判死 / 判活清单（每条带实测读数）

### 2.1 判死：多进程 `remarkRef` 分歧（任务 2a）

- `ps` / `lsof`：**:6138 = PID 30450**，`--home /Users/kaiyu/.stand`（sbt 前台，`.pid.meta` `startedAt 2026-11-01T05:23:19Z`，**至今未重启**）。
- **:61159 = PID 30605**，`--daemon-child`，父进程 30450 —— 挂的是 `PerceptionDaemonRoutes`（`PerceptionDaemonMain.scala:165-196`），对 `/`、`/js/*`、`/api/friends`、`/api/health` 一律 **403**。它**没有**好友/备注功能。
- 其余端口（53949、57177、8122、18093、8291/8292/8299-8304、18471、8199）是无关的临时主目录/冒烟环境，Bearer 请求同样 401/403。
- 单实例证据：`grep -rn "friendService(" src/main/scala` → 只有 `GatewayMain.scala:1423` 是真构造；`:1664` 是 `NeblinkWiring.sharedResourcesSlot(neblinkClient, friendService)`，复用同一实例。`remarkRef` 声明 `FriendService.scala:103`，唯一构造 `NeblinkWiring.scala:87`。
- 6138 实测：`GET /api/friends` → 9 个好友、**8 个带 remark**、`1j79gyb2fmas.remark = "张安齐"`。**作者 UI 走 6138 ⇒ 该假设判死。**
- boot 只读一次确认：`GatewayMain.scala:1412-1422`（`FriendRemarkStore.load` + `handleErrorWith` → 空 map + WARN）；唯一写路径 `PUT /api/friends/{uid}/remark` → `SocialRoutes.scala:299-310` → `FriendService.setRemark:160-168` → `FriendRemarkStore.save`（`NeblinkModel.scala:1378-1381`）。**无 mtime 回源、无跨进程失效**——但这在本机无第二个消费进程，故不构成本次症状。

### 2.2 判死：前端快照 / 缓存分量（任务 2b）

| 项 | 读数 | 结论 |
|---|---|---|
| IDB 首帧快照（`contacts.js:229-238 seedFriendsFromStore`） | IDB `nebflow-local / roster`，`k='friends'` 载荷**含** `remark:"张安齐"`（9 行 / 8 行有 remark）；`k='conversations'` 的 `friend` 同样含 | **判死**，快照腿不可能造出无备注的行 |
| SW 跑旧版 `contacts.js`（`web/sw.js`） | `/js/contacts.js` 与工作区源码**逐字同**（唯一差异是与取名无关的 `submitSearch` 重构，`:760-798`）；`:600` 两侧都是 `f.remark \|\| f.name \|\| f.neblinkId`。响应头 `Cache-Control: no-cache`、`Last-Modified: 2026-11-01T05:23:18Z` | **判死**。且 `/api`、`/ws`、`/uploads` **不在 SW 范围内**（fetch handler 直接 return） |
| `web/js/messages/friendCopy.js` | 线上与源码完全一致；`:19-22 personLabel = remark \|\| name \|\| neblinkId` | 判死 |
| 清 localStorage + 全 IDB 后冷启 | `张安齐`（`shots/D-contacts-after-wipe-dark.png`） | 判死 |

### 2.3 判死：孤儿闭包 / 编辑器竞态（`contacts.js:651 / :699-707`）

`recon2-orphan.mjs`：编辑器开着时 refresh 会**销毁编辑器** ⇒ 提交返回 `NO-INPUT`，物理上到不了「写进孤儿对象」那条路。
`recon2-editor.mjs`（**走真 UI 入口**：右键 → 「设置备注」→ 打字 → Enter）：
```
e1_open.clickedMenuLabel = "设置备注", editor = true
e1_commit = SUBMITTED → e1_row_after_commit = "张安齐E1", gateway = "张安齐E1"
e3_editor_alive_after_refresh = false   (refresh 直接销毁编辑器)
e3_commit = SUBMITTED → e3_row_after_commit = "张安齐E3", gateway = "张安齐E3"
e5_row_after_next_refresh = "张安齐E3"  (下一次刷新不回退)
pageErrors = []
```
⇒ **真入口提交是即时生效且不回退的。判死。**

### 2.4 判死：真实速度下的读取竞态

`recon2-realrace.mjs`（5 次，真 GET 与真 PUT 交错，零注入）：**5/5 次 GET 交付的都是新值**，行始终 `张安齐`，`diskRestored: true`。
⇒ 真实速度下不存在「刷新在途读到旧值」。`recon2-race.mjs`（注入 3 s 延迟）能复现回退，但那是装置产物，**不当作根因**。

### 2.5 判死：搜索命中卡为作者照片来源

`contacts.js:903` 渲染 `@${r.username}`，**带 `@` 前缀**；作者照片小字是 `anzhize`（**无 `@`**，已放大核验）。
另：会话列表行**无小字行**（`hasSub = false`）。⇒ 排除这两个面。

---

## 3. 判活：两条可复现缺口（本节点的硬读数）

### G1 — 联系人行不订阅 `fm-remark-changed`（无自愈腿）

静态：`contacts.js` 里 `addEventListener('fm-remark-changed')` 命中数 = **0**；`messages.js` = **1**（`messages.js:5337`）。`contacts.js:706` 只 `dispatchEvent`，不监听。

动态（`recon2-gaps.mjs`）：
```
baseline_row                        = "张安齐"
派发 fm-remark-changed(remark=张安齐G1) 后
  → after_remark_changed_row        = "张安齐"      ← 行毫无反应
  → fetch_friends_delta             = 0             ← 也没有重取
派发 fm-friends-changed（对照，contacts.js:1270 确实订阅）
  → friends_changed_fetch_delta     = 2             ← 真重取 2 次
  → after_friends_changed_row       = "张安齐"      ← 回到磁盘真值
```
`recon2-triggers.mjs` 的 fetch 计数补充：初始 2 次、面板切换 +1、**空闲 25 s delta = 0**、`fm-remark-changed` delta = **0**、`fm-friends-changed` **+2**。
`recon2-trace.mjs` / `recon2-attrib.mjs`（进入页面后 60 s / 30 s 空闲，期间外层 PUT 改值）：**空 trace、fetch 为零、行文本从未变化**。

⇒ **判活**：备注变更信号到不了联系人行，且没有轮询 / TTL 回源。只要页面渲染那一刻的快照不含该 remark，该行就**永久**停在显示名。这就是作者照片的机制（§4 把归属面钉在 `.fm-friend-row`）。

### G2 — 会话列表签名闸不含 `remark`（会造成永久分歧）

`recon2-siggate.mjs`（他处改备注 → 派发真事件 → 页面自己发真 GET）：
```
1_baseline_conv_row        = "张安齐"
2_put_status               = 200
3_conv_gets_after_event    = [{ friendRemark: "张安齐SIG", lastMsgId: 263 }]   ← GET 拿到了新值
4_gateway_remark           = "张安齐SIG"
5_conv_row_after_refresh   = "张安齐"        ← ★ 会话行没变
6_contact_row_after_refresh= "张安齐SIG"     ← 联系人行变了（同一拍）
7_conv_row_after_open_other= "张安齐SIG"     ← 打破签名（openConvId 变）后立刻正确
8_restored_gateway         = "张安齐"
diskRestored               = true
```
源码判据（线上 `/js/messages.js` 实取）：
```js
function rosterSignature(list) {          // messages.js:571-584
  parts.push(c.conversationId, (c.lastMessage && c.lastMessage.id) || '',
             Number(c.unreadCount) || 0, Number(c.memberCount) || 0);
  parts.push(`open:${openConvId || ''}`);
  return signatureOf(parts);              // ← 无 remark
}
function renderListIfChanged() {          // messages.js:587-590
  if (rosterSignature(conversations) === renderedListSig) return;  // ← 零 DOM 操作
  renderList();
}
```
⇒ **判活，且已实测造成"网关真值 = 新名、屏幕上 = 旧名"的持久分歧**，直到有真消息改变 `lastMessage.id` 或用户切换会话。

---

## 4. 作者照片的归属面（几何判据）

作者实拍：412×102，背景 `rgb(22,26,35)`，头像圆 ≈ **73-74 px**，大字「安之则」外接框 **60×24**，小字「anzhize」外接框 **63×18**，**小字无 `@` 前缀**。原图存 `shots/AUTHOR-row.jpg` / `shots/AUTHOR-row-3x.png`。

同 DPR(=2) 下在真实页面截同口径的行（`recon2-rowshot.mjs` → `shots/rows/`）：

| 候选面 | 渲染处 | 行尺寸 | 头像 | 小字 | 小字前缀 | 吃 remark |
|---|---|---|---|---|---|---|
| `.fm-friend-row` 联系人好友行 | `contacts.js:591-641`（`:600` 取名 / `:601` 小字） | 218×50（2x 裁图 556×124） | 36px | `neblinkId` | 无 `@` ✅ 与照片同 | ✅ `f.remark \|\| f.name \|\| f.neblinkId` |
| `.fm-pick-row` 建群多选行 | `friendGroups.js:329-332`（`:331`） | 376×50（2x 裁图 832×124） | 36px | `neblinkId` | 无 `@` ✅ | ✅ `f.remark \|\| f.name \|\| f.neblinkId` |
| `.fm-req-row` 好友请求行 | `contacts.js:1121-1137`（`:1126` / `:1128`） | 同族 | 36px | `note \|\| neblinkId` | 无 `@` | ❌ 上游不注入 |
| `.fm-result-card` 搜索命中卡 | `contacts.js:897-903`（`:900` / `:903`） | — | 40px | `@username` | **有 `@`** ❌ 排除 | ❌ 上游不注入 |
| 会话列表行 `.fm-conv-row` | `messages.js:678` → `friendCopy.js:21` | 218×54 | 40px | **无小字** ❌ 排除 | — | ✅（但受 G2 门控） |

**裁定**：照片归属 **`.fm-friend-row`**（头像 36px + 小字 `neblinkId` 无 `@` + 联系人页上下文），即 `contacts.js:600`。该行当前正确显示 `张安齐`（`shots/A-contacts-cold-dark.png`）——所以照片来自**当时那一拍**，不是当前。
不排除 `.fm-pick-row`（几何同族），但两者取值表达式**完全一致**，修复点相同，归属差异不影响下游改动。

---

## 5. 逐行显示面清点（HEAD `39e925e72`，行号已复核）

| 面 | 渲染函数 | 取名表达式 | 吃 remark | 改前读数 | 风险 |
|---|---|---|---|---|---|
| 联系人主行 | `contacts.js:591 friendRow` → `:600` | `f.remark \|\| f.name \|\| f.neblinkId` | ✅ | `张安齐` | **G1**：不监听 `fm-remark-changed`、无轮询、无 TTL ⇒ 快照缺值则永久停显示名 |
| 联系人主行小字 | `contacts.js:601` | `f.neblinkId` | ➖ 设计如此 | `anzhize` | 无 |
| 拉黑镜像行 | `contacts.js:737` 缓存 push → `:181-186` 合并 → `:600` 渲染 | 封禁时快照 `remark: f.remark \|\| null` | ⚠️ 构造时快照 | — | 封禁期间的备注编辑**永不进入**镜像行 |
| 搜索命中卡 | `contacts.js:897-903` → `:900` | `r.displayName \|\| r.username`；小字 `:903` `@username` | ❌ | `安之则` / `@anzhize` | 上游 `/api/users/search` 无 remark ⇒ 结构性无解 |
| 好友请求行 | `contacts.js:1121-1137` → `:1126` | `person?.name \|\| person?.neblinkId` | ❌ | — | 转好友后才有备注；加好友前显示名属合理 |
| 群邀请行 | `contacts.js:1180-1200` → `:1197` | 群名 | n/a | — | — |
| 建群/邀请多选行 | `friendGroups.js:329-332` → `:331` | `f.remark \|\| f.name \|\| f.neblinkId` | ✅ | `张安齐` | 每次打开重取，天然新鲜 |
| 会话列表行 | `messages.js:678` → `friendCopy.js:19-22 personLabel` | `remark \|\| name \|\| neblinkId` | ✅ 表达式 | `张安齐` | **G2**：签名闸（`:571-584`）不含 remark ⇒ 零 DOM 操作 |
| 会话窗头 | `messages.js:1029-1036 convTitleLabel` → `:1194` / `updateModalTitle :1605-1610` | `personLabel(conv.friend)` | ✅ | `张安齐`（`shots/E-chat-header-dark.png`） | 只有新窗口或 `fm-remark-changed` 才重绘 |
| 转发目标选择器 | `messages.js:4125` | `label: convTitleLabel(c)` | ✅ | — | 实时计算 |
| 转发行 / 芯片 | `messages.js:4242-4247 / :4257 / :4272` | `convTitleLabel` | ✅ | — | 实时计算 |
| 群气泡发送者名 | `groupSenderNameOf messages.js:1062-1067` → `api.friendRemarkOf`（`friendsApi.js:579-584`）；重绘 `:1074-1082` | `friendRemarkOf(senderId) \|\| roster name` | ✅ | — | 依赖好友快照已装载 |
| 群成员抽屉行 | `friendGroups.js:282 groupMemberLabel` → `:725`；重绘 `repaintGroupMemberLabels :292-297` | `friendRemarkOf(...) \|\| name \|\| userId` | ✅ | — | 全量幂等重绘（`messages.js:556 / :5354`） |
| 移动端联系人/消息页 | `mobile/socialFaces.js adoptPanelFace` | 收编桌面 `#fm-contacts-body` / `#fm-conversations`，零自研 | 继承 | — | 继承 G1/G2 |
| 移动端会话搜索标签 | `mobile/socialSearch.js:302-306` | `personLabel(conv.friend)` | ✅ | — | 实时计算 |
| `ListFriends` 工具面 | `FriendRoster.scala:55-59 candidateLine` | `"displayName (username) [remark: r]"` | ✅ 追加式 | `安之则 (anzhize) [remark: 张安齐]` | 符合设计（备注与显示名可区分） |
| 单聊资料页 | — | — | — | **不存在**（`.fm-modal-name` 只在群聊分支内） | — |

---

## 6. 给下游修复节点的改动点清单（file:line 级）

**P0-A（G1，作者照片那条）— `contacts.js`**
1. 在 `contacts.js:1266-1272` 的 `fm-friends-changed` 监听旁，**新增** `fm-remark-changed` 监听：`detail.userId` 命中当前 `friends` 里的行 ⇒ 就地改 `f.remark = detail.remark` + `render()`（或只重画该行）。不重取网络（与 `messages.js:5337` 同口径）。
2. 在 `contacts.js:706 persistRemark` 的 `render()` 之后不需要额外动作（本面已局部生效），但**必须**保证第 1 条落地，否则「他处改备注」到不了联系人行。
3. 可选自愈腿：`contacts.js:163 doRefresh` 前给 `friends` 快照加一个 TTL（如 `friendsFetchedAt` 超 60 s 则后台补取一次），或监听 `fm-wake`（`messages.js:5321` 已有）触发一次 refresh。二者取其一即可关闭「永久停旧名」窗口。

**P0-B（G2）— `messages.js:571-584`**
4. `rosterSignature` 的 `parts.push` 增加 `c.friend && (c.friend.remark || '')`（或整个 `personLabel(c.friend)`）。一行改动，签名即随备注变化 ⇒ `renderListIfChanged` 正常重建。
5. 落实后回归：`messages.js:587-590` 的「零 DOM 操作」断言需同步更新测试期望（备注变更**应当**重建）。

**P1（结构性）— 搜索命中卡**
6. `contacts.js:900` 改 `r.remark || r.displayName || r.username`，同时服务端 `/api/users/search` 出参加 `remark`（`SocialRoutes.scala` 搜索分支，需按 `remarkRef` 注入）。**注意**：备注是「好友属性」，非好友不该有值 ⇒ 服务端只在命中好友时注入。

**P2（一致性）— 拉黑镜像**
7. `contacts.js:737` 的 `remark: f.remark || null` 是构造时快照 ⇒ 镜像行在封禁期间不吃备注更新。最小修法：渲染时若 `friends` 里有同 `userId` 的行，取它的 `remark`；或让 G1 的监听同时更新 `blockedCache`。

**不需要改的（已验证正确）**：`FriendService.scala:134-141 applyRemark/applyRemarks`、`:148-150 applyRemarksToConvs`、注入点 `:912-915` / `:924-926` / `:492-496`、`FriendRemarkStore`（`NeblinkModel.scala:1367-1382`，`load :1372` / `save :1378`）、`friendsApi.js:329-333 personFromWire`、`friendCopy.js:19-22 personLabel`、`contacts.js:600`、`friendGroups.js:331`。

---

## 7. 复跑方法（每个脚本独立 `node` 可跑）

```bash
cd /Users/kaiyu/Claude\ code/Stand/.nebflow/worktrees/delegate-nebflow-991f7f0f
node evidence/20261102_friend-remark/recon2-live.mjs          # 真实 UI 冷启/刷新/清库/面板切换/聊天头
node evidence/20261102_friend-remark/recon2-gaps.mjs          # G1 静态+动态 / G2 静态判据
node evidence/20261102_friend-remark/recon2-siggate.mjs       # G2 永久分歧（决定性）
node evidence/20261102_friend-remark/recon2-editor.mjs        # 真 UI 入口提交（判死孤儿闭包）
node evidence/20261102_friend-remark/recon2-realrace.mjs      # 真实速度竞态（判死）
node evidence/20261102_friend-remark/recon2-triggers.mjs      # fetch 计数（判死轮询/自愈）
node evidence/20261102_friend-remark/recon2-stores.mjs        # IDB 快照含 remark
node evidence/20261102_friend-remark/recon2-geometry.mjs      # 各面几何
node evidence/20261102_friend-remark/recon2-rowshot.mjs       # shots/rows/ 2x 逐行截图
```

**截图存证**（`evidence/20261102_friend-remark/shots/`，全部深色）：
`A-contacts-cold-dark.png`（冷启联系人，`张安齐`）、`B-messages-dark.png`（面板切换）、`D-contacts-after-wipe-dark.png`（清 localStorage+IDB 后）、`E-chat-header-dark.png`（聊天头）、`F-counterfactual-remark-null-dark.png` / `G-counterfactual-after-toggle-dark.png`（强制 `remark:null` 反事实）、`H-race-final-dark.png`、`I-aliasing-dark.png`、`J-multitab-A-dark.png`、`K-orphan-before-commit-dark.png` / `L-orphan-after-idle-dark.png`（孤儿闭包判死）、`M-pick-row-dark.png`、`N-editor-commit-dark.png`（真 UI 入口提交）、`O-siggate-dark.png` / `O-siggate-after-break-dark.png`（G2 永久分歧 → 打破签名后恢复）、`AUTHOR-row.jpg` / `AUTHOR-row-3x.png`（作者实拍原图）、`rows/01-contacts-friend-row.png` / `rows/02-pick-row.png`（2x 同口径行截图）。

**前置**：`/opt/homebrew/lib/node_modules/playwright/index.mjs`（已装）、`/Users/kaiyu/.stand/auth.json`（文件令牌）。登录走 `POST /api/auth/console-ticket` → `GET /?ticket=<t>` 换 HttpOnly `stand_session` cookie（**普通 Bearer 头对页面导航无效**）。**必须 `page.goto(GW + '/')` 而不是 `page.reload()`**——reload 会重放已消费的 ticket 得 `invalid-ticket`。

**副作用声明**：探针用可逆值写 `friend-remarks.json`，每次结束回滚并核对（`diskRestored: true`）。**当前该文件 mtime = 2026-11-02 12:53**（不是 11:17）。内容已核实：8 条、`1j79gyb2fmas = "张安齐"`；网关 `GET /api/friends` 9 行 8 条带 remark、该行 `remark = "张安齐"`。

---

## 8. 未决 / 不可判（诚实边界）

1. **11:38 那一拍 gateway 为什么没带 remark —— 日志侧不可判**。无 HTTP access log，网关自 11-01 05:23 启动至今未重启，`friend-remarks.json` 的 11:17 mtime 只能证明「写过」，不能证明「写之前那一拍 GET 的内容」。**但下游不需要这个答案**：G1/G2 已从代码面证明「一旦错过一拍就永久错过」。
2. **照片在 `.fm-friend-row` 与 `.fm-pick-row` 之间不能排他**（几何同族、小字同形）。两者取值表达式逐字相同，修复点一致，**不阻塞**。
3. **跨标签页同步未作为事实**：`recon2-multitab.mjs` 在一次序列里 A 更新成功，但 `recon2-attrib.mjs` 两种设置都无法复现（零事件 trace）。按「交叉验证优先」取零 trace 为真。**不过 G2 的 siggate 结果与此无关**（那条走的是页面自己发的真 GET + 签名闸，可稳定复现）。
4. **`remark: null` 的合法来源未定位**：可能是页面加载早于 11:17 写入，也可能是当时 upstream 慢/失败后 keep-last-known。两种都落进 G1 的死区，机制相同。
