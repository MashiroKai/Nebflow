# feishu-mermaid-20261119 — 飞书 md→长图里的 mermaid 未渲染

## What

`scripts/social-md-shot.mjs`（社交出站 md/markdown → 单张长图 PNG 腿）新增一个
**mermaid pass**：marked 出完 HTML 后、截图之前，把每个
`<pre><code class="language-mermaid">` 就地换成 vendored mermaid 渲染出的 SVG。

- 复用同一份 vendored 库 `src/main/resources/web/vendor/mermaid/mermaid.min.js`
  （12.2.0，devDependency/vendorAssets 台账已锁，前端 `js/mermaidRenderer.js` 懒加载的就是它）
  —— 零新依赖、零第二份渲染路径。
- 复用同一 `mermaid.render(id, src, container)` API 与同一安全纪律：
  `securityLevel: 'strict'`、`htmlLabels: false`、离屏可布局测量舞台，
  产物经 `DOMParser` 解析 + `importNode` 落 DOM（不裸 `innerHTML`）。
- 库发现口径与既有 marked 一致：env 覆盖（`NEBFLOW_MERMAID_JS`）> 从脚本目录向上
  ≤6 级找仓库 vendor 面；找不到 ⇒ stderr 记一行、mermaid 块退回源码文本、**exit 0**
  （不因缺渲染器崩一枪）。
- 语法不成立的 mermaid 块：保留原始源码文本（不空白、不吞），该块失败不拖垮整张图。
- 负控开关 `SOCIAL_MD_NO_MERMAID=1`：跳过 mermaid pass（出图退回未渲染态）。

## Why

作者报障（2026-11-19）：飞书侧 md 会转成图片发过去，但图里的 mermaid 没渲染。

根因：这条腿（`RichFeishuAdapter.deliverLongShot` → `FileLongShot.NodeShot` →
`WebPageShot.NodePlaywright` → `node scripts/social-md-shot.mjs`）此前只跑
marked，**不跑任何 mermaid 渲染器**。marked 把 ```mermaid 围栏出成普通
fenced code（`<pre><code class="language-mermaid">`），长图里因此是一段源码文本，
而不是图。

## Impact

- 涉及文件：`scripts/social-md-shot.mjs`（唯一改动）。
- 产物：飞书 / 微信共用的文件类长图腿；同一段含 mermaid 的 Markdown 出图后，
  mermaid 图在图片里可见。
- 无 API / 契约 / 分类层改动：`FileLongShot` / `WebPageShot` / `RichKind` 零触碰。
- 降级面不变：库缺失或渲染失败仍退既有文本卡路径。

## Evidence

- 修前 `before.png` / 修后 `after.png`：同一夹具 `evidence/fixture-mermaid.md`，
  真实 `node scripts/social-md-shot.mjs` 产物。
- 负控 `negative-control.png`（`SOCIAL_MD_NO_MERMAID=1`）：与 `before.png`
  **逐字节相同**（sha256 `1565c974d44c83084eeb1a4dcf22bf5b2550039d07f00d5e3a41df2827a5ea15`），
  证明改动的净效应恰是 mermaid pass。
- 语法错误块 `evidence/bad-block.png`：源码保留、exit 0。
- 静态闸 `evidence/gate-static-before.log` / `gate-static-after.log`：
  既有红仅 `noclip-floating` / `script-tests` / `sdd`（均与本次改动无关，见报告）。
