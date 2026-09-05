# 右下角通知（toast）毛玻璃重设计——实施报告

- 日期：2026-09-03
- 分支：`toast-glass`（基线 main@5bb8bd88，worktree `.nebflow/worktrees/toast-glass`）
- 分支 commit：`bd76d669` web(ui): toast 毛玻璃重设计（glass-control 材质，移除色条，类型语义图标化）
- 作者裁定：2026-09-03 21:09（毛玻璃材质 / 删色条 / 类型语义图标化）

## 一、改动清单（文件/函数级）

| 文件 | 位置 | 改动 |
|---|---|---|
| `src/main/resources/web/js/modal.js` | `showToast(message, type)`（:149） | 正文由 `toast.textContent = message` 改为先建 `.nebflow-toast-icon` span（字符图标，`aria-hidden`）+ `.nebflow-toast-msg` span（`textContent` 赋值，XSS 安全性与原实现一致），`toast.append(icon, msg)`；新增模块级 `TOAST_ICONS` 常量（error `✕` / info `!` / success `✓`，未知 type 回退 `!`）。**type 参数与 `.nebflow-toast-{type}` 类名保留，4000ms/300ms 生命周期零改动，全部调用方零改动** |
| `src/main/resources/web/css/modal.css` | `.nebflow-toast`（:1074 域） | 材质整体换 `--glass-control-*` token（见下）；box-shadow 五层（内缘高光/底缘 + glow + shadow + 远投影 glow），远投影色值用 `--glass-control-glow` 替代原自造 rgba |
| 同上 | `.nebflow-toast-error/info/success`（原 :1101-1103） | **三条 `border-left: 3px` accent strip 规则删除**；类名本身保留在 DOM（调用方零改动） |
| 同上 | 新增 `.nebflow-toast-icon` 族 | 图标 14px/600/`margin-right: 8px`；三类型图标色：error `#f44336`（作者指定值）、info `var(--color-primary)`、success `var(--color-success)`；正文保持 `--color-text` |
| 同上 | `@media (prefers-color-scheme: dark) .nebflow-toast`（原 :1113-1120） | **整块删除**——box-shadow 全 token 化后暗色适配由 token 自身的暗色媒体定义承载，无需组件级覆盖 |
| 同上 | `.nebflow-toast-link` | 原样保留（消费方 activityBar.js:455 `popupBlockedFallback`，零回归，spec A3 验证） |
| `tests/toast-glass.spec.mjs` | 新增 | 验收 spec（27 断言），引真实 modal.js + 真实样式链 |
| `tests/fixtures/toast-glass-harness.html` | 新增 | harness 页（base.css + modal.css + sapphire.css 按 index.html 生产顺序加载；条纹后景供毛玻璃透出） |

范围外零触碰（核实未动）：base.css `#global-perm-toasts`（权限 toast 另一族）、messages.js `modalEls.toast`（modal 内联提示）、activityBar.js `popupBlockedFallback`（独立构造、不走 showToast）。

## 二、材质 token 与层叠核对

**选值**（对齐 sapphire.css `.glass-control` 标准）：

```css
background: var(--glass-control-bg);                                            /* 浅 rgba(255,255,255,.45) / 暗 rgba(255,255,255,.05) */
backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);                 /* blur=10px（sapphire.css:119） */
border: 1px solid var(--glass-control-border);                                  /* 浅 .40 白 / 暗 .08 白 */
box-shadow:
  inset 0 1px 0 var(--glass-control-highlight),   /* 内缘高光 */
  inset 0 -1px 0 var(--glass-control-underedge),  /* 内缘底缘 */
  0 1px 3px var(--glass-control-glow),
  0 2px 8px var(--glass-control-shadow),
  0 8px 24px var(--glass-control-glow);           /* 融合原 toast 远投影层次，色值 token 化 */
```

**暗色媒体定义核对**：sapphire.css:122-135 暗色媒体查询对 `--glass-control-bg/border/highlight/underedge/glow/shadow` 全部有暗色值；`--glass-control-blur: 10px` 仅 `:root` 定义（主题无关）。**双主题各自成立，无需补 token。**

**层叠核对**：
- index.html:51 sapphire.css 最后加载（设计系统标准层，同特异性分组规则会覆盖 feature CSS）——但 grep 全量核实 sapphire.css **不含任何 `.nebflow-toast` 选择器**，modal.css 的 toast 规则无覆盖风险，直接用 token 写即可；
- `--color-primary: #07c160`（base.css:2）双主题同值（无暗色覆盖），info 图标色双主题一致；
- spec 实测计算样式：`backdrop-filter: blur(10px) saturate(1.2)`、`border-left-width: 1px`，token 实际生效值与定义一致。

## 三、图标方案定稿

- error `✕`（U+2715）色 `#f44336`；info `!` 色 `--color-primary`（#07c160）；success `✓`（U+2713）色 `--color-success`（#4caf50）——按作者裁定示例「✓/!/✕」逐字采用；
- 14px / font-weight 600 / 与正文 inline 排列（`margin-right: 8px`）——保持原 inline 流布局，activityBar `popupBlockedFallback` 的 `append(msg, a)` 结构不受影响（flex 方案会改其布局行为，故弃用）；
- 图标 span `aria-hidden="true"`（装饰性），正文可访问性文本不变。

## 四、spec 逐条结果

`node tests/toast-glass.spec.mjs` → **27/27 passed**（真实 modal.js 经 ESM import 加载，真实样式链）：

- **A1 三类型逐个**（error/info/success 各 7 断言）：`.nebflow-toast-{type}` 存在 ✓；图标 span 存在且字形正确（✕/!/✓）✓；图标计算色 = 类型色（rgb(244,67,54) / rgb(7,193,96) / rgb(76,175,80)）✓；无 3px 色条（border-left-width = 1px solid）✓；backdrop-filter 含 blur（实测 `blur(10px) saturate(1.2)`）✓；position: fixed ✓；约 4.3s 内自动移除（4000+300ms，5s slack 内，无需刷新）✓；
- **A2 堆叠**：连续触发 3 条 → 全部同时渲染 ✓、类名正确 ✓、全部按时移除 ✓、全程无 console/page error ✓；
- **A3 link 变体不回归**：`popupBlockedFallback` 同构 DOM（`a.glass-control.nebflow-toast-link`）计算样式 `display: inline-block` + `cursor: pointer` ✓；link-bearing toast 毛玻璃材质（blur）✓。

**i18n sweep**：不适用——本批无新增用户可见文案（图标为字符字形，非文案）。

## 五、回归结论

| 项 | 结果 |
|---|---|
| `node --check modal.js` | OK |
| `node scripts/check-js-types.mjs` | worktree 输出与 main **逐字节一致**（diff 为空）→ 本批**零新增**。exit=1 为 main 既有漂移：基线生成后 main 新增 flowAnim/flowMapTab/micOrb/orbSettingsUI（"new file"）+ chat.js TS2339 7>6，total 343>326，历史归因不计入本批 |
| pr41-locale-search-jump | **11/11 passed**（含「no jump-failed toast」断言，与 toast 域直接相关） |
| 代表性子集宽跑（`npx playwright test <file> --workers=1` 逐文件） | reftag-redesign 7 ✓、orbit-anim 6 ✓、node-bubble-header 5 ✓、turn-collapse-keep-text 8 ✓、tasklist-nodes 6 ✓、history-replay-cards 2 ✓ —— **34/34 全绿，零失败** |

任务书提到的既有失败（bgagent-dedupe ghost-dup 文案断言、smoke×5 缺真实后端）未出现在本子集，未触发，无隐藏。

## 六、双主题截图（真实触发）

- 暗色：`/Users/dev/.nebflow/docs/Nebflow/20260903_toast-glass-dark.png`
- 亮色：`/Users/dev/.nebflow/docs/Nebflow/20260903_toast-glass-light.png`

方式：worktree 内自起 8100 端口静态服务（非 8080），harness 真实样式链 + 真实 showToast 触发 success「已在文件浏览器中打开」+ error「连接已断开，正在重试」两条，Playwright `colorScheme: dark/light` 分别截图；用毕 server.close()，`lsof -ti :8100` 确认无残留。截图内 success/error 因现状同位重叠（fixed 20,20，本批不新增堆叠逻辑），截图脚本内对先触发条目加临时 `translateY(-64px)` 偏移使两条均可见——纯截图行为，生产代码零改动。视觉验收：毛玻璃半透明底 + 后景条纹透出（blur 生效）、1px 细边框、无色条、绿 ✓ / 红 ✕ 图标，双主题各自成立。

## 七、生效说明

**合并进 main 即交付（代码层）；运行时生效需前端产物重建（esbuild bundle）+ 宿主重启——本批严禁任何重启动作，随重启包生效。**

## 八、遗留问题

1. 多条 toast 同位重叠（fixed 20,20）属现状行为，本批按裁定未新增堆叠布局逻辑；若后续要做堆叠，建议独立任务（ toast 容器化，注意 activityBar `popupBlockedFallback` 的独立构造路径需一并纳入）；
2. check-js-types 基线落后于 main 现状（343 vs 326），建议独立任务 `--update` 重建基线或补齐新文件 checkJs-clean；
3. error 图标色 `#f44336` 为作者指定硬编码值，暗色下对比度可接受但未做 WCAG 校验；如后续统一错误色 token 可一并收编。
