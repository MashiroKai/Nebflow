# pptx-convert 复活批 REPORT（作者令 2026-10-29「复活吧」）

分支：`delegate/delegate-nebflow-560c07d7`（基于 main tip `580be3a53`）
被复活批：`delegate/delegate-nebflow-f30e15f7`（tip `d1424ca94`，基线 `e2bcaa597`，落后 main ~350 提交）
原批 REPORT：`reports/20261024_153000_pptx-canvas-convert__delegate-nebflow-f30e15f7/REPORT.md`（§五 Scala 读数留空 = 死前从未跑到；本文补齐）

## 一、收割方式

**逐文件 cherry-pick 整提交重放**（`git cherry-pick d1424ca94` 一次干净落地，零冲突）——依据：收割前对账发现分支触面的全部 3 个已改文件（`nearpath.js` / `WebSocketRoutes.scala` / `pptx.js`）在分支基线与现 main tip 之间**字节一致**（`git diff e2bcaa597 HEAD -- <file>` 全空），6 个新文件（NfOfficeRoutes / NfOfficeRoutesSpec / canvas-office-convert.spec / live-accept / REPORT / evidence×4）无同名冲突面。收割后另起对齐 commit（`a3b5d25e1`）落漂移修复。

## 二、对齐清单（四处；任务书预判三处 + 实测挖出第四处）

| # | 面 | 漂移实况 | 处置 |
|---|---|---|---|
| ① | `WebSocketRoutes.scala` 挂点 | ask/身份等批改过同文件但挂点锚串（`nfAuthcheckRoutes … <+> NfFileRoutes.nfReachRoutes`）未动，且 `nfTicketStore`/`nfPathPolicy` 两参在现 main 作用域同名在场（现 1040-1046 行） | cherry-pick 自动落位现 1046 行，零手改；`officeRoutes(nfTicketStore, nfPathPolicy)` 二参与现 `NfFileRoutes.nfFileRoutes` 同参同序 |
| ② | nf-ticket/nf-file 票闸链 | **判据零演进**（核实项）：`NfFilePolicy.nfFileVerdictTolerant` / `NfTicketStore.verifyAndConsume(token, realPath)` 签名与语义与分支写法逐字兼容；main 侧新增的宽容形态（`PathParamCodec.candidates`）与存量根双读（2026-10-20）都在共享判据函数**内部**——nf-office 调同一函数即自动继承，无口径分叉 | 核实即止，零代码改。备注：main 在分支基线后给 NfFileRoutes 加了 403 出向判据拒日志（`deniedWithLog`，Pop 图片 403 批）——nf-office 同状态码暂无对数行，判据不受影响，列为遗留③ |
| ③ | `nearpath.js` 4 行 | 身份批 dualhost 改造未触及这 4 行区域（`probeBase` 品牌自证头 cast ×2 + candidates 字面量类型注），cherry-pick 干净落下 | 原样保留；checkJs 总错数 260→256（-4，即此 3 错清零＋基线自然余量），基线零动（与原批纪律一致） |
| ④ | **`tests/live-accept.mjs` 认证链（实测挖出）** | 死后两批演进夹击：Stage D 撤了 URL token 轨——裸 localStorage token 不再被 WS 握手接受，浏览器唯一入门 = console-ticket 链；且沙箱执行面剥离 `NEBFLOW_LIVE_*` env（现役 spec 用 `NF_OFFICE_LIVE_*` 别名规避）。原稿两条全踩：两次 live 跑挂均因 token='t' + WS 拒手 | 对齐现役惯例：`establishSession`（`tests/helpers/session-rail.mjs`，composer-resync-live 同款）建立会话 + 别名 env 兼容读。修后 live 全绿 |

## 三、复活批新增修复（死分支从未验证过的真实破损）

原批 REPORT §五 Scala 读数留空、前端 e2e 读数存疑——实测复盘挖出 3 处，均已修（commit `a3b5d25e1`）：

1. **`NfOfficeRoutes.sweep` 编译错**：现 os-lib 的 `os.mtime` 已直返 `Long`，原稿 `.toMillis` 编译不过（`os.stat().mtime` 保留 `.toMillis` 不动）。
2. **`NfOfficeRoutesSpec` 10 处编译错**：`withSeedFile` 的 `(Path,String)=>IO[A]` 强约束下 9 处裸 `assertEquals`（Unit≠IO）+ `failingLeg` 的 Int 上 `*>`。签名收窄为 `Any` + 闭包内统一 IO lift，测试体零包装。
3. **`viewers/pptx.js renderFrontendPptx` 运行时 ReferenceError（最重）**：分支把 viewPptx 抽出路线 A 子函数时，`onProgress`/`loadLabel` 两个闭包变量未随参下行——501/502 回落腿**首取数即崩**（`onProgress is not defined` → fetch-failed 降级卡）。e2e ③④ 场景实锤后修：加载占位收拢为 `buildLoadingPane` 构建函数（顺带修了转换腿失败落路线 A 时占位子树被 setLoadingText 重写、旧闭包指向断链节点的问题），显式传参下行。

## 四、依赖核验（2026-10-29 本机实测）

| 件 | 结论 |
|---|---|
| LibreOffice | **在场**：`/Applications/LibreOffice.app`（soffice 二进制 + `/opt/homebrew/bin/soffice` shim） |
| soffice 主腿（`open -n -a` launchd 路径） | **实转通过**：fidelity-sample.pptx → 58,838B PDF 数秒出件；OS 层单测 + 端到端 live 双验 |
| qlmanage 兜底腿 | **实转通过**：1600px 首页 PNG（102,691B）0.3s 出件 |
| 直拉形态 | 未复测（原批已钉死挂死结论，代码仍走 `open` 拉起，无涉） |

⇒ 501 回落腿在本机属「转换器理论缺席」面，由 spec 注入假腿（NfOfficeRoutesSpec 501 用例）+ 前端 mock e2e ③ 场景覆盖，与原批口径一致。

## 五、读数汇总（全绿）

| 面 | 结果 |
|---|---|
| Scala `NfOfficeRoutesSpec` | **13/13 PASS**（票三态 401/403/200、415、501、缓存命中 ledger==1、mtime 重转、腿 fallthrough、502、pollFor 产出/超时、cacheKey） |
| Scala nf-file 族回归（NfFileRoutesSpec+NfTicketRoutesSpec+NfOfficeRoutesSpec） | **55/55 PASS**（票闸链触面零新增失败） |
| e2e `canvas-office-convert.spec.mjs`（mock 五场景） | **20/20 PASS**（修前 16/20——③④ 即上文 ReferenceError 实锤；fresh 截图已覆盖入 evidence） |
| e2e 回归 `canvas-office-viewers.spec.mjs` | **25/25 PASS**（路线 A/xlsx/docx/md 零回归） |
| e2e 回归 `canvas-video-viewer.spec.mjs` | **13/13 PASS** |
| 静态闸 checkJs | **PASS**（256 < 基线 260，零新增；nearpath 3 错清零） |
| 静态闸 build-web | **OK**（orphan 0） |
| **LIVE 实例验收**（`live-accept.mjs`，隔离 dev 实例 :8299，pinned JDK21，独立数据根，在产 6138 零触碰） | **全绿**：真实 34.6MB 开题 deck（作者 10-26 版）首开 `gate=convert-pdf` **11s** 出件（soffice 腿，`X-Nf-Office-Cache: miss`，产物 7.7MB PDF 落 `<home>/cache/office-preview/`）；二次打开零请求零重转（浏览器 HTTP 缓存 max-age=300 内——服务端 hit 头由 Scala spec 钉）；pageerror 0；截图 `evidence/20261024_pptx-convert/live-0{1,2,3}-*.png`（标题页+目录页真渲染入画） |

## 六、遗留

1. **docx/xls 族未切转换腿**（原批决策原样保留：既有 mammoth/xlsx viewer 正常，避免无谓回归；端点扩展名闸已收 .doc/.docx/.xls/.xlsx/.xlsm，随时可切）。
2. **nf-office 403 无出向日志**（NfFileRoutes 的 `deniedWithLog` 是分支死后加的；同状态码对数行可作后续小批，判据面无涉）。
3. **soffice 直拉挂死未深究**（原批遗留原样；`open` 拉起+按 profile pkill 收尸在本机两代 macOS 上均稳定）。
4. 缓存清扫仍只在新转换落盘时顺手做（30 天/200 条），无独立后台线（原批设计如此）。
5. docs/ 无 viewer 专属知识文档（glossary 亦无 nf-family 条目）——指针无处可挂，未加；知识锚点 = `NfOfficeRoutes.scala` 文件头注 + 本 REPORT + 原批 REPORT。
