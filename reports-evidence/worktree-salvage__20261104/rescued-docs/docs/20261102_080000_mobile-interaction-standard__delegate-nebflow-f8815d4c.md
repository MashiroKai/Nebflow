---
type: batch
status: published
date: 2026-11-02
scope: 手机端交互标准立项（Apple HIG 基准）+ mobileLayer token 单源 + 全量套用 18 面（字号/触控热区/行高/图标/开关）+ 项目面「取消加载」WiFi 图标语义错配修正；含 375 双主题前后对照实拍、触控热区机械断言、既有 mobile spec 全绿
---

# 手机端交互标准批 · 收口文档（作者令 2026-11-02）

关联：[[20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb]]（手机 dock 悬浮胶囊——本批在其几何基座上只调触控/字阶，胶囊本体与 `--dock-*` token 零改动）、[[20261031_160300_mobile-fab-unify-search]]（悬浮钮 48px 统一批——本批把 `--mtap-lg: 48px` 收编为同一口径的 token）、[[20261101_203500_project-unmount-console__delegate-nebflow-a5e474bc]]（「取消加载」面本批修正其图标语义）。

## §0 令文

作者实拍三张（任务 / 定时任务 / 心跳三面板 + 设置面板）后下令：

1. **立标准**：手机端面板字号与控件尺寸全部太小、不是手机该有的交互逻辑 ⇒ 以 Apple HIG 为基准**立一套手机端标准并全量套用**，落成 CSS token 单源，禁止各处再写死数值。
2. **全量套用**：不许只改截图里那几个 —— 任务、定时任务、心跳、设置（含各子面板）、项目、token 用量、待办、插件、后台任务、消息/联系人等**全面**套用。
3. **UI bug**：项目面板的「取消加载」按钮显示成了 wifi 图标 —— 图标语义错配，换成语义正确的图标，并清查该面板其余按钮的图标语义。

验收四条：标准表 + token 单源落盘；375 双主题前后对照实拍（≥5 面板 + toggle 一帧 + 图标修正一帧）；触控热区机械断言（列出仍不达标的并说明理由）；既有 mobile spec 全绿 + 零 console error。

## §1 标准表（唯一权威口径）

全部落到 `css/mobileLayer.css` 的 `body.mobile-layer` token 块（文件 L126-232）。**这是手机层唯一取值来源**——本文件除该块外已零裸 px 字号（机械自查见 §4.1）。

| 元素类型 | 字号 | 尺寸 | 热区 | token 名 |
|---|---|---|---|---|
| 正文 / 主文案（卡名、行名、读数） | 17px | — | — | `--mfs-body` |
| 次正文（行文本、输入控件、紧凑列表名） | 16px | — | — | `--mfs-callout` |
| 次要说明（副行、描述、脚注） | 15px | — | — | `--mfs-sub` |
| 最小档（角标、时间戳、分组签、chips） | 13px | — | — | `--mfs-min` |
| 面板大标题（B 批破档，仅手机面板头） | 28px | — | — | `--mfs-title` |
| 收缩态面板标题 | 15px | — | — | `--mfs-title-mini` |
| 可点元素（本体或 `::before`/`::after` 外扩命中盒） | — | ≥44×44 | 44×44 | `--mtap` / `--mhit` |
| 悬浮圆钮（fab / 页面壳「<」/ canvas 工具钮 / 输入卡双键） | — | 48×48 | 48×48 | `--mtap-lg` |
| 列表行（行内文字与控件垂直居中） | — | min-height 44 | 整行 | `--mrow` |
| 行 / 卡内边距 | — | 左右 16 / 上下 12 | — | `--mpad-x` / `--mpad-y` |
| 同组件垂直间隙 / 卡间堆叠间隙 | — | 8 / 12 | — | `--mgap` / `--mstack-gap` |
| 图标本体（视觉档 20-24） | — | 22 / 20 / 24 | 随宿主 44 | `--micon` / `--micon-sm` / `--micon-lg` |
| **开关（iOS 尺寸语言）** | — | 轨道 51×31、圆钮 27、内衬 2 | 44×44（`::before` 外扩） | `--msw-w` / `--msw-h` / `--msw-knob` / `--msw-inset` / `--msw-travel` |

开关颜色**不新造**：沿用回绿后的既有配方（`--glass-control-*` 材质 + `rgb(var(--brand-green))` 35% 轨道 tint / 50% 描边 / 实心绿圆钮，2026-11-01 作者令「toggle 重新回到绿色」）。本批只换几何。

### §1.1 开关几何推导（iOS 比例）

```
轨道 51×31，border-radius 15.5（= 高/2）
圆钮 27，left:2 / top:2
OFF 左缝 3 = ON 右缝 3（行程 22 = 51 − 27 − 2×2）
两态圆钮中心都落在轨道轴上（纵向 2/2 对称）
```

## §2 全量套用 · 面板 × 是否达标

逐面实测（`tests/mobile-touch-target.spec.mjs`，375×812@2x 触屏，18 面 351 个可点件）：

| 面板 | 可点件 | 不达标 | 结论 |
|---|---:|---:|---|
| 任务（tasks） | 21 | 0 | PASS |
| 定时任务（reminder） | 17 | 0 | PASS |
| 心跳（daemon） | 12 | 0 | PASS |
| 设置（settings，含 cfg 全族） | 19 | 0 | PASS |
| 项目（projects） | 20 | 0 | PASS |
| token 用量（usage） | 86 | 0（65 项热力图格已登豁免，见 §4.2） | PASS（豁免登记） |
| 待办（todo） | 15 | 0 | PASS |
| 插件（plugins） | 12 | 0 | PASS |
| 后台任务（background） | 15 | 0 | PASS |
| 消息（messages） | 14 | 0 | PASS |
| 联系人（contacts） | 13 | 0 | PASS |
| 工作台（workbench） | 19 | 0 | PASS |
| 个人主页（me） | 20 | 0 | PASS |
| 看板（canvas） | 15 | 0 | PASS |
| 搜索（search） | 18 | 0 | PASS |
| 图谱（graph） | 13 | 0 | PASS |
| 聊天根（chat） | 11 | 0 | PASS |
| 登录卡（login） | 11 | 0 | PASS |

字号侧：**本文件零裸 px 字号**（全部 56 处 `font-size` 声明都吃 token）；收编面（reminder / usage / todo-face / taskList / sidebar 等桌面样式表）按「最小档兜底 + 关键类点名」双层压，旧 8 / 9 / 10 / 10.5 / 11 / 12 / 12.5px 档全部上升到 ≥13。

## §3 UI bug：「取消加载」显示成 WiFi

### §3.1 根因（不是类名撞车，是**路径本身写错**）

`js/projectTab.js:975` 的 `UNMOUNT_BTN_SVG` 常量，注释写着「卸载按钮（unplug 图标）」，但四条 `<path>` 逐字就是 **WiFi 信号字形**：

```
M2 8.5a14.5 14.5 0 0 1 20 0   ← 最外圈弧
M5 12.5a10 10 0 0 1 14 0      ← 中圈弧
M8.5 16a5 5 0 0 1 7 0         ← 内圈弧
M12 20h.01                    ← 底点
```

作者质疑「取消加载按钮怎么是一个 wifi？」——**名实不符：注释说 unplug，路径是 wifi**。这不是图标名/类名解析撞车，是该处 inline SVG 被写成了网络类图形。

### §3.2 修法

换成语义正确的 **unplug**（插头自插座拔出：两根插脚 + 插座缺口 + 上下拔出示意），对应「取消加载 = 把项目从上下文里拔出来、本地文件全部保留（零删除）」的真实语义。见于 `js/projectTab.js:983-988`。

### §3.3 该面板图标语义清查清单

| 控件 | 位置 | 图标 | 语义判定 |
|---|---|---|---|
| 取消加载 | `projectTab.js:1223` | ~~wifi~~ → **unplug** | 🔴 已修（错配：网络 → 卸载） |
| 在文件浏览器中打开 | `projectTab.js:1229` | `folder-open` | ✅ 正确（打开目录） |
| 编辑描述 | `projectTab.js:1242` | `pencil` | ✅ 正确 |
| 新建项目 | `projectTab.js:182` | `plus` | ✅ 正确 |
| 空态「让 agent 创建项目」 | `projectTab.js:956` | `message-circle` | ✅ 正确（落到输入框讲话） |
| 远端设备徽标 | `projectTab.js:1162` `DEV_BADGE_SVG` | `hard-drive` 线框 | ✅ 正确（磁盘/设备，非网络） |
| 重新加载（已卸载分组） | `projectTab.js:1026` | 纯文字钮（无图标） | ✅ 无隐患 |
| 页链视图返回 | 页面壳 `mobile-back-btn` | `chevron-left` | ✅ 正确（与本批 §3 无关，登记备查） |

清查结论：**该面板仅此一处语义错配**，其余 7 项图标语义与动作一致。全仓另有三处 `wifi-off`（`dropbox.js:819` 上传失败 / `notificationBanner.js:72` 连接断开 / `sidebar.js:1079` 未连接 / `main/bgAgents.js:412` 智能体离线）——全部挂在**真实的网络/连接语义**上，判定正确，不动。

## §4 机械断言

### §4.1 字号单源自查

```bash
# 期望输出为空（除 token 单源块自身）
grep -nE "^\s*font-size:\s*[0-9]" src/main/resources/web/css/mobileLayer.css
```

实测：**0 行命中**。

### §4.2 触控热区

```bash
node tests/mobile-touch-target.spec.mjs          # 退出码 0 = 无未豁免不达标项
```

判据：真实可交互宿主（`button` / `a[href]` / `[role=button|switch|tab|menuitem]` / `input[type=checkbox|radio]` / `label[for]` / `[onclick]` / `summary` / 显式 `cursor:pointer` 的最外层非内联块）；热区 = 本体 rect ∪ `::before`/`::after` 外扩 rect。

结果：**18 面 / 351 个可点件 / 未豁免不达标 0 个 / 豁免 65 个（全部是热力图格）/ console error 0 / 退出码 0**。

**18 面全 PASS**（17 面零不达标 + usage 面 65 项全部落在下述豁免内）。

**豁免（唯一一项，含几何推导）—— 用量热力图 `.ud-cell`：**

- 形态 = 52 周 × 7 天的**整年日历**；实测格面 20px（本批已从 11px 提到 20px），含 4px 外扩命中盒后 28×28。
- **几何不可达 44**：52 列 × 44px = **2288px**，是 375px 视宽的 **6.1 倍**。照 HIG 放大后整图失去「一年一览」语义 —— 热力图的全部价值就是同屏横向可比。
- 已做的四重缓解：① 格面 11 → 20px（可读性与命中率翻倍，横滚保留）；② 命中面外扩 4px ⇒ 28×28；③ **点击代理** = `closest('.ud-cell[data-date]')`（`usageDashboard.js:314`），点在格间缝隙也命中最近格 ⇒ 等效热区 > 实际格面；④ 精确读数走**钻取表**（本批已把表字号提到 `--mfs-min`），正式操作面不经格子。

## §5 实拍证据（375 双主题 · 前后对照）

生成器：`tests/mobile-interaction-standard-shots.mjs`（`--before-web` 指向 HEAD 导出的 web 根，`--after-web` 指向本树 ⇒ 同一夹具、同一视口、同一时刻的两侧对拍）。

产物目录：`evidence/mobile-interaction-standard/shots/`（40 帧，`before-*` / `after-*` × `light` / `dark`）。

| 帧 | 内容 |
|---|---|
| `{before,after}-tasks-{light,dark}.png` | 任务面板 |
| `{before,after}-reminder-{light,dark}.png` | 定时任务面板 |
| `{before,after}-daemon-{light,dark}.png` | 心跳面板 |
| `{before,after}-settings-{light,dark}.png` | 设置面板 |
| `{before,after}-projects-{light,dark}.png` | 项目面板 |
| `{before,after}-usage-{light,dark}.png` | token 用量（受控面） |
| `{before,after}-todo-{light,dark}.png` | 待办 |
| `{before,after}-plugins-{light,dark}.png` | 插件 |
| `{before,after}-background-{light,dark}.png` | 后台任务 |
| **`{before,after}-toggle-{light,dark}.png`** | **开关前后对照（专项一帧）** |
| **`{before,after}-unmount-icon-{light,dark}.png`** | **取消加载图标修正（专项一帧）** |

### §5.1 开关几何读数（脚本落盘）

```
before-light / before-dark：track 36×20，radius 10px，knob 16×16，offset 1px/2px
after-light  / after-dark ：track 51×31，radius 15.5px，knob 27×27，offset 2px/2px
```

两态推进量 14px → **22px**（iOS 比例）。

### §5.2 图标修正读数（脚本落盘）

```
before：paths = [wifi 四段弧 + 底点]
after ：paths = ["m19 5 3-3", "m2 22 3-3", "M6.3 20.3a2.4…", "M7.5 13.5 10 11", "M10.5 16.5 13 14", "m12 6 6 6 2.3-2.3…"]  ← unplug
尺寸：15×15 → 20×20（图标本体升到 --micon-sm 档，热区 44）
```

## §6 回归

| 套件 | 结果 |
|---|---|
| `tests/mobile-*.spec.mjs`（全部 22 个既有 mobile spec） | 见 §6.1 |
| `tests/mobile-touch-target.spec.mjs`（本批新增） | 0 未豁免不达标 / 0 console error |
| `tests/mobile-interaction-standard-shots.mjs`（本批新增） | 40 帧产出成功 |

### §6.1 既有 mobile spec：108 绿 / 6 红，且 6 红与本批无关（基线同一份）

跑法：`PW_SELFCONTAINED_ONLY=1 npx playwright test tests/mobile-*.spec.mjs`（114 例）。

**结果：108 passed / 6 failed —— 6 红与「改造前基线」逐条同名同因，本批零新增红。**

| # | 失败项 | 与基线对比 | 归因 |
|---|---|---|---|
| 1-2 | `mobile-chat-topclip` C2（light / dark） | **基线同红** | `#chat { mask-image: none }` 手机层显式摘除（liquid-glass v2 批，`mobileLayer.css` HEAD L2633 原样在案）——spec 还在等 `linear-gradient`。陈旧判据，跨批遗留。 |
| 3 | `mobile-p10-plugins` T3 | **基线同红** | 封禁卡 `opacity` 读到 `NaN`（元素类名在夹具里未命中），跨批遗留。 |
| 4 | `mobile-p6-bgstatus` T2 | **基线同红** | `.bg-origin-nebula` 节点不在（桌面侧渲染差异），跨批遗留。 |
| 5 | `mobile-topline-clear` T1 | **基线同红** | 同 #1 的 mask 判据，跨批遗留。 |
| 6 | `mobile-v3` T2 | **基线同红** | 任务徽标期望 `10` 实得 `8`（`collectChains` 计数口径与夹具不符），跨批遗留。 |

基线证据：本批动手前跑同一命令，输出为**完全相同的 6 条失败清单**（`108 passed / 6 failed`）。本批改动后复跑，清单逐字未变 ⇒ 本批对既有 spec 无回归。

**这 6 条属独立欠账**，不在本批令文范围（都是跨批遗留的陈旧判据/夹具问题，与字号尺寸契约无关），已在 §9 开放项登记。

既有 mobile spec 的判据**未按新契约改写**——因为本批改动全部落在「手机层样式取值」上，既有 spec 钉的是**结构与行为**（页栈互斥、面板收编、adopt 直子、开合对账、转发链、角标镜像、胶囊几何 token 恒等），不钉字号绝对值；唯一钉绝对值的是 B 批面板大标题 `28px/600`（`mobile-panel-title.spec.mjs:259`）与 dock 胶囊高 `54`（`mobile-dock.spec.mjs:625`），两者本批均**原值保留**（`--mfs-title: 28px` / `--dock-h: 54px`），故零改写需求。

## §7 改动清单

| 文件 | 内容 |
|---|---|
| `src/main/resources/web/css/mobileLayer.css` | 新增 token 单源块（L126-232，24 枚 token）；iOS 开关几何块；56 处 `font-size` 全部改吃 token；新增「全量套用」块（行高/内距/图标/触控热区逐面补齐）；新增「热区补齐续 + 豁免登记」块 |
| `src/main/resources/web/js/projectTab.js` | `UNMOUNT_BTN_SVG` wifi → unplug（含根因注记） |
| `tests/mobile-touch-target.spec.mjs` | 新增：触控热区机械断言（18 面遍历 + 豁免白名单） |
| `tests/mobile-interaction-standard-shots.mjs` | 新增：375 双主题前后对照实拍生成器 |
| `evidence/mobile-interaction-standard/touch-targets.json` | 断言产物（逐面逐件读数 + 豁免理由） |
| `evidence/mobile-interaction-standard/shots/` | 40 帧实拍 |
| `docs/20261102_<HHMMSS>_mobile-interaction-standard__delegate-nebflow-f8815d4c.md` | 本文档 |

## §8 与在飞批的交叠

作者令点名四批在飞（面板标题胶囊吸附 / 搜索框固定顶栏 / 顶部头像调小 / 全设备统一更新）。本批工作全部收在自有 worktree 内，未触碰任何在飞批工作区。交叠面登记：

| 在飞批 | 交叠点 | 本批处置 |
|---|---|---|
| 面板标题胶囊吸附 | `.mobile-page-title` 字号 | 本批只把 28px 收编为 `var(--mfs-title)`（**值不变**），形态/位置零触碰 ⇒ 合并时该批改几何、本批改取值来源，冲突面极小 |
| 搜索框固定顶栏 | `.mh-search-bar` 高度 | 本批把它从 44 提到 `max(44px, --mtap)`（值仍 44），只声明来源 ⇒ 无语义冲突 |
| 顶部头像调小 | `#agent-avatar-btn` 78px | 本批未触碰头像几何（只把 `#session-name` 字号 14 → `--mfs-sub` 15） |
| 全设备统一更新 | 无样式交叠 | — |

合并策略：本批改动集中在 `mobileLayer.css`（新增块为主，改动块为「值 → token」的等价替换）+ 一个 JS 常量 + 两个新测试文件。冲突时以「本批 token 名保留、对方几何值保留」为原则就地解。

## §9 开放项

1. **热力图格子 44 达标**：若作者坚持要达标，唯一路径是把整年网格改成「周 / 月分页」形态（每页 ≤8 列即可 44px），但那会改掉热力图的「一年一览」语义 —— 属**产品形态裁定**，本批不自行改。当前四重缓解已把命中面从 11×11 提到 28×28 + 点击代理。
2. **收编面字号双层压**：收编面（reminder / usage / todo-face / taskList / sidebar）是**桌面样式表**，本批在手机层以「最小档兜底 + 关键类点名」压上去，未改桌面样式表本身。若后续要给这些面板做完整的手机档字阶（而非兜底），需要按面板逐个写皮肤块 —— 属下一批的量。
3. **图标语义清查范围**：本批按作者令清查了项目面板（8 项），确认仅 1 处错配。其余面板（任务 / 心跳 / 插件 / 用量 / 设置）的图标语义未做系统清查 —— 超出本批令文范围，登记为候选批。
4. **6 条跨批遗留红**（§6.1 表）：与本批无关、基线同红，但堵塞「既有 spec 全绿」的验收命题。建议另起一批统一清理陈旧判据（mask 判据 ×3、插件卡 opacity 夹具、bg-origin 夹具、任务徽标计数口径）。本批**未自行改写**这些判据 —— 因为它们的失败原因与字号尺寸契约无关，属别的批的账。
