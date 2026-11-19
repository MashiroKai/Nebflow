// toggle-green-revert.spec.mjs — 开关回绿批验证（2026-11-01 作者令「toggle 重新回到绿色」）
//
// 隔离实例 :8765（隔离 home，宿主 gateway 6138 零接触）+ Bearer file token +
// console-ticket 会话引导（同 tests/settings-e2e-isolated.mjs 惯例，真实 http4s 实例）。
//
// 覆盖：
//   A. computed-style 契约：ON = brand-green 35% tint 轨道 + 50% 绿描边 + 实心绿圆钮；
//      OFF = 玻璃材质零绿；disabled = opacity 0.5（真实 toggle.js 模块渲染四参考行）；
//   B. hover 描边口径：OFF 悬停描边递进，ON 悬停保持绿描边（sapphire.css :not(.on)
//      限定——钉死「同特异性后者把 ON 绿描边洗回玻璃描边」的回归）；
//   C. 真实开关点击翻转（#toggle-llm-log → 真处理器乐观翻转，配色随态）；
//   D. 真实面落点：设置页 / 插件页（真实开关点击）/ 流图视图过滤（fail-soft）；
//   E. 双主题（prefers-color-scheme light/dark — 控制台唯一真实主题轴）×
//      双引擎（chromium + webkit）实拍；
//   F. WCAG 对比度读数（sRGB 合成数学，readings.json 落盘）；
//   G. 零 console error / pageerror（全程收集）。
//
// 用法：先起隔离实例（sbt "run --home /tmp/nb-toggle-green-batch/home --port 8765
// --no-browser"），curl /api/health 200 后：node tests/toggle-green-revert.spec.mjs
import { chromium, webkit } from '@playwright/test';
import fs from 'node:fs';

const BASE = 'http://localhost:8765';
const EV = '/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-16e74801/evidence/20261101_toggle-green-revert__delegate-nebflow-16e74801';
fs.mkdirSync(`${EV}/shots`, { recursive: true });

const GREEN = { r: 7, g: 193, b: 96 }; // --brand-green = 7 193 96（sapphire.css 单源）

const results = [];
const check = (name, ok, extra = '') => {
  results.push({ name, ok, extra: String(extra) });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`);
  if (!ok) process.exitCode = 1;
};

const parseRgbStr = (s) => {
  const m = (s || '').match(/[\d.]+/g);
  if (!m) return null;
  const [r, g, b, a = 1] = m.map(Number);
  return { r, g, b, a };
};
/** 颜色串通道是否 = brand-green（不限 α）。 */
const isGreenChannels = (s) => {
  const c = parseRgbStr(s);
  return !!c && Math.round(c.r) === GREEN.r && Math.round(c.g) === GREEN.g && Math.round(c.b) === GREEN.b;
};
const greenAt = (s, a) => {
  const c = parseRgbStr(s);
  return !!c && isGreenChannels(s) && Math.abs(c.a - a) < 0.02;
};

/** 页内探针：读取开关 computed 色 + WCAG 合成对比度读数（自包含，无注入依赖）。 */
const probeSel = (page, sel) => page.evaluate((sel) => {
  const parseRgb = (s) => {
    const m = (s || '').match(/[\d.]+/g);
    if (!m) return null;
    const [r, g, b, a = 1] = m.map(Number);
    return { r, g, b, a };
  };
  const lum = ({ r, g, b }) => {
    const f = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
  };
  const over = (fg, bg) => ({
    r: fg.r * fg.a + bg.r * bg.a * (1 - fg.a),
    g: fg.g * fg.a + bg.g * bg.a * (1 - fg.a),
    b: fg.b * fg.a + bg.b * bg.a * (1 - fg.a),
    a: fg.a + bg.a * (1 - fg.a),
  });
  const ratio = (x, y) => {
    const lx = lum(x); const ly = lum(y);
    return (Math.max(lx, ly) + 0.05) / (Math.min(lx, ly) + 0.05);
  };
  const el = document.querySelector(sel);
  if (!el) return null;
  const cs = getComputedStyle(el);
  const knob = getComputedStyle(el, '::after');
  let base = null; let hop = el; // 合成 base：向上首个不透明背景
  while (hop && hop !== document.documentElement) {
    const bg = parseRgb(getComputedStyle(hop).backgroundColor);
    if (bg && bg.a >= 0.99) { base = bg; break; }
    hop = hop.parentElement;
  }
  if (!base) base = { r: 255, g: 255, b: 255, a: 1 };
  const knobBg = parseRgb(knob.backgroundColor);
  const trackBg = parseRgb(cs.backgroundColor);
  const track = over(trackBg, base);
  const knobC = knobBg.a >= 0.99 ? knobBg : over(knobBg, track);
  return {
    trackRaw: cs.backgroundColor, knobRaw: knob.backgroundColor, borderRaw: cs.borderColor,
    opacity: cs.opacity,
    knobVsTrack: +ratio(knobC, track).toFixed(2),
    trackVsBase: +ratio(track, base).toFixed(2),
  };
}, sel);

/** 三态参考行：走真实 toggle.js 模块（toggleHTML）注入，与真实组件同一渲染契约。 */
const injectTrio = (page) => page.evaluate(async () => {
  const { toggleHTML } = await import('/js/toggle.js');
  const rows = document.querySelector('.settings-row')?.parentElement;
  if (!rows) throw new Error('settings rows container not found');
  const trio = document.createElement('div');
  trio.id = 'trio-probe';
  trio.innerHTML = [
    '<div class="settings-row"><span class="settings-label">probe ON</span>' + toggleHTML({ on: true, id: 'probe-on', label: 'probe on' }) + '</div>',
    '<div class="settings-row"><span class="settings-label">probe OFF</span>' + toggleHTML({ on: false, id: 'probe-off', label: 'probe off' }) + '</div>',
    '<div class="settings-row"><span class="settings-label">probe DIS-ON</span>' + toggleHTML({ on: true, id: 'probe-dis', label: 'probe dis', disabled: true }) + '</div>',
    '<div class="settings-row"><span class="settings-label">probe DIS-OFF</span>' + toggleHTML({ on: false, id: 'probe-dis-off', label: 'probe dis off', disabled: true }) + '</div>',
  ].join('');
  rows.appendChild(trio);
});

async function runEngine(engine, schemeList) {
  for (const scheme of schemeList) {
    const browser = await engine.launch();
    const page = await browser.newPage({ viewport: { width: 1440, height: 960 }, colorScheme: scheme });
    const eng = engine._name;
    const consoleErrors = []; const notFoundUrls = [];
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push({ text: m.text(), url: m.location()?.url || '' }); });
    page.on('pageerror', (e) => consoleErrors.push({ text: 'pageerror: ' + e.message, url: '' }));
    page.on('response', (r) => { if (r.status() === 404) notFoundUrls.push(r.url()); });

    try {
      const ticket = await mintTicket();
      await page.goto(`${BASE}/?ticket=${encodeURIComponent(ticket)}`, { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('#settings-btn', { timeout: 45000 });

      // 设置面（真实开关行）
      await page.click('#settings-btn');
      await page.waitForSelector('#toggle-llm-log', { timeout: 30000 });
      await page.waitForTimeout(600);
      await injectTrio(page);
      await page.waitForTimeout(150);

      // ── A. computed-style 契约 ─────────────────────────────────────────
      const on = await probeSel(page, '#probe-on');
      const off = await probeSel(page, '#probe-off');
      const dis = await probeSel(page, '#probe-dis');
      check(`A1 ${eng}/${scheme} ON 轨道=绿 35%`, greenAt(on.trackRaw, 0.35), on.trackRaw);
      check(`A2 ${eng}/${scheme} ON 描边=绿 50%`, greenAt(on.borderRaw, 0.5), on.borderRaw);
      check(`A3 ${eng}/${scheme} ON 圆钮=实心绿`, greenAt(on.knobRaw, 1), on.knobRaw);
      check(`A4 ${eng}/${scheme} OFF 轨道零绿`, !isGreenChannels(off.trackRaw), off.trackRaw);
      check(`A5 ${eng}/${scheme} OFF 圆钮零绿`, !isGreenChannels(off.knobRaw), off.knobRaw);
      check(`A6 ${eng}/${scheme} disabled opacity=0.5`, Math.abs(parseFloat(dis.opacity) - 0.5) < 0.01, dis.opacity);

      // ── B. hover 描边口径 ──────────────────────────────────────────────
      const offRest = off.borderRaw;
      await page.hover('#probe-off'); await page.waitForTimeout(250);
      const offHover = await probeSel(page, '#probe-off');
      check(`B1 ${eng}/${scheme} OFF hover 描边递进（≠rest）`, offHover.borderRaw !== offRest, `${offRest} → ${offHover.borderRaw}`);
      await page.hover('#probe-on'); await page.waitForTimeout(250);
      const onHover = await probeSel(page, '#probe-on');
      check(`B2 ${eng}/${scheme} ON hover 保持绿描边`, greenAt(onHover.borderRaw, 0.5), onHover.borderRaw);
      await page.mouse.move(10, 10); await page.waitForTimeout(250);

      // ── C. 真实开关点击翻转 ────────────────────────────────────────────
      const real = '#toggle-llm-log';
      const before = await page.getAttribute(real, 'aria-checked');
      await page.click(real);
      await page.waitForTimeout(400);
      const after = await page.getAttribute(real, 'aria-checked');
      check(`C1 ${eng}/${scheme} 真实开关翻转`, before !== after, `aria-checked ${before}→${after}`);
      const realProbe = await probeSel(page, real);
      const expectGreen = after === 'true';
      check(`C2 ${eng}/${scheme} 真实开关翻转后配色随态`,
        expectGreen ? greenAt(realProbe.trackRaw, 0.35) : !isGreenChannels(realProbe.trackRaw),
        realProbe.trackRaw);
      await page.click(real); await page.waitForTimeout(300); // 翻回原态

      // ── D. 截图（三态参考行 + 真实开关行 + 面板全景）───────────────────
      await page.locator('#trio-probe').screenshot({ path: `${EV}/shots/${eng}-${scheme}-trio.png` });
      await page.locator('.settings-row:has(#toggle-llm-log)').first()
        .screenshot({ path: `${EV}/shots/${eng}-${scheme}-settings-real-toggle.png` });
      await page.screenshot({ path: `${EV}/shots/${eng}-${scheme}-settings-full.png` });

      // 插件面（落点②）：探测首颗可见分发开关（详情折叠内的为 hidden 是常态），
      // 配色随 aria-checked 断言——点击翻转行为已由 C1/C2 在共享组件上证毕，
      // 插件页只验「同组件同色」落点在onia场。
      try {
        await page.keyboard.press('Escape'); // 关设置抽屉（sidebar.js:208），scrim 不再拦截活动栏
        await page.waitForTimeout(400);
        await page.click('#agents-btn');
        await page.waitForSelector('.plugins-card[data-plugin]', { timeout: 15000 });
        // 分发开关在折叠详情内（plugins-item-detail[hidden]）——走真实手风琴路径展开。
        // 插件画布会被 WS 事件重渲（活动会话在跑）把手风琴折回去 ⇒ 探针窗极窄，
        // 用「开→探→被折→再开」重试环对抗（最多 4 轮）。
        // 原子探针：展开→发现→读数在同一次 evaluate 内出（重渲风暴下三段式会被
        // 折叠撕裂）；同步 DOM click = 应用真实点击同一 handler 通路。
        const plugProbe = await (async () => {
          // 紧凑环：WS 重渲整面板重建会把 detail 折回 hidden，间隔可 <400ms ⇒
          // 「查→未中即展开」同循环紧贴执行（evaluate 往返 ~ms 级），抢重渲窗口
          for (let i = 0; i < 60; i++) {
            const probe = await page.evaluate(() => {
              const parseRgb = (s0) => { const m = (s0 || '').match(/[\d.]+/g); if (!m) return null; const [r, g, b, a = 1] = m.map(Number); return { r, g, b, a }; };
              const lum = ({ r, g, b }) => { const f = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
              const over = (fg, bg) => ({ r: fg.r * fg.a + bg.r * bg.a * (1 - fg.a), g: fg.g * fg.a + bg.g * bg.a * (1 - fg.a), b: fg.b * fg.a + bg.b * bg.a * (1 - fg.a), a: fg.a + bg.a * (1 - fg.a) });
              const ratio = (x, y) => { const lx = lum(x); const ly = lum(y); return (Math.max(lx, ly) + 0.05) / (Math.min(lx, ly) + 0.05); };
              const vis = (el) => { for (let h = el; h; h = h.parentElement) { const cs = getComputedStyle(h); if (cs.display === 'none' || cs.visibility === 'hidden') return false; } return true; };
              const t = [...document.querySelectorAll('.nb-toggle[data-plugin-dispatch]')].find(vis);
              if (!t) return null;
              const cs = getComputedStyle(t);
              const knob = getComputedStyle(t, '::after');
              let base = null; let hop = t;
              while (hop && hop !== document.documentElement) { const bg = parseRgb(getComputedStyle(hop).backgroundColor); if (bg && bg.a >= 0.99) { base = bg; break; } hop = hop.parentElement; }
              if (!base) base = { r: 255, g: 255, b: 255, a: 1 };
              const track = over(parseRgb(cs.backgroundColor), base);
              return { trackRaw: cs.backgroundColor, knobRaw: knob.backgroundColor, opacity: cs.opacity,
                ariaChecked: t.getAttribute('aria-checked') };
            });
            if (probe) return probe;
            await page.evaluate(() => document.querySelector('.plugins-card-head[data-plugin-item]')?.click());
          }
          return null;
        })();
        if (!plugProbe) throw new Error('no visible dispatch toggle (WS re-render storm x5)');
        const plugGreen = plugProbe.ariaChecked === 'true';
        check(`D1 ${eng}/${scheme} 插件面开关配色随态(${plugProbe.ariaChecked})`,
          plugGreen ? greenAt(plugProbe.trackRaw, 0.35) : !isGreenChannels(plugProbe.trackRaw),
          plugProbe.trackRaw);
        await page.locator('.plugins-item-detail:not([hidden])').first()
          .screenshot({ path: `${EV}/shots/${eng}-${scheme}-plugins-toggle.png` }).catch(() => {});
      } catch (e) {
        check(`D1 ${eng}/${scheme} 插件面开关`, false, 'unreachable: ' + e.message.split('\n')[0]);
      }
      await page.click('#settings-btn').catch(() => {});

      // 流图视图过滤（落点③，fail-soft）：先开项目画布取真实项目名再开流图
      try {
        await page.keyboard.press('Escape');
        await page.waitForTimeout(400);
        await page.click('#projects-btn');
        await page.waitForTimeout(600);
        await page.evaluate(async () => {
          const { openFlowMapTab } = await import('/js/flowMapTab.js');
          const proj = document.querySelector('[data-project-name]')?.getAttribute('data-project-name')
            || document.querySelector('.project-card')?.textContent?.trim() || null;
          await openFlowMapTab(proj);
        });
        await page.waitForSelector('[data-testid="fm-view-filter-toggle"]', { timeout: 8000 });
        await page.locator('.flowmap-view-filter')
          .screenshot({ path: `${EV}/shots/${eng}-${scheme}-flowmap-filter.png` });
        const fm = await probeSel(page, '[data-testid="fm-view-filter-toggle"]');
        console.log(`  [soft] flowmap filter: track=${fm && fm.trackRaw}`);
      } catch (e) { console.log(`  [soft] flowmap filter unreachable (${eng}/${scheme}): ${e.message.split('\n')[0]}`); }

      // ── G. console 卫生 ────────────────────────────────────────────────
      // virgin home 固有数据缺失 404（无 Nebula agent.json/avatar、无 portal 注册
      // ——与 CSS 批零关，实证见批文档诊断段）；其余 console error / pageerror
      // 零容忍。webkit 的 resource-error 文本不带 URL ⇒ 以 response 404 URL
      // 集合 + location.url 双通道归因。
      const allow404 = /api\/agents\/Nebula\/avatar\/meta|agents\/Nebula\/agent\.json|api\/portal\/status/;
      const bad404 = notFoundUrls.filter((u) => !allow404.test(u));
      const resourceErrs = consoleErrors.filter((e) => /Failed to load resource/.test(e.text));
      const hardErrors = consoleErrors.filter((e) => !/Failed to load resource/.test(e.text));
      const unexplained = resourceErrs.filter((e) => !(allow404.test(e.url) || (!e.url && bad404.length === 0)));
      const ok = hardErrors.length === 0 && bad404.length === 0 && unexplained.length === 0;
      check(`G1 ${eng}/${scheme} 零 console error / pageerror（除 virgin-home 固有 404 ×${notFoundUrls.length}）`, ok,
        JSON.stringify({ hard: hardErrors.slice(0, 2), bad404: bad404.slice(0, 3), unexplained: unexplained.slice(0, 2) }));
      fs.writeFileSync(`${EV}/console-${eng}-${scheme}.json`, JSON.stringify(consoleErrors, null, 2));
    } catch (e) {
      check(`流程 ${eng}/${scheme}`, false, e.message.split('\n')[0]);
      fs.writeFileSync(`${EV}/console-${eng}-${scheme}.json`, JSON.stringify(consoleErrors, null, 2));
    } finally {
      await browser.close();
    }
  }
}

// ── 会话引导（Bearer file token → console-ticket → ?ticket= 303 落 cookie）──
// console-ticket 一次性：必须每上下文独立 mint——复用同一 ticket 时后续上下文的
// WS 鉴权全部失效（boot 闸不落，settings-btn 永不可见；instance 日志 ws=0 实证）。
const homeToken = JSON.parse(fs.readFileSync('/tmp/nb-toggle-green-batch/home/auth.json', 'utf8'));
const token = typeof homeToken === 'string' ? homeToken : (homeToken.token || homeToken.bearer || Object.values(homeToken)[0]);
async function mintTicket() {
  const res = await fetch(BASE + '/api/auth/console-ticket', { method: 'POST', headers: { Authorization: 'Bearer ' + token } });
  const t = (await res.json()).ticket;
  if (!t) { console.error('FAIL ticket mint: HTTP ' + res.status); process.exit(1); }
  return t;
}

const readings = {};
for (const [name, engine] of [['chromium', chromium], ['webkit', webkit]]) {
  engine._name = name;
  await runEngine(engine, ['light', 'dark']);
}
// ── F. 读数采集（chromium，light/dark；probe 自带合成对比度）───────────────
{
  const browser = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 960 }, colorScheme: scheme });
    const ticket = await mintTicket();
    await page.goto(`${BASE}/?ticket=${encodeURIComponent(ticket)}`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#settings-btn', { timeout: 45000 });
    await page.click('#settings-btn');
    await page.waitForSelector('#toggle-llm-log', { timeout: 30000 });
    await injectTrio(page);
    // ON 轨 vs OFF 轨（状态轴 = WCAG 1.4.11 的真正承载面）+ 常规 knob/track 读数
    const stateAxis = await page.evaluate(() => {
      const parseRgb = (s) => { const m = (s || '').match(/[\d.]+/g); if (!m) return null; const [r, g, b, a = 1] = m.map(Number); return { r, g, b, a }; };
      const lum = ({ r, g, b }) => { const f = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
      const over = (fg, bg) => ({ r: fg.r * fg.a + bg.r * bg.a * (1 - fg.a), g: fg.g * fg.a + bg.g * bg.a * (1 - fg.a), b: fg.b * fg.a + bg.b * bg.a * (1 - fg.a), a: fg.a + bg.a * (1 - fg.a) });
      const ratio = (x, y) => { const lx = lum(x); const ly = lum(y); return (Math.max(lx, ly) + 0.05) / (Math.min(lx, ly) + 0.05); };
      const baseOf = (el) => { let hop = el; while (hop && hop !== document.documentElement) { const bg = parseRgb(getComputedStyle(hop).backgroundColor); if (bg && bg.a >= 0.99) return bg; hop = hop.parentElement; } return { r: 255, g: 255, b: 255, a: 1 }; };
      const onEl = document.querySelector('#probe-on'); const offEl = document.querySelector('#probe-off');
      if (!onEl || !offEl) return null;
      const base = baseOf(onEl);
      const onTrack = over(parseRgb(getComputedStyle(onEl).backgroundColor), base);
      const offTrack = over(parseRgb(getComputedStyle(offEl).backgroundColor), base);
      return +ratio(onTrack, offTrack).toFixed(2);
    });
    readings[scheme] = { ...(await probeSel(page, '#probe-on')), onVsOffTrack: stateAxis };
    await page.close();
  }
  await browser.close();
}
fs.writeFileSync(`${EV}/readings.json`, JSON.stringify(readings, null, 2));
console.log('\n── contrast readings（knob 实心绿 vs 轨道合成绿；轨道合成 vs 不透明底）──');
for (const [scheme, v] of Object.entries(readings)) {
  console.log(`${scheme}: knob-vs-track=${v.knobVsTrack}:1  track-vs-base=${v.trackVsBase}:1`);
}

fs.writeFileSync(`${EV}/results.json`, JSON.stringify(results, null, 2));
const fails = results.filter((x) => !x.ok);
console.log(`\n=== ${results.length - fails.length}/${results.length} PASS ===`);
process.exit(fails.length ? 1 : 0);
