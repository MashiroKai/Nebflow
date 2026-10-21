---
type: report
status: solved
---

# 控制台 403 症状群诊断报告（2026-10-21）

> 范本说明：本文是 docs 文档批的**格式范本**之一，改写自仓内既有报告 `reports/20261021_115500_console-403-group-diagnosis__delegate-nebflow-6ba62e27.md`。正文为真实诊断内容；front-matter、wiki-link 引用与概念首现处理（首现即解释或引用 [[glossary#^concept-first-use]]）为示范面。另一篇范本：[[console-load-perf]]。

批次：2026-10-21 诊断修复批（delegate-nebflow-6ba62e27）· 引擎 stand 2026.9.20（PID 77400，当日 09:59 / 10:17 两次重启）。

## 每症状一行结论

| # | 症状 | 一行结论 |
|---|------|---------|
| ① | 插件 nebflow-plugin-creator 拒载 | **非用户篡改，是出厂前进踩死锁**：盘上包逐字节等于 10-14 出厂版（eef4c9119），10-20 出厂更新（5fe4309c6「Plugin.md=必要文件机制」种子面）把内置 digest 从 0621dd40dbfe 抬到 f0b97c9f90cc，而信任记录停在更旧的 e885aa308c21 ⇒ SeedService 判「用户改过保留」（刷新腿要求 runtime==trusted，永不触发）⇒ 官方闸（只认当前 jar 出厂 digest）拒载——两个仲裁器基准不同步的自愈死锁。**已修**：备份后恢复出厂字节，digest 已吻合，**免重启实时装载成功**（见「插件修复证据」）。 |
| ② | projects 403 | **隧道来源的会话在中继 ingress 平面（[[glossary#^relay-ingress-plane]]，下称 ingress 平面）被拒，不是宿主网关闸**：link.mystand.space 控制台 ingress（Rust link-server）对每个 /api/* 先验中继自己那套登录（require_user），过闸才经隧道转发到宿主；宿主侧 curl 实证全部 200（带有效会话）。作者浏览器缺的是 ingress 平面登录 ⇒ 请求根本没到宿主（当日重启后宿主侧零鉴权拒绝日志可证）。 |
| ③ | 知识图谱（图谱视图，[[glossary#^graph-view]]）空白 | **403 群下游**：/api/wiki/graph 是宿主会话门面，curl 带有效会话 200 实证；前端拉不到数据即空态渲染。无独立病。 |
| ④ | token 用量面板加载失败 | **403 群下游**：/api/usage/aggregate 同一宿主会话门，curl 带有效会话 200 实证。 |
| ⑤ | 搜索聊天记录 403 | **403 群下游**：/api/sessions（搜索/历史）同一宿主会话门，curl 带有效会话 200 实证。 |
| ⑥ | 心跳进程（daemons 面板）空 | **403 群下游**：面板数据 = GET /api/daemons 同一宿主会话门，curl 带有效会话 200 实证（空态=拉不到）。附带观察：'Nebflow Website' daemon 凌晨 01:39–06:19 曾 crash-loop（exit 1）后自稳，与本症状无关。 |
| ⑦ | 均在 link.mystand.space | **与 ingress 平面解释自洽**：静态面由 Service Worker（[[glossary#^service-worker]]，下称 SW）缓存秒开（sw.js 对 /api 零接触），API/WS 全在 ingress 平面被拒 ⇒ 静好 API 全挂、宿主侧零日志。 |
| ⑧ | 智能体头像/名称跨设备不同步 | **双实现缺口（非 403 群，与隧道无关）**：①头像只有 localStorage 本地腿（agentProfile.js `writeStored`=纯本地，全仓无任何服务端头像面）——「登录后可随账号同步」从未实现；②名称「回车即存」更是纯内存（saveName 只写 state/agentsData 行+事件，**连本机刷新都会还原**；唯一落盘写点是一次性 onboarding 的 agent.json 腿，面板写回 2026-09-06 已退役）。**本批已补齐双腿**（见「症状⑧修复」），候 [[glossary#^restart-window]]（重启窗）生效。 |

## 403 群主攻：判定与证据

**架构事实**（代码读）：控制台有两套独立会话平面——

1. **ingress 平面**（link-server/src/routes.rs:559 `require_user`）：Logto AT（访问令牌）+ refresh 滑动窗，管 /api/*、/ws、静态资源的**转发准入**；
2. **宿主会话平面**（[[glossary#^host-session-plane]]；GatewaySessionStore，纯内存）：管宿主各面鉴权，**每次引擎重启即清空**（设计如此，注册过的 trade-off）。

**宿主侧健康证明**（curl 级，2026-10-21 11:2x 实测）：

```
POST /api/auth/console-ticket (Bearer 文件令牌) → 43 字符票
GET  /?ticket=<票>                              → 303 + Set-Cookie
GET  /api/nf-authcheck   (带会话)               → 204
GET  /api/projects      (带会话)               → 200
GET  /api/usage/aggregate(带会话)               → 200
GET  /api/wiki/graph    (带会话)               → 200
GET  /api/daemons       (带会话)               → 200
GET  /api/sessions      (带会话)               → 200
```

当日重启 jar 新含的四道鉴权改动（Secure cookie 按来源 / auth-trim 服务端 / local-ticket 腿绑定 / 绑定闸）**均不在中继平面、也不拦 relay-mint 会话**——localTicketOnLeg 只约束 local-ticket 源会话（隧道腿 relay-mint 逐字不受影响，GatewayAuth.scala:102-103），Secure 按来源正确，绑定状态实测 `bound`。**判定：不是回归 bug，是真·ingress 平面登录缺失（需作者重新登录一次）**；但控制台前端有一个**真陷阱**：WS 被拒后的兜底登录走「PKCE（授权码交换防截持的 OIDC 流程）直连宿主」——它只能铸宿主会话，对 ingress 平面无效，形成「登录了还是 403」死循环（本次已修，见「引擎侧改动」）。

**ingress 平面缺失证据**（无需作者浏览器即可取得）：

```
curl https://link.mystand.space/api/projects        → 403 {"error":"Missing token"}   ← Rust 形状（ingress 拒），非宿主 403
curl https://link.mystand.space/                    → 302 /api/auth/login?return_to=%2Fexit
curl https://link.mystand.space/api/auth/login-config → 200 {"configured":true}        ← 登录腿在位（公开面）
curl -I https://link.mystand.space/api/auth/login?return_to=%2F → 303 auth.mystand.space/oidc/auth ← OIDC 腿活
```

日志旁证：当日 10:17 重启后宿主侧零 "WebSocket auth failed" / 零 "portal ws_open failed" / 全历史零 "console ticket redeemed"——作者的 403 都发生在 ingress（未达宿主），静态面靠 SW 缓存照常渲染。

## 插件修复证据（实时，免重启）

- 修前：`GET /api/plugins` → rejected=[nebflow-plugin-creator: OFFICIAL_IMPERSONATION (built-in f0b97c9f90cc vs disk 0621dd40dbfe)]
- 动作：备份 `~/.stand/plugins-backups/nebflow-plugin-creator-pre-restore-20261021T1129.tar.gz` → 恢复出厂字节（rsync 自 `src/main/resources/seed/plugins/nebflow-plugin-creator`，与运行 jar 内置树同源）
- 修后：盘上 digest 复算 = `f0b97c9f90ccb4…`（与运行引擎允许列表逐字相等）；30s 重扫闸后 `GET /api/plugins` → **creator loaded: True, rejected: []**（2026-10-21 11:34 实测）；11:33 之后日志零新增拒载行。
- **候选保留的用户改动面：无**——盘上包与 10-14 出厂版（eef4c9119）逐字节相同，零用户定制可裁。

## 症状⑧修复：智能体头像/名称的持久与跨设备同步腿（候重启窗）

**诊断细节**：双设备连的是**同一引擎**（link.mystand.space 隧道），同引擎本应同状态——不同步的根因是**保存腿根本没落到引擎共享存储**：

- **头像**：`agentProfile.js` 写 localStorage（branding 命名空间 `agent_avatar_<name>` 键，256px dataURL）——纯浏览器本地，引擎侧零存储零路由；`/api/neblink/avatar`、`/api/avatars/{userId}` 是 neblink **联系人**头像面，与此无关。承诺的「随账号同步」腿不存在 ⇒ 实现缺口。
- **名称**：`saveName()` 只写内存（state.rootDisplayName / agentsData 行 / 事件）；持久名唯一写点 = 一次性 onboarding 流（`OnboardingArtifacts.writeDisplayName` → agents/<root>/agent.json）；面板通用写回（PUT /agents/:name）2026-09-06 以「能力配置 definition/plugin-managed」为由退役（410）。⇒「回车即存」既不跨设备、也不跨刷新。

**本批修复**（display 面定位——只写展示字段，不触能力配置，退役裁决不被复活）：

| 面 | 实现 |
|---|---|
| 名称落盘+同步 | 引擎 `PUT /api/agents/:name/display-name`（trim 1–64 字，AtomicJson 合并写 agents/<name>/agent.json 的 displayName；root 沿用 OnboardingArtifacts 的 name 键补底纪律，非 root 不补、既有字段零抹除）+ 前端 saveName fire-and-forget 写穿（内存先写+事件照旧，UI 零阻塞）。agentsData 行（handleListAgents 折叠 agent.json）与 rootName 探针同源此文件 ⇒ 同引擎多端下次 list/探针即收敛。 |
| 头像落盘+同步 | 引擎 `PUT /api/agents/:name/avatar`（base64 dataURL，png/jpeg/webp，字节闸 ≤200KB，落 agents/<name>/avatar）+ `GET`（**fs2.Stream 显式字节实体**——裸 Ok(Array[Byte]) 会命中 circe byte 数组编码器把图片变 JSON 数字数组，/neblink/avatar 同款教训；内容 sha256 强 ETag + If-None-Match 304 + Cache-Control private）+ `DELETE`（幂等 reset）。前端：上传/reset fire-and-forget 写穿；读腿「本地优先」照旧（localStorage 先），**缺键回填**（本地空 → GET 引擎 → 命中写回本地键+重绘，按 agentName 在飞去重）——另一端经回填/下次拉取收敛。 |
| 文案校直 | `profile.note`：「登录后可随账号同步」→「保存后同步到本引擎的所有登录设备；名称回车即存（跨设备生效）」（zh-CN + en 同步；新文案与实现逐字对齐）。 |
| 定位纪律 | 三面全部 withAuth（ApiFull 会话/Bearer，与 GET /agents/:name 同 rail，**新路由禁裸奔过夜**口径）；agentName 白名单校验拒路径穿越；2026-09-06 退役臂（PUT /agents/:name ⇒ 410）保持钉住——display 面不在其射程内。 |

**验收**：`AgentDisplayFaceRoutesSpec` 7/7 绿（worktree）——displayName 写入/合并/校验三例、avatar 字节闸/回显/304/404/DELETE 幂等、全部三面裸请求 403 + 退役 sibling 仍 410。质量闸：checkJs agentProfile.js 清零（顺带把该文件既有 4 处 TS2339 收掉，全库回到基线 273=273）；build-web 175 源 orphans:0；DeviceFaceHardeningRoutesSpec 7/7（同表 sibling 零误伤）。**跨设备实链验收候重启窗后由作者两步操作**：设备 A 改头像/名称 → 设备 B 刷新控制台即见（头像 B 端本地若有旧键，以 B 端 localStorage 旧值优先——v1 已知限制：reset 只对「本地也清/缺键」的端收敛，见 agentProfile.js 头注）。

## 引擎侧改动（候重启窗照旧注记）

1. **SeedService 官方 namespace 死锁修复**（`core/seed/SeedService.scala`）：保留 `nebflow-` 前缀包 runtime ≠ seed 时不再「用户改过保留」，一律出厂字节赢（pre-sync 备份 + 镜像 + 重审），与装载层官方闸「只认当前分发出厂 digest」对齐；非保留名包「用户版保留」语义逐字不变。随附：`SeedPluginReconcileSpec` 新增 ②b 死锁回归钉；`SeedPluginDeletionMarkerSpec` ③ 内容面合同翻转（官方包手动装回+用户附件 ⇒ 镜像回出厂、附件进备份；标记清理/重护生命周期不变）。**此改动候重启窗生效**（生效前若出厂再前进，需重跑本批的手工恢复步骤）。
2. **ws.js 兜底登录双平面化**（`web/js/ws.js`）：remint 失败且 `/api/portal/status` 被拒时，探测公开面 `/api/auth/login-config`，命中即导航 ingress 自己的登录腿 `/api/auth/login?return_to=<当前页>`（已登录者服务端直弹 return_to，无 provider 往返）；直连 LAN 控制台（无中继）保持原 PKCE 兜底。**web 静态树随 jar 走，候重启窗生效**（SW 索引指纹会在重启后的首次刷新拉到新壳）。质量闸：reconnect-collapse + ws-card-lock 20/20 绿；checkJs ws.js 0 错；build-web 175 源 orphans:0；devpanelname U3Pd 头像断言红经 HEAD A/B 定谳存量（与本批无关）。
3. **智能体 display 面（症状⑧）**（`gateway/PresenceRoutes.scala` + `web/js/agentProfile.js` + locales）：PUT display-name / PUT-GET-DELETE avatar 三面 + 前端写穿/回填双腿 + 文案校直，详见上节「症状⑧修复」。规格：`AgentDisplayFaceRoutesSpec` 7/7。**候重启窗**（引擎面与 web 树均随 jar）。

## 需作者操作的两步卡（立即可用，不等重启）

> **第 1 步**：控制台页打开 <https://link.mystand.space/api/auth/login?return_to=%2F>，用官网账号完成登录（或从 mystand.space/portal 重新点进控制台一次）。
> **第 2 步**：回到控制台刷新一次。首次 WS 握手失败会自动走「重铸票→兑换→重载」链（/api/portal/status → console-ticket → ?ticket= 兑换），宿主会话自动落座，面板即恢复；若个别面板仍空，再硬刷新一次（Cmd+Shift+R）。

## 附带观察（未处理，一行备案）

- DESKTOP-IJBBEOT（100.118.229.97）对宿主 8080 的 presence WS 升级持续被 conn-guard 以 per-ip-cap 拒（该 IP 已持 64 条 WS），每次快照 rejected 计数缓涨——对端侧的拨号循环病，与本批七症状无关，候另行排查。
- 前日 19:40 有 "portal uplink inbox full" 背压告警簇（中继慢于本端），当日零复现。

## 关键文件

| 面 | 路径 |
|---|---|
| 宿主会话/腿绑定 | `src/main/scala/nebflow/gateway/GatewayAuth.scala`、`GatewaySessionStore.scala`、`LoopbackLeg.scala` |
| 票兑换面 | `src/main/scala/nebflow/gateway/ConsoleTicketRoutes.scala`、`AuthAdminRoutes.scala` |
| 前端自愈链 | `src/main/resources/web/js/ws.js`（remintHostSessionAndReload / showTicketEntry） |
| ingress 闸 | `~/.stand/projects/link-server/src/routes.rs`（require_user:559 / console_ingress_http:3712 / console_ticket:3108 / console_ws_bridge:3424） |
| 出厂包与允许列表 | `src/main/resources/seed/plugins/`、`src/main/scala/nebflow/core/plugin/OfficialPackages.scala` |
| 种子仲裁修复 | `src/main/scala/nebflow/core/seed/SeedService.scala`（reconcilePlugin 官方 namespace 腿） |

## 相关文档

- 术语：[[glossary]]（ingress 平面 / 宿主会话平面 / SW / 重启窗的定义都在词表）。
- 同批范本：[[console-load-perf]]（控制台加载性能验证报告范本）。
- 入口：[[index]]。
