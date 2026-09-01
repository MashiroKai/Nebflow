// verify-profile-url.mjs — contract regression guard for the account profile
// link (login-chain fix, 2026-09-01).
//
// WHY this exists: the client shipped `window.open(`https://${brand.domain}/profile`)`,
// and brand.domain carries the brand.conf placeholder — so the avatar opened
// https://neblink.example/profile, a domain that does not resolve. The fix
// moved the URL to its own injected field (window.__BRAND__.profileUrl) read
// through brand.js getProfileUrl(). This script pins that contract:
//
//   1. injected https URL is used verbatim (debug domain AND publish domain,
//      so the NEBFLOW_PROFILE_URL env override passes through untouched)
//   2. absent field / absent __BRAND__ falls back to the live profile page
//   3. non-https values (javascript:, http:) are rejected — the value goes
//      into window.open(), so a hostile injection must never be navigable
//   4. the placeholder domain never appears in the result
//
// Runs headless in plain node: brand.js only touches window.__BRAND__ at
// module scope, so a bare `globalThis.window` shim is enough — no browser,
// no gateway, no network.
//
// Usage: node scripts/verify-profile-url.mjs   (exit 0 = pass, 1 = fail)

import { pathToFileURL } from 'node:url';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const BRAND_JS = pathToFileURL(join(ROOT, 'src', 'main', 'resources', 'web', 'js', 'brand.js')).href;

const FALLBACK = 'https://neblink.space/profile';
const PLACEHOLDER = 'neblink.example';
const BASE = { productName: 'Nebflow', lowerName: 'nebflow', domain: PLACEHOLDER };

const cases = [
  ['gateway injects real URL (debug domain)', { ...BASE, profileUrl: 'https://neblink.space/profile' }, 'https://neblink.space/profile'],
  ['publish env override (nebflow.space)', { ...BASE, domain: 'nebflow.space', profileUrl: 'https://nebflow.space/profile' }, 'https://nebflow.space/profile'],
  ['older gateway: field absent', { ...BASE }, FALLBACK],
  ['no __BRAND__ at all (static serve)', null, FALLBACK],
  ['hostile: javascript: scheme', { ...BASE, profileUrl: 'javascript:alert(1)' }, FALLBACK],
  ['hostile: http (not https)', { ...BASE, profileUrl: 'http://evil.example/profile' }, FALLBACK],
  ['hostile: empty string', { ...BASE, profileUrl: '' }, FALLBACK],
];

let failed = 0;
for (let i = 0; i < cases.length; i++) {
  const [name, injected, expected] = cases[i];
  // brand.js reads window.__BRAND__ once at module scope; a unique query
  // string forces a fresh module evaluation per case.
  globalThis.window = injected ? { __BRAND__: injected } : {};
  const mod = await import(`${BRAND_JS}?case=${i}`);
  const got = mod.getProfileUrl();
  const ok = got === expected && !got.includes(PLACEHOLDER);
  if (!ok) failed++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}\n        got=${got}${ok ? '' : `  expected=${expected}`}`);
}

if (failed > 0) {
  console.error(`verify-profile-url: ${failed}/${cases.length} FAILED`);
  process.exit(1);
}
console.log(`verify-profile-url OK: ${cases.length}/${cases.length} cases, no placeholder leak.`);
