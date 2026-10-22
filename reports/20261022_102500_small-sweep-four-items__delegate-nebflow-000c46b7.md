# 小扫尾批 · 四件（2026-10-22）__delegate-nebflow-000c46b7

昨晚各批遗留的零碎收口四件，全落 main。逐件证据如下。

## ① checkJs baseline 更新 — `--update` 入账，gate 恢复干净基线

- **改前实测**（`node scripts/check-js-types.mjs`）：gate FAIL，新文件报错 **5 个**（非批令
  所列 3 个——`delegateChainProvider.js`/`delegatePanoramaView.js` 来自昨晚更晚合入的
  delegate 视图批 a5fb3e7b3/d24ee9fd6，晚于批令起草）：
  `chat/historyReplay.js` 2×TS2304、`delegateChainProvider.js` 1×TS2339、
  `delegatePanoramaView.js` 5×TS2339、`scrollUnderGlass.js` 1×TS2339、
  `socialPanel.js` 7×TS2339（合计 16 错，均先前批次合入未进 baseline）。
- **动作**：待 ② 的代码改动落定后执行 `node scripts/check-js-types.mjs --update`
  （冻结基线对应终态树）——baseline **273 → 257**（只降不升 ✓，257 errors / 38 files）。
- **复跑**：`checkJs PASS: no new errors above baseline.`（exit 0）。
- 凭证：`tests/type-baseline.json` diff（20+/12-）；secretsPanel.js 重构后仍零错误
  （不在基线内=干净文件，重构未引入新错，改前改后两轮 gate 均无该文件条目）。

## ② secretsPanel.js EYE_SVG 收敛 — providersUi 单源 + 渲染像素级对照

- **改动**（`src/main/resources/web/js/secretsPanel.js`，净 ±12 行）：私有
  `EYE_SVG`/`EYE_OFF_SVG`（lucide v0.454 眼形）退役 → `import { eyeSvg, eyeOffSvg }
  from './sidebar/providersUi.js'`；三个使用点换源（eyeFace 掩码面/行模板/新建草稿眼）。
  无环：providersUi 依赖 state/ws/i18n/providerModels(→branding)，均不回指本面板；
  secretsPanel 仅 main.js 顶层装配。chat.js（2026-10-21 设计语言批）与 sidebar.js 已在
  单源上，本批为最后一块私有副本。
- **验证**（新脚本 `tests/secrets-eye-converge-shots.mjs`，before/after 双轮 +
  亮暗双主题；证据 `evidence/20261022_secrets-eye-converge/`，README 有全表）：
  - after **22/22 PASS**：行眼/草稿眼/AskUser 卡眼三方 innerHTML 逐字节互等且等于
    providersUi 导出（DOM 归一化口径，脚本内注明 `<path/>`→`<path></path>` 序列化
    展开）；揭示一次 GET/重掩码烧回/草稿纯表现层切换/aria-pressed 语义零变化；
    console 零错误（8 条 mock 夹具 /api/ 404 列名静噪）。
  - before（HEAD 私有副本对拍）恰 12 条「≠单源」FAIL、功能断言全 PASS——改前状态诚实存证。
  - 像素对照：4 张 16px 眼特写 before/after 逐一不同（唯一预期视觉变化 lucide→feather）；
    **AskUser 卡 before/after 逐字节相同**（sha256 light `b2b63a39…` / dark `c845f21e…`）
    —— chat.js 面零附带损伤。
  - 面板族回归：design-language-shots.mjs 复跑 eye-toggle=ok / answer-gate=ok 亮暗双主题。
  - 生产 bundle：`npm run build` PASS（177 sources, orphans: 0）。

## ③ pilot workflow 形式闭环 — `gh workflow run` 真跑绿，零托管分钟

- **前置发现**：`ci-selfhosted.yml` 在本地 main 领先 origin 的 21 个提交内（origin/main
  停在 02f7626e1 beta.1 bump），GitHub 默认分支上无此 workflow——首次触发 HTTP 404
  （`workflow ci-selfhosted.yml not found on the default branch`）。使能步：
  `git push origin main`（02f7626e1..a5fb3e7b3，同一积压口径）。
- **触发**：`gh workflow run ci-selfhosted.yml --ref main` →
  run **39709267510**（headSha a5fb3e7b3），
  https://github.com/MashiroKai/Nebflow/actions/runs/39709267510
- **执行宿主**：runner `Mashiros-MacBook-Pro`（labels: self-hosted, macOS, ARM64，
  触发时 status=online/busy=false）——workflow_dispatch 手动触发 + 自托管 runner，
  **GitHub 托管分钟消耗 = 0**。
- **结果**：**GREEN**（`gh run watch --exit-status` exit 0）——web bundle（npm ci +
  build-web）✓、friends release-strip 双形态闸 ✓、Aliyun sbt-launch 自举 ✓、
  `sbt assembly` ✓、jar+sha256 断言 ✓。唯二注记为 actions node20 deprecation 警告
  （checkout@v4/setup-java@v4，非阻塞）。

## ④ USTC 补推 — main + v2026.10.21-beta.2 tag → dev 远端

- **状态实测**：dev（git.ustc.edu.cn:kaiyu/nebflow）main 停在 02f7626e1，本地 main
  领先 21 提交（昨晚各批）；tag `v2026.10.21-beta.2`（→1ab024df2，main 祖先）在
  origin 已有、**dev 缺**。
- **执行**：按合并队列纪律，本批（①②）先落 main，合并落地后执行
  `git push dev main` + `git push dev v2026.10.21-beta.2`——一次推平昨晚积压 + 本批，
  推送凭证（sha 区间/tag ref）见本批会话最终报告。
- 作者放行依据：2026-10-22 晨「候派的批直接派发」涵盖候令补推。
