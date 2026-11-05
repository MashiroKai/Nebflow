# R08 标签横拖保序 — 证据

条目：UI 批次审计 2026-11-04 D 档 R08 / 台账 #1376（作者 recall 2026-11-02 12:05「左右推动标签顺序的功能要保留」）
批：ui-batch-gap-close（delegate-nebflow-47a71c60）

## 根因（改前读码）
`beginChipDrag` 冻结帧表**排除被拖 chip**：
```js
for (const [id, elx] of (this.chipEls || new Map())) {
  if (id !== tag.id) frozen.set(id, elx.getBoundingClientRect());   // ← 改前
}
```
而 `moveChipDrag` 首句就取它自己那一帧：
```js
const r0 = drag.frozen.get(drag.id);
if (!r0) return;                                                    // ← 恒早退
```
⇒ `todoStore.reorderTag()` 永不可达（死码）。

## 改前（红）/ 改后（绿）读数（真指针序列：首枚 chip 中心 → 第 3 枚右缘外 8px）
| | store 顺序 | DOM 顺序 |
|---|---|---|
| 改前 拖前 | `work, life, study, idea` | 同左 |
| 改前 **拖后** | `work, life, study, idea`（**不变 = 死码**） | 同左 |
| 改后 拖前 | `work, life, study, idea` | 同左 |
| 改后 **拖后** | **`life, study, work, idea`**（真换位，越过 2 枚） | **同 store 逐值相同** |

（改后读数同时验证：拖拽途中 `during.storeOrder` 已变 ⇒ 邻位让位是拖动中发生的，不是抬手后补账。）

## 命令原文
```bash
TODOFACE_WEB_ROOT=/tmp/uibatch-before-web/src/main/resources/web EVID_DIR=…/R08 EVID_TAG=before \
node node_modules/@playwright/test/cli.js test tests/todo-chip-reorder.spec.mjs   # → 1 failed（红）
EVID_DIR=…/R08 EVID_TAG=after node node_modules/@playwright/test/cli.js test tests/todo-chip-reorder.spec.mjs   # → 1 passed（绿）
```

## 落地改动
- `js/todoFace.js` `beginChipDrag`：冻结帧表改为**全量**（含被拖 chip 自己，`elx.isConnected` 守卫），`r0` 因此可得；越位判据与 `centerAdjust` 补偿语义不变（源 chip 不动、ghost 跟手）。
- 新增门禁 `tests/todo-chip-reorder.spec.mjs`（改前绿不了、改后必须绿：store 序变 + DOM 同步 + 拖动中已让位 + 拖后 click 被抑制）。

## 文件
`r08-reorder.before.json` / `r08-reorder.after.json` / `r08-chips.*.png`
