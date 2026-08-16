#!/usr/bin/env node
// verify-web-assets.mjs — static asset contract test (C1).
//
// Walks EVERY file under src/main/resources/web, maps it to the URL the
// production http4s router (src/main/scala/nebflow/gateway/WebSocketRoutes.scala)
// serves it at, and asserts HTTP 200 against a running instance.
//
// Traversal-based, not a whitelist: files added later are covered
// automatically. A file that exists on disk but maps to no route is a HARD
// failure — the fix is explicit (add a route, or add an exemption below with
// a reason), never silent.
//
// TODO(W1-a): Backend is generalizing the static routes to js/** wildcards
// (/tmp/nb-jsroute-general). Once merged, the per-subdirectory js/ mapping
// below collapses to a single recursive rule — simplify then.
//
// Usage:
//   node scripts/verify-web-assets.mjs [baseUrl]
//   baseUrl defaults to $BASE_URL, then http://localhost:8080.
// Exit code 0 = all assets served; 1 = any unreachable file or non-200.

import { readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB_ROOT = fileURLToPath(new URL('../src/main/resources/web/', import.meta.url));
const BASE = (process.argv[2] ?? process.env.BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '');

// Root-level files the router serves at /<name>
// (WebSocketRoutes.scala `Root / fileName` whitelist).
const ROOT_WHITELIST = new Set([
  'style.css', 'app.js', 'favicon.svg', 'logo.svg',
  'favicon-32.png', 'favicon-16.png', 'favicon.ico',
]);

// Explicit exemptions: files that intentionally have no served route.
// Every entry MUST carry a justification. Empty by default — think twice
// before adding one; an unreachable file in web/ is usually a bug.
const EXEMPTIONS = new Map([
  // ['js/some-build-only.js', 'reason'],
]);

/**
 * Map a web/-relative path to its served URL, or null when no route covers it.
 * Derived from WebSocketRoutes.scala static route table at the time of writing:
 *   index.html              → /
 *   <root whitelist>        → /<name>
 *   css/<f>                 → /css/<f>            (single segment)
 *   js/<f>                  → /js/<f>             (single segment)
 *   js/locales/<f>          → /js/locales/<f>     (single segment)
 *   js/viewers/<f>          → /js/viewers/<f>     (single segment)
 *   vendor/<f>              → /vendor/<f>         (single segment)
 *   vendor/fonts/<f>        → /vendor/fonts/<f>   (single segment)
 *   vendor/monaco/<deep…>   → /vendor/monaco/<deep…> (multi-segment, manual parse)
 */
function toUrl(rel) {
  if (rel === 'index.html') return '/';
  const segs = rel.split('/');
  if (segs.length === 1) return ROOT_WHITELIST.has(rel) ? `/${rel}` : null;
  const [top, second] = segs;
  if (top === 'css') return segs.length === 2 ? `/css/${second}` : null;
  if (top === 'js') {
    if (segs.length === 2) return `/js/${second}`;
    if (segs.length === 3 && (second === 'locales' || second === 'viewers')) {
      return `/js/${second}/${segs[2]}`;
    }
    return null;
  }
  if (top === 'vendor') {
    if (segs.length === 2) return `/vendor/${second}`;
    if (second === 'fonts') return segs.length === 3 ? `/vendor/fonts/${segs[2]}` : null;
    if (second === 'monaco') return segs.length >= 3 ? `/vendor/monaco/${segs.slice(2).join('/')}` : null;
    return null;
  }
  return null;
}

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) yield* walk(p);
    else yield p;
  }
}

const files = [...walk(WEB_ROOT)]
  .map(p => relative(WEB_ROOT, p).replaceAll('\\', '/'))
  .sort();

console.log(`C1 asset contract: ${files.length} files under web/, verifying against ${BASE}`);

const failures = [];
let checked = 0;
let skipped = 0;

// Modest concurrency — localhost, hundreds of tiny GETs.
const CONCURRENCY = 10;
const queue = [...files];
await Promise.all(Array.from({ length: CONCURRENCY }, async () => {
  while (queue.length) {
    const rel = queue.shift();
    const url = toUrl(rel);
    if (!url) {
      if (EXEMPTIONS.has(rel)) {
        skipped++;
        console.log(`  SKIP (exempt): ${rel} — ${EXEMPTIONS.get(rel)}`);
      } else {
        failures.push(`UNREACHABLE: ${rel} — exists in web/ but no static route serves it ` +
          `(add a route in WebSocketRoutes.scala or an explicit exemption in scripts/verify-web-assets.mjs)`);
      }
      continue;
    }
    const full = BASE + url.split('/').map(encodeURIComponent).join('/');
    let status = 0;
    try {
      const resp = await fetch(full, { redirect: 'manual' });
      status = resp.status;
      // Drain so the socket is reusable.
      await resp.arrayBuffer().catch(() => {});
    } catch (e) {
      failures.push(`ERROR: ${rel} → GET ${url} threw ${e.message}`);
      continue;
    }
    checked++;
    if (status !== 200) {
      failures.push(`HTTP ${status}: ${rel} → GET ${url} (file exists, route matched, but not served)`);
    }
  }
}));

console.log(`checked=${checked} skipped=${skipped} failures=${failures.length}`);
if (failures.length) {
  console.error('\nC1 ASSET CONTRACT FAILURES:');
  for (const f of failures) console.error(`  ${f}`);
  process.exit(1);
}
console.log('C1 PASS: every web asset is served with 200.');
