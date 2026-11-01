---
type: batch
status: done
related: "[[20261101_172033_ustc-git-survey__kernel]]"
---

# USTC main 10 条并入本地 main 批（2026-11-01）

> 批令（作者 11-01 17:4x，console 牌「把张的 10 条合并进来」）：把张安齐直推到 USTC `kaiyu/stand` 远端 main 的 10 条合并进本地 main；冲突面双方改动都保（功能语义各留各的，locales 键集合并去重）；合并后重跑 checkJs 并重生成 `type-baseline.json`（报前后数）；受影响套件 + seed 四 spec + 今日新增 spec 抽跑，红必须归因（存量 vs 新引入），不许带红合并；视觉回归 harness 接进前端验证流（win32 基线留给 Windows runner 腿，说明进文档）；`git push dev main` 回推 USTC main（禁 force）。调研前置 = [[20261101_172033_ustc-git-survey__kernel]] §1.2/§1.3。

## 0. 结论（先行）

1. **合并完成、实际冲突 0**：merge commit `5febec851`（父母 = 本地 `7d18dabb0` × dev/main `081cde4e8`）。撞车交集仍是调研报告的 6 文件，但两侧改动落在**互不重叠的 hunk** 上——git 三方合并自动合入，调研预判的「6 文件手解冲突」未发生。两侧改动逐文件对账（numstat 等值法）证明各留各的。
2. **类型闸 红 → 绿**：合并前 365 错 / 基线 345 / 闸红；合并后 zhang 的烧平成果落地为 0 错 / 基线 0，仅剩本侧一个文件 3 处新错，就地窄化后 **0 错 / 基线 0 / 闸绿**；`--update` 重生成的基线文件零差异。
3. **测试零新增红**：sbt 编译绿（701 主源 + 968 测试源，`-Xfatal-warnings`）× seed 四 spec + `StaticRoutesHomeScriptSpec` = 34/34 绿；前端 20 spec 抽跑 14 绿 6 红，**6 红全部为存量或环境前置缺失**——5 红在 pristine（`7d18dabb0`）树上逐字复现，1 红需隔离实例前置（证据见 §3.2 与 evidence 目录）。
4. **视觉 harness 已采纳**：`make check-visual` / `make visual-baseline` 双 target + CONTRIBUTING 双语验收清单小节 + 本机平台基准 `darwin-chromium-151`（12 张）入库；`win32-msedge-156` 原样保留归属 Windows runner 腿。
5. **推送**：`git push dev main` 待合并落库后执行（落库前 dry-run 实测非快进被拒 = 预期，见 §5）。

## 1. 合并面

| 角色 | sha | 说明 |
|---|---|---|
| merge-base | `f8bd1f0d4` | 两侧共同祖先（zhang 的 5008241be 曾把它当 upstream 合流） |
| 本地 pre-merge | `7d18dabb0` | 本地 main tip，含我们同日 44 条 |
| dev/main tip | `081cde4e8` | zhang 10 条（15:42–16:20，`zhang <zhang@local>`） |
| **merge commit** | **`5febec851`** | `Merge remote-tracking branch 'dev/main' into delegate/delegate-nebflow-eed3ad45`，无冲突 |

### 1.1 撞车 6 文件逐条处置

| 文件 | zhang 侧改动性质 | 本侧改动性质 | 处置 |
|---|---|---|---|
| `js/chat.js` | 补 JSDoc `@param` 类型（+3/−1） | 紧急压缩卡注释批 | 自动合，两侧均在位 |
| `js/locales/en.js` | +4（`neblink.logout` 退出登录键 + 注释） | +17/−2（本侧同日新键） | 自动合；键集合并去重实证：**重复键 0**，en 2228 / zh 2235 |
| `js/locales/zh-CN.js` | +4（同上中文键） | +13/−2 | 自动合，同上 |
| `js/main.js` | +21（`import './nfAdaptApi.js'` R1 门面 + 5 处类型窄化） | +18/−4（移动端顶部标识批 / 玻璃批锚点） | 自动合，两侧均在位 |
| `js/sidebar.js` | 一批类型窄化（`querySelector` 结果按 HTMLElement 窄化） | +2/−2 | 自动合 |
| `js/viewers/shared.js` | 类型窄化（`CSSStyleRule` 窄化、pane expando 注解） | +23/−18 | 自动合 |

**对账方法**（消「自动合是否悄悄吞掉一侧」的疑虑）：对每个文件同时算两条 numstat——`081cde4e8..HEAD`（合并树相对 zhang 树的差 = 本侧改动量）与 `f8bd1f0d4..7d18dabb0`（合并前本侧改动量），逐文件等值：`+3/−1`、`+17/−2`、`+13/−2`、`+18/−4`、`+2/−2`、`+23/−18`。等值 ⇒ 本侧改动在合并树上逐字节保留；zhang 侧同理（`7d18dabb0..HEAD` 的差 = 他的 6 文件改动全体）。

**单侧文件零冲突**（与调研 §1.3 一致）：`tests/type-baseline.json`（367→2 的烧平结果，实际落库值 total=0——他后续又清空）、`js/vendor-globals.d.ts`（+205）、`gateway/StaticRoutes.scala`（NF_HOME 注入 +46）、新增 `StaticRoutesHomeScriptSpec.scala`、`js/nfAdaptApi.js`（101 行）、`tests/visual/`（12 张基准 + manifest）、`scripts/visual-baseline.mjs`、`scripts/lib/{visual-harness,png-diff}.mjs`、`settings-cleanup.spec.mjs`（+84）、`package.json`（3 条 visual script 别名）。

## 2. 类型闸前后（checkJs）

| 时点 | tsc 错总量 | 基线 | 闸 | 失败项 |
|---|---|---|---|---|
| 合并前 `7d18dabb0` | **365** | 345 | **红（EXIT=1）** | ws.js zero-gate 6；mobile/navController.js 新文件 3；modelPanel.js 新文件 10；main.js TS2339 19>13；scheduled-task.js 9>8；total 359>345 |
| 合并后（未补） | 3 | 0（zhang 已烧平） | 红（EXIT=1） | 仅 mobile/navController.js 新文件 3 |
| **提交态（本批补后）** | **0** | **0** | **绿（EXIT=0）** | — |

- 补的那一处：`mobile/navController.js:1221` 的 `pane.style.*` 三连——`querySelector` 静态签名返回 `Element`，要写样式需 `HTMLElement` 面；就地注解窄化（不放宽基线，守「基线只降不升」），并留注释说明「本批不得把新错带进来，故窄化而非抬基线」。
- 基线重生成：`node scripts/check-js-types.mjs --update` → `baseline written: 0 errors across 0 files`，`git diff tests/type-baseline.json` **空**（zhang 的 total=0 已是正确终态，本批无需改写）。
- 口径差异注记：调研报告写的是「367→2」，实际合并树实测基线 total=**0**（他 15:42 那条之后又清掉了剩余 2 条）。本批以实测为准。

## 3. 测试

### 3.1 Scala 侧（sbt，无缓存全量编译）

- `sbt testOnly`：编译 701 主源 + 968 测试源，`-Xfatal-warnings` 下零 warning 红；目标 5 个 spec **34/34 全绿**，299s：
  `SeedManifestCoverageSpec` / `SeedServiceSpec` / `SeedPluginReconcileSpec` / `SeedPluginDeletionMarkerSpec`（= 批令里的「seed 四 spec」）+ `StaticRoutesHomeScriptSpec`（zhang 的 NF_HOME 注入结构钉，5 条含 Windows 反斜杠转义与空家目录不注入）。
- 汇编摘要见 `evidence/20261101_175329_ustc-zhang-merge__delegate-nebflow-eed3ad45/sbt-seed-summary.txt`。

### 3.2 前端 20 spec 抽跑（串行跑，禁并行端口腔）

覆盖批令点名的 chat / sidebar / main / locales / viewers 相关面 + seed 相邻 + 今日新增主要 spec：

**绿 14**（括号内为耗时）：`chat-autoscroll-follow`(7s)、`chat-skeleton`(8s)、`chat-history-perf`(12s)、`chat-diff-canvas`(20s)、`sidebar-plugins`(12s，5/5)、`canvas-html-interactive`(5s)、`canvas-preview-nav-parity`(22s)、`mobile-panel-title`(38s，本侧最新批)、`search-return-pill`(62s)、`pop-footer-family`(13s)、`cards-family`(46s)、`entry-veil`(3s)、`bg-flow-chain-visibility`(9s)、`delegate-receipt-card`(15s)。

**红 6 + 逐条归因**（证据全在 `evidence/…/attribution/`）：

| spec | 现象 | 归因 | 判据 |
|---|---|---|---|
| `sidebar-restructure` | T1 活动栏顺序缺 `wiki-btn`（1 红 / 5 绿） | **存量（早于两侧今日批）** | pristine `7d18dabb0` 树同红，且 merge-base `f8bd1f0d4` 纯净树**同样红**（1 红 / 5 绿，同条同因） |
| `content-i18n` | 第 97 行 `DS.agents[0].id` 崩溃（1s 即崩，未起浏览器） | **存量** | pristine 树**逐字同崩**（同文件同行同错）——夹具/数据形状过期，与本合并无关 |
| `settings-cleanup` | P 键集对等失败：8 个只在 zh 的键（`chat.allowTool`/`chat.permSource`/`chat.permLevel.*`/`chat.permExecCmd` 等） | **存量** | pristine 树同红（1 红 / 9 绿）；键集不对称在 merge-base `f8bd1f0d4` 就已存在（zhOnly=8，两侧全同）。**zhang 新加的 T4c「设置账号块携带退出登录入口」在合并树上通过**（合并树 10 绿 / 1 红 = 比他 pristine 版多过的正是这条） |
| `canvas-html-zoom` | `.canvas-zoom-btn[title="Zoom in"]` 30s 不可点 | **存量（早于两侧今日批）** | pristine 同红，且 merge-base `f8bd1f0d4` 纯净树**同样红** ⇒ 不是今日任何一批引入 |
| `canvas-file-refresh` | A2 strict mode：一个 pane 内解析出 2 个 `.canvas-md-viewer`（8 绿 / 1 红） | **存量** | pristine 树逐字同红（同条同因同计数） |
| `toggle-green-revert` | `fetch failed` → 无法铸 console-ticket | **非代码红：环境前置缺失** | 该 spec 需隔离实例 `:8765` + `/tmp/nb-toggle-green-batch/home/auth.json` 令牌（真实 http4s 实例）；本批未起实例，属「前置未满足」而非断言失败。其钉的开关面本批零改动（zhang 对 sidebar.js 的改动纯类型窄化、行为零变） |

**零新增红结论**：6 红中 5 红在 pristine 树逐字复现（三树对照法：pristine=`7d18dabb0`、base=`f8bd1f0d4`、HEAD=合并树），第 6 红为环境前置缺失 ⇒ 本合并未引入任何新红。

### 3.3 其它静态闸与旁证

- **check-circular**：闸红（EXIT=1），2 条 SCC——`popArtifacts.js ↔ reference.js ↔ msgQuote.js`、`mobile/navController.js ↔ mobile/taskPanel.js`；与 pristine 树输出**逐字节相同**（`diff` 空）⇒ 存量，非本批引入（详 §6 开放项）。
- **esbuild 生产 bundle**：`node scripts/build-web.mjs` 绿——203 源全入图（orphans 0）、226 懒加载 chunk 边界保留、CSS 36 文件拼装；入口产物 `build/web-dist/assets/app-*.js` 里 `__nfAdaptApi` 命中 1 处 ⇒ zhang 的 R1 门面化「裸 import 不被摇掉」的自验法实测成立（他注释里留的 grep 验法）。
- **语言键集**：`en.js` 2228 键 / `zh-CN.js` 2235 键，**重复键 0**（合并去重成立）；不对称量（zhOnly 8 / enOnly 1）在 base 与两侧树完全一致 ⇒ 存量。

## 4. 视觉回归 harness 采纳（本批落点）

1. **check 脚本面**：`Makefile` 新增两 target（并进 `.PHONY`）——
   - `make check-visual` → `node scripts/visual-baseline.mjs --check --max-ratio 0.0005`（带 node 在位守卫，阈值可由 `VISUAL_MAX_RATIO` 覆盖）；
   - `make visual-baseline` → `--update`（录完提示「人工评审 `.nebflow/visual-baseline/<platform>/` 后再提交基准」）。
   **不并入 `make check`**：后者是 Scala 静态面（compile/fmt/lint），视觉腿依赖浏览器且按宿主平台分库，属「改动后自检」腿。
2. **文档清单面**：`CONTRIBUTING.md` / `CONTRIBUTING.zh-CN.md` 前端门禁小节后新增「视觉回归基线 / Visual regression baseline」篇——三态用法、平台键 `<process.platform>-<browserLabel>`、MISS 的正确处置、win32 归属 Windows runner 腿、阈值的显式放宽依据。
3. **本机基准入库**：`tests/visual/baseline/darwin-chromium-151/`（12 张 + manifest）。实测口径：`--check` 最终 **VISUAL BASELINE PASS（9 张 SAME + 3 张在 0.002–0.003% 容差内 PASS）**；`--determinism` 同轮双拍逐张一致 ⇒ 差异是**跨轮宿主渲染噪声**（两个 monaco 场景 552×874 的 1px 页签边框，12–16 px 漂移）而非产品抖动。因此本机 target 显式带 `--max-ratio 0.0005`（≈241 px 于该画布），一整个字形/一整行边框量级的真变化仍会红；要零差异把关用 `make check-visual VISUAL_MAX_RATIO=0`。首次录基准时机器上有 spec 套件在跑，两个 canvas 场景漂移偏大——**录基准应在空载时做**（此条已写进 Makefile 注释）。
4. **win32 腿说明**：`win32-msedge-156/` 原样保留（作者 Windows + Edge 宿主采集，`label` 由 `launchBrowser` 对系统 Edge 自动取 `msedge-156`）；跨 OS/浏览器构建像素不可比，故「Windows runner 腿跑 `--check`（建议 `VISUAL_MAX_RATIO=0`，那套基准无噪声采集）命中该目录；其它宿主 MISS ⇒ 自行录本平台基准」写进双语文档与 Makefile 注释，不放宽阈值糊过去。
5. **零端口纪律**：harness 走 `page.route` 从磁盘供给 `src/main/resources/web` + MockWS，不起服务 ⇒ 结构上碰不到宿主 gateway `6138`；本批多次 `--check`/`--update` 全程零端口零进程。

## 5. 推送

- 目标：`git push dev main`（`dev` = `git@git.ustc.edu.cn:kaiyu/stand.git`；不碰 GitHub `origin`）。
- 落库前 dry-run 实测（留证）：`! [rejected] main -> main (non-fast-forward)`——本地 main 尚未含 `081cde4e8`，符合预期，**不 force**；SSH 鉴权通（到得了远端、被策略拒）。
- 实际推送在合并落库（main 收到本分支）之后执行；区间 = `081cde4e8..<合并后 main tip>`，即把 zhang 的 10 条 + 我们今日 44 条 + 本合并提交一并回推 USTC main。

## 6. 未做 / 开放项（如实申报）

1. **5 条存量红未修**（各有主，非本批引入）：`sidebar-restructure` T1（wiki-btn 缺位——与本侧 wiki 图谱入口的旗标门相关）、`content-i18n`（夹具 `DS.agents` 形状过期）、`settings-cleanup` P（8 个 zh-only 键，建议由权限档批补 en 侧）、`canvas-html-zoom`（缩放钮可点性，base 即红）、`canvas-file-refresh` A2（同 pane 双 `.canvas-md-viewer` 挂载，疑渲染双写）。任一条需要各自批的上下文，本批不动。
2. **check-circular 2 环未动**：`popArtifacts ↔ reference ↔ msgQuote` 与 `navController ↔ taskPanel` 涉及在飞批模块（今日 Pop footer / 卡片家族 / 移动手势批），此刻改动必与其 worktree 撞车；建议由归属批按仓内惯例（动态 `import()` 或经 `state.js` 注入访问器）收敛。
3. **`toggle-green-revert` 未跑通**：需隔离实例前置，本批未起；如需完整覆盖，另起隔离实例批补跑。
4. **`nfAdaptApi.js` 是新增对外契约面**：下游（推断为 zhang 自己实例的 `ui/js/nf-adapt.js`）依赖 `window.__nfAdaptApi`；我们后续改 web 入口面时须把它当兼容面对待（调研 §1.3 风险 ④，本批只登记不设闸）。
5. **zhang 直推 main 的流程风险未变**：调研 §2 建议的分支纪律 / protected branch 仍待作者裁（本批只做技术合流）。
6. **win32 基准未在本机复核**：平台不可比（按令留给 runner 腿），本机无法验证其有效性。

## 7. 方法与存证

- **三树对照法**：每条红都在 `pristine`（`7d18dabb0`，`git archive` 解包）与需要时 `base`（`f8bd1f0d4`）上重跑，逐字比对输出后再下「存量 / 新引入」判定；check-circular 与 locale 键集用 `diff`/键集差集做同法对账。
- **spec 串行跑**：`PORT` 固定端口的自足 spec 不可并行（仓内既有训诫），本批单进程顺序执行 20 条。
- **文档库校验**：`check_wiki_docs.py --root docs` 收尾为 `通过（113 篇文档，零违规）`——顺手补齐了 17:25 批文档的一处 R3 首现未释（「样张」链到 `[[glossary#^mockup-sample]]`）与孤儿链（补两条正文引用），属文档库面卫生，不改任何批结论。
- 证据：`evidence/20261101_175329_ustc-zhang-merge__delegate-nebflow-eed3ad45/`（`checkjs-before/after.txt`、`circular.txt` + `circular-PRE.txt`、`sbt-seed-summary.txt`、`spec-results.txt`、`attribution/` 六份对照日志）。

## 相关件

- [[20261101_172033_ustc-git-survey__kernel]]——本批前置调研（分叉全景、zhang 10 条逐件、撞车 6 文件、更名 kaiyu/nebflow→stand）。
- [[20261101_151034_ustc-cicd-migration-assessment__delegate-nebflow-7f8e8dec]]——USTC 平台/CI/发版迁移评估（runner 腿与视觉基线归属的背景）。
