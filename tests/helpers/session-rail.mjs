// session-rail.mjs — shared session establishment for the browser e2e faces.
//
// Stage D (phase 4) retired the URL token rail, so a browser page can no
// longer pick a credential out of the URL. The one browser-reachable
// establishment path is the console-ticket chain (the same one
// tests/session-rail.spec.mjs pins):
//   POST /api/auth/console-ticket   Authorization: Bearer <file token>
//     -> { ticket }  (one-time, ~40 chars)
//   GET  /?ticket=<ticket>          -> 303 + HttpOnly session cookie
// After the landing the page authenticates through the session cookie —
// same-origin fetches and the /ws handshake ride it automatically.

export async function establishSession(base, token, page, { waitUntil = 'load' } = {}) {
  const root = base.replace(/\/$/, '');
  const mint = await fetch(`${root}/api/auth/console-ticket`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!mint.ok) throw new Error(`console-ticket mint failed: HTTP ${mint.status}`);
  const body = await mint.json();
  const ticket = body?.ticket;
  if (typeof ticket !== 'string' || ticket.length < 20) {
    throw new Error(`console-ticket mint returned no usable ticket: ${JSON.stringify(body).slice(0, 120)}`);
  }
  await page.goto(`${root}/?ticket=${encodeURIComponent(ticket)}`, { waitUntil });
}
