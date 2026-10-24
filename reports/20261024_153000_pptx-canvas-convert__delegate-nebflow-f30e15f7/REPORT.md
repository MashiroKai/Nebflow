# canvas-pptx-convert 批 REPORT（作者令 2026-10-24 14:35「支持 pptx 在 canvas 查看……服务端转换优先」）

分支：`delegate/delegate-nebflow-f30e15f7`（基于 `e2bcaa597` = main）

## 一、问题定位（为什么作者说「没落实」）

主线 pptx viewer（路线 A 纯前端渲染，commit `0adfef219`，已在 main）在**真实 deck 上不工作**：

- 实测（Playwright 驱动真 web 树 + 作者真实开题 deck 13MB）：`Loading presentation...` 挂 ~37s
  → `Failed to render PPTX: render timeout 30s` → 下载卡。轻样例（6 页 fidelity-sample）能过，
  13MB 重 deck 触发 staging 帧 30s 渲染超时——即作者日常打开的文件恰恰是渲染不了的。
- 另查得 soffice 在本机的直拉形态挂死（见下），纯前端单腿注定不足。

## 二、选型与理由（主+备）

**主腿 = 服务端转换**（作者令指定）：新增 `GET /api/nf-office?path=&ticket=`（`NfOfficeRoutes.scala`），
转换器阶梯按序尝试、首个产出即胜：

1. **soffice → 整件 PDF**（保真最优，前端复用既有 pdf 面板——作者令「PDF 预览复用既有 pdf 面板」）。
2. **qlmanage（macOS Quick Look）→ 首页 PNG**（作者令「Mac 端兜底」；如实注明 only first page）。
3. 全缺席 → `501 converter-absent`；全失败 → `502 conversion-failed`。

**备腿 = 路线 A 纯前端渲染**（结构原样保留）：转换器缺席/转换失败/附件 blob 腿（无 absPath）时兜底。

**终态降级**：路线 A 也不可渲（>50MB / CFB 加密件 / 渲染失败）→ 显式「**此环境无预览能力**」卡
＋下载键（作者令「不许裸失败」，文案用作者原话）。

理由：转换在服务端走——① 不受浏览器 tab 内存与 staging 渲染超时约束（13MB deck soffice ~18s 出件）；②
legacy .ppt（CFB 魔数，路线 A 直接拒）soffice 也能转；③ 产物 PDF 远小于源件，重复打开走服务端缓存零重转。

## 三、转换器探测结果（本机实测，关键发现）

| 面 | 结果 |
|---|---|
| soffice 二进制 | 在场（`/opt/homebrew/bin/soffice` → `/Applications/LibreOffice.app`，LO 26.2.4.2） |
| soffice 直拉（ProcessBuilder） | **挂死**：`sal_detail_initialize` 内 fstat 自旋，11.5 分钟零输出（连 `--version` 都挂；干净 env 同挂）——macOS 26.4 + LO 26.2 组合在本进程上下文的 bootstrap 缺陷 |
| soffice 经 `open -na LibreOffice --args …`（launchd GUI 会话） | **正常**：6 页样例 ~8-12s 出件；真实 13MB deck **~18s 出件**（5.3MB PDF，全页） |
| `open` 拉起实例的收尾 | **不自退且持续烧 CPU** → 每次转换携独立 `-env:UserInstallation`（并发安全＋不碰用户 GUI LibreOffice），出件/超时后按 profile 串 `pkill` 精确收尸 |
| qlmanage | 正常：真实 deck 7.7s → 首页 1600px PNG（1.9MB） |

⇒ 生产转换腿在 macOS 上**必须**走 `open` 拉起（代码内注释钉死该事实与收尸纪律）；探测=「app 在场性」
（`.app` bundle 或 shim 在），非直拉 smoke（直拉 smoke 在本机会误判「缺席」）。

## 四、实现面（改动清单）

| 文件 | 内容 |
|---|---|
| `src/main/scala/nebflow/gateway/NfOfficeRoutes.scala` **新** | 转换端点：同 nf-file 判据链（同一 verdict＋同一票闸，零新增凭据面）；扩展名闸 {.ppt,.pptx,.doc,.docx,.xls,.xlsx,.xlsm}；源件 200MB 服务端闸；阶梯转换＋腿间 fallthrough；落盘缓存 `<dataRoot>/cache/office-preview/<sha256(realpath\|size\|mtime\|legs)>.<kind>.<ext>`（源件一变自然换键；30 天/200 条顺手清扫）；同键单飞（并发点击只转一次）＋2 并发闸；响应头 `X-Nf-Office-Converter` / `X-Nf-Office-Cache: hit\|miss` / `X-Nf-Reason`（nf-file 同族） |
| `src/main/scala/nebflow/gateway/WebSocketRoutes.scala` | 路由挂接一行（`nfTicketRoutes` 之后） |
| `src/main/resources/web/js/viewers/pptx.js` | viewPptx 改腿阶梯：转换优先（200-PDF→`pdfViewer.render` 复用既有 pdf 面板；200-PNG→首页预览卡＋下载键；501/502/无票/网络错→路线 A，原因记 `diag.convertSkipped`）；终态降级卡加「此环境无预览能力」；viewer 自铸 blob 的 pane 生命周期账；路线 A 主体逐字保留 |
| `src/main/resources/web/js/nearpath.js` | 顺手修 main 上 checkJs 闸**已存在**的 3 错（`window.__BRAND__` cast ×2、candidates kind 字面量类型）——main 现状跑 checkJs 闸本身就红（260>257），本批清零带回闸内，基线零动 |
| `tests/canvas-office-convert.spec.mjs` **新** | 前端门禁档（五场景，mock 档，20 断言） |
| `tests/live-accept.mjs` **新** | LIVE 实例验收脚本（真实例＋真转换＋真实 deck） |
| `src/test/scala/nebflow/gateway/NfOfficeRoutesSpec.scala` **新** | 路由语义 spec（假转换器注入） |
| `reports/20261024_*_pptx-canvas-convert__delegate-nebflow-f30e15f7/` | 本 REPORT ＋ evidence 截图 |

## 五、spec 结果

**前端（`node tests/canvas-office-convert.spec.mjs`，mock 档）：20/20 PASS**

- ① 200 PDF → `gateDecision=convert-pdf`、复用 `.canvas-pdf-viewer`＋pdf.js 真渲染、**零**路线 A renderer 资产请求、office 请求带票、converter/cache 头回读 diag。
- ② 200 PNG → `convert-firstpage`、首页卡、first-page 注明、下载键。
- ③ 501 converter-absent → 静默回落路线 A（renderer 资产真请求、gate=render、6 页出片）。
- ④ 502 全败＋路线 A 不可渲（CFB .ppt）→ 「此环境无预览能力」＋下载键（不裸失败）。
- ⑤ `X-Nf-Office-Cache: hit` → diag 透传。

**前端回归（`canvas-office-viewers.spec.mjs` 既有 17 断言）：17/17 PASS**（路线 A/xlsx/docx/md 零回归）。

**Scala（`NfOfficeRoutesSpec`，假转换器）：见 §七 运行读数**——票据三态（无票 401/错路票 403/合法 200）、
扩展名 415、缺席 501、单转双请求（缓存命中 `ledger.count==1`）、源件变更重转、腿 fallthrough、全败 502、
pollFor 产出/超时、cacheKey 随 mtime/size 变化。

**静态闸**：checkJs 257=基线（本批文件零错；nearpath 3 错修清）；`build-web` OK（orphan 0）；
circular 闸 **main 上已红**（popArtifacts/reference/msgQuote SCC，非本批文件，逐字一致复现——不属本批修，报备）。

## 六、LIVE 实例验收（真实例＋真 soffice＋真实 deck）

（待本节末尾运行读数回填）

## 七、遗留与边界

- soffice 直拉挂死未深究到 LO bug 编号（不影响本批：`open` 路径已稳定复现两轮）；若未来 LO 修复直拉，
  Linux 腿（直拉形态）即为本机同形。
- docx/xlsx「尽力而为」：端点扩展名闸已收，前端保留既有 viewer（mammoth/xlsx.full 工作正常）未切转换腿——
  避免无谓回归，作者要看 docx/xlsx 现状已可看。
- 缓存清扫只在新转换落盘时顺手做（30 天/200 条）；无独立后台清扫线。
- 浏览器侧第二次打开仍会重新**下载**转换产物（小 PDF，max-age=300 内走 HTTP 缓存）；「不重转」由服务端缓存
  承诺（`X-Nf-Office-Cache: hit` 可验）。
