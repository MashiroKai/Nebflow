#!/usr/bin/env node
// session-rail.spec.mjs — phase 3b session-rail e2e (plan §7.2/§7.3/§7.4/§7.5).
//
// Drives a REAL isolated gateway instance through the console-session chain:
//
//   §7.2 bootstrap   POST /api/auth/console-ticket (file-token Bearer) -> one
//                    ticket -> GET /?ticket= => 303 + the FROZEN Set-Cookie
//                    attribute set (HttpOnly / Secure / SameSite=Lax / Path=/
//                    / Max-Age=2592000=30d / host-only, criterion 4;
//                    session TTL batch 2026-10-21: was 43200=12h)
//   §7.2(4) JS face  the landed page cannot READ the session cookie
//                    (document.cookie, HttpOnly), yet /api/nf-authcheck
//                    answers 204 with x-<brand>-credential: session (the
//                    landing request already carried the cookie)
//   §7.2(5)/③       the SAME ticket a second time => readable failure page
//                    with NO Set-Cookie (one-shot, criterion 3)
//   §7.3(6)          revoked-state e2e is covered by the IN-REPO specs
//                    (ConsoleTicketRoutesSpec local-ticket exemption +
//                    PortalTunnelBridgeSpec) — this script needs a live
//                    gateway whose binding state is whatever it booted with,
//                    so it asserts only the bound-state happy path here
//   §7.4             token rotate (gateway face) => auth.json changed, old
//                    Bearer => 403, the live session still answers 204
//                    (criterion 7); file perms stay 600
//   §6.6 B'          after a session-rail landing the long-lived copies are
//                    PURGED: localStorage nebflow_token gone, non-HttpOnly
//                    nebflow_token cookie gone (criterion B' trigger = the
//                    rail header said "session")
//   §7.5(2)          sessions revoke --all => the session cookie is dead
//                    (403) — criterion 9's in-repo HTTP half
//
// Run (against an ISOLATED instance you started yourself — never the host):
//   NEBFLOW_URL=http://127.0.0.1:8098 NEBFLOW_HOME_DIR=/tmp/qa-3b-* \
//     node tests/session-rail.spec.mjs
//
// Env:
//   NEBFLOW_URL       REQUIRED gateway base URL. There is NO default on
//                     purpose: this script rotates the token and revokes
//                     sessions — pointing it at the wrong instance would be
//                     destructive.
//   NEBFLOW_HOME_DIR  REQUIRED instance home (holds auth.json; its token is
//                     read once at startup as the bootstrap credential).

import { chromium } from 'playwright';
import { readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.NEBFLOW_URL?.replace(/\/$/, '');
const HOME = process.env.NEBFLOW_HOME_DIR;

if (!BASE || !HOME) {
  console.error(
    'session-rail.spec.mjs requires NEBFLOW_URL and NEBFLOW_HOME_DIR pointing at an ISOLATED instance.\n' +
      'No defaults on purpose: this script rotates the token and revokes sessions.'
  );
  process.exit(2);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let passed = 0;
const failures = [];
function check(name, cond, detail = '') {
  if (cond) {
    passed++;
    console.log(`  ok  ${name}`);
  } else {
    failures.push(`${name}${detail ? ` — ${detail}` : ''}`);
    console.error(`FAIL  ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

// Node 18+/20 fetch: multi-value Set-Cookie only via getSetCookie().
function cookieHeader(resp, name) {
  const all = typeof resp.headers.getSetCookie === 'function' ? resp.headers.getSetCookie() : [];
  return all.find((c) => c.startsWith(`${name}=`));
}

async function main() {
  // Bootstrap credential: the instance's file token, read ONCE. Everything
  // after a rotation uses the NEW value re-read from auth.json.
  const authPath = join(HOME, 'auth.json');
  let fileToken = JSON.parse(readFileSync(authPath, 'utf8'));

  // ── §7.2(2): mint a break-glass ticket ──────────────────────────────────
  const mint = await fetch(`${BASE}/api/auth/console-ticket`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${fileToken}` },
  });
  check('console-ticket mint answers 200', mint.status === 200, `status=${mint.status}`);
  const mintBody = await mint.json();
  const ticket1 = mintBody.ticket;
  check('mint returns a one-shot ticket', typeof ticket1 === 'string' && ticket1.length >= 40);

  // ── §7.2(3): redemption response-header assertions (criterion 4) ────────
  const brand = 'nebflow'; // window.__BRAND__ default brand; cookie name derives from it
  const SESSION_COOKIE = `${brand}_session`;
  const redir = await fetch(`${BASE}/?ticket=${encodeURIComponent(ticket1)}`, { redirect: 'manual' });
  check('redemption answers 303', redir.status === 303, `status=${redir.status}`);
  check('Location: /', redir.headers.get('location') === '/', `got ${redir.headers.get('location')}`);
  const setc = cookieHeader(redir, SESSION_COOKIE) ?? '';
  check('Set-Cookie names the session cookie', setc.startsWith(`${SESSION_COOKIE}=`), setc);
  check('Set-Cookie is HttpOnly', /httponly/i.test(setc), setc);
  check('Set-Cookie is Secure', /secure/i.test(setc), setc);
  check('Set-Cookie is SameSite=Lax', /samesite=lax/i.test(setc), setc);
  check('Set-Cookie Path=/', /path=\//i.test(setc), setc);
  check('Set-Cookie Max-Age=2592000 (30d absolute ceiling)', /max-age=2592000/i.test(setc), setc);
  check('Set-Cookie is host-only (no Domain=)', !/domain=/i.test(setc), setc);

  // ── §7.2(5)/criterion 3: second use of the SAME ticket ──────────────────
  const replay = await fetch(`${BASE}/?ticket=${encodeURIComponent(ticket1)}`, { redirect: 'manual' });
  check('replayed ticket is refused (not 3xx)', replay.status >= 400 && replay.status < 500, `status=${replay.status}`);
  check('replayed ticket sets NO cookie', cookieHeader(replay, SESSION_COOKIE) === undefined);
  const replayBody = await replay.text();
  check(
    'replay lands on a readable failure page (no bare 500)',
    replay.status !== 500 && /already used|rejected/i.test(replayBody),
    replayBody.slice(0, 120)
  );

  // ── §7.2(4): browser face — ticket -> landing -> HttpOnly + rail header ──
  const mint2 = await (await fetch(`${BASE}/api/auth/console-ticket`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${fileToken}` },
  })).json();
  const ticket2 = mint2.ticket;

  const browser = await chromium.launch();
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  await page.goto(`${BASE}/?ticket=${encodeURIComponent(ticket2)}`, { waitUntil: 'load' });
  await sleep(500); // let the frontend settle (probe + first WS attempt)

  const docCookie = await page.evaluate(() => document.cookie);
  check(
    'HttpOnly: document.cookie cannot read the session cookie (criterion 4, JS face)',
    !docCookie.includes(SESSION_COOKIE),
    docCookie
  );

  const authcheck = await page.evaluate(async () => {
    const r = await fetch('/api/nf-authcheck', { credentials: 'same-origin', cache: 'no-store' });
    return {
      status: r.status,
      rail: r.headers.get(`x-${(window.__BRAND__ ?? { lowerName: 'nebflow' }).lowerName}-credential`),
      // Session TTL batch (2026-10-21): a session-rail 204 SLIDES the browser
      // cookie — same id, fresh Max-Age = the 30d absolute TTL.
      slid: (typeof r.headers.getSetCookie === 'function' ? r.headers.getSetCookie() : [])
        .find((c) => c.startsWith(`${(window.__BRAND__ ?? { lowerName: 'nebflow' }).lowerName}_session=`)) ?? '',
    };
  });
  check('the landed page authenticates to /api/nf-authcheck (204)', authcheck.status === 204, `status=${authcheck.status}`);
  check('the rail header names the session rail', authcheck.rail === 'session', `rail=${authcheck.rail}`);
  check('the authcheck 204 slides the session cookie (same sid, Max-Age=2592000)',
    /max-age=2592000/i.test(authcheck.slid) && /httponly/i.test(authcheck.slid), authcheck.slid || 'NO SET-COOKIE');

  // ── B' purge: the long-lived copies are gone after a session-rail landing ──
  const purge = await page.evaluate(() => ({
    localStorageToken: localStorage.getItem('nebflow_token'),
    legacyCookie: /(?:^|;\s*)nebflow_token=/.test(document.cookie),
  }));
  check("B-prime: the localStorage token copy is purged", purge.localStorageToken === null, JSON.stringify(purge));
  check("B-prime: the non-HttpOnly nebflow_token cookie is purged", !purge.legacyCookie, JSON.stringify(purge));

  // ── §7.4: rotate — old Bearer dies, the live session survives ───────────
  const rotate = await fetch(`${BASE}/api/auth/token/rotate`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${fileToken}` },
  });
  check('rotate via the gateway face answers 200 ok', rotate.status === 200, `status=${rotate.status}`);
  const rotateBody = await rotate.json().catch(() => ({}));
  check('rotate does NOT echo the new token', !JSON.stringify(rotateBody).includes('token":'), JSON.stringify(rotateBody));
  await sleep(300);
  const newValue = JSON.parse(readFileSync(authPath, 'utf8'));
  check('auth.json holds a NEW value after rotate', newValue !== fileToken);
  check('auth.json permissions stay 600', (statSync(authPath).mode & 0o777) === 0o600);

  const oldBearer = await fetch(`${BASE}/api/auth/sessions`, { headers: { Authorization: `Bearer ${fileToken}` } });
  check('the OLD Bearer is dead after rotate (403)', oldBearer.status === 403, `status=${oldBearer.status}`);
  fileToken = newValue; // the CLI/gateway would now hold the new value

  const sessionStillAlive = await page.evaluate(async () => {
    const r = await fetch('/api/nf-authcheck', { credentials: 'same-origin', cache: 'no-store' });
    return r.status;
  });
  check('the live SESSION survives the rotate (204, criterion 7)', sessionStillAlive === 204, `status=${sessionStillAlive}`);

  const newBearer = await fetch(`${BASE}/api/auth/sessions`, { headers: { Authorization: `Bearer ${fileToken}` } });
  check('the NEW Bearer works on the AuthAdmin face (200)', newBearer.status === 200, `status=${newBearer.status}`);

  // ── §7.5(2): revoke all => the session cookie is dead (criterion 9 half) ──
  const revoke = await fetch(`${BASE}/api/auth/sessions/revoke`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${fileToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ all: true }),
  });
  check('sessions revoke --all answers 200', revoke.status === 200, `status=${revoke.status}`);
  const revokedBody = await revoke.json().catch(() => ({}));
  check('revoke reports the dropped count', typeof revokedBody.revoked === 'number' && revokedBody.revoked >= 1, JSON.stringify(revokedBody));

  const afterRevoke = await page.evaluate(async () => {
    const r = await fetch('/api/nf-authcheck', { credentials: 'same-origin', cache: 'no-store' });
    return r.status;
  });
  check('after revoke --all the session cookie no longer authenticates (403)', afterRevoke === 403, `status=${afterRevoke}`);

  // ── AuthAdmin rail isolation (R1-1 pin, live half) ──────────────────────
  // (The session is revoked now, so mint a fresh session-less probe: a bare
  // browser context with NO credential must be refused everywhere.)
  const barePage = await (await browser.newContext({ ignoreHTTPSErrors: true })).newPage();
  await barePage.goto(`${BASE}/`, { waitUntil: 'load' });
  const bareProbe = await barePage.evaluate(async () => {
    const r = await fetch('/api/auth/sessions', { credentials: 'same-origin' });
    return r.status;
  });
  check('a credential-less context cannot reach the AuthAdmin face (403)', bareProbe === 403, `status=${bareProbe}`);

  await browser.close();

  console.log(`\nsession-rail e2e: ${passed} passed, ${failures.length} failed`);
  if (failures.length > 0) {
    for (const f of failures) console.error(`  - ${f}`);
    process.exit(1);
  }
}

main().catch((e) => {
  console.error('session-rail e2e crashed:', e);
  process.exit(1);
});
