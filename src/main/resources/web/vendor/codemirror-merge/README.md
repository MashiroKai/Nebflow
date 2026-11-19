# vendor/codemirror-merge — CodeMirror 6 差异底座（@codemirror/merge + state + view，浏览器 IIFE 同源包）

`js/diffView/index.js` 的共享差异组件（移动 / 桌面两面同一份）经 `js/diffView/cmLoader.js`
懒加载使用。入仓批次：mobile-diff-migrate（作者令 2026-11-19「手机 diff 按推荐」）。

| 台账项 | 值 |
|---|---|
| 上游 | `@codemirror/merge` + `@codemirror/state` + `@codemirror/view`（npm 官方发布件） |
| 版本 | merge `6.12.3` + state `6.7.6` + view `6.43.15` |
| 来源 URL | https://registry.npmjs.org/@codemirror/merge/-/merge-6.12.3.tgz ・ https://registry.npmjs.org/@codemirror/state/-/state-6.7.6.tgz ・ https://registry.npmjs.org/@codemirror/view/-/view-6.43.15.tgz |
| tarball sha256 | merge `42d1bc38fff9bf0b5799d19ca7f00abab178765844f431d71cd9d2bfda0ce9cd` ・ state `91d75acc955ceeaf86af9396c11cda7694fe6222aac8427fa033d89eff5ac839` ・ view `f0d5921406f841d211fa0887aabf10626c47676bfc63a4abed2a4d207e27c5ac` |
| 本目录文件 | `codemirror-merge.min.js`（esbuild IIFE 合并包，276192 B） |
| codemirror-merge.min.js sha256 | `dad7dcb9444f503f2f85a3dacb16c7bad2796e27c978066051852a021afd5156` |
| license | MIT（正文逐字节原样保留在本目录：`licenses/merge.LICENSE`・`licenses/state.LICENSE`・`licenses/view.LICENSE`；state 与 view 正文相同） |
| 构建脚本 | `scripts/build-vendor-codemirror-merge.mjs`（锁版本 → esbuild 打 IIFE → 打印 sha256 / bytes → 回填 `package.json` 的 `vendorAssets.assets`） |
| 加载契约 | `js/diffView/cmLoader.js` 首次挂载时注入 `<script src="/vendor/codemirror-merge/codemirror-merge.min.js">`；产物在 `globalThis.NfCodeMirror` 上挂 `{ EditorState, EditorView, lineNumbers, highlightActiveLine, unifiedMergeView, MergeView, getChunks, acceptChunk, rejectChunk, uncollapseUnchanged }` |
| 运行期零公网 | 同源随包分发（既有纪律：锁版本 + sha256 入 `package.json.vendorAssets`，禁 CDN）。产物内唯一的 `http(s)://` 字面量是 SVG 命名空间 `http://www.w3.org/2000/svg`，无任何运行期取件 |
| 升级方法 | 上表 tarball 重取 → 核对 tarball sha256 → 跑构建脚本 → 回填 `vendorAssets.assets` 的 version / source / sha256 / bytes → 同步本表与 `licenses/` 正文 |
