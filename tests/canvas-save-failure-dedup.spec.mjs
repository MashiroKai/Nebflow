// canvas-save-failure-dedup.spec.mjs — Canvas「保存失败」**静默保留**的前端门禁。
// 历史：2026-11-18 P0 加过去重通知；2026-11-22 作者令「删掉这个弹窗」→ 失败不再弹任何通知，
// 本地兜底保留。静态面见 scripts/check-canvas-save-notice.mjs。
//
// 装置：真 node http 服务器（复刻 ConsoleTakeover/ConfigRoutes 的单写者席位语义，
// 非抢占；席位被占即 409）+ 真 src/main/resources/web + 真 js/canvas.js。
//
// 钉住三条判据：
//   A 保存成功路径绝不触发失败通知（零误报）；
//   B 写者席位被占导致的保存失败：页内多次写、刷新后均**不弹任何通知**（静默）；
//   C 失败时本地兜底仍在：本地页签不丢，写路径照常发出（409 真实落在服务端）。

import { test, expect } from '@playwright/test';
import { startSeatServer, bootPage, driveWrite, toasts } from './_canvas-save-failure-harness.mjs';

const FAIL = 'Canvas 保存失败';

const server = await startSeatServer(0);
test.afterAll(async () => { await server.close(); });

test('A: 保存成功路径绝不触发失败通知（写入真落地 200）', async ({ browser }) => {
  server.clearSeat();
  const { context, page, errors } = await bootPage(browser, server.base);
  try {
    await driveWrite(page, 'ok-1');
    await driveWrite(page, 'ok-2');
    await driveWrite(page, 'ok-3');
    const t = await toasts(page);
    const puts = server.net.filter((n) => n.method === 'PUT' && n.path === '/api/canvas-tabs');
    expect(puts.length, 'the write path must actually reach PUT').toBeGreaterThan(0);
    expect(puts.every((p) => p.status === 200), JSON.stringify(puts)).toBe(true);
    expect(t.filter((x) => x.includes(FAIL))).toEqual([]);
    expect(errors).toEqual([]);
  } finally { await context.close(); }
});

test('B: 席位被占的保存失败静默，页内多次写与刷新后均不弹任何通知', async ({ browser }) => {
  server.clearSeat();
  await server.seedGhostSeat();               // 席位被一个已消失的窗口占住
  const { context, page, errors } = await bootPage(browser, server.base);
  try {
    // B1 页内：连写三次 ⇒ 服务端三次拒授，且零通知。
    for (let i = 0; i < 3; i++) await driveWrite(page, `r-${i}`);
    const refusals = server.net.filter((n) => n.path.endsWith('/claim') && n.status === 409);
    expect(refusals.length, 'the server must genuinely refuse the write (409)').toBeGreaterThan(0);
    expect(refusals[0].body).toContain('writer_already_held');
    let t = await toasts(page);
    expect(t.filter((x) => x.includes(FAIL))).toHaveLength(0);

    // B2 跨刷新：仍然静默。
    for (let i = 0; i < 2; i++) {
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.waitForSelector('#activity-bar', { state: 'attached' });
      await driveWrite(page, `r-reload-${i}`);
      t = await toasts(page);
      expect(t.filter((x) => x.includes(FAIL)), `reload ${i + 1} must stay silent`).toHaveLength(0);
    }
    expect(errors).toEqual([]);
  } finally { await context.close(); }
});

test('C: 失败时本地兜底保留——页签不丢，且无任何通知节点出现', async ({ browser }) => {
  server.clearSeat();
  await server.seedGhostSeat();
  const { context, page } = await bootPage(browser, server.base);
  try {
    await driveWrite(page, 'c-1');
    await driveWrite(page, 'c-2');
    await page.waitForTimeout(800);
    // 本地页签仍在（静默保留的内容可见）。
    await expect(page.locator('.canvas-tab-pane[data-tab-id="c-1"]')).toHaveCount(1);
    await expect(page.locator('.canvas-tab-pane[data-tab-id="c-2"]')).toHaveCount(1);
    // 失败不弹通知：DOM 中无任何保存失败 toast（含 hint 次级行）。
    await expect(page.locator('.nebflow-toast', { hasText: FAIL })).toHaveCount(0);
    await expect(page.locator('.nebflow-toast-hint')).toHaveCount(0);
  } finally { await context.close(); }
});

test('D: 普通通知面零回退（不带 opts 的 showToast 仍正常渲染正文）', async ({ browser }) => {
  server.clearSeat();
  const { context, page } = await bootPage(browser, server.base);
  try {
    // 富通知批把 showToast 改成 icon + 正文列；普通通知必须仍走 icon + msg 平铺。
    // 这条闸钉住「普通 toast 不因新结构自嵌套（HierarchyRequestError）而整体失效」。
    const errs = [];
    page.on('pageerror', (e) => errs.push(String(e.message)));
    await page.evaluate(() => { window.__showToast?.('PLAIN_TOAST_PROBE', 'info'); });
    await expect(page.locator('.nebflow-toast-msg', { hasText: 'PLAIN_TOAST_PROBE' })).toHaveCount(1);
    // 不带 opts 时不得出现富结构。
    await expect(page.locator('.nebflow-toast-rich')).toHaveCount(0);
    expect(errs).toEqual([]);
  } finally { await context.close(); }
});
