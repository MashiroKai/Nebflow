# R05 + R06 标签垃圾桶与叉号删除模式 — 证据

条目：UI 批次审计 2026-11-04 D 档 R05 / R06 / 台账 #1376（作者 recall 2026-11-02 12:05 逐字：「标签 hover 不要动画 标签列放垃圾桶图标 点垃圾桶后在标签上出现叉号用于删除」）
批：ui-batch-gap-close（delegate-nebflow-47a71c60）

## 改前（红）读数
| 判据 | 改前 |
|---|---|
| `trash/垃圾桶` 命中（`todoFace.js` + `todo-face.css`） | **0 / 0**（垃圾桶整套不存在） |
| chips 行 `.tfl-chip-trash` | **不存在**（spec 断言 `toHaveCount(1)` 直接红） |
| 每枚标签 `.tfl-chip-x` | **不存在** ⇒ 无删除模式入口 |
| hover 700ms 后 chip 盒 | 有布局级动画：`padding 3px 6px→3px 9px`、dot 6→8px、字号 10→11.5px + 450ms 教学气泡层 |

## 改后（绿）读数
| 判据 | 改后 |
|---|---|
| 垃圾桶 | 1 枚在场、可见、`aria-label="删除标签"` |
| hover 700ms 后 chip 盒 | `left 396 / top 152 / w 44 / h 26` **逐值不变**；`padding 3px 6px`、`font-size 10px`、dot 6×6px 全部不变 |
| 教学气泡 | `.tfl-hint-layer` 计数 **0**（整链退役） |
| 点垃圾桶 ⇒ 删除模式 | `.tfl-chips-deleting` 在行上、`aria-pressed=true`；**4/4 枚**标签叉号 `display:flex` |
| 点叉号 ⇒ 删除 | 走全站 `showConfirm`（`#modal-overlay.on` + `#delete-box` 显示、标题「删除标签」、主钮「删除」）⇒ 确认后 `tag-life` 从 store 与 DOM 双双消失（`["tag-work","tag-study","tag-idea"]` 两边逐值相同） |
| 非删除模式 | 叉号 `display:none` ⇒ 点 chip 右内缘命中 chip 本体（非叉号）；点 chip 仍切视图（`activeTagId="tag-work"`） |

## 命令原文
```bash
TODOFACE_WEB_ROOT=/tmp/uibatch-before-web/src/main/resources/web EVID_DIR=…/R05R06 EVID_TAG=before \
node node_modules/@playwright/test/cli.js test tests/todo-tag-delete-mode.spec.mjs   # → 2 failed / 1 passed（红；第 3 支是既有行为不回归腿，改前改后皆绿）
EVID_DIR=…/R05R06 EVID_TAG=after node node_modules/@playwright/test/cli.js test tests/todo-tag-delete-mode.spec.mjs   # → 3 passed（绿）
```

## 落地改动
- `js/todoFace.js`
  - chips 行尾新增 `.tfl-chip-trash`（`lucideIcon('trash-2')` + `aria-pressed` 镜像），点按 ⇒ `toggleChipDeleteMode()`。
  - `buildTagChip` 每枚标签常驻 `.tfl-chip-x`（`role=button` 的 span，SVG 走命名空间 API，形状色交给 CSS）；点击 = `confirmDeleteTag()` 既有二次确认单点。
  - **整套悬停动画与教学气泡退役**：`onChipHover` / `showHint` / `chevSvg` / `dismissHint` 四函数 + `chipHover`/`hintTimer`/`hintLayer` 三状态位 + 5 处调用点 + outside-close 的 hintLayer 判据全部摘除。
- `css/todo-face.css`
  - `.tfl-chip` transition 去掉 `padding 0.32s` / `font-size 0.32s` 两条布局级弹簧；`.tfl-chip-dot` 去点径弹簧。
  - `.tfl-chip-tag.hovered` 四条规则删除；`.tfl-hint*` / `.tfl-chev*` / 四支 keyframes 整块删除（文件头与变量注释留退役说明）。
  - 新增 `.tfl-chip-trash`（24×26，`--ds-radius-chip`）、`.tfl-chip-x`（11px，`display:none` 常驻）、`.tfl-chips-row.tfl-chips-deleting .tfl-chip-x{display:inline-flex}`。

## 设计取舍（如实申报）
- 叉号用 `span[role=button]` 而非 `button`：chip 本身是 `button`，嵌套 `button` 违反 HTML 内容模型。
- 删除仍走二次确认（作者 HIG 铁律「破坏性操作必须二次确认」）：R06 的「叉号用于删除」入口成立，确认面只是既有单点，不是新增弹窗。
- 退役**真删**而非留注释：审计 R08 已把「保留的死码」列为缺陷形态，此处按同一判据处理。
