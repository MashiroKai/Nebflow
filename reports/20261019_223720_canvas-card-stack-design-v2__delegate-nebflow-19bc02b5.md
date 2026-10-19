# Canvas 面板 · 标签行改叠卡 · 设计 demo 报告 v2（作者试用反馈六条修订）

- 上位文档：`reports/20261019_220500_canvas-card-stack-design__delegate-nebflow-19bc02b5.md`（v1 设计报告，现状盘点 / 实施落点 / 开放问题仍有效，本篇只写增量）。
- v1 样张：`reports/20261019_220500_canvas-card-stack-mockup__delegate-nebflow-19bc02b5.html`（已并入 main）。
- **v2 样张：`reports/20261019_223720_canvas-card-stack-mockup-v2__delegate-nebflow-19bc02b5.html`**（自包含，双击浏览器直接打开；暗亮切按钮页右上；面板宽度 720/500/340 三档可切）。
- 本批仍为**纯 demo，零源码改动**；实施批落点备忘见 v1 报告 §三，v2 无变更（浮钮挂面板层原叉位、deck 层挂 `#canvas-content` 内）。
- 交付注：本批次两文件直写主仓 `reports/`（延续 delegate 会话的原 worktree 已随 v1 合并回收，无分支可挂），当前为未跟踪态，归档提交由调度方处理（同 `0f2f2ee0a` 先例）。

## 一、v1 → v2 修订对照（作者试用反馈六条）

源码内所有修订点均带 `[v2①]`…`[v2⑥]` 注释标签，可按标签检索 diff。

| # | 反馈 | v1 | v2 |
| --- | --- | --- | --- |
| ① | 常态头去掉叉号，层叠钮改浮钮 | 头行右侧嵌层叠圆钮 + 面板关闭叉 | 头行**只剩居中标题**，关闭叉整个移除（常态全 UI 无叉）；层叠钮改 **40px 悬浮浮钮**：absolute `top:10 right:12` 跨压头线右上（原叉位置域），逐字对齐产品 `.canvas-source-toggle`（split.css :870）浮钮语言——rest opacity 0.6 / hover 1、玻璃白底细描边；z-60 高于叠卡层，叠卡态仍可见可点、sapphire 激活态（`aria-pressed`） |
| ② | 进堆叠带上当前卡 | `pos = i + 2.3` 从深处「发牌」翻入，当前卡虽是卡组一员但观感是「从旁边看卡组」 | 进入时 `pos = target = 当前卡`：**当前展示的卡原地成为堆叠前卡**（sapphire 环 + 「当前」徽标、置顶在前）；其余卡以 spread 0→1 **从当前卡背后展开**成食谱卡盒；退出反向收回（spread→0）。当前卡就是卡组一员且在前 |
| ③ | 堆叠卡顶部是名称 | 名称条在卡身底部（thumb flex:1 在上） | 卡结构翻转：**顶部名称横条**（38px：`当前`徽标 + 类型图标 + 脏点 + 文件名 ellipsis + 面板标 + ×，底缘 hairline）+ 下方预览区 |
| ④ | 卡片尺寸自适应占满 | 写死 `min(390px, 76%) × 252px`，窄档另写 88%×208 | `layoutCards()` 量 `deck-stage` 实际可用面积：宽 −36px、高 −34px 留边后**尽量占满**，写入 `--card-w/--card-h`；`sizeK = cardH/252` 把级联位移/纵深按卡高等比缩放（任意尺寸观感一致）；面板宽度切换 / window resize 即重算。窄档不再需要尺寸覆写，只收排版（提示行隐藏、标题截断） |
| ⑤ | 滚轮联动动画（连续 scrub） | 阈值步进：acc≥90 才整数步进 ±1 格（翻页感） | **滚多少动多少**：wheel 增量（deltaMode 归一：line×40 / page×100）× 增益 `1/240` 直接累加进浮点位置 target（**连续量**，一格 ≈ 0.4-0.5 张卡）；rAF 指数趋近 `pos += (target−pos)·(1−e^(−10dt))` 平滑跟随；向上滚**回滚**；无阈值、无整格约束。键盘 ↑↓ 同通路 ±1 |
| ⑥ | 堆叠态关卡双路 | 悬停浮出右上关闭钮 / 右键菜单 | **划走**：按住前卡左右拖，跟手位移 + rotateZ 侧倾；松手位移 ≥ 卡宽 20% 或速度 ≥ 0.55px/ms → 沿拖行方向**甩出**（外抛 + 13° 侧旋 + 淡出，0.3s）并关闭；不足阈 → **弹回**（spring 0.22s）。**点 ×**：顶条右端 ×（前卡常显）点击收拢淡出关闭。两路均走 `settleClose` 邻位接管（右邻优先，同现状 `closeTab`）。叉只存在于堆叠卡顶条 |

## 二、v2 交互细节裁定（实施批注意）

- **拖划与点击的界分**：pointerdown 起位移 <6px 判为点击（前卡=选中、后卡=翻到该卡），≥6px 进入拖划且该次松手不再触发选中；× 按钮不参与拖划（`closest('.card-close')` 直通 click）。`touch-action: none` + pointer capture，触屏/触控板同通路。
- **关闭进行中的互斥**：`exitFx` 活跃时滚轮 / 键盘 / 空白点击 / 浮钮 / 二次关闭全部忽略，防止半程退场卡被状态切换复活成满透明度鬼影；退场完成才执行 `settleClose`（重建卡组 + `pos=target=邻位`）。
- **展开度 spread** 是独立于 pos 的第二运动轴：只缩放非 front 卡的位姿（front 恒为全显），进入/退出 = spread 0↔1，与擦洗互不干扰——`prefers-reduced-motion` 下双轴插值系数置 1（位置直达，仅留淡入淡出）。
- **浮钮与卡顶条 × 不冲突**：浮钮悬在头线上（面板坐标系），卡顶条 × 在卡内顶条右端（stage 坐标系，卡顶起点在头线之下），几何上无叠压；浮钮 z-60 > deck 层 z-40，叠卡态浮钮压在卡组上方仍可点（再点收起）。
- 面板手动关闭入口随常态叉一并移除（作者①明令常态无叉）：demo 中面板收起仅由「关掉全部卡」触发；实施批如需保留收起入口，建议另行安放（右键面板头 / 快捷键），不在本 demo 范围。

## 三、保留不变（v1 → v2 未动）

环形取模首尾循环、点后卡翻到该卡、点击选中飞入标题位、右键菜单（引用当前页/选区 / 关闭 / 关闭其他，承接 `showCanvasRefMenu`）、ESC/点空白退出、单卡/空态/全关收面板 + 重开浮钮、亮暗双主题 token 镜像、脏标记绿点语义、缩略 CSS mock 族、`overscroll-behavior: contain` 防滚链。

## 四、自验记录（Playwright，本地 chromium，21 断言全 PASS）

脚本断言 21 条全 PASS、console 零错误；截图 9 张（亮/暗 × 常态/叠卡/擦洗中/拖划中/选中后/右键菜单/窄档）覆盖亮暗双档。

| 断言 | 结果 |
| --- | --- |
| ① `.canvas-header button` 计数=0、常态面板全域无叉钮；`#deck-fab` absolute 40px、rest opacity 0.6、底缘跨压头线（fab.bottom 254 > header.bottom 250） | PASS |
| ② `openDeck()` 后 `pos=0=activeIdx`、spread→0.996+（收束中）；前卡含 `is-active` + 「当前」徽标 | PASS |
| ③ 前卡首个子块 `.card-top`（h=38）位于卡顶（top 条 y=257 < thumb y=307），文件名在条内 | PASS |
| ④ 720 档 card 682×327 ≥ stage 718×361 −40；切 340 档重算 card 302×348 ≥ stage 338×382 −40（占满） | PASS |
| ⑤ wheel 300px → target=1.25（连续非整数格）；中途 pos=0.827 平滑跟随；pos 收敛至 target；反向 −150px → target 0.625（可回滚） | PASS |
| ⑥ 小幅拖 20px → spring 弹回 tab 数不变；拖 260px 松手 → 甩出关闭 9→8、邻位接管 activeId=README.md；顶条 × 点击 8→7；全关 → 面板收起 + 重开浮钮 | PASS |
| 键盘 ArrowDown + Enter 选中回常态，头名 = 激活卡名（package.json）；右键菜单可开（引用/关闭/关闭其他） | PASS |
| `prefers-reduced-motion` 下过渡时长 0.01s | PASS |

**过程中被断言拦下的三处实施 bug（已修）**：
1. `layoutCards()` 原先在 `layer.hidden=false` 之前调用——hidden 时 `clientWidth=0` 被守卫早退，卡尺寸永远停在缺省值；改为先显层再量。
2. `buildCards()` 重写时丢了卡片级 `contextmenu` 监听——右键菜单整条失效，断言「右键菜单保留」FAIL 后补回（含后卡点按翻到该卡分支）。
3. 拖划不足阈时原实现直接清 drag 状态——卡瞬移弹回无过渡；补 `mode:'spring'` 弹回曲线（0.22s easeOut）。

**环境注**：无头 chromium rAF 实测 ~36fps（30 帧/831ms），指数趋近的有效收敛时间按比例拉长——自动化等待需放宽（≥1.6s）；真实浏览器 60fps 无此现象，非 demo 代码问题。

## 五、开放问题（继承 v1 §四，无新增裁决）

拖拽排序安放、preview/pinned 表达、面板/文件分堆、滚轮自然滚动方向——仍待作者拍板；v2 反馈未涉及，默认按 v1 文案执行。
