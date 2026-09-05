# 好友 UI 契约对齐对照表（friend-search-contract v1.0+§8 → 客户端实现）

- 批次：friend-contract-align（好友 UI 契约对齐，作者指令 2026-09-05）
- 契约基准：`friend-search-contract.md` v1.0（冻结）+ §8 实现回写，唯一契约原文
- 基点：username-unify 分支 tip（= main 533a2a0a）；本批 diff = 上游 username-unify 未提交改动 + 本批对齐改动（宿主按 username-unify → friend-contract-align 顺序落两支，同内容 diff 自动消解）
- 行号以本 worktree（分支 friend-contract-align）最终态为准

## 一、字段硬映射（统一切换，无双写别名期 §4.7）

| 契约条目 | 客户端实现（文件:行） | 断言/测试 |
|---|---|---|
| `neblinkId` → `username`（可 null） | Scala `NeblinkModel.scala:317`（case class 字段更名）；Decoder `strOr("username","neblinkId")` `:412/:426`；null 折叠 "" | FriendApiRoutesSpec「GET /friends passes blocked flag…」: username null → `Some("")` |
| `name` → `display_name`（**字面 snake_case**，永不为 null，fallback name→username→user_id） | Decoder `displayNameOf` `NeblinkModel.scala:402-406`（镜像服务端 fallback 链后折叠 ""，单行容错不毁整表）；Encoder 字面 `"display_name"` `:463` | 同上测试： display_name null → `Some("u2")`（fallback 到 user_id）；「GET /friends proxies…」: `display_name == 林小满` + 旧 `name` 字段零残留 |
| `avatarUrl` → `avatar`（可 null） | Decoder `strOr("avatar","avatarUrl")` `:414/:428`；Encoder `"avatar"` `:464`（None → JSON null 原样透出） | 同上： `avatar` null → `Some(None)`；「GET /friends proxies…」: avatar 值透传 |
| —（新增）`relation_status` 六态 | JS 搜索结果归一透传 `friendsApi.js:126-139`（normalizeSearch，miss 不带冗余键）；mock 判定 `relationOf` `friendsApi.js:141-152` | friend-contract-align.spec M1~M7/D2/D5（六态逐项） |
| 信封字段 camelCase 维持（userId/requestId/friendshipId/conversationId/createdAt/note/eventId/type/payload） | Encoder 自定义实例仅换档案四字段，信封沿用（`NeblinkModel.scala:459-469` + deriveEncoder）；JS 列表归一保留 since/blocked `friendsApi.js:223-238` | 「GET /friends/requests normalizes…」: requestId/note/createdAt 三断言 |
| 切换面：GET /api/friends 内嵌 profile | 网关再编码经 `Encoder[FriendSummary]` `NeblinkModel.scala:459-469`；JS 直连链 personFromWire 归一 `friendsApi.js:116-123,223-238` | FriendApiRoutesSpec friends 行三断言（username/display_name/avatar + 旧字段零残留） |
| 切换面：GET /api/conversations 内嵌 friend | 同上（ConversationSummary deriveEncoder 复用 FriendSummary Encoder）；JS `friendsApi.js:281-287` | FriendApiRoutesSpec「GET /conversations…」friend 三断言 |
| 切换面：WS friend_request.from / friend_accepted.friend / message_new.sender（四字段卡） | 网关 FriendService 事件原样中继（`FriendService.scala:62`，不解码档案对象）→ 零改动天然带新卡；web 只读信封（contacts.js:414、messages.js:597-627 的 senderId/userId） | 无需新断言（原始中继由 neblink-server 侧 friends_test 钉死；web 消费面不受字段切换影响） |
| 明确不动：GET /api/user/profile、EnrollResponse.github_username、消息/会话/已读端点、RestApiRoutes A2A 代理块 | 本批零改动（RestApiRoutes.scala / FriendService.scala / NeblinkClient.scala 未触碰） | — |

## 二、端点切换

| 契约条目 | 客户端实现（文件:行） | 断言/测试 |
|---|---|---|
| 搜索 = `GET /api/users/search?q=`（双键 username/email NOCASE 精确） | `friendsApi.js:197-221`（searchUser：直连 hit `/api/users/search`；mock 双键匹配 `matchUser` `:108-112`） | friend-contract-align.spec D1（端点钉死：调旧 lookup 则拦截不命中必红）+ M1/M7（username/email 双键） |
| 空白 q ⇒ `{"found":false}`；miss 恒 `{"found":false}`（防枚举，两维度无差别） | 直连 normalizeSearch `friendsApi.js:126-139`（found!==true ⇒ 恒 `{found:false}`）；mock `friendsApi.js:204-205`（空白/miss 同形） | M8 / D3 |
| >256 字符 ⇒ 422 invalid_query；429 rate_limited | 直连走 req() 错误面（err.status/err.data）；调用方 contacts.js:270-271 catch ⇒ 未找到卡兜底；mock `friendsApi.js:202`（422） | D4（422 → 未找到卡） |
| Username 设置 = `PUT /api/users/me/username`（422/409/503/404/401/403，§8.2 M2M 映射） | **客户端无设置入口（10:54 裁定，见三）**——不发该请求；错误映射由 neblink-server 侧实现与测试承载 | D6/D7（无调用 + 无入口） |
| available = `GET /api/users/me/username/available?q=` | 客户端无调用方（原 NL 号自定义 UI 已移除） | D6 |
| `POST /api/friends/requests` 形状不变、query 双键语义 | `friendsApi.js:244-249`（端点/形状不动，注释钉语义）；mock `:250-258` | M10b（wire query=username）+ friend-chain-ui.spec T2 系列 |
| **旧端点同 release 移除**（GET /api/users/lookup、PUT neblink-id、GET neblink-id/available——客户端不得再调用） | `friendsApi.js:362-364`（setNeblinkId/neblinkIdAvailable 删除 + 移除声明）；lookupUser → searchUser 改名（无别名残留，全仓 grep 零命中） | D6（全程请求扫描零命中）+ U8/上游 spec 路由改挂 /users/search |
| 联调依赖 = 服务端部署窗口（作者控制，与 beta.55 绑定；§4.7 窗口期空列表/搜索 404 属预期） | friendsApi.js 头注 `:1-17`；contacts.js:271 catch 兜底注释 | 本条为标注项（无自动化断言） |

## 三、两项专项裁定

| 裁定 | 实施 | 断言/测试 |
|---|---|---|
| 设置「修改 NL 号」入口移除（10:54 裁定；客户端不提供修改入口，官网改 Username；contacts 列表展示 username 保持） | neblink.js：NL 号自定义 UI 整体移除（状态/防抖检测/保存 `:40-46` 声明、模板 `:249-251`、事件绑定 `:474`）；locale 死键 neblink.nlId* ×12 双语删除；friendsApi setNeblinkId/neblinkIdAvailable 删除；sidebar.js **零改动**（仅嵌入 neblinkSettingsHTML() 输出，移除后天然收敛） | friend-chain-ui.spec T5（zh 入口零残留 + 设备区完好）、T7b（en 同）；friend-contract-align.spec D7 |
| mock→直连：直连为默认，保留 mock 开关；mock 数据按新字段/新端点实现 | friendsApi.js MOCK 开关机制原样（`fm_api_mock`/`?fmMock`，默认直连 `:20-27`）；mock 搜索/目录/六态按契约实现（`normalizeSearch`/`relationOf`/双键 matchUser）；seed 默认用户即新形态 | M1~M10（mock 全绿）+ D1~D7（直连全绿）双模式 |

## 四、relation_status 六态 → UI 按钮态（契约 §4.1 逐态落 UI）

| 值 | 契约客户端动作 | 实现（contacts.js buildResultCard） | 断言/测试 |
|---|---|---|---|
| `self` | 禁用添加 | `:311-314` → 「这是你自己」文案，无按钮 | M6 |
| `addable` | 显示「添加」 | 默认兜底 `:376-407` → fm-add-btn → 微信式验证消息流（≤50 字可选，Enter 直发） | M1 / M10 / D1 |
| `already_friends` | 显示「发消息」 | `:316-326` → fm-msg-btn → openChatWithFriend（好友行同链路） | M2 / D2 |
| `outgoing_pending` | 显示「等待对方处理」 | `:328-332` → contacts.outgoingPending（sentTo 乐观回显并入同态） | M3 / D5 |
| `incoming_pending` | 显示「回应请求」（accept/decline） | `:334-360` → 回应请求文案 + fm-req-accept/fm-req-decline（requestId 取自已加载请求列表；列表落后时 refresh 后按钮态回归） | M4a/M4b |
| `blocked_by_me` | 禁用添加 + 「取消拉黑」入口 | `:362-375` → 已拉黑标记 + fm-unblock-btn（unblock 后清本地 blocked 镜像 + refresh），无添加钮 | M5 |

## 五、Scala 侧（网关解码/出参契约同步）

| 项 | 实现 | 断言/测试 |
|---|---|---|
| FriendSummary 档案字段契约化 | `NeblinkModel.scala:317-324`（username/displayName/avatar 字段更名，since/blocked 信封保持） | `sbt testOnly nebflow.gateway.FriendApiRoutesSpec nebflow.neblink.FriendMessageToolSpec` |
| display_name 必填语义（永不为 null） | Decoder `displayNameOf` `:402-406`：display_name → name（窗口期）→ username → neblinkId → userId → ""（镜像服务端链 + 单行容错不毁整表，2026-09-04 审计口径保留） | FriendApiRoutesSpec「passes blocked flag and tolerates null profile fields」：null display_name → "u2" |
| 出参契约切换（snake_case 四字段 + 信封 camelCase） | 自定义 `Encoder[FriendSummary]` `:459-469`（字面 "display_name"；Json.obj 显式字段序）——/api/friends、/api/conversations 再编码与 WS 中继消费面统一 | 「GET /friends proxies…」+「GET /conversations…」全字段断言 + 旧 name/avatarUrl 零残留断言 |
| 窗口期容错（§4.7 非别名期） | Decoder strOr 旧字段仅作 fallback（契约字段缺席时），注释钉明「非双写别名期」 | flat/nested 双 Decoder 均走 strOr（flatFriendSummary :409-417） |
| Agent 工具语义同步（编译连带） | FriendMessageTool.scala：resolveFriend 按 username/displayName（值域不变，仅字段更名）+ 文案 "use the exact username"；FriendMessageToolSpec 同步 | FriendMessageToolSpec 全绿 |

## 六、落库与合并（宿主执行，本节点禁自行 merge/push）

1. **先落 username-unify**（其自身申报命令——上游交付 = 该 worktree 未提交改动）
2. **再在 friend-contract-align worktree commit**（上游 + 对齐两批改动）：
   ```
   cd /Users/dev/Claude\ code/Nebflow/.nebflow/worktrees/friend-contract-align
   git add src/main/resources/web/js/friendsApi.js src/main/resources/web/js/contacts.js \
     src/main/resources/web/js/neblink.js src/main/resources/web/js/locales/en.js \
     src/main/resources/web/js/locales/zh-CN.js src/main/scala/nebflow/neblink/NeblinkModel.scala \
     src/main/scala/nebflow/core/tools/FriendMessageTool.scala \
     src/test/scala/nebflow/gateway/FriendApiRoutesSpec.scala \
     src/test/scala/nebflow/neblink/FriendMessageToolSpec.scala \
     tests/friends-username-unify.spec.mjs tests/friend-chain-ui.spec.mjs \
     tests/friend-contract-align.spec.mjs .gitignore .nebflow/Spec/friend-ui-contract-alignment.md
   git commit -m "friend-contract-align: 好友 UI 契约对齐 v1.0+§8（含 username-unify 上游改动）
   - 端点切换：lookup→/api/users/search；旧端点（lookup/neblink-id 设置链）客户端调用全摘除
   - 字段切换：FriendSummary 契约化（username/display_name/avatar snake_case + 信封 camelCase），Encoder/Decoder 同步
   - relation_status 六态→按钮态逐项落 UI（§4.1）；locales 四新键双语 parity，死键清理
   - 10:54 裁定：设置面板 NL 号修改入口移除（neblink.js UI/绑定/死键/friendsApi 旧函数）
   - mock→直连默认保持，mock 按契约实现；tests/friend-contract-align.spec.mjs 双模式验收"
   ```
3. **merge 顺序**：username-unify 先进 main → friend-contract-align 再并（上游部分同内容 diff 自动消解）；.gitignore hunk 与 memory-plan/merge-node/dispatcher-ctx 三批同文件交叠——每批各自基于 main 重放本批三行配方（`.nebflow/` → `.nebflow/*` + `!.nebflow/Spec/` + 注释行）
4. **网关侧待办（非本批范围，登记）**：RestApiRoutes A2A 代理块为 friend-chain-ui 交付物未动——`/users/search`、`/users/me/username(+available)` 的网关代理路由需后续批补齐；窗口期直连搜索 404 → 未找到卡兜底（§4.7 预期，非回归）

## 七、验收记录

- JS：`node tests/friend-contract-align.spec.mjs`（mock+直连双模式）＝全绿；`node tests/friends-username-unify.spec.mjs`（上游，U3a 文案随契约更新、U8 路由随端点切换）＝全绿；`node tests/friend-chain-ui.spec.mjs`（T2e 文案、T5/T7b 重写为移除钉死）＝全绿
- Scala：`sbt testOnly nebflow.gateway.FriendApiRoutesSpec nebflow.neblink.FriendMessageToolSpec`（/tmp 私有缓存，禁 sbt run）＝全绿
- 进程纪律：两个 spec 各自内置静态服务器随机端口 + finally 关闭；无残留进程
