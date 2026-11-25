import { chromium } from 'playwright';
import { writeFileSync } from 'node:fs';
import { bootTodoFace, openPanel } from '../_todo-face-harness.mjs';

const [output, screenshot] = process.argv.slice(2);
if (!output || !screenshot) throw new Error('用法：node desktop-todo-probe.mjs <报告.json> <截图.png>');
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, colorScheme: 'dark', reducedMotion: 'reduce' });
  await bootTodoFace(page);
  await openPanel(page);
  const result = await page.locator('#task-list').evaluate(root => {
    const nodes = [...root.querySelectorAll('*')].filter(el => { const r = el.getBoundingClientRect(); const s = getComputedStyle(el); return r.width > 0 && r.height > 0 && s.visibility === 'visible' && s.display !== 'none'; });
    return nodes.map(el => ({ tag: el.tagName.toLowerCase(), class: typeof el.className === 'string' ? el.className : '', text: el.textContent.trim().slice(0, 30) }));
  });
  await page.screenshot({ path: screenshot, animations: 'disabled' });
  writeFileSync(output, JSON.stringify({ route: '桌面待办胶囊点击展开', count: result.length, visibleNodes: result }, null, 2) + '\n');
  console.log(`可见节点 ${result.length}；报告 ${output}`);
} finally { await browser.close(); }
