# 设置面板隐藏光球（micOrb）配置区——实施报告

- 日期：2026-09-03
- 分支：`micorb-config-hide`（自 main@1f10246a 开出，worktree 独占施工）
- 分支 commit：`398ee67b`（基于 main@1f10246a，merge main 后无新提交即最新）
- 作者裁定：2026-09-03 光球按预设驱动，设置页隐藏光球配置区；UI 层门控隐藏，代码与配置读取逻辑全保留，用户本地已存自定义配置不受影响。

## 1. 门控实现点（文件/函数级）

唯一改动文件：`src/main/resources/web/js/sidebar.js`

| 位置（符号定位） | 改动 |
|---|---|
| :23 `import { renderAppearanceSection, bindAppearanceEvents } from './orbSettingsUI.js'` | **保留不动**（harness/tests 仍用，未来恢复无需改 import） |
| :24-27 模块级新增 | `const ORB_SETTINGS_VISIBLE = false;` + 注释（2026-09-03 作者裁定；翻回 true 即恢复） |
| `renderSettings()` 外观 section（原 :757-760） | 整块（`settings.appearance` 标题 + `renderAppearanceSection()` body）包进 `${ORB_SETTINGS_VISIBLE ? `...` : ''}` —— 无空壳「外观」卡片残留 |
| 设置事件绑定中 `bindAppearanceEvents(content)`（原 :1290） | 改为 `if (ORB_SETTINGS_VISIBLE) bindAppearanceEvents(content);` |

**连带入口排查结论**：全文件 grep `appearance|orb`，除上述两处消费点外仅有 :152/:166 的 lucide `orbit` 导航图标（agent 列表图标，与 micOrb 配置无关，不属配置入口，未动）。无其他独立按钮/入口。

**严禁触碰域确认零改动**：orbSettingsUI.js / orbPresets.js / micOrb.js / i18n.js / locales（zh-CN/en）/ chat.js / 后端 scala / 既有 spec 全部原样（`git show --stat 398ee67b` 仅 2 文件：sidebar.js + 新 spec）。

**恢复方式（一句话）**：把 sidebar.js 里 `ORB_SETTINGS_VISIBLE` 翻回 `true` 即完整恢复配置区（渲染+事件绑定自动回归，无需其他改动）。

## 2. 改动清单（commit 398ee67b）

1. `src/main/resources/web/js/sidebar.js`：+9/-3（门控常量 + 两处消费点门控 + 注释）
2. `tests/micorb-settings-hidden.spec.mjs`：新增（211 行，3 个用例 H1/H2/H3）

## 3. 测试结果（全部真实执行）

### 3.1 新 spec `tests/micorb-settings-hidden.spec.mjs`（3/3 绿，前台真实跑）

| 用例 | 内容 | 结果 |
|---|---|---|
| H1 DOM 断言（真实路径） | 静态服务（127.0.0.1:8177，非 8080）serve web 根；真实 app shell + WS/API mock（tmp-usage-display-verify.mjs 先例模式）；`await import('/js/sidebar.js')` 调真实 `renderSettings()`；断言 `#settings-content` 无 `settings.appearance` 标题分区（zh「外观」/en「Appearance」）、无 18 类 orb-* 配置元素（#orb-base-select/#orb-appearance-reset/#orb-custom-*/#orb-preview-slot/.orb-state-select/.orb-state-row/.orb-slot-*/.orb-theme-btn/.orb-swatches 等）；zh-CN 与 en 各一遍；相邻分区（运行时/服务商等 >3 section）仍在——整块门控无空壳 | ✅ PASS ×2 locale |
| H2 代码保留证据 | import 真实 `/js/orbSettingsUI.js` 直接调 `renderAppearanceSection()` 返回 >500 字符 markup，含 orb-base-select/orb-state-select/orb-appearance-reset/orb-preview-slot（与 micorb-presets T4 harness 同源） | ✅ PASS |
| H3 自定义配置仍生效 | 不经 UI 直接 `orbPresets.saveSaved({base:'emerald', map:{listening:'sunset'}, custom:{dark:{a:'#40E0FF'}}})` → shell live orb（真实 MicOrb，webglOk 断言过）经 CHANGE_EVENT→loadSaved 解析出自定义板（idle a=#40E0FF/b,c=emerald；listening→sunset 映射不受 custom 覆盖）——隐藏入口≠丢配置 | ✅ PASS |

### 3.2 既有回归（零改动）

- `tests/micorb-presets.spec.mjs` T1-T6（含 T4 harness 挂载）：单独跑 **6/6 全绿**（13.8s，断言零改动）
- `tests/orbit-anim.spec.mjs`：**6/6 全绿**
- `node --check src/main/resources/web/js/sidebar.js`：通过

### 3.3 i18n sweep

`node scripts/verify-i18n-sweep.cjs 8387`（自起静态服务 8387，用毕确认 PID 非 87216 后 kill，端口已释放）：**11/11 PASS**——zh/en parity 921=921 不破，`settings.appearance` 系列 key 全部保留（key 层不动，符合裁定）。

### 3.4 套件宽跑（19 个标准 .spec.mjs 显式逐文件，89 用例：77 绿 / 12 失败）

**重要发现**：`npx playwright test tests/` 整目录跑不可作为全量证据——`tests/pr41-locale-search-jump.spec.mjs` 是顶层自执行脚本（playwright-core 直跑 + `process.exit(0)`，其文档自述运行方式为 `node tests/...`），playwright 收集阶段加载它即截断整个 run 并返回 EXIT=0（连 `--list` 干跑都会触发）。因此改用显式逐文件跑。

12 个失败逐项归因（**无一属于本批**）：

| 失败 | 数量 | 归因 |
|---|---|---|
| `micorb-presets` T2/T4（多 worker 合跑时） | 2 | 多 worker 并行资源竞争（timing 敏感断言）；受控归因跑 `--workers=1` 下本批域三件套（micorb-presets + micorb-settings-hidden + orbit-anim）**15/15 全绿**。物理证据：harness 执行链只 import micOrb/orbPresets/orbSettingsUI，**不含本批唯一改动文件 sidebar.js** |
| `askuser.spec.mjs` | 4 | `ERR_CONNECTION_REFUSED localhost:8094`——需真实后端实例；该域属并行节点「补充-AskUser刷新存活」（askuser-refresh worktree），与本批零交集 |
| `smoke.spec.mjs` | 5 | 同为 "real backend" 用例（WS auth/lazy-load/Canvas readFile/settings modal 逐条断言 console errors）——后端 400/连接拒绝所致，环境缺真实后端；门控只减 DOM 不产生网络请求 |
| `bgagent-dedupe.spec.mjs` ghost-dup | 1 | 任务书明示的历史既有失败（已归因既有），不计入本批 |

本批域内（micorb-* / orbit-anim / 新 spec）在任何受控跑法下零失败。

## 4. 环境纪律执行记录

- 静态服务均非 8080（8177/8387），用毕 lsof 确认 PID（97545=python http.server）非宿主后 kill，端口已释放复验
- 未碰宿主进程（PID 87216 / 端口 8080）；未跑 sbt（纯前端零 scala 改动）
- git add 仅具体文件，commit 前复核暂存区；不 push 不动 origin
- 工作区最终干净（git status 无未跟踪/未提交）

## 5. 生效说明

本批为前端产物源码改动：**生效需前端产物重建 + 应用重启，本批不做**（任务明令禁止重启宿主）。当前运行中的 Nebflow 实例前端仍是旧产物（配置区仍可见），重建+重启后设置页即无光球配置区。

## 6. 遗留问题

1. `npx playwright test tests/` 整目录跑被 pr41 自执行 spec 截断（EXIT=0 假绿）——建议后续把 pr41 改名为非 `.spec.mjs` 后缀或加 playwright test() 包装（超出本批授权，未动）。
2. 多 worker 并行下 micorb-presets T2（timing 断言）存在资源竞争 flaky，单 worker 稳定全绿——建议 CI 固定 `--workers=1` 或给 T2 放宽时序容差（超出本批授权，未动）。
3. 旧验证节点 n-5aaf0e3a 的验收口径含「设置面板可编辑」——已被本裁定取代（任务书明示），无需兼容。

## 7. 附件

- 截图：`~/.nebflow/docs/Nebflow/20260903_settings-no-orb-config.png`（设置页 zh-CN：设备互联→运行时→LLM 服务商→模型方案，无「外观」区、无任何 orb-* 元素）
