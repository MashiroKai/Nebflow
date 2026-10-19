// One-off evidence harness: boot the plugins panel from the WORKTREE web dir
// (new panel code) with a registry built from the REAL seed manifests, and
// screenshot the new form in dark + light (+ one expanded detail shot).
// Cleanup: closes browser + server in finally. Port 8187 (8100+ discipline).
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { readdirSync, readFileSync, mkdirSync, statSync } from 'node:fs';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire('/opt/homebrew/lib/node_modules/@playwright/test/index.js');
const { chromium } = require('playwright-core');

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(join(HERE, '..', '..', 'src', 'main', 'resources', 'web'));
const OUT = join(HERE, 'shots');
mkdirSync(OUT, { recursive: true });
const PORT = 8187;
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.png': 'image/png' };

function startServer() {
  return new Promise((res) => {
    const s = createServer(async (req, r) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        const f = resolve(join(WEB, p));
        if (!f.startsWith(WEB)) { r.writeHead(403); r.end(); return; }
        const data = readFileSync(f);
        r.writeHead(200, { 'content-type': MIME[extname(f)] || 'application/octet-stream' });
        r.end(data);
      } catch { r.writeHead(404); r.end('nf'); }
    });
    s.listen(PORT, '127.0.0.1', () => res(s));
  });
}

// Registry from the REAL seed manifests (names/descriptions/skills live data).
function seedRegistry() {
  const seedDir = resolve(join(HERE, '..', '..', 'src', 'main', 'resources', 'seed', 'plugins'));
  const plugins = [];
  for (const d of readdirSync(seedDir)) {
    try {
      const j = JSON.parse(readFileSync(join(seedDir, d, 'plugin.json'), 'utf8'));
      const skills = [];
      try {
        for (const sk of readdirSync(join(seedDir, d, 'skills'))) {
          try {
            const text = readFileSync(join(seedDir, d, 'skills', sk, 'SKILL.md'), 'utf8');
            const desc = (text.match(/^description:\s*(.+)$/m) || ['', ''])[1].trim();
            skills.push({ id: `${j.name}/${sk}`, description: desc, preview: '' });
          } catch { /* skill dir without SKILL.md */ }
        }
      } catch { /* no skills dir */ }
      plugins.push({
        name: j.name, version: j.version || '', description: j.description || '',
        author: typeof j.author === 'object' ? (j.author?.name || '') : (j.author || ''),
        digest: 'seed', fileCount: 3, trusted: true, blocked: false, contentChanged: false,
        skills, mcpServers: [], toolsExtension: [], warnings: [],
      });
    } catch { /* skip non-package dirs */ }
  }
  return { plugins: plugins.sort((a, b) => a.name.localeCompare(b.name)), rejected: [{ name: 'legacy-stale-pack', reason: 'missing plugin.json manifest' }] };
}

async function boot(page, registry, colorScheme) {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.emulateMedia({ colorScheme });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
  });
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.route('**/api/plugin-assets/*/Plugin.md*', r => r.fulfill({ body: '# Plugin.md\n', contentType: 'text/markdown' }));
  await page.route('**/api/plugins', r => r.fulfill({ json: registry }));
  await page.routeWebSocket(/\/ws/, ws => {
    ws.onMessage(() => {});
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
    ws.send(JSON.stringify({ type: 'serverConfig', mcpServers: [], streamTimeoutMs: 60000, version: 'test', thinking: {}, workSchedule: {}, tools: [] }));
  });
  await page.goto(`http://127.0.0.1:${PORT}/index.html`);
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await page.evaluate(() => import('/js/plugins.js').then(m => m.openPlugins()));
  await page.waitForSelector('#plugins-content .plugins-card', { timeout: 10000 });
  await page.waitForTimeout(600);
}

const server = await startServer();
const browser = await chromium.launch();
try {
  const registry = seedRegistry();
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  for (const scheme of ['dark', 'light']) {
    await boot(page, registry, scheme);
    await page.screenshot({ path: join(OUT, `panel-${scheme}.png`) });
    // 展开态（首个多 skill 包）——布局收紧 + 胶囊列表取证
    const first = registry.plugins.slice().sort((a, b) => b.skills.length - a.skills.length)[0]?.name;
    if (first) {
      await page.click(`.plugins-card[data-plugin="${first}"] [data-plugin-item]`);
      await page.waitForTimeout(400);
      await page.screenshot({ path: join(OUT, `panel-${scheme}-expanded.png`) });
    }
  }
  await ctx.close();
  console.log('EVIDENCE-OK');
} finally {
  await browser.close();
  server.close();
}
