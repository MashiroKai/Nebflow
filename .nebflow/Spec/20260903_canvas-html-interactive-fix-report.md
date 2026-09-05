> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Canvas 面板打开 HTML 文件交互失效——复现 + 根因定位 + 修复 + 验收报告

日期：2026-09-03 ｜ 分支：canvas-html-fix（基线 main@7710190c，开工合并 main→18a50432）｜ 合并：main@3c6f9864（--no-ff，未 push）

## 0. 结论一句话

交互失效的根因**不在 iframe sandbox、不在渲染路径**，而在后端本地文件端点：`GET /api/nf-file` 的扩展名白名单（WebSocketRoutes.scala 原第 613-646 行）**不含 js/css/json**，导致多文件 HTML 交付物的伴随数据模块（`<script src="./snapshot-data.js">`）被改写为 `/api/nf-file?path=...` 后 **400 "File type not allowed"**；页面自举脚本在首个数据引用处（prototype.html:487 `seedFromSnapshot()` → :482 `SNAP.active.map`，`FM_SNAPSHOT` 未定义）**在绑定任何事件监听之前**抛错——于是「渲染出来但整页不可点」。补白名单 js/mjs/css/json 后端到端复通。

## 1. 复现与现状盘点（第零步，带代码行证据）

### 1.1 原型交互机制（~/.nebflow/docs/Nebflow/assets/20260903_flowmap-archive-panel/prototype.html，1075 行）

- 外部数据模块：**行 451** `<script src="./snapshot-data.js"></script>`（43KB 真实快照，同目录在盘）。
- 内联主脚本：**行 452-1073**，机制 = 纯内联 `addEventListener`（click/keydown/wheel/pointerover/pointermove，行 765-993）+ `requestAnimationFrame` 相机动画（行 872-874）+ `matchMedia` 主题/reduced-motion（行 463/993）+ 自有主题切换 `btnTheme → body.classList.toggle('theme-dark')`（行 992）。**无 localStorage、无 origin 依赖逻辑**。
- 回归调试钩子 `window.__fm`（行 1054-1055）与 `data-testid`（行 397-433）。
- 同目录作者自留 Playwright 回归脚本 regression.mjs（静态服务 ：8477 直开）29 断言全过 —— **原型本身在普通浏览器完好**，问题必然在 Canvas 装载链。

### 1.2 Canvas HTML 渲染链（iframe 组装点现状）

`Pop 工具`（PopTool.scala:219-230，popFile 帧：itemType/content/**absPath**）→ canvas.js openWorkspaceItem → viewers/html.js `viewHtml`（html.js:293）：

1. `resolveLocalFiles`（viewers/shared.js:49-75）：把 `src="./snapshot-data.js"` 相对路径按 absPath 目录解析，改写为 `/api/nf-file?path=<绝对路径 URL 编码>&token=...`（行 62）。
2. iframe 组装（**html.js:380-388**）：`sandbox='allow-scripts allow-same-origin allow-forms allow-popups'`（**行 384**）+ `iframe.srcdoc = srcdoc`（**行 387**，live iframe，非静态快照/高亮路径）。
3. 注入脚本（行 378 尾部拼接）：refSelect/svgInline/imgClick/anchorNav/themeProp 五段，均休眠或被动。
4. 防递归嵌套保护（**html.js:411-420**）：load 后检查 `contentWindow.location.href`，非 `about:srcdoc` 则替换为提示层；index.html 内嵌嵌入式启动守卫兜底。

### 1.3 复现（harness 层，新 spec T0 负控制组）

`tests/canvas-html-interactive.spec.mjs` 在 page.route 层让 `/api/nf-file` 对 `.js` 应 **400**（= 修复前真实后端行为，路由语义逐字对照 WebSocketRoutes.scala:647）：真身原型经 `workspace-open-item` 打进 Canvas → iframe 渲染成功、`.fm-node=0`、真实鼠标点击归档图标 `aria-expanded` 纹丝不动（false→false）——**完整复现「能看不能点」**。

## 2. 根因定位（哪一环阻止交互）

| 环节 | 证据 | 判定 |
|---|---|---|
| ① sandbox 缺 allow-scripts | html.js:384 已有 allow-scripts | **伪** |
| ② 缺 allow-same-origin 致 origin 依赖失败 | html.js:384 已有 allow-same-origin；原型无 localStorage/origin 逻辑 | **伪** |
| ③ pointer-events/外层遮罩拦截 | viewHtml 无遮罩层；T0/T5 点击事件可达 iframe | **伪** |
| ④ 走了代码高亮/静态快照路径 | html.js:387 srcdoc live iframe；innerH1/frame evaluate 可读 | **伪** |
| **⑤ /api/nf-file 白名单拒绝 .js/.css/.json** | **WebSocketRoutes.scala:613-646 白名单仅媒体扩展；:647 `BadRequest("File type not allowed")`** | **真（根因）** |

**完整因果链**：`<script src="./snapshot-data.js">` → resolveLocalFiles 改写为 /api/nf-file → 白名单无 js → **400** → `window.FM_SNAPSHOT` undefined → prototype.html:455 `const SNAP = window.FM_SNAPSHOT` → **:487 `seedFromSnapshot()` → :482 `SNAP.active.map` TypeError** → 脚本轮询中止于行 482，**765-993 行的事件绑定一行都没执行** → DOM 静态外壳照常渲染（CSS/HTML 不依赖该脚本）→ 用户视角「渲染正常但什么都点不动」。伴随 `<link href="*.css">` 同类交付物同样会 400（css 也不在白名单）。

## 3. 改动清单（文件/函数级）

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/gateway/WebSocketRoutes.scala` | ① 行 490 挂载点插入 `WebSocketRoutes.nfFileRoutes(token) <+>`；② 原 595-651 行内联 nf-file case **原样上移**至 companion（逻辑零改动，删内联留指向注释）；③ 类内 `extractToken`（:4515）改为委托 companion 纯函数别名，**全部既有调用点零改动**；④ companion 新增：`extractToken`（private[gateway]，逐字迁移）、`val NfFileAllowedExt`（原 32 个媒体扩展 + **js/mjs/css/json**）、`def nfFileRoutes(token)`（逐字迁移 + 白名单引用）。提取动机 = 路由可单测，**同文件 uploadsRoutes/jsRoutes 「Standalone (zero class deps) so it is directly unit-testable」既有先例**，非顺手重构 |
| `src/test/scala/nebflow/gateway/NfFileRoutesSpec.scala` | 新增，11 断言：鉴权 403×2、js 模块真身 200+逐字节、css/json 200、png 回归 200+逐字节、sh 400、缺文件 404、缺 path 400、白名单成员（修复在位/既有类型不缩窄/脚本类扩展仍排除） |
| `tests/canvas-html-interactive.spec.mjs` | 新增（见 §5） |
| `tests/fixtures/canvas-html-interactive/{prototype.html,snapshot-data.js}` | 作者原型逐字节拷贝（62,924B + 45,521B），spec 稳定资产 |

前端 `viewers/html.js` **零改动**（sandbox 本就正确——这正是四条假设全伪的结论）。

## 4. sandbox/origin 取舍与安全说明

- **不给不给的问题不存在**：allow-scripts 与 allow-same-origin 均已在位（html.js:384），且任务威胁模型成立——Canvas HTML 内容来源 = 本地受信交付物（用户/作者主动打开的文件），iframe 内脚本本就以 app 同源权限运行（可读 localStorage token、可 fetch /api/*），**后端白名单补 js/css/json 不扩大信任边界一分**：能引用这些 URL 的页面早已拥有任意脚本执行权。
- 路由安全面零回退：全程 token 门禁（403 先于一切）、normalize 后路径校验、白名单外 400（sh/exe/py/html 等仍拒）。实测：js 200 / sh 400 / png 200 / 无 token 403。
- **08-25 srcdoc opaque origin 坑不受影响**：五段注入脚本 postMessage 目标 origin 一律 `'*'`（html.js:47/125/153/223/229），本次未触碰任何 postMessage 代码。

## 5. spec 逐条结果（全部真实前台执行）

### 5.1 新交互断言 spec（tests/canvas-html-interactive.spec.mjs）——真实链路 harness

链路：静态服务真实 web/ 源码树（:8117）→ `workspace-open-item` 真身内容+absPath → canvas.js → viewHtml → resolveLocalFiles 改写 → srcdoc iframe；`/api/nf-file` 在 page.route 层按修复后后端语义模拟（磁盘真身 + 正确 Content-Type）。

```
PASS T0 复现控制：iframe 已渲染（死态可检出）
PASS T0 复现控制：.fm-node = 0（数据模块被 400 拒绝）
PASS T0 复现控制：点击图标 aria-expanded 不变（不可交互）  before=false after=false
PASS T0 复现控制：未被防递归保护误替换
PASS T1 iframe 存在且 sandbox 含 allow-scripts
PASS T1 sandbox 契约快照（allow-scripts allow-same-origin allow-forms allow-popups）
PASS T2 window.FM_SNAPSHOT 已由伴随模块注入
PASS T2 活动卡 = 11（数据模块真实执行）  fmNodes=11
PASS T2 归档徽章 = 25  badge=25
PASS T3 点击归档图标 → 面板展开（aria-expanded=true）
PASS T3 面板条目 = 25  entries=25
PASS T3 点击条目 → 独占展开（同时仅一条 expanded + aria=true）
PASS T3 Esc 关闭面板（状态回 closed）  open=false
PASS T4 点击主题按钮 → body.theme-dark + --color-bg 翻转  #f5f6f8 → #13161c
PASS T4 再次点击 → 主题还原  bg=#f5f6f8
PASS T5 无防递归误替换（无 "Rendering was stopped"）
PASS R0 主文档无 JS 运行时错误
═══ canvas-html-interactive: 17 PASS / 0 FAIL ═══
```

两条完整交互链到状态变化：归档图标→面板→条目独占展开→Esc 关闭（T3）；主题按钮→body.theme-dark+token 翻转→还原（T4）。合并后主仓重跑 **17/17**。

### 5.2 Scala spec（NfFileRoutesSpec）+ sbt compile

`sbt compile` 通过（main 早前轮次已过、test 236 源编译过）；`sbt "testOnly nebflow.gateway.NfFileRoutesSpec"` → **Passed: Total 11, Failed 0**；合并后主仓重跑（8095 实例实测 js 200/45521B 一致）。

### 5.3 变异验红（红→绿证据原文）

**变异 A（前端，任务指定方式）**：`html.js:384` sandbox 移除 `allow-scripts`（sed 就地改写，其余不动）→
```
frame.waitForSelector: Timeout 6000ms exceeded.
  - waiting for locator('.fm-node') to be visible     [exit 1 = 红]
```
恢复后 `git diff` 空（逐字还原）→ `═══ canvas-html-interactive: 17 PASS / 0 FAIL ═══`（绿）。

**变异 B（后端，本次修复本体）**：`NfFileAllowedExt` 剔除 `"js","mjs","css","json"` 四行 →
```
[error] Failed: Total 11, Failed 4, Errors 0, Passed 7     （sbt TestsFailedException = 红）
```
恢复后 `cmp` 逐字节一致 → `Passed: Total 11, Failed 0, Errors 0, Passed 11`（绿）。

### 5.4 回归（既有 spec 只读未改）

| spec | 结果 | 说明 |
|---|---|---|
| askuser.spec.mjs（AskUser 卡片+canvas.js 交互，隔离实例真后端） | **4/4 绿**（分支 :8094 与合并后 :8095 各跑一轮；首轮偶发 1 flake「answer slots」，重跑两轮全绿，非确定性时序 flake） | postMessage 通道 _nfAskAnswer/_nfAskState（chat.js:1495-1522）本次零触碰 |
| askuser-answer-source.spec.mjs | **5/5 绿**（分支）／**5/5 绿**（合并后主仓） | 静态 harness，无实例依赖 |
| tmp-regress-normal-html.mjs（防递归不误伤+正常渲染） | `{"iframe":true,"noticeShown":false,"innerH1":"Hello Canvas"}`（分支+合并后各一轮） | html.js:411-420 导航离开检测未触碰 |
| tmp-recursion-depth.mjs（52MB deck 嵌套探测） | 30s 全程 `iframe-depth=0 frames=1 readFile=2`，无重开无递归（分支+合并后各一轮） | 嵌套保护零回归 |
| reftag-redesign / history-replay-cards / orbit-anim | 7/7、2/2、6/6 绿 | 代表性前端子集（--workers=1 显式逐文件） |
| check-js-types.mjs | 分支输出与 main HEAD **diff 逐字节一致**（两侧同报既有的 orbSettingsUI.js 新文件 2 错、chat.js 7>6、343>326 基线漂移）——**零新增**，漂移在 main 已存在 | |

**已知既有失败（历史归因，不计入本批）**：
- bgagent-dedupe 1 failed：ghost-dup 文案断言 `toContainText "running Frontend · nebflow-project/Frontend"`——任务简报明示的历史归因，逐字复现。
- smoke.spec.mjs 8 passed / 5 failed：WS-cookie-probe 两例 + lazy-load 三例，环境类失败（隔离新 home 实例），与历史「smoke×5」条目数一致；失败路径均不涉 nf-file（前端 web/ 树与 main 逐字节一致，WS 连通类 8 例全绿证明挂载链健康）。
- askuser-refresh-survive T1 未跑绿：**基础设施缺失非产品缺陷**——该 spec 需 OpenAI 兼容 mock LLM（fixtures/askuser-refresh/mock-llm.mjs）注入实例配置；临时实例未接 mock，实例日志铁证 `event=llm-fail detail=all providers down: none recovered within 120000ms`（TOKEN env 修正后鉴权已过、会话已建、卡在首个垫轮 REST turn）。域=AskUser 刷新存活，与本批 nf-file 域零交集，遗留 #43 域。

### 5.5 真实端到端（隔离实例，真后端非 mock）

- 分支代码实例 ：8094：`js-served: 200 text/javascript size=45521`（与 fixture 逐字节同长）、`sh-blocked: 400`、`png-still-ok: 200`、`no-token: 403`。
- 合并后主仓实例 ：8095：`postmerge-whitelist-js: 200 size=45521`。

## 6. 截图（交互证据）

- /Users/dev/.nebflow/docs/Nebflow/20260903_canvas-html-interactive-dark.png（暗色：Canvas 内原型全暗主题、11 活动卡图、徽章 25、亮色切换钮在位）
- /Users/dev/.nebflow/docs/Nebflow/20260903_canvas-html-interactive-light.png
- 生成方式：新 spec CANVAS_HTML_SHOTS_DIR 模式（T4 主题链真实点击后 page.screenshot）；已 commit 进 ~/.nebflow（fa0e8ce）。

## 7. 分支与 merge commit hash

- canvas-html-fix 分支 commit：**7ce7afd9**（fix(gateway) 白名单+路由提取+NfFileRoutesSpec）、**579f1fd4**（test(web) 交互 spec+fixtures）
- 合并：main `git merge canvas-html-fix --no-ff` → **merge commit 3c6f9864**（无冲突；当时 main HEAD=2552fb57 已含并行节点 chat.js/scala 合入，与本次文件域零交集）
- ~/.nebflow：fa0e8ce（截图）+ 本报告 commit（见文末）

## 8. 合并后子集复跑（主仓）

新 spec 17/17 ✅ ｜ tmp-regress-normal-html 守卫 JSON 同上 ✅ ｜ tmp-recursion-depth 全程 depth=0 ✅ ｜ askuser-answer-source 5/5 ✅ ｜ askuser.spec.mjs 4/4（:8095 新实例）✅ ｜ :8095 实测白名单 js 200 ✅ ——不跑全量（分支已全绿）。

## 9. 清理确认

- `git worktree remove .nebflow/worktrees/canvas-html-fix`：完成（--force；残留仅为 node_modules 软链/playwright test-results/sbt target/ 施工垃圾，已合并内容零丢失）
- `rm .nebflow/canvas-html-fix` 软链：完成（ls 验证不存在）
- `git branch -d canvas-html-fix`：完成（已删除分支 canvas-html-fix（曾为 579f1fd4））
- 全程未 push、未动 origin
- 临时进程全部签退：:8977 静态服务（PID 40599/51552）、:8094 实例（PID 43386）、:8095 实例（PID 53061）均 lsof 验 PID≠宿主(87216) 后 kill，端口复检为空；宿主 8080 全程零接触

## 10. 生效说明

修复合入 main 即交付。**运行时生效需前端产物重建（esbuild dist）+ 宿主重启——本批严格遵守纪律未做任何重启动作**（含 8080 宿主零接触）；当前在跑宿主仍为修复前行为，重启后自然生效。

## 11. 遗留

1. **ES module import 语句不改写**：resolveLocalFiles 只重写 `src=`/`href=` 属性；内联 `<script type="module">` 里的 `import './x.mjs'` 不会被改写（相对路径会落在 app origin 404）。mjs 已进白名单，仅惠及属性引用形态；import 语句形态需未来做 import 语句改写或 importmap 注入（本批未动，严禁顺手重构）。
2. askuser-refresh-survive 全绿需 mock LLM 接线，归 #43 域基础设施。
3. nf-file path 参数接受任意绝对路径（仅 token 门禁）为**既有姿态**，本批未扩大未缩小；如需目录级收窄另立任务。
4. smoke×5 / bgagent-dedupe ghost-dup 既有失败仍在 main（历史归因，另案）。
