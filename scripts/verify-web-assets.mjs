#!/usr/bin/env node
// verify-web-assets.mjs — static asset contract test (C1).
//
// Walks EVERY file under the published web tree, maps it to the URL the
// production http4s router (src/main/scala/nebflow/gateway/WebSocketRoutes.scala)
// serves it at, and asserts HTTP 200 against a running instance.
//
// Traversal-based, not a whitelist: files added later are covered
// automatically. A file that exists on disk but maps to no route is a HARD
// failure — the fix is explicit (add a route, or add an exemption below with
// a reason), never silent.
//
// Two tree modes (P1): the gate guards whatever is PUBLISHED.
//   source mode (default): traverse src/main/resources/web/ — the dev tree.
//   dist mode (--root):    traverse a built tree (build/web-dist/) — the prod
//                          tree. URL mapping differs: assets/** is served by
//                          the /assets/** wildcard route (Backend P1 piece).
//
// Usage:
//   node scripts/verify-web-assets.mjs [baseUrl]            # source tree
//   node scripts/verify-web-assets.mjs --root <dir> [baseUrl]  # built tree
//   baseUrl defaults to $BASE_URL, then http://localhost:8080.
// Exit code 0 = all assets served; 1 = any unreachable file or non-200.

import { readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SOURCE_ROOT = fileURLToPath(new URL('../src/main/resources/web/', import.meta.url));

// Parse args: positional = baseUrl, or --root <dir> [baseUrl].
const args = process.argv.slice(2);
let rootDir = SOURCE_ROOT;
let baseArg = null;
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--root') { rootDir = resolve(args[++i]); }
  else baseArg = args[i];
}
const DIST_MODE = rootDir !== SOURCE_ROOT;
const BASE = (baseArg ?? process.env.BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '');

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
 * Map a tree-relative path to its served URL, or null when no route covers it.
 * Derived from WebSocketRoutes.scala static route table:
 *   index.html              → /
 *   <root whitelist>        → /<name>
 *   css/<f>                 → /css/<f>            (single segment)
 *   js/<deep…>              → /js/<deep…>         (any depth, jsRoutes wildcard)
 *   vendor/<f>              → /vendor/<f>         (single segment)
 *   vendor/fonts/<f>        → /vendor/fonts/<f>   (single segment)
 *   vendor/monaco/<deep…>   → /vendor/monaco/<deep…> (multi-segment, manual parse)
 *   assets/<deep…>          → /assets/<deep…>     (dist mode only, P1 route)
 */
function toUrl(rel) {
  if (rel === 'index.html') return '/';
  const segs = rel.split('/');
  if (segs.length === 1) return ROOT_WHITELIST.has(rel) ? `/${rel}` : null;
  const [top, second] = segs;
  if (top === 'assets' && DIST_MODE) return `/assets/${segs.slice(1).join('/')}`;
  if (top === 'css') return segs.length === 2 ? `/css/${second}` : null;
  if (top === 'js') return `/js/${segs.slice(1).join('/')}`;
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

const files = [...walk(rootDir)]
  .map(p => relative(rootDir, p).replaceAll('\\', '/'))
  .sort();

console.log(`C1 asset contract (${DIST_MODE ? 'dist tree' : 'source tree'}): ${files.length} files under ${relative(process.cwd(), rootDir) || '.'}, verifying against ${BASE}`);

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
        failures.push(`UNREACHABLE: ${rel} — exists in the ${DIST_MODE ? 'dist' : 'web/'} tree but no static route serves it ` +
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
