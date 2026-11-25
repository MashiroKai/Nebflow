import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { loadPlaywright, launchBrowser, createSession, settle } from '../../scripts/lib/visual-harness.mjs';

const [panelPath, output, lightShot, darkShot] = process.argv.slice(2);
if (!darkShot) throw new Error('用法: collect.mjs <面板.json> <基线.json> <亮图> <暗图>');
const panel = JSON.parse(readFileSync(panelPath, 'utf8'));
if (!Array.isArray(panel.openSteps) || !panel.openSteps.length) throw new Error(`${panel.panel}: 缺少 openSteps，无法到达面板`);
const { mod } = loadPlaywright();
const { browser, version } = await launchBrowser(mod);
try {
  const variants = {};
  for (const [theme, screenshot] of [['light', lightShot], ['dark', darkShot]]) {
    const { width, height } = panel.viewportGroups[0];
    const session = await createSession(browser, { width, height, colorScheme: theme, clock: 1793940000000, apiStubs: { '/api/nf-authcheck': { status: 204 }, '/api/plugins': { body: { plugins: [], rejected: [] } }, '/api/neblink/status': { body: { loggedIn: true, device: { name: 'fixture' }, peers: [] } } } });
    try {
      if (panel.fixtures?.includes('friends')) await session.page.addInitScript(() => {
        localStorage.setItem('fm_api_mock', '1');
        localStorage.setItem('fm_api_mock_seed', JSON.stringify({ self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' }, users: [], friends: Array.from({ length: 30 }, (_, i) => ({ userId: `u-${i}`, neblinkId: `friend-${i}`, name: `联系人${i}`, avatarUrl: '', since: Date.now() - 86400000 })), incoming: [], outgoing: [], conversations: [], messages: {} }));
      });
      await session.boot({ config: { llm: { providers: {} }, features: { friends: true } } });
      const page = session.page;
      for (const step of panel.openSteps) {
        try {
          if (step.action === 'click') await page.locator(step.selector).first().click({ timeout: 5000 });
          else if (step.action === 'wait') await page.locator(step.selector).first().waitFor({ state: 'visible', timeout: 5000 });
          else if (step.action === 'fill') await page.locator(step.selector).first().fill(step.value, { timeout: 5000 });
          else if (step.action === 'agentsFixture') await page.evaluate(async () => {
            const state = (await import('/js/state.js')).default;
            state.sessionBgAgents['visual-root'] = { 'delegate-fixture-1': { name: 'delegate-fixture-1', kind: 'Delegate', sessionId: 'delegate-fixture-1', status: 'Processing', startedAt: Date.now() - 120000, parentSessionId: 'visual-root', task: '测试任务' } };
            state.updateBgAgentIndicator();
          });
          else if (step.action === 'canvasFixture') await page.evaluate(async () => { (await import('/js/canvas.js')).openTab('doc-1', '测试文档', { type: 'generic' }); });
          else if (step.action === 'todoFixture') await page.evaluate(async () => {
            const { todoStore } = await import('/js/todoStore.js');
            todoStore.__seed({ tagList: [{ id: 'tag-work', name: '工作', color: 'rgb(59,130,246)' }], itemList: [{ id: 'f-1', tagId: 'tag-work', title: '修登录态过期', due: Date.now() + 3600000, priority: 3, createdAt: Date.now() - 3600000 }] });
            const { renderTodoTaskList } = await import('/js/todoFace.js');
            renderTodoTaskList('visual-root');
          });
          else throw new Error(`未知操作 ${step.action}`);
        } catch (error) { throw new Error(`${panel.panel} ${theme} openStep ${JSON.stringify(step)}: ${error.message}`); }
      }
      await settle(page, 300);
      const elements = {};
      for (const item of panel.elements) {
        const loc = page.locator(item.selector).first();
        try { await loc.waitFor({ state: 'visible', timeout: 5000 }); }
        catch (error) { throw new Error(`${panel.panel} ${theme} 元素 ${item.id} (${item.selector}) 不可见: ${error.message}`); }
        elements[item.id] = await loc.evaluate((el, spec) => {
          const style = getComputedStyle(el), styles = {};
          for (const key of spec.properties) styles[key] = style[key];
          const r = el.getBoundingClientRect();
          return { selector: spec.selector, styles, ...(spec.box ? { box: { x: r.x, y: r.y, width: r.width, height: r.height } } : {}) };
        }, item);
      }
      await page.screenshot({ path: screenshot, animations: 'disabled', caret: 'hide', scale: 'css' });
      variants[theme] = { viewport: { width, height, dpr: 1 }, elements };
      if (session.errors.length) throw new Error(`页面错误: ${session.errors.join('; ')}`);
    } finally { await session.close(); }
  }
  const baseline = { approved: false, approvedBy: null, approvedAt: null, sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(), panel: panel.panel, viewportNA: panel.viewportNA, browser: version, variants };
  writeFileSync(output, JSON.stringify(baseline, null, 2) + '\n');
  console.log(`${panel.panel}: 两档采集成功`);
} finally { await browser.close(); }
