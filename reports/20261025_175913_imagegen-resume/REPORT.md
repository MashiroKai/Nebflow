# imagegen 悬批恢复 REPORT（imagegen-resume）

- 批次：#798 悬批恢复——`delegate/delegate-nebflow-dca51539`（9776d3ce1，imagegen 双层批）合入 main
- 合并 commit：`83788191f`（main，`--no-ff`）
- 原批 REPORT：`reports/20261024_090719_imagegen-dual-layer__delegate-nebflow-dca51539.md`
- 悬因：原批已完成提交（49+20 spec 全绿）但会话被引擎重启带走，分支悬置；期间 main 前进 77 commits（含感知批 EmailSend 等 ~15 批）

## 1. 冲突清单与解法

git 三方合并**零文本冲突**（5 文件交集全部自动合并）。任务简报预告的四处冲突面逐一语义复核（自动合并≠语义正确，逐点核过共存性）：

| # | 文件 | 合流点 | 复核结论 |
|---|---|---|---|
| 1 | `core/tools/registry.scala` | 感知批 `EmailSend -> PerceptionMailTool`（:42，builtins 面）× 本批 `generate_image` seal 门控注册（:186-188，experimental 面） | ✅ 两行共存，各归各面（builtins vs 编译期门控 map），零语义交叉 |
| 2 | `web/js/chat.js` | 感知批 askUser 增强钩（:3116-3122，`__perceptionEnhanceAskCard`）× 本批 generate_image 占位卡特判（:1025-1042）+ import | ✅ 两钩共存，位置相距 ~2000 行，无共享槽位 |
| 3 | `web/js/main.js` + locale | 本批 `imagegenUpdate` WS 腿（:1150-1156）与 main 侧改动 | ✅ 自动合并正确；locale 零新增键需求（imagegen.js 无 i18n 引用，「调试中」徽标来自服务端 `ImageGenSeal.DebugLabel`） |
| 4 | `web/css/chat.css` | 本批 16 处 imagegen 占位动画规则 × main 侧样式演进 | ✅ 16 条规则全数在位 |
| 5 | checkJs 基线（260 档） | 新文件 `imagegen.js`（73 行）入基线面 | ✅ **PASS：260=260**（新文件零新增错误，基线无漂移；worktree 复跑同读数） |

- `RestApiRoutes.scala`：本批 `ImageGenRoutes` seal 门控挂载（:90-92）与 main 新增路由 `<+>` 链共存 ✅。
- `AgentCore.scala`/`shared/config.scala`：不在交集（main 自基线未触碰），分支版本原样生效；`FriendsSealKit.expectedNebulaSize` presence-based 派生对 EmailSend/双 seal 天然免疫计数漂移。
- **漂移修复面（6 锚，4 件，+23/-10 → 终版见 git）**：宽面电池逐轮显露的**分支悬置期潜伏红**（涉事 spec 在分支与合并树逐字节相同、main 自基线未触碰其面；原批电池只跑了 ImageGenSpec+三锚件，agent 族宽面未覆盖；seal=ON 形态更只跑过 ImageGenSpec）——非合并引入。同一根因：imagegen 把 `generate_image` 织入 Nebula 静态集/防逃逸剥除名单，而钉死断言仍钉 pre-imagegen 形态。修复 = 复用本批自建单点 `AgentCore.imageGenSealedStrip`（presence-based 派生，两 seal 形态自洽，零裸数字、字面集不动——原批 friendseal 同款纪律），**非放宽**：
  1. `AllowedToolSetSpec`（:711 converged 名交付面 ≡ 机制集）：期望面改扣 `imageGenSealedStrip`（OFF 形态红）；
  2. `AgentConvergenceSpec`（:109 `expected` 派生）：字面集 `baseExpected` 不动，`imgInFace = (静态集 -- strip).filter(已注册) -- baseExpected` 随形态加入（顺带修掉潜伏 **seal=ON 红**）；
  3. `AgentConvergenceSpec`（:113 缺口显式登记）：`{MemoryNote} ++ imageGenSealedStrip`——关形态显式登记、开形态收回 `{MemoryNote}`；
  4. `AgentConvergenceSpec`（:270/:292 dream 剥除名单两处字面集）：+`generate_image`（剥除名单静态在场，沿革注记随行）；
  5. `AgentDefCategoryBackdoorSpec`（:50 `mechanismFace` Nebula 臂）：同 ①扣单点 strip（OFF 形态红）；
  6. `Phase2dToolRefactorSpec`（:96 D.1-1 期望面）：原批 `imageGenStrip` 扣除逻辑只顾 OFF 形态，开形态 obtained 面**加入** generate_image 而字面集无之——补 `imgInFace = 静态集 -- strip -- 字面集` 镜像加入（潜伏 **seal=ON 红**，实跑捕获）。

## 2. Spec 读数（本 worktree 实跑 @ 83788191f 树）

验证选址说明：主树上 `sbt run` 即在产引擎（PID 88102 持锁），spec 跑自己的 worktree（独立 target/，不触引擎）。

| 门 | 形态 | 结果 |
|---|---|---|
| checkJs（`check-js-types.mjs`） | — | ✅ PASS，260=260，零门槛文件 3（主树与 worktree 双跑同读数） |
| build-web（`build-web.mjs`） | UNSEALED 缺省 | ✅ OK——184 js 全入 bundle 图（orphans: 0），194 lazy chunks，imagegen.js 在图内 |
| seal=OFF（release 形态）全电池：ImageGenSpec + RootSixBaseToolsSpec + Phase2dToolRefactorSpec + Phase2dSkillCatalogSpec + FriendsSealSpec + AllowedToolSetSpec + AgentConvergenceSpec + AgentDefCategoryBackdoor{Spec,Probe} + AgentControlE2ESpec + `nebflow.core.tools.*` 全族 | CompiledIn=false（生成件实读核验） | ✅ **1131/1131**（Total 1132；Skipped 1 = NEBFLOW_TASKLIST_LEGACY 环境门控存量件，Ignored 2 = 既有忽略项） |
| seal=ON（`STAND_IMAGEGEN=1`）电池：ImageGenSpec + RootSixBaseToolsSpec + Phase2dToolRefactorSpec + AllowedToolSetSpec + AgentConvergenceSpec | CompiledIn=true（生成件实读核验） | ✅ **119/119**（含 ImageGenSpec seal 镜像组 20/20） |

（原批读数对照：seal=OFF 49/49、seal=ON 20/20、插件 creator 校验 30/30——本批以宽面电池取而代之；插件包本批未触碰，30/30 沿用原批。双形态 seal 翻转机制实证：`sourceGenerators` 每次编译按 env 重写 `target/scala-3.5.2/src_managed/main/nebflow/core/ImageGenSeal.scala`，增量编译可靠感知内容翻转（4s 级），两轮形态互切均以生成件 `CompiledIn` 实读核验。）

## 3. 完整性核验

- `git diff 9776d3ce1 83788191f -- src/test`：分支触过的四 spec 锚件 + ImageGenSpec **不在差异**中（分支内容逐字节存活）；差异 101 件全为 main 侧新增（感知/委托/网关族），非本批语义面。
- 分支尾随 28 文件 +1837/-18 全部落树；28 件中 5 件交集已逐点复核（§1）。

## 4. 未做 / 开放项

- 真连冒烟（`scripts/imagegen-smoke.py`，两 provider 实调）本批未重跑——需在配 key 环境（原批已 PASS 并钉照，见原 REPORT §6）；恢复合并不改变 provider 面，无重跑必要。
- 全量 `sbt test` 回归仍留合并队列统一跑（与原批口径一致；本批受影响族已全绿）。
- ImageGenSpec 头注提及 `scripts/imagegen-smoke.sh`，实际件为 `.py`——原批遗留注释级笔误，非本批合并面，未顺手改（最小适配纪律）。
- Imagen 适配器预留位未真连（Google 停服窗口）——原批已声明，非本批事项。

## 5. 交付物

- main 合并 commit `83788191f`（零冲突合入 9776d3ce1）
- 漂移修复 4 件（§1 漂移修复面；spec 期望面适配，生产码零改动）+ 本 REPORT，随恢复批分支 `delegate/delegate-nebflow-f817af85` 入队落 main
- 本 REPORT：`reports/20261025_175913_imagegen-resume/REPORT.md`

## 6. 重启后 refresh（2026-10-25 23:26 引擎重启，会话复活续作）

- 事件：23:26 引擎重启，本批会话以 116 条 transcript 复活续跑；悬置期间 main 自 `83788191f` 前进至 `beb5c6f5f`（+24 commits：感知 P0 boot 修复直并、#809/#810 收口、面板候合并批、ticket 排查报告等）。
- 分支处置：`git rebase main` 干净落位——本批 5 件（4 spec + REPORT）与 main 新增 commits **零文件重叠**（rebase 后与原 8555f7cf5 逐字节 diff 为空）；分支现为 `main(beb5c6f5f) + 1 commit 586442fbf`，内容与重启前完全一致。
- 重验动机：main 侧动过 agent 族生产件（`AgentFinishTurn`/`AgentIdle`/`BackoffSupervisor`），§2 绿声明须在重生效（rebase 后树）上重跑才诚实：

| 门 | 形态 | 结果 |
|---|---|---|
| §2 同款 seal=OFF 全电池 | CompiledIn=false（同 §2 纪律） | ✅ **1131/1131**（Total 1132, Skipped 1 存量门控, Ignored 2），355s |
| seal=ON 轮（`STAND_IMAGEGEN=1`，§2 同五件） | 生成件实读 `final val CompiledIn: Boolean = true` | ✅ **119/119**，4s |

  证据：本目录 `evidence-refresh-sealOFF.log` / `evidence-refresh-sealON.log`（读数与 §2 重启前完全一致）。
- §1–§5 结论全部在重生效；合并面不变（4 spec 适配 + 本 REPORT），继续候并入 main。
