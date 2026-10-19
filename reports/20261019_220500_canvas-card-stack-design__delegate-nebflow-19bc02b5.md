# Canvas 面板 · 标签行改叠卡 · 设计 demo 报告

- 作者令：优化 canvas 设计——浏览器式标签页改为叠卡（选食谱卡）模式；文件名居中；右侧加一枚按钮，点击进入叠卡模式，滚轮上下翻动，点击选中。
- 本批为 **纯 demo，零源码改动**；批后另开实施批。
- 可交互样张：`reports/20261019_220500_canvas-card-stack-mockup__delegate-nebflow-19bc02b5.html`（双击浏览器直接打开；暗主亮切按钮在页右上；面板宽度 720/500/340 三档可切）。

## 一、现状盘点（能力面，demo 设计的约束来源）

| 现状（canvas.js / split.css） | 事实 |
| --- | --- |
| 标签行 | `#canvas-tab-bar`（index.html :360），`.canvas-tab` + `.canvas-tab-label` + `.canvas-tab-close`；active 类切显 |
| 脏标记 | `.canvas-tab-label.dirty::before` 圆点，色 `--color-primary`（split.css :863）；状态源 = Monaco `editor-dirty-change`（canvas.js :1186） |
| preview / pinned | VS Code 式预览签替换（`previewTabs`，canvas.js :56）+ 双击 pin（:1250） |
| 拖拽排序 | 标签条内 drag reorder（canvas.js :187-276），并携带 nebflow-ref 拖到输入栏 |
| 右键菜单 | 「引用当前页/选区」`showCanvasRefMenu`（canvas.js :155），面板类页签 toast `canvas.noRef` |
| 关闭语义 | 邻位接管（右邻优先），关最后一个收起面板（canvas.js :641-696） |
| 面板形态 | 侧靠 / overlay（窄窗）双形态，`body.canvas-open`（canvas.js :331）；头部高度参与 overlay 顶缘推导 |
| 面钮基准 | `.canvas-source-toggle`：40px 圆、`--glass-control-*` 白底细描边（split.css :870）——新叠卡钮同语言 |

## 二、demo 方案

### ① 常态头（标签行退役）
- 当前文件名**绝对居中**（不随左右按钮组浮动）：类型图标 + 名称 + 脏标记绿点（沿用 `--color-primary` 语义）；长名截断。
- 右侧按钮组：**叠卡圆钮**（新）+ 既有面板收起钮。叠卡钮 = 面钮同款玻璃语言（白底细描边、图标线重 1.8、三层叠卡隐喻图标）；deck 打开时呈 sapphire 激活态（`aria-pressed`）。
- 空态：标题「画板」、叠卡钮置灰。

### ② 叠卡选择模式
- **进入**：点右侧圆钮。透视 1150px、消失点置于上沿 → 食谱卡盒形态：前卡全显，后卡向上露边渐退（22px 级联 + 渐小渐暗），已翻过的卡向下翻落。
- **滚轮**：向下 = 下一张翻上来（带 rotateX 弧线 + rAF 指数趋近的液态惯性）；deltaMode 归一 + 阈值步进（触控板小步合并、鼠标一格一翻）；连滚多步自然堆积成甩动。
- **选中**：点击前卡 → 该卡向常态头标题位收拢飞入，其余卡向后翻回，标题交叉淡入。
- **退出**：ESC / 点空白；`↑↓←→` 翻动、`Enter` 选中。
- **卡面**：类型图标 + 文件名 + 脏点 + 预览缩略暗示（CSS mock；实施时文本文件可用首行、图片用 objectUrl 真缩略）+ 当前文件 sapphire 环与「当前」徽标。
- **关闭安放（方案）**：主案 = 悬停前卡浮现右上关闭钮，邻位接管语义同现状 `closeTab`；扩展案 = 卡片右键菜单「引用当前页/选区 / 关闭 / 关闭其他」，承接 `showCanvasRefMenu` 能力面。demo 两案均可操作。

### ③ 边界
- 文件很多：卡组位置环形取模（最短路径绕回），首尾无缝循环（demo 9 张可滚一圈）。
- 当前文件：进叠卡即置顶为前卡（高亮环 + 徽标），选中即回归常态头。
- 窄面板（<400px）：卡宽 88%、高度收窄、副信息/提示行隐藏、标题截断，交互不变。
- 单卡 / 空态 / `prefers-reduced-motion`（惯性系数置 1、仅留淡入淡出）均已处理。

## 三、实施落点备忘（给实施批）
- `#canvas-tab-bar` → `.canvas-title` 常态头；deck 层挂 `#canvas-content` 内（absolute inset 0，不遮头部；`overscroll-behavior: contain` 防滚链）。
- 数据源 = 既有 `tabs` Map（顺序即卡序）；dirty 复用 `isTabDirty()`；引用复用 `refForTab()`；关闭接管同 `closeTab`；`canvas-tab-closed/switched` 事件与 `activeCanvasPane`（Ctrl+F 路由）零影响。
- **OS 文件拖到标签条即开新签**（canvasdrop R6④）：标签条退役后需把 header 整条注册为替代落区。
- 滚轮实现 = `wheel` 事件 + transform 透视栈，**不需要 3D 库**（demo 控制台已打印同款提示）。

## 四、开放问题（请作者拍板）
1. **拖拽排序**：叠卡模式内不做拖拽（滚轮/点击已覆盖切换）；是否接受「卡序 = 打开顺序」、或后续在卡上加长按拖动？
2. **preview / 双击 pin**：预览态在卡面如何表达（斜体名或「预览」角标）？双击 pin 语义迁到卡上还是收敛掉？
3. **类型过滤**：面板页签（Flow Map/任务看板等）与文件同堆；是否需要「文件 / 面板」筛选或分堆？
4. **滚轮方向**：demo 按口谕「下 = 下一张」；是否需跟随系统自然滚动设置反向。

## 五、自验记录（Playwright，本地 chromium）
- 状态机：开 → 滚轮 +1（一格一翻）→ 点前卡选中（activeId 切换、面板回常态）→ ESC 退出，`window.__demo.state()` 全部符合预期；console 零错误。
- 截图 9 张覆盖：亮/暗 × 常态/叠卡/翻动中/选中后、窄档 340、暗档右键菜单；暗亮双档 token 镜像 sapphire.css。
- 过程修复：`.deck-layer[hidden]` 被 `display:flex` 类规则压制导致退场不隐藏（UA hidden 语义失效）→ 补 `[hidden]{display:none}`；滚轮阈值 42→90（鼠标一格一步）。
