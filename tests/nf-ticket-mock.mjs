// nf-ticket-mock.mjs — shared /api/nf-ticket + /api/nf-file playground for the
// Playwright harnesses that render local-file material.
//
// Why every one of them needs it (2026-09-11, C batch): GET /api/nf-file is
// ticket-only now (R5). A harness that mocks `/api/**` with a catch-all
// `{}` — or that only mocks /api/nf-file — silently swallows the POST
// /api/nf-ticket that viewers/cardRegistry now make, the mint resolves "no
// tickets", the URL goes out credential-free, and the endpoint answers 401.
// Installing the ticket mock AFTER the catch-all (Playwright matches the most
// recently registered route first) restores a deterministic fake credential.
//
// `ticketGuard` is the second half: the nf-file mock should refuse a request
// that carries no ticket, so the new mechanism is itself under test — a
// regression that drops the injection turns into a loud 401 instead of a
// silently-passing render.

/** Deterministic fake tickets, one counter per install so values never collide. */
let seq = 0;

/**
 * Register a deterministic `POST /api/nf-ticket` route.
 * MUST be called AFTER any `**\/api\/**` catch-all.
 *
 * @param {import('playwright').Page} page
 * @param {{ drop?: string[], status?: number, json?: unknown, headers?: Record<string,string>, body?: string }} [opts]
 *        drop   — paths to leave out of `tickets` (partial-success scenario)
 *        status — answer with this HTTP status instead (mint-failure scenario)
 *        json   — replace the response body entirely ({} catch-all scenario)
 * @returns {{ calls: string[][], tokens: string[], count: () => number, last: () => string[] }}
 */
export async function installTicketMock(page, opts = {}) {
  const calls = [];
  const tokens = [];
  await page.route('**/api/nf-ticket*', async (route) => {
    let paths = [];
    try { paths = JSON.parse(route.request().postData() || '{}').paths || []; } catch { paths = []; }
    paths = Array.isArray(paths) ? paths : [];
    calls.push(paths);
    if (opts.status) {
      return route.fulfill({ status: opts.status, contentType: 'text/plain', body: 'mock mint failure' });
    }
    if (opts.json !== undefined) {
      return route.fulfill({ json: opts.json });
    }
    const tickets = {};
    for (const p of paths) {
      if (opts.drop && opts.drop.includes(p)) continue;
      const t = 'ticket-' + (++seq);
      tokens.push(t);
      tickets[p] = { t, exp: Date.now() + 60 * 60 * 1000, uses: -1 };
    }
    return route.fulfill({ json: { tickets, rejected: [] } });
  });
  return {
    calls,
    tokens,
    count: () => calls.length,
    last: () => (calls.length ? calls[calls.length - 1] : []),
  };
}

/**
 * Inside an /api/nf-file route handler: refuse a ticket-less request exactly
 * like the real endpoint does, so a broken injection fails loudly.
 * @param {import('playwright').Route} route
 * @returns {Promise<boolean>} true when the handler already answered
 */
export async function ticketGuard(route) {
  const url = new URL(route.request().url());
  if (!url.searchParams.has('ticket')) {
    await route.fulfill({ status: 401, contentType: 'text/plain', body: 'Missing ticket' });
    return true;
  }
  return false;
}
