# v3.1 消息搜索 · 视觉评审报告（四道防线 · 第三道）

- 日期：2026-08-17 23:35 · 评审人：design-engineer · 对象：feat/msg-search-v3 @cdb9b824（worktree /tmp/nb-msg-search-v3）
- 依据：规格书 v3.1 `/tmp/message-search-spec-v3.md` + visual-style 铁律 + design-system 案例 001
- **结论：FAIL**（1 项阻断：B13 锚点态「向上滑向更晚日期」触发死区；2 项非阻断发现）

## 交叉验证声明

qa DOM/行为断言 53 项全绿（core-report.json 38 项 + stream-report.json 15 项，2026-08-17 22:57/23:02）。本评审不裸看图：每条结论均有截图 + DOM computed + 像素/几何取证三证。评审中 `/tmp/msg-search-qa/` 被系统清理（证据目录整体消失），qa 已断言项以报告 JSON 为准，视觉证据由我用自包含 harness（route 拦截 serve worktree web/）重采，输出 `/tmp/dr-v31-*.png`。

## 阻断项（FAIL → 打回 Frontend）

### F-1 · B13 锚点态「向更晚方向滑动」存在触发死区 —— R1 旗舰承诺在直达路径上断裂

- **规格条款**：§1 规则 4 / §6.3 锚点跳转「向上触顶 → after 游标取更晚」/ §8 B13①；用户裁定原话「可以从5号滑到6号，中间是不会断的」
- **现象**（取证 `/tmp/dr-v31-b13-probe2.mjs`，seed 150 条跨三日，mock 遵循真实后端契约=全量返回）：
  - 锚定 8/16 后列表 born at `scrollTop=0`（锚点条目=渲染窗口首行，8/17 内容不在 DOM）
  - **路径 (a)** 用户直接在锚点态向上滚（wheel-up ×5）：**零请求、零加载，days 恒 [16]**——滚轮事件在 scrollTop=0 处不产生 scroll 事件，`loadAfterPages()`（chatSearch.js:168 唯一触发点=scroll 监听）永远不点火
  - **路径 (b)** 先向下滚再滚回顶：正确发出 `after=1786893181338&limit=100`，8/17 条目前置插入、scrollTop 保持（1650）、跨日共存 ✓（截图 `/tmp/dr-v31-b13-crossday2-dark.png`）
- **归因**：机制本身正确，但触发器只挂 scroll 事件；锚点定位后视口天然位于列表顶端，「触顶」先于任何滚动存在 → 用户裁定主场景「锚定 5 号 → 滑向 6 号」在不做反向折返的情况下不可达。qa 的 B13 全绿走的是路径 (b)，未覆盖该触发死区
- **修复方向（实现方择一）**：锚点重渲完成后若 `scrollTop ≤ 2 && s.start > 0` 主动补一次 `loadAfterPages()`；或对 wheel/touch 溢出方向兜底；或锚点定位预留上方偏移使首次上滑能产生 scroll 事件
- **验收补丁建议**：B13 增补子断言「锚点应用后**不先下滚**、直接 wheel-up → 必发 after 请求且 D+1 条目进入 DOM」

## 逐项核对（视觉相关段）

| 条款 | 结论 | 证据 |
|---|---|---|
| 铁律 1 · 无遮罩 | PASS | 全截图背景零暗化；qa A1 computed overlay rgba(0,0,0,0) 双主题绿 |
| 铁律 1 · 面板毛玻璃 | PASS | `#search-modal` blur(var(--glass-blur))；a1 亮/暗截图玻璃质感一致 |
| B1 打开即浏览 | PASS | `/tmp/dr-v31-b1-light.png`：23 条倒序、无旧 hint 文案、附件「📎 photo.png」文本化、时间戳右对齐 |
| §5.1 栏目条 | PASS | 5 栏目、「全部」激活下划线（蓝宝石蓝 inset 指示条）、日期栏目右置；字重 600/400 符合铁律 3 |
| B5 日历弹层（亮） | PASS | `/tmp/dr-v31-b5-light.png`：标题/双玻璃胶囊「2026年▾ 8月▾」/周一起始星期头/今日 17 环形高亮/未来日 18-31 置灰/取消+确定（玻璃主按钮，铁律 2）/顶部箭头 |
| B5 日历弹层（暗） | PASS | `/tmp/dr-v31-b5-dark.png`：全不透明度下可读性良好。**注意 qa 的 b5-popover-dark.png 是 0.12s 淡入中途抓拍**（opacity≈0.5 致背景文字透字），非实现缺陷；且 qa 的 b5-popover-light.png 根本没拍到弹层（采集时序问题）——亮色视觉证据以本评审重采为准 |
| 弹层嵌套玻璃渲染 | PASS | 像素取证（glass-only 对照 `/tmp/dr-v31-b5-glassonly-dark.png`）：弹层下文字笔画亮度 237→<70，抑制充分；bg 合成值与 rgba(24,28,38,0.68) 理论值一致 |
| B7 锚点标签 | PASS | `/tmp/dr-v31-b7-label-light.png`：「8月16日 ×」激活态+下划线，列表定位 8/16 首条并与 8/14 条目无缝同流 |
| B10 栏目空态 | PASS | `/tmp/dr-v31-b10-light.png`：文件栏目「该栏目下暂无内容」单句 + tool select 视觉禁用（§2.2 联动）|
| A7 跨会话分组 | PASS | `/tmp/dr-v31-a7-light.png`：组头「QA-Beta (2)」「QA-Alpha (14)」计数=组内条目，形态沿用 v2.1 |
| B12 · 375px | PASS | 几何：popover left=55.5 ≥0、right=319.5 ≤375、两态无新增横滚（qa B12 绿）；截图 `/tmp/dr-v31-b12-375-open-dark.png` 内容完整可读 |
| A10 reduced-motion | PASS（断言沿用） | qa A10 绿；CSS modal.css:1033-1040 弹层 0.01s + cal-day 去过渡，符合 §4 表 |
| 零新 token 纪律 | PASS | 新增色值仅 rgb(91,127,191) 透明度变体（指示条 0.8/today 环 0.8/selected 0.35），与案例 001 纪律一致 |
| B13 跨日共存（路径 b） | PASS | 见 F-1：下滚回顶触发后 16+17 共存、单调扩展、scrollTop 保持 |
| **B13 跨日共存（路径 a：锚点态直接上滑）** | **FAIL** | **见 F-1（阻断）** |

## 非阻断发现（不拦合并，建议随修复一并处理）

- **N1 · 短弹窗态弹层底部约 3px 被裁**（规格缺失 · §6.1 未覆盖）：结果 0–2 条时弹窗矮于弹层，`#search-modal` overflow:hidden 裁掉弹层底部圆角/边框（popBottom 609 > modalBottom 606；确定按钮完整可点，confirmCenterHit=true）。Frontend 22:18 自采图可见底部按钮贴边。建议规格补边界条款 + 实现择「弹层翻转向上 / 弹窗 min-height / 弹层脱离 overflow 上下文」其一
- **N2 · qa 截图采集时序**：弹层截图须等 0.12s 淡入结束（建议 waitForTimeout ≥300ms）再拍，b5-light 需确认弹层实际入镜；已删除目录中的 b5-popover-light/dark 两张不满足 §8「需截图」的证据标准，本评审已用重采图补齐

## 环境备注

评审 23:13 许 `/tmp/msg-search-qa/`（qa 证据目录）被整体清理消失，疑为系统 tmp 清理；qa 报告 JSON 内容已在本评审上下文留档，视觉证据重采于 `/tmp/dr-v31-*.png`（flat 文件未受影响）。建议 qa 后续把证据目录放 `~/.nebflow/` 下而非 /tmp。

## 结论路由

**FAIL** → 附本报告打回 Frontend 修复 F-1（建议连带 N1）；修复后重审仅需复测 F-1 两条路径 + N1 短弹窗态。qa 侧建议按「验收补丁建议」补 B13 子断言防回归。
