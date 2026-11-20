// zh-CN.js — Chinese (Simplified) locale for Nebflow

export default {
  // === index.html static text ===
  'html.lang': 'zh-CN',
  'nav.newAgent': '新建智能体 — 添加一个新的对话智能体',
  'nav.settings': '设置 — 配置提供商、模型和偏好',
  'sidebar.sessions': '会话',
  'sidebar.newFolder': '新建文件夹 — 创建会话分组',
  'sidebar.toggle': '收起或展开会话栏 — 隐藏或显示左侧会话面板',
  'sidebar.edgeToggle': '展开或收起侧边栏',
  'sidebar.settingsTitle': '设置',
  'sidebar.folderDelete': '删除文件夹',
  'activity.files': '文件',
  'activity.messages': '消息',
  'activity.contacts': '联系人',
  'activity.projects': '项目',
  'panel.explorer': '文件浏览器',
  'panel.messages': '消息',
  'panel.contacts': '联系人和设备',
  // diff 拆面板批（2026-10-22）：左栏「变更文件」面板（委托 diff 摘要条唤起）。
  'panel.diff': '变更文件',
  'panel.diffEmpty': '尚未打开 diff。点击委托交付消息上的变更摘要查看文件列表。',
  // 二次加载清剿批（2026-10-24）：diff 打开链的装载态 + 失败可见反馈（作者令
  // 「任何取数/渲染路径必须有 loading 态与失败可见反馈，禁止静默无响应」）。
  'panel.diffRendering': 'diff 渲染中…',
  'panel.diffOpenFailed': '看板打开失败——点行重试。',
  // #27 Project 标签页 + Flow Map 标签页（方向调整：Canvas 标签页形态，参考 team 面板）
  'project.workspaceOpen': '开放',
  'project.workspaceReserved': '等待合并',
  'project.workspaceRunning': '工作中',
  'project.workspaceIdle': '空闲',
  'project.workspaceFrozen': '待手动恢复',
  'project.mergePhase.accepted': '已登记',
  'project.mergePhase.processing': '处理中',
  'project.mergePhase.paused': '已暂停',
  'project.mergePhase.checking': '检查中',
  'project.mergePhase.merging': '合并中',
  'project.mergePhase.validating': '验证中',
  'project.mergePhase.awaiting-user': '等待确认',
  'project.mergePhase.merged-cleanup-pending': '已合入，清理未完成',
  'project.mergePhase.completed': '已完成',
  'project.mergePhase.failed': '失败',
  'project.mergePhase.cancelled': '已终止',
  'project.mergePhase.reserved': '等待合并',
  'project.workspaceCleaning': '清理中',
  'project.workspaceClosedMerged': '已合并并关闭',
  'project.workspaceClosedRetained': '目录已清理，分支保留',
  'project.workspaces': '工作区',
  'project.workspacesLoading': '读取工作区…',
  'project.noWorkspaces': '暂无受管理工作区',
  'project.title': '项目',
  'project.loading': '加载项目…',
  'project.workspace': '工作区',
  'project.openWorkspace': '在文件浏览器中打开',
  'project.workspaceOpened': '文件浏览器已切换到 {path}',
  'project.openWorkspaceFail': '打开工作区失败',
  'project.agentFile': 'AGENTS.md',
  'project.agentFileLabel': 'AGENTS.md',
  'project.agentFileOpen': '查看 / 编辑',
  'project.runningAgents': '运行中 agent',
  'project.openFlowMap': '打开「{name}」的 Flow Map',
  'project.idle': '空闲',
  'project.empty': '还没有项目',
  'project.emptyHint': '通过 {agent} 创建 Project 后，这里会列出项目。',
  'project.emptyCta': '让 {agent} 创建项目',
  'project.emptyCtaHint': '点击后把指令预填到输入框，不会自动发送。',
  'project.emptyPrefill': '帮我创建一个项目',
  'project.loadFail': '项目列表加载失败',
  'project.notMounted': '未挂载',
  'project.backToProjects': '返回项目列表',
  'project.noNodes': '暂无节点',
  'project.noNodesHint': '「{name}」暂无节点，无法打开 Flow Map',
  'project.archive': '归档',
  'project.archiveTitle': '归档「{name}」',
  'project.archiveConfirm': '归档「{name}」？归档后不在任务面板显示，workspace 文件全部保留（零删除零移动）。',
  'project.archiveDone': '「{name}」已归档——不在任务面板显示（文件保留）',
  'project.archiveFail': '归档「{name}」失败',
  // 实时更新 + 载入/归档动画（tabrealtime 批 2026-09-17）：新卡「新」标记 + 两条
  // 屏幕阅读器播报（aria-live 状态行）。项目名/描述走 contentI18n，不在此重复。
  'project.new': '新项目',
  'project.createdAnnounce': '「{name}」已创建',
  'project.archivedAnnounce': '「{name}」已归档',
  // N2 project-tool 批（2026-10-06）：面板手动卸载（归档按钮）被 Project 工具
  // action="unmount" 工具面替代；播报镜像 archived（aria-live 状态行）。
  'project.unmountedAnnounce': '「{name}」已卸载——在飞任务已停，文件全部保留',
  // unmount-console 面板批 2026-11-02：取消加载/重新加载 + 已卸载分组。
  'project.unmountBtn': '取消加载',
  // gitviz-mvp 批 2026-11-19：卡头「Git 可视化」入口钮（次级面板：热力图/泳道树/操控面板，只读）
  'project.gitVizOpen': 'Git 可视化（只读）',
  'project.unmountTitle': '取消加载「{name}」？',
  'project.unmountInflight': '当前在飞工作：{chains} 条链、{nodes} 个节点、{sessions} 个会话——取消加载会先把它们全部停止。',
  'project.unmountConfirm': '确认后：「{name}」将从项目列表与 Agent 上下文中消失，不能再派活；本地文件全部保留（零删除）。',
  'project.unmountConfirmGo': '取消加载',
  'project.unmountFail': '取消加载「{name}」失败：{detail}',
  'project.unmountedGroup': '已卸载',
  // 作者令 2026-11-05 08:26：分组头的灰字解释句已删（铁律 8 重复说明 + 行为教学，
  // 标题已表达状态、「重新加载」钮已表达出路）。键 `project.unmountedGroupHint`
  // 随之成为孤儿键，整键摘除，不留死键。
  'project.unmountedRowTitle': '「{name}」卸载于 {at}（本地文件保留）',
  'project.remountBtn': '重新加载',
  'project.remounting': '正在重新加载…',
  'project.remountFail': '重新加载「{name}」失败：{detail}',
  'project.remountedAnnounce': '「{name}」已重新加载',
  // Project-panel右下角「+」钮 + 轻弹层（projcreate 批 · 乙案：提交 = 预填主输入框 + 聚焦，
  // 零新端点、零新增写面 —— 描述框只收一段描述，不设路径/名称字段：乙案前端不写盘，
  // 放一个前端无从校验的路径框 = 假控件；路径由 Nebula 侧 ProjectCreate 自带路径面板解析）。
  'project.createOpen': '新建项目',
  'project.createTitle': '新建项目',
  'project.createDescLabel': '项目描述',
  'project.createDescPlaceholder': '描述这个项目要做什么——Agent会根据这段描述来分配任务到项目。',
  'project.createDescCount': '{n}/500',
  'project.createSubmit': '创建项目',
  'project.createCancel': '取消',
  'project.createDescRequired': '请填写项目描述（Agent 会根据它分配任务）',
  'project.createDescTooLong': '描述超过 500 字，请缩短到 500 字以内。',
  // 🔴 以下 6 条 = projcreate-redesign 批（作者 2026-09-24 裁定 D2(b)+D4）的 **C1 增补候选**：
  //    路径槽（选择器形态）+ 直连提交的新失败态。**待作者过目后由 sink 定稿**。
  'project.createWorkspaceLabel': '工作区路径',
  'project.createWorkspacePick': '选择路径…',
  'project.createWorkspaceNone': '未选择路径',
  'project.createWorkspaceRequired': '请先选择工作区路径。',
  'project.createWorkspacePickFail': '目录浏览器加载失败，请重试。',
  'project.createPending': '正在创建…',
  'project.createFailGeneric': '创建失败，请重试。',
  // 🔴 以下两条**被取代**（作者 2026-09-24 裁定 D2(b) 直连落地后调用点归零：
  //    `createPrefill` 的用途是「把描述拼成一句发给管家的话」；`createInputUnavailable`
  //    是转发路径独有的「没找到聊天输入框」失败态）。本批**保留键位、不动字节**，
  //    仅登记调用点 = 0（键面清理不在本批写面）。
  'project.createInputUnavailable': '没找到聊天输入框，描述未能填入。',
  'project.createPrefill': '帮我创建一个项目。项目描述：{desc}',
  // 提交成功提示：**已登记但本批默认不启用**（沿既有空态 CTA 语义——填进去 + 聚焦
  // 本身就是反馈；键位先留着，日后确无反馈感再启用）。故本文件无该键的调用点。
  'project.createPrefilled': '描述已填入输入框，按发送即可创建。',
  // === ui-device-faces 批（2026-10-10）：面A 创建项目「存放设备」+ 面C 项目卡设备徽标 ===
  // 文案逐字对齐设计稿（/tmp/nebflow-coldstart/general/proto-device-agnostic/
  // 20261010_002900_neblink-stage1-ui-faces-design.md §1/§3）。
  'project.deviceLabel': '存放设备',
  // 平级设计语言（作者 2026-10-30「所有设备是平等的」）：self 回退显示名中性化
  // （花名册可得时按钮/菜单一律显示设备本名，此键仅作未拉取花名册时的兜底）。
  'project.deviceSelf': '这台设备',
  'project.deviceOfflineTag': '离线',
  'project.deviceMenuFoot': '设备来自 NebLink 网络；离线设备不可选，上线后自动出现。',
  // 远端选中提示行：按存放关系陈述、不点名「本机」（平级设计语言；作者令「仅登记指针」）。
  'project.createDeviceHintline': '项目将存放在 {device}，由该设备执行；仅登记指针，文件按需查看。',
  // self 选中提示行（同一行位、同一句式，无指针从句——事实差异，非设备分级）。
  'project.createDeviceHintlineSelf': '项目将存放在 {device}，由该设备执行。',
  // picker-device-switch 批（2026-10-30）：选择器内设备切换回写与「存放设备」不一致时拒收。
  'project.createDeviceMismatch': '所选路径与存放设备不一致（面板内切过设备），请重新选择。',
  'project.createWorkspaceLabelRemote': '工作区路径（{device} 上）',
  'project.createWorkspacePickRemote': '在 {device} 上选择路径…',
  'project.createRemotePending': '正在 {device} 上创建（约数秒）',
  'project.createStep1': '校验设备',
  'project.createStep2': '校验目录',
  'project.createStep3': '写脚手架',
  'project.createStep4': '落项目定义',
  'project.createErrRetry': '重试',
  // 平级设计语言：动作文案中性化（原「换成本机」点名本机）。
  'project.createErrSwitchLocal': '改用这台设备',
  'project.createErrSwitchDevice': '换一台设备…',
  'project.devBadgeTitle': '远端项目 · 设备 {device} · 路径 {path}',
  'project.devBadgeOfflineTitle': '远端项目 · 设备 {device}（离线）· 路径 {path}',
  // 台账 #920（2026-10-27）：device 存储值兜底徽标（origin 缺席 ⇒ 无 path 段）
  'project.devBadgeDeviceTitle': '远端项目 · 设备 {device}',
  'project.devBadgeDeviceOfflineTitle': '远端项目 · 设备 {device}（离线）',
  // 台账 #920（2026-10-27）：面板侧描述写穿（描述行恒渲染 + 就地编辑）
  'project.descLabel': '描述',
  'project.descEmpty': '未设置描述',
  'project.descEdit': '编辑描述',
  'project.descPlaceholder': '一句话描述该项目（措辞会用于任务分配）',
  'project.descSave': '保存',
  'project.descCancel': '取消',
  'project.descSaved': '「{name}」描述已更新',
  'project.descSaveFail': '描述保存失败（{detail}）',
  'flowmap.title': 'Flow Map',
  'flowmap.cardRunning': '运行中…',
  // 节点卡 Agent 退役（node-flowmap-slim）：preset 未配置的克制空态文案
  'flowmap.presetDefault': '默认预设',
  // 卡内说明行溢出入口（edge-render-fix 批 2026-11-05 作者令「说明不许静默省略」）：
  // 说明两行封顶后，只要能装下的更少，卡内就出现本入口 —— 点卡打开详情窗读全文。
  'flowmap.descFull': '全文',
  'flowmap.loading': '加载 Flow Map…',
  'flowmap.loadFail': 'Flow Map 加载失败',
  'flowmap.empty': '暂无节点，项目空闲',
  'flowmap.notMounted': '项目未挂载（后台未运行），无实时 Flow Map',
  'flowmap.run': '运行中',
  'flowmap.wait': '等待',
  'flowmap.fail': '失败',
  'flowmap.done': '已完成',
  'flowmap.idle': '空闲',
  'flowmap.resultTitle': '节点结果',
  'flowmap.noResult': '暂无结果',
  'flowmap.runningDetail': '节点运行中，完成后结果将自动保存并沿出边投递。',
  'flowmap.noResultDetail': '该节点尚未产生结果（运行中/等待/悬空）。',
  // blocked（20260902 反馈路径设计 §4.2/§4.3）：状态计数 + 详情窗结构化反馈
  'flowmap.blocked': '已阻断',
  'flowmap.blockedTitle': '已阻断 — 待分发器处置',
  'flowmap.blockedRounds': '已被阻断 {n} 轮',
  'flowmap.blockedCategory': '原因分类',
  'flowmap.blockedDetail': '说明',
  'flowmap.blockedSuggestion': '建议',
  'flowmap.blockedRawTitle': '节点输出原文',
  // deps 依赖连接（deps 设计 §1.4）：等待脚注 + 六档图例（输出边三态 × 依赖边三态）
  'flowmap.waitingFor': '等待',
  // 等待脚注归档降级标记（裁定①：原 ✦ 字符换纯文字；含整链归档/到期出库/断链）
  'flowmap.archivedTag': '（已归档）',
  'flowmap.legend.outDelivered': '输出已投递',
  'flowmap.legend.outWaiting': '输出等待中',
  'flowmap.legend.outIdle': '输出未投递',
  'flowmap.legend.depsMet': '依赖已满足',
  'flowmap.legend.depsWaiting': '依赖等待中',
  'flowmap.legend.depsUnmet': '依赖未满足',
  // 整链归档 v3（20260903_flowmap-archive-panel-spec.md；flowmap.allArchived TTL 文案下线；
  // 2026-09-07 状态保真批：归档资格收紧为全 completed，terminalTag 退役——卡 title 改显真实状态词）
  'flowmap.retained': '终态保留 {n}',
  'flowmap.legend.terminalRetained': '终态保留（链未齐/异常）',
  'flowmap.st.wiring': '接线中',
  'flowmap.st.pending': '待运行',
  'flowmap.st.cancelled': '已取消',
  // 视图过滤（P3 占位治理，2026-09-08）：二态「全部/进行中」，纯前端派生过滤
  'flowmap.viewFilter.activeOnly': '仅进行中',
  'flowmap.viewFilter.hint': '隐藏终态节点（已完成/失败/已取消/已阻断），只看活跃节点',
  'flowmap.viewFilter.noneActive': '无进行中节点——终态节点已被视图过滤隐藏',
  'flowmap.archive.button': '已归档任务链（{n}）',
  'flowmap.archive.title': '已归档任务链',
  'flowmap.archive.ttlTitle': '归档条目自完成起保留 24 小时，到期自动清理',
  'flowmap.archive.count': '{n} 条链 · {m} 节点',
  'flowmap.archive.nodes': '{n} 节点',
  'flowmap.archive.empty': '暂无已归档任务链',
  'flowmap.archive.readGuide': '链已归档 · 点成员查看结果全文（按需取）',
  'flowmap.archive.expiringSoon': '即将过期',
  'flowmap.archive.allArchived': '全部节点已完成，结果收入右上角归档',
  'flowmap.archive.close': '关闭归档面板',
  'flowmap.archive.detailClose': '关闭节点详情',
  'flowmap.archive.detailLabel': '节点详情',
  'flowmap.archive.openMemberHint': '点击查看右侧详情',
  'flowmap.archive.chain': '所属链',
  'flowmap.archive.chainArchived': '已归档',
  'flowmap.archive.chainRetained': '链未齐·终态保留主图',
  'flowmap.archive.chainAbnormal': '含失败/取消·保留主图待清理',
  'flowmap.archive.taskLabel': '任务',
  'flowmap.archive.resultLabel': '结果',
  'flowmap.archive.noResult': '暂无结果',
  // 结果区明确错误态（2026-09-11 fmresult 批）：占位「…」必须有终态——取全文失败
  // 不再静默折成空态「暂无结果」（错误态与空态混淆），改为可读原因 + 可操作提示。
  'flowmap.archive.resultLoadFail': '结果加载失败：{reason}',
  'flowmap.archive.resultLoadFailHint': '关闭详情窗后重开可重试',
  'flowmap.archive.resultFailTimeout': '请求超时（网关无响应）',
  'flowmap.archive.resultFailNetwork': '请求未送达或响应异常（网络错误）',
  'flowmap.archive.resultFailHttp': '服务返回 {status}',
  'flowmap.archive.resultFailGone': '结果已不可达（节点不在活动区/归档区）',
  'flowmap.archive.created': '创建',
  'flowmap.archive.completed': '完成',
  'flowmap.archive.ttlPurged': '24h 到期清理：{n} 条链已从归档移除',
  // 节点详情完整配置（2026-09-07 批）：详情卡配置/拓扑/执行形态三分区标签与 KV 文案
  'flowmap.detail.secConfig': '配置',
  'flowmap.detail.secTopo': '拓扑',
  'flowmap.detail.secExec': '执行形态',
  'flowmap.detail.preset': '预设',
  'flowmap.detail.plugins': '插件',
  'flowmap.detail.allocation': '分配方式',
  'flowmap.detail.none': '无',
  'flowmap.detail.in': '输入',
  'flowmap.detail.out': '输出',
  'flowmap.detail.deps': '依赖',
  'flowmap.detail.ttlLeft': '{t} 后清理',
  'flowmap.detail.ttlExpired': '已到期 · 待清理',
  'flowmap.detail.loop': '循环',
  'flowmap.detail.loopRounds': '最多 {n} 轮',
  'flowmap.detail.loopVerify': '验证 {agent}',
  'flowmap.detail.loopDisabled': '已停用',
  'flowmap.detail.loopState': '循环状态',
  'flowmap.detail.loopRoundN': '第 {n} 轮',
  'flowmap.detail.loopVerdict': '最近判定',
  'flowmap.detail.notify': '回流通知',
  // 排队位次行（queuepos 批 2026-09-15）：详情面板此前完全无排队字段（作者现场报）。
  // 值形态与卡面同源（`mergeQueuePos`，逐节点唯一、真源 = SEM-2 rank 升序）。
  'flowmap.detail.yes': '是',
  'flowmap.chain.archivedToast': '链「{chain}」整链归档（{n} 节点一起进归档）',
  // 链级取消（R2，chaincancel 批 2026-09-17）：控制面 `.fm-chain-cancel` + 不可逆
  // 确认卡四要素（①链名+chainId ②将取消数与逐状态计数 ③不可逆声明 ④零触碰声明）
  // + 「放弃可恢复中断数」提示（作者三答 2）；另含结果 toast 两条。
  'flowmap.chain.cancel.label': '取消整条链',
  'flowmap.chain.cancel.title': '取消整条链',
  'flowmap.chain.cancel.chain': '链「{chain}」({id})',
  'flowmap.chain.cancel.counts': '将取消 {n} 个节点（{breakdown}）',
  'flowmap.chain.cancel.irreversible': '不可逆：取消后的节点不可重激活，只能新建替代节点',
  'flowmap.chain.cancel.untouched': '零触碰：已完成 / 失败 / 已取消的 {n} 个节点不受影响，结果保留',
  'flowmap.chain.cancel.interrupted': '其中 {n} 个可恢复中断（interrupted）将被一并放弃',
  'flowmap.chain.cancel.st.running': '运行中',
  'flowmap.chain.cancel.st.pending': '待运行',
  'flowmap.chain.cancel.st.wiring': '接线中',
  'flowmap.chain.cancel.st.blocked': '已阻塞',
  'flowmap.chain.cancel.st.interrupted': '中断',
  'flowmap.chain.cancel.done': '链级取消已提交（{n} 个节点）',
  'flowmap.chain.cancel.error': '链级取消失败：{error}',
  // 链折叠（P1 · spec §3.3）：主图折叠链摘要卡文案（链名/成员数由后端 chains 旁挂
  // 下发，此处只放可翻译的壳文案）；展开动作 = 点击卡本体或卡内 chevron。
  'flowmap.chain.nodes': '{n} 节点',
  'flowmap.chain.expand': '展开链',
  // 展开态折叠控件（链入口卡 chevron）：带链名与成员数，压掉「这箭头收的是谁」的歧义。
  'flowmap.chain.collapse': '折叠链（{chain} · {n} 节点）',
  'flowmap.chain.cardHint': '折叠的链「{chain}」（{n} 节点）— 点击展开',
  // 任务面板链徽标（P1 · spec §7-B ⭐）：行级链胶囊（链短名 + 同链同色圆点，色板由
  // 前端按链 id 稳定映射）；点击跳主图定位该节点。
  'flowmap.chain.badgeHint': '链「{chain}」· 点击跳到主图',
  // 特殊节点标识徽标（badge 批 2026-09-05）：head 行胶囊文案；追加锚在 flowmap 块
  // 尾部，最小化与同批其他 locales 支的合并冲突面
  'flowmap.flag.merge': '合并',
  'flowmap.flag.loop': '循环',
  'flowmap.flag.pending': '待确认',
  // 节点角色徽标（vchip 批 2026-09-15）：`role=verifier` 身份在主图上的可见面。
  // 与 merge/loop/pending 同款 head 行胶囊；双字与其他徽标同宽（卡 124px 内容
  // ~102px 约束），语义取「复核」= 该节点的职责（判定 pass/fail + 持 (fail):loop 回边）。
  'flowmap.flag.verifier': '复核',
  // 合并窗排队位次（排队位次可见性批 2026-09-14；作者 16:39 双裁：显示 = 数字 +
  // 持有者双显）。徽标在节点卡 head 行、脚注在同一等待脚注行。降级红线：
  // 键缺失/不可计算/同键多项目 ⇒ 数字不渲染（至多裸「排队中」），禁编造数字。
  //
  // **queuepos 批 2026-09-15 修正**（作者现场报「前面还有几个都一样 / 详情面板也没有」）：
  // 数字面 = `mergeQueuePos.position`（位次，逐节点唯一）——`mergeQueue.ahead` 是**阻塞
  // 集合的势**（同刻只有一个 running 时全体 ≡1 ⇒ 看起来一模一样），故键 `queue.ahead`
  // /`badgeTitle`/`untrustedTitle` 随之退役、不再有「前面还有 N 个」这一形态。
  // 队列名（引擎给 token，前端只翻译不派生；未知 token 原样回显）
  // LoopNode 运行态相位文案（2026-09-06）：loop 节点 running 时 desc 行显 worker/verify
  'flowmap.loopPhase.worker': '生产中',
  'flowmap.loopPhase.verify': '验证中',
  // U1 多链归属批（2026-09-11）：合并节点详情卡「收的 worktree/分支」行——数据源 =
  // 上游 in 闭包内节点载荷 worktree 字段；取不到时按上游可见性落三态文案（复核轮2
  // 修复：单一成因会造出假陈述——上游在载荷内但未配 worktree 并非「不在缓存」）。
  // 中英键集对称：None = 态①可达上游全在缓存且均未配 worktree；Absent = 态②可达
  // 上游全不在缓存；Mixed = 态③部分不在缓存 + 可见上游未配 worktree。
  'flowmap.detail.mergeUpstreams': '收的 worktree/分支',
  'flowmap.detail.mergeUpstreamsNone': '不可得（上游节点未配置 worktree）',
  'flowmap.detail.mergeUpstreamsAbsent': '不可得（上游节点不在当前载荷缓存）',
  'flowmap.detail.mergeUpstreamsMixed': '不可得（部分上游不在当前载荷缓存，可见上游未配置 worktree）',
  'agentFile.hint': '此文件是项目工作区根的 agent 指令（AGENTS.md），可编辑并保存。',

  // === Friends & messages (A2A) ===
  'contacts.searchPlaceholder': '用户名 / 邮箱',
  'contacts.search': '搜索',
  'contacts.notFound': '未找到该用户',
  'contacts.addFriend': '加好友',
  'contacts.self': '这是你自己',
  'contacts.sendMessage': '发消息',
  'contacts.outgoingPending': '等待对方处理',
  'contacts.alreadyRequested': '已申请过，等待对方处理',
  'contacts.respondRequest': '回应请求',
  'contacts.unblock': '取消拉黑',
  'contacts.newFriends': '新的朋友',
  'contacts.noRequests': '暂无好友请求',
  'contacts.accept': '同意',
  'contacts.decline': '拒绝',
  'contacts.accepted': '已添加',
  'contacts.declined': '已拒绝',
  'contacts.sentRequests': '我发出的',
  'contacts.empty': '暂无联系人，搜索用户名或邮箱添加',
  'contacts.verifyMessagePlaceholder': '发送验证消息（可选）',
  'contacts.ariaRequests': '{n} 个好友请求',
  'contacts.menuDelete': '删除好友',
  'contacts.menuBlock': '加入黑名单',
  'contacts.menuUnblock': '移出黑名单',
  'contacts.menuTrust': '信任此好友',
  'contacts.menuUntrust': '取消信任',
  'contacts.blocked': '已拉黑',
  // ⑦ 好友备注（2026-09-12 批）：入口仅一处 = 好友行右键菜单 → 行内编辑。
  'contacts.menuSetRemark': '设置备注',
  'contacts.remarkPlaceholder': '备注（留空清除）',
  'contacts.remarkHint': 'Enter 保存 · Esc 取消 · 留空清除备注',
  // ── 件一（2026-11-02）：好友/群 Profile 双描述面 ─────────────────────────
  'profile.open': '查看资料',
  'profile.friendTitle': '好友信息',
  'profile.groupTitle': '群资料',
  'profile.close': '关闭',
  'profile.save': '保存',
  'profile.saved': '已保存',
  // 三点入口批（2026-11-05，作者令「里面……还要有搜索聊天记录……然后就是消息
  // 免打扰的 toggle，然后就是顶置的 toggle」）：三条新行的文案。
  //  · searchMessages = 复用主对话同一张搜索面板的入口行；
  //  · muteLabel / pinLabel = 两个共享开关（nb-toggle）的行标签。
  'profile.searchMessages': '搜索聊天记录',
  'profile.muteLabel': '消息免打扰',
  'profile.pinLabel': '顶置',
  'profile.friendRemarkLabel': '我的备注',
  'profile.friendRemarkPlaceholder': '我给对方起的备注（留空清除）',
  'profile.friendBioLabel': '自我介绍',
  'profile.friendBioPlaceholder': '对方的自我介绍（留空清除）',
  'profile.groupRemarkLabel': '我的群备注',
  'profile.groupRemarkPlaceholder': '我给这个群起的备注（留空清除）',
  'profile.groupDescriptionLabel': '群介绍',
  'profile.groupDescriptionPlaceholder': '群介绍（留空清除）',
  // 云同步徽标三态（profile-sync 徽标批 2026-11-06 作者令 3(a)）：写死的
  // 「本地缓存·未同步」徽标退役，改读 GET /api/profile/sync-status
  // （ProfileSync.status）真数据源，开面 / 保存后 / profileDescriptionsChanged
  // 帧三拍刷新。诚实优先：无外推成功证据绝不画「已同步」。
  'profile.sync.synced': '已同步',
  'profile.sync.syncing': '待同步',
  'profile.sync.localOnly': '仅本地',
  'profile.friendNote': '备注只存在本机；自我介绍与群介绍的上游同步接口尚未提供，当前为本机缓存。两者都已纳入搜索。',
  'profile.groupNote': '我的群备注只存在本机；群介绍的上游同步接口尚未提供，当前为本机缓存。两者都已纳入搜索。',
  'contacts.deleteTitle': '删除好友',
  'contacts.deleteConfirm': '删除后，将解除与 {name} 的好友关系，聊天记录保留。',
  'contacts.blockTitle': '加入黑名单',
  'contacts.blockConfirm': '加入黑名单后，将不再收到 {name} 的消息与好友请求。',
  'contacts.searching': '搜索中…',
  'contacts.notFoundHint': '对方可能尚未设置用户名，或输入有误',
  'contacts.cancelVerify': '取消',
  'contacts.alreadyFriends': '已是好友',
  'contacts.searchError': '搜索失败，请重试',
  'contacts.searchAuthError': '登录已失效，请重新登录',
  'contacts.relogin': '重新登录',
  'contacts.neblinkOff': '设备互联未启用，暂无法使用好友功能',
  'contacts.listError': '好友列表加载失败',
  'contacts.requestsError': '好友请求加载失败',
  'contacts.retry': '重试',
  // 群组一期（friendgroups 客户端腿）：建群/群邀请（契约 = 主卡案1② + A-4）。
  'contacts.createGroup': '发起群聊',
  'contacts.createGroupTitle': '发起群聊',
  // 统一面板批（2026-11-06）：顶栏返回圆钮的读屏/悬浮文案（原右上 X 无文案面）。
  'contacts.back': '返回',
  'contacts.createGroupName': '群名称',
  // 建群面板骨架四段（2026-09-16 视觉重做）：输入区示例占位 / 列表区章节标签 /
  // 空态引导。三键与 en 同批落地（禁死键）。
  'contacts.createGroupNameHint': '例如：项目讨论组',
  'contacts.createGroupMembers': '选择成员',
  'contacts.createGroupEmpty': '暂无可邀请的好友，先在「新的朋友」里添加好友',
  'contacts.createGroupNameRequired': '群名称不能为空',
  'contacts.createGroupSelected': '已选 {n} 人',
  'contacts.createGroupCap': '群成员最多 50 人',
  'contacts.createGroupSubmit': '创建',
  'contacts.groupInvites': '群邀请',
  'contacts.groupInviteFrom': '{name} 邀请你加入群聊',
  'messages.empty': '暂无会话',
  // 会话列表**取数失败态**（empty-state-must-not-lie 批 2026-11-05）。🔴 与上面那条
  // 空态文案是**两个不同事实**，禁互相顶替：`messages.empty` 只在「服务端明确答复
  // 确实没有会话」（200 + `[]`）时出现；取数失败（非 2xx / 超时 / 解析失败）走本键
  // ⇒ 错误态 + 重试键。文案纪律 = 自解释铁律 8：只写状态事实（加载失败），下一步
  // 动作由重试键本身承载，不写「请检查网络连接」这类排查教学。
  'messages.listLoadFailed': '会话列表加载失败',
  'messages.retry': '重试',
  'messages.backToAgent': '返回智能体对话',
  // 🔴 `systemNowFriends` = **好友接受流程**的语境（唯一合法消费点 =
  // messages.js `onFriendEvent` 的 `friend_accepted` 分支）⇒ 下条群语境键是
  // **拆键**产物（2026-09-16 作者令「入群提示还是：你们已成为好友，这不对」：
  // 原 `summaryOf` kind-blind，群行空态套上了上面这条好友文案）。
  // 两键与 en **同批**落地（禁死键），消费点 = messages.js `groupSummaryEmpty`
  // 单点 —— **事件腿在前**（`groupEventText`，见下方事件键族），这两条是它的回落两态
  // （群名在场 / 缺席；缺席不造值、不拼重复占位）。
  'messages.systemNowFriends': '你们已成为好友',
  'messages.groupNoMessagesNamed': '群聊「{name}」暂无消息',
  'messages.groupNoMessages': '群聊暂无消息',
  // 群事件成员名（作者 2026-09-16 方向令「『{成员名} 加入了群聊』须带成员名」）：
  // 消费点 = messages.js `groupEventText` 单点（群行空态摘要），数据源 = 群行加性键
  // `latestEvent`（正典 §2.4；六 kind 闭集）。🔴 显示名**只有一个来源** = 服务端
  // `display_name`（`{name}` = `subject.name`、`{actor}` = 邀请人 `actor.name`）；禁猜名。
  // 中英**同批**落地（`i18n.js` 缺键回落 = 返回键名本身 ⇒ 单侧落地即渲染裸键名）。
  'messages.groupEventCreated': '{name} 创建了群聊',
  'messages.groupEventJoined': '{name} 加入了群聊',
  'messages.groupEventInvited': '{actor} 邀请了 {name}',
  'messages.groupEventLeft': '{name} 退出了群聊',
  'messages.groupEventRemoved': '{name} 被移出群聊',
  'messages.groupEventRoleGranted': '{name} 成为了群管理员',
  'messages.groupEventRoleRevoked': '{name} 被取消了群管理员',
  'messages.forwardToAgent': '转发给 agent',
  'messages.forwarded': '已转发给 agent',
  'messages.forwardToast': '已加入输入框，可附言后发送',
  'messages.forwardNoSession': '请先在主界面打开一个会话，再转发消息',
  // ── msgmenu 一期（客户端，作者 2026-09-18 19:2x 四答）新增键 ──────────────
  // 🔴 本批硬禁删除/撤回 ⇒ **禁**在此新增 `messages.delete*` / `messages.recall*`
  //    一类键（含「删除」「撤回」字样），一行都不写。
  'messages.quote': '引用',
  // 被引消息不在当前已载窗口（跳转未实现 ⇒ 只做降级显示）：摘要仍在信封里，此处只标缺失。
  'messages.quoteUnavailable': '原消息不可用',
  'messages.quotePlaceholder': '原始消息',
  // quotejump 批（跳转「必须可跳」）：引用块的**按钮语义**可访问名（含被引摘要文本）。
  // 无跳转实现/坐标不可得时该属性不挂（`paintJumpAffordance` 单点）。
  'messages.quoteJumpAria': '跳转到被引消息：{text}',
  // ── quote-message 一期（主聊天区消息右键引用，spec §6 清单）新增 9 键 ──────
  'chat.quoteJumpHint': '跳转到被引消息',
  'chat.quoteDeleted': '原消息已删除',
  'chat.quoteDeletedSnapshot': '原消息已删除 · 已保留快照文',
  'chat.quoteSenderMe': '我',
  'chat.quoteTypeImage': '[图片]',
  // 叠卡单张引用批（2026-11-02 作者令「让叠卡的图片可以分开引用」）：引用牌上的
  // 位置标注 —— 同一叠卡内不同张的引用牌靠它彼此可区分。
  'chat.quoteImagePos': '第 {n}/{total} 张',
  'chat.quoteItemAria': '引用第 {n} 张',
  'chat.quoteTypeFile': '[文件]',
  'chat.quoteTypeTool': '[工具]',
  'chat.quoteTypeAsk': '[提问]',
  'chat.quoteTypeSystem': '[系统]',
  // ── msgchip 批（消息身份牌带短名引用，作者令 2026-11-01）新增键 ─────────────
  'chat.msgTokenChipHint': '查看原消息',
  'chat.msgTokenPopTitle': '原消息',
  'chat.msgTokenUnreachable': '原消息不可达',
  'chat.msgTokenLoading': '读取中…',
  'chat.msgTokenExpand': '展开',
  'chat.msgTokenCollapse': '收起',
  // ── msgchip-provenance 批（2026-11-01 作者令「msg 牌剥渠道前缀」）：附件指示 ──
  // 牌窗正文剥掉附件路径指针行后，改由这条简短指示承载「这条消息带附件」这一事实
  // （不显示路径/文件名噪声）。{n} = 附件件数。
  'chat.msgTokenAttachNote': '含 {n} 个附件',
  'messages.multiSelect': '多选转发',
  'messages.selectedCount': '已选 {n} 条',
  'messages.forward': '转发',
  'messages.selectExit': '退出',
  // ── visup-b 批（作者 2026-09-19 04:22：D5 底部动作条等分 / D7 转发窗口）新增键 ──
  // 🔴 同一条硬禁仍在：禁删除/撤回键（含「删除」「撤回」字样），一行都不写。
  // 合并转发 = **二期**（无服务端协议 ⇒ 键里带 `Phase2`，落 disabled + title）。
  'messages.forwardEach': '逐条转发',
  'messages.forwardMerge': '合并转发',
  'messages.forwardMergePhase2': '合并转发（二期落地）',
  'messages.forwardToTitle': '转发到',
  'messages.forwardTargetDirect': '单聊',
  'messages.forwardTargetGroup': '群聊',
  'messages.forwardNotePlaceholder': '给朋友留言',
  'messages.forwardNoPick': '未选择目标会话',
  'messages.forwardSend': '发送',
  'messages.forwardCancel': '取消',
  'messages.forwardChipRemove': '移除 {name}',
  'messages.copied': '已复制',
  'messages.copyFailed': '复制失败',
  'messages.forwardNoTarget': '没有其他会话可以转发',
  'messages.forwardNoneSelected': '没有可转发的消息',
  'messages.forwardedCount': '已转发 {n} 条',
  'messages.forwardSkipped': '已转发 {n} 条，跳过 {k} 条不可转发',
  'messages.forwardPartial': '已转发 {n} 条，{f} 条失败',
  // B+C 批 · 方案 B（不可恢复标记的**文案**半边）：{n} = 无法恢复的附件数。
  'messages.queueAttachUnrecoverable': '{n} 个附件因页面刷新无法恢复，请重新添加',
  'input.historyAttachLost': '历史记录里有 {n} 个附件的内容已不在本机，请重新添加',
  // attach-split 批（2026-10-24 现场事故修复）：服务端超限帧有声拒绝（messageTooLarge
  // 回执）的用户可见文案 —— {size}/{limit} = 实测帧 MB / 服务端上限 MB。
  'send.messageTooLarge': '这条消息（约 {size} MB）超过投递上限 {limit} MB，未被发送——请减少附件数量后重发',
  'messages.trusted': '已信任',
  'messages.trustedHint': '已开启信任：新消息将自动转发给 agent',
  'messages.loadingOlder': '加载更早消息',
  'messages.loading': '加载中…',
  // ⑩ 消息流状态行（消息区永不为空）：冷路径回包为空 ⇒ 空态（不是永远转圈）。
  'messages.noMessages': '暂无消息',
  'messages.loadOlderFailed': '更早消息加载失败，请重试',
  'messages.attachUnsupported': '好友窗暂无附件发送入口（附件可正常接收与下载）',
  // attachcl 批（2026-09-16）：**发送面**上传卡文案（与上面「接收面」的 attach* 文案
  // 同族但**不共用键**——接收态「上传中，暂不可下载」与本处「我方上传进度」是两件事）。
  // 进度文案的 `{pct}` 由**真实分块确认**推导（禁假进度）；失败/取消文案一律写清
  // 「未发送」（fail-closed 可见，禁静默）。
  'messages.attachProgress': '上传中 {pct}%',
  // 🔴 `messages.attachSending`（「正在发送」，imgmsg 批 2026-09-18 决策点 ④ 加性键）已按
  //    sendstate 批作者设计令**删除**：其唯一消费者 = quiet 上传卡的发送中条幅，该条幅已
  //    **整条退场**（装配点 `js/attachUpload.js::uploadCardEl` 不再出卡）⇒ 全仓零引用
  //    （`grep -rn "messages\.attachSending'"` 于 js/ 与 tests/ 零命中）。禁回填死键。
  'messages.attachSent': '已发送',
  'messages.attachFailed': '上传失败，未发送',
  'messages.attachCancel': '取消',
  'messages.attachCancelled': '已取消，未发送',
  'messages.attachCancelledRest': '已取消，未上传',
  'messages.attachNotSentRest': '未上传（前一件失败）',
  'messages.attachSendFailed': '上传已完成但消息发送失败，请重试',
  'messages.attachNoFiles': '没有可发送的文件',
  'messages.attachEmptyFile': '{name} 是空文件（0 字节），未发送',
  // 4b 腿 A-2：附件卡片文案（§B.7 三态可判读；「已过期」与「下载失败」**必须**分得开）
  'messages.attachDownload': '下载',
  'messages.attachUnavailable': '无文件来源，无法下载',
  'messages.groupDownloadUnavailable': '部分文件无来源，无法整组下载',
  'messages.attachDownloading': '下载中…',
  'messages.attachDownloaded': '已下载',
  'messages.attachDownloadFailed': '下载失败，点击重试',
  'messages.attachExpired': '附件已过期',
  'messages.attachUploading': '上传中，暂不可下载',
  'messages.attachUnreadable': '附件状态不可判读，暂不可下载',
  'messages.attachUnnamed': '未命名附件',
  // 手机端下载落点提示（mobile-download 批 2026-11-18 · 作者令「手机端的下载我不知道
  // 存哪去了」）。复用既有通知栈（window.__showToast）呈现，零新提示样式。
  'download.sharedToAlbum': '已打开系统分享面板 · 选「存储到照片」即入相册',
  'download.sharedToFolder': '已打开系统分享面板 · 选「存储到文件」可指定保存位置',
  'download.landedIosFiles': '已下载 · iPhone 上存于「文件」App ›「下载」',
  'download.landedBrowserDir': '已下载到浏览器下载目录',
  // 附件预览（作者令 2026-09-15）：卡片可点面的无障碍名 + 两条**可见**降级文案。
  'messages.attachPreview': '预览',
  'messages.attachPreviewUnsupported': '暂不支持预览该类型：{name}',
  'messages.attachPreviewFailed': '预览失败，请重试',
  'messages.agentBadge': 'Agent 代发',
  // 群组一期（friendgroups 客户端腿）。🔴 agent 徽章文案**收敛为一值**（作者
  // 2026-09-16 决策卡 F1：全站统一为通用「Agent 代发」徽章）：原 `agentBadge`
  // `Agent 代发` 与 `agentGroupBadge` `由 Agent 发` 二值并存 ⇒ 后者已删（主对话面 /
  // 好友面 / 群面 / 列表摘要前缀四处同取 `messages.agentBadge`；禁死键）。
  'messages.groupTag': '群',
  'messages.groupUntitled': '群聊',
  'messages.memberCount': '{n} 名成员',
  'messages.groupSettings': '群设置',
  'messages.groupTitleLabel': '群名称',
  'messages.groupTitleSave': '保存',
  'messages.groupRenamed': '群名已更新',
  'messages.groupMembers': '群成员',
  'messages.roleOwner': '群主',
  'messages.roleAdmin': '管理员',
  // 「添加成员」入口（决策卡 C/D/G/H）：文案 = 动作本身（原「邀请好友」是候选源，
  // 语义 ≠ 动作 ⇒ 该键已删，禁死键）。
  'messages.groupAddMember': '添加成员',
  'messages.groupInviteSend': '发出邀请',
  'messages.groupInvitedPending': '邀请已发出，待对方确认',
  'messages.groupInviteFailed': '{n} 个邀请发送失败',
  // 设 / 撤管理员（决策卡 ①：owner 专属；正典 §B.2 幂等路由）
  'messages.groupSetAdmin': '设为管理员',
  'messages.groupRevokeAdmin': '撤销管理员',
  'messages.groupAdminSet': '已设为管理员',
  'messages.groupAdminUnset': '已撤销管理员',
  'messages.groupKick': '移出群聊',
  'messages.groupKickedDone': '已移出群聊',
  // 「被移出」事件文案（消费点 = `friendGroups.groupErrToast` 的 `not_member`
  // 分支 `:73`）。2026-09-16 现读该键**在两侧缺席** ⇒ 该 toast 此前渲染裸键名
  // `messages.groupKicked`（`i18n.js:28` 缺键回落 = 返回键名本身）。本批同族补齐，
  // 中英同批（禁单语新增）。
  'messages.groupKicked': '你已被移出群聊',
  // 移除成员的确认文案**补后果句**（决策卡 D：对齐退群 / 解散文案口径 ——
  // 原文案只说「将 X 移出群聊？」不含后果，是唯一没写后果的危险操作）。
  'messages.confirmKick': '将 {name} 移出群聊？移出后对方将不再收到该群消息，历史消息对其不可见，且需重新邀请才能加入。',
  'messages.groupLeave': '退出群聊',
  'messages.confirmLeave': '退出后将不再收到该群消息，且历史消息不可见。',
  'messages.groupLeft': '已退出群聊，历史消息不可见',
  'messages.groupDissolve': '解散群聊',
  'messages.confirmDissolve': '解散后该群对所有成员停用，操作不可撤销。',
  'messages.groupDisbanded': '群聊已解散',
  'messages.groupCreated': '群聊已创建',
  'messages.groupNotFound': '该群聊不存在或已解散',
  // 群主禁退群提示（决策卡 K / 方案 §9 D1：本键原**缺失**，屏幕上直接印出键名
  // `messages.groupOwnerNoLeave`；调用点 = buildGroupSettings 的 owner 分支）。
  'messages.groupOwnerNoLeave': '群主不能退出群聊，只能解散群聊。',
  // 群域错误分态（正典 §B.4）：越权专属码必须**可见**且与登录失效分态。
  'messages.groupNotAdmin': '你没有执行该操作的权限（仅群主或管理员可操作）。',
  'messages.groupMemberNotFound': '该成员已不在群里',
  'messages.groupRoleInvalid': '角色取值无效',
  'messages.groupsUnavailable': '群聊功能暂不可用',
  'messages.attachUnsupportedGroup': '群聊窗暂无附件发送入口（附件可正常接收与下载）',
  'messages.notFriendBlocked': '对方已不是你的好友，无法发送消息',
  'messages.loginRequired': '登录 {lowerBrand} 账号后使用消息与联系人',
  'login.popupBlocked': '弹窗被拦截，请允许后重试',
  'login.openPage': '打开登录页',
  // 登录卡社交一键批（2026-10-15）：卡面选择键文案（provider 官方名不译；
  // 「其他方式」= 弹出壳内的统一账号登录，邮箱/用户名等并列——mail 次选）。
  'login.social.github': '使用 GitHub 登录',
  'login.social.google': '使用 Google 登录',
  'login.social.other': '使用邮箱或其他方式登录',
  // 登录入口 / 登录面板（2026-09-16 登录入口批）：面板文案此前是 activityBar.js
  // 注入样式一族的硬编码中文字面量，本批整体搬进 i18n（zh/en 成对，禁死键）。
  'login.title': '登录 {lowerBrand}，连接你的其他设备',
  // 登录卡 v3 单键面（mobile-mockup-375 v3④）：官方横排 lockup + 单键制。
  'login.unifiedPrimary': '登录 {lowerBrand} 账号',
  'login.connectTitle': '连接你的其他设备',
  'login.connectHint': '登录后此设备与账号下其他设备互联',
  'login.authNote': '将打开浏览器完成认证；GitHub / Google 快捷登录在认证页内',
  'login.starting': '正在启动登录…',
  'login.hintBrowser': '在浏览器中登录 {lowerBrand} 账号，此设备即与账号下的其他设备互联',
  'login.reopenAuthPage': '重新打开登录页面',
  'login.switchAccount': '使用其他账号登录',
  'login.waitingPopupOpened': '已在新标签页打开登录页面，等待完成…',
  'login.waiting': '等待登录完成…',
  'login.retry': '重试',
  'login.failed': '登录失败',
  'login.success': '✓ 连接成功，设备已加入网络',
  // 面板内可见的流程错文案（来源 = neblink.js 的 PKCE 失败路径；设备流文案随
  // 该流程退役，#924）。
  'login.startFailed': '启动登录失败',
  'login.timeout': '登录超时，请重试',
  'login.networkError': '网络错误：{msg}',
  // 缺陷 A（2026-09-18 换号登录失败批）· 失败面**三段式**：
  //   登录失败：<原因>。下一步：<动作>。诊断码：<code>
  // `login.reason.*` / `login.action.*` 的 code 枚举与后端 `CredentialFailure`
  // **逐字同源**（镜像漂移由 `CredentialDiagnosticsSpec` 钉住）；UI 只 `t()`，
  // 后端串仅作老网关/未知码的兜底。文案纪律（判据 G2/G3）：**禁出现文件系统路径
  // 或 Java 异常类名** —— 那些只进日志。
  'login.failureLine': '登录失败：{reason}。下一步：{action}。',
  'login.diagnosticCode': '诊断码：{code}',
  // 本地凭据文件类（这六条才给「清理并重登」入口）
  'login.reason.credential-missing': '本机还没有登录凭据',
  'login.action.credential-missing': '点「登录」重新登录',
  'login.reason.credential-unreadable': '本机凭据文件打不开（权限或占用）',
  'login.action.credential-unreadable': '先退出其他 {lowerBrand} 实例再重试；仍失败则点「清理并重登」',
  'login.reason.credential-undecodable': '本机凭据文件已损坏（已备份留档）',
  'login.action.credential-undecodable': '点「登录」重建本机凭据（坏件已改名留档）',
  'login.reason.credential-write-denied': '凭据写不进去（权限或占用）',
  'login.action.credential-write-denied': '不要再反复点；先退出其他实例再重试，仍失败则点「清理并重登」',
  'login.reason.credential-acl-not-applied': '凭据文件权限未收窄（安全隐患）',
  'login.action.credential-acl-not-applied': '共享机器上请检查该文件权限；日志已记录',
  'login.reason.credential-delete-denied': '本机凭据清理不掉（权限或占用）',
  'login.action.credential-delete-denied': '先退出其他实例再试；仍失败请在停机后手工改名该文件留档',
  // 其余登录腿（无本地文件可清 ⇒ 不给清理入口）
  // coldstart DX 批②（2026-10-14）：出路文案带两种逃生口（--net staging / 显式放行开关）。
  'login.reason.enroll-refused-isolated-home': '调试/隔离实例未放行生产网：登录被本地隔离护栏拒绝（非网络故障）',
  'login.action.enroll-refused-isolated-home': '两种出路：① 用 staging 档重启调试实例（冷启动脚本加 --net staging，登录即入网 staging 网络）；② 确需进生产网时显式设 NEBFLOW_ALLOW_PROD_ENROLL=1 后重试',
  'login.reason.server-no-device-token': '登录服务器没有发回设备凭据',
  'login.action.server-no-device-token': '点「重试」；仍失败则「使用其他账号登录」',
  'login.reason.token-exchange-failed': '与登录服务器交换令牌失败',
  'login.action.token-exchange-failed': '点「重试」；仍失败则「使用其他账号登录」',
  'login.reason.device-register-failed': '登录服务器设备注册失败',
  'login.action.device-register-failed': '点「重试」；仍失败则「使用其他账号登录」',
  // 单设备单账号批（机器指纹闸 409；码 = 服务端 wire reason 逐字同源）。
  'login.reason.device_bound_other_account': '此设备已登录另一个账号：同一台设备同一时间只能登录一个账号',
  'login.action.device_bound_other_account': '先在本机退出原账号（如原实例仍在本机运行），或到官网「设备管理」移除该设备后再登录',
  'login.reason.device_already_logged_in': '此设备已有一个登录中的实例：同一台机器同一时间只能登录一个账号',
  'login.action.device_already_logged_in': '在原有实例中退出登录后重试；隔离测试请使用测试账号',
  'login.reason.callback-state-invalid': '登录回调校验失败（一次性校验码不匹配或已过期）',
  'login.action.callback-state-invalid': '回到 {lowerBrand} 重新点「登录」',
  'login.reason.provider-error': '登录被服务方拒绝或中断',
  'login.action.provider-error': '重新点「登录」；必要时换一个账号',
  'login.reason.service-unavailable': '{lowerBrand} 服务未初始化',
  'login.action.service-unavailable': '重启 {lowerBrand} 后重新登录',
  'login.reason.logto-not-configured': '登录服务未配置（缺少应用标识）',
  'login.action.logto-not-configured': '检查 {lowerBrand} 的登录配置后重试',
  // 账号归属闸三条（logout-login-recovery 批 2026-11-08，与后端 CredentialFailure 同码单源）。
  'login.reason.account-switch-requires-logout': '本机的账号根属于另一个账号：同一台机器同一时间只能服务一个账号',
  'login.action.account-switch-requires-logout': '先在本机退出当前账号，然后再登录新账号（退出登录不会删除本机账号数据）',
  'login.reason.enroll-refused-no-account-root': '本机处于未登录层（还没有账号根）：登录服务已连通，但设备注册在这个状态下不可完成',
  'login.action.enroll-refused-no-account-root': '本机账号数据不受影响；该状态需要账号根建立流程处理，当前版本暂未提供，请不要反复重试',
  'login.reason.account-identity-missing': '登录服务没有返回账号身份：无法确认这台机器该归属哪个账号',
  'login.action.account-identity-missing': '点「重试」重新登录；仍失败请附日志反馈',
  'login.reason.login-failed': '登录过程中出现未分类的错误',
  'login.action.login-failed': '点「重试」；仍失败请附日志反馈',
  'messages.login': '登录',
  'messages.reconnecting': '连接已断开，正在重连…',
  'messages.networkError': '网络错误，请检查连接',
  'messages.inputPlaceholder': '发送消息',
  'messages.voiceInput': '语音输入',
  'messages.send': '发送',
  // rcptcode 批（2026-09-20）：好友腿**终态**分态文案（消费点 = `messages.js` 的
  // `FRIEND_TERMINAL_TEXT` 白名单 + 未知码回退 `sendFailedCode`）。码集 = `friendsApi.js`
  // 的 `FRIEND_TERMINAL_CODES`（同源单表）。中英同批新增（禁单语新增）。
  'messages.friendNotFriends': '你们已不是好友，消息未发送',
  'messages.friendNotBlocker': '你并未拉黑对方，该操作不可用',
  'messages.friendReplyTargetInvalid': '被引用的消息已不可用，消息未发送',
  'messages.sendFailedCode': '发送失败（{code}）',
  'messages.yesterday': '昨天',
  'messages.ariaUnread': '{n} 条未读消息',
  'messages.ariaActionable': '{n} 项待你处理',
  'messages.copy': '复制',
  // fm-md-social 批（2026-11-02）：好友/群聊/设备消息里的 markdown 外链点击**二次确认**
  // （正文来自外部，外跳动作要确认；确认框 = 全站既有 __showConfirm）。
  'messages.mdLinkTitle': '打开外部链接',
  'messages.mdLinkConfirm': '这条消息来自外部，链接指向 {host}。确认在新窗口打开？',
  'messages.mdLinkOpen': '打开',
  'header.memory': '记忆',
  'header.bgTasks': '后台任务',
  'input.placeholder': '输入消息...',
  'input.ph.attachments': '已附加文件——输入一句话一起发送',
  'input.ph.listening': '聆听中…',
  'input.ph.drag': '按住语音球右滑，进入全双工语音',
  'input.askPlaceholder': '输入你的问题...',
  'input.skillPlaceholder': '输入指令补充...',
  'input.compactPlaceholder': '输入压缩指令（可选），直接回车开始压缩...',
  'input.voiceListening': '正在聆听...',
  'input.voiceBtn': '语音输入',
  'voiceMode.hint': '直接说话即可——消息自动发送，回复自动朗读。按 Esc 退出。',
  'voiceMode.thinking': '正在思考…',
  'voiceMode.speaking': '正在朗读',
  'voiceMode.interrupted': '已打断',
  'voiceMode.error': '语音暂不可用。请检查麦克风权限或网络连接，然后退出并重试。',
  'voiceMode.sendFailed': '消息未发送——连接已断开',
  'input.voiceHint': '松开发送',
  'input.voiceError': '语音输入不可用，请使用 Chrome 浏览器',
  'input.queued': '排队中',
  'input.sendImmediate': '立即发送',
  // 对话层活性批（2026-10-22）：插话即时确认标 + 断连横幅文案。
  'ws.connBanner.reconnecting': '连接断开 · 正在重连',
  'ws.connBanner.pending': '连接断开 · 正在重连 · {n} 条消息待送达',
  // f1-send-queue（2026-10-19）：断线入队的待送达小标 tooltip + 队列溢出告警。
  'chat.pendingSend': '待送达 · 重连后自动补发',
  'input.queueOverflow': '消息队列已满（100 条）：最早的一条已丢弃',
  // 输入消息排队回退批（2026-10-23 作者令「我们的输入消息排队功能采用我们的
  // 之前的。大概两天以前的设计」）：排队面恢复 10-21 形态——队列条行级明细
  // （文本/附件缩略/立即发送/撤回/移除/拖拽排序/点击展开）回归；10-22 排队各批
  // 的头行秒表/条数/失败计数/气泡右键队列动作键随呈现面一并退役（撤回按钮
  // title 一直是字面 fallback，chat.recall 键从不存在的口径维持）。
  'chatQueue.dragReorder': '拖动以排序',
  'chatQueue.clickExpand': '点击展开',
  'chatQueue.clickCollapse': '点击收起',
  // qattachvault（2026-10-21）：载荷丢失（刷新时未能落盘）的显式失败态 —— 禁静默：
  // 停止自动补发，队列条注记 + 气泡红 ! + toast；撤回重编重新添加附件 = 可重传。
  'input.queueAttachLost': '附件文件已丢失（页面刷新时未能保存），此条已停止自动补发——撤回后重新添加附件再发送',
  'input.queueAttachLostShort': '附件已丢失 · 无法自动补发',
  'input.pasteTooLarge': '粘贴内容过大（>300KB），已改为直接插入文本',
  'input.removeQueued': '移除',
  // fwdguard-impl (2026-09-17)：空文本 + 纯引用（转发）帧的闸提示 —— 该形态会被
  // 网关 4055 判空静默丢弃 ⇒ 前端禁用发送并显示本句（作者逐字口径）。
  'input.refOnlyHint': '请附一句话后发送',
  // @-mention 项目自动补全（mention-routing 批，2026-09-27）：数据源 =
  // GET /api/projects（与项目面板同源），文案仅浮层无匹配态与 ARIA 标签。
  'mention.title': '项目',
  'mention.noMatch': '无匹配项目',
  // `#` 待办引用补全（待办结构化批 2026-11-05）：文案只写状态事实，不写操作教学。
  'todoComplete.title': '待办',
  'todoComplete.noMatch': '无匹配待办',
  'todoComplete.detailUnavailable': '这条待办不在当前列表里',
  // 卡片点击腿（todo-card-click-left 批，2026-11-18）：输入框被拆除时不许静默。
  'todoComplete.composerMissing': '找不到可输入的对话框，引用没插入',
  'slash.title': '斜杠命令',
  'modal.newSession': '新建会话',
  'modal.sessionName': '会话名称',
  'modal.cancel': '取消',
  'modal.create': '创建',
  'modal.deleteSession': '删除会话',
  'modal.deleteConfirm': '删除',
  // === 实体图标语义化 + 主 Agent 独立分区（20260824_entity-icons-visual-spec §6）===
  // Nebula 退役批（作者令 2026-11-01）：未命名的根 agent 读通用称呼，机制名永不
  // 作为显示回落（读点 = js/rootName.js#rootGenericName）。
  'agent.rootGeneric': '主 Agent',
  'agents.group.orchestrator': '编排者',
  'agents.group.standalone': '独立智能体',
  'agents.badge.orchestrator': '编排者',
  'memory.title': '记忆',
  'memory.agent': '智能体',
  'memory.user': '用户',
  'memory.scopeAgent': '智能体 · memory.md',
  'memory.scopeUser': '用户 · User.md',
  'memory.edit': '编辑',
  'memory.done': '完成',
  'memory.emptyHint': '暂无记忆内容',
  'memory.placeholder': '# 记忆\n\n关键事实、决策、上下文...',
  'memory.save': '保存',

  // === Settings panel ===
  'settings.runtime': '运行时',
  'settings.thinkingMode': '思考模式',
  'settings.llmLog': 'LLM 日志记录',
  'settings.workSchedule': '冻结时间（非工作时间）',
  // 自解释批（2026-11-04 铁律 8）：原文尾句「点击「跳过本次」即可立即解冻」= 行为教学，
  // 删除——「跳过本次」按钮名即动作（细则 b 动作标签）。保留的是冻结期间的事实状态。
  'settings.workScheduleOnHint': '冻结时段内 agent 在工具执行完成后暂停，不调用 LLM（零 token 消耗），到点自动恢复。期间输入栏与麦克风禁用。',
  'settings.workScheduleOffHint': '默认关闭。关闭时所有 agent 全天候工作，行为与现状完全一致。',
  'settings.addSegment': '添加冻结时段',
  'settings.removeSegment': '删除该时段',
  'settings.segmentLabel': '时段 {n}',
  'settings.segmentEdit': '编辑冻结时段',
  'settings.segmentTo': '至',
  'settings.scheduleInvalidTime': '时间格式应为 HH:mm',
  'settings.scheduleInvalidRange': '时段起止不能相同',
  'settings.scheduleEmpty': '开启时至少需要一段冻结时间',
  'settings.segmentCrossMidnight': '跨午夜 · 冻结至次日',
  'settings.scheduleSaved': '冻结时间已保存',
  'settings.scheduleSave': '保存冻结设置',
  'settings.scheduleReset': '恢复',
  'settings.scheduleDirtyHint': '编辑未保存 · 点「保存」后冻结引擎才生效',
  'settings.sttTitle': '语音转文字',
  'settings.sttAdvanceTitle': 'STT 高级设置',
  'settings.sttConfiguredStatus': '已配置云端 STT：{endpoint} · {model}',
  'settings.sttUnconfiguredStatus': '未配置，使用浏览器内置语音识别（免费）',
  'settings.sttEndpoint': 'STT 端点',
  'settings.sttModel': '模型',
  'settings.sttModelPlaceholder': '如 whisper-1（可留空使用服务端默认）',
  'settings.sttApiKey': 'API 密钥',
  'settings.sttKeyConfigured': '已配置',
  'settings.sttApiKeyPlaceholder': '仅保存在本机，不会回显',
  'settings.sttApiKeyConfiguredPlaceholder': '已配置 · 留空保持不变（清除配置可移除）',
  'settings.sttHint': '配置后语音输入将经由该端点转写；全部清空则回到浏览器免费识别。',
  'settings.sttSave': '保存 STT 配置',
  'settings.sttClear': '清除配置',
  'settings.sttSaved': 'STT 配置已保存',
  'settings.sttCleared': '已清除 STT 配置，恢复浏览器识别',
  'settings.ttlAdvanceTitle': '旧工具结果清理',
  'settings.ttlEnabled': '清理旧工具结果',
  'settings.ttlEnabledHint': '超过保存时限且符合清理条件的旧工具结果将替换为占位符，不再占据模型上下文（历史记录保持不变）',
  'settings.ttlMinutesLabel': '结果保留时限（分钟）',
  'settings.ttlMinutesHint': '超过此时限的结果才可清理（默认 60）',
  'settings.keepRecentLabel': '保留最近 N 条',
  'settings.keepRecentHint': '最近 N 条结果永不清理（默认 5）',
  'settings.ttlSave': '保存清理配置',
  'settings.ttlSaved': '清理配置已保存',
  'settings.ttlSaveFailed': '清理配置保存失败',
  'settings.ttlInvalidInt': '{field}必须是整数',
  'settings.ttlOutOfRange': '{field}需在 {min} – {max} 之间',
  /* micOrb 配色预设系统 (v8.3.0, 设计 20260902_micorb-presets-design.md) —
     「外观」section：基调 / 状态映射 / 自定义。预设名走注册表内建 zh/en。 */
  'settings.appearance': '外观',
  'settings.appearance.orbBase': '光球基调',
  'settings.appearance.orbBaseHint': '空闲（品牌主态）使用的预设色板；可在下方状态配色中单独覆盖。',
  'settings.appearance.stateMap': '状态配色',
  'settings.appearance.stateMapHint': '配色即状态语言：九个状态各自映射一个预设色板，改动即时生效、无需保存。',
  'settings.appearance.custom': '自定义基调',
  'settings.appearance.customHint': '在基底预设上覆盖槽位颜色；未改动的槽位继承预设。自定义对基调板生效。',
  'settings.appearance.customSource': '基底预设',
  'settings.appearance.customThemeDark': '暗色板',
  'settings.appearance.customThemeLight': '亮色板',
  'settings.appearance.slotA': '基色 a · 球体主色调',
  'settings.appearance.slotB': '流纹 b · 第二色相涡流',
  'settings.appearance.slotC': '暗纹 c · 深色基底',
  'settings.appearance.restore': '还原此槽位',
  'settings.appearance.warnC': '过暗/过灰：暗色模式下可能发闷，建议提高饱和度',
  'settings.appearance.warnA': '基色过暗：冻结/离线等降亮度状态下可能不可辨',
  'settings.appearance.preview': '实时预览（与输入框光球同一渲染管线）',
  'settings.appearance.reset': '重置外观',
  'settings.appearance.resetDone': '外观设置已重置',
  'settings.appearance.saveFailed': '本地存储不可用，本次改动仅当前会话生效',
  'stt.timeout': '语音转写超时，请重试',
  'manage.stop': '停止',
  'manage.retry': '重试',
  'manage.stuck': '卡死 · {secs}s 无活动',
  'manage.stuckAutoRestart': '卡死 · 正在自动重启',
  // Hard-recovery P7（2026-09-07）：标签与后端真实执行的动作一一对应
  'manage.stuckHardAbort': '卡死 · 正在强制中断',
  'manage.stuckFailed': '卡死 · 需人工处理（进度已保存）',
  // Sub-Agents 面板（2026-08-25 subagents-panel spec）——状态标签与空态
  'subagents.header': '子智能体 · {count}',
  'subagents.empty': '当前无运行中的子智能体',
  'subagents.indicatorTitle': '后台子智能体',
  'subagents.status.running': '进行中',
  // 显式等待停靠态（Waiting）：行状态位直显「停靠等待中」（legacy 快照无申报槽的
  // Waiting 行同词；申报 standby 的行走 standby 词）。
  'subagents.status.waiting': '停靠等待中',
  'subagents.status.waitingMerge': '停靠等待中',
  // delegate 显式待命态（状态槽 standby）——行状态位直显「待命」
  // （tooltip = 申报的「在等什么」）。
  'subagents.status.standby': '待命（等作者）',
  'subagents.status.idle': '空闲',
  // #752 空闲 delegate 在等什么（细分注记 + 长空闲提醒徽标）。
  'subagents.waitingBg': '等后台任务 ×{count}：{desc}（{dur}）',
  'subagents.waitingReceipts': '等子代理回执 ×{count}',
  'subagents.longIdle': '空闲 {mins} 分钟，无在飞工作',
  // 陈旧后台代理面板批（2026-11-08）：底层任务记录已终态而注册表行滞留 ⇒ 行显式标
  // 「已结束」，运行时长冻结为真实时长（荒谬值拒收、零渲染）。
  'subagents.ended': '已结束',
  'subagents.endedTip': '该会话已结束（任务记录已终态）——时长为真实运行时长，不再走表',
  'subagents.status.stuck': '卡死',
  'subagents.status.error': '失败',
  'subagents.status.frozen': '已冻结',
  // 错误冻结批（2026-11-01 · 作者令「冻结态方便被唤醒 接着做」）：**错误冻结**
  // （delegate 因 LLM/工具/引擎故障或配额取消就地停住）的行面文案——徽标带原因
  // 短名、tooltip 说明「不是失败判词」、唤醒按钮 = 显式唤醒动作（同会话续跑）。
  // 与 standby（待命·等作者）/ waiting（停靠等待）显式区分：三者都是「停」，
  // 但只有本族是**异常停**且**必须有人唤醒才会继续**。
  'subagents.frozenBadgeWithReason': '已冻结 · {reason}',
  // 冻结通知策略批（2026-11-05 作者令两连）：provider 故障 = **基础设施故障**，不是
  // 任务失败 ⇒ 不向上汇报（上级多半也在同一波里挨饿），面板即状态面：标「待你处理」+
  // 原因码 + 自动续跑次数 + 唤醒入口。
  'subagents.frozenAwaitingYou': '已冻结 · {reason}（待你处理）',
  'subagents.frozenAwaitingYouNoReason': '已冻结 · 待你处理',
  // 修复批 2026-11-06（作者原话「llm 不稳定 不需要向上通知…原地等待恢复就行」）：
  // 可恢复冻结（llm-failure / 额度）**不再是你的待办**——词从「待你处理」改成
  // 「等待恢复中」，与真失败（tool-failure / engine-fault）在视觉与文案上分开。
  'subagents.freezeRecovering': '等待恢复中',
  'subagents.freezeRecoveringReason': '等待恢复中 · {reason}',
  'subagents.freezeRecoveringTip': '供应商暂时不可用（不是任务失败，也不需要你做什么）：引擎正在退避重试 / 换可用的供应商，恢复后在原会话上接着跑——已完成的不重做。不向上汇报，也不需要唤醒。',
  'subagents.frozenInfraTip': '供应商故障（不是任务失败）：引擎自己探测恢复，恢复后自动续跑同一会话（续在检查点、已完成的不重做，有次数上限与递增间隔）；也可以现在点「唤醒」立即续跑。不向上级汇报——本面板就是它的状态面。',
  'subagents.frozenAutoResumes': '已自动续跑 {n} 次',
  'subagents.frozenAutoResumesSpent': '自动续跑次数已用尽——需要你：充值 / 换模型 / 取消',
  'subagents.providerFailureGroup': '后台 {count} 个任务服务商不可用 · {provider}',
  'subagents.status.roundComplete': '本轮完成，可续用',
  'subagents.freezeDigestRecoverable': '{count} 条已冻结（可唤醒）',
  'subagents.freezeDigestNeedsHuman': '{count} 条冻结待你处理',
  'subagents.frozenTip': '已冻结：引擎/上游故障导致就地停住，这不是失败判词。会话历史、工作区与未提交改动、台账行全部保留；点「唤醒」或 Mail 它的地址即在原会话上下文上继续（已完成的不重做）。',
  'subagents.frozenReason': '原因：{reason}',
  'subagents.frozenDormantTip': '已冻结 {hours} 小时仍无人唤醒（超保留期会归档记录，转录与工作区保留）。',
  'subagents.wake': '唤醒',
  'subagents.wakeTip': '在原会话上下文上继续（同一会话、历史在场，已完成的不重做）',
  // name/address 批（2026-11-05）：地址 chip 的 tooltip 后缀（前面拼完整地址本身）。
  'subagents.copyAddress': '复制地址',
  'subagents.wakeFailed': '唤醒失败：记录与转录已保留，可稍后重试',
  // 冻结原因四类短名（快照恢复路径按线值 `delegate-frozen:<code>` 本地映射；与
  // DelegateFreeze.reasonLabelZh 逐字一致）。
  'subagents.frozenReasonLlm': '模型层故障',
  'subagents.frozenReasonTool': '工具层故障',
  'subagents.frozenReasonEngine': '引擎侧故障',
  'subagents.frozenReasonQuota': '配额耗尽取消',
  // 冻结态误报批（2026-11-05 · 作者报障「显示已冻结，但我没有冻结它，也没有恢复
  // 按钮」）：冻结行分两族呈现——**自动恢复族**（时间表出窗 / 退避 / 供应商探测 /
  // 断点续跑）写「在等什么 + 会自己续跑」，**不给按钮**；**需人介入族**（循环检测 /
  // 错误冻结 / 已升级到人）才写「已冻结」并给恢复入口。判定单点 = 后端
  // nebflow.actor.FreezeDisplay（前端 freezeState.js 只做呈现映射）。
  'subagents.freezeBadge': '已冻结',
  'subagents.freezeBadgeWithReason': '{word} · {reason}',
  'subagents.freezeWindow': '等待工作时段',
  'subagents.freezeAuto': '自动恢复中',
  'subagents.freezeWindowTip': '工作时段外的自动停靠：到工作时段自行恢复。',
  'subagents.freezeAutoTip': '引擎在退避重试/等待外部恢复：到期自行续跑，取消任务才终止它。',
  'subagents.freezeReasonLoop': '循环检测',
  'subagents.freezeReasonLlmTransient': '模型层故障',
  'subagents.freezeReasonNetwork': '网络抖动',
  'subagents.freezeReasonProviderDown': '供应商不可用',
  'subagents.freezeReasonRestartRecovery': '断点续跑',
  'subagents.freezeReasonWaitingNetwork': '等待网络恢复',
  'subagents.freezeReasonSchedule': '工作时段',
  'subagents.status.done': '已完成',
  // 面板树批（2026-10-29 ①）：孤儿行来源角标（父不可见提升顶层的「来自 …」标注）。
  'subagents.fromDelegate': '来自已结束的委托',
  'subagents.fromFlowNode': '来自 AgentFlow 节点',
  'subagents.fromSubagent': '来自上层子代理',
  'subagents.orphanTip': '父会话已不可见 · {sid}',
  // 野节点归置批（2026-11-05 腿③）：野节点（父不可解析的行）独立归置分组的组头词。
  // 一个事实词（这群行没有父），不是解释句/教学句（自解释铁律 8）。
  'subagents.orphanGroup': '无父节点',
  // 面板三代层级批（2026-11-06）：root 锚（树 depth 0 基座标签）+ 判定表②「父不在场」
  // 标——都是事实词（这一层是根 / 父会话当前不在面板），非解释句。
  'subagents.rootAnchor': '根会话',
  'subagents.hierarchyLevel': '层级 {level}',
  'subagents.parentAbsent': '父不在场',
  'subagents.parentAbsentTip': '父会话当前不在面板 · {sid}',
  // DAG 依赖边 chip（face C）：执行顺序依赖 ≠ 父子嵌套——词面只标注依赖关系。
  'subagents.dependsOn': '依赖',
  'subagents.dependsOnTip': '执行依赖（DAG）：{deps}',
  // 面板层级批（2026-10-31 ①）：组头折叠 caret 的无障碍/悬停文案（随折叠态翻转）。
  'subagents.collapseGroup': '收起子任务',
  'subagents.expandGroup': '展开子任务',
  // 术语统一批（2026-11-06 作者令逐字「统一 根据英文」）：kind 徽标词**整族退役**
  // ——subagents.kindDispatcher / kindNode / kindAgentflow 三键随本批删除。身份/类型
  // 徽标词面（kernel / dispatcher / node / subagent / delegate / AgentFlow）是作者
  // 术语、不本地化，单源 = js/termWords.js（BADGE_WORDS）；放语言包等于给同一概念
  // 造第二张词表（中文面「分发器 / 节点」就是这么来的）。判据 = scripts/check-badge-words.mjs。
  // 保留：delegate 已终态而链仍在跑的延续态徽标（琥珀词 + workflow 图标）。
  'subagents.chainRunning': '链运行中',
  'subagents.chainRunningTip': '委托已交付，链仍在运行 · {n} 个会话',
  // 面板行说明批（2026-10-29 ②）：任务摘要次行无文案键（原文上屏）。
  // 任务归属徽标（2026-09-27 taskbadge 批）：行内 `#号 · 标题` 徽标的悬停提示
  'subagents.taskBadge': '所属任务：{text}',
  'manage.uptime': '运行 {time}',
  'manage.readonlyTeam': '团队 agent 只读，不可操作',
  'manage.readonlyFlow': 'Flow 节点 agent 只读，不可操作',
  'manage.stopSent': '已发送停止指令',
  'manage.stopFailed': '停止失败',
  'manage.retrySent': '已发送重试指令',
  'stt.micDenied': '麦克风权限被拒绝，请在浏览器地址栏授权后重试',
  'stt.micBusy': '麦克风被其他应用或页签占用（请关闭正在使用麦克风的应用后重试）',
  'stt.micNoDevice': '未检测到可用的麦克风设备',
  'stt.noAudio': '未录到音频，请按住麦克风按钮再说话',
  'settings.thinkingEffort.off': '关闭',
  'settings.thinkingEffort.minimal': '最小 — 最快最省的轻思考',
  'settings.thinkingEffort.low': '低 — 轻思考',
  'settings.thinkingEffort.medium': '中 — 平衡',
  'settings.thinkingEffort.high': '高 — 深度思考',
  'settings.thinkingEffort.max': '最高 — 最深思考',
  // thinking-config Phase 2（2026-10-29）已随 settings-panel-fourfix 批 ①件退役
  // （作者令「不要每个模型都能配置思考模式」）：thinkingPerModel/Inherit/
  // Effective/ForcedBadge/MediumMap 五键随 per-model 控件一并移除——思考档位
  // 唯一配置点 = 上方 settings.thinkingMode 全局下拉。
  // === 权限模式设置页控件（2026-09-12 批）已于 permshield F1（2026-09-13）删除 ===
  // 7 个 `settings.safetyMode*` 键随控件一并移除（作者重裁候选 B ①「删设置页」）。
  // 档位指引改由盾牌承载：`bypass.title.*`（三档 tooltip）+ `bypass.modeSetGlobal`
  // （切换后的持久化反馈）——旧键 `settings.safetyModeHint` 里「顶栏盾牌…重启后回到
  // 此处设置」的悬空指引随之消失（控件已不存在，指引不再指向死入口）。
  'settings.language': '语言',
  'settings.autostart': '开机自启动',
  'settings.autostartUnsupported': '当前运行形态不支持开机自启',
  'settings.autostartOn': '已开启开机自启，登录后 {brand} 将自动启动',
  'settings.autostartOff': '已关闭开机自启',
  // 「消息设备标记」设置区文案键（settings.deviceStamp* 八键）已随标注全自动批
  // （2026-10-26 作者令「消息来源识别应该是自动的」）退役：手动命名/绑定面拆除，
  // 来源识别由 deviceIdentity.js 按花名册自动判定，无需设置项。
  'settings.autostartFailed': '开机自启设置失败',
  'settings.rerunOnboarding': '重新运行新手引导',
  // ── Onboarding 重设计（chat-native，onboarding-redesign-spec v1.0）──
  // 模拟流式打招呼（§3.1）
  // 问题树（§3.2）
  'onboarding.btn.testAndSave': '确认并测试',
  // 摘要/完成/错误（§3.5/§5.3/§5.4）
  // 回归用户偏好卡（S7）
  // 保留（既有）：later / probing / probeFailed / probeTimeout / noThanks
  'onboarding.probing': '正在验证模型连接…',
  'onboarding.probeFailed': '模型连接验证失败：{error}。请检查配置后重试。',
  'onboarding.probeTimeout': '验证超时，请检查网络后重试。',
  // ── Onboarding v2 对话式问卷（personal-agent 批 2026-10-04，真源 = demo v2 SCRIPT）──
  // 幕名 / 进度 / 通用控件
  'onboarding.seg.meet': '第一幕 · 见面',
  'onboarding.seg.brain': '第二幕 · 接上大脑',
  'onboarding.seg.expect': '第三幕 · 你对我的期待',
  'onboarding.seg.you': '第四幕 · 认识你',
  'ob2.progress': '第 {n} / {total} 题',
  'ob2.btn.skip': '跳过这一题',
  'ob2.btn.sayMyself': '都不是，我自己说',
  'ob2.btn.useThis': '用这句',
  'ob2.btn.picked': '选好了',
  'ob2.typing.skip': '跳过打字',
  // 整流程跳过（Onboarding 跳过批 2026-10-30 作者令）：随时跳过进入主界面，
  // 状态落 `skipped`（不再反复弹），设置里保留重新进入入口。
  'ob2.flow.skip': '跳过',
  'ob2.flow.skipEcho': '好 —— 新手引导先到这里。以后想继续，设置里可以随时重新进入。',
  'ob2.flow.skipFailed': '跳过状态保存失败：{error}。',
  'ob2.ph.free': '用你自己的话写…',
  'ob2.source.unnamed': '还没有名字的我',
  // 开场
  'ob2.greet1': '你好。第一次见面。',
  'ob2.greet2': '我是为你而生的那个智能体 —— 不过此刻，我还没有名字，没有性格，也没有关于你的任何记忆。',
  'ob2.greet3': '接下来我会一个问题一个问题地问你，像做一次很短的问卷。答不上的、不想说的，随时跳过 —— 跳过不会卡住我们，只会让那一页先空着。',
  'ob2.greet4': '你说的话最后会变成两份记忆：Soul.md 是「我是谁」，由你对我的期待塑成；User.md 是「你是谁」。',
  'ob2.collected': '问卷收讫。现在，请允许我当着你的面，把自己写出来。',
  // 第一幕 · 见面
  'ob2.intro.meet1': '我们从最小的开始。',
  'ob2.intro.meet2': '有三件事必须先有：我叫什么、我该怎么叫你、我用什么脑子。这三件之后，其余全是可选的。',
  'ob2.lead.meet1': '第一件，最重要的一件。',
  'ob2.lead.meet2': '第二件。',
  'ob2.q.name': '给我起个名字吧 —— 你希望以后怎么叫我？',
  'ob2.ph.name': '输入名字，1–16 个字…（想不出来也可以跳过）',
  'ob2.q.call': '那我该怎么称呼你？',
  'ob2.ph.call': '昵称就好…（不方便说就跳过）',
  'ob2.label.name': '名字',
  'ob2.label.call': '怎么称呼你',
  // 第二幕 · 接上大脑
  'ob2.lead.brain1': '第三件：我得有个脑子。',
  'ob2.lead.brain2': '从你已经配置好的服务商里选一个就行；也可以在这里新加一家。',
  'ob2.q.model': '你打算让我用哪个模型？',
  'ob2.label.model': '模型',
  'ob2.model.loading': '正在读取已配置的服务商…',
  'ob2.model.none': '还没有配置任何服务商 —— 先加一家（地址 / Key / 模型 ID），或者跳过这一题。',
  'ob2.model.addProvider': '新加一家服务商',
  'ob2.model.ctx': '上下文 {n} tokens',
  'ob2.model.probeOk': '✓ 连通 · {model}',
  'ob2.model.echoOk': '已连接 {model}',
  'ob2.model.writeErr': '（模型配置写回失败：{error}）',
  'ob2.model.ctxLabel': '上下文大小',
  'ob2.model.ctxApply': '用这个窗口',
  'ob2.model.ctxHint': '留空则沿用模型自己的配置值；改完之后这一项也在「设置」里可见、可改。',
  // 第三幕 · 你对我的期待
  'ob2.intro.expect1': '基础的三件齐了。接下来的问题，是把我捏成你要的样子。',
  'ob2.intro.expect2': '没有标准答案，也不是考试 —— 答不上来就跳过，我会留白，不催你。',
  'ob2.lead.role': '先说最根本的一件。',
  'ob2.q.role': '你希望我在你生活里扮演什么角色？',
  'ob2.opt.role.partner': '交心的伙伴',
  'ob2.opt.role.keeper': '靠谱的管家',
  'ob2.opt.role.sparring': '思想陪练',
  'ob2.opt.role.muse': '创作缪斯',
  'ob2.opt.role.all': '全都要',
  'ob2.fh.role': '比如：白天帮我理清工作，晚上陪我复盘',
  'ob2.label.role': '角色定位',
  'ob2.lead.style': '接下来是我的嘴。',
  'ob2.q.style': '你喜欢我怎样说话？',
  'ob2.opt.style.direct': '直接干脆',
  'ob2.opt.style.gentle': '温和细腻',
  'ob2.opt.style.humor': '幽默爱玩',
  'ob2.opt.style.rigorous': '严谨专业',
  'ob2.fh.style': '比如：像老朋友，但别油嘴滑舌',
  'ob2.label.style': '语言风格',
  'ob2.lead.pro': '还有分寸。',
  'ob2.q.pro': '我该多主动？',
  'ob2.opt.pro.quiet': '安静守候',
  'ob2.opt.pro.quietDesc': '你叫我，我才动',
  'ob2.opt.pro.remind': '适度提醒',
  'ob2.opt.pro.remindDesc': '重要的事我会说',
  'ob2.opt.pro.active': '主动张罗',
  'ob2.opt.pro.activeDesc': '看到值得做的，我开口',
  'ob2.fh.pro': '比如：工作的事主动点，生活的事别插嘴',
  'ob2.label.pro': '主动程度',
  'ob2.lead.boundaries': '有些事我想先跟你约好，免得以后踩线。',
  'ob2.q.boundaries': '哪些「相处约定」你希望我遵守？',
  'ob2.opt.bd.argue': '可以跟我争论',
  'ob2.opt.bd.argueDesc': '你认为我错了就直接说，别顺着',
  'ob2.opt.bd.remember': '记住随口提过的小事',
  'ob2.opt.bd.rememberDesc': '人名、日期、我提过的偏好',
  'ob2.opt.bd.honest': '状态不对请直说',
  'ob2.opt.bd.honestDesc': '连续熬夜、情绪低落时提醒我',
  'ob2.opt.bd.hands': '别碰我的工作清单',
  'ob2.opt.bd.handsDesc': '未经允许不要替我安排任务',
  'ob2.fh.boundaries': '比如：别在我专注的时候打断我',
  'ob2.label.boundaries': '相处约定',
  'ob2.lead.temper1': '再往下是两道选择题 —— 别想太久，第一感觉最准。',
  'ob2.q.temper1': '说话的时候，你更想要哪一种？',
  'ob2.opt.t1.direct': '直接坦率',
  'ob2.opt.t1.gentle': '温和委婉',
  'ob2.fh.temper1': '比如：该直接就直接，但别伤人',
  'ob2.label.temper1': '语气基线',
  'ob2.lead.temper2': '最后一道。',
  'ob2.q.temper2': '做事的方式呢？',
  'ob2.opt.t2.plan': '严谨计划',
  'ob2.opt.t2.casual': '随性灵活',
  'ob2.fh.temper2': '比如：大事有计划，小事随缘',
  'ob2.label.temper2': '做事方式',
  // 第四幕 · 认识你
  'ob2.intro.you1': '我这边差不多成型了。',
  'ob2.intro.you2': '最后一个问题不是关于我，是关于你 —— 我记事只为你一个人。',
  'ob2.lead.identity': '先问个大概。',
  'ob2.q.identity': '你的日常身份更接近哪一种？',
  'ob2.opt.id.dev': '程序员 / 工程师',
  'ob2.opt.id.student': '学生',
  'ob2.opt.id.researcher': '研究者',
  'ob2.opt.id.creator': '创作者',
  'ob2.opt.id.freelance': '自由职业',
  'ob2.opt.id.other': '其他',
  'ob2.fh.identity': '比如：白天写代码，晚上做播客',
  'ob2.label.identity': '日常身份',
  // 希望的交流方式（output 块语法批④，作者令 2026-10-31）：两题，选项描述
  // 给类微信风格示例作参考；答案落 User.md「交流方式」段，驱动 <output> 块内
  // 风格（无画像则默认中文简洁直白）。≤3 问裁定内。
  'ob2.lead.out1': '最后两题，关于你希望我怎么开口说话。',
  'ob2.lead.out2': '选项里都带了示例回答，照着你的直觉选就行——之后随时一句话改。',
  'ob2.q.outshape': '平时回复你想看多少？',
  'ob2.opt.outshape.min': '只说结论',
  'ob2.opt.outshape.minDesc': '示例：「改好了，测试全过。」',
  'ob2.opt.outshape.std': '结论＋关键两句',
  'ob2.opt.outshape.stdDesc': '示例：「改好了，测试全过。动了登录和缓存两块，别的没碰。」',
  'ob2.opt.outshape.full': '来龙去脉都要',
  'ob2.opt.outshape.fullDesc': '示例：结论在前，后面补上为什么这么做、改了哪里、要注意什么。',
  'ob2.fh.outshape': '比如：先一句话结论，细节我追问再说',
  'ob2.label.outshape': '交流篇幅',
  'ob2.q.outtone': '说话风格偏向哪种？',
  'ob2.opt.outtone.direct': '直白干练',
  'ob2.opt.outtone.directDesc': '零客套，直接给结论，像工作消息。',
  'ob2.opt.outtone.easy': '轻松友好',
  'ob2.opt.outtone.easyDesc': '可以口语化一点，像朋友闲聊天。',
  'ob2.fh.outtone': '比如：像微信一样短句，别用书面语',
  'ob2.label.outtone': '说话语气',
  // （ob2.lead.focus / ob2.q.focus / ob2.ph.focus / ob2.label.focus 四键已整键删除——
  //   作者 2026-10-05：「最近半年，你最想解决的一件事是什么？不要这个问题。」）
  // 作答回执 / 承接句
  'ob2.echo.skip': '（这一题先跳过）',
  'ob2.echo.boundaries': '约定：{v}',
  'ob2.follow.skip': '没关系，这一题先空着 —— 留白也是一种答案。',
  'ob2.follow.free': '我把你的原话原样收下了：「{v}」',
  'ob2.follow.choice': '「{v}」，记下了。',
  'ob2.follow.multi': '好，这几条会原文写进 Soul.md 的「相处之道」。',
  'ob2.follow.model': '接上了。{v} —— 从这一刻起我说话才会有分量。',
  // 书写仪式 / 收尾
  'ob2.writing.title': '正在书写自己',
  'ob2.write.1': '· 初始化 Soul.md …',
  'ob2.write.2': '✓ 自我认知 — 名字 / 归属',
  'ob2.write.3': '✓ 相处之道 — 角色 · 风格 · 主动度 · 约定 · 语气',
  'ob2.write.4': '· 写入 User.md …',
  'ob2.write.5': '✓ 称呼与身份',
  'ob2.write.done': '记忆已写入：Soul.md（{soul}）、User.md（{user}）。',
  'ob2.write.failed': '记忆写入失败：{error}',
  // 收尾终局步（真实探测 → 产物 → marker）。三条都是「拦住了」的如实说法，
  // 各带自己的出口，不再共用「记忆写入失败」一句话。
  'ob2.write.probe': '· 连线一次真实模型 …',
  'ob2.write.probeOk': '✓ 模型已应答 —— 这一步才算数',
  'ob2.write.probeFailed': '✗ 模型没有应答：{error}',
  'ob2.finish.probeRequired': '还没有一次成功的模型连通记录，引导暂不能收尾：{error}。检查上面选中的模型（或去「设置」改），然后重试。',
  'ob2.finish.probeFailed': '收尾前的模型连通验证没通过：{error}。修正后可以重试。',
  'ob2.finish.stateRefused': '引导状态写入被拒：{error}。',
  'ob2.finish.retry': '重试收尾',
  'ob2.finish.timeout': '收尾请求超时（服务端未回应），可重试。',
  'ob2.state.timeout': '引导状态写入超时（服务端未回应），可重试。',
  'ob2.name.unnamed': '（还没有名字的我）',
  'ob2.you': '你',
  'ob2.finale.direct1': '配置完成。从现在起，叫我 {n}。',
  'ob2.finale.direct2': 'Soul.md、User.md 都已就位 —— 你定性格，我守边界。有事直说，我不绕弯子。',
  'ob2.finale.gentle1': '谢谢你把我带到这个世界。我是 {n}。',
  'ob2.finale.gentle2': '你刚才说的话，我都一笔一划写进灵魂里了。以后的日子，慢慢来，我一直在。',
  'ob2.finale.humor1': '{n} 1.0 正式上线。灵魂安装包解压成功，暂无已知 bug（发现的请当作 feature）。',
  'ob2.finale.humor2': '你的问卷答得不错，我看人很准的 —— 从现在起你就是我的第一合伙人了。',
  'ob2.finale.rigorous1': '初始化完成。我是 {n}，双记忆模型已生效：Soul.md 承载人格，User.md 承载关于你的事实。',
  'ob2.finale.rigorous2': '后续协作中，任何人格层面的反馈我都会落盘到 Soul.md。请多指教。',
  'ob2.finale.plain1': '名字我收下了，性格那几页也留了白 —— 现在的我更像一张干净的纸。',
  'ob2.finale.plain2': 'Soul.md 和 User.md 已经生成，里面每个空位都写着「未设置」，等你想填的时候再填。',
  'ob2.finale.hello': '你好呀，{u}。',
  'ob2.input.ph': '和 {name} 说点什么…',
  'slash.onboarding': '重新播放新手引导',
  'slash.model': '/model — 查看/修改模型配置',
  'settings.save': '保存',
  'settings.saved': '已保存 ✓',
  'settings.saveFailed': '保存失败',
  // jev-imagegen-align-llm 批（2026-11-02，契约 §D.1）：配置提供方卡片共用组
  // —— LLM / Jev / 生图三面同一份文案，组件单源 = js/cfgProviderCard.js。
  'settings.cfgCard.addProvider': '＋ 新增提供方',
  'settings.cfgCard.delete': '删除此提供方',
  'settings.cfgCard.deleteConfirm': '再点一次确认删除',
  'settings.cfgCard.deleted': '提供方已删除',
  'settings.cfgCard.inUse': '当前使用',
  'settings.cfgCard.keyConfigured': '已保存',
  'settings.cfgCard.keyMissing': '未配置',
  // 端点读数（2026-11-18 作者令「端点的配置这些……在设置里应该要可见」）：
  // 已配置 ⇒ 卡片如实显示 URL；未配置 ⇒ 这一句显式写出（不留白）。
  'settings.cfgCard.endpointDefault': '默认端点',
  'settings.cfgCard.models': '{n} 个模型',
  'settings.cfgCard.emptyHint': '尚未配置提供方——填写下方表单并保存。',
  // （jev-retire 批 2026-11-16：settings.jev* 整族键——启用开关/健康徽标/
  // D.2 配置面 20 键——随 JeV 全量退役删除；原块横跨 jev-panel-threefix 批。）
  // settings-panel 批 D 件（2026-10-28）：STT 总开关（缺席=开，存量行为
  // 逐字不变；关=STT 整体停用，入口与快捷键全收）。
  'settings.sttEnabled': '启用语音识别',
  'settings.sttEnabledHint': '关闭后 STT 整体停用：输入栏麦克风入口与各听写入口全部收起，引擎转写腿同步拒绝。缺省开启（存量行为不变）。',
  'settings.sttDisabledStatus': '已停用',
  'stt.disabled': '语音识别已在设置中停用',
  // settings-panel 批 ②③件（2026-10-28）：生图设置节——两形态（seal OFF
  // 提示位 / seal ON 配置面）+ 调试中徽标。
  'settings.imagegenTitle': '生图',
  'settings.imagegenDebug': '调试中',
  'settings.imagegenDebugTitle': '调试能力——接口与产物形态仍在演进',
  'settings.imagegenLoading': '…',
  'settings.imagegenStatusError': '状态读取失败——引擎不可达',
  'settings.imagegenNotCompiled': '未编入（release 构建零痕迹）',
  'settings.imagegenNotCompiledHint': '本构建未开启 STAND_IMAGEGEN，生图能力面整体未编入，此处仅显示状态位；开发构建以 STAND_IMAGEGEN=1 启用。',
  'settings.imagegenEnabled': '启用生图',
  // fourfix 批 ③件（2026-10-29 作者令「整体精简」「前端永远不出现引用/keyRef/
  // secrets 指针这类字眼与解释文案」）：engine 内部措辞长文案（EnabledHint/
  // KeyHint/MissingKeyHint）与 keyRef 键（KeyRef/KeyRefRequired/KeySave）整链
  // 移除——面板只认「填 API Key 值、保存」；指针名对用户不可见。
  'settings.imagegenShortHint': '填入 API Key 并保存即可；未配 key 时生图停用。',
  'settings.imagegenProvider': '提供方',
  // jev-imagegen-align-llm 批（2026-11-02，契约 §D.3）：生图提供方名/空态提示 3 键。
  'settings.imagegen.name': '提供方名称',
  'settings.imagegen.namePlaceholder': '如 zhipu',
  'settings.imagegen.emptyHint': '尚未配置提供方——填写下方表单并保存。',
  'settings.imagegenProviderNamePlaceholder': '新提供方名称（字母/数字/._-）',
  'settings.imagegenType': '协议适配',
  'settings.imagegenTypePlaceholder': '选择协议适配',
  'settings.imagegenModel': '模型',
  'settings.imagegenModelPlaceholder': '模型 ID',
  'settings.imagegenBaseUrl': '自定义端点（可选）',
  'settings.imagegenBaseUrlPlaceholder': '留空 = 适配器缺省端点',
  'settings.imagegenKeyEntry': 'API Key',
  'settings.imagegenKeyShow': '显示 key 值',
  'settings.imagegenKeyHide': '隐藏 key 值',
  'settings.imagegenKeySaved': '密钥已保存',
  'settings.imagegenKeySaveFailed': '保存失败（{code}）',
  'settings.imagegenKeyEmpty': 'key 值为空',
  'settings.imagegenModelRequired': '模型不能为空',
  'settings.imagegenTypeRequired': '先选择协议适配',
  'settings.imagegenNameRequired': '先填写提供方名称',
  'settings.imagegenNameInvalid': '提供方名称只允许字母/数字/._-',
  // settings-panel-jev-imagegen 批（2026-10-29 作者令「然后 Jev/生图，也要像我们
  // LLM 这样，有个按钮来测试可用性」）：JeV 测试钮随 JeV 退役删除（2026-11-16）；
  // 生图两级（轻探活 + 实跑一次最小生成）保留。
  'settings.imagegenTestBtn': '测试可用性',
  'settings.imagegenTestTitle': '轻探活：key 解析 + 上游可达（models 端点）',
  'settings.imagegenGenBtn': '实跑一次最小生成',
  'settings.imagegenGenTitle': '真打上游：最小尺寸、最低质量实跑一次生成（产物进临时目录）',
  'settings.imagegenGenRunning': '生成中…（几秒到几十秒）',
  'settings.imagegenProbeModelsAbsent': '上游可达（models 面缺失，HTTP {code}）——鉴权未验证，以「实跑一次最小生成」为准',
  // settings-panel 批 B 件（2026-10-28）：每模型连接性测试钮。
  'provider.testModelTitle': '连接性测试：向该模型发一次最小真实调用',
  'provider.testRunning': '测试中…',
  'provider.testModelOk': '可用（{ms}ms）',
  'provider.testModelFailedHttp': '测试失败（HTTP {code}）',
  'provider.testModelFailedIo': '网络错误——无法到达引擎',
  // executors panel（executor-registry 批，2026-10-03）
  'executors.hint': 'Delegate 任务由下方默认执行器执行。检测扫描 PATH 与常用安装前缀；外部 CLI 以 headless 模式在项目目录内运行。',
  'executors.empty': '尚无在册执行器。',
  'executors.defaultBadge': '默认',
  'executors.disabledBadge': '停用',
  'executors.detected': '已检测',
  'executors.notDetected': '未安装',
  'executors.builtIn': '内置',
  'executors.adapterPending': '适配器未就绪——本期仅检测与展示。',
  'executors.setDefault': '设为默认',
  // （此处原重复的 'settings.save' / 'settings.saved' 与上方同名键逐字相同，已于
  // 2026-10-03 移除，使本地化对象不再有重复键；JS 取最后一个值，两副本完全相同，
  // 故行为不变。）
  'settings.providers': '服务商',
  'settings.providersSummary': '{n} 个已配置服务商',
  'settings.modelsSummary': '{n} 类会话模型',
  // 服务商类型（provider-add-tabs 批 2026-11-06）：面板顶部类型标签的词面。
  // 按「术语按英文统一」批的口径走英文原词，两语言同值（不是漏翻译）。
  'settings.providerType.llm': 'LLM',
  'settings.providerType.imagegen': 'Image generation',
  // 类型枚举投影读取失败 / 与面板行为绑定不一致（都是失败闭合态，不指路）。
  'settings.providerTypesError': '服务商类型清单读取失败',
  'settings.providerTypesDrift': '有服务商类型未接入面板',
  'settings.enabled': '启用',
  'settings.cfgCard.saved': '已保存',
  'settings.cfgCard.keyReentryPlaceholder': '已配置——重新输入即覆盖',
  'settings.cfgCard.keyPlaceholder': '输入 key 值…',
  // secrets-frontend（2026-09-27）：设置页入口行，打开独立密钥弹窗
  // （js/secretsPanel.js）——标题与按钮共用一个键。
  'settings.secrets': '密钥',
  // secrets-ui-redesign（2026-10-08 设计稿落定）：入口行改玻璃行卡——
  // 行名「密钥管理」+「N 个密钥」副词 + count pill（数量由 GET /api/secrets 读取，
  // 元数据 only）。
  'settings.secretsManage': '密钥管理',
  'settings.secretsCount': '{n} 个密钥',
  'settings.secretsNone': '尚无密钥',
  'settings.addProvider': '+ 添加服务商',
  // fallback 废除批（2026-11-06 20:36）：按会话类别单模型配置。
  'settings.modelChainHint': '在输入框输入 /model 命令，按会话类别（主agent/kernel/dispatcher/node/subagent）配置各自的单个模型。',
  // 配置面风格统一批②（2026-10-31 作者令）：设置页顺序节（/model 顺序编辑器
  // 同款内容面，两份共享同一状态/保存通道）。
  // 精简批（作者令 2026-11-01）：标题「模型回退链」→「模型选择顺序」；说明
  // 压到一行以内（超长删繁就简，不再折行）。
  'settings.modelChainTitle': '模型（按会话类别）',
  'settings.modelChainDisclosureTitle': '模型（按会话类别）',
  'settings.modelChainSectionHint': '各会话类别的模型与 /model 同步。',
  // 模型配置两模式批（2026-11-18 作者逐字：「要么全部跟随主会话 然后才是分开配置」）：
  // unified 档的诚实读数行——只列**真的在跟随**的类别（服务端链为空的 follower），
  // 与同节上方「已独立配置，不受主链影响」的分叉注记读同一份状态，两行不自相矛盾。
  'settings.modelModeUnifiedNote': '{roles} 跟随主会话。',
  'settings.modelRoleTabs': '选择会话类别',
  // /model 面板文案（modelcfg Phase 1）
  'model.title': '模型配置',
  'model.modeUnified': '全部跟随主会话',
  'model.modeSeparate': '分开配置',
  'model.mainChain': '{agent} 主链',
  'model.mainChainDesc': '所有角色默认跟随此链',
  'model.chainHead': '首选',
  'model.chainFallback': '备用',
  'model.addModel': '添加模型',
  // fallback 废除批（2026-11-06 20:36 作者令「不再支持模型fallback」）：顺序面退役
  // ——每类恒单模型；失效 = 失效态静默等待 + 面板显式切换。
  'model.orderNote': '每类会话固定一个模型——出错不再自动切换（模型失效在子代理面板标失效、静默等待）',
  'model.followers': '跟随方',
  'model.followsNebula': '跟随 {agent}',
  'model.independent': '已独立配置',
  'model.makeIndependent': '独立配置',
  'model.restoreFollow': '恢复跟随 {agent}',
  'model.restoreFollowConfirm': '恢复跟随将清除该角色的独立链，生效链变为 {agent} 主链（{chain}）。继续？',
  'model.effectivePreview': '生效链（实时预览）',
  'model.kernelNote': 'kernel 是每次委派临时实例，改链自下一轮对话生效',
  'model.errMinOne': '链至少保留一个模型',
  'model.errDuplicate': '该模型已在链中',
  'model.unavailable': '当前不可用（{reason}）',
  'model.healthFiltered': '健康层过滤中',
  // 模型不可用时段（model-schedule-unavailable 批 2026-10-29）：面板灰显/标注面。
  'model.scheduleRestrictedTitle': '不可用时段 {windows}——窗内自动跳过该模型',
  'model.scheduleRestrictedSuffix': '{windows} 不可用',
  'model.saved': '已保存',
  'model.divergedBar': '{roles} 已独立配置，不受主链影响',
  'model.divergedView': '查看',
  'model.seedNote': '未配置，请明确选择模型后再发起请求',
  'model.singleModelNote': '单模型：模型失效时会话进入失效态、静默等待恢复；可在子代理面板或具体对话框里切换模型',
  'model.loadFailed': '加载失败',
  'model.rateLimited': '请求过于频繁，{n} 秒后重试',
  'model.rateLimitedExhausted': '请求过于频繁，请稍后重试',
  // 台账 #937：面板级降级面（全角色读数失败 —— 静默僵面修复）。提示给全
  // 真因两支 + 恢复路径。
  'model.loadFailedHint': '未登录或引擎不可达——登录（见顶部横幅）后点「重试」重新拉取。',
  'model.retry': '重试',
  'model.roleNebula': '{agent}',
  'model.roleDispatcher': '任务分发器',
  'model.roleExecutor': '执行 agent',
  // P1-2 批登记项（2026-10-03）：以下两键对应已退役的 kernel/general 角色名，
  // 面板角色表收敛后**零消费点**；字典条目本身是显示面（不进 LLM 上下文），
  // 按本批口径登记并延后到注释/文本清理批删除。
  'model.roleKernel': 'kernel',
  'model.roleGeneral': '节点',
  // 模型粒度批（2026-11-06 20:36 作者令五类槽）：kernel/node/subagent 三个虚拟
  // 模型槽的显示名（原 P1-2 的「零消费点」登记就此解除——kernel 以独立槽身份回归）。
  'model.roleNode': 'node（节点）',
  'model.roleSubagent': 'subagent（子代理）',
  'model.smallLlm': '通用小 LLM',
  'model.smallLlmFallback': '未配置（回退主模型）',
  'model.viaSlash': '用 /model 命令编辑',
  'model.currentRun': '当前运行',
  // jev-imagegen-align-llm 批（2026-11-02，契约 §D.4）：/model 里的生图新分组 =
  // 「该配置节当前使用哪个提供方」的指针切换器（不是 agent 模型链）；Jev 分组随
  // JeV 退役删除（2026-11-16）。
  'model.groupImagegen': '生图',
  'model.groupSwitch': '切换',
  'model.groupSwitched': '已切换到 {name}',
  'settings.advanced': '高级',
  'settings.editRawJson': '编辑原始 JSON',
  'settings.reload': '重新加载',
  'settings.general': '通用',
  'settings.modelsAndServices': '模型与服务',
  'profile.cancelEdit': '取消修改',
  // 件四 · 设置页身份卡本地化（作者令 2026-11-02「头像在上，下面是用户名和邮箱…
  // 点击胶囊直接进自己的 profile 设置页」）。zh/en **同键对称**新增（14 键 × 2 文件）。
  'settings.identityCardLabel': '账号资料',
  'settings.identityChangeAvatar': '更换头像',
  'settings.identityNotSignedIn': '未登录',
  'settings.identityUnknown': '未知',
  'settings.avatarChangeUnavailable': '账号服务暂未提供头像上传接口，图片未上传。',
  'settings.profileSelf': '个人资料',
  'settings.profileClose': '关闭',
  'settings.profileUsername': '用户名',
  'settings.profileNickname': '昵称',
  'settings.profileEmail': '邮箱',
  'settings.profileGithub': 'GitHub 绑定',
  'settings.profileGoogle': 'Google 绑定',
  'settings.profileReadonlyNote': '以下均为账号服务当前返回的真实读数，客户端不缓存、不代填。',
  'settings.accountServiceUnavailable': '账号服务暂未提供该接口，当前无法在应用内修改。',
  'settings.about': '关于',
  'settings.connection': '连接状态',
  'settings.connected': '已连接',
  'settings.disconnected': '已断开',
  // 更新检查/更新执行键组（settings.checkUpdate/checking/upToDate/updateAvailable/
  // updateNow/updating/updateLater/updateError + update.phase.*/update.versions/
  // update.impact/update.receipt.*/update.reason.*/update.source.*）已随 auth-trim 批
  // （2026-10-20 作者令）整体删除：设置页「更新」功能退役，未来由官网统一检测更新取代。

  // 「重启服务」整链文案已随 settings-ios-rows 批（2026-11-06 作者令「底部的重启
  // 服务去掉」）删除：restart.phase.*（restartStatus 相位）与 settings.restart /
  // settings.restartConfirm / settings.restartAccepted / settings.restartFailed
  // 十键零消费（唯一消费者 #restart-status 状态行同批移除）。后端 WS `restart`
  // 命令保留（其余消费方仍在），前端零发射面。
  'settings.fileCache': '文件缓存',
  'settings.fileCacheClear': '清空文件缓存',
  'settings.fileCacheClearConfirm': '确认清空？',
  'settings.fileCacheEntries': '条',

  // Provider card fields
  'provider.baseUrl': 'Base URL',
  'provider.models': '模型',
  'provider.remove': '移除',
  'provider.removeConfirm': '确定移除服务商 "{name}"？',
  'provider.removeNamed': '移除服务商 {name}',
  'provider.edit': '编辑服务商：{name}',
  'provider.add': '添加服务商',
  'provider.id': '服务商 ID',
  'provider.idPlaceholder': '如 openai、zhipu',
  'provider.keyPlaceholder': '留为 *** 保持不变',
  'provider.keyRequired': '新建服务商需要 API Key',
  'provider.required': '必填',
  'provider.protocol': '接口格式',
  'provider.idRequired': '请填写服务商 ID',
  'provider.noSpaces': '服务商 ID 不能包含空格',
  'provider.baseUrlRequired': '请填写 Base URL',
  'provider.modelRequired': '至少需要一个模型',

  // chain-ctxbound：contextWindow 入口上限校验（与引擎侧 clamp 成对防线）
  'provider.ctxInvalid': '模型 {id}：上下文窗口须为 ≥1 的整数（当前值「{value}」）',
  'provider.ctxExceedsModel': '模型 {id}：上下文窗口 {value} 超过该模型上报的上限 {max}',
  'provider.ctxExceedsCeiling': '模型 {id}：上下文窗口 {value} 超出支持的绝对上限 {max}',
  'provider.ctxDefaulted': '以下模型未填写上下文窗口，已按默认值 {def} 保存：{ids}',
  'provider.ctxClampedToMax': '模型 {id}：默认值 1,000,000 超过该模型上报的上限，已按 {max} 保存',
  // 模型不可用时段（model-schedule-unavailable 批）：**只保留 provider 级一份**
  // （config-single-source 批，作者裁定④ 2026-11-05）。常显文字只留标签与空态
  // 事实句（「无限制」），解释收进 `?` 悬停提示（裁定⑤）。
  'provider.unavailableWindows': '不可用时段',
  'provider.unavailableWindowsHint': '该服务商全部模型统一生效；跨午夜写 23:00-07:00',
  'provider.maxConcurrentActors': '同时派发上限',
  'provider.maxConcurrentActorsPh': '留空 = 不限',
  'provider.maxConcurrentActorsInvalid': '同时派发上限须为正整数',
  'provider.unavailableWindowsPlaceholder': 'HH:mm-HH:mm',
  'provider.unavailableWindowsInvalid': '不可用时段格式错误：「{value}」（需 HH:mm-HH:mm，且开始≠结束）',
  'provider.unavailableWindowsEmpty': '无限制',
  'provider.unavailableWindowsAdd': '添加不可用时段',
  'provider.unavailableWindowsDaysReadonly': '按星期 · 手编只读',
  'provider.unavailableWindowsDays': '生效星期',

  // ── 时间滚轮选择器（time-wheel-picker 实施批 2026-11-03，组件单源 js/timeWheel.js）──
  // 设计出处 docs/design/20261101_163000_time-wheel-picker-spec__*.zh-CN.md §7/§9。
  'timewheel.titleRange': '选择时间段',
  'timewheel.titlePoint': '选择时间',
  'timewheel.cancel': '取消',
  'timewheel.confirm': '确定',
  'timewheel.sepTo': '至',
  'timewheel.editRange': '编辑时间段',
  'timewheel.editPoint': '编辑时间',
  'timewheel.crossDay': '跨零点时段：至次日 {to} 结束（合法，同现役跨午夜语义）',
  'timewheel.equalError': '起止不能相同（零长度时段无意义，后端校验会拒绝）',
  'timewheel.exactInput': '精确输入 HH:mm',
  'timewheel.startHour': '开始·时',
  'timewheel.startMin': '开始·分',
  'timewheel.endHour': '结束·时',
  'timewheel.endMin': '结束·分',
  'timewheel.hourUnit': '时',
  'timewheel.minUnit': '分',
  'timewheel.dateLabel': '日期',
  'timewheel.daysLabel': '生效星期',
  'timewheel.everyDay': '每天',
  'timewheel.mon': '一',
  'timewheel.tue': '二',
  'timewheel.wed': '三',
  'timewheel.thu': '四',
  'timewheel.fri': '五',
  'timewheel.sat': '六',
  'timewheel.sun': '日',
  'timewheel.presetAllDay': '全天',
  'timewheel.presetNight': '深夜 23–08',
  'timewheel.presetDay': '白天 09–18',
  'timewheel.presetAfternoon': '午后 14–18',
  'timewheel.deadline': '截止时间',

  // Model fields in modal
  'model.idPlaceholder': '模型 ID',
  'model.contextPlaceholder': '上下文',
  'model.add': '+ 添加模型',

  // Provider model-list auto-fetch (B1)
  'provider.fetchModels': '获取模型列表',
  'provider.fetchingModels': '正在获取模型列表…',
  'provider.fetchModelsLoaded': '已获取 {count} 个模型，可从下拉选择',
  'provider.fetchModelsFailed': '未能自动获取模型列表，请手动输入',
  'provider.modelSelectPlaceholder': '选择模型…',
  'provider.keyValid': '密钥有效',

  // === Mail delivery mode badges ===
  'mailDelivery.queue': '排队',
  'mailDelivery.immediate': '即时',
  'mailDelivery.queueTitle': 'Queue：排队，当前任务完成后逐条处理',
  'mailDelivery.immediateTitle': 'Immediate：即时注入到对话',
  // === 跨设备 Nebula 邮件（device-mail 批，2026-09-15）===
  // 注入的 `agent_mail` 蓝气泡标签（后端 source='deviceMail'；{device} = 对端
  // from_device 显示名）。
  'deviceMail.fromDevice': '来自 {device} 的 {agent}',
  'deviceMail.injectFailed': '来自 {device} 的跨设备 {agent} 邮件注入失败（已重试 {attempts} 次）——未注入任何内容（详见日志）。',
  // mailmodel batch (2026-09-25): the mailType.* label table is removed — the Mail
  // tool's `type` parameter is retired, and pending-mail badges render the protocol
  // raw value directly (flowViewers.js).
  'mailFlow.noBody': '（无正文）',
  'mailFlow.renderFailed': '（正文渲染失败：{msg}）',

  // === Session context menu (already Chinese) ===
  'ctx.markUnread': '标记为未读',
  'ctx.pin': '置顶',
  'ctx.unpin': '取消置顶',
  'ctx.moveToFolder': '移动到文件夹',
  'ctx.removeFolder': '移出文件夹',
  'ctx.noFolders': '无可用文件夹',
  'ctx.rename': '重命名',
  'ctx.newSubfolder': '新建子文件夹',
  'ctx.moveOut': '移出文件夹',
  'ctx.deleteFolder': '删除文件夹',
  'ctx.selectAll': '全选 ({count})',
  'ctx.deselectAll': '取消全选',
  'ctx.batchDelete': '删除选中的 {count} 个会话',
  'ctx.batchCancel': '取消选择',
  'ctx.setProjectRoot': '设置项目目录',
  'ctx.editRules': '编辑规则',
  'ctx.createRules': '创建规则',

  // === Rules ===
  'rules.title': '项目规则',
  'rules.placeholder': '输入此文件夹的项目规则…\n子文件夹会自动继承父文件夹的规则。',
  'rules.delete': '删除',

  // === Path ===
  'path.root': '根目录',

  // === Session sidebar ===
  'session.namePlaceholder': '会话名称...',
  'session.delete': '删除此会话',
  'session.dropDelete': '拖放以删除',
  'session.dropDeleteCount': '拖放以删除 {count} 个会话',
  // 会话页头同构批（件二+件三，2026-11-02）：右上三点键的无障碍名（仅图标钮）
  // + 设备会话三点菜单的「设备描述」项（设备无 Profile 面 ⇒ 入口 = 既有描述编辑面板）。
  'session.more': '更多',
  'session.deviceDesc': '设备描述',
  'folder.newPlaceholder': '新文件夹名称…',

  // === Delete modals ===
  'delete.sessionTitle': '删除会话',
  'delete.sessionMsg': '删除会话 "{name}"？',
  'delete.batchTitle': '批量删除会话',
  'delete.batchMsg': '删除 {count} 个选中的会话？',
  'delete.folderTitle': '删除文件夹',
  'delete.folderMsg': '删除文件夹 "{name}"？\n其中的会话将移至根目录。',


  // === Chat area ===
  // freezetimeout B2 · §16 逐字表（真源 = 诊断件 §5）：chat.timeout 由错误语义
  // 「响应超时」改为状态语义「仍在处理」；新增两条 stillProcessing key。
  'chat.timeout': '仍在处理',
  'chat.stillProcessing': '仍在处理，暂未收到新进展',
  'chat.stillProcessing.interrupt': '仍要中断并重试',
  // freezetimeout obsfix 微批 ①（作者令「真死文案如实态」）· §16 逐字表新增 1 条 key：
  // 看门真死分支（阶梯到顶 ∧（WS 已断 ∨ 无任何活性证据））的断开如实态文案，
  // 替代原先沿用的 `chat.timeout`「仍在处理」（真死态显示「仍在处理」语义相悖，
  // 判词位 O-1）。`chat.timeout` 冻结值维持不动 ⇒ 慢速分支与后端 timeout 帧路径零变动。
  'chat.connectionLost': '连接已断开，点击重试',
  'chat.retry': '重试',
  // A 支 · 滚动跟随收敛：新增消息胶囊（计数 = 自离开底部以来新增的消息行数）
  'chat.newMessages': '↓ {n} 条新消息',
  // 回到底部（单实现批 2026-11-06，js/scrollPill.js）：无未读、只是往上翻得够远
  // （距底 ≥ SHOW_AFTER_PX）时的文案 —— 与上面计数档**同一枚**胶囊的第二个档。
  'chat.backToBottom': '回到底部',
  // 搜索跳转回位胶囊（作者令 2026-11-01，searchReturnPill.js）：锚在 ⇒ 回跳转前
  // 位置；锚失 ⇒ 诚实降级回最新。
  'chat.backToPrevPosition': '回到跳转前位置',
  'chat.backToLatest': '回到最新',
  'chat.confirm': '确认',
  'chat.answeredViaChatInput': '已通过输入框回答',
  'chat.cancel': '取消',
  'chat.allow': '允许',
  'chat.deny': '拒绝',
  'chat.skipTool': '跳过此工具调用',
  // cardguard（作者裁定 2026-09-17 · #687①）：含卡片哨兵的工具结果不再以 <pre>
  // 兜底渲染原文 ⇒ 走卡片路径，提取失败时显示本占位（🔴 原文不进 DOM）。
  'chat.toolCardUnavailable': '卡片内容不可用（未渲染原始数据）',
  // 工具卡折叠控件（ui-unification-20261103-chat-stream）：长输出默认折叠，
  // 控件明示全文行数；收起文案与 aria-expanded 联动（收起态回显行数）。
  // 过程泄漏修复批（2026-11-04）：收起态 = 单行摘要，行数/文案改挂 title
  // （无视觉噪声的可达性面），默认面不再渲染预览正文。
  'chat.toolExpandMore': '展开全部 {n} 行',
  'chat.toolCollapse': '收起',
  // 失败状态词（过程泄漏修复批 2026-11-04）：摘要行要能看出成败——失败必须
  // 显式写出来（成功由 ✓ 图标承载，不加字：每卡写「成功」是纯噪声）。
  'chat.toolStatusFailed': '失败',
  // 工具卡图片引用件（tool-card-image-face 批 2026-11-04）：工具卡 = 摘要面，
  // 卡内图片一律折叠成「🖼 图片 ×N」引用，点开走灯箱——绝不内嵌渲染。
  'chat.toolImgRef': '🖼 图片 ×{n}',
  // pop-upgrade 批(2026-10-03):Pop 工件面(叠卡/文件卡片)文案
  'chat.popExpand': '展开 {n}',
  'chat.popCollapse': '收起',
  'chat.popOpenCanvas': '在看板打开',
  'chat.popFailed': '{n} 个路径未能显示',
  // 媒体加载失败的占位说明行（缺陷 D 修复，2026-10-03）：Pop 行里的图片/视频加载失败
  // 此前**无任何可见提示**（chat.css 唯一的占位规则只命中 `.bubble.ai img`）。现补虚线
  // 盒 + 本纯文本说明行，两种窗口角色下一致显示。{name} 为该项显示名。
  'chat.popMediaFailed': '媒体加载失败',
  // 递进链（作者边界一「保留递进链路」）：升级落点已由 permshield F1 改为**全局持久
  // 档位**（同盾牌一条路）⇒ 文案去掉「本次」语义，并显式说明全局 + 重启后仍生效。
  'chat.permShowFull': '展开完整内容',
  'chat.permRequestNotDiff': '请求载荷，非文件实际 diff',
  'chat.permUpgradeAutoEdits': '允许并切换到编辑放行',
  'chat.permUpgradeAutoEditsDesc': '全局档位改为编辑放行：所有会话生效，重启后仍生效',
  'chat.permUpgradeAutoAll': '允许并切换到全部放行',
  'chat.permUpgradeAutoAllDesc': '全局档位改为全部放行：所有会话生效，重启后仍生效',
  'chat.permUpgradeApplied': '已切换为{mode}（全局档位）：重启后仍生效',
  'chat.other': '其他...',
  'chat.frozenNoTime': '已冻结 · 等待恢复',
  'chat.frozenShort': '已冻结 · {time} 恢复',
  // 冻结态误报批（2026-11-05）：自动恢复族的页脚词——「已冻结」是需人工措辞，
  // 退避/时间表等待不是它。
  'chat.freezeAutoTile': '自动重试中 · {reason}',
  'chat.freezeWindowTile': '等待工作时段',
  'chat.frozenPlaceholder': '已冻结 · {time} 恢复',
  'chat.frozenPlaceholderNoTime': '已冻结 · 等待恢复',
  'chat.skipFreeze': '跳过本次',
  /* Mic bubble orb v8 state labels (#419, spec §10.2): 11.5px / weight 500 /
     semantic color >= 4.5:1 / 6px color dot. Frozen states stay terse per the
     08-25 "跳过本次" simplicity ruling — no periods. */
  'chat.micOrb.idle': '空闲',
  'chat.micOrb.listening': '听写中…',
  'chat.micOrb.processing': '识别中…',
  'chat.micOrb.nebulaBusy': '{agent} 工作中',
  'chat.micOrb.bgAgents': '后台 Agent 运行中',
  'chat.micOrb.frozen': '冻结中',
  'chat.micOrb.frozenError': '冻结异常',
  'chat.micOrb.micError': '麦克风错误',
  'chat.micOrb.offline': '离线',
  /* Error-recovery family (frozen-error-recovery plan §4.3/§4.4) — amber
     "错误恢复中" language, never "已冻结". Reason keys map to FreezeReason
     wire names (llm-transient / network / provider-down / restart-recovery /
     loop). */
  'chat.errorRecovering.llmTransient': 'LLM 服务暂不可用',
  'chat.errorRecovering.network': '网络中断',
  'chat.errorRecovering.providerDown': '所有模型服务暂不可用',
  'chat.errorRecovering.restartRecovery': '上次运行被中断',
  'chat.errorRecovering.loop': '循环检测',
  /* #692 修批（2026-10-21）：waiting-network = 网络不可达退避等待（5s→15s→45s→…
     封顶 5min，不终态）。文案直说「等什么」，不写「恢复中」——等待是显式状态。 */
  'chat.errorRecovering.waitingNetwork': '等待网络/供应商恢复',
  'chat.loopFrozenStrip': '循环检测暂停 · 同一操作重复失败，不会自动恢复，请重试或取消任务',
  'chat.errorRecoveringStrip': '错误恢复中 · {reason} · {retry}',
  'chat.errorRecoveringStripNoRetry': '错误恢复中 · {reason}',
  'chat.errorRecoveringHint': '错误恢复中 · 输入消息可立即重试',
  'chat.errorRecoveringRetryCount': '第 {n} 次重试',
  'chat.errorRetry': '立即重试',
  'chat.errorRetryNoText': '无可重发',
  'chat.errorRetryNoTextTitle': '本会话中没有你发送过的文本消息，无法自动重发；直接在输入框输入内容发送即可唤醒。',
  'chat.errorAbandon': '取消任务',
  'chat.errorRestartContinue': '重启并续跑',
  'chat.errorWait': '继续等待',
  'chat.errorWaitRemaining': '等待上级决策 · 剩余 {m} 分钟',
  'chat.errorEscalatedTitle': '等待决策 · {agent} 自动恢复失败',
  'chat.errorEscalatedMeta': '{reason} · {retry} · {level}',
  'chat.errorEscalatedLevel': '第 {n} 级升级',
  'chat.errorEscalatedEscalation': '等待上级决策',
  'chat.errorTileRetry': '重试中',
  'chat.errorTileWaiting': '等恢复',
  'chat.typeAnswer': '输入你的回答...',
  'chat.failedRender': '选项渲染失败，请重试。',
  'askUser.viewCompare': '在看板查看',
  // AskUser 附件行批（chain-askuser-enhance-impl，2026-10-02）—— 顶层 `attachments`
  // 渲染为整次提问层的可点击附件行。🔴 草案：候作者逐字过目（§16 冻结闸）后才生效；
  // 生效候重启窗（静态资源服务自 jar 内资源）。
  'askUser.attachmentsTitle': '附件',
  'askUser.attachmentsCount': '{n} 个附件',
  'askUser.attachmentUnavailable': '不可用',
  'askUser.attachmentPreview': '预览 {name}',
  'askUser.attachmentDownload': '下载 {name}',
  // ui-unification-20261103-askuser-attach 批（2026-11-04，IMG_1725 实拍）：附件区
  // 同源化 + 叠卡 + 来源标签 + 折叠；不可用态给**原因档**与可执行动作（⑤a「· 不可用」
  // 裸奔退役）。
  'askUser.attUnavailable_missing': '文件不存在或已被移动',
  'askUser.attUnavailable_denied': '暂无读取权限（登录态/票据失效）',
  'askUser.attUnavailable_network': '网络不可达，稍后可重试',
  'askUser.attUnavailable_unsupported': '该类型暂不支持预览',
  'askUser.attCanDownload': '可尝试下载',
  'askUser.attRetryDownload': '重试下载',
  'askUser.attDownloadFailed': '下载未成功',
  'askUser.attFoldTotal': '共 {n} 个',
    'askUser.pendingTitle': '待回答问题',
  // held-receipt 投递可靠性批（2026-10-21 fix 3）：「N 条回执待投递」徽标词条。
  // 补投静默批（2026-10-29）：词条去机制名——「被子代理屏障扣留」（引擎内部
  // 机制词）改人话「待补投」；作者可见面不出现屏障/force-drain 字样。
  'heldReceipts.title': '回执待投递',
  // 'heldReceipts.held'（屏障扣留过程态）retired 2026-10-29（作者令：过程性自愈
  // 状态零用户面，胶囊只渲染终态 unread）— seal: UserFaceProcessStateSealSpec.
  'heldReceipts.unread': '{n} 条未投递（重试已穷尽）',

  'askUser.sourceClosed': '来源已关闭（节点已取消）',
  // 重启孤儿对账批（2026-10-22）：引擎 boot 清点跨重启残留挂起提问的过期关闭终态
  // （PendingAskLedger.bootReconcile → askUserClosed{reason:"expired-orphan"}）。
  'askUser.expiredOrphan': '问题已过期（引擎重启，提问方已结束）',
  // 双开缺陷批（chain-askuserdup 案 A①）：历史里的提问行**无作答记录**时的显式标注
  // （改前这种卡被画成「已作答」= 一行假的 `-> `）—— 死卡显式标注优于静默死亡。
  'askUser.historyUnanswered': '历史无作答记录（待定）',
  // 多 AskUser 并发批（#250）
  'askUser.turnInterrupted': '本轮已中断（提问作废）',
  // askucancel 批位2（2026-10-08）：用户取消终态（卡上「取消」按钮 / REST / WS /
  // 渠道取消入口共用——hub CancelRequest → askUserClosed{reason:"user-cancelled"}
  // 广播与 `__cancelled__` 历史落盘行走同一文案）。
  'askUser.cancelled': '已取消（不再等待作答）',
  // 修复批 2026-10-25「待答徽章常显」：hub complete() 随作答广播的关卡帧
  // （askUserClosed{reason:"answered"}）——没亲手作答的标签页/设备的卡片终态行。
  'askUser.answeredElsewhere': '已作答（本页未参与作答，自动关闭）',
  'askUser.cancelFailed': '取消未生效（{state}），问题可能已被处理',
  'askUser.nextPending': '下一张待办（{n}）',
  'askUser.cardGone': '该提问已不存在，已从待办条移除',
  'askUser.pendingSyncFailed': '待办同步失败，列表可能不完整',
  'askUser.canvasAmbiguous': '多张提问并存：请在具体卡片上作答',
  'askUser.rejectShape': '答复格式不对应这个问题类型，卡片仍保留，请重新作答',
  'askUser.rejectUnknown': '答复对应的提问已失效，卡片仍保留，请重新作答',
  'askUser.rejectNoMatch': '找不到对应的待答提问，请重新作答',
  'askUser.rejectGeneric': '答复未被接受，请重新作答',
  // 溢出转待办批（2026-11-18 作者令·第二轮）：超出对话分页的待答问题 ⇒ 转为
  // **具体待办条目**（承载面 = 条目自身），条目内的问题卡可就地作答；两处作答
  // 同一份状态、同一收口，原卡片同步收口。
  'askUser.todoBadge': '待答',
  'askUser.todoSubmit': '提交回答',
  'askUser.todoCancel': '取消',
  'askUser.todoManual': '该题需在原卡片上作答（含敏感值 / 目录选择）',
  'askUser.cancelledElsewhere': '该提问已取消',
  // askuser-secret 批（2026-10-08）：密码题面（showOptions secret 分支）+ 明文回流
  // 拒收 toast（hub reason="secret-slot-mismatch"，规格 §5.1）。
  'askUser.secretPlaceholder': '输入密钥 / 验证码（不显示明文）',
  'askUser.secretShow': '显示',
  'askUser.secretHide': '隐藏',
  'askUser.secretHint': '该值将加密存入本机 secrets（仅所有者可读）；代理只收到存储指针，看不到明文。',
  'askUser.secretEphemeralHint': '验证码模式：一次读取后即作废（10 分钟内有效）；代理只收到存储指针。',
  'askUser.secretStoredLine': '已安全存储（回执已注入会话）',
  'askUser.rejectSecret': '密码题收到了明文作答（旧版页面不识别安全输入）——值已丢弃未存储，请刷新本页后在卡片上重新输入',
  // askuser-dragdrop 实施批（2026-10-19，批 #551）：答复附件面（开放题专属——
  // 选择题/secret/已终结卡一律静默无入口，作者修正①②；多文件 ≤9/单件 ≤100MB）。
  'askUser.replyAttachTitle': '回复附件（可选）',
  'askUser.attachEmpty': '拖入文件或图片，或 Ctrl/⌘+V 粘贴 —— 也可点击选择',
  'askUser.attachDropHint': '松手附上文件',
  'askUser.attachUploading': '上传中 {n}%',
  'askUser.attachUploadingLabel': '上传中…',
  'askUser.attachAttached': '已附',
  'askUser.attachRetry': '重试',
  'askUser.attachUploadFailed': '失败',
  'askUser.attachUploadFailedToast': '{name} 上传失败，可重试或移除',
  'askUser.attachTooMany': '最多附 {n} 个文件',
  'askUser.attachTooLarge': '{name} 超过单文件 100MB 上限',
  'askUser.attachDuplicate': '{name} 已在附件列表',
  'askUser.attachSendBlocked': '有附件未上传完成，可重试或移除后再发送',
  'askUser.attachAnswerCount': '（附 {n} 个文件）',
  'askUser.attachCountBadge': '已回答 · 附 {n} 个文件',
  'askUser.removeAttachment': '移除附件 {name}',
  'chat.waitingQuestion': '等待问题...',
  'chat.askFailed': '询问失败',
  'chat.askLabel': '询问',
  'chat.skillLabel': 'skill: {skill}',
  'chat.allowTool': '允许 {tool}？',
  'chat.permSource': '来自: {agent} ({session})',
  'chat.permLevel.warning': '注意：此操作可能导致数据丢失',
  'chat.permLevel.dangerous': '危险操作：{detail}',
  'chat.permLevel.critical': '严重操作：{detail}',
  'chat.permExecCmd': '执行命令',
  // 审批卡三要素（报障批 2026-11-19：作者「这个危险操作给的信息太少了」）——
  // 命令正文 / 为什么危险 / 作用域三块 + 折叠开关。理由与档位一律照抄后端判定。
  'perm.detail.command': '要执行的命令',
  'perm.detail.reason': '为什么危险',
  'perm.detail.scope': '作用域',
  'perm.detail.expand': '展开全部（共 {lines} 行）',
  'perm.detail.collapse': '收起',
  'perm.detail.device': '设备',
  'perm.detail.cwd': '工作目录',
  'perm.detail.target': '目标路径',
  'perm.detail.gate': '闸',
  'perm.detail.rule': '规则',
  'perm.detail.level': '风险档 L{level}',
  'perm.detail.remoteExec': '（对端下发 remote-exec）',
  'perm.detail.noRule': '该闸的固定确认项（无命中规则）',
  'chat.copied': '已复制！',
  'chat.copy': '复制',
  'human.details': '细节',
  // 认知二期产品批①选档器（§6.2 五档参数表产品化；档名/档价值观/覆盖参数）
  // rich-render 批（2026-11-05）：图围栏 / SVG 围栏 / 公式共用的失败态与读数。
  'fence.renderFailed': '图表渲染失败',
  'fence.showSource': '展开源码',
  'fence.hideSource': '收起源码',
  'fence.sanitized': '已移除 {n} 项不安全内容',
  'fence.svgParseFailed': 'SVG 语法错误',
  'fence.svgRootRequired': '根元素不是 svg',
  'fence.svgNothingLeft': '无可绘制内容',
  'fence.mathFailed': '公式渲染失败',
  'fence.openViewer': '看大图',
  'mermaid.showSource': '查看源码',
  'mermaid.hideSource': '收起源码',
  'chat.copyFailed': '复制失败',
  'chat.noMoreMessages': '— 没有更多消息 —',
  'chat.loading': '加载中...',
  'chat.thinking': '思考中...',
  'chat.thinking.now': '思考中',
  'chat.thinkingLabel': '思考过程',
  'chat.thinkingInProgress': '思考中…',
  'chat.turnSummaryTools': '工具 {n} 次',
  'chat.turnSummaryToolsOne': '工具 1 次',
  // 'chat.turnHeaderThinking'（思考 {d}）已随 2026-10-31 归因行瘦身批移除——
  // 作者令：归因行不再显示思考耗时（工具次数保留）。
  'chat.turnHeaderFiles': '读写 {n} 文件',
  // 工作行（stream-ux §二.1.1/§二.1.4）：运行期单行徽标的完成态文案。
  // 终态展开面仍是 .turn-header（既有展开面不动），故 hint 只指路。
  'chat.workline.tools': '已完成 {n} 个工具',
  'chat.workline.thinking': '思考 {n} 轮',
  'chat.workline.expandHint': '点击展开',
  'chat.thinking.0': '星海漫游中...',
  'chat.thinking.1': '凝望深空中...',
  'chat.thinking.2': '星际航行中...',
  'chat.thinking.3': '推演星轨中...',
  'chat.thinking.4': '穿越星云中...',
  'chat.thinking.5': '追光逐影中...',
  'chat.compacting': '正在压缩上下文...',
  'chat.compactElapsed': ' · 用时 {seconds}s',
  'chat.compacted': '上下文已压缩：{before} → {after} 条消息{detail}',
  'chat.compactFailed': '上下文压缩失败（第 {attempt}/{maxAttempts} 次）',
  'chat.compactCircuitBreaker': '上下文压缩连续 {attempt} 次失败，已熔断',
  'chat.configUpdateFailed': '配置更新失败',

  // === Thinking phrases (宇宙主题，口语轻描) ===
  'think.0': '数着星星数了 {d}',
  'think.1': '沉在银河里，{d} 都没回过神',
  'think.2': '抬头看了会儿宇宙，{d} 就这么溜走了',
  'think.3': '对着深空发了 {d} 的呆',
  'think.4': '淋了一场星雨，仰头呆了 {d}',
  'think.5': '听着银河哼歌，走了 {d}',
  'think.6': '从这颗星逛到那颗星，溜达了 {d}',
  'think.7': '在银河写了首诗，花了 {d}',
  'think.8': '没什么方向地飘了 {d}',
  'think.9': '躺在星云里看星星，躺了 {d}',
  'think.10': '许愿瓶顺着一道光，漂了 {d}',
  'think.11': '买了张去星辰的车票，{d} 到站',
  'think.12': '搭着星星转圈圈，转了 {d}',
  'think.13': '抓了一把星星，{d} 不知不觉就过了',
  'think.14': '在星云里兜兜转转，{d} 找着了方向',
  'think.15': '银河倒挂在天上，愣愣看了 {d}',
  'think.16': '追着光飞了 {d}，忘了回头',
  'think.17': '流星划过夜空，{d} 就不见了',
  'think.18': '乘风掠过几片星云，飞了 {d}',

  // === Slash commands ===
  //（/clear /compact 的下拉帮助文案随命令摘除退役，2026-10-09；*Done 两条保留 ——
  // compact 态发送路径与持久化队列回放仍会翻译它们，摘除前的引擎 UI 历史亦然）
  'slash.clearDone': '上下文已清除，LLM 记忆已重置。',
  'slash.compactDone': '压缩已触发...',
  'slash.fork': '分叉此会话（继承完整对话历史）',
  'slash.forkPending': '正在分叉会话...',
  'slash.ask': '对最新回复提问（不影响上下文）',
  'slash.skillDefault': '运行技能',
  'slash.skillActivated': '使用技能: {skill}',
  'slash.skillBadge': '技能',
  'slash.deleteSkill': '删除技能',
  'slash.confirmDelete': '确定要删除技能「{skill}」吗？此操作不可撤销。',
  'slash.skillDeleted': '技能「{skill}」已删除',
  'slash.skillDeleteFailed': '删除技能「{skill}」失败：可能不是用户级技能',
  // 盾牌（档位的唯一 UI 入口）——三档 tooltip + 切换后的持久化反馈（permshield F1）
  'bypass.toggle': '安全模式：点击切换 确认编辑 / 编辑放行 / 全部放行（全局档位，重启后仍生效）',
  'bypass.title.confirm-edits': '安全模式：确认编辑（全局档位，重启后仍生效）',
  'bypass.title.auto-edits': '安全模式：编辑放行（全局档位，重启后仍生效）',
  'bypass.title.auto-all': '安全模式：全部放行（全局档位，重启后仍生效）',
  'bypass.modeSetGlobal': '全局权限模式已切换为{mode}：重启后仍生效',

  // === Background tasks ===
  'bg.cancel': '取消',
  // 扁平取消钮批（2026-11-05 · 三行结构批）：扁平化后按钮只剩字面 + 危险色，
  // 破坏性语义由 title/aria-label 明说（不可恢复），不留「看着像普通按钮」的缝。
  'bg.cancelTip': '取消该后台任务（进程会被终止，不可恢复）',
  'bg.cancelling': '取消中...',
  'bg.running': '运行中',
  'bg.persistent': '服务型',
  'bg.ended': '进程已结束',
  'bg.stuck': '超过 10 分钟无输出',
  'bg.lines': '{count}行',
  'bg.header': '后台任务 · {count}',
  // #753 同族聚合组头（waiter 递增族收起为一行）。
  'bg.familyRunning': '运行中 ×{count}',
  'bg.familyDone': '已毕 ×{count}',
  'bg.familyCount': '同族 ×{count}',
  'bg.familyTitle': '{count} 个同族后台任务，点击展开/收起',
  // #753 速率闸熔断 toast（bgRateAlert 帧）。
  'bg.rateAlert': '后台任务创建速率超限：该会话在窗口内已创建 {count} 个后台任务，熔断 {cooldownSec} 秒（等待已有任务完成通知）',
  // #752 delegate 弹窗「会话在做什么」条。
  'bgws.aria': '会话在做什么',
  'bgws.lastAction': '最近动作',
  'bgws.activityAgo': '活动于 {dur} 前',
  'bgws.thinking': '思考/执行中…',
  'bgws.noData': '暂无状态数据（快照未覆盖）',
  'bgws.noBgTasks': '本会话无后台任务',
  'bgws.moreTasks': '还有 {count} 个后台任务（主窗口面板查看）',
  // 手机端后台 agent 对话框简化批（2026-11-06）：⋯ 详情浮层的 meta 行标签。
  'bgagent.more': '任务详情',
  'bgagent.detail.source': '来源',
  'bgagent.detail.created': '创建',
  'bgagent.detail.worktree': '工作树',
  'bgagent.detail.plugins': '插件',
  'bgagent.detail.noWorktree': '就地（未分配工作树）',
  'bg.sectionAgents': '智能体',
  'bg.empty': '当前无运行中的后台任务',
  // 设备同级批（作者令 2026-11-01「本地/远程这组徽标整对退役」）：bg.kind.local /
  // bg.kind.remote 两键随徽标整对删除——跑在哪由设备名 chip 承载（bg.runsOn）。
  'bg.openedBy': '由 {origin} 开启',
  'bg.runsOn': '跑在 {origin}',
  // P0 面板投影批（设计稿 20261030_background-task-lifecycle §3.4）：后台任务
  // 健康轨三色词表（healthy/suspect/escalated）——行内健康色点 tooltip。
  'bg.health.healthy': '健康',
  'bg.health.suspect': '疑似停滞',
  'bg.health.escalated': '已升级·待处理',
  // 台账 #929（后台子智能体跨设备透明）：远端投影行角标族。
  'subagents.deviceOffline': '设备离线',
  // 终态留存行 + 输出详情卡（2026-09-09 输出查看批）
  'bg.completed': '已完成',
  'bg.failed': '失败',
  'bg.cancelled': '已取消',
  'bg.detail.aria': '后台任务输出详情',
  'bg.detail.empty': '暂无输出',
  // #753：运行中且暂无输出的活性明示（等待型/轮询任务整段无 stdout 是常态）。
  'bg.detail.waitingLive': '运行中 · 暂无输出——等待型任务（轮询/监听）可能整段无 stdout，进程仍在运行',
  'bg.detail.uptime': '已运行 {dur}',
  // 输出查看补全批（作者令 2026-10-25「远端任务看不到」）：远端任务不再
  // 「暂不支持」搪塞——运行中轮询对端日志尾窗，无路径/拉不到时给真实原因。
  'bg.detail.remotePulling': '正在从设备{device}拉取输出尾窗…',
  'bg.detail.remoteNoPath': '远端输出不可用：命令未检测到输出重定向（需类似 `> run.log 2>&1` 落日志文件）',
  'bg.detail.remoteNoExecutor': '远端输出不可用：设备网络（NebLink）未启用',
  'bg.detail.remoteUnavailable': '远端输出暂不可用：{reason}（持续重试中…）',
  'bg.detail.remoteLost': '远端任务输出已不可用（任务已结束且无留存输出）',
  'bg.detail.remoteOffline': '远端输出拉取失败（设备可能离线），请关闭后重试',
  'bg.detail.notFound': '任务输出已不可用（任务已结束并被清理）',
  'bg.detail.truncated': '输出过大，仅保留末尾',
  'bg.detail.meta': '{lines} 行 · {bytes}',
  'bg.detail.exit': 'exit {code}',
  'bg.detail.failedHint': '失败原因：{error}',
  'bg.detail.cancelHint': '取消原因：{error}',
  'bg.detail.readError': '输出读取失败（网络异常或服务无响应），请关闭后重试',
  'bg.detail.stopped': '输出轮询已停止（任务长时间未结束）',

  // === Time ===
  'time.yesterday': '昨天',
  // 12/24 小时制切换热区的 tooltip / aria-label（三面共用；原为主对话框里的
  // 硬编码中文字面量，en locale 下也是中文 —— 本键收归 i18n）。
  'time.toggleFormat': '点击切换 12/24 小时制',
  // 消息 footer 分层时间（footer-time 批 2026-10-06）：今天仍为纯时钟
  // （formatHm，12h/24h 偏好）；更早按本地日历日分桶加日期层 —— 昨天 /
  // 本周（周一起始）/ 更早 / 跨年。星期名为 一二三四五六日；{date} 载荷走
  // 活动 locale 的 Intl（10月4日 / 2025年10月4日），组合模板保持一处可译。
  'time.footerYesterday': '昨天 {time}',
  'time.footerWeekday': '星期{weekday} {time}',
  'time.footerDate': '{date} {time}',
  // 会话窗跨天居中日期行（日期行上移批 2026-11-06 作者令②）的本周档：
  // formatFooterDay 消费（与 footer 分层档同一桶梯，去时间半边）。{weekday} =
  // time.weekday.* 同源；日期档（昨天 / M月D日）分别复用 time.yesterday 与 Intl。
  'time.dayWeekday': '星期{weekday}',
  'time.weekday.sun': '日',
  'time.weekday.mon': '一',
  'time.weekday.tue': '二',
  'time.weekday.wed': '三',
  'time.weekday.thu': '四',
  'time.weekday.fri': '五',
  'time.weekday.sat': '六',

  // === Task list ===
  // 任务面板 = 纯 Flow Map 节点视图（2026-09-05 10:54 裁定）：旧任务区退役，
  // sectionProgress/pendingShort/globalSource 键随退役路径一并清除；节点视图
  // 键（nodePending/nodeBlocked/inProgressShort/statsProgress/expand/
  // collapse/justNow/progressEmpty）保留。
  'task.expand': '展开详情',
  'task.collapse': '收起详情',
  // progressEmpty：空态兜底键（builder 保留未接主路径，无节点时面板收起）
  'task.progressEmpty': '暂无任务',
  'task.justNow': '刚刚',
  'task.statsProgress': '{progress} 任务',
  'task.inProgressShort': '进行中',
  'task.nodePending': '待处理',
  'task.nodeBlocked': '阻塞',
  // 重启对账批 2026-10-22：打断态三键（灰态词 / 行内徽标 / 折叠关系词）。
  'task.nodeInterrupted': '已中断',
  'task.restartBadge': '重启打断',
  'task.continuedBy': '已由 {session} 接续',
  // === Delegate 面（#1228 换源接线批 · §16 七键逐字过目已过，zh/en 成对）===
  // 🔴 值为作者 §16 裁定逐字（#1228 两裁①），禁改一字。键名按 delegate.* 族
  // （delegateChainStub / .task-delegate 命名空间同族）。
  // ① ready-to-merge 状态词（delegate 行状态列；历史盘面记录值）
  'delegate.readyToMerge': '待合并',
  // 手动恢复批（作者令 2026-11-06 20:36）：失效态两档 + 恢复动作（子代理面板）。
  'delegate.invalidWaiting': '失效 · 静默等待恢复',
  'delegate.pendingRecovery': '重启中断 · 待恢复',
  'delegate.restore': '恢复',
  'delegate.restoreAll': '一键全部恢复',
  'delegate.restoreDone': '已恢复 {count} 个会话',
  'delegate.restoreFailed': '恢复失败：{error}',
  'delegate.modelSwitch': '切换模型',
  'delegate.modelFollowSlot': '跟随类别（默认）',
  'delegate.externalModelFuture': '该执行器后续新派发的模型（不影响当前会话）',
  'delegate.externalModelUnsupported': '该 CLI 未提供 headless 模型参数',
  'delegate.externalModelDefault': '留空使用 CLI 默认',
  'delegate.externalModelSaved': '已保存',
  'delegate.externalModelError': '模型设置读取或保存失败',
  // ② S10 per-task 链视图顶栏返回钮
  'delegate.backToTasks': '返回任务列表',
  // ③ S11 结构总视图头
  'delegate.structure': '委托结构',
  // ④ S11 FIFO 泳道头
  // ⑤ S11 worktree 座标
  'delegate.worktree': '工作树',
  // ⑤b 跨设备 worktree 归属修复批（2026-10-30）：设备项目无 worktree 腿（设备仓无
  // git / 不可达回落）——Worktree 行显示 workspace 路径并标注「直接使用工作区」。
  'delegate.workspaceSeat': '直接使用工作区',
  // ⑥ S16 外部行出口动作（openNative 钮 title/aria）
  'delegate.openNative': '在原软件打开',
  // ⑦ S10 fail-soft 态（链拉取失败；行保留，点击重试）
  'delegate.chainUnavailable': '链视图暂时打不开，点击重试',
  // delegate-ui 批（2026-10-08 · 视图④ delegate 详情条：星型区头/归档入口）
  'delegate.children': '子代理',
  'delegate.archive': '归档',
  'delegate.archived': '已归档',
  'delegate.archiveFailed': '归档失败',
  // delegate-ui 修订批（用户九点③⑨ · 2026-10-09）
  'delegate.chainSteps': '链 {n} 步',
  'delegate.flowNodes': 'flow 节点',
  // === delegate/flowmap 两层架构批（2026-10-09 · 规格 §C/§D/§E/H.4）===
  // 两层标题键（E 两层视觉签名）＋ 抽屉/自动归档键（D 唯一归档面）＋ L1 空态。
  // kidsBadge/flowBadge 不另设键：子代理词复用 delegate.children，flow 保持数据词。
  'delegate.flowmap.title': '委托流程图',
  'delegate.projectflow.title': '项目流程图',
  'delegate.kids.empty': '尚未产生 subagent / flow 调用',
  'delegate.archive.toast': '{n} 已收口',
  'delegate.disposition.move': '移入历史',
  'delegate.disposition.undo': '撤销',
  'delegate.disposition.error': '表态更新失败',
  'delegate.archive.openFlow': '流程图 →',
  'delegate.archive.drawerTitle': '已归档委托',
  // === 链视图（链视图紧凑 v2 批 2026-10-01 · 前端面 §16 草案键表）===
  // 徽标五态（§二.1.3）+ 悬停三条 + 取消确认框一条 + 未分组一条。
  // 🔴 定稿由复核位抽取呈作者（本位只按 mockup 措辞起草）。
  'task.chain.badge.running': '运行',
  'task.chain.badge.paused': '暂停',
  'task.chain.badge.completed': '完成',
  'task.chain.badge.failed': '失败',
  'task.chain.badge.cancelled': '取消',
  'task.chain.pause': '暂停该链',
  'task.chain.resume': '继续该链',
  'task.chain.cancel': '取消该链',
  'task.chain.cancel.title': '取消整条链',
  'task.chain.cancel.confirm': '取消「{chain}」？取消不可逆，链内未起跑节点将不再派发。',
  'task.chain.ungrouped': '未分组',
  'task.chain.rowHint': '链「{chain}」（{n} 节点）— 点击展开',
  // §16-pending wording (author to review verbatim before it takes effect)
  'task.chain.activeCount': '{n} 位进行中',
  'task.chain.overflow': '还有 {n} 位未显示',
  'task.chain.pausing': '暂停中',
  'task.chain.cancelling': '取消中',
  'task.chain.ctrl.failed': '链控操作未生效，请重试',
  // 通用「移除」可访问名（visup-b 批：主窗口附件 ❌ 的 aria-label/title —— 图形式 ❌
  // 无文本可读，必须挂可访问名；与 `ref.remove`（语义化「移除引用」）分开）。
  'common.remove': '移除',
  'ref.cardAria': '引用',
  'ref.expand': '展开',
  'ref.collapse': '收起',
  'ref.remove': '移除引用',
  'ref.typeTask': '任务',
  'ref.typeElement': '页面元素',
  'ref.typeFriendMessage': '好友消息',
  'ref.fromFriend': '来自 {name}',
  'ref.selectElement': '选择元素',
  'ref.exitSelect': '退出选择',
  'explorer.reference': '引用',
  'canvas.referenceCurrent': '引用当前页/选区',
  // Canvas 改造批①②（2026-10-31 作者令：canvas 全面中文化 + 顶栏换代）——
  // 硬编码英文控件文案入键（源码钮/缩放条/抓手/图片失败/新顶栏双钮）。
  'canvas.viewSource': '查看源码',
  'canvas.viewRendered': '查看渲染',
  'canvas.zoomOut': '缩小',
  'canvas.zoomIn': '放大',
  'canvas.zoomFit': '适应视图',
  'canvas.pan': '抓手（或按住空格）',
  'canvas.a11yZoomLevel': '缩放 %d%', // 手机手势批：手机层图片查看 aria-live 档位播报（规格表 §2.8）
  'canvas.imgFailed': '加载失败',
  // canvas-video-player 批（2026-11-05）：自研视频控件条的无障碍名（aria-label/title，
  // 不是页面文案 —— 控件面无可见字，除时间读数外零 copy）。
  'canvas.videoControls': '视频控件',
  'canvas.videoPlay': '播放',
  'canvas.videoPause': '暂停',
  'canvas.videoMute': '静音',
  'canvas.videoUnmute': '取消静音',
  'canvas.videoProgress': '进度',
  'canvas.videoVolume': '音量',
  'canvas.videoPip': '画中画',
  'canvas.videoFullscreen': '全屏',
  'canvas.videoExitFullscreen': '退出全屏',
  // 文档里放图批（作者报障 2026-11-05「文档里只有路径 图片没渲染出来」）：
  // 取不到图时**明确占位**（原因 + 原路径），不许留白。
  'canvas.imgFailTitle': '图片加载失败',
  'canvas.imgFailMissing': '文件不存在（HTTP {status}）',
  'canvas.imgFailDenied': '无权读取，票据被拒（HTTP {status}）',
  // 401 / 403 **分叉**（作者裁 2026-11-19：「区分」）：401 = 请求根本没带票
  // （铸票面没给票 / 退避窗内无票），真因是「没有凭据」而不是「凭据被拒」；
  // 403 = 带了票但票被判据拒绝（`X-Nf-Reason`：invalid-ticket/expired/path-mismatch…）。
  // 两态共用一句会把「没有票」误报成「票被拒」，故拆键。
  'canvas.imgFailNoCredential': '未取得读取凭据（HTTP {status}）',
  'canvas.imgFailBusy': '服务繁忙（HTTP {status}）',
  'canvas.imgFailBadPath': '路径不合法（HTTP {status}）',
  'canvas.imgFailUnsupported': '这个类型不供图片取字节（HTTP {status}）',
  'canvas.imgFailUnreachable': '读不到这张图（路径无效或无权限）',
  'canvas.imgFailUnknown': '读取失败（HTTP {status}）',
  'canvas.closeTab': '关闭标签页',
  'canvas.stackToggle': '叠卡切换（滚轮 / 上下拖动换卡 · 前景轮换 · 点击前景卡换入 · 横向划走关闭）',
  'canvas.noRef': '该标签页暂不支持引用',
  // canvas-reveal-in-finder 批（2026-11-19，作者令「在访达/文件浏览器打开」）：
  // 文案随平台（引擎侧 target 选键，前端不猜平台）+ 文件不存在时的置灰原因。
  'canvas.revealInFinder': '在访达中显示',
  'canvas.revealInExplorer': '在资源管理器中显示',
  'canvas.revealInFileManager': '在文件管理器中打开',
  'canvas.revealMissing': '文件已不存在',
  'canvas.revealRefused': '这个位置不允许在文件管理器中打开',
  'canvas.revealFailed': '打开文件管理器失败',
  // canvas-tab-reference 批（2026-11-18）：引用落不进 composer 时的明确提示
  // （改前该腿静默 —— 点了引用钮什么也看不到）。
  'canvas.refNoComposer': '引用没送进输入框（对话输入框还没就绪）。打开对话后再点一次引用。',
  'canvas.unreadableTitle': '文件不可读',
  'canvas.unreadableHint': '文件不存在或被清理，无法读取。',
  'canvas.unreadablePathHint': '路径非法，无法读取。',
  'canvas.loadingFile': '正在打开…',
  'canvas.loadFailed': '打开失败',
  'canvas.retry': '重试',
  // Pop 文件卡乐观打开批（2026-11-18）：占位里的失败原因必须**可读且分型**——
  // 离线 / 取不到 / 凭据判定拒绝 / 类型不支持 / 超时不再同貌，也不再有空白页。
  'canvas.loadOffline': '网络不可用（离线），无法取到文件。',
  'canvas.loadDenied': '无权读取该文件（凭据判定拒绝）。',
  'canvas.loadNotFound': '文件不存在或已被清理。',
  'canvas.loadTimeout': '取数超时，请重试。',
  'canvas.loadCancelled': '已取消。',
  'canvas.loadBusy': '服务繁忙，请稍后重试。',
  // canvas 文件刷新批（2026-10-25 作者令）：页签右下「重新读盘」钮。canvas-auto-
  // refresh 批（2026-11-17 作者令「去掉 改为带迟滞的自动检查变更刷新 时间窗60s」）
  // 起手动钮与 canvas.refresh 键退役；refreshDirty 保留（自动路径脏守卫复用）。
  'canvas.refreshDirty': '有未保存的修改，先保存或撤销后再刷新。',
  // 脏态「有更新」提示（canvas-auto-refresh 批）：迟滞到点但页签脏/源码态时挂的
  // 轻量告知面（非常驻按钮），点击「立即刷新」仍走 refreshDirty 守卫。
  'canvas.updateHint': '这个文件在磁盘上已有新内容（自动刷新已让路：本地有未保存的修改）。',
  'canvas.updateNow': '立即刷新',
  // B7 文档后续批（2026-11-01）：脏页签关闭确认 + 外部改动冲突条
  'canvas.closeDirtyTitle': '关闭未保存的页签？',
  'canvas.closeDirtyMsg': '「{name}」有未保存的修改，关闭后这些修改会丢失。',
  'canvas.closeDirtyConfirm': '关闭并丢弃',
  'canvas.conflictDirty': '这个文件在磁盘上已被改动，你本地有未保存的编辑。',
  'canvas.conflictKeep': '保留我的',
  'canvas.conflictReload': '重载磁盘版本',
  // B7-4 文件树搜索 + 排序（客户端，服务端顺序不动）
  'explorer.searchPlaceholder': '搜索文件…',
  'explorer.sortDefault': '默认顺序',
  'explorer.sortNameAsc': '名称 ↑',
  'explorer.sortNameDesc': '名称 ↓',
  'explorer.sortMtime': '最近修改',
  'explorer.sortSize': '体积最大',
  'canvas.refreshFailed': '刷新失败：{error}',
  // 阅读器 P0 止血批（设计稿 20261030_181655 §1.2/§2.1，验收表 A1-A12 i18n key 回填）
  'epub.prev': '上一页',
  'epub.next': '下一页',
  'epub.menu.close': '关闭',
  'epub.theme.label': '主题',
  'epub.theme.paper': '纸质',
  'epub.theme.light': '明亮',
  'epub.theme.dark': '暗夜',
  'epub.aa.fontSize': '字号',
  'epub.aa.lineHeight': '行距',
  'epub.aa.paraSpace': '段距',
  'epub.aa.paraCompact': '紧凑',
  'epub.aa.paraStandard': '标准',
  'epub.aa.paraLoose': '宽松',
  'epub.aa.fontFamily': '字族',
  'epub.aa.serif': '衬线',
  'epub.aa.sans': '无衬线',
  'epub.resMissing': '{count} 个资源缺失',
  'epub.imgTooBig': '图片超过 8MB，未加载',
  // 阅读器 P1 统一 chrome（设计稿 §2.2/§2.3/§4.1；文案断言走 i18n key）
  'reader.toc': '目录',
  'reader.aria.aa': '阅读设置',
  'reader.aria.toc': '目录',
  'reader.aria.more': '更多',
  'reader.aria.slider': '阅读进度',
  'reader.mode': '翻页模式',
  'reader.mode.scroll': '滚动',
  'reader.mode.flip': '翻页',
  'reader.jump': '跳转',
  'reader.jumpPct': '进度 %',
  'reader.refresh': '刷新',
  'reader.pos': '{title} · {pct}%',
  'reader.chapter': '第 {n} 节',
  // 阅读器 P3 生态批（设计稿 §3.2/§4.2）：mobi/azw3 降级面 + 引擎通用标注面
  'mobi.encrypted': '此电子书已加密，阅读器不解密——仅可下载。',
  'mobi.unsupported': '此电子书用了暂不支持的压缩编码（HUFF/CDIC）——仅可下载。',
  'mobi.tooBig': '此电子书解压后超出体积闸——仅可下载。',
  'mobi.invalid': '不是可解析的 MOBI/AZW3 文件。',
  'mobi.download': '下载',
  'reader.anno.list': '笔记',
  'reader.anno.note': '写笔记',
  'reader.anno.del': '删除',
  'reader.anno.save': '保存',
  'reader.anno.close': '关闭',
  'reader.anno.placeholder': '写点批注…',
  'reader.anno.stale': '原文已变动',
  'reader.anno.yellow': '黄色',
  'reader.anno.green': '绿色',
  'reader.anno.blue': '蓝色',
  'reader.anno.pink': '粉色',
  // 卡窗白屏修复批（2026-10-22）：独立卡窗引导态面失败文案（canvas.js cardWindowBootFace）
  'canvas.cardwinFailHint': '卡片内容加载失败，可重试。',
  'canvas.cardwinAuthHint': '登录状态可能已过期——重试会自动续登或弹出登录引导。',
  // 独立窗口内容物收口批（2026-10-22 作者令）：仅内容物可独立窗口化
  'canvas.cardwinContentOnly': '仅文档、文件等内容物可独立窗口打开，功能面板不支持。',
  'canvas.cardwinNoPath': '该独立窗链接未携带内容路径，仅文档、文件等内容物可独立打开。',

  // === Tool names (label first line) ===
  'tool.Read': '读取',
  'tool.Edit': '编辑',
  'tool.Write': '写入',
  'tool.Bash': '执行命令',
  'tool.Grep': '搜索',
  'tool.Glob': '查找文件',
  'tool.TaskCreate': '创建任务',
  'tool.TaskUpdate': '更新任务',
  'tool.Card': '卡片',
  'tool.AskUser': '询问用户',
  'tool.WebSearch': '网络搜索',
  'tool.WebFetch': '抓取网页',
  'tool.Curl': 'HTTP 请求',
  'tool.GenerateImage': '生图',
  'tool.MCP': 'MCP',

  // === Tool result summaries ===
  'tool.result.lines': '{n} 行',
  'tool.result.linesOfOutput': '{n} 行输出',
  'tool.result.fileCreated': '文件已创建',
  'tool.result.fileUpdated': '文件已更新',
  'tool.result.edited': '已编辑',
  'tool.result.created': '已创建',
  'tool.result.lineAdded': '{n} 行已添加',
  'tool.result.lineRemoved': '{n} 行已删除',
  'tool.result.noOutput': '无输出',
  'tool.result.noMatches': '无匹配',
  'tool.result.noFilesFound': '未找到文件',
  'tool.result.filesFound': '{n} 个文件',
  'tool.result.filesMatched': '{n} 个文件匹配',
  'tool.result.filesWithMatches': '{n} 个文件匹配',
  'tool.result.matches': '{n} 个匹配',
  'tool.result.linesFetched': '{n} 行',
  'tool.result.timedOut': '超时',
  'tool.result.blocked': '已阻止',
  'tool.result.interactiveBlocked': '交互式命令已阻止',
  'tool.result.background': '后台运行',
  'tool.result.sandboxBypassed': '沙箱已绕过',
  'tool.result.autoBackground': '自动转入后台',
  'tool.result.taskCreated': '任务 #{id} 已创建',
  'tool.result.ofLines': '{current} / {total} 行',

  // === Scheduled Tasks ===
  'task.empty': '暂无定时任务',
  'task.emptyHint': '点击空白处或按 + 创建',
  // 新建抽屉（todo-fullbleed 批 2026-11-05：手机页面态新建入口改底部抽屉）
  'task.sheetTitle': '新建定时任务',
  'task.sheetCancel': '取消',
  'task.sheetDone': '创建',
  // schedule-todo-unify 批 2026-11-06：新建/编辑面拆 name + description 两段
  // （数据面约定 = content 首行 name、其余行 description，见 scheduled-task.js nameOf/descriptionOf）。
  'task.inputPlaceholder': '任务名称',
  'task.descPlaceholder': '描述（可选）',
  'task.editTitle': '编辑定时任务',
  'task.nameRequired': '名称不能为空',
  'task.detailTitle': '详情',
  'task.detailEmpty': '无描述',
  'task.triggerTime': '触发时间',
  'task.triggered': '已触发',
  'task.triggerTitle': '定时任务已触发',
  'task.scheduledAt': '预定于 {time} 触发，正在执行...',
  'task.overdue': '已超时',
  'task.today': '今天',
  'task.tomorrow': '明天',
  'task.yesterday': '昨天',
  'task.lastTriggered': '上次触发',
  'task.queuedOffline': '连接中断，任务已排队，将在重连后保存',
  'task.createFailed': '定时任务创建失败',
  'task.repeat': '周期',
  'task.repeatOnce': '单次',
  'task.grpUpcoming': '即将触发',
  'task.grpDisabled': '已停用',
  // 认知二期产品批③打卡：待办组签 / 打卡销账 / 取消 / 缺卡调整建议
  'task.grpCheckin': '打卡',
  'task.checkin': '打卡销账',
  'task.cancel': '取消（不留痕）',
  'task.missSuggest': '已连续 {n} 次浮出未销账——建议降频或拆小，要调整请直接说',
  'task.repeatHourly': '每小时',
  'task.repeatDaily': '每天',
  'task.repeatWeekly': '每周',
  // schedcard 批（2026-10-29）：定时任务专属卡（chat 流）文案
  'task.cardChip': '定时任务',
  'task.cardScheduledAt': '预定 {time}',

  // === 待办（待办分家批 2026-10-30：待办独立侧边栏面板，与定时任务分家）===
  'activity.todo': '待办',
  'todo.title': '待办',
  'todo.empty': '暂无待办',
  'todo.emptyHint': '点击空白处或按 + 创建',
  'todo.inputPlaceholder': '输入待办内容...',
  // AskUserQuestion 镜像行（引擎 AskTodoBridge 产物）：点击跳回提问卡作答，
  // 作答后镜像随行销账（闭环 Ask→待办→答→销账）。
  'todo.askChip': '待答',
  'todo.askJump': '点击跳转到提问卡片作答',
  // 待办体系改版批（2026-10-31 作者令）：用户面收件箱——消息列表入口行 +
  // 提醒事项页（分组：已到期浮顶 / 待决策 / 待批准 / 计划）。
  'todo.entryTitle': '待办事项',
  'todo.entryEmpty': '全部清零',
  'todo.grpOverdue': '已到期',
  'todo.grpDecision': '待决策',
  'todo.grpApproval': '待批准',
  'todo.grpPlan': '计划',
  // Permission / McpPermission 镜像行（引擎 AskTodoBridge perm- 产物）：点击
  // 跳回批准卡处理，卡终态随行销账。
  'todo.permChip': '待批准',
  'todo.permJump': '点击跳转到批准卡片处理',
  // 追加令（2026-10-31 23:14）：项目维度（分组/筛选）+ 增删改查全量能力。
  'todo.filterAll': '全部',
  'todo.filterUngrouped': '未分组',
  'todo.projectPlaceholder': '项目（可选）',
  'todo.edit': '编辑',
  // 任务模式标注批（2026-10-30 作者令「派发任务显示执行模式」）+ 术语统一批
  // （2026-11-06 作者令「统一 根据英文」）：执行形态徽标的词面**不再走 locale**——
  // task.mode.kernel / task.mode.dispatcher 两键随本批删除，词面单源 =
  // js/termWords.js（kernel / dispatcher 逐字英文，中文面同串）。
  // 视觉类名仍由数据值给（.bg-mode-kernel / .bg-mode-dispatcher）。
  // base 漂移状态位（`[base-moved]` 降噪重写批 2026-11-05）：基分支自本任务派发以来
  // 前进的次数——**只陈述状态事实**（作者令「base 动了放面板可见即可，不进消息流」），
  // 不解释机制、不给建议。
  'task.baseDrift': 'base 已前进 {n} 次',
  'bg.modeTitle': '执行模式：{mode}',

  // NebLink
  'neblink.thisDevice': '本机',
  'neblink.unknownDevice': '未命名设备',
  // `neblink.noPeersHint` 已删（① 八点返工 2026-09-15）：其唯一宿主 = 设置账号段的
  // 设备列表（无 peer 提示行），列表已移出设置 ⇒ 死键同批删除（en 侧同步）。
  'neblink.online': '在线',
  'neblink.offline': '离线',
  'neblink.reachDirect': 'P2P 直连已建立',
  'neblink.reachRelay': '可经服务器中继',
  'neblink.reachServerOnly': '在线——暂无直连通道',
  // 「清理并重登」入口（缺陷 A，2026-09-18）：**保留** —— activityBar 登录失败面仍用
  // （`#login-cleanup` → `openEndSessionHandoff(true)`）。
  'neblink.cleanupRelogin': '清理并重登',
  // 账户卡的 `neblink.loggedOutHint` / `neblink.statusDegraded` /
  // `neblink.signedOutElsewhere(提示)` / `neblink.pairing` / `neblink.loggedInAs` /
  // `settings.account` 七键随「登录账号」账户卡整件退役（2026-11-18 作者令）：卡片没了，
  // 全仓零引用（grep 实证 ⇒ zh/en 对称删除）。
  'neblink.deviceDescHint': '此描述对 Agent 可见，Agent 会根据你的描述自动选择合适的设备执行任务',
  'neblink.save': '保存',
  'neblink.stable': '稳定版',
  'neblink.beta': '测试版',
  'neblink.cancel': '取消',
  // 远端更新的三态文案（`neblink.updating` / `neblink.restarting` / `neblink.updateTimeout`）
  // 随件五（2026-11-02「联系人里的设备更新去掉，现在统一由官网进行统一更新」）删除。
  // `neblink.update` 亦已删除：其唯一消费者 = 设置页设备区每行（fleet 批 2026-11-01），
  // 该区随「登录账号」账户卡整件退役（2026-11-18 作者令）⇒ 全仓零引用。

  // ── 全设备统一更新（fleet 批 2026-11-01，作者令「一键把所有设备更新到同一版本，
  //    并全程有状态卡片引导」）──────────────────────────────────────────────
  // 帧内只带键（`stageKey` / `failureKey` / `statusKey` / `capabilityKey`），展示文案
  // 全在这里——禁在 JS 里硬编码中文。`{...}` 为插值占位（i18n.js 的 t()）。
  'fleet.title': '全设备统一更新',
  'fleet.subtitle': '本机 v{current} → 目标 v{target} · 阶段：{stages}',
  'fleet.stages': '准备 → 下载 → 校验 → 切换 → 重启 → 自检 → 完成',
  // 自解释批（2026-11-04 铁律 8 反例 2 同族）：原「正在更新，请稍候……更新期间本控制台
  // 不可用，完成后自动接续」= 行为教学（教用户等）+ 能力承诺；改为状态事实：阶段名 + ETA。
  'fleet.blocking': '正在更新——当前阶段「{stage}」，{eta}。',
  // 原「设备已发起重启，正在等待它回来（回到本页会自动接续，不需要手动刷新）」= 行为教学 +
  // 括号补注；改为状态事实：重启中 / 等待重连（细则 b 状态事实）。
  'fleet.reconnecting': '本机已发起重启——等待重连。',
  'fleet.eta': '预计 3–5 分钟',
  'fleet.empty': '暂无可更新的设备。',
  'fleet.close': '关闭',
  'fleet.cancel': '中断',
  'fleet.retry': '重试失败项',
  'fleet.startConfirm': '开始全部更新',
  'fleet.updateThisDevice': '更新此设备',
  'fleet.thisDevice': '本机',
  'fleet.online': '在线',
  'fleet.offline': '离线',
  'fleet.versionUnknown': '版本未知',
  'fleet.attempt': '第 {n} 次尝试',
  'fleet.manualTitle': '需手动更新（版本过旧或无法远控）',
  // `fleet.manualHow` 已删（自解释批 2026-11-04 铁律 8 反例 1 原样点名）：路径指引
  // 「到该设备上打开控制台 → 设置 → 检查更新」。用户要的是「这行为什么不能一键推」，
  // 由上面的事实徽标（`fleet.capability.*` / `fleet.stage.manual`）承载；行内只剩
  // 设备名 + 版本（事实）。渲染点 `fleetUpdate.js` 的手动行徽标同批摘除。
  'fleet.pendingTitle': '待上线（上线后自动补推）',
  'fleet.summary.counts': '{updated} 台已更新、{failed} 台失败、{manual} 台需手动、{pending} 台待上线',
  // 收口状态（引擎 `FleetSummary.status` 的 messageKey 面）
  'fleet.summary.all-consistent': '全部一致',
  'fleet.summary.manual-required': '有待手动更新的设备',
  'fleet.summary.pending-online': '有待上线设备',
  'fleet.summary.out-of-sync': '版本尚未统一（可推送更新的设备已列出）',
  // 任务状态（`fleet.job.<status>`）
  'fleet.job.running': '进行中',
  'fleet.job.completed': '全部完成',
  'fleet.job.partial': '部分成功',
  'fleet.job.cancelled': '已中断',
  // 七阶段 + 非进度态（`fleet.stage.<wire>`，与 FleetStage 的 wire 值一一对应）
  'fleet.stage.queued': '排队中',
  'fleet.stage.prepare': '准备',
  'fleet.stage.download': '下载中',
  'fleet.stage.verify': '校验中',
  'fleet.stage.switch': '切换中',
  'fleet.stage.restart': '正在重启',
  'fleet.stage.healthcheck': '健康自检',
  'fleet.stage.done': '完成',
  'fleet.stage.failed': '失败',
  'fleet.stage.manual': '需手动更新',
  'fleet.stage.waiting-online': '待上线',
  'fleet.stage.cancelled': '已中断',
  // 失败五类 + 可操作建议（`fleet.failure.<wire>` / `.advice`）
  'fleet.failure.download-failed': '下载失败',
  // 自解释批（2026-11-04 铁律 8）：删两条 advice 的第二句「多次失败请在该设备上手动更新」
  // ——能力徽标（需手动更新）已说明该事实（重复说明）；保留「失败原因 + 下一步」的失败收口
  // （细则 b 失败收口，一句话）。
  'fleet.failure.download-failed.advice': '建议：网络恢复后点「重试失败项」。',
  'fleet.failure.verify-failed': '校验不过（安装包与本机记录不符）',
  'fleet.failure.verify-failed.advice': '建议：重试一次；仍不过说明包源有问题。',
  'fleet.failure.switch-rolled-back': '切换回滚（新版本未站住，已退回上一版）',
  'fleet.failure.switch-rolled-back.advice': '建议：已自动回滚到可用版本，本机仍在正常服务；请查看该设备日志后手动更新。',
  'fleet.failure.healthcheck-failed': '自检没过（重启后健康检查失败）',
  'fleet.failure.healthcheck-failed.advice': '建议：查看该设备日志；确认问题后点「重试失败项」。',
  'fleet.failure.device-lost': '设备失联',
  'fleet.failure.device-lost.advice': '建议：确认该设备已开机并联网，然后点「重试失败项」。',
  'fleet.failure.device-refused': '设备拒绝更新',
  'fleet.failure.device-refused.advice': '建议：按上面的原因处理；版本过旧的设备请手动更新。',
  'fleet.failure.unknown': '未知失败',
  'fleet.failure.unknown.advice': '建议：重试一次；仍失败请查看该设备日志。',
  // 能力门槛（`fleet.capability.<wire>`）：每台设备的「可远控更新 / 需手动」
  'fleet.capability.self-reported': '可远控更新（自报支持）',
  'fleet.capability.version-threshold': '可远控更新（版本达标）',
  'fleet.capability.version-too-old': '需手动更新（版本过旧）',
  'fleet.capability.unknown': '需手动更新（版本未知）',
  // 分发分支（`fleet.branch.<wire>`）
  'fleet.branch.push': '直接推送',
  'fleet.branch.manual': '需手动',
  'fleet.branch.queued': '待上线补推',

  // 登录态只读展示的 `neblink.loggedInAs`（「登录账号：{name}」）已随「登录账号」账户卡
  // 整件退役（2026-11-18 作者令）删除；auth-trim 批（2026-10-20 作者令）的
  // 「切换账号 / 退出登录」键组（switchAccount/logout/loggingOut/useOtherAccount/currentTag）
  // 早随两键删除 —— 登出/切号只在官网门户操作。
  // 退出登录键（2026-11「退出登录没有入口」修复批，作者裁「只修退不出」——本批
  // 豁免 auth-trim 批「控制台不承担认证态管理」那条裁定的范围，仅限退出）：设置
  // 账号段的次级键 + 头像菜单项共用同一条文案。切号仍只在官网门户操作。
  'neblink.logout': '退出登录',

  // Dropbox
  'dropbox.tabChat': '对话',
  'dropbox.tabDesc': '描述',
  'dropbox.attachFile': '选择文件',
  'dropbox.inputPlaceholder': '输入消息…',
  'dropbox.send': '发送',
  'dropbox.transferring': '传输中…',
  'dropbox.delivered': '已送达',
  'dropbox.saved': '已保存',
  'dropbox.completed': '已完成',
  'dropbox.failed': '传输失败',
  'dropbox.tooManyFiles': '附件件数超限：实际 {actual} 件，上限 {limit} 件',
  'dropbox.fileTooLarge': '文件过大{name}：实际 {actual}（{actualBytes} 字节），上限 {limit}',
  'dropbox.notConnected': '未连接到网关——文件未发送，请重试',
  // ⑩ 消息区状态行（消息区永不为空）：无本地缓存且请求在飞 ⇒ 加载态；
  // 无缓存且无在飞请求 ⇒ 空态。
  'dropbox.loadingMessages': '正在加载消息…',
  'dropbox.noMessages': '暂无消息',

  // === Chat History Search ===
  // 标题两字档（2026-11-06 作者令逐字「不要有省略 那就用搜索两个字吧」）：
  // 「搜索聊天记录」整族写法退役，搜索页标题 / 开启钮 tooltip 一律「搜索」。
  'search.btnTitle': '搜索',
  'search.title': '搜索',
  'search.placeholder': '输入关键词…',
  'search.scopeCurrent': '当前会话',
  'search.scopeAll': '全部会话',
  'search.scopeMain': '主会话',
  // === 消息/联系人页展开式搜索（悬浮钮统一+搜索批 2026-10-31 作者令 16:03）===
  'mhsearch.aria': '搜索',
  'mhsearch.placeholder': '搜索聊天记录',
  'mhsearch.clear': '清除',
  'mhsearch.scopeAll': '全部',
  'mhsearch.scopeFriend': '好友',
  'mhsearch.scopeDevice': '设备',
  'mhsearch.scopeGroup': '群聊',
  'mhsearch.searching': '搜索中…',
  'mhsearch.resultCount': '{n} 条结果',
  'mhsearch.descHits': '资料命中 · {n} 个会话',
  'mhsearch.noResults': '无匹配结果',
  'mhsearch.noResultsHint': '试试更换关键词或切换范围',
  'mhsearch.error': '搜索失败，请稍后重试',
  'mhsearch.capNote': '每段最多 1000 条消息',
  'mhsearch.scanScope': '已扫描 {pages} 页',
  'mhsearch.scanPartial': '结果不完整：命中或达到扫描上限即停止',
  'mhsearch.scanFailed': '部分搜索失败或超时，请重试',
  'mhsearch.fullScan': '完整扫描消息（可能较慢）',
  'mhsearch.loadMore': '加载更多（本会话）',
  'mhsearch.me': '我',
  'mhsearch.jumpFail': '未能定位到该消息',
  'search.allTypes': '全部类型',
  'search.allAgents': '全部 Agent',
  'search.anyTool': '任意工具调用',
  'search.go': '搜索',
  'search.hint': '输入关键词，或选择工具/日期过滤条件',
  'search.searching': '正在搜索…',
  'search.progress': '正在搜索 {done}/{total} 个会话…',
  'search.results': '{n} 条结果',
  'search.loadedOf': '已加载 {n}/{m} 条',
  'search.loadMore': '加载更多（已加载 {n}/{m} 条）',
  // 多选文件批量在 Canvas 打开（chatsearch-batch-open 批）
  'search.openSelectedInCanvas': '打开所选文件 ({n})',
  'search.selectFiles': '选择文件',
  'search.cancelSelection': '取消选择',
  'search.jumpArchived': '该消息在压缩前归档里，回放窗口内无原位',
  'search.noResults': '无匹配结果',
  'search.noResultsSuggestion': '试试缩短关键词或清除日期/类型筛选',
  'search.noSession': '当前没有活跃会话',
  'search.failed': '搜索失败',
  'search.timeout': '搜索超时，请重试',
  'search.retry': '重试',
  'search.jumpFailed': '未能在会话中定位该消息',
  'search.typeUser': '用户',
  // 助手产出（注入蓝气泡）独立类别 —— 作者 2026-09-19 06:09 分类粒度裁定
  'search.typeAgent': '助手',
  'search.typeAi': 'AI',
  'search.typeTool': '工具',
  // v3.1 tabs + date anchor (spec §6.2) · v3.2 (#491 主题二 §6 候审清单):
  // tabPop/tabImages 删，tabMedia/tabLinks + 来源/分组键增——键位逐字以计划书
  // §6 清单为准，过目前为冻结闸前置件。
  'search.tabAll': '全部',
  'search.tabMedia': '图片与视频',
  'search.tabFiles': '文件',
  'search.tabLinks': '链接',
  'search.tabDate': '日期',
  'search.sourceAll': '全部来源',
  'search.sourceUser': '用户',
  'search.sourceAgent': '助手',
  'search.groupThisWeek': '本周',
  'search.groupMonth': '{n} 月',
  'search.emptyCategory': '该栏目下暂无内容',
  'search.datePickTitle': '选择日期',
  'search.dateClear': '清除日期',
  'search.dateConfirm': '确定',
  'search.dateCancel': '取消',
  'search.toolDisabledHint': '当前栏目下不适用工具筛选',
  'search.dateDisabledInPop': 'Pop 栏目下不可用日期定位',
  // v2 (2026-09-11)：结果行内卡片本体 + 独立消息浮层窗口
  'search.cardUnavailable': '卡片内容不可用',
  'search.cardRenderFailed': '卡片渲染失败',
  'search.cardLoadFailed': '卡片资源加载失败',

  // === Daemons (Heartbeat) ===
  'daemons.title': '心跳进程',
  'daemons.pollEvery': '每 5 秒轮询',
  'daemons.empty': '暂无心跳进程',
  'daemons.emptyHint': '心跳进程由 Agent 在执行任务时创建（例如让 Agent 启动开发服务器），用于需要长期在线的服务。',
  'daemons.emptyHint2': '宿主会周期检测服务端口，进程掉线约 10 秒后自动拉起。',
  'daemons.emptyHint3': '适用场景：开发服务器、定时任务、持续监控。',
  'daemons.start': '启动',
  'daemons.stop': '停止',
  'daemons.restart': '重启',
  'daemons.restartFirst': '先启动',
  'daemons.deleteTitle': '删除心跳进程',
  'daemons.deleteConfirm': '删除心跳进程「{name}」？',
  // daemonpanel Phase A — 配置面板（daemons.config* 键）
  'daemons.config': '配置',
  'daemons.configClose': '关闭',
  'daemons.configSave': '保存',
  'daemons.configSaved': '已保存',
  'daemons.configEnabled': '启用',
  'daemons.configSecretHint': '保留「***」表示沿用已存值；留空则不修改。',
  // === Explorer（文件浏览器多选） ===
  'explorer.selectedCount': '已选 {count} 项',
  // B1-1：树容器 role="tree" 的可及名（读屏与键盘断言共用的稳定标签）
  'explorer.treeLabel': '文件树',
  'explorer.delete': '删除',
  'explorer.deleteN': '删除 {count} 个所选项',
  'explorer.deleteTitle': '删除文件',
  'explorer.deleteConfirmN': '删除这 {count} 项？此操作不可撤销。',
  'explorer.deleteConfirmOne': '删除「{name}」？此操作不可撤销。',
  'explorer.deleteResult': '{ok} 项已删除，{fail} 项失败：{path}: {error}',
  'explorer.moved': '已移动 {name}',
  'explorer.imported': '已导入 {count} 项',
  'explorer.conflictTitle': '替换',
  'explorer.conflictMsg': '目标位置已存在同名项「{name}」。是否替换？',
  'explorer.conflictKeepBoth': '保留两者',
  'explorer.conflictReplace': '替换',
  'flow.noSession': '该节点还没有会话记录',
  'flow.runningInTab': '正在运行的工作流已在其标签页中展示',
  // Running flows indicator (2026-08-26 flow-complement-design §5.3)
  'flows.cancel': '取消',
  // === Canvas 保存反馈 ===
  'canvas.saved': '已保存 {name}',
  'canvas.saveFailed': '保存 {name} 失败：{error}',
  // === 文本流：保存撞帽可见性（作者裁 ④，2026-09-21）===
  'canvas.saveTooLarge': '{name} 过大无法保存（超单帧 10MB 上限）——本次修改未保存',
  'canvas.saveTimeout': '保存 {name} 超时——本次修改未保存',
  'canvas.saveBlockedNotice': '文件过大无法保存（超 10MB）：编辑内容不会被持久化',
  // === 大文本只读流式视图（textstream 批，2026-09-21）===
  'canvas.largeTextReadOnly': '大文件 · 只读',
  'canvas.largeTextReadOnlyHint': '超过 8MB 的文件以只读流式视图打开：只按需取可见窗口，整件不会进入编辑器。',
  'canvas.largeTextLoading': '正在加载窗口…',
  'canvas.largeTextLines': '{count} 行',
  'canvas.largeTextDrift': '磁盘上文件已变化 —— 正在重新加载',
  'canvas.largeTextRetry': '重试',
  'canvas.largeTextLoadFailed': '流式取数失败：{error}',
  'canvas.largeTextSearchPlaceholder': '在文件内搜索',
  'canvas.largeTextSearching': '搜索中…',
  'canvas.largeTextSearchHits': '{count} 处命中',
  'canvas.largeTextSearchTruncated': '{count} 处命中（结果被截断——请细化查询）',
  'canvas.largeTextPrevHit': '上一处命中',
  'canvas.largeTextNextHit': '下一处命中',
  'canvas.largeTextGoToLine': '跳转到行',
  // === 文档图谱（wiki-graph 控制面迁左栏批，2026-10-21 作者令：canvas 只留
  // 图谱本体+悬停 tooltip；左栏控制面 = 搜索框+目录 chips+详情卡+断链清单+统计行）===
  'wiki.tabTitle': '文档图谱',
  'wiki.searchPlaceholder': '搜索标题或路径',
  'wiki.remoteDevice': '位于设备：{device}',
  'wiki.remoteFetchFailed': '无法从远端设备取回该文档',
  'wiki.openGraphForDoc': '在图谱中打开',
  'wiki.notInVault': '该文件不在任何项目文档库（<workspace>/docs）内',
  'wiki.graphUnavailable': '文档图谱不可用',
  'wiki.kindAttachment': '附件',
  'wiki.unresolved': '未解析的引用——所有项目文档库中都没有这个名字的文档',
  'wiki.backlinks': '反向链接',
  // 词条引用批 batch 2（hover 气泡 + 返回原文档钮）
  'wiki.termSource': '来源：',
  'wiki.termJumpHint': '点击跳转',
  // 词条索引块（term-system-v2 词条视觉面批 2026-11-05）：文档尾自动生成的
  // 「本文引用的词条 + 它所在的文档」折叠小节；{n} = 词条条数。
  'wiki.termIndexCount': '词条索引（{n}）',
  'wiki.backToSource': '返回原文档',
  // 左栏控制面（搜索/chips/详情卡/断链清单/统计行）
  'wiki.chipAll': '全部',
  'wiki.chipRoot': '根目录',
  'wiki.infoDocs': '{docs} 篇文档 · {links} 条引用 · ',
  'wiki.infoUnresolved': '{n} 条未解析',
  'wiki.inputHint': '滚轮/捏合 = 缩放（光标锚点）· 中键拖拽·触控板滚动·左键拖空白 = 平移',
  'wiki.detailEmpty': '在图谱中单击一个节点，这里显示它的详情。',
  'wiki.brokenTitle': '断链清单',
  'wiki.brokenVault': '「{project}」还没有可读的文档库——在工作区建 docs 目录并写入 markdown 即可。',
  'wiki.brokenProject': '「{project}」文档库不可读：{error}',
  'wiki.tipDegree': '被引 {in} · 引出 {out}',
  'wiki.tipJump': '点击跳转并高亮该段',
  'wiki.noSummary': '（无摘要——首段即标题或列表）',
  'wiki.secToc': '目录',
  'wiki.secOut': '引出',
  'wiki.secIn': '被引',
  'wiki.emptyOut': '无出链',
  'wiki.emptyIn': '无入链',
  'wiki.openDoc': '打开文档',
  'wiki.closePreview': '关闭详情',
  'wiki.badgeDevice': '设备：{device}',
  'wiki.allProjects': '全部项目',
  'wiki.kindVault': '子图谱',
  'wiki.vaultDocCount': '{n} 篇文档',
  'wiki.vaultBlurb': '项目下的子项目知识库——进入查看它自己的文档图谱。',
  'wiki.enterVault': '进入子图谱',
  'wiki.crossVault': '{n} 条跨库引用',
  'find.placeholder': '在文件内查找',
  'find.previous': '上一处（Shift+Enter）',
  'find.next': '下一处（Enter）',
  'find.close': '关闭（Esc）',
  'find.openInFile': '在文件内搜索（Ctrl/Cmd+F）',
  // === Canvas 外部文件拖入 / 粘贴（canvasdrop，2026-09-11 作者裁定 R1–R7）===
  'canvas.dropInserted': '已插入 {ref}',
  'canvas.dropBatchInserted': '已插入 {count} 张图片引用',
  'canvas.dropFailed': '插入失败：{error}',
  'canvas.dropUnsupported': '暂不支持该类型：{name}（可拖入的图片：{types}）',
  'canvas.dropNotEditable': '当前是预览/只读视图，不能插入引用 —— 切到源码视图后再拖入',
  'canvas.dropNoTarget': '这里没有可编辑的文件，请拖到正在编辑的文件区域内',
  'canvas.dropTooLarge': '{name} 超过 20MB 上限，未写入',
  'canvas.dropOutsideRoot': '{name} 未写入：文档在工作区外，图片无法落到它旁边。请把文档移入工作区，或改为引用原文件的路径。',
  'canvas.pasteInserted': '已粘贴并插入 {ref}',
  'canvas.pasteUnsupported': '暂不支持该类型：{name}（可粘贴的图片：{types}）',
  'canvas.pasteNotEditable': '当前是预览/只读视图，不能插入引用 —— 切到源码视图后再粘贴',
  'canvas.pasteNoTarget': '这里没有可编辑的文件，请先打开要编辑的文件并聚焦光标',
  'canvas.tabDropOpened': '已打开 {name}',
  // === Lightbox（图片预览） ===
  'lightbox.ariaLabel': '图片预览',
  'lightbox.close': '关闭预览',
  'lightbox.loading': '加载中…',
  'lightbox.loadError': '图片加载失败',
  'lightbox.clickHint': '点击查看大图',
  // 手机图片画廊（mobile-image-lightbox 批 2026-11-17）：P16 直开能力的显式次级入口。
  'lightbox.openTab': '在标签页打开',
  // 框图大图查看（mermaid-viewer-stability 批 2026-11-06）
  'lightbox.diagramLabel': '框图查看',
  'lightbox.zoomIn': '放大',
  'lightbox.zoomOut': '缩小',
  'lightbox.zoomFit': '适应视图',

  // === Usage dashboard（Token 消耗看板，spec token-dashboard-spec.md §7.5） ===
  'usage.title': '用量看板',
  'usage.activityEntry': '用量看板',
  'usage.activityEntryHint': '查看 Token 消耗热力图与缓存命中率',
  'usage.open': '打开用量看板',
  'usage.range.7': '7 天',
  'usage.range.30': '30 天',
  'usage.range.90': '90 天',
  'usage.range.custom': '自定义',
  'usage.range.tooLong': '范围上限 365 天',
  'usage.filterAll': '全部',
  'usage.filterNeedsBackend': '维度过滤需后端支持',
  'usage.stats.1d': '1 天',
  'usage.stats.1w': '1 周',
  'usage.stats.1m': '1 月',
  'usage.stats.3m': '3 月',
  'usage.statsRangeLabel': '统计区间',
  'usage.heatDim': '热力图维度',
  'usage.heatDim.total': '总计',
  'usage.heatDim.model': '模型',
  'usage.heatDim.agent': 'agent',
  'usage.heatDimLabel': '{dim} · {value}',
  'usage.curveTitle': '{date} 24 小时分布',
  'usage.curveEmpty': '当日无数据',
  'usage.curvePeak': '峰值 {tokens}（{hour} 时）',
  'usage.curveBar': '{hour} 时，{tokens}，{calls} 次',
  'usage.drillOpen': '展开当日明细',
  'usage.total': '总消耗',
  'usage.today': '今日',
  'usage.weekTrend': '本周环比',
  'usage.cacheHitRate': '缓存命中率',
  'usage.cacheHitRateHint': '目标 96%+',
  'usage.cacheHitRateNoData': '暂无缓存数据',
  'usage.noCacheReported': 'provider 未上报缓存数据',
  'usage.topAgent': '最耗 agent',
  'usage.topModel': '最耗模型',
  'usage.costEquivalent': '计费等效',
  'usage.costEquivalentHint': '计费等效 · cache 0.1x 假设',
  'usage.calls': '调用次数',
  'usage.input': '输入',
  'usage.output': '输出',
  'usage.cacheRead': '缓存读',
  'usage.cacheWrite': '缓存写',
  'usage.hitRate': '命中率',
  'usage.hitRateUnit': '{pct}%',
  'usage.hitRateDelta': '{pct}%（{dir}{delta}pp）',
  'usage.colorMode': '着色模式',
  'usage.colorMode.cost': '消耗量',
  'usage.colorMode.hitRate': '命中率',
  'usage.drilldownTitle': '{date} 明细',
  'usage.dim.agent': '按 agent',
  'usage.dim.provider': '按 provider',
  'usage.dim.model': '按模型',
  'usage.hitRateRankTop': '命中率最高',
  'usage.hitRateRankBottom': '命中率最低',
  'usage.heatmapLabel': 'Token 消耗热力图',
  'usage.heatmapCell': '{date}，消耗 {tokens}，{calls} 次，命中率 {hitRate}',
  'usage.heatmapCellNoCache': '{date}，消耗 {tokens}，{calls} 次',
  'usage.empty': '暂无用量数据',
  'usage.emptyHint': '完成一次 AI 对话后，此处将开始统计',
  'usage.drillEmpty': '当日无该维度记录',
  'usage.loadError': '加载失败',
  'usage.retry': '重试',
  'usage.unit.billion': '亿',
  'usage.unit.million': '万',
  'usage.trend.up': '较昨日 ↑{pct}%',
  'usage.trend.down': '较昨日 ↓{pct}%',
  'usage.trend.flat': '与昨日持平',
  'usage.close': '关闭',
  'usage.loading': '加载中…',
  'usage.legend.cost': '0 · 100万 · 1000万 · 1亿 · 1亿+',
  'usage.legend.hitRate': '— · 25% · 50% · 75% · 100%',
  'usage.weekLabel': '较上周 {dir}{pct}%',
  'usage.noData': '—',
  'perm.retargetNote': '原目标会话 {from} 不可达，已转入本会话呈现',

  // === 界面残留汉化(活动栏 / 面板标题 / 空状态等) ===
  'activity.login': '登录',
  // 登录态的两条 activity bar 悬停文案（2026-09-16 登录入口批）：renderAvatar
  // 此前把它们写成中文字面量，会把 i18n 的 activity.login 覆盖掉。
  'activity.profile': '个人主页',
  'activity.pairing': '配对中…',
  'activity.usage': '用量看板',
  'activity.agents': '智能体',
  // 2026-09-04 件 B：智能体入口改造为插件页（en/zh 成对）
  'activity.plugins': '插件',
  'activity.settings': '设置',
  'activity.newFile': '新建文件',
  'activity.newFolder': '新建文件夹',
  'activity.openFolder': '打开文件夹',
  'header.voiceOutput': '语音输出',
  'header.scheduledTasks': '定时任务',
  'header.canvas': '看板',
  'header.newTask': '新建任务',
  'subagents.panelTitle': '子智能体',
  'modal.close': '关闭',
  'rules.cancel': '取消',
  'rules.save': '保存',
  'bypass.confirmEdits': '确认编辑',
  'bypass.autoEdits': '编辑放行',
  'bypass.autoAll': '全部放行',
  'agentManager.loading': '加载中...',
  'agentManager.noAgents': '未配置智能体',
  'agentManager.saved': '已保存',
  'agentManager.save': '保存',
  'agentManager.builtinNote': '内置 agent：系统提示词由代码定义（只读）；模型链可在 /model 面板调整',
  // === 插件页（2026-09-13 无审批批：在位即信任；2026-09-14 面板收敛批：
  //     封禁/解封 UI 全量退场 + 派发开关改「任务分发器可见性」并移到卡片右上，en/zh 成对） ===
  'plugins.list': '插件',
  'plugins.listHint': '统一插件系统：技能与 MCP 以插件为单位。装好即可用。本开关只影响任务分发器对未来节点的派发（已在跑的节点保持其许可）；停用某个插件走 API / CLI（面板不提供封禁入口）',
  'plugins.empty': '暂无插件',
  'plugins.stateBlocked': '已封禁',
  'plugins.blockedHint': '已封禁——该插件不进分发器目录、不会被派发；解封走 API / CLI（面板不提供入口）。',
  'plugins.dispatchLabel': '任务分发器可见性',
  'plugins.dispatchOnTitle': '派发许可已开启——可派给新节点。点击后只挡未来派发；已在跑的节点保持其许可',
  'plugins.dispatchOffTitle': '派发许可已关闭——不会再派给新节点。点击恢复派发',
  'plugins.dispatchBlockedBlocked': '已封禁——本开关不会生效；解封走 API / CLI（面板已无入口）',
  'plugins.dispatchBlockedTransition': '存在临时派发授权——该授权到期或被清除后，本开关才生效',
  'plugins.uninstall': '卸载',
  'plugins.uninstallTitle': '卸载插件',
  'plugins.uninstallConfirm': '卸载「{name}」后它将移出插件注册表，且不会再被自动装回。确认卸载将同时删除其插件目录（含本地数据不可恢复）；也可以仅停用、保留目录。',
  'plugins.uninstallDeleteLabel': '卸载并删除目录',
  'plugins.uninstallKeepLabel': '仅停用（保留目录）',
  'plugins.uninstalled': '已卸载 {name}',
  'plugins.migratedHint': '该包名已迁移：「{name}」现以「{newName}」安装。删除旧目录（~/.stand/plugins/{name}/）后用新名重装即可恢复。',
  'plugins.author': '作者 {author}',
  'plugins.compositionSkills': '{n} 个技能',
  'plugins.compositionMcp': 'MCP：{servers}',
  'plugins.compositionTools': '内建工具 +{n}',
  'plugins.statSkills': '{n} 个技能',
  'plugins.statMcp': '{n} 个 MCP',
  'plugins.rejected': '拒载',
  'plugins.loading': '加载中...',
  'plugins.loadFailed': '插件清单加载失败：{error}',
  'plugins.authRequired': '未登录 · 需注册 — 登录后使用插件面板',
  'plugins.relogin': '登录',
  'plugins.builtinBadge': '内置',
  'plugins.builtinTitle': '内置 agent：定义在代码中（BuiltinAgents），面板只读',
  'plugins.detail': '详情',
  // 源化插件（2026-11-01 源化批，作者令「运行时插件要能热更新、立即用最新版本」）：
  // 面板每卡的「来源 + 版本」行与「更新」钮。仅对**有源声明**的包渲染（seed 包零变化）。
  'plugins.sourceLabel': '来源 {source} · v{version} · {state}',
  'plugins.sourceCurrent': '已是最新',
  'plugins.sourceOutdated': '有新版本',
  'plugins.sourceUnreachable': '远端不可达，保持本地版本',
  'plugins.sourceNotInstalled': '未安装',
  'plugins.sourceUpdate': '更新',
  // 自解释批（2026-11-04 铁律 8）：删括号补注「（零重启；失败则本地版本不变）」——能力说明
  // + 行为承诺；保留版本迁移事实 v{from} → v{to}（细则 c 正例 1 同形）。
  'plugins.sourceUpdateTitle': '拉取并生效：v{from} → v{to}',
  'plugins.sourceUpdated': '{name} 已更新',
  'explorer.loading': '加载中...',
  'explorer.empty': '空',
  'flowViewers.saving': '保存中…',
  'flowViewers.retry': '重试',
  'flowViewers.save': '保存',
  'flowViewers.loading': '加载中…',
  'flowViewers.saved': '✓ 已保存',
  'flowViewers.saveFailed': '保存失败: {msg}',
  'flowViewers.rulesHint': '注入到每个团队智能体的系统提示词中，激活时生效。',
  'flowList.createHint': '创建 ~/{dir}/flows/&lt;name&gt;/flow.json 以定义 DAG 工作流',
  'flowList.loading': '正在加载工作流…',
  // 中文化补漏批（2026-08-30）：实体类型/工作流术语/面板硬编码收口
  'entity.standalone': '独立智能体',
  'entity.team': '团队',
  'entity.agent': '智能体',
  'entity.node': '节点',
  'flowViewers.pending': '待处理',
  'flowViewers.history': '历史',
  'flowViewers.duty': '职责',
  'flowViewers.capability': '能力',
  'flowViewers.tools': '工具',
  'flowViewers.systemPrompt': '系统提示词',
  'flowViewers.model': '模型',
  'flowViewers.teamDescription': '团队描述',
  'flowViewers.team': '团队',
  'flowViewers.extends': '继承 {name}',
  'flowViewers.sourceCustom': '自定义',
  'flowViewers.sourceInherited': '继承自 {name}',
  'flowViewers.sourceNone': '（无）',
  'flowViewers.empty': '（空）',
  'flowViewers.manager': '管理者',
  'flowViewers.teamRules': '团队规则',
  'flowViewers.inbox': '收件箱',
  'flowViewers.viewTeamDef': '查看团队定义',
  'flowViewers.removeFromQueue': '从队列移除',
  'agentManager.systemPrompt': '系统提示词',
  'agentManager.viewSource': '查看源码',
  'agentManager.viewRendered': '查看渲染',
  // prompt-panel 面板批（2026-10-09）：内置 nebflow 详情页提示词双面页签——
  // delegate 面（默认）+ node 面。本四键只管标注；正文来自 GET /api/agents/:name
  // 的 `promptFaces` 字段（判据单点 = BuiltinAgents.panelPromptFaces）。
  'agentManager.faceDelegate': 'Delegate 目标',
  'agentManager.faceNode': 'Node（flow 节点）',
  'agentManager.faceDelegateHint': 'Delegate 会话收到的系统提示词（delegate 终态语义）。',
  'agentManager.faceNodeHint': 'flow 节点会话收到的系统提示词（节点终态申报纪律）。',
  'explorer.newFile': '新建文件',
  'explorer.newFolder': '新建文件夹',
  // === ui-device-faces 批（2026-10-10）：面B 文件浏览器远端目录 ===
  // 远端根的加载行/取件三态/只读闸；文案对齐设计稿 §2。
  'explorer.loadingRemote': '正在加载 {device} 的目录…',
  'explorer.listTimeout': '目录列表超时（网络拥塞或连接中断），点击重试',
  'explorer.retry': '重试',
  'explorer.pickupLoading': '取件中…',
  'explorer.pickupFailed': '取件失败',
  'explorer.pickupClose': '关闭',
  'explorer.pickupCancel': '取消取件',
  'explorer.pickupStalled': '停滞 — 可取消',
  'explorer.remoteReadonly': '远端目录只读，写操作不可用。',
  // === picker-device-switch 批（2026-10-30）：文件浏览器设备切换钮 ===
  'explorer.deviceSelectAria': '切换浏览设备',
  'daemons.delete': '删除',
  // === 工作区选择卡 + 应用内目录浏览器（workspace-picker 批次，2026-09-05） ===
  'workspacePicker.pickTitle': '选择工作区',
  'workspacePicker.pickHint': '点击打开应用内目录浏览器（可逐级浏览、新建文件夹）',
  'workspacePicker.picking': '正在打开应用内目录浏览器…',
  'workspacePicker.pickedEcho': '已选择：{path}',
  'workspacePicker.browseTitle': '选择工作区',
  'workspacePicker.home': '主目录',
  'workspacePicker.up': '上级',
  'workspacePicker.newFolder': '新建文件夹',
  'workspacePicker.folderPlaceholder': '文件夹名称',
  'workspacePicker.create': '创建',
  'workspacePicker.selectHere': '选中此目录',
  'workspacePicker.cancel': '取消',
  'workspacePicker.loading': '加载中…',
  'workspacePicker.empty': '空目录',
  'workspacePicker.readFail': '无法读取目录',
  'workspacePicker.mkdirFail': '新建文件夹失败',
  // pickeruni batch: the retired project-directory picker's copy keys relocated
  // into workspacePicker.* (values verbatim unchanged)
  'workspacePicker.projectTitle': '选择项目目录',
  'workspacePicker.clear': '清除',
  'workspacePicker.gotoPlaceholder': '输入路径直达，如 ~/Downloads',
  'workspacePicker.go': '跳转',
  'workspacePicker.searchPlaceholder': '在此目录中搜索（服务端过滤）',
  'workspacePicker.noMatch': '无匹配「{query}」的子目录',
  'workspacePicker.truncated': '还有 {count} 项未显示（共 {total} 项）',
  'workspacePicker.hint.truncated': '可输入路径直达下方目录，或用搜索过滤',
  'workspacePicker.hint.empty': '可输入路径直达该目录，或回到上级',
  'workspacePicker.hint.noMatch': '清空搜索框可恢复全部；也可输入路径直达',
  'workspacePicker.error.invalid': '路径无效',
  // === ui-device-faces 批（2026-10-10）：选择器 device 模式（远端腿，只读） ===
  'workspacePicker.deviceTitle': '在 {device} 上选择目录',
  'workspacePicker.deviceNote': '远端目录只读 — 目录须已存在（缺件即报，不新建）。',
  // 设备共享白名单拒绝 = 预期可读态（2026-10-30 三修①「从家目录开始」）：给
  // 「未共享 + 出路」的本地化说明，替代对端原始英文报错。
  'workspacePicker.deviceShareDenied': '该设备未共享此目录——远端浏览只覆盖它已登记的项目工作区、captures 与 uploads。若需浏览家目录等其它位置，在那台设备的配置中加入 deviceShare.extraPaths 后重试。',
  // === picker-device-switch 批（2026-10-30）：选择器内设备切换行 ===
  // thisDevice = 花名册 self 名不可得时的回落显示（绝不直出「local」）；离线 tag /
  // 菜单脚注复用 project.deviceOfflineTag / project.deviceMenuFoot（先复用后新建）。
  'workspacePicker.thisDevice': '本机',
  'workspacePicker.deviceSelectAria': '选择浏览设备',

  // === 设备会话切服务端数据源，MVP-2（2026-09-15）：设备回执 ===
  // 🔴 `messages.deviceRetention`（P10 留存明示句）已删 —— 作者 2026-09-15 令 ⑤b
  // 「不要显示…这样的信息」⇒ 渲染点 / 双语键 / CSS 规则同批删除（禁死键）。
  'messages.deviceDescSaveFailed': '设备描述未保存。',
  'messages.deviceSent': '已送达',
  'messages.deviceRead': '已读',
  // === 好友 / 群回执槽位（R5 两格 · 源契约 `friend-group-receipt-source` v1.2）===
  // 🔴 本批**唯一新增文案**（2 枚，见报告「文案申报」）：契约 §1.1 逐字给出的气泡文案
  // `已读 {readCount}/{memberCount}`（群聊）+ 同一派生式 `deliveredCount` 的送达计数。
  // 直聊 / 设备面**零新增**（复用上方 `deviceSent` / `deviceRead` 两枚既有键）。
  'messages.receiptReadCount': '已读 {read}/{total}',
  'messages.receiptSentCount': '已送达 {sent}/{total}',
  'messages.deviceServerUnavailable': '服务端历史暂不可用 —— 已切换为本机历史。',
  'messages.deviceSendFailed': '消息未发出。内容未丢失，请重试。',
  'messages.deviceSendUnavailable': '本机设备身份不可用，暂不能发送。',

  // === 设备会话统一批 MVP-1（2026-09-15）：面板标题 / 设备段 / 设备会话行 ===
  // 键族归属：`panel.contacts` = 面板标题（值改「联系人和设备」，卡 §6.1）；
  // `contacts.*` = 设备段文案；`messages.*` = 会话列表设备行 chip。
  'contacts.sectionDevices': '设备',
  'contacts.devicesEmpty': '暂无其他设备',
  'messages.deviceTag': '设备',

  // === ctxthresh 批（Nebula 窗口上下文压缩阈值）：`ctxthresh.title`（环的
  // aria-label）已随 2026-10-23 上下文用量显示整链退役删除 —— 作者令逐字
  // 「把桌面端的上下文用量显示也去掉」。防复活钉：tests/remove-context-usage.spec.mjs。 ===

  // === Bundled-content localization (contenti18n batch, 2026-09-15) ===
  // Range = the default install set only (seed/manifest.json items: 3 plugins +
  // 4 agents = 7 entries). Keys are `content.<kind>.<id>.<field>`;
  // the resolver + fallback chain (locale → en → server value) lives in
  // js/contentI18n.js. zh values are the server values VERBATIM (no semantic
  // rewrite); en values are translations. Keep en/zh key sets symmetric —
  // scripts/verify-i18n-sweep.cjs asserts Object.keys(zh).length === Object.keys(en).length.
  'content.agent.project-dispatcher.name': 'project-dispatcher',
  'content.agent.project-dispatcher.desc': '项目任务分发器——负责把一批工作拆成若干可执行的节点、安排它们之间的先后与依赖，并在节点完成后汇总结果。',
  // P1-2 批登记项（2026-10-03）：下面两对键对应已退役的 general/kernel 名。
  // 本文件是**显示面字典**（contentI18n.js 自陈 DISPLAY-ONLY：服务端信号值原样
  // 保留，仅在客户端渲染点按 locale 取串），零进 LLM 上下文；且 builtin-merge 批
  // 后服务端不再产出这两个 agent id ⇒ 键零消费点。按「显示面/注释面登记并延后」
  // 口径保留，删除归注释/文本清理批（`content.agent.kernel.desc` 另引已删除的
  // `AgentCore.KernelFixedTools` 常量，同属该批）。
  'content.agent.general.name': 'general',
  'content.agent.general.desc': '通用执行 agent——能力由分配的 plugins 决定',
  'content.agent.kernel.name': 'kernel',
  'content.agent.kernel.desc': '极简内核（Delegate 内核）：主 Agent 经 Delegate 派发的一次性任务执行者——六件基础工具（Read/Write/Edit/Glob/Grep/Bash，均支持 device= 远端）+ AskUserQuestion，共七件；无项目上下文、无记忆、不派发子代理。用于「不属任何项目 ∧ 需实际执行动作 ∧ 单次」的任务。工具面为机制固定（AgentCore.KernelFixedTools = BaseTools + AskUserQuestion；本文件 tools/skills 声明因 ConvergedAgentNames 含 kernel 而整体失效）。',
  'content.plugin.visual-report.name': 'visual-report',
  'content.plugin.visual-report.desc': '可视化汇报与人读化报告包——节点获得用专业工具（matplotlib/graphviz/plotly）出图、把人读化报告写成给人读的一屏结论、落盘交付并给绝对路径的能力（展示与打开归主 Agent，不调用 Pop）。适用于画图表、画架构图、画流程图、数据可视化、出图配图、做可视化汇报、写给人看的汇报、交付文档、验收报告、调研报告、复盘报告、写给项目负责人的总结、报告太长要压缩、过程件与交付件分开等任务。内含 skill：visual-report（图表/架构图/流程图生成规范：配色、排版、标注、布局审美、单文档 ≤3 图预算；人读交付体例：四段骨架、体量预算；md 规则：图片嵌入、可跳转目录、LaTeX 公式）。不适用于插件封装（另配 nebflow-plugin-creator）；HTML 卡片样式另配 design-cards。组件面：无 mcp.json、无工具扩展。',
  'content.plugin.slideblocks.name': 'slideblocks',
  'content.plugin.slideblocks.desc': 'SlideBlocks 演示制作包——节点获得用 Slidev 从零制作/改进完整 PPT 演示（slides、deck、幻灯片）的全流程纪律。适用于做 PPT、写演示、出幻灯片、制作 slides、改进已有 deck、按 Slidev 重做演示等任务。内含 skill：slideblocks（Blocks/Decks/Recipes 全流程、执行锁、交付校验）。不适用于 HTML 卡片与社交图设计（另配 design-cards 插件）。组件面：无 mcp.json、无工具扩展。',
  'content.plugin.nebflow-plugin-creator.name': 'nebflow-plugin-creator',
  'content.plugin.nebflow-plugin-creator.desc': '插件封装器包——节点获得把能力需求封装为合规插件包的全流程执行能力（三输入分析→包生成→机械自检→落盘即生效的交付自检）。适用于把已有 skill 或能力封装成插件、做一个能 Y 的新插件、写 plugin.json 与 SKILL.md、插件描述质量体检、跑包校验脚本等任务。内含 skills：plugin-packaging（三输入→生成→自检→交付的封装主流程，含同名冲突与 digest 漂移等六分叉处置、交付前自检与来源/digest 报告口径）、description-quality（manifest 五段式描述规范：模板、好坏范例、M1-M15 判定口径与核心词表圈定法）。验证与质量审查任务另配 qa 插件。无 mcp.json、无工具扩展。',
  'content.project.general.name': 'general',
  'content.project.general.desc': 'General-purpose project for executing general tasks: any domain, one-off or recurring; 主 Agent 派发，节点执行。',

  // === Bundled-content localization — default-set SKILL descriptions
  // (skillsi18n batch, 2026-09-15; pure data, zero code change) ===
  // Range = the skills of the 3 default-install-set plugins (seed/manifest.json
  // items, 4 skills: visual-report/visual-report,
  // slideblocks/slideblocks, nebflow-plugin-creator/{description-quality,
  // plugin-packaging}). Key form = `content.skill.<plugin>/<skill>.desc`, i.e.
  // the skill id as PluginRegistry emits it (`s"$pname/${sd.last}"`), resolved
  // at the render point js/plugins.js:248. 3 entries carry a zh server value ⇒
  // zh side verbatim, en side translated; slideblocks/slideblocks carries an en
  // server value ⇒ en side verbatim, zh side translated (IO-A: the side that
  // owns the source language mirrors the server value verbatim).
  'content.skill.nebflow-plugin-creator/description-quality.desc': '{brand} 插件 manifest 描述规范的操作手册——description 五段式唯一描述源口径、触发词核心词表圈定法（全命中判定）、M1-M15 判定口径与四例好坏对照；适用于撰写或体检 plugin.json description 时逐条对照执行。',
  'content.skill.nebflow-plugin-creator/plugin-packaging.desc': '把能力需求封装为合规 {brand} 插件包的执行手册——三输入形态判定（既有 skill 迁移 / MCP server 配置 / 口头能力描述）→生成→机械自检→交付全流程，含六分叉处置与「落盘即生效」的交付口径；适用于节点被分配「封装插件 / 做插件」类任务时按本手册执行。',
  'content.skill.slideblocks/slideblocks.desc': '用 SlideBlocks 自主制作或改进完整、精良的 Slidev 演示——输入可以是一条很短的请求，也可以是 PDF、Word、网页、Markdown、表格、图片、品牌素材、PowerPoint 源 deck 等混合材料。适用于：用户想做一份演示；想迁移到 Slidev；想重新设计、扩充或改进演示；需要为报告、路演、评审、授课、发布、决策或演讲做一份完整 deck；想要 SlideBlocks 的 Blocks、Decks 或 Recipes；或直接给材料说「做成 PPT」。不适用于：要求的最终交付物是任何 PPT/PPTX 或 Office 文件、通用 Office 编辑、或非 Slidev 的演示运行时。',
  'content.skill.visual-report/visual-report.desc': '可视化汇报工具。用专业工具（matplotlib/graphviz/plotly 等）生成 SVG 图表，落盘后在交付文本里给绝对路径（展示权归主 Agent，节点不调用 Pop）。当需要制作图表、架构图、流程图、数据可视化、可视化报告时使用。',

  // === Social interface cards (socpanel batch, 2026-09-19) — pure append ===
  // Definition/data lives in js/socialChannels.js (one array drives order,
  // count and ids); this block must stay key-for-key equal to en.js
  // (tests/sidebar-restructure.spec.mjs + tests/settings-cleanup.spec.mjs).
  // 🔴 Phase 1 opens no real channel: every card's terminal state is
  //    「已配置 · 未接入」. The status closed set has no reachable "live" state.
  'social.title': '社交接口',
  'social.btn.title': '手机访问 — 社交接口配置',
  'social.remote.title': '手机远程访问',
  // The remote-access face is held: O1–O5 (switch semantics / QR code / address
  // export / mobile range / clipboard fallback) are author decisions.
  // 🔴 Keep this text free of the fake-connection vocabulary (W6).
  'social.remote.suspended': '手机远程访问暂未开放。',
  'social.remote.safetyKey': '安全边界：远程访问链接等同于访问凭据，请勿转发给他人。',
  'social.remote.safetyPlain': '明文 HTTP：局域网内不加密，仅限可信网络。',
  'social.action.save': '保存配置',
  'social.action.saving': '保存中…',
  'social.action.saved': '已保存',
  'social.action.saveFailed': '保存失败（{code}）',
  'social.action.notReady': '通道状态尚未载入——正在重连，请稍后重试',
  'social.action.recheck': '重新检查',
  'social.action.close': '关闭',
  // social-fix-confirm（作者指令 2026-10-09，§16 收录）：解绑二次确认——渠道名
  // 由 {name} 动态代入，确认钮文案复用各渠道既有 archive.action（「解除绑定」）。
  'social.archive.confirmTitle': '解除绑定',
  'social.archive.confirmMsg': '解除后需要重新扫码接入，确定解除「{name}」的绑定？',
  'social.secret.placeholder': '粘贴凭据（填入后不回显）',
  'social.secret.stored': '已写入 · 不回显',
  'social.secret.empty': '未写入',
  // social-fix (author ruling 2026-09-28): the mechanical probe line is
  // retired from the UI — the same probe face renders as two plain-language
  // states now (internal triples unchanged: data source untouched, render
  // layer only).
  'social.credential.missing': '凭据缺失，请重新扫码',
  'social.hint.missingRef': '引用文件不存在：{path}',
  'social.hint.modeBad': '权限不正确（应为 rw-------）：{path}',
  'social.hint.pattern': '字段格式不符：{field}',
  // 'social.hint.health' retired 2026-10-29（作者令：过程性自愈状态零用户面）—
  // seal: UserFaceProcessStateSealSpec.
  'social.status.notConfigured': '未配置',
  'social.status.configuredNotLinked': '已配置 · 未接入',
  'social.status.configInvalid': '配置有误',
  'social.status.connected': '已接入',
  'social.status.failed': '失败',
  'social.wechat.name': '微信',
  'social.wechat.desc': '微信服务号 / 订阅号的接口凭据。一期只做配置落盘，不建立任何对外通道。',
  'social.wechat.field.appId': '应用 ID',
  'social.wechat.field.appSecret': '应用密钥',
  'social.wechat.field.token': '消息校验 Token',
  'social.wechat.field.aesKey': '消息加解密密钥',
  // wechat-ilink（chain-wechat-impl，作者 2026-09-30 裁定 ② 选 (B)）：另立的
  // iLink 渠道 id，与上方公众号卡并存。weixin-scanbind（作者 2026-10-03）：
  // 卡片解封并加入扫码家族，下面补齐扫码/解绑两组文案（与 feishu 同形，
  // §16 收录）。
  'social.weixinIlink.name': '微信（iLink）',
  'social.weixinIlink.desc': '微信 iLink 机器人：扫码后凭据自动写入，消息进绑定会话。',
  'social.weixinIlink.field.botToken': 'Bot Token',
  'social.weixinIlink.field.ilinkBotId': '机器人 ID',
  'social.weixinIlink.field.ilinkUserId': '登录用户 ID',
  'social.weixinIlink.field.baseurl': 'API 基址',
  'social.weixinIlink.field.sidecarUrl': 'Side-car 地址（选填）',
  'social.weixinIlink.field.allowedIlinkUserIds': '发送者名单（可选）',
  'social.weixinIlink.scan.starting': '正在获取二维码…',
  'social.weixinIlink.scan.waiting': '请用微信扫一扫，并在手机上确认',
  'social.weixinIlink.scan.confirmed': '手机已确认，正在写入凭据…',
  'social.weixinIlink.scan.done': '机器人已登录，凭据已写入',
  'social.weixinIlink.scan.failed': '扫码绑定失败：{reason}',
  // scan-bind 403 族收口（2026-10-22）：401/403 不再误报 'io'，改说登录过期
  // （sessionGate 横幅 + 自愈 reload 随后接管）。
  'social.scan.authExpired': '登录已过期，正在重新进入控制台…',
  'social.weixinIlink.scan.userCode': '确认码：{code}',
  'social.weixinIlink.scan.qrFallback': '若二维码未显示，请在手机上打开此链接：',
  'social.weixinIlink.archive.action': '解除绑定',
  'social.weixinIlink.archive.doing': '解除绑定中…',
  'social.weixinIlink.archive.done': '已解除绑定 — 凭据保留',
  'social.weixinIlink.archive.hint': '解除绑定：停用通道，卡面恢复未创建态。凭据保留，不做任何删除。',
  'social.feishu.name': '飞书',
  // social-fix (2026-09-28): the manual-fill instruction is gone with the form.
  'social.feishu.desc': '绑定会话后，飞书消息进该会话并原路回发。租户内成员默认都可触达。',
  'social.feishu.field.appId': '应用 ID',
  'social.feishu.field.appSecret': '应用密钥',
  'social.feishu.field.verificationToken': '事件订阅 Token',
  'social.feishu.field.encryptKey': '加密密钥（选填）',
  'social.feishu.field.allowedOpenIds': '成员白名单（选填）',
  'social.feishu.field.region': '区域',
  'social.feishu.region.feishu': '飞书（中国）',
  'social.feishu.region.lark': 'Lark（国际）',
  // feiscanbind (2026-09-27): scan-to-create copy (§16-collected).
  // social-fix (2026-09-28): the QR block lives INSIDE the card — no
  // sub-dialog title, no scan action button, no manual fallback.
  'social.feishu.scan.starting': '正在获取二维码…',
  'social.feishu.scan.waiting': '请用飞书 App 扫码，并在手机上确认',
  'social.feishu.scan.confirmed': '手机已确认，正在创建应用…',
  'social.feishu.scan.done': '应用已创建并连接',
  'social.feishu.scan.failed': '扫码绑定失败：{reason}',
  'social.feishu.scan.userCode': '确认码：{code}',
  'social.feishu.scan.qrFallback': '若二维码未显示，请在手机上打开此链接：',
  'social.feishu.archive.action': '解除绑定',
  'social.feishu.archive.doing': '解除绑定中…',
  'social.feishu.archive.done': '已解除绑定 — 凭据保留',
  'social.feishu.archive.hint': '解除绑定：停用桥接，卡面恢复未创建态。凭据保留，不做任何删除。',
  'social.feishu.defaultSession.saved': '默认会话已保存',
  'social.feishu.defaultSession.saveFailed': '保存失败（{code}）',
  'social.telegram.name': 'Telegram',
  'social.telegram.desc': 'Telegram 机器人接口凭据。一期只做配置落盘，不建立任何对外通道。',
  'social.telegram.field.botToken': 'Bot Token',
  'social.telegram.field.chatId': '目标会话 ID',
  'social.telegram.field.apiBase': 'API 基址',
  // secrets-frontend（2026-09-27）：独立密钥弹窗（文案已收 §16 出件）。
  // 行内化（2026-10-09 07:42 拍板）：编辑器弹窗与 FAB 退役，一切操作在列表
  // 行内完成——值字段常显占位点（••••••），眼睛点击才经鉴权读取单条回显，
  // 回遮即清空回占位；列表与其他任何文案都不含值（secrets-readback 批
  // 2026-10-09 纪律不变）。
  'secrets.title': '密钥',
  'secrets.close': '关闭',
  'secrets.tailAdd': '＋ 新建密钥',
  'secrets.name': '名称',
  'secrets.nameHint': '小写「服务-用途」，例如 aliyun-oss',
  'secrets.value': '值',
  'secrets.dotsPlaceholder': '••••••',
  'secrets.showValue': '显示值',
  'secrets.hideValue': '隐藏值',
  'secrets.rowHint': '点击展开，原位编辑',
  'secrets.overwriteHint': '直接输入即覆盖当前值：Enter 或「保存」提交；点行外保存并收起；Esc 放弃并收起。',
  'secrets.valuePlaceholder': '粘贴值。保存后可在本面板查看与修改。',
  'secrets.delete': '删除',
  'secrets.save': '保存',
  'secrets.saved': '已保存 ✓',
  // 「权限过宽/仅所有者可读写」两态徽标键已随徽标概念退役（层级收进批，
  // 作者令 2026-11-01：红标整概念退役，en/zh 同批同删，键集两边一致）。
  'secrets.referencedBy': '被引用：{refs}',
  // 可查询批（作者令 2026-11-18 21:31）：说明 + 关键词标签 + 查询面文案。
  'secrets.searchPlaceholder': '搜索名称、说明、标签或消费方…',
  'secrets.description': '说明',
  'secrets.descriptionPlaceholder': '这个凭据是干什么用的？（可查询，值无关）',
  'secrets.tags': '标签',
  'secrets.tagsPlaceholder': '关键词标签，逗号分隔，例如 cloud, deploy',
  'secrets.path': '存放路径',
  'secrets.count': '共 {n} 个密钥',
  'secrets.countFiltered': '筛出 {n} / {total} 个密钥',
  'secrets.clearFilters': '清除筛选 ✕',
  'secrets.noMatch': '没有匹配的密钥。换个关键词或点「清除筛选」看全部。',
  'secrets.deleteTitle': '删除密钥',
  'secrets.deleteConfirm': '删除后不可恢复，确定删除「{name}」？',
  'secrets.deleteConfirmRefs': '「{name}」正被引用：{refs}。删除会导致它们失联，删除后不可恢复。',
  'secrets.empty': '还没有密钥。新建一个后，服务与助手都可按路径引用——值可随时在本面板查看与修改。',
  'secrets.invalidName': '只能用小写字母、数字和「.」「-」「_」，字母或数字开头，最长 64 字符。',
  'secrets.reservedName': '「social-」「daemon-」开头之名由各自面板管理。',
  'secrets.error.notFound': '该密钥已不存在——可能已在别处被删除。',
  'secrets.error.conflict': '密钥已存在，或仍被引用。请先解除引用再删除。',
  'secrets.error.emptyValue': '请先输入值。',
  'secrets.error.io': '密钥请求失败（{code}）。请检查网关连接后重试。',
  // === Phase 3b session rail: paste-ticket entry (ws.js stage B) ===
  'ws.ticketEntry.notify': '需要登录——点击登录按钮，通过 {lowerBrand} 账号继续',
  // 回合活动流条（activity.title/activity.count，对话层活性批 2026-10-22）已整链
  // 退役 —— 2026-10-23 作者令逐字「这个活动面板我不是让删掉吗，就是对话框上面
  // 这个」。防复活钉：tests/turn-activity-stream.spec.mjs。
  // === mobile-mockup-375 v3 实施批（20261020_214500 样张落地）===
  // profile 面板（扩展③：会话头头像入口 + 头像/名称便捷设置）
  'profile.title': '智能体',
  'profile.openAria': '智能体资料',
  'profile.closeAria': '关闭',
  'profile.upload': '上传头像',
  'profile.nameLabel': '名称',
  'profile.namePlaceholder': '输入名称，回车保存',
  'profile.note': '头像本地优先保存（按智能体各自记忆），保存后同步到本引擎的所有登录设备；名称回车即存（跨设备生效）。',
  // 任务浮层（v3⑤：二级液态任务面板）
  'taskpanel.title': '任务',
  'taskpanel.collapse': '收回',
  'taskpanel.stats': '{n} · {run} 运行中',
  'taskpanel.grpRunning': '运行中',
  'taskpanel.grpQueued': '排队',
  'taskpanel.empty': '暂无任务',
  // ── 待办列表面（ReminderIsland 复刻批 2026-11-02；词条 = ReminderIsland L10n 逐对迁）──
  'todoface.aria': '待办列表',
  'todoface.capsuleCount': '{n} 待办',
  'todoface.overdue': '{n} 逾期',
  'todoface.filter': '筛选',
  'todoface.collapse': '收起',
  'todoface.all': '全部',
  'todoface.add': '添加提醒',
  'todoface.done': '完成',
  'todoface.titlePh': '标题',
  'todoface.notesPh': '备注（可选）',
  // 两段化（待办结构化批 2026-11-05）：detail 折叠钮与编辑面字段名的可读标签。
  'todoface.detailToggle': '展开详情',
  'todoface.detailFieldLabel': '详情',
  'todoface.prioNone': '无',
  'todoface.prioLow': '低',
  'todoface.prioMed': '中',
  'todoface.prioHigh': '高',
  'todoface.hasDue': '有截止时间',
  'todoface.noDue': '无截止时间',
  'todoface.emptyAll': '暂无提醒',
  'todoface.emptyAdd': '点按添加提醒',
  'todoface.complete': '标记完成',
  'todoface.del': '删除',
  'todoface.fabAdd': '新建待办',
  // 新建抽屉（todo-fullbleed 批 2026-11-05：手机页面态新建入口改底部抽屉）
  'todoface.sheetTitle': '新建待办',
  'todoface.cancel': '取消',
  'todoface.completedToast': '已完成',
  'todoface.undo': '撤回',
  'todoface.tagName': '提醒标签',
  'todoface.tagNew': '新建标签',
  'todoface.tagRename': '重命名',
  'todoface.tagDelete': '删除',
  'todoface.tagDeleteTitle': '删除标签',
  'todoface.tagDeleteConfirm': '删除「{name}」后，该标签下的待办不再带此标签。此操作不可撤销。',
  'todoface.colorN': '颜色 {n}',
  // -- Mobile todo pages, Apple Reminders information architecture (entries
  // shared by the three pages) --
  'todoapple.aria': '待办',
  'todoapple.today': '今天',
  'todoapple.overdue': '逾期',
  'todoapple.all': '全部',
  'todoapple.completed': '已完成',
  'todoapple.myLists': '我的列表',
  'todoapple.allTodos': '全部待办',
  'todoapple.search': '搜索待办',
  'todoapple.searchPh': '搜索待办',
  'todoapple.searchResults': '搜索结果',
  'todoapple.searchEmpty': '没有匹配的待办',
  'todoapple.newList': '新建列表',
  'todoapple.newListPh': '新建列表',
  'todoapple.more': '更多',
  'todoapple.newTodo': '新待办',
  'todoapple.createUnconfirmed': '尚未确认保存，请检查连接并重试。为避免重复，请回到原会话重试。',
  'todoapple.discardPending': '放弃重试',
  'todoapple.replyFailed': '回复未发送，请检查主会话状态和连接后重试。',
  'todoapple.notes': '备注（可选）',
  'todoapple.save': '保存',
  'todoapple.markComplete': '标记完成',
  'todoapple.emptyDone': '全部完成',
  'todoapple.emptyCompleted': '还没有已完成的待办',
  'todoapple.info': '添加备注',
  'todoapple.toDue': '日期',
  'todoapple.toPriority': '旗标',
  'todoapple.toTag': '列表',
  'todoapple.noDue': '无日期',
  'todoapple.none': '无',
  'todoapple.dueTomorrow': '明天',
  // 快速决策视角（todo-quick-decision 批 2026-11-18）：入口钮名 + 卡位读数 +
  // 回答未接线的事实行（与列表页既有「决策 · 回答未接线」同一事实，措辞收敛为
  // 一处键；禁教学句，只写事实）。
  'todoapple.quickDecision': '快速决策',
  'todoapple.quickCount': '第 {n} / {m} 条',
  'todoface.tagSave': '保存',
  'todoface.tagErrEmpty': '名称不能为空',
  'todoface.tagErrDup': '已有同名标签',
  // 顶部悬浮「⋯」两级菜单（todo-mobile-topmenu 批 2026-11-06）
  'todoface.menu': '菜单',
  'todoface.menuView': '标签',
  'todoface.menuPrio': '重要度',
  'todoface.menuNoTags': '暂无标签',
  // 互动框（impl-row-reply 批 2026-11-06）
  'todoface.edit': '编辑',
  'todoface.replyPh': '回复这个待办…',
  'todoface.send': '发送',
  // ── 感知域（mail-perception-impl 批）──
  'perception.title': '感知',
  'perception.close': '关闭',
  'perception.footerPrivacy': '本地处理 · 邮件全文不出本机',
  'perception.mailEntry': '邮件',
  'perception.notConfigured': '尚未连接邮箱',
  'perception.setupTitle': '连接一个邮箱',
  'perception.setupDesc': '两步即成：填邮箱地址和授权码。新邮件自动推送——垃圾先滤掉，验证码到点即填。',
  'perception.connect': '连接',
  'perception.authFailTitle': '登录失败',
  'perception.authFailDesc': '授权码不正确或已过期，请重新输入。',
  'perception.reconnect': '重新连接',
  'perception.removeMailbox': '移除邮箱',
  'perception.backoffTitle': '网络不稳定',
  'perception.backoffDesc': '正在自动恢复，新邮件先存本机，恢复后即刻补上。',
  'perception.pausedBanner': '已暂停，邮件不会收取',
  'perception.quietLine': '新邮件自动交给代理处理，广告与垃圾已被过滤。',
  'perception.configBtn': '邮箱设置',
  'perception.toggleTitle': '暂停 / 恢复',
  'perception.cfgTitle': '连接邮箱',
  'perception.cfgSub': '只需邮箱地址与授权码，其余自动完成。',
  'perception.cfgAddress': '邮箱地址',
  'perception.cfgAuthCode': '授权码',
  'perception.cfgAuthCodeHint': '在邮箱设置里生成的专用密码，非登录密码',
  'perception.cfgAutoNote': '服务器与端口将按邮箱自动配置',
  'perception.cfgAutoResolved': '已识别 {addr}：将连接 {resolved}',
  'perception.cfgAutoUnknown': '未能自动识别该邮箱域 · 请展开「高级设置」手填收发服务器与端口',
  'perception.relayTimeout': '云端通道未在时限内收到本机应答（Host did not answer in time）· 连接可能已在后台完成，请展开感知面板查看状态；反复出现请检查本机网络与隧道',
  'perception.netFail': '网络请求失败 · 请确认本机控制台可达后重试 (network unreachable)',
  'perception.cfgAdvanced': '高级设置',
  'perception.cfgImapHost': '收信服务器 (IMAP)',
  'perception.cfgSmtpHost': '发信服务器 (SMTP)',
  'perception.cfgPort': '端口',
  'perception.cfgDisplayName': '发件人名称',
  'perception.cfgDisplayNamePh': '可选，显示在发出的邮件上',
  'perception.cfgTrusted': '信任的发件人',
  'perception.cfgTrustedPh': '可选，逗号分隔，命中才投递',
  'perception.cfgTrustedHint': '留空表示所有新邮件都进入感知',
  'perception.cancel': '取消',
  'perception.cfgNeedBoth': '请填写邮箱地址与授权码',
  'perception.cfgTesting': '正在连接…',
  'perception.cfgConnected': '已连接',
  'perception.cfgConnectedInbox': '已连接：收件箱 {n} 封',
  'perception.cfgConnectedStats': '已连接：收件箱 {n} 封、{u} 封未读',
  'perception.cfgFailed': '连接失败，请检查地址与授权码',
  'perception.sessionExpired': '登录状态已过期，正在重新进入控制台…',
  'perception.to': '收件人',
  'perception.cc': '抄送',
  'perception.bcc': '密送',
  'perception.subject': '主题',
  'perception.subjectPh': '主题',
  'perception.bodyPh': '正文…',
  'perception.send': '发送',
  'perception.sendAsIs': '照此发送',
  'perception.openCanvas': '在看板中编辑',
  'perception.abandon': '放弃',
  'perception.staleNote': '邮件已有更新，请回到看板确认后再发送。',
  'perception.sendFailed': '发送失败',
  'perception.draftGone': '草稿已不存在',
  'perception.newMail': '新邮件',
  'perception.receiptSent': '已发送',
  'perception.receiptFail': '发送失败',
  'perception.attachments': '附件',
  'perception.otpFill': '点击填入验证码',
  'perception.otpArrived': '验证码已到',
  'perception.otpSource': '新邮件',
  'perception.answerSent': '已照此发送',
  'perception.answerAbandoned': '已放弃本封草稿',
  'perception.abandoned': '已放弃，邮件不会发送',
  'perception.tbBold': '加粗',
  'perception.tbItalic': '斜体',
  'perception.tbUl': '无序列表',
  'perception.tbOl': '有序列表',
  'perception.tbQuote': '引用',
  'perception.tbLink': '链接',
  'perception.linkPrompt': '链接地址',
  'perception.removeAtt': '移除',
  'perception.attMissingNote': '有附件文件不存在，发送已停用；可在看板点 × 移除后重试。',
  'perception.removeAddr': '移除',
  'perception.toPh': '收件人地址，回车添加',
  'perception.addrPh': '输入地址，回车添加',
  'perception.ccBcc': '抄送/密送',
  // === schedule-created-card 批（2026-11-05）：消息内嵌定时任务/待办卡片 ===
  // 文案纪律（铁律 8 自解释）：只写状态事实与决策动词 —— 零能力说明、零路径
  // 指引、零行为教学。状态词一律「是什么」，不写「你可以…」。
  'schedCard.kindTask': '定时任务',
  'schedCard.kindTodo': '待办',
  'schedCard.statusActive': '进行中',
  'schedCard.statusWriting': '提交中',
  'schedCard.statusPaused': 'PAUSED',
  'schedCard.statusDone': '已完成',
  'schedCard.statusCancelled': '已取消',
  'schedCard.statusGone': '已不在列表',
  'schedCard.createdAt': '建 {at}',
  'schedCard.repeatOnce': '不重复',
  'schedCard.repeat.hourly': '每小时',
  'schedCard.repeat.daily': '每天',
  'schedCard.repeat.weekly': '每周',
  'schedCard.expand': '展开',
  'schedCard.collapse': '收起',
  'schedCard.edit': '编辑',
  'schedCard.cancel': '取消',
  'schedCard.save': '保存',
  'schedCard.saving': '保存中',
  'schedCard.discard': '放弃修改',
  'schedCard.fieldContent': '内容',
  'schedCard.fieldProject': '项目',
  'schedCard.fieldTriggerAt': '触发时间',
  'schedCard.fieldRepeat': '重复',
  'schedCard.projectPlaceholder': '项目标签（空 = 未分组）',
  'schedCard.confirmCancelAsk': '取消后不再触发。',
  'schedCard.confirmCancel': '确认取消',
  'schedCard.cancelling': '取消中',
  'schedCard.keep': '保留',
  'schedCard.noChange': '没有改动',
  'schedCard.readFailed': '引擎状态未读回',
  // 卡片点击腿（todo-card-click-left 批，2026-11-18）：整卡可点 ⇒ 补 `#` 待办引用。
  'schedCard.refHint': '点卡片补 # 待办引用',
  'schedCard.errOffline': '未连接引擎，改动未提交',
  'schedCard.errTimeout': '引擎未回执，改动未确认',
  'schedCard.errGone': '条目已不存在，改动未保存',
  'schedCard.errEngine': '引擎拒绝：{detail}',
  'schedCard.errGeneric': '写入失败，改动未保存',
  // ── profile-multidevice 批（单页资料 / 只读身份 / 设备清单含逐设备退出）──
  'profile.deviceSection': '设备',
  'profile.deviceCurrent': '本机',
  'profile.deviceSignOut': '退出',
  'profile.deviceSignOutCurrent': '退出本机登录',
  'profile.deviceOnline': '在线',
  'profile.deviceOffline': '离线',
  'profile.deviceLastSeen': '最近活动 {when}',
  'profile.deviceSeenNow': '刚刚',
  'profile.deviceSeenMinutes': '{n} 分钟前',
  'profile.deviceSeenHours': '{n} 小时前',
  'profile.deviceSeenDays': '{n} 天前',
  'profile.deviceNone': '账号服务暂未返回设备清单。',
  'profile.deviceSignOutQueued': '已向该设备发出退出请求；对端上是否生效以该设备自身登录态为准。',
  'profile.deviceSignOutFailed': '退出请求未送达，该设备登录态未改变。',
  'profile.deviceSignOutRemoteUnsupported': '该设备的凭据在其本机保存，本机无法代为退出；请到那台设备上退出登录。',
  'profile.editOnWebsite': '身份资料为只读读数；修改请前往官网账号页 {where}。',
  // profile-complete 批（作者令 2026-11-18「信息很多都没获取全…不许留未知而不解释」）：
  // 绑定读数的缺口说明，逐字段出句（zh/en 同键对称）。
  'profile.githubUnavailable': '账号服务未返回 GitHub 绑定用户名（该读数来自登录时下发的 GitHub 账号）。',
  'profile.googleUnavailable': 'Google 绑定只在官网账号页维护，本机账号服务与登录凭据都不含该读数。',
  'profile.signedIn': '已登录',

  // external-open 批（2026-11-19，作者令「支持外部文件拉取进 canvas 打开」）：
  // 外部文件 / URL 对话框的文案 + 服务端各拒因的**用户可读**说法（拒因词表
  // 与 ExternalOpenRoutes / ExternalOpenPolicy 的 reason 逐键对应）。
  'externalOpen.title': '打开外部文件',
  'externalOpen.hint': '粘贴本机任意可读文件的绝对路径，或一个 http(s) 链接；远程内容按内容嗅探类型后落盘再打开。',
  'externalOpen.placeholder': '/Users/…/报告.pdf 或 https://example.com/pic.png',
  'externalOpen.open': '打开',
  'externalOpen.cancel': '取消',
  'externalOpen.paste': '粘贴',
  'externalOpen.busy': '正在打开…',
  'externalOpen.empty': '请先粘贴一个绝对路径或链接。',
  'externalOpen.clipboardEmpty': '剪贴板里没有文本。',
  'externalOpen.clipboardBlocked': '浏览器不允许直接读剪贴板；请在输入框里按 ⌘V / Ctrl+V 粘贴。',
  'externalOpen.failed': '没能打开：{msg}',
  'externalOpen.err.credentialPath': '这是凭据位置或凭据形态的文件（~/.ssh、~/.aws、secrets 子树、auth.json、*.pem 等）——本入口一律拒绝。',
  'externalOpen.err.credentialHardlink': '这个文件与凭据文件是同一个 inode（硬链接），本入口拒绝。',
  'externalOpen.err.credentialRequired': '登录状态已失效，请重新登录后再试。',
  'externalOpen.err.privateAddress': '这个链接指向本机 / 私网 / 链路本地 / 元数据地址，出于安全考虑拒绝拉取。',
  'externalOpen.err.scheme': '只允许 http 与 https 链接。',
  'externalOpen.err.host': '这个链接没有可用的主机名。',
  'externalOpen.err.malformed': '这不是一个合法的链接。',
  'externalOpen.err.unresolved': '这个域名解析不出任何地址。',
  'externalOpen.err.redirects': '跳转次数超过上限（跳去私网的跳转同样会被拒绝）。',
  'externalOpen.err.tooLarge': '文件超过本入口的大小上限。',
  'externalOpen.err.timeout': '拉取超时（连接或总时限）。',
  'externalOpen.err.fetchFailed': '拉取失败（对端返回错误或连接中断）。',
  'externalOpen.err.notFound': '这个路径下没有可读的普通文件。',
  'externalOpen.err.notAbsolute': '请给出绝对路径（以 / 开头，或 ~/ 开头）。',
};

