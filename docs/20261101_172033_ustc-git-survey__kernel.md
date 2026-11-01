---
type: survey
status: done
related: "[[20261101_151034_ustc-cicd-migration-assessment__delegate-nebflow-7f8e8dec]]"
---

# USTC git 调研（第一层）+ 主仓更名批（2026-11-01）

> 作者令：17:0x「拉取一下 ustc git 上最新的代码，分析一下看看我们怎么利用。两层都查」→ **17:12 改令**：「只分析我们的 stand 开发仓」，第二层（账号名下别人/共享仓全量 survey）取消；追加任务「把 USTC 上的仓更名 kaiyu/nebflow → kaiyu/stand，答案=能，你直接办」。本批交付＝第一层分析 + 更名执行 + 更名前后对照与引用清单。全程零推送（唯一写动作＝更名 API 调用与 remote URL 配置）；PAT 于子 shell 消费，未打印未落盘未转发。

## 0. 结论（先行）

1. **dev/main 有 10 条我们没有的新提交**：`zhang <zhang@local>` 于 11-01 15:42–16:20 直接推入 main——四件真 feature（JS 类型债烧平 367→2、window.NF_HOME 注入、已登录态退出登录入口+头像菜单、视觉回归基线首套 harness）+ 一件 R1 门面化（nfAdaptApi.js）+ 一条「Merge USTC upstream f8bd1f0d4 (ustc-real/main)」+ 四条 delegate 命名同构的 merge 提交。每件带 spec/基线，质量高；与本地 main 同日并行推进的 31 条**互不知情**。
2. **撞车交集小、合并可行**：merge-base=f8bd1f0d4；两侧都动的文件仅 **6 个**（chat.js / main.js / sidebar.js / locales/en.js / locales/zh-CN.js / viewers/shared.js）。type-baseline.json、vendor-globals.d.ts、StaticRoutes.scala 仅 zhang 侧动过，零撞车。一条 `git merge dev/main` 可收，预期 6 文件冲突手解。
3. **怎么利用**：视觉回归 harness 是我们缺的能力（本地无 tests/visual/）——采纳并按宿主重录基线；NF_HOME 注入/退出登录/R1 门面化随合并自动到位；类型债基线不能直接拿他的文件，合并后重跑 checkJs 重新生成。流程面要立规矩：zhang 直推 main 绕开了合并队列/闸体系，建议约定分支纪律或开 protected branch。
4. **更名已完成**：`kaiyu/nebflow` → `kaiyu/stand`（id 8663 不变）。PATCH 路由 404——站方实例的 projects 更新路由 **PUT-only**（旧版本形态），PUT 一次成功；dev 远端 URL 已更新、fetch 验证通；旧 URL SSH redirect 留证通过（在飞批不断链）。仓内「远端地址」性质的活引用需改 = **0**（3 处历史叙述按令不改）。

## 1. 第一层：dev 远端有什么新东西

### 1.1 分叉全景

| 面 | 事实（2026-11-01 17:0x fetch 实测） |
|---|---|
| main 分叉 | 本地 main `cdb2fa2c9` ↔ dev/main `081cde4e8`；merge-base=`f8bd1f0d4`；远端多 10 条（zhang，15:42–16:20），本地多 31 条（我们同日 13:33–16:19 的批） |
| dev 分支面 | `main` / `ci-gitlab-probe`(10-24, MashiroKai) / `delegate/delegate-nebflow-1f99803a`(11-01 **17:13**, MashiroKai, runner 前提校准批**在飞**) / `docs/glossary`(10-09, zhang) / `merge/bchain-20261010`(10-13, zhang) / `merge/ustc-9ca39083`(10-09, zhang) |
| 发版三支 | **USTC 侧无 `beta`、无 `release` 分支**——发版面仍在 GitHub 侧（与 [[20261101_151034_ustc-cicd-migration-assessment__delegate-nebflow-7f8e8dec]] §2.3 的迁移路线待办一致） |
| GitHub origin | 主仓 main 领先 origin/main 430+，漂移继续扩大；「先追平再退役」结论不变 |

### 1.2 zhang 的 10 条逐件

| sha | 时间 | 主题 | 落点与量级 | 本地有无同类 |
|---|---|---|---|---|
| 1d2a3e033 | 15:42 | fix(web) 烧平 JS 类型债 367→2 | 43 文件 402+/385-；type-baseline.json −175 行（基线仅剩 2 条） | 无——我们 c4f8a0d91 只描述存量 362，未烧平 |
| bbfaae14d | 15:46 | feat(web) window.NF_HOME 注入（shortPath 生效） | StaticRoutes.scala +46 + 新增 StaticRoutesHomeScriptSpec 62 行 | 无 |
| 03c96f8bc | 15:48 | fix(settings) 退出登录入口 + 头像两选一菜单 | neblink.js +39、locales +4、settings-cleanup.spec.mjs +90 | 无（本地同日是 Muse 悬浮头像，另一码事） |
| a9ce010ae | 15:53 | feat(visual) 视觉回归基线首套 | **新目录 tests/visual/**：零依赖 harness + PNG 比对器 + 6 场景×暗亮 12 基准图（win32-msedge-156）+ manifest，991 行 | 无——本地缺位的能力 |
| 5008241be | 16:07 | Merge USTC upstream f8bd1f0d4 (**ustc-real/main**) | 49 文件 852+/351-——zhang 把我们旧 main 当 upstream 合流 | — |
| 8edc03cec | 16:18 | feat(web) R1 门面化：js/nfAdaptApi.js 发布下游反适配扩展面 | 新文件 101 行 + main.js +6 + vendor-globals.d.ts +41 | 无 |
| ×4 merge | 15:53–16:20 | delegate/delegate-nebflow-{acda6c83,fbc4bf54,d2619066,de0eaa01} | 命名与我们的 delegate 分支同构 | — |

**身份注记（推断，非事实）**：作者串 `zhang <zhang@local>`、delegate 分支命名/批文措辞与我们工作流同构、视觉基线平台 win32-msedge-156（Windows+Edge）——与 10-28 审计名单上的张安齐（phenol1145，Windows 阵营，nebflow-dbm / triple-star 作者，reports/20261028_143325_dbm-mcp-plugin-pilot）画像吻合，推断同一人；且 dev/docs/glossary(10-09)、dev/merge/*(10-09/10-13) 显示 zhang 在该远端的提交史**早于** 10-28 审计批，非新出现。

### 1.3 撞车与影响

- **两侧都动的文件（冲突候选，全集）**：`src/main/resources/web/js/chat.js`、`main.js`、`sidebar.js`、`locales/en.js`、`locales/zh-CN.js`、`viewers/shared.js`。除此之外零交集（comm -12 实证）。
- **type-baseline.json**：仅 zhang 侧动（367→2）。口径不能直接取他的文件（基底差 5 条：他的 367 @f8bd1f0d4 vs 我们的 362 @本地 main）——合并后以本地树重跑 checkJs 重新生成再过闸。
- **风险**：①双头并行开发正在加速分叉（单日 10 vs 31）；②直推 main 完全绕开合并队列/闸体系——dev/main 无分支保护；③视觉基线平台性：win32-msedge-156 基线在 Mac 宿主上不可比，直接跑比对会全红；④nfAdaptApi「发布下游反适配扩展面」意味着存在一个外部下游依赖我们的页面内部结构（推断即 zhang 自己的实例）——门面化是正确解法，但这是**新增的对外契约**，我们改 web 入口面时要把它当兼容面对待。

## 2. 建议动作清单

1. 【合并】后续批 `git merge dev/main` 收 10 条：预期 6 文件冲突手解；合并后重跑 checkJs 重生成 type-baseline.json、过全闸。（本批只分析不动树，未预演合并。）
2. 【采纳】tests/visual/ 视觉回归 harness 并入前端闸体系（stand-dev 前端章/qa 章的验证流挂新腿）；基线按宿主分目录或重录，win32 基线留给 Windows runner 腿用。
3. 【采纳】NF_HOME 注入 / 退出登录 / R1 门面化随合并到位；合并批对三件的新 spec（StaticRoutesHomeScriptSpec、settings-cleanup.spec.mjs 扩段）跑一遍回归。
4. 【立规】与 zhang 约定推送纪律：推 `feature/*` 或 `zhang/*` 分支由作者合并；或 GitLab Settings→Repository→Protected branches 给 main 上保护（Free 层可用，作者一键可办）。
5. 【CI】作者自己的 runner 批（dev/delegate/delegate-nebflow-1f99803a，17:13 在飞，mac runner id=100 已注册、smoke 腿前置）与迁移评估阶段 1 对齐，无需本批动作。

## 3. 更名批结果：kaiyu/nebflow → kaiyu/stand

### 3.1 API 前后对照

| 字段 | 更名前（GET 实测） | 更名后（PUT 响应实测） |
|---|---|---|
| id | 8663 | 8663（不变） |
| name | Nebflow | **stand** |
| path | nebflow | **stand** |
| path_with_namespace | kaiyu/nebflow | **kaiyu/stand** |
| ssh_url_to_repo | git@git.ustc.edu.cn:kaiyu/nebflow.git | **git@git.ustc.edu.cn:kaiyu/stand.git** |
| web_url | https://git.ustc.edu.cn/kaiyu/nebflow | **https://git.ustc.edu.cn/kaiyu/stand** |
| visibility / 权限 | private / project_access=50 (Owner) | 不变 |
| owner | kaiyu（id 4736，PAT 本人实测 /user） | 不变 |

### 3.2 过程与根因（API 拒了一条路，换路打通）

- PATCH `/projects/8663`（数字 id 寻址）→ **404** `{"error":"404 Not Found"}`；PATCH `/projects/kaiyu%2Fnebflow`（path 寻址）→ 404 同形；PATCH 仅 `name=stand`（不带 path）→ 仍 404。响应带完整 GitLab 应用头（x-gitlab-meta/x-request-id/x-runtime 24ms）＝**应用层拒绝，非代理拦截**；`{"error":…}` 形态＝Grape 路由不存在。PAT 经 `/personal_access_tokens/self` 实测 scope 全量（含 `api`）＝非权限非 scope。**判定：站方实例的 projects 更新路由只挂了 PUT 没挂 PATCH（旧版本形态）**。
- PUT `/projects/8663`（path=stand&name=stand）→ **200，一次成功**。未动 UI 侧手艺（作者令红线），全程 API。

### 3.3 git 侧验证

- `git remote set-url dev git@git.ustc.edu.cn:kaiyu/stand.git`（.git/config 为全体 worktree 共享，改一次全体生效）；`git fetch dev --prune` **EXIT=0**（新 URL 通）。
- 旧 URL redirect 留证：`git ls-remote git@git.ustc.edu.cn:kaiyu/nebflow.git` → `HEAD=081cde4e8`、`refs/heads/main=081cde4e8`，与 dev/main tip 一致——**SSH 旧路径 redirect 活，迁移批/在飞 push 不断链**（作者预判兑现）。

### 3.4 引用修改清单（全仓 grep 三形式：`kaiyu/nebflow`、`ustc.edu.cn[:/]+kaiyu[/:%2F]+nebflow`、`kaiyu%2Fnebflow`）

| 处置 | 位置 | 理由 |
|---|---|---|
| **活引用需改 = 0** | — | README/.github/scripts/tools/根级 md+json 全扫，无一处硬编码旧远端地址；唯一远端地址在 .git/config（已改，不入版本库） |
| 不改（历史叙述） | reports/20261022_102500_small-sweep-four-items__delegate-nebflow-000c46b7.md:59 | 当日状态实测快照 |
| 不改（历史叙述） | docs/20261101_151034_ustc-cicd-migration-assessment__delegate-nebflow-7f8e8dec.md:105,115,155 | 11-01 评估快照的实测值表述 |
| 不改（与更名无关） | tests/uifix-mobile6-shots.mjs:38 | 本地假路径 fixture `/Users/kaiyu/nebflow`（非 git 远端地址） |

因此本批 commit＝本报告文档（引用面零改动可提交）。

## 4. 第二层（按 17:12 改令取消）

未执行账号名下 projects 列表，未 clone 任何外部仓，无浪费；PAT 仅消费于更名 API（GET×6 / PATCH×4 / PUT×1）与本仓 fetch。

## 5. 方法与存证

- **git 侧**：`git fetch dev --prune` ×2（17:0x 前后各一）；`rev-list --count` / `merge-base` / `diff --stat` / `comm -12` 定交集；`for-each-ref` 列分支面；`ls-remote` 验旧 URL redirect。
- **API 侧**：curl + `PRIVATE-TOKEN`（凭证文件子 shell 消费）；GET /projects/kaiyu%2Fnebflow、GET /user、GET /personal_access_tokens/self、PATCH ×4、PUT ×1、GET /projects/kaiyu%2Fstand（更名前 404 即未生效之证）。
- **零推送声明**：本批未向 dev/origin 推任何 ref；唯一远端写动作＝更名 API 本身。

## 相关件

- [[20261101_151034_ustc-cicd-migration-assessment__delegate-nebflow-7f8e8dec]]——USTC 平台能力与 CI/发版迁移路线权威评估（本批分支面/runner 批的背景）。
- [[20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4]]——机制审计权威版（协作者与 delegate 机制上下文）。
- reports/20261028_143325_dbm-mcp-plugin-pilot__delegate-nebflow-76aa2f3c/REPORT.md——张安齐（phenol1145）dbm 批，身份推断的画像来源。
