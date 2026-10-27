# 台账 #815 收口批 E —— Canvas PPTX/Office 在线查看

日期：2026-10-27　分支：delegate/delegate-nebflow-c9b76822　作者令：#815（10-24 点名重申）

## 结论先行

**#815「pptx 至今零 commit」的前提已过时**：pptx 在线查看主能力（路线 A 沙箱渲染，commit
0adfef219 → 03dcc4789 打捞入 main）早已落库并在跑。本批为收口：盘点覆盖面（下表）、
补大件进度态、钉 spec 三条件（首屏/翻页/错误态）、修一条存量 stale 断言。

## 覆盖面清单

| 类型 | 改前 | 改后 |
|---|---|---|
| pptx/ppt | ✅ 沙箱双层帧渲染（staging CSP 断网 → 中和 → 产物全沙箱帧）、滚动翻页、缩放、CFB/超限/失败三降级腿 | ✅ 同左 ＋ 取数/渲染进度读数（不黑盒）＋ spec 三条件钉死 |
| docx/doc | ✅ mammoth 渲染＋reMint 自愈＋错误面板 | 不变（本批零触碰） |
| xls/xlsx/xlsm | ✅ xlsx viewer＋reMint 自愈＋终态错误面板 | 不变 |
| epub | ✅ 分章阅读器＋reMint 自愈＋错误面板 | 不变 |
| pdf / image / video / html / md / yaml | ✅ 各自 viewer（#912 错误态批已收口） | 不变 |
| 服务端 | FileTypeRegistry ppt→pptx/doc→docx/xls→xlsx 映射在位；vendor 子树路由在位 | 不变（零 Scala 改动） |

## 改动（2 文件 + 1 spec）

1. **viewers/pptx.js**：加载占位升级为「进度态」——`onProgress` 透传
   `nfViewerUrl → nfViewerSource → fetchBlobWithProgress`（既有链路，零新增取字节路），
   大件取数逐段回填「字节/百分比」，字节到齐切「Rendering slides...」读数直至产物帧接管。
   objectUrl 腿/账本命中不触发（瞬时完成无可报）。大件 >50MB 仍秒级拒绝渲染落可见降级（不阻塞）。
2. **nfFileCache.js**：`nfViewerUrl` JSDoc 补 `onProgress` 参数类型（运行时本就透传，纯 typedef）。
3. **tests/canvas-office-viewers.spec.mjs**（+8 断言，17→25）：
   - ①+「首屏可见」：产物帧非零尺寸且与面板可视区相交（几何断言＋截图存证）；
   - ①-C 翻页：pane 自滚自由度＋产物帧随动滚出首屏＋滚动前后面板区截图字节不同；
   - ①-D 错误态三态：CFB 容器→`download-leg:cfb` 人话降级；坏 zip→`render-failed` 终态面板；
     >50MB→`refuse-render:source>50MB` 秒级终态不阻塞（三态均带 Download to view）；
   - ②-T4 铸票轮数断言订正 4→6（pop-403 批给 nfViewerSource 加了取数腿内部自愈 reMint，
     旧断言 stale——pristine HEAD 基线实测 mints=6，本批修前已红，非本批引入）。

## 验证

- `canvas-office-viewers.spec.mjs`：改前 16/17（唯红=stale 铸票断言，存量同判）→ 改后 **25/25**；
- `canvas-file-refresh.spec.mjs`（触碰 pptx/docx/xlsx pane 回归面）：**9/9**（npx playwright test）；
- `checkJs`：**PASS**（257 < 基线 260，touched 文件零新错）；
- 证据：`.nebflow/evidence/20261027_pptx-closeout-e/{before,after}/`（首屏/翻页/三错误态/xlsx/docx 共 11 图）。

## 遗留 / 边界

- LIVE 档未跑（需真实例 env；vendor 路由腿 2026-10-06 批已在 LIVE 取证，本批未触碰服务端）；
- KAI 同步：本批纯前端静态资源（src/main/resources/web），随常规合并落 Mac main 后候引擎
  分发/重启窗生效；对端无会话内直推通道，KAI 侧同步归分发面。
- .ppt 老格式/加密件按既定魔数嗅探落下载链（作者 2026-10-06 拍板范围，未扩）。
