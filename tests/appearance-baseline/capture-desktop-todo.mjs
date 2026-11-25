import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { bootTodoFace, openPanel } from '../_todo-face-harness.mjs';

const [out, lightShot, darkShot] = process.argv.slice(2);
if (!out || !lightShot || !darkShot) throw new Error('用法：node capture-desktop-todo.mjs <基线.json> <亮图.png> <暗图.png>');
const panel = JSON.parse(readFileSync(new URL('./panels/desktop-todo.json', import.meta.url)));
const browser = await chromium.launch({ headless: true });
try {
  const variants = {};
  for (const [theme, screenshot] of [['light', lightShot], ['dark', darkShot]]) {
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, colorScheme: theme, reducedMotion: 'reduce' });
    await bootTodoFace(page);
    await openPanel(page);
    const elements = {};
    for (const item of panel.elements) {
      const locator = page.locator(item.selector).first();
      await locator.waitFor({ state: 'visible', timeout: 5000 });
      elements[item.id] = await locator.evaluate((el, spec) => {
        const cs = getComputedStyle(el), styles = {};
        for (const property of spec.properties) styles[property] = cs[property];
        return { selector: spec.selector, styles };
      }, item);
    }
    await page.screenshot({ path: screenshot, animations: 'disabled', caret: 'hide', scale: 'css' });
    variants[theme] = { viewport: { width: 1280, height: 800, dpr: 1 }, elements };
    await page.close();
  }
  const baseline = { approved: false, approvedBy: null, approvedAt: null, sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(), panel: panel.panel, viewportNA: { width: 390, reason: '手机层隐藏桌面待办 #task-list（css/mobileLayer.css:3205）' }, browser: browser.version(), variants };
  writeFileSync(out, JSON.stringify(baseline, null, 2) + '\n');
  console.log(`已采集 ${out}，每主题 ${panel.elements.length} 个可见元素`);
} finally { await browser.close(); }
