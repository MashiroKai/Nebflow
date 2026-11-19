// canvas-save-failure-dedup.spec.mjs — Canvas「保存失败」通知的**前端门禁**
// （canvas-save-failure 批，2026-11-18 P0）。静态面见
// scripts/check-canvas-save-notice.mjs。
//
// 装置：真 node http 服务器（复刻 ConsoleTakeover/ConfigRoutes 的单写者席位语义，
// 非抢占；席位被占即 409）+ 真 src/main/resources/web + 真 js/canvas.js。每个
// /api/canvas* 调用都是真实 HTTP，状态码与响应体原文可读。
//
// 钉住两条作者点名的判据：
//   A 保存成功路径绝不触发失败通知（零误报）；
//   B 同一失败原因只产生一条通知 —— 页内多次写收敛成一条，且**刷新不重弹**
//     （页内 Set 会在刷新时清空 ⇒ 席位被持久占住时每次加载重弹 = 作者报的「老是」）。

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

test('B: 同一次失败只产生一条通知，且刷新不重弹', async ({ browser }) => {
  server.clearSeat();
  await server.seedGhostSeat();               // 席位被一个已消失的窗口占住
  const { context, page, errors } = await bootPage(browser, server.base);
  try {
    // B1 页内：连写三次 ⇒ 服务端三次拒授，通知仍只有一条。
    for (let i = 0; i < 3; i++) await driveWrite(page, `r-${i}`);
    const refusals = server.net.filter((n) => n.path.endsWith('/claim') && n.status === 409);
    expect(refusals.length, 'the server must genuinely refuse the write (409)').toBeGreaterThan(0);
    expect(refusals[0].body).toContain('writer_already_held');
    let t = await toasts(page);
    expect(t.filter((x) => x.includes(FAIL))).toHaveLength(1);

    // B2 跨刷新：同因不再重弹（这就是「老是」的根 — 席位持久占住 + 页内 Set 随刷新清空）。
    for (let i = 0; i < 2; i++) {
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.waitForSelector('#activity-bar', { state: 'attached' });
      await driveWrite(page, `r-reload-${i}`);
      t = await toasts(page);
      expect(t.filter((x) => x.includes(FAIL)), `reload ${i + 1} must not re-pop the same cause`).toHaveLength(0);
    }
    expect(errors).toEqual([]);
  } finally { await context.close(); }
});

test('C: 通知带可执行的下一步，且可亲手关闭', async ({ browser }) => {
  server.clearSeat();
  await server.seedGhostSeat();
  const { context, page } = await bootPage(browser, server.base);
  try {
    await driveWrite(page, 'c-1');
    const toast = page.locator('.nebflow-toast', { hasText: FAIL }).first();
    await expect(toast).toBeVisible();
    // 可执行的下一步：次级一行 hint，不是一句「保存失败」。
    const hint = toast.locator('.nebflow-toast-hint');
    await expect(hint).toBeVisible();
    const hintText = (await hint.innerText()).trim();
    expect(hintText.length).toBeGreaterThanOrEqual(12);
    // 可关闭：亲手关闭钮在场，点掉即消失。
    const close = toast.locator('.nebflow-toast-close');
    await expect(close).toBeVisible();
    await close.click();
    await expect(page.locator('.nebflow-toast', { hasText: FAIL })).toHaveCount(0);
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
