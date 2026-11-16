// Synthetic browser regression: node tests/usage-calendar-fixture.mjs <before|after>
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, join, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const web = join(root, 'src/main/resources/web');
const phase = process.argv[2] || 'after';
const out = join(root, 'evidence/usage-calendar');
mkdirSync(out, { recursive: true });
const browser = await chromium.launch();
let failures = 0;
for (const { scheme, zone, date, instant } of [
  { scheme: 'light', zone: 'Asia/Shanghai', date: '2026-11-16', instant: '2026-11-16T02:00:00Z' },
  { scheme: 'dark', zone: 'Asia/Shanghai', date: '2026-11-16', instant: '2026-11-16T02:00:00Z' },
  { scheme: 'light', zone: 'Asia/Shanghai', date: '2027-01-01', instant: '2027-01-01T02:00:00Z' },
  { scheme: 'light', zone: 'America/New_York', date: '2026-03-08', instant: '2026-03-08T15:00:00Z' },
  { scheme: 'light', zone: 'America/New_York', date: '2026-11-01', instant: '2026-11-01T15:00:00Z' },
]) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: scheme, timezoneId: zone });
  const page = await context.newPage();
  await page.clock.install({ time: new Date(instant) });
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/usage/aggregate') {
      const from = Number(url.searchParams.get('from'));
      const to = Number(url.searchParams.get('to'));
      const dim = url.searchParams.get('dim');
      const bucket = { key: dim === 'day' ? date : `${date}T10`, inputTokens: 190000000, outputTokens: 0, count: 1 };
      const buckets = (dim === 'day' || dim === 'hour') && from <= Date.parse(instant) && Date.parse(instant) < to ? [bucket] : [];
      return route.fulfill({ json: { buckets, count: buckets.length, totalInput: 190000000, totalOutput: 0 } });
    }
    if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body: '<!doctype html><html><head><link rel="stylesheet" href="/css/usage.css"></head><body><script type="module">import {openUsageDashboard} from "/js/usageDashboard.js"; openUsageDashboard();</script></body></html>' });
    const file = normalize(join(web, decodeURIComponent(url.pathname)));
    if (!file.startsWith(web + '/')) return route.fulfill({ status: 403 });
    try { return route.fulfill({ contentType: extname(file) === '.js' ? 'text/javascript' : 'text/css', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: '' }); }
  });
  await page.goto('http://fixture.invalid/');
  await page.locator('#usage-grid [data-date]').last().waitFor();
  const last = page.locator('#usage-grid [data-date]').last();
  await last.click();
  await page.locator('#usage-curve-body').waitFor();
  await page.waitForFunction(() => document.querySelector('#usage-curve-body')?.children.length > 0);
  const result = await page.evaluate(() => {
    const cells = [...document.querySelectorAll('#usage-grid [data-date]')];
    const last = cells.at(-1);
    const labels = [...document.querySelectorAll('#usage-months span')];
    const gridCells = [...document.querySelectorAll('#usage-grid .ud-cell')];
    const drift = Math.max(...labels.map((label, i) => Math.abs(label.getBoundingClientRect().left - gridCells[i * 7].getBoundingClientRect().left)));
    const keys = cells.map(cell => cell.dataset.date);
    const consecutive = keys.every((key, i) => !i || Date.parse(key) - Date.parse(keys[i - 1]) === 86400000);
    const weekdays = cells.every(cell => new Date(`${cell.dataset.date}T00:00:00`).getDay() === gridCells.indexOf(cell) % 7);
    return { date: last.dataset.date, level: last.className, drift, consecutive, weekdays, curve: !!document.querySelector('#usage-curve-body svg'), today: document.querySelector('#usage-summary')?.innerText };
  });
  await page.screenshot({ path: join(out, `${phase}-${date}-${zone.replace('/', '-')}-${scheme}.png`), fullPage: true });
  const ok = result.date === date && result.level.includes('level-4') && result.drift < 1 && result.consecutive && result.weekdays && result.curve && result.today.includes('1.9');
  console.log(`${date} ${zone} ${scheme}: ${ok ? 'PASS' : 'FAIL'} ${JSON.stringify(result)}`);
  if (!ok) failures++;
  await context.close();
}
await browser.close();
if (failures) process.exitCode = 1;
