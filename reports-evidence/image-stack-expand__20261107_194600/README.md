# 证据索引 — 图片组「展开即消失」（2026-11-07 · image-expand-disappears 批）

被测面：**真实浏览器（Playwright/Chromium）+ 真实模块图 + 真实 CSS + 真实应用外壳
`index.html`**（`tests/_chat-face-harness.mjs` 的 boot：静态树从磁盘注入，WS 帧桩，
零后端、零宿主实例 —— 不碰 :6138 宿主控制台）。改前树 = `git archive HEAD` 导出的
`src/main/resources/web`（`/tmp/web-prefix`），改后树 = 本 worktree。

## 一、改前 / 改后读数（真几何，`reading-*.json` 逐份落盘）

| 读数 | 改前（HEAD） | 改后 |
|---|---|---|
| 展开列 computed `display` | **none**（`.show` 类在场） | flex |
| 叠卡容器盒（桌面 1280×900） | **94 × 38**（只剩胶囊） | 330 × 680 |
| 叠卡容器盒（手机层 375×812） | **100 × 44** | 320 × 680 |
| 展开行盒（三张异比例图） | **0×0 / 0×0 / 0×0** | 220×55 / 105×420 / 220×165 |
| 行内 `<img>` naturalWidth | 800 / 200 / 400（字节到位但盒为 0） | 800 / 200 / 400（同，盒真实） |
| 单张平铺面（C4） | 220×165 可见 | 220×165 可见（两版一致） |
| Canvas 图片页签（C5） | 可见 | 可见（两版一致） |

## 二、文件清单

| 文件 | 内容 |
|---|---|
| `before/c1-*.png`、`after/c1-*.png` | 桌面亮档：折叠 → 展开 → 收起 → 再展开（C1）。改前 `c1-2-expanded.png` = 报障现场（只剩「收起」胶囊、图全无）；改后三行图各自比例可见 |
| `before/c2-1-dark-expanded.png`、`after/c2-1-dark-expanded.png` | 暗档展开（C2） |
| `before/c3-1-mobile-expanded.png`、`after/c3-1-mobile-expanded.png` | 手机层 375×812（body.mobile-layer 真实激活）展开（C3）。改前形态与作者附件同形 |
| `before/c4-1-single-flat.png`、`after/c4-1-single-flat.png` | 单张平铺面（C4，无展开钮） |
| `before/c5-1-canvas-image.png`、`after/c5-1-canvas-image.png` | Canvas 图片页签（C5，不回归） |
| `before/reading-*.json`、`after/reading-*.json` | 上述每张截图的逐项几何读数（面积/行盒/display/字节状态） |
| `spec-before.log` / `spec-after.log` | `tests/image-stack-expand.spec.mjs` 两棵树各跑一次：改前 3 failed / 3 passed（C1/C2/C3 红），改后 6 passed |
| `gate-css-structure.log` | 新门禁 `scripts/check-css-structure.mjs` 三档：改前树 FAIL（L641 S1 + L694 S2）／改后 PASS／判据自检 4/4 |
| `existing-specs-before.log` / `existing-specs-after.log` | 既有媒体组件 spec 两棵树对照：改前 **8 failed / 44 passed**（pop-stack-fan 展开腿、pop-artifacts U3/U4、quote-media-stack、pop-media-alignment），改后 **52 passed / 0 failed** |

## 三、复现命令（任何人可重跑）

```bash
# 改前树
git archive HEAD src/main/resources/web | tar -x -C /tmp/web-prefix
CHATFACE_WEB_ROOT=/tmp/web-prefix/src/main/resources/web \
IMG_STACK_SHOT_DIR=/tmp/ev-before \
  node node_modules/@playwright/test/cli.js test tests/image-stack-expand.spec.mjs

# 改后树
IMG_STACK_SHOT_DIR=/tmp/ev-after \
  node node_modules/@playwright/test/cli.js test tests/image-stack-expand.spec.mjs

# 门禁（三档：改前红 / 改后绿 / 判据自证）
node scripts/check-css-structure.mjs --root=/tmp/web-prefix/src/main/resources/web
node scripts/check-css-structure.mjs
node scripts/check-css-structure.mjs --selftest
```
