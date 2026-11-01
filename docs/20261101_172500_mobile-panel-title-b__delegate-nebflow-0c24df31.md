---
type: batch
status: done
related: "[[20261031_160300_mobile-fab-unify-search]]"
---

# 手机端面板顶部标识 B 批（2026-11-01，iOS 大标题式）

> 选型（作者 11-01 17:01）：五方案[[glossary#^mockup-sample|样张]]（`~/.stand/design-explore/panel-label-variants/`，作者令 16:42「每个面板顶部的这个标识有点丑而且很小…设计几种方案供我选择，简约一点」实探产出）**B 方案「iOS 大标题式」**拍板——读 B 条目 + token 实测账本 + B_LIGHT/B_DARK 两态对照实施。任务令四件：①旧「奶片」胶囊（mobileLayer.css 悬浮钮统一批 L391-416）退役换 B 两态，28px 破「页面级大标题 20px」档为作者既定选型（标注留档不回退）；②全部手机端收编/下钻面板统一过一遍（盘点清单见 §4）；③token 全用样张账本现役值，零新 token，深浅双主题成立；④与卡片家族/玻璃包等已并 main 新语言协调，用旧胶囊的一并换新。

## 1. 现状考古

- 旧标识 = `.mobile-page-title`（navController.js buildSection 建，mobileLayer.css 样式）：悬浮 glass-control 胶囊（白 60% 底 + 白 90% 亮边 + blur16/sat1.2 + 内高光/底缘 + 0 1px 3px glow，13px/600，带内居中 top 28px+safe）——「容器重量与字量倒挂」病灶与样张 README 诊断一致。
- 标识只存在于**详情页壳**（PAGES 非 dock 页）；dock 四根页（消息/联系人/看板/工作台）顶栏已于手机顶栏批（作者令 10-31 ①）整体退役，无标识可换。
- 收编面滚动模型：页面体（.mobile-page-body）只在流内内容面滚动；pane/overlay 收编面（team-scroll、#settings-content、todo-page-body 等）**各自内滚**——样张「大标题在流内随滚走」在真机上分两型落地（§3 滚型）。
- 面内同名双标题存量：todo/.todo-page-title（待办事项）、usage/#usage-title（token 用量）、settings/#settings-modal-title（设置，i18n zh 回填）、daemon/#daemon-panel-title（心跳）——与壳层标识逐字同名，奶片时代就是「浮标+面头」双显。

## 2. 改动面

- `js/mobile/navController.js`（buildSection ∪ locale 重绘 ∪ 新探针）：
  - 展开态大标题改为**页体流内首件**（`.mobile-page-title` 类名不变 → locale 重绘与 push 聚焦两个既有查询点零改；tabIndex -1 读屏播报承接）；
  - 收缩态两件挂页头：`.mobile-page-title-mini`（15px/600 居中，aria-hidden 装饰副本）+ `.mobile-page-title-band`（顶缘渐隐带）；`mobile-page-head-spacer` 随胶囊退役（创建与样式同批摘除）；
  - **滚动探针** `bindRollProbe(section)`：section 捕获相位监听（scroll 不冒泡但走捕获，页内一切滚区零逐面接线），主滚轴 = 事件目标到 section 之间**最外层**纵向可滚容器（overflowY auto/scroll + 真实余量 >4px）——内层横滑条/嵌套小滚区不误触发不误复位；`scrollTop > 56` 切 `.rolled`（样张迷你比例 36px → 真机 390 宽换算）。
- `css/mobileLayer.css`：
  - L391 胶囊块整块改写为 B 两态：大标题 28px/600 · 字距 -0.01em · 左对齐 padding 6px 16px 10px（28px = 作者令有意破档，**仅限手机端面板头，不回流桌面与卡片标题**——留档于此，不回退）；胶囊玻璃材质（底/边/blur/三重影）整族退役；收缩态小字（top 40px+safe = 48px 返回钮中心）+ 渐隐带（`var(--g2-veil)` + `calc(var(--g2-fade-height) + safe)`，#g2-edge-fade 现役 token，深浅主题自随）；两态 160ms `var(--ease-std)`；z 序带 4 < 小字 5 < 返回钮 6（header 20 层内）；
  - 收编面统一页面化几何块 `height:100% → auto`（大标题流内首件后 face 靠 `flex:1 1 auto` 吃余量；height:100% 会把 face 顶出页体造成假滚动）；
  - 文件尾批次块：#todo-page 同维补丁（height:auto；注：todo 基座 overflow:hidden ⇒ min-height:auto 归零，实为内滚型面，同 settings 滚型）＋ 面内同名双标题退役四件（todo/usage/settings/daemon，桌面 DOM 零感知）＋ prefers-reduced-motion 两态过渡压 0.01ms；
  - 文件头批次注记补条。
- 零新 token（全部现役值：--g2-veil/--g2-fade-height/--dur-2/--ease-std/--color-frame-text-bright）、零裸色值、桌面零触碰（规则全挂 body.mobile-layer；navController 本就是手机层专属控制器）。

## 3. 两态与滚型

- **展开态**：大标题即版面（首屏吃高约 72+52px，样张取舍明文接受）；随内容流滚走（流内滚型页真滚走；内滚型面页由探针切态后位移+淡出让位，余量由渐隐带承底读作「栏区」）。
- **收缩态**：15px/600 小字居中淡入 + 顶缘渐隐带承底（页底色→透明，滚入内容「先糊后没」同语言）。
- **滚型两分**（探针自适应，零逐面配置）：①内滚型（pane/overlay 收编面：projects/graph/plugins/settings/usage/tasks/search/reminder/todo/daemon/background/profile/conversation）＝面内主滚轴驱动，大标题位移+淡出收缩；②流内型（me 四页、settings-sub 四子页）＝页面体自身滚动，大标题随滚自然走。两型探针同一套断言覆盖。

## 4. 面板盘点清单（任务令②；标识文案 = 各面板现名）

B 两态覆盖 19 面详情页壳（全部沿用 PAGES 现名 zh/en）：

| # | 页面 id | 现名 | 内容面 | 滚型 | 面内同名标题处置 |
|---|---|---|---|---|---|
| 1 | projects | 项目 | 桌面画板 pane（team-scroll） | 内滚 | 面内无标题 |
| 2 | graph | 图谱 | pane（wiki-graph） | 内滚 | 面内无标题 |
| 3 | plugins | 插件 | pane | 内滚 | 面内无标题 |
| 4 | settings | 设置 | #settings-modal | 内滚 | **退役**（#settings-modal-title） |
| 5 | usage | token 用量 | #usage-dashboard | 内滚 | **退役**（#usage-title） |
| 6 | tasks | 任务 | .mh-tpanel | 内滚 | 面头=icon+统计+×（无标题文本） |
| 7 | search | 搜索 | #search-modal | 内滚 | 面内「搜索消息」异名，保留 |
| 8 | reminder | 定时任务 | #reminder-panel | 内滚 | 面内 "Scheduled Tasks" EN 原文，保留（存量申报） |
| 9 | todo | 待办事项 | #todo-page | 内滚 | **退役**（.todo-page-title；计数/新建/关闭钮全留） |
| 10 | daemon | 心跳 | #daemon-panel | 内滚 | **退役**（#daemon-panel-title） |
| 11 | background | 后台任务 | #bg-dropdown | 内滚 | 面内 "Background tasks" EN 原文，保留（存量申报） |
| 12 | profile | 智能体 | #memory-modal | 内滚 | 面内 "Memory" 异名，保留 |
| 13 | conversation | 会话 | .fm-modal | 内滚 | 面头=联系人名（异语义信息），保留 |
| 14-17 | me / me-user / me-avatar / me-bind | 个人主页/用户名/头像/绑定 | meFace 流内面 | 流内 | 面内无标题 |
| 18-21 | settings-sub 四子页 | STT 高级设置/旧工具结果清理/Jev（插件分配）/生图 | 设置折叠体收编 | 流内 | 面内无标题 |

**不入 B**：dock 四根页（messages 消息 / contacts 联系人 / **canvas 看板** / workbench 工作台）——手机顶栏批（作者令 10-31 ①「手机端各页顶部标题文字全去掉」）明判根页无标题、内容上移吃回空间，该令在先且今日任务令对象为「收编/下钻面板」；任务令枚举中的「看板」= canvas 根页，按在先作者令维持无标识态，**未加 B 头**（如需根页标识另立批令，壳层加法一行）。任务令④「谁在用旧胶囊的一并换新」清查：胶囊形态标识全站仅 `.mobile-page-title` 一处（dock 徽标/搜索条/筛选 chip 等其余 999px 圆角件均为功能控件非标识），无混血残留。

## 5. 验证实证

- `tests/mobile-panel-title.spec.mjs`（新建，pop-footer-family 同款自包含 harness：route 静态 + WS 夹具 + /api 夹具，零后端零 sbt）：**全腿 ALL PASS / 零 console error**——
  - 390×844 手机视口 × **chromium/webkit × light/dark** 四腿 × 三面板（项目/设置/待办事项）× 两态；
  - 展开态探针：position:static（奶片 absolute 退役）、28px/600、字距 -0.28px（=-0.01em）、墨色=--color-frame-text-bright、收缩小字/渐隐带 opacity 0、渐隐带=--g2-veil 渐变、返回钮在场；
  - 阈值探针：主滚轴 scrollTop 40（<56）不切态 / 120（>56）切 .rolled，收缩态小字/带 opacity 1、大标题 opacity 0、pointer-events:none；
  - 真滚轮交互：mouse.wheel 下滚切态、回顶展开态复现（projects=team-scroll / settings=settings-content / todo=todo-page-body 三条主滚轴各证）；
  - 面内同名标题退役断言（display:none）+ 设置面滚动域夹具充足断言；
  - 双态实拍 24 张（4 腿 × 3 面 × 2 态）；
  - 桌面零触碰腿（1280×800）：body.mobile-layer 不激活、页面壳/标题节点 0、#header 在场。
- 证据：`evidence/20261101_093045_mobile-panel-title/`（shots 24 张；TS 为 UTC 计时）。
- 批前合并：main（cdb2fa2c9，Pop footer 批）已真实并入本分支 fast-forward。

## 6. 边界与申报

- 28px 破档：作者既定选型（仅限手机端面板头），批注留档于 mobileLayer.css B 形态块与文件头批次注记，不回退。
- 看板（canvas 根页）未加 B 头：依据 §4 在先作者令申报；如作者要根页标识，另行小批。
- 面内异名头三处 EN 原文（Scheduled Tasks / Background tasks / Memory）与搜索面「搜索消息」为收编面存量既有显示（奶片时代即浮标+面头双排），非同名双显不在本批退役面——如实申报，等后续内容面批统一裁。
- reduced-motion：两态过渡压 0.01ms（形态切换瞬时成立）。
- 遗留观察（非本批）：pane/overlay 面内滚型在收缩后大标题占位（~52px）保留为「栏区」由渐隐带承底——与 iOS collapsed 导航栏同构，为有意形态。
