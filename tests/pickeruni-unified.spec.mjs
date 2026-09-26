// pickeruni-unified.spec.mjs — the unified directory picker (pickeruni batch).
//
// workspacePicker.openPicker is now the ONE in-app directory-picking face:
// explorer root button + sidebar folder "set project root" were rewired onto it
// and the old static-DOM path picker was retired (DOM + CSS + locale keys).
//
// Coverage (binary assertions, product's own faces only — zero product-logic mocks):
//   S1  zero-residue probe: no `path-picker*` DOM left anywhere
//   S2  open: single `wsBrowse.list '~'` outbound (plain face unchanged for
//       existing callers), goto/search rows present, note+error hidden
//   S3  goto success: browsePath frame + crumbs/rows land on the target; Select
//       commits the server-resolved target
//   S4  goto error NO-COMMIT (picker-trunc r3 invariant, preserved): inline
//       error line, crumbs untouched, list NOT wiped while in flight, Select
//       falls back to the directory the user was actually in (callback mode)
//   S5  server-side search: debounced browsePath carries the query; echo guard
//       drops stale frames; empty state names its way out
//   S6  truncation note (A face): fact line (hidden/total) + hint line
//   S7  Clear (project-root mode): rendered only with showClear, closes with
//       onClear and WITHOUT onCancel
//   S8/S9 cancel button + Esc → onCancel
//   S10 new-folder regression on the unified face (mkdir → auto-enter, plain channel)
//   S11 explorer root button regression: real button → unified picker opens
//       with the relocated project title → pick persists the explorer root
//   S12 sidebar folder-root regression: real context menu → unified picker with
//       Clear visible → Clear commits `projectRoot:null` + change event
//   S13 nav-origin listing failure raises onListUnavailable (chat fallback face)
//   S14 staleness: a response answering a superseded request is dropped
//
// Harness (same family as the retired path-picker-error spec, weaker intrusion
// than a state.ws replacement): real index.html + real modules; outbound frames
// recorded via a WebSocket.prototype.send stub with readyState pinned OPEN;
// inbound frames driven through the REAL ws.js dispatch entry (state.ws.onmessage),
// so GLOBAL routing + onMessage subscription chains are the production ones.
//
// Run (self-contained static server, no gateway needed):
//   node tests/pickeruni-unified.spec.mjs
//   PORT=18998 node tests/pickeruni-unified.spec.mjs
//
// Exit codes: 0 = all green; 1 = assertion failure; 3 = environment/fatal.

import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..');
const WEB = join(REPO, 'src', 'main', 'resources', 'web');
const PORT = Number(process.env.PORT || 18997);
const HOME = '/Users/e2e'; // the home the injected frames pretend
const GOOD = '/tmp/nb-pv3-good'; // a directory that "exists" (frame-level fixture)
const BAD = '/nonexistent/nb-pv3-xyz'; // a typed path that must never commit

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
};

const results = [];
function check(name, ok, extra = '') {
  results.push({ name, pass: !!ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  return !!ok;
}

/** Tiny poll helper (bare-playwright equivalent of expect.poll). */
async function poll(fn, timeout = 4000, everyMs = 80) {
  const t0 = Date.now();
  for (;;) {
    let v;
    try { v = await fn(); } catch { v = undefined; }
    if (v) return v;
    if (Date.now() - t0 > timeout) throw new Error('poll timeout');
    await new Promise((r) => setTimeout(r, everyMs));
  }
}

/** Static file server over the web root (gateway jsRoutes serves this same tree). */
function startStatic() {
  const server = createServer((req, res) => {
    const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
    const rel = normalize(decodeURIComponent(url.pathname)).replace(/^(\.\.[/\\])+/, '');
    const file = join(WEB, rel === '/' ? 'index.html' : rel);
    if (!file.startsWith(WEB) || !existsSync(file)) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('not found');
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(readFileSync(file));
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(PORT, '127.0.0.1', () => resolve(server));
  });
}

const { chromium } = await import('playwright');

const server = await startStatic();
const browser = await chromium.launch();
let code = 0;
const pageErrors = [];
try {
  const BASE = `http://127.0.0.1:${PORT}`;
  const page = await browser.newPage();
  page.on('pageerror', (e) => pageErrors.push(String(e && e.stack ? e.stack : e)));
  await page.goto(`${BASE}/index.html`);
  await page.waitForTimeout(1500);

  // Defensive onboarding dismissal (async overlay intercepts pointers if present)
  const overlay = page.locator('.onboarding-overlay');
  if (await overlay.isVisible().catch(() => false)) {
    const dismiss = overlay.locator('#ob-skip, #ob-no');
    if (await dismiss.count()) {
      await dismiss.first().click();
      await overlay.waitFor({ state: 'detached', timeout: 3000 }).catch(() => {});
    }
  }

  // ── 0 · wire the page: record outbound, pin socket OPEN, capture real entry ──
  await page.evaluate(() => {
    const proto = WebSocket.prototype;
    window.__sent = [];
    proto.send = function (d) {
      try {
        const m = typeof d === 'string' ? JSON.parse(d) : d;
        window.__sent.push(m);
      } catch { window.__sent.push(String(d)); }
      return true;
    };
    Object.defineProperty(proto, 'readyState', { configurable: true, get: () => 1 }); // OPEN
  });
  const wired = await page.evaluate(async () => {
    const state = (await import('/js/state.js')).default;
    window.__entry = state.ws && typeof state.ws.onmessage === 'function' ? state.ws.onmessage : null;
    return { hasEntry: !!window.__entry };
  });
  check('A1 real ws dispatch entry captured (state.ws.onmessage)', wired.hasEntry);

  const inject = (msg) => page.evaluate((m) => window.__entry({ data: JSON.stringify(m) }), msg);
  const sentFrames = () => page.evaluate(() => window.__sent);
  const resetSent = () => page.evaluate(() => { window.__sent.length = 0; });
  /** Answer the plain navigation channel (wsBrowseList — complete listing). */
  const answerList = (path, names, opts = {}) => inject({
    type: 'wsBrowseList', path, home: opts.home ?? HOME, entries: names,
    ...(opts.error ? { error: opts.error } : {}),
  });
  /** Answer the filter/goto channel (browseResult — objects + total/truncated + echoed query). */
  const answerFilter = (path, entries, opts = {}) => inject({
    type: 'browseResult', path,
    entries, total: opts.total ?? entries.length, truncated: !!opts.truncated,
    query: opts.query ?? '', ...(opts.errorKind ? { errorKind: opts.errorKind } : {}),
    ...(opts.error ? { error: opts.error } : {}),
  });

  // ════════ S1 · zero-residue DOM probe ════════
  const residue = await page.evaluate(() => ({
    ids: document.querySelectorAll('[id^="path-picker"]').length,
    classes: document.querySelectorAll('[class*="path-picker"], [class*="pp-item"], [class*="pp-icon"], [class*="pp-name"], [class*="pp-hint"]').length,
  }));
  check('S1 zero residue: no path-picker DOM of any shape', residue.ids === 0 && residue.classes === 0,
    JSON.stringify(residue));

  // ════════ helpers to drive the unified picker through the real module ════════
  /** Open with per-call callback sinks; returns nothing (sinks live on window). */
  const openPicker = (opts = {}) => page.evaluate((o) => {
    const wk = window.__wkPromise = import('/js/workspacePicker.js');
    return wk.then(({ openPicker: open }) => {
      window.__picked = 'NOT-CALLED';
      window.__cleared = 'NOT-CALLED';
      window.__cancelled = 'NOT-CALLED';
      window.__unavailable = 'NOT-CALLED';
      open({
        startPath: o.startPath ?? '~',
        title: o.title,
        showClear: !!o.showClear,
        onPick: (p) => { window.__picked = p; },
        onClear: () => { window.__cleared = 'CLEARED'; },
        onCancel: () => { window.__cancelled = 'CANCELLED'; },
        onListUnavailable: (e) => { window.__unavailable = e; },
      });
    });
  }, opts);

  // ════════ S2 · open: plain face unchanged ════════
  await openPicker({ startPath: '~' });
  await expectWsp(page);
  await poll(async () => (await sentFrames()).some(f => f.type === 'wsBrowse.list' && f.path === '~'));
  {
    const frames = await sentFrames();
    const listingFrames = frames.filter(f => f.type === 'wsBrowse.list' || f.type === 'browsePath');
    check('S2 first paint sends exactly ONE frame: wsBrowse.list ~ (plain face unchanged)',
      listingFrames.length === 1 && listingFrames[0].type === 'wsBrowse.list', JSON.stringify(listingFrames));
  }
  await answerList(HOME, ['fixture-root']);
  await page.waitForTimeout(150);
  {
    const state = await page.evaluate(() => ({
      goto: !!document.querySelector('.wsp-goto-input'),
      search: !!document.querySelector('.wsp-search-input'),
      noteHidden: document.querySelector('.wsp-note').hidden,
      errHidden: document.querySelector('.wsp-err').hidden,
      crumbs: document.querySelector('.wsp-crumbs').textContent,
      rows: document.querySelectorAll('.wsp-row.wsp-dir').length,
    }));
    check('S2 goto+search rows present, note+error hidden, crumbs folded to home',
      state.goto && state.search && state.noteHidden && state.errHidden
        && /主目录|Home/.test(state.crumbs) && state.rows === 2, JSON.stringify(state));
  }

  // ════════ S3 · goto success ════════
  await resetSent();
  await page.fill('.wsp-goto-input', GOOD);
  await page.locator('.wsp-goto-input').press('Enter');
  await poll(async () => (await sentFrames()).some(f => f.type === 'browsePath' && f.path === GOOD && f.query === ''));
  await answerFilter(GOOD, [{ name: 'b1', path: `${GOOD}/b1` }], { query: '' });
  await page.waitForTimeout(150);
  await page.locator('.wsp-pick').click();
  await page.waitForTimeout(200);
  {
    const picked = await page.evaluate(() => window.__picked);
    const cancelled = await page.evaluate(() => window.__cancelled);
    check('S3 goto success: crumbs land on target, Select commits the resolved path',
      picked === GOOD && cancelled === 'NOT-CALLED', `picked=${JSON.stringify(picked)}`);
  }

  // ════════ S4 · goto error NO-COMMIT (both commit pipes) ════════
  await openPicker({ startPath: GOOD });
  await expectWsp(page);
  await answerList(GOOD, ['b1']);
  await page.waitForTimeout(150);
  await resetSent();
  await page.fill('.wsp-goto-input', BAD);
  await page.locator('.wsp-goto-input').press('Enter');
  await poll(async () => (await sentFrames()).some(f => f.type === 'browsePath' && f.path === BAD));
  {
    // goto keeps the previous listing on screen while in flight (no loading wipe)
    const loading = await page.evaluate(() => document.querySelectorAll('.wsp-empty').length);
    check('S4b goto in flight keeps the previous listing (no loading wipe)', loading === 0, `loadingRows=${loading}`);
  }
  await inject({
    type: 'browseResult', path: BAD, entries: [], total: 0, truncated: false, query: '',
    errorKind: 'invalid-path', error: `No such directory: ${BAD}`,
  });
  await page.waitForTimeout(150);
  {
    const st = await page.evaluate(() => ({
      errVisible: !document.querySelector('.wsp-err').hidden,
      errText: document.querySelector('.wsp-err').textContent,
      crumbs: document.querySelector('.wsp-crumbs').textContent,
      unavail: window.__unavailable,
    }));
    check('S4c error line inline with server text; crumbs stay; goto-origin does NOT raise onListUnavailable',
      st.errVisible && st.errText.includes(BAD) && st.crumbs.includes('nb-pv3-good') && st.unavail === 'NOT-CALLED',
      JSON.stringify(st));
  }
  await page.locator('.wsp-pick').click();
  await page.waitForTimeout(200);
  {
    const picked = await page.evaluate(() => window.__picked);
    check('S4d error-state Select does NOT hand out the failed path (falls back to GOOD)',
      picked === GOOD, `picked=${JSON.stringify(picked)}`);
  }

  // ════════ S5 · server-side search + echo guard + empty naming ════════
  await openPicker({ startPath: '~' });
  await expectWsp(page);
  await answerList(HOME, ['alpha', 'beta']);
  await page.waitForTimeout(150);
  await resetSent();
  await page.fill('.wsp-search-input', 'fix');
  await poll(async () => (await sentFrames()).some(f => f.type === 'browsePath' && f.query === 'fix'), 3000);
  await answerFilter(HOME, [{ name: 'fixture-root', path: `${HOME}/fixture-root` }], { query: 'fix', total: 1 });
  await page.waitForTimeout(120);
  // stale frame (answers an older filter string) must be dropped
  await answerFilter(HOME, [{ name: 'WRONG', path: `${HOME}/WRONG` }], { query: 'stale-query' });
  await page.waitForTimeout(120);
  {
    const st = await page.evaluate(() => ({
      names: [...document.querySelectorAll('.wsp-row-name')].map(n => n.textContent),
    }));
    check('S5 search renders server-filtered rows; stale-echo frame dropped',
      st.names.includes('fixture-root') && !st.names.includes('WRONG') && !st.names.includes('alpha'),
      JSON.stringify(st));
  }
  // empty filtered state names its own way out
  await page.fill('.wsp-search-input', 'zzz-none');
  await poll(async () => (await sentFrames()).filter(f => f.type === 'browsePath' && f.query === 'zzz-none').length === 1, 3000);
  await answerFilter(HOME, [], { query: 'zzz-none', total: 0 });
  await page.waitForTimeout(150);
  {
    const st = await page.evaluate(() => ({
      empty: document.querySelector('.wsp-empty')?.textContent ?? '',
    }));
    check('S5b empty filtered state names the noMatch fact + the way out',
      st.empty.includes('zzz-none') && st.empty.length > 0, JSON.stringify(st));
  }

  // ════════ S6 · truncation note (A face) ════════
  await page.fill('.wsp-search-input', 'lib');
  await poll(async () => (await sentFrames()).filter(f => f.type === 'browsePath' && f.query === 'lib').length === 1, 3000);
  await answerFilter(HOME,
    [{ name: 'a1', path: `${HOME}/a1` }, { name: 'a2', path: `${HOME}/a2` }, { name: 'a3', path: `${HOME}/a3` }],
    { query: 'lib', total: 2500, truncated: true });
  await page.waitForTimeout(150);
  {
    const st = await page.evaluate(() => ({
      visible: !document.querySelector('.wsp-note').hidden,
      text: document.querySelector('.wsp-note').textContent,
      hint: document.querySelector('.wsp-note-hint')?.textContent ?? '',
    }));
    check('S6 truncation note: fact line carries hidden+total counts, hint line present',
      st.visible && st.text.includes('2497') && st.text.includes('2500') && st.hint.length > 0,
      JSON.stringify(st));
  }

  // ════════ S7 · Clear semantics ════════
  {
    const noClear = await page.evaluate(() => document.querySelectorAll('.wsp-clear').length);
    check('S7a no Clear button without showClear', noClear === 0, `count=${noClear}`);
  }
  await page.keyboard.press('Escape'); // close S5/S6 dialog
  await page.waitForTimeout(120);
  await openPicker({ startPath: GOOD, showClear: true });
  await expectWsp(page);
  await answerList(GOOD, ['b1']);
  await page.waitForTimeout(150);
  {
    await page.locator('.wsp-clear').click();
    await page.waitForTimeout(150);
    const st = await page.evaluate(() => ({
      cleared: window.__cleared, cancelled: window.__cancelled,
      overlay: document.querySelectorAll('.wsp-overlay').length,
    }));
    check('S7b Clear closes with onClear and WITHOUT onCancel',
      st.cleared === 'CLEARED' && st.cancelled === 'NOT-CALLED' && st.overlay === 0, JSON.stringify(st));
  }

  // ════════ S8/S9 · cancel + Esc ════════
  await openPicker({ startPath: '~' });
  await expectWsp(page);
  await page.locator('.wsp-cancel').click();
  await page.waitForTimeout(120);
  {
    const st = await page.evaluate(() => ({ cancelled: window.__cancelled, overlay: document.querySelectorAll('.wsp-overlay').length }));
    check('S8 Cancel → onCancel, dialog gone', st.cancelled === 'CANCELLED' && st.overlay === 0, JSON.stringify(st));
  }
  await openPicker({ startPath: '~' });
  await expectWsp(page);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(120);
  {
    const st = await page.evaluate(() => ({ cancelled: window.__cancelled, overlay: document.querySelectorAll('.wsp-overlay').length }));
    check('S9 Esc → onCancel, dialog gone', st.cancelled === 'CANCELLED' && st.overlay === 0, JSON.stringify(st));
  }

  // ════════ S10 · new-folder regression on the unified face ════════
  await openPicker({ startPath: '~' });
  await expectWsp(page);
  await answerList(HOME, ['fixture-root']);
  await page.waitForTimeout(150);
  await resetSent();
  await page.locator('.wsp-mkdir').click();
  await page.locator('.wsp-mkdir-input').fill('ws-new');
  await page.locator('.wsp-mkdir-ok').click();
  await poll(async () => (await sentFrames()).some(f => f.type === 'wsBrowse.mkdir' && f.name === 'ws-new'));
  await inject({ type: 'wsBrowseMkdir', ok: true, path: `${HOME}/ws-new` });
  await poll(async () => (await sentFrames()).some(f => f.type === 'wsBrowse.list' && f.path === `${HOME}/ws-new`));
  await answerList(`${HOME}/ws-new`, []);
  await page.waitForTimeout(150);
  {
    const crumbs = await page.evaluate(() => document.querySelector('.wsp-crumbs').textContent);
    check('S10 mkdir → auto-enter via the plain channel (wsBrowse.list)', crumbs.includes('ws-new'), `crumbs=${JSON.stringify(crumbs)}`);
  }
  await page.keyboard.press('Escape');

  // ════════ S11 · explorer root button regression (real button) ════════
  await resetSent();
  await page.locator('#explorer-folder-btn').click();
  await expectWsp(page);
  {
    const title = await page.evaluate(() => document.querySelector('.wsp-title').textContent);
    check('S11a explorer button opens the unified picker with the relocated project title',
      /选择项目目录|Select Project Directory/.test(title), `title=${JSON.stringify(title)}`);
  }
  await poll(async () => (await sentFrames()).some(f => f.type === 'wsBrowse.list'));
  await answerList(HOME, ['explorer-root-target']);
  await page.waitForTimeout(150);
  await page.locator('.wsp-row', { hasText: 'explorer-root-target' }).click();
  await poll(async () => (await sentFrames()).some(f => f.type === 'wsBrowse.list' && f.path === `${HOME}/explorer-root-target`));
  await answerList(`${HOME}/explorer-root-target`, []);
  await page.waitForTimeout(150);
  await page.locator('.wsp-pick').click();
  await page.waitForTimeout(250);
  {
    const st = await page.evaluate(() => ({
      overlay: document.querySelectorAll('.wsp-overlay').length,
      persisted: localStorage.getItem('nebflow_explorer_root') || localStorage.getItem('nb_explorer_root') || [...Object.keys(localStorage)].filter(k => k.includes('explorer')).map(k => `${k}=${localStorage.getItem(k)}`).join(','),
      treeHasError: !!document.querySelector('.explorer-error'),
    }));
    check('S11b pick persists the explorer root + tree re-renders without error',
      st.overlay === 0 && String(st.persisted).includes('explorer-root-target') && !st.treeHasError,
      JSON.stringify(st));
  }

  // ════════ S12 · sidebar folder-root wiring (static assertion) ════════
  // Baseline fact (read at this batch's baseline b4c0a875d): index.html has no
  // `#session-list` element, so renderSessionSidebar early-returns and the
  // sidebar folder tree — hence the folder context menu and its
  // set-project-root entry — is DORMANT in the live build (single-session
  // sidebar restructure left it unreachable). The rewiring itself is still
  // mandated (card §三.2): the dormant handler must point at the unified
  // picker, never at retired functions. Asserted at the source level:
  {
    const src = await page.evaluate(async () => await (await fetch('/js/sidebar.js')).text());
    const anchor = src.indexOf('menu.querySelector(\'[data-action="set-project-root"]\')');
    const block = anchor >= 0 ? src.slice(anchor, anchor + 1600) : '';
    check('S12 sidebar folder-root handler rewired onto the unified picker (openPicker + projectTitle + showClear + setFolderProjectRoot on both commit pipes + change event)',
      block.includes("import('./workspacePicker.js')")
        && block.includes('openPicker') && block.includes('workspacePicker.projectTitle')
        && block.includes('showClear: !!folder.projectRoot')
        && block.includes("type: 'setFolderProjectRoot', folderId: folder.id, projectRoot: path")
        && block.includes("type: 'setFolderProjectRoot', folderId: folder.id, projectRoot: null")
        && block.includes("new CustomEvent('project-root-changed')")
        && !block.includes('openPathPicker'),
      `anchorFound=${anchor >= 0}`);
  }

  // ════════ S13 · nav-origin listing failure raises onListUnavailable ════════
  await openPicker({ startPath: '~' });
  await expectWsp(page);
  await answerList(HOME, ['dead-end']);
  await page.waitForTimeout(150);
  await page.locator('.wsp-row', { hasText: 'dead-end' }).click();
  await poll(async () => (await sentFrames()).some(f => f.type === 'wsBrowse.list' && f.path === `${HOME}/dead-end`));
  await answerList(`${HOME}/dead-end`, [], { error: 'permission denied' });
  await page.waitForTimeout(200);
  {
    const st = await page.evaluate(() => ({
      unavail: window.__unavailable,
      row: document.querySelector('.wsp-empty')?.textContent ?? '',
      overlay: document.querySelectorAll('.wsp-overlay').length,
    }));
    check('S13 nav-origin failure: error surfaced to caller + readFail row; dialog stays for non-chat callers',
      String(st.unavail).includes('permission denied') && st.row.length > 0 && st.overlay === 1, JSON.stringify(st));
  }
  await page.keyboard.press('Escape');

  // ════════ S14 · staleness: superseded response dropped (seq guard) ════════
  await openPicker({ startPath: HOME });
  await expectWsp(page);
  await answerList(HOME, ['target-a']);
  await page.waitForTimeout(150);
  await page.locator('.wsp-row', { hasText: 'target-a' }).click();
  await poll(async () => (await sentFrames()).some(f => f.type === 'wsBrowse.list' && f.path === `${HOME}/target-a`));
  await answerList(`${HOME}/target-a`, ['deep-one']);
  await page.waitForTimeout(150);
  await page.locator('.wsp-row', { hasText: 'deep-one' }).click();
  await poll(async () => (await sentFrames()).some(f => f.type === 'wsBrowse.list' && f.path === `${HOME}/target-a/deep-one`));
  // newest response first, then a STALE response answering the superseded request
  await answerList(`${HOME}/target-a/deep-one`, ['final']);
  await answerList(`${HOME}/target-a`, ['STALE']);
  await page.waitForTimeout(200);
  {
    const st = await page.evaluate(() => ({
      names: [...document.querySelectorAll('.wsp-row-name')].map(n => n.textContent),
    }));
    check('S14 out-of-order responses: only the newest request paints',
      st.names.includes('final') && !st.names.includes('STALE'), JSON.stringify(st));
  }
  await page.keyboard.press('Escape');

  // ── console/pageerror hygiene ──
  check('Z1 zero page errors during the whole run', pageErrors.length === 0,
    pageErrors.slice(0, 3).join(' | '));
} catch (e) {
  console.error('[spec] FATAL ' + (e && e.stack ? e.stack : String(e)));
  code = 3;
} finally {
  await browser.close().catch(() => {});
  await new Promise((r) => server.close(r));
}

const fails = results.filter((r) => !r.pass);
console.log(`\n[spec] total=${results.length} pass=${results.length - fails.length} fail=${fails.length}`);
if (fails.length) console.log('[spec] FAILED: ' + fails.map((f) => f.name).join(' ; '));
if (!results.length) code = 3;
process.exit(code || (fails.length ? 1 : 0));

/** Wait for the unified picker dialog to be up. */
async function expectWsp(page) {
  await page.waitForSelector('.wsp-overlay', { state: 'visible', timeout: 4000 });
}
