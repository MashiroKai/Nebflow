// social-panel.spec.mjs — socpanel batch acceptance (author rulings 令①–④ as
// redefined by `socremote-design` n-5c95367d; anchors W1–W17 of its §E.2).
//
// SELF-CONTAINED: a static server on a random free port (127.0.0.1) + page.route
// mocks; the browser is closed in `finally`; no port is ever taken from the
// host. Zero dependency on the running gateway.
//
// FIVE MODES (all offline):
//   · AFTER (default)         — serves src/main/resources/web; every anchor this
//                               batch implements is expected GREEN.
//   · BEFORE (SOCIAL_WEB_ROOT=<baseline web/>) — serves a pre-change tree; the
//                               same probes must read RED (absence evidence).
//   · FIXTURE (SOCIAL_MUTATE=adapter-true) — serves the same tree with the
//                               definition layer's `adapterRegistered` flipped
//                               to true (in memory, byte change reported). The
//                               W7 red-proof: the SAME assertion that is 0 in
//                               the production tree must fire here.
//   · RED-FILTER (SOCIAL_MUTATE=card-filter-off) — serves the same tree with
//                               the definition layer's ONE visible-filter
//                               dropped (in memory, byte change reported). The
//                               socialhide red-proof: the SAME visible-card
//                               probes that read {feishu} in production must
//                               read every sealed card here, in array order.
//   · ILINK-RED (SOCIAL_MUTATE=ilink-reseal | ilink-fields) — the wechat-ilink
//                               batch's red proof, mutation-safe in the same
//                               sense as the two above: the served definition
//                               layer is damaged in memory and the SAME probes
//                               the production tree pins must fire. Since
//                               weixin-scanbind (2026-10-03) the production tree
//                               ships the card UNSEALED, so the seal leg is
//                               INVERTED: `ilink-reseal` re-applies the dropped
//                               seal (IL1 — which now pins the UNSEALED face —
//                               turns red: the visible face collapses back to
//                               feishu-only);
//                               `ilink-fields` drops `sidecar_url`'s pattern and
//                               flips `ilink_bot_id` to optional (IL2 turns red:
//                               the field mirror no longer matches the contract).
//                               Nothing is written to the tree.
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
// 🔴 HIDE RULING (author 2026-09-23, Part A) — W4's remote leg only: the
//    `#social-section-remote` section is withdrawn from the VISIBLE face by the
//    `hidden` attribute (plus css/social.css's companion
//    `#social-section-remote[hidden] { display: none; }` rule — required,
//    because `.social-section { display: flex }` would otherwise outrank the
//    user agent's `[hidden]` style and the attribute would be silently
//    defeated). HIDDEN, NOT DELETED: W4 therefore flips from "the section is
//    rendered" to "the section is still IN THE DOM and NOT VISIBLE", and the
//    absence assertions of W5/W15/W17 (no link, no QR, no guessed address) are
//    unchanged — they keep holding on the hidden subtree. Nothing about the
//    O1–O5 suspension changes: hiding the section does not decide it.
//
// 🔴 CHANNEL SEAL (socialhide batch, author ruling 2026-09-26, merged order
//    ③): wechat + telegram are SEALED — `hidden: true` at the CHANNEL level,
//    data retained. W4's count now reads the VISIBLE single source
//    (socialChannelCount() = 1); SH1–SH3 pin the visible set = {feishu}, the
//    sealed data face (every entry, all flags in place, feishu unflagged) and
//    the DOM absence of the sealed ids. The red leg is MUTATION-SAFE
//    (SOCIAL_MUTATE=card-filter-off): the served definition layer loses its
//    one visible filter and the SAME probes must read 3 cards. W6 (production
//    = 0 connected) and W7 (fixture flip ⇒ connected) keep their original
//    judgement; W9 / W14 / W16-B6 keep their state-machine coverage by moving
//    onto the feishu card — the only production-visible face (the state
//    machine is definition-driven and channel-agnostic; the REAL apiSuite leg
//    still POSTs telegram, proving sealed ≠ deleted on the server face too).
//
// 🔴 SOCIAL-FIX (author ruling 2026-09-28, three changes over the landed
//    panel): ① the scan QR lives INSIDE the feishu card — one view layer, the
//    `#social-scan` sub-dialog is gone; ② the manual-fill form and its two
//    entries are retired — scan-to-create is the ONLY creation path; ③ the
//    mechanical probe triples stop rendering as field names — the card shows
//    a plain-language credential state. Flipped anchors, each red-proven
//    against the pre-change tree (SOCIAL_SUITE=after + SOCIAL_WEB_ROOT):
//    W4's control face (the in-card block / [data-archive] — an ATTRIBUTE then,
//    the `.social-scan-inline` container class since the socpanel-min wave-2
//    removed that attribute), W14's fill flow
//    (replaced by the in-card scan flow SF-1), FB1/FB2 (form fields flip to
//    absence), W16 B-3/B-6 (form geometry → in-card QR geometry), SF-3
//    (retired faces stay retired). BEFORE / FIXTURE / RED-FILTER keep their
//    original judgement.
//
// 🔴 SOCPANEL-MIN (author ruling 2026-10-01): the created face's SESSION-
//    BINDINGS display block is removed — the chat→session list (contract C3)
//    and the default-session control (contract C4's rendered half) no longer
//    render. Binding BEHAVIOUR is untouched: routing, the fixed Nebula pin and
//    the boot-time convergence write-back ([[pinDefaultSessions]] →
//    [[saveDefaultSession]] PUT) all stay. Flipped anchor: SF-1c's `bindings`
//    leg, which asserted the block's PRESENCE and now asserts its ABSENCE (red
//    on the pre-change tree, where a created card always renders
//    `[data-bindings]` — see the judgement comment at the assertion).
//    New anchors, all in [[minSuite]] on a created card with a full mock
//    backend: MIN-A1/A2/A3 (the removed surface is absent from the DOM and its
//    copy from the rendered text; the vocabulary is read from the repo locale
//    tables, never hard-coded) and MIN-B1 (behaviour invariance: a backend
//    holding a NON-Nebula default session still gets converged onto the Nebula
//    id by exactly the same PUT). MIN-A* are RED on the pre-change tree;
//    MIN-B1 is expected GREEN on BOTH trees — it is the control that says the
//    removal was display-only.
//
// 🔴 SOCPANEL-MIN WAVE-3 (author ruling #442, 2026-10-01: "clear all 11, in the
//    same branch, riding the existing verify/sink program"): the 11 locale keys
//    the wave-2 minimal-face removals ORPHANED are deleted from BOTH tables —
//    `social.channels.title`, `social.credential.ok`, and the nine
//    `social.feishu.live.*` keys (title/appUnknown/fingerprint/fpMatch/
//    fpMismatch/fpUnknown/bridge/bridgeOn/bridgeOff). Same governance reading the
//    K-group applied to its own 8 direct siblings: the row that consumed the copy
//    is gone, so the key has no consumer left. Removal only — ZERO new copy
//    (AGENTS §16), 0 inserted lines in both tables.
//    New anchors: [[MIN-K2]] — ONE PER KEY (11), each asserting absence on BOTH
//    the SERVED and the REPO locale faces; plus [[MIN-K2P]] (symmetric deletion,
//    0 carrier lines per table). All RED on the pre-change tree.
//
// 🔴 WHY THE A6/A7 NEEDLES ARE NOW FROZEN LITERALS ([[RETIRED_W3_COPY]]): three
//    of the 11 deleted keys were in use AS LIVE NEEDLES by the wave-2 anchors
//    (`A6_LABEL`/`A6_UNKNOWN`/`A7_TITLE`, read as `ZH[k] || EN[k] || ''`).
//    After the deletion those reads yield `''` — and the empty string is
//    contained in EVERY string, so `text.includes(A6_UNKNOWN)` becomes `true`
//    unconditionally while `!text.includes(A7_TITLE)` becomes `false`
//    unconditionally. The anchors would not merely weaken, they would INVERT;
//    MIN2-0 is the guard that catches it. This is not a hypothetical: the
//    mutation is recorded in `.nebflow/evidence/20261001_socpanel-min/
//    mutation-vacuity-proof.txt` (reverting the three constants to the live-read
//    form turns MIN2-0 / MIN2-A6 / MIN2-A6b / MIN2-A7 red — 120/124).
//    The values were captured from the merge-base tree just before deletion and
//    are the zh-CN readings, which is what `ZH[k] || EN[k]` resolved to on every
//    leg of this suite (all of them boot with `locale: 'zh-CN'`), so the
//    judgement carried by those four anchors is unchanged.
//
// 🔴 WHY MIN-K2 ASSERTS ON THE SERVED FACE, NOT ONLY THE IMPORTS: `ZH`/`EN` are
//    imported from `REPO_WEB` — the tree this SPEC file lives in. On a RED leg
//    (new spec, pre-change served tree) the repo tables ALREADY carry the
//    deletion, so a table-only check like `!(k in ZH) && !(k in EN)` passes on
//    BOTH trees and can never go red. The served half is what gives the anchor
//    teeth; the repo half is kept because it is the deployment reading a reviewer
//    wants. MIN-K1 (wave-2) takes the same shape for the same reason.
//
//    Pending (registered, NOT changed this round): `js/socialPanel.js:111`
//    still NAMES `social.feishu.live.appUnknown` inside a comment. It is a
//    source-comment face, not rendered and not read by any check — the wave-3
//    brief explicitly leaves it alone and asks for it to be registered instead.
//    It is reported as 「注释面待清」 for a separate ruling.
//
// Run:
//   node tests/social-panel.spec.mjs
//   SOCIAL_WEB_ROOT=/tmp/nb-socpanel-baseline/web node tests/social-panel.spec.mjs
//   SOCIAL_MUTATE=adapter-true node tests/social-panel.spec.mjs
//   SOCIAL_MUTATE=card-filter-off node tests/social-panel.spec.mjs
//   SOCIAL_API_BASE=http://127.0.0.1:8155 SOCIAL_API_TOKEN=<token> node tests/social-panel.spec.mjs
//
//   🔴 RED leg for an assertion this batch FLIPPED (hide ruling 2026-09-23) —
//      run the AFTER suite against a pre-change tree, so the new assertion is
//      exercised against the tree that must make it fail:
//   SOCIAL_SUITE=after SOCIAL_WEB_ROOT=/tmp/nb-socpanel-baseline/web \
//     SOCIAL_SHOT_DIR=/tmp/nb-socpanel-hide-red node tests/social-panel.spec.mjs
//      (SOCIAL_SUITE only SELECTS which suite runs; SOCIAL_WEB_ROOT still says
//       which tree is served. Without it, SOCIAL_WEB_ROOT implies BEFORE, whose
//       suite is red-by-absence and never reaches the flipped assertions — see
//       the note on MODE below.) The socpanel-min batch's RED leg is the same
//       command with its own snapshot dir:
//   SOCIAL_SUITE=after SOCIAL_WEB_ROOT=/tmp/nb-socmin-baseline/web \
//     SOCIAL_SHOT_DIR=/tmp/nb-socmin-red node tests/social-panel.spec.mjs
//      (SF-1c's flipped leg + MIN-A1/A2/A3 must FAIL there; MIN-B1 must PASS
//       there — it is the behaviour-invariance control, not a flipped anchor.)

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
// Suite selection (SOCIAL_SUITE) is DECOUPLED from tree selection
// (SOCIAL_WEB_ROOT / SOCIAL_MUTATE). Unset = the original behaviour, unchanged:
// a baseline tree implies BEFORE, a mutated tree FIXTURE, else AFTER. The
// decoupling exists for exactly one job — the RED leg of an assertion this
// batch FLIPPED (hide ruling 2026-09-23): the AFTER suite has to run against a
// PRE-CHANGE tree, which the tree-derived rule cannot express, because
// SOCIAL_WEB_ROOT alone would silently route to the BEFORE suite (red-by-absence,
// which never even reaches the flipped assertions) and would prove nothing
// about them. SOCIAL_SUITE only SELECTS the suite; it never changes which tree
// is served (the `web=` reading on the mode line below states the tree).
const SUITE_ENV = String(process.env.SOCIAL_SUITE || '').toLowerCase();
const MODE = SUITE_ENV === 'after' || SUITE_ENV === 'before' || SUITE_ENV === 'fixture' || SUITE_ENV === 'redfilter'
  || SUITE_ENV === 'nrs' || SUITE_ENV === 'ilinkred'
  ? SUITE_ENV.toUpperCase()
  : (process.env.SOCIAL_WEB_ROOT ? 'BEFORE'
    : (process.env.SOCIAL_MUTATE === 'adapter-true' ? 'FIXTURE'
      : (process.env.SOCIAL_MUTATE === 'card-filter-off' ? 'REDFILTER'
        : (process.env.SOCIAL_MUTATE === 'ilink-reseal' || process.env.SOCIAL_MUTATE === 'ilink-fields' ? 'ILINKRED' : 'AFTER'))));
const SHOTS = process.env.SOCIAL_SHOT_DIR || '/tmp/nb-socpanel';
const API_BASE = process.env.SOCIAL_API_BASE || '';
const API_TOKEN = process.env.SOCIAL_API_TOKEN || '';

// ── Fixed references taken from THIS repo (independent of the served tree) ──
const CHANNELS_MOD = await import(pathToFileURL(join(REPO_WEB, 'js', 'socialChannels.js')).href);
// socialhide: the two §F.2 faces are pinned SEPARATELY — the sealed data set
// (every entry, hide ≠ delete) and the visible single source (count = 1).
const EXPECTED_DEFINITION_CHANNELS = CHANNELS_MOD.SOCIAL_CHANNELS.length;
const EXPECTED_CARDS = CHANNELS_MOD.socialChannelCount();
// Over the FULL set on purpose: W11 keeps proving that the sealed channels'
// name/desc locale keys are still enumerated (and W11 itself proves they
// exist in both tables) — sealing must not orphan keys. (SH2 pins the length.)
const SOCIAL_KEYS = CHANNELS_MOD.SOCIAL_CHANNELS.flatMap((c) => [c.nameKey, c.descKey]);
const ZH = (await import(pathToFileURL(join(REPO_WEB, 'js', 'locales', 'zh-CN.js')).href)).default;
const EN = (await import(pathToFileURL(join(REPO_WEB, 'js', 'locales', 'en.js')).href)).default;

/** The wechat-ilink contract — transcribed from the batch's single source of
 *  truth, NOT re-derived from the implementation. Since weixin-scanbind
 *  (2026-10-03) the card ships UNSEALED (`hidden: false`) and on the scanBind
 *  family; `pattern` / `secretName` stay `undefined` where the contract says
 *  the field carries none, so the mirror below compares the full five-tuple on
 *  every field and an added/removed/downgraded attribute fails.
 *  This constant is the ONE judgement input for both the production probe
 *  (IL1–IL3, read off the repo module) and its red proof (IL-R, read off the
 *  served mutated tree) — that is what makes "the same probe" literal. */
const ILINK_CONTRACT = {
  id: 'weixin-ilink',
  hidden: false,
  fields: [
    { key: 'bot_token', kind: 'secret', required: true, pattern: undefined, secretName: 'social-weixin-bot-token' },
    { key: 'ilink_bot_id', kind: 'text', required: true, pattern: undefined, secretName: undefined },
    { key: 'ilink_user_id', kind: 'text', required: true, pattern: undefined, secretName: undefined },
    { key: 'baseurl', kind: 'url', required: false, pattern: '^https?://', secretName: undefined },
    { key: 'sidecar_url', kind: 'url', required: false, pattern: '^https?://.*', secretName: undefined },
    { key: 'allowed_ilink_user_ids', kind: 'text', required: false, pattern: undefined, secretName: undefined },
  ],
};

/** Mirror a channel object against [[ILINK_CONTRACT]] field by field. Returns
 *  `{ok, mismatch, got, sealed}` so a failing probe reports WHICH attribute
 *  diverged instead of only that something did. `null`/`undefined` channel ⇒ a
 *  named failure (an absent card must never read as a vacuous pass). */
function ilinkMirror(ch) {
  if (!ch) return { ok: false, mismatch: ['channel absent'], got: [], sealed: false };
  const got = (ch.fields || []).map((f) => ({ key: f.key, kind: f.kind, required: f.required, pattern: f.pattern, secretName: f.secretName }));
  const want = ILINK_CONTRACT.fields;
  const mismatch = [];
  if (got.length !== want.length) mismatch.push(`fieldCount ${got.length}≠${want.length}`);
  for (let i = 0; i < Math.max(got.length, want.length); i++) {
    const g = got[i], w = want[i];
    if (!g || !w) { mismatch.push(`slot ${i} ${w ? w.key : '(extra)'} missing`); continue; }
    for (const k of ['key', 'kind', 'required', 'pattern', 'secretName']) {
      if (g[k] !== w[k]) mismatch.push(`${w.key}.${k}=${String(JSON.stringify(g[k]))}≠${String(JSON.stringify(w[k]))}`);
    }
  }
  return { ok: mismatch.length === 0, mismatch, got, sealed: ch.hidden === true };
}

/** The entry span of one channel id inside the definition-layer SOURCE — used by
 *  the IL-R mutations to damage exactly one card. Anchored on the unique
 *  `id: '<id>',` literal and closed by the entry terminator, so a mutation can
 *  never reach a neighbouring card (wechat also carries `hidden: true`, and
 *  telegram also carries `pattern: '^https?://'` — a global replace would hit
 *  the wrong entries and the red leg would prove nothing about the new card). */
function entrySpan(body, id) {
  const key = `id: '${id}',`;
  const at = body.indexOf(key);
  if (at < 0) return null;
  // The entry opens at the `  {` that precedes the id literal — `hidden: true`
  // sits ABOVE `id` in this file, so anchoring on the id alone would leave the
  // seal outside the span and the unseal mutation would silently do nothing.
  const open = body.lastIndexOf('\n  {\n', at);
  if (open < 0) return null;
  const start = open + 1;
  const close = body.indexOf('\n  },\n', at);
  if (close < 0) return null;
  return { start, end: close + '\n  },\n'.length };
}

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
  const mode = process.env.SOCIAL_MUTATE;
  if (mode === 'adapter-true') {
    if (rel !== '/js/socialChannels.js') return body;
    const hits = (body.match(/adapterRegistered: false/g) || []).length;
    mutationHits += hits;
    return body.replace(/adapterRegistered: false/g, 'adapterRegistered: true');
  }
  if (mode === 'card-filter-off') {
    // Drops the ONE visible filter ([[visibleChannels]] in the definition
    // layer) — the exact inverse of the seal: every sealed card renders again.
    if (rel !== '/js/socialChannels.js') return body;
    const hits = (body.match(/\.filter\(\(c\) => !c\.hidden\)/g) || []).length;
    mutationHits += hits;
    return body.replace(/\.filter\(\(c\) => !c\.hidden\)/g, '.slice()');
  }
  if (mode === 'ilink-reseal' || mode === 'ilink-fields') {
    // wechat-ilink red proof (polarity inverted by weixin-scanbind: the
    // production card ships UNSEALED, so the seal leg RE-APPLIES the seal).
    // Both variants damage ONLY the `weixin-ilink` entry (see [[entrySpan]]):
    // the other sealed cards carry `hidden: true` too, so an unscoped replace
    // would damage wechat/telegram as well and the reading would stop being
    // evidence about THIS batch's card.
    if (rel !== '/js/socialChannels.js') return body;
    const span = entrySpan(body, ILINK_CONTRACT.id);
    if (!span) return body;
    const entry = body.slice(span.start, span.end);
    // 🔴 The damage is applied LINE BY LINE, and only to CODE lines: the entry's
    //    own explanatory comment quotes these very literals in prose, so an
    //    unanchored string replace would rewrite the COMMENT and leave the code
    //    intact — a mutation that proves nothing while still reporting a hit.
    //    Classifying lines is robust where regex tail-anchoring was not: these
    //    code lines end in `,` and the field ones share their line with the
    //    opening `{ key: ...`.
    const CODE_PATTERN = /^(.*?), pattern: '\^https\?:\/\/',$/m;
    const CODE_BOTID = /^(.*\bkey: 'ilink_bot_id', kind: 'text', required: )true$/m;
    const isComment = (line) => {
      const t = line.trim();
      return t.startsWith('//') || t.startsWith('/*') || t.startsWith('*');
    };
    const edits = mode === 'ilink-reseal'
      ? [['hidden: false', 'hidden: true']]
      : [["pattern: '^https?://',", ''],
        ["pattern: '^https?://.*',", ''],
        ["key: 'ilink_bot_id', kind: 'text', required: true", "key: 'ilink_bot_id', kind: 'text', required: false"]];
    let hit = 0;
    const damaged = entry.split('\n').map((line) => {
      if (isComment(line)) return line;
      let out = line;
      for (const [from, to] of edits) {
        if (out.includes(from)) { out = out.split(from).join(to); hit++; }
      }
      return out;
    }).join('\n');
    mutationHits += hit;
    return body.slice(0, span.start) + damaged + body.slice(span.end);
  }
  return body;
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
  /** feishubridge: live adapter registration truth per channel id — the probe
   *  face's `adapterRegistered` value the panel reads in phase 2. */
  registered: {},
  posts: [],
  failPost: false,
  /** social-fix: the scan-bind face the in-card blocks poll (state-machine
   *  mirror of the real contract: qr_ready → polling → done|failed). A
   *  `done` reading writes the created config ONCE PER CHANNEL — the backend
   *  side-effect the real endpoint carries (weixin-scanbind: both visible
   *  scanBind cards poll the same face; each `done` writes THAT channel). */
  scan: { state: 'qr_ready', qrUrl: 'https://feishu.example/qr_connect/mock', userCode: 'MCCK-1234', failBegin: false, doneWrites: {} },
};

/** weixin-scanbind: the created-config fixture the scan-bind `done` side
 *  effect writes, per channel — the mock mirror of the real backend's
 *  persist-on-done (identifiers plaintext, the secret as a `_ref` path, the
 *  probe triple clean, the adapter registered). */
const CREATED_FIXTURE = {
  feishu: {
    fields: { app_id: 'cli_mock0123456789ab', app_secret_ref: '~/.nebflow/secrets/social-feishu-app-secret' },
    probe: { app_secret: { exists: true, modeOk: true, readable: true } },
  },
  'weixin-ilink': {
    fields: {
      ilink_bot_id: 'wxid_mock_bot',
      ilink_user_id: 'wxid_mock_user',
      bot_token_ref: '~/.nebflow/secrets/social-weixin-bot-token',
    },
    probe: { bot_token: { exists: true, modeOk: true, readable: true } },
  },
};

/** Fresh scan face (leg setup / teardown between modes). */
function resetScanFace() {
  apiState.scan = { state: 'qr_ready', qrUrl: 'https://feishu.example/qr_connect/mock', userCode: 'MCCK-1234', failBegin: false, doneWrites: {} };
}

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
    return r.fulfill({ json: { adapterRegistered: apiState.registered[id] === true, secrets: apiState.probes[id] || {} } });
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
  // social-fix: the scan-bind face (registered LAST ⇒ highest priority above
  // the catch-all; `*` never crosses `/`, so the deeper scan paths cannot be
  // shadowed by the channel POST route).
  page.route('**/api/social/channels/*/scan-bind/begin*', (r) => {
    if (r.request().method() !== 'POST') return r.fallback();
    if (apiState.scan.failBegin) return r.fulfill({ status: 503, json: { error: 'io' } });
    return r.fulfill({ json: { scanId: 'mock-scan-1' } });
  });
  page.route('**/api/social/channels/*/scan-bind/status*', (r) => {
    // weixin-scanbind: the finished-scan side effect is PER CHANNEL — the
    // polling URL names the channel, and `done` writes THAT channel's created
    // config exactly once (see CREATED_FIXTURE).
    const id = decodeURIComponent(r.request().url().split('/api/social/channels/')[1].split('/')[0]);
    if (apiState.scan.state === 'done' && !apiState.scan.doneWrites[id]) {
      const fx = CREATED_FIXTURE[id];
      if (fx) {
        apiState.scan.doneWrites[id] = true;
        apiState.channels[id] = { enabled: true, fields: { ...fx.fields } };
        apiState.probes[id] = JSON.parse(JSON.stringify(fx.probe));
        apiState.registered[id] = true;
      }
    }
    return r.fulfill({ json: { state: apiState.scan.state, qrUrl: apiState.scan.qrUrl, userCode: apiState.scan.userCode } });
  });
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

/** Wait for the in-card scan block to reach its QR face (social-fix: the QR
 *  renders INSIDE the not-created card — no sub-dialog exists any more). The
 *  canvas is the expected form; the fallback link is accepted so the anchor
 *  survives a missing vendor QR lib. */
async function waitInlineScanQr(page) {
  await page.waitForFunction(() => {
    const box = document.querySelector('.social-card[data-channel="feishu"] [data-scan-qr]');
    return !!box && !!box.querySelector('canvas, a');
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
  // (feishubridge batch added its own face below — the `feishubridge:` entries)
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
    'src/main/scala/nebflow/gateway/GatewayMain.scala',
    'src/main/scala/nebflow/gateway/PresenceRoutes.scala',   // weixin-scanbind: the begin/status routes
    'src/main/scala/nebflow/social/SocialChannels.scala',
    'src/main/scala/nebflow/social/WeixinIlinkScanBind.scala', // weixin-scanbind: the manager
    'src/test/scala/nebflow/social/WeixinIlinkScanBindSpec.scala', // weixin-scanbind spec
    'src/test/scala/nebflow/social/WeixinIlinkBridgeSpec.scala', // weixin-scanbind: the WI-F1 field contract
    'README.md',                                             // weixin-scanbind: the section update
    'src/main/scala/nebflow/social/FeishuChannel.scala',       // feishubridge: sender-id extraction
    'src/main/scala/nebflow/social/FeishuMessage.scala',       // feishubridge: senderId field
    'src/main/scala/nebflow/social/FeishuBridgePlugin.scala',  // feishubridge: the adapter
    'src/main/scala/nebflow/bridge/BridgeManager.scala',       // feishubridge: unregister/startOne/registeredNames
    'src/test/scala/nebflow/social/FeishuBridgePluginSpec.scala',       // feishubridge spec
    'src/test/scala/nebflow/social/FeishuAdapterActivationSpec.scala',  // feishubridge spec
    'src/test/scala/nebflow/social/FeishuChannelSpec.scala',  // feishubridge: senderId joined the wire shape (additive)
    'src/test/scala/nebflow/social/SocialChannelsBootSealSpec.scala', // feishu-boot-seal spec (A1–A5)
    'src/test/scala/nebflow/social/FeishuBootSealRestore.scala',       // feishu-boot-seal: the restore entry point (Test scope)
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

  // socialhide SH1 — the visible single source. Since weixin-scanbind
  // (2026-10-03) the weixin-ilink card is UNSEALED and the visible set is
  // [weixin-ilink, feishu] in definition order. Read off the REAL repo module
  // (not the served tree): the production truth itself.
  const visIds = CHANNELS_MOD.visibleChannels().map((c) => c.id);
  check('SH1 visible single source = [weixin-ilink, feishu] (socialChannelCount = 2)',
    EXPECTED_CARDS === 2 && CHANNELS_MOD.socialChannelCount() === 2
    && JSON.stringify(visIds) === JSON.stringify(['weixin-ilink', 'feishu']),
    `count=${CHANNELS_MOD.socialChannelCount()} visible=[${visIds.join(',')}]`);

  // socialhide SH2 — sealed ≠ deleted: the full data set keeps every entry, the
  // sealed flags are in place, feishu stays unflagged, and the sealed channels'
  // name/desc keys are still enumerated (W11 below proves those keys exist in
  // both locale tables — no orphans).
  // 🔴 wechat-ilink (chain-wechat-impl): the READING moved 3 → 4 entries because
  //    the batch adds one sealed card; the judgement itself is unchanged (every
  //    sealed id flagged, the visible one unflagged, keys enumerated pairwise).
  //    The set of sealed ids is pinned by NAME, not by count, so a future
  //    add/remove can never pass this check by arithmetic alone.
  const sealFlags = CHANNELS_MOD.SOCIAL_CHANNELS.map((c) => `${c.id}=${c.hidden === true ? 'hidden' : 'visible'}`);
  const sealedIds = CHANNELS_MOD.SOCIAL_CHANNELS.filter((c) => c.hidden === true).map((c) => c.id);
  check('SH2 sealed ≠ deleted: 4 entries in data, wechat+telegram flagged, feishu+weixin-ilink unflagged',
    EXPECTED_DEFINITION_CHANNELS === 4
    && CHANNELS_MOD.channelById('wechat')?.hidden === true
    && CHANNELS_MOD.channelById('weixin-ilink')?.hidden === false
    && CHANNELS_MOD.channelById('telegram')?.hidden === true
    && CHANNELS_MOD.channelById('feishu')?.hidden !== true
    && JSON.stringify(sealedIds) === JSON.stringify(['wechat', 'telegram'])
    && SOCIAL_KEYS.length === EXPECTED_DEFINITION_CHANNELS * 2,
    `entries=${EXPECTED_DEFINITION_CHANNELS} flags=[${sealFlags.join(' ')}] channelLocaleKeys=${SOCIAL_KEYS.length}`);

  // socialhide SH2b — the raw-source grep face of the acceptance: with
  // comments stripped (headers document the flag too), the CODE carries
  // exactly 3 `hidden: true` literals — the wechat / telegram channel entries
  // plus the lark region entry. The sealed-data shape on the bytes themselves.
  // 🔴 weixin-scanbind (2026-10-03): 4 → 3 — the weixin-ilink entry dropped its
  //    seal (`hidden: false` now), so the literal count drops with it.
  const srcCode = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
  const hiddenFlags = (srcCode.match(/hidden: true/g) || []).length;
  check('SH2b code-level `hidden: true` literals = 3 (wechat + telegram + lark region)',
    hiddenFlags === 3, `grep -c (comments stripped) = ${hiddenFlags}`);

  // ── wechat-ilink: the card's data face ─────────────────────────────────────
  // IL1 — since weixin-scanbind (2026-10-03) the card ships UNSEALED: the
  // visible face is [weixin-ilink, feishu] in definition order and the count
  // is 2. The seal leg lives on as the RESEAL red proof (ILINKRED below), so
  // this pin still has teeth — just with inverted polarity.
  check('IL1 weixin-ilink is unsealed: visible face = [weixin-ilink, feishu], count = 2',
    CHANNELS_MOD.channelById('weixin-ilink')?.hidden === false
    && CHANNELS_MOD.socialChannelCount() === 2
    && JSON.stringify(CHANNELS_MOD.visibleChannels().map((c) => c.id)) === JSON.stringify(['weixin-ilink', 'feishu']),
    `sealed=${CHANNELS_MOD.channelById('weixin-ilink')?.hidden} count=${CHANNELS_MOD.socialChannelCount()} visible=[${CHANNELS_MOD.visibleChannels().map((c) => c.id).join(',')}]`);

  // IL2 — the field mirror. Compares the FULL five-tuple (key / kind / required /
  // pattern / secretName) against the batch's contract constant, so a dropped
  // pattern, a flipped required flag, a renamed secret file or a reordered field
  // all fail. `context_token` must NOT appear (it is runtime state, not config).
  const ilinkCh = CHANNELS_MOD.channelById(ILINK_CONTRACT.id);
  const ilinkMr = ilinkMirror(ilinkCh);
  check('IL2 weixin-ilink fields mirror the contract 5-tuple (key/kind/required/pattern/secretName)',
    ilinkMr.ok && !(ilinkCh?.fields || []).some((f) => f.key === 'context_token'),
    `mismatch=[${ilinkMr.mismatch.join('; ')}] fields=[${(ilinkMr.got || []).map((f) => `${f.key}:${f.kind}:${f.required}:${f.pattern ?? '-'}:${f.secretName ?? '-'}`).join(' | ')}]`);

  // IL3 — the old official-account card is untouched by option (B): it keeps its
  // seal, its id and its own four fields. This is the "coexist, don't replace"
  // half of the ruling; a future edit that folds iLink into `wechat` fails here.
  const wechatCh = CHANNELS_MOD.channelById('wechat');
  const wechatFieldKeys = (wechatCh?.fields || []).map((f) => f.key);
  check('IL3 the official-account wechat card coexists untouched (sealed, 4 own fields)',
    wechatCh?.hidden === true
    && JSON.stringify(wechatFieldKeys) === JSON.stringify(['app_id', 'app_secret', 'token', 'aes_key']),
    `hidden=${wechatCh?.hidden} fields=[${wechatFieldKeys.join(',')}]`);

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
          ids: all.map((c) => c.dataset.channel),
          heads: all.map((c) => {
            const h = c.querySelector('.social-card-head');
            return h ? {
              icon: h.querySelectorAll('svg, i[data-lucide]').length,
              name: !!h.querySelector('.social-card-name')?.textContent?.trim(),
              status: !!h.querySelector('[data-status]'),
              // scanBind card (feishu, the only visible channel): the head
              // keeps icon + name + status and carries NO switch — enabled=false
              // IS the archive, so the card has no `.nb-toggle` anywhere; its
              // state control lives in the card body — social-fix 2026-09-28:
              // the in-card QR block (not-created) or the archive button
              // ([data-archive], created).
              // 🔴 RE-POINTED by socpanel-min wave-2: the in-card block is now
              // read by its CONTAINER CLASS (`.social-scan-inline`), because the
              // batch removed the `data-scan-inline` ATTRIBUTE (P4) while the
              // container and its `data-scan-qr` / `data-scan-code` /
              // `data-scan-status` children stay. This replaces the judging
              // surface, it does not add an absence probe: the created face
              // swaps the whole block out for the live block + archive, so the
              // class discriminates exactly as the attribute did.
              // Plain cards keep the head switch; sealed channels never render.
              // See js/socialPanel.js scanBindCardHTML / plainCardHTML.
              noCardToggle: !c.querySelector('.nb-toggle'),
              control: !!c.querySelector('.social-scan-inline, [data-archive]'),
            } : null;
          }),
        };
      });
      const headsOk = cards.heads.every((h) => h && h.icon > 0 && h.name && h.status && h.noCardToggle && h.control);
      check(`W4 card shape (count = visible single source) (${tag})`, cards.count === EXPECTED_CARDS && headsOk,
        `cards=${cards.count} visibleExpected=${EXPECTED_CARDS} sealedDataEntries=${EXPECTED_DEFINITION_CHANNELS} headsOk=${headsOk} heads=${JSON.stringify(cards.heads)}`);
      // socialhide SH3 — the DOM face of the seal: exactly the unsealed cards
      // render (in definition order) and the sealed ids are ABSENT, not merely
      // invisible. weixin-scanbind: weixin-ilink joined the visible face.
      check(`SH3 DOM: visible cards = [weixin-ilink, feishu], sealed ids absent (${tag})`,
        JSON.stringify(cards.ids) === JSON.stringify(['weixin-ilink', 'feishu'])
        && !cards.ids.includes('wechat') && !cards.ids.includes('telegram'),
        `ids=[${cards.ids.join(',')}]`);
      // social-fix SF-3 — the retired faces stay retired on the visible face:
      // no sub-dialog, no scan action button, no manual entries, and the
      // mechanical probe vocabulary never reaches the rendered text.
      const retired = await page.evaluate(() => {
        const m = document.getElementById('social-modal');
        const text = m ? m.innerText : '';
        return {
          subDialog: document.querySelectorAll('#social-scan').length,
          scanBtn: document.querySelectorAll('[data-scanbind]').length,
          manual: document.querySelectorAll('[data-manual], [data-manual-toggle], [data-scan-manual]').length,
          probeVocab: (text.match(/exists=|modeOk=|readable=|凭据探针|Credential probe/g) || []).length,
        };
      });
      check(`SF-3 retired faces stay retired (${tag})`,
        retired.subDialog === 0 && retired.scanBtn === 0 && retired.manual === 0 && retired.probeVocab === 0,
        JSON.stringify(retired));
      // 🔴 W4 flipped to HIDE semantics (author ruling 2026-09-23, Part A).
      //
      // JUDGEMENT — why this assertion is RED on the pre-change tree: before the
      // ruling `#social-section-remote` carried neither a `hidden` attribute nor
      // a companion rule, and its own cascade resolves `display` through
      // `.social-section { display: flex }` — which OUTRANKS the user agent's
      // `[hidden] { display: none }`. So on the pre-change tree this element
      // reads `display: flex` with a NON-ZERO box even while it is inside the
      // open modal, and the check below must FAIL there (both the display and
      // the box reading bite). That is exactly the silent-defeat the companion
      // `#social-section-remote[hidden] { display: none; }` rule in
      // css/social.css exists to prevent, so the assertion has to measure the
      // COMPUTED result and not the presence of the attribute.
      //
      // The other half of the ruling is "hidden, NOT deleted": `present === 1`
      // plus the four surviving caption ids prove the DOM subtree is intact.
      const remote = await page.evaluate(() => {
        const el = document.getElementById('social-section-remote');
        if (!el) return { present: 0 };
        const box = el.getBoundingClientRect();
        return {
          present: document.querySelectorAll('#social-section-remote').length,
          display: getComputedStyle(el).display,
          hiddenAttr: el.hasAttribute('hidden'),
          subtree: el.querySelectorAll('#social-remote-title, #social-remote-suspended, #social-remote-safety-key, #social-remote-safety-plain').length,
          boxW: Math.round(box.width),
          boxH: Math.round(box.height),
        };
      });
      check(`W4 remote section in the DOM but not visible (${tag})`,
        remote.present === 1 && remote.display === 'none'
        && remote.boxW === 0 && remote.boxH === 0 && remote.subtree === 4,
        `#social-section-remote = ${remote.present} display=${remote.display} hiddenAttr=${remote.hiddenAttr} box=${remote.boxW}x${remote.boxH} surviving captions=${remote.subtree}`);

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

  // W9 configInvalid: pattern violation and a missing referenced file.
  // (socialhide: exercised on the FEISHU card — the only production-visible
  // face; the state machine is definition-driven and channel-agnostic.)
  await withPanel(browser, base, 'light', async (page) => {
    apiState.channels = { feishu: { enabled: false, fields: { app_id: 'not-a-cli-id' } } };
    apiState.probes = {};
    await page.reload();
    await openPanel(page);
    let st = await statuses(page);
    let pill = await page.evaluate(() => {
      const c = document.querySelector('.social-card[data-channel="feishu"]');
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

    apiState.channels = { feishu: { enabled: true, fields: {
      app_id: 'cli_0123456789abcdef',
      app_secret_ref: '~/.nebflow/secrets/social-feishu-app-secret',
      verification_token_ref: '~/.nebflow/secrets/social-feishu-verification-token',
    } } };
    apiState.probes = { feishu: {
      app_secret: { exists: false, modeOk: false, readable: false },
      verification_token: { exists: true, modeOk: true, readable: true },
    } };
    await page.reload();
    await openPanel(page);
    st = await statuses(page);
    pill = await page.evaluate(() => {
      const c = document.querySelector('.social-card[data-channel="feishu"]');
      return { status: c.querySelector('.social-status-pill').dataset.status, hint: c.querySelector('.social-card-hint').textContent };
    });
    check('W9 missing referenced file ⇒ configInvalid + path in hint',
      pill.status === 'configInvalid' && pill.hint.includes('~/.nebflow/secrets/social-feishu-app-secret'),
      JSON.stringify(pill));

    // SF-1 the in-card scan flow (social-fix): the not-created card auto-begins
    // the scan INSIDE the card — QR + status + confirmation code, one view
    // layer; a finished scan flips the SAME card to its created face. The
    // retired faces (sub-dialog / scan button / manual entries) stay absent,
    // and the panel offers no "go edit the file" step anywhere.
    apiState.channels = {};
    apiState.probes = {};
    apiState.registered = {};
    apiState.posts = [];
    resetScanFace();
    await page.reload();
    await openPanel(page);
    await waitInlineScanQr(page);
    const scanFace = await page.evaluate(() => {
      const card = document.querySelector('.social-card[data-channel="feishu"]');
      const status = card.querySelector('[data-scan-status]');
      const code = card.querySelector('[data-scan-code]');
      return {
        // 🔴 RE-POINTED by socpanel-min wave-2: the `data-scan-inline` ATTRIBUTE
        //    was removed (P4); the container class it sat on stayed, so the
        //    in-card QR block is read off the class. Same judging surface, new
        //    selector — not a new absence probe.
        inline: !!card.querySelector('.social-scan-inline'),
        canvas: !!card.querySelector('[data-scan-qr] canvas'),
        statusText: status ? status.textContent : '',
        codeShown: !!code && !code.hasAttribute('hidden'),
        codeText: code ? code.textContent : '',
        noSubDialog: document.querySelectorAll('#social-scan').length,
        noScanBtn: document.querySelectorAll('[data-scanbind]').length,
        noManual: document.querySelectorAll('[data-manual], [data-manual-toggle], [data-scan-manual]').length,
        noFormFields: document.querySelectorAll('.social-card[data-channel="feishu"] [data-field]').length,
        noFileStep: !/编辑文件|手动放置|去配置|Bot Channels/.test(document.getElementById('social-modal').innerText),
      };
    });
    check('SF-1 not-created face: QR + status INSIDE the card (one view layer)',
      scanFace.inline && scanFace.canvas && scanFace.codeShown
      && scanFace.codeText.includes('MCCK-1234')
      && [ZH['social.feishu.scan.waiting'], EN['social.feishu.scan.waiting'],
          ZH['social.feishu.scan.starting'], EN['social.feishu.scan.starting']].includes(scanFace.statusText)
      && scanFace.noSubDialog === 0 && scanFace.noScanBtn === 0 && scanFace.noManual === 0
      && scanFace.noFormFields === 0,
      JSON.stringify(scanFace));
    check('SF-1b the panel offers no file step on the scan face',
      scanFace.noFileStep, `noFileStep=${scanFace.noFileStep}`);

    // the phone side confirms ⇒ the SAME card flips to its created face
    apiState.scan.state = 'done';
    await page.waitForFunction(
      () => !!document.querySelector('.social-card[data-channel="feishu"] [data-archive]'),
      undefined, { timeout: 10000 });
    const createdFace = await page.evaluate(() => {
      const card = document.querySelector('.social-card[data-channel="feishu"]');
      const hint = card.querySelector('.social-card-hint');
      return {
        pill: card.querySelector('.social-status-pill').dataset.status,
        archive: !!card.querySelector('[data-archive]'),
        // 🔴 RE-POINTED by socpanel-min wave-2: the `data-scan-inline` ATTRIBUTE
        //    was removed (P4) while the container class stayed, so "the scan
        //    block is gone from the created face" is read off the class now.
        scanInlineGone: !card.querySelector('.social-scan-inline'),
        liveBlock: !!card.querySelector('[data-live]'),
        bindings: !!card.querySelector('[data-bindings]'),
        // 🔴 socpanel-min wave-2 (A5): the happy-path credential line is gone,
        //    so this anchors ABSENCE. `hint` is '' both when the node is hidden
        //    and when its text is empty, so `hintHidden` is read alongside it —
        //    together they say the node really took the hidden branch rather
        //    than merely carrying no text.
        hint: hint && !hint.hasAttribute('hidden') ? hint.textContent : '',
        hintHidden: !!hint && hint.hasAttribute('hidden'),
        probeVocab: (document.getElementById('social-modal').innerText.match(/exists=|modeOk=|readable=|凭据探针|Credential probe/g) || []).length,
      };
    });
    // 🔴 FLIPPED by socpanel-min (author ruling 2026-10-01): this leg used to
    //    assert the bindings block's PRESENCE (`createdFace.bindings === true`).
    //    The display block is now removed, so the SAME reading asserts ABSENCE.
    //    JUDGEMENT — why this is RED on the pre-change tree: a created card
    //    always rendered `bindingsHTML(ch)`, i.e. a `[data-bindings]` element,
    //    as the last child of its created face. On the pre-change tree the
    //    reading below is therefore `bindings === true` and the check FAILS;
    //    after the change no branch of scanBindCardHTML emits it and the check
    //    PASSES. Everything else in the check is unchanged — the created face
    //    must still flip, keep its archive control, drop the scan block and
    //    still render the C1 live block.
    // 🔴 FLIPPED AGAIN by wave-2 (A5): the hint clause used to require the
    //    happy-path credential COPY; a healthy created card now says nothing, so
    //    it asserts the empty+hidden form instead. RED on both pre-change trees
    //    (wave-1's tree renders the ok copy; the merge-base tree renders it too).
    check('SF-1c scan done ⇒ the SAME card flips to created (rebuild stays in-card; bindings block absent since socpanel-min)',
      createdFace.pill === 'connected' && createdFace.archive && createdFace.scanInlineGone
      && createdFace.liveBlock && createdFace.bindings === false
      && createdFace.hint === '' && createdFace.hintHidden === true
      && createdFace.probeVocab === 0,
      JSON.stringify(createdFace));

    const afterGet = await page.evaluate(async () => {
      const r = await fetch('/api/social/channels');
      return await r.text();
    });
    check('SF-1d config surface never carries credential content',
      !/PLAINTEXT-/.test(afterGet) && afterGet.includes('app_secret_ref'),
      `hasRef=${afterGet.includes('app_secret_ref')}`);

    // park the face back on not-created for the focus / negative-control legs
    // (a connected pill would legitimately carry the banned caption 已接入).
    resetScanFace();
    apiState.channels = {};
    apiState.probes = {};
    apiState.registered = {};
    await page.reload();
    await openPanel(page);

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

  // ── feishubridge batch (2026-09-25): lark hide + allowlist slot + the
  //    runtime flip. The flip leg drives the REAL production path — the panel
  //    reads `adapterRegistered` off the probe face — so both directions below
  //    are red if the flag never reaches channelStatus.
  await withPanel(browser, base, 'light', async (page) => {
    apiState.channels = {};
    apiState.probes = {};
    apiState.registered = {};
    resetScanFace();
    await page.reload();
    await openPanel(page);

    // FB1 lark hide — FLIPPED by social-fix (2026-09-28): the region choice
    // was a manual-form field; with the form retired the face renders no form
    // select at all. The seal itself is data-level (FB1b below).
    const region = await page.evaluate(() => {
      const card = document.querySelector('.social-card[data-channel="feishu"]');
      const sel = card && card.querySelector('select[data-field="region"]');
      return { present: sel ? 1 : 0 };
    });
    check('FB1 (flipped, social-fix) the region choice has no manual-form face',
      region.present === 0,
      JSON.stringify(region));
    const larkInData = await page.evaluate(() => import('/js/socialChannels.js').then((m) => {
      const ch = m.channelById('feishu');
      const lark = (ch.regions || []).find((r) => r.key === 'lark');
      return { inData: !!lark, hidden: !!(lark && lark.hidden) };
    }));
    check('FB1b lark survives in the definition layer as hidden data (sealed ≠ deleted)',
      larkInData.inData && larkInData.hidden, JSON.stringify(larkInData));

    // FB2 — FLIPPED by social-fix (2026-09-28): the allowlist slot was a
    // manual-form field; the form is retired (scan-to-create is the only
    // creation path), so the card renders NO [data-field] controls at all.
    const allowlist = await page.evaluate(() => {
      const card = document.querySelector('.social-card[data-channel="feishu"]');
      return { fields: card ? card.querySelectorAll('[data-field]').length : -1 };
    });
    check('FB2 (flipped, social-fix) manual form retired — no [data-field] controls render',
      allowlist.fields === 0,
      JSON.stringify(allowlist));

    // FB3 runtime flip, positive direction: config complete + probes clean +
    // the probe face answering adapterRegistered=true ⇒ the feishu pill is
    // `connected` through the LIVE path (not the W7 definition fixture).
    apiState.channels = { feishu: { enabled: true, fields: {
      app_id: 'cli_0123456789abcdef',
      region: 'feishu',
      app_secret_ref: '~/.nebflow/secrets/social-feishu-app-secret',
      verification_token_ref: '~/.nebflow/secrets/social-feishu-verification-token',
    } } };
    apiState.probes = { feishu: {
      app_secret: { exists: true, modeOk: true, readable: true },
      verification_token: { exists: true, modeOk: true, readable: true },
    } };
    apiState.registered = { feishu: true };
    await page.reload();
    await openPanel(page);
    let st = await statuses(page);
    const feishu = (s) => s.find((x) => x.id === 'feishu');
    check('FB3 probe adapterRegistered=true ⇒ feishu pill reads connected (runtime flip)',
      feishu(st).status === 'connected', JSON.stringify(st));
    // ...and the negative direction: the flag going false must unlink again.
    apiState.registered = { feishu: false };
    await page.reload();
    await openPanel(page);
    st = await statuses(page);
    check('FB3b probe adapterRegistered=false ⇒ feishu pill reads configuredNotLinked',
      feishu(st).status === 'configuredNotLinked', JSON.stringify(st));
  });

  // SF-2 the probe line renders as human language (social-fix): same probe
  // face, same triples — the card shows a plain-language credential state,
  // never the mechanical field names. The W9 legs above keep the actionable
  // path hints for a contradicting ref (configInvalid); these two states
  // cover the non-invalid cards.
  await withPanel(browser, base, 'light', async (page) => {
    // ① configured + clean probe ⇒ a healthy card says NOTHING
    //    🔴 FLIPPED by socpanel-min wave-2 (A5): this leg used to require the
    //    happy-path copy ("credentials configured · permissions normal"). That
    //    line is gone — a healthy card carries no hint, and the status pill is
    //    what states the state — so the SAME reading now asserts ABSENCE.
    //    JUDGEMENT (why it is RED on both pre-change trees): neither the wave-1
    //    tree nor the merge-base tree ever renders a healthy card without that
    //    line, so `hint === ''` fails there and passes here. `hintHidden` rides
    //    along so "no text" cannot be confused with "node still on screen".
    apiState.channels = { feishu: { enabled: true, fields: {
      app_id: 'cli_0123456789abcdef',
      app_secret_ref: '~/.nebflow/secrets/social-feishu-app-secret',
    } } };
    apiState.probes = { feishu: { app_secret: { exists: true, modeOk: true, readable: true } } };
    apiState.registered = { feishu: false };
    await page.reload();
    await openPanel(page);
    const ok = await page.evaluate(() => {
      const c = document.querySelector('.social-card[data-channel="feishu"]');
      const hint = c.querySelector('.social-card-hint');
      return {
        status: c.querySelector('.social-status-pill').dataset.status,
        hint: hint && !hint.hasAttribute('hidden') ? hint.textContent : '',
        hintHidden: !!hint && hint.hasAttribute('hidden'),
        vocab: (document.getElementById('social-modal').innerText.match(/exists=|modeOk=|readable=/g) || []).length,
      };
    });
    check('SF-2① clean probe ⇒ human credential status, zero probe vocabulary (happy-path line removed by socpanel-min wave-2)',
      ok.status === 'configuredNotLinked'
      && ok.hint === '' && ok.hintHidden === true
      && ok.vocab === 0, JSON.stringify(ok));

    // ② the probe contradicts but the card holds no ref — the author's exact
    //    quoted confusion (triples answering 否 on a live card) ⇒ "missing"
    apiState.channels = {};
    apiState.probes = { feishu: { app_secret: { exists: false, modeOk: false, readable: false } } };
    await page.reload();
    await openPanel(page);
    const missing = await page.evaluate(() => {
      const c = document.querySelector('.social-card[data-channel="feishu"]');
      const hint = c.querySelector('.social-card-hint');
      return {
        status: c.querySelector('.social-status-pill').dataset.status,
        hint: hint && !hint.hasAttribute('hidden') ? hint.textContent : '',
        vocab: (document.getElementById('social-modal').innerText.match(/exists=|modeOk=|readable=/g) || []).length,
      };
    });
    check('SF-2② contradicted probe ⇒ "missing — scan again" human state',
      missing.status === 'notConfigured'
      && (missing.hint === ZH['social.credential.missing'] || missing.hint === EN['social.credential.missing'])
      && missing.vocab === 0, JSON.stringify(missing));
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
    // social-fix: the fillable state no longer exists (manual form retired);
    // the measured face is the not-created card with its in-card QR.
    await waitInlineScanQr(page);
    const geo = await page.evaluate(() => {
      const vw = window.innerWidth;
      const m = document.getElementById('social-modal').getBoundingClientRect();
      const controls = [...document.querySelectorAll('#social-modal input, #social-modal select, #social-modal button')]
        .map((el) => el.getBoundingClientRect());
      const cards = [...document.querySelectorAll('.social-card')].map((c) => ({ sw: c.scrollWidth, cw: c.clientWidth }));
      // socialhide: read on the FEISHU card (the only production-visible one;
      // the sealed telegram card no longer has a DOM face).
      const card = document.querySelector('.social-card[data-channel="feishu"]');
      const canvas = card ? card.querySelector('[data-scan-qr] canvas') : null;
      const cardBox = card ? card.getBoundingClientRect() : null;
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
        clipped: controls.filter((r) => r.width <= 0 || r.right > vw).length,
        overflowCards: cards.filter((c) => c.sw > c.cw + 1).length,
        canvasFits: !!canvas && !!cardBox && canvas.getBoundingClientRect().width <= cardBox.width + 1,
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
    check(`W16 B-3 no clipped control (${theme})`, geo.clipped === 0, `clipped=${geo.clipped}`);
    check(`W16 B-4 no card overflow (${theme})`, geo.overflowCards === 0, `overflowCards=${geo.overflowCards}`);
    check(`W16 B-6 (re-anchored, social-fix) in-card QR fits its card (${theme})`,
      geo.canvasFits, `canvasFits=${geo.canvasFits}`);
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

// ── SOCPANEL-MIN: the removed session-bindings display block ────────────────
// Author ruling 2026-10-01: the created face shows no session-bindings block —
// the chat→session list (C3) and the default-session control (C4's rendered
// half) leave the VISIBLE face while every binding BEHAVIOUR stays.
//
// Two kinds of anchor live here, and their expectations differ ON PURPOSE:
//
//   · MIN-A1/A2/A3 + MIN-K1 — ABSENCE anchors. Each is RED on the pre-change
//     tree: a created feishu card on that tree always rendered `<div class="social-
//     bindings" data-bindings="feishu">`, whose subtree carries the fixed
//     default-session control (`[data-default-session-fixed]`) and the copy of
//     the four locale keys listed in [[RETIRED_BINDINGS_COPY]]. On this tree the
//     card renders neither.
//
//   · MIN-B1 — a BEHAVIOUR-INVARIANCE control, and therefore GREEN ON BOTH
//     TREES. It is deliberately NOT an absence anchor: it proves the ruling
//     removed a DISPLAY and not a binding. The mock backend is told to hold a
//     NON-Nebula default session, and the panel's boot must converge it onto
//     the Nebula session through the pre-existing write path
//     ([[pinDefaultSessions]] → [[saveDefaultSession]] → PUT). The assertion is
//     order-insensitive (it asks whether the PUT happened at all, with the
//     Nebula id, after boot) because the exact interleaving of the boot GETs is
//     not the subject — the subject is "the convergence write still fires".
//
// 🔴 WHY THE VOCABULARY IS FROZEN RATHER THAN READ (wave-2 K-group, root ruling
//    2026-10-01 — "clear every orphan"). MIN-A3 used to read its needles off the
//    live locale tables. The K-group then DELETED the 8 keys those needles came
//    from, so `ZH[k]`/`EN[k]` are `undefined` now and the old check would have
//    degenerated into a VACUOUS one: a vocabulary of `undefined`s filters to an
//    empty needle list, which can never hit anything, which passes forever. The
//    needles are therefore frozen as literals (see [[RETIRED_BINDINGS_COPY]],
//    captured from the merge-base tree at deletion time) and the deletion itself
//    is pinned from the other side by [[RETIRED_BINDING_KEYS]] / MIN-K1, which
//    reads the SERVED locale files. The pair is what keeps MIN-A3 meaningful:
//    MIN-K1 proves the keys are gone, MIN-A3 proves their copy is not on screen.
const RETIRED_BINDINGS_COPY = [
  // zh — the sentences the removed block rendered (bindings title/empty/
  // unavailable + the default-session label/unavailable).
  '会话绑定',
  '暂无绑定 — chat 的第一条消息会自动绑定到这里。',
  '当前后端暂不提供绑定数据。',
  '新 chat 的默认会话',
  '当前后端暂不支持默认会话设置。',
  // en — the same five readings.
  'Chat bindings',
  'No chats bound yet — the first message from a chat will be bound here.',
  'Binding data is not available on this backend.',
  'Default session for new chats',
  'Default-session setting is not available on this backend.',
];

/** The 8 locale keys the wave-2 K-group deleted from BOTH tables — MIN-K1's
 *  subject, and the reason MIN-A3's needles had to be frozen. Frozen at
 *  2026-10-01 (deletion time), captured from the merge-base tree
 *  `5e8b3a932ebdc3fa8d181add39281ed67b0ffffe`. */
const RETIRED_BINDING_KEYS = [
  'social.feishu.bindings.title',
  'social.feishu.bindings.empty',
  'social.feishu.bindings.unavailable',
  'social.feishu.bindings.source.auto',
  'social.feishu.bindings.source.manual',
  'social.feishu.defaultSession.label',
  'social.feishu.defaultSession.none',
  'social.feishu.defaultSession.unavailable',
];

/** The 11 locale keys the wave-3 leg deleted from BOTH tables (author ruling
 *  `#442`, 2026-10-01 — "clear all 11 in the same branch, ride the existing
 *  verify/sink program"). Each is a WAVE-2-PRODUCED ORPHAN: the minimal-face
 *  removals took its last consumer, so nothing renders it any more — the same
 *  governance reading the K-group above applied to its own 8 direct siblings.
 *  Frozen at 2026-10-01 (deletion time), captured from the merge-base tree
 *  `002f4732a34fcbcafe2578d7162c8b44765e8579`.
 *
 *  🔴 RED-PROOF NOTE: the 11 anchors below ([[MIN-K2]]) are the ones this leg
 *     must show FAILING on the pre-change tree, so they are built on the
 *     SERVED locale bytes (as MIN-K1 is), NOT only on the imported `ZH`/`EN`
 *     tables. The reason is worth stating: `ZH`/`EN` are imported from
 *     `REPO_WEB` — the tree the SPEC lives in — so on the red leg (new spec,
 *     old served tree) the repo tables are ALREADY post-deletion and a
 *     repo-only check would pass on both trees, i.e. it could never go red.
 *     The served half is what discriminates; the repo half is asserted too so
 *     the reading names the deployment face and the source face separately. */
const RETIRED_W3_KEYS = [
  'social.channels.title',
  'social.credential.ok',
  'social.feishu.live.title',
  'social.feishu.live.appUnknown',
  'social.feishu.live.fingerprint',
  'social.feishu.live.fpMatch',
  'social.feishu.live.fpMismatch',
  'social.feishu.live.fpUnknown',
  'social.feishu.live.bridge',
  'social.feishu.live.bridgeOn',
  'social.feishu.live.bridgeOff',
];

/** The wave-3 orphan COPY, frozen for the same reason [[RETIRED_BINDINGS_COPY]]
 *  is: three of the 11 deleted keys were being used AS LIVE NEEDLES by the
 *  wave-2 anchors (A6_LABEL / A6_UNKNOWN / A7_TITLE). Leaving them as
 *  `ZH[k] || EN[k] || ''` reads would turn those three anchors into `''`
 *  comparisons — a needle that matches everything (every string contains the
 *  empty string), which passes forever. Freezing them as the literals captured
 *  at deletion time keeps MIN2-A6 / MIN2-A6b / MIN2-A7 discriminating; the
 *  non-vacuous guard MIN2-0 pins that the literals are really there.
 *  Values are the zh-CN readings — the locale every one of these legs boots
 *  with (`boot(page, { locale: 'zh-CN' })`), which is also what `ZH[k] || EN[k]`
 *  resolved to before the freeze, so the judgement is unchanged. */
const RETIRED_W3_COPY = {
  A6_LABEL: '连接的应用',
  A6_UNKNOWN: '未知 — 当前后端未上报实际连接的应用',
  A7_TITLE: '渠道配置',
};

async function minSuite(browser, base) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  const NEBULA_ID = 'sess-nebula-0001';
  const FOREIGN_ID = 'sess-somewhere-else-9';
  // The C4 read the panel performs at boot, plus the PUT it must still issue.
  // Registered AFTER boot's routes (a later, exact-path handler wins) and
  // deliberately WITHOUT `fulfill` on the GET side for anything else — the
  // catch-all keeps owning every other endpoint.
  /** @type {Array<{method: string, body: any}>} */
  const defaultSessionCalls = [];
  await boot(page, { locale: 'zh-CN' });
  await page.route('**/api/social/channels/feishu/default-session', async (r) => {
    const req = r.request();
    const body = req.postData() ? JSON.parse(req.postData() || '{}') : null;
    defaultSessionCalls.push({ method: req.method(), body });
    if (req.method() === 'PUT') return r.fulfill({ json: { ok: true } });
    // The foreign id: exactly the state the 2026-09-30 ruling says boot must
    // converge away from.
    return r.fulfill({ json: { sessionId: FOREIGN_ID, sessionName: 'Somewhere else' } });
  });
  // The session list the panel reads to identify the Nebula session: one
  // non-root session plus the root one (agentName omitted = root identity, per
  // the encoder contract socialPanel.js documents on `nebulaSession`).
  await page.route('**/api/sessions', (r) => r.fulfill({
    json: { sessions: [
      { id: FOREIGN_ID, name: 'Somewhere else', agentName: 'SomeoneElse' },
      { id: NEBULA_ID, name: 'Nebula' },
    ] },
  }));
  // A created feishu card: the ONLY face that ever carried the removed block.
  apiState.channels = { feishu: { enabled: true, fields: {
    app_id: 'cli_0123456789abcdef',
    region: 'feishu',
    app_secret_ref: '~/.nebflow/secrets/social-feishu-app-secret',
    verification_token_ref: '~/.nebflow/secrets/social-feishu-verification-token',
  } } };
  apiState.probes = { feishu: {
    app_secret: { exists: true, modeOk: true, readable: true },
    verification_token: { exists: true, modeOk: true, readable: true },
  } };
  apiState.registered = { feishu: true };
  await page.goto(base);
  await openPanel(page);

  // `openPanel` returns on `aria-busy=false`, which the panel sets BEFORE the
  // convergence write (loadAll: setBusy(false) → renderAll → maybeBeginScan →
  // pinDefaultSessions). So the PUT can still be in flight — wait for it with a
  // bounded poll instead of a bare timer, and report the deadline as a reading
  // rather than hanging the suite.
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline && !defaultSessionCalls.some((c) => c.method === 'PUT')) {
    await page.waitForTimeout(50);
  }

  const cardPresent = await page.evaluate(() => !!document.querySelector('.social-card[data-channel="feishu"] [data-archive]'));
  check('MIN-0 the leg drives the CREATED face (the only face that ever carried the block)',
    cardPresent, `created face present = ${cardPresent}`);

  // MIN-A1/A2/A3 — one scope (`#social-modal`), three readings. The scope is
  // the same one the other absence anchors of this spec use.
  const min = await page.evaluate((needles) => {
    const m = document.getElementById('social-modal');
    const text = m ? m.innerText : '';
    return {
      bindings: m.querySelectorAll('[data-bindings]').length,
      defaultSessionFixed: m.querySelectorAll('[data-default-session-fixed]').length,
      // The copy the removed block rendered. The needles are the FROZEN
      // literals from the merge-base tree (the keys they came from were deleted
      // by the K-group, so reading them off the live tables would now yield
      // `undefined` and turn this check vacuous — see the suite comment).
      copyHits: needles.filter((k) => !!k && text.includes(k)),
      needlesChecked: needles.filter(Boolean).length,
    };
  }, RETIRED_BINDINGS_COPY);
  check('MIN-A1 created face: zero [data-bindings] in #social-modal',
    min.bindings === 0,
    `[data-bindings] count = ${min.bindings} (pre-change tree renders exactly 1 on a created card ⇒ this reading is RED there)`);
  check('MIN-A2 created face: zero [data-default-session-fixed] in #social-modal',
    min.defaultSessionFixed === 0,
    `[data-default-session-fixed] count = ${min.defaultSessionFixed} (pre-change tree: the pinned control inside [data-bindings] ⇒ RED there)`);
  // 🔴 NON-VACUOUS GUARD: `needlesChecked` pins that the frozen vocabulary is
  //    really there (10 non-empty needles). Without it a truncated constant would
  //    make the check pass by having nothing to look for — the exact failure mode
  //    the K-group's deletion would have introduced had the needles kept being
  //    read off the tables.
  check('MIN-A3 created face: the bindings/default-session copy no longer renders',
    min.needlesChecked === RETIRED_BINDINGS_COPY.length && min.needlesChecked === 10 && min.copyHits.length === 0,
    `vocabulary FROZEN at deletion time (${min.needlesChecked} non-empty needles of ${RETIRED_BINDINGS_COPY.length}) — hits=[${min.copyHits.join(' | ')}] (pre-change tree renders 3 of the zh needles on a created card ⇒ this reading is RED there)`);

  // MIN-K1 (wave-2 K-group, root ruling 2026-10-01) — the deleted keys are
  // genuinely ABSENT from the SERVED tables, checked per table so a one-sided
  // deletion reports itself (W11's key-set parity would also catch it, but this
  // names the offending keys). RED on the pre-change tree: all 8 keys are present
  // in both tables there.
  const servedTables = {
    'zh-CN': await readFile(join(WEB, 'js', 'locales', 'zh-CN.js'), 'utf8'),
    en: await readFile(join(WEB, 'js', 'locales', 'en.js'), 'utf8'),
  };
  const stillPresent = [];
  for (const [tag, src] of Object.entries(servedTables)) {
    for (const key of RETIRED_BINDING_KEYS) if (src.includes(`'${key}'`)) stillPresent.push(`${tag}:${key}`);
  }
  check('MIN-K1 the 8 K-group keys are absent from BOTH served locale tables',
    stillPresent.length === 0,
    `retired=${RETIRED_BINDING_KEYS.length} keys × 2 tables — still present: [${stillPresent.join(', ')}]`);

  // MIN-K2 (wave-3, author ruling #442, 2026-10-01) — ONE ANCHOR PER KEY (11),
  // the same shape MIN-K1 uses for its 8. Each asserts the key is absent from
  // BOTH faces, so a one-sided or partial deletion names itself. Reuses MIN-K1's
  // `servedTables` reads above, so both anchors judge the same bytes:
  //   · SERVED face (`WEB` = whatever tree the leg serves) — the discriminating
  //     half, and the reason the anchors are not table-only. On this batch's RED
  //     leg the served tree is the PRE-change snapshot, so each anchor goes red
  //     exactly as it must. 🔴 The brief's literal form `!(k in ZH) && !(k in EN)`
  //     CANNOT go red: `ZH`/`EN` are imported from `REPO_WEB` — the tree the SPEC
  //     file lives in — so on a red leg (new spec, old served tree) the repo
  //     tables already carry the deletion and the reading passes vacuously on
  //     both trees. The served half is what gives the anchor teeth; the repo half
  //     is kept because it is the DEPLOYMENT reading a reviewer wants (it is the
  //     same table W11's parity check imports).
  for (const key of RETIRED_W3_KEYS) {
    const inServed = Object.entries(servedTables).filter(([, src]) => src.includes(`'${key}'`)).map(([tag]) => tag);
    const inRepo = (key in ZH) || (key in EN);
    check(`MIN-K2 ${key} — absent from both served tables and both repo tables`,
      inServed.length === 0 && !inRepo,
      `served present in: [${inServed.join(', ')}] · repo present: ${inRepo} (pre-change tree: present in zh-CN, en, ZH and EN ⇒ this reading is RED there)`);
  }

  // MIN-K2P — the deletion is SYMMETRIC. W11's parity check also catches a
  // one-sided deletion (the key sets would differ); this reports the raw CARRIER
  // LINE count per table, so the reading says "11 out of each side" rather than
  // only "the sets differ". 0/0 on this tree; 11/11 on the pre-change tree.
  const w3Counts = {};
  for (const [tag, src] of Object.entries(servedTables)) {
    w3Counts[tag] = src.split('\n').filter((l) => RETIRED_W3_KEYS.some((k) => l.includes(`'${k}'`))).length;
  }
  check('MIN-K2P the deletion is symmetric — zero carrier lines in either served table',
    w3Counts['zh-CN'] === 0 && w3Counts.en === 0,
    `carrier lines for the 11 keys: zh=${w3Counts['zh-CN']} en=${w3Counts.en} (both must be 0; pre-change tree: 11 each ⇒ RED there)`);

  // MIN-B1 — behaviour invariance, GREEN ON BOTH TREES by design (see the
  // suite comment). The mock answered the boot GET with a FOREIGN session id, so
  // the panel must have issued a PUT carrying the Nebula id.
  const puts = defaultSessionCalls.filter((c) => c.method === 'PUT');
  const converged = puts.find((c) => c.body && c.body.sessionId === NEBULA_ID);
  check('MIN-B1 behaviour held: boot still converges a NON-Nebula default session onto the Nebula id (PUT)',
    !!converged,
    `calls=[${defaultSessionCalls.map((c) => `${c.method}${c.body ? ' ' + JSON.stringify(c.body) : ''}`).join(' | ')}] — mock GET returned sessionId=${FOREIGN_ID}, session list carries the Nebula session ${NEBULA_ID}`);

  await shot(page, 'minsuite-1440-light');
  await ctx.close();
}

// ── SOCPANEL-MIN WAVE-2: the minimal-face extension (author ruling 2026-10-01) ─
// Wave-2 removed, on the created face: the fingerprint row (A1), the bridge row
// (A2), the card description line (A3), the archive's permanent hint line (A4,
// its copy moved to the unbind button's `title`), the happy-path credential line
// (A5), the application row's label + its "unknown" explainer (A6, the row is
// now CONDITIONAL on a real app), the section title (A7), and the P-group
// leftovers (P2 `.social-scan-manual`, P4 the `data-scan-inline` attribute).
//
// Every anchor here is RED on the pre-change tree (the wave-1 tip), which is the
// point of the RED leg: that tree renders `[data-fp]`, `[data-live-enabled]`, the
// description line, the permanent hint line, all THREE `.social-live-row`s, the
// `appUnknown` copy and the section title. The two legs differ only in whether
// the connection endpoint reports a real application, so the conditional row can
// be read from both sides (empty when unlinked, exactly one row when linked —
// never the middle state of three rows).
//
// The needles for the A6/A7 copy are read from the LIVE locale tables because
// the K-group did NOT delete those two keys (they are wave-2-produced orphans
// whose scope is awaiting a separate ruling); each is paired with a non-empty
// guard so a future deletion fails LOUDLY here instead of silently emptying the
// needle list — the same non-vacuous discipline MIN-A3 follows.
async function min2Suite(browser, base) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  /** The connection reading this leg's handler answers with; '' ⇒ a backend that
   *  reports no live application (the unlinked/dark reading). */
  let liveAppId = '';
  await boot(page, { locale: 'zh-CN' });
  await page.route('**/api/social/channels/feishu/connection', (r) => r.fulfill({
    json: {
      channel: 'feishu', enabled: true, adapterRegistered: true, connected: true,
      liveAppId: liveAppId || null, liveAppIdFp: 'abcdef', liveAppName: null,
      storedAppId: 'cli_9999999999999999', storedAppIdFp: '9999',
      fingerprintMatch: 'match',
    },
  }));
  apiState.channels = { feishu: { enabled: true, fields: {
    // Pattern-valid on purpose (`^cli_[0-9a-zA-Z]{16,}$`): a violating value
    // would make the card `configInvalid`, which renders the ACTIONABLE hint
    // (A5 keeps that one) and would defeat this leg's "healthy card is silent"
    // reading.
    app_id: 'cli_9999999999999999',
    app_secret_ref: '~/.nebflow/secrets/social-feishu-app-secret',
  } } };
  apiState.probes = { feishu: { app_secret: { exists: true, modeOk: true, readable: true } } };
  apiState.registered = { feishu: true };

  // Live needles for the A6/A7 copy. 🔴 FROZEN since wave-3 (author ruling #442,
  // 2026-10-01): this leg's own anchor set deleted the three keys these used to
  // be read from, and `ZH[k] || EN[k] || ''` would now resolve to `''` — a
  // needle every string contains, i.e. an anchor that passes by matching
  // nothing. The values are the literals captured at deletion time (see
  // [[RETIRED_W3_COPY]]); MIN2-0 below is the non-vacuous guard that pins them.
  // `A4_HINT` stays a live read on purpose: `social.feishu.archive.hint` was
  // NOT deleted (it has a live consumer — the unbind button's `title`), so
  // reading it off the table is still meaningful and still fails LOUDLY if a
  // future deletion orphans it.
  const A6_LABEL = RETIRED_W3_COPY.A6_LABEL;
  const A6_UNKNOWN = RETIRED_W3_COPY.A6_UNKNOWN;
  const A7_TITLE = RETIRED_W3_COPY.A7_TITLE;
  const A4_HINT = ZH['social.feishu.archive.hint'] || EN['social.feishu.archive.hint'] || '';
  check('MIN2-0 the A6/A7 needles resolved (non-vacuous guard)',
    !!(A6_LABEL && A6_UNKNOWN && A7_TITLE && A4_HINT),
    `FROZEN at deletion time: live.title=${JSON.stringify(A6_LABEL)} live.appUnknown=${JSON.stringify(A6_UNKNOWN)} channels.title=${JSON.stringify(A7_TITLE)} · live read: archive.hint=${JSON.stringify(A4_HINT)} (a truncation of the frozen block or the deletion of archive.hint fails HERE rather than silently emptying an anchor)`);

  await page.goto(base);
  await openPanel(page);

  // ── Leg 1: the connection endpoint reports NO live application ────────────
  const dark = await page.evaluate((n) => {
    const modal = document.getElementById('social-modal');
    const card = document.querySelector('.social-card[data-channel="feishu"]');
    const live = card.querySelector('[data-live]');
    const archiveBtn = card.querySelector('[data-archive]');
    const hintEl = card.querySelector('.social-card-hint');
    return {
      liveContainers: card.querySelectorAll('[data-live]').length,
      liveRows: card.querySelectorAll('[data-live] .social-live-row').length,
      liveText: live ? live.innerText.trim() : '(no container)',
      fp: card.querySelectorAll('[data-fp]').length,
      liveEnabled: card.querySelectorAll('[data-live-enabled]').length,
      liveWarn: card.querySelectorAll('.social-live-warn').length,
      desc: card.querySelectorAll('.social-card-desc').length,
      archiveHint: card.querySelectorAll('.social-archive-hint').length,
      archiveTitle: archiveBtn ? archiveBtn.getAttribute('title') : null,
      hintHidden: !!hintEl && hintEl.hasAttribute('hidden'),
      sectionTitle: document.querySelectorAll('#social-channels-title').length,
      modalText: modal ? modal.innerText : '',
    };
  }, {});
  // ⑩ container presence — SF-1c's anchor must survive the row removals.
  check('MIN2-⑩ created face: the .social-live container is still present (SF-1c anchor kept)',
    dark.liveContainers === 1,
    `[data-live] count = ${dark.liveContainers} (must be 1 — the container is SF-1c's judging surface and refreshCard's re-render target)`);
  // ⑪ row ceiling — three rows was the pre-change shape.
  check('MIN2-⑪ created face: the live block renders at most one row (three-row shape broken)',
    dark.liveRows === 0 || dark.liveRows === 1,
    `rows = ${dark.liveRows} (∈{0,1}; pre-change tree renders 3 ⇒ this reading is RED there)`);
  check('MIN2-A1 created face: the fingerprint row is gone ([data-fp] = 0)',
    dark.fp === 0 && dark.liveWarn === 0,
    `[data-fp]=${dark.fp} .social-live-warn=${dark.liveWarn} (pre-change tree: the mismatch-capable fingerprint row ⇒ RED there; a mismatch now reads off the status pill, not the DOM)`);
  check('MIN2-A2 created face: the bridge row is gone ([data-live-enabled] = 0)',
    dark.liveEnabled === 0,
    `[data-live-enabled]=${dark.liveEnabled} (pre-change tree: 1 ⇒ RED there)`);
  check('MIN2-A6 created face: an unlinked bridge renders an EMPTY container, not the "unknown" explainer',
    dark.liveText === '' && !dark.modalText.includes(A6_UNKNOWN),
    `live text = ${JSON.stringify(dark.liveText)} — appUnknown copy on screen = ${dark.modalText.includes(A6_UNKNOWN)} (pre-change tree renders it ⇒ RED there; the row is conditional on a REAL app, never on a post-render text comparison)`);
  check('MIN2-A3 created face: the card description line is gone',
    dark.desc === 0,
    `.social-card-desc in the feishu card = ${dark.desc} (pre-change tree: 1 ⇒ RED there; plainCardHTML keeps its own — sealed-family surface, out of scope)`);
  check('MIN2-A4 created face: the permanent hint line is gone and its copy rode onto the unbind button title',
    dark.archiveHint === 0 && dark.archiveTitle === A4_HINT,
    `.social-archive-hint=${dark.archiveHint} button title=${JSON.stringify(dark.archiveTitle)} expected=${JSON.stringify(A4_HINT)} (pre-change tree: 1 permanent line, no title ⇒ RED there; same existing copy reused, none authored)`);
  check('MIN2-A5 created face: a healthy card carries no credential line (hidden branch)',
    dark.hintHidden === true,
    `hint hidden = ${dark.hintHidden} (pre-change tree renders the ok copy ⇒ RED there)`);
  check('MIN2-A7 created face: the channel-configuration section title is gone',
    dark.sectionTitle === 0 && !dark.modalText.includes(A7_TITLE),
    `#social-channels-title=${dark.sectionTitle} — channels.title copy on screen = ${dark.modalText.includes(A7_TITLE)} (pre-change tree: 1 node ⇒ RED there; the visibleChannels/cardHTML/.social-card-list chain is untouched)`);
  // 🔴 P4 (`data-scan-inline`) is anchored in Leg 3 on the NOT-created face, NOT
  //    here: the attribute belongs to that branch, so reading it on a created
  //    card would be vacuous (0 on both trees — a check that can never go red).

  // ── Leg 2: the connection endpoint reports a REAL application ─────────────
  liveAppId = 'cli_live_abcdef012345';
  await page.reload();
  await openPanel(page);
  const lit = await page.evaluate(() => {
    const card = document.querySelector('.social-card[data-channel="feishu"]');
    const rows = card.querySelectorAll('[data-live] .social-live-row');
    return {
      rows: rows.length,
      labels: card.querySelectorAll('[data-live] .social-live-label').length,
      values: card.querySelectorAll('[data-live] .social-live-value').length,
      rowText: rows[0] ? rows[0].innerText.trim() : '',
      fp: card.querySelectorAll('[data-fp]').length,
    };
  });
  check('MIN2-⑫ linked bridge: exactly one row, the application VALUE only, and no label span',
    lit.rows === 1 && lit.labels === 0 && lit.values === 1
    && lit.rowText.includes('cli_live_abcdef012345') && lit.fp === 0,
    `rows=${lit.rows} labels=${lit.labels} values=${lit.values} text=${JSON.stringify(lit.rowText)} [data-fp]=${lit.fp} (pre-change tree: 3 rows, each with a label span, plus the label copy ⇒ RED there)`);
  check('MIN2-A6b linked bridge: the removed application-label copy no longer renders',
    !lit.rowText.includes(A6_LABEL),
    `row text = ${JSON.stringify(lit.rowText)} — label copy ${JSON.stringify(A6_LABEL)} present = ${lit.rowText.includes(A6_LABEL)} (pre-change tree renders it ⇒ RED there)`);

  // ── Leg 3: the NOT-created face — where the P4 attribute actually lived ────
  // 🔴 WHY THIS LEG EXISTS (found by running the red leg): `data-scan-inline` is
  //    emitted by the NOT-created branch only. A created face therefore reports
  //    `[data-scan-inline] = 0` on the pre-change tree too, so reading it there
  //    would be VACUOUS — it could never go red. The attribute's removal is
  //    therefore anchored where it discriminates: on the scan face, where the
  //    container must remain (`SF-1` reads `.social-scan-inline`) while the
  //    attribute is gone.
  apiState.channels = {};
  apiState.registered = { feishu: false };
  await page.reload();
  await openPanel(page);
  await waitInlineScanQr(page);
  const scanFace = await page.evaluate(() => {
    const card = document.querySelector('.social-card[data-channel="feishu"]');
    return {
      container: card.querySelectorAll('.social-scan-inline').length,
      attr: card.querySelectorAll('[data-scan-inline]').length,
      qr: card.querySelectorAll('[data-scan-qr]').length,
      code: card.querySelectorAll('[data-scan-code]').length,
      status: card.querySelectorAll('[data-scan-status]').length,
    };
  });
  check('MIN2-B4 scan face: the data-scan-inline ATTRIBUTE is gone while the container and its data-scan-* children stay',
    scanFace.attr === 0 && scanFace.container === 1
    && scanFace.qr === 1 && scanFace.code === 1 && scanFace.status === 1,
    `[data-scan-inline]=${scanFace.attr} .social-scan-inline=${scanFace.container} [data-scan-qr]=${scanFace.qr} [data-scan-code]=${scanFace.code} [data-scan-status]=${scanFace.status} (pre-change tree: attribute 1 / container 1 ⇒ RED there; scanNode reads the children, so they must survive)`);

  // ── ⑧ The CSS residue: the served sheet carries none of the removed rules ──
  // 🔴 Comments are STRIPPED before scanning: this batch documents every removal
  //    in a `/* … */` note that necessarily NAMES the selector it removed, so a
  //    raw substring scan would hit its own tombstone and report a false red.
  //    The stripped text is what the browser actually applies.
  const cssText = (await readFile(join(WEB, 'css', 'social.css'), 'utf8')).replace(/\/\*[\s\S]*?\*\//g, '');
  const cssResidue = ['.social-live-label', '.social-archive-hint', '.social-scan-manual', '[data-fp=']
    .filter((sel) => cssText.includes(sel));
  check('MIN2-⑧ served social.css: zero residue of the removed rules',
    cssResidue.length === 0,
    `removed rules still present (comments stripped): [${cssResidue.join(', ')}] (pre-change tree has all four ⇒ RED there; .social-live-row/.social-live-value are KEPT — the surviving application row consumes them, and .social-card-desc is KEPT for plainCardHTML)`);

  await shot(page, 'minsuite2-1440-light');
  await ctx.close();
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

// ── NRS: the UNREADY panel must not hand the switch to the backend
//    (feishu-boot-seal batch, 2026-09-28) ────────────────────────────────────
// The frontend half of the boot-seal defect. The stored half is pinned in
// `SocialChannelsBootSealSpec` (a body that does not MENTION `enabled` must keep
// the stored value); this leg pins the OTHER producer of an unasked-for write:
// a panel whose config read FAILED renders every card from an empty map, so the
// card's switch line reads "not enabled" — on the pre-change tree that reading
// was indistinguishable from a real OFF, and a click on it went straight to
// `saveChannel`, which sent an `enabled` write derived from the failed read.
//
// Run under `SOCIAL_MUTATE=card-filter-off`: the plain-card switch only exists
// on a card that RENDERS, and production renders the feishu scan-bind card
// alone (wechat/telegram are sealed at the data layer). The mutation is this
// spec's own sanctioned inverse-of-the-seal (see `redFilterSuite`), and it is
// applied to `js/socialChannels.js` — the definition layer, NOT the file this
// batch changed — so the switch under test is the real one, merely reachable.
//
// Discriminators (red on the pre-change tree, green after):
//   NRS-1  unready ⇒ the switch is not OPERABLE (disabled; never an OFF claim);
//   NRS-2  unready ⇒ clicking it emits ZERO writes carrying `enabled`.
// Guards (expected green on both trees — stated as such, not as fix evidence):
//   NRS-3  the exactly-one-legal explicit close path still works: a READY,
//          created feishu card offers archive, and clicking it sends exactly one
//          `{enabled:false, fields:{}}` — the archive semantics are untouched;
//   NRS-4  with a ready config read the same probe finds an OPERABLE switch that
//          IS on, and a click sends exactly one explicit write — proof that
//          NRS-1/NRS-2 are not passing on a panel that never renders a switch.
async function nrsSuite(browser, base) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  apiState.channels = {};
  apiState.probes = {};
  apiState.registered = {};
  apiState.posts = [];
  resetScanFace();
  // 🔴 ONE handler owns the config GET for the whole leg, switched by `nrsReady`
  // below. It is registered AFTER boot's routes (a later, exact-path handler
  // wins), and it is deliberately NOT removed later: `page.unroute(url)` drops
  // EVERY handler on that URL — including boot's own — which would silently
  // route the "ready" leg to the `**/api/**` catch-all and hand the panel an
  // empty channel map. The leg must fail for the reason it is testing.
  let nrsReady = false;
  await boot(page);
  await page.route('**/api/social/channels', (r) => (nrsReady
    ? r.fulfill({ json: { channels: apiState.channels, adapterRegistered: false } })
    : r.fulfill({ status: 403, json: { error: 'forbidden' } })));
  await page.goto(base);
  await openPanel(page);

  const probeSwitches = () => page.evaluate(() => {
    const g = (id) => {
      const card = document.querySelector(`.social-card[data-channel="${id}"]`);
      if (!card) return { card: false, count: 0, disabled: null, on: null, archive: 0 };
      const sw = card.querySelector(`[data-toggle-channel="${id}"]`);
      return {
        card: true,
        count: card.querySelectorAll(`[data-toggle-channel="${id}"]`).length,
        disabled: sw ? /** @type {HTMLButtonElement} */ (sw).disabled : null,
        on: sw ? sw.classList.contains('on') : null,
        archive: card.querySelectorAll('[data-archive]').length,
      };
    };
    return { wechat: g('wechat'), feishu: g('feishu'), cards: document.querySelectorAll('#social-modal .social-card[data-channel]').length };
  });

  const unready = await probeSwitches();
  const clickAll = async (id) => {
    const loc = page.locator(`.social-card[data-channel="${id}"] [data-toggle-channel="${id}"]`);
    const n = await loc.count();
    for (let i = 0; i < n; i++) await loc.nth(i).click({ force: true });
    await page.waitForTimeout(300);
  };
  await clickAll('wechat');
  const unreadySaveState = await page.evaluate(() => (document.querySelector('[data-save-state="wechat"]') || {}).textContent || '');
  const unreadyToggleWrites = {
    posts: apiState.posts.length,
    enabledWrites: apiState.posts.filter((p) => !!(p.body && 'enabled' in p.body)).length,
    falseWrites: apiState.posts.filter((p) => p.body && p.body.enabled === false).length,
    saveState: unreadySaveState,
  };

  // The close shape itself: the card's SAVE action carries no override, so it
  // agrees with the rendered switch — on an unready panel the switch renders
  // OFF, hence `enabled:false` for a channel the backend may have enabled.
  const saveBtn = page.locator('.social-card[data-channel="wechat"] [data-save="wechat"]');
  if (await saveBtn.count()) await saveBtn.first().click({ force: true });
  await page.waitForTimeout(500);
  const unreadySaveWrites = {
    posts: apiState.posts.length,
    falseWrites: apiState.posts.filter((p) => p.body && p.body.enabled === false).length,
    enabledWrites: apiState.posts.filter((p) => !!(p.body && 'enabled' in p.body)).length,
    saveState: await page.evaluate(() => (document.querySelector('[data-save-state="wechat"]') || {}).textContent || ''),
  };

  check('NRS-1 unready panel: the switch is not operable (disabled — never a rendered OFF claim)',
    unready.wechat.count === 0 || unready.wechat.disabled === true,
    `wechat card=${unready.wechat.card} switches=${unready.wechat.count} disabled=${unready.wechat.disabled} on=${unready.wechat.on} | cards=${unready.cards}`);
  check('NRS-2 unready panel: the card SAVE action emits ZERO `enabled:false` writes (the close shape)',
    unreadySaveWrites.falseWrites === 0,
    `posts=${unreadySaveWrites.posts} writes carrying \`enabled\`=${unreadySaveWrites.enabledWrites} of which false=${unreadySaveWrites.falseWrites} saveState="${unreadySaveWrites.saveState}"`);
  check('NRS-2b unready panel: clicking the switch emits ZERO writes carrying `enabled`',
    unreadyToggleWrites.enabledWrites === 0,
    `posts=${unreadyToggleWrites.posts} writes carrying \`enabled\`=${unreadyToggleWrites.enabledWrites} (of which false=${unreadyToggleWrites.falseWrites}) saveState="${unreadyToggleWrites.saveState}"`);
  await shot(page, 'nrs-unready-1440-light');

  // ── ready legs (config GET answers again) ─────────────────────────────────
  apiState.channels = {
    wechat: { enabled: true, fields: { app_id: 'wx0123456789abcdef' } },
    feishu: { enabled: true, fields: { app_id: 'cli_aa32140e8af85d25', region: 'feishu' } },
  };
  apiState.probes = { wechat: { app_secret: { exists: true, modeOk: true, readable: true } } };
  apiState.posts = [];
  nrsReady = true;
  await page.reload();
  await openPanel(page);

  const ready = await probeSwitches();
  check('NRS-4 positive control: with a ready config read the SAME probe finds an OPERABLE switch that is ON',
    ready.wechat.count === 1 && ready.wechat.disabled === false && ready.wechat.on === true,
    `wechat card=${ready.wechat.card} switches=${ready.wechat.count} disabled=${ready.wechat.disabled} on=${ready.wechat.on}`);
  await clickAll('wechat');
  const readyWrite = (() => {
    const p = apiState.posts[apiState.posts.length - 1];
    return {
      posts: apiState.posts.length,
      enabled: p && p.body ? p.body.enabled : null,
      appId: p && p.body && p.body.fields ? p.body.fields.app_id : null,
      id: p ? p.id : null,
    };
  })();
  check('NRS-4b positive control: the operable switch DOES write (one explicit user-action body, fields intact)',
    readyWrite.posts === 1 && readyWrite.enabled === false && readyWrite.id === 'wechat'
      && readyWrite.appId === 'wx0123456789abcdef',
    `posts=${readyWrite.posts} id=${readyWrite.id} enabled=${readyWrite.enabled} app_id=${readyWrite.appId}`);

  // ── NRS-3: the archive face is still the one explicit close (no regression) ─
  const created = await probeSwitches();
  check('NRS-3 ready + created ⇒ the archive control is reachable (the unready leg\'s 0 has teeth)',
    created.feishu.archive === 1,
    `feishu [data-archive] controls=${created.feishu.archive}`);
  apiState.posts = [];
  await page.locator('.social-card[data-channel="feishu"] [data-archive]').first().click({ force: true });
  await page.waitForTimeout(400);
  const archWrite = (() => {
    const p = apiState.posts[apiState.posts.length - 1];
    return {
      posts: apiState.posts.length,
      enabled: p && p.body ? p.body.enabled : null,
      fieldKeys: p && p.body && p.body.fields ? Object.keys(p.body.fields).length : -1,
      id: p ? p.id : null,
    };
  })();
  check('NRS-3b archive semantics unchanged: exactly one explicit `{enabled:false, fields:{}}` write',
    archWrite.posts === 1 && archWrite.id === 'feishu' && archWrite.enabled === false && archWrite.fieldKeys === 0,
    `posts=${archWrite.posts} id=${archWrite.id} enabled=${archWrite.enabled} fieldKeys=${archWrite.fieldKeys}`);

  await ctx.close();
}

// ── FIXTURE: W7 red proof (the same probe must fire when the flag is flipped) ─
async function fixtureSuite(browser, base) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  // Every required field present + probes clean ⇒ the ONLY remaining variable is
  // the flapped flag. (socialhide: the fixture drives the FEISHU card — the
  // only production-visible face, so the flipped state is observable in DOM.)
  apiState.channels = {
    feishu: { enabled: true, fields: {
      app_id: 'cli_0123456789abcdef',
      region: 'feishu',
      app_secret_ref: '~/.nebflow/secrets/social-feishu-app-secret',
      verification_token_ref: '~/.nebflow/secrets/social-feishu-verification-token',
    } },
  };
  apiState.probes = { feishu: {
    app_secret: { exists: true, modeOk: true, readable: true },
    verification_token: { exists: true, modeOk: true, readable: true },
  } };
  await boot(page, { locale: 'zh-CN' }); // explicit: the caption assertion below is zh-only, so pin the locale instead of relying on the default
  await page.goto(base);
  await openPanel(page);
  // Kept AFTER the load on purpose: the served bytes are mutated lazily as they
  // are requested, so a check placed before `goto` would always read 0 and
  // would silently stop proving anything. The hit count is against the SEALED
  // data set (3 definition entries), not the visible count.
  check('FIXTURE mutation applied to the served definition layer',
    mutationHits === EXPECTED_DEFINITION_CHANNELS, `adapterRegistered:false → true hits = ${mutationHits} (expected ${EXPECTED_DEFINITION_CHANNELS})`);
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

// ── RED-FILTER: socialhide red proof (drop the ONE visible filter ⇒ the seal
//    opens; the SAME probes that read {feishu} in production must read all
//    every sealed card here) ───────────────────────────────────────────────────
async function redFilterSuite(browser, base) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  apiState.channels = {};
  apiState.probes = {};
  apiState.registered = {};
  await boot(page, { locale: 'zh-CN' });
  await page.goto(base);
  await openPanel(page);
  // Byte-change proof first (see fixtureSuite: kept after the load on purpose).
  check('SH-R mutation applied to the served definition layer',
    mutationHits === 1, `visible-filter .filter((c) => !c.hidden) → .slice() hits = ${mutationHits} (expected 1)`);
  // The pin probe, re-read on the mutated tree: in-page import sees the SAME
  // mutated module instance the app rendered from, so module and DOM must agree.
  const vis = await page.evaluate(() => import('/js/socialChannels.js').then((m) => ({
    rendered: [...document.querySelectorAll('#social-modal .social-card[data-channel]')].map((e) => e.dataset.channel),
    inModule: m.visibleChannels().map((c) => c.id),
    count: m.socialChannelCount(),
  })));
  // 🔴 wechat-ilink (chain-wechat-impl): the reading moved 3 → 4 because the
  //    batch adds one sealed card; the judgement is unchanged (dropping the ONE
  //    filter renders EVERY sealed entry in array order, so the production pins
  //    — visible = {feishu}, count = 1 — are red here). The expected id list is
  //    spelled out, not just counted, so a card silently joining or leaving the
  //    array fails this check rather than passing on arithmetic.
  check('SH-R red proof: dropping the filter unseals all four cards (SH1/SH3 fire)',
    vis.rendered.length === 4 && vis.rendered.join(',') === 'wechat,weixin-ilink,feishu,telegram'
    && vis.inModule.length === 4 && vis.count === 4,
    `rendered=[${vis.rendered.join(',')}] module=[${vis.inModule.join(',')}] count=${vis.count} — production pins (visible=1, {feishu}) are RED on this tree`);
  await shot(page, 'redfilter-1440-light');
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

// ── ILINKRED: wechat-ilink red proof (damage the card in the SERVED tree
//    ⇒ the SAME probes the production tree pins must fire) ───────────────────
// Mutation-safe in the same sense as the FIXTURE / RED-FILTER legs above: the
// damage happens to the bytes as they are served, in memory, and the tree on
// disk is never touched. The probes below are the served-tree twins of IL1
// (unsealed face) and IL2 (field mirror); the production readings themselves
// are taken off the repo module in afterSuite, which is why this leg reads the
// app's own in-page module instance instead — module and DOM then come from the
// SAME mutated bytes, so the reading cannot be an artefact of a stale import.
//
// TWO variants, each with its own judgement (polarity INVERTED by
// weixin-scanbind, 2026-10-03: the production card ships UNSEALED):
//   · SOCIAL_MUTATE=ilink-reseal — the dropped seal is re-applied ⇒ IL1's
//     inverse fires (the visible face collapses back to feishu-only).
//   · SOCIAL_MUTATE=ilink-fields — `baseurl` AND `sidecar_url` lose their
//     pattern (2 hits) and `ilink_bot_id` flips to optional (1 hit) ⇒ IL2's
//     inverse fires (the mirror no longer reproduces the contract five-tuple),
//     while IL1 stays green — which is exactly what proves the two probes have
//     separate teeth.
async function ilinkRedSuite(browser, base) {
  const variant = process.env.SOCIAL_MUTATE;
  const expectHits = variant === 'ilink-reseal' ? 1 : 3;
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
  const page = await ctx.newPage();
  apiState.channels = {};
  apiState.probes = {};
  apiState.registered = {};
  await boot(page, { locale: 'zh-CN' });
  await page.goto(base);
  await openPanel(page);
  // Byte-change proof first, and AFTER the load on purpose: the served bytes are
  // mutated lazily as they are requested, so a check placed before `goto` would
  // always read 0 and would silently stop proving anything.
  check(`IL-R mutation applied to the served definition layer (${variant})`,
    mutationHits === expectHits,
    `mutations applied to the weixin-ilink entry = ${mutationHits} (expected ${expectHits})`);
  const served = await page.evaluate(() => import('/js/socialChannels.js').then((m) => {
    const ch = m.channelById('weixin-ilink');
    return {
      hidden: ch ? ch.hidden : null,
      visible: m.visibleChannels().map((c) => c.id),
      count: m.socialChannelCount(),
      rendered: [...document.querySelectorAll('#social-modal .social-card[data-channel]')].map((e) => e.dataset.channel),
      fields: (ch ? ch.fields : []).map((f) => ({ key: f.key, kind: f.kind, required: f.required, pattern: f.pattern, secretName: f.secretName })),
    };
  }));
  check('IL-R the probe sees a card that IS damaged (this tree is not the production tree)',
    variant === 'ilink-reseal'
      ? served.hidden === true
      : !(served.fields.find((f) => f.key === 'baseurl') || {}).pattern
        && !(served.fields.find((f) => f.key === 'sidecar_url') || {}).pattern
        && (served.fields.find((f) => f.key === 'ilink_bot_id') || {}).required === false,
    `hidden=${served.hidden} baseurl.pattern=${String((served.fields.find((f) => f.key === 'baseurl') || {}).pattern)} sidecar_url.pattern=${String((served.fields.find((f) => f.key === 'sidecar_url') || {}).pattern)} ilink_bot_id.required=${String((served.fields.find((f) => f.key === 'ilink_bot_id') || {}).required)}`);
  if (variant === 'ilink-reseal') {
    // IL1 on the damaged tree: the seal is back, so the visible face and the
    // count BOTH collapse — the production pins (visible = [weixin-ilink,
    // feishu], count = 2) are red here, which is what gives IL1 teeth.
    check('IL-R red proof: re-applying the seal collapses the visible face (IL1 fires)',
      served.hidden === true && !served.visible.includes('weixin-ilink') && served.count === 1,
      `visible=[${served.visible.join(',')}] count=${served.count} rendered=[${served.rendered.join(',')}] — production pins (visible=[weixin-ilink, feishu], count=2) are RED on this tree`);
  } else {
    // IL2 on the damaged tree: the mirror no longer reproduces the contract.
    // The seal stays dropped, so IL1 must stay green here — the two probes are
    // independent, not two names for one reading.
    const mr = ilinkMirror({ fields: served.fields, hidden: served.hidden });
    check('IL-R red proof: a damaged field mirror no longer matches the contract (IL2 fires)',
      !mr.ok, `mismatch=[${mr.mismatch.join('; ')}] — the production IL2 reading is RED on this tree`);
    check('IL-R control: IL1\'s unsealed-face reading is UNAFFECTED by the field damage',
      served.hidden === false && served.count === 2
      && JSON.stringify(served.visible) === JSON.stringify(['weixin-ilink', 'feishu']),
      `unsealed face intact: hidden=${served.hidden} count=${served.count} visible=[${served.visible.join(',')}]`);
  }
  await shot(page, `ilinkred-${variant}-1440-light`);
  await ctx.close();
}

// ── main ───────────────────────────────────────────────────────────────────
console.log(`# social-panel.spec.mjs mode=${MODE} web=${WEB} visibleCards=${EXPECTED_CARDS} sealedEntries=${EXPECTED_DEFINITION_CHANNELS} api=${API_BASE || '(mock only)'}`);
const { server, base } = await startServer();
const browser = await chromium.launch();
try {
  if (MODE === 'BEFORE') await beforeSuite(browser, base);
  else if (MODE === 'FIXTURE') await fixtureSuite(browser, base);
  else if (MODE === 'REDFILTER') await redFilterSuite(browser, base);
  else if (MODE === 'NRS') await nrsSuite(browser, base);
  else if (MODE === 'ILINKRED') await ilinkRedSuite(browser, base);
  // socpanel-min: the removed display block's absence anchors + the behaviour
  // control. Runs LAST in the AFTER suite so its exact-path routes (`/api/
  // sessions`, `/feishu/default-session`, `/feishu/connection`) cannot leak into
  // the legs above. Wave-2's minimal-face anchors follow it for the same reason.
  else { await afterSuite(browser, base); await apiSuite(); await minSuite(browser, base); await min2Suite(browser, base); }
} finally {
  await browser.close();
  await new Promise((r) => server.close(r));
}
console.log(`\n# ${results.filter((r) => r.ok).length}/${results.length} checks green, failures=${failures}`);
process.exit(failures ? 1 : 0);
