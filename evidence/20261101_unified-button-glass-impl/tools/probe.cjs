#!/usr/bin/env node
'use strict';
/* probe.cjs — 统一按钮玻璃批：计算样式断言探针（2026-11-01）。
 *
 * 零端口零进程，与 shot.cjs 同打桩路线（page.route 磁盘真文件 +
 * MockWebSocket 生产 boot 链）。两类被测对象：
 *   REAL    — 页面真实节点（#header、#input-bar、注入的 .bubble.user/.ai）；
 *   FIXTURE — 用生产类名构建的代表节点（canvas 圆钮族/ask-fab/dock chrome 钮/
 *              g2-pill/关签钮等——挂 body 独立定位层，不依赖真实 canvas 文档树；
 *              样式级联（liquid-glass.css ④ 块 F1 覆盖、sapphire.css 加载序）
 *              与生产同源，仅宿主祖先不同——清点「类名级联终局」用）。
 * 每元素采集：尺寸/底色/背景图/backdrop-filter（含 -webkit- 计算值）/边框/
 * 圆角/box-shadow/transform/opacity/color + winning rule 所在 stylesheet
 * href（扫 document.styleSheets 按序记录命中，末条 ≈ 级联终局）。
 * 另采集关键 CSS 变量双主题值。输出 JSON。
 *
 * 用法：
 *   NODE_PATH=/opt/homebrew/lib/node_modules node probe.cjs \
 *     [--engine chromium|webkit|both] [--theme dark|light|both] [--out <file>]
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
const OUT = arg('out', join(__dirname, '..', 'probe-report.json'));

/** fixture 构建表：key → 生产类名逐字（JS 挂类处已注）。 */
const FIXTURES = [
  { key: 'cv-source-toggle', className: 'canvas-source-toggle' },
  { key: 'cv-ref-select', className: 'canvas-ref-select' },
  { key: 'cv-refresh-toggle', className: 'canvas-refresh-toggle' },
  { key: 'cv-find-toggle', className: 'canvas-find-toggle' },
  { key: 'deck-fab', className: 'deck-fab g2-fab', id: 'deck-fab' },            // canvas.js:1938-39
  { key: 'canvas-head-close', className: 'g2-fab', id: 'canvas-head-close' },   // canvas.js:2053
  { key: 'ask-fab', className: 'ask-fab' },                                     // askDock.js:204
  { key: 'mh-chrome-btn', className: 'mh-chrome-btn g2-fab' },                  // chatChrome.js:153/169
  { key: 'mh-fab-search', className: 'mh-fab-search g2-fab' },                  // mobile/socialSearch.js
  { key: 'mf-cv-close', className: 'mf-cv-close g2-fab' },                      // mobile/navController.js:864
  { key: 'mf-stack-x', className: 'mf-stack-x g2-fab' },                        // mobile/navController.js:759
  { key: 'g2-pill', className: 'g2-pill' },
  { key: 'g2-pill-primary', className: 'g2-pill g2-pill-primary' },
  { key: 'canvas-refback-btn', className: 'canvas-refback-btn' },
  { key: 'canvas-header', className: 'canvas-header' },
  { key: 'ask-bub', className: 'ask-bub ai' },
  { key: 'ask-input', className: 'ask-input' },
];

const VARS = ['--glass-bg', '--glass-blur', '--glass-border', '--cv-bg', '--cv-blur', '--cv-saturate',
  '--cv-shadow', '--cv-size-circle', '--cv-hover-scale', '--cv-active-scale', '--capsule-h', '--capsule-w',
  '--g2-fab-bg', '--g2-fab-blur', '--g2-fab-sat', '--g2-fab-solid', '--g2-fade-blur',
  '--glass-control-bg', '--glass-control-blur', '--glass-control-sat', '--glass-control-bg-hover',
  '--ctl-send-bg', '--ctl-send-bg-hover', '--btn-ink-bg', '--frost-shadow'];

async function runProbe(page) {
  await page.evaluate((fixtures) => {
    // fixture 层：body 直挂 fixed 定位 + 渐变背板，让 backdrop-filter 有采样源
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
    for (const f of fixtures) {
      if (document.querySelector('[data-probe="' + f.key + '"]')) continue;
      const el = document.createElement('button');
      el.type = 'button';
      el.className = f.className || '';
      if (f.id) el.id = f.id;
      el.setAttribute('data-probe', f.key);
      el.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24"><circle cx="12" cy="12" r="7"/></svg>';
      host.appendChild(el);
    }
    // REAL 气泡（若未注入）：生产类名两条
    const chat = document.getElementById('chat');
    if (chat && !chat.querySelector('.bubble.user')) {
      const rowU = document.createElement('div'); rowU.className = 'row user';
      const bu = document.createElement('div'); bu.className = 'bubble user'; bu.textContent = 'probe user bubble — 玻璃清点实料';
      rowU.appendChild(bu); chat.appendChild(rowU);
      const rowA = document.createElement('div'); rowA.className = 'row ai';
      const ba = document.createElement('div'); ba.className = 'bubble ai'; ba.textContent = 'probe ai bubble — glass inventory fixture';
      rowA.appendChild(ba); chat.appendChild(rowA);
    }
  }, FIXTURES);
  await page.waitForTimeout(150);
  return page.evaluate(({ fixtures, vars }) => {
    const pick = (el) => {
      if (!el) return null;
      const cs = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      // winning rule：按 stylesheet 表序扫 document.styleSheets，命中即记（末条≈级联终局）
      const wins = [];
      for (const sh of Array.from(document.styleSheets)) {
        let rules;
        try { rules = sh.cssRules; } catch { continue; }
        if (!rules) continue;
        for (const rule of rules) {
          const flat = [];
          if (rule.type === 1 && rule.selectorText) flat.push(rule);
          else if (rule.cssRules) {
            for (const sub of rule.cssRules) if (sub.type === 1 && sub.selectorText) flat.push(sub);
          }
          for (const rl of flat) {
            let hit = false;
            try { hit = el.matches(rl.selectorText); } catch { /* 引擎不支持的选择器 */ }
            if (hit) wins.push({ href: sh.href ? sh.href.split('/').pop() : '(inline)', selector: rl.selectorText });
          }
        }
      }
      return {
        rect: { w: Math.round(r.width * 10) / 10, h: Math.round(r.height * 10) / 10 },
        display: cs.display, opacity: cs.opacity,
        backgroundColor: cs.backgroundColor, backgroundImage: cs.backgroundImage,
        backdropFilter: cs.backdropFilter, webkitBackdropFilter: cs.webkitBackdropFilter || '',
        border: cs.border, borderRadius: cs.borderRadius, boxShadow: cs.boxShadow,
        color: cs.color, transform: cs.transform,
        // g2-fab 族的玻璃在 ::before 折射层上（元素本体 backdrop-filter=none）
        beforeBackdropFilter: getComputedStyle(el, '::before').backdropFilter || '',
        beforeBackgroundImage: getComputedStyle(el, '::before').backgroundImage || '',
        winningRules: wins.slice(-4),
      };
    };
    const out = { fixtures: {}, real: {}, vars: {} };
    for (const f of fixtures) {
      const el = document.querySelector('[data-probe="' + f.key + '"]');
      out.fixtures[f.key] = el ? pick(el) : { missing: true };
    }
    const reals = { header: '#header', inputBar: '#input-bar', bubbleUser: '.bubble.user', bubbleAi: '.bubble.ai' };
    for (const [k, sel] of Object.entries(reals)) out.real[k] = pick(document.querySelector(sel));
    const rs = getComputedStyle(document.documentElement);
    for (const v of vars) out.vars[v] = rs.getPropertyValue(v).trim();
    return out;
  }, { fixtures: FIXTURES, vars: VARS });
}

(async () => {
  const report = [];
  for (const engineName of lib.engines(ENGINE)) {
    const engine = engineName === 'webkit' ? webkit : chromium;
    const browser = await engine.launch();
    for (const theme of lib.themes(THEME)) {
      const page = await browser.newPage({ viewport: { width: 1280, height: 844 }, colorScheme: theme });
      const errors = lib.attachErrorCollectors(page);
      await page.addInitScript(lib.INIT_SCRIPT);
      await lib.makeRouter(page);
      await page.goto('http://glassshot.local/', { waitUntil: 'domcontentloaded' });
      await lib.bootSession(page);
      await lib.applyTheme(page, theme);
      const data = await runProbe(page);
      report.push({ engine: engineName, theme, errors, ...data });
      console.log(`[probe] ${engineName} ${theme}: fixtures=${Object.keys(data.fixtures).length} vars=${Object.keys(data.vars).length} errors=${errors.length}`);
      await page.close();
    }
    await browser.close();
  }
  writeFileSync(OUT, JSON.stringify(report, null, 2));
  console.log(`probe.cjs done → ${OUT}`);
})().catch((e) => { console.error(e); process.exit(1); });
