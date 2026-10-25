# 手机端 UI 优化要求逐条对账总账（纯审计零改码）

- **审计令**：作者 2026-10-25 13:56「检索一下我之前对手机端ui优化的要求 还是有很多没实现的 一一对账」
- **对账范围**：作者 2026-10-19 20:58 → 10-25 00:36 六波共 **40 条**要求（台账见 §3 分组标题）
- **证据基线**：worktree `delegate-nebflow-76e86699`（main 同源 HEAD `efae698db`）代码 + 六份点名列报告 + git 考古 + 本批后台实跑 spec 快照（§5）
- **方法**：逐条读码取证（file:line 锚点）× git 提交考古 × 存量报告交叉核验；零改码

---

## 1. 结论（一段话）

40 条要求 **37 条已实现、3 条部分实现、0 条完全未实现**（落实率 92.5%）。作者「交代了也没做」的体感与代码库状态背离，有三个实证根因：①**交付链断裂**——10-25 00:36 作者真机所见的四条全坏（含渐变横条没删）根因是缓存击穿，代码早已在树、字节没到手机（cachebust 批 4df51ddcd 已修，本批实跑 mobile-topline-clear 3/3 绿实证）；②**反馈缺失**——加载无骨架卡（②27）、慢无提示，做了的优化在慢链路上不可感；③**验收面欠账**——本批实跑手机腿 spec 67 断言 60 绿/7 红，**7 红中 6 条是判据陈旧**（spec 断言停在旧语义：批⑨下滑换卡已被⑦令收起取代、图谱工具条已迁左栏、注入返回钮已被②17去重退役），真缺陷仅 CC6-4 触感缩放一条且是 CSS 腿。真正的功能缺口只有三处：桌面端会话头纵排被后续批改回横排（①7，待作者裁）、断连期 busy 排队项无补发轨（②26/⑥38 同根）、初次进会话无 loading 骨架（②27）。

---

## 2. 对账总表（40 条 × 状态 × 证据）

> 状态三态：✅已实现 / 🟡部分 / ❌未实现。`ML:` = src/main/resources/web/css/mobileLayer.css，`JS:` = src/main/resources/web/js。路径均自仓根。

### 第一波 10-19（mockup v2/v3 期，①1-①11）

| # | 要求 | 状态 | 证据 |
|---|------|------|------|
| ①1 | 手机端不显示上下文用量 | ✅ | 10-23 `249d31da0` 整链退役（手机+桌面双端）；JS/flowAgentPopup.js:558「context usage ring retired」；防复活钉 tests/remove-context-usage.spec.mjs:141-173（R1-R5）+ :197-233（注入 usageUpdate 不复活） |
| ①2 | 顶栏不显示时间 | ✅ | JS/mobile/headerController.js:16「时间戳本就不在顶栏…spec 断言守护」；守护 tests/mobile-v3.spec.mjs:330-331（`#header .header-time/.clock/time` count=0）；桌面顶栏同样无时钟（全 JS 仅 scheduled-task.js:74 有 toLocaleTimeString，非顶栏）——比作者令更严成立 |
| ①3 | 顶栏按钮+头像悬浮文字上方（无明确顶栏条） | ✅ | ML:141-152 `#header` 透明悬浮（background:none + pointer-events:none + z-40）；按钮/头像各自 glass-control（ML:174-193, 244-253）；内容直顶状态栏+顶让位 main.js:3707-3709 |
| ①4 | 点按钮向下展开液态动画（下拉宽度=按钮宽） | ✅ | `.mh-dd` width:40 恒定 ML:235；spring `cubic-bezier(.3,1.36,.45,1)` ML:255 + squash/stretch 关键帧 ML:260-273；子钮 blur 析出 stagger ML:334,347；列高公式 headerController.js:90-93 |
| ①5 | 悬浮玻璃右栏不挤布局（底部永远铺满） | ✅（形态演化） | r4 右缘活动栏走廊退役（ML:86-88 注释；`--mobile-bar-gutter` 全库无活规则）；`#main{margin:0}` ML:116-118；输入卡 `bottom:0+safe-area` ML:1539-1542；页面恒全宽 ML:1168-1175 |
| ①6 | muse 式布局：右栏转顶部圆钮+左侧同款各六按钮、右栏去聊天 | ✅ | JS/mobile/layoutController.js:28-35 `BAR_BUTTONS`=canvas/projects/graph/plugins/usage/settings（**无 chat**）；JS/mobile/floatController.js:35 FLOAT_IDS 五键+自建任务键+待答胶囊=左列六圆钮；两列壳 headerController.js:144-145 |
| ①7 | agent 头像居中名称在下（**手机+电脑**） | 🟡 | 手机✓：ML:158-171 `.header-center` 纵排居中（头像 52px 上、名称胶囊下）。**桌面✗**：chat.css:4904-4913 `body:not(.mobile-layer)` 恢复**横排**（28px 头像+名称在旁），系 10-20 UI修复批 `9542d73ff`「header收纳」有意改回，无作者裁定记录在案 → 需作者裁断：认横排=改台账口径；要纵排=列修复项 |
| ①8 | 登陆只留一按钮 | ✅ | JS/activityBar.js:770-785「单键制——三键社交选择面退役」，唯一 `#login-unified`；GitHub/Google 键退役记录 :598-602 |
| ①9 | 搜索聊天记录等按钮任务卡片式适配 | ✅ | JS/mobile/searchFace.js:67-111（P2 任务卡面）；ML:783-813「面板=任务浮层同语言」308×560 圆角16；结果行卡面化 ML:880-887；P3 定时任务同语言 ML:896-912 |
| ①10 | profile 面板适配+头像名称设置 | ✅ | JS/agentProfile.js:254-312 `buildProfilePane`（头像上传+名称就地编辑）注入合并面板 JS/memory.js:16,173；手机几何 ML:1036-1050（P5 308×560 顶挂） |
| ①11 | AskUser 附件按钮场景适配（选择题不显示附件钮） | ✅（卡面口径） | JS/askAttachments.js:62-68 `answerAttachmentsAllowed`=「至少一道开放题（无 options）」才挂，选择题/secret/dirPicker 静默无入口；挂载门 JS/chat.js:2628。注：输入条纸夹无 AskUser 联动（仅 busy 禁用），若作者口径含输入条纸夹则该半边未做 |

### 第二波 10-20（②12-②19）

| # | 要求 | 状态 | 证据 |
|---|------|------|------|
| ②12 | 会话面头像按钮悬浮顶层无 header | ✅ | 中簇悬浮 ML:158-171（flex 居中列）+ #agent-avatar-btn 玻璃悬浮 ML:174-193 + 名称胶囊 ML:204-222；#header 透明+不拦截 ML:141-152 |
| ②13 | 最上方毛玻璃渐隐 | ✅（口径已演化） | 原渐隐按 10-22 作者令「不允许任何渐变蒙层盖在气泡内容上」（`eb8832dd2` 消失点批）退役 → 现行=**硬裁切**：chat.css:36-38 `--chat-top-clip` mask 2px 硬切（锚 Header 顶缘，main.js:3698-3709）；顶带裙边退役 scrollUnderGlass.css:27-33；钉 tests/mobile-topline-clear.spec.mjs T1-T3 |
| ②14 | 左侧栏按钮优化 | ✅ | 与桌面做区分（作者令 10-21）：floatController.js:38-48 `PERSIST_ICONS` 图标常驻+角标计数（`95684a28d`）；ML:389-396 反转桌面 count=0 隐藏语义；`.mh-key` 36px 圆键统一 ML:316-353 + 徽标悬挂 ML:408-430 |
| ②15 | 右侧栏按钮样式去重 | ✅ | 右列键为自建 `mh-key activity-btn` 圆键（layoutController.js:56-61），不继承桌面活动栏样式；桌面右栏手机层整体退场 ML:99-106；active 态唯一手机语言 ML:440-442 |
| ②16 | 设置自动开 agent 面板问题 | ✅已修 | settings 打开链（JS/mobile/navController.js:194-204, 599-627）零 openMergedPanel 调用；入口仅两条（memory.js:192、agentProfile.js:415）；守护 tests/mobile-p12-settings.spec.mjs:122-139（`.profile-entry` count=0、页体首块=settings-modal） |
| ②17 | 右侧栏面板返回按钮去重 | ✅ | 双层返回退役：navController.js:625-634「不再向 .settings-modal-header 注入返回钮——页面壳已有一枚」；`#settings-modal-close,#usage-close{display:none!important}` ML:1498-1504；每页壳单枚 `.mobile-back-btn`（:126-143）。残留死代码 ensureInjectedBack（:687-706，无调用方，无行为影响） |
| ②18 | 项目面板垂直卡片下划（非左右） | ✅ | ML:1556-1578 `.team-scroll{flex-direction:column;overflow-y:auto}` 单列纵滚 + `.team-card.project-card{width:100%}`；左右滑语义仅保留在画板文档卡头 |
| ②19 | 「手机端进入怎么不是它专门的ui」（mobile-layer 生效面） | ✅ | 独立交互层（非响应式补丁）：index.html:659-662 注册 `js/mobile/bootstrap.js` + :90-96 双 css；bootstrap.js:30 激活判据 `matchMedia('(max-width:768px)')`，body.mobile-layer 总开关（ML 全部规则挂该类）；视口跨越双向拆装（bootstrap.js:81-96）；QA 探针 `window.__nfMobileLayer` |

### 第三波 10-21（③20-③24 卡片胶囊手势批）

| # | 要求 | 状态 | 证据 |
|---|------|------|------|
| ③20 | 卡片 header 毛玻璃胶囊悬浮顶层、底部文字从胶囊下穿过全显示 | ✅ | `cbaec5364` UI批⑨：胶囊 absolute top10 居中+999px 圆角+玻璃（split.css:660-714，固定 180×36 `9f345de57` :750-754）；内容全高穿底（CC0-4/5 契约，tests/canvas-cardhead-capsule.spec.mjs:209-212）；手机通栏变体 ML:1368-1376 |
| ③21 | 按住胶囊上下滑切换卡片（**手机端不要按住**） | ✅（语义随⑦令演化） | 手机无按压前置直滑（navController.js:283-284「上手势（无按压前置，作者令 C）」:314-414）。现行语义=10-23 ⑦令（作者后令取代本条下半向）：**下滑=收起整叠**（:402-404 closeCanvas 状态保留）、**上滑=换下一张**（:405 switchDocCard(1)）、横滑=关卡（:406）。⚠️ spec CC6-1/2 红为**判据代际差**：spec:371-375 仍断批⑨旧语义（下滑=下一张/上滑=上一张），代码已按⑦令改写——见 §4 |
| ③22 | 左右滑关闭卡片 | ✅ | 双通路：卡头横滑 navController.js:406（\|dx\|>64 closeCard）+ 侧/底缘带内滑 JS/mobile/canvasGestures.js:51-63,146-154（缘带 touch-action:none ML:1435-1445）；CC6-3 绿（spec:376-381） |
| ③23 | 右键引用/手机端长按引用 | ✅（CC6-4 红因待裁） | 桌面右键引用菜单 canvas.js:161-183 + :3178-3185（`isMobileLayer()` 排除手机）；手机长按 450ms→is-longpress+vibrate(8)+浮出 `.mobile-card-ref-btn`（navController.js:341-371）→ `quoteActiveCardRef`（canvas.js:3578-3581，与桌面同一 appendRef 通路）；触感 CSS ML:1375-1405。**CC6-4 所断言能力现码逐项在挂但 spec 红**——红因未定论，疑 CC6-1 塌层级联污染运行态或判据旧（§4） |
| ③24 | 毛玻璃渐隐只用于手机端最顶部（桌面去掉）+桌面消息顶部不超 header | ✅（口径再演化） | 桌面胶囊裙边退役 scrollUnderGlass.css:76-78（`body:not(.mobile-layer) …::after{display:none}`）；桌面消息不超 header=`933e8b742` ⑦（main.js syncTopReserve 裁剪线=header 下沿）；随后 `eb8832dd2`（10-22 作者追加令）**两端统一硬裁切**——渐隐裙边连手机端也退役，比原令走得更远（作者令在后且更严，非缺口） |

### 第四波 10-21 00:26（④25-④27 性能与可靠性）

| # | 要求 | 状态 | 证据 |
|---|------|------|------|
| ④25 | 手机端/他机连接慢→本地快+远端大提速 | ✅（缺真机 A/B 实测） | 三腿（`reports/20261024_003000_console-fastpath-batch-lan__delegate-nebflow-791ae42c`）：设备识别 WS onopen 跳 LAN 直连 + `POST /api/console-assets` 打包（隧道首开 45+请求→1，投影 2100ms→300ms 7×↓，报告 :28-35 实测表）+ ≥4MiB 大文件 LAN 直传；spec ConsoleFastpathSpec.scala:24-40、NfFileLanDirectSpec.scala。**仓内无真机端到端实测数字** |
| ④26 | 手机端发消息同步慢+丢失 | 🟡 | 已盖：离线队列持久化+重连补发（JS/input.js:736-751 f1-send-queue）、同 id 幂等（:277「#569 服务端见过⇒messageDuplicate 丢弃」）、10s beacon 对账+wakeResync（JS/messages.js:91,4987）、刷新丢消息收口（reports/20261023_094109_input-queue-restore:33 flipOfflineMark）。**残余：busy 排队项不带 offline 标记（input.js:740-741 两轨隔离设计）⇒ WS 断连期 busy 项无补发轨** |
| ④27 | 加载慢且无 loading 卡片/placeholder | 🟡 | 已有：分页腿 `.history-loader` spinner（JS/main/historyIndicators.js:6-13）+「加载更早」loading 文案（messages.js:3167-3180）。**缺：初次进入会话的消息加载无骨架卡/placeholder**（全 JS 无 chat 骨架组件） |

### 第五波 10-23（⑤28-⑤36 面板重组大批）

| # | 要求 | 状态 | 证据 |
|---|------|------|------|
| ⑤28 | agent 名称/头像跨机同步 | ✅ | src/test/scala/nebflow/neblink/AgentIdentitySyncSpec.scala:13-14（载荷构建/验签/LWW 收敛）；写路 agentProfile.js:105-107（PUT avatar）、:380-384（PUT display-name）；收敛探针 :127,141（meta-sha）+ agentIdentityChanged/重连钩子 :209 |
| ⑤29 | 名称修改落实进智能体记忆 | ✅（通道=系统提示） | IdentityPromptSpec.scala:29-51：identity 变更 SystemReminders（"current values authoritative, previous superseded"）+ `## Self` 段（AgentIdentityStore.selfSectionText，order 396）注入系统提示。注：落点是系统提示+变更提醒，非记忆面板条目——语义满足「智能体知道并使用新名称」 |
| ⑤30 | 记忆面板+智能体面板合并 | ✅ | `340f4a922` 批①：memory.js:63 `openMergedPanel(pane)` + :150 `ensureMergedChrome`；agentProfile.js buildProfilePane 注入 `.ap-pane` 分区；守护 mobile-p12-settings.spec.mjs:130-132 |
| ⑤31 | 点头像即换头像（去单独按钮+去重置按钮） | ✅ | agentProfile.js:262-265 预览盒本体即按钮（click→fileInput.click）；:9 头注「『重置头像』按钮**删除**」、:264「无独立上传/重置按钮；清除收敛走 meta 404 腿」；守护 mobile-p12:138-139（ap-btnrow 等三选择器 count=0） |
| ⑤32 | 手机端 header 头像→直进合并面板（去单独记忆按钮） | ✅ | floatController.js:9-10「记忆键退出左列——入口由 header 头像承担」+ :55-60 PANEL_FACE 头像腿（keyId=agent-avatar-btn→#memory-modal）；`#memory-btn{display:none}` ML:1520-1525；agentProfile.js:414-415 头像→openMergedPanel('agent') |
| ⑤33 | 手机端设置去 agent 面板按钮 | ✅ | ML:1699-1702「设置页 profile 入口行退役」；`syncProfileBlock`/`profile-entry` 全库 JS 零命中；agent 入口由 header 头像承接（agentProfile.js:414-415） |
| ⑤34 | 手机端各面板显示居中 | ✅ | 六面板同款 `left:0;right:0;margin:auto;width:min(308px,…)`：`.mh-tpanel` ML:533-541、`#search-modal` :802-810、`#reminder-panel` :901-910、`#daemon-panel` :940-949、`#bg-dropdown` :996-1004、`#memory-modal` :1036-1044（不用 transform，与 morphIn FLIP 链零冲突 :531-532） |
| ⑤35 | 后台任务+子智能体面板手机端优化（空白多、两面板独立） | ✅ | 两下拉归并单面板 JS/bgMergedPanel.js:1-18（分节+空节收签）+ tests/bg-merged-panel.spec.mjs 双键同面板钉；手机面紧凑化（「手机端空白太多」固定 560 高退役→内容高自适应）mobile-p6-bgstatus.spec.mjs:3-5,151,173-174 + ML:994-1029 |
| ⑤36 | 手机端 canvas 交互=底部弹出卡片：顶/尾下滑收起、左右滑关闭 | ✅ | bottom sheet：ML:1268-1291（#canvas-panel fixed top76/bottom6+safe、圆角20、z-170）+入场 mhSheetIn :1299-1302 + 页签条/桌面关闭钮退场 :1307-1310；手势：底缘带下滑跟手收起（canvasGestures.js:116-124,139-145）+ 卡头下滑收起（navController.js:402-404）+ 缘带左右滑关卡（:147-154）+ 内容区零劫持（ML:1447-1449）。⚠️ mobile-canvas-sheet T3 存量红疑判据旧（§4） |

### 第六波 10-25 00:36（⑥37-⑥40 作者自审四条）

| # | 要求 | 状态 | 证据 |
|---|------|------|------|
| ⑥37 | 仍跳转 nebflow.space（旧域） | ✅（主仓面） | `aca93765a` legacy-domain-sweep：四处点名+复核补漏 17 文件改指 mystand.space；防回归门禁 scripts/check-legacy-domain.mjs（白名单 12 条带豁免理由）+ ci.yml legacy-domain-gate job；**web 资产 grep nebflow.space=0 命中**。边界：website 跨仓项另账（docs/stand-website/20261021_121042_legacy-domain-sweep.md）；服务子域（neblink./auth./api.）按 REBRAND 裁定保留；EnrollGuard 双域=旧域安装仍拦（有意） |
| ⑥38 | 轮次结束已排队消息没自动投递 | ✅已修（残余同④26） | queue-idle-flush 批（reports/20261023_160706，35 断言，变异安全实证：stash 摘修复 R1-R3 全 FAIL）修「空闲不投递」四形态；queue-one-per-turn 批（reports/20261024_164337，34 断言含 X1/X2 双回归捕获器）修「一次性全发」；触发点 main.js:519,1340,2159,2190,2544,4238 + scheduleQueueDrain :537-555。残余=断连期 busy 项无补发轨（input.js:740，与④26 同根同修法） |
| ⑥39 | 手机端上方渐变横条没删 | ✅（码在树，真机待复验） | 渐变蒙层 10-22 已全退役（chat.css:36-38 硬裁切；scrollUnderGlass.css:27-33 顶带裙边退役；无 `.scroll-under-glass` 挂 #top-overlays）；钉 tests/mobile-topline-clear.spec.mjs T1（顶带零渐变）。作者 10-25 真机仍见=**缓存击穿**（reports/20261025_025804_mobile-debt-cachebust：四条全坏根因=交付链断，immutable-at-稳定-URL 半成品钉死旧字节），已修 `4df51ddcd` 内容寻址版本化 URL。**待作者真机刷新复验收口** |
| ⑥40 | 「手机端界面的优化我交代了也没做」（总控诉） | 🟡（=本对账答案） | 落实率 37/40=92.5%（§3）；体感背离三根因见 §1：交付链断裂（⑥39/①②③）、反馈缺失（④27）、spec 红项未清（§4）。**结论：不是「没做」，是「做了没到手上+欠账没扫」** |

---

## 3. 落实率统计

| 状态 | 条数 | 条目 |
|------|------|------|
| ✅ 已实现 | **37**（92.5%） | 其余全部；其中 4 条按作者后令口径演化（②13/③24 渐隐→硬裁切、③21 下滑→收起、⑥39 缓存击穿已修） |
| 🟡 部分 | **3**（7.5%） | ①7（桌面会话头横排 vs 要求纵排，待裁）、④26（断连期 busy 项无补发轨）、④27（初次加载无骨架卡） |
| ❌ 未实现 | **0** | — |

分波落实率：10-19 波 10/11（①7 部分）；10-20 波 8/8；10-21 手势波 5/5；10-21 性能波 1/3（④26/④27 部分）；10-23 面板波 9/9；10-25 自审波 3/4（⑥40=对账本身）。

---

## 4. 手机腿 spec 红项清单（main 存量，对应作者要求映射）

> 追踪器：reports/20261023_215233_spec-hygiene/REPORT.md（§3 申报表）；最新复跑记录：reports/20261025_131500_capsule-trackpad/REPORT.md:83,91-93（今日 13:15，25/28 同红）+ reports/20261025_113000_capsule-fixes/REPORT.md:63（stash 对照实证=HEAD 既有，非近期批引入）。

**本批实跑快照（2026-10-25 14:2x，main 同源 worktree，Playwright 11 件 39 test：35 绿 / 4 红）**：
- ✅ 转绿（spec-hygiene 存量红已自愈）：mobile-canvas-sheet **5/5 含 T3**、mobile-p3-reminder 5/5、mobile-p8-projects 3/3（启动崩溃未复现）、mobile-p12 3/3、mobile-topline-clear 3/3（⑥39 码面实证绿）、mobile-v3 7/7（含 T5 顶栏无时间守护）、bg-merged-panel 绿。
- 🔴 现 red 4 条，**全部初判为判据陈旧**：mobile-p9-graph T1/T2/T3（断言 `.wiki-toolbar-btn` 在场——控制面已按 10-21 作者令 `8a2c703f9` 迁左栏，图谱页不再有工具条玻璃钮）+ mobile-p11 T3（断言 `.mobile-back-injected` 注入返回钮——该注入已被②17 返回钮去重批退役为无调用死代码，navController.js:625-634/687-706；页面壳 `.mobile-back-btn` 才是现行单点）。

| spec 红项 | 现象 | 对应作者要求 | 本批判读 |
|---|---|---|---|
| canvas-cardhead-capsule **CC6-1** | 直滑下滑≠换下一张（本批实跑仍红：`title=bravo.md` 不变） | ③21 | **判据代际差**：spec:371 仍断批⑨「下滑=下一张」，代码已按 10-23 ⑦令改写「下滑=收起整叠」（navController.js:402-404 实读确认；实跑 title 不变恰是塌层+状态保留的表现）。**不是功能回归，是 spec 未随⑦令换代**；不修则永远红 |
| canvas-cardhead-capsule **CC6-2** | 直滑上滑≠换上一张（实跑：`title=alpha.md`） | ③21 | 同上：⑦令保留上滑=**下一张**（:405 switchDocCard(1)），实跑 bravo→alpha 恰是现行为正确执行；spec 断「上滑=上一张」为旧语义——判据需重钉 |
| canvas-cardhead-capsule **CC6-4** | 长按触感断言失败（本批实跑：`feel=true scale=1.000 btn=true(引用)`） | ③23 | **功能链路全绿，唯触感缩放腿失配**：is-longpress 类在挂、引用钮浮出成功、CC6-5 点击引用+收臂全过——失败的只是 `scale<0.999` 断言（computed transform=恒等）。即 CSS 触感规则（ML:1375-1405 `--head-scale` 链）与现选择器/状态组合失配，属**视觉手感缺陷**非功能缺失；修复=CSS 一处（或断言改锚 head 本体类）。疑 340f4a922 面板批重写卡头 CSS 后未重钉（capsule-fixes :63 同判） |
| mobile-canvas-sheet **T3** | 内滑关卡 mobile-layer 未生效 | ⑤36 | 现码缘带实现双侧在挂（canvasGestures.js:146-154 + ML:1435-1445），spec-hygiene 记录早于该实现换代；疑存量判据 |
| mobile-p3-reminder（5 test） | 存量红 | ①9（P3 定时任务卡面） | spec-hygiene :104 存量申报，红因未重验 |
| mobile-p8-projects（3 test） | 存量红 | ②18（项目纵滚） | 兼有环境级启动崩溃史（evidence/20261021_130200_canvas-cardhead-capsule/报告.md:55「mobile-p8/11/12 启动崩溃=HEAD 逐位一致」） |
| mobile-p9-graph（3 test） | 存量红 | （P9 图谱推送页） | 红因=`.wiki-toolbar-status` 面退役（spec-hygiene :104）——**判据陈旧**，面板已删 spec 未跟 |
| mobile-p11-usage（3 test） | 存量红 | （P11 用量推送页） | 兼有环境级启动崩溃史 |
| 邻族挂账：canvas-capsule-ring CR2-5 | 材质断言挂账红 | （桌面胶囊材质，非手机腿） | spec-hygiene :88、capsule-fixes :58；列此仅备查 |

**结论**：本批实跑（Playwright 11 件 39 test 35 绿/4 红 + node-runner capsule 28 断言 25 绿/3 红）证实——**全部 7 条现 red 中 6 条是判据陈旧/代际差**（CC6-1/2、p9×3、p11-T3，且 spec-hygiene 存量表的 p3/p8/canvas-sheet-T3 已自愈转绿），**真缺陷仅 1 条且是视觉手感腿**（CC6-4 触感缩放 CSS 失配，功能链路全绿）。手机腿验收面欠账=「交代了感觉没做」的第二根因，且大半欠的是**判据换代**而非功能。

**本批实跑快照（capsule node-runner，2026-10-25 14:3x）：25 PASS / 3 FAIL（CC6-1/2/4），CC6-0/3/5 与 R0/R1 全绿；CC6-4 细节 `feel=true scale=1.000 btn=true(引用)`——功能在、触感缩放不在。**

---

## 5. 实机验证面（作者三问，直接读码结论）

**① mobile-layer 判定条件（什么触发手机 UI）**
- 判据=**纯视口宽度**：`matchMedia('(max-width: 768px)')`（bootstrap.js:30），**无 UA 嗅探、无触摸检测**。≤768px 视口即激活 `body.mobile-layer`+`html.mobile-html-layer`（bootstrap.js:40-44），跨 768px 双向拆装（:81-96）。推论：**桌面窄窗口也会进手机层**（设计如此，注释「桌面窗口永远不会命中」指桌面常规尺寸）；手机横屏>768px 会退回桌面层。诊断探针 `window.__nfMobileLayer.active/version`（:99-102）。注册点 index.html:659-662。

**② 渐变横条 CSS 现状**
- **渐变蒙层零存留**：顶带裙边退役（scrollUnderGlass.css:27-33，#top-overlays 不再挂 .scroll-under-glass）；#header 手机态无渐变底（ML:141-144）；聊天顶= `--chat-top-clip` 硬 mask 裁切 2px 过渡带（chat.css:36-38，消失点锚 Header 顶缘 main.js:3698-3709）；`.scroll-under-glass` 现仅挂五面浮层（reminder/daemon/settings/memory/rules）。10-25 00:36 作者真机所见横条=旧字节缓存（cachebust 批根因归因+已修 `4df51ddcd`），**非 CSS 仍在**。

**③ 顶栏时间/上下文用量现状**
- 时间：手机顶栏**无时间节点**（headerController.js:16 注释+mobile-v3.spec.mjs:330-331 守护断言）；桌面顶栏亦无时钟。
- 上下文用量：环已 10-23 整链退役（`249d31da0`，双端），全 web 资产 grep ctx-ring/ctxUsage=0；防复活钉 remove-context-usage.spec.mjs R1-R5+A 组。用量**页面**（P11/usage.css）为独立功能面，非顶栏环，不在该令范围。

---

## 6. 修复分组建议（作者拍板后派修）

### P1 快赢（半天级，纯收口）
1. **④26/⑥38 残余**：busy 排队项补发轨——input.js:740 两轨隔离处给 busy 项补 offline 标记或断连重排轨（一处判据+一条 spec）。
2. **④27 骨架卡**：初次进会话 loading 骨架/placeholder（historyIndicators 同语言复用）。
3. **spec 判据换代四连**（本批实跑实证全部为陈旧判据）：CC6-1/2 重钉到⑦令语义（下滑=收起/上滑=下一张）、p9 图谱判据跟 8a2c703f9 左栏迁移（`.wiki-toolbar-btn`→现行控制面选择器）、p11-T3 判据跟②17 去重（`.mobile-back-injected`→页面壳 `.mobile-back-btn`）——判据改完 7 红中 6 条即刻转绿。
4. **CC6-4 触感缩放修复**：功能链路实跑全绿（feel 类+引用钮+点击引用+收臂全过），唯 computed scale=1.000——ML:1375-1405 `--head-scale` 触感规则与现选择器/状态组合失配（疑 340f4a922 重写卡头 CSS 后失配），CSS 一处修复+断言保留。
5. **⑥39 收口**：作者真机刷新复验（cachebust 已修+本批 topline spec 3/3 绿，只差真机确认）。

### P2 面板与台面（一至两天）
6. **①7 裁断落地**：作者定「桌面会话头纵排 vs 横排」——认横排则台账改口径；要纵排则改 chat.css:4904-4913 一处（手机面已符合，零联动）。
7. ~~p3/p8/p11 存量红~~：本批实跑已自愈（p3 5/5、p8 3/3 绿，启动崩溃未复现）——spec-hygiene 存量表相应条目可在下次卫生批销账。
8. **死代码清理**：navController.js:687 ensureInjectedBack（六件⑤退役残留；p11-T3 换代后即可删）。

### P3 手感打磨（择机）
9. **真机 A/B 实测**：④25 提速真机端到端数字（隧道/LAN 各一轮首开耗时），补报告空缺。
10. **输入条纸夹×AskUser 联动**（①11 半边）：若作者口径含输入条，AskUser 激活期纸夹显隐对齐卡面判据。
11. **③21 手感确认**：⑦令「上滑=下一张、下滑=收起」单向换卡是否符合作者手感预期（spec 已按此换代即可锁死）。

---

## 7. 未决项与假设

- **实跑复核已完成**：mobile spec 族两路实跑（Playwright 39 test + node-runner 28 断言），结果已入 §4；CC6-4 隔离定性未做（现判读=CSS 触感腿失配，P1-4 派修时顺手验证）。
- **假设**：①11 作者口径=AskUser 卡面附件钮（已实现），输入条纸夹不在该令内（列为 P3-10 待裁）；⑤29「记忆」按「智能体使用新名称」语义验收（系统提示通道），若作者要的是写入记忆面板条目则需另派。
- **报告归位**：reports/20261023_182241_panels-mobile-ux/ 至今 **untracked**（主仓 git status 实证），批报告未入库——建议随本批一并归档，避免验收链断证。
- 台账原文以作者六波逐字要点为准（审计令 §「作者原始要求台账」），本表条目编号沿用该台账 ①-⑥ 组 1-40 序号。
