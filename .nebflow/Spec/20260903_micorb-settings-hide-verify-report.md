# 设置面板隐藏光球配置区——独立验证报告（QA）

- 日期：2026-09-03
- 验证节点：验证-隐藏光球配置区（barrier，独立复核）
- 上游：实施-隐藏光球配置区（n-783a71c0），commit `398ee67b`（分支 micorb-config-hide）
- 验证 worktree：`/Users/dev/Claude code/Nebflow/.nebflow/worktrees/micorb-config-hide`（分支只读验证，未合并、未 push）
- 验收解释权：作者 2026-09-03 裁定——光球按预设驱动，配置 UI 隐藏；代码与配置读取逻辑保留（不删）；micOrb 9 态默认映射行为不变；用户本地已存自定义配置不受影响。旧节点 n-5aaf0e3a「设置面板可编辑」口径作废。
- 独立性声明：全部验收证据由本节点独立生成（独立 spec `tests/verify-micorb-hide-qa.spec.mjs`，预设组合 magma/iceberg 与上游 H3 的 emerald/sunset 不同，断言不复用上游输出）。

---

## 逐项验收表

| # | 验收项 | 判定 | 证据 |
|---|--------|------|------|
| ① | 隐藏断言（独立重放） | **PASS** | 独立 spec V1 四象限 4/4 绿（zh-CN/en × dark/light）：真实静态服务（127.0.0.1:8188）加载真实页面 → import 真实 `/js/sidebar.js` → `renderSettings()` → `#settings-content` DOM 断言：零 orb-* 配置元素（18 类选择器零命中 + 任意 `[id*=orb]/[class*=orb]` 节点零命中，含 display:none 均无）；无「外观」/「Appearance」标题卡片（无空壳）；相邻分区完好（sections>3，标题全非空）。CSS 层抽查：61 条 orb-* 规则仍在但全部为死规则（DOM 零匹配 = 无可见残留），且为「代码保留」证据 |
| ② | micOrb 渲染回归不受影响 | **PASS** | 独立重跑（--workers=1）micorb-presets T1-T6 全绿（断言零改动；T1 出厂断言 = Ocean base + 默认 8 态映射，即九态默认映射行为不变；T4 经 harness 直接挂载 orbSettingsUI.js 通过 = 模块保留证据）+ orbit-anim 6/6 绿，共 12/12 |
| ③ | 自定义配置读取路径保留 | **PASS** | 独立 spec V2a/V2b 全绿：V2a 直接预写 localStorage `nebflow_micOrb.palette`（base=magma + custom.dark.a=#FF00AA + map frozen→iceberg）→ 页面加载（刷新语义）后 live orb renderer.target 三槽精确解析为自定义板（a=#FF00AA, b=#F0483C, c=#7A1220）；V2b 不经 UI `orbPresets.saveSaved()`（dawn + processing→neon）→ CHANGE_EVENT 即时驱动 live orb（idle=dawn dark 三槽精确；apply('processing')→neon dark 三槽精确）。隐藏入口 ≠ 丢配置 |
| ④ | 改动面审查 | **PASS** | `git show 398ee67b --stat`：仅 2 文件——`src/main/resources/web/js/sidebar.js`（+9/-3）+ 新 `tests/micorb-settings-hidden.spec.mjs`（202 行）。sidebar.js 三处：`:28` 模块级 `const ORB_SETTINGS_VISIBLE = false;`（注明裁定与恢复方式）；`:762-766` renderSettings 外观 section 整块（标题+body）条件插值；`:1296` `if (ORB_SETTINGS_VISIBLE) bindAppearanceEvents(content)`。全文件独立 grep：appearance/orb 消费点仅上述两处均已门控；`:23` import 按裁定保留（T4 harness 依赖）；`:171` lucide `orbit` 为导航图标非配置入口（无需门控）。orbSettingsUI.js / orbPresets.js / micOrb.js / i18n / locales / chat.js / 后端零触碰。无删除式改动 |
| ⑤ | 回归独立复跑 | **PASS** | 三件套重跑全绿：hidden spec 3/3 + micorb-presets 6/6 + orbit-anim 6/6 + 独立 spec 6/6；i18n sweep 独立跑 11/11 PASS（自起 8189 静态服务，parity 921=921）；appearance key 独立断言：zh/en 各 20 个 `settings.appearance.*` key 全保留（locale 零触碰实证）；宽抽查 smoke：8/13（5 失败归因见⑥）；`node --check sidebar.js` 过 |
| ⑥ | 既有失败归因核对 | **PASS（归因成立）** | smoke×5：全部为 real-backend 用例（`loads without console errors`→API 400、WS cookie 探测、lazy-load ×3→需 8094/8080 真后端），环境既有，与本批（sidebar.js 门控）零交集；bgagent-dedupe ghost-dup×1：去重行为断言 `toHaveCount(1)` 通过，失败的是过期文案断言（spec 期望 `running` 但 UI 已中文化为 `进行中`）——历史 i18n 中文化批遗留，与本批无关 |

## 改动面审查结论（门控可逆性 / 零删除确认）

- **门控可逆**：单常量 `ORB_SETTINGS_VISIBLE` 两处消费（renderSettings 插值 + bindAppearanceEvents）。恢复 = 翻回 `true` 一行，无其他改动。确认。
- **零删除**：diff 为纯插入/包裹，import 与模块调用全部保留在插值/条件中，未删除任何函数或引用。确认。
- **无越界**：禁改域（orbSettingsUI/orbPresets/micOrb/i18n/locales/chat.js/scala 后端）零触碰（diff stat 实证）。
- **无未门控连带入口**：全文件 grep 独立复核，appearance 连带消费点仅两处且均已门控。[verify-fix] 不需要。

## 回归结论（逐 spec）

| Spec | 结果 | 备注 |
|------|------|------|
| tests/verify-micorb-hide-qa.spec.mjs（本节点新立） | 6/6 绿 | V1×4 + V2a + V2b |
| tests/micorb-settings-hidden.spec.mjs（上游新 spec） | 3/3 绿 | 独立重跑复核上游结论 |
| tests/micorb-presets.spec.mjs | 6/6 绿 | --workers=1；断言零改动 |
| tests/orbit-anim.spec.mjs | 6/6 绿 | R1/R2/R3×2 + pending + visual |
| scripts/verify-i18n-sweep.cjs | 11/11 PASS | parity 921=921；appearance key 20×2 保留 |
| tests/smoke.spec.mjs | 8/13 | 5 失败均 real-backend 环境既有（见⑥） |
| tests/bgagent-dedupe.spec.mjs | 1/2 | ghost-dup 文案断言过期（历史 i18n 遗留，见⑥） |
| node --check sidebar.js | 过 | 语法零问题 |

## 遗留问题（不阻塞合并）

1. smoke×5 与 bgagent-dedupe×1 为历史既有失败（归因见⑥），建议另行开任务修复（smoke 接真实后端；bgagent-dedupe 文案断言同步中文化）。
2. pr41 自执行 spec 顶层 `process.exit` 会截断 `npx playwright test tests/` 整目录跑（EXIT=0 假绿）——上游已发现，本节点未复跑整目录（逐文件跑受控），建议改名/包装该 spec。
3. micorb-presets T2 多 worker 时序 flaky——建议 CI 固定 `--workers=1`。
4. 生效需前端产物重建 + 应用重启（本批禁令未做）；当前运行实例仍显示旧 UI。

## 本节点 commit

- 分支 micorb-config-hide：`e34cabc7`（[verify] 独立验证 spec，仅新增 1 文件，未触碰上游改动）
- 未合并回 main、未 push（合并留给下游节点）

## 最终判定

**PASS（可合并）**——六项验收全部 PASS，门控实现符合作者裁定（UI 隐藏、代码与读取保留、9 态默认映射不变、自定义配置照常生效），门控一行可逆，无删除式与越界改动。
