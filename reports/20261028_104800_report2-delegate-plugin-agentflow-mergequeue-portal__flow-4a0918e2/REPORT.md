# 只读分析报告二:delegate 机制 / 插件分配 / agentflow / 合并队列 / 官网入口与数据同步

五腿并行侦查(chain flow-4a0918e2)的收口报告。全部只读,零代码/配置/引擎改动(唯一写动作 = 按 merge-quarantine PROVENANCE 的 restoreHint 把三条腿产物移回原路径)。

## 0. 五腿核对清单(交接审计)

| 腿 | 子会话 | 状态 | 产物(绝对路径) |
|---|---|---|---|
| plugin-alloc | node-22be8dbc | ✅ 完成 10:31 | `/Users/kaiyu/Claude code/Stand/20261028_102603_plugin-alloc-mechanism-recon__flow-4a0918e2.md` |
| merge-queue | node-652cb43b | ✅ 完成 10:36 | `/Users/kaiyu/Claude code/Stand/20261028_103036_nebflow-merge-queue-recon__flow-4a0918e2.md` |
| portal-sync | node-443a91ef | ✅ 完成 10:35 | `/Users/kaiyu/Claude code/Stand/design/20261028_103008_recon-entry-dataplane__flow-4a0918e2.md` |
| delegate-mech | node-989b65a5 | ✅ 文档已落 10:38 | `/Users/kaiyu/Claude code/Stand/reports/20261028_103847_delegate-mech-recon__flow-4a0918e2.md` |
| agentflow-map | node-9de8bbbd | ⚠️ 会话仍在写、截至 10:48 无产物 → 本报告 §3 为内联补查结果(直接读代码+台账取证) | (无;以 §3 为准) |

插曲(合并队列机制的现场实证):10:41:57 另一 delegate(delegate-nebflow-fec22d25,分支 delegate/delegate-nebflow-fec22d25)过合并闸,MergeArtifactCare 把本链三条腿落在主工作区根/design 的未跟踪产物判性后移入 `/Users/kaiyu/.stand/merge-quarantine/Stand/20261028_104157/`(PROVENANCE.json 旁挂,零删除);已按其 restoreHint 于 10:45 全部移回原路径。

---

## 1. delegate 机制(腿:delegate-mech)

**生命周期单点**:`DelegateTool.spawnBackground`(`src/main/scala/nebflow/core/tools/DelegateTool.scala:654-680`)→ id=`delegate-<agent>-<8hex>`(:683)→ `DelegateWorkspace.prepare` 判定机裁决,**判是/判否一律建** `delegate/<id>` 分支 + worktree 座位(`core/delegate/DelegateWorkspace.scala:89-146`,判否=只读座位,建败回落 inPlaceBinding)→ DelegateRegistry 持久地址簿(`<dataRoot>/delegates/<id>.json`,零删除纪律)→ ChainStore/TaskLedger/任务行三处登记。终态唯一写点 = `BackoffSupervisor.notifyParentAndStop`:判定机(漂移闸→预检→judge→自申报)→ Release / HoldForMerge(入 MergeQueue)→ 终态三清+五件(台账结算 note、flow-host 卸载、插件回收、MergeQueue 关账、registry 归档)→ 交付回执 → 父通知。三层状态分域干净:内存活体(agentRegistry)/任务行状态机(SubAgentTaskStore)/持久地址簿(DelegateRegistry,状态不落本表)。

**崩溃恢复三线**:进程内 BackoffSupervisor 退避 respawn(5s×2^n 封顶 60s,maxRestarts=2/5min,断点续跑;respawnSafely 兜底壳封死「restarting 行无人终态化」);跨进程 boot sweep(SubAgentStartupRecovery)→ 复活资格 7 条闸(DelegateRevival:kill-switch 开、source=delegate、registry 在、座位在、父在、revivalCount<2、年龄<7d;boot cap=8,enabled 缺省 true)→ 不合格走收殓链(捞尸部分结果+F2 幂等父通知+跨设备改走 outbox);运行期 watchdog(在飞行 ≥10min 无活体 actor ⇒ 诚实 failed)。「queued for revival」出处 = `agent/SubAgentStartupRecovery.scala:180`。

**cancel 语义与已知缺陷(28 僵尸事故的机制缝隙)**:正路 doCancel(监督者在)写了完整持久终态(任务行 cancelled+回执+判词行+registry 归档旗)。但①`AgentControlTool.scala:877` 对监督者是 fire-and-forget tell,监督者已死 ⇒ 零写入零报错、工具却回成功文案(缝隙 A);②SubAgentTaskStore.updateStatus 父文件/行不匹配时静默 no-op(缝隙 B);③DelegateRegistry 无 cancelled 墓碑字段、复活资格不复查取消指纹(缝隙 C)——取消意图的持久性全押在任务行一行上。盘面指纹:2 件「已取消 × 被复活过」(e25cd0c2 revival#1、b5646a58 revival#2);「28」单次直证未复得(日志滚动,单次最高 15),登记为未证。DelegateLedgerReconcile 修的是台账↔面板对账,与该缺口正交。修复方向:post-cancel 校验、updateStatus 零命中告警、registry 加 cancelRequestedAt 墓碑 + 复活资格查墓碑。

**台账与座位**:派发登记 TaskLedger(`~/.stand/tasks-v2.json`,现读 966 条);终态经 DelegateLedgerReconcile 腿2 append 结算 note、腿1 台账终态回收面板行(日志实证多批归档);项目侧 task-board.json(178 项)是派发者工作单,非运行状态机。worktree 座位 = `<ws>/.nebflow/worktrees/delegate-<id>`(项目仓现 153 项);flow-map 座位 = `.nebflow/flow-maps/<delegateId>/`。

---

## 2. 插件分配(腿:plugin-alloc)

**声明面 = 文件组,不是 nebflow.json**。能力本体在 `<数据根>/plugins/<name>/`(本机 `/Users/kaiyu/.stand/plugins/`,21 个插件):`plugin.json`($schema 必须 canonical `https://agent-plugins.org/schemas/1.0.0/plugin.schema.json`,非 canonical 拒载;skills 与 mcp 至少其一否则拒载,`core/plugin/PluginRegistry.scala:815-818`)+ `skills/*/SKILL.md` + `mcp.json` + `org.nebflow/tools.json`。nebflow.json 只有控制面五键族:`plugins.enabled`/`autoAllocate`(热读缺省 true,fail-safe 向开启)/`trust`/`dispatch`/`revoked`(`core/plugin/PluginsConfig.scala:27-45`)。

**分配链全五步**:①Catalog 渲染进分发器首条 prompt(`PluginRegistry.renderCatalog:1513-1539`,三级过滤 enabled→trusted→dispatchable);②分发器经 NodeEdit `plugins` 参数点名(节点声明面;新建漏传即拒 `NODE_PLUGINS_UNDECLARED`;闸 A 在落库时刻判 `contentTrusted ∧ PluginDispatchPolicy.effective`,只判新增分配);③派发许可单点 `PluginDispatchPolicy.effective(name,trusted)`(`core/plugin/PluginDispatchPolicy.scala:127-128`,读 dispatch 热表,缺省放行,作者关闭只拒新分配);④spawn 装载序 `NodeStarter.scala:331-367`:prepareNodePlugins 失败→failNode,成功→首条消息注入 `<injected-plugins>` 块(Plugin.md 全文)+ `pluginMcp.acquire`(MCP 引用计数、15s 超时、任一失败回滚整批=failNode)、运行结束 release;⑤会话内授权 = `mcp__plugin_<p>_<s>__` 前缀 + builtin 工具白名单再过滤(`agent/AgentSessionExecution.scala:2272-2334`)。

**deny-list 五点阻断**(独立命名空间 `plugins.revoked`,先 deny 后 allow;放 trust 段会被自动 approve 抹掉,所以独立):装载后不进目录 / resolve Left / 闸 A 新分配拒 / 闸 D 运行期 revalidate 停飞 MCP+系统提醒(30s 拍)/ viewer 内联 fail-closed。

**delegate 边界(一处精确化)**:Delegate 工具三参(task/project/executor)**无 plugins 键**,派发方无法点名、模型无热挂载权(mounting authority stays with the dispatch side);但 #1226/#523 批后内置执行器腿有引擎按 **brief 文本自动分配**的注入(`DelegateTool.scala:686-707`,PluginInjection.allocatePreparation,Jev→LLM 兜底→fail-open 空集);**外部执行器腿零注入**(ExternalSessionRunner.scala:198 原文)。本链即活例:5 条 step-plugins 台账行,4 腿 fail-open 空集、plugin-alloc 腿 llm-fallback 分得 explorer-toolkit(`~/.stand/chains/delegate-nebflow-c93d27ad.json`)。

**现状盘点**:21 装 / 7 trusted(=种子默认集 visual-report、computer-use、browser-use、slideblocks、web-search-toolkit、document-production、nebflow-plugin-creator);revoked/dispatch 两表零使用、项目级按项目挂载零实例、MCP 型插件零个(全部 skills 型,仅 web-search-toolkit 带 tools.json)。空缺:按项目挂配置两处均不存在;`.nebflow/plugins-ux` 不存在(report-plugface/merge-plugface 是 2026-09-12 历史证据非配置)。

---

## 3. agentflow / flow map(腿:agentflow-map 会话未产出 → 内联补查)

**两条机制线**(`src/main/scala/nebflow/core/flow/`,全件 13 文件 2241 行):

- **持久 Flow Map(Flow Map 节点图)**:`FlowTreeActor.scala:275`(852 行,树命令/mount 生命周期)+ FlowTreeRegistry(134 行)+ FlowTreeTypes(56 行)。节点经 NodeEngine 路由:Mail `node:<id>` 寻址 → `NodeDelivery.sendNodeMessage`(`core/project/NodeDelivery.scala:229`)——running 节点下一 turn 边界注入(带 `[NODE-MESSAGE]` 头,:157/:273/:339)、wiring/pending 节点 append 进 task、terminal 节点拒收。验证器 verdict 走 `(fail)<worker>:loop` 回环;loop 节点 maxRounds;合并窗口 mutex(mergeQueuePos/mergeQueue 投影)。
- **一次性 AgentFlow(ephemeral DAG)**:`core/tools/AgentFlowTool.scala` → `FlowDagRunner.scala:15-24`:one-shot actor,同步逐节点执行,结果经 ImmediateInput 回投调用方后自停;#406 inline DAG 用 `inline-` 前缀实例 id。EphemeralAgentRunner.scala:14-21 是给 MailTool 寄给未挂载 standalone agent 的临时会话(spawn→UserInput→event 驱动等结果→删会话自停)。

**链账本**:`core/delegate/ChainStore.scala:53-64` appendSync journal → 本项目 `.nebflow/chain-ledger.json`(20,151,535 B):**1,664 条链 entries + 54,309 条 rounds 归档记录**,归档侧 `chain-ledger-archive/` 54,309 个 round-*.json(~44.6KB/个);aliases 17。delegate 作用域子图另落 `<ws>/.nebflow/flow-maps/<delegateId>/`(本链 = delegate-nebflow-d99a238a,链已归档、图空)。

**使用现状量化**(主项目 `.nebflow/flow-map-events.jsonl`,49,175 行,2026-09-03 → 2026-10-28):
- 事件类型 Top:dead-session-reaped 10,375 + node-session-dead-reminder 10,357(两族合计 42%,全是僵尸会话回收噪声)、interrupted 4,482、mount-stalled 4,108、node-destroy-scheduled 3,501、**node-message 3,222**、node-destroyed 3,177、dispatch-notify 1,980、chain-archived 1,631、chain-membership-changed 1,196、reactivated 707、settle-sweep 536;`"type":"ask"` **0 条**(面板 ask 追踪未在此日志落事件)。月度:9 月 19,515 / 10 月 29,658;**峰值日 10-15/16/17/18 = 6,863/11,954/5,258/1,994 条,最近 7 天每天仅 2~34 条**——持久 Flow Map 重度使用期已过,日常主承载已转到 delegate 主线 + 一次性 AgentFlow 链(本链即后者形态:ChainWrite 5 节点、终态即归档)。
- `flow-map-archive/` 1,838 项;主项目 flow-map.json 当前 **0 活跃节点**;`/Users/kaiyu/.stand/flows/` 预置挂载 flow 8 个(code-review、entity-creator、git-merge、nebflow-review-merge、presentation-prep.archived、release-stable、research、weekly-summary)。

**分工结论**:delegate(单任务+worktree+合并队列)= 真实改码工作主线;Flow Map 持久 DAG = 需要跨 turn 存活的多节点编排/验证器回环/合并窗口(现偶发);AgentFlow ephemeral = 分发器会话内一次性并行侦查(高频,不留盘面)。

---

## 4. 合并队列(腿:merge-queue)

**裁定与形态**:纯排队+通知的「交通引导员」制(台账 #896 作者裁定原文,`reports/20261026_021500_merge-queue-traffic-controller/REPORT.md`:引擎永不自己跑 merge,放行=提醒属主;整体删除 DelegateAutoMerge/MergeStallCare/MergeWindowCare/MergeRefusalCare 四件)。#943 诊断(`reports/20261026_153500_merge-gate-diagnosis/REPORT.md`)坐实 #896 把 fail-soft 工件卫生走样成 fail-closed 阻拦闸且零通知;修复三件:拆闸(余脏 WARN 放行)、自动判性、阻塞必通知(`reports/20261026_161900_merge-queue-pure-queueing-notify/REPORT.md`)。

**队列本体**(`core/delegate/MergeQueue.scala`,935 行):状态机 waiting→turn-granted→merged/owner-gone/failed;排序 readyAt FIFO;持久账本 `~/.stand/merge-ledger.json`(逐迁移写穿+tmp+move 原子);per projectKey 单飞行;放行三闸 = 账实核对先行(is-ancestor 已并⇒直接关账)> 净空闸(baseReady,余脏只 WARN、方向闸保留、闸故障 fail-open)> 通知即放行(notifyTurn:内部腿向属主 actor 注入 UserInput(source="merge-queue"),外部腿走续聊闭包;通道失败 ⇒ owner-gone,绝不楔死)。合并指令 = 属主自己执行 `git merge --no-ff`,拒绝/冲突=STOP 上报关账 failed 无自动重试;机械验收闸 MergeAcceptance(零 LLM 双路校验,fail-closed)。工件卫生 MergeArtifactCare(白名单 reports/evidence:还原→判性→收编;白名单外移 `~/.stand/merge-quarantine/` + PROVENANCE)。合并后 PostMergeCleanup(零领先守卫,`-d` 不 force)+ WorktreeRescue(删前抢救 reports/evidence 只补缺不覆盖)。重启对账 MergeQueueBootSweep:三分类(已并/无可并物/可并但属主失联⇒owner-gone 需 dispatcher 处置),零复活。

**吞吐实测**(merge-ledger.json,25 条):10-26 首日 5 → 10-27 峰值 16 条/天(3.2×)→ 10-28 半日 4;合计 merged 24 / owner-gone 1 / failed 0;排队时延中位 ~49s(25-90s 密集),离群 #931=2644s(#896 旧闸卡 44 分钟,人工疏通)。零引擎代并,24 条全部 delegate 自并后机械验收关账。

**重启丢队缺陷(B2 归队实证)**:状态持久、**通道不持久**(internal/external 投递通道是进程内闭包/actor ref 不落盘,MergeQueue.scala:123-133)⇒ 重启后开放成员必走 `channel lost across restart` ⇒ owner-gone;boot 对账零复活零重挂 ⇒ 重启必然把未关账成员打成人工处置。更早一层:ready-to-merge 之前被重启杀死 ⇒ 连账本条目都没有——B2(WS 分片腿 #978)即此形态:13:00 判 completed 未入队,20:55 引擎重启,23:07 人工 `--no-ff` 归队(main=c0329131c,提交消息原文 "(merge queue lost in 20:55 engine restart, re-landed)");对照 #987 重派批走正常队列流 52s 关账。10-26~27 至少重启 5 次,每次同一缺口。跨设备腿有 DeviceResultOutbox write-ahead(at-least-once)兜底,本机 outbox 目录不存在=零积压。

**今日现场实证(本报告收口时)**:10:41:57 delegate-nebflow-fec22d25 过闸,MergeArtifactCare 把本链 3 个未跟踪产物(根/design 下)判性后移隔离区 `/Users/kaiyu/.stand/merge-quarantine/Stand/20261028_104157/`,PROVENANCE.json 旁挂(来源工作区/原路径/delegate/分支/restoreHint),零删除、合并不被阻塞——判性+隔离+必留痕机制按设计运转;已按 restoreHint 移回原路径。

---

## 5. 官网入口与数据同步(腿:portal-sync)

**全链路**:浏览器 mystand.space(官网/门户)→ Logto OIDC AC+PKCE 登录(auth 租户;引擎内嵌默认仍指旧域 auth.nebflow.space,`neblink/NeblinkModel.scala:675,714-724`)→ portal 页经 link-server 发 ConsoleTicketMint 帧 → relay WS(`wss://link.mystand.space/api/device/relay-ws`)→ 本机常驻出站隧道 NeblinkRelayTunnel → PortalTunnelBridge(真 loopback `ws://127.0.0.1:6138`)→ `GET /?ticket=` 兑换一次性 console-ticket(relay-mint TTL 90s / local break-glass 120s,`neblink/ConsoleTicketStore.scala:168`)→ `stand_session` host-only cookie(30d 绝对/7d 滑动,进程内不持久化)→ console 全功能。门户不持有引擎 cookie,ticket 是唯一桥。

**入口单源**:brand.conf:12-18(domain/portalUrl/serverUrl 等)→ `gateway/PortalUrl.scala:31-57` 单点解析(env NEBFLOW_PORTAL_URL > config > brand;NEBFLOW_OPEN_UI=local 逃生口)。配对三路径(配对码/浏览器 PKCE/无头密码)收口同一 `NeblinkEnrollment.persist`;uplink 凭据单源 config.json `neblinkServer.deviceToken`+每帧 token hash 校验;设备凭据 `neblink/device.json` 明文 JSON 靠 0600 ACL。

**portal WS v1.3 分片+gzip:已落 main、结构性未部署**:合并提交 c0329131c(10-27 23:07)已进 main(B0 b5e93cb2a/B1 1049153d8/B2 ecadba91e;22 测 spec、224/224 回归),但 relay 侧 B3 批(link-server 仓)未做——没有对端 echo `neblink.portal.v2`,引擎先部署只会协商失败回落 v1 整帧、**零收益**;B4 灰度/allowlist/cap 同源也未做。痛点原值:4MiB 控制台大 JSON→base64≈5.6MB 单帧、跨境上行单帧写 10-45s;预期压缩 5-20×(简报口径),B2 实测 0.636×(伪随机文本)、高熵走 worth 门 wire 不增、64KiB 全零→96B(炸弹防线生效)。

### 数据面瘦身清单(流量细账,按单帧/单事件量级排序)

| # | 项 | 机制与量级 | 瘦身选项(预期) |
|---|---|---|---|
| 1 | **identity-sync 头像内联** | `neblink/AgentIdentitySync.scala`:载荷 10 字段含 avatar(b64),闸 MaxAvatarBytes=200,000(:54)→ **带头像 ≈267KB/帧**,全数据面最大单帧;每次外推重读盘+b64 | sha 先行、字节按需(载荷已带 avatarSha256);头像字节改走 `/api/nf-file` 票道(immutable+304),帧内只留 sha → **~267KB→~300B/帧**;对账推只在 boot 首联一次 |
| 2 | **静态资源/vendor 首开** | 前端源树 529 文件、整树估 **8-12MB**(monaco editor.main.js 3.6MB、tsWorker 51,327 行、xlsx 0.8MB、KaTeX 字体 ~60 个、mermaid 217KB);GzipMiddleware 只包 `/` 静态树、无阈值、/api 排除(`gateway/GzipMiddleware.scala:42-89`) | monaco/tsWorker/xlsx 懒加载(首开 **−60-70%**);KaTeX 字体子集化(60→4-6,省 ~1MB+);静态预压缩 .gz/.br 伴生直出(文本资产再 −~70%) |
| 3 | **canvas-tabs 全量重写** | `/Users/kaiyu/.stand/canvas_tabs.json` 实测 18,260B/15 tab;前端每次变更 500ms 防抖后 **PUT 全量 tabs、18KB 全量重写**(峰值 ~0.5-1MB/min 磁盘写);服务端 merge 本是并集语义 | journal 化(AtomicJson 已内建 append/checkpoint,append 单行 ~200B);PUT 差量化(协议已留 tombstones/reopened 账口,加 tabUpserts 即可)→ **18KB/次→200B/次**;id 改项目相对短 id(18KB→~8KB) |
| 4 | **session-sync push** | `neblink/AgentSessionSync.scala`:14 字段帧 ~300-500B,每任务生命周期 4-8 写×设备数;peer 抖动时 N 行全量重推(幂等但重复);帽:标题 200 字符/载荷 <2KB/每设备 200 行 | 对账推改指纹比对(清单 hash 先行、对端回缺再单推);写后外推 300-500ms 防抖(**帧数 −30-50%**);多行合帧省信封+逐帧签名 |
| 5 | **presence 心跳** | 拨号端 5s ping 15B/帧 → **360B/分/对端** 纯 keepalive;服务端 10s tick、peer 同步 45s;data 帧信封 ≈50B+payload | 心跳 5s→15s 自适应(须连 10.05s 自循环验收 spec 一起改);收益最小 |

**官网 vs 本地体验差距的答案口径**:传输面可消除字节排序 = 头像出帧(−267KB/帧)> vendor 懒加载(首开 −60-70%)> canvas-tabs journal 化(−99% 写)> session-sync 防抖合帧(−30-50% 帧)> 心跳降频;最大的一笔未兑现收益是 v1.3 分片+gzip 上生产(被 relay B3 结构性卡住,单独上引擎零收益)。

---

## 附:本报告证据文件

- 四腿原文(绝对路径见 §0 表);本报告 §3 为内联补查(agentflow-map 腿会话截至 10:48 无产物)。
- 插件分配活证:`~/.stand/chains/delegate-nebflow-c93d27ad.json`(5 条 step-plugins 行:4×fail-open 空集、1×llm-fallback explorer-toolkit)。
- 隔离区事件:`~/.stand/merge-quarantine/Stand/20261028_104157/PROVENANCE.json`(delegate-nebflow-fec22d25,3 文件,已复原)。
