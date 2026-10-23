# spec-hygiene 批 —— 存量红 spec 判据考古与换代（HEAD 即红清扫）

- 批时间：2026-10-23 晚
- 分支：`delegate/delegate-nebflow-2637ab1d`（worktree `delegate-nebflow-2637ab1d`）
- 红线自核：**零产品代码改动** —— 全部改动落在 `tests/*.spec.mjs` 测试判据/harness 与
  `scripts/check-ime-guard.mjs` 的 allowlist 位置元数据（CI lint 工具簿记，判定逻辑 =
  锚文本，行为零变化）。发现的疑似产品真问题一律申报不动（见 §3）。
- 方法：命名两处目标考古修复 → 全量 web spec 楼层清扫（node-runner 逐件 +
  @playwright/test 全件 workers=1）→ 逐件重跑定谳（排掉共享检出/并发会话的瞬态干扰）
  → 每处考古到作者令/批次 commit → 判据换代或申报。

## 1. 命名目标（任务书两项）

### 1.1 `tests/mdscroll-toc-scroll.spec.mjs` —— `#activity-bar` hidden 超时
- **考古**：mobile-ui-refactor v3 后壳有双形态。≤768px 视口命中
  `js/mobile/bootstrap.js` 的 `MOBILE_MQ`，激活 `body.mobile-layer`，桌面壳整体退场
  （`css/mobileLayer.css`「桌面壳退场」段：`body.mobile-layer #activity-bar { display:none }`，
  作者令移动端 v3 面板合并批形态）；桌面 md 链（`.canvas-tab-pane.active .canvas-md-scroll`）
  在该层不存在（探针实证）。旧矩阵 375×812 腿钉的是手机层诞生前的「窄桌面链清册」——
  判据前提已不成立，干净 HEAD 即红。
- **换代**：
  - boot 门：`#activity-bar`（可见等待）→ `body.mobile-layer, #activity-bar` 双形态逗号
    选择器（桌面腿只有后者能命中，无假绿面）。
  - 矩阵 375×812 腿 → 新形态钉 **R0-c**（mobile-layer 激活 + `#activity-bar`
    display:none 退役防复活），不进桌面链残量清册；清册判据 R0-b 收敛到桌面壳腿
    （>768px，条件不变）。泄露机制判据 R1–R5 零改动。
- **结果**：30 PASS / 0 FAIL（exit 0）。

### 1.2 `tests/legacy-ui-retire.spec.mjs` T2/T3 —— plugins 页签可见性断言脱节
- **考古**：canvas-stack v5（2026-10-22 作者令）**桌面态标签行退役** —— 常态头只留居中
  标题胶囊（`.canvas-title`）；标签条/关闭钮 DOM 保留（数据模型 + 手机层引用），桌面由
  `split.css` `body:not(.mobile-layer) .canvas-tab-bar { display:none }` 隐藏
  （面板合并批 REPORT 开项⑤已申报干净 HEAD 同挂）。探针实证：boot 落点 =
  `.canvas-tab-pane[data-tab-id="plugins"].active` + `#plugins-content` 可见 +
  `#canvas-title-name` = 标签模型名；`#canvas-tab-bar` 0×0。
- **换代**：
  - T2/T3 boot 落点：`#canvas-tab-bar .canvas-tab[...]` 可见等待 → pane active +
    `#plugins-content` 可见 + 胶囊标题随动（`#canvas-title-name` === 标签模型名）。
  - 旧「标签条可见」判据翻转**退役脸防复活钉**：桌面壳上 `#canvas-tab-bar` 保持
    `display:none`（DOM 为数据模型/手机层保留面，桌面不得复活为可见条）。
  - 标签集合对比仍走标签条 dataset（元素在、读数不受隐藏影响）——零改动。
  - T3 设置模态：面板合并批后居中模态覆盖右下角钮区，带模态点击
    `#canvas-toggle-btn` 已不可行 → 走模态文档关闭接缝（Esc → sidebar.js
    `closeSettingsPanel`）后再验 canvas 开合。
- **结果**：spec 4/4 + 关联 static 全绿。

## 2. 楼层清扫判据换代清单（旧判据 → 新判据 → 依据）

| # | spec | 旧判据 | 新判据 | 依据（作者令/批次） |
|---|------|--------|--------|---------------------|
| 1 | mdscroll-toc-scroll | boot 门 = `#activity-bar` 可见；375 腿跑桌面链残量清册 | 双形态 boot 门；375 腿 = 手机层形态钉 R0-c（+桌面活动栏退役防复活） | mobile v3 / 面板合并批（mobileLayer.css 桌面壳退场段；bootstrap.js MOBILE_MQ） |
| 2 | legacy-ui-retire T2/T3 | 标签条里 plugins 标签**可见** = boot 落点 | pane active + 内容面可见 + 胶囊标题随动 + 标签条 display:none 防复活 | canvas-stack v5 桌面标签行退役（split.css；index.html 叠卡批注记） |
| 3 | ime-guard G4-3/G4-4 | allowlist 承载 `input.js:1175-1176`（keyCode 裸判定） | 承载 `input.js:1442-1443`（view.composing 弃用绑定对） | 旧 keyCode 裸判定已被 imeGuard 元素级判定取代整段不在；绑定对随 input.js 增量漂移（工具按锚文本定位，行号簿记 + spec 钉双换代） |
| 4 | friends-dev-default-on | 空 config ⇒ dev 默认开，Messages/Contacts 入口回挂 | 空 config ⇒ 封存缺省：入口/面板**缺席**（防复活）；显式 true ⇒ 回挂 + W1 顺序契约 + 可进 | friendseal 批作者裁定 ④（2026-09-25，featureFlags.js friendsEnabled：缺席 = 封存 = 默认 OFF）推翻 2026-09-10「dev 默认开」前提 |
| 5 | slash-clear-compact S1b | 门键 = `nebflow_slash.enabled` | 门键 = `stand_slash.enabled` | 品牌换代 `nebflow_` → `stand_`（branding.js `key()` = `${brand.lowerName}_<suffix>`；nebflow_ 降级 legacy 兼容命名空间） |
| 6 | friend-cache-latency ⑨-6 | 裸键迁移目标 = `nebflow_fm_*` | 迁移目标 = `stand_fm_*` | 同上（branding.js MIGRATION_MAP 目标随 key() 走） |
| 7 | imgmsg-send E/B② | `.fm-retry` 描边 = `--color-error` rgb(244,67,54) | 描边 = `--status-error-icon` rgb(214,69,69) | ui-status-redesign 批（盘点 #44）token 搬迁（friends.css .fm-retry 注释；暗档随 token 提亮） |
| 8 | session-gate ①/③/④ | 铸票 403 ⇒ sessionGate 横幅 + 自愈 reload | 铸票 403 ⇒ **静默退避**（防复活钉）；可见面 = 统一入口 `noteAuthRejected`（+reload/冷却腿不变） | authgate 批（2026-10-22）：铸票 401/403 撤出 sessionGate（修「铸票一发 401 整页重登」登出级 bug，nfTicket.js 注记）；横幅面本身未动 |
| 9 | unifyfix-eight-points ⑤/R5c | 描述编辑 = 常驻只读文本条 `.fm-device-desc-text` 点击换 input + 裸 Enter 提交 | 窗头行内小键 `.fm-device-desc-btn`（aria-expanded）点击展开面板 + `.fm-device-desc-save` 提交；旧行内渲染点防复活 | devrow 批（作者 2026-09-16 截图令）+ R2 更正令：行内编辑整体替换（messages.js buildDeviceDescRow/Toggle 注记） |
| 10 | resend-dedup 场景 B | 重连返回**之后**才挂终态行再点重试 | 等重连初载替换落定（sleep 800ms）再挂行（harness 时序，与真看门时序一致） | main.js onReconnect getHistory ⇒ historyPage 初载替换聊天区（spec A 腿既有注记同款机制）；改前行被历史替换冲掉 |
| 11 | card-persist-fe-search ③ | 零 console.error（harness 静态树无 `/agents/<name>/agent.json`） | mock `**/agents/*/agent.json` ⇒ `{}`（rootName 显示名探针缺名回退态） | rootName/identity 批新面：引擎生产伺服该静态路径，harness 树本就没有——harness 缺口，非产品问题 |
| 12 | canvas-html-interactive | `frame.click` 直点 iframe 内顶右钮 | boot 后 `#deck-fab{display:none!important}` 让位（仅本 spec 视图，真实鼠标事件路径保留） | canvas-stack v5 批后 deck FAB（fixed top10/right12）悬浮 iframe 顶右工具带拦截点击（Playwright 命中日志实证）；本 spec 主角是 iframe 内交互链，FAB 无关 |
| 13 | canvas-office-viewers | —（harness 读 `.nebflow/evidence/...pptx`） | worktree 预置未跟踪 evidence fixture（gitignored，不入提交） | 证据目录不随 worktree 分发（env 供给，同 pptx-mvp 批既有惯例） |
| 14 | uifix-quad ① PANEL-1/2/3 | 头像点击开 `#agent-profile-panel`（visible·display:flex）+ `.ap-close` X 关 | 头像点击 `openMergedPanel('agent')`：#memory-modal.show + overlay.on + `.ap-pane`；**背板点击**关（agent 窗格动作行设计性隐藏，modal.css 418）+ Esc 关 | 面板合并批作者令 ①（agentProfile.js 头注：独立面板退役，分区注入合并面板）；旧 `.ap-close` 随面板退役 |

### 2b. pw 楼层换代清单（全量 pw 复扫 599/48 后，属本命名家族的 5 件）

| # | spec | 旧判据 | 新判据 | 依据（作者令/批次） |
|---|------|--------|--------|---------------------|
| 15 | subagent-panel-open-geometry | `dropdownOpen` 轮询幽灵 `#bgagent-dropdown`（恒 hidden）⇒ indicator 连点 4 次（每次 toggle 真面）必失败 | 钉真面 `#bg-dropdown`：已开幂等返回 / 未开点一次 indicator → `waitForSelector('#bg-dropdown:not(.hidden)')`（照 delegate-ui-twolayer 换代口径） | 面板合并批 ⑥：两面板归并为 #bg-dropdown 合并载体，#bgagent-dropdown 留盘幽灵（恒 hidden，手机层引用面）；行/几何采样器 `.bg-task-row`/`.flow-agent-overlay` 本已是新形态零改动 |
| 16 | subagent-panel-cancel-realtime | openPanel 等 `#bgagent-dropdown:not(.hidden)`（幽灵面永不可达） | 同款改等 `#bg-dropdown:not(.hidden)` | 同上 |
| 17 | plugins-panel-itemize ⑤ | 等/读 `#canvas-tab-bar .canvas-tab[...]` **可见** | pane active（`.canvas-tab-pane[data-tab-id].active`）+ 标签 dataset 读数（隐藏不影响）+ `#canvas-tab-bar` display:none 退役防复活钉 | canvas-stack v5 桌面标签行退役（同 §1.2 家族） |
| 18 | plugins-panel-pure-plugins ⑥ | 同上可见性钉；404 腿鼠标点隐藏标签切回 plugins | pane active 同款；切回腿改程序化 `.click()`（JS 触发激活 handler，display:none 元素不可鼠标点但可程序点）+ 等 `.canvas-tab-pane[data-tab-id="plugins"].active` | 同上 |
| 19 | sidebar-restructure T1/T3 | T1：top 顺序表钉 2026-09-22 形态（枚举含全部子节点）；T3：读 `nebflow_avatar_cache` + 12s 收敛窗 | T1：顺序表收编 `wiki-btn`（wiki-graph 批新增，projects 与 agents 间）+ 枚举滤除惰性 `<script>` 同辈（brand-switch-console 批 2026-10-15 pre-paint brand slot apply——非交互结构成员，legacy 复活物均为 button/div，滤除不削弱判据）；T3：键换代 `stand_avatar_cache`（branding key() 命名空间）+ 收敛窗 12s→40s + `test.slow()`（perf 批 6b6704a17 把 STATUS_POLL_MS 节流 10s→30s，收敛=一拍轮询） | T1：wiki-graph 批 + brand-switch-console 批；T3：品牌换代批（同 §2 #5/#6 家族）+ perf coalesce 批 6b6704a17 |
| 20 | social-panel W16 B-5/B-6 | 「375 下 `#social-btn` 可点（elementFromPoint 命中）」+ open-state 对话框几何（B-1..B-4/B-6：开合溢出/模态贴边/控件裁剪/卡溢出/in-card QR） | 防复活钉：手机层上桌面社交入口随活动栏整体退役——B-5 = 等 `body.mobile-layer` 落定后入口恒 0×0 且 elementFromPoint 不命中；B-6 = 栏 display:none 保持 + #social-overlay 不亮 + #social-modal 无可见面（computed display）。桌面视口对话框几何由 W 块 1280 配置腿覆盖，不随换代减面 | mobile-ui-refactor v3（≤768 ⇒ body.mobile-layer；mobileLayer.css「桌面壳退场」段 `body.mobile-layer #activity-bar { display:none !important }`）——旧判据钉的是手机层诞生前的桌面壳形态；干净树实证 afterSuite 曾因此 TimeoutError 崩溃（375 无入口可开） |

pw 复扫后本命名家族全清：subagent×2（6/6）+ plugins×2（14/14）+ sidebar-restructure（6/6）逐件复跑全绿。

注：uifix-quad 余下唯一失败 = WHEEL-5（deck 滑行动量 26/1）——PANEL 修复使 spec 首次跑穿全腿，WHEEL-5 为 canvas-stack/deck 物理面漂移，随 §3 申报。imgmsg-send B/② 帧读数为预存竞态（修复后 3 跑 2 绿），同申报。

## 3. 申报项（判据/行为疑似漂移，未动 —— 红线：发现疑似产品真 bug 申报不动）

以下为全量楼层清扫发现的**存量红**（全部经干净 HEAD 等价基线判定：本批零产品改动，
故非本批引入；多数有早期批次考古签名）。逐条列证据，交由对应批次作者裁：

| spec | 失败签名 | 考古指向 | 建议裁断 |
|------|----------|----------|----------|
| canvas-capsule-ring | CR2-5 材质≠chat header frosted（34/1） | 胶囊六件批 REPORT §F **已申报判例**（主批明确不动，不改 chat header） | 判例挂账，无需再动 |
| canvas-cardhead-capsule | CC6-1/2/4 直滑换卡/长按触感（25/3，确定性复现；同批 REPORT 16:38 时 28/28） | 16:38 后批次波及手势面（panels-mobile-ux 18:22 或其紧邻批次） | 手感面回归 or 环境，需作者裁 |
| canvas-preview-nav-parity | 修②×4：代理 URL 导航后 pane iframe 销毁 + "Rendering was stopped" | viewers/html.js 递归嵌套守卫（:697）与 修② 代理导航契约冲突——今日 html.js 被 三症状批/胶囊修正批改过 | 守卫与代理导航的契约仲裁（产品面 or 判据面） |
| mvp2-device-client | R4b 一次点击 attDownload=2（期望 1） | 附件下载链疑似双发（href + fetch 腿） | 产品面双发核对 |
| uidefect-thinking-stream-image | G5 C1/C2 ×4：图已渲染（pixelVariance>0）但 elementFromPoint(img中心) = DIV 覆盖层 | 聊天流图新增覆盖层 chrome（哪批未坐实） | 覆盖层是否设计内；判据 hitIsImg 是否换代 |
| devpanelname-panelrows | 设备窗 `.fm-modal .fm-msg` 零行 + 离线条在场 | neblink 消息面改走实时通道（fmw3 ⑨ 消息缓存+增量同步批），harness 只 mock `/ws`+REST | harness 需补实时通道 mock（深，非本批范围） |
| friend-cache-latency（⑨-6 外） | — | 已修（§2 #6） | — |
| friend-chain-ui | 自建服务器 `goto` 失败（两次独立复现） | 服务器自身启动/响应异常（非端口冲突——8976 空闲） | 待深查（疑似 harness 内部错） |
| imgcard-click-safari-compat | B2/B3 字节 200/size=2、C3/C4 viewer、D2/D3 铸票 401 | auth stageD 批（退 token query/legacy cookie 轨）重塑票据/会话门 rail，spec  harness 与旧轨逐字对齐 | harness 需按新轨重建（深） |
| cardwin-session-scenarios | S2-1/2 relay 形态登录重定向未发生（S3 直连模态腿通过） | 同上（auth stageD / authgate 批 reshaped relay 鉴权握手） | 同上 |
| content-i18n | `DS.agents[0].id` TypeError（seed manifest 无 agents 条目） | a5b716d55：seed agent tree 退役（BuiltinAgents 代码定义四员）⇒ spec 的 seed 树锚源消失 | spec 锚源改 BuiltinAgents 定义（需内容面重设计） |
| taskbar-flow-overlap | T1/T2 任务条与聊天视口大面重叠（236208px²） | 任务条锚定模型疑似被近批改动 | **疑似产品回归，优先裁** |
| uifix-quad | WHEEL-5 deck 滑行动量（26/1；PANEL 修复后首次跑穿全腿才可见） | deck/环物理面近批漂移 | 手感面仲裁（同 CC6 族） |
| imgmsg-send | B/② 帧 naturalWidth 读数竞态（修复后 3 跑 2 绿） | 预存 harness 竞态（非本批引入） | 已知 flaky，负载下偶红 |
| friend-cache-latency（M6 外其它腿） | — | 已全绿 | — |
| mergequeue-pos ×6 | harness 走 per-project Flow Map 就地视图（`GET /api/projects/<n>/flow-map` → `.flowmap-view-body .fm-node`），视图不存在 ⇒ 卡面永不出现 | **载体被作者令整块退役**：flowmaptab-retire（#1228 两裁② 停用清单面 1，2026-10-06）+ 视图重设计实施批（2026-10-21 入口统一）：项目卡标题改指 delegate-chain 全景视图，fm-node 卡面迁 stub 读取口（delegateChainStub.__setFixture）+ L1 链视图（`.delegate-flowmap-host`） | 位次数字判据本身已在 delegate-ui-twolayer 绿面钉住（mergeQueuePos.position）；几何腿（徽标不截断/不出卡）待作者裁定新载体（delegate-chain L1）后重钉——** harness 重建属中等工程，超出本批最小改动红线** |
| pw 楼层其余 ~33 腿（22 文件：dialog-unify×3 / explorer-nebflow-dir×2 / history-replay-cards / micorb-presets×4 / mobile-canvas-sheet / mobile-p3/p8/p9/p11 / modelpanel-roles / msgqueue-recall / onboarding-name-header / pop-artifacts P7 / projcreate-fab×2 / project-create-panel T9 / project-panel-archive×2 / search-realshape×3 / secrets-readback R2/R7 / stream-ux A1 / tool-heartbeat-timer×2 / turn-single-badge / uicopy-copy×2 / wiki-graph-multivault / wiki-graph-ux §F / askuser-history-attach N1） | 各腿签名不一（枚举见 pw 日志 /tmp/pw-suite2.log） | 逐条快考：askuser N1 = harness 双树差 `opt-other` 类（harness 内部，非产品面）；dialog-unify + micorb-presets = 预设/orb 面板面改名（同一家族，非本命名批次）；mobile-p9 = `.wiki-toolbar-status` 面退役（wiki-graph 批）；mobile-canvas-sheet T3 = 内滑关卡在 mobile-layer 未生效（疑似手势回归 or 判据，需 mobile 批作者裁）；其余为聊天流/搜索/密钥/wiki 面近批漂移 | 全部属**其它批次**存量红（本批零产品改动，非本批引入）；各归对应批次作者裁 |

## 4. 环境门限项（设计如此，不判为红）

- `session-rail.spec.mjs` / `clientconn-wake.spec.mjs`：需要 `NEBFLOW_URL` 指向**隔离**
  网关实例（session-rail 会轮换 token/吊销会话，无默认是故意的）。
- BASE_URL 族 7 件（smoke / askuser×4 / askuser-attach / workspace-picker /
  askinput-off-pending-text / askuser-lockline-history）：默认指向作者**在跑实例**
  （8080 实测 OPEN）——跑它们 = 在生产实例上建真实会话，副作用禁手，本批不跑。
- `queuepos-detail.spec.mjs`：模块级硬门（缺 `target/queuepos-payload.json`，需先
  `sbt testOnly nebflow.core.project.MergeQueuePositionSpec`）——已另行补跑（见 §5）。
- `social-panel.spec.mjs`：~~SCOPE 守卫 spec（工作树 vs HEAD 白名单），脏工作树必红——
  合并点 settle 后树干净时即为绿~~ **已换代修复**（§2b #20）：红因与守卫无关——
  mobile v3 后 375 配置下桌面社交入口/对话框面整体退役（作者令），W16 B-5/B-6
  判据换代为防复活钉；干净树终验 139/139 绿（§5）。

## 5. 楼层结果汇总

- node-runner（69 件）：初扫 42 PASS / 27 FAIL / 7 SKIP(env)；逐件重跑定谳 + 换代后：
  **本批修复 14 件全绿**（§1×2 + §2 除 canvas-office-viewers 外 12 件全数转绿），
  遗留申报 12 件（§3），瞬态 1 件（msgmenu 独立复跑即绿），env 门 2 件。
- @playwright/test（110 件）：首扫被 queuepos-detail 模块级硬门整批阻断；剔除硬门 +
  env 门后 104 件（658 腿）单调用 workers=1 全量复扫：**599 passed / 48 failed**
  （33.6min，日志 `/tmp/pw-suite2.log`）。48 失败中 5 件属本命名家族已换代复绿
  （§2b：subagent×2 6/6、plugins×2 14/14、sidebar-restructure 6/6），mergequeue-pos ×6
  载体被作者令退役（§3），其余 ~33 腿为其它批次存量红（§3 归批）。
- queuepos-detail 补跑：sbt 已产出 `target/queuepos-payload.json`，模块级硬门解除后
  pw 全量复扫中**未再失败**（green）。
- social-panel（§2b #20）：SCOPE 守卫 + 换代后**干净树终验 139/139 checks green，
  exit=0**（`/tmp/sp-check` @ a64c16f0b 实证，临时 worktree 已清）。
- 复验命令示例：`node tests/<spec>` / `npx playwright test tests/<spec> --workers=1`。
- worktree 注：`node_modules` 以符号链接借用主检出（不进提交）；`.nebflow/evidence/
  20261006_pptx-mvp/poc/decks` 按 pptx-mvp 批惯例预置未跟踪 fixture（gitignored）。

## 6. 红线自核

- 产品代码：**0 文件改动**（`git diff main...HEAD -- src/ scripts/ 除 check-ime-guard
  allowlist 元数据` 为空；该元数据为 CI lint 簿记，判定逻辑锚文本，行为零变化）。
- 最小改动：每处换代只动判据/接缝选择器/harness 时序/mock 补齐，判据语义（为何红、
  钉什么行为）在注记中逐条存档。
- 疑似产品问题：§3 全部申报未动。
