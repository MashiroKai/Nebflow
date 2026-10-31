# ask mode 修复批证据（2026-10-31，作者令 10-31 10:43 + 截屏 3 张）

截图脚本：`shoot-ask-fix.mjs`（自包含——静态 serve web 树 + WS mock，无后端；
复用 tests/canvas-ask-dock.spec.mjs 同款 harness）。

| 文件 | 对应 | 内容 |
|---|---|---|
| `01-waiting-single-bubble.png` | ④双泡修复 | 发问等待期：typing 指示**单件**、零空 ai 泡。修复前现场 = 作者截屏 3（等待小气泡上方叠多余空泡）；脚本 WAITING 读数 `{ai:0, typing:1, user:1}` |
| `02-md-rendered.png` | ①markdown 渲染 | ai 答复按主会话渲染腿成 HTML：h2 标题 / 无序列表 / 表格 / 代码块（pre+code）全套在场；脚本 RENDERED 读数 `{h2:true, ul:true, table:true, pre:true, raw:false}`。修复前现场 = 作者截屏 1（裸 `##`/`**`/反引号字面量） |
| `03-liquid-glass-v2-fade.png` | ⑤顶带渐隐 | 多轮对话下液体玻璃 v2 渐隐带成品（暗档）；材质读数 FADE `{filter: blur(18px) saturate(1.5), padTop: 64px}`——材质/模糊/饱和/两段式 mask 全部 `--g2-*` 单源，与 `#g2-edge-fade` 同配方；带净空在滚动容器之外（colwrap padding-top），气泡滚顶只到带底沿，首条永不入带 |

断言面（全绿才收批）：

- `tests/canvas-ask-dock.spec.mjs` F1/F2/F2-b/F3/F3-b（本批新增）+ T0–T13 全量回归；
- `tests/voice-seal.spec.mjs` N10/N10-b（封存态剥签 + 零 /api/tts）+ R6（恢复态回归）+ #508/#513 存量断言；
- Scala：`ReadFileContentToolSpec`（R1–R10）+ `AskGateSpec`（G1–G5）。
