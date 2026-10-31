// profile-copy-shots.mjs — 设置页「档参数覆盖」人话文案批双主题截图
// （作者令 2026-10-31「双主题截图」；审计+文案批见
// docs/20261031_153527_settings-profile-params-wiring-audit__delegate-nebflow-bd54b0ee.md）
//
// 自足脚本（照 cognition-phase2.spec.mjs 同一套替身纪律：静态服务真 web/ 树 +
// WS 替身 + ticket mock），开设置面板 → #profile-section 元素截图，亮/暗各一张。
// 全站主题 = prefers-color-scheme 媒体查询 ⇒ 两个 context 各跑一轮。
//
// 运行：node tests/profile-copy-shots.mjs
// 可选 env：PORT=8177、SHOTS_DIR=<dir>

import { chromium } from 'playwright';
import { mkdirSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8177);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || join(ROOT, 'evidence', '20261031_settings-profile-copy');
const VIEWPORT = { width: 1280, height: 900 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const PROFILE_DEFS = [
  { key: 'adhd-friendly', maxLines: 8, keyNumbers: 4, foldDefault: 'fold', imageDensity: 'high', reminderCadence: 'single', supervision: 'fine', pendingStyle: 'single', teachingAllowed: false },
  { key: 'deep-work', maxLines: 4, keyNumbers: 3, foldDefault: 'all-fold', imageDensity: 'low', reminderCadence: 'quiet', supervision: 'weekly', pendingStyle: 'batch', teachingAllowed: false },
  { key: 'quick-browse', maxLines: 3, keyNumbers: 4, foldDefault: 'strong', imageDensity: 'medium', reminderCadence: 'aggregate', supervision: 'coarse', pendingStyle: 'none', teachingAllowed: false },
  { key: 'expert-minimal', maxLines: 3, keyNumbers: 2, foldDefault: 'anomaly-top', imageDensity: 'low', reminderCadence: 'quiet', supervision: 'exception', pendingStyle: 'blocking', teachingAllowed: false },
  { key: 'exam-sprint', maxLines: 6, keyNumbers: 5, foldDefault: 'expand-findings', imageDensity: 'high', reminderCadence: 'single', supervision: 'fine', pendingStyle: 'deadline', teachingAllowed: true },
];

mkdirSync(SHOTS_DIR, { recursive: true });

/* ─────────────── 静态服务（真 web/ 树；自有端口） ─────────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.md': 'text/markdown; charset=utf-8',
};
const webServer = createServer((req, res) => {
  const u = new URL(req.url, BASE);
  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

async function shoot(colorScheme) {
  const browser = await chromium.launch();
  // 高视口：设置面板 = 85vh 定高滚动容器，常规 900px 高视口装不下整个
  // profile 区（7 参数 × 人话 hint）——证据截图用 2000px 视口一次收全。
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 2000 }, colorScheme });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route('**/agents/**', (r) => r.fulfill({ json: {} }));
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
      if (m.type === 'getConfig') {
        return ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
      }
      if (m.type === 'getPresentationProfile') {
        return ws.send(JSON.stringify({ type: 'presentationProfileData', profile: { current: 'expert-minimal', profiles: PROFILE_DEFS, overrides: {} } }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000, state: 'attached' });
  await page.evaluate(() => {
    document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove());
    document.documentElement.classList.remove('ui-init');
  });
  await sleep(400);
  await page.click('#settings-btn');
  await page.waitForSelector('#profile-section', { timeout: 5000 });
  await sleep(300);
  // 设置面板是定高滚动容器，section 超出部分会被裁掉——截图为证据目的临时
  // 放开容器高度（仅本页面临时样式，不写真树）。
  await page.evaluate(() => {
    const content = document.getElementById('settings-content');
    if (content) { content.style.maxHeight = 'none'; content.style.overflow = 'visible'; }
    const modal = document.getElementById('settings-modal');
    if (modal) { modal.style.maxHeight = 'none'; modal.style.height = 'auto'; }
  });
  await sleep(200);
  const section = page.locator('#profile-section');
  const file = join(SHOTS_DIR, `profile-section-${colorScheme}.png`);
  await section.screenshot({ path: file });
  // 读数：hint 行数与「暂未接线」标注在场性
  const readout = await page.evaluate(() => {
    const sec = document.getElementById('profile-section');
    return {
      hints: sec.querySelectorAll('.profile-ov-hint').length,
      rows: sec.querySelectorAll('.settings-row').length,
      wiringNote: (sec.textContent.match(/还没接到行为上|Not wired|尚未上线|not wired yet/g) || []).length,
    };
  });
  console.log(`${colorScheme}: rows=${readout.rows} hints=${readout.hints} wiringNotes=${readout.wiringNote} -> ${file}`);
  await browser.close();
  return readout;
}

try {
  const light = await shoot('light');
  const dark = await shoot('dark');
  const ok = light.hints === 7 && dark.hints === 7 && light.rows >= 8 && dark.rows >= 8 && light.wiringNote >= 1 && dark.wiringNote >= 1;
  console.log(`${ok ? 'PASS' : 'FAIL'}  profile-copy shots (hints=7/7 行数≥8 接线实话注在场)`);
  process.exitCode = ok ? 0 : 1;
} finally {
  webServer.close();
}
