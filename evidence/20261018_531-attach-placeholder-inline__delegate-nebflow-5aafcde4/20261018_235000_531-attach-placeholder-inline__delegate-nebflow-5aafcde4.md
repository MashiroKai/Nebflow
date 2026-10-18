#531 附件态占位逻辑修复 —— 「请附一句话后发送」从独立提示行改为输入框自身 placeholder。

## 现象（作者截图 2026-10-18 23:36，已真复现）

转发/引用落框（纯引用帧，闸 blocked）时，输入区在附件 chip 下方**另起一行**渲染
「请附一句话后发送」（`.ref-gate-hint` div，composer-overlap 修复 2026-10-18 引入的
独立提示行），textarea 自身 placeholder 被置空。作者令：「不应该换行，而是在原有输入框
提示文字的位置进行修改」。

复现读数（修前，探针 DOM 实测）：`{placeholder:"", hintExists:true, hintHidden:false, sendDisabled:true, chipCount:1}`

## 根因

- `js/input.js` initInput：blocked 时插入 `.ref-gate-hint` div（#input 前文档流位），
  `syncRefOnlyGate` 翻其 `hidden`；`updateInputPlaceholder` 的 isRefOnlyFrame 分支把
  placeholder 置空（避免与提示行双文案）⇒ 提示成为独立行。
- 同型分支第二份：`js/input.js` voice 段 `refreshPlaceholder`。

## 修复

- 独立提示行整个移除：`.ref-gate-hint` 元素不再创建（input.js）、`syncRefOnlyGate`
  只管发送键禁用（input/attachments.js）、CSS 删 `.ref-gate-hint` 两块与
  `.has-attachments:has(> .ref-gate-hint:not([hidden]))` 收顶距特例（css/input.css）。
- blocked 态文案改落**输入框自身 placeholder**：`updateInputPlaceholder` /
  `refreshPlaceholder` 的 isRefOnlyFrame 分支 → `t('input.refOnlyHint')`
  （「请附一句话后发送」逐字保留，zh/en 字典零改动）。
- 几何等价性：blocked 时 chip 必在场 ⇒ `#input-wrap.has-attachments` 必开 ⇒ 既有
  `#input { padding-top: 44px }` 把 placeholder 压到附件条命中带（4..34px）之下，
  原流位的结构角色（防全宽引用 chip 吃输入框中央点击）由该规则继续承担，未回退。
- 附件清空/附言输入 ⇒ 闸解除 ⇒ placeholder 恢复既有链（语音 > 附件计数 > 通用）。
  ×移除 / paperclip / 发送·停止钮行为零改动。

## 验证

- `node tests/fwdguard-refonly-gate.spec.mjs`（AFTER 树）：**16/16 PASS**。
  新断言面：A2b placeholder=refOnlyHint ∧ 无 `.ref-gate-hint` 元素；A2c 输入框真渲染
  可见 ∧ 屏上文案即闸提示；A3 文案 = zh 字典逐字；B1a 闸解除后 placeholder 复位附件
  文案；B4 清空附件后 placeholder 复位通用文案。
- 判红纪律保持：`FWDGUARD_WEB_ROOT=baaae8ff3^`（前-闸树）跑同一探针 → 全红符合预期
  （A4/A5 捕获断点帧），两树契约未破。
- `npm run build`（esbuild）：161 js sources 全量进 bundle graph，orphans: 0。
- 截图对照（本目录）：`before.png`（独立提示行）/ `after.png`（placeholder 位，chip 行
  紧凑，无独立行）。dev 即 `src/main/resources/web` 源树直服 —— 刷新页面即生效
  （静态资源无 hash，必要时硬刷新 ⌘⇧R）。
