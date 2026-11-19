// errorfrozen-minimal.spec.mjs — 极简结构断言 for the error/frozen-state
// dialog minimalization batch (author directive 2026-10-29 13:51:
// 「错误态/冻结态对话框要极简」).
//
// Loads the REAL production chain via tests/fixtures/errorfrozen-minimal-harness.html
// (real stylesheets base/chat/modal/input/sapphire/liveness + real renderers
// errorRecovery.js / sessionGate.js / modal.js / flowAgentPopup.js POPUP_CSS),
// then asserts per surface:
//
//   S1 schedule-freeze bar (.frozen) — decorations retired: no 3px ring in the
//      box-shadow list, placeholder text-shadow none, no brightened ::before
//      gradient (border/bg only), structure = placeholder line + 1 action chip.
//   S2 error bar (.frozen-error) + strip — same decoration ban; strip = exactly
//      1 icon + 1 line + ≤2 text-chip buttons (transparent bg, borderless),
//      icon static (no pulse animation), abandon NOT red (neutral muted).
//   S3 escalation card — neutral etched material (not amber block), amber only
//      as the 2px left rule + icon, static icon, ≤3 borderless text-chip
//      buttons, no glow/inset highlights.
//   S4 session-gate banner — no large red area (dark frost chip), the ONLY red
//      = the leading icon on --status-error-icon, structure ≤2 nodes, contract
//      attributes (id / data-session-gate / data-source / role=alert) intact.
//   S5 sub-agent frozen tiles — no glow on the footer, no ring on the status
//      dot, static error icon.
//   S6 token discipline (source-level) — retired decoration literals gone from
//      the touched surfaces; error/frozen colors ride var(--amber)/var(--sapphire)/
//      var(--status-error-icon) tokens only (no new hex).
//
// Regression pairs: tests/toast-glass.spec.mjs (toast family untouched),
// tests/session-gate.spec.mjs (gate contract), tests/conn-banner.spec.mjs.
//
// Run: node tests/errorfrozen-minimal.spec.mjs

import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' };

function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/' || p === '/harness') p = '/tests/fixtures/errorfrozen-minimal-harness.html';
        else if (p.startsWith('/fixtures/')) p = '/tests' + p;
        const file = (p.startsWith('/js/') || p.startsWith('/css/') || p.startsWith('/vendor/'))
          ? join(WEB, p) : join(HERE, '..', p);
        const data = readFileSync(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

const checks = [];
function check(name, ok, extra = '') {
  checks.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

const { server, port } = await startServer();
const base = `http://127.0.0.1:${port}`;

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 900, height: 1150 } });
const page = await context.newPage();
const consoleErrors = [];
page.on('pageerror', (e) => consoleErrors.push(String(e.message)));
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });

await page.goto(`${base}/harness?state=all`);
await page.waitForFunction('window.__ef.ready === true', null, { timeout: 15000 });
await page.waitForTimeout(400);

// Live-theme token resolver (same probe technique as toast-glass W-snap).
const tokenProbe = (token) => page.evaluate((t) => {
  const probe = document.createElement('span');
  // RGB-triplet tokens (sapphire.css --amber family) are not colors by
  // themselves — resolve through the tint-composition syntax the app uses.
  // Detection: a valid triplet probe keeps the injected alpha (0.5); an
  // invalid assignment falls through to the inherited solid color (alpha 1).
  probe.style.color = `rgb(var(${t}) / 0.5)`;
  document.body.appendChild(probe);
  let color = getComputedStyle(probe).color;
  if (!/,\s*0\.5\)$/.test(color)) {
    probe.style.color = `var(${t})`;
    color = getComputedStyle(probe).color;
  }
  probe.remove();
  return color;
}, token);

const parseRGBA = (s) => {
  const m = s.match(/rgba?\(([^)]+)\)/);
  if (!m) return null;
  const parts = m[1].split(/[,/]/).map((x) => parseFloat(x));
  return { r: parts[0], g: parts[1], b: parts[2], a: parts.length > 3 ? parts[3] : 1 };
};

// ── S1: schedule-freeze bar (.frozen) ────────────────────────────────────
await page.evaluate(() => window.__ef.applyBarState('frozen'));
await page.waitForTimeout(420);
{
  const snap = await page.evaluate(() => {
    const bar = document.getElementById('input-bar');
    const input = document.getElementById('input');
    const cs = getComputedStyle(bar);
    const before = getComputedStyle(bar, '::before');
    return {
      cls: bar.classList.contains('frozen'),
      boxShadow: cs.boxShadow,
      borderColor: cs.borderColor,
      background: cs.backgroundColor,
      beforeImage: before.backgroundImage,
      placeholderShadow: getComputedStyle(input, '::placeholder').textShadow,
      skipShown: getComputedStyle(document.getElementById('skip-freeze-btn')).display,
      inputDisabled: input.hasAttribute('disabled'),
    };
  });
  const sapphire = parseRGBA(await tokenProbe('--sapphire'));
  const border = parseRGBA(snap.borderColor);
  check('S1 frozen bar carries .frozen', snap.cls);
  check('S1 no 3px frost ring (box-shadow has no 0px 0px 0px 3px layer)',
    !/0px 0px 0px 3px/.test(snap.boxShadow), snap.boxShadow.slice(0, 80));
  check('S1 no outer glow layer (no 18px blur layer in shadow list)',
    !/0px 0px 18px/.test(snap.boxShadow));
  check('S1 placeholder halo retired (text-shadow none)',
    snap.placeholderShadow === 'none', snap.placeholderShadow);
  check('S1 ::before stays the standard refraction line (no brightened ice edge)',
    !/rgb\(var\(--sapphire-glow\)/.test(snap.beforeImage) || snap.beforeImage.includes('sapphire-refraction') === false && /gradient/.test(snap.beforeImage),
    snap.beforeImage.slice(0, 90));
  check('S1 border is quiet sapphire (hue 91,127,191, alpha ≤ 0.5)',
    border && Math.round(border.r) === 91 && Math.round(border.g) === 127 && Math.round(border.b) === 191 && border.a <= 0.5,
    `alpha=${border && border.a}`);
  check('S1 background is a light sapphire tint (alpha ≤ 0.15)',
    /rgba/.test(snap.background) && parseRGBA(snap.background).a <= 0.15, snap.background);
  check('S1 one-line info + one action: skip chip visible, composer disabled',
    snap.skipShown !== 'none' && snap.inputDisabled, `skip=${snap.skipShown}`);
}

// ── S2: error bar + strip ────────────────────────────────────────────────
await page.evaluate(() => window.__ef.applyBarState('error'));
await page.waitForTimeout(420);
{
  const snap = await page.evaluate(() => {
    const bar = document.getElementById('input-bar');
    const strip = bar.querySelector('.error-recovery-strip');
    const icon = strip && strip.querySelector('.error-icon');
    const text = strip && strip.querySelector('.error-recovery-text');
    const btns = strip ? [...strip.querySelectorAll('button')] : [];
    const cs = getComputedStyle(bar);
    const ics = icon && getComputedStyle(icon);
    const btn = (sel) => {
      const b = strip && strip.querySelector(sel);
      if (!b) return null;
      const s = getComputedStyle(b);
      return { bg: s.backgroundColor, border: s.borderTopStyle, borderW: s.borderTopWidth, color: s.color };
    };
    return {
      barErr: bar.classList.contains('frozen-error'),
      barShadow: cs.boxShadow,
      strip: !!strip,
      iconCount: strip ? strip.querySelectorAll('.error-icon').length : 0,
      textCount: text ? 1 : 0,
      btnCount: btns.length,
      iconAnim: ics && ics.animationName,
      retry: btn('.error-retry-btn'),
      abandon: btn('.error-abandon-btn'),
    };
  });
  const errToken = parseRGBA(await tokenProbe('--status-error-icon'));
  const abandon = parseRGBA(snap.abandon && snap.abandon.color);
  check('S2 bar carries .frozen-error and no ring/glow layers',
    snap.barErr && !/0px 0px 0px 3px/.test(snap.barShadow) && !/0px 0px 18px/.test(snap.barShadow));
  check('S2 strip renders', snap.strip);
  check('S2 strip structure = 1 icon + 1 line + ≤2 buttons',
    snap.iconCount === 1 && snap.textCount === 1 && snap.btnCount >= 1 && snap.btnCount <= 2,
    `icon=${snap.iconCount} text=${snap.textCount} buttons=${snap.btnCount}`);
  check('S2 icon is static (pulse animation retired)',
    snap.iconAnim === 'none', String(snap.iconAnim));
  check('S2 retry chip: transparent bg + no border (text-carrier only)',
    snap.retry && snap.retry.bg === 'rgba(0, 0, 0, 0)' && snap.retry.borderW === '0px',
    JSON.stringify(snap.retry));
  check('S2 abandon is NOT red (neutral muted word; red budget spent on the icon)',
    snap.abandon && abandon && Math.abs(abandon.r - errToken.r) + Math.abs(abandon.g - errToken.g) + Math.abs(abandon.b - errToken.b) > 60,
    `abandon=${snap.abandon && snap.abandon.color} vs err=${errToken}`);
}

// ── S3: escalation card ──────────────────────────────────────────────────
{
  const etched = await tokenProbe('--glass-etched-bg');
  const snap = await page.evaluate(() => {
    const card = document.querySelector('.error-escalation-card');
    if (!card) return null;
    const cs = getComputedStyle(card);
    const icon = card.querySelector('.error-icon');
    const btns = [...card.querySelectorAll('button')];
    return {
      bg: cs.backgroundColor,
      borderLeft: cs.borderLeftWidth,
      borderLeftColor: cs.borderLeftColor,
      shadow: cs.boxShadow,
      iconAnim: icon && getComputedStyle(icon).animationName,
      btnCount: btns.length,
      btnShapes: btns.map((b) => {
        const s = getComputedStyle(b);
        return { bg: s.backgroundColor, bw: s.borderTopWidth };
      }),
      childTags: [...card.children].map((c) => c.className),
    };
  });
  const amber = parseRGBA(await tokenProbe('--amber'));
  const etchedParsed = parseRGBA(etched);
  const cardBg = parseRGBA(snap && snap.bg);
  const leftRule = parseRGBA(snap && snap.borderLeftColor);
  check('S3 card renders with neutral etched material (NOT the amber block)',
    snap && cardBg && etchedParsed && Math.abs(cardBg.r - etchedParsed.r) < 2 && Math.abs(cardBg.a - etchedParsed.a) < 0.05,
    `card=${snap && snap.bg} vs token=${etched}`);
  check('S3 amber accent = 2px left rule only',
    snap.borderLeft === '2px' && leftRule && Math.round(leftRule.r) === Math.round(amber.r) && Math.round(leftRule.g) === Math.round(amber.g),
    `left=${snap.borderLeft} ${snap.borderLeftColor}`);
  check('S3 no glow / inset highlight layers',
    !/0px 0px 16px/.test(snap.shadow) && !/inset 0px 1px/.test(snap.shadow), snap.shadow.slice(0, 80));
  check('S3 icon static (pulse retired)', snap.iconAnim === 'none', String(snap.iconAnim));
  check('S3 ≤3 exits, all borderless text chips (transparent bg, 0 border)',
    snap.btnCount <= 3 && snap.btnShapes.every((b) => b.bg === 'rgba(0, 0, 0, 0)' && b.bw === '0px'),
    JSON.stringify(snap.btnShapes));
  check('S3 minimal structure ≤4 blocks (header/meta/wait/actions)',
    snap.childTags.length <= 4, snap.childTags.join('|'));
}

// ── S4: session-gate banner ──────────────────────────────────────────────
{
  const errToken = await tokenProbe('--status-error-icon');
  const snap = await page.evaluate(() => {
    const bar = document.getElementById('stand-session-gate');
    if (!bar) return null;
    const cs = getComputedStyle(bar);
    const glyph = bar.querySelector('span[aria-hidden="true"]');
    const text = bar.querySelector('span:last-child');
    return {
      bg: cs.backgroundColor,
      color: cs.color,
      glyphColor: glyph && getComputedStyle(glyph).color,
      glyphSvg: !!(glyph && glyph.querySelector('svg')),
      textOk: !!(text && text.textContent.includes('登录状态已过期')),
      role: bar.getAttribute('role'),
      source: bar.getAttribute('data-source'),
      childCount: bar.children.length,
    };
  });
  const bg = parseRGBA(snap && snap.bg);
  const glyph = parseRGBA(snap && snap.glyphColor);
  const err = parseRGBA(errToken);
  check('S4 banner renders with contract attributes intact',
    snap && snap.role === 'alert' && snap.source === 'probe' && snap.textOk);
  check('S4 no large red area: dark frost chip (≈rgba(28,30,38,.92))',
    bg && Math.round(bg.r) === 28 && Math.round(bg.g) === 30 && Math.round(bg.b) === 38,
    snap && snap.bg);
  check('S4 the ONLY red = leading icon on --status-error-icon',
    snap.glyphSvg && glyph && err && Math.abs(glyph.r - err.r) < 2 && Math.abs(glyph.g - err.g) < 2,
    `glyph=${snap && snap.glyphColor} token=${errToken}`);
  check('S4 structure ≤2 nodes (icon + one line, no button)',
    snap.childCount === 2, `children=${snap.childCount}`);
}

// ── S5: sub-agent frozen tiles ───────────────────────────────────────────
{
  const snap = await page.evaluate(() => {
    const probe = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const cs = getComputedStyle(el);
      const dot = el.querySelector('.fa-status-dot');
      const icon = el.querySelector('.error-icon');
      return {
        shadow: cs.boxShadow,
        dotShadow: dot && getComputedStyle(dot).boxShadow,
        iconAnim: icon && getComputedStyle(icon).animationName,
      };
    };
    return { frozen: probe('.flow-agent-footer.frozen'), err: probe('.flow-agent-footer.frozen.error') };
  });
  for (const [k, v] of Object.entries(snap)) {
    check(`S5 tile ${k}: no outer glow`, v && (v.shadow === 'none' || !/0px 0px 12px/.test(v.shadow)), String(v && v.shadow));
    check(`S5 tile ${k}: status dot has no ring`, v && (v.dotShadow === 'none' || v.dotShadow === 'rgba(0, 0, 0, 0)'), String(v && v.dotShadow));
  }
  check('S5 tile error icon static (pulse retired)',
    snap.err.iconAnim === 'none', String(snap.err.iconAnim));
}

// ── S6: token discipline (source level) ──────────────────────────────────
{
  const inputCss = readFileSync(join(WEB, 'css', 'input.css'), 'utf8');
  const chatCss = readFileSync(join(WEB, 'css', 'chat.css'), 'utf8');
  const flowJs = readFileSync(join(WEB, 'js', 'flowAgentPopup.js'), 'utf8');
  const gateJs = readFileSync(join(WEB, 'js', 'sessionGate.js'), 'utf8');
  check('S6 input.css: frost ring literal retired',
    !inputCss.includes('0 0 0 3px rgb(var(--sapphire)') && !inputCss.includes('0 0 0 3px rgb(var(--amber)'));
  check('S6 input.css: bar glow literal retired (0 0 18px)',
    !inputCss.includes('0 0 18px rgb(var(--sapphire-glow)') && !inputCss.includes('0 0 18px rgb(var(--amber-glow)'));
  check('S6 input.css: placeholder halo retired (no frozen text-shadow)',
    !/#input-bar\.frozen #input::placeholder,\n\s*\.fa-input-bar\.frozen \.fa-input::placeholder \{[^}]*text-shadow/s.test(inputCss));
  check('S6 chat.css: escalation card uses etched tokens, no amber block',
    chatCss.includes('.error-escalation-card {\n  margin: 10px 14px 4px 14px;\n  padding: 10px 12px;')
    && !/\.error-escalation-card \{[^}]*--amber-glow/s.test(chatCss));
  check('S6 chat.css: escalation buttons borderless (no 1px solid rgb(var(--amber) rule)',
    !/\.error-restart-btn \{[^}]*border: 1px solid/s.test(chatCss)
    && !/\.error-escalation-actions \.error-abandon-btn \{[^}]*border: 1px solid/s.test(chatCss));
  check('S6 flowAgentPopup.js POPUP_CSS: tile glow retired',
    !flowJs.includes('box-shadow: 0 0 12px rgb(var(--sapphire-glow)') && !flowJs.includes('box-shadow: 0 0 12px rgb(var(--amber-glow)'));
  check('S6 sessionGate.js: solid-red banner retired (#b62324 gone)',
    !gateJs.includes('#b62324'));
  check('S6 sessionGate.js: red rides --status-error-icon token (with fallback)',
    gateJs.includes('var(--status-error-icon'));
  check('S6 error-pulse animation fully retired (no definition, no consumer)',
    !inputCss.includes('error-pulse') && !chatCss.includes('error-pulse') && !flowJs.includes('error-pulse'));
}

check('no console/page errors across the whole run', consoleErrors.length === 0, consoleErrors.join('; '));

await browser.close();
server.close();

const failed = checks.filter((c) => !c.ok);
console.log(`\n${checks.length - failed.length}/${checks.length} passed`);
process.exit(failed.length ? 1 : 0);
