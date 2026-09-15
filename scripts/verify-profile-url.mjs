// verify-profile-url.mjs — contract regression guard for the account profile
// link (login-chain fix, 2026-09-01; hint param added 2026-09-15).
//
// WHY this exists: the client shipped `window.open(`https://${brand.domain}/profile`)`,
// and brand.domain carries the brand.conf placeholder — so the avatar opened
// https://neblink.example/profile, a domain that does not resolve. The fix
// moved the URL to its own injected field (window.__BRAND__.profileUrl) read
// through brand.js getProfileUrl(). This script pins that contract:
//
//   1. injected https URL is used verbatim (default config AND the
//      NEBFLOW_PROFILE_URL env override passes through untouched)
//   2. absent field / absent __BRAND__ falls back to the live profile page
//   3. non-https values (javascript:, http:) are rejected — the value goes
//      into window.open(), so a hostile injection must never be navigable
//   4. the placeholder domain never appears in the result
//   5. (2026-09-15, session-handoff 案 3) the result ALWAYS carries the
//      non-credential landing hint `from=client`, and exactly that spelling —
//      it is a cross-repo contract with nebflow.space. Re-baselined by the
//      author's "A 案3 全做" ruling; the guard is stricter than before (it now
//      asserts the parameter, previously only the URL), never weaker.
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

const FALLBACK = 'https://nebflow.space/profile?from=client';
const PLACEHOLDER = 'neblink.example';
const BASE = { productName: 'Nebflow', lowerName: 'nebflow', domain: PLACEHOLDER };

const cases = [
  ['gateway injects real URL (default config)', { ...BASE, profileUrl: 'https://nebflow.space/profile' }, FALLBACK],
  ['env override (nebflow.space)', { ...BASE, domain: 'nebflow.space', profileUrl: 'https://nebflow.space/profile' }, FALLBACK],
  ['older gateway: field absent', { ...BASE }, FALLBACK],
  ['no __BRAND__ at all (static serve)', null, FALLBACK],
  ['hostile: javascript: scheme', { ...BASE, profileUrl: 'javascript:alert(1)' }, FALLBACK],
  ['hostile: http (not https)', { ...BASE, profileUrl: 'http://evil.example/profile' }, FALLBACK],
  ['hostile: empty string', { ...BASE, profileUrl: '' }, FALLBACK],
  ['configured URL that already carries the hint (idempotent)',
    { ...BASE, profileUrl: 'https://nebflow.space/profile?from=client' }, FALLBACK],
];

let failed = 0;
for (let i = 0; i < cases.length; i++) {
  const [name, injected, expected] = cases[i];
  // brand.js reads window.__BRAND__ once at module scope; a unique query
  // string forces a fresh module evaluation per case.
  globalThis.window = injected ? { __BRAND__: injected } : {};
  const mod = await import(`${BRAND_JS}?case=${i}`);
  const got = mod.getProfileUrl();
  const hint = new URL(got).searchParams.get('from');
  const params = [...new URL(got).searchParams.keys()].sort().join(',');
  const ok = got === expected && !got.includes(PLACEHOLDER) && hint === 'client' && params === 'from';
  if (!ok) failed++;
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}\n        got=${got}${ok ? '' : `  expected=${expected}`}`);
}

if (failed > 0) {
  console.error(`verify-profile-url: ${failed}/${cases.length} FAILED`);
  process.exit(1);
}
console.log(`verify-profile-url OK: ${cases.length}/${cases.length} cases, no placeholder leak.`);
