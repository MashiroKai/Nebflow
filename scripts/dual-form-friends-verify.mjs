// dual-form-friends-verify.mjs — 好友构建环境区分·双形态真机断言（e2e 证据）。
// dev  :8095（classpath 前置 worktree src/main/resources → serve 源码树）
// rel  :8096（classpath 前置 webdist-sim.jar → hasBundledDist serve web-dist，
//             模拟 ci.yml `sbt -D<lowerName>.webdist=1 assembly` 产物挂载）
// 口径（作者裁定 2026-09-14，friend-gate 解封 D1②）：剔除默认关闭 ⇒ **两侧
// 均为露出形态**（friendsEnabled() === true + 四节点可见）；再封形态由
// `node scripts/build-web.mjs --strip-friends` 产出，其门禁见
// tests/release-strip-friends.static.mjs --expect stripped。
// Run: node scripts/dual-form-friends-verify.mjs  (两实例须已在跑)
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';

const DEV = 'http://127.0.0.1:8095';
const REL = 'http://127.0.0.1:8096';
const devToken = JSON.parse(readFileSync('/tmp/qa-gating-split/dev-home/auth.json', 'utf8'));
const relToken = JSON.parse(readFileSync('/tmp/qa-gating-split/rel-home/auth.json', 'utf8'));

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

const browser = await chromium.launch();

async function boot(base, token, tag) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  await page.addInitScript(t => {
    localStorage.setItem('nebflow_token', t);
    localStorage.setItem('nebflow_onboarding', 'done');
  }, token);
  await page.goto(base + '/');
  await sleep(3500); // WS boot + configData latch + onboarding settle
  return { ctx, page };
}

// ── DEV 形态（源码树直跑）：默认开，入口可见可进 ──
{
  const { ctx, page } = await boot(DEV, devToken, 'dev');
  const shots = '/tmp/qa-gating-split';
  const flag = await page.evaluate(async () => (await import('/js/featureFlags.js')).friendsEnabled());
  ok('DEV friendsEnabled() === true (no config needed)', flag === true, String(flag));
  ok('DEV #messages-btn visible', await page.locator('#messages-btn').isVisible());
  ok('DEV #contacts-btn visible', await page.locator('#contacts-btn').isVisible());
  await page.click('#contacts-btn');
  await sleep(400);
  ok('DEV contacts panel opens on click', await page.locator('#panel-contacts.active').count() === 1);
  await page.screenshot({ path: shots + '/DEV-form-entries-on.png' });
  await ctx.close();
}

// ── RELEASE 形态（web-dist 产物）：露出（2026-09-14 解封后与 DEV 同向）──
{
  const { ctx, page } = await boot(REL, relToken, 'rel');
  const shots = '/tmp/qa-gating-split';
  const flag = await page.evaluate(async () => (await import('/js/featureFlags.js')).friendsEnabled()).catch(() => 'IMPORT_FAIL');
  ok('REL friendsEnabled() === true (unsealed bundle)', flag === true, String(flag));
  ok('REL #messages-btn visible', await page.locator('#messages-btn').isVisible());
  ok('REL #contacts-btn visible', await page.locator('#contacts-btn').isVisible());
  ok('REL #panel-messages present', await page.locator('#panel-messages').count() === 1);
  ok('REL #panel-contacts present', await page.locator('#panel-contacts').count() === 1);
  // 设备互联（NebLink）不 gating：头像（登录入口）仍在
  ok('REL #activity-avatar still present (device interconnect NOT gated)', await page.locator('#activity-avatar').isVisible());
  await page.screenshot({ path: shots + '/REL-form-entries-on.png' });
  await ctx.close();
}

await browser.close();
console.log(failures === 0 ? '\nDUAL-FORM ALL PASS' : `\n${failures} FAILED`);
process.exit(failures ? 1 : 0);
