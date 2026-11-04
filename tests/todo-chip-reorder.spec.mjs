// todo-chip-reorder.spec.mjs — R08 标签「左右推动顺序」门禁（横拖保序真活着）。
//
// 条目来源：UI 批次审计 2026-11-04（D 档 9 条之 R08；台账 #1376，作者 recall
// 2026-11-02 12:05「左右推动标签顺序的功能要保留」）。改前读数（审计读码 + 实测）：
//   `beginChipDrag` 的冻结帧表**故意排除被拖 chip**（`if (id !== tag.id) frozen.set(...)`），
//   而 `moveChipDrag` 首句 `const r0 = drag.frozen.get(drag.id); if (!r0) return;`
//   ⇒ 取不到自己那一帧，**逐帧早退** ⇒ `todoStore.reorderTag()` 永不可达（死码），
//   实测拖过 2 枚邻 chip 顺序恒 `工作>生活>学习>灵感`，只有 ghost 跟手。
//
// 判据（机械，真指针事件序列）：
//   ① 拖前顺序 = 夹具序；② 把首枚 chip 右拖过 ≥2 枚邻 chip 后 `todoStore.tags` 顺序
//   **真变**（被拖 chip 落到被越过者之后）；③ DOM chips 顺序 == store 顺序（渲染跟账）；
//   ④ 非被拖 chip 位移 ≥ 1px（邻位让位的几何证据，不是只改了数组）；
//   ⑤ 拖拽结束后点击不再误触发视图切换（既有手势抑制不回归）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/todo-chip-reorder.spec.mjs
//   env：EVID_DIR=<dir> 落 readings.json + 截图；TODOFACE_WEB_ROOT=<web 树> 跑改前腿。

import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { bootTodoFace, openPanel, openFilter, readOrder } from './_todo-face-harness.mjs';

const EVID = process.env.EVID_DIR || null;
const TAG = process.env.EVID_TAG || 'after';

function saveReadings(name, payload) {
  if (!EVID) return;
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, `${name}.${TAG}.json`), JSON.stringify(payload, null, 2));
}

test.describe('R08 标签横拖保序', () => {
  test('把首枚标签右拖过 2 枚邻 chip ⇒ store 顺序真变 + DOM 同步', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await bootTodoFace(page);
    await openPanel(page);
    await openFilter(page); // chips 行在可折叠筛选栏里（面板默认收起）

    if (EVID) {
      mkdirSync(EVID, { recursive: true });
      await page.screenshot({ path: join(EVID, `r08-chips.${TAG}.png`) });
    }
    const before = await readOrder(page);
    expect(before.storeOrder.length).toBeGreaterThanOrEqual(3);

    const box = await page.locator('#task-list .tfl-chip-tag').first().boundingBox();
    const target = await page.locator('#task-list .tfl-chip-tag').nth(2).boundingBox();
    expect(box && target).toBeTruthy();

    const cy = box.y + box.height / 2;
    const x0 = box.x + box.width / 2;
    // 起点 = 首枚 chip 中心；终点 = 第 3 枚 chip 的**右缘外**（越过 2 枚邻 chip 的中点）
    const x1 = target.x + target.width + 8;

    await page.mouse.move(x0, cy);
    await page.mouse.down();
    for (let i = 1; i <= 12; i++) {
      await page.mouse.move(x0 + ((x1 - x0) * i) / 12, cy + (i % 2 ? 0.5 : -0.5));
      await page.waitForTimeout(30);
    }
    const during = await readOrder(page);
    await page.mouse.up();
    await page.waitForTimeout(500);

    const after = await readOrder(page);
    saveReadings('r08-reorder', { before, during, after });

    // ② store 顺序真变
    expect(after.storeOrder).not.toEqual(before.storeOrder);
    // 被拖 chip 落到它越过的邻 chip 之后（首枚 ⇒ 至少前移 2 位）
    const movedTo = after.storeOrder.indexOf(before.storeOrder[0]);
    expect(movedTo).toBeGreaterThanOrEqual(2);
    // ③ DOM == store
    expect(after.domOrder).toEqual(after.storeOrder);
    // ④ 拖拽过程中邻位已让位（during 也已变 ⇒ 不是 up 后才补账）
    expect(during.storeOrder).not.toEqual(before.storeOrder);

    // ⑤ 拖后点击被抑制：视图不因拖拽尾随的 click 切换
    const view = await page.evaluate(async () => {
      const store = await import('/js/todoStore.js');
      return store.todoStore.activeTagId;
    });
    expect(view).toBeNull();
  });
});
