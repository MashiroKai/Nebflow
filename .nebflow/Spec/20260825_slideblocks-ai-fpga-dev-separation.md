> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# slideblocks 平台代码与 ai-fpga-dev（FPGA deck 内容）工作混线——区分方案

> 调查日期：2026-08-25 ｜ 性质：一次性诊断（只调查分析，未修改任何文件）
> 调查人：Explorer（独立调查）｜ 数据基准：调查时点 git 状态

---

## 0. 一句话结论

`decks/ai-fpga-dev` 是**用户个人的 64 页 FPGA 讲座 deck（内容），不是 slideblocks 平台代码**，但它从 08-18 起被塞进 slideblocks 平台仓库当作「Slidev offline 导出路由 + workbench skill 测试载体」，后续 v2/64 页重建/08-25 redo 全在平台仓库分支上开发——这是混线根因。**deck 源工程应整体迁出到 html-deck-studio（正式线）或独立 deck repo，slideblocks 仓库只保留平台代码 + 平台 demo decks。**

---

## 1. 现状盘点（四条线 + 一条僵尸线）

![拓扑图](/tmp/sb-invest/topo.svg)

### 线 A：slideblocks 平台仓库（真正的平台代码）

| 项 | 值 |
|---|---|
| 主 worktree | `/Users/dev/.nebflow/projects/slideblocks` |
| 分支/HEAD | `fix/deck-workbench` @ **c51d9c1**（本地领先 origin 4 提交，未 push） |
| 副 worktree | `/Users/dev/.nebflow/projects/slideblocks-deck` @ `feat/visual-redesign-p1` @ **5d3a052**（领先 origin 21 提交） |
| remote | `origin = github.com/UniUni2000/slideblocks.git`（两 worktree 共享） |
| 平台代码 | `apps/`（web）、`packages/`、`registry/`（blocks/families/prompts/recipes）、`scripts/`、`skills/slideblocks/`（skill + build-offline.mjs + workbench/context-menu）、`docs/`、`infra/` |
| 平台 demo decks（合理存在） | `decks/macbook-pro-m5-launch`、`decks/sgr-a-discovery`、`decks/glacier-thanks-slidev`（1 页 block 演示）、`decks/workbench-verify`（workbench 运行时测试 deck） |
| **混入内容** | `decks/ai-fpga-dev/`（详见 §1.5） |

> 关键事实：`slideblocks` 与 `slideblocks-deck` 是**同一个 repo 的两个 worktree**（`git worktree list` 确认），不是两个仓库。

### 线 B：html-deck-studio 正式交付线

| 项 | 值 |
|---|---|
| 仓库 | `/Users/dev/.nebflow/projects/html-deck-studio`（**无 remote，纯本地**） |
| HEAD | `a63740e`（08-24 21:58「write back 64-page fpga-agent deck offline.html as ppt/index.html」） |
| 内容 | `ai-fpga-deck/ppt/index.html`（7.6MB 单文件交付物）、`ppt.md`（大纲）、`ppt/images/`、`qa/`（报告 + 截图）、`shots/`、`ai-fpga-deck.pdf`、`slide-source.md`、`slide-structure*.md`、`style-decision.html`、`scripts/export_deck_pdf.py` |
| 当前状态 | 16-worker 并行重做**进行中**（磁盘上尚无新内容文件；工作树仅 ppt.md / qa/report-v2-recheck.md 改动 + report-contract.md 未跟踪） |
| git 体积 | `.git` 64MB（tracked 二进制 114 个，含 7.6MB index.html 各版本 + PDF + 截图） |

### 线 C：deck 规划文档（权威结构，不在任何 repo 内）

| 文件 | 时间 | 角色 |
|---|---|---|
| `~/.nebflow/docs/Presentations/fpga-agent-deck-plan.md` | 08-24 18:07 | content-planner 阶段 1（素材+每页内容规划） |
| `~/.nebflow/docs/Presentations/20260824_fpga-agent-deck-plan.md` | 08-24 21:16 | **visual-designer 合并终稿：64 页 · P01-P64 · 12 章 Ch0-Ch12 · 90min**——供 html-deck-studio 制作与 verifier 校验 |
| `visual-proposal.html` | — | 视觉提案 |

### 线 D：僵尸旧环境（建议清理，先不动）

`~/.zcode/workspace/default/ai-fpga-slidev`（port **3047** 的 slidev dev server 自 7/26 运行至今）——更早的 deck 开发副本，与现线无关。

### 线 E（核心调查对象）：`decks/ai-fpga-dev` —— 混入 slideblocks 的 deck 内容

《如何使用 Agent 为 FPGA 开发、科研提速》64 页瑞士风 deck（柠檬绿 `#C5E803`、全浅色、零暗页；Slidev 52 + workbench runtime）。`decks/README.md` 登记的 demo decks **不含它**——它是用户个人 90 分钟讲座，不属于平台产品。

| 子项 | 内容性质 |
|---|---|
| `slides.md`（96KB，3023 行改动） | deck 内容（64 页正文，`<!-- slideblocks-page: P01..P64 -->` 分页标注） |
| `style.css` / `global-top.vue` / `global-bottom.vue` / `setup/` | **deck 级**样式与运行时扩展（全球组件属于 Slidev deck 自身，非平台组件；platform 版本在 `skills/slideblocks/assets/workbench/`） |
| `.slideblocks/`（build-offline.mjs、smoke-64.mjs、shot-all-64.mjs、verify-pages.mjs、page-plan.md、execution-lock.json、qa-report.md、parts/chX-Y.md…） | deck 本地工具与契约（platform 版工具在 `skills/slideblocks/assets/`，deck 内为副本/专用） |
| `public/images/` + `qa/after-v3/page-01..64.png` | deck 素材 + QA 截图（**83 个 tracked 二进制**） |
| `offline.html`（7.6MB） | 构建产物（**gitignored，未 tracked**） |
| **未提交 08-25 redo**（工作树） | `global-top.vue`、`style.css` 已修改；`parts/`（p01-04…p45-48 共 11 文件，**缺 p33-36、p49-64，redo 未完成**）+ 10 张新图未跟踪 |

---

## 2. 四提交（f25389f→c51d9c1）逐条定性

全部作者 **MashiroKai**（用户 git 身份），全部 08-24 21:57 批量提交，**全部只改 `decks/ai-fpga-dev/` 路径**——100% deck 内容，无一属于平台改进：

| 提交 | 改动 | 定性 |
|---|---|---|
| `f25389f` feat(ai-fpga-dev): rebuild as 64-page deck | slides.md 重写 3023 行、style.css +296、`.slideblocks/parts/ch0-4/5-8/9-12.md`（拼接源）、README | **deck 内容**（39 页→64 页重建） |
| `6c344a6` docs: update project contract | execution-lock/page-plan/qa-report/source-map/workflow-state 5 件套 | **deck 契约文档**（deck 内 .slideblocks 状态） |
| `2b71572` feat: add reused evidence assets | `03-attention-figure1.png`、`20-bilibili-cover.jpg` | **deck 素材** |
| `c51d9c1` test: add 64-page smoke/shot/verify scripts | smoke-64.mjs / shot-all-64.mjs / verify-pages.mjs + **qa/after-v3/page-01..64.png 64 张截图**（67 files） | **deck 专用测试脚本 + QA 产物**（脚本是 deck 内 .slideblocks 副本，非平台脚本） |

**与 html-deck-studio 的关系——是同一 deck 的两端，不是两份独立 deck：**
`html-deck-studio/ai-fpga-deck/ppt/index.html` 与 `slideblocks/decks/ai-fpga-dev/offline.html` **md5 完全一致（`fd78b796466605c44ae50d63e118b7de`）**。即：slideblocks 的 Slidev 源 → `build:offline` → offline.html → 08-24 21:56 回写为 html-deck-studio 的 ppt/index.html（提交 a63740e）。README 亦明示「PDF export: python3 html-deck-studio/scripts/export_deck_pdf.py → html-deck-studio/ai-fpga-deck/ai-fpga-deck.pdf」——**html-deck-studio 是 slideblocks 构建产物的正式接收方/交付线**。

---

## 3. 08-25 redo 未提交精修——归属哪一层

- `global-top.vue`：**deck 级**（ASCII 呼吸场 canvas 驱动、sb-played 首播制动画控制）——这是 deck 自己加的特效，不是 slideblocks 平台组件。
- `style.css`：**deck 级**（`2026-08-25 redo` 注释——三阶全浅色节奏 tone-accent/accent-panel + ascii-bg 样式）。
- `parts/`：**deck 内容分页**（新分页方案 `p01-04.md…`，每 4 页一块；与已提交的 `.slideblocks/parts/ch0-4.md` 是**两套并行分页方案**，新 parts/ 未接入 slides.md——grep 无引用，redo 进行中）。
- 笔迹：无 git 作者（未提交）。文件 mtime **08-25 08:26–08:30**（早于调查时点 08:43），内容为英文设计注释风格——08-24 21:57 四提交同一脉（workbench 线）在当日早晨的续作。
- **与用户背景不符的一处**：任务描述说 redo「已 stash 暂存」，实际 **git stash list 为空（仅一条无关的 `DocsLayout uncommitted (pre-existing)`，在 feat/visual-redesign-p1 上）**；redo 改动**仍在 working tree**，且**未完成**（parts 缺 p33-36/p49-64）。→ 迁移前必须先保全。

---

## 4. 混线根因（历史路径）

1. **08-18 `89fd407`**：`feat(decks): add ai-fpga-dev deck sources — Slidev offline export route (html-deck-studio)` —— deck 源**首次进入 slideblocks 仓库**，理由是需要平台 Slidev 基础设施（仓库根 node_modules、build-offline.mjs 单文件导出、workbench runtime）。
2. **08-18~08-22（feat/visual-redesign-p1）**：deck 内容开发（a5c9422 用户精修 → fba2cf3 all-light 定稿 → 3967e43 运行时交互 → 836e41c 截图工具 → v2 39 页三连 a0b4bdb/6e929cd/3eba65a → 退修 27a253d/ec3db02 → 契约 5e28d41/6ebac0f）**全部作为平台仓库分支的提交**。deck 同时兼任 workbench skill 的开发/验证载体。
3. **08-24 `ad9fb60` merge**：feat/visual-redesign-p1（21 提交）并入 `fix/deck-workbench` 主脉；随后 **4 个 deck 内容提交**（64 页重建）直接落在该分支（未 push）。
4. **08-25 08:26**：worktree 上继续做 redo（ASCII 场 + 三阶节奏 + parts 分页），仍未提交。
5. **同一 deck 的正式线**：html-deck-studio（独立 repo）同时维护大纲/QA/交付物，并接收 slideblocks 的构建产物——两线通过**构建产物耦合**而非 git 耦合，任何一侧改动不会自动同步。

**本质**：平台把用户个人 deck 当作「导出路由 + skill 测试床」，deck 内容开发长期寄生在平台 repo 分支上；而正式交付线（html-deck-studio）只有产物、没有源，导致源无法脱离平台仓库独立演化。叠加用户 08-20 裁定「repo 按项目分开」，`decks/ai-fpga-dev` 明显违反该裁定。

---

## 5. 归属清单（文件/提交级）

### 5.1 留在 slideblocks repo（平台）
- `apps/ packages/ registry/ scripts/ skills/slideblocks/ docs/ infra/` 全部平台代码
- `decks/README.md` + 平台 demo decks：`macbook-pro-m5-launch/`、`sgr-a-discovery/`、`glacier-thanks-slidev/`、`workbench-verify/`
- 平台工具本体：`skills/slideblocks/assets/build-offline.mjs`、`skills/slideblocks/assets/workbench/`（deck 内 `.slideblocks/` 为副本，不随平台走）

### 5.2 迁出 → html-deck-studio（或独立 deck repo）——`decks/ai-fpga-dev/` 全部
- **四提交全部内容**：f25389f / 6c344a6 / 2b71572 / c51d9c1（slides.md、style.css、global-top/bottom.vue、setup/、.slideblocks/ 全套、public/images/、qa/、README、package.json）
- **08-25 redo 未提交改动**：global-top.vue、style.css 修改 + parts/ + 10 张新图（先保全再迁移）
- 迁移后 slideblocks 侧 `decks/ai-fpga-dev/` 整体删除，分支历史中保留（不 push 4 提交即可，或用 `git rm` 清理分支）

### 5.3 重复需去重
| 重复项 | 处理 |
|---|---|
| `ppt/index.html` vs `offline.html`（md5 相同） | 构建产物二选一持有。建议：**源**（Slidev + 脚本）入 deck repo，产物按需构建；交付线保留一个 tracked 版本即可（或 git-lfs） |
| `.slideblocks/parts/chX-Y.md`（已提交） vs `parts/pXX-YY.md`（未提交新方案） | 两套分页方案并存——**需用户/制作方裁定用哪套**，另一套删除 |
| `qa/after-v3/page-01..64.png`（64 张） vs html-deck-studio `qa/after-v2` | 构建产物/截图基线，建议移出 git（git-lfs 或忽略），或只保留最终验收基线 |
| `~/.zcode/workspace/default/ai-fpga-slidev`（port 3047） | 僵尸旧副本，确认无用后清理（**需用户确认**） |

### 5.4 误放内容（第二处混线）
`html-deck-studio/ai-fpga-deck/ppt.md` **工作树版本第 1-35 行是 Nebflow 平台待办反馈**（任务工具优化/登录测试/官网发布流程等），与 deck 无关；deck 大纲在第 37 行后才开始（08-24 新「大改」方向）。原大纲（a63740e 版本）被覆盖。→ Nebflow 反馈应移回 Nebflow 文档体系；ppt.md 只留 deck 大纲。

---

## 6. 区分实施步骤（git 层面）+ 验收条件

> 前置决策（需用户裁定，见 §7）：**权威线 = html-deck-studio**（用户背景已明确「正式 deck 线」，本方案按此执行）。

### Step 0 —— 保全（最先做，防丢）
在 slideblocks worktree（fix/deck-workbench）把 08-25 redo 落盘到安全处：
```bash
cd /Users/dev/.nebflow/projects/slideblocks
git add decks/ai-fpga-dev/  # 含未跟踪 parts/ 与图片
git stash push -m "ai-fpga-dev 08-25 redo 保全"   # 或 commit 到临时分支 deck-redo-wip
```
**验收**：stash/分支可见；`git stash list` 或临时分支含 global-top.vue/style.css/parts/ 全部改动。

### Step 1 —— 迁出源工程到 html-deck-studio
方式 A（推荐，保历史）：`git format-patch` 四提交 → 在 html-deck-studio 建 `decks/ai-fpga-dev`（或 `ai-fpga-deck/slidev/`）→ `git am`；随后应用 Step 0 保全的 redo。
方式 B（轻量）：`git subtree split --prefix=decks/ai-fpga-dev` 后在新 repo `git subtree add`。
二进制（qa 截图 64 张、offline.html）按 §5.3 决定：**建议 qa/ 与 offline.html 进 .gitignore/git-lfs，只迁源与素材**。
**验收**：html-deck-studio 中 `npm ci && npm run build:offline` 成功产出 offline.html；`node .slideblocks/smoke-64.mjs` 通过；页面数 = 64。

### Step 2 —— 清理 slideblocks 分支
```bash
cd /Users/dev/.nebflow/projects/slideblocks
git rm -r decks/ai-fpga-dev
git commit -m "chore(decks): move ai-fpga-dev deck source out to html-deck-studio (per-project repo policy)"
git push origin fix/deck-workbench
```
（4 个 deck 内容提交保留在本地历史即可，不必 push；也可 `git rebase --onto` 摘除，但**不建议**——历史保留无害，仅确认不 push。）
**验收**：`git ls-files | grep ai-fpga` 为空；`git log origin/fix/deck-workbench..HEAD` 只含清理提交。

### Step 3 —— 处理副 worktree（slideblocks-deck @ feat/visual-redesign-p1）
该分支 21 个本地提交**已并入 ad9fb60**（merge 时保留本地），其 `decks/ai-fpga-dev` 为 39 页旧版。建议：
```bash
cd /Users/dev/.nebflow/projects/slideblocks-deck
# 确认无独有内容后：git checkout -B feat/visual-redesign-p1 archive/visual-redesign-p1 或直接删除分支
```
**验收**：worktree 移除或归档；`git worktree list` 干净；`:3030` dev server 指向新位置或已停。

### Step 4 —— 修复 :3030 dev server 与僵尸环境
当前 `:3030`（PID 40694）**实际跑在 slideblocks worktree**（fix/deck-workbench 的 deck，含未提交 redo）——用户背景「slideblocks-deck 是 :3030 源工程」已过时。迁移后 dev server 应改从新 deck repo 起（`npm run dev -- --port 3030`）。port 3047 僵尸进程与用户确认后清理。
**验收**：`lsof -iTCP:3030` 的 cwd/node_modules 路径指向新 repo；`curl http://localhost:3030` 200。

### Step 5 —— 修复 html-deck-studio 的误放内容
- `ppt.md`：Nebflow 平台反馈（1-35 行）移出，恢复/保留 deck 大纲（08-24 新「大改」方向即为 16-worker 重做依据）。
**验收**：`ppt.md` 无「任务工具需要优化/登录功能」等平台条目；大纲与 `20260824_fpga-agent-deck-plan.md` 章节对齐。

### 冒烟测试（总验收第一项）
1. 新 deck repo（html-deck-studio 内）：`npm ci` → `npm run dev` 启动 → `curl http://localhost:3030` 200 → Playwright 打开首屏无 console error。
2. `npm run build:offline` → offline.html 生成 → 与迁移前产物对比页面数/首屏（64 页断言）。
3. slideblocks：`git ls-files` 无 ai-fpga 残留；`npm run build`（平台本体）通过；demo decks（macbook/sgr）仍可构建。
4. 16-worker 重做交付后：`ppt/index.html` 更新为新产物并提交（若重做基于大纲独立制作，则旧 md5 对等关系解除属预期，需人工确认新视觉）。

---

## 7. 风险与建议（哪些先不动、哪些需用户确认）

| # | 风险/事项 | 建议 |
|---|---|---|
| 1 | **08-25 redo 未完成未提交**（parts 缺 p33-36/p49-64，工作树非 stash） | **最先保全（Step 0），且不要丢弃**——它可能是 16-worker 重做之外的另一版实现，需用户决定去留 |
| 2 | **两套分页方案并存**（.slideblocks/parts/chX-Y vs parts/pXX-YY）+ 柠檬绿全浅色（既有契约）vs 计划文档 IKB 蓝建议 | **需用户裁定**：权威视觉方向、保留哪套分页；计划文档风格为「待用户确认」，契约 QA（report-contract.md）已按「全浅色/柠檬绿/无黑底」执行——以契约为准即可，但 redo 的 ASCII 场/三阶节奏是否纳入正式线需确认 |
| 3 | **16-worker 重做进行中**（磁盘尚无产出，方向=ppt.md 新大纲「从 Transformer 讲起、砍 AI 历史」） | 迁移**不要在重做关键期打断**：先保全、定方案，重做交付后再执行 Step 2-3；或与 html-deck-studio 沟通节奏 |
| 4 | **feat/visual-redesign-p1 21 个本地提交**（未 push，已 merge） | 删分支前确认无独有内容（git log 比对已做过：merge 整合时 favor local） |
| 5 | **二进制体积**：slideblocks .git 77MB / html-deck-studio 64MB（tracked 二进制 83+114 个） | 迁移时对 qa 截图/产物启用 .gitignore 或 git-lfs；offline.html 7.6MB 不要双仓 tracked |
| 6 | `:3030` dev server 正在跑 fix/deck-workbench 的 deck | Step 2 删除 decks/ai-fpga-dev 会打断它——先停/迁移 dev server 再删 |
| 7 | 僵尸 `~/.zcode/workspace/default/ai-fpga-slidev`（port 3047，7/26 起） | 需用户确认后清理 |
| 8 | html-deck-studio 无 remote | 若正式线需备份/协作，建议补 remote（如 github 私有仓）——**需用户裁定** |
| 9 | 平台 workbench skill **不依赖** ai-fpga-dev（grep 零引用；平台测试用 workbench-verify） | 迁出对平台**零功能影响**——可放心执行 |

**明确先不动的**：slideblocks 平台代码本体、平台 demo decks、html-deck-studio 已交付产物（重做中）、计划文档。
**需用户确认的**：① 权威视觉方向（柠檬绿全浅色契约 vs 计划 IKB 蓝）；② 两套分页方案去留；③ 16-worker 重做与 slideblocks 64 页重建的关系（是否后者停摆、统一到 html-deck-studio 制作）；④ html-deck-studio 是否补 remote；⑤ 僵尸环境清理。

---

## 8. 附：调查证据索引

| 证据 | 位置 |
|---|---|
| 两 worktree 同 repo | `git worktree list`（slideblocks / slideblocks-deck） |
| 四提交 stat/作者/时间 | `git show --stat f25389f 6c344a6 2b71572 c51d9c1`（全 decks/ai-fpga-dev 路径） |
| 四提交未 push | `origin/fix/deck-workbench` @ ad9fb60；`git log origin/fix/deck-workbench..HEAD` = 4 提交 |
| 产物同一 | md5 `fd78b796466605c44ae50d63e118b7de`（slideblocks offline.html = html-deck-studio ppt/index.html） |
| redo 未提交非 stash | slideblocks `git status --short`（2 M + 11 ??）；`git stash list` 仅 DocsLayout |
| redo 不完整 | `parts/` 缺 p33-36、p49-64（`ls -V`） |
| 根因提交 | `89fd407 feat(decks): add ai-fpga-dev deck sources — Slidev offline export route (html-deck-studio)` |
| 平台不依赖 | `grep -rn ai-fpga skills/ scripts/ packages/ apps/` = 零引用 |
| dev server 实际位置 | `lsof -iTCP:3030` → PID 40694 `.../slideblocks/node_modules/.bin/slidev` |
| 契约风格 | html-deck-studio `qa/report-contract.md`：「瑞士风铁律（全浅色/柠檬绿/无黑底）」 |
| 误放内容 | html-deck-studio `ppt.md` 1-35 行 = Nebflow 平台反馈；37 行起 = deck 新大纲 |
| 计划权威 | `~/.nebflow/docs/Presentations/20260824_fpga-agent-deck-plan.md`（64 页·Ch0-Ch12） |

---

# v2 执行细案（2026-08-28）

> 修订人：Frontend（slideblocks team）｜ 性质：执行细案（本轮零迁移动作，仅文档+只读调研）
> 基准：slideblocks `fix/deck-workbench` @ **dd24a0f**（69 页终版）；html-deck-studio `main` @ **2ffff61**
> 前置状态：作者红线落定（deck 内容永不进平台公开仓）；PR #5 历史已清洗（ee2e333），公开仓 decks/ 仅 README+macbook+sgr

## v2.0 与 v1 的差异（按现状修订，v1 哪些已过时）

| v1 假设 | 现状（v2 基准） |
|---|---|
| 64 页 deck，redo 未提交未保全（Step 0） | **69 页终版**（P12 拆子页），redo 已全部提交进 fix/deck-workbench 历史——本地分支即保全，无需 Step 0 |
| 16-worker 重做进行中、ppt.md 误放待修 | 五批 planner 交付完成（batch4/5 report+台账落盘），69 页终版已回写 html-deck-studio（2ffff61，43.9MB index.html，Export PDF 二轮修复版 c5a2a7d 产物） |
| 迁移方式 A/B（format-patch/subtree 保历史） | **作者已定：git 历史不带**——整体文件拷贝+新仓基线 commit，方式 A/B 作废 |
| 平台零依赖仅 grep 推断 | **实证**：platform/codex-pr（无 ai-fpga-dev 全量剔除形态）skill:test 132/132 + build 92 页全通（PR #5 验收） |
| 64 页时代工具面 | 新增：碰撞断言链（text-collision-audit.mjs / pixel-same-source.py / pixel-diff-*.py / measure-figarea.mjs / probe-*.mjs）、Export PDF 验证链（verify-pdf-export.mjs / compare-pdf-pages.py）、build-parts/B01-B19 工单、pilot-out 产物面、ASSET_SOURCES.md 同源表 |

## v2.1 迁移对象清单（@dd24a0f 实测）

**366 个 tracked 文件 / 磁盘 88MB**（含 qa 截图+pilot-out+public 资产二进制）。

| 组件 | 性质 | 随迁 |
|---|---|---|
| slides.md / style.css | deck 内容本体（69 页） | ✅ |
| global-top.vue / global-bottom.vue | **deck 定制版**（首播动画+ASCII 场，与 canonical 无关，禁覆盖） | ✅ |
| setup/（context-menu.ts / export-pdf.ts / shortcuts.ts / vite-plugins.ts） | 与 skill canonical **逐字节相同**（08-28 diff 实证） | ✅ |
| .slideblocks/ 工具（build-offline.mjs / verify-runtime.mjs / verify-pdf-export.mjs / compare-pdf-pages.py / screenshot-all.mjs / text-collision-audit.mjs / pixel-*.py / measure-* / probe-* / smoke-* / shot-* / legibility-check 等） | deck 专用契约脚本；build-offline+verify-runtime 与 canonical 逐字节相同 | ✅ |
| .slideblocks/ 内容 team 产物（execution-lock.json / page-plan.md / qa-report.md / source-map.md / workflow-state.json / layout-realization.md / parts/） | deck 工程状态 | ✅ |
| build-parts/B01-B19（69 页构建工单） | 内容 team 工作产物 | ✅ |
| public/（images+pilot 图）/ qa/（截图基线+报告）/ pilot-out/（碰撞基线+对比图） | 素材与 QA 产物 | ✅ |
| tools/redraw-p54-p55.py | 素材重绘脚本（含绝对路径，见 v2.3-7） | ✅ |
| ASSET_SOURCES.md / README.md / package.json | 同源表/说明/依赖声明 | ✅ |
| node_modules/（仅 .slidev 缓存）+ offline.html（7.6MB 构建产物） | **untracked，不带**——产物唯一权威=ai-fpga-deck/ppt/index.html 回写机制 | ❌ |

## v2.2 目录设计（落位推荐）

**推荐：`ai-fpga-deck-src/`（扁平成对）**——与现有交付目录 `ai-fpga-deck/` 字母相邻、源工程 vs 交付产物关系一目了然：

```
html-deck-studio/
  ai-fpga-deck-src/    ← 迁入：Slidev 源工程（366 文件基线 commit）
  ai-fpga-deck/        ← 现状不动：交付产物（ppt/index.html 43.9MB 回写目标 + qa/ + planning-figures/）
  scripts/             ← 现状不动：仓级共享工具（export_deck_pdf.py / probe_deck.py）
```

- 理由：html-deck-studio 现状是 ai-fpga 单项目仓（无其他 deck），`decks/` 顶层命名空间是平台仓语义，引入无必要；未来真成多 deck 仓再重组不迟。备选 `decks/ai-fpga-dev/`（与 slideblocks 侧路径同形），两者引用改写量相当（实测仅 3 处仓根相对引用），不推荐但不反对。
- package.json name 由 `slideblocks-ai-fpga-dev-deck` 改 `ai-fpga-deck-src`（去平台前缀）。
- html-deck-studio .gitignore 增补：`ai-fpga-deck-src/node_modules/`、`ai-fpga-deck-src/offline.html`（node_modules/ 已被根规则覆盖，补产物规则即可）。
- 迁入前其 Manager 先处置工作树杂项：`ai-fpga-deck/ppt/index(2).html`（untracked 遗留副本）+ `M .DS_Store`。

## v2.3 引用适配清单（逐项，核心）

1. **dev server**：daemons.json 实测**无 deck 条目**（全量 4 条：slideblocks:4321 / Jarvis:7861 / nebflow-website:3000 / czt:8765）——3030 历来是 run_in_background 手工起，当前端口空闲。迁后从新路径手工起（`npm run dev -- --port 3030`），**零 daemon 配置改动**。简报假设「daemons.json cwd 指向 slideblocks-deck worktree」证伪。
2. **node_modules / 依赖**：现状=npm workspaces（根 `decks/*`）hoist 到 slideblocks 根（1.1G），deck 本地 node_modules 仅 `.slidev` 缓存。迁后策略=**独立 npm install**——脱钩即迁移目的本身，symlink 借用是换形态的耦合，否。版本对齐=从 slideblocks package-lock 抄 6 个精确版本 pin 进 package.json（@slidev/cli / @slidev/theme-default / katex / vue / playwright-chromium / vite-plugin-singlefile）；`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`（浏览器缓存 ~/Library/Caches/ms-playwright 全局共享已装）。体积预估 300-500MB。**补注（08-28 迁移实测教训）**：动态 import 依赖面须 grep 全量——Export PDF 的 html2canvas/jspdf 原靠根 package.json workspaces hoist、deck 未声明，独立 install 后 dev 500 被抓现形（已补录 pin 进 commit d1ad65c）。pin 清单类工作「6 个已知项」不等于依赖全集，以运行时验证兜底。
3. **skill 引用（副本归属与更新通路）**：setup/ 4 文件 + .slideblocks/{build-offline,verify-runtime}.mjs 共 6 文件与 canonical（skills/slideblocks/assets/）**逐字节相同**（08-28 diff 实证，迁移同步基线天然成立）。canonical 主权留 slideblocks；deck 副本=导出快照随迁。更新通路：canonical 变更 → 拷贝到 deck → diff 校验 → deck QA（verify-runtime）重跑。global-top.vue 是 deck 定制版，**永不从 canonical 同步**。
4. **构建脚本路径假设**：build-offline.mjs 自含（projectRoot=deck 目录、stateRoot=.slideblocks/）；`.slideblocks/*.mjs` / `setup/*.ts` / `tools/*.py` 的 `../` 父级引用 **grep 零命中**——node 模块解析走向上查找，新位置由本地 node_modules 接管，**无需改码**。
5. **QA 工具归属**：全部随迁（verify-pdf-export / compare-pdf-pages / screenshot-all / text-collision-audit / pixel-*.py / measure-* / probe-* / smoke-* / shot-*）——均为 deck 专用契约脚本，非平台脚本；canonical 侧 build-offline/verify-runtime 保留（平台 demo decks macbook/sgr 自用，契约测试硬编码遍历）。
6. **内容 team 产物**：execution-lock.json / page-plan.md / qa-report.md / source-map.md / workflow-state.json / layout-realization.md / parts/ 全套随迁——是 deck 工程状态，离开即失忆。
7. **绝对路径改写清单（4 文件实测）**：
   - `tools/redraw-p54-p55.py`：2 处 savefig 指向 slideblocks deck public/images → 改新落位
   - `.slideblocks/pixel-same-source.py` / `pixel-diff-heat.py` / `pixel-diff-hist.py`：SRC 指向 `html-deck-studio/ai-fpga-deck/planning-figures/pilot`——**迁后变同仓引用，planning-figures 不动则字面值不变、零改写**（新增发现）
8. **仓根相对引用（3 处实测）**：`.slideblocks/layout-realization.md`（文档）+ `tools/redraw-p54-p55.py` 内 "decks/ai-fpga-dev" → 按新落位改写。
9. **README 交叉引用**：deck README 提及 html-deck-studio scripts/export_deck_pdf.py——迁后同仓，改为仓内相对描述。
10. **回写通路变更**：build:offline 产物回写 `ai-fpga-deck/ppt/index.html` 由跨仓 cp 变仓内 cp（路径缩短，机制不变）。

## v2.4 两端验证标准

**slideblocks 端**（迁移收尾）：
- `git rm -r decks/ai-fpga-dev` 本地 commit（fix/deck-workbench，**永不 push 上游**——公开历史已清洗，此删除只影响本地工作线）
- `git ls-files | grep ai-fpga` 为空；build 92 页全通 + skill:test 132/132（platform/codex-pr 已实证同形态）
- slideblocks-deck 旧 worktree（@5d3a052，持 39 页旧版 deck）处置：确认无独有内容后归档或删（沿用 v1 Step 3）

**html-deck-studio 端**（迁入验收）：
- `npm install` 成功（pin 版本与 slideblocks package-lock 对齐抽核）
- `npm run dev -- --port 3030` 起 → 首屏 Playwright 无 console error
- `npm run build:offline` 产出 69 页 offline.html（页数断言）
- Export PDF 全链重测：verify-pdf-export 双场景 10/10 + 契约
- 碰撞断言链可用：text-collision-audit + pixel-same-source 对 pilot-out 基线复跑
- 产物回写 ai-fpga-deck/ppt/index.html 流程复跑通

## v2.5 协调时序（先问后动）

1. **经 Nebula 问 html-deck-studio Manager**：①迁入窗口条件（无在途批次）②landing 目录确认（ai-fpga-deck-src/ vs 其偏好）③其仓 commit 安排（基线 commit 由谁落、工作树杂项处置）
2. 窗口确认后执行顺序：
   - ① html-deck-studio 侧建 ai-fpga-deck-src/ 整体拷贝 + .gitignore 增补 + 基线 commit
   - ② package.json 改名+pin 版本 → npm install → 绝对/相对路径改写（v2.3-7/8/9）
   - ③ v2.4 验证链全跑
   - ④ slideblocks 本地 fix/deck-workbench `git rm -r decks/ai-fpga-dev` + commit（本地）
   - ⑤ slideblocks-deck 旧 worktree 处置
3. 每步留证（commit hash + 验证输出），卡住即停即报。

## v2.6 现场事实附录（2026-08-28 只读实测）

| 事实 | 证据 |
|---|---|
| daemons.json 无 deck daemon（4 条：4321/7861/3000/8765） | ~/.nebflow/daemons.json 全文 |
| 3030 / 3047 端口均空闲 | lsof 零监听（3047 僵尸已死） |
| 平台对 ai-fpga 零引用 | grep skills/ scripts/ packages/ apps/ registry/ decks/README.md 全空 |
| 6 文件 canonical 一致性 | diff setup/4 + .slideblocks/2 vs skills/slideblocks/assets/ 全 IDENTICAL |
| 工具零 ../ 父级引用 | grep "\.\./" .slideblocks/*.mjs setup/*.ts tools/*.py 零命中 |
| slideblocks-deck worktree 仍存 @5d3a052 持 39 页旧版 | git worktree list + ls decks/ |
| html-deck-studio 无 root package.json，.git 566M，ai-fpga-deck 127M | ls/du 实测 |
| html-deck-studio 工作树杂项 | `?? ai-fpga-deck/ppt/index(2).html` + `M .DS_Store` |
