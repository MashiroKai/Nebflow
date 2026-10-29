# wiki-graph 边栏入口封存 — 前后对比证据（2026-10-29）

作者手写想法① 2026-10-29 逐字：「词典边栏的图谱入口暂时隐藏，因为目前感觉并没有很好用，未来可能还要优化。但是保留词条有关的各种关联、引用的功能，需要保留。」

## 截图（真实源码树渲染，自包含 mock harness，1440×900 @2x）

| 文件 | 形态 | 说明 |
|---|---|---|
| `before.png` / `before.bar.png` | 改动**前**工作树（3c4765f7c） | 在产形态：图谱入口（hub-spoke 图标）在 Projects 与 Plugins 之间 |
| `after-sealed.png` / `after-sealed.bar.png` | 改动后 + 空配置（在产默认） | 入口已隐藏；其余项（files→projects→agents→usage→settings）原位、布局不塌 |
| `after-unsealed.png` / `after-unsealed.bar.png` | 改动后 + `"features":{"wikiGraph":true}` | 入口回挂**原静态槽**（恢复 = 配置一行，零改码） |

`.bar.png` 为左侧 chrome 放大条（x∈[0,120] 全高）。

## 断言钉

`tests/wiki-graph-seal.spec.mjs`（自包含，`node tests/wiki-graph-seal.spec.mjs`）：
W1 封存缺省 = `#wiki-btn` 不在 DOM + 五入口在场 + projects→agents 相邻（槽位空出不塌）；
W2 显式 true = 回挂静态槽（前邻 projects、后邻 agents）+ i18n 标题。本批 12/12 PASS；
好友封存回归 `friends-dev-default-on.spec.mjs` ALL PASS（同模块改动回归绿）。

## 可逆开关（唯一恢复点）

`<home>/nebflow.json` 加键：`"features": { "wikiGraph": true }` → 刷新页面生效
（dev 树直跑即生效；打包态配置逐字透传，无需重建 jar）。代码侧无第二开关。
