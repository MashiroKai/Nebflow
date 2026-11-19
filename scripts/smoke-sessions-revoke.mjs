#!/usr/bin/env node
// smoke-sessions-revoke.mjs — E2E smoke for the console-session revocation
// chain (phase 4 criterion 5): mint a break-glass ticket through the AuthAdmin
// face, redeem it for a session cookie, prove the session works, revoke it,
// and prove the old cookie dies while the file-token Bearer rail is unaffected.
//
// Run (against an isolated instance, never the user's live home):
//   NEBFLOW_URL=http://localhost:8096 NEBFLOW_HOME_DIR=/tmp/nb-sr-home \
//     node scripts/smoke-sessions-revoke.mjs
//
// Env:
//   NEBFLOW_URL       gateway base URL (default http://localhost:8096)
//   NEBFLOW_HOME_DIR  instance home dir holding auth.json (required)
//   NEBFLOW_TOKEN     explicit token (default: read $NEBFLOW_HOME_DIR/auth.json)
//   NEBFLOW_JAR       assembly jar path — when set, the revoke leg runs through
//                     the real CLI (`sessions revoke --all`) instead of the HTTP
//                     AuthAdmin face the CLI wraps.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';

const URL_BASE = process.env.NEBFLOW_URL || 'http://localhost:8096';
const HOME = process.env.NEBFLOW_HOME_DIR;
if (!HOME) {
  console.error('FAIL  NEBFLOW_HOME_DIR is required (isolated instance home, never ~/.nebflow)');
  process.exit(1);
}
const TOKEN =
  process.env.NEBFLOW_TOKEN || JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
const JAR = process.env.NEBFLOW_JAR || '';
const JAVA = process.env.JAVA_HOME
  ? `${process.env.JAVA_HOME}/bin/java`
  : 'java';

// The CLI resolves its gateway as --port > GATEWAY_PORT > 8080 — an unpinned
// port would aim the revoke at the user's live :8080 host. Derive the port
// from NEBFLOW_URL and hard-refuse the never-touch ports.
const TARGET_PORT = new URL(URL_BASE).port;
if (!TARGET_PORT || TARGET_PORT === '8080' || TARGET_PORT === '8097') {
  console.error(`FAIL  NEBFLOW_URL must point at the isolated instance's port (got "${TARGET_PORT}")`);
  process.exit(1);
}

let failures = 0;
function check(label, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ' — ' + detail : ''}`);
  if (!ok) failures++;
}

// Phase 0: the instance under test is the one in NEBFLOW_HOME_DIR (never touch
// a foreign home — same discipline as smoke-scheduled-task.mjs).
const authRaw = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
if (typeof authRaw !== 'string' || authRaw.length < 20) {
  console.error('FAIL  auth.json is not a bare token string — wrong home?');
  process.exit(1);
}

// Phase 1: mint a break-glass console ticket (BearerFileOnly face).
const mint = await fetch(`${URL_BASE}/api/auth/console-ticket`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
  body: '{}',
});
check('mint console ticket', mint.status === 200, `status ${mint.status}`);
const mintBody = await mint.json().catch(() => ({}));
const ticket = typeof mintBody.ticket === 'string' ? mintBody.ticket : '';
check('ticket present', ticket.length > 0);

// Phase 2: redeem it (303 + HttpOnly session cookie).
const redeem = await fetch(`${URL_BASE}/?ticket=${encodeURIComponent(ticket)}`, {
  redirect: 'manual',
});
const setCookie = redeem.headers.get('set-cookie') || '';
check(
  'redeem 303 + HttpOnly session cookie',
  redeem.status === 303 && /nebflow_session=[^;]+/.test(setCookie) && /HttpOnly/i.test(setCookie),
  `status ${redeem.status}`,
);
const sessionPair = (setCookie.match(/^(nebflow_session=[^;]+)/) || [])[1] || '';

// Phase 3: the session works.
const before = await fetch(`${URL_BASE}/api/sessions`, {
  headers: { Cookie: sessionPair },
});
check('session cookie authenticates pre-revoke', before.status === 200, `status ${before.status}`);

// Phase 4: revoke. Prefer the real CLI when the jar is given; else the HTTP
// face the CLI wraps (TokenCommand.scala posts here verbatim).
if (JAR) {
  const out = execFileSync(
    JAVA,
    [
      '--add-opens=java.base/java.lang=ALL-UNNAMED',
      '-cp', JAR,
      'nebflow.Main',
      '--home', HOME,
      '--port', TARGET_PORT,
      'sessions', 'revoke', '--all',
    ],
    { encoding: 'utf8', timeout: 60000 },
  );
  check('CLI sessions revoke --all', /Revoked [1-9]\d* console session/.test(out), out.trim().split('\n').pop());
} else {
  const rev = await fetch(`${URL_BASE}/api/auth/sessions/revoke`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
    body: '{"all":true}',
  });
  const revBody = await rev.json().catch(() => ({}));
  check(
    'HTTP sessions/revoke all',
    rev.status === 200 && typeof revBody.revoked === 'number' && revBody.revoked >= 1,
    `status ${rev.status} body ${JSON.stringify(revBody)}`,
  );
}

// Phase 5: the revoked cookie dies on its next request; the file-token Bearer
// rail is unaffected (revocation scope = console sessions only).
const after = await fetch(`${URL_BASE}/api/sessions`, { headers: { Cookie: sessionPair } });
check('revoked session cookie => 403', after.status === 403, `status ${after.status}`);
const bearer = await fetch(`${URL_BASE}/api/sessions`, {
  headers: { Authorization: `Bearer ${TOKEN}` },
});
check('file Bearer rail unaffected', bearer.status === 200, `status ${bearer.status}`);

console.log(failures === 0 ? 'SMOKE OK' : `SMOKE FAILED (${failures})`);
process.exit(failures === 0 ? 0 : 1);
