# vendor/mermaid — mermaid 12.2.0（浏览器 IIFE 构建，`globalThis.mermaid`）

Markdown ```mermaid 围栏块 → SVG 图表渲染库，`js/mermaidRenderer.js` 懒加载使用。
入仓批次：mermaid-md 渲染批（作者令 2026-10-23 19:24 逐字「让我们的markdown支持mermaid渲染」）。

| 台账项 | 值 |
|---|---|
| 来源 | npm `mermaid@12.2.0`（dist-tag latest，2026-10-23 查询） |
| tarball | https://registry.npmjs.org/mermaid/-/mermaid-12.2.0.tgz |
| tarball sha256 | `071914164d0f6d53e5c26a10a220230d4ae8d37ed0f2263fbf072266f0af3a0a` |
| 本目录文件 | `dist/mermaid.min.js`（官方 esbuild IIFE 构建，5.5MB，全部图表类型内联自包含） |
| mermaid.min.js sha256 | `a8a6a6f805b074a8261a2825c86f99c8054acb3b35f50bb7e24a4f9f2c3c3b9f` |
| license | MIT（见 `LICENSE`，上游随包件逐字节原样） |
| 加载纪律 | 懒加载单飞 Promise（`mermaidRenderer.js` script 注入，对齐 pdf.js「不进开屏包」纪律——首个 mermaid 块进入视口才加载，普通文档首屏零开销） |
| 全局形态 | IIFE 结尾无条件 `globalThis["mermaid"] = …default`——不走 `define.amd` 分支（内联 dayjs/cose 等 CJS 依赖的 module/exports 均为 bundle 局部变量），与 monaco AMD loader 无冲突面 |
| 安全 | `securityLevel: 'strict'`（mermaid 消毒标签、禁 click 回调/脚本）；渲染经 `mermaid.render` 游离临时容器完成、SVG 落 DOM 前再做一道 `<script`/`on*=`/`javascript:` 防御扫描 |
| 升级方法 | 上表 tarball 重取 → 核对 sha256 → 替换 `mermaid.min.js` → `mermaidRenderer.js` 顶部 PIN 注记同步版本号 |
