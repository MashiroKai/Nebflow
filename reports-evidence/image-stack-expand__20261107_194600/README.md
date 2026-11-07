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
| `spec-history-bisect.log` | 历史定位原始日志：`5545b1d4f` 6 passed → `1a8182372` / `f9630aa8c` / `3dc6c9885` 各 3 failed → 反事实（坏树只补一个 `}`）6 passed |
| `runtime-state.md` | 运行态核对：宿主实例 PID/启动时刻、main 与 `target/classes` 上该文件的 mtime 与块平衡 |

## 三、历史定位：回归是哪一个 commit 引进的（实证，不是相关性）

**结论：`1a8182372`（2026-11-06 14:27:07，定时任务页与待办页统一批）是回归的唯一引入点；经 14:32:30 的合并 `f9630aa8c` 进入 main。判定靠三件事同时成立 —— 纯增量 diff 里少一个 `}`、块平衡在它这一笔从 0 变 1、真实浏览器 spec 在它这一笔从 6 passed 变 3 failed（更早的 `5545b1d4f` 仍 6 passed），并且只把它缺的那个 `}` 补回去就 6 passed。**

### 3.1 逐 commit 实证（`spec-history-bisect.log` 为原始日志）

| 树 | 关系 | 该文件块平衡 | `tests/image-stack-expand.spec.mjs` | 判读 |
|---|---|---|---|---|
| `5545b1d4f` | 2026-11-06 09:50，坏批之前的最近一次 main 面 | depth=0（闭合） | **6 passed** | 好 |
| `1a8182372` | 2026-11-06 14:27，坏批本体（pure insert 77 行 / 0 删除） | **depth=1** | **3 failed / 3 passed** | 第一笔坏 |
| `f9630aa8c` | 2026-11-06 14:32，坏批合入 main | depth=1 | 3 failed / 3 passed | 进 main |
| `3dc6c9885` | 2026-11-06 19:15（= 工作树与运行实例现在拿到的资源） | depth=1 | 3 failed / 3 passed | 至今未好 |
| 反事实：`1a8182372` + 仅补 `}`（2 字节） | — | depth=0 | **6 passed** | 充分性证明 |

三次坏树跑里 C4（单张平铺面）与 C5（Canvas 图片页签）始终绿 —— 说明红的是「展开腿」这一条行为链，不是整份样式面崩掉。

### 3.2 具体代码与触发机制

1. 缺口位置：`css/design-system.css` 第 641 行 `:root {` 起（`--ds-hit-tap-min` / `--ds-hit-box-min` 两条声明），插入块在两条声明后**直接结束**，没有 `}`；下一行是既有的 `@media (hover: hover) {`。`git show 1a8182372 -- …design-system.css` 可见该 hunk 最后一行是 `+  --ds-hit-box-min: …`，紧接着就是上下文行 ` @media (hover: hover) {`。
2. 触发机制：浏览器不报错而按 CSS 嵌套解析，`641` 之后的 **145 个块（10 个 @ 规则包壳 + 135 条真实样式规则）** 全部被挂进 `:root`；`CSS.getMatchedStylesForNode` 读回的选择器带前导 `&`，`document.styleSheets` 里该表顶层规则只剩 6 条。每条规则因此多得 1 个类名的特异性（(0,1,0)→(0,2,0)）。
3. 胜负条件（三个条件必须同时成立，这也是「之前好好的、现在坏了」的时间点解释）：`.ds-media-stack__expand{display:none}`（本文件 :1423，由 `2e299189d` 2026-11-03 引入，**早于本次回归**，本身没变）+ `.pop-expand-view.show{display:flex}`（`chat.css:4922`，(0,2,0)）+ `index.html` 装载序 chat.css(:54) 先、design-system.css(:114) 后 ⇒ 打平后由后者按文档序胜出。缺括号之前前者是 (0,1,0) 打不过后者，所以同一份 UI 一直正常。
4. 收尾：折叠面此时已被 `setExpanded` 写成 inline `display:none`，两面俱隐 ⇒ 只剩胶囊。

> 反例校准（避免把相关性当因果）：`3e1577be9f`（contact-panel-unify 分支，15:04）该文件块平衡显示 depth=0，看着像「已经修好」，实际是**它的分支线里根本没有 `--ds-hit-tap-*` 这一段**（`tap=False`）。块平衡只在同一谱系上可比 —— 这正是不能用「depth 归零」当修复凭据的原因。

## 四、既有测试为什么没拦住（三层，逐层有据）

**结论：不是「没有测试」，而是三层都漏 —— 静态门禁里没有一条检查 CSS 块结构，唯一跑浏览器行为 spec 的 job 带 `allow_failure: true`，而那一批自己的验收面全是静态判据。**

| 层 | 事实 | 依据 |
|---|---|---|
| 判据层 | `make frontend-gates` / CI gates 全为静态检查（令牌值、选择器字面量、单源、路由、文案），**零条**检查块闭合或浏览器几何 | `Makefile:107`（FRONTEND_GATES 列表 20 项） |
| 执行层 | 唯一执行 `tests/*.spec.mjs` 的 job = `frontend-specs`（`PW_SELFCONTAINED_ONLY=1 npx playwright test`），但该 job **`allow_failure: true`** 且只在 main/beta/release 全量面跑 ⇒ 即使 8 条媒体 spec 全红，流水线照样绿、合并照样过 | `.gitlab-ci.yml:546-556` |
| 验收层 | 该批自报「机检 26 判据改前 15 红→改后全绿」，全是静态判据；那两页视觉核对不看聊天媒体面 | commit `1a8182372` 消息 |
| 旁证 | GitHub 镜像面 Actions 最后一次运行 = 2026-10-29 00:42（此后 10 天的合并全无 CI 记录） | `gh run list` |

**实际后果**：`pop-stack-fan` / `pop-artifacts` / `quote-media-stack` / `pop-media-alignment` 这 4 份媒体 spec 从 2026-11-06 14:32 起就是红的，一直没人跑（见 `existing-specs-before.log`：8 failed / 44 passed）。

## 五、运行态与上线状态核对（`runtime-state.md`）

**结论：现在正在跑的那个实例拿到的仍是坏 CSS —— 修复只在分支上，尚未合并、未重启。**

| 对象 | 事实 |
|---|---|
| 宿主实例 | PID 98327，`sbt run` 从源码跑，启动于 2026-11-07 15:26:58（早于修复提交 20:37），监听 :6138 |
| `src/main/resources/web/css/design-system.css` | mtime 2026-11-06 19:16:25，brace-depth=**1**（坏） |
| `target/scala-3.5.2/classes/web/css/design-system.css`（served 副本） | 同上，depth=**1**（坏） |
| main HEAD | `eec7cbf40`（不含本修复） |

修复生效路径：分支合并 main → 重新编译（覆盖 `target/classes` 下的资源副本）→ 浏览器刷新。只读核对，未重启、未写任何用户数据。

## 六、与本批无关的存量红（两棵树同一条，逐条复核过）

| 面 | 症状 | 复核方式 |
|---|---|---|
| `tests/canvas-stack-view.spec.mjs` | ①b2-after container change (landscape) image refits ≥vp×0.8（顶栏 92 后实测 0.841） | `TB_WEB=/tmp/web-prefix/src/main/resources/web` 同一 spec 复跑 ⇒ **同一条、同读数**失败 |
| `design-tokens` 门禁 | `css/lightbox.css` baseline-color 5→12 | 本批未改该文件（mermaid 灯箱批引入） |
| `glass-unify` 门禁 | `.nf-lightbox-bar` backdrop-filter 双源 `blur(14px)` | 同上 |
| `check-freeze-silence` | 2 条（冻结通知面） | 同上 |

## 七、复现命令（任何人可重跑）

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
