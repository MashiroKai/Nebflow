# REPORT — 台账 #919 + #920 + #923 三件重派批（KAI 侧遗留 · 作者单 2026-10-27）

批次：hot-reload-foundation（#919）+ panel-desc/device-faces（#920）+ history-firstscreen-pin（#923）
分支：`delegate/delegate-nebflow-ddbdde82`（Mac，基于 main `8989391ef`）
背景：三件系 KAI 遗留重派（昨日 KAI 会话随引擎重启死亡、main 零落地）；今天核实 KAI main 是 Mac main 祖先（563da864c ⊂ 8989391ef，Mac 侧另有 6 笔不相关前进），故开发基座取 Mac main 超集，验证在本机（node v23 + playwright chromium + sbt/JDK23）全跑；KAI 工作区未触碰（其检出另有 #926 批未提交改动，属他在飞批）。

## #923 性能·聊天历史加载慢+触发重连 —— 首屏钉子（commit 8d879f60f）

根因与修复 = 台账 #922 批（`7d773b928` fix + `2a6ba3a36` report，已在 Mac main——本批 git log 核实在案，**零重复实现**）；本批补其缺的「首屏历史条数/时延断言」：

- `tests/chat-history-perf.spec.mjs` 新增 P0 组两钉：
  - **P0-a 首屏恰一页**：行数 = 一页（夹具 60 .row，卡片行双计）∧ init getHistory 恰一发（同游标重复请求 bug 的签名位）∧ 内容行零重复（剔卡族两行的 text 唯一性——双挂载页在 user/ai/read 行现形）；
  - **P0-b 首屏时延**：stub 收到 init → 行落定 < `PERF_BUDGET_FIRST_MS`（默认 3000ms）。
- Mac 实测（本机 chromium，300 条重会话夹具）：**firstScreen 261ms / 60 行 / contentDup 0**；全套 **13 PASS / 0 FAIL**（P1 分页 385ms/页、longtask n=0、WS 连接数 1 零重连、遮蔽腿 sheltered=1 照旧全绿）。
- 验证过程顺手加固：首屏等待从 `rows>0` 收紧为精确计数断言 + 失败路 debug 读数（rows/iframes/cardRows + 判据读数），红腿对照旋钮（NB_UX_ROOT）不变。

## #920 特性·多设备交互上下文 + 项目描述可更新 —— 面板侧补全

#913/#917（已落地）给的是 agent 上下文与 Project 工具面；本批补**面板侧**：

- **描述写穿（REST + WS + 卡片就地编辑）**：
  - 引擎：`POST /api/projects/<name>/description`（ProjectsRoutes，body `{description}`，空=清空）——写穿既有单点 `ProjectStore.updateDescription`（#917 面已是该单点，**零第二实现**）；成功腿广播 `projectDescriptionUpdated` 帧（帧单点 `ProjectActor.projectDescriptionUpdatedFrame`，经既有 wsHub；**幂等腿 unchanged 零写入不发帧**）；404 = updateDescription 的 Left 面。
  - 前端：projectTab.js 描述行**恒渲染**（空 = 占位文案）+ ✎ 钮 → 就地编辑（textarea + 保存/取消 + Esc）；保存 = `updateProjectDescription`（nodeData 三态）→ 定点写回卡槽 + toast；失败保持编辑器（输入不丢）；WS 帧按名定点写（**编辑中的卡不吃帧**，不打断输入）；`projectDescriptionUpdated` 入 ws.js GLOBAL_MSG_TYPES（无 sessionId 帧防视图抢夺，既有纪律）。
- **多设备交互上下文补全（会话/项目卡面）**：
  - `projectCreatedFrame.row` additive `device`（= #913 effectiveDevice 存储值）——projectCreated 帧行与 GET /api/projects 行**同构闭合**（此前 GET 行有 device、帧行没有）；
  - 前端徽标兜底：origin 缺席而 `device`= 对端 deviceId 的行（本机直建后被 #913 标注的）也拿 `.dev-badge`（`projectBadgeOf` 单点；`self`/local 仍零徽标，本机无感缺省纪律逐字不变）；无 path 段的兜底徽标 title 走无 path 专用 i18n 键（不产「路径 」空尾巴）。
- **验证**：
  - Scala `ProjectEventSpec` 42/42（原 5 钉 + 新 3 钉：成功帧恰一帧带写入值 / 幂等腿零帧带 unchanged / 404 零帧；row 键集钉更新为七字段含 device）+ ProjectOriginRowSpec + ProjectStoreSpec + ProjectToolActionsSpec 同轮全绿；
  - 前端新 spec `tests/project-desc-edit.spec.mjs` **6/6**（恒渲染/取消零请求/保存载荷+定点写+零 reload/帧定点写/编辑中不吃帧/projectCreated 带 device 插卡带徽标）；
  - 门禁：checkJs 257<260 基线零新增；build-web orphans 0；check-circular 仅既有 SCC[3]（popArtifacts/reference/msgQuote，与 main HEAD 同）。

## #919 基础设施·热更新地基 —— 方案段 + 地基层 + 验证钩子

- **方案段**（交付文档）：`docs/design/20261027_hot-reload-foundation-spec.zh-CN.md`——三层生效判据表（L1 前端 dist 自动生效 / L2 dev 树手动刷新 / L3 引擎代码走 #916 热重启链）+ 前端能热到什么程度（既有链：磁盘 dist 逐请求读 + 内容寻址 URL，4df51ddcd 奠基；缺口 = 已开页面不知道换字节）+ 边界五条诚实清单（DevTree module 编辑不挪 stamp、BundledDist 面冻结、忙时永不 reload 等）+ 引擎代码边界（**JVM 进程内热替换明确不做**，正解 = 既有 hotrestart 链；两链经 version 字段互认零代码共享）+ 开放项 R1-R3。
- **地基层（本批实现）**：
  - 引擎 `GET /api/nf-asset-stamp`（新 `AssetStampRoutes.scala` + RestApiRoutes 级联挂载）：stamp = 引擎实际伺服的 index.html **原始字节** SHA-256 前 16hex，与 `StaticRoutes.indexResponse` 三静态面**同源**（DevTree=classpath web/index.html 经 AssetVersions mtime 感知缓存；BundledDist=classpath web-dist；DiskDist=盘上文件 (path,mtime)→hash 缓存同形）；载荷带 `source`（哪张脸在伺服）+ `version`（=Version.string，热重启链互认面）；指纹不可得 = 404（前端静默空转）。
  - 前端 `web/js/hotStamp.js`（零依赖叶子）+ main.js 一行 init：启动捕获基线 → 45s 低频轮询（visibility 门）+ focus 补拍（10s 节流）→ stamp 变 ⇒ **空闲时自动 reload**（`state.busySessionIds` 非空让位挂起、sessionStorage 20s 防环闸、端点缺席 = no-op）；诊断/测试缝 `window.__nfHotStamp` + `_hotStampTestHooks()`（checkNow/setBoot/state）；vendor-globals.d.ts 声明。
- **验证钩子**：
  - Scala 新 spec `AssetStampRoutesSpec`：字节变⇒stamp 变（mtime 缓存重算）+ 幂等 + 缺文件 None；source 标签三面映射 + BundledDist 开发检出诚实 None；真实路由腿 200 载荷 + version 单源。
  - 前端新 spec `tests/hot-stamp-reload.spec.mjs` **3/3**：空闲变化自动 reload；忙挂起→闲落 reload；防环闸第二次 guard-skipped；端点缺失静默 no-op。
  - 门禁：checkJs 零新增（hotStamp.js clean）；build-web **187 源全入图 orphans 0**；check-circular 既有 SCC[3] 零回归。

## 判据与边界（如实申报）

1. **#923 定位为「#922 修复面的首屏钉子」**：作者单口径「定位根因…修复后钉性能测试」——根因与修复已在 main（#922 批 7d773b928，git log 核实），本批不做第二份修复，只钉其首屏断言；若作者意图是 KAI 侧重做修复本体，此批可废（判断依据：#922/#923 同日同症状两台账，#923 标注「台账 #922」引用关系）。
2. **基线对照组**：projcreate-fab T6 几何 + project-create-panel T9 + project-panel-archive 截图腿在本机**修前树同败**（git stash 基线复跑证实，2 failed/16 passed 修前后完全同集）——T9 是 askUser 哨兵腿超时（chromium 版本差）、archive 腿败因 `/Users/dev/` 硬编码 SHOT_DIR（他机路径），均非本批引入；project-desc-edit/hot-stamp 两新 spec + chat-history-perf + 主链路 spec 全绿。
3. **#920 「会话交互上下文」的边界**：会话侧的多设备上下文（device 表进 agent 上下文、强制显式设备参数）= #913 已落地（fleet.enforceExplicitDevice 在案生效）；本批会话面只补**面板可见性**（项目卡设备徽标兜底）——会话列表卡片不另加设备角标（folder 徽标已有 projectRoot 提示；跨设备会话身份 = agent 会话自身字段，属另一批次面），如实登记不扩面。
4. **验证环境**：本机 chromium（playwright 1.62 默认拉起的 headless shell）；#922 报告在案的「chromium 版本差伪差」（属性序等）不适用本批新断言（全部走文本/属性值/几何语义，无字节级 innerHTML 比对）。
5. **KAI 面**：未触碰 KAI 工作区（他在飞批文件面）；落 Mac main 后经既有 bundle 通道反向同步 KAI（#926 批同款路径）。

## 文件清单

#923：`tests/chat-history-perf.spec.mjs`
#920：`src/main/scala/nebflow/core/project/ProjectActor.scala`、`src/main/scala/nebflow/gateway/ProjectsRoutes.scala`、`src/test/scala/nebflow/gateway/ProjectEventSpec.scala`、`src/main/resources/web/js/projectTab.js`、`src/main/resources/web/js/nodeData.js`、`src/main/resources/web/js/ws.js`、`src/main/resources/web/js/locales/{zh-CN,en}.js`、`src/main/resources/web/css/projectPanel.css`、`tests/project-desc-edit.spec.mjs`（新）
#919：`docs/design/20261027_hot-reload-foundation-spec.zh-CN.md`（新）、`src/main/scala/nebflow/gateway/AssetStampRoutes.scala`（新）、`src/main/scala/nebflow/gateway/RestApiRoutes.scala`、`src/test/scala/nebflow/gateway/AssetStampRoutesSpec.scala`（新）、`src/main/resources/web/js/hotStamp.js`（新）、`src/main/resources/web/js/main.js`、`src/main/resources/web/js/vendor-globals.d.ts`、`tests/hot-stamp-reload.spec.mjs`（新）
