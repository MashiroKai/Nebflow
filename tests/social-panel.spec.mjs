// social-panel.spec.mjs — socpanel batch acceptance (author rulings 令①–④ as
// redefined by `socremote-design` n-5c95367d; anchors W1–W17 of its §E.2).
//
// SELF-CONTAINED: a static server on a random free port (127.0.0.1) + page.route
// mocks; the browser is closed in `finally`; no port is ever taken from the
// host. Zero dependency on the running gateway.
//
// FOUR MODES (all offline):
//   · AFTER (default)         — serves src/main/resources/web; every anchor this
//                               batch implements is expected GREEN.
//   · BEFORE (SOCIAL_WEB_ROOT=<baseline web/>) — serves a pre-change tree; the
//                               same probes must read RED (absence evidence).
//   · FIXTURE (SOCIAL_MUTATE=adapter-true) — serves the same tree with the
//                               definition layer's `adapterRegistered` flipped
//                               to true (in memory, byte change reported). The
//                               W7 red-proof: the SAME assertion that is 0 in
//                               the production tree must fire here.
//   · API (SOCIAL_API_BASE=<url>[, <token>]) — optional extra leg that drives
//                               the REAL /api/social/* endpoints of an isolated
//                               instance (config round-trip + "no secret
//                               content in any response"). Skipped loudly when
//                               unset: the mock-only readings never claim to
//                               prove the server side.
//
// 🔴 DELIBERATELY SUSPENDED (dispatcher ruling 2026-09-19 21:04:34 = face C is
//    withheld; the design's author-face items O1–O5 are undecided): the remote
//    link (`[data-remote-url]`), the copy action (`[data-copy-link]`) and the
//    QR code are NOT implemented, so W5 / W15 / W17 cannot be green. This spec
//    asserts their ABSENCE and prints it as a SUSPENDED reading — it must never
//    be mistaken for a pass, and it must never be "fixed" by inventing an
//    address.
//
// Run:
//   node tests/social-panel.spec.mjs
//   SOCIAL_WEB_ROOT=/tmp/nb-socpanel-baseline/web node tests/social-panel.spec.mjs
//   SOCIAL_MUTATE=adapter-true node tests/social-panel.spec.mjs
//   SOCIAL_API_BASE=http://127.0.0.1:8155 SOCIAL_API_TOKEN=<token> node tests/social-panel.spec.mjs

import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const REPO_WEB = join(REPO, 'src', 'main', 'resources', 'web');
const WEB = process.env.SOCIAL_WEB_ROOT ? resolve(process.env.SOCIAL_WEB_ROOT) : REPO_WEB;
const MODE = process.env.SOCIAL_WEB_ROOT ? 'BEFORE' : (process.env.SOCIAL_MUTATE === 'adapter-true' ? 'FIXTURE' : 'AFTER');
const SHOTS = process.env.SOCIAL_SHOT_DIR || '/tmp/nb-socpanel';
const API_BASE = process.env.SOCIAL_API_BASE || '';
const API_TOKEN = process.env.SOCIAL_API_TOKEN || '';

// ── Fixed references taken from THIS repo (independent of the served tree) ──
const CHANNELS_MOD = await import(pathToFileURL(join(REPO_WEB, 'js', 'socialChannels.js')).href);
const EXPECTED_CARDS = CHANNELS_MOD.SOCIAL_CHANNELS.length;
const SOCIAL_KEYS = CHANNELS_MOD.SOCIAL_CHANNELS.flatMap((c) => [c.nameKey, c.descKey]);
const ZH = (await import(pathToFileURL(join(REPO_WEB, 'js', 'locales', 'zh-CN.js')).href)).default;
const EN = (await import(pathToFileURL(join(REPO_WEB, 'js', 'locales', 'en.js')).href)).default;

/** W6① — the fake-connection vocabulary (design §E.1: 「等待手机连接」/「已就绪」
 *  added to the original set). Any hit in the RENDERED text of this batch's own
 *  face is a red.
 *
 *  🔴 SCOPE (2026-09-19): the scan is `#social-modal` innerText plus the
 *  `#social-btn` title — i.e. this batch's surface only. It is deliberately NOT
 *  `document.body`: the shell hosts other panels, and their copy is not this
 *  batch's business (a body-wide scan false-hits `daemons.*` in the locale
 *  table — 「心跳进程」 — which no anchor of this batch touches). Widening it
 *  back would trade a real signal for noise; narrowing it further (e.g. cards
 *  only) would drop the panel's own captions. */
const BANNED = /已接入|连通|在线|健康|连接中|延迟|心跳|等待手机连接|已就绪|connected|online|healthy|latency/g;

/** The one place that defines "the panel's own rendered text". W6① (AFTER), the
 *  FIXTURE red-proof and the negative control all scan EXACTLY this — that is
 *  what makes "the same probe" a literal statement instead of a claim. */
async function bannedHits(page) {
  return page.evaluate((re) => {
    const rx = new RegExp(re, 'g');
    const m = document.getElementById('social-modal');
    const b = document.getElementById('social-btn');
    const text = ((m ? m.innerText : '') + ' ' + ((b && b.getAttribute('title')) || ''));
    const hit = text.match(rx);
    return hit ? [...new Set(hit)] : [];
  }, BANNED.source);
}

/** arch §7.4 — the four "no plaintext credential" blacklist expressions. */
const PLAINTEXT_RULES = [
  { id: 'prefixed-key', re: /(?:sk|pk|ghp|xox[baprs])[-_][A-Za-z0-9]{16,}/ },
  { id: 'bare-token', re: /^[A-Za-z0-9_-]{24,}$/ },
  { id: 'telegram-bot-token', re: /\b\d{8,10}:[A-Za-z0-9_-]{30,}\b/ },
  { id: 'wechat-app-secret', re: /\bwx[0-9a-f]{16}\b/ },
];

// ── Harness ────────────────────────────────────────────────────────────────
const results = [];
let failures = 0;
function check(id, ok, reading) {
  results.push({ id, ok: !!ok, reading: String(reading) });
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${reading}`);
}
function suspended(id, reading) {
  console.log(`SUSPENDED  ${id}  ${reading}`);
}
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json',
};

let mutationHits = 0;
/** The fixture copy: served in memory, never written to the tree. */
function mutate(rel, body) {
  if (process.env.SOCIAL_MUTATE !== 'adapter-true') return body;
  if (rel !== '/js/socialChannels.js') return body;
  const hits = (body.match(/adapterRegistered: false/g) || []).length;
  mutationHits += hits;
  return body.replace(/adapterRegistered: false/g, 'adapterRegistered: true');
}

async function startServer() {
  const server = createServer(async (req, res) => {
    try {
      const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
      let rel = path === '/' ? '/index.html' : path;
      const file = resolve(join(WEB, rel));
      if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
      let data = await readFile(file);
      if (rel.endsWith('.js') || rel.endsWith('.css') || rel.endsWith('.html')) {
        data = Buffer.from(mutate(rel, data.toString('utf8')), 'utf8');
      }
      res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
      res.end(data);
    } catch {
      res.writeHead(404); res.end('not found');
    }
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  return { server, base: `http://127.0.0.1:${server.address().port}` };
}

/** Backend truth the mock answers from (shape = the real contract's). */
const apiState = {
  channels: {},
  probes: {},
  posts: [],
  failPost: false,
};

const SECRET_FILE = {
  wechat: { app_secret: 'social-wechat-app-secret', token: 'social-wechat-token', aes_key: 'social-wechat-aes-key' },
  feishu: {
    app_secret: 'social-feishu-app-secret',
    verification_token: 'social-feishu-verification-token',
    encrypt_key: 'social-feishu-encrypt-key',
  },
  telegram: { bot_token: 'social-telegram-bot-token' },
};

function stubApi(page) {
  page.route('**/api/**', (r) => r.fulfill({ json: {} }));       // catch-all FIRST (lowest priority)
  page.route('**/api/social/probe**', (r) => {
    const url = new URL(r.request().url());
    const id = url.searchParams.get('channel') || '';
    return r.fulfill({ json: { adapterRegistered: false, secrets: apiState.probes[id] || {} } });
  });
  page.route('**/api/social/channels/*', (r) => {
    const req = r.request();
    const id = decodeURIComponent(req.url().split('/').pop().split('?')[0]);
    if (req.method() !== 'POST') return r.fallback();
    const body = JSON.parse(req.postData() || '{}');
    apiState.posts.push({ id, body });
    if (apiState.failPost) return r.fulfill({ status: 403, json: { error: 'secret_mode' } });
    const entry = apiState.channels[id] || { enabled: false, fields: {} };
    entry.enabled = body.enabled === true;
    for (const [k, v] of Object.entries(body.fields || {})) {
      if (SECRET_FILE[id] && SECRET_FILE[id][k]) {
        if (!v) continue;
        entry.fields[`${k}_ref`] = `~/.nebflow/secrets/${SECRET_FILE[id][k]}`;
        apiState.probes[id] = { ...(apiState.probes[id] || {}), [k]: { exists: true, modeOk: true, readable: true } };
      } else {
        entry.fields[k] = v;
      }
    }
    apiState.channels[id] = entry;
    return r.fulfill({ json: { ok: true, probe: apiState.probes[id] || {} } });
  });
  page.route('**/api/social/channels', (r) => r.fulfill({
    json: { channels: apiState.channels, adapterRegistered: false },
  }));
}

async function boot(page, opts = {}) {
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage(() => {});
    ws.send(JSON.stringify({
      type: 'configData',
      config: '{"features":{"friends":true}}',
      configured: true, onboarding: 'done', models: [], defaults: {},
    }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    ws.send(JSON.stringify({ type: 'serverConfig', mcpServers: [], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] }));
  });
  stubApi(page);
  if (opts.locale) {
    await page.addInitScript((loc) => { try { localStorage.setItem('nebflow_locale', loc); } catch { /* ignore */ } }, opts.locale);
  }
  if (opts.collapsed) {
    // Design §M2: the 375 leg runs in the design's own mobile configuration —
    // side bar collapsed — by REUSING the existing restore path (index.html's
    // inline pre-paint script accepts the legacy `nebflow_sidebar_collapsed`
    // spelling and adds `body.sidebar-collapsed` before first paint). No new
    // mechanism, and the reading states that this leg was collapsed.
    await page.addInitScript(() => { try { localStorage.setItem('nebflow_sidebar_collapsed', 'true'); } catch { /* ignore */ } });
  }
}

/** The reference form of --color-danger, resolved by the page itself. */
const DANGER_PROBE = `(() => {
  const d = document.createElement('div');
  d.style.color = 'var(--color-danger)';
  document.body.appendChild(d);
  const v = getComputedStyle(d).color;
  d.remove();
  return v;
})()`;

const statuses = (page) => page.$$eval('#social-modal .social-card[data-channel]',
  (els) => els.map((e) => ({ id: e.dataset.channel, status: (e.querySelector('[data-status]') || {}).dataset?.status })));

async function openPanel(page) {
  await page.waitForSelector('#social-btn', { timeout: 8000 });
  // The button exists from the first byte of static HTML; the <i> → <svg>
  // conversion only happens once the boot wiring ran (activityBar.js), so it is
  // the readiness signal that makes the click deterministic after a reload.
  await page.waitForFunction(() => !!document.querySelector('#social-btn svg'), undefined, { timeout: 8000 });
  await page.click('#social-btn');
  await page.waitForSelector('#social-overlay.on', { timeout: 4000 });
  // 🔴 The panel paints synchronously and THEN converges on backend truth
  // (config + one probe per channel). Reading the cards before that convergence
  // is a race: it once produced a "notConfigured" reading for a configured
  // fixture, and — worse — a later `renderAll()` could wipe values typed in
  // between. `aria-busy` (set by the panel itself) is the convergence signal, so
  // no timer is involved. Set BEFORE the load begins, so it cannot be mistaken
  // for the previous round's value.
  await page.waitForFunction(() => {
    const s = document.getElementById('social-section-channels');
    return !!s && s.getAttribute('aria-busy') === 'false';
  }, undefined, { timeout: 8000 });
}

async function shot(page, name) {
  try {
    await mkdir(SHOTS, { recursive: true });
    await page.screenshot({ path: join(SHOTS, `${name}.png`) });
  } catch { /* screenshots are evidence only — never a gate */ }
}

// ── Checks (AFTER tree) ────────────────────────────────────────────────────
async function afterSuite(browser, base) {
  // W12 + write-face whitelist: the diff must stay inside the batch's file face
  const WHITELIST = [
    'src/main/resources/web/index.html',
    'src/main/resources/web/js/socialChannels.js',
    'src/main/resources/web/js/socialPanel.js',
    'src/main/resources/web/css/social.css',
    'src/main/resources/web/js/locales/zh-CN.js',
    'src/main/resources/web/js/locales/en.js',
    'src/main/resources/web/js/activityBar.js',
    'src/main/resources/web/js/main.js',
    'src/main/scala/nebflow/gateway/RestApiRoutes.scala',
    'src/main/scala/nebflow/social/SocialChannels.scala',
    'tests/social-panel.spec.mjs',
  ];
  let diff = '';
  try {
    // The batch's OWN file face: working-tree edits + branch commits against the
    // merge base — deliberately NOT "working tree vs main", which would mix in
    // any other sink that lands on main while this spec runs.
    const base = execFileSync('git', ['merge-base', 'main', 'HEAD'], { cwd: REPO, encoding: 'utf8' }).trim();
    // `-uall`: untracked entries are listed FILE BY FILE. The default would
    // collapse a new directory (`src/main/scala/nebflow/social/`) into one line
    // and the whitelist check could then neither pass nor name the real file.
    diff = execFileSync('git', ['status', '--porcelain', '-uall'], { cwd: REPO, encoding: 'utf8' }).replace(/^\s*\S+\s+/gm, '')
      + execFileSync('git', ['diff', '--name-only', base, 'HEAD'], { cwd: REPO, encoding: 'utf8' });
  } catch (e) { diff = String(e.stdout || ''); }
  const files = [...new Set(diff.split('\n').map((s) => s.trim()).filter(Boolean))];
  const promptFace = files.filter((f) => /^src\/main\/resources\/seed\/agents\//.test(f)
    || /(^|\/)SKILL\.md$/.test(f) || /AGENTS\.md$/.test(f) || /plugin\.json$/.test(f));
  check('W12 zero prompt face', promptFace.length === 0, `prompt-face lines = ${promptFace.length} [${promptFace.join(', ')}]`);
  const outside = files.filter((f) => !WHITELIST.includes(f));
  check('SCOPE whitelist', outside.length === 0,
    `base=merge-base(main,HEAD) files=${files.length} outside-face=[${outside.join(', ')}] all=[${files.join(' ')}]`);

  // W11 i18n key parity
  const zhKeys = Object.keys(ZH).filter((k) => k.startsWith('social.'));
  const enKeys = Object.keys(EN).filter((k) => k.startsWith('social.'));
  const onlyZh = zhKeys.filter((k) => !(k in EN));
  const onlyEn = enKeys.filter((k) => !(k in ZH));
  const emptyVals = [...zhKeys, ...enKeys].filter((k) => !String(ZH[k] ?? EN[k] ?? '').trim());
  check('W11 i18n key sets', zhKeys.length > 0 && zhKeys.length === enKeys.length && !onlyZh.length && !onlyEn.length && !emptyVals.length,
    `zh=${zhKeys.length} en=${enKeys.length} onlyZh=[${onlyZh}] onlyEn=[${onlyEn}] empty=${emptyVals.length}`);

  // W6③ static: the definition layer never carries an enabled adapter flag
  const src = await readFile(join(WEB, 'js', 'socialChannels.js'), 'utf8');
  const enabledFlags = (src.match(/adapterRegistered: *true/g) || []).length;
  check('W6③ adapterRegistered:true in definition layer = 0', enabledFlags === 0, `grep -c = ${enabledFlags}`);

  // W6① + W2/W4 family in both themes and both locales
  for (const theme of ['light', 'dark']) {
    for (const locale of ['zh-CN', 'en']) {
      const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: theme });
      const page = await ctx.newPage();
      apiState.channels = {};
      apiState.probes = {};
      await boot(page, { locale });
      await page.goto(base);
      await openPanel(page);
      const tag = `${theme}/${locale}`;

      const banned = await bannedHits(page);
      check(`W6① banned vocabulary in the panel surface (${tag})`, banned.length === 0,
        `scope=#social-modal+#social-btn[title] hits=[${banned.join(', ')}]`);

      const wired = await page.evaluate(() => ({
        connected: document.querySelectorAll('#social-modal [data-status="connected"]').length,
        dots: document.querySelectorAll('#social-modal .social-status-dot:not([hidden])').length,
      }));
      check(`W6② data-status=connected / dot counts = 0 (${tag})`, wired.connected === 0 && wired.dots === 0,
        `connected=${wired.connected} dots=${wired.dots}`);

      const cards = await page.evaluate(() => {
        const all = [...document.querySelectorAll('#social-modal .social-card[data-channel]')];
        return {
          count: all.length,
          heads: all.map((c) => {
            const h = c.querySelector('.social-card-head');
            return h ? {
              icon: h.querySelectorAll('svg, i[data-lucide]').length,
              name: !!h.querySelector('.social-card-name')?.textContent?.trim(),
              status: !!h.querySelector('[data-status]'),
              toggle: !!h.querySelector('.nb-toggle[role="switch"]'),
            } : null;
          }),
        };
      });
      const headsOk = cards.heads.every((h) => h && h.icon > 0 && h.name && h.status && h.toggle);
      check(`W4 card shape (count = definition length) (${tag})`, cards.count === EXPECTED_CARDS && headsOk,
        `cards=${cards.count} expected=${EXPECTED_CARDS} headsOk=${headsOk}`);
      const remote = await page.locator('#social-section-remote').count();
      check(`W4 remote section always present (${tag})`, remote === 1, `#social-section-remote = ${remote}`);

      const st = await statuses(page);
      check(`W8 empty config ⇒ every card notConfigured (${tag})`,
        st.length === EXPECTED_CARDS && st.every((s) => s.status === 'notConfigured'),
        JSON.stringify(st));

      const shell = await page.evaluate(() => {
        const m = document.getElementById('social-modal');
        const o = document.getElementById('social-overlay');
        const cs = getComputedStyle(m);
        return { blur: cs.backdropFilter || cs.webkitBackdropFilter, overlay: getComputedStyle(o).backgroundColor };
      });
      check(`W3 modal shell: blur(24px) + transparent overlay (${tag})`,
        String(shell.blur).includes('blur(24px)') && shell.overlay === 'rgba(0, 0, 0, 0)',
        `backdropFilter=${shell.blur} overlayBg=${shell.overlay}`);

      const entry = await page.evaluate(() => {
        const btn = document.getElementById('social-btn');
        const contacts = document.getElementById('contacts-btn');
        const spacer = document.querySelector('#activity-bar .activity-spacer');
        const svg = btn.querySelector('svg');
        const cSvg = contacts.querySelector('svg');
        const cs = getComputedStyle(btn);
        const ccs = getComputedStyle(contacts);
        return {
          prev: btn.previousElementSibling && btn.previousElementSibling.id,
          next: btn.nextElementSibling === spacer,
          above: btn.getBoundingClientRect().top >= contacts.getBoundingClientRect().bottom,
          gap: Math.round(btn.getBoundingClientRect().top - contacts.getBoundingClientRect().bottom),
          cls: btn.classList.contains('activity-btn'),
          smartphone: btn.querySelectorAll('svg.lucide-smartphone').length,
          placeholders: btn.querySelectorAll('i[data-lucide]').length,
          box: svg && { w: Math.round(svg.getBoundingClientRect().width), h: Math.round(svg.getBoundingClientRect().height) },
          cbox: cSvg && { w: Math.round(cSvg.getBoundingClientRect().width), h: Math.round(cSvg.getBoundingClientRect().height) },
          stroke: svg && svg.getAttribute('stroke-width'),
          fill: svg && svg.getAttribute('fill'),
          same: ['width', 'height', 'borderRadius', 'color', 'paddingTop', 'paddingLeft'].every((p) => cs[p] === ccs[p]),
        };
      });
      check(`W1 entry position (${tag})`, entry.prev === 'contacts-btn' && entry.next && entry.above,
        JSON.stringify(entry));
      check(`W2 smartphone icon + family (${tag})`,
        entry.smartphone === 1 && entry.placeholders === 0 && entry.cls
        && entry.box.w === entry.cbox.w && entry.box.h === entry.cbox.h
        && entry.stroke === '2' && entry.fill === 'none',
        `svg=${entry.smartphone} placeholder=${entry.placeholders} box=${JSON.stringify(entry.box)} contacts=${JSON.stringify(entry.cbox)} stroke=${entry.stroke} fill=${entry.fill}`);
      check(`W2b computed-style family equality (${tag})`, entry.same, `five-tuple equal = ${entry.same}`);

      const gapOk = entry.gap === 8;
      check(`W2c sibling gap = activity-bar gap (${tag})`, gapOk, `gap=${entry.gap}px`);

      if (theme === 'light' && locale === 'zh-CN') await shot(page, 'after-1440-light');
      if (theme === 'dark' && locale === 'zh-CN') await shot(page, 'after-1440-dark');
      await ctx.close();
    }
  }

  // V4 theme parity: the pill colour must come from tokens, i.e. differ per theme
  const lightColor = await withPanel(browser, base, 'light', async (page) => page.evaluate(
    () => getComputedStyle(document.querySelector('#social-modal .social-status-pill')).color));
  const darkColor = await withPanel(browser, base, 'dark', async (page) => page.evaluate(
    () => getComputedStyle(document.querySelector('#social-modal .social-status-pill')).color));
  check('V4 pill colour is token-driven (light ≠ dark)', lightColor !== darkColor, `light=${lightColor} dark=${darkColor}`);

  // W9 configInvalid: pattern violation and a missing referenced file
  await withPanel(browser, base, 'light', async (page) => {
    apiState.channels = { wechat: { enabled: false, fields: { app_id: 'not-a-wx-id' } } };
    apiState.probes = {};
    await page.reload();
    await openPanel(page);
    let st = await statuses(page);
    let pill = await page.evaluate(() => {
      const c = document.querySelector('.social-card[data-channel="wechat"]');
      const p = c.querySelector('.social-status-pill');
      const d = document.createElement('div');
      d.style.color = 'var(--color-danger)';
      document.body.appendChild(d);
      const danger = getComputedStyle(d).color; d.remove();
      return { status: p.dataset.status, color: getComputedStyle(p).color, danger,
        hint: c.querySelector('.social-card-hint').textContent, hidden: c.querySelector('.social-card-hint').hasAttribute('hidden') };
    });
    check('W9 pattern violation ⇒ configInvalid + danger colour',
      pill.status === 'configInvalid' && pill.color === pill.danger && !pill.hidden && /应用 ID|App ID/.test(pill.hint),
      JSON.stringify(pill));

    apiState.channels = { wechat: { enabled: true, fields: {
      app_id: 'wx0123456789abcdef',
      app_secret_ref: '~/.nebflow/secrets/social-wechat-app-secret',
      token_ref: '~/.nebflow/secrets/social-wechat-token',
      aes_key_ref: '~/.nebflow/secrets/social-wechat-aes-key',
    } } };
    apiState.probes = { wechat: {
      app_secret: { exists: false, modeOk: false, readable: false },
      token: { exists: true, modeOk: true, readable: true },
      aes_key: { exists: true, modeOk: true, readable: true },
    } };
    await page.reload();
    await openPanel(page);
    st = await statuses(page);
    pill = await page.evaluate(() => {
      const c = document.querySelector('.social-card[data-channel="wechat"]');
      return { status: c.querySelector('.social-status-pill').dataset.status, hint: c.querySelector('.social-card-hint').textContent };
    });
    check('W9 missing referenced file ⇒ configInvalid + path in hint',
      pill.status === 'configInvalid' && pill.hint.includes('~/.nebflow/secrets/social-wechat-app-secret'),
      JSON.stringify(pill));

    // W14 the full "fill → save → probe re-read" flow (令④), incl. the negative
    // assertion: no "go edit the file" step anywhere in the panel.
    apiState.channels = {};
    apiState.probes = {};
    apiState.posts = [];
    await page.reload();
    await openPanel(page);
    await page.fill('.social-card[data-channel="wechat"] [data-field="app_id"]', 'wx0123456789abcdef');
    await page.fill('.social-card[data-channel="wechat"] [data-field="app_secret"]', 'PLAINTEXT-SECRET-MARKER');
    await page.fill('.social-card[data-channel="wechat"] [data-field="token"]', 'PLAINTEXT-TOKEN-MARKER');
    await page.fill('.social-card[data-channel="wechat"] [data-field="aes_key"]', 'PLAINTEXT-AES-MARKER');
    await page.click('.social-card[data-channel="wechat"] [data-save]');
    await page.waitForFunction(() => {
      const c = document.querySelector('.social-card[data-channel="wechat"]');
      return c && c.querySelector('.social-status-pill').dataset.status === 'configuredNotLinked';
    }, undefined, { timeout: 6000 });
    const step3 = await page.evaluate(() => {
      const c = document.querySelector('.social-card[data-channel="wechat"]');
      return {
        status: c.querySelector('.social-status-pill').dataset.status,
        hint: c.querySelector('.social-card-hint').textContent,
        secretInput: c.querySelector('[data-field="app_secret"]').value,
        secretState: c.querySelector('.social-secret-state').textContent,
        noFileStep: !/编辑文件|手动放置|去配置|Bot Channels/.test(document.getElementById('social-modal').innerText),
      };
    });
    check('W14 填 → 存 → 探针读回',
      step3.status === 'configuredNotLinked' && /exists/.test(step3.hint) && /modeOk/.test(step3.hint),
      JSON.stringify(step3));
    check('W14b panel discards the credential and offers no file step',
      step3.secretInput === '' && step3.noFileStep, JSON.stringify({ value: step3.secretInput, noFileStep: step3.noFileStep }));
    check('W10② POST body carries the plaintext exactly once (the only place it ever appears)',
      apiState.posts.length === 1
      && JSON.stringify(apiState.posts[0].body).includes('PLAINTEXT-SECRET-MARKER'),
      `posts=${apiState.posts.length}`);

    const afterGet = await page.evaluate(async () => {
      const r = await fetch('/api/social/channels');
      return await r.text();
    });
    check('W10② config surface never carries credential content',
      !afterGet.includes('PLAINTEXT-SECRET-MARKER') && !afterGet.includes('PLAINTEXT-TOKEN-MARKER'),
      `bodyHasPlaintext=${afterGet.includes('PLAINTEXT-SECRET-MARKER')}`);

    // W13 keyboard / focus
    await page.keyboard.press('Escape');
    const restored = await page.evaluate(() => document.activeElement && document.activeElement.id);
    const open = await page.evaluate(() => document.getElementById('social-overlay').classList.contains('on'));
    check('W13 Esc closes and returns focus to the entry', restored === 'social-btn' && !open,
      `activeElement=${restored} overlayOn=${open}`);

    await openPanel(page);
    const first = await page.evaluate(() => document.activeElement && document.activeElement.id);
    for (let i = 0; i < 40; i++) await page.keyboard.press('Tab');
    const trapped = await page.evaluate(() => {
      const m = document.getElementById('social-modal');
      return { inside: m.contains(document.activeElement), active: document.activeElement && document.activeElement.id };
    });
    check('W13b 40×Tab never escapes the dialog', trapped.inside && first !== undefined,
      `inside=${trapped.inside} active=${trapped.active}`);

    // W6① negative control: the blacklist itself must be able to fire — injected
    // into the SAME scope the AFTER scan reads, then removed.
    const bannedProbe = await bannedHits(page);
    check('W6① negative control: the scan scope is clean before injection', bannedProbe.length === 0, `hits=[${bannedProbe.join(', ')}]`);
    const injectedHits = await page.evaluate((re) => {
      const rx = new RegExp(re, 'g');
      const m = document.getElementById('social-modal');
      const d = document.createElement('div');
      d.textContent = '已接入 在线 healthy latency 等待手机连接';
      m.appendChild(d);
      const hit = m.innerText.match(rx);
      d.remove();
      return hit ? [...new Set(hit)] : [];
    }, BANNED.source);
    check('W6① negative control: injected vocabulary IS caught', injectedHits.length >= 4, `hits=[${injectedHits.join(', ')}]`);

    // W10④ negative control: the plaintext rules catch an injected credential
    const injected = { wechat: { fields: { app_secret_ref: 'sk-abcdefghijklmnopqrstuvwx', token_ref: 'x'.repeat(32) } } };
    const ruleHits = [];
    for (const v of Object.values(injected.wechat.fields)) {
      for (const rule of PLAINTEXT_RULES) if (rule.re.test(v)) ruleHits.push(rule.id);
    }
    check('W10④ negative control: plaintext rules fire on an injected credential', ruleHits.length >= 2, `ruleHits=[${ruleHits.join(', ')}]`);

    // W5 / W15 / W17 — suspended, and asserted ABSENT (never green by omission)
    const absent = await page.evaluate(() => ({
      remoteUrl: document.querySelectorAll('[data-remote-url]').length,
      copyLink: document.querySelectorAll('[data-copy-link]').length,
      qr: document.querySelectorAll('#social-section-remote svg.lucide-qr-code, #social-section-remote canvas, #social-section-remote img').length,
    }));
    suspended('W5 remote URL / W15 real reachability / W17 clipboard', `data-remote-url=${absent.remoteUrl} data-copy-link=${absent.copyLink} qr=${absent.qr} — face C withheld by the dispatcher ruling (O1–O5 undecided): no link, no QR, no guessed address.`);
    check('W5/W15/W17 not faked in the absence of a ruling',
      absent.remoteUrl === 0 && absent.copyLink === 0 && absent.qr === 0,
      `no fabricated link/QR present: ${JSON.stringify(absent)}`);
  });

  // W16 / B-1..B-7 — 375×812, both themes.
  // 🔴 Configuration, read off the design's own probe (§B.1: 「壳 @375x812 …
  // bodyScrollW=375 hOverflow=false barW=48」): side bar COLLAPSED (§M2 — reuse
  // the existing restore path, no new mechanism) and the optional Canvas panel
  // closed. Both are user-toggled surfaces driven through the app's OWN
  // controls here, so the leg is the design's configuration, not a convenient
  // one. Readings for the other two configurations are printed as context and
  // are never asserted (at 375 the app shell overflows by itself with the Canvas
  // panel open — measured identically on the pre-change tree, see beforeSuite).
  for (const theme of ['light', 'dark']) {
    const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, colorScheme: theme, isMobile: false });
    const page = await ctx.newPage();
    apiState.channels = { telegram: { enabled: false, fields: { chat_id: '-1001234567890' } } };
    apiState.probes = {};
    await boot(page, { collapsed: true });
    await page.goto(base);
    await page.waitForFunction(() => !!document.querySelector('#social-btn svg'), undefined, { timeout: 8000 });

    // B-5 is an ENTRY-side anchor (「`#social-btn` 可点」), so it is read with the
    // dialog CLOSED: while the dialog is open its overlay legitimately owns the
    // pointer, and reading the entry then would measure the overlay, not the entry.
    const closed = await page.evaluate(() => {
      // Canvas panel: closed through its own control (the design's configuration).
      const canvasWasOpen = document.body.classList.contains('canvas-open');
      document.getElementById('canvas-close-btn')?.click();
      const btn = document.getElementById('social-btn').getBoundingClientRect();
      const hit = document.elementFromPoint(btn.left + btn.width / 2, btn.top + btn.height / 2);
      const vw = window.innerWidth;
      const offenders = [...document.querySelectorAll('body *')].filter((el) => {
        const r = el.getBoundingClientRect();
        return r.width > 0 && (r.right > vw + 1 || r.left < -1);
      }).length;
      return {
        canvasWasOpen,
        sidebarCollapsed: document.body.classList.contains('sidebar-collapsed'),
        entry: {
          left: Math.round(btn.left), top: Math.round(btn.top), right: Math.round(btn.right), bottom: Math.round(btn.bottom),
          inViewport: btn.left >= 0 && btn.right <= vw && btn.top >= 0 && btn.bottom <= 812,
          // elementFromPoint at the centre returns the icon (an <svg> child with
          // no id) or the button itself — B-5 asks whether the point BELONGS to
          // the entry, so walk up instead of comparing ids.
          hit: !!hit && !!hit.closest && hit.closest('#social-btn') === document.getElementById('social-btn'),
          hitTag: hit ? hit.tagName + (hit.id ? '#' + hit.id : '') : null,
        },
        offenders,
        scrollWidth: document.documentElement.scrollWidth,
      };
    });
    check(`W16 B-5 entry clickable at 375 (${theme})`, closed.entry.hit && closed.entry.inViewport,
      `${JSON.stringify(closed.entry)} — elementFromPoint lands inside the entry (${closed.entry.hitTag})`);

    await openPanel(page);
    const geo = await page.evaluate(() => {
      const vw = window.innerWidth;
      const m = document.getElementById('social-modal').getBoundingClientRect();
      const fields = [...document.querySelectorAll('#social-modal input, #social-modal select')]
        .map((el) => el.getBoundingClientRect());
      const cards = [...document.querySelectorAll('.social-card')].map((c) => ({ sw: c.scrollWidth, cw: c.clientWidth }));
      const card = document.querySelector('.social-card[data-channel="telegram"]');
      const labels = [...card.querySelectorAll('.social-field')].map((l) => Math.round(l.getBoundingClientRect().left));
      const panel = document.getElementById('social-modal');
      const overlay = document.getElementById('social-overlay');
      const offenders = [...document.querySelectorAll('body *')].filter((el) => {
        const r = el.getBoundingClientRect();
        return r.width > 0 && (r.right > vw + 1 || r.left < -1);
      });
      return {
        collapsed: document.body.classList.contains('sidebar-collapsed'),
        scrollWidth: document.documentElement.scrollWidth,
        modal: { left: Math.round(m.left), right: Math.round(m.right), top: Math.round(m.top), bottom: Math.round(m.bottom) },
        clipped: fields.filter((r) => r.width <= 0 || r.right > vw).length,
        overflowCards: cards.filter((c) => c.sw > c.cw + 1).length,
        labels,
        offenders: offenders.length,
        offendersInPanel: offenders.filter((el) => panel.contains(el) || overlay.contains(el)).length,
      };
    });
    check(`W16 B-1 no horizontal overflow at 375 (${theme})`,
      geo.scrollWidth <= 375 && geo.collapsed,
      `sidebar-collapsed=${geo.collapsed} canvas=${closed.canvasWasOpen ? 'closed by the probe via #canvas-close-btn' : 'already closed'} scrollWidth=${geo.scrollWidth} (viewport 375)`);
    check(`W16 B-1(in-face) opening the panel changes no overflow reading (${theme})`,
      geo.scrollWidth === closed.scrollWidth && geo.offendersInPanel === 0,
      `document.scrollWidth closed=${closed.scrollWidth} open=${geo.scrollWidth}; elements sticking out INSIDE the panel/overlay=${geo.offendersInPanel} (whole-document offenders, incl. the shell's own off-screen sliders: closed=${closed.offenders} open=${geo.offenders})`);
    check(`W16 B-2 modal inside the viewport (${theme})`,
      geo.modal.left >= 0 && geo.modal.right <= 375 && geo.modal.top >= 0 && geo.modal.bottom <= 812, JSON.stringify(geo.modal));
    check(`W16 B-3 no clipped field (${theme})`, geo.clipped === 0, `clipped=${geo.clipped}`);
    check(`W16 B-4 no card overflow (${theme})`, geo.overflowCards === 0, `overflowCards=${geo.overflowCards}`);
    check(`W16 B-6 fields single column (${theme})`, geo.labels.length > 1 && new Set(geo.labels).size === 1, `lefts=${JSON.stringify(geo.labels)}`);
    // context readings (never assertions): the two configurations the design's
    // probe did NOT measure, printed so the deviation is on the record.
    if (theme === 'light') await shot(page, 'after-375-light');
    if (theme === 'dark') await shot(page, 'after-375-dark');
    const ctxRead = await page.evaluate(() => {
      const out = {};
      document.getElementById('canvas-toggle-btn')?.click();  // reopen the Canvas panel via its own control
      return new Promise((r) => setTimeout(() => {
        out.canvasOpen = document.documentElement.scrollWidth;
        document.body.classList.remove('sidebar-collapsed');
        setTimeout(() => { out.canvasOpenSidebarExpanded = document.documentElement.scrollWidth; r(out); }, 200);
      }, 200));
    });
    console.log(`  · context ${theme} @375 (never asserted): document.scrollWidth with Canvas panel OPEN = ${ctxRead.canvasOpen}; + side bar expanded = ${ctxRead.canvasOpenSidebarExpanded} — both are the app shell's own pre-existing behaviour (same numbers on the pre-change tree, see beforeSuite).`);
    await ctx.close();
  }
}

async function withPanel(browser, base, theme, fn) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: theme });
  const page = await ctx.newPage();
  await boot(page);
  await page.goto(base);
  await openPanel(page);
  try {
    return await fn(page);
  } finally {
    await ctx.close();
  }
}

// ── FIXTURE: W7 red proof (the same probe must fire when the flag is flipped) ─
async function fixtureSuite(browser, base) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  // Every required field present + probes clean ⇒ the ONLY remaining variable is
  // the flapped flag.
  apiState.channels = {
    wechat: { enabled: true, fields: {
      app_id: 'wx0123456789abcdef',
      app_secret_ref: '~/.nebflow/secrets/social-wechat-app-secret',
      token_ref: '~/.nebflow/secrets/social-wechat-token',
      aes_key_ref: '~/.nebflow/secrets/social-wechat-aes-key',
    } },
  };
  apiState.probes = { wechat: {
    app_secret: { exists: true, modeOk: true, readable: true },
    token: { exists: true, modeOk: true, readable: true },
    aes_key: { exists: true, modeOk: true, readable: true },
  } };
  await boot(page, { locale: 'zh-CN' }); // explicit: the caption assertion below is zh-only, so pin the locale instead of relying on the default
  await page.goto(base);
  await openPanel(page);
  // Kept AFTER the load on purpose: the served bytes are mutated lazily as they
  // are requested, so a check placed before `goto` would always read 0 and
  // would silently stop proving anything.
  check('FIXTURE mutation applied to the served definition layer',
    mutationHits === EXPECTED_CARDS, `adapterRegistered:false → true hits = ${mutationHits} (expected ${EXPECTED_CARDS})`);
  const st = await statuses(page);
  const text = await page.evaluate(() => {
    const m = document.getElementById('social-modal');
    return m ? m.innerText : '';
  });
  const connected = st.filter((s) => s.status === 'connected').length;
  check('W7 fixture: adapterRegistered=true ⇒ connected pill appears',
    connected >= 1, `locale=zh-CN ${JSON.stringify(st)}`);
  check('W7 fixture: the connected caption is reachable (state machine is total)',
    /已接入/.test(text), `caption present = ${/已接入/.test(text)}`);
  const banned = await bannedHits(page);
  check('W7 fixture: the SAME banned-vocabulary probe goes red here (proves W6① has teeth)',
    banned.length > 0, `hits=[${banned.join(', ')}]`);
  await shot(page, 'fixture-1440-light');
  await ctx.close();
}

// ── BEFORE: red-by-absence evidence on a pre-change tree ────────────────────
async function beforeSuite(browser, base) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  await boot(page);
  await page.goto(base);
  await new Promise((r) => setTimeout(r, 1200));
  const counts = await page.evaluate(() => ({
    btn: document.querySelectorAll('#social-btn').length,
    overlay: document.querySelectorAll('#social-overlay').length,
    modal: document.querySelectorAll('#social-modal').length,
    remote: document.querySelectorAll('#social-section-remote').length,
    cards: document.querySelectorAll('.social-card').length,
    rule: !![...document.styleSheets].some((s) => String(s.href || '').includes('social.css')),
  }));
  check('W1/W2 red before: entry absent', counts.btn === 0, JSON.stringify(counts));
  check('W3/W4 red before: modal + cards + remote section absent',
    counts.overlay === 0 && counts.modal === 0 && counts.remote === 0 && counts.cards === 0, JSON.stringify(counts));
  check('red before: social.css not loaded', counts.rule === false, `stylesheet linked = ${counts.rule}`);
  const zhKeys = Object.keys(ZH).filter((k) => k.startsWith('social.'));
  check('W11 red before: social.* keys absent from the served locale table',
    !(await readFile(join(WEB, 'js', 'locales', 'zh-CN.js'), 'utf8')).includes("'social."),
    `reference locale has ${zhKeys.length} social.* keys (this repo); served tree has none`);
  await ctx.close();

  // Context (never an assertion): the PRE-CHANGE tree at 375 reproduces the
  // shell's own overflow readings, so W16's configurations are checkable as
  // pre-existing behaviour instead of being taken on trust. Measured on the old
  // tree, which contains none of this batch's files.
  const mctx = await browser.newContext({ viewport: { width: 375, height: 812 } });
  const mpage = await mctx.newPage();
  await boot(mpage);
  await mpage.goto(base);
  await new Promise((r) => setTimeout(r, 1000));
  const shell = await mpage.evaluate(() => new Promise((done) => {
    const out = { hasSocialBtn: !!document.getElementById('social-btn'), hasSocialOverlay: !!document.getElementById('social-overlay') };
    out.canvasOpen = document.documentElement.scrollWidth;
    document.getElementById('canvas-close-btn')?.click();
    setTimeout(() => {
      out.canvasClosed = document.documentElement.scrollWidth;
      document.body.classList.add('sidebar-collapsed');
      setTimeout(() => { out.canvasClosedSidebarCollapsed = document.documentElement.scrollWidth; done(out); }, 200);
    }, 250);
  }));
  console.log(`  · context BEFORE tree @375 (never asserted): #social-btn=${shell.hasSocialBtn} #social-overlay=${shell.hasSocialOverlay} | document.scrollWidth canvas-open=${shell.canvasOpen} canvas-closed=${shell.canvasClosed} canvas-closed+sidebar-collapsed=${shell.canvasClosedSidebarCollapsed}`);
  await mctx.close();
}

// ── API (opt-in): the REAL endpoints on an isolated instance ────────────────
async function apiSuite() {
  if (!API_BASE) {
    suspended('W10②/③ + W14 real-backend leg', 'SOCIAL_API_BASE unset — the mock legs above prove the CONSUMER contract only; the server side (secret file, rw-------, config round-trip) is unproven here and must be run against an isolated instance.');
    return;
  }
  const H = { 'Content-Type': 'application/json', Authorization: `Bearer ${API_TOKEN}` };
  const before = await fetch(`${API_BASE}/api/social/channels`, { headers: H });
  check('API GET /api/social/channels is authorized + shaped',
    before.ok, `status=${before.status}`);
  const beforeBody = await before.text();
  check('API config surface carries no credential content',
    !/PLAINTEXT-API-MARKER/.test(beforeBody), `bytes=${beforeBody.length}`);

  const post = await fetch(`${API_BASE}/api/social/channels/telegram`, {
    method: 'POST', headers: H,
    body: JSON.stringify({ enabled: true, fields: { bot_token: 'PLAINTEXT-API-MARKER-000000', chat_id: '-1001234567890' } }),
  });
  const postBody = await post.text();
  check('API POST stores the credential and answers a probe triple',
    post.ok && /"exists":true/.test(postBody) && /"modeOk":true/.test(postBody) && !/PLAINTEXT-API-MARKER/.test(postBody),
    `status=${post.status} body=${postBody.slice(0, 200)}`);

  const after = await fetch(`${API_BASE}/api/social/channels`, { headers: H });
  const afterBody = await after.text();
  check('API GET after the write: only a PATH, never the credential',
    !/PLAINTEXT-API-MARKER/.test(afterBody) && afterBody.includes('/secrets/social-telegram-bot-token'),
    `containsPlaintext=${/PLAINTEXT-API-MARKER/.test(afterBody)}`);

  const probe = await fetch(`${API_BASE}/api/social/probe?channel=telegram`, { headers: H });
  const probeBody = await probe.text();
  check('API probe is a mechanical triple, no content',
    probe.ok && /"adapterRegistered":false/.test(probeBody) && !/PLAINTEXT-API-MARKER/.test(probeBody),
    `status=${probe.status} body=${probeBody.slice(0, 200)}`);

  const unknown = await fetch(`${API_BASE}/api/social/channels/nope`, {
    method: 'POST', headers: H, body: JSON.stringify({ enabled: false, fields: {} }),
  });
  check('API unknown channel ⇒ 400 unknown_channel', unknown.status === 400, `status=${unknown.status}`);
  const badField = await fetch(`${API_BASE}/api/social/channels/wechat`, {
    method: 'POST', headers: H, body: JSON.stringify({ enabled: false, fields: { app_id: 'nope' } }),
  });
  check('API pattern violation ⇒ 400 invalid_field', badField.status === 400, `status=${badField.status}`);
  const unauth = await fetch(`${API_BASE}/api/social/channels`);
  check('API without a token ⇒ 403', unauth.status === 403, `status=${unauth.status}`);
}

// ── main ───────────────────────────────────────────────────────────────────
console.log(`# social-panel.spec.mjs mode=${MODE} web=${WEB} cards=${EXPECTED_CARDS} api=${API_BASE || '(mock only)'}`);
const { server, base } = await startServer();
const browser = await chromium.launch();
try {
  if (MODE === 'BEFORE') await beforeSuite(browser, base);
  else if (MODE === 'FIXTURE') await fixtureSuite(browser, base);
  else { await afterSuite(browser, base); await apiSuite(); }
} finally {
  await browser.close();
  await new Promise((r) => server.close(r));
}
console.log(`\n# ${results.filter((r) => r.ok).length}/${results.length} checks green, failures=${failures}`);
process.exit(failures ? 1 : 0);
