---
name: frontend-verification
description: 前端自验与验证方法论——实现侧无后端 smoke harness + data-* 断言契约、wire-probe 侦查法、contract-test-harness、分层验证、Playwright 路由/定位坑、静态 serve 验收、结构化盲点补验。Use when 前端改动需要自验/交付 qa、调查"功能没接通"类 bug、验证 WS/REST 契约、或静态资源可达性/嵌套路径排查。
audience: nebflow-project
language: zh
status: active
last_verified: 2026-09-02
---

# Frontend Verification — 前端自验与验证方法论

把「能自动化验证的」从 QA 的手工黑盒前移到实现侧自验。核心信念：**mock 只验证你对帧形状的假设，真实后端才会发出不一样的东西**；断言契约要与视觉文案/i18n 解耦。

## 1. 实现侧自建无后端 smoke harness

实现完成后、交付 qa 前，自跑断言：

- Playwright `page.route` 拦截 mock REST/WS，以 repo 静态文件为被测面
- 在结果行 DOM 上暴露 `data-*` 属性（data-ts/data-kind 等）作为二值断言契约——断言与视觉文案/i18n 解耦，QA 可直接复用种子与 mock 手法，打回率显著降低
- 复用同一 MockWebSocket + view 脚手架（勿自造驱动时序：`window.__api.init({configured:false,...})`、skip 后等待渲染、选项文本大小写）
- **harness 与实现共享注入源**（冻结类 UI 验收）：live-import 真实 i18n/CSS/模块（而非 mock 副本），用 app 自己的状态后门进入状态（`classList.add('frozen')+dataset.frozen`，而非内部 JS 函数），视觉断言转成**数值关系**（解析后 alpha 比较、frozen vs normal 的 scrollWidth delta、相等颜色来自同一 var()）——不拍快照不硬编码值；「零新增 token」「雾晕层级」「嵌入式时间格式」等规范文本变成可判定断言（A9 雾晕 alpha < bar alpha、A12 拒收任何原生蓝色 token）。测试与待测物耦合而非与检测方式耦合：文案/CSS token 只定义一次，布局敏感性被增量测量中和
- **MockWebSocket 注入**：`addInitScript` 注入 `readyState=1` + app 注册后调 `onopen()`，用 `onmessage({data: JSON.stringify(msg)})` 驱动、断言捕获的 `send()` 帧 + 真实 DOM 交互。能抓静态审查漏掉的回归（Save 按钮藏在 `display:none` 编辑器内 → 用户无法保存/重置草稿——动作行必须移出可折叠编辑器，仅由 `draft !== null` 门控）

## 2. wire-probe-harness 侦查法（"功能没接通"类 bug）

调查接线 bug 时先写 wire 层探针，不读源码推演：

- `routeWebSocket` 拦截 + 捕获出站帧 + 服务器注入事件，在真实浏览器走用户路径断言帧形状
- 源码各段「看起来已接」，但一次性守卫（`_inputBound`）+ 每次重建的弹窗 DOM 这类跨状态 bug 只有重开/事件序列才能暴露
- 一次 harness 可产出多项侦查结论（路径缺陷 + 状态粒度缺口）

## 3. contract-test-harness（API client 契约）

mock gateway 断言**双面正确性**——发送面与接受面：

- wire-shape：请求 method/path/body/Authorization header
- 响应字段/类型保留：数字 id、epoch-seconds 而非 ISO 字符串（Number→String 类型强转静默通过，不显式 pin 必漏）
- 失败事件派发：403→fm-auth-required、network→fm-network-error
- fixture 用确定性分支：`today-at-09:30` epoch 打同一天分支，不要断言通用格式

## 4. 分层验证顺序

JS+CSS+i18n+WS 跨层改动按层验证，每层抓不同失败类，防 E2E 调试实际是语法错误：

1. `node --check` 循环全 touched JS
2. `checkJs` 基线对比
3. Playwright mock WS 模拟 serverConfig/frozen/resumed 帧验验收标准
4. 必要时隔离实例真实 E2E（见 isolated-smoke-verification skill）

## 5. Playwright 高频坑

- **route 注册顺序**：catch-all 先注册、specific 后注册——Playwright 反向匹配（后注册者优先），`**/api/**` 后注册会 shadow 具体路由
- **多题共用 class 定位**：按可见性过滤 `[...inputs].find(i => getComputedStyle(i).display !== 'none')`，不用 querySelector 首个（隐藏 Other 槽会误中前题、答案串题、症状延后两步才现）
- **computed style 与关键字过滤矛盾**：按 `selectorText.includes('activity-btn')` 过滤 stylesheet 只看到 muted 规则，但 computed 颜色来自别处——停止 substring 过滤，枚举**每个匹配该元素的选择器**（正确拆逗号列表、按 cascade 顺序遍历 sheets），看哪个规则真正设置了属性；泛化选择器（`#activity-bar button`、`[data-lucide]`、`svg` 规则，ID 特异性压过 class）才是真实覆盖源。`i[data-lucide]` 替换后颜色规则可能针对 `i`/svg 而非按钮自身 class，更易漏
- **去重断言 per-phase**：按用户动作边界分阶段断言重复请求=0；跨阶段合法重复（重新锚定=新用户意图）不算
- **浮层挂进裁剪容器 = DOM 全绿、屏幕不见**（#392 popover 教训）：断言软浮层（menupop/dialog/toast）只查 `hidden===false` / `getComputedStyle(display)!=='none'` 会假绿——`#activity-bar` 是 `overflow:hidden` 的圆角玻璃卡且带 `backdrop-filter`，后者使该元素成为**所有 fixed 后代的包含块**，浮层会一起被裁掉（48px 一条）。硬断言三连：①`getBoundingClientRect()` 有宽高且 `right<=innerWidth && bottom<=innerHeight`（在视口内）②`elementFromPoint(中心)` 命中浮层自身（真渲染在顶层，不是被别的元素盖住）③若浮层应超出承载容器，其 `left >= 容器.right`（确认没被包含块夹住）。修法：浮层挂 **body** 级、JS 按钮锚定 + 视口夹取，别用承载容器的 absolute 定位。同类：任何「浮在滚动容器/圆角卡/带 blur 容器之上」的组件。
- **元素替换保留 class**（lucide `<i class=foo>`→`<svg class=foo>`）：用元素限定 `svg.foo` 而非后代 `.foo svg`

## 6. 静态 serve 验收 / 资源路径

- JVM 实例重启窗口不可得时：`python http.server` 指 worktree 的 `src/main/resources/web`，harness 用 `window.__api` stub 双向帧 + 计算样式断言替代视觉截图；curl 字节对比确认 serve 的是目标 commit 非旧缓存
- **served-resource 验证**：curl 管道 `grep -c` 返回 0 **不是**修复缺失的证据（引号管道模式/缓存/boot 顺序都是 fluke 源）——用**字节数对比**（`wc -c` served 文件 == worktree 源文件）+ 一个唯一 marker token 的 grep 双重确认；且 health 200 ≠ 所有 web 路由已 warm，**应用完全 boot 后再重探**（#35 先例：grep -c=0 + 错误 class 查询均像「未部署」，字节对比 28662==源 + marker=1 证明修复已 serve，E2E 随即通过）
- **静态资源嵌套路径**：http4s DSL `Root / "vendor" / file` 只匹配单段子文件，`/vendor/pdfjs/pdf.min.js`（两段）会 404——嵌套路径需 `startsWith("/vendor/monaco/")` 前缀 handler；代码审查阶段即可发现，省整轮 boot-test 循环
- **产物二分法**（请求/写入绕过 patched API 时一步定位数据源）：string-replace 构建产物嫌疑字面值（如 './ice.webp'→'./BOGUS.webp'），看原始请求 URL 是否跟随；`getOwnPropertyDescriptor(CSSStyleDeclaration.prototype,'backgroundImage')===undefined` 证明命名属性 getter/setter 不在原型上——defineProperty 补丁必然静默空操作，运行时拦截不可行时改走构建期静态重写

## 7. 独立补充 harness（QA 视角盲点补验）

验收迭代改动时，独立 harness 瞄准产出者自测的结构性盲点，双轨报告（复现产出者结果 + 独立交叉验证）：

- 字面量精确比对（seed 计时 → formatDuration 字面量而非正则）
- 性能半边实测（归拢同步耗时）
- 多代码路径补缺（restoreFromStorage vs backend-history、live finish 各分支）
- 计算样式铁律而非仅存在性；异常/排除行（injected/askUser 不进组）

## 8. 确定性真实 fallback 夹具

配置 bogus primary（127.0.0.1:9 必挂）→ 真实免费模型 fallback，每 turn 必走 modelChanged 切换路径——mock 无法覆盖真实 WS 帧时序与 fallback 后 label 切换；老后端兼容用 wire 级 strip 模拟。

## 9. 契约未落地的 mock-frame 交验模式

前端任务面对「后端契约未落地、但产品验收需要真实事件流渲染」：

- 立即定位契约消费者的**骨架单点**（`grep` 消费者按 type/sessionId 分路的处理器），用 mock 帧从现有 onMessage 频道注入驱动 harness 自测
- mock 只换生产不换消费路径——断言可复用到真实后端（#380 13/13：同一 renderAskUser 消费者，真假帧行为一致）
- 交验标注「消费路径已真实验证，producer 待后端合入后补跑 E2E」——QA 与 Manager 接受该口径
- 开工前先 grep 冻结骨架 choke points 而非新造：`set` 权威状态 + 终态事件兜底清理路径 + 各事件处理器 + 驱动展示点——保留原路径只增修饰类，后端合入后仅换 mock producer 为真实帧、消费者断言零改动

## 10. 自适应布局三不变量

验证 priority-based 自动隐藏的响应式 header/toolbar（#396 先例），在每个测试宽度断言三个派生不变量——截图与单宽度检查抓不到跨宽度回归（中间优先级按钮隐藏而低优先级仍在、子元素被父 overflow 不可见地裁剪）：

1. **数据驱动固定元素永不隐藏**（backing data 存在时）
2. **可见元素两两不重叠**（getBoundingClientRect + display:none/offsetParent/零宽过滤）
3. **隐藏子集是前缀**（优先级索引 k 隐藏 ⇒ 0..k−1 全隐藏——防优先级顺序腐化，免金快照）

宽度用真实驱动（`#main.style.width` 而非 viewport），并 regex 源码中的 banish workaround（`.header-compact` 等）抓死代码复现；辅以高度恒定（无 flex-wrap）、center-clamp 预算、省略号断言。

## 11. 可测性架构：统一工厂 + 单一渲染器

一个功能有 N 个入口且都要产出同一 UI artifact/同一 wire-format 载荷（#303 全局引用 6 入口先例）：

- 统一工厂 `makeReference(rawInput)`（判别 refType、补全缺省）+ 单一渲染器 `renderRefBlock(ref, {mode})`（按 mode 分支渲染）+ 旧格式归一 shim——入口只产 raw input，绝不各自拼 DOM（各自拼会把同一 bug 复制 N 份且逐步漂移；单工厂收敛后只改一处）
- 发新载荷字段前先查后端 parser 是否容忍未知字段（circe `downField(...).getOrElse(Nil)` 游标式解析 → 可直接发新字段，前端少一层序列化适配，零后端改动）
- 断言收益：单一渲染器=单一断言点；旧结构 shim 归一保留兼容

## 12. 后端批次合并后的真链补验

前端功能依赖独立批次合并的后端 handler（#303 文件浏览器拖拽依赖 movePath 先例）：

1. 前端 mock harness 先验全链（不阻塞、不依赖跨批）
2. 后端合并后请求 Manager **rebase**（非 merge）特性分支到新主线——内容原样仅基线前移，避免 merge 历史噪音
3. 隔离实例（独立端口 + 预置磁盘夹具）起真后端跑真链 E2E——合成拖拽/保存事件驱动真实 UI，断言 = UI 状态 + 磁盘 ground truth（existsSync/readFileSync）
4. 真链证据补进交付说明后才审合——mock 只能证前端契约链，真 movePath 到达/响应/刷新链必须真后端走一遍

## 13. 进程清理纪律（2026-09-05 裁定）

- harness/冒烟/e2e 结束**必须清理自己 spawn 的进程**：`python http.server` 静态服务、裸 playwright 的 chromium、隔离实例全部关掉——不留给宿主或下一个会话手清。
- spawn 的 server 进程：收尾 `kill` + `lsof` 复查端口释放；脚本包 `trap 'cleanup' EXIT INT TERM`（裸 EXIT trap 信号退出不触发）。
- 裸 playwright（不走 @playwright/test runner）：`browser.close()` 必须进 `finally`——waitForSelector 超时等异常路径会跳过 happy-path close，headless chromium 进程树漏到脚本外；SIGINT/SIGTERM 时 Node 不走 finally，需显式信号处理器兜底。
- 环境表里的宿主 PID 绝对禁杀（第一道防线）；8080 端口识别是第二道防线；清理前照旧 PID 验身。

## Evidence

- 消息搜索 v3.1：3 个自包含 harness（35/35+13/13 绿，fetch 计数断言、零移除零重排子序列断言），qa-frontend 直接复用框架与种子。
- 弹窗发送链「看起来全接通」：探针 harness（打开→agentStart→stop→agentDone→重开→再发）T5 失败定位到 `_inputBound` 未重置——重开时 initInput 被跳过，正是用户「无法输入无法打断」根因。
- friends-contract-harness：19 断言覆盖 8 endpoint wire-shape + 字段透传 + auth/net 事件；fmtTime(1234567890) 期望失败因代码分支 same-day→HH:mm vs older→M-D，改 today-at-09:30 确定性打同一天分支。
- freeze-schedule：10 JS + 2 CSS + 2 locale 分层验证，checkJs 337 vs 基线 351 PASS；i18n "Thinking Mode"（大写 M）与 zh 推断不一致在编辑期捕获。
- image viewer "File may be corrupted"：catch-all `**/api/**` 后注册 shadow 了 `**/api/nf-file**`；catch-all 提前注册即修。
- onboarding 自测：typeOther('sk-test123') 命中 C1 隐藏 textarea，answers[0] 被写成 API key——可见性过滤后链路恢复。
- ice.webp 404：DOM 埋点+CDP initiator 全空，产物二分法一步锁定组件 JS 字面量为唯一数据源；descriptor=undefined 定案构建期静态重写。
- #346 双轨：产出者 30/30 复跑全绿 + 独立 19/19 盲点（formatDuration="1m 33s" 字面量、10 工具归拢≤50ms、摘要条计算样式×5、E7/E8 排除）。
- v5 阶段 0 旧面板 popover（#392）：初版浮层嵌 `#activity-bar` 内，DOM 断言全绿（`hidden===false`+blur(24px) 全过）但屏幕完全看不见——bar 是 `overflow:hidden`+`backdrop-filter` 的圆角卡，filter 使其成为 fixed 后代包含块，浮层被裁成 48px 一条（截图取证）。改挂 body + JS 锚定后，用「视口内有盒 + elementFromPoint 命中 + clearsBar」三连断言 48/48 全绿。
- usage-display 复验 27/27：bogus qadown 秒挂后 fallback 107/deepseek-v4-flash-ascend，modelChanged 帧全部真实 wire 数据。
