# 修复批 REPORT · 智能体名称/头像跨机同步 + 改名落到智能体自我认知

批次：delegate-nebflow-c2b7661f · 2026-10-23 17:56
分支：`delegate/delegate-nebflow-c2b7661f`（独立 worktree）
作者令（2026-10-23 16:54 逐字）：「现在我给 agent 设置的名称和头像还是没有跨机同步」
配套令：「对名称的修改要落实到智能体的记忆里，让他知道自己的头像和名字」

---

## 1. 一行结论

跨机不同步的根因 = 展示面是**每引擎实例各自的本地盘**（多机各跑一个引擎，A 机写入 B 机永不可见）+ 前端**本地键永胜**（同引擎多浏览器形态下旧 localStorage 值遮住服务端新值）；修复 = 展示面升级为 **LWW 可收敛状态**（`identityUpdatedAt` 戳 + 内容 sha256），经**既有设备数据通道**（presence sendData / relay Notify 双腿）写后外推 + peer 变化对账推，前端改 **sha 对账收敛**；改名/换头像同步落到智能体自我认知的三层既有设施（Soul.md 自我认知段 / `## Self` 提示词段 / identity 变更提醒）。

## 2. 考古：为什么上一轮（2026-10-21 诊断批症状⑧）没生效

上一批建立了引擎 display 面（`PUT/GET/DELETE /api/agents/:name/display-name|avatar`，写 `agents/<name>/agent.json` + `agents/<name>/avatar`）与前端「本地优先 + 缺键回填」。它解决的是**同引擎**的持久化；两类缺口原样保留（`agentProfile.js` 头注自陈的 v1 已知限制）：

| # | 缺口 | 形态 | 证据锚点 |
|---|------|------|---------|
| R1 | **多机 = 多引擎**：Mac 主力 + DESKTOP-IJBBEOT 各跑一个引擎实例、各一个数据根（`devices.json` 配对、relay 注册均为**两实例**拓扑，见 `reports/20261022_194019_multi-device-compute-design` §3 现状表） | A 机面板写入落 A 机引擎盘；B 机引擎的 `agents/Nebula/agent.json` 从未变化 | `PresenceRoutes.scala` display face（写本机 `PathUtil.dataRoot/agents`） |
| R2 | **前端本地键永胜**：`paintSlot` 读 localStorage 命中即渲染，只有**缺键**才向引擎回填 ⇒ 他端/他浏览器写入（甚至清除）永远被旧本地值遮住 | 换头像/改名的另一端永远显示旧值；reset 也收敛不了 | `agentProfile.js` 旧 `pullAvatarIfMissing`（仅缺键拉取）；旧 DELETE 路由注释「另一端本地键仍优先——v1 已知限制」 |

## 3. 修复（引擎侧）

| 面 | 改动 | 锚点 |
|---|------|------|
| 落盘单点 + LWW 戳 | 新件 `AgentIdentityStore`：displayName / avatar 字节 / `identityUpdatedAt` 戳的读写单点（根名 `name` 键补底纪律逐字保留；旧 PUT 路由体收拢进来，行为逐字保持 + 加戳）；`readRec` 现读三面；**applyRemote** LWW 收敛（远端戳严格更旧 ⇒ 丢弃；等/新 ⇒ 全量覆盖并落远端戳；displayName=None = 摘键、avatar=None = 清文件的 total-state 语义） | `core/entity/AgentIdentityStore.scala`（新） |
| 跨机通道 | 新件 `AgentIdentitySync`：**全复用既有设施**——数据通道（presence `sendData` 优先 + relay `relayNotify` 兜底，`DeviceDelegate.sendToDevice` 同款双腿）、`devices.json` 配对表、`DeviceHmac` 验签（ts ±90s + nonce，**逐对端 key 签署**——配对 key 每对一份）。一种载荷 `identity-sync`（单 agent 全量态，头像 b64 内联 ≤200KB）；两种触发：①写后外推（display-face 三路由成功后 fire-and-forget）②**对账推**（`addPeerChangeCallback`——boot 后首联/对端重连/新配对都触发；单飞闸防抖）。离线对端由其自身下次上线/重启的对账推补齐（两腿对称、零新增定时器） | `neblink/AgentIdentitySync.scala`（新）；`GatewayMain.scala`（DeviceDelegate.initialize 同缝装配） |
| 回声抑制 | 收敛写入（applyRemote）不在任何广播路径上 ⇒ 结构性无回推，无收敛乒乓 | 同上 |
| 面板即时性 | 收敛落地 ⇒ `wsHub` 广播 `agentIdentityChanged`（对端 UI 立即重读，不等刷新/重启） | 同上 |
| meta 探针面 | 新路由 `GET /api/agents/:name/avatar/meta`（withAuth + 白名单；零字节回 `{sha256, updatedAt, displayName}`，无头像 404）——前端对账的轻读面 | `PresenceRoutes.scala` |

**红线自查**：头像 b64/字节零入日志（全部日志面只有 agent 名 / sha 前 8 位 / 字节长 / 目标数）；零引擎重启；主实例零触碰（全部工作在 worktree 分支）；凭证（devices.json sharedKey）零落日志。

## 4. 修复（前端 agentProfile.js + rootName.js + main.js）

| 面 | 改动 |
|---|------|
| 存储升级 | localStorage 条目 v1 裸 dataURL → v2 `{dataUrl, sha, at}`（旧裸值宽容读为 `sha:''`，首次对账补戳——零迁移脚本） |
| sha 对账收敛（R2 闭合） | `convergeAvatar`：meta 探针（零字节）→ sha 同 = 收敛态零动作；sha 异/缺 = 拉一次字节回填；**meta 404 = 他端已清 ⇒ 本地键清除**（v1 reset 不收敛的闭合）。常规 paint 30s 节流；强制触发绕过 |
| 误判防护 | 本机写后 15s 静默窗（自己的 PUT 在途期间探针不误判 404）；上传即算 sha（crypto.subtle，与引擎同算法同值）⇒ 写完即收敛态 |
| 触发钩 | `agentIdentityChanged` WS 帧 → `reprobeRootName()` + `listAgents` 刷新 + `nebflow-agent-identity-changed` 事件（会话头强制对账重绘）；WS 重连（onReconnect，会话恢复语义）→ 同款重读 |
| 名称可见 | `rootName.js` 新增 `reprobeRootName()`（boot 探针原先每页一次，他端改名收敛后不可达） |

## 5. 修复（改名/头像落到智能体的自我认知——第二项令）

| 层 | 改动 | 锚点 |
|---|------|------|
| 记忆层（Soul.md） | 展示面每次写（本机写面 + 远端收敛两条路统一走 `afterLocalWrite`/收敛腿）→ `touchSoulIdentity`：根 agent 的 Soul.md `## 自我认知` 段**replace-in-place**（名字行就地改写 + 头像行插入/更新，段内他行零触碰；段缺席 = 文件尾追加最小段；无变化零落盘；Soul.md 不存在 = 跳过不凭空造；落盘走 `MemoryStore.saveSoulMemory` 预算闸+快照全套既有设施，闸拒 = WARN 放弃不阻塞） | `AgentIdentityStore.touchSoulIdentity` / `rewriteSoulSection` |
| 提示词层（system prompt） | `## Self` 段（order 396）：`Display name: "X"（机制名恒为地址）+ Avatar: 自定义头像已设（sha 前 8 位）`——**只在非默认身份时注入**（无名无头像 = 段不出现 = 既有会话字节零变化、前缀缓存零失效）；随 systemStable 生命周期重建刷新 | `PromptSections.scala`（PromptContext.identitySelfText + 段）/ `AgentSessionExecution.scala` |
| 提醒层（中期变更） | identity 变更差量轴（devices 轴 F-C 同款）：每 turn 现读展示面 vs `SystemStableSnapshot.identity` 基线，变更 ⇒ 下一真用户轮注入权威提醒（「current values authoritative, previous superseded」），注入后基线推进；compact/ask 轮抑制位不产对象；生命周期轮不产（重建的 prompt 已携带新值）。AgentState 不持久化 ⇒ 重启即生命周期轮，天然自愈 | `reminders.scala`（identityReminder / renderIdentityChange）/ `AgentSessionExecution.scala`（identityDeltaText + 基线推进链） |

## 6. 验收对照

| 验收项 | 落点 | 验证 |
|---|------|------|
| ① 设备 A 改名/换头像 → 设备 B（会话恢复/重连后）可见 | 写后外推（在线即达）+ 对账推（重连/重启补齐）+ 前端 meta 对账/`agentIdentityChanged`/onReconnect 三钩 | `AgentIdentitySyncSpec`（载荷/验签/LWW/外推 6 例）+ `agent-identity-sync.spec.mjs` P1-P5（迁移/sha 异回填/404 清除/改名可见/上传写穿） |
| ② agent 新回合上下文带新名称/头像 | `## Self` 段（生命周期重建）+ identity 提醒（新回合即达）+ Soul.md 记忆层 | `IdentityPromptSpec`（段注入/提醒渲染 5 例）+ `AgentIdentityStoreSpec`（Self 段体/Soul 段 9 例） |
| ③ 单测覆盖同步写读路径 | 写面（加戳/补底/保键）、收敛面（LWW 新旧/摘键）、通道面（构建/编码/宽容解析/逐对端签验/篡改拒收/过期拒收/未配对拒收/字节闸/外推枚举） | Scala 三 spec 19 例 + 前端 spec 14 断言（见 §7） |

邻接面回归：`checkJs 257 = 冻结基线零新增`（前端类型门）；主源 `sbt compile` 全绿（598 源）。

## 7. 测试清单（本批新 Spec）

- `src/test/scala/nebflow/core/entity/AgentIdentityStoreSpec.scala` — 9 例：displayName 写（戳/保键/根名补底）、avatar 写清+readRec 三面、applyRemote（旧戳跳过/等戳应用/远端戳落盘/摘键）、selfSectionText 四门、touchSoulIdentity（无 Soul 跳过/模板 Soul 就地改写/幂等/段缺席追加/非根忽略）。
- `src/test/scala/nebflow/neblink/AgentIdentitySyncSpec.scala` — 6 例：载荷构建（total 态/无身份 None）、encode/parse roundtrip（错 kind/缺签名字段拒）、逐对端签验（过/篡改/过期/未配对）、字节闸（超限/坏 b64）、外推扇出（每目标一份逐 key 签署 + 对账枚举跳过无身份 agent）、未装配 no-op。
- `src/test/scala/nebflow/core/IdentityPromptSpec.scala` — 4 例：`## Self` 仅非默认身份注入、identity 提醒有/无差量、renderIdentityChange 形状（权威值/supersede/单行 previous/清空态）。
- `tests/agent-identity-sync.spec.mjs` — 14 断言（真模块图 + routeWebSocket 桩 + 可控引擎 API）：P1 v1 裸值迁移、P2 sha 异字节回填+会话头重绘、P3 meta 404 本地清除、P4 agentIdentityChanged 探针重跑改名可见、P5 上传 PUT 写穿+写入即收敛、E1-E5 源面接缝钉、页面零报错。**14/14 PASS**。

**Scala 三 spec 实测（2026-10-23 18:15）**：`Passed: Total 19, Failed 0`（Store 9 例 / Sync 6 例 / Prompt 4 例）。

## 8. 已知边界（诚实申报）

1. **对端离线时的收敛时机**：写后外推是 live 通道（presence/relay Notify 均不排队）——对端离线错过帧，收敛发生在它下次上线/重启时的**对账推**或本端下次 peer 变化重推；不保证秒级（fire-and-forget，无定向重试队列——重试队列属新通道面，本批不做）。
2. **LWW 粒度 = 单 agent 全量态**：两端同时改同一 agent（一端改名一端换头像）⇒ 戳新者整态胜；无字段级合并。
3. **自定义 agent 身份镜像的保守面**：远端推来的身份只对**本机已知 agent**（有 agent.json/目录者）落盘——不为陌生名凭空造空壳 agent；收敛单点 = 双端都装了同一路径的同名 agent。
4. **非根 agent 无 Soul 腿**：Soul.md 是根 agent 自我认知位（MemoryStore 单文件），非根身份落 agent.json + 提示词/提醒层（覆盖验收②）。
5. `agentIdentityChanged` 面板刷新依赖 `listAgents`/探针既有读链；对端面板在**会话恢复/重连**时也由 onReconnect 钩兜底。

## 9. 文件清单

新增：
- `src/main/scala/nebflow/core/entity/AgentIdentityStore.scala`
- `src/main/scala/nebflow/neblink/AgentIdentitySync.scala`
- `src/test/scala/nebflow/core/entity/AgentIdentityStoreSpec.scala`
- `src/test/scala/nebflow/neblink/AgentIdentitySyncSpec.scala`
- `src/test/scala/nebflow/core/IdentityPromptSpec.scala`
- `tests/agent-identity-sync.spec.mjs`

修改：
- `src/main/scala/nebflow/gateway/PresenceRoutes.scala`（display 三路由收拢 + flatTap 收尾 + meta 探针路由）
- `src/main/scala/nebflow/gateway/GatewayMain.scala`（AgentIdentitySync.initialize 装配）
- `src/main/scala/nebflow/agent/PromptSections.scala`（PromptContext.identitySelfText + order-396 段）
- `src/main/scala/nebflow/agent/AgentSessionExecution.scala`（身份现读 + 差量 + 基线推进）
- `src/main/scala/nebflow/core/reminders.scala`（identity 提醒类 + 渲染器）
- `src/main/scala/nebflow/actor/AgentState.scala`（SystemStableSnapshot.identity 轴）
- `src/main/resources/web/js/agentProfile.js`（sha 对账收敛全套）
- `src/main/resources/web/js/rootName.js`（reprobeRootName）
- `src/main/resources/web/js/main.js`（agentIdentityChanged 消费 + onReconnect 重读）
