# 偏差声明 — mobile-mockup-375 v3 落地 + 三扩展（实施批）

样张基准 = `reports/20261020_214500_mobile-mockup-375-v3__delegate-nebflow-74eb4637.html`（作者已批方向）。
本批分支 = `delegate/delegate-nebflow-0417c2ca`。以下为**与样张的已知偏差**与**实现取舍**，
逐条给出理由；无偏差项不在列。

## A. 布局 / 键位

- **左列 8 槽 vs 样张 6 键**：样张左列画 6 枚子钮；本批按任务书「键位映射对齐真实客户端」
  落 FLOAT_IDS 六键 + 任务键 + 待答（0 计数键 hidden 退场，列高只数可见键）。静态截图
  环境里记忆功能关闭（config '{}'）⇒ 可见 4 键（任务/搜索/定时/后台任务域）；生产按
  功能开关动态 5-7 键。样张是示意排版，不是键位清单。
- **dd 头钮徽章**：样张头钮右上有数字角标（会话功能计数）；本批未做 —— 头钮无对应
  数据面（任务计数在任务键徽章上，与桌面任务面板同源）。需要时后补，属加法。
- **右列 6 键去聊天**：任务书口径（活动栏七键去聊天），样张右列 6 键一致，无偏差；
  按下态 .active 镜像 navController（样张静态稿无此态）。

## B. 任务浮层

- **卡点击 = L1 跳转**（样张为静态稿无交互）：与桌面任务面板行同语义
  （openDelegateChainTask），外接/排队卡 display-only。
- **浮层高度 `min(560px, dvh-130)`**：样张 560px 定值；小屏（<690px 视口）自适应收
  高、列表内滚。375×812 档与样张一致。
- **已完成组**：样张有「已完成」分组；桌面面板不显示终态行（既有口径），浮层按同一
  读取口（listDelegateRows 终态未归档）补出该组 —— 呈现层补组，数据面单一权威不变。

## C. profile / 会话头

- **名称持久化 = 写穿既有 display-name 机制**（state.rootDisplayName + agentsData +
  `nebflow-root-name-change` 事件），非服务端新端点 —— 任务书「名称就地编辑回车保存」
  按既有机制接线；账号级服务端持久化属登录域，未动。
- **头像 local-first**：localStorage dataURL（`key('agent_avatar_<agent>')`，镜像
  avatarCache.js 键位纪律）；「登录后可随账号同步」为面板文案既有口径，本批未接账号
  同步链（无该端点）。
- **桌面会话头**：头像 40px 居中 + 名称在下（`.header-center` 纵排）；桌面顶栏其余
  簇（模型信息/右工具簇）不动 —— 样张只画了会话头本体。

## D. 附件 / 走廊

- **附件 +N 折叠**：样张为静态展开；本批做溢出折叠（+N 常驻行尾，点开横向滚动再点
  收拢）。折叠计数按行宽实时计算（syncAttachmentRow 重写为纯读算目标 + diff 应用，
  原「先重置再重折」在 MutationObserver 下会自激打满微任务队列 —— 实测页面冻结，
  已修）。视口更宽时全量显示，与样张展开态一致。

## E. 交互细节

- **morph 起始几何 = 触发圆钮中心 40×40**：样张弹层自钮「原地生长」；FLIP 实现取
  origin 中心对齐（列内键/头像钮几何恒定，视觉等价）。
- **reduced-motion**：全部形变/过渡压到 ≤0.01ms（A10 口径）—— 样张无此态。
- **scrim 只收聊天区空白点按**：顶栏控件（两列/头像/名称）在幕上保持可点（z 39 <
  #header 40/46），样张语义同。

## F. 测试基线（非本批缺陷，已核证）

- `sidebar-restructure` T1/T3、`activity-bar-smoke` A1/A3/A4、`smoke` cookie-blocked
  腿在 **clean main（0f2f2ee0a）同样失败**（wiki-graph 合并后的 spec 漂移 / 环境腿），
  非本批引入；本批不越界代修。
- `wiki-graph-ux` R8 的 r4 面（#mobile-bar-graph）随 v3③ 走廊退役 —— spec 已对位更新
  为 v3 右列 graph face（被测意图不变：移动视口不自动开侧栏抽屉）。

## G. 截图环境注记

- 证据截图 = 静态 route harness（真实 js/css/locale + MockWebSocket + agent.json
  displayName 种子「笨笨」+ delegate 12 卡夹具经 `delegateChainStub.__setFixture`）。
- 记忆键在 harness 不可见 = 记忆功能随空 config 关闭（同 A 条）。
