#!/usr/bin/env node
// _capture-diff-geometry.mjs — 桌面零回归**逐值**几何读数（非闸）。
//
// 在**真实前端源码树**（WEB_DIR，可指向基线树做对照）上，起桌面上下文
// （1440×900）→ 注入一份两文件委派完成帧 → 打开 Canvas diff 阅读面 → 逐值读出
// 周边 chrome（卡头/标签条/左栏面板/阅读面容器/文件头胶囊）与组件几何。
// 同一脚本在迁移前后各跑一次，读数逐值对比 = 「桌面零回归」的证据（非观感）。
//
// 用法：WEB_DIR=<web 目录> node tests/_capture-diff-geometry.mjs <outJson>

import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const WEB = process.env.WEB_DIR || resolve(REPO, 'src/main/resources/web');
const OUT = process.argv[2] || join(REPO, 'evidence/diff-geometry.json');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const SID = 'geo-root';

const PAYLOAD = {
  nodeId: 'delegate-geo', generatedAt: 1791999000000, root: '/tmp/ws-geo',
  files: [
    { path: 'src/main/resources/web/js/main.js', changeType: 'modified', added: 11, removed: 4, source: 'tool-ledger', diffText: '@@ -1,4 +1,6 @@\n import a\n-import b\n+import c\n+import d\n const x = 1\n const y = 2' },
    { path: 'docs/spec/diff-split.md', changeType: 'modified', added: 3, removed: 1, source: 'tool-ledger', diffText: '@@ -1,2 +1,3 @@\n-b\n+b\n+c' },
  ],
};

const browser = await chromium.launch();
const out = { webDir: WEB, viewport: '1440×900', dsf: 1, chrome: {}, component: {} };
try {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1, colorScheme: 'dark' });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'geo-token');
    localStorage.setItem('nebflow_locale', 'en');
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sess = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    cfg(); sess();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sess();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.evaluate(async (payload) => {
    const s = (await import('/js/state.js')).default;
    s.ws.onmessage({ data: JSON.stringify({ type: 'user', injected: true, sessionId: s.activeSessionId, source: 'delegate', eventType: 'completed', header: 'DELEGATE · stand · geo · COMPLETED', text: 'done', timestamp: Date.now(), fileChanges: payload }) });
  }, PAYLOAD);
  await page.locator('.bubble.injected .nf-rcpt-note').first().click();
  await page.waitForSelector('.canvas-tab-pane[data-type="filechanges"] .fcv-filehead', { timeout: 10000 });
  await page.waitForTimeout(900);

  out.chrome = await page.evaluate(() => {
    const r = (sel) => { const e = document.querySelector(sel); if (!e) return null; const b = e.getBoundingClientRect(); return { x: +b.x.toFixed(1), y: +b.y.toFixed(1), w: +b.width.toFixed(1), h: +b.height.toFixed(1) }; };
    return {
      canvasHeader: r('.canvas-header'),
      canvasTabBar: r('.canvas-tab-bar'),
      filePanel: r('#panel-filechanges'),
      fileRows: document.querySelectorAll('#panel-filechanges .nfp-list .nf-fc').length,
      pane: r('.canvas-tab-pane[data-type="filechanges"]'),
      fcvView: r('.canvas-tab-pane[data-type="filechanges"] .fcv-view'),
      fcvFilehead: r('.canvas-tab-pane[data-type="filechanges"] .fcv-filehead'),
      fcvHeadpill: r('.canvas-tab-pane[data-type="filechanges"] .fcv-headpill'),
      dvHost: r('.canvas-tab-pane[data-type="filechanges"] .fcv-dvhost'),
      nfDv: r('.canvas-tab-pane[data-type="filechanges"] .fcv-dvhost .nf-dv'),
      nfDvEditor: r('.canvas-tab-pane[data-type="filechanges"] .fcv-dvhost .nf-dv-editor'),
      cmEditor: r('.canvas-tab-pane[data-type="filechanges"] .cm-editor'),
      computed: (() => {
        const q = (sel) => document.querySelector('.canvas-tab-pane[data-type="filechanges"] ' + sel);
        const cs = (el) => { if (!el) return null; const s = getComputedStyle(el); return { display: s.display, flexDir: s.flexDirection, flexGrow: s.flexGrow, flexBasis: s.flexBasis, width: s.width, height: s.height, minW: s.minWidth, align: s.alignItems }; };
        return { dvhost: cs(q('.fcv-dvhost')), nfDv: cs(q('.nf-dv')), nfDvEditor: cs(q('.nf-dv-editor')) };
      })(),
      rules: (() => {
        const hits = [];
        for (const ss of document.styleSheets) {
          let rules; try { rules = ss.cssRules; } catch { continue; }
          for (const r of rules) {
            if (r.selectorText && r.selectorText.includes('nf-dv')) hits.push({ sheet: (ss.href || '').split('/').pop(), sel: r.selectorText, txt: r.style.cssText.slice(0, 200) });
          }
        }
        return hits;
      })(),
    };
  });
  out.component = await page.evaluate(() => {
    const host = document.querySelector('.canvas-tab-pane[data-type="filechanges"] .fcv-dvhost .nf-dv-editor');
    const api = host && host.__nfDiffApi;
    if (!api) return { present: false };
    const doc = api.view.state.doc;
    return {
      present: true, docLines: doc.lines,
      mountedLines: document.querySelectorAll('.canvas-tab-pane[data-type="filechanges"] .cm-line').length,
      gutterW: (() => { const g = document.querySelector('.canvas-tab-pane[data-type="filechanges"] .cm-gutters'); return g ? +g.getBoundingClientRect().width.toFixed(1) : null; })(),
      scroller: (() => { const s = document.querySelector('.canvas-tab-pane[data-type="filechanges"] .cm-scroller'); if (!s) return null; const b = s.getBoundingClientRect(); return { w: +b.width.toFixed(1), h: +b.height.toFixed(1), scrollW: s.scrollWidth, clientW: s.clientWidth }; })(),
    };
  });
} finally {
  await browser.close();
}
writeFileSync(OUT, JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2));
