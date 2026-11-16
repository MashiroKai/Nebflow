import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
const web = join(process.cwd(), 'src/main/resources/web');
const mime = {'.js':'text/javascript','.json':'application/json','.css':'text/css'};
test('FRM-SCN-001 actual provider form keeps values after failed confirmation and closes on retry', async ({ page }) => {
  await page.route('**/*', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/') return route.fulfill({contentType:'text/html',body:'<!doctype html><body></body>'});
    const file = normalize(join(web, path));
    if (!file.startsWith(web + '/')) return route.abort();
    try { return route.fulfill({contentType:mime[extname(file)] || 'text/plain',body:readFileSync(file)}); }
    catch { return route.fulfill({status:404,body:''}); }
  });
  await page.goto('http://localhost:8380/');
  await page.evaluate(async () => {
    const { showProviderForm } = await import('/js/sidebar/providersUi.js');
    window.__calls = 0;
    showProviderForm({title:'Provider', fields:[{key:'name',label:'Name',type:'text',value:''}], onConfirm: async () => ++window.__calls > 1 });
  });
  const field = page.locator('#cfg-modal [data-field="name"]');
  await field.fill('draft name');
  await page.locator('#cfg-modal-save').click();
  await expect(field).toHaveValue('draft name');
  await expect(page.locator('#cfg-modal')).toBeVisible();
  await page.locator('#cfg-modal-save').click();
  await expect(page.locator('#cfg-modal')).toHaveCount(0);
  expect(await page.evaluate(() => window.__calls)).toBe(2);
});

// （FRM-SCN-004 JeV probe 腿已随 JeV 全量退役删除——jev-retire 批 2026-11-16：
// js/sidebar/jevTestButton.js 与 /api/jev/test 随 jev 域下线。）
