#!/usr/bin/env node
'use strict';
/* probe-after.cjs — 统一按钮玻璃批 · 实施后机械探针（2026-11-01）。
 *
 * 与 probe.cjs 同打桩路线（page.route 磁盘真文件 + MockWebSocket 生产 boot 链，
 * 零端口零进程）。断言面 = 设计稿 §7 七条二值验收点 + 本批包消费断言：
 *   A1 收编面圆钮材质 = blur(24px) saturate(1.15) + winning rule = glass.css
 *   A2 圆钮直径桌面 40 / body.mobile-layer 48；手机图标 20
 *   A3 选中档（ref-select.active / deck-fab[aria-pressed=true]）= sapphire 12%/35% + primary 字
 *   A4 disabled opacity .35 + hover 不形变不换底
 *   A5 html.no-backdrop：圆钮 bd=none + 背景 = --glass-bg 原值实色档
 *   A6 #input-bar：纯色玻璃底（无 linear-gradient）+ 三档栈在场
 *   A7 prefers-reduced-motion：降级档生效（hover 无形变）
 *   B1 气泡禁独立 backdrop-filter：.bubble/.ask-bub 全零 blur + 半透明实底 α≈0.9x
 *   B2 面级浮件 winning rule = glass.css（input/queue/modal/ask-input）
 *   B3 墨玻璃包：.g2-pill-primary / #memory-modal-save = 墨族底 + 零 backdrop + --ig-shadow
 *   B4 F1 退役残留扫描：无 --g2-fab-* 消费、liquid-glass.css 不加载、glass.css 在场
 *   B5 #g2-edge-fade 待裁项维持现状（桌面 display:none / 手机 display:block）
 *   D9 #header 零触碰（winning rule 仍 chat.css）
 * 输出 JSON + 控制台汇总；任一 FAIL 退出码 1。
 */

const { chromium, webkit } = require('playwright');
const { writeFileSync } = require('node:fs');
const { join } = require('node:path');
const lib = require('./lib.cjs');

const argv = process.argv.slice(2);
const arg = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : dflt;
};
const ENGINE = arg('engine', 'chromium');
const THEME = arg('theme', 'both');
const OUT = arg('out', join(__dirname, '..', 'probe-after-report.json'));

const CIRCLES = [
  { key: 'source', cls: 'canvas-source-toggle' },
  { key: 'ref', cls: 'canvas-ref-select' },
  { key: 'find', cls: 'canvas-find-toggle' },
  { key: 'refresh', cls: 'canvas-refresh-toggle' },
  { key: 'deck', cls: 'deck-fab g2-fab', id: 'deck-fab' },
  { key: 'headclose', cls: 'g2-fab', id: 'canvas-head-close' },
  { key: 'askfab', cls: 'ask-fab' },
  { key: 'chrome', cls: 'mh-chrome-btn g2-fab' },
  { key: 'mfstackx', cls: 'mf-stack-x g2-fab' },
  { key: 'g2fab', cls: 'g2-fab-probe g2-fab' },
];

async function installFixtures(page) {
  await page.evaluate((circles) => {
    let host = document.getElementById('__probe_host');
    if (!host) {
      host = document.createElement('div');
      host.id = '__probe_host';
      host.style.cssText = 'position:fixed;left:8px;bottom:8px;z-index:99999;display:flex;flex-wrap:wrap;gap:8px;align-items:flex-end;';
      const backdrop = document.createElement('div');
      backdrop.style.cssText = 'position:absolute;inset:-40px;background:linear-gradient(135deg,#7c5cff,#38bdf8,#f59e0b);z-index:-1;';
      host.appendChild(backdrop);
      document.body.appendChild(host);
    }
    for (const c of circles) {
      if (document.querySelector('[data-probe="' + c.key + '"]')) continue;
      const el = document.createElement('button');
      el.type = 'button';
      el.className = c.cls;
      if (c.id) el.id = c.id;
      el.setAttribute('data-probe', c.key);
      el.innerHTML = '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="7"/></svg>';
      host.appendChild(el);
    }
    const deck = document.querySelector('[data-probe="deck"]');
    if (deck) deck.setAttribute('aria-pressed', 'true');
    const ref = document.querySelector('[data-probe="ref"]');
    if (ref) ref.classList.add('active');
    const chat = document.getElementById('chat');
    if (chat && !chat.querySelector('.bubble.user')) {
      const rowU = document.createElement('div'); rowU.className = 'row user';
      const bu = document.createElement('div'); bu.className = 'bubble user'; bu.textContent = 'probe user';
      rowU.appendChild(bu); chat.appendChild(rowU);
      const rowA = document.createElement('div'); rowA.className = 'row ai';
      const ba = document.createElement('div'); ba.className = 'bubble ai'; ba.textContent = 'probe ai';
      rowA.appendChild(ba); chat.appendChild(rowA);
    }
    let askHost = document.getElementById('__probe_ask');
    if (!askHost) {
      askHost = document.createElement('div'); askHost.id = '__probe_ask';
      askHost.style.cssText = 'position:fixed;right:8px;top:8px;z-index:99998;';
      document.body.appendChild(askHost);
    }
    if (!askHost.querySelector('.ask-bub')) {
      const b = document.createElement('div'); b.className = 'ask-bub ai'; b.textContent = 'ask bub probe';
      const bu = document.createElement('div'); bu.className = 'ask-bub user'; bu.textContent = 'ask bub user';
      const inp = document.createElement('div'); inp.className = 'ask-input';
      askHost.append(b, bu, inp);
    }
    const qb = document.getElementById('queue-bar');
    if (qb) qb.classList.add('visible');
    if (!document.getElementById('__probe_takeover')) {
      const acts = document.createElement('div'); acts.id = '__probe_takeover';
      acts.innerHTML = '<button class="g2-pill">Exit</button><button class="g2-pill g2-pill-primary">Retake</button><button id="memory-modal-save">Save</button>';
      document.body.appendChild(acts);
    }
  }, CIRCLES);
  await page.waitForTimeout(150);
}

async function collect(page) {
  return page.evaluate((circles) => {
    // per-property 级联终局：命中该元素且声明了该属性的**最后**一条规则的 stylesheet
    const winFor = (el, prop) => {
      let win = null;
      for (const sh of Array.from(document.styleSheets)) {
        let rules; try { rules = sh.cssRules; } catch { continue; }
        if (!rules) continue;
        for (const rule of rules) {
          const flat = [];
          if (rule.type === 1 && rule.selectorText) flat.push(rule);
          else if (rule.cssRules) for (const sub of rule.cssRules) if (sub.type === 1 && sub.selectorText) flat.push(sub);
          for (const rl of flat) {
            let hit = false; try { hit = el.matches(rl.selectorText); } catch {}
            if (!hit || !rl.style) continue;
            let v = ''; try { v = rl.style.getPropertyValue(prop); } catch {}
            if (v && v !== '') win = (sh.href || '(inline)').split('/').pop() + ' :: ' + rl.selectorText;
          }
        }
      }
      return win;
    };
    // 计算色参照（token 原文是 hex/rgba 原文，比较需走 computed 归一）
    let colorRef = document.getElementById('__probe_colorref');
    if (!colorRef) {
      colorRef = document.createElement('span');
      colorRef.id = '__probe_colorref';
      colorRef.style.cssText = 'position:fixed;visibility:hidden;';
      document.body.appendChild(colorRef);
    }
    const computedVarColor = (name) => {
      colorRef.style.color = 'var(' + name + ')';
      return getComputedStyle(colorRef).color;
    };
    const pick = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const c = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      // per-property winning rule（同表内末条命中该属性者的 stylesheet）：
      // 材质断言只认 backdrop-filter 的归属 —— 手机层几何/过渡块后置不影响判读
      const winBd = winFor(el, 'backdrop-filter');
      const winBg = winFor(el, 'background');
      return {
        w: Math.round(r.width * 10) / 10, h: Math.round(r.height * 10) / 10,
        bg: c.backgroundColor, bgi: c.backgroundImage,
        bd: c.backdropFilter, wbd: c.webkitBackdropFilter || '',
        border: c.borderColor, color: c.color, shadow: c.boxShadow,
        opacity: c.opacity, transform: c.transform, display: c.display,
        win: winBd || winBg,
        winBd, winBg,
      };
    };
    const out = { circles: {}, panels: {}, bubbles: {}, ink: {}, misc: {} };
    for (const c of circles) out.circles[c.key] = pick('[data-probe="' + c.key + '"]');
    out.panels.inputBar = pick('#input-bar');
    out.panels.queueBar = pick('#queue-bar.visible');
    out.panels.modalBox = pick('#modal-box');
    out.panels.askInput = pick('.ask-input');
    out.bubbles.user = pick('.bubble.user');
    out.bubbles.ai = pick('.bubble.ai');
    out.bubbles.ask = pick('.ask-bub.ai');
    out.bubbles.askUser = pick('.ask-bub.user');
    out.ink.pillPrimary = pick('.g2-pill-primary');
    out.ink.memorySave = pick('#memory-modal-save');
    out.misc.edgeFade = pick('#g2-edge-fade');
    out.misc.header = pick('#header');
    out.misc.sheets = Array.from(document.styleSheets).map((s) => (s.href || '(inline)').split('/').pop());
    const rs = getComputedStyle(document.documentElement);
    out.misc.vars = {
      fabBg: rs.getPropertyValue('--g2-fab-bg').trim(),
      inkBg: rs.getPropertyValue('--btn-ink-bg').trim(),
      igShadow: rs.getPropertyValue('--ig-shadow').trim(),
      bubUser: rs.getPropertyValue('--glass-bub-user-bg').trim(),
      bubAi: rs.getPropertyValue('--glass-bub-ai-bg').trim(),
      glassBg: rs.getPropertyValue('--glass-bg').trim(),
      primary: rs.getPropertyValue('--color-primary').trim(),
      primaryComputed: (() => {
        const el = document.getElementById('__probe_colorref');
        el.style.color = 'var(--color-primary)';
        return getComputedStyle(el).color;
      })(),
      inkBgComputed: (() => {
        const el = document.getElementById('__probe_colorref');
        el.style.color = 'var(--btn-ink-bg)';
        return getComputedStyle(el).color;
      })(),
      igShadowComputed: (() => {
        const el = document.getElementById('__probe_colorref');
        el.style.color = '';
        /* 墨玻璃面 = 顶缘高光 + 浮影两层（glass.css 主操作块同构） */
        el.style.boxShadow = 'inset 0 1px 0 var(--ig-highlight), var(--ig-shadow)';
        const v = getComputedStyle(el).boxShadow;
        el.style.boxShadow = '';
        return v;
      })(),
    };
    return out;
  }, CIRCLES);
}

function assertAll(data, mobile) {
  const checks = [];
  const add = (id, pass, detail) => checks.push({ id, pass: !!pass, detail: String(detail).slice(0, 220) });
  const glassBg = data.misc.vars.glassBg;
  const primary = data.misc.vars.primary;
  const bd24 = 'blur(24px) saturate(1.15)';
  const saph12 = 'rgba(91, 127, 191, 0.12)';
  const saph35 = 'rgba(91, 127, 191, 0.35)';

  for (const [k, v] of Object.entries(data.circles)) {
    if (!v) { add('A1/' + k, false, 'missing'); continue; }
    const bdOk = v.bd === bd24; // -webkit- 双写由 cssaudit.cjs 静态扫描承担（Chromium computed 不出前缀属性）
    add('A1/' + k, bdOk, `bd="${v.bd}" wbd="${v.wbd}"`);
    add('A1win/' + k, (v.winBd || '').startsWith('glass.css'), v.winBd);
    const desktopOnly = k === 'deck' || k === 'headclose';
    /* ask-fab 桌面档 = 44px 是设计稿 §5.3-3 待作者裁项（本批不动维持现状） */
    const wantW = k === 'askfab' ? (mobile ? 48 : 44) : (mobile ? 48 : 40);
    const sizeOk = desktopOnly && mobile ? v.display === 'none' : (v.w === wantW && v.h === wantW);
    add('A2/' + k, sizeOk, desktopOnly && mobile ? `display=${v.display} (桌面专属件手机隐藏)` : `${v.w}x${v.h} want=${wantW}`);
  }
  const ref = data.circles.ref, deck = data.circles.deck;
  const primaryC = data.misc.vars.primaryComputed;
  if (ref) add('A3/ref', ref.bg === saph12 && ref.border === saph35 && ref.color === primaryC, `bg=${ref.bg} border=${ref.border} color=${ref.color} want=${primaryC}`);
  if (deck) add('A3/deck', deck.bg === saph12 && deck.border === saph35 && deck.color === primaryC, `bg=${deck.bg} border=${deck.border} color=${deck.color}`);

  const ib = data.panels.inputBar;
  if (ib) {
    add('A6/input-bg', ib.bgi === 'none' && ib.bd === bd24, `bgi=${ib.bgi} bd=${ib.bd}`);
    add('A6/input-shadow', ib.shadow.indexOf('inset') !== -1 && (ib.shadow.match(/rgba\(/g) || []).length >= 3, ib.shadow.slice(0, 140));
    add('B2/input-win', (ib.winBd || '').startsWith('glass.css'), ib.winBd);
  }
  const qb = data.panels.queueBar, mb = data.panels.modalBox, ai = data.panels.askInput;
  if (qb) add('B2/queue-win', (qb.winBd || '').startsWith('glass.css') && qb.bd === bd24, qb.winBd);
  if (mb) add('B2/modal-win', (mb.winBd || '').startsWith('glass.css') && mb.bd === bd24, mb.winBd);
  if (ai) add('B2/askinput-win', (ai.winBd || '').startsWith('glass.css') && ai.bd === bd24, ai.winBd);

  const bu = data.bubbles.user, ba = data.bubbles.ai, bask = data.bubbles.ask, basku = data.bubbles.askUser;
  if (bu) add('B1/bubble-user', bu.bd === 'none' && bu.bgi === 'none' && /(?:149[^)]*0\.9\)|0\.5843[0-9]* 0\.9254[0-9]* 0\.4117[0-9]* \/ 0\.9\))/.test(bu.bg), `bd=${bu.bd} bg=${bu.bg}`);
  if (ba) add('B1/bubble-ai', ba.bd === 'none' && ba.bgi === 'none' && /0\.92\)$/.test(ba.bg), `bd=${ba.bd} bg=${ba.bg}`);
  /* 气泡禁独立 backdrop-filter：bd=none 且无任何活规则声明 blur（winBd=null=无声明） */
  if (bask) add('B1/ask-bub', bask.bd === 'none' && ((bask.winBd || '').indexOf('glass.css') !== -1 || bask.winBd === null), `bd=${bask.bd} winBd=${bask.winBd}`);
  if (basku) add('B1/ask-bub-user', basku.bd === 'none' && basku.bgi.indexOf('linear-gradient') !== -1, `bd=${basku.bd} bgi=${basku.bgi.slice(0, 80)}`);

  const pp = data.ink.pillPrimary, ms = data.ink.memorySave;
  if (pp) add('B3/pill-primary', pp.bd === 'none' && pp.bg === data.misc.vars.inkBgComputed && pp.shadow === data.misc.vars.igShadowComputed, `bd=${pp.bd} bg=${pp.bg} shadow=${pp.shadow.slice(0,60)} want=${data.misc.vars.igShadowComputed.slice(0,60)}`);
  if (ms) add('B3/memory-save', ms.bd === 'none' && ms.bg === data.misc.vars.inkBgComputed, `bd=${ms.bd} bg=${ms.bg} want=${data.misc.vars.inkBgComputed}`);

  add('B4/no-g2-fab-token', data.misc.vars.fabBg === '', `--g2-fab-bg="${data.misc.vars.fabBg}"`);
  add('B4/no-liquid-glass-css', !data.misc.sheets.includes('liquid-glass.css') && data.misc.sheets.includes('glass.css'), data.misc.sheets.join(','));

  const ef = data.misc.edgeFade;
  if (ef) add('B5/edge-fade', mobile ? ef.display === 'block' : ef.display === 'none', `display=${ef.display}`);
  const hd = data.misc.header;
  if (hd) add('D9/header-untouched', (hd.winBg || '').indexOf('chat.css') !== -1, hd.winBg);
  return checks;
}

function add2(checks, id, pass, detail) { checks.push({ id, pass: !!pass, detail: String(detail).slice(0, 220) }); }

(async () => {
  const report = [];
  let totalFail = 0;
  for (const engineName of lib.engines(ENGINE)) {
    const engine = engineName === 'webkit' ? webkit : chromium;
    const browser = await engine.launch();
    for (const theme of lib.themes(THEME)) {
      for (const mobile of [false, true]) {
        const page = await browser.newPage({ viewport: mobile ? { width: 390, height: 844 } : { width: 1280, height: 844 }, colorScheme: theme });
        const errors = lib.attachErrorCollectors(page);
        await page.addInitScript(lib.INIT_SCRIPT);
        await lib.makeRouter(page);
        await page.goto('http://glassshot.local/', { waitUntil: 'domcontentloaded' });
        await lib.bootSession(page);
        await lib.applyTheme(page, theme);
        if (mobile) await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 10000 });
        await installFixtures(page);
        const data = await collect(page);
        const checks = assertAll(data, mobile);
        const extra = await page.evaluate(async () => {
          const out = {};
          const fab = document.querySelector('[data-probe="source"]');
          fab.disabled = true;
          await new Promise((r) => setTimeout(r, 300)); /* transition: all .2s —— 等过渡结束再读稳态 */
          out.disabledOpacity = getComputedStyle(fab).opacity;
          fab.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
          await new Promise((r) => setTimeout(r, 260));
          const hovered = getComputedStyle(fab);
          out.hoverTransform = hovered.transform;
          out.hoverBg = hovered.backgroundColor;
          fab.disabled = false;
          document.documentElement.classList.add('no-backdrop');
          await new Promise((r) => setTimeout(r, 320)); /* 背景 transition 180ms —— 等稳态 */
          const nb = getComputedStyle(document.querySelector('[data-probe="deck"]'));
          out.noBackdropBd = nb.backdropFilter;
          out.noBackdropBg = nb.backgroundColor;
          document.documentElement.classList.remove('no-backdrop');
          return out;
        });
        add2(checks, 'A4/disabled-opacity', Math.abs(parseFloat(extra.disabledOpacity) - 0.35) < 0.01, extra.disabledOpacity);
        add2(checks, 'A4/disabled-hover-notransform', extra.hoverTransform === 'none' || extra.hoverTransform === 'matrix(1, 0, 0, 1, 0, 0)', extra.hoverTransform);
        add2(checks, 'A4/disabled-hover-bg-unchanged', extra.hoverBg === data.misc.vars.glassBg, extra.hoverBg);
        add2(checks, 'A5/no-backdrop-bd-none', extra.noBackdropBd === 'none', extra.noBackdropBd);
        add2(checks, 'A5/no-backdrop-bg-glass', extra.noBackdropBg === data.misc.vars.glassBg, `${extra.noBackdropBg} vs ${data.misc.vars.glassBg}`);
        await page.emulateMedia({ reducedMotion: 'reduce' });
        await page.waitForTimeout(80);
        const rm = await page.evaluate(() => {
          const el = document.querySelector('[data-probe="g2fab"]');
          el.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
          const cs = getComputedStyle(el);
          return { hoverTransform: cs.transform, mq: matchMedia('(prefers-reduced-motion: reduce)').matches };
        });
        add2(checks, 'A7/reduced-motion-applied', rm.mq && (rm.hoverTransform === 'none' || rm.hoverTransform === 'matrix(1, 0, 0, 1, 0, 0)'), `mq=${rm.mq} hoverT=${rm.hoverTransform}`);
        const fails = checks.filter((c) => !c.pass);
        totalFail += fails.length;
        report.push({ engine: engineName, theme, mobile, errors: errors.length, errorTexts: errors.slice(0, 10), api404: errors.api404 && errors.api404(), checks, summary: { pass: checks.length - fails.length, total: checks.length } });
        console.log(`[probe-after] ${engineName} ${theme} mobile=${mobile}: ${checks.length - fails.length}/${checks.length} pass, errors=${errors.length}`);
        if (fails.length) fails.forEach((f) => console.log(`   FAIL ${f.id}: ${f.detail}`));
        await page.close();
      }
    }
    await browser.close();
  }
  writeFileSync(OUT, JSON.stringify(report, null, 2));
  console.log(`probe-after.cjs done → ${OUT}  totalFail=${totalFail}`);
  process.exit(totalFail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
