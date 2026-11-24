import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
test.use({ viewport: { width: 375, height: 812 } });
test('mobile live thinking wraps without clipping', async ({ page }) => {
  await page.route('**/*', route => {
    const path = decodeURIComponent(new URL(route.request().url()).pathname);
    const file = normalize(join(root, path));
    if (!file.startsWith(root + '/')) return route.fulfill({ status: 403 });
    try { return route.fulfill({ body: readFileSync(file), contentType: types[extname(file)] || 'application/octet-stream' }); }
    catch { return route.fulfill({ status: 404 }); }
  });
  await page.goto('http://localhost:1/tests/fixtures/stream-ux/harness.html');
  await page.waitForFunction(() => window.__ready === true);
  await page.evaluate(async () => {
    for(let i=0;i<30;i++) await window.__userSlide();
    await window.__thinkingRun();
    const chat = await import('/src/main/resources/web/js/chat.js');
    chat.appendThinkingDelta(' Long internal progress text that is wider than a mobile screen and must not silently disappear '.repeat(6));
  });
  const think = page.locator('.nf-workline .nf-wl-think');
  await expect(think).toBeVisible();
  const metrics = await think.evaluate(el => ({ left: el.scrollLeft, width: el.clientWidth, scroll: el.scrollWidth, ellipsis: getComputedStyle(el).textOverflow, line: getComputedStyle(el).webkitLineClamp, wrap: getComputedStyle(el).whiteSpace }));
  expect(metrics.left).toBe(0);
  expect(metrics.wrap).not.toBe('nowrap');
  expect(metrics.scroll).toBeLessThanOrEqual(metrics.width + 1);
  const chat=page.locator('#chat'); await chat.evaluate(el=>{el.style.scrollBehavior='auto'});
  for(const f of [0,0.5,1]) {
    const state=await chat.evaluate((el,fraction)=>{el.scrollTop=(el.scrollHeight-el.clientHeight)*fraction; return {scroll:el.scrollTop,max:el.scrollHeight-el.clientHeight}},f);
    expect(state.max).toBeGreaterThan(0); expect(Math.abs(state.scroll-state.max*f)).toBeLessThan(2);
  }
  await think.evaluate(el=>el.scrollIntoView({block:'center',behavior:'instant'}));
  const rect = await think.boundingBox();
  expect(rect.x).toBeGreaterThanOrEqual(0); expect(rect.x+rect.width).toBeLessThanOrEqual(375);
  const collision = await think.evaluate(el => {const a=el.getBoundingClientRect(), s=el.closest('.nf-wl-slot').getBoundingClientRect(); return {height:a.height,slotHeight:s.height,top:a.top,slotTop:s.top,bottom:a.bottom,slotBottom:s.bottom};});
  expect(collision.height).toBeGreaterThan(18); expect(collision.top).toBeGreaterThanOrEqual(collision.slotTop-1); expect(collision.bottom).toBeLessThanOrEqual(collision.slotBottom+1);
  const out=process.env.MOBILE_AUDIT_EVIDENCE;
  if(out) {mkdirSync(out,{recursive:true}); await page.screenshot({path:join(out,'聚焦条.png'),animations:'disabled'});}
  await page.evaluate(()=>window.__fullTurnCollapse());
  const settled=page.locator('.turn-header').last(); await expect(settled).toBeVisible(); await settled.click();
  await expect(settled).toBeVisible();
});
