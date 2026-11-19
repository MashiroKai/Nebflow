# vendor/pptx-renderer — @aiden0z/pptx-renderer 1.3.0 (standalone browser ESM build)

PPTX（OOXML presentation）纯前端静态渲染库，`viewers/pptx.js` 懒加载使用。
入仓批次：pptx 预览 MVP 实施批（路线 A，作者 2026-10-06 拍板令；POC 闸四闸全过后 vendored）。

| 台账项 | 值 |
|---|---|
| 来源 | npm `@aiden0z/pptx-renderer@1.3.0`（dist-tag latest，2026-09-14 发布） |
| tarball | https://registry.npmjs.org/@aiden0z/pptx-renderer/-/pptx-renderer-1.3.0.tgz |
| tarball sha256 | f867ce7e814454ac048c3857b0db689f884dea633a66e73648860a44cacf0422 |
| 上游仓库 | https://github.com/aiden0z/pptx-renderer（main @ 2026-09-23 push） |
| 本目录文件 | `aiden0z-pptx-renderer.browser.es.js`（README 指定的 no-bundler standalone 产物，自带 JSZip/ECharts 内联；pdfjs 为可选外部件、本仓不用） |
| browser.es.js sha256 | `46b61afa1435de0c194f93324c9467ca392c891c6e07517727c8ffb5e51c376b` |
| license | Apache-2.0（库本体，见 `LICENSE`）；内联第三方见 `THIRD_PARTY_NOTICES.md`（mtx-decompressor MPL-2.0，全文在 `licenses/`） |
| 加载纪律 | 懒加载单飞 Promise（`viewers/pptx.js` 内 fetch 文本 + blob-module `import()`，对齐 pdf.js 的「不进开屏包」纪律；UMD/Monaco AMD 冲突面不适用——ESM 不触 window.define） |
| 预算闸 | viewer 以 `zipLimits` 传入 `viewers/shared.js` 的 `ZIP_BUDGET` 预算族（entry 32MB / total 128MB，严于库推荐值 256MiB） |
| 升级方法 | 上表 tarball 重取 → 核对 sha256 → 替换 browser.es.js → `viewers/pptx.js` 顶部 PIN 注记同步版本号 |

注：产物含内联 ECharts（图表腿）；pptx.js 渲染后经 canvas→dataURL 克隆序列化，图表像素不丢失。
