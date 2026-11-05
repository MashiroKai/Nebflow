// todo-tag-delete-mode.spec.mjs — R05（标签 hover 不要动画 + 标签列放垃圾桶）+ R06
// （点垃圾桶后在标签上出现叉号用于删除）门禁。
//
// 条目来源：UI 批次审计 2026-11-04（D 档 9 条之 R05 / R06；台账 #1376，作者 recall
// 2026-11-02 12:05 逐字「标签 hover 不要动画 标签列放垃圾桶图标 点垃圾桶后在标签上
// 出现叉号用于删除」）。改前读数（审计实测）：面板内 `trash/垃圾桶` 命中 **0/0**
// （`todoFace.js` + `todo-face.css`）、hover **有**布局级动画（`padding 3px 6px→3px
// 9px`、dot 6→8px + 450ms 教学气泡）、零叉号入口。
//
// 判据（机械）：
//   R05-a 垃圾桶在场：chips 行内 `.tfl-chip-trash` 存在、可见、有 aria-label；
//   R05-b hover 零动画：真指针悬停 700ms（覆盖原 450ms 气泡延迟）后
//         · chip 盒（left/top/width/height）逐值不变（Δ = 0）；
//         · computed padding / font-size / 点径逐值不变；
//         · 页面**无** `.tfl-hint-layer`（教学气泡已退役）；
//   R06-a 点垃圾桶 ⇒ 每枚标签显叉号：`.tfl-chips-deleting` 类 + 每枚 chip 的
//         `.tfl-chip-x` computed display = inline-flex（改前恒 none/不存在）；
//   R06-b 点叉号 ⇒ 走二次确认删除，确认后该标签从 store 与 DOM 双双消失（其余标签
//         与行不受影响）；
//   R06-c 非删除模式点 chip 仍是视图切换（既有行为不回归），且叉号不可命中。
//
// Run: node node_modules/@playwright/test/cli.js test tests/todo-tag-delete-mode.spec.mjs
//   env：EVID_DIR=<dir> 落 readings.json + 双档截图；TODOFACE_WEB_ROOT=<web 树> 跑改前腿。

import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { bootTodoFace, openPanel, openFilter } from './_todo-face-harness.mjs';

const EVID = process.env.EVID_DIR || null;
const TAG = process.env.EVID_TAG || 'after';

function saveReadings(name, payload) {
  if (!EVID) return;
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, `${name}.${TAG}.json`), JSON.stringify(payload, null, 2));
}

/** 单枚 chip 的几何 + 排版读数（hover 前后逐值比对用）。 */
const chipMetrics = (page, idx = 0) => page.evaluate((i) => {
  const chip = document.querySelectorAll('#task-list .tfl-chip-tag')[i];
  if (!chip) return null;
  const r = chip.getBoundingClientRect();
  const s = getComputedStyle(chip);
  const dot = chip.querySelector('.tfl-chip-dot');
  const name = chip.querySelector('.tfl-chip-name');
  return {
    left: +r.left.toFixed(2), top: +r.top.toFixed(2),
    width: +r.width.toFixed(2), height: +r.height.toFixed(2),
    padding: s.padding, fontSize: s.fontSize,
    dotW: dot ? getComputedStyle(dot).width : null,
    dotH: dot ? getComputedStyle(dot).height : null,
    nameFont: name ? getComputedStyle(name).fontSize : null,
    cls: chip.className,
  };
}, idx);

test.describe('R05 + R06 标签垃圾桶与叉号删除模式', () => {
  test('R05：垃圾桶在场；hover 700ms 后 chip 盒与排版逐值不变、无教学气泡', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await bootTodoFace(page);
    await openPanel(page);
    await openFilter(page);

    if (EVID) {
      mkdirSync(EVID, { recursive: true });
      await page.screenshot({ path: join(EVID, `r05-chips.${TAG}.png`) });
    }
    // R05-a 垃圾桶
    const trash = page.locator('#task-list .tfl-chip-trash');
    await expect(trash).toHaveCount(1);
    await expect(trash).toBeVisible();
    const aria = await trash.getAttribute('aria-label');
    expect(aria && aria.length).toBeGreaterThan(0);

    // R05-b hover 零动画
    const before = await chipMetrics(page, 0);
    const box = await page.locator('#task-list .tfl-chip-tag').first().boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.waitForTimeout(700); // 原教学气泡 450ms 延迟 + 动画时长
    const after = await chipMetrics(page, 0);
    const hintLayers = await page.locator('.tfl-hint-layer').count();
    saveReadings('r05-hover', { before, after, hintLayers, aria });

    expect(after.left).toBeCloseTo(before.left, 2);
    expect(after.top).toBeCloseTo(before.top, 2);
    expect(after.width).toBeCloseTo(before.width, 2);
    expect(after.height).toBeCloseTo(before.height, 2);
    expect(after.padding).toBe(before.padding);
    expect(after.nameFont).toBe(before.nameFont);
    expect(after.dotW).toBe(before.dotW);
    expect(hintLayers).toBe(0);
    expect(after.cls).not.toContain('hovered');

    // 移开指针（后续用例不带 hover 残留）
    await page.mouse.move(5, 5);
    await page.waitForTimeout(120);
  });

  test('R06：点垃圾桶 ⇒ 每枚标签显叉号；点叉号 ⇒ 二次确认后真删除', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await bootTodoFace(page);
    await openPanel(page);
    await openFilter(page);

    // 模式前：叉号不可见（display none）
    const beforeX = await page.evaluate(() => {
      const x = document.querySelector('#task-list .tfl-chip-tag .tfl-chip-x');
      return x ? getComputedStyle(x).display : 'absent';
    });

    // R06-a 点垃圾桶 ⇒ 删除模式
    await page.locator('#task-list .tfl-chip-trash').click();
    await page.waitForTimeout(200);
    const mode = await page.evaluate(() => {
      const row = document.querySelector('#task-list .tfl-chips-row');
      const xs = [...document.querySelectorAll('#task-list .tfl-chip-tag .tfl-chip-x')];
      return {
        rowDeleting: row.classList.contains('tfl-chips-deleting'),
        trashArmed: document.querySelector('#task-list .tfl-chip-trash').getAttribute('aria-pressed'),
        xCount: xs.length,
        xDisplays: xs.map((x) => getComputedStyle(x).display),
        chipCount: document.querySelectorAll('#task-list .tfl-chip-tag').length,
      };
    });
    saveReadings('r06-mode', { beforeX, mode });

    expect(beforeX === 'none' || beforeX === 'absent').toBe(true);
    expect(mode.rowDeleting).toBe(true);
    expect(mode.trashArmed).toBe('true');
    expect(mode.xCount).toBe(mode.chipCount); // 每枚标签都有叉号
    expect(mode.xDisplays.every((d) => d === 'inline-flex' || d === 'flex')).toBe(true);

    // R06-b 点第二枚标签（生活）的叉号 ⇒ 二次确认 ⇒ 真删除
    const targetTagId = await page.evaluate(() => document.querySelectorAll('#task-list .tfl-chip-tag')[1].dataset.tagId);
    await page.locator('#task-list .tfl-chip-tag').nth(1).locator('.tfl-chip-x').click();
    await page.waitForTimeout(300);
    // 确认面 = 全站既有 `showConfirm`（modal.js：复用 #modal-overlay + #delete-box，
    // 主钮 #delete-confirm）。不桩替 window.__showConfirm —— 走真实确认链。
    const confirmSeen = await page.evaluate(() => {
      const overlay = document.getElementById('modal-overlay');
      const box = document.getElementById('delete-box');
      const on = !!(overlay && overlay.classList.contains('on'));
      const shown = !!(box && getComputedStyle(box).display !== 'none');
      return {
        on, shown,
        title: document.getElementById('delete-title')?.textContent || '',
        confirmLabel: document.getElementById('delete-confirm')?.textContent || '',
      };
    });
    await page.locator('#delete-confirm').click();
    await page.waitForTimeout(600);

    const after = await page.evaluate(async () => {
      const store = await import('/js/todoStore.js');
      return {
        storeIds: store.todoStore.tags.map((t) => t.id),
        domIds: [...document.querySelectorAll('#task-list .tfl-chip-tag')].map((c) => c.dataset.tagId),
      };
    });
    saveReadings('r06-deleted', { confirmSeen, targetTagId, after });

    expect(confirmSeen.on).toBe(true); // 破坏性操作走了二次确认（HIG 铁律）
    expect(confirmSeen.shown).toBe(true);
    expect(after.storeIds).not.toContain(targetTagId);
    expect(after.domIds).toEqual(after.storeIds); // 渲染跟账
    expect(after.storeIds.length).toBe(3);

    if (EVID) {
      mkdirSync(EVID, { recursive: true });
      await page.screenshot({ path: join(EVID, `r06-delete-mode.${TAG}.png`) });
    }
  });

  test('R06-c 非删除模式：叉号不可命中、点 chip 仍是视图切换（既有行为不回归）', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await bootTodoFace(page);
    await openPanel(page);
    await openFilter(page);

    // 叉号在非删除模式不占命中（display:none ⇒ 点 chip 右内缘必然命中 chip 本身）
    const hit = await page.evaluate(() => {
      const chip = document.querySelector('#task-list .tfl-chip-tag');
      const r = chip.getBoundingClientRect();
      const hitEl = document.elementFromPoint(r.right - 4, r.top + r.height / 2);
      return { insideChip: chip.contains(hitEl), hitCls: hitEl && hitEl.className };
    });
    expect(hit.insideChip).toBe(true);
    expect(String(hit.hitCls)).not.toContain('tfl-chip-x');

    await page.locator('#task-list .tfl-chip-tag').first().click();
    await page.waitForTimeout(300);
    const view = await page.evaluate(async () => {
      const store = await import('/js/todoStore.js');
      return store.todoStore.activeTagId;
    });
    saveReadings('r06-view', { hit, view });
    expect(view).toBe('tag-work');
  });
});
