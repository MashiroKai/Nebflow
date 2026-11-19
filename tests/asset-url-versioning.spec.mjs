// asset-url-versioning.spec.mjs — 静态资源缓存击穿批（2026-10-25 作者令④）
// 引擎面回归钉（instance-backed：需真实引擎；BASE_URL 缺省 localhost:8080）。
//
// 契约：
//   ① dev 树 index（no-cache）里每个 css/…、vendor/…、js/… 引用都带内容指纹
//     `?v=<16 hex>`（AssetVersions.versionOf 现算）——修完即生效的载体；
//   ② 版本化 URL ⇒ `Cache-Control: public, max-age=31536000, immutable`
//     （内容寻址 ⇒ immutable 永远安全）；裸 URL ⇒ no-cache（模块图 import 保底）；
//   ③ 根文件白名单：favicon（引用自带 ?v= digest）⇒ immutable；sw.js（裸）⇒
//     no-cache 不动（SW 更新即时可见）；
//   ④ 版本化引用在真实引擎上全部 200（无 404 回归 = 形状改写零误伤）。
// dist 脸（/assets/app-*，esbuild 已内容寻址）非本批对象 ⇒ 优雅跳过。
//
// Run: npx playwright test tests/asset-url-versioning.spec.mjs

import { test, expect } from '@playwright/test';

const BASE = process.env.BASE_URL ?? 'http://localhost:8080';
const IMMUTABLE = 'public, max-age=31536000, immutable';

test('index references carry content fingerprints; versioned URLs serve immutable', async ({ request }) => {
  const index = await request.get(`${BASE}/`);
  expect(index.status()).toBe(200);
  const cc = index.headers()['cache-control'] ?? '';
  expect(cc).toContain('no-cache'); // index 恒 no-cache = 唯一新鲜裁判
  const body = await index.text();

  if (body.includes('/assets/app-')) {
    // dist 脸：esbuild bundle（app-[hash]）已是内容寻址形态 —— 本批对象外。
    test.info().annotations.push({ type: 'skip-note', description: 'dist face active (bundled assets already content-addressed)' });
    return;
  }

  const hrefPaths = [...body.matchAll(/href="((?:css|vendor)\/[^"?]+)"/g)].map((m) => m[1]);
  const srcPaths = [...body.matchAll(/src="((?:vendor|js)\/[^"?]+)"/g)].map((m) => m[1]);
  const refs = [...new Set([...hrefPaths, ...srcPaths])];
  expect(refs.length).toBeGreaterThan(10);
  const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  for (const p of refs) {
    // 每个引用必须以 `?v=<16 hex>` 形态在场（版本化本体）。
    expect(new RegExp(`${esc(p)}\\?v=[0-9a-f]{16}`).test(body), `${p} must be fingerprinted`).toBe(true);
  }

  // ② 版本化 URL 可取回且 immutable；同体裸 URL no-cache。
  const probe = refs.find((p) => p.endsWith('.css')) ?? refs[0];
  const fp = (body.match(new RegExp(`${esc(probe)}\\?v=([0-9a-f]{16})`)) ?? [])[1];
  expect(fp).toBeTruthy();
  const versioned = await request.get(`${BASE}/${probe}?v=${fp}`);
  expect(versioned.status()).toBe(200);
  expect(versioned.headers()['cache-control']).toBe(IMMUTABLE);
  const bare = await request.get(`${BASE}/${probe}`);
  expect(bare.status()).toBe(200);
  expect(bare.headers()['cache-control']).toBe('no-cache');
});

test('root whitelist: digest-versioned favicon is immutable; sw.js stays no-cache', async ({ request }) => {
  const fav = await request.get(`${BASE}/favicon-32.png?v=71a456e2d388b27e`);
  expect(fav.status()).toBe(200);
  expect(fav.headers()['cache-control']).toBe(IMMUTABLE);
  const sw = await request.get(`${BASE}/sw.js`);
  expect(sw.status()).toBe(200);
  expect(sw.headers()['cache-control']).toBe('no-cache');
});

test('boot: every versioned static request resolves (zero asset 404s)', async ({ page }) => {
  const bad = [];
  page.on('response', (resp) => {
    const u = new URL(resp.url());
    if (/^\/(js|css|vendor)\//.test(u.pathname) && resp.status() >= 400) {
      bad.push(`${resp.status()} ${u.pathname}${u.search}`);
    }
  });
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle', timeout: 30_000 }).catch(() => {
    /* 登录墙/网关形态差异不影响静态面 —— 只看已发生的资源应答 */
  });
  expect(bad).toEqual([]);
});
