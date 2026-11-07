// cache-check.mjs — 先排除「前端静态资源陈旧 / SW 接管旧缓存」这个低成本假设
// 三条独立证据链：
//   A. 服务端 index.html 的 ?v=<指纹> 是否等于该资源的真实内容 sha256 前16（不符 ⇒ immutable 缓存会长期钉住旧字节）
//   B. SW 缓存里的索引哈希 vs 线上索引哈希 + 缓存条目数/键
//   C. 冷 context（CDP 禁用缓存 + 无存储）与温 context（持久 profile、SW 已接管）同一页面的渲染指纹对比
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/ui-audit/reports-evidence/ui-audit-post-restart';
const BASE = 'http://localhost:6138/';
const OUT = path.join(ROOT, '00-cache');
const DATA = path.join(ROOT, '_data');
fs.mkdirSync(OUT, { recursive: true });
fs.mkdirSync(DATA, { recursive: true });
const sha16 = (b) => crypto.createHash('sha256').update(b).digest('hex').slice(0, 16);
const mint = () => execFileSync('/Users/kaiyu/.stand/bin/stand', ['token'], { encoding: 'utf8' })
  .match(/^\s*([A-Za-z0-9_-]{20,})\s*$/m)[1];

const result = {};

// ── A. 服务端引用的 ?v= 是否等值真实内容 ──
const indexHTML = await (await fetch(BASE)).text();
fs.writeFileSync(path.join(DATA, 'index.served.html'), indexHTML);
const refs = [...indexHTML.matchAll(/(?:href|src)="([^"]+\.(?:css|js))(?:\?v=([0-9a-f]+))?"/g)]
  .map((m) => ({ url: m[1], v: m[2] || null }))
  .filter((r, i, a) => a.findIndex((x) => x.url === r.url) === i);
const assetRows = [];
for (const r of refs) {
  try {
    const res = await fetch(new URL(r.url, BASE));
    const buf = Buffer.from(await res.arrayBuffer());
    const real = sha16(buf);
    assetRows.push({ url: r.url, declared: r.v, real, ok: !r.v || r.v === real, status: res.status, bytes: buf.length, cc: res.headers.get('cache-control') });
  } catch (e) {
    assetRows.push({ url: r.url, declared: r.v, error: String(e).slice(0, 80) });
  }
}
result.assets = {
  total: assetRows.length,
  missingVersion: assetRows.filter((r) => !r.declared).length,
  mismatched: assetRows.filter((r) => r.declared && r.real && r.declared !== r.real),
  notOk: assetRows.filter((r) => r.status && r.status >= 400),
  rows: assetRows,
};

// ── B/C. 浏览器侧 ──
const DOMHASH = () => {
  // 渲染指纹：结构 + 关键面 computed style + 资源清单（不含动态内容文本）
  const css = (sel) => {
    const e = document.querySelector(sel);
    if (!e) return null;
    const cs = getComputedStyle(e);
    return [cs.backgroundColor, cs.color, cs.fontSize, cs.borderRadius, cs.padding, cs.width, cs.height, cs.zIndex].join('|');
  };
  const nodes = [...document.querySelectorAll('body *')].slice(0, 4000).map((e) => e.tagName + (e.id || '') + ((e.className || '').toString().split(/\s+/)[0] || ''));
  const res = performance.getEntriesByType('resource').map((r) => `${r.name.replace(location.origin, '')}@${Math.round(r.duration)}`).sort();
  return {
    nodes: nodes.length,
    sig: nodes.join(',').length,
    resourceCount: res.length,
    resources: res,
    skins: {
      body: css('body'), bar: css('#activity-bar'), sidebar: css('#sidebar'),
      chat: css('#chat'), input: css('#input-bar'), header: css('#header'),
    },
    sw: { controlled: !!navigator.serviceWorker?.controller },
  };
};

const browser = await chromium.launch({ headless: true });

// 温 context：持久 profile，两次访问（第一次安装 SW 并填缓存，第二次是「旧 context」形态）
const profileDir = path.join(DATA, 'warm-profile');
const warm = await chromium.launchPersistentContext(profileDir, {
  headless: true, viewport: { width: 1440, height: 900 }, colorScheme: 'light', locale: 'zh-CN',
});
const wp = warm.pages()[0] || (await warm.newPage());
const warmRuns = [];
for (let i = 1; i <= 2; i++) {
  await wp.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
  await wp.waitForTimeout(9000);
  const f = path.join(OUT, `light-desktop__warm-run${i}.png`);
  await wp.screenshot({ path: f, animations: 'disabled' });
  const fp = await wp.evaluate(DOMHASH);
  const cacheInfo = await wp.evaluate(async () => {
    const names = await caches.keys().catch(() => []);
    const detail = {};
    for (const n of names) {
      const c = await caches.open(n);
      const keys = await c.keys();
      detail[n] = keys.length;
      if (n.includes('index')) {
        const r = await c.match('/__nfsw-index-hash');
        detail[n + ':hash'] = r ? (await r.text()).slice(0, 32) : null;
      }
    }
    return { names, detail };
  });
  warmRuns.push({ run: i, shot: f, fp, cacheInfo, bytes: fs.statSync(f).size });
}

// 冷 context：全新存储 + CDP 全面禁用缓存 + 反注册 SW
const cold = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light', locale: 'zh-CN' });
const cp = await cold.newPage();
const cdp = await cold.newCDPSession(cp);
await cdp.send('Network.enable');
await cdp.send('Network.setCacheDisabled', { cacheDisabled: true });
await cp.addInitScript(() => {
  try { navigator.serviceWorker?.getRegistrations?.().then((rs) => rs.forEach((r) => r.unregister())); } catch (_) {}
});
await cp.goto(`${BASE}?ticket=${encodeURIComponent(mint())}`, { waitUntil: 'domcontentloaded' });
await cp.waitForTimeout(9000);
const coldShot = path.join(OUT, 'light-desktop__cold-nocache.png');
await cp.screenshot({ path: coldShot, animations: 'disabled' });
const coldFP = await cp.evaluate(DOMHASH);

// 像素级差异（PNG 解码后的粗粒度比较：按行取字节指纹，够判定「是否同一渲染」）
const cmp = (a, b) => {
  const A = fs.readFileSync(a), B = fs.readFileSync(b);
  return { identicalBytes: A.length === B.length && A.equals(B), sizeA: A.length, sizeB: B.length };
};
result.compare = {
  warm1_vs_warm2: cmp(warmRuns[0].shot, warmRuns[1].shot),
  warm2_vs_cold: cmp(warmRuns[1].shot, coldShot),
  skinsWarm: warmRuns[1].fp.skins,
  skinsCold: coldFP.skins,
  resourcesWarm: warmRuns[1].fp.resources.length,
  resourcesCold: coldFP.resources.length,
  missingInWarm: coldFP.resources.filter((r) => !warmRuns[1].fp.resources.some((w) => w.split('@')[0] === r.split('@')[0])).slice(0, 20),
  missingInCold: warmRuns[1].fp.resources.filter((r) => !coldFP.resources.some((w) => w.split('@')[0] === r.split('@')[0])).slice(0, 20),
  swWarm: warmRuns[1].fp.sw,
  swCold: coldFP.sw,
};
result.warm = warmRuns.map((r) => ({ run: r.run, shot: r.shot, cacheInfo: r.cacheInfo, nodes: r.fp.nodes }));

// 线上索引哈希 vs SW 记录
const liveIndexHash = crypto.createHash('sha256').update(Buffer.from(indexHTML)).digest('hex');
result.indexHash = { live: liveIndexHash.slice(0, 32), swRecorded: warmRuns[1].cacheInfo.detail };

await warm.close();
await cold.close();
await browser.close();
fs.writeFileSync(path.join(DATA, 'cache-check.json'), JSON.stringify(result, null, 1));
console.log(JSON.stringify({
  assets: { total: result.assets.total, missingVersion: result.assets.missingVersion, mismatched: result.assets.mismatched.length, notOk: result.assets.notOk.length },
  compare: result.compare,
  indexHash: result.indexHash,
}, null, 1));
