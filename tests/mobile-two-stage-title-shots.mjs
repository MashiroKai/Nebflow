#!/usr/bin/env node
// 375 双主题实拍：静止 / 吸附中 / 回顶 三态 × 3 面板 + 「修前 vs 修后」同屏占比对比帧。
// 用法：node tests/mobile-two-stage-title-shots.mjs <outDir> [--web-root <webDir>] [--tag <name>]
//   --web-root 默认本仓 src/main/resources/web；「修前」指向 main 工作树的同名目录。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const outDir = resolve(args[0] || 'evidence/tmp-shots');
mkdirSync(outDir, { recursive: true });
const webRootIdx = args.indexOf('--web-root');
const WEB = webRootIdx >= 0 ? resolve(args[webRootIdx + 1]) : join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const tagIdx = args.indexOf('--tag');
const TAG = tagIdx >= 0 ? String(args[tagIdx + 1]) : 'after';

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'two-stage-root';
const VW = 375; const VH = 812;

const PROJECTS = Array.from({ length: 12 }, (_, i) => ({
  name: ['CdZnTe 探测器研究', 'SlideBlocks Studio', 'D0STEL 读出固件', 'chisel-fpga', 'Nebflow 手机层'][i % 5] + ` #${i + 1}`,
  workspace: `/Users/kaiyu/.stand/projects/proj-${i + 1}`,
  description: i % 2 === 0 ? '验收夹具项目（滚动域填充行）' : '',
  agentFile: true,
  createdAt: Date.now() - (i + 1) * 86_400_000,
}));
// 待办行数取 24：面板高度 812 时须保证主滚轴余量远超吸附阈值 56px（10 行时
// 内容恰好铺满视口、scrollTop 恒 0 ⇒ 吸附态拍不到）。
const TODOS = Array.from({ length: 24 }, (_, i) => ({ id: `ts-${i + 1}`, kind: 'todo', content: `验收夹具待办 ${i + 1}`, triggerAt: Date.now() + 3600_000 }));

async function page(scheme) {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: VW, height: VH }, colorScheme: scheme, deviceScaleFactor: 2, hasTouch: true, locale: 'zh-CN' });
  const pg = await ctx.newPage();
  await pg.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_sw', '0');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => new Promise(() => {}), configurable: true });
  });
  await pg.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/projects') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ projects: PROJECTS }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await pg.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const ses = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    cfg(); ses();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions' || m.type === 'listSessions') ses();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'listScheduledTasks') ws.send(JSON.stringify({ type: 'scheduledTaskList', scope: m.scope === 'all' ? 'all' : 'session', tasks: TODOS.map((t) => ({ ...t })) }));
    });
  });
  await pg.goto('http://localhost:8899/');
  await pg.waitForFunction(async (sid) => { const s = (await import('/js/state.js')).default; return s.activeSessionId === sid && s.ws && s.ws.readyState === 1; }, SID, { timeout: 20000 });
  await pg.waitForSelector('body.mobile-layer', { timeout: 15000 });
  await pg.waitForTimeout(600);
  return { pg, browser };
}

const dom = {
  async workbench(pg) {
    await pg.evaluate(() => { const s = document.querySelector('.mobile-page.live'); if (s) s.querySelectorAll('.mobile-back-btn').forEach((b) => b.click()); });
    await pg.waitForTimeout(300);
    await pg.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
    await pg.waitForSelector('#mobile-pages .mobile-page.live[data-page="workbench"] .mf-hub-rows', { timeout: 10000 });
    await pg.waitForTimeout(250);
  },
  async hub(pg, id, sel) {
    await dom.workbench(pg);
    await pg.locator(`#mobile-pages .mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="${id}"]`).click();
    await pg.waitForSelector(sel, { timeout: 15000 });
    await pg.waitForTimeout(500);
  },
  // ⛔ 腿退役（入口收敛批 2026-11-04，见调用点注记）：原文留档，恢复入口即可复用。
  // async todo(pg) {
  //   await dom.workbench(pg);
  //   await pg.evaluate(() => document.dispatchEvent(new CustomEvent('nf:mobile-todo-entry')));
  //   await pg.waitForSelector('#mobile-pages .mobile-page.live[data-page="todo"] #todo-page .reminder-row', { timeout: 15000 });
  //   await pg.waitForTimeout(500);
  // },
  // 主滚轴：**最外层**有余量的纵向滚动器（与 navController.primaryScroller /
  // refreshRoll 判据同源）——按距 section 的层级取最小者；页体自身可滚即它。
  scroll(pg, pid, v) {
    return pg.evaluate(({ pid, v }) => {
      const sec = document.querySelector(`#mobile-pages .mobile-page[data-page="${pid}"]`);
      const bodyEl = sec.querySelector('.mobile-page-body');
      const depthOf = (n) => { let d = 0; for (let p = n; p && p !== sec; p = p.parentElement) d += 1; return d; };
      let primary = null; let best = Infinity;
      for (const n of [bodyEl, ...bodyEl.querySelectorAll('*')]) {
        const oy = getComputedStyle(n).overflowY;
        if (oy !== 'auto' && oy !== 'scroll') continue;
        if (n.scrollHeight - n.clientHeight <= 4) continue;
        const d = depthOf(n);
        if (d < best) { primary = n; best = d; }
        if (d === 1) break; // 页体自身可滚 ⇒ 它必是最外层
      }
      const t = primary || bodyEl;
      t.scrollTop = v;
      t.dispatchEvent(new Event('scroll', { bubbles: false }));
      return { tag: t.className || t.id || t.tagName, top: t.scrollTop, p: getComputedStyle(sec.querySelector('.mobile-page-title')).getPropertyValue('--mtitle-p').trim() };
    }, { pid, v });
  },
};

const shots = [];
const shot = async (pg, name) => { const f = join(outDir, `${TAG}-${name}.png`); await pg.screenshot({ path: f }); shots.push(name); };

for (const scheme of ['light', 'dark']) {
  const { pg, browser } = await page(scheme);
  try {
    // 设置：静止 / 吸附中 / 回顶
    await dom.hub(pg, 'settings', '#mobile-pages .mobile-page.live[data-page="settings"] #settings-modal');
    await shot(pg, `${scheme}-settings-rest`);
    const midInfo = await dom.scroll(pg, 'settings', 32);
    await shot(pg, `${scheme}-settings-mid`);
    await dom.scroll(pg, 'settings', 300);
    await shot(pg, `${scheme}-settings-snapped`);
    await dom.scroll(pg, 'settings', 0);
    await pg.waitForTimeout(200);
    await shot(pg, `${scheme}-settings-backtop`);

    // 项目：静止 / 吸附
    await dom.hub(pg, 'projects', '#mobile-pages .mobile-page.live[data-page="projects"] .team-scroll');
    await shot(pg, `${scheme}-projects-rest`);
    await dom.scroll(pg, 'projects', 300);
    await shot(pg, `${scheme}-projects-snapped`);
    await dom.scroll(pg, 'projects', 32);
    await shot(pg, `${scheme}-projects-mid`);

    // 待办：静止 / 吸附 —— ⛔ 腿退役（入口收敛批 todo-unify-entry-collapse
    // 2026-11-04）：#todo-page 整面退役、推送腿摘除 ⇒ 页面栈无 todo 页，
    // 两段式标题吸附对该面无测点（现役待办面 = 顶带胶囊，非页面栈成员）。
    // await dom.todo(pg);
    // await shot(pg, `${scheme}-todo-rest`);
    // await dom.scroll(pg, 'todo', 300);
    // await shot(pg, `${scheme}-todo-snapped`);
    console.log(`[shots] ${scheme} mid-p=${JSON.stringify(midInfo)}`);
  } finally {
    await browser.close();
  }
}
console.log(`[shots] ${shots.length} files → ${outDir}`);
