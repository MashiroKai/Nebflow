#!/usr/bin/env node
// mobile-interaction-standard-shots.mjs — 手机端交互标准批 前后对照实拍
//（作者令 2026-11-02「375 双主题实拍：改造前后对照 × 至少 5 个面板
//  （任务/定时任务/心跳/设置/项目），含 toggle 前后对照一帧、
//  取消加载按钮图标修正一帧」）
//
// 产物（每个主题一套）：
//   <tag>-<panel>-<scheme>.png   面板前后对照（task/reminder/daemon/settings/projects）
//   <tag>-toggle-<scheme>.png    设置页开关（iOS 尺寸语言）前后对照
//   <tag>-unmount-icon-<scheme>.png  项目卡「取消加载」图标修正前后对照
//
// 用法：
//   node tests/mobile-interaction-standard-shots.mjs <outDir> \
//        [--before-web <dir>] [--after-web <dir>] [--schemes light,dark]
//   --before-web 缺省 = 当前仓 src/main/resources/web（即把当前树当 both，
//                 用于单侧出图）；前后对照须显式给两个不同根。

import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const arg = (n, d) => {
  const i = args.indexOf(n);
  return i >= 0 && args[i + 1] ? args[i + 1] : d;
};
const OUT = resolve(args[0] || join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', 'mobile-interaction-standard', 'shots'));
mkdirSync(OUT, { recursive: true });

const REPO_WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const BEFORE_WEB = resolve(arg('--before-web', REPO_WEB));
const AFTER_WEB = resolve(arg('--after-web', REPO_WEB));
const SCHEMES = arg('--schemes', 'light,dark').split(',').map((s) => s.trim()).filter(Boolean);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'mis-root-session';
const T0 = Date.now();

function projectsFixture() {
  return {
    projects: [
      { name: 'CdZnTe 探测器研究', workspace: '/Users/kaiyu/.stand/projects/CdZnTe-detector-research-lab-notebook', description: 'CdZnTe（碲锌镉）探测器研究——探测器制备、能谱性能表征与读出电子学联调', agentFile: true, createdAt: T0 - 86400_000 },
      { name: 'SlideBlocks Studio', workspace: '/Users/kaiyu/Claude code/SlideBlocksStudio', description: '演示引擎与课件编辑器', agentFile: true, createdAt: T0 - 7200_000 },
      { name: 'D0STEL', workspace: '/Users/kaiyu/Claude code/D0STEL', description: 'SiPM 读出固件', agentFile: true, createdAt: T0 - 1800_000 },
    ],
    unmounted: [],
  };
}

const browser = await chromium.launch();

async function makePage(webRoot, scheme) {
  const ctx = await browser.newContext({
    viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, colorScheme: scheme,
  });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'shots-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (url.pathname === '/favicon.ico') return route.fulfill({ status: 200, contentType: 'image/x-icon', body: '' });
    if (p === '/agents/Nebula/agent.json') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: '笨笨' }) });
    }
    if (p.startsWith('/api/sessions')) {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
      });
    }
    if (p.startsWith('/api/projects')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(projectsFixture()) });
    }
    if (p.startsWith('/api/reminders')) {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          reminders: [
            { id: 'r1', text: '每天早上汇总一次夜间产线日志', minute: 30, hour: 8, repeat: 'daily', enabled: true },
            { id: 'r2', text: '每周五清理构建缓存', minute: 0, hour: 18, repeat: 'weekly', enabled: true },
            { id: 'r3', text: '一次性提醒：复核关闭批的证据链', minute: 15, hour: 14, repeat: 'none', enabled: false },
          ],
        }),
      });
    }
    if (p.startsWith('/api/daemons')) {
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          daemons: [
            { id: 'd1', name: '引擎主进程', port: 6138, command: 'sbt "run --port 6138"', status: 'running', enabled: true, autostart: true },
            { id: 'd2', name: '静态资源构建', port: 6139, command: 'npm run build --watch', status: 'stopped', enabled: false, autostart: false },
          ],
        }),
      });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(webRoot, p));
    if (!file.startsWith(webRoot)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: '笨笨的会话', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig(); sendSessions();
    ws.send(JSON.stringify({
      type: 'activeBgTasks',
      tasks: {
        [SID]: [
          { taskId: 'mis-1', description: '跑手机端触控标准回归', status: 'running', startedAt: T0 - 65_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 3000, outputLines: 14 } },
        ],
      },
    }));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getMemory') ws.send(JSON.stringify({ type: 'memoryData', scope: m.scope, content: '手机端交互标准批自验样例。' }));
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(800);
  // 夹具卫生：空态登录卡是全屏拦截层，出图前摘除（单独一帧会拍到它，见 login 面）。
  await page.evaluate(() => {
    document.getElementById('nebflow-login-modal')?.remove();
    document.body.classList.remove('login-gate');
  });
  await page.waitForTimeout(120);
  return page;
}

const dockTo = async (page, id) => {
  await page.locator(`#mobile-dock .dock-key[data-dock="${id}"]`).click();
  await page.waitForFunction((pid) => document.querySelector(`.mobile-page[data-page="${pid}"]`)?.classList.contains('live'), id, { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(320);
};
const hubTo = async (page, face) => {
  await dockTo(page, 'workbench');
  await page.locator(`.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="${face}"]`).click();
  await page.waitForFunction((pid) => document.querySelector(`.mobile-page[data-page="${pid}"]`)?.classList.contains('live'), face, { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(400);
};

/** 一帧 = 一面。 */
const PANELS = [
  {
    id: 'tasks',
    open: async (page) => {
      await page.locator('.mh-chrome-btn[data-chrome="tasks"]').click();
      await page.waitForTimeout(520);
    },
  },
  { id: 'reminder', open: (page) => hubTo(page, 'reminder') },
  { id: 'daemon', open: (page) => hubTo(page, 'daemon') },
  { id: 'settings', open: (page) => hubTo(page, 'settings') },
  { id: 'projects', open: (page) => hubTo(page, 'projects') },
  { id: 'usage', open: (page) => hubTo(page, 'usage') },
  { id: 'todo', open: (page) => hubTo(page, 'todo') },
  { id: 'plugins', open: (page) => hubTo(page, 'plugins') },
  { id: 'background', open: (page) => hubTo(page, 'background') },
];

const written = [];
async function shoot(side, webRoot, scheme) {
  const page = await makePage(webRoot, scheme);
  for (const pan of PANELS) {
    try {
      await pan.open(page);
      const f = join(OUT, `${side}-${pan.id}-${scheme}.png`);
      await page.screenshot({ path: f });
      written.push(f);
    } catch (e) {
      console.warn(`skip ${side}/${pan.id}/${scheme}: ${String(e).slice(0, 120)}`);
    }
  }

  // 专项一帧：toggle 前后对照（设置页第一枚开关 = #toggle-llm-log，裁切到
  // 它所在的设置分组卡，让 iOS 尺寸语言的前后差一眼可读）。
  try {
    await hubTo(page, 'settings');
    const sw = page.locator('#toggle-llm-log');
    await sw.waitFor({ state: 'visible', timeout: 6000 });
    const box = await sw.boundingBox();
    if (box) {
      const pad = 90;
      await page.screenshot({
        path: join(OUT, `${side}-toggle-${scheme}.png`),
        clip: {
          x: Math.max(0, box.x - pad), y: Math.max(0, box.y - 60),
          width: Math.min(375 - Math.max(0, box.x - pad), box.width + pad + 24),
          height: 150,
        },
      });
      written.push(join(OUT, `${side}-toggle-${scheme}.png`));
      // 几何读数落盘（前后对照的机械证据）。
      const geo = await sw.evaluate((el) => {
        const cs = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        const knob = getComputedStyle(el, '::after');
        return {
          track: `${Math.round(r.width)}×${Math.round(r.height)}`,
          radius: cs.borderRadius,
          knob: `${knob.width}×${knob.height}`,
          knobOffset: `${knob.top}/${knob.left}`,
        };
      });
      geom[`${side}-${scheme}`] = geo;
    }
  } catch (e) {
    console.warn(`skip ${side}/toggle/${scheme}: ${String(e).slice(0, 120)}`);
  }

  // 专项一帧：项目卡「取消加载」按钮图标（裁切卡头右上角动作钮）。
  try {
    await hubTo(page, 'projects');
    await page.waitForTimeout(500);
    const btn = page.locator('.project-unmount-btn').first();
    await btn.waitFor({ state: 'visible', timeout: 8000 });
    const box = await btn.boundingBox();
    if (box) {
      const pad = 26;
      await page.screenshot({
        path: join(OUT, `${side}-unmount-icon-${scheme}.png`),
        clip: {
          x: Math.max(0, box.x - pad), y: Math.max(0, box.y - pad),
          width: Math.min(375 - Math.max(0, box.x - pad), box.width + pad * 2),
          height: box.height + pad * 2,
        },
      });
      written.push(join(OUT, `${side}-unmount-icon-${scheme}.png`));
      const icon = await btn.evaluate((el) => {
        const svg = el.querySelector('svg');
        return {
          size: svg ? `${Math.round(svg.getBoundingClientRect().width)}×${Math.round(svg.getBoundingClientRect().height)}` : null,
          paths: svg ? [...svg.querySelectorAll('path')].map((p) => p.getAttribute('d')) : [],
          title: el.getAttribute('title'),
        };
      });
      geom[`${side}-${scheme}-unmount`] = icon;
    }
  } catch (e) {
    console.warn(`skip ${side}/unmount/${scheme}: ${String(e).slice(0, 120)}`);
  }

  await page.context().close();
}

const geom = {};
const sides = [];
if (existsSync(BEFORE_WEB)) sides.push(['before', BEFORE_WEB]);
sides.push(['after', AFTER_WEB]);

for (const scheme of SCHEMES) {
  for (const [side, root] of sides) {
    if (side === 'before' && BEFORE_WEB === AFTER_WEB) continue; // 同根只出一套
    console.log(`shooting ${side} / ${scheme} …`);
    await shoot(side, root, scheme);
  }
}

console.log(`\n${written.length} shots → ${OUT}`);
console.log('几何读数：');
console.log(JSON.stringify(geom, null, 2));
await browser.close();
